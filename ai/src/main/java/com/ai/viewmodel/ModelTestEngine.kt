package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.AgentParameters
import com.ai.data.AnalysisRepository
import com.ai.data.ApiCallCaps
import com.ai.data.ModelType
import com.ai.data.TokenUsage
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
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
     *  Idempotent: a no-op once the flow already holds a run. */
    suspend fun hydrate(context: Context) {
        if (_run.value != null) return
        val persisted = withContext(Dispatchers.IO) { ModelTestRunStore.load(context) }
        if (persisted != null) _run.update { it ?: persisted }
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
        val skipKeys = aiSettings.testExcludedKeys
        for (service in aiSettings.getActiveServices()) {
            if (service.id !in providerIds) continue
            val config = aiSettings.getProvider(service)
            if (config.apiKey.isBlank()) continue
            for (model in config.models) {
                if (model.isBlank()) continue
                val key = modelTestKey(service.id, model)
                if (key in items) continue
                if (key in skipKeys) continue   // Test-excluded — skip entirely.
                // Skip types we have no working probe for (image / tts /
                // stt / moderation / classify / ocr). CHAT / RESPONSES /
                // EMBEDDING / RERANK / unknown go through their native
                // dispatch in runOne.
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
        _run.value = ModelTestRunState(startedAt = System.currentTimeMillis(), items = items)
        AppLog.i("ModelTest", "→ startRun ${items.size} models")
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
     *  Also drops FAIL items that should never have been in the sweep
     *  to begin with: test-excluded models (added since the original
     *  run) and non-testable types ([NON_TESTABLE_TYPES] — images,
     *  TTS, STT, etc., possibly inherited from a run that predates the
     *  type filter). These are removed from `_run.items` entirely so
     *  the L1 Total reflects what's actually testable. */
    fun rerunErrors(context: Context): RerunErrorsOutcome {
        if (runJob?.isActive == true) return RerunErrorsOutcome.ALREADY_RUNNING
        val run = _run.value ?: return RerunErrorsOutcome.NO_RUN
        val aiSettings = appViewModel.uiState.value.aiSettings

        val originalFailedKeys = run.items.values
            .filter { it.status == TestStatus.FAIL }
            .map { it.key }
            .toSet()
        // Stale FAIL items — items that wouldn't be in a fresh sweep
        // today (test-excluded, or non-testable type). Drop them
        // entirely rather than re-probing them every rerun.
        val staleKeys = run.items.values
            .filter { st ->
                if (st.key !in originalFailedKeys) return@filter false
                if (aiSettings.isTestExcluded(st.providerId, st.model)) return@filter true
                val svc = AppService.findById(st.providerId) ?: return@filter false
                val t = aiSettings.getModelType(svc, st.model)
                t != null && t in NON_TESTABLE_TYPES
            }
            .map { it.key }
            .toSet()
        val toRerunKeys = originalFailedKeys - staleKeys

        if (toRerunKeys.isEmpty()) {
            if (staleKeys.isNotEmpty()) {
                _run.update { r -> r?.copy(items = r.items.filterKeys { it !in staleKeys }) }
                AppLog.i("ModelTest", "↻ rerunErrors dropped ${staleKeys.size} stale, nothing to rerun")
                _run.value?.let { ModelTestRunStore.save(context, it) }
            }
            return RerunErrorsOutcome.NO_ERRORS
        }

        cancelItemJobs()
        _throttledKeys.value = emptySet()
        // One atomic StateFlow update: drop stale items, flip the
        // rerun set back to PENDING.
        _run.update { r ->
            r?.copy(items = r.items
                .filterKeys { it !in staleKeys }
                .mapValues { (k, st) ->
                    if (k in toRerunKeys) st.copy(status = TestStatus.PENDING) else st
                })
        }
        if (staleKeys.isNotEmpty()) {
            AppLog.i("ModelTest", "↻ rerunErrors dropped ${staleKeys.size} stale items")
        }
        AppLog.i("ModelTest", "↻ rerunErrors ${toRerunKeys.size} previously-failed models")
        dispatch(context, toRerunKeys.toList())
        return RerunErrorsOutcome.RESTARTED
    }

    /** Launch the outer coroutine that runs per-item workers for
     *  [keys] against the current `_run`. Shared by [startRun] (fresh
     *  list), [resumeRun] (unfinished items) and [rerunErrors] (reset
     *  FAILs). */
    private fun dispatch(context: Context, keys: List<ModelTestKey>) {
        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                withTracerTags(reportId = null, category = "Test all models") {
                    _run.value?.let { ModelTestRunStore.save(context, it) }
                    if (keys.isEmpty()) return@withTracerTags

                    val cap = appViewModel.uiState.value.generalSettings.maxTestApiCalls.coerceAtLeast(1)
                    val ioCap = Semaphore(cap)
                    val items = keys.mapNotNull { _run.value?.items?.get(it) }
                    val ordered = interleaveByHost(items) { item ->
                        AppService.findById(item.providerId)?.let { providerHost(it) } ?: ""
                    }
                    coroutineScope {
                        ordered.map { item ->
                            val deferred = async(start = CoroutineStart.LAZY) {
                                runOne(context, item.key, ioCap)
                            }
                            itemJobs[item.key] = deferred
                            deferred.invokeOnCompletion { itemJobs.remove(item.key, deferred) }
                            deferred.start()
                            deferred
                        }.awaitAll()
                    }
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
        /** Model types the sweep does not probe — no dispatch path in
         *  the app today. They're filtered out of the candidate list
         *  in [startRun] and the count in [testableProviders] so they
         *  don't show as "pending" or "failed". Types not in this set
         *  (CHAT, RESPONSES, EMBEDDING, RERANK, UNKNOWN, null) all
         *  have a working probe in [runOne]. */
        private val NON_TESTABLE_TYPES: Set<String> = setOf(
            ModelType.IMAGE, ModelType.TTS, ModelType.STT,
            ModelType.MODERATION, ModelType.CLASSIFY, ModelType.OCR
        )
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
                // Subtract test-excluded + non-testable model types so
                // the count matches what the sweep will actually probe.
                val n = config.models.count { m ->
                    if (m.isBlank()) return@count false
                    if (aiSettings.isTestExcluded(service.id, m)) return@count false
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
        AppLog.i("ModelTest", "✕ run cancelled")
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
    private suspend fun runOne(context: Context, key: ModelTestKey, ioCap: Semaphore) {
        val item = _run.value?.items?.get(key) ?: return
        val service = AppService.findById(item.providerId) ?: run {
            transition(key) { it.copy(status = TestStatus.FAIL, errorMessage = "Provider not registered") }
            _run.value?.items?.get(key)?.let { appViewModel.applyTestItemIncrement(it) }
            persist(context)
            return
        }
        val aiSettings = appViewModel.uiState.value.aiSettings
        val apiKey = aiSettings.getApiKey(service)
        val type = aiSettings.getModelType(service, item.model)
        ApiCallCaps.global.withPermit {
            ioCap.withPermit {
                val host = providerHost(service)
                val releaser = acquireOrRequeue(
                    host,
                    onThrottled = { _throttledKeys.update { it + key } },
                    onCleared = { _throttledKeys.update { it - key } }
                )
                try {
                    transition(key) { it.copy(status = TestStatus.RUNNING) }
                    val t0 = System.currentTimeMillis()
                    try {
                        // acquireOrRequeue already holds the per-host
                        // throttle permit — flag it so the OkHttp
                        // interceptor skips its own acquire. Without
                        // this the interceptor re-acquires on the same
                        // per-host semaphore and three same-host probes
                        // self-deadlock (each waits on a permit its own
                        // coroutine holds).
                        //
                        // suppressInlineRetry: a 429 / 529 here is a
                        // result worth recording, not something to
                        // sleep-and-retry — the sleeping retry loop
                        // would pin this ioCap permit for tens of
                        // seconds and stall the whole sweep.
                        val probe = withContext(
                            ProviderThrottle.permitPreAcquired.asContextElement(true) +
                                ProviderThrottle.suppressInlineRetry.asContextElement(true)
                        ) {
                            withTraceCategory("Test all models") {
                                when (type) {
                                    ModelType.EMBEDDING -> probeEmbedding(service, apiKey, item.model)
                                    ModelType.RERANK -> probeRerank(service, apiKey, item.model)
                                    // CHAT, RESPONSES, UNKNOWN, null →
                                    // the existing chat-completions probe.
                                    else -> probeChat(service, apiKey, item.model)
                                }
                            }
                        }
                        val durationMs = probe.durationMs.takeIf { it > 0 } ?: (System.currentTimeMillis() - t0)
                        val (inCost, outCost) = probe.tokenUsage?.let { usage ->
                            PricingCache.computeInOutCost(
                                usage, PricingCache.getPricing(context, service, item.model)
                            )
                        } ?: (0.0 to 0.0)
                        val trace = if (com.ai.data.ApiTracer.isTracingEnabled) {
                            com.ai.data.ApiTracer.getTraceFiles()
                                .firstOrNull { it.model == item.model && it.timestamp >= t0 }
                                ?.filename
                        } else null
                        transition(key) {
                            it.copy(
                                status = if (probe.isSuccess) TestStatus.PASS else TestStatus.FAIL,
                                errorMessage = probe.errorMessage,
                                responseText = probe.responseText,
                                durationMs = durationMs,
                                inputCost = inCost,
                                outputCost = outCost,
                                traceFilename = trace
                            )
                        }
                        // A passing probe means the model is healthy
                        // again — drop any stale >1h-429 cooldown so
                        // future calls aren't gratuitously dimmed.
                        if (probe.isSuccess) {
                            com.ai.data.ModelCooldownStore.remove(item.providerId, item.model)
                        }
                        // Live-sync this item's outcome into the
                        // Blocked / Test-excluded lists so the user
                        // sees entries appear as the sweep progresses.
                        // Disk flush happens once at end-of-run /
                        // cancel.
                        _run.value?.items?.get(key)?.let { appViewModel.applyTestItemIncrement(it) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        transition(key) {
                            it.copy(
                                status = TestStatus.FAIL,
                                errorMessage = e.message ?: "Connection error",
                                durationMs = System.currentTimeMillis() - t0
                            )
                        }
                        _run.value?.items?.get(key)?.let { appViewModel.applyTestItemIncrement(it) }
                    }
                } finally {
                    releaser.release()
                    _throttledKeys.update { it - key }
                    persist(context)
                }
            }
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
     *  Treats `HTTP 200 + completion_tokens > 0 + empty content` as
     *  *reachable*: OpenAI's gpt-5 / o-series via OpenRouter return
     *  encrypted `reasoning_details` and null content when the 64-token
     *  budget is exhausted on hidden reasoning. The model IS responsive
     *  on its endpoint; only the *visible* output is empty. The probe's
     *  job is reachability, not "did the model produce useful text". */
    private suspend fun probeChat(service: AppService, apiKey: String, model: String): ProbeResult {
        val t0 = System.currentTimeMillis()
        val r = appViewModel.repository.analyze(
            service, apiKey, AnalysisRepository.TEST_PROMPT, model,
            params = AgentParameters(maxTokens = AnalysisRepository.TEST_MAX_TOKENS)
        )
        val outputTokens = r.tokenUsage?.outputTokens ?: 0
        val reachableViaReasoning = !r.isSuccess && r.httpStatusCode == 200 && outputTokens > 0
        return ProbeResult(
            isSuccess = r.isSuccess || reachableViaReasoning,
            errorMessage = if (reachableViaReasoning) null else r.error,
            responseText = r.analysis
                ?: if (reachableViaReasoning) "Reachable — emitted $outputTokens reasoning tokens, no visible content" else null,
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
