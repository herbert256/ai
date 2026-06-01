package com.ai.ui.search

import android.content.Context
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
import com.ai.data.EmbeddingsStore
import com.ai.data.local.LocalEmbedder
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.withTracerTags
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Semantic search backed by an on-device LiteRT model (MediaPipe
 * Tasks Text Embedder). Models are user-supplied .tflite files in
 * <filesDir>/local_models/; the picker lists whatever's there. Embed
 * calls go through [LocalEmbedder] which also writes a synthetic
 * trace entry (hostname "local") so the Trace screen lights up the
 * same way it does for HTTP-backed embedders.
 */
@Composable
fun LocalSemanticSearchScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenReport: (String) -> Unit,
    /** Per-row 👁 View icon target — opens the report at the View tile
     *  grid. Row tap + 🔧 keep going to Manage. */
    onOpenReportView: (String) -> Unit = onOpenReport,
    onNavigateToTraceFile: (String) -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Re-list installed models on resume so a model added/removed in
    // Housekeeping is reflected on return (was an unkeyed remember).
    val resumeTick = com.ai.ui.shared.resumeRefreshTick()
    val availableModels = remember(resumeTick) { LocalEmbedder.availableModels(context) }
    var picked by remember { mutableStateOf(availableModels.firstOrNull()) }
    // Drop a stale pick when the installed-model set changes.
    LaunchedEffect(availableModels) {
        if (picked != null && picked !in availableModels) picked = availableModels.firstOrNull()
    }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LocalSemanticHit>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    // Timestamp captured when the current search starts; the 🐞 only opens a
    // trace produced by this run (timestamp >= start), not the newest one of
    // the category globally.
    var searchStartedAt by remember { mutableStateOf(0L) }
    // Latest "Local semantic search" trace — drives the 🐞 next to the
    // status text after a search completes. Refreshed each time
    // running flips back to false.
    val latestTrace by produceState<String?>(initialValue = null, running) {
        if (running) return@produceState
        val startedAt = searchStartedAt
        value = withContext(Dispatchers.IO) {
            com.ai.data.ApiTracer.getTraceFiles()
                .firstOrNull { it.category == "Local semantic search" && it.timestamp >= startedAt }?.filename
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        val tfTop = latestTrace
        TitleBar(
            helpTopic = "search_local_semantic", title = "Local semantic search", subject = "On-device meaning search, no cloud", onBackClick = onBack,
            onTrace = if (com.ai.data.ApiTracer.ladybugLinksEnabled && tfTop != null) {
                { onNavigateToTraceFile(tfTop) }
            } else null
        )

        if (availableModels.isEmpty()) {
            Text(
                "No local models installed yet. Open AI Housekeeping → Local LiteRT models to download Universal Sentence Encoder Lite or import your own .tflite.",
                fontSize = 13.sp, color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            // Picker over the already-installed models. Maintenance
            // (download / import / remove) lives on the Housekeeping
            // screen so this screen stays focused on running searches.
            Box {
                OutlinedButton(
                    onClick = { pickerOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) {
                    Text(
                        text = picked ?: "Choose a local model",
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    availableModels.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { picked = m; pickerOpen = false })
                    }
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
                val model = picked ?: return@Button
                val q = query.trim()
                if (q.isBlank()) return@Button
                running = true
                searchStartedAt = System.currentTimeMillis()
                status = "Indexing reports…"
                results = emptyList()
                scope.launch {
                    val hits = withContext(Dispatchers.IO) {
                        runLocalEmbedSearch(context, model, q) { msg ->
                            // Hop to Main on the same coroutine so progress writes are
                            // lifecycle-bound rather than fire-and-forget launches.
                            withContext(Dispatchers.Main) { status = msg }
                        }
                    }
                    results = hits
                    status = if (hits.isEmpty()) "No matches." else "${hits.size} results"
                    running = false
                }
            },
            enabled = !running && picked != null && query.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent)
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
                            Text(hit.title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hit.timestamp, fontSize = 11.sp, color = AppColors.TextTertiary)
                        }
                        Text("%.3f".format(hit.score), fontSize = 11.sp, color = AppColors.InfoAccent,
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

private data class LocalSemanticHit(val reportId: String, val title: String, val timestamp: String, val score: Double, val icon: String?)

private suspend fun runLocalEmbedSearch(
    context: Context,
    modelName: String,
    query: String,
    onProgress: suspend (String) -> Unit
): List<LocalSemanticHit> = withTracerTags(category = "Local semantic search") {
    val queryVec = LocalEmbedder.embed(context, modelName, listOf(query))?.firstOrNull() ?: return@withTracerTags emptyList()
    val reports: List<Report> = ReportStorage.getAllReports(context)
    val iconById = reports.associate { it.id to it.icon }
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    // (reportId, repText, displayTitle, displayTimestamp)
    val toEmbed = mutableListOf<EmbedCandidate>()
    // already-embedded: (reportId, vec, displayTitle, displayTimestamp)
    val cached = mutableListOf<CachedCandidate>()

    val providerKey = "LOCAL"
    for ((i, r) in reports.withIndex()) {
        if (i % 10 == 0) onProgress("Indexing reports… ${i + 1} / ${reports.size}")
        val rep = "${r.title}\n${r.prompt}\n${r.agents.firstOrNull { !it.responseBody.isNullOrBlank() }?.responseBody?.take(2000) ?: ""}"
        val title = r.title.ifBlank { "(untitled)" }
        val ts = df.format(Date(r.timestamp))
        val existing = EmbeddingsStore.get(context, r.id, providerKey, modelName, rep)
        if (existing != null) cached += CachedCandidate(r.id, existing, title, ts)
        else toEmbed += EmbedCandidate(r.id, rep, title, ts)
    }

    // MediaPipe TextEmbedder embeds one string per call internally
    // anyway, so a "batch" of 50 just means we get fewer trace entries.
    val batched = toEmbed.chunked(50)
    for ((batchIdx, batch) in batched.withIndex()) {
        onProgress("Embedding batch ${batchIdx + 1} / ${batched.size} (${batch.size} reports)")
        val vecs = LocalEmbedder.embed(context, modelName, batch.map { it.repText }) ?: return@withTracerTags emptyList()
        for ((j, item) in batch.withIndex()) {
            // Embedder may return fewer vectors than inputs; never index past it.
            val v = vecs.getOrNull(j) ?: continue
            EmbeddingsStore.put(context, item.reportId, providerKey, modelName, item.repText, v)
            cached += CachedCandidate(item.reportId, v, item.title, item.timestamp)
        }
    }

    cached.map { c ->
        LocalSemanticHit(c.reportId, c.title, c.timestamp,
            EmbeddingsStore.cosine(queryVec, c.vec), iconById[c.reportId])
    }.sortedByDescending { it.score }.take(10).filter { it.score > 0.0 }
}

private data class EmbedCandidate(val reportId: String, val repText: String, val title: String, val timestamp: String)
private data class CachedCandidate(val reportId: String, val vec: List<Double>, val title: String, val timestamp: String)
