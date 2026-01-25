package com.ai.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.AiAnalysisRepository
import com.ai.data.AiAnalysisResponse
import com.ai.data.AiHistoryManager
import com.ai.data.AiService
import com.ai.data.ApiTracer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val aiAnalysisRepository = AiAnalysisRepository()
    private val prefs = application.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    // Helper classes for settings
    private val settingsPrefs = SettingsPreferences(prefs)

    private val _uiState = MutableStateFlow(AiUiState())
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    // Settings persistence
    private fun loadGeneralSettings(): GeneralSettings = settingsPrefs.loadGeneralSettings()
    private fun saveGeneralSettings(settings: GeneralSettings) = settingsPrefs.saveGeneralSettings(settings)
    private fun loadAiSettings(): AiSettings = settingsPrefs.loadAiSettingsWithMigration()
    private fun saveAiSettings(settings: AiSettings) = settingsPrefs.saveAiSettings(settings)

    init {
        // Initialize ApiTracer for debugging
        ApiTracer.init(application)

        // Initialize AiHistoryManager for AI report storage
        AiHistoryManager.init(application)

        // Load settings
        val generalSettings = loadGeneralSettings()
        val aiSettings = loadAiSettings()

        _uiState.value = _uiState.value.copy(
            generalSettings = generalSettings,
            aiSettings = aiSettings
        )

        // Enable API tracing if configured
        ApiTracer.isTracingEnabled = generalSettings.trackApiCalls
    }

    // ========== Settings Management ==========

    fun updateGeneralSettings(settings: GeneralSettings) {
        saveGeneralSettings(settings)
        _uiState.value = _uiState.value.copy(generalSettings = settings)
    }

    fun updateAiSettings(settings: AiSettings) {
        saveAiSettings(settings)
        _uiState.value = _uiState.value.copy(aiSettings = settings)
    }

    fun updateTrackApiCalls(enabled: Boolean) {
        val settings = _uiState.value.generalSettings.copy(trackApiCalls = enabled)
        saveGeneralSettings(settings)
        _uiState.value = _uiState.value.copy(generalSettings = settings)
        ApiTracer.isTracingEnabled = enabled
        if (!enabled) {
            ApiTracer.clearTraces()
        }
    }

    fun clearTraces() {
        ApiTracer.clearTraces()
    }

    // ========== AI Reports Agent Selection ==========

    fun loadAiReportAgents(): Set<String> {
        val stored = prefs.getStringSet(AI_REPORT_AGENTS_KEY, emptySet()) ?: emptySet()
        return stored.toSet()
    }

    fun saveAiReportAgents(agentIds: Set<String>) {
        prefs.edit().putStringSet(AI_REPORT_AGENTS_KEY, agentIds).apply()
    }

    // ========== Generic AI Reports ==========

    fun showGenericAiAgentSelection(title: String, prompt: String) {
        _uiState.value = _uiState.value.copy(
            genericAiPromptTitle = title,
            genericAiPromptText = prompt,
            showGenericAiAgentSelection = true
        )
    }

    fun dismissGenericAiAgentSelection() {
        _uiState.value = _uiState.value.copy(
            showGenericAiAgentSelection = false
        )
    }

    fun generateGenericAiReports(selectedAgentIds: Set<String>) {
        viewModelScope.launch {
            val aiSettings = _uiState.value.aiSettings
            val prompt = _uiState.value.genericAiPromptText

            _uiState.value = _uiState.value.copy(
                showGenericAiAgentSelection = false,
                showGenericAiReportsDialog = true,
                genericAiReportsProgress = 0,
                genericAiReportsTotal = selectedAgentIds.size,
                genericAiReportsSelectedAgents = selectedAgentIds,
                genericAiReportsAgentResults = emptyMap()
            )

            // Get the actual agents from settings
            val agents = selectedAgentIds.mapNotNull { agentId ->
                aiSettings.getAgentById(agentId)
            }

            // Make all API calls in parallel, but update state as each completes
            agents.map { agent ->
                async {
                    val response = try {
                        aiAnalysisRepository.analyzePositionWithAgent(
                            agent = agent,
                            fen = "",  // No FEN for generic prompts
                            prompt = prompt
                        )
                    } catch (e: Exception) {
                        AiAnalysisResponse(
                            service = agent.provider,
                            analysis = null,
                            error = e.message ?: "Unknown error"
                        )
                    }

                    // Update state immediately when this agent completes
                    _uiState.value = _uiState.value.copy(
                        genericAiReportsProgress = _uiState.value.genericAiReportsProgress + 1,
                        genericAiReportsAgentResults = _uiState.value.genericAiReportsAgentResults + (agent.id to response)
                    )
                }
            }.awaitAll()
        }
    }

    fun dismissGenericAiReportsDialog() {
        _uiState.value = _uiState.value.copy(
            showGenericAiReportsDialog = false,
            genericAiPromptTitle = "",
            genericAiPromptText = "",
            genericAiReportsProgress = 0,
            genericAiReportsTotal = 0,
            genericAiReportsSelectedAgents = emptySet(),
            genericAiReportsAgentResults = emptyMap()
        )
    }

    // ========== Model Fetching ==========

    fun fetchChatGptModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingChatGptModels = true)
            try {
                val models = aiAnalysisRepository.fetchChatGptModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableChatGptModels = models,
                    isLoadingChatGptModels = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingChatGptModels = false)
            }
        }
    }

    fun fetchGeminiModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingGeminiModels = true)
            try {
                val models = aiAnalysisRepository.fetchGeminiModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableGeminiModels = models,
                    isLoadingGeminiModels = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingGeminiModels = false)
            }
        }
    }

    fun fetchGrokModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingGrokModels = true)
            try {
                val models = aiAnalysisRepository.fetchGrokModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableGrokModels = models,
                    isLoadingGrokModels = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingGrokModels = false)
            }
        }
    }

    fun fetchGroqModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingGroqModels = true)
            try {
                val models = aiAnalysisRepository.fetchGroqModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableGroqModels = models,
                    isLoadingGroqModels = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingGroqModels = false)
            }
        }
    }

    fun fetchDeepSeekModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDeepSeekModels = true)
            try {
                val models = aiAnalysisRepository.fetchDeepSeekModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableDeepSeekModels = models,
                    isLoadingDeepSeekModels = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingDeepSeekModels = false)
            }
        }
    }

    fun fetchMistralModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMistralModels = true)
            try {
                val models = aiAnalysisRepository.fetchMistralModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableMistralModels = models,
                    isLoadingMistralModels = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMistralModels = false)
            }
        }
    }

    fun fetchPerplexityModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPerplexityModels = true)
            try {
                val models = aiAnalysisRepository.fetchPerplexityModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availablePerplexityModels = models,
                    isLoadingPerplexityModels = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingPerplexityModels = false)
            }
        }
    }

    fun fetchTogetherModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingTogetherModels = true)
            try {
                val models = aiAnalysisRepository.fetchTogetherModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableTogetherModels = models,
                    isLoadingTogetherModels = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingTogetherModels = false)
            }
        }
    }

    fun fetchOpenRouterModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingOpenRouterModels = true)
            try {
                val models = aiAnalysisRepository.fetchOpenRouterModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableOpenRouterModels = models,
                    isLoadingOpenRouterModels = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingOpenRouterModels = false)
            }
        }
    }

    // ========== AI Model Testing ==========

    suspend fun testAiModel(service: AiService, apiKey: String, model: String): String? {
        return try {
            aiAnalysisRepository.testModel(service, apiKey, model)
        } catch (e: Exception) {
            e.message ?: "Test failed"
        }
    }

    companion object {
        private const val AI_REPORT_AGENTS_KEY = "ai_report_agents"
    }
}
