package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// General app settings
/** How combined provider+model labels render across UI rows.
 *  MODEL_ONLY shows just the model id (the dense default); PROVIDER_AND_MODEL
 *  shows both, joined by " · ", for users who run the same model on
 *  multiple providers and want to disambiguate at a glance. */
enum class ModelNameLayout { MODEL_ONLY, PROVIDER_AND_MODEL }

data class GeneralSettings(
    val userName: String = "user",
    val huggingFaceApiKey: String = "",
    val openRouterApiKey: String = "",
    /** Free-tier API key for artificialanalysis.ai/api/v2/data/llms/models —
     *  empty until the user pastes one in External Services. The Refresh
     *  screen disables the AA button while this is blank. */
    val artificialAnalysisApiKey: String = "",
    val defaultEmail: String = "",
    /** Default API path per model type. Used when a provider doesn't declare a
     *  per-type override in its typePaths. Falls back to ModelType.DEFAULT_PATHS
     *  when this map is empty for a given type. */
    val defaultTypePaths: Map<String, String> = emptyMap(),
    /** Master switch for API tracing. When false, no new traces are
     *  written, the Hub "AI API Traces" card is hidden, and every 🐞
     *  ladybug icon disappears from the per-result screens. Mirrored
     *  to [com.ai.data.ApiTracer.isTracingEnabled] so non-UI call
     *  sites consult a single global. */
    val tracingEnabled: Boolean = true,
    /** Controls whether combined provider+model labels (Fan out drill-in
     *  rows, secondary picker buttons, agent rows on Report Result,
     *  chat headers, …) show only the model or both. Provided to the
     *  composition tree via LocalModelNameLayout in the AppNavHost. */
    val modelNameLayout: ModelNameLayout = ModelNameLayout.MODEL_ONLY,
    /** When false the visible "< Back" button on every TitleBar is
     *  hidden and the screen title slides to the left edge. The
     *  system / gesture back still works because TitleBar's
     *  BackHandler is registered independently of the button.
     *  Provided to the composition tree via LocalShowBackButton. */
    val showBackButton: Boolean = true,
    /** Compact-header mode. When true, every screen that today shows
     *  a fixed TitleBar label plus a green "subject" sub-header below
     *  it (Model Info, Trace detail, Knowledge base, Translation
     *  run, Agent result, …) folds the dynamic subject into the
     *  title bar and drops the green line. Default false preserves
     *  the legacy two-row layout. Provided via LocalSubjectToTitleBar. */
    val subjectToTitleBar: Boolean = false,
    /** Whether the AI Knowledge card appears on the home Hub. Default
     *  false — Knowledge / RAG is an advanced flow that most users
     *  don't need; hiding it on a fresh install keeps the Hub
     *  approachable. Surfaces a toggle under Settings → "Show AI
     *  Knowledge card on home page" once a user wants it. The
     *  Knowledge subsystem itself stays fully functional whether or
     *  not the card is visible — KBs attached to a chat / report
     *  still work, share-target Knowledge ingest still works. */
    val showKnowledgeCard: Boolean = false
)

// Prompt history entry
data class PromptHistoryEntry(
    val timestamp: Long,
    val title: String,
    val prompt: String
)

/** Captured failure from a Fetch-models call. The trace filename, if any,
 *  points at the request/response pair recorded by [com.ai.data.ApiTracer]
 *  during this attempt — the model picker renders a 🐞 next to the error
 *  text that deep-links into the Trace screen for that exact call. */
data class FetchModelsError(val message: String, val traceFile: String?)

// Main UI state
data class UiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val generalSettings: GeneralSettings = GeneralSettings(),
    val aiSettings: Settings = Settings(),
    val loadingModelsFor: Set<AppService> = emptySet(),
    /** Most recent Fetch-models failure per provider (keyed by service.id).
     *  Cleared when the next Fetch-models call for that provider starts;
     *  populated from the catch arm of [AppViewModel.fetchModels]. The
     *  model-picker UI consults this to render an inline error + 🐞 link
     *  to the captured trace. */
    val fetchModelsErrors: Map<String, FetchModelsError> = emptyMap(),
    // Reports
    val showGenericAgentSelection: Boolean = false,
    val showGenericReportsDialog: Boolean = false,
    val genericPromptTitle: String = "",
    val genericPromptText: String = "",
    // Optional image attachment for the next report — set on NewReportScreen
    // by the 📎 button, consumed once by generateGenericReports, then cleared.
    val reportImageBase64: String? = null,
    val reportImageMime: String? = null,
    // Per-report web-search toggle. ORs with each agent's pinned default; the
    // dispatch layer also auto-falls-back to no-tool on a 400 tool-rejection.
    val reportWebSearchTool: Boolean = false,
    // Per-report reasoning-effort hint (low / medium / high). Set on the
    // New AI Report screen by the 🧠 pulldown, applied on top of any
    // preset reasoningEffort. Non-reasoning models silently ignore the
    // field at dispatch time.
    val reportReasoningEffort: String? = null,
    /** Knowledge bases attached to the next report run. The selection
     *  screen toggles entries here; ReportViewModel.generateGenericReports
     *  copies the snapshot onto the new Report's knowledgeBaseIds.
     *  AnalysisRepository.analyzeWithAgent reads it via the per-call
     *  Report it loads to inject the context block. */
    val attachedKnowledgeBaseIds: List<String> = emptyList(),
    /** Initial user-input text staged by the share-target chooser
     *  so a freshly-opened chat session pre-fills its input box.
     *  ChatSessionScreen consumes this once on first composition
     *  and clears it via clearChatStarterText() so navigating
     *  away and back doesn't re-stuff the box. */
    val chatStarterText: String? = null,
    /** Initial vision attachment staged by the AI Chat hub's
     *  "📸 Start with photo" entry (and any future flow that wants
     *  to drop a chat session in with an image already attached).
     *  Consumed by ChatSessionScreen on first composition the same
     *  way chatStarterText is. */
    val chatStarterImageBase64: String? = null,
    val chatStarterImageMime: String? = null,
    /** SAF Uri strings staged for ingestion by the AI Knowledge
     *  screen. The list screen drops them into the active KB
     *  after the user picks one (or creates a new KB) and clears
     *  the queue. */
    val pendingKnowledgeUris: List<String> = emptyList(),
    /** Non-image SAF Uri strings staged by the share-target
     *  chooser when the user picked "New Report". The New Report
     *  screen surfaces a banner that lets the user auto-create a
     *  one-shot knowledge base from these files and attach it to
     *  the report being composed. Drained on attach / skip. */
    val pendingReportKnowledgeUris: List<String> = emptyList(),
    val genericReportsProgress: Int = 0,
    val genericReportsTotal: Int = 0,
    val genericReportsSelectedAgents: Set<String> = emptySet(),
    val currentReportId: String? = null,
    val reportAdvancedParameters: AgentParameters? = null,
    // One-shot signal: when non-empty, the Reports selection screen pre-fills its model
    // list from this and clears it via clearPendingReportModels(). Used for Edit-models
    // and Regenerate flows kicked off from a finished report.
    val pendingReportModels: List<ReportModel> = emptyList(),
    // When non-null, the Reports selection screen is in "edit mode" for that reportId —
    // the bottom button reads "Update model list" and only stages the new model list
    // instead of running. Set by ReportViewModel.prepareEditModels and cleared once
    // the user taps Update or backs out.
    val editModeReportId: String? = null,
    // Model list staged by the Edit-models flow. When non-empty, regenerateReport uses
    // it instead of rebuilding from the on-disk report.agents.
    val stagedReportModels: List<ReportModel> = emptyList(),
    // Set when Edit / Prompt or Edit / Parameters changes the saved title/prompt or
    // reportAdvancedParameters after a report has finished, so the Result screen can
    // surface a "changes pending" banner. Both flags are cleared when regenerateReport
    // kicks off the new run, or when the report is dismissed.
    val hasPendingPromptChange: Boolean = false,
    val hasPendingParametersChange: Boolean = false,
    val externalIntent: ExternalIntent = ExternalIntent(),
    // Number of Rerank/Summarize/Compare batches currently running.
    // Each runSecondary() launch increments this on entry and decrements
    // on completion; multiple batches can be in flight at once. The
    // Meta button's hourglass and the Meta screen's poll loop key off
    // this being > 0.
    val activeSecondaryBatches: Int = 0,
    // Chat
    val chatParameters: ChatParameters = ChatParameters(),
    val dualChatConfig: DualChatConfig? = null
) {
    // Flat accessors preserved so call sites don't need updating. Grouping the 13 external
    // fields into a nested ExternalIntent struct makes "is anything external set?" checks
    // and reset operations trivial, and shrinks the top-level UiState surface.
    val externalSystemPrompt: String? get() = externalIntent.systemPrompt
    val externalCloseHtml: String? get() = externalIntent.closeHtml
    val externalReportType: String? get() = externalIntent.reportType
    val externalEmail: String? get() = externalIntent.email
    val externalNextAction: String? get() = externalIntent.nextAction
    val externalReturn: Boolean get() = externalIntent.returnAfterNext
    val externalEdit: Boolean get() = externalIntent.edit
    val externalSelect: Boolean get() = externalIntent.select
    val externalOpenHtml: String? get() = externalIntent.openHtml
    val externalAgentNames: List<String> get() = externalIntent.agentNames
    val externalFlockNames: List<String> get() = externalIntent.flockNames
    val externalSwarmNames: List<String> get() = externalIntent.swarmNames
    val externalModelSpecs: List<String> get() = externalIntent.modelSpecs
}

data class ExternalIntent(
    val systemPrompt: String? = null,
    val closeHtml: String? = null,
    val reportType: String? = null,
    val email: String? = null,
    val nextAction: String? = null,
    val returnAfterNext: Boolean = false,
    val edit: Boolean = false,
    val select: Boolean = false,
    val openHtml: String? = null,
    val agentNames: List<String> = emptyList(),
    val flockNames: List<String> = emptyList(),
    val swarmNames: List<String> = emptyList(),
    val modelSpecs: List<String> = emptyList()
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    internal val repository = AnalysisRepository()
    internal val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    internal val settingsPrefs = SettingsPreferences(prefs, application.filesDir)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Hot-mutating fan-out pair set lives outside UiState so per-task
    // start/finish updates (5–15 Hz during a Fan out batch) don't
    // recompose every consumer that reads any other UiState field.
    // Subscribers that actually care (the Fan out L1 / L2 / L3 screens,
    // the Run-button hourglass) collect this flow directly.
    private val _runningFanOutPairs = MutableStateFlow<Set<String>>(emptySet())
    val runningFanOutPairs: StateFlow<Set<String>> = _runningFanOutPairs.asStateFlow()
    internal fun updateRunningFanOutPairs(block: (Set<String>) -> Set<String>) {
        _runningFanOutPairs.update(block)
    }

    init {
        // Tracing default is true; the bootstrap below overrides it with
        // the persisted GeneralSettings.tracingEnabled. Setting it here as
        // well keeps any pre-bootstrap call (e.g. PricingCache.preloadAsync
        // on the same launch) consistent with the user's last choice
        // rather than always recording.
        ApiTracer.isTracingEnabled = true
        // Warm the trace-file cache off the main thread so the first
        // UI-side getTraceFiles() (Trace screen open, agent test 🐞
        // lookup, fan-out 🐞 lookup) doesn't pay the streaming-parse
        // cost across the whole trace dir.
        ApiTracer.prewarmCache(viewModelScope)
        PricingCache.preloadAsync(application, viewModelScope)
        viewModelScope.launch(Dispatchers.IO) {
            val bs = bootstrap(application)
            // Publish user-supplied default type paths to the global resolver so dispatch
            // (which doesn't see GeneralSettings directly) can fall back through them.
            ModelType.userDefaults = bs.first.defaultTypePaths
            ApiTracer.isTracingEnabled = bs.first.tracingEnabled
            _uiState.update { it.copy(generalSettings = bs.first, aiSettings = bs.second) }
            refreshAllModelLists(bs.second)
        }
        // Mirror the latest aiSettings to a static holder so the
        // dispatcher helpers (which can't easily thread Settings
        // through their call stack) can consult capability lookups
        // like Settings.isReasoningCapable. Updated on every uiState
        // emission — the cost is one volatile write per state change.
        viewModelScope.launch {
            uiState.collect { SettingsHolder.current = it.aiSettings }
        }
    }

    override fun onCleared() {
        // Flush off the main thread — flushUsageStats does a
        // SharedPreferences commit which blocks on disk I/O. Use
        // GlobalScope + NonCancellable so the work survives the
        // ViewModel's scope cancellation that's already in flight.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            runCatching { settingsPrefs.flushUsageStats() }
        }
        // viewModelScope cancellation runs every active flow's
        // withTracerTags finally on the way out — that restores the
        // previous (reportId, category). No manual clear needed.
        super.onCleared()
    }

    private suspend fun bootstrap(application: Application): Pair<GeneralSettings, Settings> {
        ApiTracer.init(application)
        ChatHistoryManager.init(application)
        ReportStorage.init(application)
        SecondaryResultStorage.init(application)
        ProviderRegistry.init(application)
        PromptCache.init(application)

        val gs = settingsPrefs.loadGeneralSettings()
        var ai = settingsPrefs.loadSettings()

        // First-run seeding from bundled assets. Flag wiped on data
        // clear / reinstall (which is exactly when we want to seed
        // again); persists across APK upgrades.
        if (!prefs.getBoolean(KEY_FIRST_RUN_BOOTSTRAPPED, false)) {
            val isEmptyInstall = ProviderRegistry.getAll().isEmpty() && ai.internalPrompts.isEmpty()
            if (isEmptyInstall) {
                val providersAdded = ProviderRegistry.importFromAsset(application, "providers.json")
                if (providersAdded < 0) {
                    android.util.Log.w("AppViewModel", "First-run providers.json import failed")
                }
                val bundled = com.ai.data.InternalPromptSeed.loadFromAssets(application)
                if (bundled.isNotEmpty()) {
                    val merged = com.ai.data.InternalPromptSeed.ensureAllPresent(ai.internalPrompts, bundled)
                    if (merged.size != ai.internalPrompts.size) {
                        ai = ai.copy(internalPrompts = merged)
                    }
                }
            }
            prefs.edit().putBoolean(KEY_FIRST_RUN_BOOTSTRAPPED, true).apply()
        }

        return gs to ai
    }

    /** On-demand merge of the prompts declared in `assets/prompts.json`
     *  into [Settings.internalPrompts]. Existing rows are left strictly
     *  alone (no overwrites); only names not yet present are appended.
     *  Returns the count of newly added prompts so the caller can show
     *  feedback ("3 new prompts added", "all prompts already present"). */
    fun loadBundledInternalPrompts(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.InternalPromptSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        val merged = com.ai.data.InternalPromptSeed.ensureAllPresent(current.internalPrompts, bundled)
        val added = merged.size - current.internalPrompts.size
        if (added > 0) updateSettings(current.copy(internalPrompts = merged))
        return added
    }

    /** Drop every Internal prompt and replace the list with a fresh
     *  load of `assets/prompts.json`. Returns the number of rows loaded
     *  (0 if the asset is missing or fails to parse, in which case the
     *  existing list is left untouched). */
    fun resetInternalPromptsFromAssets(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.InternalPromptSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        updateSettings(current.copy(internalPrompts = bundled))
        return bundled.size
    }

    /** On-demand merge of the prompts declared in `assets/examples.json`
     *  into [Settings.examplePrompts]. Existing rows (matched by title,
     *  case-insensitive) are left alone; only new titles are appended.
     *  Returns the count of newly added prompts. */
    fun loadBundledExamplePrompts(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.ExamplePromptSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        val merged = com.ai.data.ExamplePromptSeed.ensureAllPresent(current.examplePrompts, bundled)
        val added = merged.size - current.examplePrompts.size
        if (added > 0) updateSettings(current.copy(examplePrompts = merged))
        return added
    }

    // ===== Housekeeping primitives =====
    //
    // Each Housekeeping → Reset card button (Clear Usage Statistics,
    // Clear all runtime data, Clear all configuration) and the
    // resetApplication() orchestrator route through these helpers, so
    // the wipe sets stay in lockstep when one is later extended.

    data class RuntimeWipeResult(val reports: Int, val chats: Int, val knowledgeBases: Int)
    data class ConfigWipeResult(val localLlms: Int, val embedders: Int)

    fun clearUsageStatistics() {
        settingsPrefs.clearUsageStats()
    }

    fun clearAllRuntimeData(context: Context): RuntimeWipeResult {
        val reports = ReportStorage.getAllReports(context).also { list ->
            list.forEach { ReportStorage.deleteReport(context, it.id) }
        }
        val chats = ChatHistoryManager.deleteAllSessions()
        ApiTracer.clearTraces()
        PromptCache.clearAll()
        settingsPrefs.clearPromptHistory()
        settingsPrefs.clearLastReportPrompt()
        val kbs = KnowledgeStore.clearAll(context)
        PricingCache.clearAll(context)
        ModelListCache.clearAll(context)
        EmbeddingsStore.clearAll(context)
        return RuntimeWipeResult(reports.size, chats, kbs)
    }

    fun clearAllConfiguration(context: Context): ConfigWipeResult {
        updateSettings(Settings())
        updateGeneralSettings(GeneralSettings())
        val llms = LocalLlm.clearAll(context)
        val embedders = LocalEmbedder.clearAll(context)
        return ConfigWipeResult(llms, embedders)
    }

    /** Factory-style reset that preserves API keys. Runs the cascade
     *  documented in the Reset application dialog: snapshot the keys
     *  to a temp file in cacheDir, wipe usage stats / runtime data /
     *  provider registry, reload providers.json + prompts.json from
     *  assets, clear configuration (so Settings() is built against
     *  the freshly loaded registry), re-import the keys, and finally
     *  run the same Refresh-all chain the Refresh screen exposes so
     *  the freshly-reset app starts with current catalogs, verified
     *  provider keys, model lists, and default agents. The temp file
     *  is deleted in finally so a mid-cascade crash can't strand the
     *  keys on disk. */
    fun resetApplication(context: Context, onComplete: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tempFile = java.io.File(context.cacheDir, "reset_keys_${System.currentTimeMillis()}.json")
            val outcome = runCatching {
                // 1. Export keys to temp file
                val snap = _uiState.value
                val keysJson = com.ai.ui.settings.buildApiKeysJson(
                    snap.aiSettings,
                    snap.generalSettings.huggingFaceApiKey,
                    snap.generalSettings.openRouterApiKey,
                    snap.generalSettings.artificialAnalysisApiKey
                )
                tempFile.writeText(keysJson)

                // 2. Clear usage stats
                clearUsageStatistics()
                // 3. Clear runtime data
                clearAllRuntimeData(context)
                // 4. Wipe provider registry
                ProviderRegistry.resetToDefaults(context)
                // 5. Reload providers.json from assets
                val providersAdded = ProviderRegistry.importFromAsset(context, "providers.json")
                if (providersAdded < 0) {
                    android.util.Log.w("AppViewModel", "providers.json reload failed during reset")
                }
                // 6. Clear configuration (Settings() now keyed against fresh registry)
                clearAllConfiguration(context)
                // Persist the reset Settings synchronously before the
                // import step reads _uiState — updateSettings's IO save
                // is fire-and-forget but the StateFlow update is sync.
                // 7. Reload prompts.json from assets
                loadBundledInternalPrompts()
                // 8. Re-import keys from temp file
                val readBack = tempFile.readText()
                val result = com.ai.ui.settings.applyApiKeysJson(readBack, _uiState.value.aiSettings)
                if (result != null) {
                    var gs = _uiState.value.generalSettings
                    result.huggingFaceApiKey?.let { gs = gs.copy(huggingFaceApiKey = it) }
                    result.openRouterApiKey?.let { gs = gs.copy(openRouterApiKey = it) }
                    result.artificialAnalysisApiKey?.let { gs = gs.copy(artificialAnalysisApiKey = it) }
                    if (gs != _uiState.value.generalSettings) updateGeneralSettings(gs)
                    updateSettings(result.settings)
                    result.imported
                } else 0
            }
            // 9. Always delete the temp keys file
            runCatching { tempFile.delete() }

            // 10. Refresh all — mirrors the Refresh screen's "Refresh all"
            // chain so the freshly-reset app starts with up-to-date catalogs,
            // verified provider keys, current model lists, and a default
            // agents flock. Best-effort: a failure here doesn't roll back
            // the reset, since the keys-restored state is already useful.
            if (outcome.isSuccess) {
                runCatching { performRefreshAllCascade(context) }
            }

            withContext(Dispatchers.Main) {
                outcome.fold(
                    onSuccess = { count ->
                        onComplete(true, "Reset complete — $count API keys restored")
                    },
                    onFailure = { ex ->
                        android.util.Log.e("AppViewModel", "resetApplication failed", ex)
                        onComplete(false, "Reset failed: ${ex.javaClass.simpleName}: ${ex.message}")
                    }
                )
            }
        }
    }

    /** Headless equivalent of the Refresh screen's "Refresh all" chain.
     *  Fetches every pricing/capability catalog, tests each provider's
     *  API key, refreshes per-provider model lists, then rebuilds the
     *  "default agents" flock. Each catalog fetch is wrapped so a single
     *  network failure can't abort the whole cascade. Used by
     *  [resetApplication] as the final step. */
    private suspend fun performRefreshAllCascade(context: Context) {
        val openRouterKey = _uiState.value.generalSettings.openRouterApiKey
        val aaKey = _uiState.value.generalSettings.artificialAnalysisApiKey

        if (openRouterKey.isNotBlank()) {
            runCatching {
                val pricing = PricingCache.fetchOpenRouterPricing(openRouterKey)
                if (pricing.isNotEmpty()) PricingCache.saveOpenRouterPricing(context, pricing)
                PricingCache.fetchAndSaveModelSpecifications(context, openRouterKey)
            }
        }
        runCatching {
            val n = PricingCache.fetchLiteLLMPricingOnline(context)
            if (n != null) updateSettings(_uiState.value.aiSettings.recomputeAllCapabilities())
        }
        runCatching {
            val n = PricingCache.fetchModelsDevOnline(context)
            if (n != null) updateSettings(_uiState.value.aiSettings.recomputeAllCapabilities())
        }
        runCatching { PricingCache.fetchHeliconeOnline(context) }
        runCatching { PricingCache.fetchLLMPricesOnline(context) }
        if (aaKey.isNotBlank()) {
            runCatching { PricingCache.fetchArtificialAnalysisOnline(context, aaKey) }
        }

        val testSnap = _uiState.value.aiSettings
        coroutineScope {
            AppService.entries
                .filter { service ->
                    testSnap.getProviderState(service) != "inactive" &&
                        testSnap.getApiKey(service).isNotBlank()
                }
                .map { service ->
                    async(Dispatchers.IO) {
                        val apiKey = _uiState.value.aiSettings.getApiKey(service)
                        val model = _uiState.value.aiSettings.getModel(service)
                        val error = try { testAiModel(service, apiKey, model) } catch (e: Exception) { e.message ?: "error" }
                        val newState = if (error == null) "ok" else "error"
                        withContext(Dispatchers.Main) { updateProviderState(service, newState) }
                    }
                }
                .awaitAll()
        }

        runCatching { refreshAllModelLists(_uiState.value.aiSettings, forceRefresh = true) }

        val current = _uiState.value.aiSettings
        val candidates = current.getActiveServices().map { s ->
            Triple(s, current.getApiKey(s), current.getModel(s))
        }
        val results: List<Pair<AppService, Boolean>> = coroutineScope {
            candidates.map { (service, key, model) ->
                async(Dispatchers.IO) {
                    val error = try { testAiModel(service, key, model) } catch (e: Exception) { e.message ?: "error" }
                    service to (error == null)
                }
            }.awaitAll()
        }
        var updatedSettings = _uiState.value.aiSettings
        for ((service, _, model) in candidates) {
            val success = results.find { it.first == service }?.second == true
            if (success) {
                val existing = updatedSettings.agents.find { it.name == service.id && it.provider.id == service.id }
                if (existing == null) {
                    val agent = Agent(java.util.UUID.randomUUID().toString(), service.id, service, model, "")
                    updatedSettings = updatedSettings.copy(agents = updatedSettings.agents + agent)
                }
            }
        }
        val defaultAgentIds = updatedSettings.agents
            .filter { a -> results.any { it.first == a.provider && it.second } }
            .map { it.id }
        val existingFlock = updatedSettings.flocks.find { it.name == DEFAULT_AGENTS_FLOCK_NAME }
        updatedSettings = if (existingFlock != null) {
            updatedSettings.copy(flocks = updatedSettings.flocks.map {
                if (it.id == existingFlock.id) it.copy(agentIds = defaultAgentIds) else it
            })
        } else {
            val flock = Flock(java.util.UUID.randomUUID().toString(), DEFAULT_AGENTS_FLOCK_NAME, defaultAgentIds)
            updatedSettings.copy(flocks = updatedSettings.flocks + flock)
        }
        updateSettings(updatedSettings)
    }

    // ===== Settings =====

    fun updateGeneralSettings(settings: GeneralSettings) {
        ModelType.userDefaults = settings.defaultTypePaths
        ApiTracer.isTracingEnabled = settings.tracingEnabled
        _uiState.update { it.copy(generalSettings = settings) }
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveGeneralSettings(settings) }
    }

    fun updateSettings(settings: Settings) {
        _uiState.update { it.copy(aiSettings = settings) }
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(settings) }
    }

    fun updateProviderState(service: AppService, state: String) {
        // Compute the delta inside the StateFlow.update CAS lambda so
        // concurrent calls (e.g. the parallel provider-test sweep
        // inside Refresh All) each apply their change to the latest
        // snapshot — capturing _uiState.value once at function entry
        // and then writing back the closed-over local clobbered every
        // peer's update, leaving the surface bug "Refresh All reported
        // 2 failures but only 1 red cross in AI Setup".
        _uiState.update { current ->
            var updated = current.aiSettings.withProviderState(service, state)
            // When a provider goes inactive, drop its default agent (the one named after the
            // provider's displayName) so flocks/swarms don't keep referencing a disabled path.
            if (state == "inactive") {
                val pruned = updated.agents.filterNot { it.provider.id == service.id && it.name == service.id }
                if (pruned.size != updated.agents.size) {
                    val droppedIds = updated.agents.filter { it !in pruned }.map { it.id }.toSet()
                    val flocks = updated.flocks.map { f -> f.copy(agentIds = f.agentIds.filterNot { it in droppedIds }) }
                    updated = updated.copy(agents = pruned, flocks = flocks)
                }
            }
            current.copy(aiSettings = updated)
        }
        // Save the latest post-update snapshot — picks up any peer
        // updates that landed in the same window. Last writer wins on
        // the persistence layer too, but every concurrent caller's
        // change is in the snapshot we save.
        val final = _uiState.value.aiSettings
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(final) }
    }

    /** Called by the per-provider Test button when the test passes:
     *  flips the provider state to "ok" AND ensures there's a row for
     *  the provider in the "default agents" flock (creating the agent
     *  + flock if needed). Both edits are applied to the same Settings
     *  copy so the StateFlow update lands atomically and a single save
     *  flushes the result to disk.
     *
     *  Also kicks off a background `/v1/models` fetch — when that
     *  succeeds the provider's `modelSource` flips to API and the
     *  fetched ids replace the manual list. A failed fetch leaves
     *  modelSource untouched (the test itself already passed, so the
     *  provider stays "ok" either way). */
    /** Called by the per-provider settings screen after the user picks
     *  a new default model and the API-key test succeeds. Drops every
     *  agent named after the provider's displayName (and prunes those
     *  ids from every flock), then recreates a fresh default agent
     *  pointing at [defaultModel] and adds it back to the
     *  "default agents" flock. */
    fun replaceDefaultAgent(service: AppService, defaultModel: String) {
        // CAS-style update so a concurrent updateProviderState /
        // markProviderTestedOk call doesn't get clobbered by the
        // closed-over local-snapshot pattern.
        _uiState.update { current ->
            val droppedIds = current.aiSettings.agents
                .filter { it.provider.id == service.id && it.name == service.id }
                .map { it.id }.toSet()
            val pruned = current.aiSettings.copy(
                agents = current.aiSettings.agents.filterNot { it.id in droppedIds },
                flocks = current.aiSettings.flocks.map { f -> f.copy(agentIds = f.agentIds.filterNot { it in droppedIds }) }
            )
            current.copy(aiSettings = pruned.ensureDefaultAgentInFlock(service, defaultModel))
        }
        val final = _uiState.value.aiSettings
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(final) }
    }

    fun markProviderTestedOk(service: AppService, defaultModel: String, fetchAfter: Boolean = true) {
        // CAS-style update — see updateProviderState for the same
        // race that the closed-over snapshot pattern caused.
        _uiState.update { current ->
            val updated = current.aiSettings
                .withProviderState(service, "ok")
                .ensureDefaultAgentInFlock(service, defaultModel)
            current.copy(aiSettings = updated)
        }
        val final = _uiState.value.aiSettings
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(final) }
        // Background model-list fetch with API-source flip on success.
        // The activation flow pre-fetches synchronously and passes
        // [fetchAfter] = false to avoid a duplicate request.
        if (fetchAfter) {
            fetchModels(service, _uiState.value.aiSettings.getApiKey(service), flipToApiOnSuccess = true)
        }
    }

    fun clearTraces() = ApiTracer.clearTraces()

    // ===== Report Agents/Models Selection =====

    // SharedPreferences docs forbid mutating the set returned by getStringSet AND the set passed
    // to putStringSet; we defensively copy on both sides so aliasing can't corrupt stored state.
    fun loadReportAgents(): Set<String> = prefs.getStringSet(AI_REPORT_AGENTS_KEY, emptySet())?.toHashSet() ?: emptySet()
    fun saveReportAgents(agentIds: Set<String>) { prefs.edit { putStringSet(AI_REPORT_AGENTS_KEY, agentIds.toHashSet()) } }
    fun loadReportModels(): Set<String> = prefs.getStringSet(AI_REPORT_MODELS_KEY, emptySet())?.toHashSet() ?: emptySet()
    fun saveReportModels(modelIds: Set<String>) { prefs.edit { putStringSet(AI_REPORT_MODELS_KEY, modelIds.toHashSet()) } }

    // ===== Model Fetching =====

    fun fetchModels(service: AppService, apiKey: String, flipToApiOnSuccess: Boolean = false) {
        viewModelScope.launch { fetchModelsAwait(service, apiKey, flipToApiOnSuccess) }
    }

    /** Suspend variant of [fetchModels]. Returns null on success or an
     *  error message on failure. Used by the provider-activation flow,
     *  which needs to await the result before deciding whether to test
     *  the API key and create the default agent. */
    suspend fun fetchModelsAwait(service: AppService, apiKey: String, flipToApiOnSuccess: Boolean = false): String? {
        return withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            _uiState.update { it.copy(
                loadingModelsFor = it.loadingModelsFor + service,
                fetchModelsErrors = it.fetchModelsErrors - service.id
            ) }
            try {
                val fetched = repository.fetchModelsWithKinds(service, apiKey)
                // Persist the raw /models response to disk under
                // files/model_lists/<id>.json for later
                // pricing/capability lookups. Done before the in-memory
                // settings update so a subsequent crash still leaves a
                // valid snapshot on disk.
                ModelListCache.save(getApplication(), service, fetched.rawResponse)
                // Together AI ships per-model pricing inside the
                // /v1/models response itself; the dispatcher harvests
                // it into FetchedModels.nativePricing and we pump it
                // straight into the TOGETHER pricing tier here so a
                // model-list refresh doubles as a pricing refresh.
                if (fetched.nativePricing.isNotEmpty()) {
                    com.ai.data.PricingCache.saveTogetherPricing(getApplication(), fetched.nativePricing)
                }
                _uiState.update { state ->
                    val withSelf = state.aiSettings.withModels(service, fetched.ids, fetched.types, fetched.visionModels, fetched.capabilities, fetched.rawResponse)
                    // When the caller asked for an API-source flip
                    // (per-provider Test button), apply it in the same
                    // state update so model list + source land atomically.
                    val withSource = if (flipToApiOnSuccess && fetched.ids.isNotEmpty()) {
                        val cfg = withSelf.getProvider(service)
                        if (cfg.modelSource != ModelSource.API)
                            withSelf.withProvider(service, cfg.copy(modelSource = ModelSource.API))
                        else withSelf
                    } else withSelf
                    // Fan out-pollinate OpenRouter labels — covers two flows:
                    //   • non-OpenRouter fetch picks up labels OpenRouter already has cached
                    //   • OpenRouter fetch propagates fresh labels to every other provider
                    state.copy(aiSettings = withSource.applyOpenRouterTypes(), loadingModelsFor = state.loadingModelsFor - service)
                }
                val final = _uiState.value.aiSettings
                val cfgSelf = final.getProvider(service)
                settingsPrefs.saveModelsForProvider(service, fetched.ids, cfgSelf.modelTypes, cfgSelf.visionModels, cfgSelf.modelCapabilities, cfgSelf.modelListRawJson)
                // saveModelsForProvider only writes the per-key model
                // set. The modelSource lives in the main aiSettings JSON
                // and needs a saveSettings to be persisted across
                // restarts, so flush it here when the flip happened.
                if (flipToApiOnSuccess && fetched.ids.isNotEmpty() && cfgSelf.modelSource == ModelSource.API) {
                    settingsPrefs.saveSettings(final)
                }
                if (service.crossProviderModelList) {
                    // Persist the freshly fan out-applied labels for every other provider.
                    AppService.entries.filter { it.id != service.id }.forEach { other ->
                        val cfg = final.getProvider(other)
                        if (cfg.models.isNotEmpty()) settingsPrefs.saveModelsForProvider(other, cfg.models, cfg.modelTypes, cfg.visionModels, cfg.modelCapabilities)
                    }
                }
                null
            } catch (e: Exception) {
                android.util.Log.w("AppViewModel", "Failed to fetch models for ${service.id}: ${e.message}")
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                // Match the trace bracketed by withTraceCategory("Retrieve
                // models list") in ApiDispatch.fetchModelsWithKinds. Filtering
                // by category as well as timestamp keeps concurrent fetches
                // from clobbering each other's pointers.
                val traceFile = if (ApiTracer.isTracingEnabled) {
                    // Multiple parallel fetches all share the
                    // "Retrieve models list" category, so the
                    // category filter alone could pick a different
                    // provider's trace. Match on hostname too —
                    // the AppService.baseUrl host is the most
                    // specific identity we have.
                    val providerHost = runCatching {
                        java.net.URI(service.baseUrl).host?.lowercase()
                    }.getOrNull()
                    ApiTracer.getTraceFiles()
                        .firstOrNull {
                            it.timestamp >= startedAt &&
                                it.category == "Retrieve models list" &&
                                (providerHost == null || it.hostname.equals(providerHost, ignoreCase = true))
                        }
                        ?.filename
                } else null
                _uiState.update { it.copy(
                    loadingModelsFor = it.loadingModelsFor - service,
                    fetchModelsErrors = it.fetchModelsErrors + (service.id to FetchModelsError(msg, traceFile))
                ) }
                msg
            }
        }
    }

    suspend fun refreshAllModelLists(settings: Settings, forceRefresh: Boolean = false, onProgress: ((String) -> Unit)? = null): Map<String, Int> {
        return withContext(Dispatchers.IO) {
            val toRefresh = AppService.entries.filter { service ->
                settings.getProviderState(service) != "inactive" &&
                    settings.getModelSource(service) == ModelSource.API &&
                    settings.getApiKey(service).isNotBlank() &&
                    (forceRefresh || !settingsPrefs.isModelListCacheValid(service))
            }
            if (toRefresh.isEmpty()) return@withContext emptyMap()

            val results = coroutineScope {
                toRefresh.map { service ->
                    async {
                        onProgress?.invoke(service.id)
                        // Clear any previous error for this provider — the
                        // try below either succeeds (no error needed) or
                        // catches and re-stamps a fresh one.
                        _uiState.update { it.copy(fetchModelsErrors = it.fetchModelsErrors - service.id) }
                        try {
                            val fetched = repository.fetchModelsWithKinds(service, settings.getApiKey(service))
                            // Disk-cache the raw response for later
                            // pricing / capability lookups (see
                            // ModelListCache).
                            ModelListCache.save(getApplication(), service, fetched.rawResponse)
                            _uiState.update { state -> state.copy(aiSettings = state.aiSettings.withModels(service, fetched.ids, fetched.types, fetched.visionModels, fetched.capabilities, fetched.rawResponse)) }
                            // Persist with the freshly-merged visionModels (auto + user override), capability map, and raw response snapshot.
                            val cfg = _uiState.value.aiSettings.getProvider(service)
                            settingsPrefs.saveModelsForProvider(service, fetched.ids, fetched.types, cfg.visionModels, cfg.modelCapabilities, cfg.modelListRawJson)
                            service to fetched.ids.size
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Surface the failure on UiState.fetchModelsErrors
                            // so the model picker can show "fetch failed"
                            // instead of presenting a stale catalog as if
                            // it were fresh. The per-provider models on
                            // disk are preserved (we never called
                            // saveModelsForProvider for this provider).
                            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                            _uiState.update {
                                it.copy(fetchModelsErrors = it.fetchModelsErrors + (service.id to FetchModelsError(msg, null)))
                            }
                            service to -1
                        }
                    }
                }.awaitAll()
            }

            val successful = results.filter { it.second > 0 }.map { it.first }
            if (successful.isNotEmpty()) settingsPrefs.updateModelListTimestamps(successful)
            // Final fan out-lookup pass — guarantees that whichever order the parallel
            // fetches finished in, OpenRouter's labels end up applied everywhere.
            _uiState.update { state -> state.copy(aiSettings = state.aiSettings.applyOpenRouterTypes()) }
            val final = _uiState.value.aiSettings
            successful.forEach { service ->
                val cfg = final.getProvider(service)
                if (cfg.models.isNotEmpty()) settingsPrefs.saveModelsForProvider(service, cfg.models, cfg.modelTypes, cfg.visionModels, cfg.modelCapabilities)
            }
            results.associate { it.first.id to it.second }
        }
    }

    // ===== Model Testing =====

    suspend fun testAiModel(service: AppService, apiKey: String, model: String): String? {
        return try {
            val result = repository.testModel(service, apiKey, model)
            if (result == null) settingsPrefs.updateUsageStatsAsync(service, model, 10, 2, 12)
            result
        } catch (e: Exception) { e.message ?: "Test failed" }
    }

    suspend fun testModelWithPrompt(service: AppService, apiKey: String, model: String, prompt: String): Pair<Boolean, String?> {
        return try {
            val traceCountBefore = ApiTracer.getTraceCount()
            val (responseText, _) = repository.testModelWithPrompt(service, apiKey, model, prompt)
            val traceFile = ApiTracer.getTraceFiles().firstOrNull()?.let {
                if (ApiTracer.getTraceCount() > traceCountBefore) it.filename else null
            } ?: ApiTracer.getTraceFiles().firstOrNull()?.filename
            Pair(responseText != null && responseText.isNotBlank(), traceFile)
        } catch (_: Exception) { Pair(false, ApiTracer.getTraceFiles().firstOrNull()?.filename) }
    }

    /**
     * Per-model test variant safe to run concurrently. Captures startTime before the
     * call and then resolves the trace file by matching model name + timestamp, so
     * five parallel "Test all models" calls don't all collapse onto whichever trace
     * happened to land last globally.
     */
    suspend fun testSpecificModel(service: AppService, apiKey: String, model: String, prompt: String): Pair<Boolean, String?> {
        val startTime = System.currentTimeMillis()
        return try {
            val (responseText, _) = repository.testModelWithPrompt(service, apiKey, model, prompt)
            val traceFile = ApiTracer.getTraceFiles()
                .firstOrNull { it.model == model && it.timestamp >= startTime }
                ?.filename
            Pair(responseText != null && responseText.isNotBlank(), traceFile)
        } catch (_: Exception) {
            val traceFile = ApiTracer.getTraceFiles()
                .firstOrNull { it.model == model && it.timestamp >= startTime }
                ?.filename
            Pair(false, traceFile)
        }
    }

    // ===== External Intent =====

    fun setExternalInstructions(
        closeHtml: String?, reportType: String?, email: String?,
        nextAction: String? = null, returnAfterNext: Boolean = false,
        agentNames: List<String> = emptyList(), flockNames: List<String> = emptyList(),
        swarmNames: List<String> = emptyList(), modelSpecs: List<String> = emptyList(),
        edit: Boolean = false, select: Boolean = false, openHtml: String? = null,
        systemPrompt: String? = null
    ) {
        _uiState.update { it.copy(externalIntent = ExternalIntent(
            systemPrompt = systemPrompt, closeHtml = closeHtml,
            reportType = reportType, email = email,
            nextAction = nextAction, returnAfterNext = returnAfterNext,
            edit = edit, select = select, openHtml = openHtml,
            agentNames = agentNames, flockNames = flockNames,
            swarmNames = swarmNames, modelSpecs = modelSpecs
        )) }
    }

    fun clearExternalInstructions() {
        _uiState.update { it.copy(externalIntent = ExternalIntent()) }
    }

    // ===== Chat Parameters =====

    fun setChatParameters(params: ChatParameters) { _uiState.update { it.copy(chatParameters = params) } }
    fun setDualChatConfig(config: DualChatConfig?) { _uiState.update { it.copy(dualChatConfig = config) } }
    fun setReportAdvancedParameters(params: AgentParameters?) { _uiState.update { it.copy(reportAdvancedParameters = params) } }

    // ===== Internal helpers =====

    internal fun updateUiState(block: (UiState) -> UiState) { _uiState.update(block) }

    companion object {
        const val PREFS_NAME = "eval_prefs"
        fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
        internal const val REPORT_CONCURRENCY_LIMIT = 4
        /** Per-provider concurrency cap for fan-out runs. Each
         *  provider gets at most this many simultaneous requests in
         *  flight; different providers run their own caps in parallel.
         *  A 6-report fan out run on 6 different providers therefore
         *  runs up to 6 × FAN_OUT_PER_PROVIDER_LIMIT calls concurrently. */
        internal const val FAN_OUT_PER_PROVIDER_LIMIT = 3
        internal const val AI_REPORT_AGENTS_KEY = "ai_report_agents_v2"
        internal const val AI_REPORT_MODELS_KEY = "ai_report_models_v2"
        internal val USER_TAG_REGEX = Regex("""<user>(.*?)</user>""", RegexOption.DOT_MATCHES_ALL)

        // First-run marker. Absent on a fresh install and after a data
        // clear / reinstall, present after the very first successful
        // bootstrap. Survives app updates (SharedPreferences is part of
        // user data, untouched by APK upgrades), so the bundled
        // providers.json + prompts.json import is one-shot per install.
        internal const val KEY_FIRST_RUN_BOOTSTRAPPED = "first_run_bootstrapped"
    }
}
