package com.example.kiteclientapp.feature.scanners.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kiteclientapp.domain.Account
import com.example.kiteclientapp.domain.SessionExpired
import com.example.kiteclientapp.feature.scanners.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

enum class ScannerView { LIBRARY, SCANNER, HISTORY, SAVED }
data class ScannersState(
    val view: ScannerView = ScannerView.LIBRARY,
    val strategyId: String = "sma",
    val forms: Map<String, Map<String, String>> = emptyMap(),
    val query: String = "",
    val alphabetical: Boolean = false,
    val signalFilter: String? = null,
    val historyFilter: String? = null,
    val runs: List<ScanRun> = emptyList(),
    val latest: Map<String, ScanRun> = emptyMap(),
    val savedRun: ScanRun? = null,
    val runningStrategy: String? = null,
    val progress: ScanProgress = ScanProgress(),
    val historyLoading: Boolean = false,
    val error: String? = null,
    val historyError: String? = null,
    val demo: Boolean = false
)

/** MVVM: exposes immutable observable state; coordinates domain use cases only. */
@HiltViewModel
class ScannersViewModel @Inject constructor(
    private val runScanner: RunScanner, private val loadHistory: LoadScanHistory,
    private val saveScan: SaveScan, private val saved: SavedStateHandle
) : ViewModel() {
    private fun defaultForms() = ScannerCatalog.strategies.associate { s ->
        s.id to s.parameters.associate { p ->
            p.key to (saved.get<String>("scanner.${s.id}.${p.key}") ?: number(p.default))
        }
    }

    private val mutable = MutableStateFlow(ScannersState(forms = defaultForms()))
    val state = mutable.asStateFlow();
    private var owner: ScanOwner? = null;
    private var scan: Job? = null;
    private var history: Job? = null
    fun account(account: Account?) {
        val next = account?.let { ScanOwner(it.id, it.isDemo) }
        if (owner == next) return
        scan?.cancel()
        history?.cancel()
        owner = next
        mutable.value = ScannersState(forms = defaultForms(), demo = next?.demo ?: false)
        if (next != null) refreshHistory()
    }

    fun library() {
        mutable.update {
            it.copy(
                view = ScannerView.LIBRARY,
                query = "",
                signalFilter = null,
                error = null
            )
        }
    }

    fun history() {
        mutable.update {
            it.copy(
                view = ScannerView.HISTORY,
                query = "",
                signalFilter = null,
                error = null
            )
        }; refreshHistory()
    }

    fun open(id: String) {
        mutable.update {
            it.copy(
                view = ScannerView.SCANNER,
                strategyId = id,
                query = "",
                signalFilter = null,
                error = null
            )
        }
    }

    fun saved(run: ScanRun) {
        mutable.update {
            it.copy(
                view = ScannerView.SAVED,
                savedRun = run,
                strategyId = run.strategyId,
                query = "",
                signalFilter = null,
                error = null
            )
        }
    }

    fun query(value: String) {
        mutable.update { it.copy(query = value) }
    }

    fun sort() {
        mutable.update { it.copy(alphabetical = !it.alphabetical) }
    }

    fun filter(value: String?) {
        mutable.update { it.copy(signalFilter = value) }
    }

    fun historyFilter(value: String?) {
        mutable.update { it.copy(historyFilter = value) }
    }

    fun edit(key: String, value: String) {
        val s = state.value; if (s.runningStrategy == s.strategyId) return
        val form = s.forms.getValue(s.strategyId) + (key to value)
        saved["scanner.${s.strategyId}.$key"] = value
        mutable.update { it.copy(forms = it.forms + (s.strategyId to form), error = null) }
    }

    fun reset() {
        val strategy = ScannerCatalog.get(state.value.strategyId); strategy.parameters.forEach {
            edit(
                it.key,
                number(it.default)
            )
        }
    }

    fun cancel() {
        scan?.cancel()
        scan = null
        mutable.update {
            it.copy(
                runningStrategy = null,
                progress = ScanProgress(),
                error = "Scan cancelled. Start again to generate a complete result."
            )
        }
    }

    fun generate() {
        if (state.value.runningStrategy != null) return
        val account = owner ?: return;
        val strategy = ScannerCatalog.get(state.value.strategyId)
        val settings: Map<String, Double>
        try {
            settings = state.value.forms.getValue(strategy.id).mapValues { (_, v) ->
                v.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Enter a number in every setting.")
            }; strategy.validate(settings)
        } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(error = e.message) }; return
        }
        mutable.update {
            it.copy(
                runningStrategy = strategy.id,
                error = null,
                progress = ScanProgress(),
                query = "",
                signalFilter = null
            )
        }
        scan = viewModelScope.launch {
            try {
                val result = runScanner(account, strategy, settings) { progress ->
                    mutable.update {
                        it.copy(progress = progress)
                    }
                }
                ensureActive(); if (owner != account) return@launch
                mutable.update {
                    it.copy(
                        latest = it.latest + (strategy.id to result),
                        runningStrategy = null,
                        query = "",
                        signalFilter = null
                    )
                }
                try {
                    saveScan(
                        account,
                        result
                    ); ensureActive(); if (owner == account) mutable.update {
                        it.copy(
                            runs = (listOf(
                                result
                            ) + it.runs.filter { r -> r.id != result.id }).take(100),
                            historyError = null
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (owner == account) mutable.update { it.copy(historyError = "Scan finished, but history could not be saved. The current result is still available.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (owner == account) mutable.update {
                    it.copy(
                        runningStrategy = null,
                        error = message(e)
                    )
                }
            }
        }
    }

    fun refreshHistory() {
        val account = owner ?: return; history?.cancel()
        mutable.update { it.copy(historyLoading = true, historyError = null) }
        history = viewModelScope.launch {
            try {
                val runs =
                    loadHistory(account); ensureActive(); if (owner == account) mutable.update { current ->
                    current.copy(
                        runs = runs,
                        historyLoading = false,
                        latest = runs.groupBy { it.strategyId }
                            .mapValues { it.value.maxBy { r -> r.finishedAt } } + current.latest
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (owner == account) mutable.update {
                    it.copy(
                        historyLoading = false,
                        historyError = "Could not read saved scans. Retry history."
                    )
                }
            }
        }
    }

    private fun message(error: Exception) = when (error) {
        is SessionExpired -> "Your broker session expired or changed. Sign out and reconnect with Zerodha."
        is IllegalArgumentException -> error.message
            ?: "Check scanner settings and market-data availability."

        is IllegalStateException -> "The broker could not complete this scan. Wait a moment and retry."
        else -> "Could not complete the scan. Check your connection and broker historical-data access, then retry."
    }

    companion object {
        private fun number(value: Double) =
            if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }
}
