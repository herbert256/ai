package com.ai.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * Parsed payload of a cross-app `com.ai.ACTION_NEW_REPORT` intent.
 * Held by [com.ai.ui.navigation.AppNavHost] between the parse step
 * and the user's explicit confirmation — without this gate any
 * installed app could trigger model calls (spending the user's API
 * credits) and the auto-side-effects (email / share / browser /
 * return) attached to instructions.
 */
data class PendingExternalReport(
    val title: String?,
    val systemPrompt: String?,
    val aiPrompt: String,
    val openHtml: String?,
    val closeHtml: String?,
    val reportType: String?,
    val email: String?,
    val nextAction: String?,
    val hasReturn: Boolean,
    val hasEdit: Boolean,
    val hasSelect: Boolean,
    val agentNames: List<String>,
    val flockNames: List<String>,
    val swarmNames: List<String>,
    val modelSpecs: List<String>
) {
    val willAutoGenerate: Boolean get() = !hasEdit && !hasSelect &&
        reportType != null &&
        (agentNames.isNotEmpty() || flockNames.isNotEmpty() ||
            swarmNames.isNotEmpty() || modelSpecs.isNotEmpty())
}

/**
 * Confirmation overlay shown before any cross-app ACTION_NEW_REPORT
 * with `<instructions>` is acted on. Lays out exactly what will
 * happen: which models get called, which side effects (email, next
 * action, return-on-completion) fire, and a preview of the prompt.
 * Generate is the only path that spends API credits.
 */
@Composable
fun ExternalIntentConfirmScreen(
    intent: PendingExternalReport,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    BackHandler { onCancel() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TitleBar(helpTopic = "external_intent", title = "External request", onBackClick = onCancel)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Another app asked this app to run a report. Review what it will do before generating — this can spend API credits.",
            fontSize = 12.sp, color = AppColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SourceCard(intent)
            ActionCard(intent)
            val hasSideEffects = !intent.email.isNullOrBlank() ||
                !intent.nextAction.isNullOrBlank() || intent.hasReturn
            if (hasSideEffects) SideEffectsCard(intent)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) { Text("Cancel") }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) { Text(if (intent.willAutoGenerate) "Generate" else "Continue") }
        }
    }
}

@Composable
private fun SourceCard(intent: PendingExternalReport) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Prompt", fontSize = 11.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
            intent.title?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            val previewLimit = 400
            val preview = if (intent.aiPrompt.length > previewLimit)
                intent.aiPrompt.take(previewLimit) + "…" else intent.aiPrompt
            Text(preview, fontSize = 12.sp, color = AppColors.TextSecondary)
            if (!intent.systemPrompt.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text("System prompt: ${intent.systemPrompt.take(120)}${if (intent.systemPrompt.length > 120) "…" else ""}",
                    fontSize = 11.sp, color = AppColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun ActionCard(intent: PendingExternalReport) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Will do", fontSize = 11.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
            val headline = when {
                intent.hasEdit -> "Open the new-report editor with the prompt pre-filled"
                intent.willAutoGenerate -> "Generate a report immediately"
                intent.hasSelect -> "Open agent/model selection for a report"
                else -> "Open agent/model selection for a report"
            }
            Text(headline, fontSize = 13.sp, color = Color.White)

            intent.reportType?.takeIf { it.isNotBlank() }?.let {
                Text("Report type: $it", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            val agents = (intent.agentNames + intent.flockNames + intent.swarmNames + intent.modelSpecs)
                .filter { it.isNotBlank() }
            if (agents.isNotEmpty()) {
                Text("Targets:", fontSize = 11.sp, color = AppColors.TextTertiary)
                agents.forEach { Text("• $it", fontSize = 12.sp, color = AppColors.TextSecondary) }
            } else if (intent.willAutoGenerate.not()) {
                Text("You pick the models on the next screen.", fontSize = 11.sp, color = AppColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun SideEffectsCard(intent: PendingExternalReport) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A2A))) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("After generation", fontSize = 11.sp, color = AppColors.RedBright, fontWeight = FontWeight.SemiBold)
            if (!intent.email.isNullOrBlank()) {
                Text("• Email the report to ${intent.email}", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            when (intent.nextAction?.lowercase()) {
                "view" -> Text("• Open the report viewer", fontSize = 12.sp, color = AppColors.TextSecondary)
                "share" -> Text("• Share the report HTML", fontSize = 12.sp, color = AppColors.TextSecondary)
                "browser" -> Text("• Open the report in Chrome", fontSize = 12.sp, color = AppColors.TextSecondary)
                "email" -> Text("• Email the report to your default address", fontSize = 12.sp, color = AppColors.TextSecondary)
                else -> Unit
            }
            if (intent.hasReturn) {
                Text("• Close this app after the side effect completes", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
        }
    }
}
