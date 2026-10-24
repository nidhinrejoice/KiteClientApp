package com.example.kiteclientapp.feature.scanners.domain

import java.time.*

val SCANNER_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

data class ScannerParameter(
    val key: String,
    val label: String,
    val default: Double,
    val min: Double,
    val max: Double,
    val integer: Boolean = true
)

data class ScannerStrategy(
    val id: String, val name: String, val category: String, val description: String,
    val parameters: List<ScannerParameter>, val signals: List<String>, val state: Boolean = false,
    val derivatives: Boolean = false, val outcomes: Boolean = true
) {
    val defaults get() = parameters.associate { it.key to it.default }
    fun validate(values: Map<String, Double>) {
        require(values.keys == defaults.keys) { "Check the scanner settings." }
        parameters.forEach { p ->
            val v = values.getValue(p.key)
            require(v.isFinite() && v in p.min..p.max && (!p.integer || v % 1.0 == 0.0)) { "${p.label}: enter ${if (p.integer) "a whole number" else "a number"} between ${p.min} and ${p.max}." }
        }
        fun less(a: String, b: String) {
            require(values.getValue(a) < values.getValue(b)) { "${parameters.first { it.key == a }.label} must be below ${parameters.first { it.key == b }.label}." }
        }
        when (id) {
            "sma" -> less("short", "long"); "macd" -> less(
            "fast",
            "slow"
        ); "rsi" -> less("oversold", "overbought"); "move" -> less("cheap", "rich")
        }
    }
}

object ScannerCatalog {
    private fun p(k: String, l: String, d: Number, min: Number, max: Number, int: Boolean = true) =
        ScannerParameter(k, l, d.toDouble(), min.toDouble(), max.toDouble(), int)

    private val stocks = p("limit", "Max stocks", 100, 1, 100)
    private val underlyings = p("limit", "Max underlyings", 250, 1, 250)
    private fun lookback(d: Int) = p("lookback", "Lookback days", d, 1, 730)
    val strategies = listOf(
        ScannerStrategy(
            "oi",
            "F&O OI buildup",
            "Open interest",
            "Price and combined open interest across all live futures contracts.",
            listOf(
                p("oi", "Min OI change %", 2, 0, 50, false),
                p("price", "Min price change %", .25, 0, 20, false),
                underlyings
            ),
            listOf("Long buildup", "Short covering", "Short buildup", "Long unwinding"),
            true,
            true,
            false
        ),
        ScannerStrategy(
            "move",
            "Expected move vs realised volatility",
            "Volatility",
            "ATM straddle implied move compared with realised volatility and past moves.",
            listOf(
                p("days", "Min days to expiry", 2, 0, 30, false),
                p("rich", "Rich if IV/RV ≥", 1.3, 1, 5, false),
                p("cheap", "Cheap if IV/RV ≤", .9, .1, 1, false),
                p("rv", "RV sessions", 20, 5, 120),
                p("spread", "Max bid-ask % of straddle", 10, .5, 100, false),
                underlyings
            ),
            listOf("Rich", "Fair", "Cheap"),
            true,
            true,
            false
        ),
        ScannerStrategy(
            "sma",
            "Nifty 100 SMA crossover",
            "Trend",
            "Daily bullish and bearish SMA crossovers.",
            listOf(
                p("short", "Short SMA", 6, 1, 199),
                p("long", "Long SMA", 30, 2, 200),
                lookback(365),
                stocks
            ),
            listOf("Bullish", "Bearish")
        ),
        ScannerStrategy(
            "breakout",
            "52-week breakout with volume",
            "Breakout",
            "Closes beyond the prior 252-session range on heavy volume.",
            listOf(
                p("volume", "Volume multiple", 1.5, 1, 10, false),
                p("average", "Volume average sessions", 20, 5, 100),
                lookback(10),
                stocks
            ),
            listOf("Breakout", "Breakdown")
        ),
        ScannerStrategy(
            "rsi",
            "RSI pullback in trend",
            "Mean reversion",
            "Oversold dips in uptrends and overbought rallies in downtrends.",
            listOf(
                p("period", "RSI period", 2, 2, 14),
                p("oversold", "Oversold below", 10, 1, 49),
                p("overbought", "Overbought above", 90, 51, 99),
                p("trend", "Trend SMA", 200, 20, 250),
                stocks
            ),
            listOf("Oversold dip", "Overbought rally"),
            true
        ),
        ScannerStrategy(
            "macd",
            "MACD crossover",
            "Momentum",
            "MACD line crossing its signal line.",
            listOf(
                p("fast", "Fast EMA", 12, 2, 50),
                p("slow", "Slow EMA", 26, 3, 100),
                p("signal", "Signal EMA", 9, 2, 50),
                lookback(30),
                stocks
            ),
            listOf("Bullish", "Bearish")
        ),
        ScannerStrategy(
            "supertrend",
            "Supertrend flip",
            "Trend",
            "Trend direction changes, with the current Supertrend stop level.",
            listOf(
                p("atr", "ATR period", 10, 2, 50),
                p("multiplier", "Multiplier", 3, .5, 10, false),
                lookback(30),
                stocks
            ),
            listOf("Bullish", "Bearish")
        ),
        ScannerStrategy(
            "rs",
            "Relative strength vs Nifty 50",
            "Relative strength",
            "Stocks ranked by return relative to the Nifty 50 on shared sessions.",
            listOf(
                p("period", "Return sessions", 63, 5, 252),
                p("average", "RS average sessions", 50, 5, 200),
                stocks
            ),
            listOf("Outperforming", "Underperforming"),
            true,
            false,
            false
        )
    )

    fun get(id: String) = strategies.first { it.id == id }
}

data class ScanOwner(val id: String, val demo: Boolean)
data class ScanInstrument(
    val token: Long,
    val symbol: String,
    val name: String,
    val exchange: String,
    val type: String,
    val segment: String,
    val expiry: LocalDate? = null,
    val strike: Double = 0.0,
    val lot: Int = 0
) {
    val key get() = "$exchange:$symbol"
}

data class ScanStock(val symbol: String, val company: String)
data class ScanCandle(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val oi: Double? = null
)

data class ScanQuote(
    val price: Double?,
    val previousClose: Double?,
    val bid: Double?,
    val ask: Double?,
    val oi: Double?,
    val volume: Double?,
    val date: LocalDate?
)

enum class MetricFormat { NUMBER, PRICE, PERCENT, RATE, MULTIPLE, QUANTITY, PLUS_MINUS }
data class ScanMetric(
    val label: String,
    val value: Double?,
    val format: MetricFormat = MetricFormat.NUMBER
)

data class ScanRow(
    val symbol: String, val company: String, val signal: String, val date: String,
    val metrics: List<ScanMetric>, val note: String? = null
)

data class ScanSkip(val symbol: String, val reason: String)
data class ForwardOutcome(
    val signal: String,
    val sessions: Int,
    val count: Int,
    val winRate: Double?,
    val averageReturn: Double?
)

data class ScanProgress(
    val phase: String = "Preparing scan",
    val completed: Int = 0,
    val total: Int = 0
)

data class ScanRun(
    val id: String,
    val strategyId: String,
    val finishedAt: String,
    val throughDate: String,
    val settings: Map<String, Double>,
    val total: Int,
    val rows: List<ScanRow>,
    val skipped: List<ScanSkip>,
    val noSignal: Int,
    val outcomes: List<ForwardOutcome>,
    val demo: Boolean
)

class ScanDataUnavailable(message: String) : Exception(message)
interface ScannerMarketSource {
    suspend fun instruments(owner: ScanOwner): List<ScanInstrument>
    suspend fun nifty100(owner: ScanOwner): List<ScanStock>
    suspend fun candles(
        owner: ScanOwner,
        instrument: ScanInstrument,
        from: LocalDate,
        through: LocalDate
    ): List<ScanCandle>

    suspend fun quotes(owner: ScanOwner, instruments: List<ScanInstrument>): Map<String, ScanQuote>
    fun checkOwner(owner: ScanOwner)
}

interface ScanHistoryRepository {
    suspend fun load(owner: ScanOwner): List<ScanRun>
    suspend fun save(owner: ScanOwner, run: ScanRun)
}

class LoadScanHistory(private val repository: ScanHistoryRepository) {
    suspend operator fun invoke(owner: ScanOwner) = repository.load(owner)
}

class SaveScan(private val repository: ScanHistoryRepository) {
    suspend operator fun invoke(owner: ScanOwner, run: ScanRun) = repository.save(owner, run)
}

fun bearish(signal: String) = signal in listOf(
    "Bearish",
    "Breakdown",
    "Overbought rally",
    "Underperforming",
    "Short buildup",
    "Long unwinding"
)

fun completedScanDate(now: ZonedDateTime): LocalDate =
    if (now.hour >= 16) now.toLocalDate() else now.toLocalDate().minusDays(1)
