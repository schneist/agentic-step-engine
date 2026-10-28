package ase.model

import ase.core.*
import cats.effect.{Sync, Temporal}
import io.circe.{Json, JsonObject}
import io.circe.parser

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Ein Modell über die Antigravity-CLI (D#54), für Entwicklungsläufe ohne eigenen Schlüssel: agy ist angemeldet. Wie
  * bei Ollama gibt es kein Werkzeug; --json-schema bindet die Antwort an das Term-Schema, und structured_output ist die
  * Eingabe von submit_term. Tor 1 und Tor 2 prüfen sie wie jede andere.
  */
final class Agy[F[_]: Temporal](transport: Transport[F], config: ModelConfig, check: CheckConfig)
    extends CallingAdapter[F](transport, config, check):
  protected def request(nestingLimit: Int, user: String): String = Agy.request(config, system, nestingLimit, user).noSpaces
  protected def reply(body: String): Either[ModelError, ModelReply] = Agy.reply(body)
  protected def error(status: Int, body: String): ModelError = ModelError.Status(status, "agy", body.take(500))

object Agy:
  /** agy ist ein Agent mit Werkzeugen; hier soll er nur antworten. Er läuft in einem leeren Verzeichnis. */
  private val noTool =
    s"\n\nHier gibt es kein Werkzeug $toolName: Deine ganze Antwort ist dessen Eingabe, ein JSON-Objekt {\"term\": …}. " +
      "Lies keine Dateien und rufe keine Werkzeuge auf; alles, was du brauchst, steht in dieser Nachricht."

  private def toolName = Prompt.toolName

  /** Was AgyTransport braucht: Modell, Prompt (System und Nutzer in einem, agy kennt keinen System-Prompt), Schema. */
  def request(config: ModelConfig, system: String, nestingLimit: Int, user: String): Json = Json.obj(
    "model" -> Json.fromString(config.model),
    "prompt" -> Json.fromString(system + noTool + "\n\n" + user),
    "schema" -> Schema.termSchema(nestingLimit)
  )

  /** Ausgabe von agy --output-format json. status SUCCESS mit structured_output: eine Antwort; ohne: ein Fehlversuch.
    * Jeder andere status (Anmeldung, Kontingent) ist kein Fehler des Modells und endet als ModelUnavailable.
    */
  def reply(body: String): Either[ModelError, ModelReply] =
    parser
      .parse(body)
      .left
      .map(e => ModelError.UnreadableResponse(e.message))
      .flatMap(_.asObject.toRight(ModelError.UnreadableResponse("kein Objekt")))
      .flatMap { o =>
        val tokens = o("usage").flatMap(_.asObject).flatMap(_("total_tokens")).flatMap(_.asNumber).flatMap(_.toLong).getOrElse(0L)
        o("status").flatMap(_.asString) match
          case Some("SUCCESS") =>
            Right(o("structured_output").filterNot(_.isNull) match
              case Some(answer) => ModelReply.Answer(answer.noSpaces, tokens)
              case None         => ModelReply.Failed(s"Keine Antwort im Schema; antworte mit der Eingabe von $toolName.", tokens)
            )
          case status =>
            val detail = o("error").flatMap(_.asString).getOrElse("")
            Left(ModelError.Status(200, "agy", s"status ${status.getOrElse("fehlt")}: $detail"))
      }

/** Startet agy einmal pro Anfrage: Argumente als Liste, keine Shell (D#12), stdin leer. Jeder Aufruf bekommt unter
  * workDir ein eigenes leeres Verzeichnis mit dem Schema darin, damit der Agent nichts aus dem Workspace liest; danach
  * wird es gelöscht. Ein zu langer Prompt (Kommandozeile unter Windows) ist Status 413, ohne Wiederholung.
  */
final class AgyTransport[F[_]: Sync](executable: String, workDir: Path, timeout: FiniteDuration) extends Transport[F]:
  def post(body: String): F[Either[String, HttpReply]] =
    Sync[F].blocking {
      parser.parse(body).toOption.flatMap(_.asObject) match
        case None => Right(HttpReply(400, None, "Anfrage ist kein JSON-Objekt"))
        case Some(o) =>
          val model = str(o, "model")
          val prompt = str(o, "prompt")
          if prompt.length > AgyTransport.MaxPrompt then
            Right(HttpReply(413, None, s"Prompt mit ${prompt.length} Zeichen; höchstens ${AgyTransport.MaxPrompt} passen auf die Kommandozeile."))
          else run(model, prompt, o("schema").getOrElse(Json.obj()))
    }

  private def str(o: JsonObject, k: String): String = o(k).flatMap(_.asString).getOrElse("")

  private def run(model: String, prompt: String, schema: Json): Either[String, HttpReply] =
    Files.createDirectories(workDir)
    val dir = Files.createTempDirectory(workDir, "agy-")
    try
      Files.writeString(dir.resolve("schema.json"), schema.noSpaces, UTF_8)
      val command = List(executable) ++ AgyTransport.args(model, prompt, timeout)
      val process = ProcessBuilder(command*)
        .directory(dir.toFile)
        .redirectOutput(dir.resolve("out.json").toFile)
        .redirectError(dir.resolve("err.txt").toFile)
        .start()
      process.getOutputStream.close()
      if !process.waitFor(timeout.toSeconds + 60, TimeUnit.SECONDS) then
        process.destroyForcibly()
        Left(s"agy antwortet nicht nach ${timeout.toSeconds + 60} s")
      else
        val out = Files.readString(dir.resolve("out.json"), UTF_8)
        if process.exitValue() == 0 then Right(HttpReply(200, None, out))
        else Right(HttpReply(502, None, s"agy endete mit ${process.exitValue()}: " + Files.readString(dir.resolve("err.txt"), UTF_8).take(500)))
    catch case NonFatal(e) => Left(e.getClass.getSimpleName + Option(e.getMessage).fold("")(": " + _))
    finally
      try
        val paths = Files.walk(dir)
        try paths.sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally paths.close()
      catch case NonFatal(_) => ()

object AgyTransport:
  /** Windows begrenzt die Kommandozeile auf 32767 Zeichen; Rest für Programm und übrige Argumente. */
  val MaxPrompt = 30000

  /** --mode plan: der Agent darf nichts ändern. Das Schema liegt im Arbeitsverzeichnis des Aufrufs. */
  def args(model: String, prompt: String, timeout: FiniteDuration): List[String] = List(
    "--print",
    prompt,
    "--model",
    model,
    "--mode",
    "plan",
    "--output-format",
    "json",
    "--json-schema",
    "schema.json",
    "--print-timeout",
    s"${timeout.toSeconds}s"
  )

  def apply[F[_]: Sync](config: ModelConfig): AgyTransport[F] =
    val dir = if config.workDir.isEmpty then Paths.get(System.getProperty("java.io.tmpdir"), "ase-agy") else Paths.get(config.workDir)
    new AgyTransport[F](config.endpoint, dir, config.requestTimeout)
