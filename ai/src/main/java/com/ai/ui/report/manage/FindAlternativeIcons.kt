package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.ReportModel
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.modelInfoClickable
import com.ai.viewmodel.IconCandidate

/** Live progress list for an icon fan-out. One row per
 *  (provider, model): ⏳ while running, the returned emoji once
 *  done, ❌ on error. Done rows are clickable — tapping commits
 *  that emoji + model label to the Report. */
@Composable
fun AlternativeIconsScreen(
    candidates: List<IconCandidate>,
    reportId: String,
    onPickIcon: (emoji: String, iconModel: String) -> Unit,
    onRestart: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "alternative_icons", title = "Alternative icons", onBackClick = onBack)

        // Stable order: by provider id, then model id — so re-renders
        // as candidates flip Running → Done/Error don't reshuffle rows.
        val ordered = remember(candidates) {
            candidates.sortedWith(compareBy({ it.provider.id.lowercase() }, { it.model.lowercase() }))
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ordered.isEmpty()) {
                androidx.compose.material3.Text(
                    "Nothing here yet. Pick models on the previous screen and tap Find Icons.",
                    fontSize = 12.sp, color = AppColors.TextTertiary
                )
            } else {
                ordered.forEach { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        reportId = reportId,
                        onPickIcon = onPickIcon,
                        onNavigateToTraceFile = onNavigateToTraceFile
                    )
                }
            }
        }

        // Restart sits at the bottom — destructive ish (drops the
        // current list + cancels in-flight calls) so it lives away
        // from the per-row tap target. Costs from completed calls
        // stay bumped on the Report by design.
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRestart,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) {
            androidx.compose.material3.Text("Restart", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: IconCandidate,
    reportId: String,
    onPickIcon: (emoji: String, iconModel: String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit
) {
    val context = LocalContext.current
    val iconModel = "${candidate.provider.id}/${candidate.model}"
    val cost = when (candidate) {
        is IconCandidate.Done -> candidate.cost
        is IconCandidate.Error -> candidate.cost
        is IconCandidate.Running -> 0.0
    }
    // 🐞 lookup — most recent trace for this (reportId, model) pair
    // tagged with the icon fan-out category. Only triggered when
    // tracing is on AND the call has actually run; for Running rows
    // we keep the slot empty so the row layout stays stable when
    // the candidate flips to Done.
    val tracingEnabled = com.ai.data.ApiTracer.isTracingEnabled
    val traceFilenameState = if (!tracingEnabled || candidate is IconCandidate.Running) null
        else androidx.compose.runtime.produceState<String?>(
            initialValue = null, reportId, candidate.model, candidate::class
        ) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.ai.data.ApiTracer.getTraceFiles()
                    .filter { it.reportId == reportId && it.model == candidate.model && it.category == "Report icon (alt)" }
                    .maxByOrNull { it.timestamp }
                    ?.filename
            }
        }
    val traceFilename = traceFilenameState?.value
    val isTappable = candidate is IconCandidate.Done
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (isTappable && candidate is IconCandidate.Done)
                Modifier.clickable { onPickIcon(candidate.emoji, iconModel) }
            else Modifier
        ),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left status cell — animated ⏳ for Running, the emoji for
            // Done, ❌ for Error. The AnimatedHourglass rotates at the
            // same 1500 ms cadence the rest of the app uses so the
            // visual idiom is consistent.
            when (candidate) {
                is IconCandidate.Running -> com.ai.ui.shared.AnimatedHourglass(
                    fontSize = 28.sp,
                    modifier = Modifier.padding(end = 12.dp)
                )
                is IconCandidate.Done -> androidx.compose.material3.Text(
                    candidate.emoji, fontSize = 28.sp, modifier = Modifier.padding(end = 12.dp)
                )
                is IconCandidate.Error -> androidx.compose.material3.Text(
                    "❌", fontSize = 28.sp, modifier = Modifier.padding(end = 12.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                androidx.compose.material3.Text(iconModel, fontSize = 13.sp, color = Color.White)
                when (candidate) {
                    is IconCandidate.Error -> androidx.compose.material3.Text(
                        candidate.reason, fontSize = 11.sp, color = AppColors.Red,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    is IconCandidate.Done -> androidx.compose.material3.Text(
                        "Tap to use this icon",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    is IconCandidate.Running -> { /* no subtitle while running */ }
                }
            }
            // Right side — 🐞 trace icon (when tracing is on and a
            // matching trace exists) then the per-call USD cost.
            if (traceFilename != null) {
                androidx.compose.material3.Text(
                    "🐞", fontSize = 16.sp,
                    modifier = Modifier
                        .clickable { onNavigateToTraceFile(traceFilename) }
                        .padding(horizontal = 6.dp)
                )
            }
            if (cost > 0.0) {
                androidx.compose.material3.Text(
                    "${com.ai.ui.shared.formatCents(cost)} ¢",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppColors.TextTertiary
                )
            }
        }
    }
}
