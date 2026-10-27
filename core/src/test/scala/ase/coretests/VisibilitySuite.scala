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

  test("Execute nimmt weder einen CheckedTerm noch einen rohen Term, nur einen ApprovedTerm"):
    assert(compileErrors("""
      import ase.core.*
      def c: CheckedTerm = ???
      def p: Ports[cats.Id] = ???
      Execute.run[cats.Id](c, p, ExecConfig.default)
    """).nonEmpty)
    assert(compileErrors("""
      import ase.core.*
      def p: Ports[cats.Id] = ???
      Execute.run[cats.Id](Term.Done("x", Origin.Human), p, ExecConfig.default)
    """).nonEmpty)
    assert(compileErrors("""
      import ase.core.*
      def c: CheckedTerm = ???
      def m: FillModel[cats.effect.IO] = ???
      Execute.local[cats.effect.IO](c, java.nio.file.Path.of("."), m, AskHandler.nonInteractive, ExecConfig.default, None)
    """).nonEmpty)
    assertNoDiff(compileErrors("""
      import ase.core.*
      def a: ApprovedTerm = ???
      def p: Ports[cats.Id] = ???
      Execute.run[cats.Id](a, p, ExecConfig.default)
    """), "")

  test("Gegenprobe: der erlaubte Weg kompiliert"):
    assertNoDiff(compileErrors("""
      import ase.core.*
      def run(t: ApprovedTerm): Unit = ()
      def c: CheckedTerm = ???
      Approval.approve(c, c.termHash).foreach(run)
    """), "")
