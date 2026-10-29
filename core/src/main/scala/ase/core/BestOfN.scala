package ase.core

/** BestOfN (SPEC INTERPRETER, D#63): n Kandidaten zur selben Anfrage. Ein Kandidat gilt nur, wenn mehr als die Hälfte
  * aller n denselben structuralFingerprint hat (D#19). Kandidaten, die Tor 1 nicht bestanden haben, zählen mit und
  * stimmen mit keinem überein. Ohne Mehrheit entscheidet der Mensch: ask statt raten. Eine reine Funktion; wer die
  * Kandidaten erzeugt und prüft, ist Rand (der Planner im Runner).
  */
object BestOfN:
  enum Vote:
    /** chosen ist der erste Kandidat der Mehrheit, in der Reihenfolge der Anfragen. */
    case Majority(chosen: CheckedTerm, agreeing: Int, of: Int)

    /** Keine Mehrheit: Zahl der Kandidaten je Fingerprint, die größte Gruppe zuerst; failed zählt die Kandidaten, die
      * Tor 1 nicht bestanden haben.
      */
    case Split(groups: List[(Fingerprint, Int)], failed: Int, of: Int)

    def of: Int

  def vote(candidates: Vector[Option[CheckedTerm]]): Vote =
    val n = candidates.size
    val checked = candidates.flatten.zipWithIndex
    val groups = checked
      .groupBy((c, _) => Fingerprint.of(c.term))
      .toList
      .map((f, cs) => (f, cs.sortBy(_._2)))
      .sortBy((_, cs) => (-cs.size, cs.head._2))
    groups.headOption match
      case Some((_, cs)) if cs.size * 2 > n => Vote.Majority(cs.head._1, cs.size, n)
      case _                                => Vote.Split(groups.map((f, cs) => f -> cs.size), n - checked.size, n)
