package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val doneIcon: String? = null
)

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
    titleModeAi: Boolean,
    perModelIcon: Boolean,
    perModelTitle: Boolean
): List<InfoJob> {
    val jobs = mutableListOf<InfoJob>()

    val iconPrompt = settings.internalPrompts.firstOrNull { it.category == "icons" && it.name == "main" }
    val iconAgent = iconPrompt?.let { p -> settings.agents.firstOrNull { it.name.equals(p.agent, ignoreCase = true) } }
    val iconRowOn = iconGenEnabled && iconPrompt != null && iconAgent != null

    if (iconRowOn) {
        val state = when {
            report.iconErrorMessage != null -> InfoJobState.FAILED
            report.icon != null -> InfoJobState.DONE
            else -> InfoJobState.RUNNING
        }
        val label = report.iconErrorMessage ?: report.icon ?: "Generating…"
        jobs += InfoJob("icon", label, state, report.iconInputCost + report.iconOutputCost, doneIcon = report.icon)

        // Language detection shares the icon-gen gate (same as the old
        // Manage row, which nested the language row inside the icon gate).
        val langState = when {
            report.languageIconErrorMessage != null -> InfoJobState.FAILED
            report.languageName != null -> InfoJobState.DONE
            else -> InfoJobState.RUNNING
        }
        val langLabel = report.languageIconErrorMessage ?: report.languageName ?: "Detecting…"
        jobs += InfoJob(
            "language", langLabel, langState,
            report.languageInputCost + report.languageOutputCost +
                report.languageIconInputCost + report.languageIconOutputCost,
            doneIcon = report.languageIcon ?: "🌐"
        )
    }

    val titlePrompt = settings.internalPrompts.firstOrNull { it.category == "info" && it.name == "report_title" }
    val titleAgent = titlePrompt?.let { p -> settings.agents.firstOrNull { it.name.equals(p.agent, ignoreCase = true) } }
    if (titleModeAi && titlePrompt != null && titleAgent != null) {
        val state = when {
            report.titleErrorMessage != null -> InfoJobState.FAILED
            report.titlePromptUsed != null -> InfoJobState.DONE
            else -> InfoJobState.RUNNING
        }
        val label = report.titleErrorMessage
            ?: report.title.takeIf { report.titlePromptUsed != null }
            ?: "Generating…"
        jobs += InfoJob("title", label, state, report.titleInputCost + report.titleOutputCost, doneIcon = "🏷️")
    }

    // Per-agent title state — agent must succeed first; when both jobs are
    // on the icon is derived from the title, so the icon waits for it.
    fun titleStateFor(a: com.ai.data.ReportAgent): InfoJobState =
        if (a.reportStatus != ReportStatus.SUCCESS) InfoJobState.CLOCK else when {
            a.modelTitleErrorMessage != null -> InfoJobState.FAILED
            a.modelTitle != null -> InfoJobState.DONE
            else -> InfoJobState.RUNNING
        }

    // All model-title rows first, then all model-icon rows (after the
    // three report-level rows above).
    if (perModelTitle) {
        report.agents.forEach { a ->
            val modelName = "${a.provider} · ${shortModelName(a.model)}"
            jobs += InfoJob(
                "model-title", modelName, titleStateFor(a),
                a.modelTitleInputCost + a.modelTitleOutputCost, a.agentId,
                // Show the model's found icon when there is one, else 🏷️.
                doneIcon = a.icon?.takeIf { it.isNotBlank() } ?: "🏷️"
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
                a.iconErrorMessage != null -> InfoJobState.FAILED
                a.icon != null -> InfoJobState.DONE
                else -> InfoJobState.RUNNING
            }
            // Label shows the found title (the icon is derived from it);
            // falls back to the model name when there's no title.
            jobs += InfoJob("model-icon", foundTitle ?: modelName, iconState,
                a.iconInputCost + a.iconOutputCost, a.agentId, doneIcon = a.icon)
        }
    }
    return jobs
}

/** Aggregate state for the Manage **info** row: ❌ if any job failed, else
 *  ⏳ if any job is clock/running, else ✅. */
fun aggregateInfoState(jobs: List<InfoJob>): InfoJobState = when {
    jobs.any { it.state == InfoJobState.FAILED } -> InfoJobState.FAILED
    jobs.any { it.state == InfoJobState.CLOCK || it.state == InfoJobState.RUNNING } -> InfoJobState.RUNNING
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
    titleModeAi: Boolean,
    perModelIcon: Boolean,
    perModelTitle: Boolean,
    onBack: () -> Unit,
    onOpenIconDetail: () -> Unit,
    onOpenLanguageDetail: () -> Unit,
    onEditTitle: () -> Unit,
    onOpenAgentIconDetail: (String) -> Unit,
    onEditModelTitle: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val jobs by produceState(initialValue = emptyList<InfoJob>(), reportId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            val r = ReportStorage.getReport(context, reportId) ?: return@withContext emptyList()
            buildInfoJobs(r, settings, iconGenEnabled, titleModeAi, perModelIcon, perModelTitle)
        }
    }
    val total = jobs.sumOf { it.cost }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_get_info", title = "Report - Get info", onBackClick = onBack,
            costText = total.takeIf { it > 0.0 }?.let { formatCents(it, 2) }
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
    }
}
