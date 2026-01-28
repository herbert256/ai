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

/**
 * Configuration for AI agent parameters with defaults.
 */
data class AiAgentParameters(
    val temperature: Float? = null,           // null means use provider default
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
    val searchRecency: String? = null         // "day", "week", "month", "year"
)

/**
 * Available Claude models (hardcoded as Anthropic doesn't provide a list models API).
 */
val CLAUDE_MODELS = listOf(
    "claude-sonnet-4-20250514",
    "claude-opus-4-20250514",
    "claude-3-7-sonnet-20250219",
    "claude-3-5-sonnet-20241022",
    "claude-3-5-haiku-20241022",
    "claude-3-opus-20240229",
    "claude-3-sonnet-20240229",
    "claude-3-haiku-20240307"
)

/**
 * Available Perplexity models (hardcoded).
 */
val PERPLEXITY_MODELS = listOf(
    "sonar",
    "sonar-pro",
    "sonar-reasoning-pro",
    "sonar-deep-research"
)

/**
 * Available SiliconFlow models (hardcoded - popular models).
 */
val SILICONFLOW_MODELS = listOf(
    "Qwen/Qwen2.5-7B-Instruct",
    "Qwen/Qwen2.5-14B-Instruct",
    "Qwen/Qwen2.5-32B-Instruct",
    "Qwen/Qwen2.5-72B-Instruct",
    "Qwen/QwQ-32B",
    "deepseek-ai/DeepSeek-V3",
    "deepseek-ai/DeepSeek-R1",
    "THUDM/glm-4-9b-chat",
    "meta-llama/Llama-3.3-70B-Instruct"
)

/**
 * Available Z.AI models (hardcoded - uses ZhipuAI GLM models).
 */
val ZAI_MODELS = listOf(
    "glm-4.7-flash",
    "glm-4.7",
    "glm-4.5-flash",
    "glm-4.5",
    "glm-4-plus",
    "glm-4-long",
    "glm-4-flash"
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
 * AI Agent - user-created configuration combining provider, model, API key, and parameters.
 */
data class AiAgent(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val provider: AiService,           // Reference to provider enum
    val model: String,                 // Model name
    val apiKey: String,                // API key for this agent
    val endpointId: String? = null,    // Reference to AiEndpoint ID (null = use default/hardcoded)
    val parameters: AiAgentParameters = AiAgentParameters()  // Optional parameters
)

/**
 * AI Swarm - a named group of AI Agents that work together.
 */
data class AiSwarm(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val agentIds: List<String> = emptyList(),  // List of agent IDs in this swarm
    val paramsId: String? = null       // Reference to AiParams ID (optional parameter preset)
)

/**
 * AI Flock Member - a provider/model combination within a flock.
 * Unlike agents, flock members have no custom settings - they use provider defaults.
 */
data class AiFlockMember(
    val provider: AiService,           // Provider enum
    val model: String                  // Model name
)

/**
 * AI Flock - a named group of provider/model combinations.
 * Unlike swarms which reference agents, flocks contain lightweight provider/model pairs.
 */
data class AiFlock(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val members: List<AiFlockMember> = emptyList(),  // List of provider/model combinations
    val paramsId: String? = null       // Reference to AiParams ID (optional parameter preset)
)

/**
 * AI Params - a named parameter preset that can be reused across agents or reports.
 * All parameters default to null/off, meaning they won't override provider defaults.
 */
data class AiParams(
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
    val returnCitations: Boolean = false,
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
    val chatGptApiKey: String = "",
    val chatGptModel: String = AiService.OPENAI.defaultModel,
    val chatGptModelSource: ModelSource = ModelSource.API,
    val chatGptManualModels: List<String> = emptyList(),
    val chatGptAdminUrl: String = AiService.OPENAI.adminUrl,
    val chatGptModelListUrl: String = "",  // Custom model list URL (empty = use default)
    val chatGptParamsId: String? = null,   // Default parameters preset for this provider
    val claudeApiKey: String = "",
    val claudeModel: String = AiService.ANTHROPIC.defaultModel,
    val claudeModelSource: ModelSource = ModelSource.API,
    val claudeManualModels: List<String> = CLAUDE_MODELS,
    val claudeAdminUrl: String = AiService.ANTHROPIC.adminUrl,
    val claudeModelListUrl: String = "",
    val claudeParamsId: String? = null,
    val geminiApiKey: String = "",
    val geminiModel: String = AiService.GOOGLE.defaultModel,
    val geminiModelSource: ModelSource = ModelSource.API,
    val geminiManualModels: List<String> = emptyList(),
    val geminiAdminUrl: String = AiService.GOOGLE.adminUrl,
    val geminiModelListUrl: String = "",
    val geminiParamsId: String? = null,
    val grokApiKey: String = "",
    val grokModel: String = AiService.XAI.defaultModel,
    val grokModelSource: ModelSource = ModelSource.API,
    val grokManualModels: List<String> = emptyList(),
    val grokAdminUrl: String = AiService.XAI.adminUrl,
    val grokModelListUrl: String = "",
    val grokParamsId: String? = null,
    val groqApiKey: String = "",
    val groqModel: String = AiService.GROQ.defaultModel,
    val groqModelSource: ModelSource = ModelSource.API,
    val groqManualModels: List<String> = emptyList(),
    val groqAdminUrl: String = AiService.GROQ.adminUrl,
    val groqModelListUrl: String = "",
    val groqParamsId: String? = null,
    val deepSeekApiKey: String = "",
    val deepSeekModel: String = AiService.DEEPSEEK.defaultModel,
    val deepSeekModelSource: ModelSource = ModelSource.API,
    val deepSeekManualModels: List<String> = emptyList(),
    val deepSeekAdminUrl: String = AiService.DEEPSEEK.adminUrl,
    val deepSeekModelListUrl: String = "",
    val deepSeekParamsId: String? = null,
    val mistralApiKey: String = "",
    val mistralModel: String = AiService.MISTRAL.defaultModel,
    val mistralModelSource: ModelSource = ModelSource.API,
    val mistralManualModels: List<String> = emptyList(),
    val mistralAdminUrl: String = AiService.MISTRAL.adminUrl,
    val mistralModelListUrl: String = "",
    val mistralParamsId: String? = null,
    val perplexityApiKey: String = "",
    val perplexityModel: String = AiService.PERPLEXITY.defaultModel,
    val perplexityModelSource: ModelSource = ModelSource.MANUAL,
    val perplexityManualModels: List<String> = PERPLEXITY_MODELS,
    val perplexityAdminUrl: String = AiService.PERPLEXITY.adminUrl,
    val perplexityModelListUrl: String = "",
    val perplexityParamsId: String? = null,
    val togetherApiKey: String = "",
    val togetherModel: String = AiService.TOGETHER.defaultModel,
    val togetherModelSource: ModelSource = ModelSource.API,
    val togetherManualModels: List<String> = emptyList(),
    val togetherAdminUrl: String = AiService.TOGETHER.adminUrl,
    val togetherModelListUrl: String = "",
    val togetherParamsId: String? = null,
    val openRouterApiKey: String = "",
    val openRouterModel: String = AiService.OPENROUTER.defaultModel,
    val openRouterModelSource: ModelSource = ModelSource.API,
    val openRouterManualModels: List<String> = emptyList(),
    val openRouterAdminUrl: String = AiService.OPENROUTER.adminUrl,
    val openRouterModelListUrl: String = "",
    val openRouterParamsId: String? = null,
    val siliconFlowApiKey: String = "",
    val siliconFlowModel: String = AiService.SILICONFLOW.defaultModel,
    val siliconFlowModelSource: ModelSource = ModelSource.API,
    val siliconFlowManualModels: List<String> = SILICONFLOW_MODELS,
    val siliconFlowAdminUrl: String = AiService.SILICONFLOW.adminUrl,
    val siliconFlowModelListUrl: String = "",
    val siliconFlowParamsId: String? = null,
    val zaiApiKey: String = "",
    val zaiModel: String = AiService.ZAI.defaultModel,
    val zaiModelSource: ModelSource = ModelSource.API,
    val zaiManualModels: List<String> = ZAI_MODELS,
    val zaiAdminUrl: String = AiService.ZAI.adminUrl,
    val zaiModelListUrl: String = "",
    val zaiParamsId: String? = null,
    val dummyApiKey: String = "",
    val dummyModel: String = AiService.DUMMY.defaultModel,
    val dummyModelSource: ModelSource = ModelSource.API,
    val dummyManualModels: List<String> = listOf("dummy-model"),
    val dummyAdminUrl: String = AiService.DUMMY.adminUrl,
    val dummyModelListUrl: String = "",
    val dummyParamsId: String? = null,
    // AI Agents
    val agents: List<AiAgent> = emptyList(),
    // AI Swarms
    val swarms: List<AiSwarm> = emptyList(),
    // AI Flocks
    val flocks: List<AiFlock> = emptyList(),
    // AI Params (reusable parameter presets)
    val params: List<AiParams> = emptyList(),
    // AI Prompts (internal app prompts)
    val prompts: List<AiPrompt> = emptyList(),
    // API Endpoints per provider (multiple endpoints allowed, one can be default)
    val endpoints: Map<AiService, List<AiEndpoint>> = emptyMap()
) {
    fun getApiKey(service: AiService): String {
        return when (service) {
            AiService.OPENAI -> chatGptApiKey
            AiService.ANTHROPIC -> claudeApiKey
            AiService.GOOGLE -> geminiApiKey
            AiService.XAI -> grokApiKey
            AiService.GROQ -> groqApiKey
            AiService.DEEPSEEK -> deepSeekApiKey
            AiService.MISTRAL -> mistralApiKey
            AiService.PERPLEXITY -> perplexityApiKey
            AiService.TOGETHER -> togetherApiKey
            AiService.OPENROUTER -> openRouterApiKey
            AiService.SILICONFLOW -> siliconFlowApiKey
            AiService.ZAI -> zaiApiKey
            AiService.DUMMY -> dummyApiKey
        }
    }

    fun getModel(service: AiService): String {
        return when (service) {
            AiService.OPENAI -> chatGptModel
            AiService.ANTHROPIC -> claudeModel
            AiService.GOOGLE -> geminiModel
            AiService.XAI -> grokModel
            AiService.GROQ -> groqModel
            AiService.DEEPSEEK -> deepSeekModel
            AiService.MISTRAL -> mistralModel
            AiService.PERPLEXITY -> perplexityModel
            AiService.TOGETHER -> togetherModel
            AiService.OPENROUTER -> openRouterModel
            AiService.SILICONFLOW -> siliconFlowModel
            AiService.ZAI -> zaiModel
            AiService.DUMMY -> dummyModel
        }
    }

    fun withModel(service: AiService, model: String): AiSettings {
        return when (service) {
            AiService.OPENAI -> copy(chatGptModel = model)
            AiService.ANTHROPIC -> copy(claudeModel = model)
            AiService.GOOGLE -> copy(geminiModel = model)
            AiService.XAI -> copy(grokModel = model)
            AiService.GROQ -> copy(groqModel = model)
            AiService.DEEPSEEK -> copy(deepSeekModel = model)
            AiService.MISTRAL -> copy(mistralModel = model)
            AiService.PERPLEXITY -> copy(perplexityModel = model)
            AiService.TOGETHER -> copy(togetherModel = model)
            AiService.OPENROUTER -> copy(openRouterModel = model)
            AiService.SILICONFLOW -> copy(siliconFlowModel = model)
            AiService.ZAI -> copy(zaiModel = model)
            AiService.DUMMY -> copy(dummyModel = model)
        }
    }

    fun getModelSource(service: AiService): ModelSource {
        return when (service) {
            AiService.OPENAI -> chatGptModelSource
            AiService.ANTHROPIC -> claudeModelSource
            AiService.GOOGLE -> geminiModelSource
            AiService.XAI -> grokModelSource
            AiService.GROQ -> groqModelSource
            AiService.DEEPSEEK -> deepSeekModelSource
            AiService.MISTRAL -> mistralModelSource
            AiService.PERPLEXITY -> perplexityModelSource
            AiService.TOGETHER -> togetherModelSource
            AiService.OPENROUTER -> openRouterModelSource
            AiService.SILICONFLOW -> siliconFlowModelSource
            AiService.ZAI -> zaiModelSource
            AiService.DUMMY -> dummyModelSource
        }
    }

    fun getManualModels(service: AiService): List<String> {
        return when (service) {
            AiService.OPENAI -> chatGptManualModels
            AiService.ANTHROPIC -> claudeManualModels
            AiService.GOOGLE -> geminiManualModels
            AiService.XAI -> grokManualModels
            AiService.GROQ -> groqManualModels
            AiService.DEEPSEEK -> deepSeekManualModels
            AiService.MISTRAL -> mistralManualModels
            AiService.PERPLEXITY -> perplexityManualModels
            AiService.TOGETHER -> togetherManualModels
            AiService.OPENROUTER -> openRouterManualModels
            AiService.SILICONFLOW -> siliconFlowManualModels
            AiService.ZAI -> zaiManualModels
            AiService.DUMMY -> dummyManualModels
        }
    }

    fun hasAnyApiKey(): Boolean {
        return chatGptApiKey.isNotBlank() ||
                claudeApiKey.isNotBlank() ||
                geminiApiKey.isNotBlank() ||
                grokApiKey.isNotBlank() ||
                deepSeekApiKey.isNotBlank() ||
                mistralApiKey.isNotBlank() ||
                perplexityApiKey.isNotBlank() ||
                togetherApiKey.isNotBlank() ||
                openRouterApiKey.isNotBlank() ||
                siliconFlowApiKey.isNotBlank() ||
                zaiApiKey.isNotBlank()
    }

    fun getConfiguredServices(): List<AiService> {
        return AiService.entries.filter { getApiKey(it).isNotBlank() }
    }

    // Helper methods for agents
    fun getAgentById(id: String): AiAgent? = agents.find { it.id == id }

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

    // Helper methods for swarms
    fun getSwarmById(id: String): AiSwarm? = swarms.find { it.id == id }

    fun getAgentsForSwarm(swarm: AiSwarm): List<AiAgent> =
        swarm.agentIds.mapNotNull { agentId -> getAgentById(agentId) }

    fun getAgentsForSwarms(swarmIds: Set<String>): List<AiAgent> =
        swarmIds.flatMap { swarmId ->
            getSwarmById(swarmId)?.let { getAgentsForSwarm(it) } ?: emptyList()
        }.distinctBy { it.id }

    // Helper methods for flocks
    fun getFlockById(id: String): AiFlock? = flocks.find { it.id == id }

    fun getMembersForFlock(flock: AiFlock): List<AiFlockMember> = flock.members

    fun getMembersForFlocks(flockIds: Set<String>): List<AiFlockMember> =
        flockIds.flatMap { flockId ->
            getFlockById(flockId)?.members ?: emptyList()
        }.distinctBy { "${it.provider.name}:${it.model}" }

    // Helper methods for prompts
    fun getPromptByName(name: String): AiPrompt? = prompts.find { it.name.equals(name, ignoreCase = true) }

    // Helper methods for params
    fun getParamsById(id: String): AiParams? = params.find { it.id == id }

    fun getParamsByName(name: String): AiParams? = params.find { it.name.equals(name, ignoreCase = true) }

    fun getPromptById(id: String): AiPrompt? = prompts.find { it.id == id }

    fun getAgentForPrompt(prompt: AiPrompt): AiAgent? = getAgentById(prompt.agentId)

    // Helper methods for endpoints
    fun getEndpointsForProvider(provider: AiService): List<AiEndpoint> =
        endpoints[provider]?.ifEmpty { getBuiltInEndpoints(provider) } ?: getBuiltInEndpoints(provider)

    /**
     * Get built-in default endpoints for providers.
     * These are used when no custom endpoints are configured.
     */
    private fun getBuiltInEndpoints(provider: AiService): List<AiEndpoint> = when (provider) {
        AiService.OPENAI -> listOf(
            AiEndpoint("openai-chat", "Chat Completions", "https://api.openai.com/v1/chat/completions", true),
            AiEndpoint("openai-responses", "Responses API", "https://api.openai.com/v1/responses", false)
        )
        AiService.MISTRAL -> listOf(
            AiEndpoint("mistral-chat", "Chat Completions", "https://api.mistral.ai/v1/chat/completions", true),
            AiEndpoint("mistral-codestral", "Codestral", "https://codestral.mistral.ai/v1/chat/completions", false)
        )
        AiService.DEEPSEEK -> listOf(
            AiEndpoint("deepseek-chat", "Chat Completions", "https://api.deepseek.com/chat/completions", true),
            AiEndpoint("deepseek-beta", "Beta (FIM)", "https://api.deepseek.com/beta/completions", false)
        )
        AiService.ZAI -> listOf(
            AiEndpoint("zai-chat", "Chat Completions", "https://api.z.ai/api/paas/v4/chat/completions", true),
            AiEndpoint("zai-coding", "Coding", "https://api.z.ai/api/coding/paas/v4/chat/completions", false)
        )
        AiService.SILICONFLOW -> listOf(
            AiEndpoint("siliconflow-chat", "Chat Completions", "https://api.siliconflow.com/v1/chat/completions", true)
        )
        AiService.GROQ -> listOf(
            AiEndpoint("groq-chat", "Chat Completions", "https://api.groq.com/openai/v1/chat/completions", true)
        )
        AiService.XAI -> listOf(
            AiEndpoint("xai-chat", "Chat Completions", "https://api.x.ai/v1/chat/completions", true)
        )
        AiService.TOGETHER -> listOf(
            AiEndpoint("together-chat", "Chat Completions", "https://api.together.xyz/v1/chat/completions", true)
        )
        AiService.OPENROUTER -> listOf(
            AiEndpoint("openrouter-chat", "Chat Completions", "https://openrouter.ai/api/v1/chat/completions", true)
        )
        AiService.PERPLEXITY -> listOf(
            AiEndpoint("perplexity-chat", "Chat Completions", "https://api.perplexity.ai/chat/completions", true)
        )
        AiService.ANTHROPIC -> listOf(
            AiEndpoint("anthropic-messages", "Messages API", "https://api.anthropic.com/v1/messages", true)
        )
        AiService.GOOGLE -> listOf(
            AiEndpoint("google-generate", "Generate Content", "https://generativelanguage.googleapis.com/v1beta/models", true)
        )
        AiService.DUMMY -> listOf(
            AiEndpoint("dummy-chat", "Chat Completions", "http://localhost:54321/v1/chat/completions", true)
        )
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
     * Get the custom model list URL for a provider.
     * Returns empty string if using default (hardcoded) URL.
     */
    fun getModelListUrl(service: AiService): String {
        return when (service) {
            AiService.OPENAI -> chatGptModelListUrl
            AiService.ANTHROPIC -> claudeModelListUrl
            AiService.GOOGLE -> geminiModelListUrl
            AiService.XAI -> grokModelListUrl
            AiService.GROQ -> groqModelListUrl
            AiService.DEEPSEEK -> deepSeekModelListUrl
            AiService.MISTRAL -> mistralModelListUrl
            AiService.PERPLEXITY -> perplexityModelListUrl
            AiService.TOGETHER -> togetherModelListUrl
            AiService.OPENROUTER -> openRouterModelListUrl
            AiService.SILICONFLOW -> siliconFlowModelListUrl
            AiService.ZAI -> zaiModelListUrl
            AiService.DUMMY -> dummyModelListUrl
        }
    }

    /**
     * Get the default model list URL for a provider (hardcoded).
     */
    fun getDefaultModelListUrl(service: AiService): String {
        return when (service) {
            AiService.OPENAI -> "https://api.openai.com/v1/models"
            AiService.ANTHROPIC -> "https://api.anthropic.com/v1/models"
            AiService.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/models"
            AiService.XAI -> "https://api.x.ai/v1/models"
            AiService.GROQ -> "https://api.groq.com/openai/v1/models"
            AiService.DEEPSEEK -> "https://api.deepseek.com/models"
            AiService.MISTRAL -> "https://api.mistral.ai/v1/models"
            AiService.PERPLEXITY -> "https://api.perplexity.ai/models"
            AiService.TOGETHER -> "https://api.together.xyz/v1/models"
            AiService.OPENROUTER -> "https://openrouter.ai/api/v1/models"
            AiService.SILICONFLOW -> "https://api.siliconflow.cn/v1/models"
            AiService.ZAI -> "https://api.z.ai/api/paas/v4/models"
            AiService.DUMMY -> "http://localhost:54321/v1/models"
        }
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
     * Get the default parameters preset ID for a provider.
     */
    fun getParamsId(service: AiService): String? {
        return when (service) {
            AiService.OPENAI -> chatGptParamsId
            AiService.ANTHROPIC -> claudeParamsId
            AiService.GOOGLE -> geminiParamsId
            AiService.XAI -> grokParamsId
            AiService.GROQ -> groqParamsId
            AiService.DEEPSEEK -> deepSeekParamsId
            AiService.MISTRAL -> mistralParamsId
            AiService.PERPLEXITY -> perplexityParamsId
            AiService.TOGETHER -> togetherParamsId
            AiService.OPENROUTER -> openRouterParamsId
            AiService.SILICONFLOW -> siliconFlowParamsId
            AiService.ZAI -> zaiParamsId
            AiService.DUMMY -> dummyParamsId
        }
    }

    /**
     * Set the default parameters preset ID for a provider.
     */
    fun withParamsId(service: AiService, paramsId: String?): AiSettings {
        return when (service) {
            AiService.OPENAI -> copy(chatGptParamsId = paramsId)
            AiService.ANTHROPIC -> copy(claudeParamsId = paramsId)
            AiService.GOOGLE -> copy(geminiParamsId = paramsId)
            AiService.XAI -> copy(grokParamsId = paramsId)
            AiService.GROQ -> copy(groqParamsId = paramsId)
            AiService.DEEPSEEK -> copy(deepSeekParamsId = paramsId)
            AiService.MISTRAL -> copy(mistralParamsId = paramsId)
            AiService.PERPLEXITY -> copy(perplexityParamsId = paramsId)
            AiService.TOGETHER -> copy(togetherParamsId = paramsId)
            AiService.OPENROUTER -> copy(openRouterParamsId = paramsId)
            AiService.SILICONFLOW -> copy(siliconFlowParamsId = paramsId)
            AiService.ZAI -> copy(zaiParamsId = paramsId)
            AiService.DUMMY -> copy(dummyParamsId = paramsId)
        }
    }
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
    val outputTokens: Long = 0,
    val totalTokens: Long = 0
) {
    /** Unique key for this provider+model combination */
    val key: String get() = "${provider.name}::$model"
}

/**
 * A single message in a chat conversation.
 */
data class ChatMessage(
    val role: String,      // "system", "user", or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * A saved chat session with all messages.
 */
data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val provider: com.ai.data.AiService,
    val model: String,
    val messages: List<ChatMessage>,
    val parameters: ChatParameters = ChatParameters(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    // Preview text from first user message
    val preview: String
        get() = messages.firstOrNull { it.role == "user" }?.content?.take(50) ?: "Empty chat"
}

/**
 * Parameters for a chat session (subset of AiAgentParameters used for chat).
 */
data class ChatParameters(
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val searchEnabled: Boolean = false,
    val returnCitations: Boolean = true,
    val searchRecency: String? = null
)
