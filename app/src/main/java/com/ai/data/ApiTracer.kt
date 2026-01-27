package com.ai.data

import android.content.Context
import com.google.gson.GsonBuilder
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing a traced API request
 */
data class TraceRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?
)

/**
 * Data class representing a traced API response
 */
data class TraceResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String?
)

/**
 * Data class representing a complete API trace
 */
data class ApiTrace(
    val timestamp: Long,
    val hostname: String,
    val request: TraceRequest,
    val response: TraceResponse
)

/**
 * Data class for displaying trace files in the list
 */
data class TraceFileInfo(
    val filename: String,
    val hostname: String,
    val timestamp: Long,
    val statusCode: Int
)

/**
 * Singleton class to manage API trace storage.
 * Stores trace files in the app's internal storage under a "trace" directory.
 */
object ApiTracer {
    private const val TRACE_DIR = "trace"
    private var traceDir: File? = null
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val tracingLock = Any()

    @Volatile
    var isTracingEnabled: Boolean = false
        get() = synchronized(tracingLock) { field }
        set(value) = synchronized(tracingLock) { field = value }

    /**
     * Initialize the tracer with the app context.
     * Must be called before using any other methods.
     */
    fun init(context: Context) {
        traceDir = File(context.filesDir, TRACE_DIR).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /**
     * Save an API trace to a JSON file.
     */
    fun saveTrace(trace: ApiTrace) {
        if (!isTracingEnabled) return

        val dir = traceDir ?: return
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val timestamp = dateFormat.format(Date(trace.timestamp))
        val filename = "${trace.hostname}_$timestamp.json"
        val file = File(dir, filename)

        try {
            file.writeText(gson.toJson(trace))
        } catch (e: Exception) {
            android.util.Log.e("ApiTracer", "Failed to save trace: ${e.message}", e)
        }
    }

    /**
     * Get list of all trace files, sorted by timestamp (most recent first).
     */
    fun getTraceFiles(): List<TraceFileInfo> {
        val dir = traceDir ?: return emptyList()
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val trace = gson.fromJson(file.readText(), ApiTrace::class.java)
                    TraceFileInfo(
                        filename = file.name,
                        hostname = trace.hostname,
                        timestamp = trace.timestamp,
                        statusCode = trace.response.statusCode
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Read a specific trace file by filename.
     */
    fun readTraceFile(filename: String): ApiTrace? {
        val dir = traceDir ?: return null
        val file = File(dir, filename)
        if (!file.exists()) return null

        return try {
            gson.fromJson(file.readText(), ApiTrace::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get raw JSON content of a trace file.
     */
    fun readTraceFileRaw(filename: String): String? {
        val dir = traceDir ?: return null
        val file = File(dir, filename)
        if (!file.exists()) return null

        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clear all trace files.
     */
    fun clearTraces() {
        val dir = traceDir ?: return
        if (!dir.exists()) return

        dir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                file.delete()
            }
        }
    }

    /**
     * Delete traces older than the given timestamp.
     * Returns the number of traces deleted.
     */
    fun deleteTracesOlderThan(cutoffTimestamp: Long): Int {
        val dir = traceDir ?: return 0
        if (!dir.exists()) return 0

        var deletedCount = 0
        dir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                try {
                    val trace = gson.fromJson(file.readText(), ApiTrace::class.java)
                    if (trace.timestamp < cutoffTimestamp) {
                        if (file.delete()) {
                            deletedCount++
                        }
                    }
                } catch (e: Exception) {
                    // Skip files that can't be parsed
                }
            }
        }
        return deletedCount
    }

    /**
     * Get count of trace files.
     */
    fun getTraceCount(): Int {
        val dir = traceDir ?: return 0
        if (!dir.exists()) return 0

        return dir.listFiles()?.count { it.extension == "json" } ?: 0
    }

    /**
     * Format JSON string for pretty printing.
     */
    fun prettyPrintJson(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            // Use Gson to parse and re-serialize with pretty printing
            val jsonElement = gson.fromJson(json, com.google.gson.JsonElement::class.java)
            gson.toJson(jsonElement)
        } catch (e: Exception) {
            json
        }
    }
}

/**
 * OkHttp Interceptor that traces all API requests and responses.
 * Captures request/response details and saves them to trace files when tracing is enabled.
 */
class TracingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // If tracing is not enabled, just proceed normally
        if (!ApiTracer.isTracingEnabled) {
            return chain.proceed(request)
        }

        val timestamp = System.currentTimeMillis()
        val hostname = request.url.host

        // Capture request details
        val requestHeaders = headersToMap(request.headers)
        val requestBody = request.body?.let { body ->
            try {
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            } catch (e: Exception) {
                null
            }
        }

        val traceRequest = TraceRequest(
            url = request.url.toString(),
            method = request.method,
            headers = requestHeaders,
            body = requestBody
        )

        // Execute the request
        val response = chain.proceed(request)

        // Capture response details
        val responseHeaders = headersToMap(response.headers)
        val responseBody = response.body?.let { body ->
            try {
                val source = body.source()
                source.request(Long.MAX_VALUE)
                val buffer = source.buffer

                // Get the content
                val content = buffer.clone().readUtf8()

                content
            } catch (e: Exception) {
                null
            }
        }

        val traceResponse = TraceResponse(
            statusCode = response.code,
            headers = responseHeaders,
            body = responseBody
        )

        // Create and save the trace
        val trace = ApiTrace(
            timestamp = timestamp,
            hostname = hostname,
            request = traceRequest,
            response = traceResponse
        )

        ApiTracer.saveTrace(trace)

        return response
    }

    private val sensitiveHeaders = setOf(
        "authorization",
        "x-api-key",
        "x-goog-api-key",
        "api-key",
        "api_key",
        "apikey",
        "bearer",
        "token",
        "secret",
        "password",
        "anthropic-api-key"
    )

    private fun headersToMap(headers: Headers): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            // Mask sensitive headers
            val value = if (sensitiveHeaders.contains(name.lowercase())) {
                maskSensitiveValue(headers.value(i))
            } else {
                headers.value(i)
            }
            map[name] = value
        }
        return map
    }

    private fun maskSensitiveValue(value: String): String {
        if (value.length <= 8) return "****"
        return value.take(4) + "****" + value.takeLast(4)
    }
}
