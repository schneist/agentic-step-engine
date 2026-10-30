package ase.runner

import ase.core.*

/** `ase tighten` (D#75): schlägt für einen Flow engere Hüllen seiner Agentenschritte vor, aus den Läufen, deren
  * freigegebener Term dieselbe Form hat (BestOfN.shape; Flow-Parameter ändern die Form nicht). Schreibt einen neuen
  * Flow und gibt nichts frei. Probe: dieselben Änderungen am gebundenen Term des neuesten Laufs, gegen dessen Wurzel
  * durch DryRun (Tor 1); scheitert sie, entsteht kein Vorschlag.
  */
object FlowTighten:
  final case class Result(flow: Flow, proposals: Vector[Tighten.Proposal], runs: Int, probe: String)

  def run(flow: Flow, runs: Vector[RunStats.Run], minCalls: Int, check: CheckConfig): Either[String, Result] =
    val shape = BestOfN.shape(flow.term)
    val matching = runs
      .flatMap(r => r.events.flatMap(es => es.collectFirst { case Event.Start(_, root, term) => (r, es, root, term) }))
      .filter((_, _, _, term) => BestOfN.shape(term) == shape)
    if matching.isEmpty then Left("Keine Läufe mit Record-Log, deren Term die Form dieses Flows hat.")
    else
      val calls = matching.flatMap((_, es, _, _) => Usage.of(es)).groupBy(_.at.parts)
      val proposals = Tighten.holes(flow.term).map { (n, hole) =>
        Tighten.propose(n, hole.envelope, calls.getOrElse(Vector(n), Vector.empty), minCalls)
      }
      val changes = proposals.filterNot(_.change.isEmpty).map(p => p.step -> p.change).toMap
      val (newest, _, root, bound) = matching.maxBy(_._1.dir)
      val probe = Tighten.apply(bound, changes)
      // nur enger (Attenuation) und gültig (Tor 1), am gebundenen Term geprüft
      val wider = Tighten.holes(bound).zip(Tighten.holes(probe)).flatMap { case ((n, old), (_, neu)) =>
        Attenuation.check(neu.envelope, old.envelope, Location.root, Attenuation.Mode.Flow).map(v => s"Agentenschritt $n: ${v.render}")
      }
      if wider.nonEmpty then Left(("Vorschlag wäre weiter als vorher:" +: wider).mkString("\n"))
      else
        DryRun.plan(probe, root, check) match
          case Left(vs) => Left((s"Probe gescheitert (DryRun gegen den Term aus ${newest.dir}):" +: vs.toList.map(_.render)).mkString("\n"))
          case Right(_) =>
            val note = s" Hüllen eingeengt aus ${matching.size} Läufen (ase tighten, D#75)."
            val tightened = flow.copy(description = flow.description + note, term = Tighten.apply(flow.term, changes))
            Right(Result(tightened, proposals, matching.size, s"DryRun ok gegen den Term aus ${newest.dir}"))

  def render(before: Flow, r: Result): String =
    val old = Tighten.holes(before.term).toMap
    val neu = Tighten.holes(r.flow.term).toMap
    val lines = r.proposals.flatMap { p =>
      val head = s"Agentenschritt ${p.step} · ${p.calls} Aufrufe"
      val body =
        if p.change.isEmpty then Vector("  unverändert")
        else
          Vector(
            s"  alt   ${Explain.envelopeText(old(p.step).envelope)}",
            s"  neu   ${Explain.envelopeText(neu(p.step).envelope)}"
          ) ++ p.reasons.map("  weil  " + _)
      (head +: body) ++ p.skipped.map("  bleibt " + _) :+ ""
    }
    val changed = r.proposals.count(!_.change.isEmpty)
    (Vector(s"tighten   ${r.runs} Läufe · $changed von ${r.proposals.size} Agentenschritten enger", s"probe     ${r.probe}", "") ++ lines).mkString("\n")
