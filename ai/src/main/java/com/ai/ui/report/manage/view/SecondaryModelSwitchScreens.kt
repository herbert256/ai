package com.ai.ui.report.manage.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.SelectModelScreen
import com.ai.ui.shared.SelectProviderScreen
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.ModelSwitchResult
import com.ai.viewmodel.ModelSwitchSelection
import com.ai.viewmodel.ModelSwitchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Empty fallback for `collectAsState` when the model-switch manager local is
 *  absent (the compositionLocal default) — keeps the collect unconditional. */
internal val emptyModelSwitchStatesFlow: StateFlow<Map<String, ModelSwitchState>> = MutableStateFlow(emptyMap())

/**
 * Picker for the "Switch model / agent" Change-result action. Lets the user
 * re-run a secondary result against either a saved Agent (which carries its own
 * provider/model + presets) or a raw provider + model. Reuses the existing
 * [com.ai.ui.other.ReportSelectAgentScreen] / [SelectProviderScreen] /
 * [SelectModelScreen]; resolves the pick to a [ModelSwitchSelection] and hands
 * it to [onPicked].
 */
@Composable
internal fun SecondaryModelSwitchPickScreen(
    aiSettings: Settings,
    rowParamsIds: List<String>,
    rowSystemPromptId: String?,
    onPicked: (ModelSwitchSelection) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    var step by remember { mutableStateOf("choose") }
    var pickedProvider by remember { mutableStateOf<AppService?>(null) }

    when (step) {
        "agent" -> {
            com.ai.ui.other.ReportSelectAgentScreen(
                aiSettings = aiSettings,
                onSelectAgent = { a ->
                    val model = aiSettings.getEffectiveModelForAgent(a)
                    onPicked(ModelSwitchSelection(a.provider, model, a.paramsIds, a.systemPromptId, "${a.name} — ${a.provider.id} / $model"))
                },
                onBack = { step = "choose" },
                onEditAgents = onBack
            )
            return
        }
        "provider" -> {
            SelectProviderScreen(
                aiSettings = aiSettings,
                onSelectProvider = { p -> pickedProvider = p; step = "model" },
                onBack = { step = "choose" },
                onNavigateHome = onNavigateHome,
                activeOnly = true
            )
            return
        }
        "model" -> {
            val p = pickedProvider
            if (p != null) {
                SelectModelScreen(
                    provider = p,
                    aiSettings = aiSettings,
                    currentModel = "",
                    onSelectModel = { m -> onPicked(ModelSwitchSelection(p, m, rowParamsIds, rowSystemPromptId, "${p.id} / $m")) },
                    onBack = { step = "provider" },
                    onNavigateHome = onNavigateHome
                )
                return
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "model_switch",
            title = "Switch model / agent",
            subject = "Re-run this result against another model",
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = { step = "agent" }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
            Text("Choose an agent", maxLines = 1)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { step = "provider" }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
            Text("Choose a provider & model", maxLines = 1)
        }
    }
}

/**
 * Preview of a "Switch model / agent" candidate. Shows the running / error /
 * success state for the re-run; on success renders the candidate via [body]
 * (each detail screen supplies its kind's renderer) with Use / Discard. The
 * candidate is only committed onto the row when the user taps Use.
 */
@Composable
internal fun SecondaryModelSwitchPreviewScreen(
    state: ModelSwitchState,
    onUse: () -> Unit,
    onDiscard: () -> Unit,
    onTrace: (String) -> Unit,
    onBack: () -> Unit,
    body: @Composable (content: String) -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "model_switch",
            title = "Switch model / agent",
            subject = state.selection.label,
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(12.dp))
        when (val r = state.result) {
            is ModelSwitchResult.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.InfoAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Running ${state.selection.label}…", fontSize = 13.sp, color = AppColors.TextSecondary)
                }
            }
            is ModelSwitchResult.Error -> {
                Text("Error", fontSize = 14.sp, color = AppColors.DangerAccent, fontWeight = FontWeight.SemiBold)
                Text(r.message, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                if (r.traceFile != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { onTrace(r.traceFile) }, colors = AppColors.outlinedButtonColors()) { Text("🐞 Trace", maxLines = 1) }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) { Text("Discard", maxLines = 1) }
            }
            is ModelSwitchResult.Success -> {
                val cost = (r.inputCost ?: 0.0) + (r.outputCost ?: 0.0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append("%.1fs".format(java.util.Locale.US, r.durationMs / 1000.0))
                            if (cost > 0.0) append("  ·  ${formatCents(cost)}")
                        },
                        fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    if (r.traceFile != null) {
                        Text("🐞", fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).clickable { onTrace(r.traceFile) })
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    body(r.content)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Discard", maxLines = 1) }
                    OutlinedButton(onClick = onUse, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Use", maxLines = 1, color = AppColors.SuccessAccent) }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
