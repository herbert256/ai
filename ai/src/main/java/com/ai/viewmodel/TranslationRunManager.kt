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

/** Translation-run orchestration extracted from [ReportViewModel].
 *  Owns the live translation-run state + jobs and the full run
 *  lifecycle. Back-references [rvm] for a few shared helpers
 *  (e.g. reportLogContext) and [appViewModel] for settings /
 *  storage / coroutine scope. Mirrors the FanOutEngine collaborator. */
class TranslationRunManager(
    private val appViewModel: AppViewModel,
    private val rvm: ReportViewModel
) {
    // ===== Translate =====


    // Multiple concurrent translation runs: each Translate click
    // allocates a fresh runId and runs in parallel with any others
    // already in flight. Map keyed by runId so the UI can render one
    // live row per run and Cancel can target a specific one.
    private val _translationRuns = MutableStateFlow<Map<String, TranslationRunState>>(emptyMap())
    val translationRuns: StateFlow<Map<String, TranslationRunState>> = _translationRuns.asStateFlow()
    internal val translationJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Snapshot the report's translatable items, kick off the runner.
     *  Concurrency capped at 3 — translations are often the slowest
     *  operation in the app and respecting provider rate limits is
     *  more important than maximum throughput. Each translated piece
     *  is persisted as a TRANSLATE [SecondaryResult] on the SOURCE
     *  report, tagged with the language. The viewer / HTML exports
     *  group those rows by language to render Original | Dutch | …
     *  views. Structured-JSON meta results (rerank, moderation) are
     *  skipped. */
    fun startTranslation(
        context: Context,
        sourceReportId: String,
        targetLanguageName: String,
        targetLanguageNative: String,
        models: List<Pair<AppService, String>>
    ): Job {
        if (models.isEmpty()) {
            AppLog.w("Translation", "startTranslation called with empty models — skipping")
            return appViewModel.viewModelScope.launch {}
        }
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val generalSettings = state.generalSettings
            val sourceReport = ReportStorage.getReport(context, sourceReportId) ?: run {
                _translationRuns.update { it - runId }
                return@launch
            }
            val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)

            // Build the work list. Order: title → prompt → agent
            // responses (in success order) → summaries → compares.
            // Reranks and moderation results are skipped (structured
            // JSON, no human-language content to translate). The title
            // is only included when non-blank — a blank title has
            // nothing meaningful to translate.
            val items = mutableListOf<TranslationItem>()
            if (sourceReport.title.isNotBlank()) {
                items += TranslationItem(
                    id = "title",
                    label = "Report title",
                    kind = TranslationKind.TITLE,
                    sourceText = sourceReport.title
                )
            }
            items += TranslationItem(
                id = "prompt",
                label = "Report prompt",
                kind = TranslationKind.PROMPT,
                sourceText = sourceReport.prompt
            )
            sourceReport.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .forEach { agent ->
                    val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                    items += TranslationItem(
                        id = "agent:${agent.agentId}",
                        label = "$provDisplay / ${shortModelName(agent.model)}",
                        kind = TranslationKind.AGENT_RESPONSE,
                        sourceText = agent.responseBody!!,
                        target = agent.agentId
                    )
                }
            // Every chat-type Meta result is a candidate for translation.
            // Label the row by the user-given Meta prompt name so the
            // progress screen / per-call detail show "Compare 1: …" or
            // "Critique 2: …" — driven entirely by the CRUD prompt name,
            // not a hardcoded "Summary" / "Compare".
            secondaries.filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
                .forEachIndexed { idx, s ->
                    val provDisplay = AppService.findById(s.providerId)?.id ?: s.providerId
                    val name = s.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.legacyKindDisplayName(s.kind)
                    items += TranslationItem(
                        id = "meta:${s.id}",
                        label = "$name ${idx + 1}: $provDisplay / ${shortModelName(s.model)}",
                        kind = TranslationKind.META,
                        sourceText = s.content!!,
                        target = s.id
                    )
                }

            // Stamp each item with the SecondaryResultStorage row id it
            // will eventually write to. Persisting an empty placeholder
            // for that id up front records the run's original target
            // list on disk — startMissingTranslations later compares
            // against THIS set rather than recomputing from current
            // report state, so items added to the report AFTER this
            // run completes don't get spuriously translated.
            val itemsWithIds = items.map { it.copy(persistedRowId = java.util.UUID.randomUUID().toString()) }
            itemsWithIds.forEach { item ->
                val (srcKind, srcTargetId) = when (item.kind) {
                    TranslationKind.TITLE -> "TITLE" to "title"
                    TranslationKind.PROMPT -> "PROMPT" to "prompt"
                    TranslationKind.AGENT_RESPONSE -> "AGENT" to (item.target ?: "")
                    TranslationKind.META -> "META" to (item.target ?: "")
                }
                SecondaryResultStorage.save(context, SecondaryResult(
                    id = item.persistedRowId!!,
                    reportId = sourceReportId,
                    kind = SecondaryKind.TRANSLATE,
                    providerId = "",
                    model = "",
                    agentName = "Translate: ${item.label.ifBlank { item.kind.name.lowercase() }}",
                    timestamp = System.currentTimeMillis(),
                    content = null,
                    errorMessage = null,
                    translateSourceKind = srcKind,
                    translateSourceTargetId = srcTargetId,
                    targetLanguage = targetLanguageName,
                    targetLanguageNative = targetLanguageNative,
                    translationRunId = runId,
                    runId = runId,
                ))
            }
            _translationRuns.update { it + (runId to TranslationRunState(
                runId = runId,
                sourceReportId = sourceReportId,
                targetLanguageName = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                items = itemsWithIds,
                // Brand-new runId always has no persisted entry → COST.
                // Kept explicit for symmetry with the resume / persisted
                // reconstruction paths below.
                mode = com.ai.data.TranslationModeStore.get(runId) ?: TranslationMode.COST,
                models = models.distinctBy { (p, m) -> p.id to m }
                    .map { (p, m) -> "${p.id}|$m" }
            )) }
            AppLog.i("Translation", "→ start $targetLanguageName ($targetLanguageNative) for report=$sourceReportId — ${itemsWithIds.size} items via ${models.size} model${if (models.size == 1) "" else "s"}")

            val template = aiSettings.getInternalPromptByName("Translate")?.text.orEmpty()

            // Pre-resolve apiKey / baseUrl / pricing once per
            // distinct (provider, model) so the inner loop doesn't
            // hit PricingCache repeatedly for items that share a
            // model.
            data class ModelCtx(
                val provider: AppService, val model: String,
                val apiKey: String, val baseUrl: String,
                val pricing: PricingCache.ModelPricing
            )
            val ctxByKey: Map<Pair<String, String>, ModelCtx> = models
                .distinct()
                .associate { (p, m) ->
                    (p.id to m) to ModelCtx(
                        provider = p, model = m,
                        apiKey = aiSettings.getApiKey(p),
                        baseUrl = aiSettings.getEffectiveEndpointUrl(p),
                        pricing = PricingCache.getPricing(context, p, m)
                    )
                }

            // Per-host suspending caps — mirrors runFanOutPrompt /
            // rerunFanOutPlaceholders. Each provider host gets its
            // own concurrent gate sized from ProviderThrottle so a
            // multi-model run on (OpenAI + Anthropic + Google)
            // doesn't bottleneck one host on the other's rate
            // limit.
            val perHostCaps: Map<String, kotlinx.coroutines.sync.Semaphore> = models
                .map { (p, _) -> providerHost(p) }
                .distinct()
                .associateWith { host ->
                    val (_, concurrent) = ProviderThrottle.limitsFor(host)
                    kotlinx.coroutines.sync.Semaphore(concurrent)
                }

            // Shared work queue. Items go into one channel; each
            // distinct picked model gets a worker that pulls the next
            // item, translates it, and pulls again — so a fast model
            // keeps grabbing work instead of idling on a fixed slice.
            // The channel is NOT closed up front: a failed attempt
            // re-queues its item, and `remaining` (only decremented on
            // a terminal outcome) closes the channel when the last
            // item settles. A requeued item is non-terminal so it's
            // still counted — `remaining` can't hit 0 while one is in
            // flight, so the requeue `trySend` always lands.
            val itemQueue = Channel<TranslationItem>(Channel.UNLIMITED)
            itemsWithIds.forEach { itemQueue.trySend(it) }
            val remaining = java.util.concurrent.atomic.AtomicInteger(itemsWithIds.size)
            val attempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
            val triedBy = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Pair<String, String>>>()
            val distinctModels = models.distinctBy { (p, m) -> p.id to m }
            val distinctModelCount = distinctModels.size
            // Skip the OkHttp 429 / 529 retry loops when the swarm has
            // > 3 models — the cross-model requeue below is a faster
            // recovery than a 1-3 s sleep on the same model. With ≤ 3
            // alternatives we keep the inline retry: fewer models to
            // bounce between, more value in waiting for the same one
            // to recover. The fast bench check (long Retry-After) still
            // runs either way, so a >1h rate limit still parks the
            // model on ModelCooldownStore. Set via the existing
            // ProviderThrottle.suppressInlineRetry ThreadLocal (same
            // knob the bulk health-sweep uses).
            val suppressInlineRetryOn429And529 = distinctModelCount > 3
            val callContext: kotlin.coroutines.CoroutineContext =
                if (suppressInlineRetryOn429And529)
                    ProviderThrottle.permitPreAcquired.asContextElement(true) +
                        ProviderThrottle.suppressInlineRetry.asContextElement(true)
                else
                    ProviderThrottle.permitPreAcquired.asContextElement(true)
            // Cost-aware biasing. Each worker records (count, totalCost)
            // for its own model after every successful call (single
            // writer per key — only that model's worker touches it).
            // Before pulling the next item, a worker hesitates for a
            // delay proportional to how much pricier its model is than
            // the cheapest one observed so far — so cheap models drain
            // the queue first and an expensive model (e.g. Perplexity
            // sonar, with its per-request search fee) only picks up
            // work the cheap models can't keep up with. The cheapest
            // model always has 0 penalty, so the run never stalls.
            //
            // Seeded from the static price table (a synthetic 2-sample
            // prior on a nominal 200-in / 200-out token profile) so the
            // bias is right from the very first item — otherwise, with
            // many models, the expensive ones grab a cold-start chunk
            // before real per-item costs are learned. Real completions
            // accumulate on top and drift the average to reality (which
            // also corrects per-request-fee models the static table
            // under-prices).
            val modelCost = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Pair<Int, Double>>()
            distinctModels.forEach { (p, m) ->
                val pr = ctxByKey[p.id to m]?.pricing ?: return@forEach
                val estPerItem = 200.0 * pr.promptPrice + 200.0 * pr.completionPrice + pr.perQueryPrice
                if (estPerItem > 0.0) modelCost[p.id to m] = 2 to (2.0 * estPerItem)
            }
            // Per-attempt wall-clock budget. The work queue rebalances
            // *queued* items but can't steal one already in-flight in a
            // slow worker — so a pathologically slow model (or one
            // hitting the 120s socket timeout, then the dispatch
            // layer's internal retry → ~240s) would hold an item
            // hostage and strand the tail at "960/962" for minutes.
            // Capping the call here turns that into a Failed attempt:
            // the item is requeued and a faster model picks it up.
            val callBudgetMs = 90_000L

            // Tag every translation call's trace with the SOURCE report
            // id — translations live on that report now, no separate
            // translated copy to keep traces with.
            withTracerTags(reportId = sourceReportId, category = "Translation", runId = runId) {
                coroutineScope {
                    distinctModels.map { (p, m) ->
                        val ctx = ctxByKey[p.id to m]
                            ?: error("missing ctx for ${p.id}/$m")
                        launch {
                            val modelKey = ctx.provider.id to ctx.model
                            // Hesitation before pulling the next item,
                            // proportional to how much pricier this
                            // model's observed per-item cost is than the
                            // cheapest model's: (ratio - 1) × 100ms, up
                            // to 120s. The cheapest model never waits, so
                            // the queue always drains; a pathological
                            // outlier (a frontier model or Perplexity
                            // sonar, hundreds× the cheapest here) pulls
                            // only every minute or two and ends up doing
                            // a handful of items instead of dozens.
                            // Needs ≥2 samples so one freak-sized item
                            // can't lock a model out — the modelCost
                            // seed above already supplies a 2-sample
                            // prior, so the bias is live from item 1.
                            fun costPenaltyMs(): Long {
                                // Re-read the user-chosen mode on every
                                // call so the L1 toggle takes effect on
                                // the very next pull. setTranslationMode
                                // writes; we read.
                                val curMode = _translationRuns.value[runId]?.mode ?: TranslationMode.COST
                                if (curMode == TranslationMode.SPEED) return 0L
                                val mine = modelCost[modelKey] ?: return 0L
                                if (mine.first < 2) return 0L
                                val myAvg = mine.second / mine.first
                                if (myAvg <= 0.0) return 0L
                                val cheapest = modelCost.values
                                    .filter { it.first >= 2 }
                                    .minOfOrNull { it.second / it.first } ?: return 0L
                                if (cheapest <= 0.0) return 0L
                                // COST: full 100ms-per-ratio penalty, 120s cap.
                                // MIXED: 20ms-per-ratio, 5s cap — still
                                // favours cheap models but a 100× outlier
                                // hesitates 2 s instead of 10 s and an
                                // extreme outlier 5 s instead of 2 minutes.
                                val (mult, cap) = when (curMode) {
                                    TranslationMode.COST -> 100.0 to 120_000.0
                                    TranslationMode.MIXED -> 20.0 to 5_000.0
                                    TranslationMode.SPEED -> return 0L  // handled above
                                }
                                return ((myAvg / cheapest - 1.0) * mult)
                                    .coerceIn(0.0, cap).toLong()
                            }
                            // Wait out the penalty in 1s chunks so the
                            // worker still notices the run finishing
                            // (channel closed) promptly instead of
                            // sitting out a long hesitation. The
                            // penalty is captured once at entry so the
                            // loop is finite — re-reading every chunk
                            // would loop forever for an expensive
                            // model whose ratio never drops. The mid-
                            // chunk re-check below catches a user
                            // flipping to a less-aggressive mode and
                            // exits early when the new (smaller)
                            // penalty has already been satisfied.
                            suspend fun costHesitate() {
                                val penalty = costPenaltyMs()
                                var waited = 0L
                                while (waited < penalty && remaining.get() > 0) {
                                    val chunk = minOf(1_000L, penalty - waited)
                                    delay(chunk)
                                    waited += chunk
                                    if (waited >= costPenaltyMs()) return
                                }
                            }
                            // A benched model stops pulling — its share
                            // flows to the healthy workers instead.
                            while (!rvm.isBenched(ctx.provider, ctx.model)) {
                                costHesitate()
                                val item = itemQueue.receiveCatching().getOrNull() ?: break
                                val tried = triedBy.computeIfAbsent(item.id) {
                                    java.util.concurrent.ConcurrentHashMap.newKeySet()
                                }
                                // Prefer handing a previously-failed item
                                // to a model that hasn't tried it yet.
                                // Requeue + a short delay bounds the
                                // busy-wait while other workers are
                                // mid-call.
                                if (modelKey in tried && tried.size < distinctModelCount) {
                                    itemQueue.trySend(item)
                                    delay(100)
                                    continue
                                }
                                tried += modelKey
                                val host = providerHost(ctx.provider)
                                val cap = perHostCaps[host]
                                    ?: kotlinx.coroutines.sync.Semaphore(1)
                                // Acquire order: per-host first, then outer
                                // global + translation. An item waiting on a
                                // saturated per-host cap suspends without
                                // holding the outer caps idle.
                                val outcome = cap.withPermit {
                                    // Non-blocking ProviderThrottle gate — a capped
                                    // host yields the coroutine (delay, not
                                    // Thread.sleep) so other items proceed; the
                                    // OkHttp interceptor skips its own acquire via
                                    // permitPreAcquired. callContext also pins the
                                    // 429/529 suppress flag when the swarm is
                                    // big enough that cross-model requeue beats
                                    // in-place retry.
                                    val releaser = acquireOrRequeue(host)
                                    try {
                                        ApiCallCaps.global.withPermit {
                                            ApiCallCaps.translation.withPermit {
                                                withContext(callContext) {
                                                    // withTimeoutOrNull cancels just this
                                                    // call on budget overrun (the HTTP call
                                                    // gets cancelled with it); the worker
                                                    // then requeues the item for a faster
                                                    // model. A null result == timed out.
                                                    kotlinx.coroutines.withTimeoutOrNull(callBudgetMs) {
                                                        runOneTranslation(
                                                            runId, context, ctx.provider, ctx.apiKey,
                                                            ctx.model, ctx.baseUrl, template,
                                                            targetLanguageName, item, ctx.pricing
                                                        )
                                                    } ?: run {
                                                        AppLog.w("Translation", "item ${item.id} timed out on ${ctx.provider.id}/${ctx.model} after ${callBudgetMs / 1000}s — reassigning")
                                                        TranslationOutcome.Failed("${ctx.provider.id}/${ctx.model} timed out after ${callBudgetMs / 1000}s")
                                                    }
                                                }
                                            }
                                        }
                                    } finally {
                                        releaser.release()
                                    }
                                }
                                when (outcome) {
                                    is TranslationOutcome.Success -> {
                                        // Record the spend so costPenaltyMs
                                        // can bias future items toward
                                        // cheaper models.
                                        val cur = modelCost[modelKey]
                                        modelCost[modelKey] =
                                            ((cur?.first ?: 0) + 1) to ((cur?.second ?: 0.0) + outcome.costDollars)
                                        if (remaining.decrementAndGet() == 0) itemQueue.close()
                                    }
                                    is TranslationOutcome.Failed -> {
                                        // Up to 3 attempts across models;
                                        // only the 3rd failure is terminal.
                                        val n = attempts.merge(item.id, 1, Int::plus)!!
                                        if (n >= 3) {
                                            finalizeTranslationError(
                                                context, runId, item,
                                                ctx.provider, ctx.model, ctx.pricing,
                                                outcome.message
                                            )
                                            if (remaining.decrementAndGet() == 0) itemQueue.close()
                                        } else {
                                            // Back to PENDING + requeue for
                                            // another model. Clear the model
                                            // attribution — a requeued item is
                                            // unassigned again, so the run
                                            // screen counts it under "Queue",
                                            // not the model that just failed it.
                                            _translationRuns.update { runs ->
                                                val cur = runs[runId] ?: return@update runs
                                                runs + (runId to cur.copy(items = cur.items.map {
                                                    if (it.id == item.id) it.copy(
                                                        status = TranslationStatus.PENDING,
                                                        providerId = null,
                                                        model = null
                                                    ) else it
                                                }))
                                            }
                                            itemQueue.trySend(item)
                                        }
                                    }
                                }
                            }
                        }
                    }.joinAll()
                }

                // Degenerate case: every picked model is benched, so
                // every worker breaks on the `isBenched` guard with
                // items still non-terminal. Finalize whatever is left
                // so nothing leaks as PENDING/RUNNING forever.
                val leftover = _translationRuns.value[runId]?.items.orEmpty()
                    .filter { it.status != TranslationStatus.DONE && it.status != TranslationStatus.ERROR }
                if (leftover.isNotEmpty()) {
                    val (p, m) = models.first()
                    val ctx = ctxByKey[p.id to m]!!
                    leftover.forEach {
                        finalizeTranslationError(
                            context, runId, it, ctx.provider, ctx.model, ctx.pricing,
                            "no available model — all picked models are rate-limited"
                        )
                    }
                }

                // Per-item rows were already persisted inside
                // runOneTranslation as each call settled, so the
                // batch survives a redeploy / OS kill mid-run. Just
                // bump the parent report's timestamp once at the end
                // so the History list resorts. Skipped on cancel —
                // the cancelled item stays unpersisted and the
                // timestamp bump is moot.
                val finalState = _translationRuns.value[runId] ?: return@withTracerTags
                if (finalState.cancelled) {
                    AppLog.i("Translation", "← cancelled $targetLanguageName for report=$sourceReportId")
                    return@withTracerTags
                }
                ReportStorage.bumpReportTimestamp(context, sourceReportId)
                _translationRuns.update { runs ->
                    val cur = runs[runId] ?: return@update runs
                    runs + (runId to cur.copy(finished = true))
                }
                val okCount = finalState.items.count { it.translatedText?.isNotBlank() == true }
                val failCount = finalState.items.count { it.errorMessage != null }
                AppLog.i("Translation", "← done $targetLanguageName for report=$sourceReportId — ok=$okCount fail=$failCount")
            }
        }
        translationJobs[runId] = job
        job.invokeOnCompletion { translationJobs.remove(runId) }
        return job
    }

    /** Result of a single translation call. A [Failed] is non-terminal
     *  at the [runOneTranslation] level — the caller decides whether to
     *  retry the item on another model or finalize it as ERROR. */
    private sealed interface TranslationOutcome {
        /** [costDollars] is the call's billed cost — the work queue
         *  feeds it into a per-model running average to bias future
         *  items toward cheaper models. */
        data class Success(val costDollars: Double) : TranslationOutcome
        data class Failed(val message: String) : TranslationOutcome
    }

    /** Mark a translation item ERROR and persist its TRANSLATE row.
     *  This is the terminal failure path — used after the retry budget
     *  is exhausted (3 attempts) or when no model is available at all.
     *  Pulled out of [runOneTranslation] so the retrying caller, not
     *  the call itself, owns the decision to give up on an item. */
    private fun finalizeTranslationError(
        context: Context,
        runId: String,
        item: TranslationItem,
        provider: AppService,
        model: String,
        pricing: PricingCache.ModelPricing,
        message: String
    ) {
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(items = cur.items.map {
                if (it.id == item.id) it.copy(
                    status = TranslationStatus.ERROR,
                    errorMessage = message,
                    providerId = provider.id,
                    model = model
                ) else it
            }))
        }
        val freshRun = _translationRuns.value[runId]
        val freshItem = freshRun?.items?.firstOrNull { it.id == item.id }
        if (freshRun != null && freshItem != null) {
            saveOneTranslationItem(context, runId, freshRun, freshItem, provider, model, pricing)
        }
    }

    private suspend fun runOneTranslation(
        runId: String,
        context: Context,
        provider: AppService,
        apiKey: String,
        model: String,
        baseUrl: String,
        template: String,
        targetLanguageName: String,
        item: TranslationItem,
        pricing: PricingCache.ModelPricing
    ): TranslationOutcome {
        // Model benched on a >1h 429 by an earlier call — report it as
        // a failed attempt without touching state or persisting. The
        // caller retries the item on another model and only finalizes
        // ERROR once the retry budget is spent.
        if (rvm.isBenched(provider, model)) {
            AppLog.w("Translation", "skip benched ${provider.id}/$model — item ${item.id} failed attempt")
            return TranslationOutcome.Failed("${provider.id}/$model is rate-limited (benched) — skipped")
        }
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(items = cur.items.map {
                if (it.id == item.id) it.copy(
                    status = TranslationStatus.RUNNING,
                    providerId = provider.id,
                    model = model
                ) else it
            }))
        }
        // Belt-and-braces against a coroutine cancellation between
        // the RUNNING-state set above and the terminal DONE/Failed
        // update below — a cancelled `runOneTranslation` returns no
        // outcome to its caller (no `finalizeTranslationError` runs),
        // so without this catch the item is stranded as RUNNING in
        // the in-memory run state forever (disk is unaffected). Demote
        // back to PENDING and rethrow; a subsequent retry/reload will
        // pick it up cleanly.
        try {
            AppLog.d("Translation", "→ item ${item.id} \"${item.label}\" kind=${item.kind} srcLen=${item.sourceText.length}")
            val resolved = template
                .replace("@LANGUAGE@", targetLanguageName)
                .replace("@TEXT@", item.sourceText)
            val agent = Agent(
                id = "translate:${provider.id}:$model",
                name = "Translate / ${provider.id} / $model",
                provider = provider, model = model, apiKey = apiKey
            )
            val callStart = System.currentTimeMillis()
            // Capture the trace filename so the View → Prompt screen
            // can wire a 🐞 directly to this exact translation call.
            val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val response = try {
                withTraceFilenameSink(traceSink) {
                    appViewModel.repository.analyzeWithAgent(
                        agent, "", resolved, AgentParameters(), null, context, baseUrl
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AnalysisResponse(provider, null, e.message ?: "Unknown error", agentName = agent.name)
            }
            val callDurationMs = System.currentTimeMillis() - callStart
            val capturedTraceFile = traceSink.get()
            val tu = response.tokenUsage
            val costDollars = if (tu != null) PricingCache.computeCost(tu, pricing) else 0.0
            // A failed call (incl. a benched-on-this-call >1h 429) is
            // non-terminal here — the caller retries the item on another
            // model, up to 3 attempts, and only then finalizes ERROR via
            // finalizeTranslationError. So a failure leaves _translationRuns
            // untouched (the item stays RUNNING) and persists nothing.
            if (!response.isSuccess) {
                AppLog.d(
                    "Translation",
                    "← item ${item.id} err ${callDurationMs}ms — ${response.error ?: "Empty response"}"
                )
                return TranslationOutcome.Failed(response.error ?: "Empty response")
            }
            _translationRuns.update { runs ->
                val cur = runs[runId] ?: return@update runs
                val updated = cur.items.map {
                    if (it.id != item.id) it
                    else it.copy(
                        status = TranslationStatus.DONE,
                        translatedText = response.analysis,
                        costDollars = costDollars,
                        tokenUsage = tu,
                        durationMs = callDurationMs,
                        providerId = provider.id,
                        model = model,
                        traceFile = capturedTraceFile
                    )
                }
                runs + (runId to cur.copy(items = updated, totalCostDollars = updated.sumOf { it.costDollars }))
            }
            // Persist this item as soon as the call settles so the row
            // survives a process kill mid-batch. Previously the whole
            // batch was held in-memory and only flushed to disk via
            // saveTranslationSecondaries after awaitAll(); a redeploy
            // or an OS kill halfway through silently lost everything.
            // Use the in-memory runId as the on-disk translationRunId so
            // every item this batch writes groups under the same run on
            // the result screen and survives restart with the same
            // identity.
            val freshRun = _translationRuns.value[runId]
            val freshItem = freshRun?.items?.firstOrNull { it.id == item.id }
            if (freshRun != null && freshItem != null) {
                saveOneTranslationItem(context, runId, freshRun, freshItem, provider, model, pricing)
            }
            // Roll the translation call into per-(provider, model) usage so
            // it shows up on the AI Usage screen alongside report / chat
            // calls.
            if (tu != null) {
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, tu.inputTokens, tu.outputTokens, tu.totalTokens,
                    kind = "translate"
                )
            }
            AppLog.d(
                "Translation",
                "← item ${item.id} ok ${callDurationMs}ms" +
                    (tu?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") +
                    " cost=${"%.5f".format(costDollars)}"
            )
            return TranslationOutcome.Success(costDollars)
        } catch (t: Throwable) {
            _translationRuns.update { runs ->
                val cur = runs[runId] ?: return@update runs
                runs + (runId to cur.copy(items = cur.items.map {
                    if (it.id == item.id && it.status == TranslationStatus.RUNNING) it.copy(
                        status = TranslationStatus.PENDING,
                        providerId = null,
                        model = null
                    ) else it
                }))
            }
            throw t
        }
    }

    /** Persist one TRANSLATE [SecondaryResult] for a single completed
     *  (or errored) translation item. Replaces the all-at-once flush
     *  that used to happen at the end of [startTranslation] — moving
     *  the save inline lets a half-finished batch survive process
     *  death with the rows that did complete still on disk. */
    private fun saveOneTranslationItem(
        context: Context,
        runId: String,
        run: TranslationRunState,
        item: TranslationItem,
        translateProvider: AppService,
        translateModel: String,
        translatePricing: PricingCache.ModelPricing
    ) {
        val tu = item.tokenUsage
        // Tier-aware split — runOneTranslation already computes the
        // total via PricingCache.computeCost. The persisted in / out
        // halves now go through computeInOutCost so a long-context
        // translation (>200k tokens) doesn't drift from the canonical
        // total recorded in the parent run.
        val (inCost, outCost) = tu?.let { PricingCache.computeInOutCost(it, translatePricing) }
            ?.let { it.first to it.second } ?: (null to null)
        val labelPrefix = "Translate: ${item.label.ifBlank { item.kind.name.lowercase() }}"
        val (srcKind, srcTargetId) = when (item.kind) {
            TranslationKind.TITLE -> "TITLE" to "title"
            TranslationKind.PROMPT -> "PROMPT" to "prompt"
            TranslationKind.AGENT_RESPONSE -> "AGENT" to (item.target ?: "")
            TranslationKind.META -> "META" to (item.target ?: "")
        }
        SecondaryResultStorage.save(context, SecondaryResult(
            // Reuse the placeholder's id (stashed at startTranslation /
            // restart time) so this save OVERWRITES the placeholder
            // row instead of creating a parallel record. That keeps
            // exactly one row per (runId, kind, targetId) so the
            // auto-resume can tell "originally targeted" from "newly
            // added to the report later".
            id = item.persistedRowId ?: java.util.UUID.randomUUID().toString(),
            reportId = run.sourceReportId,
            kind = SecondaryKind.TRANSLATE,
            providerId = translateProvider.id,
            model = translateModel,
            agentName = labelPrefix,
            timestamp = System.currentTimeMillis(),
            content = item.translatedText,
            errorMessage = item.errorMessage,
            tokenUsage = tu,
            inputCost = inCost,
            outputCost = outCost,
            durationMs = item.durationMs,
            translateSourceKind = srcKind,
            translateSourceTargetId = srcTargetId,
            targetLanguage = run.targetLanguageName,
            targetLanguageNative = run.targetLanguageNative,
            translationRunId = runId,
            runId = runId,
            traceFile = item.traceFile
        ))
    }

    // saveTranslationSecondaries (the bulk all-at-end flush) was
    // replaced by saveOneTranslationItem, called inline as each
    // translation call settles — so a half-finished batch persists
    // the rows it did complete instead of losing everything on a
    // redeploy / OS kill.

    fun cancelTranslation(runId: String) {
        translationJobs[runId]?.cancel()
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(cancelled = true))
        }
    }

    /** Reconcile a stalled translation run by dropping the stale
     *  in-memory state and re-seeding it from the persisted disk
     *  rows.
     *
     *  Why it exists: certain failure modes (an `addCrossTranslationItems`
     *  / `startMissingTranslations` coroutine cancelled mid-dispatch)
     *  leave `_translationRuns[runId]` with `finished = false`,
     *  `completed == total` in its items list, but on-disk rows that
     *  never got their `saveOneTranslationItem` update — so the
     *  manage screen's animated hourglass keeps spinning over a row
     *  whose Done count already matches Total. The fix runs the
     *  same disk-rebuild the L1 screen falls back to when liveRun is
     *  null ([buildPersistedTranslationRunState]): it produces a
     *  state whose `items` cover every persisted row (DONE / ERROR /
     *  placeholder-as-PENDING) and whose `finished` flag is true so
     *  the hourglass clears.
     *
     *  Guard: skips when a dispatch job is currently alive for the
     *  same runId. Reconciling on top of an active worker would race
     *  the worker's pending `saveOneTranslationItem` write — and
     *  there's nothing stalled if a worker is still moving items
     *  toward terminal anyway. */
    fun reconcileStalledTranslationRun(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        if (translationJobs[runId]?.isActive == true) {
            AppLog.d("Translation", "reconcile skipped — runId=$runId has active dispatch job")
            return@launch
        }
        AppLog.i("Translation", "reconciling stalled translation runId=$runId — rebuilding in-memory state from disk")
        val translateRows = withContext(Dispatchers.IO) {
            SecondaryResultStorage
                .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
                .filter { com.ai.ui.helpers.translationRunGroupingId(it) == runId }
        }
        if (translateRows.isEmpty()) {
            // No rows on disk for this runId — the run is gone. Drop
            // the stale in-memory entry so the hourglass stops.
            _translationRuns.update { it - runId }
            return@launch
        }
        val hasPlaceholders = translateRows.any {
            it.content.isNullOrBlank() && it.errorMessage == null && it.durationMs == null
        }
        if (hasPlaceholders) {
            // Drop the stale in-memory entry so startMissingTranslations'
            // null-cur seed path re-builds from disk (includes the
            // 14 errors + 28 placeholder PENDING items in the
            // canonical Portuguese-style scenario). The dispatch
            // then flips finished=true at the end so the hourglass
            // clears once the placeholders settle.
            AppLog.i("Translation", "reconcile runId=$runId — placeholders present, re-dispatching via startMissingTranslations")
            _translationRuns.update { it - runId }
            startMissingTranslations(context, sourceReportId, runId)
        } else {
            // No placeholders on disk — nothing to dispatch. Just
            // rebuild with finished=true so the manage hourglass
            // clears immediately.
            val rebuilt = buildPersistedTranslationRunState(context, sourceReportId, runId)
                ?: return@launch
            _translationRuns.update { it + (runId to rebuilt) }
        }
    }

    /** Flip the cost-vs-speed mode on a (possibly in-flight) run.
     *  Persists to [com.ai.data.TranslationModeStore] so the choice
     *  survives a process kill / app restart, and updates the
     *  in-memory [_translationRuns] so the worker's per-pull mode
     *  read (in [startTranslation]'s `costPenaltyMs`) picks up the
     *  new value on the next item. In-flight calls keep running on
     *  whichever model they're already on. */
    fun setTranslationMode(runId: String, mode: TranslationMode) {
        com.ai.data.TranslationModeStore.set(runId, mode)
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            if (cur.mode == mode) return@update runs
            runs + (runId to cur.copy(mode = mode))
        }
    }

    fun consumeTranslationRun(runId: String) {
        _translationRuns.update { it - runId }
    }

    /** Delete a whole translation run. Cancels the in-flight job and
     *  **joins** it before touching disk — `cancel()` alone is
     *  fire-and-forget, so the old per-row delete raced the runner:
     *  in-flight per-item coroutines wrote fresh rows *after* the
     *  delete pass, leaving zombie rows behind and the run's summary
     *  row still on the report page. Joining first guarantees the
     *  runner is fully dead; then we list the run's TRANSLATE rows
     *  from disk (catching any that landed during the cancel) and
     *  delete every one. Returns the Job so the caller can await it
     *  behind a "Deleting…" popup before navigating back. */
    fun deleteTranslationRun(context: Context, sourceReportId: String, runId: String): Job {
        cancelTranslation(runId)   // request cancel + flag the live run
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
            translationJobs[runId]?.cancelAndJoin()
            val rows = SecondaryResultStorage
                .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
                .filter { translationRunGroupingId(it) == runId }
            var costDelta = 0.0
            rows.forEach {
                costDelta += (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0)
                SecondaryResultStorage.delete(context, sourceReportId, it.id)
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, sourceReportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, sourceReportId)
            _translationRuns.update { it - runId }
            // Drop the persisted Speed/Mixed/Cost pick so the prefs
            // file doesn't accumulate entries for deleted runs.
            com.ai.data.TranslationModeStore.remove(runId)
        }
    }

    /** Drop one pending/running item from an in-flight translation
     *  run. Removes the item from [_translationRuns] so its row
     *  disappears on the detail screen; the [saveOneTranslationItem]
     *  guard inside [runOneTranslation] will then skip the disk
     *  write for any call that lands after this point (the guard
     *  looks the item up by id and bails if it's gone). The
     *  in-flight call itself is allowed to finish — there's no
     *  per-item Job to cancel — but its result is discarded. */
    fun cancelTranslationItem(runId: String, itemId: String) {
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            val filtered = cur.items.filter { it.id != itemId }
            if (filtered.size == cur.items.size) return@update runs
            runs + (runId to cur.copy(
                items = filtered,
                totalCostDollars = filtered.sumOf { it.costDollars }
            ))
        }
    }

    /** Re-run every errored translation row in [runId]: deletes the
     *  failed [SecondaryResult]s on disk, rebuilds [TranslationItem]s
     *  from the current report state, and dispatches them through
     *  [runOneTranslation]. The runId is preserved so the rerun rows
     *  group under the same translation run on the result screen. */
    fun restartFailedTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        val rows = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId && it.errorMessage != null }
        if (rows.isEmpty()) return@launch
        runTranslationSubset(
            context, sourceReportId, runId,
            rows.map { it.translateSourceTargetId.orEmpty() to it.translateSourceKind.orEmpty() },
            deleteRowIds = rows.map { it.id },
            // Multi-model runs: avoid retrying on the same model
            // that just failed. runTranslationSubset round-robins
            // each failed entry over the other models on the run.
            switchModelOnFail = true
        )
    }

    /** Drop every errored translation row from [runId] without
     *  re-firing. Wired to the run detail screen's "Remove failed
     *  items" button so the user can clear failures without burning
     *  more tokens. */
    fun removeFailedTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        // Benched rows are kept — they're cleared by
        // removeBenchedTranslations instead, so the two are complementary.
        val failed = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter {
                translationRunGroupingId(it) == runId && it.errorMessage != null &&
                    !com.ai.data.ModelCooldownStore.isUnavailable(it.providerId, it.model)
            }
        if (failed.isEmpty()) return@launch
        failed.forEach { SecondaryResultStorage.delete(context, sourceReportId, it.id) }
        // Also drop the items from any live state so the detail
        // screen's row count updates immediately instead of waiting
        // for the next list refresh.
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            val failedTargetKeys = failed
                .map { (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "") }
                .toSet()
            val filtered = cur.items.filterNot { item ->
                val srcKind = when (item.kind) {
                    TranslationKind.TITLE -> "TITLE"
                    TranslationKind.PROMPT -> "PROMPT"
                    TranslationKind.AGENT_RESPONSE -> "AGENT"
                    TranslationKind.META -> "META"
                }
                val srcId = when (item.kind) {
                    TranslationKind.TITLE -> "title"
                    TranslationKind.PROMPT -> "prompt"
                    else -> item.target ?: ""
                }
                item.status == TranslationStatus.ERROR && "$srcKind:$srcId" in failedTargetKeys
            }
            runs + (runId to cur.copy(
                items = filtered,
                totalCostDollars = filtered.sumOf { it.costDollars }
            ))
        }
        ReportStorage.bumpReportTimestamp(context, sourceReportId)
    }

    /** Drop only the errored translation rows whose model is
     *  currently benched (>1h-429 cooldown). Mirror of
     *  [removeFailedTranslations], narrowed to the benched subset so
     *  the user can clear the rate-limited-will-recover failures
     *  without touching the genuine errors. */
    fun removeBenchedTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        val benched = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter {
                translationRunGroupingId(it) == runId && it.errorMessage != null &&
                    com.ai.data.ModelCooldownStore.isUnavailable(it.providerId, it.model)
            }
        if (benched.isEmpty()) return@launch
        benched.forEach { SecondaryResultStorage.delete(context, sourceReportId, it.id) }
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            val benchedTargetKeys = benched
                .map { (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "") }
                .toSet()
            val filtered = cur.items.filterNot { item ->
                val srcKind = when (item.kind) {
                    TranslationKind.TITLE -> "TITLE"
                    TranslationKind.PROMPT -> "PROMPT"
                    TranslationKind.AGENT_RESPONSE -> "AGENT"
                    TranslationKind.META -> "META"
                }
                val srcId = when (item.kind) {
                    TranslationKind.TITLE -> "title"
                    TranslationKind.PROMPT -> "prompt"
                    else -> item.target ?: ""
                }
                item.status == TranslationStatus.ERROR && "$srcKind:$srcId" in benchedTargetKeys
            }
            runs + (runId to cur.copy(
                items = filtered,
                totalCostDollars = filtered.sumOf { it.costDollars }
            ))
        }
        ReportStorage.bumpReportTimestamp(context, sourceReportId)
    }

    /** Re-fire every expected entry in [runId]: deletes all existing
     *  rows (success or error) and re-dispatches the full prompt +
     *  agent + meta set from the current report state. The existing
     *  Semaphore(3) throttle inside [runTranslationSubset] still
     *  applies, so a large run shows a mix of RUNNING + PENDING rows
     *  in the detail screen rather than firing N calls in parallel. */
    fun restartAllTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        // Cancel any in-flight run for this runId so its already-
        // dispatched coroutines don't keep writing fresh rows under
        // the about-to-be-restarted runId. Cancellation is co-operative;
        // in-flight API calls finish but the post-call writes are
        // gated by translationJobs[runId] cancellation.
        cancelTranslation(runId)
        _translationRuns.update { it - runId }

        val existing = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        if (existing.isEmpty()) return@launch

        val report = ReportStorage.getReport(context, sourceReportId) ?: return@launch
        val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)
        val pairs = buildList<Pair<String, String>> {
            if (report.title.isNotBlank()) add("title" to "TITLE")
            add("prompt" to "PROMPT")
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .forEach { add(it.agentId to "AGENT") }
            secondaries
                .filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
                .forEach { add(it.id to "META") }
        }
        if (pairs.isEmpty()) return@launch

        runTranslationSubset(
            context, sourceReportId, runId, pairs,
            deleteRowIds = existing.map { it.id }
        )
    }

    /** Re-dispatch translation items whose placeholder rows are
     *  still empty (no content, no error, no durationMs). The
     *  original target set lives on disk as one row per item
     *  (written up-front by [startTranslation] / [runTranslationSubset])
     *  so we never extend coverage to items added to the report
     *  AFTER the run completed — those have no placeholder for
     *  this runId and are intentionally skipped. */
    fun startMissingTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        val existing = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        // Only placeholders need re-dispatch — completed (has content)
        // and errored (errorMessage non-null) rows are terminal. The
        // ERROR rows are addressed separately by
        // restartFailedTranslations when the user opts in.
        val placeholders = existing.filter {
            it.content.isNullOrBlank() && it.errorMessage == null && it.durationMs == null
        }
        if (placeholders.isEmpty()) {
            // Nothing to dispatch but the in-memory state may still
            // be flagged in-flight from an earlier partial pass.
            // Flip finished=true so the manage hourglass clears.
            _translationRuns.update { runs ->
                val cur = runs[runId] ?: return@update runs
                if (cur.finished) return@update runs
                runs + (runId to cur.copy(finished = true))
            }
            return@launch
        }
        val missing = placeholders.map {
            (it.translateSourceTargetId.orEmpty()) to (it.translateSourceKind.orEmpty())
        }
        runTranslationSubset(context, sourceReportId, runId, missing, deleteRowIds = emptyList())
        // After the subset's awaitAll() returns, every dispatched
        // item has settled (DONE or ERROR). Flip finished=true so
        // the manage hourglass clears and the next reconcile
        // doesn't re-fire us pointlessly.
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            if (cur.finished) return@update runs
            runs + (runId to cur.copy(finished = true))
        }
    }

    /** Rebuild [TranslationItem]s from the persisted TRANSLATE rows of
     *  a run. Used by [runTranslationSubset] to seed live state on a
     *  post-kill resume, and by [buildPersistedTranslationRunState] to
     *  render a finished run in the 3-level run screen. Each item is
     *  stamped with the provider/model that produced the row and the
     *  on-disk row id. Rows in [deleteSet] and stale placeholders (no
     *  content, no error) are skipped. */
    private fun reconstructTranslationItemsFromDisk(
        translateRows: List<SecondaryResult>,
        report: Report,
        secondaries: List<SecondaryResult>,
        deleteSet: Set<String>,
        /** When true, placeholder rows (no content, no error, no
         *  durationMs) materialise as PENDING items rather than
         *  being dropped. Display path (L1 fallback) wants this so
         *  the screen shows the full run size + Queue count even
         *  before the resume dispatch has re-seeded in-memory
         *  state. Resume seeding path leaves the default false so
         *  the placeholders aren't double-counted alongside the
         *  fresh items runTranslationSubset is about to build for
         *  them. */
        includePlaceholders: Boolean = false
    ): List<TranslationItem> = translateRows
        .filter { it.id !in deleteSet }
        .mapNotNull { row ->
            val kind = when (row.translateSourceKind) {
                "TITLE" -> TranslationKind.TITLE
                "PROMPT" -> TranslationKind.PROMPT
                "AGENT" -> TranslationKind.AGENT_RESPONSE
                "META" -> TranslationKind.META
                else -> return@mapNotNull null
            }
            val targetId = row.translateSourceTargetId.orEmpty()
            val (itemId, label) = when (kind) {
                TranslationKind.TITLE -> "title" to "Report title"
                TranslationKind.PROMPT -> "prompt" to "Report prompt"
                TranslationKind.AGENT_RESPONSE -> {
                    val ag = report.agents.firstOrNull { it.agentId == targetId }
                    val prov = AppService.findById(ag?.provider.orEmpty())?.id ?: ag?.provider.orEmpty()
                    "agent:$targetId" to "$prov / ${ag?.model.orEmpty()}"
                }
                TranslationKind.META -> {
                    val s = secondaries.firstOrNull { it.id == targetId }
                    val prov = AppService.findById(s?.providerId.orEmpty())?.id ?: s?.providerId.orEmpty()
                    val name = s?.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: s?.let { com.ai.data.legacyKindDisplayName(it.kind) } ?: ""
                    "meta:$targetId" to "$name: $prov / ${s?.model.orEmpty()}"
                }
            }
            val status = when {
                row.errorMessage != null -> TranslationStatus.ERROR
                !row.content.isNullOrBlank() -> TranslationStatus.DONE
                includePlaceholders -> TranslationStatus.PENDING
                else -> return@mapNotNull null  // resume dispatch covers it
            }
            TranslationItem(
                id = itemId, label = label, kind = kind,
                sourceText = "",
                target = targetId.takeIf { kind != TranslationKind.PROMPT && kind != TranslationKind.TITLE },
                status = status,
                translatedText = row.content,
                errorMessage = row.errorMessage,
                costDollars = (row.inputCost ?: 0.0) + (row.outputCost ?: 0.0),
                tokenUsage = row.tokenUsage,
                durationMs = row.durationMs,
                // Placeholders carry blank providerId/model (no model
                // has picked them up yet); leave the item's fields
                // null so translationModelKey returns null and the
                // L1 keeps them in the Queue rather than grouping them
                // under a phantom "" / "" model row.
                providerId = row.providerId.takeIf { it.isNotBlank() },
                model = row.model.takeIf { it.isNotBlank() },
                persistedRowId = row.id
            )
        }

    /** Reconstruct a finished / persisted translation run as a
     *  [TranslationRunState] so the 3-level run screen can render it
     *  exactly like a live run. Returns null when the run has no
     *  persisted TRANSLATE rows on disk. */
    suspend fun buildPersistedTranslationRunState(
        context: Context,
        reportId: String,
        runId: String
    ): TranslationRunState? = withContext(Dispatchers.IO) {
        val rows = SecondaryResultStorage
            .listForReport(context, reportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        if (rows.isEmpty()) return@withContext null
        val anchor = rows.first()
        val report = ReportStorage.getReport(context, reportId) ?: return@withContext null
        val secondaries = SecondaryResultStorage.listForReport(context, reportId)
        // includePlaceholders=true: a run interrupted by app restart
        // has placeholder rows on disk that the L1 should surface as
        // PENDING (Queue) items so the Total + per-status counts match
        // what the manage page shows for the same runId. The resume
        // dispatch later replaces these in the live state with real
        // items as workers pick them up.
        val items = reconstructTranslationItemsFromDisk(rows, report, secondaries, emptySet(), includePlaceholders = true)
        TranslationRunState(
            runId = runId,
            sourceReportId = reportId,
            targetLanguageName = anchor.targetLanguage ?: "",
            targetLanguageNative = anchor.targetLanguageNative ?: anchor.targetLanguage ?: "",
            items = items,
            totalCostDollars = items.sumOf { it.costDollars },
            finished = true,
            cancelled = false,
            mode = com.ai.data.TranslationModeStore.get(runId) ?: TranslationMode.COST,
            // Distinct (providerId, model) tuples from the disk rows
            // — skipping the blank-provider placeholder rows that
            // haven't been claimed by a worker yet.
            models = rows
                .filter { it.providerId.isNotBlank() && it.model.isNotBlank() }
                .map { "${it.providerId}|${it.model}" }
                .distinct()
        )
    }

    /** Shared core for [restartFailedTranslations] /
     *  [startMissingTranslations]. Reads the existing run rows to
     *  pick up provider / model / language, deletes any rows in
     *  [deleteRowIds] (used by the restart path so failed rows
     *  don't double up), builds [TranslationItem]s for each
     *  (target, kind) pair, populates [_translationRuns] under
     *  [runId], and dispatches via [runOneTranslation]. */
    private suspend fun runTranslationSubset(
        context: Context,
        sourceReportId: String,
        runId: String,
        targetKindPairs: List<Pair<String, String>>,
        deleteRowIds: List<String>,
        /** Optional per-pair source-text overrides. When a (kind,
         *  targetId) key is present the override string is used as
         *  the translation input instead of the default
         *  `report.prompt` / `agent.responseBody` / `meta.content`.
         *  Used by [translateMissingItems] to translate FROM an
         *  arbitrary source language (e.g. an existing Spanish
         *  TRANSLATE row's content) instead of always from Original.
         *  Null preserves the original behavior for the failed-
         *  restart and start-missing callers. */
        sourceTextOverrides: Map<Pair<String, String>, String>? = null,
        /** Optional model set to use when the existing target-run
         *  rows yield no usable (provider, model) tuples — which
         *  happens when [translateMissingItems] bootstraps a brand-
         *  new run from blank-provider placeholders. The caller
         *  picks the models (typically from another existing
         *  translation run on the same report). Falls back to the
         *  per-run derivation when null. */
        modelsOverride: List<Pair<AppService, String>>? = null,
        /** Restart-failed flag — when true AND the run uses more
         *  than one (provider, model) tuple, each failed item is
         *  reassigned to a model OTHER than the one it failed on
         *  (round-robin over the remaining set). Off by default to
         *  preserve [startMissingTranslations]' "reuse the recorded
         *  model" semantics, which has no failed-on model to avoid. */
        switchModelOnFail: Boolean = false
    ) {
        if (targetKindPairs.isEmpty()) return
        val translateRows = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        val anchor = translateRows.firstOrNull() ?: return
        val targetLanguageName = anchor.targetLanguage ?: return
        val targetLanguageNative = anchor.targetLanguageNative ?: targetLanguageName

        // Distinct (provider, model) tuples present on the run's
        // existing rows — this is the model set the original run
        // launched with. New items (startMissing path) round-robin
        // over this set; failed-row retries reuse each row's own
        // recorded (provider, model) so a retry hits the same
        // model that failed.
        val runModels: List<Pair<AppService, String>> = translateRows
            .mapNotNull { r -> AppService.findById(r.providerId)?.let { it to r.model } }
            .distinct()
            .ifEmpty { modelsOverride ?: emptyList() }
        if (runModels.isEmpty()) return
        // Index BEFORE deletion so a restart-failed flow can read
        // back each failed row's original (provider, model) and
        // retry on the same model. Keys are (kind, targetId).
        val rowByKindTarget: Map<Pair<String, String>, SecondaryResult> = translateRows
            .mapNotNull { r ->
                val k = r.translateSourceKind ?: return@mapNotNull null
                val t = r.translateSourceTargetId ?: return@mapNotNull null
                (k to t) to r
            }
            .toMap()

        val report = ReportStorage.getReport(context, sourceReportId) ?: return
        val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)

        val items = targetKindPairs.mapNotNull { (targetId, kind) ->
            // Reuse the existing row's id as the item's persistedRowId
            // so the re-dispatch's save overwrites the placeholder
            // (or the prior failed row, in the restart-failed flow)
            // rather than spawning a parallel record.
            val rowId = rowByKindTarget[(kind to targetId)]?.id
            // Honor caller-supplied source-text overrides ahead of
            // the default report/agent/meta derivation — used to
            // translate from a non-Original source language.
            val sourceOverride = sourceTextOverrides?.get(kind to targetId)
            when (kind) {
                "TITLE" -> TranslationItem(
                    id = "title", label = "Report title",
                    kind = TranslationKind.TITLE,
                    sourceText = sourceOverride ?: report.title,
                    persistedRowId = rowId
                )
                "PROMPT" -> TranslationItem(
                    id = "prompt", label = "Report prompt",
                    kind = TranslationKind.PROMPT,
                    sourceText = sourceOverride ?: report.prompt,
                    persistedRowId = rowId
                )
                "AGENT" -> {
                    val ag = report.agents.firstOrNull { it.agentId == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(ag.provider)?.id ?: ag.provider
                    TranslationItem(
                        id = "agent:${ag.agentId}",
                        label = "$prov / ${ag.model}",
                        kind = TranslationKind.AGENT_RESPONSE,
                        sourceText = sourceOverride ?: ag.responseBody.orEmpty(),
                        target = ag.agentId,
                        persistedRowId = rowId
                    )
                }
                "META" -> {
                    val s = secondaries.firstOrNull { it.id == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(s.providerId)?.id ?: s.providerId
                    val name = s.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.legacyKindDisplayName(s.kind)
                    TranslationItem(
                        id = "meta:${s.id}", label = "$name: $prov / ${s.model}",
                        kind = TranslationKind.META,
                        sourceText = sourceOverride ?: s.content.orEmpty(),
                        target = s.id,
                        persistedRowId = rowId
                    )
                }
                else -> null
            }
        }
        if (items.isEmpty()) return

        // Delete the rows we're replacing so the rerun doesn't double
        // up under the same (target, kind) pair.
        deleteRowIds.forEach { SecondaryResultStorage.delete(context, sourceReportId, it) }

        val state = appViewModel.uiState.value
        val aiSettings = state.aiSettings
        val template = aiSettings.getInternalPromptByName("Translate")?.text.orEmpty()

        // Pre-resolve per-(provider, model) context — mirrors
        // startTranslation. Keys are (providerId, model).
        data class ModelCtx(
            val provider: AppService, val model: String,
            val apiKey: String, val baseUrl: String,
            val pricing: PricingCache.ModelPricing
        )
        val ctxByKey: Map<Pair<String, String>, ModelCtx> = runModels
            .associate { (p, m) ->
                (p.id to m) to ModelCtx(
                    provider = p, model = m,
                    apiKey = aiSettings.getApiKey(p),
                    baseUrl = aiSettings.getEffectiveEndpointUrl(p),
                    pricing = PricingCache.getPricing(context, p, m)
                )
            }

        // Per-host caps mirror startTranslation. Each host gets
        // its own concurrent gate, so multi-host runs aren't
        // bottlenecked on the slowest provider's limit.
        val perHostCaps: Map<String, kotlinx.coroutines.sync.Semaphore> = runModels
            .map { (p, _) -> providerHost(p) }
            .distinct()
            .associateWith { host ->
                val (_, concurrent) = ProviderThrottle.limitsFor(host)
                kotlinx.coroutines.sync.Semaphore(concurrent)
            }

        // When the in-memory run state is gone (post-kill resume or
        // manual reload on a finished run), seed the fresh state with
        // already-settled rows from disk so the detail screen shows
        // every entry — the 10 done + the 30 about to retry — not just
        // the ones currently being re-dispatched.
        val deleteSet = deleteRowIds.toSet()
        val persistedItems: List<TranslationItem> = if (_translationRuns.value[runId] == null) {
            reconstructTranslationItemsFromDisk(translateRows, report, secondaries, deleteSet)
        } else emptyList()

        // Merge our items into _translationRuns under this runId so
        // runOneTranslation can read the active TranslationRunState.
        // If a state already exists (live run still in flight),
        // append; otherwise create one seeded with already-settled rows
        // so the detail screen displays the full set of entries.
        _translationRuns.update { runs ->
            val cur = runs[runId]
            // Dedupe by persistedRowId — if cur.items already has an
            // entry for the same disk row (typical after the reconcile
            // sweep rebuilt the in-memory state with placeholders as
            // PENDING and then handed the same placeholders to us to
            // dispatch), the new TranslationItem must REPLACE the old
            // one rather than be appended. Without this, the sweep
            // would double-count items and the L1 Total would balloon
            // each cycle. Also re-opens the run (`finished = false`)
            // because the append is by definition new work.
            val merged = if (cur != null) {
                val newIds = items.mapNotNull { it.persistedRowId }.toSet()
                val keptOld = cur.items.filter { it.persistedRowId !in newIds }
                cur.copy(items = keptOld + items, finished = false)
            } else TranslationRunState(
                runId = runId,
                sourceReportId = sourceReportId,
                targetLanguageName = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                items = persistedItems + items,
                totalCostDollars = persistedItems.sumOf { it.costDollars },
                mode = com.ai.data.TranslationModeStore.get(runId) ?: TranslationMode.COST,
                models = runModels.map { (p, m) -> "${p.id}|$m" }
            )
            runs + (runId to merged)
        }

        // Pair each item with the model context it should run
        // under. Default: failed-row retries reuse the (provider,
        // model) recorded on disk so the retry hits the same model
        // that failed. With [switchModelOnFail] AND a multi-model
        // run, round-robin over the OTHER models on the run
        // instead — the failed model has already proved itself
        // unreliable for this entry. New items (startMissing — no
        // row yet) always round-robin over the run's distinct
        // model set.
        val assignments: List<Pair<TranslationItem, ModelCtx>> = items.mapIndexed { idx, item ->
            val targetKey = when (item.kind) {
                TranslationKind.TITLE -> "TITLE" to "title"
                TranslationKind.PROMPT -> "PROMPT" to "prompt"
                TranslationKind.AGENT_RESPONSE -> "AGENT" to (item.target ?: "")
                TranslationKind.META -> "META" to (item.target ?: "")
            }
            val originRow = rowByKindTarget[targetKey]
            val originPair: Pair<String, String>? = originRow?.let { r ->
                AppService.findById(r.providerId)?.let { p -> p.id to r.model }
            }
            val alternatives: List<Pair<AppService, String>> =
                if (switchModelOnFail && runModels.size > 1 && originPair != null) {
                    runModels.filterNot { (p, m) -> (p.id to m) == originPair }
                } else emptyList()
            val ctx = when {
                alternatives.isNotEmpty() -> {
                    val (p, m) = alternatives[idx % alternatives.size]
                    ctxByKey[p.id to m]
                }
                originPair != null -> ctxByKey[originPair]
                else -> {
                    val (p, m) = runModels[idx % runModels.size]
                    ctxByKey[p.id to m]
                }
            }
            item to (ctx ?: ctxByKey.values.first())
        }

        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        // Mirror startTranslation's per-attempt wall-clock budget.
        // Without this, a pathologically slow (or OkHttp-retry-stuck)
        // call on the resume/restart path can hold an item RUNNING for
        // minutes — observed in the wild at 240s+ on SiliconFlow and
        // 600s+ on OpenAI. Timeout produces a Failed outcome that the
        // existing finalize path turns into a terminal ERROR row.
        val callBudgetMs = 90_000L
        try {
            withTracerTags(reportId = sourceReportId, category = "Translation", runId = runId) {
                coroutineScope {
                    assignments.map { (item, ctx) ->
                        async {
                            val host = providerHost(ctx.provider)
                            val cap = perHostCaps[host]
                                ?: kotlinx.coroutines.sync.Semaphore(1)
                            // Acquire order: per-host first (see
                            // startTranslation for the rationale).
                            cap.withPermit {
                                // Non-blocking ProviderThrottle gate — a capped
                                // host yields the coroutine (delay, not
                                // Thread.sleep) so other items proceed; the
                                // OkHttp interceptor skips its own acquire via
                                // permitPreAcquired.
                                val releaser = acquireOrRequeue(host)
                                try {
                                    ApiCallCaps.global.withPermit {
                                        ApiCallCaps.translation.withPermit {
                                            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                                // restart-failed / start-missing path:
                                                // no cross-model retry — a failed call
                                                // is finalized ERROR immediately, as
                                                // before. runOneTranslation no longer
                                                // self-finalizes, so do it here.
                                                val outcome = kotlinx.coroutines.withTimeoutOrNull(callBudgetMs) {
                                                    runOneTranslation(
                                                        runId, context, ctx.provider, ctx.apiKey,
                                                        ctx.model, ctx.baseUrl, template,
                                                        targetLanguageName, item, ctx.pricing
                                                    )
                                                } ?: run {
                                                    AppLog.w("Translation", "item ${item.id} timed out on ${ctx.provider.id}/${ctx.model} after ${callBudgetMs / 1000}s")
                                                    TranslationOutcome.Failed("${ctx.provider.id}/${ctx.model} timed out after ${callBudgetMs / 1000}s")
                                                }
                                                if (outcome is TranslationOutcome.Failed) {
                                                    finalizeTranslationError(
                                                        context, runId, item,
                                                        ctx.provider, ctx.model, ctx.pricing,
                                                        outcome.message
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    releaser.release()
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            ReportStorage.bumpReportTimestamp(context, sourceReportId)
        } finally {
            appViewModel.updateUiState {
                it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0))
            }
        }
    }

    /** Multi-language meta cross-translate dispatch. For each non-seed
     *  language, append one TRANSLATE item per just-completed seed META
     *  to that language's existing translation run, reopen the run
     *  (`finished = false`), dispatch via [runTranslationSubset], and
     *  close it again. The seed META rows aren't touched — only the
     *  target-language translation runs are reopened. The translate
     *  prompt (`internal/translate`) has only `@LANGUAGE@` / `@TEXT@`,
     *  no source-language placeholder, so cross-language source text
     *  (French → Dutch in the canonical scenario) works without any
     *  prompt change — the model auto-detects the source. */
    internal suspend fun addCrossTranslationItems(
        context: Context,
        reportId: String,
        targetLanguageName: String,
        targetLanguageNative: String,
        sourceMetas: List<SecondaryResult>,
        allSecondaries: List<SecondaryResult>
    ) {
        if (sourceMetas.isEmpty()) return
        // The meta language picker only offers languages that already
        // have a translation run, so a missing runId here means
        // something raced (e.g. the run was deleted between picker
        // open and meta execution). Skip rather than synthesising a
        // fresh run — that would surface as an unexpected new run
        // row in the manage list.
        val runId = allSecondaries
            .firstOrNull { it.kind == SecondaryKind.TRANSLATE && it.targetLanguage == targetLanguageName }
            ?.let { translationRunGroupingId(it) }
        if (runId == null) {
            AppLog.w("Meta-xlate", "No existing translation run for $targetLanguageName — skipping cross-translate")
            return
        }

        // Build placeholder TRANSLATE rows on disk so a process kill
        // mid-cross-translate leaves rows that startMissingTranslations
        // can pick up on resume — same pattern as startTranslation's
        // up-front placeholder persistence.
        val placeholders = sourceMetas.map { meta ->
            val prov = AppService.findById(meta.providerId)?.id ?: meta.providerId
            val name = meta.metaPromptName?.takeIf { it.isNotBlank() }
                ?: com.ai.data.legacyKindDisplayName(meta.kind)
            val label = "$name: $prov / ${shortModelName(meta.model)}"
            val placeholderId = java.util.UUID.randomUUID().toString()
            SecondaryResultStorage.save(context, SecondaryResult(
                id = placeholderId,
                reportId = reportId,
                kind = SecondaryKind.TRANSLATE,
                providerId = "",
                model = "",
                agentName = "Translate: $label",
                timestamp = System.currentTimeMillis(),
                content = null,
                errorMessage = null,
                translateSourceKind = "META",
                translateSourceTargetId = meta.id,
                targetLanguage = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                translationRunId = runId,
                runId = runId,
            ))
            meta.id
        }

        // Reopen the run: flip `finished` to false so the manage screen
        // (which filters by !it.isFinished && !it.cancelled) surfaces
        // the live ⏳ row again. Rebuild from disk if the run was
        // already evicted from memory (consumeTranslationRun after the
        // original run completed).
        val current = _translationRuns.value[runId]
        if (current != null) {
            _translationRuns.update { runs ->
                val c = runs[runId] ?: return@update runs
                runs + (runId to c.copy(finished = false))
            }
        } else {
            val rebuilt = buildPersistedTranslationRunState(context, reportId, runId) ?: run {
                AppLog.w("Meta-xlate", "Could not rebuild persisted state for run $runId — aborting cross-translate")
                return
            }
            _translationRuns.update { it + (runId to rebuilt.copy(finished = false)) }
        }

        // runTranslationSubset reads the placeholders (rowByKindTarget
        // lookup), builds the TranslationItems from the seed metas in
        // `secondaries`, merges them into _translationRuns, and
        // dispatches. It awaits all items before returning.
        runTranslationSubset(
            context = context,
            sourceReportId = reportId,
            runId = runId,
            targetKindPairs = placeholders.map { it to "META" },
            deleteRowIds = emptyList()
        )

        // All cross-translate items have settled — close the run again
        // so the manage row reverts from live ⏳ to summary.
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(finished = true))
        }
        ReportStorage.bumpReportTimestamp(context, reportId)
    }


    /** Translate one or more items into [targetLanguageName] using
     *  the source text the caller already resolved from the user's
     *  picked source language. Attaches to the existing target-
     *  language translation run; reuses that run's model set so the
     *  user doesn't have to pick. Fires the View-screen "Language
     *  missing" popup's chosen action. */
    fun translateMissingItems(
        context: Context,
        reportId: String,
        items: List<TranslateMissingItem>,
        targetLanguageName: String,
        targetLanguageNative: String
    ): Job? {
        if (items.isEmpty()) return null
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
            val existingRunId = allSecondaries
                .firstOrNull { it.kind == SecondaryKind.TRANSLATE && it.targetLanguage == targetLanguageName }
                ?.let { translationRunGroupingId(it) }
            // Bootstrap a new run when the target language has none.
            // This is the "back-translation to Original" case: the
            // user picked Original on the View screen for a META
            // they only have in a non-Original language, and the
            // helper translates into report.languageName which has
            // no prior run yet. Reuse models from any existing
            // translation row on the report so the new run inherits
            // the user's already-trusted translation model choice.
            val runId: String
            val modelsOverride: List<Pair<AppService, String>>?
            if (existingRunId != null) {
                runId = existingRunId
                modelsOverride = null
            } else {
                val sampleRows = allSecondaries.filter {
                    it.kind == SecondaryKind.TRANSLATE && it.providerId.isNotBlank()
                }
                val mods = sampleRows.mapNotNull { r ->
                    AppService.findById(r.providerId)?.let { it to r.model }
                }.distinct()
                if (mods.isEmpty()) {
                    AppLog.w("Translate-missing", "No existing translation run for $targetLanguageName and no model to bootstrap from — skipping ${items.size} item(s)")
                    return@launch
                }
                runId = java.util.UUID.randomUUID().toString()
                modelsOverride = mods
            }

            // Persist placeholder TRANSLATE rows — same pattern as
            // addCrossTranslationItems. Map the (sourceKind, targetId)
            // back to the placeholder row id so runTranslationSubset's
            // rowByKindTarget lookup picks it up and saveOneTranslationItem
            // overwrites this row in place.
            items.forEach { item ->
                SecondaryResultStorage.save(context, SecondaryResult(
                    id = java.util.UUID.randomUUID().toString(),
                    reportId = reportId,
                    kind = SecondaryKind.TRANSLATE,
                    providerId = "",
                    model = "",
                    agentName = "Translate: ${item.label}",
                    timestamp = System.currentTimeMillis(),
                    content = null,
                    errorMessage = null,
                    translateSourceKind = item.sourceKind,
                    translateSourceTargetId = item.targetId,
                    targetLanguage = targetLanguageName,
                    targetLanguageNative = targetLanguageNative,
                    translationRunId = runId,
                    runId = runId,
                ))
            }

            // Reopen the run so the live row reverts to ⏳ while the
            // new items dispatch. Rebuild from disk if the run was
            // evicted from memory after the original finished. For
            // a brand-new (bootstrapped) run we let
            // runTranslationSubset seed _translationRuns on first
            // touch — no pre-flip needed.
            if (existingRunId != null) {
                if (_translationRuns.value[runId] != null) {
                    _translationRuns.update { runs ->
                        val c = runs[runId] ?: return@update runs
                        runs + (runId to c.copy(finished = false))
                    }
                } else {
                    val rebuilt = buildPersistedTranslationRunState(context, reportId, runId) ?: run {
                        AppLog.w("Translate-missing", "Could not rebuild persisted state for run $runId — aborting")
                        return@launch
                    }
                    _translationRuns.update { it + (runId to rebuilt.copy(finished = false)) }
                }
            }

            // Dispatch via runTranslationSubset with per-item source
            // overrides — passes our caller-resolved sourceText
            // instead of the default Original-derivation. Pass the
            // model set for the bootstrapped-new-run case so the
            // subset helper has something to round-robin items over.
            val pairs = items.map { it.targetId to it.sourceKind }
            val overrides: Map<Pair<String, String>, String> = items.associate {
                (it.sourceKind to it.targetId) to it.sourceText
            }
            runTranslationSubset(
                context = context,
                sourceReportId = reportId,
                runId = runId,
                targetKindPairs = pairs,
                deleteRowIds = emptyList(),
                sourceTextOverrides = overrides,
                modelsOverride = modelsOverride
            )

            _translationRuns.update { runs ->
                val cur = runs[runId] ?: return@update runs
                runs + (runId to cur.copy(finished = true))
            }
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
    }
}
