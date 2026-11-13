package ase.runner

import ase.core.*
import ase.model.Prompt
import io.circe.Json

/** Herkunft eines Laufs (D#102, O#61): unter welchem System-Prompt, mit welcher Fassung von ase und auf welchem Weg
  * freigegeben. Steht in summary.json neben den Kennzahlen, damit `ase stats` Läufe aus verschiedenen Bedingungen
  * nicht stillschweigend zusammenzählt. Wer freigegeben hat, steht nicht darin: summary.json landet in Datensätzen,
  * die eingecheckt werden können, und ein Name gehört nicht dorthin; der Weg genügt für die Frage, ob ein Mensch
  * am Terminal bestätigt hat.
  */
final case class Provenance(promptSha: String, aseCommit: Option[String], approval: Option[Provenance.Way]):
  def fields: List[(String, Json)] =
    List("promptSha" -> Json.fromString(promptSha)) ++
      aseCommit.map("aseCommit" -> Json.fromString(_)).toList ++
      approval.map(w => "approval" -> Json.fromString(w.label)).toList

object Provenance:
  /** Wie freigegeben wurde (D#11, D#38, D#95): am Terminal bestätigt oder mit --approve HASH. */
  enum Way(val label: String):
    case Terminal extends Way("terminal")
    case Flag extends Way("--approve")

  /** SHA-256 des System-Prompts aus der Prüfkonfiguration, wie im Serien-Schlüssel (D#86). */
  def promptSha(check: CheckConfig): String = Canonical.sha256Hex(Prompt.system(check))

  private val Commit = "^[0-9a-f]{40}(-dirty)?$".r

  /** Die Fassung von ase aus der Systemeigenschaft ase.commit, die bin/ase setzt (Commit, mit „-dirty“, wenn die
    * Quellen davon abweichen). Fehlt sie oder hat sie eine andere Form, fehlt das Feld: lieber keine Angabe als eine
    * falsche.
    */
  def aseCommit(property: Option[String]): Option[String] = property.map(_.trim).filter(Commit.matches)
