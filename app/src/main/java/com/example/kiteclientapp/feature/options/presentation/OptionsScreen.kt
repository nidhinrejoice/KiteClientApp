package com.example.kiteclientapp.feature.options.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.kiteclientapp.feature.options.domain.*
import com.example.kiteclientapp.presentation.money
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun OptionsScreen(state: OptionsState, onIntent: (OptionsIntent) -> Unit) {
    var editing by remember { mutableStateOf<OptionLegDraft?>(null) }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp)
            .testTag("options-list"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Options analysis",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Build a strategy. Explore its expiry payoff.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    "Manual inputs · All legs share one underlying and expiry. Premiums are per unit; quantity = lots × lot size. Results exclude fees, taxes, slippage and early exercise. No orders are placed.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showPresets = true }) { Text("Example strategies") }
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        item {
            OutlinedTextField(
                state.underlying,
                { onIntent(OptionsIntent.Underlying(it)) },
                Modifier.fillMaxWidth(),
                label = { Text("Underlying") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                state.expiry,
                { onIntent(OptionsIntent.Expiry(it)) },
                Modifier.fillMaxWidth(),
                label = { Text("Common expiry (YYYY-MM-DD)") },
                singleLine = true
            )
        }
        item {
            NumberInput(
                "Contract lot size",
                state.lotSize,
                { onIntent(OptionsIntent.LotSize(it)) },
                integer = true
            )
        }
        item {
            Text(
                "Strategy legs (${state.legs.size}/8)",
                style = MaterialTheme.typography.titleLarge
            )
        }
        if (state.legs.isEmpty()) item { Text("Add a call or put, or start with an example strategy.") }
        items(state.legs, key = { it.id }) { leg ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${leg.side} ${leg.type} · Strike ${leg.strike}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("Premium ${leg.premium} / unit · ${leg.lots} lot(s)")
                    Row {
                        TextButton(onClick = { editing = leg }) { Text("Edit leg") }
                        TextButton(onClick = { onIntent(OptionsIntent.RemoveLeg(leg.id)) }) { Text("Remove leg") }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    editing = OptionLegDraft((state.legs.maxOfOrNull { it.id } ?: 0) + 1)
                },
                enabled = state.legs.size < 8,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add option leg") }
        }
        item {
            NumberInput(
                "Underlying price at expiry",
                state.scenario,
                { onIntent(OptionsIntent.Scenario(it)) })
        }
        item {
            Button(
                onClick = { onIntent(OptionsIntent.Analyze) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("analyze-options")
            ) { Text("Analyse strategy") }
        }
        state.error?.let { error ->
            item {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("options-error")
                )
            }
        }
        state.analysis?.let { result ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Expiry analysis", style = MaterialTheme.typography.titleLarge)
                        Metric(
                            if (result.netPremiumPaid.signum() >= 0) "Net premium paid" else "Net premium received",
                            money(result.netPremiumPaid.abs())
                        )
                        Metric(
                            if (result.maxProfit?.signum() == -1) "Best outcome (P&L)" else "Maximum profit",
                            result.maxProfit?.let(::money) ?: "Unlimited"
                        )
                        Metric("Maximum loss", result.maxLoss?.let(::money) ?: "Unlimited")
                        Text("Breakeven price(s)", fontWeight = FontWeight.SemiBold)
                        Text(result.breakEvens.joinToString("\n") { range ->
                            when {
                                range.to == null -> "${price(range.from)} and above"
                                range.from.compareTo(range.to) == 0 -> price(range.from)
                                else -> "${price(range.from)} – ${price(range.to)}"
                            }
                        }.ifEmpty { "None" })
                        Metric("P&L at ${state.scenario}", money(result.scenarioProfit))
                    }
                }
            }
            item { PayoffChart(result) }
        }
    }
    editing?.let { leg ->
        LegEditor(
            leg,
            { editing = null }) { onIntent(OptionsIntent.SaveLeg(it)); editing = null }
    }
    if (showPresets) AlertDialog(
        onDismissRequest = { showPresets = false },
        title = { Text("Example strategies") },
        text = {
            Column {
                Text("Synthetic prices with lot size 1. Selecting an example replaces your draft; edit every input for your contract.")
                StrategyPreset.entries.forEach { preset ->
                    TextButton(onClick = {
                        onIntent(
                            OptionsIntent.Preset(preset)
                        ); showPresets = false
                    }) { Text(preset.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showPresets = false }) { Text("Cancel") } })
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear strategy?") },
        text = { Text("This removes the current legs and inputs.") },
        confirmButton = {
            TextButton(onClick = {
                onIntent(OptionsIntent.Clear); confirmClear = false
            }) { Text("Clear strategy") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun NumberInput(
    label: String,
    value: String,
    changed: (String) -> Unit,
    integer: Boolean = false
) {
    OutlinedTextField(
        value,
        { changed(it.take(20)) },
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal)
    )
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        ); Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun price(value: BigDecimal) = value.setScale(2, RoundingMode.HALF_UP).toPlainString()

@Composable
private fun LegEditor(
    initial: OptionLegDraft,
    dismiss: () -> Unit,
    save: (OptionLegDraft) -> Unit
) {
    var leg by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Option leg") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionSide.entries.forEach { side ->
                        FilterChip(
                            leg.side == side,
                            { leg = leg.copy(side = side) },
                            label = { Text(side.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionType.entries.forEach { type ->
                        FilterChip(
                            leg.type == type,
                            { leg = leg.copy(type = type) },
                            label = { Text(type.name) })
                    }
                }
                NumberInput("Strike price", leg.strike, { leg = leg.copy(strike = it) })
                NumberInput("Premium per unit", leg.premium, { leg = leg.copy(premium = it) })
                NumberInput(
                    "Number of lots",
                    leg.lots,
                    { leg = leg.copy(lots = it) },
                    integer = true
                )
            }
        },
        confirmButton = { TextButton(onClick = { save(leg) }) { Text("Save leg") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun PayoffChart(result: OptionAnalysis) {
    val points = result.points
    val maxSpot = points.last().spot.toDouble()
    val low = minOf(0.0, points.minOf { it.profit.toDouble() })
    val high = maxOf(0.0, points.maxOf { it.profit.toDouble() })
    val span = (high - low).coerceAtLeast(1.0)
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    val gainColor = Color(0xFF16846B)
    val lossColor = MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("P&L at expiry", style = MaterialTheme.typography.titleLarge)
        Text(
            "Top ${money(high.toBigDecimal())} · Bottom ${money(low.toBigDecimal())}",
            style = MaterialTheme.typography.bodySmall
        )
        Canvas(Modifier
            .fillMaxWidth()
            .height(210.dp)
            .testTag("option-payoff-chart")) {
            val pad = 8.dp.toPx();
            val w = size.width - pad * 2;
            val h = size.height - pad * 2
            fun x(value: Double) = pad + (value / maxSpot * w).toFloat()
            fun y(value: Double) = pad + ((high - value) / span * h).toFloat()
            drawLine(axisColor, Offset(pad, y(0.0)), Offset(size.width - pad, y(0.0)), 1.dp.toPx())
            val path = Path()
            points.forEachIndexed { i, point ->
                if (i == 0) path.moveTo(
                    x(point.spot.toDouble()),
                    y(point.profit.toDouble())
                ) else path.lineTo(x(point.spot.toDouble()), y(point.profit.toDouble()))
            }
            drawPath(
                path,
                lineColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
            // Draw the selected scenario on the same exact payoff curve.
            val scenario = points.firstOrNull { it.spot.compareTo(result.scenarioPrice) == 0 }
            if (scenario != null) drawCircle(
                if (scenario.profit.signum() >= 0) gainColor else lossColor,
                3.dp.toPx(),
                Offset(x(scenario.spot.toDouble()), y(scenario.profit.toDouble()))
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) { Text("0"); Text(price(points.last().spot)) }
        Text(
            "Underlying price at expiry →\nThe plotted range is finite; unlimited outcomes extend beyond it. Breakevens are rounded to 2 decimals.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
