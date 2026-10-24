package com.example.kiteclientapp

import com.example.kiteclientapp.domain.*
import com.example.kiteclientapp.presentation.*
import org.junit.Assert.*
import org.junit.Test

class PortfolioSortingTest {
    private fun holding(name: String, quantity: Int, average: String, last: String, close: String, pledged: Int = 0) =
        Holding(name, "NSE", quantity, average.toBigDecimal(), last.toBigDecimal(), close.toBigDecimal(), pledgedQuantity = pledged)
    private val holdings = listOf(
        holding("GAMMA", 2, "50", "45", "50"),
        holding("EPSILON", 1, "0", "1", "0"),
        holding("BETA", 10, "100", "105", "104"),
        holding("DELTA", 0, "80", "100", "90", pledged = 5),
        holding("ALPHA", 1, "100", "110", "100"),
    )
    private fun names(field: SortField, direction: SortDirection) = sortHoldings(holdings, SortOrder(field, direction)).map { it.symbol }
    @Test fun `name sort is case insensitive and supports both directions`() {
        assertEquals(listOf("ALPHA", "BETA", "DELTA", "EPSILON", "GAMMA"), names(SortField.Name, SortDirection.Ascending))
        assertEquals(listOf("GAMMA", "EPSILON", "DELTA", "BETA", "ALPHA"), names(SortField.Name, SortDirection.Descending))
        val mixed = listOf(holdings.first().copy(symbol = "beta"), holdings.first().copy(symbol = "ALPHA"))
        assertEquals(listOf("ALPHA", "beta"), sortHoldings(mixed, SortOrder()).map { it.symbol })
    }
    @Test fun `profit sorts include pledged shares losses and deterministic ties`() {
        assertEquals(listOf("DELTA", "BETA", "ALPHA", "EPSILON", "GAMMA"), names(SortField.TotalProfit, SortDirection.Descending))
        assertEquals(listOf("GAMMA", "EPSILON", "ALPHA", "BETA", "DELTA"), names(SortField.TotalProfit, SortDirection.Ascending))
        assertEquals(listOf("DELTA", "ALPHA", "BETA", "EPSILON", "GAMMA"), names(SortField.DailyProfit, SortDirection.Descending))
        assertEquals(listOf("GAMMA", "EPSILON", "ALPHA", "BETA", "DELTA"), names(SortField.DailyProfit, SortDirection.Ascending))
    }
    @Test fun `percentage sorts use invested and previous close bases and keep unknowns last`() {
        assertEquals(listOf("DELTA", "ALPHA", "BETA", "GAMMA", "EPSILON"), names(SortField.ProfitPercentage, SortDirection.Descending))
        assertEquals(listOf("GAMMA", "BETA", "ALPHA", "DELTA", "EPSILON"), names(SortField.ProfitPercentage, SortDirection.Ascending))
        assertEquals(listOf("DELTA", "ALPHA", "BETA", "GAMMA", "EPSILON"), names(SortField.DailyPercentage, SortDirection.Descending))
        assertEquals(listOf("GAMMA", "BETA", "ALPHA", "DELTA", "EPSILON"), names(SortField.DailyPercentage, SortDirection.Ascending))
        assertNull(holdings.first { it.symbol == "EPSILON" }.profitPercentage)
        assertNull(holdings.first { it.symbol == "EPSILON" }.dayChangePercentage)
    }
    @Test fun `investment current value and combined quantity sorts are numeric`() {
        assertEquals(listOf("BETA", "DELTA", "ALPHA", "GAMMA", "EPSILON"), names(SortField.InvestedValue, SortDirection.Descending))
        assertEquals(listOf("BETA", "DELTA", "ALPHA", "GAMMA", "EPSILON"), names(SortField.CurrentValue, SortDirection.Descending))
        assertEquals(listOf("BETA", "DELTA", "GAMMA", "ALPHA", "EPSILON"), names(SortField.Quantity, SortDirection.Descending))
        assertEquals(listOf("EPSILON", "GAMMA", "ALPHA", "DELTA", "BETA"), names(SortField.CurrentValue, SortDirection.Ascending))
    }
    @Test fun `percentage sort retains differences hidden by display rounding`() {
        val items = listOf(holding("ALPHA", 1, "100", "100.000000000001", "100"), holding("BETA", 1, "100", "100.000000000002", "100"))
        for (field in listOf(SortField.ProfitPercentage, SortField.DailyPercentage)) {
            assertEquals(listOf("BETA", "ALPHA"), sortHoldings(items, SortOrder(field, SortDirection.Descending)).map { it.symbol })
        }
    }
    @Test fun `sorting does not mutate source data or change portfolio totals`() {
        val original = holdings.toList()
        val portfolio = Portfolio(holdings, emptyList())
        val reordered = Portfolio(sortHoldings(holdings, SortOrder(SortField.TotalProfit, SortDirection.Descending)), emptyList())
        assertEquals(original, holdings)
        assertEquals(0, portfolio.invested.compareTo(reordered.invested))
        assertEquals(0, portfolio.current.compareTo(reordered.current))
        assertEquals(0, portfolio.profit.compareTo(reordered.profit))
        assertEquals(0, portfolio.dayChange.compareTo(reordered.dayChange))
    }
    @Test fun `positions sort supplied PnL and signed quantity`() {
        val positions = listOf(
            Position("BETA", "NFO", "MIS", -10, "100".toBigDecimal(), "-50".toBigDecimal()),
            Position("ALPHA", "NSE", "CNC", 2, "100".toBigDecimal(), "25".toBigDecimal()),
        )
        assertEquals(listOf("ALPHA", "BETA"), sortPositions(positions, SortOrder(SortField.TotalProfit, SortDirection.Descending)).map { it.symbol })
        assertEquals(listOf("BETA", "ALPHA"), sortPositions(positions, SortOrder(SortField.Quantity, SortDirection.Ascending)).map { it.symbol })
        assertFalse(SortField.DailyPercentage in sortFields(PortfolioTab.Positions))
    }
    @Test fun `sort state survives refresh and is independent between tabs`() {
        val order = SortOrder(SortField.TotalProfit, SortDirection.Descending)
        val sorted = reduce(ClientState(), ClientMutation.Sorting(PortfolioTab.Holdings, order))
        val loaded = reduce(sorted, ClientMutation.Loaded(Portfolio(holdings, emptyList()), 1))
        assertEquals(order, loaded.holdingsSort)
        assertEquals(SortOrder(), loaded.positionsSort)
        val switched = reduce(loaded, ClientMutation.Tab(PortfolioTab.Positions))
        assertEquals(SortOrder(), switched.sortOrder())
        assertEquals(switched, reduce(switched, ClientMutation.Sorting(PortfolioTab.Positions, order.copy(field = SortField.DailyProfit))))
    }
}
