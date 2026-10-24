package com.example.kiteclientapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.kiteclientapp.data.*
import com.example.kiteclientapp.domain.*
import com.example.kiteclientapp.feature.scanners.data.*
import com.example.kiteclientapp.feature.scanners.domain.*
import com.example.kiteclientapp.feature.scanners.presentation.*
import com.example.kiteclientapp.presentation.*
import com.example.kiteclientapp.ui.theme.KiteClientAppTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID

class ScannersFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun nativeNavigationScanFilteringAndSavedHistory() {
        val sessions=SessionStore(object: SessionPersistence { override fun read(): StoredSession?=null; override fun write(session: StoredSession) {}; override fun clear() {} })
        runBlocking { sessions.demo() }
        val source=KiteScannerMarketSource(Retrofit.Builder().baseUrl("http://127.0.0.1:1/").addConverterFactory(GsonConverterFactory.create()).build(),sessions,KiteConfiguration("",""),KiteMarketRequestGate())
        val repository=LocalScanHistoryRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val account=Account("SCANNER-TEST-${UUID.randomUUID()}","Scanner test",true)
        lateinit var vm: ScannersViewModel
        compose.runOnUiThread { vm=ScannersViewModel(RunScanner(source),LoadScanHistory(repository),SaveScan(repository),SavedStateHandle()); vm.account(account) }
        compose.setContent { val state by vm.state.collectAsState(); KiteClientAppTheme {
            ClientScreen(ClientState(account=account,portfolio=Portfolio(emptyList(),emptyList())),{},optionsContent={ TextPlaceholder() },scannersContent={ ScannersScreen(state,vm) })
        } }
        compose.onNodeWithText("Scanners",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("scanners-list").performScrollToNode(hasText("Nifty 100 SMA crossover"))
        compose.onNodeWithText("Nifty 100 SMA crossover").performClick()
        compose.onNodeWithTag("scanners-list").performScrollToNode(hasText("Max stocks"))
        compose.onNodeWithText("Max stocks").performTextReplacement("3")
        compose.onNodeWithTag("scanners-list").performScrollToNode(hasText("Generate signals"))
        compose.onNodeWithText("Generate signals").performClick()
        compose.waitUntil(15000) { vm.state.value.latest["sma"]!=null && vm.state.value.runs.isNotEmpty() }
        compose.runOnIdle { assertEquals(3,vm.state.value.latest.getValue("sma").total); assertNull(vm.state.value.error) }
        compose.onNodeWithTag("scanners-list").performScrollToNode(hasText("Search stocks"))
        compose.onNodeWithText("Search stocks").performTextInput("NO-MATCH")
        compose.onNodeWithTag("scanners-list").performScrollToNode(hasText("No matching stocks"))
        compose.onNodeWithText("No matching stocks").assertIsDisplayed()
        compose.onNodeWithText("Portfolio",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Your portfolio").assertIsDisplayed()
        compose.onNodeWithText("Scanners",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("scanners-list").performScrollToNode(hasText("History"))
        compose.onNodeWithText("History",substring=false).performClick()
        compose.waitUntil(5000) { !vm.state.value.historyLoading }
        compose.onNodeWithTag("scanners-list").performScrollToNode(hasText("Nifty 100 SMA crossover"))
        compose.onNodeWithTag("scan-history.${vm.state.value.runs.single().id}").performClick()
        compose.runOnIdle { assertEquals(ScannerView.SAVED,vm.state.value.view) }
        compose.onNodeWithTag("scanners-list").performScrollToNode(hasText("Scan history",substring=true))
        compose.onNodeWithText("Generate signals").assertDoesNotExist()
    }
    @Test fun historyRoundTripsAndSeparatesAccountsAndDemo() = runBlocking {
        val store=LocalScanHistoryRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val owner=ScanOwner("SCANNER-HISTORY-${UUID.randomUUID()}",false)
        val run=ScanRun(UUID.randomUUID().toString(),"sma","2026-10-20T12:00:00Z","2026-10-20",ScannerCatalog.get("sma").defaults,1,listOf(ScanRow("TEST","Test","Bullish","2026-10-20",listOf(ScanMetric("Close",100.0,MetricFormat.PRICE)))),emptyList(),0,emptyList(),false)
        store.save(owner,run)
        assertEquals(run,LocalScanHistoryRepository(InstrumentationRegistry.getInstrumentation().targetContext).load(owner).single())
        assertTrue(store.load(owner.copy(id=owner.id+"-other")).isEmpty()); assertTrue(store.load(owner.copy(demo=true)).isEmpty())
    }
}
@Composable private fun TextPlaceholder() { androidx.compose.material3.Text("Options placeholder") }
