package com.example.kiteclientapp.feature.scanners.domain

import java.time.*
import kotlin.math.*

object DerivativeScanners {
    fun buildup(
        stock: ScanStock,
        contracts: List<Pair<ScanInstrument, List<ScanCandle>>>,
        liveDates: Set<LocalDate>,
        settings: Map<String, Double>,
        today: LocalDate
    ): ScanRow? {
        require(contracts.isNotEmpty() && contracts.all { it.second.isNotEmpty() }) { "Futures history is missing for one or more live contracts." }
        val ordered = contracts.sortedBy { it.first.expiry };
        val near = ordered.first().second.associateBy { it.date }
        val dates = ordered.flatMap { it.second.map { c -> c.date } }.distinct().sorted()
        val first = ordered.maxOf { it.second.minOf { c -> c.date } }

        data class Combined(
            val date: LocalDate,
            val price: Double,
            val priceChange: Double,
            val oi: Double,
            val change: Double,
            val percent: Double,
            val label: String?
        )

        val points = mutableListOf<Combined>();
        var previousNear: Double? = null
        val previousOi = DoubleArray(ordered.size);
        val currentOi = DoubleArray(ordered.size)
        val maps = ordered.map { it.second.associateBy { c -> c.date } }
        for (date in dates) {
            var delta = 0.0
            maps.forEachIndexed { i, map ->
                map[date]?.let { c ->
                    val oi =
                        requireNotNull(c.oi) { "Missing futures open interest." }; require(oi >= 0 && oi.isFinite()) { "Invalid futures open interest." }; currentOi[i] =
                    oi
                }; delta += currentOi[i] - previousOi[i]; previousOi[i] = currentOi[i]
            }
            val candle = near[date] ?: continue
            val change = previousNear?.let { (candle.close / it - 1) * 100 }; previousNear =
                candle.close
            val oi = currentOi.sum();
            val base = oi - delta
            if (date < first || change == null || base <= 0) continue
            val percent = delta / base * 100
            val rising = change > 0 && change >= settings.getValue("price");
            val falling = change < 0 && change <= -settings.getValue("price")
            val adding = percent > 0 && percent >= settings.getValue("oi");
            val cutting = percent < 0 && percent <= -settings.getValue("oi")
            val label = when {
                rising && adding -> "Long buildup"; falling && adding -> "Short buildup"; rising && cutting -> "Short covering"; falling && cutting -> "Long unwinding"; else -> null
            }
            points += Combined(date, candle.close, change, oi, delta, percent, label)
        }
        val latest = points.lastOrNull()
            ?: throw IllegalArgumentException("Not enough comparable futures sessions.")
        if (latest.date < today.minusDays(6)) throw IllegalArgumentException("Futures data is too old for a current setup.")
        val label = latest.label ?: return null
        val streak = points.takeLastWhile { it.label == label };
        val start = streak.first()
        return ScanRow(
            stock.symbol,
            stock.company,
            label,
            start.date.toString(),
            listOf(
                ScanMetric("Near futures", latest.price, MetricFormat.PRICE),
                ScanMetric("Price change", latest.priceChange, MetricFormat.PERCENT),
                ScanMetric("OI change", latest.percent, MetricFormat.PERCENT),
                ScanMetric("OI added", latest.change, MetricFormat.QUANTITY),
                ScanMetric("Combined OI", latest.oi, MetricFormat.QUANTITY),
                ScanMetric("Sessions", streak.size.toDouble()),
                ScanMetric(
                    "Since buildup",
                    (latest.price / start.price - 1) * 100,
                    MetricFormat.PERCENT
                )
            ),
            if (latest.date in liveDates) "Live · provisional OI · ${latest.date}" else "EOD · ${latest.date}"
        )
    }

    private fun cdf(x: Double): Double {
        val t = 1 / (1 + .2316419 * abs(x));
        val density = exp(-x * x / 2) / sqrt(2 * PI)
        val positive =
            1 - density * t * (.319381530 + t * (-.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (x >= 0) positive else 1 - positive
    }

    fun blackPrice(
        forward: Double,
        strike: Double,
        years: Double,
        vol: Double,
        call: Boolean
    ): Double {
        val s = vol * sqrt(years);
        val d1 = ln(forward / strike) / s + s / 2;
        val d2 = d1 - s;
        val discount = exp(-.065 * years)
        return discount * if (call) forward * cdf(d1) - strike * cdf(d2) else strike * cdf(-d2) - forward * cdf(
            -d1
        )
    }

    fun impliedVol(
        price: Double,
        forward: Double,
        strike: Double,
        years: Double,
        call: Boolean
    ): Double? {
        if (!price.isFinite() || price <= 0 || forward <= 0 || years <= 0 || price <= exp(-.065 * years) * max(
                if (call) forward - strike else strike - forward,
                0.0
            ) || price >= blackPrice(forward, strike, years, 5.0, call)
        ) return null
        var low = .00001;
        var high = 5.0
        repeat(80) {
            val mid = (low + high) / 2; if (blackPrice(
                forward,
                strike,
                years,
                mid,
                call
            ) > price
        ) high = mid else low = mid
        }
        return (low + high) / 2
    }

    fun sessionsLeft(now: ZonedDateTime, expiry: LocalDate): Double {
        var total = 0.0;
        var day = now.toLocalDate()
        if (day.dayOfWeek.value <= 5 && now.toLocalTime() < LocalTime.of(15, 30)) {
            val start = maxOf(now.toLocalTime(), LocalTime.of(9, 15)); total =
                Duration.between(start, LocalTime.of(15, 30)).seconds / (6.25 * 3600)
        }
        day = day.plusDays(1)
        while (day <= expiry) {
            if (day.dayOfWeek.value <= 5) total++; day = day.plusDays(1)
        }
        return total
    }

    fun expectedMove(
        stock: ScanStock,
        call: ScanInstrument,
        put: ScanInstrument,
        callQuote: ScanQuote,
        putQuote: ScanQuote,
        candles: List<ScanCandle>,
        settings: Map<String, Double>,
        now: ZonedDateTime
    ): ScanRow {
        fun mid(q: ScanQuote): Double {
            val bid = q.bid;
            val ask =
                q.ask; require(bid != null && ask != null && bid > 0 && ask >= bid && ask.isFinite()) { "ATM call and put need valid bids and asks." }; return (bid + ask) / 2
        }

        val cp = mid(callQuote);
        val pp = mid(putQuote);
        val straddle = cp + pp
        require(callQuote.date != null && callQuote.date == putQuote.date) { "ATM quotes have missing or mismatched session dates." }
        val spread =
            (callQuote.ask!! + putQuote.ask!! - callQuote.bid!! - putQuote.bid!!) / straddle * 100
        require(spread <= settings.getValue("spread")) { "ATM bid-ask spread exceeds the selected limit." }
        val expiry = requireNotNull(call.expiry);
        val years = Duration.between(
            now,
            expiry.atTime(15, 30).atZone(SCANNER_ZONE)
        ).seconds / (365.0 * 86400)
        require(years > 0) { "The selected expiry has closed." }
        val forward =
            call.strike + exp(.065 * years) * (cp - pp); require(forward > 0) { "ATM option prices imply an invalid forward." }
        val vols = listOfNotNull(
            impliedVol(cp, forward, call.strike, years, true),
            impliedVol(pp, forward, put.strike, years, false)
        )
        require(vols.isNotEmpty()) { "ATM prices imply no usable volatility." };
        val iv = vols.average()
        val closes = candles.map { it.close };
        val period = settings.getValue("rv").toInt()
        require(closes.size > period && closes.all { it.isFinite() && it > 0 }) { "Not enough completed closes for realised volatility." }
        val returns = closes.takeLast(period + 1).zipWithNext { a, b -> ln(b / a) };
        val mean = returns.average();
        val rv = sqrt(returns.sumOf { (it - mean).pow(2) } / (returns.size - 1)) * sqrt(252.0)
        require(rv > 0 && rv.isFinite()) { "Realised volatility is unavailable or zero." }
        val ratio = iv / rv;
        val move = sqrt(2 / PI) * iv * sqrt(years);
        val horizon = max(1, sessionsLeft(now, expiry).roundToInt())
        val moves = (horizon until closes.size).map { abs(closes[it] / closes[it - horizon] - 1) }
            .takeLast(252).sorted()
        val median =
            if (moves.isEmpty()) null else if (moves.size % 2 == 0) (moves[moves.size / 2 - 1] + moves[moves.size / 2]) / 2 * 100 else moves[moves.size / 2] * 100
        val label =
            if (ratio >= settings.getValue("rich")) "Rich" else if (ratio <= settings.getValue("cheap")) "Cheap" else "Fair"
        return ScanRow(
            stock.symbol, stock.company, label, candles.last().date.toString(), listOf(
                ScanMetric("IV/RV", ratio),
                ScanMetric("ATM IV", iv * 100, MetricFormat.RATE),
                ScanMetric("Realised volatility", rv * 100, MetricFormat.RATE),
                ScanMetric("Implied move", move * 100, MetricFormat.PLUS_MINUS),
                ScanMetric(
                    "Breached past year",
                    if (moves.isEmpty()) null else moves.count { it > move } * 100.0 / moves.size,
                    MetricFormat.RATE
                ),
                ScanMetric("Median past move", median, MetricFormat.PLUS_MINUS),
                ScanMetric("Straddle", straddle, MetricFormat.PRICE),
                ScanMetric(
                    "Premium per lot",
                    if (call.lot > 0) straddle * call.lot else null,
                    MetricFormat.PRICE
                ),
                ScanMetric("Bid-ask spread", spread, MetricFormat.RATE),
                ScanMetric("Days to expiry", years * 365)
            ), "Expiry $expiry · quotes ${callQuote.date} · weekday sessions exclude holidays"
        )
    }
}
