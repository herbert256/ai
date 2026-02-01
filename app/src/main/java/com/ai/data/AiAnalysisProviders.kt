package com.ai.data

import com.ai.ui.AiAgentParameters

/**
 * Unified analysis method for OpenAI-format providers that support the Responses API.
 * For providers with apiFormat == OPENAI_COMPATIBLE, the analyzeWithOpenAiCompatible method
 * handles most cases. This handles the OpenAI-specific Responses API for newer models.
 */
internal suspend fun AiAnalysisRepository.analyzeWithChatGptResponsesApi(service: AiService, apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createOpenAiApiWithBaseUrl(service.baseUrl)
    val request = OpenAiResponsesRequest(
        model = model,
        input = prompt,
        instructions = params?.systemPrompt?.takeIf { it.isNotBlank() }
    )
    val response = api.createResponse(
        authorization = "Bearer $apiKey",
        request = request
    )

    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.output?.let { outputs ->
            outputs.firstOrNull()?.content?.firstOrNull { it.type == "output_text" }?.text
                ?: outputs.firstOrNull()?.content?.firstOrNull { it.type == "text" }?.text
                ?: outputs.firstOrNull()?.content?.firstNotNullOfOrNull { it.text }
                ?: outputs.firstOrNull { it.type == "message" }?.content?.firstNotNullOfOrNull { it.text }
                ?: outputs.flatMap { it.content ?: emptyList() }.firstNotNullOfOrNull { it.text }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.input_tokens ?: it.prompt_tokens ?: 0,
                outputTokens = it.output_tokens ?: it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(service, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (output: ${body?.output})"
            AiAnalysisResponse(service, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(service, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithClaude(service: AiService, apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val claudeApi = AiApiFactory.createClaudeApiWithBaseUrl(service.baseUrl)
    val request = ClaudeRequest(
        model = model,
        messages = listOf(ClaudeMessage(role = "user", content = prompt)),
        max_tokens = params?.maxTokens ?: 1024,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        system = params?.systemPrompt?.takeIf { it.isNotBlank() },
        stop_sequences = params?.stopSequences?.takeIf { it.isNotEmpty() },
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = claudeApi.createMessage(apiKey = apiKey, request = request)

    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.content?.let { contentBlocks ->
            contentBlocks.firstOrNull { it.type == "text" }?.text
                ?: contentBlocks.firstNotNullOfOrNull { it.text }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.input_tokens ?: 0,
                outputTokens = it.output_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(service, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (blocks: ${body?.content?.size ?: 0})"
            AiAnalysisResponse(service, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(service, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithGemini(service: AiService, apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    // Build generation config with all parameters
    val generationConfig = if (params != null) {
        GeminiGenerationConfig(
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            maxOutputTokens = params.maxTokens,
            stopSequences = params.stopSequences?.takeIf { it.isNotEmpty() },
            frequencyPenalty = params.frequencyPenalty,
            presencePenalty = params.presencePenalty,
            seed = params.seed,
            search = if (params.searchEnabled) true else null
        )
    } else null

    // System instruction for Gemini
    val systemInstruction = params?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
        GeminiContent(parts = listOf(GeminiPart(text = it)))
    }

    val request = GeminiRequest(
        contents = listOf(
            GeminiContent(parts = listOf(GeminiPart(text = prompt)))
        ),
        generationConfig = generationConfig,
        systemInstruction = systemInstruction
    )

    val geminiApi = AiApiFactory.createGeminiApiWithBaseUrl(service.baseUrl)
    val response = geminiApi.generateContent(model = model, apiKey = apiKey, request = request)

    android.util.Log.d("GeminiAPI", "Response code: ${response.code()}, message: ${response.message()}")

    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        // Try multiple parsing strategies for content extraction
        val content = body?.candidates?.let { candidates ->
            // Strategy 1: Get text from first candidate's first part
            candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            // Strategy 2: Get any non-null text from first candidate's parts
                ?: candidates.firstOrNull()?.content?.parts?.firstNotNullOfOrNull { it.text }
            // Strategy 3: Try any candidate with non-null content
                ?: candidates.flatMap { it.content?.parts ?: emptyList() }.firstNotNullOfOrNull { it.text }
        }
        val rawUsageJson = formatUsageJson(body?.usageMetadata)
        val usage = body?.usageMetadata?.let {
            TokenUsage(
                inputTokens = it.promptTokenCount ?: 0,
                outputTokens = it.candidatesTokenCount ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(service, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (candidates: ${body?.candidates?.size ?: 0})"
            AiAnalysisResponse(service, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        val errorBody = response.errorBody()?.string()
        android.util.Log.e("GeminiAPI", "Error body: $errorBody")
        AiAnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

/**
 * Unified analysis method for all OpenAI-compatible providers.
 * Handles provider-specific request fields and response extraction.
 * Also handles the Responses API for OpenAI newer models (gpt-5.x, o3, o4).
 */
internal suspend fun AiAnalysisRepository.analyzeWithOpenAiCompatible(
    service: AiService,
    apiKey: String,
    prompt: String,
    model: String,
    params: AiAgentParameters? = null,
    baseUrl: String = service.baseUrl
): AiAnalysisResponse {
    // Check if this model uses the Responses API (OpenAI gpt-5.x, o3, o4)
    if (usesResponsesApi(model)) {
        return analyzeWithChatGptResponsesApi(service, apiKey, prompt, model, params)
    }

    val api = AiApiFactory.createOpenAiCompatibleApi(baseUrl)
    val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    val chatUrl = normalizedBase + service.chatPath

    // Build messages with optional system prompt
    val messages = buildList {
        params?.systemPrompt?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                add(OpenAiMessage(role = "system", content = systemPrompt))
            }
        }
        add(OpenAiMessage(role = "user", content = prompt))
    }

    val request = OpenAiRequest(
        model = model,
        messages = messages,
        max_tokens = params?.maxTokens,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = if (service.seedFieldName == "seed") params?.seed else null,
        random_seed = if (service.seedFieldName == "random_seed") params?.seed else null,
        response_format = if (params?.responseFormatJson == true) OpenAiResponseFormat(type = "json_object") else null,
        return_citations = if (service.supportsCitations) params?.returnCitations else null,
        search_recency_filter = if (service.supportsSearchRecency) params?.searchRecency else null,
        search = if (params?.searchEnabled == true) true else null
    )

    val response = api.createChatCompletion(
        url = chatUrl,
        authorization = "Bearer $apiKey",
        request = request
    )

    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        // Try multiple parsing strategies for content extraction
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
                ?: choices.firstNotNullOfOrNull { it.message?.reasoning_content }
        }
        val citations = body?.citations
        val searchResults = body?.search_results
        val relatedQuestions = body?.related_questions
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: it.input_tokens ?: 0,
                outputTokens = it.completion_tokens ?: it.output_tokens ?: 0,
                apiCost = extractApiCost(it, if (service.extractApiCost) service else null)
            )
        }
        if (!content.isNullOrBlank()) {
            AiAnalysisResponse(service, content, null, usage, citations = citations, searchResults = searchResults, relatedQuestions = relatedQuestions, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(service, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(service, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

