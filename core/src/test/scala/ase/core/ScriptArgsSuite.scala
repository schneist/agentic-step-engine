package ase.core

/** Skripte mit Pfadargumenten (D#100, O#55): nur wo die Konfiguration positional Paths nennt, jeder Pfad geprüft wie
  * bei ReadFile, keine Flags; ein Skript berührt auch mit Pfaden das ganze Arbeitsverzeichnis.
  */
class ScriptArgsSuite extends munit.FunSuite:
  private val h = Origin.Human
  private val pruefen = "./ase/bericht_pruefen.sh"
  private val config = CheckConfig.default.copy(argPolicies =
    ArgPolicy.defaults ++ List(ArgPolicy.scriptAs(pruefen, Positional.Paths), ArgPolicy.script("./run-tests.sh"))
  )
  private val root = Envelope(OpKind.values.toSet, ".", 20, 1, Set(AllowedCommand(pruefen, Nil), AllowedCommand("./run-tests.sh", Nil)))

  private def violations(t: Term, env: Envelope = root): List[Violation] =
    DryRun.plan(t, env, config).left.toOption.map(_.toList).getOrElse(Nil)
  private def sh(cmd: String, args: String*) = Term.Shell(cmd, args.toList, h)

  test("parse: ein Skript berührt mit Pfaden auch \".\", ohne Pfade nur \".\"; andere Programme nur ihre Pfade"):
    val p = ArgPolicy.parse(ArgPolicy.scriptAs(pruefen, Positional.Paths), List("PRUEFUNG_V1.md", "stand/a.txt"))
    assertEquals(p.rejected, Nil)
    assertEquals(p.touchedBy(pruefen), List(".", "PRUEFUNG_V1.md", "stand/a.txt"))
    assertEquals(ArgPolicy.parse(ArgPolicy.script("./run-tests.sh"), Nil).touchedBy("./run-tests.sh"), List("."))
    val ls = ArgPolicy.parse(ArgPolicy.defaults.head, List("docs"))
    assertEquals(ls.touchedBy("ls"), List("docs"))

  test("Tor 1: Pfade angenommen, Flags, .. und absolute Pfade abgelehnt, Skript ohne Paths nimmt keine Argumente"):
    assertEquals(violations(sh(pruefen, "PRUEFUNG_V1.md")), Nil)
    assertEquals(violations(sh(pruefen)), Nil)
    assert(violations(sh(pruefen, "-v", "PRUEFUNG_V1.md")).exists {
      case Violation.ShellArgRejected(_, `pruefen`, "-v", _) => true; case _ => false
    })
    assert(violations(sh(pruefen, "--")).exists { case _: Violation.ShellArgRejected => true; case _ => false })
    assert(violations(sh(pruefen, "../geheim.md")).exists { case _: Violation.InvalidPath => true; case _ => false })
    assert(violations(sh(pruefen, "/etc/passwd")).exists { case _: Violation.InvalidPath => true; case _ => false })
    assert(violations(sh(pruefen, ".git/config")).exists { case _: Violation.InvalidPath => true; case _ => false })
    assert(violations(sh("./run-tests.sh", "PRUEFUNG_V1.md")).exists {
      case Violation.ShellArgRejected(_, "./run-tests.sh", "PRUEFUNG_V1.md", _) => true; case _ => false
    })

  test("Tor 1: unter einem engeren pathPrefix läuft ein Skript auch mit Pfaden darunter nicht"):
    val inAse = root.copy(pathPrefix = "ase")
    assert(violations(sh(pruefen, "ase/a.md"), inAse).exists {
      case Violation.PathOutsidePrefix(_, ".", "ase") => true; case _ => false
    }, violations(sh(pruefen, "ase/a.md"), inAse))

  test("Abnahme mit Pfad und Flow-Parameter (der Fall aus ducktest)"):
    val hole = Envelope(Set(OpKind.ReadFile, OpKind.WriteFile), "PRUEFUNG_V${v}.md", 5, 0, Set.empty)
    val verify = Verify(pruefen, List("PRUEFUNG_V${v}.md"), Pred.LastExitCode(0), 2)
    val flow = Flow("bericht", Map("v" -> "^[0-9]+$"), root, Term.Hole("Bericht schreiben", hole, h, Some(verify)))
    val bound = Flow.bind(flow, Map("v" -> "139"), root).fold(e => fail(e.toString), identity)
    val checked = DryRun.plan(bound.term, root, config).fold(vs => fail(vs.toList.mkString("\n")), identity)
    checked.term match
      case Term.Hole(_, _, _, Some(v)) => assertEquals(v.args, List("PRUEFUNG_V139.md"))
      case other                        => fail(s"erwartet ein Hole mit verify, war $other")

  test("ToolCheck: Pfade normalisiert ans Skript, berührt \".\" und die Pfade; Flag abgelehnt"):
    assertEquals(
      ToolCheck.shell(pruefen, List("stand//a.txt"), root, config.argPolicies),
      Right(ToolCheck.ShellCall(List(".", "stand/a.txt"), List("stand/a.txt")))
    )
    assert(ToolCheck.shell(pruefen, List("-x"), root, config.argPolicies).isLeft)
    assert(ToolCheck.shell(pruefen, List("ase/a.md"), root.copy(pathPrefix = "ase"), config.argPolicies).isLeft)

  test("Scope: Ergebnis eines Skripts mit Pfaden trägt \".\", sieht also nur ein Loch, das alles sieht; ohne Pfade pfadlos"):
    assertEquals(Scope.shellPaths(pruefen, List("PRUEFUNG_V1.md"), config), List(".", "PRUEFUNG_V1.md"))
    assertEquals(Scope.shellPaths(pruefen, Nil, config), Nil)
    val report = Envelope(Set(OpKind.ReadFile), "PRUEFUNG_V1.md", 3, 0, Set.empty)
    assertEquals(Scope.visible(Vector(Scope.Seen(1, List(".", "PRUEFUNG_V1.md"))), report), Vector.empty)
