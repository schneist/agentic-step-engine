package ase.model

import ase.core.*
import io.circe.{Json, JsonObject}
import io.circe.parser

/** Anfrage und Antwort der Messages API, von Hand über circe (wie der Term-Codec, D#26). */
object Messages:
  /** Ein Tool, strict (D#10); tool_choice auto, höchstens ein Aufruf (erzwungenes any/tool ist auf
    * claude-opus-5-5 ein 400). thinking fehlt: adaptiv. System-Prompt und Tool sind der stabile Präfix mit
    * Cache-Marke; nur die Nachricht wechselt.
    */
  def request(config: ModelConfig, system: String, nestingLimit: Int, user: String): Json =
    Json.obj(
      "model" -> Json.fromString(config.model),
      "max_tokens" -> Json.fromInt(config.maxTokens),
      "output_config" -> Json.obj("effort" -> Json.fromString(config.effort)),
      "system" -> Json.arr(
        Json.obj(
          "type" -> Json.fromString("text"),
          "text" -> Json.fromString(system),
          "cache_control" -> Json.obj("type" -> Json.fromString("ephemeral"))
        )
      ),
      "tools" -> Json.arr(
        Json.obj(
          "name" -> Json.fromString(Prompt.toolName),
          "description" -> Json.fromString(Prompt.toolDescription),
          "strict" -> Json.True,
          "input_schema" -> Schema.termSchema(nestingLimit)
        )
      ),
      "tool_choice" -> Json.obj("type" -> Json.fromString("auto"), "disable_parallel_tool_use" -> Json.True),
      "messages" -> Json.arr(Json.obj("role" -> Json.fromString("user"), "content" -> Json.fromString(user)))
    )

  /** Status 200: die Antwort als ModelReply. Ein fehlender submit_term-Aufruf, Abbruch durch max_tokens oder eine
    * Ablehnung sind Fehlversuche des Modells (ModelReply.Failed), keine Fehler des Adapters.
    */
  def reply(body: String): Either[ModelError, ModelReply] =
    for
      o <- parser.parse(body).left.map(e => ModelError.UnreadableResponse(e.message)).flatMap(obj)
      tokens = usage(o)
      stop = o("stop_reason").flatMap(_.asString).getOrElse("")
      content <- o("content").flatMap(_.asArray).toRight(ModelError.UnreadableResponse("content fehlt"))
    yield
      val calls = content.flatMap(_.asObject).filter(b => b("type").flatMap(_.asString).contains("tool_use"))
      val submits = calls.filter(_("name").flatMap(_.asString).contains(Prompt.toolName))
      stop match
        case "refusal" => ModelReply.Failed("Das Modell hat die Anfrage abgelehnt (refusal).", tokens)
        case "max_tokens" =>
          ModelReply.Failed("Die Antwort wurde bei max_tokens abgeschnitten; liefere einen kürzeren Term.", tokens)
        case _ =>
          submits.toList match
            case List(call) =>
              call("input").fold(ModelReply.Failed(s"${Prompt.toolName} ohne Eingabe.", tokens))(i => ModelReply.Answer(i.noSpaces, tokens))
            case Nil if calls.nonEmpty =>
              ModelReply.Failed(s"Unbekanntes Werkzeug; es gibt nur ${Prompt.toolName}.", tokens)
            case Nil => ModelReply.Failed(s"Antwort ohne ${Prompt.toolName}; rufe ${Prompt.toolName} mit dem Term auf.", tokens)
            case _   => ModelReply.Failed(s"Mehrere Aufrufe von ${Prompt.toolName}; rufe es genau einmal auf.", tokens)

  /** Fehlerantwort: Typ und Meldung aus {"type":"error","error":{"type","message"}}, sonst der rohe Text. */
  def error(status: Int, body: String): ModelError.Status =
    val e = parser.parse(body).toOption.flatMap(_.hcursor.downField("error").focus).flatMap(_.asObject)
    ModelError.Status(
      status,
      e.flatMap(_("type")).flatMap(_.asString).getOrElse("unbekannt"),
      e.flatMap(_("message")).flatMap(_.asString).getOrElse(body.take(500))
    )

  /** Wiederholbar: Ratenlimit, Überlast, Serverfehler (429, 500, 502, 503, 504, 529). */
  def retryable(status: Int): Boolean = status == 429 || status >= 500

  /** Alle Eingabe- und Ausgabe-Tokens, auch aus dem Cache. */
  private def usage(o: JsonObject): Long =
    val u = o("usage").flatMap(_.asObject)
    List("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
      .flatMap(k => u.flatMap(_(k)).flatMap(_.asNumber).flatMap(_.toLong))
      .sum

  private def obj(j: Json): Either[ModelError, JsonObject] = j.asObject.toRight(ModelError.UnreadableResponse("kein Objekt"))
