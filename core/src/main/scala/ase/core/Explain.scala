package ase.core

/** explainDenials (D#24): Befehle, die Explain als „nicht erlaubt, u. a.“ nennt.
  * workspaceCode (D#12): Programme, die Code aus dem Arbeitsverzeichnis ausführen, und dessen Pfade.
  * Programme, die mit "./" beginnen, führen sich selbst aus.
  */
final case class ExplainConfig(explainDenials: List[AllowedCommand], workspaceCode: Map[String, List[String]])

object ExplainConfig:
  val default: ExplainConfig = ExplainConfig(
    List(
      AllowedCommand("git", List("push")),
      AllowedCommand("git", List("commit")),
      AllowedCommand("git", List("tag")),
      AllowedCommand("rm", Nil),
      AllowedCommand("curl", Nil)
    ),
    Map("sbt" -> List("build.sbt", "project", "src"))
  )

/** Ein geprüfter Term als Text zur Freigabe. Löcher als „Hier entscheidet der Agent“ mit der Hülle im Klartext
  * und den früheren Ergebnissen, die das Loch sehen wird (D#30).
  */
object Explain:
  def render(
      checked: CheckedTerm,
      params: Map[String, String] = Map.empty,
      config: ExplainConfig = ExplainConfig.default,
      check: CheckConfig = CheckConfig.default
  ): String =
    val root = checked.rootEnvelope
    val code = codePaths(checked.term, config)
    val (body, _, _) = AgentAlg.fromTerm(checked.term)(interpreter(code, config, check))(State(0, 1, Vector.empty))
    val allowlist = root.shellAllowlist.toList.map(_.show).sorted
    val denied = config.explainDenials.filterNot(Attenuation.allowedBy(root.shellAllowlist, _)).map(_.show)
    val warned = config.explainDenials.filter(Attenuation.allowedBy(root.shellAllowlist, _)).map(_.show)
    val header = Vector(
      s"dryrun    ok  (worst case ${checked.worstCaseSteps} steps, depth ${checked.depth})",
      s"explain   ${checked.termHash.short}"
    ) ++ Option.when(params.nonEmpty)(s"params    ${params.toList.sorted.map((k, v) => s"$k=$v").mkString(" ")}")
    val footer = Vector(s"shell     ${if allowlist.isEmpty then "keine" else allowlist.mkString(", ")}") ++
      Option.when(denied.nonEmpty)(s"nicht erlaubt, u. a.: ${denied.mkString(", ")}") ++
      Option.when(warned.nonEmpty)(s"Warnung: erlaubt, obwohl in explainDenials: ${warned.mkString(", ")}")
    (header ++ Vector("") ++ body ++ Vector("") ++ footer).mkString("\n")

  /** Einrückung, nächste Schrittnummer, Ergebnisse, die vor diesem Knoten entstanden sein können. */
  final case class State(indent: Int, next: Int, prior: Vector[Scope.Seen])

  /** Zeilen, nächste Nummer, frühere Ergebnisse nach diesem Knoten. */
  type Out[A] = State => (Vector[String], Int, Vector[Scope.Seen])

  private def quote(s: String, max: Int = 50): String =
    val flat = s.replace("\n", "⏎")
    "„" + (if flat.length > max then flat.take(max) + "…" else flat) + "“"

  private def runsWorkspaceCode(cmd: String, config: ExplainConfig): Option[List[String]] =
    if cmd.startsWith("./") then Some(List(cmd.drop(2))) else config.workspaceCode.get(cmd)

  private def codePaths(t: Term, config: ExplainConfig): List[Vector[String]] = t match
    case Term.Shell(cmd, _, _)        => runsWorkspaceCode(cmd, config).toList.flatten.flatMap(RelPath.parse(_).toOption)
    case Term.Seq(steps, _)           => steps.flatMap(codePaths(_, config))
    case Term.If(_, a, b, _)          => codePaths(a, config) ++ codePaths(b, config)
    case Term.RepeatUntil(b, _, _, _) => codePaths(b, config)
    case _                            => Nil

  private val writes = Set(OpKind.WriteFile, OpKind.EditFile, OpKind.MoveFile)

  private def envelopeText(e: Envelope): String =
    val ops = e.allowedOps.toList.sortBy(_.ordinal).mkString(", ")
    val shell = Option.when(e.shellAllowlist.nonEmpty)(s" · shell: ${e.shellAllowlist.toList.map(_.show).sorted.mkString(", ")}")
    val steps = if e.maxSteps == 1 then "1 Schritt" else s"${e.maxSteps} Schritte"
    s"darf $ops · unter ${e.pathPrefix} · max $steps · Tiefe ${e.maxDepth}${shell.getOrElse("")}"

  private def interpreter(code: List[Vector[String]], config: ExplainConfig, check: CheckConfig): AgentAlg[Out] = new AgentAlg[Out]:
    private def pad(indent: Int): String = "  " * indent
    private def note(text: String, indent: Int): String = "   " + pad(indent) + text

    private def step(text: String, paths: List[String]): Out[StepResult] = s =>
      (Vector(f"${s.next}%-3d" + pad(s.indent) + text), s.next + 1, s.prior :+ Scope.Seen(s.next, paths))

    def readFile(path: String, origin: Origin): Out[StepResult] = step(s"liest $path", List(path))
    def listDir(path: String, origin: Origin): Out[StepResult] = step(s"listet $path", List(path))
    def writeFile(path: String, content: String, origin: Origin): Out[StepResult] =
      step(s"legt $path neu an (${content.length} Zeichen)", List(path))
    def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): Out[StepResult] =
      step(s"ersetzt in $path  ${quote(expectedOld)}  durch  ${quote(replacement)}", List(path))
    def moveFile(from: String, to: String, origin: Origin): Out[StepResult] = step(s"verschiebt $from nach $to", List(from, to))
    def shell(cmd: String, args: List[String], origin: Origin): Out[StepResult] =
      val marker = if runsWorkspaceCode(cmd, config).isDefined then " (führt Workspace-Code aus)" else ""
      step(s"führt aus: ${(cmd :: args).mkString(" ")}$marker", Scope.shellPaths(cmd, args, check.argPolicies))
    def ask(question: String, origin: Origin): Out[StepResult] = step(s"fragt: ${quote(question, 80)}", Nil)
    def done(summary: String, origin: Origin): Out[StepResult] = step(s"fertig: $summary", Nil)

    def think(goal: String, envelope: Envelope, origin: Origin): Out[StepResult] = s =>
      val prefix = RelPath.parse(envelope.pathPrefix).toOption
      val changesCode = envelope.allowedOps.exists(writes) && prefix.exists(p => code.exists(RelPath.overlaps(p, _)))
      val seen = Scope.visible(s.prior, envelope).map(_.step)
      val sees =
        if seen.isEmpty then "sieht keine früheren Ergebnisse"
        else s"sieht Ergebnisse von Schritt ${seen.mkString(", ")}"
      val lines = Vector(
        f"${s.next}%-3d" + pad(s.indent) + s"Hier entscheidet der Agent: ${quote(goal, 70)}",
        note(envelopeText(envelope), s.indent),
        note(sees, s.indent)
      ) ++ Option.when(changesCode)(note("Hinweis: darf Code ändern, der im Lauf ausgeführt wird", s.indent))
      // Das Ergebnis eines Lochs ist seine Done-Zusammenfassung, pfadlos (D#9)
      (lines, s.next + 1, s.prior :+ Scope.Seen(s.next, Nil))

    def seq(steps: List[Out[StepResult]], origin: Origin): Out[StepResult] = s =>
      steps.foldLeft((Vector.empty[String], s.next, s.prior)) { case ((acc, next, prior), step) =>
        val (lines, n, p) = step(s.copy(next = next, prior = prior))
        (acc ++ lines, n, p)
      }

    def branch(pred: Pred, thenP: Out[StepResult], elseP: Out[StepResult], origin: Origin): Out[StepResult] = s =>
      val inner = s.copy(indent = s.indent + 1)
      val (a, n1, pa) = thenP(inner)
      // der sonst-Zweig sieht nicht, was im wenn-Zweig geschehen ist
      val (b, n2, pb) = elseP(inner.copy(next = n1))
      val elseLines = if b.isEmpty then Vector.empty else note("sonst:", s.indent) +: b
      ((note(s"wenn ${Pred.show(pred)}:", s.indent) +: a) ++ elseLines, n2, (pa ++ pb).distinct)

    def repeatUntil(body: Out[StepResult], until: Pred, max: Int, origin: Origin): Out[StepResult] = s =>
      val inner = s.copy(indent = s.indent + 1)
      // ab dem zweiten Durchlauf liegen auch die Ergebnisse des Rumpfs selbst vor
      val (_, _, firstPass) = body(inner)
      val (b, next, prior) = body(inner.copy(prior = firstPass))
      (note(s"wiederhole höchstens $max-mal, bis ${Pred.show(until)}:", s.indent) +: b, next, prior.distinct)
