package com.ai.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ai.data.*
import com.ai.ui.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ===== Trace List =====

@Composable
fun TraceListScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectTrace: (String) -> Unit,
    onClearTraces: () -> Unit,
    reportId: String? = null,
    modelFilter: String? = null
) {
    BackHandler { onBack() }
    // getTraceFiles parses every trace JSON to extract 4 summary fields;
    // with hundreds of traces this is tens of ms. Off the UI thread so
    // opening the trace list doesn't stall.
    var allTraceFiles by remember { mutableStateOf(emptyList<TraceFileInfo>()) }
    LaunchedEffect(reportId, modelFilter) {
        allTraceFiles = withContext(Dispatchers.IO) {
            when {
                reportId != null -> ApiTracer.getTraceFilesForReport(reportId)
                modelFilter != null -> ApiTracer.getTraceFiles().filter { it.model == modelFilter }
                else -> ApiTracer.getTraceFiles()
            }
        }
    }
    // Build the category list from whatever the loaded set actually
    // contains. "(All)" sits at the top; "(uncategorised)" represents
    // pre-feature traces and any sites that weren't bracketed.
    val categories = remember(allTraceFiles) {
        val present = allTraceFiles.map { it.category }.distinct()
        val labelled = present.filterNotNull().sorted()
        val hasUncategorised = present.contains(null)
        listOf("(All)") + labelled + (if (hasUncategorised) listOf("(uncategorised)") else emptyList())
    }
    var selectedCategory by rememberSaveable { mutableStateOf("(All)") }

    // Provider distinct list, derived from the trace hostname matched
    // against each AppService's baseUrl. Hostnames we can't resolve get
    // bucketed into "(unknown)" so the user can still slice by them.
    val providers = remember(allTraceFiles) {
        val labels = allTraceFiles.map { providerLabelForHost(it.hostname) }.distinct()
        val labelled = labels.filter { it != "(unknown)" }.sorted()
        val hasUnknown = labels.contains("(unknown)")
        listOf("(All)") + labelled + (if (hasUnknown) listOf("(unknown)") else emptyList())
    }
    var selectedProvider by rememberSaveable { mutableStateOf("(All)") }

    // Model filter — chosen via the Model picker overlay. null = no filter.
    var selectedModel by rememberSaveable { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }

    val traceFiles = remember(allTraceFiles, selectedCategory, selectedProvider, selectedModel) {
        allTraceFiles
            .filter { t -> when (selectedCategory) {
                "(All)" -> true
                "(uncategorised)" -> t.category == null
                else -> t.category == selectedCategory
            } }
            .filter { t -> when (selectedProvider) {
                "(All)" -> true
                else -> providerLabelForHost(t.hostname) == selectedProvider
            } }
            .filter { t -> selectedModel == null || t.model == selectedModel }
    }
    var currentPage by rememberSaveable { mutableIntStateOf(0) }

    // Models with traces in the currently scoped (Category + Provider)
    // subset. The picker shows only these so the user can't pick one
    // that yields zero rows.
    val pickableModels = remember(allTraceFiles, selectedCategory, selectedProvider) {
        allTraceFiles
            .filter { t -> when (selectedCategory) {
                "(All)" -> true
                "(uncategorised)" -> t.category == null
                else -> t.category == selectedCategory
            } }
            .filter { t -> when (selectedProvider) {
                "(All)" -> true
                else -> providerLabelForHost(t.hostname) == selectedProvider
            } }
            .mapNotNull { t ->
                val m = t.model?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                providerLabelForHost(t.hostname) to m
            }
            .distinct()
            .sortedWith(compareBy({ it.first.lowercase() }, { it.second.lowercase() }))
    }

    if (showModelPicker) {
        TraceModelPickerOverlay(
            models = pickableModels,
            current = selectedModel,
            onSelect = { sel -> selectedModel = sel; showModelPicker = false; currentPage = 0 },
            onBack = { showModelPicker = false }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        val rowHeight = 52
        val overhead = 130
        val pageSize = maxOf(1, ((maxHeight.value - overhead) / rowHeight).toInt())
        val totalPages = if (traceFiles.isEmpty()) 1 else (traceFiles.size + pageSize - 1) / pageSize

        LaunchedEffect(totalPages) { if (currentPage >= totalPages) currentPage = (totalPages - 1).coerceAtLeast(0) }

        val startIndex = currentPage * pageSize
        val pageItems = traceFiles.subList(startIndex.coerceAtMost(traceFiles.size), (startIndex + pageSize).coerceAtMost(traceFiles.size))

        Column(modifier = Modifier.fillMaxSize()) {
            val title = when {
                reportId != null -> "Report Traces"
                modelFilter != null -> "Traces — $modelFilter"
                else -> "API Traces"
            }
            TitleBar(title = title, onBackClick = onBack, onAiClick = onNavigateHome)

            // Category / Provider / Model selectors — each row is only
            // shown when there's something useful to pick. Clearing a
            // filter re-shows the broader scope.
            if (categories.size > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                FilterDropdown(
                    label = "Category",
                    value = selectedCategory,
                    options = categories,
                    onPick = { selectedCategory = it; currentPage = 0 }
                )
            }
            if (providers.size > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                FilterDropdown(
                    label = "Provider",
                    value = selectedProvider,
                    options = providers,
                    onPick = { selectedProvider = it; currentPage = 0 }
                )
            }
            if (pickableModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showModelPicker = true },
                        modifier = Modifier.weight(1f),
                        colors = AppColors.outlinedButtonColors(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Model: ${selectedModel ?: "(All)"}", fontSize = 12.sp,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("▸", fontSize = 12.sp, color = AppColors.TextTertiary)
                    }
                    if (selectedModel != null) {
                        TextButton(onClick = { selectedModel = null; currentPage = 0 }) {
                            Text("Clear", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            if (categories.size > 1 || providers.size > 1 || pickableModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (totalPages > 1) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { currentPage = (currentPage - 1).coerceAtLeast(0) }, enabled = currentPage > 0) { Text("< Prev", maxLines = 1, softWrap = false) }
                    Text("${currentPage + 1} / $totalPages (${traceFiles.size})", fontSize = 12.sp, color = AppColors.TextTertiary)
                    TextButton(onClick = { currentPage = (currentPage + 1).coerceAtMost(totalPages - 1) }, enabled = currentPage < totalPages - 1) { Text("Next >", maxLines = 1, softWrap = false) }
                }
            }

            // Table header
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Host", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
                    Text("Date/Time", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("Status", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(0.4f), textAlign = TextAlign.End)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(pageItems, key = { it.filename }) { trace ->
                    TraceListItem(trace = trace, onClick = { onSelectTrace(trace.filename) })
                }
            }

            if (reportId == null && modelFilter == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    onClearTraces(); allTraceFiles = emptyList(); currentPage = 0
                }, enabled = allTraceFiles.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear Traces", maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** Resolve a trace's hostname back to a known [AppService.displayName],
 *  or "(unknown)" when none of the registered services' baseUrls match.
 *  Used by the Provider filter and the Model picker so traces can be
 *  sliced by which provider produced them. */
private fun providerLabelForHost(host: String): String =
    AppService.entries.firstOrNull { svc ->
        runCatching { java.net.URI(svc.baseUrl).host }.getOrNull()
            ?.equals(host, ignoreCase = true) == true
    }?.displayName ?: "(unknown)"

/** Generic dropdown row used by Category and Provider — full-width
 *  outlined button labelled "<label>: <value>" with a downward chevron,
 *  expanding into a DropdownMenu of [options]. */
@Composable
private fun FilterDropdown(
    label: String,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("$label: $value", fontSize = 12.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾", fontSize = 12.sp, color = AppColors.TextTertiary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt, fontSize = 13.sp) },
                    onClick = { onPick(opt); expanded = false }
                )
            }
        }
    }
}

/** Full-screen overlay listing every (provider, model) pair that has
 *  at least one trace in the currently scoped subset. The user picks
 *  one to filter the trace list down to a single model, or "(All)" to
 *  clear the filter. */
@Composable
private fun TraceModelPickerOverlay(
    models: List<Pair<String, String>>,
    current: String?,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Pick model", onBackClick = onBack, onAiClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("(All models)", fontSize = 13.sp,
                    color = if (current == null) AppColors.Blue else Color.White,
                    modifier = Modifier.weight(1f))
                if (current == null) Text("✓", color = AppColors.Blue, fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(models, key = { "${it.first}::${it.second}" }) { (provider, model) ->
                val selected = current == model
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(model) }
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider, fontSize = 11.sp, color = AppColors.Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(model, fontSize = 13.sp,
                                color = if (selected) AppColors.Blue else Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (selected) Text("✓", color = AppColors.Blue, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceListItem(trace: TraceFileInfo, onClick: () -> Unit) {
    val dateFormat = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault()) }
    val statusColor = when {
        trace.statusCode in 200..299 -> AppColors.Green
        trace.statusCode in 400..499 -> AppColors.Orange
        trace.statusCode >= 500 -> AppColors.Red
        else -> AppColors.TextTertiary
    }

    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(trace.hostname, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(dateFormat.format(Instant.ofEpochMilli(trace.timestamp)), fontSize = 11.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("${trace.statusCode}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = statusColor, modifier = Modifier.weight(0.4f), textAlign = TextAlign.End)
        }
    }
}

// ===== Trace Detail =====

private enum class TraceContentView { ALL, REQ_HEADERS, RSP_HEADERS, REQ_DATA, RSP_DATA }

@Composable
fun TraceDetailScreen(
    filename: String,
    aiSettings: com.ai.model.Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onEditRequest: () -> Unit,
    onNavigateToProvider: (AppService) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToEditAgent: (String) -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var currentFilename by remember { mutableStateOf(filename) }
    var trace by remember { mutableStateOf<ApiTrace?>(null) }
    var rawJson by remember { mutableStateOf("") }
    var currentView by remember { mutableStateOf(TraceContentView.ALL) }

    var traceFiles by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        // Off the UI thread — same parse-every-JSON cost as the list screen.
        traceFiles = withContext(Dispatchers.IO) { ApiTracer.getTraceFiles().map { it.filename } }
    }
    val currentIndex = traceFiles.indexOf(currentFilename)
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex < traceFiles.size - 1 && currentIndex >= 0

    // Load trace data
    LaunchedEffect(currentFilename) {
        trace = ApiTracer.readTraceFile(currentFilename)
        rawJson = ApiTracer.readTraceFileRaw(currentFilename) ?: ""
    }

    val t = trace
    val statusCode = t?.response?.statusCode ?: 0
    val bgColor = if (statusCode >= 300) Color(0xFF4A1515) else MaterialTheme.colorScheme.background

    // Translation traces (category == "Translation") get an extra
    // "Translation result" button that opens a top/bottom split
    // showing the original prompt body and the model's translated
    // output. Extraction is best-effort across the three API
    // formats; null when either side can't be parsed (the button
    // hides itself in that case).
    val translationParts = remember(t?.category, t?.request?.body, t?.response?.body) {
        if (t?.category != "Translation") null
        else extractTranslationParts(t.request.body, t.response.body)
    }
    var showTranslationCompare by remember { mutableStateOf(false) }

    if (showTranslationCompare && translationParts != null) {
        com.ai.ui.report.TranslationCompareScreen(
            title = "Translation result",
            originalLabel = "Original",
            originalContent = translationParts.first,
            translatedLabel = "Translation",
            translatedContent = translationParts.second,
            onBack = { showTranslationCompare = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Parse JSON trees
    val requestTreeNodes = remember(t?.request?.body) { t?.request?.body?.let { parseJsonTree(it) } }
    val responseTreeNodes = remember(t?.response?.body) { t?.response?.body?.let { parseJsonTree(it) } }
    val allTreeNodes = remember(rawJson) { if (rawJson.isNotBlank()) parseJsonTree(rawJson) else null }

    // Build content for current view. The on-screen display always
    // shows the raw bytes (including secrets) — the Copy / Share
    // buttons run their own redaction pass before exporting.
    val displayContent = remember(t, currentView) {
        if (t == null) return@remember ""
        when (currentView) {
            TraceContentView.ALL -> ApiTracer.prettyPrintJson(rawJson)
            TraceContentView.REQ_HEADERS -> t.request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            TraceContentView.RSP_HEADERS -> t.response.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            TraceContentView.REQ_DATA -> ApiTracer.prettyPrintJson(t.request.body)
            TraceContentView.RSP_DATA -> ApiTracer.prettyPrintJson(t.response.body)
        }
    }

    // Parallel content used only by the Copy and Share buttons. Same
    // shape as displayContent but with sensitive headers / JSON keys /
    // URL query params replaced by "[REDACTED]".
    fun redactedContentFor(view: TraceContentView, trace: ApiTrace): String = when (view) {
        TraceContentView.ALL -> redactedTraceJson(trace)
        TraceContentView.REQ_HEADERS -> com.ai.ui.report.redactHeaders(trace.request.headers)
        TraceContentView.RSP_HEADERS -> com.ai.ui.report.redactHeaders(trace.response.headers)
        TraceContentView.REQ_DATA -> com.ai.ui.report.redactJsonString(trace.request.body)
            ?.let { ApiTracer.prettyPrintJson(it) } ?: ""
        TraceContentView.RSP_DATA -> com.ai.ui.report.redactJsonString(trace.response.body)
            ?.let { ApiTracer.prettyPrintJson(it) } ?: ""
    }

    Column(modifier = Modifier.fillMaxSize().background(bgColor).padding(16.dp)) {
        TitleBar(title = "Trace: $statusCode", onBackClick = onBack, onAiClick = onNavigateHome)

        // URL
        t?.request?.url?.let { url ->
            Text(url, fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        // Resolve provider from the request hostname so we can offer a Provider/Model/Agent
        // shortcut. Provider lookup matches AppService.baseUrl's host to t.hostname; model
        // comes from the captured trace; agent is matched by (provider, model) — if multiple
        // agents share the same pair, the first one wins.
        val provider = remember(t?.hostname) {
            t?.hostname?.let { host ->
                AppService.entries.firstOrNull { svc ->
                    runCatching { java.net.URI(svc.baseUrl).host }.getOrNull()?.equals(host, ignoreCase = true) == true
                }
            }
        }
        val matchingAgent = remember(provider, t?.model, aiSettings.agents) {
            if (provider == null || t?.model == null) null
            else aiSettings.agents.firstOrNull { it.provider.id == provider.id && it.model == t.model }
        }
        if (provider != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onNavigateToProvider(provider) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text("Provider", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                if (!t?.model.isNullOrBlank()) {
                    Button(onClick = { onNavigateToModelInfo(provider, t!!.model!!) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("Model", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                }
                if (matchingAgent != null) {
                    Button(onClick = { onNavigateToEditAgent(matchingAgent.id) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("Agent", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                }
            }
        }
        if (translationParts != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = { showTranslationCompare = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Translation result", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // View selector buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val views = listOf(
                TraceContentView.ALL to "All",
                TraceContentView.REQ_HEADERS to "Req Hdr",
                TraceContentView.RSP_HEADERS to "Rsp Hdr",
                TraceContentView.REQ_DATA to "Req",
                TraceContentView.RSP_DATA to "Rsp"
            )
            views.forEach { (view, label) ->
                val isActive = currentView == view
                OutlinedButton(onClick = { currentView = view }, modifier = Modifier.weight(1f),
                    colors = if (isActive) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF3366BB).copy(alpha = 0.3f)) else ButtonDefaults.outlinedButtonColors(),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                ) { Text(label, fontSize = 10.sp, maxLines = 1, softWrap = false) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content area — render the colored JSON tree whenever the current view's payload is
        // valid JSON (the whole trace file always is, so ALL gets the tree too).
        val useTree = (currentView == TraceContentView.ALL && allTreeNodes != null) ||
            (currentView == TraceContentView.REQ_DATA && requestTreeNodes != null) ||
            (currentView == TraceContentView.RSP_DATA && responseTreeNodes != null)
        val treeNodes = when (currentView) {
            TraceContentView.ALL -> allTreeNodes
            TraceContentView.REQ_DATA -> requestTreeNodes
            TraceContentView.RSP_DATA -> responseTreeNodes
            else -> null
        }

        val headerEntries: List<Pair<String, String>>? = when (currentView) {
            TraceContentView.REQ_HEADERS -> t?.request?.headers?.entries?.map { it.key to it.value }
            TraceContentView.RSP_HEADERS -> t?.response?.headers?.entries?.map { it.key to it.value }
            else -> null
        }

        Box(modifier = Modifier.weight(1f)) {
            if (useTree && treeNodes != null) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(treeNodes.size) { index ->
                        JsonTreeNodeView(node = treeNodes[index], depth = 0)
                    }
                }
            } else if (headerEntries != null) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(headerEntries.size) { index ->
                        val (name, value) = headerEntries[index]
                        Row(modifier = Modifier.padding(vertical = 1.dp)) {
                            Text("$name: ", fontSize = 11.sp, color = AppColors.Blue, fontFamily = FontFamily.Monospace)
                            Text(value, fontSize = 11.sp, color = Color(0xFF6A8759), fontFamily = FontFamily.Monospace,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                LazyColumn {
                    val lines = displayContent.lines()
                    items(lines.size) { index ->
                        Text(lines[index], fontSize = 11.sp, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom buttons: navigation + actions
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                if (hasPrev) { currentFilename = traceFiles[currentIndex - 1]; currentView = TraceContentView.ALL }
            }, enabled = hasPrev, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(36.dp).semantics { contentDescription = "Previous trace" }, colors = AppColors.outlinedButtonColors()
            ) { Text("<", fontSize = 14.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = {
                val toCopy = t?.let { redactedContentFor(currentView, it) } ?: displayContent
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("trace", toCopy))
                Toast.makeText(context, "Copied (redacted)", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Copy", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = {
                // Save provider/model/key to prefs for EditApiRequestScreen
                t?.let { trace ->
                        val prefs = context.getSharedPreferences("eval_prefs", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            putString("last_test_raw_json", trace.request.body)
                            putString("last_test_api_url", trace.request.url)
                            putString("last_test_model", trace.model ?: "")
                        }.apply()
                    }
                onEditRequest()
            }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Edit", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = {
                val toShare = t?.let { redactedTraceJson(it) } ?: rawJson
                shareTrace(context, toShare, currentFilename)
            }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Share", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = {
                if (hasNext) { currentFilename = traceFiles[currentIndex + 1]; currentView = TraceContentView.ALL }
            }, enabled = hasNext, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(36.dp).semantics { contentDescription = "Next trace" }, colors = AppColors.outlinedButtonColors()
            ) { Text(">", fontSize = 14.sp, maxLines = 1, softWrap = false) }
        }
    }
}

/** Build a copy of the trace's raw JSON with API keys / tokens /
 *  cookies replaced by "[REDACTED]" — used by Copy and Share so the
 *  bytes that leave the device are scrubbed, while the on-disk file
 *  and the in-app display stay raw. Redacts:
 *   - request URL query params (key, api_key, apikey, access_token, token)
 *   - request and response sensitive headers (Authorization, X-API-Key, …)
 *   - sensitive JSON keys in both bodies (api_key, token, secret, …)
 */
private fun redactedTraceJson(trace: ApiTrace): String {
    val redactedTrace = trace.copy(
        request = trace.request.copy(
            url = redactSecretsInUrl(trace.request.url),
            headers = redactHeaderMap(trace.request.headers),
            body = trace.request.body?.let { com.ai.ui.report.redactJsonString(it) }
        ),
        response = trace.response.copy(
            headers = redactHeaderMap(trace.response.headers),
            body = trace.response.body?.let { com.ai.ui.report.redactJsonString(it) }
        )
    )
    return com.ai.data.createAppGson(prettyPrint = true).toJson(redactedTrace)
}

private fun redactHeaderMap(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (name, value) ->
        if (name.lowercase(Locale.US) in com.ai.ui.report.SENSITIVE_HEADERS) com.ai.ui.report.REDACTED else value
    }

/** Strip API-key-bearing query params from a request URL. Gemini's
 *  endpoint embeds the key as `?key=…`; some other providers accept
 *  `api_key=` / `access_token=` / `token=`. */
private fun redactSecretsInUrl(url: String): String {
    val pattern = Regex("([?&])(key|api[_-]?key|access[_-]?token|token)=[^&]*", RegexOption.IGNORE_CASE)
    return pattern.replace(url) { m -> "${m.groupValues[1]}${m.groupValues[2]}=${com.ai.ui.report.REDACTED}" }
}

private fun shareTrace(context: Context, content: String, filename: String) {
    try {
        val dir = File(context.cacheDir, "shared_traces").also { it.mkdirs() }
        val file = File(dir, filename)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "Share Trace"))
    } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
}

// ===== JSON Tree View =====

private enum class JsonNodeType { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

private data class JsonTreeNode(val key: String?, val type: JsonNodeType, val value: String? = null, val children: List<JsonTreeNode> = emptyList())

private fun parseJsonTree(json: String): List<JsonTreeNode>? {
    return try {
        @Suppress("DEPRECATION")
        val element = JsonParser().parse(json)
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().map { parseJsonElement(it.key, it.value) }
            element.isJsonArray -> element.asJsonArray.toList().mapIndexed { i: Int, el: JsonElement -> parseJsonElement("[$i]", el) }
            else -> null
        }
    } catch (_: Exception) { null }
}

private fun parseJsonElement(key: String?, element: JsonElement): JsonTreeNode {
    return when {
        element.isJsonObject -> JsonTreeNode(key, JsonNodeType.OBJECT, children = element.asJsonObject.entrySet().map { parseJsonElement(it.key, it.value) })
        element.isJsonArray -> JsonTreeNode(key, JsonNodeType.ARRAY, children = element.asJsonArray.mapIndexed { i, el -> parseJsonElement("[$i]", el) })
        element.isJsonPrimitive -> {
            val p = element.asJsonPrimitive
            when {
                p.isString -> JsonTreeNode(key, JsonNodeType.STRING, "\"${p.asString}\"")
                p.isNumber -> JsonTreeNode(key, JsonNodeType.NUMBER, p.asString)
                p.isBoolean -> JsonTreeNode(key, JsonNodeType.BOOLEAN, p.asString)
                else -> JsonTreeNode(key, JsonNodeType.NULL, "null")
            }
        }
        element.isJsonNull -> JsonTreeNode(key, JsonNodeType.NULL, "null")
        else -> JsonTreeNode(key, JsonNodeType.NULL, "null")
    }
}

@Composable
private fun JsonTreeNodeView(node: JsonTreeNode, depth: Int) {
    var expanded by remember { mutableStateOf(depth < 2) }
    val indent = (depth * 16).dp
    val hasChildren = node.children.isNotEmpty()

    Row(modifier = Modifier.fillMaxWidth().padding(start = indent).then(if (hasChildren) Modifier.clickable { expanded = !expanded } else Modifier).padding(vertical = 2.dp)) {
        if (hasChildren) {
            Text(if (expanded) "▾ " else "▸ ", fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }

        if (node.key != null) {
            Text("${node.key}: ", fontSize = 11.sp, color = AppColors.Blue, fontFamily = FontFamily.Monospace)
        }

        when {
            hasChildren -> {
                val bracket = if (node.type == JsonNodeType.OBJECT) "{${node.children.size}}" else "[${node.children.size}]"
                Text(bracket, fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
            }
            else -> {
                val valueColor = when (node.type) {
                    JsonNodeType.STRING -> Color(0xFF6A8759) // green
                    JsonNodeType.NUMBER -> AppColors.Orange
                    JsonNodeType.BOOLEAN -> AppColors.Purple
                    JsonNodeType.NULL -> AppColors.TextDim
                    else -> Color.White
                }
                Text(node.value ?: "", fontSize = 11.sp, color = valueColor, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (hasChildren && expanded) {
        node.children.forEach { child -> JsonTreeNodeView(node = child, depth = depth + 1) }
    }
}

// ===== Translation extraction =====

/** Best-effort extraction of (original, translation) from a translation
 *  trace's request + response bodies. The translation prompt always
 *  embeds the source text after the literal "TEXT TO TRANSLATE:"
 *  marker; the response carries the model's reply in a format-specific
 *  shape. Returns null when either side can't be parsed — the caller
 *  hides the "Translation result" button in that case. */
private fun extractTranslationParts(requestBody: String?, responseBody: String?): Pair<String, String>? {
    if (requestBody.isNullOrBlank() || responseBody.isNullOrBlank()) return null
    val userPrompt = extractUserPrompt(requestBody) ?: return null
    val marker = "TEXT TO TRANSLATE:"
    val idx = userPrompt.indexOf(marker)
    val original = (if (idx < 0) userPrompt else userPrompt.substring(idx + marker.length)).trim()
    if (original.isBlank()) return null
    val translation = extractAssistantContent(responseBody)?.trim() ?: return null
    if (translation.isBlank()) return null
    return original to translation
}

/** Walk the request JSON looking for the user's prompt text. Tries
 *  three shapes: OpenAI-compatible / Anthropic messages[] arrays
 *  (with content as either a plain string or a content-parts array)
 *  and Gemini's contents[].parts[].text. */
private fun extractUserPrompt(json: String): String? {
    return try {
        @Suppress("DEPRECATION")
        val root = JsonParser().parse(json)
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        var found: String? = null
        // OpenAI / Anthropic messages array — last user message wins.
        obj.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
            for (i in arr.size() - 1 downTo 0) {
                val el = arr[i]
                if (!el.isJsonObject) continue
                val m = el.asJsonObject
                if (m.get("role")?.takeIf { it.isJsonPrimitive }?.asString != "user") continue
                val content = m.get("content") ?: continue
                if (content.isJsonPrimitive) { found = content.asString; break }
                if (content.isJsonArray) {
                    for (part in content.asJsonArray) {
                        if (!part.isJsonObject) continue
                        val text = part.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                        if (text != null) { found = text; break }
                    }
                    if (found != null) break
                }
            }
        }
        if (found != null) return found
        // Gemini contents array.
        obj.get("contents")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
            for (i in arr.size() - 1 downTo 0) {
                val el = arr[i]
                if (!el.isJsonObject) continue
                val m = el.asJsonObject
                val role = m.get("role")?.takeIf { it.isJsonPrimitive }?.asString
                if (role != null && role != "user") continue
                val parts = m.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                for (p in parts) {
                    if (!p.isJsonObject) continue
                    val text = p.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                    if (text != null) { found = text; break }
                }
                if (found != null) break
            }
        }
        found
    } catch (_: Exception) { null }
}

/** Walk the response JSON looking for the assistant's reply text.
 *  Tries OpenAI choices[0].message.content, Anthropic content[].text,
 *  Gemini candidates[0].content.parts[0].text — the first non-blank
 *  text wins. */
private fun extractAssistantContent(json: String): String? {
    return try {
        @Suppress("DEPRECATION")
        val root = JsonParser().parse(json)
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        // OpenAI: choices[0].message.content
        obj.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull { it.isJsonObject }
            ?.asJsonObject?.get("message")
            ?.takeIf { it.isJsonObject }?.asJsonObject?.get("content")
            ?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // Anthropic: content[].text — pick the first non-blank text.
        obj.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
            for (el in arr) {
                if (!el.isJsonObject) continue
                val text = el.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                if (!text.isNullOrBlank()) return text
            }
        }
        // Gemini: candidates[0].content.parts[].text
        obj.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull { it.isJsonObject }
            ?.asJsonObject?.get("content")
            ?.takeIf { it.isJsonObject }?.asJsonObject?.get("parts")
            ?.takeIf { it.isJsonArray }?.asJsonArray?.let { parts ->
                for (p in parts) {
                    if (!p.isJsonObject) continue
                    val text = p.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!text.isNullOrBlank()) return text
                }
            }
        null
    } catch (_: Exception) { null }
}
