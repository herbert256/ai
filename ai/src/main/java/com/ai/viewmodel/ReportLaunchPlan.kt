package com.ai.viewmodel

import android.content.Context
import com.ai.data.*
import com.ai.model.Settings
import com.ai.model.InternalPrompt

/** Uses the same parameter and worker resolvers as dispatch; no credentials enter the preview. */
internal fun buildPrimaryWorkPlan(context: Context, reportId: String, question: String,
    tasks: List<ReportViewModel.ReportTask>, overlay: AgentParameters?, config: ReportWorkerConfig,
    metadataDisabled: Boolean, knowledgeBaseIds: List<String>, settings: Settings, general: GeneralSettings, repository: AnalysisRepository
): ReportWorkPlan {
    val jobs = mutableListOf("${tasks.size} primary answers")
    val recipients = mutableListOf<ReportRecipient>()
    val instructions = mutableListOf<String>()
    val report = Report(reportId, System.currentTimeMillis(), title="Preview", prompt=question,
        agents=tasks.map { it.reportAgent }.toMutableList(), workerConfig=config)
    tasks.forEach { task ->
        val params = repository.effectiveReportParameters(task.resolvedParams, overlay,
            task.runtimeAgent.provider, task.runtimeAgent.model, context)
        val label = "${task.reportAgent.agentName} · ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model}"
        val endpoint = settings.getEffectiveEndpointUrlForAgent(task.runtimeAgent)
        task.reportAgent.executionConfig = ReportExecutionConfig(params,endpoint,repository.resolveReportPrompt(question,task.runtimeAgent))
        recipients += ReportRecipient(label, endpoint)
        instructions += "$label\nEffective role: ${params.systemPrompt?.takeIf { it.isNotBlank() } ?: "No system instruction"}\nParameters: ${params.copy(systemPrompt=null)}\nQuestion: ${repository.resolveReportPrompt(question, task.runtimeAgent)}"
    }
    fun add(prompt: InternalPrompt, label: String) {
        val frozen = prompt.freezeWorkers(settings, general)
        frozen.workers.forEach { worker ->
            recipients += ReportRecipient("$label · ${worker.provider}/${worker.model}", worker.frozenEndpointUrl.orEmpty())
            instructions += "$label · ${worker.provider}/${worker.model}\nTemplate: ${prompt.text}\nEffective role: ${worker.frozenParameters?.systemPrompt ?: "No system instruction"}\nParameters: ${worker.frozenParameters?.copy(systemPrompt=null)}"
        }
    }
    if (!metadataDisabled) {
        val names = mutableListOf<String>()
        if(general.reportTitleAiOn()) { jobs += "Optional: up to 2 report title jobs"; names += listOf("report-title-short","report-title-long") }
        if(general.reportIconOn()) { jobs += "Optional: report icon, with configured fallback attempts"; names += "report-icon" }
        if(general.reportLanguageOn()) { jobs += "Optional: language name and language icon (up to 2 jobs)"; names += listOf("report-language-name","report-language-icon") }
        names.forEach { name -> settings.internalPrompts.firstOrNull { it.category=="workers" && it.name==name }?.let { add(it.withReportInfoWorkers(report), name) } }
        if(general.perModelTitleOn() || general.perModelIconOn()) {
            jobs += "Optional: up to ${tasks.size} answer titles${if(general.perModelIconOn()) " and ${tasks.size} answer icons, plus icon fallback attempts" else ""}"
            listOf("model-titles","model-icons").filter { it!="model-icons" || general.perModelIconOn() }.forEach { name ->
                settings.internalPrompts.firstOrNull { it.category=="workers" && it.name==name }?.let { prompt ->
                    if(config.modelInfo==ModelInfoMode.OWN_MODEL) tasks.forEach { add(prompt.withOwnModelWorker(report,it.reportAgent.provider,it.reportAgent.model),name) }
                    else add(prompt,name)
                }
            }
        }
    }
    if(general.autostartItemsEnabled) {
        if(general.autoCreateRerankAndModeration) {
            jobs += "Optional after answers: question relevance (2+ successful answers) and moderation when a moderation model is configured"
            settings.internalPrompts.filter { it.name in setOf("second-rerank","second-moderation") }.forEach { add(it,it.name) }
            settings.getActiveServices().firstNotNullOfOrNull { provider -> settings.getModels(provider).firstOrNull { settings.getModelType(provider,it)==ModelType.MODERATION }?.let { provider to it } }?.let { (provider, model) ->
                recipients += ReportRecipient("Moderation · ${provider.id}/$model",provider.nativeModerationUrl ?: settings.getEffectiveEndpointUrlForAgent(com.ai.model.Agent(id="",name="",provider=provider,model=model,apiKey="")))
            }
        }
        settings.defaultMetaItems.filter { it.active }.forEach { item ->
            settings.internalPrompts.firstOrNull { it.category=="meta" && it.name.equals(item.metaName,true) }?.let {
                jobs += "Optional after answers: ${it.name}; separately reviewed before dispatch"
                add(it.withBatchWorkers(report.copy(workerConfig=config.copy(batches=config.metaBatches,batchWorkers=config.metaBatchWorkers))),it.name)
            }
        }
    }
    knowledgeBaseIds.firstOrNull()?.let { id ->
        val kb = KnowledgeStore.loadKnowledgeBase(context,id) ?: throw java.io.IOException("Attached knowledge base is unavailable")
        jobs += "Knowledge retrieval: query embedding with ${kb.embedderProviderId}/${kb.embedderModel}, then shared saved passages for every answer"
        AppService.findById(kb.embedderProviderId)?.let { recipients += ReportRecipient("Knowledge query · ${it.id}/${kb.embedderModel}",it.baseUrl) }
    }
    instructions.add(0,"Primary precedence: explicit run parameters override the selected participant's resolved role and settings. Group overrides apply only to participants selected through that group. Values below show the resulting configuration; provider defaults apply to unset values.")
    return ReportWorkPlan(jobs,recipients.distinct(),instructions,primaryLaunch=true)
}
