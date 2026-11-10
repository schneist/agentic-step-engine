package ase.core

import munit.FunSuite

/** Wertregeln für Flags mit Wert (D#83): Path geht durch die Pfadprüfung, Pattern prüft den ganzen Wert. */
class ArgPolicySuite extends FunSuite:
  private val h = Origin.Human
  private val put = ArgPolicy("aws", List("s3api", "put-object"), Set.empty, Set("--bucket", "--key", "--body"), Set.empty, Positional.NoArgs,
    Map("--body" -> ValueRule.Path(), "--key" -> ValueRule.Pattern("^paket/[a-z_.]+$")))
  private val run = ArgPolicy("aws", List("ec2", "run-instances"), Set.empty, Set("--user-data", "--region"), Set.empty, Positional.NoArgs,
    Map("--user-data" -> ValueRule.Path("file://")))
  private def call(args: String*) = List("s3api", "put-object") ++ args

  test("parse: ein Wert nach Regel Path ist ein Pfad des Aufrufs, mit Stelle und Präfix (D#83)"):
    val p = ArgPolicy.parse(put, call("--bucket", "b", "--key", "paket/a.txt", "--body", "paket/a.txt"))
    assertEquals(p.rejected, Nil)
    assertEquals((p.paths, p.pathAt, p.touched), (List("paket/a.txt"), List((7, "")), List("paket/a.txt")))
    val u = ArgPolicy.parse(run, List("ec2", "run-instances", "--user-data", "file://infra/userdata.sh", "--region", "eu-central-1"))
    assertEquals(u.rejected, Nil)
    assertEquals((u.paths, u.pathAt), (List("infra/userdata.sh"), List((3, "file://"))))

  test("parse: Path ohne Präfix oder leer abgelehnt, Pattern prüft den ganzen Wert, ohne Regel bleibt der Wert frei (D#83)"):
    assertEquals(ArgPolicy.parse(run, List("ec2", "run-instances", "--user-data", "infra/userdata.sh")).rejected.map(_._1), List("--user-data"))
    assertEquals(ArgPolicy.parse(run, List("ec2", "run-instances", "--user-data", "file://")).rejected.map(_._1), List("--user-data"))
    assertEquals(ArgPolicy.parse(put, call("--key", "../x", "--body", "paket/a.txt")).rejected.map(_._1), List("--key"))
    assertEquals(ArgPolicy.parse(put, call("--key", "paket/a.txt;rm", "--body", "paket/a.txt")).rejected.map(_._1), List("--key"))
    assertEquals(ArgPolicy.parse(put, call("--bucket", "../anything")).rejected, Nil)

  test("ToolCheck: ein Path-Wert außerhalb der Hülle oder absolut wird abgelehnt, sonst normalisiert mit Präfix (D#83)"):
    val env = Envelope(Set(OpKind.Shell), "paket", 5, 0, Set(AllowedCommand("aws", Nil)))
    val ok = ToolCheck.shell("aws", call("--key", "paket/a.txt", "--body", "paket/a.txt"), env, List(put))
    assertEquals(ok.map(_.touched), Right(List("paket/a.txt")))
    assert(ToolCheck.shell("aws", call("--key", "paket/a.txt", "--body", "stand/x"), env, List(put)).isLeft)
    assert(ToolCheck.shell("aws", call("--key", "paket/a.txt", "--body", "../a"), env, List(put)).isLeft)
    val root = Envelope(Set(OpKind.Shell), ".", 5, 0, Set(AllowedCommand("aws", Nil)))
    val ud = ToolCheck.shell("aws", List("ec2", "run-instances", "--user-data", "file://infra/userdata.sh"), root, List(run))
    assertEquals(ud.map(_.args.last), Right("file://infra/userdata.sh"))
    assert(ToolCheck.shell("aws", List("ec2", "run-instances", "--user-data", "file:///etc/passwd"), root, List(run)).isLeft)

  test("DryRun und Scope: der Path-Wert zählt zu den Pfaden des Aufrufs (D#83)"):
    val config = TestSupport.config.copy(argPolicies = TestSupport.config.argPolicies :+ put)
    val env = Envelope(Set(OpKind.Shell), "paket", 5, 0, Set(AllowedCommand("aws", Nil)))
    assert(DryRun.plan(Term.Shell("aws", call("--key", "paket/a.txt", "--body", "paket/a.txt"), h), env, config).isRight)
    assert(DryRun.plan(Term.Shell("aws", call("--key", "paket/a.txt", "--body", "stand/x"), h), env, config).isLeft)
    assert(DryRun.plan(Term.Shell("aws", call("--key", "x", "--body", "paket/a.txt"), h), env, config).isLeft)
    assertEquals(Scope.shellPaths("aws", call("--key", "paket/a.txt", "--body", "paket/a.txt"), config), List("paket/a.txt"))

  private val grep = ArgPolicy.defaults.find(_.binary == "grep").get

  test("grep: -A, -B, -C mit einer Zahl; das Muster bleibt Muster, die Pfade bleiben Pfade (D#99)"):
    val p = ArgPolicy.parse(grep, List("-rn", "-A", "3", "-B", "2", "TODO", "src/a.scala"))
    assertEquals((p.rejected, p.paths), (Nil, List("src/a.scala")))
    assertEquals(ArgPolicy.parse(grep, List("-C", "999", "x", "src")).rejected, Nil)
    assertEquals(ArgPolicy.parse(grep, List("-C", "0", "x")).touched, List("."))

  test("grep: Kontext ohne Zahl, zu groß, angehängt, doppelt oder -e wird abgelehnt (D#99)"):
    def rejected(args: String*) = ArgPolicy.parse(grep, args.toList).rejected.map(_._1)
    assertEquals(rejected("-A", "x", "src"), List("-A"))         // Muster statt Zahl als Wert
    assertEquals(rejected("-A", "1000", "x"), List("-A"))
    assertEquals(rejected("-C", "3x", "x"), List("-C"))
    assertEquals(rejected("-A3", "x"), List("-A3"))               // nur getrennt: -A 3
    assertEquals(rejected("-A", "2", "-A", "3", "x"), List("-A")) // jedes Flag mit Wert höchstens einmal (D#87)
    assertEquals(rejected("-e", "x", "src"), List("-e"))

  test("grep: mit Kontextzeilen bleibt die Pfadprüfung (D#99)"):
    val env = Envelope(Set(OpKind.Shell), "src", 5, 0, Set(AllowedCommand("grep", Nil)))
    assert(ToolCheck.shell("grep", List("-n", "-C", "3", "x", "src/a.scala"), env, ArgPolicy.defaults).isRight)
    assert(ToolCheck.shell("grep", List("-n", "-C", "3", "x", "../a.scala"), env, ArgPolicy.defaults).isLeft)
    assert(ToolCheck.shell("grep", List("-n", "-C", "3", "x", "docs/a.md"), env, ArgPolicy.defaults).isLeft)

  test("wellFormed: ein Befehl mit Path-Regel nimmt Pfade und ist unter einem engeren pathPrefix nutzbar (D#83)"):
    val config = TestSupport.config.copy(argPolicies = TestSupport.config.argPolicies ++ List(put, run))
    val under = AllowedCommand("aws", List("s3api", "put-object"))
    assertEquals(Attenuation.wellFormed(Envelope(Set(OpKind.Shell), "paket", 5, 0, Set(under)), Location.root, config), Nil)
    // git status hat keine Path-Regel und keine Positionsargumente (D#27)
    val noPaths = Attenuation.wellFormed(Envelope(Set(OpKind.Shell), "paket", 5, 0, Set(AllowedCommand("git", List("status")))), Location.root, config)
    assert(noPaths.exists(_.render.contains("nimmt keine Pfade")), noPaths.map(_.render).toString)
