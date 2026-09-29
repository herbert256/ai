package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.restartApp
import com.ai.viewmodel.AppViewModel

// The former ResetScreen hub is gone — its five leaf screens below are now
// reached directly from the merged Manage-data hub (see ManageDataScreen.kt).

/** Non-dismissable progress dialog shown while a wipe runs in the
 *  background (the wipes used to run inside the confirm tap and ANR'd). */
@Composable
private fun WipeBusyDialog(title: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        confirmButton = {}
    )
}

@Composable
fun ResetRuntimeDataScreen(
    /** Starts the wipe; the callback fires on Main with the counts, or null
     *  when the wipe failed. */
    onClearRuntimeData: (onDone: (AppViewModel.RuntimeWipeResult?) -> Unit) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    if (busy) WipeBusyDialog("Clearing runtime data…")

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Clear runtime data?") },
            text = { Text("This permanently deletes the app logs, chat history, API traces, AI reports, per-report audit logs, prompt history, usage statistics, and the last \"Test all models\" run. Anything still running on a report (generation, fan-out, tournament, translation, …) is stopped first. Configuration (providers, agents, flocks, swarms, parameters, system + internal + example prompts, API keys), knowledge bases, the ten Info-provider caches, the per-provider model-list cache, and the local semantic-search embedding cache are all kept.") },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        showConfirm = false
                        busy = true
                        onClearRuntimeData { r ->
                            busy = false
                            Toast.makeText(
                                context,
                                if (r == null) "Clear runtime data failed — see the app log"
                                else "Cleared ${r.logs} log files, ${r.chats} chats, ${r.traces} traces, ${r.reports} reports, ${r.audit} audit logs, ${r.prompts} prompt entries, ${r.testModels} test results, usage statistics",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    enabled = !busy,
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Clear", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "reset_runtime", title = "Clear runtime data", subject = "Drop history; keeps config & API keys", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Wipes the activity + personal-history surface that accumulates while the app is in use: rolling app logs, chat sessions, API traces, AI reports (incl. their secondary-result rows), prompt history, usage statistics, and the last \"Test all models\" run. Configuration (providers, agents, flocks, swarms, system / internal / example prompts, parameters, API keys), knowledge bases, the ten Info-provider pricing caches, the per-provider model-list cache, and the local semantic-search embedding cache are all preserved.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            OutlinedButton(
                onClick = { showConfirm = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Clear runtime data", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun ResetInfoProvidersScreen(
    /** Starts the wipe; the callback fires on Main with success / failure. */
    onClearInfoProviders: (onDone: (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    if (busy) WipeBusyDialog("Clearing Info providers…")

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Reset Info providers?") },
            text = { Text("Drops every refreshed tier of the ten Info providers (OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis, Requesty, llm-stats, genai-prices, TrueFoundry) and deletes the OpenRouter model-specs cache. Each tier goes back to the snapshot bundled with this app version — what a fresh install uses — until you run Refresh again. Manual cost overrides and Together's native pricing are preserved.") },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        showConfirm = false
                        busy = true
                        onClearInfoProviders { ok ->
                            busy = false
                            Toast.makeText(
                                context,
                                if (ok) "Info providers reset to the bundled snapshot" else "Reset Info providers failed — see the app log",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !busy,
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Reset", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "reset_info_providers", title = "Reset Info providers", subject = "Back to the bundled catalogs; refetch on Refresh", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Drops the refreshed pricing tier blobs and timestamps of the ten Info providers — OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis, Requesty, llm-stats, genai-prices, TrueFoundry — and deletes the OpenRouter model-specs cache. Each tier then reloads the snapshot bundled with this app version (a fresh install's catalogs; shown as \"never fetched\") until Housekeeping → Refresh fetches current ones, and the model prices / capability flags are rebuilt from them in the background. Manual cost overrides survive (they sit above the Info tiers in the layered lookup) and Together's native self-reported pricing also survives.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            OutlinedButton(
                onClick = { showConfirm = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Reset Info providers", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun ResetConfigurationScreen(
    /** Starts the wipe; the callback fires on Main with the counts, or null
     *  when the wipe failed. */
    onClearConfiguration: (onDone: (AppViewModel.ConfigWipeResult?) -> Unit) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    if (busy) WipeBusyDialog("Clearing configuration…")

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Clear all configuration?") },
            text = { Text("This permanently deletes every provider's API key, models, endpoints, plus all agents, flocks, swarms, parameters, prompts, system prompts, External Services keys (HuggingFace, OpenRouter), user name, default email, and every installed Local LLM (.task) and LiteRT model (.tflite). Reports, chats, traces, and usage statistics are kept.") },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        showConfirm = false
                        busy = true
                        onClearConfiguration { r ->
                            busy = false
                            Toast.makeText(
                                context,
                                if (r == null) "Clear all configuration failed — see the app log"
                                else "Configuration cleared, ${r.localLlms} local LLM${if (r.localLlms == 1) "" else "s"} and ${r.embedders} LiteRT model${if (r.embedders == 1) "" else "s"} removed",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    enabled = !busy,
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Clear all", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "reset_configuration", title = "Clear all configuration", subject = "Wipe all config; keeps reports & chats", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Wipes every piece of the app's configuration surface: each provider's API key + model list + endpoint set; every agent, flock, swarm, parameter preset, system prompt, internal prompt, example prompt; External Services keys (HuggingFace, OpenRouter, Artificial Analysis); user name and default email; every installed Local LLM (.task) and LiteRT embedder (.tflite). Reports, chats, traces, usage statistics, and the Info-provider pricing caches are kept.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            OutlinedButton(
                onClick = { showConfirm = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Clear all configuration", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun ResetAssetsScreen(
    onRestartProvidersFromAsset: () -> Int,
    onResetInternalPromptsFromAsset: () -> Int,
    onResetExamplePromptsFromAsset: () -> Int,
    onResetSystemPromptsFromAsset: () -> Int,
    onResetDefaultMetaItemsFromAsset: () -> Int,
    onResetWorkersFromAsset: () -> Int,
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
                OutlinedButton(
                    onClick = {
                        val n = when (target) {
                            AssetReset.PROVIDERS -> onRestartProvidersFromAsset()
                            AssetReset.PROMPTS -> onResetInternalPromptsFromAsset()
                            AssetReset.EXAMPLES -> onResetExamplePromptsFromAsset()
                            AssetReset.SYSTEM_PROMPTS -> onResetSystemPromptsFromAsset()
                            AssetReset.DEFAULT_META -> onResetDefaultMetaItemsFromAsset()
                            AssetReset.WORKERS -> onResetWorkersFromAsset()
                        }
                        pending = null
                        val msg = when {
                            n < 0 -> "Could not read ${target.assetPath}"
                            n == 0 -> "${target.assetPath} read OK but had no ${target.itemNoun}"
                            else -> "Loaded $n ${target.itemNoun} from ${target.assetPath}"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    },
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Restore", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "reset_assets", title = "assets/*.json", subject = "Restore providers/prompts from defaults", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Restore one of the bundled JSON catalogs to its as-shipped contents. Each button drops every entry in the matching list and reloads from the asset; user-authored entries in that list are lost. Other configuration (API keys, agents, etc.) is untouched — these buttons are scoped to a single list each.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            OutlinedButton(
                onClick = { pending = AssetReset.PROVIDERS },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("back to assets/providers/", maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { pending = AssetReset.PROMPTS },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("back to assets/internal-prompts/", maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { pending = AssetReset.EXAMPLES },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("back to assets/prompts/examples/", maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { pending = AssetReset.SYSTEM_PROMPTS },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("back to assets/prompts/system/", maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { pending = AssetReset.DEFAULT_META },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("back to assets/meta.json", maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { pending = AssetReset.WORKERS },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("back to assets/workers/", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun ResetApplicationScreen(
    onResetApplication: ((success: Boolean, message: String) -> Unit) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** Wired by AppNavHost to AppViewModel.startRefreshAll + navigate
     *  to the Refresh screen — for the "Refresh all" button on the
     *  post-reset action banner. */
    onStartRefreshAll: () -> Unit = {},
    /** Wired by AppNavHost to AppViewModel.startRefreshWorkers + nav,
     *  for the "Refresh providers, model lists & default agents"
     *  button. */
    onStartRefreshWorkers: () -> Unit = {},
    /** Open the Import / Export screen — used by the post-reset
     *  "Import API keys" button so the user can re-seed keys from a
     *  bundle backup without leaving the flow. */
    onNavigateToImportExport: () -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Non-null after a successful reset → renders the 4-button action
    // banner at the top of the page (no modal). The on-disk state is
    // fresh but the in-memory singletons are stale until the user taps
    // one of the buttons — three lead to a restart eventually, one
    // navigates them into Import/Export to seed API keys first.
    var restartMessage by remember { mutableStateOf<String?>(null) }

    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Resetting…") },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {}
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Reset application?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Factory-style reset: API keys are preserved (per-provider plus HuggingFace, OpenRouter, Artificial Analysis); everything else is wiped and providers + internal prompts are reloaded fresh from app assets. Run Housekeeping → Refresh afterwards if you want to repopulate pricing catalogs, model lists, and the default agents flock.")
                    Text(
                        "Lost: agents, flocks, swarms, parameters, system prompts, custom-added providers, per-agent API key overrides, custom endpoints, all reports, chats, traces, knowledge bases, embeddings, prompt history, usage stats, pricing/model-list caches, Local LLM and LiteRT models.",
                        fontSize = 12.sp, color = AppColors.TextTertiary
                    )
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        showConfirm = false
                        busy = true
                        onResetApplication { success, message ->
                            busy = false
                            if (success) restartMessage = message
                            else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !busy,
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Reset", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "reset_application", title = "Reset application", subject = "Factory reset; only API keys are kept", onBackClick = onBack)

        restartMessage?.let { msg ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$msg — pick what should happen next:",
                    fontSize = 12.sp, color = AppColors.TextTertiary
                )
                OutlinedButton(
                    onClick = { restartMessage = null; onStartRefreshAll() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Refresh all", maxLines = 1, softWrap = false) }
                OutlinedButton(
                    onClick = { restartMessage = null; onStartRefreshWorkers() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Refresh providers, model lists & default agents", maxLines = 1, softWrap = false) }
                OutlinedButton(
                    onClick = { restartApp(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Restart application", maxLines = 1, softWrap = false) }
                OutlinedButton(
                    onClick = { restartMessage = null; onNavigateToImportExport() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Import API keys", maxLines = 1, softWrap = false) }
            }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Factory-style reset. API keys (per-provider + HuggingFace + OpenRouter + Artificial Analysis) survive — everything else is wiped, providers and internal prompts reload from assets. A confirm dialog gates the action; on success a banner appears at the top of the page with four follow-ups: Refresh all, Refresh providers/models/default agents, Restart application, or Import API keys.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            OutlinedButton(
                onClick = { showConfirm = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Reset application", maxLines = 1, softWrap = false) }
        }
    }
}

private enum class AssetReset(val assetPath: String, val itemNoun: String, val dialogBody: String) {
    PROVIDERS(
        "assets/providers/", "providers",
        "Drops every provider definition currently in the registry (including any hand-edited fields) and reloads the bundled assets/providers/ verbatim. Per-provider API keys, model lists, and agents are stored separately and will survive."
    ),
    PROMPTS(
        "assets/internal-prompts/", "internal prompts",
        "Drops every Internal prompt (including any you customized) and reloads the bundled assets/internal-prompts/ tree fresh."
    ),
    EXAMPLES(
        "assets/prompts/examples/", "example prompts",
        "Drops every Example prompt (including any you authored) and reloads the bundled assets/prompts/examples/ fresh."
    ),
    SYSTEM_PROMPTS(
        "assets/prompts/system/", "system prompts",
        "Drops every System prompt (including any you authored) and reloads the bundled assets/prompts/system/ fresh."
    ),
    DEFAULT_META(
        "assets/meta.json", "default meta items",
        "Drops every Default meta item (including any you authored) and reloads the bundled assets/meta.json fresh."
    ),
    WORKERS(
        "assets/workers/", "swarms & flocks",
        "Drops every Swarm and Flock currently configured (including any you authored and the default agents flock) and reloads the whole bundled assets/workers/ tree — swarms/ + flocks/. Flock members are re-matched to your agents by name. Agents, providers, and prompts are untouched; regenerate the default agents flock via Housekeeping → Refresh if you need it back."
    )
}
