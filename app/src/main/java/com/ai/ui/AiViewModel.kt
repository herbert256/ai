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
import kotlinx.coroutines.launch

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

        // Initialize AiReportStorage for tracking report generation
        AiReportStorage.init(application)

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
        val claudeApiModels = settingsPrefs.loadClaudeApiModels()
        val siliconFlowApiModels = settingsPrefs.loadSiliconFlowApiModels()
        val zaiApiModels = settingsPrefs.loadZaiApiModels()
        val moonshotApiModels = settingsPrefs.loadMoonshotApiModels()
        val cohereApiModels = settingsPrefs.loadCohereApiModels()
        val ai21ApiModels = settingsPrefs.loadAi21ApiModels()
        val dashScopeApiModels = settingsPrefs.loadDashScopeApiModels()
        val fireworksApiModels = settingsPrefs.loadFireworksApiModels()
        val cerebrasApiModels = settingsPrefs.loadCerebrasApiModels()
        val sambaNovaApiModels = settingsPrefs.loadSambaNovaApiModels()
        val baichuanApiModels = settingsPrefs.loadBaichuanApiModels()
        val stepFunApiModels = settingsPrefs.loadStepFunApiModels()
        val miniMaxApiModels = settingsPrefs.loadMiniMaxApiModels()
        val nvidiaApiModels = settingsPrefs.loadNvidiaApiModels()
        val replicateApiModels = settingsPrefs.loadReplicateApiModels()
        val huggingFaceInferenceApiModels = settingsPrefs.loadHuggingFaceInferenceApiModels()
        val lambdaApiModels = settingsPrefs.loadLambdaApiModels()
        val leptonApiModels = settingsPrefs.loadLeptonApiModels()
        val yiApiModels = settingsPrefs.loadYiApiModels()
        val doubaoApiModels = settingsPrefs.loadDoubaoApiModels()
        val rekaApiModels = settingsPrefs.loadRekaApiModels()
        val writerApiModels = settingsPrefs.loadWriterApiModels()

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
            availableClaudeModels = claudeApiModels,
            availableSiliconFlowModels = siliconFlowApiModels,
            availableZaiModels = zaiApiModels,
            availableMoonshotModels = moonshotApiModels,
            availableCohereModels = cohereApiModels,
            availableAi21Models = ai21ApiModels,
            availableDashScopeModels = dashScopeApiModels,
            availableFireworksModels = fireworksApiModels,
            availableCerebrasModels = cerebrasApiModels,
            availableSambaNovaModels = sambaNovaApiModels,
            availableBaichuanModels = baichuanApiModels,
            availableStepFunModels = stepFunApiModels,
            availableMiniMaxModels = miniMaxApiModels,
            availableNvidiaModels = nvidiaApiModels,
            availableReplicateModels = replicateApiModels,
            availableHuggingFaceInferenceModels = huggingFaceInferenceApiModels,
            availableLambdaModels = lambdaApiModels,
            availableLeptonModels = leptonApiModels,
            availableYiModels = yiApiModels,
            availableDoubaoModels = doubaoApiModels,
            availableRekaModels = rekaApiModels,
            availableWriterModels = writerApiModels
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

    fun generateGenericAiReports(selectedAgentIds: Set<String>, selectedSwarmIds: Set<String> = emptySet(), directModelIds: Set<String> = emptySet(), parametersIds: List<String> = emptyList()) {
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
                "swarm:${member.provider.name}:${member.model}"
            }.toSet()

            // Filter direct model IDs to exclude those already in swarms
            val uniqueDirectModelIds = directModelIds.filter { it !in swarmMemberIds }.toSet()

            // Parse direct model IDs into AiSwarmMember-like structures
            val directModels = uniqueDirectModelIds.mapNotNull { modelId ->
                // Parse synthetic ID: "swarm:PROVIDER:model"
                val parts = modelId.removePrefix("swarm:").split(":", limit = 2)
                val providerName = parts.getOrNull(0) ?: return@mapNotNull null
                val modelName = parts.getOrNull(1) ?: return@mapNotNull null
                val provider = com.ai.data.AiService.entries.find { it.name == providerName } ?: return@mapNotNull null
                AiSwarmMember(provider, modelName)
            }

            // Combine swarm members and direct models
            val allModelMembers = swarmMembers + directModels
            val allModelIds = swarmMemberIds + uniqueDirectModelIds

            // Total worker count
            val totalWorkers = agents.size + allModelMembers.size

            _uiState.value = _uiState.value.copy(
                showGenericAiAgentSelection = false,
                showGenericAiReportsDialog = true,
                genericAiReportsProgress = 0,
                genericAiReportsTotal = totalWorkers,
                genericAiReportsSelectedAgents = selectedAgentIds + allModelIds,
                genericAiReportsAgentResults = emptyMap(),
                currentReportId = null  // Will be set after report creation
            )

            // Create AI Report objects for agents
            val reportAgents = agents.map { agent ->
                AiReportAgent(
                    agentId = agent.id,
                    agentName = agent.name,
                    provider = agent.provider.name,
                    model = agent.model,
                    reportStatus = ReportStatus.PENDING
                )
            }

            // Create AI Report objects for all model members (from swarms and direct selection)
            val reportModelMembers = allModelMembers.map { member ->
                val syntheticId = "swarm:${member.provider.name}:${member.model}"
                AiReportAgent(
                    agentId = syntheticId,
                    agentName = "${member.provider.displayName} / ${member.model}",
                    provider = member.provider.name,
                    model = member.model,
                    reportStatus = ReportStatus.PENDING
                )
            }

            val allReportAgents = reportAgents + reportModelMembers

            // Extract <user>...</user> content from prompt if present
            // Content inside tags goes to HTML export, tags are stripped from AI prompt
            val userTagRegex = Regex("""<user>(.*?)</user>""", RegexOption.DOT_MATCHES_ALL)
            val userMatch = userTagRegex.find(prompt)
            val rapportText = userMatch?.groupValues?.get(1)?.trim()
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
                rapportText = rapportText
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
                        aiAnalysisRepository.analyzePositionWithAgent(
                            agent = effectiveAgent,
                            fen = "",  // No FEN for generic prompts
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
                    val syntheticId = "swarm:${member.provider.name}:${member.model}"

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
                        aiAnalysisRepository.analyzePositionWithAgent(
                            agent = tempAgent,
                            fen = "",  // No FEN for generic prompts
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
                    service = agent?.provider ?: com.ai.data.AiService.OPENAI,
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

    fun fetchClaudeModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingClaudeModels = true)
            try {
                val models = aiAnalysisRepository.fetchClaudeModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableClaudeModels = models,
                    isLoadingClaudeModels = false
                )
                // Persist the fetched models
                settingsPrefs.saveClaudeApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingClaudeModels = false)
            }
        }
    }

    fun fetchSiliconFlowModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingSiliconFlowModels = true)
            try {
                val models = aiAnalysisRepository.fetchSiliconFlowModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableSiliconFlowModels = models,
                    isLoadingSiliconFlowModels = false
                )
                // Persist the fetched models
                settingsPrefs.saveSiliconFlowApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingSiliconFlowModels = false)
            }
        }
    }

    fun fetchZaiModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingZaiModels = true)
            try {
                val models = aiAnalysisRepository.fetchZaiModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableZaiModels = models,
                    isLoadingZaiModels = false
                )
                // Persist the fetched models
                settingsPrefs.saveZaiApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingZaiModels = false)
            }
        }
    }

    fun fetchMoonshotModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMoonshotModels = true)
            try {
                val models = aiAnalysisRepository.fetchMoonshotModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableMoonshotModels = models,
                    isLoadingMoonshotModels = false
                )
                // Persist the fetched models
                settingsPrefs.saveMoonshotApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMoonshotModels = false)
            }
        }
    }

    fun fetchCohereModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingCohereModels = true)
            try {
                val models = aiAnalysisRepository.fetchCohereModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableCohereModels = models,
                    isLoadingCohereModels = false
                )
                settingsPrefs.saveCohereApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingCohereModels = false)
            }
        }
    }

    fun fetchAi21Models(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingAi21Models = true)
            try {
                val models = aiAnalysisRepository.fetchAi21Models(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableAi21Models = models,
                    isLoadingAi21Models = false
                )
                settingsPrefs.saveAi21ApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingAi21Models = false)
            }
        }
    }

    fun fetchDashScopeModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDashScopeModels = true)
            try {
                val models = aiAnalysisRepository.fetchDashScopeModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableDashScopeModels = models,
                    isLoadingDashScopeModels = false
                )
                settingsPrefs.saveDashScopeApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingDashScopeModels = false)
            }
        }
    }

    fun fetchFireworksModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingFireworksModels = true)
            try {
                val models = aiAnalysisRepository.fetchFireworksModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableFireworksModels = models,
                    isLoadingFireworksModels = false
                )
                settingsPrefs.saveFireworksApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingFireworksModels = false)
            }
        }
    }

    fun fetchCerebrasModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingCerebrasModels = true)
            try {
                val models = aiAnalysisRepository.fetchCerebrasModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableCerebrasModels = models,
                    isLoadingCerebrasModels = false
                )
                settingsPrefs.saveCerebrasApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingCerebrasModels = false)
            }
        }
    }

    fun fetchSambaNovaModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingSambaNovaModels = true)
            try {
                val models = aiAnalysisRepository.fetchSambaNovaModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableSambaNovaModels = models,
                    isLoadingSambaNovaModels = false
                )
                settingsPrefs.saveSambaNovaApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingSambaNovaModels = false)
            }
        }
    }

    fun fetchBaichuanModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingBaichuanModels = true)
            try {
                val models = aiAnalysisRepository.fetchBaichuanModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableBaichuanModels = models,
                    isLoadingBaichuanModels = false
                )
                settingsPrefs.saveBaichuanApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingBaichuanModels = false)
            }
        }
    }

    fun fetchStepFunModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingStepFunModels = true)
            try {
                val models = aiAnalysisRepository.fetchStepFunModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableStepFunModels = models,
                    isLoadingStepFunModels = false
                )
                settingsPrefs.saveStepFunApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingStepFunModels = false)
            }
        }
    }

    fun fetchMiniMaxModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMiniMaxModels = true)
            try {
                val models = aiAnalysisRepository.fetchMiniMaxModels(apiKey)
                _uiState.value = _uiState.value.copy(
                    availableMiniMaxModels = models,
                    isLoadingMiniMaxModels = false
                )
                settingsPrefs.saveMiniMaxApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMiniMaxModels = false)
            }
        }
    }

    fun fetchNvidiaModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingNvidiaModels = true)
            try {
                val models = aiAnalysisRepository.fetchNvidiaModels(apiKey)
                _uiState.value = _uiState.value.copy(availableNvidiaModels = models, isLoadingNvidiaModels = false)
                settingsPrefs.saveNvidiaApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingNvidiaModels = false)
            }
        }
    }

    fun fetchReplicateModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingReplicateModels = true)
            try {
                val models = aiAnalysisRepository.fetchReplicateModels(apiKey)
                _uiState.value = _uiState.value.copy(availableReplicateModels = models, isLoadingReplicateModels = false)
                settingsPrefs.saveReplicateApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingReplicateModels = false)
            }
        }
    }

    fun fetchHuggingFaceInferenceModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingHuggingFaceInferenceModels = true)
            try {
                val models = aiAnalysisRepository.fetchHuggingFaceInferenceModels(apiKey)
                _uiState.value = _uiState.value.copy(availableHuggingFaceInferenceModels = models, isLoadingHuggingFaceInferenceModels = false)
                settingsPrefs.saveHuggingFaceInferenceApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingHuggingFaceInferenceModels = false)
            }
        }
    }

    fun fetchLambdaModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingLambdaModels = true)
            try {
                val models = aiAnalysisRepository.fetchLambdaModels(apiKey)
                _uiState.value = _uiState.value.copy(availableLambdaModels = models, isLoadingLambdaModels = false)
                settingsPrefs.saveLambdaApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingLambdaModels = false)
            }
        }
    }

    fun fetchLeptonModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingLeptonModels = true)
            try {
                val models = aiAnalysisRepository.fetchLeptonModels(apiKey)
                _uiState.value = _uiState.value.copy(availableLeptonModels = models, isLoadingLeptonModels = false)
                settingsPrefs.saveLeptonApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingLeptonModels = false)
            }
        }
    }

    fun fetchYiModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingYiModels = true)
            try {
                val models = aiAnalysisRepository.fetchYiModels(apiKey)
                _uiState.value = _uiState.value.copy(availableYiModels = models, isLoadingYiModels = false)
                settingsPrefs.saveYiApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingYiModels = false)
            }
        }
    }

    fun fetchDoubaoModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDoubaoModels = true)
            try {
                val models = aiAnalysisRepository.fetchDoubaoModels(apiKey)
                _uiState.value = _uiState.value.copy(availableDoubaoModels = models, isLoadingDoubaoModels = false)
                settingsPrefs.saveDoubaoApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingDoubaoModels = false)
            }
        }
    }

    fun fetchRekaModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingRekaModels = true)
            try {
                val models = aiAnalysisRepository.fetchRekaModels(apiKey)
                _uiState.value = _uiState.value.copy(availableRekaModels = models, isLoadingRekaModels = false)
                settingsPrefs.saveRekaApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingRekaModels = false)
            }
        }
    }

    fun fetchWriterModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingWriterModels = true)
            try {
                val models = aiAnalysisRepository.fetchWriterModels(apiKey)
                _uiState.value = _uiState.value.copy(availableWriterModels = models, isLoadingWriterModels = false)
                settingsPrefs.saveWriterApiModels(models)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingWriterModels = false)
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

        // OpenAI
        if (settings.chatGptModelSource == ModelSource.API && settings.chatGptApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.OPENAI)) {
                onProgress?.invoke("OpenAI")
                try {
                    val models = aiAnalysisRepository.fetchChatGptModels(settings.chatGptApiKey)
                    _uiState.value = _uiState.value.copy(availableChatGptModels = models)
                    settingsPrefs.saveChatGptApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.OPENAI)
                    results["OpenAI"] = models.size
                } catch (e: Exception) {
                    results["OpenAI"] = -1  // -1 indicates error
                }
            }
        }

        // Google
        if (settings.geminiModelSource == ModelSource.API && settings.geminiApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.GOOGLE)) {
                onProgress?.invoke("Google")
                try {
                    val models = aiAnalysisRepository.fetchGeminiModels(settings.geminiApiKey)
                    _uiState.value = _uiState.value.copy(availableGeminiModels = models)
                    settingsPrefs.saveGeminiApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.GOOGLE)
                    results["Google"] = models.size
                } catch (e: Exception) {
                    results["Google"] = -1
                }
            }
        }

        // xAI
        if (settings.grokModelSource == ModelSource.API && settings.grokApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.XAI)) {
                onProgress?.invoke("xAI")
                try {
                    val models = aiAnalysisRepository.fetchGrokModels(settings.grokApiKey)
                    _uiState.value = _uiState.value.copy(availableGrokModels = models)
                    settingsPrefs.saveGrokApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.XAI)
                    results["xAI"] = models.size
                } catch (e: Exception) {
                    results["xAI"] = -1
                }
            }
        }

        // Groq
        if (settings.groqModelSource == ModelSource.API && settings.groqApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.GROQ)) {
                onProgress?.invoke("Groq")
                try {
                    val models = aiAnalysisRepository.fetchGroqModels(settings.groqApiKey)
                    _uiState.value = _uiState.value.copy(availableGroqModels = models)
                    settingsPrefs.saveGroqApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.GROQ)
                    results["Groq"] = models.size
                } catch (e: Exception) {
                    results["Groq"] = -1
                }
            }
        }

        // DeepSeek
        if (settings.deepSeekModelSource == ModelSource.API && settings.deepSeekApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.DEEPSEEK)) {
                onProgress?.invoke("DeepSeek")
                try {
                    val models = aiAnalysisRepository.fetchDeepSeekModels(settings.deepSeekApiKey)
                    _uiState.value = _uiState.value.copy(availableDeepSeekModels = models)
                    settingsPrefs.saveDeepSeekApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.DEEPSEEK)
                    results["DeepSeek"] = models.size
                } catch (e: Exception) {
                    results["DeepSeek"] = -1
                }
            }
        }

        // Mistral
        if (settings.mistralModelSource == ModelSource.API && settings.mistralApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.MISTRAL)) {
                onProgress?.invoke("Mistral")
                try {
                    val models = aiAnalysisRepository.fetchMistralModels(settings.mistralApiKey)
                    _uiState.value = _uiState.value.copy(availableMistralModels = models)
                    settingsPrefs.saveMistralApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.MISTRAL)
                    results["Mistral"] = models.size
                } catch (e: Exception) {
                    results["Mistral"] = -1
                }
            }
        }

        // Together
        if (settings.togetherModelSource == ModelSource.API && settings.togetherApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.TOGETHER)) {
                onProgress?.invoke("Together")
                try {
                    val models = aiAnalysisRepository.fetchTogetherModels(settings.togetherApiKey)
                    _uiState.value = _uiState.value.copy(availableTogetherModels = models)
                    settingsPrefs.saveTogetherApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.TOGETHER)
                    results["Together"] = models.size
                } catch (e: Exception) {
                    results["Together"] = -1
                }
            }
        }

        // OpenRouter
        if (settings.openRouterModelSource == ModelSource.API && settings.openRouterApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.OPENROUTER)) {
                onProgress?.invoke("OpenRouter")
                try {
                    val models = aiAnalysisRepository.fetchOpenRouterModels(settings.openRouterApiKey)
                    _uiState.value = _uiState.value.copy(availableOpenRouterModels = models)
                    settingsPrefs.saveOpenRouterApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.OPENROUTER)
                    results["OpenRouter"] = models.size
                } catch (e: Exception) {
                    results["OpenRouter"] = -1
                }
            }
        }

        // Anthropic/Claude
        if (settings.claudeModelSource == ModelSource.API && settings.claudeApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.ANTHROPIC)) {
                onProgress?.invoke("Anthropic")
                try {
                    val models = aiAnalysisRepository.fetchClaudeModels(settings.claudeApiKey)
                    _uiState.value = _uiState.value.copy(availableClaudeModels = models)
                    settingsPrefs.saveClaudeApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.ANTHROPIC)
                    results["Anthropic"] = models.size
                } catch (e: Exception) {
                    results["Anthropic"] = -1
                }
            }
        }

        // SiliconFlow
        if (settings.siliconFlowModelSource == ModelSource.API && settings.siliconFlowApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.SILICONFLOW)) {
                onProgress?.invoke("SiliconFlow")
                try {
                    val models = aiAnalysisRepository.fetchSiliconFlowModels(settings.siliconFlowApiKey)
                    _uiState.value = _uiState.value.copy(availableSiliconFlowModels = models)
                    settingsPrefs.saveSiliconFlowApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.SILICONFLOW)
                    results["SiliconFlow"] = models.size
                } catch (e: Exception) {
                    results["SiliconFlow"] = -1
                }
            }
        }

        // Z.AI
        if (settings.zaiModelSource == ModelSource.API && settings.zaiApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.ZAI)) {
                onProgress?.invoke("Z.AI")
                try {
                    val models = aiAnalysisRepository.fetchZaiModels(settings.zaiApiKey)
                    _uiState.value = _uiState.value.copy(availableZaiModels = models)
                    settingsPrefs.saveZaiApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.ZAI)
                    results["Z.AI"] = models.size
                } catch (e: Exception) {
                    results["Z.AI"] = -1
                }
            }
        }

        // Moonshot
        if (settings.moonshotModelSource == ModelSource.API && settings.moonshotApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.MOONSHOT)) {
                onProgress?.invoke("Moonshot")
                try {
                    val models = aiAnalysisRepository.fetchMoonshotModels(settings.moonshotApiKey)
                    _uiState.value = _uiState.value.copy(availableMoonshotModels = models)
                    settingsPrefs.saveMoonshotApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.MOONSHOT)
                    results["Moonshot"] = models.size
                } catch (e: Exception) {
                    results["Moonshot"] = -1
                }
            }
        }

        // Cohere
        if (settings.cohereModelSource == ModelSource.API && settings.cohereApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.COHERE)) {
                onProgress?.invoke("Cohere")
                try {
                    val models = aiAnalysisRepository.fetchCohereModels(settings.cohereApiKey)
                    _uiState.value = _uiState.value.copy(availableCohereModels = models)
                    settingsPrefs.saveCohereApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.COHERE)
                    results["Cohere"] = models.size
                } catch (e: Exception) {
                    results["Cohere"] = -1
                }
            }
        }

        // AI21
        if (settings.ai21ModelSource == ModelSource.API && settings.ai21ApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.AI21)) {
                onProgress?.invoke("AI21")
                try {
                    val models = aiAnalysisRepository.fetchAi21Models(settings.ai21ApiKey)
                    _uiState.value = _uiState.value.copy(availableAi21Models = models)
                    settingsPrefs.saveAi21ApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.AI21)
                    results["AI21"] = models.size
                } catch (e: Exception) {
                    results["AI21"] = -1
                }
            }
        }

        // DashScope
        if (settings.dashScopeModelSource == ModelSource.API && settings.dashScopeApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.DASHSCOPE)) {
                onProgress?.invoke("DashScope")
                try {
                    val models = aiAnalysisRepository.fetchDashScopeModels(settings.dashScopeApiKey)
                    _uiState.value = _uiState.value.copy(availableDashScopeModels = models)
                    settingsPrefs.saveDashScopeApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.DASHSCOPE)
                    results["DashScope"] = models.size
                } catch (e: Exception) {
                    results["DashScope"] = -1
                }
            }
        }

        // Fireworks
        if (settings.fireworksModelSource == ModelSource.API && settings.fireworksApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.FIREWORKS)) {
                onProgress?.invoke("Fireworks")
                try {
                    val models = aiAnalysisRepository.fetchFireworksModels(settings.fireworksApiKey)
                    _uiState.value = _uiState.value.copy(availableFireworksModels = models)
                    settingsPrefs.saveFireworksApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.FIREWORKS)
                    results["Fireworks"] = models.size
                } catch (e: Exception) {
                    results["Fireworks"] = -1
                }
            }
        }

        // Cerebras
        if (settings.cerebrasModelSource == ModelSource.API && settings.cerebrasApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.CEREBRAS)) {
                onProgress?.invoke("Cerebras")
                try {
                    val models = aiAnalysisRepository.fetchCerebrasModels(settings.cerebrasApiKey)
                    _uiState.value = _uiState.value.copy(availableCerebrasModels = models)
                    settingsPrefs.saveCerebrasApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.CEREBRAS)
                    results["Cerebras"] = models.size
                } catch (e: Exception) {
                    results["Cerebras"] = -1
                }
            }
        }

        // SambaNova
        if (settings.sambaNovaModelSource == ModelSource.API && settings.sambaNovaApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.SAMBANOVA)) {
                onProgress?.invoke("SambaNova")
                try {
                    val models = aiAnalysisRepository.fetchSambaNovaModels(settings.sambaNovaApiKey)
                    _uiState.value = _uiState.value.copy(availableSambaNovaModels = models)
                    settingsPrefs.saveSambaNovaApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.SAMBANOVA)
                    results["SambaNova"] = models.size
                } catch (e: Exception) {
                    results["SambaNova"] = -1
                }
            }
        }

        // Baichuan
        if (settings.baichuanModelSource == ModelSource.API && settings.baichuanApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.BAICHUAN)) {
                onProgress?.invoke("Baichuan")
                try {
                    val models = aiAnalysisRepository.fetchBaichuanModels(settings.baichuanApiKey)
                    _uiState.value = _uiState.value.copy(availableBaichuanModels = models)
                    settingsPrefs.saveBaichuanApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.BAICHUAN)
                    results["Baichuan"] = models.size
                } catch (e: Exception) {
                    results["Baichuan"] = -1
                }
            }
        }

        // StepFun
        if (settings.stepFunModelSource == ModelSource.API && settings.stepFunApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.STEPFUN)) {
                onProgress?.invoke("StepFun")
                try {
                    val models = aiAnalysisRepository.fetchStepFunModels(settings.stepFunApiKey)
                    _uiState.value = _uiState.value.copy(availableStepFunModels = models)
                    settingsPrefs.saveStepFunApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.STEPFUN)
                    results["StepFun"] = models.size
                } catch (e: Exception) {
                    results["StepFun"] = -1
                }
            }
        }

        // MiniMax
        if (settings.miniMaxModelSource == ModelSource.API && settings.miniMaxApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.MINIMAX)) {
                onProgress?.invoke("MiniMax")
                try {
                    val models = aiAnalysisRepository.fetchMiniMaxModels(settings.miniMaxApiKey)
                    _uiState.value = _uiState.value.copy(availableMiniMaxModels = models)
                    settingsPrefs.saveMiniMaxApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.MINIMAX)
                    results["MiniMax"] = models.size
                } catch (e: Exception) {
                    results["MiniMax"] = -1
                }
            }
        }

        // NVIDIA
        if (settings.nvidiaModelSource == ModelSource.API && settings.nvidiaApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.NVIDIA)) {
                onProgress?.invoke("NVIDIA")
                try {
                    val models = aiAnalysisRepository.fetchNvidiaModels(settings.nvidiaApiKey)
                    _uiState.value = _uiState.value.copy(availableNvidiaModels = models)
                    settingsPrefs.saveNvidiaApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.NVIDIA)
                    results["NVIDIA"] = models.size
                } catch (e: Exception) {
                    results["NVIDIA"] = -1
                }
            }
        }

        // Replicate
        if (settings.replicateModelSource == ModelSource.API && settings.replicateApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.REPLICATE)) {
                onProgress?.invoke("Replicate")
                try {
                    val models = aiAnalysisRepository.fetchReplicateModels(settings.replicateApiKey)
                    _uiState.value = _uiState.value.copy(availableReplicateModels = models)
                    settingsPrefs.saveReplicateApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.REPLICATE)
                    results["Replicate"] = models.size
                } catch (e: Exception) {
                    results["Replicate"] = -1
                }
            }
        }

        // Hugging Face Inference
        if (settings.huggingFaceInferenceModelSource == ModelSource.API && settings.huggingFaceInferenceApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.HUGGINGFACE)) {
                onProgress?.invoke("Hugging Face")
                try {
                    val models = aiAnalysisRepository.fetchHuggingFaceInferenceModels(settings.huggingFaceInferenceApiKey)
                    _uiState.value = _uiState.value.copy(availableHuggingFaceInferenceModels = models)
                    settingsPrefs.saveHuggingFaceInferenceApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.HUGGINGFACE)
                    results["Hugging Face"] = models.size
                } catch (e: Exception) {
                    results["Hugging Face"] = -1
                }
            }
        }

        // Lambda
        if (settings.lambdaModelSource == ModelSource.API && settings.lambdaApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.LAMBDA)) {
                onProgress?.invoke("Lambda")
                try {
                    val models = aiAnalysisRepository.fetchLambdaModels(settings.lambdaApiKey)
                    _uiState.value = _uiState.value.copy(availableLambdaModels = models)
                    settingsPrefs.saveLambdaApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.LAMBDA)
                    results["Lambda"] = models.size
                } catch (e: Exception) {
                    results["Lambda"] = -1
                }
            }
        }

        // Lepton
        if (settings.leptonModelSource == ModelSource.API && settings.leptonApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.LEPTON)) {
                onProgress?.invoke("Lepton")
                try {
                    val models = aiAnalysisRepository.fetchLeptonModels(settings.leptonApiKey)
                    _uiState.value = _uiState.value.copy(availableLeptonModels = models)
                    settingsPrefs.saveLeptonApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.LEPTON)
                    results["Lepton"] = models.size
                } catch (e: Exception) {
                    results["Lepton"] = -1
                }
            }
        }

        // YI (01.AI)
        if (settings.yiModelSource == ModelSource.API && settings.yiApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.YI)) {
                onProgress?.invoke("01.AI")
                try {
                    val models = aiAnalysisRepository.fetchYiModels(settings.yiApiKey)
                    _uiState.value = _uiState.value.copy(availableYiModels = models)
                    settingsPrefs.saveYiApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.YI)
                    results["01.AI"] = models.size
                } catch (e: Exception) {
                    results["01.AI"] = -1
                }
            }
        }

        // Doubao
        if (settings.doubaoModelSource == ModelSource.API && settings.doubaoApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.DOUBAO)) {
                onProgress?.invoke("Doubao")
                try {
                    val models = aiAnalysisRepository.fetchDoubaoModels(settings.doubaoApiKey)
                    _uiState.value = _uiState.value.copy(availableDoubaoModels = models)
                    settingsPrefs.saveDoubaoApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.DOUBAO)
                    results["Doubao"] = models.size
                } catch (e: Exception) {
                    results["Doubao"] = -1
                }
            }
        }

        // Reka
        if (settings.rekaModelSource == ModelSource.API && settings.rekaApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.REKA)) {
                onProgress?.invoke("Reka")
                try {
                    val models = aiAnalysisRepository.fetchRekaModels(settings.rekaApiKey)
                    _uiState.value = _uiState.value.copy(availableRekaModels = models)
                    settingsPrefs.saveRekaApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.REKA)
                    results["Reka"] = models.size
                } catch (e: Exception) {
                    results["Reka"] = -1
                }
            }
        }

        // Writer
        if (settings.writerModelSource == ModelSource.API && settings.writerApiKey.isNotBlank()) {
            if (forceRefresh || !settingsPrefs.isModelListCacheValid(AiService.WRITER)) {
                onProgress?.invoke("Writer")
                try {
                    val models = aiAnalysisRepository.fetchWriterModels(settings.writerApiKey)
                    _uiState.value = _uiState.value.copy(availableWriterModels = models)
                    settingsPrefs.saveWriterApiModels(models)
                    settingsPrefs.updateModelListTimestamp(AiService.WRITER)
                    results["Writer"] = models.size
                } catch (e: Exception) {
                    results["Writer"] = -1
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
        private const val AI_REPORT_AGENTS_KEY = "ai_report_agents"
        private const val AI_REPORT_SWARMS_KEY = "ai_report_flocks"
        private const val AI_REPORT_FLOCKS_KEY = "ai_report_swarms"
        private const val AI_REPORT_MODELS_KEY = "ai_report_models"
    }
}
