package com.ai.data

import android.content.Context
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    val request: TraceRequest, val response: TraceResponse
)
data class TraceFileInfo(
    val filename: String, val hostname: String, val timestamp: Long, val statusCode: Int,
    val reportId: String? = null, val model: String? = null, val category: String? = null
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

    fun saveTrace(trace: ApiTrace) {
        if (!isTracingEnabled) return
        lock.withLock {
            val dir = traceDir ?: return
            if (!dir.exists()) dir.mkdirs()
            val ts = dateFormat.format(Instant.ofEpochMilli(trace.timestamp))
            val seq = fileSequence.incrementAndGet().toString(36)
            // Sanitise hostname so a `host:port` style host (some
            // configurations pass a port through) doesn't produce a
            // filename with `:` — Android's filesystem rejects that and
            // the trace silently fails to land. Replace any
            // non-alphanumeric / dot / dash with `_`.
            val safeHost = trace.hostname.replace(Regex("[^A-Za-z0-9.-]"), "_")
            val filename = "${safeHost}_${ts}_${seq}.json"
            try {
                // Atomic so a process death mid-write leaves no
                // half-JSON behind — the streaming parser silently
                // drops corrupt trace files, which would otherwise
                // make traces vanish from the listing.
                File(dir, filename).writeTextAtomic(gson.toJson(trace))
                cachedTraceFiles?.let { current ->
                    val info = TraceFileInfo(
                        filename, trace.hostname, trace.timestamp,
                        trace.response.statusCode, trace.reportId, trace.model, trace.category
                    )
                    // Re-sort rather than prepend: trace.timestamp is set at request-issue
                    // time but saveTrace runs at response-complete time, so concurrent
                    // calls can land out of order.
                    cachedTraceFiles = (current + info).sortedByDescending { it.timestamp }
                }
            }
            catch (e: Exception) { android.util.Log.e("ApiTracer", "Failed to save trace: ${e.message}") }
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
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "timestamp" -> timestamp = reader.nextLong()
                        "hostname" -> hostname = reader.nextString()
                        "reportId" -> reportId = readNullableString(reader)
                        "model" -> model = readNullableString(reader)
                        "category" -> category = readNullableString(reader)
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
                TraceFileInfo(file.name, hostname, timestamp, statusCode, reportId, model, category)
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
 */
class TracingInterceptor : Interceptor {
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
        val response = chain.proceed(request)

        val isStreaming = response.header("Content-Type")?.contains("text/event-stream") == true ||
            (response.header("Transfer-Encoding") == "chunked" && response.header("Content-Type")?.contains("application/json") != true)
        val responseHeaders = headersToMap(response.headers)
        val responseBody = if (isStreaming) "[streaming response - not captured]" else {
            response.body?.let { body ->
                try {
                    val source = body.source()
                    // Cap the buffered read at 8 MB. Trace files are
                    // primarily used for debugging — a 50 MB OpenRouter
                    // model-list response captured verbatim doubles
                    // process memory pressure for no diagnostic gain.
                    // Above the cap we keep the prefix (which contains
                    // headers / IDs / first chunks of content) and
                    // append a marker so the user knows it's clipped.
                    val cap = 8L * 1024 * 1024
                    source.request(cap)
                    val buffered = source.buffer.size
                    val text = source.buffer.clone().readUtf8(minOf(buffered, cap))
                    if (buffered >= cap) "$text\n…[trace truncated at ${cap / (1024 * 1024)} MiB]" else text
                } catch (_: Exception) { null }
            }
        }

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

        ApiTracer.saveTrace(ApiTrace(timestamp, hostname, ApiTracer.currentReportId, model,
            ApiTracer.currentCategory, traceRequest,
            TraceResponse(response.code, responseHeaders, responseBody)))
        return response
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
            current = chain.proceed(request)
        }
        return current
    }
}

/** Set the trace category for the duration of [block], restoring
 *  whatever was there before. Use to bracket a top-level flow's API
 *  calls — works fine inside suspend lambdas because the function is
 *  inlined at the call site. */
inline fun <R> withTraceCategory(category: String, block: () -> R): R {
    val tl = ApiTracer.currentTags
    val previous = tl.get() ?: ApiTracer.TraceTags(null, null)
    tl.set(previous.copy(category = category))
    return try { block() } finally { tl.set(previous) }
}

/** Push (reportId, category) onto the per-thread tag pair for the
 *  duration of [block], restoring both on exit. A null argument leaves
 *  that side untouched (useful for flows that only set one of the two).
 *
 *  Backed by a ThreadLocal that [TagPropagatingExecutor] copies onto
 *  OkHttp's dispatcher worker threads, so concurrent flows on
 *  different coroutines no longer race a process-wide volatile pair.
 *  The historical bare-`= null` reset pattern is also replaced — both
 *  sides are saved and restored on block exit, making nested calls
 *  safe (an inner withTracerTags doesn't clobber an enclosing one). */
inline fun <R> withTracerTags(
    reportId: String? = null,
    category: String? = null,
    block: () -> R
): R {
    val tl = ApiTracer.currentTags
    val previous = tl.get() ?: ApiTracer.TraceTags(null, null)
    tl.set(ApiTracer.TraceTags(
        reportId = reportId ?: previous.reportId,
        category = category ?: previous.category
    ))
    return try {
        block()
    } finally {
        tl.set(previous)
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
