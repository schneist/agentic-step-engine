class FarewellTest extends munit.FunSuite:
  test("bye"):
    assertEquals(Farewell.bye("Mara"), "Goodbye, Mara!")
