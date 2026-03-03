package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.*
import com.ai.ui.shared.*

@Composable
fun SystemPromptsListScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddSystemPrompt: () -> Unit,
    onEditSystemPrompt: (String) -> Unit
) {
    CrudListScreen(
        title = "System Prompts",
        items = aiSettings.systemPrompts,
        addLabel = "Add System Prompt",
        emptyMessage = "No system prompts configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { it.prompt.take(80) },
        onAdd = onAddSystemPrompt,
        onEdit = { onEditSystemPrompt(it.id) },
        onDelete = { sp -> onSave(aiSettings.removeSystemPrompt(sp.id)) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "System Prompt",
        deleteEntityName = { it.name }
    )
}

@Composable
fun SystemPromptEditScreen(
    systemPrompt: SystemPrompt?,
    existingNames: Set<String>,
    onSave: (SystemPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = systemPrompt != null

    var name by remember { mutableStateOf(systemPrompt?.name ?: "") }
    var prompt by remember { mutableStateOf(systemPrompt?.prompt ?: "") }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = if (isEditing) "Edit System Prompt" else "Add System Prompt", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.Red) } } else null
            )

            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("System prompt text") }, modifier = Modifier.fillMaxWidth(),
                minLines = 6, maxLines = 15, colors = AppColors.outlinedFieldColors()
            )

            Text("${prompt.length} characters", fontSize = 11.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val id = systemPrompt?.id ?: java.util.UUID.randomUUID().toString()
                onSave(SystemPrompt(id, name.trim(), prompt))
            },
            enabled = nameError == null && prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isEditing) "Save" else "Create") }
    }
}
