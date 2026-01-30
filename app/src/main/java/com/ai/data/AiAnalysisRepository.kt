package com.ai.data

import android.content.Context
import com.ai.ui.AiAgentParameters
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Token usage statistics from AI API response.
 */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val apiCost: Double? = null  // Cost from API response (highest priority, source "API")
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

/**
 * Extract cost from OpenAiUsage, checking all variations.
 * Returns the cost in USD, or null if not available.
 */
fun extractApiCost(usage: OpenAiUsage?, provider: AiService? = null): Double? {
    if (usage == null) return null

    // OpenRouter: use cost from API response
    if (provider == AiService.OPENROUTER) {
        usage.cost?.let { return it }
    }
    // Note: Perplexity's 'cost' field is intentionally ignored because their API
    // returns costs ~6x higher than published pricing ($6/M vs $1/M).

    // Check xAI cost_in_usd_ticks
    usage.cost_in_usd_ticks?.let { ticks ->
        return ticks / AiAnalysisRepository.XAI_COST_TICKS_DIVISOR
    }
    return null
}

/**
 * Extract cost from ClaudeUsage, checking all variations.
 */
fun extractApiCost(usage: ClaudeUsage?): Double? {
    if (usage == null) return null
    usage.cost?.let { return it }
    usage.cost_in_usd_ticks?.let { return it / 10_000_000_000.0 }  // 1e10
    usage.cost_usd?.total_cost?.let { return it }
    return null
}

/**
 * Extract cost from GeminiUsageMetadata, checking all variations.
 */
fun extractApiCost(usage: GeminiUsageMetadata?): Double? {
    if (usage == null) return null
    usage.cost?.let { return it }
    usage.cost_in_usd_ticks?.let { return it / 10_000_000_000.0 }  // 1e10
    usage.cost_usd?.total_cost?.let { return it }
    return null
}

/**
 * Response from AI analysis containing either the analysis text or an error message.
 */
data class AiAnalysisResponse(
    val service: AiService,
    val analysis: String?,
    val error: String?,
    val tokenUsage: TokenUsage? = null,
    val agentName: String? = null,  // Name of the agent that generated this response (for three-tier architecture)
    val promptUsed: String? = null,  // The actual prompt sent to the AI (with @FEN@ etc. replaced)
    val citations: List<String>? = null,  // Citations/sources returned by AI (e.g., Perplexity)
    val searchResults: List<SearchResult>? = null,  // Search results returned by AI (e.g., Grok, Perplexity)
    val relatedQuestions: List<String>? = null,  // Related questions returned by AI (e.g., Perplexity)
    val rawUsageJson: String? = null,  // Raw usage/usageMetadata JSON for developer mode
    val httpHeaders: String? = null,  // HTTP response headers for developer mode
    val httpStatusCode: Int? = null  // HTTP status code (captured even on parse errors)
) {
    val isSuccess: Boolean get() = analysis != null && error == null

    // Display name: use agent name if available, otherwise service name
    val displayName: String get() = agentName ?: service.displayName
}

/**
 * Repository for making AI analysis requests to various AI services.
 */
class AiAnalysisRepository {
    companion object {
        internal const val RETRY_DELAY_MS = 500L
        internal const val TEST_PROMPT = "Reply with exactly: OK"
        internal const val XAI_COST_TICKS_DIVISOR = 10_000_000_000.0
    }

    internal val openAiApi = AiApiFactory.createOpenAiApi()
    internal val claudeApi = AiApiFactory.createClaudeApi()
    internal val geminiApi = AiApiFactory.createGeminiApi()
    internal val grokApi = AiApiFactory.createGrokApi()
    internal val groqApi = AiApiFactory.createGroqApi()
    internal val deepSeekApi = AiApiFactory.createDeepSeekApi()
    internal val mistralApi = AiApiFactory.createMistralApi()
    internal val perplexityApi = AiApiFactory.createPerplexityApi()
    internal val togetherApi = AiApiFactory.createTogetherApi()
    internal val openRouterApi = AiApiFactory.createOpenRouterApi()
    internal val siliconFlowApi = AiApiFactory.createSiliconFlowApi()
    internal val zaiApi = AiApiFactory.createZaiApi()
    internal val moonshotApi = AiApiFactory.createMoonshotApi()
    internal val cohereApi = AiApiFactory.createCohereApi()
    internal val ai21Api = AiApiFactory.createAi21Api()
    internal val dashScopeApi = AiApiFactory.createDashScopeApi()
    internal val fireworksApi = AiApiFactory.createFireworksApi()
    internal val cerebrasApi = AiApiFactory.createCerebrasApi()
    internal val sambaNovaApi = AiApiFactory.createSambaNovaApi()
    internal val baichuanApi = AiApiFactory.createBaichuanApi()
    internal val stepFunApi = AiApiFactory.createStepFunApi()
    internal val miniMaxApi = AiApiFactory.createMiniMaxApi()
    internal val nvidiaApi = AiApiFactory.createNvidiaApi()
    internal val replicateApi = AiApiFactory.createReplicateApi()
    internal val huggingFaceInferenceApi = AiApiFactory.createHuggingFaceInferenceApi()
    internal val lambdaApi = AiApiFactory.createLambdaApi()
    internal val leptonApi = AiApiFactory.createLeptonApi()
    internal val yiApi = AiApiFactory.createYiApi()
    internal val doubaoApi = AiApiFactory.createDoubaoApi()
    internal val rekaApi = AiApiFactory.createRekaApi()
    internal val writerApi = AiApiFactory.createWriterApi()

    // Streaming API instances
    internal val openAiStreamApi = AiApiFactory.createOpenAiStreamApi()
    internal val claudeStreamApi = AiApiFactory.createClaudeStreamApi()
    internal val geminiStreamApi = AiApiFactory.createGeminiStreamApi()
    internal val grokStreamApi = AiApiFactory.createGrokStreamApi()
    internal val groqStreamApi = AiApiFactory.createGroqStreamApi()
    internal val deepSeekStreamApi = AiApiFactory.createDeepSeekStreamApi()
    internal val mistralStreamApi = AiApiFactory.createMistralStreamApi()
    internal val perplexityStreamApi = AiApiFactory.createPerplexityStreamApi()
    internal val togetherStreamApi = AiApiFactory.createTogetherStreamApi()
    internal val openRouterStreamApi = AiApiFactory.createOpenRouterStreamApi()
    internal val siliconFlowStreamApi = AiApiFactory.createSiliconFlowStreamApi()
    internal val zaiStreamApi = AiApiFactory.createZaiStreamApi()
    internal val moonshotStreamApi = AiApiFactory.createMoonshotStreamApi()
    internal val cohereStreamApi = AiApiFactory.createCohereStreamApi()
    internal val ai21StreamApi = AiApiFactory.createAi21StreamApi()
    internal val dashScopeStreamApi = AiApiFactory.createDashScopeStreamApi()
    internal val fireworksStreamApi = AiApiFactory.createFireworksStreamApi()
    internal val cerebrasStreamApi = AiApiFactory.createCerebrasStreamApi()
    internal val sambaNovaStreamApi = AiApiFactory.createSambaNovaStreamApi()
    internal val baichuanStreamApi = AiApiFactory.createBaichuanStreamApi()
    internal val stepFunStreamApi = AiApiFactory.createStepFunStreamApi()
    internal val miniMaxStreamApi = AiApiFactory.createMiniMaxStreamApi()
    internal val nvidiaStreamApi = AiApiFactory.createNvidiaStreamApi()
    internal val replicateStreamApi = AiApiFactory.createReplicateStreamApi()
    internal val huggingFaceInferenceStreamApi = AiApiFactory.createHuggingFaceInferenceStreamApi()
    internal val lambdaStreamApi = AiApiFactory.createLambdaStreamApi()
    internal val leptonStreamApi = AiApiFactory.createLeptonStreamApi()
    internal val yiStreamApi = AiApiFactory.createYiStreamApi()
    internal val doubaoStreamApi = AiApiFactory.createDoubaoStreamApi()
    internal val rekaStreamApi = AiApiFactory.createRekaStreamApi()
    internal val writerStreamApi = AiApiFactory.createWriterStreamApi()

    // Gson instance for pretty printing usage JSON
    internal val gson = GsonBuilder().setPrettyPrinting().create()

    // Helper to convert usage object to pretty JSON
    internal fun formatUsageJson(usage: Any?): String? {
        return usage?.let { gson.toJson(it) }
    }

    // Helper to format HTTP headers as a string
    internal fun formatHeaders(headers: Headers): String {
        return headers.toMultimap().entries.joinToString("\n") { (name, values) ->
            "$name: ${values.joinToString(", ")}"
        }
    }

    /**
     * Formats the current date as "Saturday, January 24th".
     */
    private fun formatCurrentDate(): String {
        val today = LocalDate.now()
        val dayOfWeek = today.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
        val month = today.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
        val dayOfMonth = today.dayOfMonth
        val ordinalSuffix = when {
            dayOfMonth in 11..13 -> "th"
            dayOfMonth % 10 == 1 -> "st"
            dayOfMonth % 10 == 2 -> "nd"
            dayOfMonth % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$dayOfWeek, $month $dayOfMonth$ordinalSuffix"
    }

    /**
     * Builds the chess analysis prompt by replacing @FEN@ and @DATE@ placeholders.
     */
    private fun buildChessPrompt(promptTemplate: String, fen: String): String {
        return promptTemplate
            .replace("@FEN@", fen)
            .replace("@DATE@", formatCurrentDate())
    }

    /**
     * Merge agent parameters with optional override parameters.
     * Override parameters take precedence for non-null values.
     */
    private fun mergeParameters(
        agentParams: com.ai.ui.AiAgentParameters,
        overrideParams: com.ai.ui.AiAgentParameters?
    ): com.ai.ui.AiAgentParameters {
        if (overrideParams == null) return agentParams
        return com.ai.ui.AiAgentParameters(
            temperature = overrideParams.temperature ?: agentParams.temperature,
            maxTokens = overrideParams.maxTokens ?: agentParams.maxTokens,
            topP = overrideParams.topP ?: agentParams.topP,
            topK = overrideParams.topK ?: agentParams.topK,
            frequencyPenalty = overrideParams.frequencyPenalty ?: agentParams.frequencyPenalty,
            presencePenalty = overrideParams.presencePenalty ?: agentParams.presencePenalty,
            systemPrompt = if (overrideParams.systemPrompt?.isNotBlank() == true) overrideParams.systemPrompt else agentParams.systemPrompt,
            stopSequences = if (overrideParams.stopSequences?.isNotEmpty() == true) overrideParams.stopSequences else agentParams.stopSequences,
            seed = overrideParams.seed ?: agentParams.seed,
            responseFormatJson = overrideParams.responseFormatJson || agentParams.responseFormatJson,
            searchEnabled = overrideParams.searchEnabled || agentParams.searchEnabled,
            returnCitations = overrideParams.returnCitations && agentParams.returnCitations,
            searchRecency = overrideParams.searchRecency ?: agentParams.searchRecency
        )
    }

    /**
     * Filter parameters based on supported parameters for the model.
     * Only called when user has set advanced parameters (overrideParams).
     *
     * @param params The merged parameters
     * @param supportedParams List of supported parameter names from OpenRouter, or null if not available
     * @return Filtered parameters with unsupported parameters set to null/default
     */
    private fun filterParametersBySupported(
        params: com.ai.ui.AiAgentParameters,
        supportedParams: List<String>?
    ): com.ai.ui.AiAgentParameters {
        // If no supported params info, return original (use all parameters)
        if (supportedParams == null) return params

        // Map our parameter names to OpenRouter parameter names
        // OpenRouter uses: max_tokens, temperature, top_p, top_k, frequency_penalty, presence_penalty, stop, seed, etc.
        return com.ai.ui.AiAgentParameters(
            temperature = if ("temperature" in supportedParams) params.temperature else null,
            maxTokens = if ("max_tokens" in supportedParams) params.maxTokens else null,
            topP = if ("top_p" in supportedParams) params.topP else null,
            topK = if ("top_k" in supportedParams) params.topK else null,
            frequencyPenalty = if ("frequency_penalty" in supportedParams) params.frequencyPenalty else null,
            presencePenalty = if ("presence_penalty" in supportedParams) params.presencePenalty else null,
            systemPrompt = params.systemPrompt,  // Always allow system prompt
            stopSequences = if ("stop" in supportedParams) params.stopSequences else null,
            seed = if ("seed" in supportedParams) params.seed else null,
            responseFormatJson = if ("response_format" in supportedParams) params.responseFormatJson else false,
            searchEnabled = params.searchEnabled,  // Provider-specific, always pass
            returnCitations = params.returnCitations,  // Provider-specific, always pass
            searchRecency = params.searchRecency  // Provider-specific, always pass
        )
    }

    /**
     * Analyze a chess position using an AI Agent configuration.
     * Uses the agent's provider, model, and API key.
     * @param overrideParams Optional parameters to override agent parameters for this specific call
     * @param context Optional context for looking up supported parameters (only needed when overrideParams is set)
     */
    suspend fun analyzePositionWithAgent(
        agent: com.ai.ui.AiAgent,
        fen: String,
        prompt: String,
        overrideParams: com.ai.ui.AiAgentParameters? = null,
        context: Context? = null
    ): AiAnalysisResponse = withContext(Dispatchers.IO) {
        if (agent.apiKey.isBlank()) {
            return@withContext AiAnalysisResponse(
                service = agent.provider,
                analysis = null,
                error = "API key not configured for agent ${agent.name}",
                agentName = agent.name
            )
        }

        val finalPrompt = buildChessPrompt(prompt, fen)

        suspend fun makeApiCall(): AiAnalysisResponse {
            var params = mergeParameters(agent.parameters, overrideParams)

            // If user has set advanced parameters, filter by supported parameters
            if (overrideParams != null && context != null) {
                val supportedParams = PricingCache.getSupportedParameters(context, agent.provider, agent.model)
                params = filterParametersBySupported(params, supportedParams)
            }
            val result = when (agent.provider) {
                AiService.OPENAI -> analyzeWithChatGpt(agent.apiKey, finalPrompt, agent.model, params)
                AiService.ANTHROPIC -> analyzeWithClaude(agent.apiKey, finalPrompt, agent.model, params)
                AiService.GOOGLE -> analyzeWithGemini(agent.apiKey, finalPrompt, agent.model, params)
                AiService.XAI -> analyzeWithGrok(agent.apiKey, finalPrompt, agent.model, params)
                AiService.GROQ -> analyzeWithGroq(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DEEPSEEK -> analyzeWithDeepSeek(agent.apiKey, finalPrompt, agent.model, params)
                AiService.MISTRAL -> analyzeWithMistral(agent.apiKey, finalPrompt, agent.model, params)
                AiService.PERPLEXITY -> analyzeWithPerplexity(agent.apiKey, finalPrompt, agent.model, params)
                AiService.TOGETHER -> analyzeWithTogether(agent.apiKey, finalPrompt, agent.model, params)
                AiService.OPENROUTER -> analyzeWithOpenRouter(agent.apiKey, finalPrompt, agent.model, params)
                AiService.SILICONFLOW -> analyzeWithSiliconFlow(agent.apiKey, finalPrompt, agent.model, params)
                AiService.ZAI -> analyzeWithZai(agent.apiKey, finalPrompt, agent.model, params)
                AiService.MOONSHOT -> analyzeWithMoonshot(agent.apiKey, finalPrompt, agent.model, params)
                AiService.COHERE -> analyzeWithCohere(agent.apiKey, finalPrompt, agent.model, params)
                AiService.AI21 -> analyzeWithAi21(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DASHSCOPE -> analyzeWithDashScope(agent.apiKey, finalPrompt, agent.model, params)
                AiService.FIREWORKS -> analyzeWithFireworks(agent.apiKey, finalPrompt, agent.model, params)
                AiService.CEREBRAS -> analyzeWithCerebras(agent.apiKey, finalPrompt, agent.model, params)
                AiService.SAMBANOVA -> analyzeWithSambaNova(agent.apiKey, finalPrompt, agent.model, params)
                AiService.BAICHUAN -> analyzeWithBaichuan(agent.apiKey, finalPrompt, agent.model, params)
                AiService.STEPFUN -> analyzeWithStepFun(agent.apiKey, finalPrompt, agent.model, params)
                AiService.MINIMAX -> analyzeWithMiniMax(agent.apiKey, finalPrompt, agent.model, params)
                AiService.NVIDIA -> analyzeWithNvidia(agent.apiKey, finalPrompt, agent.model, params)
                AiService.REPLICATE -> analyzeWithReplicate(agent.apiKey, finalPrompt, agent.model, params)
                AiService.HUGGINGFACE -> analyzeWithHuggingFaceInference(agent.apiKey, finalPrompt, agent.model, params)
                AiService.LAMBDA -> analyzeWithLambda(agent.apiKey, finalPrompt, agent.model, params)
                AiService.LEPTON -> analyzeWithLepton(agent.apiKey, finalPrompt, agent.model, params)
                AiService.YI -> analyzeWithYi(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DOUBAO -> analyzeWithDoubao(agent.apiKey, finalPrompt, agent.model, params)
                AiService.REKA -> analyzeWithReka(agent.apiKey, finalPrompt, agent.model, params)
                AiService.WRITER -> analyzeWithWriter(agent.apiKey, finalPrompt, agent.model, params)
            }
            // Add agent name and prompt used to result
            return result.copy(agentName = agent.name, promptUsed = finalPrompt)
        }

        // First attempt
        try {
            val result = makeApiCall()
            if (result.isSuccess) {
                return@withContext result
            }
            android.util.Log.w("AiAnalysis", "Agent ${agent.name} first attempt failed: ${result.error}, retrying...")
            delay(RETRY_DELAY_MS)
            return@withContext makeApiCall()
        } catch (e: Exception) {
            android.util.Log.w("AiAnalysis", "Agent ${agent.name} first attempt exception: ${e.message}, retrying...")
            try {
                delay(RETRY_DELAY_MS)
                return@withContext makeApiCall()
            } catch (e2: Exception) {
                return@withContext AiAnalysisResponse(
                    service = agent.provider,
                    analysis = null,
                    error = "Network error after retry: ${e2.message ?: "Unknown error"}",
                    agentName = agent.name
                )
            }
        }
    }

    /**
     * Analyze a player using an AI Agent configuration (no FEN, just prompt).
     */
    suspend fun analyzePlayerWithAgent(
        agent: com.ai.ui.AiAgent,
        prompt: String
    ): AiAnalysisResponse = withContext(Dispatchers.IO) {
        if (agent.apiKey.isBlank()) {
            return@withContext AiAnalysisResponse(
                service = agent.provider,
                analysis = null,
                error = "API key not configured for agent ${agent.name}",
                agentName = agent.name
            )
        }

        // Replace @DATE@ placeholder
        val finalPrompt = prompt.replace("@DATE@", formatCurrentDate())

        suspend fun makeApiCall(): AiAnalysisResponse {
            val params = agent.parameters
            val result = when (agent.provider) {
                AiService.OPENAI -> analyzeWithChatGpt(agent.apiKey, finalPrompt, agent.model, params)
                AiService.ANTHROPIC -> analyzeWithClaude(agent.apiKey, finalPrompt, agent.model, params)
                AiService.GOOGLE -> analyzeWithGemini(agent.apiKey, finalPrompt, agent.model, params)
                AiService.XAI -> analyzeWithGrok(agent.apiKey, finalPrompt, agent.model, params)
                AiService.GROQ -> analyzeWithGroq(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DEEPSEEK -> analyzeWithDeepSeek(agent.apiKey, finalPrompt, agent.model, params)
                AiService.MISTRAL -> analyzeWithMistral(agent.apiKey, finalPrompt, agent.model, params)
                AiService.PERPLEXITY -> analyzeWithPerplexity(agent.apiKey, finalPrompt, agent.model, params)
                AiService.TOGETHER -> analyzeWithTogether(agent.apiKey, finalPrompt, agent.model, params)
                AiService.OPENROUTER -> analyzeWithOpenRouter(agent.apiKey, finalPrompt, agent.model, params)
                AiService.SILICONFLOW -> analyzeWithSiliconFlow(agent.apiKey, finalPrompt, agent.model, params)
                AiService.ZAI -> analyzeWithZai(agent.apiKey, finalPrompt, agent.model, params)
                AiService.MOONSHOT -> analyzeWithMoonshot(agent.apiKey, finalPrompt, agent.model, params)
                AiService.COHERE -> analyzeWithCohere(agent.apiKey, finalPrompt, agent.model, params)
                AiService.AI21 -> analyzeWithAi21(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DASHSCOPE -> analyzeWithDashScope(agent.apiKey, finalPrompt, agent.model, params)
                AiService.FIREWORKS -> analyzeWithFireworks(agent.apiKey, finalPrompt, agent.model, params)
                AiService.CEREBRAS -> analyzeWithCerebras(agent.apiKey, finalPrompt, agent.model, params)
                AiService.SAMBANOVA -> analyzeWithSambaNova(agent.apiKey, finalPrompt, agent.model, params)
                AiService.BAICHUAN -> analyzeWithBaichuan(agent.apiKey, finalPrompt, agent.model, params)
                AiService.STEPFUN -> analyzeWithStepFun(agent.apiKey, finalPrompt, agent.model, params)
                AiService.MINIMAX -> analyzeWithMiniMax(agent.apiKey, finalPrompt, agent.model, params)
                AiService.NVIDIA -> analyzeWithNvidia(agent.apiKey, finalPrompt, agent.model, params)
                AiService.REPLICATE -> analyzeWithReplicate(agent.apiKey, finalPrompt, agent.model, params)
                AiService.HUGGINGFACE -> analyzeWithHuggingFaceInference(agent.apiKey, finalPrompt, agent.model, params)
                AiService.LAMBDA -> analyzeWithLambda(agent.apiKey, finalPrompt, agent.model, params)
                AiService.LEPTON -> analyzeWithLepton(agent.apiKey, finalPrompt, agent.model, params)
                AiService.YI -> analyzeWithYi(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DOUBAO -> analyzeWithDoubao(agent.apiKey, finalPrompt, agent.model, params)
                AiService.REKA -> analyzeWithReka(agent.apiKey, finalPrompt, agent.model, params)
                AiService.WRITER -> analyzeWithWriter(agent.apiKey, finalPrompt, agent.model, params)
            }
            return result.copy(agentName = agent.name, promptUsed = finalPrompt)
        }

        try {
            val result = makeApiCall()
            if (result.isSuccess) {
                return@withContext result
            }
            android.util.Log.w("AiAnalysis", "Agent ${agent.name} player analysis failed: ${result.error}, retrying...")
            delay(RETRY_DELAY_MS)
            return@withContext makeApiCall()
        } catch (e: Exception) {
            android.util.Log.w("AiAnalysis", "Agent ${agent.name} player analysis exception: ${e.message}, retrying...")
            try {
                delay(RETRY_DELAY_MS)
                return@withContext makeApiCall()
            } catch (e2: Exception) {
                return@withContext AiAnalysisResponse(
                    service = agent.provider,
                    analysis = null,
                    error = "Network error after retry: ${e2.message ?: "Unknown error"}",
                    agentName = agent.name
                )
            }
        }
    }

    /**
     * Check if a model requires the Responses API (GPT-5.x and newer).
     * Chat Completions API is used for older models (gpt-4o, gpt-4, gpt-3.5, etc.)
     */
    internal fun usesResponsesApi(model: String): Boolean {
        val lowerModel = model.lowercase()
        return lowerModel.startsWith("gpt-5") ||
               lowerModel.startsWith("o3") ||
               lowerModel.startsWith("o4")
    }

}
