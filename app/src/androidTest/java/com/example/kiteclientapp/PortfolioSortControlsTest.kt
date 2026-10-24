package com.example.kiteclientapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.kiteclientapp.data.KiteConfiguration
import com.example.kiteclientapp.domain.*
import com.example.kiteclientapp.presentation.*
import com.example.kiteclientapp.ui.theme.KiteClientAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PortfolioSortControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: ClientViewModel
    private val snapshot = Portfolio(listOf(
        Holding("ALPHA", "NSE", 1, "100".toBigDecimal(), "110".toBigDecimal(), "100".toBigDecimal(), pledgedQuantity = 0),
        Holding("BETA", "NSE", 0, "80".toBigDecimal(), "100".toBigDecimal(), "90".toBigDecimal(), pledgedQuantity = 5),
    ), listOf(Position("TEST", "NFO", "MIS", 1, "100".toBigDecimal(), "50".toBigDecimal())))

    private fun openPortfolio() {
        val auth = object : AuthRepository {
            override suspend fun exchange(requestToken: String): Account = error("No login in this test")
            override suspend fun restoreSession() = Account("DEMO", "Demo investor", true)
            override suspend fun demo() = Account("DEMO", "Demo investor", true)
            override suspend fun logout() = Unit
            override suspend fun clearSession() = Unit
        }
        val portfolio = object : PortfolioRepository { override suspend fun load() = snapshot }
        compose.runOnUiThread {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ClientViewModel(SignIn(auth), SignOut(auth), RestoreSession(auth), LoadPortfolio(portfolio), KiteConfiguration("", ""), SavedStateHandle()) as T
            }
            model = ViewModelProvider(compose.activity, factory)[ClientViewModel::class.java]
        }
        compose.setContent {
            val state by model.state.collectAsState()
            KiteClientAppTheme { ClientScreen(state, model::accept) }
        }
        compose.waitUntil(5_000) { model.state.value.portfolio != null }
    }
    private fun openSortMenu() {
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasTestTag("sort-menu-button"))
        compose.onNodeWithTag("sort-menu-button").performClick()
    }
    @Test fun sortMenuAndDirectionDriveMviStateWithoutChangingTotals() {
        openPortfolio()
        openSortMenu()
        compose.onNodeWithText("Total profit").performScrollTo().performClick()
        compose.onNodeWithText("Sort: Total profit").assertIsDisplayed()
        compose.onNodeWithText("High → low").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(SortOrder(SortField.TotalProfit, SortDirection.Descending), model.state.value.holdingsSort)
            assertEquals("BETA", sortHoldings(snapshot.holdings, model.state.value.holdingsSort).first().symbol)
        }
        compose.onNodeWithTag("sort-direction-button").performClick()
        compose.onNodeWithText("Low → high").assertIsDisplayed()
        compose.runOnIdle { assertEquals("ALPHA", sortHoldings(snapshot.holdings, model.state.value.holdingsSort).first().symbol) }
        openSortMenu()
        compose.onNodeWithText("Daily percentage change").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(SortField.DailyPercentage, model.state.value.holdingsSort.field)
            assertSame(snapshot, model.state.value.portfolio)
        }
    }
    @Test fun positionsOfferOnlySupportedFieldsAndKeepIndependentSort() {
        openPortfolio()
        openSortMenu()
        compose.onNodeWithText("Invested value").performScrollTo().performClick()
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("Positions (1)"))
        compose.onNodeWithText("Positions (1)").performClick()
        openSortMenu()
        compose.onNodeWithText("Daily profit").assertDoesNotExist()
        compose.onNodeWithText("Profit percentage").assertDoesNotExist()
        compose.onNodeWithText("Quantity").performClick()
        compose.runOnIdle {
            assertEquals(SortField.Quantity, model.state.value.positionsSort.field)
            assertEquals(SortField.InvestedValue, model.state.value.holdingsSort.field)
        }
    }
}
