package ase.core

/** Lauf-Log für Menschen im Format des README (Abschnitt „Was während des Laufs passiert“), aus den Ereignissen. */
object RunLog:
  /** Was außerhalb von Execute geschieht: Modellaufrufe und Ablehnungen an Tor 1 (Plan) und wo das Record-Log liegt. */
  final case class Footer(planRejections: Int, recordPath: Option[String], planCalls: Int = 0, planTokens: Long = 0)

  private def quote(s: String, max: Int = 50): String =
    val flat = s.replace("\n", "⏎")
    "„" + (if flat.length > max then flat.take(max) + "…" else flat) + "“"

  private def plural(n: Long, one: String, many: String): String = s"$n ${if n == 1 then one else many}"

  /** Kurzform eines Teilterms: Operationen mit Ziel, Kontrollknoten mit Kindern. */
  def brief(t: Term): String = t match
    case Term.ReadFile(p, _)          => s"ReadFile $p"
    case Term.ListDir(p, _)           => s"ListDir $p"
    case Term.WriteFile(p, _, _)      => s"WriteFile $p"
    case Term.EditFile(p, _, _, _)    => s"EditFile $p"
    case Term.MoveFile(f, to, _)      => s"MoveFile $f $to"
    case Term.Shell(c, _, _)          => s"Shell $c"
    case Term.Ask(_, _)               => "Ask"
    case Term.Done(_, _)              => "Done"
    case Term.Hole(_, e, _)           => s"Hole ${e.pathPrefix}"
    case Term.Seq(steps, _)           => s"Seq(${steps.map(brief).mkString(", ")})"
    case Term.If(_, a, b, _)          => s"If(${brief(a)}, ${brief(b)})"
    case Term.RepeatUntil(b, _, m, _) => s"RepeatUntil(${brief(b)}, max $m)"

  private def target(c: Call): (String, String) = c match
    case Call.ReadFile(p)        => ("read", p)
    case Call.ListDir(p)         => ("list", p)
    case Call.WriteFile(p, _)    => ("write", p)
    case Call.EditFile(p, _, _)  => ("edit", p)
    case Call.MoveFile(f, t)     => ("move", s"$f → $t")
    case Call.Shell(cmd, args)   => ("shell", (cmd :: args).mkString(" "))
    case Call.FileExists(p)      => ("exists", p)

  /** Spalte vor der Operation: Nummer auf oberster Ebene, sonst eingerückt nach Loch-Tiefe. */
  private def lead(at: StepId): String =
    if at.level == 0 then f"${at.parts.head}%-3d" else "   " * at.level

  private def inner(hole: StepId): String = "   " * (hole.level + 1)

  private def line(lead: String, op: String, rest: String): String = (lead + f"$op%-7s" + rest).replaceAll("\\s+$", "")

  def reason(r: AbortReason): String = r match
    case AbortReason.StepFailed(at, e)        => s"Schritt ${at.show} gescheitert: ${Marshal(e)}"
    case AbortReason.AttemptsExhausted(at, n) => s"Loch ${at.show}: $n Versuche erschöpft"
    case AbortReason.BudgetExhausted(at, n)   => s"Loch ${at.show}: Schrittbudget $n aufgebraucht"
    case AbortReason.LimitReached(l) =>
      l match
        case Limit.TotalSteps(max) => s"Schrittlimit $max erreicht"
        case Limit.ModelCalls(max) => s"Limit von $max Modellaufrufen erreicht"
        case Limit.Time(seconds)   => s"Zeitlimit $seconds s erreicht"
        case Limit.Depth(max)      => s"Tiefenlimit $max erreicht"

  def render(events: Vector[Event], footer: Footer): String =
    val body = events.flatMap {
      case Event.Start(_, _, _) => Vector.empty
      case Event.Step(_, _: Call.FileExists, _) => Vector.empty
      case Event.Step(at, call, reply) =>
        val (op, tgt) = target(call)
        val result = (call, reply.result) match
          case (_: Call.Shell, Right(r)) => s"exit ${r.exitCode.fold("?")(_.toString)}  (${reply.note})"
          case (_, Right(_))             => if reply.note.isEmpty then "" else s"(${reply.note})"
          case (_, Left(e))              => s"Fehler: ${Marshal(e)}"
        Vector(line(lead(at), op, f"$tgt%-34s $result"))
      case Event.Rejected(at, op) => Vector(line(lead(at), "refuse", s"$op nicht in der Hülle (restrict)"))
      case Event.Hole(at, goal, _) => Vector("", line(lead(at), "hole", quote(goal)))
      case Event.Fill(_, _, _, _) => Vector.empty
      case Event.FillRejected(at, _, gate, term, reasons, toModel) =>
        val i = inner(at)
        val tor = gate match
          case Gate.Answer => "answer"
          case Gate.Decode => "decode"
          case Gate.DryRun => "dryrun"
        Vector(line(i, "fill", s"→ ${term.fold("(nicht lesbar)")(brief)}")) ++
          reasons.headOption.map(r => line(i, tor, s"rejected  $r")).toVector ++
          reasons.drop(1).map(r => i + " " * 17 + r) ++
          Vector(i + " " * 7 + s"→ an Modell: ${quote(toModel, 160)}")
      case Event.FillAccepted(at, _, hash, worst, budget, term) =>
        val i = inner(at)
        Vector(line(i, "fill", s"→ ${brief(term)}   hash ${hash.short}"), line(i, "dryrun", s"ok ($worst steps ≤ $budget)"))
      case Event.FillFailed(at, _, _, toModel) =>
        Vector(inner(at) + " " * 7 + s"→ an Modell: ${quote(toModel, 160)}")
      case Event.HoleRound(at, round) =>
        Vector(line(inner(at), "round", s"Runde $round: der Fill davor endete ohne Done, seine Ergebnisse gehen mit"))
      case Event.HoleDone(_, _) => Vector("")
      case Event.Asked(at, q, answer) =>
        Vector(line(lead(at), "ask", s"${quote(q, 80)}  → ${answer.fold("keine Antwort")(a => quote(a, 80))}"))
      case Event.Finished(at, summary) => Vector(line(lead(at), "done", summary))
      case Event.End(outcome, _) =>
        val text = outcome match
          case Outcome.Done       => "Lauf beendet"
          case Outcome.Ask(q)     => s"Lauf endet mit ask: ${quote(q, 80)}"
          case Outcome.Aborted(r) => s"Lauf abgebrochen: ${reason(r)}"
        Vector("", text)
    }
    // keine doppelten Leerzeilen
    val compact = body.foldLeft(Vector.empty[String])((acc, l) => if l.isEmpty && acc.lastOption.forall(_.isEmpty) then acc else acc :+ l)
    val s = Stats.of(events)
    val all = footer.planTokens + s.tokens
    val tokens = if all >= 1000 then f"${all / 1000.0}%.1fk" else all.toString
    val summary = List(
      Some(plural(s.steps, "Schritt", "Schritte")),
      Some(plural(footer.planCalls + s.modelCalls, "Modellaufruf", "Modellaufrufe")),
      Some(plural(footer.planRejections, "Ablehnung", "Ablehnungen") + " an Tor 1"),
      Some(plural(s.gate2Rejections, "Ablehnung", "Ablehnungen") + " an Tor 2"),
      Option.when(s.unusableAnswers > 0)(plural(s.unusableAnswers, "Antwort", "Antworten") + " ohne Teilterm"),
      Option.when(s.runtimeFailures > 0)(plural(s.runtimeFailures, "Laufzeitfehler", "Laufzeitfehler")),
      Some(plural(s.changedFiles.size, "Datei", "Dateien") + " geändert"),
      Some(s"$tokens Tokens")
    ).flatten.mkString(" · ")
    val hints = s.hints.map((at, h) => s"Hinweis des Agenten (Loch ${at.show}): $h")
    val approved = events.collectFirst { case Event.Start(h, _, _) => s"approved ${h.short}" }
    val record = (footer.recordPath.toList ++ approved.toList).mkString(" · ")
    (compact ++ Vector(summary) ++ hints ++ Option.when(record.nonEmpty)(record)).mkString("\n")
