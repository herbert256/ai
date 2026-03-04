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
data class ApiTrace(val timestamp: Long, val hostname: String, val reportId: String? = null, val model: String? = null, val request: TraceRequest, val response: TraceResponse)
data class TraceFileInfo(val filename: String, val hostname: String, val timestamp: Long, val statusCode: Int, val reportId: String? = null, val model: String? = null)

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
            try { File(dir, "${trace.hostname}_${ts}_${seq}.json").writeText(gson.toJson(trace)) }
            catch (e: Exception) { android.util.Log.e("ApiTracer", "Failed to save trace: ${e.message}") }
        }
    }

    fun getTraceFiles(): List<TraceFileInfo> = lock.withLock {
        val dir = traceDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            try {
                val trace = gson.fromJson(file.readText(), ApiTrace::class.java)
                TraceFileInfo(file.name, trace.hostname, trace.timestamp, trace.response.statusCode, trace.reportId, trace.model)
            } catch (_: Exception) { null }
        }?.sortedByDescending { it.timestamp } ?: emptyList()
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
    }

    fun deleteTracesOlderThan(cutoffTimestamp: Long): Int = lock.withLock {
        val dir = traceDir ?: return 0
        if (!dir.exists()) return 0
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                try {
                    val trace = gson.fromJson(file.readText(), ApiTrace::class.java)
                    if (trace.timestamp < cutoffTimestamp && file.delete()) count++
                } catch (_: Exception) {}
            }
        }
        count
    }

    fun getTraceCount(): Int = lock.withLock {
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
    companion object {
        private val SENSITIVE_HEADER_NAMES = setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "cookie",
            "set-cookie"
        )
        private val SENSITIVE_JSON_KEYS = setOf(
            "api_key",
            "apikey",
            "authorization",
            "token",
            "access_token",
            "refresh_token",
            "password",
            "secret"
        )
        private const val REDACTED = "[REDACTED]"
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
        val requestBody = redactJsonString(rawRequestBody)

        val traceRequest = TraceRequest(request.url.toString(), request.method, requestHeaders, requestBody)
        val response = chain.proceed(request)

        val isStreaming = response.header("Content-Type")?.contains("text/event-stream") == true ||
            (response.header("Transfer-Encoding") == "chunked" && response.header("Content-Type")?.contains("application/json") != true)
        val responseHeaders = headersToMap(response.headers)
        val responseBody = if (isStreaming) "[streaming response - not captured]" else {
            response.body?.let { body ->
                try {
                    val source = body.source()
                    source.request(Long.MAX_VALUE)
                    redactJsonString(source.buffer.clone().readUtf8())
                } catch (_: Exception) { null }
            }
        }

        val model = rawRequestBody?.let { body ->
            try {
                @Suppress("DEPRECATION")
                val el = com.google.gson.JsonParser().parse(body)
                if (el.isJsonObject) el.asJsonObject.get("model")?.asString else null
            } catch (_: Exception) { null }
        }

        ApiTracer.saveTrace(ApiTrace(timestamp, hostname, ApiTracer.currentReportId, model, traceRequest,
            TraceResponse(response.code, responseHeaders, responseBody)))
        return response
    }

    private fun headersToMap(headers: Headers): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            val name = headers.name(i); val value = headers.value(i)
            val safeValue = if (name.lowercase(Locale.US) in SENSITIVE_HEADER_NAMES) REDACTED else value
            map[name] = map[name]?.let { "$it, $safeValue" } ?: safeValue
        }
        return map
    }

    private fun redactJsonString(text: String?): String? {
        if (text.isNullOrBlank()) return text
        return try {
            @Suppress("DEPRECATION")
            val root = com.google.gson.JsonParser().parse(text)
            redactJsonElement(root)
            createAppGson().toJson(root)
        } catch (_: Exception) {
            text
        }
    }

    private fun redactJsonElement(element: com.google.gson.JsonElement?) {
        when {
            element == null || element.isJsonNull -> return
            element.isJsonObject -> {
                val obj = element.asJsonObject
                obj.entrySet().forEach { (key, value) ->
                    if (key.lowercase(Locale.US) in SENSITIVE_JSON_KEYS) {
                        obj.addProperty(key, REDACTED)
                    } else {
                        redactJsonElement(value)
                    }
                }
            }
            element.isJsonArray -> {
                element.asJsonArray.forEach { redactJsonElement(it) }
            }
        }
    }
}
