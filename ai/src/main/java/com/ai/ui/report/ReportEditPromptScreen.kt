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
 * Edit the report's prompt body. Saving sets `hasPendingPromptChange`
 * so the result screen surfaces a "regenerate to apply" hint — the
 * model output is stale until the user re-runs.
 */
@Composable
fun ReportEditPromptScreen(
    initialPrompt: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onUpdate: (newPrompt: String) -> Unit
) {
    BackHandler { onBack() }
    var prompt by rememberSaveable { mutableStateOf(initialPrompt) }
    val canUpdate = prompt.trim().isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Edit prompt", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onUpdate(prompt.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Update prompt", maxLines = 1, softWrap = false) }
    }
}

/**
 * Edit just the report title. Title changes don't affect any outbound
 * API call, so saving updates the persisted report + UiState in place
 * without flagging the report as needing a regenerate.
 */
@Composable
fun ReportEditTitleScreen(
    initialTitle: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onUpdate: (newTitle: String) -> Unit
) {
    BackHandler { onBack() }
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    val canUpdate = title.trim().isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Edit title", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { onUpdate(title.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Update title", maxLines = 1, softWrap = false) }
    }
}
