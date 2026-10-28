package ase.model

import ase.core.*
import ase.core.ExecSupport.runIO
import cats.effect.IO
import io.circe.Json

import scala.concurrent.duration.*

class OllamaSuite extends munit.FunSuite:
  private val config = ModelConfig.ollama.copy(backoff = 1.millis)
  private val check = CheckConfig.default
  private val h = Origin.Human
  private val root = Envelope(Set(OpKind.ReadFile, OpKind.Hole), ".", 10, 1, Set.empty)
  private val term = Term.Seq(List(Term.ReadFile("a.txt", h), Term.Done("fertig", h)), h)
  private val submission = Json.obj("term" -> Codec.term(term, withOrigin = false)).noSpaces

  private def chat(content: String, doneReason: String = "stop"): String = Json.obj(
    "model" -> Json.fromString(config.model),
    "message" -> Json.obj("role" -> Json.fromString("assistant"), "content" -> Json.fromString(content)),
    "done" -> Json.True,
    "done_reason" -> Json.fromString(doneReason),
    "prompt_eval_count" -> Json.fromInt(900),
    "eval_count" -> Json.fromInt(100)
  ).noSpaces

  test("Anfrage: Schema als format, kein Werkzeug, Kontext und Länge, System- und Nutzernachricht"):
    val c = Ollama.request(config, "SYS", 4, "hallo").hcursor
    assertEquals(c.downField("model").as[String], Right("qwen3:4b-instruct"))
    assertEquals(c.downField("stream").as[Boolean], Right(false))
    assertEquals(c.downField("format").focus, Some(Schema.termSchema(4)))
    assert(c.downField("tools").focus.isEmpty)
    assertEquals(c.downField("options").downField("num_ctx").as[Int], Right(32768))
    assertEquals(c.downField("options").downField("num_predict").as[Int], Right(8000))
    assertEquals(c.downField("messages").downN(0).downField("role").as[String], Right("system"))
    assert(c.downField("messages").downN(0).downField("content").as[String].exists(_.startsWith("SYS")))
    assertEquals(c.downField("messages").downN(1).downField("content").as[String], Right("hallo"))
    assert(c.downField("think").focus.isEmpty)
    assertEquals(Ollama.request(config.copy(think = true), "SYS", 4, "x").hcursor.downField("think").as[Boolean], Right(true))

  test("Antwort: Inhalt ist die Eingabe von submit_term; Tokens aus prompt_eval_count und eval_count"):
    Ollama.reply(chat(submission)) match
      case Right(ModelReply.Answer(text, tokens)) =>
        assertEquals(tokens, 1000L)
        assertEquals(Submission.decode(text, root, 5, Origin.Model("plan-1")).map(Fingerprint.of), Right(Fingerprint.of(term)))
      case other => fail(other.toString)

  test("Fehlversuche: abgeschnitten, leer"):
    assert(Ollama.reply(chat("{\"term\":", "length")).exists { case ModelReply.Failed(r, _) => r.contains("abgeschnitten"); case _ => false })
    assert(Ollama.reply(chat("  ")).exists { case ModelReply.Failed(r, _) => r.contains("Leere Antwort"); case _ => false })

  test("Fehler von Ollama: nicht wiederholt bei 404, als ModelUnavailable"):
    val body = Json.obj("error" -> Json.fromString("model \"qwen3:4b-instruct\" not found")).noSpaces
    val r = runIO(
      FakeApi.scripted(List(Right(HttpReply(404, None, body)))).flatMap((t, _) => Ollama[IO](t, config, check).plan(PlanRequest("plan-1", 1, "x", root, 5, Vector.empty)).attempt)
    )
    r match
      case Left(ModelUnavailable(ModelError.Status(404, "ollama", m))) => assert(m.contains("not found"), m)
      case other                                                      => fail(other.toString)

  test("Verbindungsfehler und 500 werden wiederholt"):
    val r = runIO(
      FakeApi.scripted(List(Left("ConnectException"), Right(HttpReply(500, None, "{}")), Right(HttpReply(200, None, chat(submission)))))
        .flatMap((t, _) => Ollama[IO](t, config, check).attempt(5, "x"))
    )
    assert(r.exists(_.isInstanceOf[ModelReply.Answer]), r)

  test("Lösungsbeispiel im System-Prompt besteht Tor 1 und Tor 2"):
    assert(DryRun.plan(Ollama.examplePlan, Ollama.exampleRoot, check).isRight)
    assert(DryRun.fill(Ollama.exampleFill, Ollama.exampleHole, check).isRight)
    val sys = Ollama.request(config, "SYS", 4, "x").hcursor.downField("messages").downN(0).downField("content").as[String]
    assert(sys.exists(s => s.contains("# Beispiel") && s.contains("\"VERSION\"")), sys)

  test("Fabrik: Anbieter aus der Konfiguration"):
    val t: Transport[IO] = _ => IO.pure(Left("nie"))
    assert(ModelAdapter[IO](t, config, check).isInstanceOf[Ollama[IO]])
    assert(ModelAdapter[IO](t, ModelConfig.default, check).isInstanceOf[Anthropic[IO]])
