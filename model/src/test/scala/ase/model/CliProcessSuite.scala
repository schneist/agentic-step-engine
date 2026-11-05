package ase.model

import ase.core.Platform

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

/** D#93: Vertrauensgrenze der Modellaufrufe (O#50). */
class CliProcessSuite extends munit.FunSuite:

  private def exe(dir: Path, name: String, body: String): Path =
    val p = dir.resolve(name)
    Files.writeString(p, "#!/bin/sh\n" + body)
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"))
    p

  test("Endpunkt: https immer, http nur zum eigenen Rechner, CLI-Anbieter hier nicht geprüft"):
    val a = ModelConfig.default
    assertEquals(ModelConfig.checkEndpoint(a), Right(()))
    assert(ModelConfig.checkEndpoint(a.copy(endpoint = "http://api.anthropic.com/v1/messages")).isLeft)
    assert(ModelConfig.checkEndpoint(a.copy(endpoint = "ftp://x/y")).isLeft)
    assert(ModelConfig.checkEndpoint(a.copy(endpoint = "api.anthropic.com/v1")).isLeft)
    val o = ModelConfig.ollama
    for ok <- List("http://127.0.0.1:11434/api/chat", "http://localhost:11434/api/chat", "http://[::1]:11434/api/chat", "HTTPS://gpu.example.com/api/chat") do
      assertEquals(ModelConfig.checkEndpoint(o.copy(endpoint = ok)), Right(()), ok)
    assert(ModelConfig.checkEndpoint(o.copy(endpoint = "http://gpu.example.com:11434/api/chat")).isLeft)
    assert(ModelConfig.checkEndpoint(o.copy(endpoint = "http://127.0.0.1.example.com/api/chat")).isLeft)
    assertEquals(ModelConfig.checkEndpoint(ModelConfig.claudeCode.copy(endpoint = "./claude")), Right(()))

  test("Umgebung: nur die Liste und PATH ohne relative Einträge und ohne gesperrte Verzeichnisse"):
    val ws = Files.createTempDirectory("cli-ws")
    Files.createDirectories(ws.resolve("bin"))
    val env = Map(
      "HOME" -> "/home/x",
      "PATH" -> s"/usr/bin:bin:${ws.resolve("bin")}:/bin",
      "ANTHROPIC_API_KEY" -> "geheim",
      "AWS_SECRET_ACCESS_KEY" -> "geheim",
      "WSL_INTEROP" -> "/run/WSL/1_interop"
    )
    val e = CliProcess.environment(env, List(ws))
    assertEquals(e, Map("HOME" -> "/home/x", "PATH" -> "/usr/bin:/bin", "WSL_INTEROP" -> "/run/WSL/1_interop"))

  test("Programm: Name aus PATH, absoluter Pfad, nie relativ und nie unter einem gesperrten Verzeichnis"):
    assume(Platform.isLinux, s"Ausführungsbits nur unter Linux, hier ${Platform.current}")
    val ws = Files.createTempDirectory("cli-ws")
    val inWs = Files.createDirectories(ws.resolve("bin"))
    val outside = Files.createTempDirectory("cli-bin")
    exe(inWs, "modell", "exit 0\n")
    val good = exe(outside, "anderes", "exit 0\n")
    val env = Map("PATH" -> s"$inWs:$outside")
    assert(CliProcess.locate("./bin/modell", env, List(ws)).left.exists(_.contains("relativer")))
    assert(CliProcess.locate("bin/modell", env, List(ws)).left.exists(_.contains("relativer")))
    // im PATH, aber unter dem Arbeitsverzeichnis: zählt nicht
    assert(CliProcess.locate("modell", env, List(ws)).isLeft)
    assert(CliProcess.locate(inWs.resolve("modell").toString, env, List(ws)).isLeft)
    assertEquals(CliProcess.locate("anderes", env, List(ws)), Right(good))
    assertEquals(CliProcess.locate(good.toString, env, List(ws)), Right(good))
    // ein Symlink außerhalb, der ins Arbeitsverzeichnis zeigt, zählt aufgelöst
    val link = Files.createSymbolicLink(outside.resolve("verweis"), inWs.resolve("modell"))
    assert(CliProcess.locate(link.toString, env, List(ws)).isLeft)
    assert(CliProcess.locate("verweis", env, List(ws)).isLeft)
    // nicht ausführbar
    val plain = outside.resolve("text")
    Files.writeString(plain, "x")
    assert(CliProcess.locate(plain.toString, env, Nil).isLeft)

  test("Prozess: sieht nur die übergebene Umgebung"):
    assume(Platform.isLinux, s"Fake-Programm als Shell-Skript nur unter Linux, hier ${Platform.current}")
    val bin = Files.createTempDirectory("cli-bin")
    val p = exe(bin, "zeige", "printf '%s|%s' \"${GEHEIM-fehlt}\" \"$HOME\"\n")
    val env = CliProcess.environment(sys.env + ("GEHEIM" -> "x") + ("HOME" -> "/home/probe"), Nil)
    val r = CliProcess.run("zeige", List(p.toString), Map.empty, "", Files.createTempDirectory("cli-work"), 10.seconds, env)
    assertEquals(r.map(_.body), Right("fehlt|/home/probe"))

  test("Prozess: bei Zeitüberschreitung enden auch die Kindprozesse"):
    assume(Platform.isLinux, s"Fake-Programm als Shell-Skript nur unter Linux, hier ${Platform.current}")
    val bin = Files.createTempDirectory("cli-bin")
    val pidFile = bin.resolve("kind.pid")
    val p = exe(bin, "haengt", s"sleep 300 &\necho $$! > $pidFile\nwait\n")
    val env = CliProcess.environment(sys.env, Nil)
    val r = CliProcess.run("haengt", List(p.toString), Map.empty, "", Files.createTempDirectory("cli-work"), 0.seconds, env, grace = 2.seconds)
    assert(r.left.exists(_.contains("antwortet nicht")), r)
    val pid = Files.readString(pidFile).trim.toLong
    val child = ProcessHandle.of(pid)
    child.ifPresent(h => { h.onExit().get(10, TimeUnit.SECONDS); () })
    assert(!ProcessHandle.of(pid).map(_.isAlive).orElse(false), s"Kind $pid läuft noch")
