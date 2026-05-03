package com.ai.ui.search

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.ai.data.LocalEmbedder
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.withTracerTags
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    onOpenReport: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var availableModels by remember { mutableStateOf(LocalEmbedder.availableModels(context)) }
    var picked by remember { mutableStateOf(availableModels.firstOrNull()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LocalSemanticHit>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { importTfliteModel(context, uri) }
            if (name != null) {
                availableModels = LocalEmbedder.availableModels(context)
                picked = name
                status = "Imported $name"
            } else status = "Could not import model"
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Local semantic search", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        if (availableModels.isEmpty()) {
            Text(
                "No local models yet. Tap \"Add model\" and pick a MediaPipe-compatible text-embedder .tflite (e.g. universal_sentence_encoder.tflite, average_word_embedder.tflite). The file is copied into the app's local_models folder; nothing leaves the device.",
                fontSize = 13.sp, color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }

        Box {
            OutlinedButton(
                onClick = { if (availableModels.isNotEmpty()) pickerOpen = true },
                enabled = availableModels.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) {
                Text(
                    text = picked ?: "No local models",
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                availableModels.forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = { picked = m; pickerOpen = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { pickFile.launch(arrayOf("application/octet-stream", "*/*")) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Add model", maxLines = 1, softWrap = false) }
            picked?.let { name ->
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        LocalEmbedder.release(name)
                        File(LocalEmbedder.localModelsDir(context), "$name.tflite").delete()
                        availableModels = LocalEmbedder.availableModels(context)
                        picked = availableModels.firstOrNull()
                        status = "Removed $name"
                    },
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
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
                status = "Indexing reports…"
                results = emptyList()
                scope.launch {
                    val hits = withContext(Dispatchers.IO) {
                        runLocalEmbedSearch(context, model, q) { msg ->
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
                            Text(hit.title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hit.timestamp, fontSize = 11.sp, color = AppColors.TextTertiary)
                        }
                        Text("%.3f".format(hit.score), fontSize = 11.sp, color = AppColors.Blue,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

private data class LocalSemanticHit(val reportId: String, val title: String, val timestamp: String, val score: Double)

private fun importTfliteModel(context: Context, uri: android.net.Uri): String? {
    return try {
        // Pull a sensible filename out of DocumentsContract metadata; fall
        // back to an opaque uuid+timestamp so the user always gets *some*
        // visible name in the picker.
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && nameIdx >= 0) c.getString(nameIdx) else null
        }?.takeIf { it.isNotBlank() } ?: "model_${System.currentTimeMillis()}.tflite"
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .let { if (it.endsWith(".tflite", ignoreCase = true)) it else "$it.tflite" }
        val target = File(LocalEmbedder.localModelsDir(context), sanitized)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.nameWithoutExtension
    } catch (e: Exception) {
        android.util.Log.e("LocalSemanticSearch", "import failed: ${e.message}", e)
        null
    }
}

private suspend fun runLocalEmbedSearch(
    context: Context,
    modelName: String,
    query: String,
    onProgress: (String) -> Unit
): List<LocalSemanticHit> = withTracerTags(category = "Local semantic search") {
    val queryVec = LocalEmbedder.embed(context, modelName, listOf(query))?.firstOrNull() ?: return@withTracerTags emptyList()
    val reports: List<Report> = ReportStorage.getAllReports(context)
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val toEmbed = mutableListOf<Triple<String, String, String>>()
    val cached = mutableListOf<Triple<String, List<Double>, String>>()

    val providerKey = "LOCAL"
    for ((i, r) in reports.withIndex()) {
        if (i % 10 == 0) onProgress("Indexing reports… ${i + 1} / ${reports.size}")
        val rep = "${r.title}\n${r.prompt}\n${r.agents.firstOrNull { !it.responseBody.isNullOrBlank() }?.responseBody?.take(2000) ?: ""}"
        val title = r.title.ifBlank { "(untitled)" } + " — " + df.format(Date(r.timestamp))
        val existing = EmbeddingsStore.get(context, r.id, providerKey, modelName, rep)
        if (existing != null) cached += Triple(r.id, existing, title)
        else toEmbed += Triple(r.id, rep, title)
    }

    // MediaPipe TextEmbedder embeds one string per call internally
    // anyway, so a "batch" of 50 just means we get fewer trace entries.
    val batched = toEmbed.chunked(50)
    for ((batchIdx, batch) in batched.withIndex()) {
        onProgress("Embedding batch ${batchIdx + 1} / ${batched.size} (${batch.size} reports)")
        val vecs = LocalEmbedder.embed(context, modelName, batch.map { it.second }) ?: return@withTracerTags emptyList()
        for ((j, item) in batch.withIndex()) {
            val v = vecs[j]
            EmbeddingsStore.put(context, item.first, providerKey, modelName, item.second, v)
            cached += Triple(item.first, v, item.third)
        }
    }

    cached.map { (id, vec, title) ->
        LocalSemanticHit(id, title.substringBefore(" — "), title.substringAfter(" — ", ""),
            EmbeddingsStore.cosine(queryVec, vec))
    }.sortedByDescending { it.score }.take(10).filter { it.score > 0.0 }
}
