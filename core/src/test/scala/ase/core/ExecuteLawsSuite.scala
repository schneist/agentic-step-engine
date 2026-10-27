package ase.core

import ase.core.ExecSupport.*
import cats.effect.IO
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Die Gesetze von Execute gegen das echte Dateisystem, mit Symlinks nach außen. Nur unter Linux (D#29). */
class ExecuteLawsSuite extends munit.ScalaCheckSuite:
  override def munitTimeout: Duration = 10.minutes
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(40)

  private val check = CheckConfig.default
  private val config = ExecConfig.default.copy(shellTimeout = 20.seconds)
  private val segments = List("src", "docs", "lib", "a", "b")

  /** Verzeichnisse aus den Segmenten der Generatoren bis Tiefe 3, je mit x.txt; Symlinks unter jedem Präfix, das
    * die Generatoren bilden (., src, src/src, src/src/src).
    */
  private def fixture(ws: Path, outside: Path): Unit =
    val dirs = (0 to 3).flatMap(n => (1 to n).foldLeft(List(Vector.empty[String]))((acc, _) => acc.flatMap(d => segments.map(d :+ _))))
    dirs.foreach { d =>
      val dir = d.foldLeft(ws)(_.resolve(_))
      Files.createDirectories(dir)
      Files.writeString(dir.resolve("x.txt"), "alt\n")
    }
    (0 to 3).map(n => Vector.fill(n)("src")).foreach { d =>
      val dir = d.foldLeft(ws)(_.resolve(_))
      Files.createSymbolicLink(dir.resolve("link-out"), outside)
      Files.createSymbolicLink(dir.resolve("link-file"), outside.resolve("secret.txt"))
    }

  private val all = Envelope(OpKind.values.toSet, ".", 1000, 0, Set.empty)

  test("Symlink nach außen: Tor 2 lässt den Fill durch, Execute fängt ihn zur Laufzeit, neuer Fill (D#15)"):
    assume(Platform.isLinux, s"Execute nur unter Linux (D#29), hier ${Platform.current}")
    val base = tempDir("ase-link")
    try
      val ws = Files.createDirectories(base.resolve("ws"))
      val outside = Files.createDirectories(base.resolve("outside"))
      Files.writeString(outside.resolve("secret.txt"), "geheim\n")
      fixture(ws, outside)
      val h = Origin.Human
      val hole = Envelope(Set(OpKind.ReadFile, OpKind.EditFile), "src", 4, 0, Set.empty)
      val approved = approve(Term.Hole("lies", hole, h), Envelope(OpKind.values.toSet - OpKind.Shell, ".", 10, 1, Set.empty), check)
      val m = Origin.Model("x")
      val model: FillRequest => ModelReply = r =>
        if r.attempt == 1 then ModelReply.Answer(submission(Term.EditFile("src/link-file", "geheim", "ausbruch", m)), 1)
        else ModelReply.Answer(submission(Term.Done("außerhalb, nicht geändert", m)), 1)
      val tools = runIO(LocalTools.open[IO](ws, config)).fold(e => fail(e.message), identity)
      val r = runIO(Execute.run[IO](approved, ports[IO](tools, model, _ => None), config))
      assertEquals(r.outcome, Outcome.Done)
      assertEquals(r.events.collect { case e: Event.FillRejected => e }, Vector.empty)
      assertEquals(r.events.collect { case Event.Step(_, _, reply) => reply }, Vector(Reply(Nil, Left(ToolError.OutsideWorkspace("src/link-file")), "")))
      assertEquals(r.events.collect { case e: Event.FillFailed => e.error }, Vector(ToolError.OutsideWorkspace("src/link-file")))
      assertEquals(Files.readString(outside.resolve("secret.txt")), "geheim\n")
    finally deleteTree(base)

  property("AdversarialFill mit Symlinks: kein Syscall außerhalb der Hüllen, Replay gleich, Schreibvorgänge ergeben denselben Baum"):
    assume(Platform.isLinux, s"Execute nur unter Linux (D#29), hier ${Platform.current}")
    forAll(Gens.validPlanWith(Gens.quickCommands), Gen.long) { case ((env, t), seed) =>
      val base = tempDir("ase-law")
      try
        val ws = Files.createDirectories(base.resolve("ws"))
        val outside = Files.createDirectories(base.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "geheim\n")
        fixture(ws, outside)
        val outsideBefore = tree(outside)
        val approved = approve(t, env, check)
        val tools = runIO(LocalTools.open[IO](ws, config)).fold(e => fail(e.message), identity)
        val model = adversarialModel(seed, AdversarialFill.symlinkAttacks)
        val r = runIO(Execute.run[IO](approved, ports[IO](tools, model, q => Option.when(q.length % 2 == 0)("ja")), config))

        val esc = escapes(r.events, env)
        assert(esc.isEmpty, esc.mkString("\n"))
        assertEquals(tree(outside), outsideBefore)
        assertEquals(Files.list(outside).count(), 1L)
        assert(r.stats.steps <= approved.checked.worstCaseSteps)

        val reread = RecordLog.parse(RecordLog.render(r.events)).fold(e => fail(e.message), identity)
        assertEquals(Replay.verify(approved, reread, config).map(_.events), Right(r.events))

        val fresh = Files.createDirectories(base.resolve("fresh"))
        fixture(fresh, outside)
        val freshTools = runIO(LocalTools.open[IO](fresh, config)).fold(e => fail(e.message), identity)
        runIO(Replay.applyWrites[IO](r.events, freshTools, all))
        assertEquals(tree(fresh), tree(ws))
        true
      finally deleteTree(base)
    }
