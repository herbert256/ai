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
 * Content-only "View" variant of a translation run. Reached from a
 * Translate tile on Report - view; the management-heavy translation
 * run drill-in is still served from Report - manage.
 *
 * Layout: stacked side-by-side per row of the run. Each item gets a
 * pair of cards — neutral on the left with the source content (the
 * report prompt, an agent response, or a META row's content), Orange-
 * accented on the right with the translated body. Header above each
 * pair names the source kind + label so the user knows what was
 * translated. Long bodies collapse with a Read-more affordance,
 * mirroring the FanOutViewScreen pattern.
 */
@Composable
fun TranslateViewScreen(
    reportId: String,
    translationRunId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    data class Loaded(
        val rows: List<SecondaryResult>,
        val report: Report?,
        val metaSources: Map<String, SecondaryResult>
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(emptyList(), null, emptyMap()),
        reportId, translationRunId
    ) {
        value = withContext(Dispatchers.IO) {
            val all = SecondaryResultStorage.listForReport(context, reportId)
            val translates = all.filter {
                it.kind == SecondaryKind.TRANSLATE &&
                    it.translationRunId == translationRunId &&
                    !it.content.isNullOrBlank()
            }
            val rep = ReportStorage.getReport(context, reportId)
            // Map any source META row by id so we can render its
            // content as the "source" side for META translations.
            val byId = all.filter { it.kind != SecondaryKind.TRANSLATE }.associateBy { it.id }
            Loaded(translates, rep, byId)
        }
    }
    val loaded = loadedState.value
    val rows = loaded.rows
    val report = loaded.report
    val metaSources = loaded.metaSources
    val targetLanguage = rows.firstOrNull()?.targetLanguageNative
        ?: rows.firstOrNull()?.targetLanguage
        ?: "(language)"

    val expanded = remember { TranslateExpansionMap() }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Translate - view",
            subject = rows.firstOrNull()?.let { it.targetLanguageNative ?: it.targetLanguage }?.takeIf { it.isNotBlank() },
            helpTopic = "translate_view",
            onBack = onBack,
            onLogoClick = onBack
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🌍", fontSize = 28.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = targetLanguage,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Orange,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (rows.isNotEmpty()) {
                Text(
                    text = "${rows.size}",
                    color = AppColors.Orange, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.SurfaceDark)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        if (rows.isEmpty()) {
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
                    Text(text = "🌍", fontSize = 40.sp)
                    Text(
                        text = "No translation rows in this run",
                        color = AppColors.TextPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            items(rows) { row ->
                TranslatePair(
                    row = row,
                    report = report,
                    metaSources = metaSources,
                    expanded = expanded
                )
            }
        }
    }
}

@Composable
private fun TranslatePair(
    row: SecondaryResult,
    report: Report?,
    metaSources: Map<String, SecondaryResult>,
    expanded: TranslateExpansionMap
) {
    val sourceKind = row.translateSourceKind.orEmpty()
    val sourceTargetId = row.translateSourceTargetId.orEmpty()
    val sourceLabel: String
    val sourceBody: String
    when (sourceKind) {
        "PROMPT" -> {
            sourceLabel = "📝 Report prompt"
            sourceBody = report?.prompt.orEmpty()
        }
        "AGENT" -> {
            val agent = report?.agents?.firstOrNull { it.agentId == sourceTargetId }
            val prov = agent?.let { AppService.findById(it.provider)?.id ?: it.provider } ?: "?"
            val mdl = agent?.model?.let { shortModelName(it) } ?: sourceTargetId.take(8)
            sourceLabel = "🤖 $prov / $mdl"
            sourceBody = agent?.responseBody.orEmpty()
        }
        "META" -> {
            val meta = metaSources[sourceTargetId]
            val name = meta?.metaPromptName?.takeIf { it.isNotBlank() }
                ?: meta?.let { com.ai.data.legacyKindDisplayName(it.kind) }
                ?: "Meta"
            sourceLabel = "🧠 $name"
            sourceBody = meta?.content.orEmpty()
        }
        else -> {
            sourceLabel = sourceKind.ifBlank { "Source" }
            sourceBody = ""
        }
    }
    val translatedBody = row.content.orEmpty()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = sourceLabel,
            color = AppColors.TextSecondary,
            fontSize = 12.sp, fontWeight = FontWeight.Medium
        )
        SidePanel(
            tint = AppColors.SurfaceDark,
            borderColor = AppColors.BorderUnfocused,
            badge = "Source",
            badgeColor = AppColors.TextTertiary,
            body = sourceBody,
            expansionKey = "src:${row.id}",
            expanded = expanded
        )
        SidePanel(
            tint = AppColors.Orange.copy(alpha = 0.18f),
            borderColor = AppColors.Orange.copy(alpha = 0.55f),
            badge = "🌍 ${row.targetLanguageNative ?: row.targetLanguage ?: ""}",
            badgeColor = AppColors.Orange,
            body = translatedBody,
            expansionKey = "tr:${row.id}",
            expanded = expanded
        )
    }
}

@Composable
private fun SidePanel(
    tint: androidx.compose.ui.graphics.Color,
    borderColor: androidx.compose.ui.graphics.Color,
    badge: String,
    badgeColor: androidx.compose.ui.graphics.Color,
    body: String,
    expansionKey: String,
    expanded: TranslateExpansionMap
) {
    val collapseThreshold = 600
    val previewChars = 360
    val isLong = body.length > collapseThreshold
    val isExpanded = expanded.get(expansionKey, defaultValue = !isLong)
    val shown = if (isLong && !isExpanded) {
        body.take(previewChars).trimEnd() + "…"
    } else body

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = badge,
            color = badgeColor,
            fontSize = 11.sp, fontWeight = FontWeight.Medium
        )
        if (body.isBlank()) {
            Text(text = "(no content)", color = AppColors.TextTertiary, fontSize = 12.sp)
        } else {
            ContentWithThinkSections(analysis = shown)
            if (isLong) {
                Text(
                    text = if (isExpanded) "Show less" else "Read more",
                    color = AppColors.Blue,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { expanded.put(expansionKey, !isExpanded) }
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

/** Tiny per-screen expansion-state holder; mirrors the
 *  FanOutViewScreen pattern. */
private class TranslateExpansionMap {
    private val backing = mutableStateMapOf<String, Boolean>()
    fun get(key: String, defaultValue: Boolean): Boolean = backing[key] ?: defaultValue
    fun put(key: String, value: Boolean) { backing[key] = value }
}
