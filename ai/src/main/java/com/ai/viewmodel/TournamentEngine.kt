package com.ai.viewmodel

import android.content.Context
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.AuditLog
import com.ai.data.MatchState
import com.ai.data.MatchStatus
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.SecondaryScope
import com.ai.data.TournamentMethod
import com.ai.data.TournamentRunKey
import com.ai.data.TournamentRunState
import com.ai.data.computeWinMatrix
import com.ai.data.decodeTournamentMatrix
import com.ai.data.encode
import com.ai.data.matchKey
import com.ai.data.rankFor
import com.ai.data.resolveSecondaryPrompt
import com.ai.data.toMatchState
import com.ai.data.toRerankJson
import com.ai.data.tournamentRunKey
import com.ai.data.withTracerTags
import com.ai.ui.shared.shortModelName
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative runtime owner for every Tournament run on every report —
 * the sibling of [FanOutEngine] for pairwise head-to-head judging.
 *
 * A tournament judges every unordered pair of a report's successful
 * responses TWICE (swapped, to cancel position bias) with one chosen
 * judge model, persisting each ordered judgment as a MATCH
 * [SecondaryResult] row (kind == TOURNAMENT). Once the matches settle
 * the engine folds them into a win matrix (see
 * [com.ai.data.TournamentRanking]) and writes a single AGGREGATE row
 * whose `content` is the rerank-compatible ranking for the currently
 * selected method — so the result feeds the existing rerank renderers
 * and the Top-ranked scope unchanged.
 *
 * Structure mirrors [FanOutEngine] — StateFlow of run states, per-match
 * Job map with LAZY register-before-start, [runThrottledBatch] dispatch
 * reusing [SecondaryRunManager.executeSecondaryTask], disk hydration and
 * app-kill resume — minus the fan-out-specific answerer×source / Fan-Meta
 * / fan-in machinery, which has no tournament analog.
 */
class TournamentEngine internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) {
    private val _runs = MutableStateFlow<Map<TournamentRunKey, TournamentRunState>>(emptyMap())
    val runs: StateFlow<Map<TournamentRunKey, TournamentRunState>> = _runs.asStateFlow()

    /** Per-match coroutines keyed by [MatchState.id] (= on-disk row id).
     *  Registered before the coroutine starts so concurrent deletes can
     *  always find the Job to cancel. */
    private val matchJobs = ConcurrentHashMap<String, Job>()

    /** Top-level batch Job per run. */
    private val runJobs = ConcurrentHashMap<TournamentRunKey, Job>()

    /** Per-run dedup for the resume scan. */
    private val resumeScans = ConcurrentHashMap.newKeySet<TournamentRunKey>()

    private companion object {
        const val JUDGE_PROMPT = "second-tournament"
        const val ROLE_MATCH = "MATCH"
        const val ROLE_AGGREGATE = "AGGREGATE"
        const val MATCH_TIMEOUT_MS = 60_000L
    }

    // -----------------------------------------------------------------
    // Hydration — disk → StateFlow
    // -----------------------------------------------------------------

    /** Walk every TOURNAMENT row on disk for [reportId], group by
     *  [SecondaryResult.tournamentJudgeRunId] into [TournamentRunState]s,
     *  and publish them. Preserves a live RUNNING match status the disk
     *  placeholder can't yet show. */
    suspend fun hydrate(context: Context, reportId: String) {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val rows = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TOURNAMENT)
        }
        if (rows.isEmpty()) {
            _runs.update { it.filterKeys { k -> !k.startsWith("$reportId|") } }
            return
        }
        val byRun = rows.filter { !it.tournamentJudgeRunId.isNullOrBlank() }
            .groupBy { it.tournamentJudgeRunId!! }
        val currentRuns = _runs.value
        val newRuns = mutableMapOf<TournamentRunKey, TournamentRunState>()
        for ((rk, group) in byRun) {
            val any = group.first()
            val prompt = aiSettings.internalPrompts.firstOrNull { it.id == any.metaPromptId }
                ?: aiSettings.getInternalPromptByName(JUDGE_PROMPT)
                ?: continue
            val aggRow = group.firstOrNull { it.tournamentRole == ROLE_AGGREGATE }
            val method = decodeTournamentMatrix(aggRow?.tournamentMatrix)?.second ?: TournamentMethod.COPELAND
            val currentMatches = currentRuns[rk]?.matches
            val matches = group.mapNotNull { it.toMatchState() }.associateBy { m ->
                // Preserve a live RUNNING status the disk can't yet show.
                m.key
            }.mapValues { (k, diskMatch) ->
                if (diskMatch.status == MatchStatus.PENDING &&
                    currentMatches?.get(k)?.status == MatchStatus.RUNNING) {
                    diskMatch.copy(status = MatchStatus.RUNNING)
                } else diskMatch
            }
            newRuns[rk] = TournamentRunState(
                key = rk,
                reportId = reportId,
                judgeProviderId = any.providerId,
                judgeModel = any.model,
                judgePrompt = prompt,
                matches = matches,
                aggregateRowId = aggRow?.id,
                selectedMethod = method
            )
        }
        _runs.update { current ->
            val keep = current.filterKeys { !it.startsWith("$reportId|") }
            keep + newRuns
        }
    }

    fun runByKey(key: TournamentRunKey): TournamentRunState? = _runs.value[key]

    // -----------------------------------------------------------------
    // State-flow transition helpers
    // -----------------------------------------------------------------

    private fun transitionMatch(runKey: TournamentRunKey, mKey: String, update: (MatchState) -> MatchState) {
        _runs.update { runs ->
            val run = runs[runKey] ?: return@update runs
            val cur = run.matches[mKey] ?: return@update runs
            val next = update(cur)
            if (next == cur) runs
            else runs + (runKey to run.copy(matches = run.matches + (mKey to next)))
        }
    }

    private fun dropMatch(runKey: TournamentRunKey, mKey: String) {
        _runs.update { runs ->
            val run = runs[runKey] ?: return@update runs
            if (mKey !in run.matches) runs
            else runs + (runKey to run.copy(matches = run.matches - mKey))
        }
    }

    private fun dropRun(runKey: TournamentRunKey) {
        _runs.update { it - runKey }
    }

    // -----------------------------------------------------------------
    // Run launch
    // -----------------------------------------------------------------

    /** One ordered head-to-head to judge. */
    private data class PendingMatch(
        val aAgent: ReportAgent, val bAgent: ReportAgent, val orientation: Int,
        val placeholder: SecondaryResult
    )

    /** Number of ordered judge calls a tournament over [responseCount]
     *  responses will run — N(N-1). Used by the launch confirmation. */
    fun matchCountFor(responseCount: Int): Int = responseCount * (responseCount - 1)

    /** Launch a brand-new tournament with [judgeProvider]/[judgeModel] as
     *  the judge. Dedupes against an active run for the same (report,
     *  judge). Pre-creates N(N-1) MATCH placeholders + one AGGREGATE
     *  placeholder, publishes the run with all matches PENDING, runs each
     *  match through the shared throttled batch, then folds the verdicts
     *  into the aggregate ranking row. */
    fun startRun(context: Context, reportId: String, judgeProvider: AppService, judgeModel: String): Job? {
        val rk = tournamentRunKey(reportId, judgeProvider.id, judgeModel)
        runJobs[rk]?.let { if (it.isActive) return it }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val runId = java.util.UUID.randomUUID().toString()
        val startMs = System.currentTimeMillis()
        val job = appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "after/tournament", runId = runId) {
                    val aiSettings = appViewModel.uiState.value.aiSettings
                    val prompt = aiSettings.getInternalPromptByName(JUDGE_PROMPT) ?: run {
                        AppLog.w("Tournament", "no $JUDGE_PROMPT prompt — aborting")
                        return@withTracerTags
                    }
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successful.size < 2) return@withTracerTags
                    AppLog.i("Tournament", "→ start judge=${judgeProvider.id}/$judgeModel (${successful.size} responses, ${matchCountFor(successful.size)} matches)")
                    AuditLog.append(reportId, "Start Tournament — judge ${judgeProvider.id}/${shortModelName(judgeModel)}, ${successful.size} responses, ${matchCountFor(successful.size)} matches")
                    val agentName = "${judgeProvider.id} / ${shortModelName(judgeModel)}"
                    val scopeEncoded = SecondaryScope.AllReports.encode()

                    // Pre-create the AGGREGATE row up-front so its id is known.
                    val aggregate = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.TOURNAMENT, judgeProvider.id, judgeModel, agentName
                    ) {
                        it.copy(
                            tournamentRole = ROLE_AGGREGATE,
                            tournamentJudgeRunId = rk,
                            metaPromptId = prompt.id,
                            metaPromptName = prompt.name,
                            runId = runId,
                            secondaryScope = scopeEncoded
                        )
                    }

                    // Pre-create every ordered-match placeholder.
                    val pending = mutableListOf<PendingMatch>()
                    val newMatches = LinkedHashMap<String, MatchState>()
                    for (i in successful.indices) {
                        for (j in i + 1 until successful.size) {
                            val a = successful[i]; val b = successful[j]
                            for ((aa, bb, orient) in listOf(
                                Triple(a, b, 0), Triple(b, a, 1)
                            )) {
                                val placeholder = SecondaryResultStorage.create(
                                    context, reportId, SecondaryKind.TOURNAMENT, judgeProvider.id, judgeModel, agentName
                                ) {
                                    it.copy(
                                        tournamentRole = ROLE_MATCH,
                                        tournamentJudgeRunId = rk,
                                        matchResponseAId = aa.agentId,
                                        matchResponseBId = bb.agentId,
                                        matchOrientation = orient,
                                        metaPromptId = prompt.id,
                                        metaPromptName = prompt.name,
                                        runId = runId,
                                        secondaryScope = scopeEncoded
                                    )
                                }
                                pending.add(PendingMatch(aa, bb, orient, placeholder))
                                placeholder.toMatchState()?.let { newMatches[it.key] = it }
                            }
                        }
                    }

                    _runs.update { runs ->
                        runs + (rk to TournamentRunState(
                            key = rk,
                            reportId = reportId,
                            judgeProviderId = judgeProvider.id,
                            judgeModel = judgeModel,
                            judgePrompt = prompt,
                            matches = newMatches,
                            aggregateRowId = aggregate.id,
                            selectedMethod = TournamentMethod.COPELAND
                        ))
                    }

                    runThrottledBatch(
                        items = pending,
                        hostOf = { providerHost(judgeProvider) },
                        subCap = ApiCallCaps.fanOut,
                        register = { item, d ->
                            matchJobs[item.placeholder.id] = d
                            d.invokeOnCompletion { matchJobs.remove(item.placeholder.id, d) }
                        }
                    ) { item ->
                        runOneMatch(context, rk, report.id, judgeProvider, judgeModel, prompt, report.prompt, report.title, item)
                    }
                    // All matches settled — fold into the aggregate ranking.
                    recomputeAndPersistAggregate(context, rk)
                    AppLog.i("Tournament", "← done judge=${judgeProvider.id}/$judgeModel in ${System.currentTimeMillis() - startMs}ms")
                    AuditLog.append(reportId, "End Tournament — judge ${judgeProvider.id}/${shortModelName(judgeModel)}")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                finalizeLeftoverMatches(context, reportId, rk)
            }
        }
        runJobs[rk] = job
        job.invokeOnCompletion { runJobs.remove(rk, job) }
        return job
    }

    // -----------------------------------------------------------------
    // Per-match runner
    // -----------------------------------------------------------------

    private suspend fun runOneMatch(
        context: Context, runKey: TournamentRunKey, reportId: String,
        judgeProvider: AppService, judgeModel: String,
        prompt: com.ai.model.InternalPrompt,
        question: String, title: String,
        item: PendingMatch
    ) {
        val mKey = matchKey(item.aAgent.agentId, item.bAgent.agentId, item.orientation)
        if (!SecondaryResultStorage.exists(context, reportId, item.placeholder.id)) {
            dropMatch(runKey, mKey)
            return
        }
        transitionMatch(runKey, mKey) { it.copy(status = MatchStatus.RUNNING) }
        val start = System.currentTimeMillis()
        try {
            val resolvedBase = resolveSecondaryPrompt(prompt.text, question = question, results = "", count = 2, title = title)
            val resolved = resolvedBase
                .replace("@RESPONSE_A@", item.aAgent.responseBody.orEmpty())
                .replace("@RESPONSE_B@", item.bAgent.responseBody.orEmpty())
            try {
                withTimeout(MATCH_TIMEOUT_MS) {
                    reportViewModel.secondary.executeSecondaryTask(
                        context, reportId, SecondaryKind.TOURNAMENT, prompt,
                        judgeProvider, judgeModel, resolved,
                        appViewModel.uiState.value.aiSettings, ReportStorage.getReport(context, reportId)!!,
                        existingPlaceholder = item.placeholder
                    )
                }
            } catch (e: TimeoutCancellationException) {
                val timedOut = (SecondaryResultStorage.get(context, reportId, item.placeholder.id) ?: item.placeholder)
                    .copy(errorMessage = "Tournament match timed out after 60s", durationMs = System.currentTimeMillis() - start)
                SecondaryResultStorage.save(context, timedOut)
            }
            val saved = SecondaryResultStorage.get(context, reportId, item.placeholder.id)
            if (saved == null) {
                dropMatch(runKey, mKey)
                return
            }
            val refreshed = saved.toMatchState()
            transitionMatch(runKey, mKey) {
                if (refreshed != null) refreshed else it.copy(status = MatchStatus.ERROR, errorMessage = "Match row could not be parsed")
            }
        } finally {
            AppLog.d("Tournament", "← match ${item.aAgent.agentId} vs ${item.bAgent.agentId} (o${item.orientation}) ${System.currentTimeMillis() - start}ms")
        }
    }

    // -----------------------------------------------------------------
    // Aggregation
    // -----------------------------------------------------------------

    /** Fold the run's match verdicts into the win matrix and persist the
     *  AGGREGATE row's rerank-shaped content for the run's selected
     *  method, plus the matrix sidecar for instant method toggling. */
    private fun recomputeAndPersistAggregate(context: Context, runKey: TournamentRunKey) {
        val run = _runs.value[runKey] ?: return
        val aggId = run.aggregateRowId ?: return
        val report = ReportStorage.getReport(context, run.reportId) ?: return
        val successful = report.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
        }
        val idByAgent = successful.withIndex().associate { (i, a) -> a.agentId to (i + 1) }
        val matrix = computeWinMatrix(run.matches.values.toList()) { idByAgent[it] }
        val ranks = rankFor(run.selectedMethod, matrix)
        val row = SecondaryResultStorage.get(context, run.reportId, aggId) ?: return
        SecondaryResultStorage.save(context, row.copy(
            content = ranks.toRerankJson(),
            tournamentMatrix = matrix.encode(run.selectedMethod),
            durationMs = row.durationMs ?: 0
        ))
    }

    /** Switch the displayed/Top-ranked aggregation method. A pure local
     *  recompute from the stored win matrix — no API calls. Falls back to
     *  a full recompute from match rows when the matrix sidecar is
     *  missing. */
    fun setMethod(context: Context, runKey: TournamentRunKey, method: TournamentMethod): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val aggId = run.aggregateRowId ?: return@launch
            _runs.update { runs ->
                val r = runs[runKey] ?: return@update runs
                runs + (runKey to r.copy(selectedMethod = method))
            }
            val row = SecondaryResultStorage.get(context, run.reportId, aggId) ?: return@launch
            val decoded = decodeTournamentMatrix(row.tournamentMatrix)
            if (decoded != null) {
                val ranks = rankFor(method, decoded.first)
                SecondaryResultStorage.save(context, row.copy(
                    content = ranks.toRerankJson(),
                    tournamentMatrix = decoded.first.encode(method)
                ))
            } else {
                recomputeAndPersistAggregate(context, runKey)
            }
        }

    // -----------------------------------------------------------------
    // Failure / rerun / delete
    // -----------------------------------------------------------------

    /** Re-fire every errored match, then recompute the aggregate. */
    fun restartFailedMatches(context: Context, runKey: TournamentRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val keys = run.matches.values.filter { it.status == MatchStatus.ERROR }.map { it.key }
            rerunMatchesBlocking(context, runKey, keys)
            recomputeAndPersistAggregate(context, runKey)
        }

    /** Re-fire a single match by its [MatchKey]. */
    fun rerunMatch(context: Context, runKey: TournamentRunKey, mKey: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            rerunMatchesBlocking(context, runKey, listOf(mKey))
            recomputeAndPersistAggregate(context, runKey)
        }

    private suspend fun rerunMatchesBlocking(context: Context, runKey: TournamentRunKey, mKeys: List<String>) {
        if (mKeys.isEmpty()) return
        val run = _runs.value[runKey] ?: return
        val report = ReportStorage.getReport(context, run.reportId) ?: return
        val provider = AppService.findById(run.judgeProviderId) ?: return
        val agentsById = report.agents.associateBy { it.agentId }
        data class Reset(val item: PendingMatch)
        val resets = mutableListOf<Reset>()
        for (k in mKeys) {
            val m = run.matches[k] ?: continue
            val a = agentsById[m.responseAId] ?: continue
            val b = agentsById[m.responseBId] ?: continue
            val cleared = SecondaryResultStorage.get(context, run.reportId, m.id)?.copy(
                content = null, errorMessage = null, inputCost = null, outputCost = null,
                durationMs = null, tokenUsage = null, timestamp = System.currentTimeMillis()
            ) ?: continue
            SecondaryResultStorage.save(context, cleared)
            transitionMatch(runKey, k) {
                it.copy(status = MatchStatus.PENDING, content = null, verdict = null, errorMessage = null,
                    inputCost = null, outputCost = null, durationMs = null, tokenUsage = null)
            }
            resets.add(Reset(PendingMatch(a, b, m.orientation, cleared)))
        }
        if (resets.isEmpty()) return
        withTracerTags(reportId = run.reportId, category = "after/tournament") {
            runThrottledBatch(
                items = resets,
                hostOf = { providerHost(provider) },
                subCap = ApiCallCaps.fanOut,
                register = { r, d ->
                    matchJobs[r.item.placeholder.id] = d
                    d.invokeOnCompletion { matchJobs.remove(r.item.placeholder.id, d) }
                }
            ) { r ->
                runOneMatch(context, runKey, run.reportId, provider, run.judgeModel, run.judgePrompt,
                    report.prompt, report.title, r.item)
            }
        }
    }

    /** Cancel + delete the whole run (matches + aggregate), rolling the
     *  spend into the report's deleted-items tally. */
    fun deleteRun(context: Context, runKey: TournamentRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            runJobs[runKey]?.cancelAndJoin()
            run.matches.values.forEach { matchJobs[it.id]?.cancelAndJoin() }
            val costDelta = run.matches.values.sumOf { it.totalCost }
            run.matches.values.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
            run.aggregateRowId?.let { SecondaryResultStorage.delete(context, run.reportId, it) }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            dropRun(runKey)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
        }

    /** Best-effort cancel of every in-flight run + match for [reportId]
     *  (called from the synchronous report-delete path). */
    fun cancelAllForReport(reportId: String) {
        val prefix = "$reportId|"
        runJobs.filterKeys { it.startsWith(prefix) }.values.forEach { it.cancel() }
        _runs.value.filterKeys { it.startsWith(prefix) }
            .values.flatMap { it.matches.values.map { m -> m.id } }
            .forEach { matchJobs[it]?.cancel() }
        _runs.update { it.filterKeys { k -> !k.startsWith(prefix) } }
    }

    // -----------------------------------------------------------------
    // Resume on report open / regenerate
    // -----------------------------------------------------------------

    /** Resume every stale match (blank placeholder on disk, no live Job)
     *  across every tournament run on this report — the app-kill recovery
     *  path. Bounds re-dispatch via [BatchResume]. */
    fun resumeStaleRunsForReport(context: Context, reportId: String, resetAttempts: Boolean = false): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            hydrate(context, reportId)
            val diskStale = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TOURNAMENT)
                .filter {
                    it.tournamentRole == ROLE_MATCH && it.content.isNullOrBlank() &&
                        it.errorMessage == null && it.durationMs == null && !matchJobs.containsKey(it.id)
                }
            if (diskStale.isEmpty()) return@launch
            val diskById = diskStale.associateBy { it.id }
            val runsForReport = _runs.value.filterKeys { it.startsWith("$reportId|") }
            val dispatchable = mutableMapOf<TournamentRunKey, MutableList<String>>()
            for ((rk, run) in runsForReport) {
                for (m in run.matches.values) {
                    if (m.status == MatchStatus.PENDING && m.id in diskById) {
                        dispatchable.getOrPut(rk) { mutableListOf() }.add(m.key)
                    }
                }
            }
            for ((rk, mKeys) in dispatchable) {
                if (!resumeScans.add(rk)) continue
                val run = runsForReport[rk] ?: run { resumeScans.remove(rk); continue }
                val rows = mKeys.mapNotNull { k -> run.matches[k]?.id?.let { diskById[it] } }
                if (resetAttempts) BatchResume.resetAttempts(rows.map { it.id })
                val retryRows = BatchResume.capForRetry(rows) { row ->
                    markRowInterrupted(context, reportId, row.id,
                        "Interrupted — no result after ${BatchResume.MAX_ATTEMPTS} resume attempts")
                    run.matches.values.firstOrNull { it.id == row.id }?.let { m ->
                        transitionMatch(rk, m.key) {
                            it.copy(status = MatchStatus.ERROR, errorMessage = "Interrupted — no result after resume attempts", durationMs = 0)
                        }
                    }
                }
                val retryKeys = retryRows.mapNotNull { row -> run.matches.values.firstOrNull { it.id == row.id }?.key }
                if (retryKeys.isEmpty()) { resumeScans.remove(rk); continue }
                appViewModel.viewModelScope.launch(Dispatchers.IO) {
                    try {
                        rerunMatchesBlocking(context, rk, retryKeys)
                        recomputeAndPersistAggregate(context, rk)
                    } finally {
                        resumeScans.remove(rk)
                    }
                }
            }
        }

    private suspend fun finalizeLeftoverMatches(context: Context, reportId: String, runKey: TournamentRunKey) {
        withContext(kotlinx.coroutines.NonCancellable) {
            val leftover = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TOURNAMENT)
                .filter {
                    it.tournamentJudgeRunId == runKey && it.tournamentRole == ROLE_MATCH &&
                        it.content.isNullOrBlank() && it.errorMessage == null && it.durationMs == null &&
                        !matchJobs.containsKey(it.id)
                }
            BatchResume.finalizeLeftover(leftover) { row ->
                markRowInterrupted(context, reportId, row.id, "Interrupted — run stopped before this match finished")
                _runs.value[runKey]?.matches?.values?.firstOrNull { it.id == row.id }?.let { m ->
                    transitionMatch(runKey, m.key) {
                        if (it.status == MatchStatus.PENDING || it.status == MatchStatus.RUNNING)
                            it.copy(status = MatchStatus.ERROR, errorMessage = "Interrupted", durationMs = 0)
                        else it
                    }
                }
            }
        }
    }

    private fun markRowInterrupted(context: Context, reportId: String, rowId: String, message: String) {
        val current = SecondaryResultStorage.get(context, reportId, rowId) ?: return
        if (current.errorMessage != null || !current.content.isNullOrBlank() || current.durationMs != null) return
        SecondaryResultStorage.save(context, current.copy(errorMessage = message, durationMs = 0))
    }
}
