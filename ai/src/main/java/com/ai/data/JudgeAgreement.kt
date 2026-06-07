package com.ai.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Inter-judge agreement analysis for the "Judge the judges" batch.
 *
 * Given the full grid of [JudgeCellState] cells (every judge's verdict on
 * every match), we derive a per-match CONSENSUS verdict (what most judges
 * said) and then score each judge by how often it agreed with that
 * consensus — the headline "is this a good judge?" metric — alongside a
 * few supporting traits (decisiveness, position lean, confidence).
 */

/** Consensus verdict for one match across all judges' verdicts. Plurality
 *  wins; a verdict only wins if it is STRICTLY more common than both
 *  others, so an A/B split (or an A-vs-tie draw) resolves to "tie" — there
 *  is no clear winner. Returns null when no judge produced a verdict. */
fun consensusForMatch(verdicts: List<String>): String? {
    if (verdicts.isEmpty()) return null
    val a = verdicts.count { it == "A" }
    val b = verdicts.count { it == "B" }
    val t = verdicts.count { it == "tie" }
    return when {
        a > b && a > t -> "A"
        b > a && b > t -> "B"
        else -> "tie"
    }
}

/** Per-judge performance row, sorted best-first by [agreement]. */
data class JudgeStats(
    val judgeProviderId: String,
    val judgeModel: String,
    /** Cells that produced a parseable verdict. */
    val matchesJudged: Int,
    /** Cells with no usable verdict (errored or unparseable reply). */
    val errors: Int,
    /** Fraction of judged matches whose verdict equals the consensus (0..1). */
    val agreement: Double,
    /** Fraction of judged matches the judge called a "tie". */
    val tieRate: Double,
    /** Of the decisive (A/B) verdicts, the fraction that picked "A" — a
     *  position-bias proxy (every judge sees the same A/B placement). */
    val aLean: Double,
    /** Mean confidence over judged matches, or null when none reported. */
    val avgConfidence: Double?,
    /** Total USD spend across this judge's cells. */
    val totalCost: Double,
    /** Total wall-clock API time (ms) across this judge's cells. */
    val totalMs: Long
) {
    val judgeKey: String get() = "$judgeProviderId/$judgeModel"
}

/** Score every judge from the full cell grid. The consensus per match is
 *  computed from ALL judges' verdicts; each judge's agreement is then its
 *  share of matches that matched that consensus. */
fun analyzeJudges(cells: List<JudgeCellState>): List<JudgeStats> {
    val consensusByMatch: Map<String, String?> = cells.groupBy { it.matchKey }
        .mapValues { (_, cs) -> consensusForMatch(cs.mapNotNull { it.verdict }) }
    return cells.groupBy { it.judgeKey }.map { (_, cs) ->
        val first = cs.first()
        val withVerdict = cs.filter { it.verdict != null }
        val judged = withVerdict.size
        val agree = withVerdict.count { c ->
            val cons = consensusByMatch[c.matchKey]
            cons != null && c.verdict == cons
        }
        val ties = withVerdict.count { it.verdict == "tie" }
        val decided = withVerdict.filter { it.verdict == "A" || it.verdict == "B" }
        val aCount = decided.count { it.verdict == "A" }
        val confs = withVerdict.mapNotNull { it.confidence }
        JudgeStats(
            judgeProviderId = first.judgeProviderId,
            judgeModel = first.judgeModel,
            matchesJudged = judged,
            errors = cs.size - judged,
            agreement = if (judged > 0) agree.toDouble() / judged else 0.0,
            tieRate = if (judged > 0) ties.toDouble() / judged else 0.0,
            aLean = if (decided.isNotEmpty()) aCount.toDouble() / decided.size else 0.0,
            avgConfidence = if (confs.isNotEmpty()) confs.average() else null,
            totalCost = cs.sumOf { it.totalCost },
            totalMs = cs.sumOf { it.durationMs ?: 0L }
        )
    }.sortedWith(compareByDescending<JudgeStats> { it.agreement }.thenByDescending { it.matchesJudged })
}

/** Overall "consensus strength" — the mean agreement across judges (0..1).
 *  High = the judges broadly see the same winners; low = they disagree. */
fun List<JudgeStats>.consensusStrength(): Double =
    if (isEmpty()) 0.0 else map { it.agreement }.average()

/** Rank the ANSWERS (not the judges) by what the panel of judges
 *  collectively decided: take each match's plurality [consensusForMatch]
 *  verdict, then fold those consensus verdicts through the same
 *  [computeWinMatrix] the Tournament uses. Feeds the Value view's "Judges"
 *  ranking source so a Judge-the-judges run yields a per-answer ranking the
 *  same way a Tournament does. [idForAgentId] maps a response's agentId to
 *  its 1-based `[N]` id (unresolved cells are skipped by computeWinMatrix). */
fun judgesConsensusWinMatrix(
    cells: List<JudgeCellState>,
    idForAgentId: (String) -> Int?
): WinMatrix {
    val consensusMatches = cells.groupBy { it.matchKey }.mapNotNull { (_, cs) ->
        val cons = consensusForMatch(cs.mapNotNull { it.verdict }) ?: return@mapNotNull null
        val first = cs.first()
        MatchState(
            id = "judges-consensus:${first.matchKey}",
            responseAId = first.responseAId,
            responseBId = first.responseBId,
            orientation = first.orientation,
            status = MatchStatus.DONE,
            verdict = cons
        )
    }
    return computeWinMatrix(consensusMatches, idForAgentId)
}

/** Serialise the ranked judge stats for the AGGREGATE row's `content`.
 *  Recomputable from the cells, so this is a convenience snapshot for
 *  exports / inspection; the UI recomputes live. */
fun List<JudgeStats>.toJudgesJson(): String {
    val arr = JsonArray()
    forEachIndexed { i, s ->
        arr.add(JsonObject().apply {
            addProperty("rank", i + 1)
            addProperty("provider", s.judgeProviderId)
            addProperty("model", s.judgeModel)
            addProperty("judged", s.matchesJudged)
            addProperty("errors", s.errors)
            addProperty("agreement", s.agreement)
            addProperty("tieRate", s.tieRate)
            addProperty("aLean", s.aLean)
            s.avgConfidence?.let { addProperty("avgConfidence", it) }
        })
    }
    return arr.toString()
}
