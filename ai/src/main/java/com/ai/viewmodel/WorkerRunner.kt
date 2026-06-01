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
 * list is a fallback chain. Selection is **random per call** — the worker
 * order is shuffled each run, so the primary pick (and the fallback order
 * after a miss) is random rather than a deterministic rotation. On a **429**
 * the worker is parked on a short cooldown (the response's `Retry-After`, else
 * [WORKER_429_DEFAULT_MS] = 5 s) and the next worker is tried; a non-429 /
 * logical miss just advances.
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

    /** workerKey -> epoch-ms the worker becomes selectable again (local 429). */
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    /** workerKey of workers taken out of rotation for the rest of this session
     *  because the model itself is gone — HTTP 404 ("model does not exist") /
     *  410 ("gone"). Unlike the 429 [cooldownUntil] this never expires on its
     *  own: a retired or mistyped model won't come back mid-session, so
     *  re-picking it on every pair just burns a call (and a slot in that
     *  provider's rate window) on a guaranteed miss. Cleared only when the
     *  WorkerRunner is recreated — i.e. after the user fixes the worker config
     *  or restarts. Shared across every worker flow on this instance (fan-meta,
     *  model-titles, model-icons), so one dead model is disabled everywhere. */
    private val disabledWorkers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** A response whose status says the *model* is unusable (not a transient
     *  rate-limit): 404 (does not exist) or 410 (gone). Checks the status code
     *  and the error-string form, mirroring the 429 detection. */
    private fun isModelGone(resp: AnalysisResponse): Boolean =
        resp.httpStatusCode == 404 || resp.httpStatusCode == 410 ||
            resp.error?.contains("API error: 404") == true ||
            resp.error?.contains("API error: 410") == true

    private fun workerKey(w: Worker): String =
        if (w.agent != "*N/A" && w.agent.isNotBlank() && w.agent != "*select")
            "agent:${w.agent.lowercase()}"
        else "pm:${w.provider}:${w.model}"

    /** Run one workers prompt's chain with [resolvedText] as the call body
     *  (placeholders already substituted by the caller).
     *
     *  [accept] validates a worker's *successful* (HTTP-200) response for the
     *  artifact the caller actually needs — a parseable emoji, a non-blank
     *  title, a detected language, etc. A 200 that fails [accept] is a
     *  **logical miss**: the worker produced no usable result, so the chain
     *  advances to the next worker exactly as it does for a transport miss,
     *  instead of returning a hollow Success the caller then has to paper over
     *  with a fallback. Defaults to accepting any success (legacy behaviour). */
    suspend fun run(
        prompt: InternalPrompt,
        resolvedText: String,
        aiSettings: Settings,
        context: Context,
        accept: (AnalysisResponse) -> Boolean = { true },
    ): WorkerOutcome {
        // Expand each worker into the per-member plain workers we actually
        // run: a Flock contributes one worker per member agent, a Swarm one
        // per (provider, model), a Model / Agent worker just itself. So a
        // flock/swarm's members each become an independent fallback
        // candidate (own cooldown key, own attribution).
        val members = prompt.workers.flatMap { aiSettings.expandWorker(it) }
        if (members.isEmpty()) {
            AppLog.w("Workers", "prompt '${prompt.name}' has no runnable workers — nothing to run")
            return WorkerOutcome.Failed
        }
        val n = members.size
        // Random pick (not round-robin): shuffle the worker order each call
        // so the primary choice — and the fallback order after a cooldown /
        // 429 / logical miss — is random rather than a deterministic rotation.
        val order = members.indices.shuffled()
        var sawRateLimit = false

        for (idx in order) {
            val w = members[idx]
            val key = workerKey(w)
            // Permanently out of order this session (model gone) — skip with no
            // call. NOT counted as a rate-limit: a dead worker isn't "try later".
            if (key in disabledWorkers) continue
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
                resp.isSuccess && accept(resp) -> {
                    AppLog.i("Workers", "${com.ai.data.MetadataIconsHolder.current.checkMark} '${prompt.name}' via ${agent.name} (worker ${idx + 1}/$n)")
                    return WorkerOutcome.Success(resp, w)
                }
                resp.httpStatusCode == 429 || resp.error?.contains("API error: 429") == true -> {
                    val waitMs = retryAfterFromHeaderBlock(resp.httpHeaders) ?: WORKER_429_DEFAULT_MS
                    cooldownUntil[key] = System.currentTimeMillis() + waitMs
                    sawRateLimit = true
                    AppLog.w("Workers", "429 '${prompt.name}' via ${agent.name} — cooling ${waitMs}ms, next worker")
                }
                // Model gone (404 does-not-exist / 410 retired) — not transient.
                // Take the worker out of rotation for the rest of the session so
                // it stops wasting a call (and a provider rate slot) on every
                // pair. The chain still falls through to the next worker now.
                isModelGone(resp) -> {
                    disabledWorkers.add(key)
                    AppLog.w("Workers", "${resp.httpStatusCode ?: "model-gone"} '${prompt.name}' via ${agent.name} — model unavailable, disabling this worker for the session")
                }
                // HTTP-200 but no usable artifact (e.g. a reply with no emoji /
                // no title) — a logical miss; fall through to the next worker
                // just like a transport error rather than accepting it.
                resp.isSuccess -> AppLog.w("Workers", "no usable result '${prompt.name}' via ${agent.name} — next worker")
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
        accept: (AnalysisResponse) -> Boolean = { true },
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
            onResult(item, run(pair.first, pair.second, aiSettings, context, accept))
        }
    }
}
