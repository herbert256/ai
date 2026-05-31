package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AuditFileInfo
import com.ai.data.AuditLog
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.copyToClipboard
import com.ai.ui.shared.shareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun fmtTime(millis: Long): String = try {
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(millis))
} catch (_: Exception) { "" }

// ===== Audit list =====

/** One report's row on the Audit list: its file metadata plus the
 *  resolved live title (or null when the report has been deleted —
 *  the audit file is kept, so the row still shows with "(deleted)"). */
private data class AuditRow(val info: AuditFileInfo, val title: String?)

@Composable
fun AuditListScreen(
    onBack: () -> Unit,
    onSelect: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    var rows by remember { mutableStateOf<List<AuditRow>>(emptyList()) }
    LaunchedEffect(refreshTick) {
        rows = withContext(Dispatchers.IO) {
            AuditLog.auditReports().map { info ->
                AuditRow(info, ReportStorage.getReport(context, info.reportId)?.title)
            }
        }
    }
    var confirmClearAll by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "audit_list",
            title = "Audit", subject = "Per-report audit trail",
            onBackClick = onBack,
            onDelete = if (rows.isNotEmpty()) { { confirmClearAll = true } } else null
        )

        Text(
            "Every report's actions, batches and API calls. Tap a report to read its audit lines.",
            fontSize = 11.sp, color = AppColors.TextTertiary
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("(no audited reports yet)", color = AppColors.TextTertiary)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(rows, key = { it.info.reportId }) { row ->
                    AuditListItem(row, onClick = { onSelect(row.info.reportId) })
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all audit files?") },
            text = { Text("Permanently deletes the audit trail of ${rows.size} report(s).") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    val n = AuditLog.clearAll()
                    rows = AuditLog.auditReports().map { AuditRow(it, ReportStorage.getReport(context, it.reportId)?.title) }
                    Toast.makeText(context, "Deleted $n audit file(s)", Toast.LENGTH_SHORT).show()
                }) { Text("Clear", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

@Composable
private fun AuditListItem(row: AuditRow, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.title ?: "(deleted report)",
                    fontSize = 13.sp,
                    color = if (row.title != null) Color.White else AppColors.TextTertiary,
                    fontWeight = if (row.title != null) FontWeight.Normal else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${row.info.reportId.take(8)} · ${fmtTime(row.info.lastModified)}",
                    fontSize = 10.sp, color = AppColors.TextTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text("${row.info.lineCount} line${if (row.info.lineCount == 1) "" else "s"}",
                fontSize = 11.sp, color = AppColors.TextTertiary)
        }
    }
}

// ===== Audit detail =====

@Composable
fun AuditDetailScreen(
    reportId: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var title by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reportId) {
        lines = withContext(Dispatchers.IO) { AuditLog.lines(reportId) }
        title = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId)?.title }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "audit_detail",
            title = "Audit",
            subject = title ?: reportId,
            onBackClick = onBack,
            onDelete = { confirmDelete = true },
            onCopy = { copyToClipboard(context, lines.joinToString("\n"), "audit") },
            onShare = { shareText(context, lines.joinToString("\n"), "Audit $reportId") }
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(AppColors.CardBackground).padding(8.dp)) {
            if (lines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("(empty)", color = AppColors.TextTertiary)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(lines.size) { i ->
                        Text(
                            lines[i],
                            fontSize = 11.sp,
                            color = colorForAuditLine(lines[i]),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "${lines.size} line${if (lines.size == 1) "" else "s"}",
            fontSize = 11.sp, color = AppColors.TextTertiary,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this audit file?") },
            text = { Text("Permanently removes the audit trail for this report.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        if (AuditLog.deleteAudit(reportId)) {
                            Toast.makeText(context, "Audit deleted", Toast.LENGTH_SHORT).show()
                            onBack()
                        } else {
                            Toast.makeText(context, "Could not delete audit", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Delete", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

/** Tint audit lines so structure scans at a glance: API technical lines
 *  are dim, errors red, batch start/end and the report start line stand
 *  out. Everything else is the default near-white. */
private fun colorForAuditLine(line: String): Color {
    // Drop the leading "yyyy-MM-dd HH:mm:ss.SSS " timestamp before matching.
    val body = if (line.length > 24) line.substring(24) else line
    return when {
        body.startsWith("API ") && " · ERROR " in body -> AppColors.Red
        body.startsWith("API ") -> AppColors.TextTertiary
        body.startsWith("Start ") || body.startsWith("End ") ||
            body.startsWith("Start AI report") -> AppColors.Blue
        body.startsWith("Deleted ") || body == "Report deleted" -> AppColors.Orange
        else -> Color(0xFFCCCCCC)
    }
}
