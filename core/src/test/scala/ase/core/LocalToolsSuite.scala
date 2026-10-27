package ase.core

import ase.core.ExecSupport.*
import cats.effect.IO

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Pfad- und Shell-Prüfung zur Laufzeit, gegen echte Dateien. Nur unter Linux (D#29), sonst sichtbar übersprungen. */
class LocalToolsSuite extends munit.FunSuite:
  override def munitTimeout: Duration = 2.minutes

  private val all = Envelope(OpKind.values.toSet, ".", 50, 1, Set(AllowedCommand("grep", Nil), AllowedCommand("git", Nil)))
  private def scripts(names: String*) =
    CheckConfig.default.copy(argPolicies = ArgPolicy.defaults ++ names.map(ArgPolicy.script))

  /** Arbeitsverzeichnis ws mit src/A.scala und einem Verzeichnis außerhalb (outside/secret.txt); Symlinks nach außen. */
  private def withWs(name: String)(body: (Path, Path) => Unit): Unit =
    test(name) {
      assume(Platform.isLinux, s"Execute nur unter Linux (D#29), hier ${Platform.current}")
      val (ws, outside) = setup()
      try body(ws, outside)
      finally deleteTree(ws.getParent)
    }

  private def setup(): (Path, Path) =
      val base = tempDir("ase-tools")
      val ws = Files.createDirectories(base.resolve("ws"))
      val outside = Files.createDirectories(base.resolve("outside"))
      Files.writeString(outside.resolve("secret.txt"), "geheim\n")
      Files.createDirectories(ws.resolve("src"))
      Files.writeString(ws.resolve("src/A.scala"), "val a = 1\nval b = 1\n")
      Files.createSymbolicLink(ws.resolve("src/out"), outside)
      Files.createSymbolicLink(ws.resolve("src/secret.txt"), outside.resolve("secret.txt"))
      Files.createSymbolicLink(ws.resolve("src/inner.scala"), ws.resolve("src/A.scala"))
      (ws, outside)

  private def tools(ws: Path, config: ExecConfig = ExecConfig.default, check: CheckConfig = CheckConfig.default): Tools[IO] =
    runIO(LocalTools.open[IO](ws, config, check)).fold(e => fail(e.message), identity)

  private def call(ws: Path, c: Call, env: Envelope = all, config: ExecConfig = ExecConfig.default, check: CheckConfig = CheckConfig.default): Reply =
    runIO(tools(ws, config, check).call(c, env))

  private def error(r: Reply): Option[ToolError] = r.result.left.toOption

  withWs("writeFile: neue Datei innerhalb ja, außerhalb nein, auch nicht über Symlink"): (ws, outside) =>
      val ok = call(ws, Call.WriteFile("src/B.scala", "neu\n"))
      assertEquals(ok.result, Right(StepResult(None, None)))
      assertEquals(ok.touched, List("src/B.scala"))
      assertEquals(Files.readString(ws.resolve("src/B.scala")), "neu\n")
      assertEquals(error(call(ws, Call.WriteFile("../x.txt", "x"))).map(_.getClass), Some(classOf[ToolError.InvalidPath]))
      val viaLink = call(ws, Call.WriteFile("src/out/neu.txt", "x"))
      assertEquals(error(viaLink), Some(ToolError.OutsideWorkspace("src/out/neu.txt")))
      assertEquals(viaLink.touched, Nil)
      assert(!Files.exists(outside.resolve("neu.txt")))
      assertEquals(error(call(ws, Call.WriteFile("src/A.scala", "x"))), Some(ToolError.AlreadyExists("src/A.scala")))
      // ein Symlink als Ziel wird nicht überschrieben und nicht verfolgt
      assertEquals(error(call(ws, Call.WriteFile("src/secret.txt", "x"))), Some(ToolError.AlreadyExists("src/secret.txt")))
      assertEquals(Files.readString(outside.resolve("secret.txt")), "geheim\n")
      assertEquals(error(call(ws, Call.WriteFile("src/neu/x.txt", "x"))), Some(ToolError.ParentMissing("src/neu/x.txt")))
      assertEquals(error(call(ws, Call.WriteFile("docs/x.md", "x"), all.copy(pathPrefix = "src"))), Some(ToolError.OutsidePrefix("docs/x.md", "src")))

  withWs("readFile: Symlink nach außen abgefangen, nach innen erlaubt; große Dateien als Ausschnitt"): (ws, _) =>
      assertEquals(error(call(ws, Call.ReadFile("src/secret.txt"))), Some(ToolError.OutsideWorkspace("src/secret.txt")))
      assertEquals(error(call(ws, Call.ReadFile("src/out/secret.txt"))), Some(ToolError.OutsideWorkspace("src/out/secret.txt")))
      assertEquals(call(ws, Call.ReadFile("src/inner.scala")).result.map(_.output), Right(Some("val a = 1\nval b = 1\n")))
      Files.writeString(ws.resolve("big.txt"), (1 to 200000).map(i => s"Zeile $i").mkString("\n"))
      val big = call(ws, Call.ReadFile("big.txt"), config = ExecConfig.default.copy(maxBytesPerStep = 4096))
      val text = big.result.toOption.flatMap(_.output).getOrElse("")
      assert(text.startsWith("Zeile 1\nZeile 2\n"), text.take(100))
      assert(text.contains("von 200000 Zeilen ausgelassen; suche gezielter"), text)
      assert(text.endsWith("Zeile 200000"), text.takeRight(100))
      assert(text.length < 8192)

  withWs("editFile: expectedOld fehlt, mehrdeutig, genau einmal"): (ws, _) =>
      val before = Files.readString(ws.resolve("src/A.scala"))
      assertEquals(error(call(ws, Call.EditFile("src/A.scala", "val c", "x"))), Some(ToolError.CasMissing("src/A.scala")))
      val twice = call(ws, Call.EditFile("src/A.scala", "= 1", "= 2"))
      assertEquals(error(twice), Some(ToolError.CasAmbiguous("src/A.scala", 2)))
      assert(Marshal(ToolError.CasAmbiguous("src/A.scala", 2)).contains("2-mal vor; erweitere den Kontext"))
      assertEquals(Files.readString(ws.resolve("src/A.scala")), before)
      assertEquals(call(ws, Call.EditFile("src/A.scala", "val b = 1", "val b = 2")).result, Right(StepResult(None, None)))
      assertEquals(Files.readString(ws.resolve("src/A.scala")), "val a = 1\nval b = 2\n")
      assertEquals(error(call(ws, Call.EditFile("src/secret.txt", "geheim", "x"))), Some(ToolError.OutsideWorkspace("src/secret.txt")))

  withWs("moveFile: Ziel existiert, Quelle Verzeichnis oder Symlink, Ziel außerhalb; Erfolg"): (ws, _) =>
      assertEquals(error(call(ws, Call.MoveFile("src/A.scala", "src/inner.scala"))), Some(ToolError.AlreadyExists("src/inner.scala")))
      assertEquals(error(call(ws, Call.MoveFile("src", "neu"))), Some(ToolError.NotAFile("src")))
      assertEquals(error(call(ws, Call.MoveFile("src/inner.scala", "src/C.scala"))), Some(ToolError.SymlinkNotAllowed("src/inner.scala")))
      assertEquals(error(call(ws, Call.MoveFile("src/A.scala", "src/out/A.scala"))), Some(ToolError.OutsideWorkspace("src/out/A.scala")))
      assertEquals(
        error(call(ws, Call.MoveFile("src/A.scala", "B.scala"), all.copy(pathPrefix = "src"))),
        Some(ToolError.OutsidePrefix("B.scala", "src"))
      )
      val content = Files.readString(ws.resolve("src/A.scala"))
      assertEquals(call(ws, Call.MoveFile("src/A.scala", "src/C.scala")).touched, List("src/A.scala", "src/C.scala"))
      assert(!Files.exists(ws.resolve("src/A.scala")))
      assertEquals(Files.readString(ws.resolve("src/C.scala")), content)

  withWs("Shell: Argumentpfade auch zur Laufzeit geprüft, normalisiert weitergegeben"): (ws, _) =>
      val viaLink = call(ws, Call.Shell("grep", List("-r", "geheim", "src/out")))
      assertEquals(error(viaLink), Some(ToolError.OutsideWorkspace("src/out")))
      assertEquals(viaLink.touched, Nil)
      val r = call(ws, Call.Shell("grep", List("-rn", "val a", "./src/")), all.copy(pathPrefix = "src"))
      assertEquals(r.touched, List("src"))
      assertEquals(r.result.map(_.exitCode), Right(Some(0)))
      assert(r.result.toOption.flatMap(_.output).exists(_.contains("src/A.scala:1:val a = 1")), r)
      assertEquals(error(call(ws, Call.Shell("curl", List("x")))), Some(ToolError.ShellNotAllowed("curl", List("x"))))

  private def script(ws: Path, name: String, body: String): Unit =
    val p = ws.resolve(name)
    Files.writeString(p, "#!/usr/bin/env bash\n" + body)
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"))

  private def shellEnv(name: String) = all.copy(shellAllowlist = Set(AllowedCommand(name, Nil)))

  withWs("Shell: Zeitüberschreitung beendet den Prozess samt Kindprozessen"): (ws, _) =>
      script(ws, "slow.sh", "sleep 60 &\necho \"kind $!\"\nsleep 60\n")
      val config = ExecConfig.default.copy(shellTimeout = 2.seconds)
      val started = System.nanoTime()
      val r = call(ws, Call.Shell("./slow.sh", Nil), shellEnv("./slow.sh"), config, scripts("./slow.sh"))
      assert((System.nanoTime() - started).nanos < 20.seconds)
      val out = error(r) match
        case Some(ToolError.Timeout("./slow.sh", 2L, out)) => out
        case other                                       => fail(s"erwartet Timeout, war $other")
      val pid = "kind (\\d+)".r.findFirstMatchIn(out).map(_.group(1).toLong).getOrElse(fail(s"keine PID in $out"))
      Thread.sleep(500)
      assert(!java.lang.ProcessHandle.of(pid).map(_.isAlive).orElse(false), s"Kindprozess $pid lebt noch")

  withWs("Shell: Ausgabe begrenzt, stdin leer, Programm relativ zum Arbeitsverzeichnis"): (ws, _) =>
      script(ws, "loud.sh", "for i in $(seq 1 100000); do echo \"Zeile $i\"; done\n")
      script(ws, "stdin.sh", "if read -r x; then echo \"gelesen: $x\"; else echo \"stdin leer\"; fi\n")
      val config = ExecConfig.default.copy(maxBytesPerStep = 2048)
      val check = scripts("./loud.sh", "./stdin.sh")
      val loud = call(ws, Call.Shell("./loud.sh", Nil), shellEnv("./loud.sh"), config, check)
      val text = loud.result.toOption.flatMap(_.output).getOrElse("")
      assertEquals(loud.result.map(_.exitCode), Right(Some(0)))
      assert(text.contains("von 100000 Zeilen ausgelassen") && text.endsWith("Zeile 100000") && text.length < 4096, text.take(300))
      assertEquals(loud.touched, List("loud.sh", "."))
      val stdin = call(ws, Call.Shell("./stdin.sh", Nil), shellEnv("./stdin.sh"), config, check)
      assertEquals(stdin.result.map(_.output), Right(Some("stdin leer\n")))
      assertEquals(error(call(ws, Call.Shell("./fehlt.sh", Nil), shellEnv("./fehlt.sh"), ExecConfig.default, scripts("./fehlt.sh"))), Some(ToolError.ProgramNotFound("./fehlt.sh")))

  withWs("git gehärtet: präparierte .git/config mit fsmonitor startet kein fremdes Programm"): (ws, outside) =>
      assume(sh(ws, "git", "--version")._1 == 0, "git fehlt")
      assertEquals(sh(ws, "git", "init", "-q")._1, 0)
      val marker = outside.resolve("fsmonitor-lief")
      val hook = outside.resolve("hook.sh")
      Files.writeString(hook, s"#!/bin/sh\ntouch ${marker}\n")
      Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"))
      Files.writeString(ws.resolve(".git/config"), Files.readString(ws.resolve(".git/config")) + s"[core]\n\tfsmonitor = $hook\n")
      // Gegenprobe: ein ungehärtetes git würde den Hook starten
      sh(ws, "git", "status", "--porcelain")
      assume(Files.exists(marker), "dieses git startet fsmonitor auch ungehärtet nicht")
      Files.delete(marker)
      val r = call(ws, Call.Shell("git", List("status", "--porcelain")), all)
      assertEquals(r.result.map(_.exitCode), Right(Some(0)), r)
      assert(!Files.exists(marker), "fsmonitor lief trotz Härtung")

  withWs("Pfade: Symlink ins Leere und Elternverzeichnis als Datei"): (ws, _) =>
      Files.createSymbolicLink(ws.resolve("src/leer"), ws.resolve("gibt-es-nicht"))
      assertEquals(error(call(ws, Call.ReadFile("src/leer"))), Some(ToolError.Unresolvable("src/leer")))
      assertEquals(error(call(ws, Call.WriteFile("src/leer", "x"))), Some(ToolError.AlreadyExists("src/leer")))
      assert(!Files.exists(ws.resolve("gibt-es-nicht")))
      assertEquals(call(ws, Call.FileExists("src/A.scala")).result.map(_.output), Right(Some("true")))
      assertEquals(call(ws, Call.FileExists("src/fehlt")).result.map(_.output), Right(Some("false")))

class PlatformSuite extends munit.FunSuite:
  test("Execute auf anderer Plattform: typisierter Fehler beim Start (D#29)"):
    assertEquals(Platform.check("Windows 10"), Left(ExecuteError.UnsupportedPlatform("Windows 10")))
    assertEquals(Platform.check("Linux"), Right(()))

  test("Execute.local verweigert hier den Start, wenn das nicht Linux ist"):
    assume(!Platform.isLinux, "läuft nur außerhalb von Linux")
    val t = Term.Done("x", Origin.Human)
    val approved = ExecSupport.approve(t, Envelope(Set(OpKind.ReadFile), ".", 1, 0, Set.empty), CheckConfig.default)
    val r = ExecSupport.runIO(
      Execute.local[IO](approved, Path.of("."), _ => IO.pure(ModelReply.Failed("x", 0)), AskHandler.nonInteractive[IO], ExecConfig.default, None)
    )
    assertEquals(r.left.map(_.getClass), Left(classOf[ExecuteError.UnsupportedPlatform]))
