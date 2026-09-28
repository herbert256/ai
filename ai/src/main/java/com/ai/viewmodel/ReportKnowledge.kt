package com.ai.viewmodel

import android.content.Context
import com.ai.data.*
import com.ai.data.local.LocalLlm
import com.ai.model.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retrieve once before acquiring answer-provider permits. Persist the exact
 * context so replay and competing answers use identical evidence. */
internal object ReportKnowledge {
    private val locks = Array(64) { Mutex() }
    suspend fun prepare(context: Context, reportId: String, repository: AnalysisRepository, settings: Settings): Report? {
        val mutex=locks[(reportId.hashCode() and Int.MAX_VALUE) % locks.size]
        return mutex.withLock {
            val report=ReportStorage.getReport(context,reportId) ?: return@withLock null
            if (report.knowledgeContext != null || report.knowledgeBaseIds.isEmpty()) return@withLock report
            try {
                // One block serves every answer. A Local (on-device) answer's
                // 2048-token window holds prompt AND answer, and the default
                // 8000-char block alone overflows it — so a report with a
                // Local model gets the smaller budget for all its answers,
                // keeping the evidence identical across them.
                val maxChars = if (report.agents.any { it.provider == AppService.LOCAL.id }) LocalLlm.KNOWLEDGE_CONTEXT_CHARS
                    else KnowledgeService.DEFAULT_CONTEXT_CHARS
                val hits=KnowledgeService.retrieve(context,repository,settings,report.knowledgeBaseIds,report.prompt,
                    maxContextChars = maxChars)
                val text=KnowledgeService.formatContextBlock(hits)
                val saved = ReportStorage.saveKnowledgeContext(context,reportId,text,
                    if(hits.isEmpty()) "No relevant knowledge passages found" else "Saved ${hits.size} knowledge passages for this report",
                    report.prompt, report.knowledgeBaseIds)
                if (!saved) throw kotlinx.coroutines.CancellationException("Report inputs changed during knowledge retrieval; retry with the updated inputs")
                ReportStorage.getReport(context,reportId)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val saved = ReportStorage.saveKnowledgeContext(context, reportId, null, "Retrieval failed: ${e.message}",
                    report.prompt, report.knowledgeBaseIds, "Knowledge retrieval failed: ${e.message}")
                if (!saved) throw kotlinx.coroutines.CancellationException("Report inputs changed during knowledge retrieval; retry with the updated inputs")
                // Leave context null so an explicit retry can recover retrieval.
                throw java.io.IOException("Knowledge retrieval failed; no ungrounded answer was requested: ${e.message}",e)
            }
        }
    }
}
