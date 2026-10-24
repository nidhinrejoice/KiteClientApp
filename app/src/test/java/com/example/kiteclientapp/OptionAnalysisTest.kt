package com.example.kiteclientapp

import com.example.kiteclientapp.feature.options.domain.*
import org.junit.Assert.*
import org.junit.Test

class OptionAnalysisTest {
    private val analyze = AnalyzeOptionStrategy()
    private fun leg(type: OptionType = OptionType.Call, side: OptionSide = OptionSide.Buy, strike: String = "100", premium: String = "5", lots: Int = 1) = OptionLeg(1, type, side, strike.toBigDecimal(), premium.toBigDecimal(), lots)
    private fun result(vararg legs: OptionLeg, spot: String = "100", lot: Int = 1) = analyze(OptionStrategy(lot, legs.toList()), spot.toBigDecimal())
    private fun number(expected: String, actual: java.math.BigDecimal?) { assertNotNull(actual); assertEquals(0, expected.toBigDecimal().compareTo(actual)) }
    @Test fun `long call includes debit and unlimited upside`() {
        val r = result(leg(), spot = "120", lot = 50)
        number("250", r.netPremiumPaid); number("250", r.maxLoss); assertNull(r.maxProfit)
        number("105", r.breakEvens.single().from); number("750", r.scenarioProfit)
    }
    @Test fun `short call has unlimited loss and premium credit`() {
        val r = result(leg(side = OptionSide.Sell), spot = "120")
        number("-5", r.netPremiumPaid); number("5", r.maxProfit); assertNull(r.maxLoss); number("-15", r.scenarioProfit)
    }
    @Test fun `puts stop losing or gaining at underlying zero`() {
        val long = result(leg(type = OptionType.Put), spot = "0")
        number("95", long.maxProfit); number("5", long.maxLoss); number("95", long.breakEvens.single().from)
        val short = result(leg(type = OptionType.Put, side = OptionSide.Sell), spot = "0")
        number("5", short.maxProfit); number("95", short.maxLoss); number("-95", short.scenarioProfit)
    }
    @Test fun `bull spread uses net premium and bounded extrema`() {
        val r = result(leg(premium = "8"), leg(side = OptionSide.Sell, strike = "110", premium = "3"), spot = "120")
        number("5", r.maxLoss); number("5", r.maxProfit); number("105", r.breakEvens.single().from); number("5", r.scenarioProfit)
    }
    @Test fun `straddle finds both breakevens`() {
        val r = result(leg(), leg(type = OptionType.Put))
        assertEquals(listOf("90", "110"), r.breakEvens.map { it.from.stripTrailingZeros().toPlainString() })
        number("10", r.maxLoss); assertNull(r.maxProfit)
    }
    @Test fun `iron condor credit and wing risk`() {
        val r = result(leg(OptionType.Put, strike = "90", premium = "1"), leg(OptionType.Put, OptionSide.Sell, "95", "2"), leg(side = OptionSide.Sell, strike = "105", premium = "2"), leg(strike = "110", premium = "1"))
        number("2", r.maxProfit); number("3", r.maxLoss); number("-2", r.netPremiumPaid)
        assertEquals(listOf("93", "107"), r.breakEvens.map { it.from.stripTrailingZeros().toPlainString() })
    }
    @Test fun `opposite matching legs break even across all nonnegative spots`() {
        val r = result(leg(), leg(side = OptionSide.Sell))
        number("0", r.maxProfit); number("0", r.maxLoss)
        number("0", r.breakEvens.single().from); assertNull(r.breakEvens.single().to)
    }
    @Test fun `zero premium call has flat breakeven interval`() {
        val r = result(leg(premium = "0"))
        number("0", r.breakEvens.single().from); number("100", r.breakEvens.single().to)
    }
    @Test fun `unequal lots leave unbounded exposure and use full quantities`() {
        val r = result(leg(lots = 1), leg(side = OptionSide.Sell, lots = 2), spot = "120", lot = 10)
        assertNull(r.maxLoss); number("50", r.maxProfit); number("-150", r.scenarioProfit)
    }
    @Test fun `expensive put can have no breakeven at any nonnegative price`() {
        val r = result(leg(type = OptionType.Put, premium = "120"))
        assertTrue(r.breakEvens.isEmpty()); number("-20", r.maxProfit); number("120", r.maxLoss)
    }
    @Test fun `chart includes strikes breakevens and scenario exactly`() {
        val r = result(leg(), spot = "109.25")
        assertTrue(r.points.any { it.spot.compareTo("109.25".toBigDecimal()) == 0 })
        assertTrue(r.points.any { it.spot.compareTo("105".toBigDecimal()) == 0 && it.profit.signum() == 0 })
        number("4.25", r.scenarioProfit)
    }
    @Test fun `invalid inputs are rejected`() {
        val bad = listOf(OptionStrategy(0, listOf(leg())), OptionStrategy(1, emptyList()), OptionStrategy(1, List(9) { leg() }), OptionStrategy(1, listOf(leg(strike = "0"))), OptionStrategy(1, listOf(leg(premium = "-1"))), OptionStrategy(1, listOf(leg(lots = 0))))
        bad.forEach { strategy -> assertThrows(IllegalArgumentException::class.java) { analyze(strategy, "100".toBigDecimal()) } }
        assertThrows(IllegalArgumentException::class.java) { result(leg(), spot = "-1") }
    }
}
