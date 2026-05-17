package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-focused "View" variant of the Fan-out drill-in, paired with
 * the existing management-heavy [FanOutScreen]. Reached from the
 * "Report - view" tile for a fan-out run (the Manage flow keeps using
 * [FanOutScreen]).
 *
 * Layout: a single scrolling conversational thread, grouped by
 * Answerer (one big header chip per Answerer model) with each
 * Initiator → Answerer pair laid out as two chat-style bubbles —
 * the Initiator's original report response on the left as a "user"
 * bubble, the Answerer's fan-out reply below it on the right as
 * an "assistant" bubble. Long bodies collapse with a "Read more"
 * tap that flips that bubble into "Show less".
 *
 * Deliberately omitted: status counters, throttle indicators,
 * restart-failed / delete / re-run / fan-icons controls, role
 * toggles, L1/L2/L3 navigation. Anyone who wants those drops back
 * to "Report - manage" and opens the same fan-out run there.
 *
 * [language] mirrors the View screen's active-language picker:
 *  - null = no force (act like Original)
 *  - "" = explicit Original
 *  - non-empty = English displayName of the active translation;
 *    each pair's TRANSLATE row whose
 *    `translateSourceKind == "META"` +
 *    `translateSourceTargetId == pair.id` +
 *    `targetLanguage == language` is preferred for the bubble body.
 *    Pairs without a matching translation fall back to the original
 *    fan-out content so the thread stays continuous.
 */
@Composable
fun FanOutViewScreen(
    reportId: String,
    metaPromptName: String,
    language: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    data class Loaded(
        val report: Report?,
        val pairs: List<SecondaryResult>,
        val translates: List<SecondaryResult>
    )

    val loadedState = produceState(
        initialValue = Loaded(null, emptyList(), emptyList()),
        reportId, metaPromptName
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, reportId)
            val allSecondary = SecondaryResultStorage.listForReport(context, reportId)
            val pairs = allSecondary.filter {
                it.fanOutSourceAgentId != null &&
                    it.metaPromptName == metaPromptName &&
                    !it.content.isNullOrBlank()
            }
            val translates = allSecondary.filter {
                it.kind == SecondaryKind.TRANSLATE &&
                    it.translateSourceKind == "META" &&
                    !it.content.isNullOrBlank()
            }
            Loaded(rep, pairs, translates)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val pairs = loaded.pairs
    val translates = loaded.translates

    // Map answererKey "providerId|model" → ordered list of pairs.
    // Preserves the iteration order of [pairs] so the thread reads
    // top-to-bottom in the order the fan-out runner wrote them
    // (which already groups by answerer at the runtime layer).
    val groupedByAnswerer = remember(pairs) {
        val map = LinkedHashMap<String, MutableList<SecondaryResult>>()
        for (p in pairs) {
            val key = "${p.providerId}|${p.model}"
            map.getOrPut(key) { mutableListOf() }.add(p)
        }
        map
    }

    // Per-bubble expand state — collapsed by default for any body
    // over the preview-line threshold. Keyed by an arbitrary string
    // (we use "<role>:<rowId>"). Tapping "Read more" flips it.
    val expanded = remember { ExpansionMap() }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Fan-out",
            subject = metaPromptName.takeIf { it.isNotBlank() },
            helpTopic = "fan_out_view",
            onBack = onBack,
            onLogoClick = onBack
        )
        // Subject row carries the fan-out's prompt name in big
        // green text — same emphasis the Report - view tile shows.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🌀",  // 🌀
                fontSize = 28.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = metaPromptName,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Green,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!language.isNullOrEmpty()) {
                Text(
                    text = language,
                    fontSize = 13.sp,
                    color = AppColors.TextTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.SurfaceDark)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        if (pairs.isEmpty()) {
            EmptyFanOutState()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            val answererEntries = groupedByAnswerer.entries.toList()
            items(answererEntries.size, key = { idx -> answererEntries[idx].key }) { idx ->
                val (answererKey, answererPairs) = answererEntries[idx]
                AnswererBlock(
                    answererKey = answererKey,
                    answererPairs = answererPairs,
                    language = language,
                    report = report,
                    translates = translates,
                    expanded = expanded
                )
            }
        }
    }
}

/** Friendly empty-state card shown when the fan-out run has been
 *  launched but no pair has produced content yet. */
@Composable
private fun EmptyFanOutState() {
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
            Text(text = "🌀", fontSize = 40.sp)
            Text(
                text = "No fan-out replies yet",
                color = AppColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Generate them from Report - manage. " +
                    "This screen will fill in as each (Answerer × Initiator) pair completes.",
                color = AppColors.TextTertiary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/** One Answerer's block: a header chip naming the model, followed
 *  by every (Initiator → Answerer) pair as a stack of two chat-style
 *  bubbles. */
@Composable
private fun AnswererBlock(
    answererKey: String,
    answererPairs: List<SecondaryResult>,
    language: String?,
    report: Report?,
    translates: List<SecondaryResult>,
    expanded: ExpansionMap
) {
    val parts = answererKey.split("|", limit = 2)
    val providerId = parts.getOrNull(0).orEmpty()
    val model = parts.getOrNull(1).orEmpty()
    val providerDisplay = AppService.findById(providerId)?.id ?: providerId
    val shortModel = shortModelName(model)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Answerer header chip — full-width, indigo accent so it
        // visually anchors each block. Emoji prefix is a fixed 🤖
        // (the answerer is "the model speaking") so the user
        // doesn't need to memorise provider glyphs to scan the
        // thread.
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.IndigoHighlight)
                .border(1.dp, AppColors.Indigo, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🤖", fontSize = 18.sp)  // 🤖
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shortModel,
                    color = AppColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = providerDisplay,
                    color = AppColors.TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${answererPairs.size}",
                color = AppColors.Indigo,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x33000000))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        // One pair = one initiator bubble + one answerer bubble,
        // stacked. Spacer between consecutive pairs to make the
        // boundary obvious.
        answererPairs.forEachIndexed { idx, pair ->
            if (idx > 0) {
                Spacer(modifier = Modifier.height(4.dp))
            }
            PairExchange(
                pair = pair,
                report = report,
                language = language,
                translates = translates,
                expanded = expanded
            )
        }
    }
}

/** One (Initiator, Answerer) pair rendered as two stacked bubbles:
 *  - Initiator's report response = user bubble (left-aligned, neutral).
 *  - Answerer's fan-out reply = assistant bubble (right-aligned, indigo).
 *
 *  Active-language slice: when [language] is non-blank we look up the
 *  matching META TRANSLATE row in [translates] for the answerer body.
 *  The initiator body is always the report agent's `responseBody` —
 *  the answerer bubble carries the new content the user came here
 *  to read; we leave the initiator side as the original prompt
 *  source so the contrast stays meaningful. */
@Composable
private fun PairExchange(
    pair: SecondaryResult,
    report: Report?,
    language: String?,
    translates: List<SecondaryResult>,
    expanded: ExpansionMap
) {
    val initiator = report?.agents?.firstOrNull { it.agentId == pair.fanOutSourceAgentId }
    val initiatorLabel = initiator?.let {
        val provDisplay = AppService.findById(it.provider)?.id ?: it.provider
        "$provDisplay / ${shortModelName(it.model)}"
    } ?: "Initiator"
    val initiatorBody = initiator?.takeIf { it.reportStatus == ReportStatus.SUCCESS }
        ?.responseBody
        ?.takeIf { !it.isNullOrBlank() }
        ?: "(initiator response no longer available)"

    val translatedAnswer = if (!language.isNullOrEmpty()) {
        translates.firstOrNull {
            it.translateSourceTargetId == pair.id &&
                it.targetLanguage == language
        }?.content
    } else null
    val answererBody = translatedAnswer?.takeIf { it.isNotBlank() } ?: pair.content.orEmpty()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Bubble(
            label = initiatorLabel,
            body = initiatorBody,
            color = AppColors.SurfaceDark,
            borderColor = AppColors.BorderUnfocused,
            isAssistant = false,
            expansionKey = "init:${pair.id}",
            expanded = expanded
        )
        Bubble(
            // No model label here — the Answerer is named by the
            // block header above. Keeping the bubble unlabelled
            // reduces noise and lets the eye land directly on the
            // reply.
            label = null,
            body = answererBody,
            color = AppColors.IndigoHighlight,
            borderColor = AppColors.Indigo,
            isAssistant = true,
            expansionKey = "ans:${pair.id}",
            expanded = expanded,
            languageBadge = translatedAnswer?.let { language }
        )
    }
}

/** A single chat bubble. Long bodies collapse to a preview with
 *  "Read more" / "Show less" affordance. Markdown renders via the
 *  existing [ContentWithThinkSections] pipeline so tables, headings,
 *  fenced blocks and `<think>` sections behave identically to every
 *  other content view in the app. */
@Composable
private fun Bubble(
    label: String?,
    body: String,
    color: Color,
    borderColor: Color,
    isAssistant: Boolean,
    expansionKey: String,
    expanded: ExpansionMap,
    languageBadge: String? = null
) {
    val collapseThreshold = 600   // chars
    val previewChars = 360
    val isLong = body.length > collapseThreshold
    val isExpanded = expanded.get(expansionKey, defaultValue = !isLong)
    val shown = if (isLong && !isExpanded) {
        body.take(previewChars).trimEnd() + "…"
    } else body

    // Slight horizontal offset to differentiate the two sides
    // without losing readable width; bubbles still fill ~94% of
    // the row so prose doesn't get squeezed.
    val sidePad = if (isAssistant) PaddingValues(start = 20.dp) else PaddingValues(end = 20.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(sidePad),
        horizontalArrangement = if (isAssistant) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(color)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!label.isNullOrBlank() || languageBadge != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!label.isNullOrBlank()) {
                        Text(
                            text = label,
                            color = AppColors.TextTertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = true)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f, fill = true))
                    }
                    if (languageBadge != null) {
                        Text(
                            text = "🌍 $languageBadge",  // 🌍
                            color = AppColors.Orange,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x33000000))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            // Reuse the standard markdown renderer; it handles tables,
            // headings, lists, fenced code, and `<think>` regions
            // exactly like ReportSingleResultScreen.
            ContentWithThinkSections(analysis = shown)
            if (isLong) {
                Text(
                    text = if (isExpanded) "Show less" else "Read more",
                    color = AppColors.Blue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { expanded.put(expansionKey, !isExpanded) }
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

/** Tiny per-screen expansion-state holder. Each `key` defaults to
 *  the supplied [defaultValue] (true for short bubbles, false for
 *  long collapsed ones) until the user toggles it. rememberSaveable
 *  isn't a fit here — the map is keyed by SecondaryResult ids which
 *  vary per fan-out run, and on rotation we'd rather rebuild from
 *  the loaded data than try to restore a stale map. */
private class ExpansionMap {
    private val backing = mutableStateMapOf<String, Boolean>()
    fun get(key: String, defaultValue: Boolean): Boolean = backing[key] ?: defaultValue
    fun put(key: String, value: Boolean) { backing[key] = value }
}
