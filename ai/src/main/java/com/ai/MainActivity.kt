package com.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.data.SharedContent
import com.ai.ui.navigation.AppNavHost
import com.ai.viewmodel.AppViewModel
import com.ai.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val externalTitle = mutableStateOf<String?>(null)
    private val externalSystem = mutableStateOf<String?>(null)
    private val externalPrompt = mutableStateOf<String?>(null)
    private val externalInstructions = mutableStateOf<String?>(null)
    private val sharedContent = mutableStateOf<SharedContent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only process the launch intent on a fresh start. After a configuration change
        // (rotation, locale switch, etc.) onCreate runs again with the same launch intent
        // — handling it a second time re-imports the shared content the user already
        // consumed, which surfaces as the chat composer suddenly re-populating with a
        // shared file or text the user just dismissed.
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val viewModel: AppViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            val fullScreen = uiState.generalSettings.fullScreen
            // View screens publish a hide-status-bar request via the
            // LocalStatusBarHideCount counter (incremented per active
            // ViewScreenTitleBar). Combine with the user's Full screen
            // setting: hide whenever either is asking for it.
            val statusBarHideCount = remember { mutableIntStateOf(0) }
            val hideStatusBar = fullScreen || statusBarHideCount.intValue > 0
            // Apply / restore the Android status-bar hide based on the
            // combined signal. WindowInsetsControllerCompat is
            // idempotent so re-running on every recomposition where
            // the derived flag changed is fine.
            LaunchedEffect(hideStatusBar) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                if (hideStatusBar) {
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.statusBars())
                }
            }

            AppTheme {
                CompositionLocalProvider(
                    com.ai.ui.shared.LocalStatusBarHideCount provides statusBarHideCount
                ) {
                Scaffold(
                    // Pad the status bar only when it's visible — when
                    // hidden the inset shrinks to 0 so there's no slot
                    // to reserve. Both system bars share the app's
                    // #0A0A0A background so drawing under the gesture
                    // pill stays visually consistent.
                    modifier = if (hideStatusBar) Modifier.fillMaxSize()
                               else Modifier.fillMaxSize().statusBarsPadding()
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
                        sharedContent = sharedContent.value,
                        onSharedContentHandled = { sharedContent.value = null },
                        appViewModel = viewModel
                    )
                }
                } // close CompositionLocalProvider for LocalStatusBarHideCount
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            "com.ai.ACTION_NEW_REPORT" -> {
                externalTitle.value = intent.getStringExtra("title")
                externalSystem.value = intent.getStringExtra("system")
                externalPrompt.value = intent.getStringExtra("prompt")
                externalInstructions.value = intent.getStringExtra("instructions")
            }
            Intent.ACTION_SEND -> {
                val uri = uriExtra(intent, Intent.EXTRA_STREAM)
                sharedContent.value = SharedContent(
                    text = intent.getStringExtra(Intent.EXTRA_TEXT),
                    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
                    uris = listOfNotNull(uri?.toString()),
                    mime = intent.type
                )
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = uriListExtra(intent, Intent.EXTRA_STREAM)
                sharedContent.value = SharedContent(
                    text = intent.getStringExtra(Intent.EXTRA_TEXT),
                    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
                    uris = uris.map { it.toString() },
                    mime = intent.type
                )
            }
        }
    }

    /** API-level-aware Uri parcelable extractor. The newer typed
     *  variant landed in API 33; below that we fall back to the
     *  deprecated cast which still works. */
    @Suppress("DEPRECATION")
    private fun uriExtra(intent: Intent, key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Uri::class.java)
        else intent.getParcelableExtra(key) as? Uri

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun uriListExtra(intent: Intent, key: String): List<Uri> =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(key, Uri::class.java) ?: emptyList()
        else (intent.getParcelableArrayListExtra<Uri>(key) ?: arrayListOf())
}
