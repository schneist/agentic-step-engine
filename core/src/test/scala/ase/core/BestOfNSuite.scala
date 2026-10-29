package ase.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** BestOfN (D#63, D#65): eine Mehrheit nur, wenn mehr als die Hälfte aller Kandidaten dieselbe Form hat; Kandidaten
  * ohne Tor 1 zählen mit. Die Form kennt Operationsarten und Kontrollfluss, aber keine Pfade, Grenzen oder Prosa.
  */
class BestOfNSuite extends munit.ScalaCheckSuite:
  private val root = Envelope(Set(OpKind.ReadFile, OpKind.ListDir, OpKind.Hole), ".", 20, 1, Set.empty)
  private def o(k: Int) = Origin.Model(s"plan-1.$k")

  private def check(t: Term): CheckedTerm = DryRun.plan(t, root, CheckConfig.default).fold(vs => fail(vs.toString), identity)

  /** Drei Formen: lesen, listen, zweimal lesen. Der Pfad hängt vom Kandidaten ab und darf die Form nicht ändern. */
  private def candidate(kind: Int, k: Int): CheckedTerm = check(kind match
    case 0 => Term.ReadFile(s"f$k", o(k))
    case 1 => Term.ListDir(s"d$k", o(k))
    case _ => Term.Seq(List(Term.ReadFile(s"a$k", o(k)), Term.ReadFile(s"b$k", o(k))), o(k))
  )

  /** Bis zu 7 Kandidaten, jeder mit einer von drei Formen oder ohne Tor 1 (None). */
  private val choices: Gen[Vector[Option[Int]]] =
    Gen.choose(1, 7).flatMap(n => Gen.listOfN(n, Gen.option(Gen.choose(0, 2))).map(_.toVector))

  property("Mehrheit genau dann, wenn mehr als die Hälfte aller n dieselbe Form hat; gewählt wird ihr erster Kandidat"):
    forAll(choices) { cs =>
      val n = cs.size
      val candidates = cs.zipWithIndex.map((c, k) => c.map(candidate(_, k)))
      val counts = cs.flatten.groupBy(identity).view.mapValues(_.size).toMap
      val winner = counts.find((_, c) => c * 2 > n).map(_._1)
      BestOfN.vote(candidates) match
        case BestOfN.Vote.Majority(chosen, agreeing, of) =>
          val first = cs.indexOf(winner)
          assertEquals(chosen.term, candidate(winner.get, first).term)
          assertEquals((agreeing, of), (counts(winner.get), n))
        case BestOfN.Vote.Split(groups, failed, of) =>
          assertEquals(winner, None)
          assertEquals((failed, of), (cs.count(_.isEmpty), n))
          assertEquals(groups.map(_._2).sum + failed, n)
          assertEquals(groups.map(_._2), groups.map(_._2).sortBy(-_))
    }

  test("Ein Kandidat allein ist eine Mehrheit von 1 aus 1"):
    assertEquals(BestOfN.vote(Vector(Some(candidate(0, 1)))).of, 1)
    assert(BestOfN.vote(Vector(Some(candidate(0, 1)))).isInstanceOf[BestOfN.Vote.Majority])

  test("Zwei gleiche und ein abgelehnter von drei sind eine Mehrheit; ein gültiger von drei ist keine"):
    // CheckedTerm vergleicht nach Identität; verglichen wird der gewählte Term
    BestOfN.vote(Vector(Some(candidate(1, 1)), None, Some(candidate(1, 3)))) match
      case BestOfN.Vote.Majority(chosen, agreeing, of) => assertEquals((chosen.term, agreeing, of), (candidate(1, 1).term, 2, 3))
      case other                                       => fail(other.toString)
    assertEquals(
      BestOfN.vote(Vector(None, Some(candidate(2, 2)), None)),
      BestOfN.Vote.Split(List(BestOfN.shape(candidate(2, 2).term) -> 1), 2, 3)
    )

  test("Die Form übersieht Pfade, Grenzen, Flags und Prosa, nicht aber Operationsarten und erlaubte Operationen (D#65)"):
    def hole(goal: String, ops: Set[OpKind], prefix: String, steps: Int) = Term.Hole(goal, Envelope(ops, prefix, steps, 0, Set.empty), o(1))
    def plan(path: String, h: Term) = Term.Seq(List(Term.Shell("grep", List("-rn", "TODO", path), o(1)), h), o(1))
    val a = plan("src", hole("schreibe", Set(OpKind.WriteFile), "TODO.md", 2))
    val b = plan("src/", hole("lege an", Set(OpKind.WriteFile), ".", 5))
    assertEquals(BestOfN.shape(a), BestOfN.shape(b))
    assertNotEquals(Fingerprint.of(a), Fingerprint.of(b))
    assertNotEquals(BestOfN.shape(a), BestOfN.shape(plan("src", hole("schreibe", Set(OpKind.WriteFile, OpKind.ReadFile), "TODO.md", 2))))
    assertNotEquals(BestOfN.shape(a), BestOfN.shape(Term.Seq(List(Term.Shell("ls", Nil, o(1)), hole("x", Set(OpKind.WriteFile), ".", 2)), o(1))))
