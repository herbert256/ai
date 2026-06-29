package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.ai.viewmodel.AppHomeMode
import com.ai.viewmodel.DEFAULT_UI_BUTTON_BACKGROUND_ARGB
import com.ai.viewmodel.DEFAULT_UI_CARD_BACKGROUND_ARGB
import com.ai.viewmodel.GeneralSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

enum class SettingsSubScreen {
    MAIN, AI_PROVIDER_EDIT, AI_SETUP,
    AI_PROVIDERS,
    AI_PROVIDERS_PREDEFINED,
    AI_MODELS_SETUP,
    AI_MODELS_SEARCH,
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
    AI_INFO_PROVIDERS,
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
    AI_MODEL_STATISTICS,
    AI_IMPORT_EXPORT,
    AI_REFRESH,
    // Four preference buckets carved out of the main Settings screen
    // so the top page stays a short nav list. Each sub-screen owns
    // its own help topic and renders only the cards from its bucket.
    SETTINGS_NETWORK,
    SETTINGS_NETWORK_API_CALLS,
    SETTINGS_NETWORK_PER_PROVIDER,
    SETTINGS_UI,
    SETTINGS_UI_COLORS,
    SETTINGS_LOGGING,
    SETTINGS_OTHER,
    SETTINGS_METADATA,
    SETTINGS_AUTOSTART,
    SETTINGS_DEFAULT_ICONS,
    SETTINGS_RANKING_WEIGHTS
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
    onSaveLlmStatsApiKey: (String) -> Unit = {},
    onSetDisabledInfoProviders: (Set<String>) -> Unit = {},
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
    /** When true and the initial sub-screen is AI_REFRESH, open straight on
     *  the Info Providers sub-page (Manage-data → Info providers → Refresh). */
    refreshOpenInfoProviders: Boolean = false,
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
    var defaultMetaItemsParent by rememberSaveable {
        mutableStateOf(SettingsSubScreen.SETTINGS_AUTOSTART)
    }
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
    // rememberSaveable (not plain remember) so the edit target survives process
    // recreation alongside currentSubScreen — otherwise a rotation / SAF-picker
    // recreation mid-edit reset these to their initials, dropping the edit
    // target and rendering the edit screen as Add (audit settings#10).
    var editingAgentId by rememberSaveable { mutableStateOf(initialEditingAgentId) }
    var editingFlockId by rememberSaveable { mutableStateOf(initialEditingFlockId) }
    var editingSwarmId by rememberSaveable { mutableStateOf(initialEditingSwarmId) }
    var editingSystemPromptId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingInternalPromptId by rememberSaveable { mutableStateOf(initialEditingInternalPromptId) }
    var editingExamplePromptId by rememberSaveable { mutableStateOf<String?>(null) }
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
    // True when the open Provider edit screen was reached from the
    // "Add provider - predefined" picker, so back returns there (and the
    // just-keyed provider has dropped off that list) instead of jumping
    // to the main Providers list.
    var providerEditFromPredefined by remember { mutableStateOf(false) }
    // Providers-list scroll position — hoisted to SettingsScreen because
    // the sub-screen `when` block destroys ProvidersScreen's composition
    // entirely on navigation into AI_PROVIDER_EDIT, throwing any
    // rememberScrollState there away. SettingsScreen itself survives the
    // switch, so a remember here keeps the list scrolled where the user
    // left it. ScrollState.Saver also keeps it across process death.
    val providersListScrollState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.ScrollState.Saver
    ) { androidx.compose.foundation.ScrollState(0) }
    // Same hoisting for the predefined-provider picker, so scrolling
    // it, opening a provider to set a key, and coming back keeps the
    // scroll position.
    val predefinedProvidersScrollState = androidx.compose.runtime.saveable.rememberSaveable(
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
            SettingsSubScreen.AI_PROVIDER_EDIT -> {
                val fromPredefined = providerEditFromPredefined
                providerEditFromPredefined = false
                currentSubScreen = if (fromPredefined) SettingsSubScreen.AI_PROVIDERS_PREDEFINED else SettingsSubScreen.AI_PROVIDERS
            }
            SettingsSubScreen.AI_PROVIDERS_PREDEFINED -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
            SettingsSubScreen.AI_MODEL_EDIT -> {
                val from = modelEditFromProvider
                modelEditFromProvider = false
                currentSubScreen = if (from) SettingsSubScreen.AI_PROVIDER_EDIT else SettingsSubScreen.AI_MODELS
            }
            SettingsSubScreen.AI_MODELS, SettingsSubScreen.AI_MODEL_TYPES,
            SettingsSubScreen.AI_MANUAL_MODEL_TYPES,
            SettingsSubScreen.AI_MODELS_SEARCH,
            SettingsSubScreen.AI_MODEL_STATISTICS -> currentSubScreen = SettingsSubScreen.AI_MODELS_SETUP
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
            SettingsSubScreen.AI_INFO_PROVIDERS,
            SettingsSubScreen.AI_APP_SETTINGS,
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
            SettingsSubScreen.SETTINGS_UI_COLORS,
            SettingsSubScreen.SETTINGS_LOGGING,
            SettingsSubScreen.SETTINGS_AUTOSTART,
            SettingsSubScreen.SETTINGS_OTHER -> currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.AI_DEFAULT_META_ITEMS -> currentSubScreen = defaultMetaItemsParent
            SettingsSubScreen.SETTINGS_METADATA -> currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.SETTINGS_DEFAULT_ICONS -> currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.SETTINGS_RANKING_WEIGHTS -> currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.SETTINGS_NETWORK_API_CALLS,
            SettingsSubScreen.SETTINGS_NETWORK_PER_PROVIDER ->
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
        SettingsSubScreen.SETTINGS_NETWORK_PER_PROVIDER,
        SettingsSubScreen.SETTINGS_UI,
        SettingsSubScreen.SETTINGS_UI_COLORS,
        SettingsSubScreen.SETTINGS_LOGGING,
        SettingsSubScreen.SETTINGS_OTHER,
        SettingsSubScreen.SETTINGS_METADATA,
        SettingsSubScreen.SETTINGS_DEFAULT_ICONS,
        SettingsSubScreen.SETTINGS_RANKING_WEIGHTS
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
                llmStatsApiKey = generalSettings.llmStatsApiKey,
                onBackToSettings = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it }, onSave = onSaveAi,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onSaveArtificialAnalysisApiKey = onSaveArtificialAnalysisApiKey,
                onSaveLlmStatsApiKey = onSaveLlmStatsApiKey,
                onNavigateToCostConfig = onNavigateToCostConfig
            )
        }
        SettingsSubScreen.AI_PROVIDERS -> {
            ProvidersScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome,
                scrollState = providersListScrollState,
                onHousekeeping = hkRefresh,
                onProviderSelected = { selectedProviderId = it.id; currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT },
                onAddPredefined = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS_PREDEFINED },
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
        SettingsSubScreen.AI_PROVIDERS_PREDEFINED -> {
            PredefinedProvidersScreen(
                aiSettings = aiSettings, onBack = goBack, onBackToHome = onNavigateHome,
                scrollState = predefinedProvidersScrollState,
                onHousekeeping = hkRefresh,
                onProviderSelected = {
                    selectedProviderId = it.id
                    providerEditFromPredefined = true
                    currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
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
        SettingsSubScreen.AI_MODELS_SEARCH -> {
            // Reuses the +Model report picker (ReportSelectModelsScreen) in
            // browse mode so the look is byte-identical; taps route to the
            // Model Info page via the same callback the per-provider model
            // rows use, instead of confirming a selection.
            com.ai.ui.other.ReportSelectModelsScreen(
                aiSettings = aiSettings,
                onConfirm = {},
                onBack = goBack,
                onNavigateHome = onNavigateHome,
                titleText = "Search models",
                subject = "Search every model — tap for info",
                helpTopic = "setup_models_search",
                onBrowse = { (provider, model) -> onNavigateToModelInfo(provider, model) }
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
        SettingsSubScreen.AI_MODEL_STATISTICS -> {
            com.ai.ui.models.ModelStatisticsScreen(
                aiSettings = aiSettings,
                onBack = goBack
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
                    modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
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
                llmStatsApiKey = generalSettings.llmStatsApiKey,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onSaveArtificialAnalysisApiKey = onSaveArtificialAnalysisApiKey,
                onSaveLlmStatsApiKey = onSaveLlmStatsApiKey,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
        }
        SettingsSubScreen.AI_INFO_PROVIDERS -> {
            InfoProvidersSetupScreen(
                disabledInfoProviders = aiSettings.disabledInfoProviders,
                onSetDisabledInfoProviders = onSetDisabledInfoProviders,
                onBack = goBack,
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
                llmStatsApiKey = generalSettings.llmStatsApiKey,
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
                llmStatsApiKey = generalSettings.llmStatsApiKey,
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
                openInfoProvidersInitially = refreshOpenInfoProviders,
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
        SettingsSubScreen.SETTINGS_NETWORK_PER_PROVIDER -> {
            PerProviderThrottlingSubScreen(onBack = goBack)
        }
        SettingsSubScreen.SETTINGS_UI -> {
            UiTweaksSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_UI_COLORS -> {
            UiColorsSubScreen(
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
        SettingsSubScreen.SETTINGS_AUTOSTART -> {
            AutostartSubScreen(
                generalSettings = generalSettings, aiSettings = aiSettings,
                onSave = onSaveGeneral, onBack = goBack, onNavigateHome = onNavigateHome,
                onOpenDefaultMetaItems = {
                    defaultMetaItemsParent = currentSubScreen
                    currentSubScreen = SettingsSubScreen.AI_DEFAULT_META_ITEMS
                }
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
        SettingsSubScreen.SETTINGS_RANKING_WEIGHTS -> {
            RankingWeightsSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
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
    // editable card lives in one of the topic sub-screens reached via
    // the nav rows below.
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_main", title = "Settings", subject = "App preferences, grouped by topic", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Nav rows into the four preference buckets. Each opens
            // its own full-screen sub-screen with its own TitleBar +
            // help topic. The main Settings page is now purely a
            // table-of-contents — every actual control lives one tap
            // deeper.
            // ----- Appearance: how the app looks -----
            SettingsSectionLabel("Appearance")
            SettingsNavCard(
                icon = MetadataDefaults.CONTROLS,
                title = "UI tweaks",
                description = "Model name layout, full-screen, ladybug icons, experimental features.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_UI) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.PALETTE,
                title = "UI Colors",
                description = "Customize the app palette — backgrounds, text, accents, borders.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_UI_COLORS) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.SPARKLES,
                title = "Default icons",
                description = "Edit the fallback emoji shown when a report or result has no generated icon of its own.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_DEFAULT_ICONS) }
            )
            // ----- Generation & behaviour: what the app does automatically -----
            SettingsSectionLabel("Generation & behaviour")
            SettingsNavCard(
                icon = MetadataDefaults.LABEL,
                title = "Metadata & icons",
                description = "Master switch for all optional metadata — report icon / language / title, per-model icons / titles, fan & meta icons.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_METADATA) }
            )
            SettingsNavCard(
                icon = com.ai.data.MetadataIconsHolder.current.gem,
                title = "Ranking weights",
                description = "Weight each ranking 0–10 — Rerank, Judges, Translations, and every Tournament method (Elo, Davidson, Schulze, …).",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_RANKING_WEIGHTS) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.REPEAT,
                title = "Autostart",
                description = "What the app starts automatically when a report finishes — Rerank & Moderation, Fan Meta, and the default meta items.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_AUTOSTART) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.SETTINGS,
                title = "Other settings",
                description = "Identity (Name + Email).",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_OTHER) }
            )
            // ----- Network & logging: connectivity and diagnostics -----
            SettingsSectionLabel("Network & logging")
            SettingsNavCard(
                icon = MetadataDefaults.WEB,
                title = "Network settings",
                description = "Read timeouts, per-provider throttling, 429 / 529 retry policy.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_NETWORK) }
            )
            SettingsNavCard(
                icon = MetadataDefaults.APP_LOG,
                title = "Log/trace/audit/statistics",
                description = "API tracing, audit log, usage statistics and application log level.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_LOGGING) }
            )
        }
    }
}

/** Small group heading on the main Settings screen — separates the nav
 *  cards into Appearance / Generation & behaviour / Network & logging. */
@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextTertiary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
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
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(description, fontSize = 12.sp, color = AppColors.TextTertiary)
            }
            Text(">", fontSize = 16.sp, color = AppColors.InfoAccent)
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
    var batchItemTimeoutText by remember {
        mutableStateOf(generalSettings.batchItemTimeoutSec.toString())
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
    var typeABenchEnabled by remember { mutableStateOf(generalSettings.typeABenchEnabled) }
    var typeABenchSecondsText by remember { mutableStateOf(generalSettings.typeABenchSeconds.toString()) }
    var typeABenchMaxAttemptsText by remember { mutableStateOf(generalSettings.typeABenchMaxAttempts.toString()) }

    fun build(): GeneralSettings = generalSettings.copy(
        streamingReadTimeoutSec = streamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.streamingReadTimeoutSec,
        nonStreamingReadTimeoutSec = nonStreamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.nonStreamingReadTimeoutSec,
        batchItemTimeoutSec = batchItemTimeoutText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.batchItemTimeoutSec,
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
            ?: generalSettings.retryBackoffMs529,
        typeABenchEnabled = typeABenchEnabled,
        typeABenchSeconds = typeABenchSecondsText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.typeABenchSeconds,
        typeABenchMaxAttempts = typeABenchMaxAttemptsText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.typeABenchMaxAttempts
    )

    LaunchedEffect(
        streamingReadTimeoutText, nonStreamingReadTimeoutText,
        maxCallsPerMinuteText, maxConcurrentCallsText,
        maxRetriesText, retryBackoffMs429Text,
        maxRetries529Text, retryBackoffMs529Text,
        typeABenchEnabled, typeABenchSecondsText, typeABenchMaxAttemptsText
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
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_network", title = "Network settings", subject = "Timeouts, throttling and retry rules", onBackClick = onBack,
            onClear = {
                val d = GeneralSettings()
                streamingReadTimeoutText = d.streamingReadTimeoutSec.toString()
                nonStreamingReadTimeoutText = d.nonStreamingReadTimeoutSec.toString()
                maxCallsPerMinuteText = d.maxCallsPerProviderPerMinute.toString()
                maxConcurrentCallsText = d.maxConcurrentCallsPerProvider.toString()
                maxRetriesText = d.maxRetriesOn429.toString()
                retryBackoffMs429Text = d.retryBackoffMs429.toString()
                maxRetries529Text = d.maxRetriesOn529.toString()
                retryBackoffMs529Text = d.retryBackoffMs529.toString()
                typeABenchEnabled = d.typeABenchEnabled
                typeABenchSecondsText = d.typeABenchSeconds.toString()
                typeABenchMaxAttemptsText = d.typeABenchMaxAttempts.toString()
            })
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
                            fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▸", color = AppColors.TextTertiary)
                    }
                }
            }
            SettingCard(
                "Network read timeouts",
                "How long the app waits for an API response before giving up. Streaming applies to chat / report SSE streams (the timeout is the gap between chunks, so the long default is normal). Non-streaming applies to analyze, meta, rerank, fetch-models, translate — everything that blocks for the full response body. Batch item is the wall-clock ceiling for one batch item (a fan-out pair, translation item, tournament match, judge / compare / translator-rank cell) including worker fallbacks and retries — a timed-out item is stamped as a restartable error. Provider-test calls always cap at 30 s regardless.",
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
                OutlinedTextField(
                    value = batchItemTimeoutText,
                    onValueChange = { batchItemTimeoutText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Batch item (seconds)") },
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
                OutlinedButton(
                    onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_NETWORK_PER_PROVIDER) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Per provider") }
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
            SettingCard(
                "Model bench (fixed-model batches)",
                "For Fan Out and Judge the judges — where a model can't be swapped out — a 429/529 parks that model and moves its waiting items to the Bench column instead of erroring or retrying each one in line. The bench lasts for the response's Retry-After hint when present, otherwise the seconds below; siblings of the same model wait too, then everything returns to Queue when the bench lifts. After the max benches an item is left errored. (Worker-swarm batches — Tournament, Compare, Translation, Fan Meta — fall back to another model instead, so this doesn't apply to them.)",
                MetadataDefaults.ROADBLOCK
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Bench fixed-model batches on 429 / 529",
                        color = AppColors.TextPrimary, modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = typeABenchEnabled,
                        onCheckedChange = { typeABenchEnabled = it }
                    )
                }
                OutlinedTextField(
                    value = typeABenchSecondsText,
                    onValueChange = { typeABenchSecondsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Bench seconds (no Retry-After)") },
                    enabled = typeABenchEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = typeABenchMaxAttemptsText,
                    onValueChange = { typeABenchMaxAttemptsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max benches before error") },
                    enabled = typeABenchEnabled,
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

    fun build(): GeneralSettings = generalSettings.copy(
        maxConcurrentApiCalls = apiText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentApiCalls
    )

    LaunchedEffect(apiText) {
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
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "settings_network_api_calls",
            title = "Maximal API calls", subject = "How many calls run at once",
            onBackClick = onBack,
            onClear = { apiText = GeneralSettings().maxConcurrentApiCalls.toString() }
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingCard(
                "Concurrent API calls at the same time",
                "Hard global ceiling on every API call the app keeps in flight at once — reports, translations, fan-out, workers, and any sub-dispatcher under them. Calls beyond the cap suspend until a permit frees up. This is the only concurrency cap; there are no per-batch limits. Default 100.",
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
        }
    }
}

/** Per-provider throttle overrides as a single list. One row per
 *  registered provider, each with the two override fields that also
 *  live on the provider's own edit screen
 *  ([com.ai.data.AppService.maxCallsPerProviderPerMinute] /
 *  [maxConcurrentCallsPerProvider]). Blank = inherit the global
 *  default; a value autosaves straight into [ProviderRegistry] on
 *  every edit. The ✕ at the field's right clears that override. */
@Composable
private fun PerProviderThrottlingSubScreen(onBack: () -> Unit) {
    val providers = remember {
        com.ai.data.ProviderRegistry.getAll().sortedWith(
            compareByDescending<AppService> {
                it.maxCallsPerProviderPerMinute != null || it.maxConcurrentCallsPerProvider != null
            }.thenBy { it.id.lowercase() }
        )
    }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "settings_network_per_provider",
            title = "Per provider", subject = "Per-provider throttle overrides",
            onBackClick = onBack
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Blank fields inherit the global defaults set on the Network settings screen. Any value here overrides that default for the one provider.",
                fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            providers.forEach { service ->
                PerProviderThrottleRow(service)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** One provider's two throttle-override fields. State is local; each
 *  edit re-reads the live registry entry and writes the changed pair
 *  back so a concurrent field edit elsewhere can't be clobbered. */
@Composable
private fun PerProviderThrottleRow(service: AppService) {
    var callsText by remember(service.id) {
        mutableStateOf(service.maxCallsPerProviderPerMinute?.toString() ?: "")
    }
    var concText by remember(service.id) {
        mutableStateOf(service.maxConcurrentCallsPerProvider?.toString() ?: "")
    }
    fun persist() {
        val live = com.ai.data.ProviderRegistry.findById(service.id) ?: return
        com.ai.data.ProviderRegistry.update(
            live.copy(
                maxCallsPerProviderPerMinute = callsText.trim().toIntOrNull()?.takeIf { it > 0 },
                maxConcurrentCallsPerProvider = concText.trim().toIntOrNull()?.takeIf { it > 0 }
            )
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(service.id, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = callsText,
                    onValueChange = { callsText = it.filter { ch -> ch.isDigit() }; persist() },
                    label = { Text("Calls/min", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true, colors = AppColors.outlinedFieldColors(),
                    trailingIcon = {
                        if (callsText.isNotEmpty()) IconButton(onClick = { callsText = ""; persist() }) {
                            Text(com.ai.data.MetadataIconsHolder.current.closeMark, color = AppColors.TextTertiary, fontSize = 12.sp)
                        }
                    }
                )
                OutlinedTextField(
                    value = concText,
                    onValueChange = { concText = it.filter { ch -> ch.isDigit() }; persist() },
                    label = { Text("Concurrent", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true, colors = AppColors.outlinedFieldColors(),
                    trailingIcon = {
                        if (concText.isNotEmpty()) IconButton(onClick = { concText = ""; persist() }) {
                            Text(com.ai.data.MetadataIconsHolder.current.closeMark, color = AppColors.TextTertiary, fontSize = 12.sp)
                        }
                    }
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
    var appHomeMode by remember { mutableStateOf(generalSettings.appHomeMode) }
    var showKnowledgeCard by remember { mutableStateOf(generalSettings.showKnowledgeCard) }
    var fullScreen by remember { mutableStateOf(generalSettings.fullScreen) }
    var showLadybugIcons by remember { mutableStateOf(generalSettings.showLadybugIcons) }
    var experimentalFeatures by remember { mutableStateOf(generalSettings.experimentalFeaturesEnabled) }

    fun build(): GeneralSettings = generalSettings.copy(
        modelNameLayout = modelNameLayout,
        appHomeMode = appHomeMode,
        showKnowledgeCard = showKnowledgeCard,
        fullScreen = fullScreen,
        showLadybugIcons = showLadybugIcons,
        experimentalFeaturesEnabled = experimentalFeatures
    )

    LaunchedEffect(modelNameLayout, appHomeMode, showKnowledgeCard, fullScreen, showLadybugIcons, experimentalFeatures) {
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
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_ui", title = "UI tweaks", subject = "Visual and layout preferences", onBackClick = onBack,
            onClear = {
                val d = GeneralSettings()
                modelNameLayout = d.modelNameLayout
                appHomeMode = d.appHomeMode
                showKnowledgeCard = d.showKnowledgeCard
                fullScreen = d.fullScreen
                showLadybugIcons = d.showLadybugIcons
                experimentalFeatures = d.experimentalFeaturesEnabled
            })
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingCard("App home", "Choose whether Home uses the classic card hub or the persistent top icon bar.", MetadataDefaults.HOME) {
                Column {
                    RadioRow(
                        selected = appHomeMode == AppHomeMode.HOME_SCREEN,
                        label = "Home screen",
                        onClick = { appHomeMode = AppHomeMode.HOME_SCREEN }
                    )
                    RadioRow(
                        selected = appHomeMode == AppHomeMode.HOME_BAR,
                        label = "Home bar",
                        onClick = { appHomeMode = AppHomeMode.HOME_BAR }
                    )
                }
            }
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
            ToggleSettingCard(
                title = "Show Ladybug icons",
                description = "Show the ${com.ai.data.MetadataIconsHolder.current.traces} trace hot-links throughout the app. Turn off to hide every 🐞 link while keeping tracing on — view captured traces from the API Traces screen instead. No effect when API tracing is off.",
                icon = MetadataDefaults.TRACES,
                checked = showLadybugIcons,
                onCheckedChange = { showLadybugIcons = it }
            )
        }
    }
}

@Composable
private fun UiColorsSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    // Two independently-editable colour sets. Night is the original full
    // override map; Day is its light counterpart. The top Day/Night
    // switch only chooses which set the cards edit + preview — the set
    // the app actually paints is governed by Colors mode (UI Tweaks).
    var editingDay by rememberSaveable { mutableStateOf(generalSettings.uiColorMode == com.ai.viewmodel.UiColorMode.DAY) }
    var colorMode by remember { mutableStateOf(generalSettings.uiColorMode) }
    var nightOverrides by remember(generalSettings.uiColorOverrides, generalSettings.uiCardBackgroundArgb, generalSettings.uiButtonBackgroundArgb) {
        mutableStateOf(currentUiColorMap(generalSettings))
    }
    var dayOverrides by remember(generalSettings.uiColorOverridesDay) {
        mutableStateOf(generalSettings.uiColorOverridesDay)
    }
    val editing = if (editingDay) dayOverrides else nightOverrides

    fun build(): GeneralSettings = generalSettings.copy(
        uiCardBackgroundArgb = nightOverrides["CardBackgroundAlt"] ?: DEFAULT_UI_CARD_BACKGROUND_ARGB,
        uiButtonBackgroundArgb = nightOverrides["ButtonBackground"] ?: DEFAULT_UI_BUTTON_BACKGROUND_ARGB,
        uiColorOverrides = nightOverrides,
        uiColorOverridesDay = dayOverrides,
        uiColorMode = colorMode
    )

    // Preview the set currently being edited. This SideEffect is nested
    // below AppNavHost's theme SideEffect, so it runs last and wins while
    // the screen is open; leaving restores the active Colors-mode theme.
    SideEffect {
        AppColors.applyResolved(editingDay, editing)
    }
    LaunchedEffect(dayOverrides, nightOverrides, colorMode) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(250)
            onSave(updated)
        }
    }
    val latestSettings by rememberUpdatedState(build())
    DisposableEffect(Unit) {
        onDispose {
            if (latestSettings != generalSettings) onSave(latestSettings)
        }
    }

    // Per-color "Usage" → source-derived list, layered as a full-screen
    // early-return so the autosave effects above stay composed. Holds the
    // card title + every canonical color key the card represents
    // (combined cards stand in for more than one key).
    var usageForColor by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    usageForColor?.let { (title, keys) ->
        ColorUsageScreen(title = title, keys = keys, onBack = { usageForColor = null })
        return
    }

    fun setEditing(next: Map<String, Int>) {
        if (editingDay) dayOverrides = next else nightOverrides = next
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_ui_colors", title = "UI Colors", subject = "App palette", onBackClick = onBack,
            // 🧽 restores the currently-edited set to its factory default.
            onClear = { setEditing(emptyMap()) })
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingCard("Colors mode", "Which of the two colour sets the app paints. Auto follows the Android system day/night setting.", MetadataDefaults.PALETTE) {
                Column {
                    RadioRow(
                        selected = colorMode == com.ai.viewmodel.UiColorMode.DAY,
                        label = "Day ☀️",
                        onClick = { colorMode = com.ai.viewmodel.UiColorMode.DAY }
                    )
                    RadioRow(
                        selected = colorMode == com.ai.viewmodel.UiColorMode.NIGHT,
                        label = "Night 🌙",
                        onClick = { colorMode = com.ai.viewmodel.UiColorMode.NIGHT }
                    )
                    RadioRow(
                        selected = colorMode == com.ai.viewmodel.UiColorMode.AUTO,
                        label = "Auto (follow system)",
                        onClick = { colorMode = com.ai.viewmodel.UiColorMode.AUTO }
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = editingDay, onClick = { editingDay = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Day ☀️") }
                SegmentedButton(
                    selected = !editingDay, onClick = { editingDay = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Night 🌙") }
            }
            Text(
                "Editing the ${if (editingDay) "Day ☀️" else "Night 🌙"} set. Each set has its own colours; Colors mode (at the top) picks which one the app paints. These are the AppColors used by shared cards, buttons, text, borders, badges, and status accents.",
                fontSize = 12.sp,
                color = AppColors.TextTertiary,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            uiColorPickerSpecs().forEach { spec ->
                UiColorPickerCard(
                    title = spec.title,
                    description = spec.description,
                    icon = spec.icon,
                    argb = editing[spec.key] ?: AppColors.defaultArgbFor(spec.key, editingDay),
                    defaultArgb = AppColors.defaultArgbFor(spec.key, editingDay),
                    onChange = { next ->
                        // A combined row writes the same value to every key it
                        // represents (spec.alsoSet) — e.g. Primary + Info accent.
                        setEditing(editing.toMutableMap().apply {
                            put(spec.key, next)
                            spec.alsoSet.forEach { put(it, next) }
                        }.toMap())
                    },
                    onUsage = { usageForColor = spec.title to (listOf(spec.key) + spec.alsoSet) }
                )
            }
        }
    }
}

private data class UiColorPickerSpec(
    /** Representative key — drives the displayed value, default and reset. */
    val key: String,
    /** Extra keys this single picker also writes (combined rows). */
    val alsoSet: List<String> = emptyList(),
    val title: String,
    val description: String,
    val icon: String
)

private fun currentUiColorMap(generalSettings: GeneralSettings): Map<String, Int> {
    return AppColors.normalizeUiColorOverrides(generalSettings.uiColorOverrides).toMutableMap().apply {
        put("CardBackgroundAlt", generalSettings.uiColorOverrides["CardBackgroundAlt"] ?: generalSettings.uiCardBackgroundArgb)
        put("ButtonBackground", generalSettings.uiColorOverrides["ButtonBackground"] ?: generalSettings.uiButtonBackgroundArgb)
    }
}

private fun uiColorPickerSpecs(): List<UiColorPickerSpec> {
    // Explicit importance order; combined rows appear once via their
    // representative key. Backgrounds & titles → text → card → button →
    // accents → minor surfaces → border. "InfoAccent" / "CardBackground" are
    // folded into the combined rows below.
    val order = listOf(
        "AppBackground", "MainTitle", "SubTitle",
        "TextPrimary", "TextSecondary", "TextDim",
        "CardBackgroundAlt", "ButtonBackground",
        "PrimaryAccent", "SuccessAccent", "DangerAccent", "WarningAccent", "QueueAccent",
        "SurfaceDark", "SelectionHighlight", "DisabledBackground",
        "BorderUnfocused",
    )
    val folded = setOf("InfoAccent", "CardBackground")
    // Append any color key not yet placed (defends against a new AppColors key
    // being added later without updating this list).
    val rest = AppColors.defaultUiColorMap().keys.filter { it !in order && it !in folded }
    return (order + rest).map { key ->
        when (key) {
            "PrimaryAccent" -> UiColorPickerSpec(
                key = "PrimaryAccent",
                alsoSet = listOf("InfoAccent"),
                title = "Accent",
                description = "One accent for primary actions and the secondary/detail accents " +
                    "(headings, links, selected states, totals, focused fields).",
                icon = uiColorIcon("PrimaryAccent")
            )
            "CardBackgroundAlt" -> UiColorPickerSpec(
                key = "CardBackgroundAlt",
                alsoSet = listOf("CardBackground"),
                title = "Card Background",
                description = "One surface for both the gray-blue cards (Monitor / Housekeeping) " +
                    "and the darker dense panels.",
                icon = uiColorIcon("CardBackgroundAlt")
            )
            else -> UiColorPickerSpec(
                key = key,
                title = uiColorTitle(key),
                description = uiColorDescription(key),
                icon = uiColorIcon(key)
            )
        }
    }
}

private fun uiColorTitle(key: String): String = when (key) {
    "InfoAccent" -> "Secondary / Info Accent"
    "SuccessAccent" -> "Success"
    "DangerAccent" -> "Error"
    "WarningAccent" -> "Warning"
    "TextSecondary" -> "Text Secondary"
    "TextDim" -> "Text Dimmed"
    "BorderUnfocused" -> "Border"
    else -> readableUiColorName(key)
}

private fun readableUiColorName(key: String): String =
    key.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")

private fun uiColorDescription(key: String): String = when (key) {
    "AppBackground" -> "Full-screen app background behind every screen."
    "MainTitle" -> "Top title text in the shared screen title bar."
    "SubTitle" -> "Subtitle text below the main title in the shared title bar."
    "PrimaryAccent" -> "Primary action and user-side accent."
    "InfoAccent" -> "Shared by secondary/detail accents, headings, links, selected states, totals, and focused fields."
    "SuccessAccent" -> "Shared by success states and success count highlights."
    "DangerAccent" -> "Shared by danger, error, and destructive action colors."
    "WarningAccent" -> "Shared by warning, caution, running, reload, and throttled highlights."
    "QueueAccent" -> "Queued and alternate worker/category highlights."
    "CardBackgroundAlt" -> "Monitor and Housekeeping gray-blue card surface."
    "ButtonBackground" -> "Neutral outlined button fill."
    "CardBackground" -> "Darker card surface used by older dense panels."
    "SurfaceDark" -> "Primary dark app surface."
    "DisabledBackground" -> "Disabled or unavailable surface fill."
    "SelectionHighlight" -> "Muted highlight strip and selected-state background."
    "TextPrimary" -> "Primary text color."
    "TextSecondary" -> "Shared by secondary and tertiary helper text."
    "TextDim" -> "Shared by dim, disabled, very dim, and darkest low-emphasis text."
    "BorderUnfocused" -> "Shared by subtle dividers, unfocused fields, and swatch borders."
    else -> "AppColors.$key accent."
}

private fun uiColorIcon(key: String): String = when {
    key == "AppBackground" -> MetadataDefaults.DEVICE
    key == "MainTitle" -> MetadataDefaults.TOGGLE_LABELS
    key == "SubTitle" -> MetadataDefaults.LABEL
    key == "PrimaryAccent" -> MetadataDefaults.SPARKLES
    key == "InfoAccent" -> MetadataDefaults.INFO
    key == "SuccessAccent" -> MetadataDefaults.STATUS_DONE
    key == "DangerAccent" -> MetadataDefaults.STATUS_FAILED
    key == "WarningAccent" -> MetadataDefaults.STATUS_WARNING
    key == "QueueAccent" -> MetadataDefaults.CLOCK_QUEUED
    key == "SelectionHighlight" -> MetadataDefaults.CHECKBOX_ON
    key == "ButtonBackground" -> MetadataDefaults.CONTROLS
    key.contains("Card") -> MetadataDefaults.BLUE_DIAMOND
    key.contains("Surface") || key.contains("Background") -> MetadataDefaults.BENTO
    key.contains("Text") -> MetadataDefaults.TOGGLE_LABELS
    key.contains("Border") || key.contains("Divider") -> MetadataDefaults.RULER_STRAIGHT
    key.contains("Pricing") -> MetadataDefaults.COST
    key.contains("Count") -> MetadataDefaults.NUMBER_INPUT
    else -> MetadataDefaults.PALETTE
}

@Composable
private fun UiColorPickerCard(
    title: String,
    description: String,
    icon: String,
    argb: Int,
    defaultArgb: Int,
    onChange: (Int) -> Unit,
    onUsage: () -> Unit
) {
    var hexText by remember { mutableStateOf(argbToRgbHex(argb)) }
    LaunchedEffect(argb) {
        val current = argbToRgbHex(argb)
        if (hexText != current) hexText = current
    }
    val parsedHex = parseRgbHex(hexText)
    val shape = RoundedCornerShape(8.dp)
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val mi = LocalMetadataIcons.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                SettingsCardHeaderIcon(icon)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    Text(argbToRgbHex(argb), fontSize = 11.sp, color = AppColors.TextTertiary)
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(AppColors.colorFromArgb(argb), shape)
                        .border(1.dp, AppColors.BorderUnfocused, shape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    mi.forFactoryGlyph(if (expanded) MetadataDefaults.CARET_EXPANDED else MetadataDefaults.CARET_COLLAPSED),
                    color = AppColors.TextTertiary
                )
            }
            if (!expanded) return@Column
            Text(description, fontSize = 11.sp, color = AppColors.TextTertiary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(AppColors.colorFromArgb(argb), shape)
                        .border(1.dp, AppColors.BorderUnfocused, shape)
                )
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { raw ->
                        hexText = raw.uppercase(java.util.Locale.US)
                        parseRgbHex(raw)?.let(onChange)
                    },
                    label = { Text("Hex") },
                    isError = parsedHex == null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = AppColors.outlinedFieldColors()
                )
                TextButton(onClick = { onChange(defaultArgb) }) {
                    Text("Default", maxLines = 1, softWrap = false)
                }
            }
            ColorChannelSlider("Red", colorChannel(argb, 16)) { onChange(withColorChannel(argb, 16, it)) }
            ColorChannelSlider("Green", colorChannel(argb, 8)) { onChange(withColorChannel(argb, 8, it)) }
            ColorChannelSlider("Blue", colorChannel(argb, 0)) { onChange(withColorChannel(argb, 0, it)) }
            OutlinedButton(
                onClick = onUsage, modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Usage") }
        }
    }
}

/** Source-derived "where is this color used" screen. Reads the
 *  build-time [com.ai.data.ColorUsageData] index for every canonical
 *  color key the card represents ([keys]) and renders a screen / element
 *  table. The list is a static snapshot of the `AppColors.<token>`
 *  references in the source (alias accessors folded into their canonical
 *  key) — it is NOT recomputed at runtime, hence the disclaimer +
 *  generated timestamp at the top. */
@Composable
private fun ColorUsageScreen(title: String, keys: List<String>, onBack: () -> Unit) {
    BackHandler { onBack() }
    val usages = remember(keys) { com.ai.data.ColorUsageData.forKeys(keys) }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(title = "Usage: $title", subject = "Where this color is used in the source", onBackClick = onBack)
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "This table is derived from a static scan of the source code, not computed at runtime — it can drift as the code changes. Element names are best-effort (the colored parameter / composable role).",
                    fontSize = 12.sp, color = AppColors.TextSecondary
                )
                Text(
                    "Generated: ${com.ai.data.ColorUsageData.GENERATED_AT}  ·  ${usages.size} reference${if (usages.size == 1) "" else "s"}",
                    fontSize = 12.sp, color = AppColors.TextTertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        // Header row
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Screen", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(0.4f))
            Text("Element", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(0.6f))
        }
        if (usages.isEmpty()) {
            Text(
                "No source references found for this color.",
                fontSize = 13.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(usages) { u ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(0.4f)) {
                            Text(u.screen, fontSize = 13.sp, color = AppColors.TextPrimary)
                            Text(u.location, fontSize = 10.sp, color = AppColors.TextDim,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Text(u.element, fontSize = 12.sp, color = AppColors.TextSecondary,
                            modifier = Modifier.weight(0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RankingWeightsSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    // One card for the three named rankings, one for every Tournament method.
    val coreEntries = listOf("rerank" to "Rerank", "judges" to "Judges", "translations" to "Translations", "compare" to "Compare")
    val tournamentEntries = remember {
        com.ai.data.TournamentMethod.values().map { m -> m.name to m.name.lowercase().replaceFirstChar { it.uppercase() } }
    }
    var weights by remember { mutableStateOf(generalSettings.rankingWeights) }
    fun build(): GeneralSettings = generalSettings.copy(rankingWeights = weights)
    LaunchedEffect(weights) {
        val updated = build()
        if (updated != generalSettings) { kotlinx.coroutines.delay(400); onSave(updated) }
    }
    DisposableEffect(Unit) {
        onDispose { val u = build(); if (u != generalSettings) onSave(u) }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "settings_ranking_weights", title = "Ranking weights",
            subject = "Weight each ranking 0–10", onBackClick = onBack,
            // 🧽 clear (icons bar) → factory defaults (an empty map resolves to them).
            onClear = { weights = emptyMap() }
        )
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Each ranking gets a weight from 0 to 10. The 🧽 in the icons bar resets every slider to its default.",
                fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 8.dp)
            )
            val setWeight: (String, Int) -> Unit = { key, v -> weights = weights + (key to v) }
            RankingWeightCard("Rerank · Judges · Translations · Compare", coreEntries, weights, setWeight)
            RankingWeightCard("Tournament rankings", tournamentEntries, weights, setWeight)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RankingWeightCard(
    title: String,
    entries: List<Pair<String, String>>,
    weights: Map<String, Int>,
    onChange: (String, Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            entries.forEach { (key, label) ->
                val value = weights[key] ?: (com.ai.viewmodel.RANKING_WEIGHT_DEFAULTS[key] ?: 0)
                RankingWeightSlider(label, value) { v -> onChange(key, v) }
            }
        }
    }
}

@Composable
private fun RankingWeightSlider(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, color = AppColors.TextPrimary, modifier = Modifier.width(112.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(0, 10)) },
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.weight(1f)
        )
        Text(
            value.toString().padStart(2, ' '), fontSize = 14.sp, color = AppColors.TextSecondary,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.width(28.dp).padding(start = 6.dp)
        )
    }
}

@Composable
private fun ColorChannelSlider(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.width(52.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            steps = 254,
            modifier = Modifier.weight(1f)
        )
        Text(value.toString().padStart(3, ' '), fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.width(34.dp))
    }
}

private fun colorChannel(argb: Int, shift: Int): Int = (argb ushr shift) and 0xFF

private fun withColorChannel(argb: Int, shift: Int, value: Int): Int {
    val cleared = argb and (0xFF shl shift).inv()
    return (cleared or ((value.coerceIn(0, 255) and 0xFF) shl shift)) or 0xFF000000.toInt()
}

private fun argbToRgbHex(argb: Int): String = String.format(
    java.util.Locale.US,
    "#%02X%02X%02X",
    colorChannel(argb, 16),
    colorChannel(argb, 8),
    colorChannel(argb, 0)
)

private fun parseRgbHex(raw: String): Int? {
    val clean = raw.trim().removePrefix("#").takeLast(6)
    if (clean.length != 6 || clean.any { it !in '0'..'9' && it !in 'A'..'F' && it !in 'a'..'f' }) return null
    return (0xFF000000L or clean.toLong(16)).toInt()
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

    fun build(): GeneralSettings = generalSettings.copy(
        userName = userName,
        defaultEmail = defaultEmail
    )

    LaunchedEffect(userName, defaultEmail) {
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
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_other", title = "Other settings", subject = "Identity", onBackClick = onBack,
            onClear = {
                val d = GeneralSettings()
                userName = d.userName
                defaultEmail = d.defaultEmail
            })
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
        }
    }
}

/** "Autostart" — what the app kicks off automatically when a report finishes:
 *  auto-create Rerank & Moderation, autostart Fan Meta, and the default meta
 *  items list. Grouped here (moved out of Other settings / Metadata / AI
 *  Setup) so all the report-completion automation lives in one place. */
@Composable
private fun AutostartSubScreen(
    generalSettings: GeneralSettings,
    aiSettings: com.ai.model.Settings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenDefaultMetaItems: () -> Unit
) {
    var autostartItemsEnabled by remember { mutableStateOf(generalSettings.autostartItemsEnabled) }
    var autoCreateRerankAndModeration by remember { mutableStateOf(generalSettings.autoCreateRerankAndModeration) }
    var autostartFanMeta by remember { mutableStateOf(generalSettings.autostartFanMeta) }

    fun build(): GeneralSettings = generalSettings.copy(
        autostartItemsEnabled = autostartItemsEnabled,
        autoCreateRerankAndModeration = autoCreateRerankAndModeration,
        autostartFanMeta = autostartFanMeta
    )
    LaunchedEffect(autostartItemsEnabled, autoCreateRerankAndModeration, autostartFanMeta) {
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
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_autostart", title = "Autostart", subject = "What runs automatically when a report finishes", onBackClick = onBack,
            onClear = {
                val d = GeneralSettings()
                autostartItemsEnabled = d.autostartItemsEnabled
                autoCreateRerankAndModeration = d.autoCreateRerankAndModeration
                autostartFanMeta = d.autostartFanMeta
            })
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleSettingCard(
                title = "Autostart items",
                description = "Master switch for everything that runs by itself when a report finishes. Off (default) means the app never autostarts anything and the per-item options below stay hidden. Turn it on to reveal and configure them.",
                icon = MetadataDefaults.ROCKET,
                checked = autostartItemsEnabled,
                onCheckedChange = { autostartItemsEnabled = it }
            )
            if (autostartItemsEnabled) {
                ToggleSettingCard(
                    title = "Auto create Rerank and Moderation",
                    description = "When a report's models all finish, automatically create one Rerank and one Moderation — each using the first rerank- / moderation-capable model found among your active providers. A kind is skipped when no capable model exists or one is already present. Manual Rerank / Moderation still lets you pick the model.",
                    icon = MetadataDefaults.REPEAT,
                    checked = autoCreateRerankAndModeration,
                    onCheckedChange = { autoCreateRerankAndModeration = it }
                )
                ToggleSettingCard(
                    title = "Autostart Fan Meta",
                    description = "When a Fan Out finishes with no errored pairs, automatically kick off its Fan Meta batch (one call per pair produces both the title and the icon) — so you don't have to tap the Fan Meta button by hand. A run with any error pair is left alone; you can still start it manually.",
                    icon = MetadataDefaults.FAN_OUT,
                    checked = autostartFanMeta,
                    onCheckedChange = { autostartFanMeta = it }
                )
                SettingsNavCard(
                    icon = MetadataDefaults.FAN_IN,
                    title = "Default meta items",
                    description = "Meta prompts auto-run when a report finishes (${aiSettings.defaultMetaItems.size}).",
                    onClick = onOpenDefaultMetaItems
                )
            }
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

    fun build(): GeneralSettings = generalSettings.copy(
        metadataEnabled = metadataEnabled,
        reportTitleMode = reportTitleMode,
        iconGenEnabled = iconGenEnabled,
        reportLanguageGenEnabled = reportLanguageGenEnabled,
        perModelIconGenEnabled = perModelIconGenEnabled,
        perModelTitleGenEnabled = perModelTitleGenEnabled,
        useInternalPromptsIcons = useInternalPromptsIcons
    )

    LaunchedEffect(metadataEnabled, reportTitleMode, iconGenEnabled, reportLanguageGenEnabled, perModelIconGenEnabled, perModelTitleGenEnabled, useInternalPromptsIcons) {
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
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_metadata", title = "Metadata & icons", subject = "Master switch and per-item options for optional report metadata", onBackClick = onBack,
            onClear = {
                val d = GeneralSettings()
                metadataEnabled = d.metadataEnabled
                reportTitleMode = d.reportTitleMode
                iconGenEnabled = d.iconGenEnabled
                reportLanguageGenEnabled = d.reportLanguageGenEnabled
                perModelIconGenEnabled = d.perModelIconGenEnabled
                perModelTitleGenEnabled = d.perModelTitleGenEnabled
                useInternalPromptsIcons = d.useInternalPromptsIcons
            })
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleSettingCard(
                title = "Generate metadata & icons",
                description = "Grand-master switch for every optional metadata item: report icon, report language, AI report title, per-model icons & titles, and internal-prompt row icons. When off, none of it is generated and the related UI disappears, including the Manage report info row; a new report must be given a manual title. View screens are unaffected: a report that already has icons keeps showing them. Turn it on to reveal the per-item toggles below.",
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
                    description = "Detect the report's language and pick a matching language icon after report start. Surfaces as the language row on the info screen and in language-aware report headers. Independent of the report icon.",
                    icon = MetadataDefaults.LANGUAGE,
                    checked = reportLanguageGenEnabled,
                    onCheckedChange = { reportLanguageGenEnabled = it }
                )
                ToggleSettingCard(
                    title = "Generate per model icons",
                    description = "Auto-run the workers/model-icons flow after each successful report response. Each row's leftmost ${com.ai.data.MetadataIconsHolder.current.statusDone} flips to a returned emoji once the worker finishes. Costs accumulate on the row's cost cell and in AI Usage.",
                    icon = MetadataDefaults.MODEL_ICON,
                    checked = perModelIconGenEnabled,
                    onCheckedChange = { perModelIconGenEnabled = it }
                )
                ToggleSettingCard(
                    title = "Generate per model titles",
                    description = "After each model response, run the configured model-title worker to title that response in ≤4 words. The title replaces the model name on the Manage report row; its cost folds into that row and the report cost table. Off by default — it's one extra LLM call per model.",
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
            }
        }
    }
}

/** One editable row on the Default icons screen, bound to a single
 *  [com.ai.data.MetadataIcons] field: its [label], a getter/setter over the
 *  immutable data class, and the [factory] glyph a blank field falls back to. */
private class IconRowSpec(
    val label: String,
    val field: String,
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
            IconRowSpec("Report", "reportIcon", { it.reportIcon }, { m, v -> m.copy(reportIcon = v) }, d.REPORT_ICON),
            IconRowSpec("Report model", "reportModelIcon", { it.reportModelIcon }, { m, v -> m.copy(reportModelIcon = v) }, d.MODEL_ICON),
        ),
        "Secondary results" to listOf(
            IconRowSpec("Rerank", "rerank", { it.rerank }, { m, v -> m.copy(rerank = v) }, d.RERANK),
            IconRowSpec("Moderate", "moderate", { it.moderate }, { m, v -> m.copy(moderate = v) }, d.MODERATE),
            IconRowSpec("Meta", "meta", { it.meta }, { m, v -> m.copy(meta = v) }, d.META),
            IconRowSpec("Tournament", "tournament", { it.tournament }, { m, v -> m.copy(tournament = v) }, d.TOURNAMENT),
            IconRowSpec("Judges", "judges", { it.judges }, { m, v -> m.copy(judges = v) }, d.JUDGES),
            IconRowSpec("Compare", "compare", { it.compare }, { m, v -> m.copy(compare = v) }, d.COMPARE),
            IconRowSpec("Fan out", "fanOutRow", { it.fanOutRow }, { m, v -> m.copy(fanOutRow = v) }, d.FAN_OUT),
            IconRowSpec("Fan in", "fanInRow", { it.fanInRow }, { m, v -> m.copy(fanInRow = v) }, d.FAN_IN),
        ),
        "Translation" to listOf(
            IconRowSpec("Language", "languageIcon", { it.languageIcon }, { m, v -> m.copy(languageIcon = v) }, d.LANGUAGE),
            IconRowSpec("Translation row", "translationRow", { it.translationRow }, { m, v -> m.copy(translationRow = v) }, d.TRANSLATE),
            IconRowSpec("Translation compare", "translationCompare", { it.translationCompare }, { m, v -> m.copy(translationCompare = v) }, d.TRANSLATION_COMPARE),
        ),
        "Monitor bar" to listOf(
            IconRowSpec("Live dashboard", "liveDashboard", { it.liveDashboard }, { m, v -> m.copy(liveDashboard = v) }, d.LIVE_DASHBOARD),
            IconRowSpec("Traces", "traces", { it.traces }, { m, v -> m.copy(traces = v) }, d.TRACES),
            IconRowSpec("App log", "appLog", { it.appLog }, { m, v -> m.copy(appLog = v) }, d.APP_LOG),
            IconRowSpec("Audit", "audit", { it.audit }, { m, v -> m.copy(audit = v) }, d.AUDIT),
            IconRowSpec("Statistics (monitor)", "statisticsMonitor", { it.statisticsMonitor }, { m, v -> m.copy(statisticsMonitor = v) }, d.STATISTICS_MONITOR),
        ),
        "Create & chat" to listOf(
            IconRowSpec("Add / new", "add", { it.add }, { m, v -> m.copy(add = v) }, d.ADD),
            IconRowSpec("Chat", "chat", { it.chat }, { m, v -> m.copy(chat = v) }, d.CHAT),
            IconRowSpec("Agent chat", "agentChat", { it.agentChat }, { m, v -> m.copy(agentChat = v) }, d.AGENT_CHAT),
            IconRowSpec("Temperature sweep", "temperatureSweep", { it.temperatureSweep }, { m, v -> m.copy(temperatureSweep = v) }, d.TEMPERATURE_SWEEP),
            IconRowSpec("Reasoning sweep", "reasoningSweep", { it.reasoningSweep }, { m, v -> m.copy(reasoningSweep = v) }, d.REASONING_SWEEP),
            IconRowSpec("Web-search replay", "webSearchReplay", { it.webSearchReplay }, { m, v -> m.copy(webSearchReplay = v) }, d.WEB_SEARCH_REPLAY),
        ),
        "Navigation" to listOf(
            IconRowSpec("Pick report", "pickReport", { it.pickReport }, { m, v -> m.copy(pickReport = v) }, d.PICK_REPORT),
            IconRowSpec("Import report", "importReport", { it.importReport }, { m, v -> m.copy(importReport = v) }, d.IMPORT),
            IconRowSpec("Open manage", "openManage", { it.openManage }, { m, v -> m.copy(openManage = v) }, d.OPEN_MANAGE),
            IconRowSpec("Housekeeping", "housekeeping", { it.housekeeping }, { m, v -> m.copy(housekeeping = v) }, d.HOUSEKEEPING),
            IconRowSpec("Settings", "settings", { it.settings }, { m, v -> m.copy(settings = v) }, d.SETTINGS),
            IconRowSpec("Statistics", "statistics", { it.statistics }, { m, v -> m.copy(statistics = v) }, d.STATISTICS),
            IconRowSpec("Info", "info", { it.info }, { m, v -> m.copy(info = v) }, d.INFO),
        ),
        "Configuration" to listOf(
            IconRowSpec("Parameters", "parameters", { it.parameters }, { m, v -> m.copy(parameters = v) }, d.PARAMETERS),
            IconRowSpec("System prompt", "systemPrompt", { it.systemPrompt }, { m, v -> m.copy(systemPrompt = v) }, d.SYSTEM_PROMPT),
            IconRowSpec("Clear", "clear", { it.clear }, { m, v -> m.copy(clear = v) }, d.CLEAR),
            IconRowSpec("Attach", "attach", { it.attach }, { m, v -> m.copy(attach = v) }, d.ATTACH),
            IconRowSpec("Validate prompt", "validatePrompt", { it.validatePrompt }, { m, v -> m.copy(validatePrompt = v) }, d.VALIDATE_PROMPT),
        ),
        "Report actions" to listOf(
            IconRowSpec("Copy", "copy", { it.copy }, { m, v -> m.copy(copy = v) }, d.COPY),
            IconRowSpec("Pin", "pin", { it.pin }, { m, v -> m.copy(pin = v) }, d.PIN),
            IconRowSpec("Toggle labels", "toggleLabels", { it.toggleLabels }, { m, v -> m.copy(toggleLabels = v) }, d.TOGGLE_LABELS),
            IconRowSpec("Share", "share", { it.share }, { m, v -> m.copy(share = v) }, d.SHARE),
            IconRowSpec("Duplicate", "duplicate", { it.duplicate }, { m, v -> m.copy(duplicate = v) }, d.DUPLICATE),
        ),
        "Item actions" to listOf(
            IconRowSpec("View", "view", { it.view }, { m, v -> m.copy(view = v) }, d.VIEW),
            IconRowSpec("Memo", "memo", { it.memo }, { m, v -> m.copy(memo = v) }, d.MEMO),
            IconRowSpec("Add note", "addNote", { it.addNote }, { m, v -> m.copy(addNote = v) }, d.ADD_NOTE),
            IconRowSpec("List notes", "listNotes", { it.listNotes }, { m, v -> m.copy(listNotes = v) }, d.LIST_NOTES),
            IconRowSpec("Edit", "edit", { it.edit }, { m, v -> m.copy(edit = v) }, d.EDIT),
            IconRowSpec("Reload", "reload", { it.reload }, { m, v -> m.copy(reload = v) }, d.RELOAD),
            IconRowSpec("Delete", "delete", { it.delete }, { m, v -> m.copy(delete = v) }, d.DELETE),
        ),
        "View bar & help" to listOf(
            IconRowSpec("Show all", "viewShowAll", { it.viewShowAll }, { m, v -> m.copy(viewShowAll = v) }, d.VIEW_SHOW_ALL),
            IconRowSpec("Show one", "viewShowOne", { it.viewShowOne }, { m, v -> m.copy(viewShowOne = v) }, d.VIEW_SHOW_ONE),
            IconRowSpec("Help", "help", { it.help }, { m, v -> m.copy(help = v) }, d.HELP),
            IconRowSpec("Icons help", "helpLegend", { it.helpLegend }, { m, v -> m.copy(helpLegend = v) }, d.HELP_LEGEND),
        ),
        "Status & progress" to listOf(
            IconRowSpec("Done", "statusDone", { it.statusDone }, { m, v -> m.copy(statusDone = v) }, d.STATUS_DONE),
            IconRowSpec("Failed", "statusFailed", { it.statusFailed }, { m, v -> m.copy(statusFailed = v) }, d.STATUS_FAILED),
            IconRowSpec("Pending", "statusPending", { it.statusPending }, { m, v -> m.copy(statusPending = v) }, d.STATUS_PENDING),
            IconRowSpec("Paused", "statusPaused", { it.statusPaused }, { m, v -> m.copy(statusPaused = v) }, d.STATUS_PAUSED),
            IconRowSpec("Stopped", "statusStopped", { it.statusStopped }, { m, v -> m.copy(statusStopped = v) }, d.STATUS_STOPPED),
            IconRowSpec("Alarm", "statusAlarm", { it.statusAlarm }, { m, v -> m.copy(statusAlarm = v) }, d.STATUS_ALARM),
            IconRowSpec("Blocked", "statusBlocked", { it.statusBlocked }, { m, v -> m.copy(statusBlocked = v) }, d.STATUS_BLOCKED),
            IconRowSpec("Locked", "statusLocked", { it.statusLocked }, { m, v -> m.copy(statusLocked = v) }, d.STATUS_LOCKED),
            IconRowSpec("Warning", "statusWarning", { it.statusWarning }, { m, v -> m.copy(statusWarning = v) }, d.STATUS_WARNING),
            IconRowSpec("Warning (plain)", "warningPlain", { it.warningPlain }, { m, v -> m.copy(warningPlain = v) }, d.WARNING_PLAIN),
            IconRowSpec("Hot", "hot", { it.hot }, { m, v -> m.copy(hot = v) }, d.HOT),
            IconRowSpec("Clock", "clockTime", { it.clockTime }, { m, v -> m.copy(clockTime = v) }, d.CLOCK_TIME),
            IconRowSpec("Clock (queued)", "clockQueued", { it.clockQueued }, { m, v -> m.copy(clockQueued = v) }, d.CLOCK_QUEUED),
            IconRowSpec("Clock (recent)", "clockRecent", { it.clockRecent }, { m, v -> m.copy(clockRecent = v) }, d.CLOCK_RECENT),
            IconRowSpec("Sleep / inactive", "sleep", { it.sleep }, { m, v -> m.copy(sleep = v) }, d.SLEEP),
            IconRowSpec("No entry", "noEntry", { it.noEntry }, { m, v -> m.copy(noEntry = v) }, d.NO_ENTRY),
            IconRowSpec("Snowflake", "snowflake", { it.snowflake }, { m, v -> m.copy(snowflake = v) }, d.SNOWFLAKE),
            IconRowSpec("Roadblock", "roadblock", { it.roadblock }, { m, v -> m.copy(roadblock = v) }, d.ROADBLOCK),
            IconRowSpec("Explosion", "explosion", { it.explosion }, { m, v -> m.copy(explosion = v) }, d.EXPLOSION),
        ),
        "Marks & ranks" to listOf(
            IconRowSpec("Check", "checkMark", { it.checkMark }, { m, v -> m.copy(checkMark = v) }, d.CHECK),
            IconRowSpec("Cross", "crossMark", { it.crossMark }, { m, v -> m.copy(crossMark = v) }, d.CROSS),
            IconRowSpec("Close", "closeMark", { it.closeMark }, { m, v -> m.copy(closeMark = v) }, d.CLOSE),
            IconRowSpec("Checkbox on", "checkboxOn", { it.checkboxOn }, { m, v -> m.copy(checkboxOn = v) }, d.CHECKBOX_ON),
            IconRowSpec("Checkbox off", "checkboxOff", { it.checkboxOff }, { m, v -> m.copy(checkboxOff = v) }, d.CHECKBOX_OFF),
            IconRowSpec("Blank box", "boxBlank", { it.boxBlank }, { m, v -> m.copy(boxBlank = v) }, d.BOX_BLANK),
            IconRowSpec("Gold medal", "medalGold", { it.medalGold }, { m, v -> m.copy(medalGold = v) }, d.MEDAL_GOLD),
            IconRowSpec("Silver medal", "medalSilver", { it.medalSilver }, { m, v -> m.copy(medalSilver = v) }, d.MEDAL_SILVER),
            IconRowSpec("Bronze medal", "medalBronze", { it.medalBronze }, { m, v -> m.copy(medalBronze = v) }, d.MEDAL_BRONZE),
        ),
        "Arrows" to listOf(
            IconRowSpec("Arrow up", "arrowUp", { it.arrowUp }, { m, v -> m.copy(arrowUp = v) }, d.ARROW_UP),
            IconRowSpec("Arrow right", "arrowRight", { it.arrowRight }, { m, v -> m.copy(arrowRight = v) }, d.ARROW_RIGHT),
            IconRowSpec("Arrow down", "arrowDown", { it.arrowDown }, { m, v -> m.copy(arrowDown = v) }, d.ARROW_DOWN),
            IconRowSpec("Arrow back", "arrowBack", { it.arrowBack }, { m, v -> m.copy(arrowBack = v) }, d.ARROW_BACK),
            IconRowSpec("Arrow forward", "arrowForward", { it.arrowForward }, { m, v -> m.copy(arrowForward = v) }, d.ARROW_FORWARD),
            IconRowSpec("Submit arrow", "arrowSubmit", { it.arrowSubmit }, { m, v -> m.copy(arrowSubmit = v) }, d.ARROW_SUBMIT),
            IconRowSpec("Caret expanded", "caretExpanded", { it.caretExpanded }, { m, v -> m.copy(caretExpanded = v) }, d.CARET_EXPANDED),
            IconRowSpec("Caret collapsed", "caretCollapsed", { it.caretCollapsed }, { m, v -> m.copy(caretCollapsed = v) }, d.CARET_COLLAPSED),
            IconRowSpec("Minus", "minus", { it.minus }, { m, v -> m.copy(minus = v) }, d.MINUS),
        ),
        "Search & files" to listOf(
            IconRowSpec("Agent / AI", "agent", { it.agent }, { m, v -> m.copy(agent = v) }, d.AGENT),
            IconRowSpec("AI find", "aiFind", { it.aiFind }, { m, v -> m.copy(aiFind = v) }, d.AI_FIND),
            IconRowSpec("Web / remote", "web", { it.web }, { m, v -> m.copy(web = v) }, d.WEB),
            IconRowSpec("Lookup", "lookup", { it.lookup }, { m, v -> m.copy(lookup = v) }, d.LOOKUP),
            IconRowSpec("Search", "search", { it.search }, { m, v -> m.copy(search = v) }, d.SEARCH),
            IconRowSpec("Open folder", "folderOpen", { it.folderOpen }, { m, v -> m.copy(folderOpen = v) }, d.FOLDER_OPEN),
            IconRowSpec("Label", "label", { it.label }, { m, v -> m.copy(label = v) }, d.LABEL),
            IconRowSpec("Bookmark", "bookmark", { it.bookmark }, { m, v -> m.copy(bookmark = v) }, d.BOOKMARK),
            IconRowSpec("Notepad", "notepad", { it.notepad }, { m, v -> m.copy(notepad = v) }, d.NOTEPAD),
            IconRowSpec("Document", "document", { it.document }, { m, v -> m.copy(document = v) }, d.DOCUMENT),
            IconRowSpec("Package", "packageBox", { it.packageBox }, { m, v -> m.copy(packageBox = v) }, d.PACKAGE_BOX),
            IconRowSpec("Plug", "plug", { it.plug }, { m, v -> m.copy(plug = v) }, d.PLUG),
            IconRowSpec("Key", "key", { it.key }, { m, v -> m.copy(key = v) }, d.KEY),
        ),
        "Content & media" to listOf(
            IconRowSpec("World", "world", { it.world }, { m, v -> m.copy(world = v) }, d.WORLD),
            IconRowSpec("Chart", "chart", { it.chart }, { m, v -> m.copy(chart = v) }, d.CHART),
            IconRowSpec("Cyclone", "cyclone", { it.cyclone }, { m, v -> m.copy(cyclone = v) }, d.CYCLONE),
            IconRowSpec("Library", "library", { it.library }, { m, v -> m.copy(library = v) }, d.LIBRARY),
            IconRowSpec("Book", "book", { it.book }, { m, v -> m.copy(book = v) }, d.BOOK),
            IconRowSpec("Image", "image", { it.image }, { m, v -> m.copy(image = v) }, d.IMAGE),
            IconRowSpec("Mail", "mail", { it.mail }, { m, v -> m.copy(mail = v) }, d.MAIL),
            IconRowSpec("Speech", "speech", { it.speech }, { m, v -> m.copy(speech = v) }, d.SPEECH),
            IconRowSpec("Gem", "gem", { it.gem }, { m, v -> m.copy(gem = v) }, d.GEM),
            IconRowSpec("Tip", "tip", { it.tip }, { m, v -> m.copy(tip = v) }, d.TIP),
            IconRowSpec("Camera", "camera", { it.camera }, { m, v -> m.copy(camera = v) }, d.CAMERA),
            IconRowSpec("Gift", "gift", { it.gift }, { m, v -> m.copy(gift = v) }, d.GIFT),
            IconRowSpec("Rocket", "rocket", { it.rocket }, { m, v -> m.copy(rocket = v) }, d.ROCKET),
            IconRowSpec("Home", "home", { it.home }, { m, v -> m.copy(home = v) }, d.HOME),
            IconRowSpec("Save", "save", { it.save }, { m, v -> m.copy(save = v) }, d.SAVE),
            IconRowSpec("Cloud", "cloud", { it.cloud }, { m, v -> m.copy(cloud = v) }, d.CLOUD),
            IconRowSpec("Building blocks", "buildingBlocks", { it.buildingBlocks }, { m, v -> m.copy(buildingBlocks = v) }, d.BUILDING_BLOCKS),
            IconRowSpec("Groupings", "groupings", { it.groupings }, { m, v -> m.copy(groupings = v) }, d.GROUPINGS),
            IconRowSpec("GitHub", "github", { it.github }, { m, v -> m.copy(github = v) }, d.GITHUB),
        ),
        "Cost" to listOf(
            IconRowSpec("Cost", "cost", { it.cost }, { m, v -> m.copy(cost = v) }, d.COST),
            IconRowSpec("Dollar", "dollar", { it.dollar }, { m, v -> m.copy(dollar = v) }, d.DOLLAR),
            IconRowSpec("Spend", "spend", { it.spend }, { m, v -> m.copy(spend = v) }, d.SPEND),
            IconRowSpec("Cash", "cash", { it.cash }, { m, v -> m.copy(cash = v) }, d.CASH),
        ),
        "Workers & tools" to listOf(
            IconRowSpec("Swarm", "swarm", { it.swarm }, { m, v -> m.copy(swarm = v) }, d.SWARM),
            IconRowSpec("Batch workers", "ant", { it.ant }, { m, v -> m.copy(ant = v) }, d.ANT),
            IconRowSpec("Flock", "flock", { it.flock }, { m, v -> m.copy(flock = v) }, d.FLOCK),
            IconRowSpec("Fan-in knot", "fanInKnot", { it.fanInKnot }, { m, v -> m.copy(fanInKnot = v) }, d.FAN_IN_KNOT),
            IconRowSpec("Feather", "feather", { it.feather }, { m, v -> m.copy(feather = v) }, d.FEATHER),
            IconRowSpec("Tools", "tools", { it.tools }, { m, v -> m.copy(tools = v) }, d.TOOLS),
            IconRowSpec("Toolbox", "toolbox", { it.toolbox }, { m, v -> m.copy(toolbox = v) }, d.TOOLBOX),
            IconRowSpec("Puzzle", "puzzle", { it.puzzle }, { m, v -> m.copy(puzzle = v) }, d.PUZZLE),
            IconRowSpec("Palette", "palette", { it.palette }, { m, v -> m.copy(palette = v) }, d.PALETTE),
            IconRowSpec("Test", "test", { it.test }, { m, v -> m.copy(test = v) }, d.TEST),
            IconRowSpec("Worker", "worker", { it.worker }, { m, v -> m.copy(worker = v) }, d.WORKER),
            IconRowSpec("Sparkles", "sparkles", { it.sparkles }, { m, v -> m.copy(sparkles = v) }, d.SPARKLES),
            IconRowSpec("Ruler", "ruler", { it.ruler }, { m, v -> m.copy(ruler = v) }, d.RULER),
            IconRowSpec("Straight ruler", "rulerStraight", { it.rulerStraight }, { m, v -> m.copy(rulerStraight = v) }, d.RULER_STRAIGHT),
            IconRowSpec("Shuffle", "shuffle", { it.shuffle }, { m, v -> m.copy(shuffle = v) }, d.SHUFFLE),
            IconRowSpec("Repeat", "repeat", { it.repeat }, { m, v -> m.copy(repeat = v) }, d.REPEAT),
            IconRowSpec("Hide", "hide", { it.hide }, { m, v -> m.copy(hide = v) }, d.HIDE),
            IconRowSpec("Shield", "shield", { it.shield }, { m, v -> m.copy(shield = v) }, d.SHIELD),
            IconRowSpec("Microscope", "microscope", { it.microscope }, { m, v -> m.copy(microscope = v) }, d.MICROSCOPE),
            IconRowSpec("Controls", "controls", { it.controls }, { m, v -> m.copy(controls = v) }, d.CONTROLS),
            IconRowSpec("Sliders", "sliders", { it.sliders }, { m, v -> m.copy(sliders = v) }, d.SLIDERS),
            IconRowSpec("Magic", "magic", { it.magic }, { m, v -> m.copy(magic = v) }, d.MAGIC),
        ),
        "Devices & misc" to listOf(
            IconRowSpec("Device", "device", { it.device }, { m, v -> m.copy(device = v) }, d.DEVICE),
            IconRowSpec("Computer", "computer", { it.computer }, { m, v -> m.copy(computer = v) }, d.COMPUTER),
            IconRowSpec("Satellite", "satellite", { it.satellite }, { m, v -> m.copy(satellite = v) }, d.SATELLITE),
            IconRowSpec("Hugging Face", "huggingface", { it.huggingface }, { m, v -> m.copy(huggingface = v) }, d.HUGGINGFACE),
            IconRowSpec("Green circle", "greenCircle", { it.greenCircle }, { m, v -> m.copy(greenCircle = v) }, d.GREEN_CIRCLE),
            IconRowSpec("White circle", "whiteCircle", { it.whiteCircle }, { m, v -> m.copy(whiteCircle = v) }, d.WHITE_CIRCLE),
            IconRowSpec("Red circle", "redCircle", { it.redCircle }, { m, v -> m.copy(redCircle = v) }, d.RED_CIRCLE),
            IconRowSpec("Orange circle", "orangeCircle", { it.orangeCircle }, { m, v -> m.copy(orangeCircle = v) }, d.ORANGE_CIRCLE),
            IconRowSpec("Blue circle", "blueCircle", { it.blueCircle }, { m, v -> m.copy(blueCircle = v) }, d.BLUE_CIRCLE),
            IconRowSpec("Sun", "sun", { it.sun }, { m, v -> m.copy(sun = v) }, d.SUN),
            IconRowSpec("Calendar", "calendar", { it.calendar }, { m, v -> m.copy(calendar = v) }, d.CALENDAR),
            IconRowSpec("Spiral calendar", "calendarSpiral", { it.calendarSpiral }, { m, v -> m.copy(calendarSpiral = v) }, d.CALENDAR_SPIRAL),
            IconRowSpec("Runner", "runner", { it.runner }, { m, v -> m.copy(runner = v) }, d.RUNNER),
            IconRowSpec("Fog", "fog", { it.fog }, { m, v -> m.copy(fog = v) }, d.FOG),
            IconRowSpec("Bento", "bento", { it.bento }, { m, v -> m.copy(bento = v) }, d.BENTO),
            IconRowSpec("Number input", "numberInput", { it.numberInput }, { m, v -> m.copy(numberInput = v) }, d.NUMBER_INPUT),
            IconRowSpec("Symbols", "symbols", { it.symbols }, { m, v -> m.copy(symbols = v) }, d.SYMBOLS),
            IconRowSpec("Handshake", "handshake", { it.handshake }, { m, v -> m.copy(handshake = v) }, d.HANDSHAKE),
            IconRowSpec("Blue diamond", "blueDiamond", { it.blueDiamond }, { m, v -> m.copy(blueDiamond = v) }, d.BLUE_DIAMOND),
            IconRowSpec("Group", "group", { it.group }, { m, v -> m.copy(group = v) }, d.GROUP),
            IconRowSpec("Bolt", "bolt", { it.bolt }, { m, v -> m.copy(bolt = v) }, d.BOLT),
            IconRowSpec("Health", "health", { it.health }, { m, v -> m.copy(health = v) }, d.HEALTH),
            IconRowSpec("Slow", "slow", { it.slow }, { m, v -> m.copy(slow = v) }, d.SLOW),
            IconRowSpec("Coffin", "coffin", { it.coffin }, { m, v -> m.copy(coffin = v) }, d.COFFIN),
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

    // 📍 Usage — opened by the per-row Usage icon. Layered as a
    // full-screen early-return (same pattern as the AI finder above)
    // so the save effects stay composed. Holds the row's label + the
    // MetadataIcons field name keyed into the source-derived index.
    var usageFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    usageFor?.let { (label, field) ->
        IconUsageScreen(label = label, field = field, onBack = { usageFor = null })
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
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
                        IconDefaultRow(
                            label = row.label, value = row.get(icons),
                            onChange = { icons = row.set(icons, it) },
                            onAiFind = { aiFindFor = IconAiTarget(row.label) { picked -> icons = row.set(icons, picked) } },
                            onUsage = { usageFor = row.label to row.field }
                        )
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
                Text(title, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
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
private fun IconDefaultRow(label: String, value: String, onChange: (String) -> Unit, onAiFind: () -> Unit, onUsage: () -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
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
        // 📍 Usage — open the source-derived list of where this icon is used.
        Text(
            "📍", fontSize = 20.sp,
            modifier = Modifier.clickable { onUsage() }.padding(start = 6.dp, end = 2.dp)
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

/** Source-derived "where is this icon used" screen. Reads the
 *  build-time [com.ai.data.IconUsageData] index keyed by the row's
 *  MetadataIcons [field] name. The list is a static snapshot of the
 *  `MetadataIconsHolder.current.<field>` references in the source — it
 *  is NOT recomputed at runtime, hence the disclaimer + generated
 *  timestamp at the top. */
@Composable
private fun IconUsageScreen(label: String, field: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val usages = remember(field) { com.ai.data.IconUsageData.forField(field) }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(title = "Usage: $label", subject = "Where “$field” is used in the source", onBackClick = onBack)
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "This list is derived from a static scan of the source code, not computed at runtime — it can drift as the code changes.",
                    fontSize = 12.sp, color = AppColors.TextSecondary
                )
                Text(
                    "Generated: ${com.ai.data.IconUsageData.GENERATED_AT}",
                    fontSize = 12.sp, color = AppColors.TextTertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (usages.isEmpty()) {
                Text(
                    "No source references found for this icon. It may only be a fallback/default, or referenced indirectly.",
                    fontSize = 13.sp, color = AppColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Text("${usages.size} reference${if (usages.size == 1) "" else "s"}",
                    fontSize = 12.sp, color = AppColors.TextTertiary)
                usages.forEach { line ->
                    Text(
                        line, fontSize = 12.sp, color = AppColors.TextPrimary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
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
                OutlinedButton(
                    onClick = { run() }, enabled = !running, modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
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
                        Text(com.ai.ui.shared.modelLabel(c.provider.id, c.model), fontSize = 13.sp, color = AppColors.TextPrimary,
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

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "settings_app_settings", title = "App settings", subject = "App-wide & report-model default prompt / parameters", onBackClick = onBack,
            onClear = {
                val d = GeneralSettings()
                appSp = d.appWideSystemPromptId
                appPar = d.appWideParametersIds
                rmSp = d.reportModelSystemPromptId
                rmPar = d.reportModelParametersIds
            })
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
        Text(label, fontSize = 14.sp, color = AppColors.TextPrimary, modifier = Modifier.width(110.dp))
        Text(
            selectedName ?: "Tap to select", fontSize = 13.sp,
            color = if (selectedName == null) AppColors.TextTertiary else AppColors.InfoAccent,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(">", fontSize = 16.sp, color = AppColors.InfoAccent)
    }
}

/** Diagnostic preferences gated behind a master switch (default off):
 *  API tracing, audit log, usage statistics and the application log
 *  level. All flow to background subsystems (ApiTracer / AuditLog /
 *  SettingsPreferences / AppLog) on save via the effective* gates; the
 *  change takes effect on the next traced call / audit write / API call
 *  / log line. While the master is off the four items are hidden and
 *  forced off at runtime; their stored values are preserved. */
@Composable
private fun LoggingAndTracingSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var loggingMasterEnabled by remember { mutableStateOf(generalSettings.loggingMasterEnabled) }
    var tracingEnabled by remember { mutableStateOf(generalSettings.tracingEnabled) }
    var auditLogEnabled by remember { mutableStateOf(generalSettings.auditLogEnabled) }
    var usageStatsEnabled by remember { mutableStateOf(generalSettings.usageStatsEnabled) }
    var logLevel by remember { mutableStateOf(generalSettings.logLevel) }

    fun build(): GeneralSettings = generalSettings.copy(
        loggingMasterEnabled = loggingMasterEnabled,
        tracingEnabled = tracingEnabled,
        auditLogEnabled = auditLogEnabled,
        usageStatsEnabled = usageStatsEnabled,
        logLevel = logLevel
    )

    LaunchedEffect(loggingMasterEnabled, tracingEnabled, auditLogEnabled, usageStatsEnabled, logLevel) {
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
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_logging", title = "Log/trace/audit/statistics", subject = "Tracing, audit log, usage statistics and log level", onBackClick = onBack,
            onClear = {
                val d = GeneralSettings()
                loggingMasterEnabled = d.loggingMasterEnabled
                tracingEnabled = d.tracingEnabled
                auditLogEnabled = d.auditLogEnabled
                usageStatsEnabled = d.usageStatsEnabled
                logLevel = d.logLevel
            })
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleSettingCard(
                title = "Enable logging & tracing",
                description = "Master switch for this page (default on). While off, nothing is recorded at runtime — no API tracing, no audit log, no usage statistics, and the file logger is disabled — and the four settings below stay hidden. Turn it on to record diagnostics and configure each one; their previous values come back as you left them.",
                icon = MetadataDefaults.APP_LOG,
                checked = loggingMasterEnabled,
                onCheckedChange = { loggingMasterEnabled = it }
            )
            if (loggingMasterEnabled) {
                ToggleSettingCard(
                    title = "API tracing",
                    description = "Record every API request and response. Turn off to hide the AI API Traces card and the ${com.ai.data.MetadataIconsHolder.current.traces} trace icons.",
                    icon = MetadataDefaults.TRACES,
                    checked = tracingEnabled,
                    onCheckedChange = { tracingEnabled = it }
                )
                ToggleSettingCard(
                    title = "Audit log",
                    description = "Record the per-report audit trail — every mutating action, batch start/end and API call, viewable under Monitor → Audit. Turn off to stop all audit writes.",
                    icon = "🧾",
                    checked = auditLogEnabled,
                    onCheckedChange = { auditLogEnabled = it }
                )
                ToggleSettingCard(
                    title = "Usage statistics",
                    description = "Accumulate per-provider / per-model token counts and costs on every API call — the figures behind AI Usage, the Statistics screen and the Live Dashboard rates. Turn off to stop all usage-stat recording; existing totals are kept until you clear them.",
                    icon = MetadataDefaults.CHART,
                    checked = usageStatsEnabled,
                    onCheckedChange = { usageStatsEnabled = it }
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
                Text(title, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
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
                Text(title, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
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
        Text(label, fontSize = 14.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 4.dp))
    }
}
