package com.example.kiteclientapp.feature.scanners.data

import com.example.kiteclientapp.data.*
import com.example.kiteclientapp.domain.SessionExpired
import com.example.kiteclientapp.feature.scanners.domain.*
import com.google.gson.JsonArray
import kotlinx.coroutines.*
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.*
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

data class ScannerCandlesDto(val candles: List<JsonArray>?)
data class ScannerDepthLevel(val price: Double?)
data class ScannerDepthDto(val buy: List<ScannerDepthLevel>?, val sell: List<ScannerDepthLevel>?)
data class ScannerOhlcDto(val close: Double?)
data class ScannerQuoteDto(
    val last_price: Double?,
    val ohlc: ScannerOhlcDto?,
    val depth: ScannerDepthDto?,
    val oi: Double?,
    val volume: Double?,
    val last_trade_time: String?,
    val timestamp: String?
)

internal fun scannerCsv(line: String): List<String> {
    val fields = mutableListOf<String>();
    val value = StringBuilder();
    var quoted = false;
    var i = 0
    while (i < line.length) {
        val c = line[i]; if (c == '"') {
            if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                value.append('"'); i++
            } else quoted = !quoted
        } else if (c == ',' && !quoted) {
            fields += value.toString(); value.clear()
        } else value.append(c); i++
    }
    fields += value.toString(); return fields
}

@Singleton
class KiteScannerMarketSource @Inject constructor(
    retrofit: Retrofit,
    private val sessions: SessionStore,
    private val config: KiteConfiguration,
    private val gate: KiteMarketRequestGate
) : ScannerMarketSource {
    private val api = retrofit.create(ScannerKiteApi::class.java)
    private var catalog: Pair<LocalDate, List<ScanInstrument>>? = null
    private var constituents: Pair<LocalDate, List<ScanStock>>? = null
    private val historyCache = object : LinkedHashMap<String, List<ScanCandle>>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ScanCandle>>?) =
            size > 256
    }
    private var cacheOwner: ScanOwner? = null
    override fun checkOwner(owner: ScanOwner) {
        if (owner.demo) {
            if (!sessions.isDemo) throw SessionExpired()
        } else {
            if (sessions.isDemo || sessions.accountId != owner.id) throw SessionExpired(); sessions.authorization(
                config.apiKey
            )
        }
    }

    private suspend fun <T> request(owner: ScanOwner, block: suspend (String) -> T): T {
        checkOwner(owner);
        val auth = sessions.authorization(config.apiKey)
        try {
            val result = block(auth); checkOwner(owner); return result
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                checkOwner(owner); throw SessionExpired()
            }
            if (e.code() == 429) throw IllegalStateException("Broker request limit reached. Wait a moment and retry.")
            throw ScanDataUnavailable("Broker market data is unavailable for this instrument.")
        }
    }

    private fun <T> Envelope<T>.checked(): T {
        if (status != "success" || data == null) throw ScanDataUnavailable("Broker returned incomplete market data."); return data!!
    }

    override suspend fun instruments(owner: ScanOwner): List<ScanInstrument> =
        withContext(Dispatchers.IO) {
            checkOwner(owner); if (owner.demo) return@withContext DemoScannerData.instruments()
            val date = LocalDate.now(SCANNER_ZONE); catalog?.takeIf { it.first == date }
            ?.let { return@withContext it.second }
            val all = mutableListOf<ScanInstrument>()
            for (exchange in listOf("NSE", "NFO")) request(owner) { auth ->
                api.instruments(auth, exchange).use { body ->
                    body.charStream().buffered().useLines { lines ->
                        var headers = emptyList<String>(); lines.forEachIndexed { i, line ->
                        val row = scannerCsv(line); if (i == 0) headers =
                        row.map { it.trim().removePrefix("\uFEFF") } else {
                        fun f(key: String) = row.getOrNull(headers.indexOf(key)).orEmpty()
                        val token = f("instrument_token").toLongOrNull()
                        if (token != null) all += ScanInstrument(
                            token,
                            f("tradingsymbol"),
                            f("name"),
                            exchange,
                            f("instrument_type"),
                            f("segment"),
                            f("expiry").takeIf { it.isNotBlank() }?.let(LocalDate::parse),
                            f("strike").toDoubleOrNull() ?: 0.0,
                            f("lot_size").toIntOrNull() ?: 0
                        )
                    }
                    }
                    }
                }
            }
            require(all.isNotEmpty()) { "The broker instrument list is empty." }; catalog =
            date to all; all
        }

    override suspend fun nifty100(owner: ScanOwner): List<ScanStock> = withContext(Dispatchers.IO) {
        checkOwner(owner); if (owner.demo) return@withContext DemoScannerData.stocks
        val date = LocalDate.now(SCANNER_ZONE); constituents?.takeIf { it.first == date }
        ?.let { return@withContext it.second }
        val stocks = api.constituents().use { body ->
            val lines = body.string().lineSequence().filter { it.isNotBlank() }.toList(); require(
            lines.isNotEmpty()
        ) { "Nifty 100 constituent list is empty." }
            val header = scannerCsv(lines.first()).map { it.trim().removePrefix("\uFEFF") };
            val symbol = header.indexOf("Symbol");
            val company = header.indexOf("Company Name")
            require(symbol >= 0 && company >= 0) { "Could not read the Nifty 100 constituent list." }
            lines.drop(1).map { scannerCsv(it) }.map {
                ScanStock(
                    it.getOrNull(symbol).orEmpty().trim(),
                    it.getOrNull(company).orEmpty().trim()
                )
            }
        }
        require(stocks.size == 100 && stocks.map { it.symbol }
            .distinct().size == 100 && stocks.all { it.symbol.isNotBlank() && it.company.isNotBlank() }) { "The Nifty 100 constituent list is incomplete. Retry later." }; checkOwner(
        owner
    ); constituents = date to stocks; stocks
    }

    override suspend fun candles(
        owner: ScanOwner,
        instrument: ScanInstrument,
        from: LocalDate,
        through: LocalDate
    ): List<ScanCandle> = withContext(Dispatchers.IO) {
        checkOwner(owner); if (owner.demo) return@withContext DemoScannerData.candles(
        instrument,
        from,
        through
    )
        if (cacheOwner != owner) {
            historyCache.clear(); cacheOwner = owner
        }
        val key = "${instrument.key}:$from:$through:${LocalDate.now(SCANNER_ZONE)}"
        // Today's futures candles are provisional until the broker finalises OI; never cache them.
        val cacheable = through < LocalDate.now(SCANNER_ZONE)
        if (cacheable) historyCache[key]?.let { return@withContext it }
        gate.history()
        val raw = request(owner) {
            api.candles(
                it,
                instrument.token,
                "$from 00:00:00",
                "$through 23:59:59"
            ).checked().candles
        } ?: throw ScanDataUnavailable("No daily candles returned.")
        val result = try {
            raw.map { a ->
                require(a.size() >= 6); ScanCandle(
                LocalDate.parse(a[0].asString.take(10)),
                a[1].asDouble,
                a[2].asDouble,
                a[3].asDouble,
                a[4].asDouble,
                a[5].asDouble,
                if (a.size() > 6 && !a[6].isJsonNull) a[6].asDouble else null
            )
            }.associateBy { it.date }.values.filter { it.date in from..through }
                .sortedBy { it.date }
        } catch (e: Exception) {
            throw ScanDataUnavailable("Invalid daily candles from the broker.")
        }
        if (result.any { !it.close.isFinite() || it.close <= 0 || !it.high.isFinite() || !it.low.isFinite() || it.high < it.low || !it.volume.isFinite() || it.volume < 0 }) throw ScanDataUnavailable(
            "Invalid daily prices or volume."
        )
        if (cacheable) historyCache[key] = result; result
    }

    override suspend fun quotes(
        owner: ScanOwner,
        instruments: List<ScanInstrument>
    ): Map<String, ScanQuote> = withContext(Dispatchers.IO) {
        checkOwner(owner); if (owner.demo) return@withContext DemoScannerData.quotes(instruments)
        val result = mutableMapOf<String, ScanQuote>()
        for (batch in instruments.distinctBy { it.key }.chunked(400)) {
            gate.quotes();
            val quotes = request(owner) { api.quotes(it, batch.map { c -> c.key }).checked() }
            quotes.forEach { (key, q) ->
                result[key] = ScanQuote(
                    q.last_price?.takeIf { it.isFinite() },
                    q.ohlc?.close,
                    q.depth?.buy?.firstOrNull()?.price,
                    q.depth?.sell?.firstOrNull()?.price,
                    q.oi,
                    q.volume,
                    (q.last_trade_time ?: q.timestamp)?.take(10)
                        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
            }
        }; result
    }
}

internal object DemoScannerData {
    val stocks =
        (1..100).map { ScanStock("SAMPLE${it.toString().padStart(3, '0')}", "Sample company $it") }

    fun instruments(): List<ScanInstrument> {
        val expiry = LocalDate.now(SCANNER_ZONE).plusDays(10)
        return stocks.mapIndexed { i, s ->
            ScanInstrument(
                i + 1L,
                s.symbol,
                s.company,
                "NSE",
                "EQ",
                "NSE"
            )
        } + ScanInstrument(999, "NIFTY 50", "NIFTY 50", "NSE", "INDEX", "INDICES") + stocks.take(8)
            .flatMapIndexed { i, s ->
                listOf(
                    ScanInstrument(
                        2000 + i * 10L,
                        "${s.symbol}FUT",
                        s.symbol,
                        "NFO",
                        "FUT",
                        "NFO-FUT",
                        expiry
                    )
                ) + listOf("CE", "PE").mapIndexed { j, t ->
                    ScanInstrument(
                        2001 + i * 10L + j,
                        "${s.symbol}$t",
                        s.symbol,
                        "NFO",
                        t,
                        "NFO-OPT",
                        expiry,
                        100.0,
                        1
                    )
                }
            }
    }

    fun candles(instrument: ScanInstrument, from: LocalDate, through: LocalDate): List<ScanCandle> {
        val seed =
            if (instrument.type == "FUT") instrument.name.filter { it.isDigit() }.toIntOrNull()
                ?: 1 else instrument.symbol.filter { it.isDigit() }.toIntOrNull() ?: 1
        return generateSequence(from) { it.plusDays(1) }.takeWhile { it <= through }
            .filter { it.dayOfWeek.value <= 5 }.map { date ->
            val t = date.toEpochDay().toDouble();
            val price =
                100 * exp(.0001 * (t - 20000) + .06 * sin(t * .04 + seed) + .015 * sin(t * .27 + seed))
            ScanCandle(
                date,
                price * .998,
                price * 1.01,
                price * .99,
                price,
                100000.0 * (1 + abs(sin(t * .3 + seed)) * 2),
                if (instrument.type == "FUT") 100000 * (1 + .2 * sin(t * .1 + seed)) else null
            )
        }.toList()
    }

    fun quotes(instruments: List<ScanInstrument>): Map<String, ScanQuote> =
        instruments.associate { c ->
            val now = ZonedDateTime.now(SCANNER_ZONE);
            val date =
                generateSequence(completedScanDate(now)) { it.minusDays(1) }.first { it.dayOfWeek.value <= 5 }
            val value = if (c.type in listOf("CE", "PE")) {
                val years = Duration.between(
                    now,
                    c.expiry!!.atTime(15, 30).atZone(SCANNER_ZONE)
                ).seconds / (365.0 * 86400); DerivativeScanners.blackPrice(
                    100 * exp(.065 * years),
                    100.0,
                    years,
                    .24,
                    c.type == "CE"
                )
            } else 100.0
            c.key to ScanQuote(value, 99.0, value * .99, value * 1.01, 100000.0, 10000.0, date)
        }
}
