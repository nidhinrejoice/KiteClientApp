package com.example.kiteclientapp.feature.options.domain

import java.math.BigDecimal
import java.math.MathContext
import javax.inject.Inject

enum class OptionType { Call, Put }
enum class OptionSide { Buy, Sell }
data class OptionLeg(
    val id: Long,
    val type: OptionType,
    val side: OptionSide,
    val strike: BigDecimal,
    val premium: BigDecimal,
    val lots: Int
)

data class OptionStrategy(val lotSize: Int, val legs: List<OptionLeg>)
data class BreakEven(val from: BigDecimal, val to: BigDecimal?)
data class PayoffPoint(val spot: BigDecimal, val profit: BigDecimal)
data class OptionAnalysis(
    val netPremiumPaid: BigDecimal,
    // Null means unlimited. A negative best P&L means the strategy loses at every price.
    val maxProfit: BigDecimal?, val maxLoss: BigDecimal?,
    val breakEvens: List<BreakEven>, val scenarioPrice: BigDecimal, val scenarioProfit: BigDecimal,
    val points: List<PayoffPoint>,
)

/** Expiry payoff only: all legs must share one underlying and expiry. No time value or fees. */
class AnalyzeOptionStrategy @Inject constructor() {
    operator fun invoke(strategy: OptionStrategy, scenario: BigDecimal): OptionAnalysis {
        require(strategy.lotSize in 1..100_000) { "Lot size must be between 1 and 100,000." }
        require(strategy.legs.size in 1..8) { "Add between 1 and 8 legs." }
        require(scenario >= BigDecimal.ZERO && scenario <= LIMIT) { "Enter a valid scenario price." }
        strategy.legs.forEach {
            require(it.strike > BigDecimal.ZERO && it.strike <= LIMIT) { "Strike must be greater than zero." }
            require(it.premium >= BigDecimal.ZERO && it.premium <= LIMIT) { "Premium cannot be negative." }
            require(it.lots in 1..10_000) { "Lots must be between 1 and 10,000." }
        }
        fun payoff(spot: BigDecimal) = strategy.legs.fold(BigDecimal.ZERO) { sum, leg ->
            val intrinsic =
                (if (leg.type == OptionType.Call) spot - leg.strike else leg.strike - spot).max(
                    BigDecimal.ZERO
                )
            sum + (intrinsic - leg.premium) * units(strategy, leg)
        }

        val premium = strategy.legs.fold(BigDecimal.ZERO) { sum, leg ->
            sum + leg.premium * units(
                strategy,
                leg
            )
        }
        val knots =
            (listOf(BigDecimal.ZERO) + strategy.legs.map { it.strike }).distinctBy { it.stripTrailingZeros() }
                .sorted()
        val values = knots.map(::payoff)
        val tailSlope = strategy.legs.filter { it.type == OptionType.Call }
            .fold(BigDecimal.ZERO) { sum, leg -> sum + units(strategy, leg) }
        val roots = mutableListOf<BreakEven>()
        knots.zipWithNext().forEachIndexed { index, (a, b) ->
            val fa = values[index];
            val fb = values[index + 1]
            when {
                fa.signum() == 0 && fb.signum() == 0 -> roots += BreakEven(a, b)
                fa.signum() == 0 -> roots += BreakEven(a, a)
                fb.signum() == 0 -> roots += BreakEven(b, b)
                fa.signum() != fb.signum() -> {
                    val root = a - fa.multiply(b - a).divide(fb - fa, MathContext.DECIMAL128)
                    roots += BreakEven(root, root)
                }
            }
        }
        val last = knots.last();
        val lastValue = values.last()
        if (tailSlope.signum() == 0 && lastValue.signum() == 0) roots += BreakEven(last, null)
        else if (tailSlope.signum() != 0) {
            val root = last - lastValue.divide(tailSlope, MathContext.DECIMAL128)
            if (root >= last) roots += BreakEven(root, root)
        }
        val merged = mutableListOf<BreakEven>()
        roots.sortedBy { it.from }.forEach { next ->
            val previous = merged.lastOrNull()
            if (previous != null && (previous.to == null || next.from <= previous.to)) {
                merged[merged.lastIndex] = BreakEven(
                    previous.from,
                    if (previous.to == null || next.to == null) null else previous.to.max(next.to)
                )
            } else merged += next
        }
        val upper = (merged.mapNotNull { it.to } + listOf(last, scenario)).maxOrNull()!!
            .max(BigDecimal.ONE) * "1.3".toBigDecimal()
        val grid = (0..100).map { upper * it.toBigDecimal() / 100.toBigDecimal() }
        val spots = (grid + knots + merged.flatMap {
            listOfNotNull(
                it.from,
                it.to
            )
        } + scenario).distinctBy { it.stripTrailingZeros() }.sorted()
        return OptionAnalysis(
            premium,
            if (tailSlope.signum() > 0) null else values.maxOrNull()!!,
            if (tailSlope.signum() < 0) null else values.minOrNull()!!.negate()
                .max(BigDecimal.ZERO),
            merged, scenario, payoff(scenario), spots.map { PayoffPoint(it, payoff(it)) })
    }

    private fun units(strategy: OptionStrategy, leg: OptionLeg): BigDecimal =
        (strategy.lotSize.toLong() * leg.lots).toBigDecimal() * (if (leg.side == OptionSide.Buy) BigDecimal.ONE else BigDecimal.ONE.negate())

    private companion object {
        val LIMIT = "1000000000".toBigDecimal()
    }
}
