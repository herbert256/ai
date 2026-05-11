package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppLog
import com.ai.data.AppLogFileInfo
import com.ai.data.LogLevel
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

/** Resolve the [LogLevel] of a header line, or null when the line
 *  predates AppLog or has a corrupt header. Used by the level-chip
 *  filter. */
private fun levelOfHeader(header: String): LogLevel? = when {
    " ERROR " in header -> LogLevel.ERROR
    " WARN " in header -> LogLevel.WARN
    " INFO " in header -> LogLevel.INFO
    " DEBUG " in header -> LogLevel.DEBUG
    " TRACE " in header -> LogLevel.TRACE
    else -> null
}

/** Parse the time-of-day portion (HH:mm:ss) of an entry header.
 *  Used to compare against the user-supplied start / end filter. */
private fun timeOfHeader(header: String): String? {
    // Header shape: "YYYY-MM-DD HH:MM:SS.SSS LEVEL …" — the time
    // sits at fixed positions 11..18.
    if (header.length < 19) return null
    val candidate = header.substring(11, 19)
    if (candidate[2] != ':' || candidate[5] != ':') return null
    return candidate
}

/** Strip the leading "YYYY-MM-DD " from a log line. Each log file is
 *  one day, so the date prefix is pure noise on screen — the file
 *  name carries the date. Falls through unchanged when the line
 *  doesn't start with a date (legacy / hand-written entries). */
private fun stripDatePrefix(line: String): String {
    if (line.length < 11) return line
    val datePart = line.substring(0, 10)
    if (datePart[4] != '-' || datePart[7] != '-') return line
    return line.substring(11)
}

/** Normalise a user-typed time filter to "HH:mm:ss". Accepts blank
 *  (= no constraint), "HH:mm" (zero-fills seconds), and "HH:mm:ss".
 *  Returns null when the input doesn't parse — caller treats null
 *  as "no constraint" so a partial mid-keystroke edit doesn't blank
 *  the list. */
private fun normaliseTimeFilter(raw: String): String? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    val re5 = Regex("""^(\d{1,2}):(\d{2})$""")
    val re8 = Regex("""^(\d{1,2}):(\d{2}):(\d{2})$""")
    val (h, m, sec) = when {
        re8.matches(s) -> {
            val parts = s.split(":")
            Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
        re5.matches(s) -> {
            val parts = s.split(":")
            Triple(parts[0].toInt(), parts[1].toInt(), 0)
        }
        else -> return null
    }
    if (h !in 0..23 || m !in 0..59 || sec !in 0..59) return null
    return "%02d:%02d:%02d".format(h, m, sec)
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
    val allEntries = remember(content) { parseLogEntries(content).asReversed() }

    // ===== Filter state =====
    // Search: free-text substring match across the entire entry
    // (header + continuation lines). Case-insensitive.
    var searchQuery by remember(currentFilename) { mutableStateOf("") }
    // Levels: only WARN + ERROR enabled by default — the log file is
    // most useful for triage of failures, so the initial view shows
    // the failure surface first and the user can opt into INFO /
    // DEBUG / TRACE to dig further. Headers without a recognised
    // level token (legacy / pre-AppLog) are kept visible — the user
    // can't filter them out explicitly, but a search query still
    // narrows them.
    var enabledLevels by remember(currentFilename) {
        mutableStateOf(setOf(LogLevel.WARN, LogLevel.ERROR))
    }
    // Time range — strings so partial / blank inputs don't fight the
    // user mid-keystroke. Parsed via normaliseTimeFilter; an
    // unparseable value falls back to "no constraint", same idea as
    // the network-timeout fields under Settings.
    var startTimeText by remember(currentFilename) { mutableStateOf("") }
    var endTimeText by remember(currentFilename) { mutableStateOf("") }
    // Tag filter. "(All)" sentinel = no constraint. The pulldown is
    // populated from the distinct set of tags actually present in
    // this file's entries, sorted alphabetically.
    var selectedTag by remember(currentFilename) { mutableStateOf("(All)") }
    val availableTags = remember(allEntries) {
        val present = allEntries
            .mapNotNull { parseHeader(it.header).tag.trim().takeIf { t -> t.isNotEmpty() } }
            .distinct()
            .sorted()
        listOf("(All)") + present
    }

    val entries = remember(allEntries, searchQuery, enabledLevels, startTimeText, endTimeText, selectedTag) {
        val query = searchQuery.trim().lowercase()
        val startT = normaliseTimeFilter(startTimeText)
        val endT = normaliseTimeFilter(endTimeText)
        allEntries.filter { entry ->
            val lvl = levelOfHeader(entry.header)
            if (lvl != null && lvl !in enabledLevels) return@filter false
            if (selectedTag != "(All)") {
                val t = parseHeader(entry.header).tag.trim()
                if (t != selectedTag) return@filter false
            }
            if (query.isNotEmpty() && !entry.text.lowercase().contains(query)) return@filter false
            if (startT != null) {
                val t = timeOfHeader(entry.header)
                if (t != null && t < startT) return@filter false
            }
            if (endT != null) {
                val t = timeOfHeader(entry.header)
                if (t != null && t > endT) return@filter false
            }
            true
        }
    }

    // In-screen overlay: tap an entry → full-screen view of just that
    // entry, with prev/next buttons that walk the same filtered list.
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

        // ===== Filter row =====
        // Search field + tag pulldown share one row. The pulldown is
        // a fixed-width dropdown showing the distinct tags present in
        // this file (e.g. App / Report / ApiCall / SSE / …); "(All)"
        // disables the filter. Search trailing ✕ clears the query in
        // one tap.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search", fontSize = 11.sp) },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        TextButton(onClick = { searchQuery = "" }, contentPadding = PaddingValues(0.dp)) {
                            Text("✕", fontSize = 14.sp, color = AppColors.TextTertiary)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = AppColors.outlinedFieldColors()
            )
            TagDropdown(
                value = selectedTag,
                options = availableTags,
                onPick = { selectedTag = it },
                modifier = Modifier.weight(0.7f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Level chips. Multi-select; tapping toggles inclusion. All
        // enabled by default. Horizontal scroll keeps the row tight
        // on narrow phones.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (lvl in listOf(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)) {
                val selected = lvl in enabledLevels
                FilterChip(
                    selected = selected,
                    onClick = {
                        enabledLevels = if (selected) enabledLevels - lvl else enabledLevels + lvl
                    },
                    label = { Text(lvl.name, fontSize = 11.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Time range — Material3 clock pickers. Tap a button to open
        // the dialog; the displayed value is "HH:mm" or "(any)" when
        // no constraint is set. Each dialog also has a Clear button
        // to drop the filter without typing.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeFilterButton(
                label = "Start",
                value = startTimeText,
                onChange = { startTimeText = it },
                modifier = Modifier.weight(1f)
            )
            TimeFilterButton(
                label = "End",
                value = endTimeText,
                onChange = { endTimeText = it },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Count line. Tells the user when the filter is hiding rows
        // so an empty list doesn't read as "the log is empty".
        if (allEntries.isNotEmpty()) {
            Text(
                "Showing ${entries.size} of ${allEntries.size}",
                fontSize = 11.sp, color = AppColors.TextTertiary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Reversed entry list (most recent first). Tap a row → full
        // screen view of that one entry with prev/next within this
        // file's entry list. Stack-trace continuation lines stay
        // glued to their header by parseLogEntries.
        Box(modifier = Modifier.weight(1f).background(AppColors.CardBackground).padding(8.dp)) {
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (allEntries.isEmpty()) "(empty)" else "(no matches)",
                        color = AppColors.TextTertiary
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(entries.size) { i ->
                        val entry = entries[i]
                        val color = colorForEntry(entry.header)
                        val suffix = if (entry.lines.size > 1) "  (+${entry.lines.size - 1})" else ""
                        Text(
                            stripDatePrefix(entry.header) + suffix,
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

/** Tap-to-open clock-style time picker used for the Start / End
 *  filter on the log file viewer. The on-screen button shows the
 *  current HH:mm or "(any)" when unset; tapping opens a Material 3
 *  TimePicker inside a dialog. The dialog has an explicit Clear
 *  button so the user can drop the filter without typing — much
 *  more usable than the text field this replaces, which forced
 *  thumb-typed digits + colons.
 *
 *  Two-way state: [value] is the canonical HH:mm string the rest of
 *  the screen filters by (empty = no constraint). [onChange] writes
 *  back, called with empty on Clear and "HH:mm" on OK. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeFilterButton(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val displayValue = value.takeIf { it.isNotBlank() } ?: "(any)"
    OutlinedButton(
        onClick = { open = true },
        modifier = modifier,
        colors = AppColors.outlinedButtonColors(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text("$label: $displayValue", fontSize = 11.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("⏱", fontSize = 11.sp, color = AppColors.TextTertiary)
    }
    if (open) {
        // Seed the picker with the current value when set, otherwise
        // start at 00:00. Parsing is lenient — anything we can't read
        // falls through to 00:00.
        val seedParts = if (value.isNotBlank()) value.split(":") else emptyList()
        val seedHour = seedParts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
        val seedMinute = seedParts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val state = rememberTimePickerState(initialHour = seedHour, initialMinute = seedMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("$label time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val hh = state.hour.toString().padStart(2, '0')
                    val mm = state.minute.toString().padStart(2, '0')
                    onChange("$hh:$mm")
                    open = false
                }) { Text("OK", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onChange(""); open = false }) {
                        Text("Clear", color = AppColors.Red, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = { open = false }) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            }
        )
    }
}

/** Outlined-button dropdown shared with the log file viewer for the
 *  tag filter. "(All)" sentinel is rendered as just the placeholder
 *  label; any other selection shows "Tag: <value>". Mirrors the
 *  FilterDropdown used by the API Traces list — same look so the two
 *  filtering screens feel consistent. */
@Composable
private fun TagDropdown(
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        ) {
            val display = if (value == "(All)") "Tag" else "Tag: $value"
            Text(display, fontSize = 11.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾", fontSize = 11.sp, color = AppColors.TextTertiary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt, fontSize = 13.sp) },
                    onClick = { onPick(opt); expanded = false }
                )
            }
        }
    }
}

/** Split an entry's header into (timestamp, levelToken, tagAndRest).
 *  AppLog writes the line as "YYYY-MM-DD HH:MM:SS.SSS LEVEL TAG: rest"
 *  via appendLine; this regex matches that shape, falling back to a
 *  best-effort parse on legacy or hand-written lines. */
private data class HeaderParts(val timestamp: String, val level: String, val tag: String, val rest: String)

private fun parseHeader(header: String): HeaderParts {
    // Fast path: AppLog's exact format. Anchored so a misshapen line
    // (no timestamp, raw stack trace at line 0, etc.) falls through.
    val re = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (\w+) ([^:]+): (.*)$""", RegexOption.DOT_MATCHES_ALL)
    val m = re.matchEntire(header)
    if (m != null) {
        return HeaderParts(
            timestamp = m.groupValues[1],
            level = m.groupValues[2],
            tag = m.groupValues[3],
            rest = m.groupValues[4]
        )
    }
    // Legacy / corrupt line: render verbatim under "rest" so nothing
    // is lost, leave the other slots blank.
    return HeaderParts(timestamp = "", level = "", tag = "", rest = header)
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
    val parts = remember(entry.header) { parseHeader(entry.header) }
    // Stack-trace / continuation lines: every line after the header.
    val continuation = remember(entry) {
        if (entry.lines.size <= 1) emptyList() else entry.lines.drop(1)
    }

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

        // Body: tap left half → previous entry, tap right half → next
        // entry. detectTapGestures + verticalScroll cohabit cleanly —
        // drags are consumed by the scroll modifier, taps fall through
        // to the gesture detector. Disabled at the ends so the user
        // gets visible feedback (no nav) instead of silent no-op via
        // the page counter.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AppColors.CardBackground)
                .pointerInput(hasPrev, hasNext, startIndex) {
                    detectTapGestures { offset ->
                        if (offset.x < size.width / 2f) {
                            if (hasPrev) onIndexChange(startIndex - 1)
                        } else {
                            if (hasNext) onIndexChange(startIndex + 1)
                        }
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Line 1: time-of-day. Each log file is one day, so
                // the date prefix is redundant — strip it for display.
                Text(
                    stripDatePrefix(parts.timestamp).ifBlank { "(no timestamp)" },
                    fontSize = 13.sp,
                    color = AppColors.TextTertiary,
                    fontFamily = FontFamily.Monospace
                )
                // Line 2: log level + tag. Level-coloured + bold so
                // the severity is unmissable.
                Text(
                    listOf(parts.level, parts.tag).filter { it.isNotBlank() }.joinToString("  "),
                    fontSize = 14.sp,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                // Line 3: the data / message itself. SelectionContainer
                // would be nice eventually for partial copy-paste; for
                // now the title-bar Copy button grabs the whole entry.
                Text(
                    parts.rest,
                    fontSize = 13.sp,
                    color = Color(0xFFCCCCCC),
                    fontFamily = FontFamily.Monospace
                )
                // Stack-trace continuation, if any.
                if (continuation.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    for (line in continuation) {
                        Text(
                            line,
                            fontSize = 12.sp,
                            color = AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Position indicator below the tap zone. Buttons would
        // duplicate the tap zone above — keep just the counter so the
        // user knows where they are without a second control to ignore.
        Text(
            "${startIndex + 1} / $total  (tap left ← prev, tap right → next)",
            fontSize = 11.sp,
            color = AppColors.TextTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}
