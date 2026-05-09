package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.*
import com.ai.ui.shared.*

@Composable
fun ExamplePromptsListScreen(
    aiSettings: Settings,
    onBackToPromptsSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddExamplePrompt: () -> Unit,
    onEditExamplePrompt: (String) -> Unit
) {
    CrudListScreen(
        title = "Example prompts",
        items = aiSettings.examplePrompts,
        addLabel = "Add example prompt",
        emptyMessage = "No example prompts yet",
        sortKey = { it.title },
        itemTitle = { it.title.ifBlank { "(untitled)" } },
        itemSubtitle = { it.text.lineSequence().firstOrNull().orEmpty().take(80) },
        onAdd = onAddExamplePrompt,
        onEdit = { onEditExamplePrompt(it.id) },
        onDelete = { ep -> onSave(aiSettings.removeExamplePrompt(ep.id)) },
        onBack = onBackToPromptsSetup,
        onHome = onBackToHome,
        deleteEntityType = "Example prompt",
        deleteEntityName = { it.title.ifBlank { "(untitled)" } },
        itemKey = { it.id }
    )
}

@Composable
fun ExamplePromptEditScreen(
    examplePrompt: ExamplePrompt?,
    onSave: (ExamplePrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = examplePrompt != null

    var title by remember { mutableStateOf(examplePrompt?.title ?: "") }
    var text by remember { mutableStateOf(examplePrompt?.text ?: "") }

    val titleError = if (title.isBlank()) "Title is required" else null

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "example_prompt_edit",
            title = if (isEditing) "Edit example prompt" else "Add example prompt",
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = title.isNotBlank().not() && titleError != null
            )

            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Text") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8, maxLines = 22,
                colors = AppColors.outlinedFieldColors()
            )
            Text("${text.length} characters", fontSize = 11.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val id = examplePrompt?.id ?: java.util.UUID.randomUUID().toString()
                onSave(ExamplePrompt(id = id, title = title.trim(), text = text))
            },
            enabled = titleError == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isEditing) "Save" else "Create", maxLines = 1, softWrap = false) }
    }
}
