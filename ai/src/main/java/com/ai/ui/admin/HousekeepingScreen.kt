package com.ai.ui.admin

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HousekeepingScreen(
    onBackToHome: () -> Unit,
    onClearConfiguration: () -> Unit = {},
    onNavigateToImportExport: () -> Unit = {},
    onNavigateToRefresh: () -> Unit = {},
    onNavigateToProviderAdmin: () -> Unit = {}
) {
    BackHandler { onBackToHome() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showClearConfigConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf<android.net.Uri?>(null) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var daysToKeepText by remember { mutableStateOf("30") }
    val daysToKeep = daysToKeepText.toIntOrNull()

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyLabel = "Backing up…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        BackupManager.backup(context, out)
                    } ?: error("Could not open output stream")
                }
            }
            busyLabel = null
            val msg = result.fold(
                onSuccess = { "Backup written to selected location" },
                onFailure = { "Backup failed: ${it.javaClass.simpleName}: ${it.message}" }
            )
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    val restorePickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showRestoreConfirm = uri
    }

    showRestoreConfirm?.let { uri ->
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = null },
            title = { Text("Restore from backup?") },
            text = { Text("This overwrites all current configuration, API keys, reports, chats, and traces with the contents of the selected backup. The app will restart automatically when restore finishes.") },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirm = null
                        busyLabel = "Restoring…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        BackupManager.restore(context, input)
                                    } ?: error("Could not open input stream")
                                }
                            }
                            busyLabel = null
                            result.fold(
                                onSuccess = { s ->
                                    Toast.makeText(
                                        context,
                                        "Restored ${s.prefsFiles} prefs + ${s.dataFiles} files. Restarting…",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    // Brief delay so the toast renders, then relaunch the
                                    // launcher activity in a new task and kill the current
                                    // process — the next launch reads the restored data fresh.
                                    kotlinx.coroutines.delay(800)
                                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    if (launch != null) {
                                        launch.addFlags(
                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        )
                                        context.startActivity(launch)
                                    }
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                },
                                onFailure = {
                                    Toast.makeText(
                                        context,
                                        "Restore failed: ${it.javaClass.simpleName}: ${it.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Restore", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = null }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    busyLabel?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(label) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {}
        )
    }

    if (showClearConfigConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfigConfirm = false },
            title = { Text("Clear all configuration?") },
            text = { Text("This permanently deletes every provider's API key, models, endpoints, plus all agents, flocks, swarms, parameters, prompts, system prompts, External Services keys (HuggingFace, OpenRouter), user name, default email, and every installed Local LLM (.task) and LiteRT model (.tflite). Reports, chats, traces, and usage statistics are kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearConfiguration()
                        val llms = LocalLlm.clearAll(context)
                        val embedders = LocalEmbedder.clearAll(context)
                        showClearConfigConfirm = false
                        Toast.makeText(
                            context,
                            "Configuration cleared, $llms local LLM${if (llms == 1) "" else "s"} and $embedders LiteRT model${if (embedders == 1) "" else "s"} removed",
                            Toast.LENGTH_LONG
                        ).show()
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
            text = { Text("This permanently deletes all reports, chat history, API traces, prompt history, knowledge bases, pricing cache (manual overrides plus cached tier blobs), the per-provider model-list cache, and the local semantic-search embedding cache. Configuration (providers, agents, flocks, swarms, prompts, parameters, API keys) and usage statistics are kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        val reports = ReportStorage.getAllReports(context).also { list ->
                            list.forEach { ReportStorage.deleteReport(context, it.id) }
                        }
                        val chats = ChatHistoryManager.deleteAllSessions()
                        ApiTracer.clearTraces()
                        PromptCache.clearAll()
                        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                        val sp = SettingsPreferences(prefs, context.filesDir)
                        sp.clearPromptHistory()
                        sp.clearLastReportPrompt()
                        val kbs = KnowledgeStore.clearAll(context)
                        PricingCache.clearAll(context)
                        ModelListCache.clearAll(context)
                        EmbeddingsStore.clearAll(context)
                        showClearAllConfirm = false
                        Toast.makeText(context, "Cleared ${reports.size} reports, $chats chats, traces, prompt cache & history, $kbs knowledge bases, pricing cache, model-list cache, semantic-search cache", Toast.LENGTH_LONG).show()
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

            NavCard("Export / Import", onClick = onNavigateToImportExport)

            CollapsibleCard("Refresh") {
                Button(
                    onClick = onNavigateToRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                ) { Text("Refresh", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }

            CollapsibleCard("Provider administration") {
                Button(
                    onClick = onNavigateToProviderAdmin,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Provider administration", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }

            // Backup / restore — uses Android's Storage Access Framework so the
            // user picks the destination on save (Google Drive, OneDrive, local
            // file, etc., all show up as choices when the corresponding app is
            // installed) and the source on restore. We never see the underlying
            // location; SAF gives us a Uri to write to / read from.
            CollapsibleCard("Backup & Restore") {
                    Text(
                        "Saves a single .zip with everything: configuration, API keys, reports, chats, traces, and prompt cache. The Android picker lets you pick Google Drive (or any other cloud storage app you have installed) as the destination.",
                        fontSize = 12.sp, color = AppColors.TextTertiary
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { backupLauncher.launch(BackupManager.defaultFileName()) },
                            enabled = busyLabel == null,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
                        ) { Text("Backup", maxLines = 1, softWrap = false) }
                        Button(
                            // Restrict the picker to .zip files. Some providers report
                            // the mime as application/octet-stream rather than
                            // application/zip — accept both, but drop the */* fallback
                            // so unrelated documents don't clutter the picker.
                            onClick = { restorePickLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                            enabled = busyLabel == null,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                        ) { Text("Restore", maxLines = 1, softWrap = false) }
                    }
            }

            CollapsibleCard("Trim by age") {
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

            CollapsibleCard("Usage statistics") {
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
            }

            CollapsibleCard("Manual cost overrides") {
                    Text(
                        "Drops every manual price override that is dormant or redundant: covered by LiteLLM, covered by OpenRouter, equal to the built-in default, or equal to what the lookup would return without it.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = {
                            val n = PricingCache.cleanupRedundantManualOverrides(context)
                            Toast.makeText(
                                context,
                                if (n == 0) "No redundant overrides to remove" else "Removed $n manual cost override${if (n == 1) "" else "s"}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
                    ) { Text("Cleanup manual cost overrides", maxLines = 1, softWrap = false) }
            }

            CollapsibleCard("Reset") {
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

// Local LiteRT models + Local LLMs maintenance moved to AI Setup
// — see ui/settings/LocalRuntimeScreens.kt. They're configuration of
// on-device runtimes, not housekeeping, and naturally belong with
// the rest of the AI configuration cards.

/** Card that doesn't expand — clicking the whole row fires [onClick].
 *  Used by Housekeeping rows whose entire purpose is a single
 *  navigation, so the user shouldn't have to expand-then-tap-button. */
@Composable
private fun NavCard(title: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Text(">", color = AppColors.Blue, fontSize = 16.sp)
        }
    }
}

/** Card that starts collapsed — the title row is always visible and
 *  acts as a click target; tapping reveals [content]. Lets the
 *  Housekeeping screen present six cards as a compact list-of-titles
 *  rather than a long scroll of fully-expanded sections. */
@Composable
private fun CollapsibleCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (expanded) content()
        }
    }
}
