package com.ai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
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
import com.ai.ui.AiNavHost
import com.ai.ui.AiViewModel
import com.ai.ui.SettingsPreferences
import com.ai.ui.theme.AiTheme

class MainActivity : ComponentActivity() {
    // Reactive state for external intent parameters
    private val externalTitle = mutableStateOf<String?>(null)
    private val externalPrompt = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Set system bars to black
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        // Handle initial intent
        handleIntent(intent)

        // Read initial full screen mode setting
        val prefs = getSharedPreferences(SettingsPreferences.PREFS_NAME, MODE_PRIVATE)
        val initialFullScreen = prefs.getBoolean("full_screen_mode", false)
        applyFullScreenMode(initialFullScreen)

        setContent {
            val viewModel: AiViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()

            // Apply full screen mode when setting changes
            LaunchedEffect(uiState.generalSettings.fullScreenMode) {
                applyFullScreenMode(uiState.generalSettings.fullScreenMode)
            }

            AiTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                ) { innerPadding ->
                    AiNavHost(
                        modifier = Modifier.padding(innerPadding),
                        externalTitle = externalTitle.value,
                        externalPrompt = externalPrompt.value,
                        onExternalIntentHandled = {
                            // Clear after navigation so subsequent normal navigation works
                            externalTitle.value = null
                            externalPrompt.value = null
                        },
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    private fun applyFullScreenMode(fullScreen: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (fullScreen) {
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle new intent when activity is already running (singleTop mode)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.ai.ACTION_NEW_REPORT") {
            externalTitle.value = intent.getStringExtra("title")
            externalPrompt.value = intent.getStringExtra("prompt")
        }
    }
}
