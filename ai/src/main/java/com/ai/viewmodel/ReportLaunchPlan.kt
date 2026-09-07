package com.ai.viewmodel

import android.content.Context
import com.ai.data.*
import com.ai.model.Settings

/** Capture replay settings and validate attached knowledge before saving a new report. */
internal fun preparePrimaryExecution(
    context: Context, question: String, tasks: List<ReportViewModel.ReportTask>,
    overlay: AgentParameters?, knowledgeBaseIds: List<String>,
    settings: Settings, repository: AnalysisRepository
) {
    tasks.forEach { task ->
        val params = repository.effectiveReportParameters(task.resolvedParams, overlay,
            task.runtimeAgent.provider, task.runtimeAgent.model, context)
        task.reportAgent.executionConfig = ReportExecutionConfig(
            params, settings.getEffectiveEndpointUrlForAgent(task.runtimeAgent),
            repository.resolveReportPrompt(question, task.runtimeAgent)
        )
    }
    knowledgeBaseIds.firstOrNull()?.let { id ->
        KnowledgeStore.loadKnowledgeBase(context, id)
            ?: throw java.io.IOException("Attached knowledge base is unavailable")
    }
}
