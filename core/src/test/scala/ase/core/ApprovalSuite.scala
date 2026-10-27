package ase.core

class ApprovalSuite extends munit.FunSuite:
  private val env = Envelope(Set(OpKind.ReadFile), ".", 3, 0, Set.empty)
  private val checked = DryRun.plan(Term.ReadFile("a.txt", Origin.Human), env, CheckConfig.default).toOption.get

  test("Freigabe mit dem gezeigten Hash ergibt einen ApprovedTerm"):
    val approved = Approval.approve(checked, checked.termHash).toOption.get
    assertEquals(approved.termHash, checked.termHash)

  test("Freigabe mit einem anderen Hash scheitert"):
    val other = TermHash.of(Term.ReadFile("b.txt", Origin.Human), env)
    assertEquals(Approval.approve(checked, other), Left(ApprovalError.HashMismatch(checked.termHash, other)))
