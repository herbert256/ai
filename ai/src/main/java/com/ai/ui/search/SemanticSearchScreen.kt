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
import com.ai.data.AnalysisRepository
import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.ai.data.EmbeddingsStore
import com.ai.data.ModelType
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.embed
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search across saved reports. Two modes:
 *
 *  * Local app search — pure on-device case-insensitive token match
 *    over title + prompt + every agent response. Nothing leaves the
 *    device.
 *  * Embedding search — picks an OpenAI-compatible embedding model and
 *    scores each report by cosine similarity to the query embedding.
 *    Sends report text to the chosen provider on first index; cached
 *    per (reportId, provider, model, content hash) so edited reports
 *    re-embed automatically.
 *
 * MVP scope: reports only (no chats yet), single-pass scoring (no
 * chunking within a long report).
 */
@Composable
fun SemanticSearchScreen(
    aiSettings: Settings,
    repository: AnalysisRepository,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenReport: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Embedding-capable (provider, model) pairs across active providers — read
    // from Settings.modelTypes which is populated by ModelType.infer at fetch
    // time and overridden by the user via Manual model types overrides.
    val embeddingChoices = remember(aiSettings) { supportedEmbeddingChoices(aiSettings) }
    // Local-app-search is always available; embedding choices are layered
    // on top. Local is the default so first-run users don't have to
    // configure embeddings before getting any value out of the screen.
    var mode by remember { mutableStateOf<SearchMode>(SearchMode.Local) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Semantic search", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        // Mode picker — always shows "Local app search" as an option, plus
        // every embedding-capable (provider, model) pair the user has
        // configured. Embedding rows note that they upload report text.
        Box {
            OutlinedButton(
                onClick = { pickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) {
                Text(
                    text = when (val m = mode) {
                        SearchMode.Local -> "Local app search"
                        is SearchMode.Embedding -> "${m.service.displayName} / ${m.model}"
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Local app search") },
                    onClick = { mode = SearchMode.Local; pickerOpen = false }
                )
                embeddingChoices.forEach { (s, m) ->
                    DropdownMenuItem(
                        text = { Text("${s.displayName} / $m") },
                        onClick = { mode = SearchMode.Embedding(s, m); pickerOpen = false }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                val current = mode
                if (current is SearchMode.Embedding) {
                    val key = aiSettings.getApiKey(current.service)
                    if (key.isBlank()) { status = "No API key set for ${current.service.displayName}"; return@Button }
                }
                running = true
                status = if (current is SearchMode.Local) "Searching…" else "Indexing reports…"
                results = emptyList()
                scope.launch {
                    val hits = withContext(Dispatchers.IO) {
                        when (current) {
                            SearchMode.Local -> runLocalSearch(context, q)
                            is SearchMode.Embedding -> runEmbeddingSearch(
                                context, repository, current.service,
                                aiSettings.getApiKey(current.service), current.model, q
                            ) { msg -> scope.launch(Dispatchers.Main) { status = msg } }
                        }
                    }
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
                            Text(hit.title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hit.timestamp, fontSize = 11.sp, color = AppColors.TextTertiary)
                        }
                        Text("%.3f".format(hit.score), fontSize = 11.sp, color = AppColors.Blue, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

internal fun supportedEmbeddingChoices(aiSettings: Settings): List<Pair<AppService, String>> {
    return aiSettings.getActiveServices().flatMap { service ->
        if (service.apiFormat != ApiFormat.OPENAI_COMPATIBLE) return@flatMap emptyList()
        val cfg = aiSettings.getProvider(service)
        cfg.models.mapNotNull { model ->
            if (aiSettings.getModelType(service, model) == ModelType.EMBEDDING) service to model else null
        }
    }
}

private data class SearchHit(val reportId: String, val title: String, val timestamp: String, val score: Double)

private sealed class SearchMode {
    object Local : SearchMode()
    data class Embedding(val service: AppService, val model: String) : SearchMode()
}

/** On-device token search across [Report.title], [Report.prompt], and
 *  every successful agent's response body. The query is split on
 *  whitespace, lowercased; a report scores by the sum of token
 *  occurrences in its concatenated text. Reports where no token
 *  appears at all are dropped. Sorted descending by score, then by
 *  recency for ties. Top 25. Nothing leaves the device. */
private fun runLocalSearch(context: android.content.Context, query: String): List<SearchHit> {
    val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val reports: List<Report> = ReportStorage.getAllReports(context)
    return reports.mapNotNull { r ->
        val sb = StringBuilder()
        sb.append(r.title).append('\n').append(r.prompt).append('\n')
        for (a in r.agents) {
            a.responseBody?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        }
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
        else SearchHit(r.id, r.title.ifBlank { "(untitled)" }, df.format(Date(r.timestamp)), score.toDouble())
    }.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.timestamp }).take(25)
}

/** Embed [query], embed each report's representative text (cached), score by
 *  cosine similarity, return top 10 sorted descending. */
private suspend fun runEmbeddingSearch(
    context: android.content.Context,
    repository: AnalysisRepository,
    service: AppService,
    apiKey: String,
    model: String,
    query: String,
    onProgress: (String) -> Unit
): List<SearchHit> {
    val queryVec = repository.embed(service, apiKey, model, listOf(query))?.firstOrNull() ?: return emptyList()
    val reports: List<Report> = ReportStorage.getAllReports(context)
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val toEmbed = mutableListOf<Triple<String, String, String>>() // (reportId, repText, displayTitle)
    val cached = mutableListOf<Triple<String, List<Double>, String>>() // already-embedded: id, vec, title

    for ((i, r) in reports.withIndex()) {
        if (i % 10 == 0) onProgress("Indexing reports… ${i + 1} / ${reports.size}")
        val rep = "${r.title}\n${r.prompt}\n${r.agents.firstOrNull { !it.responseBody.isNullOrBlank() }?.responseBody?.take(2000) ?: ""}"
        val title = r.title.ifBlank { "(untitled)" } + " — " + df.format(Date(r.timestamp))
        val existing = EmbeddingsStore.get(context, r.id, service.id, model, rep)
        if (existing != null) cached += Triple(r.id, existing, title)
        else toEmbed += Triple(r.id, rep, title)
    }

    // Batch the new ones up to 50 at a time — most providers allow at least
    // that many inputs per /embeddings call.
    val batched = toEmbed.chunked(50)
    for ((batchIdx, batch) in batched.withIndex()) {
        onProgress("Embedding batch ${batchIdx + 1} / ${batched.size} (${batch.size} reports)")
        val vecs = repository.embed(service, apiKey, model, batch.map { it.second }) ?: return emptyList()
        for ((j, item) in batch.withIndex()) {
            val v = vecs[j]
            EmbeddingsStore.put(context, item.first, service.id, model, item.second, v)
            cached += Triple(item.first, v, item.third)
        }
    }

    return cached.map { (id, vec, title) ->
        SearchHit(id, title.substringBefore(" — "), title.substringAfter(" — ", ""), EmbeddingsStore.cosine(queryVec, vec))
    }.sortedByDescending { it.score }.take(10).filter { it.score > 0.0 }
}
