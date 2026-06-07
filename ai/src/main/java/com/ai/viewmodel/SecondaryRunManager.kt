package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.helpers.translationRunGroupingId
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Secondary-result orchestration extracted from [ReportViewModel]:
 *  rerank / moderation, fan-in (+ model fan-in), meta runs, the shared
 *  executeSecondaryTask dispatch, secondary-result deletion, and the
 *  cross-kind report-open / background resume orchestrator. The whole
 *  fan-out lifecycle (launch / rerun / remove / resume) now lives on
 *  [com.ai.viewmodel.FanOutEngine] ([rvm.fanOutEngine]); this class only
 *  delegates to it from the resume orchestrator + fan-in's join. The
 *  `resumingMetaIds` guard stays on [rvm] and is reached via rvm.* . */
class SecondaryRunManager(
    private val appViewModel: AppViewModel,
    private val rvm: ReportViewModel
) {
    // ===== Worker-swarm dispatch for single-result secondaries =====
    //
    // Rerank / Moderation / Meta / Fan-in no longer take a user-picked
    // model. Each routes through a dedicated "workers"-category prompt
    // whose swarm (a copy of the cheap worker pool by default) is the
    // fallback chain — the same Mode-B path tournament / fan-meta use.
    // The chain runs OVER executeSecondaryTask, so a rerank-type (Cohere
    // / SiliconFlow) or moderation-type (Mistral) member still
    // auto-routes to its native endpoint while chat members take the
    // chat-JSON path.

    /** The "workers"-category prompt that carries [name]'s swarm. */
    private fun workerSwarmPrompt(aiSettings: Settings, name: String): InternalPrompt? =
        aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name.equals(name, ignoreCase = true)
        }

    /** Run one single-result secondary through [swarm] with 429 / miss
     *  fallback. [base] is a placeholder already persisted with this
     *  row's id + metadata (metaPromptId / name, scope, language). Each
     *  attempt re-stamps the placeholder's provider / model to the
     *  worker being tried, so a success attributes the row to the worker
     *  that actually answered. The row stays ERROR only when the whole
     *  chain is spent. */
    private suspend fun runSecondaryViaSwarm(
        context: Context, reportId: String, kind: SecondaryKind,
        contentPrompt: InternalPrompt, swarm: List<Worker>,
        resolvedPrompt: String, aiSettings: Settings, report: Report,
        base: SecondaryResult,
        targetLanguage: String? = null, targetLanguageNative: String? = null,
        referenceLegend: String? = null, fanInOf: String? = null,
        scopeEncoded: String? = null,
        paramsIds: List<String> = emptyList(), systemPromptId: String? = null
    ) {
        val members = swarm.flatMap { aiSettings.expandWorker(it) }
        if (members.isEmpty()) {
            SecondaryResultStorage.saveIfStillPresent(context, base.copy(
                errorMessage = "No workers configured for '${contentPrompt.name}' — add a swarm under AI Setup → Prompt management → Worker prompts."
            ))
            return
        }
        val cooldown = HashMap<String, Long>()
        var sawRateLimit = false
        var lastErr: String? = null
        for (idx in members.indices.shuffled()) {
            val w = members[idx]
            val key = "${w.provider}:${w.model}:${w.agent}"
            if ((cooldown[key] ?: 0L) > System.currentTimeMillis()) { sawRateLimit = true; continue }
            val agent = aiSettings.resolveWorker(w) ?: continue
            val model = aiSettings.getEffectiveModelForAgent(agent)
            val provider = agent.provider
            if (ModelCooldownStore.isUnavailable(provider.id, model)) { sawRateLimit = true; continue }
            // Row deleted mid-run — stop without recreating it.
            if (!SecondaryResultStorage.exists(context, reportId, base.id)) return
            ApiCallCaps.global.withPermit {
                executeSecondaryTask(
                    context, reportId, kind, contentPrompt,
                    provider, model, resolvedPrompt, aiSettings, report,
                    targetLanguage = targetLanguage, targetLanguageNative = targetLanguageNative,
                    referenceLegend = referenceLegend, fanInOf = fanInOf,
                    existingPlaceholder = base.copy(providerId = provider.id, model = model),
                    scopeEncoded = scopeEncoded,
                    paramsIds = paramsIds, systemPromptId = systemPromptId
                )
            }
            val row = SecondaryResultStorage.get(context, reportId, base.id) ?: return
            if (row.errorMessage == null && !row.content.isNullOrBlank()) return  // success
            lastErr = row.errorMessage
            if (row.errorMessage?.contains("429") == true || row.errorMessage?.contains("529") == true) {
                cooldown[key] = System.currentTimeMillis() + WORKER_429_DEFAULT_MS
                sawRateLimit = true
            }
        }
        val msg = if (sawRateLimit) "All '${contentPrompt.name}' workers rate-limited — try again shortly."
                  else (lastErr ?: "No worker produced a result for '${contentPrompt.name}'.")
        SecondaryResultStorage.saveIfStillPresent(context,
            (SecondaryResultStorage.get(context, reportId, base.id) ?: base).copy(errorMessage = msg))
    }

    // ===== Meta prompt results =====

    /** Kick off a Rerank or Summarize run for [reportId] across [picks]
     *  (provider/model pairs). One [SecondaryResult] per pick is persisted
     *  independently — multi-model picks produce N separate viewable
     *  results. Each call launches its own coroutine; multiple batches
     *  can run concurrently, so a second click while a first is still
     *  in flight does NOT cancel the earlier one. UiState's
     *  [com.ai.viewmodel.UiState.activeSecondaryBatches] counter is
     *  bumped on entry and decremented in a finally block so the Meta
     *  screen's hourglass / poll loop reflects "anything running" no
     *  matter how many overlap. */
    /** Rerank the current report's responses using a locally-installed
     *  MediaPipe TextEmbedder. Embed the prompt + each successful
     *  agent response, score by cosine similarity to the prompt, and
     *  emit the same JSON shape the chat-model / Cohere rerank flows
     *  produce so downstream code (Top-Ranked scope, HTML export,
     *  detail screen) keeps working unchanged. SecondaryResult is
     *  saved with providerId="LOCAL" so cost / usage rows stay
     *  separate from remote provider activity. */
    fun runLocalRerank(
        context: Context,
        reportId: String,
        modelName: String
    ): Job {
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "after/rerank") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    val responses = report.agents
                        .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                        .map { it.responseBody!! }
                    if (responses.isEmpty()) return@withTracerTags
                    val agentName = "Local / ${shortModelName(modelName)}"
                    val placeholder = SecondaryResultStorage.create(context, reportId, SecondaryKind.RERANK, "LOCAL", modelName, agentName)
                    ReportStorage.bumpReportTimestamp(context, reportId)

                    val started = System.currentTimeMillis()
                    val queryVec = com.ai.data.local.LocalEmbedder.embed(context, modelName, listOf(report.prompt))?.firstOrNull()
                    val docVecs = com.ai.data.local.LocalEmbedder.embed(context, modelName, responses)
                    val durationMs = System.currentTimeMillis() - started

                    if (queryVec == null || docVecs == null) {
                        SecondaryResultStorage.save(context, placeholder.copy(
                            errorMessage = "Local embedder failed — check that $modelName is installed (Housekeeping → Local LiteRT models).",
                            durationMs = durationMs
                        ))
                        return@withTracerTags
                    }

                    // Cosine score per doc, descending. Scores rescaled
                    // 0-100 to match the chat-model rerank output.
                    val scored = docVecs.mapIndexed { idx, vec ->
                        val sim = com.ai.data.EmbeddingsStore.cosine(queryVec, vec)
                        Triple(idx + 1, sim, ((sim.coerceIn(-1.0, 1.0) + 1.0) * 50.0).toInt().coerceIn(0, 100))
                    }.sortedByDescending { it.second }

                    val arr = com.google.gson.JsonArray()
                    scored.forEachIndexed { rank, (originalId, sim, score) ->
                        arr.add(com.google.gson.JsonObject().apply {
                            addProperty("id", originalId)
                            addProperty("rank", rank + 1)
                            addProperty("score", score)
                            addProperty("reason", "Cosine similarity: %.4f".format(sim))
                        })
                    }
                    SecondaryResultStorage.save(context, placeholder.copy(
                        content = arr.toString(),
                        errorMessage = null,
                        durationMs = durationMs
                    ))
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Single-pick Rerank runner exposed to the report action row. For
     *  the synthetic LOCAL provider this delegates to [runLocalRerank];
     *  for any other provider it resolves the `rerank` Internal prompt,
     *  builds the @RESULTS@ block from the report's successful agent
     *  responses, and dispatches through [executeSecondaryTask] with
     *  [SecondaryKind.RERANK]. The dispatch already routes RERANK-typed
     *  models (Cohere rerank-v3.5 etc.) to the dedicated rerank API and
     *  routes chat models through the standard analyse path. */
    fun runRerank(
        context: Context,
        reportId: String,
        /** Honoured only as a single language: rerank is one call against
         *  one set of bodies. AllPresent or a Selected set whose first
         *  non-empty entry is "" means "rank the original bodies"; a
         *  non-empty entry means "rank the translated bodies for that
         *  language". Multi-language Selected just picks the first. */
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent,
        paramsIds: List<String> = emptyList(),
        systemPromptId: String? = null,
        /** When non-null (the driving prompt is *SELECT), run against these
         *  user-picked workers instead of the configured "second-rerank" chain. */
        overrideWorkers: List<com.ai.model.Worker>? = null
    ): Job? {
        AppLog.i("Rerank", "→ start report=$reportId via the Rerank worker swarm")
        val aiSettings = appViewModel.uiState.value.aiSettings
        // The workers/second-rerank prompt carries both the chat-JSON
        // template and the "Rerank" swarm. A rerank-type member auto-uses
        // its native endpoint inside executeSecondaryTask.
        val rerankPrompt = workerSwarmPrompt(aiSettings, "second-rerank")
            ?: aiSettings.getInternalPromptByName("second-rerank")
            ?: return null
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "after/rerank") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successfulCount = report.agents.count {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successfulCount == 0) return@withTracerTags
                    val sourceLanguage: String? = (languageScope as? SecondaryLanguageScope.Selected)
                        ?.languages?.firstOrNull()?.takeIf { it.isNotEmpty() }
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    val (questionForPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, sourceLanguage, includeIds = null)
                    val langCtx = lookupLanguageTranslations(report, allSecondaries, sourceLanguage)
                    val titleForPrompt = langCtx?.title ?: (report.title ?: "")
                    val resolvedPrompt = resolveSecondaryPrompt(
                        rerankPrompt.text, question = questionForPrompt, results = resultsBlock,
                        count = successfulCount, title = titleForPrompt
                    )
                    val base = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.RERANK, "", "", "Rerank"
                    ) {
                        it.copy(
                            targetLanguage = sourceLanguage,
                            targetLanguageNative = langCtx?.native,
                            metaPromptId = rerankPrompt.id,
                            metaPromptName = rerankPrompt.name,
                            secondaryParameterPresetIds = paramsIds,
                            secondarySystemPromptId = systemPromptId
                        )
                    }
                    runSecondaryViaSwarm(
                        context, reportId, SecondaryKind.RERANK, rerankPrompt, overrideWorkers ?: rerankPrompt.workers,
                        resolvedPrompt, aiSettings, report, base,
                        targetLanguage = sourceLanguage, targetLanguageNative = langCtx?.native,
                        paramsIds = paramsIds, systemPromptId = systemPromptId
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Run a moderation pass on this report — classifies every
     *  successful agent's response via the provider's native
     *  /v1/moderations endpoint (Mistral compatible). One batch
     *  call, one persisted SecondaryResult with structured JSON
     *  content (per-input flagged + categories + scores).
     *
     *  executeSecondaryTask short-circuits on `kind == MODERATION`
     *  and routes through [com.ai.data.callModerationApi] rather
     *  than the chat path, so the [resolvedPrompt] arg here is
     *  unused at runtime — a stub InternalPrompt covers the
     *  metaPromptId / metaPromptName columns on the persisted row. */
    fun runModeration(
        context: Context,
        reportId: String,
        /** Same single-language semantics as rerank. When set, the
         *  moderation API receives translated bodies (fallback per-
         *  agent to the original) and the persisted row is tagged
         *  with the language so it appears under that section. */
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent,
        /** When non-null (the driving prompt is *SELECT), run against these
         *  user-picked workers instead of the configured "second-moderation" chain. */
        overrideWorkers: List<com.ai.model.Worker>? = null
    ): Job? {
        AppLog.i("Moderation", "→ start report=$reportId via the Moderation worker swarm")
        val aiSettings = appViewModel.uiState.value.aiSettings
        // The workers/second-moderation prompt carries the "Moderation"
        // swarm (only Mistral has native moderation wired, so the default
        // swarm is just mistral-moderation-latest). The text is unused —
        // executeSecondaryTask short-circuits MODERATION to the native
        // /moderations endpoint.
        val moderationPrompt = workerSwarmPrompt(aiSettings, "second-moderation")
            ?: aiSettings.getInternalPromptByName("second-moderation")
            ?: com.ai.model.InternalPrompt(
                id = "moderation",
                name = "Moderation",
                category = "workers",
                text = ""
            )
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "after/moderation") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successfulCount = report.agents.count {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successfulCount == 0) return@withTracerTags
                    val sourceLanguage: String? = (languageScope as? SecondaryLanguageScope.Selected)
                        ?.languages?.firstOrNull()?.takeIf { it.isNotEmpty() }
                    val native = sourceLanguage?.let { lang ->
                        val secondaries = SecondaryResultStorage.listForReport(context, reportId)
                        lookupLanguageTranslations(report, secondaries, lang)?.native
                    }
                    val base = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.MODERATION, "", "", "Moderation"
                    ) {
                        it.copy(
                            targetLanguage = sourceLanguage,
                            targetLanguageNative = native,
                            metaPromptId = moderationPrompt.id,
                            metaPromptName = moderationPrompt.name
                        )
                    }
                    runSecondaryViaSwarm(
                        context, reportId, SecondaryKind.MODERATION, moderationPrompt, overrideWorkers ?: moderationPrompt.workers,
                        resolvedPrompt = "", aiSettings = aiSettings, report = report, base = base,
                        targetLanguage = sourceLanguage, targetLanguageNative = native
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Resume every interrupted Translation, Fan-out, and
     *  single-call Meta/Rerank/Moderation run on the report.
     *  **Manual/explicit use only** now — it is no longer fired on
     *  report open or by the background loop (that loop is detect-only:
     *  see [startBackgroundBrokenScan] / [detectBrokenBatchesForReport]). Kept
     *  for explicit user fixes (Regenerate / retry) and the regenerate
     *  orchestrator.
     *
     *  - **Translation**: groups disk rows by `translationRunId`; for
     *    each runId that isn't already in [_translationRuns], calls
     *    [startMissingTranslations] which dispatches every expected
     *    item (prompt + successful agents + meta secondaries) that
     *    doesn't yet have a row.
     *  - **Fan-out**: delegates to
     *    [com.ai.viewmodel.FanOutEngine.resumeStaleRunsForReport], which
     *    re-dispatches every stale pair (bounded by BatchResume) and
     *    terminalizes deleted-prompt / unrecoverable rows.
     *  - **Single Meta/Rerank/Moderation**: walks stale rows where
     *    `fanOutSourceAgentId == null && fanInOf == null &&
     *    translationRunId == null` and re-issues each via
     *    [resumeStaleMetaPlaceholder] using the placeholder's persisted
     *    `metaPromptId`, `providerId`, `model`, and `secondaryScope`.
     *  - **Unrecoverable** (legacy rows missing fields, prompts since
     *    deleted, fan-in / model-fan-in rows whose substitution data
     *    can't be reconstructed): falls back to the existing
     *    "No data yet" marker so the row renders ❌
     *    and the user can manually retry.
     *
     *  Only rows interrupted by app death (content blank, errorMessage
     *  null, durationMs null) are touched — previously-errored rows are
     *  left as-is. Translation runs use the same `translationRunId`
     *  that was assigned at start, so the resume queues under the
     *  original group on the result page. */
    fun resumeStaleRunsForReport(
        context: Context,
        reportId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
      // Fire-and-forget on viewModelScope; there's no global coroutine
      // exception handler, so an uncaught throw here (the startup sweep only
      // join()s this Job, it can't catch it) crashes the app. Contain the
      // whole orchestrator — a failed pass just retries on the next sweep/open.
      try {
        // Single-secondary in-flight ids so a slow-but-running auto
        // Meta/Rerank/Moderation isn't re-dispatched + terminalized as
        // stale. Fan-out pairs are owned by the engine (step 2 delegates
        // to it), so they don't need to be in this set.
        val running = appViewModel.runningSingleSecondaries.value
        // Only runs that are actually in flight block step 1 — a
        // run that previously finished (or one a reconcile rebuilt
        // with finished=true) can still have disk placeholders that
        // step 1 must re-dispatch. Snapshot taken once; the
        // reconcile sweep (step 0 below) is awaited per-entry so by
        // the time step 1 reads this snapshot it's still pre-
        // reconcile — runs the reconcile is about to re-seed (via
        // its own startMissingTranslations call) are NOT in this
        // set, so step 1 would double-dispatch — runTranslationSubset's
        // persistedRowId dedupe handles that race cleanly.
        val activeTranslationRunIds = rvm.translation.translationRuns.value.values
            .filter { !it.isFinished && !it.cancelled }
            .map { it.runId }
            .toSet()
        val rows = SecondaryResultStorage.listForReport(context, reportId)
        val aiSettings = appViewModel.uiState.value.aiSettings

        // 0. Reconcile any in-memory translation runs for this report
        //    that are flagged !isFinished && !cancelled but have no
        //    live dispatch job. Those are demonstrably stuck — a
        //    previous flow's coroutine (cross-translate, restart-
        //    failed, etc.) died before flipping `finished` — and the
        //    disk-rebuild via reconcileStalledTranslationRun is safe
        //    because there's no worker mid-write to race.
        //
        //    Ordering: this step runs BEFORE step 1's
        //    startMissingTranslations dispatches. activeTranslationRunIds
        //    (snapshotted above) still includes these runIds, so step
        //    1's "skip if already active" guard keeps it from racing
        //    the reconcile's async rebuild. Same-session navigate-back
        //    catches the stuck state; fresh app start has an empty
        //    _translationRuns so this filter matches nothing and step
        //    1 owns the dispatch path as today.
        rvm.translation.translationRuns.value.values
            .filter {
                it.sourceReportId == reportId &&
                    !it.isFinished && !it.cancelled &&
                    rvm.translation.translationJobs[it.runId]?.isActive != true
            }
            .forEach { rvm.translation.reconcileStalledTranslationRun(context, reportId, it.runId) }

        // 1. Translation: walk every distinct translationRunId on disk
        //    that isn't actively running in memory and queue the
        //    missing items. startMissingTranslations needs at least one
        //    anchor row for the run; any runId with zero rows on disk
        //    can't be resumed (no provider/model/language to read).
        val translationRunIds = rows
            .filter { it.kind == SecondaryKind.TRANSLATE && it.translationRunId != null }
            .map { it.translationRunId!! }
            .distinct()
        translationRunIds.forEach { runId ->
            if (runId in activeTranslationRunIds) return@forEach
            rvm.translation.startMissingTranslations(context, reportId, runId)
        }

        // 2. Fan-out pairs: the FanOutEngine owns the whole lifecycle now
        //    (hydrate → re-dispatch stale pairs with a BatchResume cap →
        //    terminalize unrecoverable / deleted-prompt rows). One call
        //    handles every run on the report; idempotent vs in-flight work.
        rvm.fanOutEngine.resumeStaleRunsForReport(context, reportId)

        // 2c. Tournament matches: same contract — TournamentEngine owns the
        //     lifecycle (hydrate → re-dispatch stale matches). Without this the
        //     app-start + 30 s background sweep covered every other batch but
        //     not tournaments, so an interrupted tournament never auto-resumed.
        rvm.tournamentEngine.resumeStaleRunsForReport(context, reportId)

        // 2c'. Judge-the-judges batches: same contract — JudgeEvalEngine owns
        //      the lifecycle (hydrate → re-dispatch stale cells).
        rvm.judgeEvalEngine.resumeStaleRunsForReport(context, reportId)

        // 2b. Fan Meta batches: relaunch any fan-meta sweep the user
        //     started (some pair already carries a title/icon / error /
        //     run id) so a batch interrupted by an app kill resumes from
        //     this same orchestrator. runFanMetaBatch dedupes in-flight
        //     jobs and is a no-op when no pair is pending.
        rows.filter {
            it.kind == SecondaryKind.META && it.fanOutSourceAgentId != null && it.fanInOf == null &&
                (!it.title.isNullOrBlank() || !it.titleErrorMessage.isNullOrBlank() || it.titleRunId != null ||
                    !it.icon.isNullOrBlank() || !it.iconErrorMessage.isNullOrBlank() || it.iconRunId != null)
        }
            .mapNotNull { it.metaPromptId }
            .distinct()
            .filter { pid -> aiSettings.internalPrompts.any { it.id == pid } }
            .forEach { promptId -> rvm.iconGen.runFanMetaBatch(context, reportId, promptId) }

        // 3. Single-call Meta/Rerank/Moderation: re-issue each stale
        //    placeholder via executeSecondaryTask. Fan-in single
        //    (fanInOf != null) rows are skipped here — they go to the
        //    legacy mark-as-❌ branch below since their substitution
        //    inputs aren't derivable from the placeholder alone.
        val staleSingleMeta = rows.filter {
            it.kind != SecondaryKind.TRANSLATE &&
                // Tournament MATCH/AGGREGATE rows are owned by TournamentEngine
                // (resumed via its own resumeStaleRunsForReport) — never drive
                // them through the single-Meta path or terminalize them here, or
                // the engine's in-flight pending matches get clobbered ❌.
                it.kind != SecondaryKind.TOURNAMENT &&
                // JUDGES cells are owned by JudgeEvalEngine — same contract.
                it.kind != SecondaryKind.JUDGES &&
                it.content.isNullOrBlank() &&
                it.errorMessage == null &&
                it.durationMs == null &&
                it.fanOutSourceAgentId == null &&
                it.fanInOf == null &&
                it.translationRunId == null &&
                it.id !in running &&
                it.metaPromptId != null &&
                aiSettings.internalPrompts.any { p -> p.id == it.metaPromptId } &&
                AppService.findById(it.providerId) != null
        }
        BatchResume.capForRetry(staleSingleMeta) {
            markRowAsInterrupted(context, reportId, it.id, "Interrupted — no result after ${BatchResume.MAX_ATTEMPTS} resume attempts")
        }.forEach { row ->
            resumeStaleMetaPlaceholder(context, reportId, row)
        }

        // 5. Regenerate batch: ask the engine to reconcile any
        //    persisted RegenerateJob for this report. Idempotent;
        //    no-op when DONE / CANCELLED, revives a stale RUNNING
        //    orchestrator (process-kill recovery), or auto-resumes
        //    a PAUSED_ON_ERROR job whose offending row was fixed.
        rvm.regenerateBatchEngine.reconcile(context, reportId)

        // 4. Legacy fallback: any other stale row we can't reconstruct
        //    (fan-in single, model-fan-in, deleted prompt for a non-
        //    fan-out single meta, deleted provider) gets the honest ❌
        //    so it stops spinning.
        val handledIds = staleSingleMeta.map { it.id }.toSet()
        rows.forEach { row ->
            if (row.errorMessage != null) return@forEach
            if (!row.content.isNullOrBlank()) return@forEach
            if (row.durationMs != null) return@forEach
            if (row.id in running) return@forEach
            if (row.id in handledIds) return@forEach
            // Fan-out pair rows are owned by the engine's resume (step 2);
            // never terminalize them here or we'd clobber a row the engine
            // is about to re-dispatch.
            if (row.kind == SecondaryKind.META && row.fanOutSourceAgentId != null && row.fanInOf == null) return@forEach
            // Tournament rows are owned by TournamentEngine — same contract as
            // fan-out pairs; terminalizing its pending matches here is what made
            // a big tournament show a flood of "No data yet" ❌ mid-run.
            if (row.kind == SecondaryKind.TOURNAMENT) return@forEach
            // JUDGES cells are owned by JudgeEvalEngine — same contract.
            if (row.kind == SecondaryKind.JUDGES) return@forEach
            // Translation rows in an active or newly-resumed run are
            // covered by startMissingTranslations; skip them here.
            if (row.kind == SecondaryKind.TRANSLATE &&
                row.translationRunId != null &&
                (row.translationRunId in activeTranslationRunIds ||
                    row.translationRunId in translationRunIds)) return@forEach
            // Anything still standing here is unrecoverable — mark ❌.
            markRowAsInterrupted(context, reportId, row.id, "No data yet")
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
      } catch (e: Exception) {
          AppLog.w("SecondaryResume", "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
      }
    }

    /** App-wide background broken-work scan. Walks every report whose
     *  timestamp is within the last 7 days and asks [detectBrokenBatchesForReport]
     *  which of them carry interrupted work — translation / fan-out /
     *  tournament / judges / single-Meta placeholders, plus stalled
     *  regenerate jobs — WITHOUT touching anything. The result is published
     *  whole to [AppViewModel.setBrokenBatches], which drives the ⚠️ top-bar
     *  badge and the Broken-work screen.
     *
     *  Read-only by design: the app no longer auto-resumes interrupted
     *  batches. The per-report [resumeStaleRunsForReport] orchestrator (and
     *  each engine's resume) is kept for explicit manual fixes (Regenerate /
     *  retry), but nothing automatic dispatches or terminalizes any more.
     *
     *  Lifecycle: one loop at a time. The Job is stored on
     *  [AppViewModel.backgroundResumeSweepJob] so a re-creation of
     *  ReportViewModel (Activity config change tearing down the `remember{}`
     *  ReportViewModel inside AppNavHost) cancels the prior loop before
     *  starting the fresh one. */
    fun startBackgroundBrokenScan(context: Context) {
        appViewModel.backgroundResumeSweepJob?.cancel()
        appViewModel.backgroundResumeSweepJob = appViewModel.viewModelScope.launch(
            rvm.reportLogContext("background-broken-scan")
        ) {
            // One-time pass first: a hard-killed batch leaves blank placeholder
            // cells whose run is no longer active. Mark them interrupted up front
            // so the very first scan flags them, instead of waiting out the
            // stale-placeholder grace.
            try {
                finalizeAbandonedLeftovers(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("BrokenScan", "startup finalize failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                try {
                    refreshBrokenBatches(context)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("BrokenScan", "iteration failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }

    /** Walks every report newer than 7 days and emits one [BrokenBatch] per
     *  (kind, run) that carries interrupted and/or errored work. Sequential
     *  per-report disk scan — same cost profile as the old resume sweep,
     *  minus all dispatch. Sorted newest-first for the list. */
    private suspend fun scanBrokenRunsForRecentReports(context: Context): List<BrokenBatch> {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val recent = withContext(Dispatchers.IO) {
            ReportStorage.getAllReports(context).filter { it.timestamp >= cutoff }
        }
        if (recent.isEmpty()) return emptyList()
        val batches = recent.flatMap { report -> detectBrokenBatchesForReport(context, report) }
            .sortedByDescending { it.timestamp }
        AppLog.d("BrokenScan", "scanned ${recent.size} report${if (recent.size == 1) "" else "s"} (7d) → ${batches.size} broken batch${if (batches.size == 1) "" else "es"}")
        return batches
    }

    /** Force one Broken-work scan now. Used after manual recovery actions so
     *  the badge/screen do not keep stale actionable cards until the next
     *  30-second background tick. */
    suspend fun refreshBrokenBatches(context: Context) {
        appViewModel.setBrokenBatches(scanBrokenRunsForRecentReports(context))
    }

    /** The live "what's running right now in THIS process" snapshot the
     *  Broken-work policy needs to exclude rows a worker still owns. After a
     *  process kill every set is empty — exactly when blank placeholders are
     *  genuinely abandoned. Shared by the read-only scan and the startup
     *  finalize so both apply identical active/in-flight guards. */
    private fun liveStateFor(reportId: String): BrokenWorkLiveState {
        val inFlight = appViewModel.runningSingleSecondaries.value +
            rvm.fanOutEngine.inFlightRowIds() +
            rvm.tournamentEngine.inFlightRowIds() +
            rvm.judgeEvalEngine.inFlightRowIds() +
            rvm.compareEngine.inFlightRowIds() +
            rvm.resumingMetaIds
        val activeFanMetaRunKeys = rvm.fanMetaJobs
            .filterValues { it.isActive }
            .keys
            .mapNotNull { key ->
                val marker = "$reportId|meta|"
                if (key.startsWith(marker)) "$reportId|${key.removePrefix(marker)}" else null
            }
            .toSet()
        val activeTranslationRunIds = rvm.translation.translationRuns.value.values
            .filter { !it.isFinished && !it.cancelled }
            .map { it.runId }
            .toSet()
        return BrokenWorkLiveState(
            inFlightRowIds = inFlight,
            activeFanOutRunKeys = rvm.fanOutEngine.activeRunKeys(),
            activeTournamentRunKeys = rvm.tournamentEngine.activeRunKeys(),
            activeJudgeRunKeys = rvm.judgeEvalEngine.activeRunKeys(),
            activeCompareRunKeys = rvm.compareEngine.activeRunKeys(),
            activeFanMetaRunKeys = activeFanMetaRunKeys,
            runningFanMetaRowIds = appViewModel.runningFanMetaPairs.value,
            activeTranslationRunIds = activeTranslationRunIds,
        )
    }

    /** One-time startup pass over recent reports: stamp the blank placeholder
     *  cells of runs that are no longer active (a hard kill left them, so the
     *  run's own `finally` never marked them) with the same "Interrupted"
     *  message a clean stop writes. With the stale-placeholder grace dropped to
     *  zero here — and the active/in-flight guards from [liveStateFor] still in
     *  force — abandoned work surfaces on the first Broken-work scan instead of
     *  after the 60s grace, while a live or just-started run is left untouched.
     *  Covers every batch family at once (the content-blank filter skips Fan
     *  Meta's title/icon sub-state, which isn't a blank placeholder). */
    private suspend fun finalizeAbandonedLeftovers(context: Context) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val recent = ReportStorage.getAllReports(context).filter { it.timestamp >= cutoff }
        var marked = 0
        recent.forEach { report ->
            val rows = SecondaryResultStorage.listForReport(context, report.id)
            val live = liveStateFor(report.id).copy(stalePlaceholderMs = 0L)
            BrokenWorkPolicy.detectBatches(report.id, report.title, report.timestamp, rows, live)
                .flatMap { BrokenWorkPolicy.matchingRows(rows, it, BrokenItemMode.UNFINISHED, live) }
                .distinctBy { it.id }
                .filter { it.content.isNullOrBlank() && it.errorMessage == null && it.durationMs == null }
                .forEach { row ->
                    SecondaryResultStorage.save(
                        context,
                        row.copy(errorMessage = "Interrupted — run stopped before this cell finished", durationMs = 0)
                    )
                    marked++
                }
        }
        if (marked > 0) AppLog.d("BrokenScan", "startup: finalized $marked abandoned leftover cell(s)")
    }

    /** Read-only detection for one report: groups every interrupted (blank
     *  content, no error, no duration, not in flight) and errored
     *  SecondaryResult row into its batch ([BrokenBatch]) and tallies the
     *  two states separately. Covers all six batch families, a stalled
     *  regenerate job, and single Meta/Rerank/Moderation calls. Mutates
     *  NOTHING — it mirrors the engines' predicates but never dispatches,
     *  terminalizes, or hydrates. One entry per (kind, run); empty when
     *  nothing needs attention. A fan-out pair feeds BOTH its Fan Out batch
     *  (response state) and its Fan Meta batch (title/icon state). The
     *  "MATCH" sentinel is the shared match/cell role
     *  (TournamentEngine.ROLE_MATCH / [com.ai.data.JUDGE_ROLE_CELL]). */
    private fun detectBrokenBatchesForReport(context: Context, report: Report): List<BrokenBatch> {
        val reportId = report.id
        val rows = SecondaryResultStorage.listForReport(context, reportId)
        val batches = BrokenWorkPolicy.detectBatches(
            reportId = reportId,
            reportTitle = report.title,
            reportTimestamp = report.timestamp,
            rows = rows,
            live = liveStateFor(reportId)
        ).toMutableList()

        // Stalled regenerate job — one synthetic entry. PAUSED_ON_ERROR reads
        // as an error (with a message); a dead-orchestrator RUNNING job reads
        // as interrupted/unfinished.
        if (rvm.regenerateBatchEngine.detectBroken(context, reportId)) {
            val paused = com.ai.data.RegenerateBatchStorage.get(context, reportId)?.status ==
                com.ai.data.RegenerateJobStatus.PAUSED_ON_ERROR
            batches.add(BrokenBatch(reportId, report.title, BatchFamilyKind.REGENERATE, reportId,
                "Regenerate",
                unfinishedCount = if (paused) 0 else 1,
                errorCount = if (paused) 1 else 0,
                timestamp = report.timestamp,
                errorMessage = if (paused) "Paused on an error" else "Interrupted mid-run"))
        }

        // Primary report agents — ERROR responses, or PENDING/RUNNING agents
        // a process kill stranded (only when no live generation/regenerate is
        // running for this report). One synthetic "Report models" entry: the
        // agent analogue of the secondary batches above. This is the condition
        // the hub-only reportHasProblems used to carry alone; folding it here
        // makes the ⚠️ badge and the hub's "problems" card agree.
        val (interruptedAgents, erroredAgents) = BrokenWorkPolicy.agentProblems(
            report, reportIsLive = rvm.isReportGenerating(reportId)
        )
        if (interruptedAgents.isNotEmpty() || erroredAgents.isNotEmpty()) {
            // One broken agent → key on the agentId and label with the model
            // name, so the card taps straight through to that model's Model
            // response screen (skipping the agents list). Many → key on the
            // reportId, label "Report models", and the list picks one.
            val singleAgentId = if (interruptedAgents.size + erroredAgents.size == 1)
                (erroredAgents + interruptedAgents).first() else null
            val singleAgent = singleAgentId?.let { id -> report.agents.firstOrNull { it.agentId == id } }
            val singleError = if (erroredAgents.size == 1 && interruptedAgents.isEmpty())
                singleAgent?.errorMessage else null
            batches.add(BrokenBatch(reportId, report.title, BatchFamilyKind.RESPONSES,
                key = singleAgentId ?: reportId,
                batchName = singleAgent?.let { it.model.ifBlank { it.agentName }.ifBlank { "model" } }
                    ?: "Report models",
                unfinishedCount = interruptedAgents.size,
                errorCount = erroredAgents.size,
                timestamp = report.timestamp,
                errorMessage = singleError))
        }
        return batches
    }

    /** TOCTOU-safe re-read + save pair that flips a stuck placeholder
     *  into a terminal errored row. Used by [resumeStaleRunsForReport]'s
     *  fallback branch for rows that can't be reconstructed (legacy
     *  rows missing fields, prompts since deleted, fan-in single
     *  rows). Re-reads the row right before saving so an in-flight
     *  completion landing between our list scan and the save isn't
     *  clobbered. */
    private fun markRowAsInterrupted(
        context: Context,
        reportId: String,
        rowId: String,
        message: String
    ) {
        val current = SecondaryResultStorage.get(context, reportId, rowId) ?: return
        if (current.errorMessage != null) return
        if (!current.content.isNullOrBlank()) return
        if (current.durationMs != null) return
        SecondaryResultStorage.save(context, current.copy(
            errorMessage = message,
            durationMs = 0
        ))
    }

    /** Re-issue a single interrupted META / RERANK / MODERATION
     *  placeholder. Looks up the [com.ai.model.InternalPrompt] by
     *  [SecondaryResult.metaPromptId] and the [AppService] by
     *  [SecondaryResult.providerId], decodes the persisted
     *  [SecondaryResult.secondaryScope], rebuilds the resolved prompt
     *  from the current report state, and calls [executeSecondaryTask]
     *  with the same placeholder so the in-place row transitions
     *  from ⏳ to ✅/❌ rather than being replaced by a fresh row. */
    internal fun resumeStaleMetaPlaceholder(
        context: Context,
        reportId: String,
        placeholder: SecondaryResult
    ): Job? {
        if (!rvm.resumingMetaIds.add(placeholder.id)) return null
        val promptId = placeholder.metaPromptId ?: run {
            rvm.resumingMetaIds.remove(placeholder.id); return null
        }
        val state = appViewModel.uiState.value
        val aiSettings = state.aiSettings
        val metaPrompt = aiSettings.internalPrompts.firstOrNull { it.id == promptId } ?: run {
            rvm.resumingMetaIds.remove(placeholder.id); return null
        }
        val provider = AppService.findById(placeholder.providerId) ?: run {
            rvm.resumingMetaIds.remove(placeholder.id); return null
        }
        val model = placeholder.model
        val scope = com.ai.data.SecondaryScope.decodeOrAllReports(placeholder.secondaryScope)
        val kind = placeholder.kind
        val lang = placeholder.targetLanguage
        val langNative = placeholder.targetLanguageNative
        val cat = "${metaPrompt.category}/${metaPrompt.name}"

        AppLog.i("Resume", "→ re-issue ${kind.name} \"${metaPrompt.name}\" report=$reportId row=${placeholder.id} via ${provider.id}/$model")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                placeholder.fullCost().takeIf { it > 0.0 }?.let {
                    ReportStorage.bumpCostsFromDeletedItems(context, reportId, it)
                }
                withTracerTags(reportId = reportId, category = cat) {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    // Fan-in (combine-reports) rows rebuild from the fan-out
                    // matrix, NOT from the report's answers — resolve via the
                    // shared fan-in builder and re-issue against the same
                    // placeholder (preserving the fanInOf linkage). Without
                    // this branch the row would be regenerated as a plain
                    // meta over the report answers (wrong content).
                    if (placeholder.fanInOf != null) {
                        val resolution = buildFanInResolution(context, reportId, metaPrompt, report, lang)
                            ?: return@withTracerTags
                        executeSecondaryTask(
                            context, reportId, kind, metaPrompt,
                            provider, model, resolution.resolvedPrompt, aiSettings, report,
                            targetLanguage = lang,
                            targetLanguageNative = langNative ?: resolution.languageNative,
                            fanInOf = placeholder.fanInOf,
                            existingPlaceholder = placeholder,
                            scopeEncoded = placeholder.secondaryScope
                        )
                        return@withTracerTags
                    }
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    // Same scope → includeIds resolution as runMetaPrompt.
                    val includeIds: Set<Int>? = when (scope) {
                        com.ai.data.SecondaryScope.AllReports -> null
                        is com.ai.data.SecondaryScope.TopRanked -> {
                            val rerank = SecondaryResultStorage.get(context, reportId, scope.rerankResultId)
                            val ids = com.ai.data.extractTopRankedIds(rerank?.content, scope.count)
                            // Clamp stale 1-based positions to the current
                            // successful count — same as runMetaPrompt — so a
                            // removed agent can't leave an out-of-range id that
                            // inflates @COUNT@ above the emitted result blocks.
                            val nSuccess = report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                            val valid = ids?.filter { it in 1..nSuccess }
                            if (valid.isNullOrEmpty()) null else valid.toSet()
                        }
                        is com.ai.data.SecondaryScope.Manual -> {
                            val successful = report.agents.filter {
                                it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                            }
                            val ids = successful.mapIndexedNotNull { idx, a ->
                                if (a.agentId in scope.agentIds) idx + 1 else null
                            }
                            if (ids.isEmpty()) null else ids.toSet()
                        }
                    }
                    val successfulCount = if (includeIds != null) includeIds.size
                        else report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    val (translatedPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, lang, includeIds)
                    val resolvedPrompt = resolveSecondaryPrompt(
                        metaPrompt.text, question = translatedPrompt, results = resultsBlock,
                        count = successfulCount, title = report.title
                    )
                    val referenceLegend = if (metaPrompt.reference) buildReferenceLegend(report, includeIds) else null
                    executeSecondaryTask(
                        context, reportId, kind, metaPrompt,
                        provider, model, resolvedPrompt, aiSettings, report,
                        lang, langNative, referenceLegend,
                        existingPlaceholder = placeholder,
                        scopeEncoded = placeholder.secondaryScope
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                rvm.resumingMetaIds.remove(placeholder.id)
            }
        }
    }

    /** Combines a fan-out run's per-pair responses plus every
     *  successful agent's response body into a single combined report
     *  via a single chat call on [pick]. The template's iterable block
     *  `\n\n***Report*** @REPORT@@RESPONSES@` is expanded once per
     *  successful (source) agent, with its `@RESPONSES@` populated
     *  from the latest fan-out response row of every other answerer.
     *
     *  Persists one [SecondaryResult] with kind=META and
     *  fanInOf=metaPrompt.id, so the fan out detail screen can show
     *  it inline above the L1 list while the View bucket still buckets
     *  by `metaPromptName`.
     */
    fun runFanInPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        /** English-name source language inherited from the parent
         *  fan-out (null = Original). When non-null, every @-token the
         *  fan-in template substitutes — @QUESTION@, @TITLE@, and the
         *  per-source @REPORT@ body — comes from the matching
         *  translation rows. The persisted combined-report is also
         *  tagged with the language so it groups under that section
         *  in the report list. */
        sourceLanguage: String? = null,
        paramsIds: List<String> = emptyList(),
        systemPromptId: String? = null,
        /** When non-null (the driving prompt is *SELECT), run against these
         *  user-picked workers instead of the configured "fan-in" chain. */
        overrideWorkers: List<com.ai.model.Worker>? = null
    ): Job? {
        AppLog.i("FanIn", "→ start \"${metaPrompt.name}\" report=$reportId via the Fan-in worker swarm")
        val aiSettings0 = appViewModel.uiState.value.aiSettings
        val swarm = overrideWorkers ?: workerSwarmPrompt(aiSettings0, "fan-in")?.workers ?: emptyList()
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val cat = "${metaPrompt.category}/${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    // Build the fan-in prompt from the current fan-out
                    // matrix. Shared with the resume / edit paths so the
                    // matrix assembly never drifts (see buildFanInResolution).
                    val resolution = buildFanInResolution(context, reportId, metaPrompt, report, sourceLanguage)
                    if (resolution == null) {
                        SecondaryResultStorage.create(
                            context, reportId, SecondaryKind.META, "", "", "Fan-in: ${metaPrompt.name}"
                        ) {
                            it.copy(
                                metaPromptId = metaPrompt.id,
                                metaPromptName = metaPrompt.name,
                                fanInOf = metaPrompt.id,
                                errorMessage = "No fan-out responses available — run the fan-out prompt first."
                            )
                        }
                        return@withTracerTags
                    }
                    val base = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.META, "", "", "Fan-in: ${metaPrompt.name}"
                    ) {
                        it.copy(
                            targetLanguage = sourceLanguage,
                            targetLanguageNative = resolution.languageNative,
                            metaPromptId = metaPrompt.id,
                            metaPromptName = metaPrompt.name,
                            fanInOf = metaPrompt.id,
                            secondaryParameterPresetIds = paramsIds,
                            secondarySystemPromptId = systemPromptId
                        )
                    }
                    runSecondaryViaSwarm(
                        context, reportId, SecondaryKind.META, metaPrompt,
                        // ♻️ report-models as the worker swarm when the flag is on.
                        if (report.useReportModelsAsWorkers) reportModelWorkers(report) else swarm,
                        resolution.resolvedPrompt, aiSettings, report, base,
                        targetLanguage = sourceLanguage,
                        targetLanguageNative = resolution.languageNative,
                        fanInOf = metaPrompt.id,
                        paramsIds = paramsIds, systemPromptId = systemPromptId
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Resolved fan-in (combine-reports) prompt + the source-report count
     *  it expanded, plus the resolved language native name. */
    internal data class FanInResolution(
        val resolvedPrompt: String,
        val sourceCount: Int,
        val languageNative: String?
    )

    /** Rebuild the fan-in (combine-reports) prompt for [reportId] from the
     *  current fan-out matrix — the single source of truth shared by
     *  [runFanInPrompt] (initial run), [resumeStaleMetaPlaceholder] (resume
     *  after kill) and [MetaEditManager]'s ✏️ edit overlay (Reload / sweeps
     *  / prompt-edit), so the matrix assembly never drifts. Joins any
     *  in-flight fan-out runs first so the matrix is complete before it is
     *  read. Returns null when no fan-out responses exist yet (the caller
     *  surfaces the "run the fan-out prompt first" error). */
    internal suspend fun buildFanInResolution(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        report: Report,
        sourceLanguage: String?
    ): FanInResolution? {
        val successful = report.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
        }
        // Wait for any in-flight fan out runs on this report to finish
        // before reading their per-pair rows — otherwise the combine call
        // would build its payload from an arbitrary partial subset of
        // responses while the user thinks the report is "all of them". The
        // engine owns every run's batch Job; join them all for this report.
        rvm.fanOutEngine.joinActiveRunsForReport(reportId)
        val fanOutRows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter { it.fanOutSourceAgentId != null }
            // Drop errored rows before bucketing — without this, the
            // firstOrNull pick below grabs the OLDEST row (we sort
            // ascending), which is the failed first attempt. A successful
            // retry landed afterwards is then ignored. Filtering up front
            // leaves only valid responses in the bucket.
            .filter { it.errorMessage == null && !it.content.isNullOrBlank() }
        // Bucket fan out rows by (providerId, model, sourceAgentId). Two
        // report rows can legitimately share (provider, model) — e.g. an
        // Agent UUID row and a swarm:provider:model row pointing at the
        // same model under different agentIds. Bucketing into a list keeps
        // every matching row.
        val byPair = LinkedHashMap<String, MutableList<SecondaryResult>>()
        fanOutRows.sortedBy { it.timestamp }.forEach { r ->
            val src = r.fanOutSourceAgentId ?: return@forEach
            byPair.getOrPut("${r.providerId}|${r.model}|$src") { mutableListOf() }.add(r)
        }
        val consumed = HashSet<String>()
        // Translation context for the parent fan-out's language. Null when
        // sourceLanguage is null (Original); otherwise carries the
        // translated prompt/title/native + per-agent translated body map.
        val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
        val langCtx = lookupLanguageTranslations(report, allSecondaries, sourceLanguage)
        val perReport: List<Pair<String, List<String>>> = successful.map { source ->
            val fanOutResponses = successful.mapNotNull other@{ other ->
                if (other.agentId == source.agentId) return@other null
                // Pick the next un-consumed row for this (provider, model,
                // source) bucket so two distinct other-agents sharing
                // (provider, model) each get their own response.
                val bucket = byPair["${other.provider}|${other.model}|${source.agentId}"]
                    ?: return@other null
                val row = bucket.firstOrNull { it.id !in consumed }
                    ?: return@other null
                consumed += row.id
                if (row.errorMessage != null) return@other null
                val c = row.content
                if (c.isNullOrBlank()) null else c.trim()
            }
            // Each @REPORT@ slot: translated body when available, original
            // otherwise. Without this, a Dutch fan-in feeds the picked
            // model the Dutch @RESPONSES@ but English @REPORT@.
            val sourceBody = langCtx?.bodiesByAgentId?.get(source.agentId)
                ?: source.responseBody?.trim().orEmpty()
            sourceBody to fanOutResponses
        }
        if (perReport.all { it.second.isEmpty() }) return null
        val resolved = resolveFanInPrompt(
            template = metaPrompt.text,
            question = langCtx?.prompt ?: report.prompt,
            count = perReport.size,
            fanOutCount = (perReport.size - 1).coerceAtLeast(0),
            perReport = perReport,
            title = langCtx?.title ?: report.title
        )
        return FanInResolution(resolved, perReport.size, langCtx?.native)
    }

    /** Build a fresh AI Report from the L2 active model's fan-out
     *  conversation. Promotes "active model said X, the others
     *  responded" into a standalone report:
     *
     *  - prompt = active model's report response on the source
     *    report (verbatim)
     *  - title  = "From: <orig title> · <provider> / <model>"
     *  - agents = one synthesised [ReportAgent] per fan-out row
     *    where active is the source (i.e. fanOutSourceAgentId in
     *    activeAgentIds). Carries the answerer's body, error,
     *    tokens, cost, and durationMs straight through so the
     *    new result page reads like a normal completed report.
     *
     *  Buckets per (other-provider, other-model, source-agent),
     *  keeping the latest row — same dedup the L2 page uses for
     *  its on-screen list, so retries don't pile up.
     *
     *  Returns the new report id on success, null when there's
     *  nothing to copy (active agent missing / no fan-out rows /
     *  active's responseBody blank). */
    suspend fun createReportFromFanOut(
        context: Context,
        sourceReportId: String,
        activeProviderId: String,
        activeModel: String
    ): String? = withContext(Dispatchers.IO) {
        val source = ReportStorage.getReport(context, sourceReportId) ?: return@withContext null
        val activeAgents = source.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS
                && it.provider == activeProviderId && it.model == activeModel
                && !it.responseBody.isNullOrBlank()
        }
        val active = activeAgents.firstOrNull() ?: return@withContext null
        val activeAgentIds = activeAgents.map { it.agentId }.toHashSet()

        val raw = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.META)
            .filter { it.fanOutSourceAgentId in activeAgentIds && it.fanInOf == null }
        val bucketed = LinkedHashMap<String, SecondaryResult>()
        raw.sortedBy { it.timestamp }.forEach { r ->
            bucketed["${r.providerId}|${r.model}|${r.fanOutSourceAgentId}"] = r
        }
        val rows = bucketed.values.toList()
        if (rows.isEmpty()) return@withContext null

        val newAgents = rows.map { row ->
            ReportAgent(
                agentId = java.util.UUID.randomUUID().toString(),
                agentName = "${row.providerId} / ${shortModelName(row.model)}",
                provider = row.providerId,
                model = row.model,
                // A pair that errored → ERROR; one with real content →
                // SUCCESS; anything else (still in-flight / blank reply)
                // would otherwise become a green SUCCESS agent with an
                // empty body. Map that to STOPPED so a half-finished
                // fan-out doesn't mint blank "successful" rows.
                reportStatus = when {
                    row.errorMessage != null -> ReportStatus.ERROR
                    !row.content.isNullOrBlank() -> ReportStatus.SUCCESS
                    else -> ReportStatus.STOPPED
                },
                responseBody = row.content,
                errorMessage = row.errorMessage,
                tokenUsage = row.tokenUsage,
                cost = ((row.inputCost ?: 0.0) + (row.outputCost ?: 0.0)).takeIf { it > 0.0 },
                // Carry the FROZEN cost split + trace from the pair row.
                // Without inputCost/outputCost the cost UI falls back to
                // live PricingCache recomputation (ContentDisplay), so the
                // derived report's historical cost drifts as the catalog
                // re-prices. traceFile is null on today's fan-out pairs
                // (only TRANSLATE rows persist one) but is wired through so
                // the 🐞 affordance works if that ever changes.
                inputCost = row.inputCost,
                outputCost = row.outputCost,
                traceFile = row.traceFile,
                durationMs = row.durationMs
            )
        }

        val now = System.currentTimeMillis()
        val srcTitle = source.title.ifBlank { "AI Report" }
        val newTitle = "From: $srcTitle · $activeProviderId / $activeModel"
        val newReport = Report(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = now,
            createdAt = now,
            title = newTitle,
            prompt = active.responseBody.orEmpty(),
            agents = newAgents.toMutableList(),
            completedAt = now,
            sourceReportId = sourceReportId,
            totalCost = newAgents.mapNotNull { it.cost }.sum()
        )
        // Mirror the source's icon + language visible state onto the
        // new report. Without this the inline icon / language rows on
        // the new report's Report - manage screen would sit with a
        // spinning ⏳ forever (icon null + error null = "generating",
        // languageName null + error null = "detecting"), even though
        // nothing is actually running — the fan-out derivation never
        // schedules those API calls. Same shape as
        // ReportStorage.copyReport's icon + language carry-over.
        // Costs / tokens / trace files stay at defaults: those calls
        // were paid for by the source.
        newReport.icon = source.icon
        newReport.iconErrorMessage = source.iconErrorMessage
        newReport.languageName = source.languageName
        newReport.languageIcon = source.languageIcon
        newReport.languageIconErrorMessage = source.languageIconErrorMessage
        ReportStorage.persistNewReport(context, newReport)
        newReport.id
    }

    fun runMetaPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        scopeChoice: SecondaryScope = SecondaryScope.AllReports,
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent,
        paramsIds: List<String> = emptyList(),
        systemPromptId: String? = null,
        /** When non-null (the driving prompt is *SELECT), run against these
         *  user-picked workers instead of the configured "meta" chain. */
        overrideWorkers: List<com.ai.model.Worker>? = null
    ): Job? {
        val kind = SecondaryKind.META
        // Swarm from the dedicated workers/meta holder prompt; the
        // content comes from the user's chosen meta prompt.
        val swarm = overrideWorkers ?: workerSwarmPrompt(appViewModel.uiState.value.aiSettings, "meta")?.workers ?: emptyList()
        AppLog.i("Meta", "→ start \"${metaPrompt.name}\" report=$reportId via the Meta worker swarm")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }

        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            // Tag every API call this batch makes with the parent
            // report's id and a Meta-prompt-name category. Without the
            // reportId tag the resulting trace files would land with
            // reportId=null and the report's JSON export wouldn't
            // pull them in. withTracerTags saves and restores both
            // values so this works correctly when nested.
            val cat = "${metaPrompt.category}/${metaPrompt.name}"
            try {
              withTracerTags(reportId = reportId, category = cat) {
                val state = appViewModel.uiState.value
                val aiSettings = state.aiSettings
                val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                // Bump the parent report's timestamp so it sorts to the top
                // of the History list — adding a meta result is a real
                // update to the report, not a passive read.
                ReportStorage.bumpReportTimestamp(context, reportId)
                // Resolve scope: AllReports → no filter; TopRanked → parse
                // the chosen rerank, take the top-N original ids. If parsing
                // fails (legacy / malformed rerank output) fall back to
                // AllReports rather than blocking the user.
                val includeIds: Set<Int>? = when (scopeChoice) {
                    SecondaryScope.AllReports -> null
                    is SecondaryScope.TopRanked -> {
                        val rerank = SecondaryResultStorage.get(context, reportId, scopeChoice.rerankResultId)
                        val ids = extractTopRankedIds(rerank?.content, scopeChoice.count)
                        // The rerank stored 1-based positions, which go stale
                        // if the agent set changed since (e.g. an agent was
                        // removed): an out-of-range id would feed a
                        // non-existent agent and inflate @COUNT@. Clamp to the
                        // current successful count; positions still in range
                        // are honoured.
                        val nSuccess = report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                        val valid = ids?.filter { it in 1..nSuccess }
                        if (valid.isNullOrEmpty()) null else valid.toSet()
                    }
                    is SecondaryScope.Manual -> {
                        // Manual is expressed as agentIds; convert to the
                        // 1-based original-id indices used by buildLanguageInputs
                        // / buildResultsBlock.
                        val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                        val ids = successful.mapIndexedNotNull { idx, a ->
                            if (a.agentId in scopeChoice.agentIds) idx + 1 else null
                        }
                        if (ids.isEmpty()) null else ids.toSet()
                    }
                }
                val successfulCount = if (includeIds != null) includeIds.size
                    else report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }

                // Multi-language fan-out: one batch per language present
                // on the report. The Original language is encoded as null
                // and the SecondaryResult.targetLanguage stays null for
                // it; translations get the human English name.
                val translationLanguages = run {
                    val nativeByLang = LinkedHashMap<String, String?>()
                    allSecondaries
                        .filter { it.kind == SecondaryKind.TRANSLATE && !it.targetLanguage.isNullOrBlank() }
                        .forEach { tr ->
                            val l = tr.targetLanguage!!
                            if (l !in nativeByLang) nativeByLang[l] = tr.targetLanguageNative
                        }
                    when (languageScope) {
                        SecondaryLanguageScope.AllPresent -> nativeByLang
                        is SecondaryLanguageScope.Selected -> {
                            val filtered = LinkedHashMap<String, String?>()
                            nativeByLang.forEach { (k, v) -> if (k in languageScope.languages) filtered[k] = v }
                            filtered
                        }
                    }
                }
                // The original (untranslated) source is included by
                // default and when the user kept it ticked under
                // Selected. The empty-string sentinel "" in
                // SecondaryLanguageScope.Selected.languages signals
                // "include original".
                val includeOriginal = when (languageScope) {
                    SecondaryLanguageScope.AllPresent -> true
                    is SecondaryLanguageScope.Selected -> "" in languageScope.languages
                }
                val languages: List<Pair<String?, String?>> =
                    (if (includeOriginal) listOf<Pair<String?, String?>>(null to null) else emptyList()) +
                    translationLanguages.map { (lang, native) -> lang to native }

                // Reference legend — built when the prompt's reference
                // flag is on. Computed once per batch.
                val referenceLegend = if (metaPrompt.reference)
                    buildReferenceLegend(report, includeIds) else null

                // Multi-language meta: instead of fanning out N×M
                // independent META rows (one per language × pick), run
                // the meta ONCE in a single "seed" language and then
                // append cross-translation items to each other
                // selected language's existing translation run. The
                // seed prefers Original when it's in the selection,
                // otherwise the first non-original language. Picks
                // still produce M META rows in the seed language.
                if (languages.isEmpty()) return@withTracerTags
                val seedLang: Pair<String?, String?> =
                    if (languages.any { it.first == null }) null to null
                    else languages.first()
                val nonSeedLanguages = languages.filter { it != seedLang }

                // Phase 1: seed-language meta run (the only META rows
                // produced by this invocation).
                val (translatedPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, seedLang.first, includeIds)
                // @TITLE@: prefer the per-language TITLE translation
                // row when one exists; fall back to the original title.
                // Without this, a Dutch seed run would send Dutch
                // QUESTION + RESULTS but an English title — the model
                // tends to mirror the title's language in its reply.
                val seedTitle = lookupLanguageTranslations(report, allSecondaries, seedLang.first)?.title
                    ?: (report.title ?: "")
                // Use the report-language translation of this meta prompt
                // when one is bundled (English / unknown keeps the editable
                // text); the body is otherwise identical.
                val localizedTemplate = com.ai.data.InternalPromptSeed.bodyForReportLanguage(
                    context, report.languageName, metaPrompt
                )
                val resolvedPrompt = resolveSecondaryPrompt(
                    localizedTemplate, question = translatedPrompt, results = resultsBlock,
                    count = successfulCount, title = seedTitle
                )
                // One seed META row, produced via the Meta worker swarm
                // (single result with 429 / miss fallback — no user-picked
                // model). Its id is needed up front for phase 2's
                // cross-translate items.
                val langSuffix = seedLang.first?.let { " [$it]" } ?: ""
                val base = SecondaryResultStorage.create(
                    context, reportId, kind, "", "", "${metaPrompt.name}$langSuffix"
                ) {
                    it.copy(
                        targetLanguage = seedLang.first,
                        targetLanguageNative = seedLang.second,
                        metaPromptId = metaPrompt.id,
                        metaPromptName = metaPrompt.name,
                        secondaryScope = scopeChoice.encode(),
                        secondaryParameterPresetIds = paramsIds,
                        secondarySystemPromptId = systemPromptId
                    )
                }
                runSecondaryViaSwarm(
                    context, reportId, kind, metaPrompt,
                    // ♻️ When the report flag is on, the worker swarm is the
                    // report's own models; runSecondaryViaSwarm shuffles, so
                    // a single Meta result picks one at random.
                    if (report.useReportModelsAsWorkers) reportModelWorkers(report) else swarm,
                    resolvedPrompt, aiSettings, report, base,
                    targetLanguage = seedLang.first, targetLanguageNative = seedLang.second,
                    referenceLegend = referenceLegend,
                    scopeEncoded = scopeChoice.encode(),
                    paramsIds = paramsIds, systemPromptId = systemPromptId
                )

                // Phase 2: cross-translate the seed META to each non-seed
                // language. Re-read the row from disk so we pick up the
                // saved content / errorMessage; an errored seed is skipped.
                if (nonSeedLanguages.isNotEmpty()) {
                    val completedSeedMetas = listOfNotNull(
                        SecondaryResultStorage.get(context, reportId, base.id)
                    ).filter { !it.content.isNullOrBlank() && it.errorMessage == null }
                    if (completedSeedMetas.isNotEmpty()) {
                        val secondariesAfter = SecondaryResultStorage.listForReport(context, reportId)
                        for ((lang, langNative) in nonSeedLanguages) {
                            if (lang == null) continue
                            rvm.translation.addCrossTranslationItems(
                                context, reportId,
                                targetLanguageName = lang,
                                targetLanguageNative = langNative ?: lang,
                                sourceMetas = completedSeedMetas,
                                allSecondaries = secondariesAfter
                            )
                        }
                    }
                }
              }
            } finally {
                // activeSecondaryBatches must drop even if the tag
                // block throws or is cancelled mid-batch — otherwise
                // the Meta screen's hourglass / poll loop would think
                // a batch is still in flight forever.
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }


    internal suspend fun executeSecondaryTask(
        context: Context, reportId: String, kind: SecondaryKind,
        metaPrompt: com.ai.model.InternalPrompt,
        provider: AppService, model: String, resolvedPrompt: String, aiSettings: Settings,
        report: Report,
        targetLanguage: String? = null,
        targetLanguageNative: String? = null,
        referenceLegend: String? = null,
        fanOutSourceAgentId: String? = null,
        fanInOf: String? = null,
        /** Optional pre-created placeholder. When the caller has staged
         *  a row up-front (fan-out does this so all N×(M-1) pair
         *  rows surface as ⏳ on the fan out detail screen the moment the
         *  run starts) we run against that row instead of creating a
         *  fresh one — otherwise the placeholder duplicates and the
         *  pre-created row never gets a result. */
        existingPlaceholder: SecondaryResult? = null,
        scopeEncoded: String? = null,
        /** Per-launch 🌡️ / 🎭 pick; empty → App-wide default fallback. */
        paramsIds: List<String> = emptyList(),
        systemPromptId: String? = null
    ) {
        val apiKey = aiSettings.getApiKey(provider)
        val langSuffix = targetLanguage?.let { " [$it]" } ?: ""
        val agentName = "${provider.id} / ${shortModelName(model)}$langSuffix"
        val placeholder = existingPlaceholder ?: SecondaryResultStorage.create(
            context, reportId, kind, provider.id, model, agentName
        ) {
            it.copy(
                targetLanguage = targetLanguage,
                targetLanguageNative = targetLanguageNative,
                metaPromptId = metaPrompt.id,
                metaPromptName = metaPrompt.name,
                fanOutSourceAgentId = fanOutSourceAgentId,
                fanInOf = fanInOf,
                secondaryScope = scopeEncoded,
                secondaryParameterPresetIds = paramsIds,
                secondarySystemPromptId = systemPromptId
            )
        }

        // Mark this single-secondary row in flight for the whole call
        // (incl. its wait in the per-provider rate gate) so the resume
        // sweep doesn't see a slow-but-running meta/rerank/moderation as
        // "stale" and terminalize it after 3 attempts. Cleared in finally.
        appViewModel.updateRunningSingleSecondaries { it + placeholder.id }
        try {

        // Model benched on a >1h 429 by an earlier call — skip the
        // doomed call but keep the row as a visible red error (don't
        // delete it). runOnePair re-reads the row, sees the
        // errorMessage, and marks the pair ERROR rather than dropping
        // it; a single secondary run shows the same red error row.
        if (rvm.isBenched(provider, model)) {
            AppLog.w("Secondary", "skip benched ${provider.id}/$model — marking row ${placeholder.id} errored")
            SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                errorMessage = "${provider.id}/$model is rate-limited (benched) — skipped"
            ))
            return
        }

        // Moderation runs through the dedicated /v1/moderations
        // endpoint — one batch call classifying every report response.
        // No chat prompt, no per-response loop here (the API takes the
        // input array and returns one result per input). The structured
        // JSON content is rendered as a flagged-categories table by the
        // detail screen.
        if (kind == SecondaryKind.MODERATION) {
            // When the caller picked a target language on the scope
            // screen, classify the TRANSLATED bodies instead of the
            // originals — otherwise a "Run moderation in Dutch" click
            // still moderates the English text. Falls back to the
            // original body per-agent when a translation row is
            // missing so a partial set still classifies coherently.
            val translatedBodies: Map<String, String>? = targetLanguage?.let { lang ->
                val secondaries = SecondaryResultStorage.listForReport(context, reportId)
                lookupLanguageTranslations(report, secondaries, lang)?.bodiesByAgentId
            }
            val responses = report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .map { agent -> translatedBodies?.get(agent.agentId) ?: agent.responseBody!! }
            val (_, r) = com.ai.data.callModerationApi(provider, apiKey, model, responses)
            // Persist Mistral's reported token usage + per-token cost
            // so the result row shows cents like the other meta runs.
            // Falls through to no-cost when the API didn't report usage.
            val tu = r.tokenUsage
            val pricing = PricingCache.getPricing(context, provider, model)
            val (inCost, outCost) = tu?.let { PricingCache.computeInOutCost(it, pricing) }
                ?: (null to null)
            val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                tokenUsage = tu,
                inputCost = inCost,
                outputCost = outCost,
                durationMs = r.durationMs,
                httpStatusCode = r.httpStatusCode,
                responseChangeSource = null,
                responseChangeValue = null
            ))
            // Skip usage-stats too if the row was deleted while in
            // flight — the user dropped this run, so we shouldn't bill
            // the per-provider token counters for it either.
            if (saved && r.errorMessage == null && tu != null) {
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, tu, kind = "moderation"
                )
            }
            return
        }

        // Rerank-typed models (Cohere rerank-v3.5 etc.) don't have a chat
        // endpoint — they take query + documents and return relevance
        // scores. Detect that and route to the dedicated rerank API,
        // converting the response back to the structured JSON the rest
        // of the system already consumes (HTML export, Top-Ranked scope).
        val isRerankApiPath = kind == SecondaryKind.RERANK
            && aiSettings.getModelType(provider, model) == com.ai.data.ModelType.RERANK
        if (isRerankApiPath) {
            // Honour a selected target language: rank the TRANSLATED query +
            // documents (falling back to the originals per-agent / for the
            // query when a translation row is missing), so "Rerank in Dutch"
            // ranks the Dutch text rather than the English originals — same
            // as the moderation branch above.
            val rerankLangCtx = targetLanguage?.let { lang ->
                lookupLanguageTranslations(report, SecondaryResultStorage.listForReport(context, reportId), lang)
            }
            val query = rerankLangCtx?.prompt?.takeIf { it.isNotBlank() } ?: report.prompt
            val docs = report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .map { agent -> rerankLangCtx?.bodiesByAgentId?.get(agent.agentId) ?: agent.responseBody!! }
            val r = com.ai.data.callRerankApi(provider, apiKey, model, query, docs)
            // Per-query pricing: cost = billedSearchUnits × perQueryPrice.
            // Stored on inputCost so the report cost table renders
            // alongside chat/summarize rows. The provider may omit the
            // billed-units block on an error response — fall back to 1
            // unit per call so cost is at least roughly tracked.
            val pricing = PricingCache.getPricing(context, provider, model)
            val units = r.billedSearchUnits ?: if (r.errorMessage == null) 1 else 0
            val perQueryCost = if (units > 0) units * pricing.perQueryPrice else 0.0
            // Token-billed rerankers (SiliconFlow, Novita, …) return no
            // billed-search-units and price per TOKEN, not per query — so
            // perQueryCost is 0 for them. Fall back to estimating the input
            // (query + every document) at the model's prompt price, the same
            // way usage-less embedding calls are costed. estimateTokens is a
            // chars/4 heuristic; the rerank API returns no usage to do better.
            // Gated on promptPrice > 0 so a genuinely free / unpriced model
            // still reads as $0 instead of carrying a fabricated token count.
            val estInputTokens = if (r.errorMessage == null && perQueryCost <= 0.0 && pricing.promptPrice > 0.0)
                AppViewModel.estimateTokens(query) + docs.sumOf { AppViewModel.estimateTokens(it) }
                else 0
            val rerankCost = when {
                perQueryCost > 0.0 -> perQueryCost
                estInputTokens > 0 -> estInputTokens * pricing.promptPrice
                else -> null
            }
            val rerankTokenUsage = if (estInputTokens > 0)
                com.ai.data.TokenUsage(inputTokens = estInputTokens, outputTokens = 0) else null
            val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                inputCost = rerankCost,
                tokenUsage = rerankTokenUsage,
                durationMs = r.durationMs,
                httpStatusCode = r.httpStatusCode
            ))
            if (saved && r.errorMessage == null) {
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, estInputTokens, 0, estInputTokens, kind = "rerank", searchUnits = units
                )
            }
            return
        }

        val agent = Agent(
            id = "secondary:${kind.name}:${provider.id}:$model",
            name = agentName, provider = provider, model = model, apiKey = apiKey
        )
        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
        val start = System.currentTimeMillis()
        val secondaryParams = resolveSecondaryParams(
            appViewModel.uiState.value.generalSettings, aiSettings, paramsIds, systemPromptId, metaPrompt
        )
        val response = try {
            appViewModel.repository.analyzeWithAgent(
                agent, "", resolvedPrompt, secondaryParams, null, context, baseUrl
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't translate cancellation into a fake error stored on
            // the placeholder — re-throw so structured concurrency
            // works. (Used to surface as "StandaloneCoroutine was
            // canceled" on the Meta detail screen because the generic
            // catch below swallowed it.)
            throw e
        } catch (e: Exception) {
            AnalysisResponse(provider, null, e.message ?: "Unknown error", agentName = agentName)
        }
        val duration = System.currentTimeMillis() - start
        val pricing = PricingCache.getPricing(context, provider, model)
        val tu = response.tokenUsage
        // Use the same cost model as the report + moderation paths:
        // computeInOutCost honours cached-read / cache-creation rates, the
        // above-200k input tier, and a provider-reported apiCost. The old
        // naive inputTokens*promptPrice ignored all three.
        val (inCost, outCost) = tu?.let { PricingCache.computeInOutCost(it, pricing) } ?: (null to null)

        // For chat-type Meta prompts with reference=true, append the
        // deterministic legend so each [N] in the model's prose has a
        // "[N] = Provider / Model" entry at the bottom regardless of
        // whether the model bothered to include one. Only when the
        // call actually produced content — an error response is left
        // untouched so the failure is visible.
        val finalContent = if (response.error == null
                && !response.analysis.isNullOrBlank() && !referenceLegend.isNullOrBlank()) {
            "${response.analysis.trimEnd()}\n\n---\n\n## References\n\n$referenceLegend\n"
        } else response.analysis
        // A benched-on-this-call >1h 429 is no longer special-cased —
        // it flows through the normal save path below so the row
        // stays as a visible red error carrying the real API error,
        // instead of silently disappearing.
        val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
            content = finalContent,
            errorMessage = response.error,
            tokenUsage = tu,
            inputCost = inCost,
            outputCost = outCost,
            durationMs = duration,
            httpStatusCode = response.httpStatusCode,
            responseChangeSource = null,
            responseChangeValue = null
        ))

        if (saved && response.error == null) {
            val what = when (kind) {
                SecondaryKind.RERANK -> "Rerank"
                SecondaryKind.MODERATION -> "Moderation"
                SecondaryKind.TRANSLATE -> "Translation"
                SecondaryKind.TOURNAMENT -> "Tournament match"
                SecondaryKind.JUDGES -> "Judge match"
                SecondaryKind.COMPARE -> "Compare cell"
                SecondaryKind.TRANSRANK -> "Translator-rank score"
                SecondaryKind.META -> "Meta '${metaPrompt.name}'"
            }
            AuditLog.append(reportId, "$what result produced by ${provider.id}/$model")
        }
        if (saved && response.error == null && tu != null) {
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                provider, model, tu,
                kind = when (kind) {
                    SecondaryKind.RERANK -> "rerank"
                    SecondaryKind.META -> "meta"
                    SecondaryKind.MODERATION -> "moderation"
                    SecondaryKind.TRANSLATE -> "translate"
                    SecondaryKind.TOURNAMENT -> "tournament"
                    SecondaryKind.JUDGES -> "judges"
                    SecondaryKind.COMPARE -> "compare"
                    SecondaryKind.TRANSRANK -> "transrank"
                }
            )
        }
        } finally {
            appViewModel.updateRunningSingleSecondaries { it - placeholder.id }
        }
    }

    fun deleteSecondaryResult(context: Context, reportId: String, resultId: String) {
        // Read the row's cost + kind BEFORE deleting so we can carry
        // the cost into the report's costsFromDeletedItems tally and
        // decide whether to cascade. The user dropped the row from
        // the report; the API spend is real and should still surface
        // on the result page.
        val deleted = SecondaryResultStorage.get(context, reportId, resultId)
        var costDelta = deleted?.fullCost() ?: 0.0
        val deletedSecondaryIds = mutableSetOf(resultId)
        SecondaryResultStorage.delete(context, reportId, resultId)
        // Cascade: when a META row is deleted, its cross-translate
        // TRANSLATE rows (translateSourceKind = "META",
        // translateSourceTargetId = this meta id) become orphans —
        // they'd still surface as language tabs on the View screen but
        // no longer reachable through any meta tile. Drop them too so
        // the on-disk state stays consistent.
        if (deleted?.kind == SecondaryKind.META) {
            val orphans = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.TRANSLATE)
                .filter { it.translateSourceKind == "META" && it.translateSourceTargetId == resultId }
            orphans.forEach { tr ->
                costDelta += (tr.inputCost ?: 0.0) + (tr.outputCost ?: 0.0)
                deletedSecondaryIds += tr.id
                SecondaryResultStorage.delete(context, reportId, tr.id)
            }
        }
        ReportStorage.removeIconCallsForSecondaryIds(context, reportId, deletedSecondaryIds)
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        AuditLog.append(reportId, "Deleted a ${deleted?.kind?.name?.lowercase() ?: "secondary"} result from the report")
        // Bump the parent report's timestamp — removing a meta /
        // translation row is a real change to what the report contains
        // and should sort the report to the top of History, same as an
        // additive change does.
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** Bulk-delete every secondary result row in [resultIds] off the
     *  UI thread on viewModelScope. Survives the screen scope being
     *  cancelled mid-loop — the previous screen-scoped sweep
     *  abandoned hundreds of rows when the user navigated away
     *  during a Fan-out delete. Returns once the sweep finishes. */
    fun bulkDeleteSecondaryResults(context: Context, reportId: String, resultIds: List<String>, onComplete: () -> Unit = {}): Job =
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            // Snapshot the per-id costs + kinds before deleting so we
            // can bump costsFromDeletedItems with the total and
            // cascade-delete META cross-translate orphans.
            var costDelta = 0.0
            val deletedMetaIds = mutableSetOf<String>()
            val deletedSecondaryIds = mutableSetOf<String>()
            resultIds.forEach { id ->
                runCatching {
                    SecondaryResultStorage.get(context, reportId, id)?.let { r ->
                        costDelta += r.fullCost()
                        if (r.kind == SecondaryKind.META) deletedMetaIds.add(id)
                    }
                    deletedSecondaryIds += id
                    SecondaryResultStorage.delete(context, reportId, id)
                }
            }
            // Cascade: every TRANSLATE row pointing back at a
            // just-deleted META row is now an orphan; drop it too.
            if (deletedMetaIds.isNotEmpty()) {
                val orphans = SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.TRANSLATE)
                    .filter { it.translateSourceKind == "META" && it.translateSourceTargetId in deletedMetaIds }
                orphans.forEach { tr ->
                    costDelta += (tr.inputCost ?: 0.0) + (tr.outputCost ?: 0.0)
                    deletedSecondaryIds += tr.id
                    SecondaryResultStorage.delete(context, reportId, tr.id)
                }
            }
            ReportStorage.removeIconCallsForSecondaryIds(context, reportId, deletedSecondaryIds)
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
            withContext(Dispatchers.Main) { onComplete() }
        }
}
