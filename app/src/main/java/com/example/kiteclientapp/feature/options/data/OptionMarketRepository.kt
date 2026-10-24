package com.example.kiteclientapp.feature.options.data

import com.example.kiteclientapp.data.*
import com.example.kiteclientapp.domain.SessionExpired
import com.example.kiteclientapp.feature.options.domain.*
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import kotlinx.coroutines.*
import retrofit2.HttpException
import retrofit2.Retrofit
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

val OPTIONS_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
val INDEX_NAMES = linkedMapOf(
    "NIFTY" to "NIFTY 50",
    "BANKNIFTY" to "NIFTY BANK",
    "FINNIFTY" to "NIFTY FIN SERVICE",
    "MIDCPNIFTY" to "NIFTY MID SELECT"
)

fun expiryInstant(date: LocalDate): Instant = date.atTime(15, 30).atZone(OPTIONS_ZONE).toInstant()
data class DepthPrice(val price: Double?)
data class Depth(val buy: List<DepthPrice>?, val sell: List<DepthPrice>?)
data class QuoteOhlc(val close: Double?)
data class MarketQuote(
    val instrument_token: Long,
    val last_price: Double?,
    val oi: Long?,
    val volume: Long?,
    val depth: Depth?,
    val timestamp: String?,
    val ohlc: QuoteOhlc?
)

private fun JsonArray.getOrNull(index: Int): JsonElement? =
    if (index in 0 until size()) get(index) else null

data class Candles(val candles: List<JsonArray>)

data class OptionInstrument(
    val token: Long,
    val symbol: String,
    val name: String,
    val expiry: LocalDate?,
    val strike: Double,
    val lot: Int,
    val type: String,
    val exchange: String
)

// Quoted fields in the broker CSV may contain commas or escaped quotes.
internal fun csvFields(line: String): List<String> {
    val fields = mutableListOf<String>();
    val part = StringBuilder();
    var quoted = false;
    var i = 0
    while (i < line.length) {
        val c = line[i]; if (c == '"') {
            if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                part.append('"'); i++
            } else quoted = !quoted
        } else if (c == ',' && !quoted) {
            fields += part.toString(); part.clear()
        } else part.append(c); i++
    }
    fields += part.toString(); return fields
}

@Singleton
class OptionMarketRepository @Inject constructor(
    retrofit: Retrofit,
    private val session: SessionStore,
    private val config: KiteConfiguration,
    private val requestGate: KiteMarketRequestGate = KiteMarketRequestGate()
) {
    private val api = retrofit.create(OptionsMarketApi::class.java)
    private var catalogDate: LocalDate? = null
    private var catalog = emptyList<OptionInstrument>()
    private val oiCache = mutableMapOf<Pair<String, LocalDate>, Long?>()
    private val closeCache = mutableMapOf<Pair<Long, LocalDate>, List<Double>>()
    private fun <T> Envelope<T>.checked(): T {
        check(status == "success" && data != null) { "Broker returned incomplete market data." }; return data!!
    }

    private suspend fun <T> request(block: suspend (String) -> T): T {
        try {
            return block(session.authorization(config.apiKey))
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                session.clear(); throw SessionExpired()
            }; throw e
        }
    }

    private suspend fun instruments(): List<OptionInstrument> {
        val today = LocalDate.now(OPTIONS_ZONE)
        if (catalogDate == today && catalog.isNotEmpty()) return catalog
        val rows = mutableListOf<OptionInstrument>()
        for (exchange in listOf("NFO", "NSE")) {
            request { auth ->
                api.instruments(auth, exchange).use { body ->
                    body.charStream().buffered().useLines { lines ->
                        var headers = emptyList<String>()
                        lines.forEachIndexed { i, line ->
                            val p = csvFields(line)
                            if (i == 0) headers = p else {
                                fun field(key: String) = p.getOrNull(headers.indexOf(key)).orEmpty()
                                val name = field("name");
                                val symbol = field("tradingsymbol")
                                if ((exchange == "NFO" && name in INDEX_NAMES && field("instrument_type") in listOf(
                                        "CE",
                                        "PE"
                                    )) || (exchange == "NSE" && symbol in INDEX_NAMES.values)
                                ) {
                                    rows += OptionInstrument(
                                        field("instrument_token").toLong(),
                                        symbol,
                                        name,
                                        field("expiry").takeIf { it.isNotBlank() }
                                            ?.let(LocalDate::parse),
                                        field("strike").toDoubleOrNull() ?: 0.0,
                                        field("lot_size").toIntOrNull() ?: 0,
                                        field("instrument_type"),
                                        exchange
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        catalog = rows; catalogDate = today; return rows
    }

    suspend fun load(index: String, expiry: LocalDate?): ChainSnapshot =
        withContext(Dispatchers.IO) {
            require(index in INDEX_NAMES)
            if (session.isDemo) return@withContext demoChain(index, expiry)
            val now = Instant.now();
            val all = instruments()
            val options =
                all.filter { it.name == index && it.expiry != null && expiryInstant(it.expiry) > now }
            val expiries = options.mapNotNull { it.expiry }.distinct().sorted()
            require(expiries.isNotEmpty()) { "The broker lists no open contracts for this index." }
            val selected = expiry?.takeIf { expiryInstant(it) > now } ?: expiries.first(); require(
            selected in expiries
        ) { "Expiry is no longer available. Choose the next expiry." }
            val contracts = options.filter { it.expiry == selected }
            val spotKey = "NSE:${INDEX_NAMES.getValue(index)}"
            val quotes = mutableMapOf<String, MarketQuote>()
            for (batch in (listOf(
                spotKey,
                "NSE:INDIA VIX"
            ) + contracts.map { "NFO:${it.symbol}" }).chunked(400)) {
                if (quotes.isNotEmpty()) delay(1050) // Quote endpoint: one request per second.
                requestGate.quotes()
                quotes += request { api.quotes(it, batch).checked() }
            }
            val spot = quotes[spotKey]?.last_price
            require(spot != null && spot > 0) { "Broker did not return an index price." }
            val stamp = quotes[spotKey]?.timestamp
            val date = stamp?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: now.atZone(OPTIONS_ZONE).toLocalDate()
            ChainSnapshot(
                index,
                selected,
                expiries,
                spot,
                now,
                date,
                (expiryInstant(selected).toEpochMilli() - now.toEpochMilli()) / 1000.0 / YEAR_SECONDS,
                contracts.map { c ->
                    val q = quotes["NFO:${c.symbol}"]
                    ChainLeg(
                        c.token,
                        c.symbol,
                        c.type == "CE",
                        c.strike,
                        c.lot,
                        q?.last_price,
                        q?.depth?.buy?.firstOrNull()?.price,
                        q?.depth?.sell?.firstOrNull()?.price,
                        q?.oi,
                        q?.volume,
                        quoteTime = q?.timestamp,
                        previousClose = q?.ohlc?.close
                    )
                },
                quoteTime = stamp,
                previousClose = quotes[spotKey]?.ohlc?.close,
                vix = quotes["NSE:INDIA VIX"]?.last_price
            )
        }

    // Fetch history only for the visible window, retaining all strikes for aggregate analytics.
    suspend fun enrich(
        chain: ChainSnapshot,
        strikes: Set<Double>,
        publish: (ChainSnapshot) -> Unit
    ): ChainSnapshot = withContext(Dispatchers.IO) {
        if (chain.demo) return@withContext chain
        var result = chain
        val index =
            catalog.firstOrNull { it.exchange == "NSE" && it.symbol == INDEX_NAMES[chain.underlying] }

        suspend fun candles(token: Long, from: LocalDate, to: LocalDate): List<JsonArray> {
            requestGate.history() // Shared pacing with on-device scanners.
            return request {
                api.history(it, token, "$from 00:00:00", "$to 23:59:59").checked().candles
            }
        }

        suspend fun <T> optional(block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpired) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (index != null) {
            val key = index.token to chain.sessionDate
            val closes = closeCache[key] ?: optional {
                candles(
                    index.token,
                    chain.sessionDate.minusDays(70),
                    chain.sessionDate.minusDays(1)
                ).also { rows ->
                    rows.lastOrNull()?.getOrNull(0)?.asString?.take(10)
                        ?.let { previousSessionCache[chain.sessionDate] = it }
                }.mapNotNull { it.getOrNull(4)?.asDouble }
            }?.also { closeCache[key] = it }
            result = result.copy(
                closes = closes.orEmpty(),
                historyNote = if (closes == null) "Index history unavailable." else null
            )
            publish(result)
        }
        for (leg in chain.legs.filter { it.strike in strikes }) {
            val key = leg.symbol to chain.sessionDate
            var status = "Loaded"
            val prior = if (oiCache.containsKey(key)) oiCache[key] else {
                val rows = optional {
                    candles(
                        leg.token,
                        chain.sessionDate.minusDays(14),
                        chain.sessionDate.minusDays(1)
                    )
                }
                if (rows == null) {
                    status = "History unavailable"; null
                } else {
                    // Previous market session, not the last day on which this contract happened to trade.
                    val expected = previousSessionCache[chain.sessionDate]
                    val row = rows.lastOrNull()
                        ?.takeIf { expected != null && it[0].asString.take(10) == expected }
                    val value = row?.getOrNull(6)?.takeUnless { it.isJsonNull }?.asLong
                    if (value != null) oiCache[key] = value
                    value
                }
            }
            if (prior == null && status == "Loaded") status = "No previous-session OI"
            result = result.copy(legs = result.legs.map {
                if (it.symbol == leg.symbol) it.copy(
                    previousOi = prior,
                    historyStatus = status
                ) else it
            })
            publish(result)
        }
        result
    }

    private val previousSessionCache = mutableMapOf<LocalDate, String>()
}

internal fun demoChain(index: String = "NIFTY", selected: LocalDate? = null): ChainSnapshot {
    val now = Instant.now();
    val today = now.atZone(OPTIONS_ZONE).toLocalDate();
    val expiries = listOf(today.plusDays(7), today.plusDays(14), today.plusDays(28))
    val expiry = selected?.takeIf { it in expiries } ?: expiries.first();
    val years = (expiryInstant(expiry).toEpochMilli() - now.toEpochMilli()) / 1000.0 / YEAR_SECONDS
    val spot = 25000.0;
    val forward = spot * exp(.02 * years)
    val legs = (-30..30).flatMap { offset ->
        val strike = 25000.0 + offset * 50; listOf(true, false).map { call ->
        val price =
            OptionModels.price(forward, strike, years, .16 + (if (call) 0.0 else .008), call)
        val oi = (100000 + 500000 * exp(
            -(offset - (if (call) 5 else -5)).toDouble().pow(2) / 45
        )).toLong()
        ChainLeg(
            (offset + 31) * 2L + if (call) 0 else 1,
            "SAMPLE-$strike-${if (call) "CE" else "PE"}",
            call,
            strike,
            1,
            price,
            max(price - .05, .01),
            price + .05,
            oi,
            10000,
            oi - 5000,
            "Synthetic",
            now.toString(),
            price * 1.08
        )
    }
    }
    return ChainSnapshot(
        index,
        expiry,
        expiries,
        spot,
        now,
        today,
        years,
        legs,
        (0..30).map { 25000 * exp(.006 * sin(it.toDouble())) },
        true,
        "Synthetic prices and OI · unit lot size 1",
        now.toString(),
        25100.0,
        15.68
    )
}
