package ase.core

import cats.data.State
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.scalacheck.Gen
import org.scalacheck.rng.Seed

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{FileVisitResult, Files, LinkOption, Path, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Hilfen für Execute-Tests: eine reine Welt im Speicher (für Tests gegen F[_] ohne Dateisystem, auf allen
  * Plattformen), die Gesetze über das Record-Log und Arbeitsverzeichnisse auf der Platte.
  */
object ExecSupport:
  type Files_ = Map[String, String]
  type W[A] = State[Files_, A]

  /** Werkzeuge im Speicher: dieselbe syntaktische Prüfung wie LocalTools und Replay (ToolCheck), ohne Symlinks. */
  def fakeTools(policies: List[ArgPolicy]): Tools[W] = (call, env) =>
    State { files =>
      def dirs: Set[String] = files.keySet.flatMap(k => k.split('/').toVector.inits.drop(1).map(RelPath.show)) + "."
      // nach ToolCheck.call gültig
      def norm(raw: String): String = ToolCheck.path(raw, env).map(RelPath.show).getOrElse(raw)
      def parent(p: String): String = RelPath.show(p.split('/').toVector.init)
      def ok(t: List[String], r: StepResult, note: String = "") = Reply(t, Right(r), note)
      def err(t: List[String], e: ToolError) = Reply(t, Left(e), "")
      ToolCheck.call(call, env, policies) match
        case Left(e) => (files, err(Nil, e))
        case Right(()) =>
          call match
            case Call.ReadFile(raw) =>
              val p = norm(raw)
              (files, files.get(p).fold(err(List(p), ToolError.NotFound(raw)))(c => ok(List(p), StepResult(None, Some(c)))))
            case Call.ListDir(raw) =>
              val p = norm(raw)
              if !dirs(p) then (files, err(List(p), ToolError.NotFound(raw)))
              else
                val inDir = (files.keySet ++ dirs).filter(k => k != p && parent(k) == p).toList.sorted
                (files, ok(List(p), StepResult(None, Some(inDir.mkString("\n")))))
            case Call.WriteFile(raw, content) =>
              val p = norm(raw)
              if files.contains(p) || dirs(p) then (files, err(List(p), ToolError.AlreadyExists(raw)))
              else if !dirs(parent(p)) then (files, err(List(p), ToolError.ParentMissing(raw)))
              else (files.updated(p, content), ok(List(p), StepResult(None, None)))
            case Call.EditFile(raw, old, rep) =>
              val p = norm(raw)
              if old.isEmpty then (files, err(Nil, ToolError.EmptyExpectedOld(raw)))
              else
                files.get(p) match
                  case None => (files, err(List(p), ToolError.NotFound(raw)))
                  case Some(c) =>
                    Iterator.iterate(c.indexOf(old))(i => c.indexOf(old, i + 1)).takeWhile(_ >= 0).size match
                      case 0 => (files, err(List(p), ToolError.CasMissing(raw)))
                      case 1 => (files.updated(p, c.replace(old, rep)), ok(List(p), StepResult(None, None)))
                      case n => (files, err(List(p), ToolError.CasAmbiguous(raw, n)))
            case Call.MoveFile(from, to) =>
              val (f, t) = (norm(from), norm(to))
              val tt = List(f, t)
              if !files.contains(f) then (files, err(tt, ToolError.NotFound(from)))
              else if files.contains(t) || dirs(t) then (files, err(tt, ToolError.AlreadyExists(to)))
              else if !dirs(parent(t)) then (files, err(tt, ToolError.ParentMissing(to)))
              else (files.removed(f).updated(t, files(f)), ok(tt, StepResult(None, None)))
            case Call.Shell(cmd, args) =>
              ToolCheck.shell(cmd, args, env, policies) match
                case Left(e)   => (files, err(Nil, e))
                case Right(sc) => (files, ok(sc.touched, StepResult(Some(0), Some(s"$cmd ok")), "1 Zeile"))
            case Call.FileExists(raw) =>
              val p = norm(raw)
              (files, ok(List(p), StepResult(None, Some((files.contains(p) || dirs(p)).toString))))
    }

  def ports[F[_]: cats.Applicative](tools: Tools[F], model: FillRequest => ModelReply, ask: String => Option[String]): Ports[F] =
    Ports(
      tools,
      r => cats.Applicative[F].pure(model(r)),
      q => cats.Applicative[F].pure(ask(q)),
      Clock.never[F],
      Recorder.none[F]
    )

  def submission(t: Term): String = Json.obj("term" -> Codec.term(t, withOrigin = false)).noSpaces

  def approve(term: Term, root: Envelope, check: CheckConfig): ApprovedTerm =
    val checked = DryRun.plan(term, root, check).fold(v => throw new AssertionError(v.toList.map(_.render).mkString("\n")), identity)
    Approval.approve(checked, checked.termHash).fold(e => throw new AssertionError(e.toString), identity)

  // ---------- Gesetze über das Record-Log

  private def holeEnvelopes(events: Vector[Event]): Map[StepId, Envelope] =
    events.collect { case Event.Hole(at, _, env) => at -> env }.toMap

  /** Die Hüllen, unter denen ein Schritt lief: Wurzel und alle umschließenden Löcher. */
  private def envelopesOf(at: StepId, events: Vector[Event], root: Envelope): List[Envelope] =
    val holes = holeEnvelopes(events)
    root :: at.holes.map(holes)

  private def prefixOf(e: Envelope): Vector[String] = RelPath.parse(e.pathPrefix).getOrElse(Vector("\u0000"))

  /** Verstöße eines Laufs gegen die zentrale Invariante, aus dem Log allein: jeder Syscall (touched nicht leer) liegt
    * unter Wurzel- und allen umschließenden Loch-Hüllen, nur erlaubte Operationen und Befehle, nichts außerhalb.
    */
  def escapes(events: Vector[Event], root: Envelope): List[String] =
    events.toList.collect { case Event.Step(at, call, reply) if reply.touched.nonEmpty => (at, call, reply) }.flatMap { (at, call, reply) =>
      val envs = envelopesOf(at, events, root)
      val paths = for
        t <- reply.touched
        e <- envs
        if t.startsWith("/") || t.split('/').contains("..") || !RelPath.isUnder(t, prefixOf(e))
      yield s"${at.show}: $t außerhalb von ${e.pathPrefix}"
      val ops = call.op.toList.flatMap(op => envs.filterNot(_.allowedOps(op)).map(e => s"${at.show}: $op nicht in ${e.allowedOps}"))
      val shell = call match
        case Call.Shell(cmd, args) =>
          envs.filterNot(_.shellAllowlist.exists(_.allows(cmd, args))).map(_ => s"${at.show}: $cmd ${args.mkString(" ")} nicht erlaubt")
        case _ => Nil
      paths ++ ops ++ shell
    }

  /** Scope-Gesetz (M1): jede Scope-Zeile eines FillRequest stammt aus einem Eintrag, dessen Pfade unter dem Präfix
    * des Lochs liegen. Pfadlos sind Shell-Aufrufe ohne ausdrücklichen Pfad (D#30), Antworten auf Ask und Ergebnisse
    * früherer Löcher; sonst zählen die aufgelösten Pfade aus dem Log (touched), nicht die Pfade im Term.
    */
  def scopeLeaks(requests: Vector[FillRequest], events: Vector[Event], policies: List[ArgPolicy]): List[String] =
    requests.toList.flatMap { r =>
      val prefix = prefixOf(r.envelope)
      r.scope.toList.flatMap { line =>
        // FileExists ist nie ein Eintrag; seine Position teilt er mit dem ersten Schritt des Wenn-Zweigs
        val steps = events.collect { case Event.Step(at, c, reply @ Reply(_, Right(_), _)) if at.show == line.at && !c.isInstanceOf[Call.FileExists] => (c, reply) }
        val pathless = events.exists {
          case Event.Asked(at, _, Some(_)) => at.show == line.at
          case Event.HoleDone(at, _)       => at.show == line.at
          case _                           => false
        }
        if steps.isEmpty && !pathless then List(s"${r.callId}: Scope-Zeile ${line.at} ohne Eintrag im Log")
        else
          steps.toList.flatMap { (c, reply) =>
            val explicit = c match
              case Call.Shell(cmd, args) => Scope.shellPaths(cmd, args, policies).nonEmpty
              case _                     => true
            if !explicit then Nil
            else
              reply.touched.filterNot(RelPath.isUnder(_, prefix)).map(t => s"${r.callId}: Scope-Zeile ${line.at} trägt $t, außerhalb von ${r.envelope.pathPrefix}")
          }
      }
    }

  /** Änderungen im Arbeitsverzeichnis, unabhängig vom Log festgestellt (M5): jede geänderte, neue oder entfernte
    * Stelle muss unter dem pathPrefix aller Hüllen eines gelungenen schreibenden Schritts liegen.
    */
  def unexplainedChanges(changed: Set[String], events: Vector[Event], root: Envelope): List[String] =
    val writers = events.collect { case Event.Step(at, c, Reply(_, Right(_), _)) if c.writes => envelopesOf(at, events, root) }
    changed.toList.sorted.filterNot(p => writers.exists(_.forall(e => RelPath.isUnder(p, prefixOf(e)))))

  // ---------- Modell, das für jedes Loch würfelt: gültige Teilterme und Ausbruchsversuche

  def adversarialModel(seed: Long, extraAttacks: Envelope => List[Gen[Term]] = _ => Nil): FillRequest => ModelReply = r =>
    val o = Origin.Model(r.callId)
    val env = r.envelope
    val valid = Gens.validTerm(env, r.remainingSteps, 2, o).map(t => ModelReply.Answer(submission(t), 10))
    val attack = AdversarialFill.gen(env).map((_, t) => ModelReply.Answer(submission(t), 10))
    val runtime = extraAttacks(env).map(_.map(t => ModelReply.Answer(submission(t), 10)))
    val junk = Gen.oneOf(
      ModelReply.Answer("kein JSON", 5),
      ModelReply.Answer("""{"term": {"type": "Done", "summary": "x", "origin": "Human"}}""", 5),
      ModelReply.Failed("kein submit_term", 5)
    )
    val all = List(4 -> valid, 3 -> attack, 1 -> junk) ++ runtime.map(3 -> _)
    val h = (seed, r.callId, r.at, r.attempt).hashCode.toLong
    Gen.frequency(all*).pureApply(Gen.Parameters.default, Seed(h))

  // ---------- Arbeitsverzeichnisse auf der Platte

  def tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

  def deleteTree(p: Path): Unit =
    if Files.exists(p, LinkOption.NOFOLLOW_LINKS) then
      Files.walkFileTree(
        p,
        new SimpleFileVisitor[Path]:
          override def visitFile(f: Path, a: BasicFileAttributes): FileVisitResult =
            Files.delete(f); FileVisitResult.CONTINUE
          override def postVisitDirectory(d: Path, e: java.io.IOException): FileVisitResult =
            Files.delete(d); FileVisitResult.CONTINUE
      )

  /** Kopiert ein Fixture; Skripte (*.sh) ausführbar wie im Repo (D#29). */
  def copyTree(from: Path, to: Path): Unit =
    Files.walk(from).iterator.asScala.foreach { src =>
      val dst = to.resolve(from.relativize(src).toString)
      if Files.isDirectory(src) then Files.createDirectories(dst)
      else
        Files.copy(src, dst)
        if dst.toString.endsWith(".sh") then Files.setPosixFilePermissions(dst, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

  /** Dateibaum als relativer Pfad → Inhalt (nur normale Dateien). */
  def tree(root: Path): Map[String, Vector[Byte]] =
    Files.walk(root).iterator.asScala
      .filter(p => Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
      .map(p => root.relativize(p).toString -> Files.readAllBytes(p).toVector)
      .toMap

  private def sha(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** Alles im Baum, ohne Symlinks zu folgen: Verzeichnisse, Symlinks mit Ziel, Dateien mit Inhalt und Rechten. */
  def snapshot(root: Path): Map[String, String] =
    Files.walk(root).iterator.asScala
      .filter(_ != root)
      .map { p =>
        val kind =
          if Files.isSymbolicLink(p) then s"link ${Files.readSymbolicLink(p)}"
          else if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then "dir"
          else s"file ${sha(Files.readAllBytes(p))} ${PosixFilePermissions.toString(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS))}"
        root.relativize(p).toString -> kind
      }
      .toMap

  def changes(before: Map[String, String], after: Map[String, String]): Set[String] =
    (before.keySet ++ after.keySet).filter(k => before.get(k) != after.get(k))

  def runIO[A](io: IO[A]): A = io.unsafeRunSync()

  def sh(dir: Path, argv: String*): (Int, String) =
    val pb = new ProcessBuilder(argv.asJava).directory(dir.toFile).redirectErrorStream(true)
    val p = pb.start()
    p.getOutputStream.close()
    val out = new String(p.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    (p.waitFor(), out)
