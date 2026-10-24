package com.example.kiteclientapp

import com.example.kiteclientapp.feature.options.domain.*
import com.example.kiteclientapp.feature.options.data.csvFields
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import kotlin.math.*

class MarketAnalysisTest {
    private val start=Instant.parse("2026-10-21T10:00:00Z")
    private val years=7.0/365
    private fun chain():ChainSnapshot {
        val legs=(80..120 step 5).flatMap { strike -> listOf(true,false).map { call ->
            val premium=OptionModels.price(100.0,strike.toDouble(),years,.2,call)
            ChainLeg(strike*2L+if(call) 1 else 0,"$strike-$call",call,strike.toDouble(),50,premium,premium*.99,premium*1.01,if(call) 100 else 200,100)
        } }
        return ChainSnapshot("TEST",LocalDate.parse("2026-10-28"),listOf(LocalDate.parse("2026-10-28")),100.0,start,LocalDate.parse("2026-10-21"),years,legs)
    }
    @Test fun black76ParityAndIvRoundTrip() {
        val c=OptionModels.price(25000.0,25100.0,30.0/365,.17,true)
        val p=OptionModels.price(25000.0,25100.0,30.0/365,.17,false)
        assertEquals(-100*exp(-OPTION_RATE*30/365),c-p,1e-6)
        assertEquals(.17,OptionModels.impliedVol(c,25000.0,25100.0,30.0/365,true)!!,1e-8)
        assertNull(OptionModels.impliedVol(26000.0,25000.0,25000.0,.1,true))
    }
    @Test fun quotePreferenceAndUnavailableOi() {
        val leg=chain().legs.first()
        assertEquals((leg.bid!!+leg.ask!!)/2,leg.price!!,1e-9)
        assertEquals(leg.last!!,leg.copy(ask=leg.bid-1).price!!,0.0)
        assertNull(leg.copy(bid=null,last=0.0).price)
        assertEquals(2.0,chain().pcr!!,0.0)
        assertNull(chain().copy(legs=chain().legs.mapIndexed { i,l -> if(i==0) l.copy(oi=null) else l }).pcr)
        assertNotNull(chain().maxPain)
    }
    @Test fun realisedVolUsesTwentySampleReturns() {
        val closes=(0..20).map { exp(if(it%2==0) 0.0 else .01) }
        assertEquals(sqrt(20*.0001/19*252),OptionModels.realisedVol(closes)!!,1e-10)
        assertNull(OptionModels.realisedVol(closes.drop(1)))
    }
    @Test fun expiryLongShortAndQuantityAgree() {
        val c=chain(); val input=SimulationInput(100.0,100.0,true,2,start.plusSeconds(7*86400L),0.0)
        val long=SimulateOptions()(c,input); val short=SimulateOptions()(c,input.copy(buy=false))
        assertEquals(100L,long.quantity)
        assertEquals(2,long.breakevens.size)
        val premium=c.leg(100.0,true)!!.price!!+c.leg(100.0,false)!!.price!!
        assertEquals(100-premium,long.breakevens[0],1e-5)
        assertEquals(100+premium,long.breakevens[1],1e-5)
        assertEquals(1.0,long.profitChance+short.profitChance,1e-7)
        long.points.zip(short.points).forEach { (a,b) -> assertEquals(a.targetPnl,-b.targetPnl,1e-8); assertEquals(a.targetPnl,a.expiryPnl,1e-6) }
        assertEquals(1.0,long.bands.sumOf { it.chance },1e-8)
        assertTrue(long.worstFive<0)
    }
    @Test fun ivShiftOnlyChangesRepricingDistributionStaysFixed() {
        val c=chain(); val input=SimulationInput(105.0,95.0,true,1,start.plusSeconds(3*86400L),0.0)
        val a=SimulateOptions()(c,input); val b=SimulateOptions()(c,input.copy(ivShift=5.0))
        assertEquals(a.rangeLow,b.rangeLow,0.0); assertEquals(a.rangeHigh,b.rangeHigh,0.0)
        assertEquals(a.bands.map { it.chance },b.bands.map { it.chance })
        assertTrue(b.points[b.points.size/2].targetPnl>a.points[a.points.size/2].targetPnl)
    }
    @Test fun sameTimestampHasZeroModelPnlAtSpot() {
        val c=chain(); val r=SimulateOptions()(c,SimulationInput(100.0,100.0,true,1,start,0.0))
        assertEquals(100.0,r.rangeLow,1e-8)
        assertTrue(r.points.minOf { abs(it.targetPnl) }<.01)
    }
    @Test fun expiryLimitsAndStablePlotMatchStrategySide() {
        val c=chain(); val input=SimulationInput(105.0,95.0,true,1,start.plusSeconds(2*86400L),0.0)
        val long=SimulateOptions()(c,input)
        val short=SimulateOptions()(c,input.copy(buy=false))
        assertNull(long.maxProfit); assertEquals(long.premium,long.maxLoss!!,0.0)
        assertNull(short.maxLoss); assertEquals(short.premium,short.maxProfit!!,0.0)
        assertEquals(95-long.premium/long.quantity,long.expiryBreakevens.first(),1e-8)
        assertEquals(105+long.premium/long.quantity,long.expiryBreakevens.last(),1e-8)
        val later=SimulateOptions()(c,input.copy(target=start.plusSeconds(5*86400L),ivShift=2.0))
        assertEquals(long.points.first().level,later.points.first().level,0.0)
        assertEquals(long.points.last().level,later.points.last().level,0.0)
        assertTrue(long.rangeTwoLow<long.rangeLow); assertTrue(long.rangeTwoHigh>long.rangeHigh)
    }
    @Test fun weekdayDatesExcludePastClosesAndWeekends() {
        val c=chain()
        val dates=com.example.kiteclientapp.feature.options.presentation.targetDates(c)
        assertEquals(c.expiry,dates.last())
        assertTrue(dates.all { it.dayOfWeek !in listOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY) })
        assertTrue(dates.none { it.atTime(15,30).atZone(ZoneId.of("Asia/Kolkata")).toInstant()<=c.asOf })
    }
    @Test fun parsesQuotedCsv() { assertEquals(listOf("123","A,B","say \"yes\""),csvFields("123,\"A,B\",\"say \"\"yes\"\"\"")) }
    @Test(expected=IllegalArgumentException::class) fun rejectsTargetAfterExpiry() {
        SimulateOptions()(chain(),SimulationInput(100.0,100.0,true,1,start.plusSeconds(8*86400L),0.0))
    }
}
