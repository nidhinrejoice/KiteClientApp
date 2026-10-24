package com.example.kiteclientapp.presentation

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kiteclientapp.BuildConfig
import com.example.kiteclientapp.ui.theme.KiteClientAppTheme
import org.json.JSONObject
import java.io.File

class DeviceLoginActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var completed = false
    private var closing = false
    private var errorMessage by mutableStateOf<String?>(null)
    private lateinit var policy: DeviceLoginPolicy

    @SuppressLint("SetJavaScriptEnabled") // Interactive Kite login needs JS. HTTPS navigation is restricted; no JS bridge is installed.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val loginUrl = intent.getStringExtra("login_url") ?: return fail()
        val login = Uri.parse(loginUrl)
        if (login.scheme != "https" || login.host != "kite.zerodha.com" || login.path != "/connect/login" || login.userInfo != null) return fail()
        val redirectParameters = login.getQueryParameter("redirect_params").orEmpty()
        val expectedState = Uri.parse("https://state.invalid/?$redirectParameters").getQueryParameter("state")
        if (expectedState.isNullOrBlank()) return fail()
        policy = DeviceLoginPolicy(BuildConfig.KITE_REDIRECT_URL, expectedState)
        setContent {
            KiteClientAppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Kite sign in", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { closing = true; finish() }) { Text("Cancel") }
                        }
                        errorMessage?.let { message ->
                            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { errorMessage = null; webView?.loadUrl(loginUrl) }) { Text("Restart sign-in") }
                                }
                            }
                        }
                        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                            WebView(context).apply {
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        if (!request.isForMainFrame) return false
                                        return when (policy.classify(request.url.toString())) {
                                            LoginNavigation.Callback -> {
                                                if (!completed) {
                                                    completed = true
                                                    view.stopLoading()
                                                    setResult(RESULT_OK, Intent().putExtra("login_callback", checkNotNull(policy.callbackResult(request.url.toString()))))
                                                    finish()
                                                }
                                                true
                                            }
                                            LoginNavigation.Continue -> { errorMessage = null; false }
                                            LoginNavigation.Block -> {
                                                record("blocked_navigation", request.url)
                                                errorMessage = "Sign-in tried to open an unsupported destination (${safeHost(request.url)}). Restart sign-in or check the registered redirect URL."
                                                true
                                            }
                                        }
                                    }
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame && !completed && !closing) {
                                            record("network_error", request.url, error.errorCode)
                                            errorMessage = "Kite sign-in could not load (network error ${error.errorCode}). Check your connection and restart sign-in."
                                        }
                                    }
                                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                        if (request.isForMainFrame && !completed && !closing && response.statusCode >= 400) {
                                            record("http_error", request.url, response.statusCode)
                                            // Keep the response visible: it may explain a credential or authorisation problem.
                                            errorMessage = "Kite returned HTTP ${response.statusCode}. Review its message below, or restart sign-in."
                                        }
                                    }
                                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                                        handler.cancel()
                                        if (!completed && !closing) {
                                            record("tls_error", null, error.primaryError)
                                            errorMessage = "The secure connection to Kite could not be verified. Check the device date and connection, then restart sign-in."
                                        }
                                    }
                                }
                                loadUrl(loginUrl)
                            }
                        })
                    }
                }
            }
        }
    }
    private fun safeHost(uri: Uri): String = uri.host?.take(150)?.filter { it.isLetterOrDigit() || it in ".-" } ?: "unknown host"
    private fun record(reason: String, uri: Uri?, code: Int? = null) {
        // Only hostname and numeric error are retained. No URL path, query, token, page body, password, or TOTP.
        runCatching {
            val diagnostic = JSONObject().put("reason", reason).put("host", uri?.let(::safeHost))
                .put("code", code).put("time", System.currentTimeMillis())
            File(filesDir, "kite-login-diagnostic.json").writeText(diagnostic.toString())
        }
    }
    private fun fail() {
        if (completed) return
        completed = true
        setResult(RESULT_CANCELED, Intent().putExtra("login_failed", true))
        finish()
    }
    override fun onDestroy() {
        closing = true
        webView?.apply { stopLoading(); clearHistory(); clearCache(true); removeAllViews(); destroy() }
        webView = null
        if (!isChangingConfigurations) CookieManager.getInstance().removeAllCookies(null)
        super.onDestroy()
    }
}
