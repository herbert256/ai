package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.ChatHistoryManager
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun TrimByAgeScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
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
            val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
            // Counts off the IO thread would be cleaner, but the Trim
            // path runs synchronously today and these reads are
            // already on the UI thread; stay consistent with the
            // existing pattern.
            val rCount = ReportStorage.getAllReports(context).count { it.timestamp < cutoff }
            val cCount = ChatHistoryManager.getAllSessions().count { it.updatedAt < cutoff }
            val tCount = ApiTracer.getTraceFiles().count { it.timestamp < cutoff }
            AlertDialog(
                onDismissRequest = { showTrimConfirm = false },
                title = { Text("Trim by age?") },
                text = {
                    Text("Permanently deletes everything older than $days day${if (days == 1) "" else "s"}: " +
                        "$rCount report${if (rCount == 1) "" else "s"}, " +
                        "$cCount chat session${if (cCount == 1) "" else "s"}, " +
                        "$tCount trace file${if (tCount == 1) "" else "s"}. " +
                        "Cannot be undone.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showTrimConfirm = false
                            val reports = ReportStorage.getAllReports(context).filter { it.timestamp < cutoff }
                            reports.forEach { ReportStorage.deleteReport(context, it.id) }
                            val chats = ChatHistoryManager.getAllSessions().filter { it.updatedAt < cutoff }
                            chats.forEach { ChatHistoryManager.deleteSession(it.id) }
                            val traces = ApiTracer.deleteTracesOlderThan(cutoff)
                            Toast.makeText(
                                context,
                                "Deleted ${reports.size} reports, ${chats.size} chats, $traces traces older than $days days",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
                    ) { Text("Trim", maxLines = 1, softWrap = false) }
                },
                dismissButton = { TextButton(onClick = { showTrimConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "trim_by_age", title = "Trim by age", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trim by age", fontWeight = FontWeight.Bold, color = Color.White)
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
                    Button(
                        onClick = { showTrimConfirm = true },
                        enabled = daysToKeep != null && daysToKeep > 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
                    ) { Text("Clear Reports/Chats/Traces", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
