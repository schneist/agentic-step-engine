package ase.model

import ase.core.*
import cats.effect.{Sync, Temporal}
import io.circe.Json
import io.circe.parser

import java.nio.file.Path
import scala.concurrent.duration.*

/** Claude über die Kommandozeile von Claude Code (D#58): `claude --print` mit der Anmeldung des Abos, ohne
  * API-Schlüssel. Unser System-Prompt ersetzt den von Claude Code (--system-prompt-file), Werkzeuge sind aus, die
  * Antwort ist per --json-schema an das Term-Schema gebunden; structured_output ist die Eingabe von submit_term.
  * Näher an der Messages API als agy, aber nicht dieselbe: kein Werkzeug mit strict-Schema, Effort über die CLI.
  */
final class ClaudeCode[F[_]: Temporal](transport: Transport[F], config: ModelConfig, check: CheckConfig)
    extends CallingAdapter[F](transport, config, check):
  protected def request(nestingLimit: Int, user: String): String = ClaudeCode.request(config, system, nestingLimit, user).noSpaces
  protected def reply(body: String): Either[ModelError, ModelReply] = ClaudeCode.reply(body)
  protected def error(status: Int, body: String): ModelError = ModelError.Status(status, "claude", body.take(500))

object ClaudeCode:
  /** Der System-Prompt spricht vom Werkzeug submit_term; hier ist die strukturierte Antwort dessen Eingabe. */
  private val noTool =
    s"\n\nDeine Antwort ist die Eingabe von ${Prompt.toolName}: ein JSON-Objekt {\"term\": …} nach dem vorgegebenen Schema."

  /** Was ClaudeCodeTransport braucht: Modell, Effort, System-Prompt, Nutzerprompt (über stdin), Schema. */
  def request(config: ModelConfig, system: String, nestingLimit: Int, user: String): Json = Json.obj(
    "model" -> Json.fromString(config.model),
    "effort" -> Json.fromString(config.effort),
    "system" -> Json.fromString(system + noTool),
    "prompt" -> Json.fromString(user),
    "schema" -> Schema.termSchema(nestingLimit)
  )

  /** Ausgabe von claude --print --output-format json. Erfolg mit structured_output: eine Antwort; Erfolg ohne, oder
    * ein Abbruch an einer Grenze (subtype error_max_…): ein Fehlversuch. Jeder andere Fehler ist kein Fehler des
    * Modells und endet als ModelUnavailable.
    */
  def reply(body: String): Either[ModelError, ModelReply] =
    parser
      .parse(body)
      .left
      .map(e => ModelError.UnreadableResponse(e.message))
      .flatMap(_.asObject.toRight(ModelError.UnreadableResponse("kein Objekt")))
      .flatMap { o =>
        val usage = o("usage").flatMap(_.asObject)
        val tokens = List("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
          .flatMap(k => usage.flatMap(_(k)).flatMap(_.asNumber).flatMap(_.toLong))
          .sum
        val isError = o("is_error").flatMap(_.asBoolean).getOrElse(true)
        val subtype = o("subtype").flatMap(_.asString).getOrElse("")
        val result = o("result").flatMap(_.asString).getOrElse("")
        val answer = o("structured_output").filterNot(_.isNull)
        if !isError && subtype == "success" then
          Right(answer match
            case Some(a) => ModelReply.Answer(a.noSpaces, tokens)
            case None    => ModelReply.Failed(s"Keine Antwort im Schema; antworte mit der Eingabe von ${Prompt.toolName}.", tokens)
          )
        else if subtype.startsWith("error_max") then Right(ModelReply.Failed(s"Abbruch ($subtype); liefere einen kürzeren Term.", tokens))
        else Left(ModelError.Status(200, "claude", s"$subtype: ${result.take(300)}"))
      }

/** Startet claude einmal pro Anfrage über CliProcess: System-Prompt als Datei, Nutzerprompt über stdin, Schema als
  * Argument; ein zu großes Schema für die Kommandozeile ist Status 413.
  */
final class ClaudeCodeTransport[F[_]: Sync](executable: String, workDir: Path, timeout: FiniteDuration) extends Transport[F]:
  def post(body: String): F[Either[String, HttpReply]] =
    Sync[F].blocking {
      parser.parse(body).toOption.flatMap(_.asObject) match
        case None => Right(HttpReply(400, None, "Anfrage ist kein JSON-Objekt"))
        case Some(o) =>
          def str(k: String): String = o(k).flatMap(_.asString).getOrElse("")
          val schema = o("schema").getOrElse(Json.obj()).noSpaces
          val command = executable :: ClaudeCodeTransport.args(str("model"), str("effort"), schema)
          CliProcess.run("claude", command, Map("system.txt" -> str("system")), str("prompt"), workDir, timeout)
    }

object ClaudeCodeTransport:
  /** Keine Werkzeuge, keine MCP-Server, keine Einstellungen (also keine Hooks und Plugins), keine Sitzung auf der
    * Platte; nur die Antwort im Schema.
    */
  def args(model: String, effort: String, schema: String): List[String] = List(
    "--print",
    "--output-format",
    "json",
    "--json-schema",
    schema,
    "--system-prompt-file",
    "system.txt",
    "--tools",
    "",
    "--strict-mcp-config",
    "--setting-sources",
    "",
    "--no-session-persistence",
    "--model",
    model,
    "--effort",
    effort
  )

  def apply[F[_]: Sync](config: ModelConfig): ClaudeCodeTransport[F] =
    new ClaudeCodeTransport[F](config.endpoint, CliProcess.workDir(config, "ase-claude"), config.requestTimeout)
