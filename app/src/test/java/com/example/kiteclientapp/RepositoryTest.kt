package com.example.kiteclientapp

import com.example.kiteclientapp.data.*
import com.google.gson.Gson
import com.example.kiteclientapp.domain.*
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RepositoryTest {
    private val config = KiteConfiguration("key", "secret")
    private class FakeKiteApi : KiteApi {
        var failure: Exception? = null
        var calls = 0
        var authorization: String? = null
        override suspend fun holdings(authorization: String): Envelope<List<HoldingDto>> {
            calls++; this.authorization = authorization; failure?.let { throw it }
            return Envelope("success", emptyList())
        }
        override suspend fun positions(authorization: String): Envelope<PositionsDto> {
            calls++; failure?.let { throw it }; return Envelope("success", PositionsDto(emptyList()))
        }
        override suspend fun logout(authorization: String, apiKey: String, accessToken: String): Envelope<Any> {
            failure?.let { throw it }; return Envelope("success", null)
        }
    }
    @Test fun `expired session clears credentials and requires login`() = runTest {
        val store = SessionStore(MemorySessionPersistence()).apply { save("key", "expired", Account("AB1", "Investor")) }
        val api = FakeKiteApi().apply { failure = HttpException(Response.error<Any>(403, "{}".toResponseBody())) }
        val repository = KitePortfolioRepository(api, store, config, DemoPortfolioSource())
        try { repository.load(); fail("Expected SessionExpired") } catch (_: SessionExpired) { }
        assertNull(store.token)
    }
    @Test fun `demo never makes authenticated requests`() = runTest {
        val store = SessionStore(MemorySessionPersistence()).apply { demo() }
        val api = FakeKiteApi()
        val result = KitePortfolioRepository(api, store, config, DemoPortfolioSource()).load()
        assertEquals(5, result.holdings.size)
        assertEquals(0, api.calls)
    }
    @Test fun `live portfolio sends Kite authorization header`() = runTest {
        val store = SessionStore(MemorySessionPersistence()).apply { save("key", "session", Account("AB1", "Investor")) }
        val api = FakeKiteApi()
        KitePortfolioRepository(api, store, config, DemoPortfolioSource()).load()
        assertEquals("token key:session", api.authorization)
        assertEquals(2, api.calls)
    }
    @Test fun `failed remote logout still clears local token`() = runTest {
        val store = SessionStore(MemorySessionPersistence()).apply { save("key", "session", Account("AB1", "Investor")) }
        val api = FakeKiteApi().apply { failure = java.io.IOException("offline") }
        val auth = object : DirectKiteApi {
            override suspend fun exchange(apiKey: String, requestToken: String, checksum: String): Envelope<ExchangeResponse> = error("unused")
        }
        try { KiteAuthRepository(DeviceSessionExchange(auth, config), api, store, config).logout(); fail("Expected network failure") } catch (_: java.io.IOException) { }
        assertNull(store.token)
    }
    @Test fun `configuration requires an api key and secret`() {
        assertTrue(config.isConfigured)
        assertFalse(KiteConfiguration("", "secret").isConfigured)
        assertFalse(KiteConfiguration("key", "").isConfigured)
        assertFalse(KiteConfiguration("key", "  ").isConfigured)
    }
    @Test fun `holdings include unsettled without double subtracting sold quantities`() {
        val dto = HoldingDto("TEST", "NSE", 10, 3, 2, "100".toBigDecimal(), "110".toBigDecimal(), "105".toBigDecimal())
        assertEquals(13L, dto.toDomain().quantity)
    }

    @Test fun `Kite settled T1 and pledged quantities all contribute to holding valuation`() {
        val response = """{"tradingsymbol":"TEST","exchange":"NSE","quantity":100,"t1_quantity":5,"used_quantity":2,"average_price":100,"last_price":110,"close_price":108,"collateral_quantity":40,"collateral_type":"collateral"}"""
        val holding = Gson().fromJson(response, HoldingDto::class.java).toDomain()
        assertEquals(105, holding.nonPledgedQuantity)
        assertEquals(145L, holding.quantity)
        assertEquals(40, holding.pledgedQuantity)
        assertEquals("collateral", holding.collateralType)
        assertEquals(0, "4400".toBigDecimal().compareTo(holding.pledgedMarketValue))
        assertEquals(0, "15950".toBigDecimal().compareTo(holding.current))
    }
    @Test fun `missing and zero pledged quantities are distinguished`() {
        val dto = HoldingDto("TEST", "NSE", 10, 0, 0, "100".toBigDecimal(), "110".toBigDecimal(), "105".toBigDecimal())
        assertNull(dto.toDomain().pledgedQuantity)
        assertNull(dto.toDomain().pledgedMarketValue)
        assertEquals(0, dto.copy(collateral_quantity = 0).toDomain().pledgedQuantity)
        assertNull(dto.copy(collateral_quantity = -1).toDomain().pledgedQuantity)
        assertNull(dto.copy(collateral_type = "  ").toDomain().collateralType)
    }

    @Test fun `successful sign in persists account and token for a fresh process`() = runTest {
        val persistence = MemorySessionPersistence()
        val exchange = object : SessionExchange {
            override val usesDeviceSecret = false
            override suspend fun isAvailable() = true
            override suspend fun exchange(requestToken: String) = ExchangeResponse("new-session", "AB1234", "Investor")
        }
        val repository = KiteAuthRepository(exchange, FakeKiteApi(), SessionStore(persistence), config)
        assertEquals(Account("AB1234", "Investor"), repository.exchange("one-time-request"))
        val restarted = SessionStore(persistence)
        assertEquals(Account("AB1234", "Investor"), restarted.restore("key"))
        assertEquals("token key:new-session", restarted.authorization("key"))
    }
}
