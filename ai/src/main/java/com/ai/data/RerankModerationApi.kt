package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Result of an API-level rerank call (Cohere v2/rerank or compatible).
 *  Outcome shape mirrors what the chat-model rerank flow already
 *  produces — content is the structured JSON the rest of the system
 *  reads (HTML export, Top-Ranked scope, etc.). */
data class RerankApiResult(
    val content: String?,           // JSON in the same shape as the chat prompt asks for
    val errorMessage: String?,
    val httpStatusCode: Int? = null,
    val billedSearchUnits: Int? = null,
    val durationMs: Long
)

/** Call the provider's dedicated rerank endpoint with the parent
 *  report's prompt as the query and the per-agent response bodies as
 *  documents, then convert the response into the same
 *  `[{id, rank, score, reason}, ...]` JSON the chat-model rerank flow
 *  uses — so the rest of the system (HTML export, Top-Ranked scope)
 *  doesn't need a second code path.
 *
 *  Routes any provider that declares [AppService.nativeRerankUrl];
 *  others fall through with an explanatory error. Documents are
 *  passed in success-ordered position, so the provider's `index`
 *  (0-based) maps directly to the bracketed [N] id (1-based) the
 *  rest of the app expects. */
suspend fun callRerankApi(
    provider: AppService, apiKey: String, model: String,
    query: String, documents: List<String>
): RerankApiResult {
    val start = System.currentTimeMillis()
    val url = provider.nativeRerankUrl
    return if (url != null) {
        callNativeRerank(url, apiKey, model, query, documents, start)
    } else {
        RerankApiResult(
            content = null,
            errorMessage = "Rerank API not wired for provider ${provider.id}. Pick a chat model instead, or open an issue to add ${provider.id} rerank support.",
            durationMs = System.currentTimeMillis() - start
        )
    }
}

/** Outcome of a single moderation call. [content] is structured JSON of
 *  the form `[{id: N, flagged: bool, categories: {…}, scores: {…}}, …]`
 *  — one object per input. The renderer parses this in the detail
 *  screen and tabulates flags. [tokenUsage] mirrors the chat token
 *  trio so the caller can attribute cost the same way other meta runs
 *  do — Mistral's /v1/moderations response includes prompt_tokens /
 *  completion_tokens / total_tokens just like the chat endpoints. */
data class ModerationApiResult(
    val content: String?,
    val errorMessage: String?,
    val httpStatusCode: Int? = null,
    val durationMs: Long,
    val tokenUsage: TokenUsage? = null
)

/** Per-input moderation classification. Used by both the chat-input
 *  validator and the Meta moderation flow; the latter aggregates many
 *  of these into a [ModerationApiResult.content] JSON array. */
data class ModerationInputResult(
    val flagged: Boolean,
    val categories: Map<String, Boolean>,
    val scores: Map<String, Double>
) {
    /** Categories that fired (`true` in `categories`). Convenience for
     *  the dialog / row label so callers don't repeat the filter. */
    val firedCategories: List<String> get() = categories.filterValues { it }.keys.toList()
}

/** Call the provider's moderation endpoint on each entry in [inputs].
 *  Routes any provider that declares [AppService.nativeModerationUrl];
 *  others fall through with an explanatory error so the user learns
 *  to pick a moderation-capable model. The result list is
 *  index-aligned with [inputs]. */
suspend fun callModerationApi(
    provider: AppService, apiKey: String, model: String,
    inputs: List<String>
): Pair<List<ModerationInputResult>?, ModerationApiResult> {
    val start = System.currentTimeMillis()
    val url = provider.nativeModerationUrl
    return if (url != null) {
        callNativeModeration(url, apiKey, model, inputs, start)
    } else {
        val err = ModerationApiResult(
            content = null,
            errorMessage = "Moderation API not wired for provider ${provider.id}. Pick a Mistral moderation model instead, or open an issue to add ${provider.id} moderation support.",
            durationMs = System.currentTimeMillis() - start
        )
        null to err
    }
}

private suspend fun callNativeModeration(
    url: String, apiKey: String, model: String, inputs: List<String>, start: Long
): Pair<List<ModerationInputResult>?, ModerationApiResult> {
    return try {
        val api = ApiFactory.createMistralModerationApi()
        val response = api.moderate(url, "Bearer $apiKey", MistralModerationRequest(model, inputs))
        val duration = System.currentTimeMillis() - start
        if (!response.isSuccessful) {
            val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            return null to ModerationApiResult(
                content = null,
                errorMessage = "${response.code()} ${response.message()}: ${errBody ?: ""}",
                httpStatusCode = response.code(),
                durationMs = duration
            )
        }
        val body = response.body() ?: return null to ModerationApiResult(
            content = null, errorMessage = "Empty moderation response", httpStatusCode = response.code(), durationMs = duration
        )
        if (body.results.isNullOrEmpty()) {
            return null to ModerationApiResult(
                content = null,
                errorMessage = body.message ?: "Moderation returned no results",
                httpStatusCode = response.code(), durationMs = duration
            )
        }
        val results = body.results.map { r ->
            val cats = r.categories.orEmpty()
            ModerationInputResult(
                flagged = cats.any { it.value },
                categories = cats,
                scores = r.category_scores.orEmpty()
            )
        }
        val json = moderationResultsToJson(results)
        // Mistral returns token usage on a successful moderation call —
        // lift it into TokenUsage so cost attribution matches the
        // chat-driven meta runs. Falls through to null when the field
        // is missing (older API versions or local proxies).
        val tu = body.usage?.let { u ->
            val inT = u.prompt_tokens ?: 0
            val outT = u.completion_tokens ?: 0
            if (inT == 0 && outT == 0) null
            else TokenUsage(inputTokens = inT, outputTokens = outT)
        }
        results to ModerationApiResult(
            content = json,
            errorMessage = null,
            httpStatusCode = response.code(),
            durationMs = duration,
            tokenUsage = tu
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null to ModerationApiResult(
            content = null,
            errorMessage = "Moderation call failed: ${e.message ?: e.javaClass.simpleName}",
            durationMs = System.currentTimeMillis() - start
        )
    }
}

/** Encode the moderation outcomes as JSON the detail screen parses
 *  back. id = 1-based index of the input (so the bracketed [N] tags
 *  the rest of the pipeline already uses align). */
private fun moderationResultsToJson(results: List<ModerationInputResult>): String {
    val arr = com.google.gson.JsonArray()
    results.forEachIndexed { idx, r ->
        val obj = com.google.gson.JsonObject().apply {
            addProperty("id", idx + 1)
            addProperty("flagged", r.flagged)
            val catsObj = com.google.gson.JsonObject()
            r.categories.forEach { (k, v) -> catsObj.addProperty(k, v) }
            add("categories", catsObj)
            val scoresObj = com.google.gson.JsonObject()
            r.scores.forEach { (k, v) -> scoresObj.addProperty(k, v) }
            add("scores", scoresObj)
        }
        arr.add(obj)
    }
    return createAppGson(prettyPrint = true).toJson(arr)
}

private suspend fun callNativeRerank(
    url: String, apiKey: String, model: String, query: String, documents: List<String>, start: Long
): RerankApiResult {
    return try {
        val api = ApiFactory.createCohereRerankApi()
        val request = CohereRerankRequest(model, query, documents, top_n = documents.size)
        val response = api.rerank(url, "Bearer $apiKey", request)
        val duration = System.currentTimeMillis() - start
        if (!response.isSuccessful) {
            val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            return RerankApiResult(
                content = null,
                errorMessage = "${response.code()} ${response.message()}: ${errBody ?: ""}",
                httpStatusCode = response.code(),
                durationMs = duration
            )
        }
        val body = response.body() ?: return RerankApiResult(
            content = null, errorMessage = "Empty rerank response", httpStatusCode = response.code(), durationMs = duration
        )
        if (body.results.isNullOrEmpty()) {
            return RerankApiResult(
                content = null,
                errorMessage = body.message ?: "Rerank returned no results",
                httpStatusCode = response.code(), durationMs = duration
            )
        }
        val json = rerankResultsToJson(body.results)
        RerankApiResult(
            content = json,
            errorMessage = null,
            httpStatusCode = response.code(),
            billedSearchUnits = body.meta?.billed_units?.search_units,
            durationMs = duration
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Don't swallow cancellation — let structured concurrency
        // unwind without persisting a fake "failed" placeholder.
        // (Same shape as the moderation / chat paths.)
        throw e
    } catch (e: Exception) {
        RerankApiResult(
            content = null,
            errorMessage = "Rerank call failed: ${e.message ?: e.javaClass.simpleName}",
            durationMs = System.currentTimeMillis() - start
        )
    }
}

/** Convert a list of (index, relevance_score) into the structured JSON
 *  the rest of the secondary-result pipeline already parses. Higher
 *  relevance gets a lower rank number (rank 1 = most relevant). Score
 *  is rescaled to 0-100 for parity with the chat-model rerank prompt. */
private fun rerankResultsToJson(results: List<CohereRerankResult>): String {
    val sorted = results.sortedByDescending { it.relevance_score }
    val arr = com.google.gson.JsonArray()
    sorted.forEachIndexed { rank, r ->
        val obj = com.google.gson.JsonObject().apply {
            addProperty("id", r.index + 1)
            addProperty("rank", rank + 1)
            addProperty("score", Math.round(r.relevance_score * 100).toInt().coerceIn(0, 100))
            addProperty("reason", "Relevance score: %.4f".format(r.relevance_score))
        }
        arr.add(obj)
    }
    return createAppGson(prettyPrint = true).toJson(arr)
}

/** Parse the structured JSON output of a rerank result and return the
 *  original [N] ids of the top [count] entries (sorted by `rank`). The
 *  prompt asks for `[{"id":N,"rank":R, ...}, ...]`; ``` fences are
 *  tolerated. Returns null if the payload doesn't deserialize cleanly. */
fun extractTopRankedIds(rerankContent: String?, count: Int): List<Int>? {
    if (rerankContent.isNullOrBlank() || count <= 0) return null
    val cleaned = rerankContent.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = try {
        com.google.gson.JsonParser.parseString(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { return null } ?: return null
    if (arr.size() == 0) return null

    data class Entry(val id: Int, val rank: Int?)
    val entries = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val rank = obj.get("rank")?.takeIf { it.isJsonPrimitive }?.asInt
        Entry(id, rank)
    }
    if (entries.isEmpty()) return null
    // Prefer ranked entries when we have any. Without this guard,
    // a payload missing the rank field on every row used to sort
    // every Entry as MAX_VALUE — turning sortedBy into a no-op
    // that returned the model's response order, NOT the rank
    // order. If at least one rank is present, surface only ranked
    // entries (the model intended those to win); otherwise keep
    // the model's emit order.
    val ranked = entries.filter { it.rank != null }
    return if (ranked.isNotEmpty()) {
        ranked.sortedBy { it.rank!! }.take(count).map { it.id }
    } else {
        entries.take(count).map { it.id }
    }
}
