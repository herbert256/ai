package com.ai.ui.report.view
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.ai.ui.shared.modelInfoViewClickable
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-only "View" variant of a fan-in-model META row. Reached
 * from the 🧩 Fan-in-model tile on Report - view; the management-heavy
 * [SecondaryResultDetailScreen] is still served from Report - manage.
 *
 * Layout: a horizontal pager between every sibling fan-in-model row
 * sharing this row's metaPromptName. The active page renders as a
 * full-width Blue-gradient card with the model header + synthesis
 * body; the user pages with a horizontal swipe. The active model's
 * tab among the sibling list shows above the pager so the user knows
 * which model they're reading. Each page also carries its own
 * 'Synthesised from' credits strip naming the report agents that fed
 * the run.
 */
@Composable
fun FanInModelViewScreen(
    reportId: String,
    resultId: String,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current

    data class Loaded(
        val rows: List<SecondaryResult>,
        val activeIndex: Int,
        val report: Report?
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(emptyList(), 0, null),
        reportId, resultId
    ) {
        value = withContext(Dispatchers.IO) {
            val all = SecondaryResultStorage.listForReport(context, reportId)
            val seed = all.firstOrNull { it.id == resultId }
            val siblings = if (seed != null) {
                all.filter {
                    it.kind == SecondaryKind.META &&
                        it.metaPromptName == seed.metaPromptName &&
                        !it.content.isNullOrBlank()
                }
            } else emptyList()
            val active = siblings.indexOfFirst { it.id == resultId }.coerceAtLeast(0)
            val rep = ReportStorage.getReport(context, reportId)
            Loaded(siblings, active, rep)
        }
    }
    val loaded = loadedState.value
    val rows = loaded.rows
    val report = loaded.report
    val title = rows.firstOrNull()?.metaPromptName?.takeIf { it.isNotBlank() } ?: "Fan-in-model"

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // 🔧 → Manage's SecondaryResultDetail for the seed row. The
        // visible pager (defined further down) can swipe to siblings,
        // but the seed id is a stable jump target — Manage's detail
        // screen renders the same metaPromptName group.
        // 🔧 → Manage's SecondaryResultDetail for this seed row.
        // No fallback to LocalNavigateToCurrentReport — that local is
        // overridden by ViewAiReportScreen to "back to View grid",
        // which would land 🔧 back on the grid instead of Manage.
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            { dispatch(com.ai.ui.shared.ManageJump.MetaResult(resultId)) }
        }
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Fan-in-model",
            subject = rows.firstOrNull()?.metaPromptName?.takeIf { it.isNotBlank() },
            helpTopic = "fan_in_model_view",
            onOpenManage = onOpenManageJump,
            onBack = onBack
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🧩", fontSize = 28.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Blue,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (rows.size > 1) {
                Text(
                    text = "${rows.size}",
                    color = AppColors.Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold,
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
                Text("Loading…", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }

        val pagerState = rememberPagerState(initialPage = loaded.activeIndex, pageCount = { rows.size })
        // Sync the pager to the loaded active index when it lands (the
        // initial value is captured at remember time, before the IO
        // load finishes, so it's 0). Without this, the user opening
        // the second sibling would always land on the first.
        LaunchedEffect(loaded.activeIndex, rows.size) {
            if (rows.isNotEmpty() && pagerState.currentPage != loaded.activeIndex) {
                pagerState.scrollToPage(loaded.activeIndex.coerceAtMost(rows.size - 1))
            }
        }

        // Sibling tabs strip: one chip per model. Active model is
        // bolded + brighter; tapping a chip flips the pager to it.
        ModelTabsRow(rows = rows, activePage = pagerState.currentPage,
            onSelect = { idx ->
                // Pager scroll happens via LaunchedEffect below.
            },
            onSelectPager = { idx ->
                // No-op — only the pager itself drives selection here.
            }
        )

        com.ai.ui.shared.SwipeEdgeNoMoreOverlay(
            pagerState = pagerState,
            noMoreLabel = "No more models",
            modifier = Modifier.fillMaxSize()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { page ->
                val row = rows[page]
                val provider = AppService.findById(row.providerId)?.id ?: row.providerId
                val scopedProvider = row.scopeProviderId?.let { AppService.findById(it)?.id ?: it }
                val scopedModel = row.scopeModel
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    item {
                        ModelSynthesisCard(
                            provider = provider, model = row.model,
                            scopedProvider = scopedProvider, scopedModel = scopedModel,
                            body = row.content.orEmpty()
                        )
                    }
                    item {
                        ModelCreditsStrip(report)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelTabsRow(
    rows: List<SecondaryResult>,
    activePage: Int,
    onSelect: (Int) -> Unit,
    onSelectPager: (Int) -> Unit
) {
    if (rows.size <= 1) {
        // Single model — no tab strip needed.
        Spacer(modifier = Modifier.height(4.dp))
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEachIndexed { idx, row ->
            val active = idx == activePage
            val scope = row.scopeModel?.let { shortModelName(it) } ?: shortModelName(row.model)
            Text(
                text = scope,
                fontSize = 12.sp,
                color = if (active) AppColors.TextPrimary else AppColors.TextTertiary,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (active) AppColors.Blue.copy(alpha = 0.32f) else AppColors.SurfaceDark
                    )
                    .border(
                        1.dp,
                        if (active) AppColors.Blue.copy(alpha = 0.65f) else AppColors.BorderUnfocused,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ModelSynthesisCard(
    provider: String,
    model: String,
    scopedProvider: String?,
    scopedModel: String?,
    body: String
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(AppColors.Blue.copy(alpha = 0.28f), AppColors.Blue.copy(alpha = 0.05f))
                )
            )
            .border(1.dp, AppColors.Blue.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "🧩", fontSize = 24.sp, modifier = Modifier.padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (scopedProvider != null && scopedModel != null) {
                    Text(
                        text = "Scope: $scopedProvider / ${shortModelName(scopedModel)}",
                        color = AppColors.Blue,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Synthesised by $provider / ${shortModelName(model)}",
                    color = AppColors.TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.modelInfoViewClickable(
                        com.ai.data.AppService.findById(provider), model
                    )
                )
            }
        }
        if (body.isBlank()) {
            Text(
                text = "(no content)",
                color = AppColors.TextTertiary, fontSize = 13.sp
            )
        } else {
            ContentWithThinkSections(analysis = body)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCreditsStrip(report: Report?) {
    val agents = report?.agents
        ?.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        .orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Synthesised from",
            color = AppColors.Blue,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        if (agents.isEmpty()) {
            Text(
                text = "(no source agents)",
                color = AppColors.TextTertiary, fontSize = 12.sp
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                agents.forEach { a ->
                    val prov = AppService.findById(a.provider)?.id ?: a.provider
                    val icon = a.icon?.takeIf { it.isNotBlank() } ?: "🤖"
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppColors.Blue.copy(alpha = 0.18f))
                            .border(1.dp, AppColors.Blue.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = icon, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
                        Text(
                            text = "$prov / ${shortModelName(a.model)}",
                            color = AppColors.TextPrimary,
                            fontSize = 11.sp,
                            maxLines = 1, softWrap = false
                        )
                    }
                }
            }
        }
    }
}
