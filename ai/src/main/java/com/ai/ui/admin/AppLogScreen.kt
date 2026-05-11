package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
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
import com.ai.data.AppLog
import com.ai.data.AppLogFileInfo
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.copyToClipboard
import com.ai.ui.shared.shareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ===== App Log list =====

@Composable
fun AppLogListScreen(
    onBack: () -> Unit,
    onSelectLog: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    // Re-fetched on every screen-resume (covers the back-pop from
    // the detail view after a delete).
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    var files by remember { mutableStateOf<List<AppLogFileInfo>>(emptyList()) }
    LaunchedEffect(refreshTick) {
        files = withContext(Dispatchers.IO) { AppLog.getLogFiles() }
    }

    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmTrim by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "applog_list",
            title = "Application log",
            onBackClick = onBack,
            onDelete = if (files.isNotEmpty()) { { confirmClearAll = true } } else null
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Daily-rotating log under filesDir/applog/. Tap a row to view, copy or share.",
            fontSize = 11.sp, color = AppColors.TextTertiary
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("(no log files yet)", color = AppColors.TextTertiary)
            }
        } else {
            // Table header
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Date", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
                    Text("Size", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = AppColors.TextTertiary, modifier = Modifier.weight(0.6f),
                        textAlign = TextAlign.End)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(files, key = { it.filename }) { info ->
                    AppLogListItem(info, onClick = { onSelectLog(info.filename) })
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { confirmTrim = true },
                    modifier = Modifier.weight(1f),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Delete > 7 days", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all log files?") },
            text = { Text("Permanently deletes ${files.size} log file(s). The current session's lines so far go too — the next log call starts a fresh file.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    val n = AppLog.clearLogs()
                    AppLog.i("Housekeeping", "Cleared $n log file(s)")
                    files = AppLog.getLogFiles()
                    Toast.makeText(context, "Deleted $n log file(s)", Toast.LENGTH_SHORT).show()
                }) { Text("Clear", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    if (confirmTrim) {
        AlertDialog(
            onDismissRequest = { confirmTrim = false },
            title = { Text("Delete logs older than 7 days?") },
            text = { Text("Removes log files whose last-modified time is more than 7 days old.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrim = false
                    val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                    val n = AppLog.deleteLogsOlderThan(cutoff)
                    AppLog.i("Housekeeping", "Deleted $n log file(s) older than 7 days")
                    files = AppLog.getLogFiles()
                    Toast.makeText(context, "Deleted $n log file(s)", Toast.LENGTH_SHORT).show()
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrim = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
}

@Composable
private fun AppLogListItem(info: AppLogFileInfo, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(info.date, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(info.sizeBytes), fontSize = 12.sp, color = AppColors.TextTertiary,
                modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "${b / (1024 * 1024)} MB"
}

// ===== App Log detail =====

/** One log entry = a header line (with the ISO timestamp) plus any
 *  indented continuation lines that follow it (stack trace dump from
 *  AppLog.appendLine). Grouped so the viewer can show one entry per
 *  row without scattering the stack trace lines across the list. */
private data class LogEntry(val lines: List<String>) {
    val header: String get() = lines.firstOrNull().orEmpty()
    val text: String get() = lines.joinToString("\n")
}

/** Regex that detects an entry-starting line — same shape AppLog
 *  writes: `YYYY-MM-DD HH:MM:SS.SSS LEVEL TAG: …`. Anything else is
 *  treated as a continuation (typically stack trace lines that
 *  AppLog indents with 4 spaces). */
private val LOG_HEADER_RE = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} """)

private fun parseLogEntries(content: String): List<LogEntry> {
    if (content.isBlank()) return emptyList()
    val out = mutableListOf<LogEntry>()
    var buf = mutableListOf<String>()
    for (line in content.lines()) {
        if (line.isEmpty() && buf.isEmpty()) continue
        if (LOG_HEADER_RE.containsMatchIn(line)) {
            if (buf.isNotEmpty()) out.add(LogEntry(buf.toList()))
            buf = mutableListOf(line)
        } else {
            // Stack trace continuation or a one-off raw line written
            // before AppLog existed. Either way it belongs to the
            // previous entry if there is one; otherwise it's a
            // standalone entry.
            if (buf.isEmpty()) buf.add(line) else buf.add(line)
        }
    }
    if (buf.isNotEmpty()) out.add(LogEntry(buf.toList()))
    return out
}

/** Pick a render colour per entry based on its level token. */
private fun colorForEntry(header: String): Color = when {
    " ERROR " in header -> AppColors.Red
    " WARN " in header -> AppColors.Orange
    " INFO " in header -> AppColors.Green
    " DEBUG " in header -> AppColors.Blue
    " TRACE " in header -> AppColors.TextTertiary
    else -> Color(0xFFCCCCCC)
}

@Composable
fun AppLogDetailScreen(
    filename: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var currentFilename by remember { mutableStateOf(filename) }
    var content by remember { mutableStateOf("") }

    // Sibling file list for prev/next nav. Refreshed on every resume
    // so a delete in this screen + back-pop lands the list view on a
    // consistent set.
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(refreshTick) {
        files = withContext(Dispatchers.IO) { AppLog.getLogFiles().map { it.filename } }
    }
    val currentIndex = files.indexOf(currentFilename)
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex in 0 until files.size - 1

    LaunchedEffect(currentFilename) {
        content = withContext(Dispatchers.IO) { AppLog.readLogFile(currentFilename) ?: "" }
    }

    // Reverse-chronological so the most recent entry is at the top.
    val entries = remember(content) { parseLogEntries(content).asReversed() }

    // In-screen overlay: tap an entry → full-screen view of just that
    // entry, with prev/next buttons that walk the same reversed list.
    // Uses the existing "overlay + return" idiom so the parent
    // screen's scroll position survives.
    var selectedEntryIndex by remember(content) { mutableStateOf<Int?>(null) }
    val selIdx = selectedEntryIndex
    if (selIdx != null && selIdx in entries.indices) {
        AppLogEntryScreen(
            entries = entries,
            startIndex = selIdx,
            filename = currentFilename,
            onIndexChange = { selectedEntryIndex = it },
            onBack = { selectedEntryIndex = null }
        )
        return
    }

    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this log file?") },
            text = { Text("Permanently removes $currentFilename from disk.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        if (AppLog.deleteLog(currentFilename)) {
                            Toast.makeText(context, "Log deleted", Toast.LENGTH_SHORT).show()
                            onBack()
                        } else {
                            Toast.makeText(context, "Could not delete log", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Delete", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "applog_detail",
            title = "Log file",
            subject = currentFilename,
            onBackClick = onBack,
            onDelete = { confirmDelete = true },
            onCopy = { copyToClipboard(context, content, "log") },
            onShare = { shareText(context, content, currentFilename) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Reversed entry list (most recent first). Tap a row → full
        // screen view of that one entry with prev/next within this
        // file's entry list. Stack-trace continuation lines stay
        // glued to their header by parseLogEntries.
        Box(modifier = Modifier.weight(1f).background(AppColors.CardBackground).padding(8.dp)) {
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("(empty)", color = AppColors.TextTertiary)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(entries.size) { i ->
                        val entry = entries[i]
                        val color = colorForEntry(entry.header)
                        val suffix = if (entry.lines.size > 1) "  (+${entry.lines.size - 1})" else ""
                        Text(
                            entry.header + suffix,
                            fontSize = 11.sp, color = color,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedEntryIndex = i }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { if (hasPrev) currentFilename = files[currentIndex - 1] },
                enabled = hasPrev, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(36.dp),
                colors = AppColors.outlinedButtonColors()
            ) { Text("<", fontSize = 14.sp, maxLines = 1, softWrap = false) }
            Text(
                if (currentIndex >= 0) "${currentIndex + 1} / ${files.size}" else "",
                fontSize = 12.sp, color = AppColors.TextTertiary,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = { if (hasNext) currentFilename = files[currentIndex + 1] },
                enabled = hasNext, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(36.dp),
                colors = AppColors.outlinedButtonColors()
            ) { Text(">", fontSize = 14.sp, maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun AppLogEntryScreen(
    entries: List<LogEntry>,
    startIndex: Int,
    filename: String,
    onIndexChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val total = entries.size
    val hasPrev = startIndex > 0
    val hasNext = startIndex < total - 1
    val entry = entries[startIndex]
    val color = colorForEntry(entry.header)
    val text = entry.text

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "applog_detail",
            title = "Log entry",
            subject = filename,
            onBackClick = onBack,
            onCopy = { copyToClipboard(context, text, "log entry") },
            onShare = { shareText(context, text, "Log entry from $filename") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f).background(AppColors.CardBackground).padding(8.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(entry.lines.size) { i ->
                    val line = entry.lines[i]
                    Text(
                        line,
                        fontSize = 12.sp,
                        color = if (i == 0) color else AppColors.TextTertiary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Prev/Next walks the same reverse-chronological list the
        // parent screen displayed: < moves toward the most recent
        // entry, > moves toward older ones. The middle text shows
        // "n / total" so the user knows where they are.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { if (hasPrev) onIndexChange(startIndex - 1) },
                enabled = hasPrev, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(36.dp),
                colors = AppColors.outlinedButtonColors()
            ) { Text("<", fontSize = 14.sp, maxLines = 1, softWrap = false) }
            Text(
                "${startIndex + 1} / $total",
                fontSize = 12.sp, color = AppColors.TextTertiary,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = { if (hasNext) onIndexChange(startIndex + 1) },
                enabled = hasNext, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(36.dp),
                colors = AppColors.outlinedButtonColors()
            ) { Text(">", fontSize = 14.sp, maxLines = 1, softWrap = false) }
        }
    }
}
