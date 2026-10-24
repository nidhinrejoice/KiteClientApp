package com.example.kiteclientapp.presentation

import com.example.kiteclientapp.domain.Holding
import com.example.kiteclientapp.domain.Position
import java.math.BigDecimal

enum class SortDirection {
    Ascending, Descending;

    fun reversed() = if (this == Ascending) Descending else Ascending
}

enum class SortField(
    val label: String,
    val defaultDirection: SortDirection = SortDirection.Descending
) {
    Name("Name", SortDirection.Ascending),
    TotalProfit("Total profit"),
    DailyProfit("Daily profit"),
    ProfitPercentage("Profit percentage"),
    DailyPercentage("Daily percentage change"),
    InvestedValue("Invested value"),
    CurrentValue("Current value"),
    Quantity("Quantity"),
}

data class SortOrder(
    val field: SortField = SortField.Name,
    val direction: SortDirection = SortDirection.Ascending
)

fun sortFields(tab: PortfolioTab): List<SortField> = when (tab) {
    PortfolioTab.Holdings -> SortField.entries
    // The positions API supplies net P&L; it has no comparable holding cost/day-return basis.
    PortfolioTab.Positions -> listOf(SortField.Name, SortField.TotalProfit, SortField.Quantity)
}

private fun directed(result: Int, direction: SortDirection) =
    if (direction == SortDirection.Ascending) result else -result

private fun compareRates(
    a: BigDecimal,
    baseA: BigDecimal,
    b: BigDecimal,
    baseB: BigDecimal,
    direction: SortDirection
): Int {
    val knownA = baseA.signum() > 0
    val knownB = baseB.signum() > 0
    // Undefined percentages stay last in either direction, rather than becoming fabricated zeroes.
    if (!knownA || !knownB) return when {
        knownA -> -1; knownB -> 1; else -> 0
    }
    // Cross multiplication retains precision; never sort by rounded display strings or Double.
    return directed((a * baseB).compareTo(b * baseA), direction)
}

private fun byName(symbolA: String, exchangeA: String, symbolB: String, exchangeB: String): Int =
    symbolA.compareTo(symbolB, ignoreCase = true).takeIf { it != 0 }
        ?: exchangeA.compareTo(exchangeB, ignoreCase = true)

fun sortHoldings(holdings: List<Holding>, order: SortOrder): List<Holding> =
    holdings.sortedWith { a, b ->
        val result = when (order.field) {
            SortField.Name -> directed(
                a.symbol.compareTo(b.symbol, ignoreCase = true),
                order.direction
            )

            SortField.TotalProfit -> directed(a.profit.compareTo(b.profit), order.direction)
            SortField.DailyProfit -> directed(a.dayChange.compareTo(b.dayChange), order.direction)
            SortField.ProfitPercentage -> compareRates(
                a.profit,
                a.invested,
                b.profit,
                b.invested,
                order.direction
            )

            SortField.DailyPercentage -> compareRates(
                a.dayChange,
                a.previousValue,
                b.dayChange,
                b.previousValue,
                order.direction
            )

            SortField.InvestedValue -> directed(a.invested.compareTo(b.invested), order.direction)
            SortField.CurrentValue -> directed(a.current.compareTo(b.current), order.direction)
            SortField.Quantity -> directed(a.quantity.compareTo(b.quantity), order.direction)
        }
        if (result != 0) result else byName(a.symbol, a.exchange, b.symbol, b.exchange)
    }

fun sortPositions(positions: List<Position>, order: SortOrder): List<Position> =
    positions.sortedWith { a, b ->
        val result = when (order.field) {
            SortField.TotalProfit -> directed(a.profit.compareTo(b.profit), order.direction)
            SortField.Quantity -> directed(a.quantity.compareTo(b.quantity), order.direction)
            else -> directed(a.symbol.compareTo(b.symbol, ignoreCase = true), order.direction)
        }
        if (result != 0) result else byName(
            a.symbol,
            a.exchange,
            b.symbol,
            b.exchange
        ).takeIf { it != 0 }
            ?: a.product.compareTo(b.product, ignoreCase = true)
    }
