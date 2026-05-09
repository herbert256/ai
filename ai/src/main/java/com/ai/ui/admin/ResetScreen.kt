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
import com.ai.ui.shared.TitleBar
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

    busyLabel?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(label) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {}
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
                        val r = onClearRuntimeData()
                        showClearAllConfirm = false
                        Toast.makeText(
                            context,
                            "Cleared ${r.reports} reports, ${r.chats} chats, traces, prompt cache & history, ${r.knowledgeBases} knowledge bases, pricing cache, model-list cache, semantic-search cache",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Clear all", maxLines = 1, softWrap = false) }
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
                        "Factory-style reset: API keys are preserved (per-provider plus HuggingFace, OpenRouter, Artificial Analysis); everything else is wiped and providers + internal prompts are reloaded fresh from app assets. Finishes by running the Refresh-all chain (catalogs, provider tests, model lists, default agents)."
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
                        onResetApplication { _, message ->
                            busyLabel = null
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                    Text("Clear all runtime data", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Wipes reports, chats, API traces, prompt history, knowledge bases, pricing cache, model-list cache, and the semantic-search cache. Configuration and API keys are kept.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = { showClearAllConfirm = true },
                        enabled = busyLabel == null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                    ) { Text("Clear all runtime data", maxLines = 1, softWrap = false) }
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
                        "Factory-style reset. API keys (per-provider + HuggingFace + OpenRouter + Artificial Analysis) are preserved; everything else is wiped, providers and internal prompts are reloaded from assets, then the Refresh-all chain runs. Type-to-confirm dialog.",
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
