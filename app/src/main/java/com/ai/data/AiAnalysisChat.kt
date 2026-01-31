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
    when (service) {
        AiService.OPENAI -> sendChatMessageChatGpt(apiKey, model, messages, params)
        AiService.ANTHROPIC -> sendChatMessageClaude(apiKey, model, messages, params)
        AiService.GOOGLE -> sendChatMessageGemini(apiKey, model, messages, params)
        AiService.XAI -> sendChatMessageGrok(apiKey, model, messages, params)
        AiService.GROQ -> sendChatMessageGroq(apiKey, model, messages, params)
        AiService.DEEPSEEK -> sendChatMessageDeepSeek(apiKey, model, messages, params)
        AiService.MISTRAL -> sendChatMessageMistral(apiKey, model, messages, params)
        AiService.PERPLEXITY -> sendChatMessagePerplexity(apiKey, model, messages, params)
        AiService.TOGETHER -> sendChatMessageTogether(apiKey, model, messages, params)
        AiService.OPENROUTER -> sendChatMessageOpenRouter(apiKey, model, messages, params)
        AiService.SILICONFLOW -> sendChatMessageSiliconFlow(apiKey, model, messages, params)
        AiService.ZAI -> sendChatMessageZai(apiKey, model, messages, params)
        AiService.MOONSHOT -> sendChatMessageMoonshot(apiKey, model, messages, params)
        AiService.COHERE -> sendChatMessageCohere(apiKey, model, messages, params)
        AiService.AI21 -> sendChatMessageAi21(apiKey, model, messages, params)
        AiService.DASHSCOPE -> sendChatMessageDashScope(apiKey, model, messages, params)
        AiService.FIREWORKS -> sendChatMessageFireworks(apiKey, model, messages, params)
        AiService.CEREBRAS -> sendChatMessageCerebras(apiKey, model, messages, params)
        AiService.SAMBANOVA -> sendChatMessageSambaNova(apiKey, model, messages, params)
        AiService.BAICHUAN -> sendChatMessageBaichuan(apiKey, model, messages, params)
        AiService.STEPFUN -> sendChatMessageStepFun(apiKey, model, messages, params)
        AiService.MINIMAX -> sendChatMessageMiniMax(apiKey, model, messages, params)
        AiService.NVIDIA -> sendChatMessageNvidia(apiKey, model, messages, params)
        AiService.REPLICATE -> sendChatMessageReplicate(apiKey, model, messages, params)
        AiService.HUGGINGFACE -> sendChatMessageHuggingFaceInference(apiKey, model, messages, params)
        AiService.LAMBDA -> sendChatMessageLambda(apiKey, model, messages, params)
        AiService.LEPTON -> sendChatMessageLepton(apiKey, model, messages, params)
        AiService.YI -> sendChatMessageYi(apiKey, model, messages, params)
        AiService.DOUBAO -> sendChatMessageDoubao(apiKey, model, messages, params)
        AiService.REKA -> sendChatMessageReka(apiKey, model, messages, params)
        AiService.WRITER -> sendChatMessageWriter(apiKey, model, messages, params)
    }
}

internal fun AiAnalysisRepository.convertToOpenAiMessages(messages: List<com.ai.ui.ChatMessage>): List<OpenAiMessage> {
    return messages.map { msg -> OpenAiMessage(role = msg.role, content = msg.content) }
}

internal suspend fun AiAnalysisRepository.sendChatMessageChatGpt(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = openAiApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageGrok(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = grokApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageGroq(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = groqApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageDeepSeek(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = deepSeekApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageMistral(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        search = if (params.searchEnabled) true else null
    )
    val response = mistralApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessagePerplexity(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = perplexityApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageTogether(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
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
    val response = togetherApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageOpenRouter(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
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
    val response = openRouterApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageSiliconFlow(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
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
    val response = siliconFlowApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageZai(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = zaiApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageMoonshot(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = moonshotApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageCohere(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = cohereApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageAi21(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = ai21Api.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageDashScope(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = dashScopeApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageFireworks(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = fireworksApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageCerebras(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = cerebrasApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageSambaNova(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = sambaNovaApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageBaichuan(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = baichuanApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageStepFun(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = stepFunApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageMiniMax(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = miniMaxApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageNvidia(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )
    val response = nvidiaApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageReplicate(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )
    val response = replicateApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageHuggingFaceInference(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )
    val response = huggingFaceInferenceApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageLambda(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )
    val response = lambdaApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageLepton(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )
    val response = leptonApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageYi(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )
    val response = yiApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageDoubao(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )
    val response = doubaoApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageReka(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )
    val response = rekaApi.createChatCompletion(
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

internal suspend fun AiAnalysisRepository.sendChatMessageWriter(
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    val openAiMessages = convertToOpenAiMessages(messages)
    val request = OpenAiRequest(
        model = model,
        messages = openAiMessages,
        max_tokens = params.maxTokens,
        temperature = params.temperature,
        top_p = params.topP,
        frequency_penalty = params.frequencyPenalty,
        presence_penalty = params.presencePenalty
    )
    val response = writerApi.createChatCompletion(
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
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    // Filter out system message (passed separately) and convert to Claude format
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
    apiKey: String,
    model: String,
    messages: List<com.ai.ui.ChatMessage>,
    params: com.ai.ui.ChatParameters
): String {
    // Convert messages to Gemini format (alternating user/model roles)
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
