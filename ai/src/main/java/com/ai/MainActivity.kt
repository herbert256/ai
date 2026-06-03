package com.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.data.CrashReporter
import com.ai.data.SharedContent
import com.ai.ui.navigation.AppNavHost
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.shareText
import com.ai.ui.theme.AppTheme
import com.ai.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    private val externalTitle = mutableStateOf<String?>(null)
    private val externalSystem = mutableStateOf<String?>(null)
    private val externalPrompt = mutableStateOf<String?>(null)
    private val externalInstructions = mutableStateOf<String?>(null)
    private val sharedContent = mutableStateOf<SharedContent?>(null)

    // statusBarColor / navigationBarColor are deprecated on API 35+ (ignored under
    // enableEdgeToEdge, where the window background is used instead — see the SideEffect),
    // but still take effect on older releases, so we keep setting them.
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Install the crash recorder before anything else can throw, so a
        // startup crash still lands a shareable report.
        CrashReporter.init(applicationContext)
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
            // User-controlled "Full screen" setting is the only thing
            // that hides the Android status bar now. The earlier
            // per-View counter mechanism (LocalStatusBarHideCount)
            // has been removed — View screens no longer hide the
            // system bar at all.
            val hideStatusBar = uiState.generalSettings.fullScreen
            val appBackground = AppColors.AppBackground
            SideEffect {
                val systemBarColor = appBackground.toArgb()
                val useDarkSystemBarIcons = appBackground.luminance() > 0.5f
                window.statusBarColor = systemBarColor
                window.navigationBarColor = systemBarColor
                // Under enableEdgeToEdge() on Android 15+ the deprecated
                // statusBarColor / navigationBarColor are ignored and the
                // system-bar areas show the *window* background instead
                // (the Scaffold is inset below them via statusBarsPadding).
                // Paint the window background with the App Background color
                // too, so changing it on the UI Colors screen recolours the
                // top Android bar live. This SideEffect re-runs whenever the
                // AppBackground snapshot state changes.
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(systemBarColor))
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = useDarkSystemBarIcons
                    isAppearanceLightNavigationBars = useDarkSystemBarIcons
                }
            }
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
              // Paint the whole window — including the area behind the
              // transparent status bar — with AppBackground via Compose.
              // Under enableEdgeToEdge() the deprecated window.statusBarColor
              // is ignored and window.setBackgroundDrawable doesn't reliably
              // repaint the bar live, so the top Android bar wouldn't follow
              // an App Background change. This Compose-driven background does,
              // exactly like the in-app content.
              Box(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)) {
                Scaffold(
                    // Pad the status bar only when it's visible — when
                    // hidden the inset shrinks to 0 so there's no slot
                    // to reserve. Both system bars share AppBackground
                    // so drawing under the gesture pill stays consistent.
                    //
                    // imePadding() is the app-wide keyboard fix: under
                    // enableEdgeToEdge() the window never resizes for the
                    // IME, so the whole content shrinks above the keyboard
                    // here instead. Combined with the foundation text
                    // field's built-in bring-into-view, a focused field
                    // (report prompt, chat composer, edit screens, …)
                    // scrolls up above the keyboard rather than sitting
                    // hidden behind it.
                    modifier = if (hideStatusBar) Modifier.fillMaxSize().imePadding()
                               else Modifier.fillMaxSize().statusBarsPadding().imePadding()
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
              }

                // Surface a crash report saved on the previous run — one tap
                // to share it (out to email / notes / wherever) so it can be
                // analysed. Read off the main thread; the file is tiny.
                val crashContext = LocalContext.current
                var pendingCrash by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    pendingCrash = withContext(Dispatchers.IO) { CrashReporter.peekPendingCrashReport() }
                }
                pendingCrash?.let { report ->
                    AlertDialog(
                        onDismissRequest = { CrashReporter.clearPendingCrashReport(); pendingCrash = null },
                        title = { Text("The app crashed last time") },
                        text = { Text("A crash report was saved. Share it so the problem can be analysed? It contains app version, device, locale and the error — no prompts or report content.") },
                        confirmButton = {
                            TextButton(onClick = {
                                shareText(crashContext, report, "AI crash report")
                                CrashReporter.clearPendingCrashReport()
                                pendingCrash = null
                            }) { Text("Share", maxLines = 1, softWrap = false) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                CrashReporter.clearPendingCrashReport()
                                pendingCrash = null
                            }) { Text("Dismiss", maxLines = 1, softWrap = false) }
                        }
                    )
                }
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
