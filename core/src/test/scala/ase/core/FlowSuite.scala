package ase.core

/** Die Flows aus dem Repo, gegen die Hüllen aus evals/ und flows/. */
class FlowSuite extends munit.FunSuite:
  private def flow(name: String): Flow = Flow.parse(TestSupport.repoFile(s"flows/$name")).fold(e => fail(e.toString), identity)

  private val eval6Root = Codec
    .decodeEnvelope(io.circe.parser.parse(TestSupport.repoFile("evals/6-red-test/envelope.json")).toOption.get, Location.root)
    .toOption
    .get

  test("red-test: geprüft gegen die Hülle von Eval 6, schlechtester Fall 19, Tiefe 1"):
    val f = Flow.bind(flow("red-test.json"), Map.empty, eval6Root).fold(e => fail(e.toString), identity)
    val checked = DryRun.plan(f.term, f.envelope, TestSupport.config).fold(v => fail(v.toList.map(_.render).mkString("\n")), identity)
    assertEquals(checked.worstCaseSteps, 19L)
    assertEquals(checked.depth, 1)

  test("Eval 6, Plan 1: Loch 1 mit maxDepth der Wurzel scheitert mit genau einem Verstoß"):
    val f = flow("red-test.json")
    val plan1 = f.term match
      case Term.Seq(steps, o) =>
        Term.Seq(
          steps.map {
            case Term.Hole(g, e, ho) if g.startsWith("Finde") => Term.Hole(g, e.copy(maxDepth = 1), ho)
            case other                                         => other
          },
          o
        )
      case other => fail(s"unerwartet: $other")
    val v = DryRun.plan(plan1, eval6Root, TestSupport.config).left.toOption.map(_.toList).getOrElse(Nil)
    assertEquals(v, List(Violation.DepthNotSmaller(Location.root.at("steps", 2) / "envelope", 1, 1, true)))

  test("Fingerprint des Modell-Plans 2 gleicht dem Referenzflow"):
    val f = flow("red-test.json")
    assertEquals(Fingerprint.of(TestSupport.stamp(f.term, Origin.Model("plan-2"))), Fingerprint.of(f.term))

  test("release: Parameter gebunden, geprüft, schlechtester Fall 23, Tiefe 2"):
    val f = flow("release.json")
    val bound = Flow.bind(f, Map("version" -> "2.4.0", "previous" -> "2.3.0"), f.envelope).fold(e => fail(e.toString), identity)
    val checked = DryRun.plan(bound.term, bound.envelope, TestSupport.config).fold(v => fail(v.toList.map(_.render).mkString("\n")), identity)
    assertEquals(checked.worstCaseSteps, 23L)
    assertEquals(checked.depth, 2)
    assert(Codec.term(bound.term).noSpaces.contains("v2.3.0..HEAD"))
    assert(!Codec.term(bound.term).noSpaces.contains("${"))

  test("release: Wert, der nicht zum Muster passt, wird abgelehnt"):
    val f = flow("release.json")
    assertEquals(
      Flow.bind(f, Map("version" -> "2.4.0\", \"type\": \"Shell", "previous" -> "2.3.0"), f.envelope),
      Left(FlowError.InvalidValue("version", "2.4.0\", \"type\": \"Shell", "^[0-9]+[.][0-9]+[.][0-9]+$"))
    )

  private val minimal = Flow(
    "test",
    Map("msg" -> ".*"),
    Envelope(Set(OpKind.ReadFile), ".", 5, 0, Set.empty),
    Term.Done("${msg}", Origin.Human)
  )

  test("Injection: ein JSON-Fragment im Wert landet als Text, nie als Knoten"):
    val evil = """", "type": "Shell", "cmd": "rm"""
    val bound = Flow.bind(minimal, Map("msg" -> evil), minimal.envelope).fold(e => fail(e.toString), identity)
    assertEquals(bound.term, Term.Done(evil, Origin.Human))

  test("ungebundener Platzhalter, undeklarierter Wert, fehlender Wert"):
    assertEquals(
      Flow.bind(minimal.copy(term = Term.Done("${andere}", Origin.Human)), Map("msg" -> "x"), minimal.envelope),
      Left(FlowError.UndeclaredPlaceholder("andere"))
    )
    assertEquals(Flow.bind(minimal, Map("msg" -> "x", "y" -> "z"), minimal.envelope), Left(FlowError.UndeclaredValue("y")))
    assertEquals(Flow.bind(minimal, Map.empty, minimal.envelope), Left(FlowError.MissingValue("msg")))

  test("Flow-Hülle weiter als die Runner-Hülle wird abgelehnt"):
    val runner = minimal.envelope.copy(maxSteps = 3)
    assert(Flow.bind(minimal, Map("msg" -> "x"), runner).left.exists(_.isInstanceOf[FlowError.EnvelopeTooWide]))

  test("Parameter werden auch im scopePrefix ersetzt (D#69)"):
    val report = Envelope(Set(OpKind.WriteFile), "R.md", 1, 0, Set.empty, Some("${dir}"))
    val f = Flow("t", Map("dir" -> "^[a-z]+$"), Envelope(Set(OpKind.WriteFile, OpKind.Hole), ".", 5, 1, Set.empty), Term.Hole("x", report, Origin.Human))
    val bound = Flow.bind(f, Map("dir" -> "src"), f.envelope).fold(e => fail(e.toString), identity)
    assertEquals(bound.term, Term.Hole("x", report.copy(scopePrefix = Some("src")), Origin.Human))
