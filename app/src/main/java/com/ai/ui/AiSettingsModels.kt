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
 * Parameters supported by each AI provider.
 */
val PROVIDER_SUPPORTED_PARAMETERS: Map<AiService, Set<AiParameter>> = mapOf(
    AiService.CHATGPT to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.FREQUENCY_PENALTY,
        AiParameter.PRESENCE_PENALTY,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES,
        AiParameter.SEED,
        AiParameter.RESPONSE_FORMAT
    ),
    AiService.CLAUDE to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.TOP_K,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES
    ),
    AiService.GEMINI to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.TOP_K,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES
    ),
    AiService.GROK to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.FREQUENCY_PENALTY,
        AiParameter.PRESENCE_PENALTY,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES,
        AiParameter.SEARCH_ENABLED
    ),
    AiService.GROQ to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.FREQUENCY_PENALTY,
        AiParameter.PRESENCE_PENALTY,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES,
        AiParameter.SEED
    ),
    AiService.DEEPSEEK to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.FREQUENCY_PENALTY,
        AiParameter.PRESENCE_PENALTY,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES
    ),
    AiService.MISTRAL to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES,
        AiParameter.SEED
    ),
    AiService.PERPLEXITY to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.FREQUENCY_PENALTY,
        AiParameter.PRESENCE_PENALTY,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.RETURN_CITATIONS,
        AiParameter.SEARCH_RECENCY
    ),
    AiService.TOGETHER to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.TOP_K,
        AiParameter.FREQUENCY_PENALTY,
        AiParameter.PRESENCE_PENALTY,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES
    ),
    AiService.OPENROUTER to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.TOP_K,
        AiParameter.FREQUENCY_PENALTY,
        AiParameter.PRESENCE_PENALTY,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES,
        AiParameter.SEED
    ),
    AiService.SILICONFLOW to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.TOP_P,
        AiParameter.TOP_K,
        AiParameter.FREQUENCY_PENALTY,
        AiParameter.PRESENCE_PENALTY,
        AiParameter.SYSTEM_PROMPT,
        AiParameter.STOP_SEQUENCES
    ),
    AiService.DUMMY to setOf(
        AiParameter.TEMPERATURE,
        AiParameter.MAX_TOKENS,
        AiParameter.SYSTEM_PROMPT
    )
)

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
 * AI Agent - user-created configuration combining provider, model, API key, and parameters.
 */
data class AiAgent(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val provider: AiService,           // Reference to provider enum
    val model: String,                 // Model name
    val apiKey: String,                // API key for this agent
    val parameters: AiAgentParameters = AiAgentParameters()  // Optional parameters
)

/**
 * AI Settings data class for storing API keys for various AI services.
 */
data class AiSettings(
    val chatGptApiKey: String = "",
    val chatGptModel: String = "gpt-4o-mini",
    val chatGptModelSource: ModelSource = ModelSource.API,
    val chatGptManualModels: List<String> = emptyList(),
    val claudeApiKey: String = "",
    val claudeModel: String = "claude-sonnet-4-20250514",
    val claudeModelSource: ModelSource = ModelSource.MANUAL,
    val claudeManualModels: List<String> = CLAUDE_MODELS,
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.0-flash",
    val geminiModelSource: ModelSource = ModelSource.API,
    val geminiManualModels: List<String> = emptyList(),
    val grokApiKey: String = "",
    val grokModel: String = "grok-3-mini",
    val grokModelSource: ModelSource = ModelSource.API,
    val grokManualModels: List<String> = emptyList(),
    val groqApiKey: String = "",
    val groqModel: String = "llama-3.3-70b-versatile",
    val groqModelSource: ModelSource = ModelSource.API,
    val groqManualModels: List<String> = emptyList(),
    val deepSeekApiKey: String = "",
    val deepSeekModel: String = "deepseek-chat",
    val deepSeekModelSource: ModelSource = ModelSource.API,
    val deepSeekManualModels: List<String> = emptyList(),
    val mistralApiKey: String = "",
    val mistralModel: String = "mistral-small-latest",
    val mistralModelSource: ModelSource = ModelSource.API,
    val mistralManualModels: List<String> = emptyList(),
    val perplexityApiKey: String = "",
    val perplexityModel: String = "sonar",
    val perplexityModelSource: ModelSource = ModelSource.MANUAL,
    val perplexityManualModels: List<String> = PERPLEXITY_MODELS,
    val togetherApiKey: String = "",
    val togetherModel: String = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
    val togetherModelSource: ModelSource = ModelSource.API,
    val togetherManualModels: List<String> = emptyList(),
    val openRouterApiKey: String = "",
    val openRouterModel: String = "anthropic/claude-3.5-sonnet",
    val openRouterModelSource: ModelSource = ModelSource.API,
    val openRouterManualModels: List<String> = emptyList(),
    val siliconFlowApiKey: String = "",
    val siliconFlowModel: String = "Qwen/Qwen2.5-7B-Instruct",
    val siliconFlowModelSource: ModelSource = ModelSource.MANUAL,
    val siliconFlowManualModels: List<String> = SILICONFLOW_MODELS,
    val dummyApiKey: String = "",
    val dummyModel: String = "dummy-model",
    val dummyManualModels: List<String> = listOf("dummy-model"),
    // AI Agents
    val agents: List<AiAgent> = emptyList()
) {
    fun getApiKey(service: AiService): String {
        return when (service) {
            AiService.CHATGPT -> chatGptApiKey
            AiService.CLAUDE -> claudeApiKey
            AiService.GEMINI -> geminiApiKey
            AiService.GROK -> grokApiKey
            AiService.GROQ -> groqApiKey
            AiService.DEEPSEEK -> deepSeekApiKey
            AiService.MISTRAL -> mistralApiKey
            AiService.PERPLEXITY -> perplexityApiKey
            AiService.TOGETHER -> togetherApiKey
            AiService.OPENROUTER -> openRouterApiKey
            AiService.SILICONFLOW -> siliconFlowApiKey
            AiService.DUMMY -> dummyApiKey
        }
    }

    fun getModel(service: AiService): String {
        return when (service) {
            AiService.CHATGPT -> chatGptModel
            AiService.CLAUDE -> claudeModel
            AiService.GEMINI -> geminiModel
            AiService.GROK -> grokModel
            AiService.GROQ -> groqModel
            AiService.DEEPSEEK -> deepSeekModel
            AiService.MISTRAL -> mistralModel
            AiService.PERPLEXITY -> perplexityModel
            AiService.TOGETHER -> togetherModel
            AiService.OPENROUTER -> openRouterModel
            AiService.SILICONFLOW -> siliconFlowModel
            AiService.DUMMY -> dummyModel
        }
    }

    fun withModel(service: AiService, model: String): AiSettings {
        return when (service) {
            AiService.CHATGPT -> copy(chatGptModel = model)
            AiService.CLAUDE -> copy(claudeModel = model)
            AiService.GEMINI -> copy(geminiModel = model)
            AiService.GROK -> copy(grokModel = model)
            AiService.GROQ -> copy(groqModel = model)
            AiService.DEEPSEEK -> copy(deepSeekModel = model)
            AiService.MISTRAL -> copy(mistralModel = model)
            AiService.PERPLEXITY -> copy(perplexityModel = model)
            AiService.TOGETHER -> copy(togetherModel = model)
            AiService.OPENROUTER -> copy(openRouterModel = model)
            AiService.SILICONFLOW -> copy(siliconFlowModel = model)
            AiService.DUMMY -> this
        }
    }

    fun getModelSource(service: AiService): ModelSource {
        return when (service) {
            AiService.CHATGPT -> chatGptModelSource
            AiService.CLAUDE -> claudeModelSource
            AiService.GEMINI -> geminiModelSource
            AiService.GROK -> grokModelSource
            AiService.GROQ -> groqModelSource
            AiService.DEEPSEEK -> deepSeekModelSource
            AiService.MISTRAL -> mistralModelSource
            AiService.PERPLEXITY -> perplexityModelSource
            AiService.TOGETHER -> togetherModelSource
            AiService.OPENROUTER -> openRouterModelSource
            AiService.SILICONFLOW -> siliconFlowModelSource
            AiService.DUMMY -> ModelSource.MANUAL
        }
    }

    fun getManualModels(service: AiService): List<String> {
        return when (service) {
            AiService.CHATGPT -> chatGptManualModels
            AiService.CLAUDE -> claudeManualModels
            AiService.GEMINI -> geminiManualModels
            AiService.GROK -> grokManualModels
            AiService.GROQ -> groqManualModels
            AiService.DEEPSEEK -> deepSeekManualModels
            AiService.MISTRAL -> mistralManualModels
            AiService.PERPLEXITY -> perplexityManualModels
            AiService.TOGETHER -> togetherManualModels
            AiService.OPENROUTER -> openRouterManualModels
            AiService.SILICONFLOW -> siliconFlowManualModels
            AiService.DUMMY -> emptyList()
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
                siliconFlowApiKey.isNotBlank()
    }

    fun getConfiguredServices(): List<AiService> {
        return AiService.entries.filter { getApiKey(it).isNotBlank() }
    }

    // Helper methods for agents
    fun getAgentById(id: String): AiAgent? = agents.find { it.id == id }

    fun getConfiguredAgents(): List<AiAgent> = agents.filter { it.apiKey.isNotBlank() }
}
