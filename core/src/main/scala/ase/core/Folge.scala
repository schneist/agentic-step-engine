package ase.core

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.circe.{Json, JsonObject, parser}

import java.util.regex.PatternSyntaxException
import scala.util.matching.Regex

/** Eine Stufenfolge (D#112): Flows, die nacheinander laufen, jede Stufe mit eigener Hülle und Konfiguration. Kein Term
  * und keine Algebra: die Folge ist eine Liste, gebunden wird wie bei einem Flow, geprüft wird jede Stufe für sich, frei
  * gegeben einmal über den Hash aller Stufen (Approval.CheckedFolge). Die nächste Stufe läuft nur, wenn die vorige mit
  * done endete. flow, envelope und config: Pfade relativ zur Datei der Folge. werte: Werte für die Parameter des Flows
  * der Stufe; sie dürfen ${name} der Folge enthalten.
  */
final case class Folge(description: String, params: Map[String, String], stufen: NonEmptyList[Folge.Stufe])

enum FolgeError:
  case InvalidJson(message: String)
  case Field(path: String, message: String)
  case DuplicateStage(name: String)
  case InvalidRegex(name: String, regex: String)
  case MissingValue(name: String)
  case UndeclaredValue(name: String)
  case InvalidValue(name: String, value: String, regex: String)
  case UndeclaredPlaceholder(stage: String, name: String)

  def render: String = this match
    case InvalidJson(m)                => s"Folge: kein gültiges JSON: $m"
    case Field(p, m)                   => s"Folge: $p: $m"
    case DuplicateStage(n)             => s"Folge: Stufe „$n“ doppelt"
    case InvalidRegex(n, rx)           => s"Folge: Parameter $n: „$rx“ ist kein gültiger regulärer Ausdruck"
    case MissingValue(n)               => s"Folge: Wert für $n fehlt"
    case UndeclaredValue(n)            => s"Folge: $n ist kein Parameter der Folge"
    case InvalidValue(n, v, rx)        => s"Folge: $n=„$v“ passt nicht zu $rx"
    case UndeclaredPlaceholder(s, n)   => s"Folge: Stufe $s: $${$n} ist kein Parameter der Folge"

object Folge:
  final case class Stufe(name: String, flow: String, envelope: String, config: Option[String], werte: Map[String, String])

  private val Placeholder = """\$\{([A-Za-z_][A-Za-z0-9_]*)\}""".r
  private val Name = "^[a-z][a-z0-9-]*$".r

  def parse(text: String): Either[FolgeError, Folge] =
    for
      json <- parser.parse(text).leftMap(e => FolgeError.InvalidJson(e.message))
      o <- json.asObject.toRight(FolgeError.Field("(Wurzel)", "erwartet ein Objekt"))
      _ <- only(o, "(Wurzel)", "description", "params", "stufen")
      description <- str(o, "description", "description")
      params <- strMap(o, "params", "params")
      list <- o("stufen").flatMap(_.asArray).toRight(FolgeError.Field("stufen", "erwartet eine Liste"))
      stufen <- list.toList.zipWithIndex.traverse((j, i) => stufe(j, s"stufen[$i]"))
      nel <- NonEmptyList.fromList(stufen).toRight(FolgeError.Field("stufen", "mindestens eine Stufe"))
      _ <- stufen.map(_.name).diff(stufen.map(_.name).distinct).headOption.map(FolgeError.DuplicateStage(_)).toLeft(())
    yield Folge(description, params, nel)

  private def stufe(j: Json, at: String): Either[FolgeError, Stufe] =
    for
      o <- j.asObject.toRight(FolgeError.Field(at, "erwartet ein Objekt"))
      _ <- only(o, at, "name", "flow", "envelope", "config", "werte")
      name <- str(o, "name", at)
      _ <- Either.cond(Name.matches(name), (), FolgeError.Field(s"$at.name", s"„$name“: Kleinbuchstaben, Ziffern und -, vorne ein Buchstabe"))
      flow <- str(o, "flow", at)
      envelope <- str(o, "envelope", at)
      config <- o("config").traverse(_.asString.toRight(FolgeError.Field(s"$at.config", "erwartet einen Text")))
      werte <- if o.contains("werte") then strMap(o, "werte", at) else Right(Map.empty)
    yield Stufe(name, flow, envelope, config, werte)

  /** Bindet die Werte der Folge und setzt sie in die Werte jeder Stufe ein. Wie Flow.bind: jeder Parameter braucht
    * einen Wert, der zu seinem Ausdruck passt, und kein anderer ist erlaubt. Die Werte einer Stufe prüft danach
    * Flow.bind gegen die Parameter ihres Flows.
    */
  def bind(folge: Folge, values: Map[String, String]): Either[FolgeError, NonEmptyList[(Stufe, Map[String, String])]] =
    for
      regexes <- folge.params.toList.traverse((name, rx) =>
        try Right(name -> new Regex(rx))
        catch case _: PatternSyntaxException => Left(FolgeError.InvalidRegex(name, rx))
      )
      _ <- (values.keySet -- folge.params.keySet).toList.sorted.headOption.map(FolgeError.UndeclaredValue(_)).toLeft(())
      _ <- (folge.params.keySet -- values.keySet).toList.sorted.headOption.map(FolgeError.MissingValue(_)).toLeft(())
      _ <- regexes.traverse((name, rx) => Either.cond(rx.matches(values(name)), (), FolgeError.InvalidValue(name, values(name), rx.regex)))
      bound <- folge.stufen.traverse(s =>
        s.werte.toList.traverse((k, v) => substitute(s.name, v, values).map(k -> _)).map(w => s -> w.toMap)
      )
    yield bound

  private def substitute(stage: String, s: String, values: Map[String, String]): Either[FolgeError, String] =
    Placeholder.findAllMatchIn(s).map(_.group(1)).find(!values.contains(_)) match
      case Some(name) => Left(FolgeError.UndeclaredPlaceholder(stage, name))
      case None       => Right(Placeholder.replaceAllIn(s, m => Regex.quoteReplacement(values(m.group(1)))))

  private def only(o: JsonObject, at: String, keys: String*): Either[FolgeError, Unit] =
    o.keys.find(k => !keys.contains(k)).map(k => FolgeError.Field(at, s"Feld „$k“ ist nicht vorgesehen")).toLeft(())

  private def str(o: JsonObject, name: String, at: String): Either[FolgeError, String] =
    o(name).flatMap(_.asString).toRight(FolgeError.Field(if at == name then name else s"$at.$name", "erwartet einen Text"))

  private def strMap(o: JsonObject, name: String, at: String): Either[FolgeError, Map[String, String]] =
    val p = if at == name then name else s"$at.$name"
    o(name).flatMap(_.asObject).toRight(FolgeError.Field(p, "erwartet ein Objekt")).flatMap(
      _.toList.traverse((k, j) => j.asString.map(k -> _).toRight(FolgeError.Field(s"$p.$k", "erwartet einen Text"))).map(_.toMap)
    )

  /** Datei je Stufe (D#112): record.log → record-deckel.log, summary.json → summary-deckel.json; ase stats findet
    * summary*.json weiter.
    */
  def suffixed(p: java.nio.file.Path, stage: String): java.nio.file.Path =
    val name = p.getFileName.toString
    val dot = name.lastIndexOf('.')
    val next = if dot > 0 then s"${name.take(dot)}-$stage${name.drop(dot)}" else s"$name-$stage"
    p.resolveSibling(next)
