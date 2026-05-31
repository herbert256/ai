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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.helpers.ContentWithThinkSections
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.TemperatureSweepCandidate
import com.ai.viewmodel.TemperatureSweepState
import java.util.Locale

@Composable
internal fun TemperatureSweepScreen(
    reportId: String,
    agentId: String,
    modelLabel: String,
    state: TemperatureSweepState?,
    onSubmit: (List<Float>) -> Unit,
    onUseCandidate: (Int) -> Unit,
    onTrace: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var tempTexts by rememberSaveable(reportId, agentId) { mutableStateOf(listOf("0", "1", "2")) }
    val parsedTemps = remember(tempTexts) { tempTexts.map { it.toFloatOrNull() } }
    val validTemps = parsedTemps.filterNotNull()
    val allValid = validTemps.size == 3 && validTemps.all { it in 0f..2f }
    val running = state?.isRunning == true
    val candidates = state?.candidates.orEmpty()

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_single_result",
            title = "Temperature sweep",
            subject = modelLabel,
            onBackClick = onBack
        )

        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Run three variants of this model response.", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    tempTexts.forEachIndexed { index, text ->
                        OutlinedTextField(
                            value = text,
                            onValueChange = { next ->
                                tempTexts = tempTexts.mapIndexed { i, old ->
                                    if (i == index) next.filter { ch -> ch.isDigit() || ch == '.' }.take(4) else old
                                }
                            },
                            label = { Text("T${index + 1}") },
                            singleLine = true,
                            enabled = !running,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = parsedTemps.getOrNull(index)?.let { it !in 0f..2f } ?: true,
                            colors = AppColors.outlinedFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Button(
                    onClick = { onSubmit(validTemps) },
                    enabled = allValid && !running,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (running) "Running…" else "Submit", maxLines = 1, softWrap = false)
                }
                state?.unavailableMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = AppColors.Red, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (candidates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text("Choose temperatures and submit to compare candidates.", color = AppColors.TextTertiary, fontSize = 13.sp)
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
                TemperatureCandidatePanel(
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
private fun TemperatureCandidatePanel(
    candidate: TemperatureSweepCandidate,
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
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🌡️ ${formatTemperature(candidate.temperature)}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppColors.Orange)
                Spacer(modifier = Modifier.width(8.dp))
                Text(candidateStatus(candidate), fontSize = 13.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (candidate) {
                is TemperatureSweepCandidate.Pending -> {
                    Text("Queued", color = AppColors.TextTertiary, fontSize = 14.sp)
                }
                is TemperatureSweepCandidate.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Calling API…", color = AppColors.TextSecondary, fontSize = 14.sp)
                    }
                }
                is TemperatureSweepCandidate.Error -> {
                    Text(candidate.message, color = AppColors.Red, fontSize = 14.sp)
                    CandidateMeta(
                        durationMs = candidate.durationMs,
                        cost = null,
                        tokenText = candidate.httpStatusCode?.let { "HTTP $it" },
                        traceFile = candidate.traceFile,
                        onTrace = onTrace
                    )
                }
                is TemperatureSweepCandidate.Success -> {
                    CandidateMeta(
                        durationMs = candidate.durationMs,
                        cost = candidate.cost,
                        tokenText = candidate.tokenUsage?.let {
                            "in ${it.inputTokens} / out ${it.outputTokens} / total ${it.totalTokens}"
                        },
                        traceFile = candidate.traceFile,
                        onTrace = onTrace
                    )
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

@Composable
private fun CandidateMeta(
    durationMs: Long?,
    cost: Double?,
    tokenText: String?,
    traceFile: String?,
    onTrace: (String) -> Unit
) {
    val pieces = buildList {
        durationMs?.let { add("${it}ms") }
        cost?.let { add(String.format(Locale.US, "$%.6f", it)) }
        tokenText?.let { add(it) }
    }
    if (pieces.isNotEmpty() || traceFile != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(pieces.joinToString(" • "), color = AppColors.TextTertiary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            traceFile?.let { fn ->
                TextButton(onClick = { onTrace(fn) }) {
                    Text("🐞", fontSize = 16.sp)
                }
            }
        }
    }
}

private fun candidateStatus(candidate: TemperatureSweepCandidate): String = when (candidate) {
    is TemperatureSweepCandidate.Pending -> "queued"
    is TemperatureSweepCandidate.Running -> "running"
    is TemperatureSweepCandidate.Success -> "success"
    is TemperatureSweepCandidate.Error -> "error"
}

private fun formatTemperature(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
