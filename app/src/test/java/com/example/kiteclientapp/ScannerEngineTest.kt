package com.example.kiteclientapp

import com.example.kiteclientapp.feature.scanners.domain.*
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import kotlin.math.*

class ScannerEngineTest {
    @Test fun `six technical engines match frozen web Python outputs and outcome samples`() {
        val fixture=JsonParser.parseReader(javaClass.getResourceAsStream("/scanners/web-parity.json")!!.reader()).asJsonObject
        fun candles(key: String)=fixture.getAsJsonArray(key).map { e -> val v=e.asJsonObject; ScanCandle(LocalDate.parse(v["date"].asString),v["open"].asDouble,v["high"].asDouble,v["low"].asDouble,v["close"].asDouble,v["volume"].asDouble) }
        val fieldMap=mapOf("Close" to "close","Short SMA" to "sma_short","Long SMA" to "sma_long","RSI" to "rsi","Trend SMA" to "trend","vs trend SMA" to "trend_distance","MACD" to "macd","Supertrend now" to "stop","Distance to stop" to "stop_distance","Latest close" to "latest_close","Stock return" to "stock_return","Nifty 50 return" to "index_return","Relative return" to "relative_return","RS vs average" to "rs_trend","Since signal" to "since_return","Prior 52W level" to "level","Volume vs avg" to "volume_multiple")
        for(id in listOf("sma","breakout","rsi","macd","supertrend","rs")) {
            val strategy=ScannerCatalog.get(id)
            val actual=TechnicalScanner.evaluate(strategy,strategy.defaults,ScanStock("TEST","Test"),candles("candles"),LocalDate.parse(fixture["through"].asString),candles("benchmark"))
            val expected=fixture.getAsJsonObject("results").getAsJsonObject(id)
            if(expected["row"].isJsonNull) assertNull("$id must have no current setup",actual.row)
            else {
                val row=expected.getAsJsonObject("row"); assertNotNull(id,actual.row)
                assertEquals(id,row[if(id=="sma") "crossover_type" else "signal"].asString,actual.row!!.signal)
                assertEquals(id,row[if(id=="sma") "crossover_date" else "signal_date"].asString,actual.row!!.date)
                actual.row!!.metrics.forEach { metric -> assertEquals("$id ${metric.label}",row[fieldMap.getValue(metric.label)].asDouble,metric.value!!,.051) }
            }
            if(strategy.outcomes) for(signal in strategy.signals) for(horizon in listOf(5,10,20)) {
                val stat=expected.getAsJsonObject("stats").getAsJsonObject(signal).getAsJsonObject(horizon.toString()); val values=actual.samples[signal to horizon].orEmpty()
                assertEquals("$id $signal $horizon sample count",stat["count"].asInt,values.size)
                if(values.isNotEmpty()) { assertEquals(stat["avg_return"].asDouble,values.average(),.006); assertEquals(stat["win_rate"].asDouble,values.count { if(bearish(signal)) it<0 else it>0 }*100.0/values.size,.051) }
            }
        }
    }
    @Test fun `flat and monotonic RSI handle zero losses without false oversold readings`() {
        assertEquals(50.0,ScanIndicators.rsi(List(30) { 100.0 },2).last(),0.0)
        assertEquals(100.0,ScanIndicators.rsi((1..30).map { it.toDouble() },2).last(),0.0)
        assertTrue(ScanIndicators.rsi(listOf(100.0,101.0),2).last().isNaN())
    }
    @Test fun `settings reject nonfinite fractional periods and invalid relationships`() {
        for(s in ScannerCatalog.strategies) { s.validate(s.defaults); assertThrows(IllegalArgumentException::class.java) { s.validate(s.defaults+("limit" to Double.NaN)) }; assertThrows(IllegalArgumentException::class.java) { s.validate(s.defaults+("limit" to 1.5)) } }
        val sma=ScannerCatalog.get("sma"); assertThrows(IllegalArgumentException::class.java) { sma.validate(sma.defaults+("short" to 40.0)) }
        val macd=ScannerCatalog.get("macd"); assertThrows(IllegalArgumentException::class.java) { macd.validate(macd.defaults+("fast" to 30.0)) }
        val move=ScannerCatalog.get("move"); assertThrows(IllegalArgumentException::class.java) { move.validate(move.defaults+("rich" to 1.0)+("cheap" to 1.0)) }
    }
    @Test fun `OI rolls across current contracts and new listings do not create false unwinding`() {
        val day=LocalDate.of(2026,10,19)
        fun c(d: LocalDate,price: Double,oi: Double)=ScanCandle(d,price,price,price,price,100.0,oi)
        val near=ScanInstrument(1,"NEAR","TEST","NFO","FUT","NFO-FUT",day.plusDays(10))
        val far=near.copy(token=2,symbol="FAR",expiry=day.plusDays(40))
        val series=listOf(near to listOf(c(day,100.0,1000.0),c(day.plusDays(1),101.0,800.0)),far to listOf(c(day,101.0,100.0),c(day.plusDays(1),102.0,400.0)))
        val row=DerivativeScanners.buildup(ScanStock("TEST","Test"),series,emptySet(),ScannerCatalog.get("oi").defaults,day.plusDays(1))!!
        assertEquals("Long buildup",row.signal); assertEquals(100.0,row.metrics.first { it.label=="OI added" }.value!!,0.0)
        val missing=series.mapIndexed { i,pair -> if(i==1) pair.first to pair.second.map { it.copy(oi=null) } else pair }
        assertThrows(IllegalArgumentException::class.java) { DerivativeScanners.buildup(ScanStock("TEST","Test"),missing,emptySet(),ScannerCatalog.get("oi").defaults,day.plusDays(1)) }
    }
    @Test fun `Black pricing obeys parity and IV inversion recovers known volatility`() {
        for(forward in listOf(80.0,100.0,120.0)) for(years in listOf(.01,.1,1.0)) {
            val call=DerivativeScanners.blackPrice(forward,100.0,years,.25,true); val put=DerivativeScanners.blackPrice(forward,100.0,years,.25,false)
            assertEquals(exp(-.065*years)*(forward-100),call-put,1e-8)
            if(min(call,put)>1e-7) { assertEquals(.25,DerivativeScanners.impliedVol(call,forward,100.0,years,true)!!,1e-6); assertEquals(.25,DerivativeScanners.impliedVol(put,forward,100.0,years,false)!!,1e-6) }
        }
        assertNull(DerivativeScanners.impliedVol(-1.0,100.0,100.0,.1,true))
    }
    @Test fun `straddle rejects crossed books and computes rich premium using sample RV`() {
        val now=ZonedDateTime.of(2026,10,20,16,0,0,0,SCANNER_ZONE); val expiry=now.toLocalDate().plusDays(10); val years=Duration.between(now,expiry.atTime(15,30).atZone(SCANNER_ZONE)).seconds/(365.0*86400)
        val call=ScanInstrument(1,"CALL","TEST","NFO","CE","NFO-OPT",expiry,100.0,50); val put=call.copy(token=2,symbol="PUT",type="PE")
        fun q(isCall: Boolean): ScanQuote { val price=DerivativeScanners.blackPrice(100*exp(.065*years),100.0,years,.3,isCall); return ScanQuote(price,null,price*.99,price*1.01,null,null,now.toLocalDate()) }
        val candles=(0..100).map { i -> val p=100*exp(.005*sin(i.toDouble())); ScanCandle(now.toLocalDate().minusDays(100L-i),p,p,p,p,100.0) }
        val result=DerivativeScanners.expectedMove(ScanStock("TEST","Test"),call,put,q(true),q(false),candles,ScannerCatalog.get("move").defaults,now)
        assertEquals("Rich",result.signal); assertEquals(.3*100,result.metrics.first { it.label=="ATM IV" }.value!!,.001)
        assertThrows(IllegalArgumentException::class.java) { DerivativeScanners.expectedMove(ScanStock("TEST","Test"),call,put,q(true).copy(bid=20.0,ask=1.0),q(false),candles,ScannerCatalog.get("move").defaults,now) }
    }
}
