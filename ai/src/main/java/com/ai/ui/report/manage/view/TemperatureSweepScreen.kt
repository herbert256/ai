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
import com.ai.data.TemperatureRange
import com.ai.ui.helpers.ContentWithThinkSections
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.TemperatureSweepCandidate
import com.ai.viewmodel.TemperatureSweepState
import java.util.Locale

@Composable
internal fun TemperatureSweepScreen(
    reportId: String,
    agentId: String,
    modelLabel: String,
    temperatureRange: TemperatureRange = TemperatureRange.Default,
    state: TemperatureSweepState?,
    onSubmit: (List<Float>) -> Unit,
    onUseCandidate: (Int) -> Unit,
    onTrace: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var tempTexts by rememberSaveable(reportId, agentId, temperatureRange.min, temperatureRange.max) {
        mutableStateOf(defaultTemperatureTexts(temperatureRange))
    }
    var locallySubmittedTemps by remember(reportId, agentId) { mutableStateOf<List<Float>?>(null) }
    val parsedTemps = remember(tempTexts) { tempTexts.map { it.toFloatOrNull() } }
    val validTemps = parsedTemps.filterNotNull()
    val allValid = validTemps.size == 3 && validTemps.all { temperatureRange.contains(it) }
    val displayState = state ?: locallySubmittedTemps?.let { temps ->
        TemperatureSweepState(
            reportId = reportId,
            agentId = agentId,
            candidates = temps.map { TemperatureSweepCandidate.Pending(it) },
            isRunning = true
        )
    }
    val running = displayState?.isRunning == true
    val candidates = displayState?.candidates.orEmpty()

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
                Text(
                    "Allowed temperature: ${formatTemperature(temperatureRange.min)}..${formatTemperature(temperatureRange.max)}",
                    fontSize = 12.sp,
                    color = AppColors.TextTertiary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    tempTexts.forEachIndexed { index, text ->
                        OutlinedTextField(
                            value = text,
                            onValueChange = { next ->
                                tempTexts = tempTexts.mapIndexed { i, old ->
                                    if (i == index) next.take(8) else old
                                }
                            },
                            label = { Text("T${index + 1}") },
                            singleLine = true,
                            enabled = !running,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = parsedTemps.getOrNull(index)?.let { !temperatureRange.contains(it) } ?: true,
                            colors = AppColors.outlinedFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Button(
                    onClick = {
                        locallySubmittedTemps = validTemps
                        onSubmit(validTemps)
                    },
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
                displayState?.unavailableMessage?.takeIf { it.isNotBlank() }?.let {
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
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🌡️ ${formatTemperature(candidate.temperature)}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.Orange)
                Spacer(modifier = Modifier.width(8.dp))
                if (candidate is TemperatureSweepCandidate.Running) {
                    CircularProgressIndicator(modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(candidateStatus(candidate), fontSize = 12.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.weight(1f))
                (candidate as? TemperatureSweepCandidate.Success)?.cost?.let { cost ->
                    Text("${formatCents(cost)} ¢", color = AppColors.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                val traceFile = when (candidate) {
                    is TemperatureSweepCandidate.Success -> candidate.traceFile
                    is TemperatureSweepCandidate.Error -> candidate.traceFile
                    else -> null
                }
                traceFile?.let { fn ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("🐞", fontSize = 16.sp, modifier = Modifier.clickable { onTrace(fn) })
                }
            }
            when (candidate) {
                is TemperatureSweepCandidate.Pending -> Unit
                is TemperatureSweepCandidate.Running -> Unit
                is TemperatureSweepCandidate.Error -> {
                    Text(candidate.message, color = AppColors.Red, fontSize = 14.sp)
                }
                is TemperatureSweepCandidate.Success -> {
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

private fun candidateStatus(candidate: TemperatureSweepCandidate): String = when (candidate) {
    is TemperatureSweepCandidate.Pending -> "queued"
    is TemperatureSweepCandidate.Running -> "running"
    is TemperatureSweepCandidate.Success -> "success"
    is TemperatureSweepCandidate.Error -> "error"
}

private fun formatTemperature(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

private fun defaultTemperatureTexts(range: TemperatureRange): List<String> =
    listOf(range.min, (range.min + range.max) / 2f, range.max).map(::formatTemperature)
