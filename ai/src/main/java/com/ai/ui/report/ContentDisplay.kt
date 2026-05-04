package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ai.data.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val conclusionTagRegex = Regex("<conclusion>.*?</conclusion>", RegexOption.DOT_MATCHES_ALL)
private val motivationTagRegex = Regex("<motivation>.*?</motivation>", RegexOption.DOT_MATCHES_ALL)

@Composable
fun ReportsViewerScreen(
    reportId: String,
    initialSelectedAgentId: String? = null,
    initialSection: String? = null,  // "prompt" / "costs" — driven from the Report Result View buttons
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit = onDismiss,
    onNavigateToTraceFile: (String) -> Unit = {}
) {
    BackHandler { onDismiss() }
    val context = LocalContext.current
    // IO load → keeps the UI thread free while reading JSON. The
    // initial Loading sentinel distinguishes "still reading from disk"
    // from "read finished, file genuinely missing" so the empty-state
    // text only appears after the load completes.
    val reportState = produceState<ReportLoadState>(initialValue = ReportLoadState.Loading, reportId) {
        val r = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
        value = if (r != null) ReportLoadState.Loaded(r) else ReportLoadState.NotFound
    }

    when (val s = reportState.value) {
        ReportLoadState.Loading -> {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                TitleBar(title = "View Reports", onBackClick = onDismiss, onAiClick = onNavigateHome)
            }
            return
        }
        ReportLoadState.NotFound -> {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                TitleBar(title = "View Reports", onBackClick = onDismiss, onAiClick = onNavigateHome)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Report not found", color = AppColors.TextSecondary, fontSize = 16.sp)
                }
            }
            return
        }
        is ReportLoadState.Loaded -> {
            ReportsViewerScreenLoaded(s.report, initialSelectedAgentId, initialSection,
                onDismiss, onNavigateHome, onNavigateToTraceFile)
        }
    }
}

private sealed interface ReportLoadState {
    data object Loading : ReportLoadState
    data object NotFound : ReportLoadState
    data class Loaded(val report: Report) : ReportLoadState
}

/** A single tab in a report's language picker. Built from the
 *  TRANSLATE secondaries on the report — Original always present;
 *  Dutch / German / … added per distinct [SecondaryResult.targetLanguage].
 *  Used by the in-app report viewer (ContentDisplay) and the per-kind
 *  per-Meta-prompt result lists (SecondaryResultsScreen). [key] is a
 *  filesystem-safe lowercase id; [displayName] is the human English
 *  name and matches [SecondaryResult.targetLanguage]. */
internal data class LangTab(val key: String, val displayName: String, val nativeName: String?) {
    companion object { const val ORIGINAL_KEY = "original" }
}

@Composable
internal fun LanguagePickerRow(
    languages: List<LangTab>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
) {
    if (languages.size <= 1) return
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        languages.forEach { lang ->
            val isSelected = lang.key == selectedKey
            Button(
                onClick = { onSelect(lang.key) },
                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) AppColors.Green else Color(0xFF3A3A4A)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(
                    lang.displayName,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, softWrap = false
                )
            }
        }
    }
}

/** Group TRANSLATE secondaries by language and produce the picker
 *  list. "Original" is always first. */
internal fun buildLangTabs(translates: List<SecondaryResult>): List<LangTab> {
    val out = mutableListOf(LangTab(LangTab.ORIGINAL_KEY, "Original", null))
    val seen = LinkedHashMap<String, String?>()
    translates.forEach { t ->
        val lang = t.targetLanguage?.takeIf { it.isNotBlank() } ?: return@forEach
        if (lang !in seen) seen[lang] = t.targetLanguageNative
    }
    seen.forEach { (lang, native) ->
        val key = lang.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
        out += LangTab(key, lang, native?.takeIf { it != lang })
    }
    return out
}

@Composable
private fun ReportsViewerScreenLoaded(
    report: Report,
    initialSelectedAgentId: String?,
    initialSection: String?,
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit
) {
    val context = LocalContext.current

    // Load TRANSLATE secondaries up front; the picker / overlay both
    // key on this list. Empty list → no picker shown, viewer behaves
    // as before.
    val translatesState = produceState(initialValue = emptyList<SecondaryResult>(), report.id) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, report.id, SecondaryKind.TRANSLATE)
                .filter { !it.content.isNullOrBlank() }
        }
    }
    val translates = translatesState.value
    val langTabs = remember(translates) { buildLangTabs(translates) }
    var selectedLangKey by remember { mutableStateOf("original") }
    // Keep the selection valid if translations finish loading after
    // first composition — if the previously chosen key dropped off the
    // list (e.g. a translation was deleted) snap back to Original.
    LaunchedEffect(langTabs) {
        if (langTabs.none { it.key == selectedLangKey }) selectedLangKey = "original"
    }
    // Quick lookups for the active language: by-targetId → translated
    // content. Recomputed only when the selected language or the
    // translation list changes.
    val translationByTarget = remember(translates, selectedLangKey) {
        if (selectedLangKey == "original") emptyMap()
        else {
            val tab = langTabs.firstOrNull { it.key == selectedLangKey }
            val langName = tab?.displayName ?: return@remember emptyMap()
            translates
                .filter { it.targetLanguage == langName }
                .associate {
                    val k = (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "")
                    k to (it.content ?: "")
                }
        }
    }

    // Single-section variants: just the prompt, or just the cost table — no agent picker,
    // no per-agent body. These come from the View row's Prompt / Costs buttons.
    if (initialSection == "prompt" || initialSection == "costs") {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val title = if (initialSection == "prompt") "Prompt" else "Cost summary"
            TitleBar(title = title, onBackClick = onDismiss, onAiClick = onNavigateHome)
            // Costs aggregate every API call (including translation
            // calls) so the language picker doesn't apply — only the
            // prompt screen shows the picker.
            if (initialSection == "prompt") {
                LanguagePickerRow(langTabs, selectedLangKey, onSelect = { selectedLangKey = it })
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                if (initialSection == "prompt") {
                    val displayPrompt = translationByTarget["PROMPT:prompt"] ?: report.prompt
                    if (displayPrompt.isBlank()) {
                        Text("(no prompt recorded)", color = AppColors.TextTertiary, fontSize = 14.sp)
                    } else {
                        Text(displayPrompt, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp)
                    }
                } else {
                    val hasAgentCosts = report.agents.any { it.tokenUsage != null && (it.reportStatus == ReportStatus.SUCCESS || it.reportStatus == ReportStatus.ERROR) }
                    val hasSecondaryCostsState = produceState(initialValue = false, report.id) {
                        value = withContext(Dispatchers.IO) {
                            SecondaryResultStorage.listForReport(context, report.id).any { it.tokenUsage != null }
                        }
                    }
                    if (!hasAgentCosts && !hasSecondaryCostsState.value) {
                        Text("(no usage recorded)", color = AppColors.TextTertiary, fontSize = 14.sp)
                    } else {
                        ReportCostTable(report = report)
                    }
                }
            }
        }
        return
    }

    val agentsWithResults = remember(report) {
        report.agents.filter { it.reportStatus == ReportStatus.SUCCESS }.sortedBy { it.agentName.lowercase() }
    }
    var selectedAgentId by remember { mutableStateOf(initialSelectedAgentId ?: agentsWithResults.firstOrNull()?.agentId) }
    val selectedReportAgent = selectedAgentId?.let { id -> report.agents.find { it.agentId == id } }
    val scrollState = rememberScrollState()
    LaunchedEffect(selectedAgentId) { scrollState.scrollTo(0) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val providerName = selectedReportAgent?.let { AppService.findById(it.provider)?.displayName ?: it.provider } ?: "View Reports"
        TitleBar(title = providerName, onBackClick = onDismiss, onAiClick = onNavigateHome)

        LanguagePickerRow(langTabs, selectedLangKey, onSelect = { selectedLangKey = it })

        // Agent buttons
        if (agentsWithResults.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                agentsWithResults.forEach { agent ->
                    val isSelected = agent.agentId == selectedAgentId
                    Button(
                        onClick = { selectedAgentId = agent.agentId },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) AppColors.Purple else Color(0xFF3A3A4A)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.heightIn(min = 40.dp)
                    ) { Text(agent.agentName, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                }
            }

            if (selectedReportAgent != null) {
                val provDisplay = AppService.findById(selectedReportAgent.provider)?.displayName ?: selectedReportAgent.provider
                // Trace lookup hoisted next to the model header so the
                // \ud83d\udc1e ladybug can sit at the top of the screen instead of
                // a separate Trace button below the body.
                val traceFilenameState = produceState<String?>(initialValue = null, report.id, selectedReportAgent.model, selectedReportAgent.agentId) {
                    value = withContext(Dispatchers.IO) {
                        ApiTracer.getTraceFiles()
                            .filter { it.reportId == report.id && it.model == selectedReportAgent.model }
                            .maxByOrNull { it.timestamp }?.filename
                    }
                }
                val headerTraceFilename = traceFilenameState.value
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$provDisplay \u2014 ${selectedReportAgent.model}",
                        fontSize = 18.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    if (ApiTracer.isTracingEnabled && headerTraceFilename != null) {
                        Text("\ud83d\udc1e", fontSize = 18.sp,
                            modifier = Modifier.padding(start = 8.dp).clickable { onNavigateToTraceFile(headerTraceFilename) })
                    }
                }
            }
        }

        // Content
        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            if (agentsWithResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No successful reports to display", color = AppColors.TextSecondary, fontSize = 16.sp)
                }
            } else if (selectedReportAgent?.responseBody != null) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    val translated = translationByTarget["AGENT:${selectedReportAgent.agentId}"]
                    val rawBody = translated ?: (selectedReportAgent.responseBody ?: "")
                    val conclusion = extractTagContent(rawBody, "conclusion")
                    val motivation = extractTagContent(rawBody, "motivation")
                    val strippedBody = if (conclusion != null || motivation != null)
                        rawBody.replace(conclusionTagRegex, "").replace(motivationTagRegex, "").trim()
                    else rawBody

                    if (conclusion != null) {
                        Text("Conclusion", fontSize = 18.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        ContentWithThinkSections(analysis = conclusion)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (motivation != null) {
                        Text("Motivation", fontSize = 18.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        ContentWithThinkSections(analysis = motivation)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (strippedBody.isNotBlank()) {
                        if (conclusion != null || motivation != null) {
                            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        ContentWithThinkSections(analysis = strippedBody)
                    }

                    selectedReportAgent.citations?.takeIf { it.isNotEmpty() }?.let { Spacer(modifier = Modifier.height(16.dp)); CitationsSection(it) }
                    selectedReportAgent.searchResults?.takeIf { it.isNotEmpty() }?.let { Spacer(modifier = Modifier.height(16.dp)); SearchResultsSection(it) }
                    selectedReportAgent.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { Spacer(modifier = Modifier.height(16.dp)); RelatedQuestionsSection(it) }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No analysis available", color = AppColors.TextSecondary, fontSize = 16.sp)
                }
            }
        }
    }
}

// ===== Cost Table =====

@Composable
fun ReportCostTable(report: Report) {
    val context = LocalContext.current
    val agentsWithCosts = remember(report) {
        report.agents.filter { it.tokenUsage != null && (it.reportStatus == ReportStatus.SUCCESS || it.reportStatus == ReportStatus.ERROR) }
    }
    val secondaryState = produceState(initialValue = emptyList<SecondaryResult>(), report.id) {
        value = withContext(Dispatchers.IO) { SecondaryResultStorage.listForReport(context, report.id) }
    }
    val secondary = secondaryState.value
    if (agentsWithCosts.isEmpty() && secondary.isEmpty()) return

    data class CostRow(val type: String, val providerDisplay: String, val model: String, val tier: String, val durationMs: Long?, val inputTokens: Int, val outputTokens: Int, val inputCents: Double, val outputCents: Double)

    val agentRows = agentsWithCosts.map { agent ->
        val providerEnum = AppService.findById(agent.provider)
        val tu = agent.tokenUsage!!
        val pricing = providerEnum?.let { PricingCache.getPricing(context, it, agent.model) }
        val inCents = (pricing?.let { tu.inputTokens * it.promptPrice } ?: 0.0) * 100
        val outCents = (pricing?.let { tu.outputTokens * it.completionPrice } ?: 0.0) * 100
        CostRow("report", providerEnum?.displayName ?: agent.provider, agent.model, pricing?.source ?: "", agent.durationMs, tu.inputTokens, tu.outputTokens, inCents, outCents)
    }
    // Rerank / summarize call costs end up alongside the report rows so the
    // user sees one consolidated breakdown \u2014 distinguished by the new Type
    // column.
    val secondaryRows = secondary.mapNotNull { s ->
        val tu = s.tokenUsage ?: return@mapNotNull null
        val providerEnum = AppService.findById(s.providerId)
        val providerDisplay = providerEnum?.displayName ?: s.providerId
        val pricing = providerEnum?.let { PricingCache.getPricing(context, it, s.model) }
        val inCents = (s.inputCost ?: 0.0) * 100
        val outCents = (s.outputCost ?: 0.0) * 100
        // Cost-table "Type" column: prefer the user-given Meta prompt
        // name so a "Compare" row reads "compare", a "Critique" row
        // reads "critique", etc. Rerank / moderation / translate keep
        // their fixed labels — those routing labels are the user's
        // mental model for those rows.
        val type = s.metaPromptName?.takeIf { it.isNotBlank() }?.lowercase()
            ?: when (s.kind) {
                SecondaryKind.RERANK -> "rerank"
                SecondaryKind.META -> "meta"
                SecondaryKind.MODERATION -> "moderation"
                SecondaryKind.TRANSLATE -> "translate"
            }
        CostRow(type, providerDisplay, s.model, pricing?.source ?: "", s.durationMs, tu.inputTokens, tu.outputTokens, inCents, outCents)
    }
    val rows = (agentRows + secondaryRows).sortedByDescending { it.inputCents + it.outputCents }

    var totalIn = 0; var totalOut = 0; var totalInC = 0.0; var totalOutC = 0.0
    rows.forEach { totalIn += it.inputTokens; totalOut += it.outputTokens; totalInC += it.inputCents; totalOutC += it.outputCents }

    fun fmtC(v: Double) = "%.2f".format(v)
    fun fmtS(ms: Long?) = if (ms != null) "%.1f".format(ms / 1000.0) else ""
    fun fmtT(n: Int) = "%,d".format(n)

    val hColor = AppColors.Blue; val vColor = AppColors.TextSecondary; val tColor = AppColors.Blue
    val hSize = 11.sp; val vSize = 11.sp

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Row {
                    Text("Total \u00A2", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Type", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp).padding(start = 8.dp))
                    Text("Provider", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
                    Text("Model", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
                    Text("Tier", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                    Text("Sec", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("In tok", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Out tok", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("In \u00A2", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Out \u00A2", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp, modifier = Modifier.width(704.dp))
                rows.forEach { r ->
                    val typeColor = when (r.type) { "rerank" -> AppColors.Orange; "summarize" -> AppColors.Indigo; "compare" -> AppColors.Purple; else -> vColor }
                    val tierColor = when (r.tier) {
                        "OVERRIDE" -> AppColors.Orange; "OPENROUTER" -> AppColors.Blue; "LITELLM" -> AppColors.Purple
                        else -> AppColors.TextDim
                    }
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(fmtC(r.inputCents + r.outputCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(r.type, fontSize = vSize, color = typeColor, modifier = Modifier.width(70.dp).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.providerDisplay, fontSize = vSize, color = vColor, modifier = Modifier.width(90.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.model, fontSize = vSize, color = vColor, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                        Text(r.tier, fontSize = vSize, color = tierColor, modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmtS(r.durationMs), fontSize = vSize, color = vColor, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtT(r.inputTokens), fontSize = vSize, color = vColor, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtT(r.outputTokens), fontSize = vSize, color = vColor, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtC(r.inputCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtC(r.outputCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp, modifier = Modifier.width(704.dp))
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(fmtC(totalInC + totalOutC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text("Total", fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(360.dp).padding(start = 8.dp))
                    Text("", fontSize = vSize, modifier = Modifier.width(48.dp))
                    Text(fmtT(totalIn), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text(fmtT(totalOut), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text(fmtC(totalInC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text(fmtC(totalOutC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ===== Content Parsing =====

private sealed class ContentSegment {
    data class Text(val content: String) : ContentSegment()
    data class Think(val content: String) : ContentSegment()
    data class Table(val table: MarkdownTable) : ContentSegment()
}

private fun parseContentWithThinkSections(text: String): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    val thinkPattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    var lastEnd = 0
    thinkPattern.findAll(text).forEach { match ->
        if (match.range.first > lastEnd) {
            val before = text.substring(lastEnd, match.range.first).trim()
            if (before.isNotEmpty()) appendTextWithTables(segments, before)
        }
        val thinkContent = match.groupValues[1].trim()
        if (thinkContent.isNotEmpty()) segments.add(ContentSegment.Think(thinkContent))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        val remaining = text.substring(lastEnd).trim()
        if (remaining.isNotEmpty()) appendTextWithTables(segments, remaining)
    }
    if (segments.isEmpty() && text.isNotBlank()) appendTextWithTables(segments, text)
    return segments
}

/** Split a Text chunk further on GFM table blocks. Each table becomes
 *  its own segment so it can render via a Compose Row/Column grid
 *  rather than getting flattened to AnnotatedString (which has no
 *  table primitive). */
private fun appendTextWithTables(segments: MutableList<ContentSegment>, text: String) {
    val (placeheld, tables) = parseGfmTables(text)
    if (tables.isEmpty()) {
        segments.add(ContentSegment.Text(text))
        return
    }
    var pos = 0
    MD_TABLE_PLACEHOLDER_REGEX.findAll(placeheld).forEach { match ->
        if (match.range.first > pos) {
            val chunk = placeheld.substring(pos, match.range.first).trim()
            if (chunk.isNotEmpty()) segments.add(ContentSegment.Text(chunk))
        }
        val idx = match.groupValues[1].toInt()
        segments.add(ContentSegment.Table(tables[idx]))
        pos = match.range.last + 1
    }
    if (pos < placeheld.length) {
        val tail = placeheld.substring(pos).trim()
        if (tail.isNotEmpty()) segments.add(ContentSegment.Text(tail))
    }
}

@Composable
internal fun ContentWithThinkSections(analysis: String) {
    val segments = remember(analysis) { parseContentWithThinkSections(analysis) }
    segments.forEach { segment ->
        when (segment) {
            is ContentSegment.Text -> {
                val html = remember(segment.content) { convertMarkdownToSimpleHtml(segment.content) }
                HtmlContentDisplay(htmlContent = html)
            }
            is ContentSegment.Think -> ThinkSection(content = segment.content)
            is ContentSegment.Table -> MarkdownTableSection(segment.table)
        }
    }
}

@Composable
private fun MarkdownTableSection(table: MarkdownTable) {
    val divider = Color(0xFF3A3A3A)
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)
        .background(Color(0xFF222222), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        .border(1.dp, divider, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
    ) {
        Row(modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A3A), androidx.compose.foundation.shape.RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .padding(8.dp)
        ) {
            for ((i, h) in table.headers.withIndex()) {
                Text(
                    text = stripInlineMarkdown(h),
                    color = Color(0xFF9FCFFF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    textAlign = textAlignFor(table.alignments.getOrNull(i))
                )
            }
        }
        for ((rowIdx, row) in table.rows.withIndex()) {
            HorizontalDivider(color = divider, thickness = 1.dp)
            Row(modifier = Modifier
                .fillMaxWidth()
                .background(if (rowIdx % 2 == 1) Color(0xFF262626) else Color(0xFF222222))
                .padding(8.dp)
            ) {
                for (i in 0 until table.headers.size) {
                    Text(
                        text = stripInlineMarkdown(row.getOrNull(i) ?: ""),
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        textAlign = textAlignFor(table.alignments.getOrNull(i))
                    )
                }
            }
        }
    }
}

private fun textAlignFor(a: TableAlign?) = when (a) {
    TableAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
    TableAlign.RIGHT -> androidx.compose.ui.text.style.TextAlign.End
    else -> androidx.compose.ui.text.style.TextAlign.Start
}

@Composable
private fun ThinkSection(content: String) {
    var isExpanded by remember { mutableStateOf(false) }
    Column {
        Button(
            onClick = { isExpanded = !isExpanded },
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceDark),
            border = BorderStroke(1.dp, AppColors.TextDim),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) { Text(if (isExpanded) "Hide Think" else "Think", color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, softWrap = false) }

        if (isExpanded) {
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525))) {
                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(AppColors.TextDisabled))
                Text(content, color = Color(0xFF999999), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

// ===== HTML Conversion =====

internal fun convertMarkdownToSimpleHtml(markdown: String): String {
    var html = markdown.replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n")
    html = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        .replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\* (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace("\n\n", "</p><p>").replace("\n", "<br>")
    html = html.replace(Regex("(<li>.*?</li>)+")) { "<ul>${it.value}</ul>" }
    if (html.isNotBlank()) html = "<p>$html</p>"
    return html
}

@Composable
private fun HtmlContentDisplay(htmlContent: String) {
    val annotatedString = remember(htmlContent) { parseHtmlToAnnotatedString(htmlContent) }
    Text(text = annotatedString, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth())
}

private fun parseHtmlToAnnotatedString(html: String): androidx.compose.ui.text.AnnotatedString {
    val cleanHtml = html.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("<p>", "").replace("</p>", "\n\n").replace("<br>", "\n")
        .replace("<ul>", "\n").replace("</ul>", "\n")
        .replace("<li>", "  \u2022 ").replace("</li>", "\n")
        .replace(Regex("\n{3,}"), "\n\n").trim()

    return buildAnnotatedString {
        val tagPattern = Regex("<(/?)(h[123]|strong|em)>")
        var lastEnd = 0
        val matches = tagPattern.findAll(cleanHtml).toList()
        val styleStack = mutableListOf<Pair<String, Int>>()

        for (match in matches) {
            if (match.range.first > lastEnd) append(cleanHtml.substring(lastEnd, match.range.first))
            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2]
            if (!isClosing) {
                styleStack.add(tagName to length)
            } else {
                val idx = styleStack.indexOfLast { it.first == tagName }
                if (idx >= 0) {
                    val (_, startPos) = styleStack.removeAt(idx)
                    val style = when (tagName) {
                        "h1" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.White)
                        "h2" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF8BB8FF))
                        "h3" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF9FCFFF))
                        "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "em" -> SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFCCCCCC))
                        else -> null
                    }
                    style?.let { addStyle(it, startPos, length) }
                }
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < cleanHtml.length) append(cleanHtml.substring(lastEnd))
    }
}

// ===== Supplementary Sections =====

@Composable
private fun CitationsSection(citations: List<String>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text("Sources", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Purple, modifier = Modifier.padding(bottom = 12.dp))
        citations.forEachIndexed { i, url ->
            Row(modifier = Modifier.padding(vertical = 4.dp).clickable {
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } catch (_: Exception) {}
            }) {
                Text("${i + 1}. ", color = AppColors.TextSecondary, fontSize = 14.sp)
                Text(url, color = Color(0xFF64B5F6), fontSize = 14.sp, modifier = Modifier.weight(1f), textDecoration = TextDecoration.Underline)
            }
        }
    }
}

@Composable
private fun SearchResultsSection(searchResults: List<SearchResult>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text("Search Results", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Orange, modifier = Modifier.padding(bottom = 12.dp))
        searchResults.forEachIndexed { i, result ->
            if (result.url != null) {
                Column(modifier = Modifier.padding(vertical = 6.dp).clickable {
                    try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, result.url.toUri())) } catch (_: Exception) {}
                }) {
                    Row {
                        Text("${i + 1}. ", color = AppColors.TextSecondary, fontSize = 14.sp)
                        Text(result.name ?: result.url, color = Color(0xFF64B5F6), fontSize = 14.sp, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
                    }
                    if (result.name != null && result.name != result.url) Text(result.url, color = AppColors.TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
                    if (!result.snippet.isNullOrBlank()) Text(result.snippet, color = Color(0xFFBBBBBB), fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun RelatedQuestionsSection(relatedQuestions: List<String>) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text("Related Questions", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Green, modifier = Modifier.padding(bottom = 12.dp))
        relatedQuestions.forEachIndexed { i, q ->
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}. ", color = AppColors.TextSecondary, fontSize = 14.sp)
                Text(q, color = Color(0xFFE0E0E0), fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ===== Tag Extraction =====

internal fun extractTagContent(text: String, tagName: String): String? {
    val pattern = Regex("<$tagName>(.*?)</$tagName>", RegexOption.DOT_MATCHES_ALL)
    return pattern.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
