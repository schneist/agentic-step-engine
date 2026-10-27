package ase.core

/** Was ein Loch sieht (D#9, D#30): dieselbe Regel nutzt Explain jetzt und Execute in Schritt 3. */
class ScopeSuite extends munit.FunSuite:
  private val docs = Envelope(Set(OpKind.ReadFile), "docs/", 5, 0, Set.empty)
  private def shell(args: String*) = Scope.shellPaths(args.head, args.tail.toList, TestSupport.config.argPolicies)

  test("Ergebnisse mit Pfad außerhalb des pathPrefix sieht das Loch nicht"):
    val prior = Vector(Scope.Seen(1, List("secrets/token.txt")), Scope.Seen(2, List("docs/api.md")), Scope.Seen(3, Nil))
    assertEquals(Scope.visible(prior, docs).map(_.step), Vector(2, 3))

  test("Shell: ausdrückliche Pfade zählen, das implizite Arbeitsverzeichnis nicht (D#9 gegen D#27)"):
    assertEquals(shell("grep", "-r", "token", "secrets"), List("secrets"))
    assertEquals(shell("grep", "-rn", "x", "docs/"), List("docs/"))
    assertEquals(shell("./run-tests.sh"), Nil)
    assertEquals(shell("git", "status", "--porcelain"), Nil)
    assertEquals(shell("unbekannt", "x"), List("."))

  test("MoveFile ist nur sichtbar, wenn beide Pfade unter dem Präfix liegen"):
    assertEquals(Scope.visible(Vector(Scope.Seen(1, List("docs/a.md", "src/b.md"))), docs), Vector.empty)
