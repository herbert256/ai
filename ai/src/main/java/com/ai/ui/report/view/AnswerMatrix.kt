package com.ai.ui.report.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.data.barTitle
import com.ai.ui.helpers.RerankRow
import com.ai.ui.helpers.SwipeDirection
import com.ai.ui.helpers.ViewSwipeFilter
import com.ai.ui.helpers.findSwipeMatch
import com.ai.ui.helpers.formatRerankScore
import com.ai.ui.helpers.parseRerankRows
import com.ai.ui.report.manage.ViewUserNotes
import com.ai.ui.report.manage.view.LangTab
import com.ai.ui.report.manage.view.LanguagePickerRow
import com.ai.ui.report.manage.view.extractTagContent
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.report.view.helpers.viewBodySwipe
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.formatCompactNumber
import com.ai.ui.shared.formatCentsValue
import com.ai.ui.shared.shortModelName
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MatrixRerank(
    val rowsByResultId: Map<Int, RerankRow>,
    val modelShort: String?
)

private data class AnswerMatrixRow(
    val ordinal: Int,
    val rank: Int?,
    val score: Double?,
    val modelLabel: String,
    val stance: String,
    val stanceColor: Color,
    val confidence: String,
    val confidenceColor: Color,
    val recommendation: String,
    val risks: String,
    val cost: String,
    /** Numeric cost in cents — the precise value behind [cost]'s display
     *  string. The summary total sums THIS, not a re-parse of [cost] (which
     *  was rounded to 2-4 decimals and locale-fragile). */
    val costCents: Double,
    val latency: String,
    val tokens: String,
    /** Report agent behind this row — lets a row tap open the answer. */
    val agentId: String = ""
)

@Composable
internal fun AnswerMatrixViewScreen(
    report: Report?,
    translationByTarget: Map<String, String>,
    langTabs: List<LangTab>,
    selectedLangKey: String,
    onSelectLang: (String) -> Unit,
    forcedLanguage: String?,
    onBack: () -> Unit,
    /** Open the tapped row's model answer (Model responses seeded at the
     *  agent). Null → rows stay inert. */
    onOpenAgent: ((String) -> Unit)? = null
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val reportId = report?.id
    // Title-bar / body swipe to the prev/next report that has at least
    // one successful answer to chart. Unlike the sibling sub-Views this
    // screen is fully prop-driven (the hub passes [report] + the
    // language tabs), so there is no local report shadow: the handler
    // only flips the app's current report via [LocalReportSwitchHandler]
    // and the hub re-feeds every prop on recomposition.
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current
    // Precompute the answer-carrier list off the UI thread. The swipe
    // handlers fire in the drag-end callback on main, and the HasAnswers
    // filter walked up to n-1 candidate reports per gesture, full-Gson-
    // parsing every cache miss (report files can carry base64 images) —
    // seconds of main-thread IO per swipe on large libraries. The gesture
    // now walks this in-memory list only; swipes no-op for the moment the
    // filter is still computing. The self-hop guard keeps the wrap-to-self
    // of the unfiltered walk from reload-flashing a lone carrier.
    val answerCarrierIds by produceState(initialValue = emptyList<String>(), reportIdsList) {
        value = withContext(Dispatchers.IO) {
            reportIdsList.filter { id ->
                val rep = com.ai.ui.report.view.helpers.ViewReportCache.get(context, id)
                rep?.agents?.any {
                    it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                } == true
            }
        }
    }
    val onSwipePrevAction: () -> Boolean = {
        val m = reportId?.let { findSwipeMatch(context, answerCarrierIds, it, SwipeDirection.Prev, ViewSwipeFilter.Any) }
        if (m != null && m.reportId != reportId) { switchReport?.invoke(m.reportId); true } else false
    }
    val onSwipeNextAction: () -> Boolean = {
        val m = reportId?.let { findSwipeMatch(context, answerCarrierIds, it, SwipeDirection.Next, ViewSwipeFilter.Any) }
        if (m != null && m.reportId != reportId) { switchReport?.invoke(m.reportId); true } else false
    }
    val activeLanguage = if (selectedLangKey == LangTab.ORIGINAL_KEY) ""
        else langTabs.firstOrNull { it.key == selectedLangKey }?.displayName ?: ""
    val secondaryDataVersion by SecondaryDataVersion.versionFor(reportId).collectAsState()
    val rerankState = produceState(MatrixRerank(emptyMap(), null), reportId, secondaryDataVersion) {
        value = if (reportId == null) MatrixRerank(emptyMap(), null)
        else withContext(Dispatchers.IO) {
            val latest = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.RERANK)
                .filter { !it.content.isNullOrBlank() }
                .maxByOrNull { it.timestamp }
            MatrixRerank(
                rowsByResultId = latest?.content?.let { parseRerankRows(it) }?.associateBy { it.id } ?: emptyMap(),
                modelShort = latest?.model?.let { shortModelName(it) }
            )
        }
    }

    val matrixRows = remember(report, translationByTarget, rerankState.value) {
        report?.let { buildAnswerMatrixRows(it, translationByTarget, rerankState.value.rowsByResultId) }
            ?: emptyList()
    }
    val totalCost = remember(matrixRows) {
        // Sum the precise numeric cents, NOT a re-parse of the rounded/locale-
        // fragile display string (audit reports#32).
        matrixRows.sumOf { it.costCents }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .viewBodySwipe(reportId, onPrev = { onSwipePrevAction() }, onNext = { onSwipeNextAction() })
    ) {
        ViewTitleBar(
            reportTitle = report?.barTitle,
            reportId = report?.id,
            activeLanguage = activeLanguage,
            screenTitle = "Answer matrix",
            subject = rerankState.value.modelShort?.let { "ranked by $it" },
            helpTopic = "view_ai_report",
            onBack = onBack,
            onSwipePrev = onSwipePrevAction,
            onSwipeNext = onSwipeNextAction,
            // 📋 — the whole matrix as CSV, ready for a spreadsheet.
            onCopy = if (matrixRows.isNotEmpty()) ({
                val csv = buildString {
                    appendLine("rank,model,stance,confidence,recommendation,risks,cost_cents,latency,tokens")
                    matrixRows.forEach { r ->
                        appendLine(listOf(
                            r.rank?.toString().orEmpty(), r.modelLabel, r.stance, r.confidence,
                            r.recommendation, r.risks,
                            String.format(Locale.US, "%.4f", r.costCents),
                            r.latency, r.tokens
                        ).joinToString(",") { f -> "\"${f.replace("\"", "\"\"")}\"" })
                    }
                }
                com.ai.ui.shared.copyToClipboard(context, csv, "answer matrix CSV")
            }) else null
        )
        report?.let { ViewUserNotes(it.id, "REPORT", it.id) }

        if (forcedLanguage == null && langTabs.size > 1) {
            LanguagePickerRow(
                langTabs,
                selectedLangKey,
                onSelect = onSelectLang,
                useIcons = true,
                originalIcon = report?.languageIcon
            )
        }

        when {
            report == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text("Loading...", color = AppColors.TextTertiary, fontSize = 14.sp)
                }
            }
            matrixRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.CardBackground)
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = com.ai.data.MetadataIconsHolder.current.chart, fontSize = 40.sp)
                        Text(
                            text = "No successful agent responses",
                            color = AppColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Generate the report or fix failed agents in Report - manage.",
                            color = AppColors.TextTertiary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MatrixSummaryCard(
                        modelCount = matrixRows.size,
                        rankedCount = matrixRows.count { it.rank != null },
                        totalCostCents = totalCost
                    )
                    AnswerMatrixTable(matrixRows, onOpenAgent)
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun MatrixSummaryCard(
    modelCount: Int,
    rankedCount: Int,
    totalCostCents: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatText("Models", modelCount.toString(), AppColors.InfoAccent)
            StatText("Ranked", rankedCount.takeIf { it > 0 }?.toString() ?: "-", AppColors.SuccessAccent)
            StatText("Cost", if (totalCostCents > 0.0) formatCentsValue(totalCostCents) else "-", AppColors.CautionAccent)
        }
    }
}

@Composable
private fun StatText(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AppColors.TextTertiary, fontSize = 11.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AnswerMatrixTable(rows: List<AnswerMatrixRow>, onOpenAgent: ((String) -> Unit)? = null) {
    val hScroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.InfoAccent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .horizontalScroll(hScroll)
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            HeaderCell("#", 46.dp, end = true)
            HeaderCell("Model", 180.dp)
            HeaderCell("Rank", 64.dp, end = true)
            HeaderCell("Stance", 96.dp)
            HeaderCell("Confidence", 96.dp)
            HeaderCell("Recommendation", 280.dp)
            HeaderCell("Risks", 260.dp)
            HeaderCell("Cost", 88.dp, end = true)
            HeaderCell("Latency", 88.dp, end = true)
            HeaderCell("Tokens", 84.dp, end = true)
        }
        HorizontalDivider(color = AppColors.DividerDark)
        rows.forEachIndexed { idx, row ->
            // Row tap opens the model's answer (users kept tapping and
            // getting nothing) — sibling Rerank rows drill in the same way.
            Row(modifier = Modifier
                .let { m ->
                    if (onOpenAgent != null && row.agentId.isNotBlank())
                        m.clickable { onOpenAgent(row.agentId) } else m
                }
                .padding(horizontal = 10.dp, vertical = 9.dp)) {
                BodyCell(row.ordinal.toString(), 46.dp, end = true, mono = true, color = AppColors.TextSecondary)
                BodyCell(row.modelLabel, 180.dp, color = AppColors.TextPrimary, weight = FontWeight.SemiBold)
                BodyCell(
                    text = rankText(row),
                    width = 64.dp,
                    end = true,
                    mono = true,
                    color = if (row.rank != null) AppColors.SuccessAccent else AppColors.TextTertiary
                )
                BodyCell(row.stance, 96.dp, color = row.stanceColor, weight = FontWeight.SemiBold)
                BodyCell(row.confidence, 96.dp, color = row.confidenceColor, weight = FontWeight.SemiBold)
                BodyCell(row.recommendation, 280.dp, maxLines = 3)
                BodyCell(row.risks, 260.dp, maxLines = 3, color = if (row.risks == "None explicit") AppColors.TextTertiary else AppColors.CautionAccent)
                BodyCell(row.cost, 88.dp, end = true, mono = true, color = AppColors.CautionAccent)
                BodyCell(row.latency, 88.dp, end = true, mono = true)
                BodyCell(row.tokens, 84.dp, end = true, mono = true)
            }
            if (idx != rows.lastIndex) HorizontalDivider(color = AppColors.DividerDark)
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, end: Boolean = false) {
    Text(
        text = text,
        color = AppColors.InfoAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun BodyCell(
    text: String,
    width: Dp,
    end: Boolean = false,
    mono: Boolean = false,
    color: Color = AppColors.TextSecondary,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1
) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        lineHeight = 16.sp,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

private fun buildAnswerMatrixRows(
    report: Report,
    translationByTarget: Map<String, String>,
    rankRows: Map<Int, RerankRow>
): List<AnswerMatrixRow> {
    val agents = report.agents.filter {
        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
    }
    return agents.mapIndexed { index, agent ->
        val ordinal = index + 1
        val rank = rankRows[ordinal]
        val title = translationByTarget["AGENT_TITLE:${agent.agentId}"]
            ?.takeIf { it.isNotBlank() }
            ?: agent.modelTitle?.takeIf { it.isNotBlank() }
        val extraction = extractMatrixSignals(agent.responseBody.orEmpty())
        val provider = AppService.findById(agent.provider)?.id ?: agent.provider
        val costUsd = agent.cost ?: ((agent.inputCost ?: 0.0) + (agent.outputCost ?: 0.0))
        AnswerMatrixRow(
            ordinal = ordinal,
            rank = rank?.rank,
            score = rank?.score,
            modelLabel = title?.let { "$provider / $it" } ?: "$provider / ${shortModelName(agent.model)}",
            stance = extraction.stance,
            stanceColor = extraction.stanceColor,
            confidence = extraction.confidence,
            confidenceColor = extraction.confidenceColor,
            recommendation = extraction.recommendation,
            risks = extraction.risks,
            cost = if (costUsd > 0.0) formatCentsValue(costUsd * 100.0) else "-",
            costCents = if (costUsd > 0.0) costUsd * 100.0 else 0.0,
            latency = formatDuration(agent.durationMs),
            tokens = agent.tokenUsage?.totalTokens?.takeIf { it > 0 }
                ?.let { formatCompactNumber(it.toLong()) }
                ?: "-",
            agentId = agent.agentId
        )
    }.sortedWith(
        compareBy<AnswerMatrixRow> { it.rank ?: Int.MAX_VALUE }
            .thenBy { it.ordinal }
    )
}

private data class MatrixExtraction(
    val stance: String,
    val stanceColor: Color,
    val confidence: String,
    val confidenceColor: Color,
    val recommendation: String,
    val risks: String
)

private fun extractMatrixSignals(body: String): MatrixExtraction {
    val clean = cleanResponseText(body)
    val sentences = splitSentences(clean)
    val lower = clean.lowercase(Locale.US)
    val conclusion = extractTagContent(body, "conclusion")
        ?.let { firstUsefulSentence(it) }
    val recommendation = listOfNotNull(
        conclusion,
        sentences.firstOrNull { recommendationRegex.containsMatchIn(it) },
        sentences.firstOrNull()
    ).firstOrNull { it.isNotBlank() }
        ?.let { compactText(it, 220) }
        ?: "(no recommendation extracted)"
    val risk = sentences
        .filter { riskRegex.containsMatchIn(it) }
        .take(2)
        .joinToString(" ")
        .let { compactText(it, 220) }
        .ifBlank { "None explicit" }
    val refused = refusalRegex.containsMatchIn(lower)
    val hasRecommendation = recommendationRegex.containsMatchIn(lower)
    val hasRisk = riskRegex.containsMatchIn(lower)
    val stance = when {
        refused -> "Refuses"
        hasRecommendation && hasRisk -> "Mixed"
        hasRecommendation -> "Recommends"
        hasRisk -> "Cautious"
        else -> "Neutral"
    }
    val stanceColor = when (stance) {
        "Recommends" -> AppColors.SuccessAccent
        "Mixed" -> AppColors.WarningAccent
        "Cautious" -> AppColors.CautionAccent
        "Refuses" -> AppColors.DangerAccent
        else -> AppColors.TextSecondary
    }
    val high = confidenceHighRegex.findAll(lower).count()
    val low = confidenceLowRegex.findAll(lower).count()
    val confidence = when {
        low > high -> "Low"
        high >= low + 2 -> "High"
        else -> "Medium"
    }
    val confidenceColor = when (confidence) {
        "High" -> AppColors.SuccessAccent
        "Low" -> AppColors.CautionAccent
        else -> AppColors.InfoAccent
    }
    return MatrixExtraction(stance, stanceColor, confidence, confidenceColor, recommendation, risk)
}

private fun rankText(row: AnswerMatrixRow): String {
    val rank = row.rank ?: return "-"
    val score = row.score?.let { " / ${formatRerankScore(it)}" } ?: ""
    return "$rank$score"
}

internal fun firstUsefulSentence(text: String): String? =
    splitSentences(cleanResponseText(text)).firstOrNull { it.isNotBlank() }

internal fun cleanResponseText(text: String): String =
    text
        .replace(Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("</?(conclusion|motivation)>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun splitSentences(text: String): List<String> =
    text.split(Regex("(?<=[.!?])\\s+"))
        .map { it.trim().trim('-', '*', ' ') }
        .filter { it.isNotBlank() }

internal fun compactText(text: String, maxChars: Int): String {
    val oneLine = text.replace(Regex("\\s+"), " ").trim()
    if (oneLine.length <= maxChars) return oneLine
    return oneLine.take((maxChars - 3).coerceAtLeast(0)).trimEnd() + "..."
}

internal fun formatDuration(ms: Long?): String {
    val value = ms ?: return "-"
    if (value <= 0L) return "-"
    if (value < 1000L) return "${value} ms"
    val totalSec = value / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return when {
        h > 0L -> "${h}h ${m}m"
        m > 0L -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

private val recommendationRegex = Regex(
    "\\b(recommend|recommended|best|should|choose|select|use|adopt|go with|prefer|answer is|conclude)\\b",
    RegexOption.IGNORE_CASE
)

private val riskRegex = Regex(
    "\\b(risk|risks|caution|concern|limitation|drawback|downside|trade[- ]?off|problem|issue|failure|privacy|security|cost|uncertain|depends)\\b",
    RegexOption.IGNORE_CASE
)

private val refusalRegex = Regex(
    "\\b(i can'?t|i cannot|unable to|not able to|won'?t|cannot comply|not possible)\\b",
    RegexOption.IGNORE_CASE
)

private val confidenceHighRegex = Regex(
    "\\b(confident|certain|clearly|strongly|definitely|very likely|high confidence|straightforward)\\b",
    RegexOption.IGNORE_CASE
)

private val confidenceLowRegex = Regex(
    "\\b(uncertain|unsure|maybe|possibly|depends|limited|not enough|cannot determine|low confidence|speculative|assumption)\\b",
    RegexOption.IGNORE_CASE
)
