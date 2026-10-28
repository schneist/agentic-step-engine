package ase.runner

import ase.core.*
import ase.model.{ModelConfig, PlanModel, PlanRequest, Provider}
import cats.Id
import cats.data.State
import io.circe.Json

import java.nio.file.Path
import scala.concurrent.duration.*

/** Plan-Schleife gegen F[_] (State statt IO), Plan-Datei, Konfiguration, Befehlszeile. */
class RunnerSuite extends munit.FunSuite:
  private val check = CheckConfig.default
  private val h = Origin.Human
  private val root = Envelope(Set(OpKind.ReadFile, OpKind.ListDir, OpKind.WriteFile, OpKind.Hole), ".", 10, 1, Set.empty)
  private def answer(t: Term): ModelReply = ModelReply.Answer(ExecSupport.submission(t), 1000)

  private val good = Term.Seq(
    List(Term.ListDir("src", h), Term.Hole("Schreibe a.md", Envelope(Set(OpKind.WriteFile), "a.md", 2, 0, Set.empty), h)),
    h
  )
  // maxDepth des Lochs gleich der Wurzel: Tor 1 lehnt ab
  private val tooDeep = Term.Seq(List(Term.Hole("x", Envelope(Set(OpKind.ReadFile), ".", 2, 1, Set.empty), h)), h)

  /** Ein Skript-Modell als State: Antworten der Reihe nach, Anfragen gemerkt. */
  type S[A] = State[(List[ModelReply], Vector[PlanRequest]), A]
  private val scripted: PlanModel[S] = r =>
    State { case (replies, seen) =>
      val reply = replies.headOption.getOrElse(ModelReply.Failed("leer", 0))
      ((replies.drop(1), seen :+ r), reply)
    }

  test("Plan: Antwort ohne Term, Verstoß an Tor 1, dann geprüft; Fehler gehen mit dem abgelehnten Term zurück"):
    val replies = List(ModelReply.Failed("Antwort ohne submit_term", 500), answer(tooDeep), answer(good))
    val ((_, seen), p) = Planner.run[S](scripted, "Aufgabe", root, check, 3).run((replies, Vector.empty)).value
    assertEquals((p.calls, p.unusable, p.rejections, p.tokens), (3, 1, 1, 2500L))
    assertEquals(p.checked.map(c => Fingerprint.of(c.term)), Some(Fingerprint.of(good)))
    assertEquals(p.checked.map(_.term.origin), Some(Origin.Model("plan-3")))
    assertEquals(seen.map(_.callId), Vector("plan-1", "plan-2", "plan-3"))
    assertEquals(seen.map(_.errors.size), Vector(0, 1, 2))
    assert(seen(2).errors(1).contains("Abgelehnter Term:"), seen(2).errors(1))
    assert(!seen(2).errors(1).contains("origin"), seen(2).errors(1))

  test("Plan: nach allen Runden ohne geprüften Term"):
    val replies = List.fill(3)(answer(tooDeep))
    val p = Planner.run[S](scripted, "Aufgabe", root, check, 3).runA((replies, Vector.empty)).value
    assertEquals((p.checked.isEmpty, p.calls, p.rejections), (true, 3, 3))

  test("Plan: ein Modell darf kein origin mitschicken (D#26)"):
    val withOrigin = Json.obj("term" -> Codec.term(good)).noSpaces
    val model: PlanModel[Id] = _ => ModelReply.Answer(withOrigin, 1)
    val p = Planner.run[Id](model, "Aufgabe", root, check, 1)
    assertEquals(p.rejections, 1)
    assert(p.log.exists(_.contains("decode")), p.log)

  test("Plan-Datei: Roundtrip, origin bleibt, fremde Felder und alte Versionen abgelehnt"):
    val file = PlanFile("evals/1-todos/task.md", "default", root, Map("v" -> "1.0"), Term.Seq(List(Term.ReadFile("a", Origin.Model("plan-2"))), h), 2, 1, 0, 4200L)
    val text = PlanFile.encode(file).spaces2
    assertEquals(PlanFile.parse(text, 5), Right(file))
    assert(PlanFile.parse(text.replace("\"v\" : 1", "\"v\" : 0"), 5).isLeft)
    assert(PlanFile.parse(PlanFile.encode(file).deepMerge(Json.obj("extra" -> Json.True)).noSpaces, 5).isLeft)

  test("Konfiguration: Standards, Überschreiben, unbekannte Felder und ungültiger Effort abgelehnt"):
    assertEquals(RunnerConfig.parse("{}"), Right(RunnerConfig.default))
    val c = RunnerConfig.parse("""{"model":{"effort":"xhigh"},"limits":{"maxModelCalls":7,"shellTimeoutSeconds":5},"nestingLimit":4,"planRounds":2}""")
      .fold(fail(_), identity)
    assertEquals((c.model.effort, c.model.model, c.exec.maxModelCalls, c.exec.shellTimeout, c.check.nestingLimit, c.planRounds), ("xhigh", ModelConfig.default.model, 7, 5.seconds, 4, 2))
    assert(RunnerConfig.parse("""{"limits":{"maxSteps":3}}""").isLeft)
    assert(RunnerConfig.parse("""{"model":{"effort":"turbo"}}""").isLeft)
    val d = RunnerConfig.parse("""{"explainDenials":[{"binary":"git","requiredPrefixArgs":["push"]}]}""").fold(fail(_), identity)
    assertEquals(d.explain.explainDenials, List(AllowedCommand("git", List("push"))))
    val o = RunnerConfig.parse("""{"model":{"provider":"ollama","maxTokens":4000}}""").fold(fail(_), identity)
    assertEquals((o.model.provider, o.model.model, o.model.endpoint, o.model.maxTokens), (Provider.Ollama, "qwen3:4b-instruct", "http://127.0.0.1:11434/api/chat", 4000))
    assert(RunnerConfig.parse("""{"model":{"provider":"openai"}}""").isLeft)
    assertEquals(RunnerConfig.parse("""{"model":{"provider":"ollama","think":true}}""").map(_.model.think), Right(Some(true)))
    assertEquals(RunnerConfig.parse("""{"model":{"provider":"ollama","think":false}}""").map(_.model.think), Right(Some(false)))
    assertEquals(RunnerConfig.parse("""{"model":{"provider":"ollama"}}""").map(_.model.think), Right(None))
    assert(RunnerConfig.parse("""{"model":{"think":"ja"}}""").isLeft)
    val s = RunnerConfig.parse("""{"scripts":["./run-tests.sh"]}""").fold(fail(_), identity)
    assertEquals(s.check.argPolicies, CheckConfig.default.argPolicies :+ ArgPolicy.script("./run-tests.sh"))
    List("run-tests.sh", "./bin/x.sh", "./..", "../x.sh").foreach(bad => assert(RunnerConfig.parse(s"""{"scripts":["$bad"]}""").isLeft, bad))

  test("Lauf-Log zählt Aufrufe und Tokens des Plans mit"):
    val log = RunLog.render(Vector(Event.End(Outcome.Done, 0L)), RunLog.Footer(1, None, planCalls = 2, planTokens = 2500L))
    assert(log.contains("2 Modellaufrufe"), log)
    assert(log.contains("1 Ablehnung an Tor 1"), log)
    assert(log.contains("2.5k Tokens"), log)

  test("Befehlszeile"):
    assertEquals(
      Cli.parse(List("plan", "--envelope", "e.json", "--flow", "f.json", "version=2.4.0", "--out", "p.json")),
      Right(Command.Plan(Path.of("e.json"), None, Some(Path.of("f.json")), Map("version" -> "2.4.0"), Path.of("p.json"), None))
    )
    assert(Cli.parse(List("plan", "--envelope", "e", "--task", "t", "--flow", "f", "--out", "p")).isLeft)
    assert(Cli.parse(List("plan", "--envelope", "e", "--task", "t", "--out", "p", "x=y")).isLeft)
    Cli.parse(List("run", "--plan", "p", "--envelope", "e", "--workspace", "w", "--approve", "ab", "--no-ask")) match
      case Right(r: Command.Run) => assertEquals((r.approve, r.noAsk), (Some("ab"), true))
      case other                 => fail(other.toString)
    assert(Cli.parse(List("run", "--plan", "p", "--envelope", "e", "--workspace", "w", "--force", "x")).isLeft)
