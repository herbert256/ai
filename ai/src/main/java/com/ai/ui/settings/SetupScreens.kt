package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    val hasActiveProvider = remember(aiSettings) { aiSettings.getActiveServices().isNotEmpty() }
    // Total models across active providers — matches the per-provider counts shown on the
    // Models sub-screen, which only lists active providers.
    val modelCount = remember(aiSettings) {
        aiSettings.getActiveServices().sumOf { aiSettings.getProvider(it).models.size }
    }
    val agentCount = remember(aiSettings.agents) { aiSettings.agents.count { aiSettings.isProviderActive(it.provider) } }
    val costCount = remember { PricingCache.getAllManualPricing(context).size }
    val externalCount = remember(huggingFaceApiKey, openRouterApiKey, aaApiKey) {
        (if (huggingFaceApiKey.isNotBlank()) 1 else 0) +
        (if (openRouterApiKey.isNotBlank()) 1 else 0) +
        (if (aaApiKey.isNotBlank()) 1 else 0)
    }
    // Counts of installed on-device runtimes — surfaced as the badge
    // on the matching SetupNavCard so the user can see at a glance
    // whether anything is installed without drilling in.
    val liteRtCount = remember { com.ai.data.LocalEmbedder.availableModels(context).size }
    val localLlmCount = remember { com.ai.data.LocalLlm.availableLlms(context).size }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "AI Setup", onBackClick = onBackToSettings, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))

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
            SetupNavCard("\uD83C\uDFDB\uFE0F", "Parameters", "Parameter presets", "${aiSettings.parameters.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_PARAMETERS) })
            run {
                val promptsCount = aiSettings.systemPrompts.size + aiSettings.internalPrompts.size
                SetupNavCard("\uD83D\uDCDD", "Prompt management", "System prompts and Internal (Meta + Internal) prompts", "$promptsCount",
                    onClick = { onNavigate(SettingsSubScreen.AI_PROMPTS_SETUP) })
            }
            SetupNavCard("\uD83D\uDCB0", "Costs", "Manual pricing configuration", "$costCount",
                onClick = onNavigateToCostConfig)
            SetupNavCard("\uD83D\uDD11", "External Services", "HuggingFace, OpenRouter keys", "$externalCount",
                onClick = { onNavigate(SettingsSubScreen.AI_EXTERNAL_SERVICES) })
            run {
                val localCount = liteRtCount + localLlmCount
                SetupNavCard("\uD83D\uDCBB", "Local Models", "On-device LLMs and LiteRT text embedders", "$localCount",
                    onClick = { onNavigate(SettingsSubScreen.AI_LOCAL_MODELS_SETUP) })
            }
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

/** Sub-hub under AI Setup that groups the three model-related screens
 *  (Models, Model Types, Manual model types overrides). */
@Composable
fun ModelsSetupScreen(
    aiSettings: Settings,
    hasActiveProvider: Boolean,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit
) {
    BackHandler { onBack() }
    val modelCount = remember(aiSettings) {
        aiSettings.getActiveServices().sumOf { aiSettings.getProvider(it).models.size }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "AI Models setup", onBackClick = onBack, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("🧠", "Models", "Source and model list per active provider", "$modelCount",
                onClick = { onNavigate(SettingsSubScreen.AI_MODELS) }, enabled = hasActiveProvider)
            ModelsSetupNavCard("🏷️", "Model Types", "Default API path per type (chat, embedding, ...)", "${com.ai.data.ModelType.ALL.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_MODEL_TYPES) })
            ModelsSetupNavCard("✍️", "Manual model types overrides", "Per-model type assignments that win over autodetection", "${aiSettings.modelTypeOverrides.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_MANUAL_MODEL_TYPES) })
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
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "AI Workers", onBackClick = onBack, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))

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

/** Sub-hub under AI Setup that groups the two prompt-management
 *  entry points (System Prompts — reusable system messages — and
 *  Internal Prompts — the unified Meta + Internal CRUD covering
 *  Rerank / Summarize / Compare / Moderation / Intro / Model info /
 *  Translate). Same pattern as [WorkersSetupScreen] and
 *  [ModelsSetupScreen] — keeps the AI Setup landing page compact. */
@Composable
fun PromptsSetupScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit
) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Prompt management", onBackClick = onBack, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("🗨️", "System Prompts", "Reusable system prompts", "${aiSettings.systemPrompts.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_SYSTEM_PROMPTS) })
            ModelsSetupNavCard("🧩", "Internal Prompts", "Rerank, Summarize, Compare, Moderation, Intro, Model info, Translate", "${aiSettings.internalPrompts.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_INTERNAL_PROMPTS) })
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
    val liteRtCount = remember { com.ai.data.LocalEmbedder.availableModels(context).size }
    val localLlmCount = remember { com.ai.data.LocalLlm.availableLlms(context).size }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Local models", onBackClick = onBack, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))

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
    onAddProvider: () -> Unit = {},
    onAdminLinks: () -> Unit = {},
    activeOnly: Boolean = true,
    onActiveOnlyChange: (Boolean) -> Unit = {}
) {
    BackHandler { onBackToAiSetup() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // refreshTick bumps after the on-demand assets/providers.json
    // import so newly-added providers appear without leaving the
    // screen — AppService.entries is read each composition, but the
    // remembered list/counts otherwise stay stale.
    var refreshTick by remember { mutableStateOf(0) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val allProviders = remember(refreshTick) { AppService.entries }
    // "Active" = providers that have been tested and have a working API key (state == "ok").
    // The filter choice is hoisted into the parent SettingsScreen so it survives a
    // navigation hop into a provider's detail screen and back.
    val activeCount = remember(refreshTick, aiSettings) { allProviders.count { aiSettings.getProviderState(it) == "ok" } }

    val visibleProviders = remember(refreshTick, activeOnly, aiSettings) {
        (if (activeOnly) allProviders.filter { aiSettings.getProviderState(it) == "ok" } else allProviders)
            .sortedBy { it.displayName }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Providers", onBackClick = onBackToAiSetup, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = activeOnly,
                onClick = { onActiveOnlyChange(true) },
                label = { Text("Active ($activeCount)") }
            )
            FilterChip(
                selected = !activeOnly,
                onClick = { onActiveOnlyChange(false) },
                label = { Text("All (${allProviders.size})") }
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = onAdminLinks,
                colors = AppColors.outlinedButtonColors(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("Admin links", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (visibleProviders.isEmpty()) {
                Text(
                    "No active providers yet. Switch to All and set an API key.",
                    fontSize = 13.sp, color = AppColors.TextTertiary
                )
            }
            if (!activeOnly) {
                Button(
                    onClick = onAddProvider,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
                ) { Text("+ Add provider", maxLines = 1, softWrap = false) }
                Button(
                    onClick = {
                        val added = com.ai.data.ProviderRegistry.importFromAsset(context)
                        importStatus = when {
                            added < 0 -> "Could not read assets/providers.json"
                            added == 0 -> "No new providers in assets/providers.json"
                            added == 1 -> "Added 1 new provider"
                            else -> "Added $added new providers"
                        }
                        if (added > 0) refreshTick++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Import new providers from assets/providers.json", maxLines = 1, softWrap = false) }
                importStatus?.let {
                    Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
                }
            }

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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider.displayName, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            if (state == "ok") {
                                val model = aiSettings.getModel(provider)
                                if (model.isNotBlank()) Text(model, fontSize = 12.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(stateEmoji, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
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
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    var hfKey by remember { mutableStateOf(huggingFaceApiKey) }
    var orKey by remember { mutableStateOf(openRouterApiKey) }
    var aaKey by remember { mutableStateOf(artificialAnalysisApiKey) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "External Services", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("HuggingFace", fontWeight = FontWeight.Bold, color = Color.White)
                Text("Used for model information lookup", fontSize = 12.sp, color = AppColors.TextTertiary)
                OutlinedTextField(
                    value = hfKey, onValueChange = { hfKey = it; onSaveHuggingFaceApiKey(it) },
                    label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )

                HorizontalDivider(color = AppColors.DividerDark)

                Text("OpenRouter", fontWeight = FontWeight.Bold, color = Color.White)
                Text("Used for pricing data and model specifications", fontSize = 12.sp, color = AppColors.TextTertiary)
                OutlinedTextField(
                    value = orKey, onValueChange = { orKey = it; onSaveOpenRouterApiKey(it) },
                    label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )

                HorizontalDivider(color = AppColors.DividerDark)

                Text("Artificial Analysis", fontWeight = FontWeight.Bold, color = Color.White)
                Text("Pricing snapshot plus quality / speed scores. Free tier — sign up at artificialanalysis.ai/api", fontSize = 12.sp, color = AppColors.TextTertiary)
                OutlinedTextField(
                    value = aaKey, onValueChange = { aaKey = it; onSaveArtificialAnalysisApiKey(it) },
                    label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
        }
    }
}
