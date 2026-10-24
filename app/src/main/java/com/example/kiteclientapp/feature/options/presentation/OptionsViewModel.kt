package com.example.kiteclientapp.feature.options.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.kiteclientapp.feature.options.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class OptionLegDraft(
    val id: Long,
    val type: OptionType = OptionType.Call,
    val side: OptionSide = OptionSide.Buy,
    val strike: String = "",
    val premium: String = "",
    val lots: String = "1"
)

enum class StrategyPreset(val label: String) {
    LongCall("Long call"), BullCallSpread("Bull call spread"), LongStraddle(
        "Long straddle"
    ),
    IronCondor("Iron condor")
}

data class OptionsState(
    val underlying: String = "",
    val expiry: String = "",
    val lotSize: String = "1",
    val scenario: String = "",
    val legs: List<OptionLegDraft> = emptyList(),
    val analysis: OptionAnalysis? = null,
    val error: String? = null
)

sealed interface OptionsIntent {
    data class Underlying(val value: String) : OptionsIntent
    data class Expiry(val value: String) : OptionsIntent
    data class LotSize(val value: String) : OptionsIntent
    data class Scenario(val value: String) : OptionsIntent
    data class SaveLeg(val leg: OptionLegDraft) : OptionsIntent
    data class RemoveLeg(val id: Long) : OptionsIntent
    data class Preset(val preset: StrategyPreset) : OptionsIntent
    data object Analyze : OptionsIntent
    data object Clear : OptionsIntent
}

fun reduceOptions(state: OptionsState, intent: OptionsIntent): OptionsState = when (intent) {
    is OptionsIntent.Underlying -> state.copy(
        underlying = intent.value.take(40),
        analysis = null,
        error = null
    )

    is OptionsIntent.Expiry -> state.copy(
        expiry = intent.value.take(10),
        analysis = null,
        error = null
    )

    is OptionsIntent.LotSize -> state.copy(
        lotSize = intent.value.take(7),
        analysis = null,
        error = null
    )

    is OptionsIntent.Scenario -> state.copy(
        scenario = intent.value.take(20),
        analysis = null,
        error = null
    )

    is OptionsIntent.SaveLeg -> state.copy(legs = if (state.legs.any { it.id == intent.leg.id }) state.legs.map { if (it.id == intent.leg.id) intent.leg else it } else (state.legs + intent.leg).take(
        8
    ), analysis = null, error = null)

    is OptionsIntent.RemoveLeg -> state.copy(
        legs = state.legs.filterNot { it.id == intent.id },
        analysis = null,
        error = null
    )

    OptionsIntent.Clear -> OptionsState()
    else -> state
}

@HiltViewModel
class OptionsViewModel @Inject constructor(
    private val analyze: AnalyzeOptionStrategy,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val mutableState = MutableStateFlow(restore())
    val state = mutableState.asStateFlow()
    fun accept(intent: OptionsIntent) {
        mutableState.value = when (intent) {
            OptionsIntent.Analyze -> calculate(mutableState.value)
            is OptionsIntent.Preset -> preset(intent.preset)
            else -> reduceOptions(mutableState.value, intent)
        }
        persist(mutableState.value)
    }

    private fun calculate(state: OptionsState): OptionsState = try {
        require(state.underlying.isNotBlank()) { "Enter an underlying name." }
        require(runCatching { java.time.LocalDate.parse(state.expiry) }.isSuccess) { "Enter a common expiry as YYYY-MM-DD." }
        val lotSize = state.lotSize.toIntOrNull()
            ?: throw IllegalArgumentException("Enter a whole-number lot size.")
        val scenario = decimal(state.scenario)
            ?: throw IllegalArgumentException("Enter an underlying price for the scenario.")
        val legs = state.legs.mapIndexed { index, draft ->
            val strike = decimal(draft.strike)
                ?: throw IllegalArgumentException("Leg ${index + 1}: enter a strike.")
            val premium = decimal(draft.premium)
                ?: throw IllegalArgumentException("Leg ${index + 1}: enter a premium per unit.")
            val lots = draft.lots.toIntOrNull()
                ?: throw IllegalArgumentException("Leg ${index + 1}: enter whole-number lots.")
            OptionLeg(draft.id, draft.type, draft.side, strike, premium, lots)
        }
        state.copy(analysis = analyze(OptionStrategy(lotSize, legs), scenario), error = null)
    } catch (e: IllegalArgumentException) {
        state.copy(analysis = null, error = e.message ?: "Check your inputs.")
    }

    private fun decimal(value: String) =
        value.takeIf { it.matches(Regex("[0-9]{1,10}(\\.[0-9]{1,8})?")) }?.toBigDecimalOrNull()

    private fun preset(preset: StrategyPreset): OptionsState {
        fun leg(id: Long, type: OptionType, side: OptionSide, strike: String, premium: String) =
            OptionLegDraft(id, type, side, strike, premium)

        val buy = OptionSide.Buy;
        val sell = OptionSide.Sell;
        val call = OptionType.Call;
        val put = OptionType.Put
        val legs = when (preset) {
            StrategyPreset.LongCall -> listOf(leg(1, call, buy, "100", "5"))
            StrategyPreset.BullCallSpread -> listOf(
                leg(1, call, buy, "100", "8"),
                leg(2, call, sell, "110", "3")
            )

            StrategyPreset.LongStraddle -> listOf(
                leg(1, call, buy, "100", "5"),
                leg(2, put, buy, "100", "5")
            )

            StrategyPreset.IronCondor -> listOf(
                leg(1, put, buy, "90", "1"),
                leg(2, put, sell, "95", "2"),
                leg(3, call, sell, "105", "2"),
                leg(4, call, buy, "110", "1")
            )
        }
        // Synthetic examples deliberately use unit quantity; never imply current contract lot sizes/prices.
        return OptionsState(
            "SAMPLE",
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(7).toString(),
            "1",
            "100",
            legs
        )
    }

    private fun persist(s: OptionsState) {
        savedState["underlying"] = s.underlying; savedState["expiry"] = s.expiry
        savedState["lotSize"] = s.lotSize; savedState["scenario"] = s.scenario
        savedState["legs"] = ArrayList(s.legs.flatMap {
            listOf(
                it.id.toString(),
                it.type.name,
                it.side.name,
                it.strike,
                it.premium,
                it.lots
            )
        })
    }

    private fun restore(): OptionsState {
        val legs = savedState.get<ArrayList<String>>("legs").orEmpty().chunked(6).mapNotNull { p ->
            runCatching {
                OptionLegDraft(
                    p[0].toLong(),
                    OptionType.valueOf(p[1]),
                    OptionSide.valueOf(p[2]),
                    p[3],
                    p[4],
                    p[5]
                )
            }.getOrNull()
        }.take(8)
        return OptionsState(
            savedState["underlying"] ?: "",
            savedState["expiry"] ?: "",
            savedState["lotSize"] ?: "1",
            savedState["scenario"] ?: "",
            legs
        )
    }
}
