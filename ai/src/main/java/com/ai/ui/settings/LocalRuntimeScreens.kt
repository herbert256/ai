package com.ai.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.LocalEmbedder
import com.ai.data.LocalLlm
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen sub-screen under AI Setup → Local LiteRT models.
 *
 * Lists every curated MediaPipe-Tasks text-embedder as its own
 * download button, plus a SAF "Add model from file…" path for
 * user-supplied .tflite files, plus per-row Remove on the Installed
 * list. Drives the on-device embedder runtime ([LocalEmbedder]) used
 * by Local Semantic Search and Knowledge bases whose
 * embedderProviderId == "LOCAL".
 *
 * Note: only models with proper MediaPipe Tasks metadata baked into
 * the .tflite load successfully — that's why the curated list is
 * short. The SAF flow exists for users who've stamped metadata via
 * MediaPipe Model Maker.
 */
@Composable
fun LocalLiteRtModelsScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(LocalEmbedder.availableModels(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { importTfliteModel(context, uri) }
            if (name != null) {
                installed = LocalEmbedder.availableModels(context)
                status = "Imported $name"
            } else status = "Could not import model"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Local LiteRT models", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "On-device text embedders for the Local Semantic Search screen and Local-embedder Knowledge bases. Models live in app storage; nothing leaves the device once installed. Only models with MediaPipe Tasks metadata can load — the curated list below is the verified set.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )

            LocalEmbedder.downloadable.forEach { spec ->
                val isInstalled = spec.name in installed
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Button(
                        onClick = {
                            if (isInstalled) {
                                status = "${spec.displayName} is already installed."
                                return@Button
                            }
                            working = true
                            status = "Downloading ${spec.displayName}…"
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    LocalEmbedder.download(context, spec) { soFar, total ->
                                        val pct = if (total > 0) " ${(soFar * 100 / total)}%" else ""
                                        scope.launch(Dispatchers.Main) {
                                            status = "Downloading ${spec.displayName}…$pct"
                                        }
                                    }
                                }
                                working = false
                                if (ok) {
                                    installed = LocalEmbedder.availableModels(context)
                                    status = "Installed ${spec.displayName}"
                                } else status = "Download failed."
                            }
                        },
                        enabled = !working && !isInstalled,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                    ) {
                        Text(
                            if (isInstalled) "${spec.displayName} ✓"
                            else "Download ${spec.displayName} (~${spec.sizeMbHint} MB)",
                            maxLines = 1, softWrap = false
                        )
                    }
                    Text(spec.description, fontSize = 11.sp, color = AppColors.TextTertiary)
                }
            }

            Button(
                onClick = { pickFile.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
            ) { Text("Add model from file…", maxLines = 1, softWrap = false) }

            if (installed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Installed", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
                installed.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            LocalEmbedder.release(name)
                            File(LocalEmbedder.localModelsDir(context), "$name.tflite").delete()
                            installed = LocalEmbedder.availableModels(context)
                            status = "Removed $name"
                        }) { Text("Remove", color = AppColors.Red, fontSize = 12.sp) }
                    }
                }
            }

            status?.let { Text(it, fontSize = 11.sp, color = AppColors.TextTertiary) }
        }
    }
}

/**
 * Full-screen sub-screen under AI Setup → Local LLMs.
 *
 * Most worthwhile small LLMs (Gemma, Phi, Llama …) are licence-gated
 * and require accepting model-card terms in a browser before
 * download. The screen leads with hand-off links the user opens to
 * do the gating dance, then a SAF picker brings the downloaded
 * .task / .zip / .tar.gz into `<filesDir>/local_llms/`. Per-row
 * Remove deletes. Drives the on-device LLM runtime ([LocalLlm]) used
 * by the synthetic Local provider in chat / report flows.
 */
@Composable
fun LocalLlmsScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(LocalLlm.availableLlms(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            status = "Importing…"
            val name = withContext(Dispatchers.IO) { importTaskModel(context, uri) }
            working = false
            if (name != null) {
                installed = LocalLlm.availableLlms(context)
                status = "Imported $name"
            } else status = "Could not import model"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Local LLMs", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "On-device LLMs via MediaPipe Tasks GenAI. Most useful models (Gemma, Phi, Llama) require accepting a licence on the model's web page before download — open one of the links below in a browser, accept the terms, download the .task file (typically 0.5 - 2.5 GB), then come back and tap \"Add LLM from file\" to import it.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )

            Text("Download links", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
            LocalLlm.recommendedLinks.forEach { link ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.url)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                    ) {
                        Text(
                            text = if (link.sizeHint != null) "${link.name} (${link.sizeHint})" else link.name,
                            fontSize = 12.sp, maxLines = 1, softWrap = false
                        )
                    }
                    Text(link.description, fontSize = 11.sp, color = AppColors.TextTertiary)
                }
            }

            Button(
                onClick = { pickFile.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
            ) { Text("Add LLM from file…", maxLines = 1, softWrap = false) }
            Text(
                "Accepts .task, .zip, .tar.gz, .tgz, .tar — the first .task entry inside an archive is extracted automatically.",
                fontSize = 11.sp, color = AppColors.TextTertiary
            )

            if (installed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Installed", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
                installed.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            LocalLlm.release(name)
                            File(LocalLlm.localLlmsDir(context), "$name.task").delete()
                            installed = LocalLlm.availableLlms(context)
                            status = "Removed $name"
                        }) { Text("Remove", color = AppColors.Red, fontSize = 12.sp) }
                    }
                }
            }

            status?.let { Text(it, fontSize = 11.sp, color = AppColors.TextTertiary) }
        }
    }
}

// ===== Import helpers (lifted from HousekeepingScreen) =====

/** Copy a user-picked .tflite from the SAF Uri into local_models/. */
internal fun importTfliteModel(context: Context, uri: android.net.Uri): String? {
    return try {
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
        android.util.Log.e("LocalRuntime", "tflite import failed: ${e.message}", e)
        null
    }
}

/** SAF copy for a Local LLM source. Accepts:
 *
 *   - `.task` — copied straight into local_llms/
 *   - `.zip` — first .task entry extracted
 *   - `.tar`, `.tar.gz`, `.tgz` — same
 *
 *  The .task bundles ship with their tokenizer + weights inside, so
 *  a single file is the whole model. Many Kaggle/HF downloads wrap
 *  it in an archive; this saves the user a desktop round-trip.
 *  Returns the imported model name (no extension), or null on
 *  failure. */
internal fun importTaskModel(context: Context, uri: android.net.Uri): String? {
    return try {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && nameIdx >= 0) c.getString(nameIdx) else null
        }?.takeIf { it.isNotBlank() } ?: "llm_${System.currentTimeMillis()}.task"
        val lower = displayName.lowercase()
        val outDir = LocalLlm.localLlmsDir(context)
        val outName = when {
            lower.endsWith(".task") -> sanitizeFileName(displayName, ".task")
            lower.endsWith(".zip") -> stemFromArchive(displayName, listOf(".zip")) + ".task"
            lower.endsWith(".tar.gz") -> stemFromArchive(displayName, listOf(".tar.gz")) + ".task"
            lower.endsWith(".tgz") -> stemFromArchive(displayName, listOf(".tgz")) + ".task"
            lower.endsWith(".tar") -> stemFromArchive(displayName, listOf(".tar")) + ".task"
            else -> sanitizeFileName(displayName, ".task")
        }
        val target = File(outDir, outName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            when {
                lower.endsWith(".task") -> target.outputStream().use { input.copyTo(it) }
                lower.endsWith(".zip") -> {
                    val entry = extractFirstTaskFromZip(input, target)
                        ?: throw java.io.IOException("No .task entry inside $displayName")
                    android.util.Log.i("LocalRuntime", "Extracted $entry from $displayName")
                }
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> {
                    val entry = extractFirstTaskFromTar(java.util.zip.GZIPInputStream(input), target)
                        ?: throw java.io.IOException("No .task entry inside $displayName")
                    android.util.Log.i("LocalRuntime", "Extracted $entry from $displayName")
                }
                lower.endsWith(".tar") -> {
                    val entry = extractFirstTaskFromTar(input, target)
                        ?: throw java.io.IOException("No .task entry inside $displayName")
                    android.util.Log.i("LocalRuntime", "Extracted $entry from $displayName")
                }
                else -> target.outputStream().use { input.copyTo(it) }
            }
        }
        if (!target.exists() || target.length() == 0L) {
            target.delete()
            return null
        }
        target.nameWithoutExtension
    } catch (e: Exception) {
        android.util.Log.e("LocalRuntime", "LLM import failed: ${e.message}", e)
        null
    }
}

private fun sanitizeFileName(name: String, ensuredSuffix: String): String {
    val cleaned = name.replace(Regex("[^A-Za-z0-9._-]+"), "_")
    return if (cleaned.endsWith(ensuredSuffix, ignoreCase = true)) cleaned else "$cleaned$ensuredSuffix"
}

/** Strip the archive extension(s), then sanitize. Used as the .task
 *  filename stem when the archive only contains one model. */
private fun stemFromArchive(name: String, suffixes: List<String>): String {
    var stem = name
    for (s in suffixes) if (stem.endsWith(s, ignoreCase = true)) {
        stem = stem.substring(0, stem.length - s.length)
        break
    }
    return stem.replace(Regex("[^A-Za-z0-9._-]+"), "_")
}

/** Stream the first `*.task` entry out of a .zip into [target].
 *  Returns the entry name if extracted, null when no .task is found. */
private fun extractFirstTaskFromZip(input: java.io.InputStream, target: File): String? {
    java.util.zip.ZipInputStream(input).use { zin ->
        var entry = zin.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".task", ignoreCase = true)) {
                target.outputStream().use { zin.copyTo(it) }
                return entry.name
            }
            entry = zin.nextEntry
        }
    }
    return null
}

/** Stream the first `*.task` entry out of a tar (or already-gunzipped
 *  tar.gz) stream into [target]. Uses Apache Commons Compress's
 *  TarArchiveInputStream — same API for both. */
private fun extractFirstTaskFromTar(input: java.io.InputStream, target: File): String? {
    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input).use { tin ->
        var entry = tin.nextEntry
        while (entry != null) {
            if (entry.isFile && entry.name.endsWith(".task", ignoreCase = true)) {
                target.outputStream().use { tin.copyTo(it) }
                return entry.name
            }
            entry = tin.nextEntry
        }
    }
    return null
}
