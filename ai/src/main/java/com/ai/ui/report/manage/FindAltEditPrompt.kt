package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.Settings
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.AltEditPayload
import com.ai.viewmodel.AltPromptFlow
import com.ai.viewmodel.ResolvedAltPrompt

/**
 * Pre-pick "Edit prompt" step shared by every "Find alternative …" flow.
 * Shown the moment a fan-out is launched, BEFORE the model picker: it
 * resolves [flow]'s alt prompt (every @VAR@ marker already replaced with
 * the concrete report / response / language values) and lets the user tweak
 * the exact text the picked models will receive.
 *
 * Tapping Next hands back an [AltEditPayload] (the edited text + the
 * substitutions, so the edit can be persisted back onto the template); the
 * caller then advances to the model picker. When the flow's alt prompt
 * isn't configured, resolution returns null and Next just proceeds with no
 * override (the fan-out falls back to its own resolution).
 */
@Composable
internal fun FindAltPromptEditorScreen(
    flow: AltPromptFlow,
    aiSettings: Settings,
    onResolve: suspend (AltPromptFlow) -> ResolvedAltPrompt?,
    onNext: (AltEditPayload?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    // produceState double-wraps so we can tell "still loading" (null outer)
    // from "loaded, no alt prompt" (non-null outer holding null).
    val resolvedState = produceState<Pair<Boolean, ResolvedAltPrompt?>>(false to null, flow) {
        value = true to onResolve(flow)
    }
    val (loaded, resolved) = resolvedState.value

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_find_alt_prompt",
            title = "Edit prompt",
            subject = "Sent to the models you pick next",
            onBackClick = onBack,
            publishBottomBar = false
        )

        if (!loaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AnimatedHourglass()
            }
            return
        }

        if (resolved == null) {
            // Alt prompt not configured for this flow — nothing to edit.
            Text(
                "This Find-alternative prompt isn't configured, so there's nothing to edit. Continue to pick models.",
                color = AppColors.TextSecondary, fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { onNext(null) },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Continue", maxLines = 1, softWrap = false) }
            return
        }

        // Key the draft on the resolved text so a fresh resolution (a new
        // flow, or a persisted edit) reseeds the field.
        var text by rememberSaveable(resolved.resolved) { mutableStateOf(resolved.resolved) }

        Text(
            "Markers (@…@) are already filled in. Edits here are sent this run, and saved back to the template when they can be cleanly re-applied.",
            color = AppColors.TextTertiary, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = AppColors.outlinedFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                onNext(AltEditPayload(resolved.promptId, text, resolved.subs))
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Next — pick models", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
