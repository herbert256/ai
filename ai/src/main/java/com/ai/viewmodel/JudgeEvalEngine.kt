package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.AuditLog
import com.ai.data.JUDGE_MATCH_COUNT
import com.ai.data.JUDGE_ROLE_AGGREGATE
import com.ai.data.JUDGE_ROLE_CELL
import com.ai.data.JudgeCellState
import com.ai.data.JudgeCellStatus
import com.ai.data.JudgeEvalRunKey
import com.ai.data.JudgeEvalRunState
import com.ai.data.PricingCache
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.SecondaryScope
import com.ai.data.analyzeJudges
import com.ai.data.judgeCellKey
import com.ai.data.matchKey
import com.ai.data.parseMatchVerdict
import com.ai.data.resolveSecondaryPrompt
import com.ai.data.toJudgeCellState
import com.ai.data.toJudgesJson
import com.ai.data.withTracerTags
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.model.SwarmMember
import com.ai.model.Worker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative runtime owner for the "Judge the judges" batch on every
 * report.
 *
 * Where [TournamentEngine] asks the worker chain to judge a report's pairs
 * once each (round-robin, one judge per match), this engine evaluates the
 * JUDGES themselves: it picks [JUDGE_MATCH_COUNT] random answer-pairs and
 * gives EVERY judge — the concrete worker models named by the
 * `workers/tournament` prompt's swarm — the SAME pairs, so the per-cell
 * verdicts can be cross-compared. Once the cells settle it folds them into
 * a per-judge agreement analysis (see [com.ai.data.JudgeAgreement]) on a
 * single AGGREGATE row.
 *
 * Structure mirrors [TournamentEngine], with two differences: each cell is
 * judged by a SPECIFIC named judge (a direct [AnalysisRepository.analyzeWithAgent]
 * call, not the round-robin chain), so the batch runs FIXED-host (each
 * cell's provider is known up front) rather than dynamic-host.
 */
class JudgeEvalEngine internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) : BatchEngine<JudgeEvalRunKey, String, JudgeCellState, JudgeEvalRunState>() {
    override fun copyWithItems(run: JudgeEvalRunState, items: Map<String, JudgeCellState>) =
        run.copy(cells = items)

    /** The L1 "Wait" stat — cell ids parked on a provider throttle. */
    val throttledCells: StateFlow<Set<String>> get() = appViewModel.throttledJudgeEvalCells

    /** Per-cell coroutines keyed by [JudgeCellState.id] (= on-disk row id). */
    private val cellJobs = ConcurrentHashMap<String, Job>()

    /** Top-level batch Job per report. */
    private val runJobs = ConcurrentHashMap<JudgeEvalRunKey, Job>()

    /** Per-report dedup for the resume scan. */
    private val resumeScans = ConcurrentHashMap.newKeySet<JudgeEvalRunKey>()

    private companion object {
        const val WORKERS_CATEGORY = "workers"
        const val PROMPT_NAME = "tournament"
        const val AGG_PROVIDER = "*judges"
        const val AGG_MODEL = "aggregate"
    }

    /** The judging prompt — reuses the Tournament worker prompt; its
     *  swarm names the judges. */
    private fun judgePrompt(aiSettings: Settings): InternalPrompt? =
        aiSettings.internalPrompts.firstOrNull { it.category == WORKERS_CATEGORY && it.name == PROMPT_NAME }

    /** A concrete judge resolved from the prompt's swarm. */
    private data class Judge(val worker: Worker, val providerId: String, val model: String) {
        val key: String get() = "$providerId/$model"
    }

    /** Expand the prompt's worker chain into the concrete, resolvable judge
     *  models (one per swarm member). */
    private fun resolveJudges(aiSettings: Settings, prompt: InternalPrompt): List<Judge> =
        prompt.workers.flatMap { aiSettings.expandWorker(it) }
            .distinctBy { "${it.provider}/${it.model}" }
            .mapNotNull { w ->
                val raw = aiSettings.resolveWorker(w) ?: return@mapNotNull null
                Judge(w, raw.provider.id, aiSettings.getEffectiveModelForAgent(raw))
            }

    private fun judgeFromRow(row: SecondaryResult): Judge =
        Judge(Worker(provider = row.providerId, model = row.model), row.providerId, row.model)

    // -----------------------------------------------------------------
    // Hydration — disk → StateFlow
    // -----------------------------------------------------------------

    suspend fun hydrate(context: Context, reportId: String) {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val rows = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.JUDGES)
        }
        val byRun = rows.filter { !it.tournamentJudgeRunId.isNullOrBlank() }
            .groupBy { it.tournamentJudgeRunId!! }
        if (byRun.isEmpty()) {
            _runs.update { it - reportId }
            return
        }
        val (runId, group) = byRun.maxByOrNull { (_, g) -> g.maxOf { it.timestamp } }!!
        val prompt = aiSettings.internalPrompts.firstOrNull { it.id == group.first().metaPromptId }
            ?: judgePrompt(aiSettings) ?: run { _runs.update { it - reportId }; return }
        val aggRow = group.firstOrNull { it.tournamentRole == JUDGE_ROLE_AGGREGATE }
        val currentCells = _runs.value[reportId]?.cells
        val cells = group.mapNotNull { it.toJudgeCellState() }
            .associateBy { it.key }
            .mapValues { (k, diskCell) ->
                if (diskCell.status == JudgeCellStatus.PENDING &&
                    currentCells?.get(k)?.status == JudgeCellStatus.RUNNING
                ) diskCell.copy(status = JudgeCellStatus.RUNNING) else diskCell
            }
        val run = JudgeEvalRunState(
            key = reportId,
            reportId = reportId,
            runId = runId,
            prompt = prompt,
            cells = cells,
            aggregateRowId = aggRow?.id
        )
        _runs.update { it + (reportId to run) }
    }

    fun runByKey(key: JudgeEvalRunKey): JudgeEvalRunState? = _runs.value[key]

    // -----------------------------------------------------------------
    // State-flow transition helpers
    // -----------------------------------------------------------------

    private fun transitionCell(reportId: String, cKey: String, update: (JudgeCellState) -> JudgeCellState) =
        transitionItem(reportId, cKey, update)

    private fun dropCell(reportId: String, cKey: String) = dropItem(reportId, cKey)

    // -----------------------------------------------------------------
    // Run launch
    // -----------------------------------------------------------------

    private data class PendingCell(
        val judge: Judge, val aId: String, val bId: String, val orientation: Int,
        val placeholder: SecondaryResult
    )

    /** Launch a brand-new judge-eval on [reportId]: enumerate the judges from
     *  the tournament prompt's swarm, pick up to [JUDGE_MATCH_COUNT] random
     *  answer-pairs, pre-create judges×matches CELL placeholders + one
     *  AGGREGATE placeholder, judge every cell with its fixed judge, then fold
     *  the verdicts into the per-judge agreement analysis. */
    fun startRun(context: Context, reportId: String, buildKey: String? = null): Job? {
        val rk: JudgeEvalRunKey = reportId
        runJobs[rk]?.let { if (it.isActive) return it }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val runId = java.util.UUID.randomUUID().toString()
        val startMs = System.currentTimeMillis()
        val job = appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "after/judges", runId = runId) {
                    val aiSettings = appViewModel.uiState.value.aiSettings
                    val prompt = judgePrompt(aiSettings)
                    if (prompt == null) {
                        AppLog.w("JudgeEval", "workers/tournament prompt not configured — aborting")
                        return@withTracerTags
                    }
                    val judges = resolveJudges(aiSettings, prompt)
                    if (judges.isEmpty()) {
                        AppLog.w("JudgeEval", "no resolvable judges in the prompt's swarm — aborting")
                        return@withTracerTags
                    }
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successful.size < 2) return@withTracerTags

                    // Distinct random answer-pairs, randomising which side is A.
                    val ids = successful.map { it.agentId }
                    val allPairs = ArrayList<Pair<String, String>>()
                    for (i in ids.indices) for (j in i + 1 until ids.size) allPairs.add(ids[i] to ids[j])
                    val chosen = allPairs.shuffled().take(JUDGE_MATCH_COUNT).map { (x, y) ->
                        if (kotlin.random.Random.nextBoolean()) Triple(x, y, 0) else Triple(y, x, 0)
                    }
                    AppLog.i("JudgeEval", "→ start report=$reportId (${judges.size} judges × ${chosen.size} matches = ${judges.size * chosen.size} cells)")
                    AuditLog.append(reportId, "Start Judge-the-judges — ${judges.size} judges × ${chosen.size} matches")
                    val scopeEncoded = SecondaryScope.AllReports.encode()

                    val aggregate = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.JUDGES, AGG_PROVIDER, AGG_MODEL, "Judge the judges"
                    ) {
                        it.copy(
                            tournamentRole = JUDGE_ROLE_AGGREGATE, tournamentJudgeRunId = runId,
                            metaPromptId = prompt.id, metaPromptName = prompt.name,
                            runId = runId, secondaryScope = scopeEncoded
                        )
                    }

                    val pending = mutableListOf<PendingCell>()
                    val newCells = LinkedHashMap<String, JudgeCellState>()
                    // Build stage: create every (match × judge) cell up front.
                    if (buildKey != null) appViewModel.beginBuild(buildKey, chosen.size * judges.size, "Building judge-the-judges")
                    var built = 0
                    for ((aId, bId, orient) in chosen) {
                        for (judge in judges) {
                            val placeholder = SecondaryResultStorage.create(
                                context, reportId, SecondaryKind.JUDGES,
                                judge.providerId, judge.model, "${judge.providerId} / ${judge.model}"
                            ) {
                                it.copy(
                                    tournamentRole = JUDGE_ROLE_CELL, tournamentJudgeRunId = runId,
                                    matchResponseAId = aId, matchResponseBId = bId, matchOrientation = orient,
                                    metaPromptId = prompt.id, metaPromptName = prompt.name,
                                    runId = runId, secondaryScope = scopeEncoded
                                )
                            }
                            pending.add(PendingCell(judge, aId, bId, orient, placeholder))
                            placeholder.toJudgeCellState()?.let { newCells[it.key] = it }
                            if (buildKey != null) { built++; if (built % 5 == 0) appViewModel.updateBuild(buildKey, built) }
                        }
                    }
                    if (buildKey != null) appViewModel.finishBuild(buildKey)

                    _runs.update { runs ->
                        runs + (rk to JudgeEvalRunState(
                            key = rk, reportId = reportId, runId = runId, prompt = prompt,
                            cells = newCells, aggregateRowId = aggregate.id
                        ))
                    }

                    dispatchCells(context, reportId, prompt, report.prompt, report.title, pending)
                    recomputeAndPersistAggregate(context, reportId)
                    AppLog.i("JudgeEval", "← done report=$reportId in ${System.currentTimeMillis() - startMs}ms")
                    AuditLog.append(reportId, "End Judge-the-judges")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                finalizeLeftoverCells(context, reportId)
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

    /** Fixed-host cell dispatch — each cell's judge provider is known up
     *  front, so the batch throttles per-host (unlike the worker round-robin). */
    private suspend fun dispatchCells(
        context: Context, reportId: String, prompt: InternalPrompt,
        question: String, title: String, items: List<PendingCell>
    ) {
        if (items.isEmpty()) return
        val report = ReportStorage.getReport(context, reportId)
        val agentsById = report?.agents?.associateBy { it.agentId }.orEmpty()
        runThrottledBatch(
            items = items,
            hostOf = { AppService.findById(it.judge.providerId)?.let { s -> providerHost(s) } },
            subCap = ApiCallCaps.workers,
            onThrottled = { appViewModel.updateThrottledJudgeEvalCells { s -> s + it.placeholder.id } },
            onCleared = { appViewModel.updateThrottledJudgeEvalCells { s -> s - it.placeholder.id } },
            register = { item, d ->
                cellJobs[item.placeholder.id] = d
                d.invokeOnCompletion { cellJobs.remove(item.placeholder.id, d) }
            },
            // Type-A fixed-model batch: a 429/529 short-benches the judge
            // model and re-queues the cell (and parks its same-model
            // siblings) instead of erroring outright.
            benchEnabled = com.ai.data.ModelCooldownStore.typeABenchEnabled,
            benchKey = { item -> item.judge.providerId to item.judge.model },
            onBenchRetry = { item -> restoreBenchedCellForRequeue(context, reportId, item) }
        ) { item ->
            if (!SecondaryResultStorage.exists(context, reportId, item.placeholder.id)) return@runThrottledBatch
            try {
                runOneCell(context, reportId, prompt, question, title, agentsById, item)
            } finally {
                appViewModel.updateThrottledJudgeEvalCells { it - item.placeholder.id }
            }
        }
    }

    // -----------------------------------------------------------------
    // Per-cell judge call (a single specific model, not the worker chain)
    // -----------------------------------------------------------------

    /** Reset a cell the bench loop is about to re-queue: clear the error the
     *  failed attempt persisted and put the in-memory cell back to PENDING (it
     *  shows as Bench while its judge model is short-benched, then Queue once
     *  the bench lifts). */
    private fun restoreBenchedCellForRequeue(context: Context, reportId: String, item: PendingCell) {
        val cKey = judgeCellKey(item.judge.providerId, item.judge.model, matchKey(item.aId, item.bId, item.orientation))
        SecondaryResultStorage.get(context, reportId, item.placeholder.id)?.let { saved ->
            SecondaryResultStorage.save(
                context,
                saved.copy(content = null, errorMessage = null, durationMs = null, tokenUsage = null)
            )
        }
        transitionCell(reportId, cKey) {
            it.copy(status = JudgeCellStatus.PENDING, content = null, errorMessage = null, durationMs = null)
        }
    }

    private suspend fun runOneCell(
        context: Context, reportId: String, prompt: InternalPrompt,
        question: String, title: String,
        agentsById: Map<String, ReportAgent>, item: PendingCell
    ) {
        val cKey = judgeCellKey(item.judge.providerId, item.judge.model, matchKey(item.aId, item.bId, item.orientation))
        val rowId = item.placeholder.id
        transitionCell(reportId, cKey) { it.copy(status = JudgeCellStatus.RUNNING) }
        val started = System.currentTimeMillis()
        val aiSettings = appViewModel.uiState.value.aiSettings
        val aBody = agentsById[item.aId]?.responseBody.orEmpty()
        val bBody = agentsById[item.bId]?.responseBody.orEmpty()
        val resolvedBase = resolveSecondaryPrompt(prompt.text, question = question, results = "", count = 2, title = title)
        val resolved = resolvedBase
            .replace("@RESPONSE_A@", aBody)
            .replace("@RESPONSE_B@", bBody)

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
                val resp = com.ai.data.withTraceFilenameSink(traceSink) {
                    appViewModel.repository.analyzeWithAgent(
                        agent, "", resolved, context = context, baseUrl = baseUrl, retry = false
                    )
                }
                if (resp.isSuccess && parseMatchVerdict(resp.analysis)?.verdict != null) {
                    val tu = resp.tokenUsage
                    val inT = tu?.inputTokens ?: 0
                    val outT = tu?.outputTokens ?: 0
                    var inCost = 0.0; var outCost = 0.0
                    if (tu != null && (inT > 0 || outT > 0)) {
                        val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
                        val split = PricingCache.computeInOutCost(tu, pricing)
                        inCost = split.first
                        outCost = split.second
                        appViewModel.settingsPrefs.updateUsageStatsAsync(agent.provider, agent.model, tu, kind = "judges")
                    }
                    SecondaryResultStorage.recordTournamentMatch(
                        context, reportId, rowId, item.judge.providerId, item.judge.model,
                        resp.analysis.orEmpty(),
                        inT, outT, inCost, outCost, System.currentTimeMillis() - started,
                        traceFile = traceSink.get()
                    )
                } else {
                    val msg = resp.error?.takeIf { it.isNotBlank() } ?: "judge produced no verdict"
                    recordCellError(context, reportId, rowId, item.placeholder, msg, started)
                }
            }
        } finally {
            // Mirror the saved row into memory in a NonCancellable block so a
            // stop / cancel mid-call still settles the cell on its disk-truth
            // status instead of leaving it stuck at RUNNING — the per-screen
            // 3s re-hydrate that used to recover that case has been removed.
            withContext(kotlinx.coroutines.NonCancellable) {
                val saved = SecondaryResultStorage.get(context, reportId, rowId)
                if (saved == null) dropCell(reportId, cKey)
                else transitionCell(reportId, cKey) {
                    saved.toJudgeCellState() ?: it.copy(status = JudgeCellStatus.ERROR, errorMessage = "Cell row could not be parsed")
                }
            }
        }
    }

    private fun recordCellError(
        context: Context, reportId: String, rowId: String,
        placeholder: SecondaryResult, message: String, started: Long
    ) {
        val cur = SecondaryResultStorage.get(context, reportId, rowId) ?: placeholder
        SecondaryResultStorage.save(context, cur.copy(errorMessage = message, durationMs = System.currentTimeMillis() - started))
    }

    // -----------------------------------------------------------------
    // Aggregation
    // -----------------------------------------------------------------

    private fun recomputeAndPersistAggregate(context: Context, reportId: String) {
        val run = _runs.value[reportId] ?: return
        val aggId = run.aggregateRowId ?: return
        val stats = analyzeJudges(run.cells.values.toList())
        val row = SecondaryResultStorage.get(context, reportId, aggId) ?: return
        SecondaryResultStorage.save(context, row.copy(
            content = stats.toJudgesJson(),
            durationMs = row.durationMs ?: 0
        ))
    }

    // -----------------------------------------------------------------
    // Swarm editing
    // -----------------------------------------------------------------

    /** Id of the swarm the judges are drawn from (the workers/tournament
     *  prompt's swarm), for the ✏️ "edit this swarm" jump. Null if unresolved. */
    fun activeSwarmId(): String? {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val swarmName = judgePrompt(aiSettings)?.workers?.firstOrNull()?.swarm ?: return null
        return aiSettings.getSwarmByName(swarmName)?.id
    }

    /** The set of judge keys ("providerId/model") the active swarm currently
     *  resolves to — for comparing against a finished run's judges after the
     *  user edits the swarm. Null when the prompt/judges can't be resolved. */
    fun activeJudgeKeys(): Set<String>? {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val prompt = judgePrompt(aiSettings) ?: return null
        return resolveJudges(aiSettings, prompt).map { it.key }.toSet()
    }

    /** Delete the current run and immediately start a fresh one — used when the
     *  user edits the judge swarm and chooses to re-judge with the new set. */
    fun rerunBatch(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch {
            deleteRun(context, reportId).join()
            startRun(context, reportId)
        }

    /** Remove a judge (provider/model) from the prompt's worker swarm, so
     *  future runs (and the Tournament) no longer use it. Persists settings.
     *  No-op if the swarm or member can't be found. */
    fun removeJudgeFromSwarm(providerId: String, model: String) {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val swarmName = judgePrompt(aiSettings)?.workers?.firstOrNull()?.swarm ?: return
        val updated = aiSettings.copy(swarms = aiSettings.swarms.map { s ->
            if (s.name.equals(swarmName, ignoreCase = true))
                s.copy(members = s.members.filter { !(it.provider.id == providerId && it.model == model) })
            else s
        })
        appViewModel.updateUiState { it.copy(aiSettings = updated) }
        appViewModel.viewModelScope.launch(Dispatchers.IO) { appViewModel.settingsPrefs.saveSettings(updated) }
        AppLog.i("JudgeEval", "Removed judge $providerId/$model from swarm '$swarmName'")
    }

    /** Drop a judge (provider/model) from the current run on [reportId]:
     *  cancel its in-flight cells, delete its rows, roll their spend into the
     *  deleted-items tally, drop them from the run state, and recompute the
     *  agreement aggregate over the remaining judges. */
    fun deleteJudgeFromRun(context: Context, reportId: String, providerId: String, model: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            val judgeKey = "$providerId/$model"
            val cells = run.cells.values.filter { it.judgeKey == judgeKey }
            if (cells.isEmpty()) return@launch
            val costDelta = cells.sumOf { it.totalCost }
            cells.forEach { c ->
                cellJobs[c.id]?.cancelAndJoin()
                SecondaryResultStorage.delete(context, reportId, c.id)
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            _runs.update { runs ->
                val r = runs[reportId] ?: return@update runs
                runs + (reportId to r.copy(cells = r.cells.filterValues { it.judgeKey != judgeKey }))
            }
            recomputeAndPersistAggregate(context, reportId)
            ReportStorage.bumpReportTimestamp(context, reportId)
            AppLog.i("JudgeEval", "Removed judge $judgeKey from run on $reportId (${cells.size} cells)")
        }

    /** Add a judge (provider/model) to the prompt's worker swarm — the inverse
     *  of [removeJudgeFromSwarm], so future runs (and the Tournament) include it.
     *  No-op if the swarm is unresolved or already contains the member. */
    fun addJudgeToSwarm(provider: AppService, model: String) {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val swarmName = judgePrompt(aiSettings)?.workers?.firstOrNull()?.swarm ?: return
        var changed = false
        val updated = aiSettings.copy(swarms = aiSettings.swarms.map { s ->
            if (s.name.equals(swarmName, ignoreCase = true) &&
                s.members.none { it.provider.id == provider.id && it.model == model }
            ) {
                changed = true
                s.copy(members = s.members + SwarmMember(provider, model))
            } else s
        })
        if (!changed) return
        appViewModel.updateUiState { it.copy(aiSettings = updated) }
        appViewModel.viewModelScope.launch(Dispatchers.IO) { appViewModel.settingsPrefs.saveSettings(updated) }
        AppLog.i("JudgeEval", "Added judge ${provider.id}/$model to swarm '$swarmName'")
    }

    /** Add ONE new judge (provider/model) to the current run on [reportId]:
     *  create a CELL for it on every match already in the run, run only those
     *  new cells, and refresh the agreement aggregate. Existing judges' cells
     *  are left untouched (only the new judge's row appears / updates). No-op if
     *  the judge is already in the run or there are no matches yet. */
    fun addJudgeToRun(context: Context, reportId: String, provider: AppService, model: String): Job =
        appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            val run = _runs.value[reportId] ?: return@launch
            val judgeKey = "${provider.id}/$model"
            if (run.cells.values.any { it.judgeKey == judgeKey }) return@launch
            val matches = run.cells.values
                .map { Triple(it.responseAId, it.responseBId, it.orientation) }.distinct()
            if (matches.isEmpty()) return@launch
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val prompt = run.prompt
            val runId = run.runId
            val scopeEncoded = SecondaryScope.AllReports.encode()
            val judge = Judge(Worker(provider = provider.id, model = model), provider.id, model)

            appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
            try {
                val pending = mutableListOf<PendingCell>()
                val newCells = LinkedHashMap<String, JudgeCellState>()
                for ((aId, bId, orient) in matches) {
                    val placeholder = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.JUDGES,
                        judge.providerId, judge.model, "${judge.providerId} / ${judge.model}"
                    ) {
                        it.copy(
                            tournamentRole = JUDGE_ROLE_CELL, tournamentJudgeRunId = runId,
                            matchResponseAId = aId, matchResponseBId = bId, matchOrientation = orient,
                            metaPromptId = prompt.id, metaPromptName = prompt.name,
                            runId = runId, secondaryScope = scopeEncoded
                        )
                    }
                    pending.add(PendingCell(judge, aId, bId, orient, placeholder))
                    placeholder.toJudgeCellState()?.let { newCells[it.key] = it }
                }
                _runs.update { runs ->
                    val r = runs[reportId] ?: return@update runs
                    runs + (reportId to r.copy(cells = r.cells + newCells))
                }
                ReportStorage.bumpReportTimestamp(context, reportId)
                AppLog.i("JudgeEval", "Added judge $judgeKey to run on $reportId (${matches.size} cells)")
                withTracerTags(reportId = reportId, category = "after/judges", runId = runId) {
                    dispatchCells(context, reportId, prompt, report.prompt, report.title, pending)
                }
                recomputeAndPersistAggregate(context, reportId)
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }

    // -----------------------------------------------------------------
    // Failure / rerun / delete
    // -----------------------------------------------------------------

    fun restartFailedCells(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            val keys = run.cells.values.filter { it.status == JudgeCellStatus.ERROR }.map { it.key }
            rerunCellsBlocking(context, reportId, keys)
            recomputeAndPersistAggregate(context, reportId)
        }

    fun rerunCell(context: Context, reportId: String, cKey: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            rerunCellsBlocking(context, reportId, listOf(cKey))
            recomputeAndPersistAggregate(context, reportId)
        }

    /** Drop every errored judge cell without re-firing — clears a
     *  permanently-dead failure so the run can settle. Rolls the spend into
     *  deleted-items and recomputes the agreement aggregate; if nothing is
     *  left, drops the whole run. */
    fun removeFailedCells(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            val failed = run.cells.values.filter { it.status == JudgeCellStatus.ERROR }
            if (failed.isEmpty()) return@launch
            failed.forEach { cellJobs[it.id]?.cancelAndJoin() }
            val costDelta = failed.sumOf { it.totalCost }
            failed.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            val remaining = run.cells - failed.map { it.key }.toSet()
            if (remaining.isEmpty()) {
                run.aggregateRowId?.let { SecondaryResultStorage.delete(context, reportId, it) }
                dropRun(reportId)
            } else {
                _runs.update { runs ->
                    val cur = runs[reportId] ?: return@update runs
                    runs + (reportId to cur.copy(cells = remaining))
                }
                recomputeAndPersistAggregate(context, reportId)
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }

    private suspend fun rerunCellsBlocking(context: Context, reportId: String, cKeys: List<String>) {
        if (cKeys.isEmpty()) return
        val run = _runs.value[reportId] ?: return
        val report = ReportStorage.getReport(context, reportId) ?: return
        val prompt = judgePrompt(appViewModel.uiState.value.aiSettings) ?: return
        val resets = mutableListOf<PendingCell>()
        for (k in cKeys) {
            val c = run.cells[k] ?: continue
            val cleared = clearCellRow(context, reportId, c.id) ?: continue
            transitionCell(reportId, k) {
                it.copy(
                    status = JudgeCellStatus.PENDING, content = null, verdict = null,
                    confidence = null, reason = null, errorMessage = null,
                    inputCost = null, outputCost = null, durationMs = null, tokenUsage = null
                )
            }
            resets.add(PendingCell(judgeFromRow(cleared), c.responseAId, c.responseBId, c.orientation, cleared))
        }
        if (resets.isEmpty()) return
        withTracerTags(reportId = reportId, category = "after/judges") {
            dispatchCells(context, reportId, prompt, report.prompt, report.title, resets)
        }
    }

    /** Reset a CELL row back to a blank placeholder, keeping its judge
     *  (providerId/model) so the re-judge goes to the same judge. */
    private fun clearCellRow(context: Context, reportId: String, rowId: String): SecondaryResult? {
        val cur = SecondaryResultStorage.get(context, reportId, rowId) ?: return null
        val cleared = cur.copy(
            content = null, errorMessage = null,
            inputCost = null, outputCost = null, tokenUsage = null, durationMs = null,
            timestamp = System.currentTimeMillis()
        )
        SecondaryResultStorage.save(context, cleared)
        return cleared
    }

    fun deleteRun(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            runJobs[reportId]?.cancelAndJoin()
            run.cells.values.forEach { cellJobs[it.id]?.cancelAndJoin() }
            val costDelta = run.cells.values.sumOf { it.totalCost }
            run.cells.values.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            run.aggregateRowId?.let { SecondaryResultStorage.delete(context, reportId, it) }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            dropRun(reportId)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }

    fun cancelAllForReport(reportId: String) {
        runJobs[reportId]?.cancel()
        _runs.value[reportId]?.cells?.values?.forEach { cellJobs[it.id]?.cancel() }
        _runs.update { it - reportId }
    }

    // -----------------------------------------------------------------
    // Resume on report open / app restart
    // -----------------------------------------------------------------

    /** Judge-cell row ids whose worker Job is live in THIS process — the
     *  read-only broken-work scan's in-flight exclusion (parallel to
     *  [FanOutEngine.inFlightRowIds]). Empty after a process kill. */
    fun inFlightRowIds(): Set<String> = cellJobs.keys.toSet()

    fun resumeStaleRunsForReport(context: Context, reportId: String, resetAttempts: Boolean = false): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            // No global coroutine exception handler exists, so an uncaught
            // throw on this viewModelScope launch crashes the app (the startup
            // resume sweep only join()s it). hydrate runs before the scan
            // guard, so guard it on its own; the body try below covers the rest.
            try {
                hydrate(context, reportId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("JudgeEval", "hydrate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
                return@launch
            }
            if (!resumeScans.add(reportId)) return@launch
            try {
                val run = _runs.value[reportId] ?: return@launch
                val prompt = run.prompt
                val report = ReportStorage.getReport(context, reportId) ?: return@launch
                val diskById = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.JUDGES)
                    .filter {
                        it.tournamentRole == JUDGE_ROLE_CELL && it.content.isNullOrBlank() &&
                            it.errorMessage == null && it.durationMs == null && !cellJobs.containsKey(it.id)
                    }.associateBy { it.id }
                if (diskById.isEmpty()) return@launch
                val staleRows = run.cells.values
                    .filter { it.status == JudgeCellStatus.PENDING && it.id in diskById }
                    .mapNotNull { diskById[it.id] }
                if (resetAttempts) BatchResume.resetAttempts(staleRows.map { it.id })
                val retryRows = BatchResume.capForRetry(staleRows) { row ->
                    markRowInterrupted(context, reportId, row.id, "Interrupted — no result after ${BatchResume.MAX_ATTEMPTS} resume attempts")
                    run.cells.values.firstOrNull { it.id == row.id }?.let { c ->
                        transitionCell(reportId, c.key) {
                            it.copy(status = JudgeCellStatus.ERROR, errorMessage = "Interrupted — no result after resume attempts", durationMs = 0)
                        }
                    }
                }
                val pending = retryRows.mapNotNull { row ->
                    val c = run.cells.values.firstOrNull { it.id == row.id } ?: return@mapNotNull null
                    PendingCell(judgeFromRow(row), c.responseAId, c.responseBId, c.orientation, row)
                }
                if (pending.isEmpty()) return@launch
                withTracerTags(reportId = reportId, category = "after/judges") {
                    dispatchCells(context, reportId, prompt, report.prompt, report.title, pending)
                }
                recomputeAndPersistAggregate(context, reportId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("JudgeEval", "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                resumeScans.remove(reportId)
            }
        }

    private suspend fun finalizeLeftoverCells(context: Context, reportId: String) {
        withContext(kotlinx.coroutines.NonCancellable) {
            val leftover = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.JUDGES)
                .filter {
                    it.tournamentRole == JUDGE_ROLE_CELL && it.content.isNullOrBlank() &&
                        it.errorMessage == null && it.durationMs == null && !cellJobs.containsKey(it.id)
                }
            BatchResume.finalizeLeftover(leftover) { row ->
                markRowInterrupted(context, reportId, row.id, "Interrupted — run stopped before this cell finished")
                _runs.value[reportId]?.cells?.values?.firstOrNull { it.id == row.id }?.let { c ->
                    transitionCell(reportId, c.key) {
                        if (it.status == JudgeCellStatus.PENDING || it.status == JudgeCellStatus.RUNNING)
                            it.copy(status = JudgeCellStatus.ERROR, errorMessage = "Interrupted", durationMs = 0)
                        else it
                    }
                }
            }
            recomputeAndPersistAggregate(context, reportId)
        }
    }

    private fun markRowInterrupted(context: Context, reportId: String, rowId: String, message: String) {
        val current = SecondaryResultStorage.get(context, reportId, rowId) ?: return
        if (current.errorMessage != null || !current.content.isNullOrBlank() || current.durationMs != null) return
        SecondaryResultStorage.save(context, current.copy(errorMessage = message, durationMs = 0))
    }
}
