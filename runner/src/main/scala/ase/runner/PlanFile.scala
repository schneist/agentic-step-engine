package ase.runner

import ase.core.*
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.parser

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Was `ase plan` ablegt und `ase run` liest (zweiphasiges CLI): der Term mit origin, die Hülle, gegen die er geprüft
  * wurde, die gebundenen Flow-Parameter, der Fingerabdruck der Runner-Konfiguration und die Kosten des Plans.
  * candidates und agreeing: wie viele Pläne die letzte Runde hatte und wie viele davon den Fingerprint des gewählten
  * teilen (BestOfN, D#63); ohne Abstimmung 1 und 1. sourceSha: SHA-256 der Aufgabe oder des Flows als Text, damit
  * `ase stats` die Läufe einer Fassung zusammenfasst (D#74). implicits: die impliziten Flags des Flows (D#87); term ist
  * schon elaboriert, die Tabelle dient Explain und Tor 2. Version 4; Version 1 (ohne candidates und agreeing),
  * Version 2 (ohne sourceSha) und Version 3 (ohne implicits) werden weiter gelesen.
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
    tokens: Long,
    candidates: Int = 1,
    agreeing: Int = 1,
    sourceSha: Option[String] = None,
    implicits: Implicits = Implicits.empty
)

object PlanFile:
  val Version = 4

  def encode(p: PlanFile): Json = Json.obj(
    "v" -> Json.fromInt(Version),
    "source" -> Json.fromString(p.source),
    "sourceSha" -> p.sourceSha.fold(Json.Null)(Json.fromString),
    "configSha" -> Json.fromString(p.configSha),
    "envelope" -> Codec.envelope(p.envelope),
    "params" -> Json.obj(p.params.toList.sorted.map((k, v) => k -> Json.fromString(v))*),
    "implicits" -> (if p.implicits.isEmpty then Json.Null else Implicits.encode(p.implicits)),
    "term" -> Codec.term(p.term),
    "plan" -> Json.obj(
      "calls" -> Json.fromInt(p.calls),
      "rejections" -> Json.fromInt(p.rejections),
      "unusable" -> Json.fromInt(p.unusable),
      "tokens" -> Json.fromLong(p.tokens),
      "candidates" -> Json.fromInt(p.candidates),
      "agreeing" -> Json.fromInt(p.agreeing)
    )
  ).dropNullValues

  def parse(text: String, nestingLimit: Int): Either[String, PlanFile] =
    for
      json <- parser.parse(text).leftMap(e => s"Kein gültiges JSON: ${e.message}")
      o <- json.asObject.toRight("Plan-Datei: erwartet ein Objekt")
      _ <- o.keys.find(k => !Set("v", "source", "sourceSha", "configSha", "envelope", "params", "implicits", "term", "plan")(k)).map(k => s"Plan-Datei: Feld „$k“ ist nicht vorgesehen").toLeft(())
      v <- o("v").flatMap(_.asNumber).flatMap(_.toInt).toRight("Plan-Datei: v fehlt")
      _ <- Either.cond(v >= 1 && v <= Version, (), s"Plan-Datei Version $v wird nicht unterstützt (erwartet 1 bis $Version)")
      source <- str(o, "source")
      sourceSha <- o("sourceSha").traverse(_.asString.toRight("Plan-Datei: sourceSha ist kein Text"))
      configSha <- str(o, "configSha")
      envelope <- o("envelope").toRight("envelope fehlt").flatMap(Codec.decodeEnvelope(_, Location.root / "envelope").leftMap(_.message))
      params <- o("params").flatMap(_.asObject).toRight("params fehlt").flatMap(
        _.toList.traverse((k, j) => j.asString.map(k -> _).toRight(s"params.$k: erwartet einen Text")).map(_.toMap)
      )
      implicits <- o("implicits").fold(Implicits.empty.asRight[String])(Implicits.decode(_).leftMap(e => s"Plan-Datei: implicits: $e"))
      term <- o("term").toRight("term fehlt").flatMap(Codec.decodeTerm(_, OriginMode.Required, DecodeLimits.all(nestingLimit)).leftMap(_.message))
      plan <- o("plan").flatMap(_.asObject).toRight("plan fehlt")
      calls <- int(plan, "calls")
      rejections <- int(plan, "rejections")
      unusable <- int(plan, "unusable")
      tokens <- plan("tokens").flatMap(_.asNumber).flatMap(_.toLong).toRight("plan.tokens fehlt")
      candidates <- if v == 1 then Right(1) else int(plan, "candidates")
      agreeing <- if v == 1 then Right(1) else int(plan, "agreeing")
    yield PlanFile(source, configSha, envelope, params, term, calls, rejections, unusable, tokens, candidates, agreeing, sourceSha, implicits)

  /** Fingerabdruck der Runner-Konfiguration: SHA-256 der Datei, ohne Datei "default". Plan und Lauf müssen mit
    * derselben Konfiguration geprüft werden (D#40).
    */
  def configSha(configText: Option[String]): String =
    configText.fold("default")(t => MessageDigest.getInstance("SHA-256").digest(t.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString)

  private def str(o: JsonObject, name: String): Either[String, String] =
    o(name).flatMap(_.asString).toRight(s"Plan-Datei: $name fehlt")

  private def int(o: JsonObject, name: String): Either[String, Int] =
    o(name).flatMap(_.asNumber).flatMap(_.toInt).toRight(s"plan.$name fehlt")

/** Was `ase plan --folge` ablegt (D#112): je Stufe Name, Flow, Hülle und Konfiguration als absolute Pfade und die
  * Plan-Datei der Stufe. `ase run` liest Hülle und Konfiguration neu von diesen Pfaden, prüft jede Stufe neu (DryRun)
  * und gibt nur den Hash der Folge frei, der dabei herauskommt. Kein CheckedFolge (D#11).
  */
final case class FolgePlanFile(source: String, sourceSha: String, params: Map[String, String], stufen: List[FolgePlanFile.Stufe])

object FolgePlanFile:
  val Version = 1

  /** plan: die Plan-Datei der Stufe als JSON; gelesen wird sie erst mit der Konfiguration der Stufe (Schachtelungsgrenze). */
  final case class Stufe(name: String, flow: String, envelope: String, config: Option[String], plan: Json)

  def isFolge(text: String): Boolean =
    parser.parse(text).toOption.flatMap(_.hcursor.get[String]("kind").toOption).contains("folge")

  def encode(f: FolgePlanFile): Json = Json.obj(
    "v" -> Json.fromInt(Version),
    "kind" -> Json.fromString("folge"),
    "source" -> Json.fromString(f.source),
    "sourceSha" -> Json.fromString(f.sourceSha),
    "params" -> Json.obj(f.params.toList.sorted.map((k, v) => k -> Json.fromString(v))*),
    "stufen" -> Json.arr(f.stufen.map(s =>
      Json.obj(
        "name" -> Json.fromString(s.name),
        "flow" -> Json.fromString(s.flow),
        "envelope" -> Json.fromString(s.envelope),
        "config" -> s.config.fold(Json.Null)(Json.fromString),
        "plan" -> s.plan
      ).dropNullValues
    )*)
  )

  def parse(text: String): Either[String, FolgePlanFile] =
    def str(o: JsonObject, k: String, at: String) = o(k).flatMap(_.asString).toRight(s"Folge-Plan: $at$k fehlt")
    for
      json <- parser.parse(text).leftMap(e => s"Kein gültiges JSON: ${e.message}")
      o <- json.asObject.toRight("Folge-Plan: erwartet ein Objekt")
      _ <- o.keys.find(k => !Set("v", "kind", "source", "sourceSha", "params", "stufen")(k)).map(k => s"Folge-Plan: Feld „$k“ ist nicht vorgesehen").toLeft(())
      v <- o("v").flatMap(_.asNumber).flatMap(_.toInt).toRight("Folge-Plan: v fehlt")
      _ <- Either.cond(v == Version, (), s"Folge-Plan Version $v wird nicht unterstützt (erwartet $Version)")
      _ <- Either.cond(o("kind").flatMap(_.asString).contains("folge"), (), "Folge-Plan: kind ist nicht folge")
      source <- str(o, "source", "")
      sourceSha <- str(o, "sourceSha", "")
      params <- o("params").flatMap(_.asObject).toRight("Folge-Plan: params fehlt").flatMap(
        _.toList.traverse((k, j) => j.asString.map(k -> _).toRight(s"Folge-Plan: params.$k: erwartet einen Text")).map(_.toMap)
      )
      list <- o("stufen").flatMap(_.asArray).filter(_.nonEmpty).toRight("Folge-Plan: stufen fehlt oder ist leer")
      stufen <- list.toList.zipWithIndex.traverse { (j, i) =>
        val at = s"stufen[$i]."
        for
          s <- j.asObject.toRight(s"Folge-Plan: stufen[$i]: erwartet ein Objekt")
          _ <- s.keys.find(k => !Set("name", "flow", "envelope", "config", "plan")(k)).map(k => s"Folge-Plan: $at$k ist nicht vorgesehen").toLeft(())
          name <- str(s, "name", at)
          flow <- str(s, "flow", at)
          envelope <- str(s, "envelope", at)
          config <- s("config").traverse(_.asString.toRight(s"Folge-Plan: ${at}config: erwartet einen Text"))
          plan <- s("plan").toRight(s"Folge-Plan: ${at}plan fehlt")
        yield Stufe(name, flow, envelope, config, plan)
      }
    yield FolgePlanFile(source, sourceSha, params, stufen)
