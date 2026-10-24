package com.example.kiteclientapp.data

import com.example.kiteclientapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException
import javax.inject.Inject

class KiteConfiguration(val apiKey: String, val apiSecret: String, val redirectUrl: String = "https://www.google.com/") {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
}

class KiteAuthRepository @Inject constructor(
    private val exchange: SessionExchange, private val kiteApi: KiteApi,
    private val session: SessionStore, private val configuration: KiteConfiguration,
) : AuthRepository {
    override val usesDeviceSecret: Boolean get() = exchange.usesDeviceSecret
    override suspend fun isAvailable(): Boolean = exchange.isAvailable()
    override suspend fun exchange(requestToken: String): Account {
        if (!exchange.isAvailable()) throw ConfigurationMissing()
        val result = exchange.exchange(requestToken)
        check(result.accessToken.isNotBlank() && result.userId.isNotBlank() && result.userName.isNotBlank())
        val account = Account(result.userId, result.userName)
        session.save(configuration.apiKey, result.accessToken, account)
        return account
    }
    override suspend fun restoreSession(): Account? = session.restore(configuration.apiKey)
    override suspend fun demo(): Account {
        session.demo()
        return Account("DEMO", "Demo investor", isDemo = true)
    }
    override suspend fun clearSession() = session.clear()
    override suspend fun logout() {
        val token = session.token
        val authorization = try { session.authorization(configuration.apiKey) } catch (_: SessionExpired) { null }
        // Clear persisted and cached credentials before remote work, including when it is cancelled/offline.
        session.clear()
        if (token != null && authorization != null) {
            try {
                check(kiteApi.logout(authorization, configuration.apiKey, token).status == "success")
            } catch (error: HttpException) {
                if (error.code() != 403 && error.code() != 401) throw error
            }
        }
    }
}

class KitePortfolioRepository @Inject constructor(
    private val api: KiteApi, private val session: SessionStore,
    private val configuration: KiteConfiguration, private val demo: DemoPortfolioSource,
) : PortfolioRepository {
    override suspend fun load(): Portfolio {
        if (session.isDemo) return demo.load()
        val authorization = session.authorization(configuration.apiKey)
        return try {
            coroutineScope {
                val holdings = async { api.holdings(authorization).requireData().map { it.toDomain() } }
                val positions = async { api.positions(authorization).requireData().net.map {
                    Position(it.tradingsymbol, it.exchange, it.product, it.quantity, it.last_price, it.pnl)
                } }
                Portfolio(holdings.await(), positions.await())
            }
        } catch (error: CancellationException) { throw error
        } catch (error: HttpException) {
            if (error.code() == 403 || error.code() == 401) {
                session.clear()
                throw SessionExpired()
            }
            throw error
        }
    }
}
// Keep settled + T1 non-pledged shares separate. The domain adds reported pledged shares once.
// Kite already reduces quantity after a sale; used_quantity must not be subtracted again.
internal fun HoldingDto.toDomain() = Holding(
    symbol = tradingsymbol,
    exchange = exchange,
    nonPledgedQuantity = (quantity + t1_quantity).coerceAtLeast(0),
    averagePrice = average_price,
    lastPrice = last_price,
    previousClose = close_price,
    pledgedQuantity = collateral_quantity?.takeIf { it >= 0 },
    collateralType = collateral_type?.trim()?.takeIf { it.isNotEmpty() },
)
private fun <T> Envelope<T>.requireData(): T {
    check(status == "success") { "Kite request failed" }
    return checkNotNull(data) { "Missing Kite response data" }
}
