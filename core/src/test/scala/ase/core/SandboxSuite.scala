package ase.core

import ase.core.ExecSupport.*
import cats.effect.IO

import java.net.{InetAddress, ServerSocket}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Sandbox je Programmaufruf (D#88), gegen echte Prozesse. Nur unter Linux mit bwrap, sonst sichtbar übersprungen. */
class SandboxSuite extends munit.FunSuite:
  override def munitTimeout: Duration = 2.minutes

  private val hasBwrap = List("/usr/bin/bwrap", "/bin/bwrap").exists(p => Files.isExecutable(Path.of(p)))

  /** Ein Skript, das tut, was ein ungeprüftes Skript im Repository tun könnte; bash -c steht für ein Programm mit
    * Zusätzen, dessen Argumente die Engine nicht deuten kann.
    */
  private val bash = ArgPolicy("bash", Nil, Set.empty, Set("-c"), Set.empty, Positional.NoArgs)
  private val check = CheckConfig.default.copy(argPolicies = ArgPolicy.defaults ++ List(ArgPolicy.script("./x.sh"), bash))
  private val env = Envelope(Set(OpKind.Shell), ".", 10, 0, Set(AllowedCommand("./x.sh", Nil), AllowedCommand("bash", Nil)))

  private def withWs(name: String)(body: (Path, Path) => Unit): Unit =
    test(name) {
      assume(Platform.isLinux, s"Execute nur unter Linux (D#29), hier ${Platform.current}")
      assume(hasBwrap, "bwrap fehlt (D#88)")
      val base = tempDir("ase-sandbox")
      val ws = Files.createDirectories(base.resolve("ws"))
      val outside = Files.createDirectories(base.resolve("outside"))
      Files.writeString(outside.resolve("secret.txt"), "geheim\n")
      try body(ws, outside)
      finally deleteTree(base)
    }

  private def script(ws: Path, body: String): Unit =
    val f = ws.resolve("x.sh")
    Files.deleteIfExists(f)
    Files.writeString(f, "#!/bin/bash\n" + body + "\n")
    Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"))

  private def run(ws: Path, c: Call, sandbox: Sandbox = Sandbox.default): StepResult =
    val tools = runIO(LocalTools.open[IO](ws, ExecConfig.default.copy(sandbox = sandbox), check)).fold(e => fail(e.message), identity)
    runIO(tools.call(c, env)).result.fold(e => fail(e.toString), identity)

  private val x = Call.Shell("./x.sh", Nil)

  withWs("ein Skript liest und schreibt außerhalb des Arbeitsverzeichnisses nichts; ohne Sandbox schon"): (ws, outside) =>
    script(ws, s"""cat "${outside.resolve("secret.txt")}"; echo x > "${outside.resolve("neu.txt")}"; echo im > drinnen.txt""")
    val r = run(ws, x)
    assert(!r.output.exists(_.contains("geheim")), r)
    assert(!Files.exists(outside.resolve("neu.txt")))
    // das Arbeitsverzeichnis bleibt schreibbar
    assertEquals(Files.readString(ws.resolve("drinnen.txt")), "im\n")
    // Gegenprobe: ohne Sandbox liest und schreibt das Skript draußen (die Lücke, die D#88 schließt)
    val open = run(ws, x, Sandbox.Off)
    assert(open.output.exists(_.contains("geheim")), open)
    assert(Files.exists(outside.resolve("neu.txt")))

  withWs("HOME ist leer, Geheimnisse in /etc überdeckt, TMPDIR ist das eigene /tmp"): (ws, _) =>
    script(ws, """ls -A "$HOME" | wc -l; cat /etc/shadow 2>/dev/null | wc -c; echo "tmp=$TMPDIR"; ls -A /tmp""")
    val lines = run(ws, x).output.getOrElse("").linesIterator.map(_.trim).toList
    // in /tmp steht nur der Weg zum eingehängten Arbeitsverzeichnis, nicht das Verzeichnis daneben
    val tmpEntry = ws.getParent.getFileName.toString
    assertEquals(lines, List("0", "0", s"tmp=${if sys.env.contains("TMPDIR") then "/tmp" else ""}", tmpEntry))

  withWs("kein Netz in der Sandbox, auch nicht zu 127.0.0.1; mit Zusatz net ein Programm schon, ein Skript nie"): (ws, _) =>
    val server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress)
    try
      val port = server.getLocalPort
      val connect = s"""(exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null && echo verbunden || echo kein Netz"""
      script(ws, connect)
      assertEquals(run(ws, x).output.map(_.trim), Some("kein Netz"))
      // Zusätze gelten für Programme mit Namen; das Skript bekommt sie nicht, auch wenn die Tabelle es versuchte
      val withNet = Sandbox.On(Map("bash" -> Extra(net = true, Nil), "./x.sh" -> Extra(net = true, Nil)))
      assertEquals(run(ws, x, withNet).output.map(_.trim), Some("kein Netz"))
      // im eigenen Netz-Namespace wäre 127.0.0.1 ein anderes Gerät; mit net teilt bash das Netz des Runners
      val viaBash = Call.Shell("bash", List("-c", connect))
      assertEquals(run(ws, viaBash).output.map(_.trim), Some("kein Netz"))
      assertEquals(run(ws, viaBash, withNet).output.map(_.trim), Some("verbunden"))
    finally server.close()

  withWs("Einhängungen: lesbar heißt nur lesen, schreibbar wird angelegt, wenn es fehlt"): (ws, outside) =>
    val stub = ws.getParent.resolve(".stub")
    val mounts = Sandbox.On(Map("bash" -> Extra(net = false, List(Mount("../outside", writable = false), Mount("../.stub", writable = true)))))
    val read = run(ws, Call.Shell("bash", List("-c", s"""cat "${outside.resolve("secret.txt")}"; echo x > "${outside.resolve("w")}" 2>&1 || true""")), mounts)
    assert(read.output.exists(_.contains("geheim")), read)
    assert(!Files.exists(outside.resolve("w")))
    run(ws, Call.Shell("bash", List("-c", s"""echo zustand > "$stub/state" """)), mounts)
    assertEquals(Files.readString(stub.resolve("state")), "zustand\n")
    // ein Skript bekommt die Einhängungen nicht
    script(ws, s"""cat "${outside.resolve("secret.txt")}" 2>&1 | head -1""")
    assert(!run(ws, x, mounts).output.exists(_.contains("geheim")))

  withWs("ein Hintergrundprozess endet mit dem Aufruf, ohne Sandbox bleibt er (D#43)"): (ws, _) =>
    val marker = s"sleep ${4000 + scala.util.Random.nextInt(900)}"
    def left = ExecSupport.sh(ws, "pgrep", "-fc", marker)._2.trim
    script(ws, s"$marker & echo gestartet")
    try
      run(ws, x)
      Thread.sleep(500)
      assertEquals(left, "0")
      run(ws, x, Sandbox.Off)
      Thread.sleep(500)
      assertEquals(left, "1")
    finally ExecSupport.sh(ws, "pkill", "-f", marker)

  withWs("open: eine Einhängung, die das Arbeitsverzeichnis enthält oder darin liegt, startet nichts"): (ws, _) =>
    List("..", ".", "sub", "/").foreach { p =>
      val bad = Sandbox.On(Map("bash" -> Extra(net = false, List(Mount(p, writable = false)))))
      val opened = runIO(LocalTools.open[IO](ws, ExecConfig.default.copy(sandbox = bad), check))
      assert(opened.left.exists(_.isInstanceOf[ExecuteError.SandboxUnavailable]), s"$p: $opened")
    }

  test("describe: eine Zeile für Explain, Zusätze nach Programm sortiert; aus ist deutlich"):
    val on = Sandbox.On(Map("sbt" -> Extra(false, List(Mount("~/.sbt", writable = true))), "aws" -> Extra(true, List(Mount("~/.aws", writable = false)))))
    assertEquals(
      Sandbox.describe(on),
      "jeder Aufruf sieht nur das Arbeitsverzeichnis und Systemverzeichnisse (lesbar), ohne Netz; dazu aws: Netz, ~/.aws lesbar · sbt: ~/.sbt schreibbar"
    )
    assert(Sandbox.describe(Sandbox.Off).startsWith("AUS"))
    assertEquals(Sandbox.extraFor(on, "./aws"), Extra(net = false, Nil))
