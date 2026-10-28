package ase.model

import scala.concurrent.duration.*

/** Modelladapter (SPEC MODELLADAPTER). Der Runner liest ihn aus seiner Konfigurationsdatei; was hier steht, gehört
  * zu jeder Messung (playbook/evals.md: Modell und Effort stehen in der M#-Zeile).
  */
final case class ModelConfig(
    model: String,
    effort: String,
    maxTokens: Int,
    endpoint: String,
    apiVersion: String,
    apiKeyEnv: String,
    requestTimeout: FiniteDuration,
    retries: Int,
    backoff: FiniteDuration
)

object ModelConfig:
  val default: ModelConfig = ModelConfig(
    model = "claude-opus-5-5",
    effort = "high",
    maxTokens = 16000,
    endpoint = "https://api.anthropic.com/v1/messages",
    apiVersion = "2026-10-21",
    apiKeyEnv = "ANTHROPIC_API_KEY",
    requestTimeout = 10.minutes,
    retries = 3,
    backoff = 2.seconds
  )

  val efforts: Set[String] = Set("low", "medium", "high", "xhigh", "max")

/** Der Adapter kann das Modell nicht erreichen oder bekommt eine Antwort, die kein Modellfehler ist. Das ist kein
  * Fehlversuch des Modells: Der Lauf endet, statt Versuche zu verbrauchen.
  */
enum ModelError:
  case MissingApiKey(env: String)
  case Unreachable(reason: String)
  /** HTTP-Status ungleich 200 nach allen Wiederholungen oder ohne Wiederholung (4xx). */
  case Status(code: Int, errorType: String, detail: String)
  case UnreadableResponse(reason: String)

  def message: String = this match
    case MissingApiKey(env)              => s"Umgebungsvariable $env ist nicht gesetzt."
    case Unreachable(reason)             => s"Messages API nicht erreichbar: $reason"
    case Status(code, tpe, msg)          => s"Messages API antwortet $code ($tpe): $msg"
    case UnreadableResponse(reason)      => s"Antwort der Messages API nicht lesbar: $reason"

/** Trägt einen ModelError durch Execute, das nur ModelReply kennt; der Runner fängt sie und meldet den Fehler. */
final case class ModelUnavailable(error: ModelError) extends RuntimeException(error.message)
