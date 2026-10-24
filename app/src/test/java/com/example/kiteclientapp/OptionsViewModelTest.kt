package com.example.kiteclientapp

import androidx.lifecycle.SavedStateHandle
import com.example.kiteclientapp.feature.options.domain.*
import com.example.kiteclientapp.feature.options.presentation.*
import org.junit.Assert.*
import org.junit.Test

class OptionsViewModelTest {
    @Test fun `example analysis and edits clear stale results`() {
        val vm = OptionsViewModel(AnalyzeOptionStrategy(), SavedStateHandle())
        vm.accept(OptionsIntent.Preset(StrategyPreset.BullCallSpread)); vm.accept(OptionsIntent.Analyze)
        assertNotNull(vm.state.value.analysis); assertNull(vm.state.value.error)
        vm.accept(OptionsIntent.Scenario("120")); assertNull(vm.state.value.analysis)
        vm.accept(OptionsIntent.Analyze); assertEquals(0, "5".toBigDecimal().compareTo(vm.state.value.analysis!!.scenarioProfit))
        vm.accept(OptionsIntent.RemoveLeg(2)); assertNull(vm.state.value.analysis); assertEquals(1, vm.state.value.legs.size)
    }
    @Test fun `draft survives saved state restoration including leg edits`() {
        val saved = SavedStateHandle(); val vm = OptionsViewModel(AnalyzeOptionStrategy(), saved)
        vm.accept(OptionsIntent.Preset(StrategyPreset.IronCondor))
        vm.accept(OptionsIntent.LotSize("50")); vm.accept(OptionsIntent.SaveLeg(vm.state.value.legs.first().copy(premium = "1.5")))
        val restored = OptionsViewModel(AnalyzeOptionStrategy(), saved)
        assertEquals(vm.state.value, restored.state.value)
        restored.accept(OptionsIntent.Clear); assertEquals(OptionsState(), restored.state.value)
    }
    @Test fun `validation errors retain draft and reject scientific notation`() {
        val vm = OptionsViewModel(AnalyzeOptionStrategy(), SavedStateHandle())
        vm.accept(OptionsIntent.Analyze); assertNotNull(vm.state.value.error)
        vm.accept(OptionsIntent.Preset(StrategyPreset.LongCall)); vm.accept(OptionsIntent.Scenario("1e999999999")); vm.accept(OptionsIntent.Analyze)
        assertNull(vm.state.value.analysis); assertNotNull(vm.state.value.error); assertEquals(1, vm.state.value.legs.size)
        vm.accept(OptionsIntent.Scenario("100")); vm.accept(OptionsIntent.Expiry("2026-13-01")); vm.accept(OptionsIntent.Analyze)
        assertNull(vm.state.value.analysis); assertNotNull(vm.state.value.error)
    }
}
