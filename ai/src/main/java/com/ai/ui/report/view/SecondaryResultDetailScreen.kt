package com.ai.ui.report.view
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

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
import com.ai.ui.shared.horizontalSwipeNavigation
import com.ai.ui.shared.modelInfoClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext

@Composable
internal fun SecondaryResultDetailScreen(
    result: SecondaryResult,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    /** When non-null, suppress the per-screen language picker and lock
     *  content to this language. Same convention as ReportsViewerScreen
     *  / SecondaryResultsScreen: null = picker mode (Report - Manage
     *  path), "" = locked to Original, non-empty = locked displayName. */
    forcedLanguage: String? = null,
    /** Delete a specific SecondaryResult by id — used by the
     *  multi-language delete popup's "Active language only" path to
     *  drop just the active-language TRANSLATE row while keeping the
     *  META and its other translations. Default no-op so legacy
     *  callers that don't wire it still work for the "All languages"
     *  path. */
    onDeleteRowById: (String) -> Unit = { _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val providerService = AppService.findById(result.providerId)
    val provider = providerService?.id ?: result.providerId
    // Prefer the user-given Meta-prompt name over the legacy kind
    // label — every chat-type Meta runs under kind=META, so the kind
    // alone would surface "Meta" even when the user picked "Compare".
    val title = result.metaPromptName?.takeIf { it.isNotBlank() }
        ?: com.ai.data.legacyKindDisplayName(result.kind)
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLangChoice by remember { mutableStateOf(false) }

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
    val baseTraceFilename = traceFilenameState.value

    // Load TRANSLATE secondaries for this report — drives the language
    // icon picker for chat-type META rows (same UX as View → Prompt /
    // Model response). When the user selects a non-original language
    // the picker overlay swaps content, trace, copy/share onto the
    // matching TRANSLATE row.
    val translatesState = produceState(initialValue = emptyList<SecondaryResult>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, result.reportId, SecondaryKind.TRANSLATE)
                .filter { !it.content.isNullOrBlank() }
        }
    }
    val translates = translatesState.value
    // Pull the parent report — needed both for the title bar icon
    // below and so we can read languageName here to fold any
    // back-translation rows into the Original tab.
    val parentReportState = produceState<com.ai.data.Report?>(initialValue = null, result.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, result.reportId) }
    }
    val parentReport = parentReportState.value
    val reportLanguageName = parentReport?.languageName?.takeIf { it.isNotBlank() }
    // Only show language tabs where THIS meta actually has content —
    // either the seed-language row itself (result.targetLanguage) or
    // a cross-translate TRANSLATE row pointing back at it. Include
    // Original when this meta was run in Original OR a META TRANSLATE
    // to reportLanguageName exists (back-translation). reportLanguageName
    // also folds into Original via buildLangTabs's originalAlias so we
    // don't show a duplicate "English" tab.
    val langTabs = remember(translates, result.id, result.targetLanguage, reportLanguageName) {
        val mineTranslates = translates.filter {
            it.translateSourceKind == "META" && it.translateSourceTargetId == result.id
        }
        // Synthesise a fake TRANSLATE row tagged with the result's own
        // language so buildLangTabs surfaces a tab for the seed
        // language even when no one cross-translated INTO this same
        // language.
        val seedTab: SecondaryResult? = result.targetLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
            result.copy(
                kind = SecondaryKind.TRANSLATE,
                targetLanguage = lang,
                targetLanguageNative = result.targetLanguageNative ?: lang
            )
        }
        val combined = if (seedTab != null) mineTranslates + seedTab else mineTranslates
        val hasOriginalContent = result.targetLanguage.isNullOrBlank() ||
            (reportLanguageName != null && mineTranslates.any { it.targetLanguage == reportLanguageName })
        buildLangTabs(
            combined,
            includeOriginal = hasOriginalContent,
            originalAlias = reportLanguageName
        )
    }
    // Default the picker to the tab matching the result's own
    // language so opening a French-seed META highlights "French"
    // immediately rather than defaulting to Original (which would
    // render "(no content)" for a seed-only meta).
    var pickerLangKey by rememberSaveable(result.id) {
        val target = result.targetLanguage
        mutableStateOf(
            if (target.isNullOrBlank()) LangTab.ORIGINAL_KEY
            else target.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
        )
    }
    LaunchedEffect(langTabs, forcedLanguage) {
        if (forcedLanguage == null && langTabs.none { it.key == pickerLangKey }) {
            pickerLangKey = LangTab.ORIGINAL_KEY
        }
    }
    val selectedLangKey: String = if (forcedLanguage != null) {
        if (forcedLanguage.isEmpty()) LangTab.ORIGINAL_KEY
        else forcedLanguage.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
    } else pickerLangKey
    val activeLangName: String? = when {
        selectedLangKey == LangTab.ORIGINAL_KEY -> null
        forcedLanguage != null && forcedLanguage.isNotEmpty() -> forcedLanguage
        else -> langTabs.firstOrNull { it.key == selectedLangKey }?.displayName
    }
    // For META kinds: when the picked language differs from the
    // result's own language, find the TRANSLATE row that targets this
    // meta in the picked language. Non-META kinds (RERANK / MODERATION)
    // have no language variants — pass-through.
    val activeTranslateRow: SecondaryResult? = remember(translates, selectedLangKey, result.id, activeLangName, reportLanguageName) {
        if (result.kind != SecondaryKind.META) null
        else if (activeLangName == result.targetLanguage) null
        else if (activeLangName == null) {
            // Original tab selected. The result itself is in some
            // non-Original language; look for a back-translation
            // TRANSLATE row tagged with reportLanguageName.
            if (reportLanguageName == null) null
            else translates.firstOrNull {
                it.translateSourceKind == "META" &&
                    it.translateSourceTargetId == result.id &&
                    it.targetLanguage == reportLanguageName
            }
        }
        else translates.firstOrNull {
            it.translateSourceKind == "META" &&
                it.translateSourceTargetId == result.id &&
                it.targetLanguage == activeLangName
        }
    }
    // For non-META and same-language tabs, show the result's own
    // content. For a different-language META tab, show only the
    // translation (null if none exists — caller renders a "(no
    // translation)" placeholder rather than falling back to the
    // foreign-language source).
    val displayContent: String? = when {
        result.kind != SecondaryKind.META -> result.content
        activeLangName == result.targetLanguage -> result.content
        else -> activeTranslateRow?.content
    }
    val traceFilename = activeTranslateRow?.traceFile?.takeIf { it.isNotBlank() } ?: baseTraceFilename

    // (parentReport already loaded above so we could read languageName
    // for the langTabs fold.)

    // Build the same id → "provider / model" map the @RESULTS@ block
    // used (success-ordered, 1-based) so the viewer can show real model
    // names instead of the bracketed [N] ids. Also build the parallel
    // id → response body map — used by the per-row moderation detail
    // screen to surface the exact text that was moderated.
    val agentLabelsState = produceState(initialValue = emptyMap<Int, String>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext emptyMap<Int, String>()
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .mapIndexed { idx, agent ->
                    val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                    (idx + 1) to "$provDisplay / ${agent.model}"
                }.toMap()
        }
    }
    val agentLabels = agentLabelsState.value
    val agentResponsesState = produceState(initialValue = emptyMap<Int, String>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext emptyMap<Int, String>()
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .mapIndexed { idx, agent -> (idx + 1) to (agent.responseBody ?: "") }
                .toMap()
        }
    }
    val agentResponses = agentResponsesState.value

    // Per-row moderation drill-in — clicking a row in the moderation
    // table sets this to that row; the detail screen renders full
    // screen until back / dismiss clears it.
    var openModerationRow by remember { mutableStateOf<ModerationRow?>(null) }
    val activeModRow = openModerationRow
    if (activeModRow != null) {
        ModerationCallDetailScreen(
            row = activeModRow,
            agentLabel = agentLabels[activeModRow.id] ?: "[${activeModRow.id}]",
            agentResponse = agentResponses[activeModRow.id].orEmpty(),
            moderationModelLabel = com.ai.ui.shared.modelLabel(provider, result.model, separator = " / "),
            onBack = { openModerationRow = null }
        )
        return
    }

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
        val tf = result.traceFile
        val translatedIcon = result.targetLanguage?.takeIf { it.isNotBlank() }
            ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
        TranslationCompareScreen(
            title = "Translation info — $title — ${com.ai.ui.shared.modelLabel(provider, result.model, separator = " / ")}",
            originalLabel = "Original",
            originalContent = sourceContent,
            translatedLabel = "Translation",
            translatedContent = result.content,
            onBack = { showTranslationCompare = false },
            onNavigateHome = onNavigateHome,
            onTrace = tf?.let { fn -> { onNavigateToTraceFile(fn) } },
            onDelete = {
                onDelete()
                showTranslationCompare = false
            },
            originalIcon = parentReport?.languageIcon,
            translatedIcon = translatedIcon
        )
        return
    }

    // New-style translation compare: fires when the active picker
    // language renders a TRANSLATE overlay on top of this META
    // (activeTranslateRow != null). Source = the seed META's own
    // content; translation = the overlay TRANSLATE row's content.
    // Wired to the title bar ↔ icon via onTranslationCompare.
    var showLiveTranslationCompare by remember { mutableStateOf(false) }
    val liveTranslateActive = activeTranslateRow
    if (showLiveTranslationCompare && liveTranslateActive != null && !result.content.isNullOrBlank() && !liveTranslateActive.content.isNullOrBlank()) {
        val sourceLangLabel = result.targetLanguage?.takeIf { it.isNotBlank() } ?: "Original"
        val translatedLangLabel = liveTranslateActive.targetLanguage?.takeIf { it.isNotBlank() } ?: activeLangName ?: "Translation"
        val tf = liveTranslateActive.traceFile
        val sourceIcon = result.targetLanguage?.takeIf { it.isNotBlank() }
            ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
            ?: parentReport?.languageIcon
        val translatedIcon = liveTranslateActive.targetLanguage?.takeIf { it.isNotBlank() }
            ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
        TranslationCompareScreen(
            title = "Translation — $title",
            originalLabel = sourceLangLabel,
            originalContent = result.content,
            translatedLabel = translatedLangLabel,
            translatedContent = liveTranslateActive.content,
            onBack = { showLiveTranslationCompare = false },
            onNavigateHome = onNavigateHome,
            onTrace = tf?.let { fn -> { onNavigateToTraceFile(fn) } },
            onDelete = {
                onDeleteRowById(liveTranslateActive.id)
                showLiveTranslationCompare = false
            },
            originalIcon = sourceIcon,
            translatedIcon = translatedIcon
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        val traceEnabled = ApiTracer.isTracingEnabled && traceFilename != null
        // 👁 → matching View sub-screen, per-kind dispatch.
        // RERANK → Rerank, MODERATION → Moderation, META → Meta /
        // FanIn / FanInModel by flavour. State lives in
        // ReportsScreenNav via LocalPendingViewOverManage; the
        // top-of-chain block in ReportPrimaryOverlays consumes it.
        val pendingViewHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingViewHolder?.let { holder ->
            {
                holder.value = when {
                    result.kind == SecondaryKind.RERANK -> com.ai.ui.shared.ViewJump.Rerank(result.id)
                    result.kind == SecondaryKind.MODERATION -> com.ai.ui.shared.ViewJump.Moderation(result.id)
                    result.kind == SecondaryKind.META && result.fanInOf != null -> com.ai.ui.shared.ViewJump.FanIn(result.id)
                    result.kind == SecondaryKind.META && (result.scopeProviderId != null || result.scopeModel != null) -> com.ai.ui.shared.ViewJump.FanInModel(result.id)
                    result.kind == SecondaryKind.META -> com.ai.ui.shared.ViewJump.Meta(result.id)
                    else -> com.ai.ui.shared.ViewJump.Main
                }
            }
        }
        TitleBar(
            helpTopic = "secondary_detail",
            title = "Secondary detail",
            reportIcon = parentReport?.icon?.takeIf { it.isNotBlank() } ?: "📝",
            subject = title,
            onBackClick = onBack,
            onTrace = if (traceEnabled) { { onNavigateToTraceFile(traceFilename!!) } } else null,
            onDelete = {
                // Multi-language items get the 3-button popup so the
                // user can drop just the active-language rendering vs.
                // every language at once. Single-language items keep
                // the existing single-confirm dialog.
                if (langTabs.size > 1) confirmLangChoice = true
                else confirmDelete = true
            },
            onOpenView = onOpenViewJump,
            onInfo = if (providerService != null) { { onNavigateToModelInfo(providerService, result.model) } } else null,
            onTranslationCompare = if (liveTranslateActive != null && !result.content.isNullOrBlank() && !liveTranslateActive.content.isNullOrBlank()) {
                { showLiveTranslationCompare = true }
            } else null,
            onCopy = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.copyToClipboard(context, body, "secondary result") }
            },
            onShare = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.shareText(context, body, "${result.kind.name} — $title") }
            }
        )
        com.ai.ui.shared.HardcodedSubjectRow(title)
        if (result.kind == SecondaryKind.META && langTabs.size > 1 && forcedLanguage == null) {
            LanguagePickerRow(
                langTabs, selectedLangKey,
                onSelect = { pickerLangKey = it },
                useIcons = true,
                originalIcon = parentReport?.languageIcon
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(com.ai.ui.shared.shortModelName(result.model), fontSize = 13.sp, color = AppColors.Blue,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when {
                result.errorMessage != null -> {
                    Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                    Text(result.errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                displayContent.isNullOrBlank() -> {
                    val msg = if (result.kind == SecondaryKind.META && activeLangName != null && activeLangName != result.targetLanguage)
                        "(no translation for this language yet)"
                    else "(no content)"
                    Text(msg, color = AppColors.TextTertiary, fontSize = 13.sp)
                }
                result.kind == SecondaryKind.RERANK -> {
                    // Try to parse the structured JSON the rerank flow
                    // produces (chat-prompt path or callRerankApi path).
                    // Fall back to raw markdown rendering if the model's
                    // output deviated from the requested schema.
                    val rows = remember(displayContent) { parseRerankRows(displayContent) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = displayContent)
                    } else {
                        RerankTable(rows = rows, agentLabels = agentLabels)
                    }
                }
                result.kind == SecondaryKind.MODERATION -> {
                    val rows = remember(displayContent) { parseModerationRows(displayContent) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = displayContent)
                    } else {
                        ModerationTable(
                            rows = rows,
                            agentLabels = agentLabels,
                            onRowClick = { openModerationRow = it }
                        )
                    }
                }
                else -> {
                    ContentWithThinkSections(analysis = displayContent)
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
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${title.lowercase()}?") },
            text = { Text(com.ai.ui.shared.modelLabel(provider, result.model)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    if (confirmLangChoice) {
        val activeLabel = activeLangName ?: "Original"
        AlertDialog(
            onDismissRequest = { confirmLangChoice = false },
            title = { Text("Delete this ${title.lowercase()}?") },
            text = {
                Text("Active language: $activeLabel.\n\n" +
                    "\"Active language only\" drops just this language's content. " +
                    "\"All languages\" removes the source and every translation.")
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        confirmLangChoice = false
                        // If the active view is a per-language TRANSLATE
                        // overlay (active != the meta's own language),
                        // drop just that TRANSLATE row by id. Otherwise
                        // the active view IS the seed meta — falling
                        // through to onDelete() removes the meta and
                        // cascades its translations, same as the
                        // "All languages" branch.
                        val tr = activeTranslateRow
                        if (tr != null) {
                            onDeleteRowById(tr.id)
                            onBack()
                        } else {
                            onDelete()
                        }
                    }) {
                        Text("Active language only", color = AppColors.Red, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = {
                        confirmLangChoice = false
                        onDelete()
                    }) {
                        Text("All languages", color = AppColors.Red, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = { confirmLangChoice = false }) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            }
        )
    }
}

