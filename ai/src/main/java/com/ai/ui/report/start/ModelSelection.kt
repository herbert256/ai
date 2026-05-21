package com.ai.ui.report.start
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.ReportModel
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.modelInfoClickable
import com.ai.viewmodel.IconCandidate

/** Multi-model accumulator with the +Agent / +Flock / +Swarm /
 *  +Report / +Model chip row used by the New-Report SelectionPhase,
 *  but stripped of the Params / Sys-prompt / Knowledge rows. Shared
 *  by the "Find alternative icons" flow (reached from
 *  `ReportIconDetailScreen`) and the Translate flow — the title,
 *  action-button label / colour and help topic are parameterised so
 *  each caller reads correctly. The +chip callbacks and the action
 *  button are wired by the caller. */
@Composable
fun ModelSelectionScreen(
    models: List<ReportModel>,
    aiSettings: Settings,
    onAddAgent: () -> Unit,
    onAddFlock: () -> Unit,
    onAddSwarm: () -> Unit,
    onAddFromReport: () -> Unit,
    onAddAllModels: () -> Unit,
    onRemoveModel: (Int) -> Unit,
    onClearAll: () -> Unit,
    onAction: () -> Unit,
    onBack: () -> Unit,
    title: String = "Find icons",
    subject: String? = null,
    actionLabel: String = "Find Icons",
    actionColor: Color = AppColors.Green,
    helpTopic: String = "find_icons_selection"
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = helpTopic, title = title, subject = subject, onBackClick = onBack)

        // Primary CTA hoisted to the top — kept gated on
        // `models.isNotEmpty()` so the empty-state still shows a
        // disabled button as a hint. Clear stays at the bottom.
        Button(
            onClick = onAction,
            enabled = models.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = actionColor)
        ) { androidx.compose.material3.Text(actionLabel, maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        // +Add chip row — same layout as SelectionPhase but fewer
        // sources matter for an icon: we keep all the same chips so
        // the affordance is familiar. +Report is conditional on
        // having saved reports.
        val hasAnyReport by androidx.compose.runtime.produceState(initialValue = false) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.ai.data.ReportStorage.getAllReports(context).isNotEmpty()
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val entries = mutableListOf(
                "Agent" to onAddAgent,
                "Flock" to onAddFlock,
                "Swarm" to onAddSwarm
            )
            if (hasAnyReport) entries.add("Report" to onAddFromReport)
            entries.add("Model" to onAddAllModels)
            entries.forEach { (label, action) ->
                OutlinedButton(
                    onClick = action,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                    colors = AppColors.outlinedButtonColors()
                ) { androidx.compose.material3.Text("+$label", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when (val n = models.size) {
                0 -> "No models selected"
                1 -> "1 model"
                else -> "$n models"
            },
            fontSize = 11.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        val sortedIndices = remember(models) {
            models.indices.sortedBy { models[it].model.lowercase() }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            sortedIndices.forEach { index ->
                val entry = models[index]
                val pricing = formatPricingPerMillion(context, entry.provider, entry.model)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .modelInfoClickable(entry.provider, entry.model)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Text(com.ai.ui.shared.shortModelName(entry.model), fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(entry.provider, entry.model))
                            com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(entry.provider, entry.model))
                            com.ai.ui.shared.ReasoningBadge(aiSettings.isReasoningCapable(entry.provider, entry.model))
                        }
                        androidx.compose.material3.Text(
                            "${entry.provider.id}${if (entry.sourceName.isNotBlank()) " via ${entry.sourceName}" else ""}",
                            fontSize = 11.sp, color = AppColors.TextTertiary
                        )
                    }
                    androidx.compose.material3.Text(
                        pricing.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = if (pricing.isDefault) AppColors.SurfaceDark else AppColors.Red,
                        modifier = if (pricing.isDefault) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.Text(
                        "✕", color = AppColors.Red, fontSize = 14.sp,
                        modifier = Modifier.clickable { onRemoveModel(index) }
                    )
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }

        // Bottom row — Clear only (the primary CTA is hoisted to
        // the top). Hidden when nothing's selected.
        if (models.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClearAll,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { androidx.compose.material3.Text("Clear", maxLines = 1, softWrap = false) }
        }
    }
}
