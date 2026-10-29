package ase.core

/** Was ein Loch beim Fill sieht (D#9, D#30, D#39). Eine Regel für Explain (vorher) und Execute (zur Laufzeit). */
object Scope:
  /** Ergebnis eines früheren Schritts: Nummer wie im Explain und die Pfade, die das Ergebnis trägt (leer = pfadlos). */
  final case class Seen(step: Int, paths: List[String])

  /** Sichtbar sind Ergebnisse ohne Pfad und solche, deren Pfade alle unter dem pathPrefix des Lochs liegen, oder unter
    * seinem scopePrefix, wenn die Hülle einen nennt (D#69).
    */
  def visible(prior: Vector[Seen], hole: Envelope): Vector[Seen] =
    RelPath.parse(hole.sees) match
      case Left(_)       => Vector.empty
      case Right(prefix) => prior.filter(_.paths.forall(RelPath.isUnder(_, prefix)))

  /** Pfade eines Shell-Ergebnisses (D#39): die ausdrücklich genannten. Ohne Pfadargument pfadlos (leer) nur bei
    * Programmen, die Workspace-Code ausführen, und bei Befehlen aus config.pathless; sonst das ganze
    * Arbeitsverzeichnis ".". Ohne Policy ebenfalls ".".
    */
  def shellPaths(cmd: String, args: List[String], config: CheckConfig): List[String] =
    ArgPolicy.find(config.argPolicies, cmd, args) match
      case None => List(".")
      case Some(policy) =>
        val explicit = ArgPolicy.parse(policy, args).paths
        if explicit.nonEmpty then explicit
        else if config.runsWorkspaceCode(cmd).isDefined || config.pathless.exists(_.allows(cmd, args)) then Nil
        else List(".")
