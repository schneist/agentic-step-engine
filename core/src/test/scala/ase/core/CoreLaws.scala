package ase.core

import io.circe.Json
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws

/** Invarianten als Gesetze (discipline). */
object CoreLaws extends Laws:
  private val limits = DecodeLimits.all()
  private val terms = Gens.anyTerm(DecodeLimits.DefaultNesting)

  val term: RuleSet = new SimpleRuleSet(
    "Term",
    "JSON-Roundtrip mit origin" -> forAll(terms)(t =>
      Codec.decodeTerm(Codec.term(t), OriginMode.Required, limits) == Right(t)
    ),
    "Modellform ohne origin: Engine stempelt" -> forAll(terms, Gens.origin)((t, o) =>
      Codec.decodeTerm(Codec.term(t, withOrigin = false), OriginMode.Stamp(o), limits) == Right(TestSupport.stamp(t, o))
    ),
    "jeder generierte Term validiert gegen das Schema" -> forAll(terms)(t =>
      TestSupport.schemaErrors(DecodeLimits.DefaultNesting, Json.obj("term" -> Codec.term(t, withOrigin = false))).isEmpty
    ),
    "toTerm(program(t)) == t" -> forAll(terms)(t => AgentAlg.toTerm(AgentAlg.program(t)) == t),
    "Fingerprint ignoriert Prosa und origin" -> forAll(terms, Gens.origin)((t, o) =>
      Fingerprint.of(TestSupport.stamp(t, o)) == Fingerprint.of(t)
    ),
    "Interpreter-Produkt ist kommutativ" -> forAll(terms) { t =>
      val ab = AgentAlg.fromTerm(t)(AgentAlg.product(Cost.interpreter(CostConfig.default), AgentAlg.termWriter))
      val ba = AgentAlg.fromTerm(t)(AgentAlg.product(AgentAlg.termWriter, Cost.interpreter(CostConfig.default)))
      ab.first == ba.second && ab.second == ba.first
    }
  )

  val envelope: RuleSet = new SimpleRuleSet(
    "Envelope",
    "refines ist reflexiv" -> forAll(org.scalacheck.Gen.oneOf(Gens.commands))(c => Attenuation.refines(c, c)),
    "abgeschwächte Hülle besteht die Attenuation" -> forAll(Gens.rootEnvelope.suchThat(_.maxDepth >= 1)) { root =>
      forAll(Gens.childEnvelope(root, 3))(child =>
        Attenuation.check(child, root, Location.root, Attenuation.Mode.Hole).isEmpty &&
          Attenuation.wellFormed(child, Location.root).isEmpty
      )
    },
    "zusätzliche Operation wird abgelehnt" -> forAll(Gens.rootEnvelope) { root =>
      val missing = OpKind.values.toSet -- root.allowedOps
      missing.isEmpty || Attenuation
        .check(root.copy(allowedOps = root.allowedOps + missing.head, maxDepth = root.maxDepth - 1), root, Location.root, Attenuation.Mode.Hole)
        .exists { case _: Violation.WiderOps => true; case _ => false }
    }
  )

  val dryRun: RuleSet = new SimpleRuleSet(
    "DryRun",
    "gültiger Plan wird angenommen" -> forAll(Gens.validPlan) { (env, t) =>
      DryRun.plan(t, env, CheckConfig.default).isRight
    },
    "gültiger Fill wird angenommen, ein leerer abgelehnt (D#56)" -> forAll(Gens.holeEnvelope) { hole =>
      forAll(Gens.validTerm(hole, hole.maxSteps, 3, Origin.Model("fill-1"))) { t =>
        val r = DryRun.fill(t, hole, CheckConfig.default)
        if Cost.of(t).worstCaseSteps == 0 then r.left.exists(_.exists { case _: Violation.EmptyFill => true; case _ => false })
        else r.isRight
      }
    },
    "jeder Ausbruchsversuch wird mit dem passenden Verstoß abgelehnt" -> forAll(Gens.holeEnvelope) { hole =>
      forAll(AdversarialFill.gen(hole)) { (attack, t) =>
        DryRun.fill(t, hole, CheckConfig.default).left.exists(_.exists(AdversarialFill.expected(attack)))
      }
    },
    "Cost und DryRun rechnen denselben schlechtesten Fall" -> forAll(Gens.validPlan) { (env, t) =>
      val check = AgentAlg.fromTerm(t)(AgentAlg.product(DryRun.interpreter, Cost.interpreter(CostConfig.default)))
      val a = check.first(DryRun.Ctx(env, RelPath.parse(env.pathPrefix).getOrElse(Vector.empty), Location.root, None, CheckConfig.default))
      a.worstCase == check.second.worstCaseSteps
    }
  )
