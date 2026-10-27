package ase.coretests

/** Compile-Tests außerhalb von ase.core: geprüfte und freigegebene Terme lassen sich nicht fälschen (D#11).
  * Jeder Test prüft die erwartete Meldung, damit ein Tippfehler im Snippet ihn nicht grün färbt.
  */
class VisibilitySuite extends munit.FunSuite:
  test("CheckedTerm hat keinen öffentlichen Konstruktor"):
    val errors = compileErrors("""
      import ase.core.*
      val t = Term.Done("x", Origin.Human)
      val e = Envelope(Set.empty, ".", 1, 0, Set.empty)
      new CheckedTerm(t, e, TermHash.of(t, e), 1L, 0)
    """)
    assert(errors.contains("constructor CheckedTerm in class CheckedTerm cannot be accessed"), errors)

  test("ApprovedTerm hat keinen öffentlichen Konstruktor"):
    val errors = compileErrors("""
      import ase.core.*
      def c: CheckedTerm = ???
      new ApprovedTerm(c)
    """)
    assert(errors.contains("constructor ApprovedTerm in class ApprovedTerm cannot be accessed"), errors)

  test("ein CheckedTerm ist kein ApprovedTerm, ein Term erst recht nicht"):
    val checked = compileErrors("""
      import ase.core.*
      def run(t: ApprovedTerm): Unit = ()
      def c: CheckedTerm = ???
      run(c)
    """)
    assert(checked.contains("Found:    ase.core.CheckedTerm") && checked.contains("Required: ase.core.ApprovedTerm"), checked)
    val raw = compileErrors("""
      import ase.core.*
      def run(t: ApprovedTerm): Unit = ()
      run(Term.Done("x", Origin.Human))
    """)
    assert(raw.contains("Required: ase.core.ApprovedTerm"), raw)

  test("Gegenprobe: der erlaubte Weg kompiliert"):
    assertNoDiff(compileErrors("""
      import ase.core.*
      def run(t: ApprovedTerm): Unit = ()
      def c: CheckedTerm = ???
      Approval.approve(c, c.termHash).foreach(run)
    """), "")
