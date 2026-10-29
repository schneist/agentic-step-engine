package ase.core

/** explainDenials (D#24): Befehle, die Explain als „nicht erlaubt, u. a.“ nennt. Welche Programme Workspace-Code
  * ausführen, steht in der Prüfkonfiguration des CheckedTerm (D#39, D#40).
  */
final case class ExplainConfig(explainDenials: List[AllowedCommand])

object ExplainConfig:
  val default: ExplainConfig = ExplainConfig(
    List(
      AllowedCommand("git", List("push")),
      AllowedCommand("git", List("commit")),
      AllowedCommand("git", List("tag")),
      AllowedCommand("rm", Nil),
      AllowedCommand("curl", Nil)
    )
  )

/** Ein geprüfter Term als Text zur Freigabe. Löcher als „Hier entscheidet der Agent“ mit der Hülle im Klartext
  * und den früheren Ergebnissen, die das Loch sehen kann (D#30, D#42). Prüfkonfiguration und Flow-Parameter
  * kommen aus dem CheckedTerm (D#40).
  */
object Explain:
  def render(checked: CheckedTerm, config: ExplainConfig = ExplainConfig.default): String =
    val root = checked.rootEnvelope
    val check = checked.config
    val code = codePaths(checked.term, check)
    val (body, _, _) = AgentAlg.fromTerm(checked.term)(interpreter(code, check))(State(0, 1, Vector.empty))
    val allowlist = root.shellAllowlist.toList.map(_.show).sorted
    val denied = config.explainDenials.filterNot(Attenuation.allowedBy(root.shellAllowlist, _)).map(_.show)
    val warned = config.explainDenials.filter(Attenuation.allowedBy(root.shellAllowlist, _)).map(_.show)
    val params = checked.params
    val header = Vector(
      s"dryrun    ok  (worst case ${checked.worstCaseSteps} steps, depth ${checked.depth})",
      s"explain   ${checked.termHash.short}",
      // der volle Hash, den die Freigabe bestätigt (Approval.approve)
      s"hash      ${checked.termHash.hex}"
    ) ++ Option.when(params.nonEmpty)(s"params    ${Text.visible(params.toList.sorted.map((k, v) => s"$k=$v").mkString(" "))}")
    val footer = Vector(s"shell     ${if allowlist.isEmpty then "keine" else Text.visible(allowlist.mkString(", "))}") ++
      Option.when(denied.nonEmpty)(s"nicht erlaubt, u. a.: ${denied.mkString(", ")}") ++
      Option.when(warned.nonEmpty)(s"Warnung: erlaubt, obwohl in explainDenials: ${warned.mkString(", ")}")
    (header ++ Vector("") ++ body ++ Vector("") ++ footer).mkString("\n")

  /** Einrückung, nächste Schrittnummer, Ergebnisse, die vor diesem Knoten entstanden sein können. */
  final case class State(indent: Int, next: Int, prior: Vector[Scope.Seen])

  /** Zeilen, nächste Nummer, frühere Ergebnisse nach diesem Knoten. */
  type Out[A] = State => (Vector[String], Int, Vector[Scope.Seen])

  /** Statischer Inhalt passt in die Zeile, wenn er kurz ist und keinen Zeilenumbruch hat; sonst als Block (D#41). */
  private val InlineMax = 50

  /** Ziel eines Agentenschritts und Frage an den Menschen: bis hierhin in der Zeile, sonst vollständig als Block (D#71). */
  private val TextInlineMax = 70

  /** Anzeigebreite im Block; länger wird an Leerzeichen umbrochen, Fortsetzungen eingerückt (D#71). */
  private val WrapAt = 100

  /** Eine sichtbare Zeile in Stücke bis WrapAt, an Leerzeichen; ein Wort länger als WrapAt bleibt ganz. */
  private def wrap(line: String): Vector[String] =
    line.split(" ", -1).foldLeft(Vector.empty[String]) { (acc, word) =>
      acc.lastOption match
        case Some(last) if last.length + 1 + word.length <= WrapAt => acc.init :+ s"$last $word"
        case _ if acc.isEmpty                                       => Vector(word)
        case _                                                      => acc :+ word
    }

  private def fitsInLine(s: String): Boolean = !s.contains('\n') && Text.visible(s).length <= InlineMax

  private def textFitsInLine(s: String): Boolean = Text.visible(s).length <= TextInlineMax

  /** Inhalt vollständig, eine Zeile pro Zeile; ein Zeilenende am Schluss ergibt keine leere Zeile. */
  private def contentLines(s: String): Vector[String] =
    val ls = s.split("\n", -1).toVector
    if ls.size > 1 && ls.last.isEmpty then ls.init else ls

  private def lineCount(s: String): String =
    val n = contentLines(s).size
    if n == 1 then "1 Zeile" else s"$n Zeilen"

  private def codePaths(t: Term, check: CheckConfig): List[Vector[String]] = t match
    case Term.Shell(cmd, _, _)        => check.runsWorkspaceCode(cmd).toList.flatten.flatMap(RelPath.parse(_).toOption)
    case Term.Hole(_, e, _)           => e.shellAllowlist.toList.flatMap(c => check.runsWorkspaceCode(c.binary).toList.flatten).flatMap(RelPath.parse(_).toOption)
    case Term.Seq(steps, _)           => steps.flatMap(codePaths(_, check))
    case Term.If(_, a, b, _)          => codePaths(a, check) ++ codePaths(b, check)
    case Term.RepeatUntil(b, _, _, _) => codePaths(b, check)
    case _                            => Nil

  private val writes = Set(OpKind.WriteFile, OpKind.EditFile, OpKind.MoveFile)

  private def envelopeText(e: Envelope): String =
    val ops = e.allowedOps.toList.sortBy(_.ordinal).mkString(", ")
    val shell = Option.when(e.shellAllowlist.nonEmpty)(s" · shell: ${e.shellAllowlist.toList.map(_.show).sorted.mkString(", ")}")
    val steps = if e.maxSteps == 1 then "1 Schritt" else s"${e.maxSteps} Schritte"
    val scope = e.scopePrefix.fold("")(s => s" · sieht Ergebnisse unter $s")
    s"darf $ops · unter ${e.pathPrefix}$scope · max $steps · Tiefe ${e.maxDepth}${shell.getOrElse("")}"

  private def interpreter(code: List[Vector[String]], check: CheckConfig): AgentAlg[Out] = new AgentAlg[Out]:
    private def pad(indent: Int): String = "  " * indent
    private def note(text: String, indent: Int): String = "   " + pad(indent) + Text.visible(text)

    /** Prosa als Block unter einer Zeile, Zeilenumbrüche sichtbar als \n wie in der Zeile (H3); umbrochen nur für die
      * Anzeige, Fortsetzungen eingerückt (D#71).
      */
    private def textBlock(title: String, text: String, indent: Int): Vector[String] =
      note(s"$title (${text.length} Zeichen):", indent) +:
        wrap(Text.visible(text)).zipWithIndex.map((part, i) => "   " + pad(indent) + (if i == 0 then "│ " else "│   ") + part)

    /** Inhaltsblock unter einem Schritt: jede Zeile mit Rahmen, gefährliche Zeichen sichtbar (D#31, D#41). */
    private def block(title: String, content: String, indent: Int): Vector[String] =
      note(s"$title (${lineCount(content)}):", indent + 1) +:
        contentLines(content).map(l => "   " + pad(indent + 1) + "│ " + Text.visible(l))

    private def step(text: String, paths: List[String], details: Int => Vector[String] = _ => Vector.empty): Out[StepResult] = s =>
      (Vector(f"${s.next}%-3d" + pad(s.indent) + Text.visible(text)) ++ details(s.indent), s.next + 1, s.prior :+ Scope.Seen(s.next, paths))

    def readFile(path: String, origin: Origin): Out[StepResult] = step(s"liest $path", List(path))
    def listDir(path: String, origin: Origin): Out[StepResult] = step(s"listet $path samt Unterverzeichnissen", List(path))
    def writeFile(path: String, content: String, origin: Origin): Out[StepResult] =
      if content.isEmpty then step(s"legt $path neu an (leer)", List(path))
      else step(s"legt $path neu an (${content.length} Zeichen)", List(path), block("Inhalt", content, _))
    def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): Out[StepResult] =
      if fitsInLine(expectedOld) && fitsInLine(replacement) then
        step(s"ersetzt in $path  „${Text.visible(expectedOld)}“  durch  „${Text.visible(replacement)}“", List(path))
      else step(s"ersetzt in $path", List(path), i => block("alt", expectedOld, i) ++ block("neu", replacement, i))
    def moveFile(from: String, to: String, origin: Origin): Out[StepResult] = step(s"verschiebt $from nach $to", List(from, to))
    def shell(cmd: String, args: List[String], origin: Origin): Out[StepResult] =
      val marker = if check.runsWorkspaceCode(cmd).isDefined then " (führt Workspace-Code aus)" else ""
      step(s"führt aus: ${(cmd :: args).mkString(" ")}$marker", Scope.shellPaths(cmd, args, check))
    def ask(question: String, origin: Origin): Out[StepResult] =
      if textFitsInLine(question) then step(s"fragt: „${Text.visible(question)}“", Nil)
      else step("fragt:", Nil, i => textBlock("Frage", question, i + 1))
    def done(summary: String, origin: Origin): Out[StepResult] = step(s"fertig: $summary", Nil)

    def think(goal: String, envelope: Envelope, origin: Origin): Out[StepResult] = s =>
      val prefix = RelPath.parse(envelope.pathPrefix).toOption
      // welcher ausgeführte Code unter dem pathPrefix eines ändernden Lochs liegt (D#12, D#62)
      val changesCode =
        if !envelope.allowedOps.exists(writes) then Nil
        else prefix.toList.flatMap(p => code.filter(RelPath.overlaps(p, _))).map(c => RelPath.show(c)).distinct.sorted
      val seen = Scope.visible(s.prior, envelope).map(_.step)
      val runsCode = envelope.shellAllowlist.toList.filter(c => check.runsWorkspaceCode(c.binary).isDefined).map(_.show).sorted
      // Execute filtert nach aufgelösten Pfaden und zeigt höchstens diese Ergebnisse, nie mehr (D#42)
      val sees =
        if seen.isEmpty then "kann keine früheren Ergebnisse sehen"
        else s"kann Ergebnisse von Schritt ${seen.mkString(", ")} sehen"
      val lines = Vector(
        f"${s.next}%-3d" + pad(s.indent) + "Hier entscheidet der Agent:" + (if textFitsInLine(goal) then s" „${Text.visible(goal)}“" else "")
      ) ++ Option.when(!textFitsInLine(goal))(textBlock("Ziel", goal, s.indent)).toVector.flatten ++ Vector(
        note(envelopeText(envelope), s.indent),
        note(sees, s.indent)
      ) ++ Option.when(changesCode.nonEmpty)(note(s"Hinweis: darf Code ändern, der im Lauf ausgeführt wird (${changesCode.mkString(", ")})", s.indent)) ++
        Option.when(runsCode.nonEmpty)(note(s"Hinweis: darf Workspace-Code ausführen (${runsCode.mkString(", ")})", s.indent))
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
