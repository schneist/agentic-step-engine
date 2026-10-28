package ase.runner

import ase.core.*
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.parser

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Was `ase plan` ablegt und `ase run` liest (zweiphasiges CLI): der Term mit origin, die Hülle, gegen die er geprüft
  * wurde, die gebundenen Flow-Parameter, der Fingerabdruck der Runner-Konfiguration und die Kosten des Plans.
  * Kein CheckedTerm: `ase run` prüft neu (DryRun) und gibt nur frei, was dabei herauskommt (D#11).
  */
final case class PlanFile(
    source: String,
    configSha: String,
    envelope: Envelope,
    params: Map[String, String],
    term: Term,
    calls: Int,
    rejections: Int,
    unusable: Int,
    tokens: Long
)

object PlanFile:
  val Version = 1

  def encode(p: PlanFile): Json = Json.obj(
    "v" -> Json.fromInt(Version),
    "source" -> Json.fromString(p.source),
    "configSha" -> Json.fromString(p.configSha),
    "envelope" -> Codec.envelope(p.envelope),
    "params" -> Json.obj(p.params.toList.sorted.map((k, v) => k -> Json.fromString(v))*),
    "term" -> Codec.term(p.term),
    "plan" -> Json.obj(
      "calls" -> Json.fromInt(p.calls),
      "rejections" -> Json.fromInt(p.rejections),
      "unusable" -> Json.fromInt(p.unusable),
      "tokens" -> Json.fromLong(p.tokens)
    )
  )

  def parse(text: String, nestingLimit: Int): Either[String, PlanFile] =
    for
      json <- parser.parse(text).leftMap(e => s"Kein gültiges JSON: ${e.message}")
      o <- json.asObject.toRight("Plan-Datei: erwartet ein Objekt")
      _ <- o.keys.find(k => !Set("v", "source", "configSha", "envelope", "params", "term", "plan")(k)).map(k => s"Plan-Datei: Feld „$k“ ist nicht vorgesehen").toLeft(())
      v <- o("v").flatMap(_.asNumber).flatMap(_.toInt).toRight("Plan-Datei: v fehlt")
      _ <- Either.cond(v == Version, (), s"Plan-Datei Version $v wird nicht unterstützt (erwartet $Version)")
      source <- str(o, "source")
      configSha <- str(o, "configSha")
      envelope <- o("envelope").toRight("envelope fehlt").flatMap(Codec.decodeEnvelope(_, Location.root / "envelope").leftMap(_.message))
      params <- o("params").flatMap(_.asObject).toRight("params fehlt").flatMap(
        _.toList.traverse((k, j) => j.asString.map(k -> _).toRight(s"params.$k: erwartet einen Text")).map(_.toMap)
      )
      term <- o("term").toRight("term fehlt").flatMap(Codec.decodeTerm(_, OriginMode.Required, DecodeLimits.all(nestingLimit)).leftMap(_.message))
      plan <- o("plan").flatMap(_.asObject).toRight("plan fehlt")
      calls <- int(plan, "calls")
      rejections <- int(plan, "rejections")
      unusable <- int(plan, "unusable")
      tokens <- plan("tokens").flatMap(_.asNumber).flatMap(_.toLong).toRight("plan.tokens fehlt")
    yield PlanFile(source, configSha, envelope, params, term, calls, rejections, unusable, tokens)

  /** Fingerabdruck der Runner-Konfiguration: SHA-256 der Datei, ohne Datei "default". Plan und Lauf müssen mit
    * derselben Konfiguration geprüft werden (D#40).
    */
  def configSha(configText: Option[String]): String =
    configText.fold("default")(t => MessageDigest.getInstance("SHA-256").digest(t.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString)

  private def str(o: JsonObject, name: String): Either[String, String] =
    o(name).flatMap(_.asString).toRight(s"Plan-Datei: $name fehlt")

  private def int(o: JsonObject, name: String): Either[String, Int] =
    o(name).flatMap(_.asNumber).flatMap(_.toInt).toRight(s"plan.$name fehlt")
