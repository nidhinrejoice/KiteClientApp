package com.example.kiteclientapp.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Shared endpoint pacing for Options and Scanners, including simultaneous requests. */
@Singleton
class KiteMarketRequestGate @Inject constructor() {
    private val history = Mutex();
    private val quotes = Mutex()
    private var lastHistory = 0L;
    private var lastQuote = 0L
    suspend fun history() = history.withLock {
        val now =
            System.nanoTime() / 1_000_000; delay((360 - (now - lastHistory)).coerceAtLeast(0)); lastHistory =
        System.nanoTime() / 1_000_000
    }

    suspend fun quotes() = quotes.withLock {
        val now =
            System.nanoTime() / 1_000_000; delay((1050 - (now - lastQuote)).coerceAtLeast(0)); lastQuote =
        System.nanoTime() / 1_000_000
    }
}
