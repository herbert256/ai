package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.MetadataDefaults
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalMetadataIcons
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.GeneralSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

enum class SettingsSubScreen {
    MAIN, AI_PROVIDER_EDIT, AI_SETUP,
    AI_PROVIDERS,
    AI_MODELS_SETUP,
    AI_MODELS, AI_MODEL_EDIT,
    AI_MODEL_TYPES,
    AI_MANUAL_MODEL_TYPES,
    AI_WORKERS_SETUP,
    AI_AGENTS, AI_AGENT_EDIT,
    AI_FLOCKS, AI_FLOCK_EDIT,
    AI_SWARMS, AI_SWARM_EDIT,
    AI_PARAMETERS,
    AI_SYSTEM_PROMPTS, AI_SYSTEM_PROMPT_EDIT,
    AI_FAN_PROMPTS_HUB,
    AI_INTERNAL_PROMPTS, AI_INTERNAL_PROMPT_EDIT,
    AI_EXAMPLE_PROMPTS, AI_EXAMPLE_PROMPT_EDIT,
    AI_EXTERNAL_SERVICES,
    AI_APP_SETTINGS,
    AI_DEFAULT_META_ITEMS,
    AI_PROMPTS_SETUP,
    AI_INTERNAL_PROMPTS_HUB,
    AI_LOCAL_MODELS_SETUP,
    AI_LOCAL_LITERT_MODELS,
    AI_LOCAL_LLMS,
    AI_MODEL_COOLDOWNS,
    AI_BLOCKED_MODELS,
    AI_TEST_EXCLUDED_MODELS,
    AI_INACCESSIBLE_MODELS,
    AI_IMPORT_EXPORT,
    AI_REFRESH,
    // Four preference buckets carved out of the main Settings screen
    // so the top page stays a short nav list. Each sub-screen owns
    // its own help topic and renders only the cards from its bucket.
    SETTINGS_NETWORK,
    SETTINGS_NETWORK_API_CALLS,
    SETTINGS_UI,
    SETTINGS_LOGGING,
    SETTINGS_OTHER,
    SETTINGS_METADATA,
    SETTINGS_DEFAULT_ICONS
}

@Composable
fun SettingsScreen(
    generalSettings: GeneralSettings,
    aiSettings: Settings,
    loadingModelsFor: Set<AppService> = emptySet(),
    fetchModelsErrors: Map<String, com.ai.viewmodel.FetchModelsError> = emptyMap(),
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onSaveGeneral: (GeneralSettings) -> Unit,
    onSaveAi: (Settings) -> Unit,
    onFetchModels: (AppService, String) -> Unit = { _, _ -> },
    onFetchModelsAwait: suspend (AppService, String) -> String? = { _, _ -> null },
    onTestAiModel: suspend (AppService, String, String) -> String? = { _, _, _ -> null },
    /** Ask one model a free-form prompt → its response text (Default-icons
     *  "AI" icon finder). (service, model, prompt) → text or null. */
    onAskModelText: suspend (AppService, String, String) -> String? = { _, _, _ -> null },
    onProviderStateChange: (AppService, String) -> Unit = { _, _ -> },
    onProviderTestedOk: (AppService, String) -> Unit = { _, _ -> },
    onProviderTestedOkNoFetch: (AppService, String) -> Unit = onProviderTestedOk,
    onReplaceDefaultAgent: (AppService, String) -> Unit = { _, _ -> },
    onRefreshAllModels: suspend (Settings, Boolean, ((String) -> Unit)?) -> Map<String, Int> = { _, _, _ -> emptyMap() },
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onSaveArtificialAnalysisApiKey: (String) -> Unit = {},
    onNavigateToCostConfig: () -> Unit = {},
    onTestModelWithPrompt: suspend (AppService, String, String, String) -> Pair<Boolean, String?> = { _, _, _, _ -> Pair(false, null) },
    onTestSpecificModel: suspend (AppService, String, String, String) -> Pair<Boolean, String?> = { _, _, _, _ -> Pair(false, null) },
    onNavigateToTrace: (String) -> Unit = {},
    onNavigateToTraceCategory: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    refreshAllState: com.ai.viewmodel.RefreshAllState? = null,
    onStartRefreshAll: () -> Unit = {},
    onStartRefreshWorkers: () -> Unit = {},
    onClearRefreshAllState: () -> Unit = {},
    /** Replace the current sub-screen with the Refresh page. Used by
     *  the post-API-keys-import dialog so "Run Refresh all" lands the
     *  user on the progress overlay it just kicked off. */
    onNavigateToRefresh: () -> Unit = {},
    onNavigateToHelpTopic: (String) -> Unit = {},
    /** Optional 👁-bar hooks wired from AppNavHost to the matching
     *  View screen route. When set, the Agent / Flock / Swarm Edit
     *  screens render a 👁 in their bottom bar; tap navigates to the
     *  read-only View sibling and back returns here via Jetpack Nav.
     *  Default no-op keeps the icon hidden on legacy call sites. */
    onNavigateToAgentView: ((String) -> Unit)? = null,
    onNavigateToFlockView: ((String) -> Unit)? = null,
    onNavigateToSwarmView: ((String) -> Unit)? = null,
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN,
    initialProviderId: String? = null,
    initialEditingAgentId: String? = null,
    initialEditingFlockId: String? = null,
    initialEditingSwarmId: String? = null,
    initialEditingInternalPromptId: String? = null,
    initialInternalPromptCategory: String? = null,
    /** Overrides the computed ⚙️ / 🤖 section icon in the top-left slot.
     *  Set by standalone route entries that are shared with another
     *  section — e.g. Refresh / Import-Export reached from Housekeeping,
     *  which pass 🧹 (→ Housekeeping) so the icon matches where the user
     *  actually came from rather than always reading as AI Setup. */
    sectionIconOverride: com.ai.ui.shared.TopBarLeftIcon? = null
) {
    // rememberSaveable so a navigation hop OUT of Settings and back
    // (e.g. tapping a per-card ❓ that opens HelpScreen) restores the
    // user to the same sub-screen they left, instead of resetting to
    // the initial entry point. SettingsSubScreen is an enum →
    // Bundle's serializable saver handles it.
    var currentSubScreen by rememberSaveable { mutableStateOf(initialSubScreen) }
    // Hold the runtime selection as the AppService id (a String) so a
    // mutating ProviderRegistry doesn't blow it away. The previous
    // approach keyed remember on AppService.entries.size to "re-resolve
    // post-bootstrap", but that ALSO re-resolved on Add provider /
    // Import / Reset — which silently dropped the user's runtime
    // selection back to initialProviderId, bouncing them out of any
    // open provider edit. Now: store the id, look the AppService up
    // lazily by id below. The cold-launch race the previous comment
    // worried about is handled by the lookup returning null until
    // bootstrap finishes, then succeeding on the next recomposition.
    // rememberSaveable so the AI_PROVIDER_EDIT sub-screen knows
    // which provider to show after a navigation hop back into
    // Settings (per-card ❓ → HelpScreen → back).
    var selectedProviderId by rememberSaveable(initialProviderId) {
        mutableStateOf(initialProviderId)
    }
    val selectedProvider: AppService? = selectedProviderId?.let { AppService.findById(it) }
    var editingAgentId by remember { mutableStateOf(initialEditingAgentId) }
    var editingFlockId by remember { mutableStateOf(initialEditingFlockId) }
    var editingSwarmId by remember { mutableStateOf(initialEditingSwarmId) }
    var editingSystemPromptId by remember { mutableStateOf<String?>(null) }
    var editingInternalPromptId by remember { mutableStateOf(initialEditingInternalPromptId) }
    var editingExamplePromptId by remember { mutableStateOf<String?>(null) }
    // "providerId:model" key of the blocked-model row being edited;
    // null = adding a new one.
    // Which Internal Prompts CRUD bucket is currently open. Set by the
    // four cards on Prompt Management; the AI_INTERNAL_PROMPTS list
    // and AI_INTERNAL_PROMPT_EDIT screens filter / pin on it. When the
    // caller deep-links into AI_INTERNAL_PROMPT_EDIT (e.g. Fan out L1's
    // "Edit the used Fan out prompt") we derive the bucket from the
    // prompt being edited so the edit screen pins the right category.
    // rememberSaveable (like currentSubScreen above) so a navigation hop
    // OUT of Settings and back — e.g. a CRUD row's 🐞 → API Traces →
    // Android back — restores the category the user was on, instead of
    // resetting to the "internal" default ("Other internal prompts").
    var selectedInternalCategory by rememberSaveable {
        mutableStateOf(
            initialInternalPromptCategory
                ?: initialEditingInternalPromptId
                    ?.let { aiSettings.getInternalPromptById(it) }
                    ?.category
                ?: "internal"
        )
    }
    // Once the deep-linked prompt resolves (settings load is async on
    // cold start), pin the category to the prompt's actual bucket. The
    // initial `remember` runs once when settings are still empty —
    // without this LaunchedEffect a deep-link into a "meta" prompt
    // would save back as "internal".
    LaunchedEffect(initialEditingInternalPromptId, aiSettings) {
        if (initialInternalPromptCategory == null && initialEditingInternalPromptId != null) {
            val resolved = aiSettings.getInternalPromptById(initialEditingInternalPromptId)?.category
            if (resolved != null && resolved != selectedInternalCategory) {
                selectedInternalCategory = resolved
            }
        }
    }
    // Tracks whether the user entered AI_MODEL_EDIT via the Providers → Models link, so
    // pressing back returns to the provider edit rather than the Models list.
    var modelEditFromProvider by remember { mutableStateOf(false) }
    // Providers-list scroll position — hoisted to SettingsScreen because
    // the sub-screen `when` block destroys ProvidersScreen's composition
    // entirely on navigation into AI_PROVIDER_EDIT, throwing any
    // rememberScrollState there away. SettingsScreen itself survives the
    // switch, so a remember here keeps the list scrolled where the user
    // left it. ScrollState.Saver also keeps it across process death.
    val providersListScrollState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.ScrollState.Saver
    ) { androidx.compose.foundation.ScrollState(0) }

    val goBack: () -> Unit = goBack@ {
        // If the user landed directly on a deep-linked sub-screen (e.g. opened
        // Export/Import or Refresh from Housekeeping), back from that screen
        // should exit to the caller, not climb the Settings hierarchy.
        if (currentSubScreen == initialSubScreen && initialSubScreen != SettingsSubScreen.MAIN) {
            onBack(); return@goBack
        }
        when (currentSubScreen) {
            SettingsSubScreen.MAIN -> onBack()
            SettingsSubScreen.AI_SETUP -> if (initialSubScreen == SettingsSubScreen.AI_SETUP) onBack() else currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.AI_PROVIDER_EDIT -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
            SettingsSubScreen.AI_MODEL_EDIT -> {
                val from = modelEditFromProvider
                modelEditFromProvider = false
                currentSubScreen = if (from) SettingsSubScreen.AI_PROVIDER_EDIT else SettingsSubScreen.AI_MODELS
            }
            SettingsSubScreen.AI_MODELS, SettingsSubScreen.AI_MODEL_TYPES,
            SettingsSubScreen.AI_MANUAL_MODEL_TYPES -> currentSubScreen = SettingsSubScreen.AI_MODELS_SETUP
            SettingsSubScreen.AI_AGENTS, SettingsSubScreen.AI_FLOCKS,
            SettingsSubScreen.AI_SWARMS -> currentSubScreen = SettingsSubScreen.AI_WORKERS_SETUP
            SettingsSubScreen.AI_SYSTEM_PROMPTS,
            SettingsSubScreen.AI_EXAMPLE_PROMPTS -> currentSubScreen = SettingsSubScreen.AI_PROMPTS_SETUP
            // The Internal prompts hub now sits between Prompt
            // management and the per-category lists; the Fan out/in
            // sub-hub is a child of the Internal prompts hub.
            SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB -> currentSubScreen = SettingsSubScreen.AI_PROMPTS_SETUP
            SettingsSubScreen.AI_FAN_PROMPTS_HUB -> currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB
            // Back from a per-category list lands on whichever hub
            // owns that category — Fan out/in for any of the fan-*
            // buckets, Internal prompts for meta / internal / icons.
            // selectedInternalCategory is set when the list is opened,
            // so it's authoritative here.
            SettingsSubScreen.AI_INTERNAL_PROMPTS -> currentSubScreen =
                if (selectedInternalCategory in setOf("fan_out", "fan_in"))
                    SettingsSubScreen.AI_FAN_PROMPTS_HUB
                else
                    SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB
            SettingsSubScreen.AI_LOCAL_LITERT_MODELS,
            SettingsSubScreen.AI_LOCAL_LLMS -> currentSubScreen = SettingsSubScreen.AI_LOCAL_MODELS_SETUP
            SettingsSubScreen.AI_PROVIDERS,
            SettingsSubScreen.AI_MODELS_SETUP,
            SettingsSubScreen.AI_WORKERS_SETUP,
            SettingsSubScreen.AI_PROMPTS_SETUP,
            SettingsSubScreen.AI_LOCAL_MODELS_SETUP,
            SettingsSubScreen.AI_PARAMETERS,
            SettingsSubScreen.AI_EXTERNAL_SERVICES,
            SettingsSubScreen.AI_APP_SETTINGS,
            SettingsSubScreen.AI_DEFAULT_META_ITEMS,
            SettingsSubScreen.AI_MODEL_COOLDOWNS,
            SettingsSubScreen.AI_BLOCKED_MODELS,
            SettingsSubScreen.AI_TEST_EXCLUDED_MODELS,
            SettingsSubScreen.AI_INACCESSIBLE_MODELS,
            SettingsSubScreen.AI_IMPORT_EXPORT, SettingsSubScreen.AI_REFRESH -> currentSubScreen = SettingsSubScreen.AI_SETUP
            SettingsSubScreen.AI_AGENT_EDIT -> { editingAgentId = null; currentSubScreen = SettingsSubScreen.AI_AGENTS }
            SettingsSubScreen.AI_FLOCK_EDIT -> { editingFlockId = null; currentSubScreen = SettingsSubScreen.AI_FLOCKS }
            SettingsSubScreen.AI_SWARM_EDIT -> { editingSwarmId = null; currentSubScreen = SettingsSubScreen.AI_SWARMS }
            SettingsSubScreen.AI_SYSTEM_PROMPT_EDIT -> { editingSystemPromptId = null; currentSubScreen = SettingsSubScreen.AI_SYSTEM_PROMPTS }
            SettingsSubScreen.AI_INTERNAL_PROMPT_EDIT -> { editingInternalPromptId = null; currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS }
            SettingsSubScreen.AI_EXAMPLE_PROMPT_EDIT -> { editingExamplePromptId = null; currentSubScreen = SettingsSubScreen.AI_EXAMPLE_PROMPTS }
            SettingsSubScreen.SETTINGS_NETWORK,
            SettingsSubScreen.SETTINGS_UI,
            SettingsSubScreen.SETTINGS_LOGGING,
            SettingsSubScreen.SETTINGS_OTHER -> currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.SETTINGS_METADATA -> currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.SETTINGS_DEFAULT_ICONS -> currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.SETTINGS_NETWORK_API_CALLS ->
                currentSubScreen = SettingsSubScreen.SETTINGS_NETWORK
        }
    }

    BackHandler { goBack() }

    // Cross-area bottom-bar jumps for dispatcher sub-screens. Navigates
    // by route via the AppNavHost-provided local, so no per-mount
    // prop-drilling. 🧹 → Housekeeping (Models setup / Providers →
    // Refresh; Test-excluded / Inaccessible → Test). ⚙️ → AI Setup
    // (Refresh → Models setup).
    val navRoute = com.ai.ui.shared.LocalNavigateToRoute.current
    val hkRefresh = { navRoute(com.ai.ui.navigation.NavRoutes.AI_REFRESH) }
    val hkTest = { navRoute(com.ai.ui.navigation.NavRoutes.AI_TEST) }
    val settingsModelsSetup = { navRoute(com.ai.ui.navigation.NavRoutes.SETTINGS_MODELS_SETUP) }

    // Section icon for the shared top bar: ⚙️ on the Settings subtree
    // (root MAIN), 🤖 on the AI Setup subtree (root AI_SETUP). Tap the
    // icon or the screen title → app Home from the section's main
    // screen, else the section's main screen.
    val inSettingsSubtree = currentSubScreen in setOf(
        SettingsSubScreen.MAIN,
        SettingsSubScreen.SETTINGS_NETWORK,
        SettingsSubScreen.SETTINGS_NETWORK_API_CALLS,
        SettingsSubScreen.SETTINGS_UI,
        SettingsSubScreen.SETTINGS_LOGGING,
        SettingsSubScreen.SETTINGS_OTHER,
        SettingsSubScreen.SETTINGS_METADATA,
        SettingsSubScreen.SETTINGS_DEFAULT_ICONS
    )
    val sectionMain = if (inSettingsSubtree) SettingsSubScreen.MAIN else SettingsSubScreen.AI_SETUP
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalTopBarLeftIcon provides (sectionIconOverride ?: com.ai.ui.shared.TopBarLeftIcon(
            glyph = if (inSettingsSubtree) com.ai.data.MetadataIconsHolder.current.settings else com.ai.data.MetadataIconsHolder.current.agent,
            onClick = {
                if (currentSubScreen == sectionMain) onNavigateHome()
                else currentSubScreen = sectionMain
            }
        ))
    ) {
    when (currentSubScreen) {
        SettingsSubScreen.MAIN -> {
            SettingsMainScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = onBack, onNavigateHome = onNavigateHome,
                onOpenSubScreen = { currentSubScreen = it }
            )
        }
        SettingsSubScreen.AI_SETUP -> {
            SetupScreen(
                aiSettings = aiSettings,
                huggingFaceApiKey = generalSettings.huggingFaceApiKey, openRouterApiKey = generalSettings.openRouterApiKey,
                aaApiKey = generalSettings.artificialAnalysisApiKey,
                onBackToSettings = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it }, onSave = onSaveAi,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onSaveArtificialAnalysisApiKey = onSaveArtificialAnalysisApiKey,
                onNavigateToCostConfig = onNavigateToCostConfig
            )
        }
        SettingsSubScreen.AI_PROVIDERS -> {
            ProvidersScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome,
                scrollState = providersListScrollState,
                onHousekeeping = hkRefresh,
                onProviderSelected = { selectedProviderId = it.id; currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT },
                onAddProvider = { name ->
                    // Stub provider — every other field is empty / default;
                    // the user fills the rest in on the existing edit
                    // screen (single source of truth, including the
                    // SelectModelScreen entry for default model).
                    val service = com.ai.data.AppService(
                        id = name, baseUrl = "", adminUrl = "", defaultModel = ""
                    )
                    if (com.ai.data.ProviderRegistry.add(service)) {
                        selectedProviderId = name
                        currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
                    }
                }
            )
        }
        SettingsSubScreen.AI_PROVIDER_EDIT -> {
            selectedProvider?.let { provider ->
                ProviderSettingsScreen(
                    service = provider, aiSettings = aiSettings,
                    isLoadingModels = provider in loadingModelsFor,
                    fetchError = fetchModelsErrors[provider.id],
                    onBackToSettings = goBack, onBackToHome = onNavigateHome,
                    onSave = onSaveAi,
                    onFetchModels = {
                        val fresh = AppService.findById(provider.id) ?: provider
                        onFetchModels(fresh, it)
                    },
                    onFetchModelsAwait = { svc, key -> onFetchModelsAwait(svc, key) },
                    onTestApiKey = onTestAiModel, onProviderStateChange = { onProviderStateChange(provider, it) },
                    onProviderTestedOk = { defaultModel -> onProviderTestedOk(provider, defaultModel) },
                    onProviderTestedOkNoFetch = { defaultModel -> onProviderTestedOkNoFetch(provider, defaultModel) },
                    onReplaceDefaultAgent = { defaultModel -> onReplaceDefaultAgent(provider, defaultModel) },
                    onTestModelWithPrompt = { prompt ->
                        val fresh = AppService.findById(provider.id) ?: provider
                        onTestModelWithPrompt(fresh, aiSettings.getApiKey(fresh), aiSettings.getModel(fresh), prompt)
                    },
                    onNavigateToTrace = onNavigateToTrace,
                    onNavigateToModels = {
                        // Jump directly into the Models sub-screen for this provider; back returns here.
                        modelEditFromProvider = true
                        currentSubScreen = SettingsSubScreen.AI_MODEL_EDIT
                    },
                    onNavigateToHelpTopic = onNavigateToHelpTopic
                )
            } ?: run {
                // Provider id no longer resolves (removed/renamed in the
                // registry, or a cold-launch race before bootstrap). Navigate
                // away exactly once via a LaunchedEffect — calling goBack()
                // directly in the composable body re-fires every frame and
                // spins a recomposition loop if the id never resolves.
                androidx.compose.runtime.LaunchedEffect(selectedProviderId) { goBack() }
            }
        }
        SettingsSubScreen.AI_MODELS -> {
            ModelsListScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome,
                onProviderSelected = { selectedProviderId = it.id; currentSubScreen = SettingsSubScreen.AI_MODEL_EDIT },
                onRefreshAllModels = onRefreshAllModels,
                onNavigateToTraceCategory = onNavigateToTraceCategory
            )
        }
        SettingsSubScreen.AI_MODEL_EDIT -> {
            selectedProvider?.let { provider ->
                ProviderModelSettingsScreen(
                    service = provider, aiSettings = aiSettings,
                    isLoadingModels = provider in loadingModelsFor,
                    fetchError = fetchModelsErrors[provider.id],
                    onBack = goBack, onBackToHome = onNavigateHome,
                    onSave = onSaveAi,
                    onFetchModels = {
                        // Use the registry's current AppService so a baseUrl edit on
                        // the catalog flows through immediately.
                        val fresh = AppService.findById(provider.id) ?: provider
                        onFetchModels(fresh, it)
                    },
                    onNavigateToModelInfo = onNavigateToModelInfo,
                    onTestSpecificModel = { model, prompt ->
                        val fresh = AppService.findById(provider.id) ?: provider
                        onTestSpecificModel(fresh, aiSettings.getApiKey(fresh), model, prompt)
                    },
                    onNavigateToTrace = onNavigateToTrace
                )
            } ?: goBack()
        }
        SettingsSubScreen.AI_MODELS_SETUP -> {
            ModelsSetupScreen(
                aiSettings = aiSettings,
                hasActiveProvider = aiSettings.getActiveServices().isNotEmpty(),
                experimentalFeatures = generalSettings.experimentalFeaturesEnabled,
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it },
                onHousekeeping = hkRefresh
            )
        }
        SettingsSubScreen.AI_WORKERS_SETUP -> {
            WorkersSetupScreen(
                aiSettings = aiSettings,
                hasApiKey = aiSettings.hasAnyApiKey(),
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it }
            )
        }
        SettingsSubScreen.AI_PROMPTS_SETUP -> {
            PromptsSetupScreen(
                aiSettings = aiSettings,
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it },
                onOpenInternalPromptsHub = { currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB }
            )
        }
        SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB -> {
            InternalPromptsHubScreen(
                aiSettings = aiSettings,
                onBack = goBack, onBackToHome = onNavigateHome,
                onOpenInternalPrompts = { cat ->
                    selectedInternalCategory = cat
                    currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS
                },
                onOpenFanInOutHub = { currentSubScreen = SettingsSubScreen.AI_FAN_PROMPTS_HUB }
            )
        }
        SettingsSubScreen.AI_FAN_PROMPTS_HUB -> {
            FanInOutPromptsHubScreen(
                aiSettings = aiSettings,
                onBack = goBack, onBackToHome = onNavigateHome,
                onOpenInternalPrompts = { cat ->
                    selectedInternalCategory = cat
                    currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS
                }
            )
        }
        SettingsSubScreen.AI_LOCAL_MODELS_SETUP -> {
            LocalModelsSetupScreen(
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it }
            )
        }
        SettingsSubScreen.AI_MODEL_TYPES -> {
            ModelTypesScreen(
                generalSettings = generalSettings,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onSave = onSaveGeneral
            )
        }
        SettingsSubScreen.AI_MANUAL_MODEL_TYPES -> {
            com.ai.ui.cruds.models.manualoverrides.ManualOverridesCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_MODEL_COOLDOWNS -> {
            com.ai.ui.cruds.models.cooldowns.ModelCooldownsCrud(
                aiSettings = aiSettings,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onNavigateToTrace = onNavigateToTrace
            )
        }
        SettingsSubScreen.AI_BLOCKED_MODELS -> {
            com.ai.ui.cruds.models.blocked.BlockedModelsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_TEST_EXCLUDED_MODELS -> {
            com.ai.ui.cruds.models.testexcluded.TestExcludedModelsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onHousekeeping = hkTest
            )
        }
        SettingsSubScreen.AI_INACCESSIBLE_MODELS -> {
            com.ai.ui.cruds.models.inaccessible.InaccessibleModelsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onHousekeeping = hkTest
            )
        }
        SettingsSubScreen.AI_AGENTS -> {
            com.ai.ui.cruds.workers.agents.AgentsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome,
                deps = com.ai.ui.cruds.workers.agents.AgentEditDeps(
                    onTestAiModel = onTestAiModel,
                    onFetchModels = onFetchModels,
                    loadingModelsFor = loadingModelsFor,
                    fetchModelsErrors = fetchModelsErrors,
                    onNavigateToTrace = onNavigateToTrace,
                    onAddEndpoint = { provider, ep ->
                        val current = aiSettings.getEndpointsForProvider(provider)
                        onSaveAi(aiSettings.withEndpoints(provider, current + ep))
                    }
                )
            )
        }
        SettingsSubScreen.AI_AGENT_EDIT -> {
            // Deep-link entry (SETTINGS_AGENT_EDIT route) — the AI_AGENTS
            // list itself uses AgentsCrud's own internal edit overlay.
            val agent = editingAgentId?.let { aiSettings.getAgentById(it) }
            AgentEditScreen(
                agent = agent, aiSettings = aiSettings,
                existingNames = aiSettings.agents.filter { it.id != (agent?.id ?: "") }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet(),
                onTestAiModel = onTestAiModel, onFetchModels = onFetchModels,
                onSave = { saved ->
                    val updated = if (agent != null) aiSettings.copy(agents = aiSettings.agents.map { if (it.id == agent.id) saved else it })
                    else aiSettings.copy(agents = aiSettings.agents + saved)
                    onSaveAi(updated); goBack()
                },
                onAddEndpoint = { provider, ep ->
                    val current = aiSettings.getEndpointsForProvider(provider)
                    onSaveAi(aiSettings.withEndpoints(provider, current + ep))
                },
                onBack = goBack, onNavigateHome = onNavigateHome,
                loadingModelsFor = loadingModelsFor,
                fetchModelsErrors = fetchModelsErrors,
                onNavigateToTrace = onNavigateToTrace,
                onOpenView = agent?.id?.let { aid ->
                    onNavigateToAgentView?.let { { it(aid) } }
                }
            )
        }
        SettingsSubScreen.AI_FLOCKS -> {
            com.ai.ui.cruds.workers.flocks.FlocksCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_FLOCK_EDIT -> {
            val flock = editingFlockId?.let { aiSettings.getFlockById(it) }
            FlockEditScreen(
                flock = flock, aiSettings = aiSettings,
                existingNames = aiSettings.flocks.filter { it.id != (flock?.id ?: "") }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet(),
                onSave = { saved ->
                    val updated = if (flock != null) aiSettings.copy(flocks = aiSettings.flocks.map { if (it.id == flock.id) saved else it })
                    else aiSettings.copy(flocks = aiSettings.flocks + saved)
                    onSaveAi(updated); goBack()
                },
                onBack = goBack, onNavigateHome = onNavigateHome,
                onOpenView = flock?.id?.let { fid ->
                    onNavigateToFlockView?.let { { it(fid) } }
                }
            )
        }
        SettingsSubScreen.AI_SWARMS -> {
            com.ai.ui.cruds.workers.swarms.SwarmsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_SWARM_EDIT -> {
            val swarm = editingSwarmId?.let { aiSettings.getSwarmById(it) }
            SwarmEditScreen(
                swarm = swarm, aiSettings = aiSettings,
                existingNames = aiSettings.swarms.filter { it.id != (swarm?.id ?: "") }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet(),
                onSave = { saved ->
                    val updated = if (swarm != null) aiSettings.copy(swarms = aiSettings.swarms.map { if (it.id == swarm.id) saved else it })
                    else aiSettings.copy(swarms = aiSettings.swarms + saved)
                    onSaveAi(updated); goBack()
                },
                onBack = goBack, onNavigateHome = onNavigateHome,
                onOpenView = swarm?.id?.let { sid ->
                    onNavigateToSwarmView?.let { { it(sid) } }
                }
            )
        }
        SettingsSubScreen.AI_PARAMETERS -> {
            com.ai.ui.cruds.parameters.ParametersCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_SYSTEM_PROMPTS -> {
            com.ai.ui.cruds.prompts.system.SystemPromptsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_SYSTEM_PROMPT_EDIT -> {
            val sp = editingSystemPromptId?.let { aiSettings.getSystemPromptById(it) }
            SystemPromptEditScreen(
                systemPrompt = sp,
                existingNames = aiSettings.systemPrompts.filter { it.id != (sp?.id ?: "") }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet(),
                onSave = { saved ->
                    val updated = if (sp != null) aiSettings.copy(systemPrompts = aiSettings.systemPrompts.map { if (it.id == sp.id) saved else it })
                    else aiSettings.copy(systemPrompts = aiSettings.systemPrompts + saved)
                    onSaveAi(updated); goBack()
                },
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_INTERNAL_PROMPTS -> {
            com.ai.ui.cruds.prompts.internal.InternalPromptCrud(
                aiSettings = aiSettings,
                category = selectedInternalCategory,
                onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onNavigateToTraceCategory = onNavigateToTraceCategory
            )
        }
        SettingsSubScreen.AI_EXAMPLE_PROMPTS -> {
            com.ai.ui.cruds.prompts.examples.ExamplePromptsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_EXAMPLE_PROMPT_EDIT -> {
            val ep = editingExamplePromptId?.let { aiSettings.getExamplePromptById(it) }
            key(ep?.id) {
                ExamplePromptEditScreen(
                    examplePrompt = ep,
                    onSave = { saved ->
                        val updated = if (ep != null) aiSettings.copy(examplePrompts = aiSettings.examplePrompts.map { if (it.id == ep.id) saved else it })
                        else aiSettings.copy(examplePrompts = aiSettings.examplePrompts + saved)
                        onSaveAi(updated); goBack()
                    },
                    onBack = goBack, onNavigateHome = onNavigateHome
                )
            }
        }
        SettingsSubScreen.AI_INTERNAL_PROMPT_EDIT -> {
            val ip = editingInternalPromptId?.let { aiSettings.getInternalPromptById(it) }
            // Deep-link safety: when the caller asked to edit a
            // specific id but aiSettings hasn't bootstrapped yet, ip
            // resolves to null. The InternalPromptEditScreen captures
            // its initial state via remember{} on first composition,
            // so an empty form shown here would silently create a
            // duplicate prompt on Save once the user typed a name.
            // Treat the not-yet-loaded case as a transient loading
            // state — InternalPromptEditScreen is keyed on ip?.id so
            // it re-initialises when the lookup resolves.
            if (editingInternalPromptId != null && ip == null) {
                Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
                ) {
                    TitleBar(helpTopic = "settings_main", title = "Loading…", subject = "Loading settings…", onBackClick = goBack)
                }
            } else {
                // Derive the category synchronously from the resolved prompt
                // when editing an existing one — selectedInternalCategory is
                // corrected by a LaunchedEffect that can lag this first
                // composition, which (for a very fast user) could otherwise
                // pin a save to the wrong category. Fall back to the selected
                // category only when adding a brand-new prompt (ip == null).
                val effectiveCategory = ip?.category ?: selectedInternalCategory
                key(ip?.id) {
                    InternalPromptEditScreen(
                        internalPrompt = ip,
                        // Names are unique within a category, not across all
                        // internal prompts — so "Compare" under meta and
                        // "Compare" under fan_in can coexist.
                        existingNames = aiSettings.internalPrompts
                            .filter { it.id != (ip?.id ?: "") && it.category == effectiveCategory }
                            .map { it.name.lowercase(java.util.Locale.ROOT) }
                            .toSet(),
                        agentNames = aiSettings.agents.map { it.name },
                        aiSettings = aiSettings,
                        fixedCategory = effectiveCategory,
                        onSave = { saved ->
                            val updated = if (ip != null) aiSettings.copy(internalPrompts = aiSettings.internalPrompts.map { if (it.id == ip.id) saved else it })
                            else aiSettings.copy(internalPrompts = aiSettings.internalPrompts + saved)
                            onSaveAi(updated); goBack()
                        },
                        onBack = goBack, onNavigateHome = onNavigateHome
                    )
                }
            }
        }
        SettingsSubScreen.AI_EXTERNAL_SERVICES -> {
            ExternalServicesScreen(
                huggingFaceApiKey = generalSettings.huggingFaceApiKey, openRouterApiKey = generalSettings.openRouterApiKey,
                artificialAnalysisApiKey = generalSettings.artificialAnalysisApiKey,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onSaveArtificialAnalysisApiKey = onSaveArtificialAnalysisApiKey,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
        }
        SettingsSubScreen.AI_APP_SETTINGS -> {
            AppSettingsScreen(
                generalSettings = generalSettings, aiSettings = aiSettings,
                onSave = onSaveGeneral, onBack = goBack
            )
        }
        SettingsSubScreen.AI_DEFAULT_META_ITEMS -> {
            com.ai.ui.cruds.defaultmetaitems.DefaultMetaItemsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_LOCAL_LITERT_MODELS -> {
            LocalLiteRtModelsScreen(onBack = goBack, onNavigateHome = onNavigateHome)
        }
        SettingsSubScreen.AI_LOCAL_LLMS -> {
            LocalLlmsScreen(onBack = goBack, onNavigateHome = onNavigateHome)
        }
        SettingsSubScreen.AI_IMPORT_EXPORT -> {
            // Fold to Import-only when no provider is active yet —
            // there's nothing meaningful to export at that point, but
            // Import from another install is exactly the use case.
            // Same condition Housekeeping uses for its rename.
            val importOnly = aiSettings.getActiveServices().isEmpty()
            ImportExportScreen(
                aiSettings = aiSettings,
                generalSettings = generalSettings,
                huggingFaceApiKey = generalSettings.huggingFaceApiKey, openRouterApiKey = generalSettings.openRouterApiKey,
                artificialAnalysisApiKey = generalSettings.artificialAnalysisApiKey,
                onSave = onSaveAi,
                onSaveGeneral = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome,
                importOnly = importOnly,
                onStartRefreshAll = onStartRefreshAll,
                onStartRefreshWorkers = onStartRefreshWorkers,
                onNavigateToRefresh = onNavigateToRefresh
            )
        }
        SettingsSubScreen.AI_REFRESH -> {
            RefreshScreen(
                aiSettings = aiSettings,
                openRouterApiKey = generalSettings.openRouterApiKey,
                artificialAnalysisApiKey = generalSettings.artificialAnalysisApiKey,
                onSave = onSaveAi,
                refreshAllState = refreshAllState,
                onStartRefreshAll = onStartRefreshAll,
                onStartRefreshWorkers = onStartRefreshWorkers,
                onClearRefreshAllState = onClearRefreshAllState,
                onOpenProvider = { svc ->
                    selectedProviderId = svc.id
                    currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
                },
                onNavigateToHelpTopic = onNavigateToHelpTopic,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onSettings = settingsModelsSetup
            )
        }
        SettingsSubScreen.SETTINGS_NETWORK -> {
            NetworkSettingsSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onOpenSubScreen = { currentSubScreen = it }
            )
        }
        SettingsSubScreen.SETTINGS_NETWORK_API_CALLS -> {
            MaximalApiCallsSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_UI -> {
            UiTweaksSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_LOGGING -> {
            LoggingAndTracingSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_OTHER -> {
            OtherSettingsSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_METADATA -> {
            MetadataSettingsSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_DEFAULT_ICONS -> {
            DefaultIconsSubScreen(
                generalSettings = generalSettings, aiSettings = aiSettings,
                onAskModelText = onAskModelText, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
    }
    }
}

// ===== Main Settings Screen (General) =====

@Composable
private fun SettingsMainScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSubScreen: (SettingsSubScreen) -> Unit = {}
) {
    // No local preference state on the main screen any more — every
    // editable card lives in one of the four sub-screens reached via
    // the nav rows below.
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_main", title = "Settings", subject = "App preferences, grouped by topic", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Nav rows into the four preference buckets. Each opens
            // its own full-screen sub-screen with its own TitleBar +
            // help topic. The main Settings page is now purely a
            // table-of-contents — every actual control lives one tap
            // deeper.
            SettingsNavCard(
                icon = MetadataDefaults.WEB,
                title = "Network settings",
                description = "Read timeouts, per-provider throttling, 429 / 529 retry policy.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_NETWORK) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.PALETTE,
                title = "UI tweaks",
                description = "Model name layout, full-screen, experimental features.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_UI) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.APP_LOG,
                title = "Logging and tracing",
                description = "API tracing master switch and application log level.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_LOGGING) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.LABEL,
                title = "Metadata & icons",
                description = "Master switch for all optional metadata — report icon / language / title, per-model icons / titles, fan & meta icons.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_METADATA) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.PALETTE,
                title = "Default icons",
                description = "Edit the fallback emoji shown when a report or result has no generated icon of its own.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_DEFAULT_ICONS) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.SETTINGS,
                title = "Other settings",
                description = "Identity (Name + Email), auto-create Rerank & Moderation.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_OTHER) }
            )
        }
    }
}

/** Tap-target row used on the main Settings screen to drill into a
 *  preference sub-screen. Visual style mirrors SetupNavCard so the
 *  navigation pattern feels consistent across the hub-style screens. */
@Composable
private fun SettingsNavCard(
    icon: String,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    val mi = LocalMetadataIcons.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                mi.forFactoryGlyph(icon),
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(42.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(description, fontSize = 12.sp, color = AppColors.TextTertiary)
            }
            Text(">", fontSize = 16.sp, color = AppColors.Blue)
        }
    }
}

// ===== Carved-out preference buckets =====
//
// Each sub-screen owns its slice of GeneralSettings: it mirrors the
// fields it cares about into local state, debounces saves through
// the same 400ms pattern the main screen uses, and flushes any
// pending edit on dispose so a quick back-tap doesn't lose the
// last keystroke. The other fields on the parent GeneralSettings
// flow through unchanged via .copy(), so the three sub-screens
// don't clobber each other even when the user navigates between
// them quickly.

/** Network read timeouts + per-provider throttling + per-provider
 *  retries. Each field stored as text so partial / empty edits
 *  don't fight the keystroke; parsed back via toIntOrNull /
 *  toLongOrNull on save, with the previous value preserved when
 *  the field is blank or non-numeric. */
@Composable
private fun NetworkSettingsSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSubScreen: (SettingsSubScreen) -> Unit
) {
    var streamingReadTimeoutText by remember {
        mutableStateOf(generalSettings.streamingReadTimeoutSec.toString())
    }
    var nonStreamingReadTimeoutText by remember {
        mutableStateOf(generalSettings.nonStreamingReadTimeoutSec.toString())
    }
    var maxCallsPerMinuteText by remember {
        mutableStateOf(generalSettings.maxCallsPerProviderPerMinute.toString())
    }
    var maxConcurrentCallsText by remember {
        mutableStateOf(generalSettings.maxConcurrentCallsPerProvider.toString())
    }
    var maxRetriesText by remember {
        mutableStateOf(generalSettings.maxRetriesOn429.toString())
    }
    var retryBackoffMs429Text by remember {
        mutableStateOf(generalSettings.retryBackoffMs429.toString())
    }
    var maxRetries529Text by remember {
        mutableStateOf(generalSettings.maxRetriesOn529.toString())
    }
    var retryBackoffMs529Text by remember {
        mutableStateOf(generalSettings.retryBackoffMs529.toString())
    }

    fun build(): GeneralSettings = generalSettings.copy(
        streamingReadTimeoutSec = streamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.streamingReadTimeoutSec,
        nonStreamingReadTimeoutSec = nonStreamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.nonStreamingReadTimeoutSec,
        maxCallsPerProviderPerMinute = maxCallsPerMinuteText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxCallsPerProviderPerMinute,
        maxConcurrentCallsPerProvider = maxConcurrentCallsText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentCallsPerProvider,
        // 0 is a valid maxRetries setting (no in-line retries) — coerce ≥ 0.
        maxRetriesOn429 = maxRetriesText.toIntOrNull()?.coerceAtLeast(0)
            ?: generalSettings.maxRetriesOn429,
        retryBackoffMs429 = retryBackoffMs429Text.toLongOrNull()?.coerceAtLeast(1L)
            ?: generalSettings.retryBackoffMs429,
        maxRetriesOn529 = maxRetries529Text.toIntOrNull()?.coerceAtLeast(0)
            ?: generalSettings.maxRetriesOn529,
        retryBackoffMs529 = retryBackoffMs529Text.toLongOrNull()?.coerceAtLeast(1L)
            ?: generalSettings.retryBackoffMs529
    )

    LaunchedEffect(
        streamingReadTimeoutText, nonStreamingReadTimeoutText,
        maxCallsPerMinuteText, maxConcurrentCallsText,
        maxRetriesText, retryBackoffMs429Text,
        maxRetries529Text, retryBackoffMs529Text
    ) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_network", title = "Network settings", subject = "Timeouts, throttling and retry rules", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Nav row to the Maximal API calls sub-screen. Styled like
            // SettingCard (same surface, title weight, chevron) so it
            // sits in visually with the other cards on this page —
            // tapping opens the deeper screen instead of expanding.
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onOpenSubScreen(SettingsSubScreen.SETTINGS_NETWORK_API_CALLS)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsCardHeaderIcon(MetadataDefaults.CONTROLS)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Maximal API calls",
                            fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▸", color = AppColors.TextTertiary)
                    }
                }
            }
            SettingCard(
                "Network read timeouts",
                "How long the app waits for an API response before giving up. Streaming applies to chat / report SSE streams (the timeout is the gap between chunks, so the long default is normal). Non-streaming applies to analyze, meta, rerank, fetch-models, translate — everything that blocks for the full response body. Provider-test calls always cap at 30 s regardless.",
                MetadataDefaults.STATUS_ALARM
            ) {
                OutlinedTextField(
                    value = streamingReadTimeoutText,
                    onValueChange = { streamingReadTimeoutText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Streaming (seconds)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = nonStreamingReadTimeoutText,
                    onValueChange = { nonStreamingReadTimeoutText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Non-streaming (seconds)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Per-provider throttling",
                "Caps the load the app puts on any single provider. Calls beyond the per-minute rate sleep until the sliding window opens up; concurrent calls beyond the cap queue on a per-host semaphore. Defaults: 60 calls/minute, 5 in flight at once.",
                MetadataDefaults.CONTROLS
            ) {
                OutlinedTextField(
                    value = maxCallsPerMinuteText,
                    onValueChange = { maxCallsPerMinuteText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max calls per provider per minute") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = maxConcurrentCallsText,
                    onValueChange = { maxConcurrentCallsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max concurrent calls per provider") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "429 error handling",
                "When a provider answers HTTP 429 (rate-limited), the OkHttp client waits and re-issues the same request up to this many times. Set retries to 0 to disable in-line retries entirely (the outer retry layer still gets a chance on transient 4xx). Defaults: 3 retries, 1000 ms between each.",
                MetadataDefaults.ROADBLOCK
            ) {
                OutlinedTextField(
                    value = maxRetriesText,
                    onValueChange = { maxRetriesText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max retries on 429") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = retryBackoffMs429Text,
                    onValueChange = { retryBackoffMs429Text = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Wait between retries (ms)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "529 error handling",
                "When a provider answers HTTP 529 (server overloaded), the OkHttp client waits and re-issues the same request up to this many times. Set retries to 0 to disable in-line retries entirely (the outer retry layer still gets a chance on transient 5xx). Defaults: 3 retries, 1000 ms between each.",
                MetadataDefaults.EXPLOSION
            ) {
                OutlinedTextField(
                    value = maxRetries529Text,
                    onValueChange = { maxRetries529Text = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max retries on 529") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = retryBackoffMs529Text,
                    onValueChange = { retryBackoffMs529Text = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Wait between retries (ms)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
        }
    }
}

/** Maximal API calls — global + per-kind concurrency caps. Sits
 *  one tap deeper than Network settings; each field flows into
 *  [com.ai.data.ApiCallCaps] via [updateGeneralSettings]. */
@Composable
private fun MaximalApiCallsSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var apiText by remember { mutableStateOf(generalSettings.maxConcurrentApiCalls.toString()) }
    var reportText by remember { mutableStateOf(generalSettings.maxConcurrentReportCalls.toString()) }
    var translationText by remember { mutableStateOf(generalSettings.maxConcurrentTranslationCalls.toString()) }
    var fanOutText by remember { mutableStateOf(generalSettings.maxConcurrentFanOutCalls.toString()) }
    var fanMetaText by remember { mutableStateOf(generalSettings.maxConcurrentFanMetaCalls.toString()) }
    var testText by remember { mutableStateOf(generalSettings.maxTestApiCalls.toString()) }

    fun build(): GeneralSettings = generalSettings.copy(
        maxConcurrentApiCalls = apiText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentApiCalls,
        maxConcurrentReportCalls = reportText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentReportCalls,
        maxConcurrentTranslationCalls = translationText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentTranslationCalls,
        maxConcurrentFanOutCalls = fanOutText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentFanOutCalls,
        maxConcurrentFanMetaCalls = fanMetaText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentFanMetaCalls,
        maxTestApiCalls = testText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxTestApiCalls
    )

    LaunchedEffect(apiText, reportText, translationText, fanOutText, fanMetaText, testText) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "settings_network_api_calls",
            title = "Maximal API calls", subject = "How many calls run at once",
            onBackClick = onBack
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingCard(
                "Concurrent API calls at the same time",
                "Hard global ceiling on every API call the app keeps in flight at once — reports, translations, fan-out, and any sub-dispatcher under them. Calls beyond the cap suspend until a permit frees up. Default 100.",
                MetadataDefaults.CLOUD
            ) {
                OutlinedTextField(
                    value = apiText,
                    onValueChange = { apiText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent API calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Model reports API calls",
                "Cap on the primary per-agent calls fired during a new-report run. The global cap still wins if it's lower. Default 50.",
                MetadataDefaults.REPORT_ICON
            ) {
                OutlinedTextField(
                    value = reportText,
                    onValueChange = { reportText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Model reports calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Translations API calls",
                "Cap on per-item translation calls inside a translation run. With multi-model translation runs, the cap is on the total across models, not per model. Default 50.",
                MetadataDefaults.TRANSLATE
            ) {
                OutlinedTextField(
                    value = translationText,
                    onValueChange = { translationText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Translation calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Fan Out API calls",
                "Cap on per-pair fan-out calls. The per-provider cap (Network settings → Per-provider throttling) still applies on top, so a single-provider fan-out still respects that limit. Default 50.",
                MetadataDefaults.FAN_OUT
            ) {
                OutlinedTextField(
                    value = fanOutText,
                    onValueChange = { fanOutText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Fan Out calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Fan Meta API calls",
                "Cap on the Fan Meta batch — the title+icon generation the user launches from a fan-out's Fan Meta button. Separate from the fan-out cap so the two can run side-by-side without halving each other's budget. Default 50.",
                MetadataDefaults.META
            ) {
                OutlinedTextField(
                    value = fanMetaText,
                    onValueChange = { fanMetaText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Fan Meta calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Test all models API calls",
                "Cap on the \"Test all models\" run (Housekeeping → Test). A run probes every configured model of every active provider, so this controls how hard that sweep hits the network. Default 50.",
                MetadataDefaults.TEST
            ) {
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Test all models calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
        }
    }
}

/** Visual / layout preferences that don't affect the network layer.
 *  Two cards: Model name layout, Show AI Knowledge card on home page. */
@Composable
private fun UiTweaksSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var modelNameLayout by remember { mutableStateOf(generalSettings.modelNameLayout) }
    var showKnowledgeCard by remember { mutableStateOf(generalSettings.showKnowledgeCard) }
    var fullScreen by remember { mutableStateOf(generalSettings.fullScreen) }
    var experimentalFeatures by remember { mutableStateOf(generalSettings.experimentalFeaturesEnabled) }

    fun build(): GeneralSettings = generalSettings.copy(
        modelNameLayout = modelNameLayout,
        showKnowledgeCard = showKnowledgeCard,
        fullScreen = fullScreen,
        experimentalFeaturesEnabled = experimentalFeatures
    )

    LaunchedEffect(modelNameLayout, showKnowledgeCard, fullScreen, experimentalFeatures) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_ui", title = "UI tweaks", subject = "Visual and layout preferences", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingCard("Model name layout", "How model labels render across rows and pickers.", MetadataDefaults.LABEL) {
                Column {
                    RadioRow(
                        selected = modelNameLayout == com.ai.viewmodel.ModelNameLayout.MODEL_ONLY,
                        label = "Model name only",
                        onClick = { modelNameLayout = com.ai.viewmodel.ModelNameLayout.MODEL_ONLY }
                    )
                    RadioRow(
                        selected = modelNameLayout == com.ai.viewmodel.ModelNameLayout.PROVIDER_AND_MODEL,
                        label = "Provider and model name",
                        onClick = { modelNameLayout = com.ai.viewmodel.ModelNameLayout.PROVIDER_AND_MODEL }
                    )
                }
            }
            ToggleSettingCard(
                title = "Experimental features",
                description = "Master gate for on-device Local LLMs, LiteRT embedders, AI Knowledge / RAG, and Local Semantic Search. Off (default) hides those UI surfaces — installed model files and KBs stay on disk, and any KB already attached to a chat or report keeps sending context at API time.",
                icon = MetadataDefaults.SPARKLES,
                checked = experimentalFeatures,
                onCheckedChange = { experimentalFeatures = it }
            )
            if (experimentalFeatures) {
                ToggleSettingCard(
                    title = "Show Knowledge card on home page",
                    description = "Show the AI Knowledge / RAG card on the Hub. Off hides the card — knowledge bases still work via the share-target chooser, and any KB already attached to a chat or report is unaffected.",
                    icon = MetadataDefaults.LIBRARY,
                    checked = showKnowledgeCard,
                    onCheckedChange = { showKnowledgeCard = it }
                )
            }
            ToggleSettingCard(
                title = "Full screen",
                description = "Hide the Android status bar (clock / battery / signal) so the app uses the full screen height. Swipe down from the top edge to reveal the bar transiently.",
                icon = MetadataDefaults.DEVICE,
                checked = fullScreen,
                onCheckedChange = { fullScreen = it }
            )
        }
    }
}

/** Everything that doesn't fit the network / UI / logging buckets:
 *  the user's Name + Email used for outbound prompts and email
 *  exports, plus the master switch for the per-report icon-gen
 *  feature. Keeps Settings main as a pure nav list. */
@Composable
private fun OtherSettingsSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var userName by remember { mutableStateOf(generalSettings.userName) }
    var defaultEmail by remember { mutableStateOf(generalSettings.defaultEmail) }
    var autoCreateRerankAndModeration by remember { mutableStateOf(generalSettings.autoCreateRerankAndModeration) }

    fun build(): GeneralSettings = generalSettings.copy(
        userName = userName,
        defaultEmail = defaultEmail,
        autoCreateRerankAndModeration = autoCreateRerankAndModeration
    )

    LaunchedEffect(userName, defaultEmail, autoCreateRerankAndModeration) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_other", title = "Other settings", subject = "Identity and report automation", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingCard("Identity", "Used as the human side of the conversation in agent prompts; the email pre-fills the export sheet so you don't retype it on every send.", MetadataDefaults.MAIL) {
                OutlinedTextField(
                    value = userName, onValueChange = { userName = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = defaultEmail, onValueChange = { defaultEmail = it },
                    label = { Text("Email address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            ToggleSettingCard(
                title = "Auto create Rerank and Moderation",
                description = "When a report's models all finish, automatically create one Rerank and one Moderation — each using the first rerank- / moderation-capable model found among your active providers. A kind is skipped when no capable model exists or one is already present. Manual Rerank / Moderation still lets you pick the model.",
                icon = MetadataDefaults.REPEAT,
                checked = autoCreateRerankAndModeration,
                onCheckedChange = { autoCreateRerankAndModeration = it }
            )
        }
    }
}

/** "Metadata & icons" — the grand-master switch for every optional
 *  metadata item plus the per-item sub-toggles it gates. When the master
 *  is off the sub-toggles are hidden (and every item is treated as off
 *  app-wide). Default on, mirroring the old per-item defaults. */
@Composable
private fun MetadataSettingsSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var metadataEnabled by remember { mutableStateOf(generalSettings.metadataEnabled) }
    var reportTitleMode by remember { mutableStateOf(generalSettings.reportTitleMode) }
    var iconGenEnabled by remember { mutableStateOf(generalSettings.iconGenEnabled) }
    var reportLanguageGenEnabled by remember { mutableStateOf(generalSettings.reportLanguageGenEnabled) }
    var perModelIconGenEnabled by remember { mutableStateOf(generalSettings.perModelIconGenEnabled) }
    var perModelTitleGenEnabled by remember { mutableStateOf(generalSettings.perModelTitleGenEnabled) }
    var useInternalPromptsIcons by remember { mutableStateOf(generalSettings.useInternalPromptsIcons) }
    var autostartFanMeta by remember { mutableStateOf(generalSettings.autostartFanMeta) }

    fun build(): GeneralSettings = generalSettings.copy(
        metadataEnabled = metadataEnabled,
        reportTitleMode = reportTitleMode,
        iconGenEnabled = iconGenEnabled,
        reportLanguageGenEnabled = reportLanguageGenEnabled,
        perModelIconGenEnabled = perModelIconGenEnabled,
        perModelTitleGenEnabled = perModelTitleGenEnabled,
        useInternalPromptsIcons = useInternalPromptsIcons,
        autostartFanMeta = autostartFanMeta
    )

    LaunchedEffect(metadataEnabled, reportTitleMode, iconGenEnabled, reportLanguageGenEnabled, perModelIconGenEnabled, perModelTitleGenEnabled, useInternalPromptsIcons, autostartFanMeta) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_metadata", title = "Metadata & icons", subject = "Master switch and per-item options for optional report metadata", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleSettingCard(
                title = "Generate metadata & icons",
                description = "Grand-master switch for every optional metadata item: report icon, report language, AI report title, per-model icons & titles, Fan Out icons & titles, and the meta / rerank / moderate / translate row icons. When off, none of it is generated and all of its UI disappears — the Fan Out Icons / Titles buttons, the Manage report 'info' row — and a new report must be given a manual title. View screens are unaffected: a report that already has icons keeps showing them. Turn it on to reveal the per-item toggles below.",
                icon = MetadataDefaults.PALETTE,
                checked = metadataEnabled,
                onCheckedChange = { metadataEnabled = it }
            )
            if (metadataEnabled) {
                SettingCard("Report title", "How a new report's title is decided. Manual keeps the Title input field on the New Report screen. Generate (default) hides the field and runs a background LLM call after report start that fills the title from the prompt body — the resolved title shows on the 'title' row of the Manage report screen, alongside the icon and language rows.", MetadataDefaults.DOCUMENT) {
                    Column {
                        RadioRow(
                            selected = reportTitleMode == com.ai.viewmodel.ReportTitleMode.Manual,
                            label = "Manual — type a title yourself",
                            onClick = { reportTitleMode = com.ai.viewmodel.ReportTitleMode.Manual }
                        )
                        RadioRow(
                            selected = reportTitleMode == com.ai.viewmodel.ReportTitleMode.AI,
                            label = "Generate from the prompt",
                            onClick = { reportTitleMode = com.ai.viewmodel.ReportTitleMode.AI }
                        )
                    }
                }
                ToggleSettingCard(
                    title = "Generate report icon",
                    description = "Run a small LLM call at the start of every report to pick a fitting emoji icon. The icon shows in the title bar, hub list, history, and search hits. Turn this off to skip the call and hide every report-icon affordance.",
                    icon = MetadataDefaults.REPORT_ICON,
                    checked = iconGenEnabled,
                    onCheckedChange = { iconGenEnabled = it }
                )
                ToggleSettingCard(
                    title = "Generate report language",
                    description = "Detect the report's language and pick a flag emoji for it (a two-step LLM call after report start). Surfaces as the 'language' row on the info screen and as a flag on the language picker. Independent of the report icon.",
                    icon = MetadataDefaults.LANGUAGE,
                    checked = reportLanguageGenEnabled,
                    onCheckedChange = { reportLanguageGenEnabled = it }
                )
                ToggleSettingCard(
                    title = "Generate per model icons",
                    description = "Auto-run the 3-tier per-agent icon chain (chat continuation → one-shot template → fixed-agent fallback) at the end of every report run. Each successful agent's leftmost ${com.ai.data.MetadataIconsHolder.current.statusDone} flips to a returned emoji once the chain finishes for that row. Costs accumulate on the row's cost cell and post to Usage statistics with kind=\"icon\".",
                    icon = MetadataDefaults.MODEL_ICON,
                    checked = perModelIconGenEnabled,
                    onCheckedChange = { perModelIconGenEnabled = it }
                )
                ToggleSettingCard(
                    title = "Generate per model titles",
                    description = "After each model response, run a short Anthropic call (internal/model_title) to title that response in ≤4 words. The title replaces the model name on the Manage report 'report' row; its cost folds into that row and into a 'Model titles' category on the Costs screen. Off by default — it's one extra LLM call per model.",
                    icon = MetadataDefaults.LABEL,
                    checked = perModelTitleGenEnabled,
                    onCheckedChange = { perModelTitleGenEnabled = it }
                )
                ToggleSettingCard(
                    title = "Use internal prompts icons",
                    description = "Generate a small emoji for each Internal Prompt and show it as a leading glyph on the secondary-result rows of the report result page (compare / critique / rerank / fan-out / …). One LLM call per (name, title) — results cached persistently and reused across reports. Renaming a prompt or editing its title invalidates only that entry.",
                    icon = MetadataDefaults.SYSTEM_PROMPT,
                    checked = useInternalPromptsIcons,
                    onCheckedChange = { useInternalPromptsIcons = it }
                )
                ToggleSettingCard(
                    title = "Autostart Fan Meta",
                    description = "When a Fan Out finishes with no errored pairs, automatically kick off its Fan Meta batch (one call per pair produces both the title and the icon) — so you don't have to tap the Fan Meta button by hand. A run with any error pair is left alone; you can still start it manually.",
                    icon = MetadataDefaults.FAN_OUT,
                    checked = autostartFanMeta,
                    onCheckedChange = { autostartFanMeta = it }
                )
            }
        }
    }
}

/** One editable row on the Default icons screen, bound to a single
 *  [com.ai.data.MetadataIcons] field: its [label], a getter/setter over the
 *  immutable data class, and the [factory] glyph a blank field falls back to. */
private class IconRowSpec(
    val label: String,
    val get: (com.ai.data.MetadataIcons) -> String,
    val set: (com.ai.data.MetadataIcons, String) -> com.ai.data.MetadataIcons,
    val factory: String,
)

/** Every editable icon, grouped into the sections shown on the Default icons
 *  screen. Covers the report/secondary fallbacks rendered on the view screens
 *  AND every glyph the bottom action bars draw, so nothing in the bars is
 *  hard-coded — the render sites (buildBottomBarIcons / ViewBottomBar) read the
 *  live values straight from this same MetadataIcons set. */
private val DEFAULT_ICON_SECTIONS: List<Pair<String, List<IconRowSpec>>> = run {
    val d = com.ai.data.MetadataDefaults
    listOf(
        "Report" to listOf(
            IconRowSpec("Report", { it.reportIcon }, { m, v -> m.copy(reportIcon = v) }, d.REPORT_ICON),
            IconRowSpec("Report model", { it.reportModelIcon }, { m, v -> m.copy(reportModelIcon = v) }, d.MODEL_ICON),
        ),
        "Secondary results" to listOf(
            IconRowSpec("Rerank", { it.rerank }, { m, v -> m.copy(rerank = v) }, d.RERANK),
            IconRowSpec("Moderate", { it.moderate }, { m, v -> m.copy(moderate = v) }, d.MODERATE),
            IconRowSpec("Meta", { it.meta }, { m, v -> m.copy(meta = v) }, d.META),
            IconRowSpec("Tournament", { it.tournament }, { m, v -> m.copy(tournament = v) }, d.TOURNAMENT),
            IconRowSpec("Judges", { it.judges }, { m, v -> m.copy(judges = v) }, d.JUDGES),
            IconRowSpec("Compare", { it.compare }, { m, v -> m.copy(compare = v) }, d.COMPARE),
            IconRowSpec("Fan out", { it.fanOutRow }, { m, v -> m.copy(fanOutRow = v) }, d.FAN_OUT),
            IconRowSpec("Fan in", { it.fanInRow }, { m, v -> m.copy(fanInRow = v) }, d.FAN_IN),
        ),
        "Translation" to listOf(
            IconRowSpec("Language", { it.languageIcon }, { m, v -> m.copy(languageIcon = v) }, d.LANGUAGE),
            IconRowSpec("Translation row", { it.translationRow }, { m, v -> m.copy(translationRow = v) }, d.TRANSLATE),
            IconRowSpec("Translation compare", { it.translationCompare }, { m, v -> m.copy(translationCompare = v) }, d.TRANSLATION_COMPARE),
        ),
        "Monitor bar" to listOf(
            IconRowSpec("Live dashboard", { it.liveDashboard }, { m, v -> m.copy(liveDashboard = v) }, d.LIVE_DASHBOARD),
            IconRowSpec("Traces", { it.traces }, { m, v -> m.copy(traces = v) }, d.TRACES),
            IconRowSpec("App log", { it.appLog }, { m, v -> m.copy(appLog = v) }, d.APP_LOG),
            IconRowSpec("Audit", { it.audit }, { m, v -> m.copy(audit = v) }, d.AUDIT),
            IconRowSpec("Statistics (monitor)", { it.statisticsMonitor }, { m, v -> m.copy(statisticsMonitor = v) }, d.STATISTICS_MONITOR),
        ),
        "Create & chat" to listOf(
            IconRowSpec("Add / new", { it.add }, { m, v -> m.copy(add = v) }, d.ADD),
            IconRowSpec("Chat", { it.chat }, { m, v -> m.copy(chat = v) }, d.CHAT),
            IconRowSpec("Agent chat", { it.agentChat }, { m, v -> m.copy(agentChat = v) }, d.AGENT_CHAT),
            IconRowSpec("Temperature sweep", { it.temperatureSweep }, { m, v -> m.copy(temperatureSweep = v) }, d.TEMPERATURE_SWEEP),
            IconRowSpec("Reasoning sweep", { it.reasoningSweep }, { m, v -> m.copy(reasoningSweep = v) }, d.REASONING_SWEEP),
            IconRowSpec("Web-search replay", { it.webSearchReplay }, { m, v -> m.copy(webSearchReplay = v) }, d.WEB_SEARCH_REPLAY),
        ),
        "Navigation" to listOf(
            IconRowSpec("Pick report", { it.pickReport }, { m, v -> m.copy(pickReport = v) }, d.PICK_REPORT),
            IconRowSpec("Open manage", { it.openManage }, { m, v -> m.copy(openManage = v) }, d.OPEN_MANAGE),
            IconRowSpec("Housekeeping", { it.housekeeping }, { m, v -> m.copy(housekeeping = v) }, d.HOUSEKEEPING),
            IconRowSpec("Settings", { it.settings }, { m, v -> m.copy(settings = v) }, d.SETTINGS),
            IconRowSpec("Statistics", { it.statistics }, { m, v -> m.copy(statistics = v) }, d.STATISTICS),
            IconRowSpec("Info", { it.info }, { m, v -> m.copy(info = v) }, d.INFO),
        ),
        "Configuration" to listOf(
            IconRowSpec("Parameters", { it.parameters }, { m, v -> m.copy(parameters = v) }, d.PARAMETERS),
            IconRowSpec("System prompt", { it.systemPrompt }, { m, v -> m.copy(systemPrompt = v) }, d.SYSTEM_PROMPT),
            IconRowSpec("Clear", { it.clear }, { m, v -> m.copy(clear = v) }, d.CLEAR),
            IconRowSpec("Attach", { it.attach }, { m, v -> m.copy(attach = v) }, d.ATTACH),
            IconRowSpec("Validate prompt", { it.validatePrompt }, { m, v -> m.copy(validatePrompt = v) }, d.VALIDATE_PROMPT),
        ),
        "Report actions" to listOf(
            IconRowSpec("Copy", { it.copy }, { m, v -> m.copy(copy = v) }, d.COPY),
            IconRowSpec("Pin", { it.pin }, { m, v -> m.copy(pin = v) }, d.PIN),
            IconRowSpec("Toggle labels", { it.toggleLabels }, { m, v -> m.copy(toggleLabels = v) }, d.TOGGLE_LABELS),
            IconRowSpec("Share", { it.share }, { m, v -> m.copy(share = v) }, d.SHARE),
            IconRowSpec("Duplicate", { it.duplicate }, { m, v -> m.copy(duplicate = v) }, d.DUPLICATE),
        ),
        "Item actions" to listOf(
            IconRowSpec("View", { it.view }, { m, v -> m.copy(view = v) }, d.VIEW),
            IconRowSpec("Memo", { it.memo }, { m, v -> m.copy(memo = v) }, d.MEMO),
            IconRowSpec("Add note", { it.addNote }, { m, v -> m.copy(addNote = v) }, d.ADD_NOTE),
            IconRowSpec("List notes", { it.listNotes }, { m, v -> m.copy(listNotes = v) }, d.LIST_NOTES),
            IconRowSpec("Edit", { it.edit }, { m, v -> m.copy(edit = v) }, d.EDIT),
            IconRowSpec("Reload", { it.reload }, { m, v -> m.copy(reload = v) }, d.RELOAD),
            IconRowSpec("Delete", { it.delete }, { m, v -> m.copy(delete = v) }, d.DELETE),
        ),
        "View bar & help" to listOf(
            IconRowSpec("Show all", { it.viewShowAll }, { m, v -> m.copy(viewShowAll = v) }, d.VIEW_SHOW_ALL),
            IconRowSpec("Show one", { it.viewShowOne }, { m, v -> m.copy(viewShowOne = v) }, d.VIEW_SHOW_ONE),
            IconRowSpec("Help", { it.help }, { m, v -> m.copy(help = v) }, d.HELP),
            IconRowSpec("Icons help", { it.helpLegend }, { m, v -> m.copy(helpLegend = v) }, d.HELP_LEGEND),
        ),
        "Status & progress" to listOf(
            IconRowSpec("Done", { it.statusDone }, { m, v -> m.copy(statusDone = v) }, d.STATUS_DONE),
            IconRowSpec("Failed", { it.statusFailed }, { m, v -> m.copy(statusFailed = v) }, d.STATUS_FAILED),
            IconRowSpec("Pending", { it.statusPending }, { m, v -> m.copy(statusPending = v) }, d.STATUS_PENDING),
            IconRowSpec("Paused", { it.statusPaused }, { m, v -> m.copy(statusPaused = v) }, d.STATUS_PAUSED),
            IconRowSpec("Stopped", { it.statusStopped }, { m, v -> m.copy(statusStopped = v) }, d.STATUS_STOPPED),
            IconRowSpec("Alarm", { it.statusAlarm }, { m, v -> m.copy(statusAlarm = v) }, d.STATUS_ALARM),
            IconRowSpec("Blocked", { it.statusBlocked }, { m, v -> m.copy(statusBlocked = v) }, d.STATUS_BLOCKED),
            IconRowSpec("Locked", { it.statusLocked }, { m, v -> m.copy(statusLocked = v) }, d.STATUS_LOCKED),
            IconRowSpec("Warning", { it.statusWarning }, { m, v -> m.copy(statusWarning = v) }, d.STATUS_WARNING),
            IconRowSpec("Warning (plain)", { it.warningPlain }, { m, v -> m.copy(warningPlain = v) }, d.WARNING_PLAIN),
            IconRowSpec("Hot", { it.hot }, { m, v -> m.copy(hot = v) }, d.HOT),
            IconRowSpec("Clock", { it.clockTime }, { m, v -> m.copy(clockTime = v) }, d.CLOCK_TIME),
            IconRowSpec("Clock (queued)", { it.clockQueued }, { m, v -> m.copy(clockQueued = v) }, d.CLOCK_QUEUED),
            IconRowSpec("Clock (recent)", { it.clockRecent }, { m, v -> m.copy(clockRecent = v) }, d.CLOCK_RECENT),
            IconRowSpec("Sleep / inactive", { it.sleep }, { m, v -> m.copy(sleep = v) }, d.SLEEP),
            IconRowSpec("No entry", { it.noEntry }, { m, v -> m.copy(noEntry = v) }, d.NO_ENTRY),
            IconRowSpec("Snowflake", { it.snowflake }, { m, v -> m.copy(snowflake = v) }, d.SNOWFLAKE),
            IconRowSpec("Roadblock", { it.roadblock }, { m, v -> m.copy(roadblock = v) }, d.ROADBLOCK),
            IconRowSpec("Explosion", { it.explosion }, { m, v -> m.copy(explosion = v) }, d.EXPLOSION),
        ),
        "Marks & ranks" to listOf(
            IconRowSpec("Check", { it.checkMark }, { m, v -> m.copy(checkMark = v) }, d.CHECK),
            IconRowSpec("Cross", { it.crossMark }, { m, v -> m.copy(crossMark = v) }, d.CROSS),
            IconRowSpec("Close", { it.closeMark }, { m, v -> m.copy(closeMark = v) }, d.CLOSE),
            IconRowSpec("Checkbox on", { it.checkboxOn }, { m, v -> m.copy(checkboxOn = v) }, d.CHECKBOX_ON),
            IconRowSpec("Checkbox off", { it.checkboxOff }, { m, v -> m.copy(checkboxOff = v) }, d.CHECKBOX_OFF),
            IconRowSpec("Blank box", { it.boxBlank }, { m, v -> m.copy(boxBlank = v) }, d.BOX_BLANK),
            IconRowSpec("Gold medal", { it.medalGold }, { m, v -> m.copy(medalGold = v) }, d.MEDAL_GOLD),
            IconRowSpec("Silver medal", { it.medalSilver }, { m, v -> m.copy(medalSilver = v) }, d.MEDAL_SILVER),
            IconRowSpec("Bronze medal", { it.medalBronze }, { m, v -> m.copy(medalBronze = v) }, d.MEDAL_BRONZE),
        ),
        "Arrows" to listOf(
            IconRowSpec("Arrow up", { it.arrowUp }, { m, v -> m.copy(arrowUp = v) }, d.ARROW_UP),
            IconRowSpec("Arrow right", { it.arrowRight }, { m, v -> m.copy(arrowRight = v) }, d.ARROW_RIGHT),
            IconRowSpec("Arrow down", { it.arrowDown }, { m, v -> m.copy(arrowDown = v) }, d.ARROW_DOWN),
            IconRowSpec("Arrow back", { it.arrowBack }, { m, v -> m.copy(arrowBack = v) }, d.ARROW_BACK),
            IconRowSpec("Arrow forward", { it.arrowForward }, { m, v -> m.copy(arrowForward = v) }, d.ARROW_FORWARD),
            IconRowSpec("Submit arrow", { it.arrowSubmit }, { m, v -> m.copy(arrowSubmit = v) }, d.ARROW_SUBMIT),
            IconRowSpec("Caret expanded", { it.caretExpanded }, { m, v -> m.copy(caretExpanded = v) }, d.CARET_EXPANDED),
            IconRowSpec("Caret collapsed", { it.caretCollapsed }, { m, v -> m.copy(caretCollapsed = v) }, d.CARET_COLLAPSED),
            IconRowSpec("Minus", { it.minus }, { m, v -> m.copy(minus = v) }, d.MINUS),
        ),
        "Search & files" to listOf(
            IconRowSpec("Agent / AI", { it.agent }, { m, v -> m.copy(agent = v) }, d.AGENT),
            IconRowSpec("AI find", { it.aiFind }, { m, v -> m.copy(aiFind = v) }, d.AI_FIND),
            IconRowSpec("Web / remote", { it.web }, { m, v -> m.copy(web = v) }, d.WEB),
            IconRowSpec("Lookup", { it.lookup }, { m, v -> m.copy(lookup = v) }, d.LOOKUP),
            IconRowSpec("Search", { it.search }, { m, v -> m.copy(search = v) }, d.SEARCH),
            IconRowSpec("Open folder", { it.folderOpen }, { m, v -> m.copy(folderOpen = v) }, d.FOLDER_OPEN),
            IconRowSpec("Label", { it.label }, { m, v -> m.copy(label = v) }, d.LABEL),
            IconRowSpec("Bookmark", { it.bookmark }, { m, v -> m.copy(bookmark = v) }, d.BOOKMARK),
            IconRowSpec("Notepad", { it.notepad }, { m, v -> m.copy(notepad = v) }, d.NOTEPAD),
            IconRowSpec("Document", { it.document }, { m, v -> m.copy(document = v) }, d.DOCUMENT),
            IconRowSpec("Package", { it.packageBox }, { m, v -> m.copy(packageBox = v) }, d.PACKAGE_BOX),
            IconRowSpec("Plug", { it.plug }, { m, v -> m.copy(plug = v) }, d.PLUG),
            IconRowSpec("Key", { it.key }, { m, v -> m.copy(key = v) }, d.KEY),
        ),
        "Content & media" to listOf(
            IconRowSpec("World", { it.world }, { m, v -> m.copy(world = v) }, d.WORLD),
            IconRowSpec("Chart", { it.chart }, { m, v -> m.copy(chart = v) }, d.CHART),
            IconRowSpec("Cyclone", { it.cyclone }, { m, v -> m.copy(cyclone = v) }, d.CYCLONE),
            IconRowSpec("Library", { it.library }, { m, v -> m.copy(library = v) }, d.LIBRARY),
            IconRowSpec("Book", { it.book }, { m, v -> m.copy(book = v) }, d.BOOK),
            IconRowSpec("Image", { it.image }, { m, v -> m.copy(image = v) }, d.IMAGE),
            IconRowSpec("Mail", { it.mail }, { m, v -> m.copy(mail = v) }, d.MAIL),
            IconRowSpec("Speech", { it.speech }, { m, v -> m.copy(speech = v) }, d.SPEECH),
            IconRowSpec("Gem", { it.gem }, { m, v -> m.copy(gem = v) }, d.GEM),
            IconRowSpec("Tip", { it.tip }, { m, v -> m.copy(tip = v) }, d.TIP),
            IconRowSpec("Camera", { it.camera }, { m, v -> m.copy(camera = v) }, d.CAMERA),
            IconRowSpec("Gift", { it.gift }, { m, v -> m.copy(gift = v) }, d.GIFT),
            IconRowSpec("Rocket", { it.rocket }, { m, v -> m.copy(rocket = v) }, d.ROCKET),
            IconRowSpec("Home", { it.home }, { m, v -> m.copy(home = v) }, d.HOME),
            IconRowSpec("Save", { it.save }, { m, v -> m.copy(save = v) }, d.SAVE),
            IconRowSpec("Cloud", { it.cloud }, { m, v -> m.copy(cloud = v) }, d.CLOUD),
            IconRowSpec("Building blocks", { it.buildingBlocks }, { m, v -> m.copy(buildingBlocks = v) }, d.BUILDING_BLOCKS),
            IconRowSpec("Groupings", { it.groupings }, { m, v -> m.copy(groupings = v) }, d.GROUPINGS),
            IconRowSpec("GitHub", { it.github }, { m, v -> m.copy(github = v) }, d.GITHUB),
        ),
        "Cost" to listOf(
            IconRowSpec("Cost", { it.cost }, { m, v -> m.copy(cost = v) }, d.COST),
            IconRowSpec("Dollar", { it.dollar }, { m, v -> m.copy(dollar = v) }, d.DOLLAR),
            IconRowSpec("Spend", { it.spend }, { m, v -> m.copy(spend = v) }, d.SPEND),
            IconRowSpec("Cash", { it.cash }, { m, v -> m.copy(cash = v) }, d.CASH),
        ),
        "Workers & tools" to listOf(
            IconRowSpec("Swarm", { it.swarm }, { m, v -> m.copy(swarm = v) }, d.SWARM),
            IconRowSpec("Flock", { it.flock }, { m, v -> m.copy(flock = v) }, d.FLOCK),
            IconRowSpec("Fan-in knot", { it.fanInKnot }, { m, v -> m.copy(fanInKnot = v) }, d.FAN_IN_KNOT),
            IconRowSpec("Feather", { it.feather }, { m, v -> m.copy(feather = v) }, d.FEATHER),
            IconRowSpec("Tools", { it.tools }, { m, v -> m.copy(tools = v) }, d.TOOLS),
            IconRowSpec("Toolbox", { it.toolbox }, { m, v -> m.copy(toolbox = v) }, d.TOOLBOX),
            IconRowSpec("Puzzle", { it.puzzle }, { m, v -> m.copy(puzzle = v) }, d.PUZZLE),
            IconRowSpec("Palette", { it.palette }, { m, v -> m.copy(palette = v) }, d.PALETTE),
            IconRowSpec("Test", { it.test }, { m, v -> m.copy(test = v) }, d.TEST),
            IconRowSpec("Worker", { it.worker }, { m, v -> m.copy(worker = v) }, d.WORKER),
            IconRowSpec("Sparkles", { it.sparkles }, { m, v -> m.copy(sparkles = v) }, d.SPARKLES),
            IconRowSpec("Ruler", { it.ruler }, { m, v -> m.copy(ruler = v) }, d.RULER),
            IconRowSpec("Straight ruler", { it.rulerStraight }, { m, v -> m.copy(rulerStraight = v) }, d.RULER_STRAIGHT),
            IconRowSpec("Shuffle", { it.shuffle }, { m, v -> m.copy(shuffle = v) }, d.SHUFFLE),
            IconRowSpec("Repeat", { it.repeat }, { m, v -> m.copy(repeat = v) }, d.REPEAT),
            IconRowSpec("Hide", { it.hide }, { m, v -> m.copy(hide = v) }, d.HIDE),
            IconRowSpec("Shield", { it.shield }, { m, v -> m.copy(shield = v) }, d.SHIELD),
            IconRowSpec("Microscope", { it.microscope }, { m, v -> m.copy(microscope = v) }, d.MICROSCOPE),
            IconRowSpec("Controls", { it.controls }, { m, v -> m.copy(controls = v) }, d.CONTROLS),
            IconRowSpec("Sliders", { it.sliders }, { m, v -> m.copy(sliders = v) }, d.SLIDERS),
            IconRowSpec("Magic", { it.magic }, { m, v -> m.copy(magic = v) }, d.MAGIC),
        ),
        "Devices & misc" to listOf(
            IconRowSpec("Device", { it.device }, { m, v -> m.copy(device = v) }, d.DEVICE),
            IconRowSpec("Computer", { it.computer }, { m, v -> m.copy(computer = v) }, d.COMPUTER),
            IconRowSpec("Satellite", { it.satellite }, { m, v -> m.copy(satellite = v) }, d.SATELLITE),
            IconRowSpec("Hugging Face", { it.huggingface }, { m, v -> m.copy(huggingface = v) }, d.HUGGINGFACE),
            IconRowSpec("Green circle", { it.greenCircle }, { m, v -> m.copy(greenCircle = v) }, d.GREEN_CIRCLE),
            IconRowSpec("White circle", { it.whiteCircle }, { m, v -> m.copy(whiteCircle = v) }, d.WHITE_CIRCLE),
            IconRowSpec("Red circle", { it.redCircle }, { m, v -> m.copy(redCircle = v) }, d.RED_CIRCLE),
            IconRowSpec("Orange circle", { it.orangeCircle }, { m, v -> m.copy(orangeCircle = v) }, d.ORANGE_CIRCLE),
            IconRowSpec("Blue circle", { it.blueCircle }, { m, v -> m.copy(blueCircle = v) }, d.BLUE_CIRCLE),
            IconRowSpec("Sun", { it.sun }, { m, v -> m.copy(sun = v) }, d.SUN),
            IconRowSpec("Calendar", { it.calendar }, { m, v -> m.copy(calendar = v) }, d.CALENDAR),
            IconRowSpec("Spiral calendar", { it.calendarSpiral }, { m, v -> m.copy(calendarSpiral = v) }, d.CALENDAR_SPIRAL),
            IconRowSpec("Runner", { it.runner }, { m, v -> m.copy(runner = v) }, d.RUNNER),
            IconRowSpec("Fog", { it.fog }, { m, v -> m.copy(fog = v) }, d.FOG),
            IconRowSpec("Bento", { it.bento }, { m, v -> m.copy(bento = v) }, d.BENTO),
            IconRowSpec("Number input", { it.numberInput }, { m, v -> m.copy(numberInput = v) }, d.NUMBER_INPUT),
            IconRowSpec("Symbols", { it.symbols }, { m, v -> m.copy(symbols = v) }, d.SYMBOLS),
            IconRowSpec("Handshake", { it.handshake }, { m, v -> m.copy(handshake = v) }, d.HANDSHAKE),
            IconRowSpec("Blue diamond", { it.blueDiamond }, { m, v -> m.copy(blueDiamond = v) }, d.BLUE_DIAMOND),
            IconRowSpec("Group", { it.group }, { m, v -> m.copy(group = v) }, d.GROUP),
            IconRowSpec("Bolt", { it.bolt }, { m, v -> m.copy(bolt = v) }, d.BOLT),
            IconRowSpec("Health", { it.health }, { m, v -> m.copy(health = v) }, d.HEALTH),
            IconRowSpec("Slow", { it.slow }, { m, v -> m.copy(slow = v) }, d.SLOW),
            IconRowSpec("Coffin", { it.coffin }, { m, v -> m.copy(coffin = v) }, d.COFFIN),
        ),
    )
}

private fun defaultIconSectionIcon(section: String): String = when (section) {
    "Report" -> MetadataDefaults.REPORT_ICON
    "Secondary results" -> MetadataDefaults.META
    "Translation" -> MetadataDefaults.TRANSLATE
    "Monitor bar" -> MetadataDefaults.LIVE_DASHBOARD
    "Create & chat" -> MetadataDefaults.CHAT
    "Navigation" -> MetadataDefaults.ARROW_RIGHT
    "Configuration" -> MetadataDefaults.SETTINGS
    "Report actions" -> MetadataDefaults.OPEN_MANAGE
    "Item actions" -> MetadataDefaults.EDIT
    "View bar & help" -> MetadataDefaults.HELP
    "Status & progress" -> MetadataDefaults.STATUS_PENDING
    "Marks & ranks" -> MetadataDefaults.CHECK
    "Arrows" -> MetadataDefaults.ARROW_RIGHT
    "Search & files" -> MetadataDefaults.SEARCH
    "Content & media" -> MetadataDefaults.IMAGE
    "Cost" -> MetadataDefaults.COST
    "Workers & tools" -> MetadataDefaults.TOOLS
    "Devices & misc" -> MetadataDefaults.DEVICE
    else -> MetadataDefaults.PALETTE
}

/** "Default icons" — edit every fallback / action emoji the app draws: the
 *  report & secondary-result fallbacks on the view screens, plus every glyph in
 *  the bottom action bars. Always reachable (independent of the metadata master
 *  switch) since the fallbacks render on view screens regardless. Blank field on
 *  save → factory default. Driven entirely by [DEFAULT_ICON_SECTIONS]. */
@Composable
private fun DefaultIconsSubScreen(
    generalSettings: GeneralSettings,
    aiSettings: com.ai.model.Settings,
    onAskModelText: suspend (AppService, String, String) -> String?,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var icons by remember { mutableStateOf(generalSettings.metadataIcons) }

    // Blank field on save → its factory default. Walks the same row table the UI
    // renders, so any field added there is normalized automatically.
    fun normalized(): com.ai.data.MetadataIcons {
        var m = icons
        DEFAULT_ICON_SECTIONS.forEach { (_, rows) ->
            rows.forEach { row -> if (row.get(m).isBlank()) m = row.set(m, row.factory) }
        }
        return m
    }
    fun build(): GeneralSettings = generalSettings.copy(metadataIcons = normalized())

    LaunchedEffect(icons) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    // 🤖 AI icon finder — opened by the per-row AI link. Holds the row's label
    // + a writeback that drops the picked emoji into that row's field. Layered
    // as a full-screen early-return (the save effects above stay composed, so
    // the pick persists once it lands back in a field). [label] is also the
    // empty key, so a non-null target means the finder is open.
    var aiFindFor by remember { mutableStateOf<IconAiTarget?>(null) }
    aiFindFor?.let { target ->
        DefaultIconAiFinderScreen(
            label = target.label,
            aiSettings = aiSettings,
            onAskModelText = onAskModelText,
            onPick = { emoji -> target.onPicked(emoji); aiFindFor = null },
            onBack = { aiFindFor = null }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_default_icons", title = "Default icons", subject = "Fallback + bottom-bar action emoji", onBackClick = onBack,
            // 🧽 restores every icon to its factory default.
            onClear = { icons = com.ai.data.MetadataIcons() }
        )
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Every emoji the app draws — report/result fallbacks on the view screens and every action icon in the bottom bars. Editing one updates it everywhere; a blank field falls back to the factory default, and ${com.ai.data.MetadataIconsHolder.current.clear} resets them all. Tap a category to expand it.",
                fontSize = 11.sp, color = AppColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            // One collapsible card per category, all collapsed at start. While
            // collapsed the header previews every glyph in the category (just the
            // emoji, no labels — wrapping to new lines); tap to expand the
            // editable rows. "Reset all" lives on the 🧽 bottom-bar icon.
            DEFAULT_ICON_SECTIONS.forEach { (section, rows) ->
                IconCategoryCard(
                    title = section,
                    icon = defaultIconSectionIcon(section),
                    glyphs = rows.map { row -> row.get(icons).ifBlank { row.factory } }
                ) {
                    rows.forEach { row ->
                        IconDefaultRow(row.label, row.get(icons), { icons = row.set(icons, it) }) {
                            aiFindFor = IconAiTarget(row.label) { picked -> icons = row.set(icons, picked) }
                        }
                    }
                }
            }
        }
    }
}

/** A collapsible category card on the Default icons screen. Collapsed at
 *  start: shows the category [title] and, on the line(s) below, every glyph in
 *  the category ([glyphs]) — just the emoji, no labels, wrapping across lines.
 *  Tap the header (or the glyph preview) to expand the editable rows; tap the
 *  header again to collapse. Mirrors the Settings [SettingCard] chrome (▾/▸). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun IconCategoryCard(
    title: String,
    icon: String,
    glyphs: List<String>,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsCardHeaderIcon(icon)
                Spacer(modifier = Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (expanded) {
                content()
            } else {
                // Collapsed preview — every glyph in the category, no labels,
                // wrapping to new lines. Tapping it also expands the card.
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    glyphs.forEach { g -> Text(g, fontSize = 20.sp) }
                }
            }
        }
    }
}

/** One labelled row on the Default icons screen: the item name on the
 *  left, an editable text field the user can type / paste any emoji into,
 *  and a 🔎 lookup link beside it that opens the AndroidX EmojiPickerView in
 *  a bottom sheet — picking writes the chosen emoji straight back into the
 *  field. Blank on save falls back to the factory default (see `build()`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconDefaultRow(label: String, value: String, onChange: (String) -> Unit, onAiFind: () -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            ),
            modifier = Modifier.width(88.dp)
        )
        // 🔎 lookup — opens the emoji picker; the pick is written into the field.
        Text(
            com.ai.data.MetadataIconsHolder.current.lookup, fontSize = 20.sp,
            modifier = Modifier.clickable { showPicker = true }.padding(start = 8.dp, end = 2.dp)
        )
        // 🤖 AI — ask models for a fitting emoji (editable prompt), pick one.
        Text(
            com.ai.data.MetadataIconsHolder.current.aiFind, fontSize = 20.sp,
            modifier = Modifier.clickable { onAiFind() }.padding(start = 6.dp, end = 2.dp)
        )
    }
    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.emoji2.emojipicker.EmojiPickerView(ctx).apply {
                        setOnEmojiPickedListener { picked ->
                            onChange(picked.emoji)
                            showPicker = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(360.dp)
            )
        }
    }
}

/** Target of the Default-icons 🤖 AI finder: the row's [label] (used in the
 *  seeded prompt + title) and a writeback that drops the picked emoji into
 *  that row's field. */
private data class IconAiTarget(val label: String, val onPicked: (String) -> Unit)

/** "Find icon" — the 🤖 AI flow on a Default-icons row. Works like the report
 *  "Find alternative icons" flow: an editable prompt (pre-seeded for the row's
 *  concept), a Find button that asks every icon-worker model in parallel, and a
 *  list of candidate emoji — tap one to drop it into the field. No report
 *  context; each model is called through [onAskModelText]. */
@Composable
private fun DefaultIconAiFinderScreen(
    label: String,
    aiSettings: com.ai.model.Settings,
    onAskModelText: suspend (AppService, String, String) -> String?,
    onPick: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    // Icon-worker models = the same set the app uses for every generated icon
    // (the "second-meta" worker prompt's swarm), de-duplicated.
    val models = remember(aiSettings) {
        (aiSettings.getInternalPromptByName("second-meta")?.workers ?: emptyList())
            .flatMap { aiSettings.expandWorker(it) }
            .mapNotNull { aiSettings.resolveWorker(it) }
            .map { it.provider to aiSettings.getEffectiveModelForAgent(it) }
            .filter { it.second.isNotBlank() }
            .distinctBy { "${it.first.id}/${it.second}" }
    }
    var prompt by rememberSaveable(label) {
        mutableStateOf("Please give a single fitting emoji for: \"$label\".\nReply with only the emoji, nothing more.")
    }
    val candidates = remember { mutableStateMapOf<String, com.ai.viewmodel.IconCandidate>() }
    var running by remember { mutableStateOf(false) }

    fun run() {
        if (models.isEmpty() || running) return
        candidates.clear()
        running = true
        models.forEach { (p, m) -> candidates["${p.id}/$m"] = com.ai.viewmodel.IconCandidate.Running(p, m) }
        scope.launch {
            models.map { (p, m) ->
                async {
                    val text = onAskModelText(p, m, prompt)
                    val emoji = com.ai.data.extractFirstEmoji(text)
                    candidates["${p.id}/$m"] = if (emoji != null) com.ai.viewmodel.IconCandidate.Done(p, m, emoji)
                        else com.ai.viewmodel.IconCandidate.Error(p, m, text?.trim()?.take(40)?.ifBlank { "no emoji in reply" } ?: "no reply")
                }
            }.awaitAll()
            running = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "settings_default_icons", title = "Find icon", subject = label, onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Edit the prompt, then Find — every icon-worker model answers and you pick an emoji.",
                fontSize = 11.sp, color = AppColors.TextTertiary)
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Prompt") }
            )
            if (models.isEmpty()) {
                Text("No icon-worker models configured. Add models to the worker swarm under AI Setup → Worker prompts.",
                    fontSize = 12.sp, color = AppColors.TextSecondary)
            } else {
                Button(
                    onClick = { run() }, enabled = !running, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
                ) { Text(if (running) "Finding…" else "Find (${models.size} model${if (models.size == 1) "" else "s"})", fontSize = 14.sp) }
            }
            candidates.values.sortedBy { "${it.provider.id}/${it.model}" }.forEach { c ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .then(if (c is com.ai.viewmodel.IconCandidate.Done) Modifier.clickable { onPick(c.emoji) } else Modifier)
                        .padding(vertical = 4.dp)
                ) {
                    Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                        when (c) {
                            is com.ai.viewmodel.IconCandidate.Running -> com.ai.ui.shared.AnimatedHourglass(fontSize = 18.sp)
                            is com.ai.viewmodel.IconCandidate.Done -> Text(c.emoji, fontSize = 22.sp)
                            is com.ai.viewmodel.IconCandidate.Error -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 18.sp)
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(com.ai.ui.shared.modelLabel(c.provider.id, c.model), fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val sub = when (c) {
                            is com.ai.viewmodel.IconCandidate.Done -> "Tap to use this icon"
                            is com.ai.viewmodel.IconCandidate.Error -> c.reason
                            else -> "…"
                        }
                        Text(sub, fontSize = 11.sp, color = AppColors.TextTertiary,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** "App settings" — app-wide and report-model default System prompt /
 *  Parameters presets. App-wide is the universal lowest fallback for every
 *  model; report-model fills in for bare/direct models only. Both are
 *  outranked by anything more specific (pre-gen, agent / flock / swarm,
 *  provider). Stored in GeneralSettings. */
@Composable
private fun AppSettingsScreen(
    generalSettings: GeneralSettings,
    aiSettings: Settings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit
) {
    var appSp by remember { mutableStateOf(generalSettings.appWideSystemPromptId) }
    var appPar by remember { mutableStateOf(generalSettings.appWideParametersIds) }
    var rmSp by remember { mutableStateOf(generalSettings.reportModelSystemPromptId) }
    var rmPar by remember { mutableStateOf(generalSettings.reportModelParametersIds) }

    fun build(): GeneralSettings = generalSettings.copy(
        appWideSystemPromptId = appSp,
        appWideParametersIds = appPar,
        reportModelSystemPromptId = rmSp,
        reportModelParametersIds = rmPar
    )
    LaunchedEffect(appSp, appPar, rmSp, rmPar) {
        val updated = build()
        if (updated != generalSettings) { kotlinx.coroutines.delay(400); onSave(updated) }
    }
    DisposableEffect(Unit) { onDispose { val u = build(); if (u != generalSettings) onSave(u) } }

    var spDialog by remember { mutableStateOf<String?>(null) }   // "app" | "rm"
    var parDialog by remember { mutableStateOf<String?>(null) }  // "app" | "rm"
    if (spDialog != null) {
        val app = spDialog == "app"
        com.ai.ui.shared.SystemPromptSelectScreen(
            aiSettings = aiSettings, selectedId = if (app) appSp else rmSp,
            onSelect = { if (app) appSp = it else rmSp = it },
            onBack = { spDialog = null }, onNavigateHome = onBack
        )
        return
    }
    if (parDialog != null) {
        val app = parDialog == "app"
        com.ai.ui.shared.ParametersSelectScreen(
            aiSettings = aiSettings, selectedIds = if (app) appPar else rmPar,
            onConfirm = { if (app) appPar = it else rmPar = it },
            onBack = { parDialog = null }, onNavigateHome = onBack
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "settings_app_settings", title = "App settings", subject = "App-wide & report-model default prompt / parameters", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingCard("System prompt", "App-wide is the lowest fallback for every model; Report model applies to bare models (not from an agent / flock / swarm) and is skipped when a pre-generation system prompt is given.", MetadataDefaults.SYSTEM_PROMPT) {
                AppDefaultRow("App-wide", appSp?.let { aiSettings.getSystemPromptById(it)?.name }) { spDialog = "app" }
                AppDefaultRow("Report model", rmSp?.let { aiSettings.getSystemPromptById(it)?.name }) { spDialog = "rm" }
            }
            SettingCard("Parameters", "App-wide is the lowest fallback for every model; Report model applies to bare models (not from an agent / flock / swarm) and is skipped when pre-generation parameters are given.", MetadataDefaults.PARAMETERS) {
                AppDefaultRow("App-wide", appPar.mapNotNull { aiSettings.getParametersById(it)?.name }.joinToString(", ").ifBlank { null }) { parDialog = "app" }
                AppDefaultRow("Report model", rmPar.mapNotNull { aiSettings.getParametersById(it)?.name }.joinToString(", ").ifBlank { null }) { parDialog = "rm" }
            }
        }
    }
}

/** One labelled selector row inside an App settings card. */
@Composable
private fun AppDefaultRow(label: String, selectedName: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 6.dp)
    ) {
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.width(110.dp))
        Text(
            selectedName ?: "Tap to select", fontSize = 13.sp,
            color = if (selectedName == null) AppColors.TextTertiary else AppColors.Blue,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(">", fontSize = 16.sp, color = AppColors.Blue)
    }
}

/** Diagnostic preferences: master API tracing switch + application
 *  log severity threshold. Both flow to background subsystems
 *  (ApiTracer / AppLog) on save; the change takes effect on the
 *  next traced call / next log line. */
@Composable
private fun LoggingAndTracingSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var tracingEnabled by remember { mutableStateOf(generalSettings.tracingEnabled) }
    var logLevel by remember { mutableStateOf(generalSettings.logLevel) }

    fun build(): GeneralSettings = generalSettings.copy(
        tracingEnabled = tracingEnabled,
        logLevel = logLevel
    )

    LaunchedEffect(tracingEnabled, logLevel) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_logging", title = "Logging and tracing", subject = "Log level and API call tracing", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleSettingCard(
                title = "API tracing",
                description = "Record every API request and response. Turn off to hide the AI API Traces card and the ${com.ai.data.MetadataIconsHolder.current.traces} trace icons.",
                icon = MetadataDefaults.TRACES,
                checked = tracingEnabled,
                onCheckedChange = { tracingEnabled = it }
            )
            SettingCard(
                "Application log level",
                "Severity threshold for the in-app file logger. Calls at or above this level are appended to a daily-rotating file in app storage. View / clear under Housekeeping → Application log. OFF disables the file appender.",
                MetadataDefaults.APP_LOG
            ) {
                Column {
                    com.ai.data.LogLevel.entries.forEach { lvl ->
                        RadioRow(
                            selected = logLevel == lvl,
                            label = lvl.name,
                            onClick = { logLevel = lvl }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    description: String? = null,
    icon: String,
    content: @Composable ColumnScope.() -> Unit
) {
    // Cards start collapsed so the Settings screen lands on a compact
    // title-only list. Tap the header row to expand description + body.
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsCardHeaderIcon(icon)
                Spacer(modifier = Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (expanded) {
                if (description != null) {
                    Text(description, fontSize = 11.sp, color = AppColors.TextTertiary)
                }
                content()
            }
        }
    }
}

/** Switch on the same row as the title, description below — denser
 *  than [SettingCard] for the boolean preferences that don't need a
 *  full-width control beneath them. Starts collapsed; tap the header
 *  to expand and reveal the Switch + description. */
@Composable
private fun ToggleSettingCard(
    title: String,
    description: String? = null,
    icon: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsCardHeaderIcon(icon)
                Spacer(modifier = Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (checked) "On" else "Off",
                        fontSize = 12.sp, color = AppColors.TextTertiary,
                        modifier = Modifier.weight(1f))
                    Switch(checked = checked, onCheckedChange = onCheckedChange)
                }
                if (description != null) {
                    Text(description, fontSize = 11.sp, color = AppColors.TextTertiary)
                }
            }
        }
    }
}

@Composable
private fun SettingsCardHeaderIcon(icon: String) {
    Text(
        LocalMetadataIcons.current.forFactoryGlyph(icon),
        fontSize = 28.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(42.dp)
    )
}

/** Radio + label on one row. Default RadioButton ships with a 48dp
 *  touch-target padding which leaves a wide gap between stacked rows
 *  — fine for accessibility but visually noisy here. We let the
 *  default through (don't shrink the touch target) but wrap rows in
 *  a no-spacing Column so they sit at their natural minimum. */
@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp))
    }
}
