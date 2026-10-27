package ase.core

class MarshalSuite extends munit.FunSuite:
  test("kleiner Text bleibt vollständig"):
    val r = Marshal.excerpt("a\nb\n", 100, 2, 1)
    assertEquals(r, Marshal.Excerpt.Result("a\nb\n", 2L, truncated = false))

  test("großer Text: erste und letzte Zeilen, Gesamtzahl und Hinweis"):
    val text = (1 to 1000).map(i => s"z$i").mkString("\n")
    val r = Marshal.excerpt(text, 200, 3, 2)
    assertEquals(r.lines, 1000L)
    assert(r.truncated)
    assertEquals(r.text.linesIterator.toList, List("z1", "z2", "z3", "… gekürzt: 995 von 1000 Zeilen ausgelassen; suche gezielter (grep) oder lies einen engeren Pfad …", "z999", "z1000"))

  test("eine sehr lange Zeile wird abgeschnitten"):
    val r = Marshal.excerpt("x" * 100000, 1000, 2, 2)
    assert(r.text.length < 2000, r.text.length)
    assert(r.text.contains("lange Zeilen abgeschnitten"), r.text)

  test("Fehler als Handlungsanweisung"):
    assertEquals(Marshal(ToolError.CasAmbiguous("a.txt", 3)), "expectedOld kommt in „a.txt“ 3-mal vor; erweitere den Kontext, bis er genau einmal vorkommt.")
    assert(Marshal(ToolError.OutsidePrefix("README.md", "docs/")).contains("erlaubt ist nur docs/"))
