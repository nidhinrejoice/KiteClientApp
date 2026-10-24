package com.example.kiteclientapp.feature.options.domain

import java.time.Instant
import java.time.LocalDate
import kotlin.math.*

const val OPTION_RATE = 0.065
const val YEAR_SECONDS = 365.0 * 86400

data class ChainLeg(
    val token: Long,
    val symbol: String,
    val call: Boolean,
    val strike: Double,
    val lotSize: Int,
    val last: Double?,
    val bid: Double?,
    val ask: Double?,
    val oi: Long?,
    val volume: Long?,
    val previousOi: Long? = null,
    val historyStatus: String = "Not loaded",
    val quoteTime: String? = null,
    val previousClose: Double? = null
) {
    val midpoint: Boolean get() = bid != null && ask != null && bid > 0 && ask >= bid
    val price: Double? get() = if (midpoint) (bid!! + ask!!) / 2 else last?.takeIf { it > 0 }
    val changeOi: Long? get() = if (oi != null && previousOi != null) oi - previousOi else null
}

data class ChainSnapshot(
    val underlying: String,
    val expiry: LocalDate,
    val expiries: List<LocalDate>,
    val spot: Double,
    val asOf: Instant,
    val sessionDate: LocalDate,
    val years: Double,
    val legs: List<ChainLeg>,
    val closes: List<Double> = emptyList(),
    val demo: Boolean = false,
    val historyNote: String? = null,
    val quoteTime: String? = null,
    val previousClose: Double? = null,
    val vix: Double? = null
) {
    val change get() = previousClose?.takeIf { it > 0 }?.let { spot - it }
    val changePercent get() = previousClose?.takeIf { it > 0 }?.let { spot / it - 1 }
    val volumePcr
        get() = if (legs.any { it.volume == null }) null else legs.filter { it.call }
            .sumOf { it.volume!! }.takeIf { it > 0 }
            ?.let { calls -> legs.filter { !it.call }.sumOf { it.volume!! }.toDouble() / calls }
    val strikes: List<Double> by lazy { legs.map { it.strike }.distinct().sorted() }
    private val contracts by lazy { legs.associateBy { it.strike to it.call } }
    fun leg(strike: Double, call: Boolean) = contracts[strike to call]
    val forward: Double by lazy {
        strikes.filter { leg(it, true)?.price != null && leg(it, false)?.price != null }
            .minByOrNull { abs(it - spot) }?.let {
                it + (leg(it, true)!!.price!! - leg(
                    it,
                    false
                )!!.price!!) * exp(OPTION_RATE * years)
            } ?: spot
    }
    val atm get() = strikes.minBy { abs(it - forward) }
    private fun solveIv(leg: ChainLeg): Double? = if (!leg.midpoint && (leg.volume ?: 0) == 0L) null
    else leg.price?.let { OptionModels.impliedVol(it, forward, leg.strike, years, leg.call) }

    private val ivs by lazy { legs.associate { it.symbol to solveIv(it) } }
    fun iv(leg: ChainLeg): Double? = ivs[leg.symbol]
    fun delta(leg: ChainLeg): Double? =
        iv(leg)?.let { OptionModels.delta(forward, leg.strike, years, it, leg.call) }

    val atmIv
        get() = listOfNotNull(
            leg(atm, true)?.let(::iv),
            leg(atm, false)?.let(::iv)
        ).takeIf { it.isNotEmpty() }?.average()
    val straddle get() = leg(atm, true)?.price?.let { c -> leg(atm, false)?.price?.let { c + it } }

    // Missing contracts/quotes must not silently become zero open interest.
    val completeOi get() = legs.all { it.oi != null }
    val pcr
        get() = if (!completeOi) null else legs.filter { it.call }.sumOf { it.oi!! }
            .takeIf { it > 0 }
            ?.let { calls -> legs.filter { !it.call }.sumOf { it.oi!! }.toDouble() / calls }
    val maxPain
        get() = if (!completeOi || legs.sumOf { it.oi!! } == 0L) null else strikes.minByOrNull { s ->
            legs.sumOf {
                max(
                    if (it.call) s - it.strike else it.strike - s,
                    0.0
                ) * it.oi!!
            }
        }

    fun wall(call: Boolean) = legs.filter {
        it.call == call && (it.oi ?: 0) > 0 && if (call) it.strike >= spot else it.strike <= spot
    }.maxByOrNull { it.oi!! }?.strike

    val realisedVol get() = OptionModels.realisedVol(closes)
    val skew: Double?
        get() {
            fun pick(call: Boolean): Pair<Double, Double>? = legs.filter { it.call == call }
                .mapNotNull { leg ->
                    iv(leg)?.let { v ->
                        v to OptionModels.delta(
                            forward,
                            leg.strike,
                            years,
                            v,
                            call
                        )
                    }
                }.minByOrNull { abs(it.second - if (call) .25 else -.25) }

            val c = pick(true) ?: return null;
            val p = pick(false) ?: return null
            return if (abs(c.second - .25) <= .1 && abs(p.second + .25) <= .1) (p.first - c.first) * 100 else null
        }
}

object OptionModels {
    fun cdf(x: Double): Double {
        val t = 1 / (1 + .3275911 * abs(x) / sqrt(2.0))
        val erf =
            1 - (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - .284496736) * t + .254829592) * t) * exp(
                -x * x / 2
            )
        return .5 * (1 + if (x < 0) -erf else erf)
    }

    fun price(f: Double, k: Double, t: Double, v: Double, call: Boolean): Double {
        if (t <= 0) return max(if (call) f - k else k - f, 0.0)
        if (f <= 0 || v <= 0) return exp(-OPTION_RATE * t) * max(if (call) f - k else k - f, 0.0)
        val root = v * sqrt(t);
        val d1 = (ln(f / k) + root * root / 2) / root
        return exp(-OPTION_RATE * t) * (if (call) f * cdf(d1) - k * cdf(d1 - root) else k * cdf(root - d1) - f * cdf(
            -d1
        ))
    }

    fun impliedVol(p: Double, f: Double, k: Double, t: Double, call: Boolean): Double? {
        if (p <= 0 || f <= 0 || k <= 0 || t <= 0 || p <= price(f, k, t, .005, call) || p >= price(
                f,
                k,
                t,
                5.0,
                call
            )
        ) return null
        var a = .005;
        var b = 5.0
        repeat(80) { val m = (a + b) / 2; if (price(f, k, t, m, call) < p) a = m else b = m }
        return (a + b) / 2
    }

    fun delta(f: Double, k: Double, t: Double, v: Double, call: Boolean): Double {
        val d = (ln(f / k) + v * v * t / 2) / (v * sqrt(t))
        return exp(-OPTION_RATE * t) * if (call) cdf(d) else cdf(d) - 1
    }

    fun realisedVol(closes: List<Double>): Double? {
        if (closes.size < 21 || closes.takeLast(21).any { it <= 0 || !it.isFinite() }) return null
        val r = closes.takeLast(21).zipWithNext { a, b -> ln(b / a) };
        val mean = r.average()
        return sqrt(r.sumOf { (it - mean).pow(2) } / 19 * 252)
    }
}

data class SimulationInput(
    val callStrike: Double, val putStrike: Double, val buy: Boolean, val lots: Int,
    val target: Instant, val ivShift: Double
)

data class ModelPoint(
    val level: Double,
    val targetPnl: Double,
    val expiryPnl: Double,
    val chanceAbove: Double = 0.0
)

data class ProbabilityBand(
    val low: Double?,
    val high: Double?,
    val chance: Double,
    val pnlLow: Double,
    val pnlHigh: Double
)

data class SimulationResult(
    val premium: Double,
    val quantity: Long,
    val profitChance: Double,
    val worstFive: Double,
    val rangeLow: Double,
    val rangeHigh: Double,
    val breakevens: List<Double>,
    val points: List<ModelPoint>,
    val bands: List<ProbabilityBand>,
    val usedAtmFallback: Boolean,
    val expiryBreakevens: List<Double>,
    val maxProfit: Double?,
    val maxLoss: Double?,
    val unchangedPnl: Double,
    val rangeTwoLow: Double,
    val rangeTwoHigh: Double
)

class SimulateOptions {
    operator fun invoke(chain: ChainSnapshot, input: SimulationInput): SimulationResult {
        require(input.lots in 1..10000) { "Enter lots between 1 and 10,000." }
        require(input.ivShift.isFinite() && input.ivShift in -100.0..100.0) { "IV change must be between -100 and 100 points." }
        val elapsed =
            (input.target.toEpochMilli() - chain.asOf.toEpochMilli()) / 1000.0 / YEAR_SECONDS
        require(elapsed >= 0 && elapsed <= chain.years + 1e-8) { "Choose a date between the snapshot and expiry." }
        val legs = listOf(
            chain.leg(input.callStrike, true),
            chain.leg(input.putStrike, false)
        ).map { requireNotNull(it) { "Both selected contracts must exist." } }
        require(legs.all { it.price != null }) { "Both contracts need usable entry prices." }
        require(legs[0].lotSize > 0 && legs[0].lotSize == legs[1].lotSize) { "Contract lot sizes must match." }
        val sigma =
            requireNotNull(chain.atmIv) { "ATM IV is unavailable; simulation needs valid quotes." }
        val vols = legs.map { chain.iv(it) ?: sigma }
        val remaining = max(chain.years - elapsed, 0.0);
        val carry = chain.forward / chain.spot
        require(carry > 0) { "Invalid forward price." }
        val quantity = input.lots.toLong() * legs[0].lotSize;
        val premium = legs.sumOf { it.price!! }
        fun pnl(level: Double, left: Double = remaining): Double {
            val f = level * carry.pow(if (chain.years > 0) left / chain.years else 0.0)
            val value = legs.indices.sumOf { i ->
                OptionModels.price(
                    f,
                    legs[i].strike,
                    left,
                    max(vols[i] + input.ivShift / 100, .005),
                    legs[i].call
                )
            }
            return (if (input.buy) 1 else -1) * (value - premium) * quantity
        }

        val centre = chain.spot * carry.pow(if (chain.years > 0) elapsed / chain.years else 0.0)
        val spread = sigma * sqrt(elapsed);
        val mu = ln(centre) - spread * spread / 2
        fun at(z: Double) = exp(mu + z * spread)
        fun below(s: Double) = if (spread <= 0) {
            if (s >= centre) 1.0 else 0.0
        } else if (s <= 0) 0.0 else OptionModels.cdf((ln(s) - mu) / spread)
        // Convex long two-option value (concave when sold): at most two crossings.
        val high = max(max(at(8.0), legs.maxOf { it.strike } * 2 + premium * 4), centre * 2)
        var lo = 0.0;
        var hi = high
        repeat(120) {
            val a = lo + (hi - lo) / 3;
            val b =
                hi - (hi - lo) / 3; if ((if (input.buy) pnl(a) else -pnl(a)) < (if (input.buy) pnl(b) else -pnl(
                b
            ))
        ) hi = b else lo = a
        }
        val turning = (lo + hi) / 2
        val roots = mutableListOf<Double>()
        listOf(0.0 to turning, turning to high).forEach { (a, b) ->
            if (pnl(a) * pnl(b) < 0) {
                var l = a;
                var r = b; repeat(80) {
                    val m = (l + r) / 2; if ((pnl(m) < 0) == (pnl(l) < 0)) l = m else r = m
                }; roots += (l + r) / 2
            }
        }
        val edges = listOf(0.0) + roots + high
        val chance = if (spread == 0.0) {
            if (pnl(centre) > 0) 1.0 else 0.0
        } else edges.zipWithNext().sumOf { (a, b) ->
            if (pnl((a + b) / 2) > 0) (if (b == high) 1.0 else below(b)) - below(a) else 0.0
        }
        val weighted =
            (0..1200).map { i -> val z = -6 + 12.0 * i / 1200; pnl(at(z)) to exp(-z * z / 2) }
                .sortedBy { it.first }
        val weight = weighted.sumOf { it.second };
        var seen = 0.0
        val worst = weighted.firstOrNull { seen += it.second; seen / weight >= .05 }!!.first
        val fullSpread = sigma * sqrt(chain.years)
        val step = chain.strikes.zipWithNext { a, b -> b - a }.minOrNull() ?: 50.0
        val plotLow = min(
            chain.spot * exp(-3 * fullSpread),
            legs.minOf { it.strike } - 2 * step
        ).coerceAtLeast(0.0)
        val plotHigh = max(chain.spot * exp(3 * fullSpread), legs.maxOf { it.strike } + 2 * step)
        val levels =
            ((0..240).map { plotLow + (plotHigh - plotLow) * it / 240 } + legs.map { it.strike } + roots.filter { it in plotLow..plotHigh }).distinct()
                .sorted()
        val bands = mutableListOf<ProbabilityBand>()
        val rawWidth = ((at(2.5) - at(-2.5)) / 12 / step).coerceAtLeast(1.0)
        val power = 10.0.pow(floor(log10(rawWidth)))
        val width = listOf(1, 2, 4, 5, 10).first { it * power >= rawWidth - 1e-9 } * power * step
        val first = floor(at(-2.5) / width) * width;
        val last = ceil(at(2.5) / width) * width
        val boundaries =
            (0..((last - first) / width).roundToInt().coerceAtLeast(1)).map { first + it * width }
        val bandEdges = listOf<Double?>(null) + boundaries + listOf(null)
        bandEdges.zipWithNext().forEach { (a, b) ->
            val l = a ?: at(-6.0);
            val r = b ?: at(6.0)
            val values = ((0..24).map { l + (r - l) * it / 24 } + legs.map { it.strike }
                .filter { it in l..r }).map { pnl(it) }
            bands += ProbabilityBand(
                a,
                b,
                (b?.let(::below) ?: 1.0) - (a?.let(::below) ?: 0.0),
                values.min(),
                values.max()
            )
        }
        return SimulationResult(
            premium * quantity,
            quantity,
            chance.coerceIn(0.0, 1.0),
            worst,
            at(-1.0),
            at(1.0),
            roots,
            levels.map { ModelPoint(it, pnl(it), pnl(it, 0.0), 1 - below(it)) },
            bands,
            legs.any { chain.iv(it) == null },
            listOf(input.putStrike - premium, input.callStrike + premium).filter { it >= 0 }
                .distinct().sorted(),
            if (input.buy) null else premium * quantity,
            if (input.buy) premium * quantity else null,
            pnl(chain.spot),
            at(-2.0),
            at(2.0)
        )
    }
}
