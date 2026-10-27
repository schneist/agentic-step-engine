package ase.core

import io.circe.Json

class SchemaSuite extends munit.FunSuite:
  private val schema = Schema.termSchema(5)

  private def wrap(t: Term) = Json.obj("term" -> Codec.term(t, withOrigin = false))

  test("Term tiefer als die Verschachtelungsgrenze validiert nicht"):
    assertEquals(TestSupport.schemaErrors(5, wrap(TestSupport.nestedSeq(5))), Nil)
    assert(TestSupport.schemaErrors(5, wrap(TestSupport.nestedSeq(6))).nonEmpty)

  test("origin ist im Modell-Schema nicht vorgesehen"):
    assert(TestSupport.schemaErrors(5, Json.obj("term" -> Codec.term(Term.Done("x", Origin.Human)))).nonEmpty)

  /** Alle Objekte im Schema, rekursiv. */
  private def objects(j: Json): List[Json] =
    j.arrayOrObject(
      Nil,
      _.toList.flatMap(objects),
      o => j :: o.values.toList.flatMap(objects)
    )

  test("nur Features, die strict unterstützt (D#10)"):
    val keys = objects(schema).flatMap(_.asObject.toList.flatMap(_.keys))
    for forbidden <- List("oneOf", "discriminator", "minimum", "maximum", "minLength", "maxLength", "pattern") do
      assert(!keys.contains(forbidden), forbidden)

  test("jedes Objekt ist geschlossen und verlangt alle Felder"):
    for o <- objects(schema) if o.hcursor.get[String]("type").contains("object") do
      assertEquals(o.hcursor.get[Boolean]("additionalProperties"), Right(false), o.noSpaces)
      val props = o.hcursor.downField("properties").keys.toList.flatten.sorted
      val required = o.hcursor.get[List[String]]("required").getOrElse(Nil).sorted
      assertEquals(required, props, o.noSpaces)

  test("keine Rekursion: der Verweisgraph der $defs ist azyklisch"):
    val defs = schema.hcursor.downField("$defs").focus.flatMap(_.asObject).get.toMap
    def refs(j: Json): Set[String] =
      j.arrayOrObject(
        Set.empty,
        _.toSet.flatMap(refs),
        o => o("$ref").flatMap(_.asString).map(_.stripPrefix("#/$defs/")).toSet ++ o.values.toSet.flatMap(refs)
      )
    val graph = defs.view.mapValues(refs).toMap
    def acyclic(node: String, path: Set[String]): Boolean =
      !path(node) && graph.getOrElse(node, Set.empty).forall(acyclic(_, path + node))
    assert(graph.keys.forall(acyclic(_, Set.empty)))
