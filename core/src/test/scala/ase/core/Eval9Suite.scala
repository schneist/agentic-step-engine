package ase.core

import ase.core.ExecSupport.*
import cats.effect.IO

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Eval 9 mit Skript-Modell (O#39 b): Ein Kompilierfehler verdeckt einen zweiten Fehler. Ohne Abnahme endet der Lauf
  * nach der halben Reparatur mit Ask; mit Abnahme (D#80) bekommt die Reparatur einen zweiten Durchlauf, sieht die
  * Ausgabe des Tests und behebt den zweiten Fehler.
  */
class Eval9Suite extends munit.FunSuite:
  override def munitTimeout: Duration = 2.minutes

  private val config = ExecConfig.default.copy(shellTimeout = 30.seconds)
  private val check = TestSupport.config
  private val m = Origin.Model("skript")

  private def repoPath(relative: String): Path =
    Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent).takeWhile(_ != null).map(_.resolve(relative)).find(Files.exists(_))
      .getOrElse(fail(s"$relative nicht gefunden"))

  private val greeter = "src/Greeter.scala"
  private val farewell = "src/Farewell.scala"
  private val report = "# Bericht\n\n- src/Greeter.scala: unbekannter Name nmae statt name\n- src/Farewell.scala: Punkt statt Ausrufezeichen\n"

  private def done(t: String) = Term.Done(t, m)
  private val diagnose = Term.Seq(List(Term.ReadFile(greeter, m), done("src/Greeter.scala:2: nmae statt name")), m)
  private val fixGreeter = Term.Seq(List(Term.EditFile(greeter, "$nmae", "$name", m), done("nmae → name")), m)
  private val fixFarewell =
    Term.Seq(List(Term.EditFile(farewell, "s\"Goodbye, $name.\"", "s\"Goodbye, $name!\"", m), done("Punkt → Ausrufezeichen")), m)
  private val writeReport = Term.Seq(List(Term.WriteFile("REPORT.md", report, m), done("Bericht geschrieben")), m)

  /** Das Skript-Modell behebt im ersten Durchlauf nur, was die Diagnose sah; erst mit der Meldung der Abnahme den Rest. */
  private def answer(r: FillRequest): Term = r.at match
    case "3"                       => diagnose
    case "4" if r.verified.isEmpty => fixGreeter
    case "4"                       => fixFarewell
    case _                         => writeReport

  private def run(flowFile: String): (Outcome, Vector[FillRequest], Int, String) =
    assume(Platform.isLinux, s"Execute nur unter Linux (D#29), hier ${Platform.current}")
    val base = tempDir("ase-eval9")
    try
      val ws = base.resolve("work")
      copyTree(repoPath("evals/9-zwei-fehler/fixture"), ws)
      val flow = Flow.parse(TestSupport.repoFile(s"evals/9-zwei-fehler/$flowFile")).fold(e => fail(e.toString), identity)
      val checked = DryRun.plan(flow.term, flow.envelope, check).fold(v => fail(v.toList.map(_.render).mkString("\n")), identity)
      val approved = AutoApprover.approve(checked, Explain.render(checked)).fold(fail(_), identity)
      var requests = Vector.empty[FillRequest]
      val model: FillModel[IO] = r => IO { requests :+= r; ModelReply.Answer(submission(answer(r)), 1000) }
      val result = runIO(Execute.local[IO](approved, ws, model, AskHandler.nonInteractive[IO], config, None)).fold(e => fail(e.message), identity)
      val (code, out) = sh(base, "bash", repoPath("evals/9-zwei-fehler/check.sh").toString, ws.toString, result.outcome.label)
      (result.outcome, requests, code, out)
    finally deleteTree(base)

  test("ohne Abnahme: halbe Reparatur, Tests rot, Ask; check.sh scheitert"):
    val (outcome, requests, code, out) = run("ohne-abnahme.json")
    assertEquals(outcome.label, "ask")
    assertEquals(requests.map(_.at), Vector("3", "4"))
    assertEquals(code, 1, out)
    assert(out.contains("erwartet done"), out)

  test("mit Abnahme: zweiter Durchlauf sieht den roten Test, behebt den zweiten Fehler; check.sh besteht"):
    val (outcome, requests, code, out) = run("mit-abnahme.json")
    assertEquals(outcome, Outcome.Done)
    assertEquals(requests.map(r => (r.at, r.round)), Vector(("3", 1), ("4", 1), ("4", 1), ("6", 1)))
    val second = requests(2)
    assert(second.verified.exists(_.contains("Abnahme nach Durchlauf 1 von 3 nicht bestanden")), second.verified)
    assert(second.scope.exists(_.text.contains("bye: erwartet")), second.scope)
    assertEquals(code, 0, out)
