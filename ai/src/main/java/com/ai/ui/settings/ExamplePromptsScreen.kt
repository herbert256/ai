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
fun ExamplePromptEditScreen(
    examplePrompt: ExamplePrompt?,
    onSave: (ExamplePrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** 🗑 delete this example prompt (Prompt management → Example prompts edit). */
    onDelete: (() -> Unit)? = null
) {
    BackHandler { onBack() }
    val isEditing = examplePrompt != null
    var resetTick by remember { mutableStateOf(0) }

    var title by remember(resetTick) { mutableStateOf(examplePrompt?.title ?: "") }
    var text by remember(resetTick) { mutableStateOf(examplePrompt?.text ?: "") }

    val dup = rememberDuplicateMode(
        isEditingExisting = examplePrompt != null,
        onDuplicate = { title = "$title-copy" }
    )
    val isAddMode = dup.isAddMode

    val titleError = if (title.isBlank()) "Title is required" else null

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "example_prompt_edit",
            title = if (isAddMode) "Add example prompt" else "Edit example prompt",
            subject = title,
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
                    onSave(ExamplePrompt(id = java.util.UUID.randomUUID().toString(), title = title.trim(), text = text)); onBack()
                },
                enabled = titleError == null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.SuccessAccent)
            ) { Text("Create", maxLines = 1, softWrap = false) }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            // Edit: no Save button — auto-persist while editing and on leave.
            com.ai.ui.shared.AutoSaveOnChange(
                current = if (titleError == null)
                    ExamplePrompt(id = examplePrompt!!.id, title = title.trim(), text = text) else null,
                onSave = onSave
            )
        }

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

    }
}
