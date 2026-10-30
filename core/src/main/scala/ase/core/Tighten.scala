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

  /** Ein Vorschlag für einen Agentenschritt, mit den Gründen in Klartext; skipped: warum ein Feld bleibt. */
  final case class Proposal(step: Int, calls: Int, change: Change, reasons: List[String], skipped: List[String])

  /** Die Agentenschritte des Terms mit ihrer Nummer wie im Explain und im Record-Log (D#20): jeder Blattknoten und
    * jeder Agentenschritt zählt eins, Seq, If und RepeatUntil nicht, der Wenn-Zweig vor dem Sonst-Zweig.
    */
  def holes(t: Term): Vector[(Int, Term.Hole)] =
    def go(t: Term, n: Int): (Int, Vector[(Int, Term.Hole)]) = t match
      case h: Term.Hole => (n + 1, Vector(n -> h))
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
      case Term.Hole(g, e, o) => (n + 1, Term.Hole(g, changes.get(n).fold(e)(_.applyTo(e)), o))
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
    * minCalls Aufrufen bleibt alles, wie es ist: Was wenige Läufe nicht gebraucht haben, braucht der nächste vielleicht.
    */
  def propose(step: Int, e: Envelope, calls: Vector[Usage.Call], minCalls: Int): Proposal =
    if calls.size < minCalls then
      Proposal(step, calls.size, Change(None, None, None, None, None), Nil, List(s"nur ${calls.size} Aufrufe, mindestens $minCalls nötig"))
    else
      val used = calls.map(_.ops).reduce(_ ++ _)
      val programs = calls.map(_.programs).reduce(_ ++ _)
      val kept = e.allowedOps & used
      val ops = Option.when(kept.nonEmpty && kept != e.allowedOps)(kept)
      val opsSkipped = Option.when(kept.isEmpty)("Operationen bleiben: kein Aufruf hat eine genutzt, nur Done")
      val finalOps = ops.getOrElse(e.allowedOps)
      val shellKept =
        if !finalOps.contains(OpKind.Shell) then Set.empty[AllowedCommand] else e.shellAllowlist.filter(c => programs.contains(c.binary))
      val shell = Option.when(shellKept != e.shellAllowlist)(shellKept)
      val needed = calls.map(c => math.max(c.needed, c.steps)).max
      val steps = Option.when(needed >= 1 && needed < e.maxSteps)(needed)
      val depth = Option.when(!finalOps.contains(OpKind.Hole) && e.maxDepth > 0)(0)
      val paths = calls.flatMap(_.paths).toSet
      val common = Usage.commonPrefix(paths)
      val (path, pathSkipped) =
        if e.pathPrefix.contains(Placeholder) then (None, Some(s"pathPrefix bleibt: enthält einen Parameter (${e.pathPrefix})"))
        else
          common match
            case Some(p) if strictlyUnder(p, e.pathPrefix) => (Some(p -> Some(e.scopePrefix.getOrElse(e.pathPrefix))), None)
            case _                                         => (None, None)
      val reasons = List(
        ops.map(o => s"Operationen ${show(e.allowedOps)} → ${show(o)}: ${show(e.allowedOps -- o)} nie genutzt"),
        shell.map(s => s"Programme ${programsShow(e.shellAllowlist)} → ${programsShow(s)}"),
        steps.map(s => s"max ${e.maxSteps} → $s Schritte: höchstens $s gebraucht, mit dem schlechtesten Fall der Teilterme"),
        depth.map(_ => s"Tiefe ${e.maxDepth} → 0: kein verschachtelter Agentenschritt"),
        path.map((p, sees) => s"unter ${e.pathPrefix} → unter $p: alle Pfade lagen dort; sieht weiter Ergebnisse unter ${sees.getOrElse(p)}")
      ).flatten
      Proposal(step, calls.size, Change(ops, shell, steps, depth, path), reasons, List(opsSkipped, pathSkipped).flatten)

  private def show(s: Set[OpKind]): String = s.toList.sortBy(_.ordinal).mkString(", ")

  private def programsShow(s: Set[AllowedCommand]): String = if s.isEmpty then "keine" else s.toList.map(_.show).sorted.mkString(", ")

  private def strictlyUnder(path: String, prefix: String): Boolean =
    (RelPath.parse(path), RelPath.parse(prefix)) match
      case (Right(p), Right(q)) => p != q && p.startsWith(q)
      case _                    => false
