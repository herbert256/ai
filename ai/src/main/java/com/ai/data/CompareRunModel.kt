package com.ai.data

import com.ai.model.InternalPrompt

/**
 * Runtime state for the "Compare with meta" batch (kind == COMPARE).
 *
 * The user picks a set of meta results (the Compare / Summarize / Synthesize
 * prose a report has accumulated) and a comparison prompt; a worker model
 * then scores how closely EACH report answer matches EACH chosen meta result,
 * as a percentage 0..100. The grid is therefore agents × meta-items: one
 * [CompareCellState] per (agent, meta-item) cell, judged by the worker engine
 * exactly like a Tournament match (the judging model isn't known until the
 * worker chain returns; the winning worker is recorded on the cell's
 * (providerId, model)).
 *
 * One [CompareRunState] per report (keyed by reportId). The on-disk
 * [SecondaryResult] rows (kind == COMPARE) are the canonical store; CELL rows
 * carry [SecondaryResult.compareAgentId] + [SecondaryResult.compareToResultId]
 * + [SecondaryResult.compareRunId]. There is no aggregate row — per-agent /
 * per-meta averages are computed in the UI from the cells.
 */

/** The reportId — one compare run per report. */
typealias CompareRunKey = String

/** Sentinel provider/model stamped on a freshly-created CELL placeholder
 *  before the worker chain has chosen who scored it. */
const val COMPARE_PENDING_PROVIDER = "*workers"
const val COMPARE_PENDING_MODEL = "*pending"

/** "${agentId}|${metaResultId}" — unique per cell within a run. */
fun compareCellKey(agentId: String, metaResultId: String): String = "$agentId|$metaResultId"

/** Per-cell lifecycle state — an alias of the shared [BatchItemStatus]. */
typealias CompareCellStatus = BatchItemStatus

/** One (answer, meta-result) similarity score within a compare run. */
data class CompareCellState(
    val id: String,                      // SecondaryResult.id (UUID)
    val agentId: String,                 // the report answer being scored
    val metaResultId: String,            // the meta result it is scored against
    val status: CompareCellStatus,
    /** Parsed 0..100 from the row's content; null until scored. */
    val percent: Int? = null,
    val reason: String? = null,
    /** "provider/model" of the worker that scored this cell — null until the
     *  worker chain settles (the cell still shows the pre-score sentinel). */
    val judgeModel: String? = null,
    val content: String? = null,         // raw worker reply
    val errorMessage: String? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null,
    val tokenUsage: TokenUsage? = null,
    val traceFile: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val key: String get() = compareCellKey(agentId, metaResultId)
    val totalCost: Double get() = (inputCost ?: 0.0) + (outputCost ?: 0.0)
}

/** Entire compare run state for a report. */
data class CompareRunState(
    val key: CompareRunKey,
    val reportId: String,
    /** Per-run UUID shared by every cell (on [SecondaryResult.compareRunId]). */
    val runId: String,
    val comparePrompt: InternalPrompt,
    /** Cell map keyed by [CompareCellState.key] so a lookup is O(1). */
    val cells: Map<String, CompareCellState> = emptyMap(),
    val cancelled: Boolean = false
) {
    val totalCells: Int get() = cells.size
    val doneCount: Int get() = cells.values.count { it.status == CompareCellStatus.DONE }
    val errorCount: Int get() = cells.values.count { it.status == CompareCellStatus.ERROR }
    val runningCount: Int get() = cells.values.count { it.status == CompareCellStatus.RUNNING }
    val queuedCount: Int get() = cells.values.count { it.status == CompareCellStatus.PENDING }
    val totalCost: Double get() = cells.values.sumOf { it.totalCost }
    /** Distinct report answers scored, in first-seen order. */
    val agentIds: List<String> get() = cells.values.map { it.agentId }.distinct()
    /** Distinct meta results scored against, in first-seen order. */
    val metaResultIds: List<String> get() = cells.values.map { it.metaResultId }.distinct()
    val allTerminal: Boolean get() = cells.isNotEmpty() &&
        cells.values.all { it.status == CompareCellStatus.DONE || it.status == CompareCellStatus.ERROR }

    /** Mean scored percentage across [agentId]'s done cells, or null when none
     *  scored yet. The "Report models" L1 grouping reads this. */
    fun avgForAgent(agentId: String): Int? {
        val scored = cells.values.filter { it.agentId == agentId && it.percent != null }
        if (scored.isEmpty()) return null
        return scored.sumOf { it.percent!! } / scored.size
    }

    /** Mean scored percentage across [metaResultId]'s done cells, or null when
     *  none scored yet. The "Meta items" L1 grouping reads this. */
    fun avgForMeta(metaResultId: String): Int? {
        val scored = cells.values.filter { it.metaResultId == metaResultId && it.percent != null }
        if (scored.isEmpty()) return null
        return scored.sumOf { it.percent!! } / scored.size
    }
}

/** Reverse-map a persisted COMPARE CELL [SecondaryResult] row into a
 *  [CompareCellState]. Returns null for rows that aren't compare cells. The
 *  scoring worker is read from the row's (providerId, model) unless it's still
 *  the pre-score sentinel. */
fun SecondaryResult.toCompareCellState(): CompareCellState? {
    if (kind != SecondaryKind.COMPARE) return null
    val aId = compareAgentId ?: return null
    val mId = compareToResultId ?: return null
    val status = when {
        errorMessage != null -> CompareCellStatus.ERROR
        !content.isNullOrBlank() || durationMs != null -> CompareCellStatus.DONE
        else -> CompareCellStatus.PENDING
    }
    val parsed = parseSimilarityScore(content)
    val judge = if (providerId == COMPARE_PENDING_PROVIDER || providerId.isBlank()) null
        else "$providerId/$model"
    return CompareCellState(
        id = id,
        agentId = aId,
        metaResultId = mId,
        status = status,
        percent = parsed?.percent,
        reason = parsed?.reason,
        judgeModel = judge,
        content = content,
        errorMessage = errorMessage,
        inputCost = inputCost,
        outputCost = outputCost,
        durationMs = durationMs,
        tokenUsage = tokenUsage,
        traceFile = traceFile,
        timestamp = timestamp
    )
}

/** Parsed similarity score. [percent] is clamped to 0..100. */
data class CompareScore(val percent: Int, val reason: String?)

/** Robustly parse a worker's reply into a [CompareScore]. Prefers the labeled
 *  `percentage:` line the `meta_compare/equivalent` prompt asks for, then a
 *  bare first-number fallback, then a strict-JSON `{"percentage":…}` form.
 *  Tolerates ``` fences, `%` suffixes and casing.
 *
 *  Returns null when NO number can be found — load-bearing: the engine's
 *  `accept` predicate uses it to treat a hollow reply as a logical miss and
 *  advance to the next worker. */
fun parseSimilarityScore(content: String?): CompareScore? {
    if (content.isNullOrBlank()) return null
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    val reason = cleaned.lineSequence()
        .firstOrNull { it.trim().startsWith("reason", ignoreCase = true) }
        ?.substringAfter(":", "")?.trim()?.takeIf { it.isNotBlank() }

    // Labeled-line form (what the equivalent prompt asks for).
    val pctLine = cleaned.lineSequence()
        .firstOrNull { it.trim().startsWith("percent", ignoreCase = true) }
        ?.substringAfter(":", "")
    parsePercentNumber(pctLine)?.let { return CompareScore(it, reason) }

    // Strict-JSON fallback.
    val obj = try {
        com.google.gson.JsonParser.parseString(cleaned).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) { null }
    if (obj != null) {
        val raw = (obj.get("percentage") ?: obj.get("percent") ?: obj.get("score"))
            ?.takeIf { it.isJsonPrimitive }?.asString
        parsePercentNumber(raw)?.let { p ->
            val jsonReason = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
            return CompareScore(p, jsonReason ?: reason)
        }
    }

    // Last resort: the first number anywhere in the reply.
    parsePercentNumber(cleaned)?.let { return CompareScore(it, reason) }
    return null
}

/** Pull the first 0..100 integer out of [raw] (tolerating a `%` suffix and a
 *  decimal point). Returns null when no number is present. Clamped to 0..100. */
private fun parsePercentNumber(raw: String?): Int? {
    val s = raw?.trim() ?: return null
    val match = Regex("""\d+(?:\.\d+)?""").find(s) ?: return null
    val v = match.value.toDoubleOrNull() ?: return null
    return v.toInt().coerceIn(0, 100)
}
