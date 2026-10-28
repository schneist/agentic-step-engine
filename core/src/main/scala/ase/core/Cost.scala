  package ase.core

/** Schätzung ohne Ausführung; vollständig, weil der Term statisch ist. */
final case class Cost(worstCaseSteps: Long, worstCaseModelCalls: Long, tokenEstimate: Long)

/** attemptsPerFill: Versuche je Runde (SPEC: 3). roundsPerHole: Runden je Loch (D#55). tokensPerFill: grobe Größe eines
  * Fill-Aufrufs.
  */
final case class CostConfig(attemptsPerFill: Int, tokensPerFill: Int, roundsPerHole: Int = 3)

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
      val calls = config.attemptsPerFill.toLong * config.roundsPerHole
      Cost(Steps.add(1L, math.max(envelope.maxSteps, 0).toLong), calls, tokens(goal) + calls * config.tokensPerFill)
    def seq(steps: List[Cost], origin: Origin): Cost =
      steps.foldLeft(Cost(0, 0, 0))((a, b) =>
        Cost(
          Steps.add(a.worstCaseSteps, b.worstCaseSteps),
          Steps.add(a.worstCaseModelCalls, b.worstCaseModelCalls),
          Steps.add(a.tokenEstimate, b.tokenEstimate)
        )
      )
    def branch(pred: Pred, thenP: Cost, elseP: Cost, origin: Origin): Cost =
      Cost(
        math.max(thenP.worstCaseSteps, elseP.worstCaseSteps),
        math.max(thenP.worstCaseModelCalls, elseP.worstCaseModelCalls),
        math.max(thenP.tokenEstimate, elseP.tokenEstimate)
      )
    def repeatUntil(body: Cost, until: Pred, max: Int, origin: Origin): Cost =
      val n = math.max(max, 0).toLong
      Cost(Steps.mul(n, body.worstCaseSteps), Steps.mul(n, body.worstCaseModelCalls), Steps.mul(n, body.tokenEstimate))
