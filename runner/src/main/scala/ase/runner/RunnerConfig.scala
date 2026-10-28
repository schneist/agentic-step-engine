package ase.runner

import ase.core.*
import ase.model.{ModelConfig, Provider}
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.parser

import scala.concurrent.duration.*

/** Runner-Konfiguration (SPEC RUNNER): Modell, harte Limits, Verschachtelungsgrenze, Plan-Runden, explainDenials und
  * Skripte im Arbeitsverzeichnis (scripts, D#46). Jedes Feld ist optional (sonst der Standard), unbekannte Felder sind
  * Fehler. Die übrigen Argument-Policies, workspaceCode und pathless bleiben die Standards aus core (O#22).
  */
final case class RunnerConfig(model: ModelConfig, exec: ExecConfig, check: CheckConfig, explain: ExplainConfig, planRounds: Int)

object RunnerConfig:
  val default: RunnerConfig = RunnerConfig(ModelConfig.default, ExecConfig.default, CheckConfig.default, ExplainConfig.default, 3)

  def parse(text: String): Either[String, RunnerConfig] =
    parser.parse(text).left.map(e => s"Kein gültiges JSON: ${e.message}").flatMap(decode)

  def decode(json: Json): Either[String, RunnerConfig] =
    val d = default
    for
      o <- obj(json, "Konfiguration")
      _ <- only(o, "Konfiguration", "model", "limits", "nestingLimit", "planRounds", "explainDenials", "scripts")
      m <- o("model").fold(d.model.asRight[String])(modelConfig(_, d.model))
      e <- o("limits").fold(d.exec.asRight[String])(limits(_, d.exec))
      nesting <- int(o, "nestingLimit", d.check.nestingLimit)
      rounds <- int(o, "planRounds", d.planRounds)
      scripts <- o("scripts").fold(List.empty[String].asRight[String])(scriptList)
      denials <- o("explainDenials").fold(d.explain.explainDenials.asRight[String])(commands)
      _ <- Either.cond(nesting >= 1 && rounds >= 1, (), "nestingLimit und planRounds müssen mindestens 1 sein")
    yield RunnerConfig(
      m,
      e,
      d.check.copy(nestingLimit = nesting, argPolicies = d.check.argPolicies ++ scripts.map(ArgPolicy.script)),
      ExplainConfig(denials),
      rounds
    )

  /** provider bestimmt die Standards der übrigen Felder (D#47). */
  private def modelConfig(json: Json, fallback: ModelConfig): Either[String, ModelConfig] =
    for
      o <- obj(json, "model")
      _ <- only(o, "model", "provider", "model", "effort", "maxTokens", "contextTokens", "think", "endpoint")
      provider <- o("provider").fold(fallback.provider.asRight[String])(
        _.asString.flatMap(Provider.parse).toRight(s"provider: einer von ${Provider.values.mkString(", ")}")
      )
      d = ModelConfig.of(provider)
      name <- str(o, "model", d.model)
      effort <- str(o, "effort", d.effort)
      _ <- Either.cond(ModelConfig.efforts(effort), (), s"effort „$effort“ ist keiner von ${ModelConfig.efforts.toList.sorted.mkString(", ")}")
      maxTokens <- int(o, "maxTokens", d.maxTokens)
      contextTokens <- int(o, "contextTokens", d.contextTokens)
      think <- o("think").fold(d.think.asRight[String])(_.asBoolean.map(Some(_)).toRight("think: erwartet true oder false"))
      endpoint <- str(o, "endpoint", d.endpoint)
    yield d.copy(model = name, effort = effort, maxTokens = maxTokens, contextTokens = contextTokens, think = think, endpoint = endpoint)

  private def limits(json: Json, d: ExecConfig): Either[String, ExecConfig] =
    for
      o <- obj(json, "limits")
      _ <- only(o, "limits", "attemptsPerFill", "maxTotalSteps", "maxModelCalls", "maxDurationSeconds", "maxBytesPerStep", "shellTimeoutSeconds")
      attempts <- int(o, "attemptsPerFill", d.attemptsPerFill)
      steps <- int(o, "maxTotalSteps", d.maxTotalSteps)
      calls <- int(o, "maxModelCalls", d.maxModelCalls)
      duration <- int(o, "maxDurationSeconds", d.maxDuration.toSeconds.toInt)
      bytes <- int(o, "maxBytesPerStep", d.maxBytesPerStep)
      shell <- int(o, "shellTimeoutSeconds", d.shellTimeout.toSeconds.toInt)
      _ <- Either.cond(List(attempts, steps, calls, duration, bytes, shell).forall(_ >= 1), (), "Limits müssen mindestens 1 sein")
    yield d.copy(
      attemptsPerFill = attempts,
      maxTotalSteps = steps,
      maxModelCalls = calls,
      maxDuration = duration.seconds,
      maxBytesPerStep = bytes,
      shellTimeout = shell.seconds
    )

  /** Skripte nur als "./name" ohne weiteren Pfad: sie laufen ohne Argumente (ArgPolicy.script) und führen
    * Workspace-Code aus (D#12).
    */
  private def scriptList(json: Json): Either[String, List[String]] =
    json.asArray.toRight("scripts: erwartet eine Liste").flatMap(_.toList.traverse(j =>
      j.asString.filter(s => s.startsWith("./") && RelPath.parse(s.drop(2)).exists(_.size == 1))
        .toRight(s"scripts: „${j.noSpaces}“ ist kein Skript der Form ./name")
    ))

  private def commands(json: Json): Either[String, List[AllowedCommand]] =
    json.asArray.toRight("explainDenials: erwartet eine Liste").flatMap(
      _.toList.traverse(j => Codec.decodeEnvelope(Json.obj(
        "allowedOps" -> Json.arr(Json.fromString("Shell")),
        "pathPrefix" -> Json.fromString("."),
        "maxSteps" -> Json.fromInt(1),
        "maxDepth" -> Json.fromInt(0),
        "shellAllowlist" -> Json.arr(j)
      ), Location.root).leftMap(e => s"explainDenials: ${e.message}").map(_.shellAllowlist.head))
    )

  private def obj(json: Json, at: String): Either[String, JsonObject] = json.asObject.toRight(s"$at: erwartet ein Objekt")

  private def only(o: JsonObject, at: String, fields: String*): Either[String, Unit] =
    o.keys.find(k => !fields.contains(k)).map(k => s"$at: Feld „$k“ ist nicht vorgesehen").toLeft(())

  private def int(o: JsonObject, name: String, default: Int): Either[String, Int] =
    o(name).fold(default.asRight[String])(_.asNumber.flatMap(_.toInt).toRight(s"$name: erwartet eine ganze Zahl"))

  private def str(o: JsonObject, name: String, default: String): Either[String, String] =
    o(name).fold(default.asRight[String])(_.asString.toRight(s"$name: erwartet einen Text"))
