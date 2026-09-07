package com.ai.data

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Per-OkHttp-call snapshot of the coroutine/thread-local state that
 *  tracing, throttling and retry interceptors read. Capturing it on
 *  the [Call.Factory.newCall] path ties the state to the actual OkHttp
 *  call, so a queued call promoted later by an OkHttp worker cannot
 *  inherit the previous worker's trace tags. */
internal data class OkHttpCallContext(
    val traceTags: ApiTracer.TraceTags?,
    val permitPreAcquired: Boolean,
    val suppressInlineRetry: Boolean,
    val backoffPermitYielder: ((backoffMs: Long) -> Unit)?,
    val benchSignal: java.util.concurrent.atomic.AtomicBoolean?,
    val traceFilenameSink: java.util.concurrent.atomic.AtomicReference<String?>?
) {
    companion object {
        fun capture(): OkHttpCallContext = OkHttpCallContext(
            traceTags = ApiTracer.currentTags.get(),
            permitPreAcquired = ProviderThrottle.permitPreAcquired.get() == true,
            suppressInlineRetry = ProviderThrottle.suppressInlineRetry.get() == true,
            backoffPermitYielder = ProviderThrottle.backoffPermitYielder.get(),
            benchSignal = ProviderThrottle.benchSignal.get(),
            traceFilenameSink = ApiTracer.traceFilenameSink.get()
        )
    }
}

internal fun Request.withCapturedOkHttpCallContext(): Request =
    newBuilder()
        .tag(OkHttpCallContext::class.java, OkHttpCallContext.capture().let { captured ->
            val tags = captured.traceTags ?: ApiTracer.TraceTags(null, null, null)
            captured.copy(traceTags = tags.copy(model = tags.model ?: modelForRequest(this)))
        })
        .build()

internal class ContextTaggingCallFactory(
    private val delegate: Call.Factory
) : Call.Factory {
    override fun newCall(request: Request): Call =
        delegate.newCall(request.withCapturedOkHttpCallContext())
}

/** Restores [OkHttpCallContext] for the duration of the application
 *  interceptor chain. This is intentionally the first interceptor on
 *  the shared client: retry, throttle, timeout and tracing all read
 *  these ThreadLocals. */
internal class OkHttpCallContextInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val captured = chain.request().tag(OkHttpCallContext::class.java)
            ?: return chain.proceed(chain.request())

        val previousTags = ApiTracer.currentTags.get()
        val previousPreAcquired = ProviderThrottle.permitPreAcquired.get() == true
        val previousSuppressRetry = ProviderThrottle.suppressInlineRetry.get() == true
        val previousYielder = ProviderThrottle.backoffPermitYielder.get()
        val previousBenchSignal = ProviderThrottle.benchSignal.get()
        val previousSink = ApiTracer.traceFilenameSink.get()

        ApiTracer.currentTags.set(captured.traceTags)
        ProviderThrottle.permitPreAcquired.set(captured.permitPreAcquired)
        ProviderThrottle.suppressInlineRetry.set(captured.suppressInlineRetry)
        ProviderThrottle.backoffPermitYielder.set(captured.backoffPermitYielder)
        ProviderThrottle.benchSignal.set(captured.benchSignal)
        ApiTracer.traceFilenameSink.set(captured.traceFilenameSink)
        return try {
            chain.proceed(chain.request())
        } finally {
            ApiTracer.currentTags.set(previousTags)
            ProviderThrottle.permitPreAcquired.set(previousPreAcquired)
            ProviderThrottle.suppressInlineRetry.set(previousSuppressRetry)
            ProviderThrottle.backoffPermitYielder.set(previousYielder)
            ProviderThrottle.benchSignal.set(previousBenchSignal)
            ApiTracer.traceFilenameSink.set(previousSink)
        }
    }
}
