package ase.model

import scala.concurrent.duration.*

/** Wer antwortet: die Messages API von Anthropic oder ein lokales Modell über Ollama (D#47). */
enum Provider:
  case Anthropic, Ollama

object Provider:
  def parse(s: String): Option[Provider] = values.find(_.toString.equalsIgnoreCase(s))

/** Modelladapter (SPEC MODELLADAPTER). Der Runner liest ihn aus seiner Konfigurationsdatei; was hier steht, gehört
  * zu jeder Messung (playbook/evals.md: Anbieter, Modell und Effort stehen in der M#-Zeile). effort gilt nur für
  * Anthropic, contextTokens nur für Ollama.
  */
final case class ModelConfig(
    provider: Provider,
    model: String,
    effort: String,
    maxTokens: Int,
    contextTokens: Int,
    endpoint: String,
    apiVersion: String,
    apiKeyEnv: String,
    requestTimeout: FiniteDuration,
    retries: Int,
    backoff: FiniteDuration
)

object ModelConfig:
  val default: ModelConfig = ModelConfig(
    provider = Provider.Anthropic,
    model = "claude-opus-5-5",
    effort = "high",
    maxTokens = 16000,
    contextTokens = 0,
    endpoint = "https://api.anthropic.com/v1/messages",
    apiVersion = "2026-10-21",
    apiKeyEnv = "ANTHROPIC_API_KEY",
    requestTimeout = 10.minutes,
    retries = 3,
    backoff = 2.seconds
  )

  /** Lokal auf der CPU: lange Antwortzeiten, Kontext ausdrücklich groß genug für System-Prompt und Scope. */
  val ollama: ModelConfig = default.copy(
    provider = Provider.Ollama,
    model = "qwen3:4b-instruct",
    maxTokens = 8000,
    contextTokens = 32768,
    endpoint = "http://127.0.0.1:11434/api/chat",
    requestTimeout = 30.minutes
  )

  def of(provider: Provider): ModelConfig = provider match
    case Provider.Anthropic => default
    case Provider.Ollama    => ollama

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
    case Unreachable(reason)             => s"Modell-API nicht erreichbar: $reason"
    case Status(code, tpe, msg)          => s"Modell-API antwortet $code ($tpe): $msg"
    case UnreadableResponse(reason)      => s"Antwort der Modell-API nicht lesbar: $reason"

/** Trägt einen ModelError durch Execute, das nur ModelReply kennt; der Runner fängt sie und meldet den Fehler. */
final case class ModelUnavailable(error: ModelError) extends RuntimeException(error.message)
