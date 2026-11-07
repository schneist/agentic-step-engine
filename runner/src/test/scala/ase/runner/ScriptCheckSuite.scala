package ase.runner

import ase.core.*

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions

/** D#94: Vorprüfung der Skripte eines Terms (O#46). */
class ScriptCheckSuite extends munit.FunSuite:
  private val h = Origin.Human
  private def sh(cmd: String, args: String*) = Term.Shell(cmd, args.toList, h)
  private val env = Envelope(Set(OpKind.ReadFile), ".", 2, 0, Set.empty)

  test("scripts: Shell und Abnahmen, beide Zweige, Schleifen, ohne Doppelte; Programme und Inhalt der Löcher nicht"):
    val t = Term.Seq(
      List(
        sh("./a.sh"),
        sh("python3", "x.py"),
        Term.If(Pred.LastExitCode(0), sh("./b.sh"), Term.Seq(List(sh("./c/d.sh"), sh("./a.sh")), h), h),
        Term.RepeatUntil(sh("./e.sh"), Pred.LastExitCode(0), 3, h),
        Term.Hole("ziel", env, h, Some(Verify("./pruefen.sh", Nil, Pred.LastExitCode(0), 2))),
        Term.Hole("ohne", env, h),
        Term.Done("fertig", h)
      ),
      h
    )
    assertEquals(ScriptCheck.scripts(t), List("./a.sh", "./b.sh", "./c/d.sh", "./e.sh", "./pruefen.sh"))
    assertEquals(ScriptCheck.scripts(sh("git", "status")), Nil)

  test("problems: fehlt, nicht ausführbar, Verzeichnis, Symlink hinaus und ins Leere; ausführbar ist ok"):
    assume(Platform.isLinux, s"Ausführungsbits nur unter Linux, hier ${Platform.current}")
    val ws = Files.createTempDirectory("skripte-ws")
    val outside = Files.createTempDirectory("skripte-aussen")
    def file(dir: Path, name: String, perm: String): Path =
      val p = dir.resolve(name)
      Files.createDirectories(p.getParent)
      Files.writeString(p, "#!/bin/sh\nexit 0\n")
      Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perm))
      p
    file(ws, "gut.sh", "rwxr-xr-x")
    file(ws, "sub/auch.sh", "rwx------")
    file(ws, "ohne_bit.sh", "rw-r--r--")
    Files.createDirectories(ws.resolve("ordner.sh"))
    Files.createSymbolicLink(ws.resolve("hinaus.sh"), file(outside, "fremd.sh", "rwxr-xr-x"))
    Files.createSymbolicLink(ws.resolve("leer.sh"), ws.resolve("gibt_es_nicht"))
    Files.createSymbolicLink(ws.resolve("drinnen.sh"), ws.resolve("gut.sh"))
    assertEquals(ScriptCheck.problems(ws, List("./gut.sh", "./sub/auch.sh", "./drinnen.sh")), Nil)
    val found = ScriptCheck.problems(ws, List("./fehlt.sh", "./ohne_bit.sh", "./ordner.sh", "./hinaus.sh", "./leer.sh", "./../x.sh"))
    assertEquals(found.size, 6, found.mkString("\n"))
    def reason(s: String) = found.find(_.startsWith(s + ":")).getOrElse(fail(s"kein Befund für $s"))
    assert(reason("./fehlt.sh").contains("fehlt"))
    assert(reason("./ohne_bit.sh").contains("nicht ausführbar"))
    assert(reason("./ordner.sh").contains("keine normale Datei"))
    assert(reason("./hinaus.sh").contains("heraus"))
    assert(reason("./leer.sh").contains("ins Leere"))
    assert(reason("./../x.sh").contains("nicht im Arbeitsverzeichnis"))
    // ohne Skripte wird nichts gelesen, auch kein fehlendes Arbeitsverzeichnis
    assertEquals(ScriptCheck.problems(ws.resolve("gibt_es_nicht"), Nil), Nil)
    assert(ScriptCheck.problems(ws.resolve("gibt_es_nicht"), List("./gut.sh")).head.contains("nicht lesbar"))

  test("render: reject und warn mit je einer Zeile pro Befund"):
    val r = ScriptCheck.render(ScriptCheck.Mode.Reject, List("./a.sh: fehlt im Arbeitsverzeichnis"))
    assert(r.startsWith("skripte   abgelehnt"), r)
    assert(r.linesIterator.toList(1).trim == "./a.sh: fehlt im Arbeitsverzeichnis", r)
    assert(ScriptCheck.render(ScriptCheck.Mode.Warn, List("x")).startsWith("skripte   Warnung"))

  test("Konfiguration: scriptCheck reject als Standard, warn wählbar, anderes ein Fehler"):
    assertEquals(RunnerConfig.parse("{}").map(_.scriptCheck), Right(ScriptCheck.Mode.Reject))
    assertEquals(RunnerConfig.default.scriptCheck, ScriptCheck.Mode.Reject)
    assertEquals(RunnerConfig.parse("""{"scriptCheck":"warn"}""").map(_.scriptCheck), Right(ScriptCheck.Mode.Warn))
    assertEquals(RunnerConfig.parse("""{"scriptCheck":"reject"}""").map(_.scriptCheck), Right(ScriptCheck.Mode.Reject))
    assert(RunnerConfig.parse("""{"scriptCheck":"aus"}""").left.exists(_.contains("scriptCheck")))
    assert(RunnerConfig.parse("""{"scriptCheck":false}""").isLeft)

  test("Befehlszeile: ase plan nimmt --workspace, ohne bleibt es leer"):
    val base = List("plan", "--envelope", "e.json", "--flow", "f.json", "--out", "p.json")
    assertEquals(Cli.parse(base :+ "--workspace" :+ "/tmp/w").map { case p: Command.Plan => p.workspace; case _ => None }, Right(Some(Path.of("/tmp/w"))))
    assertEquals(Cli.parse(base).map { case p: Command.Plan => p.workspace; case _ => Some(Path.of("x")) }, Right(None))
