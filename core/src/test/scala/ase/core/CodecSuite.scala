package ase.core

import io.circe.parser.parse

class CodecSuite extends munit.FunSuite:
  private val fill = Origin.Model("fill-1")
  private val readOnly = Envelope(Set(OpKind.ReadFile), "src", 3, 0, Set.empty)

  private def json(s: String) = parse(s).fold(e => fail(e.message), identity)

  test("decoderFor lehnt EditFile ab, wenn die Hülle es nicht erlaubt"):
    val decode = Codec.decoderFor(readOnly, 5, fill)
    val result = decode(json("""{"type":"EditFile","path":"src/A.scala","expectedOld":"a","replacement":"b"}"""))
    assertEquals(result, Left(DecodeError.OpNotAllowed(Location.root, OpKind.EditFile)))

  test("verbotenes Loch: Decoder und Tor sagen, warum und was stattdessen (D#51)"):
    val decode = Codec.decoderFor(readOnly, 5, fill)
    val result = decode(json("""{"type":"Hole","goal":"x","envelope":{"allowedOps":["ReadFile"],"pathPrefix":"src","maxSteps":1,"maxDepth":0,"shellAllowlist":[]}}"""))
    assert(result.left.exists(_.message.contains(Violation.holeHint)), result)
    val v = Violation.OpNotAllowed(Location.root, OpKind.Hole, Set(OpKind.ReadFile))
    assert(v.message.startsWith("Hole ist hier nicht erlaubt; erlaubt sind: ReadFile.") && v.message.contains(Violation.holeHint), v.message)

  test("decoderFor stempelt origin"):
    val decode = Codec.decoderFor(readOnly, 5, fill)
    assertEquals(decode(json("""{"type":"ReadFile","path":"src/A.scala"}""")), Right(Term.ReadFile("src/A.scala", fill)))

  test("ein Modell darf origin nicht selbst setzen"):
    val decode = Codec.decoderFor(readOnly, 5, fill)
    assertEquals(
      decode(json("""{"type":"ReadFile","path":"x","origin":"Human"}""")),
      Left(DecodeError.OriginNotAllowed(Location.root))
    )

  test("unbekannte Felder sind Fehler"):
    val result = Codec.decodeTerm(json("""{"type":"Done","summary":"x","origin":"Human","extra":1}"""), OriginMode.Required, DecodeLimits.all())
    assertEquals(result, Left(DecodeError.UnknownField(Location.root, "extra")))

  test("tiefer als die Verschachtelungsgrenze wird beim Decodieren abgelehnt"):
    val deep = Codec.term(TestSupport.nestedSeq(6))
    assert(Codec.decodeTerm(deep, OriginMode.Required, DecodeLimits.all(5)).left.exists(_.isInstanceOf[DecodeError.TooDeep]))
    assert(Codec.decodeTerm(Codec.term(TestSupport.nestedSeq(5)), OriginMode.Required, DecodeLimits.all(5)).isRight)

  test("Mengen werden sortiert geschrieben, unabhängig von der Einfügereihenfolge"):
    val a = Envelope(Set(OpKind.Shell, OpKind.ReadFile), ".", 1, 0, Set(AllowedCommand("ls", Nil), AllowedCommand("grep", Nil)))
    val b = a.copy(allowedOps = Set(OpKind.ReadFile, OpKind.Shell), shellAllowlist = Set(AllowedCommand("grep", Nil), AllowedCommand("ls", Nil)))
    assertEquals(Codec.envelope(a).noSpaces, Codec.envelope(b).noSpaces)

  test("scopePrefix: nur geschrieben, wenn gesetzt; Hüllen ohne behalten Form und Hash (D#69)"):
    val e = Envelope(Set(OpKind.WriteFile), "REPORT.md", 1, 0, Set.empty)
    assert(!Codec.envelope(e).noSpaces.contains("scopePrefix"))
    assertEquals(Codec.decodeEnvelope(Codec.envelope(e), Location.root), Right(e))
    val wide = e.copy(scopePrefix = Some("."))
    assertEquals(Codec.decodeEnvelope(Codec.envelope(wide), Location.root), Right(wide))
    assertNotEquals(TermHash.of(Term.Done("x", Origin.Human), wide), TermHash.of(Term.Done("x", Origin.Human), e))
