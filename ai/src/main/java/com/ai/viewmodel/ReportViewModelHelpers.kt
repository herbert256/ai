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
