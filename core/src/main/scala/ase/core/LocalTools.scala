package ase.core

import cats.effect.Sync
import cats.syntax.all.*

import java.io.{IOException, InputStream, InputStreamReader}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path, StandardCopyOption, StandardOpenOption}
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Werkzeuge am Rand, unter Linux (D#29): Dateisystem und Prozesse, mit Pfad- und Shell-Prüfung zur Laufzeit.
  * Pfade entstehen nur aus den geparsten Segmenten, nie aus dem Rohtext.
  */
final class LocalTools[F[_]] private (fs: LocalTools.Fs)(using F: Sync[F]) extends Tools[F]:
  def call(call: Call, envelope: Envelope): F[Reply] = F.blocking(fs.run(call, envelope))

object LocalTools:
  /** Prüft Plattform und Arbeitsverzeichnis; das Arbeitsverzeichnis wird einmal mit toRealPath aufgelöst. */
  def open[F[_]: Sync](workspace: Path, config: ExecConfig): F[Either[ExecuteError, Tools[F]]] =
    Sync[F].blocking {
      for
        _ <- Platform.check(Platform.current)
        root <-
          try
            val r = workspace.toRealPath()
            Either.cond(Files.isDirectory(r), r, ExecuteError.InvalidWorkspace(workspace.toString, "kein Verzeichnis"))
          catch case NonFatal(e) => Left(ExecuteError.InvalidWorkspace(workspace.toString, e.toString))
      yield LocalTools[F](Fs(root, config))
    }

  private val ReaderGraceMillis = 2000L
  private val KillWaitSeconds = 5L

  /** git startet gehärtet: keine fremden Programme über Konfiguration (fsmonitor, Hooks, externe diff). Das Modell
    * sieht diese Argumente nicht; die Prüfung gilt den Argumenten aus dem Term.
    */
  private val GitGlobal = List("-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null", "-c", "diff.external=", "--no-pager")
  private val GitNoExternal = List("--no-ext-diff", "--no-textconv")
  private val GitEnv = Map("GIT_CONFIG_NOSYSTEM" -> "1", "GIT_CONFIG_GLOBAL" -> "/dev/null")

  private[core] def gitArgs(args: List[String]): List[String] = args match
    case sub :: rest if sub == "diff" || sub == "log" => GitGlobal ++ (sub :: GitNoExternal) ++ rest
    case _                                           => GitGlobal ++ args

  private[core] final class Fs(root: Path, config: ExecConfig):
    private val nofollow = LinkOption.NOFOLLOW_LINKS

    private def at(segments: Vector[String]): Path = segments.foldLeft(root)(_.resolve(_))

    private def rel(p: Path): String =
      val r = root.relativize(p).toString
      if r.isEmpty then "." else r

    private def io(e: Throwable): ToolError =
      // keine absoluten Pfade der Engine im Log oder beim Modell
      ToolError.Io(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}".replace(root.toString, "."))

    private def fail(touched: List[String], e: ToolError): Reply = Reply(touched, Left(e), "")

    private def guarded(touched: List[String])(body: => Reply): Reply =
      try body
      catch case NonFatal(e) => fail(touched, io(e))

    // ---------- Pfadprüfung

    private def segments(raw: String, env: Envelope): Either[ToolError, (Vector[String], Path)] =
      for
        prefix <- RelPath.parse(env.pathPrefix).leftMap(ToolError.InvalidPath(env.pathPrefix, _))
        segs <- RelPath.parse(raw).leftMap(ToolError.InvalidPath(raw, _))
        _ <- Either.cond(segs.startsWith(prefix), (), ToolError.OutsidePrefix(raw, env.pathPrefix))
      yield (segs, at(prefix))

    /** Tiefstes existierendes Elternverzeichnis (oder der Pfad selbst) mit toRealPath, Rest normalisiert angehängt.
      * toRealPath nie auf etwas, das nicht existiert.
      */
    private def real(segs: Vector[String], raw: String): Either[ToolError, Path] =
      val k = (segs.size to 0 by -1).find(i => Files.exists(at(segs.take(i)), nofollow)).getOrElse(0)
      try Right(segs.drop(k).foldLeft(at(segs.take(k)).toRealPath())(_.resolve(_)).normalize())
      catch
        case _: NoSuchFileException => Left(ToolError.Unresolvable(raw))
        case NonFatal(e)            => Left(io(e))

    private def inside(p: Path, raw: String, prefixPath: Path, env: Envelope): Either[ToolError, Path] =
      if !p.startsWith(root) then Left(ToolError.OutsideWorkspace(raw))
      else if !p.startsWith(prefixPath) then Left(ToolError.OutsidePrefix(raw, env.pathPrefix))
      else Right(p)

    /** Folgt Symlinks auch im letzten Teil (lesen, ändern, Existenz, Shell-Argumente). */
    def resolve(raw: String, env: Envelope): Either[ToolError, Path] =
      segments(raw, env).flatMap((segs, prefixPath) => real(segs, raw).flatMap(inside(_, raw, prefixPath, env)))

    /** Löst nur das Elternverzeichnis auf; der letzte Teil bleibt, wie er ist (anlegen, verschieben). */
    def resolveEntry(raw: String, env: Envelope): Either[ToolError, Path] =
      segments(raw, env).flatMap { (segs, prefixPath) =>
        if segs.isEmpty then Left(ToolError.InvalidPath(raw, "kein Dateiname"))
        else real(segs.init, raw).map(_.resolve(segs.last)).flatMap(inside(_, raw, prefixPath, env))
      }

    // ---------- Operationen

    def run(c: Call, env: Envelope): Reply =
      try
        c match
          case Call.ReadFile(p)         => read(p, env)
          case Call.ListDir(p)          => list(p, env)
          case Call.WriteFile(p, text)  => write(p, text, env)
          case Call.EditFile(p, old, r) => edit(p, old, r, env)
          case Call.MoveFile(f, t)      => move(f, t, env)
          case Call.Shell(cmd, args)    => shell(cmd, args, env)
          case Call.FileExists(p)       => exists(p, env)
      catch case NonFatal(e) => fail(Nil, io(e))

    private def excerpt(): Marshal.Excerpt = Marshal.Excerpt(config.maxBytesPerStep, config.excerptHeadLines, config.excerptTailLines)

    private def lines(n: Long): String = if n == 1 then "1 Zeile" else s"$n Zeilen"

    /** Liest in Stücken; große Dateien und Ausgaben nicht ganz in den Heap. */
    private def drain(in: InputStream, ex: Marshal.Excerpt): Unit =
      val reader = new InputStreamReader(in, UTF_8)
      val buf = new Array[Char](8192)
      @tailrec def loop(): Unit =
        val n = reader.read(buf)
        if n >= 0 then
          ex.synchronized(ex.add(buf, n))
          loop()
      try loop()
      catch case _: IOException => ()
      finally
        try reader.close()
        catch case _: IOException => ()

    private def read(raw: String, env: Envelope): Reply =
      resolve(raw, env) match
        case Left(e) => fail(Nil, e)
        case Right(p) =>
          val t = List(rel(p))
          guarded(t) {
            if !Files.exists(p) then fail(t, ToolError.NotFound(raw))
            else if !Files.isRegularFile(p) then fail(t, ToolError.NotAFile(raw))
            else
              val ex = excerpt()
              drain(Files.newInputStream(p), ex)
              val r = ex.result
              Reply(t, Right(StepResult(None, Some(r.text))), lines(r.lines) + (if r.truncated then ", gekürzt" else ""))
          }

    private def list(raw: String, env: Envelope): Reply =
      resolve(raw, env) match
        case Left(e) => fail(Nil, e)
        case Right(p) =>
          val t = List(rel(p))
          guarded(t) {
            if !Files.exists(p) then fail(t, ToolError.NotFound(raw))
            else if !Files.isDirectory(p) then fail(t, ToolError.NotADirectory(raw))
            else
              val stream = Files.list(p)
              val names =
                try stream.iterator.asScala.map(e => e.getFileName.toString + (if Files.isDirectory(e, nofollow) then "/" else "")).toVector.sorted
                finally stream.close()
              val text = names.mkString("\n")
              val r = Marshal.excerpt(text, config.maxBytesPerStep, config.excerptHeadLines, config.excerptTailLines)
              Reply(t, Right(StepResult(None, Some(r.text))), s"${names.size} Einträge")
          }

    private def write(raw: String, content: String, env: Envelope): Reply =
      resolveEntry(raw, env) match
        case Left(e) => fail(Nil, e)
        case Right(p) =>
          val t = List(rel(p))
          guarded(t) {
            if Files.exists(p, nofollow) then fail(t, ToolError.AlreadyExists(raw))
            else if !Files.isDirectory(p.getParent) then fail(t, ToolError.ParentMissing(raw))
            else
              // CREATE_NEW: nur neue Dateien, folgt keinem Symlink am Ziel
              Files.write(p, content.getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, nofollow)
              Reply(t, Right(StepResult(None, None)), s"neu, ${lines(content.count(_ == '\n').toLong + (if content.isEmpty || content.endsWith("\n") then 0 else 1))}")
          }

    /** Vorkommen, auch überlappend: "genau einmal" ist streng gemeint. */
    private def occurrences(text: String, sub: String): Int =
      Iterator.iterate(text.indexOf(sub))(i => text.indexOf(sub, i + 1)).takeWhile(_ >= 0).size

    private def edit(raw: String, expectedOld: String, replacement: String, env: Envelope): Reply =
      if expectedOld.isEmpty then fail(Nil, ToolError.EmptyExpectedOld(raw))
      else
        resolve(raw, env) match
          case Left(e) => fail(Nil, e)
          case Right(p) =>
            val t = List(rel(p))
            guarded(t) {
              if !Files.exists(p) then fail(t, ToolError.NotFound(raw))
              else if !Files.isRegularFile(p) then fail(t, ToolError.NotAFile(raw))
              else if Files.size(p) > config.maxEditBytes then fail(t, ToolError.TooLarge(raw, Files.size(p), config.maxEditBytes))
              else
                val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                val text =
                  try Right(decoder.decode(ByteBuffer.wrap(Files.readAllBytes(p))).toString)
                  catch case _: CharacterCodingException => Left(ToolError.NotText(raw))
                text match
                  case Left(e) => fail(t, e)
                  case Right(s) =>
                    occurrences(s, expectedOld) match
                      case 0 => fail(t, ToolError.CasMissing(raw))
                      case 1 =>
                        val i = s.indexOf(expectedOld)
                        val updated = s.substring(0, i) + replacement + s.substring(i + expectedOld.length)
                        Files.write(p, updated.getBytes(UTF_8), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, nofollow)
                        val delta = replacement.count(_ == '\n') - expectedOld.count(_ == '\n')
                        Reply(t, Right(StepResult(None, None)), s"CAS ok, ${if delta >= 0 then "+" else ""}$delta Zeilen")
                      case n => fail(t, ToolError.CasAmbiguous(raw, n))
            }

    private def move(from: String, to: String, env: Envelope): Reply =
      (resolveEntry(from, env), resolveEntry(to, env)).tupled match
        case Left(e) => fail(Nil, e)
        case Right((f, d)) =>
          val t = List(rel(f), rel(d))
          guarded(t) {
            if Files.isSymbolicLink(f) then fail(t, ToolError.SymlinkNotAllowed(from))
            else if !Files.exists(f, nofollow) then fail(t, ToolError.NotFound(from))
            else if !Files.isRegularFile(f, nofollow) then fail(t, ToolError.NotAFile(from))
            else if Files.exists(d, nofollow) then fail(t, ToolError.AlreadyExists(to))
            else if !Files.isDirectory(d.getParent) then fail(t, ToolError.ParentMissing(to))
            else
              Files.move(f, d, StandardCopyOption.ATOMIC_MOVE)
              Reply(t, Right(StepResult(None, None)), "verschoben")
          }

    private def exists(raw: String, env: Envelope): Reply =
      resolve(raw, env) match
        case Left(e) => fail(Nil, e)
        case Right(p) =>
          val t = List(rel(p))
          guarded(t)(Reply(t, Right(StepResult(None, Some(Files.exists(p).toString))), ""))

    // ---------- Shell

    /** "./x" relativ zum Arbeitsverzeichnis und unter dem pathPrefix; sonst ein Name aus PATH. */
    private def program(cmd: String, env: Envelope): Either[ToolError, (Path, List[String])] =
      if cmd.startsWith("./") then
        resolve(cmd.drop(2), env).flatMap(p =>
          Either.cond(Files.isRegularFile(p) && Files.isExecutable(p), (p, List(rel(p))), ToolError.ProgramNotFound(cmd))
        )
      else if cmd.isEmpty || cmd.contains('/') || cmd.contains('\\') then Left(ToolError.ProgramNotFound(cmd))
      else
        sys.env
          .get("PATH")
          .toList
          .flatMap(_.split(':').toList)
          .filter(_.startsWith("/"))
          .map(dir => Path.of(dir).resolve(cmd))
          .find(p => Files.isRegularFile(p) && Files.isExecutable(p))
          .map(p => (p, Nil))
          .toRight(ToolError.ProgramNotFound(cmd))

    private def shell(cmd: String, args: List[String], env: Envelope): Reply =
      val checked =
        for
          _ <- Either.cond(env.shellAllowlist.exists(_.allows(cmd, args)), (), ToolError.ShellNotAllowed(cmd, args))
          policy <- ArgPolicy.find(config.check.argPolicies, cmd, args).toRight(ToolError.ShellNotAllowed(cmd, args))
          parsed = ArgPolicy.parse(policy, args)
          _ <- parsed.rejected.headOption.map((a, r) => ToolError.ShellArgRejected(cmd, a, r)).toLeft(())
          // auch zur Laufzeit: jeder Pfad, ohne Pfad das Arbeitsverzeichnis (D#27)
          touched <- parsed.touched.traverse(resolve(_, env))
          normalized <- parsed.pathAt.traverse(i => RelPath.parse(args(i)).leftMap(ToolError.InvalidPath(args(i), _)).map(i -> RelPath.show(_)))
          exe <- program(cmd, env)
        yield
          val byIndex = normalized.toMap
          val finalArgs = args.zipWithIndex.map((a, i) => byIndex.getOrElse(i, a))
          (exe, touched, finalArgs)
      checked match
        case Left(e) => fail(Nil, e)
        case Right(((exe, exeTouched), paths, finalArgs)) =>
          val t = (exeTouched ++ paths.map(rel)).distinct
          guarded(t)(process(cmd, exe, finalArgs, t))

    private def process(cmd: String, exe: Path, args: List[String], touched: List[String]): Reply =
      val git = cmd == "git"
      val argv = exe.toString :: (if git then gitArgs(args) else args)
      val pb = new ProcessBuilder(argv.asJava)
      pb.directory(root.toFile)
      pb.redirectErrorStream(true)
      val environment = pb.environment()
      environment.clear()
      config.shellEnv.flatMap(k => sys.env.get(k).map(k -> _)).foreach((k, v) => environment.put(k, v))
      if git then GitEnv.foreach((k, v) => environment.put(k, v))
      val proc = pb.start()
      // stdin leer
      proc.getOutputStream.close()
      val ex = excerpt()
      val reader = new Thread(() => drain(proc.getInputStream, ex))
      reader.setDaemon(true)
      reader.start()
      val finished = proc.waitFor(config.shellTimeout.toMillis, TimeUnit.MILLISECONDS)
      if !finished then killTree(proc)
      reader.join(ReaderGraceMillis)
      if reader.isAlive then
        // verwaiste Kindprozesse halten die Ausgabe offen
        proc.getInputStream.close()
        reader.join(ReaderGraceMillis)
      val out = ex.synchronized(ex.result)
      val note = if out.text.isBlank then "leer" else lines(out.lines) + (if out.truncated then ", gekürzt" else "")
      if finished then Reply(touched, Right(StepResult(Some(proc.exitValue), Some(out.text))), note)
      else fail(touched, ToolError.Timeout(cmd, config.shellTimeout.toSeconds, out.text))

    /** Bei Zeitüberschreitung samt Kindprozessen (D#29): erst die Nachfahren erfassen, dann alle beenden. */
    private def killTree(proc: Process): Unit =
      val descendants = proc.descendants().iterator().asScala.toList
      descendants.foreach(_.destroyForcibly())
      proc.destroyForcibly()
      proc.waitFor(KillWaitSeconds, TimeUnit.SECONDS)
      ()
