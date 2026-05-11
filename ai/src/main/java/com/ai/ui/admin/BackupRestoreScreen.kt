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
import com.ai.ui.shared.RestartAppDialog
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.restartApp
import com.ai.ui.shared.shareExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** True when no provider is active yet (first-run / dead-key
     *  state). Hides the Backup half of the screen and renames
     *  the title — there's nothing meaningful to back up before
     *  the user has at least one working API key, but Restore
     *  from a previous device's backup is exactly the use case. */
    restoreOnly: Boolean = false
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirm by remember { mutableStateOf<android.net.Uri?>(null) }
    // Set once a Restore finishes successfully. The popup blocks every
    // other interaction until the user taps OK to restart — the
    // in-memory state is out of sync with the freshly-restored disk
    // state at this point.
    var restartMessage by remember { mutableStateOf<String?>(null) }

    restartMessage?.let { msg ->
        RestartAppDialog(message = msg, onConfirm = { restartApp(context) })
    }

    fun runBackup() {
        busyLabel = "Backing up…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    shareExport(
                        context = context,
                        fileName = BackupManager.defaultFileName(),
                        mimeType = "application/zip",
                        chooserTitle = "Share backup"
                    ) { out -> BackupManager.backup(context, out) }
                }
            }
            busyLabel = null
            val msg = result.fold(
                onSuccess = { "Backup ready — pick a destination from the share sheet" },
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
                                    restartMessage = "Restored ${s.prefsFiles} prefs + ${s.dataFiles} files"
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
        TitleBar(
            helpTopic = "backup_restore",
            title = if (restoreOnly) "Restore" else "Backup & Restore",
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (restoreOnly) "Restore" else "Backup & Restore",
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                    Text(
                        text = if (restoreOnly) {
                            "Reads a single .zip from a previous backup and overwrites all current configuration, API keys, reports, chats, and traces. Pick the file via the Android picker, confirm the prompt, and the app restarts when restore finishes."
                        } else {
                            "Saves a single .zip with everything: configuration, API keys, reports, chats, traces, and prompt cache. Tap Backup, then pick a destination from the system share sheet — Email, Drive, Files, Slack, anything installed. Local LLM .task files and LiteRT .tflite embedders are excluded — they're large and re-downloadable."
                        },
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    // Red, unmissable: the backup zip is plaintext and
                    // every API key in the app — provider keys + the
                    // three external-service keys (HuggingFace,
                    // OpenRouter, Artificial Analysis) — rides along.
                    // Anyone with the zip can call your APIs at your
                    // expense.
                    Text(
                        text = "⚠️ The backup contains every API key in plain text. Don't share it — anyone with the file can call the APIs on your accounts. Store the file like a password.",
                        fontSize = 12.sp,
                        color = AppColors.Red,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!restoreOnly) {
                            Button(
                                onClick = { runBackup() },
                                enabled = busyLabel == null,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
                            ) { Text("Backup", maxLines = 1, softWrap = false) }
                        }
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
