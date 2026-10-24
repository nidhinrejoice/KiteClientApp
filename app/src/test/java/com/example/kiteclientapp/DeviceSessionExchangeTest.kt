package com.example.kiteclientapp

import com.example.kiteclientapp.data.*
import com.example.kiteclientapp.domain.ConfigurationMissing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DeviceSessionExchangeTest {
    @Test fun `direct exchange sends exact Kite checksum and returns session`() = runTest {
        var called = false
        val api = object : DirectKiteApi {
            override suspend fun exchange(apiKey: String, requestToken: String, checksum: String): Envelope<ExchangeResponse> {
                called = true
                assertEquals("public", apiKey)
                assertEquals("request", requestToken)
                val expected = MessageDigest.getInstance("SHA-256").digest("publicrequestprivate".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, checksum)
                assertEquals(64, checksum.length)
                return Envelope("success", ExchangeResponse("session", "AB1", "Investor"))
            }
        }
        val exchange = DeviceSessionExchange(api, KiteConfiguration("public", "private"))
        assertTrue(exchange.isAvailable())
        assertTrue(exchange.usesDeviceSecret)
        assertEquals("session", exchange.exchange("request").accessToken)
        assertTrue(called)
    }
    @Test fun `missing local credentials fail before contacting Kite`() = runTest {
        val api = object : DirectKiteApi {
            override suspend fun exchange(apiKey: String, requestToken: String, checksum: String): Envelope<ExchangeResponse> = error("Must not contact Kite")
        }
        for (configuration in listOf(KiteConfiguration("public", ""), KiteConfiguration("", "private"))) {
            val exchange = DeviceSessionExchange(api, configuration)
            assertFalse(exchange.isAvailable())
            try { exchange.exchange("request"); fail("Expected missing credentials") } catch (_: ConfigurationMissing) { }
        }
    }
}
