package ase.core

/** Engere Hüllen aus vergangenen Läufen (D#75). Zwei reine Funktionen: `propose` leitet aus der Nutzung (Usage) je
  * Agentenschritt eine engere Hülle ab, `apply` setzt sie in einen Term ein (Term => Term, kein Interpreter; DryRun
  * folgt danach, D#11). Hüllen werden nur enger, nie weiter: jedes Feld ist das alte oder ein Teil davon.
  * Freigegeben wird nichts; der neue Flow läuft durch `ase plan` wie jeder andere.
  */
object Tighten:
  /** Was sich an einer Hülle ändert; None heißt unverändert. path: neuer pathPrefix und die Sicht, die der
    * Agentenschritt behält (der alte pathPrefix, wenn er keinen eigenen scopePrefix hatte, D#69).
    */
  final case class Change(
      ops: Option[Set[OpKind]],
      shell: Option[Set[AllowedCommand]],
      maxSteps: Option[Int],
      maxDepth: Option[Int],
      path: Option[(String, Option[String])]
  ):
    def isEmpty: Boolean = ops.isEmpty && shell.isEmpty && maxSteps.isEmpty && maxDepth.isEmpty && path.isEmpty

    def applyTo(e: Envelope): Envelope =
      val (prefix, scope) = path.getOrElse((e.pathPrefix, e.scopePrefix))
      e.copy(
        allowedOps = ops.getOrElse(e.allowedOps),
        shellAllowlist = shell.getOrElse(e.shellAllowlist),
        maxSteps = maxSteps.getOrElse(e.maxSteps),
        maxDepth = maxDepth.getOrElse(e.maxDepth),
        pathPrefix = prefix,
        scopePrefix = scope
      )

  /** Ob die Läufe belegen, dass die genutzten Pfade nicht nur aus einem Fall stammen (D#78, O#37). Nur bei Varied wird
    * ein pathPrefix enger: Die Pfade eines Agentenschritts hängen oft vom Inhalt des Arbeitsverzeichnisses ab (der rote
    * Test liegt in genau einer Datei), und ein einziger Ausgangsstand zeigt das nicht.
    */
  enum PathEvidence:
    /** mindestens zwei verschiedene Ausgangsstände */
    case Varied
    /** der Flow hat Parameter, und alle Läufe hatten dieselben Werte (D#76) */
    case OneBinding
    /** alle Läufe vom selben Ausgangsstand */
    case OneStart
    /** kein Lauf nennt seinen Ausgangsstand (summary.json ohne workspaceSha, Läufe vor D#78) */
    case UnknownStart

  /** Ein Vorschlag für einen Agentenschritt, mit den Gründen in Klartext; skipped: warum ein Feld bleibt. */
  final case class Proposal(step: Int, calls: Int, change: Change, reasons: List[String], skipped: List[String])

  /** Die Agentenschritte des Terms mit ihrer Nummer wie im Explain und im Record-Log (D#20): jeder Blattknoten und
    * jeder Agentenschritt zählt eins, Seq, If und RepeatUntil nicht, der Wenn-Zweig vor dem Sonst-Zweig.
    */
  def holes(t: Term): Vector[(Int, Term.Hole)] =
    def go(t: Term, n: Int): (Int, Vector[(Int, Term.Hole)]) = t match
      // ein Loch mit Abnahme belegt zwei Nummern: das Loch und die Abnahme (D#80)
      case h: Term.Hole => (n + Term.width(h.verify), Vector(n -> h))
      case Term.Seq(steps, _) =>
        steps.foldLeft((n, Vector.empty[(Int, Term.Hole)])) { case ((k, acc), s) =>
          val (k2, hs) = go(s, k)
          (k2, acc ++ hs)
        }
      case Term.If(_, a, b, _) =>
        val (n1, ha) = go(a, n)
        val (n2, hb) = go(b, n1)
        (n2, ha ++ hb)
      case Term.RepeatUntil(b, _, _, _) => go(b, n)
      case _                            => (n + 1, Vector.empty)
    go(t, 1)._2

  /** Term => Term: ersetzt die Hüllen der genannten Agentenschritte. */
  def apply(t: Term, changes: Map[Int, Change]): Term =
    def go(t: Term, n: Int): (Int, Term) = t match
      case Term.Hole(g, e, o, v) => (n + Term.width(v), Term.Hole(g, changes.get(n).fold(e)(_.applyTo(e)), o, v))
      case Term.Seq(steps, o) =>
        val (k, out) = steps.foldLeft((n, Vector.empty[Term])) { case ((k, acc), s) =>
          val (k2, s2) = go(s, k)
          (k2, acc :+ s2)
        }
        (k, Term.Seq(out.toList, o))
      case Term.If(p, a, b, o) =>
        val (n1, a2) = go(a, n)
        val (n2, b2) = go(b, n1)
        (n2, Term.If(p, a2, b2, o))
      case Term.RepeatUntil(b, u, m, o) =>
        val (k, b2) = go(b, n)
        (k, Term.RepeatUntil(b2, u, m, o))
      case leaf => (n + 1, leaf)
    go(t, 1)._2

  private val Placeholder = "${"

  /** Vorschlag für einen Agentenschritt mit Hülle `e` (wie im Flow, Parameter ungebunden) aus seinen Aufrufen. Unter
    * minCalls abgeschlossenen Aufrufen bleibt alles, wie es ist: Was wenige Läufe nicht gebraucht haben, braucht der
    * nächste vielleicht. Abgebrochene Aufrufe zählen zum Genutzten, aber nicht zur Mindestzahl; das Budget bleibt, sobald
    * einer nicht abgeschlossen ist oder Tor 2 einen Teilterm abgelehnt hat (vielleicht am Budget). evidence: Der Pfad wird nur
    * enger, wenn die Läufe verschiedene Fälle zeigen (PathEvidence.Varied); sonst bleibt er, mit Grund.
    */
  def propose(step: Int, e: Envelope, calls: Vector[Usage.Call], minCalls: Int, evidence: PathEvidence = PathEvidence.Varied): Proposal =
    val done = calls.count(_.completed)
    if calls.isEmpty || done < minCalls then
      Proposal(step, calls.size, Change(None, None, None, None, None), Nil, List(s"nur $done abgeschlossene Aufrufe, mindestens $minCalls nötig"))
    else
      val used = calls.map(_.ops).reduce(_ ++ _)
      val programs = calls.map(_.programs).reduce(_ ++ _)
      val kept = e.allowedOps & used
      val ops = Option.when(kept.nonEmpty && kept != e.allowedOps)(kept)
      val opsSkipped = Option.when(kept.isEmpty)("Operationen bleiben: kein Aufruf hat eine genutzt, nur Done")
      val finalOps = ops.getOrElse(e.allowedOps)
      val shellParam = e.shellAllowlist.exists(c => (c.binary :: c.requiredPrefixArgs).exists(_.contains(Placeholder)))
      val shellKept =
        if !finalOps.contains(OpKind.Shell) then Set.empty[AllowedCommand] else e.shellAllowlist.filter(c => programs.contains(c.binary))
      val shell = Option.when(!shellParam && shellKept != e.shellAllowlist)(shellKept)
      val shellSkipped = Option.when(shellParam && shellKept != e.shellAllowlist)("Programme bleiben: die Allowlist enthält einen Parameter")
      val open = calls.count(c => !c.completed || c.rejected.contains(Gate.DryRun))
      val needed = calls.map(c => math.max(c.needed, c.steps)).max
      val steps = Option.when(open == 0 && needed >= 1 && needed < e.maxSteps)(needed)
      val stepsSkipped = Option.when(open > 0 && needed < e.maxSteps)(
        s"max Schritte bleibt: $open Aufruf(e) nicht abgeschlossen oder an Tor 2 abgelehnt, vielleicht am Budget"
      )
      val depth = Option.when(!finalOps.contains(OpKind.Hole) && e.maxDepth > 0)(0)
      val common = Usage.commonPrefix(calls.flatMap(_.paths).toSet)
      val (path, pathSkipped) =
        if (e.pathPrefix :: e.scopePrefix.toList).exists(_.contains(Placeholder)) then
          (None, Some(s"pathPrefix bleibt: Hülle enthält einen Parameter (${e.pathPrefix})"))
        else
          common match
            case Some(p) if strictlyUnder(p, e.pathPrefix) =>
              evidence match
                case PathEvidence.Varied => (Some(p -> Some(e.sees)), None)
                case PathEvidence.OneBinding =>
                  (None, Some(s"pathPrefix bleibt: alle Läufe mit denselben Flow-Parametern, die Pfade unter $p können aus ihnen stammen"))
                case PathEvidence.OneStart =>
                  (None, Some(s"pathPrefix bleibt: alle Läufe vom selben Ausgangsstand, die Pfade unter $p können von diesem Inhalt abhängen"))
                case PathEvidence.UnknownStart =>
                  (None, Some(s"pathPrefix bleibt: kein Lauf nennt seinen Ausgangsstand (workspaceSha), die Pfade unter $p können von einem einzigen Fall stammen"))
            case _                                         => (None, None)
      val reasons = List(
        ops.map(o => s"Operationen ${show(e.allowedOps)} → ${show(o)}: ${show(e.allowedOps -- o)} nie genutzt"),
        shell.map(s => s"Programme ${programsShow(e.shellAllowlist)} → ${programsShow(s)}"),
        steps.map(s => s"max ${e.maxSteps} → $s Schritte: höchstens $s gebraucht, mit dem schlechtesten Fall der Teilterme"),
        depth.map(_ => s"Tiefe ${e.maxDepth} → 0: kein verschachtelter Agentenschritt"),
        path.map((p, sees) => s"unter ${e.pathPrefix} → unter $p: alle Pfade lagen dort; sieht weiter Ergebnisse unter ${sees.getOrElse(p)}")
      ).flatten
      Proposal(step, calls.size, Change(ops, shell, steps, depth, path), reasons, List(opsSkipped, shellSkipped, stepsSkipped, pathSkipped).flatten)

  private def show(s: Set[OpKind]): String = s.toList.sortBy(_.ordinal).mkString(", ")

  private def programsShow(s: Set[AllowedCommand]): String = if s.isEmpty then "keine" else s.toList.map(_.show).sorted.mkString(", ")

  /** path liegt unter prefix und ist nicht prefix selbst (je Segment, nicht je Zeichen). */
  def strictlyUnder(path: String, prefix: String): Boolean =
    (RelPath.parse(path), RelPath.parse(prefix)) match
      case (Right(p), Right(q)) => p != q && p.startsWith(q)
      case _                    => false
