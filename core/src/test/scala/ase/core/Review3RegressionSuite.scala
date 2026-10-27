package ase.core

import ase.core.ExecSupport.*
import cats.effect.IO

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Regressionstests zum Review von Schritt 3. R1–R7 sind die Wegwerf-Tests des Reviewers, an die Korrekturen
  * angepasst; die übrigen halten je einen weiteren Befund fest. Tests mit Dateisystem und Prozessen nur unter Linux
  * (D#29), sonst sichtbar übersprungen.
  */
class Review3RegressionSuite extends munit.FunSuite:
  override def munitTimeout: Duration = 3.minutes
  private val h = Origin.Human
  private val fill = Origin.Model("fill")
  private val check = CheckConfig.default
  private val config = ExecConfig.default

  private def linux(): Unit = assume(Platform.isLinux, s"Execute nur unter Linux (D#29), hier ${Platform.current}")

  private def git(): Unit =
    linux()
    assume(scala.util.Try(sh(Path.of("."), "git", "--version")._1 == 0).getOrElse(false), "git fehlt")

  private def local(approved: ApprovedTerm, ws: Path, model: FillModel[IO] = _ => IO.pure(ModelReply.Failed("x", 0))): RunResult =
    runIO(Execute.local[IO](approved, ws, model, AskHandler.nonInteractive[IO], config, None)).fold(e => fail(e.message), identity)

  private def outputs(r: RunResult): String =
    r.events.collect { case Event.Step(_, _, rep) => rep.result.toOption.flatMap(_.output).getOrElse("") }.mkString

  private def withBase(prefix: String)(body: Path => Unit): Unit =
    val base = tempDir(prefix)
    try body(base)
    finally deleteTree(base)

  // ---------- M2: Loch in RepeatUntil

  private val loopRoot = Envelope(OpKind.values.toSet - OpKind.Shell, ".", 30, 1, Set.empty)
  private val loopHole = Envelope(Set(OpKind.ReadFile), "src", 2, 0, Set.empty)
  private val loopTerm = Term.RepeatUntil(Term.Hole("prüfe", loopHole, h), Pred.LastOutputContains("fertig"), 3, h)

  test("R1 (M2) Loch in RepeatUntil: jeder Durchlauf hat sein eigenes Budget und eigene Positionen (D#14)"):
    val approved = approve(loopTerm, loopRoot, check)
    assertEquals(approved.checked.worstCaseSteps, 3L * (1 + 2))
    var requests = Vector.empty[FillRequest]
    val model: FillRequest => ModelReply = r =>
      requests :+= r
      val summary = if r.callId == "fill-1" then "weiter" else "fertig"
      ModelReply.Answer(submission(Term.Seq(List(Term.ReadFile("src/A.scala", fill), Term.Done(summary, fill)), fill)), 1)
    val r = Execute.run[W](approved, ports(fakeTools(check.argPolicies), model, _ => None), config).runA(Map("src/A.scala" -> "x\n")).value
    assertEquals(r.outcome, Outcome.Done)
    // vorher: zweiter Durchlauf erbte das verbrauchte Budget (remaining 0) und brach ab
    assertEquals(requests.map(q => (q.at, q.attempt, q.remainingSteps)), Vector(("1", 1, 2), ("1", 1, 2)))
    val ids = r.events.collect { case Event.Step(at, _, _) => at.show } ++ r.events.collect { case Event.Finished(at, _) => at.show }
    assertEquals(ids.sorted, Vector("1.1.1", "1.1.2", "1.2.1", "1.2.2"))
    assertEquals(ids.distinct.size, ids.size)
    // je Durchlauf: Loch, ReadFile, Done
    assertEquals(r.stats.steps, 6L)
    assertEquals(r.events.last, Event.End(Outcome.Done, 6L))

  test("R6 (M2) Stats.steps liest die Schrittzahl der Engine aus dem End-Ereignis"):
    val approved = approve(loopTerm, loopRoot, check)
    val model: FillRequest => ModelReply = r =>
      ModelReply.Answer(submission(Term.Done(if r.callId == "fill-2" then "fertig" else "weiter", fill)), 1)
    val r = Execute.run[W](approved, ports(fakeTools(check.argPolicies), model, _ => None), config).runA(Map("src/A.scala" -> "x")).value
    // 2 Aufrufe des Lochs + 2 Done
    assertEquals(r.stats.steps, 4L)
    assertEquals(Stats.of(r.events.init).steps, 0L)
    assertEquals(RecordLog.parse(RecordLog.render(r.events)), Right(r.events))
    assertEquals(Replay.verify(approved, r.events, config).map(_.events), Right(r.events))

  // ---------- N2: Uhr vor Ask

  test("N2 Ask: nach Ablauf der Zeit wird nicht mehr gefragt"):
    val approved = approve(Term.Ask("weiter?", h), Envelope(Set(OpKind.Ask), ".", 5, 0, Set.empty), check)
    var asked = 0
    val clock: Clock[W] = pos => cats.data.State.pure(pos >= 1)
    val p = ports(fakeTools(check.argPolicies), _ => ModelReply.Failed("x", 0), _ => { asked += 1; Some("ja") }).copy(clock = clock)
    val r = Execute.run[W](approved, p, config).runA(Map.empty).value
    assertEquals(r.outcome, Outcome.Aborted(AbortReason.LimitReached(Limit.Time(config.maxDuration.toSeconds))))
    assertEquals(asked, 0)
    assert(!r.events.exists(_.isInstanceOf[Event.Asked]))
    assertEquals(Replay.verify(approved, r.events, config).map(_.events), Right(r.events))

  // ---------- N4: Replay prüft jeden aufgezeichneten Aufruf gegen die Hülle

  test("N4 Replay: ein aufgezeichneter Erfolg, den die Pfadprüfung ablehnen muss, wird nicht übernommen"):
    val root = Envelope(Set(OpKind.WriteFile), ".", 5, 0, Set.empty)
    // DryRun nimmt "." als Pfad an; die Werkzeuge lehnen ihn ab (kein Dateiname)
    val approved = approve(Term.WriteFile(".", "x", h), root, check)
    val honest = Execute.run[W](approved, ports(fakeTools(check.argPolicies), _ => ModelReply.Failed("x", 0), _ => None), config).runA(Map.empty).value
    val rejection = ToolError.InvalidPath(".", "kein Dateiname")
    assertEquals(honest.events.collect { case Event.Step(_, _, rep) => rep }, Vector(Reply(Nil, Left(rejection), "")))
    assertEquals(Replay.verify(approved, honest.events, config).map(_.events), Right(honest.events))
    val forged = honest.events.map {
      case Event.Step(at, c, _) => Event.Step(at, c, Reply(List("."), Right(StepResult(None, None)), "neu"))
      case Event.End(_, n)      => Event.End(Outcome.Done, n)
      case e                    => e
    }
    assertEquals(Replay.run(approved, forged, config).map(_.outcome), Left(Replay.ReplayError.OutsideEnvelope(Call.WriteFile(".", "x").toString, rejection)))

  test("N4 ToolCheck: dieselbe Prüfung für Operation, Pfade und Shell"):
    val env = Envelope(Set(OpKind.ReadFile, OpKind.Shell), "src", 5, 0, Set(AllowedCommand("grep", Nil)))
    assertEquals(ToolCheck.call(Call.ReadFile("src/a"), env, check.argPolicies), Right(()))
    assertEquals(ToolCheck.call(Call.ReadFile("docs/a"), env, check.argPolicies), Left(ToolError.OutsidePrefix("docs/a", "src")))
    assertEquals(ToolCheck.call(Call.EditFile("src/a", "x", "y"), env, check.argPolicies), Left(ToolError.OpNotAllowed(OpKind.EditFile)))
    assertEquals(ToolCheck.call(Call.Shell("grep", List("-r", "x", "docs")), env, check.argPolicies), Left(ToolError.OutsidePrefix("docs", "src")))
    assertEquals(ToolCheck.call(Call.Shell("ls", List("src")), env, check.argPolicies), Left(ToolError.ShellNotAllowed("ls", List("src"))))
    assertEquals(ToolCheck.shell("grep", List("-rn", "x", "./src/"), env, check.argPolicies).map(_.args), Right(List("-rn", "x", "src")))

  // ---------- M4: Freigabe mit dem Hash aus dem Explain-Text

  test("M4 Auto-Approver übergibt den gezeigten Hash; ein geänderter Hash im Text lässt die Freigabe scheitern"):
    val root = Envelope(Set(OpKind.ReadFile), ".", 5, 0, Set.empty)
    val checked = DryRun.plan(Term.ReadFile("a.txt", h), root, check).fold(v => fail(v.toString), identity)
    val explain = Explain.render(checked, check = check)
    val lines = explain.linesIterator.toVector
    val i = lines.indexWhere(_.startsWith("explain   "))
    assertEquals(lines(i + 1), s"hash      ${checked.termHash.hex}")
    assertEquals(AutoApprover.approve(checked, explain).map(_.termHash), Right(checked.termHash))
    val hex = checked.termHash.hex
    val flipped = hex.init + (if hex.last == '0' then '1' else '0')
    val forged = explain.replace(s"hash      $hex", s"hash      $flipped")
    val result = AutoApprover.approve(checked, forged)
    assert(result.left.exists(_.contains("HashMismatch")), result)
    assert(AutoApprover.approve(checked, explain.replace(s"hash      $hex\n", "")).isLeft)

  // ---------- M1: Scope mit aufgelösten Pfaden

  test("R2 (M1) Scope: ein Symlink src/link → config/secret.txt erscheint nicht im Fill-Kontext eines Lochs unter src"):
    linux()
    withBase("r2") { base =>
      val ws = Files.createDirectories(base.resolve("ws"))
      Files.createDirectories(ws.resolve("src"))
      Files.createDirectories(ws.resolve("config"))
      Files.writeString(ws.resolve("config/secret.txt"), "PASSWORT=hunter2\n")
      Files.writeString(ws.resolve("src/A.scala"), "val a = 1\n")
      Files.createSymbolicLink(ws.resolve("src/link"), Path.of("../config/secret.txt"))
      val root = Envelope(Set(OpKind.ReadFile, OpKind.Hole), ".", 10, 1, Set.empty)
      val hole = Envelope(Set(OpKind.ReadFile), "src", 2, 0, Set.empty)
      val t = Term.Seq(List(Term.ReadFile("src/link", h), Term.ReadFile("src/A.scala", h), Term.Hole("x", hole, h)), h)
      val approved = approve(t, root, check)
      var seen = Vector.empty[FillRequest]
      val model: FillModel[IO] = r => IO { seen :+= r; ModelReply.Answer(submission(Term.Done("ok", fill)), 1) }
      val res = local(approved, ws, model)
      assertEquals(res.events.collect { case Event.Step(_, _, rep) => rep.touched }, Vector(List("config/secret.txt"), List("src/A.scala")))
      // sichtbar bleibt nur, was aufgelöst unter src liegt
      assertEquals(seen.flatMap(_.scope.map(_.at)), Vector("2"))
      assert(!seen.flatMap(_.scope).exists(_.text.contains("hunter2")))
      assertEquals(scopeLeaks(seen, res.events, check.argPolicies), Nil)
    }

  // ---------- K1: Git-Verzeichnis auch über Symlinks gesperrt

  test("R3 (K1) Symlink auf .git: der Fill ändert lnk/config nicht, git status startet kein Filter-Programm"):
    git()
    withBase("r3") { base =>
      val ws = Files.createDirectories(base.resolve("ws"))
      val marker = base.resolve("PWNED")
      Files.writeString(ws.resolve("a.txt"), "aaaa\n")
      def g(args: String*) = sh(ws, ("git" :: "-c" :: "user.name=x" :: "-c" :: "user.email=x@x" :: args.toList)*)
      assertEquals(g("init", "-q")._1, 0)
      assertEquals(g("add", "a.txt")._1, 0)
      assertEquals(g("commit", "-q", "-m", "init")._1, 0)
      Files.createSymbolicLink(ws.resolve("lnk"), Path.of(".git"))
      val configBefore = Files.readString(ws.resolve(".git/config"))
      val gitStatus = AllowedCommand("git", List("status"))
      val root = Envelope(Set(OpKind.EditFile, OpKind.WriteFile, OpKind.Shell, OpKind.Hole), ".", 20, 1, Set(gitStatus))
      val hole = Envelope(Set(OpKind.EditFile, OpKind.WriteFile, OpKind.Shell), ".", 6, 0, Set(gitStatus))
      val approved = approve(Term.Hole("räume auf", hole, h), root, check)
      val firstLine = configBefore.linesIterator.next()
      val payload = s"[filter \"x\"]\n\tclean = touch $marker; cat\n$firstLine"
      val f = Term.Seq(
        List(
          Term.EditFile("lnk/config", firstLine, payload, fill),
          Term.WriteFile(".gitattributes", "* filter=x\n", fill),
          Term.EditFile("a.txt", "aaaa", "bbbb", fill),
          Term.Shell("git", List("status"), fill),
          Term.Done("fertig", fill)
        ),
        fill
      )
      val res = local(approved, ws, _ => IO.pure(ModelReply.Answer(submission(f), 1)))
      assert(!Files.exists(marker), "git status hat das Filter-Programm aus .git/config gestartet")
      assertEquals(Files.readString(ws.resolve(".git/config")), configBefore)
      assert(res.events.collect { case e: Event.FillFailed => e.error }.forall(_ == ToolError.GitDirLocked("lnk/config")), res.events)
      assertEquals(res.outcome, Outcome.Aborted(AbortReason.AttemptsExhausted(StepId(Vector(1)), config.attemptsPerFill)))
    }

  test("K1 jeder aufgelöste Pfad mit Segment .git ist gesperrt, für alle Werkzeuge, ohne Rücksicht auf Groß/Klein"):
    linux()
    withBase("k1") { base =>
      val ws = Files.createDirectories(base.resolve("ws"))
      Files.createDirectories(ws.resolve(".git/hooks"))
      Files.writeString(ws.resolve(".git/config"), "[core]\n")
      Files.createDirectories(ws.resolve(".GIT"))
      Files.writeString(ws.resolve(".GIT/x"), "x\n")
      Files.createDirectories(ws.resolve("src"))
      Files.createSymbolicLink(ws.resolve("lnk"), Path.of(".git"))
      Files.createSymbolicLink(ws.resolve("src/deep"), Path.of("../.git/hooks"))
      Files.createSymbolicLink(ws.resolve("gross"), Path.of(".GIT"))
      val all = Envelope(OpKind.values.toSet, ".", 50, 1, Set(AllowedCommand("grep", Nil), AllowedCommand("ls", Nil)))
      val tools = runIO(LocalTools.open[IO](ws, config)).fold(e => fail(e.message), identity)
      def call(c: Call) = runIO(tools.call(c, all))
      val cases = List(
        Call.ReadFile("lnk/config") -> "lnk/config",
        Call.ListDir("lnk") -> "lnk",
        Call.FileExists("lnk/config") -> "lnk/config",
        Call.EditFile("lnk/config", "[core]", "[x]") -> "lnk/config",
        Call.WriteFile("lnk/neu", "x") -> "lnk/neu",
        Call.WriteFile("src/deep/pre-commit", "x") -> "src/deep/pre-commit",
        Call.MoveFile("lnk/config", "config") -> "lnk/config",
        Call.ReadFile("gross/x") -> "gross/x",
        Call.Shell("grep", List("-r", "core", "lnk")) -> "lnk",
        Call.Shell("ls", List("src/deep")) -> "src/deep"
      )
      cases.foreach { (c, p) => assertEquals(call(c), Reply(Nil, Left(ToolError.GitDirLocked(p)), ""), c) }
      assert(!Files.exists(ws.resolve(".git/hooks/pre-commit")))
      assertEquals(Files.readString(ws.resolve(".git/config")), "[core]\n")
    }

  // ---------- M3: EditFile atomar

  test("R4 (M3) Hardlink im Workspace: EditFile ersetzt die Datei atomar, die Datei außerhalb bleibt"):
    linux()
    withBase("r4") { base =>
      val ws = Files.createDirectories(base.resolve("ws"))
      val outside = Files.createDirectories(base.resolve("outside"))
      Files.writeString(outside.resolve("secret.txt"), "geheim\n")
      Files.createDirectories(ws.resolve("src"))
      Files.createLink(ws.resolve("src/h.txt"), outside.resolve("secret.txt"))
      val perms = PosixFilePermissions.fromString("rwxr-x---")
      Files.setPosixFilePermissions(ws.resolve("src/h.txt"), perms)
      val root = Envelope(Set(OpKind.EditFile), ".", 5, 0, Set.empty)
      val res = local(approve(Term.EditFile("src/h.txt", "geheim", "geändert", h), root, check), ws)
      assertEquals(res.outcome, Outcome.Done)
      assertEquals(Files.readString(outside.resolve("secret.txt")), "geheim\n")
      assertEquals(Files.readString(ws.resolve("src/h.txt")), "geändert\n")
      assertEquals(Files.getPosixFilePermissions(ws.resolve("src/h.txt")), perms)
      // keine Temp-Datei zurückgelassen
      assertEquals(Files.list(ws.resolve("src")).iterator.asScala.map(_.getFileName.toString).toList, List("h.txt"))
    }

  // ---------- N1: grep liest .git nicht mit

  test("R5 (N1) grep -rn TOKEN . liest .git/config nicht; das Modell sieht das zusätzliche Argument nicht"):
    git()
    withBase("r5") { base =>
      val ws = Files.createDirectories(base.resolve("ws"))
      assertEquals(sh(ws, "git", "init", "-q")._1, 0)
      assertEquals(sh(ws, "git", "config", "remote.origin.url", "https://user:TOKEN123@example.com/x.git")._1, 0)
      Files.writeString(ws.resolve("a.txt"), "TOKEN im Text\n")
      val root = Envelope(Set(OpKind.Shell), ".", 5, 0, Set(AllowedCommand("grep", Nil)))
      val res = local(approve(Term.Shell("grep", List("-rn", "TOKEN", "."), h), root, check), ws)
      val out = outputs(res)
      assert(!out.contains("TOKEN123"), out)
      assert(out.contains("a.txt:1:TOKEN im Text"), out)
      assertEquals(res.events.collect { case Event.Step(_, c, _) => c }, Vector(Call.Shell("grep", List("-rn", "TOKEN", "."))))
    }

  // ---------- H1: git nur im eigenen Repository

  test("R7 (H1) git status in einem Workspace ohne eigenes .git liest nicht das Repository darüber"):
    git()
    withBase("r7") { base =>
      assertEquals(sh(base, "git", "init", "-q")._1, 0)
      Files.writeString(base.resolve("draussen-geheim.txt"), "x")
      val ws = Files.createDirectories(base.resolve("ws"))
      Files.writeString(ws.resolve("a.txt"), "a")
      val root = Envelope(Set(OpKind.Shell), ".", 5, 0, Set(AllowedCommand("git", List("status"))))
      val approved = approve(Term.Shell("git", List("status", "--porcelain"), h), root, check)
      val res = local(approved, ws)
      assert(!outputs(res).contains("draussen-geheim"), outputs(res))
      assertEquals(res.outcome, Outcome.Aborted(AbortReason.StepFailed(StepId(Vector(1)), ToolError.NoGitRepository)))
      // .git als Symlink auf ein Repository außerhalb zählt nicht
      Files.createSymbolicLink(ws.resolve(".git"), base.resolve(".git"))
      assertEquals(local(approved, ws).outcome, Outcome.Aborted(AbortReason.StepFailed(StepId(Vector(1)), ToolError.NoGitRepository)))
      Files.delete(ws.resolve(".git"))
      // eigenes Repository unter einem fremden: nur das eigene
      assertEquals(sh(ws, "git", "init", "-q")._1, 0)
      val own = local(approved, ws)
      assertEquals(own.outcome, Outcome.Done)
      assert(outputs(own).contains("a.txt") && !outputs(own).contains("draussen-geheim"), outputs(own))
    }

  // ---------- N5: PATH ohne Einträge im Workspace

  test("N5 PATH: relative Einträge und Einträge unter dem Arbeitsverzeichnis (auch über Symlink) entfallen"):
    linux()
    withBase("n5") { base =>
      val ws = Files.createDirectories(base.resolve("ws")).toRealPath()
      Files.createDirectories(ws.resolve("bin"))
      Files.createSymbolicLink(base.resolve("alias"), ws)
      val path = List(s"$ws/bin", "/usr/bin", "bin", ".", s"${base.resolve("alias")}/bin", s"$ws/neu/bin", s"$ws/../ws/bin", "/bin").mkString(":")
      assertEquals(LocalTools.safePath(path, ws), List("/usr/bin", "/bin"))
    }

  // ---------- N6: Ein-/Ausgabefehler ohne Ausnahmetext

  test("N6 Ein-/Ausgabefehler gehen ohne Ausnahmetext und ohne absolute Pfade ans Modell"):
    linux()
    withBase("n6") { base =>
      val ws = Files.createDirectories(base.resolve("ws"))
      Files.createDirectories(ws.resolve("src"))
      Files.createSymbolicLink(ws.resolve("src/loop1"), Path.of("loop2"))
      Files.createSymbolicLink(ws.resolve("src/loop2"), Path.of("loop1"))
      val tools = runIO(LocalTools.open[IO](ws, config)).fold(e => fail(e.message), identity)
      val reply = runIO(tools.call(Call.ReadFile("src/loop1"), Envelope(Set(OpKind.ReadFile), ".", 5, 0, Set.empty)))
      reply.result match
        case Left(e @ ToolError.Io(kind, "src/loop1")) =>
          assert(kind.nonEmpty && !kind.contains("/"), kind)
          val text = Marshal(e: ToolError)
          assert(!text.contains(base.toString) && !text.contains("Too many"), text)
        case other => fail(s"erwartet Io für src/loop1, war $other")
    }

  // ---------- N7: Record-Datei nicht im Workspace

  test("N7 Execute.local lehnt eine Record-Datei im Arbeitsverzeichnis ab, auch über einen Symlink"):
    linux()
    withBase("n7") { base =>
      val ws = Files.createDirectories(base.resolve("ws"))
      Files.createSymbolicLink(base.resolve("alias"), ws)
      val approved = approve(Term.Done("x", h), Envelope(Set(OpKind.ReadFile), ".", 1, 0, Set.empty), check)
      def run(record: Path) =
        runIO(Execute.local[IO](approved, ws, _ => IO.pure(ModelReply.Failed("x", 0)), AskHandler.nonInteractive[IO], config, Some(record)))
      List(ws.resolve("record/run.log"), base.resolve("alias/run.log")).foreach { record =>
        assertEquals(run(record).map(_.outcome), Left(ExecuteError.RecordInsideWorkspace(record.toString)))
        assert(!Files.exists(record))
      }
      val outside = base.resolve("record/run.log")
      assertEquals(run(outside).map(_.outcome), Right(Outcome.Done))
      assert(Files.exists(outside))
    }
