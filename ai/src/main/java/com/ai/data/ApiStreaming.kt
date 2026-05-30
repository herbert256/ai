package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody


// ============================================================================
// Shared SSE reader — parses lines, extracts content via format-specific lambda
// ============================================================================

internal fun parseSseStream(
    body: ResponseBody,
    extractContent: (eventType: String?, data: String) -> String?,
    isFinalChunk: (eventType: String?, data: String) -> Boolean = { _, _ -> false },
    // Optional usage side-channel for the streaming-report path. When set,
    // every event is also offered to [extractUsage]; any TokenUsage it
    // returns is handed to [onUsage] (which merges across events). Chat
    // callers leave both null and see the unchanged content-only Flow.
    extractUsage: ((eventType: String?, data: String) -> Pair<TokenUsage?, String?>?)? = null,
    onUsage: ((TokenUsage, String?) -> Unit)? = null
): Flow<String> = flow {
    // Always decode as UTF-8 — body.charStream() consults the
    // Content-Type charset, but provider servers often omit it on
    // SSE streams and OkHttp falls back to ISO-8859-1, mangling
    // multi-byte characters (anything non-ASCII in the response
    // text) in the parsed event payload.
    AppLog.d("SSE", "stream open")
    val parseStartMs = System.currentTimeMillis()
    var chunkCount = 0
    val reader = java.io.InputStreamReader(body.byteStream(), Charsets.UTF_8).buffered()
    try {
        // Per the W3C SSE spec, an event is delimited by a blank line.
        // Multiple `data:` lines inside one event must be concatenated
        // with `\n` and dispatched together on the blank line. Dispatching
        // each `data:` line eagerly (the previous behaviour) split JSON
        // payloads that some providers shard across two lines, so the
        // per-format `extractContent` parser would silently fail.
        var eventType: String? = null
        val dataBuf = StringBuilder()
        var sawTerminator = false
        var sawAnyData = false

        suspend fun dispatch() {
            if (dataBuf.isEmpty()) {
                eventType = null
                return
            }
            // Trailing newline shouldn't leak into the parser.
            val data = dataBuf.toString().removeSuffix("\n")
            dataBuf.setLength(0)
            if (data.equals("[DONE]", ignoreCase = true)) {
                AppLog.v("SSE", "[DONE] terminator (event=$eventType)")
                sawTerminator = true; eventType = null; return
            }
            sawAnyData = true
            if (extractUsage != null && onUsage != null) {
                try {
                    extractUsage(eventType, data)?.let { (u, raw) -> if (u != null) onUsage(u, raw) }
                } catch (_: Exception) { /* usage is best-effort — never break the content stream */ }
            }
            val content = extractContent(eventType, data)
            // Per-chunk TRACE: log the event-type tag and payload size
            // (not the payload itself — that would duplicate the trace
            // file and leak content). Skip when nothing extracted, that
            // narrows the noise to chunks that actually carry data.
            if (!content.isNullOrEmpty()) {
                chunkCount++
                AppLog.v("SSE", "chunk event=${eventType ?: "(none)"} dataBytes=${data.length} contentBytes=${content.length}")
                emit(content)
            }
            if (isFinalChunk(eventType, data)) {
                AppLog.v("SSE", "final chunk (event=$eventType)")
                sawTerminator = true
            }
            eventType = null
        }

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue
            if (currentLine.isBlank()) { dispatch(); continue }
            if (currentLine.startsWith("event:")) {
                eventType = currentLine.removePrefix("event:").trim()
                // Per-format terminator events. Anthropic ends with
                // `event: message_stop`; OpenAI's Responses API ends
                // with `event: response.completed` (and may or may not
                // also send a trailing `data: [DONE]` for back-compat
                // depending on which Responses API revision the
                // upstream is on — recognising the event keeps us
                // correct either way).
                if (eventType == "message_stop"
                    || eventType == "response.completed") sawTerminator = true
                continue
            }
            if (currentLine.startsWith(":")) continue  // SSE comment
            if (currentLine.startsWith("data:")) {
                val chunk = currentLine.removePrefix("data:").let {
                    // SSE permits but doesn't require a leading space
                    // after the colon; spec strips one if present.
                    if (it.startsWith(" ")) it.substring(1) else it
                }
                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                dataBuf.append(chunk)
            }
        }
        // Flush a trailing event that ended via TCP close (no blank line).
        dispatch()
        // Many OpenAI-compatible clones (vLLM/Ollama-compat, some proxies)
        // end a stream by simply closing the socket after the last delta,
        // with no `data: [DONE]` / `message_stop` / `response.completed`
        // terminator. Treat a clean EOF as valid as long as at least one
        // content chunk was emitted — only flag truncation when data arrived
        // but produced no content at all (Bug 2).
        if (!sawTerminator && sawAnyData && chunkCount == 0) {
            throw java.io.IOException("SSE stream ended without terminator — response likely truncated")
        }
    } finally {
        try { reader.close() } catch (_: Exception) {}
        body.close()
        AppLog.d("SSE", "stream closed — $chunkCount chunks in ${System.currentTimeMillis() - parseStartMs}ms")
    }
}

/** Gemini SSE terminator: a candidate chunk with a non-null `finishReason`. Gemini doesn't
 *  send a [DONE] line — it just closes the connection after this chunk. */
internal fun isGeminiFinalChunk(@Suppress("UNUSED_PARAMETER") eventType: String?, data: String): Boolean {
    return try {
        val obj = gson.fromJson(data, com.google.gson.JsonObject::class.java) ?: return false
        val candidates = obj.getAsJsonArray("candidates") ?: return false
        candidates.any { c -> c.asJsonObject?.get("finishReason")?.takeIf { !it.isJsonNull } != null }
    } catch (_: Exception) { false }
}

// ============================================================================
// Format-specific content extractors
// ============================================================================

private val gson = createAppGson()

// ============================================================================
// Usage extractors — recover exact token counts from the SSE stream so the
// streaming-report path keeps cost as accurate as the non-streaming call.
// Each returns (TokenUsage, rawUsageJson) for events that carry usage, else null.
// ============================================================================

/** OpenAI Chat Completions: the trailing include_usage chunk carries `usage`. */
internal fun extractOpenAiUsage(service: AppService): (String?, String) -> Pair<TokenUsage?, String?>? = { _, data ->
    try {
        gson.fromJson(data, OpenAiStreamChunk::class.java)?.usage
            ?.let { it.toTokenUsage(service) to gson.toJson(it) }
    } catch (_: Exception) { null }
}

/** OpenAI Responses API: the `response.completed` event holds response.usage. */
internal val extractResponsesApiUsage: (String?, String) -> Pair<TokenUsage?, String?>? = fn@{ eventType, data ->
    if (eventType != "response.completed") return@fn null
    try {
        val usageObj = gson.fromJson(data, com.google.gson.JsonObject::class.java)
            ?.getAsJsonObject("response")?.getAsJsonObject("usage") ?: return@fn null
        gson.fromJson(usageObj, OpenAiUsage::class.java)?.toTokenUsage() to usageObj.toString()
    } catch (_: Exception) { null }
}

/** Anthropic: input (+cache) on message_start, output on message_delta. */
internal val extractClaudeUsage: (String?, String) -> Pair<TokenUsage?, String?>? = fn@{ eventType, data ->
    try {
        val ev = gson.fromJson(data, ClaudeStreamEvent::class.java) ?: return@fn null
        when (eventType) {
            "message_start" -> ev.message?.usage?.let { it.toTokenUsage() to gson.toJson(it) }
            "message_delta" -> ev.usage?.let { it.toTokenUsage() to gson.toJson(it) }
            else -> null
        }
    } catch (_: Exception) { null }
}

/** Gemini: usageMetadata rides along on chunks (cumulative; keep the latest). */
internal val extractGeminiUsage: (String?, String) -> Pair<TokenUsage?, String?>? = { _, data ->
    try {
        gson.fromJson(data, GeminiStreamChunk::class.java)?.usageMetadata
            ?.let { it.toTokenUsage() to gson.toJson(it) }
    } catch (_: Exception) { null }
}

/** Merge usage observed across SSE events by field-wise max (handles
 *  Anthropic's split input/output events and Gemini's cumulative chunks;
 *  a single complete event like OpenAI's final chunk merges with the
 *  null seed to itself). apiCost / rawJson take the latest non-null. */
internal fun mergeUsage(a: TokenUsage?, b: TokenUsage): TokenUsage {
    if (a == null) return b
    return TokenUsage(
        inputTokens = maxOf(a.inputTokens, b.inputTokens),
        outputTokens = maxOf(a.outputTokens, b.outputTokens),
        apiCost = b.apiCost ?: a.apiCost,
        cachedInputTokens = maxOf(a.cachedInputTokens, b.cachedInputTokens),
        cacheCreationTokens = maxOf(a.cacheCreationTokens, b.cacheCreationTokens),
        reasoningTokens = maxOf(a.reasoningTokens, b.reasoningTokens)
    )
}

/** OpenAI Chat Completions SSE: data contains choices[0].delta.content */
internal fun extractOpenAiContent(eventType: String?, data: String): String? {
    return try {
        val chunk = gson.fromJson(data, OpenAiStreamChunk::class.java)
        val delta = chunk?.choices?.firstOrNull()?.delta
        delta?.content?.takeIf { it.isNotEmpty() }
            ?: delta?.reasoning_content?.takeIf { it.isNotEmpty() }
            ?: delta?.reasoning?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

/** OpenAI Responses API SSE: event=response.output_text.delta, data.delta contains text */
internal fun extractResponsesApiContent(eventType: String?, data: String): String? {
    if (eventType != "response.output_text.delta") return null
    return try {
        gson.fromJson(data, com.google.gson.JsonObject::class.java)?.get("delta")?.asString?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

/** Anthropic SSE: event=content_block_delta, data.delta.text */
internal fun extractClaudeContent(eventType: String?, data: String): String? {
    if (eventType == "message_stop") return null  // Signal end (handled by [DONE] or stream close)
    if (eventType != "content_block_delta") return null
    return try {
        gson.fromJson(data, ClaudeStreamEvent::class.java)?.delta?.text?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

/** Gemini SSE: data contains candidates[0].content.parts[0].text */
internal fun extractGeminiContent(eventType: String?, data: String): String? {
    return try {
        gson.fromJson(data, GeminiStreamChunk::class.java)
            ?.candidates?.firstOrNull()?.content?.parts
            ?.mapNotNull { it.text }
            ?.joinToString(separator = "")
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

