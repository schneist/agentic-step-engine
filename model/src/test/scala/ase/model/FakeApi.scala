package ase.model

import ase.core.*
import cats.effect.{IO, Ref}
import io.circe.Json

/** Antworten der Messages API zum Nachbauen, und ein Transport, der sie der Reihe nach liefert. */
object FakeApi:
  def message(stop: String, content: Json*): String = Json.obj(
    "id" -> Json.fromString("msg_test"),
    "type" -> Json.fromString("message"),
    "role" -> Json.fromString("assistant"),
    "stop_reason" -> Json.fromString(stop),
    "content" -> Json.arr(content*),
    "usage" -> Json.obj(
      "input_tokens" -> Json.fromInt(100),
      "output_tokens" -> Json.fromInt(20),
      "cache_creation_input_tokens" -> Json.fromInt(0),
      "cache_read_input_tokens" -> Json.fromInt(3000)
    )
  ).noSpaces

  def text(s: String): Json = Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(s))

  def toolUse(name: String, input: Json): Json = Json.obj(
    "type" -> Json.fromString("tool_use"),
    "id" -> Json.fromString("toolu_test"),
    "name" -> Json.fromString(name),
    "input" -> input
  )

  /** submit_term mit einem Term, wie das Modell ihn schickt: ohne origin. */
  def submit(t: Term): String = message("tool_use", toolUse(Prompt.toolName, Json.obj("term" -> Codec.term(t, withOrigin = false))))

  def error(status: Int, tpe: String): HttpReply =
    HttpReply(status, None, Json.obj("type" -> Json.fromString("error"), "error" -> Json.obj("type" -> Json.fromString(tpe), "message" -> Json.fromString("test"))).noSpaces)

  /** Liefert die Antworten der Reihe nach und merkt sich die Anfragen. */
  def scripted(replies: List[Either[String, HttpReply]]): IO[(Transport[IO], Ref[IO, Vector[String]])] =
    for
      queue <- Ref.of[IO, List[Either[String, HttpReply]]](replies)
      seen <- Ref.of[IO, Vector[String]](Vector.empty)
    yield
      val t: Transport[IO] = body =>
        seen.update(_ :+ body) >> queue.modify {
          case r :: rest => (rest, r)
          case Nil       => (Nil, Left("keine Antwort mehr im Skript"))
        }
      (t, seen)

  /** Antwortet je nach Anfrage: f bekommt den Text der Nutzernachricht. */
  def responding(f: String => String): Transport[IO] = body =>
    IO {
      val user = io.circe.parser.parse(body).toOption
        .flatMap(_.hcursor.downField("messages").downArray.downField("content").as[String].toOption)
        .getOrElse("")
      Right(HttpReply(200, None, f(user)))
    }
