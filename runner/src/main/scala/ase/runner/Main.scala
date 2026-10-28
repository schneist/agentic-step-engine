package ase.runner

import ase.core.*
import ase.model.*
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.{Json, parser}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Befehle des CLI, zweiphasig: `plan` prüft und legt den Term ab, `run` prüft neu, gibt frei und führt aus. */
enum Command:
  case Plan(envelope: Path, task: Option[Path], flow: Option[Path], values: Map[String, String], out: Path, config: Option[Path])
  case Run(
      plan: Path,
      envelope: Path,
      workspace: Path,
      approve: Option[String],
      record: Option[Path],
      summary: Option[Path],
      noAsk: Boolean,
      config: Option[Path]
  )

object Cli:
  val usage: String =
    """ase plan --envelope E (--task T | --flow F [name=wert …]) --out P [--config C]
      |ase run  --plan P --envelope E --workspace W [--approve HASH] [--record R] [--summary S] [--no-ask] [--config C]
      |
      |Exit-Codes von run: 0 done, 2 ask, 3 aborted, 4 nicht freigegeben, 1 Fehler. plan: 0 geprüft, 3 abgelehnt, 1 Fehler.""".stripMargin

  def parse(args: List[String]): Either[String, Command] =
    def opts(rest: List[String], acc: Map[String, String], flags: Set[String], free: List[String]): Either[String, (Map[String, String], Set[String], List[String])] =
      rest match
        case Nil                                          => Right((acc, flags, free.reverse))
        case "--no-ask" :: tail                           => opts(tail, acc, flags + "no-ask", free)
        case k :: v :: tail if k.startsWith("--") && !v.startsWith("--") => opts(tail, acc + (k.drop(2) -> v), flags, free)
        case k :: _ if k.startsWith("--")                 => Left(s"$k braucht einen Wert")
        case a :: tail                                    => opts(tail, acc, flags, a :: free)
    def need(o: Map[String, String], k: String): Either[String, Path] = o.get(k).map(Path.of(_)).toRight(s"--$k fehlt")
    def known(o: Map[String, String], ks: String*): Either[String, Unit] =
      o.keys.find(k => !ks.contains(k)).map(k => s"unbekannte Option --$k").toLeft(())

    args match
      case "plan" :: rest =>
        for
          (o, _, free) <- opts(rest, Map.empty, Set.empty, Nil)
          _ <- known(o, "envelope", "task", "flow", "out", "config")
          envelope <- need(o, "envelope")
          out <- need(o, "out")
          _ <- Either.cond(o.contains("task") != o.contains("flow"), (), "genau eines von --task und --flow")
          values <- free.traverse(a =>
            a.split("=", 2) match
              case Array(k, v) if o.contains("flow") => Right(k -> v)
              case _                                 => Left(s"unerwartetes Argument „$a“")
          )
        yield Command.Plan(envelope, o.get("task").map(Path.of(_)), o.get("flow").map(Path.of(_)), values.toMap, out, o.get("config").map(Path.of(_)))
      case "run" :: rest =>
        for
          (o, flags, free) <- opts(rest, Map.empty, Set.empty, Nil)
          _ <- known(o, "plan", "envelope", "workspace", "approve", "record", "summary", "config")
          _ <- free.headOption.map(a => s"unerwartetes Argument „$a“").toLeft(())
          plan <- need(o, "plan")
          envelope <- need(o, "envelope")
          workspace <- need(o, "workspace")
        yield Command.Run(
          plan,
          envelope,
          workspace,
          o.get("approve"),
          o.get("record").map(Path.of(_)),
          o.get("summary").map(Path.of(_)),
          flags("no-ask"),
          o.get("config").map(Path.of(_))
        )
      case _ => Left(usage)

object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    Cli.parse(args) match
      case Left(message) => IO.println(message).as(ExitCode(1))
      case Right(cmd) =>
        val go = cmd match
          case p: Command.Plan => plan(p)
          case r: Command.Run  => execute(r)
        go.handleErrorWith {
          case ModelUnavailable(e) => IO.println(s"Fehler: ${e.message}").as(ExitCode(1))
          case Failure(message)    => IO.println(s"Fehler: $message").as(ExitCode(1))
        }

  /** Ein Fehler des Runners, nicht des Modells: Eingaben, Dateien, Konfiguration. */
  final case class Failure(message: String) extends RuntimeException(message)

  private def read(p: Path): IO[String] =
    IO.blocking(Files.readString(p, UTF_8)).adaptError {
      case _: java.nio.file.NoSuchFileException => Failure(s"$p: Datei nicht gefunden")
      case e: java.io.IOException             => Failure(s"$p: $e")
    }

  private def orFail[A](e: Either[String, A]): IO[A] = IO.fromEither(e.leftMap(Failure(_)))

  private def loadConfig(path: Option[Path]): IO[(RunnerConfig, String)] =
    path.traverse(read).flatMap(text => orFail(text.fold(Right(RunnerConfig.default))(RunnerConfig.parse)).map(_ -> PlanFile.configSha(text)))

  private def loadEnvelope(path: Path): IO[Envelope] =
    read(path).flatMap(t => orFail(parser.parse(t).leftMap(_.message).flatMap(Codec.decodeEnvelope(_, Location.root).leftMap(_.message))))

  private def plan(cmd: Command.Plan): IO[ExitCode] =
    for
      (config, sha) <- loadConfig(cmd.config)
      root <- loadEnvelope(cmd.envelope)
      result <- (cmd.task, cmd.flow) match
        case (Some(task), _) => planTask(task, root, config)
        case (_, Some(flow)) => planFlow(flow, cmd.values, root, config).map(Some(_))
        case _               => IO.raiseError(Failure(Cli.usage))
      code <- result match
        case None => IO.pure(ExitCode(3))
        case Some((checked, planned, source)) =>
          val file = PlanFile(source, sha, checked.rootEnvelope, checked.params, checked.term, planned.calls, planned.rejections, planned.unusable, planned.tokens)
          IO.blocking(Files.writeString(cmd.out, PlanFile.encode(file).spaces2 + "\n", UTF_8)) >>
            IO.println(Explain.render(checked, config.explain)).as(ExitCode.Success)
    yield code

  private def planTask(task: Path, root: Envelope, config: RunnerConfig): IO[Option[(CheckedTerm, Planned, String)]] =
    for
      text <- read(task)
      transport <- JdkTransport.open[IO](config.model).flatMap(e => IO.fromEither(e.leftMap(ModelUnavailable(_))))
      model = ModelAdapter[IO](transport, config.model, config.check)
      planned <- Planner.run[IO](model, text, root, config.check, config.planRounds)
      _ <- IO.println(planned.log.mkString("\n"))
      _ <- IO.println(s"plan      ${planned.calls} Modellaufrufe · ${planned.rejections} Ablehnungen an Tor 1 · ${planned.tokens} Tokens")
    yield planned.checked.map(c => (c, planned, task.toString))

  private def planFlow(flow: Path, values: Map[String, String], root: Envelope, config: RunnerConfig): IO[(CheckedTerm, Planned, String)] =
    for
      text <- read(flow)
      bound <- orFail(Flow.parse(text, config.check.nestingLimit).flatMap(Flow.bind(_, values, root)).leftMap(_.toString))
      checked <- orFail(DryRun.plan(bound.term, bound.envelope, config.check, values).leftMap(_.toList.map(_.render).mkString("\n")))
    yield (checked, Planned(Some(checked), 0, 0, 0, 0L, Vector.empty), flow.toString)

  private def execute(cmd: Command.Run): IO[ExitCode] =
    for
      (config, sha) <- loadConfig(cmd.config)
      root <- loadEnvelope(cmd.envelope)
      file <- read(cmd.plan).flatMap(t => orFail(PlanFile.parse(t, config.check.nestingLimit)))
      _ <- orFail(Either.cond(file.configSha == sha, (), "Plan und Lauf haben verschiedene Runner-Konfigurationen (D#40)"))
      // die Hülle der Plan-Datei darf die des Runners nur abschwächen (D#22)
      _ <- orFail(Attenuation.check(file.envelope, root, Location.root / "envelope", Attenuation.Mode.Flow) match
        case Nil => Right(())
        case vs  => Left(vs.map(_.render).mkString("\n")))
      checked <- orFail(DryRun.plan(file.term, file.envelope, config.check, file.params).leftMap(_.toList.map(_.render).mkString("\n")))
      explain = Explain.render(checked, config.explain)
      _ <- IO.println(explain)
      approved <- approve(checked, cmd.approve)
      code <- approved match
        case None => IO.println("nicht freigegeben").as(ExitCode(4))
        case Some(a) => runApproved(a, file, cmd, config)
    yield code

  /** Freigabe (D#11, D#38): mit --approve der übergebene Hash, sonst fragt der Runner und bestätigt den gezeigten. */
  private def approve(checked: CheckedTerm, confirmed: Option[String]): IO[Option[ApprovedTerm]] =
    confirmed match
      case Some(hex) =>
        orFail(TermHash.fromHex(hex).toRight(s"--approve: „$hex“ ist kein Hash aus 64 Hex-Zeichen")).flatMap { h =>
          Approval.approve(checked, h) match
            case Right(a) => IO.pure(Some(a))
            case Left(ApprovalError.HashMismatch(expected, got)) =>
              IO.println(s"Freigabe gescheitert: bestätigt ${got.short}, geprüft ist ${expected.short}").as(None)
        }
      case None =>
        IO.print("\nFreigeben? [y/N] ") >> IO.readLine.map(_.trim.toLowerCase).map {
          case "y" | "j" => Approval.approve(checked, checked.termHash).toOption
          case _         => None
        }

  private def runApproved(approved: ApprovedTerm, file: PlanFile, cmd: Command.Run, config: RunnerConfig): IO[ExitCode] =
    val record = cmd.record.getOrElse(Path.of("record", LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")) + ".log"))
    for
      transport <- JdkTransport.open[IO](config.model)
      // ohne Schlüssel läuft ein Term ohne Löcher trotzdem; ein Fill endet dann mit dem Fehler
      model = transport.fold[FillModel[IO]](e => _ => IO.raiseError(ModelUnavailable(e)), t => ModelAdapter[IO](t, config.model, config.check))
      ask = if cmd.noAsk then AskHandler.nonInteractive[IO] else interactiveAsk
      _ <- IO.println(s"approved  ${approved.termHash.short}  → $record\n")
      result <- Execute.local[IO](approved, cmd.workspace, model, ask, config.exec, Some(record)).flatMap(e => orFail(e.leftMap(_.message)))
      _ <- IO.println(RunLog.render(result.events, RunLog.Footer(file.rejections, Some(record.toString), file.calls, file.tokens)))
      _ <- cmd.summary.traverse_(p => IO.blocking(Files.writeString(p, Summary.of(file, approved, result).spaces2 + "\n", UTF_8)))
    yield result.outcome match
      case Outcome.Done       => ExitCode.Success
      case Outcome.Ask(_)     => ExitCode(2)
      case Outcome.Aborted(_) => ExitCode(3)

  private val interactiveAsk: AskHandler[IO] = q =>
    IO.print(s"\nask  $q\n> ") >> IO.readLine.map(a => Option(a).map(_.trim).filter(_.nonEmpty))

/** Kennzahlen eines Laufs für Serien (playbook/evals.md): Plan und Ausführung zusammen. */
object Summary:
  def of(file: PlanFile, approved: ApprovedTerm, result: RunResult): Json =
    val s = result.stats
    Json.obj(
      "outcome" -> Json.fromString(result.outcome.label),
      "termHash" -> Json.fromString(approved.termHash.hex),
      "planFingerprint" -> Json.fromString(Fingerprint.of(file.term).hex),
      "steps" -> Json.fromLong(s.steps),
      "planCalls" -> Json.fromInt(file.calls),
      "fillCalls" -> Json.fromInt(s.modelCalls),
      "gate1Rejections" -> Json.fromInt(file.rejections),
      "gate2Rejections" -> Json.fromInt(s.gate2Rejections),
      "unusableAnswers" -> Json.fromInt(file.unusable + s.unusableAnswers),
      "runtimeFailures" -> Json.fromInt(s.runtimeFailures),
      "asked" -> Json.fromBoolean(result.events.exists(_.isInstanceOf[Event.Asked])),
      "tokens" -> Json.fromLong(file.tokens + s.tokens),
      "changedFiles" -> Json.arr(s.changedFiles.map(Json.fromString)*),
      "fillFingerprints" -> Json.arr(result.events.collect { case e: Event.FillAccepted => Json.fromString(Fingerprint.of(e.term).hex) }*)
    )
