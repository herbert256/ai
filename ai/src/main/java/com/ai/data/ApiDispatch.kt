package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

// ============================================================================
// Unified API dispatch — single when(apiFormat) for analyze, chat, fetchModels
// ============================================================================

/**
 * Analyze a prompt using the appropriate API format.
 */
suspend fun AnalysisRepository.analyze(
    service: AppService,
    apiKey: String,
    prompt: String,
    model: String,
    params: AgentParameters? = null,
    baseUrl: String = service.baseUrl
): AnalysisResponse = withContext(Dispatchers.IO) {
    when (service.apiFormat) {
        ApiFormat.ANTHROPIC -> analyzeAnthropic(service, apiKey, prompt, model, params)
        ApiFormat.GOOGLE -> analyzeGemini(service, apiKey, prompt, model, params)
        ApiFormat.OPENAI_COMPATIBLE -> analyzeOpenAi(service, apiKey, prompt, model, params, baseUrl)
    }
}

/**
 * Send a non-streaming chat message. Returns the assistant's response text.
 */
suspend fun AnalysisRepository.sendChat(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    params: ChatParameters,
    baseUrl: String = service.baseUrl
): String = withContext(Dispatchers.IO) {
    when (service.apiFormat) {
        ApiFormat.ANTHROPIC -> chatAnthropic(service, apiKey, model, messages, params)
        ApiFormat.GOOGLE -> chatGemini(service, apiKey, model, messages, params)
        ApiFormat.OPENAI_COMPATIBLE -> chatOpenAi(service, apiKey, model, messages, params, baseUrl)
    }
}

/**
 * Fetch available models from a provider.
 */
suspend fun AnalysisRepository.fetchModels(
    service: AppService,
    apiKey: String
): List<String> = withContext(Dispatchers.IO) {
    when (service.apiFormat) {
        ApiFormat.ANTHROPIC -> fetchModelsAnthropic(service, apiKey)
        ApiFormat.GOOGLE -> fetchModelsGemini(service, apiKey)
        ApiFormat.OPENAI_COMPATIBLE -> fetchModelsOpenAi(service, apiKey)
    }
}

// ============================================================================
// Analyze implementations
// ============================================================================

private suspend fun AnalysisRepository.analyzeOpenAi(
    service: AppService, apiKey: String, prompt: String, model: String,
    params: AgentParameters?, baseUrl: String
): AnalysisResponse {
    if (usesResponsesApi(service, model)) return analyzeResponsesApi(service, apiKey, prompt, model, params)

    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val chatUrl = normalizeUrl(baseUrl) + service.chatPath
    val messages = buildMessages(params?.systemPrompt, prompt)
    val request = buildOpenAiRequest(service, model, messages, params)
    val response = api.chat(chatUrl, "Bearer $apiKey", request)

    return parseOpenAiAnalysisResponse(service, response)
}

private suspend fun AnalysisRepository.analyzeResponsesApi(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?
): AnalysisResponse {
    val api = ApiFactory.createOpenAiApi(service.baseUrl)
    val request = OpenAiResponsesRequest(
        model = model,
        input = prompt,
        instructions = params?.systemPrompt?.takeIf { it.isNotBlank() }
    )
    val response = api.createResponse("Bearer $apiKey", request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = extractResponsesApiContent(body)
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(
                inputTokens = it.input_tokens ?: it.prompt_tokens ?: 0,
                outputTokens = it.output_tokens ?: it.completion_tokens ?: 0,
                apiCost = extractApiCost(it)
            )
        }
        if (content != null) AnalysisResponse(service, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

private suspend fun AnalysisRepository.analyzeAnthropic(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?
): AnalysisResponse {
    val api = ApiFactory.createClaudeApi(service.baseUrl)
    val request = ClaudeRequest(
        model = model,
        messages = listOf(ClaudeMessage("user", prompt)),
        max_tokens = params?.maxTokens ?: 4096,
        temperature = params?.temperature, top_p = params?.topP, top_k = params?.topK,
        system = params?.systemPrompt?.takeIf { it.isNotBlank() },
        stop_sequences = params?.stopSequences?.takeIf { it.isNotEmpty() },
        frequency_penalty = params?.frequencyPenalty, presence_penalty = params?.presencePenalty,
        seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null
    )
    val response = api.createMessage(apiKey, request = request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.content?.firstOrNull { it.type == "text" }?.text
            ?: body?.content?.firstNotNullOfOrNull { it.text }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let { TokenUsage(it.input_tokens ?: 0, it.output_tokens ?: 0, extractApiCost(it)) }
        if (content != null) AnalysisResponse(service, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

private suspend fun AnalysisRepository.analyzeGemini(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?
): AnalysisResponse {
    val genConfig = params?.let {
        GeminiGenerationConfig(it.temperature, it.topP, it.topK, it.maxTokens,
            it.stopSequences?.takeIf { s -> s.isNotEmpty() }, it.frequencyPenalty, it.presencePenalty, it.seed,
            if (it.searchEnabled) true else null)
    }
    val systemInstruction = params?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
        GeminiContent(listOf(GeminiPart(it)))
    }
    val request = GeminiRequest(listOf(GeminiContent(listOf(GeminiPart(prompt)))), genConfig, systemInstruction)
    val api = ApiFactory.createGeminiApi(service.baseUrl)
    val response = api.generateContent(model, apiKey, request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: body?.candidates?.flatMap { it.content?.parts ?: emptyList() }?.firstNotNullOfOrNull { it.text }
        val rawUsageJson = formatUsageJson(body?.usageMetadata)
        val usage = body?.usageMetadata?.let { TokenUsage(it.promptTokenCount ?: 0, it.candidatesTokenCount ?: 0, extractApiCost(it)) }
        if (content != null) AnalysisResponse(service, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

// ============================================================================
// Chat implementations
// ============================================================================

private suspend fun AnalysisRepository.chatOpenAi(
    service: AppService,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    params: ChatParameters,
    baseUrl: String
): String {
    if (usesResponsesApi(service, model)) return chatResponsesApi(service, apiKey, model, messages, params)

    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val chatUrl = normalizeUrl(baseUrl) + service.chatPath
    val openAiMessages = messages.map { OpenAiMessage(it.role, it.content) }
    val request = OpenAiRequest(
        model = model, messages = openAiMessages,
        max_tokens = params.maxTokens, temperature = params.temperature,
        top_p = params.topP, top_k = params.topK,
        frequency_penalty = params.frequencyPenalty, presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null
    )
    val response = api.chat(chatUrl, "Bearer $apiKey", request)
    if (response.isSuccessful) {
        return response.body()?.choices?.firstOrNull()?.message?.content ?: throw Exception("No response content")
    } else throw Exception("API error: ${response.code()} ${response.message()}")
}

private suspend fun AnalysisRepository.chatResponsesApi(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters
): String {
    val api = ApiFactory.createOpenAiApi(service.baseUrl)
    val systemPrompt = messages.find { it.role == "system" }?.content
    val inputMessages = messages.filter { it.role != "system" }.map { OpenAiResponsesInputMessage(it.role, it.content) }
    val request = if (inputMessages.size == 1 && inputMessages.first().role == "user") {
        OpenAiResponsesRequest(model = model, input = inputMessages.first().content, instructions = systemPrompt)
    } else {
        OpenAiResponsesRequest(model = model, input = inputMessages, instructions = systemPrompt)
    }
    val response = api.createResponse("Bearer $apiKey", request)
    if (response.isSuccessful) {
        return extractResponsesApiContent(response.body()) ?: throw Exception("No response content")
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}

private suspend fun AnalysisRepository.chatAnthropic(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters
): String {
    val api = ApiFactory.createClaudeApi(service.baseUrl)
    val claudeMessages = messages.filter { it.role != "system" }.map { ClaudeMessage(it.role, it.content) }
    val systemPrompt = messages.find { it.role == "system" }?.content
    val request = ClaudeRequest(
        model = model, messages = claudeMessages, max_tokens = params.maxTokens ?: 4096,
        temperature = params.temperature, top_p = params.topP, top_k = params.topK,
        system = systemPrompt, search = if (params.searchEnabled) true else null
    )
    val response = api.createMessage(apiKey, request = request)
    if (response.isSuccessful) {
        return response.body()?.content?.firstOrNull { it.type == "text" }?.text ?: throw Exception("No response content")
    } else throw Exception("API error: ${response.code()} ${response.message()}")
}

private suspend fun AnalysisRepository.chatGemini(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters
): String {
    val api = ApiFactory.createGeminiApi(service.baseUrl)
    val contents = messages.filter { it.role != "system" }.map {
        GeminiContent(listOf(GeminiPart(it.content)), if (it.role == "user") "user" else "model")
    }
    val systemInstruction = messages.find { it.role == "system" }?.let { GeminiContent(listOf(GeminiPart(it.content))) }
    val request = GeminiRequest(contents, GeminiGenerationConfig(
        params.temperature, params.topP, params.topK, params.maxTokens,
        search = if (params.searchEnabled) true else null
    ), systemInstruction)
    val response = api.generateContent(model, apiKey, request)
    if (response.isSuccessful) {
        return response.body()?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: throw Exception("No response content")
    } else throw Exception("API error: ${response.code()} ${response.message()}")
}

// ============================================================================
// Model fetching implementations
// ============================================================================

private suspend fun AnalysisRepository.fetchModelsOpenAi(service: AppService, apiKey: String): List<String> {
    if (service.hardcodedModels != null && service.modelsPath == null) return service.hardcodedModels
    val api = ApiFactory.createOpenAiCompatibleApi(service.baseUrl)
    val modelsUrl = normalizeUrl(service.baseUrl) + (service.modelsPath ?: "v1/models")
    val modelIds = if (service.modelListFormat == "array") {
        val response = api.listModelsArray(modelsUrl, "Bearer $apiKey")
        if (response.isSuccessful) response.body()?.mapNotNull { it.id } ?: emptyList() else emptyList()
    } else {
        val response = api.listModels(modelsUrl, "Bearer $apiKey")
        if (response.isSuccessful) response.body()?.data?.mapNotNull { it.id } ?: emptyList() else emptyList()
    }
    val filtered = service.modelFilterRegex?.let { regex -> modelIds.filter { regex.containsMatchIn(it) } } ?: modelIds
    return filtered.sorted()
}

private suspend fun AnalysisRepository.fetchModelsAnthropic(service: AppService, apiKey: String): List<String> {
    return try {
        val api = ApiFactory.createClaudeApi(service.baseUrl)
        val response = api.listModels(apiKey)
        if (response.isSuccessful) response.body()?.data?.mapNotNull { it.id }?.filter { it.startsWith("claude") }?.sorted() ?: emptyList()
        else emptyList()
    } catch (_: Exception) { emptyList() }
}

private suspend fun AnalysisRepository.fetchModelsGemini(service: AppService, apiKey: String): List<String> {
    return try {
        val api = ApiFactory.createGeminiApi(service.baseUrl)
        val response = api.listModels(apiKey)
        if (response.isSuccessful) {
            response.body()?.models?.filter { it.supportedGenerationMethods?.contains("generateContent") == true }
                ?.mapNotNull { it.name?.removePrefix("models/") }?.sorted() ?: emptyList()
        } else emptyList()
    } catch (_: Exception) { emptyList() }
}

// ============================================================================
// Test methods
// ============================================================================

suspend fun AnalysisRepository.testModel(service: AppService, apiKey: String, model: String): String? = withContext(Dispatchers.IO) {
    try {
        val response = analyze(service, apiKey, AnalysisRepository.TEST_PROMPT, model)
        if (response.isSuccess) null else response.error ?: "Unknown error"
    } catch (e: Exception) { e.message ?: "Connection error" }
}

suspend fun AnalysisRepository.testModelWithPrompt(
    service: AppService, apiKey: String, model: String, prompt: String
): Pair<String?, String?> = withContext(Dispatchers.IO) {
    try {
        val response = analyze(service, apiKey, prompt, model)
        if (response.isSuccess) Pair(response.analysis, null) else Pair(null, response.error ?: "Unknown error")
    } catch (e: Exception) { Pair(null, e.message ?: "Connection error") }
}

/** Test API connection with raw JSON body. */
suspend fun AnalysisRepository.testApiConnectionWithJson(
    service: AppService, apiKey: String, baseUrl: String, jsonBody: String
): AnalysisResponse = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder()
            .addInterceptor(TracingInterceptor())
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val effectiveJson = try {
            @Suppress("DEPRECATION")
            val jsonElement = com.google.gson.JsonParser().parse(jsonBody)
            if (jsonElement.isJsonObject) { jsonElement.asJsonObject.addProperty("stream", false); createAppGson().toJson(jsonElement) }
            else jsonBody
        } catch (_: Exception) { jsonBody }

        val body = effectiveJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestBuilder = okhttp3.Request.Builder().post(body).addHeader("Content-Type", "application/json")
        when (service.apiFormat) {
            ApiFormat.ANTHROPIC -> { requestBuilder.url(baseUrl); requestBuilder.addHeader("x-api-key", apiKey); requestBuilder.addHeader("anthropic-version", "2023-06-01") }
            ApiFormat.GOOGLE -> { val url = if (baseUrl.contains("key=")) baseUrl else "$baseUrl${if (baseUrl.contains("?")) "&" else "?"}key=$apiKey"; requestBuilder.url(url) }
            else -> { requestBuilder.url(baseUrl); requestBuilder.addHeader("Authorization", "Bearer $apiKey") }
        }
        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        response.use {
            val headers = formatHeaders(response.headers)
            val statusCode = response.code
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                try {
                    val oaiResponse = createAppGson().fromJson(responseBody, OpenAiResponse::class.java)
                    val content = oaiResponse?.choices?.firstOrNull()?.message?.content
                    val usage = oaiResponse?.usage?.let { u -> TokenUsage(u.prompt_tokens ?: u.input_tokens ?: 0, u.completion_tokens ?: u.output_tokens ?: 0, extractApiCost(u, service)) }
                    if (content != null) AnalysisResponse(service, content, null, usage, httpHeaders = headers, httpStatusCode = statusCode)
                    else AnalysisResponse(service, responseBody, null, httpHeaders = headers, httpStatusCode = statusCode)
                } catch (_: Exception) { AnalysisResponse(service, responseBody, null, httpHeaders = headers, httpStatusCode = statusCode) }
            } else {
                AnalysisResponse(service, null, "API error: $statusCode - ${response.body?.string()}", httpHeaders = headers, httpStatusCode = statusCode)
            }
        }
    } catch (e: Exception) { AnalysisResponse(service, null, "Error: ${e.message}") }
}

// ============================================================================
// Helpers
// ============================================================================

private fun buildMessages(systemPrompt: String?, prompt: String): List<OpenAiMessage> = buildList {
    systemPrompt?.takeIf { it.isNotBlank() }?.let { add(OpenAiMessage("system", it)) }
    add(OpenAiMessage("user", prompt))
}

internal fun buildOpenAiRequest(service: AppService, model: String, messages: List<OpenAiMessage>, params: AgentParameters?, stream: Boolean? = null): OpenAiRequest {
    return OpenAiRequest(
        model = model, messages = messages, stream = stream,
        max_tokens = params?.maxTokens, temperature = params?.temperature,
        top_p = params?.topP, top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty, presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = if (service.seedFieldName == "seed") params?.seed else null,
        random_seed = if (service.seedFieldName == "random_seed") params?.seed else null,
        response_format = if (params?.responseFormatJson == true) OpenAiResponseFormat("json_object") else null,
        return_citations = if (service.supportsCitations) params?.returnCitations else null,
        search_recency_filter = if (service.supportsSearchRecency) params?.searchRecency else null,
        search = if (params?.searchEnabled == true) true else null
    )
}

private fun AnalysisRepository.parseOpenAiAnalysisResponse(service: AppService, response: retrofit2.Response<OpenAiResponse>): AnalysisResponse {
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.content
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.content }
                ?: choices.firstNotNullOfOrNull { it.message.reasoning_content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.let {
            TokenUsage(it.prompt_tokens ?: it.input_tokens ?: 0, it.completion_tokens ?: it.output_tokens ?: 0,
                extractApiCost(it, if (service.extractApiCost) service else null))
        }
        if (!content.isNullOrBlank()) AnalysisResponse(service, content, null, usage,
            citations = body?.citations, searchResults = body?.search_results, relatedQuestions = body?.related_questions,
            rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal fun extractResponsesApiContent(body: OpenAiResponsesApiResponse?): String? {
    return body?.output?.let { outputs ->
        outputs.firstOrNull()?.content?.firstOrNull { it.type == "output_text" }?.text
            ?: outputs.firstOrNull()?.content?.firstOrNull { it.type == "text" }?.text
            ?: outputs.firstOrNull()?.content?.firstNotNullOfOrNull { it.text }
            ?: outputs.firstOrNull { it.type == "message" }?.content?.firstNotNullOfOrNull { it.text }
            ?: outputs.flatMap { it.content ?: emptyList() }.firstNotNullOfOrNull { it.text }
    }
}

internal fun normalizeUrl(url: String): String = if (url.endsWith("/")) url else "$url/"
