package calc

class InterestTest extends munit.FunSuite:
  test("einfache Zinsen"):
    assertEquals(Interest.simple(BigDecimal(1000), 5, 2), BigDecimal(1100))

  test("Zinseszins über zwei Jahre"):
    assertEquals(Interest.compound(BigDecimal(1000), 5, 2), BigDecimal("1102.50"))
