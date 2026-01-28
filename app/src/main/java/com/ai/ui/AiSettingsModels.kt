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
    val agentIds: List<String> = emptyList()  // List of agent IDs in this swarm
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
    val chatGptApiKey: String = "",
    val chatGptModel: String = AiService.OPENAI.defaultModel,
    val chatGptModelSource: ModelSource = ModelSource.API,
    val chatGptManualModels: List<String> = emptyList(),
    val chatGptAdminUrl: String = AiService.OPENAI.adminUrl,
    val claudeApiKey: String = "",
    val claudeModel: String = AiService.ANTHROPIC.defaultModel,
    val claudeModelSource: ModelSource = ModelSource.API,
    val claudeManualModels: List<String> = CLAUDE_MODELS,
    val claudeAdminUrl: String = AiService.ANTHROPIC.adminUrl,
    val geminiApiKey: String = "",
    val geminiModel: String = AiService.GOOGLE.defaultModel,
    val geminiModelSource: ModelSource = ModelSource.API,
    val geminiManualModels: List<String> = emptyList(),
    val geminiAdminUrl: String = AiService.GOOGLE.adminUrl,
    val grokApiKey: String = "",
    val grokModel: String = AiService.XAI.defaultModel,
    val grokModelSource: ModelSource = ModelSource.API,
    val grokManualModels: List<String> = emptyList(),
    val grokAdminUrl: String = AiService.XAI.adminUrl,
    val groqApiKey: String = "",
    val groqModel: String = AiService.GROQ.defaultModel,
    val groqModelSource: ModelSource = ModelSource.API,
    val groqManualModels: List<String> = emptyList(),
    val groqAdminUrl: String = AiService.GROQ.adminUrl,
    val deepSeekApiKey: String = "",
    val deepSeekModel: String = AiService.DEEPSEEK.defaultModel,
    val deepSeekModelSource: ModelSource = ModelSource.API,
    val deepSeekManualModels: List<String> = emptyList(),
    val deepSeekAdminUrl: String = AiService.DEEPSEEK.adminUrl,
    val mistralApiKey: String = "",
    val mistralModel: String = AiService.MISTRAL.defaultModel,
    val mistralModelSource: ModelSource = ModelSource.API,
    val mistralManualModels: List<String> = emptyList(),
    val mistralAdminUrl: String = AiService.MISTRAL.adminUrl,
    val perplexityApiKey: String = "",
    val perplexityModel: String = AiService.PERPLEXITY.defaultModel,
    val perplexityModelSource: ModelSource = ModelSource.MANUAL,
    val perplexityManualModels: List<String> = PERPLEXITY_MODELS,
    val perplexityAdminUrl: String = AiService.PERPLEXITY.adminUrl,
    val togetherApiKey: String = "",
    val togetherModel: String = AiService.TOGETHER.defaultModel,
    val togetherModelSource: ModelSource = ModelSource.API,
    val togetherManualModels: List<String> = emptyList(),
    val togetherAdminUrl: String = AiService.TOGETHER.adminUrl,
    val openRouterApiKey: String = "",
    val openRouterModel: String = AiService.OPENROUTER.defaultModel,
    val openRouterModelSource: ModelSource = ModelSource.API,
    val openRouterManualModels: List<String> = emptyList(),
    val openRouterAdminUrl: String = AiService.OPENROUTER.adminUrl,
    val siliconFlowApiKey: String = "",
    val siliconFlowModel: String = AiService.SILICONFLOW.defaultModel,
    val siliconFlowModelSource: ModelSource = ModelSource.API,
    val siliconFlowManualModels: List<String> = SILICONFLOW_MODELS,
    val siliconFlowAdminUrl: String = AiService.SILICONFLOW.adminUrl,
    val zaiApiKey: String = "",
    val zaiModel: String = AiService.ZAI.defaultModel,
    val zaiModelSource: ModelSource = ModelSource.API,
    val zaiManualModels: List<String> = ZAI_MODELS,
    val zaiAdminUrl: String = AiService.ZAI.adminUrl,
    val dummyApiKey: String = "",
    val dummyModel: String = AiService.DUMMY.defaultModel,
    val dummyModelSource: ModelSource = ModelSource.API,
    val dummyManualModels: List<String> = listOf("dummy-model"),
    val dummyAdminUrl: String = AiService.DUMMY.adminUrl,
    // AI Agents
    val agents: List<AiAgent> = emptyList(),
    // AI Swarms
    val swarms: List<AiSwarm> = emptyList(),
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
            AiService.DUMMY -> this
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

    fun getConfiguredAgents(): List<AiAgent> = agents.filter { it.apiKey.isNotBlank() }

    // Helper methods for swarms
    fun getSwarmById(id: String): AiSwarm? = swarms.find { it.id == id }

    fun getAgentsForSwarm(swarm: AiSwarm): List<AiAgent> =
        swarm.agentIds.mapNotNull { agentId -> getAgentById(agentId) }

    fun getAgentsForSwarms(swarmIds: Set<String>): List<AiAgent> =
        swarmIds.flatMap { swarmId ->
            getSwarmById(swarmId)?.let { getAgentsForSwarm(it) } ?: emptyList()
        }.distinctBy { it.id }

    // Helper methods for prompts
    fun getPromptByName(name: String): AiPrompt? = prompts.find { it.name.equals(name, ignoreCase = true) }

    fun getPromptById(id: String): AiPrompt? = prompts.find { it.id == id }

    fun getAgentForPrompt(prompt: AiPrompt): AiAgent? = getAgentById(prompt.agentId)

    // Helper methods for endpoints
    fun getEndpointsForProvider(provider: AiService): List<AiEndpoint> = endpoints[provider] ?: emptyList()

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
