package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

// ============================================================================
// Unified API dispatch — single when(apiFormat) for analyze, chat, fetchModels
// ============================================================================

/**
 * A safe `max_tokens` for a (service, model) when the user didn't set one.
 *
 * Anthropic *requires* max_tokens, so this was originally a Claude-only
 * helper. But OpenAI-compatible providers need it too: omitting max_tokens
 * lets balance-gating providers (OpenRouter) pre-authorise the model's
 * entire output window against the account balance, which 402s expensive
 * models that would answer a normal request fine. Per-model rule first
 * (the provider's maxTokensDefaults table), then the known output-token
 * limit from models.dev when available, then a conservative fixed fallback.
 *
 * models.dev often reports a model's max OUTPUT as the whole context window,
 * and several providers count input + output against that window (OpenRouter
 * 400, Together 422 — "input + max_new_tokens must be <= context"). So when a
 * context size is known, cap the output cap to leave [INPUT_HEADROOM] tokens
 * for the prompt; a true small output limit (output < context) is untouched.
 */
private const val INPUT_HEADROOM = 4_096

/** Ceiling applied ONLY to a value derived from a loose, cross-provider
 *  catalog match — a model served under the same id by a *different* host.
 *  A provider's own /models self-report and a same-provider models.dev row
 *  are trusted as-is; this only bounds the last-resort guess so an inflated
 *  reseller window can't over-request. (models.dev's
 *  `submodel/…/DeepSeek-V3-0324` reports a 75 000-token context → the old
 *  code sent max_tokens=70904, which 400'd AtlasCloud.) */
private const val LOOSE_MATCH_CEILING = 16_384

private fun clampOutputToContext(out: Int, context: Int?): Int =
    if (context != null && context > 0) out.coerceAtMost((context - INPUT_HEADROOM).coerceAtLeast(1_024)) else out

internal fun defaultMaxTokens(service: AppService, model: String): Int {
    // 1. Explicit per-(provider, model) rule wins (Anthropic families,
    //    AtlasCloud DeepSeek-V3, …).
    service.maxTokensDefaults.resolveMaxTokens(model)?.let { return it }
    // 2. The provider's OWN /models self-report (ModelCapabilities) —
    //    authoritative when present: it's the actual host's limit, not a
    //    cross-provider catalog guess. Reached statically via SettingsHolder
    //    (defaultMaxTokens can't thread Settings through the dispatch stack).
    com.ai.model.SettingsHolder.current?.getProvider(service)?.modelCapabilities?.get(model)?.let { caps ->
        caps.maxOutputTokens?.takeIf { it > 0 }?.let { out ->
            return clampOutputToContext(out, caps.contextLength ?: PricingCache.modelsDevMaxInputTokensOwn(service, model))
        }
    }
    // 3. A SAME-PROVIDER models.dev row — the provider's own catalog entry,
    //    never an arbitrary reseller that happens to list the same model id.
    PricingCache.modelsDevMaxOutputTokensOwn(service, model)?.takeIf { it > 0 }?.let { out ->
        return clampOutputToContext(out, PricingCache.modelsDevMaxInputTokensOwn(service, model))
    }
    // 4. Loose cross-provider match, last resort and bounded — keeps coverage
    //    for resellers models.dev only knows under the model-maker's key,
    //    without letting a stray window blow past what the host accepts.
    val out = PricingCache.modelsDevMaxOutputTokens(service, model)?.takeIf { it > 0 } ?: return 4_096
    return clampOutputToContext(out, PricingCache.modelsDevMaxInputTokens(service, model)).coerceAtMost(LOOSE_MATCH_CEILING)
}

/**
 * Hard coroutine-level ceiling for ONE outbound provider call. OkHttp's
 * connect / read / write timeouts all start AFTER DNS resolution, and DNS
 * (`InetAddress.getAllByName`) can block indefinitely — so a call wedged in
 * name resolution trips none of them and pins the calling coroutine, plus
 * any throttle permits it holds, forever (this froze whole stress runs).
 *
 * [withTimeout] cancels the call and returns at once (the throttle permits
 * release even if the zombie DNS thread lingers in OkHttp's dispatcher
 * pool). The timeout is converted to a plain [java.io.IOException] so every
 * existing `catch (Exception)` / `runCatching` path treats it as an
 * ordinary transient network failure — no caller changes, and it's not
 * mistaken for structured cancellation (Stop / navigate-away), which
 * surfaces as a different CancellationException that passes straight
 * through.
 *
 * Non-streaming call sites use the shorter non-streaming read budget.
 * Streaming-open call sites pass [streamingOpen] so a provider that is
 * slow to send SSE headers gets the same budget as the streaming read
 * path. The long SSE body is still outside this wrapper and remains
 * guarded by OkHttp's per-chunk streaming read timeout.
 */
internal suspend fun <T> withApiCallTimeout(
    streamingOpen: Boolean = false,
    block: suspend () -> T
): T {
    val configuredReadSec = if (streamingOpen) {
        NetworkSettings.streamingReadTimeoutSec
    } else {
        NetworkSettings.nonStreamingReadTimeoutSec
    }
    val readSec = configuredReadSec.takeIf { it > 0 } ?: 120
    val ceilingMs = (readSec.toLong() + com.ai.BuildConfig.NETWORK_CONNECT_TIMEOUT_SEC + 30L) * 1000L
    return try {
        kotlinx.coroutines.withTimeout(ceilingMs) { block() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        val kind = if (streamingOpen) "stream open" else "API call"
        throw java.io.IOException("$kind timed out after ${ceilingMs / 1000}s (no response — possible network/DNS hang)")
    }
}

/**
 * Acquire the per-host throttle gate at the COROUTINE layer for [dispatch],
 * unless the caller already pre-acquired it (report / fan / translation flows
 * set `permitPreAcquired`). Two reasons this belongs here, not in the OkHttp
 * interceptor:
 *  1. The rate-limit WAIT (per-minute window) happens OUTSIDE [withApiCallTimeout],
 *     so a call legitimately queued behind a saturated provider window no longer
 *     trips the DNS-hang timeout (which then made it retry → a self-sustaining
 *     storm on shared metadata models like the title/icon agent).
 *  2. It uses the non-blocking suspend [ProviderThrottle.acquireOrWait] (delay,
 *     not Thread.sleep), so a waiting call doesn't occupy an OkHttp dispatcher
 *     per-host slot — which is what deadlocked OkHttp's maxRequestsPerHost
 *     against this throttle.
 * Marks `permitPreAcquired` so the interceptor skips its own (thread-blocking)
 * acquire for this call.
 */
private suspend fun <T> withHostGate(baseUrl: String, dispatch: suspend () -> T): T {
    if (ProviderThrottle.permitPreAcquired.get() == true) return dispatch()
    val host = resolveHostForGate(baseUrl)
    if (host.isBlank()) {
        AppLog.w("ApiDispatch", "Unable to resolve throttle host for baseUrl=$baseUrl; proceeding without coroutine host gate")
        return dispatch()
    }
    val releaser = ProviderThrottle.acquireOrWait(host)
    return try {
        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) { dispatch() }
    } finally {
        releaser.release()
    }
}

internal fun resolveHostForGate(baseUrl: String): String =
    runCatching { java.net.URI(baseUrl).host?.takeIf { it.isNotBlank() } }.getOrNull()
        ?: baseUrl.toHttpUrlOrNull()?.host.orEmpty()

/**
 * Analyze a prompt using the appropriate API format.
 */
suspend fun AnalysisRepository.analyze(
    service: AppService,
    apiKey: String,
    prompt: String,
    model: String,
    params: AgentParameters? = null,
    baseUrl: String = service.baseUrl,
    imageBase64: String? = null,
    imageMime: String? = null
): AnalysisResponse = withContext(Dispatchers.IO) {
    AppLog.d("ApiDispatch", "analyze ${service.id}/$model fmt=${service.apiFormat} promptLen=${prompt.length} img=${imageBase64 != null}")
    auditApiCall(service, model, baseUrl) {
        withHostGate(baseUrl) {
            withApiCallTimeout {
                when (service.apiFormat) {
                    ApiFormat.ANTHROPIC -> analyzeAnthropic(service, apiKey, prompt, model, params, imageBase64, imageMime)
                    ApiFormat.GOOGLE -> analyzeGemini(service, apiKey, prompt, model, params, imageBase64, imageMime)
                    ApiFormat.REPLICATE -> analyzeReplicate(service, apiKey, prompt, model, params, imageBase64, imageMime)
                    ApiFormat.OPENAI_COMPATIBLE -> analyzeOpenAi(service, apiKey, prompt, model, params, baseUrl, imageBase64, imageMime)
                }
            }
        }
    }
}

/**
 * Send a non-streaming chat message. Returns the assistant's response text.
 */
suspend fun AnalysisRepository.sendChat(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    params: ChatParameters,
    baseUrl: String = service.baseUrl
): String {
    val response = sendChatResponse(service, apiKey, model, messages, params, baseUrl)
    return response.analysis ?: throw Exception(response.error ?: "No response content")
}

/**
 * Send a non-streaming chat message and preserve provider token usage when the
 * response format exposes it.
 */
suspend fun AnalysisRepository.sendChatResponse(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    params: ChatParameters,
    baseUrl: String = service.baseUrl
): AnalysisResponse = withContext(Dispatchers.IO) {
    AppLog.d("ApiDispatch", "sendChat ${service.id}/$model fmt=${service.apiFormat} msgs=${messages.size}")
    withHostGate(baseUrl) {
        withApiCallTimeout {
            when (service.apiFormat) {
                ApiFormat.ANTHROPIC -> chatAnthropicResponse(service, apiKey, model, messages, params)
                ApiFormat.GOOGLE -> chatGeminiResponse(service, apiKey, model, messages, params)
                ApiFormat.REPLICATE -> chatReplicateResponse(service, apiKey, model, messages, params)
                ApiFormat.OPENAI_COMPATIBLE -> chatOpenAiResponse(service, apiKey, model, messages, params, baseUrl)
            }
        }
    }
}

/** Thrown by the model-list dispatchers ([fetchModelsOpenAi],
 *  [fetchModelsAnthropic], [fetchModelsGemini]) on HTTP error or
 *  network/parse failure. Subclasses [java.io.IOException] so existing
 *  callers that already discriminate on IOException (the
 *  exception-side path in AnalysisRepository.withRetry, the catch in
 *  AppViewModel.fetchModelsAwait) treat it as a transient network
 *  failure rather than a programming bug.
 *
 *  The dispatchers used to silently return FetchedModels(emptyList())
 *  on any failure, which then flowed through fetchModelsAwait's
 *  success path and overwrote the cached catalog with an empty list.
 *  Throwing routes the failure to the catch + fetchModelsErrors map
 *  so the UI can surface "fetch failed" instead of "no models". */
class FetchModelsException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/**
 * Fetch available models from a provider — ids only (back-compat shim).
 */
suspend fun AnalysisRepository.fetchModels(
    service: AppService,
    apiKey: String
): List<String> = fetchModelsWithKinds(service, apiKey).ids

/**
 * Fetch available models plus a per-model type classification. Native list-API
 * fields are preferred (OpenRouter modality, Cohere endpoints, Gemini supported
 * methods); naming heuristic fills in for everything else.
 */
suspend fun AnalysisRepository.fetchModelsWithKinds(
    service: AppService,
    apiKey: String
): FetchedModels = withContext(Dispatchers.IO) {
    AppLog.d("ApiDispatch", "fetchModels ${service.id} fmt=${service.apiFormat}")
    val t0 = System.currentTimeMillis()
    withTraceCategory("model/list") {
        val result = withApiCallTimeout {
            when (service.apiFormat) {
                ApiFormat.ANTHROPIC -> fetchModelsAnthropic(service, apiKey)
                ApiFormat.GOOGLE -> fetchModelsGemini(service, apiKey)
                ApiFormat.REPLICATE -> fetchModelsReplicate(service, apiKey)
                ApiFormat.OPENAI_COMPATIBLE -> fetchModelsOpenAi(service, apiKey)
            }
        }
        AppLog.d("ApiDispatch", "fetchModels ${service.id} → ${result.ids.size} models in ${System.currentTimeMillis() - t0}ms")
        result
    }
}

/** Rich outcome of an [embedWithStatus] call. The legacy [embed]
 *  wrapper collapses this into `vectors` for callers that don't need
 *  the error / status / usage details (RAG retrieval, KB indexing).
 *  Callers that DO care — the "Test all models" probe, future
 *  diagnostics, Model Info reachability checks — should use
 *  [embedWithStatus] and inspect [errorMessage]. */
data class EmbedResult(
    val vectors: List<List<Double>>? = null,
    val errorMessage: String? = null,
    val httpStatusCode: Int? = null,
    val tokenUsage: TokenUsage? = null,
    val durationMs: Long = 0L
) {
    val isSuccess: Boolean get() = !vectors.isNullOrEmpty() && errorMessage == null
}

/**
 * Compute embeddings for a batch of input strings via the provider's
 * /v1/embeddings endpoint. OpenAI-compatible only — Anthropic and Google
 * have their own embedding APIs that we don't dispatch yet.
 *
 * Reports rich status: HTTP / payload errors land in [EmbedResult
 * .errorMessage] with the response code; token usage feeds into the
 * caller's cost accounting when present.
 */
suspend fun AnalysisRepository.embedWithStatus(
    service: AppService,
    apiKey: String,
    model: String,
    texts: List<String>,
    baseUrl: String = service.baseUrl
): EmbedResult = withContext(Dispatchers.IO) {
    val t0 = System.currentTimeMillis()
    if (apiKey.isBlank()) {
        return@withContext EmbedResult(errorMessage = "API key not configured for ${service.id}")
    }
    if (texts.isEmpty()) return@withContext EmbedResult(errorMessage = "No inputs")
    AppLog.d("ApiDispatch", "embed ${service.id}/$model — ${texts.size} input(s)")
    if (service.apiFormat == ApiFormat.GOOGLE) {
        return@withContext embedGemini(service, apiKey, model, texts, baseUrl, t0)
    }
    if (service.apiFormat != ApiFormat.OPENAI_COMPATIBLE) {
        return@withContext EmbedResult(errorMessage = "Embed dispatch only supports OpenAI-compatible and Google providers")
    }
    try {
        val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
        val embedPath = service.pathFor(ModelType.EMBEDDING) ?: ModelType.DEFAULT_PATHS[ModelType.EMBEDDING]!!
        val url = buildChatUrl(baseUrl, embedPath, service.knownEndpointPaths())
        val response = withApiCallTimeout {
            api.embeddings(url, "Bearer $apiKey", OpenAiEmbeddingRequest(model = model, input = texts))
        }
        val durationMs = System.currentTimeMillis() - t0
        if (!response.isSuccessful) {
            val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            return@withContext EmbedResult(
                errorMessage = "API error: ${response.code()} ${response.message()} - $errBody",
                httpStatusCode = response.code(), durationMs = durationMs
            )
        }
        val body = response.body()
        if (body?.error != null) {
            return@withContext EmbedResult(
                errorMessage = body.error.message ?: "Embed error",
                httpStatusCode = response.code(), durationMs = durationMs
            )
        }
        val data = body?.data
        if (data.isNullOrEmpty()) {
            return@withContext EmbedResult(
                errorMessage = "No embedding vectors returned",
                httpStatusCode = response.code(), durationMs = durationMs
            )
        }
        // Sort by index defensively — most providers return in input order but the
        // spec lets them reorder, and we need the alignment exact.
        val sorted = data.sortedBy { it.index ?: 0 }
        val vectors = sorted.map { it.embedding ?: emptyList() }
        if (vectors.size != texts.size || vectors.any { it.isEmpty() }) {
            return@withContext EmbedResult(
                errorMessage = "Malformed embedding response (expected ${texts.size} vectors, got ${vectors.size})",
                httpStatusCode = response.code(), durationMs = durationMs
            )
        }
        EmbedResult(
            vectors = vectors,
            tokenUsage = body.usage?.toTokenUsage(service),
            httpStatusCode = response.code(),
            durationMs = durationMs
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        EmbedResult(
            errorMessage = "Embed call failed: ${e.message ?: e.javaClass.simpleName}",
            durationMs = System.currentTimeMillis() - t0
        )
    }
}

/** Gemini batch-embed dispatch path. The API echoes vectors back as
 *  `embeddings[i].values`; we don't get a usage block, so cost
 *  accounting relies on pricing-by-input-tokens elsewhere. */
private suspend fun embedGemini(
    service: AppService, apiKey: String, model: String,
    texts: List<String>, baseUrl: String, t0: Long
): EmbedResult {
    val api = ApiFactory.createGeminiApi(baseUrl)
    // batchEmbedContents requires the model on every per-text request,
    // in the full "models/<id>" form. Path param is the bare id.
    val fullModelName = if (model.startsWith("models/")) model else "models/$model"
    val request = GeminiBatchEmbedRequest(
        requests = texts.map { text ->
            GeminiEmbedContentRequest(
                model = fullModelName,
                content = GeminiContent(parts = listOf(GeminiPart(text = text)))
            )
        }
    )
    return try {
        val response = withApiCallTimeout { api.batchEmbedContents(model, apiKey, request) }
        val durationMs = System.currentTimeMillis() - t0
        if (!response.isSuccessful) {
            val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            return EmbedResult(
                errorMessage = "API error: ${response.code()} ${response.message()} - $errBody",
                httpStatusCode = response.code(), durationMs = durationMs
            )
        }
        val body = response.body()
        val embeddings = body?.embeddings
        if (embeddings.isNullOrEmpty()) {
            return EmbedResult(
                errorMessage = "No embedding vectors returned",
                httpStatusCode = response.code(), durationMs = durationMs
            )
        }
        val vectors = embeddings.map { it.values ?: emptyList() }
        if (vectors.size != texts.size || vectors.any { it.isEmpty() }) {
            return EmbedResult(
                errorMessage = "Malformed embedding response (expected ${texts.size} vectors, got ${vectors.size})",
                httpStatusCode = response.code(), durationMs = durationMs
            )
        }
        EmbedResult(vectors = vectors, httpStatusCode = response.code(), durationMs = durationMs)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        EmbedResult(
            errorMessage = "Embed call failed: ${e.message ?: e.javaClass.simpleName}",
            durationMs = System.currentTimeMillis() - t0
        )
    }
}

/** Back-compat wrapper. Returns null on any failure — preserves the
 *  silent fast-path semantics RAG retrieval / KB indexing relied on
 *  before [embedWithStatus] existed. */
suspend fun AnalysisRepository.embed(
    service: AppService,
    apiKey: String,
    model: String,
    texts: List<String>
): List<List<Double>>? = embedWithStatus(service, apiKey, model, texts).vectors

// ============================================================================
// Analyze implementations
// ============================================================================

private suspend fun AnalysisRepository.analyzeOpenAi(
    service: AppService, apiKey: String, prompt: String, model: String,
    params: AgentParameters?, baseUrl: String,
    imageBase64: String? = null, imageMime: String? = null
): AnalysisResponse {
    if (usesResponsesApi(service, model)) return analyzeResponsesApi(service, apiKey, prompt, model, params, baseUrl, imageBase64, imageMime)

    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val chatUrl = buildChatUrl(baseUrl, service.chatPath, service.knownEndpointPaths())
    val messages = buildMessages(params?.systemPrompt, prompt, imageBase64, imageMime)
    val request = buildOpenAiRequest(service, model, messages, params)
    val response = api.chat(chatUrl, "Bearer $apiKey", request)

    return parseOpenAiAnalysisResponse(service, response)
}

private suspend fun AnalysisRepository.analyzeResponsesApi(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?,
    baseUrl: String,
    imageBase64: String? = null, imageMime: String? = null
): AnalysisResponse {
    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val responsesUrl = responsesUrlFor(service, baseUrl)
    val input: Any = if (imageBase64 != null) {
        // Responses API accepts an array of input messages whose content is a typed
        // parts array — input_text + input_image (image_url as a data: URL).
        val mime = imageMime ?: "image/png"
        listOf(mapOf(
            "role" to "user",
            "content" to buildList {
                if (prompt.isNotBlank()) add(mapOf("type" to "input_text", "text" to prompt))
                add(mapOf("type" to "input_image", "image_url" to "data:$mime;base64,$imageBase64"))
            }
        ))
    } else prompt
    val request = OpenAiResponsesRequest(
        model = model,
        input = input,
        instructions = params?.systemPrompt?.takeIf { it.isNotBlank() },
        tools = if (params?.webSearchTool == true) responsesWebSearchTool() else null,
        reasoning = reasoningField(service, model, params?.reasoningEffort)
    )
    val response = api.responses(responsesUrl, "Bearer $apiKey", request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = extractResponsesApiContent(body)
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.toTokenUsage()
        val webData = extractResponsesWebSearch(body)
        if (content != null) AnalysisResponse(
            service, content, null, usage,
            citations = webData.citations, searchResults = webData.searchResults, relatedQuestions = webData.queries,
            rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode
        )
        // Pass `usage` on the empty-content path too — a reasoning
        // model that exhausted max_tokens on hidden thinking still
        // reports completion_tokens, and downstream callers (the
        // "Test all models" probe) treat 200 + outputTokens > 0 as
        // reachable rather than a hard failure.
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

private suspend fun AnalysisRepository.analyzeAnthropic(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?,
    imageBase64: String? = null, imageMime: String? = null
): AnalysisResponse {
    val api = ApiFactory.createClaudeApi(service.baseUrl)
    val userMessage = ChatMessage("user", prompt, imageBase64 = imageBase64, imageMime = imageMime).toClaudeMessage()
    val bundle = claudeReasoningBundle(service, model, params?.reasoningEffort, params?.maxTokens)
    val request = ClaudeRequest(
        model = model,
        messages = listOf(userMessage),
        max_tokens = bundle.maxTokens,
        temperature = params?.temperature, top_p = params?.topP, top_k = params?.topK,
        system = params?.systemPrompt?.takeIf { it.isNotBlank() },
        stop_sequences = params?.stopSequences?.takeIf { it.isNotEmpty() },
        frequency_penalty = params?.frequencyPenalty, presence_penalty = params?.presencePenalty,
        seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null,
        tools = if (params?.webSearchTool == true) anthropicWebSearchTool() else null,
        thinking = bundle.thinking,
        output_config = bundle.outputConfig
    )
    val response = api.createMessage(apiKey, request = request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        // Concatenate every text block — when web_search runs, Anthropic
        // splits the answer into many text blocks interleaved with
        // server_tool_use / web_search_tool_result. Taking only the first
        // gives just the preamble ("I'll search for…") and drops the
        // entire response. Joins fragments with no separator since the
        // model already includes its own punctuation/whitespace.
        val content = body?.content
            ?.filter { it.type == "text" }
            ?.mapNotNull { it.text }
            ?.joinToString(separator = "")
            ?.takeIf { it.isNotBlank() }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.toTokenUsage()
        val webData = extractClaudeWebSearch(body)
        if (content != null) AnalysisResponse(
            service, content, null, usage,
            citations = webData.citations, searchResults = webData.searchResults, relatedQuestions = webData.queries,
            rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode
        )
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

private suspend fun AnalysisRepository.analyzeGemini(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?,
    imageBase64: String? = null, imageMime: String? = null
): AnalysisResponse {
    val genConfig = params?.let {
        GeminiGenerationConfig(it.temperature, it.topP, it.topK, it.maxTokens,
            it.stopSequences?.takeIf { s -> s.isNotEmpty() }, it.frequencyPenalty, it.presencePenalty, it.seed,
            if (it.searchEnabled) true else null,
            thinkingConfig = geminiThinkingConfigField(service, model, it.reasoningEffort))
    }
    val systemInstruction = params?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
        GeminiContent(listOf(GeminiPart(text = it)))
    }
    val userContent = ChatMessage("user", prompt, imageBase64 = imageBase64, imageMime = imageMime).toGeminiContent()
    val request = GeminiRequest(
        contents = listOf(userContent),
        generationConfig = genConfig,
        systemInstruction = systemInstruction,
        tools = if (params?.webSearchTool == true) geminiWebSearchTool() else null
    )
    val api = ApiFactory.createGeminiApi(service.baseUrl)
    val response = api.generateContent(model, apiKey, request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.candidates
            ?.asSequence()
            ?.mapNotNull { candidate ->
                candidate.content?.parts
                    ?.mapNotNull { it.text }
                    ?.joinToString(separator = "")
                    ?.takeIf { it.isNotEmpty() }
            }
            ?.firstOrNull()
        val rawUsageJson = formatUsageJson(body?.usageMetadata)
        val usage = body?.usageMetadata?.toTokenUsage()
        val webData = extractGeminiWebSearch(body)
        if (content != null) AnalysisResponse(
            service, content, null, usage,
            citations = webData.citations, searchResults = webData.searchResults, relatedQuestions = webData.queries,
            rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode
        )
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

// ============================================================================
// Chat implementations
// ============================================================================

private suspend fun AnalysisRepository.chatOpenAiResponse(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    params: ChatParameters,
    baseUrl: String
): AnalysisResponse {
    if (usesResponsesApi(service, model)) return chatResponsesApiResponse(service, apiKey, model, messages, params, baseUrl)

    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val chatUrl = buildChatUrl(baseUrl, service.chatPath, service.knownEndpointPaths())
    val openAiMessages = messages.map { it.toOpenAiMessage() }
    val request = OpenAiRequest(
        model = model, messages = openAiMessages,
        max_tokens = params.maxTokens ?: defaultMaxTokens(service, model),
        temperature = params.temperature,
        top_p = params.topP, top_k = params.topK,
        frequency_penalty = params.frequencyPenalty, presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null,
        tools = if (params.webSearchTool) openAiChatWebSearchTool() else null,
        reasoning_effort = params.reasoningEffort?.takeIf {
            it.isNotBlank() && isReasoningCapableForDispatch(service, model)
        }
    )
    val response = api.chat(chatUrl, "Bearer $apiKey", request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    if (response.isSuccessful) {
        // Reasoning models on OpenAI-compatible chat sometimes return
        // empty `content` with the answer in `reasoning_content`
        // (SiliconFlow, Z.AI, Moonshot, DeepInfra) or `reasoning`
        // (OpenRouter) — mirror the streaming path's fallback and the
        // analyze() path's so a thinking model with a tight max_tokens
        // doesn't surface as "No response content".
        val body = response.body()
        val msg = body?.choices?.firstOrNull()?.message
        val content = msg?.contentAsString()
            ?: msg?.reasoning_content
            ?: msg?.reasoning
        val usage = body?.usage?.toTokenUsage(service)
        return if (content != null) {
            AnalysisResponse(service, content, null, usage, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            AnalysisResponse(service, null, "No response content", usage, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        return AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

private suspend fun AnalysisRepository.chatResponsesApiResponse(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters, baseUrl: String
): AnalysisResponse {
    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val responsesUrl = responsesUrlFor(service, baseUrl)
    val systemPrompt = messages.find { it.role == "system" }?.content
    val nonSystem = messages.filter { it.role != "system" }
    val anyImage = nonSystem.any { !it.imageBase64.isNullOrBlank() }
    val tools = if (params.webSearchTool) responsesWebSearchTool() else null
    val reasoning = reasoningField(service, model, params.reasoningEffort)
    // Mirrors analyzeResponsesApi's content-block construction when a
    // turn carries an attached image. Without this, OpenAiResponsesInputMessage(role, content)
    // is text-only and any imageBase64 on a chat turn (📎 picker, share-target, camera capture)
    // gets silently dropped on gpt-5 / o3 / gpt-4.1 etc. — the model replies "I can't see the
    // image you're referring to" with no signal that the bytes never reached it.
    val request = if (anyImage) {
        val inputItems: List<Map<String, Any?>> = nonSystem.map { msg ->
            val mime = msg.imageMime ?: "image/png"
            val parts = buildList {
                if (msg.content.isNotBlank()) add(mapOf("type" to "input_text", "text" to msg.content))
                if (!msg.imageBase64.isNullOrBlank()) {
                    add(mapOf("type" to "input_image", "image_url" to "data:$mime;base64,${msg.imageBase64}"))
                }
            }
            mapOf("role" to msg.role, "content" to parts)
        }
        OpenAiResponsesRequest(model = model, input = inputItems, instructions = systemPrompt, tools = tools, reasoning = reasoning)
    } else {
        val inputMessages = nonSystem.map { OpenAiResponsesInputMessage(it.role, it.content) }
        if (inputMessages.size == 1 && inputMessages.first().role == "user") {
            OpenAiResponsesRequest(model = model, input = inputMessages.first().content, instructions = systemPrompt, tools = tools, reasoning = reasoning)
        } else {
            OpenAiResponsesRequest(model = model, input = inputMessages, instructions = systemPrompt, tools = tools, reasoning = reasoning)
        }
    }
    val response = api.responses(responsesUrl, "Bearer $apiKey", request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    if (response.isSuccessful) {
        val body = response.body()
        val content = extractResponsesApiContent(body)
        val usage = body?.usage?.toTokenUsage(service)
        return if (content != null) {
            AnalysisResponse(service, content, null, usage, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            AnalysisResponse(service, null, body?.error?.message ?: "No response content", usage, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        return AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal fun responsesUrlFor(service: AppService, baseUrl: String): String =
    buildChatUrl(baseUrl, service.responsesPath ?: "v1/responses", service.knownEndpointPaths())

/** Best-effort reconstruction of the request URL for the audit log's
 *  technical line. Mirrors the per-format dispatchers' URL choice;
 *  falls back to [baseUrl] if anything is off. Query params (incl.
 *  Google's `?key=`) are intentionally omitted — [AuditLog] redacts
 *  secrets, but the bare endpoint is what the audit cares about. */
internal fun AnalysisRepository.dispatchUrl(service: AppService, model: String, baseUrl: String): String = try {
    when (service.apiFormat) {
        ApiFormat.OPENAI_COMPATIBLE ->
            if (usesResponsesApi(service, model)) responsesUrlFor(service, baseUrl)
            else buildChatUrl(baseUrl, service.chatPath, service.knownEndpointPaths())
        // Anthropic / Google baseUrls already encode the endpoint path (and
        // Google's carries a `{model}` template), so rebuild from the bare
        // host + the canonical path to avoid a doubled `/v1/messages/v1/messages`.
        ApiFormat.ANTHROPIC -> hostBaseOf(baseUrl) + "/v1/messages"
        ApiFormat.GOOGLE -> hostBaseOf(baseUrl) + "/v1beta/models/$model:generateContent"
        ApiFormat.REPLICATE -> baseUrl.trimEnd('/') + "/models/$model/predictions"
    }
} catch (_: Exception) { baseUrl }

/** `scheme://host` of [url], dropping any path/query. String-based (not
 *  [java.net.URI]) because Google's baseUrl carries an illegal `{model}`
 *  template that would make URI parsing throw — and then the caller would
 *  re-append the path onto the full template, doubling it. */
private fun hostBaseOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url.substringBefore("/").substringBefore("?")
    val firstSlash = url.indexOf('/', schemeEnd + 3)
    return if (firstSlash < 0) url else url.substring(0, firstSlash)
}

/** Central audit hook wrapping a single report-generating dispatch.
 *  Emits the per-call **technical line** (URL, tokens, cost / error)
 *  to [AuditLog] for the report currently on [ApiTracer.currentReportId]
 *  — a no-op for non-report calls (chat, embeddings, model tests) whose
 *  tracer tags carry no reportId. A thrown call (network failure, timeout)
 *  still writes an error technical line before rethrowing; cancellations
 *  pass through untouched. */
internal suspend fun AnalysisRepository.auditApiCall(
    service: AppService,
    model: String,
    baseUrl: String,
    block: suspend () -> AnalysisResponse
): AnalysisResponse {
    val reportId = ApiTracer.currentReportId
    if (reportId == null) return block()
    val url = dispatchUrl(service, model, baseUrl)
    val traceSink = java.util.concurrent.atomic.AtomicReference<String?>()
    try {
        val resp = withTraceFilenameSink(traceSink) { block() }
        AuditLog.appendApiCall(reportId, service, model, url, resp.tokenUsage, resp.httpStatusCode, resp.error, traceSink.get())
        return resp
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        AuditLog.appendApiCall(reportId, service, model, url, null, null, e.message ?: e.javaClass.simpleName, traceSink.get())
        throw e
    }
}

private suspend fun AnalysisRepository.chatAnthropicResponse(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters
): AnalysisResponse {
    val api = ApiFactory.createClaudeApi(service.baseUrl)
    val claudeMessages = messages.filter { it.role != "system" }.map { it.toClaudeMessage() }
    val systemPrompt = messages.find { it.role == "system" }?.content
    val bundle = claudeReasoningBundle(service, model, params.reasoningEffort, params.maxTokens)
    val request = ClaudeRequest(
        model = model, messages = claudeMessages, max_tokens = bundle.maxTokens,
        temperature = params.temperature, top_p = params.topP, top_k = params.topK,
        system = systemPrompt, search = if (params.searchEnabled) true else null,
        tools = if (params.webSearchTool) anthropicWebSearchTool() else null,
        thinking = bundle.thinking,
        output_config = bundle.outputConfig
    )
    val response = api.createMessage(apiKey, request = request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    if (response.isSuccessful) {
        // See analyzeAnthropic for why we concatenate every text block.
        val body = response.body()
        val content = body?.content
            ?.filter { it.type == "text" }
            ?.mapNotNull { it.text }
            ?.joinToString(separator = "")
            ?.takeIf { it.isNotBlank() }
        val usage = body?.usage?.toTokenUsage()
        return if (content != null) {
            AnalysisResponse(service, content, null, usage, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            AnalysisResponse(service, null, "No response content", usage, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        return AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

private suspend fun AnalysisRepository.chatGeminiResponse(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters
): AnalysisResponse {
    val api = ApiFactory.createGeminiApi(service.baseUrl)
    val contents = messages.filter { it.role != "system" }.map { it.toGeminiContent() }
    val systemInstruction = messages.find { it.role == "system" }?.let { GeminiContent(listOf(GeminiPart(text = it.content))) }
    val request = GeminiRequest(
        contents = contents,
        generationConfig = GeminiGenerationConfig(
            params.temperature, params.topP, params.topK, params.maxTokens,
            search = if (params.searchEnabled) true else null,
            thinkingConfig = geminiThinkingConfigField(service, model, params.reasoningEffort)
        ),
        systemInstruction = systemInstruction,
        tools = if (params.webSearchTool) geminiWebSearchTool() else null
    )
    val response = api.generateContent(model, apiKey, request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    if (response.isSuccessful) {
        val body = response.body()
        val joined = body?.candidates
            ?.flatMap { it.content?.parts ?: emptyList() }
            ?.mapNotNull { it.text }
            ?.joinToString(separator = "")
            ?.takeIf { it.isNotEmpty() }
        val content = joined
            ?: body?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        val usage = body?.usageMetadata?.toTokenUsage()
        return if (content != null) {
            AnalysisResponse(service, content, null, usage, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            AnalysisResponse(service, null, "No response content", usage, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        return AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

// ============================================================================
// Model fetching implementations
// ============================================================================

suspend fun AnalysisRepository.testModel(service: AppService, apiKey: String, model: String): String? = withContext(Dispatchers.IO) {
    withTraceCategory("Provider test") {
        try {
            // Reachability probe — only needs "OK" back, so cap tiny rather
            // than inheriting defaultMaxTokens (the model's full output window,
            // which overflows input+output context limits → OpenRouter 400 /
            // Together 422, and balance-pre-auths expensive models).
            val response = analyze(
                service, apiKey, AnalysisRepository.TEST_PROMPT, model,
                params = AgentParameters(maxTokens = AnalysisRepository.TEST_MAX_TOKENS)
            )
            if (response.isSuccess) null else response.error ?: "Unknown error"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { e.message ?: "Connection error" }
    }
}

suspend fun AnalysisRepository.testModelWithPrompt(
    service: AppService, apiKey: String, model: String, prompt: String
): Pair<String?, String?> = withContext(Dispatchers.IO) {
    withTraceCategory("Provider test") {
        try {
            val response = analyze(
                service, apiKey, prompt, model,
                params = AgentParameters(maxTokens = AnalysisRepository.TEST_MAX_TOKENS)
            )
            if (response.isSuccess) Pair(response.analysis, null) else Pair(null, response.error ?: "Unknown error")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { Pair(null, e.message ?: "Connection error") }
    }
}

/** Test API connection with raw JSON body. */
suspend fun AnalysisRepository.testApiConnectionWithJson(
    service: AppService, apiKey: String, baseUrl: String, jsonBody: String
): AnalysisResponse = withContext(Dispatchers.IO) {
    AppLog.d("ApiDispatch", "testApiConnectionWithJson ${service.id} bodyLen=${jsonBody.length}")
    withTraceCategory("Provider test") {
    try {
        val client = OkHttpClient.Builder()
            // Provider-test calls run on their own client (with shorter
            // timeouts than the shared one) but still need to respect
            // the user's per-provider throttle — otherwise a manual
            // raw-JSON submit could bypass the caps.
            .addInterceptor(ProviderThrottleInterceptor())
            .addInterceptor(TracingInterceptor())
            .connectTimeout(com.ai.BuildConfig.NETWORK_CONNECT_TIMEOUT_SEC.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(com.ai.BuildConfig.TEST_CONNECTION_READ_TIMEOUT_SEC.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(com.ai.BuildConfig.NETWORK_WRITE_TIMEOUT_SEC.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val effectiveJson = try {
            @Suppress("DEPRECATION")
            val jsonElement = com.google.gson.JsonParser().parse(jsonBody)
            if (jsonElement.isJsonObject) { jsonElement.asJsonObject.addProperty("stream", false); createAppGson().toJson(jsonElement) }
            else jsonBody
        } catch (_: Exception) { jsonBody }

        val body = effectiveJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestBuilder = okhttp3.Request.Builder().post(body).addHeader("Content-Type", "application/json")
        when (service.apiFormat) {
            ApiFormat.ANTHROPIC -> { requestBuilder.url(baseUrl); requestBuilder.addHeader("x-api-key", apiKey); requestBuilder.addHeader("anthropic-version", "2023-06-01") }
            ApiFormat.GOOGLE -> { val encKey = android.net.Uri.encode(apiKey); val url = if (baseUrl.contains("key=")) baseUrl else "$baseUrl${if (baseUrl.contains("?")) "&" else "?"}key=$encKey"; requestBuilder.url(url) }
            else -> { requestBuilder.url(baseUrl); requestBuilder.addHeader("Authorization", "Bearer $apiKey") }
        }
        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        response.use {
            val headers = formatHeaders(response.headers)
            val statusCode = response.code
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                try {
                    // Parse the success body by the provider's API format
                    // (Bug 11): a Gemini/Anthropic provider returns a shape
                    // that OpenAiResponse can't match, which previously fell
                    // through to the raw-body branch and could mislabel a
                    // working provider.
                    val content: String? = when (service.apiFormat) {
                        ApiFormat.GOOGLE -> {
                            val g = createAppGson().fromJson(responseBody, GeminiResponse::class.java)
                            g?.candidates?.flatMap { it.content?.parts ?: emptyList() }
                                ?.mapNotNull { it.text }?.joinToString(separator = "")?.takeIf { it.isNotEmpty() }
                        }
                        ApiFormat.ANTHROPIC -> {
                            val c = createAppGson().fromJson(responseBody, ClaudeResponse::class.java)
                            c?.content?.mapNotNull { it.text }?.joinToString(separator = "")?.takeIf { it.isNotEmpty() }
                        }
                        else -> {
                            val oaiResponse = createAppGson().fromJson(responseBody, OpenAiResponse::class.java)
                            oaiResponse?.choices?.firstOrNull()?.message?.contentAsString()
                        }
                    }
                    if (content != null) AnalysisResponse(service, content, null, null, httpHeaders = headers, httpStatusCode = statusCode)
                    else AnalysisResponse(service, responseBody, null, httpHeaders = headers, httpStatusCode = statusCode)
                } catch (_: Exception) { AnalysisResponse(service, responseBody, null, httpHeaders = headers, httpStatusCode = statusCode) }
            } else {
                AnalysisResponse(service, null, "API error: $statusCode - ${response.body.string()}", httpHeaders = headers, httpStatusCode = statusCode)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) { AnalysisResponse(service, null, "Error: ${e.message}") }
    }
}

// ============================================================================
// Helpers
// ============================================================================

// ============================================================================
// Streaming report dispatch — mirrors analyzeOpenAi / analyzeAnthropic /
// analyzeGemini EXACTLY (same buildMessages / buildOpenAiRequest / reasoning
// bundles), but with stream=true so the model card can show text as it
// arrives. Usage is recovered from the SSE so cost stays as exact as the
// non-streaming path. `onDelta` is called for every text chunk.
// ============================================================================

/** Format-dispatch sibling of [analyze] for the streaming-report path.
 *  Returns the same [AnalysisResponse] shape (text + TokenUsage); on a
 *  provider that doesn't emit in-stream usage, tokenUsage comes back null
 *  and the caller estimates rather than re-billing. */
internal suspend fun AnalysisRepository.analyzeAgentStreaming(
    service: AppService, apiKey: String, prompt: String, model: String,
    params: AgentParameters?, baseUrl: String = service.baseUrl,
    imageBase64: String? = null, imageMime: String? = null,
    onDelta: (String) -> Unit
): AnalysisResponse = withContext(Dispatchers.IO) {
    auditApiCall(service, model, baseUrl) {
        // No withApiCallTimeout here: the full SSE body is drained inside
        // these stream*Report calls (collectStreamResponse), and wrapping
        // that drain in the streaming-OPEN coroutine deadline (~300s) clips
        // a healthy long report mid-stream and discards the accumulated
        // text. The body is already bounded by OkHttp's 900s callTimeout
        // plus the per-chunk streaming read timeout — exactly as the chat
        // streaming path (sendChatStream) relies on.
        withHostGate(baseUrl) {
            when (service.apiFormat) {
                ApiFormat.ANTHROPIC -> streamAnthropicReport(service, apiKey, prompt, model, params, imageBase64, imageMime, onDelta)
                ApiFormat.GOOGLE -> streamGeminiReport(service, apiKey, prompt, model, params, imageBase64, imageMime, onDelta)
                ApiFormat.REPLICATE -> streamReplicateReport(service, apiKey, prompt, model, params, imageBase64, imageMime, onDelta)
                ApiFormat.OPENAI_COMPATIBLE -> streamOpenAiReport(service, apiKey, prompt, model, params, baseUrl, imageBase64, imageMime, onDelta)
            }
        }
    }
}

/** Drain an SSE [retrofit2.Response] into an [AnalysisResponse], emitting
 *  each text chunk through [onDelta] and merging any usage events. */
