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
        val capturedRunId = ApiTracer.currentRunId

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
                runId = capturedRunId,
                request = traceRequest,
                response = TraceResponse(
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
                runId = capturedRunId,
                request = traceRequest, response = TraceResponse(response.code, responseHeaders, body),
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

