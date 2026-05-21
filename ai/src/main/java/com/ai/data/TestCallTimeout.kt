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

