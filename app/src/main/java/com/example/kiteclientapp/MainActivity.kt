package com.example.kiteclientapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.kiteclientapp.presentation.*
import com.example.kiteclientapp.feature.options.presentation.OptionsWorkspace
import com.example.kiteclientapp.feature.options.presentation.MarketOptionsViewModel
import com.example.kiteclientapp.feature.options.presentation.OptionsViewModel
import com.example.kiteclientapp.ui.theme.KiteClientAppTheme
import com.example.kiteclientapp.feature.scanners.presentation.ScannersViewModel
import com.example.kiteclientapp.feature.scanners.presentation.ScannersScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: ClientViewModel by viewModels()
    private val optionsViewModel: OptionsViewModel by viewModels()
    private val marketOptionsViewModel: MarketOptionsViewModel by viewModels()
    private val scannersViewModel: ScannersViewModel by viewModels()
    private val localLogin =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = result.data?.getStringExtra("login_callback")
            when {
                result.resultCode == RESULT_OK && callback != null -> viewModel.accept(
                    ClientIntent.LoginCallback(
                        callback
                    )
                )

                result.data?.getBooleanExtra("login_failed", false) == true -> viewModel.accept(
                    ClientIntent.LoginFailed
                )

                else -> viewModel.accept(ClientIntent.CancelLogin)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeCallback(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val optionsState by optionsViewModel.state.collectAsStateWithLifecycle()
            val scannersState by scannersViewModel.state.collectAsStateWithLifecycle()
            val marketOptionsState by marketOptionsViewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.account) {
                marketOptionsViewModel.account(state.account?.id); scannersViewModel.account(
                state.account
            )
            }
            LaunchedEffect(viewModel) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.effects.collect { effect ->
                        when (effect) {
                            is ClientEffect.OpenLogin -> {
                                try {
                                    if (effect.onDevice) {
                                        localLogin.launch(
                                            Intent(this@MainActivity, DeviceLoginActivity::class.java)
                                                .putExtra("login_url", effect.url)
                                        )
                                    } else startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            android.net.Uri.parse(effect.url)
                                        )
                                    )
                                } catch (_: android.content.ActivityNotFoundException) {
                                    viewModel.accept(ClientIntent.CancelLogin)
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Install a browser to sign in with Kite.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }
            }
            KiteClientAppTheme {
                ClientScreen(
                    state, viewModel::accept,
                    optionsContent = {
                        OptionsWorkspace(
                            optionsState,
                            optionsViewModel::accept,
                            marketOptionsState,
                            marketOptionsViewModel
                        )
                    },
                    scannersContent = { ScannersScreen(scannersState, scannersViewModel) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeCallback(intent)
    }

    private fun consumeCallback(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.toString()?.let { viewModel.accept(ClientIntent.LoginCallback(it)) }
            intent.data = null
        }
    }
}
