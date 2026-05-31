package com.ai.data

import com.ai.model.InternalPrompt

/**
 * Single source of truth for the Tournament feature's runtime state —
 * the sibling of [FanOutRunModel] for pairwise head-to-head judging.
 *
 * One [TournamentRunState] per active or persisted (reportId, judge
 * provider, judge model) triple. A tournament judges every unordered
 * pair of a report's successful responses TWICE (A-vs-B and B-vs-A,
 * to cancel first-position bias), so for N responses it runs N(N-1)
 * ordered [MatchState] rows. The wins fold into a win matrix that
 * three interchangeable aggregation methods (Copeland / Bradley–Terry /
 * Elo, see [TournamentRanking]) turn into a 1..N ranking.
 *
 * The on-disk [SecondaryResult] rows (kind == TOURNAMENT) stay the
 * canonical persistence layer; this model is hydrated from disk and
 * kept in sync by [com.ai.viewmodel.TournamentEngine]'s transition
 * lambdas. MATCH rows carry [SecondaryResult.tournamentRole] == "MATCH";
 * the single rolled-up ranking row carries "AGGREGATE".
 */

/** "${reportId}|${providerId}|${model}" — unique per judge model's
 *  tournament on a given report. */
typealias TournamentRunKey = String

/** "${responseAId}|${responseBId}|${orientation}" — unique per ordered
 *  match within a run. */
typealias MatchKey = String

fun tournamentRunKey(reportId: String, providerId: String, model: String): TournamentRunKey =
    "$reportId|$providerId|$model"

fun matchKey(responseAId: String, responseBId: String, orientation: Int): MatchKey =
    "$responseAId|$responseBId|$orientation"

/** Per-match lifecycle state — mirror of [PairStatus]. */
enum class MatchStatus {
    /** Placeholder on disk, waiting for the per-host throttle permit. */
    PENDING,

    /** Permit acquired, judge HTTP call in flight. */
    RUNNING,

    /** Verdict written to disk (content non-blank, or durationMs set). */
    DONE,

    /** Error stamped on disk. */
    ERROR
}

/** One ordered head-to-head judgment within a tournament run. The judge
 *  model is the run's (providerId, model); this row records which two
 *  responses were compared, in which orientation, and the parsed
 *  verdict. */
data class MatchState(
    val id: String,                      // SecondaryResult.id (UUID)
    val responseAId: String,             // agentId in the @RESPONSE_A@ slot
    val responseBId: String,             // agentId in the @RESPONSE_B@ slot
    val orientation: Int,                // 0 = canonical, 1 = swapped
    val status: MatchStatus,
    /** Parsed from the row's content: "A" | "B" | "tie" | null (pending). */
    val verdict: String? = null,
    val confidence: Double? = null,
    val reason: String? = null,
    val content: String? = null,         // raw judge reply (the verdict JSON)
    val errorMessage: String? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null,
    val tokenUsage: TokenUsage? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val key: MatchKey get() = matchKey(responseAId, responseBId, orientation)
    val totalCost: Double get() = (inputCost ?: 0.0) + (outputCost ?: 0.0)
}

/** Entire tournament run state for one judge model. Built by hydration
 *  on first access and mutated by the engine's per-transition update
 *  calls. The UI reads from a snapshot of this. */
data class TournamentRunState(
    val key: TournamentRunKey,
    val reportId: String,
    val judgeProviderId: String,
    val judgeModel: String,
    val judgePrompt: InternalPrompt,
    val scope: SecondaryScope = SecondaryScope.AllReports,
    /** Ordered-match map keyed by [MatchKey] so a lookup is O(1). */
    val matches: Map<MatchKey, MatchState> = emptyMap(),
    /** SecondaryResult.id of the rolled-up AGGREGATE ranking row, when
     *  it has been created. The aggregate's `content` is the rerank-
     *  shaped JSON for [selectedMethod]; its `tournamentMatrix` carries
     *  the win matrix so a method toggle is a pure local recompute. */
    val aggregateRowId: String? = null,
    val selectedMethod: TournamentMethod = TournamentMethod.COPELAND,
    /** Set when the user taps the title-bar trash — the runner stops
     *  queueing new matches and in-flight ones bail before save. */
    val cancelled: Boolean = false
) {
    val totalMatches: Int get() = matches.size
    val doneCount: Int get() = matches.values.count { it.status == MatchStatus.DONE }
    val errorCount: Int get() = matches.values.count { it.status == MatchStatus.ERROR }
    val runningCount: Int get() = matches.values.count { it.status == MatchStatus.RUNNING }
    val queuedCount: Int get() = matches.values.count { it.status == MatchStatus.PENDING }
    val totalCost: Double get() = matches.values.sumOf { it.totalCost }
    /** True once every match has reached a terminal state. */
    val allTerminal: Boolean get() = matches.isNotEmpty() &&
        matches.values.all { it.status == MatchStatus.DONE || it.status == MatchStatus.ERROR }
}

/** Reverse-map a persisted MATCH [SecondaryResult] row into a
 *  [MatchState]. Returns null for rows that aren't tournament matches
 *  (missing the match identity fields). Parses the verdict JSON out of
 *  [SecondaryResult.content]. */
fun SecondaryResult.toMatchState(): MatchState? {
    if (tournamentRole != "MATCH") return null
    val aId = matchResponseAId ?: return null
    val bId = matchResponseBId ?: return null
    val orient = matchOrientation ?: return null
    val status = when {
        errorMessage != null -> MatchStatus.ERROR
        !content.isNullOrBlank() || durationMs != null -> MatchStatus.DONE
        else -> MatchStatus.PENDING
    }
    val parsed = parseMatchVerdict(content)
    return MatchState(
        id = id,
        responseAId = aId,
        responseBId = bId,
        orientation = orient,
        status = status,
        verdict = parsed?.verdict,
        confidence = parsed?.confidence,
        reason = parsed?.reason,
        content = content,
        errorMessage = errorMessage,
        inputCost = inputCost,
        outputCost = outputCost,
        durationMs = durationMs,
        tokenUsage = tokenUsage,
        timestamp = timestamp
    )
}

/** Parsed judge verdict. [verdict] is normalised to "A" / "B" / "tie". */
data class MatchVerdict(val verdict: String, val confidence: Double?, val reason: String?)

/** Robustly parse a judge model's reply into a [MatchVerdict]. Tolerates
 *  ``` fences, casing, and "first"/"second"/1/2 synonyms; anything
 *  unparseable degrades to "tie" so a malformed reply doesn't error the
 *  match (a no-decision contributes a half-win to each side). Returns
 *  null only when [content] is blank (the match hasn't run yet). */
fun parseMatchVerdict(content: String?): MatchVerdict? {
    if (content.isNullOrBlank()) return null
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    // Try strict JSON first.
    val obj = try {
        com.google.gson.JsonParser.parseString(cleaned).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) { null }
    val rawVerdict: String?
    var confidence: Double? = null
    var reason: String? = null
    if (obj != null) {
        rawVerdict = obj.get("verdict")?.takeIf { it.isJsonPrimitive }?.asString
        confidence = obj.get("confidence")?.takeIf { it.isJsonPrimitive }?.let {
            try { it.asDouble } catch (_: Exception) { null }
        }
        reason = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
    } else {
        // Fall back to scanning the raw text for an A/B/tie token.
        rawVerdict = cleaned
    }
    return MatchVerdict(normaliseVerdict(rawVerdict), confidence?.coerceIn(0.0, 1.0), reason)
}

private fun normaliseVerdict(raw: String?): String {
    val s = raw?.trim()?.lowercase() ?: return "tie"
    return when {
        s == "a" || s.startsWith("\"a\"") || s == "1" || s.contains("first") ||
            s.startsWith("a ") || s.contains("response a") -> "A"
        s == "b" || s.startsWith("\"b\"") || s == "2" || s.contains("second") ||
            s.startsWith("b ") || s.contains("response b") -> "B"
        else -> "tie"
    }
}
