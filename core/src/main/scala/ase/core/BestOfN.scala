package ase.core

import io.circe.Json

/** BestOfN (SPEC INTERPRETER, D#63, D#65): n Kandidaten zur selben Anfrage. Ein Kandidat gilt nur, wenn mehr als die
  * Hälfte aller n dieselbe Form hat. Kandidaten, die Tor 1 nicht bestanden haben, zählen mit und stimmen mit keinem
  * überein. Ohne Mehrheit entscheidet der Mensch: ask statt raten. Eine reine Funktion; wer die Kandidaten erzeugt und
  * prüft, ist Rand (der Planner im Runner).
  */
object BestOfN:
  enum Vote:
    /** chosen ist der erste Kandidat der Mehrheit, in der Reihenfolge der Anfragen. */
    case Majority(chosen: CheckedTerm, agreeing: Int, of: Int)

    /** Keine Mehrheit: Zahl der Kandidaten je Form, die größte Gruppe zuerst; failed zählt die Kandidaten, die
      * Tor 1 nicht bestanden haben.
      */
    case Split(groups: List[(String, Int)], failed: Int, of: Int)

    def of: Int

  def vote(candidates: Vector[Option[CheckedTerm]]): Vote =
    val n = candidates.size
    val checked = candidates.flatten.zipWithIndex
    val groups = checked
      .groupBy((c, _) => shape(c.term))
      .toList
      .map((f, cs) => (f, cs.sortBy(_._2)))
      .sortBy((_, cs) => (-cs.size, cs.head._2))
    groups.headOption match
      case Some((_, cs)) if cs.size * 2 > n => Vote.Majority(cs.head._1, cs.size, n)
      case _                                => Vote.Split(groups.map((f, cs) => f -> cs.size), n - checked.size, n)

  /** Die Form eines Terms für die Abstimmung (D#65), als SHA-256: Kontrollfluss, Operationsarten, Programmnamen,
    * Prädikatarten und die erlaubten Operationen jedes Lochs. Nicht darin: Pfade, Argumente, Inhalte, Grenzen der
    * Hüllen, Prosa und origin. Gröber als der Fingerprint (D#19), der schon an maxSteps oder einem Flag unterscheidet
    * (O#30); was gilt, prüft danach Tor 1 am gewählten Kandidaten selbst, und freigegeben wird dessen voller Hash.
    */
  def shape(t: Term): String = Canonical.sha256Hex(Canonical.print(form(t)))

  private def op(name: String, more: (String, Json)*): Json = Json.obj(("op" -> Json.fromString(name)) +: more*)

  private def form(t: Term): Json = t match
    case _: Term.ReadFile          => op("ReadFile")
    case _: Term.ListDir           => op("ListDir")
    case _: Term.WriteFile         => op("WriteFile")
    case _: Term.EditFile          => op("EditFile")
    case _: Term.MoveFile          => op("MoveFile")
    case Term.Shell(cmd, _, _)     => op("Shell", "cmd" -> Json.fromString(cmd))
    case _: Term.Ask               => op("Ask")
    case _: Term.Done              => op("Done")
    case Term.Seq(steps, _)        => Json.obj("seq" -> Json.arr(steps.map(form)*))
    case Term.If(p, a, b, _)       => Json.obj("if" -> pred(p), "then" -> form(a), "else" -> form(b))
    case Term.RepeatUntil(b, u, _, _) => Json.obj("repeat" -> form(b), "until" -> pred(u))
    case Term.Hole(_, e, _, v) =>
      val ops = "hole" -> Json.arr(e.allowedOps.toList.sortBy(_.ordinal).map(o => Json.fromString(o.toString))*)
      Json.obj((ops :: v.map(x => "verify" -> Json.fromString(x.cmd)).toList)*)

  private def pred(p: Pred): Json = p match
    case Pred.Not(q)    => Json.obj("not" -> pred(q))
    case Pred.And(a, b) => Json.obj("and" -> Json.arr(pred(a), pred(b)))
    case other          => Json.fromString(other.productPrefix)
