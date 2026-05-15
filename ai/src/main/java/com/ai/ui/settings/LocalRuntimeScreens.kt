package com.ai.ui.settings

import com.ai.data.AppLog

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
import com.ai.data.local.LlmRuntime
import com.ai.data.local.LocalEmbedder
import com.ai.data.local.LocalLlm
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
        TitleBar(helpTopic = "local_litert_models", title = "Local LiteRT models", onBackClick = onBack)
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
                            val target = File(LocalEmbedder.localModelsDir(context), "$name.tflite")
                            val deleted = target.delete()
                            installed = LocalEmbedder.availableModels(context)
                            // delete() returns false on filesystem-busy
                            // (mid-ingest read in progress) or permission
                            // failure. The previous code reported success
                            // either way, leaving the file on disk and
                            // misleading the user about the state.
                            status = if (deleted) "Removed $name" else "Could not remove $name (file in use?)"
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
    var installed by remember { mutableStateOf(LocalLlm.installedTaskFiles(context)) }
    var runtimeInstalled by remember { mutableStateOf(LlmRuntime.isInstalled(context)) }
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
                installed = LocalLlm.installedTaskFiles(context)
                status = "Imported $name"
            } else status = "Could not import model"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "local_llms", title = "Local LLMs", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "On-device LLMs via MediaPipe Tasks GenAI. The native runtime isn't bundled with the app — install it once below before importing any model. Most worthwhile models (Gemma, Phi, Llama) require accepting a licence on the model's web page before download.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )

            Text("LLM runtime", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
            Button(
                onClick = {
                    if (runtimeInstalled) {
                        status = "Runtime is already installed."
                        return@Button
                    }
                    working = true
                    status = "Downloading runtime…"
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            LlmRuntime.download(context) { soFar, total ->
                                val pct = if (total > 0) " ${(soFar * 100 / total)}%" else ""
                                scope.launch(Dispatchers.Main) {
                                    status = "Downloading runtime…$pct"
                                }
                            }
                        }
                        working = false
                        if (ok) {
                            LlmRuntime.ensureLoaded(context)
                            runtimeInstalled = LlmRuntime.isInstalled(context)
                            status = "Runtime installed"
                        } else status = "Runtime download failed."
                    }
                },
                enabled = !working && !runtimeInstalled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) {
                Text(
                    if (runtimeInstalled) "LLM runtime ✓"
                    else "Download LLM runtime (~${LlmRuntime.DOWNLOAD_SIZE_MB_HINT} MB)",
                    maxLines = 1, softWrap = false
                )
            }
            Text(
                "One-time download of the MediaPipe inference engine native library. Lands in app storage; not in the APK so first-launch installs stay small.",
                fontSize = 11.sp, color = AppColors.TextTertiary
            )
            if (runtimeInstalled) {
                TextButton(onClick = {
                    if (LlmRuntime.delete(context)) {
                        runtimeInstalled = false
                        status = "Runtime file removed (restart the app to free in-memory copy)."
                    } else status = "Could not remove runtime."
                }) { Text("Remove runtime", color = AppColors.Red, fontSize = 12.sp) }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("Model download links", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
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
                if (!runtimeInstalled) {
                    Text(
                        "Models below are imported but not usable until the runtime is downloaded.",
                        fontSize = 11.sp, color = AppColors.Red
                    )
                }
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
                            installed = LocalLlm.installedTaskFiles(context)
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

/** Copy a user-picked .tflite from the SAF Uri into local_models/.
 *
 *  Two-phase: write to `<name>.part` first, verify the input stream
 *  resolved and the copy landed at least one byte, then atomic-rename
 *  into place. Without staging, a null openInputStream returned
 *  success and left a phantom model entry in the picker; a mid-copy
 *  failure left a truncated file that the runtime would try (and
 *  fail) to load on the next call. */
internal fun importTfliteModel(context: Context, uri: android.net.Uri): String? {
    var staging: File? = null
    return try {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && nameIdx >= 0) c.getString(nameIdx) else null
        }?.takeIf { it.isNotBlank() } ?: "model_${System.currentTimeMillis()}.tflite"
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .let { if (it.endsWith(".tflite", ignoreCase = true)) it else "$it.tflite" }
        val finalTarget = File(LocalEmbedder.localModelsDir(context), sanitized)
        val partFile = File(finalTarget.parentFile, "${finalTarget.name}.part").also { staging = it }
        val input = context.contentResolver.openInputStream(uri) ?: run {
            AppLog.e("LocalRuntime", "tflite import: openInputStream returned null for $uri")
            return null
        }
        input.use { src ->
            partFile.outputStream().use { dst -> src.copyTo(dst) }
        }
        if (!partFile.exists() || partFile.length() == 0L) {
            partFile.delete()
            AppLog.e("LocalRuntime", "tflite import: copy produced empty file for $sanitized")
            return null
        }
        if (finalTarget.exists()) finalTarget.delete()
        if (!partFile.renameTo(finalTarget)) {
            partFile.delete()
            AppLog.e("LocalRuntime", "tflite import: rename failed for $sanitized")
            return null
        }
        finalTarget.nameWithoutExtension
    } catch (e: Exception) {
        staging?.takeIf { it.exists() }?.delete()
        AppLog.e("LocalRuntime", "tflite import failed: ${e.message}", e)
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
    var staging: File? = null
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
        // Disambiguate against an existing .task with the same name —
        // re-importing a different `gemma.task` used to silently
        // overwrite the previous file. Append a -2 / -3 / … suffix.
        val finalTarget = run {
            val baseFile = File(outDir, outName)
            if (!baseFile.exists()) baseFile else {
                val stem = outName.removeSuffix(".task")
                var n = 2
                var candidate = File(outDir, "$stem-$n.task")
                while (candidate.exists()) {
                    n += 1
                    candidate = File(outDir, "$stem-$n.task")
                }
                candidate
            }
        }
        // Stage all writes under <name>.part. availableLlms() lists
        // by ".task" extension, so an in-flight .part file never shows
        // up as an installed model. The renameTo only fires after the
        // copy or archive extraction succeeds AND the result is
        // non-empty, so a crashed import can't leave the picker
        // pointing at a half-written .task.
        val partFile = File(finalTarget.parentFile, "${finalTarget.name}.part").also { staging = it }

        val input = context.contentResolver.openInputStream(uri) ?: run {
            AppLog.e("LocalRuntime", "LLM import: openInputStream returned null for $uri")
            return null
        }
        input.use { src ->
            when {
                lower.endsWith(".task") -> partFile.outputStream().use { src.copyTo(it) }
                lower.endsWith(".zip") -> {
                    val entry = extractFirstTaskFromZip(src, partFile)
                        ?: throw java.io.IOException("No .task entry inside $displayName")
                    AppLog.i("LocalRuntime", "Extracted $entry from $displayName")
                }
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> {
                    val entry = extractFirstTaskFromTar(java.util.zip.GZIPInputStream(src), partFile)
                        ?: throw java.io.IOException("No .task entry inside $displayName")
                    AppLog.i("LocalRuntime", "Extracted $entry from $displayName")
                }
                lower.endsWith(".tar") -> {
                    val entry = extractFirstTaskFromTar(src, partFile)
                        ?: throw java.io.IOException("No .task entry inside $displayName")
                    AppLog.i("LocalRuntime", "Extracted $entry from $displayName")
                }
                else -> partFile.outputStream().use { src.copyTo(it) }
            }
        }
        if (!partFile.exists() || partFile.length() == 0L) {
            partFile.delete()
            AppLog.e("LocalRuntime", "LLM import: staged file empty for $displayName")
            return null
        }
        if (!partFile.renameTo(finalTarget)) {
            partFile.delete()
            AppLog.e("LocalRuntime", "LLM import: rename failed for $displayName")
            return null
        }
        finalTarget.nameWithoutExtension
    } catch (e: Exception) {
        staging?.takeIf { it.exists() }?.delete()
        AppLog.e("LocalRuntime", "LLM import failed: ${e.message}", e)
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
