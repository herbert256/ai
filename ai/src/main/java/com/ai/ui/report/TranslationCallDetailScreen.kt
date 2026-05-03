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
    // its content, the model that produced it, and the trace file
    // (when one exists). Driven by translateSourceKind +
    // translateSourceTargetId stamped on the row in
    // saveTranslationSecondaries.
    data class SourceInfo(val content: String?, val model: String?, val traceFilename: String?)
    val source by produceState(initialValue = SourceInfo(null, null, null), result.id) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId)
                ?: return@withContext SourceInfo(null, null, null)
            when (result.translateSourceKind) {
                // The prompt is user-typed text — no source model, no trace.
                "PROMPT" -> SourceInfo(report.prompt, null, null)
                "AGENT" -> {
                    val targetId = result.translateSourceTargetId ?: return@withContext SourceInfo(null, null, null)
                    val agent = report.agents.firstOrNull { it.agentId == targetId && it.reportStatus == ReportStatus.SUCCESS }
                    val tf = agent?.let {
                        ApiTracer.getTraceFiles()
                            .filter { t -> t.reportId == result.reportId && t.model == it.model }
                            .maxByOrNull { t -> t.timestamp }?.filename
                    }
                    SourceInfo(agent?.responseBody, agent?.model, tf)
                }
                "SUMMARY", "COMPARE" -> {
                    val targetId = result.translateSourceTargetId ?: return@withContext SourceInfo(null, null, null)
                    val sec = SecondaryResultStorage.get(context, result.reportId, targetId)
                    val tf = sec?.let {
                        ApiTracer.getTraceFiles()
                            .filter { t -> t.reportId == result.reportId && t.model == it.model }
                            .minByOrNull { t -> kotlin.math.abs(t.timestamp - it.timestamp) }?.filename
                    }
                    SourceInfo(sec?.content, sec?.model, tf)
                }
                else -> SourceInfo(null, null, null)
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(title = titleLang, onBackClick = onBack, onAiClick = onNavigateHome)

        // Two-line header at the top — one row per model, in the same
        // visual style as the section labels below. Source model line
        // is omitted when the row translated something with no model
        // (PROMPT). Cost row sums input + output cents for this single
        // translation call.
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            source.model?.let { srcModel ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Report: $srcModel",
                        fontSize = 14.sp, color = AppColors.Blue,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    source.traceFilename?.let { tf ->
                        Text("🐞", fontSize = 18.sp,
                            modifier = Modifier.padding(start = 8.dp).clickable { onNavigateToTraceFile(tf) })
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Translation: ${result.model}",
                    fontSize = 14.sp, color = AppColors.Green,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                translationTraceFilename?.let { tf ->
                    Text("🐞", fontSize = 18.sp,
                        modifier = Modifier.padding(start = 8.dp).clickable { onNavigateToTraceFile(tf) })
                }
            }
            val totalCost = (result.inputCost ?: 0.0) + (result.outputCost ?: 0.0)
            if (totalCost > 0.0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text("Cost: ${formatCents(totalCost)} ¢",
                    fontSize = 12.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
            }
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
                // Top pane — original.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Original", fontSize = 14.sp, color = AppColors.Blue, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        val src = source.content
                        if (src.isNullOrBlank()) {
                            Text("(source content not found)", color = AppColors.TextTertiary, fontSize = 13.sp)
                        } else {
                            ContentWithThinkSections(analysis = src)
                        }
                    }
                }

                HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)

                // Bottom pane — translation.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Translation", fontSize = 14.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
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

