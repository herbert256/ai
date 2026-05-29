package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One tappable icon row on [ReportEditIconsScreen]. */
private class IconEditItem(
    val glyph: String?,
    val category: String,
    val label: String,
    val onClick: () -> Unit
)

/**
 * "Edit icons" — full-screen list of every icon in the report. One row per
 * icon (report, report language, per-model, meta, ranking, moderation, per
 * translation language, per fan-out response); tapping a row opens that icon's
 * existing Icon-lookup detail + Find-alternative flow by setting the matching
 * [ReportsScreenState] slot (the early-return overlays render over this layer).
 *
 * Reached from [ReportEditOverviewScreen]; drawn as a layer-on-top overlay in
 * [ReportRunScreen] with [publishBottomBar] = false.
 */
@Composable
internal fun ReportEditIconsScreen(
    reportId: String,
    iconRefreshTick: Int,
    st: ReportsScreenState,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    val data by produceState<Pair<Report?, List<SecondaryResult>>>(
        initialValue = null to emptyList(), reportId, iconRefreshTick
    ) {
        value = withContext(Dispatchers.IO) {
            val r = ReportStorage.getReport(context, reportId)
            val s = SecondaryResultStorage.listForReport(context, reportId)
            r to s
        }
    }
    val report = data.first
    val secondaries = data.second

    val rows = buildList {
        // Report icon — always present.
        add(IconEditItem(report?.icon, "report", "Report icon") {
            st.targetLanguageIcon.value = false
            st.showIconDetail.value = true
        })
        // Report language icon — only when a language was detected.
        report?.languageName?.takeIf { it.isNotBlank() }?.let { lang ->
            add(IconEditItem(report.languageIcon, "language", "Language: $lang") {
                st.targetLanguageIcon.value = true
                st.showIconDetail.value = true
            })
        }
        // Per-model report icons.
        report?.agents?.forEach { a ->
            val label = a.modelTitle?.takeIf { it.isNotBlank() }
                ?: "${a.provider} · ${shortModelName(a.model)}"
            add(IconEditItem(a.icon, "model", label) { st.agentIconDetailFor.value = a.agentId })
        }
        // Meta icons (non-fan-out META rows), keyed per Internal Prompt + row.
        secondaries.filter { it.kind == SecondaryKind.META && it.fanOutSourceAgentId == null }
            .forEach { row ->
                val promptId = row.metaPromptId ?: return@forEach
                val label = "Meta: ${row.metaPromptName ?: row.metaPromptId}"
                add(IconEditItem(row.icon, "meta", label) {
                    st.promptIconDetailForId.value = promptId
                    st.metaRowIdForPromptIcon.value = row.id
                })
            }
        // Ranking (Rerank) — reuses the per-SecondaryResult-row icon flow.
        secondaries.filter { it.kind == SecondaryKind.RERANK }.forEach { row ->
            add(IconEditItem(row.icon, "ranking", "Ranking") { st.pairIconDetailFor.value = row.id })
        }
        // Moderation — same per-row flow.
        secondaries.filter { it.kind == SecondaryKind.MODERATION }.forEach { row ->
            add(IconEditItem(row.icon, "moderation", "Moderation") { st.pairIconDetailFor.value = row.id })
        }
        // Translation icons — one per target language.
        secondaries.filter { it.kind == SecondaryKind.TRANSLATE }
            .groupBy { it.targetLanguage }
            .forEach { (lang, group) ->
                if (lang.isNullOrBlank()) return@forEach
                add(IconEditItem(group.firstOrNull { !it.icon.isNullOrBlank() }?.icon, "translation", "Translation: $lang") {
                    st.translationIconLanguageFor.value = lang
                })
            }
        // Fan-out response icons — one per pair.
        secondaries.filter { it.kind == SecondaryKind.META && it.fanOutSourceAgentId != null }
            .forEach { row ->
                val label = row.title?.takeIf { it.isNotBlank() }
                    ?: "${row.providerId} · ${shortModelName(row.model)}"
                add(IconEditItem(row.icon, "fan-out", label) { st.pairIconDetailFor.value = row.id })
            }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_edit_icons",
            title = "Edit icons",
            subject = "Every icon in this report",
            onBackClick = onBack,
            publishBottomBar = false
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { i, it -> "${it.category}-${it.label}-$i" }) { idx, item ->
                if (idx > 0) HorizontalDivider(color = AppColors.DividerDark)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { item.onClick() }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                        Text(item.glyph?.takeIf { it.isNotBlank() } ?: "⬜", fontSize = 22.sp)
                    }
                    Text(
                        item.category, fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                        modifier = Modifier.width(86.dp).padding(start = 6.dp, end = 6.dp)
                    )
                    Text(
                        item.label, fontSize = 14.sp, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
