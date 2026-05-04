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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    onRemoveAgent: (String, String) -> Unit,
    onRegenerateAgent: (String, String) -> Unit = { _, _ -> },
    /** Open a fresh chat session pre-seeded with the report prompt
     *  as the user turn and this agent's response as the assistant
     *  turn, then drop the user into ChatSessionScreen against the
     *  same provider/model so they can keep going. Caller persists
     *  the session and navigates. */
    onContinueInChat: (String, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    // Loaded asynchronously: getReport reads + parses the report JSON
    // (which can be MB-sized for image-attached reports). The Loading
    // → Loaded transition keeps the UI thread free while reading.
    val reportState = produceState<com.ai.data.Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
    }
    val report = reportState.value
    val agent = report?.agents?.find { it.agentId == agentId }
    val provider = agent?.let { AppService.findById(it.provider) }

    if (report == null) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TitleBar(title = "View result", onBackClick = onBack, onAiClick = onNavigateHome)
        }
        return
    }
    if (agent == null || provider == null) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TitleBar(title = "View result", onBackClick = onBack, onAiClick = onNavigateHome)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Result not found", color = AppColors.TextSecondary, fontSize = 16.sp)
            }
        }
        return
    }

    val traceFilenameState = produceState<String?>(initialValue = null, reportId, agent.model, agent.agentId) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == reportId && it.model == agent.model }
                .maxByOrNull { it.timestamp }?.filename
        }
    }
    val traceFilename = traceFilenameState.value

    // For translated reports: load the source agent's response so the
    // "Translation info" button can pop a split-screen original-vs-
    // translation viewer. The translated copy preserves agentId, so a
    // direct match by agentId works.
    val sourceAgentBodyState = produceState<String?>(initialValue = null, report.sourceReportId, agentId) {
        val sid = report.sourceReportId ?: return@produceState
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, sid)?.agents
                ?.firstOrNull { it.agentId == agentId }?.responseBody
        }
    }
    val sourceAgentBody = sourceAgentBodyState.value
    val canShowTranslation = sourceAgentBody != null && !agent.responseBody.isNullOrBlank()
    var showTranslationCompare by remember { mutableStateOf(false) }

    if (showTranslationCompare && sourceAgentBody != null && agent.responseBody != null) {
        TranslationCompareScreen(
            title = "Translation info — ${provider.displayName} / ${agent.model}",
            originalLabel = "Original",
            originalContent = sourceAgentBody,
            translatedLabel = "Translation",
            translatedContent = agent.responseBody!!,
            onBack = { showTranslationCompare = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    var confirmRemove by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(title = provider.displayName, onBackClick = onBack, onAiClick = onNavigateHome)

        // Provider/model header with the trace 🐞 ladybug at the right —
        // tapping it opens the most recent trace for this (report, model).
        // Hidden when no trace was captured for this agent.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${provider.displayName} — ${agent.model}",
                fontSize = 18.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (ApiTracer.isTracingEnabled && traceFilename != null) {
                Text("🐞", fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp).clickable { onNavigateToTraceFile(traceFilename) })
            }
        }

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

        // Bottom action row: Remove / Model Info / Re-run. Trace lives at
        // the top as a 🐞 icon next to the provider/model header.
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
            if (canShowTranslation) {
                Button(
                    onClick = { showTranslationCompare = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Translation info", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            }
            // 4th button on its own row so the longer label fits without
            // squashing the three short buttons above. Kicks off a single-
            // agent re-run and pops back to the report; the row's status
            // icon flips to ⏳ until the new response lands.
            Button(
                onClick = {
                    onRegenerateAgent(reportId, agentId)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text("Call model API again", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            // Continue in chat — open a fresh chat session against
            // the same provider/model with the report's prompt and
            // this agent's response pre-seeded as the first two
            // turns. Disabled when the response is empty / errored
            // since there's nothing to seed the assistant turn with.
            val canContinueInChat = !agent.responseBody.isNullOrBlank() && agent.errorMessage.isNullOrBlank()
            Button(
                onClick = { onContinueInChat(reportId, agentId) },
                enabled = canContinueInChat,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("💬 Continue in chat", fontSize = 13.sp, maxLines = 1, softWrap = false) }
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
