package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class SecondaryKind { RERANK, SUMMARIZE, COMPARE, MODERATION, TRANSLATE }

/**
 * A meta-result that operates on a parent Report's per-agent outputs:
 * a Rerank ranks them, a Summarize fuses them into one. Each is the work
 * of a single chosen model, persisted independently so a report can
 * accumulate many of either kind over time.
 */
data class SecondaryResult(
    val id: String,
    val reportId: String,
    val kind: SecondaryKind,
    val providerId: String,
    val model: String,
    val agentName: String,
    val timestamp: Long,
    val content: String?,
    val errorMessage: String? = null,
    val tokenUsage: TokenUsage? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null,
    /** When kind == TRANSLATE: identifier of the item this translation
     *  operated on. "prompt" for the report prompt, agent.agentId for
     *  an agent response, secondary.id for a Summary or Compare. Null
     *  on non-translate rows. */
    val translateSourceTargetId: String? = null,
    /** Companion to [translateSourceTargetId]. One of "PROMPT", "AGENT",
     *  "SUMMARY", "COMPARE". Null on non-translate rows. */
    val translateSourceKind: String? = null,
    /** Target language for a TRANSLATE row, English name (e.g. "Dutch",
     *  "German"). Used to group translated content into per-language
     *  views in the report viewer / Complete HTML / Zipped HTML. Null
     *  on non-translate rows. */
    val targetLanguage: String? = null,
    /** Companion to [targetLanguage] — native rendering for the picker
     *  ("Nederlands", "Deutsch"). Null on non-translate rows. */
    val targetLanguageNative: String? = null,
    /** UUID shared by every TRANSLATE row produced by a single Translate
     *  invocation, so the result page can render one aggregate "run"
     *  row per click and let the user drill into the individual API
     *  calls. Null on non-translate rows and on legacy rows saved
     *  before this field existed (those fall back to grouping-by-
     *  language for the same effect). */
    val translationRunId: String? = null,
    /** Legacy field — set by the old "translation creates a copy" flow
     *  on Summary / Compare secondaries that were duplicated onto a
     *  translated report. New translations don't fork the report so
     *  this stays null on every TRANSLATE row written by the current
     *  code path; preserved on disk so old reports still load. */
    val translatedFromSecondaryId: String? = null
)

/**
 * Persists [SecondaryResult]s as <filesDir>/secondary/<reportId>/<resultId>.json.
 * Mirrors [ReportStorage]'s init/lock pattern. The per-report subdirectory keeps
 * deletion of the parent report (and its meta-results) a single rmdir.
 */
object SecondaryResultStorage {
    private const val SECONDARY_DIR = "secondary"
    private val gson = createAppGson()
    private val lock = ReentrantLock()
    @Volatile private var rootDir: File? = null

    fun init(context: Context) {
        if (rootDir == null) lock.withLock {
            if (rootDir == null) {
                val dir = File(context.filesDir, SECONDARY_DIR)
                if (!dir.exists()) dir.mkdirs()
                rootDir = dir
            }
        }
    }

    private fun reportDir(reportId: String): File? {
        val root = rootDir ?: return null
        val dir = File(root, reportId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun save(context: Context, result: SecondaryResult): SecondaryResult {
        init(context)
        lock.withLock {
            val dir = reportDir(result.reportId) ?: return result
            File(dir, "${result.id}.json").writeTextAtomic(gson.toJson(result))
        }
        return result
    }

    fun create(
        context: Context, reportId: String, kind: SecondaryKind,
        providerId: String, model: String, agentName: String
    ): SecondaryResult {
        val r = SecondaryResult(
            id = UUID.randomUUID().toString(),
            reportId = reportId, kind = kind,
            providerId = providerId, model = model, agentName = agentName,
            timestamp = System.currentTimeMillis(), content = null
        )
        return save(context, r)
    }

    fun listForReport(context: Context, reportId: String, kind: SecondaryKind? = null): List<SecondaryResult> {
        init(context)
        return lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock emptyList()
            if (!dir.exists()) return@withLock emptyList()
            dir.listFiles { f -> f.extension == "json" }?.mapNotNull { file ->
                try { gson.fromJson(file.readText(), SecondaryResult::class.java) } catch (_: Exception) { null }
            }?.let { items ->
                if (kind != null) items.filter { it.kind == kind } else items
            }?.sortedBy { it.timestamp } ?: emptyList()
        }
    }

    fun get(context: Context, reportId: String, resultId: String): SecondaryResult? {
        init(context)
        return lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock null
            val file = File(dir, "$resultId.json")
            if (!file.exists()) return@withLock null
            try { gson.fromJson(file.readText(), SecondaryResult::class.java) } catch (_: Exception) { null }
        }
    }

    fun delete(context: Context, reportId: String, resultId: String) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            File(dir, "$resultId.json").delete()
        }
    }

    fun deleteAllForReport(context: Context, reportId: String) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    /** Counts persisted across all kinds for a report. Used by the Report
     *  result screen to decide whether to surface the View Reranks /
     *  Summaries / Compares buttons without paying for the full file parse
     *  on every recomposition. */
    data class Counts(val rerank: Int, val summarize: Int, val compare: Int, val moderation: Int, val translate: Int)
    fun countForReport(context: Context, reportId: String): Counts {
        init(context)
        return lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock Counts(0, 0, 0, 0, 0)
            if (!dir.exists()) return@withLock Counts(0, 0, 0, 0, 0)
            var rerank = 0; var summarize = 0; var compare = 0; var moderation = 0; var translate = 0
            dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                try {
                    val r = gson.fromJson(file.readText(), SecondaryResult::class.java)
                    when (r.kind) {
                        SecondaryKind.RERANK -> rerank++
                        SecondaryKind.SUMMARIZE -> summarize++
                        SecondaryKind.COMPARE -> compare++
                        SecondaryKind.MODERATION -> moderation++
                        SecondaryKind.TRANSLATE -> translate++
                    }
                } catch (_: Exception) {}
            }
            Counts(rerank, summarize, compare, moderation, translate)
        }
    }
}

/**
 * Hardcoded fallback prompts. Used when the user hasn't supplied an override
 * via GeneralSettings.rerankPrompt / summarizePrompt. Placeholders match the
 * existing @VAR@ convention used elsewhere — substituted by [resolveSecondaryPrompt].
 */
object SecondaryPrompts {
    const val DEFAULT_RERANK = """You are an impartial judge ranking @COUNT@ AI responses to the same question.

QUESTION:
@QUESTION@

RESPONSES:
@RESULTS@

Evaluate each response on accuracy, completeness, clarity, and usefulness.
Output ONLY a JSON array — no prose, no code fences — of the form:

[
  {"id": 1, "rank": 1, "score": 92, "reason": "..."},
  {"id": 2, "rank": 2, "score": 78, "reason": "..."}
]

`id` must match the bracketed [N] in RESPONSES.
`rank` is 1 for the best response, 2 for second, etc. — every id appears exactly once.
`score` is 0-100. `reason` is one sentence."""

    const val DEFAULT_SUMMARIZE = """The following are @COUNT@ AI responses to the same question. Synthesize a single,
authoritative answer that captures the best of all responses.

QUESTION:
@QUESTION@

RESPONSES:
@RESULTS@

Guidelines:
- Combine the strongest, most accurate points from each response.
- Resolve contradictions explicitly; if responses disagree on a fact, say so.
- Drop redundancy. Do not repeat the question.
- Match the tone and depth of the original responses.
- Do not mention that you are summarizing or reference the source responses by number."""

    const val DEFAULT_COMPARE = """You are comparing @COUNT@ AI responses to the same question. Identify where they agree and where they diverge.

QUESTION:
@QUESTION@

RESPONSES:
@RESULTS@

Reference rules — follow them exactly:
- Each response is identified by ONLY a bracketed number — `[1]`, `[2]`, etc. Provider and model names are intentionally not provided to you; a reference legend mapping each `[N]` to its provider and model is appended to your output automatically. Cite responses with the bracketed number and nothing else.
- When ALL @COUNT@ responses agree on a point, write "all responses agree" (or equivalent) and DO NOT enumerate the IDs. Listing every [1] [2] … [@COUNT@] is noise.
- Only enumerate IDs when a subset (not all) of the responses share a position — then list just that subset, e.g. "[1] and [3] argue …".

Produce a comparative analysis with these sections:

## Points of agreement
Claims, conclusions, or recommendations that most or all responses share. Apply the reference rules above.

## Points of disagreement
Where responses diverge. For each disagreement, describe each side and which response(s) hold which view, referencing them by [N]. Where possible, indicate which view is better supported and why.

## Unique contributions
Facts, framings, or arguments that only one response raised. Note the source [N] and whether the contribution adds genuine value.

## Overall takeaway
One paragraph synthesising what a careful reader should conclude given the points of agreement and disagreement above."""

    const val DEFAULT_INTRO = """Briefly introduce yourself in two short sentences: model name, who built you, and what you're best at. Plain prose, no markdown.

You are: @MODEL@ from @PROVIDER@."""

    const val DEFAULT_MODEL_INFO = """Tell me about yourself as an AI model. Cover: your name and version, who created you, your context window if you know it, your primary strengths, and any notable limitations. Plain prose, 3–6 sentences.

You are: @MODEL@ from @PROVIDER@."""

    const val DEFAULT_TRANSLATE = """Translate the following text to @LANGUAGE@.

Preserve markdown formatting (headings, bold, italic, lists, code blocks, tables) exactly. Preserve citation references like [1] or [N]. Preserve URLs and code identifiers untouched. Do NOT add commentary, preface, or explanation — output only the translation.

TEXT TO TRANSLATE:
@TEXT@"""
}

/** Substitutes placeholders in [template] using the values for the
 *  current secondary-result run. `@RESULTS@` arrives pre-formatted
 *  from the caller — we only do plain string replace here.
 *  `@RESULTS_SHORT@` is kept as an alias of `@RESULTS@` for backward
 *  compatibility with any user template that still references it. */
fun resolveSecondaryPrompt(
    template: String, question: String, results: String, count: Int,
    title: String? = null
): String {
    val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
    return template
        .replace("@QUESTION@", question)
        // @RESULTS_SHORT@ first so the @RESULTS@ pass below doesn't
        // partially match (the longer placeholder must replace before
        // its prefix).
        .replace("@RESULTS_SHORT@", results)
        .replace("@RESULTS@", results)
        .replace("@COUNT@", count.toString())
        .replace("@DATE@", now)
        .replace("@TITLE@", title ?: "")
}

/** Build the @RESULTS@ block: per-agent text, prefixed only with the
 *  bracketed `[N]` id (no provider / model identifiers — those reach
 *  the user via the appended Compare legend, not the prompt). The
 *  bracketed N is the stable id rerank models echo back — and the
 *  anchor target HTML export wires up links to.
 *
 *  [includeIds] (1-based) restricts the block to a subset of the success-
 *  ordered agent list while *preserving the original numbering*. The
 *  rest of the system (HTML anchors, secondary-result link rewriting)
 *  keys on the original ids, so passing [4, 1, 7] correctly emits
 *  blocks `[1] [4] [7]` in their original-success order. */
fun buildResultsBlock(report: Report, includeIds: Set<Int>? = null): String {
    val sb = StringBuilder()
    val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
    var emitted = 0
    val total = if (includeIds != null) successful.indices.count { (it + 1) in includeIds } else successful.size
    successful.forEachIndexed { idx, agent ->
        val originalId = idx + 1
        if (includeIds != null && originalId !in includeIds) return@forEachIndexed
        sb.append("[").append(originalId).append("]\n")
        sb.append(agent.responseBody?.trim() ?: "")
        emitted++
        if (emitted != total) sb.append("\n\n")
    }
    return sb.toString()
}

/** Build the reference legend appended to a Compare result. Mirrors
 *  [buildResultsBlock]'s 1-based id assignment so each `[N]` in the
 *  generated comparison maps to the matching `[N] = Provider / Model`
 *  line here. Honours [includeIds] the same way the results block
 *  does — restrict the legend to the same subset that fed the prompt. */
fun buildCompareLegend(report: Report, includeIds: Set<Int>? = null): String {
    val sb = StringBuilder()
    val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
    successful.forEachIndexed { idx, agent ->
        val originalId = idx + 1
        if (includeIds != null && originalId !in includeIds) return@forEachIndexed
        val provDisplay = AppService.findById(agent.provider)?.displayName ?: agent.provider
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append("[").append(originalId).append("] = ")
            .append(provDisplay).append(" / ").append(agent.model)
    }
    return sb.toString()
}

/** Restriction on which of the parent report's per-agent results are
 *  fed into a Summarize / Compare run. Rerank itself always uses
 *  [AllReports] — it ranks the full set by definition. */
sealed class SecondaryScope {
    object AllReports : SecondaryScope()
    data class TopRanked(val count: Int, val rerankResultId: String) : SecondaryScope()
    /** Explicit list of agent ids the user picked from the existing
     *  report. When non-empty the meta run only sees those rows, the
     *  same way [TopRanked] only sees the rerank's top-N. */
    data class Manual(val agentIds: Set<String>) : SecondaryScope()
}

/** Language filter for SUMMARIZE / COMPARE multi-language fan-out.
 *  Defaults to AllPresent so existing call sites keep their behaviour;
 *  the scope picker offers Selected for "only these languages".
 *  TRANSLATE / RERANK / MODERATION ignore this. */
sealed class SecondaryLanguageScope {
    object AllPresent : SecondaryLanguageScope()
    /** [languages] holds English-name keys ("Dutch") plus the empty
     *  string for the original (untranslated) source. */
    data class Selected(val languages: Set<String>) : SecondaryLanguageScope()
}

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
 *  Currently routes only Cohere; other providers fall through with an
 *  explanatory error. Documents are passed in success-ordered position,
 *  so each Cohere `index` (0-based) maps directly to the bracketed
 *  [N] id (1-based) the rest of the app expects. */
suspend fun callRerankApi(
    provider: AppService, apiKey: String, model: String,
    query: String, documents: List<String>
): RerankApiResult {
    val start = System.currentTimeMillis()
    return when (provider.id) {
        "COHERE" -> callCohereRerank(apiKey, model, query, documents, start)
        else -> RerankApiResult(
            content = null,
            errorMessage = "Rerank API not wired for provider ${provider.displayName}. Pick a chat model instead, or open an issue to add ${provider.id} rerank support.",
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
 *  Currently routes Mistral only; other providers fall through with an
 *  explanatory error so the user learns to pick a Mistral moderation
 *  model. The result list is index-aligned with [inputs]. */
suspend fun callModerationApi(
    provider: AppService, apiKey: String, model: String,
    inputs: List<String>
): Pair<List<ModerationInputResult>?, ModerationApiResult> {
    val start = System.currentTimeMillis()
    return when (provider.id) {
        "MISTRAL" -> callMistralModeration(apiKey, model, inputs, start)
        else -> {
            val err = ModerationApiResult(
                content = null,
                errorMessage = "Moderation API not wired for provider ${provider.displayName}. Pick a Mistral moderation model instead, or open an issue to add ${provider.id} moderation support.",
                durationMs = System.currentTimeMillis() - start
            )
            null to err
        }
    }
}

private suspend fun callMistralModeration(
    apiKey: String, model: String, inputs: List<String>, start: Long
): Pair<List<ModerationInputResult>?, ModerationApiResult> {
    return try {
        val api = ApiFactory.createMistralModerationApi()
        val response = api.moderate("Bearer $apiKey", MistralModerationRequest(model, inputs))
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

private suspend fun callCohereRerank(
    apiKey: String, model: String, query: String, documents: List<String>, start: Long
): RerankApiResult {
    return try {
        val api = ApiFactory.createCohereRerankApi()
        val request = CohereRerankRequest(model, query, documents, top_n = documents.size)
        val response = api.rerank("Bearer $apiKey", request)
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
            addProperty("score", (r.relevance_score * 100).toInt().coerceIn(0, 100))
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
        @Suppress("DEPRECATION")
        com.google.gson.JsonParser().parse(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { return null } ?: return null
    if (arr.size() == 0) return null

    data class Entry(val id: Int, val rank: Int)
    val entries = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val rank = obj.get("rank")?.takeIf { it.isJsonPrimitive }?.asInt ?: Int.MAX_VALUE
        Entry(id, rank)
    }
    if (entries.isEmpty()) return null
    return entries.sortedBy { it.rank }.take(count).map { it.id }
}
