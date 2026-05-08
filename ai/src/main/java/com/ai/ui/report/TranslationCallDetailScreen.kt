package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.modelInfoClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-call detail screen for a single TRANSLATE secondary. Reached
 * from [TranslationRunDetailScreen] when the user taps one of the
 * run's calls. Renders the source text on top and the translation
 * underneath as two independently scrollable panes; the title row
 * carries a 🐞 ladybug link to the matching API trace so the user
 * can drill into the raw request/response without leaving the flow.
 */
@Composable
internal fun TranslationCallDetailScreen(
    result: SecondaryResult,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val titleLang = result.targetLanguage?.let { "Translate · $it" } ?: "Translate"

    // Resolve everything we need about the source item in one IO read:
    // its content, the model that produced it, the trace file (when
    // one exists), and — for META rows — the user-given Meta prompt
    // name so the source-side header reads "Compare", "Critique",
    // "Synthesize", etc. instead of a hardcoded label. Driven by
    // translateSourceKind + translateSourceTargetId stamped on the
    // row in saveTranslationSecondaries.
    data class SourceInfo(val content: String?, val model: String?, val providerId: String?, val traceFilename: String?, val metaName: String?)
    val empty = SourceInfo(null, null, null, null, null)
    val source by produceState(initialValue = empty, result.id) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext empty
            when (result.translateSourceKind) {
                // The prompt is user-typed text — no source model, no trace.
                "PROMPT" -> SourceInfo(report.prompt, null, null, null, null)
                "AGENT" -> {
                    val targetId = result.translateSourceTargetId ?: return@withContext empty
                    val agent = report.agents.firstOrNull { it.agentId == targetId && it.reportStatus == ReportStatus.SUCCESS }
                    val tf = agent?.let {
                        ApiTracer.getTraceFiles()
                            .filter { t -> t.reportId == result.reportId && t.model == it.model }
                            .maxByOrNull { t -> t.timestamp }?.filename
                    }
                    SourceInfo(agent?.responseBody, agent?.model, agent?.provider, tf, null)
                }
                "META" -> {
                    val targetId = result.translateSourceTargetId ?: return@withContext empty
                    val sec = SecondaryResultStorage.get(context, result.reportId, targetId)
                    val tf = sec?.let {
                        ApiTracer.getTraceFiles()
                            .filter { t -> t.reportId == result.reportId && t.model == it.model }
                            .minByOrNull { t -> kotlin.math.abs(t.timestamp - it.timestamp) }?.filename
                    }
                    SourceInfo(sec?.content, sec?.model, sec?.providerId, tf, sec?.metaPromptName)
                }
                else -> empty
            }
        }
    }

    // Trace for this translation call itself: same report id + this
    // call's translation model, closest timestamp.
    val translationTraceFilename by produceState<String?>(initialValue = null, result.id) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == result.reportId && it.model == result.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - result.timestamp) }?.filename
        }
    }

    // Source-side label: model name when there is one (AGENT / META
    // rows carry the originating model on the source SecondaryResult);
    // for PROMPT rows the prompt is user-typed and has no model, so
    // fall back to "Prompt". META rows that pre-date metaPromptName
    // fall back to the kind label.
    val sourceLabel = source.model?.takeIf { it.isNotBlank() }
        ?: when (result.translateSourceKind) {
            "PROMPT" -> "Prompt"
            "META" -> source.metaName?.takeIf { it.isNotBlank() } ?: "Meta"
            else -> "Source"
        }
    val translationLabel = result.model.ifBlank { "Translation" }
    val totalCost = (result.inputCost ?: 0.0) + (result.outputCost ?: 0.0)

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Half-screen cap on the Original pane: when the source content
        // is short the pane wraps to its actual height and Translation
        // takes the rest of the screen. With long content, the cap
        // keeps both panes in view; the inner verticalScroll picks up
        // any overflow.
        val halfMax = maxHeight / 2
        Column(modifier = Modifier.fillMaxSize()) {
            val translationProviderService = com.ai.data.AppService.findById(result.providerId)
            val navToModelInfo = com.ai.ui.shared.LocalNavigateToModelInfo.current
            val traceEnabled = ApiTracer.isTracingEnabled && translationTraceFilename != null
            TitleBar(
                helpTopic = "translation_call",
                title = titleLang, onBackClick = onBack,
                onTrace = if (traceEnabled) { { onNavigateToTraceFile(translationTraceFilename!!) } } else null,
                onInfo = if (translationProviderService != null && result.model.isNotBlank()) {
                    { navToModelInfo(translationProviderService, result.model) }
                } else null
            )
            if (totalCost > 0.0) {
                Text("Cost: ${formatCents(totalCost)} ¢",
                    fontSize = 12.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            when {
                result.errorMessage != null -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                        Text(result.errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                else -> {
                    // Original pane — wraps to content height, capped at
                    // half the screen so it can't push Translation out.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = halfMax)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            val sourceProviderService = source.providerId?.let { com.ai.data.AppService.findById(it) }
                            Text(sourceLabel,
                                fontSize = 14.sp, color = AppColors.Blue, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                                    .modelInfoClickable(sourceProviderService, source.model.orEmpty()))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            val src = source.content
                            if (src.isNullOrBlank()) {
                                Text("(source content not found)", color = AppColors.TextTertiary, fontSize = 13.sp)
                            } else {
                                ContentWithThinkSections(analysis = src)
                            }
                        }
                    }

                    HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)

                    // Translation pane — fills the remaining space.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(translationLabel,
                                fontSize = 14.sp, color = AppColors.Green, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                                    .modelInfoClickable(translationProviderService, result.model))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            val tx = result.content
                            if (tx.isNullOrBlank()) {
                                Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
                            } else {
                                ContentWithThinkSections(analysis = tx)
                            }
                        }
                    }
                }
            }
        }
    }
}

