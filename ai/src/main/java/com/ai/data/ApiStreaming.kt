package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

// ============================================================================
// Unified streaming entry point
// ============================================================================

/**
 * Send a chat message with streaming response via SSE.
 * Returns a Flow that emits content chunks as they arrive.
 */
fun AnalysisRepository.sendChatStream(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    params: ChatParameters,
    baseUrl: String? = null
): Flow<String> = flow {
    val effectiveUrl = baseUrl ?: service.baseUrl
    // LiteLLM gating: when the model is known not to support native SSE
    // streaming, route through the non-streaming sendChat path and emit
    // the full response as a single chunk. The chat UI's accumulator
    // sees one large appended chunk instead of an empty stream.
    if (PricingCache.liteLLMSupportsNativeStreaming(service, model) == false) {
        val full = sendChat(service, apiKey, model, messages, params, effectiveUrl)
        emit(full)
        return@flow
    }
    when (service.apiFormat) {
        ApiFormat.ANTHROPIC -> streamAnthropic(service, apiKey, model, messages, params, effectiveUrl).collect { emit(it) }
        ApiFormat.GOOGLE -> streamGemini(service, apiKey, model, messages, params, effectiveUrl).collect { emit(it) }
        ApiFormat.OPENAI_COMPATIBLE -> streamOpenAi(service, apiKey, model, messages, params, effectiveUrl).collect { emit(it) }
    }
}

// ============================================================================
// Shared SSE reader — parses lines, extracts content via format-specific lambda
// ============================================================================

internal fun parseSseStream(
    body: ResponseBody,
    extractContent: (eventType: String?, data: String) -> String?,
    isFinalChunk: (eventType: String?, data: String) -> Boolean = { _, _ -> false },
    requireTerminator: Boolean = false,
    // Optional usage side-channel for the streaming-report path. When set,
    // every event is also offered to [extractUsage]; any TokenUsage it
    // returns is handed to [onUsage] (which merges across events). Chat
    // callers leave both null and see the unchanged content-only Flow.
    extractUsage: ((eventType: String?, data: String) -> Pair<TokenUsage?, String?>?)? = null,
    onUsage: ((TokenUsage, String?) -> Unit)? = null
): Flow<String> = flow {
    // SSE is defined as UTF-8 and provider servers often omit charset, so
    // keep forcing UTF-8 there. Some chunked JSON responses also pass through
    // this reader; for those, honor an explicit non-UTF-8 Content-Type charset
    // and fall back to UTF-8 when the server omits it.
    AppLog.d("SSE", "stream open")
    val parseStartMs = System.currentTimeMillis()
    var chunkCount = 0
    val contentType = body.contentType()
    val charset = if (contentType?.type == "text" && contentType.subtype == "event-stream") {
        Charsets.UTF_8
    } else {
        contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    }
    val reader = java.io.InputStreamReader(body.byteStream(), charset).buffered()
    try {
        // Per the W3C SSE spec, an event is delimited by a blank line.
        // Multiple `data:` lines inside one event must be concatenated
        // with `\n` and dispatched together on the blank line. Dispatching
        // each `data:` line eagerly (the previous behaviour) split JSON
        // payloads that some providers shard across two lines, so the
        // per-format `extractContent` parser would silently fail.
        var eventType: String? = null
        val dataBuf = StringBuilder()
        var sawTerminator = false
        var sawAnyData = false

        suspend fun dispatch() {
            if (dataBuf.isEmpty()) {
                eventType = null
                return
            }
            // Trailing newline shouldn't leak into the parser.
            val data = dataBuf.toString().removeSuffix("\n")
            dataBuf.setLength(0)
            if (data.equals("[DONE]", ignoreCase = true)) {
                AppLog.d("SSE", "[DONE] terminator (event=$eventType)")
                sawTerminator = true; eventType = null; return
            }
            sawAnyData = true
            if (extractUsage != null && onUsage != null) {
                try {
                    extractUsage(eventType, data)?.let { (u, raw) -> if (u != null) onUsage(u, raw) }
                } catch (_: Exception) { /* usage is best-effort — never break the content stream */ }
            }
            val content = extractContent(eventType, data)
            // Per-chunk TRACE: log the event-type tag and payload size
            // (not the payload itself — that would duplicate the trace
            // file and leak content). Skip when nothing extracted, that
            // narrows the noise to chunks that actually carry data.
            if (!content.isNullOrEmpty()) {
                chunkCount++
                AppLog.d("SSE", "chunk event=${eventType ?: "(none)"} dataBytes=${data.length} contentBytes=${content.length}")
                emit(content)
            }
            if (isFinalChunk(eventType, data)) {
                AppLog.d("SSE", "final chunk (event=$eventType)")
                sawTerminator = true
            }
            eventType = null
        }

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue
            if (currentLine.isBlank()) { dispatch(); continue }
            if (currentLine.startsWith("event:")) {
                eventType = currentLine.removePrefix("event:").trim()
                // Per-format terminator events. Anthropic ends with
                // `event: message_stop`; OpenAI's Responses API ends
                // with `event: response.completed` (and may or may not
                // also send a trailing `data: [DONE]` for back-compat
                // depending on which Responses API revision the
                // upstream is on — recognising the event keeps us
                // correct either way).
                if (eventType == "message_stop"
                    || eventType == "response.completed") sawTerminator = true
                continue
            }
            if (currentLine.startsWith(":")) continue  // SSE comment
            if (currentLine.startsWith("data:")) {
                val chunk = currentLine.removePrefix("data:").let {
                    // SSE permits but doesn't require a leading space
                    // after the colon; spec strips one if present.
                    if (it.startsWith(" ")) it.substring(1) else it
                }
                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                dataBuf.append(chunk)
            }
        }
        // Flush a trailing event that ended via TCP close (no blank line).
        dispatch()
        // Many OpenAI-compatible clones (vLLM/Ollama-compat, some proxies)
        // end a Chat Completions stream by simply closing the socket after the
        // last delta, with no `data: [DONE]` terminator. Keep that tolerant
        // default, but require a final event for formats that have one
        // (Anthropic message_stop, OpenAI Responses response.completed, Gemini
        // finishReason). Otherwise a mid-answer socket drop after some content
        // would look like a clean, complete answer.
        if (!sawTerminator && sawAnyData && (requireTerminator || chunkCount == 0)) {
            throw java.io.IOException("SSE stream ended without terminator — response likely truncated")
        }
    } finally {
        try { reader.close() } catch (_: Exception) {}
        body.close()
        AppLog.d("SSE", "stream closed — $chunkCount chunks in ${System.currentTimeMillis() - parseStartMs}ms")
    }
}

/** Gemini SSE terminator: a candidate chunk with a non-null `finishReason`. Gemini doesn't
 *  send a [DONE] line — it just closes the connection after this chunk. */
internal fun isGeminiFinalChunk(@Suppress("UNUSED_PARAMETER") eventType: String?, data: String): Boolean {
    return try {
        val obj = gson.fromJson(data, com.google.gson.JsonObject::class.java) ?: return false
        val candidates = obj.getAsJsonArray("candidates") ?: return false
        candidates.any { c -> c.asJsonObject?.get("finishReason")?.takeIf { !it.isJsonNull } != null }
    } catch (_: Exception) { false }
}

// ============================================================================
// Format-specific content extractors
// ============================================================================

private val gson = createAppGson()

// ============================================================================
// Usage extractors — recover exact token counts from the SSE stream so the
// streaming-report path keeps cost as accurate as the non-streaming call.
// Each returns (TokenUsage, rawUsageJson) for events that carry usage, else null.
// ============================================================================

/** OpenAI Chat Completions: the trailing include_usage chunk carries `usage`. */
internal fun extractOpenAiUsage(service: AppService): (String?, String) -> Pair<TokenUsage?, String?>? = { _, data ->
    try {
        gson.fromJson(data, OpenAiStreamChunk::class.java)?.usage
            ?.let { it.toTokenUsage(service) to gson.toJson(it) }
    } catch (_: Exception) { null }
}

/** OpenAI Responses API: the `response.completed` event holds response.usage. */
internal fun extractResponsesApiUsage(service: AppService): (String?, String) -> Pair<TokenUsage?, String?>? = fn@{ eventType, data ->
    if (eventType != "response.completed") return@fn null
    try {
        val usageObj = gson.fromJson(data, com.google.gson.JsonObject::class.java)
            ?.getAsJsonObject("response")?.getAsJsonObject("usage") ?: return@fn null
        gson.fromJson(usageObj, OpenAiUsage::class.java)?.toTokenUsage(service) to usageObj.toString()
    } catch (_: Exception) { null }
}

/** Anthropic: input (+cache) on message_start, output on message_delta. */
internal val extractClaudeUsage: (String?, String) -> Pair<TokenUsage?, String?>? = fn@{ eventType, data ->
    try {
        val ev = gson.fromJson(data, ClaudeStreamEvent::class.java) ?: return@fn null
        when (eventType) {
            "message_start" -> ev.message?.usage?.let { it.toTokenUsage() to gson.toJson(it) }
            "message_delta" -> ev.usage?.let { it.toTokenUsage() to gson.toJson(it) }
            else -> null
        }
    } catch (_: Exception) { null }
}

/** Gemini: usageMetadata rides along on chunks (cumulative; keep the latest). */
internal val extractGeminiUsage: (String?, String) -> Pair<TokenUsage?, String?>? = { _, data ->
    try {
        gson.fromJson(data, GeminiStreamChunk::class.java)?.usageMetadata
            ?.let { it.toTokenUsage() to gson.toJson(it) }
    } catch (_: Exception) { null }
}

/** Merge usage observed across SSE events by field-wise max (handles
 *  Anthropic's split input/output events and Gemini's cumulative chunks;
 *  a single complete event like OpenAI's final chunk merges with the
 *  null seed to itself). apiCost / rawJson take the latest non-null. */
internal fun mergeUsage(a: TokenUsage?, b: TokenUsage): TokenUsage {
    if (a == null) return b
    return TokenUsage(
        inputTokens = maxOf(a.inputTokens, b.inputTokens),
        outputTokens = maxOf(a.outputTokens, b.outputTokens),
        apiCost = b.apiCost ?: a.apiCost,
        cachedInputTokens = maxOf(a.cachedInputTokens, b.cachedInputTokens),
        cacheCreationTokens = maxOf(a.cacheCreationTokens, b.cacheCreationTokens),
        reasoningTokens = maxOf(a.reasoningTokens, b.reasoningTokens)
    )
}

/** OpenAI Chat Completions SSE: data contains choices[0].delta.content */
internal fun extractOpenAiContent(eventType: String?, data: String): String? {
    return try {
        val chunk = gson.fromJson(data, OpenAiStreamChunk::class.java)
        val delta = chunk?.choices?.firstOrNull()?.delta
        delta?.content?.takeIf { it.isNotEmpty() }
            ?: delta?.reasoning_content?.takeIf { it.isNotEmpty() }
            ?: delta?.reasoning?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

/** Stateful OpenAI Chat-Completions content extractor that emits ONLY the
 *  answer (`delta.content`) and buffers reasoning (`reasoning_content` /
 *  `reasoning`) separately, so a reasoning model's chain-of-thought never gets
 *  concatenated into the answer (the flat [extractOpenAiContent] does, because
 *  early reasoning deltas have empty content and fall through). After the
 *  stream, call [reasoningFallback] to recover the answer for the
 *  non-conforming providers that put it in `reasoning_content` with empty
 *  `content` (see the matching whole-message fallback in parseOpenAiAnalysisResponse). */
internal class OpenAiContentExtractor {
    private val reasoning = StringBuilder()
    var sawContent = false; private set
    fun extract(@Suppress("UNUSED_PARAMETER") eventType: String?, data: String): String? {
        return try {
            val delta = gson.fromJson(data, OpenAiStreamChunk::class.java)?.choices?.firstOrNull()?.delta
            val content = delta?.content?.takeIf { it.isNotEmpty() }
            if (content != null) { sawContent = true; content }
            else {
                (delta?.reasoning_content?.takeIf { it.isNotEmpty() }
                    ?: delta?.reasoning?.takeIf { it.isNotEmpty() })?.let { reasoning.append(it) }
                null
            }
        } catch (_: Exception) { null }
    }
    /** Reasoning text to use as the answer iff NO content was streamed. */
    fun reasoningFallback(): String? =
        if (!sawContent) reasoning.toString().takeIf { it.isNotBlank() } else null
}

/** OpenAI Responses API SSE: event=response.output_text.delta, data.delta contains text */
internal fun extractResponsesApiContent(eventType: String?, data: String): String? {
    if (eventType != "response.output_text.delta") return null
    return try {
        gson.fromJson(data, com.google.gson.JsonObject::class.java)?.get("delta")?.asString?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

/** Anthropic SSE: event=content_block_delta, data.delta.text */
internal fun extractClaudeContent(eventType: String?, data: String): String? {
    if (eventType == "message_stop") return null  // Signal end (handled by [DONE] or stream close)
    if (eventType != "content_block_delta") return null
    return try {
        gson.fromJson(data, ClaudeStreamEvent::class.java)?.delta?.text?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

/** Gemini SSE: data contains candidates[0].content.parts[0].text */
internal fun extractGeminiContent(eventType: String?, data: String): String? {
    return try {
        gson.fromJson(data, GeminiStreamChunk::class.java)
            ?.candidates?.firstOrNull()?.content?.parts
            ?.mapNotNull { it.text }
            ?.joinToString(separator = "")
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

// ============================================================================
// Provider-specific streaming methods
// ============================================================================

private fun AnalysisRepository.streamOpenAi(
    service: AppService, apiKey: String, model: String,
    messages: List<ChatMessage>, params: ChatParameters, baseUrl: String
): Flow<String> = flow {
    if (usesResponsesApi(service, model)) {
        val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
        val responsesUrl = buildChatUrl(baseUrl, service.responsesPath ?: "v1/responses", service.knownEndpointPaths())
        val nonSystem = messages.filter { it.role != "system" }
        val systemPrompt = messages.find { it.role == "system" }?.content
        // Image-bearing turns need the typed content-block shape;
        // string-only turns can take the simpler OpenAiResponsesInputMessage
        // form. See chatResponsesApi for the matching non-streaming path.
        val anyImage = nonSystem.any { !it.imageBase64.isNullOrBlank() }
        val input: Any = if (anyImage) {
            nonSystem.map { msg ->
                val mime = msg.imageMime ?: "image/png"
                val parts = buildList {
                    if (msg.content.isNotBlank()) add(mapOf("type" to "input_text", "text" to msg.content))
                    if (!msg.imageBase64.isNullOrBlank()) {
                        add(mapOf("type" to "input_image", "image_url" to "data:$mime;base64,${msg.imageBase64}"))
                    }
                }
                mapOf("role" to msg.role, "content" to parts)
            }
        } else {
            nonSystem.map { OpenAiResponsesInputMessage(it.role, it.content) }
        }
        val request = OpenAiResponsesRequest(
            model = model, input = input, instructions = systemPrompt, stream = true,
            tools = if (params.webSearchTool) responsesWebSearchTool() else null,
            reasoning = reasoningField(service, model, params.reasoningEffort)
        )
        val response = withApiCallTimeout(streamingOpen = true) { withContext(Dispatchers.IO) { api.responsesStream(responsesUrl, "Bearer $apiKey", request) } }
        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseSseStream(
                    body,
                    ::extractResponsesApiContent,
                    requireTerminator = true
                ).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            // Drain + close the error body so OkHttp doesn't hold a
            // connection until the next GC. The previous code read
            // the status / message but never touched the body.
            val errorMsg = try { response.errorBody()?.string() } catch (_: Exception) { null }
            throw Exception("API error: ${response.code()} ${response.message()} - $errorMsg")
        }
    } else {
        val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
        val chatUrl = buildChatUrl(baseUrl, service.chatPath, service.knownEndpointPaths())
        val openAiMessages = messages.map { it.toOpenAiMessage() }
        val request = OpenAiRequest(
            model = model, messages = openAiMessages, stream = true,
            // Bounded default — see [defaultMaxTokens].
            max_tokens = params.maxTokens ?: defaultMaxTokens(service, model),
            temperature = params.temperature,
            top_p = params.topP, top_k = params.topK,
            frequency_penalty = params.frequencyPenalty, presence_penalty = params.presencePenalty,
            search = if (params.searchEnabled) true else null,
            return_citations = if (service.supportsCitations) params.returnCitations else null,
            search_recency_filter = if (service.supportsSearchRecency) params.searchRecency else null,
            tools = if (params.webSearchTool) openAiChatWebSearchTool() else null,
            reasoning_effort = params.reasoningEffort?.takeIf {
                it.isNotBlank() && isReasoningCapableForDispatch(service, model)
            }
        )
        val response = withApiCallTimeout(streamingOpen = true) { withContext(Dispatchers.IO) { api.chatStream(chatUrl, "Bearer $apiKey", request) } }
        if (response.isSuccessful) {
            response.body()?.let { body ->
                // Emit content only, buffering reasoning, so the chain-of-
                // thought isn't interleaved into the streamed answer AND the
                // truncation guard counts real content chunks (not reasoning,
                // which previously masked a reasoning-only / truncated stream
                // as a completed answer). Surface reasoning at the end only
                // when no content streamed (answer-in-reasoning_content).
                val ext = OpenAiContentExtractor()
                var reasoningFallbackEmitted = false
                suspend fun flushReasoningFallback() {
                    if (reasoningFallbackEmitted) return
                    val fallback = ext.reasoningFallback() ?: return
                    reasoningFallbackEmitted = true
                    emit(fallback)
                }
                try {
                    parseSseStream(body, ext::extract).collect { emit(it) }
                    flushReasoningFallback()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    try { flushReasoningFallback() } catch (_: kotlinx.coroutines.CancellationException) {}
                    throw e
                } catch (e: Exception) {
                    flushReasoningFallback()
                    throw e
                }
            } ?: throw Exception("Empty response body")
        } else {
            val errorMsg = try { response.errorBody()?.string() } catch (_: Exception) { null }
            throw Exception("API error: ${response.code()} ${response.message()} - $errorMsg")
        }
    }
}

private fun AnalysisRepository.streamAnthropic(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>,
    params: ChatParameters, baseUrl: String
): Flow<String> = flow {
    val api = ApiFactory.createClaudeApi(baseUrl)
    val claudeMessages = messages.filter { it.role != "system" }.map { it.toClaudeMessage() }
    val systemPrompt = messages.find { it.role == "system" }?.content
    val bundle = claudeReasoningBundle(service, model, params.reasoningEffort, params.maxTokens)
    val request = ClaudeRequest(
        model = model, messages = claudeMessages, stream = true,
        max_tokens = bundle.maxTokens,
        temperature = params.temperature, top_p = params.topP, top_k = params.topK,
        system = systemPrompt,
        frequency_penalty = params.frequencyPenalty, presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null,
        tools = if (params.webSearchTool) anthropicWebSearchTool() else null,
        thinking = bundle.thinking,
        output_config = bundle.outputConfig
    )
    val response = withApiCallTimeout(streamingOpen = true) { withContext(Dispatchers.IO) { api.createMessageStream(apiKey, request = request) } }
    if (response.isSuccessful) {
        response.body()?.let { body ->
            parseSseStream(
                body,
                ::extractClaudeContent,
                requireTerminator = true
            ).collect { emit(it) }
        } ?: throw Exception("Empty response body")
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}

private fun AnalysisRepository.streamGemini(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>,
    params: ChatParameters, baseUrl: String
): Flow<String> = flow {
    val api = ApiFactory.createGeminiApi(baseUrl)
    val contents = messages.filter { it.role != "system" }.map { it.toGeminiContent() }
    val systemInstruction = messages.find { it.role == "system" }?.let { GeminiContent(listOf(GeminiPart(text = it.content))) }
    val request = GeminiRequest(
        contents = contents,
        generationConfig = GeminiGenerationConfig(
            params.temperature, params.topP, params.topK, params.maxTokens,
            // frequency/presence penalty were dropped on the streaming path, so a
            // Gemini chat with them set behaved differently from the non-streaming
            // analyzeGemini path (audit data#14). (stopSequences/seed aren't on
            // ChatParameters, so they stay unset for chat.)
            frequencyPenalty = params.frequencyPenalty,
            presencePenalty = params.presencePenalty,
            search = if (params.searchEnabled) true else null,
            thinkingConfig = geminiThinkingConfigField(service, model, params.reasoningEffort)
        ),
        systemInstruction = systemInstruction,
        tools = if (params.webSearchTool) geminiWebSearchTool() else null
    )
    val response = withApiCallTimeout(streamingOpen = true) { withContext(Dispatchers.IO) { api.streamGenerateContent(model, apiKey, request = request) } }
    if (response.isSuccessful) {
        response.body()?.let { body ->
            parseSseStream(
                body,
                ::extractGeminiContent,
                ::isGeminiFinalChunk,
                requireTerminator = true
            ).collect { emit(it) }
        } ?: throw Exception("Empty response body")
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}
