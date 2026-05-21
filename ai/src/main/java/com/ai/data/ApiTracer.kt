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

/**
 * Singleton managing API trace storage as JSON files under trace/ directory.
 */
object ApiTracer {
    private const val TRACE_DIR = "trace"
    private var traceDir: File? = null
    private val gson = createAppGson(prettyPrint = true)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.US).withZone(ZoneId.systemDefault())
    private val lock = ReentrantLock()
    private val fileSequence = AtomicLong(0)
    @Volatile var isTracingEnabled: Boolean = false

    /** Per-thread tag pair carried across the OkHttp dispatcher
     *  boundary by [TagPropagatingExecutor]. Each top-level flow
     *  (chat send, report meta call, agent test, …) brackets its
     *  outbound calls with [withTracerTags] which sets this for the
     *  duration of the block. Concurrent flows on different threads
     *  no longer race a process-wide @Volatile var — each gets its
     *  own ThreadLocal value, copied onto the dispatcher worker
     *  thread when the OkHttp Call's Runnable is submitted. */
    @PublishedApi internal data class TraceTags(val reportId: String?, val category: String?, val runId: String? = null)
    @PublishedApi internal val currentTags: ThreadLocal<TraceTags> = ThreadLocal.withInitial { TraceTags(null, null, null) }

    /** Public accessors — kept for binding-compatibility with read
     *  sites; setters are intentionally absent so callers go through
     *  [withTracerTags]. */
    val currentReportId: String? get() = currentTags.get()?.reportId
    val currentCategory: String? get() = currentTags.get()?.category
    val currentRunId: String? get() = currentTags.get()?.runId

    /** Filename of the most recent trace written on this thread.
     *  [TracingInterceptor] sets it right after persisting a trace;
     *  the outer [RateLimitRetryInterceptor] reads it so a benched-
     *  model cooldown can point back at the 429's trace. Safe
     *  same-thread hand-off — OkHttp app interceptors run
     *  synchronously on one thread per call. */
    val lastTraceFilename: ThreadLocal<String?> = ThreadLocal.withInitial { null }

    /** Optional per-flow sink for the just-written trace filename.
     *  When a coroutine installs one (via [withTraceFilenameSink]),
     *  [TracingInterceptor]'s save path writes the resolved filename
     *  into it — letting the coroutine grab the trace of the call it
     *  just made even though the save runs on an OkHttp worker
     *  thread. [TagPropagatingExecutor] carries the reference across
     *  the thread boundary; the AtomicReference is shared by ref so
     *  the worker's write is visible back on the coroutine. */
    val traceFilenameSink: ThreadLocal<java.util.concurrent.atomic.AtomicReference<String?>?> =
        ThreadLocal.withInitial { null }

    /** In-memory mirror of the trace dir's [TraceFileInfo] list, sorted
     *  newest-first. `null` means "not yet built" — the next
     *  [getTraceFiles] populates it via a streaming parse over every
     *  file. Mutations (save / clear / deleteOlderThan) keep it in sync
     *  so subsequent reads stay O(1). All access goes through [lock]. */
    @Volatile private var cachedTraceFiles: List<TraceFileInfo>? = null

    fun init(context: Context) = lock.withLock {
        traceDir = File(context.filesDir, TRACE_DIR).also { if (!it.exists()) it.mkdirs() }
    }

    /**
     * Persist [trace] as a JSON file. Returns the resolved filename on
     * success, or null when the write was skipped (tracing disabled, no
     * trace dir initialised) or failed.
     *
     * When [filename] is null a fresh filename is generated and a new
     * entry is appended to the in-memory cache. When [filename] is
     * non-null the existing file is overwritten in place and the
     * matching cache entry is replaced — used by the streaming
     * partial → final upgrade path so a process kill mid-stream still
     * leaves a partial trace on disk under its eventual filename.
     *
     * The disk write and the cache mutation are now in **separate**
     * try-catches: a bug in the cache update can no longer silently
     * desync the in-memory list from disk, because any exception there
     * invalidates the cache (sets it back to null) so the next
     * [getTraceFiles] re-reads from disk. We also honour
     * [writeTextAtomic]'s Boolean return — a false-return (no file on
     * disk) skips the cache update so the listing can't carry an entry
     * for a file that isn't there.
     */
    fun saveTrace(trace: ApiTrace, filename: String? = null): String? {
        if (!isTracingEnabled) return null
        lock.withLock {
            val dir = traceDir ?: return null
            if (!dir.exists()) dir.mkdirs()
            val resolvedFilename = filename ?: run {
                val ts = dateFormat.format(Instant.ofEpochMilli(trace.timestamp))
                val seq = fileSequence.incrementAndGet().toString(36)
                // Sanitise hostname so a `host:port` style host (some
                // configurations pass a port through) doesn't produce a
                // filename with `:` — Android's filesystem rejects that
                // and the trace silently fails to land. Replace any
                // non-alphanumeric / dot / dash with `_`.
                val safeHost = trace.hostname.replace(Regex("[^A-Za-z0-9.-]"), "_")
                "${safeHost}_${ts}_${seq}.json"
            }
            val isUpdate = filename != null
            // Step 1 — disk write. Atomic so a process death mid-write
            // leaves no half-JSON behind. Both a throw and a `false`
            // return count as failure — the cache must never reflect a
            // file that isn't on disk.
            val wrote = try {
                File(dir, resolvedFilename).writeTextAtomic(gson.toJson(trace))
            } catch (e: Exception) {
                AppLog.e("ApiTracer", "Failed to save trace ($resolvedFilename): ${e.message}")
                false
            }
            if (!wrote) {
                AppLog.w("ApiTracer", "writeTextAtomic returned false for $resolvedFilename — skipping cache update")
                return null
            }
            // Step 2 — cache mutation. Independently caught so an
            // exception here can't leave a stale cache that shadows the
            // freshly-written file forever. On any failure we invalidate
            // the cache so the next getTraceFiles() rebuilds it from
            // disk and re-sees the new trace.
            AppLog.v("ApiTracer", "trace written $resolvedFilename status=${trace.response.statusCode} partial=${trace.partial}")
            try {
                cachedTraceFiles?.let { current ->
                    val info = TraceFileInfo(
                        resolvedFilename, trace.hostname, trace.timestamp,
                        trace.response.statusCode, trace.reportId, trace.model,
                        trace.category, trace.runId, trace.partial
                    )
                    val next = if (isUpdate) {
                        // Streaming partial → final overwrite reuses the
                        // filename; replace the existing cache entry in
                        // place instead of duplicating it.
                        current.map { if (it.filename == resolvedFilename) info else it }
                    } else {
                        current + info
                    }
                    // Re-sort rather than prepend: trace.timestamp is set
                    // at request-issue time but saveTrace runs at
                    // response-complete time, so concurrent calls can
                    // land out of order.
                    cachedTraceFiles = next.sortedByDescending { it.timestamp }
                }
            } catch (e: Exception) {
                AppLog.e("ApiTracer", "Cache update failed for $resolvedFilename — invalidating cache: ${e.message}")
                cachedTraceFiles = null
            }
            return resolvedFilename
        }
    }

    fun getTraceFiles(): List<TraceFileInfo> = lock.withLock {
        cachedTraceFiles?.let { return it }
        val dir = traceDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        val list = dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { parseTraceFileInfoStreaming(it) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
        cachedTraceFiles = list
        list
    }

    /** Warm the trace-file cache off the calling thread so the first
     *  [getTraceFiles] call from UI code doesn't pay the streaming-
     *  parse cost (one JsonReader per file across the whole trace dir).
     *  Safe to call multiple times — lock + cache check at the top of
     *  getTraceFiles makes the prewarm idempotent. App startup
     *  (AppViewModel.init) calls this so the bulk parse never lands
     *  on a UI dispatcher coroutine. */
    fun prewarmCache(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            getTraceFiles()
        }
    }

    /** Streaming-reader variant of the metadata extract used by
     *  [getTraceFiles]. Pulls only the seven [TraceFileInfo] fields out
     *  of the trace JSON via Gson's [com.google.gson.stream.JsonReader],
     *  skipping the request body entirely and stopping the response read
     *  once `statusCode` is captured. Avoids reflective deserialisation
     *  of the full [ApiTrace] graph plus the headers maps and body
     *  strings, which is what made the trace list slow with a dense
     *  trace dir. */
    private fun parseTraceFileInfoStreaming(file: File): TraceFileInfo? {
        return try {
            com.google.gson.stream.JsonReader(file.bufferedReader()).use { reader ->
                var timestamp = 0L
                var hostname = ""
                var reportId: String? = null
                var model: String? = null
                var category: String? = null
                var runId: String? = null
                var statusCode = 0
                var partial = false
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "timestamp" -> timestamp = reader.nextLong()
                        "hostname" -> hostname = reader.nextString()
                        "reportId" -> reportId = readNullableString(reader)
                        "model" -> model = readNullableString(reader)
                        "category" -> category = readNullableString(reader)
                        "runId" -> runId = readNullableString(reader)
                        "partial" -> partial = reader.nextBoolean()
                        "response" -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (reader.nextName() == "statusCode") statusCode = reader.nextInt()
                                else reader.skipValue()
                            }
                            reader.endObject()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                TraceFileInfo(file.name, hostname, timestamp, statusCode, reportId, model, category, runId, partial)
            }
        } catch (_: Exception) { null }
    }

    private fun readNullableString(reader: com.google.gson.stream.JsonReader): String? =
        if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
            reader.nextNull(); null
        } else reader.nextString()

    /** Cheap existence check for the Hub "AI API Traces" card. Avoids the
     *  parse-every-file cost of [getTraceFiles] on a hot path that just
     *  needs a yes/no answer. */
    fun hasAnyTraceFile(): Boolean = lock.withLock {
        val dir = traceDir ?: return false
        if (!dir.exists()) return false
        dir.listFiles()?.any { it.extension == "json" } == true
    }

    fun getTraceFilesForReport(reportId: String) = getTraceFiles().filter { it.reportId == reportId }

    fun getTraceFilesForRun(runId: String) = getTraceFiles().filter { it.runId == runId }

    fun readTraceFile(filename: String): ApiTrace? = lock.withLock {
        val file = File(traceDir ?: return null, filename)
        if (!file.exists()) return null
        try { gson.fromJson(file.readText(), ApiTrace::class.java) } catch (_: Exception) { null }
    }

    fun readTraceFileRaw(filename: String): String? = lock.withLock {
        val file = File(traceDir ?: return null, filename)
        if (!file.exists()) return null
        try { file.readText() } catch (_: Exception) { null }
    }

    /** Append [suffix] to an already-written trace's category. Lets
     *  a caller tag a trace after the fact once it knows the call's
     *  outcome — e.g. a fan-out tier-1 emoji miss tags its trace
     *  "<category>-miss" so misses can be filtered later. No-op when
     *  tracing is off, the file is gone, the trace has no category,
     *  or the suffix is already present. */
    fun appendCategorySuffix(filename: String, suffix: String) {
        if (!isTracingEnabled) return
        lock.withLock {
            val trace = readTraceFile(filename) ?: return
            val cat = trace.category ?: return
            if (cat.endsWith(suffix)) return
            saveTrace(trace.copy(category = cat + suffix), filename = filename)
        }
    }

    fun clearTraces() = lock.withLock {
        traceDir?.listFiles()?.forEach { if (it.extension == "json") it.delete() }
        cachedTraceFiles = emptyList()
    }

    /** Delete one trace file by filename. Returns true on success, false
     *  when the file is missing or the delete fails. Cached file list
     *  drops the entry so the trace list refreshes without a re-scan. */
    fun deleteTrace(filename: String): Boolean = lock.withLock {
        val dir = traceDir ?: return false
        val file = java.io.File(dir, filename)
        if (!file.exists()) return false
        val ok = try { file.delete() } catch (_: Exception) { false }
        if (ok) cachedTraceFiles?.let { current ->
            cachedTraceFiles = current.filterNot { it.filename == filename }
        }
        ok
    }

    fun deleteTracesOlderThan(cutoffTimestamp: Long): Int = lock.withLock {
        val dir = traceDir ?: return 0
        if (!dir.exists()) return 0
        var count = 0
        val deletedNames = mutableSetOf<String>()
        dir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                try {
                    val info = parseTraceFileInfoStreaming(file)
                    if (info != null && info.timestamp < cutoffTimestamp && file.delete()) {
                        count++
                        deletedNames += file.name
                    }
                } catch (_: Exception) {}
            }
        }
        cachedTraceFiles?.let { current ->
            cachedTraceFiles = current.filterNot { it.filename in deletedNames }
        }
        count
    }

    fun getTraceCount(): Int = lock.withLock {
        cachedTraceFiles?.let { return it.size }
        traceDir?.listFiles()?.count { it.extension == "json" } ?: 0
    }

    fun prettyPrintJson(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try { gson.toJson(gson.fromJson(json, com.google.gson.JsonElement::class.java)) } catch (_: Exception) { json }
    }
}

/** Live mirror of the user-tunable network knobs. Singleton so the
 *  OkHttp interceptors can read the current value without threading
 *  a Settings reference through their constructors. AppViewModel
 *  writes here on bootstrap and on every GeneralSettings update; the
 *  built-in defaults below are the cold-start values used before
 *  bootstrap completes (matters for the very first call on a fresh
 *  install / process restart). */
/** Cross-host, cross-dispatcher concurrency caps. Sit one layer
 *  above [ProviderThrottle] — the report / translation / fan-out
 *  dispatchers withPermit-wrap each per-call coroutine in
 *  [global] plus the matching kind permit ([report] /
 *  [translation] / [fanOut]) so the total in-flight count across
 *  the whole app stays bounded. Surfaces in Settings → Network
 *  settings → Maximal API calls.
 *
 *  Caps are reset at runtime via [resetForNewLimits]; rebuilding
 *  a semaphore is safe because already-held permits release
 *  against their original semaphore (still alive as long as the
 *  holder keeps a reference). New withPermit calls always read
 *  the current getter. */
object ApiCallCaps {
    @Volatile private var globalSem: kotlinx.coroutines.sync.Semaphore = sem(30)
    @Volatile private var reportSem: kotlinx.coroutines.sync.Semaphore = sem(15)
    @Volatile private var translationSem: kotlinx.coroutines.sync.Semaphore = sem(15)
    @Volatile private var fanOutSem: kotlinx.coroutines.sync.Semaphore = sem(15)
    @Volatile private var fanIconsSem: kotlinx.coroutines.sync.Semaphore = sem(15)
    @Volatile private var globalCap: Int = 30
    @Volatile private var reportCap: Int = 15
    @Volatile private var translationCap: Int = 15
    @Volatile private var fanOutCap: Int = 15
    @Volatile private var fanIconsCap: Int = 15

    val global: kotlinx.coroutines.sync.Semaphore get() = globalSem
    val report: kotlinx.coroutines.sync.Semaphore get() = reportSem
    val translation: kotlinx.coroutines.sync.Semaphore get() = translationSem
    val fanOut: kotlinx.coroutines.sync.Semaphore get() = fanOutSem
    val fanIcons: kotlinx.coroutines.sync.Semaphore get() = fanIconsSem

    fun resetForNewLimits(
        globalMax: Int, reportMax: Int,
        translationMax: Int, fanOutMax: Int,
        fanIconsMax: Int
    ) {
        globalCap = globalMax.coerceAtLeast(1)
        reportCap = reportMax.coerceAtLeast(1)
        translationCap = translationMax.coerceAtLeast(1)
        fanOutCap = fanOutMax.coerceAtLeast(1)
        fanIconsCap = fanIconsMax.coerceAtLeast(1)
        globalSem = sem(globalCap)
        reportSem = sem(reportCap)
        translationSem = sem(translationCap)
        fanOutSem = sem(fanOutCap)
        fanIconsSem = sem(fanIconsCap)
    }

    private fun sem(n: Int) = kotlinx.coroutines.sync.Semaphore(n.coerceAtLeast(1))

    /** Snapshot of current in-flight vs. max for each cap. UI / log
     *  diagnostics read this to surface why a coroutine might be
     *  suspended ("global 30/30 = saturated"). Cheap — just reads
     *  the volatile fields and the sem's availablePermits. */
    data class Snapshot(
        val globalInFlight: Int, val globalMax: Int,
        val reportInFlight: Int, val reportMax: Int,
        val translationInFlight: Int, val translationMax: Int,
        val fanOutInFlight: Int, val fanOutMax: Int,
        val fanIconsInFlight: Int, val fanIconsMax: Int
    )

    fun snapshot(): Snapshot = Snapshot(
        globalInFlight = globalCap - globalSem.availablePermits,
        globalMax = globalCap,
        reportInFlight = reportCap - reportSem.availablePermits,
        reportMax = reportCap,
        translationInFlight = translationCap - translationSem.availablePermits,
        translationMax = translationCap,
        fanOutInFlight = fanOutCap - fanOutSem.availablePermits,
        fanOutMax = fanOutCap,
        fanIconsInFlight = fanIconsCap - fanIconsSem.availablePermits,
        fanIconsMax = fanIconsCap
    )
}

object NetworkSettings {
    @Volatile var streamingReadTimeoutSec: Int = com.ai.BuildConfig.NETWORK_READ_TIMEOUT_SEC
    @Volatile var nonStreamingReadTimeoutSec: Int = com.ai.BuildConfig.NETWORK_NONSTREAMING_READ_TIMEOUT_SEC
    /** Global per-provider sliding-window rate limit. Each provider
     *  hostname tracks the timestamps of its recent calls; once the
     *  count in the last 60 s hits this value, the next call waits
     *  until the oldest entry ages out. */
    @Volatile var maxCallsPerProviderPerMinute: Int = 30
    /** Global per-provider concurrency cap. At most this many in-flight
     *  requests per hostname; further calls block on a per-host
     *  semaphore in [ProviderThrottle]. Replaces the per-batch
     *  fan-out semaphore so the limit holds across overlapping flows
     *  (report + meta + chat on the same provider). */
    @Volatile var maxConcurrentCallsPerProvider: Int = 3
    /** Maximum number of in-line 429 retries [RateLimitRetryInterceptor]
     *  attempts per call. Defaults to 3 (matches the legacy constructor
     *  default that was hardcoded before this knob was exposed). */
    @Volatile var maxRetriesOn429: Int = 3
    /** Wait between successive 429 retry attempts, in milliseconds. */
    @Volatile var retryBackoffMs429: Long = 1_000L
    /** Maximum number of in-line 529 (server overloaded) retries
     *  [OverloadedRetryInterceptor] attempts per call. Independent of
     *  the 429 budget — a 529 burst does not eat the 429 retry count. */
    @Volatile var maxRetriesOn529: Int = 3
    /** Wait between successive 529 retry attempts, in milliseconds. */
    @Volatile var retryBackoffMs529: Long = 1_000L
}

