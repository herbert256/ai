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

/** Retry on HTTP 429 (rate-limit) responses. Reissues the same request
 *  after a short backoff, capped so a sustained 429 burst can't hold an
 *  OkHttp dispatcher slot indefinitely (the dispatcher's per-host limit
 *  is small — default 5 — and a slot held mid-sleep starves every other
 *  request to that host). Placed ahead of [TracingInterceptor] in the
 *  OkHttp chain so every attempt — including retries — gets a separate
 *  trace entry, which makes throttling visible on the Trace screen.
 *
 *  When the server sends no Retry-After header the per-attempt wait is
 *  an exponential-with-equal-jitter backoff (backoffMs doubled per
 *  attempt, capped at 30 s, randomized ±50%) so a synchronized burst
 *  de-syncs instead of re-colliding. Worst-case slot occupancy at the
 *  defaults (3 retries × 1 s base) is ~7 s plus the upstream request
 *  time per attempt. Caller-driven retry policies (queueing more
 *  attempts via the suspend layer) can layer on top with
 *  kotlinx.coroutines.delay, which doesn't block any thread. */
class RateLimitRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Clear any stale value from a previous call on this pooled
        // thread — TracingInterceptor refills it during chain.proceed
        // when tracing is on, leaving it null when tracing is off.
        ApiTracer.lastTraceFilename.set(null)
        val response = chain.proceed(request)
        // Defensive: never block the main thread. Retrofit suspend funcs
        // and the existing withContext(Dispatchers.IO) wrappers should
        // keep us off main, but if anything ever sneaks through, sleeping
        // here for up to maxRetries × backoffMs would ANR the UI.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return response
        if (response.code != 429) return response
        // Bench a model when the provider hands back a 429 that
        // won't clear on a quick retry, and skip the retry loop.
        // Four triggers:
        //  • Gemini's per-day quota (generate_requests_per_model_per_day)
        //    — bench for the response's own retry hint (it carries a
        //    real "retry in <hours>"); fall back to the Pacific-
        //    midnight reset only if the hint is missing / unparseable.
        //  • Cohere's monthly trial-key cap — no Retry-After / no
        //    structured reset time, so bench until the next calendar
        //    month (best-effort; the real window is whenever the
        //    trial month rolls).
        //  • ANY provider out of credits / over its spending limit —
        //    a billing 429, not a rate-limit one; retrying can't clear
        //    it, so bench for 6h instead of burning the retry budget.
        //  • ANY provider with a Retry-After hint longer than the
        //    threshold — bench for that hint.
        // Either way the dispatch layer deletes the in-flight item
        // once it sees the model is benched.
        run {
            val host = request.url.host
            val resolvedProviderId = ProviderRegistry.findByHost(host)?.id
            val isGemini = host == "generativelanguage.googleapis.com"
            // Cohere's Trial-key monthly cap is self-identifying in the
            // body ("…using a Trial key, which is limited to N API
            // calls / month…"), so it's matched on the body alone — not
            // gated on the host resolving to the "Cohere" provider id,
            // which a renamed entry or the api.cohere.ai vs .com host
            // split could break.
            // Read the 64 KiB error-body peek ONCE and share the decoded
            // string across every predicate (Bug 13) instead of peeking
            // four separate times per 429.
            val peekedBody = runCatching { response.peekBody(64L * 1024L).string() }.getOrNull()
            val cohereTrialCap = cohereTrialQuotaExhausted(peekedBody)
            val benchUntil: Long? = when {
                isGemini && googleDailyQuotaExhausted(peekedBody) ->
                    retryAfterHintMs(response, peekedBody)?.let { System.currentTimeMillis() + it }
                        ?: nextPacificMidnightMs()
                cohereTrialCap -> nextMonthStartMs()
                creditOrSpendingLimitExhausted(peekedBody) ->
                    System.currentTimeMillis() + 6L * 60L * 60L * 1000L
                else -> retryAfterHintMs(response, peekedBody)
                    ?.takeIf { it > ModelCooldownStore.LONG_RETRY_THRESHOLD_MS }
                    ?.let { System.currentTimeMillis() + it }
            }
            if (benchUntil != null) {
                // The Cohere Trial-cap body is itself proof it's a
                // Cohere-family call, so resolve by registered host family
                // instead of hard-coding the provider id.
                val providerId = resolvedProviderId ?: if (cohereTrialCap) cohereProviderIdFor(host) else null
                val model = modelForRequest(request)
                if (providerId != null && !model.isNullOrBlank()) {
                    ModelCooldownStore.markUnavailable(
                        providerId, model, benchUntil, ApiTracer.lastTraceFilename.get()
                    )
                } else {
                    AppLog.w("RateLimit", "long 429 on $host but provider/model unresolved (provider=$providerId model=$model)")
                }
                return response
            }
        }
        // Type-A fixed-model batch bench-and-requeue. The batch registered a
        // per-item [benchSignal]; on a transient 429 (the long-bench cases
        // above didn't fire) park the model for its Retry-After hint (or the
        // configured default) and signal the batch loop to re-queue this item
        // — instead of sleeping in line. Same-model siblings gate on the bench
        // so they don't fire doomed calls. The batch loop bounds the requeues.
        run {
            val sig = ProviderThrottle.benchSignal.get()
            if (sig != null && ModelCooldownStore.typeABenchEnabled) {
                val providerId = ProviderRegistry.findByHost(request.url.host)?.id
                val model = modelForRequest(request)
                if (providerId != null && !model.isNullOrBlank()) {
                    val benchMs = (retryAfterHintMs(response, null) ?: ModelCooldownStore.typeABenchBaseMs)
                        .coerceIn(1_000L, 5L * 60_000L)
                    ModelCooldownStore.markShortBench(providerId, model, System.currentTimeMillis() + benchMs)
                    sig.set(true)
                    return response
                }
            }
        }
        // Resolve caps lazily per 429 — the user can change the
        // global / per-provider settings while a call is in flight
        // and the next iteration of the retry loop picks up the new
        // values. maxRetries == 0 is a valid "no in-line retries"
        // setting; the loop exits immediately and the original 429
        // bubbles up to the outer withRetry layer. A flow that opted
        // out of the sleeping retry loop (the "Test all models" sweep)
        // is treated as maxRetries == 0 — the 429 is returned as-is.
        val hasBackoffYielder = ProviderThrottle.backoffPermitYielder.get() != null
        val (maxRetries, backoffMs) =
            if (ProviderThrottle.suppressInlineRetry.get() == true || !hasBackoffYielder) 0 to 0L
            else ProviderThrottle.retryLimitsFor429(request.url.host)
        if (!hasBackoffYielder && ProviderThrottle.suppressInlineRetry.get() != true) {
            AppLog.d("RateLimit", "429 on ${request.url.host} has no backoff yielder; returning for coroutine-level retry")
        }
        AppLog.d("RateLimit", "429 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)")
        var current = response
        var attempt = 0
        while (current.code == 429 && attempt < maxRetries) {
            // Bail if the caller already cancelled the call — no point
            // sleeping if the response will be discarded anyway.
            if (chain.call().isCanceled()) return current
            // Honour the server's Retry-After header when present —
            // matches the RFC and avoids retrying into the same rate
            // window. Falls back to an exponential-with-equal-jitter
            // backoff when the header is missing / unparseable.
            //
            // A flat backoff synchronizes concurrent retries against the
            // same per-minute window (Fireworks ships no Retry-After),
            // so a burst all re-collides. Doubling per attempt spreads a
            // sustained burst; the random half de-syncs sibling calls.
            val expBackoff = (backoffMs shl attempt.coerceAtMost(16)).coerceAtMost(30_000L)
            // Guard the jitter division/bound against a zero backoff
            // (Bug 14): nextLong(0+1) is safe, but an expBackoff of 0 would
            // otherwise make the half-jitter math degenerate.
            val jittered = if (expBackoff <= 0) 0L else
                expBackoff / 2 + java.util.concurrent.ThreadLocalRandom.current().nextLong(expBackoff / 2 + 1)
            val sleepMs = resolveRetryAfter(current, defaultMs = jittered, hostForLog = request.url.host)
            // Always close the previous response before reissuing — leaving
            // the body open leaks an OkHttp connection.
            current.close()
            // Releases this item's batch permits for the sleep when the
            // flow registered a yielder (runThrottledBatch); plain sleep
            // otherwise. Either way a backing-off call doesn't pin shared
            // capacity for the whole backoff. Counted for the dashboard's
            // live retry-pressure readout.
            RetryStats.record()
            RetryStats.enterBackoff()
            try { ProviderThrottle.backoffSleep(sleepMs) } finally { RetryStats.exitBackoff() }
            attempt++
            AppLog.d("RateLimit", "429 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}")
            current = chain.proceed(request)
        }
        if (current.code == 429) {
            AppLog.w("RateLimit", "429 still present after $attempt retries on ${request.url.host}")
        } else if (attempt > 0) {
            AppLog.d("RateLimit", "recovered after $attempt retry (status=${current.code})")
        }
        return current
    }
}

/** Pick a sleep duration before reissuing a rate-limited request.
 *  Reads the `Retry-After` response header (RFC 7231): either an
 *  integer number of seconds OR an HTTP-date. Falls back to
 *  [defaultMs] when the header is missing or unparseable. Clamps
 *  to [1 ms .. 5 min] so a misconfigured server can't hang the
 *  call for hours. */
internal fun resolveRetryAfter(response: Response, defaultMs: Long, hostForLog: String): Long {
    val raw = response.header("Retry-After") ?: response.header("retry-after")
    if (raw.isNullOrBlank()) return defaultMs
    val trimmed = raw.trim()
    // Seconds form — the common case for rate-limit responses.
    trimmed.toLongOrNull()?.let { secs ->
        if (secs <= 0) return defaultMs
        val ms = (secs * 1000).coerceIn(1L, 5L * 60_000L)
        AppLog.d("RateLimit", "Retry-After=${trimmed}s on $hostForLog → sleeping ${ms}ms")
        return ms
    }
    // HTTP-date form — rare for rate-limit but defined by the RFC.
    return runCatching {
        val date = java.time.ZonedDateTime.parse(
            trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
        )
        val now = java.time.ZonedDateTime.now(date.zone)
        val delta = java.time.Duration.between(now, date).toMillis()
        if (delta <= 0) defaultMs else delta.coerceIn(1L, 5L * 60_000L).also {
            AppLog.d("RateLimit", "Retry-After=\"$trimmed\" on $hostForLog → sleeping ${it}ms")
        }
    }.getOrDefault(defaultMs)
}

/** Default worker-fallback cooldown applied to a 429'd worker when the
 *  response carries no wait hint. The WorkerRunner chain uses this. */
internal const val WORKER_429_DEFAULT_MS = 5_000L

/** Parse a `Retry-After` hint (milliseconds) out of the formatted header
 *  block captured on [com.ai.data.AnalysisResponse.httpHeaders] (a
 *  newline-separated "Name: value" string). Seconds form or HTTP-date
 *  form; null when the header is absent or unparseable. Sibling of
 *  [retryAfterHintMs] for callers that only have the formatted string,
 *  not the live OkHttp [Response]. */
internal fun retryAfterFromHeaderBlock(headers: String?): Long? {
    if (headers.isNullOrBlank()) return null
    val line = headers.lineSequence()
        .firstOrNull { it.trimStart().startsWith("retry-after:", ignoreCase = true) }
        ?: return null
    val raw = line.substringAfter(':').trim()
    raw.toLongOrNull()?.let { secs -> if (secs > 0) return secs * 1000 }
    runCatching {
        val date = java.time.ZonedDateTime.parse(
            raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
        )
        val delta = java.time.Duration.between(
            java.time.ZonedDateTime.now(date.zone), date
        ).toMillis()
        if (delta > 0) return delta
    }
    return null
}

/** Resolve a 429's retry-after hint, **unclamped**, in
 *  milliseconds. Reads the generic `Retry-After` header first
 *  (seconds or HTTP-date — works for any provider), then falls
 *  back to the Gemini error body's `RetryInfo.retryDelay` (e.g.
 *  `"3600s"`; absent for non-Gemini providers, so the peek is a
 *  harmless no-op there). Returns null when neither is present
 *  or parseable. `peekBody` leaves the real response body
 *  untouched for the downstream parser. */
internal fun retryAfterHintMs(response: Response, peekedBody: String?): Long? {
    val raw = response.header("Retry-After") ?: response.header("retry-after")
    if (!raw.isNullOrBlank()) {
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { secs -> if (secs > 0) return secs * 1000 }
        runCatching {
            val date = java.time.ZonedDateTime.parse(
                trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            )
            val delta = java.time.Duration.between(
                java.time.ZonedDateTime.now(date.zone), date
            ).toMillis()
            if (delta > 0) return delta
        }
    }
    val body = peekedBody ?: return null
    return runCatching {
        val details = com.google.gson.JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("error")?.getAsJsonArray("details") ?: return null
        for (d in details) {
            val obj = d.asJsonObject
            if (obj.get("@type")?.asString?.contains("RetryInfo") != true) continue
            val secs = obj.get("retryDelay")?.asString?.removeSuffix("s")?.toDoubleOrNull()
                ?: continue
            return (secs * 1000).toLong()
        }
        null
    }.getOrNull()
}

/** Best-effort model id for a request being benched. Prefer the
 *  trace tag installed by the caller; Gemini path-encodes it
 *  (`/v1beta/models/<model>:generateContent`); every other provider
 *  carries it in the JSON request body's `model` field. The request
 *  has already been sent by the time this runs, so body re-reading is
 *  only a fallback for untagged calls. */
internal fun modelForRequest(request: okhttp3.Request): String? {
    ApiTracer.currentModel?.takeIf { it.isNotBlank() }?.let { return it }
    if (request.url.host == "generativelanguage.googleapis.com") {
        return request.url.pathSegments.lastOrNull()
            ?.substringBefore(":")?.takeIf { it.isNotBlank() }
    }
    val body = request.body ?: return null
    return runCatching {
        val buf = Buffer()
        body.writeTo(buf)
        com.google.gson.JsonParser.parseString(buf.readUtf8())
            .asJsonObject.get("model")?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun cohereProviderIdFor(requestHost: String): String? {
    ProviderRegistry.findByHost(requestHost)?.id?.let { return it }
    return ProviderRegistry.getAll().firstOrNull { service ->
        service.auxHosts.any(::isCohereHost) ||
            isCohereHost(hostFromUrl(service.baseUrl)) ||
            isCohereHost(hostFromUrl(service.nativeCapabilityUrl)) ||
            isCohereHost(hostFromUrl(service.nativeRerankUrl)) ||
            isCohereHost(hostFromUrl(service.nativeModerationUrl))
    }?.id
}

private fun hostFromUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return runCatching { java.net.URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()
}

private fun isCohereHost(host: String?): Boolean {
    val normalized = host?.lowercase(Locale.ROOT) ?: return false
    return normalized == "cohere.com" || normalized.endsWith(".cohere.com")
}

/** True when a Google 429 body reports a **per-day** quota as
 *  exhausted — a `QuotaFailure` detail whose violation matches
 *  either the per-day request cap
 *  (`generate_requests_per_model_per_day`) or the per-day token
 *  cap (`*_tokens_per_model_per_day`). Both reset at the same
 *  Pacific-midnight boundary. Google's `retryDelay` hint for
 *  these is unreliable (often short even though the quota only
 *  refills at the day boundary), so the caller benches until the
 *  reset instead. Per-minute quotas (`*_per_minute`) are
 *  deliberately not matched — the in-line retry loop clears
 *  those within a minute. */
private fun googleDailyQuotaExhausted(peekedBody: String?): Boolean = runCatching {
    val body = peekedBody ?: return false
    val details = com.google.gson.JsonParser.parseString(body).asJsonObject
        .getAsJsonObject("error")?.getAsJsonArray("details") ?: return false
    for (d in details) {
        val obj = d.asJsonObject
        if (obj.get("@type")?.asString?.contains("QuotaFailure") != true) continue
        val violations = obj.getAsJsonArray("violations") ?: continue
        for (v in violations) {
            val vo = v.asJsonObject
            val metric = vo.get("quotaMetric")?.asString ?: ""
            val id = vo.get("quotaId")?.asString ?: ""
            val perDayMetric = metric.endsWith("_per_model_per_day") ||
                metric.endsWith("_tokens_per_model_per_day")
            if (perDayMetric || id.contains("PerDay")) return true
        }
    }
    false
}.getOrDefault(false)

/** Epoch-ms of the next midnight in America/Los_Angeles — when
 *  Gemini's per-day request quotas reset (free and paid tiers
 *  alike). */
private fun nextPacificMidnightMs(): Long {
    val pacific = java.time.ZoneId.of("America/Los_Angeles")
    val nextMidnight = java.time.ZonedDateTime.now(pacific)
        .toLocalDate().plusDays(1).atStartOfDay(pacific)
    return nextMidnight.toInstant().toEpochMilli()
}

/** True when a Cohere 429 body reports the Trial-key monthly cap
 *  ("…using a Trial key, which is limited to N API calls /
 *  month…"). Cohere ships no Retry-After header and no structured
 *  reset time, so this text match is the only signal. */
private fun cohereTrialQuotaExhausted(peekedBody: String?): Boolean = runCatching {
    val body = peekedBody ?: return false
    val msg = com.google.gson.JsonParser.parseString(body).asJsonObject
        .get("message")?.asString ?: return false
    msg.contains("Trial key", ignoreCase = true) && msg.contains("month", ignoreCase = true)
}.getOrDefault(false)

/** True when a 429 body reports the provider account being out of
 *  money — credits exhausted, balance too low, or a spending /
 *  monthly limit hit. Distinct from a rate-limit 429: retrying
 *  won't clear it, so the caller benches the model instead of
 *  burning the in-line retry budget. Checks the structured error
 *  type/code first (e.g. OpenAI's `insufficient_quota`), then a
 *  phrase fallback — all billing-specific wording that never
 *  appears in a plain "slow down" 429. */
internal fun creditOrSpendingLimitExhausted(peekedBody: String?): Boolean = runCatching {
    val body = peekedBody ?: return false
    val typeOrCode = runCatching {
        val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
        val err = obj.getAsJsonObject("error")
        listOfNotNull(
            err?.get("type")?.asString,
            err?.get("code")?.asString,
            obj.get("code")?.asString
        ).joinToString(" ")
    }.getOrDefault("")
    if (typeOrCode.contains("insufficient_quota", ignoreCase = true)) return@runCatching true
    // Unambiguous billing wording only. Dropped "billing details" and
    // "exceeded your current quota" (audit data#30): both also appear in plain
    // RATE-limit 429 bodies, so they caused a 6h bench for a 429 a quick retry
    // would have cleared. OpenAI's real billing 429 is already caught above via
    // the structured insufficient_quota type/code.
    val needles = listOf(
        "spending limit", "all available credits", "purchase more credits",
        "insufficient credits", "insufficient balance", "credit balance is too low",
        "out of credits"
    )
    needles.any { body.contains(it, ignoreCase = true) }
}.getOrDefault(false)

/** Epoch-ms of the start of the next calendar month (device local
 *  time). Best-effort reset point for a monthly trial-quota 429 —
 *  the real window is whenever the provider's trial month rolls. */
private fun nextMonthStartMs(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.MONTH, 1)
    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
