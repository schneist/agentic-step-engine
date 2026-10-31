package ase.core

import cats.Monad
import cats.syntax.all.*

/** Der Kern von Execute und Replay: eine Deutung der Algebra über Ports. Wählt zur Laufzeit Zweige, füllt Löcher
  * (Fill → Decoder der Hülle → Tor 2 → eingeschränkte Algebra), zählt Schritte und schreibt Ereignisse.
  * Effekte nur über die Ports; Execute gibt echte, Replay aufgezeichnete.
  */
private[core] object Engine:
  /** Ein umschließender Aufruf eines Lochs mit seiner Hülle. call: laufende Nummer des Aufrufs im Lauf; an ihr hängt
    * das Budget, damit jeder Durchlauf von RepeatUntil sein eigenes hat (D#14).
    */
  final case class Frame(call: Int, envelope: Envelope)

  /** Hülle, in der gerade geprüft wird; Positionspräfix; umschließende Löcher (innerstes zuerst); Loch-Tiefe. */
  final case class Ctx(envelope: Envelope, at: Vector[Int], holes: List[Frame], depth: Int):
    def id(n: Int): StepId = StepId(at :+ n)

  /** Ein früheres Ergebnis für den Scope (D#9): die aufgelösten Pfade relativ zum Arbeitsverzeichnis, pfadlos = leer. */
  final case class Entry(at: StepId, label: String, paths: List[String], result: StepResult)

  /** fills: Zahl der Fills je Loch-Position (für die StepId); calls: Zahl der Aufrufe von Löchern; used: ausgeführte
    * Schritte je Aufruf.
    */
  final case class State(
      events: Vector[Event],
      last: StepResult,
      scope: Vector[Entry],
      steps: Long,
      modelCalls: Int,
      fills: Map[StepId, Int],
      calls: Int,
      used: Map[Int, Int]
  )

  object State:
    val initial: State = State(Vector.empty, StepResult(None, None), Vector.empty, 0L, 0, Map.empty, 0, Map.empty)

  enum Stop:
    /** Done: beendet das Loch, auf oberster Ebene den Lauf (D#15). */
    case Finished(summary: String)
    /** Laufzeitfehler: im Loch neuer Fill, auf oberster Ebene Abbruch. */
    case Failed(at: StepId, error: ToolError)
    /** Lauf endet: ask ohne Antwort, Limits, Versuche erschöpft. */
    case Halt(outcome: Outcome)

private[core] final class Engine[F[_]](ports: Ports[F], config: ExecConfig, check: CheckConfig, root: Envelope)(using F: Monad[F]):
  import Engine.*

  type Res = F[(State, Either[Stop, StepResult])]
  type Run = (Ctx, State) => Res
  /** Erst die statische Nummerierung wie im Explain, dann der Lauf. */
  type Exec[A] = Int => (Int, Run)

  private val stepCap: Int = math.min(config.maxTotalSteps, root.maxSteps)

  def run(term: Term, hash: TermHash): F[RunResult] =
    val (_, program) = AgentAlg.fromTerm[Exec](term)(AgentAlg.restrict[Exec](alg, root)(refuse))(1)
    emit(State.initial, Event.Start(hash, root, term)).flatMap(program(Ctx(root, Vector.empty, Nil, 0), _)).flatMap { (s, r) =>
      val outcome = r match
        case Right(_) | Left(Stop.Finished(_)) => Outcome.Done
        case Left(Stop.Failed(at, error))      => Outcome.Aborted(AbortReason.StepFailed(at, error))
        case Left(Stop.Halt(o))                => o
      emit(s, Event.End(outcome, s.steps)).map(end => RunResult(outcome, end.events))
    }

  // ---------- Hilfen

  private def emit(s: State, e: Event): F[State] = ports.recorder.write(e).as(s.copy(events = s.events :+ e))

  private def halt(s: State, stop: Stop): Res = F.pure((s, Left(stop)))

  private def aborted(reason: AbortReason): Stop = Stop.Halt(Outcome.Aborted(reason))

  /** Harte Grenzen vor jedem Schritt, unabhängig von DryRun gezählt: Gesamtlimit, Wurzel-Budget, Budget jedes
    * umschließenden Lochs (zweite Linie hinter Tor 2).
    */
  private def admit(ctx: Ctx, s: State, at: StepId): Option[Stop] =
    if s.steps >= stepCap then Some(aborted(AbortReason.LimitReached(Limit.TotalSteps(stepCap))))
    else
      ctx.holes
        .find(f => s.used.getOrElse(f.call, 0) >= f.envelope.maxSteps)
        .map(f => Stop.Failed(at, ToolError.BudgetExhausted(f.envelope.maxSteps)))

  /** Ein ausgeführter Schritt zählt gesamt und für jeden umschließenden Aufruf eines Lochs (D#14). */
  private def count(ctx: Ctx, s: State): State =
    s.copy(steps = s.steps + 1, used = ctx.holes.foldLeft(s.used)((u, f) => u.updated(f.call, u.getOrElse(f.call, 0) + 1)))

  private def timeUp(s: State): F[Option[Stop]] =
    ports.clock.expired(s.events.size).map(Option.when(_)(aborted(AbortReason.LimitReached(Limit.Time(config.maxDuration.toSeconds)))))

  /** Werkzeugaufruf mit Uhr und Ereignis. */
  private def tool(ctx: Ctx, s: State, at: StepId, call: Call): F[(State, Either[Stop, Reply])] =
    timeUp(s).flatMap {
      case Some(stop) => F.pure((s, Left(stop)))
      case None       => ports.tools.call(call, ctx.envelope).flatMap(reply => emit(s, Event.Step(at, call, reply)).map((_, Right(reply))))
    }

  /** Ein Blatt. scopePaths: welche Pfade das Ergebnis für den Scope trägt, aus der Antwort der Werkzeuge (M1). */
  private def leaf(label: String, call: Call, scopePaths: Reply => List[String]): Exec[StepResult] = n =>
    val run: Run = (ctx, s0) =>
      val at = ctx.id(n)
      admit(ctx, s0, at) match
        case Some(stop) => halt(s0, stop)
        case None =>
          tool(ctx, s0, at, call).map {
            case (s, Left(stop)) => (s, Left(stop))
            case (s, Right(reply)) =>
              val counted = count(ctx, s)
              reply.result match
                case Right(r)  => (counted.copy(last = r, scope = counted.scope :+ Entry(at, label, scopePaths(reply), r)), Right(r))
                case Left(err) => (counted, Left(Stop.Failed(at, err)))
          }
    (n + 1, run)

  /** Zweite Linie (D#25): eine Operation, die die Hülle nicht erlaubt, läuft nicht. width: Nummern des Knotens. */
  private val refuse: (OpKind, Origin, Int) => Exec[StepResult] = (op, _, width) =>
    n =>
      val run: Run = (ctx, s) =>
        val at = ctx.id(n)
        emit(s, Event.Rejected(at, op)).map(s1 => (s1, Left(Stop.Failed(at, ToolError.OpNotAllowed(op)))))
      (n + width, run)

  private def holds(p: Pred, ctx: Ctx, s: State, at: StepId): F[(State, Either[Stop, Boolean])] = p match
    case Pred.LastExitCode(c)       => F.pure((s, Right(s.last.exitCode.contains(c))))
    case Pred.LastOutputContains(t) => F.pure((s, Right(s.last.output.exists(_.contains(t)))))
    case Pred.LastOutputEmpty       => F.pure((s, Right(s.last.output.exists(_.forall(_.isWhitespace)))))
    case Pred.FileExists(path) =>
      tool(ctx, s, at, Call.FileExists(path)).map {
        case (s1, Left(stop)) => (s1, Left(stop))
        case (s1, Right(reply)) =>
          reply.result match
            case Right(r)  => (s1, Right(r.output.contains("true")))
            case Left(err) => (s1, Left(Stop.Failed(at, err)))
      }
    case Pred.Not(a) => holds(a, ctx, s, at).map((s1, r) => (s1, r.map(!_)))
    case Pred.And(a, b) =>
      holds(a, ctx, s, at).flatMap {
        case (s1, Right(true)) => holds(b, ctx, s1, at)
        case other             => F.pure(other)
      }

  // ---------- Löcher

  /** Ein Aufruf eines Lochs: eigene Aufrufnummer, eigenes Budget, Versuche ab 1. verified: was die Abnahme nach dem
    * Durchlauf davor verfehlt hat; geht in jeder Runde dieses Aufrufs ans Modell (D#80).
    */
  private final case class HoleCall(at: StepId, call: Int, goal: String, env: Envelope, outer: Vector[Entry], verified: Vector[String])

  /** Ohne Abnahme ein Aufruf. Mit Abnahme Durchläufe wie RepeatUntil(Seq(Loch, Programm), until, max), aber jeder
    * weitere Durchlauf erfährt, dass die Abnahme nicht bestanden ist. Nach dem letzten ist das Ergebnis das der Abnahme;
    * der Plan entscheidet mit einem Prädikat, wie es weitergeht (D#80).
    */
  private def hole(goal: String, env: Envelope, verify: Option[Verify], origin: Origin): Exec[StepResult] = n =>
    def call(ctx: Ctx, s0: State, verified: Vector[String]): Res =
      val at = ctx.id(n)
      if ctx.depth + 1 > config.maxDepth then halt(s0, aborted(AbortReason.LimitReached(Limit.Depth(config.maxDepth))))
      else
        admit(ctx, s0, at) match
          case Some(stop) => halt(s0, stop)
          case None =>
            emit(s0, Event.Hole(at, goal, env)).flatMap { s =>
              val h = HoleCall(at, s.calls + 1, goal, env, s.scope, verified)
              attempt(ctx, h)(1, 1, Vector.empty, false, s.copy(calls = h.call))
            }
    verify match
      case None => (n + 1, (ctx, s) => call(ctx, s, Vector.empty))
      case Some(v) =>
        // die Abnahme ist ein gewöhnlicher Schritt der umgebenden Hülle, mit eigener Nummer nach dem Loch
        val (next, program) = alg.shell(v.cmd, v.args, origin)(n + 1)
        // base: Scope vor dem ersten Durchlauf. Danach bleiben nur Ergebnis und Abnahme des letzten Durchlaufs darin,
        // sonst stünde jede frühere Abnahme unter derselben Nummer noch einmal im Kontext späterer Löcher.
        def loop(pass: Int, verified: Vector[String], base: Vector[Entry], ctx: Ctx, s: State): Res =
          val at = ctx.id(n + 1)
          val start = s.scope.size
          call(ctx, s, verified).flatMap {
            case (s1, Right(_)) =>
              program(ctx, s1).flatMap {
                case (s2a, Right(r)) =>
                  val s2 = s2a.copy(scope = base ++ s2a.scope.drop(start))
                  holds(v.until, ctx, s2, at).flatMap {
                    case (s3, Right(true)) => emit(s3, Event.Verified(at, pass, true, None)).map((_, Right(r)))
                    case (s3, Right(false)) if pass < v.max =>
                      val toModel = Marshal.verifyFailed(v, at, r, pass)
                      emit(s3, Event.Verified(at, pass, false, Some(toModel))).flatMap(loop(pass + 1, Vector(toModel), base, ctx, _))
                    case (s3, Right(false)) => emit(s3, Event.Verified(at, pass, false, None)).map((_, Right(r)))
                    case (s3, Left(stop))   => halt(s3, stop)
                  }
                case stopped => F.pure(stopped)
              }
            case stopped => F.pure(stopped)
          }
        (next, (ctx, s) => loop(1, Vector.empty, s.scope, ctx, s))

  private def scopeLines(scope: Vector[Entry], env: Envelope): Vector[ScopeLine] =
    val seen = scope.zipWithIndex.map((e, i) => Scope.Seen(i, e.paths))
    Scope.visible(seen, env).map(v => scope(v.step)).map { e =>
      val text = Marshal.excerpt(Marshal(e.result), config.maxBytesPerStep, config.excerptHeadLines, config.excerptTailLines).text
      ScopeLine(e.at.show, e.label, text)
    }

  /** Versuch k in Runde round für den Aufruf h. counted: der Schritt des Lochs ist für diesen Aufruf schon gezählt.
    * Versuche zählen je Runde; das Schrittbudget gilt für alle Runden zusammen (D#55).
    */
  private def attempt(ctx: Ctx, h: HoleCall)(round: Int, k: Int, errors: Vector[String], counted: Boolean, s: State): Res =
    val env = h.env
    val remaining = env.maxSteps - s.used.getOrElse(h.call, 0)
    def next(s1: State, toModel: String, countedNow: Boolean): Res =
      attempt(ctx, h)(round, k + 1, errors :+ toModel, countedNow, s1)

    if k > config.attemptsPerFill then halt(s, aborted(AbortReason.AttemptsExhausted(h.at, config.attemptsPerFill)))
    else if remaining <= 0 then halt(s, aborted(AbortReason.BudgetExhausted(h.at, env.maxSteps)))
    else if s.modelCalls >= config.maxModelCalls then halt(s, aborted(AbortReason.LimitReached(Limit.ModelCalls(config.maxModelCalls))))
    else
      timeUp(s).flatMap {
        case Some(stop) => halt(s, stop)
        case None =>
          val callId = s"fill-${s.modelCalls + 1}"
          // laufende Nummer des Fills an dieser Position, über Versuche und Durchläufe hinweg (StepId)
          val fillNo = s.fills.getOrElse(h.at, 0) + 1
          val request =
            FillRequest(callId, h.at.show, k, h.goal, env, remaining, check.nestingLimit, scopeLines(s.scope, env), errors, round, config.maxRoundsPerHole, h.verified)
          ports.model.fill(request).flatMap { reply =>
            val sCalled = s.copy(modelCalls = s.modelCalls + 1, fills = s.fills.updated(h.at, fillNo))
            emit(sCalled, Event.Fill(h.at, k, callId, reply)).flatMap { s1 =>
              def reject(gate: Gate, term: Option[Term], reasons: List[String]): Res =
                val toModel = Marshal.rejection(reasons)
                emit(s1, Event.FillRejected(h.at, k, gate, term, reasons, toModel)).flatMap(next(_, toModel, counted))

              reply match
                case ModelReply.Failed(reason, _) => reject(Gate.Answer, None, List(reason))
                case ModelReply.Answer(text, _) =>
                  Submission.decode(text, env, check.nestingLimit, Origin.Model(callId)) match
                    case Left(e) => reject(Gate.Decode, None, List(Marshal(e)))
                    case Right(term) =>
                      DryRun.fill(term, env, check) match
                        case Left(vs) => reject(Gate.DryRun, Some(term), vs.toList.map(Marshal(_)))
                        case Right(cf) if cf.worstCaseSteps < 0 || cf.worstCaseSteps > remaining =>
                          val v = Violation.StepBudgetExceeded(Location.root, cf.worstCaseSteps, remaining)
                          reject(Gate.DryRun, Some(term), List(Marshal(v)))
                        case Right(cf) => accepted(ctx, h, round, k, fillNo, errors, counted, remaining, cf, s1)
            }
          }
      }

  private def accepted(
      ctx: Ctx,
      h: HoleCall,
      round: Int,
      k: Int,
      fillNo: Int,
      errors: Vector[String],
      counted: Boolean,
      remaining: Int,
      cf: CheckedFill,
      s1: State
  ): Res =
    // der angenommene Fill zählt einmal je Aufruf als Schritt des Lochs, Wiederholungen nicht (D#14)
    val s2 = if counted then s1 else count(ctx, s1)
    emit(s2, Event.FillAccepted(h.at, k, cf.termHash, cf.worstCaseSteps, remaining, cf.term)).flatMap { s3 =>
      val inner = Ctx(h.env, h.at.parts :+ fillNo, Frame(h.call, h.env) :: ctx.holes, ctx.depth + 1)
      val (_, program) = AgentAlg.fromTerm[Exec](cf.term)(AgentAlg.restrict[Exec](alg, h.env)(refuse))(1)
      def finish(s: State, summary: String): Res =
        val r = StepResult(None, Some(summary))
        emit(s, Event.HoleDone(h.at, summary)).map(s5 =>
          (s5.copy(last = r, scope = h.outer :+ Entry(h.at, s"Ergebnis von Loch ${h.at.show}", Nil, r)), Right(r))
        )
      // Ein Fill ohne Done, der etwas getan hat, bekommt eine weitere Runde, solange Runden und Budget reichen; sie
      // sieht die Ergebnisse dieser Runde im Scope (D#55). Ohne Schritt gäbe es nichts Neues zu sehen.
      def again(s4: State): Boolean =
        round < config.maxRoundsPerHole && s4.steps > s3.steps && s4.used.getOrElse(h.call, 0) < h.env.maxSteps
      program(inner, s3.copy(last = StepResult(None, None))).flatMap {
        case (s4, Right(_)) if again(s4) =>
          emit(s4, Event.HoleRound(h.at, round + 1)).flatMap(s5 => attempt(ctx, h)(round + 1, 1, Vector.empty, true, s5))
        case (s4, Right(_))                        => finish(s4, "")
        case (s4, Left(Stop.Finished(summary)))    => finish(s4, summary)
        case (s4, Left(Stop.Failed(failedAt, err))) =>
          val toModel = Marshal.failure(failedAt, err)
          emit(s4, Event.FillFailed(h.at, k, err, toModel)).flatMap(s5 => attempt(ctx, h)(round, k + 1, errors :+ toModel, true, s5))
        case halted => F.pure(halted)
      }
    }

  // ---------- die Deutung

  /** Datei-Operationen tragen für den Scope die aufgelösten Pfade (bei MoveFile beide), nicht die Pfade im Term. */
  private val resolved: Reply => List[String] = _.touched

  val alg: AgentAlg[Exec] = new AgentAlg[Exec]:
    def readFile(path: String, origin: Origin): Exec[StepResult] = leaf(s"read $path", Call.ReadFile(path), resolved)
    def listDir(path: String, origin: Origin): Exec[StepResult] = leaf(s"list $path", Call.ListDir(path), resolved)
    def writeFile(path: String, content: String, origin: Origin): Exec[StepResult] =
      leaf(s"write $path", Call.WriteFile(path, content), resolved)
    def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): Exec[StepResult] =
      leaf(s"edit $path", Call.EditFile(path, expectedOld, replacement), resolved)
    def moveFile(from: String, to: String, origin: Origin): Exec[StepResult] =
      leaf(s"move $from → $to", Call.MoveFile(from, to), resolved)
    def shell(cmd: String, args: List[String], origin: Origin): Exec[StepResult] =
      // pfadlos nach Scope.shellPaths (D#39), sonst die aufgelösten Pfade
      val pathless = Scope.shellPaths(cmd, args, check).isEmpty
      leaf(s"shell ${(cmd :: args).mkString(" ")}", Call.Shell(cmd, args), r => if pathless then Nil else r.touched)

    def ask(question: String, origin: Origin): Exec[StepResult] = n =>
      val run: Run = (ctx, s0) =>
        val at = ctx.id(n)
        admit(ctx, s0, at) match
          case Some(stop) => halt(s0, stop)
          case None =>
            timeUp(s0).flatMap {
              case Some(stop) => halt(s0, stop)
              case None =>
                ports.ask.ask(question, s0.last).flatMap { answer =>
                  emit(count(ctx, s0), Event.Asked(at, question, answer)).map { s =>
                    answer match
                      case Some(a) =>
                        val r = StepResult(None, Some(a))
                        (s.copy(last = r, scope = s.scope :+ Entry(at, s"ask $question", Nil, r)), Right(r))
                      case None => (s, Left(Stop.Halt(Outcome.Ask(question))))
                  }
                }
            }
      (n + 1, run)

    def done(summary: String, origin: Origin): Exec[StepResult] = n =>
      val run: Run = (ctx, s0) =>
        val at = ctx.id(n)
        admit(ctx, s0, at) match
          case Some(stop) => halt(s0, stop)
          case None =>
            emit(count(ctx, s0), Event.Finished(at, summary)).map(s =>
              (s.copy(last = StepResult(None, Some(summary))), Left(Stop.Finished(summary)))
            )
      (n + 1, run)

    def think(goal: String, envelope: Envelope, verify: Option[Verify], origin: Origin): Exec[StepResult] = hole(goal, envelope, verify, origin)

    def seq(steps: List[Exec[StepResult]], origin: Origin): Exec[StepResult] = n =>
      val (next, runs) = steps.foldLeft((n, Vector.empty[Run])) { case ((k, acc), e) =>
        val (k2, r) = e(k)
        (k2, acc :+ r)
      }
      val run: Run = (ctx, s) =>
        runs.foldLeft[Res](F.pure((s, Right(s.last)))) { (acc, r) =>
          acc.flatMap {
            case (s1, Right(_)) => r(ctx, s1)
            case stopped        => F.pure(stopped)
          }
        }
      (next, run)

    def branch(pred: Pred, thenP: Exec[StepResult], elseP: Exec[StepResult], origin: Origin): Exec[StepResult] = n =>
      val (n1, a) = thenP(n)
      val (n2, b) = elseP(n1)
      val run: Run = (ctx, s) =>
        holds(pred, ctx, s, ctx.id(n)).flatMap {
          case (s1, Right(true))  => a(ctx, s1)
          case (s1, Right(false)) => b(ctx, s1)
          case (s1, Left(stop))   => halt(s1, stop)
        }
      (n2, run)

    def repeatUntil(body: Exec[StepResult], until: Pred, max: Int, origin: Origin): Exec[StepResult] = n =>
      val (next, b) = body(n)
      def loop(i: Int, ctx: Ctx, s: State): Res =
        b(ctx, s).flatMap {
          case (s1, Right(r)) =>
            holds(until, ctx, s1, ctx.id(n)).flatMap {
              case (s2, Right(true)) => F.pure((s2, Right(s2.last)))
              // höchstens max-mal; ein Durchlauf ohne Schritt ändert nichts mehr, dann hört die Schleife auf
              case (s2, Right(false)) if i < max && s2.steps > s.steps => loop(i + 1, ctx, s2)
              case (s2, Right(false)) => F.pure((s2, Right(s2.last)))
              case (s2, Left(stop))   => halt(s2, stop)
            }
          case stopped => F.pure(stopped)
        }
      (next, (ctx, s) => loop(1, ctx, s))
