package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Send a chat message with conversation history.
 * Returns the assistant's response message.
 */
suspend fun AnalysisRepository.sendChatMessage(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<com.ai.data.ChatMessage>,
    params: com.ai.data.ChatParameters
): String = withContext(Dispatchers.IO) {
    when (service.apiFormat) {
        ApiFormat.ANTHROPIC -> sendChatMessageClaude(service, apiKey, model, messages, params)
        ApiFormat.GOOGLE -> sendChatMessageGemini(service, apiKey, model, messages, params)
        ApiFormat.OPENAI_COMPATIBLE -> sendChatMessageOpenAiCompatible(service, apiKey, model, messages, params)
    }
}

internal fun AnalysisRepository.convertToOpenAiMessages(messages: List<com.ai.data.ChatMessage>): List<OpenAiMessage> {
    return messages.map { msg -> OpenAiMessage(role = msg.role, content = msg.content) }
}

/**
 * Unified chat method for all OpenAI-compatible providers.
 * Uses OpenAiCompatibleApi with @Url for dynamic chat paths.
 */
internal suspend fun AnalysisRepository.sendChatMessageOpenAiCompatible(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<com.ai.data.ChatMessage>,
    params: com.ai.data.ChatParameters
): String {
    // Check if this model uses the Responses API (OpenAI gpt-5.x, o3, o4)
    if (usesResponsesApi(service, model)) {
        return sendChatMessageResponsesApi(service, apiKey, model, messages, params)
    }

    val api = ApiFactory.createOpenAiCompatibleApi(service.baseUrl)
    val normalizedBase = if (service.baseUrl.endsWith("/")) service.baseUrl else "${service.baseUrl}/"
    val chatUrl = normalizedBase + service.chatPath

    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        top_k = params.topK,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = api.createChatCompletion(
        url = chatUrl,
        authorization = "Bearer $apiKey",
        request = request
    )
    if (response.isSuccessful) {
        val content = response.body()?.choices?.firstOrNull()?.message?.content
        return content ?: throw Exception("No response content")
    } else {
        throw Exception("API error: ${response.code()} ${response.message()}")
    }
}

/**
 * Send a non-streaming chat message via OpenAI Responses API (gpt-5.x, o3, o4).
 */
internal suspend fun AnalysisRepository.sendChatMessageResponsesApi(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<com.ai.data.ChatMessage>,
    params: com.ai.data.ChatParameters
): String {
    val api = ApiFactory.createOpenAiApiWithBaseUrl(service.baseUrl)
    val systemPrompt = messages.find { it.role == "system" }?.content
    val inputMessages = messages
        .filter { it.role != "system" }
        .map { msg -> OpenAiResponsesInputMessage(role = msg.role, content = msg.content) }

    // For single user message, use simple input string; for multi-turn, send full input array.
    // This preserves conversation context for Responses API models.
    val request = if (inputMessages.size == 1 && inputMessages.first().role == "user") {
        OpenAiResponsesRequest(
            model = model,
            input = inputMessages.first().content,
            instructions = systemPrompt
        )
    } else {
        OpenAiResponsesRequest(
            model = model,
            input = inputMessages,
            instructions = systemPrompt
        )
    }

    val response = api.createResponse(
        authorization = "Bearer $apiKey",
        request = request
    )
    if (response.isSuccessful) {
        val body = response.body()
        val content = body?.output?.let { outputs ->
            outputs.firstOrNull()?.content?.firstOrNull { it.type == "output_text" }?.text
                ?: outputs.firstOrNull()?.content?.firstOrNull { it.type == "text" }?.text
                ?: outputs.firstOrNull()?.content?.firstNotNullOfOrNull { it.text }
                ?: outputs.firstOrNull { it.type == "message" }?.content?.firstNotNullOfOrNull { it.text }
                ?: outputs.flatMap { it.content ?: emptyList() }.firstNotNullOfOrNull { it.text }
        }
        return content ?: throw Exception("No response content")
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}

internal suspend fun AnalysisRepository.sendChatMessageClaude(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<com.ai.data.ChatMessage>,
    params: com.ai.data.ChatParameters
): String {
    val claudeApi = ApiFactory.createClaudeApiWithBaseUrl(service.baseUrl)
    val claudeMessages = messages
        .filter { it.role != "system" }
        .map { msg -> ClaudeMessage(role = msg.role, content = msg.content) }
    val systemPrompt = messages.find { it.role == "system" }?.content

    val request = ClaudeRequest(
        model = model,
        messages = claudeMessages,
        max_tokens = params.maxTokens ?: 4096,
        temperature = params.temperature,
        top_p = params.topP,
        top_k = params.topK,
        system = systemPrompt,
        search = if (params.searchEnabled) true else null
    )
    val response = claudeApi.createMessage(apiKey = apiKey, request = request)
    if (response.isSuccessful) {
        val content = response.body()?.content?.firstOrNull { it.type == "text" }?.text
        return content ?: throw Exception("No response content")
    } else {
        throw Exception("API error: ${response.code()} ${response.message()}")
    }
}

internal suspend fun AnalysisRepository.sendChatMessageGemini(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<com.ai.data.ChatMessage>,
    params: com.ai.data.ChatParameters
): String {
    val geminiApi = ApiFactory.createGeminiApiWithBaseUrl(service.baseUrl)
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
    val response = geminiApi.generateContent(model, apiKey, request)
    if (response.isSuccessful) {
        val content = response.body()?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        return content ?: throw Exception("No response content")
    } else {
        throw Exception("API error: ${response.code()} ${response.message()}")
    }
}
