package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * Lets the user edit just the report title + prompt without re-running. The new values
 * are persisted to the saved report and reflected in UiState; the user picks Regenerate
 * from the Actions row when they actually want to run with the updated prompt.
 */
@Composable
fun ReportEditPromptScreen(
    initialTitle: String,
    initialPrompt: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onUpdate: (newTitle: String, newPrompt: String) -> Unit
) {
    BackHandler { onBack() }
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var prompt by rememberSaveable { mutableStateOf(initialPrompt) }
    val canUpdate = prompt.trim().isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Edit prompt", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onUpdate(title.trim(), prompt.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Update prompt", maxLines = 1, softWrap = false) }
    }
}
