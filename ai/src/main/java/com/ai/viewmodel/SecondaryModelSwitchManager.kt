package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.ApiCallCaps
import com.ai.data.AppService
import com.ai.data.ModelType
import com.ai.data.PricingCache
import com.ai.data.ProviderThrottle
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.TokenUsage
import com.ai.data.callModerationApi
import com.ai.data.callRerankApi
import com.ai.data.withTraceFilenameSink
import com.ai.data.withTracerTags
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** A configured agent or a raw provider+model the user picked to re-run a
 *  secondary result against. [paramsIds] / [systemPromptId] are the agent's
 *  presets (the row's own for a raw model). [label] is the picker display. */
data class ModelSwitchSelection(
    val provider: AppService,
    val model: String,
    val paramsIds: List<String>,
    val systemPromptId: String?,
    val label: String
)

sealed class ModelSwitchResult {
    data object Running : ModelSwitchResult()
    data class Success(
        val content: String,
        val tokenUsage: TokenUsage?,
        val inputCost: Double?,
        val outputCost: Double?,
        val durationMs: Long,
        val traceFile: String?
    ) : ModelSwitchResult()
    data class Error(
        val message: String,
        val httpStatusCode: Int?,
        val durationMs: Long?,
        val traceFile: String?
    ) : ModelSwitchResult()
}

data class ModelSwitchState(
    val reportId: String,
    val resultId: String,
    val selection: ModelSwitchSelection,
    val result: ModelSwitchResult,
    val isRunning: Boolean
) {
    companion object {
        fun key(reportId: String, resultId: String): String = "$reportId|$resultId"
    }
}

/**
 * "Switch model / agent" on a secondary result's Change-result screen. Re-runs
 * the result (Meta / Fan-in / Rerank / Moderation) against a user-picked agent
 * or provider+model, holds the candidate IN MEMORY (preview), and only writes
 * it onto the row when the user applies it.
 *
 * Meta / Fan-in / rerank-chat reuse [MetaEditManager.runModelSwitchMeta] (the
 * existing chat replay, with the row's scope/language but the new model);
 * native rerank + moderation call their dedicated APIs here. Apply preserves
 * the old run's spend (it was billed) via [ReportStorage.bumpCostsFromDeletedItems]
 * before overwriting the row — same accounting as the stale-resume path.
 */
class SecondaryModelSwitchManager internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) {
    private val _states = MutableStateFlow<Map<String, ModelSwitchState>>(emptyMap())
    val states: StateFlow<Map<String, ModelSwitchState>> = _states.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun clear(reportId: String, resultId: String) {
        val key = ModelSwitchState.key(reportId, resultId)
        jobs.remove(key)?.cancel()
        _states.update { it - key }
    }

    fun cancelAllForReport(reportId: String) {
        val prefix = "$reportId|"
        jobs.keys.filter { it.startsWith(prefix) }.forEach { jobs.remove(it)?.cancel() }
        _states.update { it.filterKeys { k -> !k.startsWith(prefix) } }
    }

    fun startModelSwitch(context: Context, reportId: String, resultId: String, selection: ModelSwitchSelection): Job {
        val key = ModelSwitchState.key(reportId, resultId)
        jobs.remove(key)?.cancel()
        _states.update { it + (key to ModelSwitchState(reportId, resultId, selection, ModelSwitchResult.Running, isRunning = true)) }
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                runCandidate(context, reportId, resultId, selection)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelSwitchResult.Error((e.message ?: "Model switch failed").take(2000), null, null, null)
            }
            _states.update { m -> m[key]?.let { m + (key to it.copy(result = result, isRunning = false)) } ?: m }
        }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
        return job
    }

    private suspend fun runCandidate(context: Context, reportId: String, resultId: String, selection: ModelSwitchSelection): ModelSwitchResult {
        val row = SecondaryResultStorage.get(context, reportId, resultId)
            ?: return ModelSwitchResult.Error("This result no longer exists", null, null, null)
        val aiSettings = appViewModel.uiState.value.aiSettings
        val provider = selection.provider
        val model = selection.model
        val nativeRerank = row.kind == SecondaryKind.RERANK && aiSettings.getModelType(provider, model) == ModelType.RERANK
        return when {
            row.kind == SecondaryKind.MODERATION -> runNativeCandidate(context, reportId, row, provider, model, SecondaryKind.MODERATION)
            nativeRerank -> runNativeCandidate(context, reportId, row, provider, model, SecondaryKind.RERANK)
            else -> {
                // Meta / Fan-in / rerank-chat: reuse the existing chat replay.
                val mr = reportViewModel.metaEditManager.runModelSwitchMeta(
                    context, reportId, resultId, provider, model, selection.paramsIds, selection.systemPromptId
                )
                val r = mr.response
                if (r.isSuccess && !r.analysis.isNullOrBlank()) {
                    val pricing = PricingCache.getPricing(context, provider, model)
                    val (inCost, outCost) = r.tokenUsage?.let { PricingCache.computeInOutCost(it, pricing) } ?: (null to null)
                    ModelSwitchResult.Success(r.analysis, r.tokenUsage, inCost, outCost, mr.durationMs, mr.traceFile)
                } else {
                    ModelSwitchResult.Error(r.error ?: "No response body", r.httpStatusCode, mr.durationMs, mr.traceFile)
                }
            }
        }
    }

    /** Native rerank / moderation candidate — call the dedicated API in memory,
     *  under the same concurrency caps + rate gate as a normal secondary run. */
    private suspend fun runNativeCandidate(
        context: Context, reportId: String, row: SecondaryResult,
        provider: AppService, model: String, kind: SecondaryKind
    ): ModelSwitchResult = withTracerTags(reportId = reportId, category = "meta/model-switch") {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val apiKey = aiSettings.getApiKey(provider)
        val report = ReportStorage.getReport(context, reportId)
            ?: return@withTracerTags ModelSwitchResult.Error("Report not found", null, null, null)
        val traceSink = AtomicReference<String?>(null)
        ApiCallCaps.fanOut.withPermit {
            ApiCallCaps.global.withPermit {
                val releaser = acquireOrRequeue(providerHost(provider))
                try {
                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                        withTraceFilenameSink(traceSink) {
                            if (kind == SecondaryKind.MODERATION) moderationCandidate(context, reportId, row, report, provider, apiKey, model, traceSink)
                            else rerankCandidate(context, reportId, row, report, provider, apiKey, model, traceSink)
                        }
                    }
                } finally {
                    releaser.release()
                }
            }
        }
    }

    private suspend fun moderationCandidate(
        context: Context, reportId: String, row: SecondaryResult, report: com.ai.data.Report,
        provider: AppService, apiKey: String, model: String, traceSink: AtomicReference<String?>
    ): ModelSwitchResult {
        val translatedBodies = row.targetLanguage?.let { lang ->
            lookupLanguageTranslations(report, SecondaryResultStorage.listForReport(context, reportId), lang)?.bodiesByAgentId
        }
        val responses = report.agents
            .mapNotNull {
                if (it.reportStatus != ReportStatus.SUCCESS) return@mapNotNull null
                translatedBodies?.get(it.agentId) ?: it.responseBody?.takeIf(String::isNotBlank)
            }
        val (_, r) = callModerationApi(provider, apiKey, model, responses)
        if (r.errorMessage != null || r.content.isNullOrBlank()) {
            return ModelSwitchResult.Error(r.errorMessage ?: "No response body", r.httpStatusCode, r.durationMs, traceSink.get())
        }
        val pricing = PricingCache.getPricing(context, provider, model)
        val (inCost, outCost) = r.tokenUsage?.let { PricingCache.computeInOutCost(it, pricing) } ?: (null to null)
        r.tokenUsage?.let { appViewModel.settingsPrefs.updateUsageStatsAsync(provider, model, it, kind = "moderation", durationMs = r.durationMs) }
        return ModelSwitchResult.Success(r.content, r.tokenUsage, inCost, outCost, r.durationMs, traceSink.get())
    }

    private suspend fun rerankCandidate(
        context: Context, reportId: String, row: SecondaryResult, report: com.ai.data.Report,
        provider: AppService, apiKey: String, model: String, traceSink: AtomicReference<String?>
    ): ModelSwitchResult {
        val langCtx = row.targetLanguage?.let { lang ->
            lookupLanguageTranslations(report, SecondaryResultStorage.listForReport(context, reportId), lang)
        }
        val query = langCtx?.prompt?.takeIf { it.isNotBlank() } ?: report.prompt
        val docs = report.agents
            .mapNotNull {
                if (it.reportStatus != ReportStatus.SUCCESS) return@mapNotNull null
                langCtx?.bodiesByAgentId?.get(it.agentId) ?: it.responseBody?.takeIf(String::isNotBlank)
            }
        val r = callRerankApi(provider, apiKey, model, query, docs)
        if (r.errorMessage != null || r.content.isNullOrBlank()) {
            return ModelSwitchResult.Error(r.errorMessage ?: "No response body", r.httpStatusCode, r.durationMs, traceSink.get())
        }
        val pricing = PricingCache.getPricing(context, provider, model)
        val units = r.billedSearchUnits ?: 1
        val perQueryCost = if (units > 0) units * pricing.perQueryPrice else 0.0
        val estInputTokens = if (perQueryCost <= 0.0 && pricing.promptPrice > 0.0)
            AppViewModel.estimateTokens(query) + docs.sumOf { AppViewModel.estimateTokens(it) } else 0
        val rerankCost = when {
            perQueryCost > 0.0 -> perQueryCost
            estInputTokens > 0 -> estInputTokens * pricing.promptPrice
            else -> null
        }
        val tu = if (estInputTokens > 0) TokenUsage(inputTokens = estInputTokens, outputTokens = 0) else null
        if (estInputTokens > 0) appViewModel.settingsPrefs.updateUsageStatsAsync(provider, model, estInputTokens, 0, estInputTokens, kind = "rerank", searchUnits = units, durationMs = r.durationMs)
        return ModelSwitchResult.Success(r.content, tu, rerankCost, null, r.durationMs, traceSink.get())
    }

    /** "Reload" — re-run this secondary result in place with its saved model,
     *  via the shared resume path (works for Meta / Fan-in / Rerank / Moderation). */
    fun reloadSecondary(context: Context, reportId: String, resultId: String): Job? {
        val row = SecondaryResultStorage.get(context, reportId, resultId) ?: return null
        return reportViewModel.secondary.resumeStaleMetaPlaceholder(context, reportId, row)
    }

    /** Commit the previewed candidate onto the row, re-pointing it at the new
     *  model. Preserves the replaced run's spend; clears the preview state. */
    fun applyModelSwitch(context: Context, reportId: String, resultId: String): Job? {
        val key = ModelSwitchState.key(reportId, resultId)
        val st = _states.value[key] ?: return null
        val success = st.result as? ModelSwitchResult.Success ?: return null
        val selection = st.selection
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val row = SecondaryResultStorage.get(context, reportId, resultId) ?: return@launch
            val oldCost = (row.inputCost ?: 0.0) + (row.outputCost ?: 0.0)
            if (oldCost > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, oldCost)
            val langSuffix = row.targetLanguage?.let { " [$it]" } ?: ""
            val agentName = "${selection.provider.id} / ${shortModelName(selection.model)}$langSuffix"
            SecondaryResultStorage.updateModelSelection(
                context, reportId, resultId,
                providerId = selection.provider.id, model = selection.model, agentName = agentName,
                content = success.content, tokenUsage = success.tokenUsage,
                inputCost = success.inputCost, outputCost = success.outputCost,
                durationMs = success.durationMs, traceFile = success.traceFile,
                parameterPresetIds = selection.paramsIds.takeIf { it.isNotEmpty() }, systemPromptId = selection.systemPromptId,
                changeValue = "${selection.provider.id} / ${selection.model}"
            )
            ReportStorage.bumpReportTimestamp(context, reportId)
            _states.update { it - key }
        }
    }
}
