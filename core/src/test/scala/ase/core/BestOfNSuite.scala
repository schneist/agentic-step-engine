package ase.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** BestOfN (D#63): eine Mehrheit nur, wenn mehr als die Hälfte aller Kandidaten denselben Fingerprint hat; Kandidaten
  * ohne Tor 1 zählen mit. Kandidaten unterscheiden sich hier nur im Pfad, der im Fingerprint steht, und im origin, der
  * nicht darin steht (D#19).
  */
class BestOfNSuite extends munit.ScalaCheckSuite:
  private val root = Envelope(Set(OpKind.ReadFile), ".", 5, 1, Set.empty)

  private def candidate(path: Int, k: Int): CheckedTerm =
    DryRun.plan(Term.ReadFile(s"f$path", Origin.Model(s"plan-1.$k")), root, CheckConfig.default).fold(vs => fail(vs.toString), identity)

  private def path(c: CheckedTerm): String = c.term match
    case Term.ReadFile(p, _) => p
    case other               => fail(other.toString)

  /** Bis zu 7 Kandidaten, jeder mit einem von drei Pfaden oder ohne Tor 1 (None). */
  private val choices: Gen[Vector[Option[Int]]] =
    Gen.choose(1, 7).flatMap(n => Gen.listOfN(n, Gen.option(Gen.choose(0, 2))).map(_.toVector))

  property("Mehrheit genau dann, wenn mehr als die Hälfte aller n übereinstimmt; gewählt wird ihr erster Kandidat"):
    forAll(choices) { cs =>
      val n = cs.size
      val candidates = cs.zipWithIndex.map((c, k) => c.map(candidate(_, k)))
      val counts = cs.flatten.groupBy(identity).view.mapValues(_.size).toMap
      val winner = counts.find((_, c) => c * 2 > n).map(_._1)
      BestOfN.vote(candidates) match
        case BestOfN.Vote.Majority(chosen, agreeing, of) =>
          assertEquals(winner.map(w => s"f$w"), Some(path(chosen)))
          assertEquals((agreeing, of), (counts(winner.get), n))
          // der erste Kandidat der Mehrheit, in der Reihenfolge der Anfragen
          assertEquals(chosen.term.origin, Origin.Model(s"plan-1.${cs.indexOf(winner)}"))
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
      BestOfN.Vote.Split(List(Fingerprint.of(candidate(2, 2).term) -> 1), 2, 3)
    )
