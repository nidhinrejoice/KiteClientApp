package com.example.kiteclientapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.kiteclientapp.data.DemoPortfolioSource
import com.example.kiteclientapp.domain.Account
import com.example.kiteclientapp.domain.Holding
import com.example.kiteclientapp.domain.Portfolio
import com.example.kiteclientapp.presentation.ClientScreen
import com.example.kiteclientapp.presentation.ClientState
import com.example.kiteclientapp.ui.theme.KiteClientAppTheme
import org.junit.Rule
import org.junit.Test

class PledgedHoldingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reportedPledgeAppearsInSummaryRowAndHoldingDetails() {
        compose.setContent {
            KiteClientAppTheme { ClientScreen(ClientState(account = Account("DEMO", "Demo", true), portfolio = DemoPortfolioSource().load())) {} }
        }
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("Pledged holdings"))
        compose.onNodeWithText("55 shares across 3 holdings").assertIsDisplayed()
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("RELIANCE"))
        compose.onNodeWithText("Pledged: 10").assertIsDisplayed()
        compose.onNode(hasText("RELIANCE") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNodeWithText("Pledged quantity").assertIsDisplayed()
        compose.onNodeWithText("10").assertIsDisplayed()
        compose.onNodeWithText("Pledged market value").assertIsDisplayed()
    }
    @Test fun combinedValuationAppearsInSummaryRowsAndDetails() {
        val holding = Holding("COMBINED", "NSE", 2, "100".toBigDecimal(), "120".toBigDecimal(), "110".toBigDecimal(), pledgedQuantity = 3)
        compose.setContent {
            KiteClientAppTheme { ClientScreen(ClientState(account = Account("DEMO", "Demo", true), portfolio = Portfolio(listOf(holding), emptyList()))) {} }
        }
        compose.onNodeWithText("₹600.00").assertIsDisplayed()
        compose.onNodeWithText("₹500.00").assertIsDisplayed()
        compose.onNodeWithText("+₹100.00  (20.00%)").assertIsDisplayed()
        compose.onNodeWithText("+₹50.00").assertIsDisplayed()
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("COMBINED"))
        compose.onNodeWithText("5 shares · NSE").assertIsDisplayed()
        compose.onNodeWithText("+₹100.00").assertIsDisplayed()
        compose.onNode(hasText("COMBINED") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNodeWithText("NSE · 5 shares").assertIsDisplayed()
        compose.onNodeWithText("Non-pledged quantity").assertIsDisplayed()
        compose.onNodeWithText("Pledged quantity").assertIsDisplayed()
    }
    @Test fun unreportedPledgeIsShownAsUnknownRatherThanZero() {
        val demo = DemoPortfolioSource().load()
        val portfolio = demo.copy(holdings = listOf(demo.holdings.first().copy(pledgedQuantity = null, collateralType = null)))
        compose.setContent {
            KiteClientAppTheme { ClientScreen(ClientState(account = Account("DEMO", "Demo", true), portfolio = portfolio)) {} }
        }
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("Pledged holdings"))
        compose.onNodeWithText("Pledged quantity not reported").assertIsDisplayed()
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("RELIANCE"))
        compose.onNodeWithText("Pledged: Not reported").assertIsDisplayed()
        compose.onNodeWithText("Pledged: 0").assertDoesNotExist()
    }
}
