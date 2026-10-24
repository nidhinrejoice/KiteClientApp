package com.example.kiteclientapp.feature.scanners.domain

import kotlinx.coroutines.*
import java.time.*
import java.util.UUID

class RunScanner(
    private val market: ScannerMarketSource,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend operator fun invoke(
        owner: ScanOwner,
        strategy: ScannerStrategy,
        settings: Map<String, Double>,
        progress: (ScanProgress) -> Unit
    ): ScanRun = withContext(Dispatchers.Default) {
        strategy.validate(settings); market.checkOwner(owner)
        val now = ZonedDateTime.now(clock).withZoneSameInstant(SCANNER_ZONE);
        val through = completedScanDate(now)
        progress(ScanProgress("Loading instruments"));
        val instruments = market.instruments(owner)
        val nse = instruments.filter { it.exchange == "NSE" };
        val today = now.toLocalDate()
        val derivatives =
            instruments.filter { it.exchange == "NFO" && it.expiry != null && it.expiry >= today }
        val stocks = if (strategy.derivatives) {
            val names = derivatives.filter {
                if (strategy.id == "oi") it.type == "FUT" else it.type in listOf(
                    "CE",
                    "PE"
                )
            }.map { it.name }.distinct().sorted()
            require(names.isNotEmpty()) { "No live derivatives are listed by the broker." }
            names.map { name ->
                ScanStock(
                    name,
                    nse.firstOrNull { it.symbol == name }?.name ?: name
                )
            }
        } else market.nifty100(owner)
        val selected = stocks.sortedBy { it.symbol }.take(settings.getValue("limit").toInt())
        require(selected.isNotEmpty()) { "No stocks are available to scan." }
        val from =
            if (strategy.id == "oi") today.minusDays(60) else if (strategy.id == "move" || strategy.id == "rs") through.minusDays(
                420L + ((settings["period"] ?: 0.0).toLong())
            ) else minOf(
                through.minusDays(1096),
                through.minusDays((settings["lookback"] ?: 7.0).toLong() + 650)
            )
        val indexSymbols = mapOf(
            "NIFTY" to "NIFTY 50",
            "BANKNIFTY" to "NIFTY BANK",
            "FINNIFTY" to "NIFTY FIN SERVICE",
            "MIDCPNIFTY" to "NIFTY MID SELECT",
            "NIFTYNXT50" to "NIFTY NEXT 50"
        )

        fun spot(symbol: String): ScanInstrument {
            val matches = nse.filter {
                it.symbol == (indexSymbols[symbol]
                    ?: symbol) && (it.type == "EQ" || it.segment == "INDICES")
            }; require(matches.size == 1) { "No unique NSE spot instrument found." }; return matches.single()
        }

        var benchmark = emptyList<ScanCandle>()
        if (strategy.id == "rs") {
            progress(ScanProgress("Loading Nifty 50 benchmark", 0, selected.size));
            val index = nse.singleOrNull { it.symbol == "NIFTY 50" }
                ?: throw IllegalArgumentException("Nifty 50 benchmark is unavailable."); benchmark =
                market.candles(
                    owner,
                    index,
                    from,
                    through
                ); require(benchmark.lastOrNull()?.date?.let { it >= through.minusDays(6) } == true) { "Nifty 50 benchmark history is too old for this scan." }
        }
        val futures =
            derivatives.filter { it.type == "FUT" && it.name in selected.map { s -> s.symbol } }
                .groupBy { it.name }
        val futureQuotes = if (strategy.id == "oi") {
            progress(ScanProgress("Loading futures quotes", 0, selected.size)); market.quotes(
                owner,
                futures.values.flatten()
            )
        } else emptyMap()

        data class PairPick(val call: ScanInstrument, val put: ScanInstrument)

        val picks = mutableMapOf<String, PairPick>();
        val reasons = mutableMapOf<String, String>();
        var optionQuotes = emptyMap<String, ScanQuote>()
        if (strategy.id == "move") {
            progress(ScanProgress("Loading spot prices", 0, selected.size))
            val spots = selected.mapNotNull { s ->
                try {
                    spot(s.symbol)
                } catch (e: IllegalArgumentException) {
                    reasons[s.symbol] = e.message.orEmpty(); null
                }
            }
            val quotes = market.quotes(owner, spots)
            selected.forEach { stock ->
                val s =
                    spots.firstOrNull { it.symbol == (indexSymbols[stock.symbol] ?: stock.symbol) };
                val price = s?.let { quotes[it.key]?.price }
                if (price == null || price <= 0) reasons[stock.symbol] = "No usable spot quote."
                else {
                    val chain = derivatives.filter {
                        it.name == stock.symbol && it.type in listOf(
                            "CE",
                            "PE"
                        ) && Duration.between(
                            now,
                            it.expiry!!.atTime(15, 30).atZone(SCANNER_ZONE)
                        ).seconds / 86400.0 >= settings.getValue("days") && it.expiry.atTime(15, 30)
                            .atZone(SCANNER_ZONE) > now
                    }
                    val expiry = chain.minOfOrNull { it.expiry!! }
                    val pairs = chain.filter { it.expiry == expiry }.groupBy { it.strike }
                        .filterValues { legs -> legs.any { it.type == "CE" } && legs.any { it.type == "PE" } }
                    val pair = pairs.minByOrNull { kotlin.math.abs(it.key - price) }?.value
                    if (pair == null) reasons[stock.symbol] =
                        "No ATM call and put pair at an eligible expiry."
                    else picks[stock.symbol] =
                        PairPick(pair.first { it.type == "CE" }, pair.first { it.type == "PE" })
                }
            }
            progress(ScanProgress("Loading ATM option quotes", 0, selected.size)); optionQuotes =
                market.quotes(owner, picks.values.flatMap { listOf(it.call, it.put) })
        }
        val rows = mutableListOf<ScanRow>();
        val skipped = mutableListOf<ScanSkip>();
        var noSignal = 0
        val samples = mutableMapOf<Pair<String, Int>, MutableList<Double>>()
        selected.forEachIndexed { i, stock ->
            currentCoroutineContext().ensureActive(); market.checkOwner(owner); progress(
            ScanProgress("Scanning ${stock.symbol}", i, selected.size)
        )
            try {
                val row = when (strategy.id) {
                    "oi" -> {
                        val contracts = futures[stock.symbol].orEmpty().sortedBy { it.expiry };
                        val live = mutableSetOf<LocalDate>()
                        val series = contracts.map { c ->
                            var history = market.candles(owner, c, from, today);
                            val quote = futureQuotes[c.key]
                            if (quote?.date != null && quote.date <= today && quote.price != null && quote.price > 0 && (history.isEmpty() || quote.date > history.last().date)) {
                                require(
                                    history.isNotEmpty() && quote.previousClose != null && quote.previousClose > 0 && kotlin.math.abs(
                                        history.last().close / quote.previousClose - 1
                                    ) <= .002
                                ) { "Previous futures candle is missing or differs from the quote's previous close. Retry later." }
                                val oi = requireNotNull(quote.oi) { "Live futures OI is missing." }
                                history = history + ScanCandle(
                                    quote.date,
                                    quote.price,
                                    quote.price,
                                    quote.price,
                                    quote.price,
                                    quote.volume ?: 0.0,
                                    oi
                                ); live += quote.date
                            }
                            c to history
                        }
                        DerivativeScanners.buildup(stock, series, live, settings, today)
                    }

                    "move" -> {
                        val pick = picks[stock.symbol] ?: throw IllegalArgumentException(
                            reasons[stock.symbol] ?: "Not priced."
                        )
                        val call = optionQuotes[pick.call.key]
                            ?: throw IllegalArgumentException("ATM call quote is missing.");
                        val put = optionQuotes[pick.put.key]
                            ?: throw IllegalArgumentException("ATM put quote is missing.")
                        require(call.date != null && call.date >= today.minusDays(6) && call.date <= today) { "ATM quotes are stale." }
                        val history = market.candles(owner, spot(stock.symbol), from, through)
                        require(history.lastOrNull()?.date?.let { it >= through.minusDays(6) } == true) { "Spot history is too old for realised volatility." }
                        DerivativeScanners.expectedMove(
                            stock,
                            pick.call,
                            pick.put,
                            call,
                            put,
                            history,
                            settings,
                            now
                        )
                    }

                    else -> {
                        val history = market.candles(owner, spot(stock.symbol), from, through)
                        require(history.lastOrNull()?.date?.let { it >= through.minusDays(6) } == true) { "Daily history is too old for this scan." }
                        val result = TechnicalScanner.evaluate(
                            strategy,
                            settings,
                            stock,
                            history,
                            through,
                            benchmark
                        )
                        result.samples.forEach { (key, values) ->
                            samples.getOrPut(key) { mutableListOf() }.addAll(values)
                        }; result.row
                    }
                }
                if (row == null) noSignal++ else rows += row
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScanDataUnavailable) {
                skipped += ScanSkip(stock.symbol, e.message ?: "Market data unavailable.")
            } catch (e: IllegalArgumentException) {
                skipped += ScanSkip(
                    stock.symbol,
                    e.message ?: "Invalid or insufficient market data."
                )
            }
            progress(ScanProgress("Scanning ${stock.symbol}", i + 1, selected.size))
        }
        market.checkOwner(owner)
        require(skipped.size < selected.size) { "No stocks could be scanned. Check broker data access and the skipped-data conditions, then retry." }
        val outcomes = if (strategy.outcomes) strategy.signals.flatMap { signal ->
            listOf(5, 10, 20).map { horizon ->
                val values = samples[signal to horizon].orEmpty(); ForwardOutcome(
                signal,
                horizon,
                values.size,
                if (values.isEmpty()) null else values.count { if (bearish(signal)) it < 0 else it > 0 } * 100.0 / values.size,
                values.takeIf { it.isNotEmpty() }?.average()
            )
            }
        } else emptyList()
        ScanRun(
            UUID.randomUUID().toString(),
            strategy.id,
            clock.instant().toString(),
            through.toString(),
            settings.toMap(),
            selected.size,
            TechnicalScanner.rank(strategy.id, rows),
            skipped,
            noSignal,
            outcomes,
            owner.demo
        )
    }
}
