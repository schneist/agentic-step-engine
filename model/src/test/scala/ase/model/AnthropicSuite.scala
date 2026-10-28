package ase.model

import ase.core.*
import ase.core.ExecSupport.runIO
import cats.effect.IO
import io.circe.parser

import scala.concurrent.duration.*

class AnthropicSuite extends munit.FunSuite:
  private val config = ModelConfig.default.copy(backoff = 1.millis)
  private val check = CheckConfig.default
  private val h = Origin.Human
  private val root = Envelope(Set(OpKind.ReadFile, OpKind.Hole), ".", 10, 1, Set.empty)
  private val term = Term.Seq(List(Term.ReadFile("a.txt", h), Term.Done("fertig", h)), h)

  private def plan(adapter: Anthropic[IO]): IO[ModelReply] =
    adapter.plan(PlanRequest("plan-1", 1, "Lies a.txt", root, check.nestingLimit, Vector.empty))

  test("Anfrage: ein Tool, strict, tool_choice auto ohne parallele Aufrufe, Effort, Cache-Marke, kein thinking"):
    val body = Messages.request(config, Prompt.system(check), 5, "hallo")
    val c = body.hcursor
    assertEquals(c.downField("model").as[String], Right("claude-opus-5-5"))
    assertEquals(c.downField("tools").values.map(_.size), Some(1))
    val tool = c.downField("tools").downArray
    assertEquals(tool.downField("name").as[String], Right("submit_term"))
    assertEquals(tool.downField("strict").as[Boolean], Right(true))
    assertEquals(tool.downField("input_schema").focus, Some(Schema.termSchema(5)))
    assertEquals(c.downField("tool_choice").downField("type").as[String], Right("auto"))
    assertEquals(c.downField("tool_choice").downField("disable_parallel_tool_use").as[Boolean], Right(true))
    assertEquals(c.downField("output_config").downField("effort").as[String], Right("high"))
    assertEquals(c.downField("system").downArray.downField("cache_control").downField("type").as[String], Right("ephemeral"))
    assert(c.downField("thinking").focus.isEmpty)

  test("Antwort mit submit_term: Eingabe als Text, Tokens inklusive Cache"):
    val reply = Messages.reply(FakeApi.submit(term)).fold(e => fail(e.message), identity)
    reply match
      case ModelReply.Answer(text, tokens) =>
        assertEquals(tokens, 3120L)
        assertEquals(Submission.decode(text, root, 5, Origin.Model("plan-1")).map(Fingerprint.of), Right(Fingerprint.of(term)))
      case other => fail(other.toString)

  test("Fehlversuche des Modells: kein Werkzeug, fremdes Werkzeug, max_tokens, refusal"):
    def failed(body: String): String = Messages.reply(body) match
      case Right(ModelReply.Failed(reason, tokens)) => assertEquals(tokens, 3120L); reason
      case other                                    => fail(other.toString)
    assert(failed(FakeApi.message("end_turn", FakeApi.text("Hier ist mein Plan …"))).contains("ohne submit_term"))
    assert(failed(FakeApi.message("tool_use", FakeApi.toolUse("bash", io.circe.Json.obj()))).contains("Unbekanntes Werkzeug"))
    assert(failed(FakeApi.message("max_tokens", FakeApi.text("…"))).contains("max_tokens"))
    assert(failed(FakeApi.message("refusal")).contains("refusal"))

  test("429 und 529 werden wiederholt, dann kommt die Antwort"):
    val (reply, seen) = runIO(
      for
        (t, seen) <- FakeApi.scripted(List(Right(FakeApi.error(429, "rate_limit_error")), Right(FakeApi.error(529, "overloaded_error")), Right(HttpReply(200, None, FakeApi.submit(term)))))
        r <- plan(Anthropic[IO](t, config, check))
        s <- seen.get
      yield (r, s)
    )
    assert(reply.isInstanceOf[ModelReply.Answer], reply)
    assertEquals(seen.size, 3)
    assertEquals(seen.distinct.size, 1)

  test("400 wird nicht wiederholt und endet als ModelUnavailable, nicht als Fehlversuch"):
    val result = runIO(
      for
        (t, seen) <- FakeApi.scripted(List(Right(FakeApi.error(400, "invalid_request_error"))))
        r <- plan(Anthropic[IO](t, config, check)).attempt
        s <- seen.get
      yield (r, s.size)
    )
    assertEquals(result._2, 1)
    result._1 match
      case Left(ModelUnavailable(ModelError.Status(400, "invalid_request_error", _))) => ()
      case other                                                                     => fail(other.toString)

  test("Wiederholungen erschöpft: der letzte Status"):
    val replies = List.fill(config.retries + 1)(Right(FakeApi.error(529, "overloaded_error")))
    val r = runIO(FakeApi.scripted(replies).flatMap((t, _) => Anthropic[IO](t, config, check).attempt(5, "x")))
    assertEquals(r.left.map { case ModelError.Status(c, _, _) => c; case e => -1 }, Left(529))

  test("Fill-Prompt: Ziel, Hülle als JSON, Restbudget, sichtbare Ergebnisse, frühere Fehler"):
    val env = Envelope(Set(OpKind.WriteFile), "TODO.md", 2, 0, Set.empty)
    val text = Prompt.fill(FillRequest("fill-1", "2", 2, "Schreibe TODO.md", env, 2, 5, Vector(ScopeLine("1", "shell grep", "src/a.scala:3: // TODO x")), Vector("abgelehnt: y")))
    assert(text.contains("Ziel: Schreibe TODO.md"))
    assert(text.contains(Codec.envelope(env).spaces2))
    assert(text.contains("Verbleibende Schritte: 2"))
    assert(text.contains("src/a.scala:3: // TODO x"))
    assert(text.contains("abgelehnt: y"))

  test("System-Prompt nennt jede Argument-Policy"):
    val s = Prompt.system(check)
    check.argPolicies.foreach(p => assert(s.contains("- " + (p.binary :: p.prefixArgs).mkString(" ") + ":"), p))
    assert(parser.parse(Messages.request(config, s, 5, "x").noSpaces).isRight)
