package com.ai.ui.report.manage

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.TransRankCellState
import com.ai.data.TransRankCellStatus
import com.ai.data.TransRankRunState
import com.ai.data.aggregateTranslatorRanks
import com.ai.data.barTitle
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import com.ai.viewmodel.TranslatorRankEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Overlay-mount helper called by ReportsScreenNav when the open-state var
 *  (the run key "$reportId|$sourceTranslationRunId") is non-null. */
@Composable
fun TranslatorRankOverlay(runKey: String, engine: TranslatorRankEngine, onClose: () -> Unit) {
    CompositionLocalProvider(com.ai.ui.shared.LocalNavigateToCurrentReport provides onClose) {
        TranslatorRankScreen(engine, runKey, onClose)
    }
}

@Composable
fun TranslatorRankScreen(engine: TranslatorRankEngine, runKey: String, onBack: () -> Unit) {
    val context = LocalContextSafe()
    val scope = rememberCoroutineScope()
    val reportId = runKey.substringBefore("|")
    val runs by engine.runs.collectAsState()
    val run = runs[runKey]

    LaunchedEffect(reportId) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
    }
    val report by produceState<Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
    }
    val localReportTitle = com.ai.ui.shared.LocalReportTitle.current
    val reportTitle = report?.barTitle?.takeIf { it.isNotBlank() }
        ?: localReportTitle?.takeIf { it.isNotBlank() } ?: "Report"
    val reportIcon = report?.icon?.takeIf { it.isNotBlank() }
        ?: com.ai.ui.shared.LocalMetadataIcons.current.reportIcon

    var openTranslatorKey by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler { if (openTranslatorKey != null) openTranslatorKey = null else onBack() }

    if (run == null) {
        Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(16.dp)) {
            TitleBar(helpTopic = "translator_rank", title = "Rank the translators",
                subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack)
            Spacer(Modifier.height(20.dp))
            Text("One moment, collecting information…", color = AppColors.TextSecondary, fontSize = 14.sp)
        }
        return
    }

    val openKey = openTranslatorKey
    if (openKey != null) {
        TranslatorRankL2(run, openKey, reportTitle, reportIcon) { openTranslatorKey = null }
        return
    }

    TranslatorRankL1(
        run = run, reportTitle = reportTitle, reportIcon = reportIcon,
        openTranslator = { openTranslatorKey = it },
        onRestartFailed = { scope.launch { engine.restartFailedCells(context, runKey) } },
        onDeleteRun = { scope.launch { engine.deleteRun(context, runKey) }; onBack() },
        onBack = onBack
    )
}

@Composable
private fun LocalContextSafe() = androidx.compose.ui.platform.LocalContext.current

// ---------- L1: leaderboard ----------

@Composable
private fun TranslatorRankL1(
    run: TransRankRunState,
    reportTitle: String,
    reportIcon: String,
    openTranslator: (String) -> Unit,
    onRestartFailed: () -> Unit,
    onDeleteRun: () -> Unit,
    onBack: () -> Unit
) {
    val cells = run.cells.values
    val total = cells.size
    val done = cells.count { it.status == TransRankCellStatus.DONE }
    val error = cells.count { it.status == TransRankCellStatus.ERROR }
    val running = cells.count { it.status == TransRankCellStatus.RUNNING }
    val queued = cells.count { it.status == TransRankCellStatus.PENDING }
    val allTerminal = total > 0 && (done + error) == total
    val ranking = aggregateTranslatorRanks(cells)

    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "translator_rank", title = "Rank the translators",
            subject = reportTitle, reportIcon = reportIcon,
            onBackClick = onBack,
            onReload = if (error > 0) onRestartFailed else null,
            onDelete = onDeleteRun
        )
        Text(
            run.targetLanguageNative.takeIf { it.isNotBlank() } ?: run.targetLanguageName,
            color = AppColors.SuccessAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            BatchStatsRow(buildList {
                add(Triple("Total", total.toString(), AppColors.InfoAccent))
                add(Triple("Done", done.toString(), AppColors.SuccessAccent))
                add(Triple("Error", error.toString(), AppColors.DangerAccent))
                add(Triple("Run", running.toString(), AppColors.WarningAccent))
                add(Triple("Queue", queued.toString(), AppColors.QueueAccent))
                add(Triple("Costs", "${formatCents(run.totalCost, 2)} ¢", AppColors.InfoAccent))
            })
            if (!allTerminal && total > 0 && !run.cancelled) {
                LinearProgressIndicator(
                    progress = { (done + error).toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.WarningAccent, trackColor = AppColors.DividerDark
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Each translated answer is scored 0–100 by the other models; translators are ranked by their average. Models are judged on the items each one happened to translate.",
                color = AppColors.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp)
            )

            if (ranking.isEmpty()) {
                Text(
                    if (allTerminal) "No scores yet — the judges produced no usable scores."
                    else "Scoring…",
                    color = AppColors.TextSecondary, fontSize = 13.sp
                )
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                        Text("Translator", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("Items", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(46.dp))
                        Text("Score", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
                    }
                    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.35f), thickness = 0.5.dp)
                    ranking.forEachIndexed { i, r ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { openTranslator(r.translatorKey) }
                                .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${i + 1}", color = AppColors.TextTertiary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(22.dp))
                            Text(shortModelName(r.model), color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 4.dp))
                            Text("${r.itemCount}", color = AppColors.TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.width(46.dp))
                            Text(fmtScore(r.avgScore), color = scoreColor(r.avgScore), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
                        }
                        if (i < ranking.lastIndex) HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.2f), thickness = 0.5.dp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- L2: one translator's items, each judge's score + reason ----------

@Composable
private fun TranslatorRankL2(
    run: TransRankRunState,
    translatorKey: String,
    reportTitle: String,
    reportIcon: String,
    onBack: () -> Unit
) {
    val cells = run.cells.values.filter { it.translatorKey == translatorKey }
    val byItem = cells.groupBy { it.translationRowId }.toList()
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "translator_rank", title = "Translator", subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack)
        Text(
            shortModelName(translatorKey.substringAfterLast('/')),
            color = AppColors.SuccessAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            byItem.forEachIndexed { idx, (_, itemCells) ->
                item(key = "h$idx") {
                    val scored = itemCells.mapNotNull { it.score }
                    val avg = if (scored.isNotEmpty()) scored.average() else null
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Item ${idx + 1}", color = AppColors.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(avg?.let { fmtScore(it) } ?: "—", color = AppColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.35f), thickness = 0.5.dp)
                }
                items(itemCells.size) { i ->
                    val c = itemCells[i]
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(shortModelName(c.judgeModel), color = AppColors.TextPrimary, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            when (c.status) {
                                TransRankCellStatus.RUNNING -> Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) { AnimatedHourglass(fontSize = 13.sp) }
                                TransRankCellStatus.ERROR -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 13.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                                else -> Text(c.score?.toString() ?: "·", color = scoreColor((c.score ?: 0).toDouble()),
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
                            }
                        }
                        (c.reason ?: c.errorMessage)?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = if (c.errorMessage != null) AppColors.DangerAccent else AppColors.TextSecondary,
                                fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.18f), thickness = 0.5.dp)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun fmtScore(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

private fun scoreColor(v: Double) = when {
    v >= 80 -> AppColors.SuccessAccent
    v >= 50 -> AppColors.CautionAccent
    else -> AppColors.WarningAccent
}
