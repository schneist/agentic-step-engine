package ase.model

import ase.core.*
import ase.core.ExecSupport.runIO
import cats.effect.IO
import io.circe.Json

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*

class AgySuite extends munit.FunSuite:
  private val config = ModelConfig.agy.copy(backoff = 1.millis)
  private val check = CheckConfig.default
  private val h = Origin.Human
  private val root = Envelope(Set(OpKind.ReadFile, OpKind.Hole), ".", 10, 1, Set.empty)
  private val term = Term.Seq(List(Term.ReadFile("a.txt", h), Term.Done("fertig", h)), h)

  private def output(status: String, structured: Option[Json], error: String = ""): String = Json.obj(
    "conversation_id" -> Json.fromString("c1"),
    "status" -> Json.fromString(status),
    "response" -> Json.fromString(""),
    "error" -> Json.fromString(error),
    "structured_output" -> structured.getOrElse(Json.Null),
    "usage" -> Json.obj("input_tokens" -> Json.fromInt(7800), "output_tokens" -> Json.fromInt(200), "total_tokens" -> Json.fromInt(8000))
  ).noSpaces

  test("Anfrage: Modell, System- und Nutzerprompt in einem, Term-Schema"):
    val c = Agy.request(config, "SYS", 4, "hallo").hcursor
    assertEquals(c.downField("model").as[String], Right("gemini-3.8-flash-medium"))
    val prompt = c.downField("prompt").as[String].getOrElse("")
    assert(prompt.startsWith("SYS") && prompt.endsWith("hallo") && prompt.contains("Lies keine Dateien"), prompt)
    assertEquals(c.downField("schema").focus, Some(Schema.termSchema(4)))

  test("Argumente: Plan-Modus, JSON-Ausgabe, Schema im Arbeitsverzeichnis, Zeitlimit"):
    assertEquals(
      AgyTransport.args("m", "p", 90.seconds),
      List("--print", "p", "--model", "m", "--mode", "plan", "--output-format", "json", "--json-schema", "schema.json", "--print-timeout", "90s")
    )

  test("Antwort: structured_output ist die Eingabe von submit_term; Tokens aus usage.total_tokens"):
    val submission = Json.obj("term" -> Codec.term(term, withOrigin = false))
    Agy.reply(output("SUCCESS", Some(submission))) match
      case Right(ModelReply.Answer(text, tokens)) =>
        assertEquals(tokens, 8000L)
        assertEquals(Submission.decode(text, root, 5, Origin.Model("plan-1")).map(Fingerprint.of), Right(Fingerprint.of(term)))
      case other => fail(other.toString)

  test("Ohne structured_output ein Fehlversuch; ein anderer status ist kein Modellfehler"):
    assert(Agy.reply(output("SUCCESS", None)).exists { case ModelReply.Failed(r, 8000L) => r.contains("Keine Antwort im Schema"); case _ => false })
    Agy.reply(output("ERROR", None, "authentication failed or timed out")) match
      case Left(ModelError.Status(_, "agy", d)) => assert(d.contains("ERROR") && d.contains("authentication"), d)
      case other                                 => fail(other.toString)

  test("Zu langer Prompt: 413 ohne Wiederholung, als ModelUnavailable"):
    val t = AgyTransport[IO](config.copy(endpoint = "agy-gibt-es-nicht"))
    val long = Agy.request(config, "x" * AgyTransport.MaxPrompt, 4, "y").noSpaces
    assertEquals(runIO(t.post(long)).map(_.status), Right(413))
    val r = runIO(Agy[IO](t, config, check).plan(PlanRequest("plan-1", 1, "x" * AgyTransport.MaxPrompt, root, 5, Vector.empty)).attempt)
    assert(r.left.exists { case ModelUnavailable(ModelError.Status(413, "agy", _)) => true; case _ => false }, r)

  test("Fabrik: Anbieter agy"):
    val t: Transport[IO] = _ => IO.pure(Left("nie"))
    assert(ModelAdapter[IO](t, config, check).isInstanceOf[Agy[IO]])

  private def fakeAgy(dir: Path, body: String): Path =
    val p = dir.resolve("agy")
    Files.writeString(p, "#!/bin/sh\n" + body)
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"))
    p

  test("Prozess: Argumente als Liste, Schema im eigenen leeren Verzeichnis, das danach verschwindet"):
    assume(Platform.isLinux, s"Fake-Programm als Shell-Skript nur unter Linux, hier ${Platform.current}")
    val bin = Files.createTempDirectory("agy-bin")
    val work = Files.createTempDirectory("agy-work")
    // gibt das Modell ($4) als Done-Text zurück, wenn das Schema da ist und sonst nichts im Verzeichnis liegt
    val agy = fakeAgy(
      bin,
      """test -f schema.json || exit 3
        |test "$(ls | grep -v -e schema.json -e stdin.txt -e out.json -e err.txt)" = "" || exit 4
        |printf '{"status":"SUCCESS","structured_output":{"term":{"type":"Done","summary":"%s"}},"usage":{"total_tokens":42}}' "$4"
        |""".stripMargin
    )
    val c = config.copy(endpoint = agy.toString, workDir = work.toString)
    val r = runIO(Agy[IO](AgyTransport[IO](c), c, check).plan(PlanRequest("plan-1", 1, "x", root, 5, Vector.empty)))
    assertEquals(r, ModelReply.Answer("""{"term":{"type":"Done","summary":"gemini-3.8-flash-medium"}}""", 42L))
    assertEquals(Files.list(work).count(), 0L)

  test("Prozess: Exit-Code ungleich 0 wird wiederholt und endet als ModelUnavailable"):
    assume(Platform.isLinux, s"Fake-Programm als Shell-Skript nur unter Linux, hier ${Platform.current}")
    val bin = Files.createTempDirectory("agy-bin")
    val agy = fakeAgy(bin, "echo kaputt >&2\nexit 2\n")
    val c = config.copy(endpoint = agy.toString, workDir = Files.createTempDirectory("agy-work").toString, retries = 1)
    val r = runIO(Agy[IO](AgyTransport[IO](c), c, check).plan(PlanRequest("plan-1", 1, "x", root, 5, Vector.empty)).attempt)
    assert(r.left.exists { case ModelUnavailable(ModelError.Status(502, "agy", d)) => d.contains("kaputt"); case _ => false }, r)
