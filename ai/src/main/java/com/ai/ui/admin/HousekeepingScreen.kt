package com.ai.ui.admin

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.data.*
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.*

@Composable
fun HousekeepingScreen(
    onBackToHome: () -> Unit,
    onClearConfiguration: () -> Unit = {}
) {
    BackHandler { onBackToHome() }
    val context = LocalContext.current
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showClearConfigConfirm by remember { mutableStateOf(false) }
    var daysToKeepText by remember { mutableStateOf("30") }
    val daysToKeep = daysToKeepText.toIntOrNull()

    if (showClearConfigConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfigConfirm = false },
            title = { Text("Clear all configuration?") },
            text = { Text("This permanently deletes every provider's API key, models, endpoints, plus all agents, flocks, swarms, parameters, prompts, system prompts, External Services keys (HuggingFace, OpenRouter), user name, and default email. Reports, chats, traces, and usage statistics are kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearConfiguration()
                        showClearConfigConfirm = false
                        Toast.makeText(context, "Configuration cleared", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Clear all", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showClearConfigConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Clear all runtime data?") },
            text = { Text("This permanently deletes all reports, chat history, API traces, and usage statistics. Configuration (providers, agents, flocks, swarms, prompts, parameters, API keys) is kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        val reports = ReportStorage.getAllReports(context).also { list ->
                            list.forEach { ReportStorage.deleteReport(context, it.id) }
                        }
                        val chats = ChatHistoryManager.deleteAllSessions()
                        ApiTracer.clearTraces()
                        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                        SettingsPreferences(prefs, context.filesDir).clearUsageStats()
                        showClearAllConfirm = false
                        Toast.makeText(context, "Cleared ${reports.size} reports, $chats chats, traces & usage stats", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Clear all", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showClearAllConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Housekeeping", onBackClick = onBackToHome, onAiClick = onBackToHome)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        onClick = {
                            val days = daysToKeep ?: return@Button
                            val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
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
                        enabled = daysToKeep != null && daysToKeep > 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
                    ) { Text("Clear Reports/Chats/Traces", maxLines = 1, softWrap = false) }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                            val settingsPrefs = SettingsPreferences(prefs, context.filesDir)
                            settingsPrefs.clearUsageStats()
                            Toast.makeText(context, "Usage statistics cleared", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                    ) { Text("Clear Usage Statistics", maxLines = 1, softWrap = false) }

                    Button(
                        onClick = { showClearAllConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                    ) { Text("Clear all runtime data", maxLines = 1, softWrap = false) }

                    Button(
                        onClick = { showClearConfigConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                    ) { Text("Clear all configuration", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
