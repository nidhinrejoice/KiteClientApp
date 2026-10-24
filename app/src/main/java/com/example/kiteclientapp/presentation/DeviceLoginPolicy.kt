package com.example.kiteclientapp.presentation

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

enum class LoginNavigation { Continue, Callback, Block }

/** Pure navigation policy. Loopback callbacks are captured locally, never loaded over the network. */
class DeviceLoginPolicy(private val registeredRedirect: String, private val expectedState: String) {
    private val kiteHosts = setOf("kite.zerodha.com", "accounts.zerodha.com", "kite.trade", "www.kite.trade")
    fun classify(value: String): LoginNavigation = runCatching {
        val uri = URI(value)
        if (uri.userInfo != null || uri.fragment != null || uri.host == null) return@runCatching LoginNavigation.Block
        val redirect = URI(registeredRedirect)
        fun effectivePort(value: URI) = if (value.port != -1) value.port else if (value.scheme == "https") 443 else 80
        val redirectIsLoopback = redirect.host?.lowercase() in setOf("127.0.0.1", "localhost", "[::1]")
        val safeRedirectScheme = redirect.scheme == "https" || (redirect.scheme == "http" && redirectIsLoopback)
        val registered = safeRedirectScheme && uri.scheme == redirect.scheme && uri.host.equals(redirect.host, true) &&
            uri.path.ifEmpty { "/" } == redirect.path.ifEmpty { "/" } && effectivePort(uri) == effectivePort(redirect)
        if (registered) {
            val query = parameters(uri)
            val validState = expectedState.isNotBlank() && query["state"] == listOf(expectedState)
            val validStatus = query["status"]?.size == 1 && query["status"]?.single() in setOf("success", "error", "cancelled")
            val token = query["request_token"]
            val validToken = if (query["status"] == listOf("success")) token?.size == 1 && token.single().matches(Regex("[A-Za-z0-9_-]{1,1024}")) else token == null || token.size <= 1
            if (validState && validStatus && validToken) LoginNavigation.Callback else LoginNavigation.Block
        } else if (uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.host.lowercase() in kiteHosts) {
            LoginNavigation.Continue
        } else LoginNavigation.Block
    }.getOrDefault(LoginNavigation.Block)

    fun callbackResult(value: String): String? {
        if (classify(value) != LoginNavigation.Callback) return null
        val query = parameters(URI(value))
        // Normalize to the existing native callback; the ViewModel independently checks its pending state.
        val fields = listOf("state", "status", "request_token").mapNotNull { name ->
            query[name]?.singleOrNull()?.let { "$name=${URLEncoder.encode(it, "UTF-8")}" }
        }
        return "kiteclient://auth/callback?${fields.joinToString("&")}"
    }
    private fun parameters(uri: URI): Map<String, List<String>> = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        .map { entry ->
            val parts = entry.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }.groupBy({ it.first }, { it.second })
}
