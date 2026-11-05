package ase.model

import scala.concurrent.duration.*

/** Wer antwortet: die Messages API von Anthropic, ein lokales Modell über Ollama (D#47), ein Modell über die
  * Antigravity-CLI agy (D#54) oder Claude über die Kommandozeile von Claude Code (claudecode, D#58).
  */
enum Provider:
  case Anthropic, Ollama, Agy, ClaudeCode

object Provider:
  def parse(s: String): Option[Provider] = values.find(_.toString.equalsIgnoreCase(s))

/** Modelladapter (SPEC MODELLADAPTER). Der Runner liest ihn aus seiner Konfigurationsdatei; was hier steht, gehört
  * zu jeder Messung (playbook/evals.md: Anbieter, Modell und Effort stehen in der M#-Zeile). effort gilt für
  * Anthropic und ClaudeCode, contextTokens und think nur für Ollama (think: erst frei denken, dann die Antwort im
  * Schema, D#48; None sendet das Feld nicht, false schaltet das Denken hybrider Modelle wie qwen3 ab, D#50). Bei agy
  * und ClaudeCode ist endpoint das Programm und workDir das Verzeichnis, unter dem jeder Aufruf ein eigenes leeres
  * Verzeichnis bekommt (leer: das temporäre Verzeichnis der JVM, D#54, D#58).
  */
final case class ModelConfig(
    provider: Provider,
    model: String,
    effort: String,
    maxTokens: Int,
    contextTokens: Int,
    think: Option[Boolean],
    endpoint: String,
    apiVersion: String,
    apiKeyEnv: String,
    requestTimeout: FiniteDuration,
    retries: Int,
    backoff: FiniteDuration,
    workDir: String
)

object ModelConfig:
  val default: ModelConfig = ModelConfig(
    provider = Provider.Anthropic,
    model = "claude-opus-5-5",
    effort = "high",
    maxTokens = 16000,
    contextTokens = 0,
    think = None,
    endpoint = "https://api.anthropic.com/v1/messages",
    apiVersion = "2026-10-21",
    apiKeyEnv = "ANTHROPIC_API_KEY",
    requestTimeout = 10.minutes,
    retries = 3,
    backoff = 2.seconds,
    workDir = ""
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

  /** Ein Modell über agy: der Aufruf startet einen Agenten, der eine Weile braucht; kein Schlüssel, agy ist angemeldet. */
  val agy: ModelConfig = default.copy(
    provider = Provider.Agy,
    model = "gemini-3.8-flash-medium",
    endpoint = "agy",
    requestTimeout = 10.minutes
  )

  /** Claude über claude --print mit der Anmeldung des Abos; endpoint ist das Programm. */
  val claudeCode: ModelConfig = default.copy(provider = Provider.ClaudeCode, endpoint = "claude")

  def of(provider: Provider): ModelConfig = provider match
    case Provider.Anthropic => default
    case Provider.Ollama    => ollama
    case Provider.Agy       => agy
    case Provider.ClaudeCode => claudeCode

  val efforts: Set[String] = Set("low", "medium", "high", "xhigh", "max")

  /** Rechner, zu denen http:// genügt (D#93): der Verkehr verlässt die Maschine nicht. */
  val Loopback: Set[String] = Set("localhost", "127.0.0.1", "[::1]", "::1")

  /** HTTP-Anbieter nur über https://, http:// nur zum eigenen Rechner (D#93): Der Schlüssel und alles, was ans Modell
    * geht, liefe sonst im Klartext. Für agy und claudecode ist endpoint ein Programm; das prüft CliProcess.locate.
    */
  def checkEndpoint(c: ModelConfig): Either[String, Unit] = c.provider match
    case Provider.Anthropic | Provider.Ollama =>
      val uri = try Option(java.net.URI.create(c.endpoint)).filter(u => u.getHost != null) catch case _: IllegalArgumentException => None
      uri match
        case None => Left(s"endpoint „${c.endpoint}“ ist keine URL mit Rechner")
        case Some(u) =>
          val scheme = Option(u.getScheme).map(_.toLowerCase)
          if scheme.contains("https") then Right(())
          else if scheme.contains("http") && Loopback(u.getHost.toLowerCase) then Right(())
          else Left(s"endpoint „${c.endpoint}“: nur https://, http:// nur zu localhost, 127.0.0.1 oder [::1] (D#93)")
    case Provider.Agy | Provider.ClaudeCode => Right(())

/** Der Adapter kann das Modell nicht erreichen oder bekommt eine Antwort, die kein Modellfehler ist. Das ist kein
  * Fehlversuch des Modells: Der Lauf endet, statt Versuche zu verbrauchen.
  */
enum ModelError:
  case MissingApiKey(env: String)
  /** endpoint verletzt D#93: http zu einem fremden Rechner, ein Programm im Arbeitsverzeichnis oder keins. */
  case Endpoint(reason: String)
  case Unreachable(reason: String)
  /** HTTP-Status ungleich 200 nach allen Wiederholungen oder ohne Wiederholung (4xx). */
  case Status(code: Int, errorType: String, detail: String)
  case UnreadableResponse(reason: String)

  def message: String = this match
    case MissingApiKey(env)              => s"Umgebungsvariable $env ist nicht gesetzt."
    case Endpoint(reason)                => s"Modell-Endpunkt: $reason"
    case Unreachable(reason)             => s"Modell-API nicht erreichbar: $reason"
    case Status(code, tpe, msg)          => s"Modell-API antwortet $code ($tpe): $msg"
    case UnreadableResponse(reason)      => s"Antwort der Modell-API nicht lesbar: $reason"

/** Trägt einen ModelError durch Execute, das nur ModelReply kennt; der Runner fängt sie und meldet den Fehler. */
final case class ModelUnavailable(error: ModelError) extends RuntimeException(error.message)
