package com.example.kiteclientapp

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.kiteclientapp.presentation.DeviceLoginActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

class DeviceLoginErrorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
    @Test fun registeredLoopbackCallbackIsConsumedWithoutOpeningALocalServer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, DeviceLoginActivity::class.java)
            .putExtra("login_url", "https://kite.zerodha.com/connect/login?api_key=synthetic&redirect_params=state%3Dnonce")
        ActivityScenario.launch<DeviceLoginActivity>(intent).use { scenario ->
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Kite sign in").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val view = checkNotNull(findWebView(activity.window.decorView))
                view.stopLoading()
                val request = object : WebResourceRequest {
                    override fun getUrl(): Uri = Uri.parse("http://127.0.0.1:8000/api/kite/callback?state=nonce&status=success&request_token=synthetic")
                    override fun isForMainFrame() = true
                    override fun isRedirect() = true
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders(): Map<String, String> = emptyMap()
                }
                assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request))
                assertTrue(activity.isFinishing)
            }
        }
    }
    @Test fun httpErrorRemainsVisibleInsteadOfClosingLogin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, DeviceLoginActivity::class.java)
            .putExtra("login_url", "https://kite.zerodha.com/connect/login?api_key=synthetic&redirect_params=state%3Dnonce")
        ActivityScenario.launch<DeviceLoginActivity>(intent).use { scenario ->
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Kite sign in").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val view = checkNotNull(findWebView(activity.window.decorView))
                view.stopLoading()
                val request = object : WebResourceRequest {
                    override fun getUrl(): Uri = Uri.parse("https://kite.trade/connect/login")
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders(): Map<String, String> = emptyMap()
                }
                view.webViewClient.onReceivedHttpError(view, request,
                    WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0))))
                assertFalse(activity.isFinishing)
            }
            compose.onNodeWithText("Kite returned HTTP 403.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Restart sign-in").assertIsDisplayed()
        }
    }
}
