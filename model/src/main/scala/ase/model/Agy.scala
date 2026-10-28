package ase.model

import ase.core.*
import cats.effect.{Sync, Temporal}
import io.circe.Json
import io.circe.parser

import java.nio.file.Path
import scala.concurrent.duration.*

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

  /** Ein Fehlversuch ohne structured_output nennt den Anfang des Freitexts (response), damit Record-Log und Modell sehen,
    * was statt des Terms kam.
    */
  private[model] def noSchema(response: String): String =
    val text = response.trim.replaceAll("\\s+", " ")
    val quoted = if text.isEmpty then "" else s" Deine Antwort begann mit: „${text.take(300)}${if text.length > 300 then "…" else ""}“"
    s"Keine Antwort im Schema; antworte mit der Eingabe von $toolName.$quoted"

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
              case None         => ModelReply.Failed(noSchema(o("response").flatMap(_.asString).getOrElse("")), tokens)
            )
          case status =>
            val detail = o("error").flatMap(_.asString).getOrElse("")
            Left(ModelError.Status(200, "agy", s"status ${status.getOrElse("fehlt")}: $detail"))
      }

/** Startet agy einmal pro Anfrage über CliProcess; das Schema liegt im leeren Verzeichnis des Aufrufs. */
final class AgyTransport[F[_]: Sync](executable: String, workDir: Path, timeout: FiniteDuration) extends Transport[F]:
  def post(body: String): F[Either[String, HttpReply]] =
    Sync[F].blocking {
      parser.parse(body).toOption.flatMap(_.asObject) match
        case None => Right(HttpReply(400, None, "Anfrage ist kein JSON-Objekt"))
        case Some(o) =>
          def str(k: String): String = o(k).flatMap(_.asString).getOrElse("")
          val files = Map("schema.json" -> o("schema").getOrElse(Json.obj()).noSpaces)
          CliProcess.run("agy", executable :: AgyTransport.args(str("model"), str("prompt"), timeout), files, "", workDir, timeout)
    }

object AgyTransport:
  val MaxPrompt = CliProcess.MaxArgs

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
    new AgyTransport[F](config.endpoint, CliProcess.workDir(config, "ase-agy"), config.requestTimeout)
