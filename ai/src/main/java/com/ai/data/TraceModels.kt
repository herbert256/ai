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
    /** Opaque id (UUID) shared by every trace produced by a single
     *  user-launched batch — a fan-out, a fan-icons sweep, a
     *  translation, a model-test, a report generation. Lets the L1
     *  "🐞" icon on those screens deep-link the trace list to exactly
     *  this batch's traces. Null on legacy traces and on call sites
     *  that don't bracket their work with [withTracerTags]. */
    val runId: String? = null,
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
    val runId: String? = null,
    val partial: Boolean = false
)

