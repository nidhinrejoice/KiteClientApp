package com.example.kiteclientapp.data

import com.example.kiteclientapp.domain.*
import javax.inject.Inject

class DemoPortfolioSource @Inject constructor() {
    fun load() = Portfolio(
        holdings = listOf(
            holding("RELIANCE", 24, "2740.50", "2916.85", "2889.10", pledged = 10),
            holding("TCS", 12, "3625.00", "3842.20", "3856.00", pledged = 5),
            holding("HDFCBANK", 40, "1580.25", "1672.40", "1658.30"),
            holding("INFY", 30, "1540.00", "1496.75", "1508.20"),
            holding("ITC", 100, "412.60", "456.35", "452.10", pledged = 40),
        ),
        positions = listOf(Position("NIFTY DEMO", "NFO", "MIS", 0, "245.50".toBigDecimal(), "1250.00".toBigDecimal())),
    )
    private fun holding(symbol: String, nonPledgedQuantity: Int, average: String, last: String, close: String, pledged: Int = 0) =
        Holding(symbol, "NSE", nonPledgedQuantity, average.toBigDecimal(), last.toBigDecimal(), close.toBigDecimal(), pledgedQuantity = pledged, collateralType = if (pledged > 0) "collateral" else null)
}
