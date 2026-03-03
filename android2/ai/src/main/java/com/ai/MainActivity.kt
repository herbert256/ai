package com.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.ui.navigation.AppNavHost
import com.ai.viewmodel.AppViewModel
import com.ai.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val externalTitle = mutableStateOf<String?>(null)
    private val externalSystem = mutableStateOf<String?>(null)
    private val externalPrompt = mutableStateOf<String?>(null)
    private val externalInstructions = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        val prefs = getSharedPreferences(AppViewModel.PREFS_NAME, MODE_PRIVATE)
        applyFullScreenMode(prefs.getBoolean("full_screen_mode", false))

        setContent {
            val viewModel: AppViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()

            LaunchedEffect(uiState.generalSettings.fullScreenMode) {
                applyFullScreenMode(uiState.generalSettings.fullScreenMode)
            }

            AppTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize().systemBarsPadding()
                ) { innerPadding ->
                    AppNavHost(
                        modifier = Modifier.padding(innerPadding),
                        externalTitle = externalTitle.value,
                        externalSystem = externalSystem.value,
                        externalPrompt = externalPrompt.value,
                        externalInstructions = externalInstructions.value,
                        onExternalIntentHandled = {
                            externalTitle.value = null
                            externalSystem.value = null
                            externalPrompt.value = null
                            externalInstructions.value = null
                        },
                        appViewModel = viewModel
                    )
                }
            }
        }
    }

    private fun applyFullScreenMode(fullScreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullScreen) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.ai.ACTION_NEW_REPORT") {
            externalTitle.value = intent.getStringExtra("title")
            externalSystem.value = intent.getStringExtra("system")
            externalPrompt.value = intent.getStringExtra("prompt")
            externalInstructions.value = intent.getStringExtra("instructions")
        }
    }
}
