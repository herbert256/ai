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
import com.ai.data.AppService
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
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
    val provider = AppService.findById(result.providerId)?.displayName ?: result.providerId
    val what = result.agentName.removePrefix("Translate:").trim().ifBlank { result.agentName }
    val titleLang = result.targetLanguage?.let { "Translate · $it" } ?: "Translate"

    // Resolve the original text based on what this row translated. The
    // source kind + targetId pair is stamped on the row when the
    // translation flow saves it (see saveTranslationSecondaries).
    val originalContent by produceState<String?>(initialValue = null, result.id) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext null
            when (result.translateSourceKind) {
                "PROMPT" -> report.prompt
                "AGENT" -> {
                    val targetId = result.translateSourceTargetId ?: return@withContext null
                    report.agents.firstOrNull { it.agentId == targetId && it.reportStatus == ReportStatus.SUCCESS }
                        ?.responseBody
                }
                "SUMMARY", "COMPARE" -> {
                    val targetId = result.translateSourceTargetId ?: return@withContext null
                    SecondaryResultStorage.get(context, result.reportId, targetId)?.content
                }
                else -> null
            }
        }
    }

    // Look up the API trace for this translation call: same report id,
    // same model, closest timestamp. Drives the 🐞 link visibility +
    // navigation target.
    val traceFilename by produceState<String?>(initialValue = null, result.id) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == result.reportId && it.model == result.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - result.timestamp) }?.filename
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(title = titleLang, onBackClick = onBack, onAiClick = onNavigateHome)

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(what, fontSize = 13.sp, color = AppColors.TextSecondary,
                    maxLines = 1, fontWeight = FontWeight.SemiBold)
                Text("$provider — ${result.model}", fontSize = 13.sp, color = AppColors.Blue,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
            // 🐞 trace link — only emitted when a trace file exists.
            // Tapping routes to the trace detail screen.
            val tf = traceFilename
            if (tf != null) {
                Text(
                    "🐞", fontSize = 18.sp,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { onNavigateToTraceFile(tf) }
                )
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
                        val src = originalContent
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

