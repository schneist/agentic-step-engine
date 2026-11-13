package ase.core

/** Programm auf ein Werkzeugskript festgelegt (D#101, O#56): `python3 ase/kette.py zeige VON-BIS DATEI`. Der Pfad
  * an erster Stelle der Pflicht-Argumente ist das Skript; ein Aufruf berührt wie ein Skript aus D#100 das ganze
  * Arbeitsverzeichnis, dazu das Skript selbst.
  */
class ToolScriptSuite extends munit.FunSuite:
  private val h = Origin.Human
  private val kette = "ase/kette.py"
  private val zeige = ArgPolicy("python3", List(kette, "zeige"), Set.empty, Set.empty, Set.empty, Positional.PatternThenPaths)
  private val config = CheckConfig.default.copy(
    argPolicies = ArgPolicy.defaults :+ zeige,
    workspaceCode = CheckConfig.default.workspaceCode.updated("python3", List(kette))
  )
  private val only = AllowedCommand("python3", List(kette, "zeige"))
  private val root = Envelope(Set(OpKind.ReadFile, OpKind.Shell), ".", 10, 0, Set(only))

  private def violations(t: Term, env: Envelope = root): List[Violation] =
    DryRun.plan(t, env, config).left.toOption.map(_.toList).getOrElse(Nil)
  private def sh(args: String*) = Term.Shell("python3", args.toList, h)

  test("script: nur ein Pfad an erster Stelle; eingebaute Policies haben keins"):
    assertEquals(zeige.script, Some(kette))
    assertEquals(ArgPolicy.defaults.flatMap(_.script), Nil)
    assertEquals(ArgPolicy("git", List("log"), Set.empty, Set.empty, Set.empty, Positional.NoArgs).script, None)

  test("parse: Bereich als Muster, Datei als Pfad; berührt \".\", das Skript und die Datei"):
    val p = ArgPolicy.parse(zeige, List(kette, "zeige", "120-180,300-310", "widerstand.py"))
    assertEquals(p.rejected, Nil)
    assertEquals(p.paths, List("widerstand.py"))
    assertEquals(p.touchedBy("python3"), List(".", kette, "widerstand.py"))
    assertEquals(ArgPolicy.parse(zeige, List(kette, "zeige")).touchedBy("python3"), List(".", kette))

  test("Tor 1: die Hülle kann python3 auf das Skript festlegen (wellFormed); andere Aufrufe abgelehnt"):
    assertEquals(Attenuation.wellFormed(root, Location.root, config), Nil)
    assertEquals(violations(sh(kette, "zeige", "120-180", "widerstand.py")), Nil)
    // anderes Skript, anderer Unterbefehl, Flag, Pfad nach draußen
    List(List("ase/anders.py", "zeige", "1-2", "a.py"), List(kette, "lauf"), List(kette, "zeige", "-v", "1-2", "a.py"),
      List(kette, "zeige", "1-2", "../a.py"))
      .foreach(args => assert(violations(sh(args*)).nonEmpty, args.toString))

  test("Tor 1: unter einem engeren pathPrefix läuft das Skript nicht, auch mit der Datei darunter"):
    val inSrc = root.copy(pathPrefix = "src")
    assert(violations(sh(kette, "zeige", "1-2", "src/a.py"), inSrc).exists {
      case Violation.PathOutsidePrefix(_, ".", "src") => true; case _ => false
    }, violations(sh(kette, "zeige", "1-2", "src/a.py"), inSrc))

  test("Abnahme: ein Loch, das das Skript ändern darf, darf es nicht als Abnahme laufen lassen (D#80)"):
    val hole = Envelope(Set(OpKind.EditFile), "ase", 3, 0, Set.empty)
    val verify = Verify("python3", List(kette, "zeige", "1-2", "a.py"), Pred.LastExitCode(0), 2)
    val t = Term.Hole("ändern", hole, h, Some(verify))
    val env = root.copy(allowedOps = OpKind.values.toSet, maxDepth = 1)
    assert(violations(t, env).exists { case Violation.VerifierWritable(_, `kette`, "ase") => true; case _ => false },
      violations(t, env))

  test("Abnahme (O#39, Fund 4): Werkzeugskript nimmt ein Loch auf einer anderen Datei ab, solange runsCode nur das Skript nennt"):
    // ein Loch ändert widerstand.py; abgenommen wird mit python3 ase/kette.py … (wie textkorrektur.json)
    val lauf = ArgPolicy("python3", List(kette, "lauf"), Set.empty, Set.empty, Set.empty, Positional.Paths)
    def mit(runsCode: List[String]) = CheckConfig.default.copy(
      argPolicies = ArgPolicy.defaults :+ lauf,
      workspaceCode = CheckConfig.default.workspaceCode.updated("python3", runsCode)
    )
    val hole = Envelope(Set(OpKind.EditFile, OpKind.ReadFile), "widerstand.py", 3, 0, Set.empty)
    val verify = Verify("python3", List(kette, "lauf", "widerstand.py"), Pred.LastExitCode(0), 2)
    val env = Envelope(OpKind.values.toSet, ".", 20, 1, Set(AllowedCommand("python3", List(kette, "lauf"))))
    val t = Term.Hole("korrigieren", hole, h, Some(verify))
    def writable(c: CheckConfig) = DryRun.plan(t, env, c).left.toOption.toList.flatMap(_.toList).collect {
      case Violation.VerifierWritable(_, code, _) => code
    }
    // (a) nur das Skript: DryRun lässt die Abnahme zu
    assertEquals(writable(mit(List(kette))), Nil)
    // (b) ehrlich, was kette.py lauf ausführt: auch die Datei des Lochs, dann lehnt D#80 ab wie bei sbt unter src
    assertEquals(writable(mit(List(kette, "widerstand.py"))), List("widerstand.py"))
    // (c) die heutige Konfiguration von ducktest (python3 mit runsCode ".") lehnt ebenfalls ab
    assertEquals(writable(mit(List(".", kette))), List("."))

  test("ToolCheck: berührt \".\", das Skript und die normalisierte Datei; Skript bleibt, wie es steht"):
    assertEquals(
      ToolCheck.shell("python3", List(kette, "zeige", "1-2", "src//a.py"), root, config.argPolicies),
      Right(ToolCheck.ShellCall(List(".", kette, "src/a.py"), List(kette, "zeige", "1-2", "src/a.py")))
    )
    assert(ToolCheck.shell("python3", List(kette, "zeige", "1-2", "src/a.py"), root.copy(pathPrefix = "src"), config.argPolicies).isLeft)

  test("Scope: Ergebnis trägt \".\", sieht also nur ein Loch, das alles sieht"):
    assertEquals(Scope.shellPaths("python3", List(kette, "zeige", "1-2", "a.py"), config), List(".", kette, "a.py"))
