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

  test("D#92: derselbe Term unter einer anderen Konfiguration hat einen anderen Hash, die alte Freigabe gilt nicht"):
    val t = Term.ReadFile("a.txt", Origin.Human)
    def under(sha: Option[String]) = DryRun.plan(t, env, CheckConfig.default.copy(configSha = sha)).toOption.get
    val approvedUnderA = under(Some("a" * 64)).termHash
    // jemand ändert die Konfiguration (etwa "sandbox": false) und plant neu: Term und Hülle gleich, Hash nicht
    val replanned = under(Some("b" * 64))
    assertNotEquals(replanned.termHash, approvedUnderA)
    assertEquals(Approval.approve(replanned, approvedUnderA), Left(ApprovalError.HashMismatch(replanned.termHash, approvedUnderA)))
    // ohne Konfiguration der Hash von D#23
    assertEquals(under(None).termHash, TermHash.of(t, env))
    assertEquals(under(Some("a" * 64)).termHash, TermHash.of(t, env, Implicits.empty, Some("a" * 64)))
