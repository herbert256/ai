package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Send a chat message with conversation history.
 * Returns the assistant's response message.
 */
suspend fun AiAnalysisRepository.sendChatMessage(
    service: AiService,
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String = withContext(Dispatchers.IO) {
    when (service.apiFormat) {
        ApiFormat.ANTHROPIC -> sendChatMessageClaude(service, apiKey, model, messages, params)
        ApiFormat.GOOGLE -> sendChatMessageGemini(service, apiKey, model, messages, params)
        ApiFormat.OPENAI_COMPATIBLE -> sendChatMessageOpenAiCompatible(service, apiKey, model, messages, params)
    }
}

internal fun AiAnalysisRepository.convertToOpenAiMessages(messages: List<com.ai.ui.ChatMessage>): List<OpenAiMessage> {
    return messages.map { msg -> OpenAiMessage(role = msg.role, content = msg.content) }
}

/**
 * Unified chat method for all OpenAI-compatible providers.
 * Uses OpenAiCompatibleApi with @Url for dynamic chat paths.
 */
internal suspend fun AiAnalysisRepository.sendChatMessageOpenAiCompatible(
    service: AiService,
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val api = AiApiFactory.createOpenAiCompatibleApi(service.baseUrl)
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

internal suspend fun AiAnalysisRepository.sendChatMessageClaude(
    service: AiService,
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val claudeApi = AiApiFactory.createClaudeApiWithBaseUrl(service.baseUrl)
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

internal suspend fun AiAnalysisRepository.sendChatMessageGemini(
    service: AiService,
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val geminiApi = AiApiFactory.createGeminiApiWithBaseUrl(service.baseUrl)
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
