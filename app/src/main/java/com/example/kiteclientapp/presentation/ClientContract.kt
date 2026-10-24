package com.example.kiteclientapp.presentation

import com.example.kiteclientapp.domain.*

enum class PortfolioTab { Holdings, Positions }
data class ClientState(
    val account: Account? = null,
    val portfolio: Portfolio? = null,
    val busy: Boolean = false,
    val restoringSession: Boolean = false,
    val awaitingLogin: Boolean = false,
    val tab: PortfolioTab = PortfolioTab.Holdings,
    val query: String = "",
    val holdingsSort: SortOrder = SortOrder(),
    val positionsSort: SortOrder = SortOrder(),
    val error: String? = null,
    val updatedAt: Long? = null,
)

sealed interface ClientIntent {
    data object Login : ClientIntent
    data object Demo : ClientIntent
    data class LoginCallback(val uri: String) : ClientIntent
    data object LoginFailed : ClientIntent
    data object CancelLogin : ClientIntent
    data object Refresh : ClientIntent
    data object Logout : ClientIntent
    data class SelectTab(val tab: PortfolioTab) : ClientIntent
    data class Search(val query: String) : ClientIntent
    data class SortBy(val field: SortField) : ClientIntent
    data object ToggleSortDirection : ClientIntent
}

sealed interface ClientEffect {
    data class OpenLogin(val url: String, val onDevice: Boolean = false) : ClientEffect
}

sealed interface ClientMutation {
    data object StartupReady : ClientMutation
    data object Ready : ClientMutation
    data object Working : ClientMutation
    data class Waiting(val waiting: Boolean) : ClientMutation
    data class SignedIn(val account: Account) : ClientMutation
    data class Loaded(val portfolio: Portfolio, val at: Long) : ClientMutation
    data class Failed(val message: String) : ClientMutation
    data class SignedOut(val message: String? = null) : ClientMutation
    data class Tab(val tab: PortfolioTab) : ClientMutation
    data class Query(val query: String) : ClientMutation
    data class Sorting(val tab: PortfolioTab, val order: SortOrder) : ClientMutation
}

fun reduce(state: ClientState, mutation: ClientMutation): ClientState = when (mutation) {
    ClientMutation.StartupReady -> state.copy(busy = false, restoringSession = false)
    ClientMutation.Ready -> state.copy(busy = false, error = null)
    ClientMutation.Working -> state.copy(busy = true, error = null)
    is ClientMutation.Waiting -> state.copy(awaitingLogin = mutation.waiting, error = null)
    is ClientMutation.SignedIn -> ClientState(
        account = mutation.account,
        busy = true,
        holdingsSort = state.holdingsSort,
        positionsSort = state.positionsSort
    )

    is ClientMutation.Loaded -> state.copy(
        portfolio = mutation.portfolio,
        busy = false,
        error = null,
        updatedAt = mutation.at
    )

    is ClientMutation.Failed -> state.copy(
        busy = false,
        awaitingLogin = false,
        error = mutation.message
    )

    is ClientMutation.SignedOut -> ClientState(
        error = mutation.message,
        holdingsSort = state.holdingsSort,
        positionsSort = state.positionsSort
    )

    is ClientMutation.Tab -> state.copy(tab = mutation.tab, query = "")
    is ClientMutation.Query -> state.copy(query = mutation.query)
    is ClientMutation.Sorting -> when {
        mutation.order.field !in sortFields(mutation.tab) -> state
        mutation.tab == PortfolioTab.Holdings -> state.copy(holdingsSort = mutation.order)
        else -> state.copy(positionsSort = mutation.order)
    }
}

fun ClientState.sortOrder(tab: PortfolioTab = this.tab): SortOrder =
    if (tab == PortfolioTab.Holdings) holdingsSort else positionsSort
