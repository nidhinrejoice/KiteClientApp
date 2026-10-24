package com.example.kiteclientapp

import androidx.lifecycle.SavedStateHandle
import com.example.kiteclientapp.domain.*
import com.example.kiteclientapp.feature.scanners.domain.*
import com.example.kiteclientapp.feature.scanners.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScannersViewModelTest {
    private val dispatcher=UnconfinedTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private class History: ScanHistoryRepository {
        val runs=mutableMapOf<ScanOwner,List<ScanRun>>()
        override suspend fun load(owner: ScanOwner)=runs[owner].orEmpty()
        override suspend fun save(owner: ScanOwner,run: ScanRun) { runs[owner]=listOf(run)+runs[owner].orEmpty() }
    }
    private class Market: ScannerMarketSource {
        var requests=0; var wait=false; var expired=false
        override fun checkOwner(owner: ScanOwner) { if(expired) throw SessionExpired() }
        override suspend fun instruments(owner: ScanOwner): List<ScanInstrument> { requests++; if(wait) delay(10000); return listOf(ScanInstrument(1,"TEST","Test","NSE","EQ","NSE")) }
        override suspend fun nifty100(owner: ScanOwner)=listOf(ScanStock("TEST","Test"),ScanStock("MISSING","Missing"))
        override suspend fun candles(owner: ScanOwner,instrument: ScanInstrument,from: LocalDate,through: LocalDate): List<ScanCandle> = (0..300).map { i -> val p=100.0+i; ScanCandle(through.minusDays(300L-i),p,p,p,p,100.0) }
        override suspend fun quotes(owner: ScanOwner,instruments: List<ScanInstrument>)=emptyMap<String,ScanQuote>()
    }
    private fun vm(m: Market,h: History,saved: SavedStateHandle=SavedStateHandle())=ScannersViewModel(RunScanner(m,Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"),ZoneOffset.UTC)),LoadScanHistory(h),SaveScan(h),saved)
    @Test fun `invalid settings never request broker data and drafts restore`() = runTest {
        val m=Market(); val saved=SavedStateHandle(); val vm=vm(m,History(),saved); vm.account(Account("A","A")); advanceUntilIdle()
        vm.open("sma"); vm.edit("short","40"); vm.generate(); advanceUntilIdle()
        assertNotNull(vm.state.value.error); assertEquals(0,m.requests); assertEquals("40",vm(m,History(),saved).state.value.forms["sma"]!!["short"])
    }
    @Test fun `partial scans persist and account switch clears prior account results`() = runTest {
        val m=Market(); val h=History(); val vm=vm(m,h); vm.account(Account("A","A")); advanceUntilIdle(); vm.open("sma"); vm.generate()
        // Computation uses Dispatchers.Default, so wait on state rather than virtual-time polling.
        withContext(Dispatchers.Default) { withTimeout(5000) { while(vm.state.value.runningStrategy!=null) delay(10) } }
        advanceUntilIdle()
        val result=vm.state.value.latest["sma"]!!; assertEquals(1,result.skipped.size); assertEquals(1,vm.state.value.runs.size)
        vm.account(Account("B","B")); advanceUntilIdle(); assertTrue(vm.state.value.latest.isEmpty()); assertTrue(vm.state.value.runs.isEmpty())
        vm.account(Account("A","A")); advanceUntilIdle(); assertEquals(1,vm.state.value.runs.size)
    }
    @Test fun `account switch cancels running work and prevents old results reaching new account`() = runTest {
        val m=Market().apply { wait=true }; val vm=vm(m,History()); vm.account(Account("A","A")); advanceUntilIdle(); vm.open("sma"); vm.generate(); runCurrent()
        vm.account(Account("B","B")); advanceUntilIdle(); assertNull(vm.state.value.runningStrategy); assertTrue(vm.state.value.latest.isEmpty())
    }
}
