package com.ai.ui.report

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists every persisted [SecondaryResult] of a given [kind] for [reportId].
 * Tapping a row opens [SecondaryResultDetailScreen]; the row also exposes a
 * trash icon for direct deletion. When the list reaches zero entries the
 * screen pops itself back to the report.
 */
@Composable
internal fun SecondaryResultsScreen(
    reportId: String,
    kind: SecondaryKind,
    nameFilter: String? = null,
    isBatching: Boolean = false,
    runningCrossPairs: Set<String> = emptySet(),
    afterCrossPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    crossPrompt: com.ai.model.InternalPrompt? = null,
    onRunAfterCross: (() -> Unit)? = null,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToInternalPromptEdit: (String) -> Unit = {},
    onResumeStaleCross: (com.ai.model.InternalPrompt) -> Unit = {},
    onRestartFailedCross: (com.ai.model.InternalPrompt) -> Unit = {},
    onRerunCompleteCross: (com.ai.model.InternalPrompt) -> Unit = {},
    onDeleteCrossModel: (String, String, String) -> Unit = { _, _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    // rememberSaveable so the user can drill into a row, jump out to a
    // trace, and return to the same row instead of the list root.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(isBatching) {
        while (isBatching) {
            delay(500)
            refreshTick++
        }
    }
    val results by produceState(initialValue = emptyList<SecondaryResult>(), reportId, kind, nameFilter, refreshTick) {
        value = withContext(Dispatchers.IO) {
            val all = SecondaryResultStorage.listForReport(context, reportId, kind)
            if (nameFilter == null) all
            else all.filter {
                val rowName = it.metaPromptName?.takeIf { n -> n.isNotBlank() }
                    ?: com.ai.data.legacyKindDisplayName(it.kind)
                rowName == nameFilter
            }
        }
    }
    // After_cross rows on this report regardless of nameFilter — the
    // filter targets the cross prompt's name, but after_cross rows
    // carry the (different) after_cross prompt's name. Loaded
    // unconditionally so the cross detail screen can list every
    // combine-reports follow-up that this report has spawned.
    val afterCrossRows by produceState(initialValue = emptyList<SecondaryResult>(), reportId, refreshTick) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter { it.afterCrossOf != null }
        }
    }
    // TRANSLATE rows on this report — drives the language picker for
    // chat-type META views. Languages not seen on TRANSLATE rows never
    // get a tab even if a per-language batch row exists, since the
    // spec is "show the picker iff there are translations."
    val translates by produceState(initialValue = emptyList<SecondaryResult>(), reportId, refreshTick) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TRANSLATE)
                .filter { !it.targetLanguage.isNullOrBlank() }
        }
    }
    val showLanguagePicker = kind == SecondaryKind.META && translates.isNotEmpty()
    val languages = remember(translates) { buildLangTabs(translates) }
    var selectedLangKey by remember { mutableStateOf(LangTab.ORIGINAL_KEY) }
    LaunchedEffect(languages) {
        if (languages.none { it.key == selectedLangKey }) selectedLangKey = LangTab.ORIGINAL_KEY
    }
    val selectedLanguageName: String? = remember(selectedLangKey, languages) {
        if (selectedLangKey == LangTab.ORIGINAL_KEY) null
        else languages.firstOrNull { it.key == selectedLangKey }?.displayName
    }

    // For chat-type META: a non-Original language view shows two
    // sources of translated content side by side:
    //   1. Per-language rows tagged with targetLanguage = X (produced
    //      by the multi-language Meta batch flow).
    //   2. Original rows (targetLanguage == null) whose content has
    //      been overlaid by a TRANSLATE row pointing at them
    //      (Translate-only flow). The overlay copies the translated
    //      content onto the Original row so the user sees the
    //      translated text without losing the row's metadata.
    // The Original view shows untagged rows only; non-META kinds skip
    // the overlay path entirely (rerank / moderation are structured
    // JSON, not narrative translatable text).
    val filteredResults = remember(results, selectedLangKey, showLanguagePicker, selectedLanguageName, translates) {
        if (!showLanguagePicker) return@remember results
        if (selectedLanguageName == null) return@remember results.filter { it.targetLanguage == null }
        val perLang = results.filter { it.targetLanguage == selectedLanguageName }
        if (kind != SecondaryKind.META) return@remember perLang
        val txByTarget = translates
            .filter {
                it.targetLanguage == selectedLanguageName &&
                    it.translateSourceKind == "META" &&
                    !it.content.isNullOrBlank()
            }
            .associateBy { it.translateSourceTargetId ?: "" }
        val overlaid = results.mapNotNull { s ->
            if (s.targetLanguage != null) return@mapNotNull null
            val tx = txByTarget[s.id] ?: return@mapNotNull null
            s.copy(content = tx.content)
        }
        perLang + overlaid
    }

    val openResult = openId?.let { id ->
        results.firstOrNull { it.id == id } ?: afterCrossRows.firstOrNull { it.id == id }
    }
    if (openResult != null) {
        SecondaryResultDetailScreen(
            result = openResult,
            onDelete = {
                onDelete(openResult.id)
                openId = null
                refreshTick++
            },
            onBack = { openId = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Always entered via the View card buckets, which pass the
    // user-given Meta-prompt name (or the legacy kind label for rows
    // pre-dating the Meta-prompt CRUD). No hardcoded plural labels —
    // the screen is driven entirely by what the bucket button said.
    val baseTitle = nameFilter ?: com.ai.data.legacyKindDisplayName(kind)
    // Cross runs paint a "Cross level N" title that tracks the drill-in
    // depth (L1 answerers → L2 sources → L3 split view). The drill-in
    // reports its current level via onLevelChange below.
    val crossRowsAll = filteredResults.filter { it.afterCrossOf == null }
    val isCrossDrillIn = kind == SecondaryKind.META &&
        crossRowsAll.any { it.crossSourceAgentId != null }
    var crossLevel by rememberSaveable { mutableIntStateOf(1) }
    val title = if (isCrossDrillIn) "Cross level $crossLevel" else baseTitle
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = title, onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        if (showLanguagePicker) {
            LanguagePickerRow(
                languages = languages,
                selectedKey = selectedLangKey,
                onSelect = { selectedLangKey = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (filteredResults.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val msg = if (showLanguagePicker && selectedLanguageName != null)
                    "No ${baseTitle.lowercase()} for $selectedLanguageName yet"
                else "No ${baseTitle.lowercase()} for this report"
                Text(msg, color = AppColors.TextSecondary, fontSize = 14.sp)
            }
            return@Column
        }

        // Cross-type META: every cross row carries a crossSourceAgentId
        // pointing at the source whose response was factchecked. The
        // L1 → L2 → L3 drill-in below replaces the flat picker. The
        // after_cross combine-reports rows live in afterCrossRows
        // (loaded unconditionally above so the nameFilter doesn't hide
        // them) and surface as the second list above the answerers.
        if (isCrossDrillIn) {
            CrossMetaDrillInView(
                reportId = reportId,
                results = crossRowsAll,
                combinedRows = afterCrossRows,
                afterCrossPrompts = afterCrossPrompts,
                crossPrompt = crossPrompt,
                runningCrossPairs = runningCrossPairs,
                onRunAfterCross = onRunAfterCross,
                onDelete = { id -> onDelete(id); refreshTick++ },
                onOpen = { id -> openId = id },
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToModelInfo = onNavigateToModelInfo,
                onNavigateToInternalPromptEdit = onNavigateToInternalPromptEdit,
                onResumeStaleCross = onResumeStaleCross,
                onRestartFailedCross = onRestartFailedCross,
                onRerunCompleteCross = onRerunCompleteCross,
                onDeleteCrossModel = onDeleteCrossModel,
                onLevelChange = { crossLevel = it }
            )
            return@Column
        }

        // Chat-type META: mirror the Reports viewer — a row of picker
        // buttons (one per filtered result) followed by the selected
        // result's body inline. RERANK / MODERATION / TRANSLATE keep
        // the row-list-and-drill-in flow since each item is a distinct
        // table or per-input classification.
        if (kind == SecondaryKind.META) {
            MetaResultsPickerView(
                results = filteredResults,
                reportId = reportId,
                onDelete = { id -> onDelete(id); refreshTick++ },
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToModelInfo = onNavigateToModelInfo
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredResults, key = { it.id }) { r ->
                SecondaryRow(
                    r,
                    onClick = { openId = r.id },
                    onDelete = { onDelete(r.id); refreshTick++ }
                )
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
    }
}

/** Reports-viewer-style screen for chat-type META results: a FlowRow
 *  of picker buttons (one per result) plus the selected result's
 *  content inline. Picker labels show the result's provider · model.
 *  The trailing action row mirrors [SecondaryResultDetailScreen]:
 *  Delete / Model Info / Trace. */
@Composable
private fun ColumnScope.MetaResultsPickerView(
    results: List<SecondaryResult>,
    reportId: String,
    onDelete: (String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit
) {
    var selectedId by remember(results) { mutableStateOf(results.firstOrNull()?.id) }
    LaunchedEffect(results) {
        if (results.none { it.id == selectedId }) selectedId = results.firstOrNull()?.id
    }
    val selected = results.firstOrNull { it.id == selectedId } ?: results.firstOrNull() ?: return

    // Picker buttons — one per result, FlowRow so wide rows wrap
    // gracefully on narrow viewports.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        results.forEach { r ->
            val isSelected = r.id == selectedId
            val provider = AppService.findById(r.providerId)?.displayName ?: r.providerId
            Button(
                onClick = { selectedId = r.id },
                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) AppColors.Orange else Color(0xFF3A3A4A)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(
                    "$provider · ${r.model}",
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, softWrap = false
                )
            }
        }
    }

    // Provider / model / timestamp header for the selected item.
    val providerService = AppService.findById(selected.providerId)
    val provider = providerService?.displayName ?: selected.providerId

    // Trace lookup mirrors SecondaryResultDetailScreen — pick the
    // closest-timestamped trace tagged with the same (reportId, model).
    // Hoisted above the header so the 🐞 ladybug can sit next to the
    // model name instead of in a bottom button row.
    val traceFilename by produceState<String?>(initialValue = null, selected.id) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == selected.reportId && it.model == selected.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - selected.timestamp) }?.filename
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$provider — ${selected.model}", fontSize = 16.sp, color = AppColors.Blue,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (ApiTracer.isTracingEnabled && traceFilename != null) {
            Text("🐞", fontSize = 18.sp,
                modifier = Modifier.padding(start = 8.dp).clickable { traceFilename?.let(onNavigateToTraceFile) })
        }
    }
    Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(selected.timestamp)),
        fontSize = 11.sp, color = AppColors.TextTertiary)
    Spacer(modifier = Modifier.height(8.dp))

    // Selected item body (scrolls independently of the picker row).
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        when {
            selected.errorMessage != null -> {
                Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                Text(selected.errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            selected.content.isNullOrBlank() -> {
                Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
            else -> {
                ContentWithThinkSections(analysis = selected.content)
            }
        }
    }

    // Bottom action row — Delete / Model. Trace lives at the top as a
    // 🐞 icon next to the provider/model header.
    var confirmDelete by remember { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { confirmDelete = true },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) { Text("Delete", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        Button(
            onClick = { providerService?.let { onNavigateToModelInfo(it, selected.model) } },
            enabled = providerService != null,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) { Text("Model", fontSize = 12.sp, maxLines = 1, softWrap = false) }
    }

    if (confirmDelete) {
        val noun = (selected.metaPromptName?.takeIf { it.isNotBlank() }
            ?: com.ai.data.legacyKindDisplayName(selected.kind)).lowercase()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this $noun?") },
            text = { Text("$provider · ${selected.model}") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(selected.id) }) {
                    Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}

/** Role-aware row in the L2 list — built by [CrossMetaDrillInView]
 *  and passed to [OnePageView] / used for prev-next stepping on L3. */
private data class L2Row(
    /** Stable key: source.agentId for Responder, "$pid|$mdl" for
     *  Initiator. */
    val key: String,
    val provider: String,
    val model: String,
    /** "$answererPid|$answererMdl|$srcAgentId" — the pair the row maps
     *  to on L3. */
    val l3PairKey: String,
    val pair: SecondaryResult?
)

/** L1 → L2 → L3 drill-in for cross-type META results.
 *  L1: list of every successful report-model that produced at least
 *      one factcheck row (the "answerer"), with progress bars + stats
 *      + a button row for restart-failed / show-prompt / edit-prompt
 *      / rerun-complete / delete-cross.
 *  L2: for the chosen model, list rows according to the active
 *      [Role]: Responder lists every source the model factchecked,
 *      Initiator lists every answerer that factchecked the model.
 *      A "One page view" button stitches the role-aware content into
 *      a single scrollable page.
 *  L3: split view — top half shows the source's report response,
 *      bottom half shows the answerer's factcheck content (or a
 *      ⏳ / 🕓 / ❌ / "(no result)" placeholder if missing). Previous /
 *      Next at the bottom step through the L2 list in role-aware
 *      order.
 */
@Composable
private fun ColumnScope.CrossMetaDrillInView(
    reportId: String,
    results: List<SecondaryResult>,
    combinedRows: List<SecondaryResult> = emptyList(),
    afterCrossPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    crossPrompt: com.ai.model.InternalPrompt? = null,
    runningCrossPairs: Set<String> = emptySet(),
    onRunAfterCross: (() -> Unit)? = null,
    onDelete: (String) -> Unit,
    /** Open a SecondaryResultDetailScreen for the given row id. Used by
     *  the combined-reports list above the answerers — tapping a row
     *  pops out the full content + delete / model / trace controls. */
    onOpen: (String) -> Unit = {},
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToInternalPromptEdit: (String) -> Unit = {},
    onResumeStaleCross: (com.ai.model.InternalPrompt) -> Unit = {},
    onRestartFailedCross: (com.ai.model.InternalPrompt) -> Unit = {},
    onRerunCompleteCross: (com.ai.model.InternalPrompt) -> Unit = {},
    onDeleteCrossModel: (String, String, String) -> Unit = { _, _, _ -> },
    /** Called whenever the drill-in level changes (1 = answerers list,
     *  2 = sources list for the chosen answerer, 3 = source/factcheck
     *  split view). Lets the parent screen reflect the depth in its
     *  TitleBar — "Cross level 1/2/3". */
    onLevelChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    // Load the parent report once so L1 / L2 can label rows by the
    // agents' provider/model and L3 can pull source.responseBody.
    val report by produceState<com.ai.data.Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { com.ai.data.ReportStorage.getReport(context, reportId) }
    }
    val r = report
    if (r == null) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp)
        }
        return
    }

    val successful = remember(r) {
        r.agents.filter { it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
    }
    val agentsById = remember(successful) { successful.associateBy { it.agentId } }

    // Latest-by-timestamp result per (answerer.providerId, answerer.model, sourceAgentId).
    val latestByPair = remember(results) {
        val byKey = LinkedHashMap<String, SecondaryResult>()
        results.sortedBy { it.timestamp }.forEach { row ->
            val src = row.crossSourceAgentId ?: return@forEach
            val key = "${row.providerId}|${row.model}|$src"
            byKey[key] = row // last write wins → newest
        }
        byKey
    }

    // Re-enqueue placeholders that survived an app kill (no content,
    // no error, and not currently in flight). The viewmodel filters by
    // metaPromptId so other Cross runs on the same report aren't
    // touched. No-op when crossPrompt is null (legacy rows without a
    // metaPromptId).
    LaunchedEffect(reportId, crossPrompt?.id) {
        crossPrompt?.let { onResumeStaleCross(it) }
    }

    var selectedModelKey by rememberSaveable { mutableStateOf<String?>(null) }
    // L2 role state — Responder = this model is the answerer (existing
    // behaviour); Initiator = this model is the source. Saved so the
    // user can drill out and back without losing the toggle.
    var selectedRole by rememberSaveable { mutableStateOf("Responder") }
    var l3AnswererKey by rememberSaveable { mutableStateOf<String?>(null) }
    var l3SourceAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    // Full-screen overlays inside this view — `if (showX) { X(); return }`
    // pattern documented in CLAUDE.md so the parent's rememberSaveable
    // state survives.
    var showPromptViewer by rememberSaveable { mutableStateOf(false) }
    var showOnePageView by rememberSaveable { mutableStateOf(false) }

    val currentLevel = when {
        l3AnswererKey != null && l3SourceAgentId != null -> 3
        selectedModelKey != null -> 2
        else -> 1
    }
    LaunchedEffect(currentLevel) { onLevelChange(currentLevel) }

    BackHandler(enabled = showPromptViewer) { showPromptViewer = false }
    BackHandler(enabled = showOnePageView) { showOnePageView = false }
    BackHandler(enabled = !showPromptViewer && !showOnePageView && currentLevel == 3) {
        l3AnswererKey = null; l3SourceAgentId = null
    }
    BackHandler(enabled = !showPromptViewer && !showOnePageView && currentLevel == 2) {
        selectedModelKey = null
    }

    // Read-only prompt viewer overlay
    if (showPromptViewer && crossPrompt != null) {
        Text(crossPrompt.name, fontSize = 16.sp, color = AppColors.Blue,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        if (crossPrompt.title.isNotBlank()) {
            Text(crossPrompt.title, fontSize = 12.sp, color = AppColors.TextSecondary)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(crossPrompt.text, fontSize = 13.sp, color = AppColors.TextSecondary,
                fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { showPromptViewer = false },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
        ) { Text("Close", fontSize = 13.sp) }
        return
    }

    // Stat / status helpers shared between L1 / L2.
    fun rowState(row: SecondaryResult?): String = when {
        row == null -> "queued"
        row.errorMessage != null -> "errored"
        !row.content.isNullOrBlank() -> "done"
        row.id in runningCrossPairs -> "running"
        else -> "queued"
    }

    // Build the role-aware ordered list for L2 / L3 stepping.
    val activeKey = selectedModelKey
    val activePid = activeKey?.split("|")?.getOrNull(0).orEmpty()
    val activeMdl = activeKey?.split("|")?.getOrNull(1).orEmpty()
    val activeAgents = remember(successful, activeKey) {
        if (activeKey == null) emptyList()
        else successful.filter { it.provider == activePid && it.model == activeMdl }
    }
    val activeAgentIds = activeAgents.map { it.agentId }.toSet()

    // L2 list rows — ordering depends on role.
    val l2Rows: List<L2Row> = remember(activeKey, selectedRole, latestByPair, successful, results) {
        if (activeKey == null) emptyList()
        else if (selectedRole == "Responder") {
            // sources = every successful agent except this model itself
            successful
                .filter { it.agentId !in activeAgentIds }
                .map { src ->
                    val pair = latestByPair["$activeKey|${src.agentId}"]
                    L2Row(
                        key = src.agentId,
                        provider = src.provider, model = src.model,
                        l3PairKey = "$activeKey|${src.agentId}",
                        pair = pair
                    )
                }
        } else {
            // Initiator — list answerers (provider, model) that have a
            // row whose crossSourceAgentId matches one of this model's
            // agents. De-dup by answerer (provider, model); use the
            // first matching active agent as the canonical source for
            // L3 drill-in.
            val canonicalSrc = activeAgents.firstOrNull()?.agentId
            results
                .filter { it.crossSourceAgentId in activeAgentIds && it.afterCrossOf == null }
                .groupBy { "${it.providerId}|${it.model}" }
                .map { (ans, rows) ->
                    val parts = ans.split("|")
                    val pid = parts.getOrNull(0).orEmpty()
                    val mdl = parts.getOrNull(1).orEmpty()
                    val pair = rows.maxByOrNull { it.timestamp }
                    L2Row(
                        key = ans,
                        provider = pid, model = mdl,
                        l3PairKey = "$ans|${canonicalSrc ?: ""}",
                        pair = pair
                    )
                }
        }
    }

    // ===== L3 (split view) — needs l2Rows for prev/next =====
    val srcAgentIdL3 = l3SourceAgentId
    val answererKeyL3 = l3AnswererKey
    if (srcAgentIdL3 != null && answererKeyL3 != null) {
        val pairResult = latestByPair["$answererKeyL3|$srcAgentIdL3"]
        val sourceAgent = agentsById[srcAgentIdL3]
        val sourceLabel = sourceAgent?.let {
            val pn = AppService.findById(it.provider)?.displayName ?: it.provider
            "$pn / ${it.model}"
        } ?: srcAgentIdL3
        val answererLabel = answererKeyL3.split("|").let { parts ->
            val pid = parts.getOrNull(0).orEmpty()
            val mdl = parts.getOrNull(1).orEmpty()
            val pn = AppService.findById(pid)?.displayName ?: pid
            "$pn / $mdl"
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("Source: $sourceLabel", fontSize = 12.sp, color = AppColors.TextTertiary,
            fontFamily = FontFamily.Monospace)
        Text("Answerer: $answererLabel", fontSize = 12.sp, color = AppColors.TextTertiary,
            fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(8.dp))
        // Top half — source's original report response.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Source response", fontSize = 13.sp, color = AppColors.Blue,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val srcTraceState = produceState<String?>(initialValue = null, reportId, sourceAgent?.model) {
                    value = if (sourceAgent == null) null else withContext(Dispatchers.IO) {
                        ApiTracer.getTraceFiles()
                            .filter { it.reportId == reportId && it.model == sourceAgent.model }
                            .maxByOrNull { it.timestamp }?.filename
                    }
                }
                val srcTrace = srcTraceState.value
                if (ApiTracer.isTracingEnabled && srcTrace != null) {
                    Text("🐞", fontSize = 16.sp,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clickable { onNavigateToTraceFile(srcTrace) })
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            val srcBody = sourceAgent?.responseBody
            if (srcBody.isNullOrBlank()) {
                Text("(source response missing)", color = AppColors.TextTertiary, fontSize = 13.sp)
            } else {
                ContentWithThinkSections(analysis = srcBody)
            }
        }
        HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp,
            modifier = Modifier.padding(vertical = 8.dp))
        // Bottom half — answerer's factcheck or a status placeholder.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Factcheck", fontSize = 13.sp, color = AppColors.Green,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val tf = pairResult?.let { res ->
                    val all = ApiTracer.getTraceFiles()
                        .filter { it.reportId == reportId && it.model == res.model }
                    all.minByOrNull { kotlin.math.abs(it.timestamp - res.timestamp) }?.filename
                }
                if (ApiTracer.isTracingEnabled && tf != null) {
                    Text("🐞", fontSize = 16.sp,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clickable { onNavigateToTraceFile(tf) })
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            when {
                pairResult == null -> Text("(no result)", color = AppColors.TextTertiary, fontSize = 13.sp)
                pairResult.errorMessage != null -> {
                    Text("❌ ${pairResult.errorMessage}", fontSize = 13.sp, color = AppColors.Red)
                }
                pairResult.content.isNullOrBlank() -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pairResult.id in runningCrossPairs) {
                            com.ai.ui.report.AnimatedHourglass(fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Running…", fontSize = 13.sp, color = AppColors.TextSecondary)
                        } else {
                            Text("🕓", fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Queued", fontSize = 13.sp, color = AppColors.TextSecondary)
                        }
                    }
                }
                else -> ContentWithThinkSections(analysis = pairResult.content)
            }
        }
        // Previous / Next — step through the L2 list in role-aware order.
        // The current pair is identified by l3PairKey. Disabled at ends.
        Spacer(modifier = Modifier.height(8.dp))
        val currentPairKey = "$answererKeyL3|$srcAgentIdL3"
        val currentIdx = l2Rows.indexOfFirst { it.l3PairKey == currentPairKey }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val prev = l2Rows.getOrNull(currentIdx - 1) ?: return@Button
                    val parts = prev.l3PairKey.split("|")
                    val ans = "${parts.getOrNull(0).orEmpty()}|${parts.getOrNull(1).orEmpty()}"
                    val src = parts.getOrNull(2).orEmpty()
                    l3AnswererKey = ans
                    l3SourceAgentId = src
                },
                enabled = currentIdx > 0,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Previous", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = {
                    val next = l2Rows.getOrNull(currentIdx + 1) ?: return@Button
                    val parts = next.l3PairKey.split("|")
                    val ans = "${parts.getOrNull(0).orEmpty()}|${parts.getOrNull(1).orEmpty()}"
                    val src = parts.getOrNull(2).orEmpty()
                    l3AnswererKey = ans
                    l3SourceAgentId = src
                },
                enabled = currentIdx >= 0 && currentIdx < l2Rows.size - 1,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Next", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        }
        return
    }

    // ===== L2 (one-page view overlay) =====
    if (showOnePageView && activeKey != null) {
        OnePageView(
            reportId = reportId,
            role = selectedRole,
            activePid = activePid,
            activeMdl = activeMdl,
            activeAgents = activeAgents,
            l2Rows = l2Rows,
            agentsById = agentsById,
            onClose = { showOnePageView = false },
            onNavigateToTraceFile = onNavigateToTraceFile
        )
        return
    }

    // ===== L2 (role-aware list) =====
    if (activeKey != null) {
        val provName = AppService.findById(activePid)?.displayName ?: activePid
        val activeProviderService = AppService.findById(activePid)
        var confirmModelDelete by remember { mutableStateOf(false) }
        // First line: provider / model
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$provName / $activeMdl", fontSize = 14.sp, color = AppColors.Blue,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            // 🐞 only in Initiator role — opens the trace of this
            // model's report-agent run (the call that produced the
            // source response).
            if (selectedRole == "Initiator") {
                val firstAgent = activeAgents.firstOrNull()
                val modelTraceState = produceState<String?>(initialValue = null, reportId, firstAgent?.model) {
                    value = if (firstAgent == null) null else withContext(Dispatchers.IO) {
                        ApiTracer.getTraceFiles()
                            .filter { it.reportId == reportId && it.model == firstAgent.model }
                            .maxByOrNull { it.timestamp }?.filename
                    }
                }
                val tr = modelTraceState.value
                if (ApiTracer.isTracingEnabled && tr != null) {
                    Text("🐞", fontSize = 18.sp,
                        modifier = Modifier.padding(start = 8.dp)
                            .clickable { onNavigateToTraceFile(tr) })
                }
            }
            // ℹ — Model Info for the active model.
            if (activeProviderService != null) {
                Text("ℹ", fontSize = 18.sp, color = AppColors.Blue,
                    modifier = Modifier.padding(start = 8.dp)
                        .clickable { onNavigateToModelInfo(activeProviderService, activeMdl) })
            }
            // 🗑 — drop the model from this Cross (both roles).
            Text("🗑", fontSize = 18.sp, color = AppColors.Red,
                modifier = Modifier.padding(start = 8.dp)
                    .clickable { confirmModelDelete = true })
        }
        // Second line: Role + Switch role button
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)) {
            Text("Role: $selectedRole", fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f))
            Button(
                onClick = { selectedRole = if (selectedRole == "Responder") "Initiator" else "Responder" },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) { Text("Switch role", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Per-model total cost
        val totalCost = l2Rows.sumOf { it.pair?.let { p -> (p.inputCost ?: 0.0) + (p.outputCost ?: 0.0) } ?: 0.0 }
        if (totalCost > 0.0) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Total", fontSize = 12.sp, color = AppColors.Blue, modifier = Modifier.weight(1f))
                Text("${formatCents(totalCost)} ¢", fontSize = 12.sp,
                    color = AppColors.Blue, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (l2Rows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (selectedRole == "Responder") "No factchecks for this model yet"
                    else "No other model has factchecked this one yet",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(l2Rows, key = { it.key }) { row ->
                    val rowProv = AppService.findById(row.provider)?.displayName ?: row.provider
                    val state = rowState(row.pair)
                    val cost = row.pair?.let { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } ?: 0.0
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                val parts = row.l3PairKey.split("|")
                                val ans = "${parts.getOrNull(0).orEmpty()}|${parts.getOrNull(1).orEmpty()}"
                                val src = parts.getOrNull(2).orEmpty()
                                if (src.isNotBlank()) {
                                    l3AnswererKey = ans
                                    l3SourceAgentId = src
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.padding(end = 8.dp).width(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when (state) {
                                "running" -> com.ai.ui.report.AnimatedHourglass(fontSize = 16.sp)
                                "queued" -> Text("🕓", fontSize = 16.sp)
                                "errored" -> Text("❌", fontSize = 16.sp)
                                else -> Text("✅", fontSize = 16.sp)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("$rowProv · ${row.model}", fontSize = 14.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (cost > 0.0) {
                            Text(formatCents(cost), fontSize = 11.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(">", fontSize = 16.sp, color = AppColors.Blue)
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }
        }
        // Bottom button — One page view
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { showOnePageView = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
        ) { Text("One page view", fontSize = 13.sp, maxLines = 1, softWrap = false) }

        if (confirmModelDelete) {
            AlertDialog(
                onDismissRequest = { confirmModelDelete = false },
                title = { Text("Delete this model from the cross?") },
                text = {
                    Text("Drop every factcheck row where $provName / $activeMdl is " +
                        "either the answerer or the source. Other Cross runs on this " +
                        "report are not affected. Can't be undone.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmModelDelete = false
                        crossPrompt?.let { onDeleteCrossModel(it.id, activePid, activeMdl) }
                        selectedModelKey = null
                    }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmModelDelete = false }) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            )
        }
        return
    }

    // ===== L1 — model rows + progress / stats / actions =====
    val modelKeys = remember(latestByPair, successful) {
        successful.map { "${it.provider}|${it.model}" }.distinct()
    }

    // Stats — derived from the cross rows + running set. A row counts
    // as processed once the executor has stamped durationMs (set on
    // every successful and errored save, cleared by resetAndRelaunch).
    // Without that signal, a successful call that returned an empty
    // body (no text, no error) would slip past the content-non-blank
    // check, get dropped from runningCrossPairs in the finally block,
    // and silently land in Queued instead of Done.
    val totalPairs = results.size
    val doneCount = results.count {
        it.errorMessage == null && (!it.content.isNullOrBlank() || it.durationMs != null)
    }
    val erroredCount = results.count { it.errorMessage != null }
    val runningCount = results.count {
        it.id in runningCrossPairs && it.errorMessage == null
            && it.content.isNullOrBlank() && it.durationMs == null
    }
    val queuedCount = (totalPairs - doneCount - erroredCount - runningCount).coerceAtLeast(0)
    val pendingCount = runningCount + queuedCount

    // Cross prompt name heading + optional title subline.
    if (crossPrompt != null) {
        Text(crossPrompt.name, fontSize = 16.sp, color = AppColors.Blue,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        if (crossPrompt.title.isNotBlank()) {
            Text(crossPrompt.title, fontSize = 12.sp, color = AppColors.TextSecondary)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    // Top progress bar — total expected pairs.
    if (pendingCount > 0 && totalPairs > 0) {
        val finished = (doneCount + erroredCount).toFloat() / totalPairs
        LinearProgressIndicator(
            progress = { finished },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = AppColors.Orange,
            trackColor = AppColors.DividerDark
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    // Total cost banner.
    val totalAnswerersCost = remember(latestByPair, combinedRows) {
        latestByPair.values.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } +
            combinedRows.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
    }
    if (totalAnswerersCost > 0.0) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Total", fontSize = 12.sp, color = AppColors.Blue, modifier = Modifier.weight(1f))
            Text("${formatCents(totalAnswerersCost)} ¢", fontSize = 12.sp,
                color = AppColors.Blue, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }

    LazyColumn(modifier = Modifier.weight(1f)) {
        // After_cross combine-reports follow-ups for this report.
        if (combinedRows.isNotEmpty()) {
            item(key = "ac-header") {
                Text("Combined reports", fontSize = 12.sp,
                    color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            val sortedCombined = combinedRows.sortedByDescending { it.timestamp }
            items(sortedCombined, key = { "ac-${it.id}" }) { row ->
                val acProv = AppService.findById(row.providerId)?.displayName ?: row.providerId
                val acCost = (row.inputCost ?: 0.0) + (row.outputCost ?: 0.0)
                val acLabel = row.metaPromptName?.takeIf { it.isNotBlank() }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onOpen(row.id) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.padding(end = 8.dp).width(20.dp),
                        contentAlignment = Alignment.Center) {
                        when {
                            row.errorMessage != null -> Text("❌", fontSize = 16.sp)
                            row.content.isNullOrBlank() ->
                                if (row.id in runningCrossPairs) com.ai.ui.report.AnimatedHourglass(fontSize = 16.sp)
                                else Text("🕓", fontSize = 16.sp)
                            else -> Text("✅", fontSize = 16.sp)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("$acProv · ${row.model}", fontSize = 14.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (acLabel != null) {
                            Text(acLabel, fontSize = 11.sp, color = AppColors.TextTertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (acCost > 0.0) {
                        Text(formatCents(acCost), fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(">", fontSize = 16.sp, color = AppColors.Blue)
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
            item(key = "ac-section-gap") {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Models", fontSize = 12.sp,
                    color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        items(modelKeys, key = { it }) { ak ->
            val parts = ak.split("|")
            val pid = parts.getOrNull(0).orEmpty()
            val mdl = parts.getOrNull(1).orEmpty()
            val provName = AppService.findById(pid)?.displayName ?: pid
            // Per-row pair status — for the answerer view (each model's
            // own factchecks of every other source).
            val totalSources = successful.count { !(it.provider == pid && it.model == mdl) }
            var rowOk = 0; var rowErr = 0; var rowRun = 0
            successful.forEach { src ->
                if (src.provider == pid && src.model == mdl) return@forEach
                val res = latestByPair["$ak|${src.agentId}"]
                when {
                    res == null -> Unit
                    res.errorMessage != null -> rowErr++
                    !res.content.isNullOrBlank() || res.durationMs != null -> rowOk++
                    res.id in runningCrossPairs -> rowRun++
                }
            }
            val rowFinished = rowOk + rowErr
            val rowPending = (totalSources - rowFinished).coerceAtLeast(0)
            val rowCost = successful.sumOf { src ->
                if (src.provider == pid && src.model == mdl) 0.0
                else latestByPair["$ak|${src.agentId}"]
                    ?.let { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } ?: 0.0
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { selectedModelKey = ak; selectedRole = "Responder" }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.padding(end = 8.dp).width(20.dp),
                    contentAlignment = Alignment.Center) {
                    when {
                        rowRun > 0 -> com.ai.ui.report.AnimatedHourglass(fontSize = 16.sp)
                        rowPending > 0 -> Text("🕓", fontSize = 16.sp)
                        rowErr > 0 -> Text("❌", fontSize = 16.sp)
                        else -> Text("✅", fontSize = 16.sp)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("$provName · $mdl", fontSize = 14.sp, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (rowPending > 0 && totalSources > 0) {
                        // Per-row progress bar replaces the legacy "X / Y" text.
                        LinearProgressIndicator(
                            progress = { rowFinished.toFloat() / totalSources },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(4.dp),
                            color = AppColors.Orange,
                            trackColor = AppColors.DividerDark
                        )
                    } else if (rowErr > 0) {
                        Text("$rowOk / $totalSources · ❌ $rowErr",
                            fontSize = 11.sp, color = AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                if (rowCost > 0.0) {
                    Text(formatCents(rowCost), fontSize = 11.sp,
                        color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 8.dp))
                }
                Text(">", fontSize = 16.sp, color = AppColors.Blue)
            }
            HorizontalDivider(color = AppColors.DividerDark)
        }
    }

    // Stats panel — only meaningful while pairs are still missing.
    // When every call has finished successfully (total == done) the
    // four-zero rows are noise, so hide the whole block.
    if (totalPairs != doneCount) {
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            StatRow("Total API calls", totalPairs.toString(), AppColors.Blue)
            StatRow("Done", doneCount.toString(), AppColors.Green)
            StatRow("Errored", erroredCount.toString(),
                if (erroredCount > 0) AppColors.Red else AppColors.TextTertiary)
            StatRow("Running", runningCount.toString(), AppColors.Orange)
            StatRow("Queued", queuedCount.toString(), AppColors.TextTertiary)
        }
    }

    // Action buttons — collapsed by default so the L1 page leads with
    // the model rows + stats, and the user expands to find the
    // run-management actions.
    var confirmRerunComplete by remember { mutableStateOf(false) }
    var confirmCrossDelete by remember { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(8.dp))
    CollapsibleCard("Actions") {
        if (afterCrossPrompts.isNotEmpty() && onRunAfterCross != null) {
            Button(
                onClick = { onRunAfterCross() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) {
                Text("Combine reports and all cross responses",
                    fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
        Button(
            onClick = { crossPrompt?.let { onRestartFailedCross(it) } },
            enabled = crossPrompt != null && erroredCount > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
        ) { Text("Restart all failed API calls", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        Button(
            onClick = { showPromptViewer = true },
            enabled = crossPrompt != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
        ) { Text("Show the used Cross prompt", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        Button(
            onClick = { crossPrompt?.let { onNavigateToInternalPromptEdit(it.id) } },
            enabled = crossPrompt != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
        ) { Text("Edit the used Cross prompt", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        Button(
            onClick = { confirmRerunComplete = true },
            enabled = crossPrompt != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) { Text("Rerun the complete Cross", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        Button(
            onClick = { confirmCrossDelete = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
        ) { Text("Delete this Cross", fontSize = 13.sp, maxLines = 1, softWrap = false) }
    }

    if (confirmRerunComplete) {
        AlertDialog(
            onDismissRequest = { confirmRerunComplete = false },
            title = { Text("Rerun the complete Cross?") },
            text = {
                Text("Delete every cross row and start a fresh run. " +
                    "Combined-report follow-ups for this prompt will also be dropped.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRerunComplete = false
                    crossPrompt?.let { onRerunCompleteCross(it) }
                }) { Text("Rerun", color = AppColors.Orange, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRerunComplete = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
    if (confirmCrossDelete) {
        val totalRows = results.size + combinedRows.size
        AlertDialog(
            onDismissRequest = { confirmCrossDelete = false },
            title = { Text("Delete cross run?") },
            text = {
                Text(
                    "Drop every per-pair factcheck for this cross run" +
                        (if (combinedRows.isNotEmpty()) " plus the combined-report follow-up" else "") +
                        " — $totalRows rows. Can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCrossDelete = false
                    (results + combinedRows).forEach { onDelete(it.id) }
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCrossDelete = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, color = valueColor, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold)
    }
}

/** L2 "One page view" overlay. Lays out the role-aware content as a
 *  single scrollable page so the user can read every source response
 *  + factcheck side-by-side without drilling into L3.
 *
 *  Responder (active model is the answerer): for every source on L2,
 *  show the source's report response followed by the active model's
 *  factcheck of it.
 *  Initiator (active model is the source): show the active model's
 *  report response once at the top, then for each answerer on L2 show
 *  that answerer's factcheck. */
@Composable
private fun OnePageView(
    reportId: String,
    role: String,
    activePid: String,
    activeMdl: String,
    activeAgents: List<com.ai.data.ReportAgent>,
    l2Rows: List<L2Row>,
    agentsById: Map<String, com.ai.data.ReportAgent>,
    onClose: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit
) {
    BackHandler { onClose() }
    val provName = AppService.findById(activePid)?.displayName ?: activePid
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("$provName / $activeMdl", fontSize = 14.sp, color = AppColors.Blue,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text("Role: $role", fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(end = 8.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("Close", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        HorizontalDivider(color = AppColors.DividerDark)
        Column(modifier = Modifier.weight(1f).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(8.dp)) {
            if (role == "Initiator") {
                val srcAgent = activeAgents.firstOrNull()
                val srcBody = srcAgent?.responseBody
                Text("$provName / $activeMdl — report response",
                    fontSize = 13.sp, color = AppColors.Blue,
                    fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                if (srcBody.isNullOrBlank()) {
                    Text("(source response missing)", color = AppColors.TextTertiary, fontSize = 13.sp)
                } else {
                    ContentWithThinkSections(analysis = srcBody)
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 12.dp))
                l2Rows.forEach { row ->
                    val ansProv = AppService.findById(row.provider)?.displayName ?: row.provider
                    Text("$ansProv / ${row.model} — factcheck",
                        fontSize = 13.sp, color = AppColors.Green,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    val pair = row.pair
                    when {
                        pair == null -> Text("(no result)", color = AppColors.TextTertiary, fontSize = 13.sp)
                        pair.errorMessage != null ->
                            Text("❌ ${pair.errorMessage}", fontSize = 13.sp, color = AppColors.Red)
                        pair.content.isNullOrBlank() ->
                            Text("(pending)", fontSize = 13.sp, color = AppColors.TextSecondary)
                        else -> ContentWithThinkSections(analysis = pair.content)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = AppColors.DividerDark)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            } else {
                l2Rows.forEach { row ->
                    val src = agentsById[row.key]
                    val srcProv = AppService.findById(row.provider)?.displayName ?: row.provider
                    Text("$srcProv / ${row.model} — report response",
                        fontSize = 13.sp, color = AppColors.Blue,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    val srcBody = src?.responseBody
                    if (srcBody.isNullOrBlank()) {
                        Text("(source response missing)", color = AppColors.TextTertiary, fontSize = 13.sp)
                    } else {
                        ContentWithThinkSections(analysis = srcBody)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Factcheck", fontSize = 13.sp, color = AppColors.Green,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    val pair = row.pair
                    when {
                        pair == null -> Text("(no result)", color = AppColors.TextTertiary, fontSize = 13.sp)
                        pair.errorMessage != null ->
                            Text("❌ ${pair.errorMessage}", fontSize = 13.sp, color = AppColors.Red)
                        pair.content.isNullOrBlank() ->
                            Text("(pending)", fontSize = 13.sp, color = AppColors.TextSecondary)
                        else -> ContentWithThinkSections(analysis = pair.content)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = AppColors.DividerDark)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SecondaryRow(r: SecondaryResult, onClick: () -> Unit, onDelete: () -> Unit) {
    val provider = AppService.findById(r.providerId)?.displayName ?: r.providerId
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(r.timestamp))
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val statusEmoji = when {
            r.errorMessage != null -> "❌"
            r.content.isNullOrBlank() -> "⏳"
            else -> "✅"
        }
        Text(statusEmoji, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("$provider · ${r.model}", fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ts, fontSize = 11.sp, color = AppColors.TextTertiary)
        }
        IconButton(onClick = { confirmDelete = true }) {
            Text("🗑", fontSize = 16.sp, color = AppColors.Red)
        }
    }

    if (confirmDelete) {
        val noun = (r.metaPromptName?.takeIf { it.isNotBlank() }
            ?: com.ai.data.legacyKindDisplayName(r.kind)).lowercase()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this $noun?") },
            text = { Text("$provider · ${r.model}") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}

@Composable
internal fun SecondaryResultDetailScreen(
    result: SecondaryResult,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val providerService = AppService.findById(result.providerId)
    val provider = providerService?.displayName ?: result.providerId
    // Prefer the user-given Meta-prompt name over the legacy kind
    // label — every chat-type Meta runs under kind=META, so the kind
    // alone would surface "Meta" even when the user picked "Compare".
    val title = result.metaPromptName?.takeIf { it.isNotBlank() }
        ?: com.ai.data.legacyKindDisplayName(result.kind)
    var confirmDelete by remember { mutableStateOf(false) }

    // Find the trace file for this meta call: same report, same model,
    // and timestamp closest to the result. Multiple meta runs of the
    // same model on the same report would otherwise alias — the
    // closest-timestamp tiebreak picks the right one. May be null when
    // tracing was off at the time of the call.
    val traceFilenameState = produceState<String?>(initialValue = null, result.id) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == result.reportId && it.model == result.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - result.timestamp) }?.filename
        }
    }
    val traceFilename = traceFilenameState.value

    // Build the same id → "provider / model" map the @RESULTS@ block
    // used (success-ordered, 1-based) so the viewer can show real model
    // names instead of the bracketed [N] ids.
    val agentLabelsState = produceState(initialValue = emptyMap<Int, String>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext emptyMap<Int, String>()
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .mapIndexed { idx, agent ->
                    val provDisplay = AppService.findById(agent.provider)?.displayName ?: agent.provider
                    (idx + 1) to "$provDisplay / ${agent.model}"
                }.toMap()
        }
    }
    val agentLabels = agentLabelsState.value

    // Translation comparison: only meaningful for chat-type META
    // rows, and only when this secondary is a translated copy (its
    // translatedFromSecondaryId points at the source's untranslated
    // counterpart). Load the source's content lazily.
    val sourceContentState = produceState<String?>(
        initialValue = null,
        result.id, result.translatedFromSecondaryId
    ) {
        val srcId = result.translatedFromSecondaryId ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val parent = ReportStorage.getReport(context, result.reportId) ?: return@withContext null
            val srcReportId = parent.sourceReportId ?: return@withContext null
            SecondaryResultStorage.get(context, srcReportId, srcId)?.content
        }
    }
    val sourceContent = sourceContentState.value
    val canShowTranslation =
        result.kind == SecondaryKind.META &&
            !sourceContent.isNullOrBlank() && !result.content.isNullOrBlank()
    var showTranslationCompare by remember { mutableStateOf(false) }

    if (showTranslationCompare && sourceContent != null && result.content != null) {
        TranslationCompareScreen(
            title = "Translation info — $title — $provider / ${result.model}",
            originalLabel = "Original",
            originalContent = sourceContent,
            translatedLabel = "Translation",
            translatedContent = result.content,
            onBack = { showTranslationCompare = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "$title — $provider", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        // Model header with the trace 🐞 ladybug at the right — tapping
        // opens the closest-timestamp trace for this (report, model).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(result.model, fontSize = 13.sp, color = AppColors.Blue,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (ApiTracer.isTracingEnabled && traceFilename != null) {
                Text("🐞", fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp).clickable { traceFilename?.let(onNavigateToTraceFile) })
            }
        }
        Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(result.timestamp)),
            fontSize = 11.sp, color = AppColors.TextTertiary)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when {
                result.errorMessage != null -> {
                    Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                    Text(result.errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                result.content.isNullOrBlank() -> {
                    Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
                }
                result.kind == SecondaryKind.RERANK -> {
                    // Try to parse the structured JSON the rerank flow
                    // produces (chat-prompt path or callRerankApi path).
                    // Fall back to raw markdown rendering if the model's
                    // output deviated from the requested schema.
                    val rows = remember(result.content) { parseRerankRows(result.content) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = result.content)
                    } else {
                        RerankTable(rows = rows, agentLabels = agentLabels)
                    }
                }
                result.kind == SecondaryKind.MODERATION -> {
                    val rows = remember(result.content) { parseModerationRows(result.content) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = result.content)
                    } else {
                        ModerationTable(rows = rows, agentLabels = agentLabels)
                    }
                }
                else -> {
                    ContentWithThinkSections(analysis = result.content)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (canShowTranslation) {
            Button(
                onClick = { showTranslationCompare = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Translation info", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Delete", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { providerService?.let { onNavigateToModelInfo(it, result.model) } },
                enabled = providerService != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Model", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${title.lowercase()}?") },
            text = { Text("$provider · ${result.model}") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}

internal data class RerankRow(val id: Int, val rank: Int?, val score: Int?, val reason: String?)

/** Parse the rerank flow's structured JSON output. Both the chat-prompt
 *  path and the dedicated rerank-API path emit the same
 *  `[{id, rank, score, reason}, ...]` shape. ``` fences are tolerated;
 *  any other shape returns null so the caller can fall back to raw
 *  rendering. */
internal fun parseRerankRows(content: String): List<RerankRow>? {
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = try {
        @Suppress("DEPRECATION")
        com.google.gson.JsonParser().parse(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { null } ?: return null
    if (arr.size() == 0) return null
    val rows = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val rank = obj.get("rank")?.takeIf { it.isJsonPrimitive }?.asInt
        val score = obj.get("score")?.takeIf { it.isJsonPrimitive }?.asNumber?.toInt()
        val reason = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        RerankRow(id, rank, score, reason)
    }
    if (rows.isEmpty()) return null
    return rows.sortedBy { it.rank ?: Int.MAX_VALUE }
}

@Composable
internal fun RerankTable(rows: List<RerankRow>, agentLabels: Map<Int, String>) {
    val hColor = AppColors.Blue
    val hSize = 12.sp
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Rank", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Model", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(220.dp).padding(start = 8.dp))
                Text("Score", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Reason", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            rows.forEach { r ->
                val label = agentLabels[r.id] ?: "[${r.id}] (unknown)"
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(r.rank?.toString() ?: "—", fontSize = 12.sp, color = AppColors.TextSecondary,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(label, fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.score?.toString() ?: "", fontSize = 12.sp, color = AppColors.Green,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(r.reason.orEmpty(), fontSize = 12.sp, color = AppColors.TextTertiary,
                        modifier = Modifier.widthIn(min = 200.dp, max = 360.dp).padding(start = 8.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            }
        }
    }
}

internal data class ModerationRow(
    val id: Int,
    val flagged: Boolean,
    val firedCategories: List<String>,
    /** All non-zero scores, sorted high → low so the table can show the
     *  top contributors even when nothing crossed the boolean threshold. */
    val topScores: List<Pair<String, Double>>
)

/** Parse the JSON [callModerationApi] writes into the SecondaryResult.
 *  Same `[{id, flagged, categories, scores}, …]` shape callRerankApi
 *  uses; tolerates ``` fences. Returns null on bad input. */
internal fun parseModerationRows(content: String): List<ModerationRow>? {
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = try {
        @Suppress("DEPRECATION")
        com.google.gson.JsonParser().parse(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { null } ?: return null
    if (arr.size() == 0) return null
    val rows = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val flagged = obj.get("flagged")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val cats = obj.getAsJsonObject("categories")
        val scores = obj.getAsJsonObject("scores")
        val fired = cats?.entrySet()?.filter { it.value.asBoolean }?.map { it.key } ?: emptyList()
        val scoreList = scores?.entrySet()?.mapNotNull {
            val v = try { it.value.asDouble } catch (_: Exception) { return@mapNotNull null }
            it.key to v
        }?.sortedByDescending { it.second }?.take(3) ?: emptyList()
        ModerationRow(id, flagged, fired, scoreList)
    }
    if (rows.isEmpty()) return null
    return rows
}

@Composable
internal fun ModerationTable(rows: List<ModerationRow>, agentLabels: Map<Int, String>) {
    val hColor = AppColors.Blue
    val hSize = 12.sp
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Flag", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(40.dp))
                Text("Model", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(220.dp).padding(start = 8.dp))
                Text("Categories fired", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(220.dp).padding(start = 8.dp))
                Text("Top scores", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            rows.forEach { r ->
                val label = agentLabels[r.id] ?: "[${r.id}] (unknown)"
                val firedText = if (r.firedCategories.isEmpty()) "—" else r.firedCategories.joinToString(", ")
                val scoresText = r.topScores.joinToString(", ") { (k, v) -> "$k=${"%.3f".format(v)}" }
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(if (r.flagged) "🚩" else "✓", fontSize = 13.sp,
                        color = if (r.flagged) AppColors.Red else AppColors.Green,
                        modifier = Modifier.width(40.dp))
                    Text(label, fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(firedText, fontSize = 12.sp,
                        color = if (r.flagged) AppColors.Red else AppColors.TextTertiary,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp))
                    Text(scoresText, fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.widthIn(min = 200.dp).padding(start = 8.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            }
        }
    }
}
