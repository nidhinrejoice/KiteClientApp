package com.example.kiteclientapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import com.example.kiteclientapp.data.*
import com.example.kiteclientapp.feature.options.data.OptionMarketRepository
import com.example.kiteclientapp.feature.options.presentation.*
import com.example.kiteclientapp.ui.theme.KiteClientAppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MarketOptionsFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun capture(name:String) {
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun demoChainAndSimulationWorkWithoutBrokerRequests() {
        val store=SessionStore(object:SessionPersistence {
            override fun read():StoredSession?=null
            override fun write(session:StoredSession) {}
            override fun clear() {}
        })
        runBlocking { store.demo() }
        // Unreachable host proves demo never requires a market-data connection.
        val repo=OptionMarketRepository(Retrofit.Builder().baseUrl("http://127.0.0.1:1/").addConverterFactory(GsonConverterFactory.create()).build(),store,KiteConfiguration("",""))
        val vm=MarketOptionsViewModel(repo,SavedStateHandle())
        compose.setContent {
            val state by vm.state.collectAsState()
            KiteClientAppTheme { OptionsWorkspace(OptionsState(),{},state,vm) }
        }
        compose.waitUntil(10000) { vm.state.value.chain!=null }
        capture("options-overview")
        compose.onNodeWithTag("market-options-list").performScrollToNode(hasText("Open interest by strike"))
        compose.onNodeWithTag("oi-chart").assertExists()
        capture("options-oi")
        compose.onNodeWithText("Straddle & strangle").performClick()
        compose.waitUntil(10000) { vm.state.value.simulation!=null }
        compose.runOnIdle { assertNull(vm.state.value.simulationError); assertFalse(vm.state.value.buy) }
        compose.onNodeWithTag("market-options-list").performScrollToNode(hasText("Strategy",substring=false))
        capture("options-builder")
        compose.onNodeWithText("Strangle",substring=false).performClick()
        compose.waitUntil(10000) { vm.state.value.simulation!=null }
        compose.runOnIdle {
            val s=vm.state.value
            assertTrue(s.callStrike!!>s.chain!!.spot)
            assertTrue(s.putStrike!!<s.chain!!.spot)
        }
        compose.onNodeWithText("Straddle",substring=false).performClick()
        compose.onNodeWithText("Buy",substring=false).performClick()
        compose.onNodeWithTag("market-options-list").performScrollToNode(hasTestTag("target-date-slider"))
        compose.onNodeWithTag("target-date-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitUntil(10000) { vm.state.value.simulation!=null }
        compose.runOnIdle { assertNotEquals(vm.state.value.chain!!.expiry.toString(),vm.state.value.targetDate) }
        val before=vm.state.value.simulation!!
        compose.onNodeWithTag("market-options-list").performScrollToNode(hasTestTag("iv-shift-slider"))
        compose.onNodeWithTag("iv-shift-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.waitUntil(10000) { vm.state.value.simulation!=null }
        compose.runOnIdle {
            assertEquals(4f,vm.state.value.ivShift)
            assertEquals(before.rangeLow,vm.state.value.simulation!!.rangeLow,0.0)
        }
        compose.waitUntil(10000) { vm.state.value.simulation?.maxLoss!=null }
        compose.onNodeWithTag("market-options-list").performScrollToNode(hasText("Max loss at expiry"))
        compose.onNodeWithText("Max loss at expiry").assertIsDisplayed()
        capture("options-results")
        compose.onNodeWithTag("market-options-list").performScrollToNode(hasTestTag("simulation-chart"))
        compose.onNodeWithTag("simulation-chart").assertIsDisplayed()
        compose.onNodeWithTag("simulation-chart").performTouchInput { click(center) }
        compose.onNodeWithText("Model chance above",substring=true).assertExists()
        capture("options-payoff")
        compose.onNodeWithTag("options-menu").performClick()
        compose.onNodeWithText("Manual payoff").performClick()
        compose.onNodeWithText("Example strategies").assertIsDisplayed()
    }
}
