package com.ai.ui.report

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

private val singleConclusionTagRegex = Regex("<conclusion>.*?</conclusion>", RegexOption.DOT_MATCHES_ALL)
private val singleMotivationTagRegex = Regex("<motivation>.*?</motivation>", RegexOption.DOT_MATCHES_ALL)

/**
 * Per-model result viewer reached by tapping a model row on the Report
 * result screen. Shows just the chosen agent's rendered response
 * (conclusion / motivation extraction, citations, search results,
 * related questions) followed by a three-button action row: Remove
 * model from report, Open Model Info, Show trace entry.
 *
 * Distinct from [ReportsViewerScreen], which is the all-agents picker
 * reached from the View → Results button.
 */
@Composable
fun ReportSingleResultScreen(
    reportId: String,
    agentId: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onRemoveAgent: (String, String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val report = remember(reportId) { ReportStorage.getReport(context, reportId) }
    val agent = report?.agents?.find { it.agentId == agentId }
    val provider = agent?.let { AppService.findById(it.provider) }

    if (report == null || agent == null || provider == null) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TitleBar(title = "View result", onBackClick = onBack, onAiClick = onNavigateHome)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Result not found", color = AppColors.TextSecondary, fontSize = 16.sp)
            }
        }
        return
    }

    val traceFilename = remember(reportId, agent.model, agent.agentId) {
        ApiTracer.getTraceFiles()
            .filter { it.reportId == reportId && it.model == agent.model }
            .maxByOrNull { it.timestamp }?.filename
    }

    var confirmRemove by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(title = provider.displayName, onBackClick = onBack, onAiClick = onNavigateHome)

        Text(
            "${provider.displayName} — ${agent.model}",
            fontSize = 18.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            val rawBody = agent.responseBody
            val errorMessage = agent.errorMessage
            when {
                !errorMessage.isNullOrBlank() -> {
                    Column {
                        Text("Error", fontSize = 16.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary)
                    }
                }
                rawBody.isNullOrBlank() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No analysis available", color = AppColors.TextSecondary, fontSize = 16.sp)
                    }
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        val conclusion = extractTagContent(rawBody, "conclusion")
                        val motivation = extractTagContent(rawBody, "motivation")
                        val strippedBody = if (conclusion != null || motivation != null)
                            rawBody.replace(singleConclusionTagRegex, "").replace(singleMotivationTagRegex, "").trim()
                        else rawBody

                        if (conclusion != null) {
                            Text("Conclusion", fontSize = 18.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            ContentWithThinkSections(analysis = conclusion)
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (motivation != null) {
                            Text("Motivation", fontSize = 18.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            ContentWithThinkSections(analysis = motivation)
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (strippedBody.isNotBlank()) {
                            if (conclusion != null || motivation != null) {
                                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            ContentWithThinkSections(analysis = strippedBody)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }

        // Bottom action row: Remove / Model Info / Trace.
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { confirmRemove = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Remove", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { onNavigateToModelInfo(provider, agent.model) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Model Info", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { traceFilename?.let(onNavigateToTraceFile) },
                enabled = traceFilename != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Trace", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove from report?") },
            text = {
                Text(
                    "Drop ${provider.displayName} / ${agent.model} from this report. " +
                        "Removes the saved response and recomputes totals; can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemoveAgent(reportId, agentId)
                    onBack()
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
}
