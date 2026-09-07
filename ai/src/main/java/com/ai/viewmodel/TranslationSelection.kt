package com.ai.viewmodel

import com.ai.data.*
import com.ai.model.Worker
import com.ai.ui.shared.shortModelName

/** Null scope is reserved for intentional whole-report/programmatic launches. */
data class TranslationSelection(
    val itemIds: Set<String>? = null,
    val terminology: String = "",
    val singleWorker: Worker? = null,
    val confirmed: Boolean = false,
    val sourceDigests: Map<String,String> = emptyMap()
)

fun translatableReportItems(sourceReport: Report, secondaries: List<SecondaryResult>): List<TranslationItem> {
    val items = mutableListOf<TranslationItem>()
    if (sourceReport.title.isNotBlank()) {
        items += TranslationItem(
            id = "title",
            label = "Report title",
            kind = TranslationKind.TITLE,
            sourceText = sourceReport.title
        )
    }
    sourceReport.titleLong?.takeIf { it.isNotBlank() }?.let { longTitle ->
        items += TranslationItem(
            id = "titleLong",
            label = "Report long title",
            kind = TranslationKind.TITLE_LONG,
            sourceText = longTitle
        )
    }
    items += TranslationItem(
        id = "prompt",
        label = "Report prompt",
        kind = TranslationKind.PROMPT,
        sourceText = sourceReport.prompt
    )
    sourceReport.agents
        .forEach { agent ->
            val body = agent.responseBody?.takeIf(String::isNotBlank) ?: return@forEach
            if (agent.reportStatus != ReportStatus.SUCCESS) return@forEach
            val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
            items += TranslationItem(
                id = "agent:${agent.agentId}",
                label = "${agent.agentName} · $provDisplay / ${shortModelName(agent.model)}",
                kind = TranslationKind.AGENT_RESPONSE,
                sourceText = body,
                target = agent.agentId
            )
        }
    // Per-model response titles (ReportAgent.modelTitle), one
    // per success agent that has a generated title.
    sourceReport.agents
        .forEach { agent ->
            val title = agent.modelTitle?.takeIf(String::isNotBlank) ?: return@forEach
            if (agent.reportStatus != ReportStatus.SUCCESS) return@forEach
            val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
            items += TranslationItem(
                id = "agentTitle:${agent.agentId}",
                label = "Title: ${agent.agentName} · $provDisplay / ${shortModelName(agent.model)}",
                kind = TranslationKind.AGENT_TITLE,
                sourceText = title,
                target = agent.agentId
            )
        }
    // Per-fan-out-pair response titles (SecondaryResult.title on
    // fan-out pair rows), one per pair that has a generated title.
    secondaries
        .forEach { s ->
            val title = s.title?.takeIf(String::isNotBlank) ?: return@forEach
            if (s.kind != SecondaryKind.META || s.fanOutSourceAgentId == null) return@forEach
            val provDisplay = AppService.findById(s.providerId)?.id ?: s.providerId
            items += TranslationItem(
                id = "fanoutTitle:${s.id}",
                label = "Fan title: $provDisplay / ${shortModelName(s.model)}",
                kind = TranslationKind.FANOUT_TITLE,
                sourceText = title,
                target = s.id
            )
        }
    // Every chat-type Meta result is a candidate for translation.
    // Label the row by the user-given Meta prompt name so the
    // progress screen / per-call detail show "Compare 1: …" or
    // "Critique 2: …" — driven entirely by the CRUD prompt name,
    // not a hardcoded "Summary" / "Compare".
    secondaries.filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
        .forEachIndexed { idx, s ->
            val content = s.content?.takeIf(String::isNotBlank) ?: return@forEachIndexed
            val provDisplay = AppService.findById(s.providerId)?.id ?: s.providerId
            val name = s.metaPromptName?.takeIf { it.isNotBlank() }
                ?: com.ai.data.legacyKindDisplayName(s.kind)
            items += TranslationItem(
                id = "meta:${s.id}",
                label = "$name ${idx + 1}: $provDisplay / ${shortModelName(s.model)}",
                kind = TranslationKind.META,
                sourceText = content,
                target = s.id
            )
        }

    return items
}
