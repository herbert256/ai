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
        // A flow that opted out of the sleeping retry loop (the "Test
        // all models" sweep) is treated as maxRetries == 0.
        val (maxRetries, backoffMs) =
            if (ProviderThrottle.suppressInlineRetry.get() == true) 0 to 0L
            else ProviderThrottle.retryLimitsFor529(request.url.host)
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

