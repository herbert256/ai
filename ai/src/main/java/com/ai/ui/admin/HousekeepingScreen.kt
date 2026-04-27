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
    onClearConfiguration: () -> Unit = {}
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
                                    val newProvidersTail = if (s.newProviders > 0) ", +${s.newProviders} new providers from setup.json" else ""
                                    Toast.makeText(
                                        context,
                                        "Restored ${s.prefsFiles} prefs + ${s.dataFiles} files$newProvidersTail. Restarting…",
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
                        PromptCache.clearAll()
                        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                        SettingsPreferences(prefs, context.filesDir).clearUsageStats()
                        showClearAllConfirm = false
                        Toast.makeText(context, "Cleared ${reports.size} reports, $chats chats, traces, prompt cache & usage stats", Toast.LENGTH_LONG).show()
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

            // Backup / restore — uses Android's Storage Access Framework so the
            // user picks the destination on save (Google Drive, OneDrive, local
            // file, etc., all show up as choices when the corresponding app is
            // installed) and the source on restore. We never see the underlying
            // location; SAF gives us a Uri to write to / read from.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup & Restore", fontWeight = FontWeight.Bold, color = Color.White)
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
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trim by age", fontWeight = FontWeight.Bold, color = Color.White)
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
                    Text("Usage statistics", fontWeight = FontWeight.Bold, color = Color.White)
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
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Full reset", fontWeight = FontWeight.Bold, color = Color.White)
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
