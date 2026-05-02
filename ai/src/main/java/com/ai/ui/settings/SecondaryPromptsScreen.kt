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
 * Editor for the five meta prompt templates: Rerank / Summarize /
 * Compare / Intro / Model info. Each prompt gets its own collapsible
 * card — closed by default so the screen opens compact and the user
 * taps a card header to expand and edit one. Empty body falls back to
 * the matching SecondaryPrompts default.
 */
@Composable
fun SecondaryPromptsScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    var rerank by remember { mutableStateOf(generalSettings.rerankPrompt) }
    var summarize by remember { mutableStateOf(generalSettings.summarizePrompt) }
    var compare by remember { mutableStateOf(generalSettings.comparePrompt) }
    var intro by remember { mutableStateOf(generalSettings.introPrompt) }
    var modelInfo by remember { mutableStateOf(generalSettings.modelInfoPrompt) }

    LaunchedEffect(rerank, summarize, compare, intro, modelInfo) {
        val updated = generalSettings.copy(
            rerankPrompt = rerank, summarizePrompt = summarize, comparePrompt = compare,
            introPrompt = intro, modelInfoPrompt = modelInfo
        )
        if (updated != generalSettings) onSave(updated)
    }

    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Internal Prompts", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Templates used by Rerank / Summarize / Compare on the Report screen, the per-model self-intro on the Comprehensive PDF export, and the model-info request on the Model Info screen. Leave a card empty to use the built-in default.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )

            PromptCard(
                title = "Rerank prompt",
                summary = "Variables: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@",
                value = rerank,
                placeholder = SecondaryPrompts.DEFAULT_RERANK,
                expanded = expanded == "rerank",
                onToggle = { expanded = if (expanded == "rerank") null else "rerank" },
                onValueChange = { rerank = it },
                onResetToDefault = { rerank = SecondaryPrompts.DEFAULT_RERANK },
                onClear = { rerank = "" }
            )
            PromptCard(
                title = "Summarize prompt",
                summary = "Variables: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@",
                value = summarize,
                placeholder = SecondaryPrompts.DEFAULT_SUMMARIZE,
                expanded = expanded == "summarize",
                onToggle = { expanded = if (expanded == "summarize") null else "summarize" },
                onValueChange = { summarize = it },
                onResetToDefault = { summarize = SecondaryPrompts.DEFAULT_SUMMARIZE },
                onClear = { summarize = "" }
            )
            PromptCard(
                title = "Compare prompt",
                summary = "Variables: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@",
                value = compare,
                placeholder = SecondaryPrompts.DEFAULT_COMPARE,
                expanded = expanded == "compare",
                onToggle = { expanded = if (expanded == "compare") null else "compare" },
                onValueChange = { compare = it },
                onResetToDefault = { compare = SecondaryPrompts.DEFAULT_COMPARE },
                onClear = { compare = "" }
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
