package com.ai.ui.admin

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.ui.shared.TitleBar

/** In-app browser for the bundled mobile-friendly documentation at
 *  `assets/docs/`. WebView loads `file:///android_asset/docs/index.html`;
 *  intra-doc anchor links and cross-doc `.html` links navigate inside
 *  the WebView. JavaScript stays off — the docs are pure HTML + CSS. */
@Composable
fun DocumentationScreen(onBack: () -> Unit) {
    // Hold a WebView reference so the system back button can walk the
    // WebView's own history before exiting the screen — gives the user
    // a normal browser back experience across cross-doc links.
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(
            helpTopic = "documentation",
            title = "Documentation",
            onBackClick = onBack,
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    webViewClient = WebViewClient()
                    webViewRef.value = this
                    loadUrl("file:///android_asset/docs/index.html")
                }
            }
        )
    }
}
