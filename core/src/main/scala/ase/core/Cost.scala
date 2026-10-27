  package ase.core

/** Schätzung ohne Ausführung; vollständig, weil der Term statisch ist. */
final case class Cost(worstCaseSteps: Long, worstCaseModelCalls: Long, tokenEstimate: Long)

/** attemptsPerFill: Versuche pro Loch (SPEC: 3). tokensPerFill: grobe Größe eines Fill-Aufrufs. */
final case class CostConfig(attemptsPerFill: Int, tokensPerFill: Int)

object CostConfig:
  val default: CostConfig = CostConfig(attemptsPerFill = 3, tokensPerFill = 4000)

object Cost:
  type Of[A] = Cost

  def of(t: Term, config: CostConfig = CostConfig.default): Cost = AgentAlg.fromTerm(t)(interpreter(config))

  private def tokens(texts: String*): Long = texts.map(_.length.toLong).sum / 4 + 10

  def interpreter(config: CostConfig): AgentAlg[Of] = new AgentAlg[Of]:
    private def leaf(texts: String*): Cost = Cost(1, 0, tokens(texts*))
    def readFile(path: String, origin: Origin): Cost = leaf(path)
    def listDir(path: String, origin: Origin): Cost = leaf(path)
    def writeFile(path: String, content: String, origin: Origin): Cost = leaf(path, content)
    def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): Cost = leaf(path, expectedOld, replacement)
    def moveFile(from: String, to: String, origin: Origin): Cost = leaf(from, to)
    def shell(cmd: String, args: List[String], origin: Origin): Cost = leaf((cmd :: args)*)
    def ask(question: String, origin: Origin): Cost = leaf(question)
    def done(summary: String, origin: Origin): Cost = leaf(summary)
    def think(goal: String, envelope: Envelope, origin: Origin): Cost =
      Cost(
        1L + math.max(envelope.maxSteps, 0),
        config.attemptsPerFill.toLong,
        tokens(goal) + config.attemptsPerFill.toLong * config.tokensPerFill
      )
    def seq(steps: List[Cost], origin: Origin): Cost =
      steps.foldLeft(Cost(0, 0, 0))((a, b) =>
        Cost(a.worstCaseSteps + b.worstCaseSteps, a.worstCaseModelCalls + b.worstCaseModelCalls, a.tokenEstimate + b.tokenEstimate)
      )
    def branch(pred: Pred, thenP: Cost, elseP: Cost, origin: Origin): Cost =
      Cost(
        math.max(thenP.worstCaseSteps, elseP.worstCaseSteps),
        math.max(thenP.worstCaseModelCalls, elseP.worstCaseModelCalls),
        math.max(thenP.tokenEstimate, elseP.tokenEstimate)
      )
    def repeatUntil(body: Cost, until: Pred, max: Int, origin: Origin): Cost =
      val n = math.max(max, 0).toLong
      Cost(n * body.worstCaseSteps, n * body.worstCaseModelCalls, n * body.tokenEstimate)
