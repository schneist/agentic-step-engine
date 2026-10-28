package ase.model

import ase.core.*
import cats.effect.Temporal
import io.circe.{Json, JsonObject}
import io.circe.parser

/** Ein lokales Modell über Ollama (/api/chat), für Entwicklungsläufe ohne Schlüssel (D#47). Kein Werkzeug: `format`
  * trägt das Term-Schema, Ollama beschränkt die Ausgabe darauf. Die Antwort ist dann die Eingabe von submit_term,
  * {"term": …}; Tor 1 und Tor 2 prüfen sie wie jede andere.
  */
final class Ollama[F[_]: Temporal](transport: Transport[F], config: ModelConfig, check: CheckConfig)
    extends CallingAdapter[F](transport, config, check):
  protected def request(nestingLimit: Int, user: String): String = Ollama.request(config, system, nestingLimit, user).noSpaces
  protected def reply(body: String): Either[ModelError, ModelReply] = Ollama.reply(body)
  protected def error(status: Int, body: String): ModelError = Ollama.error(status, body)

object Ollama:
  /** Der System-Prompt spricht von einem Werkzeug; hier gibt es keins, die Antwort selbst ist der Aufruf. */
  private val noTool =
    s"\n\nHier gibt es kein Werkzeug: Deine ganze Antwort ist die Eingabe von ${Prompt.toolName}, ein JSON-Objekt {\"term\": …}."

  /** think nur, wenn eingeschaltet: Modelle ohne Denken kennen das Feld nicht. */
  def request(config: ModelConfig, system: String, nestingLimit: Int, user: String): Json =
    val thinking = if config.think then Json.obj("think" -> Json.True) else Json.obj()
    thinking deepMerge Json.obj(
      "model" -> Json.fromString(config.model),
      "stream" -> Json.False,
      "format" -> Schema.termSchema(nestingLimit),
      "options" -> Json.obj(
        "num_ctx" -> Json.fromInt(config.contextTokens),
        "num_predict" -> Json.fromInt(config.maxTokens)
      ),
      "messages" -> Json.arr(
        Json.obj("role" -> Json.fromString("system"), "content" -> Json.fromString(system + noTool)),
        Json.obj("role" -> Json.fromString("user"), "content" -> Json.fromString(user))
      )
    )

  /** Mit think steht das Denken in message.thinking und zählt in eval_count; ausgewertet wird nur content (D#48).
    * done_reason "length": abgeschnitten, ein Fehlversuch wie max_tokens bei Anthropic.
    */
  def reply(body: String): Either[ModelError, ModelReply] =
    for
      o <- parser.parse(body).left.map(e => ModelError.UnreadableResponse(e.message)).flatMap(_.asObject.toRight(ModelError.UnreadableResponse("kein Objekt")))
      content <- o("message").flatMap(_.asObject).flatMap(_("content")).flatMap(_.asString).toRight(ModelError.UnreadableResponse("message.content fehlt"))
    yield
      val tokens = tokenCount(o)
      o("done_reason").flatMap(_.asString) match
        case Some("length") =>
          ModelReply.Failed("Die Antwort wurde bei der Längengrenze abgeschnitten; liefere einen kürzeren Term.", tokens)
        case _ if content.trim.isEmpty => ModelReply.Failed(s"Leere Antwort; antworte mit der Eingabe von ${Prompt.toolName}.", tokens)
        case _                         => ModelReply.Answer(content.trim, tokens)

  /** Ollama meldet Fehler als {"error": "…"}. */
  def error(status: Int, body: String): ModelError.Status =
    val message = parser.parse(body).toOption.flatMap(_.asObject).flatMap(_("error")).flatMap(_.asString)
    ModelError.Status(status, "ollama", message.getOrElse(body.take(500)))

  private def tokenCount(o: JsonObject): Long =
    List("prompt_eval_count", "eval_count").flatMap(k => o(k).flatMap(_.asNumber).flatMap(_.toLong)).sum
