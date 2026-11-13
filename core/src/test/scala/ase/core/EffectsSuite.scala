package ase.core

/** Wirkungen nach außen (D#103, O#58): welche Befehle bleiben, wenn ein Lauf scheitert, und wann ein Lauf ohne
  * Menschen (--no-ask) nicht starten darf, weil auf einem Weg kein Ask davor steht.
  */
class EffectsSuite extends munit.FunSuite:
  private val h = Origin.Human
  private def aws(args: List[String], effect: Effect = Effect.Reversible) =
    ArgPolicy("aws", args, Set.empty, Set.empty, Set.empty, Positional.NoArgs, effect = effect)
  private val run = aws(List("ec2", "run-instances"), Effect.Costs)
  private val describe = aws(List("ec2", "describe-instances"))
  private val put = aws(List("s3api", "put-object"), Effect.Irreversible)
  private val config = CheckConfig.default.copy(argPolicies = ArgPolicy.defaults ++ List(run, describe, put))
  private val ec2 = AllowedCommand("aws", List("ec2"))
  private val root = Envelope(Set(OpKind.Shell, OpKind.Ask, OpKind.Hole), ".", 30, 1, Set(AllowedCommand("aws", Nil)))

  private def sh(args: String*) = Term.Shell("aws", args.toList, h)
  private val start = sh("ec2", "run-instances")
  private val look = sh("ec2", "describe-instances")
  private val ask = Term.Ask("Starten?", h)
  private def seq(ts: Term*) = Term.Seq(ts.toList, h)
  private def open(t: Term, c: CheckConfig = config) = Effects.unguarded(t, c).map(_.what)

  test("Wirkung eines Aufrufs und was ein Eintrag der Allowlist erreicht"):
    assertEquals(Effects.of(config.argPolicies, "aws", List("ec2", "run-instances", "--count", "1")), Effect.Costs)
    assertEquals(Effects.of(config.argPolicies, "aws", List("ec2", "describe-instances")), Effect.Reversible)
    assertEquals(Effects.of(config.argPolicies, "grep", List("x", "a")), Effect.Reversible)
    assertEquals(Effects.reachable(config.argPolicies, ec2), List(run))
    assertEquals(Effects.reachable(config.argPolicies, AllowedCommand("aws", List("ec2", "describe-instances"))), Nil)
    assertEquals(Effects.reachable(config.argPolicies, AllowedCommand("aws", Nil)).toSet, Set(run, put))
    // ein Eintrag unter der Policy erreicht sie (längstes Präfix), wenn keine Policy genau ihn trägt
    val broad = config.copy(argPolicies = ArgPolicy.defaults :+ aws(List("ec2"), Effect.Costs))
    assertEquals(Effects.reachable(broad.argPolicies, AllowedCommand("aws", List("ec2", "run-instances"))).map(_.prefixArgs), List(List("ec2")))

  test("ohne Ask davor offen, mit Ask davor bewacht, Lesen nie"):
    assertEquals(open(seq(look, start)), List("aws ec2 run-instances"))
    assertEquals(open(seq(look, ask, start)), Nil)
    assertEquals(open(seq(start, ask)), List("aws ec2 run-instances"))
    assertEquals(open(look), Nil)

  test("If: bewacht nur, wenn beide Zweige fragen; RepeatUntil: der Rumpf läuft mindestens einmal"):
    val p = Pred.LastExitCode(0)
    assertEquals(open(seq(Term.If(p, ask, Term.Done("x", h), h), start)), List("aws ec2 run-instances"))
    assertEquals(open(seq(Term.If(p, ask, ask, h), start)), Nil)
    // eine Wirkung im Zweig ohne Ask
    assertEquals(open(Term.If(p, seq(ask, start), start, h)), List("aws ec2 run-instances"))
    assertEquals(open(seq(Term.RepeatUntil(seq(look, ask), p, 3, h), start)), Nil)
    assertEquals(open(Term.RepeatUntil(seq(start, ask), p, 3, h)), List("aws ec2 run-instances"))

  test("Agentenschritt: zählt mit allem, was seine Hülle an Wirkung erlaubt; Abnahme wie ein Schritt"):
    val hull = Envelope(Set(OpKind.Shell), ".", 5, 0, Set(ec2))
    val hole = Term.Hole("Maschine starten, wenn der Preis passt", hull, h)
    assert(open(hole).exists(_.startsWith("aws ec2 run-instances (Agentenschritt „Maschine starten")), open(hole).toString)
    assertEquals(open(seq(ask, hole)), Nil)
    val reading = Term.Hole("Zustand lesen", hull.copy(shellAllowlist = Set(AllowedCommand("aws", List("ec2", "describe-instances")))), h)
    assertEquals(open(reading), Nil)
    val withVerify = Term.Hole("lesen", Envelope(Set(OpKind.ReadFile), ".", 3, 0, Set.empty), h,
      Some(Verify("aws", List("s3api", "put-object"), Pred.LastExitCode(0), 1)))
    assertEquals(open(withVerify), List("aws s3api put-object"))

  test("unattended erlaubt einzelne Befehle ohne Frage, im Schritt und in der Hülle"):
    val allowed = config.copy(unattended = List(AllowedCommand("aws", List("ec2", "run-instances"))))
    assertEquals(open(seq(look, start), allowed), Nil)
    val hull = Envelope(Set(OpKind.Shell), ".", 5, 0, Set(AllowedCommand("aws", Nil)))
    // run-instances ist erlaubt, put-object nicht
    assertEquals(open(Term.Hole("alles", hull, h), allowed).map(_.split(" \\(").head), List("aws s3api put-object"))

  test("Explain: Wirkung am Schritt, in der Hülle und unten mit --no-ask; ohne Wirkung keine Zeile"):
    val t = seq(look, Term.If(Pred.LastOutputEmpty, ask, Term.Done("läuft schon", h), h), start)
    val checked = DryRun.plan(t, root, config).fold(vs => fail(vs.toList.mkString("\n")), identity)
    val out = Explain.render(checked)
    assert(out.contains("führt aus: aws ec2 run-instances (kostet Geld)"), out)
    assert(!out.contains("describe-instances (kostet"), out)
    assert(out.contains("wirkung   aws ec2 run-instances (kostet Geld)"), out)
    assert(out.contains("--no-ask   abgelehnt: ohne Ask davor aws ec2 run-instances"), out)
    val guarded = DryRun.plan(seq(look, ask, start), root, config).fold(vs => fail(vs.toList.mkString("\n")), identity)
    assert(Explain.render(guarded).contains("--no-ask   möglich"), Explain.render(guarded))
    val hole = Term.Hole("starten", Envelope(Set(OpKind.Shell), ".", 5, 0, Set(ec2)), h)
    val withHole = DryRun.plan(hole, root, config).fold(vs => fail(vs.toList.mkString("\n")), identity)
    assert(Explain.render(withHole).contains("Hinweis: darf nach außen wirken (aws ec2 run-instances: kostet Geld)"), Explain.render(withHole))
    val plain = DryRun.plan(look, root, config).fold(vs => fail(vs.toList.mkString("\n")), identity)
    assert(!Explain.render(plain).contains("wirkung"), Explain.render(plain))
    assert(!Explain.render(plain).contains("--no-ask"), Explain.render(plain))
