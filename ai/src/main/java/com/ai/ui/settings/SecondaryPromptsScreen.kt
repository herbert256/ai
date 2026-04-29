package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.SecondaryPrompts
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.GeneralSettings

/**
 * Editor for the two report meta-result templates: rerank and summarize.
 * Empty body falls back to [SecondaryPrompts.DEFAULT_RERANK] /
 * [SecondaryPrompts.DEFAULT_SUMMARIZE]. Available placeholders are listed
 * inline so the user knows what to thread through the prompt.
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

    LaunchedEffect(rerank, summarize) {
        val updated = generalSettings.copy(rerankPrompt = rerank, summarizePrompt = summarize)
        if (updated != generalSettings) onSave(updated)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Rerank & Summarize", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Templates used by the Rerank and Summarize buttons on the Report screen. " +
                    "Leave blank to use the built-in default. Variables are substituted at run time.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            Text(
                "Available variables: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@",
                fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
            )

            PromptCard(
                title = "Rerank prompt",
                value = rerank,
                placeholder = SecondaryPrompts.DEFAULT_RERANK,
                onValueChange = { rerank = it },
                onResetToDefault = { rerank = SecondaryPrompts.DEFAULT_RERANK },
                onClear = { rerank = "" }
            )

            PromptCard(
                title = "Summarize prompt",
                value = summarize,
                placeholder = SecondaryPrompts.DEFAULT_SUMMARIZE,
                onValueChange = { summarize = it },
                onResetToDefault = { summarize = SecondaryPrompts.DEFAULT_SUMMARIZE },
                onClear = { summarize = "" }
            )
        }
    }
}

@Composable
private fun PromptCard(
    title: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onResetToDefault: () -> Unit,
    onClear: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (value.isBlank()) {
                    Text("default", fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                }
            }
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
