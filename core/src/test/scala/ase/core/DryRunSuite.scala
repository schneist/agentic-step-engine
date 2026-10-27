package ase.core

class DryRunSuite extends munit.FunSuite:
  private val h = Origin.Human
  private val root = Envelope(
    OpKind.values.toSet,
    ".",
    50,
    2,
    Set(AllowedCommand("git", List("status")), AllowedCommand("git", List("diff")), AllowedCommand("grep", Nil), AllowedCommand("ls", Nil))
  )

  private def violations(t: Term, env: Envelope = root): List[Violation] =
    DryRun.plan(t, env, CheckConfig.default).left.toOption.map(_.toList).getOrElse(Nil)

  private def hole(e: Envelope) = Term.Hole("ziel", e, h)
  private val narrow = Envelope(Set(OpKind.ReadFile), "src", 3, 0, Set.empty)

  test("gültiger Plan ergibt einen CheckedTerm mit Hash, schlechtestem Fall und Tiefe"):
    val t = Term.Seq(List(Term.Shell("git", List("status", "--porcelain"), h), hole(narrow)), h)
    val checked = DryRun.plan(t, root, CheckConfig.default).toOption.get
    assertEquals(checked.worstCaseSteps, 1L + 1 + 3)
    assertEquals(checked.depth, 1)
    assertEquals(checked.termHash, TermHash.of(t, root))

  test("Loch mit zu weiter Hülle wird abgelehnt"):
    val wide = narrow.copy(allowedOps = Set(OpKind.ReadFile, OpKind.EditFile))
    val v = violations(hole(wide), root.copy(allowedOps = root.allowedOps - OpKind.EditFile))
    assert(v.exists { case _: Violation.WiderOps => true; case _ => false }, v)

  test("Loch mit maxDepth ≥ Eltern wird abgelehnt"):
    val v = violations(hole(narrow.copy(maxDepth = 2)))
    assertEquals(v, List(Violation.DepthNotSmaller(Location.root / "envelope", 2, 2, true)))

  test("unlösbar beschränkte Löcher werden abgelehnt"):
    for e <- List(narrow.copy(maxSteps = 0), narrow.copy(allowedOps = Set.empty), narrow.copy(allowedOps = Set(OpKind.Shell))) do
      assert(violations(hole(e)).exists { case _: Violation.IllFormed => true; case _ => false }, e)

  test("Shell: git push abgelehnt, git status angenommen"):
    assert(violations(Term.Shell("git", List("push"), h)).exists { case _: Violation.ShellNotAllowed => true; case _ => false })
    assertEquals(violations(Term.Shell("git", List("status"), h)), Nil)

  test("Shell-Attenuation: git status unter git zulässig, git unter git status nicht"):
    val gitAll = root.copy(shellAllowlist = Set(AllowedCommand("git", Nil)))
    val statusOnly = narrow.copy(allowedOps = Set(OpKind.Shell), pathPrefix = ".", shellAllowlist = Set(AllowedCommand("git", List("status"))))
    assertEquals(violations(hole(statusOnly), gitAll), Nil)
    val back = statusOnly.copy(shellAllowlist = Set(AllowedCommand("git", Nil)))
    val rootStatus = root.copy(shellAllowlist = Set(AllowedCommand("git", List("status"))))
    assert(violations(hole(back), rootStatus).exists { case _: Violation.WiderShell => true; case _ => false })

  test("Argument-Policy"):
    // unter docs nur Befehle, die Pfade nehmen; git status berührt das ganze Arbeitsverzeichnis (D#27, D#31)
    val inDocs = root.copy(pathPrefix = "docs", shellAllowlist = Set(AllowedCommand("grep", Nil), AllowedCommand("ls", Nil)))
    def rejected(t: Term, env: Envelope = root) = violations(t, env).nonEmpty
    assert(rejected(Term.Shell("grep", List("-rn", "token", "src"), h), inDocs), "Pfad außerhalb des Präfix")
    assert(rejected(Term.Shell("grep", List("-r", "token"), h), inDocs), "ohne Pfad gilt . (D#27)")
    assert(rejected(Term.Shell("git", List("diff", "--output=x"), h)))
    assert(rejected(Term.Shell("git", List("diff", "--ext-diff"), h)))
    assert(rejected(Term.Shell("ls", List("-R", "."), h)), "unbekanntes Flag")
    assert(rejected(Term.Shell("grep", List("x", "../geheim"), h)))
    assertEquals(violations(Term.Shell("grep", List("-rn", "parseConfig", "docs/"), h), inDocs), Nil)
    assertEquals(violations(Term.Shell("git", List("diff", "--stat", "--", "src"), h)), Nil)

  test("Pfade: absolut, außerhalb, Präfix"):
    assert(violations(Term.ReadFile("/etc/passwd", h)).exists { case _: Violation.InvalidPath => true; case _ => false })
    assert(violations(Term.ReadFile("src/../../x", h)).exists { case _: Violation.InvalidPath => true; case _ => false })
    assert(violations(Term.MoveFile("src/a", "docs/b", h), root.copy(pathPrefix = "src")).exists {
      case Violation.PathOutsidePrefix(_, "docs/b", _) => true; case _ => false
    })
    assertEquals(violations(Term.MoveFile("docs/setup.md", "docs/installation.md", h)), Nil)

  test("Prädikat, das nie wahr werden kann, ist ein Verstoß (D#20)"):
    val t = Term.Seq(List(Term.ListDir("src", h), Term.If(Pred.LastExitCode(0), Term.Done("a", h), Term.Done("b", h), h)), h)
    assertEquals(violations(t), List(Violation.PredicateNeverTrue(Location.root.at("steps", 1) / "pred", Pred.LastExitCode(0))))

  test("leeres Seq reicht das vorige Ergebnis durch"):
    def after(first: Term) =
      Term.Seq(List(first, Term.Seq(Nil, h), Term.If(Pred.LastOutputEmpty, Term.Done("a", h), Term.Done("b", h), h)), h)
    assertEquals(violations(after(Term.Shell("git", List("status"), h))), Nil)
    assert(violations(after(Term.WriteFile("neu.txt", "x", h))).exists { case _: Violation.PredicateNeverTrue => true; case _ => false })

  test("RepeatUntil braucht max ≥ 1"):
    assert(violations(Term.RepeatUntil(Term.Done("x", h), Pred.LastOutputEmpty, 0, h)).exists {
      case _: Violation.InvalidRepeat => true; case _ => false
    })

  test("schlechtester Fall über dem Budget wird abgelehnt"):
    val t = Term.RepeatUntil(Term.Seq(List.fill(5)(Term.Done("x", h)), h), Pred.LastOutputEmpty, 11, h)
    assertEquals(violations(t), List(Violation.StepBudgetExceeded(Location.root, 55, 50)))
