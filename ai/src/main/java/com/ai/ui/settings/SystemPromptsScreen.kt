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
fun SystemPromptEditScreen(
    systemPrompt: SystemPrompt?,
    existingNames: Set<String>,
    onSave: (SystemPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** 🗑 delete this system prompt (Prompt management → System prompts edit). */
    onDelete: (() -> Unit)? = null
) {
    BackHandler { onBack() }
    val isEditing = systemPrompt != null
    var resetTick by remember { mutableStateOf(0) }

    var name by remember(resetTick) { mutableStateOf(systemPrompt?.name ?: "") }
    var prompt by remember(resetTick) { mutableStateOf(systemPrompt?.prompt ?: "") }

    val dup = rememberDuplicateMode(
        isEditingExisting = systemPrompt != null,
        onDuplicate = { name = "$name-copy" }
    )
    val isAddMode = dup.isAddMode
    val effectiveExistingNames = if (isAddMode && systemPrompt != null) {
        existingNames + systemPrompt.name.lowercase()
    } else existingNames

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in effectiveExistingNames -> "Name already exists"
        else -> null
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "system_prompt_edit",
            title = if (isAddMode) "Add System Prompt" else "Edit System Prompt",
            subject = name,
            onBackClick = onBack,
            // 👯 duplicate into a new prompt, 🗑 delete — both hidden in add/copy mode.
            onCopyReport = dup.copyTrigger,
            onDelete = if (isAddMode) null else onDelete,
            onClear = { resetTick++ }
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (isAddMode) {
            Button(
                onClick = {
                    onSave(SystemPrompt(java.util.UUID.randomUUID().toString(), name.trim(), prompt)); onBack()
                },
                enabled = nameError == null && prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text("Create", maxLines = 1, softWrap = false) }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            // Edit: no Save button — auto-persist while editing and on leave.
            com.ai.ui.shared.AutoSaveOnChange(
                current = if (nameError == null && prompt.isNotBlank())
                    SystemPrompt(systemPrompt!!.id, name.trim(), prompt) else null,
                onSave = onSave
            )
        }

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

    }
}
