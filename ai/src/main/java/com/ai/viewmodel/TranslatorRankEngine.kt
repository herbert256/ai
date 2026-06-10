package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.AuditLog
import com.ai.data.NetworkSettings
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.SecondaryScope
import com.ai.data.TRANSRANK_ROLE_AGGREGATE
import com.ai.data.TRANSRANK_ROLE_CELL
import com.ai.data.TransRankCellState
import com.ai.data.TransRankCellStatus
import com.ai.data.TransRankRunKey
import com.ai.data.TransRankRunState
import com.ai.data.aggregateTranslatorRanks
import com.ai.data.parseScoreAndReason
import com.ai.data.toTransRankCellState
import com.ai.data.toTransRankJson
import com.ai.data.transRankCellKey
import com.ai.data.transRankRunKey
import com.ai.data.withTracerTags
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.model.Worker
import com.ai.ui.helpers.translationRunGroupingId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Runtime owner for the "Rank the translators" batch (🏅 on a Translations
 * row). Reuses an existing translation run: every long-form translated item
 * (agent answers + fan-out / meta responses) is scored 0–100 by every model
 * in the `translate-rank` swarm EXCEPT the model that produced that item, then
 * the translator models are ranked by their average received score.
 *
 * Structure mirrors [JudgeEvalEngine] (fixed-host per-cell judging), but the
 * cells are (translated-item × judge) and the aggregate is a per-translator
 * ranking. Run key is per LANGUAGE: `"$reportId|$sourceTranslationRunId"`.
 */
class TranslatorRankEngine internal constructor(
    override val appViewModel: AppViewModel,
    override val reportViewModel: ReportViewModel
) : SecondaryBatchEngine<TransRankRunKey, TransRankCellState, TransRankRunState>() {
    override fun copyWithItems(run: TransRankRunState, items: Map<String, TransRankCellState>) =
        run.copy(cells = items)

    override val secondaryKind = SecondaryKind.TRANSRANK
    override val logTag = "TransRank"
    override val itemNoun = "score"
    override fun reportIdOf(runKey: TransRankRunKey) = runKey.substringBefore("|")
    override fun runKeysForReport(reportId: String) =
        _runs.value.keys.filter { it.startsWith("$reportId|") }
    override fun terminalizeItem(item: TransRankCellState, message: String) =
        item.copy(status = TransRankCellStatus.ERROR, errorMessage = message, durationMs = 0)
    override fun itemFromRow(row: SecondaryResult) = row.toTransRankCellState()
    override fun markItemRunning(item: TransRankCellState) = item.copy(status = TransRankCellStatus.RUNNING)
    override fun isItemRow(run: TransRankRunState?, row: SecondaryResult) =
        run != null && row.tournamentRole == TRANSRANK_ROLE_CELL &&
            row.tournamentJudgeRunId == run.runId
    override fun aggregateRowIdOf(run: TransRankRunState) = run.aggregateRowId
    override fun canRedispatch(context: Context, run: TransRankRunState) =
        run.prompt.text.isNotBlank()   // synthetic prompt — can't re-run; audit bug 4
    override val requeueBuildLabel = "Re-queuing translator ranking"
    // The base clearRowForRerun keeps the row's (providerId, model) — the
    // re-score must go to the same judge.
    override fun resetItemToPending(item: TransRankCellState) =
        item.copy(
            status = TransRankCellStatus.PENDING, content = null, score = null, reason = null,
            errorMessage = null, inputCost = null, outputCost = null, durationMs = null, tokenUsage = null
        )

    /** Map rows back to (judge × scorable-item) cells, recovering each judge's
     *  ORIGINAL worker from the run's prompt — parameter presets / system
     *  prompt / flock-swarm refs — so a retry replays the same call shape,
     *  falling back to a minimal provider/model Worker only when the judge is
     *  no longer in the swarm (audit bug 2). */
    override suspend fun redispatchRows(context: Context, runKey: TransRankRunKey, rows: List<SecondaryResult>) {
        val run = _runs.value[runKey] ?: return
        val report = ReportStorage.getReport(context, run.reportId) ?: return
        val aiSettings = appViewModel.uiState.value.aiSettings
        // ♻️ Models-as-workers: resolve the judges' original worker shape
        // against the report-model panel (what startRun dispatched with),
        // not the configured swarm — otherwise every pinned judge misses
        // the lookup and falls back to a bare provider/model worker.
        val effPrompt = run.prompt.withWorkerOverrides(report)
        val items = scorableItems(context, report, run.sourceTranslationRunId).associateBy { it.translationRowId }
        val judgesByKey = resolveJudges(aiSettings, effPrompt).associateBy { it.key }
        val cellsById = run.cells.values.associateBy { it.id }
        val pending = rows.mapNotNull { row ->
            val c = cellsById[row.id] ?: return@mapNotNull null
            val sc = items[c.translationRowId] ?: return@mapNotNull null
            val judge = judgesByKey["${c.judgeProviderId}/${c.judgeModel}"]
                ?: ResolvedJudge(Worker(provider = c.judgeProviderId, model = c.judgeModel), c.judgeProviderId, c.judgeModel)
            PendingCell(judge, sc, row)
        }
        if (pending.isEmpty()) return
        withTracerTags(reportId = run.reportId, category = "transrank/rank", runId = run.runId) {
            dispatchCells(context, runKey, effPrompt, pending)
        }
    }

    // Run/cell coroutines + resume-scan dedup now live in the shared BatchEngine
    // base (registerRunJob / registerItemJob / beginResumeScan / runJobOf /
    // itemJobOf / activeRunJobKeys / hasItemJob).

    private companion object {
        const val PROMPT_NAME = "translate-rank"
        const val AGG_PROVIDER = "*transrank"
        const val AGG_MODEL = "aggregate"
        // Only long-form bodies are scored — agent answers + fan-out / meta
        // responses. Titles and the report prompt are skipped.
        val SCORED_SOURCE_KINDS = setOf("AGENT", "META")
    }

    private fun rankPrompt(aiSettings: Settings): InternalPrompt? =
        aiSettings.internalPrompts.firstOrNull { it.category == "workers" && it.name == PROMPT_NAME }

    // Judge resolution lives in the shared fixed-judge helpers
    // ([ResolvedJudge] / [resolveJudges] in SecondaryCellCalls.kt).

    /** One scorable translated item recovered from the translation run. */
    private data class ScorableItem(
        val translationRowId: String,
        val translatorProviderId: String,
        val translatorModel: String,
        val originalText: String,
        val translatedText: String,
        val languageFrom: String,
        val languageTo: String
    )

    private fun originalTextFor(context: Context, report: Report, row: SecondaryResult): String? =
        when (row.translateSourceKind) {
            "AGENT" -> report.agents.firstOrNull { it.agentId == row.translateSourceTargetId }?.responseBody
            "META" -> row.translateSourceTargetId?.let {
                SecondaryResultStorage.get(context, report.id, it)?.content
            }
            else -> null
        }

    private fun scorableItems(context: Context, report: Report, sourceTranslationRunId: String): List<ScorableItem> {
        val from = report.languageName?.takeIf { it.isNotBlank() } ?: "the original language"
        return SecondaryResultStorage.listForReport(context, report.id, SecondaryKind.TRANSLATE)
            .filter {
                translationRunGroupingId(it) == sourceTranslationRunId &&
                    it.translateSourceKind in SCORED_SOURCE_KINDS &&
                    !it.content.isNullOrBlank() && it.model.isNotBlank()
            }
            .mapNotNull { row ->
                val original = originalTextFor(context, report, row)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ScorableItem(
                    translationRowId = row.id,
                    translatorProviderId = row.providerId,
                    translatorModel = row.model,
                    originalText = original,
                    translatedText = row.content!!,
                    languageFrom = from,
                    languageTo = row.targetLanguage?.takeIf { it.isNotBlank() } ?: "the target language"
                )
            }
    }

    private data class CellCandidate(val item: ScorableItem, val judge: ResolvedJudge)

    /** Every (item × judge≠translator) pair, then capped to
     *  [com.ai.data.TRANSRANK_CELLS_PER_TRANSLATOR] random pairs PER TRANSLATOR
     *  model — so the whole batch is at most (#translators × 25) cells. With few
     *  items per translator each item still draws several judges; with many
     *  items the budget spreads thinner. */
    private fun cappedCandidates(
        items: List<ScorableItem>, judges: List<ResolvedJudge>, sourceTranslationRunId: String
    ): List<CellCandidate> =
        items
            .flatMap { item ->
                val tk = "${item.translatorProviderId}/${item.translatorModel}"
                judges.filter { it.key != tk }.map { CellCandidate(item, it) }
            }
            .groupBy { "${it.item.translatorProviderId}/${it.item.translatorModel}" }
            // Deterministic per (translation run, translator): the confirm
            // preview, the run, AND any delete-and-re-run draw the SAME 25-cell
            // sample, so the ranking is reproducible instead of re-rolled each
            // time `shuffled()` runs. See audit report bug 5.
            .flatMap { (translatorKey, list) ->
                val seed = "$sourceTranslationRunId|$translatorKey".hashCode().toLong()
                list.shuffled(kotlin.random.Random(seed)).take(com.ai.data.TRANSRANK_CELLS_PER_TRANSLATOR)
            }

    /** The number of scoring calls a run would make right now — for the confirm
     *  popup. Mirrors [startRun]'s prompt/judge/cap resolution exactly. */
    suspend fun plannedCellCount(
        context: Context, reportId: String, sourceTranslationRunId: String,
        overrideWorkers: List<Worker>? = null
    ): Int = withContext(Dispatchers.IO) {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val report = ReportStorage.getReport(context, reportId) ?: return@withContext 0
        val prompt = rankPrompt(aiSettings)?.withWorkerOverrides(report, overrideWorkers) ?: return@withContext 0
        val judges = resolveJudges(aiSettings, prompt)
        if (judges.isEmpty()) return@withContext 0
        cappedCandidates(scorableItems(context, report, sourceTranslationRunId), judges, sourceTranslationRunId).size
    }

    private data class PendingCell(
        val judge: ResolvedJudge, val item: ScorableItem, val placeholder: SecondaryResult
    )

    fun runByKey(key: TransRankRunKey): TransRankRunState? = _runs.value[key]

    /** Cell row ids currently parked on a provider rate/concurrency gate — the
     *  L1 "Wait" counter, so throttled cells don't look like a stuck Queue. */
    val throttledCells: kotlinx.coroutines.flow.StateFlow<Set<String>> get() = appViewModel.throttledTransRankCells

    // -----------------------------------------------------------------
    // Launch
    // -----------------------------------------------------------------

    fun startRun(
        context: Context, reportId: String, sourceTranslationRunId: String,
        langName: String, langNative: String,
        buildKey: String? = null, overrideWorkers: List<Worker>? = null
    ): Job? {
        val rk = transRankRunKey(reportId, sourceTranslationRunId)
        return launchRun(context, rk, buildKey, "transrank/rank") { runId ->
            val aiSettings = appViewModel.uiState.value.aiSettings
            val report = ReportStorage.getReport(context, reportId) ?: return@launchRun
            // ♻️ report-models become the judge panel, winning over a *SELECT pick.
            val prompt = rankPrompt(aiSettings)?.withWorkerOverrides(report, overrideWorkers)
            if (prompt == null) {
                AppLog.w("TransRank", "workers/translate-rank prompt not configured — aborting")
                return@launchRun
            }
            val judges = resolveJudges(aiSettings, prompt)
            if (judges.isEmpty()) {
                AppLog.w("TransRank", "no resolvable judges in the swarm — aborting")
                return@launchRun
            }
            // Cap each TRANSLATOR to at most TRANSRANK_CELLS_PER_TRANSLATOR
            // (item × judge) cells, so the whole batch is at most
            // (#translators × 25) — e.g. 10 translator models → ≤ 250.
            val candidates = cappedCandidates(scorableItems(context, report, sourceTranslationRunId), judges, sourceTranslationRunId)
            val cellCount = candidates.size
            if (cellCount == 0) {
                AppLog.w("TransRank", "nothing to rank (judges=${judges.size})")
                return@launchRun
            }
            ReportStorage.bumpReportTimestamp(context, reportId)
            AuditLog.append(reportId, "Start Rank-the-translators ($langName) — $cellCount cells × ${judges.size} judges")
            val scopeEncoded = SecondaryScope.AllReports.encode()

            val aggregate = SecondaryResult(
                id = java.util.UUID.randomUUID().toString(),
                reportId = reportId,
                kind = SecondaryKind.TRANSRANK,
                providerId = AGG_PROVIDER,
                model = AGG_MODEL,
                agentName = "Rank the translators",
                timestamp = System.currentTimeMillis(),
                content = null,
                tournamentRole = TRANSRANK_ROLE_AGGREGATE,
                tournamentJudgeRunId = runId,
                translationRunId = sourceTranslationRunId,
                targetLanguage = langName,
                targetLanguageNative = langNative,
                metaPromptId = prompt.id,
                metaPromptName = prompt.name,
                runId = runId,
                secondaryScope = scopeEncoded
            )

            val pending = mutableListOf<PendingCell>()
            val newCells = LinkedHashMap<String, TransRankCellState>()
            if (buildKey != null) appViewModel.beginBuild(buildKey, cellCount, "Building translator ranking")
            var built = 0
            for ((item, judge) in candidates) {
                val placeholder = SecondaryResult(
                    id = java.util.UUID.randomUUID().toString(),
                    reportId = reportId,
                    kind = SecondaryKind.TRANSRANK,
                    providerId = judge.providerId,
                    model = judge.model,
                    agentName = "${judge.providerId} / ${judge.model}",
                    timestamp = System.currentTimeMillis(),
                    content = null,
                    tournamentRole = TRANSRANK_ROLE_CELL,
                    tournamentJudgeRunId = runId,
                    translationRunId = sourceTranslationRunId,
                    compareToResultId = item.translationRowId,
                    matchResponseAId = item.translatorProviderId,
                    matchResponseBId = item.translatorModel,
                    targetLanguage = langName,
                    targetLanguageNative = langNative,
                    metaPromptId = prompt.id,
                    metaPromptName = prompt.name,
                    runId = runId,
                    secondaryScope = scopeEncoded
                )
                pending.add(PendingCell(judge, item, placeholder))
                if (buildKey != null) { built++; if (built % 5 == 0) appViewModel.updateBuild(buildKey, built) }
            }
            val savedIds = SecondaryResultStorage.saveAll(context, listOf(aggregate) + pending.map { it.placeholder })
                .mapTo(HashSet()) { it.id }
            if (aggregate.id !in savedIds) return@launchRun
            pending.removeAll { it.placeholder.id !in savedIds }
            pending.forEach { item -> item.placeholder.toTransRankCellState()?.let { newCells[it.key] = it } }
            if (buildKey != null) appViewModel.finishBuild(buildKey)

            _runs.update { runs ->
                runs + (rk to TransRankRunState(
                    key = rk, reportId = reportId, runId = runId,
                    sourceTranslationRunId = sourceTranslationRunId,
                    targetLanguageName = langName, targetLanguageNative = langNative,
                    prompt = prompt, cells = newCells, aggregateRowId = aggregate.id
                ))
            }

            dispatchCells(context, rk, prompt, pending)
            recomputeAggregate(context, rk)
            AuditLog.append(reportId, "End Rank-the-translators ($langName)")
        }
    }

    private suspend fun dispatchCells(
        context: Context, key: TransRankRunKey, prompt: InternalPrompt, items: List<PendingCell>
    ) {
        if (items.isEmpty()) return
        val reportId = _runs.value[key]?.reportId ?: return
        runThrottledBatch(
            items = items,
            hostOf = { AppService.findById(it.judge.providerId)?.let { s -> providerHost(s) } },
            subCap = ApiCallCaps.workers,
            onThrottled = { appViewModel.updateThrottledTransRankCells { s -> s + it.placeholder.id } },
            onCleared = { appViewModel.updateThrottledTransRankCells { s -> s - it.placeholder.id } },
            register = { item, d ->
                registerItemJob(item.placeholder.id, d)
            },
            benchEnabled = com.ai.data.ModelCooldownStore.typeABenchEnabled,
            benchKey = { item -> item.judge.providerId to item.judge.model },
            onBenchRetry = { item -> restoreBenchedCellForRequeue(context, key, item) }
        ) { item ->
            if (!SecondaryResultStorage.exists(context, reportId, item.placeholder.id)) return@runThrottledBatch
            try {
                runOneCell(context, key, prompt, item)
            } finally {
                appViewModel.updateThrottledTransRankCells { it - item.placeholder.id }
            }
        }
    }

    private fun restoreBenchedCellForRequeue(context: Context, key: TransRankRunKey, item: PendingCell) {
        val reportId = _runs.value[key]?.reportId ?: return
        val cKey = transRankCellKey(item.judge.providerId, item.judge.model, item.item.translationRowId)
        clearRowForBenchRequeue(context, reportId, item.placeholder.id)
        transitionItem(key, cKey) {
            it.copy(status = TransRankCellStatus.PENDING, content = null, errorMessage = null, durationMs = null)
        }
    }

    private suspend fun runOneCell(context: Context, key: TransRankRunKey, prompt: InternalPrompt, item: PendingCell) {
        val reportId = _runs.value[key]?.reportId ?: return
        val cKey = transRankCellKey(item.judge.providerId, item.judge.model, item.item.translationRowId)
        val rowId = item.placeholder.id
        transitionItem(key, cKey) { it.copy(status = TransRankCellStatus.RUNNING) }
        val started = System.currentTimeMillis()
        val aiSettings = appViewModel.uiState.value.aiSettings
        val resolved = prompt.text
            .replace("@LANGUAGE_FROM@", item.item.languageFrom)
            .replace("@LANGUAGE_TO@", item.item.languageTo)
            .replace("@ORIGINAL@", item.item.originalText)
            .replace("@TRANSLATION@", item.item.translatedText)
        try {
            // Per-cell wall-clock ceiling (the user-tunable "Batch item"
            // timeout) — caught locally so a wedged judge fails just this
            // cell, not the batch. Each bench-requeue attempt re-enters
            // this body, so every attempt gets a fresh ceiling.
            val res = withTimeout(NetworkSettings.batchItemTimeoutMs) {
                runFixedJudgeCall(
                    appViewModel, context, aiSettings, item.judge, resolved,
                    usageKind = "transrank", noArtifactMessage = "judge produced no score"
                ) { resp -> parseScoreAndReason(resp.analysis)?.first != null }
            }
            when (res) {
                is FixedJudgeOutcome.Accepted -> SecondaryResultStorage.recordTournamentMatch(
                    context, reportId, rowId, item.judge.providerId, item.judge.model,
                    res.response.analysis.orEmpty(),
                    res.inTokens, res.outTokens, res.inCost, res.outCost,
                    System.currentTimeMillis() - started, traceFile = res.traceFile,
                    tokenUsage = res.tokenUsage
                )
                is FixedJudgeOutcome.Rejected ->
                    recordItemCallError(context, reportId, rowId, item.placeholder, res.message, started)
            }
        } catch (e: TimeoutCancellationException) {
            recordItemCallError(
                context, reportId, rowId, item.placeholder,
                "judge timed out after ${NetworkSettings.batchItemTimeoutSec}s", started
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One poisoned cell (a storage / parse / pricing throw) must
            // fail just this item — an escape would cancel every in-flight
            // sibling via the batch's coroutineScope.
            recordItemCallError(
                context, reportId, rowId, item.placeholder,
                "judge: ${e.javaClass.simpleName}: ${e.message}", started
            )
        } finally {
            settleItemFromDisk(context, key, cKey, rowId)
        }
    }

    override fun recomputeAggregate(context: Context, runKey: TransRankRunKey) {
        val key = runKey
        val run = _runs.value[key] ?: return
        val aggId = run.aggregateRowId ?: return
        // The aggregation math + JSON encode must never take the app down —
        // this runs from finalize/`finally` paths where a throw would escape
        // the coroutine. Swallow + log; a failed recompute just leaves the
        // previous aggregate in place (same guard as Tournament / JudgeEval).
        try {
            val rows = aggregateTranslatorRanks(run.cells.values)
            val row = SecondaryResultStorage.get(context, run.reportId, aggId) ?: return
            SecondaryResultStorage.save(context, row.copy(content = rows.toTransRankJson(), durationMs = row.durationMs ?: 0))
        } catch (e: Exception) {
            AppLog.w("TransRank", "recompute aggregate failed report=${run.reportId}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // -----------------------------------------------------------------
    // Hydration / lifecycle
    // -----------------------------------------------------------------

    override suspend fun hydrate(context: Context, reportId: String) {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val rows = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TRANSRANK)
        }
        val byRun = rows.filter { !it.tournamentJudgeRunId.isNullOrBlank() }.groupBy { it.tournamentJudgeRunId!! }
        // Keep only the LATEST transrank run per source-translation-run (language).
        val latestPerLang = byRun.values
            .mapNotNull { g -> g.maxByOrNull { it.timestamp }?.let { anchor -> anchor to g } }
            .groupBy { (anchor, _) -> anchor.translationRunId.orEmpty() }
            .mapNotNull { (_, runs) -> runs.maxByOrNull { (a, _) -> a.timestamp } }
        val realPrompt = rankPrompt(aiSettings)
        latestPerLang.forEach { (anchor, group) ->
            val sourceRunId = anchor.translationRunId.orEmpty()
            val key = transRankRunKey(reportId, sourceRunId)
            val aggRow = group.firstOrNull { it.tournamentRole == TRANSRANK_ROLE_AGGREGATE }
            val cells = group.mapNotNull { it.toTransRankCellState() }.associateBy { it.key }
            // Keep the run visible even if the translate-rank prompt was deleted
            // or renamed since it ran: fall back to a synthetic prompt built from
            // the row metadata (blank text / no workers) so the run hydrates
            // read-only. Restart is gated on a real prompt — a synthetic one has
            // blank text — see restartFailedCells. See audit bug 4.
            val prompt = realPrompt ?: InternalPrompt(
                id = anchor.metaPromptId ?: "",
                name = anchor.metaPromptName?.takeIf { it.isNotBlank() } ?: PROMPT_NAME,
                category = "workers"
            )
            // Don't re-publish a run whose delete is mid-flight (rows still on disk).
            _runs.update {
                if (isDeleting(key)) it else it + (key to TransRankRunState(
                    key = key, reportId = reportId, runId = anchor.tournamentJudgeRunId!!,
                    sourceTranslationRunId = sourceRunId,
                    targetLanguageName = anchor.targetLanguage ?: "",
                    targetLanguageNative = anchor.targetLanguageNative ?: anchor.targetLanguage ?: "",
                    prompt = prompt, cells = cells, aggregateRowId = aggRow?.id
                ))
            }
        }
    }

    fun restartFailedCells(context: Context, key: TransRankRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            restartItemsWhere(context, key) { it.status == TransRankCellStatus.ERROR }
        }

    /** Broken-work per-row restart: re-judge exactly the picked cell rows. */
    fun restartCellsByIds(context: Context, key: TransRankRunKey, rowIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            restartItemsWhere(context, key) { it.id in rowIds }
        }


    /** Broken-work "delete errored": drop every errored cell without re-firing. */
    fun removeFailedCells(context: Context, key: TransRankRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            removeItemsMatching(context, key) { it.status == TransRankCellStatus.ERROR }
        }

    /** Broken-work "delete unfinished": drop every stranded PENDING cell. */
    fun removeUnfinishedCells(context: Context, key: TransRankRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            removeItemsMatching(context, key) { it.status == TransRankCellStatus.PENDING }
        }

    /** Broken-work per-row delete. */
    fun removeCellsByIds(context: Context, key: TransRankRunKey, rowIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            removeItemsMatching(context, key) { it.id in rowIds }
        }

    /** Unlike the base deleteRun (which deletes the in-memory item rows),
     *  this sweeps the DISK by source-translation-run id, so a mid-build
     *  cancel — where startRun already wrote the aggregate but hasn't
     *  published the run — still removes every row (no orphan row). */
    override fun deleteRun(context: Context, runKey: TransRankRunKey): Job {
        val key = runKey
        // deleteRunDeferred stops the UI FIRST: it drops the run from the flow
        // synchronously (the live screen + the Manage row read _runs, so they
        // stop rendering at once — and the per-cell finally `transitionCell`
        // calls + finalizeLeftoverItems then no-op, skipping the
        // drive-everything-to-ERROR re-render storm that made delete feel slow)
        // and marks it deleting so hydrate won't re-publish it. The disk sweep
        // runs in the background.
        val run = _runs.value[key]
        val runJob = runJobOf(key)
        val cellJobs = run?.cells?.values?.mapNotNull { itemJobOf(it.id) } ?: emptyList()
        // key = "reportId|sourceTranslationRunId". Sweep the disk by source-run
        // id rather than the (possibly not-yet-populated) in-memory cell map, so
        // a mid-build cancel — where startRun already wrote the aggregate but
        // hasn't published the run — still removes every row (no orphan row).
        val reportId = key.substringBefore("|")
        val sourceRunId = key.substringAfter("|")
        // Narrow the disk sweep to THIS run's id when it's known (the normal,
        // run-is-published case) so deleting one ranking attempt can't take out
        // a sibling/older attempt for the same source translation run / language.
        // Only when the run was never published (mid-build cancel, run == null)
        // do we fall back to the broad per-source-run sweep — there is only the
        // one in-flight run then, and the broad pass still clears its
        // just-written aggregate orphan. The cost delta is summed from this same
        // narrowed victim set, so the report total is adjusted by exactly what
        // was deleted.
        val victimRunId = run?.runId
        return deleteRunDeferred(appViewModel.viewModelScope, key, runJob, cellJobs) {
            val rows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TRANSRANK)
                .filter {
                    it.translationRunId == sourceRunId &&
                        (victimRunId == null || it.tournamentJudgeRunId == victimRunId)
                }
            if (rows.isEmpty()) return@deleteRunDeferred
            val costDelta = rows.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
            rows.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
    }

}
