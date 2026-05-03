package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Helper id-derivation shared between the result-page summary and
 *  this detail screen so the same group of TRANSLATE rows always lands
 *  under the same key. New rows carry [SecondaryResult.translationRunId];
 *  legacy rows fall back to a per-language synthetic id so they still
 *  cluster correctly. */
internal fun translationRunGroupingId(r: SecondaryResult): String =
    r.translationRunId ?: "lang:${r.targetLanguage ?: ""}"

/**
 * Lists every API call inside a single Translate invocation: prompt,
 * each successful agent response, and any meta translations. Reached
 * from the "translation run" row on the report result page; tapping
 * a call drills into [SecondaryResultDetailScreen].
 */
@Composable
internal fun TranslationRunDetailScreen(
    reportId: String,
    runId: String,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }

    val results by produceState(initialValue = emptyList<SecondaryResult>(), reportId, runId, refreshTick) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TRANSLATE)
                .filter { translationRunGroupingId(it) == runId }
                .sortedBy { it.timestamp }
        }
    }

    val openResult = openId?.let { id -> results.firstOrNull { it.id == id } }
    if (openResult != null) {
        // Every row in this screen is a TRANSLATE secondary, so route
        // through the split-screen TranslationCallDetailScreen rather
        // than the generic SecondaryResultDetailScreen — the user
        // wants Original / Translation side-by-side, not the meta-row
        // chrome with Delete / Model / Trace buttons.
        TranslationCallDetailScreen(
            result = openResult,
            onBack = { openId = null },
            onNavigateHome = onNavigateHome,
            onNavigateToTraceFile = onNavigateToTraceFile
        )
        return
    }

    val first = results.firstOrNull()
    val title = first?.targetLanguage?.let { "Translation: $it" } ?: "Translation run"
    val totalCost = results.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }

    // Every call in a translation run shares the same model — show
    // it once at the top instead of repeating it on every row.
    val providerService = first?.providerId?.let { AppService.findById(it) }
    val providerDisplay = providerService?.displayName ?: first?.providerId.orEmpty()
    val modelName = first?.model.orEmpty()

    // Trace lookup for the bottom Trace button — pick the most recent
    // trace tagged with this report id + the run's model. All calls in
    // the run share that model so any single match is representative.
    val traceFilename by produceState<String?>(initialValue = null, reportId, modelName) {
        if (modelName.isBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == reportId && it.model == modelName }
                .maxByOrNull { it.timestamp }?.filename
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = title, onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        if (results.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No translation calls", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
            return@Column
        }

        // Single model header for the whole run.
        Text("$providerDisplay — $modelName",
            fontSize = 14.sp, color = AppColors.Blue,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)) {
            Text("${results.size} calls", fontSize = 11.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
            if (totalCost > 0.0) {
                Text("${formatCents(totalCost)} ¢", fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results, key = { it.id }) { r ->
                val what = r.agentName.removePrefix("Translate:").trim().ifBlank { r.agentName }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { openId = r.id }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusEmoji = when {
                        r.errorMessage != null -> "❌"
                        r.content.isNullOrBlank() -> "⏳"
                        else -> "✅"
                    }
                    Text(statusEmoji, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(what, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val cost = (r.inputCost ?: 0.0) + (r.outputCost ?: 0.0)
                    if (cost > 0.0) {
                        Text(formatCents(cost), fontSize = 10.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }

        // Bottom action row — Model / Delete / Trace, mirroring
        // SecondaryResultDetailScreen. Delete drops every TRANSLATE
        // row in this run after confirmation.
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { providerService?.let { onNavigateToModelInfo(it, modelName) } },
                enabled = providerService != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Model", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Delete", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { traceFilename?.let(onNavigateToTraceFile) },
                enabled = traceFilename != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Trace", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this translation run?") },
            text = {
                Text(
                    "Drops every translation call (${results.size}) for " +
                        (first?.targetLanguage ?: "this run") + " from the report. Can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // Delete every TRANSLATE row in this run. The
                    // produceState reload below picks up the empty
                    // list and the screen pops back via onBack.
                    val ids = results.map { it.id }
                    ids.forEach { onDelete(it) }
                    refreshTick++
                    onBack()
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}
