package com.ai.viewmodel

import android.content.Context
import com.ai.data.AnalysisResponse
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.ModelCooldownStore
import com.ai.data.ProviderThrottle
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
    /** Every candidate stayed rate-limited (429) or on cooldown even after
     *  the chain waited the cooldowns out and retried — try later. */
    data object AllRateLimited : WorkerOutcome()
    /** Workers were exhausted with non-429 failures (or none resolvable). */
    data object Failed : WorkerOutcome()
}

/** How a worker-pool call picks its primary worker (and the fallback
 *  order after a miss): [Random] shuffles per call — the historical
 *  "when available" behaviour where fast models absorb more work —
 *  while [RoundRobin] deals consecutive calls to consecutive workers so
 *  every pool member gets ~the same share. A slow worker keeps its
 *  share (its items wait; nothing reassigns on slowness); 429 / 404 /
 *  error fall-through still advances an item to the next worker in
 *  rotation, so an *erroring* worker may end up with less work. */
sealed class WorkerSchedule {
    data object Random : WorkerSchedule()
    /** [key] groups calls that share one rotation — the report id. */
    data class RoundRobin(val key: String) : WorkerSchedule()
}

/** Process-wide round-robin cursors keyed by rotation key (report id).
 *  Shared by [WorkerRunner] (batch items) and
 *  SecondaryRunManager.runSecondaryViaSwarm (single-call Meta / Fan-in /
 *  Rerank / Moderation) so every covered call of one report advances the
 *  same rotation. Monotonic, never reset; the map is tiny (one int per
 *  report that ever ran a round-robin call this session). */
internal object WorkerRotation {
    private val counters = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    fun next(key: String): Int =
        counters.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger(0) }.getAndIncrement()
}

/**
 * Executes a "workers"-category [InternalPrompt]: its [InternalPrompt.workers]
 * list is a fallback chain. Selection defaults to **random per call** — the
 * worker order is shuffled each run, so the primary pick (and the fallback
 * order after a miss) is random; a [WorkerSchedule.RoundRobin] schedule
 * instead deals consecutive calls to consecutive workers. On a **429**
 * the worker is parked on a short cooldown (the response's `Retry-After`, else
 * [WORKER_429_DEFAULT_MS] = 5 s) and the next worker is tried; a non-429 /
 * logical miss just advances. A pass where EVERY candidate was cooling waits
 * for the earliest cooldown to lift and re-runs the chain (bounded — see
 * [ALL_RATE_LIMITED_MAX_RETRIES]) before settling on
 * [WorkerOutcome.AllRateLimited].
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

    private companion object {
        /** Extra chain passes after a pass where every candidate was cooling.
         *  A fully-cooling pass is usually a 5 s blip (the local 429 cooldown),
         *  not a real failure — erroring the item on the first such pass
         *  contradicts the pool design ("a failed call can be replaced by
         *  another worker"), so the chain waits the cooldown out and retries. */
        const val ALL_RATE_LIMITED_MAX_RETRIES = 3
        /** Longest single wait worth taking. An earliest-wake further out than
         *  this means the pool is on a real bench (Google's hours-long
         *  exhausted-quota cooldown) — give up immediately instead of holding
         *  the batch permits for nothing. */
        const val ALL_RATE_LIMITED_MAX_WAIT_MS = 20_000L
        /** Floor for one wait, plus up-to-500ms jitter so concurrent items
         *  woken by the same cooldown expiry don't stampede one worker. */
        const val ALL_RATE_LIMITED_MIN_WAIT_MS = 250L
    }

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
     *  with a fallback. Defaults to accepting any success (legacy behaviour).
     *
     *  [schedule] picks the worker order: [WorkerSchedule.Random] (default)
     *  shuffles per call; [WorkerSchedule.RoundRobin] starts at the shared
     *  rotation cursor for its key and walks the pool in order, so a batch
     *  deals items out evenly. */
    suspend fun run(
        prompt: InternalPrompt,
        resolvedText: String,
        aiSettings: Settings,
        context: Context,
        schedule: WorkerSchedule = WorkerSchedule.Random,
        /** Run-only parameter override (F48) — e.g. a per-run temperature
         *  from the launch screen; applied on top of the worker's own
         *  resolution for every candidate in the chain. Declared BEFORE
         *  [accept] so trailing-lambda call sites keep binding to accept. */
        overrideParams: com.ai.data.AgentParameters? = null,
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
        // Pool-cooling retry loop: a pass where EVERY candidate was skipped or
        // parked on a rate-limit isn't a real failure — the local 429 cooldown
        // defaults to 5 s. Instead of returning AllRateLimited on the first
        // such pass (which the batch engines stamp as a terminal error), wait
        // for the earliest cooldown to lift and re-run the chain, bounded by
        // ALL_RATE_LIMITED_MAX_RETRIES extra passes and capped at
        // ALL_RATE_LIMITED_MAX_WAIT_MS per wait (a wake further out means a
        // real bench — hours, not a blip — so waiting is pointless). Under a
        // throttled batch the wait runs PERMIT-FREE: the batch layer installs
        // ProviderThrottle.poolCoolingWaiter, which releases the item's
        // sub-cap + global permits for the sleep and re-acquires them before
        // the next pass — so items waiting out a cooling pool don't throttle
        // the rest of the batch. Outside a batch it's a plain delay.
        // Round-robin start index, fixed ONCE per run() call (not per pass):
        // the all-cooling retry passes below re-walk the SAME rotated order
        // without advancing the shared cursor, so a wait-and-retry doesn't
        // skew the rotation.
        val rotationStart = (schedule as? WorkerSchedule.RoundRobin)?.let { WorkerRotation.next(it.key) % n }
        var pass = 0
        while (true) {
            var sawRateLimit = false
            // Earliest epoch-ms any rate-limited candidate becomes selectable
            // again this pass — drives the retry wait.
            var earliestWakeMs = Long.MAX_VALUE
            fun noteWake(at: Long?) {
                if (at != null && at > System.currentTimeMillis() && at < earliestWakeMs) earliestWakeMs = at
            }
            // Random schedule: shuffle each pass, so the primary choice — and
            // the fallback order after a cooldown / 429 / logical miss — is
            // random. RoundRobin: deterministic rotation from the shared
            // cursor; fall-through walks the next workers in rotation order.
            val order = if (rotationStart != null) List(n) { (rotationStart + it) % n }
            else members.indices.shuffled()

            for (idx in order) {
                val w = members[idx]
                val key = workerKey(w)
                // Permanently out of order this session (model gone) — skip with no
                // call. NOT counted as a rate-limit: a dead worker isn't "try later".
                if (key in disabledWorkers) continue
                val localUntil = cooldownUntil[key] ?: 0L
                if (localUntil > System.currentTimeMillis()) { sawRateLimit = true; noteWake(localUntil); continue }
                val raw = aiSettings.resolveWorker(w) ?: continue
                val effModel = aiSettings.getEffectiveModelForAgent(raw)
                if (ModelCooldownStore.isUnavailable(raw.provider.id, effModel)) {
                    sawRateLimit = true
                    noteWake(ModelCooldownStore.availableAt(raw.provider.id, effModel))
                    continue
                }

                val agent = raw.copy(
                    apiKey = aiSettings.getEffectiveApiKeyForAgent(raw),
                    model = effModel
                )
                val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                val resp = appViewModel.repository.analyzeWithAgent(
                    agent, "", resolvedText, overrideParams = overrideParams,
                    context = context, baseUrl = baseUrl, retry = false
                )
                when {
                    resp.isSuccess && accept(resp) -> {
                        AppLog.i("Workers", "${com.ai.data.MetadataIconsHolder.current.checkMark} '${prompt.name}' via ${agent.name} (worker ${idx + 1}/$n)")
                        return WorkerOutcome.Success(resp, w)
                    }
                    resp.httpStatusCode == 429 || resp.error?.contains("API error: 429") == true -> {
                        val waitMs = retryAfterFromHeaderBlock(resp.httpHeaders) ?: WORKER_429_DEFAULT_MS
                        val until = System.currentTimeMillis() + waitMs
                        cooldownUntil[key] = until
                        sawRateLimit = true
                        noteWake(until)
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
            if (!sawRateLimit) return WorkerOutcome.Failed
            pass++
            val waitMs = if (earliestWakeMs == Long.MAX_VALUE) WORKER_429_DEFAULT_MS
                else earliestWakeMs - System.currentTimeMillis()
            if (pass > ALL_RATE_LIMITED_MAX_RETRIES || waitMs > ALL_RATE_LIMITED_MAX_WAIT_MS) {
                return WorkerOutcome.AllRateLimited
            }
            val delayMs = waitMs.coerceAtLeast(ALL_RATE_LIMITED_MIN_WAIT_MS) + (0L..500L).random()
            AppLog.i("Workers", "'${prompt.name}' all workers cooling — retrying in ${delayMs}ms (pass $pass/$ALL_RATE_LIMITED_MAX_RETRIES)")
            val waiter = ProviderThrottle.poolCoolingWaiter.get()
            if (waiter != null) waiter(delayMs) else kotlinx.coroutines.delay(delayMs)
        }
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
            onResult(item, run(pair.first, pair.second, aiSettings, context, accept = accept))
        }
    }
}
