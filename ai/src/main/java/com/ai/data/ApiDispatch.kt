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
 * Anthropic requires max_tokens on every request. The API caps differ by model;
 * sending 4096 for a Claude 4 Sonnet/Opus silently truncates long completions.
 * This returns a safe default when the user didn't specify one.
 */
internal fun defaultClaudeMaxTokens(model: String): Int {
    val m = model.lowercase()
    return when {
        "opus-4" in m -> 32_000
        "sonnet-4" in m -> 8_192
        "haiku-4" in m -> 8_192
        "claude-3-5" in m || "claude-3.5" in m -> 8_192
        else -> 4_096
    }
}

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
 * Fetch available models from a provider — ids only (back-compat shim).
 */
suspend fun AnalysisRepository.fetchModels(
    service: AppService,
    apiKey: String
): List<String> = fetchModelsWithKinds(service, apiKey).ids

/**
 * Fetch available models plus a per-model type classification. Native list-API
 * fields are preferred (OpenRouter modality, Cohere endpoints, Gemini supported
 * methods); naming heuristic fills in for everything else.
 */
suspend fun AnalysisRepository.fetchModelsWithKinds(
    service: AppService,
    apiKey: String
): FetchedModels = withContext(Dispatchers.IO) {
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
    val chatUrl = buildChatUrl(baseUrl, service.chatPath)
    val messages = buildMessages(params?.systemPrompt, prompt)
    val request = buildOpenAiRequest(service, model, messages, params)
    val response = api.chat(chatUrl, "Bearer $apiKey", request)

    return parseOpenAiAnalysisResponse(service, response)
}

private suspend fun AnalysisRepository.analyzeResponsesApi(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?
): AnalysisResponse {
    val api = ApiFactory.createOpenAiCompatibleApi(service.baseUrl)
    val responsesUrl = buildChatUrl(service.baseUrl, service.responsesPath ?: "v1/responses")
    val request = OpenAiResponsesRequest(
        model = model,
        input = prompt,
        instructions = params?.systemPrompt?.takeIf { it.isNotBlank() }
    )
    val response = api.responses(responsesUrl, "Bearer $apiKey", request)
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
        max_tokens = params?.maxTokens ?: defaultClaudeMaxTokens(model),
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
    val chatUrl = buildChatUrl(baseUrl, service.chatPath)
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
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}

private suspend fun AnalysisRepository.chatResponsesApi(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters
): String {
    val api = ApiFactory.createOpenAiCompatibleApi(service.baseUrl)
    val responsesUrl = buildChatUrl(service.baseUrl, service.responsesPath ?: "v1/responses")
    val systemPrompt = messages.find { it.role == "system" }?.content
    val inputMessages = messages.filter { it.role != "system" }.map { OpenAiResponsesInputMessage(it.role, it.content) }
    val request = if (inputMessages.size == 1 && inputMessages.first().role == "user") {
        OpenAiResponsesRequest(model = model, input = inputMessages.first().content, instructions = systemPrompt)
    } else {
        OpenAiResponsesRequest(model = model, input = inputMessages, instructions = systemPrompt)
    }
    val response = api.responses(responsesUrl, "Bearer $apiKey", request)
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
        model = model, messages = claudeMessages, max_tokens = params.maxTokens ?: defaultClaudeMaxTokens(model),
        temperature = params.temperature, top_p = params.topP, top_k = params.topK,
        system = systemPrompt, search = if (params.searchEnabled) true else null
    )
    val response = api.createMessage(apiKey, request = request)
    if (response.isSuccessful) {
        return response.body()?.content?.firstOrNull { it.type == "text" }?.text ?: throw Exception("No response content")
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
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
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}

// ============================================================================
// Model fetching implementations
// ============================================================================

private suspend fun AnalysisRepository.fetchModelsOpenAi(service: AppService, apiKey: String): FetchedModels {
    // OpenRouter exposes architecture.modality on its detailed list — use that for types.
    if (service.id == "OPENROUTER") {
        val orApi = ApiFactory.createOpenRouterModelsApi(service.baseUrl)
        val response = try { orApi.listModelsDetailed("Bearer $apiKey") } catch (_: Exception) { null }
        val data = if (response?.isSuccessful == true) response.body()?.data.orEmpty() else emptyList()
        if (data.isNotEmpty()) {
            val filtered = service.modelFilterRegex?.let { regex -> data.filter { regex.containsMatchIn(it.id) } } ?: data
            val ids = filtered.map { it.id }.sorted()
            val types = ids.associateWith { id ->
                val info = filtered.firstOrNull { it.id == id }
                ModelType.fromOpenRouterModality(info?.architecture?.modality) ?: ModelType.infer(id)
            }
            return FetchedModels(ids, types)
        }
        // Fall through to the basic /v1/models call below if the detailed call failed.
    }

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
    val ids = filtered.sorted()

    // Cohere's compatibility endpoint strips the `endpoints` field. Hit the native
    // api.cohere.com/v1/models to recover per-model types; fall back to heuristic.
    val nativeTypes: Map<String, String?> = if (service.id == "COHERE") {
        try {
            val cohere = ApiFactory.createCohereNativeApi()
            val resp = cohere.listModels("Bearer $apiKey")
            if (resp.isSuccessful) {
                resp.body()?.models.orEmpty().mapNotNull { m ->
                    val name = m.name ?: return@mapNotNull null
                    name to ModelType.fromCohereEndpoints(m.endpoints)
                }.toMap()
            } else emptyMap()
        } catch (_: Exception) { emptyMap() }
    } else emptyMap()

    val types = ids.associateWith { id -> nativeTypes[id] ?: ModelType.infer(id) }
    return FetchedModels(ids, types)
}

private suspend fun AnalysisRepository.fetchModelsAnthropic(service: AppService, apiKey: String): FetchedModels {
    return try {
        val api = ApiFactory.createClaudeApi(service.baseUrl)
        val response = api.listModels(apiKey)
        val ids = if (response.isSuccessful)
            response.body()?.data?.mapNotNull { it.id }?.filter { it.startsWith("claude") }?.sorted().orEmpty()
        else emptyList()
        FetchedModels(ids, ids.associateWith { ModelType.CHAT })
    } catch (_: Exception) { FetchedModels(emptyList(), emptyMap()) }
}

private suspend fun AnalysisRepository.fetchModelsGemini(service: AppService, apiKey: String): FetchedModels {
    return try {
        val api = ApiFactory.createGeminiApi(service.baseUrl)
        val response = api.listModels(apiKey)
        if (response.isSuccessful) {
            // Gemini's `supportedGenerationMethods` is the native source-of-truth for kind:
            // `generateContent` → CHAT, `embedContent` → EMBEDDING. Drop entries that
            // expose neither (countTokens-only, etc.) since the app can't drive them.
            val all = response.body()?.models.orEmpty().mapNotNull { m ->
                val name = m.name?.removePrefix("models/") ?: return@mapNotNull null
                val type = ModelType.fromGeminiMethods(m.supportedGenerationMethods)
                if (type != null) name to type else null
            }.toMap()
            val ids = all.keys.sorted()
            FetchedModels(ids, all)
        } else FetchedModels(emptyList(), emptyMap())
    } catch (_: Exception) { FetchedModels(emptyList(), emptyMap()) }
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
            .connectTimeout(com.ai.BuildConfig.NETWORK_CONNECT_TIMEOUT_SEC.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(com.ai.BuildConfig.TEST_CONNECTION_READ_TIMEOUT_SEC.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(com.ai.BuildConfig.NETWORK_WRITE_TIMEOUT_SEC.toLong(), java.util.concurrent.TimeUnit.SECONDS)
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

/**
 * Build the full chat-completions URL from a caller-supplied URL and the provider's chatPath.
 *
 * Callers may pass either:
 *  - a bare base URL ("https://api.example.com/"), in which case we append chatPath, or
 *  - a full endpoint URL ("https://api.example.com/v1/chat/completions"), e.g. from
 *    Settings.getEffectiveEndpointUrlForAgent(), in which case we must NOT append chatPath
 *    (that was the "/v1/chat/completions/v1/chat/completions" 404 bug in reports).
 *
 * We detect the second case by checking whether the URL already ends with chatPath.
 */
internal fun buildChatUrl(baseUrl: String, chatPath: String): String {
    val cleanedChatPath = chatPath.trim('/')
    if (cleanedChatPath.isEmpty()) return baseUrl
    val trimmedUrl = baseUrl.trimEnd('/')
    return if (trimmedUrl.endsWith("/$cleanedChatPath") || trimmedUrl.endsWith(cleanedChatPath)) {
        trimmedUrl
    } else {
        "$trimmedUrl/$cleanedChatPath"
    }
}
