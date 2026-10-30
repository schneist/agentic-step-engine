package ase.runner

import ase.core.*
import ase.core.ExecSupport.*
import ase.model.{Anthropic, FakeApi}
import cats.effect.IO
import io.circe.parser

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Schritt 4 ohne Netz: Eval 1 durch die ganze Kette mit dem echten Adapter und einem gefälschten Transport.
  * Plan → Tor 1 → Plan-Datei → erneut DryRun → Freigabe mit dem gezeigten Hash → Execute → Fill über Messages →
  * Tor 2 → check.sh. Der Fill sieht die grep-Ausgabe (D#9), sonst könnte er TODO.md nicht schreiben.
  */
class Eval1Suite extends munit.FunSuite:
  override def munitTimeout: Duration = 2.minutes

  private val config = RunnerConfig.default
  private val m = Origin.Model("skript")

  private def repoPath(relative: String): Path =
    Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent).takeWhile(_ != null).map(_.resolve(relative)).find(Files.exists(_))
      .getOrElse(fail(s"$relative nicht gefunden"))

  private val plan = Term.Seq(
    List(
      Term.Shell("grep", List("-rn", "TODO", "src"), m),
      Term.Hole(
        "Schreibe TODO.md mit den TODO-Kommentaren aus der grep-Ausgabe, ohne Zeichenketten im Code",
        Envelope(Set(OpKind.WriteFile), ".", 2, 0, Set.empty),
        m
      ),
      Term.Done("TODO.md geschrieben", m)
    ),
    m
  )

  private val todo = List(
    "- src/main/scala/notes/Main.scala:3 Kommandozeilenargumente statt fester Datei",
    "- src/main/scala/notes/Store.scala:4 Datei sperren, bevor geschrieben wird",
    "- src/main/scala/notes/Store.scala:7 Index nach Datum",
    "- src/main/scala/notes/Store.scala:11 Fehler, wenn Datei fehlt",
    "- src/main/scala/notes/util/Text.scala:5 Wortgrenzen beachten"
  ).mkString("", "\n", "\n")

  private val fill = Term.Seq(List(Term.WriteFile("TODO.md", todo, m), Term.Done("5 TODO-Kommentare", m)), m)

  test("Eval 1: Plan und Fill über den Adapter, zweiphasig, check.sh besteht"):
    assume(Platform.isLinux, s"Execute nur unter Linux (D#29), hier ${Platform.current}")
    val base = tempDir("ase-eval1")
    try
      val ws = base.resolve("work")
      copyTree(repoPath("evals/1-todos/fixture"), ws)
      val root = Codec.decodeEnvelope(parser.parse(Files.readString(repoPath("evals/1-todos/envelope.json"))).toOption.get, Location.root)
        .fold(e => fail(e.message), identity)
      val task = Files.readString(repoPath("evals/1-todos/task.md"))

      var fillPrompts = Vector.empty[String]
      val transport = FakeApi.responding { user =>
        if user.startsWith("# Aufgabe") then FakeApi.submit(plan)
        else
          fillPrompts :+= user
          FakeApi.submit(fill)
      }
      val adapter = Anthropic[IO](transport, config.model, config.check)

      // Phase 1: plan
      val planned = runIO(Planner.run[IO](adapter, task, root, config.check, config.planRounds))
      val checked = planned.checked.getOrElse(fail(planned.log.mkString("\n")))
      val text = PlanFile.encode(PlanFile(task, "default", checked.rootEnvelope, checked.params, checked.term, planned.calls, planned.rejections, planned.unusable, planned.tokens)).spaces2

      // Phase 2: run prüft neu und gibt den Hash frei, den Explain zeigt
      val file = PlanFile.parse(text, config.check.nestingLimit).fold(fail(_), identity)
      val again = DryRun.plan(file.term, file.envelope, config.check, file.params).fold(v => fail(v.toList.map(_.render).mkString), identity)
      assertEquals(again.termHash, checked.termHash)
      val approved = AutoApprover.approve(again, Explain.render(again, config.explain)).fold(fail(_), identity)

      val record = base.resolve("record/eval1.log")
      val result = runIO(Execute.local[IO](approved, ws, adapter, AskHandler.nonInteractive[IO], config.exec.copy(shellTimeout = 30.seconds), Some(record)))
        .fold(e => fail(e.message), identity)
      println(RunLog.render(result.events, RunLog.Footer(file.rejections, Some("record/eval1.log"), file.calls)))

      assertEquals(result.outcome, Outcome.Done)
      assertEquals(fillPrompts.size, 1)
      assert(fillPrompts.head.contains("src/main/scala/notes/Store.scala:7:"), fillPrompts.head)
      val summary = Summary.of(file, approved, result, 1.5, record.toString)
      assertEquals(summary.hcursor.get[Int]("planCalls"), Right(1))
      assertEquals(summary.hcursor.get[Int]("fillCalls"), Right(1))
      assertEquals(summary.hcursor.get[Long]("tokens"), Right(2 * 3120L))
      assertEquals(summary.hcursor.get[List[String]]("changedFiles"), Right(List("TODO.md")))
      assertEquals(summary.hcursor.get[String]("record"), Right(record.toString))
      assertEquals(summary.hcursor.get[Double]("seconds"), Right(1.5))
      assert(summary.hcursor.downField("sourceSha").failed, "ohne sourceSha in der Plan-Datei fehlt das Feld")

      val (code, out) = sh(base, "bash", repoPath("evals/1-todos/check.sh").toString, ws.toString, result.outcome.label)
      assertEquals(code, 0, out)
    finally deleteTree(base)
