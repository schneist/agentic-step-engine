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
import scala.jdk.CollectionConverters.*

/** Befehle des CLI, zweiphasig: `plan` prüft und legt den Term ab, `run` prüft neu, gibt frei und führt aus. */
enum Command:
  case Plan(
      envelope: Path,
      task: Option[Path],
      flow: Option[Path],
      values: Map[String, String],
      out: Path,
      config: Option[Path],
      summary: Option[Path] = None
  )
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
  case Stats(paths: List[Path])
  case Tighten(flow: Path, out: Path, runs: List[Path], minCalls: Int, config: Option[Path])

object Cli:
  val usage: String =
    """ase plan --envelope E (--task T | --flow F [name=wert …]) --out P [--summary S] [--config C]
      |ase run  --plan P --envelope E --workspace W [--approve HASH] [--record R] [--summary S] [--no-ask] [--config C]
      |ase stats PFAD …
      |ase tighten --flow F --out NEU [--min-calls N] [--config C] PFAD …
      |
      |Exit-Codes von run: 0 done, 2 ask, 3 aborted, 4 nicht freigegeben, 1 Fehler. plan: 0 geprüft, 2 keine Mehrheit (bestOfN), 3 abgelehnt, 1 Fehler.""".stripMargin

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
          _ <- known(o, "envelope", "task", "flow", "out", "config", "summary")
          envelope <- need(o, "envelope")
          out <- need(o, "out")
          _ <- Either.cond(o.contains("task") != o.contains("flow"), (), "genau eines von --task und --flow")
          values <- free.traverse(a =>
            a.split("=", 2) match
              case Array(k, v) if o.contains("flow") => Right(k -> v)
              case _                                 => Left(s"unerwartetes Argument „$a“")
          )
        yield Command.Plan(
          envelope,
          o.get("task").map(Path.of(_)),
          o.get("flow").map(Path.of(_)),
          values.toMap,
          out,
          o.get("config").map(Path.of(_)),
          o.get("summary").map(Path.of(_))
        )
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
      case "stats" :: rest =>
        rest match
          case Nil                                     => Left("ase stats braucht mindestens einen Pfad")
          case _ if rest.exists(_.startsWith("--"))    => Left(s"unbekannte Option ${rest.find(_.startsWith("--")).get}")
          case paths                                   => Right(Command.Stats(paths.map(Path.of(_))))
      case "tighten" :: rest =>
        for
          (o, flags, free) <- opts(rest, Map.empty, Set.empty, Nil)
          _ <- known(o, "flow", "out", "min-calls", "config")
          _ <- flags.headOption.map(f => s"unbekannte Option --$f").toLeft(())
          flow <- need(o, "flow")
          out <- need(o, "out")
          min <- o.get("min-calls").fold(Right(3))(v => v.toIntOption.filter(_ >= 1).toRight(s"--min-calls: „$v“ ist keine Zahl ab 1"))
          _ <- Either.cond(free.nonEmpty, (), "ase tighten braucht mindestens einen Pfad mit Läufen")
        yield Command.Tighten(flow, out, free.map(Path.of(_)), min, o.get("config").map(Path.of(_)))
      case _ => Left(usage)

object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    Cli.parse(args) match
      case Left(message) => IO.println(message).as(ExitCode(1))
      case Right(cmd) =>
        val go = cmd match
          case p: Command.Plan => plan(p)
          case r: Command.Run  => execute(r)
          case s: Command.Stats => stats(s)
          case t: Command.Tighten => tighten(t)
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

  /** Exit-Codes: 0 geprüft, 2 keine Mehrheit unter den Kandidaten (BestOfN, D#63), 3 von Tor 1 abgelehnt. Die
    * Kennzahlen des Plans gehen mit --summary in jedem dieser Fälle in eine Datei (O#23).
    */
  private def plan(cmd: Command.Plan): IO[ExitCode] =
    for
      (config, sha) <- loadConfig(cmd.config)
      root <- loadEnvelope(cmd.envelope)
      (planned, source, text) <- (cmd.task, cmd.flow) match
        case (Some(task), _) => read(task).flatMap(t => planTask(t, root, config).map((_, task.toString, t)))
        case (_, Some(flow)) => read(flow).flatMap(t => planFlow(t, cmd.values, root, config).map((_, flow.toString, t)))
        case _               => IO.raiseError(Failure(Cli.usage))
      _ <- cmd.summary.traverse_(p => IO.blocking(Files.writeString(p, PlanSummary.of(planned).spaces2 + "\n", UTF_8)))
      code <- planned.checked match
        case Some(checked) =>
          val (candidates, agreeing) = planned.vote match
            case Some(BestOfN.Vote.Majority(_, agreeing, of)) => (of, agreeing)
            case _                                            => (1, 1)
          val file = PlanFile(source, sha, checked.rootEnvelope, checked.params, checked.term, planned.calls, planned.rejections, planned.unusable, planned.tokens, candidates, agreeing, Some(Canonical.sha256Hex(text)))
          // fingerprint: Struktur ohne origin (D#19), für den Vergleich mit einem Referenzflow (playbook/evals.md)
          IO.blocking(Files.writeString(cmd.out, PlanFile.encode(file).spaces2 + "\n", UTF_8)) >>
            IO.println(Explain.render(checked, config.explain)) >>
            IO.println(s"fingerprint ${Fingerprint.of(checked.term).hex}").as(ExitCode.Success)
        case None if planned.split =>
          IO.println("Keine Mehrheit unter den Plänen; nichts abgelegt. Aufgabe schärfen oder erneut planen.").as(ExitCode(2))
        case None => IO.pure(ExitCode(3))
    yield code

  private def planTask(text: String, root: Envelope, config: RunnerConfig): IO[Planned] =
    for
      transport <- JdkTransport.open[IO](config.model).flatMap(e => IO.fromEither(e.leftMap(ModelUnavailable(_))))
      model = ModelAdapter[IO](transport, config.model, config.check)
      planned <- Planner.run[IO](model, text, root, config.check, config.planRounds, config.bestOfN)
      _ <- IO.println(planned.log.mkString("\n"))
      _ <- IO.println(s"plan      ${planned.calls} Modellaufrufe · ${planned.rejections} Ablehnungen an Tor 1 · ${planned.tokens} Tokens")
    yield planned

  /** Ein Flow braucht kein Modell. Ein Verstoß an Tor 1 ist eine Ablehnung (Exit 3); Fehler beim Binden der Parameter
    * sind Eingabefehler (Exit 1).
    */
  private def planFlow(text: String, values: Map[String, String], root: Envelope, config: RunnerConfig): IO[Planned] =
    for
      bound <- orFail(Flow.parse(text, config.check.nestingLimit).flatMap(Flow.bind(_, values, root)).leftMap(_.toString))
      planned <- DryRun.plan(bound.term, bound.envelope, config.check, values) match
        case Right(checked) => IO.pure(Planned(Some(checked), 0, 0, 0, 0L, Vector.empty))
        case Left(vs) =>
          val reasons = vs.toList.map(_.render)
          val lines = s"flow     dryrun  rejected  ${reasons.head}" :: reasons.tail.map(" " * 26 + _)
          IO.println(lines.mkString("\n")).as(Planned(None, 0, 1, 0, 0L, Vector.empty))
    yield planned

  /** `ase stats` (D#74). Exit 1, wenn kein Lauf gefunden wurde. */
  private def stats(cmd: Command.Stats): IO[ExitCode] =
    loadRuns(cmd.paths).flatMap { runs =>
      if runs.isEmpty then IO.println("Keine Läufe gefunden (summary*.json mit outcome).").as(ExitCode(1))
      else IO.println(RunStats.render(RunStats.group(runs))).as(ExitCode.Success)
    }

  /** `ase tighten` (D#75): schreibt einen Flow mit engeren Hüllen nach --out und zeigt alt gegen neu; wird nichts
    * enger, schreibt es nichts. Gibt nichts frei; der neue Flow geht durch `ase plan`. Exit 1 ohne passende Läufe oder
    * wenn die Probe scheitert. Schreibt nie über den Flow selbst.
    */
  private def tighten(cmd: Command.Tighten): IO[ExitCode] =
    for
      (config, _) <- loadConfig(cmd.config)
      // auch über Groß/Klein (Windows) und Links hinweg derselbe Flow
      same <- IO.blocking(cmd.out.toAbsolutePath.normalize == cmd.flow.toAbsolutePath.normalize || Files.exists(cmd.out) && Files.isSameFile(cmd.out, cmd.flow))
      _ <- orFail(Either.cond(!same, (), "--out darf nicht der Flow selbst sein"))
      text <- read(cmd.flow)
      flow <- orFail(Flow.parse(text, config.check.nestingLimit).left.map(_.toString))
      runs <- loadRuns(cmd.runs)
      code <- FlowTighten.run(flow, Canonical.sha256Hex(text), runs, cmd.minCalls, config.check) match
        case Left(message) => IO.println(message).as(ExitCode(1))
        case Right(result) if result.proposals.forall(_.change.isEmpty) =>
          IO.println(FlowTighten.render(flow, result)) >> IO.println("nichts enger; nichts geschrieben").as(ExitCode.Success)
        case Right(result) =>
          IO.blocking(Files.writeString(cmd.out, Flow.encode(result.flow).spaces2 + "\n", UTF_8)) >>
            IO.println(FlowTighten.render(flow, result)) >>
            IO.println(s"geschrieben ${cmd.out}; weiter mit ase plan --flow ${cmd.out} … und dem Explain").as(ExitCode.Success)
    yield code

  /** Jede summary*.json unter den Pfaden mit Ausgang eines Laufs; Kennzahlen eines Plans ohne Lauf (planOutcome)
    * zählen nicht. Das Record-Log liegt neben der summary: unter dem Namen aus dem Feld record, sonst das einzige
    * record*.log dort; erst dann der Pfad aus record selbst (ein kopiertes Laufverzeichnis liest sein eigenes Log).
    */
  private def loadRuns(paths: List[Path]): IO[Vector[RunStats.Run]] =
    def files(p: Path): IO[List[Path]] = IO.blocking {
      if Files.isRegularFile(p) then List(p)
      else if Files.isDirectory(p) then
        val s = Files.walk(p)
        try s.iterator.asScala.filter(f => Files.isRegularFile(f) && f.getFileName.toString.matches("summary.*\\.json")).toList.sorted
        finally s.close()
      else throw Failure(s"$p: Datei nicht gefunden")
    }
    def recordOf(summary: Path, json: Json): IO[Option[Path]] = IO.blocking {
      val dir = Option(summary.toAbsolutePath.getParent).getOrElse(Path.of("."))
      val named = json.hcursor.get[String]("record").toOption.map(Path.of(_))
      val beside = named.flatMap(n => Option(n.getFileName)).map(dir.resolve).filter(Files.isRegularFile(_))
      def single: Option[Path] =
        val s = Files.list(dir)
        try s.iterator.asScala.filter(f => f.getFileName.toString.matches("record.*\\.log")).toList match
          case one :: Nil => Some(one)
          case _          => None
        finally s.close()
      beside.orElse(single).orElse(named.filter(Files.isRegularFile(_)))
    }
    def load(summary: Path): IO[Option[RunStats.Run]] =
      read(summary).flatMap { text =>
        parser.parse(text).toOption.filter(_.hcursor.downField("outcome").succeeded) match
          case None => IO.pure(None)
          case Some(json) =>
            (recordOf(summary, json).flatMap(_.traverse(read)), IO.blocking(Files.getLastModifiedTime(summary).toMillis)).mapN { (log, modified) =>
              val events = log.flatMap(RecordLog.parse(_).toOption)
              Some(RunStats.Run(Option(summary.toAbsolutePath.getParent).fold(".")(_.toString), json, events, modified))
            }
      }
    paths.flatTraverse(files).flatMap(found => found.distinct.traverse(load).map(_.flatten.toVector))

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
      // Ausgangsstand für ase tighten (D#78); nur mit --summary, und ein Fehler beim Lesen lässt das Feld weg
      start <- cmd.summary.flatTraverse(_ => TreeHash.collect(cmd.workspace).attempt.map(_.toOption))
      started <- IO.monotonic
      result <- Execute.local[IO](approved, cmd.workspace, model, ask, config.exec, Some(record)).flatMap(e => orFail(e.leftMap(_.message)))
      ended <- IO.monotonic
      _ <- IO.println(RunLog.render(result.events, RunLog.Footer(file.rejections, Some(record.toString), file.calls, file.tokens)))
      seconds = (ended - started).toMillis / 1000.0
      _ <- cmd.summary.traverse_(p =>
        IO.blocking(Files.writeString(p, Summary.of(file, approved, result, seconds, record.toAbsolutePath.normalize.toString, start).spaces2 + "\n", UTF_8))
      )
    yield result.outcome match
      case Outcome.Done       => ExitCode.Success
      case Outcome.Ask(_)     => ExitCode(2)
      case Outcome.Aborted(_) => ExitCode(3)

  private val interactiveAsk: AskHandler[IO] = (q, last) =>
    IO.print(AskContext.render(last) + s"\nask  $q\n> ") >> IO.readLine.map(a => Option(a).map(_.trim).filter(_.nonEmpty))

/** Was der Mensch vor einer Frage sieht: das Ergebnis des Schritts davor (D#73). Steuerzeichen sichtbar wie im
  * Explain; lange Ausgaben mit Anfang und Ende, die Mitte ausgelassen.
  */
object AskContext:
  val HeadLines = 20
  val TailLines = 60

  def render(last: StepResult): String =
    val code = last.exitCode.map(c => s" (Exit-Code $c)").getOrElse("")
    last.output.filter(_.nonEmpty) match
      case None if last.exitCode.isEmpty => ""
      case None                          => s"\nErgebnis des Schritts davor$code: keine Ausgabe\n"
      case Some(out) =>
        val lines = out.linesIterator.toVector
        val shown =
          if lines.size <= HeadLines + TailLines then lines
          else (lines.take(HeadLines) :+ s"… ${lines.size - HeadLines - TailLines} Zeilen ausgelassen …") ++ lines.takeRight(TailLines)
        s"\nErgebnis des Schritts davor$code, ${lines.size} Zeilen:\n" + shown.map(l => "  │ " + Text.visible(l)).mkString("\n") + "\n"

/** Kennzahlen eines Laufs für Serien (playbook/evals.md) und `ase stats`: Plan und Ausführung zusammen. source,
  * sourceSha, seconds und record seit D#74; sourceSha fehlt bei Plan-Dateien älter als Version 3. workspaceSha seit
  * D#78: Tree-Hash des Arbeitsverzeichnisses vor dem Lauf (TreeHash), fehlt, wenn er nicht lesbar war.
  */
object Summary:
  def of(file: PlanFile, approved: ApprovedTerm, result: RunResult, seconds: Double, record: String, workspaceSha: Option[String] = None): Json =
    val s = result.stats
    Json.obj(
      "outcome" -> Json.fromString(result.outcome.label),
      "source" -> Json.fromString(file.source),
      "sourceSha" -> file.sourceSha.fold(Json.Null)(Json.fromString),
      "workspaceSha" -> workspaceSha.fold(Json.Null)(Json.fromString),
      "seconds" -> Json.fromDoubleOrNull(seconds),
      "record" -> Json.fromString(record),
      "termHash" -> Json.fromString(approved.termHash.hex),
      "planFingerprint" -> Json.fromString(Fingerprint.of(file.term).hex),
      "steps" -> Json.fromLong(s.steps),
      "planCalls" -> Json.fromInt(file.calls),
      "planCandidates" -> Json.fromInt(file.candidates),
      "planAgreeing" -> Json.fromInt(file.agreeing),
      "fillCalls" -> Json.fromInt(s.modelCalls),
      "gate1Rejections" -> Json.fromInt(file.rejections),
      "gate2Rejections" -> Json.fromInt(s.gate2Rejections),
      "unusableAnswers" -> Json.fromInt(file.unusable + s.unusableAnswers),
      "runtimeFailures" -> Json.fromInt(s.runtimeFailures),
      "holeRounds" -> Json.fromInt(result.events.count(_.isInstanceOf[Event.HoleRound])),
      "asked" -> Json.fromBoolean(result.events.exists(_.isInstanceOf[Event.Asked])),
      "tokens" -> Json.fromLong(file.tokens + s.tokens),
      "changedFiles" -> Json.arr(s.changedFiles.map(Json.fromString)*),
      "fillFingerprints" -> Json.arr(result.events.collect { case e: Event.FillAccepted => Json.fromString(Fingerprint.of(e.term).hex) }*)
    ).dropNullValues

/** Kennzahlen des Plans allein (O#23), auch wenn kein Plan Tor 1 bestanden hat oder keine Mehrheit zustande kam.
  * Dieselben Schlüssel wie in Summary, damit series.sh beide gleich liest; planFingerprint fehlt ohne Plan.
  */
object PlanSummary:
  def of(p: Planned): Json =
    val outcome = if p.checked.isDefined then "checked" else if p.split then "split" else "rejected"
    val (candidates, agreeing) = p.vote match
      case Some(BestOfN.Vote.Majority(_, a, of)) => (of, a)
      case Some(BestOfN.Vote.Split(_, _, of))    => (of, 0)
      case None                                  => (p.candidates, if p.checked.isDefined then 1 else 0)
    Json.obj(
      "planOutcome" -> Json.fromString(outcome),
      "planCalls" -> Json.fromInt(p.calls),
      "planCandidates" -> Json.fromInt(candidates),
      "planAgreeing" -> Json.fromInt(agreeing),
      "gate1Rejections" -> Json.fromInt(p.rejections),
      "unusableAnswers" -> Json.fromInt(p.unusable),
      "tokens" -> Json.fromLong(p.tokens)
    ).deepMerge(Json.obj(p.checked.map(c => "planFingerprint" -> Json.fromString(Fingerprint.of(c.term).hex)).toList*))
