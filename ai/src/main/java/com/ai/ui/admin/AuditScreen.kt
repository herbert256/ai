package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
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

/** Alternating row background: even rows one shade, odd rows another. */
private fun rowBackground(index: Int): Color =
    if (index % 2 == 0) AppColors.CardBackground else AppColors.CardBackgroundAlt

// ===== Audit list =====

/** One report's row on the Audit list: its file metadata plus the
 *  resolved live title (or null when the report has been deleted —
 *  the audit file is kept, so the row still shows with "(deleted)"). */
private data class AuditRow(val info: AuditFileInfo, val title: String?)

@Composable
fun AuditListScreen(
    onBack: () -> Unit,
    onNavigateToMonitor: () -> Unit,
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
            // 🧾 section glyph top-left; tapping it (or the title) jumps to Monitor.
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.audit,
            onReportIconClick = onNavigateToMonitor,
            onTitleClick = onNavigateToMonitor,
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
                itemsIndexed(rows, key = { _, r -> r.info.reportId }) { index, row ->
                    AuditListItem(row, index, onClick = { onSelect(row.info.reportId) })
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
                }) { Text("Clear", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

@Composable
private fun AuditListItem(row: AuditRow, index: Int, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = rowBackground(index)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.title ?: "(deleted report)",
                    fontSize = 13.sp,
                    color = if (row.title != null) Color.White else AppColors.TextTertiary,
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
    onBack: () -> Unit,
    onNavigateToMonitor: () -> Unit,
    onNavigateToTrace: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var title by remember { mutableStateOf<String?>(null) }
    // Traces for this report, used to resolve an API-call line → its trace.
    var traces by remember { mutableStateOf<List<com.ai.data.TraceFileInfo>>(emptyList()) }
    LaunchedEffect(reportId) {
        lines = withContext(Dispatchers.IO) { AuditLog.lines(reportId) }
        title = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId)?.title }
        traces = withContext(Dispatchers.IO) { ApiTracer.getTraceFilesForReport(reportId) }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    // Resolve the trace whose timestamp is nearest this line's timestamp
    // (the technical line is written at dispatch completion ≈ trace time;
    // its paired functional line lands a few ms later). Restricted to this
    // report's traces so concurrent reports can't cross-match.
    fun traceFor(line: String): String? {
        val ts = parseAuditMillis(line) ?: return null
        return traces.asSequence()
            .filter { kotlin.math.abs(it.timestamp - ts) <= 30_000L }
            .minByOrNull { kotlin.math.abs(it.timestamp - ts) }
            ?.filename
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "audit_detail",
            title = "Audit",
            subject = title ?: reportId,
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.audit,
            onReportIconClick = onNavigateToMonitor,
            onTitleClick = onNavigateToMonitor,
            onDelete = { confirmDelete = true },
            onCopy = { copyToClipboard(context, lines.joinToString("\n"), "audit") },
            onShare = { shareText(context, lines.joinToString("\n"), "Audit $reportId") }
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("(empty)", color = AppColors.TextTertiary)
                }
            } else {
                LazyColumn {
                    itemsIndexed(lines) { i, line ->
                        val apiLine = isApiLine(bodyOf(line))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBackground(i))
                                .then(
                                    if (apiLine) Modifier.clickable {
                                        val tf = traceFor(line)
                                        if (tf != null) onNavigateToTrace(tf)
                                        else Toast.makeText(
                                            context,
                                            "No trace for this call (enable tracing in Settings)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else Modifier
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                line,
                                fontSize = 11.sp,
                                color = colorForAuditLine(line),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "${lines.size} line${if (lines.size == 1) "" else "s"} · tap an API-call line for its trace",
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
                OutlinedButton(
                    onClick = {
                        confirmDelete = false
                        if (AuditLog.deleteAudit(reportId)) {
                            Toast.makeText(context, "Audit deleted", Toast.LENGTH_SHORT).show()
                            onBack()
                        } else {
                            Toast.makeText(context, "Could not delete audit", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Delete", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

/** The message body of an audit line, i.e. everything after the
 *  `yyyy-MM-dd HH:mm:ss.SSS ` (24-char) timestamp prefix. */
private fun bodyOf(line: String): String = if (line.length > 24) line.substring(24) else line

/** Parse the `yyyy-MM-dd HH:mm:ss.SSS` prefix of an audit line to epoch
 *  millis (device timezone, matching how [AuditLog] formats it). */
private fun parseAuditMillis(line: String): Long? {
    if (line.length < 23) return null
    return try {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .parse(line.substring(0, 23))?.time
    } catch (_: Exception) { null }
}

/** True when a line records an API call — either the technical line
 *  (`API …`) or a functional line that pairs with one (response, title,
 *  language, icon, secondary result). Drives the tap-to-trace target. */
private fun isApiLine(body: String): Boolean =
    body.startsWith("API ") ||
        body.startsWith("Response received") ||
        body.startsWith("Title '") ||
        body.startsWith("Language '") ||
        body.startsWith("Icon '") ||
        body.startsWith("AI title '") ||
        body.contains("result produced by")

/** Tint audit lines so structure scans at a glance: API technical lines
 *  are dim, errors red, batch start/end and the report start line stand
 *  out. Everything else is the default near-white. */
private fun colorForAuditLine(line: String): Color {
    val body = bodyOf(line)
    return when {
        body.startsWith("API ") && " · ERROR " in body -> AppColors.DangerAccent
        body.startsWith("API ") -> AppColors.TextTertiary
        body.startsWith("Start ") || body.startsWith("End ") -> AppColors.InfoAccent
        body.startsWith("Deleted ") || body == "Report deleted" -> AppColors.WarningAccent
        else -> Color(0xFFCCCCCC)
    }
}
