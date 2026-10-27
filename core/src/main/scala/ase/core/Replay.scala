package ase.core

import cats.Monad
import cats.data.StateT
import cats.syntax.all.*

/** Replay (D#16): derselbe Kern wie Execute, aber die Ports antworten aus der Aufzeichnung, ohne Dateisystem und
  * ohne Modell. Gleichheit heißt: dieselbe Ereignisfolge.
  */
object Replay:
  enum ReplayError:
    case NoStart
    case HashMismatch(recorded: TermHash, approved: TermHash)
    case Unexpected(requested: String, recorded: String)
    case Exhausted(requested: String)
    case Diverged(index: Int, recorded: Option[Event], replayed: Option[Event])

  /** Noch nicht abgespielte Antworten, je Port in Reihenfolge. */
  final case class Cursor(calls: List[(Call, Reply)], fills: List[(String, ModelReply)], asks: List[(String, Option[String])])

  type Attempt[A] = Either[ReplayError, A]
  type R[A] = StateT[Attempt, Cursor, A]

  def run(approved: ApprovedTerm, recorded: Vector[Event], config: ExecConfig): Either[ReplayError, RunResult] =
    recorded.headOption match
      case Some(Event.Start(h, _, _)) if h == approved.termHash =>
        val cursor = Cursor(
          recorded.collect { case Event.Step(_, c, r) => (c, r) }.toList,
          recorded.collect { case Event.Fill(_, _, id, r) => (id, r) }.toList,
          recorded.collect { case Event.Asked(_, q, a) => (q, a) }.toList
        )
        Execute.run[R](approved, ports(recorded), config).runA(cursor)
      case Some(Event.Start(h, _, _)) => Left(ReplayError.HashMismatch(h, approved.termHash))
      case _                          => Left(ReplayError.NoStart)

  /** Replay und Vergleich mit der Aufzeichnung. */
  def verify(approved: ApprovedTerm, recorded: Vector[Event], config: ExecConfig): Either[ReplayError, RunResult] =
    run(approved, recorded, config).flatMap { replayed =>
      val n = math.max(recorded.size, replayed.events.size)
      (0 until n).find(i => recorded.lift(i) != replayed.events.lift(i)) match
        case Some(i) => Left(ReplayError.Diverged(i, recorded.lift(i), replayed.events.lift(i)))
        case None    => Right(replayed)
    }

  /** Die aufgezeichneten Schreibvorgänge, die gelungen sind, in Reihenfolge. */
  def writes(events: Vector[Event]): List[Call] =
    events.collect { case Event.Step(_, c, Reply(_, Right(_), _)) if c.writes => c }.toList

  /** Spielt die Schreibvorgänge in ein frisches Arbeitsverzeichnis (D#16), über beliebige Werkzeuge. */
  def applyWrites[F[_]: Monad](events: Vector[Event], tools: Tools[F], envelope: Envelope): F[List[Reply]] =
    writes(events).traverse(tools.call(_, envelope))

  private def isTimeLimit(e: Event): Boolean = e match
    case Event.End(Outcome.Aborted(AbortReason.LimitReached(Limit.Time(_)))) => true
    case _                                                                   => false

  private def ports(recorded: Vector[Event]): Ports[R] =
    val tools: Tools[R] = (call, _) =>
      StateT { cur =>
        cur.calls match
          case (c, reply) :: rest if c == call => Right((cur.copy(calls = rest), reply))
          case (c, _) :: _                     => Left(ReplayError.Unexpected(call.toString, c.toString))
          case Nil                             => Left(ReplayError.Exhausted(call.toString))
      }
    val model: FillModel[R] = request =>
      StateT { cur =>
        cur.fills match
          case (id, reply) :: rest if id == request.callId => Right((cur.copy(fills = rest), reply))
          case (id, _) :: _                                 => Left(ReplayError.Unexpected(request.callId, id))
          case Nil                                          => Left(ReplayError.Exhausted(request.callId))
      }
    val ask: AskHandler[R] = question =>
      StateT { cur =>
        cur.asks match
          case (q, answer) :: rest if q == question => Right((cur.copy(asks = rest), answer))
          case (q, _) :: _                          => Left(ReplayError.Unexpected(question, q))
          case Nil                                  => Left(ReplayError.Exhausted(question))
      }
    val clock: Clock[R] = position => StateT.pure(recorded.lift(position).exists(isTimeLimit))
    Ports(tools, model, ask, clock, Recorder.none[R])
