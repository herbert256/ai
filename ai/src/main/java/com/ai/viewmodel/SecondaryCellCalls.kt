package com.ai.viewmodel

import android.content.Context
import com.ai.data.AnalysisResponse
import com.ai.data.NetworkSettings
import com.ai.data.PricingCache
import com.ai.data.ProviderThrottle
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.TokenUsage
import com.ai.data.withTraceCategory
import com.ai.data.withTraceFilenameSink
import com.ai.model.Agent
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.model.Worker
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/**
 * The two per-item call shapes every batch engine reduces to, promoted out
 * of the engines that used to copy them verbatim (audit finding 3):
 *
 *  - **Pool shape** ([runPooledWorkerCall] + [resolvePooledWinner]) — the
 *    item is judged by the worker round-robin chain; a failed worker is
 *    replaced by the next one and the winner is only known when the chain
 *    settles. Tournament matches, Compare cells, Fan Meta pairs,
 *    Translation items.
 *  - **Fixed-judge shape** ([runFixedJudgeCall]) — the item's model is
 *    fixed up front; a failure is a real error (no pool to fall back to).
 *    Judge-the-judges cells, Rank-the-translators cells.
 */

// ===== Pool shape =====

/** One settled worker-pool call: the chain outcome plus the call's
 *  duration and the winning call's trace filename (for the per-item 🐞). */
internal class PooledWorkerCall(
    val outcome: WorkerOutcome,
    val durationMs: Long,
    val traceFile: String?,
)

/** Run one worker-pool call with the plumbing every pool consumer needs:
 *  the per-item throttle-wait observer (a dynamic-host call's rate-limit /
 *  concurrency wait happens inside `ProviderThrottle.acquire`, not at the
 *  batch layer's onThrottled hook), the trace-filename sink, and an
 *  optional per-item trace category. */
internal suspend fun runPooledWorkerCall(
    workerRunner: WorkerRunner,
    aiSettings: Settings,
    context: Context,
    prompt: InternalPrompt,
    resolved: String,
    traceCategory: String? = null,
    onThrottleWait: (Boolean) -> Unit = {},
    accept: (AnalysisResponse) -> Boolean,
): PooledWorkerCall {
    val started = System.currentTimeMillis()
    val traceSink = AtomicReference<String?>(null)
    val outcome = withContext(ProviderThrottle.throttleWaitObserver.asContextElement(onThrottleWait)) {
        withTraceFilenameSink(traceSink) {
            if (traceCategory != null) withTraceCategory(traceCategory) {
                workerRunner.run(prompt, resolved, aiSettings, context, accept)
            } else {
                workerRunner.run(prompt, resolved, aiSettings, context, accept)
            }
        }
    }
    return PooledWorkerCall(outcome, System.currentTimeMillis() - started, traceSink.get())
}

/** The winning worker of a successful pool call, resolved and costed. */
internal class PooledWinner(
    /** The winner with its effective model — null when the worker can no
     *  longer be resolved (left the swarm since the call was queued). */
    val agent: Agent?,
    val inTokens: Int,
    val outTokens: Int,
    val inCost: Double,
    val outCost: Double,
    val tokenUsage: TokenUsage?,
)

/** Resolve a successful pool call's winning worker and split its costs,
 *  rolling the spend into AI Usage under [usageKind]. Token counts are
 *  taken from the response whenever it carries usage — even when the
 *  winner can't be resolved any more — so they never silently vanish. */
internal suspend fun resolvePooledWinner(
    appViewModel: AppViewModel,
    context: Context,
    aiSettings: Settings,
    success: WorkerOutcome.Success,
    usageKind: String,
    durationMs: Long,
): PooledWinner {
    val winAgent = aiSettings.resolveWorker(success.worker)?.let {
        it.copy(model = aiSettings.getEffectiveModelForAgent(it))
    }
    val tu = success.response.tokenUsage
    val inT = tu?.inputTokens ?: 0
    val outT = tu?.outputTokens ?: 0
    var inCost = 0.0
    var outCost = 0.0
    if (winAgent != null && tu != null && (inT > 0 || outT > 0)) {
        val pricing = PricingCache.getPricing(context, winAgent.provider, winAgent.model)
        val split = PricingCache.computeInOutCost(tu, pricing)
        inCost = split.first
        outCost = split.second
        appViewModel.settingsPrefs.updateUsageStatsAsync(winAgent.provider, winAgent.model, tu, kind = usageKind, durationMs = durationMs)
    }
    return PooledWinner(winAgent, inT, outT, inCost, outCost, tu)
}

/** One pooled per-item call reduced to its two endings: the settled
 *  successful call (winner resolved + costed) or the standard error. */
internal sealed class PooledItemOutcome {
    class Success(
        val call: PooledWorkerCall,
        val outcome: WorkerOutcome.Success,
        val winner: PooledWinner,
    ) : PooledItemOutcome()

    /** [rateLimited]: the whole pool was cooling (a try-later condition),
     *  as opposed to no worker producing a usable artifact. */
    class Error(val message: String, val rateLimited: Boolean) : PooledItemOutcome()
}

/** The pooled per-item shape Tournament / Compare / Translation shared
 *  verbatim: run the worker chain under the per-item "Batch item"
 *  ceiling, resolve + cost the winner on success (usage rolled into AI
 *  Usage under [usageKind] by [resolvePooledWinner]), and reduce every
 *  failure to the caller's standard message ([timeoutMessage] /
 *  [rateLimitedMessage] / [noResultMessage] keep each engine's wording
 *  unchanged). The timeout is caught HERE so a wedged chain fails just
 *  this item, never the batch. */
internal suspend fun runPooledItemCall(
    appViewModel: AppViewModel,
    workerRunner: WorkerRunner,
    context: Context,
    prompt: InternalPrompt,
    resolved: String,
    usageKind: String,
    timeoutMessage: String,
    rateLimitedMessage: String,
    noResultMessage: String,
    traceCategory: String? = null,
    onThrottleWait: (Boolean) -> Unit = {},
    accept: (AnalysisResponse) -> Boolean,
): PooledItemOutcome {
    val aiSettings = appViewModel.uiState.value.aiSettings
    val call = try {
        withTimeout(NetworkSettings.batchItemTimeoutMs) {
            runPooledWorkerCall(workerRunner, aiSettings, context, prompt, resolved, traceCategory, onThrottleWait, accept)
        }
    } catch (e: TimeoutCancellationException) {
        return PooledItemOutcome.Error(timeoutMessage, rateLimited = false)
    }
    return when (val outcome = call.outcome) {
        is WorkerOutcome.Success -> PooledItemOutcome.Success(
            call, outcome,
            resolvePooledWinner(appViewModel, context, aiSettings, outcome, usageKind, call.durationMs)
        )
        WorkerOutcome.AllRateLimited -> PooledItemOutcome.Error(rateLimitedMessage, rateLimited = true)
        WorkerOutcome.Failed -> PooledItemOutcome.Error(noResultMessage, rateLimited = false)
    }
}

// ===== Fixed-judge shape =====

/** A concrete judge resolved from a worker prompt's swarm — the fixed-host
 *  engines dispatch each cell to one of these instead of the chain. */
internal data class ResolvedJudge(val worker: Worker, val providerId: String, val model: String) {
    val key: String get() = "$providerId/$model"
}

/** Expand [prompt]'s worker chain into the concrete, resolvable judge
 *  models (one per swarm member). */
internal fun resolveJudges(aiSettings: Settings, prompt: InternalPrompt): List<ResolvedJudge> =
    prompt.workers.flatMap { aiSettings.expandWorker(it) }
        .distinctBy { "${it.provider}/${it.model}" }
        .mapNotNull { w ->
            val raw = aiSettings.resolveWorker(w) ?: return@mapNotNull null
            ResolvedJudge(w, raw.provider.id, aiSettings.getEffectiveModelForAgent(raw))
        }

/** One settled fixed-judge cell call. */
internal sealed class FixedJudgeOutcome {
    /** HTTP success AND the verdict / score parsed — record the cell. */
    class Accepted(
        val response: AnalysisResponse,
        val inTokens: Int,
        val outTokens: Int,
        val inCost: Double,
        val outCost: Double,
        val tokenUsage: TokenUsage?,
        val traceFile: String?,
    ) : FixedJudgeOutcome()

    /** Unresolvable judge, transport error, or no parseable artifact —
     *  a category-1 "real error" (fixed model, no pool to fall back to).
     *  [httpStatusCode] carries the transport status (e.g. a 429 whose
     *  bench-requeue budget ran out) for the Broken-work transient hint. */
    class Rejected(val message: String, val httpStatusCode: Int? = null) : FixedJudgeOutcome()
}

/** Call ONE specific [judge] (not the worker chain) with [resolved] and
 *  validate the reply via [accepted]; on success split the costs and roll
 *  them into AI Usage under [usageKind]. [noArtifactMessage] is the error
 *  for an HTTP-200 reply with no parseable verdict / score. */
internal suspend fun runFixedJudgeCall(
    appViewModel: AppViewModel,
    context: Context,
    aiSettings: Settings,
    judge: ResolvedJudge,
    resolved: String,
    usageKind: String,
    noArtifactMessage: String,
    accepted: (AnalysisResponse) -> Boolean,
): FixedJudgeOutcome {
    val started = System.currentTimeMillis()
    val raw = aiSettings.resolveWorker(judge.worker)
        ?: return FixedJudgeOutcome.Rejected("judge ${judge.key} could not be resolved")
    val agent = raw.copy(
        apiKey = aiSettings.getEffectiveApiKeyForAgent(raw),
        model = aiSettings.getEffectiveModelForAgent(raw)
    )
    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
    val traceSink = AtomicReference<String?>(null)
    val resp = withTraceFilenameSink(traceSink) {
        appViewModel.repository.analyzeWithAgent(
            agent, "", resolved, context = context, baseUrl = baseUrl, retry = false
        )
    }
    if (!resp.isSuccess || !accepted(resp)) {
        return FixedJudgeOutcome.Rejected(
            resp.error?.takeIf { it.isNotBlank() } ?: noArtifactMessage,
            resp.httpStatusCode
        )
    }
    val tu = resp.tokenUsage
    val inT = tu?.inputTokens ?: 0
    val outT = tu?.outputTokens ?: 0
    var inCost = 0.0
    var outCost = 0.0
    if (tu != null && (inT > 0 || outT > 0)) {
        val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
        val split = PricingCache.computeInOutCost(tu, pricing)
        inCost = split.first
        outCost = split.second
        appViewModel.settingsPrefs.updateUsageStatsAsync(agent.provider, agent.model, tu, kind = usageKind, durationMs = System.currentTimeMillis() - started)
    }
    return FixedJudgeOutcome.Accepted(resp, inT, outT, inCost, outCost, tu, traceSink.get())
}

/** Stamp an item's row with [message] after its call failed, preserving
 *  whatever the row already holds (the disk row wins over [placeholder]
 *  when it still exists). Shared error stamp of all four engines'
 *  per-item bodies. [httpStatusCode] makes the failure machine-readable
 *  — 429 marks a transient pool-cooling / rate-limit error the
 *  Broken-work screen can tell apart from a permanent one. */
internal fun recordItemCallError(
    context: Context,
    reportId: String,
    rowId: String,
    placeholder: SecondaryResult,
    message: String,
    started: Long,
    httpStatusCode: Int? = null,
) {
    val cur = SecondaryResultStorage.get(context, reportId, rowId) ?: placeholder
    SecondaryResultStorage.save(context, cur.copy(
        errorMessage = message,
        durationMs = System.currentTimeMillis() - started,
        httpStatusCode = httpStatusCode ?: cur.httpStatusCode
    ))
}
