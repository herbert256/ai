package com.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.AiColors
import java.util.UUID

/**
 * AI System Prompts list screen - shows all system prompts with add/edit/delete.
 */
@Composable
fun AiSystemPromptsListScreen(
    aiSettings: AiSettings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onAddSystemPrompt: () -> Unit,
    onEditSystemPrompt: (String) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf<AiSystemPrompt?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "AI System Prompts",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Add system prompt button
        Button(
            onClick = onAddSystemPrompt,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AiColors.Purple)
        ) {
            Text("Add System Prompt")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (aiSettings.systemPrompts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No system prompts configured.\nAdd a system prompt to reuse across agents, flocks, and swarms.",
                    color = AiColors.TextTertiary,
                    fontSize = 16.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val sortedSystemPrompts = remember(aiSettings.systemPrompts) { aiSettings.systemPrompts.sortedBy { it.name.lowercase() } }
                sortedSystemPrompts.forEach { sp ->
                    SettingsListItemCard(
                        title = sp.name,
                        subtitle = sp.prompt.take(80) + if (sp.prompt.length > 80) "..." else "",
                        onClick = { onEditSystemPrompt(sp.id) },
                        onDelete = { showDeleteDialog = sp }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { sp ->
        DeleteConfirmationDialog(
            entityType = "System Prompt",
            entityName = sp.name,
            onConfirm = {
                onSave(aiSettings.removeSystemPrompt(sp.id))
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }
}

/**
 * System prompt edit screen for creating or editing system prompts.
 */
@Composable
fun SystemPromptEditScreen(
    systemPrompt: AiSystemPrompt?,
    existingNames: Set<String>,
    onSave: (AiSystemPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val isEditing = systemPrompt != null

    var name by remember { mutableStateOf(systemPrompt?.name ?: "") }
    var prompt by remember { mutableStateOf(systemPrompt?.prompt ?: "") }
    var nameError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = if (isEditing) "Edit System Prompt" else "Add System Prompt",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Name field
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = { Text("Name") },
                placeholder = { Text("Enter a name for this system prompt") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it, color = AiColors.Red) } },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AiColors.Purple,
                    unfocusedBorderColor = AiColors.BorderUnfocused,
                    focusedLabelColor = AiColors.Purple,
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Prompt text field
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("System Prompt") },
                placeholder = { Text("Enter the system prompt text...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 15,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AiColors.Purple,
                    unfocusedBorderColor = AiColors.BorderUnfocused,
                    focusedLabelColor = AiColors.Purple,
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Character count
            Text(
                text = "${prompt.length} characters",
                fontSize = 12.sp,
                color = AiColors.TextTertiary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Save button
        Button(
            onClick = {
                when {
                    name.isBlank() -> {
                        nameError = "Name is required"
                    }
                    name.trim() in existingNames -> {
                        nameError = "A system prompt with this name already exists"
                    }
                    else -> {
                        val newSystemPrompt = AiSystemPrompt(
                            id = systemPrompt?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            prompt = prompt
                        )
                        onSave(newSystemPrompt)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AiColors.Purple)
        ) {
            Text(if (isEditing) "Save Changes" else "Create System Prompt")
        }
    }
}

/**
 * Reusable System Prompt selector with single-select support.
 * Shows a button that opens a single-select dialog.
 */
@Composable
fun SystemPromptSelector(
    aiSettings: AiSettings,
    selectedSystemPromptId: String?,
    onSystemPromptSelected: (String?) -> Unit,
    label: String = "System Prompt"
) {
    var showDialog by remember { mutableStateOf(false) }
    val hasSelection = selectedSystemPromptId != null
    val selectedName = selectedSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.name }

    Button(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Indigo),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (hasSelection) {
            Text("\uD83D\uDCAC ", fontSize = 14.sp)
        }
        Text(
            if (hasSelection && selectedName != null) "$label: $selectedName" else label,
            fontSize = 14.sp
        )
    }

    if (showDialog) {
        SystemPromptSelectorDialog(
            aiSettings = aiSettings,
            selectedSystemPromptId = selectedSystemPromptId,
            onSystemPromptSelected = { id ->
                onSystemPromptSelected(id)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

/**
 * Single-select system prompt dialog.
 */
@Composable
fun SystemPromptSelectorDialog(
    aiSettings: AiSettings,
    selectedSystemPromptId: String?,
    onSystemPromptSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select System Prompt", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Clear option
                TextButton(
                    onClick = { onSystemPromptSelected(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("None (Clear)", color = AiColors.Red)
                }

                if (aiSettings.systemPrompts.isNotEmpty()) {
                    val sortedSPs = remember(aiSettings.systemPrompts) { aiSettings.systemPrompts.sortedBy { it.name.lowercase() } }
                    sortedSPs.forEach { sp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSystemPromptSelected(sp.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sp.id == selectedSystemPromptId,
                                onClick = { onSystemPromptSelected(sp.id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(sp.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text(
                                    sp.prompt.take(60) + if (sp.prompt.length > 60) "..." else "",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "No system prompts configured.\nGo to AI Setup > System Prompts to create one.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
