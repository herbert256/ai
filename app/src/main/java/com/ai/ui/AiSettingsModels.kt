package com.ai.ui

import com.ai.data.AiService

/**
 * Enum for model source - API (fetched from provider) or Manual (user-maintained list)
 */
enum class ModelSource {
    API,
    MANUAL
}

/**
 * Per-provider configuration stored in AiSettings.
 */
data class ProviderConfig(
    val apiKey: String = "",
    val model: String = "",
    val modelSource: ModelSource = ModelSource.API,
    val models: List<String> = emptyList(),
    val adminUrl: String = "",
    val modelListUrl: String = "",
    val parametersIds: List<String> = emptyList()
)

/**
 * Create default ProviderConfig for a service, including provider-specific defaults
 * for model source and manual model lists.
 */
fun defaultProviderConfig(service: AiService): ProviderConfig {
    val defaultModels: List<String> = service.hardcodedModels ?: emptyList()
    val defaultModelSource = service.defaultModelSource?.let {
        try {
            ModelSource.valueOf(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
        ?: if (defaultModels.isNotEmpty()) ModelSource.MANUAL else ModelSource.API
    return ProviderConfig(
        model = service.defaultModel,
        modelSource = defaultModelSource,
        models = defaultModels,
        adminUrl = service.adminUrl
    )
}

/**
 * Create the default providers map with correct defaults for all services.
 */
fun defaultProvidersMap(): Map<AiService, ProviderConfig> {
    return AiService.entries.associateWith { defaultProviderConfig(it) }
}

/**
 * AI Parameter types that can be configured per agent.
 */
enum class AiParameter {
    TEMPERATURE,        // Randomness (0.0-2.0)
    MAX_TOKENS,         // Maximum response length
    TOP_P,              // Nucleus sampling (0.0-1.0)
    TOP_K,              // Vocabulary limit
    FREQUENCY_PENALTY,  // Reduces repetition (-2.0 to 2.0)
    PRESENCE_PENALTY,   // Encourages new topics (-2.0 to 2.0)
    SYSTEM_PROMPT,      // System instruction
    STOP_SEQUENCES,     // Stop generation sequences
    SEED,               // For reproducibility
    RESPONSE_FORMAT,    // JSON mode
    SEARCH_ENABLED,     // Web search (Grok, Perplexity)
    RETURN_CITATIONS,   // Return citations (Perplexity)
    SEARCH_RECENCY      // Search recency filter (Perplexity)
}

/**
 * All available parameters for AI agents.
 * Every agent can configure any parameter - unsupported parameters are simply ignored by the provider.
 */
val ALL_AGENT_PARAMETERS: Set<AiParameter> = AiParameter.entries.toSet()

// These model classes are defined in com.ai.data.AiDataModels and re-exported here for compatibility
typealias AiAgentParameters = com.ai.data.AiAgentParameters

/**
 * Built-in default endpoints for providers with multiple or custom endpoints.
 * Providers not in this map use a single auto-generated endpoint from baseUrl + chatPath.
 */
private val BUILT_IN_ENDPOINTS: Map<String, List<AiEndpoint>> = mapOf(
    "OPENAI" to listOf(
        AiEndpoint("openai-chat", "Chat Completions", "https://api.openai.com/v1/chat/completions", true),
        AiEndpoint("openai-responses", "Responses API", "https://api.openai.com/v1/responses", false)
    ),
    "MISTRAL" to listOf(
        AiEndpoint("mistral-chat", "Chat Completions", "https://api.mistral.ai/v1/chat/completions", true),
        AiEndpoint("mistral-codestral", "Codestral", "https://codestral.mistral.ai/v1/chat/completions", false)
    ),
    "DEEPSEEK" to listOf(
        AiEndpoint("deepseek-chat", "Chat Completions", "https://api.deepseek.com/chat/completions", true),
        AiEndpoint("deepseek-beta", "Beta (FIM)", "https://api.deepseek.com/beta/completions", false)
    ),
    "ZAI" to listOf(
        AiEndpoint("zai-chat", "Chat Completions", "https://api.z.ai/api/paas/v4/chat/completions", true),
        AiEndpoint("zai-coding", "Coding", "https://api.z.ai/api/coding/paas/v4/chat/completions", false)
    )
)

/**
 * AI Endpoint - configurable API endpoint for a provider.
 */
data class AiEndpoint(
    val id: String,                    // UUID
    val name: String,                  // User-defined name (e.g., "Default", "Custom Server")
    val url: String,                   // API base URL
    val isDefault: Boolean = false     // Whether this is the default endpoint for the provider
)

/**
 * AI Agent - user-created configuration combining provider, model, API key, and parameter presets.
 */
data class AiAgent(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val provider: AiService,           // Reference to provider enum
    val model: String,                 // Model name
    val apiKey: String,                // API key for this agent
    val endpointId: String? = null,    // Reference to AiEndpoint ID (null = use default/hardcoded)
    val paramsIds: List<String> = emptyList(),  // References to AiParameters IDs (parameter presets)
    val systemPromptId: String? = null // Reference to AiSystemPrompt ID
)

/**
 * AI Flock - a named group of AI Agents that work together.
 */
data class AiFlock(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val agentIds: List<String> = emptyList(),  // List of agent IDs in this flock
    val paramsIds: List<String> = emptyList(),  // References to AiParameters IDs (parameter presets)
    val systemPromptId: String? = null // Reference to AiSystemPrompt ID
)

/**
 * AI Swarm Member - a provider/model combination within a swarm.
 * Unlike agents, swarm members have no custom settings - they use provider defaults.
 */
data class AiSwarmMember(
    val provider: AiService,           // Provider enum
    val model: String                  // Model name
)

/**
 * AI Swarm - a named group of provider/model combinations.
 * Unlike flocks which reference agents, swarms contain lightweight provider/model pairs.
 */
data class AiSwarm(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val members: List<AiSwarmMember> = emptyList(),  // List of provider/model combinations
    val paramsIds: List<String> = emptyList(),  // References to AiParameters IDs (parameter presets)
    val systemPromptId: String? = null // Reference to AiSystemPrompt ID
)

/**
 * AI Parameters - a named parameter preset that can be reused across agents or reports.
 * All parameters default to null/off, meaning they won't override provider defaults.
 */
data class AiParameters(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val systemPrompt: String? = null,
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    val responseFormatJson: Boolean = false,
    val searchEnabled: Boolean = false,
    val returnCitations: Boolean = true,
    val searchRecency: String? = null  // "day", "week", "month", "year"
) {
    /**
     * Convert to AiAgentParameters for use with agents.
     */
    fun toAgentParameters(): AiAgentParameters = AiAgentParameters(
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        topK = topK,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        systemPrompt = systemPrompt,
        stopSequences = stopSequences,
        seed = seed,
        responseFormatJson = responseFormatJson,
        searchEnabled = searchEnabled,
        returnCitations = returnCitations,
        searchRecency = searchRecency
    )
}

/**
 * AI System Prompt - a reusable named system prompt that can be connected to Agents, Flocks, and Swarms.
 * When an API call is made, the connected system prompt is injected into the request.
 */
data class AiSystemPrompt(
    val id: String,       // UUID
    val name: String,     // User-defined name
    val prompt: String    // System prompt text
)

/**
 * AI Prompt - internal prompts used by the app for AI-powered features.
 * Supports variable replacement: @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@
 */
data class AiPrompt(
    val id: String,                    // UUID
    val name: String,                  // Unique name (e.g., "model_info")
    val agentId: String,               // Reference to AiAgent ID
    val promptText: String             // Prompt template with optional variables
) {
    /**
     * Replace variables in prompt text with actual values.
     */
    fun resolvePrompt(
        model: String? = null,
        provider: String? = null,
        agent: String? = null,
        swarm: String? = null
    ): String {
        var resolved = promptText
        if (model != null) resolved = resolved.replace("@MODEL@", model)
        if (provider != null) resolved = resolved.replace("@PROVIDER@", provider)
        if (agent != null) resolved = resolved.replace("@AGENT@", agent)
        if (swarm != null) resolved = resolved.replace("@SWARM@", swarm)
        resolved = resolved.replace("@NOW@", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
        return resolved
    }
}

/**
 * AI Settings data class for storing API keys for various AI services.
 */
data class AiSettings(
    val providers: Map<AiService, ProviderConfig> = defaultProvidersMap(),
    // AI Agents
    val agents: List<AiAgent> = emptyList(),
    // AI Flocks
    val flocks: List<AiFlock> = emptyList(),
    // AI Swarms
    val swarms: List<AiSwarm> = emptyList(),
    // AI Parameters (reusable parameter presets)
    val parameters: List<AiParameters> = emptyList(),
    // AI System Prompts (reusable system prompts for agents/flocks/swarms)
    val systemPrompts: List<AiSystemPrompt> = emptyList(),
    // AI Prompts (internal app prompts)
    val prompts: List<AiPrompt> = emptyList(),
    // API Endpoints per provider (multiple endpoints allowed, one can be default)
    val endpoints: Map<AiService, List<AiEndpoint>> = emptyMap(),
    // Provider states: "ok" (key valid), "error" (key invalid), "not-used" (no key), "inactive" (user disabled)
    val providerStates: Map<String, String> = emptyMap()
) {
    /**
     * Get the provider state for a service.
     * Returns "ok", "error", "not-used", or "inactive" based on stored state and API key presence.
     * Inactive state is checked first (user explicitly disabled the provider).
     * Untested providers (key present but no stored state) default to "ok" for cold-start.
     */
    fun getProviderState(service: AiService): String {
        val stored = providerStates[service.id]
        if (stored == "inactive") return "inactive"
        if (getApiKey(service).isBlank()) return "not-used"
        return stored ?: "ok"
    }

    /**
     * Check if a provider is active (status "ok").
     */
    fun isProviderActive(service: AiService): Boolean {
        return getProviderState(service) == "ok"
    }

    /**
     * Get all active providers (status "ok").
     */
    fun getActiveServices(): List<AiService> {
        return AiService.entries.filter { isProviderActive(it) }
    }

    /**
     * Return a copy with an updated provider state.
     */
    fun withProviderState(service: AiService, state: String): AiSettings {
        return copy(providerStates = providerStates + (service.id to state))
    }

    fun getProvider(service: AiService): ProviderConfig =
        providers[service] ?: defaultProviderConfig(service)

    fun withProvider(service: AiService, config: ProviderConfig): AiSettings =
        copy(providers = providers + (service to config))

    fun getApiKey(service: AiService): String = getProvider(service).apiKey

    fun withApiKey(service: AiService, apiKey: String): AiSettings =
        withProvider(service, getProvider(service).copy(apiKey = apiKey))

    fun getModel(service: AiService): String = getProvider(service).model

    fun withModel(service: AiService, model: String): AiSettings =
        withProvider(service, getProvider(service).copy(model = model))

    fun getModelSource(service: AiService): ModelSource = getProvider(service).modelSource

    fun getModels(service: AiService): List<String> = getProvider(service).models

    fun withModels(service: AiService, models: List<String>): AiSettings =
        withProvider(service, getProvider(service).copy(models = models))

    fun hasAnyApiKey(): Boolean = providers.values.any { it.apiKey.isNotBlank() }

    // Helper methods for agents
    fun getAgentById(id: String): AiAgent? = agents.find { it.id == id }

    /**
     * Resolve an agent's paramsIds to merged AiAgentParameters.
     * Returns AiAgentParameters with defaults if no presets are found.
     */
    fun resolveAgentParameters(agent: AiAgent): AiAgentParameters {
        return mergeParameters(agent.paramsIds) ?: AiAgentParameters()
    }

    /**
     * Get the effective API key for an agent.
     * Priority: agent's API key > provider's API key
     */
    fun getEffectiveApiKeyForAgent(agent: AiAgent): String {
        return agent.apiKey.ifBlank { getApiKey(agent.provider) }
    }

    fun getEffectiveModelForAgent(agent: AiAgent): String {
        return agent.model.ifBlank { getModel(agent.provider) }
    }

    /**
     * Get agents that have an effective API key (either their own or from provider).
     */
    fun getConfiguredAgents(): List<AiAgent> = agents.filter {
        it.apiKey.isNotBlank() || getApiKey(it.provider).isNotBlank()
    }

    // Helper methods for flocks
    fun getFlockById(id: String): AiFlock? = flocks.find { it.id == id }

    fun getAgentsForFlock(flock: AiFlock): List<AiAgent> =
        flock.agentIds.mapNotNull { agentId -> getAgentById(agentId) }

    fun getAgentsForFlocks(flockIds: Set<String>): List<AiAgent> =
        flockIds.flatMap { flockId ->
            getFlockById(flockId)?.let { getAgentsForFlock(it) } ?: emptyList()
        }.distinctBy { it.id }

    // Helper methods for swarms
    fun getSwarmById(id: String): AiSwarm? = swarms.find { it.id == id }

    fun getMembersForSwarm(swarm: AiSwarm): List<AiSwarmMember> = swarm.members

    fun getMembersForSwarms(swarmIds: Set<String>): List<AiSwarmMember> =
        swarmIds.flatMap { swarmId ->
            getSwarmById(swarmId)?.members ?: emptyList()
        }.distinctBy { "${it.provider.id}:${it.model}" }

    // Helper methods for prompts
    fun getPromptByName(name: String): AiPrompt? = prompts.find { it.name.equals(name, ignoreCase = true) }

    // Helper methods for system prompts
    fun getSystemPromptById(id: String): AiSystemPrompt? = systemPrompts.find { it.id == id }

    // Helper methods for params
    fun getParametersById(id: String): AiParameters? = parameters.find { it.id == id }

    fun getParametersByName(name: String): AiParameters? = parameters.find { it.name.equals(name, ignoreCase = true) }

    fun getPromptById(id: String): AiPrompt? = prompts.find { it.id == id }

    fun getAgentForPrompt(prompt: AiPrompt): AiAgent? = getAgentById(prompt.agentId)

    // Helper methods for endpoints
    fun getEndpointsForProvider(provider: AiService): List<AiEndpoint> =
        endpoints[provider]?.ifEmpty { getBuiltInEndpoints(provider) } ?: getBuiltInEndpoints(provider)

    /**
     * Get built-in default endpoints for providers.
     * These are used when no custom endpoints are configured.
     */
    private fun getBuiltInEndpoints(provider: AiService): List<AiEndpoint> =
        BUILT_IN_ENDPOINTS[provider.id] ?: defaultEndpointForProvider(provider)

    /** Generate a single default endpoint from a provider's baseUrl and chatPath. */
    private fun defaultEndpointForProvider(provider: AiService): List<AiEndpoint> {
        val base = if (provider.baseUrl.endsWith("/")) provider.baseUrl else "${provider.baseUrl}/"
        val url = base + provider.chatPath
        val idPrefix = provider.id.lowercase()
        return listOf(AiEndpoint("$idPrefix-chat", "Chat Completions", url, true))
    }

    fun getEndpointById(provider: AiService, endpointId: String): AiEndpoint? =
        getEndpointsForProvider(provider).find { it.id == endpointId }

    fun getDefaultEndpoint(provider: AiService): AiEndpoint? =
        getEndpointsForProvider(provider).find { it.isDefault }
            ?: getEndpointsForProvider(provider).firstOrNull()

    /**
     * Get the effective endpoint URL for a provider.
     * Priority: default endpoint > first endpoint > hardcoded URL
     */
    fun getEffectiveEndpointUrl(provider: AiService): String {
        val endpoint = getDefaultEndpoint(provider)
        return endpoint?.url ?: provider.baseUrl
    }

    /**
     * Get the effective endpoint URL for an agent.
     * Priority: agent's endpoint > provider's default endpoint > first endpoint > hardcoded URL
     */
    fun getEffectiveEndpointUrlForAgent(agent: AiAgent): String {
        // If agent has a specific endpoint, use it
        agent.endpointId?.let { endpointId ->
            getEndpointById(agent.provider, endpointId)?.let { return it.url }
        }
        // Otherwise use provider's effective endpoint
        return getEffectiveEndpointUrl(agent.provider)
    }

    /**
     * Add or update endpoints for a provider, ensuring only one default.
     */
    fun withEndpoints(provider: AiService, newEndpoints: List<AiEndpoint>): AiSettings {
        return copy(endpoints = endpoints + (provider to newEndpoints))
    }

    /**
     * Remove a provider and all its associated data from settings.
     * Also cleans flock references to deleted agents.
     */
    fun removeProvider(service: AiService): AiSettings {
        val removedAgentIds = agents.filter { it.provider.id == service.id }.map { it.id }.toSet()
        return copy(
            providers = providers - service,
            endpoints = endpoints - service,
            providerStates = providerStates - service.id,
            agents = agents.filter { it.provider.id != service.id },
            flocks = flocks.map { flock ->
                flock.copy(agentIds = flock.agentIds.filter { it !in removedAgentIds })
            },
            swarms = swarms.map { swarm ->
                swarm.copy(members = swarm.members.filter { it.provider.id != service.id })
            },
            // Clean orphaned prompts referencing deleted agents
            prompts = prompts.filter { it.agentId !in removedAgentIds }
        )
    }

    /**
     * Remove a system prompt and clean all references from agents, flocks, and swarms.
     */
    fun removeSystemPrompt(systemPromptId: String): AiSettings {
        return copy(
            systemPrompts = systemPrompts.filter { it.id != systemPromptId },
            agents = agents.map { agent ->
                if (agent.systemPromptId == systemPromptId) agent.copy(systemPromptId = null) else agent
            },
            flocks = flocks.map { flock ->
                if (flock.systemPromptId == systemPromptId) flock.copy(systemPromptId = null) else flock
            },
            swarms = swarms.map { swarm ->
                if (swarm.systemPromptId == systemPromptId) swarm.copy(systemPromptId = null) else swarm
            }
        )
    }

    /**
     * Remove a parameter preset and clean all references from agents, flocks, swarms, and providers.
     */
    fun removeParameters(parametersId: String): AiSettings {
        return copy(
            parameters = parameters.filter { it.id != parametersId },
            agents = agents.map { agent ->
                agent.copy(paramsIds = agent.paramsIds.filter { it != parametersId })
            },
            flocks = flocks.map { flock ->
                flock.copy(paramsIds = flock.paramsIds.filter { it != parametersId })
            },
            swarms = swarms.map { swarm ->
                swarm.copy(paramsIds = swarm.paramsIds.filter { it != parametersId })
            },
            providers = providers.mapValues { (_, config) ->
                config.copy(parametersIds = config.parametersIds.filter { it != parametersId })
            }
        )
    }

    /**
     * Remove an agent and clean all references from flocks.
     */
    fun removeAgent(agentId: String): AiSettings {
        return copy(
            agents = agents.filter { it.id != agentId },
            flocks = flocks.map { flock ->
                flock.copy(agentIds = flock.agentIds.filter { it != agentId })
            },
            prompts = prompts.filter { it.agentId != agentId }
        )
    }

    /**
     * Get the custom model list URL for a provider.
     * Returns empty string if using default (hardcoded) URL.
     */
    fun getModelListUrl(service: AiService): String = getProvider(service).modelListUrl

    /**
     * Get the default model list URL for a provider (derived from baseUrl + modelsPath).
     */
    fun getDefaultModelListUrl(service: AiService): String {
        val base = if (service.baseUrl.endsWith("/")) service.baseUrl else "${service.baseUrl}/"
        return base + service.modelsPath
    }

    /**
     * Get the effective model list URL for a provider.
     * Returns custom URL if set, otherwise returns default URL.
     */
    fun getEffectiveModelListUrl(service: AiService): String {
        val customUrl = getModelListUrl(service)
        return if (customUrl.isNotBlank()) customUrl else getDefaultModelListUrl(service)
    }

    /**
     * Get the default parameters preset IDs for a provider.
     */
    fun getParametersIds(service: AiService): List<String> = getProvider(service).parametersIds

    /**
     * Set the default parameters preset IDs for a provider.
     */
    fun withParametersIds(service: AiService, paramsIds: List<String>): AiSettings =
        withProvider(service, getProvider(service).copy(parametersIds = paramsIds))

    /**
     * Resolve a list of parameter preset IDs to AiParameters objects.
     */
    fun getParametersByIds(ids: List<String>): List<AiParameters> =
        ids.mapNotNull { getParametersById(it) }

    /**
     * Merge multiple parameter presets in order. Later non-null fields override earlier ones.
     * Returns null if no valid presets are found.
     */
    fun mergeParameters(ids: List<String>): AiAgentParameters? {
        if (ids.isEmpty()) return null
        val presets = getParametersByIds(ids)
        if (presets.isEmpty()) return null
        return presets.map { it.toAgentParameters() }.reduce { acc, params ->
            AiAgentParameters(
                temperature = params.temperature ?: acc.temperature,
                maxTokens = params.maxTokens ?: acc.maxTokens,
                topP = params.topP ?: acc.topP,
                topK = params.topK ?: acc.topK,
                frequencyPenalty = params.frequencyPenalty ?: acc.frequencyPenalty,
                presencePenalty = params.presencePenalty ?: acc.presencePenalty,
                systemPrompt = params.systemPrompt ?: acc.systemPrompt,
                stopSequences = params.stopSequences ?: acc.stopSequences,
                seed = params.seed ?: acc.seed,
                // Later presets override earlier ones (not "sticky true" anymore)
                responseFormatJson = params.responseFormatJson,
                searchEnabled = params.searchEnabled,
                returnCitations = params.returnCitations,
                searchRecency = params.searchRecency ?: acc.searchRecency
            )
        }
    }
}

/**
 * A model entry in the AI Reports selection list.
 * Each entry represents a single provider+model combination that will generate a report.
 */
data class ReportModel(
    val provider: AiService,
    val model: String,
    val type: String,           // "agent" or "model"
    val sourceType: String,     // "flock", "agent", "swarm", "model"
    val sourceName: String,     // empty when sourceType is "model"
    val agentId: String? = null,        // non-null for agent-based entries
    val endpointId: String? = null,     // from agent config
    val agentApiKey: String? = null,    // from agent config
    val paramsIds: List<String> = emptyList()  // from agent/flock/swarm params
) {
    val deduplicationKey: String get() = "${provider.id}:$model"
}

fun expandFlockToModels(flock: AiFlock, aiSettings: AiSettings): List<ReportModel> {
    return flock.agentIds.mapNotNull { agentId ->
        val agent = aiSettings.getAgentById(agentId) ?: return@mapNotNull null
        if (!aiSettings.isProviderActive(agent.provider)) return@mapNotNull null
        ReportModel(
            provider = agent.provider,
            model = aiSettings.getEffectiveModelForAgent(agent),
            type = "agent",
            sourceType = "flock",
            sourceName = flock.name,
            agentId = agent.id,
            endpointId = agent.endpointId,
            agentApiKey = aiSettings.getEffectiveApiKeyForAgent(agent),
            paramsIds = flock.paramsIds + agent.paramsIds
        )
    }
}

fun expandAgentToModel(agent: AiAgent, aiSettings: AiSettings): ReportModel? {
    if (!aiSettings.isProviderActive(agent.provider)) return null
    return ReportModel(
        provider = agent.provider,
        model = aiSettings.getEffectiveModelForAgent(agent),
        type = "agent",
        sourceType = "agent",
        sourceName = agent.name,
        agentId = agent.id,
        endpointId = agent.endpointId,
        agentApiKey = aiSettings.getEffectiveApiKeyForAgent(agent),
        paramsIds = agent.paramsIds
    )
}

fun expandSwarmToModels(swarm: AiSwarm, aiSettings: AiSettings): List<ReportModel> {
    return swarm.members.filter { aiSettings.isProviderActive(it.provider) }.map { member ->
        ReportModel(
            provider = member.provider,
            model = member.model,
            type = "model",
            sourceType = "swarm",
            sourceName = swarm.name,
            paramsIds = swarm.paramsIds
        )
    }
}

fun toReportModel(provider: AiService, model: String): ReportModel {
    return ReportModel(
        provider = provider,
        model = model,
        type = "model",
        sourceType = "model",
        sourceName = ""
    )
}

fun deduplicateModels(models: List<ReportModel>): List<ReportModel> {
    val seen = mutableSetOf<String>()
    return models.filter { seen.add(it.deduplicationKey) }
}

/**
 * Statistics for a specific provider+model combination.
 * Tracks call count and token usage (normalized across providers).
 */
data class AiUsageStats(
    val provider: AiService,
    val model: String,
    val callCount: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0
) {
    /** Total tokens = input + output (computed, never drifts) */
    val totalTokens: Long get() = inputTokens + outputTokens
    /** Unique key for this provider+model combination */
    val key: String get() = "${provider.id}::$model"
}

// These model classes are defined in com.ai.data.AiDataModels and re-exported here for compatibility
typealias ChatMessage = com.ai.data.ChatMessage
typealias ChatParameters = com.ai.data.ChatParameters
typealias ChatSession = com.ai.data.ChatSession
typealias DualChatConfig = com.ai.data.DualChatConfig
