package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

// Request builders, response parsers, message converters, reasoning /
// web-search fields — split out of ApiDispatch.kt (audit P01).

internal fun buildMessages(
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
        // Fall back to a bounded default so balance-gating providers
        // (OpenRouter) don't pre-authorise the model's whole output
        // window — see [defaultMaxTokens].
        max_tokens = params?.maxTokens ?: defaultMaxTokens(service, model),
        temperature = params?.temperature,
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

internal fun AnalysisRepository.parseOpenAiAnalysisResponse(service: AppService, response: retrofit2.Response<OpenAiResponse>): AnalysisResponse {
    val headers = formatHeaders(response.headers())
    val statusCode = response.code()
    return if (response.isSuccessful) {
        val body = response.body()
        val content = body?.choices?.let { choices ->
            choices.firstOrNull()?.message?.contentAsString()
                ?: choices.firstOrNull()?.message?.reasoning_content
                ?: choices.firstOrNull()?.message?.reasoning
                ?: choices.firstNotNullOfOrNull { it.message?.contentAsString() }
                ?: choices.firstNotNullOfOrNull { it.message?.reasoning_content }
                ?: choices.firstNotNullOfOrNull { it.message?.reasoning }
        }
        val rawUsageJson = formatUsageJson(body?.usage)
        val usage = body?.usage?.toTokenUsage(service)
        if (!content.isNullOrBlank()) AnalysisResponse(service, content, null, usage,
            citations = body.citations, searchResults = body.search_results, relatedQuestions = body.related_questions,
            rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
        // Pass `usage` on the empty-content branch too — a reasoning
        // model that burned all max_tokens on encrypted reasoning
        // (OpenAI o-series via OpenRouter) still reports
        // completion_tokens. The "Test all models" probe treats 200 +
        // outputTokens > 0 as reachable instead of a hard failure.
        else AnalysisResponse(service, null, body?.error?.message ?: "No response content", usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
    } else {
        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        AnalysisResponse(service, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
    }
}

internal fun extractResponsesApiContent(body: OpenAiResponsesApiResponse?): String? {
    val outputs = body?.output ?: return null
    // Concatenate every text-bearing block in order. Responses API can
    // emit multiple output_text chunks per message (pre-tool-use text
    // → tool call → post-tool text). Returning only the first chunk
    // dropped the post-tool reply entirely. Mirrors analyzeAnthropic's
    // joinToString of every text content item.
    val joined = outputs.flatMap { it.content ?: emptyList() }
        .filter { it.type == "output_text" || it.type == "text" }
        .mapNotNull { it.text }
        .joinToString("")
    if (joined.isNotEmpty()) return joined
    // Fall back to the previous lookup chain when no typed text block
    // matched — handles older / minor-revision Responses API shapes.
    return outputs.firstOrNull()?.content?.firstNotNullOfOrNull { it.text }
        ?: outputs.firstOrNull { it.type == "message" }?.content?.firstNotNullOfOrNull { it.text }
        ?: outputs.flatMap { it.content ?: emptyList() }.firstNotNullOfOrNull { it.text }
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
    // Require a `/` boundary (Bug 79): the bare `endsWith(cleanedChatPath)`
    // branch matched a base whose path merely ends with the chat-path
    // substring (e.g. ".../myv1/chat/completions" vs "v1/chat/completions"),
    // wrongly treating it as already-terminated.
    if (trimmedUrl.endsWith("/$cleanedChatPath")) {
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
/** OpenAI-compatible chat responses sometimes ship empty / whitespace
 *  `content` alongside the real reply in `reasoning_content` /
 *  `reasoning` — reasoning models that exhausted `max_tokens` during
 *  thinking, strict OpenAI-clone servers that emit empty content when
 *  there's nothing to say, or providers (e.g. OpenRouter reka-flash-3)
 *  that pad with a single space. Treat blank the same as null so the
 *  caller's `?:` chain falls through to the reasoning fallbacks. */
internal fun OpenAiMessage.contentAsString(): String? = when (val c = content) {
    is String -> c.takeIf { it.isNotBlank() }
    null -> null
    else -> c.toString().takeIf { it.isNotBlank() }
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
 *  there even though the badge stays on. The resolution chain (Settings
 *  reference if published, else catalog-only fallback with the xAI gate)
 *  and the deciding layer now live in [ModelCapabilityResolver]. */
internal fun isReasoningCapableForDispatch(service: AppService, model: String): Boolean =
    ModelCapabilityResolver.acceptsReasoningEffortParam(service, model)

/** True when [model] requires the adaptive-thinking request shape
 *  (`thinking.type:"adaptive"` + `output_config.effort`) per the
 *  provider's [AppService.adaptiveThinkingPatterns]. Older models
 *  still use the budget_tokens shape. */
private fun claudeUsesAdaptiveThinking(service: AppService, model: String): Boolean =
    service.adaptiveThinkingPatterns.anyMatches(model)

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
    if (claudeUsesAdaptiveThinking(service, model)) return mapOf("type" to "adaptive")
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
    if (!claudeUsesAdaptiveThinking(service, model)) return null
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
    val baseMax = requestedMax ?: defaultMaxTokens(service, model)
    val budget = (thinking?.get("budget_tokens") as? Int) ?: 0
    // Anthropic 400s when max_tokens <= budget_tokens — give the
    // response some additional headroom on top of the thinking budget.
    val effectiveMax = if (budget > 0 && baseMax <= budget) budget + 4096 else baseMax
    if (effectiveMax != baseMax && requestedMax != null) {
        // Log the silent override so a user who set an explicit
        // max_tokens cap notices their cap was raised instead of
        // discovering it via a surprise cost spike. The trace also
        // captures the final request body, so this Log + the
        // captured body are both visible in API Traces.
        AppLog.w("ApiDispatch",
            "Anthropic reasoning override: max_tokens raised from $baseMax to $effectiveMax (thinking budget=$budget)")
    }
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
