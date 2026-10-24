package com.example.kiteclientapp.feature.options.presentation

import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.kiteclientapp.ui.theme.WorkspacePalette
import com.example.kiteclientapp.ui.theme.WorkspaceTheme
import com.example.kiteclientapp.feature.options.data.*
import com.example.kiteclientapp.feature.options.domain.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

private val Ink = WorkspacePalette.Background
private val Panel = WorkspacePalette.Surface
private val Raised = WorkspacePalette.Raised
private val Rule = WorkspacePalette.Border
private val Foreground = WorkspacePalette.Text
private val Muted = WorkspacePalette.Muted
private val Mint = WorkspacePalette.Accent
private val Coral = WorkspacePalette.Loss
private val Calls = Color(0xFFD37443)
private val Puts = Color(0xFF679CE8)
private fun num(v: Double?, digits: Int = 2) =
    v?.let { String.format(Locale.forLanguageTag("en-IN"), "%,.${digits}f", it) } ?: "—"

private fun pct(v: Double?, digits: Int = 1) = v?.let { "${num(it * 100, digits)}%" } ?: "—"
private fun signed(v: Double?, digits: Int = 2) =
    v?.let { (if (it > 0) "+" else if (it < 0) "−" else "") + num(abs(it), digits) } ?: "—"

private fun cash(v: Double?) =
    v?.let { (if (it < 0) "−" else if (it > 0) "+" else "") + "₹" + num(abs(it), 0) } ?: "—"

private fun compact(v: Double?): String = when {
    v == null -> "—"; abs(v) >= 1e7 -> "${num(v / 1e7, 2)} Cr"; abs(v) >= 1e5 -> "${
        num(
            v / 1e5,
            1
        )
    } L"; abs(v) >= 1000 -> num(v, 0); else -> num(v, 0)
}

private fun tone(v: Double?) = if (v == null || v == 0.0) Foreground else if (v > 0) Mint else Coral
private val shortDate = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH)
private val indexLabels = mapOf(
    "NIFTY" to "Nifty",
    "BANKNIFTY" to "Bank Nifty",
    "FINNIFTY" to "Fin Nifty",
    "MIDCPNIFTY" to "Midcap Nifty"
)

@Composable
fun OptionsWorkspace(
    manual: OptionsState,
    onManual: (OptionsIntent) -> Unit,
    market: MarketOptionsState,
    vm: MarketOptionsViewModel
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var auto by rememberSaveable { mutableStateOf(true) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(vm) { if (vm.state.value.chain == null && !vm.state.value.loading && vm.state.value.error == null) vm.load() }
    LaunchedEffect(auto, tab, market.index, owner) {
        if (auto && tab == 0) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(30000);
                val s =
                    vm.state.value; if (s.chain != null && !s.loading && !s.historyLoading && !s.chain.demo) vm.load()
            }
        }
    }
    WorkspaceTheme {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "DERIVATIVES",
                            color = Muted,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        ); Text("Options", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        if (market.chain?.demo == true) "● Demo" else if (market.chain != null) "● Connected" else "Analytics",
                        color = if (market.chain != null) Mint else Muted,
                        fontSize = 11.sp
                    )
                    Box {
                        TextButton(
                            onClick = { menu = true },
                            modifier = Modifier
                                .testTag("options-menu")
                                .semantics { contentDescription = "More options" }) {
                            Text(
                                "⋮",
                                fontSize = 24.sp
                            )
                        }
                        DropdownMenu(menu,
                            {
                                menu = false
                            }) {
                            DropdownMenuItem(
                                text = { Text(if (tab == 2) "Return to option chain" else "Manual payoff") },
                                onClick = { menu = false; tab = if (tab == 2) 0 else 2 })
                        }
                    }
                }
                if (tab != 2) Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .borderBottom(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    listOf("Option chain", "Straddle & strangle").forEachIndexed { i, label ->
                        Box(
                            Modifier
                                .selectable(
                                    selected = tab == i,
                                    role = Role.Tab,
                                    onClick = { tab = i })
                                .then(if (tab == i) Modifier.drawUnderline() else Modifier)
                                .padding(vertical = 16.dp)
                        ) {
                            Text(
                                label,
                                color = if (tab == i) Foreground else Muted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                if (tab == 2) OptionsScreen(manual, onManual) else MarketBody(
                    market,
                    vm,
                    tab == 1,
                    auto,
                    { auto = it })
            }
        }
    }
}

private fun Modifier.borderBottom() = drawBehind {
    drawLine(
        Rule,
        Offset(0f, size.height),
        Offset(size.width, size.height),
        1.dp.toPx()
    )
}

private fun Modifier.drawUnderline() = drawBehind {
    drawLine(
        Mint,
        Offset(0f, size.height - 1.dp.toPx()),
        Offset(size.width, size.height - 1.dp.toPx()),
        2.dp.toPx()
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarketBody(
    s: MarketOptionsState,
    vm: MarketOptionsViewModel,
    strategy: Boolean,
    auto: Boolean,
    onAuto: (Boolean) -> Unit
) {
    val chain = s.chain
    val tableScroll = rememberScrollState()
    var oiChange by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Double?>(null) }
    var method by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("market-options-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        spacedItem {
            Column(
                Modifier.padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Segments(s.index, indexLabels.toList(), vm::selectIndex, Modifier.fillMaxWidth())
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Selector(
                        "Expiry",
                        chain?.expiry?.format(shortDate) ?: "Expiry",
                        Modifier.weight(1f),
                        enabled = chain != null
                    ) {
                        chain?.expiries?.forEach { date ->
                            SelectItem(
                                date.format(shortDate),
                                chain.expiry == date
                            ) { vm.selectExpiry(date) }
                        }
                    }
                    if (!strategy) Selector(
                        "Strike window",
                        "±${s.window}",
                        Modifier.width(78.dp)
                    ) {
                        listOf(10, 15, 20, 30).forEach { n ->
                            SelectItem(
                                "±$n strikes",
                                s.window == n
                            ) { vm.window(n) }
                        }
                    }
                    OutlinedButton(
                        onClick = vm::load,
                        enabled = !s.loading,
                        shape = RoundedCornerShape(5.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(42.dp)
                    ) { Text(if (s.loading) "Loading…" else "↻ Refresh", fontSize = 12.sp) }
                }
            }
        }
        if (s.loading) spacedItem {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = Mint,
                trackColor = Rule
            )
        }
        s.error?.let {
            spacedItem {
                Text(
                    it,
                    color = Coral,
                    fontSize = 12.sp
                ); TextButton(onClick = vm::load) { Text("Retry") }
            }
        }
        if (chain != null) {
            spacedItem { QuoteSummary(chain, strategy, auto, onAuto) }
            if (chain.demo) spacedItem {
                Text(
                    "DEMO · Synthetic prices and OI · Unit lot size 1",
                    color = Mint,
                    fontSize = 11.sp
                )
            }
            if (strategy) {
                spacedItem { StrategyControls(s, chain, vm) }
                s.simulationError?.let { spacedItem { Text(it, color = Coral, fontSize = 12.sp) } }
                s.simulation?.let { r ->
                    spacedItem { StrategyMetrics(s, chain, r) }
                    spacedItem { PnlChart(chain, r, s.targetDate == chain.expiry.toString()) }
                    spacedItem {
                        Text(
                            "Estimates by range",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        ); Text(
                        "Index range, move from spot, model chance and P&L at ${s.targetDate} close.",
                        color = Muted,
                        fontSize = 11.sp
                    )
                    }
                    item { RangeHeader() }
                    spacedItem {
                        Column {
                            r.bands.forEach { band ->
                                RangeRow(
                                    chain,
                                    band,
                                    r.bands.maxOf { it.chance })
                            }
                        }
                    }
                    spacedItem {
                        Text(
                            "Tail P&L is sampled through ±6σ; it does not bound potential losses.",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                    if (r.usedAtmFallback) spacedItem {
                        Text(
                            "Unavailable leg IV uses ATM IV for repricing.",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                spacedItem { ChainMetrics(chain) }
                spacedItem {
                    SectionHeading("Open interest by strike")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Legend(
                            "Calls",
                            Calls
                        ); Spacer(Modifier.width(16.dp)); Legend("Puts", Puts); Spacer(
                        Modifier.weight(
                            1f
                        )
                    ); Text("ATM ${num(chain.atm, 0)}", color = Muted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Segments(
                        if (oiChange) "change" else "oi",
                        listOf("oi" to "Open interest", "change" to "Change in OI"),
                        { oiChange = it == "change" })
                    OiChart(chain, vm.visibleStrikes(chain), oiChange)
                }
                spacedItem {
                    SectionHeading("Option chain"); Text(
                    "Shaded cells are ITM. Swipe columns for IV, delta, volume and OI. Tap a row for quotes.",
                    fontSize = 11.sp,
                    color = Muted
                )
                }
                if (s.historyLoading) spacedItem {
                    Text(
                        "Loading previous-session OI…",
                        fontSize = 10.sp,
                        color = Muted
                    ); LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = Mint,
                    trackColor = Rule
                )
                }
                stickyHeader { ChainHeader(tableScroll) }
                spacedItem {
                    Column {
                        vm.visibleStrikes(chain).forEach { strike ->
                            ChainRow(chain, strike, tableScroll) {
                                detail = strike
                            }
                        }
                    }
                }
                spacedItem {
                    Text(
                        "PCR and max pain use all ${chain.strikes.size} listed strikes. OI in units · 1 lot = ${chain.legs.firstOrNull()?.lotSize ?: "—"}. Prices in INR.",
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
                chain.historyNote?.let { spacedItem { Text(it, color = Muted, fontSize = 11.sp) } }
            }
        } else if (!s.loading && s.error == null) spacedItem {
            Text(
                "Connect with Zerodha for broker data or explore the portfolio demo.",
                color = Muted
            )
        }
        spacedItem {
            TextButton(
                onClick = { method = true },
                contentPadding = PaddingValues(0.dp)
            ) { Text("About these estimates", fontSize = 12.sp) }
        }
    }
    if (method) AlertDialog(
        onDismissRequest = { method = false },
        title = { Text("About these estimates") },
        text = {
            Text(
                "Prices prefer bid/ask midpoints, then last trades. IV and delta use Black-76 with a 6.5% discount rate and a parity-implied forward. Probabilities use lognormal index levels and the snapshot's ATM IV; changing IV reprices the options only. Real markets can have fatter tails. Worst 5% is the fifth-percentile P&L threshold, not maximum loss. Fees, taxes, slippage and margin are excluded. Short options can lose unlimited amounts. Max pain and OI walls are positioning references, not forecasts. Nothing here places orders.",
                fontSize = 13.sp
            )
        },
        confirmButton = { TextButton(onClick = { method = false }) { Text("Close") } })
    detail?.let { strike ->
        if (chain != null) AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("Strike ${num(strike, 0)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(true, false).forEach { call ->
                        val leg = chain.leg(strike, call); Text(
                        if (call) "CALL" else "PUT",
                        color = if (call) Calls else Puts
                    )
                        if (leg == null) Text("Contract unavailable") else Text(
                            "${leg.symbol}\nBid ${
                                num(
                                    leg.bid
                                )
                            } / Ask ${num(leg.ask)}\nEntry ${num(leg.price)} (${if (leg.midpoint) "midpoint" else "last trade"})\nIV ${
                                pct(
                                    chain.iv(leg)
                                )
                            } · Delta ${num(chain.delta(leg))}\nOI ${compact(leg.oi?.toDouble())} · ΔOI ${
                                leg.changeOi?.let {
                                    compact(
                                        it.toDouble()
                                    )
                                } ?: leg.historyStatus
                            }\nVolume ${compact(leg.volume?.toDouble())}\nQuote ${leg.quoteTime ?: "time unavailable"}",
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } })
    }
}

private fun LazyListScope.spacedItem(content: @Composable () -> Unit) {
    item { Column(Modifier
        .fillMaxWidth()
        .padding(bottom = 20.dp)) { content() } }
}

private fun quoteStamp(raw: String?): String {
    if (raw == null) return "Broker time unavailable"
    val time = runCatching { Instant.parse(raw).atZone(OPTIONS_ZONE) }.getOrNull()
        ?: runCatching {
            OffsetDateTime.parse(
                raw,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
            ).atZoneSameInstant(OPTIONS_ZONE)
        }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(raw.replace(' ', 'T')).atZone(OPTIONS_ZONE)
        }.getOrNull()
    return time?.format(DateTimeFormatter.ofPattern("dd MMM, h:mm a 'IST'", Locale.ENGLISH))
        ?: raw.take(40)
}

@Composable
private fun QuoteSummary(
    c: ChainSnapshot,
    strategy: Boolean,
    auto: Boolean,
    onAuto: (Boolean) -> Unit
) {
    val now = ZonedDateTime.now(OPTIONS_ZONE)
    // Time-based indication only; do not claim knowledge of exchange holidays.
    val sessionHours = now.dayOfWeek !in listOf(
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY
    ) && now.toLocalTime() >= LocalTime.of(9, 15) && now.toLocalTime() < LocalTime.of(15, 30)
    Column(
        Modifier
            .fillMaxWidth()
            .borderBottom()
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(INDEX_NAMES[c.underlying] ?: c.underlying, color = Muted, fontSize = 10.sp)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(num(c.spot), fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${signed(c.change)} (${signed(c.changePercent?.times(100))}%)",
                color = tone(c.change),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Text(
            "Expiry ${c.expiry.format(shortDate)}  /  ${
                num(
                    c.years * 365,
                    1
                )
            } days  /  Lot ${c.legs.firstOrNull()?.lotSize ?: "—"}  /  ${
                if (strategy) "ATM IV ${pct(c.atmIv)}" else "Forward ${
                    num(
                        c.forward
                    )
                }"
            }", color = Muted, fontSize = 11.sp, lineHeight = 17.sp
        )
        Text(
            "${if (c.demo) "Synthetic snapshot" else if (sessionHours) "● Session hours · check quote time" else "● Market closed"}  ·  ${
                quoteStamp(
                    c.quoteTime
                )
            }", color = Muted, fontSize = 10.sp
        )
        if (!strategy) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                auto,
                onAuto,
                modifier = Modifier.size(28.dp)
            ); Text("Auto-refresh every 30 s", color = Muted, fontSize = 10.sp)
        }
    }
}

private data class Tile(
    val label: String,
    val value: String,
    val detail: String = "",
    val color: Color = Foreground
)

@Composable
private fun TileGrid(tiles: List<Tile>) {
    BoxWithConstraints(Modifier
        .fillMaxWidth()
        .border(1.dp, Rule)) {
        val columns = if (maxWidth >= 650.dp) 4 else 2
        Column {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)) {
                    row.forEach { tile ->
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(.5.dp, Rule)
                                .padding(13.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(tile.label, fontSize = 10.sp, color = Muted, lineHeight = 14.sp)
                            Text(
                                tile.value,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = tile.color,
                                lineHeight = 24.sp
                            )
                            if (tile.detail.isNotEmpty()) Text(
                                tile.detail,
                                fontSize = 10.sp,
                                color = Muted,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ChainMetrics(c: ChainSnapshot) {
    val support = c.wall(false);
    val resistance = c.wall(true)
    TileGrid(
        listOf(
            Tile(
                "Expected move (straddle)",
                c.straddle?.let { "±${num(it)}" } ?: "—",
                c.straddle?.let {
                    "${pct(it / c.forward)} · ${
                        num(
                            c.forward - it,
                            0
                        )
                    } – ${num(c.forward + it, 0)}"
                } ?: "Entry prices unavailable"
            ),
            Tile("ATM IV", pct(c.atmIv), "20-day realised ${pct(c.realisedVol)}"),
            Tile("PCR (open interest)", num(c.pcr), "Volume PCR ${num(c.volumePcr)}"),
            Tile(
                "Max pain",
                num(c.maxPain, 0),
                c.maxPain?.let { "${signed(it - c.spot, 0)} from spot" } ?: "OI unavailable"
            ),
            Tile(
                "Put OI wall (below spot)",
                num(support, 0),
                "${compact(support?.let { c.leg(it, false)?.oi?.toDouble() })} OI"
            ),
            Tile(
                "Call OI wall (above spot)",
                num(resistance, 0),
                "${compact(resistance?.let { c.leg(it, true)?.oi?.toDouble() })} OI"
            ),
            Tile(
                "1σ move from IV",
                c.atmIv?.let { "±${num(c.forward * it * sqrt(c.years), 0)}" } ?: "—",
                "25Δ skew ${signed(c.skew)} vol pts"
            ),
            Tile("India VIX", num(c.vix), "30-day Nifty implied volatility")
        )
    )
}

@Composable
private fun StrategyControls(s: MarketOptionsState, c: ChainSnapshot, vm: MarketOptionsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Strategy"); Segments(
                if (s.strangle) "strangle" else "straddle",
                listOf("straddle" to "Straddle", "strangle" to "Strangle"),
                { vm.setStrangle(it == "strangle") },
                Modifier.fillMaxWidth()
            )
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("Position"); Segments(
                if (s.buy) "buy" else "sell",
                listOf("sell" to "Sell", "buy" to "Buy"),
                { v -> vm.edit { it.copy(buy = v == "buy") } },
                Modifier.fillMaxWidth()
            )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel(if (s.strangle) "Call strike" else "Strike")
                Selector(
                    if (s.strangle) "Call strike" else "Strike",
                    num(s.callStrike, 0) + (if (s.callStrike == c.atm) " (ATM)" else ""),
                    Modifier.fillMaxWidth()
                ) {
                    c.strikes.filter { k ->
                        c.leg(
                            k,
                            true
                        )?.price != null && (!s.strangle || k > c.spot)
                    }.forEach { k ->
                        SelectItem(
                            num(k, 0),
                            k == s.callStrike
                        ) {
                            vm.edit {
                                it.copy(
                                    callStrike = k,
                                    putStrike = if (it.strangle) it.putStrike else k
                                )
                            }
                        }
                    }
                }
            }
            if (s.strangle) Column(Modifier.weight(1f)) {
                FieldLabel("Put strike"); Selector(
                "Put strike",
                num(s.putStrike, 0),
                Modifier.fillMaxWidth()
            ) {
                c.strikes.filter { c.leg(it, false)?.price != null && it < c.spot }.forEach { k ->
                    SelectItem(num(k, 0), k == s.putStrike) {
                        vm.edit {
                            it.copy(putStrike = k)
                        }
                    }
                }
            }
            }
            Column(Modifier.weight(.7f)) {
                FieldLabel("Lots"); OutlinedTextField(
                s.lots,
                { v -> vm.edit { it.copy(lots = v.take(5)) } },
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("strategy-lots"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            ); Text(
                "${
                    s.lots.toLongOrNull()?.times(c.legs.firstOrNull()?.lotSize ?: 0) ?: "—"
                } units", color = Muted, fontSize = 10.sp
            )
            }
        }
        val dates = remember(c.asOf, c.expiry) { targetDates(c) }
        val selected = dates.indexOfFirst { it.toString() == s.targetDate }
            .let { if (it < 0) dates.lastIndex else it }.coerceAtLeast(0)
        Text(
            "Value the position on  ${
                dates.getOrNull(selected)?.let {
                    if (it == c.expiry) "expiry (${it.format(shortDate)})" else "${
                        it.format(shortDate)
                    } close"
                } ?: "—"
            }", fontSize = 12.sp
        )
        WebSlider(
            selected.toFloat(),
            { v ->
                dates.getOrNull(v.roundToInt())
                    ?.let { date -> vm.edit { it.copy(targetDate = date.toString()) } }
            },
            valueRange = 0f..dates.lastIndex.coerceAtLeast(1).toFloat(),
            steps = (dates.size - 2).coerceAtLeast(0),
            enabled = dates.size > 1,
            modifier = Modifier
                .testTag("target-date-slider")
                .semantics { contentDescription = "Target date" })
        Text(
            if (s.targetDate == c.expiry.toString()) "At expiry, options are worth their intrinsic value." else "Reprices the remaining time value at 15:30 IST.",
            color = Muted,
            fontSize = 10.sp
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Change in IV by then",
                fontSize = 12.sp
            ); Text(
            if (s.ivShift == 0f) "None" else "${signed(s.ivShift.toDouble(), 1)} pts",
            fontSize = 12.sp,
            color = Mint
        )
        }
        WebSlider(
            s.ivShift,
            { v -> vm.edit { it.copy(ivShift = v) } },
            valueRange = -10f..10f,
            steps = 39,
            modifier = Modifier
                .testTag("iv-shift-slider")
                .semantics { contentDescription = "Change in IV" })
        Text(
            if (s.targetDate == c.expiry.toString()) "No effect at expiry." else "Chances stay on today's ATM IV.",
            color = Muted,
            fontSize = 10.sp
        )
        Column(
            Modifier
                .fillMaxWidth()
                .borderBottom()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                c.leg(s.callStrike ?: c.atm, true),
                c.leg((if (s.strangle) s.putStrike else s.callStrike) ?: c.atm, false)
            ).forEach { leg ->
                if (leg != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (s.buy) "Buy" else "Sell",
                            color = if (s.buy) Mint else Coral,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .background(Raised, RoundedCornerShape(3.dp))
                                .padding(4.dp)
                        )
                        Text(
                            "${s.lots} × ${leg.symbol}\nat ${num(leg.price)} · IV ${pct(c.iv(leg))} · ${if (leg.midpoint) "mid" else "last"}",
                            fontSize = 11.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyMetrics(s: MarketOptionsState, c: ChainSnapshot, r: SimulationResult) {
    val premium = r.premium / r.quantity
    TileGrid(
        listOf(
            Tile(
                if (s.buy) "Premium paid" else "Premium received",
                "₹${num(r.premium, 0)}",
                "${num(premium)} pts × ${r.quantity}"
            ),
            Tile(
                "Max profit at expiry",
                r.maxProfit?.let(::cash) ?: "Unlimited",
                if (s.buy) "Grows with an upward move" else "At the selected strike(s)",
                Mint
            ),
            Tile(
                "Max loss at expiry",
                r.maxLoss?.let { "₹${num(it, 0)}" } ?: "Unlimited",
                if (s.buy) "Premium paid" else "Grows with the size of the move",
                Coral
            ),
            Tile(
                "Breakevens at expiry",
                r.expiryBreakevens.joinToString(" / ") { num(it, 0) }.ifEmpty { "None" },
                "From the entry premiums"
            ),
            Tile(
                "Chance of profit · ${s.targetDate}",
                pct(r.profitChance, 0),
                "Breakevens then ${
                    r.breakevens.joinToString(" / ") { num(it, 0) }.ifEmpty { "None" }
                }"
            ),
            Tile(
                "If spot is unchanged then",
                cash(r.unchangedPnl),
                "Index still at ${num(c.spot, 0)}",
                tone(r.unchangedPnl)
            ),
            Tile(
                "1σ range then",
                "${num(r.rangeLow, 0)} – ${num(r.rangeHigh, 0)}",
                "About 68% model probability"
            ),
            Tile(
                "Worst 5% of outcomes then",
                cash(r.worstFive),
                "1 in 20 model chance of worse",
                tone(r.worstFive)
            )
        )
    )
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 7.dp))
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier
            .size(7.dp)
            .background(color, RoundedCornerShape(1.dp))); Text(
        text,
        fontSize = 10.sp,
        color = Muted
    )
    }
}

@Composable
private fun Segments(
    value: String,
    options: List<Pair<String, String>>,
    select: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .selectableGroup()
            .background(Panel, RoundedCornerShape(5.dp))
            .border(1.dp, Rule, RoundedCornerShape(5.dp))
            .padding(3.dp)
    ) {
        options.forEach { (id, label) ->
            Box(
                Modifier
                    .weight(1f, false)
                    .background(
                        if (value == id) Raised else Color.Transparent,
                        RoundedCornerShape(3.dp)
                    )
                    .selectable(
                        selected = value == id,
                        role = Role.RadioButton,
                        onClick = { select(id) })
                    .padding(horizontal = 8.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = if (value == id) Foreground else Muted,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true
) {
    Slider(
        value, onValueChange, modifier, enabled, valueRange = valueRange, steps = steps,
        thumb = { Box(Modifier
            .size(12.dp)
            .background(if (enabled) Mint else Muted, CircleShape)) },
        track = { slider ->
            Box(Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Rule, CircleShape)) {
                val fraction =
                    ((slider.value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(
                        0f,
                        1f
                    )
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(if (enabled) Mint else Muted, CircleShape)
                )
            }
        })
}

private class MenuItems(val dismiss: () -> Unit) {
    @Composable
    fun SelectItem(label: String, selected: Boolean, click: () -> Unit) {
        DropdownMenuItem(text = {
            Text(
                label,
                color = if (selected) Mint else Foreground,
                fontSize = 12.sp
            )
        }, onClick = { dismiss(); click() })
    }
}

@Composable
private fun Selector(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    items: @Composable MenuItems.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(5.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .semantics { contentDescription = label }) {
            Text(
                value,
                fontSize = 11.sp,
                maxLines = 1
            ); Spacer(Modifier.weight(1f)); Text("⌄")
        }
        DropdownMenu(
            expanded,
            { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp)
        ) { MenuItems { expanded = false }.items() }
    }
}

private val columnWidth = 60.dp
private val chainColumns = listOf("OI", "ΔOI", "Volume", "Delta", "IV", "LTP")

@Composable
private fun ChainHeader(scroll: ScrollState) {
    Column(Modifier.background(Panel)) {
        Row(Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)) {
            Text(
                "Calls",
                Modifier.weight(1f),
                color = Calls,
                fontSize = 11.sp
            ); Text("Strike", Modifier.width(66.dp), fontSize = 11.sp); Text(
            "Puts",
            Modifier.weight(1f),
            color = Puts,
            fontSize = 11.sp
        )
        }
        Row {
            ChainSide(true, scroll, Modifier.weight(1f)) { label ->
                Text(
                    label,
                    color = Muted,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .width(columnWidth)
                        .padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }; Spacer(Modifier.width(66.dp)); ChainSide(
            false,
            scroll,
            Modifier.weight(1f)
        ) { label ->
            Text(
                label,
                color = Muted,
                fontSize = 9.sp,
                modifier = Modifier
                    .width(columnWidth)
                    .padding(vertical = 10.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        }
    }
}

@Composable
private fun ChainSide(
    call: Boolean,
    scroll: ScrollState,
    modifier: Modifier,
    cell: @Composable (String) -> Unit
) {
    Row(
        modifier.horizontalScroll(
            scroll,
            reverseScrolling = call
        )
    ) { (if (call) chainColumns else chainColumns.reversed()).forEach { cell(it) } }
}

@Composable
private fun ChainRow(c: ChainSnapshot, strike: Double, scroll: ScrollState, clicked: () -> Unit) {
    Column {
        val step = c.strikes.zipWithNext { a, b -> b - a }.minOrNull() ?: 50.0
        if (c.spot >= strike && c.spot < strike + step) Text(
            "──  Spot ${num(c.spot)}  ──",
            color = Muted,
            fontSize = 9.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = clicked)
                .borderBottom(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(true, false).forEach { call ->
                if (!call) Column(
                    Modifier
                        .width(66.dp)
                        .background(if (strike == c.atm) Color(0xFF263D32) else Raised)
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        num(strike, 0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ); if (strike == c.atm) Text("ATM", color = Mint, fontSize = 8.sp)
                }
                val leg = c.leg(strike, call)
                ChainSide(
                    call,
                    scroll,
                    Modifier
                        .weight(1f)
                        .background(
                            if (if (call) strike < c.spot else strike > c.spot) Color(0xFF1D2220) else Color.Transparent
                        )
                ) { label ->
                    val value = when (label) {
                        "LTP" -> num(leg?.last); "IV" -> num(
                            leg?.let(c::iv)?.times(100)
                        ); "Delta" -> num(leg?.let(c::delta)); "Volume" -> compact(leg?.volume?.toDouble()); "ΔOI" -> compact(
                            leg?.changeOi?.toDouble()
                        ); else -> compact(leg?.oi?.toDouble())
                    }
                    Column(
                        Modifier
                            .width(columnWidth)
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            value,
                            fontSize = 10.sp,
                            color = if (label == "ΔOI") tone(leg?.changeOi?.toDouble()) else Foreground
                        )
                        if (label == "LTP") Text(
                            leg?.previousClose?.takeIf { it > 0 }
                                ?.let { signed(leg.last?.let { p -> (p / it - 1) * 100 }, 1) + "%" }
                                ?: "—",
                            fontSize = 8.sp,
                            color = tone(leg?.last?.let { p -> leg.previousClose?.let { p - it } })
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeHeader() {
    Row(Modifier
        .fillMaxWidth()
        .background(Panel)
        .padding(vertical = 10.dp)) {
        Text(
            "Index / move",
            Modifier.weight(1.05f),
            fontSize = 10.sp,
            color = Muted
        ); Text("Chance", Modifier.weight(.65f), fontSize = 10.sp, color = Muted); Text(
        "P&L range",
        Modifier.weight(1f),
        fontSize = 10.sp,
        color = Muted,
        textAlign = androidx.compose.ui.text.style.TextAlign.End
    )
    }
}

@Composable
private fun RangeRow(c: ChainSnapshot, b: ProbabilityBand, peak: Double) {
    val spot = (b.low == null || c.spot >= b.low) && (b.high == null || c.spot < b.high)
    fun move(v: Double) = signed((v / c.spot - 1) * 100, 1) + "%"
    val label = when {
        b.low == null -> "Below ${num(b.high, 0)}"; b.high == null -> "Above ${
            num(
                b.low,
                0
            )
        }"; else -> "${num(b.low, 0)}–${num(b.high, 0)}"
    }
    val moves = when {
        b.low == null -> "< ${move(b.high!!)}"; b.high == null -> "> ${move(b.low)}"; else -> "${
            move(
                b.low
            )
        } to ${move(b.high)}"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (spot) Raised else Color.Transparent)
            .borderBottom()
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1.05f)) {
            Text(
                label,
                fontSize = 11.sp
            ); Text(
            moves + (if (spot) " · Spot" else ""),
            fontSize = 9.sp,
            color = if (spot) Mint else Muted
        )
        }
        Column(Modifier
            .weight(.65f)
            .padding(horizontal = 5.dp)) {
            Text(
                pct(b.chance),
                fontSize = 11.sp
            ); Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Rule)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(
                        (b.chance / peak.coerceAtLeast(.0001)).toFloat().coerceIn(0f, 1f)
                    )
                    .height(3.dp)
                    .background(Mint)
            )
        }
        }
        Text(
            "${cash(b.pnlLow)}\nto ${cash(b.pnlHigh)}",
            Modifier.weight(1f),
            fontSize = 10.sp,
            color = if (b.pnlLow >= 0) Mint else if (b.pnlHigh <= 0) Coral else Foreground,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

private fun Modifier.chartTouch(key: Any, width: () -> Float, pick: (Float) -> Unit): Modifier =
    pointerInput(key) { detectTapGestures { pick(it.x / width().coerceAtLeast(1f)) } }.pointerInput(
        key
    ) {
        detectDragGestures(onDragStart = { pick(it.x / width().coerceAtLeast(1f)) }) { change, _ ->
            change.consume(); pick(
            change.position.x / width().coerceAtLeast(1f)
        )
        }
    }

private fun chartPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.rgb(147, 158, 153); textSize = size
}

@Composable
private fun OiChart(c: ChainSnapshot, strikes: List<Double>, change: Boolean) {
    var selection by remember(c.underlying, c.expiry, change) { mutableStateOf<Int?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    val values = strikes.map { k ->
        listOf(true, false).map { call ->
            c.leg(k, call)?.let { if (change) it.changeOi?.toDouble() else it.oi?.toDouble() }
        }
    }
    val high = values.flatten().filterNotNull().maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val low = min(0.0, values.flatten().filterNotNull().minOrNull() ?: 0.0)
    val index = selection?.coerceIn(0, strikes.lastIndex)
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            index?.let {
                "${
                    num(
                        strikes[it],
                        0
                    )
                } · Calls ${compact(values[it][0])} · Puts ${compact(values[it][1])}"
            } ?: "Tap or drag a bar to inspect",
            fontSize = 10.sp,
            color = Muted,
            modifier = Modifier.height(28.dp)
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .testTag("oi-chart")
                .semantics {
                    contentDescription =
                        "Call and put ${if (change) "change in OI" else "open interest"} by strike"
                }
                .chartTouch(strikes to change, { width }) { fraction ->
                    selection = (((fraction - .14f) / .86f) * strikes.size).toInt()
                        .coerceIn(0, strikes.lastIndex)
                }) {
            width = size.width
            val left = 46.dp.toPx();
            val top = 12.dp.toPx();
            val bottom = size.height - 26.dp.toPx();
            val h = bottom - top
            fun y(v: Double) = top + ((high - v) / (high - low) * h).toFloat()
            val paint = chartPaint(9.sp.toPx())
            repeat(4) { i ->
                val v = low + (high - low) * i / 3;
                val yy = y(v); drawLine(
                Rule,
                Offset(left, yy),
                Offset(size.width, yy)
            ); drawContext.canvas.nativeCanvas.drawText(compact(v), 0f, yy, paint)
            }
            val step = (size.width - left) / strikes.size
            values.forEachIndexed { i, pair ->
                pair.forEachIndexed { side, v ->
                    if (v != null) {
                        val x = left + i * step + step * (if (side == 0) .15f else .52f); drawRect(
                            if (side == 0) Calls else Puts,
                            Offset(x, min(y(v), y(0.0))),
                            androidx.compose.ui.geometry.Size(
                                step * .3f,
                                abs(y(v) - y(0.0)).coerceAtLeast(1f)
                            )
                        )
                    }
                }
            }
            listOf(0, strikes.size / 2, strikes.lastIndex).distinct().forEach { i ->
                paint.textAlign = Paint.Align.CENTER; drawContext.canvas.nativeCanvas.drawText(
                num(
                    strikes[i],
                    0
                ), left + (i + .5f) * step, size.height - 5.dp.toPx(), paint
            )
            }
            index?.let {
                val x = left + (it + .5f) * step; drawLine(
                Foreground.copy(alpha = .4f),
                Offset(x, top),
                Offset(x, bottom)
            )
            }
        }
    }
}

@Composable
private fun PnlChart(c: ChainSnapshot, r: SimulationResult, expiry: Boolean) {
    var selected by remember(r) { mutableStateOf<Int?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    val points = r.points
    val low = points.minOf { minOf(it.targetPnl, it.expiryPnl, 0.0) };
    val high = points.maxOf { maxOf(it.targetPnl, it.expiryPnl, 0.0) }
    val first = points.first().level;
    val last = points.last().level
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("Profit and loss by index level")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(
                "Target date",
                Mint
            ); if (!expiry) Legend("At expiry · dashed", Muted)
        }
        Text("Shaded bands: ±1σ and ±2σ · Spot ${num(c.spot, 0)}", fontSize = 10.sp, color = Muted)
        selected?.let { i ->
            val p = points[i]; Text(
            "${
                num(
                    p.level,
                    0
                )
            }  ·  ${cash(p.targetPnl)}${if (!expiry) " / expiry ${cash(p.expiryPnl)}" else ""}\nModel chance above ${
                pct(
                    p.chanceAbove
                )
            }", color = tone(p.targetPnl), fontSize = 11.sp
        )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .testTag("simulation-chart")
                .semantics { contentDescription = "Target date and expiry profit and loss chart" }
                .chartTouch(r, { width }) { fraction ->
                    val v = first + ((fraction - .15f) / .83f).coerceIn(
                        0f,
                        1f
                    ) * (last - first); selected =
                    points.indices.minByOrNull { abs(points[it].level - v) }
                }) {
            width = size.width
            val left = 48.dp.toPx();
            val right = size.width - 8.dp.toPx();
            val top = 20.dp.toPx();
            val bottom = size.height - 28.dp.toPx()
            fun x(v: Double) = left + ((v - first) / (last - first) * (right - left)).toFloat()
            fun y(v: Double) =
                top + ((high - v) / (high - low).coerceAtLeast(1.0) * (bottom - top)).toFloat()

            val paint = chartPaint(9.sp.toPx())
            clipRect(left, top, right, bottom) {
                listOf(
                    Triple(r.rangeTwoLow, r.rangeTwoHigh, .025f),
                    Triple(r.rangeLow, r.rangeHigh, .045f)
                ).forEach { (a, b, alpha) ->
                    drawRect(
                        Foreground.copy(alpha = alpha),
                        Offset(x(a), top),
                        androidx.compose.ui.geometry.Size(x(b) - x(a), bottom - top)
                    )
                }
            }
            repeat(5) { i ->
                val v = low + (high - low) * i / 4;
                val yy = y(v); drawLine(
                Rule,
                Offset(left, yy),
                Offset(right, yy)
            ); drawContext.canvas.nativeCanvas.drawText(compact(v), 0f, yy, paint)
            }
            val path = Path(); points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(
                x(p.level),
                y(p.targetPnl)
            ) else path.lineTo(x(p.level), y(p.targetPnl))
        }
            val area = Path().apply {
                addPath(path); lineTo(x(last), y(0.0)); lineTo(
                x(first),
                y(0.0)
            ); close()
            }
            clipRect(left, top, right, y(0.0).coerceIn(top, bottom)) {
                drawPath(
                    area,
                    Mint.copy(alpha = .10f)
                )
            }
            clipRect(left, y(0.0).coerceIn(top, bottom), right, bottom) {
                drawPath(
                    area,
                    Coral.copy(alpha = .10f)
                )
            }
            if (!expiry) {
                val expiryPath = Path(); points.forEachIndexed { i, p ->
                    if (i == 0) expiryPath.moveTo(
                        x(p.level),
                        y(p.expiryPnl)
                    ) else expiryPath.lineTo(x(p.level), y(p.expiryPnl))
                }; drawPath(
                    expiryPath,
                    Muted,
                    style = Stroke(
                        1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f))
                    )
                )
            }
            drawLine(Muted, Offset(left, y(0.0)), Offset(right, y(0.0)))
            drawPath(path, Mint, style = Stroke(2.dp.toPx()))
            drawLine(Muted.copy(alpha = .6f), Offset(x(c.spot), top), Offset(x(c.spot), bottom))
            repeat(3) { i ->
                val v = first + (last - first) * i / 2; paint.textAlign =
                Paint.Align.CENTER; drawContext.canvas.nativeCanvas.drawText(
                num(v, 0),
                x(v).coerceIn(left + 12, right - 12),
                size.height - 7.dp.toPx(),
                paint
            )
            }
            selected?.let { i ->
                val p = points[i]; drawLine(
                Foreground.copy(alpha = .5f),
                Offset(x(p.level), top),
                Offset(x(p.level), bottom)
            ); drawCircle(Mint, 4.dp.toPx(), Offset(x(p.level), y(p.targetPnl)))
            }
        }
        Text(
            "Tap or drag to inspect · The plotted range is finite; losses can extend beyond it.",
            color = Muted,
            fontSize = 10.sp
        )
    }
}
