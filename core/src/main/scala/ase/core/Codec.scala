package ase.core

import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.parser

enum DecodeError:
  case InvalidJson(detail: String)
  case Malformed(loc: Location, expected: String)
  case MissingField(loc: Location, field: String)
  case UnknownField(loc: Location, field: String)
  case UnknownType(loc: Location, name: String)
  case OpNotAllowed(loc: Location, op: OpKind)
  case TooDeep(loc: Location)
  case OriginNotAllowed(loc: Location)
  case InvalidOrigin(loc: Location, value: String)
  case LoneSurrogate(loc: Location)

  def message: String = this match
    case InvalidJson(d)            => s"Kein gültiges JSON: $d"
    case Malformed(loc, expected)  => s"${loc.show}: erwartet $expected."
    case MissingField(loc, f)      => s"${loc.show}: Feld „$f“ fehlt."
    case UnknownField(loc, f)      => s"${loc.show}: Feld „$f“ ist nicht vorgesehen."
    case UnknownType(loc, n)       => s"${loc.show}: unbekannter Typ „$n“."
    case OpNotAllowed(loc, OpKind.Hole) => s"${loc.show}: Hole ist in dieser Hülle nicht erlaubt. ${Violation.holeHint}"
    case OpNotAllowed(loc, op)     => s"${loc.show}: $op ist in dieser Hülle nicht erlaubt."
    case TooDeep(loc)              => s"${loc.show}: zu tief verschachtelt; flache die Struktur ab."
    case OriginNotAllowed(loc)     => s"${loc.show}: origin setzt die Engine, nicht das Modell."
    case InvalidOrigin(loc, value) => s"${loc.show}: origin „$value“ ist weder Human noch Model:<callId>."
    case LoneSurrogate(loc)        => s"${loc.show}: Text enthält ein einzelnes Surrogat-Zeichen."

/** origin beim Decodieren (D#26): Flows und Aufzeichnungen tragen es; Modellantworten nicht, die Engine stempelt. */
enum OriginMode:
  case Required
  case Stamp(origin: Origin)

/** Was ein Decoder annimmt (D#25). */
final case class DecodeLimits(allowedOps: Set[OpKind], nestingLimit: Int)

object DecodeLimits:
  val DefaultNesting = 5

  def all(nestingLimit: Int = DefaultNesting): DecodeLimits = DecodeLimits(OpKind.values.toSet, nestingLimit)

/** JSON-Codec von Hand (D#26): Diskriminator "type", strikt (unbekannte Felder sind Fehler), Mengen sortiert. */
object Codec:
  type Result[A] = Either[DecodeError, A]

  // ---------- Encoder

  def origin(o: Origin): Json = o match
    case Origin.Human     => Json.fromString("Human")
    case Origin.Model(id) => Json.fromString(s"Model:$id")

  def allowedCommand(c: AllowedCommand): Json =
    Json.obj("binary" -> Json.fromString(c.binary), "requiredPrefixArgs" -> strs(c.requiredPrefixArgs))

  def envelope(e: Envelope): Json = Json.obj(
    "allowedOps" -> Json.arr(e.allowedOps.toList.sortBy(_.ordinal).map(o => Json.fromString(o.toString))*),
    "pathPrefix" -> Json.fromString(e.pathPrefix),
    "maxSteps" -> Json.fromInt(e.maxSteps),
    "maxDepth" -> Json.fromInt(e.maxDepth),
    "shellAllowlist" -> Json.arr(
      e.shellAllowlist.toList.sortBy(c => (c.binary, c.requiredPrefixArgs.mkString("\u0000"))).map(allowedCommand)*
    )
  )

  def pred(p: Pred): Json = p match
    case Pred.FileExists(path)         => node("FileExists", "path" -> Json.fromString(path))
    case Pred.LastExitCode(code)       => node("LastExitCode", "code" -> Json.fromInt(code))
    case Pred.LastOutputContains(text) => node("LastOutputContains", "text" -> Json.fromString(text))
    case Pred.LastOutputEmpty          => node("LastOutputEmpty")
    case Pred.Not(a)                   => node("Not", "p" -> pred(a))
    case Pred.And(a, b)                => node("And", "p" -> pred(a), "q" -> pred(b))

  /** withOrigin = false ist die Form, die das Modell schreibt (Schema ohne origin). */
  def term(t: Term, withOrigin: Boolean = true): Json =
    def n(tpe: String, o: Origin, fields: (String, Json)*): Json =
      if withOrigin then node(tpe, (fields :+ ("origin" -> origin(o)))*) else node(tpe, fields*)
    def s(v: String) = Json.fromString(v)
    t match
      case Term.ReadFile(p, o)          => n("ReadFile", o, "path" -> s(p))
      case Term.ListDir(p, o)           => n("ListDir", o, "path" -> s(p))
      case Term.WriteFile(p, c, o)      => n("WriteFile", o, "path" -> s(p), "content" -> s(c))
      case Term.EditFile(p, old, r, o)  => n("EditFile", o, "path" -> s(p), "expectedOld" -> s(old), "replacement" -> s(r))
      case Term.MoveFile(f, to, o)      => n("MoveFile", o, "from" -> s(f), "to" -> s(to))
      case Term.Shell(c, args, o)       => n("Shell", o, "cmd" -> s(c), "args" -> strs(args))
      case Term.Ask(q, o)               => n("Ask", o, "question" -> s(q))
      case Term.Done(sum, o)            => n("Done", o, "summary" -> s(sum))
      case Term.Seq(steps, o)           => n("Seq", o, "steps" -> Json.arr(steps.map(term(_, withOrigin))*))
      case Term.If(p, a, b, o)          => n("If", o, "pred" -> pred(p), "thenT" -> term(a, withOrigin), "elseT" -> term(b, withOrigin))
      case Term.RepeatUntil(b, u, m, o) => n("RepeatUntil", o, "body" -> term(b, withOrigin), "until" -> pred(u), "max" -> Json.fromInt(m))
      case Term.Hole(g, e, o)           => n("Hole", o, "goal" -> s(g), "envelope" -> envelope(e))

  private def node(tpe: String, fields: (String, Json)*): Json =
    Json.obj((("type" -> Json.fromString(tpe)) +: fields)*)

  private def strs(xs: List[String]): Json = Json.arr(xs.map(Json.fromString)*)

  // ---------- Decoder

  def parseTerm(text: String, mode: OriginMode, limits: DecodeLimits): Result[Term] =
    parser.parse(text).leftMap(e => DecodeError.InvalidJson(e.message)).flatMap(decodeTerm(_, mode, limits))

  def decodeTerm(json: Json, mode: OriginMode, limits: DecodeLimits): Result[Term] =
    termAt(json, Location.root, limits.nestingLimit, mode, limits)

  /** Decoder für Fill-Teilterme (D#25): kennt nur Knoten, die die Hülle erlaubt; origin stempelt die Engine. */
  def decoderFor(envelope: Envelope, nestingLimit: Int, origin: Origin): Json => Result[Term] =
    json => decodeTerm(json, OriginMode.Stamp(origin), DecodeLimits(envelope.allowedOps, nestingLimit))

  def decodeEnvelope(json: Json, loc: Location): Result[Envelope] =
    for
      o <- obj(json, loc)
      _ <- only(o, loc, Set("allowedOps", "pathPrefix", "maxSteps", "maxDepth", "shellAllowlist"))
      opNames <- strings(o, loc, "allowedOps")
      ops <- opNames.traverse(n => OpKind.parse(n).toRight(DecodeError.UnknownType(loc / "allowedOps", n)))
      prefix <- string(o, loc, "pathPrefix")
      maxSteps <- int(o, loc, "maxSteps")
      maxDepth <- int(o, loc, "maxDepth")
      shellJson <- array(o, loc, "shellAllowlist")
      shell <- shellJson.zipWithIndex.traverse((j, i) => decodeAllowedCommand(j, loc.at("shellAllowlist", i)))
    yield Envelope(ops.toSet, prefix, maxSteps, maxDepth, shell.toSet)

  private def decodeAllowedCommand(json: Json, loc: Location): Result[AllowedCommand] =
    for
      o <- obj(json, loc)
      _ <- only(o, loc, Set("binary", "requiredPrefixArgs"))
      binary <- string(o, loc, "binary")
      args <- strings(o, loc, "requiredPrefixArgs")
    yield AllowedCommand(binary, args)

  def decodePred(json: Json, loc: Location, level: Int): Result[Pred] =
    for
      o <- obj(json, loc)
      tpe <- string(o, loc, "type")
      p <- tpe match
        case "FileExists"         => only(o, loc, Set("type", "path")) *> string(o, loc, "path").map(Pred.FileExists(_))
        case "LastExitCode"       => only(o, loc, Set("type", "code")) *> int(o, loc, "code").map(Pred.LastExitCode(_))
        case "LastOutputContains" => only(o, loc, Set("type", "text")) *> string(o, loc, "text").map(Pred.LastOutputContains(_))
        case "LastOutputEmpty"    => only(o, loc, Set("type")).as(Pred.LastOutputEmpty)
        case "Not" =>
          deeper(loc, level) >> only(o, loc, Set("type", "p")) *>
            field(o, loc, "p").flatMap(decodePred(_, loc / "p", level - 1)).map(Pred.Not(_))
        case "And" =>
          deeper(loc, level) >> only(o, loc, Set("type", "p", "q")) *>
            (
              field(o, loc, "p").flatMap(decodePred(_, loc / "p", level - 1)),
              field(o, loc, "q").flatMap(decodePred(_, loc / "q", level - 1))
            ).mapN(Pred.And(_, _))
        case other => Left(DecodeError.UnknownType(loc, other))
    yield p

  private def termAt(json: Json, loc: Location, level: Int, mode: OriginMode, limits: DecodeLimits): Result[Term] =
    def leaf(op: OpKind, o: JsonObject, fields: Set[String]): Result[Origin] =
      allowed(op, loc, limits) *> header(o, loc, fields, mode)
    def child(o: JsonObject, name: String, at: Location): Result[Term] =
      field(o, loc, name).flatMap(termAt(_, at, level - 1, mode, limits))

    obj(json, loc).flatMap { o =>
      string(o, loc, "type").flatMap {
        case "ReadFile" =>
          (leaf(OpKind.ReadFile, o, Set("path")), string(o, loc, "path")).mapN((or, p) => Term.ReadFile(p, or))
        case "ListDir" =>
          (leaf(OpKind.ListDir, o, Set("path")), string(o, loc, "path")).mapN((or, p) => Term.ListDir(p, or))
        case "WriteFile" =>
          (leaf(OpKind.WriteFile, o, Set("path", "content")), string(o, loc, "path"), string(o, loc, "content"))
            .mapN((or, p, c) => Term.WriteFile(p, c, or))
        case "EditFile" =>
          (
            leaf(OpKind.EditFile, o, Set("path", "expectedOld", "replacement")),
            string(o, loc, "path"),
            string(o, loc, "expectedOld"),
            string(o, loc, "replacement")
          ).mapN((or, p, old, r) => Term.EditFile(p, old, r, or))
        case "MoveFile" =>
          (leaf(OpKind.MoveFile, o, Set("from", "to")), string(o, loc, "from"), string(o, loc, "to"))
            .mapN((or, f, t) => Term.MoveFile(f, t, or))
        case "Shell" =>
          (leaf(OpKind.Shell, o, Set("cmd", "args")), string(o, loc, "cmd"), strings(o, loc, "args"))
            .mapN((or, c, a) => Term.Shell(c, a, or))
        case "Ask" =>
          (leaf(OpKind.Ask, o, Set("question")), string(o, loc, "question")).mapN((or, q) => Term.Ask(q, or))
        case "Done" =>
          (header(o, loc, Set("summary"), mode), string(o, loc, "summary")).mapN((or, s) => Term.Done(s, or))
        case "Hole" =>
          (
            leaf(OpKind.Hole, o, Set("goal", "envelope")),
            string(o, loc, "goal"),
            field(o, loc, "envelope").flatMap(decodeEnvelope(_, loc / "envelope"))
          ).mapN((or, g, e) => Term.Hole(g, e, or))
        case "Seq" =>
          deeper(loc, level) >> header(o, loc, Set("steps"), mode).flatMap { or =>
            array(o, loc, "steps")
              .flatMap(_.zipWithIndex.traverse((j, i) => termAt(j, loc.at("steps", i), level - 1, mode, limits)))
              .map(Term.Seq(_, or))
          }
        case "If" =>
          deeper(loc, level) >> (
            header(o, loc, Set("pred", "thenT", "elseT"), mode),
            field(o, loc, "pred").flatMap(decodePred(_, loc / "pred", limits.nestingLimit)),
            child(o, "thenT", loc / "thenT"),
            child(o, "elseT", loc / "elseT")
          ).mapN((or, p, a, b) => Term.If(p, a, b, or))
        case "RepeatUntil" =>
          deeper(loc, level) >> (
            header(o, loc, Set("body", "until", "max"), mode),
            child(o, "body", loc / "body"),
            field(o, loc, "until").flatMap(decodePred(_, loc / "until", limits.nestingLimit)),
            int(o, loc, "max")
          ).mapN((or, b, u, m) => Term.RepeatUntil(b, u, m, or))
        case other => Left(DecodeError.UnknownType(loc, other))
      }
    }

  /** Prüft die erlaubten Felder und liefert origin nach OriginMode. */
  private def header(o: JsonObject, loc: Location, fields: Set[String], mode: OriginMode): Result[Origin] =
    mode match
      case OriginMode.Required =>
        only(o, loc, fields + "type" + "origin") *>
          string(o, loc, "origin").flatMap(parseOrigin(_, loc / "origin"))
      case OriginMode.Stamp(origin) =>
        if o.contains("origin") then Left(DecodeError.OriginNotAllowed(loc))
        else only(o, loc, fields + "type").as(origin)

  private def parseOrigin(s: String, loc: Location): Result[Origin] =
    if s == "Human" then Right(Origin.Human)
    else if s.startsWith("Model:") && s.length > "Model:".length then Right(Origin.Model(s.drop("Model:".length)))
    else Left(DecodeError.InvalidOrigin(loc, s))

  private def allowed(op: OpKind, loc: Location, limits: DecodeLimits): Result[Unit] =
    Either.cond(limits.allowedOps(op), (), DecodeError.OpNotAllowed(loc, op))

  private def deeper(loc: Location, level: Int): Result[Unit] =
    Either.cond(level > 0, (), DecodeError.TooDeep(loc))

  private def obj(json: Json, loc: Location): Result[JsonObject] =
    json.asObject.toRight(DecodeError.Malformed(loc, "ein Objekt"))

  private def only(o: JsonObject, loc: Location, fields: Set[String]): Result[Unit] =
    o.keys.find(k => !fields(k)).fold(Right(()))(k => Left(DecodeError.UnknownField(loc, k)))

  private def field(o: JsonObject, loc: Location, name: String): Result[Json] =
    o(name).toRight(DecodeError.MissingField(loc, name))

  private def string(o: JsonObject, loc: Location, name: String): Result[String] =
    field(o, loc, name).flatMap(_.asString.toRight(DecodeError.Malformed(loc / name, "Text"))).flatMap(text(_, loc / name))

  private def int(o: JsonObject, loc: Location, name: String): Result[Int] =
    field(o, loc, name).flatMap(_.asNumber.flatMap(_.toInt).toRight(DecodeError.Malformed(loc / name, "eine ganze Zahl")))

  private def array(o: JsonObject, loc: Location, name: String): Result[List[Json]] =
    field(o, loc, name).flatMap(_.asArray.map(_.toList).toRight(DecodeError.Malformed(loc / name, "eine Liste")))

  private def strings(o: JsonObject, loc: Location, name: String): Result[List[String]] =
    array(o, loc, name).flatMap(_.zipWithIndex.traverse((j, i) =>
      j.asString.toRight(DecodeError.Malformed(loc.at(name, i), "Text")).flatMap(text(_, loc.at(name, i)))
    ))

  private def text(s: String, loc: Location): Result[String] =
    Either.cond(!Text.hasLoneSurrogate(s), s, DecodeError.LoneSurrogate(loc))
