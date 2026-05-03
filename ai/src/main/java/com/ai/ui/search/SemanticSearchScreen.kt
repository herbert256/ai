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
 * Semantic search across saved reports. The user picks an embedding-typed
 * model from any active provider; the screen embeds the query, embeds each
 * report's title + first chunk of body (lazily, cached per
 * (docId, provider, model, content hash)), scores by cosine similarity, returns top 10.
 *
 * MVP scope: reports only (no chats yet), single-pass scoring (no chunking
 * within a long report). Cached report embeddings include a content hash, so
 * edited reports are re-embedded automatically on the next search.
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
    var picked by remember { mutableStateOf(embeddingChoices.firstOrNull()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Semantic search", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        if (embeddingChoices.isEmpty()) {
            Text(
                "No supported OpenAI-compatible embedding models found. Mark a compatible provider model as 'embedding' in AI Setup → Models setup → Manual model types overrides, or fetch a provider whose model list includes one (e.g. text-embedding-3-small on OpenAI).",
                fontSize = 13.sp, color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            return@Column
        }

        // Model picker.
        Box {
            OutlinedButton(
                onClick = { pickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) {
                Text(
                    text = picked?.let { (s, m) -> "${s.displayName} / $m" } ?: "Pick embedding model",
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                embeddingChoices.forEach { (s, m) ->
                    DropdownMenuItem(
                        text = { Text("${s.displayName} / $m") },
                        onClick = { picked = s to m; pickerOpen = false }
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
                val (svc, model) = picked ?: return@Button
                val q = query.trim()
                if (q.isBlank()) return@Button
                val key = aiSettings.getApiKey(svc)
                if (key.isBlank()) { status = "No API key set for ${svc.displayName}"; return@Button }
                running = true
                status = "Indexing reports…"
                results = emptyList()
                scope.launch {
                    val hits = withContext(Dispatchers.IO) {
                        runEmbeddingSearch(context, repository, svc, key, model, q) { msg ->
                            scope.launch(Dispatchers.Main) { status = msg }
                        }
                    }
                    results = hits
                    status = if (hits.isEmpty()) "No matches." else "${hits.size} results"
                    running = false
                }
            },
            enabled = !running && picked != null && query.isNotBlank(),
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
