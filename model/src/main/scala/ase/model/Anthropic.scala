package ase.model

import ase.core.*
import cats.effect.Temporal
import cats.syntax.all.*

import scala.concurrent.duration.*

/** Plan durch das Modell. Der Runner prüft die Antwort (Tor 1). */
trait PlanModel[F[_]]:
  def plan(request: PlanRequest): F[ModelReply]

/** Ein Adapter kann planen und füllen; beide Anbieter teilen Prompts und Wiederholung. */
trait ModelAdapter[F[_]] extends PlanModel[F] with FillModel[F]

/** Ein Aufruf pro Plan oder Fill, ohne Gesprächsverlauf (D#44). Was ein Versuch davor falsch gemacht hat, steht in
  * errors der Anfrage. Wiederholt wird nur bei 429, 5xx und Verbindungsfehlern, mit retry-after oder wachsender
  * Pause; alles andere, was kein Fehlversuch des Modells ist, endet als ModelUnavailable.
  */
abstract class CallingAdapter[F[_]: Temporal](transport: Transport[F], config: ModelConfig, check: CheckConfig) extends ModelAdapter[F]:
  protected val system: String = Prompt.system(check)

  /** Anfrage des Anbieters aus System-Prompt, Verschachtelungsgrenze und Nutzernachricht. */
  protected def request(nestingLimit: Int, user: String): String

  /** Antwort mit Status 200 als ModelReply. */
  protected def reply(body: String): Either[ModelError, ModelReply]

  /** Fehlerantwort des Anbieters. */
  protected def error(status: Int, body: String): ModelError

  def plan(request: PlanRequest): F[ModelReply] = call(request.nestingLimit, Prompt.plan(request))

  def fill(request: FillRequest): F[ModelReply] = call(request.nestingLimit, Prompt.fill(request))

  /** Ohne Ausnahme: für Tests und den Runner, der den Fehler selbst meldet. */
  def attempt(nestingLimit: Int, user: String): F[Either[ModelError, ModelReply]] =
    val body = request(nestingLimit, user)

    def go(retry: Int): F[Either[ModelError, ModelReply]] =
      transport.post(body).flatMap {
        case Left(reason) =>
          if retry < config.retries then Temporal[F].sleep(pause(retry, None)) >> go(retry + 1)
          else Temporal[F].pure(Left(ModelError.Unreachable(reason)))
        case Right(r) if r.status == 200 => Temporal[F].pure(reply(r.body))
        case Right(r) if Messages.retryable(r.status) && retry < config.retries =>
          Temporal[F].sleep(pause(retry, r.retryAfter)) >> go(retry + 1)
        case Right(r) => Temporal[F].pure(Left(error(r.status, r.body)))
      }

    go(0)

  private def call(nestingLimit: Int, user: String): F[ModelReply] =
    attempt(nestingLimit, user).flatMap {
      case Right(reply) => Temporal[F].pure(reply)
      case Left(e)      => Temporal[F].raiseError(ModelUnavailable(e))
    }

  private def pause(retry: Int, retryAfter: Option[FiniteDuration]): FiniteDuration =
    retryAfter.getOrElse(config.backoff * math.pow(2, retry).toLong)

/** Die Messages API von Anthropic, mit einem Werkzeug submit_term (D#10, D#44). */
final class Anthropic[F[_]: Temporal](transport: Transport[F], config: ModelConfig, check: CheckConfig)
    extends CallingAdapter[F](transport, config, check):
  protected def request(nestingLimit: Int, user: String): String = Messages.request(config, system, nestingLimit, user).noSpaces
  protected def reply(body: String): Either[ModelError, ModelReply] = Messages.reply(body)
  protected def error(status: Int, body: String): ModelError = Messages.error(status, body)

object ModelAdapter:
  /** Der Adapter für den Anbieter der Konfiguration. */
  def apply[F[_]: Temporal](transport: Transport[F], config: ModelConfig, check: CheckConfig): ModelAdapter[F] =
    config.provider match
      case Provider.Anthropic => Anthropic[F](transport, config, check)
      case Provider.Ollama    => Ollama[F](transport, config, check)
      case Provider.Agy       => Agy[F](transport, config, check)
      case Provider.ClaudeCode => ClaudeCode[F](transport, config, check)
