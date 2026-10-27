package ase.core

/** Was ein Loch beim Fill sieht (D#9, D#30). Eine Regel für Explain (vorher) und Execute (zur Laufzeit). */
object Scope:
  /** Ergebnis eines früheren Schritts: Nummer wie im Explain und die Pfade, die das Ergebnis trägt (leer = pfadlos). */
  final case class Seen(step: Int, paths: List[String])

  /** Sichtbar sind Ergebnisse ohne Pfad und solche, deren Pfade alle unter dem pathPrefix des Lochs liegen. */
  def visible(prior: Vector[Seen], hole: Envelope): Vector[Seen] =
    RelPath.parse(hole.pathPrefix) match
      case Left(_)       => Vector.empty
      case Right(prefix) => prior.filter(_.paths.forall(RelPath.isUnder(_, prefix)))

  /** Pfade eines Shell-Ergebnisses: nur ausdrücklich genannte (D#9), nicht das implizite "." der Prüfung (D#27).
    * Ohne Policy gilt das Ergebnis als Ergebnis über das ganze Arbeitsverzeichnis.
    */
  def shellPaths(cmd: String, args: List[String], policies: List[ArgPolicy]): List[String] =
    ArgPolicy.find(policies, cmd, args).fold(List("."))(ArgPolicy.parse(_, args).paths)
