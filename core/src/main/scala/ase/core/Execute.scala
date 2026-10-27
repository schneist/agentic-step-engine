package ase.core

import cats.Monad
import cats.effect.Sync
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.parser

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** Eingabe von submit_term: {"term": …}. Plan und Fill lesen sie gleich, mit dem Decoder der Hülle (D#25). */
object Submission:
  def decode(text: String, envelope: Envelope, nestingLimit: Int, origin: Origin): Either[DecodeError, Term] =
    for
      json <- parser.parse(text).leftMap(e => DecodeError.InvalidJson(e.message))
      o <- json.asObject.toRight(DecodeError.Malformed(Location.root, "ein Objekt mit dem Feld „term“"))
      _ <- o.keys.find(_ != "term").map(k => DecodeError.UnknownField(Location.root, k)).toLeft(())
      t <- o("term").toRight(DecodeError.MissingField(Location.root, "term"))
      term <- Codec.decoderFor(envelope, nestingLimit, origin)(t)
    yield term

enum ExecuteError:
  case UnsupportedPlatform(os: String)
  case InvalidWorkspace(path: String, reason: String)
  case RecordUnavailable(path: String, reason: String)

  def message: String = this match
    case UnsupportedPlatform(os)          => s"Execute läuft nur unter Linux (WSL oder CI), nicht unter $os (D#29)."
    case InvalidWorkspace(path, reason)   => s"Arbeitsverzeichnis $path: $reason"
    case RecordUnavailable(path, reason)  => s"Record-Log $path: $reason"

/** Execute nur unter Linux (D#29). */
object Platform:
  def check(osName: String): Either[ExecuteError, Unit] =
    Either.cond(osName.toLowerCase.contains("linux"), (), ExecuteError.UnsupportedPlatform(osName))

  def current: String = sys.props.getOrElse("os.name", "unbekannt")

  def isLinux: Boolean = check(current).isRight

/** Execute: führt ausschließlich einen ApprovedTerm aus (D#11). Fill-Teilterme laufen als CheckedFill ohne eigene
  * Freigabe (D#6). Effekte nur über die Ports; `local` verbindet sie mit Dateisystem, Prozessen und Uhr.
  */
object Execute:
  /** Die einzige Stelle, an der Execute die Prüfkonfiguration bestimmt (später aus dem CheckedTerm). */
  def checkConfig(approved: ApprovedTerm, config: ExecConfig): CheckConfig = config.check

  def run[F[_]: Monad](approved: ApprovedTerm, ports: Ports[F], config: ExecConfig): F[RunResult] =
    Engine[F](ports, config, checkConfig(approved, config), approved.checked.rootEnvelope).run(approved.term, approved.termHash)

  /** Lauf im Arbeitsverzeichnis, unter Linux; mit record schreibt er das Ereignislog als JSON Lines mit. */
  def local[F[_]: Sync](
      approved: ApprovedTerm,
      workspace: Path,
      model: FillModel[F],
      ask: AskHandler[F],
      config: ExecConfig,
      record: Option[Path]
  ): F[Either[ExecuteError, RunResult]] =
    LocalTools.open[F](workspace, config.copy(check = checkConfig(approved, config))).flatMap {
      case Left(e) => Sync[F].pure(Left(e))
      case Right(tools) =>
        openRecorder[F](record).flatMap {
          case Left(e) => Sync[F].pure(Left(e))
          case Right((recorder, close)) =>
            deadline[F](config.maxDuration)
              .flatMap(clock => run(approved, Ports(tools, model, ask, clock, recorder), config))
              .guarantee(close)
              .map(Right(_))
        }
    }

  /** Uhr ab Start des Laufs. */
  def deadline[F[_]: Sync](limit: FiniteDuration): F[Clock[F]] =
    Sync[F].monotonic.map(start => (_: Int) => Sync[F].monotonic.map(now => now - start > limit))

  private def openRecorder[F[_]: Sync](record: Option[Path]): F[Either[ExecuteError, (Recorder[F], F[Unit])]] =
    record match
      case None => Sync[F].pure(Right((Recorder.none[F], Sync[F].unit)))
      case Some(path) =>
        Sync[F].blocking {
          try
            Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
            val w = Files.newBufferedWriter(path, UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Right((fileRecorder[F](w), Sync[F].blocking(w.close())))
          catch case NonFatal(e) => Left(ExecuteError.RecordUnavailable(path.toString, e.toString))
        }

  private def fileRecorder[F[_]: Sync](w: BufferedWriter): Recorder[F] = e =>
    Sync[F].blocking {
      w.write(RecordLog.line(e))
      w.newLine()
      w.flush()
    }
