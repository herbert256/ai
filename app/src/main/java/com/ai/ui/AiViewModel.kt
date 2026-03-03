package com.ai.ui

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val aiAnalysisRepository = AiAnalysisRepository()
    private val prefs = application.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    // Helper classes for settings
    private val settingsPrefs = SettingsPreferences(prefs, application.filesDir)

    private val _uiState = MutableStateFlow(AiUiState())
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()
    private var reportGenerationJob: Job? = null
    @Volatile private var reportRunningInBackground = false

    // Settings persistence
    private fun loadGeneralSettings(): GeneralSettings = settingsPrefs.loadGeneralSettings()
    private fun saveGeneralSettings(settings: GeneralSettings) = settingsPrefs.saveGeneralSettings(settings)
    private fun loadAiSettings(): AiSettings = settingsPrefs.loadAiSettingsWithMigration()
    private fun saveAiSettings(settings: AiSettings) = settingsPrefs.saveAiSettings(settings)

    private data class BootstrapState(
        val generalSettings: GeneralSettings,
        val aiSettings: AiSettings
    )

    private data class ReportTask(
        val resultId: String,
        val reportAgent: AiReportAgent,
        val runtimeAgent: AiAgent,
        val resolvedParams: AiAgentParameters
    )

    init {
        ApiTracer.isTracingEnabled = true
        viewModelScope.launch(Dispatchers.IO) {
            val bootstrapState = bootstrap(application)
            _uiState.update {
                it.copy(
                    generalSettings = bootstrapState.generalSettings,
                    aiSettings = bootstrapState.aiSettings
                )
            }
            refreshAllModelLists(bootstrapState.aiSettings)
        }
    }

    override fun onCleared() {
        super.onCleared()
        reportGenerationJob?.cancel()
        reportGenerationJob = null
        ApiTracer.currentReportId = null
    }

    private fun bootstrap(application: Application): BootstrapState {
        ApiTracer.init(application)
        ChatHistoryManager.init(application)
        AiReportStorage.init(application)
        ProviderRegistry.init(application)

        var generalSettings = loadGeneralSettings()
        var aiSettings = loadAiSettings()

        val setupImported = prefs.getBoolean("setup_imported", false)
        if (!setupImported) {
            val result = importAiConfigFromAsset(application, "setup.json", aiSettings)
            if (result != null) {
                aiSettings = result.aiSettings
                saveAiSettings(aiSettings)
                val updatedGs = generalSettings.copy(
                    huggingFaceApiKey = result.huggingFaceApiKey ?: generalSettings.huggingFaceApiKey,
                    openRouterApiKey = result.openRouterApiKey ?: generalSettings.openRouterApiKey
                )
                if (updatedGs != generalSettings) {
                    generalSettings = updatedGs
                    saveGeneralSettings(generalSettings)
                }
            }
            prefs.edit { putBoolean("setup_imported", true) }
        }

        return BootstrapState(
            generalSettings = generalSettings,
            aiSettings = aiSettings
        )
    }

    // ========== Settings Management ==========

    fun updateGeneralSettings(settings: GeneralSettings) {
        _uiState.update { it.copy(generalSettings = settings) }
        viewModelScope.launch(Dispatchers.IO) {
            saveGeneralSettings(settings)
        }
    }

    fun updateAiSettings(settings: AiSettings) {
        _uiState.update { it.copy(aiSettings = settings) }
        viewModelScope.launch(Dispatchers.IO) {
            saveAiSettings(settings)
        }
    }

    fun updateProviderState(service: AiService, state: String) {
        val updated = _uiState.value.aiSettings.withProviderState(service, state)
        _uiState.update { it.copy(aiSettings = updated) }
        viewModelScope.launch(Dispatchers.IO) {
            saveAiSettings(updated)
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
        prefs.edit { putStringSet(AI_REPORT_AGENTS_KEY, agentIds) }
    }

    // ========== AI Reports Direct Model Selection ==========

    fun loadAiReportModels(): Set<String> {
        val stored = prefs.getStringSet(AI_REPORT_MODELS_KEY, emptySet()) ?: emptySet()
        return stored.toSet()
    }

    fun saveAiReportModels(modelIds: Set<String>) {
        prefs.edit { putStringSet(AI_REPORT_MODELS_KEY, modelIds) }
    }

    // ========== Generic AI Reports ==========

    fun showGenericAiAgentSelection(title: String, prompt: String) {
        _uiState.update { it.copy(
            genericAiPromptTitle = title,
            genericAiPromptText = prompt,
            showGenericAiAgentSelection = true,
            showGenericAiReportsDialog = false,
            genericAiReportsProgress = 0,
            genericAiReportsTotal = 0,
            genericAiReportsSelectedAgents = emptySet(),
            genericAiReportsAgentResults = emptyMap(),
            currentReportId = null
        ) }
    }

    fun dismissGenericAiAgentSelection() {
        _uiState.update { it.copy(showGenericAiAgentSelection = false) }
    }

    /**
     * Resolve the effective system prompt text for an agent.
     * Priority: flock/swarm systemPromptId > agent systemPromptId.
     */
    private fun resolveSystemPromptText(aiSettings: AiSettings, agentSystemPromptId: String?, groupSystemPromptId: String?): String? {
        val effectiveId = groupSystemPromptId ?: agentSystemPromptId
        return effectiveId?.let { aiSettings.getSystemPromptById(it)?.prompt }
    }

    /**
     * Find the flock that contains a given agent ID and return the flock's systemPromptId.
     * Only returns a systemPromptId from flocks that actually have one set.
     * If multiple flocks contain this agent with system prompts, the first valid one wins.
     */
    private fun findFlockSystemPromptIdForAgent(aiSettings: AiSettings, agentId: String): String? {
        return aiSettings.flocks
            .filter { agentId in it.agentIds && it.systemPromptId != null }
            .firstNotNullOfOrNull { flock ->
                flock.systemPromptId?.takeIf { aiSettings.getSystemPromptById(it) != null }
            }
    }

    /**
     * Find the swarm that contains a given provider/model member and return the swarm's systemPromptId.
     * Only returns a systemPromptId from swarms that actually have one set and valid.
     */
    private fun findSwarmSystemPromptIdForMember(aiSettings: AiSettings, provider: AiService, model: String): String? {
        return aiSettings.swarms
            .filter { swarm ->
                swarm.systemPromptId != null &&
                swarm.members.any { it.provider.id == provider.id && it.model == model }
            }
            .firstNotNullOfOrNull { swarm ->
                swarm.systemPromptId?.takeIf { aiSettings.getSystemPromptById(it) != null }
            }
    }

    fun generateGenericAiReports(selectedAgentIds: Set<String>, selectedSwarmIds: Set<String> = emptySet(), directModelIds: Set<String> = emptySet(), parametersIds: List<String> = emptyList(), reportType: com.ai.data.ReportType = com.ai.data.ReportType.CLASSIC) {
        reportGenerationJob?.cancel()
        reportGenerationJob = viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val aiSettings = _uiState.value.aiSettings
            val prompt = _uiState.value.genericAiPromptText
            val title = _uiState.value.genericAiPromptTitle
            val externalSystemPrompt = _uiState.value.externalSystemPrompt
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

            val reportTasks = buildReportTasks(
                aiSettings = aiSettings,
                agents = agents,
                modelMembers = allModelMembers,
                externalSystemPrompt = externalSystemPrompt
            )

            _uiState.update { it.copy(
                showGenericAiAgentSelection = false,
                showGenericAiReportsDialog = true,
                genericAiReportsProgress = 0,
                genericAiReportsTotal = reportTasks.size,
                genericAiReportsSelectedAgents = selectedAgentIds + allModelIds,
                genericAiReportsAgentResults = emptyMap(),
                currentReportId = null
            ) }

            // Extract <user>...</user> content from prompt if present
            // Content inside tags goes to HTML export, tags are stripped from AI prompt
            // Falls back to externalOpenHtml (from <open> tag in <edit> mode)
            val userMatch = USER_TAG_REGEX.find(prompt)
            val rapportText = userMatch?.groupValues?.get(1)?.trim() ?: _uiState.value.externalOpenHtml
            val aiPrompt = if (userMatch != null) {
                prompt.replace(userMatch.value, "").trim()
            } else {
                prompt
            }

            val report = AiReportStorage.createReportAsync(
                context = context,
                title = title.ifBlank { "AI Report" },
                prompt = aiPrompt,
                agents = reportTasks.map { it.reportAgent },
                rapportText = rapportText,
                reportType = reportType,
                closeText = _uiState.value.externalCloseHtml
            )
            val reportId = report.id

            try {
                _uiState.update { it.copy(currentReportId = reportId) }
                ApiTracer.currentReportId = reportId

                val semaphore = Semaphore(REPORT_CONCURRENCY_LIMIT)
                coroutineScope {
                    reportTasks.map { task ->
                        async {
                            semaphore.withPermit {
                                executeReportTask(
                                    context = context,
                                    reportId = reportId,
                                    aiPrompt = aiPrompt,
                                    overrideParams = overrideParams,
                                    task = task
                                )
                            }
                        }
                    }.awaitAll()
                }

                if (reportRunningInBackground) {
                    reportRunningInBackground = false
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(),
                            "Report \"$title\" is ready",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                ApiTracer.currentReportId = null
            }
        }
    }

    private fun buildReportTasks(
        aiSettings: AiSettings,
        agents: List<AiAgent>,
        modelMembers: List<AiSwarmMember>,
        externalSystemPrompt: String?
    ): List<ReportTask> {
        val agentTasks = agents.map { agent ->
            val effectiveAgent = agent.copy(
                apiKey = aiSettings.getEffectiveApiKeyForAgent(agent),
                model = aiSettings.getEffectiveModelForAgent(agent)
            )

            var agentParams = aiSettings.resolveAgentParameters(agent)
            val flockSpId = findFlockSystemPromptIdForAgent(aiSettings, agent.id)
            val spText = resolveSystemPromptText(aiSettings, agent.systemPromptId, flockSpId) ?: externalSystemPrompt
            if (spText != null) {
                agentParams = agentParams.copy(systemPrompt = spText)
            }

            ReportTask(
                resultId = agent.id,
                reportAgent = AiReportAgent(
                    agentId = agent.id,
                    agentName = agent.name,
                    provider = effectiveAgent.provider.id,
                    model = effectiveAgent.model,
                    reportStatus = ReportStatus.PENDING
                ),
                runtimeAgent = effectiveAgent,
                resolvedParams = agentParams
            )
        }

        val modelTasks = modelMembers.map { member ->
            val syntheticId = "swarm:${member.provider.id}:${member.model}"
            val swarmSpId = findSwarmSystemPromptIdForMember(aiSettings, member.provider, member.model)
            val swarmSpText = swarmSpId?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: externalSystemPrompt

            ReportTask(
                resultId = syntheticId,
                reportAgent = AiReportAgent(
                    agentId = syntheticId,
                    agentName = "${member.provider.displayName} / ${member.model}",
                    provider = member.provider.id,
                    model = member.model,
                    reportStatus = ReportStatus.PENDING
                ),
                runtimeAgent = AiAgent(
                    id = syntheticId,
                    name = "${member.provider.displayName} / ${member.model}",
                    provider = member.provider,
                    model = member.model,
                    apiKey = aiSettings.getApiKey(member.provider)
                ),
                resolvedParams = swarmSpText?.let { AiAgentParameters(systemPrompt = it) } ?: AiAgentParameters()
            )
        }

        return agentTasks + modelTasks
    }

    private suspend fun executeReportTask(
        context: Context,
        reportId: String,
        aiPrompt: String,
        overrideParams: AiAgentParameters?,
        task: ReportTask
    ) {
        AiReportStorage.markAgentRunningAsync(
            context = context,
            reportId = reportId,
            agentId = task.resultId,
            requestBody = aiPrompt
        )

        val startTime = System.currentTimeMillis()
        val response = try {
            aiAnalysisRepository.analyzeWithAgent(
                agent = task.runtimeAgent,
                content = "",
                prompt = aiPrompt,
                agentResolvedParams = task.resolvedParams,
                overrideParams = overrideParams,
                context = context
            )
        } catch (e: Exception) {
            AiAnalysisResponse(
                service = task.runtimeAgent.provider,
                analysis = null,
                error = e.message ?: "Unknown error"
            )
        }
        val durationMs = System.currentTimeMillis() - startTime
        val cost = calculateResponseCost(
            context = context,
            provider = task.runtimeAgent.provider,
            model = task.runtimeAgent.model,
            tokenUsage = response.tokenUsage
        )

        if (response.isSuccess) {
            AiReportStorage.markAgentSuccessAsync(
                context = context,
                reportId = reportId,
                agentId = task.resultId,
                httpStatus = response.httpStatusCode ?: 200,
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
            AiReportStorage.markAgentErrorAsync(
                context = context,
                reportId = reportId,
                agentId = task.resultId,
                httpStatus = response.httpStatusCode,
                errorMessage = response.error,
                responseHeaders = response.httpHeaders,
                responseBody = response.analysis,
                durationMs = durationMs
            )
        }

        if (response.error == null && response.tokenUsage != null) {
            val usage = response.tokenUsage
            settingsPrefs.updateUsageStatsAsync(
                provider = task.runtimeAgent.provider,
                model = task.runtimeAgent.model,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                totalTokens = usage.totalTokens
            )
        }

        _uiState.update { state ->
            state.copy(
                genericAiReportsProgress = state.genericAiReportsProgress + 1,
                genericAiReportsAgentResults = state.genericAiReportsAgentResults + (task.resultId to response)
            )
        }
    }

    private fun calculateResponseCost(
        context: Context,
        provider: AiService,
        model: String,
        tokenUsage: TokenUsage?
    ): Double? {
        if (tokenUsage == null) return null
        return tokenUsage.apiCost ?: run {
            val pricing = PricingCache.getPricing(context, provider, model)
            val inputCost = tokenUsage.inputTokens * pricing.promptPrice
            val outputCost = tokenUsage.outputTokens * pricing.completionPrice
            inputCost + outputCost
        }
    }

    fun stopGenericAiReports() {
        reportGenerationJob?.cancel()
        reportGenerationJob = null
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
                val service = agent?.provider ?: agentId
                    .takeIf { it.startsWith("swarm:") }
                    ?.removePrefix("swarm:")
                    ?.substringBefore(':')
                    ?.let { com.ai.data.AiService.findById(it) }
                    ?: com.ai.data.AiService.entries.firstOrNull()
                    ?: com.ai.data.AiService.findById("OPENAI")!!

                // Mark as stopped in storage if we have a reportId
                agentId to AiAnalysisResponse(
                    service = service,
                    analysis = "Not ready",
                    error = null
                )
            }
        }

        // Clear the current report ID for API tracing
        ApiTracer.currentReportId = null

        if (reportId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                selectedAgents
                    .filterNot { currentResults.containsKey(it) }
                    .forEach { agentId ->
                        AiReportStorage.markAgentStoppedAsync(
                            context = context,
                            reportId = reportId,
                            agentId = agentId
                        )
                    }
            }
        }

        _uiState.update { it.copy(
            genericAiReportsProgress = currentState.genericAiReportsTotal,
            genericAiReportsAgentResults = updatedResults
        ) }
    }

    fun dismissGenericAiReportsDialog() {
        ApiTracer.currentReportId = null
        _uiState.update { it.copy(
            showGenericAiReportsDialog = false,
            genericAiPromptTitle = "",
            genericAiPromptText = "",
            genericAiReportsProgress = 0,
            genericAiReportsTotal = 0,
            genericAiReportsSelectedAgents = emptySet(),
            genericAiReportsAgentResults = emptyMap(),
            currentReportId = null,
            reportAdvancedParameters = null
        ) }
    }

    fun continueReportInBackground() {
        reportRunningInBackground = true
        _uiState.update { it.copy(showGenericAiReportsDialog = false) }
    }

    // ========== Model Fetching ==========

    fun fetchModels(service: AiService, apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
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
                android.util.Log.w("AiViewModel", "Failed to fetch models for ${service.displayName}: ${e.message}")
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
        return withContext(Dispatchers.IO) {
            val servicesToRefresh = AiService.entries.filter { service ->
                settings.getModelSource(service) == ModelSource.API &&
                settings.getApiKey(service).isNotBlank() &&
                (forceRefresh || !settingsPrefs.isModelListCacheValid(service))
            }

            if (servicesToRefresh.isEmpty()) return@withContext emptyMap()

            val results = coroutineScope {
                servicesToRefresh.map { service ->
                    async {
                        onProgress?.invoke(service.displayName)
                        try {
                            val models = aiAnalysisRepository.fetchModels(service, settings.getApiKey(service))
                            _uiState.update { state ->
                                state.copy(aiSettings = state.aiSettings.withModels(service, models))
                            }
                            settingsPrefs.saveModelsForProvider(service, models)
                            service to models.size
                        } catch (e: Exception) {
                            service to -1
                        }
                    }
                }.awaitAll()
            }

            // Batch-write timestamps for successful providers (1 disk write instead of N)
            val successfulProviders = results.filter { it.second > 0 }.map { it.first }
            if (successfulProviders.isNotEmpty()) {
                settingsPrefs.updateModelListTimestamps(successfulProviders)
            }

            val resultMap = results.associate { it.first.displayName to it.second }

            if (resultMap.isNotEmpty()) {
                android.util.Log.d("AiViewModel", "Model lists refreshed for ${resultMap.size} providers")
            }

            resultMap
        }
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
                settingsPrefs.updateUsageStatsAsync(
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

    /**
     * Test a specific model with a custom prompt, returning success status and trace filename.
     */
    suspend fun testModelWithPrompt(service: AiService, apiKey: String, model: String, prompt: String): Pair<Boolean, String?> {
        return try {
            val traceCountBefore = ApiTracer.getTraceCount()
            val (responseText, _) = aiAnalysisRepository.testModelWithPrompt(service, apiKey, model, prompt)
            val traceFile = ApiTracer.getTraceFiles().firstOrNull()?.let {
                if (ApiTracer.getTraceCount() > traceCountBefore) it.filename else null
            } ?: ApiTracer.getTraceFiles().firstOrNull()?.filename
            val success = responseText != null && responseText.isNotBlank()
            Pair(success, traceFile)
        } catch (e: Exception) {
            val traceFile = ApiTracer.getTraceFiles().firstOrNull()?.filename
            Pair(false, traceFile)
        }
    }

    // ========== AI Chat ==========

    fun setChatParameters(params: ChatParameters) {
        _uiState.update { it.copy(chatParameters = params) }
    }

    fun setDualChatConfig(config: DualChatConfig?) {
        _uiState.update { it.copy(dualChatConfig = config) }
    }

    /**
     * Send a chat message for dual chat with explicit ChatParameters.
     * Throws on error (unlike sendChatMessage which returns error as message content).
     */
    suspend fun sendDualChatMessage(
        service: AiService,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        params: ChatParameters
    ): String {
        val response = aiAnalysisRepository.sendChatMessage(
            service = service,
            apiKey = apiKey,
            model = model,
            messages = messages,
            params = params
        )
        val inputTokens = messages.sumOf { estimateTokens(it.content) }
        val outputTokens = estimateTokens(response)
        settingsPrefs.updateUsageStatsAsync(
            provider = service,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens
        )
        return response
    }

    // ========== External Intent Instructions ==========

    fun setExternalInstructions(
        closeHtml: String?, reportType: String?, email: String?,
        nextAction: String? = null, returnAfterNext: Boolean = false,
        agentNames: List<String> = emptyList(), flockNames: List<String> = emptyList(),
        swarmNames: List<String> = emptyList(), modelSpecs: List<String> = emptyList(),
        edit: Boolean = false, select: Boolean = false, openHtml: String? = null,
        systemPrompt: String? = null
    ) {
        _uiState.update { it.copy(
            externalSystemPrompt = systemPrompt,
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
        ) }
    }

    fun clearExternalInstructions() {
        _uiState.update { it.copy(
            externalSystemPrompt = null,
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
        ) }
    }

    // ========== Report Advanced Parameters ==========

    fun setReportAdvancedParameters(params: AiAgentParameters?) {
        _uiState.update { it.copy(reportAdvancedParameters = params) }
    }

    suspend fun sendChatMessage(
        service: AiService,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>
    ): ChatMessage {
        return try {
            val response = aiAnalysisRepository.sendChatMessage(
                service = service,
                apiKey = apiKey,
                model = model,
                messages = messages,
                params = _uiState.value.chatParameters
            )
            val inputTokens = messages.sumOf { estimateTokens(it.content) }
            val outputTokens = estimateTokens(response)
            settingsPrefs.updateUsageStatsAsync(
                provider = service,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens
            )
            ChatMessage(role = "assistant", content = response)
        } catch (e: Exception) {
            ChatMessage(role = "assistant", content = "Error: ${e.message ?: "Unknown error"}")
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
        viewModelScope.launch(Dispatchers.IO) {
            settingsPrefs.updateUsageStatsAsync(
                provider = service,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens
            )
        }
    }

    companion object {
        /** Estimate token count from text (roughly 4 characters per token). */
        fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

        private const val REPORT_CONCURRENCY_LIMIT = 4
        private const val AI_REPORT_AGENTS_KEY = "ai_report_agents_v2"
        private const val AI_REPORT_MODELS_KEY = "ai_report_models_v2"
        private val USER_TAG_REGEX = Regex("""<user>(.*?)</user>""", RegexOption.DOT_MATCHES_ALL)
    }

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

}
