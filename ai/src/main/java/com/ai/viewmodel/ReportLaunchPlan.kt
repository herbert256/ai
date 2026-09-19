package com.ai.viewmodel

import android.content.Context
import com.ai.data.*
import com.ai.model.Settings
import com.ai.ui.share.ExternalReportContext

/** Keep report views and secondary operations supplied with the actual default questions. */
internal fun reportPromptOverview(question: String, tasks: List<ReportViewModel.ReportTask>): String {
    if (question.isNotBlank()) return question
    val prompts = tasks.groupBy { it.reportAgent.executionConfig?.prompt.orEmpty() }
    return if (prompts.size == 1) prompts.keys.first() else prompts.entries.joinToString("\n\n") { (text, members) ->
        "## ${members.joinToString(", ") { it.runtimeAgent.name }}\n$text"
    }
}

/** Capture replay settings and validate attached knowledge before saving a new report. */
internal fun preparePrimaryExecution(
    context: Context, question: String, tasks: List<ReportViewModel.ReportTask>,
    overlay: AgentParameters?, knowledgeBaseIds: List<String>,
    settings: Settings, repository: AnalysisRepository,
    externalContext: ExternalReportContext = ExternalReportContext()
) {
    tasks.forEach { task ->
        val effectiveQuestion = if (question.isNotBlank()) repository.resolveReportPrompt(question, task.runtimeAgent)
            else externalContext.expandPrompt(task.defaultPrompt.orEmpty()) {
                repository.resolveReportPrompt(it, task.runtimeAgent)
            }
        require(effectiveQuestion.isNotBlank()) { "${task.runtimeAgent.name} has no default prompt. Enter a report prompt or assign a default prompt." }
        val params = repository.effectiveReportParameters(task.resolvedParams, overlay,
            task.runtimeAgent.provider, task.runtimeAgent.model, context)
        task.reportAgent.executionConfig = ReportExecutionConfig(
            params, settings.getEffectiveEndpointUrlForAgent(task.runtimeAgent),
            effectiveQuestion, baseParameters = task.resolvedParams
        )
    }
    knowledgeBaseIds.firstOrNull()?.let { id ->
        KnowledgeStore.loadKnowledgeBase(context, id)
            ?: throw java.io.IOException("Attached knowledge base is unavailable")
    }
}
