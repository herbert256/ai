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
    val chatGptApiKey: String = "",
    val chatGptModel: String = AiService.OPENAI.defaultModel,
    val chatGptModelSource: ModelSource = ModelSource.API,
    val chatGptManualModels: List<String> = emptyList(),
    val chatGptAdminUrl: String = AiService.OPENAI.adminUrl,
    val chatGptModelListUrl: String = "",  // Custom model list URL (empty = use default)
    val chatGptParametersIds: List<String> = emptyList(),   // Default parameters presets for this provider
    val claudeApiKey: String = "",
    val claudeModel: String = AiService.ANTHROPIC.defaultModel,
    val claudeModelSource: ModelSource = ModelSource.API,
    val claudeManualModels: List<String> = CLAUDE_MODELS,
    val claudeAdminUrl: String = AiService.ANTHROPIC.adminUrl,
    val claudeModelListUrl: String = "",
    val claudeParametersIds: List<String> = emptyList(),
    val geminiApiKey: String = "",
    val geminiModel: String = AiService.GOOGLE.defaultModel,
    val geminiModelSource: ModelSource = ModelSource.API,
    val geminiManualModels: List<String> = emptyList(),
    val geminiAdminUrl: String = AiService.GOOGLE.adminUrl,
    val geminiModelListUrl: String = "",
    val geminiParametersIds: List<String> = emptyList(),
    val grokApiKey: String = "",
    val grokModel: String = AiService.XAI.defaultModel,
    val grokModelSource: ModelSource = ModelSource.API,
    val grokManualModels: List<String> = emptyList(),
    val grokAdminUrl: String = AiService.XAI.adminUrl,
    val grokModelListUrl: String = "",
    val grokParametersIds: List<String> = emptyList(),
    val groqApiKey: String = "",
    val groqModel: String = AiService.GROQ.defaultModel,
    val groqModelSource: ModelSource = ModelSource.API,
    val groqManualModels: List<String> = emptyList(),
    val groqAdminUrl: String = AiService.GROQ.adminUrl,
    val groqModelListUrl: String = "",
    val groqParametersIds: List<String> = emptyList(),
    val deepSeekApiKey: String = "",
    val deepSeekModel: String = AiService.DEEPSEEK.defaultModel,
    val deepSeekModelSource: ModelSource = ModelSource.API,
    val deepSeekManualModels: List<String> = emptyList(),
    val deepSeekAdminUrl: String = AiService.DEEPSEEK.adminUrl,
    val deepSeekModelListUrl: String = "",
    val deepSeekParametersIds: List<String> = emptyList(),
    val mistralApiKey: String = "",
    val mistralModel: String = AiService.MISTRAL.defaultModel,
    val mistralModelSource: ModelSource = ModelSource.API,
    val mistralManualModels: List<String> = emptyList(),
    val mistralAdminUrl: String = AiService.MISTRAL.adminUrl,
    val mistralModelListUrl: String = "",
    val mistralParametersIds: List<String> = emptyList(),
    val perplexityApiKey: String = "",
    val perplexityModel: String = AiService.PERPLEXITY.defaultModel,
    val perplexityModelSource: ModelSource = ModelSource.MANUAL,
    val perplexityManualModels: List<String> = PERPLEXITY_MODELS,
    val perplexityAdminUrl: String = AiService.PERPLEXITY.adminUrl,
    val perplexityModelListUrl: String = "",
    val perplexityParametersIds: List<String> = emptyList(),
    val togetherApiKey: String = "",
    val togetherModel: String = AiService.TOGETHER.defaultModel,
    val togetherModelSource: ModelSource = ModelSource.API,
    val togetherManualModels: List<String> = emptyList(),
    val togetherAdminUrl: String = AiService.TOGETHER.adminUrl,
    val togetherModelListUrl: String = "",
    val togetherParametersIds: List<String> = emptyList(),
    val openRouterApiKey: String = "",
    val openRouterModel: String = AiService.OPENROUTER.defaultModel,
    val openRouterModelSource: ModelSource = ModelSource.API,
    val openRouterManualModels: List<String> = emptyList(),
    val openRouterAdminUrl: String = AiService.OPENROUTER.adminUrl,
    val openRouterModelListUrl: String = "",
    val openRouterParametersIds: List<String> = emptyList(),
    val siliconFlowApiKey: String = "",
    val siliconFlowModel: String = AiService.SILICONFLOW.defaultModel,
    val siliconFlowModelSource: ModelSource = ModelSource.API,
    val siliconFlowManualModels: List<String> = SILICONFLOW_MODELS,
    val siliconFlowAdminUrl: String = AiService.SILICONFLOW.adminUrl,
    val siliconFlowModelListUrl: String = "",
    val siliconFlowParametersIds: List<String> = emptyList(),
    val zaiApiKey: String = "",
    val zaiModel: String = AiService.ZAI.defaultModel,
    val zaiModelSource: ModelSource = ModelSource.API,
    val zaiManualModels: List<String> = ZAI_MODELS,
    val zaiAdminUrl: String = AiService.ZAI.adminUrl,
    val zaiModelListUrl: String = "",
    val zaiParametersIds: List<String> = emptyList(),
    val moonshotApiKey: String = "",
    val moonshotModel: String = AiService.MOONSHOT.defaultModel,
    val moonshotModelSource: ModelSource = ModelSource.API,
    val moonshotManualModels: List<String> = MOONSHOT_MODELS,
    val moonshotAdminUrl: String = AiService.MOONSHOT.adminUrl,
    val moonshotModelListUrl: String = "",
    val moonshotParametersIds: List<String> = emptyList(),
    val cohereApiKey: String = "",
    val cohereModel: String = AiService.COHERE.defaultModel,
    val cohereModelSource: ModelSource = ModelSource.MANUAL,
    val cohereManualModels: List<String> = COHERE_MODELS,
    val cohereAdminUrl: String = AiService.COHERE.adminUrl,
    val cohereModelListUrl: String = "",
    val cohereParametersIds: List<String> = emptyList(),
    val ai21ApiKey: String = "",
    val ai21Model: String = AiService.AI21.defaultModel,
    val ai21ModelSource: ModelSource = ModelSource.MANUAL,
    val ai21ManualModels: List<String> = AI21_MODELS,
    val ai21AdminUrl: String = AiService.AI21.adminUrl,
    val ai21ModelListUrl: String = "",
    val ai21ParametersIds: List<String> = emptyList(),
    val dashScopeApiKey: String = "",
    val dashScopeModel: String = AiService.DASHSCOPE.defaultModel,
    val dashScopeModelSource: ModelSource = ModelSource.MANUAL,
    val dashScopeManualModels: List<String> = DASHSCOPE_MODELS,
    val dashScopeAdminUrl: String = AiService.DASHSCOPE.adminUrl,
    val dashScopeModelListUrl: String = "",
    val dashScopeParametersIds: List<String> = emptyList(),
    val fireworksApiKey: String = "",
    val fireworksModel: String = AiService.FIREWORKS.defaultModel,
    val fireworksModelSource: ModelSource = ModelSource.MANUAL,
    val fireworksManualModels: List<String> = FIREWORKS_MODELS,
    val fireworksAdminUrl: String = AiService.FIREWORKS.adminUrl,
    val fireworksModelListUrl: String = "",
    val fireworksParametersIds: List<String> = emptyList(),
    val cerebrasApiKey: String = "",
    val cerebrasModel: String = AiService.CEREBRAS.defaultModel,
    val cerebrasModelSource: ModelSource = ModelSource.MANUAL,
    val cerebrasManualModels: List<String> = CEREBRAS_MODELS,
    val cerebrasAdminUrl: String = AiService.CEREBRAS.adminUrl,
    val cerebrasModelListUrl: String = "",
    val cerebrasParametersIds: List<String> = emptyList(),
    val sambaNovaApiKey: String = "",
    val sambaNovaModel: String = AiService.SAMBANOVA.defaultModel,
    val sambaNovaModelSource: ModelSource = ModelSource.MANUAL,
    val sambaNovaManualModels: List<String> = SAMBANOVA_MODELS,
    val sambaNovaAdminUrl: String = AiService.SAMBANOVA.adminUrl,
    val sambaNovaModelListUrl: String = "",
    val sambaNovaParametersIds: List<String> = emptyList(),
    val baichuanApiKey: String = "",
    val baichuanModel: String = AiService.BAICHUAN.defaultModel,
    val baichuanModelSource: ModelSource = ModelSource.MANUAL,
    val baichuanManualModels: List<String> = BAICHUAN_MODELS,
    val baichuanAdminUrl: String = AiService.BAICHUAN.adminUrl,
    val baichuanModelListUrl: String = "",
    val baichuanParametersIds: List<String> = emptyList(),
    val stepFunApiKey: String = "",
    val stepFunModel: String = AiService.STEPFUN.defaultModel,
    val stepFunModelSource: ModelSource = ModelSource.MANUAL,
    val stepFunManualModels: List<String> = STEPFUN_MODELS,
    val stepFunAdminUrl: String = AiService.STEPFUN.adminUrl,
    val stepFunModelListUrl: String = "",
    val stepFunParametersIds: List<String> = emptyList(),
    val miniMaxApiKey: String = "",
    val miniMaxModel: String = AiService.MINIMAX.defaultModel,
    val miniMaxModelSource: ModelSource = ModelSource.MANUAL,
    val miniMaxManualModels: List<String> = MINIMAX_MODELS,
    val miniMaxAdminUrl: String = AiService.MINIMAX.adminUrl,
    val miniMaxModelListUrl: String = "",
    val miniMaxParametersIds: List<String> = emptyList(),
    val nvidiaApiKey: String = "",
    val nvidiaModel: String = AiService.NVIDIA.defaultModel,
    val nvidiaModelSource: ModelSource = ModelSource.API,
    val nvidiaManualModels: List<String> = emptyList(),
    val nvidiaAdminUrl: String = AiService.NVIDIA.adminUrl,
    val nvidiaModelListUrl: String = "",
    val nvidiaParametersIds: List<String> = emptyList(),
    val replicateApiKey: String = "",
    val replicateModel: String = AiService.REPLICATE.defaultModel,
    val replicateModelSource: ModelSource = ModelSource.MANUAL,
    val replicateManualModels: List<String> = REPLICATE_MODELS,
    val replicateAdminUrl: String = AiService.REPLICATE.adminUrl,
    val replicateModelListUrl: String = "",
    val replicateParametersIds: List<String> = emptyList(),
    val huggingFaceInferenceApiKey: String = "",
    val huggingFaceInferenceModel: String = AiService.HUGGINGFACE.defaultModel,
    val huggingFaceInferenceModelSource: ModelSource = ModelSource.MANUAL,
    val huggingFaceInferenceManualModels: List<String> = HUGGINGFACE_INFERENCE_MODELS,
    val huggingFaceInferenceAdminUrl: String = AiService.HUGGINGFACE.adminUrl,
    val huggingFaceInferenceModelListUrl: String = "",
    val huggingFaceInferenceParametersIds: List<String> = emptyList(),
    val lambdaApiKey: String = "",
    val lambdaModel: String = AiService.LAMBDA.defaultModel,
    val lambdaModelSource: ModelSource = ModelSource.API,
    val lambdaManualModels: List<String> = emptyList(),
    val lambdaAdminUrl: String = AiService.LAMBDA.adminUrl,
    val lambdaModelListUrl: String = "",
    val lambdaParametersIds: List<String> = emptyList(),
    val leptonApiKey: String = "",
    val leptonModel: String = AiService.LEPTON.defaultModel,
    val leptonModelSource: ModelSource = ModelSource.MANUAL,
    val leptonManualModels: List<String> = LEPTON_MODELS,
    val leptonAdminUrl: String = AiService.LEPTON.adminUrl,
    val leptonModelListUrl: String = "",
    val leptonParametersIds: List<String> = emptyList(),
    val yiApiKey: String = "",
    val yiModel: String = AiService.YI.defaultModel,
    val yiModelSource: ModelSource = ModelSource.API,
    val yiManualModels: List<String> = YI_MODELS,
    val yiAdminUrl: String = AiService.YI.adminUrl,
    val yiModelListUrl: String = "",
    val yiParametersIds: List<String> = emptyList(),
    val doubaoApiKey: String = "",
    val doubaoModel: String = AiService.DOUBAO.defaultModel,
    val doubaoModelSource: ModelSource = ModelSource.MANUAL,
    val doubaoManualModels: List<String> = DOUBAO_MODELS,
    val doubaoAdminUrl: String = AiService.DOUBAO.adminUrl,
    val doubaoModelListUrl: String = "",
    val doubaoParametersIds: List<String> = emptyList(),
    val rekaApiKey: String = "",
    val rekaModel: String = AiService.REKA.defaultModel,
    val rekaModelSource: ModelSource = ModelSource.MANUAL,
    val rekaManualModels: List<String> = REKA_MODELS,
    val rekaAdminUrl: String = AiService.REKA.adminUrl,
    val rekaModelListUrl: String = "",
    val rekaParametersIds: List<String> = emptyList(),
    val writerApiKey: String = "",
    val writerModel: String = AiService.WRITER.defaultModel,
    val writerModelSource: ModelSource = ModelSource.API,
    val writerManualModels: List<String> = WRITER_MODELS,
    val writerAdminUrl: String = AiService.WRITER.adminUrl,
    val writerModelListUrl: String = "",
    val writerParametersIds: List<String> = emptyList(),
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
            AiService.MOONSHOT -> moonshotApiKey
            AiService.COHERE -> cohereApiKey
            AiService.AI21 -> ai21ApiKey
            AiService.DASHSCOPE -> dashScopeApiKey
            AiService.FIREWORKS -> fireworksApiKey
            AiService.CEREBRAS -> cerebrasApiKey
            AiService.SAMBANOVA -> sambaNovaApiKey
            AiService.BAICHUAN -> baichuanApiKey
            AiService.STEPFUN -> stepFunApiKey
            AiService.MINIMAX -> miniMaxApiKey
            AiService.NVIDIA -> nvidiaApiKey
            AiService.REPLICATE -> replicateApiKey
            AiService.HUGGINGFACE -> huggingFaceInferenceApiKey
            AiService.LAMBDA -> lambdaApiKey
            AiService.LEPTON -> leptonApiKey
            AiService.YI -> yiApiKey
            AiService.DOUBAO -> doubaoApiKey
            AiService.REKA -> rekaApiKey
            AiService.WRITER -> writerApiKey
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
            AiService.MOONSHOT -> moonshotModel
            AiService.COHERE -> cohereModel
            AiService.AI21 -> ai21Model
            AiService.DASHSCOPE -> dashScopeModel
            AiService.FIREWORKS -> fireworksModel
            AiService.CEREBRAS -> cerebrasModel
            AiService.SAMBANOVA -> sambaNovaModel
            AiService.BAICHUAN -> baichuanModel
            AiService.STEPFUN -> stepFunModel
            AiService.MINIMAX -> miniMaxModel
            AiService.NVIDIA -> nvidiaModel
            AiService.REPLICATE -> replicateModel
            AiService.HUGGINGFACE -> huggingFaceInferenceModel
            AiService.LAMBDA -> lambdaModel
            AiService.LEPTON -> leptonModel
            AiService.YI -> yiModel
            AiService.DOUBAO -> doubaoModel
            AiService.REKA -> rekaModel
            AiService.WRITER -> writerModel
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
            AiService.MOONSHOT -> copy(moonshotModel = model)
            AiService.COHERE -> copy(cohereModel = model)
            AiService.AI21 -> copy(ai21Model = model)
            AiService.DASHSCOPE -> copy(dashScopeModel = model)
            AiService.FIREWORKS -> copy(fireworksModel = model)
            AiService.CEREBRAS -> copy(cerebrasModel = model)
            AiService.SAMBANOVA -> copy(sambaNovaModel = model)
            AiService.BAICHUAN -> copy(baichuanModel = model)
            AiService.STEPFUN -> copy(stepFunModel = model)
            AiService.MINIMAX -> copy(miniMaxModel = model)
            AiService.NVIDIA -> copy(nvidiaModel = model)
            AiService.REPLICATE -> copy(replicateModel = model)
            AiService.HUGGINGFACE -> copy(huggingFaceInferenceModel = model)
            AiService.LAMBDA -> copy(lambdaModel = model)
            AiService.LEPTON -> copy(leptonModel = model)
            AiService.YI -> copy(yiModel = model)
            AiService.DOUBAO -> copy(doubaoModel = model)
            AiService.REKA -> copy(rekaModel = model)
            AiService.WRITER -> copy(writerModel = model)
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
            AiService.MOONSHOT -> moonshotModelSource
            AiService.COHERE -> cohereModelSource
            AiService.AI21 -> ai21ModelSource
            AiService.DASHSCOPE -> dashScopeModelSource
            AiService.FIREWORKS -> fireworksModelSource
            AiService.CEREBRAS -> cerebrasModelSource
            AiService.SAMBANOVA -> sambaNovaModelSource
            AiService.BAICHUAN -> baichuanModelSource
            AiService.STEPFUN -> stepFunModelSource
            AiService.MINIMAX -> miniMaxModelSource
            AiService.NVIDIA -> nvidiaModelSource
            AiService.REPLICATE -> replicateModelSource
            AiService.HUGGINGFACE -> huggingFaceInferenceModelSource
            AiService.LAMBDA -> lambdaModelSource
            AiService.LEPTON -> leptonModelSource
            AiService.YI -> yiModelSource
            AiService.DOUBAO -> doubaoModelSource
            AiService.REKA -> rekaModelSource
            AiService.WRITER -> writerModelSource
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
            AiService.MOONSHOT -> moonshotManualModels
            AiService.COHERE -> cohereManualModels
            AiService.AI21 -> ai21ManualModels
            AiService.DASHSCOPE -> dashScopeManualModels
            AiService.FIREWORKS -> fireworksManualModels
            AiService.CEREBRAS -> cerebrasManualModels
            AiService.SAMBANOVA -> sambaNovaManualModels
            AiService.BAICHUAN -> baichuanManualModels
            AiService.STEPFUN -> stepFunManualModels
            AiService.MINIMAX -> miniMaxManualModels
            AiService.NVIDIA -> nvidiaManualModels
            AiService.REPLICATE -> replicateManualModels
            AiService.HUGGINGFACE -> huggingFaceInferenceManualModels
            AiService.LAMBDA -> lambdaManualModels
            AiService.LEPTON -> leptonManualModels
            AiService.YI -> yiManualModels
            AiService.DOUBAO -> doubaoManualModels
            AiService.REKA -> rekaManualModels
            AiService.WRITER -> writerManualModels
        }
    }

    fun hasAnyApiKey(): Boolean {
        return chatGptApiKey.isNotBlank() ||
                claudeApiKey.isNotBlank() ||
                geminiApiKey.isNotBlank() ||
                grokApiKey.isNotBlank() ||
                groqApiKey.isNotBlank() ||
                deepSeekApiKey.isNotBlank() ||
                mistralApiKey.isNotBlank() ||
                perplexityApiKey.isNotBlank() ||
                togetherApiKey.isNotBlank() ||
                openRouterApiKey.isNotBlank() ||
                siliconFlowApiKey.isNotBlank() ||
                zaiApiKey.isNotBlank() ||
                moonshotApiKey.isNotBlank() ||
                cohereApiKey.isNotBlank() ||
                ai21ApiKey.isNotBlank() ||
                dashScopeApiKey.isNotBlank() ||
                fireworksApiKey.isNotBlank() ||
                cerebrasApiKey.isNotBlank() ||
                sambaNovaApiKey.isNotBlank() ||
                baichuanApiKey.isNotBlank() ||
                stepFunApiKey.isNotBlank() ||
                miniMaxApiKey.isNotBlank() ||
                nvidiaApiKey.isNotBlank() ||
                replicateApiKey.isNotBlank() ||
                huggingFaceInferenceApiKey.isNotBlank() ||
                lambdaApiKey.isNotBlank() ||
                leptonApiKey.isNotBlank() ||
                yiApiKey.isNotBlank() ||
                doubaoApiKey.isNotBlank() ||
                rekaApiKey.isNotBlank() ||
                writerApiKey.isNotBlank()
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
            AiService.MOONSHOT -> moonshotModelListUrl
            AiService.COHERE -> cohereModelListUrl
            AiService.AI21 -> ai21ModelListUrl
            AiService.DASHSCOPE -> dashScopeModelListUrl
            AiService.FIREWORKS -> fireworksModelListUrl
            AiService.CEREBRAS -> cerebrasModelListUrl
            AiService.SAMBANOVA -> sambaNovaModelListUrl
            AiService.BAICHUAN -> baichuanModelListUrl
            AiService.STEPFUN -> stepFunModelListUrl
            AiService.MINIMAX -> miniMaxModelListUrl
            AiService.NVIDIA -> nvidiaModelListUrl
            AiService.REPLICATE -> replicateModelListUrl
            AiService.HUGGINGFACE -> huggingFaceInferenceModelListUrl
            AiService.LAMBDA -> lambdaModelListUrl
            AiService.LEPTON -> leptonModelListUrl
            AiService.YI -> yiModelListUrl
            AiService.DOUBAO -> doubaoModelListUrl
            AiService.REKA -> rekaModelListUrl
            AiService.WRITER -> writerModelListUrl
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
            AiService.MOONSHOT -> "https://api.moonshot.cn/v1/models"
            AiService.COHERE -> "https://api.cohere.ai/compatibility/v1/models"
            AiService.AI21 -> "https://api.ai21.com/v1/models"
            AiService.DASHSCOPE -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models"
            AiService.FIREWORKS -> "https://api.fireworks.ai/inference/v1/models"
            AiService.CEREBRAS -> "https://api.cerebras.ai/v1/models"
            AiService.SAMBANOVA -> "https://api.sambanova.ai/v1/models"
            AiService.BAICHUAN -> "https://api.baichuan-ai.com/v1/models"
            AiService.STEPFUN -> "https://api.stepfun.com/v1/models"
            AiService.MINIMAX -> "https://api.minimax.io/v1/models"
            AiService.NVIDIA -> "https://integrate.api.nvidia.com/v1/models"
            AiService.REPLICATE -> "https://api.replicate.com/v1/models"
            AiService.HUGGINGFACE -> "https://api-inference.huggingface.co/v1/models"
            AiService.LAMBDA -> "https://api.lambdalabs.com/v1/models"
            AiService.LEPTON -> "https://api.lepton.ai/v1/models"
            AiService.YI -> "https://api.01.ai/v1/models"
            AiService.DOUBAO -> "https://ark.cn-beijing.volces.com/api/v3/models"
            AiService.REKA -> "https://api.reka.ai/v1/models"
            AiService.WRITER -> "https://api.writer.com/v1/models"
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
     * Get the default parameters preset IDs for a provider.
     */
    fun getParametersIds(service: AiService): List<String> {
        return when (service) {
            AiService.OPENAI -> chatGptParametersIds
            AiService.ANTHROPIC -> claudeParametersIds
            AiService.GOOGLE -> geminiParametersIds
            AiService.XAI -> grokParametersIds
            AiService.GROQ -> groqParametersIds
            AiService.DEEPSEEK -> deepSeekParametersIds
            AiService.MISTRAL -> mistralParametersIds
            AiService.PERPLEXITY -> perplexityParametersIds
            AiService.TOGETHER -> togetherParametersIds
            AiService.OPENROUTER -> openRouterParametersIds
            AiService.SILICONFLOW -> siliconFlowParametersIds
            AiService.ZAI -> zaiParametersIds
            AiService.MOONSHOT -> moonshotParametersIds
            AiService.COHERE -> cohereParametersIds
            AiService.AI21 -> ai21ParametersIds
            AiService.DASHSCOPE -> dashScopeParametersIds
            AiService.FIREWORKS -> fireworksParametersIds
            AiService.CEREBRAS -> cerebrasParametersIds
            AiService.SAMBANOVA -> sambaNovaParametersIds
            AiService.BAICHUAN -> baichuanParametersIds
            AiService.STEPFUN -> stepFunParametersIds
            AiService.MINIMAX -> miniMaxParametersIds
            AiService.NVIDIA -> nvidiaParametersIds
            AiService.REPLICATE -> replicateParametersIds
            AiService.HUGGINGFACE -> huggingFaceInferenceParametersIds
            AiService.LAMBDA -> lambdaParametersIds
            AiService.LEPTON -> leptonParametersIds
            AiService.YI -> yiParametersIds
            AiService.DOUBAO -> doubaoParametersIds
            AiService.REKA -> rekaParametersIds
            AiService.WRITER -> writerParametersIds
        }
    }

    /**
     * Set the default parameters preset IDs for a provider.
     */
    fun withParametersIds(service: AiService, paramsIds: List<String>): AiSettings {
        return when (service) {
            AiService.OPENAI -> copy(chatGptParametersIds = paramsIds)
            AiService.ANTHROPIC -> copy(claudeParametersIds = paramsIds)
            AiService.GOOGLE -> copy(geminiParametersIds = paramsIds)
            AiService.XAI -> copy(grokParametersIds = paramsIds)
            AiService.GROQ -> copy(groqParametersIds = paramsIds)
            AiService.DEEPSEEK -> copy(deepSeekParametersIds = paramsIds)
            AiService.MISTRAL -> copy(mistralParametersIds = paramsIds)
            AiService.PERPLEXITY -> copy(perplexityParametersIds = paramsIds)
            AiService.TOGETHER -> copy(togetherParametersIds = paramsIds)
            AiService.OPENROUTER -> copy(openRouterParametersIds = paramsIds)
            AiService.SILICONFLOW -> copy(siliconFlowParametersIds = paramsIds)
            AiService.ZAI -> copy(zaiParametersIds = paramsIds)
            AiService.MOONSHOT -> copy(moonshotParametersIds = paramsIds)
            AiService.COHERE -> copy(cohereParametersIds = paramsIds)
            AiService.AI21 -> copy(ai21ParametersIds = paramsIds)
            AiService.DASHSCOPE -> copy(dashScopeParametersIds = paramsIds)
            AiService.FIREWORKS -> copy(fireworksParametersIds = paramsIds)
            AiService.CEREBRAS -> copy(cerebrasParametersIds = paramsIds)
            AiService.SAMBANOVA -> copy(sambaNovaParametersIds = paramsIds)
            AiService.BAICHUAN -> copy(baichuanParametersIds = paramsIds)
            AiService.STEPFUN -> copy(stepFunParametersIds = paramsIds)
            AiService.MINIMAX -> copy(miniMaxParametersIds = paramsIds)
            AiService.NVIDIA -> copy(nvidiaParametersIds = paramsIds)
            AiService.REPLICATE -> copy(replicateParametersIds = paramsIds)
            AiService.HUGGINGFACE -> copy(huggingFaceInferenceParametersIds = paramsIds)
            AiService.LAMBDA -> copy(lambdaParametersIds = paramsIds)
            AiService.LEPTON -> copy(leptonParametersIds = paramsIds)
            AiService.YI -> copy(yiParametersIds = paramsIds)
            AiService.DOUBAO -> copy(doubaoParametersIds = paramsIds)
            AiService.REKA -> copy(rekaParametersIds = paramsIds)
            AiService.WRITER -> copy(writerParametersIds = paramsIds)
        }
    }

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
