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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TrimByAgeCounts(val reports: Int, val chats: Int, val traces: Int)

/** Unpinned reports older than [cutoff], from the cached header index — no
 *  full parse of every report JSON. Call off the main thread. */
private fun trimmableReportIds(context: android.content.Context, cutoff: Long): List<String> =
    ReportStorage.getReportHeaders(context).filter { !it.pinned && it.timestamp < cutoff }.map { it.id }

/** Unpinned chat sessions last updated before [cutoff], from the cached
 *  session headers. Call off the main thread. */
private fun trimmableChatIds(cutoff: Long): List<String> =
    ChatHistoryManager.getSessionHeaders().filter { !it.pinned && it.updatedAt < cutoff }.map { it.id }

@Composable
fun TrimByAgeScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** Bulk report delete (cancels each report's in-flight work, deletes on
     *  IO); [onComplete] fires on Main once every report is gone. */
    onDeleteReports: (ids: List<String>, onComplete: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daysToKeepText by remember { mutableStateOf("30") }
    val daysToKeep = daysToKeepText.toIntOrNull()
    var showTrimConfirm by remember { mutableStateOf(false) }
    // True while a confirmed trim runs — back is held so the busy dialog
    // and the result toast aren't orphaned halfway through.
    var trimming by remember { mutableStateOf(false) }
    BackHandler { if (!trimming) onBack() }

    if (trimming) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Trimming…") },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {}
        )
    }

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
            // Pinned reports and chats are kept, like Manage → "delete older
            // than" and the chat list's "delete older"; the count and the
            // delete below share one predicate.
            val counts by produceState<TrimByAgeCounts?>(initialValue = null, cutoff) {
                value = withContext(Dispatchers.IO) {
                    TrimByAgeCounts(
                        reports = trimmableReportIds(context, cutoff).size,
                        chats = trimmableChatIds(cutoff).size,
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
                        Text("Permanently deletes everything older than $days day${if (days == 1) "" else "s"} " +
                            "(pinned reports and chats excluded): " +
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
                            trimming = true
                            // The whole trim used to run in this tap on Main — a
                            // full parse of every report plus thousands of file
                            // deletes ANR'd. Ids come off the header indexes on
                            // IO; the report bulk delete runs its own IO job
                            // and calls back once done.
                            scope.launch {
                                val (reportIds, chatIds) = withContext(Dispatchers.IO) {
                                    trimmableReportIds(context, cutoff) to trimmableChatIds(cutoff)
                                }
                                val reportsDone = CompletableDeferred<Unit>()
                                onDeleteReports(reportIds) { reportsDone.complete(Unit) }
                                val traces = withContext(Dispatchers.IO) {
                                    chatIds.forEach { ChatHistoryManager.deleteSession(it) }
                                    ApiTracer.deleteTracesOlderThan(cutoff)
                                }
                                reportsDone.await()
                                trimming = false
                                Toast.makeText(
                                    context,
                                    "Deleted ${reportIds.size} reports, ${chatIds.size} chats, $traces traces older than $days days (pinned kept)",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
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
        TitleBar(helpTopic = "trim_by_age", title = "Trim by age", subject = "Delete reports, chats & traces by age", onBackClick = { if (!trimming) onBack() })

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconCardHeader(MetadataDefaults.DELETE, "Trim by age")
                    Text(
                        "Deletes reports, chat sessions, and API trace files older than the cutoff. Pinned reports and chats stay, as do configuration, API keys, knowledge bases, prompt history, and the app log files. The confirmation dialog shows the exact per-kind count first.",
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
                        enabled = daysToKeep != null && daysToKeep > 0 && !trimming,
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) { Text("Clear Reports/Chats/Traces", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
