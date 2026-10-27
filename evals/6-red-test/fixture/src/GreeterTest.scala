class GreeterTest extends munit.FunSuite:
  test("greet"):
    assertEquals(Greeter.greet("Mara"), "Hello, Mara!")
