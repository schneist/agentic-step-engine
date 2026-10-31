package ase.core

import ase.core.ExecSupport.*
import cats.data.State
import io.circe.Json

/** Abnahme eines Lochs (D#80): Tor 1, Codec und Schema, Engine mit Replay und Record-Log, Explain. */
class VerifySuite extends munit.FunSuite:
  private val h = Origin.Human
  private val fill = Origin.Model("fill")
  private val check = CheckConfig.default.copy(argPolicies = CheckConfig.default.argPolicies :+ ArgPolicy.script("./run-tests.sh"))
  private val config = ExecConfig.default
  private val root = Envelope(OpKind.values.toSet, ".", 30, 2, Set(AllowedCommand("./run-tests.sh", Nil), AllowedCommand("grep", Nil)))
  private val noShell = root.copy(allowedOps = root.allowedOps - OpKind.Shell, shellAllowlist = Set.empty)
  private val fix = Envelope(Set(OpKind.ReadFile, OpKind.EditFile), "src", 4, 0, Set.empty)
  private val green = Verify("./run-tests.sh", Nil, Pred.LastExitCode(0), 2)
  private val files = Map("src/a.txt" -> "schlecht\n", "run-tests.sh" -> "#!/bin/sh\n")

  private def violations(t: Term, env: Envelope = root): List[Violation] =
    DryRun.plan(t, env, check).fold(_.toList, _ => Nil)

  // ---------- Tor 1

  test("Tor 1: schlechtester Fall je Durchlauf Loch und Abnahme; danach liegt ein Exit-Code vor"):
    val t = Term.Seq(List(Term.Hole("behebe", fix, h, Some(green)), Term.If(Pred.LastExitCode(0), Term.Done("grün", h), Term.Ask("rot", h), h)), h)
    val checked = DryRun.plan(t, root, check).fold(v => fail(v.toList.map(_.render).mkString("\n")), identity)
    assertEquals(checked.worstCaseSteps, 2L * (1 + 4 + 1) + 1)
    assertEquals(Cost.of(t).worstCaseSteps, checked.worstCaseSteps)
    assertEquals(Cost.of(Term.Hole("behebe", fix, h, Some(green))).worstCaseModelCalls, 2L * 3 * 3)
    // ohne Abnahme hat das Ergebnis eines Lochs keinen Exit-Code (D#20)
    val plain = Term.Seq(List(Term.Hole("behebe", fix, h), Term.If(Pred.LastExitCode(0), Term.Done("x", h), Term.Done("y", h), h)), h)
    assert(violations(plain).exists(_.isInstanceOf[Violation.PredicateNeverTrue]))

  test("Tor 1: die Abnahme läuft unter der umgebenden Hülle, nicht unter der des Lochs"):
    val vs = violations(Term.Hole("behebe", fix, h, Some(green)), noShell)
    assert(vs.exists { case Violation.OpNotAllowed(loc, OpKind.Shell, _) => loc.show == "$.verify"; case _ => false }, vs)
    val other = violations(Term.Hole("behebe", fix, h, Some(green.copy(cmd = "./deploy.sh"))))
    assert(other.exists(_.isInstanceOf[Violation.ShellNotAllowed]), other)
    assert(violations(Term.Hole("behebe", fix, h, Some(green.copy(max = 0)))).exists(_.isInstanceOf[Violation.InvalidVerify]))
    val outside = violations(Term.Hole("behebe", fix, h, Some(green.copy(until = Pred.FileExists("../x")))))
    assert(outside.exists(_.isInstanceOf[Violation.InvalidPath]), outside)

  test("Tor 1: ein ändernder Agentenschritt darf den Code seiner Abnahme nicht erreichen"):
    val wide = fix.copy(pathPrefix = ".")
    val vs = violations(Term.Hole("behebe", wide, h, Some(green)))
    assert(vs.exists { case Violation.VerifierWritable(_, code, ".") => code == "run-tests.sh"; case _ => false }, vs)
    assert(vs.exists(_.message.contains("engeren pathPrefix")), vs)
    // nur lesend darf er es
    assertEquals(violations(Term.Hole("prüfe", wide.copy(allowedOps = Set(OpKind.ReadFile)), h, Some(green))), Nil)
    // sbt führt build.sbt, project und src aus: weder darüber noch darunter, auch nicht unter src/test oder src/main
    val sbt = root.copy(shellAllowlist = root.shellAllowlist + AllowedCommand("sbt", List("test")))
    val sbtTest = Verify("sbt", List("test"), Pred.LastExitCode(0), 2)
    for prefix <- List(".", "src", "src/test", "src/main", "project", "build.sbt") do
      val found = violations(Term.Hole("behebe", fix.copy(pathPrefix = prefix), h, Some(sbtTest)), sbt)
      assert(found.exists(_.isInstanceOf[Violation.VerifierWritable]), s"$prefix: $found")
    assertEquals(violations(Term.Hole("schreibe", fix.copy(pathPrefix = "docs"), h, Some(sbtTest)), sbt), Nil)
    // ./run-tests.sh: nur das Skript selbst ist Code der Abnahme
    assertEquals(violations(Term.Hole("behebe", fix, h, Some(green))), Nil)

  // ---------- Codec und Schema

  test("Codec: ohne verify unverändert, mit verify Roundtrip; der Fill-Decoder verlangt Shell; Schema"):
    assert(Codec.term(Term.Hole("x", fix, h)).asObject.exists(!_.contains("verify")))
    val t = Term.Hole("x", fix, h, Some(green))
    assertEquals(Codec.decodeTerm(Codec.term(t), OriginMode.Required, DecodeLimits.all()), Right(t))
    val text = submission(t)
    assertEquals(Submission.decode(text, root, 5, fill), Right(Term.Hole("x", fix, fill, Some(green))))
    assertEquals(Submission.decode(text, noShell, 5, fill), Left(DecodeError.OpNotAllowed(Location.root / "verify", OpKind.Shell)))
    assertEquals(TestSupport.schemaErrors(3, Json.obj("term" -> Codec.term(t, withOrigin = false))), Nil)
    val partial = Codec.term(t, withOrigin = false).mapObject(_.add("verify", Json.obj("cmd" -> Json.fromString("x"))))
    assert(TestSupport.schemaErrors(3, Json.obj("term" -> partial)).nonEmpty)
    assertNotEquals(Fingerprint.of(t), Fingerprint.of(Term.Hole("x", fix, h)))

  // ---------- Engine

  /** ./run-tests.sh ist grün, sobald src/a.txt „gut“ enthält; sonst Exit 1 mit FAIL. */
  private val tools: Tools[W] =
    val base = fakeTools(check.argPolicies)
    (call, env) =>
      call match
        case Call.Shell("./run-tests.sh", Nil) =>
          State { (fs: Files_) =>
            val result =
              if fs.get("src/a.txt").exists(_.contains("gut")) then StepResult(Some(0), Some("1 passed"))
              else StepResult(Some(1), Some("FAIL src/a.txt"))
            (fs, Reply(Nil, Right(result), "1 Zeile"))
          }
        case other => base.call(other, env)

  /** Antworten der Reihe nach, über Versuche und Durchläufe hinweg; merkt sich die Anfragen. */
  private final class Sequence(terms: Term*):
    var requests = Vector.empty[FillRequest]
    val model: FillRequest => ModelReply = r =>
      requests :+= r
      terms.lift(requests.size - 1).fold(ModelReply.Failed("keine Antwort mehr", 0))(t => ModelReply.Answer(submission(t), 100))

  private def run(term: Term, model: FillRequest => ModelReply) =
    val approved = approve(term, root, check)
    val (fs, result) = Execute.run[W](approved, ports(tools, model, _ => None), config).run(files).value
    (approved, fs, result)

  private def edit(old: String, neu: String) =
    Term.Seq(List(Term.EditFile("src/a.txt", old, neu, fill), Term.Done(s"a.txt: $neu", fill)), fill)

  test("Engine: Abnahme rot, der zweite Durchlauf erfährt es und behebt; Replay, Record-Log und Lauf-Log"):
    val t = Term.Seq(List(Term.Hole("behebe", fix, h, Some(green)), Term.Done("fertig", h)), h)
    val s = Sequence(edit("schlecht", "noch schlecht"), edit("noch schlecht", "gut"))
    val (approved, fs, r) = run(t, s.model)
    assertEquals(r.outcome, Outcome.Done)
    assertEquals(fs("src/a.txt"), "gut\n")
    assertEquals(
      r.events.collect { case Event.Verified(at, pass, passed, m) => (at.show, pass, passed, m.isDefined) },
      Vector(("2", 1, false, true), ("2", 2, true, false))
    )
    // der zweite Durchlauf ist ein neuer Aufruf: Versuch 1, volles Budget, die nicht bestandene Abnahme eigens, kein Versuch
    val second = s.requests(1)
    assertEquals((second.at, second.attempt, second.remainingSteps, second.errors), ("1", 1, 4, Vector.empty))
    assert(second.verified.headOption.exists(_.contains("Abnahme nach Durchlauf 1 von 2 nicht bestanden")), second.verified)
    assert(second.verified.head.contains("(Exit-Code 1)"), second.verified)
    // die Abnahme führt Workspace-Code aus; ihre Ausgabe ist darum für jedes Loch sichtbar (D#9)
    assert(second.scope.exists(l => l.at == "2" && l.text.contains("FAIL")), second.scope)
    assert(second.scope.exists(l => l.at == "1" && l.text.contains("noch schlecht")), second.scope)
    assertEquals(r.stats.verifyFailures, 1)
    // zwei Aufrufe des Lochs mit je 1 + 2 Schritten, zwei Abnahmen, Done
    assertEquals(r.stats.steps, 2L * 3 + 2 + 1)
    assertEquals(Replay.verify(approved, r.events, config).map(_.events), Right(r.events))
    assertEquals(RecordLog.parse(RecordLog.render(r.events)), Right(r.events))
    val log = RunLog.render(r.events, RunLog.Footer(0, None))
    assert(log.contains("verify nicht bestanden (Durchlauf 1)"), log)
    assert(log.contains("verify bestanden (Durchlauf 2)"), log)
    assert(log.contains("1 Abnahme nicht bestanden"), log)

  test("Engine: nach max Durchläufen ist das Ergebnis das der Abnahme, und der Plan entscheidet"):
    val t = Term.Seq(List(Term.Hole("behebe", fix, h, Some(green)), Term.If(Pred.LastExitCode(0), Term.Done("grün", h), Term.Ask("immer noch rot", h), h)), h)
    val s = Sequence(edit("schlecht", "schlecht 2"), edit("schlecht 2", "schlecht 3"))
    val (_, fs, r) = run(t, s.model)
    assertEquals(r.outcome, Outcome.Ask("immer noch rot"))
    assertEquals(fs("src/a.txt"), "schlecht 3\n")
    assertEquals(s.requests.size, 2)
    assertEquals(r.events.collect { case Event.Verified(_, pass, passed, m) => (pass, passed, m.isDefined) }, Vector((1, false, true), (2, false, false)))

  test("Engine: die nicht bestandene Abnahme geht in jede Runde des nächsten Durchlaufs, nicht nur in die erste"):
    val t = Term.Hole("behebe", fix, h, Some(green))
    val s = Sequence(edit("schlecht", "noch schlecht"), Term.ReadFile("src/a.txt", fill), edit("noch schlecht", "gut"))
    val (_, fs, r) = run(t, s.model)
    assertEquals(r.outcome, Outcome.Done)
    assertEquals(fs("src/a.txt"), "gut\n")
    assertEquals(s.requests.map(q => (q.round, q.attempt)), Vector((1, 1), (1, 1), (2, 1)))
    assert(s.requests(2).verified.exists(_.contains("Abnahme nach Durchlauf 1")), s.requests(2).verified)

  test("Engine: spätere Löcher sehen nur den letzten Durchlauf, nicht jede frühere Abnahme"):
    val report = Envelope(Set(OpKind.WriteFile), "REPORT.md", 2, 0, Set.empty, Some("."))
    val t = Term.Seq(List(Term.Hole("behebe", fix, h, Some(green.copy(max = 3))), Term.Hole("berichte", report, h)), h)
    val bericht = Term.Seq(List(Term.WriteFile("REPORT.md", "grün", fill), Term.Done("Bericht", fill)), fill)
    val s = Sequence(edit("schlecht", "schlecht 2"), edit("schlecht 2", "schlecht 3"), edit("schlecht 3", "gut"), bericht)
    val (_, _, r) = run(t, s.model)
    assertEquals(r.outcome, Outcome.Done)
    val last = s.requests.last
    assertEquals(last.at, "3")
    assertEquals(last.scope.map(l => (l.at, l.text.contains("passed"))), Vector("1" -> false, "2" -> true))

  test("Engine: die eingeschränkte Algebra lässt ein Loch mit Abnahme ohne Shell nicht laufen (zweite Linie, D#25)"):
    type R[A] = Vector[String]
    val names: AgentAlg[R] = new AgentAlg[R]:
      def readFile(path: String, origin: Origin): R[StepResult] = Vector("read")
      def listDir(path: String, origin: Origin): R[StepResult] = Vector("list")
      def writeFile(path: String, content: String, origin: Origin): R[StepResult] = Vector("write")
      def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): R[StepResult] = Vector("edit")
      def moveFile(from: String, to: String, origin: Origin): R[StepResult] = Vector("move")
      def shell(cmd: String, args: List[String], origin: Origin): R[StepResult] = Vector("shell")
      def ask(question: String, origin: Origin): R[StepResult] = Vector("ask")
      def done(summary: String, origin: Origin): R[StepResult] = Vector("done")
      def think(goal: String, envelope: Envelope, verify: Option[Verify], origin: Origin): R[StepResult] = Vector("think")
      def seq(steps: List[R[StepResult]], origin: Origin): R[StepResult] = steps.flatten.toVector
      def branch(pred: Pred, thenP: R[StepResult], elseP: R[StepResult], origin: Origin): R[StepResult] = thenP ++ elseP
      def repeatUntil(body: R[StepResult], until: Pred, max: Int, origin: Origin): R[StepResult] = body
    val refused = AgentAlg.restrict[R](names, noShell)((op, _, width) => Vector(s"rejected $op ($width)"))
    assertEquals(AgentAlg.fromTerm(Term.Hole("x", fix, h, Some(green)))(refused), Vector("rejected Shell (2)"))
    assertEquals(AgentAlg.fromTerm(Term.Hole("x", fix, h))(refused), Vector("think"))
    val noHoles = root.copy(allowedOps = root.allowedOps - OpKind.Hole)
    val refusedHole = AgentAlg.restrict[R](names, noHoles)((op, _, width) => Vector(s"rejected $op ($width)"))
    assertEquals(AgentAlg.fromTerm(Term.Hole("x", fix, h, Some(green)))(refusedHole), Vector("rejected Hole (2)"))

  test("restrict: eine Ablehnung belegt so viele Nummern wie der Knoten, die Nummern danach bleiben wie im Explain"):
    type N[A] = Int => Int
    val count: AgentAlg[N] = new AgentAlg[N]:
      private val one: N[StepResult] = _ + 1
      def readFile(path: String, origin: Origin): N[StepResult] = one
      def listDir(path: String, origin: Origin): N[StepResult] = one
      def writeFile(path: String, content: String, origin: Origin): N[StepResult] = one
      def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): N[StepResult] = one
      def moveFile(from: String, to: String, origin: Origin): N[StepResult] = one
      def shell(cmd: String, args: List[String], origin: Origin): N[StepResult] = one
      def ask(question: String, origin: Origin): N[StepResult] = one
      def done(summary: String, origin: Origin): N[StepResult] = one
      def think(goal: String, envelope: Envelope, verify: Option[Verify], origin: Origin): N[StepResult] = _ + Term.width(verify)
      def seq(steps: List[N[StepResult]], origin: Origin): N[StepResult] = n => steps.foldLeft(n)((k, s) => s(k))
      def branch(pred: Pred, thenP: N[StepResult], elseP: N[StepResult], origin: Origin): N[StepResult] = n => elseP(thenP(n))
      def repeatUntil(body: N[StepResult], until: Pred, max: Int, origin: Origin): N[StepResult] = body
    val t = Term.Seq(List(Term.Hole("x", fix, h, Some(green)), Term.ReadFile("src/a.txt", h)), h)
    val restricted = AgentAlg.restrict[N](count, noShell)((_, _, width) => _ + width)
    assertEquals(AgentAlg.fromTerm(t)(restricted)(1), AgentAlg.fromTerm(t)(count)(1))
    assertEquals(AgentAlg.fromTerm(t)(count)(1), 4)

  // ---------- Explain und Nummerierung

  test("Explain: die Abnahme ist ein eigener Schritt nach dem Loch; ein weiterer Durchlauf sieht sie"):
    val t = Term.Seq(List(Term.Hole("behebe", fix, h, Some(green)), Term.ReadFile("src/a.txt", h)), h)
    val text = Explain.render(DryRun.plan(t, root, check).fold(v => fail(v.toList.map(_.render).mkString), identity))
    assert(text.contains("2  Abnahme: führt aus: ./run-tests.sh (führt Workspace-Code aus)"), text)
    assert(text.contains("bestanden, wenn Exit-Code 0; sonst entscheidet der Agent erneut, höchstens 2 Durchläufe"), text)
    assert(text.contains("kann Ergebnisse von Schritt 1, 2 sehen"), text)
    assert(text.linesIterator.exists(_.startsWith("3  liest src/a.txt")), text)
    // Tighten nummeriert wie Explain und Engine: die Abnahme belegt eine Nummer
    assertEquals(Tighten.holes(Term.Seq(List(Term.Hole("behebe", fix, h, Some(green)), Term.Hole("noch", fix, h)), h)).map(_._1), Vector(1, 3))
