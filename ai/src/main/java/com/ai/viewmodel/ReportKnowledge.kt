package com.ai.viewmodel

import android.content.Context
import com.ai.data.*
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
                val hits=KnowledgeService.retrieve(context,repository,settings,report.knowledgeBaseIds,report.prompt)
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
