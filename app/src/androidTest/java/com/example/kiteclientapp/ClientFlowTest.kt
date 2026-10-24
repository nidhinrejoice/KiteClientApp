package com.example.kiteclientapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

class ClientFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun openDemo() {
        // Session restoration finishes asynchronously before the login controls appear.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Explore a demo portfolio").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Your portfolio").fetchSemanticsNodes().isNotEmpty() }
        assumeTrue("The login-to-demo test requires a signed-out app; preserve an existing broker session.", compose.onAllNodesWithText("Explore a demo portfolio").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Explore a demo portfolio").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Your portfolio").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun scrollTo(text: String) { compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText(text)) }
    @Test fun demoSearchDetailsPositionsAndLogout() {
        openDemo()
        scrollTo("Search holdings")
        compose.onNodeWithText("Search holdings").performTextInput("RELIANCE")
        val holding = hasText("RELIANCE") and hasClickAction() and !hasSetTextAction()
        compose.onNodeWithTag("portfolio-list").performScrollToNode(holding)
        compose.onNode(holding).performClick()
        compose.onNodeWithText("Total return").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        scrollTo("Search holdings")
        compose.onNodeWithText("Search holdings").performTextReplacement("no-such-symbol")
        scrollTo("No matches found")
        compose.onNodeWithText("No matches found").assertIsDisplayed()
        scrollTo("Positions (1)")
        compose.onNodeWithText("Positions (1)").performClick()
        scrollTo("NIFTY DEMO")
        compose.onNodeWithText("NIFTY DEMO").assertIsDisplayed()
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("Sign out"))
        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Stay signed in").assertIsDisplayed()
        compose.onAllNodesWithText("Sign out").onLast().performClick()
        compose.onNodeWithText("Connect with Zerodha  →").performScrollTo().assertIsDisplayed()
    }
}
