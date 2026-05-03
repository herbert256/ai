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
import androidx.compose.ui.graphics.Color
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
    onOpenReport: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LocalSearchHit>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Local app search", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Query") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3,
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val q = query.trim()
                if (q.isBlank()) return@Button
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
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(hit.title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hit.timestamp, fontSize = 11.sp, color = AppColors.TextTertiary)
                        }
                        Text(hit.score.toString(), fontSize = 11.sp, color = AppColors.Blue,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

private data class LocalSearchHit(val reportId: String, val title: String, val timestamp: String, val score: Int)

private fun runLocalSearch(context: android.content.Context, query: String): List<LocalSearchHit> {
    val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val reports: List<Report> = ReportStorage.getAllReports(context)
    return reports.mapNotNull { r ->
        val sb = StringBuilder()
        sb.append(r.title).append('\n').append(r.prompt).append('\n')
        for (a in r.agents) a.responseBody?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        val haystack = sb.toString().lowercase()
        var score = 0
        for (t in tokens) {
            var idx = 0
            while (true) {
                val found = haystack.indexOf(t, idx)
                if (found < 0) break
                score++
                idx = found + 1
            }
        }
        if (score == 0) null
        else LocalSearchHit(r.id, r.title.ifBlank { "(untitled)" }, df.format(Date(r.timestamp)), score)
    }.sortedWith(compareByDescending<LocalSearchHit> { it.score }.thenByDescending { it.timestamp }).take(25)
}
