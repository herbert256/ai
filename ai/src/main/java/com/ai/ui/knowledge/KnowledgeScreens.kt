package com.ai.ui.knowledge

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AnalysisRepository
import com.ai.data.AppService
import com.ai.data.KnowledgeBase
import com.ai.data.KnowledgeService
import com.ai.data.KnowledgeSourceType
import com.ai.data.KnowledgeStore
import com.ai.data.LocalEmbedder
import com.ai.model.Settings
import com.ai.ui.search.supportedEmbeddingChoices
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level Knowledge screen — lists every saved knowledge base and
 * lets the user create new ones. Tap a KB to drill into its sources.
 */
@Composable
fun KnowledgeListScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenKb: (String) -> Unit,
    onCreateKb: () -> Unit,
    /** SAF Uris (file:// / content://) or http(s) URLs the user
     *  shared into the app. The screen surfaces a banner that lets
     *  them dump these into an existing KB or create a new one
     *  pre-populated. Cleared via [onConsumePending] once handled. */
    pendingUris: List<String> = emptyList(),
    onConsumePending: () -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val kbs by produceState<List<KnowledgeBase>>(initialValue = emptyList(), refreshTick) {
        value = withContext(Dispatchers.IO) { KnowledgeStore.listKnowledgeBases(context) }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "AI Knowledge", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Attach PDFs, text files, markdown, or web pages here. Knowledge bases can be linked to a Report or a Chat to inject relevant excerpts before each call.",
            fontSize = 12.sp, color = AppColors.TextTertiary)
        Spacer(modifier = Modifier.height(12.dp))

        if (pendingUris.isNotEmpty()) {
            // Sticky banner — user came from the share-target chooser
            // and we owe them a place to dump the payload. Tapping
            // an existing KB ingests there; "+ New knowledge base"
            // creates one and the detail screen consumes the queue.
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (pendingUris.size == 1) "1 shared item ready to import" else "${pendingUris.size} shared items ready to import",
                        fontSize = 13.sp, color = AppColors.Green, fontWeight = FontWeight.SemiBold
                    )
                    Text("Pick a knowledge base below — or create a new one — to ingest the shared file(s) or URL(s).",
                        fontSize = 11.sp, color = AppColors.TextTertiary)
                    TextButton(onClick = onConsumePending) {
                        Text("Discard share", fontSize = 11.sp, color = AppColors.Red)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = onCreateKb,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("+ New knowledge base", maxLines = 1, softWrap = false) }

        Spacer(modifier = Modifier.height(12.dp))

        if (kbs.isEmpty()) {
            Text("No knowledge bases yet.", fontSize = 13.sp, color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = 16.dp))
        } else {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                kbs.forEach { kb ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenKb(kb.id) },
                        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(kb.name, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = embedderLabel(kb),
                                fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${kb.sources.size} sources · ${kb.totalChunks} chunks",
                                fontSize = 11.sp, color = AppColors.TextTertiary
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { refreshTick++ }
}

private fun embedderLabel(kb: KnowledgeBase): String =
    if (kb.embedderProviderId == "LOCAL") "Local · ${kb.embedderModel}"
    else "${AppService.findById(kb.embedderProviderId)?.displayName ?: kb.embedderProviderId} · ${kb.embedderModel}"

/**
 * Wizard for picking a name + embedder when creating a new KB.
 * Lists every available embedder: local TextEmbedder .tflite files
 * + every (provider, model) marked as EMBEDDING from active
 * providers.
 */
@Composable
fun NewKnowledgeBaseScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onCreated: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    val localEmbedders = remember { LocalEmbedder.availableModels(context) }
    val remoteEmbedders = remember(aiSettings) { supportedEmbeddingChoices(aiSettings) }
    // Each option encodes (providerId, modelName, displayLabel).
    val options = remember(localEmbedders, remoteEmbedders) {
        val local = localEmbedders.map { Triple("LOCAL", it, "Local · $it") }
        val remote = remoteEmbedders.map { (svc, m) -> Triple(svc.id, m, "${svc.displayName} · $m") }
        local + remote
    }
    var selected by remember { mutableStateOf(options.firstOrNull()) }
    var pickerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "New knowledge base", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text("Embedder — fixed for the lifetime of this KB", fontSize = 12.sp, color = AppColors.TextTertiary)
        Spacer(modifier = Modifier.height(4.dp))
        if (options.isEmpty()) {
            Text(
                "No embedders configured. Install a local one in Housekeeping → Local LiteRT models, or mark a remote model as EMBEDDING in AI Setup → Manual model types overrides.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
        } else {
            Box {
                OutlinedButton(
                    onClick = { pickerOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) {
                    Text(selected?.third ?: "Pick an embedder",
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    options.forEach { opt ->
                        DropdownMenuItem(text = { Text(opt.third) },
                            onClick = { selected = opt; pickerOpen = false })
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                val (providerId, model, _) = selected ?: return@Button
                val kb = KnowledgeStore.createKnowledgeBase(context, name.ifBlank { "Knowledge base" }, providerId, model)
                onCreated(kb.id)
            },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Create", maxLines = 1, softWrap = false) }
    }
}

/**
 * KB detail — list sources, add new source (file / URL), re-index,
 * delete KB. Indexing runs in the screen's coroutine scope so the
 * progress text updates reactively while the import happens.
 */
@Composable
fun KnowledgeDetailScreen(
    aiSettings: Settings,
    repository: AnalysisRepository,
    kbId: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** Shared-content URIs queued by the share-target chooser. When
     *  non-empty on first composition we auto-ingest each one through
     *  the same indexFile / indexUrl path the manual buttons use. */
    pendingUris: List<String> = emptyList(),
    onConsumePending: () -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    val kb by produceState<KnowledgeBase?>(initialValue = null, kbId, refreshTick) {
        value = withContext(Dispatchers.IO) { KnowledgeStore.loadKnowledgeBase(context, kbId) }
    }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Auto-ingest the share-target queue once the KB loads. Keyed on
    // pendingUris so a re-share into the same screen re-runs; we
    // clear the queue at the end so a screen recompose / back-and-
    // forward doesn't re-import the same payload.
    LaunchedEffect(kbId, pendingUris, kb?.id) {
        val loaded = kb ?: return@LaunchedEffect
        if (pendingUris.isEmpty()) return@LaunchedEffect
        working = true
        try {
            for (raw in pendingUris) {
                val trimmed = raw.trim()
                if (trimmed.isBlank()) continue
                val isHttp = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
                if (isHttp) {
                    status = "Fetching $trimmed…"
                    val result = withContext(Dispatchers.IO) {
                        KnowledgeService.indexUrl(context, repository, aiSettings, loaded.id, trimmed) { msg, _, _ ->
                            scope.launch(Dispatchers.Main) { status = "$trimmed: $msg" }
                        }
                    }
                    status = result.fold(
                        onSuccess = { src -> "Indexed ${src.name} (${src.chunkCount} chunks)" },
                        onFailure = { e -> "Failed: ${e.message ?: e.javaClass.simpleName}" }
                    )
                } else {
                    val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: continue
                    val type = pickTypeForUri(context, uri)
                    val displayName = displayNameForUri(context, uri) ?: "shared_${System.currentTimeMillis()}"
                    status = "Reading $displayName…"
                    val result = withContext(Dispatchers.IO) {
                        KnowledgeService.indexFile(context, repository, aiSettings, loaded.id, type, uri, displayName) { msg, _, _ ->
                            scope.launch(Dispatchers.Main) { status = "$displayName: $msg" }
                        }
                    }
                    status = result.fold(
                        onSuccess = { src -> "Indexed ${src.name} (${src.chunkCount} chunks)" },
                        onFailure = { e -> "Failed: ${e.message ?: e.javaClass.simpleName}" }
                    )
                }
                refreshTick++
            }
        } finally {
            working = false
            onConsumePending()
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val type = pickTypeForUri(context, uri)
        val displayName = displayNameForUri(context, uri) ?: "source_${System.currentTimeMillis()}"
        scope.launch {
            working = true
            status = "Reading ${displayName}…"
            val result = withContext(Dispatchers.IO) {
                KnowledgeService.indexFile(context, repository, aiSettings, kbId, type, uri, displayName) { msg, _, _ ->
                    scope.launch(Dispatchers.Main) { status = "${displayName}: $msg" }
                }
            }
            working = false
            status = result.fold(
                onSuccess = { src -> "Indexed ${src.name} (${src.chunkCount} chunks)" },
                onFailure = { e -> "Failed: ${e.message ?: e.javaClass.simpleName}" }
            )
            refreshTick++
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = kb?.name ?: "Knowledge base", onBackClick = onBack, onAiClick = onNavigateHome)
        kb?.let {
            Text(embedderLabel(it), fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
            Text("${it.sources.size} sources · ${it.totalChunks} chunks", fontSize = 11.sp, color = AppColors.TextTertiary)
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { pickFile.launch(arrayOf(
                    "text/*",
                    "text/csv",
                    "text/tab-separated-values",
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.oasis.opendocument.text",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.oasis.opendocument.spreadsheet",
                    "image/jpeg",
                    "image/png",
                    "*/*"
                )) },
                enabled = !working, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
            ) { Text("+ File", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = urlInput, onValueChange = { urlInput = it },
            label = { Text("URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = {
                val u = urlInput.trim()
                if (u.isBlank()) return@Button
                scope.launch {
                    working = true
                    status = "Fetching $u…"
                    val result = withContext(Dispatchers.IO) {
                        KnowledgeService.indexUrl(context, repository, aiSettings, kbId, u) { msg, _, _ ->
                            scope.launch(Dispatchers.Main) { status = "$u: $msg" }
                        }
                    }
                    working = false
                    status = result.fold(
                        onSuccess = { src -> "Indexed ${src.name} (${src.chunkCount} chunks)" },
                        onFailure = { e -> "Failed: ${e.message ?: e.javaClass.simpleName}" }
                    )
                    if (result.isSuccess) urlInput = ""
                    refreshTick++
                }
            },
            enabled = !working && urlInput.trim().isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
        ) { Text("+ Web page", maxLines = 1, softWrap = false) }

        status?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Sources", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            kb?.sources?.forEach { src ->
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(src.name, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = buildString {
                                append(src.type.name.lowercase())
                                append(" · ").append(src.chunkCount).append(" chunks")
                                src.errorMessage?.let { append(" · ").append(it) }
                            }
                            Text(sub, fontSize = 11.sp, color = if (src.errorMessage != null) AppColors.Red else AppColors.TextTertiary)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    working = true
                                    status = "Re-indexing ${src.name}…"
                                    val result = withContext(Dispatchers.IO) {
                                        KnowledgeService.reindexSource(context, repository, aiSettings, kbId, src) { msg, _, _ ->
                                            scope.launch(Dispatchers.Main) { status = "${src.name}: $msg" }
                                        }
                                    }
                                    working = false
                                    status = result.fold(
                                        onSuccess = { s -> "Re-indexed ${s.name}" },
                                        onFailure = { e -> "Failed: ${e.message ?: e.javaClass.simpleName}" }
                                    )
                                    refreshTick++
                                }
                            },
                            enabled = !working
                        ) { Text("Re-index", fontSize = 11.sp, color = AppColors.Blue) }
                        TextButton(onClick = {
                            KnowledgeStore.deleteSource(context, kbId, src.id)
                            refreshTick++
                        }) { Text("Delete", fontSize = 11.sp, color = AppColors.Red) }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Delete this knowledge base", color = AppColors.Red, fontSize = 12.sp, maxLines = 1, softWrap = false) }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete knowledge base?") },
            text = { Text("Removes the manifest, every source, and every chunk. Cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    KnowledgeStore.deleteKnowledgeBase(context, kbId)
                    onBack()
                }) { Text("Delete", color = AppColors.Red) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

/** Heuristic file-type detection. Tries the display-name extension
 *  first (the SAF picker usually exposes a real filename); falls
 *  back to the URI's MIME type for share-target / document-provider
 *  URIs that hand us an opaque content:// without a useful name.
 *  Plain text is the last-resort default. */
private fun pickTypeForUri(context: android.content.Context, uri: Uri): KnowledgeSourceType {
    val name = displayNameForUri(context, uri).orEmpty().lowercase()
    val byExtension = when {
        name.endsWith(".pdf") -> KnowledgeSourceType.PDF
        name.endsWith(".md") || name.endsWith(".markdown") -> KnowledgeSourceType.MARKDOWN
        name.endsWith(".docx") -> KnowledgeSourceType.DOCX
        name.endsWith(".odt") -> KnowledgeSourceType.ODT
        name.endsWith(".xlsx") -> KnowledgeSourceType.XLSX
        name.endsWith(".ods") -> KnowledgeSourceType.ODS
        name.endsWith(".csv") || name.endsWith(".tsv") -> KnowledgeSourceType.CSV
        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") -> KnowledgeSourceType.IMAGE
        name.endsWith(".txt") -> KnowledgeSourceType.TEXT
        else -> null
    }
    if (byExtension != null) return byExtension
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase()
    return when (mime) {
        "application/pdf" -> KnowledgeSourceType.PDF
        "text/markdown", "text/x-markdown" -> KnowledgeSourceType.MARKDOWN
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> KnowledgeSourceType.DOCX
        "application/vnd.oasis.opendocument.text" -> KnowledgeSourceType.ODT
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> KnowledgeSourceType.XLSX
        "application/vnd.oasis.opendocument.spreadsheet" -> KnowledgeSourceType.ODS
        "text/csv", "text/comma-separated-values",
        "text/tab-separated-values" -> KnowledgeSourceType.CSV
        else -> when {
            mime?.startsWith("image/") == true -> KnowledgeSourceType.IMAGE
            mime?.startsWith("text/") == true -> KnowledgeSourceType.TEXT
            else -> KnowledgeSourceType.TEXT
        }
    }
}

private fun displayNameForUri(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && nameIdx >= 0) c.getString(nameIdx) else null
        }
    }.getOrNull()
}

/** Multi-select dialog over the existing knowledge bases. Used by
 *  the New Report screen and the Chat parameters screen to attach
 *  RAG context to a run / session.
 *
 *  Embedder constraint: KnowledgeService.retrieve embeds the query
 *  with the *first* attached KB's embedder and silently skips KBs
 *  whose embedder doesn't match (cosine across different vector
 *  spaces is meaningless). The dialog enforces that here so the
 *  user can't accidentally attach a mix that the retriever would
 *  partially drop — once one KB is checked, every other KB whose
 *  (provider, model) embedder differs becomes unselectable, with
 *  a small "embedder mismatch" hint. Clearing the last selection
 *  re-enables every row. */
@Composable
fun KnowledgeAttachDialog(
    knowledgeBases: List<KnowledgeBase>,
    initialSelectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val selected = remember(initialSelectedIds) { mutableStateOf(initialSelectedIds) }
    val anchorEmbedder = remember(selected.value, knowledgeBases) {
        knowledgeBases.firstOrNull { it.id in selected.value }
            ?.let { it.embedderProviderId to it.embedderModel }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach knowledge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (knowledgeBases.isEmpty()) {
                    Text("No knowledge bases yet — create one in AI Knowledge.",
                        fontSize = 13.sp, color = AppColors.TextTertiary)
                } else knowledgeBases.forEach { kb ->
                    val isOn = kb.id in selected.value
                    val matchesAnchor = anchorEmbedder == null ||
                        (kb.embedderProviderId == anchorEmbedder.first && kb.embedderModel == anchorEmbedder.second)
                    val enabled = isOn || matchesAnchor
                    Row(
                        modifier = Modifier.fillMaxWidth().let {
                            if (enabled) it.clickable {
                                selected.value = if (isOn) selected.value - kb.id else selected.value + kb.id
                            } else it
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isOn,
                            enabled = enabled,
                            onCheckedChange = { v ->
                                selected.value = if (v) selected.value + kb.id else selected.value - kb.id
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(kb.name,
                                fontSize = 14.sp,
                                color = if (enabled) Color.White else AppColors.TextDisabled,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(embedderLabel(kb),
                                fontSize = 11.sp,
                                color = if (enabled) AppColors.TextTertiary else AppColors.TextDisabled,
                                fontFamily = FontFamily.Monospace)
                            if (!enabled) {
                                Text("embedder mismatch — clear selection to enable",
                                    fontSize = 10.sp, color = AppColors.TextDisabled)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.value) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
