package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.modelInfoClickable
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
    onNavigateToTraceList: () -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    /** Re-runs every errored row in this translation run. Wired to
     *  [com.ai.viewmodel.ReportViewModel.restartFailedTranslations]. */
    onRestartFailed: (String, String) -> Unit = { _, _ -> },
    /** Fills in every expected translation item that has no row in
     *  this run yet (e.g., after an interrupted batch). Wired to
     *  [com.ai.viewmodel.ReportViewModel.startMissingTranslations]. */
    onStartMissing: (String, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    // Saveable so that back-popping from a Compose Navigation
    // destination (e.g. trace file detail) lands the user back on
    // the call detail screen instead of resetting to the run list.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val results by produceState(initialValue = emptyList<SecondaryResult>(), reportId, runId, refreshTick) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TRANSLATE)
                .filter { translationRunGroupingId(it) == runId }
                .sortedBy { it.timestamp }
        }
    }

    // Source META rows for the report — keyed by id so each TRANSLATE
    // row can resolve its translateSourceTargetId into a "type" label
    // (cross-out / cross-in / metaPromptName / "meta"). PROMPT and
    // AGENT rows don't need this lookup; their type is a constant.
    val metaSourcesById by produceState(initialValue = emptyMap<String, SecondaryResult>(), reportId, refreshTick) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .associateBy { it.id }
        }
    }
    // Report agents keyed by agentId — needed so each AGENT-source
    // TRANSLATE row can render its label via modelLabel() and follow
    // the user's "Model name layout" setting (the persisted
    // agentName always contains both, which would override the
    // user's choice).
    val agentsByIdFromReport by produceState(initialValue = emptyMap<String, com.ai.data.ReportAgent>(), reportId, refreshTick) {
        value = withContext(Dispatchers.IO) {
            com.ai.data.ReportStorage.getReport(context, reportId)?.agents
                ?.associateBy { it.agentId } ?: emptyMap()
        }
    }
    fun typeFor(r: SecondaryResult): String = when (r.translateSourceKind) {
        "PROMPT" -> "prompt"
        "AGENT" -> "report"
        "META" -> {
            val src = r.translateSourceTargetId?.let { metaSourcesById[it] }
            when {
                src == null -> "meta"
                src.crossSourceAgentId != null -> "cross-out"
                src.afterCrossOf != null -> "cross-in"
                !src.metaPromptName.isNullOrBlank() -> src.metaPromptName.lowercase()
                else -> "meta"
            }
        }
        else -> ""
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

    // Tally of how many translation traces exist for this report so
    // the bottom Trace button can be disabled when there's nothing to
    // navigate to. The button itself opens the report's trace list
    // pre-filtered to category="Translation" — that yields one row
    // per call in this run (and any other Translate runs on the
    // report, which is acceptable since the list lets the user filter
    // further).
    val translationTraceCount by produceState(initialValue = 0, reportId) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles().count { it.reportId == reportId && it.category == "Translation" }
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

        // Single model header for the whole run. The 🐞 ladybug links to
        // the report's trace list pre-filtered to "Translation"; rendered
        // only when at least one translation trace exists.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(com.ai.ui.shared.modelLabel(providerDisplay, modelName, separator = " — "),
                fontSize = 14.sp, color = AppColors.Blue,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
                    .modelInfoClickable(providerService, modelName))
            if (ApiTracer.isTracingEnabled && translationTraceCount > 0) {
                Text("🐞", fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp).clickable { onNavigateToTraceList() })
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)) {
            Text("${results.size} calls", fontSize = 11.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
            if (totalCost > 0.0) {
                Text("${formatCents(totalCost)} ¢", fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results, key = { it.id }) { r ->
                // Build the row label from the actual source so the
                // "Model name layout" setting is honoured. The stored
                // agentName always contains both provider and model
                // (it's a freeze-at-save-time string, so toggling the
                // setting wouldn't update it). For PROMPT rows the
                // source has no model, so we fall back to "Report
                // prompt" (also handles the legacy stored label).
                val what = when (r.translateSourceKind) {
                    "AGENT" -> {
                        val agent = r.translateSourceTargetId?.let { agentsByIdFromReport[it] }
                        if (agent != null) {
                            val pn = AppService.findById(agent.provider)?.displayName ?: agent.provider
                            com.ai.ui.shared.modelLabel(pn, agent.model, separator = " / ")
                        } else r.agentName.removePrefix("Translate:").trim().ifBlank { r.agentName }
                    }
                    "META" -> {
                        val src = r.translateSourceTargetId?.let { metaSourcesById[it] }
                        if (src != null) {
                            val pn = AppService.findById(src.providerId)?.displayName ?: src.providerId
                            com.ai.ui.shared.modelLabel(pn, src.model, separator = " / ")
                        } else r.agentName.removePrefix("Translate:").trim().ifBlank { r.agentName }
                    }
                    "PROMPT" -> "Report prompt"
                    else -> r.agentName.removePrefix("Translate:").trim().ifBlank { r.agentName }
                }
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
                    // What kind of source row this translation came from
                    // — "report" for an agent response, "cross-out" /
                    // "cross-in" for cross drill-in rows, the meta
                    // prompt name for chat-type META rows, "prompt" for
                    // the report prompt itself.
                    Text(
                        typeFor(r), fontSize = 11.sp, color = AppColors.TextSecondary,
                        modifier = Modifier.width(70.dp).padding(end = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
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

        // Actions — collapsed by default, mirrors the Cross L1
        // pattern. Restart / Start missing are at the top because
        // those are the recovery affordances; Model / Delete sit
        // below.
        Spacer(modifier = Modifier.height(8.dp))
        val erroredCount = results.count { it.errorMessage != null }
        CollapsibleCard("Actions") {
            Button(
                onClick = { onRestartFailed(reportId, runId) },
                enabled = erroredCount > 0,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
            ) { Text("Restart failed translations", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { onStartMissing(reportId, runId) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Start missing translations", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { providerService?.let { onNavigateToModelInfo(it, modelName) } },
                enabled = providerService != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) { Text("Model info", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
            ) { Text("Delete this translation run", fontSize = 13.sp, maxLines = 1, softWrap = false) }
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
