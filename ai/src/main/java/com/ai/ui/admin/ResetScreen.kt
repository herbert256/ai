package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.RestartAppDialog
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.restartApp
import com.ai.viewmodel.AppViewModel

@Composable
fun ResetScreen(
    onClearRuntimeData: () -> AppViewModel.RuntimeWipeResult,
    onClearConfiguration: () -> AppViewModel.ConfigWipeResult,
    onResetApplication: ((success: Boolean, message: String) -> Unit) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showClearConfigConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var resetConfirmText by remember { mutableStateOf("") }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    // Forces a restart after Reset completes — the in-memory state is
    // wholesale replaced, so every singleton has to come back fresh
    // from disk.
    var restartMessage by remember { mutableStateOf<String?>(null) }

    busyLabel?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(label) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {}
        )
    }

    restartMessage?.let { msg ->
        RestartAppDialog(message = msg, onConfirm = { restartApp(context) })
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Clear activity logs?") },
            text = { Text("This permanently deletes the app logs, chat history, API traces, and usage statistics. Reports, knowledge bases, prompt history, the six Info-provider caches, the per-provider model-list cache, and the local semantic-search embedding cache are all kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        val r = onClearRuntimeData()
                        showClearAllConfirm = false
                        Toast.makeText(
                            context,
                            "Cleared ${r.logs} log files, ${r.chats} chats, ${r.traces} traces, usage statistics",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Clear", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showClearAllConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
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
                        val r = onClearConfiguration()
                        showClearConfigConfirm = false
                        Toast.makeText(
                            context,
                            "Configuration cleared, ${r.localLlms} local LLM${if (r.localLlms == 1) "" else "s"} and ${r.embedders} LiteRT model${if (r.embedders == 1) "" else "s"} removed",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Clear all", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showClearConfigConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    if (showResetConfirm) {
        // Type-to-confirm: extra friction for a destructive op that
        // wipes essentially everything but API keys. Reset enables only
        // when the user types RESET (case-sensitive, trimmed).
        AlertDialog(
            onDismissRequest = { showResetConfirm = false; resetConfirmText = "" },
            title = { Text("Reset application?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Factory-style reset: API keys are preserved (per-provider plus HuggingFace, OpenRouter, Artificial Analysis); everything else is wiped and providers + internal prompts are reloaded fresh from app assets. Run Housekeeping → Refresh afterwards if you want to repopulate pricing catalogs, model lists, and the default agents flock."
                    )
                    Text(
                        "Lost: agents, flocks, swarms, parameters, system prompts, custom-added providers, per-agent API key overrides, custom endpoints, all reports, chats, traces, knowledge bases, embeddings, prompt history, usage stats, pricing/model-list caches, Local LLM and LiteRT models.",
                        fontSize = 12.sp,
                        color = AppColors.TextTertiary
                    )
                    Text("Type RESET to confirm:", fontSize = 12.sp)
                    OutlinedTextField(
                        value = resetConfirmText,
                        onValueChange = { resetConfirmText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedFieldColors()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        resetConfirmText = ""
                        busyLabel = "Resetting…"
                        onResetApplication { success, message ->
                            busyLabel = null
                            if (success) {
                                restartMessage = message
                            } else {
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = resetConfirmText.trim() == "RESET" && busyLabel == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                ) { Text("Reset", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false; resetConfirmText = "" }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "reset", title = "Reset", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Clear activity logs", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Wipes the app logs, chats, API traces, and usage statistics. Reports, knowledge bases, prompt history, the six Info-provider caches, model-list cache, and semantic-search cache are kept.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = { showClearAllConfirm = true },
                        enabled = busyLabel == null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                    ) { Text("Clear activity logs", maxLines = 1, softWrap = false) }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Clear all configuration", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Wipes every provider's API key, models, endpoints; agents, flocks, swarms, parameters, system prompts; External Services keys; user name and default email; every installed Local LLM and LiteRT model. Reports, chats, traces, and usage statistics are kept.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = { showClearConfigConfirm = true },
                        enabled = busyLabel == null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                    ) { Text("Clear all configuration", maxLines = 1, softWrap = false) }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reset application", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Factory-style reset. API keys (per-provider + HuggingFace + OpenRouter + Artificial Analysis) are preserved; everything else is wiped and providers + internal prompts are reloaded from assets. Type-to-confirm dialog. Run Housekeeping → Refresh afterwards to repopulate catalogs.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = { showResetConfirm = true },
                        enabled = busyLabel == null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                    ) { Text("Reset application", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
