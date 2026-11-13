package ase.core

/** explainDenials (D#24): Befehle, die Explain als „nicht erlaubt, u. a.“ nennt. Welche Programme Workspace-Code
  * ausführen, steht in der Prüfkonfiguration des CheckedTerm (D#39, D#40). sandbox: dieselbe wie in der ExecConfig
  * des Laufs, aus derselben Runner-Konfiguration (D#88); ohne Angabe keine Zeile.
  */
final case class ExplainConfig(explainDenials: List[AllowedCommand], sandbox: Option[Sandbox] = None)

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
    val implicits = checked.implicits
    val (body, _, _) = AgentAlg.fromTerm(checked.term)(interpreter(code, check, implicits))(State(0, 1, Vector.empty))
    val allowlist = root.shellAllowlist.toList.map(_.show).sorted
    val denied = config.explainDenials.filterNot(Attenuation.allowedBy(root.shellAllowlist, _)).map(_.show)
    val warned = config.explainDenials.filter(Attenuation.allowedBy(root.shellAllowlist, _)).map(_.show)
    val params = checked.params
    val header = Vector(
      s"dryrun    ok  (worst case ${checked.worstCaseSteps} steps, depth ${checked.depth})",
      s"explain   ${checked.termHash.short}",
      // der volle Hash, den die Freigabe bestätigt (Approval.approve)
      s"hash      ${checked.termHash.hex}"
    ) ++ Option.when(params.nonEmpty)(s"params    ${Text.visible(params.toList.sorted.map((k, v) => s"$k=$v").mkString(" "))}") ++
      // einmal hier statt in jeder Zeile; die Schritte zeigen nur, was davon abweicht (D#87)
      Option.when(implicits.nonEmpty)(s"implizit  ${Text.visible(implicits.lines.mkString(" · "))} (in jedem Aufruf)")
    val footer = Vector(s"shell     ${if allowlist.isEmpty then "keine" else Text.visible(allowlist.mkString(", "))}") ++
      Option.when(denied.nonEmpty)(s"nicht erlaubt, u. a.: ${denied.mkString(", ")}") ++
      Option.when(warned.nonEmpty)(s"Warnung: erlaubt, obwohl in explainDenials: ${warned.mkString(", ")}") ++
      config.sandbox.collect { case s: Sandbox.On => s"sandbox   ${Text.visible(Sandbox.describe(s))}" } ++
      effects(checked.term, check)
    // ohne Sandbox die erste Zeile, damit es niemand überliest (D#88)
    val off = config.sandbox.collect { case Sandbox.Off => s"sandbox   ${Sandbox.describe(Sandbox.Off)}" }.toVector
    (off ++ header ++ Vector("") ++ body ++ Vector("") ++ footer).mkString("\n")

  /** Wirkungen nach außen (D#103): welche Befehle des Terms bleiben, wenn der Lauf scheitert, und ob ein Lauf ohne
    * Menschen (--no-ask) starten darf. Ohne Befehl mit Wirkung keine Zeile.
    */
  private def effects(term: Term, check: CheckConfig): Vector[String] =
    val all = Effects.all(term, check)
    if all.isEmpty then Vector.empty
    else
      val open = Effects.unguarded(term, check)
      Vector(s"wirkung   ${Text.visible(all.map(s => s"${s.what} (${s.effect.label})").mkString(", "))}") ++
        Option.when(check.unattended.nonEmpty)(s"ohne Frage erlaubt: ${Text.visible(check.unattended.map(_.show).mkString(", "))}") ++
        Vector(
          if open.isEmpty then "--no-ask   möglich: vor jeder Wirkung steht auf jedem Weg ein Ask, oder sie ist ohne Frage erlaubt"
          else s"--no-ask   abgelehnt: ohne Ask davor ${Text.visible(open.map(_.what).mkString(", "))}"
        )

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
    case Term.Hole(_, e, _, v) =>
      (e.shellAllowlist.toList.map(_.binary) ++ v.map(_.cmd)).flatMap(check.runsWorkspaceCode(_).toList.flatten).flatMap(RelPath.parse(_).toOption)
    case Term.Seq(steps, _)           => steps.flatMap(codePaths(_, check))
    case Term.If(_, a, b, _)          => codePaths(a, check) ++ codePaths(b, check)
    case Term.RepeatUntil(b, _, _, _) => codePaths(b, check)
    case _                            => Nil

  private val writes = Set(OpKind.WriteFile, OpKind.EditFile, OpKind.MoveFile)

  /** Ein Loch mit diesen Rechten hinterlässt Wirkung, die ein Programm prüfen könnte (D#80). */
  private val acts = writes + OpKind.Shell

  /** Unter einem Loch ohne Abnahme, das ändern oder Programme aufrufen darf (O#39). */
  val WithoutVerify = "ohne Abnahme: kein Programm prüft das Ergebnis im Schritt selbst, kein zweiter Durchlauf"

  /** Eine Hülle in einer Zeile, wie unter einem Agentenschritt; auch für den Vergleich in `ase tighten` (D#75). */
  def envelopeText(e: Envelope): String =
    val ops = e.allowedOps.toList.sortBy(_.ordinal).mkString(", ")
    val shell = Option.when(e.shellAllowlist.nonEmpty)(s" · shell: ${e.shellAllowlist.toList.map(_.show).sorted.mkString(", ")}")
    val steps = if e.maxSteps == 1 then "1 Schritt" else s"${e.maxSteps} Schritte"
    val scope = e.scopePrefix.fold("")(s => s" · sieht Ergebnisse unter $s")
    s"darf $ops · unter ${e.pathPrefix}$scope · max $steps · Tiefe ${e.maxDepth}${shell.getOrElse("")}"

  private def interpreter(code: List[Vector[String]], check: CheckConfig, implicits: Implicits): AgentAlg[Out] = new AgentAlg[Out]:
    private def pad(indent: Int): String = "  " * indent

    /** Ein Aufruf ohne die Paare, die die Elaboration genau so eingesetzt hat (D#87). */
    private def command(cmd: String, args: List[String]): String =
      (cmd :: Implicits.stripArgs(cmd, args, implicits, check.argPolicies)).mkString(" ")
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
      step(s"führt aus: ${command(cmd, args)}$marker${effectMarker(cmd, args)}", Scope.shellPaths(cmd, args, check))

    /** Wirkung nach außen am Schritt (D#103); leer, wenn der Aufruf nichts bewirkt, das bleibt. */
    def effectMarker(cmd: String, args: List[String]): String =
      val e = Effects.of(check.argPolicies, cmd, args)
      if e.acts then s" (${e.label})" else ""
    def ask(question: String, origin: Origin): Out[StepResult] =
      if textFitsInLine(question) then step(s"fragt: „${Text.visible(question)}“", Nil)
      else step("fragt:", Nil, i => textBlock("Frage", question, i + 1))
    def done(summary: String, origin: Origin): Out[StepResult] = step(s"fertig: $summary", Nil)

    def think(goal: String, envelope: Envelope, verify: Option[Verify], origin: Origin): Out[StepResult] = s =>
      val prefix = RelPath.parse(envelope.pathPrefix).toOption
      // die Abnahme ist Schritt s.next + 1; ab dem zweiten Durchlauf liegen Loch-Ergebnis und Abnahme vor (D#80)
      val verifyPaths = verify.map(v => Scope.shellPaths(v.cmd, v.args, check))
      val after = s.prior ++ (Scope.Seen(s.next, Nil) +: verifyPaths.map(Scope.Seen(s.next + 1, _)).toVector)
      val prior = if verify.isDefined then after else s.prior
      // welcher ausgeführte Code unter dem pathPrefix eines ändernden Lochs liegt (D#12, D#62)
      val changesCode =
        if !envelope.allowedOps.exists(writes) then Nil
        else prefix.toList.flatMap(p => code.filter(RelPath.overlaps(p, _))).map(c => RelPath.show(c)).distinct.sorted
      val seen = Scope.visible(prior, envelope).map(_.step).distinct
      val runsCode = envelope.shellAllowlist.toList.filter(c => check.runsWorkspaceCode(c.binary).isDefined).map(_.show).sorted
      // was der Agent hier nach außen bewirken darf (D#103)
      val outward = Effects.hull(envelope, check).map(s => s"${s.what}: ${s.effect.label}").sorted
      // die impliziten Flags gelten auch für die Aufrufe, die der Agent hier schreibt (D#87)
      val implicitHere = envelope.shellAllowlist.toList.map(_.binary).distinct.sorted
        .filter(b => implicits.pairs(b).nonEmpty).map(b => s"$b ${implicits.pairs(b).mkString(" ")}")
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
        Option.when(runsCode.nonEmpty)(note(s"Hinweis: darf Workspace-Code ausführen (${runsCode.mkString(", ")})", s.indent)) ++
        Option.when(outward.nonEmpty)(note(s"Hinweis: darf nach außen wirken (${outward.mkString(", ")})", s.indent)) ++
        Option.when(implicitHere.nonEmpty)(note(s"implizit in jedem Aufruf: ${implicitHere.mkString(" · ")}", s.indent)) ++
        // nur wo etwas zu prüfen ist: ein Loch, das ändern oder Programme aufrufen darf (O#39)
        Option.when(verify.isEmpty && envelope.allowedOps.exists(acts))(note(WithoutVerify, s.indent))
      val verifyLines = verify.toVector.flatMap { v =>
        val marker = if check.runsWorkspaceCode(v.cmd).isDefined then " (führt Workspace-Code aus)" else ""
        val passes = if v.max == 1 then "1 Durchlauf" else s"${v.max} Durchläufe"
        Vector(
          f"${s.next + 1}%-3d" + pad(s.indent) + Text.visible(s"Abnahme: führt aus: ${command(v.cmd, v.args)}$marker${effectMarker(v.cmd, v.args)}"),
          note(s"bestanden, wenn ${Pred.show(v.until)}; sonst entscheidet der Agent erneut, höchstens $passes", s.indent)
        )
      }
      // Das Ergebnis eines Lochs ist seine Done-Zusammenfassung, pfadlos (D#9); mit Abnahme danach deren Ausgabe
      (lines ++ verifyLines, s.next + Term.width(verify), after)

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
