package com.ai.data

import com.ai.model.InternalPrompt

/**
 * Single source of truth for the Tournament feature's runtime state.
 *
 * ONE [TournamentRunState] per report. A tournament judges every
 * unordered pair of a report's successful responses TWICE (A-vs-B and
 * B-vs-A, to cancel first-position bias), so for N responses it runs
 * N(N-1) ordered [MatchState] rows. Each match is judged by the WORKER
 * engine (the round-robin chain of cheap models in the `workers` swarm)
 * — like the Fan Meta batch — so a match's judging model isn't known
 * until the worker chain returns; the winning worker is then recorded on
 * the row's (providerId, model). The wins fold into a win matrix that
 * interchangeable aggregation methods (Copeland / Elo /
 * Points / Schulze / Markov, see [TournamentRanking]) turn into a 1..N ranking.
 *
 * The on-disk [SecondaryResult] rows (kind == TOURNAMENT) stay the
 * canonical persistence layer; this model is hydrated from disk and kept
 * in sync by [com.ai.viewmodel.TournamentEngine]'s transition lambdas.
 * MATCH rows carry [SecondaryResult.tournamentRole] == "MATCH"; the
 * single rolled-up ranking row carries "AGGREGATE".
 */

/** The reportId — one tournament per report. */
typealias TournamentRunKey = String

/** "${responseAId}|${responseBId}|${orientation}" — unique per ordered
 *  match within a run. The judging worker is NOT part of match identity
 *  (a re-judge by a different worker keeps the same key). */
typealias MatchKey = String

fun tournamentRunKey(reportId: String): TournamentRunKey = reportId

fun matchKey(responseAId: String, responseBId: String, orientation: Int): MatchKey =
    "$responseAId|$responseBId|$orientation"

/** Sentinel provider/model stamped on a freshly-created MATCH placeholder
 *  before the worker chain has chosen who judges it. */
const val TOURNAMENT_PENDING_PROVIDER = "*workers"
const val TOURNAMENT_PENDING_MODEL = "*pending"

/** Per-match lifecycle state — mirror of [PairStatus]. */
enum class MatchStatus {
    /** Placeholder on disk, waiting for a worker permit. */
    PENDING,

    /** A worker call is in flight. */
    RUNNING,

    /** Verdict written to disk (content non-blank, or durationMs set). */
    DONE,

    /** Error stamped on disk (every worker failed). */
    ERROR
}

/** One ordered head-to-head judgment within a tournament run. */
data class MatchState(
    val id: String,                      // SecondaryResult.id (UUID)
    val responseAId: String,             // agentId in the @RESPONSE_A@ slot
    val responseBId: String,             // agentId in the @RESPONSE_B@ slot
    val orientation: Int,                // 0 = canonical, 1 = swapped
    val status: MatchStatus,
    /** "provider/model" of the worker that judged this match — null until
     *  the worker chain settles. Drives the "Tournament models" grouping. */
    val judgeModel: String? = null,
    /** Parsed from the row's content: "A" | "B" | "tie" | null (pending). */
    val verdict: String? = null,
    val confidence: Double? = null,
    val reason: String? = null,
    val content: String? = null,         // raw judge reply
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

/** Entire tournament run state for a report. Built by hydration on first
 *  access and mutated by the engine's per-transition update calls. */
data class TournamentRunState(
    val key: TournamentRunKey,
    val reportId: String,
    /** Per-run UUID shared by every row (carried on
     *  [SecondaryResult.tournamentJudgeRunId]); the 🐞 trace deep-link
     *  and resume both key on it. */
    val runId: String,
    val tournamentPrompt: InternalPrompt,
    val scope: SecondaryScope = SecondaryScope.AllReports,
    /** Ordered-match map keyed by [MatchKey] so a lookup is O(1). */
    val matches: Map<MatchKey, MatchState> = emptyMap(),
    /** SecondaryResult.id of the rolled-up AGGREGATE ranking row. */
    val aggregateRowId: String? = null,
    val selectedMethod: TournamentMethod = TournamentMethod.COPELAND,
    val cancelled: Boolean = false
) {
    val totalMatches: Int get() = matches.size
    val doneCount: Int get() = matches.values.count { it.status == MatchStatus.DONE }
    val errorCount: Int get() = matches.values.count { it.status == MatchStatus.ERROR }
    val runningCount: Int get() = matches.values.count { it.status == MatchStatus.RUNNING }
    val queuedCount: Int get() = matches.values.count { it.status == MatchStatus.PENDING }
    val totalCost: Double get() = matches.values.sumOf { it.totalCost }
    /** Distinct judging worker models seen so far — the L1 "Judges" stat
     *  and the "Tournament models" grouping keys. */
    val distinctJudgeModels: Set<String>
        get() = matches.values.mapNotNull { it.judgeModel }.toSet()
    /** True once every match has reached a terminal state. */
    val allTerminal: Boolean get() = matches.isNotEmpty() &&
        matches.values.all { it.status == MatchStatus.DONE || it.status == MatchStatus.ERROR }
}

/** Reverse-map a persisted MATCH [SecondaryResult] row into a
 *  [MatchState]. Returns null for rows that aren't tournament matches.
 *  The winning worker is read from the row's (providerId, model), unless
 *  it's still the pre-judge sentinel. */
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
    val judge = if (providerId == TOURNAMENT_PENDING_PROVIDER || providerId.isBlank()) null
        else "$providerId/$model"
    return MatchState(
        id = id,
        responseAId = aId,
        responseBId = bId,
        orientation = orient,
        status = status,
        judgeModel = judge,
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

/** Robustly parse a worker's reply into a [MatchVerdict]. Prefers the
 *  labeled three-line form (`verdict:` / `confidence:` / `reason:`) the
 *  `workers/tournament` prompt asks for, then a strict-JSON
 *  `{"verdict":…}` fallback. Tolerates ``` fences and casing.
 *
 *  Returns null when NO verdict can be found (blank, or neither form
 *  present) — this is load-bearing: the engine's `accept` predicate uses
 *  it to treat a hollow reply as a logical miss and advance to the next
 *  worker. The aggregation treats a never-verdicted match as a no-contest. */
fun parseMatchVerdict(content: String?): MatchVerdict? {
    if (content.isNullOrBlank()) return null
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    // Labeled-line form (what the workers/tournament prompt asks for).
    val verdictLine = cleaned.lineSequence()
        .firstOrNull { it.trim().startsWith("verdict", ignoreCase = true) }
        ?.substringAfter(":", "")?.takeIf { it.isNotBlank() }
    if (verdictLine != null) {
        val confLine = cleaned.lineSequence()
            .firstOrNull { it.trim().startsWith("confidence", ignoreCase = true) }
            ?.substringAfter(":", "")
        val reasonLine = cleaned.lineSequence()
            .firstOrNull { it.trim().startsWith("reason", ignoreCase = true) }
            ?.substringAfter(":", "")?.trim()
        return MatchVerdict(normaliseVerdict(verdictLine), parseConfidence(confLine), reasonLine)
    }

    // Strict-JSON fallback.
    val obj = try {
        com.google.gson.JsonParser.parseString(cleaned).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) { null }
    if (obj != null && obj.has("verdict")) {
        val rawVerdict = obj.get("verdict")?.takeIf { it.isJsonPrimitive }?.asString
        val confidence = obj.get("confidence")?.takeIf { it.isJsonPrimitive }?.let {
            try { it.asDouble } catch (_: Exception) { null }
        }?.coerceIn(0.0, 1.0)
        val reason = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        return MatchVerdict(normaliseVerdict(rawVerdict), confidence, reason)
    }

    // No verdict present — logical miss.
    return null
}

private fun parseConfidence(raw: String?): Double? {
    val s = raw?.trim()?.removeSuffix("%")?.trim() ?: return null
    val v = s.toDoubleOrNull() ?: return null
    return (if (v > 1.0) v / 100.0 else v).coerceIn(0.0, 1.0)
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
