package ase.core

import cats.data.NonEmptyList

/** Freigabe (D#11): der einzige Weg von einem CheckedTerm zu einem ApprovedTerm. */
object Approval:
  /** Ein geprüfter und freigegebener Term. Nur ihn nimmt Execute an. */
  final class ApprovedTerm private[Approval] (val checked: CheckedTerm):
    def term: Term = checked.term
    def termHash: TermHash = checked.termHash

  enum ApprovalError:
    case HashMismatch(expected: TermHash, confirmed: TermHash)

  /** confirmedHash ist der Hash, den Explain gezeigt hat; der Runner und der Auto-Approver geben ihn weiter. */
  def approve(checked: CheckedTerm, confirmedHash: TermHash): Either[ApprovalError, ApprovedTerm] =
    if confirmedHash == checked.termHash then Right(ApprovedTerm(checked))
    else Left(ApprovalError.HashMismatch(checked.termHash, confirmedHash))

  /** Eine geprüfte Stufenfolge (D#112): jede Stufe für sich mit ihrer Hülle und Konfiguration geprüft, ein Hash über
    * alle. Namen sind eindeutig.
    */
  final class CheckedFolge private (val stages: NonEmptyList[(String, CheckedTerm)]):
    val hash: TermHash = TermHash.ofFolge(stages.toList.map((n, c) => n -> c.termHash))

  object CheckedFolge:
    def of(stages: NonEmptyList[(String, CheckedTerm)]): Either[String, CheckedFolge] =
      val names = stages.toList.map(_._1)
      Either.cond(names.distinct.size == names.size, new CheckedFolge(stages), s"Stufennamen doppelt: ${names.diff(names.distinct).distinct.mkString(", ")}")

  /** Eine freigegebene Stufenfolge. Execute nimmt ihre Stufen nur als ApprovedTerm, und die gibt es nur hier oder aus
    * approve: Die Invariante „nichts läuft ungeprüft“ gilt für jede Stufe (D#11, D#112).
    */
  final class ApprovedFolge private[Approval] (val checked: CheckedFolge, val stages: NonEmptyList[(String, ApprovedTerm)]):
    def hash: TermHash = checked.hash

  /** Eine Freigabe für alle Stufen: confirmedHash ist der Hash der Folge, nicht der einer Stufe. */
  def approveFolge(folge: CheckedFolge, confirmedHash: TermHash): Either[ApprovalError, ApprovedFolge] =
    if confirmedHash == folge.hash then Right(ApprovedFolge(folge, folge.stages.map((n, c) => n -> ApprovedTerm(c))))
    else Left(ApprovalError.HashMismatch(folge.hash, confirmedHash))

