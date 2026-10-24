package com.example.kiteclientapp.feature.scanners.domain

import java.time.LocalDate
import kotlin.math.*

/** Pure calculations. Missing warm-up values remain NaN and cannot become signals. */
object ScanIndicators {
    fun sma(values: List<Double>, period: Int): List<Double> = values.indices.map { i ->
        if (i + 1 < period) Double.NaN else values.subList(
            i - period + 1,
            i + 1
        ).average()
    }

    fun smooth(values: List<Double>, period: Int, wilder: Boolean = false): List<Double> {
        val result = MutableList(values.size) { Double.NaN };
        val first = values.indexOfFirst { it.isFinite() }
        if (first < 0) return result
        var previous = if (wilder) Double.NaN else values[first]
        for (i in first until values.size) {
            if (wilder && i == first + period - 1) previous = values.subList(first, i + 1).average()
            else if (!wilder || i >= first + period) previous += (values[i] - previous) * if (wilder) 1.0 / period else 2.0 / (period + 1)
            if (i >= first + period - 1) result[i] = previous
        }
        return result
    }

    fun rsi(closes: List<Double>, period: Int): List<Double> {
        val changes =
            closes.indices.map { if (it == 0) Double.NaN else closes[it] - closes[it - 1] }
        val gains = smooth(changes.map { if (it.isNaN()) it else max(it, 0.0) }, period, true)
        val losses = smooth(changes.map { if (it.isNaN()) it else max(-it, 0.0) }, period, true)
        return closes.indices.map { i ->
            if (!gains[i].isFinite()) Double.NaN else if (losses[i] == 0.0) {
                if (gains[i] > 0) 100.0 else 50.0
            } else 100 - 100 / (1 + gains[i] / losses[i])
        }
    }

    fun supertrend(
        c: List<ScanCandle>,
        period: Int,
        multiplier: Double
    ): Pair<List<Double>, List<Int>> {
        val tr = c.indices.map { i ->
            if (i == 0) c[i].high - c[i].low else max(
                c[i].high - c[i].low,
                max(abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close))
            )
        }
        val atr = smooth(tr, period, true);
        val line = MutableList(c.size) { Double.NaN };
        val direction = MutableList(c.size) { 0 }
        var upper = Double.NaN;
        var lower = Double.NaN
        c.indices.forEach { i ->
            if (atr[i].isFinite()) {
                val up = (c[i].high + c[i].low) / 2 + multiplier * atr[i];
                val down = (c[i].high + c[i].low) / 2 - multiplier * atr[i]
                if (!upper.isFinite()) {
                    upper = up; lower = down; direction[i] = 1
                } else {
                    upper = if (up < upper || c[i - 1].close > upper) up else upper; lower =
                        if (down > lower || c[i - 1].close < lower) down else lower
                    direction[i] = if (direction[i - 1] == 1) {
                        if (c[i].close < lower) -1 else 1
                    } else {
                        if (c[i].close > upper) 1 else -1
                    }
                }
                line[i] = if (direction[i] == 1) lower else upper
            }
        }
        return line to direction
    }
}

data class TechnicalEvaluation(val row: ScanRow?, val samples: Map<Pair<String, Int>, List<Double>>)
object TechnicalScanner {
    fun evaluate(
        strategy: ScannerStrategy,
        settings: Map<String, Double>,
        stock: ScanStock,
        input: List<ScanCandle>,
        through: LocalDate,
        benchmark: List<ScanCandle> = emptyList()
    ): TechnicalEvaluation {
        fun p(k: String) = settings.getValue(k).toInt()
        var candles =
            input.filter { it.date <= through }.distinctBy { it.date }.sortedBy { it.date }
        if (strategy.id == "rs") {
            val dates = benchmark.map { it.date }.toSet(); candles =
                candles.filter { it.date in dates }
        }
        val minimum = when (strategy.id) {
            "sma" -> p("long") + 1; "breakout" -> 253; "rsi" -> p("trend") + 1; "macd" -> p("slow") * 3 + p(
                "signal"
            ); "supertrend" -> p("atr") * 3; "rs" -> max(
                p("period"),
                p("average")
            ) + 1; else -> error("Unknown technical scanner")
        }
        require(candles.size >= minimum) { "Not enough completed sessions for the selected periods." }
        require(candles.all { it.close.isFinite() && it.close > 0 && it.high.isFinite() && it.low.isFinite() && it.volume.isFinite() }) { "Invalid daily candles." }
        val close = candles.map { it.close };
        val labels = MutableList<String?>(close.size) { null };
        val metrics = mutableMapOf<String, List<Double>>()
        fun cross(spread: List<Double>) {
            for (i in 1 until spread.size) if (spread[i].isFinite() && spread[i - 1].isFinite()) labels[i] =
                if (spread[i] > 0 && spread[i - 1] <= 0) "Bullish" else if (spread[i] < 0 && spread[i - 1] >= 0) "Bearish" else null
        }
        when (strategy.id) {
            "sma" -> {
                val a = ScanIndicators.sma(close, p("short"));
                val b = ScanIndicators.sma(close, p("long")); metrics["Short SMA"] =
                    a; metrics["Long SMA"] = b; cross(close.indices.map { a[it] - b[it] })
            }

            "breakout" -> {
                val high = close.indices.map { i ->
                    if (i < 252) Double.NaN else candles.subList(
                        i - 252,
                        i
                    ).maxOf { it.high }
                }
                val low = close.indices.map { i ->
                    if (i < 252) Double.NaN else candles.subList(
                        i - 252,
                        i
                    ).minOf { it.low }
                }
                val volume = close.indices.map { i ->
                    if (i < p("average")) Double.NaN else {
                        val average = candles.subList(i - p("average"), i).map { it.volume }
                            .average(); if (average > 0) candles[i].volume / average else Double.NaN
                    }
                }
                close.indices.forEach { i ->
                    if (volume[i] >= settings.getValue("volume")) labels[i] =
                        if (close[i] > high[i]) "Breakout" else if (close[i] < low[i]) "Breakdown" else null
                }
                metrics["Prior 52W level"] =
                    close.indices.map { if (labels[it] == "Breakout") high[it] else low[it] }; metrics["Volume vs avg"] =
                    volume
            }

            "rsi" -> {
                val rsi = ScanIndicators.rsi(close, p("period"));
                val trend = ScanIndicators.sma(close, p("trend")); metrics["RSI"] =
                    rsi; metrics["Trend SMA"] = trend
                close.indices.forEach { i ->
                    labels[i] =
                        if (close[i] > trend[i] && rsi[i] < settings.getValue("oversold")) "Oversold dip" else if (close[i] < trend[i] && rsi[i] > settings.getValue(
                                "overbought"
                            )
                        ) "Overbought rally" else null
                }
                metrics["vs trend SMA"] = close.indices.map { (close[it] / trend[it] - 1) * 100 }
            }

            "macd" -> {
                val fast = ScanIndicators.smooth(close, p("fast"));
                val slow = ScanIndicators.smooth(close, p("slow"));
                val line = close.indices.map { fast[it] - slow[it] };
                val trigger = ScanIndicators.smooth(line, p("signal")); metrics["MACD"] =
                    line; cross(close.indices.map { line[it] - trigger[it] })
            }

            "supertrend" -> {
                val (line, direction) = ScanIndicators.supertrend(
                    candles,
                    p("atr"),
                    settings.getValue("multiplier")
                ); metrics["Supertrend now"] =
                    List(close.size) { line.last() }; metrics["Distance to stop"] =
                    List(close.size) { (close.last() / line.last() - 1) * 100 }
                for (i in 1 until close.size) labels[i] =
                    if (direction[i] == 1 && direction[i - 1] == -1) "Bullish" else if (direction[i] == -1 && direction[i - 1] == 1) "Bearish" else null
                metrics["Latest close"] = List(close.size) { close.last() }
            }

            "rs" -> {
                val byDate = benchmark.associateBy { it.date };
                val index = candles.map { byDate.getValue(it.date).close };
                val ratio = close.indices.map { close[it] / index[it] };
                val avg = ScanIndicators.sma(ratio, p("average"))
                val sr = close.indices.map {
                    if (it < p("period")) Double.NaN else (close[it] / close[it - p("period")] - 1) * 100
                };
                val ir = close.indices.map {
                    if (it < p("period")) Double.NaN else (index[it] / index[it - p("period")] - 1) * 100
                }
                val rr = close.indices.map { ((1 + sr[it] / 100) / (1 + ir[it] / 100) - 1) * 100 }
                metrics["Stock return"] = sr; metrics["Nifty 50 return"] =
                    ir; metrics["Relative return"] = rr; metrics["RS vs average"] =
                    close.indices.map { (ratio[it] / avg[it] - 1) * 100 }
                close.indices.forEach { i ->
                    if (rr[i].isFinite()) labels[i] =
                        if (rr[i] > 0) "Outperforming" else "Underperforming"
                }
            }
        }
        val first = through.minusDays((settings["lookback"]?.toLong() ?: 7L) - 1)
        val selected =
            if (strategy.state) candles.lastIndex.takeIf { labels[it] != null && candles[it].date >= first } else candles.indices.lastOrNull { labels[it] != null && candles[it].date >= first }
        val row = selected?.let { i ->
            var start =
                i; if (strategy.state) while (start > 0 && labels[start - 1] == labels[i]) start--
            val fields = mutableListOf<ScanMetric>()
            if (strategy.id != "rs") fields += ScanMetric("Close", close[i], MetricFormat.PRICE)
            metrics.forEach { (name, values) ->
                fields += ScanMetric(
                    name,
                    values[i].takeIf { it.isFinite() },
                    when {
                        name == "Volume vs avg" -> MetricFormat.MULTIPLE; name in listOf(
                        "vs trend SMA",
                        "Distance to stop",
                        "Stock return",
                        "Nifty 50 return",
                        "Relative return",
                        "RS vs average"
                    ) -> MetricFormat.PERCENT; name in listOf(
                        "MACD",
                        "RSI"
                    ) -> MetricFormat.NUMBER; else -> MetricFormat.PRICE
                    }
                )
            }
            fields += ScanMetric(
                "Since signal",
                (close.last() / close[start] - 1) * 100,
                MetricFormat.PERCENT
            )
            ScanRow(
                stock.symbol,
                stock.company,
                labels[i]!!,
                candles[start].date.toString(),
                fields,
                if (strategy.id == "macd") {
                    if (metrics.getValue("MACD")[i] > 0) "Above zero" else "Below zero"
                } else null
            )
        }
        val samples = mutableMapOf<Pair<String, Int>, MutableList<Double>>()
        if (strategy.outcomes) candles.indices.forEach { i ->
            val signal =
                labels[i]; if (signal != null && (i == 0 || labels[i - 1] != signal)) listOf(
            5,
            10,
            20
        ).forEach { h -> if (i + h < close.size) samples.getOrPut(signal to h) { mutableListOf() } += (close[i + h] / close[i] - 1) * 100 }
        }
        return TechnicalEvaluation(row, samples)
    }

    fun rank(strategyId: String, rows: List<ScanRow>): List<ScanRow> {
        fun ScanRow.metric(label: String) = metrics.firstOrNull { it.label == label }?.value ?: 0.0
        return when (strategyId) {
            "rs" -> rows.sortedWith(compareByDescending<ScanRow> { it.metric("Relative return") }.thenBy { it.symbol })
            "rsi" -> rows.sortedWith(compareBy<ScanRow> { it.signal != "Oversold dip" }.thenBy {
                if (it.signal == "Oversold dip") it.metric(
                    "RSI"
                ) else -it.metric("RSI")
            }.thenBy { it.symbol })

            "oi" -> rows.sortedWith(compareByDescending<ScanRow> { abs(it.metric("OI change")) }.thenBy { it.symbol })
            "move" -> rows.sortedWith(compareByDescending<ScanRow> { it.metric("IV/RV") }.thenBy { it.symbol })
            else -> rows.sortedWith(compareByDescending<ScanRow> { it.date }.thenBy { it.symbol })
        }
    }
}
