package com.ai.ui.report.manage.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.helpers.ContentWithThinkSections
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.ReasoningEffortCandidate
import com.ai.viewmodel.ReasoningEffortSweepState

private val ReasoningEffortSweepOptions = listOf<String?>(null, "low", "medium", "high")
private val DefaultReasoningEffortSweepKeys = setOf("low", "high")

@Composable
internal fun ReasoningEffortSweepScreen(
    reportId: String,
    agentId: String,
    modelLabel: String,
    state: ReasoningEffortSweepState?,
    onSubmit: (List<String?>) -> Unit,
    onUseCandidate: (Int) -> Unit,
    onTrace: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var selectedEffortKeys by remember(reportId, agentId) { mutableStateOf(DefaultReasoningEffortSweepKeys) }
    var locallySubmittedEfforts by remember(reportId, agentId) { mutableStateOf<List<String?>?>(null) }
    val selectedEfforts = remember(selectedEffortKeys) {
        ReasoningEffortSweepOptions.filter { optionKey(it) in selectedEffortKeys }
    }
    val displayState = state ?: locallySubmittedEfforts?.let { efforts ->
        ReasoningEffortSweepState(
            reportId = reportId,
            agentId = agentId,
            candidates = efforts.map { effort -> ReasoningEffortCandidate.Pending(effort) },
            isRunning = true
        )
    }
    val running = displayState?.isRunning == true
    val candidates = displayState?.candidates.orEmpty()
    val canSubmit = selectedEfforts.isNotEmpty() && !running

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_single_result",
            title = "Reasoning Effort",
            subject = modelLabel,
            onBackClick = onBack
        )

        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Candidates", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ReasoningEffortSweepOptions.forEach { effort ->
                        val key = optionKey(effort)
                        ReasoningEffortOptionPill(
                            effort = effort,
                            selected = key in selectedEffortKeys,
                            enabled = !running,
                            onClick = {
                                selectedEffortKeys =
                                    if (key in selectedEffortKeys) selectedEffortKeys - key
                                    else selectedEffortKeys + key
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Button(
                    onClick = {
                        locallySubmittedEfforts = selectedEfforts
                        onSubmit(selectedEfforts)
                    },
                    enabled = canSubmit,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        if (running) "Running…"
                        else "Submit ${selectedEfforts.size} call${if (selectedEfforts.size == 1) "" else "s"}",
                        maxLines = 1,
                        softWrap = false
                    )
                }
                displayState?.unavailableMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = AppColors.Red, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (candidates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text("Submit to compare reasoning-effort candidates.", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { candidates.size })
            Text(
                text = "${pagerState.currentPage + 1} / ${candidates.size}",
                color = AppColors.TextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ReasoningEffortCandidatePanel(
                    candidate = candidates[page],
                    index = page,
                    onUseCandidate = onUseCandidate,
                    onTrace = onTrace
                )
            }
        }
    }
}

@Composable
private fun ReasoningEffortOptionPill(
    effort: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = if (selected) AppColors.Indigo else AppColors.SurfaceDark
    val textColor = if (selected) AppColors.TextPrimary else AppColors.TextSecondary
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            if (selected) "✓ ${reasoningEffortLabel(effort)}" else reasoningEffortLabel(effort),
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ReasoningEffortCandidatePanel(
    candidate: ReasoningEffortCandidate,
    index: Int,
    onUseCandidate: (Int) -> Unit,
    onTrace: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 ${reasoningEffortLabel(candidate.effort)}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.Indigo)
                Spacer(modifier = Modifier.width(8.dp))
                if (candidate is ReasoningEffortCandidate.Running) {
                    CircularProgressIndicator(modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(reasoningCandidateStatus(candidate), fontSize = 12.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.weight(1f))
                (candidate as? ReasoningEffortCandidate.Success)?.cost?.let { cost ->
                    Text("${formatCents(cost)} ¢", color = AppColors.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                val traceFile = when (candidate) {
                    is ReasoningEffortCandidate.Success -> candidate.traceFile
                    is ReasoningEffortCandidate.Error -> candidate.traceFile
                    else -> null
                }
                traceFile?.let { fn ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(com.ai.data.MetadataIconsHolder.current.traces, fontSize = 16.sp, modifier = Modifier.clickable { onTrace(fn) })
                }
            }
            when (candidate) {
                is ReasoningEffortCandidate.Pending -> Unit
                is ReasoningEffortCandidate.Running -> Unit
                is ReasoningEffortCandidate.Error -> {
                    Text(candidate.message, color = AppColors.Red, fontSize = 14.sp)
                }
                is ReasoningEffortCandidate.Success -> {
                    Button(
                        onClick = { onUseCandidate(index) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Use this response", maxLines = 1, softWrap = false) }
                    Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            ContentWithThinkSections(analysis = candidate.response)
                        }
                    }
                }
            }
        }
    }
}

private fun reasoningCandidateStatus(candidate: ReasoningEffortCandidate): String = when (candidate) {
    is ReasoningEffortCandidate.Pending -> "queued"
    is ReasoningEffortCandidate.Running -> "running"
    is ReasoningEffortCandidate.Success -> "success"
    is ReasoningEffortCandidate.Error -> "error"
}

private fun reasoningEffortLabel(effort: String?): String =
    effort?.replaceFirstChar { it.uppercase() } ?: "None"

private fun optionKey(effort: String?): String = effort ?: "none"
