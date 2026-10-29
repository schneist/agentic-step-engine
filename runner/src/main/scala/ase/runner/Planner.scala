package ase.runner

import ase.core.*
import ase.model.{PlanModel, PlanRequest}
import cats.Monad
import cats.syntax.all.*

/** Ergebnis der Plan-Schleife: der geprüfte Term oder keiner, und was es gekostet hat. rejections zählt Tor 1
  * (Decoder und DryRun), unusable Antworten ohne Term; log ist der Plan-Teil des Lauf-Logs. vote: die Abstimmung der
  * letzten Runde mit Kandidaten, die Tor 1 bestanden haben (BestOfN, D#63); bei bestOf 1 eine Mehrheit von 1 aus 1.
  * candidates: Kandidaten je Runde (bestOf).
  */
final case class Planned(
    checked: Option[CheckedTerm],
    calls: Int,
    rejections: Int,
    unusable: Int,
    tokens: Long,
    log: Vector[String],
    vote: Option[BestOfN.Vote] = None,
    candidates: Int = 1
):
  /** Kein Kandidat hatte die Mehrheit: der Mensch entscheidet (ask statt raten). */
  def split: Boolean = vote.exists(_.isInstanceOf[BestOfN.Vote.Split])

/** Aufgabe → Plan → Tor 1 (Decoder der Wurzel-Hülle, dann DryRun); bei Verstoß zurück ans Modell, höchstens
  * `rounds` Runden (SPEC RUNNER). Mit bestOf n > 1 fragt jede Runde n-mal und stimmt über die Form ab
  * (BestOfN, D#63, D#65); eine Runde ohne geprüften Kandidaten geht mit den Gründen des ersten an das Modell zurück. Monad,
  * weil jede Runde von der vorigen abhängt; der Runner ist Rand, nicht Algebra.
  */
object Planner:
  /** Ein abgelehnter Kandidat: Tor, Term (wenn lesbar), Gründe; unusable heißt Antwort ohne Term. */
  private final case class Rejected(gate: String, term: Option[Term], reasons: List[String], unusable: Boolean)

  def run[F[_]: Monad](model: PlanModel[F], task: String, root: Envelope, check: CheckConfig, rounds: Int, bestOf: Int = 1): F[Planned] =
    def candidate(callId: String, reply: ModelReply): Either[Rejected, CheckedTerm] =
      reply match
        case ModelReply.Failed(reason, _) => Left(Rejected("answer", None, List(reason), unusable = true))
        case ModelReply.Answer(text, _) =>
          for
            term <- Submission.decode(text, root, check.nestingLimit, Origin.Model(callId)).leftMap(e => Rejected("decode", None, List(e.message), unusable = false))
            checked <- DryRun.plan(term, root, check).leftMap(vs => Rejected("dryrun", Some(term), vs.toList.map(_.render), unusable = false))
          yield checked

    def lines(callId: String, result: Either[Rejected, CheckedTerm]): Vector[String] = result match
      case Right(_) => Vector(f"$callId%-8s dryrun  ok")
      case Left(r) =>
        Vector(f"$callId%-8s ${r.gate}%-7s rejected  ${r.reasons.headOption.getOrElse("")}") ++ r.reasons.drop(1).map(" " * 26 + _)

    def round(k: Int, errors: Vector[String], acc: Planned): F[Planned] =
      if k > rounds then acc.pure[F]
      else
        val ids = if bestOf == 1 then List(s"plan-$k") else (1 to bestOf).toList.map(i => s"plan-$k.$i")
        ids.traverse(id => model.plan(PlanRequest(id, k, task, root, check.nestingLimit, errors)).map(id -> _)).flatMap { replies =>
          val results = replies.map((id, reply) => (id, candidate(id, reply)))
          val rejected = results.collect { case (_, Left(r)) => r }
          val called = acc.copy(
            calls = acc.calls + replies.size,
            tokens = acc.tokens + replies.map(_._2.tokens).sum,
            rejections = acc.rejections + rejected.count(!_.unusable),
            unusable = acc.unusable + rejected.count(_.unusable),
            log = acc.log ++ results.flatMap(lines)
          )
          if rejected.size == results.size then
            val first = rejected.head
            val toModel = Vector(" " * 17 + s"→ an Modell: ${Text.visible(first.reasons.mkString("; ").take(160))}")
            round(k + 1, errors :+ rejection(first.term, first.reasons), called.copy(log = called.log ++ toModel))
          else
            val vote = BestOfN.vote(results.map(_._2.toOption).toVector)
            val voted = called.copy(vote = Some(vote), log = called.log ++ Option.when(bestOf > 1)(show(vote)))
            vote match
              case BestOfN.Vote.Majority(chosen, _, _) => voted.copy(checked = Some(chosen)).pure[F]
              case _: BestOfN.Vote.Split               => voted.pure[F]
        }

    round(1, Vector.empty, Planned(None, 0, 0, 0, 0L, Vector.empty, candidates = bestOf))

  /** Eine Zeile für das Lauf-Log: wie viele Kandidaten übereinstimmen, oder dass der Mensch entscheidet. */
  def show(vote: BestOfN.Vote): String = vote match
    case BestOfN.Vote.Majority(chosen, agreeing, of) =>
      val id = chosen.term.origin match
        case Origin.Model(callId) => callId
        case Origin.Human         => "Human"
      s"bestof   $agreeing von $of mit derselben Form ${BestOfN.shape(chosen.term).take(12)} → $id"
    case BestOfN.Vote.Split(groups, failed, of) =>
      val parts = groups.map((f, c) => s"${f.take(12)} ×$c") ++ Option.when(failed > 0)(s"$failed ohne Tor 1")
      s"bestof   keine Mehrheit unter $of: ${parts.mkString(", ")} → der Mensch entscheidet"

  /** Was nach einer Ablehnung an das Modell geht: die Gründe und, wenn lesbar, der abgelehnte Term selbst; der
    * Adapter hat keinen Gesprächsverlauf.
    */
  def rejection(term: Option[Term], reasons: List[String]): String =
    ("Dein Term wurde abgelehnt und nicht ausgeführt:" :: reasons.map("- " + _)).mkString("\n") +
      term.fold("")(t => "\nAbgelehnter Term:\n" + Codec.term(t, withOrigin = false).noSpaces) +
      "\nLiefere einen neuen Term."
