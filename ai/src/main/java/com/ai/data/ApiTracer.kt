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

data class TraceRequest(val url: String, val method: String, val headers: Map<String, String>, val body: String?)
data class TraceResponse(val statusCode: Int, val headers: Map<String, String>, val body: String?)
data class ApiTrace(
    val timestamp: Long, val hostname: String,
    val reportId: String? = null, val model: String? = null,
    /** Functional description of the call site that produced this trace,
     *  e.g. "Report", "Report meta: Compare", "Chat", "Chat validate
     *  input", "Provider test", "Retrieve models list", "Pricing fetch".
     *  Set via [ApiTracer.currentCategory] / [withTraceCategory] before
     *  the API call runs. Null on traces written before the field
     *  existed or from sites that don't bracket their calls. */
    val category: String? = null,
    val request: TraceRequest, val response: TraceResponse,
    /** True while a streaming response is still being read — the trace
     *  was written speculatively before EOF/close so a process kill
     *  mid-stream still leaves a record. The final overwrite at
     *  EOF/close resets this to false. Old trace files (pre-field)
     *  deserialise with the default `false`. */
    val partial: Boolean = false
)
data class TraceFileInfo(
    val filename: String, val hostname: String, val timestamp: Long, val statusCode: Int,
    val reportId: String? = null, val model: String? = null, val category: String? = null,
    val partial: Boolean = false
)

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
    @PublishedApi internal data class TraceTags(val reportId: String?, val category: String?)
    @PublishedApi internal val currentTags: ThreadLocal<TraceTags> = ThreadLocal.withInitial { TraceTags(null, null) }

    /** Public accessors — kept for binding-compatibility with read
     *  sites; setters are intentionally absent so callers go through
     *  [withTracerTags]. */
    val currentReportId: String? get() = currentTags.get()?.reportId
    val currentCategory: String? get() = currentTags.get()?.category

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
                        trace.category, trace.partial
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
                TraceFileInfo(file.name, hostname, timestamp, statusCode, reportId, model, category, partial)
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

/**
 * OkHttp Interceptor that traces API requests and responses when tracing is enabled.
 *
 * Non-streaming responses get fully buffered and saved synchronously inside
 * [intercept]. Streaming responses (SSE / chunked-JSON) are wrapped in a
 * [TeeingSource] so reads flow through to the application unchanged while
 * the bytes are accumulated for the trace. The trace is saved when the
 * source reports EOF or is closed — whichever comes first. Both paths cap
 * the captured body at 8 MiB so a runaway response can't OOM us; above the
 * cap a `[trace truncated …]` marker is appended.
 */
class TracingInterceptor : Interceptor {

    private companion object {
        const val BODY_CAP_BYTES = 8L * 1024 * 1024
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!ApiTracer.isTracingEnabled) return chain.proceed(request)

        val timestamp = System.currentTimeMillis()
        val hostname = request.url.host
        val rawRequestBody = request.body?.let { body ->
            try { val buffer = Buffer(); body.writeTo(buffer); buffer.readUtf8() } catch (_: Exception) { null }
        }
        val requestHeaders = headersToMap(request.headers)
        val traceRequest = TraceRequest(request.url.toString(), request.method, requestHeaders, rawRequestBody)

        // Model + call-site tags resolved BEFORE chain.proceed so even
        // a pre-response failure (DNS, TLS, connect timeout) produces a
        // useful trace with the model and category attached.
        val modelFromBody = rawRequestBody?.let { body ->
            try {
                @Suppress("DEPRECATION")
                val el = com.google.gson.JsonParser().parse(body)
                if (el.isJsonObject) el.asJsonObject.get("model")?.asString else null
            } catch (_: Exception) { null }
        }
        // Path-encoded providers (Gemini's /v1beta/models/<model>:generateContent)
        // don't include `model` in the body. Pull it from the URL segment between
        // "/models/" and the next "/" or ":" so trace.model still matches.
        val modelFromUrl: String? = run {
            val u = request.url.toString()
            val seg = u.substringAfter("/models/", "")
            if (seg.isEmpty()) null
            else seg.substringBefore("/").substringBefore(":").takeIf { it.isNotBlank() }
        }
        val model = modelFromBody ?: modelFromUrl

        // Capture the call-site tags now, on the originating thread —
        // OkHttp may finish the body read on a different worker thread,
        // by which time the thread-local would carry someone else's
        // (or no) tags.
        val capturedReportId = ApiTracer.currentReportId
        val capturedCategory = ApiTracer.currentCategory

        // Wrap chain.proceed so a pre-response network failure still
        // produces a visible trace instead of silently disappearing.
        // Re-throw the original exception so caller-side error handling
        // is unchanged.
        val tag = "ApiCall"
        val callLabel = listOfNotNull(
            request.method, hostname, model, capturedCategory?.let { "[$it]" }
        ).joinToString(" ")
        AppLog.i(tag, "→ $callLabel")
        val callStart = System.currentTimeMillis()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            AppLog.w(tag, "✗ $callLabel — ${e.javaClass.simpleName}: ${e.message ?: ""} (${System.currentTimeMillis() - callStart}ms)")
            ApiTracer.saveTrace(ApiTrace(
                timestamp, hostname, capturedReportId, model, capturedCategory,
                traceRequest,
                TraceResponse(
                    statusCode = 0,
                    headers = emptyMap(),
                    body = "[network failure] ${e.javaClass.simpleName}: ${e.message ?: ""}"
                ),
                partial = false
            ))
            throw e
        }
        val durationMs = System.currentTimeMillis() - callStart
        if (response.code >= 400) {
            AppLog.w(tag, "← ${response.code} $callLabel in ${durationMs}ms")
        } else {
            AppLog.i(tag, "← ${response.code} $callLabel in ${durationMs}ms")
        }

        val isStreaming = response.header("Content-Type")?.contains("text/event-stream") == true ||
            (response.header("Transfer-Encoding") == "chunked" && response.header("Content-Type")?.contains("application/json") != true)
        val responseHeaders = headersToMap(response.headers)

        fun saveWith(body: String?, partial: Boolean = false, filename: String? = null): String? {
            return ApiTracer.saveTrace(ApiTrace(
                timestamp, hostname, capturedReportId, model, capturedCategory,
                traceRequest, TraceResponse(response.code, responseHeaders, body),
                partial = partial
            ), filename = filename)
        }

        if (!isStreaming) {
            // Non-streaming: pre-buffer up to the cap and save synchronously.
            // This was the previous behaviour and is the safest path for
            // small JSON responses (model lists, chat completions without
            // stream=true, error bodies).
            val responseBody = response.body?.let { body ->
                try {
                    val source = body.source()
                    source.request(BODY_CAP_BYTES)
                    val buffered = source.buffer.size
                    val text = source.buffer.clone().readUtf8(minOf(buffered, BODY_CAP_BYTES))
                    if (buffered >= BODY_CAP_BYTES) "$text\n…[trace truncated at ${BODY_CAP_BYTES / (1024 * 1024)} MiB]" else text
                } catch (_: Exception) { null }
            }
            saveWith(responseBody)
            return response
        }

        // Streaming: write an initial *partial* trace BEFORE the
        // consumer starts reading so a process kill mid-stream still
        // leaves a record on disk. The final EOF/close upgrade reuses
        // this filename via saveTrace's `filename` parameter so the
        // partial entry is overwritten in place (file + cache).
        val partialFilename = saveWith(
            body = "[partitial: stream in progress]",
            partial = true
        )

        // Wrap the source in a tee. The application reads as normal
        // (SSE parser in ApiStreaming, etc.); reads also flow into
        // [captured]. The final trace is saved when the source signals
        // EOF or is closed — whichever fires first wins via [saved]
        // CAS, so a half-consumed cancelled stream still produces a
        // (truncated but complete) trace.
        val originalBody = response.body ?: run {
            saveWith(null, partial = false, filename = partialFilename)
            return response
        }
        val captured = Buffer()
        val saved = AtomicBoolean(false)
        fun finishOnce() {
            if (!saved.compareAndSet(false, true)) return
            val body = try {
                val text = captured.clone().readUtf8(minOf(captured.size, BODY_CAP_BYTES))
                if (captured.size >= BODY_CAP_BYTES) "$text\n…[trace truncated at ${BODY_CAP_BYTES / (1024 * 1024)} MiB]" else text
            } catch (_: Exception) { null }
            saveWith(body, partial = false, filename = partialFilename)
        }
        val teedSource = object : ForwardingSource(originalBody.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n == -1L) {
                    finishOnce()
                } else if (captured.size < BODY_CAP_BYTES) {
                    val toCopy = minOf(n, BODY_CAP_BYTES - captured.size)
                    if (toCopy > 0) {
                        // sink received `n` new bytes at offset (sink.size - n);
                        // copy that window into our capture buffer.
                        sink.copyTo(captured, sink.size - n, toCopy)
                    }
                }
                return n
            }
            override fun close() {
                super.close()
                finishOnce()
            }
        }
        val wrappedBody = teedSource.buffer().asResponseBody(originalBody.contentType(), originalBody.contentLength())
        return response.newBuilder().body(wrappedBody).build()
    }

    private fun headersToMap(headers: Headers): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = if (isSensitiveHeader(name)) redactSecret(headers.value(i)) else headers.value(i)
            map[name] = map[name]?.let { "$it, $value" } ?: value
        }
        return map
    }

    /** Headers that carry an API key in some shape and must be
     *  redacted before the trace JSON is persisted. The Copy / Share
     *  redaction pass on the Trace screen catches these too, but doing
     *  it at write time means trace JSON files on disk (which roll up
     *  into the backup zip via filesDir/trace) never contain plaintext
     *  secrets in the first place. */
    private fun isSensitiveHeader(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "authorization" ||
            lower == "x-api-key" ||
            lower == "api-key" ||
            lower == "x-goog-api-key" ||
            lower == "openai-organization" ||
            lower == "anthropic-api-key" ||
            lower.startsWith("x-amz-") || // AWS-style signatures
            lower.startsWith("cf-")       // Cloudflare auth proxies
    }

    /** Keep the prefix so the trace remains useful for debugging
     *  ("oh, that's the wrong key" / "the key is empty") without
     *  exposing the secret. */
    private fun redactSecret(value: String): String {
        if (value.isBlank()) return value
        // Bearer tokens have a leading scheme word.
        val (scheme, raw) = value.split(' ', limit = 2).let {
            if (it.size == 2) it[0] + " " to it[1] else "" to it[0]
        }
        if (raw.length <= 8) return "$scheme[REDACTED]"
        return "$scheme${raw.take(4)}…[REDACTED]…${raw.takeLast(4)}"
    }
}

/** Live mirror of the user-tunable network knobs. Singleton so the
 *  OkHttp interceptors can read the current value without threading
 *  a Settings reference through their constructors. AppViewModel
 *  writes here on bootstrap and on every GeneralSettings update; the
 *  built-in defaults below are the cold-start values used before
 *  bootstrap completes (matters for the very first call on a fresh
 *  install / process restart). */
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
}

/** Per-hostname rate + concurrency gate. Backs
 *  [ProviderThrottleInterceptor]. One [java.util.concurrent.Semaphore]
 *  per host caps in-flight calls; a sibling deque of call timestamps
 *  enforces the sliding-window per-minute rate.
 *
 *  Acquire is **synchronous** — it blocks the calling thread (an OkHttp
 *  dispatcher worker, backed by a cached thread pool, see
 *  ApiClient.kt). Returns a [Releaser] that must be called in a
 *  finally so the permit isn't leaked on exception. */
object ProviderThrottle {
    private val sems = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Semaphore>()
    private val windows = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>>()

    class Releaser internal constructor(private val sem: java.util.concurrent.Semaphore) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) sem.release() }
    }

    /** Gate on per-minute rate first, then on concurrency. The
     *  rate-limit branch appends a timestamp on admission — even if a
     *  later concurrency-acquire blocks, the slot stays "used" for the
     *  full minute, which is the safe direction (over-throttling
     *  rather than silently exceeding the user's setting).
     *
     *  Caps are resolved at acquire time per host:
     *    per-provider override (AppService.maxCalls… / maxConcurrent…)
     *    → global default (NetworkSettings.*).
     *  Provider edits go through [ProviderRegistry.save] which calls
     *  [resetForNewLimits], so an override bump takes effect on the
     *  next acquire — no need to re-read on every iteration of the
     *  rate-limit loop. */
    fun acquire(host: String): Releaser {
        if (host.isBlank()) {
            // Hostless requests (rare; only with a malformed URL) get
            // a stub releaser so the interceptor's finally is a no-op.
            return Releaser(java.util.concurrent.Semaphore(Int.MAX_VALUE))
        }
        val override = ProviderRegistry.findByHost(host)
        val perMinuteLimit = (override?.maxCallsPerProviderPerMinute
            ?: NetworkSettings.maxCallsPerProviderPerMinute).coerceAtLeast(1)
        val concurrentLimit = (override?.maxConcurrentCallsPerProvider
            ?: NetworkSettings.maxConcurrentCallsPerProvider).coerceAtLeast(1)
        val window = windows.computeIfAbsent(host) { java.util.concurrent.ConcurrentLinkedDeque() }
        // Rate-limit gate — loop until we claim a slot in the 60 s window.
        while (true) {
            val now = System.currentTimeMillis()
            val sleepMs: Long = synchronized(window) {
                while (true) {
                    val head = window.peekFirst() ?: break
                    if (head < now - 60_000L) window.pollFirst() else break
                }
                if (window.size < perMinuteLimit) {
                    window.addLast(now)
                    0L
                } else {
                    val oldest = window.peekFirst() ?: now
                    (oldest + 60_001L - now).coerceIn(1L, 60_000L)
                }
            }
            if (sleepMs == 0L) break
            AppLog.d("Throttle", "rate-limit wait ${sleepMs}ms on $host (queue=${window.size}/$perMinuteLimit)")
            try { Thread.sleep(sleepMs) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        }
        // Concurrency gate.
        val sem = sems.computeIfAbsent(host) {
            java.util.concurrent.Semaphore(concurrentLimit)
        }
        val concurrentWaitStart = if (sem.availablePermits() == 0) System.currentTimeMillis() else 0L
        sem.acquire()
        if (concurrentWaitStart > 0L) {
            AppLog.d("Throttle", "concurrent-cap wait ${System.currentTimeMillis() - concurrentWaitStart}ms on $host (cap=$concurrentLimit)")
        }
        return Releaser(sem)
    }

    /** Drop the per-host semaphore + window maps so the next call to
     *  [acquire] builds fresh ones at the current
     *  [NetworkSettings.maxConcurrentCallsPerProvider]. Called from
     *  AppViewModel when the user changes the concurrency cap.
     *  In-flight calls still hold a permit on the old (now
     *  unreferenced) semaphore — they release correctly when they
     *  finish, the now-orphan semaphore is GC'd shortly after. Briefly
     *  during the swap the host can exceed the new cap by up to the
     *  old cap's permits; acceptable for a user-driven setting tweak. */
    fun resetForNewLimits() {
        sems.clear()
        windows.clear()
    }
}

/** OkHttp application interceptor that gates every outbound request
 *  through [ProviderThrottle]. Sits inside [RateLimitRetryInterceptor]
 *  so each 429 retry re-enters this interceptor and re-acquires its
 *  own slot (we release in `finally` before the retry-interceptor's
 *  loop reissues `chain.proceed`). */
class ProviderThrottleInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Don't block the main thread — same guard as
        // RateLimitRetryInterceptor. A misuse from a UI dispatcher
        // would ANR; pass-through is the safe fallback.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return chain.proceed(chain.request())
        }
        val request = chain.request()
        val releaser = ProviderThrottle.acquire(request.url.host)
        try {
            return chain.proceed(request)
        } finally {
            releaser.release()
        }
    }
}

/** Per-call read-timeout shim. Without this every call would inherit
 *  the OkHttpClient's static streaming timeout (10 min by default),
 *  which is fine for SSE chat / report streams but disastrous for
 *  short non-streaming calls (analyze, fetch models, meta runs) —
 *  a single hung provider then gates the whole batch for minutes.
 *
 *  Detection runs pre-`chain.proceed` against the request URL and body:
 *  - Gemini's URL distinguishes `:streamGenerateContent` from
 *    `:generateContent`.
 *  - OpenAI / Anthropic POST bodies carry `"stream": true` for SSE
 *    requests; absence => non-streaming.
 *  - Anything else (e.g. GET model-list calls) defaults to the
 *    non-streaming timeout.
 *
 *  Sits ahead of [TestCallTimeoutInterceptor] so a provider-test
 *  call's 30 s override still wins for the test flow regardless of
 *  whether this picks the streaming or non-streaming branch.
 */
class ReadTimeoutInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val nonStreamSec = NetworkSettings.nonStreamingReadTimeoutSec
        val streamSec = NetworkSettings.streamingReadTimeoutSec
        val timeoutSec = if (isStreamingRequest(request)) streamSec else nonStreamSec
        return chain
            .withReadTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .proceed(request)
    }

    private fun isStreamingRequest(request: okhttp3.Request): Boolean {
        val url = request.url.toString()
        // Gemini's streaming endpoint has a separate path segment.
        if (url.contains(":streamGenerateContent")) return true
        if (url.contains(":generateContent")) return false
        // OpenAI / Anthropic streaming requests POST with stream:true
        // in the JSON body. Read the body bytes off a Buffer copy so
        // the original request body stays untouched.
        val body = request.body ?: return false
        if (request.method != "POST") return false
        return try {
            val buf = Buffer()
            body.writeTo(buf)
            val text = buf.snapshot().utf8()
            STREAM_FLAG_REGEX.containsMatchIn(text)
        } catch (_: Exception) { false }
    }

    private companion object {
        // Cheap regex over the body — provider request bodies are JSON
        // so "stream":true vs "stream": true vs "stream" : true all
        // need to match. False positives on a string literal would be
        // weird but harmless (the longer streaming timeout still works
        // for non-streaming responses).
        val STREAM_FLAG_REGEX = Regex("\"stream\"\\s*:\\s*true")
    }
}

/** Retry on HTTP 429 (rate-limit) responses. Reissues the same request
 *  after a short backoff, capped so a sustained 429 burst can't hold an
 *  OkHttp dispatcher slot indefinitely (the dispatcher's per-host limit
 *  is small — default 5 — and a slot held mid-sleep starves every other
 *  request to that host). Placed ahead of [TracingInterceptor] in the
 *  OkHttp chain so every attempt — including retries — gets a separate
 *  trace entry, which makes throttling visible on the Trace screen.
 *
 *  Worst-case slot occupancy is roughly maxRetries × backoffMs plus the
 *  upstream request time per attempt; defaults give ~3 s of in-line
 *  retry. Caller-driven retry policies (queueing more attempts via the
 *  suspend layer) can layer on top with kotlinx.coroutines.delay, which
 *  doesn't block any thread. */
class RateLimitRetryInterceptor(
    private val maxRetries: Int = 3,
    private val backoffMs: Long = 1_000L
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        // Defensive: never block the main thread. Retrofit suspend funcs
        // and the existing withContext(Dispatchers.IO) wrappers should
        // keep us off main, but if anything ever sneaks through, sleeping
        // here for up to maxRetries × backoffMs would ANR the UI.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return response
        if (response.code != 429) return response
        AppLog.d("RateLimit", "429 received on ${request.url.host}, starting retry loop (max=$maxRetries)")
        var current = response
        var attempt = 0
        while (current.code == 429 && attempt < maxRetries) {
            // Bail if the caller already cancelled the call — no point
            // sleeping if the response will be discarded anyway.
            if (chain.call().isCanceled()) return current
            // Always close the previous response before reissuing — leaving
            // the body open leaks an OkHttp connection.
            current.close()
            try {
                Thread.sleep(backoffMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            attempt++
            AppLog.d("RateLimit", "429 retry $attempt/$maxRetries after ${backoffMs}ms on ${request.url.host}")
            current = chain.proceed(request)
        }
        if (current.code == 429) {
            AppLog.w("RateLimit", "429 still present after $attempt retries on ${request.url.host}")
        } else if (attempt > 0) {
            AppLog.d("RateLimit", "recovered after $attempt retry (status=${current.code})")
        }
        return current
    }
}

/** Per-call timeout shim for provider-test traffic. When the calling
 *  thread is inside a `withTraceCategory("Provider test")` block,
 *  override the OkHttp client's read + connect timeouts down from the
 *  streaming-friendly defaults (10 min read) to the test-specific
 *  short window. Without this, a hung provider during Refresh-all
 *  would hold the whole step waiting on the slowest server.
 *
 *  Sits as an application interceptor; `Chain.withReadTimeout` /
 *  `withConnectTimeout` returns a new Chain whose timeouts propagate
 *  through the rest of the chain into the network layer. The category
 *  is read once per call on the originating thread — same propagation
 *  contract [TracingInterceptor] relies on via [TagPropagatingExecutor].
 */
class TestCallTimeoutInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (ApiTracer.currentCategory == TEST_CALL_TRACE_CATEGORY) {
            val readSec = com.ai.BuildConfig.TEST_CONNECTION_READ_TIMEOUT_SEC
            val connectSec = com.ai.BuildConfig.NETWORK_CONNECT_TIMEOUT_SEC
            return chain
                .withReadTimeout(readSec, java.util.concurrent.TimeUnit.SECONDS)
                .withConnectTimeout(connectSec, java.util.concurrent.TimeUnit.SECONDS)
                .proceed(chain.request())
        }
        return chain.proceed(chain.request())
    }

    companion object {
        /** Trace category every test call site uses (see
         *  [testModel] / [testModelWithPrompt] / [testApiConnectionWithJson]).
         *  Defined here so [TestCallTimeoutInterceptor] and those call
         *  sites can't drift out of sync. */
        const val TEST_CALL_TRACE_CATEGORY = "Provider test"
    }
}

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
    val previous = tl.get() ?: ApiTracer.TraceTags(null, null)
    val newTags = previous.copy(category = category)
    return kotlinx.coroutines.withContext(tl.asContextElement(newTags)) {
        block()
    }
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
    block: suspend () -> R
): R {
    val tl = ApiTracer.currentTags
    val previous = tl.get() ?: ApiTracer.TraceTags(null, null)
    val newTags = ApiTracer.TraceTags(
        reportId = reportId ?: previous.reportId,
        category = category ?: previous.category
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
        if (captured?.reportId != null || captured?.category != null) {
            AppLog.v("TagPropagation", "submit reportId=${captured.reportId} cat=${captured.category}")
        }
        delegate.execute {
            val previous = ApiTracer.currentTags.get()
            ApiTracer.currentTags.set(captured)
            try { command.run() } finally { ApiTracer.currentTags.set(previous) }
        }
    }
    override fun shutdown() = delegate.shutdown()
    override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
    override fun isShutdown(): Boolean = delegate.isShutdown
    override fun isTerminated(): Boolean = delegate.isTerminated
    override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
        delegate.awaitTermination(timeout, unit)
}
