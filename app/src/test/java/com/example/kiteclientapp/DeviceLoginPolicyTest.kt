package com.example.kiteclientapp

import com.example.kiteclientapp.presentation.DeviceLoginPolicy
import com.example.kiteclientapp.presentation.LoginNavigation
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceLoginPolicyTest {
    private val policy = DeviceLoginPolicy("https://www.google.com/", "nonce")
    @Test fun `allows the official Kite authorization handoff`() {
        for (url in listOf(
            "https://kite.zerodha.com/connect/login?v=3&api_key=public",
            "https://kite.zerodha.com/connect/finish?api_key=public&sess_id=synthetic",
            "https://kite.trade/connect/login?api_key=public&sess_id=synthetic",
            "https://www.kite.trade/connect/login",
            "https://accounts.zerodha.com/",
            "https://kite.trade:443/connect/login",
        )) assertEquals(url, LoginNavigation.Continue, policy.classify(url))
    }
    @Test fun `captures registered callback with default HTTPS port and normalized root path`() {
        assertEquals(LoginNavigation.Callback, policy.classify("https://www.google.com/?request_token=synthetic&state=nonce&status=success"))
        assertEquals(LoginNavigation.Callback, policy.classify("https://www.google.com:443?request_token=synthetic&state=nonce&status=success"))
    }
    @Test fun `captures localhost callbacks only for the pending login`() {
        for (host in listOf("127.0.0.1", "localhost", "[::1]")) {
            val exact = DeviceLoginPolicy("http://$host:8000/api/kite/callback", "nonce")
            val url = "http://$host:8000/api/kite/callback?state=nonce&status=success&request_token=synthetic"
            assertEquals(LoginNavigation.Callback, exact.classify(url))
            assertEquals("kiteclient://auth/callback?state=nonce&status=success&request_token=synthetic", exact.callbackResult(url))
        }
    }
    @Test fun `rejects localhost with missing wrong duplicated state or token`() {
        val policy = DeviceLoginPolicy("http://127.0.0.1:8000/api/kite/callback", "nonce")
        for (query in listOf(
            "status=success&request_token=synthetic", "state=wrong&status=success&request_token=synthetic",
            "state=nonce&state=wrong&status=success&request_token=synthetic", "state=nonce&status=success",
            "state=nonce&status=success&request_token=one&request_token=two",
        )) assertEquals(LoginNavigation.Block, policy.classify("http://127.0.0.1:8000/api/kite/callback?$query"))
        assertEquals(LoginNavigation.Block, policy.classify("http://127.0.0.1.evil.example/?state=nonce&status=success&request_token=synthetic"))
        assertEquals(LoginNavigation.Block, policy.classify("http://192.168.1.1/?state=nonce&status=success&request_token=synthetic"))
    }
    @Test fun `requires the exact registered localhost origin and path`() {
        val exact = DeviceLoginPolicy("http://127.0.0.1:8000/api/kite/callback", "nonce")
        for (base in listOf("http://127.0.0.1:8001/api/kite/callback", "http://127.0.0.1:8000/other", "https://127.0.0.1:8000/api/kite/callback", "http://localhost:8000/api/kite/callback")) {
            assertEquals(LoginNavigation.Block, exact.classify("$base?state=nonce&status=success&request_token=synthetic"))
        }
    }
    @Test fun `rejects lookalikes and unsafe navigation`() {
        for (url in listOf(
            "http://kite.trade/connect/login", "https://kite.trade.evil.example/connect/login",
            "https://kite.trade@evil.example/", "https://evil.example/",
            "https://www.google.com.evil.example/?request_token=synthetic",
            "https://www.google.com:444/", "https://www.google.com/other",
            "javascript:alert(1)", "file:///etc/passwd", "not a URI",
        )) assertEquals(url, LoginNavigation.Block, policy.classify(url))
    }
}
