package ase.coretests

/** Compile-Tests außerhalb von ase.core: geprüfte und freigegebene Terme lassen sich nicht fälschen (D#11). */
class VisibilitySuite extends munit.FunSuite:
  test("CheckedTerm hat keinen öffentlichen Konstruktor"):
    assert(compileErrors("""
      import ase.core.*
      val t = Term.Done("x", Origin.Human)
      val e = Envelope(Set.empty, ".", 1, 0, Set.empty)
      new CheckedTerm(t, e, TermHash.of(t, e), 1L, 0)
    """).nonEmpty)

  test("ApprovedTerm hat keinen öffentlichen Konstruktor"):
    assert(compileErrors("""
      import ase.core.*
      def c: CheckedTerm = ???
      new ApprovedTerm(c)
    """).nonEmpty)

  test("ein CheckedTerm ist kein ApprovedTerm, ein Term erst recht nicht"):
    assert(compileErrors("""
      import ase.core.*
      def run(t: ApprovedTerm): Unit = ()
      def c: CheckedTerm = ???
      run(c)
    """).nonEmpty)
    assert(compileErrors("""
      import ase.core.*
      def run(t: ApprovedTerm): Unit = ()
      run(Term.Done("x", Origin.Human))
    """).nonEmpty)

  test("Gegenprobe: der erlaubte Weg kompiliert"):
    assertNoDiff(compileErrors("""
      import ase.core.*
      def run(t: ApprovedTerm): Unit = ()
      def c: CheckedTerm = ???
      Approval.approve(c, c.termHash).foreach(run)
    """), "")
