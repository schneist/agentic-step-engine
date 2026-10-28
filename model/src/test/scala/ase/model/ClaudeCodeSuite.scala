package ase.model

import ase.core.*
import ase.core.ExecSupport.runIO
import cats.effect.IO
import io.circe.Json

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*

class ClaudeCodeSuite extends munit.FunSuite:
  private val config = ModelConfig.claudeCode.copy(backoff = 1.millis)
  private val check = CheckConfig.default
  private val h = Origin.Human
  private val root = Envelope(Set(OpKind.ReadFile, OpKind.Hole), ".", 10, 1, Set.empty)
  private val term = Term.Seq(List(Term.ReadFile("a.txt", h), Term.Done("fertig", h)), h)
  private val submission = Json.obj("term" -> Codec.term(term, withOrigin = false))

  private def output(subtype: String, isError: Boolean, structured: Option[Json], result: String = ""): String = Json.obj(
    "type" -> Json.fromString("result"),
    "subtype" -> Json.fromString(subtype),
    "is_error" -> Json.fromBoolean(isError),
    "result" -> Json.fromString(result),
    "structured_output" -> structured.getOrElse(Json.Null),
    "usage" -> Json.obj(
      "input_tokens" -> Json.fromInt(2),
      "output_tokens" -> Json.fromInt(70),
      "cache_creation_input_tokens" -> Json.fromInt(1000),
      "cache_read_input_tokens" -> Json.fromInt(28)
    )
  ).noSpaces

  test("Anfrage: Modell, Effort, eigener System-Prompt, Nutzerprompt getrennt, Term-Schema"):
    val c = ClaudeCode.request(config, "SYS", 4, "hallo").hcursor
    assertEquals(c.downField("model").as[String], Right("claude-opus-5-5"))
    assertEquals(c.downField("effort").as[String], Right("high"))
    assert(c.downField("system").as[String].exists(s => s.startsWith("SYS") && s.contains("submit_term")))
    assertEquals(c.downField("prompt").as[String], Right("hallo"))
    assertEquals(c.downField("schema").focus, Some(Schema.termSchema(4)))

  test("Argumente: keine Werkzeuge, kein MCP, keine Sitzung, System-Prompt aus Datei, Schema als Argument"):
    val a = ClaudeCodeTransport.args("m", "high", "{}")
    assertEquals(a.sliding(2).collectFirst { case List("--tools", v) => v }, Some(""))
    assert(a.containsSlice(List("--system-prompt-file", "system.txt")) && a.containsSlice(List("--json-schema", "{}")))
    assert(a.contains("--strict-mcp-config") && a.contains("--no-session-persistence") && a.containsSlice(List("--effort", "high")))
    assertEquals(a.sliding(2).collectFirst { case List("--setting-sources", v) => v }, Some(""))

  test("Antwort: structured_output ist die Eingabe von submit_term; Tokens aus allen vier usage-Feldern"):
    ClaudeCode.reply(output("success", false, Some(submission))) match
      case Right(ModelReply.Answer(text, tokens)) =>
        assertEquals(tokens, 1100L)
        assertEquals(Submission.decode(text, root, 5, Origin.Model("plan-1")).map(Fingerprint.of), Right(Fingerprint.of(term)))
      case other => fail(other.toString)

  test("Fehlversuche: Erfolg ohne Schema-Antwort, Abbruch an einer Grenze; sonst ModelUnavailable"):
    assert(ClaudeCode.reply(output("success", false, None)).exists { case ModelReply.Failed(r, _) => r.contains("Keine Antwort im Schema"); case _ => false })
    assert(ClaudeCode.reply(output("error_max_structured_output_retries", true, None)).exists { case ModelReply.Failed(r, _) => r.contains("error_max"); case _ => false })
    ClaudeCode.reply(output("error_during_execution", true, None, "Not logged in")) match
      case Left(ModelError.Status(_, "claude", d)) => assert(d.contains("Not logged in"), d)
      case other                                    => fail(other.toString)

  test("Fabrik: Anbieter claudecode"):
    val t: Transport[IO] = _ => IO.pure(Left("nie"))
    assert(ModelAdapter[IO](t, config, check).isInstanceOf[ClaudeCode[IO]])
    assertEquals(Provider.parse("claudecode"), Some(Provider.ClaudeCode))

  test("Zu große Kommandozeile: 413 ohne Prozess"):
    val c = config.copy(endpoint = "claude-gibt-es-nicht", workDir = Files.createTempDirectory("cc-work").toString)
    val body = Json.obj("schema" -> Json.fromString("x" * CliProcess.MaxArgs)).noSpaces
    assertEquals(runIO(ClaudeCodeTransport[IO](c).post(body)).map(_.status), Right(413))

  private def fake(body: String): Path =
    val p = Files.createTempDirectory("cc-bin").resolve("claude")
    Files.writeString(p, "#!/bin/sh\n" + body)
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"))
    p

  test("Prozess: System-Prompt als Datei, Nutzerprompt über stdin, leeres Verzeichnis verschwindet danach"):
    assume(Platform.isLinux, s"Fake-Programm als Shell-Skript nur unter Linux, hier ${Platform.current}")
    // gibt den Nutzerprompt (stdin) als Done-Text zurück, wenn system.txt da ist und mit dem System-Prompt beginnt
    val exe = fake(
      """grep -q '^SYS' system.txt || exit 3
        |p=$(cat)
        |printf '{"type":"result","subtype":"success","is_error":false,"structured_output":{"term":{"type":"Done","summary":"%s"}},"usage":{"input_tokens":5}}' "$p"
        |""".stripMargin
    )
    val work = Files.createTempDirectory("cc-work")
    val c = config.copy(endpoint = exe.toString, workDir = work.toString)
    val r = runIO(ClaudeCodeTransport[IO](c).post(ClaudeCode.request(c, "SYS", 4, "hallo").noSpaces))
    assertEquals(r.map(_.status), Right(200))
    assertEquals(r.map(x => ClaudeCode.reply(x.body)), Right(Right(ModelReply.Answer("""{"term":{"type":"Done","summary":"hallo"}}""", 5L))))
    assertEquals(Files.list(work).count(), 0L)
