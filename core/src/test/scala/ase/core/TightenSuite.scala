package ase.core

class TightenSuite extends munit.FunSuite:
  private val h = Origin.Human
  private val grep = AllowedCommand("grep", Nil)
  private val wide = Envelope(Set(OpKind.ReadFile, OpKind.EditFile, OpKind.Shell, OpKind.Hole), ".", 8, 1, Set(grep))

  private def call(ops: Set[OpKind], paths: Set[String], steps: Int, needed: Int, env: Envelope = wide, completed: Boolean = true) =
    Usage.Call(StepId(Vector(1)), "ziel", env, ops, paths, Set.empty, steps, needed, Vector.empty, Vector.empty, Map.empty, 0, 0, 1, 10L, completed)

  test("holes: Nummern wie im Explain, über If und RepeatUntil hinweg (D#75)"):
    val hole = Term.Hole("x", Envelope(Set(OpKind.ReadFile), ".", 2, 0, Set.empty), h)
    val t = Term.Seq(List(
      Term.ListDir(".", h),
      Term.If(Pred.FileExists("a"), Term.Seq(List(Term.ReadFile("a", h), Term.ReadFile("b", h)), h), Term.Done("nein", h), h),
      hole,
      Term.RepeatUntil(Term.Seq(List(hole, Term.ReadFile("c", h)), h), Pred.FileExists("d"), 2, h)
    ), h)
    assertEquals(Tighten.holes(t).map(_._1), Vector(5, 6))
    val root = Envelope(Set(OpKind.ReadFile, OpKind.ListDir, OpKind.Hole), ".", 20, 1, Set.empty)
    val out = Explain.render(DryRun.plan(t, root, CheckConfig.default).fold(v => fail(v.head.render), identity))
    assert(out.contains("5  Hier entscheidet der Agent") && out.contains("6    Hier entscheidet der Agent"), out)

  test("propose: nur genutzte Operationen und Programme, Budget mit schlechtestem Fall, Pfad enger, Sicht bleibt (D#75)"):
    val calls = Vector(
      call(Set(OpKind.ReadFile), Set("src/A.scala"), 2, 4),
      call(Set(OpKind.ReadFile), Set("src/B.scala"), 3, 3),
      call(Set(OpKind.ReadFile), Set("src/a/C.scala"), 1, 2)
    )
    val p = Tighten.propose(1, wide, calls, 3)
    val neu = p.change.applyTo(wide)
    assertEquals(neu, Envelope(Set(OpKind.ReadFile), "src", 4, 0, Set.empty, Some(".")))
    assertEquals(Attenuation.check(neu, wide, Location.root, Attenuation.Mode.Flow), Nil)
    assertEquals(p.reasons.size, 5)

  test("propose: unter minCalls Aufrufen und bei Parametern im Pfad bleibt es, wie es ist"):
    val few = Tighten.propose(1, wide, Vector(call(Set(OpKind.ReadFile), Set("src/A.scala"), 1, 1)), 3)
    assert(few.change.isEmpty)
    assert(few.skipped.head.contains("nur 1 abgeschlossene Aufrufe"), few.skipped)
    val param = wide.copy(pathPrefix = "out/${v}")
    val calls = Vector.fill(3)(call(Set(OpKind.ReadFile), Set("out/7/a.txt"), 1, 1, param))
    val p = Tighten.propose(1, param, calls, 3)
    assertEquals(p.change.path, None)
    assert(p.skipped.exists(_.contains("enthält einen Parameter")), p.skipped)

  test("propose: nur Done genutzt, Operationen bleiben; apply ersetzt nur die genannten Hüllen"):
    val p = Tighten.propose(1, wide, Vector.fill(3)(call(Set.empty, Set.empty, 1, 1)), 3)
    assertEquals(p.change.ops, None)
    assert(p.skipped.exists(_.contains("nur Done")), p.skipped)
    val a = Term.Hole("a", wide, h)
    val t = Term.Seq(List(a, Term.ReadFile("x", h), a), h)
    val changed = Tighten.apply(t, Map(3 -> p.change))
    assertEquals(Tighten.holes(changed).map((n, hole) => n -> (hole.envelope == wide)), Vector(1 -> true, 3 -> false))

  test("propose: abgebrochene Aufrufe zählen nicht zur Mindestzahl und halten das Budget; Tor-2-Ablehnung ebenso (Review)"):
    val ok = Vector.fill(3)(call(Set(OpKind.ReadFile), Set("src/A.scala"), 2, 2))
    val aborted = call(Set(OpKind.ReadFile, OpKind.EditFile), Set("src/B.scala"), 0, 0, completed = false)
    val p = Tighten.propose(1, wide, ok :+ aborted, 3)
    assertEquals(p.change.maxSteps, None)
    assertEquals(p.change.ops, Some(Set(OpKind.ReadFile, OpKind.EditFile)))
    assert(p.skipped.exists(_.contains("max Schritte bleibt: 1 Aufruf(e) nicht abgeschlossen")), p.skipped)
    val few = Tighten.propose(1, wide, Vector.fill(2)(call(Set(OpKind.ReadFile), Set("src/A.scala"), 2, 2)) :+ aborted, 3)
    assert(few.change.isEmpty, few)
    val gate2 = ok.updated(0, ok(0).copy(rejected = Map(Gate.DryRun -> 1)))
    assertEquals(Tighten.propose(1, wide, gate2, 3).change.maxSteps, None)

  test("propose: Parameter in Sicht oder Allowlist und Läufe mit denselben Flow-Parametern lassen Pfad und Programme (Review)"):
    val calls = Vector.fill(3)(call(Set(OpKind.ReadFile, OpKind.Shell), Set("src/a/X.scala"), 1, 1))
    val seesParam = wide.copy(scopePrefix = Some("${dir}"))
    assertEquals(Tighten.propose(1, seesParam, calls, 3).change.path, None)
    val gitParam = wide.copy(shellAllowlist = Set(grep, AllowedCommand("git", List("${branch}"))))
    val g = Tighten.propose(1, gitParam, calls, 3)
    assertEquals(g.change.shell, None)
    assert(g.skipped.exists(_.contains("Allowlist enthält einen Parameter")), g.skipped)
    val one = Tighten.propose(1, wide, calls, 3, oneBinding = true)
    assertEquals(one.change.path, None)
    assert(one.skipped.exists(_.contains("denselben Flow-Parametern")), one.skipped)
    assertEquals(Tighten.propose(1, wide, calls, 3).change.path.map(_._1), Some("src/a/X.scala"))
