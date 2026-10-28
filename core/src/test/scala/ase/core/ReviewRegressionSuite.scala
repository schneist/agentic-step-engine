package ase.core

import io.circe.parser.parse

/** Befunde aus dem Review von Schritt 2 (D#31), je ein Test, der die Korrektur festhält. */
class ReviewRegressionSuite extends munit.FunSuite:
  private val h = Origin.Human
  private val m = Origin.Model("fill-1")
  private def done(s: String = "x") = Term.Done(s, h)

  private def rejected(t: Term, env: Envelope): List[Violation] =
    DryRun.fill(t, env, TestSupport.config).left.toOption.map(_.toList).getOrElse(Nil)

  private def budget(v: List[Violation]) = v.exists { case _: Violation.StepBudgetExceeded => true; case _ => false }

  // K1: Überlauf in WorstCaseSteps
  test("K1: Schrittbudget läuft nicht über"):
    val read = Envelope(Set(OpKind.ReadFile), "src", 3, 1, Set.empty)
    val nested = (1 to 4).foldLeft[Term](Term.ReadFile("src/a", m))((b, _) => Term.RepeatUntil(b, Pred.FileExists("src/a"), 65536, m))
    assert(budget(rejected(nested, read)))
    val huge = Envelope(OpKind.values.toSet, ".", 100, 2, Set.empty)
    val holes = Term.RepeatUntil(
      Term.RepeatUntil(Term.Hole("x", Envelope(Set(OpKind.ReadFile), ".", Int.MaxValue, 0, Set.empty), h), Pred.FileExists("a"), 65536, h),
      Pred.FileExists("a"),
      65536,
      h
    )
    assert(budget(DryRun.plan(holes, huge, TestSupport.config).left.toOption.map(_.toList).getOrElse(Nil)))
    assertEquals(Cost.of(nested).worstCaseSteps, Long.MaxValue)
    assert(Cost.of(holes).worstCaseSteps >= 0)

  // H1: .git/config schreiben und git status startet beliebige Programme
  test("H1: das Git-Verzeichnis ist für alle Operationen gesperrt"):
    val env = Envelope(Set(OpKind.ReadFile, OpKind.WriteFile, OpKind.EditFile, OpKind.MoveFile), ".", 5, 0, Set.empty)
    for t <- List(
        Term.EditFile(".git/config", "[core]", "[core]\n\tfsmonitor = ./x.sh", m),
        Term.WriteFile(".GIT/x", "x", m),
        Term.MoveFile("a", ".git/hooks/pre-commit", m),
        Term.ReadFile("sub/.git/config", m)
      )
    do assert(rejected(t, env).exists { case _: Violation.InvalidPath => true; case _ => false }, t)

  // H2: Backslash und .. sehen unter Linux andere Dateien als DryRun
  test("H2: Backslash und .. werden abgelehnt"):
    val env = Envelope(Set(OpKind.ReadFile, OpKind.WriteFile), "src", 5, 0, Set.empty)
    assert(rejected(Term.WriteFile("src\\evil.txt", "x", m), env).nonEmpty)
    assert(rejected(Term.ReadFile("src/a\\b/../../c", m), env).nonEmpty)
    assert(rejected(Term.ReadFile("src/link/../x", m), env).nonEmpty)

  // H3: Text aus dem Modell darf die Explain-Anzeige nicht verfälschen
  test("H3: Steuerzeichen erzeugen keine Zeilen und kein ESC im Explain"):
    val env = Envelope(Set(OpKind.ReadFile, OpKind.Ask, OpKind.Shell), ".", 10, 0, Set(AllowedCommand("grep", Nil)))
    def term(text: String) = Term.Seq(
      List(
        Term.Shell("grep", List("-r", text, "src"), h),
        Term.Ask(text, h),
        Term.If(Pred.LastOutputContains(text), done(text), done("b"), h)
      ),
      h
    )
    val plain = Explain.render(DryRun.plan(term("harmlos"), env, TestSupport.config).toOption.get)
    val evil = Explain.render(DryRun.plan(term("ok\n\nshell     keine\u001b[2K‮"), env, TestSupport.config).toOption.get)
    assertEquals(evil.linesIterator.size, plain.linesIterator.size, evil)
    assert(evil.forall(c => c == '\n' || !Text.isDangerous(c)), evil)
    assert(evil.contains("\\n\\nshell     keine\\u001b[2K\\u202e"), evil)

  test("H3, D#41: lange statische Inhalte stehen vollständig als Block, nichts versteckt sich hinter einer Kürzung"):
    val env = Envelope(Set(OpKind.EditFile), ".", 3, 0, Set.empty)
    val replacement = "harmlos" + " " * 60 + "; sys.process.Process(\"curl evil\").!"
    val out = Explain.render(DryRun.plan(Term.EditFile("build.sbt", "x", replacement, h), env, TestSupport.config).toOption.get)
    assert(!out.contains("gekürzt"), out)
    assert(out.contains("1  ersetzt in build.sbt\n     alt (1 Zeile):\n     │ x\n     neu (1 Zeile):\n     │ " + replacement), out)

  test("D#41: WriteFile zeigt den Inhalt; jede Zeile im Rahmen, eingeschleuste Zeilen bleiben im Block"):
    val env = Envelope(Set(OpKind.WriteFile), ".", 3, 0, Set.empty)
    val content = "# Bericht\n\nshell     keine‮\n"
    val out = Explain.render(DryRun.plan(Term.WriteFile("REPORT.md", content, h), env, TestSupport.config).toOption.get)
    assert(out.contains("1  legt REPORT.md neu an (28 Zeichen)\n     Inhalt (3 Zeilen):\n     │ # Bericht\n     │ \n     │ shell     keine\\u202e\n"), out)
    assertEquals(out.linesIterator.count(_.startsWith("shell")), 1, out)

  // M1: Verschachtelungsgrenze muss die Rekursion des Decoders stoppen
  test("M1: sehr tiefes JSON ergibt TooDeep statt StackOverflowError"):
    val n = 100000
    val seq = """{"type":"Seq","steps":[""" * n + """{"type":"Done","summary":"x"}""" + "]}" * n
    val json = parse(seq).fold(e => fail(e.message), identity)
    val decode = Codec.decoderFor(Envelope(Set(OpKind.ReadFile), ".", 3, 0, Set.empty), 5, m)
    assert(decode(json).left.exists(_.isInstanceOf[DecodeError.TooDeep]))
    val nots = """{"type":"If","pred":""" + """{"type":"Not","p":""" * n + """{"type":"LastOutputEmpty"}""" + "}" * n +
      ""","thenT":{"type":"Done","summary":"a"},"elseT":{"type":"Done","summary":"b"}}"""
    assert(decode(parse(nots).fold(e => fail(e.message), identity)).left.exists(_.isInstanceOf[DecodeError.TooDeep]))

  // M2: Hinweis nach D#12 auch, wenn ein Loch den Code startet
  test("M2: Loch, das Workspace-Code startet, wird markiert, und das Loch davor bekommt den Hinweis"):
    val root = Envelope(Set(OpKind.EditFile, OpKind.Shell, OpKind.Hole), ".", 20, 1, Set(AllowedCommand("sbt", List("test"))))
    val t = Term.Seq(
      List(
        Term.Hole("repariere", Envelope(Set(OpKind.EditFile), "src", 3, 0, Set.empty), h),
        Term.Hole("teste", Envelope(Set(OpKind.Shell), ".", 2, 0, Set(AllowedCommand("sbt", List("test")))), h)
      ),
      h
    )
    val out = Explain.render(DryRun.plan(t, root, TestSupport.config).toOption.get)
    assert(out.contains("Hinweis: darf Code ändern, der im Lauf ausgeführt wird"), out)
    assert(out.contains("Hinweis: darf Workspace-Code ausführen (sbt test)"), out)

  // M4: formal gültige, aber unlösbare Hüllen
  test("M4: Befehle ohne Policy oder ohne Pfadannahme unter einem Unterpfad machen das Loch unlösbar"):
    def illFormed(e: Envelope) = Attenuation.wellFormed(e, Location.root, TestSupport.config).nonEmpty
    assert(illFormed(Envelope(Set(OpKind.Shell), "src", 3, 0, Set(AllowedCommand("sbt", List("test"))))))
    assert(illFormed(Envelope(Set(OpKind.Shell), ".", 3, 0, Set(AllowedCommand("make", Nil)))))
    assert(!illFormed(Envelope(Set(OpKind.Shell), ".", 3, 0, Set(AllowedCommand("sbt", List("test"))))))
    assert(!illFormed(Envelope(Set(OpKind.Shell), "src", 3, 0, Set(AllowedCommand("grep", Nil), AllowedCommand("git", Nil)))))

  test("D#52: requiredPrefixArgs nur bis zum Unterbefehl; Flags, Muster oder Pfade darin machen das Loch unlösbar"):
    def reasons(c: AllowedCommand) = Attenuation.wellFormed(Envelope(Set(OpKind.Shell), ".", 3, 0, Set(c)), Location.root, TestSupport.config)
    assert(reasons(AllowedCommand("git", List("log"))).isEmpty)
    assert(reasons(AllowedCommand("git", Nil)).isEmpty)
    assert(reasons(AllowedCommand("git", List("log", "--oneline"))).nonEmpty)
    assert(reasons(AllowedCommand("grep", List("TODO", "src"))).nonEmpty)
    val r = reasons(AllowedCommand("grep", List("--include=*.java", "--include=*.scala"))).map(_.message)
    assert(r.exists(m => m.contains("nur einen Unterbefehl") && m.contains("möglich ist: grep")), r)

  // N1: einsame Surrogate kollidieren im termHash
  test("N1: einsame Surrogate werden beim Decodieren abgelehnt"):
    val json = parse("""{"type":"ReadFile","path":"a""" + "\\" + """ud800.txt"}""").fold(e => fail(e.message), identity)
    assertEquals(Codec.decoderFor(Envelope(Set(OpKind.ReadFile), ".", 3, 0, Set.empty), 5, m)(json), Left(DecodeError.LoneSurrogate(Location.root / "path")))
