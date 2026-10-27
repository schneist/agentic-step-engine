package ase.core

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json, parser}

enum RecordError:
  case InvalidLine(line: Int, detail: String)
  case UnsupportedVersion(line: Int, version: Int)

  def message: String = this match
    case InvalidLine(line, detail)       => s"Zeile $line: $detail"
    case UnsupportedVersion(line, found) => s"Zeile $line: Version $found, erwartet ${RecordLog.Version}"

/** Record-Log als JSON Lines (Event Sourcing). Jede Zeile trägt das Versionsfeld "v" und den Diskriminator "type".
  * Von Hand wie der Term-Codec (D#26); Terme darin mit origin.
  */
object RecordLog:
  /** 2: End trägt die Schrittzahl der Engine, Io trägt Art und Pfad statt des Ausnahmetextes (Review 3). */
  val Version = 2

  /** Verschachtelungsgrenze beim Lesen eingebetteter Terme; großzügig, das Log schreibt die Engine selbst. */
  private val LogNesting = 64

  type Result[A] = Either[String, A]

  def line(e: Event): String = encode(e).noSpaces

  def render(events: Vector[Event]): String = events.map(line).mkString("", "\n", "\n")

  def parse(text: String): Either[RecordError, Vector[Event]] =
    text.linesIterator.zipWithIndex
      .filter((l, _) => l.trim.nonEmpty)
      .toVector
      .traverse((l, i) => parseLine(l, i + 1))

  def parseLine(text: String, lineNo: Int): Either[RecordError, Event] =
    for
      json <- parser.parse(text).leftMap(e => RecordError.InvalidLine(lineNo, e.message))
      v <- json.hcursor.get[Int]("v").leftMap(e => RecordError.InvalidLine(lineNo, "Versionsfeld v fehlt"))
      _ <- Either.cond(v == Version, (), RecordError.UnsupportedVersion(lineNo, v))
      e <- decode(json).leftMap(RecordError.InvalidLine(lineNo, _))
    yield e

  // ---------- Encoder

  private def s(v: String): Json = Json.fromString(v)
  private def strs(xs: List[String]): Json = Json.arr(xs.map(s)*)
  private def obj(tpe: String, fields: (String, Json)*): Json = Json.obj((("type" -> s(tpe)) +: fields)*)

  def encode(e: Event): Json =
    val body = e match
      case Event.Start(h, root, term) =>
        obj("Start", "termHash" -> s(h.hex), "root" -> Codec.envelope(root), "term" -> Codec.term(term))
      case Event.Step(at, call, reply) => obj("Step", "at" -> s(at.show), "call" -> encodeCall(call), "reply" -> encodeReply(reply))
      case Event.Rejected(at, op)      => obj("Rejected", "at" -> s(at.show), "op" -> s(op.toString))
      case Event.Hole(at, goal, env) =>
        obj("Hole", "at" -> s(at.show), "goal" -> s(goal), "envelope" -> Codec.envelope(env))
      case Event.Fill(at, attempt, callId, reply) =>
        obj("Fill", "at" -> s(at.show), "attempt" -> Json.fromInt(attempt), "callId" -> s(callId), "reply" -> encodeModelReply(reply))
      case Event.FillRejected(at, attempt, gate, term, reasons, toModel) =>
        obj(
          "FillRejected",
          "at" -> s(at.show),
          "attempt" -> Json.fromInt(attempt),
          "gate" -> s(gate.toString),
          "term" -> term.fold(Json.Null)(Codec.term(_)),
          "reasons" -> strs(reasons),
          "toModel" -> s(toModel)
        )
      case Event.FillAccepted(at, attempt, hash, worst, budget, term) =>
        obj(
          "FillAccepted",
          "at" -> s(at.show),
          "attempt" -> Json.fromInt(attempt),
          "hash" -> s(hash.hex),
          "worstCase" -> Json.fromLong(worst),
          "budget" -> Json.fromInt(budget),
          "term" -> Codec.term(term)
        )
      case Event.FillFailed(at, attempt, error, toModel) =>
        obj("FillFailed", "at" -> s(at.show), "attempt" -> Json.fromInt(attempt), "error" -> encodeError(error), "toModel" -> s(toModel))
      case Event.HoleDone(at, summary) => obj("HoleDone", "at" -> s(at.show), "summary" -> s(summary))
      case Event.Asked(at, q, answer) =>
        obj("Asked", "at" -> s(at.show), "question" -> s(q), "answer" -> answer.fold(Json.Null)(s))
      case Event.Finished(at, summary) => obj("Finished", "at" -> s(at.show), "summary" -> s(summary))
      case Event.End(outcome, steps)   => obj("End", "outcome" -> encodeOutcome(outcome), "steps" -> Json.fromLong(steps))
    body.mapObject(("v" -> Json.fromInt(Version)) +: _)

  def encodeCall(c: Call): Json = c match
    case Call.ReadFile(p)           => obj("ReadFile", "path" -> s(p))
    case Call.ListDir(p)            => obj("ListDir", "path" -> s(p))
    case Call.WriteFile(p, content) => obj("WriteFile", "path" -> s(p), "content" -> s(content))
    case Call.EditFile(p, old, r)   => obj("EditFile", "path" -> s(p), "expectedOld" -> s(old), "replacement" -> s(r))
    case Call.MoveFile(f, t)        => obj("MoveFile", "from" -> s(f), "to" -> s(t))
    case Call.Shell(cmd, args)      => obj("Shell", "cmd" -> s(cmd), "args" -> strs(args))
    case Call.FileExists(p)         => obj("FileExists", "path" -> s(p))

  private def encodeResult(r: StepResult): Json =
    Json.obj("exitCode" -> r.exitCode.fold(Json.Null)(Json.fromInt), "output" -> r.output.fold(Json.Null)(s))

  private def encodeReply(r: Reply): Json =
    Json.obj(
      "touched" -> strs(r.touched),
      "result" -> r.result.fold(e => Json.obj("error" -> encodeError(e)), ok => Json.obj("ok" -> encodeResult(ok))),
      "note" -> s(r.note)
    )

  private def encodeModelReply(r: ModelReply): Json = r match
    case ModelReply.Answer(text, tokens)   => obj("Answer", "submission" -> s(text), "tokens" -> Json.fromLong(tokens))
    case ModelReply.Failed(reason, tokens) => obj("Failed", "reason" -> s(reason), "tokens" -> Json.fromLong(tokens))

  def encodeError(e: ToolError): Json = e match
    case ToolError.InvalidPath(p, reason)        => obj("InvalidPath", "path" -> s(p), "reason" -> s(reason))
    case ToolError.OutsidePrefix(p, prefix)      => obj("OutsidePrefix", "path" -> s(p), "prefix" -> s(prefix))
    case ToolError.OutsideWorkspace(p)           => obj("OutsideWorkspace", "path" -> s(p))
    case ToolError.GitDirLocked(p)               => obj("GitDirLocked", "path" -> s(p))
    case ToolError.Unresolvable(p)               => obj("Unresolvable", "path" -> s(p))
    case ToolError.NotFound(p)                   => obj("NotFound", "path" -> s(p))
    case ToolError.NotAFile(p)                   => obj("NotAFile", "path" -> s(p))
    case ToolError.NotADirectory(p)              => obj("NotADirectory", "path" -> s(p))
    case ToolError.SymlinkNotAllowed(p)          => obj("SymlinkNotAllowed", "path" -> s(p))
    case ToolError.ParentMissing(p)              => obj("ParentMissing", "path" -> s(p))
    case ToolError.AlreadyExists(p)              => obj("AlreadyExists", "path" -> s(p))
    case ToolError.EmptyExpectedOld(p)           => obj("EmptyExpectedOld", "path" -> s(p))
    case ToolError.CasMissing(p)                 => obj("CasMissing", "path" -> s(p))
    case ToolError.CasAmbiguous(p, n)            => obj("CasAmbiguous", "path" -> s(p), "count" -> Json.fromInt(n))
    case ToolError.TooLarge(p, bytes, limit)     => obj("TooLarge", "path" -> s(p), "bytes" -> Json.fromLong(bytes), "limit" -> Json.fromLong(limit))
    case ToolError.NotText(p)                    => obj("NotText", "path" -> s(p))
    case ToolError.ShellNotAllowed(cmd, args)    => obj("ShellNotAllowed", "cmd" -> s(cmd), "args" -> strs(args))
    case ToolError.ShellArgRejected(cmd, a, r)   => obj("ShellArgRejected", "cmd" -> s(cmd), "arg" -> s(a), "reason" -> s(r))
    case ToolError.ProgramNotFound(cmd)          => obj("ProgramNotFound", "cmd" -> s(cmd))
    case ToolError.NoGitRepository               => obj("NoGitRepository")
    case ToolError.Timeout(cmd, seconds, output) => obj("Timeout", "cmd" -> s(cmd), "seconds" -> Json.fromLong(seconds), "output" -> s(output))
    case ToolError.OpNotAllowed(op)              => obj("OpNotAllowed", "op" -> s(op.toString))
    case ToolError.BudgetExhausted(max)          => obj("BudgetExhausted", "maxSteps" -> Json.fromInt(max))
    case ToolError.Io(kind, p)                   => obj("Io", "kind" -> s(kind), "path" -> s(p))

  private def encodeLimit(l: Limit): Json = l match
    case Limit.TotalSteps(max) => obj("TotalSteps", "max" -> Json.fromInt(max))
    case Limit.ModelCalls(max) => obj("ModelCalls", "max" -> Json.fromInt(max))
    case Limit.Time(seconds)   => obj("Time", "seconds" -> Json.fromLong(seconds))
    case Limit.Depth(max)      => obj("Depth", "max" -> Json.fromInt(max))

  private def encodeOutcome(o: Outcome): Json = o match
    case Outcome.Done        => obj("Done")
    case Outcome.Ask(q)      => obj("Ask", "question" -> s(q))
    case Outcome.Aborted(r) =>
      val reason = r match
        case AbortReason.StepFailed(at, e)        => obj("StepFailed", "at" -> s(at.show), "error" -> encodeError(e))
        case AbortReason.AttemptsExhausted(at, n) => obj("AttemptsExhausted", "at" -> s(at.show), "attempts" -> Json.fromInt(n))
        case AbortReason.BudgetExhausted(at, n)   => obj("BudgetExhausted", "at" -> s(at.show), "maxSteps" -> Json.fromInt(n))
        case AbortReason.LimitReached(l)          => obj("LimitReached", "limit" -> encodeLimit(l))
      obj("Aborted", "reason" -> reason)

  // ---------- Decoder

  private def get[A: Decoder](c: ACursor, field: String): Result[A] =
    c.downField(field).as[A].leftMap(_ => s"Feld „$field“ fehlt oder hat den falschen Typ")

  private def tpe(c: ACursor): Result[String] = get[String](c, "type")

  private def at(c: ACursor): Result[StepId] =
    get[String](c, "at").flatMap(a => StepId.parse(a).toRight(s"ungültige Position „$a“"))

  private def hash(c: ACursor, field: String): Result[TermHash] =
    get[String](c, field).flatMap(h => TermHash.fromHex(h).toRight(s"ungültiger Hash „$h“"))

  private def term(j: Json): Result[Term] =
    Codec.decodeTerm(j, OriginMode.Required, DecodeLimits.all(LogNesting)).leftMap(_.message)

  private def field(c: ACursor, name: String): Result[Json] = c.downField(name).focus.toRight(s"Feld „$name“ fehlt")

  private def envelope(c: ACursor, name: String): Result[Envelope] =
    field(c, name).flatMap(Codec.decodeEnvelope(_, Location.root / name).leftMap(_.message))

  private def opKind(c: ACursor, name: String): Result[OpKind] =
    get[String](c, name).flatMap(n => OpKind.parse(n).toRight(s"unbekannte Operation „$n“"))

  def decode(json: Json): Result[Event] =
    val c = json.hcursor
    tpe(c).flatMap {
      case "Start" =>
        (hash(c, "termHash"), envelope(c, "root"), field(c, "term").flatMap(term)).mapN(Event.Start(_, _, _))
      case "Step" =>
        (at(c), decodeCall(c.downField("call")), decodeReply(c.downField("reply"))).mapN(Event.Step(_, _, _))
      case "Rejected" => (at(c), opKind(c, "op")).mapN(Event.Rejected(_, _))
      case "Hole"     => (at(c), get[String](c, "goal"), envelope(c, "envelope")).mapN(Event.Hole(_, _, _))
      case "Fill" =>
        (at(c), get[Int](c, "attempt"), get[String](c, "callId"), decodeModelReply(c.downField("reply"))).mapN(Event.Fill(_, _, _, _))
      case "FillRejected" =>
        (
          at(c),
          get[Int](c, "attempt"),
          get[String](c, "gate").flatMap(g => Gate.values.find(_.toString == g).toRight(s"unbekanntes Tor „$g“")),
          field(c, "term").flatMap(j => if j.isNull then Right(None) else term(j).map(Some(_))),
          get[List[String]](c, "reasons"),
          get[String](c, "toModel")
        ).mapN(Event.FillRejected(_, _, _, _, _, _))
      case "FillAccepted" =>
        (
          at(c),
          get[Int](c, "attempt"),
          hash(c, "hash"),
          get[Long](c, "worstCase"),
          get[Int](c, "budget"),
          field(c, "term").flatMap(term)
        ).mapN(Event.FillAccepted(_, _, _, _, _, _))
      case "FillFailed" =>
        (at(c), get[Int](c, "attempt"), decodeError(c.downField("error")), get[String](c, "toModel")).mapN(Event.FillFailed(_, _, _, _))
      case "HoleDone" => (at(c), get[String](c, "summary")).mapN(Event.HoleDone(_, _))
      case "Asked"    => (at(c), get[String](c, "question"), get[Option[String]](c, "answer")).mapN(Event.Asked(_, _, _))
      case "Finished" => (at(c), get[String](c, "summary")).mapN(Event.Finished(_, _))
      case "End"      => (decodeOutcome(c.downField("outcome")), get[Long](c, "steps")).mapN(Event.End(_, _))
      case other      => Left(s"unbekanntes Ereignis „$other“")
    }

  def decodeCall(c: ACursor): Result[Call] =
    tpe(c).flatMap {
      case "ReadFile"   => get[String](c, "path").map(Call.ReadFile(_))
      case "ListDir"    => get[String](c, "path").map(Call.ListDir(_))
      case "WriteFile"  => (get[String](c, "path"), get[String](c, "content")).mapN(Call.WriteFile(_, _))
      case "EditFile"   => (get[String](c, "path"), get[String](c, "expectedOld"), get[String](c, "replacement")).mapN(Call.EditFile(_, _, _))
      case "MoveFile"   => (get[String](c, "from"), get[String](c, "to")).mapN(Call.MoveFile(_, _))
      case "Shell"      => (get[String](c, "cmd"), get[List[String]](c, "args")).mapN(Call.Shell(_, _))
      case "FileExists" => get[String](c, "path").map(Call.FileExists(_))
      case other        => Left(s"unbekannter Aufruf „$other“")
    }

  private def decodeReply(c: ACursor): Result[Reply] =
    val result = c.downField("result")
    val r: Result[Either[ToolError, StepResult]] =
      if result.downField("error").succeeded then decodeError(result.downField("error")).map(Left(_))
      else
        val ok = result.downField("ok")
        (get[Option[Int]](ok, "exitCode"), get[Option[String]](ok, "output")).mapN((e, o) => Right(StepResult(e, o)))
    (get[List[String]](c, "touched"), r, get[String](c, "note")).mapN(Reply(_, _, _))

  private def decodeModelReply(c: ACursor): Result[ModelReply] =
    tpe(c).flatMap {
      case "Answer" => (get[String](c, "submission"), get[Long](c, "tokens")).mapN(ModelReply.Answer(_, _))
      case "Failed" => (get[String](c, "reason"), get[Long](c, "tokens")).mapN(ModelReply.Failed(_, _))
      case other    => Left(s"unbekannte Modellantwort „$other“")
    }

  def decodeError(c: ACursor): Result[ToolError] =
    def p = get[String](c, "path")
    tpe(c).flatMap {
      case "InvalidPath"       => (p, get[String](c, "reason")).mapN(ToolError.InvalidPath(_, _))
      case "OutsidePrefix"     => (p, get[String](c, "prefix")).mapN(ToolError.OutsidePrefix(_, _))
      case "OutsideWorkspace"  => p.map(ToolError.OutsideWorkspace(_))
      case "GitDirLocked"      => p.map(ToolError.GitDirLocked(_))
      case "Unresolvable"      => p.map(ToolError.Unresolvable(_))
      case "NotFound"          => p.map(ToolError.NotFound(_))
      case "NotAFile"          => p.map(ToolError.NotAFile(_))
      case "NotADirectory"     => p.map(ToolError.NotADirectory(_))
      case "SymlinkNotAllowed" => p.map(ToolError.SymlinkNotAllowed(_))
      case "ParentMissing"     => p.map(ToolError.ParentMissing(_))
      case "AlreadyExists"     => p.map(ToolError.AlreadyExists(_))
      case "EmptyExpectedOld"  => p.map(ToolError.EmptyExpectedOld(_))
      case "CasMissing"        => p.map(ToolError.CasMissing(_))
      case "CasAmbiguous"      => (p, get[Int](c, "count")).mapN(ToolError.CasAmbiguous(_, _))
      case "TooLarge"          => (p, get[Long](c, "bytes"), get[Long](c, "limit")).mapN(ToolError.TooLarge(_, _, _))
      case "NotText"           => p.map(ToolError.NotText(_))
      case "ShellNotAllowed"   => (get[String](c, "cmd"), get[List[String]](c, "args")).mapN(ToolError.ShellNotAllowed(_, _))
      case "ShellArgRejected" =>
        (get[String](c, "cmd"), get[String](c, "arg"), get[String](c, "reason")).mapN(ToolError.ShellArgRejected(_, _, _))
      case "ProgramNotFound" => get[String](c, "cmd").map(ToolError.ProgramNotFound(_))
      case "NoGitRepository" => Right(ToolError.NoGitRepository)
      case "Timeout" =>
        (get[String](c, "cmd"), get[Long](c, "seconds"), get[String](c, "output")).mapN(ToolError.Timeout(_, _, _))
      case "OpNotAllowed"    => opKind(c, "op").map(ToolError.OpNotAllowed(_))
      case "BudgetExhausted" => get[Int](c, "maxSteps").map(ToolError.BudgetExhausted(_))
      case "Io"              => (get[String](c, "kind"), p).mapN(ToolError.Io(_, _))
      case other             => Left(s"unbekannter Fehler „$other“")
    }

  private def decodeLimit(c: ACursor): Result[Limit] =
    tpe(c).flatMap {
      case "TotalSteps" => get[Int](c, "max").map(Limit.TotalSteps(_))
      case "ModelCalls" => get[Int](c, "max").map(Limit.ModelCalls(_))
      case "Time"       => get[Long](c, "seconds").map(Limit.Time(_))
      case "Depth"      => get[Int](c, "max").map(Limit.Depth(_))
      case other        => Left(s"unbekanntes Limit „$other“")
    }

  private def decodeOutcome(c: ACursor): Result[Outcome] =
    tpe(c).flatMap {
      case "Done" => Right(Outcome.Done)
      case "Ask"  => get[String](c, "question").map(Outcome.Ask(_))
      case "Aborted" =>
        val r = c.downField("reason")
        tpe(r).flatMap {
          case "StepFailed"        => (at(r), decodeError(r.downField("error"))).mapN(AbortReason.StepFailed(_, _))
          case "AttemptsExhausted" => (at(r), get[Int](r, "attempts")).mapN(AbortReason.AttemptsExhausted(_, _))
          case "BudgetExhausted"   => (at(r), get[Int](r, "maxSteps")).mapN(AbortReason.BudgetExhausted(_, _))
          case "LimitReached"      => decodeLimit(r.downField("limit")).map(AbortReason.LimitReached(_))
          case other               => Left(s"unbekannter Abbruchgrund „$other“")
        }.map(Outcome.Aborted(_))
      case other => Left(s"unbekannter Ausgang „$other“")
    }
