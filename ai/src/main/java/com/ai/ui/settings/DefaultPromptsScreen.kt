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
fun DefaultPromptEditScreen(
    defaultPrompt: DefaultPrompt?,
    existingNames: Set<String>,
    onSave: (DefaultPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** 🗑 delete this default prompt (Prompt management → Default prompts edit). */
    onDelete: (() -> Unit)? = null
) {
    var resetTick by remember { mutableStateOf(0) }

    var name by remember(resetTick) { mutableStateOf(defaultPrompt?.name ?: "") }
    var prompt by remember(resetTick) { mutableStateOf(defaultPrompt?.prompt ?: "") }

    val dup = rememberDuplicateMode(
        isEditingExisting = defaultPrompt != null,
        onDuplicate = { name = "$name-copy" }
    )
    val isAddMode = dup.isAddMode
    val effectiveExistingNames = if (isAddMode && defaultPrompt != null) {
        existingNames + defaultPrompt.name.trim().lowercase(java.util.Locale.ROOT)
    } else existingNames

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.trim().lowercase(java.util.Locale.ROOT) in effectiveExistingNames -> "Name already exists"
        else -> null
    }

    val promptId = remember { java.util.UUID.randomUUID().toString() }
    val current = if (nameError == null && prompt.isNotBlank())
        DefaultPrompt(if (isAddMode) promptId else defaultPrompt!!.id, name.trim(), prompt) else null
    val back = com.ai.ui.shared.rememberConfirmedBack(current, onBack)
    BackHandler { back() }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "default_prompt_edit",
            title = if (isAddMode) "Add Default Prompt" else "Edit Default Prompt",
            subject = name,
            onBackClick = back,
            // 👯 duplicate into a new prompt, 🗑 delete — both hidden in add/copy mode.
            onCopyReport = dup.copyTrigger,
            onDelete = if (isAddMode) null else onDelete,
            onClear = { resetTick++ }
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onSave(current!!); onBack() },
            enabled = current != null,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text(if (isAddMode) "Create" else "Save", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError, color = AppColors.DangerAccent) } } else null
            )

            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("Default prompt text") }, modifier = Modifier.fillMaxWidth(),
                minLines = 6, maxLines = 15, colors = AppColors.outlinedFieldColors()
            )

            Text("${prompt.length} characters", fontSize = 11.sp, color = AppColors.TextTertiary)
        }

    }
}
