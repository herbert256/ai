package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.settings.importAiConfigFromAsset
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
data class GeneralSettings(
    val userName: String = "user",
    val huggingFaceApiKey: String = "",
    val openRouterApiKey: String = "",
    val defaultEmail: String = "",
    /** Default API path per model type. Used when a provider doesn't declare a
     *  per-type override in its typePaths. Falls back to ModelType.DEFAULT_PATHS
     *  when this map is empty for a given type. */
    val defaultTypePaths: Map<String, String> = emptyMap()
)

// Prompt history entry
data class PromptHistoryEntry(
    val timestamp: Long,
    val title: String,
    val prompt: String
)

// Main UI state
data class UiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val generalSettings: GeneralSettings = GeneralSettings(),
    val aiSettings: Settings = Settings(),
    val loadingModelsFor: Set<AppService> = emptySet(),
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

    init {
        ApiTracer.isTracingEnabled = true
        PricingCache.preloadAsync(application, viewModelScope)
        viewModelScope.launch(Dispatchers.IO) {
            val bs = bootstrap(application)
            // Publish user-supplied default type paths to the global resolver so dispatch
            // (which doesn't see GeneralSettings directly) can fall back through them.
            ModelType.userDefaults = bs.first.defaultTypePaths
            _uiState.update { it.copy(generalSettings = bs.first, aiSettings = bs.second) }
            refreshAllModelLists(bs.second)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ApiTracer.currentReportId = null
    }

    private suspend fun bootstrap(application: Application): Pair<GeneralSettings, Settings> {
        ApiTracer.init(application)
        ChatHistoryManager.init(application)
        ReportStorage.init(application)
        ProviderRegistry.init(application)
        PromptCache.init(application)

        var gs = settingsPrefs.loadGeneralSettings()
        var ai = settingsPrefs.loadSettingsWithMigration()

        val alreadyImported = application.readBoolean(AppPrefKeys.SETUP_IMPORTED)
        if (!alreadyImported) {
            // One-shot bootstrap: read the setup flag from DataStore for atomicity guarantees
            // across a potential legacy SharedPreferences → DataStore migration. Also migrate
            // the legacy SharedPreferences flag if present so existing users don't re-run setup.
            val legacy = prefs.getBoolean("setup_imported", false)
            if (legacy) {
                application.writeBoolean(AppPrefKeys.SETUP_IMPORTED, true)
            } else {
                val result = importAiConfigFromAsset(application, "setup.json", ai)
                if (result != null) {
                    ai = result.aiSettings
                    settingsPrefs.saveSettings(ai)
                    val updatedGs = gs.copy(
                        huggingFaceApiKey = result.huggingFaceApiKey ?: gs.huggingFaceApiKey,
                        openRouterApiKey = result.openRouterApiKey ?: gs.openRouterApiKey,
                        defaultTypePaths = result.defaultTypePaths ?: gs.defaultTypePaths
                    )
                    if (updatedGs != gs) { gs = updatedGs; settingsPrefs.saveGeneralSettings(gs) }
                }
                application.writeBoolean(AppPrefKeys.SETUP_IMPORTED, true)
            }
        }

        return gs to ai
    }

    // ===== Settings =====

    fun updateGeneralSettings(settings: GeneralSettings) {
        ModelType.userDefaults = settings.defaultTypePaths
        _uiState.update { it.copy(generalSettings = settings) }
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveGeneralSettings(settings) }
    }

    fun updateSettings(settings: Settings) {
        _uiState.update { it.copy(aiSettings = settings) }
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(settings) }
    }

    fun updateProviderState(service: AppService, state: String) {
        var updated = _uiState.value.aiSettings.withProviderState(service, state)
        // When a provider goes inactive, drop its default agent (the one named after the
        // provider's displayName) so flocks/swarms don't keep referencing a disabled path.
        if (state == "inactive") {
            val pruned = updated.agents.filterNot { it.provider.id == service.id && it.name == service.displayName }
            if (pruned.size != updated.agents.size) {
                val droppedIds = updated.agents.filter { it !in pruned }.map { it.id }.toSet()
                val flocks = updated.flocks.map { f -> f.copy(agentIds = f.agentIds.filterNot { it in droppedIds }) }
                updated = updated.copy(agents = pruned, flocks = flocks)
            }
        }
        _uiState.update { it.copy(aiSettings = updated) }
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(updated) }
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

    fun fetchModels(service: AppService, apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(loadingModelsFor = it.loadingModelsFor + service) }
            try {
                val fetched = repository.fetchModelsWithKinds(service, apiKey)
                _uiState.update { state ->
                    val withSelf = state.aiSettings.withModels(service, fetched.ids, fetched.types, fetched.visionModels)
                    // Cross-pollinate OpenRouter labels — covers two flows:
                    //   • non-OpenRouter fetch picks up labels OpenRouter already has cached
                    //   • OpenRouter fetch propagates fresh labels to every other provider
                    state.copy(aiSettings = withSelf.applyOpenRouterTypes(), loadingModelsFor = state.loadingModelsFor - service)
                }
                val final = _uiState.value.aiSettings
                settingsPrefs.saveModelsForProvider(service, fetched.ids, final.getProvider(service).modelTypes)
                if (service.id == "OPENROUTER") {
                    // Persist the freshly cross-applied labels for every other provider.
                    AppService.entries.filter { it.id != service.id }.forEach { other ->
                        val cfg = final.getProvider(other)
                        if (cfg.models.isNotEmpty()) settingsPrefs.saveModelsForProvider(other, cfg.models, cfg.modelTypes)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AppViewModel", "Failed to fetch models for ${service.displayName}: ${e.message}")
                _uiState.update { it.copy(loadingModelsFor = it.loadingModelsFor - service) }
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
                        onProgress?.invoke(service.displayName)
                        try {
                            val fetched = repository.fetchModelsWithKinds(service, settings.getApiKey(service))
                            _uiState.update { state -> state.copy(aiSettings = state.aiSettings.withModels(service, fetched.ids, fetched.types, fetched.visionModels)) }
                            settingsPrefs.saveModelsForProvider(service, fetched.ids, fetched.types)
                            service to fetched.ids.size
                        } catch (_: Exception) { service to -1 }
                    }
                }.awaitAll()
            }

            val successful = results.filter { it.second > 0 }.map { it.first }
            if (successful.isNotEmpty()) settingsPrefs.updateModelListTimestamps(successful)
            // Final cross-lookup pass — guarantees that whichever order the parallel
            // fetches finished in, OpenRouter's labels end up applied everywhere.
            _uiState.update { state -> state.copy(aiSettings = state.aiSettings.applyOpenRouterTypes()) }
            val final = _uiState.value.aiSettings
            successful.forEach { service ->
                val cfg = final.getProvider(service)
                if (cfg.models.isNotEmpty()) settingsPrefs.saveModelsForProvider(service, cfg.models, cfg.modelTypes)
            }
            results.associate { it.first.displayName to it.second }
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
        internal const val AI_REPORT_AGENTS_KEY = "ai_report_agents_v2"
        internal const val AI_REPORT_MODELS_KEY = "ai_report_models_v2"
        internal val USER_TAG_REGEX = Regex("""<user>(.*?)</user>""", RegexOption.DOT_MATCHES_ALL)
    }
}
