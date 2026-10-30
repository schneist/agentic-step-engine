package ase.core

/** Was die Agentenschritte eines Laufs erlaubt bekamen und was sie davon genutzt haben (D#74). Eine reine Funktion über
  * das Record-Log; `ase stats` legt die Läufe eines Flows nebeneinander. Gezählt werden nur Agentenschritte des
  * freigegebenen Terms; was ein darin verschachtelter Agentenschritt tut, zählt für den äußeren.
  */
object Usage:
  /** Ein Aufruf eines Agentenschritts.
    *
    * ops: genutzte Operationen (Done zählt nicht, es ist immer erlaubt). paths: aufgelöste Pfade aller
    * Werkzeugaufrufe, auch FileExists. programs: aufgerufene Programme. steps: Schritte, die gegen maxSteps zählen
    * (Werkzeugaufrufe ohne FileExists, Fragen, Done, verschachtelte Agentenschritte). shapes: Form jedes angenommenen
    * Teilterms (BestOfN.shape, D#65), fingerprints dasselbe mit Pfaden und Inhalten
    * (Fingerprint, D#19): gleiche Form heißt gleicher Ablauf, gleicher Fingerprint derselbe Teilterm. rejected: abgelehnte Teilterme je Tor. failed: angenommene Teilterme, die zur
    * Laufzeit scheiterten. rounds: weitere Runden (D#55). needed: das Budget, das Tor 2 für
    * die angenommenen Teilterme brauchte, also die Schritte vor einem Teilterm plus dessen schlechtester Fall; mindestens
    * steps. fills und tokens: Modellaufrufe für diesen Aufruf.
    */
  final case class Call(
      at: StepId,
      goal: String,
      envelope: Envelope,
      ops: Set[OpKind],
      paths: Set[String],
      programs: Set[String],
      steps: Int,
      needed: Int,
      shapes: Vector[String],
      fingerprints: Vector[String],
      rejected: Map[Gate, Int],
      failed: Int,
      rounds: Int,
      fills: Int,
      tokens: Long
  )

  def of(events: Vector[Event]): Vector[Call] =
    def begin(e: Event): Option[Call] = e match
      case Event.Hole(at, goal, env) => Some(Call(at, goal, env, Set.empty, Set.empty, Set.empty, 0, 0, Vector.empty, Vector.empty, Map.empty, 0, 0, 0, 0L))
      case _                         => None
    val (done, open) = events.foldLeft((Vector.empty[Call], Option.empty[Call])) { case ((acc, cur), e) =>
      cur match
        case None                                          => (acc, begin(e))
        case Some(c) if ends(e, c.at)                       => (acc :+ c, None)
        case Some(c) if belongs(e, c.at)                    => (acc, Some(add(c, e)))
        // ein Ereignis außerhalb beendet den Aufruf (Abbruch ohne HoleDone) und kann selbst einen neuen beginnen
        case Some(c) => (acc :+ c, begin(e))
    }
    done ++ open

  private def ends(e: Event, hole: StepId): Boolean = e match
    case Event.HoleDone(a, _) => a == hole
    case _                    => false

  private def at(e: Event): Option[StepId] = e match
    case Event.Step(a, _, _)                => Some(a)
    case Event.Rejected(a, _)               => Some(a)
    case Event.Hole(a, _, _)                => Some(a)
    case Event.Fill(a, _, _, _)             => Some(a)
    case Event.FillRejected(a, _, _, _, _, _) => Some(a)
    case Event.FillAccepted(a, _, _, _, _, _) => Some(a)
    case Event.FillFailed(a, _, _, _)       => Some(a)
    case Event.HoleRound(a, _)              => Some(a)
    case Event.HoleDone(a, _)               => Some(a)
    case Event.Asked(a, _, _)               => Some(a)
    case Event.Finished(a, _)               => Some(a)
    case _: Event.Start | _: Event.End      => None

  private def inside(a: StepId, hole: StepId): Boolean =
    a.parts.size > hole.parts.size && a.parts.startsWith(hole.parts)

  /** Ereignisse des Aufrufs selbst tragen seine Position, die seiner Schritte und verschachtelter Agentenschritte eine
    * längere mit ihr als Anfang.
    */
  private def belongs(e: Event, hole: StepId): Boolean = at(e).exists(a => a == hole || inside(a, hole))

  /** Modellaufrufe und Teilterme zählen für den Aufruf, der sie hat; Modellaufrufe verschachtelter Agentenschritte
    * gehen in fills und tokens des äußeren ein, ihre Teilterme nicht in dessen Formen.
    */
  private def add(c: Call, e: Event): Call = e match
    case Event.Fill(_, _, _, reply)                         => c.copy(fills = c.fills + 1, tokens = c.tokens + reply.tokens)
    case Event.FillRejected(a, _, gate, _, _, _) if a == c.at => c.copy(rejected = c.rejected.updated(gate, c.rejected.getOrElse(gate, 0) + 1))
    case Event.FillAccepted(a, _, _, worst, _, term) if a == c.at =>
      c.copy(
        shapes = c.shapes :+ BestOfN.shape(term),
        fingerprints = c.fingerprints :+ Fingerprint.of(term).hex,
        needed = math.max(c.needed, c.steps + worst.toInt)
      )
    case Event.FillFailed(a, _, _, _) if a == c.at           => c.copy(failed = c.failed + 1)
    case Event.HoleRound(a, _) if a == c.at                  => c.copy(rounds = c.rounds + 1)
    case Event.Step(_, call, reply) =>
      val withPaths = c.copy(paths = c.paths ++ reply.touched)
      call match
        case _: ase.core.Call.FileExists => withPaths
        case other                       => withPaths.copy(ops = withPaths.ops + kind(other), programs = programs(withPaths, other), steps = c.steps + 1)
    case Event.Asked(_, _, _)  => c.copy(ops = c.ops + OpKind.Ask, steps = c.steps + 1)
    case Event.Finished(_, _)  => c.copy(steps = c.steps + 1)
    case Event.Hole(a, _, _) if a != c.at => c.copy(ops = c.ops + OpKind.Hole)
    // der angenommene Teilterm eines verschachtelten Agentenschritts zählt einmal als Schritt des äußeren (D#14)
    case Event.HoleDone(at, _) if at != c.at => c.copy(steps = c.steps + 1)
    case _                                   => c

  private def programs(c: Call, call: ase.core.Call): Set[String] = call match
    case ase.core.Call.Shell(cmd, _) => c.programs + cmd
    case _                           => c.programs

  private def kind(call: ase.core.Call): OpKind = call match
    case _: ase.core.Call.ReadFile   => OpKind.ReadFile
    case _: ase.core.Call.ListDir    => OpKind.ListDir
    case _: ase.core.Call.WriteFile  => OpKind.WriteFile
    case _: ase.core.Call.EditFile   => OpKind.EditFile
    case _: ase.core.Call.MoveFile   => OpKind.MoveFile
    case _: ase.core.Call.Shell      => OpKind.Shell
    case _: ase.core.Call.FileExists => OpKind.ReadFile

  /** Das tiefste Verzeichnis oder die einzige Datei, unter der alle Pfade liegen; "." wenn sie nichts teilen. */
  def commonPrefix(paths: Set[String]): Option[String] =
    val split = paths.toList.flatMap(p => RelPath.parse(p).toOption)
    split match
      case Nil => None
      case one :: Nil => Some(RelPath.show(one))
      case first :: rest =>
        val common = rest.foldLeft(first)((acc, p) => acc.zip(p).takeWhile((a, b) => a == b).map(_._1))
        Some(if common.isEmpty then "." else RelPath.show(common))
