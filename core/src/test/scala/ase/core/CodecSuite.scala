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
