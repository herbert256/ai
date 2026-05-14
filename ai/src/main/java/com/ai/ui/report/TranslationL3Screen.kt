package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.modelInfoClickable
import com.ai.viewmodel.ReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What gets fed to the model — the source content, the model that
 *  produced it, and (for META rows) the user-given Meta prompt name. */
private data class TranslationSourceInfo(
    val content: String?,
    val model: String?,
    val providerId: String?,
    val metaName: String?
)

/**
 * L3 of the translation run drill-in: a single translation. Source
 * text on top (capped at half-screen), translated text underneath,
 * each independently scrollable. Prev/Next steps through the same
 * model's items. Folds in the old TranslationCallDetailScreen +
 * placeholder-row chrome.
 */
@Composable
internal fun TranslationL3Screen(
    run: ReportViewModel.TranslationRunState,
    reportId: String,
    runId: String,
    modelKey: String,
    itemId: String,
    actions: TranslationActions,
    onStepItem: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    val item = run.items.firstOrNull { it.id == itemId }
    if (item == null) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TitleBar(helpTopic = "translation_run_l3", title = "Translation call", onBackClick = onBack)
            Text("This item no longer exists.", color = AppColors.TextTertiary, fontSize = 14.sp)
        }
        return
    }

    // Same model's items, ordered as L2 — drives Prev / Next.
    val siblings = remember(run.items, modelKey) {
        run.items.filter { translationModelKey(it) == modelKey }
            .sortedWith(
                compareBy(
                    { sib ->
                        when (sib.status) {
                            ReportViewModel.TranslationStatus.RUNNING,
                            ReportViewModel.TranslationStatus.PENDING -> 0
                            ReportViewModel.TranslationStatus.ERROR -> 1
                            ReportViewModel.TranslationStatus.DONE -> 2
                        }
                    },
                    { it.label.lowercase() }
                )
            )
    }
    val curIdx = siblings.indexOfFirst { it.id == itemId }
    val prev = siblings.getOrNull(curIdx - 1)
    val next = siblings.getOrNull(curIdx + 1)

    var confirmDelete by remember { mutableStateOf(false) }

    val titleLang = run.targetLanguageName.takeIf { it.isNotBlank() }
        ?.let { "Translate · $it" } ?: "Translate"

    // Resolve the original (source) content from the report. Live
    // items also carry it on item.sourceText — fall back to that so
    // an in-flight run needs no disk read.
    val empty = TranslationSourceInfo(null, null, null, null)
    val source by produceState(initialValue = empty, reportId, item.id, item.kind, item.target) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, reportId)
            when (item.kind) {
                ReportViewModel.TranslationKind.PROMPT ->
                    TranslationSourceInfo(report?.prompt, null, null, null)
                ReportViewModel.TranslationKind.AGENT_RESPONSE -> {
                    val agent = report?.agents?.firstOrNull {
                        it.agentId == item.target && it.reportStatus == ReportStatus.SUCCESS
                    }
                    TranslationSourceInfo(agent?.responseBody, agent?.model, agent?.provider, null)
                }
                ReportViewModel.TranslationKind.META -> {
                    val sec = item.target?.let { SecondaryResultStorage.get(context, reportId, it) }
                    TranslationSourceInfo(sec?.content, sec?.model, sec?.providerId, sec?.metaPromptName)
                }
            }
        }
    }
    val sourceContent = source.content?.takeIf { it.isNotBlank() } ?: item.sourceText.takeIf { it.isNotBlank() }
    val sourceLabel = source.model?.takeIf { it.isNotBlank() }
        ?: when (item.kind) {
            ReportViewModel.TranslationKind.PROMPT -> "Prompt"
            ReportViewModel.TranslationKind.META -> source.metaName?.takeIf { it.isNotBlank() } ?: "Meta"
            ReportViewModel.TranslationKind.AGENT_RESPONSE -> "Source"
        }
    val sourceProviderService = source.providerId?.let { AppService.findById(it) }

    val translationLabel = item.model?.takeIf { it.isNotBlank() } ?: "Translation"
    val translationProviderService = item.providerId?.let { AppService.findById(it) }

    // Trace for this translation call — most recent Translation-tagged
    // trace on this report for the item's model.
    val traceFilename by produceState<String?>(initialValue = null, reportId, item.id, item.model) {
        value = withContext(Dispatchers.IO) {
            val m = item.model ?: return@withContext null
            ApiTracer.getTraceFiles()
                .filter { it.reportId == reportId && it.model == m && it.category == "Translation" }
                .maxByOrNull { it.timestamp }?.filename
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val halfMax = maxHeight / 2
        Column(modifier = Modifier.fillMaxSize()) {
            val traceEnabled = ApiTracer.isTracingEnabled && traceFilename != null
            TitleBar(
                helpTopic = "translation_run_l3",
                title = "Translation call",
                reportIcon = com.ai.ui.shared.LocalReportIcon.current,
                subject = titleLang,
                onBackClick = onBack,
                onTrace = if (traceEnabled) { { actions.onNavigateToTraceFile(traceFilename!!) } } else null,
                onInfo = if (translationProviderService != null && !item.model.isNullOrBlank()) {
                    { actions.onNavigateToModelInfo(translationProviderService, item.model!!) }
                } else null,
                onCopy = item.translatedText?.takeIf { it.isNotBlank() }?.let { body ->
                    { com.ai.ui.shared.copyToClipboard(context, body, "translation") }
                },
                onShare = item.translatedText?.takeIf { it.isNotBlank() }?.let { body ->
                    { com.ai.ui.shared.shareText(context, body, "Translation $titleLang") }
                },
                onDelete = { confirmDelete = true }
            )
            if (com.ai.ui.shared.LocalSubjectToTitleBarMode.current == com.ai.viewmodel.SubjectToTitleBarMode.HARDCODED) {
                Text(
                    text = titleLang,
                    fontSize = 18.sp, color = AppColors.Green,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                )
            }
            if (item.costDollars > 0.0) {
                Text(
                    "Cost: ${formatCents(item.costDollars)} ¢",
                    fontSize = 12.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Original pane — wraps to content, capped at half-screen.
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = halfMax)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    sourceLabel,
                    fontSize = 14.sp, color = AppColors.Blue, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth()
                        .modelInfoClickable(sourceProviderService, source.model.orEmpty())
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (sourceContent.isNullOrBlank()) {
                        Text("(source content not found)", color = AppColors.TextTertiary, fontSize = 13.sp)
                    } else {
                        ContentWithThinkSections(analysis = sourceContent)
                    }
                }
            }

            HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)

            // Translation pane — fills the rest. Content depends on the
            // item's status.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    translationLabel,
                    fontSize = 14.sp, color = AppColors.Green, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth()
                        .modelInfoClickable(translationProviderService, item.model.orEmpty())
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (item.status) {
                        ReportViewModel.TranslationStatus.ERROR -> {
                            Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                            Text(
                                item.errorMessage ?: "Unknown error",
                                fontSize = 13.sp, color = AppColors.TextSecondary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        ReportViewModel.TranslationStatus.RUNNING ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AnimatedHourglass(fontSize = 16.sp)
                                Text("  Running…", color = AppColors.Orange, fontSize = 13.sp)
                            }
                        ReportViewModel.TranslationStatus.PENDING ->
                            Text("🕓 Queued", color = AppColors.TextTertiary, fontSize = 13.sp)
                        ReportViewModel.TranslationStatus.DONE -> {
                            val tx = item.translatedText
                            if (tx.isNullOrBlank()) {
                                Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
                            } else {
                                ContentWithThinkSections(analysis = tx)
                            }
                        }
                    }
                }
            }

            // Prev / Next — step through this model's items.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { prev?.let { onStepItem(it.id) } },
                    enabled = prev != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("← Prev", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(
                    onClick = { next?.let { onStepItem(it.id) } },
                    enabled = next != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Next →", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
    }

    if (confirmDelete) {
        val isPersisted = item.persistedRowId != null
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (isPersisted) "Delete this translation?" else "Remove this translation?") },
            text = {
                Text(
                    if (isPersisted) "Drops this single translation row from the report. Can't be undone."
                    else "Drops this single call from the run. The translated row won't be saved. Other calls in the run continue."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val rowId = item.persistedRowId
                    if (rowId != null) actions.onDeleteSecondaryRow(reportId, rowId)
                    else actions.onCancelItem(runId, item.id)
                    onBack()
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}
