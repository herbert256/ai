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
 * The cheaper of the two on-device search variants. Takes a single
 * word (or short phrase, used as a substring), case-insensitive
 * substring match against [Report.prompt] and every successful
 * agent's response body. No tokenisation, no scoring — a report is
 * either a hit or it isn't. Results sorted by recency.
 */
@Composable
fun QuickLocalSearchScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenReport: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<QuickHit>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Quick local search", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Word") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
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
                    val hits = withContext(Dispatchers.IO) { runQuickSearch(context, q) }
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
                    }
                }
            }
        }
    }
}

private data class QuickHit(val reportId: String, val title: String, val timestamp: String)

private fun runQuickSearch(context: android.content.Context, word: String): List<QuickHit> {
    val needle = word.lowercase()
    if (needle.isBlank()) return emptyList()
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val reports: List<Report> = ReportStorage.getAllReports(context)
    return reports.mapNotNull { r ->
        val matchesPrompt = r.prompt.contains(needle, ignoreCase = true)
        val matchesAnyResponse = r.agents.any { a ->
            a.responseBody?.contains(needle, ignoreCase = true) == true
        }
        if (!matchesPrompt && !matchesAnyResponse) null
        else QuickHit(r.id, r.title.ifBlank { "(untitled)" }, df.format(Date(r.timestamp)))
    }.sortedByDescending { it.timestamp }
}
