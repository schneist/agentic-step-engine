package ase.core

import io.circe.parser.parse

class CanonicalSuite extends munit.FunSuite:
  private def canon(s: String) = Canonical.print(parse(s).fold(e => fail(e.message), identity))

  /** Scala 3 wertet \uXXXX auch in """…""" aus; ¦ steht im Quelltext für einen Backslash im JSON. */
  private def bs(s: String) = s.replace('¦', '\\')

  // RFC 8785, Abschnitt 3.2.2.2 (ohne den Zahlenteil: Terme enthalten keine Gleitkommazahlen)
  test("RFC 8785: Strings und Literale"):
    assertEquals(
      canon(bs("""{"string":"¦u20ac$¦u000F¦u000aA'¦u0042¦u0022¦u005c¦¦¦"¦/","literals":[null,true,false]}""")),
      bs("""{"literals":[null,true,false],"string":"€$¦u000f¦nA'B¦"¦¦¦¦¦"/"}""")
    )

  // RFC 8785, Abschnitt 3.2.3: Sortierung nach UTF-16-Codeeinheiten
  test("RFC 8785: Sortierung der Schlüssel"):
    val in = bs(
      """{"¦u20ac":"Euro Sign","¦r":"Carriage Return","¦ufb33":"Hebrew Letter Dalet With Dagesh","1":"One","¦ud83d¦ude00":"Emoji: Grinning Face","¦u0080":"Control","¦u00f6":"Latin Small Letter O With Diaeresis"}"""
    )
    val values = parse(canon(in)).toOption.get.asObject.get.values.toList.flatMap(_.asString)
    assertEquals(
      values,
      List("Carriage Return", "One", "Control", "Latin Small Letter O With Diaeresis", "Euro Sign", "Emoji: Grinning Face", "Hebrew Letter Dalet With Dagesh")
    )

  private val env = Envelope(Set(OpKind.ReadFile), ".", 3, 0, Set.empty)
  private val t = Term.Seq(List(Term.ReadFile("a.txt", Origin.Human), Term.Done("fertig", Origin.Human)), Origin.Human)

  test("termHash: Schlüsselreihenfolge im Eingabe-JSON ändert ihn nicht"):
    val shuffled = """{"origin":"Human","steps":[{"path":"a.txt","origin":"Human","type":"ReadFile"},{"summary":"fertig","type":"Done","origin":"Human"}],"type":"Seq"}"""
    val decoded = Codec.parseTerm(shuffled, OriginMode.Required, DecodeLimits.all()).toOption.get
    assertEquals(TermHash.of(decoded, env), TermHash.of(t, env))
    assert(TermHash.fromHex(TermHash.of(t, env).hex).isDefined)

  test("termHash: anderes origin ändert ihn, der Fingerprint bleibt"):
    val model = TestSupport.stamp(t, Origin.Model("plan-1"))
    assertNotEquals(TermHash.of(model, env), TermHash.of(t, env))
    assertEquals(Fingerprint.of(model), Fingerprint.of(t))

  test("Fingerprint: andere Prosa gleich, anderer Pfad anders"):
    val prose = Term.Seq(List(Term.ReadFile("a.txt", Origin.Human), Term.Done("ganz anders", Origin.Human)), Origin.Human)
    val path = Term.Seq(List(Term.ReadFile("b.txt", Origin.Human), Term.Done("fertig", Origin.Human)), Origin.Human)
    assertEquals(Fingerprint.of(prose), Fingerprint.of(t))
    assertNotEquals(Fingerprint.of(path), Fingerprint.of(t))
