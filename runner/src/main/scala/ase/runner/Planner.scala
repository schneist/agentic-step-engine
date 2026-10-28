package ase.runner

import ase.core.*
import ase.model.{PlanModel, PlanRequest}
import cats.Monad
import cats.syntax.all.*

/** Ergebnis der Plan-Schleife: der geprüfte Term oder keiner, und was es gekostet hat. rejections zählt Tor 1
  * (Decoder und DryRun), unusable Antworten ohne Term; log ist der Plan-Teil des Lauf-Logs.
  */
final case class Planned(checked: Option[CheckedTerm], calls: Int, rejections: Int, unusable: Int, tokens: Long, log: Vector[String])

/** Aufgabe → Plan → Tor 1 (Decoder der Wurzel-Hülle, dann DryRun); bei Verstoß zurück ans Modell, höchstens
  * `rounds` Runden (SPEC RUNNER). Monad, weil jede Runde von der vorigen abhängt; der Runner ist Rand, nicht Algebra.
  */
object Planner:
  def run[F[_]: Monad](model: PlanModel[F], task: String, root: Envelope, check: CheckConfig, rounds: Int): F[Planned] =
    def round(k: Int, errors: Vector[String], acc: Planned): F[Planned] =
      if k > rounds then acc.pure[F]
      else
        val callId = s"plan-$k"
        model.plan(PlanRequest(callId, k, task, root, check.nestingLimit, errors)).flatMap { reply =>
          val called = acc.copy(calls = acc.calls + 1, tokens = acc.tokens + reply.tokens)
          def retry(gate: String, term: Option[Term], reasons: List[String], unusable: Boolean): F[Planned] =
            val toModel = rejection(term, reasons)
            val lines = Vector(f"$callId%-8s $gate%-7s rejected  ${reasons.headOption.getOrElse("")}") ++
              reasons.drop(1).map(r => " " * 26 + r) ++
              Vector(" " * 17 + s"→ an Modell: ${Text.visible(reasons.mkString("; ").take(160))}")
            val next =
              if unusable then called.copy(unusable = called.unusable + 1, log = called.log ++ lines)
              else called.copy(rejections = called.rejections + 1, log = called.log ++ lines)
            round(k + 1, errors :+ toModel, next)
          reply match
            case ModelReply.Failed(reason, _) => retry("answer", None, List(reason), unusable = true)
            case ModelReply.Answer(text, _) =>
              Submission.decode(text, root, check.nestingLimit, Origin.Model(callId)) match
                case Left(e) => retry("decode", None, List(e.message), unusable = false)
                case Right(term) =>
                  DryRun.plan(term, root, check) match
                    case Left(vs) => retry("dryrun", Some(term), vs.toList.map(_.render), unusable = false)
                    case Right(checked) =>
                      called.copy(checked = Some(checked), log = called.log :+ f"$callId%-8s dryrun  ok").pure[F]
        }

    round(1, Vector.empty, Planned(None, 0, 0, 0, 0L, Vector.empty))

  /** Was nach einer Ablehnung an das Modell geht: die Gründe und, wenn lesbar, der abgelehnte Term selbst; der
    * Adapter hat keinen Gesprächsverlauf.
    */
  def rejection(term: Option[Term], reasons: List[String]): String =
    ("Dein Term wurde abgelehnt und nicht ausgeführt:" :: reasons.map("- " + _)).mkString("\n") +
      term.fold("")(t => "\nAbgelehnter Term:\n" + Codec.term(t, withOrigin = false).noSpaces) +
      "\nLiefere einen neuen Term."
