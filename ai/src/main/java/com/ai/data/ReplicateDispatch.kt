package com.ai.data

/**
 * Replicate (`ApiFormat.REPLICATE`) runs models through its **asynchronous
 * predictions API**, not an OpenAI chat endpoint. A run is
 * `POST <baseUrl>/models/{owner}/{name}/predictions` with `Prefer: wait`,
 * which blocks until the prediction completes and returns it inline — so the
 * app's one-call dispatch fits without a separate poll loop. LLM `output` is
 * an array of token strings to join; token counts come from `metrics`.
 *
 * Scope: text prompt only (no vision / embeddings). Reports are single-turn
 * so [analyzeReplicate] is the main path; chat flattens the turns into one
 * prompt; the streaming-report path runs the same synchronous call and emits
 * the whole answer as one chunk.
 */

private suspend fun AnalysisRepository.replicateGenerate(
    service: AppService, apiKey: String, model: String,
    prompt: String, system: String?, maxTokens: Int?, temperature: Double?, topP: Double?
): AnalysisResponse {
    val api = ApiFactory.createReplicateApi(service.baseUrl)
    // baseUrl is https://api.replicate.com/v1/ ; model is owner/name.
    val url = "${service.baseUrl.trimEnd('/')}/models/$model/predictions"
    val request = ReplicatePredictionRequest(
        input = ReplicateInput(
            prompt = prompt,
            system_prompt = system?.takeIf { it.isNotBlank() },
            max_tokens = maxTokens ?: 1024,
            temperature = temperature,
            top_p = topP
        )
    )
    val response = api.createPrediction(url, "Bearer $apiKey", "wait", request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val usage = body?.metrics?.toTokenUsage()
        val content = body?.outputText()
        when {
            body != null && (body.error != null || body.status == "failed") ->
                AnalysisResponse(service, null, body.error ?: "Replicate prediction failed", usage,
                    httpHeaders = headers, httpStatusCode = statusCode)
            content != null ->
                AnalysisResponse(service, content, null, usage, httpHeaders = headers, httpStatusCode = statusCode)
            else ->
                // status still "processing" means the Prefer: wait window
                // elapsed before the model finished — surface it rather than
                // silently returning empty.
                AnalysisResponse(service, null, "No output (status=${body?.status ?: "unknown"})", usage,
                    httpHeaders = headers, httpStatusCode = statusCode)
        }
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody",
            httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal suspend fun AnalysisRepository.analyzeReplicate(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?,
    @Suppress("UNUSED_PARAMETER") imageBase64: String? = null,
    @Suppress("UNUSED_PARAMETER") imageMime: String? = null
): AnalysisResponse = replicateGenerate(
    service, apiKey, model, prompt,
    system = params?.systemPrompt,
    maxTokens = params?.maxTokens,
    temperature = params?.temperature?.toDouble(),
    topP = params?.topP?.toDouble()
)

internal suspend fun AnalysisRepository.chatReplicateResponse(
    service: AppService, apiKey: String, model: String, messages: List<ChatMessage>, params: ChatParameters
): AnalysisResponse {
    val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }.ifBlank { null }
    val convo = messages.filter { it.role != "system" }.joinToString("\n\n") { "${it.role}: ${it.content}" }
    return replicateGenerate(
        service, apiKey, model, convo,
        system = params.systemPrompt.ifBlank { null } ?: system,
        maxTokens = params.maxTokens,
        temperature = params.temperature?.toDouble(),
        topP = params.topP?.toDouble()
    )
}

/** Replicate has no chat-model listing — its `/v1/models` is the entire public
 *  catalog. The curated chat ids ship as [AppService.hardcodedModels]. */
internal fun AnalysisRepository.fetchModelsReplicate(
    service: AppService, @Suppress("UNUSED_PARAMETER") apiKey: String
): FetchedModels = FetchedModels(service.hardcodedModels ?: emptyList(), emptyMap())

/** Streaming-report path: Replicate's Prefer: wait call is synchronous, so run
 *  it and emit the whole answer once. */
internal suspend fun AnalysisRepository.streamReplicateReport(
    service: AppService, apiKey: String, prompt: String, model: String, params: AgentParameters?,
    imageBase64: String?, imageMime: String?, onDelta: (String) -> Unit
): AnalysisResponse {
    val resp = analyzeReplicate(service, apiKey, prompt, model, params, imageBase64, imageMime)
    resp.analysis?.takeIf { it.isNotEmpty() }?.let { onDelta(it) }
    return resp
}
