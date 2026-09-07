package com.ai.ui.shared

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.ai.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReportSourceNotice(row: SecondaryResult) {
    val context = LocalContext.current
    val version by ReportDataVersion.versionFor(row.reportId).collectAsState()
    val secondaryVersion by SecondaryDataVersion.versionFor(row.reportId).collectAsState()
    val notice by produceState("",row.id,row.sourceSnapshotId,version,secondaryVersion) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context,row.reportId)
            report?.let { ReportEvidenceStore.sourceDescription(it, row) } ?: "Report unavailable"
        }
    }
    var showRubric by remember(row.id) { mutableStateOf(false) }
    val rubric by produceState("",row.id,row.sourceSnapshotId,secondaryVersion) {
        value=withContext(Dispatchers.IO) {
            ReportEvidenceStore.run(row.reportId,row.tournamentJudgeRunId ?: row.compareRunId ?: row.runId)?.prompt?.let { "${it.title.ifBlank { it.name }}\nConfigured evaluator pool: ${it.workers.joinToString { worker -> "${worker.provider}/${worker.model}" }}\n${it.text}" }
                ?: row.executionConfig?.prompt ?: "No execution prompt was recorded for this result."
        }
    }
    Column {
        Text(evaluationMeaning(row.kind),color=AppColors.TextSecondary,fontSize=12.sp)
        Text("Evaluator / producer: ${row.agentName} · ${row.providerId}/${row.model}",color=AppColors.TextSecondary,fontSize=11.sp)
        Text(notice, color=AppColors.TextTertiary,fontSize=11.sp)
        TextButton(onClick={showRubric=!showRubric}) { Text(if(showRubric) "Hide saved rubric / inputs" else "Show saved rubric / inputs",fontSize=12.sp) }
        if(showRubric) Text(rubric,color=AppColors.TextSecondary,fontSize=12.sp)
    }
}

@Composable
fun ReportRunEvidenceNotice(reportId:String,kind:SecondaryKind,runId:String?) {
    val context=LocalContext.current
    val version by SecondaryDataVersion.versionFor(reportId).collectAsState()
    val row by produceState<SecondaryResult?>(null,reportId,kind,runId,version) {
        value=withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context,reportId,kind).filter {
                runId==null || it.tournamentJudgeRunId==runId || it.compareRunId==runId || it.runId==runId
            }.firstOrNull { it.tournamentRole=="AGGREGATE" } ?: SecondaryResultStorage.listForReport(context,reportId,kind).firstOrNull {
                runId==null || it.tournamentJudgeRunId==runId || it.compareRunId==runId || it.runId==runId
            }
        }
    }
    row?.let { ReportSourceNotice(it) } ?: Text(evaluationMeaning(kind),color=AppColors.TextSecondary,fontSize=12.sp)
}
