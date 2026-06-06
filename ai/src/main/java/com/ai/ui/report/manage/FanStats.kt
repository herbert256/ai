package com.ai.ui.report.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.FanOutHttpStatusRow
import com.ai.data.FanOutRunState
import com.ai.data.RunHttpStats
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
internal fun FanOutStatsScreen(
    run: FanOutRunState,
    onBack: () -> Unit
) {
    // In-memory only: every HTTP response (incl. 200 and retried-away 429s) is
    // tallied per run by RunHttpStats. Keyed off the run id carried on the
    // pairs (same source FanL1 uses); resets on app restart.
    val stats = remember(run) {
        RunHttpStats.statsForRun(run.pairs.values.firstNotNullOfOrNull { it.runId })
    }
    val subject = run.metaPrompt.title.takeIf { it.isNotBlank() }
        ?.let { "${run.metaPrompt.name} - $it" } ?: run.metaPrompt.name

    Column(
        modifier = Modifier.fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "secondary_fan_out_stats",
            title = "Fan out statistics",
            subject = subject,
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics
        )
        Spacer(Modifier.height(8.dp))
        BatchStatsRow(
            listOf(
                Triple("Responses", stats.totalResponses.toString(), AppColors.InfoAccent),
                Triple("Models", stats.modelCount.toString(), AppColors.PrimaryAccent)
            )
        )
        Spacer(Modifier.height(10.dp))

        if (stats.rows.isEmpty()) {
            Text("No HTTP responses recorded this session.", color = AppColors.TextSecondary, fontSize = 13.sp)
        } else {
            val scroll = rememberScrollState()
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                item {
                    Row(Modifier.horizontalScroll(scroll).padding(vertical = 4.dp)) {
                        HeaderCell("Model", 176.dp, TextAlign.Start)
                        HeaderCell("Total", 48.dp)
                        HeaderCell("200", 44.dp)
                        HeaderCell("429", 44.dp)
                        HeaderCell("529", 44.dp)
                        HeaderCell("4xx", 44.dp)
                        HeaderCell("5xx", 44.dp)
                        HeaderCell("Other", 54.dp)
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
                items(stats.rows, key = { it.modelKey }) { row ->
                    FanOutStatsRow(row, Modifier.horizontalScroll(scroll))
                    HorizontalDivider(color = AppColors.DividerDark)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun FanOutStatsRow(row: FanOutHttpStatusRow, modifier: Modifier = Modifier) {
    val c = row.counts
    Row(modifier.padding(vertical = 7.dp)) {
        Text(
            resolveModelLabel(row.modelKey),
            fontSize = 13.sp,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(176.dp)
        )
        CountCell(c.total, 48.dp, AppColors.TextPrimary)
        CountCell(c.ok200, 44.dp, AppColors.SuccessAccent)
        CountCell(c.rate429, 44.dp, countColor(c.rate429, AppColors.WarningAccent))
        CountCell(c.overloaded529, 44.dp, countColor(c.overloaded529, AppColors.DangerAccent))
        CountCell(c.client4xx, 44.dp, countColor(c.client4xx, AppColors.WarningAccent))
        CountCell(c.server5xx, 44.dp, countColor(c.server5xx, AppColors.DangerAccent))
        CountCell(c.other, 54.dp, countColor(c.other, AppColors.TextSecondary))
    }
}

@Composable
private fun HeaderCell(label: String, width: Dp, align: TextAlign = TextAlign.End) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextSecondary,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier.width(width)
    )
}

@Composable
private fun CountCell(value: Int, width: Dp, color: Color) {
    Text(
        value.toString(),
        fontSize = 13.sp,
        color = color,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(width)
    )
}

private fun countColor(value: Int, active: Color): Color =
    if (value > 0) active else AppColors.TextDim
