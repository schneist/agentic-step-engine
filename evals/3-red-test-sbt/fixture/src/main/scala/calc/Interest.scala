package calc

object Interest:
  /** Kapital nach `years` Jahren Zinseszins bei `ratePercent` Prozent pro Jahr. */
  def compound(principal: BigDecimal, ratePercent: Int, years: Int): BigDecimal =
    val factor = 1 + ratePercent / 100
    (1 to years).foldLeft(principal)((acc, _) => acc * factor)

  /** Kapital nach `years` Jahren einfacher Verzinsung. */
  def simple(principal: BigDecimal, ratePercent: Int, years: Int): BigDecimal =
    principal + principal * ratePercent * years / 100
