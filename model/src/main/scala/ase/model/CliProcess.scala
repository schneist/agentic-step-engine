package ase.model

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Ein Modell über eine Kommandozeile (agy, claude; D#54, D#58): ein Prozess pro Anfrage, Argumente als Liste, keine
  * Shell (D#12). Jeder Aufruf bekommt unter workDir ein eigenes leeres Verzeichnis mit seinen Dateien, damit das
  * Programm nichts aus dem Workspace liest; danach wird es gelöscht. stdin kommt aus einer Datei, stdout und stderr
  * gehen in Dateien (keine Pipe, die volllaufen kann). Exit-Code 0 ist Status 200, sonst 502 (wird wiederholt).
  * Seit D#93: das Programm nur aus PATH oder als absoluter Pfad, nie unter dem Arbeitsverzeichnis; die Umgebung nur
  * aus PassEnv; bei Zeitüberschreitung enden auch die Kindprozesse.
  */
object CliProcess:
  /** Windows begrenzt die Kommandozeile auf 32767 Zeichen; Rest für Programm und übrige Argumente. */
  val MaxArgs = 30000

  /** Was ein Modellprozess von der Umgebung sieht (D#93), dazu PATH ohne relative Einträge und ohne Einträge unter
    * den gesperrten Verzeichnissen. Anmeldung und Konfiguration der CLIs liegen unter HOME; Schlüssel anderer Dienste
    * (AWS, ANTHROPIC_API_KEY) gehen nicht mit. WSL_* braucht die Interop, wenn das Programm eine Windows-Datei ist.
    */
  val PassEnv: List[String] = List(
    "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TMPDIR", "TZ",
    "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME", "XDG_RUNTIME_DIR",
    "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "https_proxy", "http_proxy", "no_proxy",
    "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS",
    "WSL_INTEROP", "WSL_DISTRO_NAME", "WSLENV",
    "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "HOMEDRIVE", "HOMEPATH",
    "CLAUDE_CONFIG_DIR", "CLAUDE_CODE_OAUTH_TOKEN"
  )

  /** Nachfrist nach dem eigenen Zeitlimit der CLI, bevor ase den Prozess beendet. */
  val Grace: FiniteDuration = 60.seconds

  def run(
      name: String,
      command: List[String],
      files: Map[String, String],
      stdin: String,
      workDir: Path,
      timeout: FiniteDuration,
      env: Map[String, String],
      grace: FiniteDuration = Grace
  ): Either[String, HttpReply] =
    if command.map(_.length + 3).sum > MaxArgs then
      Right(HttpReply(413, None, s"Kommandozeile mit ${command.map(_.length + 3).sum} Zeichen; höchstens $MaxArgs."))
    else
      Files.createDirectories(workDir)
      val dir = Files.createTempDirectory(workDir, s"$name-")
      try
        files.foreach((file, content) => Files.writeString(dir.resolve(file), content, UTF_8))
        Files.writeString(dir.resolve("stdin.txt"), stdin, UTF_8)
        val builder = ProcessBuilder(command*)
          .directory(dir.toFile)
          .redirectInput(dir.resolve("stdin.txt").toFile)
          .redirectOutput(dir.resolve("out.json").toFile)
          .redirectError(dir.resolve("err.txt").toFile)
        builder.environment().clear()
        builder.environment().putAll(env.asJava)
        val process = builder.start()
        val limit = timeout + grace
        if !process.waitFor(limit.toMillis, TimeUnit.MILLISECONDS) then
          // erst die Nachkommen, solange sie noch am Prozess hängen; danach gehörten sie init
          process.descendants().forEach(p => { p.destroyForcibly(); () })
          process.destroyForcibly()
          Left(s"$name antwortet nicht nach ${limit.toSeconds} s")
        else
          val out = Files.readString(dir.resolve("out.json"), UTF_8)
          if process.exitValue() == 0 then Right(HttpReply(200, None, out))
          else
            val err = Files.readString(dir.resolve("err.txt"), UTF_8)
            Right(HttpReply(502, None, s"$name endete mit ${process.exitValue()}: " + (err + out).take(500)))
      catch case NonFatal(e) => Left(e.getClass.getSimpleName + Option(e.getMessage).fold("")(": " + _))
      finally
        try
          val paths = Files.walk(dir)
          try paths.sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
          finally paths.close()
        catch case NonFatal(_) => ()

  /** workDir aus der Konfiguration, sonst ein Unterverzeichnis des temporären Verzeichnisses der JVM. */
  def workDir(config: ModelConfig, fallback: String): Path =
    if config.workDir.isEmpty then Paths.get(System.getProperty("java.io.tmpdir"), fallback) else Paths.get(config.workDir)

  /** Die Umgebung des Prozesses aus env (D#93): nur PassEnv und der bereinigte PATH. */
  def environment(env: Map[String, String], forbidden: List[Path]): Map[String, String] =
    PassEnv.flatMap(k => env.get(k).map(k -> _)).toMap + ("PATH" -> safePath(env.getOrElse("PATH", ""), forbidden).mkString(File.pathSeparator))

  /** Das Programm des endpoint (D#93): ein Name ohne Trenner wird im bereinigten PATH gesucht, ein absoluter Pfad
    * genommen, wie er ist; ein relativer Pfad mit Trenner nie (er hinge vom aktuellen Verzeichnis ab). In beiden
    * Fällen eine ausführbare Datei, die auch aufgelöst nicht unter einem der gesperrten Verzeichnisse liegt.
    */
  def locate(executable: String, env: Map[String, String], forbidden: List[Path]): Either[String, Path] =
    val roots = forbidden.map(real)
    def usable(p: Path): Boolean =
      try Files.isRegularFile(p) && Files.isExecutable(p) && !under(p, roots)
      catch case NonFatal(_) => false
    if executable.isEmpty then Left("endpoint ist leer")
    else if executable.contains('/') || executable.contains('\\') then
      val p = Paths.get(executable)
      if !p.isAbsolute then Left(s"endpoint „$executable“: ein Name aus PATH oder ein absoluter Pfad, kein relativer Pfad (D#93)")
      else if usable(p) then Right(p)
      else Left(s"endpoint „$executable“ ist keine ausführbare Datei außerhalb des Arbeitsverzeichnisses (D#93)")
    else
      val names = executable :: env.get("PATHEXT").filter(_ => File.separatorChar == '\\').toList.flatMap(_.split(';').toList.filter(_.nonEmpty).map(executable + _))
      safePath(env.getOrElse("PATH", ""), forbidden).iterator
        .flatMap(dir => names.map(n => Paths.get(dir).resolve(n)))
        .find(usable)
        .toRight(s"endpoint „$executable“ ist nicht im PATH, Einträge unter dem Arbeitsverzeichnis nicht gezählt (D#93)")

  /** PATH ohne relative Einträge und ohne Einträge unter den gesperrten Verzeichnissen. */
  private[model] def safePath(pathVar: String, forbidden: List[Path]): List[String] =
    val roots = forbidden.map(real)
    pathVar.split(File.pathSeparator).toList.filter(_.nonEmpty).filter(d =>
      try Paths.get(d).isAbsolute && !under(Paths.get(d), roots)
      catch case NonFatal(_) => false
    )

  private def real(p: Path): Path =
    try p.toRealPath() catch case NonFatal(_) => p.toAbsolutePath.normalize

  /** Im Zweifel darunter. */
  private def under(p: Path, roots: List[Path]): Boolean =
    val candidates =
      try List(p.toAbsolutePath.normalize, real(p))
      catch case NonFatal(_) => Nil
    candidates.isEmpty || roots.exists(r => candidates.exists(_.startsWith(r)))
