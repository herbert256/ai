package com.ai.viewmodel

import android.content.Context
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.TokenUsage
import com.ai.data.buildResultsBlock
import com.ai.model.ReportModel
import com.ai.model.Settings
import com.ai.model.expandAgentToModel
import com.ai.model.toReportModel

/** Pure helper functions lifted out of [ReportViewModel]. Each
 *  takes only its inputs and a Settings (or context) — no view-
 *  model instance state — so they can live as free functions in
 *  the same package and unit-test in isolation. Keeping them on
 *  the class added no benefit beyond co-location; pulling them
 *  out is a step toward the eventual full per-concern VM split
 *  (regen / fan-out / translate / secondaries) without disturbing
 *  any private state on ReportViewModel. */

/** Group system prompt wins over agent system prompt — used so a
 *  Flock / Swarm system prompt overrides the per-agent default when
 *  the agent runs inside that group. */
internal fun resolveSystemPromptText(aiSettings: Settings, agentSpId: String?, groupSpId: String?): String? {
    return (groupSpId ?: agentSpId)?.let { aiSettings.getSystemPromptById(it)?.prompt }
}

/** First Flock the agent is a member of with a still-resolvable
 *  system prompt id. Used during report generation to pick up a
 *  Flock-level system prompt when one applies. */
internal fun findFlockSystemPromptIdForAgent(aiSettings: Settings, agentId: String): String? {
    return aiSettings.flocks.filter { agentId in it.agentIds && it.systemPromptId != null }
        .firstNotNullOfOrNull { flock -> flock.systemPromptId?.takeIf { aiSettings.getSystemPromptById(it) != null } }
}

/** First Swarm whose members include the (provider, model) pair AND
 *  carries a still-resolvable system prompt id. Mirrors
 *  [findFlockSystemPromptIdForAgent] for the Swarm dispatch path. */
internal fun findSwarmSystemPromptIdForMember(aiSettings: Settings, provider: AppService, model: String): String? {
    return aiSettings.swarms.filter { swarm ->
        swarm.systemPromptId != null && swarm.members.any { it.provider.id == provider.id && it.model == model }
    }.firstNotNullOfOrNull { swarm -> swarm.systemPromptId?.takeIf { aiSettings.getSystemPromptById(it) != null } }
}

/** Lookup the per-token pricing for (provider, model) and multiply
 *  by the token usage to produce a cost. Returns null when the call
 *  had no token usage reported. */
internal fun calculateResponseCost(context: Context, provider: AppService, model: String, tokenUsage: TokenUsage?): Double? {
    if (tokenUsage == null) return null
    return PricingCache.computeCost(tokenUsage, PricingCache.getPricing(context, provider, model))
}

/** Reverse the persisted ReportAgent rows into ReportModel entries
 *  the selection screen understands. Real-agent rows (UUID id, still
 *  resolvable in aiSettings) come back as agent-typed models;
 *  "swarm:provider:model" rows and orphaned ones come back as direct
 *  provider/model entries. */
internal fun reportToModels(report: Report, aiSettings: Settings): List<ReportModel> {
    return report.agents.mapNotNull { ra ->
        val provider = AppService.findById(ra.provider) ?: return@mapNotNull null
        if (ra.agentId.startsWith("swarm:")) toReportModel(provider, ra.model)
        else aiSettings.getAgentById(ra.agentId)?.let { expandAgentToModel(it, aiSettings) }
            ?: toReportModel(provider, ra.model)
    }
}

/** Extract the host from a provider's baseUrl so the fan-out
 *  pre-acquire path can call [PricingCache] er, [com.ai.data.ProviderThrottle.acquire]
 *  with the same host the OkHttp interceptor would see. Returns
 *  "" on a malformed baseUrl — ProviderThrottle.acquire treats an
 *  empty host as a no-op pass-through, which is the safe direction
 *  (better to skip a permit acquire than to deadlock on a parsing
 *  failure). */
internal fun providerHost(service: AppService): String =
    runCatching { java.net.URI(service.baseUrl).host ?: "" }.getOrDefault("")

/** Reorder [items] so consecutive entries target different hosts:
 *  group by [hostKey], shuffle each group for run-to-run jitter,
 *  then round-robin pick one entry from each group until empty.
 *
 *  Why: when 15 fan-out pairs all target the same provider host
 *  (per-host cap = 3), the first 12 to launch hold the outer
 *  global + fan-out cap permits idly while waiting on the per-host
 *  semaphore — pairs for other hosts queue behind them on the
 *  outer caps for no reason. Interleaving by host means launch
 *  position N + 1 is usually a different host than position N, so
 *  the outer caps stay productive even when one host's per-host
 *  cap is saturated. The shuffle-within-group keeps run-to-run
 *  order non-deterministic. */
internal fun <T> interleaveByHost(items: List<T>, hostKey: (T) -> String?): List<T> {
    if (items.size <= 1) return items
    val groups = items
        .groupBy { hostKey(it) ?: "" }
        .values
        .map { it.shuffled().toMutableList() }
    val result = ArrayList<T>(items.size)
    while (groups.any { it.isNotEmpty() }) {
        for (g in groups) if (g.isNotEmpty()) result.add(g.removeAt(0))
    }
    return result
}

/** Per-host FIFO gate guarding the [acquireOrRequeue] poll loop.
 *  `kotlinx.coroutines.sync.Mutex` hands the lock to waiters in
 *  arrival order, so when two big runs (e.g. a translation run and a
 *  fan-icon run) hammer the same provider hosts, their workers
 *  acquire the host in turn instead of one run's flood perpetually
 *  jumping the other's poll-race. */
private val hostAcquireFairness =
    java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

/** Non-blocking replacement for [com.ai.data.ProviderThrottle.acquire]
 *  in the list dispatchers (Fan Out / translation / model reports).
 *  Polls [com.ai.data.ProviderThrottle.tryAcquire]; when the host's
 *  rate / concurrency cap is full it `delay`s (a coroutine
 *  suspension — NOT a `Thread.sleep`) until roughly the
 *  `availableAtMs` the throttle reported, then re-checks. The
 *  suspension frees the worker thread so every other ready entry
 *  proceeds — the capped entry effectively waits its turn at the
 *  back of the line.
 *
 *  The whole poll loop runs under a per-host FIFO [Mutex] so callers
 *  acquire the host in arrival order — without it, a big run
 *  flooding a host (its workers re-polling continuously) can starve
 *  another run's workers for minutes, since every [tryAcquire] is a
 *  fresh race with no queue. The mutex only serialises the *attempt*;
 *  a worker that gets a slot releases the lock immediately, so the
 *  host's concurrency cap is still fully used.
 *
 *  [onThrottled] / [onCleared] let a dispatcher mirror the wait into
 *  its existing throttled-tracking StateFlow; both default to no-ops
 *  for dispatchers that don't surface it. `delay` is
 *  cancellation-aware, so Stop / navigate-away unwinds cleanly. */
internal suspend fun acquireOrRequeue(
    host: String,
    onThrottled: (availableAtMs: Long) -> Unit = {},
    onCleared: () -> Unit = {}
): com.ai.data.ProviderThrottle.Releaser {
    val gate = hostAcquireFairness.computeIfAbsent(host) { kotlinx.coroutines.sync.Mutex() }
    var throttledNotified = false
    gate.lock()
    try {
        while (true) {
            when (val o = com.ai.data.ProviderThrottle.tryAcquire(host)) {
                is com.ai.data.ProviderThrottle.Outcome.Acquired -> {
                    if (throttledNotified) onCleared()
                    return o.releaser
                }
                is com.ai.data.ProviderThrottle.Outcome.Blocked -> {
                    if (!throttledNotified) {
                        onThrottled(o.availableAtMs)
                        throttledNotified = true
                    }
                    kotlinx.coroutines.delay(
                        (o.availableAtMs - System.currentTimeMillis()).coerceIn(100L, 10_000L)
                    )
                }
            }
        }
    } finally {
        gate.unlock()
    }
}

/** Translate-mode caller for prompt + results: when [language] is
 *  null, returns the report's untranslated prompt + result block.
 *  Otherwise looks up the per-target translation rows and substitutes
 *  in the translated prompt + AGENT:<agentId> for each agent's body.
 *  Falls back to the original text per-item if a translation is
 *  missing so a partial translation set still produces a coherent
 *  batch. */
internal fun buildLanguageInputs(
    report: Report,
    secondaries: List<SecondaryResult>,
    language: String?,
    includeIds: Set<Int>?
): Pair<String, String> {
    if (language == null) {
        return report.prompt to buildResultsBlock(report, includeIds)
    }
    val byTarget = secondaries
        .filter { it.kind == SecondaryKind.TRANSLATE && it.targetLanguage == language && !it.content.isNullOrBlank() }
        .associateBy { (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "") }
    val translatedPrompt = byTarget["PROMPT:prompt"]?.content ?: report.prompt
    val sb = StringBuilder()
    val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
    var emitted = 0
    val total = if (includeIds != null) successful.indices.count { (it + 1) in includeIds } else successful.size
    successful.forEachIndexed { idx, agent ->
        val originalId = idx + 1
        if (includeIds != null && originalId !in includeIds) return@forEachIndexed
        val body = byTarget["AGENT:${agent.agentId}"]?.content ?: (agent.responseBody?.trim() ?: "")
        sb.append("[").append(originalId).append("]\n").append(body)
        emitted++
        if (emitted != total) sb.append("\n\n")
    }
    return translatedPrompt to sb.toString()
}

/** Bundle of translated inputs for one target language, ready to
 *  substitute into @QUESTION@ / @TITLE@ / @REPORT@ / @RESPONSE@ /
 *  body slots. Reused by meta @TITLE@, rerank, moderation, and both
 *  flavours of fan-in — they all need the same per-language lookup
 *  but different slicings of it (results block vs per-agent map).
 *
 *  Each field falls back to the original on a missing translation
 *  row so a partial translation set still produces a coherent run.
 *  The [native] companion is whatever the translation rows recorded
 *  (e.g. "Nederlands" for "Dutch") — null when no row carried it. */
internal data class LangCtx(
    val prompt: String,
    val title: String,
    val native: String?,
    val bodiesByAgentId: Map<String, String>
)

/** Build a [LangCtx] for [language]. Returns null when [language]
 *  is null or blank (the "Original / no translation" path — callers
 *  branch on null to keep their original-text behaviour). */
internal fun lookupLanguageTranslations(
    report: Report,
    secondaries: List<SecondaryResult>,
    language: String?
): LangCtx? {
    if (language.isNullOrBlank()) return null
    val translates = secondaries.filter {
        it.kind == SecondaryKind.TRANSLATE &&
            it.targetLanguage == language &&
            !it.content.isNullOrBlank()
    }
    val byTarget = translates.associateBy {
        (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "")
    }
    val prompt = byTarget["PROMPT:prompt"]?.content ?: report.prompt
    val title = byTarget["TITLE:title"]?.content ?: (report.title ?: "")
    val native = translates.firstNotNullOfOrNull { it.targetLanguageNative }
    val bodies = report.agents
        .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        .associate { agent ->
            agent.agentId to (
                byTarget["AGENT:${agent.agentId}"]?.content?.trim()
                    ?: agent.responseBody?.trim().orEmpty()
                )
        }
    return LangCtx(prompt, title, native, bodies)
}
