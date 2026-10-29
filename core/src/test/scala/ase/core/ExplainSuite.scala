package ase.core

class ExplainSuite extends munit.FunSuite:
  private def checked(t: Term, env: Envelope) =
    DryRun.plan(t, env, TestSupport.config).fold(v => fail(v.toList.map(_.render).mkString("\n")), identity)

  test("red-test: Ausgabe zur Freigabe"):
    val f = Flow.parse(TestSupport.repoFile("flows/red-test.json")).toOption.get
    val c = checked(f.term, f.envelope)
    assertNoDiff(
      Explain.render(c),
      s"""dryrun    ok  (worst case 19 steps, depth 1)
         |explain   ${c.termHash.short}
         |hash      ${c.termHash.hex}
         |
         |1  führt aus: ./run-tests.sh (führt Workspace-Code aus)
         |2  listet src samt Unterverzeichnissen
         |3  Hier entscheidet der Agent: „Finde die Ursache des roten Tests. Ändere nichts; beende mit Done und …“ (gekürzt, 101 Zeichen)
         |   darf ReadFile, Shell · unter src/ · max 6 Schritte · Tiefe 0 · shell: grep
         |   kann Ergebnisse von Schritt 1, 2 sehen
         |4  Hier entscheidet der Agent: „Behebe die gefundene Ursache mit genau einem EditFile. Tests nicht änd…“ (gekürzt, 74 Zeichen)
         |   darf ReadFile, EditFile · unter src/ · max 3 Schritte · Tiefe 0
         |   kann Ergebnisse von Schritt 1, 2, 3 sehen
         |   wiederhole höchstens 2-mal, bis Exit-Code 0:
         |5    führt aus: ./run-tests.sh (führt Workspace-Code aus)
         |   wenn Exit-Code 0:
         |6    Hier entscheidet der Agent: „Schreibe REPORT.md: Ursache, betroffene Datei, Änderung. Grundlage sin…“ (gekürzt, 111 Zeichen)
         |     darf WriteFile · unter REPORT.md · max 2 Schritte · Tiefe 0
         |     kann Ergebnisse von Schritt 1, 3, 4, 5 sehen
         |   sonst:
         |7    fragt: „Tests weiterhin rot; wie weiter?“
         |8  fertig: Roter Test behoben; Bericht in REPORT.md
         |
         |shell     ./run-tests.sh, grep
         |nicht erlaubt, u. a.: git push, git commit, git tag, rm, curl
         |""".stripMargin
    )

  test("release: gebundene Parameter stehen im Kopf, Platzhalter sind ersetzt"):
    val r = Flow.parse(TestSupport.repoFile("flows/release.json")).toOption.get
    val values = Map("version" -> "2.4.0", "previous" -> "2.3.0")
    val bound = Flow.bind(r, values, r.envelope).toOption.get
    val c = DryRun.plan(bound.term, bound.envelope, TestSupport.config, values).fold(v => fail(v.toList.map(_.render).mkString("\n")), identity)
    val out = Explain.render(c)
    assert(out.contains("params    previous=2.3.0 version=2.4.0"), out)
    assert(out.contains("ersetzt in build.sbt  „version := \"2.3.0\"“  durch  „version := \"2.4.0\"“"), out)
    assert(out.contains("dryrun    ok  (worst case 23 steps, depth 2)"), out)

  test("Hinweis, wenn ein Loch Code ändern darf, der im Lauf ausgeführt wird (D#12)"):
    val root = Envelope(Set(OpKind.EditFile, OpKind.Shell, OpKind.Hole), ".", 20, 1, Set(AllowedCommand("sbt", List("test"))))
    val t = Term.Seq(
      List(
        Term.Hole("repariere", Envelope(Set(OpKind.EditFile), "src", 3, 0, Set.empty), Origin.Human),
        Term.Shell("sbt", List("test"), Origin.Human)
      ),
      Origin.Human
    )
    // der Hinweis nennt, welcher ausgeführte Code unter dem pathPrefix liegt (D#62)
    assert(Explain.render(checked(t, root)).contains("Hinweis: darf Code ändern, der im Lauf ausgeführt wird (src)"))
    val wide = Term.Seq(
      List(Term.Hole("repariere", Envelope(Set(OpKind.EditFile), ".", 3, 0, Set.empty), Origin.Human), Term.Shell("sbt", List("test"), Origin.Human)),
      Origin.Human
    )
    assert(Explain.render(checked(wide, root)).contains("(build.sbt, project, src)"), Explain.render(checked(wide, root)))
    val readOnly = Term.Seq(
      List(Term.Hole("lies", Envelope(Set(OpKind.ReadFile), ".", 3, 0, Set.empty), Origin.Human), Term.Shell("sbt", List("test"), Origin.Human)),
      Origin.Human
    )
    assert(!Explain.render(checked(readOnly, root.copy(allowedOps = root.allowedOps + OpKind.ReadFile))).contains("Hinweis: darf Code ändern"))

  test("Warnung, wenn explainDenials doch erlaubt sind (D#24)"):
    val root = Envelope(Set(OpKind.Shell), ".", 5, 0, Set(AllowedCommand("git", Nil)))
    val out = Explain.render(checked(Term.Done("x", Origin.Human), root))
    assert(out.contains("Warnung: erlaubt, obwohl in explainDenials: git push, git commit, git tag"), out)
    assert(out.contains("nicht erlaubt, u. a.: rm, curl"), out)

  test("scopePrefix: Explain nennt die Sicht; der Code-Hinweis hängt am pathPrefix (D#69)"):
    val root = Envelope(Set(OpKind.WriteFile, OpKind.Shell, OpKind.Hole), ".", 20, 1, Set(AllowedCommand("grep", Nil), AllowedCommand("sbt", List("test"))))
    def plan(e: Envelope) = Term.Seq(
      List(Term.Shell("grep", List("-rn", "TODO", "src"), Origin.Human), Term.Shell("sbt", List("test"), Origin.Human), Term.Hole("berichte", e, Origin.Human)),
      Origin.Human
    )
    val report = Envelope(Set(OpKind.WriteFile), "REPORT.md", 1, 0, Set.empty)
    val narrow = Explain.render(checked(plan(report), root))
    assert(narrow.contains("kann Ergebnisse von Schritt 2 sehen"), narrow)
    val wide = Explain.render(checked(plan(report.copy(scopePrefix = Some("."))), root))
    assert(wide.contains("darf WriteFile · unter REPORT.md · sieht Ergebnisse unter . · max 1 Schritt · Tiefe 0"), wide)
    assert(wide.contains("kann Ergebnisse von Schritt 1, 2 sehen"), wide)
    assert(!wide.contains("Hinweis: darf Code ändern"), wide)
