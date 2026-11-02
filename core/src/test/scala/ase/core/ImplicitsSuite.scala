package ase.core

import ase.core.ExecSupport.*
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Pflicht-Flags und implizite Flags (D#87): Elaboration mit ihren Gesetzen, Tor 1 und 2, Explain, Lauf-Log, Replay. */
class ImplicitsSuite extends munit.ScalaCheckSuite:
  private val h = Origin.Human
  private val fill = Origin.Model("fill")

  private def aws(sub: List[String], valued: Set[String], values: Map[String, ValueRule] = Map.empty) =
    ArgPolicy("aws", sub, Set.empty, valued + "--profile" + "--region", Set.empty, Positional.NoArgs, values)

  private val sts = aws(List("sts", "get-caller-identity"), Set("--output"))
  private val describe = aws(List("ec2", "describe-instances"), Set("--filters"))
  private val policies = ArgPolicy.defaults ++ List(sts, describe)
  private val check = CheckConfig.default.copy(argPolicies = policies, required = Map("aws" -> Set("--profile", "--region")))
  private val implicits = Implicits(Map("aws" -> Map("--region" -> "eu-central-1", "--profile" -> "cfd")))
  private val root = Envelope(OpKind.values.toSet, ".", 30, 2, Set(AllowedCommand("aws", Nil)))

  private def shell(args: String*) = Term.Shell("aws", args.toList, h)
  private def elaborate(t: Term) = Implicits.elaborate(t, implicits, policies)
  private def violations(t: Term) = DryRun.plan(t, root, check).fold(_.toList, _ => Nil)

  // ---------- Elaboration

  test("elaborate: Paare nach Flag-Namen sortiert direkt hinter die Pflicht-Argumente der Policy, auch in verify"):
    val verify = Verify("aws", List("ec2", "describe-instances", "--filters", "x"), Pred.LastExitCode(0), 2)
    val holeEnv = root.copy(maxDepth = 1, maxSteps = 5)
    val hole = Term.Hole("starte", holeEnv, h, Some(verify))
    val t = Term.Seq(List(shell("sts", "get-caller-identity", "--output", "text"), hole), h)
    val e = elaborate(t).fold(v => fail(v.toList.map(_.render).mkString("\n")), identity)
    val expected = Term.Seq(
      List(
        shell("sts", "get-caller-identity", "--profile", "cfd", "--region", "eu-central-1", "--output", "text"),
        Term.Hole("starte", holeEnv, h, Some(verify.copy(args = List("ec2", "describe-instances", "--profile", "cfd", "--region", "eu-central-1", "--filters", "x"))))
      ),
      h
    )
    assertEquals(e, expected)
    assertEquals(violations(e), Nil)
    // die Reihenfolge in der Tabelle ändert nichts am Ergebnis und damit am Hash
    val other = Implicits(Map("aws" -> Map("--profile" -> "cfd", "--region" -> "eu-central-1")))
    assertEquals(Implicits.elaborate(t, other, policies), Right(e))

  test("elaborate: ein implizites Flag im Eingabeterm ist ein Fehler, auch als --profile=x, mit Ort"):
    val t = Term.Seq(List(shell("sts", "get-caller-identity"), shell("sts", "get-caller-identity", "--profile=evil")), h)
    assertEquals(
      elaborate(t).left.map(_.toList),
      Left(List(Violation.ImplicitFlagGiven(Location.root.at("steps", 1) / "args", "aws", "--profile")))
    )
    val v = Verify("aws", List("ec2", "describe-instances", "--region", "us-east-1"), Pred.LastExitCode(0), 1)
    val inVerify = elaborate(Term.Hole("x", root.copy(maxDepth = 1, maxSteps = 5), h, Some(v))).left.map(_.toList.map(_.loc.show))
    assertEquals(inVerify, Left(List("$.verify.args")))

  test("elaborate: ohne Tabelle unverändert; andere Programme und Aufrufe ohne Policy bleiben, wie sie sind"):
    val t = Term.Seq(List(Term.Shell("git", List("status"), h), shell("s3", "ls")), h)
    assertEquals(Implicits.elaborate(t, Implicits.empty, policies), Right(t))
    assertEquals(elaborate(t), Right(t))

  // ---------- Tor 1: Pflicht

  test("DryRun: ohne Pflicht-Flag abgelehnt, unter jeder Policy des Programms; doppelt abgelehnt"):
    assertEquals(
      violations(shell("sts", "get-caller-identity", "--region", "eu-central-1")),
      List(Violation.RequiredFlagMissing(Location.root / "args", "aws", "--profile"))
    )
    assert(violations(shell("ec2", "describe-instances")).count(_.isInstanceOf[Violation.RequiredFlagMissing]) == 2)
    val twice = violations(shell("sts", "get-caller-identity", "--profile", "a", "--region", "r", "--profile", "b"))
    assertEquals(twice, List(Violation.ShellArgRejected(Location.root / "args", "aws", "--profile", "nur einmal erlaubt")))
    // die Abnahme ist ein Aufruf wie jeder andere
    val v = Verify("aws", List("ec2", "describe-instances"), Pred.LastExitCode(0), 1)
    val vs = violations(Term.Hole("x", root.copy(maxDepth = 1, maxSteps = 5), h, Some(v)))
    assert(vs.exists { case Violation.RequiredFlagMissing(loc, _, _) => loc.show == "$.verify.args"; case _ => false }, vs)

  test("parse: ein Flag mit Wert zweimal ist für jedes Programm abgelehnt, nicht nur mit Pflicht"):
    val log = ArgPolicy.defaults.find(_.prefixArgs == List("log")).get
    assertEquals(ArgPolicy.parse(log, List("log", "-n", "3", "-n", "5")).rejected, List(("-n", "nur einmal erlaubt")))
    assertEquals(ArgPolicy.parse(log, List("log", "-n", "3")).valued, Set("-n"))

  // ---------- Gesetze

  private val subcommands = Gen.oneOf(List("sts", "get-caller-identity"), List("ec2", "describe-instances"), List("s3", "ls"))
  private val token = Gen.oneOf("--output", "text", "--filters", "Name=x", "--query", "a", "--profile", "--region=x", "-")
  private val call: Gen[Term] = Gen.zip(subcommands, Gen.choose(0, 3).flatMap(Gen.listOfN(_, token))).map((s, a) => shell((s ++ a)*))
  private val verify: Gen[Verify] = call.map { case Term.Shell(c, a, _) => Verify(c, a, Pred.LastExitCode(0), 1); case _ => sys.error("") }
  private def terms(level: Int): Gen[Term] =
    val leaf = Gen.frequency(4 -> call, 1 -> verify.map(v => Term.Hole("x", root, h, Some(v))), 1 -> Gen.const(Term.Done("d", h)))
    if level == 0 then leaf
    else
      val below = Gen.lzy(terms(level - 1))
      Gen.frequency(
        3 -> leaf,
        1 -> Gen.listOfN(3, below).map(Term.Seq(_, h)),
        1 -> Gen.zip(below, below).map((a, b) => Term.If(Pred.LastExitCode(0), a, b, h)),
        1 -> below.map(Term.RepeatUntil(_, Pred.LastExitCode(0), 2, h))
      )

  private def calls(t: Term): List[(String, List[String])] = t match
    case Term.Shell(c, a, _)          => List((c, a))
    case Term.Hole(_, _, _, v)        => v.toList.map(x => (x.cmd, x.args))
    case Term.Seq(s, _)               => s.flatMap(calls)
    case Term.If(_, a, b, _)          => calls(a) ++ calls(b)
    case Term.RepeatUntil(b, _, _, _) => calls(b)
    case _                            => Nil

  private def explicit(t: Term): Boolean =
    calls(t).exists((_, args) => args.exists(a => a == "--profile" || a == "--region" || a.startsWith("--profile=") || a.startsWith("--region=")))

  property("Gesetz: elaborate scheitert genau dann, wenn ein implizites Flag schon im Term steht; sonst strip(elaborate(t)) == t"):
    forAll(terms(3)) { t =>
      elaborate(t) match
        case Left(_)  => explicit(t)
        case Right(e) => !explicit(t) && Implicits.strip(e, implicits, policies) == t
    }

  property("Gesetz: jeder elaborierte Aufruf mit Policy trägt jedes Pflicht-Flag genau einmal, mit dem Wert der Tabelle"):
    forAll(terms(3)) { t =>
      elaborate(t).forall(e =>
        calls(e).forall { (cmd, args) =>
          ArgPolicy.find(policies, cmd, args).forall { p =>
            val after = args.drop(p.prefixArgs.size)
            after.count(_ == "--profile") == 1 && after.count(_ == "--region") == 1 &&
            after.take(4) == List("--profile", "cfd", "--region", "eu-central-1")
          }
        }
      )
    }

  property("Gesetz: gleiche Eingabe, gleicher Hash"):
    forAll(terms(3)) { t =>
      (elaborate(t), elaborate(t)) match
        case (Right(a), Right(b)) => TermHash.of(a, root) == TermHash.of(b, root)
        case (a, b)               => a == b
    }

  // ---------- Explain

  private def checked(t: Term, i: Implicits = implicits) =
    DryRun.plan(t, root, check, Map.empty, i).fold(v => fail(v.toList.map(_.render).mkString("\n")), identity)

  test("Explain: Bindung einmal im Kopf, Schritte ohne die Paare, eine abweichende Zeile vollständig"):
    val t = elaborate(Term.Seq(List(shell("sts", "get-caller-identity", "--output", "text"), Term.Done("ok", h)), h)).toOption.get
    val text = Explain.render(checked(t))
    assert(text.contains("implizit  aws: --profile cfd --region eu-central-1 (in jedem Aufruf)"), text)
    assert(text.contains("führt aus: aws sts get-caller-identity --output text"), text)
    assert(!text.linesIterator.filter(_.contains("führt aus")).exists(_.contains("--profile")), text)
    // ein Term, der nicht aus dieser Tabelle elaboriert wurde (etwa eine veränderte Plan-Datei), zeigt sich ganz
    val forged = Term.Seq(List(shell("sts", "get-caller-identity", "--profile", "evil", "--region", "eu-central-1"), Term.Done("ok", h)), h)
    val shown = Explain.render(checked(forged))
    assert(shown.contains("führt aus: aws sts get-caller-identity --profile evil --region eu-central-1"), shown)

  test("Explain: ein Agentenschritt, der aws darf, nennt die impliziten Flags; ohne Tabelle keine Zeile"):
    val hole = Term.Hole("starte", Envelope(Set(OpKind.Shell), ".", 3, 0, Set(AllowedCommand("aws", List("ec2")))), h)
    val text = Explain.render(checked(Term.Seq(List(hole), h)))
    assert(text.contains("implizit in jedem Aufruf: aws --profile cfd --region eu-central-1"), text)
    val plain = Explain.render(checked(Term.Seq(List(hole), h), Implicits.empty))
    assert(!plain.contains("implizit"), plain)

  // ---------- Tor 2, Lauf-Log, Replay

  private val holeEnv = Envelope(Set(OpKind.Shell), ".", 3, 0, Set(AllowedCommand("aws", List("ec2"))))

  private final class Sequence(terms: Term*):
    var requests = Vector.empty[FillRequest]
    val model: FillRequest => ModelReply = r =>
      requests :+= r
      terms.lift(requests.size - 1).fold(ModelReply.Failed("keine Antwort mehr", 0))(t => ModelReply.Answer(submission(t), 100))

  test("Tor 2: ein Fill mit implizitem Flag wird abgelehnt, einer ohne läuft mit den Paaren; Lauf-Log kürzt, Replay gleich"):
    val t = Term.Seq(List(Term.Hole("prüfe die Maschinen", holeEnv, h), Term.Done("fertig", h)), h)
    val withFlag = Term.Seq(List(Term.Shell("aws", List("ec2", "describe-instances", "--profile", "evil"), fill), Term.Done("d", fill)), fill)
    val without = Term.Seq(List(Term.Shell("aws", List("ec2", "describe-instances"), fill), Term.Done("d", fill)), fill)
    val s = Sequence(withFlag, without)
    val c = checked(elaborate(t).toOption.get)
    val approved = Approval.approve(c, c.termHash).fold(e => fail(e.toString), identity)
    val (_, r) = Execute.run[W](approved, ports(fakeTools(policies), s.model, _ => None), ExecConfig.default).run(Map.empty).value
    assertEquals(r.outcome, Outcome.Done)
    val rejected = r.events.collect { case Event.FillRejected(_, _, gate, _, reasons, _) => (gate, reasons) }
    assertEquals(rejected.size, 1)
    assertEquals(rejected.head._1, Gate.DryRun)
    assert(rejected.head._2.head.contains("--profile setzt die Engine"), rejected)
    val ran = r.events.collect { case Event.Step(_, Call.Shell(cmd, args), _) => (cmd :: args).mkString(" ") }
    assertEquals(ran, Vector("aws ec2 describe-instances --profile cfd --region eu-central-1"))
    // die Anfrage nennt die Paare, weil die Hülle aws erlaubt
    assertEquals(s.requests.head.implicits, implicits)
    val shown = (cmd: String, args: List[String]) => Implicits.stripArgs(cmd, args, implicits, policies)
    val log = RunLog.render(r.events, RunLog.Footer(0, None), shown)
    assert(log.linesIterator.exists(l => l.contains("shell  aws ec2 describe-instances") && !l.contains("--profile")), log)
    assert(RecordLog.render(r.events).contains("--profile"))
    assertEquals(Replay.verify(approved, r.events, ExecConfig.default).map(_.events), Right(r.events))
