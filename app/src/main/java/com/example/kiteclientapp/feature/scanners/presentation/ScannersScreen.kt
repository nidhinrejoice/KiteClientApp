package com.example.kiteclientapp.feature.scanners.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.kiteclientapp.feature.scanners.domain.*
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ScannersScreen(state: ScannersState, viewModel: ScannersViewModel) {
    BackHandler(state.view != ScannerView.LIBRARY) { if (state.view == ScannerView.SAVED) viewModel.history() else viewModel.library() }
    val strategy = ScannerCatalog.get(state.strategyId)
    val run =
        if (state.view == ScannerView.SAVED) state.savedRun else state.latest[state.strategyId]
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("scanners-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Scanners",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Find setups across the market",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.demo) Text(
                "DEMO · Synthetic market data · No broker requests",
                Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.view != ScannerView.HISTORY && state.view != ScannerView.SAVED,
                    onClick = viewModel::library,
                    label = { Text("Library") })
                FilterChip(
                    selected = state.view == ScannerView.HISTORY || state.view == ScannerView.SAVED,
                    onClick = viewModel::history,
                    label = { Text("History") })
            }
        }
        if (state.runningStrategy != null) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${ScannerCatalog.get(state.runningStrategy).name} · Scanning",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("${state.progress.phase} · ${state.progress.completed} / ${state.progress.total}")
                    if (state.progress.total > 0) LinearProgressIndicator(
                        progress = { state.progress.completed.toFloat() / state.progress.total },
                        modifier = Modifier.fillMaxWidth()
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Keep the app open to finish. You can browse other screens.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = viewModel::cancel) { Text("Cancel scan") }
                }
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.historyError?.let {
            item {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error
                ); TextButton(onClick = viewModel::refreshHistory) { Text("Retry history") }
            }
        }
        when (state.view) {
            ScannerView.LIBRARY -> {
                item {
                    Search(state.query, "Search strategies", viewModel::query); TextButton(
                    onClick = viewModel::sort
                ) { Text(if (state.alphabetical) "Sort: Name A–Z" else "Sort: Recently added") }
                }
                val available = ScannerCatalog.strategies.filter {
                    "${it.name} ${it.category} ${if (it.derivatives) "F&O" else "Nifty 100"}".contains(
                        state.query,
                        true
                    )
                }.let { if (state.alphabetical) it.sortedBy { s -> s.name } else it }
                items(available, key = { it.id }) { s ->
                    OutlinedCard(Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.open(s.id) }) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                s.category.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                s.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                s.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${if (s.derivatives) "F&O stocks & indices" else "Nifty 100"} · Daily",
                                style = MaterialTheme.typography.labelMedium
                            )
                            TextButton(onClick = { viewModel.open(s.id) }) { Text("Open scanner →") }
                        }
                    }
                }
                if (available.isEmpty()) item { Text("No matching strategies") }
            }

            ScannerView.HISTORY -> {
                item {
                    Text("Generated scans", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.runs.size} saved scans · Last 100 kept on this device",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = viewModel::refreshHistory,
                        enabled = !state.historyLoading
                    ) { Text("Refresh history") }
                    Search(state.query, "Search scan history", viewModel::query)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.historyFilter == null,
                            onClick = { viewModel.historyFilter(null) },
                            label = { Text("All strategies") })
                        ScannerCatalog.strategies.forEach { s ->
                            FilterChip(
                                selected = state.historyFilter == s.id,
                                onClick = { viewModel.historyFilter(s.id) },
                                label = { Text(s.name) })
                        }
                    }
                    if (state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val runs = state.runs.filter {
                    (state.historyFilter == null || it.strategyId == state.historyFilter) && ScannerCatalog.get(
                        it.strategyId
                    ).name.contains(state.query, true)
                }
                items(runs, key = { it.id }) { r ->
                    OutlinedCard(
                        Modifier
                            .fillMaxWidth()
                            .testTag("scan-history.${r.id}")
                            .clickable { viewModel.saved(r) }) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                ScannerCatalog.get(r.strategyId).name,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(stamp(r.finishedAt), style = MaterialTheme.typography.bodySmall)
                            Text("${r.rows.size} signals · ${r.total} scanned · ${if (r.skipped.isEmpty()) "Complete" else "Partial (${r.skipped.size} skipped)"}")
                        }
                    }
                }
                if (runs.isEmpty() && !state.historyLoading && state.historyError == null) item {
                    Text(
                        if (state.runs.isEmpty()) "No scans yet. Choose a scanner from the library." else "No matching scans"
                    )
                }
            }

            else -> {
                item {
                    TextButton(onClick = { if (state.view == ScannerView.SAVED) viewModel.history() else viewModel.library() }) {
                        Text(
                            if (state.view == ScannerView.SAVED) "← Scan history" else "← Scanner library"
                        )
                    }
                    Text(
                        strategy.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        strategy.description,
                        Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.view == ScannerView.SCANNER) {
                    items(strategy.parameters, key = { "parameter.${it.key}" }) { p ->
                        OutlinedTextField(
                            value = state.forms[strategy.id]?.get(p.key).orEmpty(),
                            onValueChange = { viewModel.edit(p.key, it) },
                            label = { Text(p.label) },
                            singleLine = true,
                            enabled = state.runningStrategy != strategy.id,
                            keyboardOptions = KeyboardOptions(keyboardType = if (p.integer) KeyboardType.Number else KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = viewModel::generate,
                                enabled = state.runningStrategy == null
                            ) { Text("Generate signals") }
                            TextButton(
                                onClick = viewModel::reset,
                                enabled = state.runningStrategy != strategy.id
                            ) { Text("Reset settings") }
                        }
                    }
                }
                if (run != null) {
                    item {
                        Text(
                            "${run.rows.size} signals · ${run.total - run.skipped.size} / ${run.total} scanned",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${stamp(run.finishedAt)} · Daily history through ${run.throughDate}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            strategy.parameters.joinToString(" · ") { "${it.label}: ${plain(run.settings[it.key])}" },
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.signalFilter == null,
                                onClick = { viewModel.filter(null) },
                                label = { Text("All (${run.rows.size})") })
                            strategy.signals.forEach { signal ->
                                FilterChip(
                                    selected = state.signalFilter == signal,
                                    onClick = { viewModel.filter(signal) },
                                    label = { Text("$signal (${run.rows.count { it.signal == signal }})") })
                            }
                        }
                        Search(state.query, "Search stocks", viewModel::query)
                    }
                    if (run.skipped.isNotEmpty()) item { SkippedStocks(run) }
                    val shown = run.rows.filter {
                        (state.signalFilter == null || it.signal == state.signalFilter) && "${it.symbol} ${it.company}".contains(
                            state.query.trim(),
                            true
                        )
                    }
                    items(shown, key = { "${run.id}.${it.symbol}" }) { row ->
                        SignalCard(
                            row,
                            run.rows.indexOf(row) + 1,
                            run.id
                        )
                    }
                    item {
                        Text(
                            "${shown.size} of ${run.rows.size} signals · ${run.noSignal} without a signal · Prices in INR",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (shown.isEmpty()) Text(
                            if (run.rows.isEmpty()) if (strategy.state) "No stocks in this setup now" else "No signals in this period" else "No matching stocks",
                            Modifier.padding(top = 8.dp)
                        )
                    }
                    if (run.outcomes.isNotEmpty()) {
                        item {
                            Text(
                                "How past signals played out",
                                style = MaterialTheme.typography.titleLarge
                            ); Text(
                            "About 3 years of daily history. A win means a move in the signal's direction.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        }
                        items(strategy.signals) { signal ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(signal, fontWeight = FontWeight.SemiBold)
                                    run.outcomes.filter { it.signal == signal }.forEach { stat ->
                                        Text(
                                            "${stat.sessions} sessions: ${
                                                if (stat.count == 0) "No samples" else "${
                                                    plain(stat.winRate)
                                                }% win · avg ${plain(stat.averageReturn)}% · ${stat.count} signals"
                                            }", style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Text(
                                "Before costs, close to close. Uses today's index members and overlapping signals; this is a rough historical guide, not a backtest.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (state.runningStrategy != strategy.id) item { Text("Set your parameters and generate signals.") }
            }
        }
    }
}

@Composable
private fun Search(value: String, label: String, change: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SkippedStocks(run: ScanRun) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    OutlinedCard(Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${run.skipped.size} stocks skipped · ${if (expanded) "Hide" else "Show reasons"}",
                color = MaterialTheme.colorScheme.tertiary
            )
            if (expanded) run.skipped.forEach {
                Text(
                    "${it.symbol}: ${it.reason}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SignalCard(row: ScanRow, rank: Int, runId: String) {
    var expanded by rememberSaveable(runId, row.symbol) { mutableStateOf(false) }
    val color = when {
        row.signal in listOf(
            "Rich",
            "Fair",
            "Cheap"
        ) -> MaterialTheme.colorScheme.onSurfaceVariant; bearish(row.signal) -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.primary
    }
    OutlinedCard(Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$rank  ${row.symbol}", fontWeight = FontWeight.Bold); Text(
            row.company,
            style = MaterialTheme.typography.bodySmall
        )
            Text("${row.signal} · ${row.date}", color = color, fontWeight = FontWeight.SemiBold)
            val visible = if (expanded) row.metrics else row.metrics.take(3)
            visible.forEach { metric ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(metric.label, style = MaterialTheme.typography.bodySmall); Text(
                    formatMetric(
                        metric
                    ), style = MaterialTheme.typography.bodyMedium
                )
                }
            }
            if (expanded) row.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (expanded) "Less detail ↑" else "More detail ↓",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun stamp(value: String) = runCatching {
    Instant.parse(value).atZone(SCANNER_ZONE)
        .format(DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a 'IST'", Locale.ENGLISH))
}.getOrDefault(value)

private fun plain(value: Double?): String =
    if (value == null || !value.isFinite()) "—" else String.format(Locale.ENGLISH, "%.2f", value)
        .removeSuffix(".00")

internal fun formatMetric(metric: ScanMetric): String {
    val value = metric.value?.takeIf { it.isFinite() } ?: return "—"
    return when (metric.format) {
        MetricFormat.PRICE -> NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
            .format(value)

        MetricFormat.PERCENT -> "${if (value > 0) "+" else ""}${plain(value)}%"
        MetricFormat.RATE -> "${plain(value)}%"
        MetricFormat.MULTIPLE -> "${plain(value)}×"
        MetricFormat.QUANTITY -> NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"))
            .format(value)

        MetricFormat.PLUS_MINUS -> "±${plain(value)}%"
        else -> plain(value)
    }
}
