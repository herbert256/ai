package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

/** Reset hub. Each card drills into its own full screen with its
 *  own help topic. Wipe semantics live in the leaf screens — this
 *  one is pure navigation. */
@Composable
fun ResetScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenRuntimeData: () -> Unit,
    onOpenInfoProviders: () -> Unit,
    onOpenConfiguration: () -> Unit,
    onOpenAssets: () -> Unit,
    onOpenApplication: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "reset", title = "Reset", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavCard("Clear runtime data", onClick = onOpenRuntimeData)
            NavCard("Clear Info providers", onClick = onOpenInfoProviders)
            NavCard("Clear all configuration", onClick = onOpenConfiguration)
            NavCard("assets/*.json", onClick = onOpenAssets)
            NavCard("Reset application", onClick = onOpenApplication)
        }
    }
}

@Composable
fun ResetRuntimeDataScreen(
    onClearRuntimeData: () -> AppViewModel.RuntimeWipeResult,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Clear runtime data?") },
            text = { Text("This permanently deletes the app logs, chat history, API traces, AI reports, prompt history, and usage statistics. Configuration (providers, agents, flocks, swarms, parameters, system + internal + example prompts, API keys), knowledge bases, the six Info-provider caches, the per-provider model-list cache, and the local semantic-search embedding cache are all kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        val r = onClearRuntimeData()
                        showConfirm = false
                        Toast.makeText(
                            context,
                            "Cleared ${r.logs} log files, ${r.chats} chats, ${r.traces} traces, ${r.reports} reports, ${r.prompts} prompt entries, usage statistics",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Clear", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "reset_runtime", title = "Clear runtime data", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Wipes the activity + personal-history surface that accumulates while the app is in use: rolling app logs, chat sessions, API traces, AI reports (incl. their secondary-result rows), prompt history, and usage statistics. Configuration (providers, agents, flocks, swarms, system / internal / example prompts, parameters, API keys), knowledge bases, the six Info-provider pricing caches, the per-provider model-list cache, and the local semantic-search embedding cache are all preserved.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            Button(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
            ) { Text("Clear runtime data", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun ResetInfoProvidersScreen(
    onClearInfoProviders: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Clear Info providers?") },
            text = { Text("This permanently deletes every cached tier from the six Info providers (OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis) and the OpenRouter model-specs cache. Manual cost overrides and Together's native pricing are preserved. Until you run Refresh again, pricing lookups will fall back to DEFAULT.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearInfoProviders()
                        showConfirm = false
                        Toast.makeText(context, "Info-provider caches cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Clear", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "reset_info_providers", title = "Clear Info providers", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Drops the per-provider pricing tier blobs and prefs entries from the six Info providers — OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis — plus the OpenRouter model-specs cache. Manual cost overrides survive (they sit above the Info tiers in the layered lookup) and Together's native self-reported pricing also survives. Pricing lookups will fall back to DEFAULT_PRICING until Housekeeping → Refresh repopulates the caches.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            Button(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
            ) { Text("Clear Info providers", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun ResetConfigurationScreen(
    onClearConfiguration: () -> AppViewModel.ConfigWipeResult,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Clear all configuration?") },
            text = { Text("This permanently deletes every provider's API key, models, endpoints, plus all agents, flocks, swarms, parameters, prompts, system prompts, External Services keys (HuggingFace, OpenRouter), user name, default email, and every installed Local LLM (.task) and LiteRT model (.tflite). Reports, chats, traces, and usage statistics are kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        val r = onClearConfiguration()
                        showConfirm = false
                        Toast.makeText(
                            context,
                            "Configuration cleared, ${r.localLlms} local LLM${if (r.localLlms == 1) "" else "s"} and ${r.embedders} LiteRT model${if (r.embedders == 1) "" else "s"} removed",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Clear all", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "reset_configuration", title = "Clear all configuration", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Wipes every piece of the app's configuration surface: each provider's API key + model list + endpoint set; every agent, flock, swarm, parameter preset, system prompt, internal prompt, example prompt; External Services keys (HuggingFace, OpenRouter, Artificial Analysis); user name and default email; every installed Local LLM (.task) and LiteRT embedder (.tflite). Reports, chats, traces, usage statistics, and the Info-provider pricing caches are kept.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            Button(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
            ) { Text("Clear all configuration", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun ResetAssetsScreen(
    onRestartProvidersFromAsset: () -> Int,
    onResetInternalPromptsFromAsset: () -> Int,
    onResetExamplePromptsFromAsset: () -> Int,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var pending by remember { mutableStateOf<AssetReset?>(null) }

    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore from ${target.assetPath}?") },
            text = { Text(target.dialogBody) },
            confirmButton = {
                Button(
                    onClick = {
                        val n = when (target) {
                            AssetReset.PROVIDERS -> onRestartProvidersFromAsset()
                            AssetReset.PROMPTS -> onResetInternalPromptsFromAsset()
                            AssetReset.EXAMPLES -> onResetExamplePromptsFromAsset()
                        }
                        pending = null
                        val msg = if (n >= 0) "Loaded $n ${target.itemNoun} from ${target.assetPath}"
                        else "Could not read ${target.assetPath}"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Restore", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "reset_assets", title = "assets/*.json", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Restore one of the three bundled JSON catalogs to its as-shipped contents. Each button drops every entry in the matching list and reloads from the asset; user-authored entries in that list are lost. Other configuration (API keys, agents, etc.) is untouched — these buttons are scoped to a single list each.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            Button(
                onClick = { pending = AssetReset.PROVIDERS },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
            ) { Text("back to assets/providers.json", maxLines = 1, softWrap = false) }
            Button(
                onClick = { pending = AssetReset.PROMPTS },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
            ) { Text("back to assets/prompts.json", maxLines = 1, softWrap = false) }
            Button(
                onClick = { pending = AssetReset.EXAMPLES },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
            ) { Text("back to assets/examples.json", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun ResetApplicationScreen(
    onResetApplication: ((success: Boolean, message: String) -> Unit) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    var confirmText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var restartMessage by remember { mutableStateOf<String?>(null) }

    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Resetting…") },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {}
        )
    }

    restartMessage?.let { msg -> RestartAppDialog(message = msg, onConfirm = { restartApp(context) }) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false; confirmText = "" },
            title = { Text("Reset application?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Factory-style reset: API keys are preserved (per-provider plus HuggingFace, OpenRouter, Artificial Analysis); everything else is wiped and providers + internal prompts are reloaded fresh from app assets. Run Housekeeping → Refresh afterwards if you want to repopulate pricing catalogs, model lists, and the default agents flock.")
                    Text(
                        "Lost: agents, flocks, swarms, parameters, system prompts, custom-added providers, per-agent API key overrides, custom endpoints, all reports, chats, traces, knowledge bases, embeddings, prompt history, usage stats, pricing/model-list caches, Local LLM and LiteRT models.",
                        fontSize = 12.sp, color = AppColors.TextTertiary
                    )
                    Text("Type RESET to confirm:", fontSize = 12.sp)
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedFieldColors()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        confirmText = ""
                        busy = true
                        onResetApplication { success, message ->
                            busy = false
                            if (success) restartMessage = message
                            else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = confirmText.trim() == "RESET" && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                ) { Text("Reset", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false; confirmText = "" }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "reset_application", title = "Reset application", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Factory-style reset. API keys (per-provider + HuggingFace + OpenRouter + Artificial Analysis) survive — everything else is wiped, providers and internal prompts reload from assets, then the Refresh-all chain runs (catalogs → provider tests → model lists → default-agents flock). A type-to-confirm dialog gates the action; the app force-restarts on success so every singleton picks up the fresh state.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            Button(
                onClick = { showConfirm = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
            ) { Text("Reset application", maxLines = 1, softWrap = false) }
        }
    }
}

/** Plain NavCard for the Reset hub. Mirrors the Housekeeping hub
 *  shape so the two levels read consistently. */
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

private enum class AssetReset(val assetPath: String, val itemNoun: String, val dialogBody: String) {
    PROVIDERS(
        "assets/providers.json", "providers",
        "Drops every provider definition currently in the registry (including any hand-edited fields) and reloads the bundled assets/providers.json verbatim. Per-provider API keys, model lists, and agents are stored separately and will survive."
    ),
    PROMPTS(
        "assets/prompts.json", "internal prompts",
        "Drops every Internal prompt (including any you customized) and reloads the bundled assets/prompts.json fresh."
    ),
    EXAMPLES(
        "assets/examples.json", "example prompts",
        "Drops every Example prompt (including any you authored) and reloads the bundled assets/examples.json fresh."
    )
}
