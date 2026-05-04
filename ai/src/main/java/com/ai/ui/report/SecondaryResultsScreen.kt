package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
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
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
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

    val openResult = openId?.let { id -> results.firstOrNull { it.id == id } }
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
    val title = nameFilter ?: com.ai.data.legacyKindDisplayName(kind)
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
                    "No ${title.lowercase()} for $selectedLanguageName yet"
                else "No ${title.lowercase()} for this report"
                Text(msg, color = AppColors.TextSecondary, fontSize = 14.sp)
            }
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
