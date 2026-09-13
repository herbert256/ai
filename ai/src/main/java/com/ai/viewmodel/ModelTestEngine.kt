package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.AgentParameters
import com.ai.data.AnalysisRepository
import com.ai.data.ApiCallCaps
import com.ai.data.ModelCooldownStore
import com.ai.data.ModelProbePolicy
import com.ai.data.withTraceFilenameSink
import com.ai.data.ApiTracer
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicReference
import com.ai.data.ModelType
import com.ai.data.TokenUsage
import com.ai.data.callModerationApi
import com.ai.data.callRerankApi
import com.ai.data.embedWithStatus
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.ModelTestKey
import com.ai.data.ModelTestRunState
import com.ai.data.ModelTestRunStore
import com.ai.data.ModelTestState
import com.ai.data.PricingCache
import com.ai.data.ProviderThrottle
import com.ai.data.TestStatus
import com.ai.data.analyze
import com.ai.data.modelTestKey
import com.ai.data.withTraceCategory
import com.ai.data.withTracerTags
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime owner for the single "Test all models" run (Housekeeping →
 * Test → Test all models). Mirrors [FanOutEngine]: a single
 * [StateFlow] the UI subscribes to, every transition an atomic
 * update, per-item coroutines gated by the same nested-permit
 * structure Fan Out uses.
 *
 * Unlike [FanOutEngine] there's no report and no [com.ai.data
 * .SecondaryResult] disk rows — the whole run is persisted as one
 * JSON document via [ModelTestRunStore], flushed on each item
 * completion (crash-safe partial results) and once on run end.
 *
 * The probe is the provider-screen test: `analyze(..., TEST_PROMPT,
 * ...)` — called directly (not the thin `testModel` wrapper) so the
 * L3 detail screen can show response text, cost, and latency.
 */
class ModelTestEngine internal constructor(
    private val appViewModel: AppViewModel
) {
    private val _run = MutableStateFlow<ModelTestRunState?>(null)
    val run: StateFlow<ModelTestRunState?> = _run.asStateFlow()

    /** Keys currently blocked inside [acquireOrRequeue] — feeds the
     *  L1 "Throttled" stat. */
    private val _throttledKeys = MutableStateFlow<Set<String>>(emptySet())
    val throttledKeys: StateFlow<Set<String>> = _throttledKeys.asStateFlow()

    private val itemJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var runJob: Job? = null

    /** True while a fresh run is in flight — drives the L1 button's
     *  enabled state. */
    val isRunning: Boolean get() = runJob?.isActive == true

    // -----------------------------------------------------------------
    // Hydration — disk → StateFlow
    // -----------------------------------------------------------------

    /** Load the last persisted run into the flow on screen open.
     *  Idempotent: a no-op once the flow already holds a run.
     *
     *  Pre-snapshot runs (persisted before catalogTotal /
     *  inaccessibleAtStart / etc. existed) load with those fields at
     *  zero — backfill them from current aiSettings + the providers
     *  in the run so the L1 top row shows real counts. Persisted
     *  back to disk so the next launch doesn't need to recompute. */
    suspend fun hydrate(context: Context) {
        if (_run.value != null) return
        val persisted = withContext(Dispatchers.IO) { ModelTestRunStore.load(context) }
        if (persisted != null) {
            // Reconcile the snapshot: the four buckets + items.size
            // must equal catalogTotal. They can drift when a probe
            // mid-run is auto-added to test-excluded (cost > 5 cents) or
            // inaccessible — the model ends up counted in two
            // places. Recompute when the math doesn't balance.
            val sum = persisted.inaccessibleAtStart + persisted.excludedAtStart +
                persisted.noChatAtStart + persisted.items.size
            val needsBackfill = persisted.items.isNotEmpty() &&
                (persisted.catalogTotal == 0 || sum != persisted.catalogTotal)
            val rehydrated = if (needsBackfill) {
                val aiSettings = appViewModel.uiState.value.aiSettings
                val stats = computeCatalogPartition(
                    persisted.providerIds, aiSettings, persisted.items.keys
                )
                val updated = persisted.copy(
                    catalogTotal = stats.total,
                    inaccessibleAtStart = stats.inaccessible,
                    excludedAtStart = stats.excluded,
                    noChatAtStart = stats.noChat
                )
                withContext(Dispatchers.IO) { ModelTestRunStore.save(context, updated) }
                AppLog.i("ModelTest", "→ hydrate backfill: catalog=${stats.total}, inacc=${stats.inaccessible}, excl=${stats.excluded}, noChat=${stats.noChat}, items=${updated.items.size}")
                updated
            } else persisted
            _run.update { it ?: rehydrated }
        }
    }

    /** Result of [computeCatalogPartition] — every model of the given
     *  providers' catalogs falls into exactly one bucket. */
    private data class CatalogPartition(
        val total: Int, val inaccessible: Int, val excluded: Int, val noChat: Int
    )

    /** Bucket the catalog of [providerIds] into the three skip
     *  categories. Models whose key is in [itemKeys] are treated as
     *  *already classified as for-testing* — even if they appear in
     *  a skip list now (mid-flight detection can add them
     *  post-startRun). Prevents double-counting when the L1 top-row
     *  computes Total as inacc + excl + noChat + items.size. */
    private fun computeCatalogPartition(
        providerIds: List<String>,
        aiSettings: com.ai.model.Settings,
        itemKeys: Set<String> = emptySet()
    ): CatalogPartition {
        var total = 0; var inacc = 0; var excl = 0; var noChat = 0
        for (pid in providerIds) {
            val svc = AppService.findById(pid) ?: continue
            for (model in aiSettings.getProvider(svc).models.distinct()) {
                if (model.isBlank()) continue
                total++
                if (modelTestKey(pid, model) in itemKeys) continue
                when {
                    aiSettings.isInaccessible(pid, model) -> inacc++
                    aiSettings.isTestExcluded(pid, model) -> excl++
                    else -> {
                        val t = aiSettings.getModelType(svc, model)
                        if (t != null && t in NON_TESTABLE_TYPES) noChat++
                    }
                }
            }
        }
        return CatalogPartition(total, inacc, excl, noChat)
    }

    // -----------------------------------------------------------------
    // Run launch
    // -----------------------------------------------------------------

    /** Start a fresh run — tests every configured model of every
     *  active provider whose id is in [providerIds]. Replaces the
     *  previous run entirely. No-op when a run is already in flight. */
    fun startRun(context: Context, providerIds: Set<String>) {
        if (runJob?.isActive == true) return
        cancelItemJobs()
        _throttledKeys.value = emptySet()

        // Build the candidate list: every model of every selected
        // active provider. Dedupe defensively by key. Cheap enough to
        // do on the caller's thread; the disk write happens in dispatch.
        val aiSettings = appViewModel.uiState.value.aiSettings
        val items = LinkedHashMap<ModelTestKey, ModelTestState>()
        val selectedProviderIds = aiSettings.getActiveServices()
            .filter { it.id in providerIds && aiSettings.getProvider(it).apiKey.isNotBlank() }
            .map { it.id }
        // Build the testable item list — every model that doesn't
        // land in inaccessible / test-excluded / non-chat. The L1
        // top-row counts come from [computeCatalogPartition] over
        // the same provider set so they reconcile exactly.
        for (pid in selectedProviderIds) {
            val service = AppService.findById(pid) ?: continue
            val config = aiSettings.getProvider(service)
            for (model in config.models) {
                if (model.isBlank()) continue
                val key = modelTestKey(service.id, model)
                if (key in items) continue
                if (aiSettings.isInaccessible(service.id, model)) continue
                if (aiSettings.isTestExcluded(service.id, model)) continue
                val type = aiSettings.getModelType(service, model)
                if (type != null && type in NON_TESTABLE_TYPES) continue
                items[key] = ModelTestState(
                    key = key,
                    providerId = service.id,
                    model = model,
                    status = TestStatus.PENDING
                )
            }
        }
        val stats = computeCatalogPartition(selectedProviderIds, aiSettings, items.keys)
        _run.value = ModelTestRunState(
            startedAt = System.currentTimeMillis(),
            runId = java.util.UUID.randomUUID().toString(),
            policyVersion = ModelProbePolicy.VERSION,
            items = items,
            catalogTotal = stats.total,
            inaccessibleAtStart = stats.inaccessible,
            excludedAtStart = stats.excluded,
            noChatAtStart = stats.noChat
        )
        AppLog.i("ModelTest", "→ startRun ${items.size} models (catalog=${stats.total}, inacc=${stats.inaccessible}, excl=${stats.excluded}, noChat=${stats.noChat})")
        dispatch(context, items.keys.toList())
    }

    /** Possible results of [resumeRun]. */
    enum class ResumeOutcome { NO_RUN, ALREADY_RUNNING, ALREADY_COMPLETE, RESUMED }

    /** Possible results of [rerunErrors]. */
    enum class RerunErrorsOutcome { NO_RUN, ALREADY_RUNNING, NO_ERRORS, RESTARTED }

    /** Re-dispatch a run whose coroutine is gone (process kill, crash,
     *  cancelled scope) but whose persisted state still shows
     *  unfinished models. The L1 "Check current test run" button calls
     *  this: when the run only *looks* like it's running, it restarts
     *  the leftover work. Finished results are kept; RUNNING items —
     *  whose worker no longer exists — are reset to PENDING. No-op when
     *  the run is genuinely in flight or already complete. */
    fun resumeRun(context: Context): ResumeOutcome {
        if (runJob?.isActive == true) return ResumeOutcome.ALREADY_RUNNING
        val run = _run.value ?: return ResumeOutcome.NO_RUN
        val unfinished = run.items.values.filter {
            it.status == TestStatus.PENDING || it.status == TestStatus.RUNNING
        }
        if (unfinished.isEmpty()) return ResumeOutcome.ALREADY_COMPLETE

        cancelItemJobs()
        _throttledKeys.value = emptySet()
        // RUNNING items have no live worker behind them — knock them
        // back to PENDING so the restart picks them up cleanly.
        _run.update { r ->
            r?.copy(items = r.items.mapValues { (_, st) ->
                if (st.status == TestStatus.RUNNING) st.copy(status = TestStatus.PENDING) else st
            })
        }
        AppLog.i("ModelTest", "↻ resumeRun ${unfinished.size} unfinished models")
        dispatch(context, unfinished.map { it.key })
        return ResumeOutcome.RESUMED
    }

    /** Re-probe every FAIL item from the current run — flips them back
     *  to PENDING and dispatches just that subset. PASS results are
     *  kept untouched (PENDING/RUNNING are too, in the unlikely event
     *  some are still lingering from a process-killed prior run; use
     *  [resumeRun] for those). At end-of-rerun the same
     *  [syncTestRunSideEffects] hook fires, so Blocked-models +
     *  Test-excluded stay consistent with the merged state.
     *
     *  Unsupported, excluded and inaccessible entries stay in the snapshot;
     *  retrying never changes the original catalog denominator. */
    fun rerunErrors(context: Context): RerunErrorsOutcome {
        if (runJob?.isActive == true) return RerunErrorsOutcome.ALREADY_RUNNING
        val run = _run.value ?: return RerunErrorsOutcome.NO_RUN
        val aiSettings = appViewModel.uiState.value.aiSettings

        val originalFailedKeys = run.items.values
            .filter { it.status == TestStatus.FAIL || it.status == TestStatus.UNSUPPORTED }
            .map { it.key }
            .toSet()
        // Stale FAIL items — items that wouldn't be in a fresh sweep
        // today (test-excluded, inaccessible, or non-testable type).
        // Retain their terminal outcome without re-probing them.
        val staleKeys = run.items.values
            .filter { st ->
                if (st.key !in originalFailedKeys) return@filter false
                if (aiSettings.isTestExcluded(st.providerId, st.model)) return@filter true
                if (aiSettings.isInaccessible(st.providerId, st.model)) return@filter true
                val svc = AppService.findById(st.providerId) ?: return@filter false
                val t = aiSettings.getModelType(svc, st.model)
                ModelProbePolicy.unsupportedReason(svc, st.model, t) != null
            }
            .map { it.key }
            .toSet()
        val toRerunKeys = originalFailedKeys - staleKeys

        if (toRerunKeys.isEmpty()) {
            if (staleKeys.isNotEmpty()) {
                _run.update { r -> r?.copy(items = r.items.mapValues { (k, st) ->
                    if (k in staleKeys) st.copy(status = if (aiSettings.isInaccessible(st.providerId, st.model))
                        TestStatus.INACCESSIBLE else TestStatus.UNSUPPORTED) else st
                }) }
                AppLog.i("ModelTest", "↻ rerunErrors retained ${staleKeys.size} unsupported or excluded, nothing to rerun")
                appViewModel.viewModelScope.launch(Dispatchers.IO) { persist(context) }
            }
            return RerunErrorsOutcome.NO_ERRORS
        }

        cancelItemJobs()
        _throttledKeys.value = emptySet()
        // One atomic StateFlow update: preserve skipped items, reset retry candidates.
        _run.update { r ->
            r?.copy(items = r.items
                .mapValues { (k, st) ->
                    when (k) {
                        in toRerunKeys -> st.copy(status = TestStatus.PENDING)
                        in staleKeys -> st.copy(status = if (aiSettings.isInaccessible(st.providerId, st.model))
                            TestStatus.INACCESSIBLE else TestStatus.UNSUPPORTED)
                        else -> st
                    }
                })
        }
        if (staleKeys.isNotEmpty()) {
            AppLog.i("ModelTest", "↻ rerunErrors retained ${staleKeys.size} unsupported or excluded items")
        }
        AppLog.i("ModelTest", "↻ rerunErrors ${toRerunKeys.size} previously-failed models")
        dispatch(context, toRerunKeys.toList())
        return RerunErrorsOutcome.RESTARTED
    }

    /** Recheck one result after an integration fix without repeating a whole provider. */
    fun retryItem(context: Context, key: ModelTestKey): Boolean {
        if (isRunning) return false
        val item = _run.value?.items?.get(key) ?: return false
        val settings = appViewModel.uiState.value.aiSettings
        if (settings.isTestExcluded(item.providerId, item.model)) return false
        transition(key) { it.copy(status = TestStatus.PENDING) }
        dispatch(context, listOf(key))
        return true
    }

    /** Launch the outer coroutine that runs per-item workers for
     *  [keys] against the current `_run`. Shared by [startRun] (fresh
     *  list), [resumeRun] (unfinished items) and [rerunErrors] (reset
     *  FAILs). */
    private fun dispatch(context: Context, keys: List<ModelTestKey>) {
        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                withTracerTags(reportId = null, category = "Test all models", runId = _run.value?.runId) {
                    _run.value?.let { ModelTestRunStore.save(context, it) }
                    if (keys.isEmpty()) return@withTracerTags

                    // No per-batch limit any more — bound the sweep by the
                    // single global "Concurrent API calls" cap (the shared
                    // global semaphore in runThrottledBatch still applies on
                    // top; this local cap matches it so it never binds tighter).
                    _run.value?.runId?.let { ApiTracer.retainModelTestRun(it) }
                    val cap = appViewModel.uiState.value.generalSettings.maxConcurrentApiCalls.coerceAtLeast(1)
                    val ioCap = Semaphore(cap)
                    val items = keys.mapNotNull { _run.value?.items?.get(it) }
                    // Shared runner owns the interleave + the canonical
                    // global → ioCap → per-host acquisition order. runOne keeps
                    // its own 60s withTimeout (caught locally so one hung probe
                    // fails just that item, not the whole sweep) and the
                    // suppressInlineRetry context. Empty host (provider not
                    // registered) isn't skipped — runOne records the FAIL.
                    runThrottledBatch(
                        items = items,
                        hostOf = { AppService.findById(it.providerId)?.let { s -> providerHost(s) } ?: "" },
                        subCap = ioCap,
                        onThrottled = { item -> _throttledKeys.update { it + item.key } },
                        register = { item, d ->
                            itemJobs[item.key] = d
                            d.invokeOnCompletion { itemJobs.remove(item.key, d) }
                        }
                    ) { item -> runOne(context, item.key) }
                    AppLog.i("ModelTest", "← run done (${items.size} models)")
                    // Per-item PASS/FAIL transitions in [runOne] have
                    // already pushed the cumulative effect into the
                    // in-memory Blocked / Test-excluded lists via
                    // [AppViewModel.applyTestItemIncrement]; just
                    // capture the final state to disk in one write.
                    appViewModel.flushAiSettingsToDisk()
                }
            } finally {
                _throttledKeys.value = emptySet()
                runJob = null
                _run.value?.let { ModelTestRunStore.save(context, it) }
            }
        }
        runJob = outer
    }

    private fun cancelItemJobs() {
        itemJobs.values.forEach { it.cancel() }
        itemJobs.clear()
    }

    private companion object {
        /** Alias for the shared [ModelType.NON_TESTABLE_TYPES] so the
         *  existing `in NON_TESTABLE_TYPES` call sites in this file stay
         *  legible. Single source of truth lives in [ModelType]. */
        private val NON_TESTABLE_TYPES: Set<String> = ModelType.NON_TESTABLE_TYPES
    }

    /** Active providers with a non-blank API key, each paired with its
     *  configured-model count — the candidate set the provider-
     *  selection screen offers. Mirrors the filter [startRun] applies
     *  when enumerating models. */
    fun testableProviders(): List<Pair<AppService, Int>> {
        val aiSettings = appViewModel.uiState.value.aiSettings
        return aiSettings.getActiveServices()
            .map { it to aiSettings.getProvider(it) }
            .filter { (_, config) -> config.apiKey.isNotBlank() }
            .map { (service, config) ->
                // Subtract test-excluded + inaccessible + non-testable
                // model types so the count matches what the sweep will
                // actually probe.
                val n = config.models.distinct().count { m ->
                    if (m.isBlank()) return@count false
                    if (aiSettings.isTestExcluded(service.id, m)) return@count false
                    if (aiSettings.isInaccessible(service.id, m)) return@count false
                    val t = aiSettings.getModelType(service, m)
                    t == null || t !in NON_TESTABLE_TYPES
                }
                service to n
            }
    }

    /** Stop the in-flight run. Cancels the outer + every per-item
     *  coroutine, then marks anything not yet finished as a cancelled
     *  failure so the persisted run stays a consistent, complete
     *  snapshot (and L1's count-based "running" flag goes false). */
    fun cancel(context: Context) {
        runJob?.cancel()
        runJob = null
        cancelItemJobs()
        _throttledKeys.value = emptySet()
        _run.update { run ->
            run?.copy(items = run.items.mapValues { (_, st) ->
                if (st.status == TestStatus.PENDING || st.status == TestStatus.RUNNING)
                    st.copy(status = TestStatus.FAIL, errorMessage = "Cancelled")
                else st
            })
        }
        _run.value?.let { ModelTestRunStore.save(context, it) }
        // Per-item incremental updates accumulated in memory during the
        // run aren't persisted by runOne — flush them now so the
        // Blocked / Test-excluded state survives if the process dies
        // before another save. Cancelled items skip the incremental
        // (they don't go through transition()), so they don't pollute
        // either list.
        appViewModel.flushAiSettingsToDisk()
        AppLog.i("ModelTest", "${com.ai.data.MetadataIconsHolder.current.closeMark} run cancelled")
    }

    /** Drop the in-memory run + cancel any in-flight work. Paired with
     *  [ModelTestRunStore.delete] on the Clear-runtime-data path so the
     *  L1 screen reflects the wipe without an app restart. */
    fun clearRun() {
        runJob?.cancel()
        runJob = null
        cancelItemJobs()
        _throttledKeys.value = emptySet()
        _run.value = null
    }

    // -----------------------------------------------------------------
    // Per-item runner
    // -----------------------------------------------------------------

    /** PENDING → RUNNING → PASS/FAIL for one (provider, model). Owns
     *  the throttle permit + the probe call + the state transitions. */
    // Throttle scaffolding (global + ioCap + per-host acquireOrRequeue +
    // permitPreAcquired) is owned by [runThrottledBatch] now; this runs as
    // its per-item body with all permits held. Keeps its own local 60s
    // withTimeout + suppressInlineRetry (see below).
    private suspend fun runOne(context: Context, key: ModelTestKey) {
        val item = _run.value?.items?.get(key) ?: return
        val traceSink = AtomicReference<String?>(null)
        val t0 = System.currentTimeMillis()
        try {
            val service = AppService.findById(item.providerId)
            val settings = appViewModel.uiState.value.aiSettings
            val type = service?.let { settings.getModelType(it, item.model) }
            val unsupported = service?.let { ModelProbePolicy.unsupportedReason(it, item.model, type) }
                ?: if (service == null) "Provider not registered in this app." else null
            if (unsupported != null || service == null) {
                transition(key) { it.copy(status = TestStatus.UNSUPPORTED, errorMessage = unsupported,
                    responseText = null, previousErrorMessage = item.errorMessage, traceFilename = null, durationMs = 0L) }
                _run.value?.items?.get(key)?.let { appViewModel.applyTestItemIncrement(it) }
                return
            }
            transition(key) { it.copy(status = TestStatus.RUNNING, errorMessage = null,
                responseText = null, traceFilename = null) }
            val probe = try {
                withTraceFilenameSink(traceSink) {
                    withTimeout(ModelProbePolicy.TIMEOUT_MS) {
                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true) +
                            ProviderThrottle.suppressInlineRetry.asContextElement(true)) {
                            withTraceCategory("Test all models") {
                                val apiKey = settings.getApiKey(service)
                                when (type) {
                                    ModelType.EMBEDDING -> probeEmbedding(service, apiKey, item.model)
                                    ModelType.RERANK -> probeRerank(service, apiKey, item.model)
                                    ModelType.MODERATION -> probeModeration(service, apiKey, item.model)
                                    else -> probeChat(service, apiKey, item.model)
                                }
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                ProbeResult(false, "Test timed out after 60s", null,
                    System.currentTimeMillis() - t0, null)
            }
            val status = when {
                probe.isSuccess -> TestStatus.PASS
                ModelProbePolicy.isUnsupportedError(probe.errorMessage) -> TestStatus.UNSUPPORTED
                ModelProbePolicy.isInaccessibleError(probe.errorMessage) -> TestStatus.INACCESSIBLE
                else -> TestStatus.FAIL
            }
            val (inCost, outCost) = probe.tokenUsage?.let {
                PricingCache.computeInOutCost(it, PricingCache.getPricing(context, service, item.model))
            } ?: (0.0 to 0.0)
            transition(key) { it.copy(status = status, errorMessage = probe.errorMessage, previousErrorMessage = item.errorMessage,
                responseText = probe.responseText,
                durationMs = probe.durationMs.takeIf { ms -> ms > 0 } ?: (System.currentTimeMillis() - t0),
                inputCost = inCost, outputCost = outCost, traceFilename = traceSink.get()) }
            if (status == TestStatus.INACCESSIBLE) {
                appViewModel.upsertInaccessibleModel(com.ai.model.InaccessibleModel(
                    item.providerId, item.model, probe.errorMessage.orEmpty().take(300)))
            }
            if (status == TestStatus.PASS) ModelCooldownStore.remove(item.providerId, item.model)
            _run.value?.items?.get(key)?.let { appViewModel.applyTestItemIncrement(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Keep the exact reserved filename even if its failure trace finishes later.
            transition(key) { it.copy(traceFilename = traceSink.get() ?: it.traceFilename) }
            throw e
        } catch (e: Exception) {
            transition(key) { it.copy(status = TestStatus.FAIL, errorMessage = e.message ?: "Connection error",
                durationMs = System.currentTimeMillis() - t0, traceFilename = traceSink.get()) }
            _run.value?.items?.get(key)?.let { appViewModel.applyTestItemIncrement(it) }
        } finally {
            _throttledKeys.update { it - key }
            persist(context)
        }
    }

    /** Generic outcome of a per-(provider, model) probe — chat /
     *  embedding / rerank all funnel into this shape so [runOne] can
     *  apply the result uniformly. */
    private data class ProbeResult(
        val isSuccess: Boolean,
        val errorMessage: String?,
        val responseText: String?,
        val durationMs: Long,
        val tokenUsage: TokenUsage?
    )

    /** Chat probe — the original "Reply with exactly: OK" call, capped
     *  at [AnalysisRepository.TEST_MAX_TOKENS] tokens so balance-gating
     *  providers don't pre-auth the model's whole output window.
     *
     *  Treats `HTTP 200 + (output_tokens > 0 OR reasoning_tokens > 0) +
     *  empty content` as *reachable*. Two real cases:
     *   - OpenAI gpt-5 / o-series via OpenRouter — encrypted
     *     `reasoning_details` and null content when the budget went
     *     to hidden reasoning (we see [TokenUsage.outputTokens] > 0).
     *   - Gemini 2.5 / 3.x thinking models — the budget burns entirely
     *     in [GeminiUsageMetadata.thoughtsTokenCount] and visible
     *     output is empty (we see [TokenUsage.reasoningTokens] > 0).
     *  The probe's job is reachability, not "did the model produce
     *  useful text" — both shapes count as reachable. */
    private suspend fun probeChat(service: AppService, apiKey: String, model: String): ProbeResult {
        val t0 = System.currentTimeMillis()
        val r = appViewModel.repository.analyze(
            service, apiKey, AnalysisRepository.TEST_PROMPT, model,
            params = AgentParameters(maxTokens = AnalysisRepository.TEST_MAX_TOKENS,
                systemPrompt = if (service.id == "NVIDIA" && "topic-control" in model)
                    "Requests about confirming system health are on-topic. Other topics are off-topic." else null)
        )
        val outputTokens = r.tokenUsage?.outputTokens ?: 0
        val reasoningTokens = r.tokenUsage?.reasoningTokens ?: 0
        val emittedTokens = outputTokens + reasoningTokens
        val reachable = ModelProbePolicy.reachable(r)
        val reachableViaReasoning = reachable && r.analysis.isNullOrBlank() && emittedTokens > 0
        return ProbeResult(
            isSuccess = reachable,
            errorMessage = if (reachable) null else r.error,
            responseText = r.analysis?.takeIf { it.isNotBlank() }
                ?: if (reachableViaReasoning) "Reachable — emitted $emittedTokens reasoning tokens, no visible content" else null,
            durationMs = System.currentTimeMillis() - t0,
            tokenUsage = r.tokenUsage
        )
    }

    /** Embedding probe — calls /v1/embeddings with a single short
     *  string. PASS = vector returned. Same dispatch path RAG and
     *  Knowledge bases use, via the rich [embedWithStatus] sibling of
     *  the silent [com.ai.data.embed] used by those features. */
    private suspend fun probeEmbedding(service: AppService, apiKey: String, model: String): ProbeResult {
        val r = appViewModel.repository.embedWithStatus(
            service, apiKey, model, listOf(AnalysisRepository.TEST_PROMPT)
        )
        return ProbeResult(
            isSuccess = r.isSuccess,
            errorMessage = r.errorMessage,
            responseText = r.vectors?.firstOrNull()?.let { "embedded — ${it.size} dimensions" },
            durationMs = r.durationMs,
            tokenUsage = r.tokenUsage
        )
    }

    /** Rerank probe — calls the provider's native rerank endpoint
     *  ([com.ai.data.callRerankApi]) with a trivial (query, [doc])
     *  pair. PASS = non-empty results body. Providers without
     *  `nativeRerankUrl` configured fail with an explanatory message;
     *  add the URL to the provider definition (or a Manual Model
     *  Types override) to enable. */
    private suspend fun probeRerank(service: AppService, apiKey: String, model: String): ProbeResult {
        val r = callRerankApi(
            service, apiKey, model,
            query = "test", documents = listOf("test document")
        )
        return ProbeResult(
            isSuccess = r.errorMessage == null && !r.content.isNullOrBlank(),
            errorMessage = r.errorMessage,
            responseText = r.content,
            durationMs = r.durationMs,
            tokenUsage = null
        )
    }

    /** Moderation probe — calls the provider's native moderation
     *  endpoint ([com.ai.data.callModerationApi]) with a single
     *  innocuous input. PASS = a parseable result (flagged or not is
     *  irrelevant — the API answered). Providers without
     *  `nativeModerationUrl` configured fail with an explanatory
     *  message, matching the rerank probe's shape. */
    private suspend fun probeModeration(service: AppService, apiKey: String, model: String): ProbeResult {
        val start = System.currentTimeMillis()
        val (results, apiResult) = callModerationApi(
            service, apiKey, model, listOf(AnalysisRepository.TEST_PROMPT)
        )
        val first = results?.firstOrNull()
        val resp = first?.let {
            "flagged=${it.flagged}" + (if (it.firedCategories.isNotEmpty())
                " · ${it.firedCategories.joinToString(",")}" else "")
        }
        return ProbeResult(
            isSuccess = apiResult.errorMessage == null && first != null,
            errorMessage = apiResult.errorMessage,
            responseText = resp,
            durationMs = if (apiResult.durationMs > 0) apiResult.durationMs
                         else System.currentTimeMillis() - start,
            tokenUsage = apiResult.tokenUsage
        )
    }

    // -----------------------------------------------------------------
    // State-flow helpers
    // -----------------------------------------------------------------

    private fun transition(key: ModelTestKey, update: (ModelTestState) -> ModelTestState) {
        _run.update { run ->
            val r = run ?: return@update run
            val cur = r.items[key] ?: return@update run
            val next = update(cur)
            if (next == cur) r else r.copy(items = r.items + (key to next))
        }
    }

    private suspend fun persist(context: Context) {
        withContext(Dispatchers.IO) {
            _run.value?.let { ModelTestRunStore.save(context, it) }
        }
    }
}
