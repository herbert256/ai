package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

// Streaming report generation (SSE), split out of ApiDispatch.kt (audit P01).

private enum class StreamingUsageMergeMode { FieldMax, LastComplete }

private suspend fun AnalysisRepository.collectStreamResponse(
    service: AppService,
    response: retrofit2.Response<okhttp3.ResponseBody>,
    extractContent: (String?, String) -> String?,
    extractUsage: (String?, String) -> Pair<TokenUsage?, String?>?,
    isFinalChunk: (String?, String) -> Boolean = { _, _ -> false },
    requireTerminator: Boolean = false,
    usageMergeMode: StreamingUsageMergeMode = StreamingUsageMergeMode.FieldMax,
    onDelta: (String) -> Unit
): AnalysisResponse {
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    if (!response.isSuccessful) {
        val err = try { response.errorBody()?.string() } catch (_: Exception) { null }
        return AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $err", httpHeaders = headers, httpStatusCode = statusCode)
    }
    val body = response.body()
        ?: return AnalysisResponse(service, null, "Empty response body", httpHeaders = headers, httpStatusCode = statusCode)
    val sb = StringBuilder()
    var usage: TokenUsage? = null
    var rawUsage: String? = null
    try {
        parseSseStream(
            body = body,
            extractContent = extractContent,
            isFinalChunk = isFinalChunk,
            requireTerminator = requireTerminator,
            extractUsage = extractUsage
        ) { u, raw ->
            usage = when (usageMergeMode) {
                StreamingUsageMergeMode.FieldMax -> mergeUsage(usage, u)
                StreamingUsageMergeMode.LastComplete -> u
            }
            if (!raw.isNullOrBlank()) rawUsage = raw
        }.collect { chunk -> sb.append(chunk); onDelta(chunk) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Preserve already reported usage if the stream ends abnormally.
        // A paid partial generation must not be silently retried and lost.
        return AnalysisResponse(service, sb.toString().takeIf { it.isNotBlank() },
            "Incomplete stream: ${e.message}", usage, rawUsageJson = rawUsage,
            httpHeaders = headers, httpStatusCode = statusCode,
            generationFailed = sb.isNotEmpty() || usage != null)
    }
    val text = sb.toString().takeIf { it.isNotBlank() }
    return if (text != null)
        AnalysisResponse(service, text, null, usage, rawUsageJson = rawUsage, httpHeaders = headers, httpStatusCode = statusCode)
    else AnalysisResponse(service, null, "No response content", usage, rawUsageJson = rawUsage, httpHeaders = headers, httpStatusCode = statusCode)
}

internal suspend fun AnalysisRepository.streamOpenAiReport(
    service: AppService, apiKey: String, prompt: String, model: String,
    params: AgentParameters?, baseUrl: String, imageBase64: String?, imageMime: String?, onDelta: (String) -> Unit
): AnalysisResponse {
    if (usesResponsesApi(service, model)) return streamResponsesApiReport(service, apiKey, prompt, model, params, baseUrl, imageBase64, imageMime, onDelta)
    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val chatUrl = buildChatUrl(baseUrl, service.chatPath, service.knownEndpointPaths())
    val messages = buildMessages(params?.systemPrompt, prompt, imageBase64, imageMime)
    val request = buildOpenAiRequest(service, model, messages, params, stream = true)
        .copy(stream_options = StreamOptions(include_usage = true))
    val response = api.chatStream(chatUrl, "Bearer $apiKey", request)
    // Reports require final answer content. Reasoning-only output is a failed
    // generation, even if the transport succeeded and usage was billed.
    val ext = OpenAiContentExtractor()
    val resp = collectStreamResponse(
        service,
        response,
        ext::extract,
        extractOpenAiUsage(service),
        isFinalChunk = { _, _ -> ext.finishReason != null },
        requireTerminator = true,
        usageMergeMode = StreamingUsageMergeMode.LastComplete,
        onDelta = onDelta
    )
    return validateOpenAiReportCompletion(resp, ext.finishReason)
}

internal suspend fun AnalysisRepository.streamResponsesApiReport(
    service: AppService, apiKey: String, prompt: String, model: String,
    params: AgentParameters?, baseUrl: String, imageBase64: String?, imageMime: String?, onDelta: (String) -> Unit
): AnalysisResponse {
    val api = ApiFactory.createOpenAiCompatibleApi(baseUrl)
    val responsesUrl = responsesUrlFor(service, baseUrl)
    val input: Any = if (imageBase64 != null) {
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
        model = model, input = input,
        instructions = params?.systemPrompt?.takeIf { it.isNotBlank() },
        stream = true,
        tools = if (params?.webSearchTool == true) responsesWebSearchTool() else null,
        reasoning = reasoningField(service, model, params?.reasoningEffort)
    )
    val response = api.responsesStream(responsesUrl, "Bearer $apiKey", request)
    return collectStreamResponse(
        service,
        response,
        ::extractResponsesApiContent,
        extractResponsesApiUsage(service),
        requireTerminator = true,
        usageMergeMode = StreamingUsageMergeMode.LastComplete,
        onDelta = onDelta
    )
}

internal suspend fun AnalysisRepository.streamAnthropicReport(
    service: AppService, apiKey: String, prompt: String, model: String,
    params: AgentParameters?, imageBase64: String?, imageMime: String?, onDelta: (String) -> Unit
): AnalysisResponse {
    val api = ApiFactory.createClaudeApi(service.baseUrl)
    val userMessage = ChatMessage("user", prompt, imageBase64 = imageBase64, imageMime = imageMime).toClaudeMessage()
    val bundle = claudeReasoningBundle(service, model, params?.reasoningEffort, params?.maxTokens)
    val request = ClaudeRequest(
        model = model, messages = listOf(userMessage), stream = true,
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
    val response = api.createMessageStream(apiKey, request = request)
    return collectStreamResponse(
        service,
        response,
        ::extractClaudeContent,
        extractClaudeUsage,
        requireTerminator = true,
        onDelta = onDelta
    )
}

internal suspend fun AnalysisRepository.streamGeminiReport(
    service: AppService, apiKey: String, prompt: String, model: String,
    params: AgentParameters?, imageBase64: String?, imageMime: String?, onDelta: (String) -> Unit
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
    val response = api.streamGenerateContent(model, apiKey, request = request)
    return collectStreamResponse(
        service,
        response,
        ::extractGeminiContent,
        extractGeminiUsage,
        ::isGeminiFinalChunk,
        requireTerminator = true,
        onDelta = onDelta
    )
}
