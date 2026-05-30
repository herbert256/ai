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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Status of one metadata-generation job on the "Report - Get info" screen. */
enum class InfoJobState { CLOCK, RUNNING, FAILED, DONE }

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
 * Only **enabled** jobs are emitted (report icon/language need [iconGenEnabled]
 * + the prompt's agent resolvable; title needs [titleModeAi]; per-model
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
    // Keys ("<reportId>|icon" / "|language" / "|title") of report-level jobs
    // whose call is ACTIVELY in flight. A report-level job with no result and
    // no error shows the clock (queued) unless its key is here — then the
    // animated hourglass (really running). Empty = the aggregate caller
    // (Manage info row) doesn't care about the clock/hourglass split.
    running: Set<String> = emptySet()
): List<InfoJob> {
    val jobs = mutableListOf<InfoJob>()

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
        val state = when {
            !report.iconErrorMessage.isNullOrBlank() -> InfoJobState.FAILED
            !report.icon.isNullOrBlank() -> InfoJobState.DONE
            "${report.id}|icon" in running -> InfoJobState.RUNNING
            else -> InfoJobState.CLOCK
        }
        val label = report.iconErrorMessage?.takeIf { it.isNotBlank() }
            ?: report.icon?.takeIf { it.isNotBlank() }
            ?: if (state == InfoJobState.RUNNING) "Generating…" else "Queued…"
        jobs += InfoJob("icon", label, state, report.iconInputCost + report.iconOutputCost,
            doneIcon = report.icon, pending = state == InfoJobState.RUNNING || state == InfoJobState.CLOCK)
    }

    // Same "never ran" terminal guard as the icon row above: a finished
    // report with no detected language, no error, and no recorded
    // language attempt (cost / duration / prompt all empty) never ran the
    // language flow — e.g. legacy reports, or a copy / fan-out-derived
    // report whose source had no language. Without this the row spins
    // "Queued…" forever on those.
    val languageNeverRan = report.completedAt != null &&
        report.languageName.isNullOrBlank() &&
        report.languageIconErrorMessage == null &&
        report.languageIconPromptUsed == null &&
        report.languageDurationMs == null && report.languageIconDurationMs == null &&
        report.languageInputCost == 0.0 && report.languageOutputCost == 0.0 &&
        report.languageIconInputCost == 0.0 && report.languageIconOutputCost == 0.0

    // Language detection has its own gate now (split from the icon gate)
    // so report icon and report language can be toggled independently.
    if (reportLanguageOn && !languageNeverRan) {
        val langState = when {
            !report.languageIconErrorMessage.isNullOrBlank() -> InfoJobState.FAILED
            !report.languageName.isNullOrBlank() -> InfoJobState.DONE
            "${report.id}|language" in running -> InfoJobState.RUNNING
            else -> InfoJobState.CLOCK
        }
        val langLabel = report.languageIconErrorMessage?.takeIf { it.isNotBlank() }
            ?: report.languageName?.takeIf { it.isNotBlank() }
            ?: if (langState == InfoJobState.RUNNING) "Detecting…" else "Queued…"
        jobs += InfoJob(
            "language", langLabel, langState,
            report.languageInputCost + report.languageOutputCost +
                report.languageIconInputCost + report.languageIconOutputCost,
            doneIcon = report.languageIcon ?: icons.languageIcon,
            pending = langState == InfoJobState.RUNNING || langState == InfoJobState.CLOCK
        )
    }

    val titlePrompt = settings.internalPrompts.firstOrNull { it.category == "workers" && it.name == "report-title" }
    val titleConfigured = titlePrompt != null && titlePrompt.workers.any { settings.resolveWorker(it) != null }
    // "Never ran" terminal guard, mirroring the icon / language rows: a
    // finished report that recorded no AI-title attempt (no promptUsed,
    // no error, no cost / duration) never ran the report-title job — e.g.
    // legacy reports or a copy whose title was inherited rather than
    // generated. Skip the row instead of spinning "Queued…" forever.
    val titleNeverRan = report.completedAt != null &&
        report.titlePromptUsed.isNullOrBlank() && report.titleErrorMessage == null &&
        report.titleDurationMs == null && report.titleLongDurationMs == null &&
        report.titleInputCost == 0.0 && report.titleOutputCost == 0.0 &&
        report.titleLongInputCost == 0.0 && report.titleLongOutputCost == 0.0
    if (titleModeAi && titleConfigured && !titleNeverRan) {
        val state = when {
            !report.titleErrorMessage.isNullOrBlank() -> InfoJobState.FAILED
            !report.titlePromptUsed.isNullOrBlank() -> InfoJobState.DONE
            "${report.id}|title" in running -> InfoJobState.RUNNING
            else -> InfoJobState.CLOCK
        }
        val label = report.titleErrorMessage?.takeIf { it.isNotBlank() }
            ?: report.title.takeIf { !report.titlePromptUsed.isNullOrBlank() }
            ?: if (state == InfoJobState.RUNNING) "Generating…" else "Queued…"
        jobs += InfoJob("title", label, state,
            report.titleInputCost + report.titleOutputCost + report.titleLongInputCost + report.titleLongOutputCost,
            doneIcon = "🏷️", pending = state == InfoJobState.RUNNING || state == InfoJobState.CLOCK)
    }

    // Per-agent title state — agent must succeed first; when both jobs are
    // on the icon is derived from the title, so the icon waits for it.
    fun titleStateFor(a: com.ai.data.ReportAgent): InfoJobState =
        if (a.reportStatus != ReportStatus.SUCCESS) InfoJobState.CLOCK else when {
            !a.modelTitleErrorMessage.isNullOrBlank() -> InfoJobState.FAILED
            !a.modelTitle.isNullOrBlank() -> InfoJobState.DONE
            // Call concluded but yielded no title and no error → terminal,
            // don't spin forever.
            a.modelTitleAttempted() -> InfoJobState.DONE
            else -> InfoJobState.RUNNING
        }

    // All model-title rows first, then all model-icon rows (after the
    // three report-level rows above).
    // A per-model job is "pending" (keeps the aggregate spinning) only while
    // it can still proceed: RUNNING, or CLOCK with the model still
    // pending/running. A CLOCK left by an ERRORed model is terminal.
    fun perModelPending(a: com.ai.data.ReportAgent, state: InfoJobState): Boolean =
        state == InfoJobState.RUNNING ||
            (state == InfoJobState.CLOCK && a.reportStatus != ReportStatus.ERROR)

    if (perModelTitle) {
        report.agents.forEach { a ->
            val modelName = "${a.provider} · ${shortModelName(a.model)}"
            val titleState = titleStateFor(a)
            jobs += InfoJob(
                "model-title", modelName, titleState,
                a.modelTitleInputCost + a.modelTitleOutputCost, a.agentId,
                // Show the model's found icon when there is one, else 🏷️.
                doneIcon = a.icon?.takeIf { it.isNotBlank() } ?: "🏷️",
                pending = perModelPending(a, titleState)
            )
        }
    }
    if (perModelIcon) {
        report.agents.forEach { a ->
            val modelName = "${a.provider} · ${shortModelName(a.model)}"
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
        InfoJobState.CLOCK -> Text("⏰", fontSize = 16.sp, modifier = Modifier.width(24.dp))
        InfoJobState.RUNNING -> Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            AnimatedHourglass(fontSize = 16.sp)
        }
        InfoJobState.FAILED -> Text("❌", fontSize = 16.sp, modifier = Modifier.width(24.dp))
        // Show the generated icon for this job once done; ✅ when none.
        InfoJobState.DONE -> Text(doneIcon?.takeIf { it.isNotBlank() } ?: "✅", fontSize = 16.sp, modifier = Modifier.width(24.dp))
    }
}

/**
 * "Report - Get info" — full-screen list of the report's metadata-generation
 * jobs, each with a clock/hourglass/cross/ok status and its own cost. The
 * screen total shows in the bottom icon bar (via [TitleBar] costText). Rows
 * are clickable to their existing detail screens, layered over this overlay.
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
    onBack: () -> Unit,
    onOpenIconDetail: () -> Unit,
    onOpenLanguageDetail: () -> Unit,
    onEditTitle: () -> Unit,
    onOpenAgentIconDetail: (String) -> Unit,
    onEditModelTitle: (String) -> Unit,
    onRestartErrors: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val metadataIcons = com.ai.ui.shared.LocalMetadataIcons.current
    val jobs by produceState(initialValue = emptyList<InfoJob>(), reportId, iconRefreshTick, metadataIcons, runningInfoJobs) {
        value = withContext(Dispatchers.IO) {
            val r = ReportStorage.getReport(context, reportId) ?: return@withContext emptyList()
            buildInfoJobs(r, settings, iconGenEnabled, reportLanguageOn, titleModeAi, perModelIcon, perModelTitle, metadataIcons, runningInfoJobs)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // publishBottomBar=false: this screen is drawn as a layer on top
        // of the Manage hub, which keeps publishing its own (full) bottom
        // bar. We render only the top chrome here. (costText would be
        // ignored anyway with nothing published.)
        TitleBar(
            helpTopic = "report_get_info", title = "Report - Get info", subject = "Status of icon, title & language jobs", onBackClick = onBack,
            publishBottomBar = false
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(jobs, key = { "${it.type}-${it.agentId ?: it.label}" }) { job ->
                val click: (() -> Unit)? = when (job.type) {
                    "icon" -> onOpenIconDetail
                    "language" -> onOpenLanguageDetail
                    "title" -> onEditTitle
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
                        val color = if (job.state == InfoJobState.FAILED) AppColors.Red else Color.White
                        Text(job.label, fontSize = 13.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (job.cost > 0.0) {
                        Text(formatCents(job.cost), fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
        // Restart errors — shown only when at least one job failed (red ❌).
        // Clears the errored jobs' error state and re-fires just those.
        if (jobs.any { it.state == InfoJobState.FAILED }) {
            Button(
                onClick = onRestartErrors,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
            ) { Text("Restart errors", maxLines = 1, softWrap = false) }
        }
    }
}
