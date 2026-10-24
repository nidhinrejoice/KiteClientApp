package com.example.kiteclientapp.domain

import java.math.BigDecimal
import java.math.MathContext

// Domain types have no dependency on Android, Retrofit, or Compose.
data class Account(val id: String, val name: String, val isDemo: Boolean = false)
data class Holding(
    val symbol: String,
    val exchange: String,
    val nonPledgedQuantity: Int,
    val averagePrice: BigDecimal,
    val lastPrice: BigDecimal,
    val previousClose: BigDecimal,
    val pledgedQuantity: Int? = null,
    val collateralType: String? = null,
) {
    // Valuations use one combined quantity; the pledge summary is a breakdown of this value.
    val quantity: Long get() = nonPledgedQuantity.toLong() + (pledgedQuantity ?: 0).toLong()
    val pledgedMarketValue: BigDecimal? get() = pledgedQuantity?.let { lastPrice * it.toBigDecimal() }
    val invested: BigDecimal get() = averagePrice * quantity.toBigDecimal()
    val current: BigDecimal get() = lastPrice * quantity.toBigDecimal()
    val profit: BigDecimal get() = current - invested
    val previousValue: BigDecimal get() = previousClose * quantity.toBigDecimal()
    val profitPercentage: BigDecimal? get() = percentage(profit, invested)
    val dayChangePercentage: BigDecimal? get() = percentage(dayChange, previousValue)
    val dayChange: BigDecimal get() = (lastPrice - previousClose) * quantity.toBigDecimal()
}

private fun percentage(change: BigDecimal, base: BigDecimal): BigDecimal? =
    if (base.signum() > 0) change.multiply(100.toBigDecimal())
        .divide(base, MathContext.DECIMAL128) else null

data class Position(
    val symbol: String, val exchange: String, val product: String,
    val quantity: Int, val lastPrice: BigDecimal, val profit: BigDecimal,
)

data class Portfolio(val holdings: List<Holding>, val positions: List<Position>) {
    val invested: BigDecimal get() = holdings.fold(BigDecimal.ZERO) { total, item -> total + item.invested }
    val current: BigDecimal get() = holdings.fold(BigDecimal.ZERO) { total, item -> total + item.current }
    val profit: BigDecimal get() = current - invested
    val dayChange: BigDecimal get() = holdings.fold(BigDecimal.ZERO) { total, item -> total + item.dayChange }
    val reportedPledgedQuantity: Long get() = holdings.sumOf { (it.pledgedQuantity ?: 0).toLong() }
    val pledgedHoldingCount: Int get() = holdings.count { (it.pledgedQuantity ?: 0) > 0 }
    val hasUnreportedPledgeQuantity: Boolean get() = holdings.any { it.pledgedQuantity == null }
    val reportedPledgedMarketValue: BigDecimal
        get() = holdings.fold(BigDecimal.ZERO) { total, item ->
            total + (item.pledgedMarketValue ?: BigDecimal.ZERO)
        }
    val positionProfit: BigDecimal get() = positions.fold(BigDecimal.ZERO) { total, item -> total + item.profit }
}

class SessionExpired : Exception()
class ConfigurationMissing : Exception()
interface AuthRepository {
    val usesDeviceSecret: Boolean get() = false
    suspend fun isAvailable(): Boolean = true
    suspend fun exchange(requestToken: String): Account
    suspend fun restoreSession(): Account? = null
    suspend fun demo(): Account
    suspend fun logout()
    suspend fun clearSession()
}

interface PortfolioRepository {
    suspend fun load(): Portfolio
}

class LoadPortfolio(private val repository: PortfolioRepository) {
    suspend operator fun invoke(): Portfolio = repository.load()
}

class SignIn(private val repository: AuthRepository) {
    val usesDeviceSecret: Boolean get() = repository.usesDeviceSecret
    suspend fun isAvailable(): Boolean = repository.isAvailable()
    suspend operator fun invoke(requestToken: String): Account = repository.exchange(requestToken)
    suspend fun demo(): Account = repository.demo()
}

class SignOut(private val repository: AuthRepository) {
    suspend operator fun invoke() = repository.logout()
    suspend fun clearSession() = repository.clearSession()
}

class RestoreSession(private val repository: AuthRepository) {
    suspend operator fun invoke(): Account? = repository.restoreSession()
}
