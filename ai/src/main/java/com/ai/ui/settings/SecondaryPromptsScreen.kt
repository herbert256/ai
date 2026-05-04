package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.SecondaryPrompts
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.GeneralSettings

/**
 * Editor for the three remaining built-in prompt templates: Intro
 * (per-model self-intro on the Comprehensive PDF), Model info (the
 * Model Info screen), and Translate (Translate button on the Report
 * Result screen). Each card is collapsible — closed by default so the
 * screen opens compact. Empty body falls back to the matching
 * SecondaryPrompts default. The Rerank / Summarize / Compare /
 * Moderation templates moved to the Report Meta Prompts CRUD.
 */
@Composable
fun SecondaryPromptsScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    var intro by remember { mutableStateOf(generalSettings.introPrompt) }
    var modelInfo by remember { mutableStateOf(generalSettings.modelInfoPrompt) }
    var translate by remember { mutableStateOf(generalSettings.translatePrompt) }

    LaunchedEffect(intro, modelInfo, translate) {
        val updated = generalSettings.copy(
            introPrompt = intro, modelInfoPrompt = modelInfo, translatePrompt = translate
        )
        if (updated != generalSettings) onSave(updated)
    }

    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Internal Prompts", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Templates used by the per-model self-intro on the Comprehensive PDF export, the Model Info screen, and the Translate button on the Report Result screen. Leave a card empty to use the built-in default. Rerank / Summarize / Compare / Moderation now live under Report Meta Prompts.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )

            PromptCard(
                title = "Intro prompt",
                summary = "Per-model self-intro on PDF export. Variables: @MODEL@, @PROVIDER@, @AGENT@, @NOW@",
                value = intro,
                placeholder = SecondaryPrompts.DEFAULT_INTRO,
                expanded = expanded == "intro",
                onToggle = { expanded = if (expanded == "intro") null else "intro" },
                onValueChange = { intro = it },
                onResetToDefault = { intro = SecondaryPrompts.DEFAULT_INTRO },
                onClear = { intro = "" }
            )
            PromptCard(
                title = "Model info prompt",
                summary = "Asks a model to describe itself on the Model Info screen. Variables: @MODEL@, @PROVIDER@, @AGENT@",
                value = modelInfo,
                placeholder = SecondaryPrompts.DEFAULT_MODEL_INFO,
                expanded = expanded == "model_info",
                onToggle = { expanded = if (expanded == "model_info") null else "model_info" },
                onValueChange = { modelInfo = it },
                onResetToDefault = { modelInfo = SecondaryPrompts.DEFAULT_MODEL_INFO },
                onClear = { modelInfo = "" }
            )
            PromptCard(
                title = "Translate prompt",
                summary = "Used by the Translate button on the Report result screen. Variables: @LANGUAGE@, @TEXT@",
                value = translate,
                placeholder = SecondaryPrompts.DEFAULT_TRANSLATE,
                expanded = expanded == "translate",
                onToggle = { expanded = if (expanded == "translate") null else "translate" },
                onValueChange = { translate = it },
                onResetToDefault = { translate = SecondaryPrompts.DEFAULT_TRANSLATE },
                onClear = { translate = "" }
            )
        }
    }
}

@Composable
private fun PromptCard(
    title: String,
    summary: String,
    value: String,
    placeholder: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onValueChange: (String) -> Unit,
    onResetToDefault: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Header row — tap anywhere to toggle. Shows "default" / "custom"
        // pill so the user can see at a glance which prompts have been
        // customised without expanding every card.
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, fontSize = 11.sp, color = AppColors.TextTertiary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (value.isBlank()) "default" else "custom",
                fontSize = 10.sp,
                color = if (value.isBlank()) AppColors.TextTertiary else AppColors.Green,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary, fontSize = 14.sp)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value, onValueChange = onValueChange,
                    placeholder = { Text(placeholder.lineSequence().firstOrNull().orEmpty(), fontSize = 12.sp, color = AppColors.TextDim, fontFamily = FontFamily.Monospace) },
                    label = { Text("Template (blank = built-in default)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6, maxLines = 18,
                    colors = AppColors.outlinedFieldColors()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onResetToDefault, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
                        Text("Reset to default", maxLines = 1, softWrap = false, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
                        Text("Clear (use default)", maxLines = 1, softWrap = false, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
