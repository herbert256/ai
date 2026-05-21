package com.ai.data

import android.content.Context
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch

/** Set the trace category for the duration of [block].
 *
 *  Propagation: backed by [kotlinx.coroutines.asContextElement] so the
 *  ThreadLocal value travels with the coroutine across dispatcher
 *  hops. A plain `ThreadLocal.set` here was load-bearing-incorrect for
 *  chat streaming — the chat collector wraps the flow in
 *  `withTraceCategory("Chat")`, but the upstream HTTP call runs under
 *  `flowOn(Dispatchers.IO)`, so the IO worker that actually executes
 *  the OkHttp call never saw the ThreadLocal set on the collector
 *  thread, and the trace landed without a category. Mirrors
 *  [withTracerTags]. */
suspend fun <R> withTraceCategory(category: String, block: suspend () -> R): R {
    val tl = ApiTracer.currentTags
    val previous = tl.get() ?: ApiTracer.TraceTags(null, null, null)
    val newTags = previous.copy(category = category)
    return kotlinx.coroutines.withContext(tl.asContextElement(newTags)) {
        block()
    }
}

/** Run [block] with a per-flow sink that [TracingInterceptor]
 *  populates with the filename of the trace it writes. Lets the
 *  originating coroutine grab the trace of the call it just made —
 *  e.g. to tag a fan-out tier-1 emoji miss. Backed by a ThreadLocal
 *  + [asContextElement], carried onto OkHttp workers by
 *  [TagPropagatingExecutor]; the AtomicReference is shared by
 *  reference so the worker's write lands back here. */
suspend fun <R> withTraceFilenameSink(
    sink: java.util.concurrent.atomic.AtomicReference<String?>,
    block: suspend () -> R
): R = kotlinx.coroutines.withContext(ApiTracer.traceFilenameSink.asContextElement(sink)) {
    block()
}

/** Push (reportId, category) onto the per-thread tag pair for the
 *  duration of [block], restoring both on exit. A null argument leaves
 *  that side untouched (useful for flows that only set one of the two).
 *
 *  Propagation: backed by a ThreadLocal plus a
 *  [kotlinx.coroutines.asContextElement] context element so the
 *  ThreadLocal is set on *every* thread the coroutine resumes on, not
 *  just the one that initially called this function. Without the
 *  context element a sibling `async { … }` inside [block] that
 *  dispatches onto a different IO worker thread would not see the
 *  thread-local, and any OkHttp call it submits would land with
 *  reportId=null — that's exactly why report fan-outs (meta /
 *  fan-out / fan-in / translate) used to drop traces from "Report
 *  scope" on some fraction of their per-pair calls.
 *
 *  [TagPropagatingExecutor] still snapshots tags onto OkHttp's worker
 *  threads at submission time; the context element guarantees the
 *  submission thread (the coroutine's current dispatcher thread) has
 *  the right value to snapshot.
 *
 *  Inline + suspend, so non-local `return@withTracerTags` from the
 *  block continues to work and we don't pay an allocation on every
 *  per-task wrap. */
suspend fun <R> withTracerTags(
    reportId: String? = null,
    category: String? = null,
    runId: String? = null,
    block: suspend () -> R
): R {
    val tl = ApiTracer.currentTags
    val previous = tl.get() ?: ApiTracer.TraceTags(null, null, null)
    val newTags = ApiTracer.TraceTags(
        reportId = reportId ?: previous.reportId,
        category = category ?: previous.category,
        runId = runId ?: previous.runId
    )
    return kotlinx.coroutines.withContext(tl.asContextElement(newTags)) {
        block()
    }
}

/** Executor wrapper that captures [ApiTracer.currentTags] at submission
 *  time on the calling thread and restores it on the worker thread
 *  for the duration of each Runnable. Plug into OkHttp's Dispatcher
 *  so application interceptors (TracingInterceptor in particular)
 *  read the tags of the call's originating flow rather than whatever
 *  another concurrent flow happened to set last on the global pair.
 *
 *  Edge case: a queued OkHttp call promoted later (when a per-host
 *  slot frees up) is submitted to this executor from the worker
 *  thread completing the previous call, not the original caller.
 *  Tags for that call attribute to the previous worker's flow rather
 *  than the original caller — acceptable for the rare per-host-cap
 *  scenario; a fully race-free fix would require per-Call.tag
 *  attachment at OkHttp Call construction time. */
class TagPropagatingExecutor(
    private val delegate: java.util.concurrent.ExecutorService
) : java.util.concurrent.AbstractExecutorService() {
    override fun execute(command: Runnable) {
        val captured = ApiTracer.currentTags.get()
        // Also capture ProviderThrottle.permitPreAcquired so the
        // ProviderThrottleInterceptor on the worker can tell whether
        // the calling coroutine already acquired a per-provider
        // permit. Without this the worker would always read false
        // (the worker's ThreadLocal default) and double-acquire on
        // Fan-out pairs.
        val capturedPreAcquired = ProviderThrottle.permitPreAcquired.get() == true
        // Carry the suppress-inline-retry flag the same way so the
        // retry interceptors on the worker see the originating flow's
        // intent (the "Test all models" sweep skips the sleeping retry
        // loops).
        val capturedSuppressRetry = ProviderThrottle.suppressInlineRetry.get() == true
        // Carry the trace-filename sink (if any) onto the worker so
        // TracingInterceptor's save can hand the filename back to the
        // originating coroutine.
        val capturedSink = ApiTracer.traceFilenameSink.get()
        if (captured?.reportId != null || captured?.category != null) {
            AppLog.v("TagPropagation", "submit reportId=${captured.reportId} cat=${captured.category}")
        }
        delegate.execute {
            val previousTags = ApiTracer.currentTags.get()
            val previousPreAcquired = ProviderThrottle.permitPreAcquired.get() == true
            val previousSuppressRetry = ProviderThrottle.suppressInlineRetry.get() == true
            val previousSink = ApiTracer.traceFilenameSink.get()
            ApiTracer.currentTags.set(captured)
            if (capturedPreAcquired) ProviderThrottle.permitPreAcquired.set(true)
            if (capturedSuppressRetry) ProviderThrottle.suppressInlineRetry.set(true)
            ApiTracer.traceFilenameSink.set(capturedSink)
            try {
                command.run()
            } finally {
                ApiTracer.currentTags.set(previousTags)
                if (capturedPreAcquired) ProviderThrottle.permitPreAcquired.set(previousPreAcquired)
                if (capturedSuppressRetry) ProviderThrottle.suppressInlineRetry.set(previousSuppressRetry)
                ApiTracer.traceFilenameSink.set(previousSink)
            }
        }
    }
    override fun shutdown() = delegate.shutdown()
    override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
    override fun isShutdown(): Boolean = delegate.isShutdown
    override fun isTerminated(): Boolean = delegate.isTerminated
    override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
        delegate.awaitTermination(timeout, unit)
}
