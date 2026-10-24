package com.example.kiteclientapp.presentation

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kiteclientapp.data.KiteConfiguration
import com.example.kiteclientapp.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ClientViewModel @Inject constructor(
    private val signIn: SignIn,
    private val signOut: SignOut,
    private val restoreSession: RestoreSession,
    private val loadPortfolio: LoadPortfolio,
    private val configuration: KiteConfiguration,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ClientState(
            holdingsSort = readSort(PortfolioTab.Holdings),
            positionsSort = readSort(PortfolioTab.Positions),
            busy = true,
            restoringSession = true,
            awaitingLogin = savedState.get<String>("login_state") != null
        )
    )
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<ClientEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private fun commit(mutation: ClientMutation) {
        mutableState.update { reduce(it, mutation) }
    }

    private val initialized = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            try {
                val account = restoreSession()
                if (account == null) commit(ClientMutation.StartupReady)
                else {
                    savedState.remove<String>("login_state")
                    commit(ClientMutation.SignedIn(account))
                    refresh()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionExpired) {
                signOut.clearSession()
                commit(ClientMutation.SignedOut("Your Kite session has expired. Please sign in again."))
            } catch (error: Exception) {
                // A network outage must not discard a valid restored account or its saved token.
                if (state.value.account == null) commit(ClientMutation.StartupReady)
                commit(ClientMutation.Failed(if (error is java.io.IOException) "Unable to connect. Check your connection and try again." else "Could not restore your session. Please try again."))
            } finally {
                initialized.complete(Unit)
            }
        }
    }

    fun accept(intent: ClientIntent) {
        if (state.value.restoringSession) {
            // Returning browser callbacks and user taps wait for restoration instead of being dropped.
            viewModelScope.launch { initialized.await(); accept(intent) }
            return
        }
        when (intent) {
            is ClientIntent.SelectTab -> commit(ClientMutation.Tab(intent.tab))
            is ClientIntent.Search -> commit(ClientMutation.Query(intent.query))
            is ClientIntent.SortBy -> {
                val current = state.value.sortOrder()
                val order = if (current.field == intent.field) current else SortOrder(
                    intent.field,
                    intent.field.defaultDirection
                )
                updateSort(state.value.tab, order)
            }

            ClientIntent.ToggleSortDirection -> {
                val current = state.value.sortOrder()
                updateSort(state.value.tab, current.copy(direction = current.direction.reversed()))
            }

            ClientIntent.LoginFailed -> {
                savedState.remove<String>("login_state"); commit(ClientMutation.Failed("Unable to open Kite sign-in. Check your connection and try again."))
            }

            ClientIntent.CancelLogin -> {
                savedState.remove<String>("login_state"); commit(ClientMutation.Waiting(false))
            }

            else -> {
                if (state.value.busy) return
                // Set synchronously so rapid taps cannot start competing session operations.
                commit(ClientMutation.Working)
                viewModelScope.launch {
                    try {
                        when (intent) {
                            ClientIntent.Login -> beginLogin()
                            ClientIntent.Demo -> {
                                savedState.remove<String>("login_state")
                                commit(ClientMutation.SignedIn(signIn.demo())); refresh()
                            }

                            is ClientIntent.LoginCallback -> completeLogin(intent.uri)
                            ClientIntent.Refresh -> if (state.value.account != null) refresh() else commit(
                                ClientMutation.SignedOut()
                            )

                            ClientIntent.Logout -> {
                                try {
                                    signOut()
                                } finally {
                                    commit(ClientMutation.SignedOut())
                                }
                            }

                            else -> Unit
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: SessionExpired) {
                        signOut.clearSession()
                        commit(ClientMutation.SignedOut("Your Kite session has expired. Please sign in again."))
                    } catch (error: Exception) {
                        val message = when {
                            intent == ClientIntent.Logout -> "Signed out on this device. Could not revoke the remote session."
                            error is ConfigurationMissing -> "Login is not configured. Set kite.apiKey and kite.apiSecret in local.properties and rebuild the app."
                            error is java.io.IOException -> "Unable to connect. Check your connection and try again."
                            else -> "Could not complete the request. Please try again."
                        }
                        commit(ClientMutation.Failed(message))
                    }
                }
            }
        }
    }

    private fun readSort(tab: PortfolioTab): SortOrder {
        val prefix = "sort_${tab.name}_"
        val field = savedState.get<String>(prefix + "field")
            ?.let { name -> SortField.entries.find { it.name == name } }
            ?.takeIf { it in sortFields(tab) } ?: SortField.Name
        val direction = savedState.get<String>(prefix + "direction")
            ?.let { name -> SortDirection.entries.find { it.name == name } }
            ?: field.defaultDirection
        return SortOrder(field, direction)
    }

    private fun updateSort(tab: PortfolioTab, order: SortOrder) {
        commit(ClientMutation.Sorting(tab, order))
        val actual = state.value.sortOrder(tab)
        savedState["sort_${tab.name}_field"] = actual.field.name
        savedState["sort_${tab.name}_direction"] = actual.direction.name
    }

    private suspend fun beginLogin() {
        if (!signIn.isAvailable()) throw ConfigurationMissing()
        val nonce = UUID.randomUUID().toString()
        savedState["login_state"] = nonce
        val url = Uri.parse("https://kite.zerodha.com/connect/login").buildUpon()
            .appendQueryParameter("v", "3").appendQueryParameter("api_key", configuration.apiKey)
            .appendQueryParameter("redirect_params", "state=$nonce").build().toString()
        commit(ClientMutation.Ready)
        commit(ClientMutation.Waiting(true))
        effectChannel.send(ClientEffect.OpenLogin(url, signIn.usesDeviceSecret))
    }

    private suspend fun completeLogin(value: String) {
        val uri = Uri.parse(value)
        val expected = savedState.get<String>("login_state")
        val nativeCallback =
            uri.scheme == "kiteclient" && uri.host == "auth" && uri.path == "/callback" && uri.port == -1
        val registeredRedirect = Uri.parse(configuration.redirectUrl)
        val deviceCallback =
            signIn.usesDeviceSecret && registeredRedirect.scheme == "https" && registeredRedirect.host != null && uri.scheme == "https" && uri.host.equals(
                registeredRedirect.host,
                ignoreCase = true
            ) &&
                    (if (uri.port == -1) 443 else uri.port) == (if (registeredRedirect.port == -1) 443 else registeredRedirect.port) &&
                    uri.path.orEmpty().ifEmpty { "/" } == registeredRedirect.path.orEmpty()
                .ifEmpty { "/" } && uri.userInfo == null && uri.fragment == null
        if ((!nativeCallback && !deviceCallback) || uri.userInfo != null || uri.fragment != null || expected == null ||
            uri.getQueryParameters("state").size != 1 || uri.getQueryParameter("state") != expected
        ) {
            commit(ClientMutation.Failed("Login could not be verified. Start sign-in again.")); return
        }
        savedState.remove<String>("login_state")
        val token = uri.getQueryParameter("request_token")
        if (uri.getQueryParameter("status") != "success" || token.isNullOrBlank()) {
            commit(ClientMutation.Failed("Sign-in was cancelled or declined. Please try again.")); return
        }
        commit(ClientMutation.SignedIn(signIn(token)))
        refresh()
    }

    private suspend fun refresh() {
        commit(ClientMutation.Loaded(loadPortfolio(), System.currentTimeMillis()))
    }
}
