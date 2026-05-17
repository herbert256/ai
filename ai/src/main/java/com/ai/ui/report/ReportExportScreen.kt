package com.ai.ui.report

import com.ai.data.AppLog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.launch

/**
 * Full-screen overlay that lets the user pick the export format (HTML / PDF /
 * MS Word / OpenDocument / JSON) and, when relevant, the detail level (Short /
 * Complete) before kicking off the export. JSON ignores the detail picker;
 * every other format honors it. The actual export call is delegated via
 * `onExport`; while it's running we show a progress dialog driven by the
 * (done, total) updates the export pushes.
 */
@Composable
fun ReportExportScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onExport: suspend (ReportExportFormat, ReportExportDetail, ReportExportAction, (Int, Int) -> Unit) -> Unit,
    onExportAll: suspend ((Int, Int) -> Unit) -> Unit,
    /** Open the in-app HTML viewer (the same screen the AI Report
     *  "HTML" action-row button reaches). Surfaced as a third button
     *  next to Android share / View in browser whenever the format
     *  is HTML; the picked Detail (Short / Complete) is passed
     *  through so the preview renders the same body that Android
     *  share / View in browser would produce. */
    onViewInApp: (ReportExportDetail) -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(ReportExportFormat.HTML) }
    var detail by rememberSaveable { mutableStateOf(ReportExportDetail.COMPLETE) }
    var target by rememberSaveable { mutableStateOf(ReportExportTarget.VIEW_BROWSER) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val showViewInApp = format == ReportExportFormat.HTML
    // If the user had VIEW_APP picked and then flips Format to a
    // non-HTML option, the chip vanishes — reset the selection so
    // we don't render with a hidden chip "selected" and an Export
    // button that fires the wrong path.
    LaunchedEffect(showViewInApp) {
        if (!showViewInApp && target == ReportExportTarget.VIEW_APP) {
            target = ReportExportTarget.VIEW_BROWSER
        }
    }

    progress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("$done / $total", fontSize = 12.sp, color = AppColors.TextTertiary)
                }
            },
            confirmButton = {}
        )
    }

    fun runExport(action: ReportExportAction) {
        val pickedFormat = format
        val pickedDetail = detail
        scope.launch {
            progress = 0 to 1
            try {
                onExport(pickedFormat, pickedDetail, action) { d, t -> progress = d to t }
                progress = null
                // For SHARE the share sheet itself is the user's last action so we
                // collapse this screen away. For VIEW the file just opened in the
                // browser as a separate app — keep the Export screen alive so back
                // from the browser lands here with the format/detail choices intact.
                if (action == ReportExportAction.SHARE) onBack()
            } catch (e: Exception) {
                AppLog.e("ReportExport", "Export failed", e)
                progress = null
                android.widget.Toast.makeText(
                    context,
                    "Export failed: ${e.javaClass.simpleName}: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "report_export", title = "Export", onBackClick = onBack)
        // Primary CTA hoisted to the top — same rule the settings
        // edit screens follow (commit ea047c17). Dispatches based
        // on the Target chip below.
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                when (target) {
                    ReportExportTarget.ANDROID_SHARE -> runExport(ReportExportAction.SHARE)
                    ReportExportTarget.VIEW_BROWSER -> runExport(ReportExportAction.VIEW)
                    ReportExportTarget.VIEW_APP -> onViewInApp(detail)
                }
            },
            enabled = progress == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Export", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Format", fontWeight = FontWeight.Bold, color = Color.White)
                    // FlowRow so OpenDocument doesn't push the row off-screen on
                    // narrow phones — chips wrap to a second line if needed.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ReportExportFormat.entries.forEach { f ->
                            FilterChip(
                                selected = format == f,
                                onClick = { format = f },
                                label = { Text(f.displayName) }
                            )
                        }
                    }
                }
            }

            if (format != ReportExportFormat.JSON && format != ReportExportFormat.ZIPPED_HTML) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Detail", fontWeight = FontWeight.Bold, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ReportExportDetail.entries.forEach { d ->
                                FilterChip(
                                    selected = detail == d,
                                    onClick = { detail = d },
                                    label = { Text(d.name.lowercase().replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                        Text(
                            when (detail) {
                                ReportExportDetail.SHORT -> "Prompt, per-model results (with citations and related questions), and Meta sections (one per Meta prompt) plus Moderations. No index, no costs, no traces."
                                ReportExportDetail.COMPLETE -> "Index, prompt, every Meta section (one per Meta prompt name), Reranks / Moderations / Translations, the cost table, and every captured API trace with redacted bodies."
                            },
                            fontSize = 12.sp, color = AppColors.TextTertiary
                        )
                    }
                }
            }

            // Target card — where the export should land. Mirrors
            // the Detail card's FilterChip layout. The VIEW_APP chip
            // is omitted when the format isn't HTML (the in-app
            // viewer only renders HTML).
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Target", fontWeight = FontWeight.Bold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReportExportTarget.entries
                            .filter { it != ReportExportTarget.VIEW_APP || showViewInApp }
                            .forEach { t ->
                                FilterChip(
                                    selected = target == t,
                                    onClick = { target = t },
                                    label = { Text(t.displayName) }
                                )
                            }
                    }
                    Text(
                        when (target) {
                            ReportExportTarget.ANDROID_SHARE ->
                                "Hand off the rendered file via Android's system share sheet."
                            ReportExportTarget.VIEW_BROWSER ->
                                "Open the rendered file in the system browser (HTML) or a viewer app (PDF / Word / ODT)."
                            ReportExportTarget.VIEW_APP ->
                                "Render the HTML inline in the in-app WebView preview — no external app launched."
                        },
                        fontSize = 12.sp, color = AppColors.TextTertiary
                    )
                }
            }
        }

        // "Export all" — bundle all 8 documents (Short + Complete × HTML
        // / PDF / DOCX / ODT) plus the JSON traces zip into a single
        // master zip and hand it to the standard share sheet.
        Button(
            onClick = {
                scope.launch {
                    progress = 0 to 1
                    try {
                        onExportAll { d, t -> progress = d to t }
                        progress = null
                        onBack()
                    } catch (e: Exception) {
                        AppLog.e("ReportExport", "Export all failed", e)
                        progress = null
                        android.widget.Toast.makeText(
                            context,
                            "Export all failed: ${e.javaClass.simpleName}: ${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            enabled = progress == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) { Text("Export all (zip)", maxLines = 1, softWrap = false) }
    }
}

