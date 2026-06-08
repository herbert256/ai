package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.AuditLog
import com.ai.data.PricingCache
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
import com.ai.data.withTraceFilenameSink
import com.ai.data.withTracerTags
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.model.Worker
import com.ai.ui.helpers.translationRunGroupingId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) : BatchEngine<TransRankRunKey, String, TransRankCellState, TransRankRunState>() {
    override fun copyWithItems(run: TransRankRunState, items: Map<String, TransRankCellState>) =
        run.copy(cells = items)

    private val cellJobs = ConcurrentHashMap<String, Job>()
    private val runJobs = ConcurrentHashMap<TransRankRunKey, Job>()

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

    private data class Judge(val worker: Worker, val providerId: String, val model: String) {
        val key: String get() = "$providerId/$model"
    }

    private fun resolveJudges(aiSettings: Settings, prompt: InternalPrompt): List<Judge> =
        prompt.workers.flatMap { aiSettings.expandWorker(it) }
            .distinctBy { "${it.provider}/${it.model}" }
            .mapNotNull { w ->
                val raw = aiSettings.resolveWorker(w) ?: return@mapNotNull null
                Judge(w, raw.provider.id, aiSettings.getEffectiveModelForAgent(raw))
            }

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

    /** Keep at most [com.ai.data.TRANSRANK_ITEMS_PER_TRANSLATOR] random items
     *  per translator model (so not every translation of a big run is judged). */
    private fun cappedItems(items: List<ScorableItem>): List<ScorableItem> =
        items.groupBy { "${it.translatorProviderId}/${it.translatorModel}" }
            .flatMap { (_, list) -> list.shuffled().take(com.ai.data.TRANSRANK_ITEMS_PER_TRANSLATOR) }

    /** The number of scoring calls a run would make right now — for the confirm
     *  popup. Mirrors [startRun]'s prompt/judge/item resolution; the random cap
     *  doesn't change the COUNT (only which items), so this matches the build. */
    suspend fun plannedCellCount(
        context: Context, reportId: String, sourceTranslationRunId: String,
        overrideWorkers: List<Worker>? = null
    ): Int = withContext(Dispatchers.IO) {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val report = ReportStorage.getReport(context, reportId) ?: return@withContext 0
        val prompt = rankPrompt(aiSettings)?.let {
            when {
                report.useReportModelsAsWorkers -> it.copy(workers = reportModelWorkers(report))
                overrideWorkers != null -> it.copy(workers = overrideWorkers)
                else -> it
            }
        } ?: return@withContext 0
        val judges = resolveJudges(aiSettings, prompt)
        if (judges.isEmpty()) return@withContext 0
        val items = cappedItems(scorableItems(context, report, sourceTranslationRunId))
        items.sumOf { item -> judges.count { it.key != "${item.translatorProviderId}/${item.translatorModel}" } }
    }

    private data class PendingCell(
        val judge: Judge, val item: ScorableItem, val placeholder: SecondaryResult
    )

    private fun transitionCell(key: TransRankRunKey, cKey: String, update: (TransRankCellState) -> TransRankCellState) =
        transitionItem(key, cKey, update)

    fun runByKey(key: TransRankRunKey): TransRankRunState? = _runs.value[key]

    // -----------------------------------------------------------------
    // Launch
    // -----------------------------------------------------------------

    fun startRun(
        context: Context, reportId: String, sourceTranslationRunId: String,
        langName: String, langNative: String,
        buildKey: String? = null, overrideWorkers: List<Worker>? = null
    ): Job? {
        val rk = transRankRunKey(reportId, sourceTranslationRunId)
        runJobs[rk]?.let { if (it.isActive) return it }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "transrank/rank", runId = runId) {
                    val aiSettings = appViewModel.uiState.value.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    // ♻️ report-models become the judge panel, winning over a *SELECT pick.
                    val prompt = rankPrompt(aiSettings)?.let {
                        when {
                            report.useReportModelsAsWorkers -> it.copy(workers = reportModelWorkers(report))
                            overrideWorkers != null -> it.copy(workers = overrideWorkers)
                            else -> it
                        }
                    }
                    if (prompt == null) {
                        AppLog.w("TransRank", "workers/translate-rank prompt not configured — aborting")
                        return@withTracerTags
                    }
                    val judges = resolveJudges(aiSettings, prompt)
                    if (judges.isEmpty()) {
                        AppLog.w("TransRank", "no resolvable judges in the swarm — aborting")
                        return@withTracerTags
                    }
                    // Cap each translator to at most TRANSRANK_ITEMS_PER_TRANSLATOR
                    // random items (like Judge-the-judges' 25-match cap) so the
                    // batch doesn't explode on a report with many answers.
                    val items = cappedItems(scorableItems(context, report, sourceTranslationRunId))
                    // A cell only exists where a DIFFERENT model judges the item.
                    val cellCount = items.sumOf { item -> judges.count { it.key != "${item.translatorProviderId}/${item.translatorModel}" } }
                    if (cellCount == 0) {
                        AppLog.w("TransRank", "nothing to rank (items=${items.size}, judges=${judges.size})")
                        return@withTracerTags
                    }
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    AuditLog.append(reportId, "Start Rank-the-translators ($langName) — ${items.size} items × ${judges.size} judges")
                    val scopeEncoded = SecondaryScope.AllReports.encode()

                    val aggregate = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.TRANSRANK, AGG_PROVIDER, AGG_MODEL, "Rank the translators"
                    ) {
                        it.copy(
                            tournamentRole = TRANSRANK_ROLE_AGGREGATE, tournamentJudgeRunId = runId,
                            translationRunId = sourceTranslationRunId,
                            targetLanguage = langName, targetLanguageNative = langNative,
                            metaPromptId = prompt.id, metaPromptName = prompt.name,
                            runId = runId, secondaryScope = scopeEncoded
                        )
                    }

                    val pending = mutableListOf<PendingCell>()
                    val newCells = LinkedHashMap<String, TransRankCellState>()
                    if (buildKey != null) appViewModel.beginBuild(buildKey, cellCount, "Building translator ranking")
                    var built = 0
                    for (item in items) {
                        val translatorKey = "${item.translatorProviderId}/${item.translatorModel}"
                        for (judge in judges) {
                            if (judge.key == translatorKey) continue
                            val placeholder = SecondaryResultStorage.create(
                                context, reportId, SecondaryKind.TRANSRANK,
                                judge.providerId, judge.model, "${judge.providerId} / ${judge.model}"
                            ) {
                                it.copy(
                                    tournamentRole = TRANSRANK_ROLE_CELL, tournamentJudgeRunId = runId,
                                    translationRunId = sourceTranslationRunId,
                                    compareToResultId = item.translationRowId,
                                    matchResponseAId = item.translatorProviderId,
                                    matchResponseBId = item.translatorModel,
                                    targetLanguage = langName, targetLanguageNative = langNative,
                                    metaPromptId = prompt.id, metaPromptName = prompt.name,
                                    runId = runId, secondaryScope = scopeEncoded
                                )
                            }
                            pending.add(PendingCell(judge, item, placeholder))
                            placeholder.toTransRankCellState()?.let { newCells[it.key] = it }
                            if (buildKey != null) { built++; if (built % 5 == 0) appViewModel.updateBuild(buildKey, built) }
                        }
                    }
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
                    recomputeAndPersistAggregate(context, rk)
                    AuditLog.append(reportId, "End Rank-the-translators ($langName)")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                finalizeLeftoverCells(context, rk)
                if (buildKey != null) {
                    val p = appViewModel.batchBuildProgress.value[buildKey]
                    if (p != null && !p.done) appViewModel.clearBuild(buildKey)
                }
            }
        }
        runJobs[rk] = job
        job.invokeOnCompletion { runJobs.remove(rk, job) }
        return job
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
            register = { item, d ->
                cellJobs[item.placeholder.id] = d
                d.invokeOnCompletion { cellJobs.remove(item.placeholder.id, d) }
            },
            benchEnabled = com.ai.data.ModelCooldownStore.typeABenchEnabled,
            benchKey = { item -> item.judge.providerId to item.judge.model },
            onBenchRetry = { item -> restoreBenchedCellForRequeue(context, key, item) }
        ) { item ->
            if (!SecondaryResultStorage.exists(context, reportId, item.placeholder.id)) return@runThrottledBatch
            runOneCell(context, key, prompt, item)
        }
    }

    private fun restoreBenchedCellForRequeue(context: Context, key: TransRankRunKey, item: PendingCell) {
        val reportId = _runs.value[key]?.reportId ?: return
        val cKey = transRankCellKey(item.judge.providerId, item.judge.model, item.item.translationRowId)
        SecondaryResultStorage.get(context, reportId, item.placeholder.id)?.let { saved ->
            SecondaryResultStorage.save(context, saved.copy(content = null, errorMessage = null, durationMs = null, tokenUsage = null))
        }
        transitionCell(key, cKey) {
            it.copy(status = TransRankCellStatus.PENDING, content = null, errorMessage = null, durationMs = null)
        }
    }

    private suspend fun runOneCell(context: Context, key: TransRankRunKey, prompt: InternalPrompt, item: PendingCell) {
        val reportId = _runs.value[key]?.reportId ?: return
        val cKey = transRankCellKey(item.judge.providerId, item.judge.model, item.item.translationRowId)
        val rowId = item.placeholder.id
        transitionCell(key, cKey) { it.copy(status = TransRankCellStatus.RUNNING) }
        val started = System.currentTimeMillis()
        val aiSettings = appViewModel.uiState.value.aiSettings
        val resolved = prompt.text
            .replace("@LANGUAGE_FROM@", item.item.languageFrom)
            .replace("@LANGUAGE_TO@", item.item.languageTo)
            .replace("@ORIGINAL@", item.item.originalText)
            .replace("@TRANSLATION@", item.item.translatedText)
        try {
            val raw = aiSettings.resolveWorker(item.judge.worker)
            if (raw == null) {
                recordCellError(context, reportId, rowId, item.placeholder, "judge ${item.judge.key} could not be resolved", started)
            } else {
                val agent = raw.copy(
                    apiKey = aiSettings.getEffectiveApiKeyForAgent(raw),
                    model = aiSettings.getEffectiveModelForAgent(raw)
                )
                val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val resp = withTraceFilenameSink(traceSink) {
                    appViewModel.repository.analyzeWithAgent(agent, "", resolved, context = context, baseUrl = baseUrl, retry = false)
                }
                if (resp.isSuccess && parseScoreAndReason(resp.analysis)?.first != null) {
                    val tu = resp.tokenUsage
                    val inT = tu?.inputTokens ?: 0
                    val outT = tu?.outputTokens ?: 0
                    var inCost = 0.0; var outCost = 0.0
                    if (tu != null && (inT > 0 || outT > 0)) {
                        val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
                        val split = PricingCache.computeInOutCost(tu, pricing)
                        inCost = split.first; outCost = split.second
                        appViewModel.settingsPrefs.updateUsageStatsAsync(agent.provider, agent.model, tu, kind = "transrank")
                    }
                    SecondaryResultStorage.recordTournamentMatch(
                        context, reportId, rowId, item.judge.providerId, item.judge.model,
                        resp.analysis.orEmpty(), inT, outT, inCost, outCost,
                        System.currentTimeMillis() - started, traceFile = traceSink.get(),
                        tokenUsage = tu
                    )
                } else {
                    recordCellError(context, reportId, rowId, item.placeholder,
                        resp.error?.takeIf { it.isNotBlank() } ?: "judge produced no score", started)
                }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                val saved = SecondaryResultStorage.get(context, reportId, rowId)
                if (saved == null) dropItem(key, cKey)
                else transitionCell(key, cKey) {
                    saved.toTransRankCellState() ?: it.copy(status = TransRankCellStatus.ERROR, errorMessage = "Cell row could not be parsed")
                }
            }
        }
    }

    private fun recordCellError(context: Context, reportId: String, rowId: String, placeholder: SecondaryResult, message: String, started: Long) {
        val cur = SecondaryResultStorage.get(context, reportId, rowId) ?: placeholder
        SecondaryResultStorage.save(context, cur.copy(errorMessage = message, durationMs = System.currentTimeMillis() - started))
    }

    private fun recomputeAndPersistAggregate(context: Context, key: TransRankRunKey) {
        val run = _runs.value[key] ?: return
        val aggId = run.aggregateRowId ?: return
        val rows = aggregateTranslatorRanks(run.cells.values)
        val row = SecondaryResultStorage.get(context, run.reportId, aggId) ?: return
        SecondaryResultStorage.save(context, row.copy(content = rows.toTransRankJson(), durationMs = row.durationMs ?: 0))
    }

    // -----------------------------------------------------------------
    // Hydration / lifecycle
    // -----------------------------------------------------------------

    suspend fun hydrate(context: Context, reportId: String) {
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
            _runs.update {
                it + (key to TransRankRunState(
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
            val run = _runs.value[key] ?: return@launch
            // A synthetic (prompt-unavailable) run carries blank prompt text —
            // it can't be re-run. See audit bug 4.
            if (run.prompt.text.isBlank()) return@launch
            val report = ReportStorage.getReport(context, run.reportId) ?: return@launch
            val items = scorableItems(context, report, run.sourceTranslationRunId).associateBy { it.translationRowId }
            // Recover each judge's ORIGINAL worker (parameter presets, system
            // prompt, flock/swarm/agent refs) from the run's prompt rather than a
            // minimal provider/model-only Worker, so the retry replays the same
            // call shape as the first run. Fall back to the minimal worker only
            // when the judge is no longer resolvable in the swarm. See audit bug 2.
            val aiSettings = appViewModel.uiState.value.aiSettings
            val judgesByKey = resolveJudges(aiSettings, run.prompt).associateBy { it.key }
            val failed = run.cells.values.filter { it.status == TransRankCellStatus.ERROR }
            val resets = failed.mapNotNull { c ->
                val sc = items[c.translationRowId] ?: return@mapNotNull null
                val cleared = SecondaryResultStorage.get(context, run.reportId, c.id)?.copy(
                    content = null, errorMessage = null, inputCost = null, outputCost = null,
                    tokenUsage = null, durationMs = null, timestamp = System.currentTimeMillis()
                ) ?: return@mapNotNull null
                SecondaryResultStorage.save(context, cleared)
                transitionCell(key, c.key) {
                    it.copy(status = TransRankCellStatus.PENDING, content = null, score = null, reason = null,
                        errorMessage = null, inputCost = null, outputCost = null, durationMs = null, tokenUsage = null)
                }
                val judge = judgesByKey["${c.judgeProviderId}/${c.judgeModel}"]
                    ?: Judge(Worker(provider = c.judgeProviderId, model = c.judgeModel), c.judgeProviderId, c.judgeModel)
                PendingCell(judge, sc, cleared)
            }
            if (resets.isEmpty()) return@launch
            withTracerTags(reportId = run.reportId, category = "transrank/rank") {
                dispatchCells(context, key, run.prompt, resets)
            }
            recomputeAndPersistAggregate(context, key)
        }

    fun deleteRun(context: Context, key: TransRankRunKey): Job {
        // Stop the UI updating FIRST: cancel the build/dispatch job and drop the
        // run from the flow synchronously. The live screen + the Manage row both
        // read _runs, so they stop rendering this run immediately — and the
        // per-cell finally `transitionCell` calls + finalizeLeftoverCells now
        // no-op (run gone), so we skip the drive-everything-to-ERROR re-render
        // storm that made delete feel slow. Cancel (no join) the cell jobs;
        // recordTournamentMatch guards `!exists`, so a late write can't recreate
        // a deleted row. Disk cleanup runs in the background.
        val run = _runs.value[key]
        runJobs[key]?.cancel()
        run?.cells?.values?.forEach { cellJobs[it.id]?.cancel() }
        dropRun(key)
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
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val rows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TRANSRANK)
                .filter {
                    it.translationRunId == sourceRunId &&
                        (victimRunId == null || it.tournamentJudgeRunId == victimRunId)
                }
            if (rows.isEmpty()) return@launch
            val costDelta = rows.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
            rows.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
    }

    fun cancelAllForReport(reportId: String) {
        _runs.value.keys.filter { it.startsWith("$reportId|") }.forEach { k ->
            runJobs[k]?.cancel()
            _runs.value[k]?.cells?.values?.forEach { cellJobs[it.id]?.cancel() }
            _runs.update { it - k }
        }
    }

    private suspend fun finalizeLeftoverCells(context: Context, key: TransRankRunKey) {
        withContext(kotlinx.coroutines.NonCancellable) {
            val run = _runs.value[key] ?: return@withContext
            run.cells.values.filter { it.status == TransRankCellStatus.PENDING || it.status == TransRankCellStatus.RUNNING }
                .forEach { c ->
                    val cur = SecondaryResultStorage.get(context, run.reportId, c.id) ?: return@forEach
                    if (cur.errorMessage == null && cur.content.isNullOrBlank() && cur.durationMs == null && !cellJobs.containsKey(c.id)) {
                        SecondaryResultStorage.save(context, cur.copy(errorMessage = "Interrupted — run stopped before this score finished", durationMs = 0))
                        transitionCell(key, c.key) { it.copy(status = TransRankCellStatus.ERROR, errorMessage = "Interrupted", durationMs = 0) }
                    }
                }
            recomputeAndPersistAggregate(context, key)
        }
    }
}
