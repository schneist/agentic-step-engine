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
  val default: RunnerConfig =
    RunnerConfig(ModelConfig.default, ExecConfig.default, CheckConfig.default, ExplainConfig.default.copy(sandbox = Some(ExecConfig.default.sandbox)), 3)

  def parse(text: String): Either[String, RunnerConfig] =
    parser.parse(text).left.map(e => s"Kein gültiges JSON: ${e.message}").flatMap(decode)

  def decode(json: Json): Either[String, RunnerConfig] =
    val d = default
    for
      o <- obj(json, "Konfiguration")
      _ <- only(o, "Konfiguration", "model", "limits", "nestingLimit", "planRounds", "bestOfN", "explainDenials", "scripts", "programs", "sandbox")
      m <-o("model").fold(d.model.asRight[String])(modelConfig(_, d.model))
      e <- o("limits").fold(d.exec.asRight[String])(limits(_, d.exec))
      nesting <- int(o, "nestingLimit", d.check.nestingLimit)
      rounds <- int(o, "planRounds", d.planRounds)
      bestOf <- int(o, "bestOfN", d.bestOfN)
      scripts <- o("scripts").fold(List.empty[String].asRight[String])(scriptList)
      programs <- o("programs").fold(List.empty[Program].asRight[String])(programList(_, d.check.argPolicies))
      policies = d.check.argPolicies ++ scripts.map(ArgPolicy.script) ++ programs.map(_.policy)
      required <- requiredOf(programs, policies)
      denials <- o("explainDenials").fold(d.explain.explainDenials.asRight[String])(commands)
      _ <- Either.cond(nesting >= 1 && rounds >= 1 && bestOf >= 1, (), "nestingLimit, planRounds und bestOfN müssen mindestens 1 sein")
      check = d.check.copy(
        nestingLimit = nesting,
        argPolicies = policies,
        workspaceCode = programs.foldLeft(d.check.workspaceCode)((m, p) =>
          if p.runsCode.isEmpty then m else m.updated(p.policy.binary, (m.getOrElse(p.policy.binary, Nil) ++ p.runsCode).distinct)
        ),
        required = required
      )
      sandbox <- o("sandbox").fold(ExecConfig.default.sandbox.asRight[String])(sandboxOf(_, check))
    yield RunnerConfig(
      m,
      e.copy(sandbox = sandbox),
      check,
      // dieselbe Sandbox für Explain und Execute (D#88)
      ExplainConfig(denials, Some(sandbox)),
      rounds,
      bestOf
    )

  /** Sandbox (D#88): false schaltet sie bewusst ab; sonst {"programs": {"aws": {"net": true, "mounts": [{"path":
    * "~/.aws"}, {"path": "../.cloud-stub", "write": true}]}}}. Zusätze nur für Programme mit Namen, nie für Skripte;
    * ein Programm, das Workspace-Code ausführt (D#12), bekommt Einhängungen, aber kein Netz: sein Code ist ungeprüft.
    */
  private def sandboxOf(json: Json, check: CheckConfig): Either[String, Sandbox] =
    json.asBoolean match
      case Some(false) => Right(Sandbox.Off)
      case Some(true)  => Right(Sandbox.default)
      case None =>
        for
          o <- obj(json, "sandbox")
          _ <- only(o, "sandbox", "programs")
          ps <- o("programs").fold(JsonObject.empty.asRight[String])(obj(_, "sandbox: programs"))
          extras <- ps.toList.traverse { (cmd, j) =>
            val at = s"sandbox: $cmd"
            for
              _ <- Either.cond(Name.matches(cmd), (), s"$at: nur ein Programmname, kein Skript und kein Pfad")
              e <- obj(j, at)
              _ <- only(e, at, "net", "mounts")
              net <- e("net").fold(false.asRight[String])(_.asBoolean.toRight(s"$at: net erwartet true oder false"))
              _ <- Either.cond(!net || check.runsWorkspaceCode(cmd).isEmpty, (), s"$at: führt Workspace-Code aus und bekommt darum kein Netz")
              ms <- e("mounts").fold(List.empty[Json].asRight[String])(_.asArray.map(_.toList).toRight(s"$at: mounts erwartet eine Liste"))
              mounts <- ms.traverse { mj =>
                for
                  m <- obj(mj, s"$at: mounts")
                  _ <- only(m, s"$at: mounts", "path", "write")
                  path <- m("path").flatMap(_.asString).filter(_.nonEmpty).toRight(s"$at: mounts braucht path")
                  _ <- Either.cond(!path.startsWith("~") || path == "~" || path.startsWith("~/"), (), s"$at: „$path“: nur ~ oder ~/…")
                  write <- m("write").fold(false.asRight[String])(_.asBoolean.toRight(s"$at: write erwartet true oder false"))
                yield Mount(path, write)
              }
            yield cmd -> Extra(net, mounts)
          }
        yield Sandbox.On(extras.toMap)

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

  /** Ein Programm aus programs (D#68): seine Argument-Policy, die Pfade, deren Code es ausführt (leer: keinen), und
    * seine Pflicht-Flags (D#87).
    */
  private final case class Program(policy: ArgPolicy, runsCode: List[String], required: Set[String])

  /** Pflicht-Flags je Programm (D#87). Sie gehören dem Programm, nicht der einzelnen Policy: Jede Policy desselben
    * Programms, auch eine eingebaute, muss dieselben nennen, sonst ließe die ohne Pflicht jeden Aufruf durch
    * (ArgPolicy.find nimmt das längste Präfix).
    */
  private def requiredOf(programs: List[Program], policies: List[ArgPolicy]): Either[String, Map[String, Set[String]]] =
    val declared = programs.groupBy(_.policy.binary)
    declared.toList.sortBy(_._1).traverse { (binary, ps) =>
      val sets = ps.map(_.required).distinct
      val builtIn = policies.count(_.binary == binary) > ps.size
      sets match
        case List(r) if r.isEmpty => Right(binary -> r)
        case List(r) if builtIn =>
          Left(s"programs: $binary hat eingebaute Argument-Policies ohne required; Pflicht-Flags müssen für jede Policy des Programms gelten")
        case List(r) => Right(binary -> r)
        case _ =>
          Left(s"programs: $binary nennt in verschiedenen Einträgen verschiedene required (${sets.map(_.toList.sorted.mkString("[", ", ", "]")).mkString(" gegen ")}); Pflicht-Flags gehören dem Programm")
    }.map(_.filter(_._2.nonEmpty).toMap)

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
      _ <- only(o, "programs", "binary", "args", "flags", "valuedFlags", "prefixFlags", "positional", "runsCode", "values", "required")
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
      values <- o("values").fold(Map.empty[String, ValueRule].asRight[String])(valueRules(_, at, valued.toSet))
      // --profile= neben --profile umginge „genau einmal“ und die Wertregel (D#87)
      _ <- prefix.find(p => valued.contains(p.dropRight(1))).map(p => s"$at: Präfix-Flag „$p“ hat denselben Namen wie ein Flag mit Wert").toLeft(())
      required <- strings(o, "required", at)
      _ <- required.find(f => !valued.contains(f)).map(f => s"$at: required „$f“ ist kein Flag aus valuedFlags").toLeft(())
      // ein Pfad in jedem Aufruf änderte Sicht und Explain jedes Aufrufs (D#87)
      _ <- required.find(f => values.get(f).exists(_.isInstanceOf[ValueRule.Path])).map(f => s"$at: required „$f“ hat eine Wertregel Path; Pflicht-Flags sind kein Pfad").toLeft(())
    yield Program(ArgPolicy(binary, args, flags.toSet, valued.toSet, prefix.toSet, positional, values), runsCode, required.toSet)

  /** Wertregeln je Flag mit Wert (D#83): {"--body": {"type": "Path"}, "--user-data": {"type": "Path", "prefix": "file://"},
    * "--query": {"type": "Pattern", "regex": "^…$"}}. Nur für Flags aus valuedFlags; ein Muster muss sich übersetzen lassen.
    */
  private def valueRules(json: Json, at: String, valued: Set[String]): Either[String, Map[String, ValueRule]] =
    obj(json, s"$at: values").flatMap(_.toList.traverse { (flag, j) =>
      val here = s"$at: values „$flag“"
      for
        _ <- Either.cond(valued(flag), (), s"$here ist kein Flag aus valuedFlags")
        r <- obj(j, here)
        rule <- r("type").flatMap(_.asString) match
          case Some("Path") =>
            for
              _ <- only(r, here, "type", "prefix")
              prefix <- str(r, "prefix", "").leftMap(e => s"$here: $e")
            yield ValueRule.Path(prefix)
          case Some("Pattern") =>
            for
              _ <- only(r, here, "type", "regex")
              regex <- r("regex").flatMap(_.asString).toRight(s"$here: regex fehlt")
              _ <- Either.catchOnly[java.util.regex.PatternSyntaxException](java.util.regex.Pattern.compile(regex))
                .leftMap(e => s"$here: Muster ungültig: ${e.getDescription}")
            yield ValueRule.Pattern(regex)
          case _ => Left(s"$here: type ist Path oder Pattern")
      yield flag -> rule
    }).map(_.toMap)

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
