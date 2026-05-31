package com.ai.data

import com.ai.model.InternalPrompt

/**
 * Runtime state for the "Judge the judges" batch (kind == JUDGES).
 *
 * Where a Tournament asks the worker chain to judge every pair of a
 * report's answers ONCE (round-robin, one judge per match), this batch
 * inverts the question: it picks a fixed set of [JUDGE_MATCH_COUNT] random
 * answer-pairs ("matches") and hands EVERY judge — the concrete worker
 * models named by the `workers/tournament` prompt's swarm — the SAME
 * matches, so we can measure how the judges agree with one another. The
 * grid is therefore judges × matches: one [JudgeCellState] per (judge,
 * match) cell.
 *
 * One [JudgeEvalRunState] per report (keyed by reportId). The on-disk
 * [SecondaryResult] rows (kind == JUDGES) are the canonical store: CELL
 * rows carry [SecondaryResult.tournamentRole] == "MATCH" (the match fields
 * are reused verbatim — they're generically "a pair + a run id"), the
 * single rolled-up analysis row carries "AGGREGATE". A CELL row's
 * (providerId, model) is the JUDGE that produced it — fixed at creation,
 * never a sentinel (unlike Tournament, where the judge is unknown until the
 * worker chain settles).
 */

/** The reportId — one judge-eval run per report. */
typealias JudgeEvalRunKey = String

/** Number of random answer-pairs every judge is asked to judge. Capped to
 *  the number of distinct pairs a small report can form. */
const val JUDGE_MATCH_COUNT = 25

const val JUDGE_ROLE_CELL = "MATCH"
const val JUDGE_ROLE_AGGREGATE = "AGGREGATE"

/** "${judgeProviderId}/${judgeModel}|${matchKey}" — unique per cell. */
fun judgeCellKey(judgeProviderId: String, judgeModel: String, mKey: MatchKey): String =
    "$judgeProviderId/$judgeModel|$mKey"

enum class JudgeCellStatus { PENDING, RUNNING, DONE, ERROR }

/** One judge's verdict on one match. */
data class JudgeCellState(
    val id: String,                      // SecondaryResult.id (UUID)
    val judgeProviderId: String,         // the judge (this cell's providerId)
    val judgeModel: String,              // the judge (this cell's model)
    val responseAId: String,             // agentId in the @RESPONSE_A@ slot
    val responseBId: String,             // agentId in the @RESPONSE_B@ slot
    val orientation: Int,                // kept for the shared match fields; always 0 here
    val status: JudgeCellStatus,
    /** Parsed from the row's content: "A" | "B" | "tie" | null. */
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
    val matchKey: MatchKey get() = matchKey(responseAId, responseBId, orientation)
    val judgeKey: String get() = "$judgeProviderId/$judgeModel"
    val key: String get() = judgeCellKey(judgeProviderId, judgeModel, matchKey)
    val totalCost: Double get() = (inputCost ?: 0.0) + (outputCost ?: 0.0)
}

/** Entire judge-eval run state for a report. */
data class JudgeEvalRunState(
    val key: JudgeEvalRunKey,
    val reportId: String,
    /** Per-run UUID shared by every row (on [SecondaryResult.tournamentJudgeRunId]). */
    val runId: String,
    val prompt: InternalPrompt,
    /** Cell map keyed by [JudgeCellState.key] so a lookup is O(1). */
    val cells: Map<String, JudgeCellState> = emptyMap(),
    /** SecondaryResult.id of the rolled-up AGGREGATE analysis row. */
    val aggregateRowId: String? = null,
    val cancelled: Boolean = false
) {
    val totalCells: Int get() = cells.size
    val doneCount: Int get() = cells.values.count { it.status == JudgeCellStatus.DONE }
    val errorCount: Int get() = cells.values.count { it.status == JudgeCellStatus.ERROR }
    val runningCount: Int get() = cells.values.count { it.status == JudgeCellStatus.RUNNING }
    val queuedCount: Int get() = cells.values.count { it.status == JudgeCellStatus.PENDING }
    val totalCost: Double get() = cells.values.sumOf { it.totalCost }
    /** Distinct judges in the run, in first-seen order. */
    val judgeKeys: List<String> get() = cells.values.map { it.judgeKey }.distinct()
    /** Distinct matches in the run. */
    val matchKeys: List<MatchKey> get() = cells.values.map { it.matchKey }.distinct()
    val judgeCount: Int get() = judgeKeys.size
    val matchCount: Int get() = matchKeys.size
    val allTerminal: Boolean get() = cells.isNotEmpty() &&
        cells.values.all { it.status == JudgeCellStatus.DONE || it.status == JudgeCellStatus.ERROR }
}

/** Reverse-map a persisted JUDGES CELL [SecondaryResult] row into a
 *  [JudgeCellState]. Returns null for rows that aren't judge cells. The
 *  judge is the row's own (providerId, model). */
fun SecondaryResult.toJudgeCellState(): JudgeCellState? {
    if (kind != SecondaryKind.JUDGES || tournamentRole != JUDGE_ROLE_CELL) return null
    val aId = matchResponseAId ?: return null
    val bId = matchResponseBId ?: return null
    val orient = matchOrientation ?: return null
    val status = when {
        errorMessage != null -> JudgeCellStatus.ERROR
        !content.isNullOrBlank() || durationMs != null -> JudgeCellStatus.DONE
        else -> JudgeCellStatus.PENDING
    }
    val parsed = parseMatchVerdict(content)
    return JudgeCellState(
        id = id,
        judgeProviderId = providerId,
        judgeModel = model,
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
