package com.ai.ui.shared

import androidx.compose.material3.Text
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
    val notice by produceState("",row.id,row.sourceSnapshotId,version) {
        value = withContext(Dispatchers.IO) {
            val snapshot = ReportEvidenceStore.sources(row)
            val report = ReportStorage.getReport(context,row.reportId)
            when {
                snapshot == null -> "Historical result: the original source revision was not recorded. It is excluded from current answer comparisons."
                report != null && ReportEvidenceStore.isStale(report,row) -> "Historical result: report inputs changed after this analysis. Saved source text is retained; this score is excluded from current answer comparisons."
                else -> "Original source revision recorded. Replays use the saved inputs and run settings."
            }
        }
    }
    Text(notice, color=AppColors.TextTertiary,fontSize=11.sp)
}
