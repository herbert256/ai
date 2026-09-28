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

/** The report-wide 🌐 web-search / 🧠 reasoning chips apply to every model of
 *  the run. A model that can't take one runs without it — as the per-model
 *  retry already did — instead of failing its preflight with "reasoning effort
 *  is not supported". Only values that came from the chips are dropped;
 *  preset / advanced values stay strict (an explicit experiment must not run
 *  silently changed). */
internal fun AgentParameters.withoutUnsupportedReportChips(
    settings: Settings, provider: AppService, model: String,
    chipWebSearch: Boolean, chipReasoning: String?
): AgentParameters {
    var p = this
    if (chipReasoning != null && p.reasoningEffort == chipReasoning &&
        !settings.acceptsReasoningEffortParam(provider, model)) p = p.copy(reasoningEffort = null)
    if (chipWebSearch && p.webSearchTool && !settings.isWebSearchCapable(provider, model)) p = p.copy(webSearchTool = false)
    return p
}

/** Capture replay settings and validate attached knowledge before saving a new report. */
internal fun preparePrimaryExecution(
    context: Context, question: String, tasks: List<ReportViewModel.ReportTask>,
    overlay: AgentParameters?, knowledgeBaseIds: List<String>,
    settings: Settings, repository: AnalysisRepository,
    externalContext: ExternalReportContext = ExternalReportContext(),
    chipWebSearch: Boolean = false, chipReasoning: String? = null
) {
    tasks.forEach { task ->
        val effectiveQuestion = if (question.isNotBlank()) repository.resolveReportPrompt(question, task.runtimeAgent)
            else externalContext.expandPrompt(task.defaultPrompt.orEmpty()) {
                repository.resolveReportPrompt(it, task.runtimeAgent)
            }
        require(effectiveQuestion.isNotBlank()) { "${task.runtimeAgent.name} has no default prompt. Enter a report prompt or assign a default prompt." }
        val params = repository.effectiveReportParameters(task.resolvedParams,
            overlay?.withoutUnsupportedReportChips(settings, task.runtimeAgent.provider, task.runtimeAgent.model,
                chipWebSearch, chipReasoning),
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
