package com.ai.data

import android.content.Context
import com.ai.model.InternalPrompt
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Immutable non-secret inputs, stored once per content hash, outside mutable
 * parent JSON. A secondary joins these by ID, never by current answer ordinal. */
data class ReportSourceAnswer(val id: String, val name: String, val provider: String, val model: String,
    val body: String, val title: String? = null)
data class ReportSourceSnapshot(val prompt: String, val title: String,
    val answers: List<ReportSourceAnswer>, val secondaryBodies: Map<String, String> = emptyMap())
data class ReportRunManifest(val prompt: InternalPrompt, val sourceSnapshotId: String,
    val parameters: AgentParameters? = null)
data class ReportExecutionConfig(val parameters: AgentParameters, val endpointUrl: String,
    val prompt: String, val capturedAt: Long = System.currentTimeMillis(), val credentialAgentId: String? = null)

object ReportEvidenceStore {
    @Volatile private var root: File? = null
    @Volatile private var appContext: Context? = null
    private val lock = Any()
    private val gson = createAppGson()
    fun init(context: Context) { appContext = context.applicationContext; root = File(context.filesDir, "report_evidence") }
    fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun file(reportId: String, id: String): File? {
        if (!reportId.matches(Regex("[A-Za-z0-9_-]+")) || !id.matches(Regex("[A-Za-z0-9_-]+"))) return null
        return root?.let { File(File(it, reportId), "$id.json") }
    }
    fun capture(report: Report, secondaryBodies: Map<String, String> = emptyMap()): String = synchronized(lock) {
        val snapshot = ReportSourceSnapshot(report.prompt, report.title, report.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
        }.map { ReportSourceAnswer(it.agentId,it.agentName,it.provider,it.model,it.responseBody!!,it.modelTitle) }, secondaryBodies)
        val text = gson.toJson(snapshot); val id = digest(text)
        val target = file(report.id,id) ?: throw IOException("Evidence storage is unavailable")
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            ReportSaveRecovery.write(target,text,report.id,
                retryLocked={ action -> synchronized(lock) { action() } }, onSaved={})
        }
        id
    }
    fun sources(reportId: String, id: String?): ReportSourceSnapshot? = id?.let { file(reportId,it) }
        ?.takeIf { it.exists() }?.let { target -> runCatching {
            val text = target.readText()
            require(id == digest(text)) { "Saved source integrity check failed" }
            gson.fromJson(text,ReportSourceSnapshot::class.java).also { snapshot ->
                require(snapshot.prompt != null && snapshot.answers != null)
                require(snapshot.answers.all { it != null && it.id != null && it.body != null })
            }
        }.getOrNull() }
    fun saveRun(context: Context, report: Report, runId: String, prompt: InternalPrompt,
                params: AgentParameters? = null, secondaryBodies: Map<String,String> = emptyMap()) {
        init(context)
        val sourceId = capture(report,secondaryBodies)
        val target = file(report.id,"run_$runId") ?: throw IOException("Invalid run identity")
        synchronized(lock) {
            if (target.exists()) return
            ReportSaveRecovery.write(target,gson.toJson(ReportRunManifest(prompt,sourceId,params)),report.id,
                retryLocked={ action -> synchronized(lock) { action() } }, onSaved={})
        }
    }
    fun run(reportId: String, runId: String?): ReportRunManifest? = runId?.let { file(reportId,"run_$it") }
        ?.takeIf { it.exists() }?.let { runCatching { gson.fromJson(it.readText(),ReportRunManifest::class.java) }.getOrNull() }
    fun sourceId(row: SecondaryResult): String? = row.sourceSnapshotId ?: run(row.reportId,
        row.tournamentJudgeRunId ?: row.compareRunId ?: row.translationRunId ?: row.runId)?.sourceSnapshotId
    fun sources(row: SecondaryResult): ReportSourceSnapshot? = sources(row.reportId,sourceId(row))
    fun historicalReport(report: Report, row: SecondaryResult): Report = historicalReport(report, sources(row))
    fun requireHistoricalReport(report: Report, row: SecondaryResult): Report = historicalReport(report,
        sources(row) ?: throw IOException("Saved source inputs are unavailable. Create a new analysis using the current answers; this result cannot be replayed faithfully."))
    fun historicalReport(report: Report, snapshot: ReportSourceSnapshot?): Report {
        // Display helpers must not attach current text to an unknown old evaluation.
        if (snapshot == null) return report.copy(prompt = "Saved source unavailable", agents = mutableListOf())
        return report.copy(prompt=snapshot.prompt,title=snapshot.title,agents=snapshot.answers.map { a ->
            ReportAgent(a.id,a.name,a.provider,a.model,ReportStatus.SUCCESS,responseBody=a.body,modelTitle=a.title)
        }.toMutableList())
    }
    /** Reconstruct content from the saved revision, never from a later translation/reference. */
    fun historicalSecondaries(context: Context, row: SecondaryResult): List<SecondaryResult> {
        val snapshot = sources(row) ?: throw IOException("Saved source inputs are unavailable")
        return snapshot.secondaryBodies.orEmpty().map { (id, body) ->
            val identity = SecondaryResultStorage.get(context,row.reportId,id)
                ?: throw IOException("Saved reference $id has no remaining identity metadata. Create a new analysis; this result was preserved.")
            identity.copy(content=body,errorMessage=null)
        }
    }
    fun isStale(report: Report, row: SecondaryResult): Boolean {
        val snapshot=sources(row) ?: return true // legacy provenance is unknown
        if (report.prompt != snapshot.prompt) return true
        if (snapshot.answers.any { old -> report.agents.firstOrNull { it.agentId==old.id }?.responseBody != old.body }) return true
        val context = appContext ?: return snapshot.secondaryBodies.orEmpty().isNotEmpty()
        return snapshot.secondaryBodies.orEmpty().any { (id, body) ->
            SecondaryResultStorage.get(context, report.id, id)?.content != body
        }
    }
    fun sourceDescription(report: Report, row: SecondaryResult): String {
        val snapshot = sources(row) ?: return "Source unavailable: original inputs were not recorded or cannot be read. Excluded from current comparisons; faithful replay is unavailable. Start a new analysis to use current inputs."
        val current = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        val unchanged = snapshot.answers.count { old -> current.any { it.agentId == old.id && it.responseBody == old.body } }
        val references = snapshot.secondaryBodies.orEmpty()
        val historical = isStale(report, row)
        return buildString {
            append("Saved input coverage: $unchanged of ${current.size} current answers; ${snapshot.answers.size} answer versions recorded. ")
            when(val scope=SecondaryScope.decodeOrAllReports(row.secondaryScope)) {
                is SecondaryScope.Manual -> append("Analysis scope: ${scope.agentIds.size} explicitly selected answers. ")
                is SecondaryScope.TopRanked -> append("Analysis scope: top ${scope.count} answers from the selected ranking. ")
                else -> Unit
            }
            append(if (historical) "Historical inputs or reference changed. Excluded from current comparisons. " else "Recorded sources are unchanged. ")
            if (unchanged < current.size) append("Other current answers were not covered; run a new analysis to include them. ")
            append("Reference revisions: ${references.size}. Saved source ${sourceId(row)?.take(12)}. Replay uses these saved inputs; a new analysis uses current inputs.")
        }
    }
    fun delete(reportId: String) { file(reportId,"sentinel")?.parentFile?.deleteRecursively() }
    fun files(reportId: String): List<File> = file(reportId,"sentinel")?.parentFile?.listFiles { f -> f.extension=="json" }?.toList().orEmpty()
    fun importFile(context: Context, reportId: String, id: String, text: String) {
        init(context); val target=file(reportId,id) ?: throw IOException("Invalid evidence identity")
        target.parentFile?.mkdirs()
        if (!target.writeTextAtomic(text)) throw IOException("Could not import report evidence")
    }
}
