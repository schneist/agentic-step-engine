package ase.core

import ase.core.ExecSupport.*
import cats.effect.IO

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** D#96: git in einem Arbeitsverzeichnis, das ein Git-Worktree ist (O#51 b). Gegen echtes git, mit und ohne Sandbox;
  * nur unter Linux, sonst sichtbar übersprungen.
  */
class GitWorktreeSuite extends munit.FunSuite:
  override def munitTimeout: Duration = 2.minutes
  private val h = Origin.Human
  private val check = CheckConfig.default
  private val env = Envelope(Set(OpKind.Shell), ".", 5, 0, Set(AllowedCommand("git", List("status"))))
  private val status = Call.Shell("git", List("status", "--porcelain"))
  private val hasBwrap = List("/usr/bin/bwrap", "/bin/bwrap").exists(p => Files.isExecutable(Path.of(p)))

  /** base/main mit einem Commit, base/wt als sein Worktree, darin eine neue Datei. */
  private def withWorktree(name: String)(body: (Path, Path) => Unit): Unit =
    test(name) {
      assume(Platform.isLinux, s"Execute nur unter Linux (D#29), hier ${Platform.current}")
      assume(scala.util.Try(sh(Path.of("."), "git", "--version")._1 == 0).getOrElse(false), "git fehlt")
      val base = tempDir("ase-worktree")
      try
        val main = Files.createDirectories(base.resolve("main"))
        assertEquals(sh(main, "git", "init", "-q")._1, 0)
        Files.writeString(main.resolve("a.txt"), "a\n")
        assertEquals(sh(main, "git", "add", "a.txt")._1, 0)
        assertEquals(sh(main, "git", "-c", "user.email=a@b", "-c", "user.name=a", "commit", "-qm", "init")._1, 0)
        assertEquals(sh(main, "git", "worktree", "add", "-q", "../wt")._1, 0)
        val wt = base.resolve("wt")
        Files.writeString(wt.resolve("neu.txt"), "n\n")
        body(base, wt)
      finally deleteTree(base)
    }

  private def tools(ws: Path, sandbox: Sandbox): Tools[IO] =
    runIO(LocalTools.open[IO](ws, ExecConfig.default.copy(sandbox = sandbox), check)).fold(e => fail(e.message), identity)

  private def run(ws: Path, sandbox: Sandbox): Either[ToolError, StepResult] = runIO(tools(ws, sandbox).call(status, env)).result

  withWorktree("git status im Worktree: ohne Sandbox und in der Sandbox; beide Git-Verzeichnisse außerhalb, das gemeinsame zuerst"): (base, wt) =>
    val off = run(wt, Sandbox.Off).fold(e => fail(e.toString), identity)
    assertEquals((off.exitCode, off.output.map(_.trim)), (Some(0), Some("?? neu.txt")))
    assume(hasBwrap, "bwrap fehlt (D#88)")
    val on = run(wt, Sandbox.default).fold(e => fail(e.toString), identity)
    assertEquals((on.exitCode, on.output.map(_.trim)), (Some(0), Some("?? neu.txt")))
    // beide Verzeichnisse außerhalb, das gemeinsame zuerst
    val real = base.toRealPath()
    assertEquals(GitRepo.worktree(wt.toRealPath()).map(_.outside), Some(List(real.resolve("main/.git"), real.resolve("main/.git/worktrees/wt"))))

  withWorktree("ein echtes .git-Verzeichnis ist kein Worktree; ein Lauf im Hauptbaum geht wie bisher"): (base, _) =>
    assertEquals(GitRepo.worktree(base.resolve("main").toRealPath()), None)
    assertEquals(run(base.resolve("main"), Sandbox.Off).map(_.exitCode), Right(Some(0)))

  withWorktree("keine .git-Datei zählt, die ins Leere, auf ein Verzeichnis ohne HEAD oder ins Arbeitsverzeichnis zeigt"): (base, _) =>
    val ws = Files.createDirectories(base.resolve("ws"))
    val ohneHead = Files.createDirectories(base.resolve("leer"))
    assertEquals(sh(ws, "git", "init", "-q", "--bare", "innen")._1, 0)
    List(
      "gitdir: /gibt/es/nicht",
      s"gitdir: $ohneHead",
      "gitdir: innen",
      s"gitdir: ${ws.toRealPath().resolve("innen")}",
      "irgendwas",
      ""
    ).foreach { content =>
      Files.writeString(ws.resolve(".git"), content + "\n")
      assertEquals(GitRepo.worktree(ws.toRealPath()), None, content)
      assertEquals(run(ws, Sandbox.Off).left.toOption, Some(ToolError.NoGitRepository), content)
    }

  withWorktree("eine .git-Datei, die erst nach dem Start entsteht, macht kein Worktree"): (base, wt) =>
    val ws = Files.createDirectories(base.resolve("ws"))
    val t = tools(ws, Sandbox.Off)
    // derselbe Verweis wie im echten Worktree, aber erst nach dem Öffnen
    Files.copy(wt.resolve(".git"), ws.resolve(".git"))
    assertEquals(runIO(t.call(status, env)).result.left.toOption, Some(ToolError.NoGitRepository))
    // ein neuer Lauf sieht ihn
    assert(GitRepo.worktree(ws.toRealPath()).isDefined)
