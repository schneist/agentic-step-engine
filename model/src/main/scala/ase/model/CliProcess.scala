package ase.model

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Ein Modell über eine Kommandozeile (agy, claude; D#54, D#58): ein Prozess pro Anfrage, Argumente als Liste, keine
  * Shell (D#12). Jeder Aufruf bekommt unter workDir ein eigenes leeres Verzeichnis mit seinen Dateien, damit das
  * Programm nichts aus dem Workspace liest; danach wird es gelöscht. stdin kommt aus einer Datei, stdout und stderr
  * gehen in Dateien (keine Pipe, die volllaufen kann). Exit-Code 0 ist Status 200, sonst 502 (wird wiederholt).
  */
object CliProcess:
  /** Windows begrenzt die Kommandozeile auf 32767 Zeichen; Rest für Programm und übrige Argumente. */
  val MaxArgs = 30000

  def run(
      name: String,
      command: List[String],
      files: Map[String, String],
      stdin: String,
      workDir: Path,
      timeout: FiniteDuration
  ): Either[String, HttpReply] =
    if command.map(_.length + 3).sum > MaxArgs then
      Right(HttpReply(413, None, s"Kommandozeile mit ${command.map(_.length + 3).sum} Zeichen; höchstens $MaxArgs."))
    else
      Files.createDirectories(workDir)
      val dir = Files.createTempDirectory(workDir, s"$name-")
      try
        files.foreach((file, content) => Files.writeString(dir.resolve(file), content, UTF_8))
        Files.writeString(dir.resolve("stdin.txt"), stdin, UTF_8)
        val process = ProcessBuilder(command*)
          .directory(dir.toFile)
          .redirectInput(dir.resolve("stdin.txt").toFile)
          .redirectOutput(dir.resolve("out.json").toFile)
          .redirectError(dir.resolve("err.txt").toFile)
          .start()
        val limit = timeout.toSeconds + 60
        if !process.waitFor(limit, TimeUnit.SECONDS) then
          process.destroyForcibly()
          Left(s"$name antwortet nicht nach $limit s")
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
