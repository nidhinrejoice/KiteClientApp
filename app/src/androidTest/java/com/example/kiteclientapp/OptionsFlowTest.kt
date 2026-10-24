package com.example.kiteclientapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import com.example.kiteclientapp.domain.Account
import com.example.kiteclientapp.domain.Portfolio
import com.example.kiteclientapp.feature.options.domain.AnalyzeOptionStrategy
import com.example.kiteclientapp.feature.options.presentation.*
import com.example.kiteclientapp.presentation.ClientScreen
import com.example.kiteclientapp.presentation.ClientState
import com.example.kiteclientapp.ui.theme.KiteClientAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OptionsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun open(): OptionsViewModel {
        val vm = OptionsViewModel(AnalyzeOptionStrategy(), SavedStateHandle())
        compose.setContent {
            val state by vm.state.collectAsState()
            KiteClientAppTheme {
                ClientScreen(ClientState(account = Account("DEMO", "Demo", true), portfolio = Portfolio(emptyList(), emptyList())), {}) {
                    OptionsScreen(state, vm::accept)
                }
            }
        }
        compose.onNodeWithText("Options", useUnmergedTree = true).performClick()
        return vm
    }
    private fun scrollTo(text: String) { compose.onNodeWithTag("options-list").performScrollToNode(hasText(text)) }
    @Test fun separateFeatureSupportsExampleAnalysisAndTabReturn() {
        val vm = open()
        compose.onNodeWithText("Example strategies").performClick()
        compose.onNodeWithText("Bull call spread").performClick()
        scrollTo("Analyse strategy")
        compose.onNodeWithTag("analyze-options").performClick()
        scrollTo("Maximum profit")
        compose.onNodeWithText("Maximum profit").assertIsDisplayed()
        compose.runOnIdle {
            assertNotNull(vm.state.value.analysis)
            assertEquals(0, "5".toBigDecimal().compareTo(vm.state.value.analysis!!.maxProfit))
        }
        scrollTo("P&L at expiry")
        compose.onNodeWithTag("option-payoff-chart").assertExists()
        compose.onNodeWithText("Portfolio", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your portfolio").assertIsDisplayed()
        compose.onNodeWithText("Options", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(2, vm.state.value.legs.size); assertNotNull(vm.state.value.analysis) }
    }
    @Test fun manualLegEditorAndValidationWork() {
        val vm = open()
        scrollTo("Add option leg")
        compose.onNodeWithText("Add option leg").performClick()
        compose.onNodeWithText("Put", substring = false).performClick()
        compose.onNodeWithText("Sell", substring = false).performClick()
        compose.onNodeWithText("Strike price").performTextInput("100")
        compose.onNodeWithText("Premium per unit").performTextInput("5")
        compose.onNodeWithText("Save leg").performClick()
        compose.runOnIdle { assertEquals(1, vm.state.value.legs.size) }
        scrollTo("Analyse strategy")
        compose.onNodeWithText("Analyse strategy").performClick()
        scrollTo("Enter an underlying name.")
        compose.onNodeWithTag("options-error").assertIsDisplayed()
        compose.runOnIdle { assertNull(vm.state.value.analysis) }
    }
}
