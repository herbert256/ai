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
 *  rerank / moderation, the cartesian fan-out + its resume/rerun/remove
 *  lifecycle, fan-in (+ model fan-in), meta runs, the shared
 *  executeSecondaryTask dispatch, and secondary-result deletion. Shared
 *  job maps (fanOutJobs / fanOutPairJobs / staleResumeScans /
 *  resumingMetaIds) + helpers stay on [rvm] (shared with report gen,
 *  cancellation, and FanOutEngine) and are reached via rvm.* . */
class SecondaryRunManager(
    private val appViewModel: AppViewModel,
    private val rvm: ReportViewModel
) {
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
                withTracerTags(reportId = reportId, category = "Report rerank (local)") {
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
        pick: Pair<AppService, String>,
        /** Honoured only as a single language: rerank is one call against
         *  one set of bodies. AllPresent or a Selected set whose first
         *  non-empty entry is "" means "rank the original bodies"; a
         *  non-empty entry means "rank the translated bodies for that
         *  language". Multi-language Selected just picks the first. */
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent
    ): Job? {
        val (provider, model) = pick
        AppLog.i("Rerank", "→ start report=$reportId via ${provider.id}/$model")
        if (provider.id == AppService.LOCAL.id) {
            return runLocalRerank(context, reportId, model)
        }
        val aiSettings = appViewModel.uiState.value.aiSettings
        val rerankPrompt = aiSettings.getInternalPromptByName("rerank")
            ?: return null
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "Report rerank") {
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
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.RERANK, rerankPrompt,
                        provider, model, resolvedPrompt, aiSettings, report,
                        targetLanguage = sourceLanguage,
                        targetLanguageNative = langCtx?.native
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
        pick: Pair<AppService, String>,
        /** Same single-language semantics as rerank. When set, the
         *  moderation API receives translated bodies (fallback per-
         *  agent to the original) and the persisted row is tagged
         *  with the language so it appears under that section. */
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent
    ): Job? {
        val (provider, model) = pick
        AppLog.i("Moderation", "→ start report=$reportId via ${provider.id}/$model")
        val aiSettings = appViewModel.uiState.value.aiSettings
        // Stub prompt — moderation is a fixed-API call; the
        // InternalPrompt is only used to label the persisted row.
        // If the user has a custom "moderation" prompt configured
        // (legacy), use it; otherwise mint a synthetic one.
        val moderationPrompt = aiSettings.getInternalPromptByName("moderation")
            ?: com.ai.model.InternalPrompt(
                id = "moderation",
                name = "Moderation",
                category = "moderation",
                text = ""
            )
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "Report moderation") {
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
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.MODERATION, moderationPrompt,
                        provider, model, resolvedPrompt = "", aiSettings = aiSettings, report = report,
                        targetLanguage = sourceLanguage,
                        targetLanguageNative = native
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Fan-out Meta runner: for each successful report-model
     *  (the "answerer") and each source model in [scopeChoice], runs
     *  the prompt with `@RESPONSE@` substituted by the source's
     *  response body. Self-pairs are skipped. Always runs on the
     *  Original language; fan out does not fan out to translations.
     *
     *  Persists one [SecondaryResult] per (answerer, source) with
     *  kind=META and fanOutSourceAgentId=source.agentId so the result
     *  drill-in can group by answerer then by source.
     */
    fun runFanOutPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        scopeChoice: SecondaryScope = SecondaryScope.AllReports,
        /** Subset of report-agent ids that should act as "answerers"
         *  (the model receiving the prompt with @RESPONSE@ filled in).
         *  Null = use every successful agent — preserves the pre-feature
         *  default for callers that haven't been updated. The fan-out
         *  confirmation screen passes the user's checked Responder set
         *  so picking 2 responders × 3 initiators yields exactly the
         *  6-minus-self pairs the user expects, instead of always
         *  fanning out every successful agent on the answerer side. */
        responderAgentIds: Set<String>? = null,
        /** English-language name (e.g. "Dutch") to draw the per-pair
         *  source body + prompt from. Null = run on the original
         *  untranslated text — the historical default. When non-null,
         *  each source agent's TRANSLATE row for that language supplies
         *  the body fed into @RESPONSE@; missing translations fall back
         *  to the original body for that one pair. The placeholder is
         *  tagged with targetLanguage so the L1 list groups the run
         *  under the chosen language. Fan-out is single-language by
         *  construction; the scope screen enforces this. */
        sourceLanguage: String? = null
    ): Job? {
        // Dedupe against an already-running fan out for this
        // (report, metaPrompt) — a UI double-tap on the launch
        // button or a second caller via a separate path would
        // otherwise create a parallel batch with its own
        // placeholders, doubling pairs and cost.
        rvm.fanOutJobs[rvm.fanOutJobKey(reportId, metaPrompt.id)]?.let { existing ->
            if (existing.isActive) return existing
        }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val fanOutStartMs = System.currentTimeMillis()
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat, runId = runId) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successful.size < 2) return@withTracerTags
                    AppLog.i("FanOut", "→ start \"${metaPrompt.name}\" (report=$reportId, ${successful.size} successful agents)")
                    val sources = when (scopeChoice) {
                        SecondaryScope.AllReports -> successful
                        is SecondaryScope.TopRanked -> {
                            val rerank = SecondaryResultStorage.get(context, reportId, scopeChoice.rerankResultId)
                            val topIds = extractTopRankedIds(rerank?.content, scopeChoice.count)
                            if (topIds.isNullOrEmpty()) successful
                            else topIds.mapNotNull { idx -> successful.getOrNull(idx - 1) }
                        }
                        is SecondaryScope.Manual -> successful.filter { it.agentId in scopeChoice.agentIds }
                    }
                    if (sources.isEmpty()) return@withTracerTags
                    // Apply the per-side Responder selection (when the
                    // caller passed one). Null falls back to "every
                    // successful agent" — the pre-feature default that
                    // other call sites (e.g. rerunFanOutPlaceholders)
                    // still rely on.
                    val answerers = if (responderAgentIds == null) successful
                        else successful.filter { it.agentId in responderAgentIds }
                    if (answerers.isEmpty()) return@withTracerTags
                    // Translation lookup. Build (translatedBodyByAgent,
                    // translatedPrompt, native) once per run. Missing
                    // per-agent translations fall back to the original
                    // body for that one pair — keeps the run useful
                    // even when the translation set is partial.
                    data class LangCtx(val native: String?, val prompt: String, val bodies: Map<String, String>)
                    val langCtx: LangCtx? = sourceLanguage?.let { lang ->
                        val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                        val translates = allSecondaries.filter {
                            it.kind == SecondaryKind.TRANSLATE &&
                                it.targetLanguage == lang &&
                                !it.content.isNullOrBlank()
                        }
                        val native = translates.firstNotNullOfOrNull { it.targetLanguageNative }
                        val translatedPrompt = translates.firstOrNull {
                            it.translateSourceKind == "PROMPT" && it.translateSourceTargetId == "prompt"
                        }?.content ?: report.prompt
                        val bodies = translates
                            .filter { it.translateSourceKind == "AGENT" && !it.translateSourceTargetId.isNullOrBlank() }
                            .associate { it.translateSourceTargetId!! to (it.content ?: "") }
                        LangCtx(native, translatedPrompt, bodies)
                    }
                    val langSuffix = sourceLanguage?.let { " [$it]" } ?: ""
                    // Pre-create every (answerer, source) placeholder
                    // up-front so the Report Result screen's fan out
                    // summary row and the fan out detail screen's L1/L2
                    // counts read the *full* expected work immediately
                    // (e.g. "30 pairs · 30 pending" for 6×5) and tick
                    // down as calls complete — instead of waking up
                    // with "6 pairs · 6 pending" because only the first
                    // source per answerer had been seeded.
                    data class PendingPair(val answerer: ReportAgent, val source: ReportAgent, val placeholder: SecondaryResult)
                    val pending = mutableListOf<PendingPair>()
                    for (answerer in answerers) {
                        val provider = AppService.findById(answerer.provider) ?: continue
                        for (source in sources) {
                            if (source.agentId == answerer.agentId) continue
                            val agentName = "${provider.id} / ${shortModelName(answerer.model)}$langSuffix"
                            val placeholder = SecondaryResultStorage.create(
                                context, reportId, SecondaryKind.META, provider.id, answerer.model, agentName
                            ) {
                                it.copy(
                                    metaPromptId = metaPrompt.id,
                                    metaPromptName = metaPrompt.name,
                                    fanOutSourceAgentId = source.agentId,
                                    runId = runId,
                                    targetLanguage = sourceLanguage,
                                    targetLanguageNative = langCtx?.native
                                )
                            }
                            pending.add(PendingPair(answerer, source, placeholder))
                        }
                    }
                    // Launch one coroutine per pair. Per-provider
                    // concurrency + per-minute rate are enforced through
                    // ProviderThrottle, but we acquire the permit here
                    // (not inside the OkHttp interceptor) so the UI's
                    // queued / running distinction lines up with the
                    // throttle state:
                    //   - pair enters async, hasn't called acquire yet
                    //     → not in runningFanOutPairs → reads as "queued"
                    //   - acquire returns (permit + per-minute slot held)
                    //     → flip to runningFanOutPairs → reads as "running"
                    // The OkHttp interceptor sees permitPreAcquired=true
                    // on the worker (propagated via TagPropagatingExecutor)
                    // and skips its own acquire — no double-counting.
                    //
                    // Per-host suspending caps below — sized to each
                    // host's ProviderThrottle concurrent limit — replace
                    // the single global Semaphore(8) that earlier hot-
                    // fixed Dispatchers.IO starvation. A global cap
                    // ignored the per-provider setting; with per-host
                    // caps Provider A at concurrent=5 can run 5 pairs
                    // in parallel even while Provider B is at its own
                    // separate cap. The suspending withPermit releases
                    // the IO thread while a pair waits its turn, so
                    // the blocking ProviderThrottle.acquire below
                    // (java.util.concurrent.Semaphore.acquire) returns
                    // immediately for every pair the suspending gate
                    // admitted — no IO threads pinned in waiting.
                    val perHostCaps: Map<String, kotlinx.coroutines.sync.Semaphore> = pending
                        .mapNotNull { AppService.findById(it.answerer.provider) }
                        .map { providerHost(it) }
                        .distinct()
                        .associateWith { host ->
                            val (_, concurrent) = ProviderThrottle.limitsFor(host)
                            kotlinx.coroutines.sync.Semaphore(concurrent)
                        }
                    coroutineScope {
                        // Interleave by host so the pair list doesn't
                        // fire in (a1,s1)(a1,s2)…(a1,sN)(a2,s1)… order —
                        // that has the first N launches all queue on
                        // a1's per-host cap while holding the outer
                        // (global + fan-out) caps idle. Round-robin
                        // across host buckets + jitter within each
                        // bucket spreads the very first slot across
                        // every host the run touches.
                        interleaveByHost(pending) { p ->
                            AppService.findById(p.answerer.provider)?.let { providerHost(it) }
                        }.map { item ->
                            // CoroutineStart.LAZY so the async body
                            // doesn't run until we've registered the
                            // Deferred in rvm.fanOutPairJobs below. Without
                            // LAZY there's a microsecond window between
                            // `async {}` returning and the registration
                            // line where the pair is mid-flight but
                            // can't be looked up by deleteFanOutModel,
                            // letting it slip past cancellation and
                            // either land a result on a row that's
                            // about to be deleted (silent drop) or
                            // worse, burn the API call entirely.
                            val deferred = async(start = CoroutineStart.LAZY) {
                                val provider = AppService.findById(item.answerer.provider) ?: return@async
                                val host = providerHost(provider)
                                val hostCap = perHostCaps[host]
                                    ?: kotlinx.coroutines.sync.Semaphore(1)
                                // Acquire order: per-host cap FIRST, then
                                // outer global + fan-out. A pair waiting on
                                // a saturated per-host cap (e.g. 12 of 15
                                // pairs targeting one host) suspends here
                                // without holding the outer caps — those
                                // stay free for other batches and for
                                // other hosts' pairs (which interleave
                                // ahead via interleaveByHost).
                                if (hostCap.availablePermits == 0)
                                    AppLog.v("Caps", "pair=${item.placeholder.id} WAIT hostCap (host=$host)")
                                hostCap.withPermit {
                                // Non-blocking per-host gate (concurrent +
                                // per-minute window), BEFORE the outer global /
                                // fan-out caps. A capped pair yields the
                                // coroutine (delay, not Thread.sleep) so other
                                // hosts' pairs proceed; the Throttled mark drives
                                // the L1 "Throttled N" counter while it waits.
                                val releaser = acquireOrRequeue(
                                    host,
                                    onThrottled = { appViewModel.updateThrottledFanOutPairs { it + item.placeholder.id } },
                                    onCleared = { appViewModel.updateThrottledFanOutPairs { it - item.placeholder.id } }
                                )
                                try {
                                if (ApiCallCaps.global.availablePermits == 0)
                                    AppLog.v("Caps", "pair=${item.placeholder.id} WAIT global ${ApiCallCaps.snapshot().let { "${it.globalInFlight}/${it.globalMax}" }}")
                                ApiCallCaps.global.withPermit {
                                if (ApiCallCaps.fanOut.availablePermits == 0)
                                    AppLog.v("Caps", "pair=${item.placeholder.id} WAIT fanOut ${ApiCallCaps.snapshot().let { "${it.fanOutInFlight}/${it.fanOutMax}" }}")
                                ApiCallCaps.fanOut.withPermit {
                                AppLog.d("FanOut", "queued pair ans=${item.answerer.agentId} src=${item.source.agentId} ${provider.id}/${item.answerer.model}")
                                    // Bail if the user deleted this pair (via the
                                    // L2 trash icon or deleteFanOutModel) while
                                    // we were queued on the throttle. Without
                                    // this, executeSecondaryTask still fires the
                                    // HTTP call and the saveIfStillPresent check
                                    // below would suppress the disk write — but
                                    // we'd still burn the API call and the
                                    // tokens. Checking here skips the call too.
                                    if (!SecondaryResultStorage.exists(context, reportId, item.placeholder.id)) {
                                        AppLog.d("FanOut", "skip pair ${item.placeholder.id} — deleted before launch")
                                        return@async
                                    }
                                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                        appViewModel.updateRunningFanOutPairs { it + item.placeholder.id }
                                        val pairStart = System.currentTimeMillis()
                                        AppLog.d("FanOut", "→ pair ans=${item.answerer.agentId} src=${item.source.agentId} ${provider.id}/${item.answerer.model}")
                                        try {
                                            val questionForPair = langCtx?.prompt ?: report.prompt
                                            val bodyForPair = langCtx?.bodies?.get(item.source.agentId)
                                                ?: (item.source.responseBody ?: "")
                                            val resolvedBase = resolveSecondaryPrompt(
                                                metaPrompt.text,
                                                question = questionForPair,
                                                results = "",
                                                count = sources.size,
                                                title = report.title
                                            )
                                            val resolved = resolvedBase.replace("@RESPONSE@", bodyForPair)
                                            executeSecondaryTask(
                                                context, reportId, SecondaryKind.META, metaPrompt,
                                                provider, item.answerer.model, resolved, aiSettings, report,
                                                targetLanguage = sourceLanguage,
                                                targetLanguageNative = langCtx?.native,
                                                fanOutSourceAgentId = item.source.agentId,
                                                existingPlaceholder = item.placeholder
                                            )
                                            // Note: the per-pair icon chain is NOT fired
                                            // inline anymore — it lives in a separate
                                            // user-launched batch (runFanIconsBatch).
                                            // The "Find Icons" button on the fan-out L1
                                            // launches it after the parent fan-out
                                            // completes.
                                        } finally {
                                            appViewModel.updateRunningFanOutPairs { it - item.placeholder.id }
                                            AppLog.d("FanOut", "← pair ans=${item.answerer.agentId} src=${item.source.agentId} ${System.currentTimeMillis() - pairStart}ms")
                                        }
                                    }
                                } // ApiCallCaps.fanOut.withPermit
                                } // ApiCallCaps.global.withPermit
                                } finally {
                                    releaser.release()
                                }
                                } // hostCap.withPermit
                            }
                            // Register the per-pair Job so deleteFanOutModel /
                            // rerunCompleteFanOut can target it for cancelAndJoin
                            // before deleting the row, closing the "result lands
                            // after delete and is silently dropped" race.
                            // Registration happens BEFORE start() so a concurrent
                            // delete can always find the Job.
                            rvm.fanOutPairJobs[item.placeholder.id] = deferred
                            deferred.invokeOnCompletion {
                                rvm.fanOutPairJobs.remove(item.placeholder.id, deferred)
                            }
                            deferred.start()
                            deferred
                        }.awaitAll()
                    }
                    AppLog.i("FanOut", "← end \"${metaPrompt.name}\" (${pending.size} pairs in ${System.currentTimeMillis() - fanOutStartMs}ms)")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
        rvm.registerFanOutJob(reportId, metaPrompt.id, job)
        return job
    }

    /** Re-launch a list of fan-out pair placeholders. Re-uses the per-
     *  provider semaphore + running-pair bookkeeping pattern from
     *  [runFanOutPrompt], so the L1 progress bar / stats keep
     *  reflecting "running" once a permit is held and "queued" while a
     *  pair is waiting. The caller has already cleared each
     *  placeholder's content/errorMessage on disk (so the row reads as
     *  pending again). */
    private fun rerunFanOutPlaceholders(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        placeholders: List<SecondaryResult>
    ): Job? {
        if (placeholders.isEmpty()) return null
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val job = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    val sourceCount = successful.size
                    // All placeholders in one rerun batch share a
                    // single language by construction (fan-out is
                    // single-language). Lift the translation lookup
                    // once so we don't re-list secondaries per pair.
                    val rerunLang = placeholders.firstNotNullOfOrNull { it.targetLanguage }
                    data class LangCtx(val native: String?, val prompt: String, val bodies: Map<String, String>)
                    val rerunLangCtx: LangCtx? = rerunLang?.let { lang ->
                        val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                        val translates = allSecondaries.filter {
                            it.kind == SecondaryKind.TRANSLATE &&
                                it.targetLanguage == lang &&
                                !it.content.isNullOrBlank()
                        }
                        val native = translates.firstNotNullOfOrNull { it.targetLanguageNative }
                        val translatedPrompt = translates.firstOrNull {
                            it.translateSourceKind == "PROMPT" && it.translateSourceTargetId == "prompt"
                        }?.content ?: report.prompt
                        val bodies = translates
                            .filter { it.translateSourceKind == "AGENT" && !it.translateSourceTargetId.isNullOrBlank() }
                            .associate { it.translateSourceTargetId!! to (it.content ?: "") }
                        LangCtx(native, translatedPrompt, bodies)
                    }
                    // Per-pair pre-acquire mirrors runFanOutPrompt so the
                    // queued / running flip on the UI lines up with the
                    // permit being held. See that function for the full
                    // explanation. Per-host suspending caps mirror the
                    // ProviderThrottle concurrent limit so each host gets
                    // its own gate (vs a single global cap, which under-
                    // utilised fast providers).
                    val perHostCaps: Map<String, kotlinx.coroutines.sync.Semaphore> = placeholders
                        .mapNotNull { AppService.findById(it.providerId) }
                        .map { providerHost(it) }
                        .distinct()
                        .associateWith { host ->
                            val (_, concurrent) = ProviderThrottle.limitsFor(host)
                            kotlinx.coroutines.sync.Semaphore(concurrent)
                        }
                    coroutineScope {
                        // Interleave by host — same rationale as
                        // runFanOutPrompt: avoid having the first N
                        // launches all queue on one host's per-host
                        // cap while the outer caps sit idle.
                        interleaveByHost(placeholders) { ph ->
                            AppService.findById(ph.providerId)?.let { providerHost(it) }
                        }.map { ph ->
                            // CoroutineStart.LAZY mirrors runFanOutPrompt
                            // — see the comment there for why the start
                            // is gated on the post-registration .start()
                            // call below.
                            val deferred = async(start = CoroutineStart.LAZY) {
                                val provider = AppService.findById(ph.providerId) ?: return@async
                                val source = successful.firstOrNull { it.agentId == ph.fanOutSourceAgentId }
                                    ?: return@async
                                val host = providerHost(provider)
                                val hostCap = perHostCaps[host]
                                    ?: kotlinx.coroutines.sync.Semaphore(1)
                                // Acquire order: per-host cap → non-blocking
                                // ProviderThrottle gate (yields on a capped host
                                // instead of Thread.sleep) → outer global +
                                // fan-out. See runFanOutPrompt for the full
                                // rationale; same Throttled-mark for the UI.
                                hostCap.withPermit {
                                val releaser = acquireOrRequeue(
                                    host,
                                    onThrottled = { appViewModel.updateThrottledFanOutPairs { it + ph.id } },
                                    onCleared = { appViewModel.updateThrottledFanOutPairs { it - ph.id } }
                                )
                                try {
                                ApiCallCaps.global.withPermit {
                                ApiCallCaps.fanOut.withPermit {
                                AppLog.d("FanOut", "queued rerun ph=${ph.id} src=${source.agentId} ${provider.id}/${ph.model}")
                                    if (!SecondaryResultStorage.exists(context, reportId, ph.id)) {
                                        AppLog.d("FanOut", "skip rerun ${ph.id} — deleted before launch")
                                        return@async
                                    }
                                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                        appViewModel.updateRunningFanOutPairs { it + ph.id }
                                        val rerunStart = System.currentTimeMillis()
                                        AppLog.d("FanOut", "→ rerun pair ph=${ph.id} src=${source.agentId} ${provider.id}/${ph.model}")
                                        try {
                                            val questionForPair = rerunLangCtx?.prompt ?: report.prompt
                                            val bodyForPair = rerunLangCtx?.bodies?.get(source.agentId)
                                                ?: (source.responseBody ?: "")
                                            val resolvedBase = resolveSecondaryPrompt(
                                                metaPrompt.text,
                                                question = questionForPair,
                                                results = "",
                                                count = sourceCount,
                                                title = report.title
                                            )
                                            val resolved = resolvedBase.replace("@RESPONSE@", bodyForPair)
                                            executeSecondaryTask(
                                                context, reportId, SecondaryKind.META, metaPrompt,
                                                provider, ph.model, resolved, aiSettings, report,
                                                targetLanguage = ph.targetLanguage,
                                                targetLanguageNative = ph.targetLanguageNative,
                                                fanOutSourceAgentId = source.agentId,
                                                existingPlaceholder = ph
                                            )
                                            // Icon chain no longer auto-fires here —
                                            // the user explicitly launches the
                                            // fan-icons batch via the L1 button.
                                        } finally {
                                            appViewModel.updateRunningFanOutPairs { it - ph.id }
                                            AppLog.d("FanOut", "← rerun pair ph=${ph.id} ${System.currentTimeMillis() - rerunStart}ms")
                                        }
                                    }
                                } // ApiCallCaps.fanOut.withPermit
                                } // ApiCallCaps.global.withPermit
                                } finally {
                                    releaser.release()
                                }
                                } // hostCap.withPermit
                            }
                            // Per-pair Job registration mirrors runFanOutPrompt
                            // — see the comment there for the cancel-on-delete
                            // race the registration closes.
                            rvm.fanOutPairJobs[ph.id] = deferred
                            deferred.invokeOnCompletion {
                                rvm.fanOutPairJobs.remove(ph.id, deferred)
                            }
                            deferred.start()
                            deferred
                        }.awaitAll()
                    }
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
        rvm.registerFanOutJob(reportId, metaPrompt.id, job)
        return job
    }

    /** Reset the given rows on disk so they look queued again (clears
     *  content / errorMessage / token usage / costs / duration), then
     *  feed them into [rerunFanOutPlaceholders]. The cleared rows reuse
     *  their original placeholder ids so the UI doesn't see them
     *  vanish-and-reappear. */
    private fun resetAndRelaunch(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        rows: List<SecondaryResult>
    ): Job? {
        if (rows.isEmpty()) return null
        val reset = rows.map { r ->
            r.copy(
                content = null,
                errorMessage = null,
                tokenUsage = null,
                inputCost = null,
                outputCost = null,
                durationMs = null,
                icon = null,
                iconWinningTier = null,
                iconErrorMessage = null,
                iconInputTokens = 0,
                iconOutputTokens = 0,
                iconInputCost = 0.0,
                iconOutputCost = 0.0
            ).also { SecondaryResultStorage.save(context, it) }
        }
        return rerunFanOutPlaceholders(context, reportId, metaPrompt, reset)
    }

    /** Auto-resume every interrupted Translation, Fan-out, and
     *  single-call Meta/Rerank/Moderation run on the report. Fires
     *  on report open (replaces the previous mark-as-errored sweep).
     *
     *  - **Translation**: groups disk rows by `translationRunId`; for
     *    each runId that isn't already in [_translationRuns], calls
     *    [startMissingTranslations] which dispatches every expected
     *    item (prompt + successful agents + meta secondaries) that
     *    doesn't yet have a row.
     *  - **Fan-out**: groups stale fan-out placeholder rows by
     *    `metaPromptId`; for each one whose [com.ai.model.InternalPrompt]
     *    still exists in settings, calls [resumeStaleFanOutPairs].
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
        val running = appViewModel.runningFanOutPairs.value
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

        // 2. Fan-out pairs: group stale per-pair rows by metaPromptId
        //    and dispatch resumeStaleFanOutPairs once per prompt.
        //    resumeStaleFanOutPairs is itself a no-op when the in-flight
        //    set already covers the run (defence-in-depth dedupe).
        val stalePairsByPromptId = rows
            .filter { it.kind == SecondaryKind.META &&
                it.fanOutSourceAgentId != null &&
                it.content.isNullOrBlank() &&
                it.errorMessage == null &&
                it.durationMs == null &&
                it.id !in running }
            .groupBy { it.metaPromptId }
        stalePairsByPromptId.forEach { (promptId, pairs) ->
            val prompt = promptId?.let { pid ->
                aiSettings.internalPrompts.firstOrNull { it.id == pid }
            }
            if (prompt != null) {
                resumeStaleFanOutPairs(context, reportId, prompt)
            } else {
                // Prompt deleted: mark each as ❌ so the row stops
                // spinning. The user can drop the row and re-pick a
                // prompt from scratch.
                pairs.forEach { markRowAsInterrupted(context, reportId, it.id, "Interrupted — fan-out prompt deleted") }
            }
        }

        // 3. Single-call Meta/Rerank/Moderation: re-issue each stale
        //    placeholder via executeSecondaryTask. Fan-in single
        //    (fanInOf != null) and model-fan-in (scopeProviderId != null)
        //    rows are skipped here — they go to the legacy mark-as-❌
        //    branch below since their substitution inputs aren't
        //    derivable from the placeholder alone.
        val staleSingleMeta = rows.filter {
            it.kind != SecondaryKind.TRANSLATE &&
                it.content.isNullOrBlank() &&
                it.errorMessage == null &&
                it.durationMs == null &&
                it.fanOutSourceAgentId == null &&
                it.fanInOf == null &&
                it.scopeProviderId == null &&
                it.scopeModel == null &&
                it.translationRunId == null &&
                it.id !in running &&
                it.metaPromptId != null &&
                aiSettings.internalPrompts.any { p -> p.id == it.metaPromptId } &&
                AppService.findById(it.providerId) != null
        }
        staleSingleMeta.forEach { row ->
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
        val handledIds = staleSingleMeta.map { it.id }.toSet() +
            stalePairsByPromptId.values.flatten().map { it.id }.toSet()
        rows.forEach { row ->
            if (row.errorMessage != null) return@forEach
            if (!row.content.isNullOrBlank()) return@forEach
            if (row.durationMs != null) return@forEach
            if (row.id in running) return@forEach
            if (row.id in handledIds) return@forEach
            // Translation rows in an active or newly-resumed run are
            // covered by startMissingTranslations; skip them here.
            if (row.kind == SecondaryKind.TRANSLATE &&
                row.translationRunId != null &&
                (row.translationRunId in activeTranslationRunIds ||
                    row.translationRunId in translationRunIds)) return@forEach
            // Anything still standing here is unrecoverable — mark ❌.
            markRowAsInterrupted(context, reportId, row.id, "No data yet")
        }
    }

    /** App-wide background resume sweep. Walks every report whose
     *  timestamp is within the last 7 days and calls
     *  [resumeStaleRunsForReport] on it — catches translation /
     *  fan-out / single-Meta placeholders for reports the user
     *  hasn't opened recently (the per-report on-open trigger
     *  inside [com.ai.ui.report.manage.ReportScreen] misses those).
     *
     *  Idempotent vs already-running work: the called function's
     *  guards (activeTranslationRunIds snapshot, the active-job
     *  check inside [reconcileStalledTranslationRun], the
     *  in-flight dedupe inside [resumeStaleFanOutPairs]) make
     *  this safe to retrigger.
     *
     *  Lifecycle: one loop at a time. The Job is stored on
     *  [AppViewModel.backgroundResumeSweepJob] so a re-creation
     *  of ReportViewModel (Activity config change tearing down
     *  the `remember{}` ReportViewModel inside AppNavHost)
     *  cancels the prior loop before starting the fresh one. */
    fun startBackgroundResumeSweep(context: Context) {
        appViewModel.backgroundResumeSweepJob?.cancel()
        appViewModel.backgroundResumeSweepJob = appViewModel.viewModelScope.launch(
            rvm.reportLogContext("background-resume-sweep")
        ) {
            while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                try {
                    resumeStaleRunsForRecentReports(context)
                } catch (e: Exception) {
                    AppLog.w("BgResumeSweep", "iteration failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }

    /** Walks every report newer than 7 days and triggers the
     *  per-report stale-runs resume. Sequential await — a
     *  parallel fan-out across 100 reports' disk scans would
     *  saturate IO without giving anything meaningful in return,
     *  and [resumeStaleRunsForReport] spawns its own per-runId
     *  launches anyway. */
    private suspend fun resumeStaleRunsForRecentReports(context: Context) {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val recent = withContext(Dispatchers.IO) {
            ReportStorage.getAllReports(context).filter { it.timestamp >= cutoff }
        }
        if (recent.isEmpty()) return
        AppLog.d("BgResumeSweep", "scanning ${recent.size} report${if (recent.size == 1) "" else "s"} (last 7 days)")
        recent.forEach { report ->
            // resumeStaleRunsForReport returns the orchestrating
            // Job; join it so the next iteration's IO doesn't
            // pile on top. The actual per-runId dispatches it
            // spawns are fire-and-forget inside their own
            // viewModelScope launches.
            resumeStaleRunsForReport(context, report.id).join()
        }
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
        val cat = "Report ${kind.name.lowercase()}: ${metaPrompt.name}"

        AppLog.i("Resume", "→ re-issue ${kind.name} \"${metaPrompt.name}\" report=$reportId row=${placeholder.id} via ${provider.id}/$model")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    // Same scope → includeIds resolution as runMetaPrompt.
                    val includeIds: Set<Int>? = when (scope) {
                        com.ai.data.SecondaryScope.AllReports -> null
                        is com.ai.data.SecondaryScope.TopRanked -> {
                            val rerank = SecondaryResultStorage.get(context, reportId, scope.rerankResultId)
                            com.ai.data.extractTopRankedIds(rerank?.content, scope.count)?.toSet()
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

    /** Re-enqueue fan-out pair placeholders that look stuck — the row is
     *  on disk as a pending placeholder (no content, no errorMessage)
     *  but its id is *not* in [AppViewModel.runningFanOutPairs]. This is the
     *  case after the app process is killed mid-run: the placeholders
     *  survive on disk but the launching coroutines are gone. The Fan out
     *  L1 screen calls this on entry. */
    fun resumeStaleFanOutPairs(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job? {
        val key = rvm.fanOutJobKey(reportId, metaPrompt.id)
        // Drop the call if a previous resume scan for the same
        // (report, prompt) is in flight OR has dispatched a rerun
        // that hasn't finished yet. The lifetime of the key extends
        // past the scan body all the way through the dispatched
        // rerun Job — without that, a second resume scan firing
        // milliseconds after the first scan body exits could re-read
        // the same stale placeholders (still blank on disk, the
        // rerun hasn't filled them yet) and dispatch a duplicate
        // rerun, double-billing the user. Both
        // [resumeStaleRunsForReport] (report-open orchestrator) and
        // any direct caller share the same guard.
        if (!rvm.staleResumeScans.add(key)) return null
        val scanJob = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val running = appViewModel.runningFanOutPairs.value
            val stale = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.metaPromptId == metaPrompt.id &&
                        it.fanOutSourceAgentId != null &&
                        it.fanInOf == null &&
                        it.content.isNullOrBlank() &&
                        it.errorMessage == null &&
                        // durationMs is stamped on every successful + errored
                        // save and cleared by resetAndRelaunch. A row with
                        // durationMs set but blank content is a successful
                        // empty-body completion — re-firing it would
                        // duplicate-bill the user (same fix as the L1 stats
                        // classifier in SecondaryResultsScreen).
                        it.durationMs == null &&
                        it.id !in running
                }
            val rerunJob = if (stale.isNotEmpty()) {
                rerunFanOutPlaceholders(context, reportId, metaPrompt, stale)
            } else null
            // Wait for the rerun to fully complete before releasing
            // the dedup key — only at that point can we safely admit
            // a new scan; until then a fresh scan would re-read the
            // same placeholders (still blank) and re-dispatch.
            rerunJob?.join()
        }
        scanJob.invokeOnCompletion { rvm.staleResumeScans.remove(key) }
        return scanJob
    }

    /** Re-run a single fan-out pair row. Resets it on disk (clears
     *  content / errorMessage / token usage / cost / duration so it
     *  reads as queued again) and dispatches via [resetAndRelaunch].
     *  Used by the L3 "Fan out - pair" TitleBar's 🔄 reload icon. */
    fun rerunSingleFanOutPair(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        pair: SecondaryResult
    ): Job? = resetAndRelaunch(context, reportId, metaPrompt, listOf(pair))

    /** Re-run fan-out pair rows that errored. Resets the rows on disk
     *  (clears errorMessage so they read as queued again) and dispatches
     *  via [rerunFanOutPlaceholders]. */
    fun rerunFailedFanOutPairs(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job? {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null
            }
        return resetAndRelaunch(context, reportId, metaPrompt, failed)
    }

    /** Drop every errored fan-out pair row for this metaPromptId
     *  without re-firing. Wired to the L1 Fan out detail screen's
     *  "Remove failed items" button. */
    fun removeFailedFanOutPairs(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null
            }
        if (failed.isEmpty()) return@launch
        val costDelta = failed.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        failed.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
        // Removed rows weren't in runningFanOutPairs (errored is a
        // terminal state), so no need to update that set.
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** L2-scoped "Restart failed items". Re-fires only the errored
     *  pair rows for this metaPromptId where the active (provider,
     *  model) is the answerer — pairs where another model is the
     *  answerer are left alone so a partial failure in one row of L1
     *  doesn't drag in other models' calls. Throttled through the
     *  same [rerunFanOutPlaceholders] semaphore as the original run. */
    fun rerunFailedFanOutPairsForModel(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        providerId: String,
        model: String
    ): Job? {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null &&
                    it.providerId.equals(providerId, ignoreCase = true) &&
                    it.model == model
            }
        if (failed.isEmpty()) return null
        return resetAndRelaunch(context, reportId, metaPrompt, failed)
    }

    /** L2-scoped "Remove failed items". Mirrors
     *  [removeFailedFanOutPairs] but only drops rows where the
     *  active (provider, model) is the answerer. */
    fun removeFailedFanOutPairsForModel(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null &&
                    it.providerId.equals(providerId, ignoreCase = true) &&
                    it.model == model
            }
        if (failed.isEmpty()) return@launch
        val costDelta = failed.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        failed.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** Drop every fan_out pair-row + every fan_in combine-row for this
     *  metaPromptId on this report, then kick a fresh
     *  [runFanOutPrompt]. Used by the Fan out L1 "Rerun the complete
     *  Fan out" button. */
    fun rerunCompleteFanOut(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job? {
        // Cancel + join any in-flight fan out run for this (report,
        // metaPrompt) before deleting placeholders. Without this,
        // surviving coroutines would call SecondaryResultStorage.save
        // on the just-deleted ids, resurrecting zombie rows beside
        // the freshly-created placeholders and double-billing.
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            rvm.fanOutJobs[rvm.fanOutJobKey(reportId, metaPrompt.id)]?.let { existing ->
                existing.cancelAndJoin()
            }
            // Wipe every per-pair fan-out row tied to this metaPromptId
            // so the rerun starts from a clean grid. The previous filter
            // additionally tried to clean up fan_in rows via
            // `fanInOf == metaPrompt.id`, but fanInOf is set
            // to the FAN_IN prompt's id (line ~1326), never the
            // fan out prompt's id, so that clause was always false — dead
            // code disguised as cleanup. Fan-in rows on the same
            // report are left alone here; rerunning the fan out doesn't
            // know which fan-in derivatives came from this fan out
            // (no explicit link is stored), so deleting them all would
            // be over-aggressive. Users wanting a fully clean slate
            // can delete the fan-in rows individually.
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.metaPromptId == metaPrompt.id && it.fanOutSourceAgentId != null
                }
                .forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            runFanOutPrompt(context, reportId, metaPrompt)?.join()
        }
    }

    /** Drop every fan-out pair row for this report's metaPromptId where
     *  the model appears as the answerer (providerId, model match) OR
     *  as the source (the row's fanOutSourceAgentId points at an agent
     *  whose (provider, model) matches). Other Fan out runs on the same
     *  report (different metaPromptId) are untouched.
     *
     *  Returns a Job so the caller (or tests) can await completion of
     *  the cancellation sweep + disk delete. The cancellation phase
     *  cancelAndJoins every per-pair coroutine for the rows we're about
     *  to delete (registered in [rvm.fanOutPairJobs]), so a coroutine that's
     *  mid-HTTP or mid-save can't land a completion via
     *  saveIfStillPresent AFTER the delete (silently dropping a result
     *  the user paid for) or interleave a write with the delete. */
    fun deleteFanOutModel(
        context: Context,
        reportId: String,
        metaPromptId: String,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
        val report = ReportStorage.getReport(context, reportId) ?: return@launch
        // Provider ids are looked up via AppService.findById (case-
        // insensitive); the on-disk values can be either case
        // depending on when the row was written. Compare equalsIgnoreCase
        // so a UI delete with a re-cased id still matches.
        val matchingAgentIds = report.agents
            .filter { it.provider.equals(providerId, ignoreCase = true) && it.model == model }
            .map { it.agentId }
            .toSet()
        val toDelete = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPromptId &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    (
                        (it.providerId.equals(providerId, ignoreCase = true) && it.model == model) ||
                            (it.fanOutSourceAgentId in matchingAgentIds)
                    )
            }
        if (toDelete.isEmpty()) return@launch
        // Cancel + join the per-pair coroutines BEFORE deleting their
        // rows. Each cancelled coroutine either bails inside its
        // ProviderThrottle.acquire suspend point (no HTTP fired) or
        // throws CancellationException out of the HTTP suspend (no
        // saveIfStillPresent call). Either way, no zombie write lands
        // after we delete the row. join() waits for the finally
        // blocks (running-set removal + permit release) to run.
        val toDeleteIds = toDelete.map { it.id }
        toDeleteIds.forEach { id ->
            rvm.fanOutPairJobs[id]?.cancelAndJoin()
        }
        val costDelta = toDelete.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        toDeleteIds.forEach { SecondaryResultStorage.delete(context, reportId, it) }
        // The per-pair finally blocks already removed these ids from
        // runningFanOutPairs during join, but a stale-cache window
        // could leave the UI showing them as "running" until the next
        // refresh tick. Force-prune the set so the trash icon's
        // visual effect is immediate.
        val deletedIds = toDeleteIds.toSet()
        appViewModel.updateRunningFanOutPairs { it - deletedIds }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
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
        pick: Pair<AppService, String>,
        /** English-name source language inherited from the parent
         *  fan-out (null = Original). When non-null, every @-token the
         *  fan-in template substitutes — @QUESTION@, @TITLE@, and the
         *  per-source @REPORT@ body — comes from the matching
         *  translation rows. The persisted combined-report is also
         *  tagged with the language so it groups under that section
         *  in the report list. */
        sourceLanguage: String? = null
    ): Job? {
        AppLog.i("FanIn", "→ start \"${metaPrompt.name}\" report=$reportId via ${pick.first.id}/${pick.second}")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    // Wait for any in-flight fan out runs on this report
                    // to finish before reading their per-pair rows —
                    // otherwise the combine call would build its
                    // payload from an arbitrary partial subset of
                    // responses while the user thinks the report is
                    // "all of them". Each fan out run is keyed by its
                    // own metaPromptId; a snapshot of the active jobs
                    // for THIS report is enough.
                    val fanOutJobsForReport = rvm.fanOutJobs.entries
                        .filter { it.key.startsWith("$reportId|") }
                        .map { it.value }
                    fanOutJobsForReport.forEach { it.join() }
                    val fanOutRows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                        .filter { it.fanOutSourceAgentId != null }
                        // Drop errored rows before bucketing — without
                        // this, the firstOrNull pick below grabs the
                        // OLDEST row (we sort ascending), which is the
                        // failed first attempt. A successful retry
                        // landed afterwards is then ignored. Filtering
                        // up front leaves only valid responses in the
                        // bucket, so the firstOrNull picks the oldest
                        // successful one — closer to the user's
                        // expected "completed run" semantics.
                        .filter { it.errorMessage == null && !it.content.isNullOrBlank() }
                    // Bucket fan out rows by (providerId, model, sourceAgentId).
                    // Two report rows can legitimately share (provider,
                    // model) — e.g. an Agent UUID row and a swarm:provider:model
                    // row pointing at the same model under different
                    // agentIds. Bucketing into a list keeps every
                    // matching row, and the answerer-side resolution
                    // below picks the best-fit row per (other.provider,
                    // other.model, other.agentId, source.agentId).
                    val byPair = LinkedHashMap<String, MutableList<SecondaryResult>>()
                    fanOutRows.sortedBy { it.timestamp }.forEach { r ->
                        val src = r.fanOutSourceAgentId ?: return@forEach
                        byPair.getOrPut("${r.providerId}|${r.model}|$src") { mutableListOf() }.add(r)
                    }
                    val consumed = HashSet<String>()
                    // Translation context for the parent fan-out's
                    // language. Null when sourceLanguage is null
                    // (Original); otherwise carries the translated
                    // prompt/title/native + per-agent translated body
                    // map. Built once per call from the secondaries we
                    // already listed for the fan-out lookup.
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    val langCtx = lookupLanguageTranslations(report, allSecondaries, sourceLanguage)
                    val perReport: List<Pair<String, List<String>>> = successful.map { source ->
                        val fanOutResponses = successful.mapNotNull other@{ other ->
                            if (other.agentId == source.agentId) return@other null
                            // Pick the next un-consumed row for this
                            // (provider, model, source) bucket so two
                            // distinct other-agents sharing (provider,
                            // model) each get their own response.
                            val bucket = byPair["${other.provider}|${other.model}|${source.agentId}"]
                                ?: return@other null
                            val row = bucket.firstOrNull { it.id !in consumed }
                                ?: return@other null
                            consumed += row.id
                            if (row.errorMessage != null) return@other null
                            val c = row.content
                            if (c.isNullOrBlank()) null else c.trim()
                        }
                        // Each @REPORT@ slot: translated body when
                        // available, original otherwise. Without this,
                        // a Dutch fan-in feeds the picked model the
                        // Dutch @RESPONSES@ but English @REPORT@ — the
                        // assistant typically replies in English and
                        // the row ends up mismatched against its tag.
                        val sourceBody = langCtx?.bodiesByAgentId?.get(source.agentId)
                            ?: source.responseBody?.trim().orEmpty()
                        sourceBody to fanOutResponses
                    }
                    if (perReport.all { it.second.isEmpty() }) {
                        val (provider, model) = pick
                        val agentName = "${provider.id} / ${shortModelName(model)}"
                        SecondaryResultStorage.create(
                            context, reportId, SecondaryKind.META, provider.id, model, agentName
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
                    val resolved = resolveFanInPrompt(
                        template = metaPrompt.text,
                        question = langCtx?.prompt ?: report.prompt,
                        count = perReport.size,
                        fanOutCount = (perReport.size - 1).coerceAtLeast(0),
                        perReport = perReport,
                        title = langCtx?.title ?: report.title
                    )
                    val (provider, model) = pick
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.META, metaPrompt,
                        provider, model, resolved, aiSettings, report,
                        targetLanguage = sourceLanguage,
                        targetLanguageNative = langCtx?.native,
                        fanInOf = metaPrompt.id
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Model-scoped variant of [runFanInPrompt]. Combines only the
     *  fan-out entries that involve the specified active model
     *  (provider, modelName) into one combined-report row, instead
     *  of the legacy "total" fan_in that combines every entry on the
     *  report. Driven by the three new categories `initiator`
     *  (active is source), `requester` (active is answerer), and
     *  `model` (both). The resulting row carries scopeProviderId /
     *  scopeModel so the L2 page can filter to its own model's rows.
     *
     *  Math for 10 models with active = A:
     *    initiator — 9 fan-out responses where A is the source.
     *               @RESPONDERS@ holds those bodies; @INITIATOR@ is
     *               A's own report response.
     *    requester — 9 pairs (other_i's report response, A's fan-out
     *               response to other_i). @RESPONDER_PAIRS@ holds them.
     *    model — both blocks populated. */
    fun runModelFanInPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        pick: Pair<AppService, String>,
        activeProviderId: String,
        activeModel: String,
        /** Inherited from the parent fan-out; same semantics as
         *  [runFanInPrompt]. Drives the substituted text for
         *  @QUESTION@, @TITLE@, @INITIATOR@, and the source-body
         *  half of every @RESPONDER_PAIRS@ entry. */
        sourceLanguage: String? = null
    ): Job? {
        AppLog.i("ModelFanIn", "→ start \"${metaPrompt.name}\" report=$reportId active=$activeProviderId/$activeModel via ${pick.first.id}/${pick.second}")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    // Wait for in-flight fan-out runs on this report —
                    // same race-free pattern as runFanInPrompt.
                    val fanOutJobsForReport = rvm.fanOutJobs.entries
                        .filter { it.key.startsWith("$reportId|") }
                        .map { it.value }
                    fanOutJobsForReport.forEach { it.join() }

                    // Resolve the active model's agents on this report.
                    // Swarm with duplicate (provider, model) members can
                    // produce multiple agentIds for the same model — we
                    // count rows from any of them.
                    val activeAgents = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS
                            && it.provider == activeProviderId
                            && it.model == activeModel
                            && !it.responseBody.isNullOrBlank()
                    }
                    val activeAgentIds = activeAgents.map { it.agentId }.toHashSet()
                    // Translation context inherited from the parent
                    // fan-out. When set, @INITIATOR@ and the source-
                    // body half of every @RESPONDER_PAIRS@ entry come
                    // from the per-agent translation rows.
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    val langCtx = lookupLanguageTranslations(report, allSecondaries, sourceLanguage)
                    val initiatorBody = activeAgents.firstOrNull()?.let { agent ->
                        langCtx?.bodiesByAgentId?.get(agent.agentId)
                            ?: agent.responseBody?.trim().orEmpty()
                    }.orEmpty()

                    // Per-pair fan-out rows on this report.
                    val fanOutRows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                        .filter { it.fanOutSourceAgentId != null && it.fanInOf == null }
                        .filter { it.errorMessage == null && !it.content.isNullOrBlank() }
                        .sortedBy { it.timestamp }

                    // The unified fan-in-model template can use both
                    // @RESPONDERS@ and @RESPONDER_PAIRS@; resolve both
                    // unconditionally and let the prompt body opt in
                    // by reference.

                    // @RESPONDERS@: rows where the active model is the
                    // SOURCE (others responded TO active's report). One
                    // row per other answerer; bucket and pick the
                    // freshest non-errored row per (other-provider,
                    // other-model, source-agent) triple.
                    val responders: List<String> = fanOutRows
                        .filter { it.fanOutSourceAgentId in activeAgentIds }
                        .groupBy { "${it.providerId}|${it.model}" }
                        .values
                        .mapNotNull { bucket -> bucket.lastOrNull()?.content?.trim()?.takeIf { it.isNotBlank() } }

                    // @RESPONDER_PAIRS@: rows where the active model is
                    // the ANSWERER (active responded to others). Each
                    // pair = (other's report response, active's fan-out
                    // response). Bucket by source agent.
                    val responderPairs: List<Pair<String, String>> = fanOutRows
                        .filter { it.providerId == activeProviderId && it.model == activeModel }
                        .groupBy { it.fanOutSourceAgentId.orEmpty() }
                        .mapNotNull { (srcAgentId, bucket) ->
                            if (srcAgentId.isBlank()) return@mapNotNull null
                            val source = report.agents.firstOrNull { it.agentId == srcAgentId } ?: return@mapNotNull null
                            val srcBody = langCtx?.bodiesByAgentId?.get(source.agentId)
                                ?: source.responseBody?.trim().orEmpty()
                            val resp = bucket.lastOrNull()?.content?.trim().orEmpty()
                            if (srcBody.isBlank() || resp.isBlank()) null else srcBody to resp
                        }

                    val (provider, model) = pick

                    // Bail with an error placeholder when there's
                    // nothing to combine — same shape as the legacy
                    // fan_in does for empty fan-out states.
                    val nothingToCombine = responders.isEmpty() && responderPairs.isEmpty()
                    if (nothingToCombine) {
                        val agentName = "${provider.id} / ${shortModelName(model)}"
                        SecondaryResultStorage.create(
                            context, reportId, SecondaryKind.META, provider.id, model, agentName
                        ) {
                            it.copy(
                                metaPromptId = metaPrompt.id,
                                metaPromptName = metaPrompt.name,
                                fanInOf = metaPrompt.id,
                                scopeProviderId = activeProviderId,
                                scopeModel = activeModel,
                                errorMessage = "No fan-out responses available for ${activeProviderId} / ${activeModel} — run the fan-out prompt first."
                            )
                        }
                        return@withTracerTags
                    }

                    val resolved = com.ai.data.resolveModelFanInPrompt(
                        template = metaPrompt.text,
                        question = langCtx?.prompt ?: report.prompt,
                        title = langCtx?.title ?: report.title,
                        initiatorBody = initiatorBody,
                        responders = responders,
                        responderPairs = responderPairs
                    )

                    // Pre-create the placeholder so the scope fields
                    // are persisted from the start (executeSecondaryTask
                    // doesn't take scopeProviderId / scopeModel — we
                    // pass the staged row in via existingPlaceholder).
                    // Language tag goes here too so the row groups
                    // under the right language section.
                    val langSuffix = sourceLanguage?.let { " [$it]" } ?: ""
                    val agentName = "${provider.id} / ${shortModelName(model)}$langSuffix"
                    val placeholder = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.META, provider.id, model, agentName
                    ) {
                        it.copy(
                            metaPromptId = metaPrompt.id,
                            metaPromptName = metaPrompt.name,
                            fanInOf = metaPrompt.id,
                            scopeProviderId = activeProviderId,
                            scopeModel = activeModel,
                            targetLanguage = sourceLanguage,
                            targetLanguageNative = langCtx?.native
                        )
                    }

                    executeSecondaryTask(
                        context, reportId, SecondaryKind.META, metaPrompt,
                        provider, model, resolved, aiSettings, report,
                        targetLanguage = sourceLanguage,
                        targetLanguageNative = langCtx?.native,
                        fanInOf = metaPrompt.id,
                        existingPlaceholder = placeholder
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
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
                reportStatus = if (row.errorMessage != null) ReportStatus.ERROR else ReportStatus.SUCCESS,
                responseBody = row.content,
                errorMessage = row.errorMessage,
                tokenUsage = row.tokenUsage,
                cost = ((row.inputCost ?: 0.0) + (row.outputCost ?: 0.0)).takeIf { it > 0.0 },
                durationMs = row.durationMs
            )
        }

        val now = System.currentTimeMillis()
        val srcTitle = source.title.ifBlank { "AI Report" }
        val newTitle = "From: $srcTitle · $activeProviderId / $activeModel"
        val newReport = Report(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = now,
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
        ReportStorage.persistReport(context, newReport)
        newReport.id
    }

    fun runMetaPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        picks: List<Pair<AppService, String>>,
        scopeChoice: SecondaryScope = SecondaryScope.AllReports,
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent
    ): Job? {
        if (picks.isEmpty()) return null
        val kind = SecondaryKind.META
        AppLog.i("Meta", "→ start \"${metaPrompt.name}\" report=$reportId — ${picks.size} pick(s)")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }

        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            // Tag every API call this batch makes with the parent
            // report's id and a Meta-prompt-name category. Without the
            // reportId tag the resulting trace files would land with
            // reportId=null and the report's JSON export wouldn't
            // pull them in. withTracerTags saves and restores both
            // values so this works correctly when nested.
            val cat = "Report meta: ${metaPrompt.name}"
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
                        if (ids.isNullOrEmpty()) null else ids.toSet()
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
                val resolvedPrompt = resolveSecondaryPrompt(
                    metaPrompt.text, question = translatedPrompt, results = resultsBlock,
                    count = successfulCount, title = seedTitle
                )
                // Pre-create placeholders so we know each row's id up
                // front — needed for phase 2's cross-translate items
                // which reference these rows by id.
                val seedPlaceholders: List<SecondaryResult> = picks.map { (provider, model) ->
                    val langSuffix = seedLang.first?.let { " [$it]" } ?: ""
                    val agentName = "${provider.id} / ${shortModelName(model)}$langSuffix"
                    SecondaryResultStorage.create(
                        context, reportId, kind, provider.id, model, agentName
                    ) {
                        it.copy(
                            targetLanguage = seedLang.first,
                            targetLanguageNative = seedLang.second,
                            metaPromptId = metaPrompt.id,
                            metaPromptName = metaPrompt.name,
                            secondaryScope = scopeChoice.encode()
                        )
                    }
                }
                coroutineScope {
                    picks.zip(seedPlaceholders).map { (pick, ph) ->
                        async {
                            ApiCallCaps.global.withPermit {
                                executeSecondaryTask(
                                    context, reportId, kind, metaPrompt,
                                    pick.first, pick.second, resolvedPrompt, aiSettings, report,
                                    seedLang.first, seedLang.second, referenceLegend,
                                    existingPlaceholder = ph,
                                    scopeEncoded = scopeChoice.encode()
                                )
                            }
                        }
                    }.awaitAll()
                }

                // Phase 2: cross-translate the seed METAs to each
                // non-seed language. Re-read each placeholder from disk
                // so we pick up the now-saved content / errorMessage.
                // Errored seed rows are skipped — nothing useful to
                // cross-translate.
                if (nonSeedLanguages.isNotEmpty()) {
                    val completedSeedMetas = seedPlaceholders.mapNotNull { ph ->
                        SecondaryResultStorage.get(context, reportId, ph.id)
                    }.filter { !it.content.isNullOrBlank() && it.errorMessage == null }
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
        scopeEncoded: String? = null
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
                secondaryScope = scopeEncoded
            )
        }

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
            val inCost = tu?.let { it.inputTokens * pricing.promptPrice }
            val outCost = tu?.let { it.outputTokens * pricing.completionPrice }
            val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                tokenUsage = tu,
                inputCost = inCost,
                outputCost = outCost,
                durationMs = r.durationMs
            ))
            // Skip usage-stats too if the row was deleted while in
            // flight — the user dropped this run, so we shouldn't bill
            // the per-provider token counters for it either.
            if (saved && r.errorMessage == null) {
                val inT = tu?.inputTokens ?: 0
                val outT = tu?.outputTokens ?: 0
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, inT, outT, inT + outT, kind = "moderation"
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
            val docs = report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .map { it.responseBody!! }
            val r = com.ai.data.callRerankApi(provider, apiKey, model, report.prompt, docs)
            // Per-query pricing: cost = billedSearchUnits × perQueryPrice.
            // Stored on inputCost so the report cost table renders
            // alongside chat/summarize rows. The provider may omit the
            // billed-units block on an error response — fall back to 1
            // unit per call so cost is at least roughly tracked.
            val pricing = PricingCache.getPricing(context, provider, model)
            val units = r.billedSearchUnits ?: if (r.errorMessage == null) 1 else 0
            val rerankCost = if (units > 0) units * pricing.perQueryPrice else null
            val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                inputCost = rerankCost,
                durationMs = r.durationMs
            ))
            if (saved && r.errorMessage == null) {
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, 0, 0, 0, kind = "rerank", searchUnits = units
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
        val response = try {
            appViewModel.repository.analyzeWithAgent(
                agent, "", resolvedPrompt, AgentParameters(), null, context, baseUrl
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
        val inCost = tu?.let { it.inputTokens * pricing.promptPrice }
        val outCost = tu?.let { it.outputTokens * pricing.completionPrice }

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
            durationMs = duration
        ))

        if (saved && response.error == null && tu != null) {
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                provider, model, tu.inputTokens, tu.outputTokens, tu.totalTokens,
                kind = when (kind) {
                    SecondaryKind.RERANK -> "rerank"
                    SecondaryKind.META -> "meta"
                    SecondaryKind.MODERATION -> "moderation"
                    SecondaryKind.TRANSLATE -> "translate"
                }
            )
        }
    }

    fun deleteSecondaryResult(context: Context, reportId: String, resultId: String) {
        // Read the row's cost + kind BEFORE deleting so we can carry
        // the cost into the report's costsFromDeletedItems tally and
        // decide whether to cascade. The user dropped the row from
        // the report; the API spend is real and should still surface
        // on the result page.
        val deleted = SecondaryResultStorage.get(context, reportId, resultId)
        var costDelta = deleted?.let { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } ?: 0.0
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
                SecondaryResultStorage.delete(context, reportId, tr.id)
            }
        }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
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
    fun bulkDeleteSecondaryResults(context: Context, reportId: String, resultIds: List<String>, onComplete: () -> Unit = {}) {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            // Snapshot the per-id costs + kinds before deleting so we
            // can bump costsFromDeletedItems with the total and
            // cascade-delete META cross-translate orphans.
            var costDelta = 0.0
            val deletedMetaIds = mutableSetOf<String>()
            resultIds.forEach { id ->
                runCatching {
                    SecondaryResultStorage.get(context, reportId, id)?.let { r ->
                        costDelta += (r.inputCost ?: 0.0) + (r.outputCost ?: 0.0)
                        if (r.kind == SecondaryKind.META) deletedMetaIds.add(id)
                    }
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
                    SecondaryResultStorage.delete(context, reportId, tr.id)
                }
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}
