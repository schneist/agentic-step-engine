package ase.core

class RelPathSuite extends munit.FunSuite:
  test("relativ, normalisiert"):
    assertEquals(RelPath.parse("src/./a/../b.scala"), Right(Vector("src", "b.scala")))
    assertEquals(RelPath.parse("docs\\api.md"), Right(Vector("docs", "api.md")))
    assertEquals(RelPath.parse("."), Right(Vector.empty))

  test("absolut oder außerhalb des Arbeitsverzeichnisses"):
    for raw <- List("/etc/passwd", "\\\\server\\share", "C:/x", "c:x", "..", "a/../../b") do
      assert(RelPath.parse(raw).isLeft, raw)

  test("Windows-Fallen werden überall abgelehnt (D#29)"):
    for raw <- List("a.txt:geheim", "src/NUL", "con.txt", "docs/LPT1.md", "Com9", "notes.", "notes ", "dir./x") do
      assert(RelPath.parse(raw).isLeft, raw)

  test("ähnliche, aber harmlose Namen bleiben erlaubt"):
    for raw <- List("console.txt", "nullable.scala", "COM10", ".gitignore", "a.b.c", "LPT") do
      assert(RelPath.parse(raw).isRight, raw)
