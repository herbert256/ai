package com.ai.ui.admin

import android.content.Context
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    aiSettings: com.ai.model.Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectTrace: (String) -> Unit,
    onClearTraces: () -> Unit,
    reportId: String? = null,
    modelFilter: String? = null,
    /** Initial category-dropdown selection. The user can still
     *  change it via the dropdown; this just biases the first
     *  render so a caller can land on a pre-filtered slice (the
     *  Translation run's Trace button opens the list pre-filtered
     *  to category="Translation"). */
    initialCategory: String? = null,
    /** Run-id filter. When set, the list is scoped to traces that
     *  carry this runId — every API call produced by one user-
     *  launched batch (fan-out, fan-icons, translation, model-test,
     *  report). Wired by the L1 🐞 icon on those screens. */
    runIdFilter: String? = null
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    // Resolve the per-report icon when this list is scoped to a
    // report — the trace-list screen is reached via Compose
    // Navigation, so it sits outside the LocalReportIcon provider
    // ReportsScreenNav installs for its inline overlays. Without
    // this lookup the report-icon slot on the top bar stays empty.
    val resolvedReportIcon by produceState<String?>(initialValue = null, reportId) {
        val rid = reportId ?: return@produceState
        value = withContext(Dispatchers.IO) {
            com.ai.data.ReportStorage.getReport(context, rid)?.icon?.takeIf { it.isNotBlank() }
                ?: "📝"
        }
    }
    // First call of the session populates ApiTracer's cache via a
    // streaming parse over the trace dir; subsequent calls are O(1) off
    // the cache. Kept on Dispatchers.IO so the cold load can't stall
    // the first open.
    var allTraceFiles by remember { mutableStateOf(emptyList<TraceFileInfo>()) }
    LaunchedEffect(reportId, modelFilter, runIdFilter) {
        allTraceFiles = withContext(Dispatchers.IO) {
            when {
                runIdFilter != null -> ApiTracer.getTraceFilesForRun(runIdFilter)
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
    var selectedCategory by rememberSaveable { mutableStateOf(initialCategory ?: "(All)") }

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

    // Hostname distinct list — raw host strings as recorded on the
    // trace. Useful when one provider (e.g. Cohere) ships traffic on
    // multiple hosts and the user wants to slice by which.
    val hostnames = remember(allTraceFiles) {
        listOf("(All)") + allTraceFiles.map { it.hostname }.distinct().sorted()
    }
    var selectedHostname by rememberSaveable { mutableStateOf("(All)") }

    // Model filter — chosen via the Model picker overlay. null = no filter.
    var selectedModel by rememberSaveable { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    // Errors-only toggle — narrows the list to traces with a non-2xx
    // response (HTTP 4xx/5xx and the rare 0 we record on transport-
    // level failures). Off by default; the user opts in to triage a
    // failed run.
    var errorsOnly by rememberSaveable { mutableStateOf(false) }

    val traceFiles = remember(allTraceFiles, selectedCategory, selectedProvider, selectedHostname, selectedModel, errorsOnly) {
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
            .filter { t -> when (selectedHostname) {
                "(All)" -> true
                else -> t.hostname == selectedHostname
            } }
            .filter { t -> selectedModel == null || t.model == selectedModel }
            .filter { t -> !errorsOnly || t.statusCode == 0 || t.statusCode >= 400 }
    }
    // Auto-collapse: when a 🐞 link lands here with preset filters
    // (reportId / category / model) and the resulting list has exactly
    // one entry, jump straight into that trace's detail view — saves
    // the user a redundant tap on a list of one. The rememberSaveable
    // flag survives the back-pop from the detail, so popping back drops
    // the user on the list (with its one entry) rather than re-firing
    // the auto-navigation. Only triggers on the initial load:
    // narrowing manually to one row via the filter dropdowns is a
    // user-driven slice and they may want to keep tweaking.
    var autoSelected by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(allTraceFiles, traceFiles) {
        if (!autoSelected && allTraceFiles.isNotEmpty() && traceFiles.size == 1) {
            autoSelected = true
            onSelectTrace(traceFiles[0].filename)
        }
    }
    val errorCount = remember(allTraceFiles) {
        allTraceFiles.count { it.statusCode == 0 || it.statusCode >= 400 }
    }
    var currentPage by rememberSaveable { mutableIntStateOf(0) }

    // Models with traces in the currently scoped (Category + Provider
    // + Hostname) subset. The picker shows only these so the user
    // can't pick one that yields zero rows.
    val pickableModels = remember(allTraceFiles, selectedCategory, selectedProvider, selectedHostname) {
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
            .filter { t -> when (selectedHostname) {
                "(All)" -> true
                else -> t.hostname == selectedHostname
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
            aiSettings = aiSettings,
            models = pickableModels,
            current = selectedModel,
            onSelect = { sel -> selectedModel = sel; showModelPicker = false; currentPage = 0 },
            onBack = { showModelPicker = false }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        val rowHeight = 52
        val overhead = 130
        val pageSize = maxOf(1, ((maxHeight.value - overhead) / rowHeight).toInt())
        val totalPages = if (traceFiles.isEmpty()) 1 else (traceFiles.size + pageSize - 1) / pageSize

        LaunchedEffect(totalPages) { if (currentPage >= totalPages) currentPage = (totalPages - 1).coerceAtLeast(0) }

        val startIndex = currentPage * pageSize
        val pageItems = traceFiles.subList(startIndex.coerceAtMost(traceFiles.size), (startIndex + pageSize).coerceAtMost(traceFiles.size))

        var confirmClearAll by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxSize()) {
            val subHeader = when {
                runIdFilter != null -> "Run scope"
                reportId != null -> "Report scope"
                modelFilter != null -> modelFilter
                else -> ""
            }
            val canClear = reportId == null && modelFilter == null && runIdFilter == null && allTraceFiles.isNotEmpty()
            TitleBar(
                helpTopic = "trace_list",
                title = "API Traces",
                subject = subHeader,
                reportIcon = resolvedReportIcon,
                onBackClick = onBack,
                onDelete = if (canClear) { { confirmClearAll = true } } else null
            )
            com.ai.ui.shared.HardcodedSubjectRow(subHeader)

            // Category / Provider / Hostname / Model selectors share
            // a single row. Each slot is only emitted when there's
            // something useful to pick, with the surviving slots
            // taking equal share via weight(1f). Model's "(All)" lives
            // inside its picker overlay so a separate Clear button is
            // unnecessary.
            val showCategory = categories.size > 1
            val showProvider = providers.size > 1
            val showHostname = hostnames.size > 2 // "(All)" + at least 2 distinct hosts
            val showModel = pickableModels.isNotEmpty()
            if (showCategory || showProvider || showHostname || showModel) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showCategory) {
                        FilterDropdown(
                            label = "Category",
                            value = selectedCategory,
                            options = categories,
                            onPick = { selectedCategory = it; currentPage = 0 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (showHostname) {
                        FilterDropdown(
                            label = "Hostname",
                            value = selectedHostname,
                            options = hostnames,
                            onPick = { selectedHostname = it; currentPage = 0 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (showProvider) {
                        FilterDropdown(
                            label = "Provider",
                            value = selectedProvider,
                            options = providers,
                            onPick = { selectedProvider = it; currentPage = 0 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (showModel) {
                        FilterLauncherButton(
                            label = "Model",
                            value = selectedModel ?: "(All)",
                            onClick = { showModelPicker = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (errorCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { errorsOnly = !errorsOnly; currentPage = 0 },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = errorsOnly,
                        onCheckedChange = { errorsOnly = it; currentPage = 0 }
                    )
                    Text(
                        "Only errors ($errorCount)",
                        fontSize = 12.sp, color = AppColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (categories.size > 1 || providers.size > 1 || pickableModels.isNotEmpty() || errorCount > 0) {
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

        }

        if (confirmClearAll) {
            AlertDialog(
                onDismissRequest = { confirmClearAll = false },
                title = { Text("Clear all traces?") },
                text = { Text("Permanently deletes ${allTraceFiles.size} captured trace file(s) from disk.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClearAll = false
                        onClearTraces(); allTraceFiles = emptyList(); currentPage = 0
                    }) { Text("Clear", color = AppColors.Red, maxLines = 1, softWrap = false) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearAll = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
                }
            )
        }
    }
}

/** Resolve a trace's hostname back to a known provider id, or
 *  "(unknown)" when neither the service's baseUrl host nor any of
 *  its declared [AppService.auxHosts] matches. Used by the Provider
 *  filter and the Model picker so traces can be sliced by which
 *  provider produced them. Aux hosts cover providers like Cohere
 *  whose OpenAI-compat shim lives on one host but whose native
 *  rerank / capability endpoints live on another. */
private fun providerLabelForHost(host: String): String =
    AppService.entries.firstOrNull { svc ->
        val baseHost = runCatching { java.net.URI(svc.baseUrl).host }.getOrNull()
        baseHost?.equals(host, ignoreCase = true) == true ||
            svc.auxHosts.any { it.equals(host, ignoreCase = true) }
    }?.id ?: "(unknown)"

/** Generic dropdown slot used by Category, Provider and the Model
 *  launcher — outlined button labelled "<label>: <value>" with a
 *  downward chevron, expanding into a DropdownMenu of [options].
 *  Sized by the caller's [modifier] so the three filters can share
 *  one row via weight(1f). */
@Composable
private fun FilterDropdown(
    label: String,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Default ("(All)") is implicit — show just the label so the
            // button reads "Category" rather than "Category: (All)".
            // Active filter shows "<label>: <value>".
            val display = if (value == "(All)") label else "$label: $value"
            Text(display, fontSize = 11.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾", fontSize = 11.sp, color = AppColors.TextTertiary)
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

/** Same outlined-button shape as [FilterDropdown] but the chevron
 *  hands off to an external picker overlay rather than a built-in
 *  DropdownMenu — used for Model where the option list is too long
 *  to live in a popup menu. The "(All)" option lives in the
 *  picker itself, so a separate Clear button is unnecessary. */
@Composable
private fun FilterLauncherButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = AppColors.outlinedButtonColors(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // Match FilterDropdown: hide the "(All)" sentinel on the button
        // — defaulting to All is implicit, only show "<label>: <value>"
        // when something specific is picked.
        val display = if (value == "(All)") label else "$label: $value"
        Text(display, fontSize = 11.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("▸", fontSize = 11.sp, color = AppColors.TextTertiary)
    }
}

/** Full-screen overlay listing every (provider, model) pair that has
 *  at least one trace in the currently scoped subset. The user picks
 *  one to filter the trace list down to a single model, or "(All)" to
 *  clear the filter. */
@Composable
private fun TraceModelPickerOverlay(
    aiSettings: com.ai.model.Settings,
    models: List<Pair<String, String>>,
    current: String?,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val advisory = com.ai.ui.shared.rememberModelAdvisoryLookup(aiSettings)
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "trace_pick_model", title = "Pick model", onBackClick = onBack)
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
                val state = advisory.stateFor(provider, model)
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(model) }
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).alpha(state.rowAlpha)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(provider, fontSize = 11.sp, color = AppColors.Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                com.ai.ui.shared.ModelAdvisoryBadges(state)
                            }
                            Text(com.ai.ui.shared.shortModelName(model), fontSize = 13.sp,
                                color = if (selected) AppColors.Blue else Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            com.ai.ui.shared.ModelAdvisoryCaptions(state)
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

private enum class TraceContentView { META, GET, REQ_HEADERS, RSP_HEADERS, REQ_DATA, RSP_DATA }
/** How the selected view's content is rendered:
 *  PARSED — JSON tree / key-value rows (the structured view);
 *  PRETTY — pretty-printed JSON text;
 *  RAW    — the bytes exactly as captured, no processing. */
private enum class TraceContentMode { PARSED, PRETTY, RAW }

@Composable
fun TraceDetailScreen(
    filename: String,
    aiSettings: com.ai.model.Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onEditRequest: () -> Unit,
    onNavigateToProvider: (AppService) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToEditAgent: (String) -> Unit = {},
    onNavigateToHelpTopic: (String) -> Unit = {},
    /** Navigate to the AI Report this trace belongs to. Wired only
     *  when the trace carries a non-null reportId (the bottom-bar 📝
     *  button is hidden otherwise). The host is responsible for
     *  restoring the report into ReportViewModel before navigating. */
    onOpenReport: (String) -> Unit = {},
    /** Per-row 👁 View icon target — opens the report at the View tile
     *  grid instead of Manage. Same restore + navigate path as
     *  [onOpenReport] but with the AI_REPORTS route's `initialView=true`
     *  query-param appended. */
    onOpenReportView: (String) -> Unit = onOpenReport
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var currentFilename by remember { mutableStateOf(filename) }
    var trace by remember { mutableStateOf<ApiTrace?>(null) }
    // Trace detail opens on the response body — the thing you most
    // often want to inspect.
    var currentView by remember { mutableStateOf(TraceContentView.RSP_DATA) }
    var currentMode by remember { mutableStateOf(TraceContentMode.PARSED) }

    var traceFiles by remember { mutableStateOf(emptyList<String>()) }
    // Re-fetch on every ON_RESUME so prev/next survives traces being
    // cleared / trimmed in a sibling screen (e.g. Trim by age) while
    // this detail view stays in the back stack.
    val traceListRefresh = com.ai.ui.shared.resumeRefreshTick()
    LaunchedEffect(traceListRefresh) {
        // Cheap once the list screen has primed ApiTracer's cache; cold path falls back
        // to a streaming-parse scan, so still off the UI thread to be safe.
        traceFiles = withContext(Dispatchers.IO) { ApiTracer.getTraceFiles().map { it.filename } }
    }
    val currentIndex = traceFiles.indexOf(currentFilename)
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex < traceFiles.size - 1 && currentIndex >= 0

    // Load trace data
    LaunchedEffect(currentFilename) {
        trace = ApiTracer.readTraceFile(currentFilename)
    }

    // Resolve the AI Report this trace belongs to (if any), so the
    // title bar can paint that report's AI-generated icon and the
    // bottom bar can offer a 📝 jump-back button. Off-thread because
    // ReportStorage.getReport parses the report JSON which can be
    // multi-MB for image-heavy reports.
    val reportIdForTrace = trace?.reportId
    val reportForTrace by produceState<com.ai.data.Report?>(initialValue = null, reportIdForTrace) {
        val rid = reportIdForTrace
        value = if (rid != null) withContext(Dispatchers.IO) { com.ai.data.ReportStorage.getReport(context, rid) } else null
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
        com.ai.ui.report.view.TranslationCompareScreen(
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

    // Parse JSON trees for the request / response bodies.
    val requestTreeNodes = remember(t?.request?.body) { t?.request?.body?.let { parseJsonTree(it) } }
    val responseTreeNodes = remember(t?.response?.body) { t?.response?.body?.let { parseJsonTree(it) } }

    // Split the request URL into (path-without-query, list of (k, v))
    // so the title-line URL never leaks query params (some carry API
    // keys) and the new "Get" view can render the params separately.
    val urlParts = remember(t?.request?.url) {
        val raw = t?.request?.url ?: return@remember "" to emptyList<Pair<String, String>>()
        val q = raw.indexOf('?')
        if (q < 0) raw to emptyList()
        else {
            val base = raw.substring(0, q)
            val params = raw.substring(q + 1).split('&').mapNotNull { pair ->
                if (pair.isBlank()) null
                else {
                    val eq = pair.indexOf('=')
                    if (eq < 0) pair to ""
                    else pair.substring(0, eq) to pair.substring(eq + 1)
                }
            }
            base to params
        }
    }
    val baseUrl = urlParts.first
    val queryParams = urlParts.second

    // "Meta" view — trace envelope fields only, excluding anything
    // already on the other buttons (URL/query → Get, headers → *Hdr,
    // bodies → Req/Rsp). Rendered as key/value rows in PARSED mode.
    val metaEntries: List<Pair<String, String>> = remember(t) {
        val tr = t ?: return@remember emptyList()
        buildList {
            add("Status" to tr.response.statusCode.toString())
            add("Method" to tr.request.method)
            add("Host" to tr.hostname)
            add("Timestamp" to java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()
            ).format(java.util.Date(tr.timestamp)))
            tr.model?.takeIf { it.isNotBlank() }?.let { add("Model" to it) }
            tr.category?.takeIf { it.isNotBlank() }?.let { add("Category" to it) }
            tr.reportId?.takeIf { it.isNotBlank() }?.let { add("Report ID" to it) }
            if (tr.partial) add("Partial" to "true")
        }
    }

    // Raw bytes / key-value text per view, BEFORE pretty / parse.
    fun rawForView(view: TraceContentView): String = when (view) {
        TraceContentView.META -> metaEntries.joinToString("\n") { "${it.first}: ${it.second}" }
        TraceContentView.GET -> queryParams.joinToString("\n") { "${it.first}: ${it.second}" }
        TraceContentView.REQ_HEADERS -> t?.request?.headers?.entries
            ?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
        TraceContentView.RSP_HEADERS -> t?.response?.headers?.entries
            ?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
        TraceContentView.REQ_DATA -> t?.request?.body ?: ""
        TraceContentView.RSP_DATA -> t?.response?.body ?: ""
    }

    // Text shown in PRETTY / RAW modes (PARSED renders the tree / kv
    // rows instead). The on-screen display shows the raw bytes
    // (including secrets) — Copy / Share redact separately below.
    val displayContent = remember(t, currentView, currentMode, queryParams, metaEntries) {
        if (t == null) return@remember ""
        val raw = rawForView(currentView)
        if (currentMode == TraceContentMode.RAW) raw else ApiTracer.prettyPrintJson(raw)
    }

    // Parallel content used only by the Copy and Share buttons —
    // displayContent with sensitive headers / JSON keys / query
    // params replaced by "[REDACTED]". Meta carries no secrets.
    fun redactedContentFor(view: TraceContentView, trace: ApiTrace): String = when (view) {
        TraceContentView.META -> metaEntries.joinToString("\n") { "${it.first}: ${it.second}" }
        TraceContentView.GET -> queryParams.joinToString("\n") { (k, _) -> "$k: [REDACTED]" }
        TraceContentView.REQ_HEADERS -> com.ai.ui.helpers.redactHeaders(trace.request.headers)
        TraceContentView.RSP_HEADERS -> com.ai.ui.helpers.redactHeaders(trace.response.headers)
        TraceContentView.REQ_DATA -> com.ai.ui.helpers.redactJsonString(trace.request.body)
            ?.let { ApiTracer.prettyPrintJson(it) } ?: ""
        TraceContentView.RSP_DATA -> com.ai.ui.helpers.redactJsonString(trace.response.body)
            ?.let { ApiTracer.prettyPrintJson(it) } ?: ""
    }


    val infoTraceModel = t?.model?.takeIf { it.isNotBlank() }
    val infoTraceHost = t?.hostname?.takeIf { it.isNotBlank() }
    val infoProvider = remember(infoTraceHost) {
        infoTraceHost?.let { host ->
            AppService.entries.firstOrNull { svc ->
                runCatching { java.net.URI(svc.baseUrl).host }.getOrNull()?.equals(host, ignoreCase = true) == true
            }
        }
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this trace?") },
            text = { Text("Permanently removes the trace file from disk. Cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        if (ApiTracer.deleteTrace(currentFilename)) {
                            Toast.makeText(context, "Trace deleted", Toast.LENGTH_SHORT).show()
                            onBack()
                        } else {
                            Toast.makeText(context, "Could not delete trace", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Delete", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    // ℹ️ Info target. Order:
    //  1. The trace's URL maps to one of the seven info providers
    //     (LiteLLM / models.dev / Helicone / llm-prices / AA /
    //     OpenRouter pricing fetch / HuggingFace) → open that
    //     provider's help topic. The resolver gates the
    //     dual-purpose host (OpenRouter) by category so chat-completion
    //     traces don't hijack the icon.
    //  2. The trace has a captured model + an AppService match → open
    //     Model Info for that pair.
    //  3. The trace has only an AppService match (e.g. /v1/models
    //     list call) → open the Provider edit screen.
    //  4. None of the above → no ℹ️ icon.
    val infoProviderHelp = remember(t?.request?.url, t?.category) {
        com.ai.ui.admin.infoProviderForTrace(t?.request?.url, t?.category)
    }
    val onInfoAction: (() -> Unit)? = run {
        val help = infoProviderHelp
        val p = infoProvider
        val m = infoTraceModel
        when {
            help != null -> ({ onNavigateToHelpTopic(help.topicId) })
            p != null && m != null -> ({ onNavigateToModelInfo(p, m) })
            p != null -> ({ onNavigateToProvider(p) })
            else -> null
        }
    }

    // Wire the title-bar report-icon tap to the same destination as
    // the bottom-row 📝 button (restore the report into UiState, then
    // navigate to AI_REPORTS). Without this wrap the trace-detail
    // route renders at the top-level NavHost scope, where
    // LocalNavigateToCurrentReport defaults to null and the icon tap
    // is a no-op. Only wrap when we actually know the report id —
    // hides the icon below also gate on the same field, so the
    // provider would be unused otherwise.
    val tracedReportId = t?.reportId
    val reportIconNav: () -> Unit = remember(tracedReportId) {
        { tracedReportId?.let(onOpenReport) }
    }
    CompositionLocalProvider(
        com.ai.ui.shared.LocalNavigateToCurrentReport provides reportIconNav
    ) {
    Column(modifier = Modifier.fillMaxSize().background(bgColor).padding(16.dp)) {
        TitleBar(
            helpTopic = "trace_detail",
            title = "Trace detail", onBackClick = onBack,
            // When the trace belongs to a report, paint that report's
            // AI-generated icon (the "retrieved" icon) as the leftmost
            // glyph. Null when no icon has been generated yet OR the
            // trace isn't report-scoped — the slot is hidden in both
            // cases (the bottom-bar 📝 is the persistent indicator).
            reportIcon = reportForTrace?.icon?.takeIf { it.isNotBlank() },
            onInfo = onInfoAction,
            // 🗑: confirm + delete this trace file, then pop back.
            onDelete = if (t != null) { { showDeleteConfirm = true } } else null,
            // 🔄: stage this trace's request into the API Test edit
            // screen so the user can re-fire (and edit on the way).
            // Same plumbing the bottom-row Edit button uses.
            onReload = if (t != null) {
                {
                    val prefs = context.getSharedPreferences("eval_prefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putString("last_test_raw_json", t.request.body)
                        putString("last_test_api_url", com.ai.ui.helpers.redactUrl(t.request.url))
                        putString("last_test_model", t.model ?: "")
                    }.apply()
                    onEditRequest()
                }
            } else null,
            // 📋: copy the active tab's redacted bytes — same payload
            // the body-row Copy button puts on the clipboard, so the
            // two affordances stay byte-identical.
            onCopy = if (t != null) {
                { com.ai.ui.shared.copyToClipboard(context, redactedContentFor(currentView, t), "trace") }
            } else null,
            // 📤: share the same redacted bytes via Android share
            // sheet. Preserves the API-key / Authorization / cookie
            // redaction guarantee.
            onShare = if (t != null) {
                { com.ai.ui.shared.shareText(context, redactedContentFor(currentView, t), "Trace ${t.response.statusCode}") }
            } else null
        )
        // First line: HTTP status code + path (no query params — they
        // can leak API keys and live in the dedicated Get view below).
        Text(
            text = "$statusCode - ${baseUrl.ifBlank { t?.hostname ?: "(unknown)" }}",
            fontSize = 14.sp, color = AppColors.Green,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        // Per-trace deep-link: matching Agent only (Provider is
        // reachable via the title-bar ℹ️ icon — directly when no
        // model, or via Model Info → Provider when a model exists).
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
        if (matchingAgent != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Button(onClick = { onNavigateToEditAgent(matchingAgent.id) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Agent", fontSize = 11.sp, maxLines = 1, softWrap = false) }
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

        // View selector buttons. The "Get" button only appears when
        // the request URL carried query parameters — sandwiched
        // between Meta and Req Hdr so the order tracks request
        // lifecycle.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val views = buildList {
                add(TraceContentView.META to "Meta")
                if (queryParams.isNotEmpty()) add(TraceContentView.GET to "Get")
                add(TraceContentView.REQ_HEADERS to "Req Hdr")
                add(TraceContentView.RSP_HEADERS to "Rsp Hdr")
                add(TraceContentView.REQ_DATA to "Req")
                add(TraceContentView.RSP_DATA to "Rsp")
            }
            views.forEach { (view, label) ->
                val isActive = currentView == view
                OutlinedButton(onClick = { currentView = view }, modifier = Modifier.weight(1f),
                    colors = if (isActive) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF3366BB).copy(alpha = 0.3f)) else ButtonDefaults.outlinedButtonColors(),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                ) { Text(label, fontSize = 10.sp, maxLines = 1, softWrap = false) }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Render-mode selector — applies to whichever view is active.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                TraceContentMode.PARSED to "Parsed",
                TraceContentMode.PRETTY to "Pretty print",
                TraceContentMode.RAW to "Raw"
            ).forEach { (mode, label) ->
                val isActive = currentMode == mode
                OutlinedButton(onClick = { currentMode = mode }, modifier = Modifier.weight(1f),
                    colors = if (isActive) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF3366BB).copy(alpha = 0.3f)) else ButtonDefaults.outlinedButtonColors(),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                ) { Text(label, fontSize = 10.sp, maxLines = 1, softWrap = false) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content area. PARSED mode renders the colored JSON tree for
        // the body views and key/value rows for Meta / Get / headers;
        // PRETTY / RAW fall through to plain monospace text.
        val parsed = currentMode == TraceContentMode.PARSED
        val treeNodes = if (parsed) when (currentView) {
            TraceContentView.REQ_DATA -> requestTreeNodes
            TraceContentView.RSP_DATA -> responseTreeNodes
            else -> null
        } else null

        // Meta / Get / headers reuse the key/value row renderer.
        val headerEntries: List<Pair<String, String>>? = if (parsed) when (currentView) {
            TraceContentView.META -> metaEntries
            TraceContentView.GET -> queryParams
            TraceContentView.REQ_HEADERS -> t?.request?.headers?.entries?.map { it.key to it.value }
            TraceContentView.RSP_HEADERS -> t?.response?.headers?.entries?.map { it.key to it.value }
            else -> null
        } else null

        Box(modifier = Modifier.weight(1f).horizontalSwipeNavigation(
            key1 = currentFilename,
            key2 = traceFiles,
            atFirst = !hasPrev,
            atLast = !hasNext,
            onSwipeLeft = {
                if (hasNext) { currentFilename = traceFiles[currentIndex + 1]; currentView = TraceContentView.RSP_DATA; currentMode = TraceContentMode.PARSED }
            },
            onSwipeRight = {
                if (hasPrev) { currentFilename = traceFiles[currentIndex - 1]; currentView = TraceContentView.RSP_DATA; currentMode = TraceContentMode.PARSED }
            }
        )) {
            if (treeNodes != null) {
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
                            Text(value, fontSize = 11.sp, color = Color(0xFF6A8759), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            } else {
                // PRETTY gets JSON syntax colors (same palette as the
                // Parsed tree); RAW stays uncoloured — bytes as-is.
                val pretty = currentMode == TraceContentMode.PRETTY
                LazyColumn {
                    val lines = displayContent.lines()
                    items(lines.size) { index ->
                        if (pretty) {
                            Text(highlightJsonLine(lines[index]), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                        } else {
                            Text(lines[index], fontSize = 11.sp, color = Color(0xFFCCCCCC),
                                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom buttons — navigation between traces is via horizontal
        // swipe on the content Box above. This row carries the
        // non-navigation actions only.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Fixed report indicator + jump-back button. Only rendered
            // when this trace is report-scoped — paints the same 📝
            // glyph every report-scoped screen uses, so the user has
            // a persistent "this trace belongs to a report" signal
            // independent of whether the report's AI-generated icon
            // has resolved yet.
            val tracedReportId = t?.reportId
            if (tracedReportId != null) {
                OutlinedButton(
                    onClick = { onOpenReport(tracedReportId) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(36.dp).semantics { contentDescription = "Open report" },
                    colors = AppColors.outlinedButtonColors()
                ) { Text("📝", fontSize = 14.sp, maxLines = 1, softWrap = false) }
                // 👁 — sibling of 📝 / 🔧. Opens the same report but lands
                // on the View tile grid instead of Manage. The 📝 button
                // above keeps the historical Manage entry behaviour; this
                // is the additive shortcut every report-list row across
                // the app also surfaces.
                OutlinedButton(
                    onClick = { onOpenReportView(tracedReportId) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(36.dp).semantics { contentDescription = "View report" },
                    colors = AppColors.outlinedButtonColors()
                ) { Text("👁", fontSize = 14.sp, maxLines = 1, softWrap = false) }
            }
            // Copy and Share lived here too, but they're already wired
            // on the title-bar icon strip (📋 + 📤) with byte-identical
            // redaction — duplicating them in the bottom row cluttered
            // the layout and made the icon-bar shortcuts feel optional.
            OutlinedButton(onClick = {
                // Save provider/model/url to prefs for EditApiRequestScreen.
                // Redact the URL (Google's `?key=…` query param + similar)
                // before persisting — SharedPreferences are clear-text
                // XML on disk and roll into the backup zip, so a raw
                // URL with an embedded API key would survive longer than
                // intended. The request body is JSON without auth (auth
                // lives in headers); pass it through untouched.
                t?.let { trace ->
                        val prefs = context.getSharedPreferences("eval_prefs", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            putString("last_test_raw_json", trace.request.body)
                            putString("last_test_api_url", com.ai.ui.helpers.redactUrl(trace.request.url))
                            putString("last_test_model", trace.model ?: "")
                        }.apply()
                    }
                onEditRequest()
            }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Edit", maxLines = 1, softWrap = false) }
        }
    }
    } // close CompositionLocalProvider wrap added above
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

/** Per-line JSON syntax highlight for the trace detail's Pretty
 *  print mode — same palette as the Parsed tree (keys blue,
 *  strings green, numbers orange, booleans purple, null dim,
 *  punctuation gray). Pretty-printed JSON keeps every value on
 *  its own line, so a per-line scan is sufficient. */
private fun highlightJsonLine(line: String): AnnotatedString = buildAnnotatedString {
    val green = Color(0xFF6A8759)
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' -> {
                val start = i; i++
                while (i < line.length) {
                    if (line[i] == '\\' && i + 1 < line.length) { i += 2; continue }
                    if (line[i] == '"') { i++; break }
                    i++
                }
                // Key when the next non-space char is a colon.
                var j = i
                while (j < line.length && line[j] == ' ') j++
                val isKey = j < line.length && line[j] == ':'
                withStyle(SpanStyle(color = if (isKey) AppColors.Blue else green)) {
                    append(line.substring(start, i))
                }
            }
            c.isDigit() || (c == '-' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                val start = i; i++
                while (i < line.length && (line[i].isDigit() || line[i] in ".eE+-")) i++
                withStyle(SpanStyle(color = AppColors.Orange)) { append(line.substring(start, i)) }
            }
            line.startsWith("true", i) -> { withStyle(SpanStyle(color = AppColors.Purple)) { append("true") }; i += 4 }
            line.startsWith("false", i) -> { withStyle(SpanStyle(color = AppColors.Purple)) { append("false") }; i += 5 }
            line.startsWith("null", i) -> { withStyle(SpanStyle(color = AppColors.TextDim)) { append("null") }; i += 4 }
            else -> {
                val start = i
                while (i < line.length && line[i] != '"' && !line[i].isDigit() &&
                    !(line[i] == '-' && i + 1 < line.length && line[i + 1].isDigit()) &&
                    !line.startsWith("true", i) && !line.startsWith("false", i) && !line.startsWith("null", i)
                ) i++
                if (i == start) i++
                withStyle(SpanStyle(color = AppColors.TextTertiary)) { append(line.substring(start, i)) }
            }
        }
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
                Text(node.value ?: "", fontSize = 11.sp, color = valueColor, fontFamily = FontFamily.Monospace)
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
internal fun extractTranslationParts(requestBody: String?, responseBody: String?): Pair<String, String>? {
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
internal fun extractUserPrompt(json: String): String? {
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
internal fun extractAssistantContent(json: String): String? {
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
