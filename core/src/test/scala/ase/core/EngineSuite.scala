package ase.core

import ase.core.ExecSupport.*
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Execute gegen F[_] = State, ohne Dateisystem und ohne IO: Fill-Ablauf, Tore, Budget, Scope, Ausgang, Replay. */
class EngineSuite extends munit.ScalaCheckSuite:
  private val h = Origin.Human
  private val check = CheckConfig.default
  private val config = ExecConfig.default
  private val root = Envelope(OpKind.values.toSet - OpKind.Shell, ".", 30, 1, Set.empty)
  private val src = Envelope(Set(OpKind.ReadFile, OpKind.EditFile), "src", 4, 0, Set.empty)
  private val files = Map("src/A.scala" -> "val a = 1\n", "secret/key.txt" -> "geheim", "README.md" -> "# x\n")

  private def run(term: Term, model: FillRequest => ModelReply, ask: String => Option[String] = _ => None, cfg: ExecConfig = config) =
    val approved = approve(term, root, check)
    val (fs, result) = Execute.run[W](approved, ports(fakeTools(check.argPolicies), model, ask), cfg).run(files).value
    (approved, fs, result)

  /** Skript-Modell: Antworten je Loch und Versuch; merkt sich nichts, die Anfragen prüft der Test über das Log. */
  private def script(answers: (String, Int, Term)*): FillRequest => ModelReply = r =>
    answers.collectFirst { case (at, k, t) if at == r.at && k == r.attempt => ModelReply.Answer(submission(t), 100) }
      .getOrElse(ModelReply.Failed("keine Antwort im Skript", 0))

  private def holeTerm(steps: Term*) = Term.Seq(steps.toList, h)
  private val fill = Origin.Model("fill")

  test("Done im Fill beendet nur das Loch; die Zusammenfassung ist das Ergebnis und Hinweis des Agenten"):
    val t = holeTerm(Term.Hole("ändere", src, h), Term.ReadFile("README.md", h))
    val f = Term.Seq(List(Term.EditFile("src/A.scala", "1", "2", fill), Term.Done("a ist jetzt 2", fill), Term.ReadFile("src/A.scala", fill)), fill)
    val (_, fs, r) = run(t, script(("1", 1, f)))
    assertEquals(r.outcome, Outcome.Done)
    assertEquals(fs("src/A.scala"), "val a = 2\n")
    // der ReadFile nach Done im Fill lief nicht, der ReadFile nach dem Loch schon
    assertEquals(r.events.collect { case Event.Step(at, c, _) => (at.show, c) }, Vector("1.1.1" -> Call.EditFile("src/A.scala", "1", "2"), "2" -> Call.ReadFile("README.md")))
    assertEquals(r.stats.hints, List(StepId(Vector(1)) -> "a ist jetzt 2"))
    assertEquals(r.stats.steps, 1L + 2 + 1)

  test("Tor 2 lehnt ab: kein Syscall, Handlungsanweisung im nächsten Versuch"):
    val t = holeTerm(Term.Hole("ändere", src, h))
    var requests = Vector.empty[FillRequest]
    val model = script(("1", 1, Term.ReadFile("secret/key.txt", fill)), ("1", 2, Term.Done("nichts zu tun", fill)))
    val (_, _, r) = run(t, req => { requests :+= req; model(req) })
    assertEquals(r.outcome, Outcome.Done)
    assert(!r.events.exists { case Event.Step(_, _, _) => true; case _ => false })
    val rejected = r.events.collect { case e: Event.FillRejected => e }
    assertEquals(rejected.map(_.gate), Vector(Gate.DryRun))
    assert(rejected.head.toModel.contains("liegt außerhalb von src"), rejected.head.toModel)
    assertEquals(requests.map(_.attempt), Vector(1, 2))
    assertEquals(requests(1).errors, Vector(rejected.head.toModel))

  test("Decoder der Hülle: verbotener Knoten scheitert schon beim Lesen (D#25)"):
    val t = holeTerm(Term.Hole("lies", src.copy(allowedOps = Set(OpKind.ReadFile)), h))
    val (_, _, r) = run(t, script(("1", 1, Term.EditFile("src/A.scala", "1", "2", fill)), ("1", 2, Term.Done("ok", fill))))
    assertEquals(r.events.collect { case e: Event.FillRejected => e.gate }, Vector(Gate.Decode))

  test("Laufzeitfehler (CAS): neuer Fill mit dem Fehler, Gelaufenes bleibt, Budget schrumpft (D#15)"):
    val t = holeTerm(Term.Hole("ändere", src, h))
    var requests = Vector.empty[FillRequest]
    val model = script(
      ("1", 1, Term.Seq(List(Term.ReadFile("src/A.scala", fill), Term.EditFile("src/A.scala", "val b", "x", fill)), fill)),
      ("1", 2, Term.Seq(List(Term.EditFile("src/A.scala", "val a", "val b", fill), Term.Done("umbenannt", fill)), fill))
    )
    val (_, fs, r) = run(t, req => { requests :+= req; model(req) })
    assertEquals(r.outcome, Outcome.Done)
    assertEquals(fs("src/A.scala"), "val b = 1\n")
    assertEquals(requests.map(_.remainingSteps), Vector(4, 2))
    assert(requests(1).errors.head.contains("kommt in „src/A.scala“ nicht vor"), requests(1).errors)
    // der Lesevorgang aus Versuch 1 steht im Kontext von Versuch 2
    assert(requests(1).scope.exists(_.at == "1.1.1"), requests(1).scope)
    assertEquals(r.events.collect { case e: Event.FillFailed => e.error }, Vector(ToolError.CasMissing("src/A.scala")))

  test("Versuche erschöpft: Lauf endet mit aborted"):
    val t = holeTerm(Term.Hole("ändere", src, h), Term.Done("nie", h))
    val (_, _, r) = run(t, _ => ModelReply.Answer("kein JSON", 0))
    assertEquals(r.outcome, Outcome.Aborted(AbortReason.AttemptsExhausted(StepId(Vector(1)), 3)))
    assertEquals(r.stats.modelCalls, 3)

  test("Ask: Antwort ist die Ausgabe, keine Antwort beendet den Lauf mit ask"):
    val t = holeTerm(Term.Ask("weiter?", h), Term.If(Pred.LastOutputContains("ja"), Term.Done("weiter", h), Term.Done("halt", h), h))
    val (_, _, answered) = run(t, script(), _ => Some("ja"))
    assertEquals(answered.events.collect { case Event.Finished(_, s) => s }, Vector("weiter"))
    val (_, _, silent) = run(t, script())
    assertEquals(silent.outcome, Outcome.Ask("weiter?"))
    assertEquals(silent.outcome.label, "ask")

  test("Scope: Ergebnis mit Pfad außerhalb des pathPrefix erscheint nicht im Fill-Kontext"):
    val t = holeTerm(Term.ReadFile("secret/key.txt", h), Term.ReadFile("src/A.scala", h), Term.Hole("ändere", src, h))
    var requests = Vector.empty[FillRequest]
    run(t, req => { requests :+= req; ModelReply.Answer(submission(Term.Done("x", fill)), 1) })
    assertEquals(requests.head.scope.map(_.at), Vector("2"))
    assert(!requests.head.scope.exists(_.text.contains("geheim")))

  test("Top-Level-Fehler bricht ab; das Limit der Modellaufrufe ebenso"):
    val (_, _, failed) = run(holeTerm(Term.EditFile("README.md", "fehlt", "x", h), Term.Done("nie", h)), script())
    assertEquals(failed.outcome, Outcome.Aborted(AbortReason.StepFailed(StepId(Vector(1)), ToolError.CasMissing("README.md"))))
    val (_, _, limited) = run(holeTerm(Term.Hole("x", src, h)), _ => ModelReply.Failed("nichts", 0), cfg = config.copy(maxModelCalls = 2))
    assertEquals(limited.outcome, Outcome.Aborted(AbortReason.LimitReached(Limit.ModelCalls(2))))

  test("RepeatUntil ohne Schritt im Rumpf hört auf, statt max-mal zu kreisen"):
    val t = Term.RepeatUntil(Term.Seq(Nil, h), Pred.FileExists("nie.txt"), Int.MaxValue, h)
    val (_, _, r) = run(holeTerm(Term.ReadFile("README.md", h), t), script())
    assertEquals(r.events.count { case Event.Step(_, _: Call.FileExists, _) => true; case _ => false }, 1)

  test("Zeitlimit: Replay erkennt die Stelle aus dem Log"):
    val approved = approve(holeTerm(Term.ReadFile("README.md", h), Term.ReadFile("src/A.scala", h)), root, check)
    val clock: Clock[W] = pos => cats.data.State.pure(pos >= 2)
    val p = ports(fakeTools(check.argPolicies), script(), _ => None).copy(clock = clock)
    val r = Execute.run[W](approved, p, config).runA(files).value
    assertEquals(r.outcome, Outcome.Aborted(AbortReason.LimitReached(Limit.Time(config.maxDuration.toSeconds))))
    assertEquals(Replay.verify(approved, r.events, config).map(_.events), Right(r.events))

  test("Record-Log: erstes Ereignis ist der termHash, jeder angenommene Fill hat seinen Hash, Roundtrip über JSON Lines"):
    val t = holeTerm(Term.Hole("ändere", src, h))
    val f = Term.Done("ok", fill)
    val (approved, _, r) = run(t, script(("1", 1, f)))
    assertEquals(r.events.head, Event.Start(approved.termHash, root, approved.term))
    val accepted = r.events.collect { case e: Event.FillAccepted => e }
    assertEquals(accepted.map(_.hash), Vector(TermHash.of(TestSupport.stamp(f, Origin.Model("fill-1")), src)))
    val text = RecordLog.render(r.events)
    assert(text.linesIterator.forall(_.startsWith(s"{\"v\":${RecordLog.Version},")), text)
    assertEquals(RecordLog.parse(text), Right(r.events))

  test("Record-Log: andere Version wird abgelehnt"):
    // Version 1 kannte die Schrittzahl im End-Ereignis noch nicht
    assertEquals(RecordLog.parseLine("""{"v":1,"type":"End","outcome":{"type":"Done"}}""", 1), Left(RecordError.UnsupportedVersion(1, 1)))
    assertEquals(RecordLog.parseLine("""{"v":2,"type":"End","outcome":{"type":"Done"},"steps":3}""", 1), Right(Event.End(Outcome.Done, 3L)))

  // ---------- Gesetze über generierte Pläne und beliebige Modellantworten

  private val plans: Gen[(Envelope, Term, Long)] =
    for
      (env, t) <- Gen.oneOf(Gens.validPlan, Gens.scopedPlan(Gens.commands, List("README.md", "secret/key.txt", "src/A.scala")))
      seed <- Gen.long
    yield (env, t, seed)

  property("Replay(Record(t)) == Execute(t), auch über JSON Lines"):
    forAll(plans) { (env, t, seed) =>
      val approved = approve(t, env, check)
      val ask: String => Option[String] = q => Option.when((q, seed).hashCode % 2 == 0)("weiter")
      val r = Execute.run[W](approved, ports(fakeTools(check.argPolicies), adversarialModel(seed), ask), config).runA(files).value
      val reread = RecordLog.parse(RecordLog.render(r.events)).fold(e => fail(e.message), identity)
      Replay.verify(approved, reread, config).map(_.events) == Right(r.events)
    }

  property("AdversarialFill: kein Syscall außerhalb von Wurzel- oder Loch-Hülle, Scope nach Hülle, nie mehr Schritte als DryRun erlaubt"):
    forAll(plans) { (env, t, seed) =>
      val approved = approve(t, env, check)
      var requests = Vector.empty[FillRequest]
      val adversary = adversarialModel(seed)
      val model: FillRequest => ModelReply = req => { requests :+= req; adversary(req) }
      val r = Execute.run[W](approved, ports(fakeTools(check.argPolicies), model, _ => Some("ja")), config).runA(files).value
      val esc = escapes(r.events, env)
      assert(esc.isEmpty, esc.mkString("\n"))
      val leaks = scopeLeaks(requests, r.events, check)
      assert(leaks.isEmpty, leaks.mkString("\n"))
      assert(r.stats.steps <= approved.checked.worstCaseSteps, s"${r.stats.steps} > ${approved.checked.worstCaseSteps}")
      assert(r.events.last.isInstanceOf[Event.End])
      true
    }

  // ---------- Mehrrunden-Fill (D#55)

  /** Antworten je Runde; Versuche spielen hier keine Rolle. */
  private def rounds(answers: (Int, Term)*): FillRequest => ModelReply = r =>
    answers.collectFirst { case (n, t) if n == r.round => ModelReply.Answer(submission(t), 100) }
      .getOrElse(ModelReply.Failed("keine Antwort im Skript", 0))

  test("D#55: Fill ohne Done liest, die nächste Runde sieht das Gelesene und ändert per CAS"):
    val t = holeTerm(Term.Hole("ändere", src, h))
    var requests = Vector.empty[FillRequest]
    val model = rounds(
      1 -> Term.ReadFile("src/A.scala", fill),
      2 -> Term.Seq(List(Term.EditFile("src/A.scala", "val a = 1", "val a = 2", fill), Term.Done("a ist jetzt 2", fill)), fill)
    )
    val (approved, fs, r) = run(t, req => { requests :+= req; model(req) })
    assertEquals(r.outcome, Outcome.Done)
    assertEquals(fs("src/A.scala"), "val a = 2\n")
    assertEquals(requests.map(q => (q.round, q.maxRounds, q.attempt, q.remainingSteps)), Vector((1, 3, 1, 4), (2, 3, 1, 3)))
    assert(requests(1).scope.exists(l => l.label == "read src/A.scala" && l.text.contains("val a = 1")), requests(1).scope)
    assertEquals(r.events.collect { case Event.HoleRound(at, n) => (at.show, n) }, Vector("1" -> 2))
    assertEquals(r.stats.hints, List(StepId(Vector(1)) -> "a ist jetzt 2"))
    assertEquals(Replay.verify(approved, r.events, config).map(_.events), Right(r.events))
    val reread = RecordLog.parse(RecordLog.render(r.events)).fold(e => fail(e.message), identity)
    assertEquals(reread, r.events)
    assert(RunLog.render(r.events, RunLog.Footer(0, None)).contains("round  Runde 2: der Fill davor endete ohne Done"))

  test("D#55: höchstens maxRoundsPerHole Runden; danach endet das Loch ohne Zusammenfassung"):
    val t = holeTerm(Term.Hole("lies", src.copy(maxSteps = 10), h), Term.ReadFile("README.md", h))
    var requests = Vector.empty[FillRequest]
    val (_, _, r) = run(t, req => { requests :+= req; ModelReply.Answer(submission(Term.ReadFile("src/A.scala", fill)), 1) })
    assertEquals(r.outcome, Outcome.Done)
    assertEquals(requests.map(_.round), Vector(1, 2, 3))
    assertEquals(r.events.collect { case Event.HoleRound(_, n) => n }, Vector(2, 3))
    assertEquals(r.stats.hints, Nil)
    val one = run(t, _ => ModelReply.Answer(submission(Term.ReadFile("src/A.scala", fill)), 1), cfg = config.copy(maxRoundsPerHole = 1))._3
    assertEquals(one.stats.modelCalls, 1)

  test("D#56: ein leerer Fill ist ein Fehlversuch mit Handlungsanweisung, nicht das stumme Ende des Lochs"):
    var requests = Vector.empty[FillRequest]
    val model = script(("1", 1, Term.Seq(Nil, fill)), ("1", 2, Term.Done("nichts zu tun, weil x", fill)))
    val (_, _, r) = run(holeTerm(Term.Hole("nichts", src, h)), req => { requests :+= req; model(req) })
    assertEquals(r.outcome, Outcome.Done)
    assertEquals(r.events.collect { case e: Event.FillRejected => e.gate }, Vector(Gate.DryRun))
    assert(requests(1).errors.head.contains("Der Teilterm tut nichts"), requests(1).errors)
    assertEquals(r.stats.hints, List(StepId(Vector(1)) -> "nichts zu tun, weil x"))
    assert(DryRun.fill(Term.If(Pred.LastOutputEmpty, Term.Seq(Nil, fill), Term.Seq(Nil, fill), fill), src, check).isLeft)

  test("D#55: keine weitere Runde ohne Restbudget"):
    val tight = holeTerm(Term.Hole("lies", src.copy(maxSteps = 1), h))
    val (_, _, r) = run(tight, rounds(1 -> Term.ReadFile("src/A.scala", fill)))
    assertEquals((r.outcome, r.stats.modelCalls), (Outcome.Done, 1))

  test("D#55: Kosten zählen Runden mal Versuche je Loch"):
    assertEquals(Cost.of(holeTerm(Term.Hole("x", src, h))).worstCaseModelCalls, 9L)
    assertEquals(Cost.of(holeTerm(Term.Hole("x", src, h)), CostConfig(3, 4000, roundsPerHole = 1)).worstCaseModelCalls, 3L)

  test("Ask bekommt das Ergebnis des Schritts davor, damit der Mensch sieht, worauf sich die Frage bezieht (D#73)"):
    val approved = approve(Term.Seq(List(Term.ReadFile("README.md", h), Term.Ask("weiter?", h)), h), root, check)
    var seen = Option.empty[StepResult]
    val p = ports(fakeTools(check.argPolicies), _ => ModelReply.Failed("x", 0), _ => None).copy(ask = (_, last) =>
      seen = Some(last)
      cats.Applicative[W].pure(Some("ja"))
    )
    Execute.run[W](approved, p, config).runA(files).value
    assertEquals(seen.flatMap(_.output), Some("# x\n"))
