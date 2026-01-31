package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

// ========== Streaming Chat Methods ==========

/**
 * Send a chat message with streaming response.
 * Returns a Flow that emits content chunks as they arrive.
 * @param baseUrl Optional custom API endpoint URL (uses provider default if null)
 */
fun AiAnalysisRepository.sendChatMessageStream(
    service: AiService,
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters,
    baseUrl: String? = null
): Flow<String> = flow {
    // Use custom URL or fall back to provider default
    val effectiveUrl = baseUrl ?: service.baseUrl
    when (service) {
        AiService.OPENAI -> streamChatOpenAi(apiKey, model, messages, params, effectiveUrl).collect { emit(it) }
        AiService.ANTHROPIC -> streamChatClaude(apiKey, model, messages, params, effectiveUrl).collect { emit(it) }
        AiService.GOOGLE -> streamChatGemini(apiKey, model, messages, params, effectiveUrl).collect { emit(it) }
        else -> streamChatOpenAiCompatible(service, apiKey, model, messages, params, effectiveUrl).collect { emit(it) }
    }
}

/**
 * Parse SSE (Server-Sent Events) stream for OpenAI-compatible format.
 * Format: data: {"choices":[{"delta":{"content":"..."}}]}
 */
internal fun AiAnalysisRepository.parseOpenAiSseStream(responseBody: ResponseBody): Flow<String> = flow {
    val reader = responseBody.charStream().buffered()
    try {
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Skip empty lines and comments
            if (currentLine.isBlank() || currentLine.startsWith(":")) continue

            // Parse SSE data lines
            if (currentLine.startsWith("data: ")) {
                val data = currentLine.removePrefix("data: ").trim()

                // Check for stream termination
                if (data == "[DONE]") break

                try {
                    val chunk = gson.fromJson(data, OpenAiStreamChunk::class.java)
                    val delta = chunk?.choices?.firstOrNull()?.delta

                    // Emit content or reasoning_content (for DeepSeek)
                    delta?.content?.let { if (it.isNotEmpty()) emit(it) }
                    delta?.reasoning_content?.let { if (it.isNotEmpty()) emit(it) }
                } catch (e: Exception) {
                    android.util.Log.w("StreamParse", "Failed to parse chunk: $data")
                }
            }
        }
    } finally {
        reader.close()
        responseBody.close()
    }
}

/**
 * Parse SSE stream for OpenAI Responses API format (GPT-5.x, o3, o4).
 * Events: response.output_text.delta with delta containing text
 */
internal fun AiAnalysisRepository.parseOpenAiResponsesSseStream(responseBody: ResponseBody): Flow<String> = flow {
    val reader = responseBody.charStream().buffered()
    try {
        var line: String?
        var eventType: String? = null

        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Skip empty lines
            if (currentLine.isBlank()) {
                eventType = null
                continue
            }

            // Parse event type
            if (currentLine.startsWith("event: ")) {
                eventType = currentLine.removePrefix("event: ").trim()
                continue
            }

            // Parse data
            if (currentLine.startsWith("data: ")) {
                val data = currentLine.removePrefix("data: ").trim()

                // Check for stream termination
                if (data == "[DONE]") break

                // Only process text delta events
                if (eventType == "response.output_text.delta") {
                    try {
                        val jsonObject = gson.fromJson(data, com.google.gson.JsonObject::class.java)
                        val delta = jsonObject?.get("delta")?.asString
                        if (!delta.isNullOrEmpty()) {
                            emit(delta)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("StreamParse", "Failed to parse Responses API chunk: $data")
                    }
                }
            }
        }
    } finally {
        reader.close()
        responseBody.close()
    }
}

/**
 * Parse SSE stream for Anthropic Claude format.
 * Events: content_block_delta with delta.text
 */
internal fun AiAnalysisRepository.parseClaudeSseStream(responseBody: ResponseBody): Flow<String> = flow {
    val reader = responseBody.charStream().buffered()
    try {
        var line: String?
        var eventType: String? = null

        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Skip empty lines
            if (currentLine.isBlank()) {
                eventType = null
                continue
            }

            // Parse event type
            if (currentLine.startsWith("event: ")) {
                eventType = currentLine.removePrefix("event: ").trim()
                continue
            }

            // Parse data lines
            if (currentLine.startsWith("data: ")) {
                val data = currentLine.removePrefix("data: ").trim()

                // Only process content_block_delta events
                if (eventType == "content_block_delta") {
                    try {
                        val event = gson.fromJson(data, ClaudeStreamEvent::class.java)
                        event.delta?.text?.let { if (it.isNotEmpty()) emit(it) }
                    } catch (e: Exception) {
                        android.util.Log.w("StreamParse", "Failed to parse Claude chunk: $data")
                    }
                }

                // Check for message_stop
                if (eventType == "message_stop") break
            }
        }
    } finally {
        reader.close()
        responseBody.close()
    }
}

/**
 * Parse SSE stream for Google Gemini format.
 * Format: data: {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}
 */
internal fun AiAnalysisRepository.parseGeminiSseStream(responseBody: ResponseBody): Flow<String> = flow {
    val reader = responseBody.charStream().buffered()
    try {
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Skip empty lines
            if (currentLine.isBlank()) continue

            // Parse data lines
            if (currentLine.startsWith("data: ")) {
                val data = currentLine.removePrefix("data: ").trim()

                try {
                    val chunk = gson.fromJson(data, GeminiStreamChunk::class.java)
                    val text = chunk?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    text?.let { if (it.isNotEmpty()) emit(it) }
                } catch (e: Exception) {
                    android.util.Log.w("StreamParse", "Failed to parse Gemini chunk: $data")
                }
            }
        }
    } finally {
        reader.close()
        responseBody.close()
    }
}

// ========== Provider-Specific Streaming Methods ==========

internal fun AiAnalysisRepository.streamChatOpenAi(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters,
    baseUrl: String
): Flow<String> = flow {
    // Use custom URL if different from default
    val api = if (baseUrl != AiService.OPENAI.baseUrl) {
        AiApiFactory.createOpenAiStreamApiWithBaseUrl(baseUrl)
    } else {
        openAiStreamApi
    }

    // Check if model requires Responses API (gpt-5.x, o3, o4)
    if (usesResponsesApi(model)) {
        // Use Responses API for newer models
        val inputMessages = messages.map { msg ->
            OpenAiResponsesInputMessage(role = msg.role, content = msg.content)
        }
        val systemPrompt = messages.find { it.role == "system" }?.content

        val request = OpenAiResponsesStreamRequest(
            model = model,
            input = inputMessages.filter { it.role != "system" },
            instructions = systemPrompt,
            stream = true
        )

        val response = withContext(Dispatchers.IO) {
            api.createResponseStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiResponsesSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    } else {
        // Use Chat Completions API for older models
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            api.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }
}

internal fun AiAnalysisRepository.streamChatClaude(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters,
    baseUrl: String
): Flow<String> = flow {
    val claudeMessages = messages
        .filter { it.role != "system" }
        .map { msg -> ClaudeMessage(role = msg.role, content = msg.content) }
    val systemPrompt = messages.find { it.role == "system" }?.content

    val request = ClaudeStreamRequest(
        model = model,
        messages = claudeMessages,
        stream = true,
        max_tokens = params.maxTokens ?: 4096,
        temperature = params.temperature,
        top_p = params.topP,
        top_k = params.topK,
        system = systemPrompt
    )

    // Use custom URL if different from default
    val api = if (baseUrl != AiService.ANTHROPIC.baseUrl) {
        AiApiFactory.createClaudeStreamApiWithBaseUrl(baseUrl)
    } else {
        claudeStreamApi
    }

    val response = withContext(Dispatchers.IO) {
        api.createMessageStream(apiKey, request = request)
    }

    if (response.isSuccessful) {
        response.body()?.let { body ->
            parseClaudeSseStream(body).collect { emit(it) }
        } ?: throw Exception("Empty response body")
    } else {
        throw Exception("API error: ${response.code()} ${response.message()}")
    }
}

internal fun AiAnalysisRepository.streamChatGemini(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters,
    baseUrl: String
): Flow<String> = flow {
    val contents = messages
        .filter { it.role != "system" }
        .map { msg ->
            GeminiContent(
                parts = listOf(GeminiPart(text = msg.content)),
                role = if (msg.role == "user") "user" else "model"
            )
        }
    val systemInstruction = messages.find { it.role == "system" }?.let {
        GeminiContent(parts = listOf(GeminiPart(text = it.content)))
    }

    val request = GeminiRequest(
        contents = contents,
        generationConfig = GeminiGenerationConfig(
            temperature = params.temperature,
            maxOutputTokens = params.maxTokens,
            topP = params.topP,
            topK = params.topK,
            search = if (params.searchEnabled) true else null
        ),
        systemInstruction = systemInstruction
    )

    // Use custom URL if different from default
    val api = if (baseUrl != AiService.GOOGLE.baseUrl) {
        AiApiFactory.createGeminiStreamApiWithBaseUrl(baseUrl)
    } else {
        geminiStreamApi
    }

    val response = withContext(Dispatchers.IO) {
        api.streamGenerateContent(model, apiKey, request = request)
    }

    if (response.isSuccessful) {
        response.body()?.let { body ->
            parseGeminiSseStream(body).collect { emit(it) }
        } ?: throw Exception("Empty response body")
    } else {
        throw Exception("API error: ${response.code()} ${response.message()}")
    }
}

/**
 * Unified streaming method for all 28 OpenAI-compatible providers.
 * Uses OpenAiCompatibleStreamApi with @Url for dynamic chat paths.
 */
internal fun AiAnalysisRepository.streamChatOpenAiCompatible(
    service: AiService,
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters,
    baseUrl: String
): Flow<String> = flow {
    val api = AiApiFactory.createOpenAiCompatibleStreamApi(baseUrl)
    val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    val chatUrl = normalizedBase + service.chatPath

    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiStreamRequest(
        model = model,
        messages = openAiMessages,
        stream = true,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )

    val response = withContext(Dispatchers.IO) {
        api.createChatCompletionStream(chatUrl, "Bearer $apiKey", request)
    }

    if (response.isSuccessful) {
        response.body()?.let { body ->
            parseOpenAiSseStream(body).collect { emit(it) }
        } ?: throw Exception("Empty response body")
    } else {
        throw Exception("API error: ${response.code()} ${response.message()}")
    }
}

