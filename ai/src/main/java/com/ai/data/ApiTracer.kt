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
        val traceRequest = TraceRequest(redactUrl(request.url.toString()), request.method, requestHeaders, rawRequestBody)

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

        val isStreaming = response.header("Content-Type")?.contains("text/event-stream") == true ||
            (response.header("Transfer-Encoding") == "chunked" && response.header("Content-Type")?.contains("application/json") != true)
        val responseHeaders = headersToMap(response.headers)

        fun saveWith(body: String?, partial: Boolean = false, filename: String? = null): String? {
            val fn = ApiTracer.saveTrace(ApiTrace(
                timestamp, hostname, capturedReportId, model, capturedCategory,
                traceRequest, TraceResponse(response.code, responseHeaders, body),
                partial = partial
            ), filename = filename)
            // Expose the just-written trace to the outer
            // RateLimitRetryInterceptor (same thread) so a benched-
            // model cooldown can reference this call's trace.
            ApiTracer.lastTraceFilename.set(fn)
            // And to a coroutine-installed sink (if any) so the
            // originating flow can grab this call's trace filename.
            ApiTracer.traceFilenameSink.get()?.set(fn)
            return fn
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
            if (response.code >= 400) {
                // Error responses from API providers are almost always
                // JSON like {"error":{"message":"…"}}. Pull the message
                // out for the log line so the user sees the actual cause
                // without having to open the trace file.
                val errMsg = extractErrorMessage(responseBody)
                val tail = if (errMsg.isNotBlank()) " — $errMsg" else ""
                AppLog.w(tag, "← ${response.code} $callLabel in ${durationMs}ms$tail")
            } else {
                AppLog.i(tag, "← ${response.code} $callLabel in ${durationMs}ms")
            }
            saveWith(responseBody)
            return response
        }

        // Streaming response. Error responses are virtually never SSE
        // — they come back with application/json and land in the
        // non-streaming branch above — but if we get one here we can
        // only log the status code, since the body is consumed by the
        // downstream SSE parser via the tee.
        if (response.code >= 400) {
            AppLog.w(tag, "← ${response.code} $callLabel in ${durationMs}ms")
        } else {
            AppLog.i(tag, "← ${response.code} $callLabel in ${durationMs}ms")
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

    /** Pull a human-readable error message out of an API response
     *  body, then collapse and clip it to fit on one log line. Tries
     *  the common provider shapes first — `error.message`,
     *  `error.error.message` (Anthropic / OpenRouter wrap one inside
     *  the other on some failures), top-level `message`, `detail`,
     *  and a final plain-string `error` field — falling back to the
     *  first ~200 chars of the raw body if none of them parse.
     *  Returns "" when [body] is null or blank. */
    private fun extractErrorMessage(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val parsed: com.google.gson.JsonElement? = try {
            com.google.gson.JsonParser().parse(body)
        } catch (_: Exception) { null }
        val candidate = if (parsed != null && parsed.isJsonObject) {
            val obj = parsed.asJsonObject
            fun str(name: String) = obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            fun nested(path: List<String>): String? {
                var cur: com.google.gson.JsonElement? = obj
                for (p in path) {
                    val o = cur?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
                    cur = o.get(p) ?: return null
                }
                return cur?.takeIf { it.isJsonPrimitive }?.asString
            }
            nested(listOf("error", "message"))
                ?: nested(listOf("error", "error", "message"))
                ?: nested(listOf("error", "code"))
                ?: str("message")
                ?: str("detail")
                ?: str("error")
        } else null
        val raw = candidate ?: body
        // Collapse whitespace (newlines, tabs) and cap so a verbose
        // stack-trace-style error body doesn't blow out the log line.
        return raw.replace(Regex("\\s+"), " ").trim().take(400)
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

    /** Redact API-key query params from a request URL before it's
     *  persisted in the trace JSON — Google passes the key as
     *  `?key=…`, so unlike every other provider it never shows up
     *  in a header [isSensitiveHeader] would catch. Keeps a short
     *  prefix/suffix so the trace stays useful for "wrong key"
     *  debugging, mirroring [redactSecret]. */
    private fun redactUrl(url: String): String =
        URL_KEY_PARAM_REGEX.replace(url) { m ->
            val v = m.groupValues[2]
            val red = if (v.length <= 8) "[REDACTED]"
                      else "${v.take(4)}…[REDACTED]…${v.takeLast(4)}"
            "${m.groupValues[1]}$red"
        }

    private val URL_KEY_PARAM_REGEX =
        Regex("""([?&](?:key|api[_-]?key|access_token|token)=)([^&\s]+)""", RegexOption.IGNORE_CASE)
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

    /** True on threads where the surrounding flow already acquired a
     *  per-provider permit and is holding it explicitly (Fan-out's
     *  coroutine-level acquire). The interceptor reads this on the
     *  OkHttp dispatcher thread and skips its own acquire — without
     *  the flag we'd double-count permits and halve the effective
     *  concurrency for those flows.
     *
     *  Propagated across coroutine dispatcher hops via
     *  [asContextElement]; copied onto OkHttp worker threads by
     *  [TagPropagatingExecutor]. */
    val permitPreAcquired: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    class Releaser internal constructor(private val sem: java.util.concurrent.Semaphore) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) sem.release() }
    }

    /** Result of [tryAcquire]. */
    sealed class Outcome {
        class Acquired(val releaser: Releaser) : Outcome()
        /** Gate is full; [availableAtMs] is the wall-clock time the
         *  caller should re-check (exact for the per-minute window,
         *  a short poll interval for the concurrency cap). */
        class Blocked(val availableAtMs: Long) : Outcome()
    }

    /** Poll interval when only the concurrency cap is full — there's
     *  no exact ETA for a permit, so re-check soon. */
    private const val CONCURRENCY_POLL_MS = 500L

    /** Resolve a per-provider override (if any) and return the effective
     *  caps the interceptor would apply. Exposed so the fan-out
     *  pre-acquire path uses the exact same lookup as the in-line
     *  interceptor — keeping the two callers in sync as the throttle
     *  source-of-truth evolves. */
    fun limitsFor(host: String): Pair<Int, Int> {
        if (host.isBlank()) return NetworkSettings.maxCallsPerProviderPerMinute to NetworkSettings.maxConcurrentCallsPerProvider
        val override = ProviderRegistry.findByHost(host)
        val perMinute = (override?.maxCallsPerProviderPerMinute
            ?: NetworkSettings.maxCallsPerProviderPerMinute).coerceAtLeast(1)
        val concurrent = (override?.maxConcurrentCallsPerProvider
            ?: NetworkSettings.maxConcurrentCallsPerProvider).coerceAtLeast(1)
        return perMinute to concurrent
    }

    /** Resolve the effective (maxRetries, backoffMs) for [host]'s 429
     *  retry loop: per-provider override → global default. maxRetries
     *  is coerced ≥ 0 (zero is a valid "no in-line retries" setting),
     *  backoffMs is coerced ≥ 1 so a typo can't degenerate into a
     *  busy loop. */
    fun retryLimitsFor429(host: String): Pair<Int, Long> {
        if (host.isBlank()) return NetworkSettings.maxRetriesOn429 to NetworkSettings.retryBackoffMs429
        val override = ProviderRegistry.findByHost(host)
        val maxRetries = (override?.maxRetriesOn429
            ?: NetworkSettings.maxRetriesOn429).coerceAtLeast(0)
        val backoffMs = (override?.retryBackoffMs429
            ?: NetworkSettings.retryBackoffMs429).coerceAtLeast(1L)
        return maxRetries to backoffMs
    }

    /** Resolve the effective (maxRetries, backoffMs) for [host]'s 529
     *  (server overloaded) retry loop. Same shape and clamping as
     *  [retryLimitsFor429] — kept separate so the two retry loops can
     *  be tuned independently. */
    fun retryLimitsFor529(host: String): Pair<Int, Long> {
        if (host.isBlank()) return NetworkSettings.maxRetriesOn529 to NetworkSettings.retryBackoffMs529
        val override = ProviderRegistry.findByHost(host)
        val maxRetries = (override?.maxRetriesOn529
            ?: NetworkSettings.maxRetriesOn529).coerceAtLeast(0)
        val backoffMs = (override?.retryBackoffMs529
            ?: NetworkSettings.retryBackoffMs529).coerceAtLeast(1L)
        return maxRetries to backoffMs
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

    /** Non-blocking sibling of [acquire]. Used by the list
     *  dispatchers (Fan Out / translation / model reports) so a
     *  capped entry can yield + requeue instead of `Thread.sleep`-ing
     *  a worker. Checks the concurrency permit first (non-destructive
     *  on a miss), then the per-minute window under the same
     *  `synchronized(window)` admission [acquire] uses; on a window
     *  miss the concurrency permit is released so nothing leaks. */
    fun tryAcquire(host: String): Outcome {
        if (host.isBlank()) {
            return Outcome.Acquired(Releaser(java.util.concurrent.Semaphore(Int.MAX_VALUE)))
        }
        val override = ProviderRegistry.findByHost(host)
        val perMinuteLimit = (override?.maxCallsPerProviderPerMinute
            ?: NetworkSettings.maxCallsPerProviderPerMinute).coerceAtLeast(1)
        val concurrentLimit = (override?.maxConcurrentCallsPerProvider
            ?: NetworkSettings.maxConcurrentCallsPerProvider).coerceAtLeast(1)
        val sem = sems.computeIfAbsent(host) { java.util.concurrent.Semaphore(concurrentLimit) }
        if (!sem.tryAcquire()) {
            return Outcome.Blocked(System.currentTimeMillis() + CONCURRENCY_POLL_MS)
        }
        val window = windows.computeIfAbsent(host) { java.util.concurrent.ConcurrentLinkedDeque() }
        val blockedUntil: Long? = synchronized(window) {
            val now = System.currentTimeMillis()
            while (true) {
                val head = window.peekFirst() ?: break
                if (head < now - 60_000L) window.pollFirst() else break
            }
            if (window.size < perMinuteLimit) {
                window.addLast(now)
                null
            } else {
                (window.peekFirst() ?: now) + 60_001L
            }
        }
        return if (blockedUntil == null) {
            Outcome.Acquired(Releaser(sem))
        } else {
            sem.release()
            Outcome.Blocked(blockedUntil)
        }
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
        // Fan-out (and any other pre-acquiring flow) already holds a
        // permit; acquiring here too would double-count and halve the
        // effective concurrency cap. The flag is set on the calling
        // coroutine thread and propagated onto this worker by
        // TagPropagatingExecutor.
        if (ProviderThrottle.permitPreAcquired.get() == true) {
            return chain.proceed(request)
        }
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
 *  When the server sends no Retry-After header the per-attempt wait is
 *  an exponential-with-equal-jitter backoff (backoffMs doubled per
 *  attempt, capped at 30 s, randomized ±50%) so a synchronized burst
 *  de-syncs instead of re-colliding. Worst-case slot occupancy at the
 *  defaults (3 retries × 1 s base) is ~7 s plus the upstream request
 *  time per attempt. Caller-driven retry policies (queueing more
 *  attempts via the suspend layer) can layer on top with
 *  kotlinx.coroutines.delay, which doesn't block any thread. */
class RateLimitRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Clear any stale value from a previous call on this pooled
        // thread — TracingInterceptor refills it during chain.proceed
        // when tracing is on, leaving it null when tracing is off.
        ApiTracer.lastTraceFilename.set(null)
        val response = chain.proceed(request)
        // Defensive: never block the main thread. Retrofit suspend funcs
        // and the existing withContext(Dispatchers.IO) wrappers should
        // keep us off main, but if anything ever sneaks through, sleeping
        // here for up to maxRetries × backoffMs would ANR the UI.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return response
        if (response.code != 429) return response
        // Bench a model when the provider hands back a 429 that
        // won't clear on a quick retry, and skip the retry loop.
        // Four triggers:
        //  • Gemini's per-day quota (generate_requests_per_model_per_day)
        //    — bench for the response's own retry hint (it carries a
        //    real "retry in <hours>"); fall back to the Pacific-
        //    midnight reset only if the hint is missing / unparseable.
        //  • Cohere's monthly trial-key cap — no Retry-After / no
        //    structured reset time, so bench until the next calendar
        //    month (best-effort; the real window is whenever the
        //    trial month rolls).
        //  • ANY provider out of credits / over its spending limit —
        //    a billing 429, not a rate-limit one; retrying can't clear
        //    it, so bench for 6h instead of burning the retry budget.
        //  • ANY provider with a Retry-After hint longer than the
        //    threshold — bench for that hint.
        // Either way the dispatch layer deletes the in-flight item
        // once it sees the model is benched.
        run {
            val host = request.url.host
            val providerId = ProviderRegistry.findByHost(host)?.id
            val isGemini = host == "generativelanguage.googleapis.com"
            val benchUntil: Long? = when {
                isGemini && googleDailyQuotaExhausted(response) ->
                    retryAfterHintMs(response)?.let { System.currentTimeMillis() + it }
                        ?: nextPacificMidnightMs()
                providerId == "Cohere" && cohereTrialQuotaExhausted(response) ->
                    nextMonthStartMs()
                creditOrSpendingLimitExhausted(response) ->
                    System.currentTimeMillis() + 6L * 60L * 60L * 1000L
                else -> retryAfterHintMs(response)
                    ?.takeIf { it > ModelCooldownStore.LONG_RETRY_THRESHOLD_MS }
                    ?.let { System.currentTimeMillis() + it }
            }
            if (benchUntil != null) {
                val model = modelForRequest(request)
                if (providerId != null && !model.isNullOrBlank()) {
                    ModelCooldownStore.markUnavailable(
                        providerId, model, benchUntil, ApiTracer.lastTraceFilename.get()
                    )
                } else {
                    AppLog.w("RateLimit", "long 429 on $host but provider/model unresolved (provider=$providerId model=$model)")
                }
                return response
            }
        }
        // Resolve caps lazily per 429 — the user can change the
        // global / per-provider settings while a call is in flight
        // and the next iteration of the retry loop picks up the new
        // values. maxRetries == 0 is a valid "no in-line retries"
        // setting; the loop exits immediately and the original 429
        // bubbles up to the outer withRetry layer.
        val (maxRetries, backoffMs) = ProviderThrottle.retryLimitsFor429(request.url.host)
        AppLog.d("RateLimit", "429 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)")
        var current = response
        var attempt = 0
        while (current.code == 429 && attempt < maxRetries) {
            // Bail if the caller already cancelled the call — no point
            // sleeping if the response will be discarded anyway.
            if (chain.call().isCanceled()) return current
            // Honour the server's Retry-After header when present —
            // matches the RFC and avoids retrying into the same rate
            // window. Falls back to an exponential-with-equal-jitter
            // backoff when the header is missing / unparseable.
            //
            // A flat backoff synchronizes concurrent retries against the
            // same per-minute window (Fireworks ships no Retry-After),
            // so a burst all re-collides. Doubling per attempt spreads a
            // sustained burst; the random half de-syncs sibling calls.
            val expBackoff = (backoffMs shl attempt.coerceAtMost(16)).coerceAtMost(30_000L)
            val jittered = expBackoff / 2 +
                java.util.concurrent.ThreadLocalRandom.current().nextLong(expBackoff / 2 + 1)
            val sleepMs = resolveRetryAfter(current, defaultMs = jittered, hostForLog = request.url.host)
            // Always close the previous response before reissuing — leaving
            // the body open leaks an OkHttp connection.
            current.close()
            try {
                Thread.sleep(sleepMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            attempt++
            AppLog.d("RateLimit", "429 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}")
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

/** Pick a sleep duration before reissuing a rate-limited request.
 *  Reads the `Retry-After` response header (RFC 7231): either an
 *  integer number of seconds OR an HTTP-date. Falls back to
 *  [defaultMs] when the header is missing or unparseable. Clamps
 *  to [1 ms .. 5 min] so a misconfigured server can't hang the
 *  call for hours. */
private fun resolveRetryAfter(response: Response, defaultMs: Long, hostForLog: String): Long {
    val raw = response.header("Retry-After") ?: response.header("retry-after")
    if (raw.isNullOrBlank()) return defaultMs
    val trimmed = raw.trim()
    // Seconds form — the common case for rate-limit responses.
    trimmed.toLongOrNull()?.let { secs ->
        if (secs <= 0) return defaultMs
        val ms = (secs * 1000).coerceIn(1L, 5L * 60_000L)
        AppLog.d("RateLimit", "Retry-After=${trimmed}s on $hostForLog → sleeping ${ms}ms")
        return ms
    }
    // HTTP-date form — rare for rate-limit but defined by the RFC.
    return runCatching {
        val date = java.time.ZonedDateTime.parse(
            trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
        )
        val now = java.time.ZonedDateTime.now(date.zone)
        val delta = java.time.Duration.between(now, date).toMillis()
        if (delta <= 0) defaultMs else delta.coerceIn(1L, 5L * 60_000L).also {
            AppLog.d("RateLimit", "Retry-After=\"$trimmed\" on $hostForLog → sleeping ${it}ms")
        }
    }.getOrDefault(defaultMs)
}

/** Resolve a 429's retry-after hint, **unclamped**, in
 *  milliseconds. Reads the generic `Retry-After` header first
 *  (seconds or HTTP-date — works for any provider), then falls
 *  back to the Gemini error body's `RetryInfo.retryDelay` (e.g.
 *  `"3600s"`; absent for non-Gemini providers, so the peek is a
 *  harmless no-op there). Returns null when neither is present
 *  or parseable. `peekBody` leaves the real response body
 *  untouched for the downstream parser. */
private fun retryAfterHintMs(response: Response): Long? {
    val raw = response.header("Retry-After") ?: response.header("retry-after")
    if (!raw.isNullOrBlank()) {
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { secs -> if (secs > 0) return secs * 1000 }
        runCatching {
            val date = java.time.ZonedDateTime.parse(
                trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            )
            val delta = java.time.Duration.between(
                java.time.ZonedDateTime.now(date.zone), date
            ).toMillis()
            if (delta > 0) return delta
        }
    }
    return runCatching {
        val body = response.peekBody(64L * 1024L).string()
        val details = com.google.gson.JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("error")?.getAsJsonArray("details") ?: return null
        for (d in details) {
            val obj = d.asJsonObject
            if (obj.get("@type")?.asString?.contains("RetryInfo") != true) continue
            val secs = obj.get("retryDelay")?.asString?.removeSuffix("s")?.toDoubleOrNull()
                ?: continue
            return (secs * 1000).toLong()
        }
        null
    }.getOrNull()
}

/** Best-effort model id for a request being benched. Gemini
 *  path-encodes it (`/v1beta/models/<model>:generateContent`);
 *  every other provider carries it in the JSON request body's
 *  `model` field. The request has already been sent by the time
 *  this runs, so re-reading the body is inspection-only. */
private fun modelForRequest(request: okhttp3.Request): String? {
    if (request.url.host == "generativelanguage.googleapis.com") {
        return request.url.pathSegments.lastOrNull()
            ?.substringBefore(":")?.takeIf { it.isNotBlank() }
    }
    val body = request.body ?: return null
    return runCatching {
        val buf = Buffer()
        body.writeTo(buf)
        com.google.gson.JsonParser.parseString(buf.readUtf8())
            .asJsonObject.get("model")?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** True when a Google 429 body reports a **per-day** quota as
 *  exhausted — a `QuotaFailure` detail whose violation matches
 *  either the per-day request cap
 *  (`generate_requests_per_model_per_day`) or the per-day token
 *  cap (`*_tokens_per_model_per_day`). Both reset at the same
 *  Pacific-midnight boundary. Google's `retryDelay` hint for
 *  these is unreliable (often short even though the quota only
 *  refills at the day boundary), so the caller benches until the
 *  reset instead. Per-minute quotas (`*_per_minute`) are
 *  deliberately not matched — the in-line retry loop clears
 *  those within a minute. */
private fun googleDailyQuotaExhausted(response: Response): Boolean = runCatching {
    val body = response.peekBody(64L * 1024L).string()
    val details = com.google.gson.JsonParser.parseString(body).asJsonObject
        .getAsJsonObject("error")?.getAsJsonArray("details") ?: return false
    for (d in details) {
        val obj = d.asJsonObject
        if (obj.get("@type")?.asString?.contains("QuotaFailure") != true) continue
        val violations = obj.getAsJsonArray("violations") ?: continue
        for (v in violations) {
            val vo = v.asJsonObject
            val metric = vo.get("quotaMetric")?.asString ?: ""
            val id = vo.get("quotaId")?.asString ?: ""
            val perDayMetric = metric.endsWith("_per_model_per_day") ||
                metric.endsWith("_tokens_per_model_per_day")
            if (perDayMetric || id.contains("PerDay")) return true
        }
    }
    false
}.getOrDefault(false)

/** Epoch-ms of the next midnight in America/Los_Angeles — when
 *  Gemini's per-day request quotas reset (free and paid tiers
 *  alike). */
private fun nextPacificMidnightMs(): Long {
    val pacific = java.time.ZoneId.of("America/Los_Angeles")
    val nextMidnight = java.time.ZonedDateTime.now(pacific)
        .toLocalDate().plusDays(1).atStartOfDay(pacific)
    return nextMidnight.toInstant().toEpochMilli()
}

/** True when a Cohere 429 body reports the Trial-key monthly cap
 *  ("…using a Trial key, which is limited to N API calls /
 *  month…"). Cohere ships no Retry-After header and no structured
 *  reset time, so this text match is the only signal. */
private fun cohereTrialQuotaExhausted(response: Response): Boolean = runCatching {
    val body = response.peekBody(64L * 1024L).string()
    val msg = com.google.gson.JsonParser.parseString(body).asJsonObject
        .get("message")?.asString ?: return false
    msg.contains("Trial key", ignoreCase = true) && msg.contains("month", ignoreCase = true)
}.getOrDefault(false)

/** True when a 429 body reports the provider account being out of
 *  money — credits exhausted, balance too low, or a spending /
 *  monthly limit hit. Distinct from a rate-limit 429: retrying
 *  won't clear it, so the caller benches the model instead of
 *  burning the in-line retry budget. Checks the structured error
 *  type/code first (e.g. OpenAI's `insufficient_quota`), then a
 *  phrase fallback — all billing-specific wording that never
 *  appears in a plain "slow down" 429. */
private fun creditOrSpendingLimitExhausted(response: Response): Boolean = runCatching {
    val body = response.peekBody(64L * 1024L).string()
    val typeOrCode = runCatching {
        val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
        val err = obj.getAsJsonObject("error")
        listOfNotNull(
            err?.get("type")?.asString,
            err?.get("code")?.asString,
            obj.get("code")?.asString
        ).joinToString(" ")
    }.getOrDefault("")
    if (typeOrCode.contains("insufficient_quota", ignoreCase = true)) return@runCatching true
    val needles = listOf(
        "spending limit", "all available credits", "purchase more credits",
        "insufficient credits", "insufficient balance", "credit balance is too low",
        "out of credits", "billing details", "exceeded your current quota"
    )
    needles.any { body.contains(it, ignoreCase = true) }
}.getOrDefault(false)

/** Epoch-ms of the start of the next calendar month (device local
 *  time). Best-effort reset point for a monthly trial-quota 429 —
 *  the real window is whenever the provider's trial month rolls. */
private fun nextMonthStartMs(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.MONTH, 1)
    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** Retry on HTTP 529 (server overloaded) responses. Mirror of
 *  [RateLimitRetryInterceptor] but for Anthropic-style
 *  `overloaded_error`. Independent of the 429 budget — a 529 burst
 *  cannot exhaust the 429 retry count and vice versa. Same main-thread
 *  guard, same cancellation check, same close-before-reissue pattern. */
class OverloadedRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return response
        if (response.code != 529) return response
        val (maxRetries, backoffMs) = ProviderThrottle.retryLimitsFor529(request.url.host)
        AppLog.d("Overloaded", "529 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)")
        var current = response
        var attempt = 0
        while (current.code == 529 && attempt < maxRetries) {
            if (chain.call().isCanceled()) return current
            // Honour Retry-After when the server includes it.
            // Anthropic frequently does on 529 (overloaded_error).
            val sleepMs = resolveRetryAfter(current, defaultMs = backoffMs, hostForLog = request.url.host)
            current.close()
            try {
                Thread.sleep(sleepMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            attempt++
            AppLog.d("Overloaded", "529 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}")
            current = chain.proceed(request)
        }
        if (current.code == 529) {
            AppLog.w("Overloaded", "529 still present after $attempt retries on ${request.url.host}")
        } else if (attempt > 0) {
            AppLog.d("Overloaded", "recovered after $attempt retry (status=${current.code})")
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
        // Also capture ProviderThrottle.permitPreAcquired so the
        // ProviderThrottleInterceptor on the worker can tell whether
        // the calling coroutine already acquired a per-provider
        // permit. Without this the worker would always read false
        // (the worker's ThreadLocal default) and double-acquire on
        // Fan-out pairs.
        val capturedPreAcquired = ProviderThrottle.permitPreAcquired.get() == true
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
            val previousSink = ApiTracer.traceFilenameSink.get()
            ApiTracer.currentTags.set(captured)
            if (capturedPreAcquired) ProviderThrottle.permitPreAcquired.set(true)
            ApiTracer.traceFilenameSink.set(capturedSink)
            try {
                command.run()
            } finally {
                ApiTracer.currentTags.set(previousTags)
                if (capturedPreAcquired) ProviderThrottle.permitPreAcquired.set(previousPreAcquired)
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
