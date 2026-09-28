package com.ai.data

/**
 * Replicate (`ApiFormat.REPLICATE`) runs models through its **asynchronous
 * predictions API**, not an OpenAI chat endpoint. A run is
 * `POST <baseUrl>/models/{owner}/{name}/predictions` with `Prefer: wait`,
 * which holds the call for up to ~60 s and returns the prediction inline. A
 * prediction still `starting` / `processing` after that (its `output` may
 * already hold a partial answer) is polled via `urls.get` until it ends; one
 * the app gives up on (Stop, timeout, poll failure) is cancelled via
 * `urls.cancel` so it doesn't keep running — and billing — unseen. Only a
 * `succeeded` prediction counts as an answer. LLM `output` is an array of
 * token strings to join; token counts come from `metrics`.
 *
 * Scope: text prompt only (no vision / embeddings). Reports are single-turn
 * so [analyzeReplicate] is the main path; chat flattens the turns into one
 * prompt; the streaming-report path runs the same synchronous call and emits
 * the whole answer as one chunk.
 */

private val REPLICATE_TERMINAL_STATUSES = setOf("succeeded", "failed", "canceled", "aborted")
private const val REPLICATE_POLL_INTERVAL_MS = 3_000L

private suspend fun AnalysisRepository.replicateGenerate(
    service: AppService, apiKey: String, model: String,
    prompt: String, system: String?, maxTokens: Int?, temperature: Double?, topP: Double?
): AnalysisResponse {
    val startedMs = System.currentTimeMillis()
    val api = ApiFactory.createReplicateApi(service.baseUrl)
    val auth = "Bearer $apiKey"
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
    val response = api.createPrediction(url, auth, "wait", request)
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    if (!response.isSuccessful) {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        return AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody",
            httpHeaders = headers, httpStatusCode = statusCode)
    }
    val created = response.body()
        ?: return AnalysisResponse(service, null, "Empty Replicate response", httpHeaders = headers, httpStatusCode = statusCode)
    val body = if (created.status in REPLICATE_TERMINAL_STATUSES) created
        else awaitReplicatePrediction(service, api, auth, created, startedMs)
    val usage = body.metrics?.toTokenUsage()
    val content = body.outputText()
    return when {
        body.status == "succeeded" && content != null ->
            AnalysisResponse(service, content, null, usage, httpHeaders = headers, httpStatusCode = statusCode)
        // A finished run without text is paid-for but unusable: no retry.
        body.status == "succeeded" ->
            AnalysisResponse(service, null, "Replicate prediction succeeded without output", usage,
                httpHeaders = headers, httpStatusCode = statusCode, generationFailed = true)
        body.status == "failed" || body.error != null ->
            AnalysisResponse(service, null, body.error ?: "Replicate prediction failed", usage,
                httpHeaders = headers, httpStatusCode = statusCode)
        body.status in REPLICATE_TERMINAL_STATUSES ->
            AnalysisResponse(service, null, "Replicate prediction ${body.status}", usage,
                httpHeaders = headers, httpStatusCode = statusCode)
        // Still running when the budget ran out: it was cancelled, and any
        // partial output is not a complete answer. Retrying would start
        // another equally long run, so it counts as a failed generation.
        else -> AnalysisResponse(service, null,
            "Replicate prediction did not finish within ${replicateBudgetMs() / 1000}s " +
                "(status=${body.status ?: "unknown"}); it was cancelled.", usage,
            httpHeaders = headers, httpStatusCode = statusCode, generationFailed = true)
    }
}

/** Poll/wait budget for one prediction — the configured non-streaming read
 *  timeout, measured from the initial POST. */
private fun replicateBudgetMs(): Long =
    (NetworkSettings.nonStreamingReadTimeoutSec.takeIf { it > 0 } ?: 120) * 1000L

/** Poll a still-running prediction until it reaches a terminal status or the
 *  budget runs out. Every exit that leaves it running (budget, Stop, a thrown
 *  poll) cancels it on the provider so no unseen prediction keeps billing. */
private suspend fun AnalysisRepository.awaitReplicatePrediction(
    service: AppService, api: ReplicateApi, auth: String,
    first: ReplicatePredictionResponse, startedMs: Long
): ReplicatePredictionResponse {
    val getUrl = first.urls?.getUrl ?: first.id?.let { "${service.baseUrl.trimEnd('/')}/predictions/$it" } ?: return first
    val cancelUrl = first.urls?.cancelUrl ?: "$getUrl/cancel"
    var current = first
    try {
        while (current.status !in REPLICATE_TERMINAL_STATUSES &&
            System.currentTimeMillis() - startedMs < replicateBudgetMs()) {
            kotlinx.coroutines.delay(REPLICATE_POLL_INTERVAL_MS)
            val polled = api.getPrediction(getUrl, auth)
            if (polled.isSuccessful) polled.body()?.let { current = it }
            else {
                // A transient poll error: keep polling within the budget.
                try { polled.errorBody()?.close() } catch (_: Exception) {}
                AppLog.w("Replicate", "poll ${polled.code()} for ${first.id}; retrying")
            }
        }
        return current
    } finally {
        if (current.status !in REPLICATE_TERMINAL_STATUSES) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                        api.cancelPrediction(cancelUrl, auth).body()?.close()
                    }
                    AppLog.i("Replicate", "cancelled unfinished prediction ${first.id} (status=${current.status})")
                } catch (e: Exception) {
                    AppLog.w("Replicate", "cancel of prediction ${first.id} failed: ${e.message}")
                }
            }
        }
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
