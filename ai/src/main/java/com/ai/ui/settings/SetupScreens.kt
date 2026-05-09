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
    // Counts of installed on-device runtimes — surfaced as the badge
    // on the matching SetupNavCard so the user can see at a glance
    // whether anything is installed without drilling in.
    val liteRtCount = remember(refreshTick) { com.ai.data.LocalEmbedder.availableModels(context).size }
    val localLlmCount = remember(refreshTick) { com.ai.data.LocalLlm.availableLlms(context).size }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "settings_setup", title = "AI Setup", onBackClick = onBackToSettings)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SetupNavCard("\u2699\uFE0F", "Providers", "API key, state, and default model per provider", "${AppService.entries.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS_SETUP) })
            SetupNavCard("\uD83E\uDDE0", "Models", "Models, types, and manual overrides", "$modelCount",
                onClick = { onNavigate(SettingsSubScreen.AI_MODELS_SETUP) })
            run {
                val workersCount = agentCount + aiSettings.flocks.size + aiSettings.swarms.size
                SetupNavCard("\uD83D\uDC65", "Workers", "Agents, Flocks, and Swarms", "$workersCount",
                    onClick = { onNavigate(SettingsSubScreen.AI_WORKERS_SETUP) }, enabled = hasApiKey)
            }
            run {
                val promptsCount = aiSettings.systemPrompts.size + aiSettings.internalPrompts.size
                SetupNavCard("\uD83D\uDCDD", "Prompt management", "System prompts and Internal (Meta + Internal) prompts", "$promptsCount",
                    onClick = { onNavigate(SettingsSubScreen.AI_PROMPTS_SETUP) })
            }
            SetupNavCard("\uD83C\uDFDB\uFE0F", "Parameters", "Parameter presets", "${aiSettings.parameters.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_PARAMETERS) })
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
        TitleBar(helpTopic = "setup_models", title = "AI Models setup", onBackClick = onBack)
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
        TitleBar(helpTopic = "setup_workers", title = "AI Workers", onBackClick = onBack)
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

/** Sub-hub under AI Setup that groups the two prompt-management entry
 *  points (System Prompts and the Internal Prompts hub). Same pattern
 *  as [WorkersSetupScreen] and [ModelsSetupScreen]. */
@Composable
fun PromptsSetupScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    /** On-demand merge of bundled assets/prompts.json into the Internal
     *  prompts list (existing rows by category+name kept). Returns the
     *  number of newly added rows. */
    onLoadBundledPrompts: () -> Int = { 0 },
    /** Drop every Internal prompt and reload from assets/prompts.json
     *  fresh. Returns the number of rows loaded. */
    onResetBundledPrompts: () -> Int = { 0 }
,
    /** On-demand merge of bundled assets/examples.json into the
     *  Example prompts list (existing titles kept). Returns the number
     *  of newly added rows. */
    onLoadBundledExamples: () -> Int = { 0 }
) {
    BackHandler { onBack() }
    var internalStatus by remember { mutableStateOf<String?>(null) }
    var exampleStatus by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var internalMaintExpanded by rememberSaveable { mutableStateOf(false) }
    var exampleMaintExpanded by rememberSaveable { mutableStateOf(false) }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Internal prompts?") },
            text = { Text("This deletes every Internal prompt (including any you customized) and reloads the bundled list from assets/prompts.json.") },
            confirmButton = {
                Button(
                    onClick = {
                        val loaded = onResetBundledPrompts()
                        showResetConfirm = false
                        internalStatus = if (loaded > 0) {
                            "Reset complete — loaded $loaded prompt${if (loaded == 1) "" else "s"} from assets/prompts.json"
                        } else {
                            "Reset failed — assets/prompts.json could not be read"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Reset", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "setup_prompts", title = "Prompt management", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("🗨️", "System Prompts", "Reusable system prompts", "${aiSettings.systemPrompts.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_SYSTEM_PROMPTS) })
            ModelsSetupNavCard("🧩", "Internal Prompts", "Meta, Fan-out, Fan-in, and Other internal prompts", "${aiSettings.internalPrompts.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB) })
            ModelsSetupNavCard("📝", "Example prompts", "Curated (title, text) starters for the New Report flow", "${aiSettings.examplePrompts.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_EXAMPLE_PROMPTS) })

            // Maintenance, lifted from the former Housekeeping → Prompts
            // screen so prompt management lives in one place. Styled to
            // match the NavCards above; tap to expand the body.
            CollapsibleMaintenanceCard(
                icon = "🛠️",
                title = "Internal prompts maintenance",
                description = "Load missing or reset to bundled defaults",
                expanded = internalMaintExpanded,
                onToggle = { internalMaintExpanded = !internalMaintExpanded }
            ) {
                Text(
                    "Load merges any prompt in assets/prompts.json that's missing — matched by (category, name); existing rows with the same pair keep your edits. Reset wipes every Internal prompt (including ones you authored) and reloads the bundled set fresh.",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                Button(
                    onClick = {
                        val added = onLoadBundledPrompts()
                        internalStatus = when {
                            added == 0 -> "No new prompts in assets/prompts.json"
                            added == 1 -> "Added 1 new prompt"
                            else -> "Added $added new prompts"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Load new prompts from assets/prompts.json", maxLines = 1, softWrap = false) }
                Button(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Reset Internal Prompts to assets/prompts.json", maxLines = 1, softWrap = false) }
                internalStatus?.let {
                    Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
                }
            }

            CollapsibleMaintenanceCard(
                icon = "🛠️",
                title = "Example prompts maintenance",
                description = "Add bundled example prompts",
                expanded = exampleMaintExpanded,
                onToggle = { exampleMaintExpanded = !exampleMaintExpanded }
            ) {
                Text(
                    "Adds any prompt in assets/examples.json that's missing — matched by case-insensitive title. Existing prompts (including ones you authored) are left strictly alone, never overwritten or wiped.",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                Button(
                    onClick = {
                        val added = onLoadBundledExamples()
                        exampleStatus = when {
                            added == 0 -> "No new prompts in assets/examples.json"
                            added == 1 -> "Added 1 new example prompt"
                            else -> "Added $added new example prompts"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Add new prompts from assets/examples.json", maxLines = 1, softWrap = false) }
                exampleStatus?.let {
                    Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
                }
            }
        }
    }
}

/** Sub-hub under Prompt Management that groups the category-scoped
 *  Internal Prompts CRUDs. Three top-level buckets:
 *    - Meta prompts (single CRUD)
 *    - Fan out/in prompts (forwards to its own sub-hub holding the
 *      five fan-* category CRUDs — fan_out / fan_in / initiator /
 *      requester / model)
 *    - Other internal prompts (single fixed-list CRUD) */
@Composable
fun InternalPromptsHubScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    /** Set the category that the AI_INTERNAL_PROMPTS list + edit
     *  screens filter on, then navigate to the list. */
    onOpenInternalPrompts: (String) -> Unit,
    /** Forward to the Fan out/in sub-hub. */
    onOpenFanInOutHub: () -> Unit
) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "internal_prompts_hub", title = "Internal prompts", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        fun countByCategory(c: String) = aiSettings.internalPrompts.count { it.category == c }
        // Sum of every fan-* bucket — what the new Fan out/in card's
        // count badge shows so the user can see at a glance how many
        // templates total live in the sub-hub.
        val fanTotal = countByCategory("fan_out") + countByCategory("fan_in") +
            countByCategory("initiator") + countByCategory("requester") +
            countByCategory("model")

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("🧩", "Meta prompts", "Rerank, Summarize, Compare, Moderation — run on the full report", "${countByCategory("meta")}",
                onClick = { onOpenInternalPrompts("meta") })
            ModelsSetupNavCard("🔀", "Fan out/in prompts", "Templates for the Fan out / Fan in flow — across pairs, combined reports, and per-model variants", "$fanTotal",
                onClick = onOpenFanInOutHub)
            ModelsSetupNavCard("🧰", "Other internal prompts", "Templates consumed by app features (Translate, Model info, Intro)", "${countByCategory("internal")}",
                onClick = { onOpenInternalPrompts("internal") })
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
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "fan_in_out_prompts_hub", title = "Fan out/in prompts", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        fun countByCategory(c: String) = aiSettings.internalPrompts.count { it.category == c }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("🔀", "Manage Fan Out prompts", "Run across every pair of report-models", "${countByCategory("fan_out")}",
                onClick = { onOpenInternalPrompts("fan_out") })
            ModelsSetupNavCard("🪢", "Manage Fan in, total, prompts", "Combine all fan-out responses into a single report", "${countByCategory("fan_in")}",
                onClick = { onOpenInternalPrompts("fan_in") })
            ModelsSetupNavCard("🎬", "Manage Fan in, model, Initiator prompts", "Per-model initiator template (category initiator)", "${countByCategory("initiator")}",
                onClick = { onOpenInternalPrompts("initiator") })
            ModelsSetupNavCard("💬", "Manage Fan in, model, Responder prompts", "Per-model responder template (category requester)", "${countByCategory("requester")}",
                onClick = { onOpenInternalPrompts("requester") })
            ModelsSetupNavCard("🧬", "Manage Fan in, model, Initiator & Responder prompts", "Combined initiator + responder template (category model)", "${countByCategory("model")}",
                onClick = { onOpenInternalPrompts("model") })
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
    val liteRtCount = remember(refreshTick) { com.ai.data.LocalEmbedder.availableModels(context).size }
    val localLlmCount = remember(refreshTick) { com.ai.data.LocalLlm.availableLlms(context).size }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "setup_local_models", title = "Local models", onBackClick = onBack)
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

/** Same visual shape as [ModelsSetupNavCard] but the chevron toggles
 *  an inline body rather than navigating away. Used for in-screen
 *  maintenance actions where the buttons are infrequently tapped and
 *  shouldn't dominate the screen. */
@Composable
private fun CollapsibleMaintenanceCard(
    icon: String,
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, fontSize = 22.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(description, fontSize = 12.sp, color = AppColors.TextTertiary)
                }
                Text(if (expanded) "▴" else "▾", fontSize = 16.sp, color = AppColors.Blue)
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { content() }
            }
        }
    }
}

// ===== Providers Setup hub =====

/** Sub-hub under AI Setup that groups the three provider-related
 *  entry points: Provider configuration (the per-provider list /
 *  edit screen), Provider administration (the catalog admin), and
 *  the on-demand `assets/providers.json` import. */
@Composable
fun ProvidersSetupScreen(
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onNavigateToProviderAdmin: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val providerCount = remember(refreshTick) { AppService.entries.size }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "setup_providers", title = "Providers", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsSetupNavCard("⚙️", "Provider configuration", "API key, state, and default model per provider", "$providerCount",
                onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS) })
            ModelsSetupNavCard("🛠️", "Provider administration", "Catalog admin: rename, redirect, deactivate", "",
                onClick = onNavigateToProviderAdmin)
            ModelsSetupNavCard("📥", "Import new providers from assets/providers.json",
                "Adds any provider in the bundled catalog that isn't yet registered", "",
                onClick = {
                    val added = com.ai.data.ProviderRegistry.importFromAsset(context)
                    importStatus = when {
                        added < 0 -> "Could not read assets/providers.json"
                        added == 0 -> "No new providers in assets/providers.json"
                        added == 1 -> "Added 1 new provider"
                        else -> "Added $added new providers"
                    }
                    if (added > 0) refreshTick++
                })
            importStatus?.let {
                Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
            }
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
    activeOnly: Boolean = true,
    onActiveOnlyChange: (Boolean) -> Unit = {}
) {
    BackHandler { onBackToAiSetup() }
    val allProviders = AppService.entries
    // "Active" = providers that have been tested and have a working API key (state == "ok").
    // The filter choice is hoisted into the parent SettingsScreen so it survives a
    // navigation hop into a provider's detail screen and back.
    val activeCount = remember(aiSettings) { allProviders.count { aiSettings.getProviderState(it) == "ok" } }

    val visibleProviders = remember(activeOnly, aiSettings) {
        (if (activeOnly) allProviders.filter { aiSettings.getProviderState(it) == "ok" } else allProviders)
            .sortedBy { it.id }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "providers", title = "Providers", onBackClick = onBackToAiSetup)
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
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (visibleProviders.isEmpty()) {
                Text(
                    "No active providers yet. Switch to All and set an API key.",
                    fontSize = 13.sp, color = AppColors.TextTertiary
                )
            }
            // Show Add in both filter modes — the previous gate hid the
            // button on "Active" so a fresh install (no providers
            // active yet) gave the user a dead-end empty state.
            Button(
                onClick = onAddProvider,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text("+ Add provider", maxLines = 1, softWrap = false) }

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
                            Text(provider.id, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
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
    onNavigateHome: () -> Unit,
    /** Open the per-info-provider help topic. Each card's ℹ icon
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
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "external_services", title = "External Services", onBackClick = onBack)
        Spacer(modifier = Modifier.height(16.dp))

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

/** One External-Services card: name + ℹ that opens the matching
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
