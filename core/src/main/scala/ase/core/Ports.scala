package ase.core

import cats.Applicative
import cats.syntax.all.*

import scala.concurrent.duration.*

/** Konfiguration von Execute: harte Limits (SPEC RUNNER) und Kürzung (MARSHALLING). Der Runner liest sie aus einer
  * Datei. Die Prüfkonfiguration kommt aus dem CheckedTerm (D#40). maxRoundsPerHole: wie oft ein Loch nach einem Fill
  * ohne Done erneut gefüllt wird, mit den Ergebnissen der Runden davor (D#55); attemptsPerFill gilt je Runde.
  */
final case class ExecConfig(
    attemptsPerFill: Int,
    maxRoundsPerHole: Int,
    maxTotalSteps: Int,
    maxModelCalls: Int,
    maxDuration: FiniteDuration,
    maxDepth: Int,
    maxBytesPerStep: Int,
    excerptHeadLines: Int,
    excerptTailLines: Int,
    shellTimeout: FiniteDuration,
    maxEditBytes: Long,
    shellEnv: List[String]
)

object ExecConfig:
  val default: ExecConfig = ExecConfig(
    attemptsPerFill = 3,
    maxRoundsPerHole = 3,
    maxTotalSteps = 200,
    maxModelCalls = 30,
    maxDuration = 30.minutes,
    maxDepth = 3,
    maxBytesPerStep = 16 * 1024,
    excerptHeadLines = 40,
    excerptTailLines = 20,
    shellTimeout = 120.seconds,
    maxEditBytes = 1024L * 1024,
    shellEnv = List("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "JAVA_HOME")
  )

/** Ein früheres Ergebnis im Fill-Kontext, schon für das Modell aufbereitet. */
final case class ScopeLine(at: String, label: String, text: String)

/** Was das Modell für ein Loch bekommt: Ziel, Hülle, Restbudget, Kontext nach Scope.visible (D#9, D#30) und die
  * Handlungsanweisungen der bisherigen Versuche (D#15). Die Antwort ist die Eingabe von submit_term. round von
  * maxRounds: die wievielte Runde dieses Lochs (D#55); ab Runde 2 steht im Kontext auch, was die Runden davor gelesen
  * haben.
  */
final case class FillRequest(
    callId: String,
    at: String,
    attempt: Int,
    goal: String,
    envelope: Envelope,
    remainingSteps: Int,
    nestingLimit: Int,
    scope: Vector[ScopeLine],
    errors: Vector[String],
    round: Int = 1,
    maxRounds: Int = 1
)

/** Werkzeuge am Rand: Dateisystem und Prozesse. Prüft Pfade und Befehle zur Laufzeit gegen die Hülle. */
trait Tools[F[_]]:
  def call(call: Call, envelope: Envelope): F[Reply]

/** Das Modell beim Fill (Tor 2 steht dahinter). Der echte Adapter ist Schritt 4. */
trait FillModel[F[_]]:
  def fill(request: FillRequest): F[ModelReply]

/** Der Mensch bei Ask: eine Antwort, oder keine (nicht interaktiv; der Lauf endet mit ask). */
trait AskHandler[F[_]]:
  def ask(question: String): F[Option[String]]

object AskHandler:
  def nonInteractive[F[_]: Applicative]: AskHandler[F] = _ => Option.empty[String].pure[F]

/** Zeitlimit. position: Zahl der bisher geschriebenen Ereignisse; Replay beantwortet die Frage daraus. */
trait Clock[F[_]]:
  def expired(position: Int): F[Boolean]

object Clock:
  def never[F[_]: Applicative]: Clock[F] = _ => false.pure[F]

/** Schreibt Ereignisse, sobald sie entstehen (Record-Modus). */
trait Recorder[F[_]]:
  def write(event: Event): F[Unit]

object Recorder:
  def none[F[_]: Applicative]: Recorder[F] = _ => Applicative[F].unit

final case class Ports[F[_]](tools: Tools[F], model: FillModel[F], ask: AskHandler[F], clock: Clock[F], recorder: Recorder[F])

/** Ergebnis eines Laufs: Ausgang und die vollständige Ereignisfolge. */
final case class RunResult(outcome: Outcome, events: Vector[Event]):
  def stats: Stats = Stats.of(events)

/** Kennzahlen eines Laufs, aus den Ereignissen berechnet (auch für Replay und Lauf-Log). */
final case class Stats(
    steps: Long,
    modelCalls: Int,
    gate2Rejections: Int,
    unusableAnswers: Int,
    runtimeFailures: Int,
    changedFiles: List[String],
    tokens: Long,
    hints: List[(StepId, String)]
)

object Stats:
  /** Die Schrittzahl zählt die Engine selbst (D#14) und schreibt sie ins End-Ereignis; ohne End ist sie 0. */
  def of(events: Vector[Event]): Stats =
    Stats(
      steps = events.collectFirst { case Event.End(_, n) => n }.getOrElse(0L),
      modelCalls = events.count(_.isInstanceOf[Event.Fill]),
      gate2Rejections = events.count { case e: Event.FillRejected => e.gate != Gate.Answer; case _ => false },
      unusableAnswers = events.count { case e: Event.FillRejected => e.gate == Gate.Answer; case _ => false },
      runtimeFailures = events.count(_.isInstanceOf[Event.FillFailed]),
      changedFiles = events.collect { case Event.Step(_, c, Reply(t, Right(_), _)) if c.writes => t }.flatten.distinct.toList,
      tokens = events.collect { case e: Event.Fill => e.reply.tokens }.sum,
      hints = events.collect { case Event.HoleDone(at, s) if s.nonEmpty => at -> s }.toList
    )
