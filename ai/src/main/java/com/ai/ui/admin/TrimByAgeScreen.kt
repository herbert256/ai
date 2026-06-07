package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.ChatHistoryManager
import com.ai.data.MetadataDefaults
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.IconCardHeader
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TrimByAgeCounts(val reports: Int, val chats: Int, val traces: Int)

@Composable
fun TrimByAgeScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onDeleteReport: (String) -> Unit = {},
    onDeleteReports: (List<String>) -> Unit = { ids -> ids.forEach(onDeleteReport) }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var daysToKeepText by remember { mutableStateOf("30") }
    val daysToKeep = daysToKeepText.toIntOrNull()
    var showTrimConfirm by remember { mutableStateOf(false) }

    if (showTrimConfirm) {
        val days = daysToKeep
        if (days == null || days <= 0) {
            showTrimConfirm = false
        } else {
            // Snapshot the cutoff once when the dialog opens so the counts
            // shown and the deletion performed use the exact same instant —
            // a per-recomposition recompute could drift across the dialog's
            // lifetime and count-but-not-delete a boundary item.
            val cutoff = remember(days) { System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000 }
            val counts by produceState<TrimByAgeCounts?>(initialValue = null, cutoff) {
                value = withContext(Dispatchers.IO) {
                    TrimByAgeCounts(
                        reports = ReportStorage.getAllReports(context).count { it.timestamp < cutoff },
                        chats = ChatHistoryManager.getAllSessions().count { it.updatedAt < cutoff },
                        traces = ApiTracer.getTraceFiles().count { it.timestamp < cutoff }
                    )
                }
            }
            AlertDialog(
                onDismissRequest = { showTrimConfirm = false },
                title = { Text("Trim by age?") },
                text = {
                    val loadedCounts = counts
                    if (loadedCounts == null) {
                        Text("Counting reports, chats, and trace files older than $days day${if (days == 1) "" else "s"}...")
                    } else {
                        Text("Permanently deletes everything older than $days day${if (days == 1) "" else "s"}: " +
                            "${loadedCounts.reports} report${if (loadedCounts.reports == 1) "" else "s"}, " +
                            "${loadedCounts.chats} chat session${if (loadedCounts.chats == 1) "" else "s"}, " +
                            "${loadedCounts.traces} trace file${if (loadedCounts.traces == 1) "" else "s"}. " +
                            "Cannot be undone.")
                    }
                },
                confirmButton = {
                    OutlinedButton(
                        onClick = {
                            showTrimConfirm = false
                            val reports = ReportStorage.getAllReports(context).filter { it.timestamp < cutoff }
                            onDeleteReports(reports.map { it.id })
                            val chats = ChatHistoryManager.getAllSessions().filter { it.updatedAt < cutoff }
                            chats.forEach { ChatHistoryManager.deleteSession(it.id) }
                            val traces = ApiTracer.deleteTracesOlderThan(cutoff)
                            Toast.makeText(
                                context,
                                "Deleted ${reports.size} reports, ${chats.size} chats, $traces traces older than $days days",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        enabled = counts != null,
                        colors = AppColors.outlinedButtonColors()
                    ) { Text("Trim", maxLines = 1, softWrap = false) }
                },
                dismissButton = { TextButton(onClick = { showTrimConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "trim_by_age", title = "Trim by age", subject = "Delete reports, chats & traces by age", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconCardHeader(MetadataDefaults.DELETE, "Trim by age")
                    Text(
                        "Deletes reports, chat sessions, and API trace files older than the cutoff. Configuration, API keys, knowledge bases, and prompt history stay. The confirmation dialog shows the exact per-kind count first.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    OutlinedTextField(
                        value = daysToKeepText,
                        onValueChange = { v -> daysToKeepText = v.filter { it.isDigit() }.take(4) },
                        label = { Text("Days to keep") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedFieldColors()
                    )
                    OutlinedButton(
                        onClick = { showTrimConfirm = true },
                        enabled = daysToKeep != null && daysToKeep > 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) { Text("Clear Reports/Chats/Traces", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
