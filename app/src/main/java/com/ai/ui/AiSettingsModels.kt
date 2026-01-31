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
    val manualModels: List<String> = emptyList(),
    val adminUrl: String = "",
    val modelListUrl: String = "",
    val parametersIds: List<String> = emptyList()
)

/**
 * Create default ProviderConfig for a service, including provider-specific defaults
 * for model source and manual model lists.
 */
fun defaultProviderConfig(service: AiService): ProviderConfig {
    val defaultModels: List<String> = when (service) {
        AiService.ANTHROPIC -> CLAUDE_MODELS
        AiService.PERPLEXITY -> PERPLEXITY_MODELS
        AiService.SILICONFLOW -> SILICONFLOW_MODELS
        AiService.ZAI -> ZAI_MODELS
        AiService.MOONSHOT -> MOONSHOT_MODELS
        AiService.COHERE -> COHERE_MODELS
        AiService.AI21 -> AI21_MODELS
        AiService.DASHSCOPE -> DASHSCOPE_MODELS
        AiService.FIREWORKS -> FIREWORKS_MODELS
        AiService.CEREBRAS -> CEREBRAS_MODELS
        AiService.SAMBANOVA -> SAMBANOVA_MODELS
        AiService.BAICHUAN -> BAICHUAN_MODELS
        AiService.STEPFUN -> STEPFUN_MODELS
        AiService.MINIMAX -> MINIMAX_MODELS
        AiService.REPLICATE -> REPLICATE_MODELS
        AiService.HUGGINGFACE -> HUGGINGFACE_INFERENCE_MODELS
        AiService.LEPTON -> LEPTON_MODELS
        AiService.YI -> YI_MODELS
        AiService.DOUBAO -> DOUBAO_MODELS
        AiService.REKA -> REKA_MODELS
        AiService.WRITER -> WRITER_MODELS
        else -> emptyList()
    }
    val defaultModelSource = when (service) {
        AiService.OPENAI, AiService.GOOGLE, AiService.XAI, AiService.GROQ,
        AiService.DEEPSEEK, AiService.MISTRAL, AiService.TOGETHER,
        AiService.OPENROUTER, AiService.SILICONFLOW, AiService.ZAI,
        AiService.MOONSHOT, AiService.NVIDIA, AiService.LAMBDA,
        AiService.YI -> ModelSource.API
        else -> if (defaultModels.isNotEmpty()) ModelSource.MANUAL else ModelSource.API
    }
    return ProviderConfig(
        model = service.defaultModel,
        modelSource = defaultModelSource,
        manualModels = defaultModels,
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
 * Available Moonshot models (hardcoded - Kimi models).
 */
val MOONSHOT_MODELS = listOf(
    "kimi-latest",
    "moonshot-v1-8k",
    "moonshot-v1-32k",
    "moonshot-v1-128k"
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
 * Available Cohere models (hardcoded).
 */
val COHERE_MODELS = listOf(
    "command-a-03-2025",
    "command-r-plus-08-2024",
    "command-r-08-2024",
    "command-r7b-12-2024"
)

/**
 * Available AI21 models (hardcoded).
 */
val AI21_MODELS = listOf(
    "jamba-mini",
    "jamba-large",
    "jamba-mini-1.7",
    "jamba-large-1.7"
)

/**
 * Available DashScope models (hardcoded - Alibaba Cloud Qwen models).
 */
val DASHSCOPE_MODELS = listOf(
    "qwen-plus",
    "qwen-max",
    "qwen-turbo",
    "qwen-long",
    "qwen3-max",
    "qwen3-235b-a22b"
)

/**
 * Available Fireworks models (hardcoded).
 */
val FIREWORKS_MODELS = listOf(
    "accounts/fireworks/models/llama-v3p3-70b-instruct",
    "accounts/fireworks/models/deepseek-r1-0528",
    "accounts/fireworks/models/qwen3-235b-a22b",
    "accounts/fireworks/models/llama-v3p1-8b-instruct"
)

/**
 * Available Cerebras models (hardcoded).
 */
val CEREBRAS_MODELS = listOf(
    "llama-3.3-70b",
    "llama-4-scout-17b-16e-instruct",
    "llama3.1-8b",
    "qwen-3-32b",
    "deepseek-r1-distill-llama-70b"
)

/**
 * Available SambaNova models (hardcoded).
 */
val SAMBANOVA_MODELS = listOf(
    "Meta-Llama-3.3-70B-Instruct",
    "DeepSeek-R1",
    "DeepSeek-V3-0324",
    "Qwen3-32B",
    "Meta-Llama-3.1-8B-Instruct"
)

/**
 * Available Baichuan models (hardcoded).
 */
val BAICHUAN_MODELS = listOf(
    "Baichuan4-Turbo",
    "Baichuan4",
    "Baichuan4-Air",
    "Baichuan3-Turbo",
    "Baichuan3-Turbo-128k"
)

/**
 * Available StepFun models (hardcoded).
 */
val STEPFUN_MODELS = listOf(
    "step-3",
    "step-2-16k",
    "step-2-mini",
    "step-1-8k",
    "step-1-32k",
    "step-1-128k"
)

/**
 * Available MiniMax models (hardcoded).
 */
val MINIMAX_MODELS = listOf(
    "MiniMax-M2.1",
    "MiniMax-M2",
    "MiniMax-M1",
    "MiniMax-Text-01"
)

/**
 * Available Replicate models (hardcoded).
 */
val REPLICATE_MODELS = listOf(
    "meta/meta-llama-3-70b-instruct",
    "meta/meta-llama-3-8b-instruct",
    "mistralai/mistral-7b-instruct-v0.2"
)

/**
 * Available Hugging Face Inference models (hardcoded).
 */
val HUGGINGFACE_INFERENCE_MODELS = listOf(
    "meta-llama/Llama-3.1-70B-Instruct",
    "meta-llama/Llama-3.1-8B-Instruct",
    "mistralai/Mistral-7B-Instruct-v0.3",
    "Qwen/Qwen2.5-72B-Instruct"
)

/**
 * Available Lepton models (hardcoded).
 */
val LEPTON_MODELS = listOf(
    "llama3-1-70b",
    "llama3-1-8b",
    "mistral-7b",
    "gemma2-9b"
)

/**
 * Available 01.AI Yi models (hardcoded).
 */
val YI_MODELS = listOf(
    "yi-lightning",
    "yi-large",
    "yi-medium",
    "yi-spark"
)

/**
 * Available Doubao models (hardcoded).
 */
val DOUBAO_MODELS = listOf(
    "doubao-pro-32k",
    "doubao-pro-128k",
    "doubao-lite-32k",
    "doubao-lite-128k"
)

/**
 * Available Reka models (hardcoded).
 */
val REKA_MODELS = listOf(
    "reka-core",
    "reka-flash",
    "reka-edge"
)

/**
 * Available Writer models (hardcoded).
 */
val WRITER_MODELS = listOf(
    "palmyra-x-004",
    "palmyra-x-003-instruct"
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
    val paramsIds: List<String> = emptyList()  // References to AiParameters IDs (parameter presets)
)

/**
 * AI Flock - a named group of AI Agents that work together.
 */
data class AiFlock(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val agentIds: List<String> = emptyList(),  // List of agent IDs in this flock
    val paramsIds: List<String> = emptyList()  // References to AiParameters IDs (parameter presets)
) {
    // Backward compatibility: read old single paramsId
    @Deprecated("Use paramsIds instead", ReplaceWith("paramsIds"))
    val parametersId: String? get() = paramsIds.firstOrNull()
}

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
    val paramsIds: List<String> = emptyList()  // References to AiParameters IDs (parameter presets)
) {
    // Backward compatibility: read old single paramsId
    @Deprecated("Use paramsIds instead", ReplaceWith("paramsIds"))
    val parametersId: String? get() = paramsIds.firstOrNull()
}

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
        flock: String? = null
    ): String {
        var resolved = promptText
        if (model != null) resolved = resolved.replace("@MODEL@", model)
        if (provider != null) resolved = resolved.replace("@PROVIDER@", provider)
        if (agent != null) resolved = resolved.replace("@AGENT@", agent)
        if (flock != null) resolved = resolved.replace("@SWARM@", flock)
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
    // AI Prompts (internal app prompts)
    val prompts: List<AiPrompt> = emptyList(),
    // API Endpoints per provider (multiple endpoints allowed, one can be default)
    val endpoints: Map<AiService, List<AiEndpoint>> = emptyMap(),
    // Provider states: "ok" (key valid), "error" (key invalid), "not-used" (no key)
    val providerStates: Map<String, String> = emptyMap()
) {
    /**
     * Get the provider state for a service.
     * Returns "ok", "error", or "not-used" based on API key presence and stored state.
     * Untested providers (key present but no stored state) default to "ok" for cold-start.
     */
    fun getProviderState(service: AiService): String {
        if (getApiKey(service).isBlank()) return "not-used"
        val stored = providerStates[service.name]
        return stored ?: "ok"
    }

    /**
     * Check if a provider is active (status "ok").
     */
    fun isProviderActive(service: AiService, developerMode: Boolean): Boolean {
        return getProviderState(service) == "ok"
    }

    /**
     * Get all active providers (status "ok").
     */
    fun getActiveServices(developerMode: Boolean): List<AiService> {
        return AiService.entries.filter { isProviderActive(it, developerMode) }
    }

    /**
     * Return a copy with an updated provider state.
     */
    fun withProviderState(service: AiService, state: String): AiSettings {
        return copy(providerStates = providerStates + (service.name to state))
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

    fun getManualModels(service: AiService): List<String> = getProvider(service).manualModels

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
        }.distinctBy { "${it.provider.name}:${it.model}" }

    // Helper methods for prompts
    fun getPromptByName(name: String): AiPrompt? = prompts.find { it.name.equals(name, ignoreCase = true) }

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
        AiService.MOONSHOT -> listOf(
            AiEndpoint("moonshot-chat", "Chat Completions", "https://api.moonshot.cn/v1/chat/completions", true)
        )
        AiService.COHERE -> listOf(
            AiEndpoint("cohere-chat", "Chat Completions", "https://api.cohere.ai/compatibility/v1/chat/completions", true)
        )
        AiService.AI21 -> listOf(
            AiEndpoint("ai21-chat", "Chat Completions", "https://api.ai21.com/v1/chat/completions", true)
        )
        AiService.DASHSCOPE -> listOf(
            AiEndpoint("dashscope-chat", "Chat Completions", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions", true)
        )
        AiService.FIREWORKS -> listOf(
            AiEndpoint("fireworks-chat", "Chat Completions", "https://api.fireworks.ai/inference/v1/chat/completions", true)
        )
        AiService.CEREBRAS -> listOf(
            AiEndpoint("cerebras-chat", "Chat Completions", "https://api.cerebras.ai/v1/chat/completions", true)
        )
        AiService.SAMBANOVA -> listOf(
            AiEndpoint("sambanova-chat", "Chat Completions", "https://api.sambanova.ai/v1/chat/completions", true)
        )
        AiService.BAICHUAN -> listOf(
            AiEndpoint("baichuan-chat", "Chat Completions", "https://api.baichuan-ai.com/v1/chat/completions", true)
        )
        AiService.STEPFUN -> listOf(
            AiEndpoint("stepfun-chat", "Chat Completions", "https://api.stepfun.com/v1/chat/completions", true)
        )
        AiService.MINIMAX -> listOf(
            AiEndpoint("minimax-chat", "Chat Completions", "https://api.minimax.io/v1/chat/completions", true)
        )
        AiService.NVIDIA -> listOf(
            AiEndpoint("nvidia-chat", "Chat Completions", "https://integrate.api.nvidia.com/v1/chat/completions", true)
        )
        AiService.REPLICATE -> listOf(
            AiEndpoint("replicate-chat", "Chat Completions", "https://api.replicate.com/v1/chat/completions", true)
        )
        AiService.HUGGINGFACE -> listOf(
            AiEndpoint("huggingface-chat", "Chat Completions", "https://api-inference.huggingface.co/v1/chat/completions", true)
        )
        AiService.LAMBDA -> listOf(
            AiEndpoint("lambda-chat", "Chat Completions", "https://api.lambdalabs.com/v1/chat/completions", true)
        )
        AiService.LEPTON -> listOf(
            AiEndpoint("lepton-chat", "Chat Completions", "https://api.lepton.ai/v1/chat/completions", true)
        )
        AiService.YI -> listOf(
            AiEndpoint("yi-chat", "Chat Completions", "https://api.01.ai/v1/chat/completions", true)
        )
        AiService.DOUBAO -> listOf(
            AiEndpoint("doubao-chat", "Chat Completions", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", true)
        )
        AiService.REKA -> listOf(
            AiEndpoint("reka-chat", "Chat Completions", "https://api.reka.ai/v1/chat/completions", true)
        )
        AiService.WRITER -> listOf(
            AiEndpoint("writer-chat", "Chat Completions", "https://api.writer.com/v1/chat/completions", true)
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
                responseFormatJson = if (params.responseFormatJson) true else acc.responseFormatJson,
                searchEnabled = if (params.searchEnabled) true else acc.searchEnabled,
                returnCitations = if (params.returnCitations) true else acc.returnCitations,
                searchRecency = params.searchRecency ?: acc.searchRecency
            )
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
