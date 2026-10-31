package ase.core

import cats.syntax.all.*
import io.circe.{Json, parser}

import java.util.regex.PatternSyntaxException
import scala.util.matching.Regex

/** Ein von Menschen geschriebener Term mit Hülle und typisierten Parametern (D#22). params: Name → Regex. */
final case class Flow(description: String, params: Map[String, String], envelope: Envelope, term: Term)

enum FlowError:
  case Decode(error: DecodeError)
  case InvalidRegex(name: String, regex: String)
  case MissingValue(name: String)
  case UndeclaredValue(name: String)
  case InvalidValue(name: String, value: String, regex: String)
  case UndeclaredPlaceholder(name: String)
  case EnvelopeTooWide(violations: List[Violation])

object Flow:
  private val Placeholder = """\$\{([A-Za-z_][A-Za-z0-9_]*)\}""".r

  def parse(text: String, nestingLimit: Int = DecodeLimits.DefaultNesting): Either[FlowError, Flow] =
    parser.parse(text).leftMap(e => FlowError.Decode(DecodeError.InvalidJson(e.message))).flatMap(decode(_, nestingLimit))

  def decode(json: Json, nestingLimit: Int = DecodeLimits.DefaultNesting): Either[FlowError, Flow] =
    val root = Location.root
    def err(e: DecodeError) = FlowError.Decode(e)
    for
      o <- json.asObject.toRight(err(DecodeError.Malformed(root, "ein Objekt")))
      _ <- o.keys.find(k => !Set("description", "params", "envelope", "term")(k)).map(k => err(DecodeError.UnknownField(root, k))).toLeft(())
      description <- o("description").flatMap(_.asString).toRight(err(DecodeError.MissingField(root, "description")))
      paramsObj <- o("params").flatMap(_.asObject).toRight(err(DecodeError.MissingField(root, "params")))
      params <- paramsObj.toList.traverse((k, v) => v.asString.map(k -> _).toRight(err(DecodeError.Malformed(root / "params" / k, "Text"))))
      envelope <- o("envelope").toRight(err(DecodeError.MissingField(root, "envelope"))).flatMap(Codec.decodeEnvelope(_, root / "envelope").leftMap(err))
      term <- o("term").toRight(err(DecodeError.MissingField(root, "term")))
        .flatMap(Codec.decodeTerm(_, OriginMode.Required, DecodeLimits.all(nestingLimit)).leftMap(err))
    yield Flow(description, params.toMap, envelope, term)

  /** Ein Flow als JSON, wie decode ihn liest; Terme mit origin. Für Flows, die ein Werkzeug schreibt (D#75). */
  def encode(f: Flow): Json = Json.obj(
    "description" -> Json.fromString(f.description),
    "params" -> Json.obj(f.params.toList.sorted.map((k, v) => k -> Json.fromString(v))*),
    "envelope" -> Codec.envelope(f.envelope),
    "term" -> Codec.term(f.term)
  )

  /** Bindet die Parameter nach dem Decodieren, nur in Textfeldern des Terms, und prüft die Flow-Hülle gegen die
    * Hülle des Runners. Danach kommt DryRun gegen flow.envelope.
    */
  def bind(flow: Flow, values: Map[String, String], runnerRoot: Envelope): Either[FlowError, Flow] =
    for
      regexes <- flow.params.toList.traverse((name, rx) =>
        try Right(name -> new Regex(rx))
        catch case _: PatternSyntaxException => Left(FlowError.InvalidRegex(name, rx))
      )
      _ <- (values.keySet -- flow.params.keySet).toList.sorted.headOption.map(FlowError.UndeclaredValue(_)).toLeft(())
      _ <- (flow.params.keySet -- values.keySet).toList.sorted.headOption.map(FlowError.MissingValue(_)).toLeft(())
      _ <- regexes.traverse((name, rx) =>
        Either.cond(rx.matches(values(name)), (), FlowError.InvalidValue(name, values(name), rx.regex))
      )
      term <- mapStrings(flow.term)(substitute(_, values))
      _ <- noViolations(Attenuation.check(flow.envelope, runnerRoot, Location.root / "envelope", Attenuation.Mode.Flow))
    yield flow.copy(term = term)

  private def noViolations(v: List[Violation]): Either[FlowError, Unit] =
    Either.cond(v.isEmpty, (), FlowError.EnvelopeTooWide(v))

  private def substitute(s: String, values: Map[String, String]): Either[FlowError, String] =
    Placeholder.findAllMatchIn(s).map(_.group(1)).find(!values.contains(_)) match
      case Some(name) => Left(FlowError.UndeclaredPlaceholder(name))
      case None       => Right(Placeholder.replaceAllIn(s, m => Regex.quoteReplacement(values(m.group(1)))))

  /** Term => Term: ersetzt Textfelder. Eigene Funktion, kein Interpreter; DryRun folgt danach. */
  private def mapStrings(t: Term)(f: String => Either[FlowError, String]): Either[FlowError, Term] =
    def pred(p: Pred): Either[FlowError, Pred] = p match
      case Pred.FileExists(path)         => f(path).map(Pred.FileExists(_))
      case Pred.LastOutputContains(text) => f(text).map(Pred.LastOutputContains(_))
      case Pred.Not(a)                   => pred(a).map(Pred.Not(_))
      case Pred.And(a, b)                => (pred(a), pred(b)).mapN(Pred.And(_, _))
      case other                         => Right(other)
    def envelope(e: Envelope): Either[FlowError, Envelope] =
      (
        f(e.pathPrefix),
        e.shellAllowlist.toList.traverse(c => (f(c.binary), c.requiredPrefixArgs.traverse(f)).mapN(AllowedCommand(_, _))),
        e.scopePrefix.traverse(f)
      ).mapN((prefix, shell, scope) => e.copy(pathPrefix = prefix, shellAllowlist = shell.toSet, scopePrefix = scope))
    def go(t: Term): Either[FlowError, Term] = t match
      case Term.ReadFile(p, o)          => f(p).map(Term.ReadFile(_, o))
      case Term.ListDir(p, o)           => f(p).map(Term.ListDir(_, o))
      case Term.WriteFile(p, c, o)      => (f(p), f(c)).mapN(Term.WriteFile(_, _, o))
      case Term.EditFile(p, old, r, o)  => (f(p), f(old), f(r)).mapN(Term.EditFile(_, _, _, o))
      case Term.MoveFile(a, b, o)       => (f(a), f(b)).mapN(Term.MoveFile(_, _, o))
      case Term.Shell(c, args, o)       => (f(c), args.traverse(f)).mapN(Term.Shell(_, _, o))
      case Term.Ask(q, o)               => f(q).map(Term.Ask(_, o))
      case Term.Done(s, o)              => f(s).map(Term.Done(_, o))
      case Term.Hole(g, e, o, v) =>
        val verify = v.traverse(x => (f(x.cmd), x.args.traverse(f), pred(x.until)).mapN(Verify(_, _, _, x.max)))
        (f(g), envelope(e), verify).mapN(Term.Hole(_, _, o, _))
      case Term.Seq(steps, o)           => steps.traverse(go).map(Term.Seq(_, o))
      case Term.If(p, a, b, o)          => (pred(p), go(a), go(b)).mapN(Term.If(_, _, _, o))
      case Term.RepeatUntil(b, u, m, o) => (go(b), pred(u)).mapN(Term.RepeatUntil(_, _, m, o))
    go(t)
