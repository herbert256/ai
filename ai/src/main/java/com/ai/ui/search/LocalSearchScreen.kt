package com.ai.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device keyword search across saved reports. The query is split on
 * whitespace and matched (case-insensitive) against title + prompt +
 * every successful agent's response body. Score = sum of token
 * occurrences across the concatenated text. No network calls — every
 * byte stays on the device.
 */
@Composable
fun LocalSearchScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenReport: (String) -> Unit,
    /** Per-row 👁 View icon target — opens the report at the View tile
     *  grid. Row tap + 🔧 keep going to Manage. */
    onOpenReportView: (String) -> Unit = onOpenReport
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LocalSearchHit>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "search_local", title = "Extended local search", subject = "Tokenised search over saved reports", onBackClick = onBack)

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Query") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3,
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                val q = query.trim()
                if (q.isBlank()) return@OutlinedButton
                running = true
                status = "Searching…"
                results = emptyList()
                scope.launch {
                    val hits = withContext(Dispatchers.IO) { runLocalSearch(context, q) }
                    results = hits
                    status = if (hits.isEmpty()) "No matches." else "${hits.size} results"
                    running = false
                }
            },
            enabled = !running && query.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text(if (running) "Searching…" else "Search reports", maxLines = 1, softWrap = false) }

        status?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            results.forEach { hit ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenReport(hit.reportId) },
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (com.ai.ui.shared.LocalIconGenEnabled.current) {
                            hit.icon?.let {
                                Text(it, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(hit.title, fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hit.timestamp, fontSize = 11.sp, color = AppColors.TextTertiary)
                        }
                        Text(hit.score.toString(), fontSize = 11.sp, color = AppColors.InfoAccent,
                            modifier = Modifier.padding(start = 8.dp))
                        com.ai.ui.shared.ReportRowActionIcons(
                            onOpenManage = { onOpenReport(hit.reportId) },
                            onOpenView = { onOpenReportView(hit.reportId) }
                        )
                    }
                }
            }
        }
    }
}

private data class LocalSearchHit(val reportId: String, val title: String, val timestamp: String, val epoch: Long, val score: Int, val icon: String?)

/** Per-token matcher. ASCII tokens use `\b` word boundaries so a short token
 *  ("in") doesn't match inside unrelated words ("rain", "window"). Tokens with
 *  any non-ASCII letter (café, schöne, CJK, Cyrillic…) fall back to plain
 *  substring matching — Java/Kotlin `\b` is ASCII-only and never matches around
 *  a non-ASCII letter, so the boundary form would silently score 0 (audit
 *  chat#55). */
internal fun localSearchTokenRegex(token: String): Regex {
    val asciiWord = token.isNotEmpty() && token.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
    return if (asciiWord) Regex("\\b" + Regex.escape(token) + "\\b")
    else Regex(Regex.escape(token))
}

/** Sum of every token's match count in [haystack] (caller lowercases both). */
internal fun localSearchScore(haystack: String, tokens: List<String>): Int =
    tokens.sumOf { localSearchTokenRegex(it).findAll(haystack).count() }

private fun runLocalSearch(context: android.content.Context, query: String): List<LocalSearchHit> {
    val tokens = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val reports: List<Report> = ReportStorage.getAllReports(context)
    return reports.mapNotNull { r ->
        val sb = StringBuilder()
        sb.append(r.title).append('\n').append(r.prompt).append('\n')
        for (a in r.agents) a.responseBody?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        val haystack = sb.toString().lowercase(Locale.ROOT)
        val score = localSearchScore(haystack, tokens)
        if (score == 0) null
        else LocalSearchHit(r.id, r.title.ifBlank { "(untitled)" }, df.format(Date(r.timestamp)), r.timestamp, score, r.icon)
    }.sortedWith(compareByDescending<LocalSearchHit> { it.score }.thenByDescending { it.epoch }).take(25)
}
