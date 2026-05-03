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
    baseUrl: String = service.baseUrl,
    imageBase64: String? = null,
    imageMime: String? = null
): AnalysisResponse = withContext(Dispatchers.IO) {
    when (service.apiFormat) {
        ApiFormat.ANTHROPIC -> analyzeAnthropic(service, apiKey, prompt, model, params, imageBase64, imageMime)
        ApiFormat.GOOGLE -> analyzeGemini(service, apiKey, prompt, model, params, imageBase64, imageMime)
        ApiFormat.OPENAI_COMPATIBLE -> analyzeOpenAi(service, apiKey, prompt, model, params, baseUrl, imageBase64, imageMime)
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
    withTraceCategory("Retrieve models list") {
        when (service.apiFormat) {
            ApiFormat.ANTHROPIC -> fetchModelsAnthropic(service, apiKey)
            ApiFormat.GOOGLE -> fetchModelsGemini(service, apiKey)
            ApiFormat.OPENAI_COMPATIBLE -> fetchModelsOpenAi(service, apiKey)
        }
    }
}

/**
 * Compute embeddings for a batch of input strings via the provider's
 * /v1/embeddings endpoint. OpenAI-compatible only — Anthropic and Google have
 * their own embedding APIs that we don't dispatch yet.
 *
 * Returns one vector per input string, or null on failure.
 */
suspend fun AnalysisRepository.embed(
    service: AppService,
    apiKey: String,
    model: String,
    texts: List<String>
): List<List<Double>>? = withContext(Dispatchers.IO) {
    if (service.apiFormat != ApiFormat.OPENAI_COMPATIBLE || apiKey.isBlank() || texts.isEmpty()) {
        return@withContext null
    }
    try {
        val api = ApiFactory.createOpenAiCompatibleApi(service.baseUrl)
        val embedPath = service.pathFor(ModelType.EMBEDDING) ?: ModelType.DEFAULT_PATHS[ModelType.EMBEDDING]!!
        val url = buildChatUrl(service.baseUrl, embedPath, service.knownEndpointPaths())
        val response = api.embeddings(url, "Bearer $apiKey", OpenAiEmbeddingRequest(model = model, input = texts))
        if (!response.isSuccessful) return@withContext null
        val data = response.body()?.data ?: return@withContext null
        // Sort by index defensively — most providers return in input order but the
        // spec lets them reorder, and we need the alignment exact.
        val sorted = data.sortedBy { it.index ?: 0 }
        sorted.map { it.embedding ?: emptyList() }.takeIf { it.size == texts.size }
    } catch (_: Exception) { null }
}

// ============================================================================
// Analyze implementations
// ============================================================================

private suspend fun AnalysisRepository.analyzeOpenAi(
    service: AppService, apiKey: String, prompt: String, model: String,
    params: AgentParameters?, baseUrl: String,
    imageBase64: String? = null, imageMime: String? = null
): AnalysisResponse {
    if (usesResponsesApi(service, model)) return analyzeResponsesApi(service, apiKey, prompt, model, params, baseUrl, imageBase64, imageMime)

    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val chatUrl = buildChatUrl(baseUrl, service.chatPath, service.knownEndpointPaths())
    val messages = buildMessages(params?.systemPrompt, prompt, imageBase64, imageMime)
    val request = buildOpenAiRequest(service, model, messages, params)
    val response = api.chat(chatUrl, "Bearer $apiKey", request)

    return parseOpenAiAnalysisResponse(service, response)
}

private suspend fun AnalysisRepository.analyzeResponsesApi(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?,
    baseUrl: String,
    imageBase64: String? = null, imageMime: String? = null
): AnalysisResponse {
    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val responsesUrl = responsesUrlFor(service, baseUrl)
    val input: Any = if (imageBase64 != null) {
        // Responses API accepts an array of input messages whose content is a typed
        // parts array — input_text + input_image (image_url as a data: URL).
        val mime = imageMime ?: "image/png"
        listOf(mapOf(
            "role" to "user",
            "content" to buildList {
                if (prompt.isNotBlank()) add(mapOf("type" to "input_text", "text" to prompt))
                add(mapOf("type" to "input_image", "image_url" to "data:$mime;base64,$imageBase64"))
            }
        ))
    } else prompt
    val request = OpenAiResponsesRequest(
        model = model,
        input = input,
        instructions = params?.systemPrompt?.takeIf { it.isNotBlank() },
        tools = if (params?.webSearchTool == true) responsesWebSearchTool() else null,
        reasoning = reasoningField(service, model, params?.reasoningEffort)
    )
    val response = api.responses(responsesUrl, "Bearer $apiKey", request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = extractResponsesApiContent(body)
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.toTokenUsage()
        val webData = extractResponsesWebSearch(body)
        if (content != null) AnalysisResponse(
            service, content, null, usage,
            citations = webData.citations, searchResults = webData.searchResults, relatedQuestions = webData.queries,
            rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode
        )
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

private suspend fun AnalysisRepository.analyzeAnthropic(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?,
    imageBase64: String? = null, imageMime: String? = null
): AnalysisResponse {
    val api = ApiFactory.createClaudeApi(service.baseUrl)
    val userMessage = ChatMessage("user", prompt, imageBase64 = imageBase64, imageMime = imageMime).toClaudeMessage()
    val bundle = claudeReasoningBundle(service, model, params?.reasoningEffort, params?.maxTokens)
    val request = ClaudeRequest(
        model = model,
        messages = listOf(userMessage),
        max_tokens = bundle.maxTokens,
        temperature = params?.temperature, top_p = params?.topP, top_k = params?.topK,
        system = params?.systemPrompt?.takeIf { it.isNotBlank() },
        stop_sequences = params?.stopSequences?.takeIf { it.isNotEmpty() },
        frequency_penalty = params?.frequencyPenalty, presence_penalty = params?.presencePenalty,
        seed = params?.seed,
        search = if (params?.searchEnabled == true) true else null,
        tools = if (params?.webSearchTool == true) anthropicWebSearchTool() else null,
        thinking = bundle.thinking,
        output_config = bundle.outputConfig
    )
    val response = api.createMessage(apiKey, request = request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        // Concatenate every text block — when web_search runs, Anthropic
        // splits the answer into many text blocks interleaved with
        // server_tool_use / web_search_tool_result. Taking only the first
        // gives just the preamble ("I'll search for…") and drops the
        // entire response. Joins fragments with no separator since the
        // model already includes its own punctuation/whitespace.
        val content = body?.content
            ?.filter { it.type == "text" }
            ?.mapNotNull { it.text }
            ?.joinToString(separator = "")
            ?.takeIf { it.isNotBlank() }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.toTokenUsage()
        val webData = extractClaudeWebSearch(body)
        if (content != null) AnalysisResponse(
            service, content, null, usage,
            citations = webData.citations, searchResults = webData.searchResults, relatedQuestions = webData.queries,
            rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode
        )
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

private suspend fun AnalysisRepository.analyzeGemini(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?,
    imageBase64: String? = null, imageMime: String? = null
): AnalysisResponse {
    val genConfig = params?.let {
        GeminiGenerationConfig(it.temperature, it.topP, it.topK, it.maxTokens,
            it.stopSequences?.takeIf { s -> s.isNotEmpty() }, it.frequencyPenalty, it.presencePenalty, it.seed,
            if (it.searchEnabled) true else null,
            thinkingConfig = geminiThinkingConfigField(service, model, it.reasoningEffort))
    }
    val systemInstruction = params?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
        GeminiContent(listOf(GeminiPart(text = it)))
    }
    val userContent = ChatMessage("user", prompt, imageBase64 = imageBase64, imageMime = imageMime).toGeminiContent()
    val request = GeminiRequest(
        contents = listOf(userContent),
        generationConfig = genConfig,
        systemInstruction = systemInstruction,
        tools = if (params?.webSearchTool == true) geminiWebSearchTool() else null
    )
    val api = ApiFactory.createGeminiApi(service.baseUrl)
    val response = api.generateContent(model, apiKey, request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: body?.candidates?.flatMap { it.content?.parts ?: emptyList() }?.firstNotNullOfOrNull { it.text }
        val rawUsageJson = formatUsageJson(body?.usageMetadata)
        val usage = body?.usageMetadata?.toTokenUsage()
        val webData = extractGeminiWebSearch(body)
        if (content != null) AnalysisResponse(
            service, content, null, usage,
            citations = webData.citations, searchResults = webData.searchResults, relatedQuestions = webData.queries,
            rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode
        )
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
    if (usesResponsesApi(service, model)) return chatResponsesApi(service, apiKey, model, messages, params, baseUrl)

    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val chatUrl = buildChatUrl(baseUrl, service.chatPath, service.knownEndpointPaths())
    val openAiMessages = messages.map { it.toOpenAiMessage() }
    val request = OpenAiRequest(
        model = model, messages = openAiMessages,
        max_tokens = params.maxTokens, temperature = params.temperature,
        top_p = params.topP, top_k = params.topK,
        frequency_penalty = params.frequencyPenalty, presence_penalty = params.presencePenalty,
        search = if (params.searchEnabled) true else null,
        tools = if (params.webSearchTool) openAiChatWebSearchTool() else null,
        reasoning_effort = params.reasoningEffort?.takeIf {
            it.isNotBlank() && isReasoningCapableForDispatch(service, model)
        }
    )
    val response = api.chat(chatUrl, "Bearer $apiKey", request)
    if (response.isSuccessful) {
        return response.body()?.choices?.firstOrNull()?.message?.contentAsString() ?: throw Exception("No response content")
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}

private suspend fun AnalysisRepository.chatResponsesApi(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters, baseUrl: String
): String {
    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val responsesUrl = responsesUrlFor(service, baseUrl)
    val systemPrompt = messages.find { it.role == "system" }?.content
    val inputMessages = messages.filter { it.role != "system" }.map { OpenAiResponsesInputMessage(it.role, it.content) }
    val tools = if (params.webSearchTool) responsesWebSearchTool() else null
    val reasoning = reasoningField(service, model, params.reasoningEffort)
    val request = if (inputMessages.size == 1 && inputMessages.first().role == "user") {
        OpenAiResponsesRequest(model = model, input = inputMessages.first().content, instructions = systemPrompt, tools = tools, reasoning = reasoning)
    } else {
        OpenAiResponsesRequest(model = model, input = inputMessages, instructions = systemPrompt, tools = tools, reasoning = reasoning)
    }
    val response = api.responses(responsesUrl, "Bearer $apiKey", request)
    if (response.isSuccessful) {
        return extractResponsesApiContent(response.body()) ?: throw Exception("No response content")
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}

internal fun responsesUrlFor(service: AppService, baseUrl: String): String =
    buildChatUrl(baseUrl, service.responsesPath ?: "v1/responses", service.knownEndpointPaths())

private suspend fun AnalysisRepository.chatAnthropic(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters
): String {
    val api = ApiFactory.createClaudeApi(service.baseUrl)
    val claudeMessages = messages.filter { it.role != "system" }.map { it.toClaudeMessage() }
    val systemPrompt = messages.find { it.role == "system" }?.content
    val bundle = claudeReasoningBundle(service, model, params.reasoningEffort, params.maxTokens)
    val request = ClaudeRequest(
        model = model, messages = claudeMessages, max_tokens = bundle.maxTokens,
        temperature = params.temperature, top_p = params.topP, top_k = params.topK,
        system = systemPrompt, search = if (params.searchEnabled) true else null,
        tools = if (params.webSearchTool) anthropicWebSearchTool() else null,
        thinking = bundle.thinking,
        output_config = bundle.outputConfig
    )
    val response = api.createMessage(apiKey, request = request)
    if (response.isSuccessful) {
        // See analyzeAnthropic for why we concatenate every text block.
        return response.body()?.content
            ?.filter { it.type == "text" }
            ?.mapNotNull { it.text }
            ?.joinToString(separator = "")
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("No response content")
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        throw Exception("API error: ${response.code()} ${response.message()} - $errorBody")
    }
}

private suspend fun AnalysisRepository.chatGemini(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters
): String {
    val api = ApiFactory.createGeminiApi(service.baseUrl)
    val contents = messages.filter { it.role != "system" }.map { it.toGeminiContent() }
    val systemInstruction = messages.find { it.role == "system" }?.let { GeminiContent(listOf(GeminiPart(text = it.content))) }
    val request = GeminiRequest(
        contents = contents,
        generationConfig = GeminiGenerationConfig(
            params.temperature, params.topP, params.topK, params.maxTokens,
            search = if (params.searchEnabled) true else null,
            thinkingConfig = geminiThinkingConfigField(service, model, params.reasoningEffort)
        ),
        systemInstruction = systemInstruction,
        tools = if (params.webSearchTool) geminiWebSearchTool() else null
    )
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
    // Capture the raw /models JSON alongside the typed parse so a later
    // parser revision (new capability fields, etc.) can re-extract from
    // the same response without forcing a re-fetch. Two HTTP calls per
    // refresh — the typed Retrofit call below for parsing, and one raw
    // String call here for the snapshot. The bandwidth cost is small
    // (model lists are tens of KB at most).
    val modelsUrlForRaw = run {
        val pathPart = if (service.id == "OPENROUTER") "v1/models" else (service.modelsPath ?: "v1/models")
        normalizeUrl(service.baseUrl) + pathPart
    }
    val rawJson = ApiFactory.fetchUrlAsString(modelsUrlForRaw, mapOf("Authorization" to "Bearer $apiKey"))

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
            // architecture.input_modalities lists the raw input types — "image"
            // means the model accepts vision content blocks. Union into the
            // user-curated visionModels at apply time (see withModels overload).
            val vision = ids.filter { id ->
                val info = filtered.firstOrNull { it.id == id }
                ModelType.fromOpenRouterInputModalities(info?.architecture?.input_modalities)
            }.toSet()
            val caps = ids.associateWith { id ->
                val info = filtered.firstOrNull { it.id == id }
                ModelCapabilities(
                    supportsVision = info?.architecture?.input_modalities
                        ?.any { it.equals("image", ignoreCase = true) },
                    contextLength = info?.context_length ?: info?.top_provider?.context_length,
                    maxOutputTokens = info?.top_provider?.max_completion_tokens,
                    // OpenRouter's `supported_parameters` array enumerates
                    // the request fields each model honors. "reasoning" /
                    // "include_reasoning" presence ⇒ thinking-capable.
                    supportsReasoning = info?.supported_parameters?.let {
                        it.any { p -> p.equals("reasoning", true) || p.equals("include_reasoning", true) }
                    }
                )
            }.filterValues { it.supportsVision != null || it.contextLength != null || it.maxOutputTokens != null || it.supportsReasoning != null }
            return FetchedModels(ids, types, vision, caps, rawJson)
        }
        // Fall through to the basic /v1/models call below if the detailed call failed.
    }

    val api = ApiFactory.createOpenAiCompatibleApi(service.baseUrl)
    val modelsUrl = normalizeUrl(service.baseUrl) + (service.modelsPath ?: "v1/models")
    // Capture full model objects (not just ids) so per-provider capability
    // fields (Mistral capabilities, Together AI context_length, Groq
    // context_window, Fireworks supports_image_input, etc.) ride through
    // to the FetchedModels.capabilities map.
    val rawModels: List<OpenAiModel> = if (service.modelListFormat == "array") {
        val response = api.listModelsArray(modelsUrl, "Bearer $apiKey")
        if (response.isSuccessful) response.body().orEmpty() else emptyList()
    } else {
        val response = api.listModels(modelsUrl, "Bearer $apiKey")
        if (response.isSuccessful) response.body()?.data.orEmpty() else emptyList()
    }
    val modelIds = rawModels.mapNotNull { it.id }
    val filtered = service.modelFilterRegex?.let { regex -> modelIds.filter { regex.containsMatchIn(it) } } ?: modelIds
    val ids = filtered.sorted()

    // Cohere's compatibility endpoint strips the `endpoints` field. Hit the native
    // api.cohere.com/v1/models to recover per-model types AND capability info
    // (context_length, supports_vision); fall back to heuristic on failure.
    data class CohereCap(val type: String?, val cap: ModelCapabilities)
    val cohereByName: Map<String, CohereCap> = if (service.id == "COHERE") {
        try {
            val cohere = ApiFactory.createCohereNativeApi()
            val resp = cohere.listModels("Bearer $apiKey")
            if (resp.isSuccessful) {
                resp.body()?.models.orEmpty().mapNotNull { m ->
                    val name = m.name ?: return@mapNotNull null
                    name to CohereCap(
                        type = ModelType.fromCohereEndpoints(m.endpoints),
                        cap = ModelCapabilities(
                            supportsVision = m.supports_vision,
                            contextLength = m.context_length
                        )
                    )
                }.toMap()
            } else emptyMap()
        } catch (_: Exception) { emptyMap() }
    } else emptyMap()

    val types = ids.associateWith { id ->
        cohereByName[id]?.type
            // Mistral capabilities are per-modality booleans; pick the
            // non-chat one when set so the picker auto-tags the right
            // ModelType. Order matters — moderation > stt > tts > ocr
            // > classification > chat (chat is the default; if multiple
            // flags are true we prefer the more specific one).
            ?: rawModels.firstOrNull { it.id == id }?.capabilities?.let { c ->
                when {
                    c.moderation == true -> ModelType.MODERATION
                    c.audio_transcription == true -> ModelType.STT
                    c.audio_speech == true -> ModelType.TTS
                    c.ocr == true -> ModelType.OCR
                    c.classification == true -> ModelType.CLASSIFY
                    else -> null
                }
            }
            ?: ModelType.infer(id)
    }
    val caps = mutableMapOf<String, ModelCapabilities>()
    for (id in ids) {
        val info = rawModels.firstOrNull { it.id == id }
        // Mistral exposes a rich capabilities block; other providers
        // sprinkle individual flags. Pull whatever we can recognize.
        val supportsVision = info?.capabilities?.vision
            ?: info?.supports_image_input
            ?: info?.supports_image_in
        val supportsFn = info?.capabilities?.function_calling
        val ctx = info?.max_context_length ?: info?.context_length ?: info?.context_window
        // Reasoning: prefer Mistral's nested `capabilities.reasoning`
        // boolean; fall back to any `supported_parameters` array
        // (xAI / Together / etc.) carrying a "reasoning" entry.
        val supportsReasoning = info?.capabilities?.reasoning
            ?: info?.supported_parameters?.let {
                it.any { p -> p.equals("reasoning", true) || p.equals("include_reasoning", true) }
            }
        val cohereCap = cohereByName[id]?.cap
        val merged = ModelCapabilities(
            supportsVision = supportsVision ?: cohereCap?.supportsVision,
            supportsFunctionCalling = supportsFn,
            contextLength = ctx ?: cohereCap?.contextLength,
            maxOutputTokens = null,
            supportsReasoning = supportsReasoning
        )
        if (merged.supportsVision != null || merged.supportsFunctionCalling != null
            || merged.contextLength != null || merged.maxOutputTokens != null
            || merged.supportsReasoning != null) {
            caps[id] = merged
        }
    }
    val visionFromList = caps.filterValues { it.supportsVision == true }.keys
    return FetchedModels(ids, types, visionFromList, caps, rawJson)
}

private suspend fun AnalysisRepository.fetchModelsAnthropic(service: AppService, apiKey: String): FetchedModels {
    val response = try {
        ApiFactory.createClaudeApi(service.baseUrl).listModels(apiKey)
    } catch (e: Exception) {
        // Network / parse failure — log so the user / Trace screen can
        // see why Anthropic fell through to its hardcodedModels fallback
        // instead of silently returning an empty list. Without this the
        // model picker shows only the setup.json predefines and the
        // user has no clue an API call even happened.
        android.util.Log.w("ApiDispatch", "Anthropic listModels threw: ${e.javaClass.simpleName}: ${e.message}")
        return FetchedModels(emptyList(), emptyMap())
    }
    if (!response.isSuccessful) {
        val body = runCatching { response.errorBody()?.string()?.take(300) }.getOrNull()
        android.util.Log.w("ApiDispatch", "Anthropic listModels HTTP ${response.code()}: ${body ?: "(no body)"}")
        return FetchedModels(emptyList(), emptyMap())
    }
    val entries = response.body()?.data?.filter { it.id?.startsWith("claude") == true }.orEmpty()
    if (entries.isEmpty()) {
        android.util.Log.w("ApiDispatch", "Anthropic listModels returned 200 but no claude-* entries (data size=${response.body()?.data?.size ?: 0})")
    }
    val ids = entries.mapNotNull { it.id }.sorted()
    // Read Anthropic's per-model capability bundle directly. Provider
    // self-report is authoritative when present; pre-this-change we
    // were falling through to LiteLLM / models.dev / heuristics for
    // every Claude entry's vision + context length even though
    // Anthropic ships them right here.
    val caps = entries.mapNotNull { info ->
        val id = info.id ?: return@mapNotNull null
        val thinking = info.capabilities?.thinking?.supported
        val vision = info.capabilities?.image_input?.supported
        val ctx = info.max_input_tokens
        val maxOut = info.max_tokens
        val effort = info.capabilities?.effort
        val effortLevels = if (effort?.supported == true) buildList {
            if (effort.low?.supported == true) add("low")
            if (effort.medium?.supported == true) add("medium")
            if (effort.high?.supported == true) add("high")
            if (effort.max?.supported == true) add("max")
        }.takeIf { it.isNotEmpty() } else null
        val cap = ModelCapabilities(
            supportsVision = vision,
            contextLength = ctx,
            maxOutputTokens = maxOut,
            supportsReasoning = thinking,
            reasoningEffortLevels = effortLevels
        )
        if (cap.supportsVision == null && cap.contextLength == null
            && cap.maxOutputTokens == null && cap.supportsReasoning == null
            && cap.reasoningEffortLevels == null) null
        else id to cap
    }.toMap()
    val visionFlagged = caps.filterValues { it.supportsVision == true }.keys.toSet()
    // Best-effort raw-JSON snapshot for future parser revisions.
    // fetchUrlAsString already swallows its own exceptions and returns
    // null on failure, so it can't poison the success path here — but
    // it's a second HTTP roundtrip that adds latency and another way
    // to fail silently. If it hands back null we just store null;
    // ModelListCache treats that as "no snapshot this run".
    val rawJson = ApiFactory.fetchUrlAsString(
        normalizeUrl(service.baseUrl) + "v1/models",
        mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
    )
    return FetchedModels(ids, ids.associateWith { ModelType.CHAT }, visionModels = visionFlagged, capabilities = caps, rawResponse = rawJson)
}

private suspend fun AnalysisRepository.fetchModelsGemini(service: AppService, apiKey: String): FetchedModels {
    val rawJson = ApiFactory.fetchUrlAsString("${normalizeUrl(service.baseUrl)}v1beta/models?key=$apiKey")
    return try {
        val api = ApiFactory.createGeminiApi(service.baseUrl)
        val response = api.listModels(apiKey)
        if (response.isSuccessful) {
            // Gemini's `supportedGenerationMethods` is the native source-of-truth for kind:
            // `generateContent` → CHAT, `embedContent` → EMBEDDING. Drop entries that
            // expose neither (countTokens-only, etc.) since the app can't drive them.
            val rawModels = response.body()?.models.orEmpty()
            val byName = rawModels.mapNotNull { m ->
                val name = m.name?.removePrefix("models/") ?: return@mapNotNull null
                val type = ModelType.fromGeminiMethods(m.supportedGenerationMethods) ?: return@mapNotNull null
                name to (type to m)
            }.toMap()
            val all = byName.mapValues { it.value.first }
            val ids = all.keys.sorted()
            // Gemini exposes inputTokenLimit / outputTokenLimit per model;
            // no explicit vision flag, so leave that null and let the
            // heuristic / catalog fallbacks decide. The top-level
            // `thinking` boolean rides on every 2.5-family entry.
            val caps = byName.mapValues { (_, v) ->
                val m = v.second
                ModelCapabilities(
                    contextLength = m.inputTokenLimit,
                    maxOutputTokens = m.outputTokenLimit,
                    supportsReasoning = m.thinking
                )
            }.filterValues { it.contextLength != null || it.maxOutputTokens != null || it.supportsReasoning != null }
            FetchedModels(ids, all, emptySet(), caps, rawJson)
        } else FetchedModels(emptyList(), emptyMap())
    } catch (_: Exception) { FetchedModels(emptyList(), emptyMap()) }
}

// ============================================================================
// Test methods
// ============================================================================

suspend fun AnalysisRepository.testModel(service: AppService, apiKey: String, model: String): String? = withContext(Dispatchers.IO) {
    withTraceCategory("Provider test") {
        try {
            val response = analyze(service, apiKey, AnalysisRepository.TEST_PROMPT, model)
            if (response.isSuccess) null else response.error ?: "Unknown error"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { e.message ?: "Connection error" }
    }
}

suspend fun AnalysisRepository.testModelWithPrompt(
    service: AppService, apiKey: String, model: String, prompt: String
): Pair<String?, String?> = withContext(Dispatchers.IO) {
    withTraceCategory("Provider test") {
        try {
            val response = analyze(service, apiKey, prompt, model)
            if (response.isSuccess) Pair(response.analysis, null) else Pair(null, response.error ?: "Unknown error")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { Pair(null, e.message ?: "Connection error") }
    }
}

/** Test API connection with raw JSON body. */
suspend fun AnalysisRepository.testApiConnectionWithJson(
    service: AppService, apiKey: String, baseUrl: String, jsonBody: String
): AnalysisResponse = withContext(Dispatchers.IO) {
    withTraceCategory("Provider test") {
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
                    val content = oaiResponse?.choices?.firstOrNull()?.message?.contentAsString()
                    val usage = oaiResponse?.usage?.toTokenUsage(service)
                    if (content != null) AnalysisResponse(service, content, null, usage, httpHeaders = headers, httpStatusCode = statusCode)
                    else AnalysisResponse(service, responseBody, null, httpHeaders = headers, httpStatusCode = statusCode)
                } catch (_: Exception) { AnalysisResponse(service, responseBody, null, httpHeaders = headers, httpStatusCode = statusCode) }
            } else {
                AnalysisResponse(service, null, "API error: $statusCode - ${response.body?.string()}", httpHeaders = headers, httpStatusCode = statusCode)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) { AnalysisResponse(service, null, "Error: ${e.message}") }
    }
}

// ============================================================================
// Helpers
// ============================================================================

private fun buildMessages(
    systemPrompt: String?,
    prompt: String,
    imageBase64: String? = null,
    imageMime: String? = null
): List<OpenAiMessage> = buildList {
    systemPrompt?.takeIf { it.isNotBlank() }?.let { add(OpenAiMessage("system", it)) }
    add(ChatMessage("user", prompt, imageBase64 = imageBase64, imageMime = imageMime).toOpenAiMessage())
}

internal fun buildOpenAiRequest(service: AppService, model: String, messages: List<OpenAiMessage>, params: AgentParameters?, stream: Boolean? = null): OpenAiRequest {
    // Drop response_format when LiteLLM reports the model doesn't honor a
    // response schema — sending it would either error out or be silently
    // ignored. Null on LiteLLM (unknown model) leaves it in.
    val jsonRequested = params?.responseFormatJson == true
    val jsonAllowed = jsonRequested && PricingCache.liteLLMSupportsResponseSchema(service, model) != false
    return OpenAiRequest(
        model = model, messages = messages, stream = stream,
        max_tokens = params?.maxTokens, temperature = params?.temperature,
        top_p = params?.topP, top_k = params?.topK,
        frequency_penalty = params?.frequencyPenalty, presence_penalty = params?.presencePenalty,
        stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
        seed = if (service.seedFieldName == "seed") params?.seed else null,
        random_seed = if (service.seedFieldName == "random_seed") params?.seed else null,
        response_format = if (jsonAllowed) OpenAiResponseFormat("json_object") else null,
        return_citations = if (service.supportsCitations) params?.returnCitations else null,
        search_recency_filter = if (service.supportsSearchRecency) params?.searchRecency else null,
        search = if (params?.searchEnabled == true) true else null,
        tools = if (params?.webSearchTool == true) openAiChatWebSearchTool() else null,
        // Same capability gate the Responses-API path uses — drop the
        // field when the layered lookup says this model doesn't expose
        // reasoning_effort, so strict providers (Groq, Cohere, xAI's
        // grok-4.x non-thinking, etc.) don't 400 the whole request.
        reasoning_effort = params?.reasoningEffort?.takeIf {
            it.isNotBlank() && isReasoningCapableForDispatch(service, model)
        }
    )
}

private fun AnalysisRepository.parseOpenAiAnalysisResponse(service: AppService, response: retrofit2.Response<OpenAiResponse>): AnalysisResponse {
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.contentAsString()
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstNotNullOfOrNull { it.message.contentAsString() }
                ?: choices.firstNotNullOfOrNull { it.message.reasoning_content }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.toTokenUsage(service)
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
 *    (that was the "/v1/chat/completions/v1/chat/completions" 404 bug in reports), or
 *  - a full endpoint URL pointing at a *different* known path on the same service
 *    ("https://api.openai.com/v1/chat/completions" while we want "v1/responses"),
 *    in which case [alternatePaths] should list the other known paths so we can
 *    strip the wrong tail before appending the right one. Without this we'd build
 *    "/v1/chat/completions/v1/responses" → 404 "Invalid URL".
 */
internal fun buildChatUrl(
    baseUrl: String,
    chatPath: String,
    alternatePaths: List<String> = emptyList()
): String {
    val cleanedChatPath = chatPath.trim('/')
    if (cleanedChatPath.isEmpty()) return baseUrl
    var trimmedUrl = baseUrl.trimEnd('/')
    if (trimmedUrl.endsWith("/$cleanedChatPath") || trimmedUrl.endsWith(cleanedChatPath)) {
        return trimmedUrl
    }
    for (alt in alternatePaths) {
        val cleanedAlt = alt.trim('/')
        if (cleanedAlt.isNotEmpty() && cleanedAlt != cleanedChatPath && trimmedUrl.endsWith("/$cleanedAlt")) {
            trimmedUrl = trimmedUrl.removeSuffix("/$cleanedAlt").trimEnd('/')
            break
        }
    }
    return "$trimmedUrl/$cleanedChatPath"
}

/** All endpoint paths this service knows about (chat / responses / embedding).
 *  Used by [buildChatUrl] to strip a wrong tail before appending the right one
 *  when the user has configured a full endpoint URL as their base. */
internal fun AppService.knownEndpointPaths(): List<String> = listOfNotNull(
    chatPath,
    responsesPath,
    pathFor(ModelType.EMBEDDING)
)

/** Read [OpenAiMessage.content] as a String regardless of whether it was a
 *  raw String or (after a future round-trip) a serialized list. Response
 *  bodies always come back with content as a JSON string, so this is safe. */
internal fun OpenAiMessage.contentAsString(): String? = when (val c = content) {
    is String -> c
    null -> null
    else -> c.toString()
}

// ============================================================================
// Vision helpers — convert a ChatMessage with optional image attachment into
// per-format request shapes. Text-only messages keep the simple String content
// form so the wire payload is identical to before.
// ============================================================================

internal fun ChatMessage.toOpenAiMessage(): OpenAiMessage {
    if (imageBase64 == null) return OpenAiMessage(role, content)
    val mime = imageMime ?: "image/png"
    val parts = buildList {
        if (content.isNotBlank()) add(mapOf("type" to "text", "text" to content))
        add(mapOf(
            "type" to "image_url",
            "image_url" to mapOf("url" to "data:$mime;base64,$imageBase64")
        ))
    }
    return OpenAiMessage(role, parts)
}

internal fun ChatMessage.toClaudeMessage(): ClaudeMessage {
    if (imageBase64 == null) return ClaudeMessage(role, content)
    val mime = imageMime ?: "image/png"
    val blocks = buildList {
        add(mapOf(
            "type" to "image",
            "source" to mapOf("type" to "base64", "media_type" to mime, "data" to imageBase64)
        ))
        if (content.isNotBlank()) add(mapOf("type" to "text", "text" to content))
    }
    return ClaudeMessage(role, blocks)
}

/** Definitive "should this dispatch attach a thinking / reasoning_effort
 *  block?" check used by every dispatcher helper. Distinct from
 *  [com.ai.model.Settings.isReasoningCapable] — the badge concept; xAI
 *  ships always-on reasoning models that reason but reject the
 *  `reasoning_effort` parameter, and dispatch must skip the parameter
 *  there even though the badge stays on. Delegates to
 *  [com.ai.model.Settings.acceptsReasoningEffortParam] when a Settings
 *  reference is published in [com.ai.model.SettingsHolder]; falls back
 *  to a catalog-only chain (with the same xAI gate inlined) when no
 *  Settings reference is available (early startup, unit tests). */
internal fun isReasoningCapableForDispatch(service: AppService, model: String): Boolean {
    com.ai.model.SettingsHolder.current?.let { return it.acceptsReasoningEffortParam(service, model) }
    val capable = PricingCache.liteLLMSupportsReasoning(service, model)
        ?: PricingCache.modelsDevSupportsReasoning(service, model)
        ?: ModelType.inferReasoning(service, model)
    if (!capable) return false
    if (ModelType.externalReasoningSignalUntrusted(service))
        return ModelType.inferAcceptsReasoningEffortParam(service, model)
    return true
}

/** True when [model] is a Claude Opus 4.7+ build that requires the
 *  newer thinking shape (`thinking.type:"adaptive"` +
 *  `output_config.effort`). Older 3.7 / 4.x models still use the
 *  budget_tokens shape. */
private fun claudeUsesAdaptiveThinking(model: String): Boolean {
    val id = model.lowercase()
    return "claude-opus-4-7" in id || "opus-4-7" in id
}

/** Build the OpenAI Responses-API `reasoning` field — `{effort: <value>}` —
 *  or null when the agent didn't set an effort, OR the layered
 *  capability lookup says the model doesn't accept it. */
internal fun reasoningField(service: AppService, model: String, effort: String?): Map<String, Any>? {
    if (effort.isNullOrBlank()) return null
    if (!isReasoningCapableForDispatch(service, model)) return null
    return mapOf("effort" to effort)
}

/** Map low/medium/high to a token budget Anthropic + Gemini both
 *  consume. Round numbers — exact ceiling depends on the model, but
 *  these are well within every current Claude / Gemini cap. */
internal fun budgetForEffort(effort: String?): Int? = when (effort?.lowercase()) {
    "low" -> 1024
    "medium" -> 4096
    "high" -> 16384
    else -> null
}

/** Anthropic extended-thinking block. Only attached when the layered
 *  capability lookup confirms the model accepts thinking. Two shapes:
 *  Claude 3.7 / 4.x (pre-4.7) take `{type:"enabled", budget_tokens:N}`;
 *  Claude Opus 4.7+ takes `{type:"adaptive"}` and reads effort from
 *  the request's top-level `output_config` instead — see
 *  [anthropicOutputConfigField]. Returns null otherwise. */
internal fun anthropicThinkingField(service: AppService, model: String, effort: String?): Map<String, Any>? {
    if (effort.isNullOrBlank()) return null
    if (!isReasoningCapableForDispatch(service, model)) return null
    if (claudeUsesAdaptiveThinking(model)) return mapOf("type" to "adaptive")
    val budget = budgetForEffort(effort) ?: return null
    return mapOf("type" to "enabled", "budget_tokens" to budget)
}

/** Top-level `output_config.effort` companion to [anthropicThinkingField]
 *  for Claude Opus 4.7+. Returns null on older Claude builds (which
 *  carry the budget on the thinking block instead) and on non-thinking
 *  models. */
internal fun anthropicOutputConfigField(service: AppService, model: String, effort: String?): Map<String, Any>? {
    if (effort.isNullOrBlank()) return null
    if (!isReasoningCapableForDispatch(service, model)) return null
    if (!claudeUsesAdaptiveThinking(model)) return null
    return mapOf("effort" to effort)
}

/** Bundle [anthropicThinkingField] / [anthropicOutputConfigField] /
 *  the matching `max_tokens` value into one helper so every Claude
 *  dispatch site (analyze, chat, stream) ends up with consistent
 *  values. Anthropic rejects requests where `max_tokens <=
 *  thinking.budget_tokens` — when the user-supplied max isn't large
 *  enough, bump it past the budget plus a slack so the actual
 *  response has room. */
internal data class ClaudeReasoningBundle(
    val maxTokens: Int,
    val thinking: Map<String, Any>?,
    val outputConfig: Map<String, Any>?
)

internal fun claudeReasoningBundle(
    service: AppService, model: String, effort: String?, requestedMax: Int?
): ClaudeReasoningBundle {
    val thinking = anthropicThinkingField(service, model, effort)
    val outputConfig = anthropicOutputConfigField(service, model, effort)
    val baseMax = requestedMax ?: defaultClaudeMaxTokens(model)
    val budget = (thinking?.get("budget_tokens") as? Int) ?: 0
    // Anthropic 400s when max_tokens <= budget_tokens — give the
    // response some additional headroom on top of the thinking budget.
    val effectiveMax = if (budget > 0 && baseMax <= budget) budget + 4096 else baseMax
    return ClaudeReasoningBundle(effectiveMax, thinking, outputConfig)
}

/** Gemini 2.5 thinking config block. Same capability guard as the
 *  Anthropic equivalent. includeThoughts surfaces the model's
 *  internal reasoning summary in the response — left off by default
 *  to avoid bloating the response body for callers that just want the
 *  final answer. */
internal fun geminiThinkingConfigField(service: AppService, model: String, effort: String?): Map<String, Any>? {
    val budget = budgetForEffort(effort) ?: return null
    if (!isReasoningCapableForDispatch(service, model)) return null
    return mapOf("thinkingBudget" to budget)
}

/** Per-format web-search tool descriptor, or null when unsupported by this
 *  format (OpenAI Chat Completions has no native web-search tool — gpt-5.x
 *  on the Responses API does, see [responsesWebSearchTool]). */
internal fun openAiChatWebSearchTool(): List<Any>? = null

internal fun responsesWebSearchTool(): List<Any> = listOf(mapOf("type" to "web_search_preview"))

internal fun anthropicWebSearchTool(): List<Any> = listOf(mapOf(
    "type" to "web_search_20250305",
    "name" to "web_search",
    "max_uses" to 5
))

internal fun geminiWebSearchTool(): List<Any> = listOf(mapOf("google_search" to emptyMap<String, Any>()))

/** Bundle of fields extracted from a provider's web-search-tool response.
 *  Each field maps directly to AnalysisResponse.{citations,searchResults,
 *  relatedQuestions} so the existing report UI renders them with no
 *  changes downstream. */
internal data class WebSearchData(
    val citations: List<String>? = null,
    val searchResults: List<SearchResult>? = null,
    val queries: List<String>? = null
)

private fun List<SearchResult>.uniqueByUrl(): List<SearchResult> =
    distinctBy { it.url ?: "${it.name}|${it.snippet}" }

internal fun extractClaudeWebSearch(body: ClaudeResponse?): WebSearchData {
    val blocks = body?.content ?: return WebSearchData()
    val results = mutableListOf<SearchResult>()
    val urls = mutableSetOf<String>()
    blocks.forEach { block ->
        // web_search_tool_result blocks carry the raw hit list.
        block.content?.forEach { item ->
            val u = item.url ?: return@forEach
            results += SearchResult(name = item.title, url = u, snippet = null)
            urls += u
        }
        // text blocks may carry citations pointing back at those hits.
        block.citations?.forEach { c ->
            c.url?.let { urls += it }
            if (c.url != null && results.none { it.url == c.url }) {
                results += SearchResult(name = c.title, url = c.url, snippet = c.cited_text)
            }
        }
    }
    return WebSearchData(
        citations = urls.toList().ifEmpty { null },
        searchResults = results.uniqueByUrl().ifEmpty { null }
    )
}

internal fun extractGeminiWebSearch(body: GeminiResponse?): WebSearchData {
    val gm = body?.candidates?.firstOrNull()?.groundingMetadata ?: return WebSearchData()
    val results = gm.groundingChunks?.mapNotNull { chunk ->
        val w = chunk.web ?: return@mapNotNull null
        val u = w.uri ?: return@mapNotNull null
        SearchResult(name = w.title, url = u, snippet = null)
    }.orEmpty()
    val urls = results.mapNotNull { it.url }
    return WebSearchData(
        citations = urls.distinct().ifEmpty { null },
        searchResults = results.uniqueByUrl().ifEmpty { null },
        queries = gm.webSearchQueries?.takeIf { it.isNotEmpty() }
    )
}

internal fun extractResponsesWebSearch(body: OpenAiResponsesApiResponse?): WebSearchData {
    val output = body?.output ?: return WebSearchData()
    val results = mutableListOf<SearchResult>()
    val urls = mutableSetOf<String>()
    val queries = mutableListOf<String>()
    output.forEach { item ->
        // web_search_call items expose what was searched.
        if (item.type == "web_search_call") {
            item.action?.query?.takeIf { it.isNotBlank() }?.let { queries += it }
        }
        // message items may carry url_citation annotations on each text chunk.
        item.content?.forEach { part ->
            part.annotations?.forEach { ann ->
                if (ann.type == "url_citation" && ann.url != null) {
                    urls += ann.url
                    if (results.none { it.url == ann.url }) {
                        results += SearchResult(name = ann.title, url = ann.url, snippet = null)
                    }
                }
            }
        }
    }
    return WebSearchData(
        citations = urls.toList().ifEmpty { null },
        searchResults = results.uniqueByUrl().ifEmpty { null },
        queries = queries.distinct().ifEmpty { null }
    )
}

internal fun ChatMessage.toGeminiContent(): GeminiContent {
    val role = if (role == "user") "user" else "model"
    val parts = buildList {
        if (imageBase64 != null) {
            add(GeminiPart(inlineData = GeminiInlineData(mimeType = imageMime ?: "image/png", data = imageBase64)))
        }
        if (content.isNotBlank()) add(GeminiPart(text = content))
    }
    return GeminiContent(parts.ifEmpty { listOf(GeminiPart(text = "")) }, role)
}
