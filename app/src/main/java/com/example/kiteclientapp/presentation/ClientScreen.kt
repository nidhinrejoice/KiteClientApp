package com.example.kiteclientapp.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kiteclientapp.domain.*
import com.example.kiteclientapp.ui.theme.WorkspaceTheme
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF142F3C)
internal fun money(value: BigDecimal): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).format(value)

private fun percent(profit: BigDecimal, invested: BigDecimal): String =
    if (invested.signum() == 0) "0.00%" else profit.multiply(100.toBigDecimal())
        .divide(invested, 2, RoundingMode.HALF_UP).toPlainString() + "%"

private fun holdingPercent(value: BigDecimal?): String =
    value?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()?.plus("%") ?: "—"

private fun signed(value: BigDecimal) = (if (value.signum() > 0) "+" else "") + money(value)

@Composable
fun ClientScreen(state: ClientState, onIntent: (ClientIntent) -> Unit) =
    ClientScreen(state, onIntent, null)

@Composable
fun ClientScreen(
    state: ClientState,
    onIntent: (ClientIntent) -> Unit,
    optionsContent: (@Composable () -> Unit)?
) = ClientScreen(state, onIntent, optionsContent, null)

@Composable
fun ClientScreen(
    state: ClientState,
    onIntent: (ClientIntent) -> Unit,
    optionsContent: (@Composable () -> Unit)?,
    scannersContent: (@Composable () -> Unit)?
) {
    if (state.account != null) WorkspaceTheme {
        ClientContent(
            state,
            onIntent,
            optionsContent,
            scannersContent
        )
    }
    else ClientContent(state, onIntent, optionsContent, scannersContent)
}

@Composable
private fun ClientContent(
    state: ClientState,
    onIntent: (ClientIntent) -> Unit,
    optionsContent: (@Composable () -> Unit)?,
    scannersContent: (@Composable () -> Unit)?
) {
    var feature by rememberSaveable { mutableStateOf("Portfolio") }
    LaunchedEffect(state.account) { if (state.account == null) feature = "Portfolio" }
    val workspaceActive = state.account != null
    val view = LocalView.current
    DisposableEffect(workspaceActive, view) {
        val activity = view.context as? android.app.Activity
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        if (workspaceActive) {
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
        }
        onDispose {
            if (workspaceActive) {
                previousStatus?.let { controller?.isAppearanceLightStatusBars = it }
                previousNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
            }
        }
    }
    Surface(
        Modifier.fillMaxSize(),
        color = if (workspaceActive) Color(0xFF111313) else MaterialTheme.colorScheme.background
    ) {
        Box(Modifier
            .fillMaxSize()
            .safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            if (state.restoringSession) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Restoring your session…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (state.account == null) LoginScreen(state, onIntent)
            else if (optionsContent == null) PortfolioScreen(state, onIntent)
            else Scaffold(bottomBar = {
                NavigationBar(containerColor = if (workspaceActive) Color(0xFF181B1B) else MaterialTheme.colorScheme.surface) {
                    (listOf(
                        "Portfolio",
                        "Options"
                    ) + if (scannersContent != null) listOf("Scanners") else emptyList()).forEach { destination ->
                        NavigationBarItem(
                            selected = feature == destination,
                            onClick = { feature = destination },
                            icon = {
                                Text(
                                    when (destination) {
                                        "Portfolio" -> "▤"; "Options" -> "ƒ"; else -> "⌕"
                                    }
                                )
                            },
                            label = { Text(destination) },
                            colors = if (workspaceActive) NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF76D9B1),
                                selectedTextColor = Color(0xFF76D9B1),
                                unselectedIconColor = Color(0xFF939E99),
                                unselectedTextColor = Color(0xFF939E99),
                                indicatorColor = Color(0xFF202424),
                            ) else NavigationBarItemDefaults.colors()
                        )
                    }
                }
            }) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    when (feature) {
                        "Options" -> optionsContent(); "Scanners" -> scannersContent?.invoke(); else -> PortfolioScreen(
                        state,
                        onIntent
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun Brand() {
    val brandColor = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(Modifier.size(30.dp)) {
            drawPath(Path().apply {
                moveTo(size.width * .5f, 0f); lineTo(
                size.width,
                size.height * .42f
            ); lineTo(size.width * .5f, size.height); lineTo(0f, size.height * .42f); close()
            }, brandColor)
            drawLine(
                Color.White.copy(alpha = .7f),
                androidx.compose.ui.geometry.Offset(size.width * .5f, 4f),
                androidx.compose.ui.geometry.Offset(size.width * .5f, size.height - 4f),
                2f
            )
        }
        Text(
            "kite client",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-.8).sp
        )
    }
}

@Composable
private fun LoginScreen(state: ClientState, onIntent: (ClientIntent) -> Unit) {
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Brand()
        Text(
            "YOUR PORTFOLIO, IN FOCUS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp
        )
        Text(
            "A clearer view of\nyour investments.",
            fontSize = 38.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1.5).sp
        )
        Text(
            "Connect your Zerodha account and keep your holdings, positions, and returns in one place.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 25.sp
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = Ink),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    "BUILT FOR YOUR NEXT MOVE",
                    color = Color(0xFF83C9BD),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp
                )
                Text(
                    "See the whole picture.",
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FeatureLine("01", "Holdings & returns", "Understand where your investments stand.")
                FeatureLine("02", "Positions at a glance", "Keep track of your current exposure.")
                HorizontalDivider(color = Color.White.copy(alpha = .15f))
                Text(
                    "Options strategy analysis  ·  Available after sign-in",
                    color = Color(0xFFADC2CE),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        state.error?.takeIf { it.isNotEmpty() }?.let { ErrorCard(it) }
        if (state.awaitingLogin) {
            Text(
                "Complete sign-in with Kite to continue.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(
                onClick = { onIntent(ClientIntent.CancelLogin) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy
            ) { Text("Cancel sign-in") }
        } else {
            Button(
                onClick = { onIntent(ClientIntent.Login) },
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (state.busy) CircularProgressIndicator(
                    Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                else Text("Connect with Zerodha  →", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { onIntent(ClientIntent.Demo) },
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Explore a demo portfolio") }
        }
        Text(
            "Sign in securely through Kite. This app never asks for your Zerodha password. Independent client; not affiliated with Zerodha.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun FeatureLine(number: String, title: String, subtitle: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(number, color = Color(0xFF83C9BD), style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFFADC2CE), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PortfolioScreen(state: ClientState, onIntent: (ClientIntent) -> Unit) {
    var detail by remember { mutableStateOf<Holding?>(null) }
    var showLogout by rememberSaveable { mutableStateOf(false) }
    val account = checkNotNull(state.account)
    val portfolio = state.portfolio
    val holdings = remember(portfolio, state.query, state.holdingsSort) {
        sortHoldings(
            portfolio?.holdings.orEmpty().filter {
                it.symbol.contains(state.query, true) || it.exchange.contains(
                    state.query,
                    true
                )
            }, state.holdingsSort
        )
    }
    val positions = remember(portfolio, state.query, state.positionsSort) {
        sortPositions(
            portfolio?.positions.orEmpty().filter {
                it.symbol.contains(state.query, true) || it.exchange.contains(
                    state.query,
                    true
                )
            }, state.positionsSort
        )
    }
    LazyColumn(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxSize()
            .testTag("portfolio-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Brand()
                TextButton(
                    onClick = { showLogout = true },
                    enabled = !state.busy
                ) { Text("Sign out") }
            }
        }
        if (account.isDemo) item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    "DEMO MODE  ·  Sample data, not your live portfolio",
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${account.name} · ${account.id}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Your portfolio",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                )
                Text(
                    "A little perspective. A better next move.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { message ->
            item {
                ErrorCard(
                    message,
                    if (!state.busy) ({ onIntent(ClientIntent.Refresh) }) else null
                )
            }
        }
        if (portfolio != null) {
            item { PortfolioSummary(portfolio) }
            if (portfolio.holdings.isNotEmpty()) item { PledgedSummary(portfolio) }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Portfolio snapshot", style = MaterialTheme.typography.titleSmall)
                    Text(if (account.isDemo) "Illustrative prices" else state.updatedAt?.let {
                        "Updated ${
                            SimpleDateFormat(
                                "h:mm a",
                                Locale.getDefault()
                            ).format(Date(it))
                        }"
                    } ?: "Waiting for data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    onClick = { onIntent(ClientIntent.Refresh) },
                    enabled = !state.busy
                ) { Text("Refresh") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PortfolioTab.entries.forEach { tab ->
                    FilterChip(
                        selected = state.tab == tab,
                        onClick = { onIntent(ClientIntent.SelectTab(tab)) },
                        label = {
                            Text("${tab.name} (${if (tab == PortfolioTab.Holdings) portfolio?.holdings?.size ?: 0 else portfolio?.positions?.size ?: 0})")
                        })
                }
            }
        }
        if (state.tab == PortfolioTab.Positions && portfolio != null) item {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Net positions P&L")
                    ProfitText(portfolio.positionProfit)
                }
            }
        }
        item { SortControls(state.tab, state.sortOrder(), onIntent) }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onIntent(ClientIntent.Search(it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search ${state.tab.name.lowercase()}") },
                shape = RoundedCornerShape(6.dp)
            )
        }
        if (portfolio != null) {
            if (state.tab == PortfolioTab.Holdings) {
                if (holdings.isEmpty()) item {
                    EmptyPortfolio(
                        state.query.isNotBlank(),
                        "holdings"
                    )
                }
                items(holdings, key = { "${it.exchange}:${it.symbol}" }) { holding ->
                    HoldingRow(
                        holding
                    ) { detail = holding }
                }
            } else {
                if (positions.isEmpty()) item {
                    EmptyPortfolio(
                        state.query.isNotBlank(),
                        "positions"
                    )
                }
                items(
                    positions,
                    key = { "${it.exchange}:${it.symbol}:${it.product}" }) { position ->
                    PositionRow(
                        position
                    )
                }
            }
        }
        item {
            HorizontalDivider()
            Text(
                "MORE ROOM TO GROW",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.4.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("Market scanners", fontWeight = FontWeight.Medium)
            Text(
                "Coming in a future update",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    detail?.let { holding ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(holding.symbol) }, text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("${holding.exchange} · ${holding.quantity} shares")
                DetailLine("Non-pledged quantity", holding.nonPledgedQuantity.toString())
                DetailLine(
                    "Pledged quantity",
                    holding.pledgedQuantity?.toString() ?: "Not reported"
                )
                holding.collateralType?.let { DetailLine("Pledge type", it) }
                holding.pledgedMarketValue?.takeIf { (holding.pledgedQuantity ?: 0) > 0 }
                    ?.let { DetailLine("Pledged market value", money(it)) }
                DetailLine("Average price", money(holding.averagePrice))
                DetailLine("Last price", money(holding.lastPrice))
                DetailLine("Invested", money(holding.invested))
                DetailLine("Current value", money(holding.current))
                DetailLine("Total return", signed(holding.profit))
                DetailLine("Profit percentage", holdingPercent(holding.profitPercentage))
                DetailLine("Day change", signed(holding.dayChange))
                DetailLine("Daily percentage change", holdingPercent(holding.dayChangePercentage))
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Done") } })
    }
    if (showLogout) AlertDialog(
        onDismissRequest = { showLogout = false },
        title = { Text("Sign out of Kite Client?") },
        text = { Text("You’ll need to sign in again to view your portfolio.") },
        confirmButton = {
            TextButton(onClick = {
                showLogout = false; onIntent(ClientIntent.Logout)
            }) { Text("Sign out") }
        },
        dismissButton = { TextButton(onClick = { showLogout = false }) { Text("Stay signed in") } })
}

@Composable
private fun PortfolioSummary(portfolio: Portfolio) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "HOLDINGS VALUE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.4.sp
            )
            Text(
                "Pledged + non-pledged shares",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                money(portfolio.current),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1).sp
            )
            Text(
                "${signed(portfolio.profit)}  (${percent(portfolio.profit, portfolio.invested)})",
                color = if (portfolio.profit.signum() >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "Invested",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        money(portfolio.invested),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "Day change",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        signed(portfolio.dayChange),
                        color = if (portfolio.dayChange.signum() >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun PledgedSummary(portfolio: Portfolio) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Pledged holdings", fontWeight = FontWeight.SemiBold)
            if (portfolio.reportedPledgedQuantity > 0) {
                Text(
                    "${portfolio.reportedPledgedQuantity} shares across ${portfolio.pledgedHoldingCount} holdings",
                    style = MaterialTheme.typography.bodyMedium
                )
                DetailLine("Reported market value", money(portfolio.reportedPledgedMarketValue))
                Text(
                    "Already included in portfolio totals. Market value before haircut; this is not your collateral margin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    if (portfolio.hasUnreportedPledgeQuantity) "Pledged quantity not reported" else "No pledged quantity reported",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                "${if (portfolio.hasUnreportedPledgeQuantity && portfolio.reportedPledgedQuantity > 0) "Some quantities were not reported. " else ""}Kite may omit back-office pledge details. Check Zerodha Console for the full pledge record.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HoldingRow(holding: Holding, onClick: () -> Unit) {
    Surface(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "View ${holding.symbol} details", onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    holding.symbol,
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ProfitText(holding.profit)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${holding.quantity} shares · ${holding.exchange}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    holdingPercent(holding.profitPercentage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Non-pledged: ${holding.nonPledgedQuantity}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Pledged: ${holding.pledgedQuantity?.toString() ?: "Not reported"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Avg. ${money(holding.averagePrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("LTP ${money(holding.lastPrice)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Day ${signed(holding.dayChange)} (${holdingPercent(holding.dayChangePercentage)})",
                style = MaterialTheme.typography.bodySmall,
                color = if (holding.dayChange.signum() >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PositionRow(position: Position) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(position.symbol, fontWeight = FontWeight.SemiBold)
            Text(
                "${position.exchange} · ${position.product} · Qty ${position.quantity}${if (position.quantity == 0) " · Closed" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LTP ${money(position.lastPrice)}", style = MaterialTheme.typography.bodySmall)
                ProfitText(position.profit)
            }
        }
    }
}

@Composable
private fun ProfitText(value: BigDecimal) {
    Text(
        signed(value),
        color = if (value.signum() >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ErrorCard(message: String, retry: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier
            .fillMaxWidth()
            .padding(16.dp)) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            retry?.let { TextButton(onClick = it) { Text("Try again") } }
        }
    }
}

@Composable
private fun EmptyPortfolio(searching: Boolean, label: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (searching) "No matches found" else "No $label yet",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            if (searching) "Try another symbol or exchange." else "Your $label will appear here when available.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SortControls(tab: PortfolioTab, order: SortOrder, onIntent: (ClientIntent) -> Unit) {
    var expanded by remember(tab) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sort-menu-button")
            ) {
                Text(
                    "Sort: ${order.field.label}",
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(" ▾")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 420.dp)
            ) {
                sortFields(tab).forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field.label) },
                        onClick = { expanded = false; onIntent(ClientIntent.SortBy(field)) },
                        trailingIcon = { if (field == order.field) Text("✓") })
                }
            }
        }
        OutlinedButton(
            onClick = { onIntent(ClientIntent.ToggleSortDirection) },
            modifier = Modifier.testTag("sort-direction-button")
        ) {
            Text(
                when {
                    order.field == SortField.Name && order.direction == SortDirection.Ascending -> "A → Z"
                    order.field == SortField.Name -> "Z → A"
                    order.direction == SortDirection.Ascending -> "Low → high"
                    else -> "High → low"
                }, style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
