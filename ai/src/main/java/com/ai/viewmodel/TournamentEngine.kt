package com.ai.viewmodel

import android.content.Context
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.AuditLog
import com.ai.data.MatchState
import com.ai.data.MatchStatus
import com.ai.data.PricingCache
import com.ai.data.ProviderThrottle
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.SecondaryScope
import com.ai.data.TOURNAMENT_PENDING_MODEL
import com.ai.data.TOURNAMENT_PENDING_PROVIDER
import com.ai.data.TournamentMethod
import com.ai.data.TournamentRunKey
import com.ai.data.TournamentRunState
import com.ai.data.computeWinMatrix
import com.ai.data.decodeTournamentMatrix
import com.ai.data.encode
import com.ai.data.matchKey
import com.ai.data.parseMatchVerdict
import com.ai.data.rankFor
import com.ai.data.resolveSecondaryPrompt
import com.ai.data.toMatchState
import com.ai.data.toRerankJson
import com.ai.data.tournamentRunKey
import com.ai.data.withTracerTags
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Authoritative runtime owner for the Tournament run on every report.
 *
 * A tournament judges every unordered pair of a report's successful
 * responses TWICE (A-vs-B and B-vs-A, to cancel position bias) — and each
 * match is judged by the WORKER engine (the round-robin chain of cheap
 * models in the `workers` swarm), exactly like the Fan Meta batch. The
 * judging model isn't known until the worker chain returns; the winning
 * worker is then recorded on the MATCH row's (providerId, model). Once the
 * matches settle the engine folds them into a win matrix (see
 * [com.ai.data.TournamentRanking]) and writes a single AGGREGATE row whose
 * `content` is the rerank-compatible ranking for the selected method.
 *
 * One [TournamentRunState] per report (keyed by reportId). Structure
 * mirrors [IconGenerationManager.runFanMetaBatch]: a worker batch over
 * `ApiCallCaps.workers` in dynamic-host mode, per-match Job map,
 * in-flight / throttled StateFlow sets on [AppViewModel], disk hydration,
 * and app-kill resume.
 */
class TournamentEngine internal constructor(
    override val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) : SecondaryBatchEngine<TournamentRunKey, MatchState, TournamentRunState>() {
    override fun copyWithItems(run: TournamentRunState, items: Map<String, MatchState>) =
        run.copy(matches = items)

    override val secondaryKind = SecondaryKind.TOURNAMENT
    override val logTag = "Tournament"
    override val itemNoun = "match"
    override fun reportIdOf(runKey: TournamentRunKey) = runKey
    override fun runKeysForReport(reportId: String) = listOf(reportId)
    override fun terminalizeItem(item: MatchState, message: String) =
        item.copy(status = MatchStatus.ERROR, errorMessage = message, durationMs = 0)
    override fun isItemRow(run: TournamentRunState?, row: SecondaryResult) =
        row.tournamentRole == ROLE_MATCH
    override fun aggregateRowIdOf(run: TournamentRunState) = run.aggregateRowId
    override fun canRedispatch(context: Context, run: TournamentRunState) =
        run.tournamentPrompt.text.isNotBlank()   // synthetic prompt — can't re-run; audit bug 16
    override val requeueBuildLabel = "Re-queuing tournament"
    override fun resetItemToPending(item: MatchState) =
        item.copy(
            status = MatchStatus.PENDING, judgeModel = null, content = null, verdict = null,
            confidence = null, reason = null, errorMessage = null,
            inputCost = null, outputCost = null, durationMs = null, tokenUsage = null
        )
    /** Restore the pre-judge sentinel too — the next judge is unknown until
     *  the worker chain settles (dynamic-host). */
    override fun clearRowForRerun(row: SecondaryResult) =
        row.copy(
            providerId = TOURNAMENT_PENDING_PROVIDER,
            model = TOURNAMENT_PENDING_MODEL,
            agentName = "$TOURNAMENT_PENDING_PROVIDER / $TOURNAMENT_PENDING_MODEL",
            content = null, errorMessage = null,
            inputCost = null, outputCost = null, tokenUsage = null, durationMs = null,
            timestamp = System.currentTimeMillis()
        )

    /** The L1 "Throttled" stat — match ids parked on a provider throttle. */
    val throttledMatches: StateFlow<Set<String>> get() = appViewModel.throttledTournamentMatches

    // Run/match coroutines + resume-scan dedup now live in the shared
    // BatchEngine base (registerRunJob / registerItemJob / beginResumeScan /
    // runJobOf / itemJobOf / activeRunJobKeys / hasItemJob).

    private companion object {
        const val WORKERS_CATEGORY = "workers"
        const val PROMPT_NAME = "tournament"
        const val ROLE_MATCH = "MATCH"
        const val ROLE_AGGREGATE = "AGGREGATE"
        const val AGG_PROVIDER = "*tournament"
        const val AGG_MODEL = "aggregate"
    }

    private fun tournamentPrompt(aiSettings: Settings): InternalPrompt? =
        aiSettings.internalPrompts.firstOrNull { it.category == WORKERS_CATEGORY && it.name == PROMPT_NAME }

    // -----------------------------------------------------------------
    // Hydration — disk → StateFlow
    // -----------------------------------------------------------------

    /** Walk every TOURNAMENT row on disk for [reportId], build the single
     *  [TournamentRunState] (one tournament per report — newest run wins if
     *  legacy multi-run rows are present), and publish it. Preserves a live
     *  RUNNING match status the disk placeholder can't yet show. */
    override suspend fun hydrate(context: Context, reportId: String) {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val rows = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TOURNAMENT)
        }
        val byRun = rows.filter { !it.tournamentJudgeRunId.isNullOrBlank() }
            .groupBy { it.tournamentJudgeRunId!! }
        if (byRun.isEmpty()) {
            _runs.update { it - reportId }
            return
        }
        // One tournament per report — pick the newest run group.
        val (runId, group) = byRun.maxByOrNull { (_, g) -> g.maxOf { it.timestamp } }!!
        // Keep the run visible even if the tournament prompt was deleted/renamed
        // since it ran: fall back to a synthetic prompt from the row metadata
        // (blank text) so matches hydrate read-only. Rerun/resume no-op on a
        // synthetic (blank-text) prompt. See audit bug 16.
        val prompt = aiSettings.internalPrompts.firstOrNull { it.id == group.first().metaPromptId }
            ?: tournamentPrompt(aiSettings)
            ?: InternalPrompt(
                id = group.first().metaPromptId ?: "",
                name = group.first().metaPromptName?.takeIf { it.isNotBlank() } ?: "(prompt unavailable)",
                category = WORKERS_CATEGORY
            )
        val aggRow = group.firstOrNull { it.tournamentRole == ROLE_AGGREGATE }
        val method = decodeTournamentMatrix(aggRow?.tournamentMatrix)?.second ?: TournamentMethod.COPELAND
        val currentMatches = _runs.value[reportId]?.matches
        val matches = group.mapNotNull { it.toMatchState() }
            .associateBy { it.key }
            .mapValues { (k, diskMatch) ->
                if (diskMatch.status == MatchStatus.PENDING &&
                    currentMatches?.get(k)?.status == MatchStatus.RUNNING
                ) diskMatch.copy(status = MatchStatus.RUNNING) else diskMatch
            }
        val run = TournamentRunState(
            key = reportId,
            reportId = reportId,
            runId = runId,
            tournamentPrompt = prompt,
            matches = matches,
            aggregateRowId = aggRow?.id,
            selectedMethod = method
        )
        // Don't re-publish a run whose delete is mid-flight: its rows are still
        // on disk but the user already dropped it.
        _runs.update { if (isDeleting(reportId)) it else it + (reportId to run) }
    }

    fun runByKey(key: TournamentRunKey): TournamentRunState? = _runs.value[key]

    // -----------------------------------------------------------------
    // Run launch
    // -----------------------------------------------------------------

    private data class PendingMatch(
        val aAgent: ReportAgent, val bAgent: ReportAgent, val orientation: Int,
        val placeholder: SecondaryResult
    )

    /** N(N-1) — the number of worker calls a tournament over [responseCount]
     *  responses runs. Used by the launch confirmation. */
    fun matchCountFor(responseCount: Int): Int = responseCount * (responseCount - 1)

    /** Launch a brand-new tournament on [reportId]. No judge model — every
     *  match is judged by the worker chain. Pre-creates N(N-1) MATCH
     *  placeholders (sentinel provider/model) + one AGGREGATE placeholder,
     *  publishes the run with all matches PENDING, runs each match through
     *  the worker batch, then folds the verdicts into the aggregate ranking. */
    fun startRun(context: Context, reportId: String, buildKey: String? = null, overrideWorkers: List<com.ai.model.Worker>? = null): Job? {
        val rk = tournamentRunKey(reportId)
        runJobOf(rk)?.let { if (it.isActive) return it }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "after/tournament", runId = runId) {
                    val aiSettings = appViewModel.uiState.value.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    // ♻️ report-models as the worker pool wins over a *SELECT pick.
                    val prompt = tournamentPrompt(aiSettings)?.let {
                        when {
                            report.useReportModelsAsWorkers -> it.copy(workers = reportModelWorkers(report))
                            overrideWorkers != null -> it.copy(workers = overrideWorkers)
                            else -> it
                        }
                    }
                    if (prompt == null || prompt.workers.none { aiSettings.resolveWorker(it) != null }) {
                        AppLog.w("Tournament", "workers/tournament not configured — aborting")
                        return@withTracerTags
                    }
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successful.size < 2) return@withTracerTags
                    AuditLog.append(reportId, "Start Tournament — ${successful.size} responses, ${matchCountFor(successful.size)} matches (worker-judged)")
                    val scopeEncoded = SecondaryScope.AllReports.encode()

                    val aggregate = SecondaryResult(
                        id = java.util.UUID.randomUUID().toString(),
                        reportId = reportId,
                        kind = SecondaryKind.TOURNAMENT,
                        providerId = AGG_PROVIDER,
                        model = AGG_MODEL,
                        agentName = "Tournament",
                        timestamp = System.currentTimeMillis(),
                        content = null,
                        tournamentRole = ROLE_AGGREGATE,
                        tournamentJudgeRunId = runId,
                        metaPromptId = prompt.id,
                        metaPromptName = prompt.name,
                        runId = runId,
                        secondaryScope = scopeEncoded
                    )

                    val pending = mutableListOf<PendingMatch>()
                    val newMatches = LinkedHashMap<String, MatchState>()
                    // Build stage: create every match placeholder up front.
                    if (buildKey != null) appViewModel.beginBuild(buildKey, matchCountFor(successful.size), "Building tournament")
                    var built = 0
                    for (i in successful.indices) {
                        for (j in i + 1 until successful.size) {
                            val a = successful[i]; val b = successful[j]
                            for ((aa, bb, orient) in listOf(Triple(a, b, 0), Triple(b, a, 1))) {
                                val placeholder = SecondaryResult(
                                    id = java.util.UUID.randomUUID().toString(),
                                    reportId = reportId,
                                    kind = SecondaryKind.TOURNAMENT,
                                    providerId = TOURNAMENT_PENDING_PROVIDER,
                                    model = TOURNAMENT_PENDING_MODEL,
                                    agentName = "Tournament match",
                                    timestamp = System.currentTimeMillis(),
                                    content = null,
                                    tournamentRole = ROLE_MATCH,
                                    tournamentJudgeRunId = runId,
                                    matchResponseAId = aa.agentId,
                                    matchResponseBId = bb.agentId,
                                    matchOrientation = orient,
                                    metaPromptId = prompt.id,
                                    metaPromptName = prompt.name,
                                    runId = runId,
                                    secondaryScope = scopeEncoded
                                )
                                pending.add(PendingMatch(aa, bb, orient, placeholder))
                                if (buildKey != null) { built++; if (built % 5 == 0) appViewModel.updateBuild(buildKey, built) }
                            }
                        }
                    }
                    val savedIds = SecondaryResultStorage.saveAll(context, listOf(aggregate) + pending.map { it.placeholder })
                        .mapTo(HashSet()) { it.id }
                    if (aggregate.id !in savedIds) return@withTracerTags
                    pending.removeAll { it.placeholder.id !in savedIds }
                    pending.forEach { item -> item.placeholder.toMatchState()?.let { newMatches[it.key] = it } }
                    if (buildKey != null) appViewModel.finishBuild(buildKey)

                    _runs.update { runs ->
                        runs + (rk to TournamentRunState(
                            key = rk, reportId = reportId, runId = runId, tournamentPrompt = prompt,
                            matches = newMatches, aggregateRowId = aggregate.id,
                            selectedMethod = TournamentMethod.COPELAND
                        ))
                    }

                    dispatchMatches(context, reportId, prompt, report.prompt, report.title, pending)
                    recomputeAggregate(context, reportId)
                    AuditLog.append(reportId, "End Tournament")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                finalizeLeftoverItems(context, reportId)
                if (buildKey != null) {
                    val p = appViewModel.batchBuildProgress.value[buildKey]
                    if (p != null && !p.done) appViewModel.clearBuild(buildKey)
                }
            }
        }
        registerRunJob(rk, job)
        return job
    }

    /** Worker-batch dispatch shared by [startRun] and the rerun/resume paths
     *  — dynamic-host (each worker call self-throttles its provider) under
     *  the shared workers cap. */
    private suspend fun dispatchMatches(
        context: Context, reportId: String, prompt: InternalPrompt,
        question: String, title: String, items: List<PendingMatch>
    ) {
        if (items.isEmpty()) return
        runThrottledBatch(
            items = items,
            hostOf = { null },
            subCap = ApiCallCaps.workers,
            onThrottled = { appViewModel.updateThrottledTournamentMatches { s -> s + it.placeholder.id } },
            onCleared = { appViewModel.updateThrottledTournamentMatches { s -> s - it.placeholder.id } },
            dynamicHost = true,
            register = { item, d ->
                registerItemJob(item.placeholder.id, d)
            }
        ) { item ->
            if (!SecondaryResultStorage.exists(context, reportId, item.placeholder.id)) return@runThrottledBatch
            try {
                runOneMatch(context, reportId, prompt, question, title, item)
            } finally {
                appViewModel.updateThrottledTournamentMatches { it - item.placeholder.id }
            }
        }
    }

    // -----------------------------------------------------------------
    // Per-match worker call (mirrors runFanMetaForPair)
    // -----------------------------------------------------------------

    private suspend fun runOneMatch(
        context: Context, reportId: String, prompt: InternalPrompt,
        question: String, title: String, item: PendingMatch
    ) {
        val mKey = matchKey(item.aAgent.agentId, item.bAgent.agentId, item.orientation)
        val rowId = item.placeholder.id
        transitionItem(reportId, mKey) { it.copy(status = MatchStatus.RUNNING) }
        val started = System.currentTimeMillis()
        val aiSettings = appViewModel.uiState.value.aiSettings
        val resolvedBase = resolveSecondaryPrompt(prompt.text, question = question, results = "", count = 2, title = title)
        val resolved = resolvedBase
            .replace("@RESPONSE_A@", item.aAgent.responseBody.orEmpty())
            .replace("@RESPONSE_B@", item.bAgent.responseBody.orEmpty())
        // Surface the per-provider throttle wait to the L1 "Throttled" counter
        // (a worker call is dynamic-host, so its wait happens inside
        // ProviderThrottle.acquire, not at the batch layer).
        val observer: (Boolean) -> Unit = { waiting ->
            if (waiting) appViewModel.updateThrottledTournamentMatches { it + rowId }
            else appViewModel.updateThrottledTournamentMatches { it - rowId }
        }
        // Capture the judging call's trace filename (when tracing is on) so
        // the head-to-heads' 🐞 can deep-link to it. Null otherwise.
        val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
        try {
            val outcome = withContext(ProviderThrottle.throttleWaitObserver.asContextElement(observer)) {
                com.ai.data.withTraceFilenameSink(traceSink) {
                    reportViewModel.workerRunner.run(prompt, resolved, aiSettings, context) { resp ->
                        parseMatchVerdict(resp.analysis)?.verdict != null
                    }
                }
            }
            when (outcome) {
                is WorkerOutcome.Success -> {
                    val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                        it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                    }
                    val tu = outcome.response.tokenUsage
                    val inT = tu?.inputTokens ?: 0
                    val outT = tu?.outputTokens ?: 0
                    val provId = winAgent?.provider?.id ?: TOURNAMENT_PENDING_PROVIDER
                    val mdl = winAgent?.model ?: TOURNAMENT_PENDING_MODEL
                    var inCost = 0.0; var outCost = 0.0
                    if (winAgent != null && tu != null && (inT > 0 || outT > 0)) {
                        val pricing = PricingCache.getPricing(context, winAgent.provider, winAgent.model)
                        val split = PricingCache.computeInOutCost(tu, pricing)
                        inCost = split.first
                        outCost = split.second
                        appViewModel.settingsPrefs.updateUsageStatsAsync(winAgent.provider, winAgent.model, tu, kind = "tournament")
                    }
                    SecondaryResultStorage.recordTournamentMatch(
                        context, reportId, rowId, provId, mdl,
                        outcome.response.analysis.orEmpty(),
                        inT, outT, inCost, outCost, System.currentTimeMillis() - started,
                        traceFile = traceSink.get()
                    )
                }
                else -> {
                    val msg = if (outcome is WorkerOutcome.AllRateLimited) "tournament: all workers rate-limited"
                              else "tournament: no worker produced a verdict"
                    val cur = SecondaryResultStorage.get(context, reportId, rowId) ?: item.placeholder
                    SecondaryResultStorage.save(context, cur.copy(errorMessage = msg, durationMs = System.currentTimeMillis() - started))
                }
            }
        } finally {
            // Mirror the saved row into memory in a NonCancellable block so a
            // stop / cancel mid-call still settles the match on its disk-truth
            // status instead of leaving it stuck at RUNNING — the per-screen
            // 3s re-hydrate that used to recover that case has been removed.
            withContext(kotlinx.coroutines.NonCancellable) {
                val saved = SecondaryResultStorage.get(context, reportId, rowId)
                if (saved == null) dropItem(reportId, mKey)
                else transitionItem(reportId, mKey) {
                    saved.toMatchState() ?: it.copy(status = MatchStatus.ERROR, errorMessage = "Match row could not be parsed")
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Aggregation
    // -----------------------------------------------------------------

    override fun recomputeAggregate(context: Context, runKey: TournamentRunKey) {
        val reportId = runKey
        val run = _runs.value[reportId] ?: return
        val aggId = run.aggregateRowId ?: return
        val report = ReportStorage.getReport(context, reportId) ?: return
        // The tournament's participants are FIXED at launch (the responses in
        // its matches). Numbering the [N] ids through the report's CURRENT
        // success set truncated the ranking whenever a participant was
        // transiently non-SUCCESS (mid-generate / regenerate): computeWinMatrix
        // silently drops unresolved responses, so the matrix shrank (e.g. 9 of
        // 39). Number every participant by its stable position in the report's
        // agent order instead — for participants (which were all SUCCESS at
        // launch) this reproduces the launch-time success ordering the View /
        // Top-ranked expect, but it can't be shrunk by a later status dip.
        val participantIds = run.matches.values
            .flatMapTo(HashSet()) { listOf(it.responseAId, it.responseBId) }
        val idByAgent = report.agents
            .filter { it.agentId in participantIds }
            .withIndex().associate { (i, a) -> a.agentId to (i + 1) }
        // The aggregation math (iterative ranking methods, JSON encode) must
        // never take the app down — this runs from the background resume
        // sweep at startup as well as user actions. Swallow + log; a failed
        // recompute just leaves the previous aggregate in place.
        try {
            val matrix = computeWinMatrix(run.matches.values.toList()) { idByAgent[it] }
            val ranks = rankFor(run.selectedMethod, matrix)
            val row = SecondaryResultStorage.get(context, reportId, aggId) ?: return
            SecondaryResultStorage.save(context, row.copy(
                content = ranks.toRerankJson(),
                tournamentMatrix = matrix.encode(run.selectedMethod),
                durationMs = row.durationMs ?: 0
            ))
        } catch (e: Exception) {
            AppLog.w("Tournament", "recompute aggregate failed report=$reportId method=${run.selectedMethod}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Switch the displayed / Top-ranked aggregation method — a pure local
     *  recompute from the stored win matrix, no API calls. */
    fun setMethod(context: Context, reportId: String, method: TournamentMethod): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            val aggId = run.aggregateRowId ?: return@launch
            _runs.update { runs ->
                val r = runs[reportId] ?: return@update runs
                runs + (reportId to r.copy(selectedMethod = method))
            }
            val row = SecondaryResultStorage.get(context, reportId, aggId) ?: return@launch
            val decoded = decodeTournamentMatrix(row.tournamentMatrix)
            if (decoded != null && (method != TournamentMethod.DAVIDSON || decoded.first.hasTieData)) {
                val ranks = rankFor(method, decoded.first)
                SecondaryResultStorage.save(context, row.copy(
                    content = ranks.toRerankJson(), tournamentMatrix = decoded.first.encode(method)
                ))
            } else {
                recomputeAggregate(context, reportId)
            }
        }

    // -----------------------------------------------------------------
    // Failure / rerun / delete
    // -----------------------------------------------------------------

    fun restartFailedMatches(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            restartItemsWhere(context, reportId) { it.status == MatchStatus.ERROR }
        }

    fun rerunMatch(context: Context, reportId: String, mKey: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            restartItemsWhere(context, reportId) { it.key == mKey }
        }

    /** Drop every errored match without re-firing — clears a permanently-dead
     *  failure so the run can settle. */
    fun removeFailedMatches(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            removeItemsMatching(context, reportId) { it.status == MatchStatus.ERROR }
        }

    /** Drop every unfinished (stranded, never-ran) match without re-firing
     *  — the Broken-work "delete unfinished" action. */
    fun removeUnfinishedMatches(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            removeItemsMatching(context, reportId) { it.status == MatchStatus.PENDING }
        }

    fun removeMatchesByIds(context: Context, reportId: String, rowIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            removeItemsMatching(context, reportId) { it.id in rowIds }
        }

    fun restartMatchesByIds(context: Context, reportId: String, rowIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            restartItemsWhere(context, reportId) { it.id in rowIds }
        }

    /** Cancel + delete the whole run (matches + aggregate), rolling the spend
     *  into the report's deleted-items tally. */
    fun deleteRun(context: Context, reportId: String): Job {
        val run = _runs.value[reportId] ?: return appViewModel.viewModelScope.launch { }
        // Capture the live coroutines before the deferred delete drops the run.
        val runJob = runJobOf(reportId)
        val itemJobs = run.matches.values.mapNotNull { itemJobOf(it.id) }
        return deleteRunDeferred(appViewModel.viewModelScope, reportId, runJob, itemJobs) {
            val costDelta = run.matches.values.sumOf { it.totalCost }
            run.matches.values.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            run.aggregateRowId?.let { SecondaryResultStorage.delete(context, reportId, it) }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
    }

    /** Delete the current tournament and immediately start a fresh one — the
     *  L1 🔄 redo. */
    fun rerunBatch(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch {
            deleteRun(context, reportId).join()
            startRun(context, reportId)
        }

    // -----------------------------------------------------------------
    // Resume on report open / regenerate
    // -----------------------------------------------------------------

    /** No stale matches left, but the aggregate matrix may be truncated (an
     *  old recompute ran during a partial-SUCCESS window). Re-sync once if it
     *  covers fewer responses than the run's participants. Guarded so it
     *  can't loop. */
    override fun onNoStaleRows(context: Context, runKey: TournamentRunKey) {
        val run = _runs.value[runKey] ?: return
        val participants = run.matches.values
            .flatMapTo(HashSet()) { listOf(it.responseAId, it.responseBId) }.size
        val storedN = run.aggregateRowId
            ?.let { SecondaryResultStorage.get(context, runKey, it)?.tournamentMatrix }
            ?.let { decodeTournamentMatrix(it)?.first?.ids?.size } ?: 0
        if (participants >= 2 && storedN < participants) {
            recomputeAggregate(context, runKey)
        }
    }

    override suspend fun redispatchRows(context: Context, runKey: TournamentRunKey, rows: List<SecondaryResult>) {
        val run = _runs.value[runKey] ?: return
        val report = ReportStorage.getReport(context, runKey) ?: return
        // ♻️ Models-as-workers must hold on resume / Broken-work restart /
        // regenerate too — the hydrated prompt carries the CONFIGURED swarm,
        // so re-judging without the swap would pull in foreign workers.
        val prompt = if (report.useReportModelsAsWorkers)
            run.tournamentPrompt.copy(workers = reportModelWorkers(report)) else run.tournamentPrompt
        val agentsById = report.agents.associateBy { it.agentId }
        val pending = rows.mapNotNull { row ->
            val m = run.matches.values.firstOrNull { it.id == row.id } ?: return@mapNotNull null
            val a = agentsById[m.responseAId] ?: return@mapNotNull null
            val b = agentsById[m.responseBId] ?: return@mapNotNull null
            PendingMatch(a, b, m.orientation, row)
        }
        if (pending.isEmpty()) return
        withTracerTags(reportId = runKey, category = "after/tournament") {
            dispatchMatches(context, runKey, prompt, report.prompt, report.title, pending)
        }
    }
}
