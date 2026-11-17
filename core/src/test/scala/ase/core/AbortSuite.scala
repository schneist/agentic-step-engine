package ase.core

import ase.core.ExecSupport.*
import io.circe.Json

/** Abbruch im Term (D#111): Abort beendet den Lauf als aborted, kommt nur aus Flows und nie vom Modell.
  * Jeder Test hat eine Gegenprobe, die zeigt, dass er das Fehlverhalten erkennt (L#10).
  */
class AbortSuite extends munit.FunSuite:
  private val h = Origin.Human
  private val fill = Origin.Model("fill")
  private val check = CheckConfig.default
  private val root = Envelope(OpKind.values.toSet - OpKind.Shell, ".", 30, 1, Set.empty)
  private val src = Envelope(Set(OpKind.ReadFile, OpKind.EditFile), "src", 4, 0, Set.empty)
  private val files = Map("src/A.scala" -> "val a = 1\n")

  private def run(term: Term, model: FillRequest => ModelReply = _ => ModelReply.Failed("kein Modell", 0)) =
    val approved = approve(term, root, check)
    Execute.run[W](approved, ports(fakeTools(check.argPolicies), model, _ => None), ExecConfig.default).run(files).value

  private def script(answers: (Int, String)*): FillRequest => ModelReply = r =>
    answers.collectFirst { case (k, text) if k == r.attempt => ModelReply.Answer(text, 100) }
      .getOrElse(ModelReply.Failed("keine Antwort im Skript", 0))

  test("Abort beendet den Lauf als aborted mit Grund; Schritte danach laufen nicht"):
    val t = Term.Seq(List(Term.WriteFile("a.txt", "a\n", h), Term.Abort("Deckel nicht gesetzt", h), Term.WriteFile("b.txt", "b\n", h)), h)
    val (fs, r) = run(t)
    assertEquals(r.outcome, Outcome.Aborted(AbortReason.Requested(StepId(Vector(2)), "Deckel nicht gesetzt")))
    assertEquals(r.outcome.label, "aborted")
    assert(fs.contains("a.txt") && !fs.contains("b.txt"), fs.keySet)
    assertEquals(r.stats.steps, 2L)
    // Gegenprobe: mit Done statt Abort endet derselbe Lauf mit done; der Test unterscheidet die beiden also
    val (_, r2) = run(Term.Seq(List(Term.WriteFile("a.txt", "a\n", h), Term.Done("fertig", h), Term.WriteFile("b.txt", "b\n", h)), h))
    assertEquals(r2.outcome, Outcome.Done)

  test("Abort im Zweig eines If: nur der gewählte Zweig bricht ab"):
    val t = Term.Seq(List(Term.If(Pred.FileExists("src/A.scala"), Term.Done("vorhanden", h), Term.Abort("A.scala fehlt", h), h)), h)
    assertEquals(run(t)._2.outcome, Outcome.Done)
    val t2 = Term.Seq(List(Term.If(Pred.FileExists("src/fehlt.scala"), Term.Done("vorhanden", h), Term.Abort("fehlt", h), h)), h)
    assert(run(t2)._2.outcome.label == "aborted")

  test("Ein Fill mit Abort scheitert am Decoder; der nächste Versuch läuft"):
    val t = Term.Seq(List(Term.Hole("ändere", src, h)), h)
    val mitAbort = Json.obj("term" -> Json.obj("type" -> Json.fromString("Abort"), "reason" -> Json.fromString("keine Lust"))).noSpaces
    val (_, r) = run(t, script(1 -> mitAbort, 2 -> submission(Term.Done("nichts zu tun", fill))))
    assertEquals(r.events.collect { case e: Event.FillRejected => e.gate }, Vector(Gate.Decode))
    assertEquals(r.outcome, Outcome.Done)
    // Gegenprobe: derselbe Knoten aus einer Datei (OriginMode.Required) wird gelesen
    val ausDatei = Json.obj("type" -> Json.fromString("Abort"), "reason" -> Json.fromString("x"), "origin" -> Json.fromString("Human"))
    assertEquals(Codec.decodeTerm(ausDatei, OriginMode.Required, DecodeLimits.all()), Right(Term.Abort("x", h)))

  test("DryRun weist Abort mit Herkunft Modell ab, auch wenn ein Decoder ihn durchließe"):
    val modell = DryRun.plan(Term.Seq(List(Term.Abort("x", fill)), h), root, check).left.toOption.map(_.toList).getOrElse(Nil)
    assert(modell.exists { case Violation.AbortFromModel(_) => true; case _ => false }, modell)
    // Gegenprobe: derselbe Abort aus einem Flow besteht
    assert(DryRun.plan(Term.Seq(List(Term.Abort("x", h)), h), root, check).isRight)

  test("Flow: Abort mit Parameter im Grund wird gebunden und geprüft"):
    val text =
      """{"description":"d","params":{"m":"^[a-z]+$"},
        |"envelope":{"allowedOps":["ReadFile"],"pathPrefix":".","maxSteps":3,"maxDepth":0,"shellAllowlist":[]},
        |"term":{"type":"If","origin":"Human","pred":{"type":"FileExists","path":"a"},
        |  "thenT":{"type":"Done","summary":"ok","origin":"Human"},
        |  "elseT":{"type":"Abort","reason":"Maschine ${m} fehlt","origin":"Human"}}}""".stripMargin
    val f = Flow.parse(text).fold(e => fail(e.toString), identity)
    val bound = Flow.bind(f, Map("m" -> "start"), f.envelope).fold(e => fail(e.toString), identity)
    assertEquals(bound.term, Term.If(Pred.FileExists("a"), Term.Done("ok", h), Term.Abort("Maschine start fehlt", h), h))
    assert(DryRun.plan(bound.term, bound.envelope, check).isRight)

  test("Record-Log: Ende mit Abort hin und zurück"):
    val e = Event.End(Outcome.Aborted(AbortReason.Requested(StepId(Vector(3)), "Deckel fehlt")), 3L)
    assertEquals(RecordLog.parseLine(RecordLog.line(e), 1), Right(e))

  test("Explain und Lauf-Log nennen den Abbruch"):
    val c = DryRun.plan(Term.Seq(List(Term.Abort("Deckel fehlt", h)), h), root, check).fold(v => fail(v.toString), identity)
    assert(Explain.render(c).contains("bricht ab: Deckel fehlt"), Explain.render(c))
    assertEquals(RunLog.reason(AbortReason.Requested(StepId(Vector(1)), "Deckel fehlt")), "Schritt 1 bricht ab: Deckel fehlt")

  test("Was das Modell sieht, kennt Abort nicht (Schema)"):
    val schema = Schema.termSchema(DecodeLimits.DefaultNesting).noSpaces
    assert(!schema.contains("Abort"), "Abort im Schema für das Modell")
    assert(schema.contains("\"Done\""), "Gegenprobe: das Schema nennt die Knoten beim Namen")
