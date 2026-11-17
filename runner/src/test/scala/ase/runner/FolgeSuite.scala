package ase.runner

import ase.core.*
import cats.effect.unsafe.implicits.global

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** Stufenfolge (D#112): Flows nacheinander, jede Stufe für sich geprüft, eine Freigabe über den Hash der Folge, die
  * nächste Stufe nur nach done. Gegenproben nach L#10 in findings.md (D#112).
  */
class FolgeSuite extends munit.FunSuite:
  private val env = """{"allowedOps":["ReadFile","WriteFile"],"pathPrefix":".","maxSteps":10,"maxDepth":0,"shellAllowlist":[]}"""

  private def flow(term: String, params: String = "{}") =
    s"""{"description":"d","params":$params,"envelope":$env,"term":$term}"""

  private def write(text: String, path: String): String =
    s"""{"type":"WriteFile","path":"$path","content":"$text","origin":"Human"}"""
  private def seq(steps: String*) = s"""{"type":"Seq","origin":"Human","steps":[${steps.mkString(",")}]}"""
  private def abort(reason: String) = s"""{"type":"Abort","reason":"$reason","origin":"Human"}"""
  private def done = """{"type":"Done","summary":"ok","origin":"Human"}"""
  private def ifExists(path: String, thenT: String, elseT: String) =
    s"""{"type":"If","origin":"Human","pred":{"type":"FileExists","path":"$path"},"thenT":$thenT,"elseT":$elseT}"""

  /** Ein Verzeichnis mit Folge, Flows, Hülle und Konfiguration (ohne Sandbox) und einem leeren Arbeitsverzeichnis. */
  private final case class Setup(dir: Path):
    val ws: Path = Files.createDirectories(dir.resolve("ws"))
    def put(name: String, text: String): Path =
      val p = dir.resolve(name); Files.createDirectories(p.getParent); Files.writeString(p, text, UTF_8); p
    put("cfd/envelope.json", env)
    put("cfd/ase.json", """{"sandbox": false}""")
    def folge(stufen: (String, String, String)*): Path =
      val list = stufen.map((name, flowFile, werte) =>
        s"""{"name":"$name","flow":"$flowFile","envelope":"envelope.json","config":"ase.json","werte":$werte}""")
      put("cfd/f.folge.json", s"""{"description":"Deckel und Start","params":{"m":"^[a-z]+$$"},"stufen":[${list.mkString(",")}]}""")

  private def setup() = Setup(Files.createTempDirectory("folge"))

  /** ase mit der Ausgabe als Text; IO.println schreibt nach System.out. */
  private def ase(args: String*): (Int, String) =
    val buf = ByteArrayOutputStream()
    val old = System.out
    System.setOut(PrintStream(buf, true, UTF_8))
    try (Main.run(args.toList).unsafeRunSync().code, buf.toString(UTF_8))
    finally System.setOut(old)

  private def folgeHash(out: String): String =
    out.linesIterator.collectFirst { case l if l.startsWith("folge-hash ") => l.drop(11).trim }.getOrElse(fail(s"kein folge-hash in\n$out"))

  private def plan(s: Setup, values: String*): (Int, String) =
    ase(List("plan", "--folge", s.dir.resolve("cfd/f.folge.json").toString, "--out", s.dir.resolve("plan.json").toString) ++ values*)

  private def run(s: Setup, hash: String, runs: String = "runs"): (Int, String) =
    Files.createDirectories(s.dir.resolve(runs))
    ase("run", "--plan", s.dir.resolve("plan.json").toString, "--workspace", s.ws.toString, "--approve", hash,
      "--record", s.dir.resolve(s"$runs/record.log").toString, "--summary", s.dir.resolve(s"$runs/summary.json").toString, "--no-ask")

  private def deckelStart(s: Setup): Path =
    s.put("cfd/deckel.json", flow(seq(write("${m}", "deckel.txt"), done), """{"m":"^[a-z]+$"}"""))
    s.put("cfd/start.json", flow(seq(ifExists("deckel.txt", write("los", "start.txt"), abort("Deckel nicht gesetzt")))))
    s.folge(("deckel", "deckel.json", """{"m":"${m}"}"""), ("start", "start.json", "{}"))

  test("Plan und Lauf: Stufen der Reihe nach, Werte gebunden, Record und Summary je Stufe"):
    val s = setup()
    deckelStart(s)
    Files.createDirectories(s.dir.resolve("runs"))
    val (pc, pout) = plan(s, "m=klein")
    assertEquals(pc, 0, pout)
    assert(pout.contains("── Stufe deckel") && pout.contains("── Stufe start") && pout.contains("deckel → start"), pout)
    val (rc, rout) = run(s, folgeHash(pout))
    assertEquals(rc, 0, rout)
    assertEquals(Files.readString(s.ws.resolve("deckel.txt")), "klein")
    assertEquals(Files.readString(s.ws.resolve("start.txt")), "los")
    List("summary-deckel.json", "summary-start.json", "record-deckel.log", "record-start.log").foreach(f =>
      assert(Files.isRegularFile(s.dir.resolve("runs").resolve(f)), f))
    assert(rout.contains("folge     done: alle 2 Stufen"), rout)

  test("Eine Stufe, die nicht mit done endet, hält die Folge an; Exit-Code dieser Stufe"):
    val s = setup()
    s.put("cfd/deckel.json", flow(seq(abort("Deckel fehlt"))))
    s.put("cfd/start.json", flow(seq(write("los", "start.txt"), done)))
    s.folge(("deckel", "deckel.json", "{}"), ("start", "start.json", "{}"))
    Files.createDirectories(s.dir.resolve("runs"))
    val (_, pout) = plan(s, "m=x")
    val (rc, rout) = run(s, folgeHash(pout))
    assertEquals(rc, 3, rout)
    assert(!Files.exists(s.ws.resolve("start.txt")), "Stufe start lief nach einem Abbruch")
    assert(rout.contains("Stufe deckel endete mit aborted; nicht gelaufen: start"), rout)
    // Gegenprobe: dieselbe Folge mit done statt Abbruch läuft bis zum Ende
    s.put("cfd/deckel.json", flow(seq(done)))
    val (_, pout2) = plan(s, "m=x")
    val (rc2, rout2) = run(s, folgeHash(pout2), "runs2")
    assertEquals(rc2, 0, rout2)
    assert(Files.exists(s.ws.resolve("start.txt")))

  test("Freigabe: nur der Hash der Folge; der Hash einer Stufe oder ein veränderter Plan gibt nichts frei"):
    val s = setup()
    deckelStart(s)
    Files.createDirectories(s.dir.resolve("runs"))
    val (_, pout) = plan(s, "m=klein")
    val stage = pout.linesIterator.collectFirst { case l if l.startsWith("hash ") => l.drop(5).trim }.getOrElse(fail(pout))
    assertNotEquals(stage, folgeHash(pout))
    assertEquals(run(s, stage)._1, 4)
    assert(!Files.exists(s.ws.resolve("deckel.txt")), "Stufe lief ohne Freigabe der Folge")
    // ein anderer Wert in der Plan-Datei: neu geprüft ist es eine andere Folge, der alte Hash passt nicht mehr
    val p = s.dir.resolve("plan.json")
    Files.writeString(p, Files.readString(p).replace("\"klein\"", "\"gross\""))
    assertEquals(run(s, folgeHash(pout))._1, 4)
    assert(!Files.exists(s.ws.resolve("deckel.txt")))

  test("Hash der Folge: Werte, Reihenfolge und Namen zählen"):
    val s = setup()
    deckelStart(s)
    val a = folgeHash(plan(s, "m=klein")._2)
    assertEquals(folgeHash(plan(s, "m=klein")._2), a)
    assertNotEquals(folgeHash(plan(s, "m=gross")._2), a)
    s.folge(("start", "start.json", "{}"), ("deckel", "deckel.json", """{"m":"${m}"}"""))
    assertNotEquals(folgeHash(plan(s, "m=klein")._2), a)
    s.folge(("deckel", "deckel.json", """{"m":"${m}"}"""), ("los", "start.json", "{}"))
    assertNotEquals(folgeHash(plan(s, "m=klein")._2), a)

  test("Plan: eine abgelehnte Stufe legt nichts ab; geänderte Konfiguration vor dem Lauf scheitert"):
    val s = setup()
    deckelStart(s)
    s.put("cfd/start.json", flow(seq(write("x", "../draussen.txt"), done)))
    val (pc, pout) = plan(s, "m=klein")
    assertEquals(pc, 3, pout)
    assert(!Files.exists(s.dir.resolve("plan.json")))
    // Gegenprobe: ohne den Pfad außerhalb wird abgelegt
    s.put("cfd/start.json", flow(seq(write("x", "drinnen.txt"), done)))
    val (pc2, pout2) = plan(s, "m=klein")
    assertEquals(pc2, 0, pout2)
    s.put("cfd/ase.json", """{"sandbox": false, "planRounds": 2}""")
    val (rc, rout) = run(s, folgeHash(pout2))
    assertEquals(rc, 1, rout)
    assert(rout.contains("verschiedene Runner-Konfigurationen"), rout)

  test("Folge-Datei: Parameter wie beim Flow, Platzhalter nur aus der Folge, Namen eindeutig"):
    val text = """{"description":"d","params":{"m":"^[a-z]+$"},"stufen":[{"name":"a","flow":"a.json","envelope":"e.json","werte":{"x":"v-${m}"}}]}"""
    val f = Folge.parse(text).fold(e => fail(e.render), identity)
    assertEquals(Folge.bind(f, Map("m" -> "abc")).map(_.head._2), Right(Map("x" -> "v-abc")))
    assertEquals(Folge.bind(f, Map.empty), Left(FolgeError.MissingValue("m")))
    assertEquals(Folge.bind(f, Map("m" -> "abc", "n" -> "1")), Left(FolgeError.UndeclaredValue("n")))
    assert(Folge.bind(f, Map("m" -> "A B")).left.exists(_.isInstanceOf[FolgeError.InvalidValue]))
    assertEquals(Folge.bind(f.copy(params = Map("n" -> ".*")), Map("n" -> "1")), Left(FolgeError.UndeclaredPlaceholder("a", "m")))
    assertEquals(Folge.parse(text.replace("]}", ",{\"name\":\"a\",\"flow\":\"b\",\"envelope\":\"e\"}]}")), Left(FolgeError.DuplicateStage("a")))
    assert(Folge.parse(text.replace("\"stufen\":[", "\"stufen\":[],\"x\":[")).isLeft)
    assert(Folge.parse("""{"description":"d","params":{},"stufen":[]}""").isLeft)
    assertEquals(Folge.suffixed(Path.of("r/summary.json"), "deckel"), Path.of("r/summary-deckel.json"))
    assertEquals(Folge.suffixed(Path.of("r/log"), "a"), Path.of("r/log-a"))

  test("Befehlszeile: plan --folge, run ohne --envelope nur für eine Folge"):
    assertEquals(
      Cli.parse(List("plan", "--folge", "f.json", "m=x", "--out", "p.json")),
      Right(Command.PlanFolge(Path.of("f.json"), Map("m" -> "x"), Path.of("p.json"), None))
    )
    assert(Cli.parse(List("plan", "--folge", "f.json", "--envelope", "e", "--out", "p")).isLeft)
    val s = setup()
    deckelStart(s)
    val (_, pout) = plan(s, "m=klein")
    val (rc, rout) = ase("run", "--plan", s.dir.resolve("plan.json").toString, "--envelope", s.dir.resolve("cfd/envelope.json").toString,
      "--workspace", s.ws.toString, "--approve", folgeHash(pout), "--no-ask")
    assertEquals(rc, 1, rout)
    assert(rout.contains("--envelope und --config entfallen"), rout)
    // ein einzelner Plan braucht --envelope weiter
    s.put("single.json", flow(seq(done)))
    assertEquals(ase("plan", "--envelope", s.dir.resolve("cfd/envelope.json").toString, "--flow", s.dir.resolve("single.json").toString, "--out", s.dir.resolve("one.json").toString)._1, 0)
    val (c1, o1) = ase("run", "--plan", s.dir.resolve("one.json").toString, "--workspace", s.ws.toString, "--no-ask")
    assertEquals(c1, 1, o1)
    assert(o1.contains("--envelope fehlt"), o1)
