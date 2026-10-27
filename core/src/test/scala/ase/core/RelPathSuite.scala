package ase.core

class RelPathSuite extends munit.FunSuite:
  test("relativ, normalisiert"):
    assertEquals(RelPath.parse("src/./a//b.scala"), Right(Vector("src", "a", "b.scala")))
    assertEquals(RelPath.parse("docs/"), Right(Vector("docs")))
    assertEquals(RelPath.parse("."), Right(Vector.empty))

  test("absolut, außerhalb, mit .. oder Backslash (D#31)"):
    for raw <- List("/etc/passwd", "\\\\server\\share", "C:/x", "c:x", "..", "a/../b", "a/../../b", "docs\\api.md", "src\\x") do
      assert(RelPath.parse(raw).isLeft, raw)

  test("Windows-Fallen werden überall abgelehnt (D#29)"):
    for raw <- List("a.txt:geheim", "src/NUL", "con.txt", "docs/LPT1.md", "Com9", "notes.", "notes ", "dir./x", "CONIN$", "COM¹", "NUL .txt") do
      assert(RelPath.parse(raw).isLeft, raw)

  test("Steuerzeichen und das Git-Verzeichnis werden abgelehnt (D#31)"):
    for raw <- List("a\nb", "a\u0001b", "a\u001b[2Kb", "a\u202eb", ".git/config", "sub/.GIT/hooks/x", ".Git") do
      assert(RelPath.parse(raw).isLeft, raw)

  test("ähnliche, aber harmlose Namen bleiben erlaubt"):
    for raw <- List("console.txt", "nullable.scala", "COM10", ".gitignore", ".github/ci.yml", "a.b.c", "LPT", "git/x") do
      assert(RelPath.parse(raw).isRight, raw)
