package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun SetupScreen(
    aiSettings: Settings,
    huggingFaceApiKey: String = "",
    openRouterApiKey: String = "",
    aaApiKey: String = "",
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (Settings) -> Unit = {},
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onSaveArtificialAnalysisApiKey: (String) -> Unit = {},
    onNavigateToCostConfig: () -> Unit = {}
) {
    BackHandler { onBackToSettings() }
    val context = LocalContext.current
    val hasApiKey = remember(aiSettings) { aiSettings.hasAnyApiKey() }
    // Total models across active providers — matches the per-provider counts shown on the
    // Models sub-screen, which only lists active providers.
    val modelCount = remember(aiSettings) {
        aiSettings.getActiveServices().sumOf { aiSettings.getProvider(it).models.size }
    }
    val agentCount = remember(aiSettings.agents) { aiSettings.agents.count { aiSettings.isProviderActive(it.provider) } }
    // Re-read on every ON_RESUME so adding a manual override / installing
    // a model in a sibling screen and coming back here shows the new
    // badge count without an app restart.
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    val costCount = remember(refreshTick) { PricingCache.getAllManualPricing(context).size }
    val externalCount = remember(huggingFaceApiKey, openRouterApiKey, aaApiKey) {
        (if (huggingFaceApiKey.isNotBlank()) 1 else 0) +
        (if (openRouterApiKey.isNotBlank()) 1 else 0) +
        (if (aaApiKey.isNotBlank()) 1 else 0)
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_setup", title = "AI Setup", onBackClick = onBackToSettings)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SetupNavCard("\u2699\uFE0F", "Providers", "API key, state, and default model per provider", "${AppService.entries.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS) })
            SetupNavCard("\uD83E\uDDE0", "Models", "Models, types, and manual overrides", "$modelCount",
                onClick = { onNavigate(SettingsSubScreen.AI_MODELS_SETUP) })
            run {
                val workersCount = agentCount + aiSettings.flocks.size + aiSettings.swarms.size
                SetupNavCard("\uD83D\uDC65", "Workers", "Agents, Flocks, and Swarms", "$workersCount",
                    onClick = { onNavigate(SettingsSubScreen.AI_WORKERS_SETUP) }, enabled = hasApiKey)
            }
            run {
                val promptsCount = aiSettings.systemPrompts.size + aiSettings.internalPrompts.size
                SetupNavCard("\uD83D\uDCDD", "Prompt management", "System, Meta, Fan-out/in, Other and Example prompts", "$promptsCount",
                    onClick = { onNavigate(SettingsSubScreen.AI_PROMPTS_SETUP) })
            }
            SetupNavCard("\uD83C\uDFDB\uFE0F", "Parameters", "Parameter presets", "${aiSettings.parameters.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_PARAMETERS) })
            SetupNavCard("\uD83D\uDCB0", "Costs", "Manual pricing configuration", "$costCount",
                onClick = onNavigateToCostConfig)
            SetupNavCard("\uD83D\uDD11", "External Services", "HuggingFace, OpenRouter keys", "$externalCount",
                onClick = { onNavigate(SettingsSubScreen.AI_EXTERNAL_SERVICES) })
            // Local Models / Model cooldowns / Blocked models /
            // Test-excluded models all live one level deeper under
            // "AI Models setup" \u2014 see ModelsSetupScreen.
        }
    }
}

@Composable
private fun SetupNavCard(icon: String, title: String, description: String, count: String, onClick: () -> Unit, enabled: Boolean = true) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (enabled) AppColors.CardBackgroundAlt else Color(0xFF1A2A3A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 22.sp, modifier = if (enabled) Modifier else Modifier.alpha(0.4f))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else AppColors.TextDim)
                Text(description, fontSize = 12.sp, color = if (enabled) AppColors.TextTertiary else AppColors.TextVeryDim)
            }
            if (count.isNotBlank()) {
                Text(count, fontSize = 14.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (enabled) Text(">", fontSize = 16.sp, color = AppColors.Blue)
        }
    }
}

// ===== Models Setup hub =====

/** Sub-hub under AI Setup that groups every model-related screen:
 *  Models / Model Types / Manual model type overrides, plus the
 *  on-device runtimes (Local Models) and the three model-state lists
 *  (Cooldowns / Blocked / Test-excluded). */
@Composable
fun ModelsSetupScreen(
    aiSettings: Settings,
    hasActiveProvider: Boolean,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    val modelCount = remember(aiSettings) {
        aiSettings.getActiveServices().sumOf { aiSettings.getProvider(it).models.size }
    }
    val liteRtCount = remember(refreshTick) { com.ai.data.local.LocalEmbedder.availableModels(context).size }
    val localLlmCount = remember(refreshTick) { com.ai.data.local.LocalLlm.availableLlms(context).size }
    val cooldownCount by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "setup_models", title = "AI Models setup", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("🧠", "Models", "Source and model list per active provider", "$modelCount",
                onClick = { onNavigate(SettingsSubScreen.AI_MODELS) }, enabled = hasActiveProvider)
            ModelsSetupNavCard("🏷️", "Model Types", "Default API path per type (chat, embedding, ...)", "${com.ai.data.ModelType.ALL.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_MODEL_TYPES) })
            ModelsSetupNavCard("✍️", "Manual model types overrides", "Per-model type assignments that win over autodetection", "${aiSettings.modelTypeOverrides.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_MANUAL_MODEL_TYPES) })
            ModelsSetupNavCard("💻", "Local Models", "On-device LLMs and LiteRT text embedders", "${liteRtCount + localLlmCount}",
                onClick = { onNavigate(SettingsSubScreen.AI_LOCAL_MODELS_SETUP) })
            ModelsSetupNavCard("⏳", "Model cooldowns", "Rate-limited models benched on a >1h 429", "${cooldownCount.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_MODEL_COOLDOWNS) })
            ModelsSetupNavCard("🚫", "Blocked models", "Provider/model pairs flagged as blocked — dimmed in every model picker", "${aiSettings.blockedModels.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_BLOCKED_MODELS) })
            ModelsSetupNavCard("💸", "Test-excluded models", "Skipped by Test all models — auto-added when a probe costs > 5¢", "${aiSettings.testExcludedModels.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_TEST_EXCLUDED_MODELS) })
            ModelsSetupNavCard("🔒", "Inaccessible models", "Not reachable on this account — hidden from every model picker", "${aiSettings.inaccessibleModels.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_INACCESSIBLE_MODELS) })
        }
    }
}

// ===== Workers Setup hub =====

/** Sub-hub under AI Setup that groups the three "worker" entry
 *  points (Agents, Flocks, Swarms). Mirrors [ModelsSetupScreen]'s
 *  pattern so the AI Setup landing page stays compact. */
@Composable
fun WorkersSetupScreen(
    aiSettings: Settings,
    hasApiKey: Boolean,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit
) {
    BackHandler { onBack() }
    val agentCount = remember(aiSettings.agents) { aiSettings.agents.count { aiSettings.isProviderActive(it.provider) } }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "setup_workers", title = "AI Workers", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("🤖", "Agents", "Named AI model configurations", "$agentCount",
                onClick = { onNavigate(SettingsSubScreen.AI_AGENTS) }, enabled = hasApiKey)
            ModelsSetupNavCard("🦆", "Flocks", "Groups of agents", "${aiSettings.flocks.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_FLOCKS) }, enabled = hasApiKey)
            ModelsSetupNavCard("🐝", "Swarms", "Groups of provider/model pairs", "${aiSettings.swarms.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_SWARMS) }, enabled = hasApiKey)
        }
    }
}

// ===== Prompts Setup hub =====

/** Sub-hub under AI Setup that groups every prompt-management entry
 *  point: System Prompts, the three Internal Prompts category buckets
 *  (Meta / Fan out-in / Other), and Example prompts. Same pattern as
 *  [WorkersSetupScreen] and [ModelsSetupScreen]. */
@Composable
fun PromptsSetupScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    /** Set the category that the AI_INTERNAL_PROMPTS list + edit
     *  screens filter on, then navigate to the list. */
    onOpenInternalPrompts: (String) -> Unit,
    /** Forward to the Fan out/in sub-hub. */
    onOpenFanInOutHub: () -> Unit
) {
    BackHandler { onBack() }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "setup_prompts", title = "Prompt management", onBackClick = onBack)

        fun countByCategory(c: String) = aiSettings.internalPrompts.count { it.category == c }
        val fanTotal = countByCategory("fan_out") + countByCategory("fan_in") +
            countByCategory("fan-in-model")

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("🗨️", "System Prompts", "Reusable system prompts", "${aiSettings.systemPrompts.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_SYSTEM_PROMPTS) })
            ModelsSetupNavCard("🧩", "Meta prompts", "Rerank, Summarize, Compare, Moderation — run on the full report", "${countByCategory("meta")}",
                onClick = { onOpenInternalPrompts("meta") })
            ModelsSetupNavCard("🔀", "Fan out/in prompts", "Templates for the Fan out / Fan in flow — across pairs, combined reports, and per-model variants", "$fanTotal",
                onClick = onOpenFanInOutHub)
            ModelsSetupNavCard("🧰", "Other internal prompts", "Templates consumed by app features (Translate, Model info, Intro)", "${countByCategory("internal")}",
                onClick = { onOpenInternalPrompts("internal") })
            ModelsSetupNavCard("🎨", "Icons prompts", "Bundled prompts the icon chains use (report icon, fan-out icon, internal-prompt icon, translation icon). Edit-only — can't be removed or added to.", "${countByCategory("icons")}",
                onClick = { onOpenInternalPrompts("icons") })
            ModelsSetupNavCard("📝", "Example prompts", "Curated (title, text) starters for the New Report flow", "${aiSettings.examplePrompts.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_EXAMPLE_PROMPTS) })
        }
    }
}

/** Sub-hub one level deeper, opened from the "Fan out/in prompts"
 *  card on [InternalPromptsHubScreen]. Holds the five category-
 *  scoped CRUDs the Fan out / Fan in flow consumes:
 *    - fan_out                  — per-pair source-response template
 *    - fan_in                   — combined-report template (one run
 *      per source agent)
 *    - initiator            — per-model initiator template
 *    - requester            — per-model responder template
 *    - model                — combined initiator + responder
 *      template */
@Composable
fun FanInOutPromptsHubScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onOpenInternalPrompts: (String) -> Unit
) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "fan_in_out_prompts_hub", title = "Fan out/in prompts", onBackClick = onBack)

        fun countByCategory(c: String) = aiSettings.internalPrompts.count { it.category == c }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("🔀", "Fan Out", "Run across every pair of report-models", "${countByCategory("fan_out")}",
                onClick = { onOpenInternalPrompts("fan_out") })
            ModelsSetupNavCard("🪢", "Fan in, total", "Combine all fan-out responses into a single report", "${countByCategory("fan_in")}",
                onClick = { onOpenInternalPrompts("fan_in") })
            ModelsSetupNavCard("🧬", "Fan In, model", "Per-model fan-in template (category fan-in-model) — both @RESPONDERS@ and @RESPONDER_PAIRS@ placeholders available", "${countByCategory("fan-in-model")}",
                onClick = { onOpenInternalPrompts("fan-in-model") })
        }
    }
}

// ===== Local Models Setup hub =====

/** Sub-hub under AI Setup that groups the two on-device runtime
 *  entry points (Local LLMs — `.task` chat / completion bundles
 *  driving the synthetic Local provider — and Local LiteRT models
 *  — `.tflite` text embedders driving Local Semantic Search and
 *  Local-embedder Knowledge bases). Same shape as the other
 *  sub-hubs. */
@Composable
fun LocalModelsSetupScreen(
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    val liteRtCount = remember(refreshTick) { com.ai.data.local.LocalEmbedder.availableModels(context).size }
    val localLlmCount = remember(refreshTick) { com.ai.data.local.LocalLlm.availableLlms(context).size }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "setup_local_models", title = "Local models", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("📱", "Local LLMs", "On-device .task chat models that drive the synthetic Local provider", "$localLlmCount",
                onClick = { onNavigate(SettingsSubScreen.AI_LOCAL_LLMS) })
            ModelsSetupNavCard("📐", "Local LiteRT models", "On-device .tflite text embedders for Local Semantic Search and Local-embedder Knowledge", "$liteRtCount",
                onClick = { onNavigate(SettingsSubScreen.AI_LOCAL_LITERT_MODELS) })
        }
    }
}

@Composable
private fun ModelsSetupNavCard(icon: String, title: String, description: String, count: String, onClick: () -> Unit, enabled: Boolean = true) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (enabled) AppColors.CardBackgroundAlt else Color(0xFF1A2A3A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 22.sp, modifier = if (enabled) Modifier else Modifier.alpha(0.4f))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else AppColors.TextDim)
                Text(description, fontSize = 12.sp, color = if (enabled) AppColors.TextTertiary else AppColors.TextVeryDim)
            }
            if (count.isNotBlank()) {
                Text(count, fontSize = 14.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (enabled) Text(">", fontSize = 16.sp, color = AppColors.Blue)
        }
    }
}

// ===== Providers List =====

@Composable
fun ProvidersScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onProviderSelected: (AppService) -> Unit,
    onAddProvider: (String) -> Unit = {},
    /** Hoisted by SettingsScreen so it survives the sub-screen `when`
     *  block tearing this composable down whenever the user opens a
     *  Provider detail page. Defaults to a fresh state for callers
     *  that don't need preservation. */
    scrollState: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState()
) {
    BackHandler { onBackToAiSetup() }
    val context = LocalContext.current
    val allProviders = AppService.entries

    // Sort by state bucket (ok → error → inactive → other), then by id
    // case-insensitively within each bucket. Working providers surface
    // first so they're one tap away; broken / dormant / never-configured
    // ones sink to the bottom.
    val visibleProviders = remember(aiSettings, allProviders) {
        allProviders.sortedWith(
            compareBy<AppService> { p ->
                when (aiSettings.getProviderState(p)) {
                    "ok" -> 0
                    "error" -> 1
                    "inactive" -> 2
                    else -> 3
                }
            }.thenBy { it.id.lowercase(java.util.Locale.ROOT) }
        )
    }

    var showAddDialog by remember { mutableStateOf(false) }
    if (showAddDialog) {
        AddProviderNameDialog(
            existingIds = remember(allProviders) { allProviders.map { it.id }.toSet() },
            onConfirm = { name -> showAddDialog = false; onAddProvider(name) },
            onDismiss = { showAddDialog = false }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "providers", title = "Providers", onBackClick = onBackToAiSetup)

        Column(modifier = Modifier.weight(1f).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visibleProviders.forEach { provider ->
                val state = aiSettings.getProviderState(provider)
                val stateEmoji = when (state) {
                    "ok" -> "\uD83D\uDD11"; "error" -> "\u274C"; "inactive" -> "\uD83D\uDCA4"; else -> "\u2B55"
                }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onProviderSelected(provider) },
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(provider.id, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            if (state == "ok") {
                                val model = aiSettings.getModel(provider)
                                if (model.isNotBlank()) Text(com.ai.ui.shared.shortModelName(model), fontSize = 12.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(stateEmoji, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
                        IconButton(
                            onClick = { openProviderAdminUrl(context, provider) },
                            enabled = provider.adminUrl.isNotBlank()
                        ) {
                            Text(
                                "🛠️",
                                fontSize = 18.sp,
                                modifier = if (provider.adminUrl.isNotBlank()) Modifier else Modifier.alpha(0.3f)
                            )
                        }
                    }
                }
            }
            // Add sits at the bottom — the typical flow is "scroll the
            // list to confirm what you want isn't already there, then
            // add a new entry", so the action lands under the user's
            // thumb after the scan.
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text("+ Add provider", maxLines = 1, softWrap = false) }
        }
    }
}

/** Open the per-provider admin / signup web console. Mirrors the
 *  guard from the former ProviderAdminScreen: empty adminUrl falls
 *  back to a toast (also reflected by the disabled icon state on the
 *  list row), and non-http(s) schemes are refused so a user-imported
 *  provider entry can't smuggle in a javascript: or intent: URL via
 *  an unguarded ACTION_VIEW. */
private fun openProviderAdminUrl(context: android.content.Context, provider: AppService) {
    val url = provider.adminUrl
    if (url.isBlank()) {
        android.widget.Toast.makeText(context, "No admin URL configured for ${provider.id}", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        android.widget.Toast.makeText(context, "Refusing non-http(s) admin URL: $scheme", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Couldn't open: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Tiny single-field dialog for the "+ Add provider" entry. The full
 *  provider definition (base URL, paths, format, etc.) is filled in
 *  on the existing edit screen the caller jumps to once this dialog
 *  confirms. */
@Composable
private fun AddProviderNameDialog(
    existingIds: Set<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val normalized = name.trim().replace(" ", "")
    // Spaces would break SharedPreferences key prefixes (`<id>_api_key`,
    // `<id>_model`, …); "Local" / "LOCAL" is the synthetic on-device
    // sentinel that AppService.findById short-circuits — both rejected.
    val reserved = normalized.equals("Local", ignoreCase = true)
    val taken = normalized.isNotBlank() &&
        existingIds.any { it.equals(normalized, ignoreCase = true) }
    val canSave = normalized.isNotBlank() && !reserved && !taken

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Provider name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedFieldColors(),
                supportingText = {
                    when {
                        reserved -> Text("Local is reserved for the on-device provider", color = AppColors.Red, fontSize = 11.sp)
                        taken -> Text("Already in use", color = AppColors.Red, fontSize = 11.sp)
                        name.contains(" ") -> Text("Spaces will be stripped — saved as \"$normalized\"", color = AppColors.TextTertiary, fontSize = 11.sp)
                    }
                }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(normalized) }, enabled = canSave) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ===== External Services =====

@Composable
fun ExternalServicesScreen(
    huggingFaceApiKey: String,
    openRouterApiKey: String,
    artificialAnalysisApiKey: String,
    onSaveHuggingFaceApiKey: (String) -> Unit,
    onSaveOpenRouterApiKey: (String) -> Unit,
    onSaveArtificialAnalysisApiKey: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** Open the per-info-provider help topic. Each card's ℹ️ icon
     *  routes through this; AppNavHost wires it to the help nav. */
    onNavigateToHelpTopic: (String) -> Unit = {}
) {
    BackHandler { onBack() }
    var hfKey by remember { mutableStateOf(huggingFaceApiKey) }
    var orKey by remember { mutableStateOf(openRouterApiKey) }
    var aaKey by remember { mutableStateOf(artificialAnalysisApiKey) }

    // Debounce keystrokes — every character was firing a prefs write,
    // so pasting a 96-char key wrote 96 times in rapid succession.
    // Wait 400ms of quiet (matches SettingsMainScreen's debounce) and
    // also flush on dispose so a fast back-press doesn't lose the
    // typed value.
    LaunchedEffect(hfKey) {
        kotlinx.coroutines.delay(400)
        if (hfKey != huggingFaceApiKey) onSaveHuggingFaceApiKey(hfKey)
    }
    LaunchedEffect(orKey) {
        kotlinx.coroutines.delay(400)
        if (orKey != openRouterApiKey) onSaveOpenRouterApiKey(orKey)
    }
    LaunchedEffect(aaKey) {
        kotlinx.coroutines.delay(400)
        if (aaKey != artificialAnalysisApiKey) onSaveArtificialAnalysisApiKey(aaKey)
    }
    DisposableEffect(Unit) {
        onDispose {
            if (hfKey != huggingFaceApiKey) onSaveHuggingFaceApiKey(hfKey)
            if (orKey != openRouterApiKey) onSaveOpenRouterApiKey(orKey)
            if (aaKey != artificialAnalysisApiKey) onSaveArtificialAnalysisApiKey(aaKey)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "external_services", title = "External Services", onBackClick = onBack)

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExternalServiceCard(
                name = "HuggingFace",
                description = "Used for model information lookup",
                topicId = "info_provider_huggingface",
                value = hfKey, onValueChange = { hfKey = it },
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
            ExternalServiceCard(
                name = "OpenRouter",
                description = "Used for pricing data and model specifications",
                topicId = "info_provider_openrouter",
                value = orKey, onValueChange = { orKey = it },
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
            ExternalServiceCard(
                name = "Artificial Analysis",
                description = "Pricing snapshot plus quality / speed scores. Free tier — sign up at artificialanalysis.ai/api",
                topicId = "info_provider_artificial_analysis",
                value = aaKey, onValueChange = { aaKey = it },
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
        }
    }
}

/** One External-Services card: name + ℹ️ that opens the matching
 *  info-provider help topic, blurb, and the API-key text field. */
@Composable
private fun ExternalServiceCard(
    name: String,
    description: String,
    topicId: String,
    value: String,
    onValueChange: (String) -> Unit,
    onNavigateToHelpTopic: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = { onNavigateToHelpTopic(topicId) }, modifier = Modifier.size(28.dp)) {
                    Text("ℹ️", fontSize = 16.sp)
                }
            }
            Text(description, fontSize = 12.sp, color = AppColors.TextTertiary)
            OutlinedTextField(
                value = value, onValueChange = onValueChange,
                label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors()
            )
        }
    }
}
