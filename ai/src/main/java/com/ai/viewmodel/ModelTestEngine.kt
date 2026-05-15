package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.AnalysisRepository
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.ModelTestKey
import com.ai.data.ModelTestRunState
import com.ai.data.ModelTestRunStore
import com.ai.data.ModelTestState
import com.ai.data.PricingCache
import com.ai.data.TestStatus
import com.ai.data.analyze
import com.ai.data.modelTestKey
import com.ai.data.withTraceCategory
import com.ai.data.withTracerTags
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        // Cancel any leftover per-item coroutines from a prior run.
        itemJobs.values.forEach { it.cancel() }
        itemJobs.clear()
        _throttledKeys.value = emptySet()

        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                withTracerTags(reportId = null, category = "Test all models") {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val cap = state.generalSettings.maxTestApiCalls.coerceAtLeast(1)

                    // Build the candidate list: every model of every
                    // active provider. Dedupe defensively by key.
                    val items = LinkedHashMap<ModelTestKey, ModelTestState>()
                    for (service in aiSettings.getActiveServices()) {
                        if (service.id !in providerIds) continue
                        val config = aiSettings.getProvider(service)
                        if (config.apiKey.isBlank()) continue
                        for (model in config.models) {
                            if (model.isBlank()) continue
                            val key = modelTestKey(service.id, model)
                            if (key in items) continue
                            items[key] = ModelTestState(
                                key = key,
                                providerId = service.id,
                                model = model,
                                status = TestStatus.PENDING
                            )
                        }
                    }

                    val fresh = ModelTestRunState(startedAt = System.currentTimeMillis(), items = items)
                    _run.value = fresh
                    ModelTestRunStore.save(context, fresh)
                    AppLog.i("ModelTest", "→ startRun ${items.size} models, cap=$cap")

                    if (items.isEmpty()) return@withTracerTags

                    val ioCap = Semaphore(cap)
                    val ordered = interleaveByHost(items.values.toList()) { item ->
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
                    AppLog.i("ModelTest", "← startRun done (${items.size} models)")
                }
            } finally {
                _throttledKeys.value = emptySet()
                runJob = null
                _run.value?.let { ModelTestRunStore.save(context, it) }
            }
        }
        runJob = outer
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
                service to config.models.count { it.isNotBlank() }
            }
    }

    /** Stop the in-flight run. Cancels the outer + every per-item
     *  coroutine, then marks anything not yet finished as a cancelled
     *  failure so the persisted run stays a consistent, complete
     *  snapshot (and L1's count-based "running" flag goes false). */
    fun cancel(context: Context) {
        runJob?.cancel()
        runJob = null
        itemJobs.values.forEach { it.cancel() }
        itemJobs.clear()
        _throttledKeys.value = emptySet()
        _run.update { run ->
            run?.copy(items = run.items.mapValues { (_, st) ->
                if (st.status == TestStatus.PENDING || st.status == TestStatus.RUNNING)
                    st.copy(status = TestStatus.FAIL, errorMessage = "Cancelled")
                else st
            })
        }
        _run.value?.let { ModelTestRunStore.save(context, it) }
        AppLog.i("ModelTest", "✕ run cancelled")
    }

    /** Drop the in-memory run + cancel any in-flight work. Paired with
     *  [ModelTestRunStore.delete] on the Clear-runtime-data path so the
     *  L1 screen reflects the wipe without an app restart. */
    fun clearRun() {
        runJob?.cancel()
        runJob = null
        itemJobs.values.forEach { it.cancel() }
        itemJobs.clear()
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
            persist(context)
            return
        }
        val apiKey = appViewModel.uiState.value.aiSettings.getApiKey(service)
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
                        val response = withTraceCategory("Test all models") {
                            appViewModel.repository.analyze(
                                service, apiKey, AnalysisRepository.TEST_PROMPT, item.model
                            )
                        }
                        val durationMs = System.currentTimeMillis() - t0
                        val (inCost, outCost) = response.tokenUsage?.let { usage ->
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
                                status = if (response.isSuccess) TestStatus.PASS else TestStatus.FAIL,
                                errorMessage = response.error,
                                responseText = response.analysis,
                                durationMs = durationMs,
                                inputCost = inCost,
                                outputCost = outCost,
                                traceFilename = trace
                            )
                        }
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
                    }
                } finally {
                    releaser.release()
                    _throttledKeys.update { it - key }
                    persist(context)
                }
            }
        }
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
