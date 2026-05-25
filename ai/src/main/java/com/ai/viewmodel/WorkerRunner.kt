package com.ai.viewmodel

import android.content.Context
import com.ai.data.AnalysisResponse
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.ModelCooldownStore
import com.ai.data.WORKER_429_DEFAULT_MS
import com.ai.data.retryAfterFromHeaderBlock
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.model.Worker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Outcome of running one "workers"-category prompt's chain. */
sealed class WorkerOutcome {
    /** A worker produced a successful response. */
    data class Success(val response: AnalysisResponse, val worker: Worker) : WorkerOutcome()
    /** Every candidate was rate-limited (429) or on cooldown — try later. */
    data object AllRateLimited : WorkerOutcome()
    /** Workers were exhausted with non-429 failures (or none resolvable). */
    data object Failed : WorkerOutcome()
}

/**
 * Executes a "workers"-category [InternalPrompt]: its [InternalPrompt.workers]
 * list is an ordered fallback chain. Selection is **round-robin per prompt** —
 * each call starts at the next worker than the previous call — then walks the
 * remaining workers in order (wrapping). On a **429** the worker is parked on a
 * short cooldown (the response's `Retry-After`, else [WORKER_429_DEFAULT_MS] =
 * 5 s) and the next worker is tried; a non-429 miss just advances.
 *
 * Calls go through [com.ai.data.AnalysisRepository.analyzeWithAgent] with
 * `retry = false`, so the engine owns the fallback while the shared OkHttp
 * stack still applies per-provider throttle. It also honours the global
 * [ModelCooldownStore] (a worker whose model is benched app-wide is skipped).
 *
 * [runWorkerBatch] runs a list of items through the chain under the shared
 * [ApiCallCaps.workers] cap in `dynamicHost` mode (each worker call
 * self-throttles its own provider host — see [runThrottledBatch]). No feature
 * calls this yet; it's the reusable foundation batches will be converted onto.
 */
class WorkerRunner(private val appViewModel: AppViewModel) {

    /** promptId -> rotating start index (in-memory, per session). */
    private val rotation = ConcurrentHashMap<String, AtomicInteger>()
    /** workerKey -> epoch-ms the worker becomes selectable again (local 429). */
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    private fun workerKey(w: Worker): String =
        if (w.agent != "*N/A" && w.agent.isNotBlank() && w.agent != "*select")
            "agent:${w.agent.lowercase()}"
        else "pm:${w.provider}:${w.model}"

    /** Run one workers prompt's chain with [resolvedText] as the call body
     *  (placeholders already substituted by the caller). */
    suspend fun run(
        prompt: InternalPrompt,
        resolvedText: String,
        aiSettings: Settings,
        context: Context,
    ): WorkerOutcome {
        val workers = prompt.workers
        if (workers.isEmpty()) {
            AppLog.w("Workers", "prompt '${prompt.name}' has no workers — nothing to run")
            return WorkerOutcome.Failed
        }
        val n = workers.size
        val start = rotation.getOrPut(prompt.id) { AtomicInteger(0) }.getAndIncrement().mod(n)
        var sawRateLimit = false

        for (off in 0 until n) {
            val w = workers[(start + off).mod(n)]
            val key = workerKey(w)
            if ((cooldownUntil[key] ?: 0L) > System.currentTimeMillis()) { sawRateLimit = true; continue }
            val raw = aiSettings.resolveWorker(w) ?: continue
            val effModel = aiSettings.getEffectiveModelForAgent(raw)
            if (ModelCooldownStore.isUnavailable(raw.provider.id, effModel)) { sawRateLimit = true; continue }

            val agent = raw.copy(
                apiKey = aiSettings.getEffectiveApiKeyForAgent(raw),
                model = effModel
            )
            val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
            val resp = appViewModel.repository.analyzeWithAgent(
                agent, "", resolvedText, context = context, baseUrl = baseUrl, retry = false
            )
            when {
                resp.isSuccess -> {
                    AppLog.i("Workers", "✓ '${prompt.name}' via ${agent.name} (worker ${(start + off).mod(n) + 1}/$n)")
                    return WorkerOutcome.Success(resp, w)
                }
                resp.httpStatusCode == 429 || resp.error?.contains("API error: 429") == true -> {
                    val waitMs = retryAfterFromHeaderBlock(resp.httpHeaders) ?: WORKER_429_DEFAULT_MS
                    cooldownUntil[key] = System.currentTimeMillis() + waitMs
                    sawRateLimit = true
                    AppLog.w("Workers", "429 '${prompt.name}' via ${agent.name} — cooling ${waitMs}ms, next worker")
                }
                else -> AppLog.w("Workers", "miss '${prompt.name}' via ${agent.name}: ${resp.error?.take(80)}")
            }
        }
        return if (sawRateLimit) WorkerOutcome.AllRateLimited else WorkerOutcome.Failed
    }

    /** Run [items] through the worker chain under the shared worker cap.
     *  Dynamic-host: each worker call acquires its own provider permit, so
     *  the chain can span providers without the fixed-host deadlock guard.
     *  [resolve] maps an item to its (workers prompt, resolved text); an
     *  item resolving to null is skipped. [onResult] receives each outcome. */
    suspend fun <T> runWorkerBatch(
        items: List<T>,
        aiSettings: Settings,
        context: Context,
        resolve: (T) -> Pair<InternalPrompt, String>?,
        onThrottled: (T) -> Unit = {},
        onCleared: (T) -> Unit = {},
        onResult: (T, WorkerOutcome) -> Unit = { _, _ -> },
    ) {
        runThrottledBatch(
            items = items,
            hostOf = { null },
            subCap = ApiCallCaps.workers,
            onThrottled = onThrottled,
            onCleared = onCleared,
            dynamicHost = true,
        ) { item ->
            val pair = resolve(item) ?: return@runThrottledBatch
            onResult(item, run(pair.first, pair.second, aiSettings, context))
        }
    }
}
