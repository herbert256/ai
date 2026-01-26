package com.ai.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.AiAnalysisRepository
import com.ai.data.AiAnalysisResponse
import com.ai.data.AiHistoryManager
import com.ai.data.AiService
import com.ai.data.ChatHistoryManager
import com.ai.data.ApiTracer
import com.ai.data.DummyApiServer
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

        // Initialize ChatHistoryManager for chat session storage
        ChatHistoryManager.init(application)

        // Load settings
        val generalSettings = loadGeneralSettings()
        val aiSettings = loadAiSettings()

        // Load persisted API-fetched models
        val chatGptApiModels = settingsPrefs.loadChatGptApiModels()
        val geminiApiModels = settingsPrefs.loadGeminiApiModels()
        val grokApiModels = settingsPrefs.loadGrokApiModels()
        val groqApiModels = settingsPrefs.loadGroqApiModels()
        val deepSeekApiModels = settingsPrefs.loadDeepSeekApiModels()
        val mistralApiModels = settingsPrefs.loadMistralApiModels()
        val togetherApiModels = settingsPrefs.loadTogetherApiModels()
        val openRouterApiModels = settingsPrefs.loadOpenRouterApiModels()
        val dummyApiModels = settingsPrefs.loadDummyApiModels()

        _uiState.value = _uiState.value.copy(
            generalSettings = generalSettings,
            aiSettings = aiSettings,
            availableChatGptModels = chatGptApiModels,
            availableGeminiModels = geminiApiModels,
            availableGrokModels = grokApiModels,
            availableGroqModels = groqApiModels,
            availableDeepSeekModels = deepSeekApiModels,
            availableMistralModels = mistralApiModels,
            availableTogetherModels = togetherApiModels,
            availableOpenRouterModels = openRouterApiModels,
            availableDummyModels = dummyApiModels
        )

        // Enable API tracing if configured
        ApiTracer.isTracingEnabled = generalSettings.trackApiCalls

        // Start DummyApiServer if developer mode is enabled
        if (generalSettings.developerMode) {
            DummyApiServer.start()
        }

        // Refresh model lists in background for providers with API source and configured API key
        viewModelScope.launch {
            refreshAllModelLists(aiSettings)
        }
    }

    // ========== Settings Management ==========

    fun updateGeneralSettings(settings: GeneralSettings) {
        val oldSettings = _uiState.value.generalSettings
        saveGeneralSettings(settings)
        _uiState.value = _uiState.value.copy(generalSettings = settings)

        // Start/stop DummyApiServer when developer mode changes
        if (settings.developerMode != oldSettings.developerMode) {
            if (settings.developerMode) {
                DummyApiServer.start()
            } else {
                DummyApiServer.stop()
            }
        }
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

    // ========== AI Reports Swarm Selection ==========

    fun loadAiReportSwarms(): Set<String> {
        val stored = prefs.getStringSet(AI_REPORT_SWARMS_KEY, emptySet()) ?: emptySet()
        return stored.toSet()
    }

    fun saveAiReportSwarms(swarmIds: Set<String>) {
        prefs.edit().putStringSet(AI_REPORT_SWARMS_KEY, swarmIds).apply()
    }

    // ========== Generic AI Reports ==========

    fun showGenericAiAgentSelection(title: String, prompt: String) {
        _uiState.value = _uiState.value.copy(
            genericAiPromptTitle = title,
            genericAiPromptText = prompt,
            showGenericAiAgentSelection = true,
            // Clear previous report state to prevent showing old results
            showGenericAiReportsDialog = false,
            genericAiReportsProgress = 0,
            genericAiReportsTotal = 0,
            genericAiReportsSelectedAgents = emptySet(),
            genericAiReportsAgentResults = emptyMap()
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

                    // Update usage statistics if successful
                    if (response.error == null && response.tokenUsage != null) {
                        val usage = response.tokenUsage
                        settingsPrefs.updateUsageStats(
                            provider = agent.provider,
                            model = agent.model,
                            inputTokens = usage.inputTokens,
                            outputTokens = usage.outputTokens,
                            totalTokens = usage.totalTokens
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

    fun stopGenericAiReports() {
        val currentState = _uiState.value
        val selectedAgents = currentState.genericAiReportsSelectedAgents
        val currentResults = currentState.genericAiReportsAgentResults

        // Fill in "Not ready" for agents that haven't responded yet
        val updatedResults = selectedAgents.associate { agentId ->
            val existingResult = currentResults[agentId]
            if (existingResult != null) {
                agentId to existingResult
            } else {
                val agent = currentState.aiSettings.getAgentById(agentId)
                agentId to AiAnalysisResponse(
                    service = agent?.provider ?: com.ai.data.AiService.DUMMY,
                    analysis = "Not ready",
                    error = null
                )
            }
        }

        _uiState.value = currentState.copy(
            genericAiReportsProgress = currentState.genericAiReportsTotal,
            genericAiReportsAgentResults = updatedResults
        )
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
                // Persist the fetched models
                settingsPrefs.saveChatGptApiModels(models)
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
                // Persist the fetched models
                settingsPrefs.saveGeminiApiModels(models)
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
                // Persist the fetched models
                settingsPrefs.saveGrokApiModels(models)
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
                // Persist the fetched models
                settingsPrefs.saveGroqApiModels(models)
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
                // Persist the fetched models
                settingsPrefs.saveDeepSeekApiModels(models)
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
                // Persist the fetched models
                settingsPrefs.saveMistralApiModels(models)
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
                // Note: Perplexity uses MANUAL source, but save anyway if API call is made
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
                // Persist the fetched models
                settingsPrefs.saveTogetherApiModels(models)
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
                // Persist the fetched models
                settingsPrefs.saveOpenRouterApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingOpenRouterModels = false)
            }
        }
    }

    fun fetchDummyModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDummyModels = true)
            try {
                val models = aiAnalysisRepository.fetchDummyModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableDummyModels = models,
                    isLoadingDummyModels = false
                )
                // Persist the fetched models
                settingsPrefs.saveDummyApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingDummyModels = false)
            }
        }
    }

    // ========== Refresh All Model Lists ==========

    /**
     * Refresh model lists for all providers with API as model source.
     * Returns a map of provider display name to model count.
     */
    suspend fun refreshAllModelLists(settings: AiSettings): Map<String, Int> {
        val results = mutableMapOf<String, Int>()

        // OpenAI
        if (settings.chatGptModelSource == ModelSource.API && settings.chatGptApiKey.isNotBlank()) {
            try {
                val models = aiAnalysisRepository.fetchChatGptModels(settings.chatGptApiKey)
                _uiState.value = _uiState.value.copy(availableChatGptModels = models)
                settingsPrefs.saveChatGptApiModels(models)
                results["OpenAI"] = models.size
            } catch (e: Exception) {
                results["OpenAI"] = -1  // -1 indicates error
            }
        }

        // Google
        if (settings.geminiModelSource == ModelSource.API && settings.geminiApiKey.isNotBlank()) {
            try {
                val models = aiAnalysisRepository.fetchGeminiModels(settings.geminiApiKey)
                _uiState.value = _uiState.value.copy(availableGeminiModels = models)
                settingsPrefs.saveGeminiApiModels(models)
                results["Google"] = models.size
            } catch (e: Exception) {
                results["Google"] = -1
            }
        }

        // xAI
        if (settings.grokModelSource == ModelSource.API && settings.grokApiKey.isNotBlank()) {
            try {
                val models = aiAnalysisRepository.fetchGrokModels(settings.grokApiKey)
                _uiState.value = _uiState.value.copy(availableGrokModels = models)
                settingsPrefs.saveGrokApiModels(models)
                results["xAI"] = models.size
            } catch (e: Exception) {
                results["xAI"] = -1
            }
        }

        // Groq
        if (settings.groqModelSource == ModelSource.API && settings.groqApiKey.isNotBlank()) {
            try {
                val models = aiAnalysisRepository.fetchGroqModels(settings.groqApiKey)
                _uiState.value = _uiState.value.copy(availableGroqModels = models)
                settingsPrefs.saveGroqApiModels(models)
                results["Groq"] = models.size
            } catch (e: Exception) {
                results["Groq"] = -1
            }
        }

        // DeepSeek
        if (settings.deepSeekModelSource == ModelSource.API && settings.deepSeekApiKey.isNotBlank()) {
            try {
                val models = aiAnalysisRepository.fetchDeepSeekModels(settings.deepSeekApiKey)
                _uiState.value = _uiState.value.copy(availableDeepSeekModels = models)
                settingsPrefs.saveDeepSeekApiModels(models)
                results["DeepSeek"] = models.size
            } catch (e: Exception) {
                results["DeepSeek"] = -1
            }
        }

        // Mistral
        if (settings.mistralModelSource == ModelSource.API && settings.mistralApiKey.isNotBlank()) {
            try {
                val models = aiAnalysisRepository.fetchMistralModels(settings.mistralApiKey)
                _uiState.value = _uiState.value.copy(availableMistralModels = models)
                settingsPrefs.saveMistralApiModels(models)
                results["Mistral"] = models.size
            } catch (e: Exception) {
                results["Mistral"] = -1
            }
        }

        // Together
        if (settings.togetherModelSource == ModelSource.API && settings.togetherApiKey.isNotBlank()) {
            try {
                val models = aiAnalysisRepository.fetchTogetherModels(settings.togetherApiKey)
                _uiState.value = _uiState.value.copy(availableTogetherModels = models)
                settingsPrefs.saveTogetherApiModels(models)
                results["Together"] = models.size
            } catch (e: Exception) {
                results["Together"] = -1
            }
        }

        // OpenRouter
        if (settings.openRouterModelSource == ModelSource.API && settings.openRouterApiKey.isNotBlank()) {
            try {
                val models = aiAnalysisRepository.fetchOpenRouterModels(settings.openRouterApiKey)
                _uiState.value = _uiState.value.copy(availableOpenRouterModels = models)
                settingsPrefs.saveOpenRouterApiModels(models)
                results["OpenRouter"] = models.size
            } catch (e: Exception) {
                results["OpenRouter"] = -1
            }
        }

        // Dummy (if in developer mode, we'd need to check that separately)
        if (settings.dummyModelSource == ModelSource.API && settings.dummyApiKey.isNotBlank()) {
            try {
                val models = aiAnalysisRepository.fetchDummyModels(settings.dummyApiKey)
                _uiState.value = _uiState.value.copy(availableDummyModels = models)
                settingsPrefs.saveDummyApiModels(models)
                results["Dummy"] = models.size
            } catch (e: Exception) {
                results["Dummy"] = -1
            }
        }

        return results
    }

    // ========== AI Model Testing ==========

    suspend fun testAiModel(service: AiService, apiKey: String, model: String): String? {
        return try {
            aiAnalysisRepository.testModel(service, apiKey, model)
        } catch (e: Exception) {
            e.message ?: "Test failed"
        }
    }

    // ========== AI Chat ==========

    fun setChatParameters(params: ChatParameters) {
        _uiState.value = _uiState.value.copy(chatParameters = params)
    }

    suspend fun sendChatMessage(
        service: AiService,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>
    ): ChatMessage? {
        return try {
            val response = aiAnalysisRepository.sendChatMessage(
                service = service,
                apiKey = apiKey,
                model = model,
                messages = messages,
                params = _uiState.value.chatParameters
            )
            ChatMessage(role = "assistant", content = response)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val AI_REPORT_AGENTS_KEY = "ai_report_agents"
        private const val AI_REPORT_SWARMS_KEY = "ai_report_swarms"
    }
}
