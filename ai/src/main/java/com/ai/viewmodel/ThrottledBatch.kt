package com.ai.viewmodel

import com.ai.data.ApiCallCaps
import com.ai.data.ProviderThrottle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The one place that owns the app's concurrent-batch throttle contract,
 * shared by every "fire N API calls, throttled" flow (Test all models,
 * Fan-out, Fan-in, Fan-icons, Fan-titles). Hand-rolling this six times
 * is what let the acquisition order drift and deadlock — keeping it here
 * means the order is defined exactly once.
 *
 * For each item it acquires, in this **canonical order** (the same as
 * Reports / Translation), then runs [body]:
 *
 *   [ApiCallCaps.global]  →  [subCap]  →  per-host ([acquireOrRequeue])
 *
 * `global` outermost and the per-host gate innermost is what prevents the
 * global↔per-host lock-ordering deadlock. [acquireOrRequeue] is the
 * suspending per-host gate (concurrency + per-minute window, yields via
 * `delay` instead of pinning an IO thread) — it already enforces the
 * per-host concurrency cap, so callers no longer need a separate per-host
 * `Semaphore`. While [body] runs, `ProviderThrottle.permitPreAcquired` is
 * set so the OkHttp `ProviderThrottleInterceptor` skips re-acquiring the
 * same per-host permit (which would otherwise self-deadlock same-host
 * calls).
 *
 * Concurrency shape mirrors the loops it replaces: items are spread with
 * [interleaveByHost], each runs in its own `async(LAZY)` registered via
 * [register] **before** `start()` (preserving the cancel-before-delete
 * race fix), and the whole batch is awaited in a [coroutineScope] — so a
 * body that throws cancels its siblings, exactly as before.
 *
 * Must be called from inside a coroutine (it suspends on `awaitAll`).
 *
 * @param items     the work items.
 * @param hostOf    resolves an item's provider host; an item that returns
 *                  null is skipped (no host to throttle against).
 * @param subCap    the per-flow cap (`ApiCallCaps.fanOut` / `fanIcons`, or
 *                  a flow-private `Semaphore` like Test-all's ioCap).
 * @param onThrottled / onCleared  per-item UI marks while the host gate
 *                  makes the item wait / clears it.
 * @param register  records each item's [Deferred] before it starts, for
 *                  cancellation maps. Called on the launching coroutine.
 * @param timeoutMs optional per-item ceiling (Test-all uses 60_000) so a
 *                  hung call can't pin its permits forever. Wrapped inside
 *                  the permits; [body] should treat a
 *                  TimeoutCancellationException as a failed item.
 * @param body      the per-item work (the network call + its own persist /
 *                  status / deleted-check logic). Runs with the permits
 *                  held and `permitPreAcquired = true`.
 */
internal suspend fun <T> runThrottledBatch(
    items: List<T>,
    hostOf: (T) -> String?,
    subCap: Semaphore,
    onThrottled: (T) -> Unit = {},
    onCleared: (T) -> Unit = {},
    register: (T, Deferred<*>) -> Unit = { _, _ -> },
    timeoutMs: Long? = null,
    body: suspend (T) -> Unit,
) {
    if (items.isEmpty()) return
    coroutineScope {
        interleaveByHost(items) { hostOf(it) }.map { item ->
            val deferred = async(start = CoroutineStart.LAZY) {
                val host = hostOf(item) ?: return@async
                ApiCallCaps.global.withPermit {
                    subCap.withPermit {
                        val releaser = acquireOrRequeue(
                            host,
                            onThrottled = { onThrottled(item) },
                            onCleared = { onCleared(item) }
                        )
                        try {
                            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                if (timeoutMs != null) withTimeout(timeoutMs) { body(item) }
                                else body(item)
                            }
                        } finally {
                            releaser.release()
                        }
                    }
                }
            }
            register(item, deferred)
            deferred.start()
            deferred
        }.awaitAll()
    }
}
