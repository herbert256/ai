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
    @Volatile var currentReportId: String? = null
    /** Free-form label describing what kind of call is in flight (e.g.
     *  "Report meta: Compare", "Chat validate input"). Read at trace-
     *  write time by [TracingInterceptor] and persisted on the trace
     *  JSON so the Trace screen can offer a category filter. Same
     *  volatile + global pattern as [currentReportId] — overlapping
     *  flows on different threads will share whichever value was set
     *  last; precision is per-flow, not per-call. */
    @Volatile var currentCategory: String? = null

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
            val filename = "${trace.hostname}_${ts}_${seq}.json"
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
                    source.request(Long.MAX_VALUE)
                    source.buffer.clone().readUtf8()
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
            val name = headers.name(i); val value = headers.value(i)
            map[name] = map[name]?.let { "$it, $value" } ?: value
        }
        return map
    }

}

/** Retry on HTTP 429 (rate-limit) responses. Sleeps [backoffMs] before
 *  reissuing the same request, up to [maxRetries] times. Placed ahead of
 *  [TracingInterceptor] in the OkHttp chain so every attempt — including
 *  retries — gets a separate trace entry, which makes throttling visible
 *  on the Trace screen instead of hiding inside the network layer. */
class RateLimitRetryInterceptor(
    private val maxRetries: Int = 5,
    private val backoffMs: Long = 3_000L
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

/** Set [ApiTracer.currentCategory] for the duration of [block], restoring
 *  whatever was there before. Use to bracket a top-level flow's API
 *  calls — works fine inside suspend lambdas because the function is
 *  inlined at the call site. */
inline fun <R> withTraceCategory(category: String, block: () -> R): R {
    val previous = ApiTracer.currentCategory
    ApiTracer.currentCategory = category
    return try { block() } finally { ApiTracer.currentCategory = previous }
}

/** Push (reportId, category) onto [ApiTracer]'s tag pair for the
 *  duration of [block], restoring both on exit. A null argument leaves
 *  that side untouched (useful for flows that only set one of the two).
 *
 *  This is the safe replacement for the historical pattern of writing
 *  `ApiTracer.currentReportId = X; try { ... } finally { ... = null }`
 *  scattered across viewmodels and screens — the bare-null restore
 *  clobbered any enclosing flow's tag instead of restoring it. By
 *  always saving the previous values and restoring them on exit, this
 *  helper makes the volatile tag pair safe to nest. */
inline fun <R> withTracerTags(
    reportId: String? = null,
    category: String? = null,
    block: () -> R
): R {
    val previousReportId = ApiTracer.currentReportId
    val previousCategory = ApiTracer.currentCategory
    if (reportId != null) ApiTracer.currentReportId = reportId
    if (category != null) ApiTracer.currentCategory = category
    return try {
        block()
    } finally {
        ApiTracer.currentReportId = previousReportId
        ApiTracer.currentCategory = previousCategory
    }
}
