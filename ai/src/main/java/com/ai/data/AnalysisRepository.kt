package com.ai.data

import android.content.Context
import com.ai.data.local.LocalLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
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
 *   • OpenAI-compatible: prompt_tokens is usually total and includes cached,
 *     so the extractor does inputTokens = total − cached; providers that
 *     declare flattened cached_tokens keep prompt_tokens as fresh input.
 *   • Anthropic: input_tokens already excludes both cache fields; we use
 *     them as-is.
 */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val apiCost: Double? = null,
    val cachedInputTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
    /** Hidden reasoning tokens — Gemini's thoughtsTokenCount, OpenAI's
     *  reasoning_tokens. Billed at the output rate but distinct from
     *  visible output. Lets the test probe recognise "model is reachable,
     *  budget went to reasoning" when [outputTokens] is 0. */
    val reasoningTokens: Int = 0
) {
    val totalTokens: Int get() = inputTokens + outputTokens + cachedInputTokens + cacheCreationTokens + reasoningTokens
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
    val displayName: String get() = agentName ?: service.id
}

/**
 * Repository for making AI analysis requests to various AI services.
 */
class AnalysisRepository {
    companion object {
        internal const val RETRY_DELAY_MS = 500L
        /** Body of the OK-probe sent by every model-test path
         *  (provider screen test, Test all models, ApiDispatch
         *  testModel). Default mirrors the bundled `test_model`
         *  internal prompt in assets/internal-prompts/; AppViewModel
         *  pushes the live prompt body in here whenever settings
         *  load or change so the user's edit wins everywhere
         *  without threading aiSettings through every call site. */
        @Volatile
        internal var TEST_PROMPT: String = "Reply with exactly: OK"

        /** max_tokens for the [TEST_PROMPT] probe. The probe only
         *  checks reachability — a one-word reply is all it needs — so
         *  a tiny cap keeps it cheap and, crucially, stops balance-
         *  gating providers (OpenRouter) from pre-authorising the
         *  model's whole output window against the account balance and
         *  402-ing expensive models that would otherwise answer fine. */
        internal const val TEST_MAX_TOKENS = 64
        internal const val XAI_COST_TICKS_DIVISOR = 10_000_000_000.0

        /** Set of 4xx codes that withRetry treats as deterministic
         *  client errors — a retry just burns another call + tokens.
         *  408 (Request Timeout), 425 (Too Early), and 429 (Too Many
         *  Requests) are NOT in the set: they're transient and the
         *  outer retry is the user's only safety net once the
         *  in-OkHttp 429 loop has exhausted itself. */
        internal val PERMANENT_CLIENT_ERROR_CODES: Set<Int> =
            (400..499).toSet() - setOf(408, 425, 429)
    }

    internal val gson = createAppGson(prettyPrint = true)

    internal fun formatUsageJson(usage: Any?): String? = usage?.let { gson.toJson(it) }

    internal fun formatHeaders(headers: Headers): String {
        return headers.toMultimap().entries.joinToString("\n") { (name, values) ->
            // Redact key-bearing headers before they land in the report
            // agent's requestHeaders/responseHeaders fields (Bug 70) —
            // these persist in the report JSON and roll up into the backup
            // zip, so an Authorization / x-api-key would otherwise sit in
            // plaintext on disk.
            val joined = values.joinToString(", ")
            val value = if (isSensitiveHeaderName(name)) redactHeaderSecret(joined) else joined
            "$name: $value"
        }
    }

    private fun isSensitiveHeaderName(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "authorization" ||
            lower == "x-api-key" ||
            lower == "api-key" ||
            lower == "x-goog-api-key" ||
            lower == "openai-organization" ||
            lower == "anthropic-api-key" ||
            lower.startsWith("x-amz-") ||
            lower.startsWith("cf-")
    }

    /** Keep a short prefix/suffix so the header stays useful for
     *  "wrong key" debugging without exposing the secret. */
    private fun redactHeaderSecret(value: String): String {
        if (value.isBlank()) return value
        val (scheme, raw) = value.split(' ', limit = 2).let {
            if (it.size == 2) it[0] + " " to it[1] else "" to it[0]
        }
        if (raw.length <= 8) return "$scheme[REDACTED]"
        return "$scheme${raw.take(4)}…[REDACTED]…${raw.takeLast(4)}"
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

    /** Prepend a RAG context block to [prompt] when one is non-empty.
     *  Empty prefix passes the prompt through unchanged so callers
     *  don't need to special-case the no-knowledge path. */
    private fun withRagPrefix(prompt: String, ragPrefix: String): String =
        if (ragPrefix.isBlank()) prompt else "$ragPrefix\n\n$prompt"

    private fun buildPrompt(promptTemplate: String, content: String, agent: com.ai.model.Agent? = null): String {
        var result = promptTemplate.replace("@FEN@", content).replace("@DATE@", formatCurrentDate())
        if (agent != null) {
            result = result.replace("@MODEL@", agent.model).replace("@PROVIDER@", agent.provider.id).replace("@AGENT@", agent.name)
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
            // returnCitations defaults to true (the default-true sibling
            // of the default-false flags above). Use AND so an explicit
            // opt-out on either side wins — otherwise an override that
            // simply left the field at default true would silently
            // re-enable citations for an agent that had them disabled.
            returnCitations = overrideParams.returnCitations && agentParams.returnCitations,
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
        imageMime: String? = null,
        // RAG: when non-empty, retrieve top-K chunks across these
        // KBs and prepend the rendered context block to the system
        // prompt before dispatch. aiSettings is needed for remote
        // embedders' API keys.
        knowledgeBaseIds: List<String> = emptyList(),
        aiSettings: com.ai.model.Settings? = null,
        // Best-effort callers (report title / icon / language, per-model
        // enrichment, auto-metas) pass false: a single attempt, no withRetry
        // and no in-line 429/529 retry. These all share one cheap metadata
        // model, so retrying a failed/queued call just re-floods that model's
        // rate window — a self-sustaining storm. A failed best-effort call is
        // simply skipped (the user can regenerate info).
        retry: Boolean = true
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        // Local on-device path — no API key, no HTTP, no retry. The
        // sentinel AppService.LOCAL is the marker. Embedding-style
        // simple flow: build the prompt, call MediaPipe LLM Inference,
        // wrap the response.
        // RAG injection: if knowledge bases are attached, retrieve
        // top-K chunks for this prompt and prepend the rendered
        // context block. Failures are silent (fallback to bare
        // prompt) so an embedder hiccup doesn't kill the whole call.
        val repository = this@AnalysisRepository
        val ragPrefix = if (knowledgeBaseIds.isNotEmpty() && context != null && aiSettings != null) {
            runCatching {
                val hits = KnowledgeService.retrieve(context, repository, aiSettings, knowledgeBaseIds, prompt.ifBlank { content })
                KnowledgeService.formatContextBlock(hits)
            }.getOrDefault("")
        } else ""

        if (agent.provider.id == AppService.LOCAL.id) {
            if (context == null) {
                return@withContext AnalysisResponse(agent.provider, null, "Local LLM call requires a Context", agentName = agent.name)
            }
            val finalPrompt = withRagPrefix(buildPrompt(prompt, content, agent), ragPrefix)
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
        val finalPrompt = withRagPrefix(buildPrompt(prompt, content, agent), ragPrefix)
        suspend fun makeApiCall(): AnalysisResponse {
            var params = validateParams(mergeParameters(agentResolvedParams, overrideParams), agent.provider)
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
            // Broadened fallback trigger:
            //   any 4xx status (some providers return 422 / 415 for an
            //   unsupported tool descriptor) AND
            //   any of the known tool-related substrings in the error
            //   message OR a wider set of "unsupported"/"invalid"
            //   markers — covers providers that return error wording
            //   like "Unknown parameter: tools" or "tool_choice not
            //   supported".
            val errMsg = (first.error ?: "").lowercase()
            val statusEligible = first.httpStatusCode in 400..499
            val hasToolKeyword = "tool" in errMsg || "web_search" in errMsg ||
                "googlesearch" in errMsg || "google_search" in errMsg
            val hasUnsupportedHint = ("unsupported" in errMsg || "invalid" in errMsg ||
                "unknown" in errMsg || "not supported" in errMsg) &&
                ("tools" in errMsg || "tool_choice" in errMsg || "tool" in errMsg)
            val needsFallback = !first.isSuccess
                && params.webSearchTool
                && statusEligible
                && (hasToolKeyword || hasUnsupportedHint)
            val response = if (needsFallback) {
                val retried = analyze(
                    agent.provider, agent.apiKey, finalPrompt, agent.model,
                    params.copy(webSearchTool = false),
                    effectiveBaseUrl, imageBase64, imageMime
                )
                // Preserve the original tool-rejection error in the log
                // so a user inspecting the trace sees both attempts —
                // the second response replaces the first as the value
                // returned to the caller, but the original failure
                // shouldn't disappear entirely.
                if (!retried.isSuccess) {
                    AppLog.w("AiAnalysis",
                        "Tool fallback also failed for ${agent.name}: " +
                            "first=${first.httpStatusCode}/${first.error?.take(120)}; " +
                            "fallback=${retried.httpStatusCode}/${retried.error?.take(120)}")
                } else {
                    AppLog.i("AiAnalysis",
                        "Tool fallback succeeded for ${agent.name} " +
                            "after first=${first.httpStatusCode}/${first.error?.take(120)}")
                }
                retried
            } else first
            return response.copy(agentName = agent.name, promptUsed = finalPrompt)
        }
        if (!retry) {
            // Single attempt, no in-line 429/529 retry — see the `retry` param.
            return@withContext withContext(ProviderThrottle.suppressInlineRetry.asContextElement(true)) {
                try {
                    makeApiCall()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AnalysisResponse(agent.provider, null, "Network error: ${e.message}", agentName = agent.name)
                }
            }
        }
        withRetry(
            label = "Agent ${agent.name}",
            makeCall = { makeApiCall() },
            isSuccess = { it.isSuccess },
            isPermanentFailure = { it.httpStatusCode in PERMANENT_CLIENT_ERROR_CODES },
            errorResult = { e -> AnalysisResponse(agent.provider, null, "Network error after retry: ${e.message}", agentName = agent.name) }
        )
    }

    /**
     * Streaming sibling of [analyzeWithAgent] for the report-generation
     * primary calls: streams the answer so the model card fills in live
     * ([onDelta] receives each text chunk), while keeping the persisted
     * result + cost as accurate as the non-streaming path.
     *
     * Falls back to [analyzeWithAgent] (the authoritative path) for cases
     * streaming can't safely cover — LOCAL, missing key, a web-search-tool
     * agent (so citations + tool-fallback survive), or a model LiteLLM marks
     * non-streamable — and on any stream error / empty body. So nothing
     * regresses and there's no systematic double-billing. When a provider
     * streams text but reports no usage, the streamed text is kept and tokens
     * are estimated (as chat already does) rather than re-billing the call.
     *
     * Request construction reuses the exact same builders as the
     * non-streaming dispatch ([buildMessages] / [buildOpenAiRequest] /
     * claudeReasoningBundle / geminiThinkingConfigField), so the streamed
     * request is byte-identical aside from stream=true + usage opt-in.
     */
    suspend fun analyzeWithAgentStreaming(
        agent: com.ai.model.Agent,
        content: String,
        prompt: String,
        agentResolvedParams: AgentParameters = AgentParameters(),
        overrideParams: AgentParameters? = null,
        context: Context? = null,
        baseUrl: String? = null,
        imageBase64: String? = null,
        imageMime: String? = null,
        knowledgeBaseIds: List<String> = emptyList(),
        aiSettings: com.ai.model.Settings? = null,
        onDelta: (String) -> Unit
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        val nonStreaming: suspend () -> AnalysisResponse = {
            analyzeWithAgent(agent, content, prompt, agentResolvedParams, overrideParams, context, baseUrl, imageBase64, imageMime, knowledgeBaseIds, aiSettings)
        }
        val merged = mergeParameters(agentResolvedParams, overrideParams)
        // Streaming-ineligible → authoritative non-streaming path (no preview).
        if (agent.provider.id == AppService.LOCAL.id ||
            agent.apiKey.isBlank() ||
            merged.webSearchTool ||
            PricingCache.liteLLMSupportsNativeStreaming(agent.provider, agent.model) == false) {
            return@withContext nonStreaming()
        }
        val repository = this@AnalysisRepository
        val ragPrefix = if (knowledgeBaseIds.isNotEmpty() && context != null && aiSettings != null) {
            runCatching {
                val hits = KnowledgeService.retrieve(context, repository, aiSettings, knowledgeBaseIds, prompt.ifBlank { content })
                KnowledgeService.formatContextBlock(hits)
            }.getOrDefault("")
        } else ""
        val finalPrompt = withRagPrefix(buildPrompt(prompt, content, agent), ragPrefix)
        var params = validateParams(merged, agent.provider)
        if (overrideParams != null && context != null) {
            params = filterParametersBySupported(params, PricingCache.getSupportedParameters(context, agent.provider, agent.model))
        }
        val effectiveBaseUrl = baseUrl ?: agent.provider.baseUrl
        try {
            val resp = analyzeAgentStreaming(
                agent.provider, agent.apiKey, finalPrompt, agent.model, params,
                effectiveBaseUrl, imageBase64, imageMime, onDelta
            )
            when {
                // Streamed OK with real usage → exact result + cost, single call.
                resp.isSuccess && resp.tokenUsage.let { it != null && (it.inputTokens > 0 || it.outputTokens > 0) } ->
                    resp.copy(agentName = agent.name, promptUsed = finalPrompt)
                // Streamed OK but provider reported no usage → keep the text,
                // estimate tokens (~4 chars/token) so a usable answer is never
                // re-billed by falling back.
                resp.isSuccess -> resp.copy(
                    tokenUsage = TokenUsage((finalPrompt.length + 3) / 4, ((resp.analysis ?: "").length + 3) / 4),
                    agentName = agent.name, promptUsed = finalPrompt
                )
                // Stream failed / empty → authoritative non-streaming call.
                else -> nonStreaming()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("AiAnalysis", "Streaming attempt failed for ${agent.name} (${e.message}); using non-streaming")
            nonStreaming()
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
            val params = validateParams(agentResolvedParams, agent.provider)
            return analyze(agent.provider, agent.apiKey, finalPrompt, agent.model, params).copy(agentName = agent.name, promptUsed = finalPrompt)
        }
        withRetry(
            label = "Agent ${agent.name} player",
            makeCall = { makeApiCall() },
            isSuccess = { it.isSuccess },
            isPermanentFailure = { it.httpStatusCode in PERMANENT_CLIENT_ERROR_CODES },
            errorResult = { e -> AnalysisResponse(agent.provider, null, "Network error after retry: ${e.message}", agentName = agent.name) }
        )
    }

    internal suspend fun <T> withRetry(
        label: String,
        makeCall: suspend () -> T,
        isSuccess: (T) -> Boolean,
        /** Optional discriminator that lets the caller mark a failed
         *  result as permanent (e.g. 4xx HTTP) so we skip the retry —
         *  re-issuing a deterministic 400 wastes another call + token
         *  budget. Default returns false (retry every non-success),
         *  preserving the original behaviour for callers that don't
         *  opt in. */
        isPermanentFailure: (T) -> Boolean = { false },
        errorResult: (Exception) -> T
    ): T {
        try {
            val result = makeCall()
            if (isSuccess(result)) return result
            if (isPermanentFailure(result)) {
                AppLog.w("AiAnalysis", "$label first attempt permanent failure, skipping retry")
                return result
            }
            AppLog.w("AiAnalysis", "$label first attempt failed, retrying...")
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
        } catch (e: java.io.IOException) {
            // Only retry transient I/O failures. Catching every Exception
            // (the previous behaviour) silently retried genuine bugs like
            // NullPointerException out of JSON parsing or
            // IllegalStateException out of the dispatch path, masking
            // them as "transient network errors" worth a second attempt
            // each. NPE-on-second-attempt is just as broken as the first
            // and burns 2× the cost / quota on every defective call.
            AppLog.w("AiAnalysis", "$label first attempt I/O failure: ${e.message}, retrying…")
            return try {
                delay(RETRY_DELAY_MS); makeCall()
            } catch (e2: kotlinx.coroutines.CancellationException) {
                throw e2
            } catch (e2: java.io.IOException) {
                e2.addSuppressed(e); errorResult(e2)
            }
        }
    }

    internal fun validateParams(params: AgentParameters, provider: AppService? = null): AgentParameters {
        val temperatureRange = provider?.let(::temperatureRangeForProvider) ?: TemperatureRange.Default
        return params.copy(
            temperature = params.temperature?.coerceIn(temperatureRange.min, temperatureRange.max),
            topP = params.topP?.coerceIn(0f, 1f),
            topK = params.topK?.coerceAtLeast(1),
            maxTokens = params.maxTokens?.coerceAtLeast(1),
            frequencyPenalty = params.frequencyPenalty?.coerceIn(-2f, 2f),
            presencePenalty = params.presencePenalty?.coerceIn(-2f, 2f)
        )
    }

    /** Route to the Responses API when the provider's configured
     *  responsesApiPatterns match, or — failing that — when the model's
     *  name classifies as RESPONSES. The provider-level patterns are
     *  the authoritative source (providers.json declares o1 / gpt-4.1
     *  there and a user can edit the list in Service Settings); the
     *  ModelType.infer fallback still catches gpt-5 / o3 / o4 family
     *  names on custom OpenAI-compatible endpoints that don't carry a
     *  pattern config. */
    internal fun usesResponsesApi(service: AppService, model: String): Boolean {
        if (service.responsesApiPatterns.anyMatches(model)) return true
        // Name-based RESPONSES inference is a fallback ONLY for providers that
        // actually expose a Responses API (they declare responsesApiPatterns —
        // currently just OpenAI). Chat-only OpenAI-compatible gateways (Poe,
        // Vivgrid, and most aggregators) serve gpt-5 / o3 / o4 ids over
        // /v1/chat/completions and have no /v1/responses endpoint, so inferring
        // RESPONSES for them sends every call to a non-existent path → 404.
        return service.responsesApiPatterns.isNotEmpty() &&
            ModelType.infer(model) == ModelType.RESPONSES
    }
}
