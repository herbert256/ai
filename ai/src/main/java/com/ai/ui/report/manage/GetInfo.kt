package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.model.Settings
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import com.ai.ui.shared.shortModelName2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Status of one metadata-generation job on the "Report - Get info" screen. */
enum class InfoJobState { CLOCK, RUNNING, FAILED, EMPTY, DONE }

/** One row on the Info screen: a metadata job for the report (report-level
 *  icon/language/title) or per-model (model-icon/model-title). */
data class InfoJob(
    val type: String,
    val label: String,
    val state: InfoJobState,
    val cost: Double,
    val agentId: String? = null,
    /** Emoji to show in the status cell when [state] is DONE (the generated
     *  icon for this job) — falls back to ✅ when null/blank. */
    val doneIcon: String? = null,
    /** True when this job is genuinely still in progress / will yet proceed
     *  (report-level RUNNING, or a per-model job whose model is still
     *  pending/running). A per-model CLOCK left by an **ERRORed** model is
     *  terminal — it can never run — so it is *not* pending and must not keep
     *  the Manage info-row hourglass spinning. */
    val pending: Boolean = false
)

/** True once this agent's per-model **icon** call has concluded — a tier
 *  succeeded, errored, or simply returned (cost / tokens / a winning-tier /
 *  prompt-name recorded). A concluded call that produced no [icon] and no
 *  [iconErrorMessage] is terminal: the 3-tier chain finished without yielding
 *  a glyph and will never self-complete, so its info job must NOT stay on the
 *  animated hourglass forever (the per-model analogue of the report-level
 *  `iconNeverRan` guard). */
private fun com.ai.data.ReportAgent.modelIconAttempted(): Boolean =
    iconInputTokens > 0 || iconOutputTokens > 0 || iconInputCost != 0.0 ||
        iconOutputCost != 0.0 || iconWinningTier != null || !iconPromptUsed.isNullOrBlank()

/** True once this agent's per-model **title** call has concluded (cost /
 *  tokens / duration / prompt-name recorded). A concluded call with no
 *  [modelTitle] and no [modelTitleErrorMessage] is terminal — same reasoning
 *  as [modelIconAttempted]. */
private fun com.ai.data.ReportAgent.modelTitleAttempted(): Boolean =
    modelTitleInputTokens > 0 || modelTitleOutputTokens > 0 || modelTitleInputCost != 0.0 ||
        modelTitleOutputCost != 0.0 || modelTitleDurationMs != null || !modelTitlePromptUsed.isNullOrBlank()

/**
 * Single source of truth for the "Report - Get info" rows — used both by the
 * Info screen (per-row) and by the Manage-report **info** row (aggregate +
 * total), so the two never disagree. Pure function of the report + the
 * relevant settings/gates.
 *
 * Only **enabled** jobs are emitted (report icon needs [iconGenEnabled],
 * language needs [reportLanguageOn], both need a resolvable worker;
 * title needs [titleModeAi]; per-model
 * icon/title need [perModelIcon]/[perModelTitle]). Per-model jobs sit at
 * [InfoJobState.CLOCK] until that model's own response reaches SUCCESS (a
 * failed/pending model leaves them on the clock).
 */
fun buildInfoJobs(
    report: Report,
    settings: Settings,
    iconGenEnabled: Boolean,
    reportLanguageOn: Boolean,
    titleModeAi: Boolean,
    perModelIcon: Boolean,
    perModelTitle: Boolean,
    icons: com.ai.data.MetadataIcons = com.ai.data.MetadataIcons(),
    // Keys ("<reportId>|icon" / "|language" / "|language-icon" / "|title") of report-level jobs
    // whose call is ACTIVELY in flight. A report-level job with no result and
    // no error shows the clock (queued) unless its key is here — then the
    // animated hourglass (really running). Empty = the aggregate caller
    // (Manage info row) doesn't care about the clock/hourglass split.
    running: Set<String> = emptySet()
): List<InfoJob> {
    val jobs = mutableListOf<InfoJob>()

    // A report-level CLOCK only keeps the aggregate hourglass spinning while
    // the report can still proceed (not yet completed). Once completedAt is
    // set, an unstarted job is terminal — show ⏰ but don't spin forever.
    fun reportPending(state: InfoJobState): Boolean =
        state == InfoJobState.RUNNING ||
            (state == InfoJobState.CLOCK && report.completedAt == null)

    // ----- Report-level rows: one per underlying prompt, in run order -----
    // language → language-icon → report-short → report-long → report-icon.

    // Same "never ran" terminal guard for the whole language flow: a finished
    // report with no detected language, no error, and no recorded language
    // attempt (cost / duration / prompt all empty) never ran it — e.g. legacy
    // reports, or a copy / fan-out-derived report whose source had no
    // language. Without this the rows spin "Queued…" forever on those.
    val languageNeverRan = report.completedAt != null &&
        report.languageName.isNullOrBlank() &&
        report.languageIconErrorMessage == null &&
        report.languageIconPromptUsed == null &&
        report.languageDurationMs == null && report.languageIconDurationMs == null &&
        report.languageInputCost == 0.0 && report.languageOutputCost == 0.0 &&
        report.languageIconInputCost == 0.0 && report.languageIconOutputCost == 0.0

    // Language detection has its own gate (split from the icon gate) so report
    // icon and report language can be toggled independently.
    if (reportLanguageOn && !languageNeverRan) {
        // 1) language — detect the report's language.
        val langState = when {
            !report.languageName.isNullOrBlank() -> InfoJobState.DONE
            "${report.id}|language" in running -> InfoJobState.RUNNING
            else -> InfoJobState.CLOCK
        }
        val langLabel = report.languageName?.takeIf { it.isNotBlank() }
            ?: if (langState == InfoJobState.RUNNING) "Detecting…" else "Queued…"
        jobs += InfoJob(
            "language", langLabel, langState,
            report.languageInputCost + report.languageOutputCost,
            doneIcon = null, pending = reportPending(langState)
        )

        // 2) language-icon — pick an emoji for the detected language. Waits on
        // detection (CLOCK until a language name exists).
        val langIconState = when {
            !report.languageIconErrorMessage.isNullOrBlank() -> InfoJobState.FAILED
            !report.languageIcon.isNullOrBlank() -> InfoJobState.DONE
            report.languageName.isNullOrBlank() -> InfoJobState.CLOCK
            "${report.id}|language-icon" in running -> InfoJobState.RUNNING
            else -> InfoJobState.CLOCK
        }
        val langIconLabel = report.languageIconErrorMessage?.takeIf { it.isNotBlank() }
            ?: report.languageName?.takeIf { it.isNotBlank() }
            ?: if (langIconState == InfoJobState.RUNNING) "Generating…" else "Queued…"
        jobs += InfoJob(
            "language-icon", langIconLabel, langIconState,
            report.languageIconInputCost + report.languageIconOutputCost,
            doneIcon = report.languageIcon ?: icons.languageIcon,
            pending = reportPending(langIconState)
        )
    }

    val titlePrompts = settings.internalPrompts.filter {
        it.category == "workers" &&
            (it.name == "report-title-short" || it.name == "report-title-long")
    }
    val titleConfigured = titlePrompts.any { prompt ->
        prompt.workers.any { settings.resolveWorker(it) != null }
    }
    // "Never ran" terminal guard, mirroring the language rows: a finished
    // report that recorded no AI-title attempt (no promptUsed, no error, no
    // cost / duration) never ran the report-title job — e.g. legacy reports
    // or a copy whose title was inherited rather than generated. Skip the
    // rows instead of spinning "Queued…" forever.
    val titleNeverRan = report.completedAt != null &&
        report.titlePromptUsed.isNullOrBlank() && report.titleErrorMessage == null &&
        report.titleDurationMs == null && report.titleLongDurationMs == null &&
        report.titleInputCost == 0.0 && report.titleOutputCost == 0.0 &&
        report.titleLongInputCost == 0.0 && report.titleLongOutputCost == 0.0
    if (titleModeAi && titleConfigured && !titleNeverRan) {
        // 3) report-short — the short report title.
        val shortState = when {
            !report.titleErrorMessage.isNullOrBlank() -> InfoJobState.FAILED
            !report.titlePromptUsed.isNullOrBlank() -> InfoJobState.DONE
            "${report.id}|title" in running -> InfoJobState.RUNNING
            else -> InfoJobState.CLOCK
        }
        val shortLabel = report.titleErrorMessage?.takeIf { it.isNotBlank() }
            ?: report.title.takeIf { !report.titlePromptUsed.isNullOrBlank() }
            ?: if (shortState == InfoJobState.RUNNING) "Generating…" else "Queued…"
        jobs += InfoJob("report-short", shortLabel, shortState,
            report.titleInputCost + report.titleOutputCost,
            doneIcon = com.ai.data.MetadataIconsHolder.current.label,
            pending = reportPending(shortState))

        // 4) report-long — the long report title. A finished report that has a
        // short title but no long one (and isn't actively generating) never ran
        // the long-title call — e.g. legacy / imported reports from before the
        // short+long split — so it's terminal, not a perpetual "Queued…".
        val longState = when {
            !report.titleLong.isNullOrBlank() -> InfoJobState.DONE
            "${report.id}|title" in running -> InfoJobState.RUNNING
            report.completedAt != null -> InfoJobState.EMPTY
            else -> InfoJobState.CLOCK
        }
        val longLabel = report.titleLong?.takeIf { it.isNotBlank() }
            ?: when (longState) {
                InfoJobState.RUNNING -> "Generating…"
                InfoJobState.EMPTY -> "Not generated"
                else -> "Queued…"
            }
        jobs += InfoJob("report-long", longLabel, longState,
            report.titleLongInputCost + report.titleLongOutputCost,
            doneIcon = com.ai.data.MetadataIconsHolder.current.label,
            pending = reportPending(longState))
    }

    // Report icons are produced by the worker engine (report/icon).
    // The row is eligible when that prompt has at least one resolvable worker.
    val iconPrompt = settings.internalPrompts.firstOrNull { it.category == "workers" && it.name == "report-icon" }
    val iconRowOn = iconGenEnabled && iconPrompt != null &&
        iconPrompt.workers.any { settings.resolveWorker(it) != null }

    // A finished report (completedAt set) with no icon, no error, and no
    // recorded icon attempt (cost/duration/promptUsed all empty) is one where
    // icon-gen never ran — e.g. legacy reports, or an unresolvable icon agent
    // at run time. Treat that as terminal "not run" rather than leaving the
    // row spinning "Generating…" forever.
    val iconNeverRan = report.completedAt != null && report.icon == null &&
        report.iconErrorMessage == null && report.iconPromptUsed == null &&
        report.iconDurationMs == null && report.iconInputCost == 0.0 &&
        report.iconOutputCost == 0.0

    if (iconRowOn && !iconNeverRan) {
        // 5) report-icon — the report icon, derived from the long title.
        val state = when {
            !report.iconErrorMessage.isNullOrBlank() -> InfoJobState.FAILED
            !report.icon.isNullOrBlank() -> InfoJobState.DONE
            "${report.id}|icon" in running -> InfoJobState.RUNNING
            else -> InfoJobState.CLOCK
        }
        val label = report.iconErrorMessage?.takeIf { it.isNotBlank() }
            ?: report.icon?.takeIf { it.isNotBlank() }
            ?: if (state == InfoJobState.RUNNING) "Generating…" else "Queued…"
        jobs += InfoJob("report-icon", label, state, report.iconInputCost + report.iconOutputCost,
            doneIcon = report.icon, pending = reportPending(state))
    }

    // Per-agent title state — agent must succeed first; when both jobs are
    // on the icon is derived from the title, so the icon waits for it.
    fun titleStateFor(a: com.ai.data.ReportAgent): InfoJobState =
        if (a.reportStatus != ReportStatus.SUCCESS) InfoJobState.CLOCK else when {
            !a.modelTitleErrorMessage.isNullOrBlank() -> InfoJobState.FAILED
            !a.modelTitle.isNullOrBlank() -> InfoJobState.DONE
            // Call concluded but yielded no title and no error → terminal,
            // but not a success.
            a.modelTitleAttempted() -> InfoJobState.EMPTY
            // Finished report whose per-model title NEVER ran (no result, no
            // error, no attempt markers) won't auto-run — treat it as terminal
            // rather than a phantom RUNNING that spins the hourglass forever.
            // (Imported / copied reports, or per-model-title toggled on after
            // generation.) During live generation completedAt is null, so a
            // freshly-succeeded model still shows RUNNING while its title runs.
            report.completedAt != null -> InfoJobState.EMPTY
            else -> InfoJobState.RUNNING
        }

    // All model-title rows first, then all model-icon rows (after the
    // three report-level rows above).
    // A per-model job is "pending" (keeps the aggregate spinning) only while
    // it can still proceed: RUNNING, or CLOCK with the model still
    // pending/running. A CLOCK left by a STOPPED or ERRORed model is terminal.
    fun perModelPending(a: com.ai.data.ReportAgent, state: InfoJobState): Boolean =
        state == InfoJobState.RUNNING ||
            (state == InfoJobState.CLOCK &&
                (a.reportStatus == ReportStatus.PENDING || a.reportStatus == ReportStatus.RUNNING))

    if (perModelTitle) {
        report.agents.forEach { a ->
            val modelName = "${a.provider} · ${shortModelName2(a.model)}"
            val titleState = titleStateFor(a)
            val label = if (titleState == InfoJobState.EMPTY) "$modelName · no title" else modelName
            jobs += InfoJob(
                "model-title", label, titleState,
                a.modelTitleInputCost + a.modelTitleOutputCost, a.agentId,
                // Show the model's found icon when there is one, else 🏷️.
                doneIcon = a.icon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.label,
                pending = perModelPending(a, titleState)
            )
        }
    }
    if (perModelIcon) {
        report.agents.forEach { a ->
            val modelName = "${a.provider} · ${shortModelName2(a.model)}"
            val foundTitle = a.modelTitle?.takeIf { it.isNotBlank() }
            val titleState = if (perModelTitle) titleStateFor(a) else null
            val iconState = when {
                a.reportStatus != ReportStatus.SUCCESS -> InfoJobState.CLOCK
                titleState == InfoJobState.CLOCK || titleState == InfoJobState.RUNNING -> InfoJobState.CLOCK
                !a.iconErrorMessage.isNullOrBlank() -> InfoJobState.FAILED
                !a.icon.isNullOrBlank() -> InfoJobState.DONE
                // Icon chain concluded but produced no glyph and no error
                // (e.g. an empty/unparseable model reply) → terminal, so the
                // Manage info row doesn't spin forever.
                a.modelIconAttempted() -> InfoJobState.DONE
                // Finished report whose per-model icon never ran → terminal,
                // not a phantom RUNNING (same guard as titleStateFor).
                report.completedAt != null -> InfoJobState.DONE
                else -> InfoJobState.RUNNING
            }
            // Label shows the found title (the icon is derived from it);
            // falls back to the model name when there's no title.
            jobs += InfoJob("model-icon", foundTitle ?: modelName, iconState,
                a.iconInputCost + a.iconOutputCost, a.agentId, doneIcon = a.icon,
                pending = perModelPending(a, iconState))
        }
    }
    return jobs
}

/** Aggregate state for the Manage **info** row: ❌ if any job failed, else
 *  ⏳ while any job is still genuinely in progress ([InfoJob.pending]), else
 *  ✅. A CLOCK left by an ERRORed model is *not* pending, so a finished report
 *  with a failed model settles to ✅ rather than spinning forever. */
fun aggregateInfoState(jobs: List<InfoJob>): InfoJobState = when {
    jobs.any { it.state == InfoJobState.FAILED } -> InfoJobState.FAILED
    jobs.any { it.pending } -> InfoJobState.RUNNING
    else -> InfoJobState.DONE
}

/** The leftmost 24 dp status cell shared by the Info screen rows and the
 *  Manage info row. */
@Composable
internal fun InfoStatusCell(state: InfoJobState, doneIcon: String? = null) {
    when (state) {
        InfoJobState.CLOCK -> Text(com.ai.data.MetadataIconsHolder.current.statusAlarm, fontSize = 16.sp, modifier = Modifier.width(24.dp))
        InfoJobState.RUNNING -> Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            AnimatedHourglass(fontSize = 16.sp)
        }
        InfoJobState.FAILED -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
        InfoJobState.EMPTY -> Text("⊘", fontSize = 16.sp, color = AppColors.TextDisabled, modifier = Modifier.width(24.dp))
        // Show the generated icon for this job once done; ✅ when none.
        InfoJobState.DONE -> Text(doneIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.statusDone, fontSize = 16.sp, modifier = Modifier.width(24.dp))
    }
}

/**
 * "Report - Get info" — full-screen list of the report's metadata-generation
 * jobs, each with a clock/hourglass/cross/ok status and its own cost. The
 * report's api-calls / duration / cost statistics line sits under the title
 * bar, and the list leads with the "report" / "second" cross-link rows to the
 * other two report screens. Rows are clickable to their existing detail
 * screens, layered over this overlay.
 */
@Composable
fun ReportGetInfoScreen(
    reportId: String,
    settings: Settings,
    iconRefreshTick: Int,
    iconGenEnabled: Boolean,
    reportLanguageOn: Boolean,
    titleModeAi: Boolean,
    perModelIcon: Boolean,
    perModelTitle: Boolean,
    runningInfoJobs: Set<String>,
    /** The report's long title — the orange subtitle line (same as on the
     *  other two report screens) and the "report" row text. */
    reportTitle: String,
    /** The hub's live running total for the stats line (see [ReportStatsLine]). */
    costDollars: Double,
    /** Stats-line tap → the report's costs screen. */
    onViewCosts: (() -> Unit)? = null,
    /** Combined main-response cost of every model — the "report" row's cost. */
    reportCost: Double = 0.0,
    /** Aggregate state + total for the "second" cross-link row. */
    secondState: InfoJobState = InfoJobState.DONE,
    secondTotal: Double = 0.0,
    /** "report" row tap → the Manage hub. */
    onGoManage: () -> Unit = {},
    /** "second" row tap → the second-results screen. */
    onGoSecond: () -> Unit = {},
    onBack: () -> Unit,
    /** Title tap → next of the three report screens. */
    onCycleNext: () -> Unit = {},
    /** Report-icon tap → the View hub. */
    onOpenViewHub: () -> Unit = {},
    onOpenIconDetail: () -> Unit,
    onOpenLanguageDetect: () -> Unit,
    onOpenLanguageDetail: () -> Unit,
    onEditTitle: () -> Unit,
    onEditLongTitle: () -> Unit,
    onOpenAgentIconDetail: (String) -> Unit,
    onEditModelTitle: (String) -> Unit,
    onRestartErrors: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val metadataIcons = com.ai.ui.shared.LocalMetadataIcons.current
    val jobs by produceState(
        initialValue = emptyList<InfoJob>(),
        reportId,
        iconRefreshTick,
        metadataIcons,
        runningInfoJobs,
        settings.internalPrompts,
        iconGenEnabled,
        reportLanguageOn,
        titleModeAi,
        perModelIcon,
        perModelTitle
    ) {
        value = withContext(Dispatchers.IO) {
            val r = ReportStorage.getReport(context, reportId) ?: return@withContext emptyList()
            buildInfoJobs(r, settings, iconGenEnabled, reportLanguageOn, titleModeAi, perModelIcon, perModelTitle, metadataIcons, runningInfoJobs)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // publishBottomBar=false: this screen is drawn as a layer on top
        // of the Manage hub, which keeps publishing its own bottom bar
        // (a per-layer icon set). We render only the top chrome here.
        TitleBar(
            helpTopic = "report_get_info", title = "Report - titles/icons/...", subject = reportTitle, onBackClick = onBack,
            onTitleClick = onCycleNext,
            forceTitleClick = true,
            onReportIconClick = onOpenViewHub,
            publishBottomBar = false
        )
        // Statistics line — api calls / total API time / running cost —
        // same numbers as the hub's own line; tap → costs screen.
        ReportStatsLine(
            reportId = reportId,
            costDollars = costDollars,
            refreshKey = "$iconRefreshTick|${runningInfoJobs.size}|$costDollars",
            onClick = onViewCosts
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            // Top-of-list divider — same 1 dp cap the Manage list paints.
            item(key = "top-divider") {
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
            // Cross-link rows to the other two report screens: the report
            // itself (Manage hub) and the second-results aggregate.
            item(key = "row-reports") {
                ReportsSummaryRow(reportTitle, cost = reportCost, onClick = onGoManage)
            }
            item(key = "row-second") {
                SecondSummaryRow(secondState, cost = secondTotal, onClick = onGoSecond)
            }
            items(jobs, key = { "${it.type}-${it.agentId ?: it.label}" }) { job ->
                val click: (() -> Unit)? = when (job.type) {
                    "report-icon" -> onOpenIconDetail
                    "language" -> onOpenLanguageDetect
                    "language-icon" -> onOpenLanguageDetail
                    "report-short" -> onEditTitle
                    "report-long" -> onEditLongTitle
                    "model-icon" -> job.agentId?.let { id -> { onOpenAgentIconDetail(id) } }
                    "model-title" -> job.agentId?.let { id -> { onEditModelTitle(id) } }
                    else -> null
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .then(if (click != null) Modifier.clickable { click() } else Modifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InfoStatusCell(job.state, job.doneIcon)
                    // Wider than the shared 80dp RowTypeCell so "model-icon"
                    // / "model-title" aren't truncated.
                    Text(
                        job.type, fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                        modifier = Modifier.width(96.dp).padding(start = 8.dp, end = 6.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        val color = when (job.state) {
                            InfoJobState.FAILED -> AppColors.DangerAccent
                            InfoJobState.EMPTY -> AppColors.TextDisabled
                            else -> AppColors.TextPrimary
                        }
                        Text(job.label, fontSize = 13.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Show the cost on any completed job — a 0 means the job
                    // was a cache hit (no LLM call billed), e.g. a language
                    // icon served from the 7-day meta cache. Pending / running
                    // / failed rows stay blank (no cost incurred yet).
                    if (job.cost > 0.0 || job.state == InfoJobState.DONE) {
                        Text(formatCents(job.cost), fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
        // Restart errors — shown only when at least one job failed (red ❌).
        // Clears the errored jobs' error state and re-fires just those.
        if (jobs.any { it.state == InfoJobState.FAILED }) {
            OutlinedButton(
                onClick = onRestartErrors,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Restart errors", maxLines = 1, softWrap = false) }
        }
    }
}
