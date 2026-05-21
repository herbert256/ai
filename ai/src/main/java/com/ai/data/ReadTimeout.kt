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

