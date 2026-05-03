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
/**
 * Token usage statistics from AI API response.
 *
 * inputTokens is the count of *fresh* (uncached) prompt tokens — cached
 * portions are in cachedInputTokens (charged at the cache-read rate) and
 * cacheCreationTokens (Anthropic-only, charged at the cache-write rate).
 * Each provider's extractor normalizes the response shape into these buckets:
 *
 *   • OpenAI / DeepSeek / xAI / Gemini: prompt_tokens is total and includes
 *     cached; the extractor does inputTokens = total − cached.
 *   • Anthropic: input_tokens already excludes both cache fields; we use
 *     them as-is.
 */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val apiCost: Double? = null,
    val cachedInputTokens: Int = 0,
    val cacheCreationTokens: Int = 0
) {
    val totalTokens: Int get() = inputTokens + outputTokens + cachedInputTokens + cacheCreationTokens
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

    internal fun mergeParameters(agentParams: AgentParameters, overrideParams: AgentParameters?): AgentParameters {
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
            returnCitations = overrideParams.returnCitations,
            searchRecency = overrideParams.searchRecency ?: agentParams.searchRecency,
            webSearchTool = overrideParams.webSearchTool || agentParams.webSearchTool,
            reasoningEffort = overrideParams.reasoningEffort ?: agentParams.reasoningEffort
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
            searchRecency = params.searchRecency,
            webSearchTool = params.webSearchTool,
            reasoningEffort = params.reasoningEffort
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
        baseUrl: String? = null,
        imageBase64: String? = null,
        imageMime: String? = null
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        // Local on-device path — no API key, no HTTP, no retry. The
        // sentinel AppService.LOCAL is the marker. Embedding-style
        // simple flow: build the prompt, call MediaPipe LLM Inference,
        // wrap the response.
        if (agent.provider.id == "LOCAL") {
            if (context == null) {
                return@withContext AnalysisResponse(agent.provider, null, "Local LLM call requires a Context", agentName = agent.name)
            }
            val finalPrompt = buildPrompt(prompt, content, agent)
            val out = LocalLlm.generate(context, agent.model, finalPrompt)
            return@withContext if (out != null) {
                AnalysisResponse(agent.provider, out, null, agentName = agent.name, promptUsed = finalPrompt, httpStatusCode = 200)
            } else {
                AnalysisResponse(agent.provider, null, "Local LLM \"${agent.model}\" failed — verify it loaded in Housekeeping → Local LLMs.", agentName = agent.name, promptUsed = finalPrompt, httpStatusCode = 500)
            }
        }
        if (agent.apiKey.isBlank()) {
            return@withContext AnalysisResponse(agent.provider, null, "API key not configured for agent ${agent.name}", agentName = agent.name)
        }
        val finalPrompt = buildPrompt(prompt, content, agent)
        suspend fun makeApiCall(): AnalysisResponse {
            var params = validateParams(mergeParameters(agentResolvedParams, overrideParams))
            if (overrideParams != null && context != null) {
                params = filterParametersBySupported(params, PricingCache.getSupportedParameters(context, agent.provider, agent.model))
            }
            val effectiveBaseUrl = baseUrl ?: agent.provider.baseUrl
            val first = analyze(
                agent.provider, agent.apiKey, finalPrompt, agent.model, params,
                effectiveBaseUrl, imageBase64, imageMime
            )
            // Tool-fallback: when the request asked for a web-search tool and the
            // provider rejected it (most common: 400 with "tool"/"web_search" in
            // the error body), retry once without the tool. Models that don't
            // support the tool descriptor would otherwise hard-fail the whole
            // report agent.
            val needsFallback = !first.isSuccess
                && params.webSearchTool
                && first.httpStatusCode == 400
                && (first.error ?: "").lowercase().let { msg ->
                    "tool" in msg || "web_search" in msg || "googlesearch" in msg || "google_search" in msg
                }
            val response = if (needsFallback) {
                analyze(
                    agent.provider, agent.apiKey, finalPrompt, agent.model,
                    params.copy(webSearchTool = false),
                    effectiveBaseUrl, imageBase64, imageMime
                )
            } else first
            return response.copy(agentName = agent.name, promptUsed = finalPrompt)
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
            return try {
                makeCall()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorResult(e)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation must propagate — without this re-throw, stopping a
            // report or navigating away from a chat would convert structured
            // cancellation into a fake "Network error after retry" entry.
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AiAnalysis", "$label first attempt exception: ${e.message}, retrying...")
            return try {
                delay(RETRY_DELAY_MS); makeCall()
            } catch (e2: kotlinx.coroutines.CancellationException) {
                throw e2
            } catch (e2: Exception) {
                e2.addSuppressed(e); errorResult(e2)
            }
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

    /** Route to the Responses API when the model classifies as RESPONSES. The
     *  classifier replaces the old prefix-based endpointRules — gpt-5/o3/o4 are
     *  detected directly in ModelType.infer. */
    internal fun usesResponsesApi(service: AppService, model: String): Boolean {
        return ModelType.infer(model) == ModelType.RESPONSES
    }
}
