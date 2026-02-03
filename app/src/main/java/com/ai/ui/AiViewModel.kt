package com.ai.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val aiAnalysisRepository = AiAnalysisRepository()
    private val prefs = application.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    // Helper classes for settings
    private val settingsPrefs = SettingsPreferences(prefs, application.filesDir)

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

        // Initialize ChatHistoryManager for chat session storage
        ChatHistoryManager.init(application)

        // Initialize AiReportStorage for tracking report generation
        AiReportStorage.init(application)

        // Initialize ProviderRegistry (loads providers from SharedPreferences or assets/providers.json)
        com.ai.data.ProviderRegistry.init(application)

        // Load settings (models are now part of aiSettings via ProviderConfig.models)
        val generalSettings = loadGeneralSettings()
        val aiSettings = loadAiSettings()

        _uiState.value = _uiState.value.copy(
            generalSettings = generalSettings,
            aiSettings = aiSettings
        )

        // Enable API tracing if configured
        ApiTracer.isTracingEnabled = generalSettings.trackApiCalls

        // Refresh model lists in background for providers with API source and configured API key
        viewModelScope.launch {
            refreshAllModelLists(aiSettings)
        }
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

    fun updateProviderState(service: AiService, state: String) {
        val updated = _uiState.value.aiSettings.withProviderState(service, state)
        saveAiSettings(updated)
        _uiState.value = _uiState.value.copy(aiSettings = updated)
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

    // ========== AI Reports Flock Selection ==========

    fun loadAiReportFlocks(): Set<String> {
        val stored = prefs.getStringSet(AI_REPORT_SWARMS_KEY, emptySet()) ?: emptySet()
        return stored.toSet()
    }

    fun saveAiReportFlocks(flockIds: Set<String>) {
        prefs.edit().putStringSet(AI_REPORT_SWARMS_KEY, flockIds).apply()
    }

    fun loadAiReportSwarms(): Set<String> {
        val stored = prefs.getStringSet(AI_REPORT_FLOCKS_KEY, emptySet()) ?: emptySet()
        return stored.toSet()
    }

    fun saveAiReportSwarms(swarmIds: Set<String>) {
        prefs.edit().putStringSet(AI_REPORT_FLOCKS_KEY, swarmIds).apply()
    }

    // ========== AI Reports Direct Model Selection ==========

    fun loadAiReportModels(): Set<String> {
        val stored = prefs.getStringSet(AI_REPORT_MODELS_KEY, emptySet()) ?: emptySet()
        return stored.toSet()
    }

    fun saveAiReportModels(modelIds: Set<String>) {
        prefs.edit().putStringSet(AI_REPORT_MODELS_KEY, modelIds).apply()
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
            genericAiReportsAgentResults = emptyMap(),
            currentReportId = null
        )
    }

    fun dismissGenericAiAgentSelection() {
        _uiState.value = _uiState.value.copy(
            showGenericAiAgentSelection = false
        )
    }

    fun generateGenericAiReports(selectedAgentIds: Set<String>, selectedSwarmIds: Set<String> = emptySet(), directModelIds: Set<String> = emptySet(), parametersIds: List<String> = emptyList(), reportType: com.ai.data.ReportType = com.ai.data.ReportType.CLASSIC) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val aiSettings = _uiState.value.aiSettings
            val prompt = _uiState.value.genericAiPromptText
            val title = _uiState.value.genericAiPromptTitle
            // Use the parametersIds to merge params presets, or fall back to advanced parameters
            val mergedParams = aiSettings.mergeParameters(parametersIds)
            val overrideParams = mergedParams ?: _uiState.value.reportAdvancedParameters

            // Get actual agents from settings
            val agents = selectedAgentIds.mapNotNull { agentId ->
                aiSettings.getAgentById(agentId)
            }

            // Get swarm members from selected swarms
            val swarmMembers = aiSettings.getMembersForSwarms(selectedSwarmIds)

            // Generate synthetic IDs for swarm members
            val swarmMemberIds = swarmMembers.map { member ->
                "swarm:${member.provider.id}:${member.model}"
            }.toSet()

            // Filter direct model IDs to exclude those already in swarms
            val uniqueDirectModelIds = directModelIds.filter { it !in swarmMemberIds }.toSet()

            // Parse direct model IDs into AiSwarmMember-like structures
            val directModels = uniqueDirectModelIds.mapNotNull { modelId ->
                // Parse synthetic ID: "swarm:PROVIDER:model"
                val parts = modelId.removePrefix("swarm:").split(":", limit = 2)
                val providerName = parts.getOrNull(0) ?: return@mapNotNull null
                val modelName = parts.getOrNull(1) ?: return@mapNotNull null
                val provider = com.ai.data.AiService.findById(providerName) ?: return@mapNotNull null
                AiSwarmMember(provider, modelName)
            }

            // Combine swarm members and direct models
            val allModelMembers = swarmMembers + directModels
            val allModelIds = swarmMemberIds + uniqueDirectModelIds

            // Total model count
            val totalModels = agents.size + allModelMembers.size

            _uiState.value = _uiState.value.copy(
                showGenericAiAgentSelection = false,
                showGenericAiReportsDialog = true,
                genericAiReportsProgress = 0,
                genericAiReportsTotal = totalModels,
                genericAiReportsSelectedAgents = selectedAgentIds + allModelIds,
                genericAiReportsAgentResults = emptyMap(),
                currentReportId = null  // Will be set after report creation
            )

            // Create AI Report objects for agents
            val reportAgents = agents.map { agent ->
                AiReportAgent(
                    agentId = agent.id,
                    agentName = agent.name,
                    provider = agent.provider.id,
                    model = aiSettings.getEffectiveModelForAgent(agent),
                    reportStatus = ReportStatus.PENDING
                )
            }

            // Create AI Report objects for all model members (from swarms and direct selection)
            val reportModelMembers = allModelMembers.map { member ->
                val syntheticId = "swarm:${member.provider.id}:${member.model}"
                AiReportAgent(
                    agentId = syntheticId,
                    agentName = "${member.provider.displayName} / ${member.model}",
                    provider = member.provider.id,
                    model = member.model,
                    reportStatus = ReportStatus.PENDING
                )
            }

            val allReportAgents = reportAgents + reportModelMembers

            // Extract <user>...</user> content from prompt if present
            // Content inside tags goes to HTML export, tags are stripped from AI prompt
            // Falls back to externalOpenHtml (from <open> tag in <edit> mode)
            val userTagRegex = Regex("""<user>(.*?)</user>""", RegexOption.DOT_MATCHES_ALL)
            val userMatch = userTagRegex.find(prompt)
            val rapportText = userMatch?.groupValues?.get(1)?.trim() ?: _uiState.value.externalOpenHtml
            val aiPrompt = if (userMatch != null) {
                prompt.replace(userMatch.value, "").trim()
            } else {
                prompt
            }

            val report = AiReportStorage.createReport(
                context = context,
                title = title.ifBlank { "AI Report" },
                prompt = aiPrompt,
                agents = allReportAgents,
                rapportText = rapportText,
                reportType = reportType,
                closeText = _uiState.value.externalCloseHtml
            )
            val reportId = report.id

            // Store reportId in state for tracking
            _uiState.value = _uiState.value.copy(currentReportId = reportId)

            // Set the current report ID for API tracing (if enabled)
            ApiTracer.currentReportId = reportId

            // Make all API calls in parallel, but update state as each completes
            val agentJobs = agents.map { agent ->
                async {
                    // Mark agent as running
                    AiReportStorage.markAgentRunning(
                        context = context,
                        reportId = reportId,
                        agentId = agent.id,
                        requestBody = aiPrompt
                    )

                    // Use effective API key and model (agent's or provider's)
                    val effectiveAgent = agent.copy(
                        apiKey = aiSettings.getEffectiveApiKeyForAgent(agent),
                        model = aiSettings.getEffectiveModelForAgent(agent)
                    )

                    // Resolve agent's parameter presets to AiAgentParameters
                    val agentParams = aiSettings.resolveAgentParameters(agent)

                    val startTime = System.currentTimeMillis()
                    val response = try {
                        aiAnalysisRepository.analyzeWithAgent(
                            agent = effectiveAgent,
                            content = "",
                            prompt = aiPrompt,
                            agentResolvedParams = agentParams,
                            overrideParams = overrideParams,
                            context = context  // For looking up supported parameters
                        )
                    } catch (e: Exception) {
                        AiAnalysisResponse(
                            service = agent.provider,
                            analysis = null,
                            error = e.message ?: "Unknown error"
                        )
                    }
                    val durationMs = System.currentTimeMillis() - startTime

                    // Calculate cost for this agent
                    // Priority: API cost > OVERRIDE > OPENROUTER > LITELLM > FALLBACK > DEFAULT
                    val cost: Double? = if (response.tokenUsage != null) {
                        // First check if API provided the cost directly
                        response.tokenUsage.apiCost ?: run {
                            // Otherwise calculate from pricing cache (always returns a value)
                            val pricing = PricingCache.getPricing(context, effectiveAgent.provider, effectiveAgent.model)
                            val inputCost = response.tokenUsage.inputTokens * pricing.promptPrice
                            val outputCost = response.tokenUsage.outputTokens * pricing.completionPrice
                            inputCost + outputCost
                        }
                    } else null

                    // Update report storage with result
                    if (response.isSuccess) {
                        AiReportStorage.markAgentSuccess(
                            context = context,
                            reportId = reportId,
                            agentId = agent.id,
                            httpStatus = 200,
                            responseHeaders = response.httpHeaders,
                            responseBody = response.analysis,
                            tokenUsage = response.tokenUsage,
                            cost = cost,
                            citations = response.citations,
                            searchResults = response.searchResults,
                            relatedQuestions = response.relatedQuestions,
                            durationMs = durationMs
                        )
                    } else {
                        AiReportStorage.markAgentError(
                            context = context,
                            reportId = reportId,
                            agentId = agent.id,
                            httpStatus = response.httpStatusCode,
                            errorMessage = response.error,
                            responseHeaders = response.httpHeaders,
                            responseBody = response.analysis,
                            durationMs = durationMs
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
            }

            // Process all model members in parallel (from swarms and direct selection)
            val swarmJobs = allModelMembers.map { member ->
                async {
                    val syntheticId = "swarm:${member.provider.id}:${member.model}"

                    // Mark as running
                    AiReportStorage.markAgentRunning(
                        context = context,
                        reportId = reportId,
                        agentId = syntheticId,
                        requestBody = aiPrompt
                    )

                    // Get provider's API key
                    val providerApiKey = aiSettings.getApiKey(member.provider)

                    // Create a temporary agent with provider defaults
                    val tempAgent = AiAgent(
                        id = syntheticId,
                        name = "${member.provider.displayName} / ${member.model}",
                        provider = member.provider,
                        model = member.model,
                        apiKey = providerApiKey
                    )

                    val startTime = System.currentTimeMillis()
                    val response = try {
                        aiAnalysisRepository.analyzeWithAgent(
                            agent = tempAgent,
                            content = "",
                            prompt = aiPrompt,
                            overrideParams = overrideParams,
                            context = context
                        )
                    } catch (e: Exception) {
                        AiAnalysisResponse(
                            service = member.provider,
                            analysis = null,
                            error = e.message ?: "Unknown error"
                        )
                    }
                    val durationMs = System.currentTimeMillis() - startTime

                    // Calculate cost
                    val cost: Double? = if (response.tokenUsage != null) {
                        response.tokenUsage.apiCost ?: run {
                            val pricing = PricingCache.getPricing(context, member.provider, member.model)
                            val inputCost = response.tokenUsage.inputTokens * pricing.promptPrice
                            val outputCost = response.tokenUsage.outputTokens * pricing.completionPrice
                            inputCost + outputCost
                        }
                    } else null

                    // Update report storage with result
                    if (response.isSuccess) {
                        AiReportStorage.markAgentSuccess(
                            context = context,
                            reportId = reportId,
                            agentId = syntheticId,
                            httpStatus = 200,
                            responseHeaders = response.httpHeaders,
                            responseBody = response.analysis,
                            tokenUsage = response.tokenUsage,
                            cost = cost,
                            citations = response.citations,
                            searchResults = response.searchResults,
                            relatedQuestions = response.relatedQuestions,
                            durationMs = durationMs
                        )
                    } else {
                        AiReportStorage.markAgentError(
                            context = context,
                            reportId = reportId,
                            agentId = syntheticId,
                            httpStatus = response.httpStatusCode,
                            errorMessage = response.error,
                            responseHeaders = response.httpHeaders,
                            responseBody = response.analysis,
                            durationMs = durationMs
                        )
                    }

                    // Update usage statistics if successful
                    if (response.error == null && response.tokenUsage != null) {
                        val usage = response.tokenUsage
                        settingsPrefs.updateUsageStats(
                            provider = member.provider,
                            model = member.model,
                            inputTokens = usage.inputTokens,
                            outputTokens = usage.outputTokens,
                            totalTokens = usage.totalTokens
                        )
                    }

                    // Update state immediately when this swarm member completes
                    _uiState.value = _uiState.value.copy(
                        genericAiReportsProgress = _uiState.value.genericAiReportsProgress + 1,
                        genericAiReportsAgentResults = _uiState.value.genericAiReportsAgentResults + (syntheticId to response)
                    )
                }
            }

            // Wait for all jobs to complete
            (agentJobs + swarmJobs).awaitAll()

            // Clear the current report ID for API tracing
            ApiTracer.currentReportId = null
        }
    }

    fun stopGenericAiReports() {
        val context = getApplication<Application>()
        val currentState = _uiState.value
        val selectedAgents = currentState.genericAiReportsSelectedAgents
        val currentResults = currentState.genericAiReportsAgentResults
        val reportId = currentState.currentReportId

        // Fill in "Not ready" for agents that haven't responded yet
        val updatedResults = selectedAgents.associate { agentId ->
            val existingResult = currentResults[agentId]
            if (existingResult != null) {
                agentId to existingResult
            } else {
                val agent = currentState.aiSettings.getAgentById(agentId)

                // Mark as stopped in storage if we have a reportId
                if (reportId != null) {
                    AiReportStorage.markAgentStopped(
                        context = context,
                        reportId = reportId,
                        agentId = agentId
                    )
                }

                agentId to AiAnalysisResponse(
                    service = agent?.provider ?: com.ai.data.AiService.entries.first(),
                    analysis = "Not ready",
                    error = null
                )
            }
        }

        // Clear the current report ID for API tracing
        ApiTracer.currentReportId = null

        _uiState.value = currentState.copy(
            genericAiReportsProgress = currentState.genericAiReportsTotal,
            genericAiReportsAgentResults = updatedResults
        )
    }

    fun dismissGenericAiReportsDialog() {
        // Clear the current report ID for API tracing
        ApiTracer.currentReportId = null
        _uiState.value = _uiState.value.copy(
            showGenericAiReportsDialog = false,
            genericAiPromptTitle = "",
            genericAiPromptText = "",
            genericAiReportsProgress = 0,
            genericAiReportsTotal = 0,
            genericAiReportsSelectedAgents = emptySet(),
            genericAiReportsAgentResults = emptyMap(),
            currentReportId = null,
            reportAdvancedParameters = null  // Clear advanced parameters
        )
    }

    // ========== Model Fetching ==========

    fun fetchModels(service: AiService, apiKey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingModelsFor = it.loadingModelsFor + service) }
            try {
                val models = aiAnalysisRepository.fetchModels(service, apiKey)
                _uiState.update { state ->
                    state.copy(
                        aiSettings = state.aiSettings.withModels(service, models),
                        loadingModelsFor = state.loadingModelsFor - service
                    )
                }
                settingsPrefs.saveModelsForProvider(service, models)
            } catch (e: Exception) {
                _uiState.update { it.copy(loadingModelsFor = it.loadingModelsFor - service) }
            }
        }
    }

    // ========== Refresh All Model Lists ==========

    /**
     * Refresh model lists for all providers with API as model source.
     * Returns a map of provider display name to model count.
     * Uses 24-hour cache per provider unless forceRefresh is true.
     */
    suspend fun refreshAllModelLists(settings: AiSettings, forceRefresh: Boolean = false, onProgress: ((String) -> Unit)? = null): Map<String, Int> {
        val results = mutableMapOf<String, Int>()

        for (service in AiService.entries) {
            if (settings.getModelSource(service) == ModelSource.API && settings.getApiKey(service).isNotBlank()) {
                if (forceRefresh || !settingsPrefs.isModelListCacheValid(service)) {
                    onProgress?.invoke(service.displayName)
                    try {
                        val models = aiAnalysisRepository.fetchModels(service, settings.getApiKey(service))
                        _uiState.update { state ->
                            state.copy(aiSettings = state.aiSettings.withModels(service, models))
                        }
                        settingsPrefs.saveModelsForProvider(service, models)
                        settingsPrefs.updateModelListTimestamp(service)
                        results[service.displayName] = models.size
                    } catch (e: Exception) {
                        results[service.displayName] = -1
                    }
                }
            }
        }

        if (results.isNotEmpty()) {
            android.util.Log.d("AiViewModel", "Model lists refreshed for ${results.size} providers")
        }

        return results
    }

    /**
     * Clear the model lists cache, forcing a refresh on next startup or manual refresh.
     */
    fun clearModelListsCache() {
        settingsPrefs.clearModelListsCache()
    }

    // ========== AI Model Testing ==========

    suspend fun testAiModel(service: AiService, apiKey: String, model: String): String? {
        return try {
            val result = aiAnalysisRepository.testModel(service, apiKey, model)
            // Record statistics for successful test (minimal tokens for test prompt)
            if (result == null) {
                settingsPrefs.updateUsageStats(
                    provider = service,
                    model = model,
                    inputTokens = 10,  // Estimated for "Reply with exactly: OK"
                    outputTokens = 2,  // "OK" response
                    totalTokens = 12
                )
            }
            result
        } catch (e: Exception) {
            e.message ?: "Test failed"
        }
    }

    // ========== AI Chat ==========

    fun setChatParameters(params: ChatParameters) {
        _uiState.value = _uiState.value.copy(chatParameters = params)
    }

    // ========== External Intent Instructions ==========

    fun setExternalInstructions(
        closeHtml: String?, reportType: String?, email: String?,
        nextAction: String? = null, returnAfterNext: Boolean = false,
        agentNames: List<String> = emptyList(), flockNames: List<String> = emptyList(),
        swarmNames: List<String> = emptyList(), modelSpecs: List<String> = emptyList(),
        edit: Boolean = false, select: Boolean = false, openHtml: String? = null
    ) {
        _uiState.value = _uiState.value.copy(
            externalCloseHtml = closeHtml,
            externalReportType = reportType,
            externalEmail = email,
            externalNextAction = nextAction,
            externalReturn = returnAfterNext,
            externalEdit = edit,
            externalSelect = select,
            externalOpenHtml = openHtml,
            externalAgentNames = agentNames,
            externalFlockNames = flockNames,
            externalSwarmNames = swarmNames,
            externalModelSpecs = modelSpecs
        )
    }

    fun clearExternalInstructions() {
        _uiState.value = _uiState.value.copy(
            externalCloseHtml = null,
            externalReportType = null,
            externalEmail = null,
            externalNextAction = null,
            externalReturn = false,
            externalEdit = false,
            externalSelect = false,
            externalOpenHtml = null,
            externalAgentNames = emptyList(),
            externalFlockNames = emptyList(),
            externalSwarmNames = emptyList(),
            externalModelSpecs = emptyList()
        )
    }

    // ========== Report Advanced Parameters ==========

    fun setReportAdvancedParameters(params: AiAgentParameters?) {
        _uiState.value = _uiState.value.copy(reportAdvancedParameters = params)
    }

    fun clearReportAdvancedParameters() {
        _uiState.value = _uiState.value.copy(reportAdvancedParameters = null)
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
            // Estimate tokens and record statistics
            val inputTokens = messages.sumOf { estimateTokens(it.content) }
            val outputTokens = estimateTokens(response)
            settingsPrefs.updateUsageStats(
                provider = service,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens
            )
            ChatMessage(role = "assistant", content = response)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Record usage statistics for streaming chat (call after stream completes).
     */
    fun recordChatStatistics(
        service: AiService,
        model: String,
        inputTokens: Int,
        outputTokens: Int
    ) {
        settingsPrefs.updateUsageStats(
            provider = service,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens
        )
    }

    /**
     * Estimate token count from text (roughly 4 characters per token).
     */
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    /**
     * Send a chat message with streaming response.
     * Returns a Flow that emits content chunks as they arrive.
     * @param baseUrl Optional custom endpoint URL. If null, uses the provider's default URL.
     */
    fun sendChatMessageStream(
        service: AiService,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        baseUrl: String? = null
    ): Flow<String> {
        return aiAnalysisRepository.sendChatMessageStream(
            service = service,
            apiKey = apiKey,
            model = model,
            messages = messages,
            params = _uiState.value.chatParameters,
            baseUrl = baseUrl
        ).flowOn(Dispatchers.IO)
    }

    companion object {
        private const val AI_REPORT_AGENTS_KEY = "ai_report_agents_v2"
        private const val AI_REPORT_SWARMS_KEY = "ai_report_swarms_v2"
        private const val AI_REPORT_FLOCKS_KEY = "ai_report_flocks_v2"
        private const val AI_REPORT_MODELS_KEY = "ai_report_models_v2"
    }
}
