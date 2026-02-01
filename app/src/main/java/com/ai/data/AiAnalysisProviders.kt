package com.ai.data

import com.ai.ui.AiAgentParameters

internal suspend fun AiAnalysisRepository.analyzeWithChatGpt(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    return if (usesResponsesApi(model)) {
        analyzeWithChatGptResponsesApi(apiKey, prompt, model, params)
    } else {
        analyzeWithChatGptChatCompletions(apiKey, prompt, model, params)
    }
}

/**
 * Use the Chat Completions API for older models (gpt-4o, gpt-4, gpt-3.5, etc.)
 */
internal suspend fun AiAnalysisRepository.analyzeWithChatGptChatCompletions(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
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
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = params?.seed,
        response_format = if (params?.responseFormatJson == true) OpenAiResponseFormat(type = "json_object") else null,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = openAiApi.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )

    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        // Try multiple parsing strategies for content extraction
        val content = body?.choices?.let { choices ->
            // Strategy 1: Get content from first choice's message
            choices.firstOrNull()?.message?.content
            // Strategy 2: Get reasoning_content from first choice (DeepSeek)
                ?: choices.firstOrNull()?.message?.reasoning_content
            // Strategy 3: Try any choice with non-null content
                ?: choices.firstNotNullOfOrNull { it.message.content }
            // Strategy 4: Try any choice with reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message?.reasoning_content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: it.input_tokens ?: 0,
                outputTokens = it.completion_tokens ?: it.output_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.OPENAI, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.OPENAI, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.OPENAI, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

/**
 * Use the Responses API for newer models (gpt-5.x, o3, o4, etc.)
 */
internal suspend fun AiAnalysisRepository.analyzeWithChatGptResponsesApi(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val request = OpenAiResponsesRequest(
        model = model,
        input = prompt,
        instructions = params?.systemPrompt?.takeIf { it.isNotBlank() }
    )
    val response = openAiApi.createResponse(
        authorization = "Bearer $apiKey",
        request = request
    )

    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        // Try multiple parsing strategies for content extraction
        val content = body?.output?.let { outputs ->
            // Strategy 1: Look for output_text type in first output's content
            outputs.firstOrNull()?.content?.firstOrNull { it.type == "output_text" }?.text
            // Strategy 2: Look for text type in first output's content
                ?: outputs.firstOrNull()?.content?.firstOrNull { it.type == "text" }?.text
            // Strategy 3: Get first non-null text from first output's content
                ?: outputs.firstOrNull()?.content?.firstNotNullOfOrNull { it.text }
            // Strategy 4: Look for message type output with content
                ?: outputs.firstOrNull { it.type == "message" }?.content?.firstNotNullOfOrNull { it.text }
            // Strategy 5: Try any output's content
                ?: outputs.flatMap { it.content ?: emptyList() }.firstNotNullOfOrNull { it.text }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        // Responses API uses input_tokens/output_tokens (not prompt_tokens/completion_tokens)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.input_tokens ?: it.prompt_tokens ?: 0,
                outputTokens = it.output_tokens ?: it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.OPENAI, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (output: ${body?.output})"
            AiAnalysisResponse(AiService.OPENAI, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.OPENAI, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithClaude(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
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
        // Try multiple parsing strategies for content extraction
        val content = body?.content?.let { contentBlocks ->
            // Strategy 1: Get text from first "text" type block
            contentBlocks.firstOrNull { it.type == "text" }?.text
            // Strategy 2: Get any non-null text from content blocks
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
            AiAnalysisResponse(AiService.ANTHROPIC, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (blocks: ${body?.content?.size ?: 0})"
            AiAnalysisResponse(AiService.ANTHROPIC, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.ANTHROPIC, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithGemini(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
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
            AiAnalysisResponse(AiService.GOOGLE, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (candidates: ${body?.candidates?.size ?: 0})"
            AiAnalysisResponse(AiService.GOOGLE, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        val errorBody = response.errorBody()?.string()
        android.util.Log.e("GeminiAPI", "Error body: $errorBody")
        AiAnalysisResponse(AiService.GOOGLE, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

/**
 * Unified analysis method for all 28 OpenAI-compatible providers.
 * Handles provider-specific request fields and response extraction.
 */
internal suspend fun AiAnalysisRepository.analyzeWithOpenAiCompatible(
    service: AiService,
    apiKey: String,
    prompt: String,
    model: String,
    params: AiAgentParameters? = null,
    baseUrl: String = service.baseUrl
): AiAnalysisResponse {
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

