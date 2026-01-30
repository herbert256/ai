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

internal suspend fun AiAnalysisRepository.analyzeWithGrok(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    // Build messages with optional system prompt
    val messages = buildList {
        params?.systemPrompt?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                add(OpenAiMessage(role = "system", content = systemPrompt))
            }
        }
        add(OpenAiMessage(role = "user", content = prompt))
    }

    val searchValue = if (params?.searchEnabled == true) true else null
    val request = GrokRequest(
        model = model,
        messages = messages,
        max_tokens = params?.maxTokens,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = params?.seed,
        search = searchValue
    )
    val response = grokApi.createChatCompletion(
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
        }
        val searchResults = body?.search_results  // Grok may return search results
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.XAI, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.XAI, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.XAI, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithGroq(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    // Build messages with optional system prompt
    val messages = buildList {
        params?.systemPrompt?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                add(OpenAiMessage(role = "system", content = systemPrompt))
            }
        }
        add(OpenAiMessage(role = "user", content = prompt))
    }

    val request = GroqRequest(
        model = model,
        messages = messages,
        max_tokens = params?.maxTokens,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = groqApi.createChatCompletion(
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
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.GROQ, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.GROQ, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.GROQ, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithDeepSeek(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    // Build messages with optional system prompt
    val messages = buildList {
        params?.systemPrompt?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                add(OpenAiMessage(role = "system", content = systemPrompt))
            }
        }
        add(OpenAiMessage(role = "user", content = prompt))
    }

    val request = DeepSeekRequest(
        model = model,
        messages = messages,
        max_tokens = params?.maxTokens,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = deepSeekApi.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )

    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        // Try multiple parsing strategies for content extraction
        // DeepSeek reasoning models may return content in reasoning_content field
        val content = body?.choices?.let { choices ->
            val message = choices.firstOrNull()?.message
            message?.content
                ?: message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
                ?: choices.firstNotNullOfOrNull { it.message?.reasoning_content }
        }
        val searchResults = body?.search_results
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (!content.isNullOrBlank()) {
            AiAnalysisResponse(AiService.DEEPSEEK, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.DEEPSEEK, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.DEEPSEEK, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithMistral(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    // Build messages with optional system prompt
    val messages = buildList {
        params?.systemPrompt?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                add(OpenAiMessage(role = "system", content = systemPrompt))
            }
        }
        add(OpenAiMessage(role = "user", content = prompt))
    }

    val request = MistralRequest(
        model = model,
        messages = messages,
        max_tokens = params?.maxTokens,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        random_seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = mistralApi.createChatCompletion(
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
        }
        val searchResults = body?.search_results
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.MISTRAL, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.MISTRAL, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.MISTRAL, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithPerplexity(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    // Build messages with optional system prompt
    val messages = buildList {
        params?.systemPrompt?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                add(OpenAiMessage(role = "system", content = systemPrompt))
            }
        }
        add(OpenAiMessage(role = "user", content = prompt))
    }

    val request = PerplexityRequest(
        model = model,
        messages = messages,
        max_tokens = params?.maxTokens,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = params?.seed,
        return_citations = params?.returnCitations,
        search_recency_filter = params?.searchRecency,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = perplexityApi.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )

    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val citations = body?.citations  // Extract citations from Perplexity response
        val searchResults = body?.search_results  // Extract search results
        val relatedQuestions = body?.related_questions  // Extract related questions
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.PERPLEXITY, content, null, usage, citations = citations, searchResults = searchResults, relatedQuestions = relatedQuestions, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            android.util.Log.e("PerplexityAPI", "No content found, error: $errorMsg")
            AiAnalysisResponse(AiService.PERPLEXITY, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        android.util.Log.e("PerplexityAPI", "API error: ${response.code()} ${response.message()}")
        AiAnalysisResponse(AiService.PERPLEXITY, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithTogether(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    // Build messages with optional system prompt
    val messages = buildList {
        params?.systemPrompt?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                add(OpenAiMessage(role = "system", content = systemPrompt))
            }
        }
        add(OpenAiMessage(role = "user", content = prompt))
    }

    val request = TogetherRequest(
        model = model,
        messages = messages,
        max_tokens = params?.maxTokens,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = togetherApi.createChatCompletion(
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
        }
        val searchResults = body?.search_results
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.TOGETHER, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.TOGETHER, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.TOGETHER, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithOpenRouter(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    // Build messages with optional system prompt
    val messages = buildList {
        params?.systemPrompt?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                add(OpenAiMessage(role = "system", content = systemPrompt))
            }
        }
        add(OpenAiMessage(role = "user", content = prompt))
    }

    val request = OpenRouterRequest(
        model = model,
        messages = messages,
        max_tokens = params?.maxTokens,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = openRouterApi.createChatCompletion(
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
        }
        val searchResults = body?.search_results
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it, AiService.OPENROUTER)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.OPENROUTER, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.OPENROUTER, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.OPENROUTER, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithSiliconFlow(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    // Build messages with optional system prompt
    val messages = buildList {
        params?.systemPrompt?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                add(OpenAiMessage(role = "system", content = systemPrompt))
            }
        }
        add(OpenAiMessage(role = "user", content = prompt))
    }

    val request = SiliconFlowRequest(
        model = model,
        messages = messages,
        max_tokens = params?.maxTokens,
        temperature = params?.temperature,
        top_p = params?.topP,
        top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty,
        presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = siliconFlowApi.createChatCompletion(
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
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.SILICONFLOW, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.SILICONFLOW, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.SILICONFLOW, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithZai(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createZaiApi()

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
        search = if (params?.searchEnabled == true) true else null
    )

    val response = api.createChatCompletion(
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
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.ZAI, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.ZAI, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.ZAI, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithMoonshot(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createMoonshotApi()

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
        search = if (params?.searchEnabled == true) true else null
    )

    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )

    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.MOONSHOT, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.MOONSHOT, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.MOONSHOT, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithCohere(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createCohereApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.COHERE, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.COHERE, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.COHERE, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithAi21(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createAi21Api()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.AI21, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.AI21, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.AI21, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithDashScope(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createDashScopeApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.DASHSCOPE, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.DASHSCOPE, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.DASHSCOPE, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithFireworks(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createFireworksApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.FIREWORKS, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.FIREWORKS, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.FIREWORKS, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithCerebras(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createCerebrasApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.CEREBRAS, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.CEREBRAS, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.CEREBRAS, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithSambaNova(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createSambaNovaApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.SAMBANOVA, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.SAMBANOVA, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.SAMBANOVA, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithBaichuan(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createBaichuanApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.BAICHUAN, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.BAICHUAN, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.BAICHUAN, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithStepFun(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createStepFunApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.STEPFUN, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.STEPFUN, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.STEPFUN, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithMiniMax(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createMiniMaxApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.MINIMAX, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.MINIMAX, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.MINIMAX, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithNvidia(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createNvidiaApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.NVIDIA, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.NVIDIA, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.NVIDIA, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithReplicate(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createReplicateApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.REPLICATE, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.REPLICATE, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.REPLICATE, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithHuggingFaceInference(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createHuggingFaceInferenceApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.HUGGINGFACE, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.HUGGINGFACE, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.HUGGINGFACE, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithLambda(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createLambdaApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.LAMBDA, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.LAMBDA, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.LAMBDA, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithLepton(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createLeptonApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.LEPTON, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.LEPTON, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.LEPTON, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithYi(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createYiApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.YI, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.YI, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.YI, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithDoubao(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createDoubaoApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.DOUBAO, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.DOUBAO, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.DOUBAO, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithReka(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createRekaApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.REKA, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.REKA, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.REKA, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AiAnalysisRepository.analyzeWithWriter(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
    val api = AiApiFactory.createWriterApi()
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
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createChatCompletion(
        authorization = "Bearer $apiKey",
        request = request
    )
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.prompt_tokens ?: 0,
                outputTokens = it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) {
            AiAnalysisResponse(AiService.WRITER, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        } else {
            val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
            AiAnalysisResponse(AiService.WRITER, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        AiAnalysisResponse(AiService.WRITER, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

