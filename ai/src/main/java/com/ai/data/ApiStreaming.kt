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
    when (service.apiFormat) {
        ApiFormat.ANTHROPIC -> streamAnthropic(apiKey, model, messages, params, effectiveUrl).collect { emit(it) }
        ApiFormat.GOOGLE -> streamGemini(apiKey, model, messages, params, effectiveUrl).collect { emit(it) }
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
                if (eventType == "message_stop") sawTerminator = true
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
        val api = ApiFactory.createOpenAiApi(baseUrl)
        val inputMessages = messages.filter { it.role != "system" }.map { OpenAiResponsesInputMessage(it.role, it.content) }
        val systemPrompt = messages.find { it.role == "system" }?.content
        val request = OpenAiResponsesRequest(
            model = model, input = inputMessages, instructions = systemPrompt, stream = true
        )
        val response = withContext(Dispatchers.IO) { api.createResponseStream("Bearer $apiKey", request) }
        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseSseStream(body, ::extractResponsesApiContent).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else throw Exception("API error: ${response.code()} ${response.message()}")
    } else {
        val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
        val chatUrl = buildChatUrl(baseUrl, service.chatPath)
        val openAiMessages = messages.map { OpenAiMessage(it.role, it.content) }
        val request = OpenAiRequest(
            model = model, messages = openAiMessages, stream = true,
            max_tokens = params.maxTokens, temperature = params.temperature,
            top_p = params.topP, top_k = params.topK,
            frequency_penalty = params.frequencyPenalty, presence_penalty = params.presencePenalty,
            search = if (params.searchEnabled) true else null,
            return_citations = if (service.supportsCitations) params.returnCitations else null,
            search_recency_filter = if (service.supportsSearchRecency) params.searchRecency else null
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
    apiKey: String, model: String, messages: List<ChatMessage>,
    params: ChatParameters, baseUrl: String
): Flow<String> = flow {
    val api = ApiFactory.createClaudeApi(baseUrl)
    val claudeMessages = messages.filter { it.role != "system" }.map { ClaudeMessage(it.role, it.content) }
    val systemPrompt = messages.find { it.role == "system" }?.content
    val request = ClaudeRequest(
        model = model, messages = claudeMessages, stream = true,
        max_tokens = params.maxTokens ?: defaultClaudeMaxTokens(model),
        temperature = params.temperature, top_p = params.topP, top_k = params.topK,
        system = systemPrompt,
        frequency_penalty = params.frequencyPenalty, presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
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
    apiKey: String, model: String, messages: List<ChatMessage>,
    params: ChatParameters, baseUrl: String
): Flow<String> = flow {
    val api = ApiFactory.createGeminiApi(baseUrl)
    val contents = messages.filter { it.role != "system" }.map {
        GeminiContent(listOf(GeminiPart(it.content)), if (it.role == "user") "user" else "model")
    }
    val systemInstruction = messages.find { it.role == "system" }?.let { GeminiContent(listOf(GeminiPart(it.content))) }
    val request = GeminiRequest(contents, GeminiGenerationConfig(
        params.temperature, params.topP, params.topK, params.maxTokens,
        search = if (params.searchEnabled) true else null
    ), systemInstruction)
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
