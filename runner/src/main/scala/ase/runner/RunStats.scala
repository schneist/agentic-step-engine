package ase.runner

import ase.core.*
import io.circe.{HCursor, Json}

/** `ase stats` (D#74): legt Läufe nebeneinander, je Flow oder Aufgabe in ihrer Fassung (source und sourceSha aus
  * summary.json, D#74). Läufe ohne diese Kennung, aus der Zeit davor, gruppiert es nach der Form des freigegebenen
  * Terms (BestOfN.shape, D#65). Je Agentenschritt des Terms stellt es Erlaubtes und Genutztes gegenüber (Usage). Liest
  * nur; was daraus folgt, entscheidet der Mensch, und jede engere Hülle ist ein neuer Flow mit eigener Freigabe.
  */
object RunStats:
  /** Ein Lauf: wo er liegt, seine Kennzahlen, wenn lesbar sein Record-Log, und wann seine summary.json geschrieben
    * wurde (Millisekunden; für den jüngsten Lauf).
    */
  final case class Run(dir: String, summary: Json, events: Option[Vector[Event]], modified: Long = 0L)

  /** identified: ob die Läufe eine Flow-Kennung tragen; diese Gruppen stehen vorn. */
  final case class Group(label: String, identified: Boolean, runs: Vector[Run])

  def group(runs: Vector[Run]): Vector[Group] =
    runs
      .groupBy(key)
      .toVector
      .map((k, rs) => Group(k._1, k._2, rs.sortBy(_.dir)))
      .sortBy(g => (!g.identified, g.label))

  private def str(c: HCursor, f: String): Option[String] = c.get[String](f).toOption
  private def long(c: HCursor, f: String): Long = c.get[Long](f).getOrElse(0L)

  private def key(r: Run): (String, Boolean) =
    val c = r.summary.hcursor
    str(c, "source") match
      case Some(src) =>
        val name = src.split("[/\\\\]").last
        (s"$name · Fassung ${str(c, "sourceSha").map(_.take(8)).getOrElse("unbekannt")}", true)
      case None =>
        r.events.flatMap(_.collectFirst { case Event.Start(_, _, term) => term }) match
          case Some(term) => (s"ohne Flow-Kennung · Form ${BestOfN.shape(term).take(8)}", false)
          case None       => ("ohne Flow-Kennung und ohne Record-Log", false)

  private def tokens(n: Long): String = if n >= 1000 then f"${n / 1000.0}%.1fk" else n.toString

  private def median(xs: Vector[Double]): Double =
    val s = xs.sorted
    if s.size % 2 == 1 then s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2

  private def spread(xs: Vector[Double], show: Double => String): String =
    if xs.isEmpty then "–"
    else if show(xs.min) == show(xs.max) then show(xs.head)
    else s"Median ${show(median(xs))} · ${show(xs.min)} bis ${show(xs.max)}"

  private def row(label: String, text: String): String = f"  $label%-12s $text"

  def render(groups: Vector[Group]): String =
    groups.map(renderGroup).mkString("\n\n")

  private def renderGroup(g: Group): String =
    val cs = g.runs.map(_.summary.hcursor)
    val n = cs.size
    val outcomes = cs.flatMap(str(_, "outcome")).groupBy(identity).toVector.sortBy(-_._2.size).map((o, v) => s"$o ${v.size}").mkString(" · ")
    def sum(f: String) = cs.map(long(_, f)).sum
    val seconds = cs.flatMap(_.get[Double]("seconds").toOption)
    val plans = cs.flatMap(str(_, "planFingerprint")).distinct.size
    val withLog = g.runs.flatMap(_.events)
    val head = Vector(
      s"== ${g.label} · ${plural(n, "Lauf", "Läufe")}",
      row("Ausgang", outcomes),
      row("Tokens", spread(cs.map(long(_, "tokens").toDouble), d => tokens(d.round))),
      row("Dauer", if seconds.isEmpty then "– (erst ab D#74 erfasst)" else spread(seconds, d => f"$d%.0f s")),
      row("Modell", s"Plan ${sum("planCalls")} · Fill ${sum("fillCalls")} · Runden ${sum("holeRounds")}"),
      row("Ablehnungen", s"Tor 1 ${sum("gate1Rejections")} · Tor 2 ${sum("gate2Rejections")} · ohne Term ${sum("unusableAnswers")} · Laufzeitfehler ${sum("runtimeFailures")} · Abnahme nicht bestanden ${sum("verifyFailures")}"),
      row("Fragen", s"in ${cs.count(_.get[Boolean]("asked").getOrElse(false))} von $n Läufen"),
      row("Pläne", if plans <= 1 then "ein Term" else s"$plans verschiedene Terme; Agentenschritte gleicher Nummer können verschiedene sein"),
      row("Record-Log", if withLog.size == n then s"alle $n gelesen" else s"${withLog.size} von $n gelesen; Agentenschritte nur aus diesen")
    )
    val holes = withLog
      .flatMap(Usage.of)
      .groupBy(_.at)
      .toVector
      .sortBy(_._1.parts.toList)(Ordering.Implicits.seqOrdering)
      .map((at, calls) => renderHole(at, calls, withLog.size))
    (head ++ holes.flatten).mkString("\n")

  private def plural(n: Int, one: String, many: String): String = s"$n ${if n == 1 then one else many}"

  private def ops(s: Set[OpKind]): String = if s.isEmpty then "keine" else s.toList.sortBy(_.ordinal).mkString(", ")

  private def distinct(values: Vector[String]): String =
    values.distinct match
      case Vector(one) => one
      case many        => s"${many.size} verschiedene, etwa ${many.head}"

  private def renderHole(at: StepId, calls: Vector[Usage.Call], runs: Int): Vector[String] =
    val allowedOps = calls.map(_.envelope.allowedOps).reduce(_ ++ _)
    val usedOps = calls.map(_.ops).reduce(_ ++ _)
    val allowedPrograms = calls.flatMap(_.envelope.shellAllowlist.map(_.binary)).toSet
    val usedPrograms = calls.map(_.programs).reduce(_ ++ _)
    val maxSteps = calls.map(_.envelope.maxSteps).min
    val usedSteps = calls.map(_.steps).max
    val shapes = calls.flatMap(_.shapes)
    val fingerprints = calls.flatMap(_.fingerprints)
    val rejected = calls.flatMap(_.rejected.toVector).groupMapReduce(_._1)(_._2)(_ + _)
    // je Aufruf der engste gemeinsame Pfad, gegen den pathPrefix desselben Aufrufs (Flow-Parameter ändern ihn)
    val narrower = calls.map(c => Usage.commonPrefix(c.paths).filter(p => Tighten.strictlyUnder(p, c.envelope.pathPrefix)))
    val pathsUsed = calls.flatMap(c => Usage.commonPrefix(c.paths))
    val first = calls.head
    val hints = Vector(
      Option.when((allowedOps -- usedOps).nonEmpty)(s"nie genutzt: ${ops(allowedOps -- usedOps)}"),
      Option.when((allowedPrograms -- usedPrograms).nonEmpty)(s"Programme nie genutzt: ${(allowedPrograms -- usedPrograms).toList.sorted.mkString(", ")}"),
      Option.when(narrower.forall(_.isDefined) && calls.exists(_.paths.nonEmpty))(
        s"alle Pfade enger als erlaubt, etwa unter ${narrower.flatten.head} statt ${first.envelope.pathPrefix}"
      ),
      Option.when(usedSteps < maxSteps)(s"höchstens $usedSteps von $maxSteps Schritten genutzt"),
      // derselbe Teilterm in jedem Aufruf: der Agent entscheidet hier nichts, was sich nicht auch fest schreiben ließe
      Option.when(fingerprints.size >= 2 && fingerprints.distinct.size == 1)(
        s"in allen ${fingerprints.size} Teiltermen derselbe Teilterm; Kandidat für feste Schritte statt Agentenschritt"
      )
    ).flatten
    val sees = calls.flatMap(_.envelope.scopePrefix).distinct
    Vector(
      "",
      s"  Agentenschritt ${at.show} · ${plural(calls.size, "Aufruf", "Aufrufe")} in ${plural(runs, "Lauf", "Läufen")}: „${Text.visible(first.goal.take(60))}${if first.goal.length > 60 then " …" else ""}“",
      row("erlaubt", s"${ops(allowedOps)} · unter ${distinct(calls.map(_.envelope.pathPrefix))}" +
        (if sees.isEmpty then "" else s" · sieht unter ${distinct(sees)}") + s" · max $maxSteps Schritte" +
        (if allowedPrograms.isEmpty then "" else s" · Programme ${allowedPrograms.toList.sorted.mkString(", ")}")),
      row("genutzt", s"${ops(usedOps)} · Pfade ${if pathsUsed.isEmpty then "keine" else s"unter ${distinct(pathsUsed)}"} · höchstens $usedSteps Schritte" +
        (if usedPrograms.isEmpty then "" else s" · Programme ${usedPrograms.toList.sorted.mkString(", ")}")),
      row("Formen", if shapes.isEmpty then "kein angenommener Teilterm"
        else s"${plural(shapes.distinct.size, "Form", "Formen")} und ${plural(fingerprints.distinct.size, "Inhalt", "Inhalte")} in ${plural(shapes.size, "Teilterm", "Teiltermen")}"),
      row("Modell", s"${plural(calls.map(_.fills).sum, "Fill", "Fills")} · ${tokens(calls.map(_.tokens).sum)} Tokens · abgelehnt " +
        (if rejected.isEmpty then "0" else rejected.toList.sortBy(_._1.ordinal).map((g, k) => s"$g $k").mkString(", ")) +
        s" · gescheitert ${calls.map(_.failed).sum} · Runden ${calls.map(_.rounds).sum}")
    ) ++ hints.map(h => row("Hinweis", h))
