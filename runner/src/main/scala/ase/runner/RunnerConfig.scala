package ase.runner

import ase.core.*
import ase.model.{ModelConfig, Provider}
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.parser

import scala.concurrent.duration.*

/** Runner-Konfiguration (SPEC RUNNER): Modell, harte Limits, Verschachtelungsgrenze, Plan-Runden, explainDenials und
  * Skripte im Arbeitsverzeichnis (scripts, D#46). programs: weitere Programme mit Argument-Policy und den Pfaden,
  * deren Code sie ausführen (D#68). bestOfN: Pläne je Runde, über deren Form abgestimmt wird (BestOfN, D#63); 1 heißt
  * ohne Abstimmung. Jedes Feld ist optional (sonst der Standard), unbekannte Felder sind Fehler.
  */
final case class RunnerConfig(model: ModelConfig, exec: ExecConfig, check: CheckConfig, explain: ExplainConfig, planRounds: Int, bestOfN: Int = 1)

object RunnerConfig:
  val default: RunnerConfig = RunnerConfig(ModelConfig.default, ExecConfig.default, CheckConfig.default, ExplainConfig.default, 3)

  def parse(text: String): Either[String, RunnerConfig] =
    parser.parse(text).left.map(e => s"Kein gültiges JSON: ${e.message}").flatMap(decode)

  def decode(json: Json): Either[String, RunnerConfig] =
    val d = default
    for
      o <- obj(json, "Konfiguration")
      _ <- only(o, "Konfiguration", "model", "limits", "nestingLimit", "planRounds", "bestOfN", "explainDenials", "scripts", "programs")
      m <-o("model").fold(d.model.asRight[String])(modelConfig(_, d.model))
      e <- o("limits").fold(d.exec.asRight[String])(limits(_, d.exec))
      nesting <- int(o, "nestingLimit", d.check.nestingLimit)
      rounds <- int(o, "planRounds", d.planRounds)
      bestOf <- int(o, "bestOfN", d.bestOfN)
      scripts <- o("scripts").fold(List.empty[String].asRight[String])(scriptList)
      programs <- o("programs").fold(List.empty[Program].asRight[String])(programList(_, d.check.argPolicies))
      denials <- o("explainDenials").fold(d.explain.explainDenials.asRight[String])(commands)
      _ <- Either.cond(nesting >= 1 && rounds >= 1 && bestOf >= 1, (), "nestingLimit, planRounds und bestOfN müssen mindestens 1 sein")
    yield RunnerConfig(
      m,
      e,
      d.check.copy(
        nestingLimit = nesting,
        argPolicies = d.check.argPolicies ++ scripts.map(ArgPolicy.script) ++ programs.map(_.policy),
        workspaceCode = programs.foldLeft(d.check.workspaceCode)((m, p) =>
          if p.runsCode.isEmpty then m else m.updated(p.policy.binary, (m.getOrElse(p.policy.binary, Nil) ++ p.runsCode).distinct)
        )
      ),
      ExplainConfig(denials),
      rounds,
      bestOf
    )

  /** provider bestimmt die Standards der übrigen Felder (D#47). */
  private def modelConfig(json: Json, fallback: ModelConfig): Either[String, ModelConfig] =
    for
      o <- obj(json, "model")
      _ <- only(o, "model", "provider", "model", "effort", "maxTokens", "contextTokens", "think", "endpoint", "workDir")
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
      workDir <- str(o, "workDir", d.workDir)
    yield d.copy(model = name, effort = effort, maxTokens = maxTokens, contextTokens = contextTokens, think = think, endpoint = endpoint, workDir = workDir)

  private def limits(json: Json, d: ExecConfig): Either[String, ExecConfig] =
    for
      o <- obj(json, "limits")
      _ <- only(o, "limits", "attemptsPerFill", "roundsPerHole", "maxTotalSteps", "maxModelCalls", "maxDurationSeconds", "maxBytesPerStep", "shellTimeoutSeconds")
      attempts <- int(o, "attemptsPerFill", d.attemptsPerFill)
      rounds <- int(o, "roundsPerHole", d.maxRoundsPerHole)
      steps <- int(o, "maxTotalSteps", d.maxTotalSteps)
      calls <- int(o, "maxModelCalls", d.maxModelCalls)
      duration <- int(o, "maxDurationSeconds", d.maxDuration.toSeconds.toInt)
      bytes <- int(o, "maxBytesPerStep", d.maxBytesPerStep)
      shell <- int(o, "shellTimeoutSeconds", d.shellTimeout.toSeconds.toInt)
      _ <- Either.cond(List(attempts, rounds, steps, calls, duration, bytes, shell).forall(_ >= 1), (), "Limits müssen mindestens 1 sein")
    yield d.copy(
      attemptsPerFill = attempts,
      maxRoundsPerHole = rounds,
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
      // auch in Unterverzeichnissen (D#72); RelPath schließt .., .git und absolute Pfade aus
      j.asString.filter(s => s.startsWith("./") && RelPath.parse(s.drop(2)).exists(_.nonEmpty))
        .toRight(s"scripts: „${j.noSpaces}“ ist kein Skript der Form ./pfad")
    ))

  /** Ein Programm aus programs (D#68): seine Argument-Policy und die Pfade, deren Code es ausführt (leer: keinen). */
  private final case class Program(policy: ArgPolicy, runsCode: List[String])

  private val Name = "^[A-Za-z0-9][A-Za-z0-9._+-]*$".r
  private val Flag = "^--?[A-Za-z0-9][A-Za-z0-9-]*$".r
  private val PrefixFlag = "^--?[A-Za-z0-9][A-Za-z0-9-]*=$".r

  /** Programme nur als Name, den LocalTools im bereinigten PATH sucht; Flags nur als einzelne Flags. Ein Programm mit
    * denselben Pflicht-Argumenten wie ein vorhandenes ist ein Fehler, damit keine Policy still eine andere ersetzt.
    */
  private def programList(json: Json, existing: List[ArgPolicy]): Either[String, List[Program]] =
    json.asArray.toRight("programs: erwartet eine Liste").flatMap(_.toList.traverse(program)).flatMap { ps =>
      val keys = existing.map(p => (p.binary, p.prefixArgs)) ++ ps.map(p => (p.policy.binary, p.policy.prefixArgs))
      keys.diff(keys.distinct).headOption
        .map((b, args) => s"programs: „${(b :: args).mkString(" ")}“ hat schon eine Argument-Policy")
        .toLeft(ps)
    }

  private def program(json: Json): Either[String, Program] =
    for
      o <- obj(json, "programs")
      _ <- only(o, "programs", "binary", "args", "flags", "valuedFlags", "prefixFlags", "positional", "runsCode")
      binary <- o("binary").flatMap(_.asString).filter(Name.matches).toRight("programs: binary muss ein Programmname ohne Pfad sein")
      at = s"programs: $binary"
      args <- strings(o, "args", at)
      _ <- args.find(a => !Name.matches(a)).map(a => s"$at: args „$a“ ist kein Unterbefehl").toLeft(())
      flags <- strings(o, "flags", at)
      valued <- strings(o, "valuedFlags", at)
      prefix <- strings(o, "prefixFlags", at)
      _ <- (flags ++ valued).find(f => !Flag.matches(f)).map(f => s"$at: „$f“ ist kein einzelnes Flag").toLeft(())
      _ <- prefix.find(f => !PrefixFlag.matches(f)).map(f => s"$at: „$f“ ist kein Präfix-Flag der Form --name=").toLeft(())
      positional <- o("positional").fold(Positional.Paths.asRight[String])(j =>
        j.asString.flatMap(s => Positional.values.find(_.toString == s))
          .toRight(s"$at: positional ist einer von ${Positional.values.mkString(", ")}")
      )
      runsCode <- strings(o, "runsCode", at)
      _ <- runsCode.find(p => RelPath.parse(p).isLeft).map(p => s"$at: runsCode „$p“ ist kein Pfad im Arbeitsverzeichnis").toLeft(())
    yield Program(ArgPolicy(binary, args, flags.toSet, valued.toSet, prefix.toSet, positional), runsCode)

  private def strings(o: JsonObject, name: String, at: String): Either[String, List[String]] =
    o(name).fold(List.empty[String].asRight[String])(
      _.asArray.flatMap(_.toList.traverse(_.asString)).toRight(s"$at: $name erwartet eine Liste von Texten")
    )

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
