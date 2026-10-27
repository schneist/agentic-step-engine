package ase.core

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
