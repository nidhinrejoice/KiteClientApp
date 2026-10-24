package com.example.kiteclientapp.feature.options.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kiteclientapp.domain.SessionExpired
import com.example.kiteclientapp.feature.options.data.*
import com.example.kiteclientapp.feature.options.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.*
import javax.inject.Inject
import kotlin.math.abs

data class MarketOptionsState(
    val index: String = "NIFTY",
    val selectedExpiry: LocalDate? = null,
    val chain: ChainSnapshot? = null,
    val loading: Boolean = false,
    val historyLoading: Boolean = false,
    val error: String? = null,
    val window: Int = 15,
    val strangle: Boolean = false,
    val buy: Boolean = false,
    val callStrike: Double? = null,
    val putStrike: Double? = null,
    val lots: String = "1",
    val targetDate: String = "",
    val ivShift: Float = 0f,
    val simulation: SimulationResult? = null,
    val simulationError: String? = null
)

@HiltViewModel
class MarketOptionsViewModel @Inject constructor(
    private val repository: OptionMarketRepository,
    private val saved: SavedStateHandle
) : ViewModel() {
    private val mutable = MutableStateFlow(
        MarketOptionsState(
            index = saved["marketIndex"] ?: "NIFTY",
            selectedExpiry = saved.get<String>("marketExpiry")
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            strangle = saved["marketStrangle"] ?: false,
            buy = saved["marketBuy"] ?: false,
            lots = saved["marketLots"] ?: "1",
            targetDate = saved["marketTarget"] ?: "",
            ivShift = saved["marketShift"] ?: 0f,
            callStrike = saved["marketCall"],
            putStrike = saved["marketPut"]
        )
    )
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var accountKey: String? = null
    private var calculation: Job? = null
    fun account(key: String?) {
        if (accountKey != key) {
            job?.cancel(); calculation?.cancel(); accountKey = key; mutable.value =
                mutable.value.copy(
                    chain = null,
                    simulation = null,
                    loading = false,
                    historyLoading = false,
                    error = null
                )
        }
    }

    fun edit(change: (MarketOptionsState) -> MarketOptionsState) {
        mutable.value = change(mutable.value).copy(simulation = null, simulationError = null)
        val s = mutable.value
        saved["marketIndex"] = s.index; saved["marketExpiry"] =
            s.selectedExpiry?.toString(); saved["marketStrangle"] = s.strangle; saved["marketBuy"] =
            s.buy
        saved["marketLots"] = s.lots; saved["marketTarget"] = s.targetDate; saved["marketShift"] =
            s.ivShift; saved["marketCall"] = s.callStrike; saved["marketPut"] = s.putStrike
        calculation?.cancel()
        if (s.chain != null) calculation = viewModelScope.launch { delay(80); calculate(s) }
    }

    fun selectIndex(index: String) {
        job?.cancel(); edit { MarketOptionsState(index = index) }; load()
    }

    fun selectExpiry(expiry: LocalDate) {
        job?.cancel(); edit {
            it.copy(
                selectedExpiry = expiry,
                chain = null,
                loading = false,
                historyLoading = false,
                callStrike = null,
                putStrike = null,
                targetDate = ""
            )
        }; load()
    }

    fun visibleStrikes(chain: ChainSnapshot = state.value.chain!!): List<Double> {
        val index = chain.strikes.indexOf(chain.atm); return chain.strikes.subList(
            (index - state.value.window).coerceAtLeast(
                0
            ), (index + state.value.window + 1).coerceAtMost(chain.strikes.size)
        )
    }

    fun load() {
        job?.cancel(); calculation?.cancel();
        val draft = state.value
        mutable.value = draft.copy(loading = true, error = null, simulation = null)
        job = viewModelScope.launch {
            try {
                val chain = repository.load(draft.index, draft.selectedExpiry)
                val c = draft.callStrike?.takeIf { it in chain.strikes }
                    ?: if (draft.strangle) defaultStrike(chain, true) else chain.atm
                val p =
                    if (draft.strangle) draft.putStrike?.takeIf { it in chain.strikes && it < c }
                        ?: defaultStrike(chain, false) else c
                edit {
                    it.copy(
                        chain = chain,
                        selectedExpiry = chain.expiry,
                        callStrike = c,
                        putStrike = p,
                        loading = false,
                        targetDate = draft.targetDate.takeIf { date ->
                            runCatching {
                                LocalDate.parse(date) in chain.asOf.atZone(OPTIONS_ZONE)
                                    .toLocalDate()..chain.expiry
                            }.getOrDefault(false)
                        } ?: chain.expiry.toString()
                    )
                }
                enrich(chain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value =
                    state.value.copy(loading = false, historyLoading = false, error = message(e))
            }
        }
    }

    fun window(value: Int) {
        job?.cancel(); edit {
            it.copy(
                window = value,
                loading = false
            )
        }; state.value.chain?.let { chain ->
            job = viewModelScope.launch {
                try {
                    enrich(chain)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.value = state.value.copy(historyLoading = false, error = message(e))
                }
            }
        }
    }

    private suspend fun enrich(chain: ChainSnapshot) {
        mutable.value = state.value.copy(historyLoading = !chain.demo)
        repository.enrich(chain, visibleStrikes(chain).toSet()) { enriched ->
            mutable.value = state.value.copy(chain = enriched)
        }
        mutable.value = state.value.copy(historyLoading = false)
    }

    fun setStrangle(value: Boolean) {
        val chain = state.value.chain
        edit {
            it.copy(
                strangle = value,
                callStrike = chain?.let { c -> if (value) defaultStrike(c, true) else c.atm },
                putStrike = chain?.let { c -> if (value) defaultStrike(c, false) else c.atm })
        }
    }

    private fun defaultStrike(chain: ChainSnapshot, call: Boolean): Double = chain.legs
        .filter { it.call == call && it.price != null && if (call) it.strike > chain.spot else it.strike < chain.spot }
        .minByOrNull { leg ->
            chain.delta(leg)?.let { abs(it - if (call) .2 else -.2) } ?: Double.MAX_VALUE
        }?.strike ?: chain.atm

    fun simulate() {
        calculation?.cancel();
        val draft = state.value; calculation = viewModelScope.launch { calculate(draft) }
    }

    private suspend fun calculate(s: MarketOptionsState) {
        try {
            val chain = requireNotNull(s.chain) { "Load an option chain first." }
            val date = runCatching { LocalDate.parse(s.targetDate) }.getOrElse {
                throw IllegalArgumentException("Choose a target date.")
            }
            val call = requireNotNull(s.callStrike) { "Select a call strike." }
            val put =
                if (s.strangle) requireNotNull(s.putStrike) { "Select a put strike." } else call
            require(!s.strangle || put < call) { "For a strangle, the put strike must be below the call strike." }
            val target = date.atTime(15, 30).atZone(OPTIONS_ZONE).toInstant()
            val lots =
                s.lots.toIntOrNull() ?: throw IllegalArgumentException("Enter whole-number lots.")
            val result = withContext(Dispatchers.Default) {
                SimulateOptions()(
                    chain,
                    SimulationInput(call, put, s.buy, lots, target, s.ivShift.toDouble())
                )
            }
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(simulation = result, simulationError = null)
        } catch (e: IllegalArgumentException) {
            mutable.value = state.value.copy(simulation = null, simulationError = e.message)
        }
    }

    private fun message(e: Exception) = when (e) {
        is SessionExpired -> "Your broker session expired. Sign out and reconnect with Zerodha."
        is IllegalArgumentException -> e.message ?: "Check the selected contracts."
        else -> "Could not load broker data. Check your connection and market-data access, then retry."
    }
}

// Available weekday closes after the snapshot, plus expiry even when it is an exceptional session.
fun targetDates(chain: ChainSnapshot): List<LocalDate> =
    generateSequence(chain.asOf.atZone(OPTIONS_ZONE).toLocalDate()) { it.plusDays(1) }
        .takeWhile { it <= chain.expiry }.filter { date ->
            expiryInstant(date) > chain.asOf && (date == chain.expiry || date.dayOfWeek !in listOf(
                DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY
            ))
        }.toList()
