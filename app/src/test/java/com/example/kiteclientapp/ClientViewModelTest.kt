package com.example.kiteclientapp

import androidx.lifecycle.SavedStateHandle
import com.example.kiteclientapp.data.KiteConfiguration
import com.example.kiteclientapp.domain.*
import com.example.kiteclientapp.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientViewModelTest {
    private class FakeAuth : AuthRepository {
        var cleared = false
        var restored: Account? = null
        override suspend fun restoreSession(): Account? = restored
        override suspend fun exchange(requestToken: String) = error("No browser in unit tests")
        override suspend fun demo() = Account("DEMO", "Demo", true)
        override suspend fun logout() { cleared = true }
        override suspend fun clearSession() { cleared = true }
    }
    private class FakePortfolio : PortfolioRepository {
        var failure: Exception? = null
        var calls = 0
        val snapshot = Portfolio(emptyList(), emptyList())
        override suspend fun load(): Portfolio { calls++; failure?.let { throw it }; return snapshot }
    }
    private val auth = FakeAuth()
    private val portfolio = FakePortfolio()
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun viewModel() = ClientViewModel(SignIn(auth), SignOut(auth), RestoreSession(auth), LoadPortfolio(portfolio), KiteConfiguration("", ""), SavedStateHandle())

    @Test fun `rapid demo taps load one snapshot then logout resets the screen`() = runTest {
        val model = viewModel()
        model.accept(ClientIntent.Demo)
        model.accept(ClientIntent.Demo)
        assertTrue(model.state.value.busy)
        advanceUntilIdle()
        assertEquals(1, portfolio.calls)
        assertTrue(model.state.value.account!!.isDemo)
        assertSame(portfolio.snapshot, model.state.value.portfolio)
        model.accept(ClientIntent.Logout)
        advanceUntilIdle()
        assertTrue(auth.cleared)
        assertEquals(ClientState(), model.state.value)
    }
    @Test fun `refresh failure retains data and expiry clears account`() = runTest {
        val model = viewModel()
        model.accept(ClientIntent.Demo)
        advanceUntilIdle()
        portfolio.failure = java.io.IOException("offline")
        model.accept(ClientIntent.Refresh)
        advanceUntilIdle()
        assertSame(portfolio.snapshot, model.state.value.portfolio)
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.busy)
        portfolio.failure = SessionExpired()
        model.accept(ClientIntent.Refresh)
        advanceUntilIdle()
        assertNull(model.state.value.account)
        assertNull(model.state.value.portfolio)
        assertTrue(auth.cleared)
    }

    @Test fun `startup restores valid account and loads portfolio without interactive login`() = runTest {
        auth.restored = Account("AB1234", "Investor")
        val model = viewModel()
        assertTrue(model.state.value.restoringSession)
        advanceUntilIdle()
        assertEquals(auth.restored, model.state.value.account)
        assertSame(portfolio.snapshot, model.state.value.portfolio)
        assertEquals(1, portfolio.calls)
        assertFalse(model.state.value.restoringSession)
        assertFalse(model.state.value.busy)
    }
    @Test fun `offline startup keeps restored account instead of forcing sign in`() = runTest {
        auth.restored = Account("AB1234", "Investor")
        portfolio.failure = java.io.IOException("offline")
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(auth.restored, model.state.value.account)
        assertNotNull(model.state.value.error)
        assertFalse(auth.cleared)
        assertFalse(model.state.value.busy)
    }
    @Test fun `revoked token on startup clears session and returns to login`() = runTest {
        auth.restored = Account("AB1234", "Investor")
        portfolio.failure = SessionExpired()
        val model = viewModel()
        advanceUntilIdle()
        assertNull(model.state.value.account)
        assertTrue(auth.cleared)
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.restoringSession)
    }

    @Test fun `sort choices survive refresh and saved state recreation`() = runTest {
        auth.restored = Account("AB1234", "Investor")
        val saved = SavedStateHandle()
        fun create() = ClientViewModel(SignIn(auth), SignOut(auth), RestoreSession(auth), LoadPortfolio(portfolio), KiteConfiguration("", ""), saved)
        val model = create()
        advanceUntilIdle()
        model.accept(ClientIntent.SortBy(SortField.DailyPercentage))
        assertEquals(SortOrder(SortField.DailyPercentage, SortDirection.Descending), model.state.value.holdingsSort)
        model.accept(ClientIntent.ToggleSortDirection)
        model.accept(ClientIntent.Refresh)
        advanceUntilIdle()
        assertEquals(SortDirection.Ascending, model.state.value.holdingsSort.direction)
        val recreated = create()
        advanceUntilIdle()
        assertEquals(model.state.value.holdingsSort, recreated.state.value.holdingsSort)
        model.accept(ClientIntent.SelectTab(PortfolioTab.Positions))
        assertEquals(SortOrder(), model.state.value.sortOrder())
    }
}
