package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.BackupManager
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirm by remember { mutableStateOf<android.net.Uri?>(null) }

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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "backup_restore", title = "Backup & Restore", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup & Restore", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Saves a single .zip with everything: configuration, API keys, reports, chats, traces, and prompt cache. The Android picker lets you pick Google Drive (or any cloud-storage app you have installed) as the destination. Local LLM .task files and LiteRT .tflite embedders are excluded — they're large and re-downloadable.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
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
        }
    }
}
