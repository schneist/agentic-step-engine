package ase.core

/** Interpreter gegen F[_], nicht gegen IO: Recorder ist ein konstanter Funktor, der die Aufrufe mitschreibt. */
class AlgebraSuite extends munit.FunSuite:
  type Rec[A] = Vector[String]

  private val recorder: AgentAlg[Rec] = new AgentAlg[Rec]:
    def readFile(path: String, origin: Origin): Rec[StepResult] = Vector(s"readFile $path")
    def listDir(path: String, origin: Origin): Rec[StepResult] = Vector(s"listDir $path")
    def writeFile(path: String, content: String, origin: Origin): Rec[StepResult] = Vector(s"writeFile $path")
    def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): Rec[StepResult] = Vector(s"editFile $path")
    def moveFile(from: String, to: String, origin: Origin): Rec[StepResult] = Vector(s"moveFile $from $to")
    def shell(cmd: String, args: List[String], origin: Origin): Rec[StepResult] = Vector(s"shell $cmd")
    def ask(question: String, origin: Origin): Rec[StepResult] = Vector("ask")
    def done(summary: String, origin: Origin): Rec[StepResult] = Vector("done")
    def think(goal: String, envelope: Envelope, origin: Origin): Rec[StepResult] = Vector("think")
    def seq(steps: List[Rec[StepResult]], origin: Origin): Rec[StepResult] = steps.flatten.toVector
    def branch(pred: Pred, thenP: Rec[StepResult], elseP: Rec[StepResult], origin: Origin): Rec[StepResult] = thenP ++ elseP
    def repeatUntil(body: Rec[StepResult], until: Pred, max: Int, origin: Origin): Rec[StepResult] = body

  private val o = Origin.Model("fill-1")
  private val readOnly = Envelope(Set(OpKind.ReadFile), "src", 5, 0, Set.empty)

  test("restrict reicht verbotene Operationen nicht weiter"):
    val t = Term.Seq(List(Term.ReadFile("src/A.scala", o), Term.EditFile("src/A.scala", "a", "b", o), Term.Done("x", o)), o)
    val restricted = AgentAlg.restrict(recorder, readOnly)((op, _) => Vector(s"rejected $op"))
    assertEquals(AgentAlg.fromTerm(t)(restricted), Vector("readFile src/A.scala", "rejected EditFile", "done"))

  test("restrict lässt erlaubte Operationen unverändert durch"):
    val t = Term.If(Pred.LastOutputEmpty, Term.ReadFile("src/A", o), Term.Done("x", o), o)
    val restricted = AgentAlg.restrict(recorder, readOnly)((op, _) => Vector(s"rejected $op"))
    assertEquals(AgentAlg.fromTerm(t)(restricted), AgentAlg.fromTerm(t)(recorder))

  test("fromTerm(toTerm(p)) ≡ p, beobachtet über zwei Deutungen"):
    val p: AgentAlg.Program = [F[_]] =>
      (alg: AgentAlg[F]) =>
        alg.seq(
          List(
            alg.shell("./run-tests.sh", Nil, Origin.Human),
            alg.repeatUntil(alg.shell("./run-tests.sh", Nil, Origin.Human), Pred.LastExitCode(0), 2, Origin.Human),
            alg.think("ziel", readOnly, Origin.Human)
          ),
          Origin.Human
        )
    val roundtrip = AgentAlg.program(AgentAlg.toTerm(p))
    assertEquals(roundtrip[Rec](recorder), p[Rec](recorder))
    assertEquals(roundtrip[Cost.Of](Cost.interpreter(CostConfig.default)), p[Cost.Of](Cost.interpreter(CostConfig.default)))

  test("Cost: schlechtester Fall wie in SPEC (Hole = 1 + maxSteps, RepeatUntil = max · body)"):
    val t = Term.Seq(
      List(
        Term.Shell("./run-tests.sh", Nil, Origin.Human),
        Term.Hole("x", readOnly, Origin.Human),
        Term.RepeatUntil(Term.Shell("./run-tests.sh", Nil, Origin.Human), Pred.LastExitCode(0), 2, Origin.Human)
      ),
      Origin.Human
    )
    val c = Cost.of(t)
    assertEquals(c.worstCaseSteps, 1L + 6 + 2)
    assertEquals(c.worstCaseModelCalls, 3L)
