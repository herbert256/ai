package com.ai.viewmodel

import com.ai.data.ModelCooldownStore
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult

data class BrokenWorkLiveState(
    val nowMs: Long = System.currentTimeMillis(),
    val stalePlaceholderMs: Long = BrokenWorkPolicy.DEFAULT_STALE_PLACEHOLDER_MS,
    val inFlightRowIds: Set<String> = emptySet(),
    val activeFanOutRunKeys: Set<String> = emptySet(),
    val activeTournamentRunKeys: Set<String> = emptySet(),
    val activeJudgeRunKeys: Set<String> = emptySet(),
    val activeCompareRunKeys: Set<String> = emptySet(),
    val activeFanMetaRunKeys: Set<String> = emptySet(),
    val runningFanMetaRowIds: Set<String> = emptySet(),
    val activeTranslationRunIds: Set<String> = emptySet(),
)

object BrokenWorkPolicy {
    const val DEFAULT_STALE_PLACEHOLDER_MS: Long = 60_000L

    fun fanMetaTouched(row: SecondaryResult): Boolean =
        !row.title.isNullOrBlank() ||
            !row.icon.isNullOrBlank() ||
            !row.titleErrorMessage.isNullOrBlank() ||
            !row.iconErrorMessage.isNullOrBlank() ||
            row.titleRunId != null ||
            row.iconRunId != null ||
            row.titlePromptUsed != null ||
            row.iconPromptUsed != null ||
            row.titleDurationMs != null ||
            row.titleInputTokens > 0 ||
            row.titleOutputTokens > 0 ||
            row.iconInputTokens > 0 ||
            row.iconOutputTokens > 0

    fun fanMetaRunKey(reportId: String, metaPromptId: String): String =
        "$reportId|$metaPromptId"

    private fun interrupted(row: SecondaryResult, live: BrokenWorkLiveState, activeRun: Boolean = false): Boolean =
        row.content.isNullOrBlank() &&
            row.errorMessage == null &&
            row.durationMs == null &&
            row.id !in live.inFlightRowIds &&
            !activeRun &&
            live.nowMs - row.timestamp >= live.stalePlaceholderMs

    /** A row is "errored" when it carries an error message — except a
     *  TRANSLATE row whose model is merely on cooldown (a 429 retry-after
     *  bench, not a real failure): that retries on its own, so it must not
     *  light the ⚠️ or the hub's "problems" card. Folded in from the hub's
     *  old reportHasProblems so both surfaces apply the same exception. */
    private fun errored(row: SecondaryResult): Boolean =
        row.errorMessage != null &&
            !(row.kind == SecondaryKind.TRANSLATE &&
                ModelCooldownStore.isUnavailable(row.providerId, row.model))

    /** (interruptedAgentIds, erroredAgentIds) for one report's primary
     *  agents. Errored = reportStatus ERROR. Interrupted = PENDING/RUNNING
     *  left behind by a process kill — only when the report has no live
     *  generation / regenerate job ([reportIsLive] false); a report mid-run
     *  legitimately has PENDING/RUNNING agents and must not be flagged. This
     *  is the agent half of the hub's old reportHasProblems, now the single
     *  routine that also feeds the ⚠️ Broken-work badge. */
    fun agentProblems(report: Report, reportIsLive: Boolean): Pair<List<String>, List<String>> {
        val errored = report.agents
            .filter { it.reportStatus == ReportStatus.ERROR }
            .map { it.agentId }
        val interrupted = if (reportIsLive) emptyList()
            else report.agents
                .filter { it.reportStatus == ReportStatus.PENDING || it.reportStatus == ReportStatus.RUNNING }
                .map { it.agentId }
        return interrupted to errored
    }

    private class Acc(val kind: BatchFamilyKind, val key: String, val name: String) {
        var unfinished = 0
        var errors = 0
        var lastError: String? = null
    }

    fun detectBatches(
        reportId: String,
        reportTitle: String,
        reportTimestamp: Long,
        rows: List<SecondaryResult>,
        live: BrokenWorkLiveState,
    ): List<BrokenBatch> {
        val groups = LinkedHashMap<String, Acc>()
        fun tally(
            kind: BatchFamilyKind,
            key: String,
            name: String,
            unfinished: Boolean,
            error: Boolean,
            errorMsg: String? = null,
        ) {
            if (!unfinished && !error) return
            val group = groups.getOrPut("$kind|$key") { Acc(kind, key, name) }
            if (unfinished) group.unfinished++
            if (error) {
                group.errors++
                if (!errorMsg.isNullOrBlank()) group.lastError = errorMsg
            }
        }

        rows.forEach { row ->
            when {
                row.kind == SecondaryKind.META && row.fanOutSourceAgentId != null && row.fanInOf == null -> {
                    val promptId = row.metaPromptId ?: return@forEach
                    val runKey = fanMetaRunKey(reportId, promptId)
                    val name = row.metaPromptName?.takeIf { it.isNotBlank() } ?: promptId
                    tally(
                        BatchFamilyKind.FAN_OUT,
                        runKey,
                        "Fan Out · $name",
                        interrupted(row, live, activeRun = runKey in live.activeFanOutRunKeys),
                        errored(row),
                        row.errorMessage
                    )
                    val fanMetaExpected = fanMetaTouched(row)
                    val fanMetaActive = runKey in live.activeFanMetaRunKeys
                    if (!row.content.isNullOrBlank() && fanMetaExpected && !fanMetaActive) {
                        val fmError = !row.titleErrorMessage.isNullOrBlank() || !row.iconErrorMessage.isNullOrBlank()
                        val fmUnfinished = row.title.isNullOrBlank() &&
                            row.icon.isNullOrBlank() &&
                            !fmError &&
                            row.id !in live.runningFanMetaRowIds &&
                            live.nowMs - row.timestamp >= live.stalePlaceholderMs
                        tally(
                            BatchFamilyKind.FAN_META,
                            runKey,
                            "Fan Meta · $name",
                            fmUnfinished,
                            fmError,
                            row.titleErrorMessage ?: row.iconErrorMessage
                        )
                    }
                }
                row.kind == SecondaryKind.TOURNAMENT && row.tournamentRole == "MATCH" ->
                    tally(
                        BatchFamilyKind.TOURNAMENT,
                        reportId,
                        "Tournament",
                        interrupted(row, live, activeRun = reportId in live.activeTournamentRunKeys),
                        errored(row),
                        row.errorMessage
                    )
                row.kind == SecondaryKind.JUDGES && row.tournamentRole == "MATCH" ->
                    tally(
                        BatchFamilyKind.JUDGES,
                        reportId,
                        "Judges",
                        interrupted(row, live, activeRun = reportId in live.activeJudgeRunKeys),
                        errored(row),
                        row.errorMessage
                    )
                row.kind == SecondaryKind.COMPARE ->
                    tally(
                        BatchFamilyKind.COMPARE,
                        reportId,
                        "Compare",
                        interrupted(row, live, activeRun = reportId in live.activeCompareRunKeys),
                        errored(row),
                        row.errorMessage
                    )
                row.kind == SecondaryKind.TRANSLATE && row.translationRunId != null -> {
                    val runId = row.translationRunId
                    tally(
                        BatchFamilyKind.TRANSLATION,
                        runId,
                        "Translation · ${row.targetLanguage?.takeIf { it.isNotBlank() } ?: "?"}",
                        interrupted(row, live, activeRun = runId in live.activeTranslationRunIds),
                        errored(row),
                        row.errorMessage
                    )
                }
                row.fanOutSourceAgentId == null &&
                    row.fanInOf == null &&
                    row.translationRunId == null &&
                    (row.kind == SecondaryKind.META ||
                        row.kind == SecondaryKind.RERANK ||
                        row.kind == SecondaryKind.MODERATION) ->
                    tally(
                        BatchFamilyKind.OTHER,
                        reportId,
                        "Meta / Rerank / Moderation",
                        interrupted(row, live),
                        errored(row),
                        row.errorMessage
                    )
            }
        }

        return groups.values.map {
            BrokenBatch(
                reportId = reportId,
                reportTitle = reportTitle,
                kind = it.kind,
                key = it.key,
                batchName = it.name,
                unfinishedCount = it.unfinished,
                errorCount = it.errors,
                timestamp = reportTimestamp,
                errorMessage = if (it.errors == 1 && it.unfinished == 0) it.lastError else null
            )
        }
    }

    fun matchingRows(
        rows: List<SecondaryResult>,
        batch: BrokenBatch,
        mode: BrokenItemMode,
        live: BrokenWorkLiveState = BrokenWorkLiveState(),
    ): List<SecondaryResult> {
        val errors = mode == BrokenItemMode.ERRORS
        return rows.filter { row ->
            val inBatch = when (batch.kind) {
                BatchFamilyKind.FAN_OUT,
                BatchFamilyKind.FAN_META ->
                    row.kind == SecondaryKind.META &&
                        row.fanOutSourceAgentId != null &&
                        row.fanInOf == null &&
                        row.metaPromptId?.let { fanMetaRunKey(batch.reportId, it) } == batch.key
                BatchFamilyKind.TOURNAMENT -> row.kind == SecondaryKind.TOURNAMENT && row.tournamentRole == "MATCH"
                BatchFamilyKind.JUDGES -> row.kind == SecondaryKind.JUDGES && row.tournamentRole == "MATCH"
                BatchFamilyKind.COMPARE -> row.kind == SecondaryKind.COMPARE
                BatchFamilyKind.TRANSLATION -> row.kind == SecondaryKind.TRANSLATE && row.translationRunId == batch.key
                BatchFamilyKind.OTHER ->
                    row.fanOutSourceAgentId == null &&
                        row.fanInOf == null &&
                        row.translationRunId == null &&
                        (row.kind == SecondaryKind.META ||
                            row.kind == SecondaryKind.RERANK ||
                            row.kind == SecondaryKind.MODERATION)
                // Agents aren't SecondaryResult rows; the RESPONSES detail uses
                // its own agent loader, so no row ever matches here.
                BatchFamilyKind.REGENERATE,
                BatchFamilyKind.RESPONSES -> false
            }
            if (!inBatch) return@filter false
            val activeRun = when (batch.kind) {
                BatchFamilyKind.FAN_OUT -> batch.key in live.activeFanOutRunKeys
                BatchFamilyKind.TOURNAMENT -> batch.reportId in live.activeTournamentRunKeys
                BatchFamilyKind.JUDGES -> batch.reportId in live.activeJudgeRunKeys
                BatchFamilyKind.COMPARE -> batch.reportId in live.activeCompareRunKeys
                BatchFamilyKind.TRANSLATION -> batch.key in live.activeTranslationRunIds
                BatchFamilyKind.FAN_META -> batch.key in live.activeFanMetaRunKeys
                BatchFamilyKind.OTHER,
                BatchFamilyKind.REGENERATE,
                BatchFamilyKind.RESPONSES -> false
            }
            when (batch.kind) {
                BatchFamilyKind.FAN_META -> {
                    if (!fanMetaTouched(row) || activeRun) false
                    else {
                        val fmError = !row.titleErrorMessage.isNullOrBlank() || !row.iconErrorMessage.isNullOrBlank()
                        if (errors) fmError
                        else !row.content.isNullOrBlank() &&
                            row.title.isNullOrBlank() &&
                            row.icon.isNullOrBlank() &&
                            !fmError &&
                            row.id !in live.runningFanMetaRowIds &&
                            live.nowMs - row.timestamp >= live.stalePlaceholderMs
                    }
                }
                else -> if (errors) errored(row) else interrupted(row, live, activeRun = activeRun)
            }
        }
    }
}
