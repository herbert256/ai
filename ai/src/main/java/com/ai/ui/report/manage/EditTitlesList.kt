package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
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
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One tappable title row on [ReportEditTitlesScreen]. */
private class TitleEditItem(
    val category: String,
    val value: String,
    val onEdit: () -> Unit,
    val onFindAlt: () -> Unit
)

/**
 * "Edit titles" — full-screen list of every dynamic title in the report
 * (report short + long, per-model, per fan-out response). Each row has a manual
 * edit pencil and a "Find" button (the big multi-model picker). Both reuse the
 * existing flows by setting [ReportsScreenState] slots; the early-return
 * overlays render over this layer.
 *
 * Report short/long titles read from [UiState] (their saves update it in place,
 * no iconRefreshTick bump); per-model + pair titles read from disk keyed on
 * [UiState.iconRefreshTick] (their saves DO bump it).
 *
 * Reached from [ReportEditOverviewScreen]; drawn as a layer-on-top overlay in
 * [ReportRunScreen] with [publishBottomBar] = false.
 */
@Composable
internal fun ReportEditTitlesScreen(
    reportId: String,
    uiState: UiState,
    st: ReportsScreenState,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    val data by produceState<Pair<Report?, List<SecondaryResult>>>(
        initialValue = null to emptyList(), reportId, uiState.iconRefreshTick
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
        // Report short title.
        add(TitleEditItem(
            category = "title",
            value = uiState.genericPromptTitle.ifBlank { "*NONE" },
            onEdit = { st.showEditShortTitle.value = true },
            onFindAlt = {
                st.showEditShortTitle.value = true
                st.findTitlesFor.value = "report"
                st.findTitlesLong.value = false
                st.findIconsModels.value = emptyList()
                st.showFindIconsPicker.value = true
            }
        ))
        // Report long title.
        add(TitleEditItem(
            category = "long title",
            value = uiState.genericPromptTitleLong.ifBlank { "*NONE" },
            onEdit = { st.showEditLongTitle.value = true },
            onFindAlt = {
                st.showEditLongTitle.value = true
                st.findTitlesFor.value = "report"
                st.findTitlesLong.value = true
                st.findIconsModels.value = emptyList()
                st.showFindIconsPicker.value = true
            }
        ))
        // Per-model titles.
        report?.agents?.forEach { a ->
            val title = a.modelTitle?.takeIf { it.isNotBlank() } ?: return@forEach
            val agentId = a.agentId
            add(TitleEditItem(
                category = "model · ${shortModelName(a.model)}",
                value = title,
                onEdit = { st.editModelTitleFor.value = agentId },
                onFindAlt = {
                    st.editModelTitleFor.value = agentId
                    st.findTitlesFor.value = agentId
                    st.findTitlesLong.value = false
                    st.findIconsModels.value = emptyList()
                    st.showFindIconsPicker.value = true
                }
            ))
        }
        // Fan-out response titles.
        secondaries.filter { it.kind == SecondaryKind.META && it.fanOutSourceAgentId != null }
            .forEach { row ->
                val title = row.title?.takeIf { it.isNotBlank() } ?: return@forEach
                val pairId = row.id
                add(TitleEditItem(
                    category = "fan-out · ${shortModelName(row.model)}",
                    value = title,
                    onEdit = { st.pairTitleEditFor.value = pairId },
                    onFindAlt = {
                        st.pairTitleDetailFor.value = pairId
                        st.findIconsModels.value = emptyList()
                        st.showFindIconsPicker.value = true
                    }
                ))
            }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_edit_titles",
            title = "Edit titles",
            subject = "Every dynamic title in this report",
            onBackClick = onBack,
            publishBottomBar = false
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { i, it -> "${it.category}-$i" }) { idx, item ->
                if (idx > 0) HorizontalDivider(color = AppColors.DividerDark)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.category, fontSize = 11.sp, color = AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            item.value, fontSize = 14.sp, color = Color.White,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        com.ai.data.MetadataIconsHolder.current.edit, fontSize = 20.sp,
                        modifier = Modifier.clickable { item.onEdit() }.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    OutlinedButton(
                        onClick = item.onFindAlt,
                        colors = AppColors.outlinedButtonColors(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 32.dp).padding(start = 4.dp)
                    ) { Text("Find", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
