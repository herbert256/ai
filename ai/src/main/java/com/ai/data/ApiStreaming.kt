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

private fun parseSseStream(
    body: ResponseBody,
    extractContent: (eventType: String?, data: String) -> String?,
    isFinalChunk: (eventType: String?, data: String) -> Boolean = { _, _ -> false }
): Flow<String> = flow {
    val reader = body.charStream().buffered()
    try {
        var eventType: String? = null
        var sawTerminator = false
        var sawAnyData = false
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue
            if (currentLine.isBlank()) { eventType = null; continue }
            if (currentLine.startsWith("event:")) {
                eventType = currentLine.removePrefix("event:").trim()
                // Per-format terminator events. Anthropic ends with
                // `event: message_stop`; OpenAI's Responses API ends
                // with `event: response.completed` (and may or may not
                // also send a trailing `data: [DONE]` for back-compat
                // depending on which Responses API revision the
                // upstream is on — recognising the event keeps us
                // correct either way). Without this, every Responses
                // API stream that doesn't ship the legacy [DONE]
                // throws "ended without terminator" right after a
                // perfectly valid response.
                if (eventType == "message_stop"
                    || eventType == "response.completed") sawTerminator = true
                continue
            }
            if (currentLine.startsWith(":")) continue  // SSE comment
            if (currentLine.startsWith("data:")) {
                val data = currentLine.removePrefix("data:").trim()
                if (data == "[DONE]") { sawTerminator = true; break }
                sawAnyData = true
                val content = extractContent(eventType, data)
                if (!content.isNullOrEmpty()) emit(content)
                if (isFinalChunk(eventType, data)) sawTerminator = true
            }
        }
        if (!sawTerminator && sawAnyData) {
            throw java.io.IOException("SSE stream ended without terminator — response likely truncated")
        }
    } finally {
        try { reader.close() } catch (_: Exception) {}
        body.close()
    }
}

/** Gemini SSE terminator: a candidate chunk with a non-null `finishReason`. Gemini doesn't
 *  send a [DONE] line — it just closes the connection after this chunk. */
private fun isGeminiFinalChunk(@Suppress("UNUSED_PARAMETER") eventType: String?, data: String): Boolean {
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

/** OpenAI Chat Completions SSE: data contains choices[0].delta.content */
private fun extractOpenAiContent(eventType: String?, data: String): String? {
    return try {
        val chunk = gson.fromJson(data, OpenAiStreamChunk::class.java)
        val delta = chunk?.choices?.firstOrNull()?.delta
        delta?.content?.takeIf { it.isNotEmpty() } ?: delta?.reasoning_content?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

/** OpenAI Responses API SSE: event=response.output_text.delta, data.delta contains text */
private fun extractResponsesApiContent(eventType: String?, data: String): String? {
    if (eventType != "response.output_text.delta") return null
    return try {
        gson.fromJson(data, com.google.gson.JsonObject::class.java)?.get("delta")?.asString?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

/** Anthropic SSE: event=content_block_delta, data.delta.text */
private fun extractClaudeContent(eventType: String?, data: String): String? {
    if (eventType == "message_stop") return null  // Signal end (handled by [DONE] or stream close)
    if (eventType != "content_block_delta") return null
    return try {
        gson.fromJson(data, ClaudeStreamEvent::class.java)?.delta?.text?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

/** Gemini SSE: data contains candidates[0].content.parts[0].text */
private fun extractGeminiContent(eventType: String?, data: String): String? {
    return try {
        gson.fromJson(data, GeminiStreamChunk::class.java)?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.takeIf { it.isNotEmpty() }
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
        val response = withContext(Dispatchers.IO) { api.responsesStream(responsesUrl, "Bearer $apiKey", request) }
        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseSseStream(body, ::extractResponsesApiContent).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else throw Exception("API error: ${response.code()} ${response.message()}")
    } else {
        val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
        val chatUrl = buildChatUrl(baseUrl, service.chatPath, service.knownEndpointPaths())
        val openAiMessages = messages.map { it.toOpenAiMessage() }
        val request = OpenAiRequest(
            model = model, messages = openAiMessages, stream = true,
            max_tokens = params.maxTokens, temperature = params.temperature,
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
        val response = withContext(Dispatchers.IO) { api.chatStream(chatUrl, "Bearer $apiKey", request) }
        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseSseStream(body, ::extractOpenAiContent).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else throw Exception("API error: ${response.code()} ${response.message()}")
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
    val response = withContext(Dispatchers.IO) { api.createMessageStream(apiKey, request = request) }
    if (response.isSuccessful) {
        response.body()?.let { body ->
            parseSseStream(body, ::extractClaudeContent).collect { emit(it) }
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
            search = if (params.searchEnabled) true else null,
            thinkingConfig = geminiThinkingConfigField(service, model, params.reasoningEffort)
        ),
        systemInstruction = systemInstruction,
        tools = if (params.webSearchTool) geminiWebSearchTool() else null
    )
    val response = withContext(Dispatchers.IO) { api.streamGenerateContent(model, apiKey, request = request) }
    if (response.isSuccessful) {
        response.body()?.let { body ->
            parseSseStream(body, ::extractGeminiContent, ::isGeminiFinalChunk).collect { emit(it) }
        } ?: throw Exception("Empty response body")
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}
