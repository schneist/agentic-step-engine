package ase.core

/** Was ein Loch sieht (D#9, D#30, D#39): dieselbe Regel nutzt Explain vorher und Execute zur Laufzeit. */
class ScopeSuite extends munit.FunSuite:
  private val docs = Envelope(Set(OpKind.ReadFile), "docs/", 5, 0, Set.empty)
  private def shell(args: String*) = Scope.shellPaths(args.head, args.tail.toList, TestSupport.config)

  test("Ergebnisse mit Pfad außerhalb des pathPrefix sieht das Loch nicht"):
    val prior = Vector(Scope.Seen(1, List("secrets/token.txt")), Scope.Seen(2, List("docs/api.md")), Scope.Seen(3, Nil))
    assertEquals(Scope.visible(prior, docs).map(_.step), Vector(2, 3))

  test("Shell: ausdrückliche Pfade zählen"):
    assertEquals(shell("grep", "-r", "token", "secrets"), List("secrets"))
    assertEquals(shell("grep", "-rn", "x", "docs/"), List("docs/"))
    assertEquals(shell("git", "log", "--oneline", "--", "docs"), List("docs"))

  test("Shell ohne Pfad: pfadlos nur Workspace-Code und Befehle aus pathless, sonst das ganze Arbeitsverzeichnis (D#39)"):
    assertEquals(shell("./run-tests.sh"), Nil)
    assertEquals(shell("sbt", "test"), Nil)
    assertEquals(shell("git", "log", "--oneline", "v2.3.0..HEAD"), Nil)
    assertEquals(shell("grep", "-rn", "password"), List("."))
    assertEquals(shell("ls"), List("."))
    assertEquals(shell("git", "status", "--porcelain"), List("."))
    assertEquals(shell("git", "diff"), List("."))
    assertEquals(shell("unbekannt", "x"), List("."))

  test("grep ohne Pfad ist für ein Loch unter REPORT.md nicht sichtbar, ./run-tests.sh schon (O#16)"):
    val report = Envelope(Set(OpKind.WriteFile), "REPORT.md", 1, 0, Set.empty)
    val prior = Vector(Scope.Seen(1, shell("grep", "-rn", "password")), Scope.Seen(2, shell("./run-tests.sh")))
    assertEquals(Scope.visible(prior, report).map(_.step), Vector(2))

  test("MoveFile ist nur sichtbar, wenn beide Pfade unter dem Präfix liegen"):
    assertEquals(Scope.visible(Vector(Scope.Seen(1, List("docs/a.md", "src/b.md"))), docs), Vector.empty)
