package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
 * Full-screen overlay that lets the user pick the export format (HTML / PDF / JSON) and,
 * when relevant, the detail level (Short / Medium / Comprehensive) before kicking off
 * the export. The actual export call is delegated via `onExport`; while it's running we
 * show a progress dialog driven by the (done, total) updates the export pushes.
 */
@Composable
fun ReportExportScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onExport: suspend (ReportExportFormat, ReportExportDetail, ReportExportAction, (Int, Int) -> Unit) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(ReportExportFormat.HTML) }
    var detail by rememberSaveable { mutableStateOf(ReportExportDetail.MEDIUM) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Export", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Format", fontWeight = FontWeight.Bold, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReportExportFormat.entries.forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(f.name) }
                        )
                    }
                }
            }
        }

        if (format != ReportExportFormat.JSON) {
            Spacer(modifier = Modifier.height(12.dp))
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
                            ReportExportDetail.SHORT -> "Title, prompt, and the result for each provider/model. Nothing else."
                            ReportExportDetail.MEDIUM -> "Standard report: results, citations, search snippets, related questions."
                            ReportExportDetail.COMPREHENSIVE -> "Index, prompt, cost table, per-model intro + result + redacted request/response JSON & headers, about footer."
                        },
                        fontSize = 12.sp, color = AppColors.TextTertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        fun runExport(action: ReportExportAction) {
            val pickedFormat = format
            val pickedDetail = detail
            scope.launch {
                progress = 0 to 1
                try {
                    onExport(pickedFormat, pickedDetail, action) { d, t -> progress = d to t }
                    progress = null
                    onBack()
                } catch (e: Exception) {
                    android.util.Log.e("ReportExport", "Export failed", e)
                    progress = null
                    android.widget.Toast.makeText(
                        context,
                        "Export failed: ${e.javaClass.simpleName}: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { runExport(ReportExportAction.SHARE) },
                enabled = progress == null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
            ) { Text("Android share", maxLines = 1, softWrap = false) }

            Button(
                onClick = { runExport(ReportExportAction.VIEW) },
                enabled = progress == null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text("View in browser", maxLines = 1, softWrap = false) }
        }
    }
}
