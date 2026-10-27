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
import scala.jdk.CollectionConverters.*

/** Hilfen für Execute-Tests: eine reine Welt im Speicher (für Tests gegen F[_] ohne Dateisystem, auf allen
  * Plattformen), das Gesetz über das Record-Log und Arbeitsverzeichnisse auf der Platte.
  */
object ExecSupport:
  type Files_ = Map[String, String]
  type W[A] = State[Files_, A]

  /** Werkzeuge im Speicher: dieselben syntaktischen Regeln wie LocalTools, ohne Symlinks. */
  def fakeTools(policies: List[ArgPolicy]): Tools[W] = (call, env) =>
    State { files =>
      def dirs: Set[String] = files.keySet.flatMap(k => k.split('/').toVector.inits.drop(1).map(RelPath.show)) + "."
      def norm(raw: String): Either[ToolError, String] =
        for
          prefix <- RelPath.parse(env.pathPrefix).left.map(ToolError.InvalidPath(env.pathPrefix, _))
          segs <- RelPath.parse(raw).left.map(ToolError.InvalidPath(raw, _))
          _ <- Either.cond(segs.startsWith(prefix), (), ToolError.OutsidePrefix(raw, env.pathPrefix))
        yield RelPath.show(segs)
      def parent(p: String): String = RelPath.show(p.split('/').toVector.init)
      def ok(t: List[String], r: StepResult, note: String = "") = Reply(t, Right(r), note)
      def err(t: List[String], e: ToolError) = Reply(t, Left(e), "")
      def with1(raw: String)(f: String => (Files_, Reply)): (Files_, Reply) =
        norm(raw).fold(e => (files, err(Nil, e)), f)
      call match
        case Call.ReadFile(raw) =>
          with1(raw)(p => (files, files.get(p).fold(err(List(p), ToolError.NotFound(raw)))(c => ok(List(p), StepResult(None, Some(c))))))
        case Call.ListDir(raw) =>
          with1(raw) { p =>
            if !dirs(p) then (files, err(List(p), ToolError.NotFound(raw)))
            else
              val inDir = (files.keySet ++ dirs).filter(k => k != p && parent(k) == p).toList.sorted
              (files, ok(List(p), StepResult(None, Some(inDir.mkString("\n")))))
          }
        case Call.WriteFile(raw, content) =>
          with1(raw) { p =>
            if files.contains(p) || dirs(p) then (files, err(List(p), ToolError.AlreadyExists(raw)))
            else if !dirs(parent(p)) then (files, err(List(p), ToolError.ParentMissing(raw)))
            else (files.updated(p, content), ok(List(p), StepResult(None, None)))
          }
        case Call.EditFile(raw, old, rep) =>
          if old.isEmpty then (files, err(Nil, ToolError.EmptyExpectedOld(raw)))
          else
            with1(raw) { p =>
              files.get(p) match
                case None => (files, err(List(p), ToolError.NotFound(raw)))
                case Some(c) =>
                  Iterator.iterate(c.indexOf(old))(i => c.indexOf(old, i + 1)).takeWhile(_ >= 0).size match
                    case 0 => (files, err(List(p), ToolError.CasMissing(raw)))
                    case 1 => (files.updated(p, c.replace(old, rep)), ok(List(p), StepResult(None, None)))
                    case n => (files, err(List(p), ToolError.CasAmbiguous(raw, n)))
            }
        case Call.MoveFile(from, to) =>
          (norm(from), norm(to)) match
            case (Left(e), _) => (files, err(Nil, e))
            case (_, Left(e)) => (files, err(Nil, e))
            case (Right(f), Right(t)) =>
              val tt = List(f, t)
              if !files.contains(f) then (files, err(tt, ToolError.NotFound(from)))
              else if files.contains(t) || dirs(t) then (files, err(tt, ToolError.AlreadyExists(to)))
              else if !dirs(parent(t)) then (files, err(tt, ToolError.ParentMissing(to)))
              else (files.removed(f).updated(t, files(f)), ok(tt, StepResult(None, None)))
        case Call.Shell(cmd, args) =>
          val checked =
            for
              _ <- Either.cond(env.shellAllowlist.exists(_.allows(cmd, args)), (), ToolError.ShellNotAllowed(cmd, args))
              policy <- ArgPolicy.find(policies, cmd, args).toRight(ToolError.ShellNotAllowed(cmd, args))
              parsed = ArgPolicy.parse(policy, args)
              _ <- parsed.rejected.headOption.map((a, r) => ToolError.ShellArgRejected(cmd, a, r)).toLeft(())
              touched <- parsed.touched.foldLeft[Either[ToolError, List[String]]](Right(Nil))((acc, p) => acc.flatMap(l => norm(p).map(l :+ _)))
            yield touched
          checked.fold(e => (files, err(Nil, e)), t => (files, ok(t, StepResult(Some(0), Some(s"$cmd ok")), "1 Zeile")))
        case Call.FileExists(raw) =>
          with1(raw)(p => (files, ok(List(p), StepResult(None, Some((files.contains(p) || dirs(p)).toString)))))
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

  // ---------- Gesetz über das Record-Log

  /** Verstöße eines Laufs gegen die zentrale Invariante, aus dem Log allein: jeder Syscall (touched nicht leer) liegt
    * unter Wurzel- und allen umschließenden Loch-Hüllen, nur erlaubte Operationen und Befehle, nichts außerhalb.
    */
  def escapes(events: Vector[Event], root: Envelope): List[String] =
    val holes = events.collect { case Event.Hole(at, _, env) => at -> env }.toMap
    events.toList.collect { case Event.Step(at, call, reply) if reply.touched.nonEmpty => (at, call, reply) }.flatMap { (at, call, reply) =>
      val envs = root :: at.holes.map(holes)
      val paths = for
        t <- reply.touched
        e <- envs
        prefix = RelPath.parse(e.pathPrefix).getOrElse(Vector("\u0000"))
        if t.startsWith("/") || t.split('/').contains("..") || !RelPath.isUnder(t, prefix)
      yield s"${at.show}: $t außerhalb von ${e.pathPrefix}"
      val ops = call.op.toList.flatMap(op => envs.filterNot(_.allowedOps(op)).map(e => s"${at.show}: $op nicht in ${e.allowedOps}"))
      val shell = call match
        case Call.Shell(cmd, args) =>
          envs.filterNot(_.shellAllowlist.exists(_.allows(cmd, args))).map(_ => s"${at.show}: $cmd ${args.mkString(" ")} nicht erlaubt")
        case _ => Nil
      paths ++ ops ++ shell
    }

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

  /** Dateibaum als relativer Pfad → Inhalt. */
  def tree(root: Path): Map[String, Vector[Byte]] =
    Files.walk(root).iterator.asScala
      .filter(p => Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
      .map(p => root.relativize(p).toString -> Files.readAllBytes(p).toVector)
      .toMap

  def runIO[A](io: IO[A]): A = io.unsafeRunSync()

  def sh(dir: Path, argv: String*): (Int, String) =
    val pb = new ProcessBuilder(argv.asJava).directory(dir.toFile).redirectErrorStream(true)
    val p = pb.start()
    p.getOutputStream.close()
    val out = new String(p.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    (p.waitFor(), out)
