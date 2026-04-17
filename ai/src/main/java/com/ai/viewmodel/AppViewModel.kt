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
    val defaultEmail: String = ""
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
    val genericReportsProgress: Int = 0,
    val genericReportsTotal: Int = 0,
    val genericReportsSelectedAgents: Set<String> = emptySet(),
    val currentReportId: String? = null,
    val reportAdvancedParameters: AgentParameters? = null,
    // External intent
    val externalSystemPrompt: String? = null,
    val externalCloseHtml: String? = null,
    val externalReportType: String? = null,
    val externalEmail: String? = null,
    val externalNextAction: String? = null,
    val externalReturn: Boolean = false,
    val externalEdit: Boolean = false,
    val externalSelect: Boolean = false,
    val externalOpenHtml: String? = null,
    val externalAgentNames: List<String> = emptyList(),
    val externalFlockNames: List<String> = emptyList(),
    val externalSwarmNames: List<String> = emptyList(),
    val externalModelSpecs: List<String> = emptyList(),
    // Chat
    val chatParameters: ChatParameters = ChatParameters(),
    val dualChatConfig: DualChatConfig? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    internal val repository = AnalysisRepository()
    internal val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    internal val settingsPrefs = SettingsPreferences(prefs, application.filesDir)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        ApiTracer.isTracingEnabled = true
        viewModelScope.launch(Dispatchers.IO) {
            val bs = bootstrap(application)
            _uiState.update { it.copy(generalSettings = bs.first, aiSettings = bs.second) }
            refreshAllModelLists(bs.second)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ApiTracer.currentReportId = null
    }

    private fun bootstrap(application: Application): Pair<GeneralSettings, Settings> {
        ApiTracer.init(application)
        ChatHistoryManager.init(application)
        ReportStorage.init(application)
        ProviderRegistry.init(application)

        var gs = settingsPrefs.loadGeneralSettings()
        var ai = settingsPrefs.loadSettingsWithMigration()

        if (!prefs.getBoolean("setup_imported", false)) {
            val result = importAiConfigFromAsset(application, "setup.json", ai)
            if (result != null) {
                ai = result.aiSettings
                settingsPrefs.saveSettings(ai)
                val updatedGs = gs.copy(
                    huggingFaceApiKey = result.huggingFaceApiKey ?: gs.huggingFaceApiKey,
                    openRouterApiKey = result.openRouterApiKey ?: gs.openRouterApiKey
                )
                if (updatedGs != gs) { gs = updatedGs; settingsPrefs.saveGeneralSettings(gs) }
            }
            prefs.edit { putBoolean("setup_imported", true) }
        }

        return gs to ai
    }

    // ===== Settings =====

    fun updateGeneralSettings(settings: GeneralSettings) {
        _uiState.update { it.copy(generalSettings = settings) }
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveGeneralSettings(settings) }
    }

    fun updateSettings(settings: Settings) {
        _uiState.update { it.copy(aiSettings = settings) }
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(settings) }
    }

    fun updateProviderState(service: AppService, state: String) {
        val updated = _uiState.value.aiSettings.withProviderState(service, state)
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
                val models = repository.fetchModels(service, apiKey)
                _uiState.update { state ->
                    state.copy(aiSettings = state.aiSettings.withModels(service, models), loadingModelsFor = state.loadingModelsFor - service)
                }
                settingsPrefs.saveModelsForProvider(service, models)
            } catch (e: Exception) {
                android.util.Log.w("AppViewModel", "Failed to fetch models for ${service.displayName}: ${e.message}")
                _uiState.update { it.copy(loadingModelsFor = it.loadingModelsFor - service) }
            }
        }
    }

    suspend fun refreshAllModelLists(settings: Settings, forceRefresh: Boolean = false, onProgress: ((String) -> Unit)? = null): Map<String, Int> {
        return withContext(Dispatchers.IO) {
            val toRefresh = AppService.entries.filter { service ->
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
                            val models = repository.fetchModels(service, settings.getApiKey(service))
                            _uiState.update { state -> state.copy(aiSettings = state.aiSettings.withModels(service, models)) }
                            settingsPrefs.saveModelsForProvider(service, models)
                            service to models.size
                        } catch (_: Exception) { service to -1 }
                    }
                }.awaitAll()
            }

            val successful = results.filter { it.second > 0 }.map { it.first }
            if (successful.isNotEmpty()) settingsPrefs.updateModelListTimestamps(successful)
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

    // ===== External Intent =====

    fun setExternalInstructions(
        closeHtml: String?, reportType: String?, email: String?,
        nextAction: String? = null, returnAfterNext: Boolean = false,
        agentNames: List<String> = emptyList(), flockNames: List<String> = emptyList(),
        swarmNames: List<String> = emptyList(), modelSpecs: List<String> = emptyList(),
        edit: Boolean = false, select: Boolean = false, openHtml: String? = null,
        systemPrompt: String? = null
    ) {
        _uiState.update { it.copy(
            externalSystemPrompt = systemPrompt, externalCloseHtml = closeHtml,
            externalReportType = reportType, externalEmail = email,
            externalNextAction = nextAction, externalReturn = returnAfterNext,
            externalEdit = edit, externalSelect = select, externalOpenHtml = openHtml,
            externalAgentNames = agentNames, externalFlockNames = flockNames,
            externalSwarmNames = swarmNames, externalModelSpecs = modelSpecs
        ) }
    }

    fun clearExternalInstructions() {
        _uiState.update { it.copy(
            externalSystemPrompt = null, externalCloseHtml = null,
            externalReportType = null, externalEmail = null,
            externalNextAction = null, externalReturn = false,
            externalEdit = false, externalSelect = false, externalOpenHtml = null,
            externalAgentNames = emptyList(), externalFlockNames = emptyList(),
            externalSwarmNames = emptyList(), externalModelSpecs = emptyList()
        ) }
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
