package ase.core

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.circe.Json

/** Implizite Flags eines Flows (D#87): je Programm Flag → Wert, nur für Pflicht-Flags der Konfiguration. Die
  * Elaboration setzt sie vor jedem DryRun in jeden Aufruf des Programms ein, auch in Abnahmen und in Fills; der Hash
  * geht über ihr Ergebnis. Wie ein impliziter Parameter in Scala, aber ohne Überschreiben: Ein implizites Flag, das im
  * Eingabeterm schon steht, ist ein Fehler, sonst wäre das der Weg, auf dem ein Agentenschritt das Konto wechselt.
  */
final case class Implicits(byProgram: Map[String, Map[String, String]]):
  def isEmpty: Boolean = byProgram.forall(_._2.isEmpty)
  def nonEmpty: Boolean = !isEmpty

  /** Die Paare für ein Programm, nach Flag-Namen sortiert, damit der Hash nicht an der Schreibreihenfolge hängt. */
  def pairs(cmd: String): List[String] =
    byProgram.get(cmd).toList.flatMap(_.toList.sortBy(_._1).flatMap((f, v) => List(f, v)))

  def flags(cmd: String): Set[String] = byProgram.get(cmd).fold(Set.empty)(_.keySet)

  /** Je Programm eine Zeile, etwa „aws: --profile cfd --region eu-central-1“, nach Programm sortiert. */
  def lines: List[String] =
    byProgram.toList.filter(_._2.nonEmpty).sortBy(_._1).map((cmd, _) => s"$cmd: ${pairs(cmd).mkString(" ")}")

object Implicits:
  val empty: Implicits = Implicits(Map.empty)

  def encode(i: Implicits): Json =
    Json.obj(i.byProgram.toList.filter(_._2.nonEmpty).sortBy(_._1).map((cmd, fs) =>
      cmd -> Json.obj(fs.toList.sortBy(_._1).map((f, v) => f -> Json.fromString(v))*)
    )*)

  /** {"aws": {"--profile": "cfd", "--region": "eu-central-1"}}; was das sein darf, prüft check gegen die Konfiguration. */
  def decode(json: Json): Either[String, Implicits] =
    json.asObject.toRight("erwartet ein Objekt Programm → {Flag: Wert}").flatMap(
      _.toList.traverse((cmd, j) =>
        j.asObject.toRight(s"$cmd: erwartet ein Objekt Flag → Wert").flatMap(
          _.toList.traverse((f, v) => v.asString.map(f -> _).toRight(s"$cmd $f: erwartet einen Text")).map(cmd -> _.toMap)
        )
      ).map(ps => Implicits(ps.toMap))
    )

  /** Passt die Tabelle zur Konfiguration? Nur Programme mit Pflicht-Flags und nur deren Pflicht-Flags (D#87). */
  def check(i: Implicits, config: CheckConfig): Either[String, Unit] =
    i.byProgram.toList.sortBy(_._1).traverse_ { (cmd, fs) =>
      config.required.get(cmd).filter(_.nonEmpty) match
        case None => Left(s"implicits: $cmd hat in der Konfiguration keine Pflicht-Flags (required)")
        case Some(required) =>
          fs.keySet.toList.sorted.find(f => !required(f))
            .map(f => s"implicits: $f ist kein Pflicht-Flag von $cmd (Pflicht: ${required.toList.sorted.mkString(", ")})")
            .toLeft(())
    }

  /** Term => Term (D#87): setzt die Paare direkt hinter die Pflicht-Argumente der Policy, die den Aufruf prüft, in jedes
    * Shell und jede Abnahme. Nicht in die Teilterme der Löcher: die kommen erst zur Laufzeit und werden an Tor 2
    * elaboriert. Ohne passende Policy bleibt der Aufruf, wie er ist; DryRun lehnt ihn ohnehin ab.
    */
  def elaborate(term: Term, i: Implicits, policies: List[ArgPolicy]): Either[NonEmptyList[Violation], Term] =
    if i.isEmpty then Right(term)
    else
      def shell(cmd: String, args: List[String], loc: Location): Either[NonEmptyList[Violation], List[String]] =
        val flags = i.flags(cmd)
        val explicit = args.flatMap(a => flags.find(f => a == f || a.startsWith(f + "="))).distinct
        NonEmptyList.fromList(explicit.map(Violation.ImplicitFlagGiven(loc / "args", cmd, _))) match
          case Some(vs) => Left(vs)
          case None     => Right(insert(cmd, args, i, policies))
      def go(t: Term, loc: Location): Either[NonEmptyList[Violation], Term] = t match
        case Term.Shell(cmd, args, o) => shell(cmd, args, loc).map(Term.Shell(cmd, _, o))
        case Term.Hole(g, e, o, v) =>
          v.traverse(x => shell(x.cmd, x.args, loc / "verify").map(a => x.copy(args = a))).map(Term.Hole(g, e, o, _))
        case Term.Seq(steps, o) =>
          steps.zipWithIndex.map((s, n) => go(s, loc.at("steps", n)).toValidated).sequence.toEither.map(Term.Seq(_, o))
        case Term.If(p, a, b, o) =>
          (go(a, loc / "thenT").toValidated, go(b, loc / "elseT").toValidated).mapN(Term.If(p, _, _, o)).toEither
        case Term.RepeatUntil(b, u, m, o) => go(b, loc / "body").map(Term.RepeatUntil(_, u, m, o))
        case other                        => Right(other)
      go(term, Location.root)

  private def insert(cmd: String, args: List[String], i: Implicits, policies: List[ArgPolicy]): List[String] =
    val pairs = i.pairs(cmd)
    ArgPolicy.find(policies, cmd, args) match
      case Some(p) if pairs.nonEmpty => args.take(p.prefixArgs.size) ++ pairs ++ args.drop(p.prefixArgs.size)
      case _                         => args

  /** Die Umkehrung für die Anzeige: entfernt die Paare nur dort, wo die Elaboration sie hinsetzt, und nur, wenn sie
    * genau so dort stehen. Ein abweichender Aufruf bleibt vollständig sichtbar.
    */
  def stripArgs(cmd: String, args: List[String], i: Implicits, policies: List[ArgPolicy]): List[String] =
    val pairs = i.pairs(cmd)
    ArgPolicy.find(policies, cmd, args) match
      case Some(p) if pairs.nonEmpty && args.slice(p.prefixArgs.size, p.prefixArgs.size + pairs.size) == pairs =>
        args.take(p.prefixArgs.size) ++ args.drop(p.prefixArgs.size + pairs.size)
      case _ => args

  /** strip(elaborate(t)) == t (Gesetz in den Tests). */
  def strip(term: Term, i: Implicits, policies: List[ArgPolicy]): Term =
    def go(t: Term): Term = t match
      case Term.Shell(cmd, args, o)     => Term.Shell(cmd, stripArgs(cmd, args, i, policies), o)
      case Term.Hole(g, e, o, v)        => Term.Hole(g, e, o, v.map(x => x.copy(args = stripArgs(x.cmd, x.args, i, policies))))
      case Term.Seq(steps, o)           => Term.Seq(steps.map(go), o)
      case Term.If(p, a, b, o)          => Term.If(p, go(a), go(b), o)
      case Term.RepeatUntil(b, u, m, o) => Term.RepeatUntil(go(b), u, m, o)
      case other                        => other
    if i.isEmpty then term else go(term)
