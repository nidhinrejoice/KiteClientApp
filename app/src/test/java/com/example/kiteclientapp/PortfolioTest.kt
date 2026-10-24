package com.example.kiteclientapp

import com.example.kiteclientapp.domain.*
import com.example.kiteclientapp.presentation.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PortfolioTest {
    private fun holding(qty: Int, average: String, last: String, close: String) =
        Holding("TEST", "NSE", qty, average.toBigDecimal(), last.toBigDecimal(), close.toBigDecimal())
    private fun assertAmount(expected: String, actual: BigDecimal) = assertEquals(0, expected.toBigDecimal().compareTo(actual))

    @Test fun `holding totals preserve decimal precision and losses`() {
        val portfolio = Portfolio(listOf(holding(3, "100.10", "110.20", "109.10"), holding(2, "80.20", "70.10", "72.20")), emptyList())
        assertAmount("460.70", portfolio.invested)
        assertAmount("470.80", portfolio.current)
        assertAmount("10.10", portfolio.profit)
        assertAmount("-0.90", portfolio.dayChange)
    }
    @Test fun `closed positions contribute realised pnl without affecting holdings value`() {
        val portfolio = Portfolio(emptyList(), listOf(Position("NIFTY", "NFO", "MIS", 0, BigDecimal.ZERO, "1250.25".toBigDecimal())))
        assertAmount("0", portfolio.current)
        assertAmount("0", portfolio.profit)
        assertAmount("1250.25", portfolio.positionProfit)
    }
    @Test fun `refresh failure retains last successful snapshot`() {
        val portfolio = Portfolio(listOf(holding(1, "10", "12", "11")), emptyList())
        val loaded = reduce(ClientState(), ClientMutation.Loaded(portfolio, 42))
        val failed = reduce(reduce(loaded, ClientMutation.Working), ClientMutation.Failed("Offline"))
        assertSame(portfolio, failed.portfolio)
        assertEquals(42L, failed.updatedAt)
        assertFalse(failed.busy)
        assertEquals("Offline", failed.error)
    }
    @Test fun `sign out clears all account portfolio and search state`() {
        val state = ClientState(account = Account("AB1", "Investor"), portfolio = Portfolio(emptyList(), emptyList()), query = "INFY", tab = PortfolioTab.Positions)
        assertEquals(ClientState(), reduce(state, ClientMutation.SignedOut()))
    }
    @Test fun `new account cannot inherit old portfolio`() {
        val state = ClientState(portfolio = Portfolio(emptyList(), emptyList()), query = "OLD")
        val next = reduce(state, ClientMutation.SignedIn(Account("NEW", "New investor")))
        assertNull(next.portfolio)
        assertEquals("", next.query)
        assertTrue(next.busy)
    }

    @Test fun `pledged and non pledged shares contribute once to every portfolio calculation`() {
        val first = holding(10, "100", "110", "108").copy(pledgedQuantity = 4)
        val second = holding(20, "50", "45", "46").copy(pledgedQuantity = 10)
        val unknown = holding(1, "10", "12", "11")
        val combined = Portfolio(listOf(first, second, unknown), emptyList())
        assertEquals(14L, first.quantity)
        assertEquals(30L, second.quantity)
        assertEquals(14L, combined.reportedPledgedQuantity)
        assertEquals(2, combined.pledgedHoldingCount)
        assertTrue(combined.hasUnreportedPledgeQuantity)
        assertAmount("890", combined.reportedPledgedMarketValue)
        assertAmount("2910", combined.invested)
        assertAmount("2902", combined.current)
        assertAmount("-8", combined.profit)
        assertAmount("-1", combined.dayChange)
        // Reading the pledge breakdown cannot add its market value to totals again.
        assertAmount("2902", combined.current)
    }
    @Test fun `fully pledged holding is included even when non pledged quantity is zero`() {
        val item = holding(0, "100.10", "120.20", "115.30").copy(pledgedQuantity = 5)
        val portfolio = Portfolio(listOf(item), emptyList())
        assertEquals(5L, item.quantity)
        assertAmount("500.50", portfolio.invested)
        assertAmount("601.00", portfolio.current)
        assertAmount("100.50", portfolio.profit)
        assertAmount("24.50", portfolio.dayChange)
    }
    @Test fun `unknown and zero pledges retain the known non pledged valuation`() {
        val item = holding(3, "100", "110", "109")
        assertEquals(3L, item.quantity)
        assertEquals(item.invested, item.copy(pledgedQuantity = 0).invested)
        assertEquals(item.current, item.copy(pledgedQuantity = 0).current)
        assertEquals(item.profit, item.copy(pledgedQuantity = 0).profit)
        assertEquals(item.dayChange, item.copy(pledgedQuantity = 0).dayChange)
    }
}
