package ase.core

import io.circe.{Json, Printer}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Kanonisches JSON nach RFC 8785 (JCS), soweit Terme es brauchen (D#23): sortierte Schlüssel, kein Leerraum.
  * Terme enthalten keine Gleitkommazahlen; die Zahlenregeln von JCS fallen damit weg.
  */
object Canonical:
  private val printer = Printer.noSpaces.copy(sortKeys = true)

  def print(json: Json): String = printer.print(json)

  def sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

/** SHA-256 über das kanonische JSON von Wurzel-Hülle und Term, origin eingeschlossen (D#23). */
opaque type TermHash = String

object TermHash:
  private val Hex = "[0-9a-f]{64}".r

  def of(term: Term, root: Envelope): TermHash =
    Canonical.sha256Hex(Canonical.print(Json.obj("envelope" -> Codec.envelope(root), "term" -> Codec.term(term))))

  def fromHex(s: String): Option[TermHash] = Option.when(Hex.matches(s))(s)

  extension (h: TermHash)
    def hex: String = h
    def short: String = s"${h.take(6)}…${h.takeRight(4)}"

/** Struktur eines Terms ohne Prosa (goal, summary, question) und ohne origin (D#19). */
opaque type Fingerprint = String

object Fingerprint:
  def of(t: Term): Fingerprint =
    Canonical.sha256Hex(Canonical.print(Codec.term(withoutProse(t), withOrigin = false)))

  extension (f: Fingerprint) def hex: String = f

  private def withoutProse(t: Term): Term = t match
    case Term.Ask(_, o)               => Term.Ask("", o)
    case Term.Done(_, o)              => Term.Done("", o)
    case Term.Hole(_, e, o, v)        => Term.Hole("", e, o, v)
    case Term.Seq(steps, o)           => Term.Seq(steps.map(withoutProse), o)
    case Term.If(p, a, b, o)          => Term.If(p, withoutProse(a), withoutProse(b), o)
    case Term.RepeatUntil(b, u, m, o) => Term.RepeatUntil(withoutProse(b), u, m, o)
    case leaf                         => leaf

def structuralFingerprint(t: Term): Fingerprint = Fingerprint.of(t)

def termHash(t: Term, rootEnvelope: Envelope): TermHash = TermHash.of(t, rootEnvelope)
