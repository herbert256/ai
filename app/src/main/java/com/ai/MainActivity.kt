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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.ai.ui.AiNavHost
import com.ai.ui.theme.AiTheme

class MainActivity : ComponentActivity() {
    // Reactive state for external intent parameters
    private val externalTitle = mutableStateOf<String?>(null)
    private val externalPrompt = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle initial intent
        handleIntent(intent)

        setContent {
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
                        }
                    )
                }
            }
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
