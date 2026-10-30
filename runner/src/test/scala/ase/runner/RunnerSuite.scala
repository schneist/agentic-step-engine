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
    assert(PlanFile.parse(text.replace("\"v\" : 3", "\"v\" : 0"), 5).isLeft)
    assert(PlanFile.parse(text.replace("\"v\" : 3", "\"v\" : 4"), 5).isLeft)
    // Version 1 ohne candidates und agreeing wird weiter gelesen (D#63)
    val v1 = PlanFile.encode(file).hcursor.downField("plan").withFocus(_.mapObject(_.remove("candidates").remove("agreeing"))).top.get
      .deepMerge(Json.obj("v" -> Json.fromInt(1)))
    assertEquals(PlanFile.parse(v1.noSpaces, 5), Right(file))
    val voted = file.copy(candidates = 3, agreeing = 2)
    assertEquals(PlanFile.parse(PlanFile.encode(voted).noSpaces, 5), Right(voted))
    // sourceSha seit Version 3 (D#74); ohne das Feld, wie in Version 2, bleibt es leer
    val hashed = file.copy(sourceSha = Some("ab" * 32))
    assertEquals(PlanFile.parse(PlanFile.encode(hashed).noSpaces, 5), Right(hashed))
    assert(!PlanFile.encode(file).noSpaces.contains("sourceSha"))
    assertEquals(PlanFile.parse(PlanFile.encode(file).deepMerge(Json.obj("v" -> Json.fromInt(2))).noSpaces, 5), Right(file))
    assert(PlanFile.parse(PlanFile.encode(file).deepMerge(Json.obj("extra" -> Json.True)).noSpaces, 5).isLeft)

  test("ase stats: gruppiert nach Quelle und Fassung, stellt je Agentenschritt Erlaubtes und Genutztes gegenüber (D#74)"):
    val wide = Envelope(Set(OpKind.ReadFile, OpKind.EditFile), ".", 4, 0, Set.empty)
    val fillTerm = Term.Seq(List(Term.ReadFile("src/A.scala", Origin.Model("fill-1")), Term.Done("ok", Origin.Model("fill-1"))), Origin.Model("fill-1"))
    val term = Term.Seq(List(Term.Hole("prüfe", wide, h)), h)
    def events(tokens: Long) = Vector(
      Event.Start(TermHash.of(term, root), root, term),
      Event.Hole(StepId(Vector(1)), "prüfe", wide),
      Event.Fill(StepId(Vector(1)), 1, "fill-1", ModelReply.Answer("{}", tokens)),
      Event.FillAccepted(StepId(Vector(1)), 1, TermHash.of(fillTerm, wide), 2, 4, fillTerm),
      Event.Step(StepId(Vector(1, 1, 1)), Call.ReadFile("src/A.scala"), Reply(List("src/A.scala"), Right(StepResult(None, Some("x"))), "")),
      Event.Finished(StepId(Vector(1, 1, 2)), "ok"),
      Event.HoleDone(StepId(Vector(1)), "ok"),
      Event.End(Outcome.Done, 3)
    )
    def summary(tokens: Long, seconds: Double, source: Boolean) =
      Json.obj(
        "outcome" -> Json.fromString("done"),
        "tokens" -> Json.fromLong(tokens),
        "seconds" -> Json.fromDoubleOrNull(seconds),
        "fillCalls" -> Json.fromInt(1),
        "planFingerprint" -> Json.fromString("f1")
      ).deepMerge(if source then Json.obj("source" -> Json.fromString("flows/pruefe.json"), "sourceSha" -> Json.fromString("abcdef0123456789")) else Json.obj())
    val runs = Vector(
      RunStats.Run("a", summary(1000, 10, true), Some(events(1000))),
      RunStats.Run("b", summary(3000, 30, true), Some(events(3000))),
      RunStats.Run("c", summary(500, 5, false), Some(events(500)))
    )
    val groups = RunStats.group(runs)
    assertEquals(groups.map(_.runs.size), Vector(2, 1))
    val out = RunStats.render(groups)
    assert(out.contains("== pruefe.json · Fassung abcdef01 · 2 Läufe"), out)
    assert(out.contains("Median 2.0k · 1.0k bis 3.0k"), out)
    assert(out.contains("Median 20 s · 10 s bis 30 s"), out)
    assert(out.contains("Agentenschritt 1 · 2 Aufrufe in 2 Läufen"), out)
    assert(out.contains("nie genutzt: EditFile"), out)
    assert(out.contains("alle Pfade enger als erlaubt, etwa unter src/A.scala statt ."), out)
    assert(out.contains("höchstens 2 von 4 Schritten genutzt"), out)
    assert(out.contains("1 Form und 1 Inhalt in 2 Teiltermen"), out)
    assert(out.contains("in allen 2 Teiltermen derselbe Teilterm; Kandidat für feste Schritte statt Agentenschritt"), out)
    assert(out.contains("== ohne Flow-Kennung · Form "), out)

  test("ase tighten: engere Hülle aus Läufen derselben Form, Probe gegen den gebundenen Term, Flow sonst gleich (D#75)"):
    val wide = Envelope(Set(OpKind.ReadFile, OpKind.WriteFile), ".", 5, 0, Set.empty)
    val flowRoot = Envelope(Set(OpKind.ReadFile, OpKind.WriteFile, OpKind.Hole), ".", 10, 1, Set.empty)
    val flow = Flow("Bericht", Map("v" -> "^[0-9]+$"), flowRoot, Term.Seq(List(Term.ReadFile("in/${v}.txt", h), Term.Hole("schreibe", wide, h)), h))
    def events(v: Int) =
      val bound = Term.Seq(List(Term.ReadFile(s"in/$v.txt", h), Term.Hole("schreibe", wide, h)), h)
      val fillTerm = Term.Seq(List(Term.WriteFile(s"out/$v.md", "x", Origin.Model("fill-1")), Term.Done("ok", Origin.Model("fill-1"))), Origin.Model("fill-1"))
      Vector(
        Event.Start(TermHash.of(bound, flowRoot), flowRoot, bound),
        Event.Step(StepId(Vector(1)), Call.ReadFile(s"in/$v.txt"), Reply(List(s"in/$v.txt"), Right(StepResult(None, Some("x"))), "")),
        Event.Hole(StepId(Vector(2)), "schreibe", wide),
        Event.Fill(StepId(Vector(2)), 1, "fill-1", ModelReply.Answer("{}", 100)),
        Event.FillAccepted(StepId(Vector(2)), 1, TermHash.of(fillTerm, wide), 2, 5, fillTerm),
        Event.Step(StepId(Vector(2, 1, 1)), Call.WriteFile(s"out/$v.md", "x"), Reply(List(s"out/$v.md"), Right(StepResult(None, None)), "")),
        Event.Finished(StepId(Vector(2, 1, 2)), "ok"),
        Event.HoleDone(StepId(Vector(2)), "ok"),
        Event.End(Outcome.Done, 4)
      )
    val other = Vector(Event.Start(TermHash.of(Term.Done("x", h), flowRoot), flowRoot, Term.Done("x", h)), Event.End(Outcome.Done, 1))
    val runs = Vector(1, 2, 3).map(v => RunStats.Run(s"r$v", Json.obj("outcome" -> Json.fromString("done")), Some(events(v)))) :+
      RunStats.Run("fremd", Json.obj("outcome" -> Json.fromString("done")), Some(other))
    val r = FlowTighten.run(flow, "sha", runs, 3, check).fold(fail(_), identity)
    assertEquals(r.runs, 3)
    val neu = Tighten.holes(r.flow.term).head._2.envelope
    assertEquals(neu, Envelope(Set(OpKind.WriteFile), "out", 2, 0, Set.empty, Some(".")))
    assertEquals(r.flow.term match { case Term.Seq(first :: _, _) => first; case other => other }, Term.ReadFile("in/${v}.txt", h))
    assertEquals(Flow.parse(Flow.encode(r.flow).noSpaces).map(_.term), Right(r.flow.term))
    assert(r.probe.contains("DryRun ok gegen den Term aus r3"), r.probe)
    val out = FlowTighten.render(flow, r)
    assert(out.contains("1 von 1 Agentenschritten enger") && out.contains("ReadFile nie genutzt"), out)
    assert(FlowTighten.run(flow, "sha", runs.takeRight(1), 3, check).isLeft)
    // Läufe einer anderen Fassung zählen nicht, auch bei gleicher Form; Läufe dieser Fassung schon (Review)
    def tagged(sha: String) = runs.init.map(r => r.copy(summary = r.summary.deepMerge(Json.obj("sourceSha" -> Json.fromString(sha)))))
    assert(FlowTighten.run(flow, "sha", tagged("anders"), 3, check).isLeft)
    assertEquals(FlowTighten.run(flow, "sha", tagged("sha"), 3, check).map(_.runs), Right(3))
    // alle Läufe mit v=1: Pfad bleibt
    val same = Vector.fill(3)(RunStats.Run("r", Json.obj("outcome" -> Json.fromString("done")), Some(events(1))))
    val s = FlowTighten.run(flow, "sha", same, 3, check).fold(fail(_), identity)
    assertEquals(Tighten.holes(s.flow.term).head._2.envelope.pathPrefix, ".")

  test("Konfiguration: Standards, Überschreiben, unbekannte Felder und ungültiger Effort abgelehnt"):
    assertEquals(RunnerConfig.parse("{}"), Right(RunnerConfig.default))
    val c = RunnerConfig.parse("""{"model":{"effort":"xhigh"},"limits":{"maxModelCalls":7,"shellTimeoutSeconds":5},"nestingLimit":4,"planRounds":2}""")
      .fold(fail(_), identity)
    assertEquals((c.model.effort, c.model.model, c.exec.maxModelCalls, c.exec.shellTimeout, c.check.nestingLimit, c.planRounds), ("xhigh", ModelConfig.default.model, 7, 5.seconds, 4, 2))
    assert(RunnerConfig.parse("""{"limits":{"maxSteps":3}}""").isLeft)
    assertEquals(RunnerConfig.parse("""{"bestOfN":3}""").map(_.bestOfN), Right(3))
    assert(RunnerConfig.parse("""{"bestOfN":0}""").isLeft)
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
    val a = RunnerConfig.parse("""{"model":{"provider":"agy","endpoint":"/mnt/c/agy.exe","workDir":"/mnt/c/tmp"}}""").fold(fail(_), identity)
    assertEquals((a.model.provider, a.model.model, a.model.endpoint, a.model.workDir), (Provider.Agy, "gemini-3.8-flash-medium", "/mnt/c/agy.exe", "/mnt/c/tmp"))
    val s = RunnerConfig.parse("""{"scripts":["./run-tests.sh"]}""").fold(fail(_), identity)
    assertEquals(s.check.argPolicies, CheckConfig.default.argPolicies :+ ArgPolicy.script("./run-tests.sh"))
    List("run-tests.sh", "./..", "../x.sh", "./a/../../x.sh", "./.git/hooks/x", "./").foreach(bad => assert(RunnerConfig.parse(s"""{"scripts":["$bad"]}""").isLeft, bad))
    // Skripte in Unterverzeichnissen (D#72): Policy ohne Argumente, als Workspace-Code markiert
    val n = RunnerConfig.parse("""{"scripts":["./ase/cfd/deckel_setzen.sh"]}""").fold(fail(_), identity).check
    assert(n.argPolicies.contains(ArgPolicy.script("./ase/cfd/deckel_setzen.sh")))
    assertEquals(n.runsWorkspaceCode("./ase/cfd/deckel_setzen.sh"), Some(List("ase/cfd/deckel_setzen.sh")))

  test("programs: eigene Argument-Policy, runsCode macht daraus Workspace-Code (D#68)"):
    val c = RunnerConfig.parse("""{"programs":[{"binary":"python3","flags":["-B"],"runsCode":["."]},{"binary":"wc","flags":["-l"]}]}""")
      .fold(fail(_), identity).check
    assertEquals(c.argPolicies.takeRight(2), List(
      ArgPolicy("python3", Nil, Set("-B"), Set.empty, Set.empty, Positional.Paths),
      ArgPolicy("wc", Nil, Set("-l"), Set.empty, Set.empty, Positional.Paths)
    ))
    assertEquals((c.runsWorkspaceCode("python3"), c.runsWorkspaceCode("wc")), (Some(List(".")), None))
    val root = Envelope(Set(OpKind.Shell), ".", 5, 0, Set(AllowedCommand("python3", Nil)))
    assert(DryRun.plan(Term.Shell("python3", List("-B", "nachweis.py"), h), root, c).isRight)
    List(List("-c", "print(1)"), List("-m", "http.server"), List("../x.py")).foreach(args =>
      assert(DryRun.plan(Term.Shell("python3", args, h), root, c).isLeft, args.toString)
    )
    val git = RunnerConfig.parse("""{"programs":[{"binary":"git","args":["show"],"positional":"NoArgs"}]}""").fold(fail(_), identity)
    assertEquals(git.check.argPolicies.last, ArgPolicy("git", List("show"), Set.empty, Set.empty, Set.empty, Positional.NoArgs))
    List(
      """{"binary":"./x.py"}""",
      """{"binary":"/usr/bin/python3"}""",
      """{"binary":"grep"}""",
      """{"binary":"git","args":["status"]}""",
      """{"binary":"p","flags":["-c x"]}""",
      """{"binary":"p","flags":["--"]}""",
      """{"binary":"p","prefixFlags":["--out"]}""",
      """{"binary":"p","positional":"Alles"}""",
      """{"binary":"p","runsCode":["../src"]}""",
      """{"binary":"p","env":{}}"""
    ).foreach(bad => assert(RunnerConfig.parse(s"""{"programs":[$bad]}""").isLeft, bad))
    assert(RunnerConfig.parse("""{"programs":[{"binary":"p"},{"binary":"p"}]}""").isLeft)

  // eine andere Form als good (ReadFile statt ListDir): BestOfN stimmt über die Form ab (D#65)
  private val other = Term.Seq(List(Term.ReadFile("docs/a.md", h), Term.Hole("Schreibe a.md", Envelope(Set(OpKind.WriteFile), "a.md", 2, 0, Set.empty), h)), h)

  test("BestOfN: zwei von drei gleich ist eine Mehrheit, gewählt der erste davon; Aufrufe und Tokens aller drei zählen"):
    val replies = List(answer(other), answer(good), answer(good))
    val ((_, seen), p) = Planner.run[S](scripted, "Aufgabe", root, check, 3, bestOf = 3).run((replies, Vector.empty)).value
    assertEquals(seen.map(_.callId), Vector("plan-1.1", "plan-1.2", "plan-1.3"))
    assertEquals(p.checked.map(_.term.origin), Some(Origin.Model("plan-1.2")))
    assertEquals((p.calls, p.tokens, p.split), (3, 3000L, false))
    assert(p.log.exists(_.startsWith("bestof   2 von 3")), p.log)

  test("BestOfN: ohne Mehrheit kein Plan, sondern der Mensch; abgelehnte Kandidaten stimmen mit keinem überein"):
    val replies = List(answer(other), answer(tooDeep), answer(good))
    val p = Planner.run[S](scripted, "Aufgabe", root, check, 3, bestOf = 3).runA((replies, Vector.empty)).value
    assertEquals((p.checked, p.split, p.calls, p.rejections), (None, true, 3, 1))
    assert(p.log.exists(_.contains("keine Mehrheit unter 3")), p.log)
    assertEquals(PlanSummary.of(p).hcursor.get[String]("planOutcome"), Right("split"))
    assertEquals(PlanSummary.of(p).hcursor.get[Int]("planAgreeing"), Right(0))

  test("BestOfN: eine Runde ohne geprüften Kandidaten geht mit den Gründen an das Modell zurück"):
    val replies = List.fill(3)(answer(tooDeep)) ++ List(answer(good), answer(good), answer(other))
    val ((_, seen), p) = Planner.run[S](scripted, "Aufgabe", root, check, 3, bestOf = 3).run((replies, Vector.empty)).value
    assertEquals(seen.map(_.errors.size), Vector(0, 0, 0, 1, 1, 1))
    assertEquals((p.checked.map(_.term.origin), p.rejections, p.calls), (Some(Origin.Model("plan-2.1")), 3, 6))

  test("Plan-Kennzahlen ohne Lauf (O#23): auch ohne geprüften Plan, Fingerprint nur mit Plan"):
    val rejected = Planner.run[S](scripted, "Aufgabe", root, check, 2).runA((List.fill(2)(answer(tooDeep)), Vector.empty)).value
    val r = PlanSummary.of(rejected).hcursor
    assertEquals((r.get[String]("planOutcome"), r.get[Int]("planCalls"), r.get[Int]("gate1Rejections")), (Right("rejected"), Right(2), Right(2)))
    assert(r.downField("planFingerprint").failed)
    val rejected3 = Planner.run[S](scripted, "Aufgabe", root, check, 1, bestOf = 3).runA((List.fill(3)(answer(tooDeep)), Vector.empty)).value
    assertEquals(PlanSummary.of(rejected3).hcursor.get[Int]("planCandidates"), Right(3))
    val ok = PlanSummary.of(Planner.run[S](scripted, "Aufgabe", root, check, 1).runA((List(answer(good)), Vector.empty)).value).hcursor
    assertEquals((ok.get[String]("planOutcome"), ok.get[String]("planFingerprint")), (Right("checked"), Right(Fingerprint.of(good).hex)))

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
    assertEquals(
      Cli.parse(List("plan", "--envelope", "e", "--task", "t", "--out", "p", "--summary", "s.json")).map { case p: Command.Plan => p.summary; case _ => None },
      Right(Some(Path.of("s.json")))
    )
    Cli.parse(List("run", "--plan", "p", "--envelope", "e", "--workspace", "w", "--approve", "ab", "--no-ask")) match
      case Right(r: Command.Run) => assertEquals((r.approve, r.noAsk), (Some("ab"), true))
      case other                 => fail(other.toString)
    assert(Cli.parse(List("run", "--plan", "p", "--envelope", "e", "--workspace", "w", "--force", "x")).isLeft)

  test("Ask zeigt das Ergebnis davor: Exit-Code, Zeilen im Rahmen, Steuerzeichen sichtbar, lange Ausgaben gekürzt (D#73)"):
    assertEquals(AskContext.render(StepResult(None, None)), "")
    assert(AskContext.render(StepResult(Some(0), None)).contains("Exit-Code 0): keine Ausgabe"))
    val out = AskContext.render(StepResult(Some(1), Some("a\nb\u001b[2K\n")))
    assert(out.contains("(Exit-Code 1), 2 Zeilen:\n  │ a\n  │ b\\u001b[2K"), out)
    val lang = AskContext.render(StepResult(None, Some((1 to 200).map(i => s"z$i").mkString("\n"))))
    assert(lang.contains("  │ z20\n  │ … 120 Zeilen ausgelassen …\n  │ z141"), lang)
    assert(lang.contains("  │ z200") && !lang.contains("  │ z21\n"), lang)
