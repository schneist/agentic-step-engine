package ase.model

import ase.core.*
import cats.effect.Temporal
import cats.syntax.all.*

import scala.concurrent.duration.*

/** Plan durch das Modell. Der Runner prüft die Antwort (Tor 1). */
trait PlanModel[F[_]]:
  def plan(request: PlanRequest): F[ModelReply]

/** Der Modelladapter: ein Aufruf der Messages API pro Plan oder Fill, ohne Gesprächsverlauf. Was ein Versuch davor
  * falsch gemacht hat, steht in errors der Anfrage. Wiederholt wird nur bei 429 und 5xx, mit retry-after oder
  * wachsender Pause; alles andere, was kein Fehlversuch des Modells ist, endet als ModelUnavailable.
  */
final class Anthropic[F[_]: Temporal](transport: Transport[F], config: ModelConfig, check: CheckConfig)
    extends PlanModel[F]
    with FillModel[F]:
  private val system = Prompt.system(check)

  def plan(request: PlanRequest): F[ModelReply] = call(request.nestingLimit, Prompt.plan(request))

  def fill(request: FillRequest): F[ModelReply] = call(request.nestingLimit, Prompt.fill(request))

  /** Ohne Ausnahme: für Tests und den Runner, der den Fehler selbst meldet. */
  def attempt(nestingLimit: Int, user: String): F[Either[ModelError, ModelReply]] =
    val body = Messages.request(config, system, nestingLimit, user).noSpaces

    def go(retry: Int): F[Either[ModelError, ModelReply]] =
      transport.post(body).flatMap {
        case Left(reason) =>
          if retry < config.retries then Temporal[F].sleep(pause(retry, None)) >> go(retry + 1)
          else Temporal[F].pure(Left(ModelError.Unreachable(reason)))
        case Right(r) if r.status == 200 => Temporal[F].pure(Messages.reply(r.body))
        case Right(r) if Messages.retryable(r.status) && retry < config.retries =>
          Temporal[F].sleep(pause(retry, r.retryAfter)) >> go(retry + 1)
        case Right(r) => Temporal[F].pure(Left(Messages.error(r.status, r.body)))
      }

    go(0)

  private def call(nestingLimit: Int, user: String): F[ModelReply] =
    attempt(nestingLimit, user).flatMap {
      case Right(reply) => Temporal[F].pure(reply)
      case Left(e)      => Temporal[F].raiseError(ModelUnavailable(e))
    }

  private def pause(retry: Int, retryAfter: Option[FiniteDuration]): FiniteDuration =
    retryAfter.getOrElse(config.backoff * math.pow(2, retry).toLong)
