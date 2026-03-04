package com.ai.data

import android.content.Context
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
    val apiCost: Double? = null
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

/**
 * Response from AI analysis.
 */
data class AnalysisResponse(
    val service: AppService,
    val analysis: String?,
    val error: String?,
    val tokenUsage: TokenUsage? = null,
    val agentName: String? = null,
    val promptUsed: String? = null,
    val citations: List<String>? = null,
    val searchResults: List<SearchResult>? = null,
    val relatedQuestions: List<String>? = null,
    val rawUsageJson: String? = null,
    val httpHeaders: String? = null,
    val httpStatusCode: Int? = null
) {
    val isSuccess: Boolean get() = analysis != null && error == null
    val displayName: String get() = agentName ?: service.displayName
}

/**
 * Repository for making AI analysis requests to various AI services.
 */
class AnalysisRepository {
    companion object {
        internal const val RETRY_DELAY_MS = 500L
        internal const val TEST_PROMPT = "Reply with exactly: OK"
        internal const val XAI_COST_TICKS_DIVISOR = 10_000_000_000.0
    }

    internal val gson = createAppGson(prettyPrint = true)

    internal fun formatUsageJson(usage: Any?): String? = usage?.let { gson.toJson(it) }

    internal fun formatHeaders(headers: Headers): String {
        return headers.toMultimap().entries.joinToString("\n") { (name, values) ->
            "$name: ${values.joinToString(", ")}"
        }
    }

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

    private fun buildPrompt(promptTemplate: String, content: String, agent: com.ai.model.Agent? = null): String {
        var result = promptTemplate.replace("@FEN@", content).replace("@DATE@", formatCurrentDate())
        if (agent != null) {
            result = result.replace("@MODEL@", agent.model).replace("@PROVIDER@", agent.provider.displayName).replace("@AGENT@", agent.name)
        }
        return result
    }

    private fun mergeParameters(agentParams: AgentParameters, overrideParams: AgentParameters?): AgentParameters {
        if (overrideParams == null) return agentParams
        return AgentParameters(
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
            returnCitations = overrideParams.returnCitations || agentParams.returnCitations,
            searchRecency = overrideParams.searchRecency ?: agentParams.searchRecency
        )
    }

    private fun filterParametersBySupported(params: AgentParameters, supportedParams: List<String>?): AgentParameters {
        if (supportedParams == null) return params
        return AgentParameters(
            temperature = if ("temperature" in supportedParams) params.temperature else null,
            maxTokens = if ("max_tokens" in supportedParams) params.maxTokens else null,
            topP = if ("top_p" in supportedParams) params.topP else null,
            topK = if ("top_k" in supportedParams) params.topK else null,
            frequencyPenalty = if ("frequency_penalty" in supportedParams) params.frequencyPenalty else null,
            presencePenalty = if ("presence_penalty" in supportedParams) params.presencePenalty else null,
            systemPrompt = params.systemPrompt,
            stopSequences = if ("stop" in supportedParams) params.stopSequences else null,
            seed = if ("seed" in supportedParams) params.seed else null,
            responseFormatJson = if ("response_format" in supportedParams) params.responseFormatJson else false,
            searchEnabled = params.searchEnabled,
            returnCitations = params.returnCitations,
            searchRecency = params.searchRecency
        )
    }

    /**
     * Analyze using an Agent configuration with retry logic.
     */
    suspend fun analyzeWithAgent(
        agent: com.ai.model.Agent,
        content: String,
        prompt: String,
        agentResolvedParams: AgentParameters = AgentParameters(),
        overrideParams: AgentParameters? = null,
        context: Context? = null,
        baseUrl: String? = null
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        if (agent.apiKey.isBlank()) {
            return@withContext AnalysisResponse(agent.provider, null, "API key not configured for agent ${agent.name}", agentName = agent.name)
        }
        val finalPrompt = buildPrompt(prompt, content, agent)
        suspend fun makeApiCall(): AnalysisResponse {
            var params = validateParams(mergeParameters(agentResolvedParams, overrideParams))
            if (overrideParams != null && context != null) {
                params = filterParametersBySupported(params, PricingCache.getSupportedParameters(context, agent.provider, agent.model))
            }
            return analyze(
                agent.provider,
                agent.apiKey,
                finalPrompt,
                agent.model,
                params,
                baseUrl ?: agent.provider.baseUrl
            ).copy(agentName = agent.name, promptUsed = finalPrompt)
        }
        withRetry("Agent ${agent.name}", { makeApiCall() }, { it.isSuccess }) { e ->
            AnalysisResponse(agent.provider, null, "Network error after retry: ${e.message}", agentName = agent.name)
        }
    }

    /**
     * Analyze without FEN content (player analysis).
     */
    suspend fun analyzePlayerWithAgent(
        agent: com.ai.model.Agent,
        prompt: String,
        agentResolvedParams: AgentParameters = AgentParameters()
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        if (agent.apiKey.isBlank()) {
            return@withContext AnalysisResponse(agent.provider, null, "API key not configured for agent ${agent.name}", agentName = agent.name)
        }
        val finalPrompt = prompt.replace("@DATE@", formatCurrentDate())
        suspend fun makeApiCall(): AnalysisResponse {
            val params = validateParams(agentResolvedParams)
            return analyze(agent.provider, agent.apiKey, finalPrompt, agent.model, params).copy(agentName = agent.name, promptUsed = finalPrompt)
        }
        withRetry("Agent ${agent.name} player", { makeApiCall() }, { it.isSuccess }) { e ->
            AnalysisResponse(agent.provider, null, "Network error after retry: ${e.message}", agentName = agent.name)
        }
    }

    internal suspend fun <T> withRetry(
        label: String,
        makeCall: suspend () -> T,
        isSuccess: (T) -> Boolean,
        errorResult: (Exception) -> T
    ): T {
        try {
            val result = makeCall()
            if (isSuccess(result)) return result
            android.util.Log.w("AiAnalysis", "$label first attempt failed, retrying...")
            delay(RETRY_DELAY_MS)
            return try { makeCall() } catch (e: Exception) { errorResult(e) }
        } catch (e: Exception) {
            android.util.Log.w("AiAnalysis", "$label first attempt exception: ${e.message}, retrying...")
            try { delay(RETRY_DELAY_MS); return makeCall() } catch (e2: Exception) { e2.addSuppressed(e); return errorResult(e2) }
        }
    }

    internal fun validateParams(params: AgentParameters): AgentParameters {
        return params.copy(
            temperature = params.temperature?.coerceIn(0f, 2f),
            topP = params.topP?.coerceIn(0f, 1f),
            topK = params.topK?.coerceAtLeast(1),
            maxTokens = params.maxTokens?.coerceAtLeast(1),
            frequencyPenalty = params.frequencyPenalty?.coerceIn(-2f, 2f),
            presencePenalty = params.presencePenalty?.coerceIn(-2f, 2f)
        )
    }

    internal fun usesResponsesApi(service: AppService, model: String): Boolean {
        val lowerModel = model.lowercase()
        return service.endpointRules.any {
            lowerModel.startsWith(it.modelPrefix.lowercase()) && it.endpointType == "responses"
        }
    }
}
