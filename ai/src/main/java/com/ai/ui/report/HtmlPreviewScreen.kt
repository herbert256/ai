package com.ai.ui.report

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inline HTML preview of a report — equivalent to the file produced by
 * Export → HTML → Complete, rendered directly in an embedded WebView
 * with JS enabled so any inline scripts (table sorting, collapsibles,
 * the rerank-anchor highlight) behave as in the standalone export.
 *
 * The HTML is built off the main thread via the same `*FromData`
 * helpers the Export screen feeds and then handed to the WebView once.
 * We scope the WebView lifecycle to the composition: cleanup runs when
 * the user backs out so the renderer doesn't hold onto the report
 * payload after the screen is gone.
 *
 * When [language] is non-null the preview slices the report via
 * [buildLanguageViews] before rendering so the user's One-language
 * pick from the Export screen reaches the WebView; null preserves the
 * pre-refactor multi-language layout (the in-page language picker
 * still works inside the WebView in that case).
 */
@Composable
fun HtmlPreviewScreen(
    reportId: String,
    detail: ReportExportDetail = ReportExportDetail.COMPLETE,
    /** Language filter passed through from the Export screen's
     *  Language card — same encoding as [shareReportAsExport]:
     *  null = all languages, "" = original-only, non-empty = single
     *  named translation. */
    language: String? = null,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val state = produceState<PreviewState>(initialValue = PreviewState.Loading, reportId, detail, language) {
        value = withContext(Dispatchers.IO) {
            val report: Report? = ReportStorage.getReport(context, reportId)
            if (report == null) PreviewState.NotFound
            else {
                val base = buildHtmlReportData(context, report)
                val data: HtmlReportData = if (language == null) base else {
                    val views = buildLanguageViews(base)
                    val targetKey = if (language.isBlank()) LangTab.ORIGINAL_KEY else languageKey(language)
                    views.firstOrNull { it.key == targetKey }?.data ?: base
                }
                val raw = when (detail) {
                    ReportExportDetail.COMPLETE -> convertReportToHtmlFromData(data, getAppVersionForPreview(context))
                    ReportExportDetail.SHORT -> buildShortHtmlFromData(data)
                }
                // Both exporters open with `<h1>title</h1>`
                // immediately after the body wrapper. The title bar
                // already shows the title, so strip the first <h1>
                // in the preview only — the export paths are
                // untouched. Title text is HTML-escaped server side
                // (`esc(...)`) so it never contains a literal `<`,
                // making `[^<]*` a safe inner match.
                val html = raw.replaceFirst(Regex("<h1>[^<]*</h1>"), "")
                PreviewState.Ready(report, html)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val titleSubject = (state.value as? PreviewState.Ready)?.report?.title?.takeIf { it.isNotBlank() }
        val reportIcon = (state.value as? PreviewState.Ready)?.report?.icon?.takeIf { it.isNotBlank() } ?: "📝"
        TitleBar(
            helpTopic = "report_html_preview",
            title = if (detail == ReportExportDetail.SHORT) "HTML preview (short)" else "HTML preview",
            reportIcon = reportIcon,
            subject = titleSubject,
            onBackClick = onBack,
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )

        when (val s = state.value) {
            PreviewState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Building preview…", color = AppColors.TextSecondary, fontSize = 14.sp)
                }
            }
            PreviewState.NotFound -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Report not found", color = AppColors.TextSecondary, fontSize = 16.sp)
                }
            }
            is PreviewState.Ready -> {
                // Single AndroidView keyed off the html length avoids
                // re-instantiating the WebView on every recomposition;
                // we only care that the same WebView keeps showing the
                // same HTML for the lifetime of this screen.
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // No file:// or content:// access — the
                            // HTML is self-contained (data: URIs for
                            // images), and disallowing these closes
                            // the obvious local-file exfiltration
                            // vector if a model ever produces a
                            // crafted <script>.
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            // Match the exported file behaviour:
                            // tapping a link inside the preview
                            // navigates within the WebView (intra-doc
                            // anchors like #sec-2 are how Rerank items
                            // link back to their source agent).
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL(
                                /* baseUrl = */ "about:blank",
                                /* data = */ s.html,
                                /* mimeType = */ "text/html",
                                /* encoding = */ "utf-8",
                                /* historyUrl = */ null
                            )
                        }
                    }
                )
            }
        }
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data object NotFound : PreviewState
    data class Ready(val report: Report, val html: String) : PreviewState
}

private fun getAppVersionForPreview(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (_: Exception) { "?" }
