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
import com.ai.data.AiService
import java.util.UUID

/**
 * AI Prompts list screen - shows all internal prompts with add/edit/delete.
 */
@Composable
fun AiPromptsScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onAddPrompt: () -> Unit,
    onEditPrompt: (String) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf<AiPrompt?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Internal Prompts",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Add prompt button
        Button(
            onClick = onAddPrompt,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AiColors.Purple)
        ) {
            Text("Add Prompt")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info text about variables
        Card(
            colors = CardDefaults.cardColors(
                containerColor = AiColors.CardBackgroundAlt
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "Supported variables:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AiColors.Orange
                )
                Text(
                    text = "@MODEL@ @PROVIDER@ @AGENT@ @SWARM@ @NOW@",
                    fontSize = 12.sp,
                    color = AiColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (aiSettings.prompts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No prompts configured.\nAdd a prompt to use AI features like Model Info.",
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
                aiSettings.prompts.sortedBy { it.name.lowercase() }.forEach { prompt ->
                    // Get the agent for this prompt (filter to active providers)
                    val agent = aiSettings.getAgentForPrompt(prompt)
                    val agentVisible = agent != null && aiSettings.isProviderActive(agent.provider)

                    SettingsListItemCard(
                        title = prompt.name,
                        subtitle = if (agentVisible) "Agent: ${agent?.name}" else "Agent not found",
                        subtitleColor = if (agentVisible) AiColors.TextTertiary else AiColors.Red,
                        extraLine = prompt.promptText.take(50) + if (prompt.promptText.length > 50) "..." else "",
                        onClick = { onEditPrompt(prompt.id) },
                        onDelete = { showDeleteDialog = prompt }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { prompt ->
        DeleteConfirmationDialog(
            entityType = "Prompt",
            entityName = prompt.name,
            onConfirm = {
                val newPrompts = aiSettings.prompts.filter { it.id != prompt.id }
                onSave(aiSettings.copy(prompts = newPrompts))
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }
}

/**
 * Prompt edit screen for creating or editing a prompt.
 */
@Composable
fun PromptEditScreen(
    prompt: AiPrompt?,
    aiSettings: AiSettings,
    developerMode: Boolean,
    existingNames: Set<String>,
    onSave: (AiPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val isEditing = prompt != null

    var name by remember { mutableStateOf(prompt?.name ?: "") }
    var selectedAgentId by remember { mutableStateOf(prompt?.agentId ?: "") }
    var promptText by remember { mutableStateOf(prompt?.promptText ?: "") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var showSelectAgent by remember { mutableStateOf(false) }

    // Find selected agent
    val selectedAgent = aiSettings.agents.find { it.id == selectedAgentId }

    // Full-screen overlay for agent selection
    if (showSelectAgent) {
        SelectAgentScreen(
            aiSettings = aiSettings,
            onSelectAgent = { agent ->
                selectedAgentId = agent.id
                showSelectAgent = false
            },
            onBack = { showSelectAgent = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = if (isEditing) "Edit Prompt" else "Add Prompt",
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
            // Prompt name field
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = { Text("Prompt Name") },
                placeholder = { Text("e.g., model_info") },
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

            // Agent selection
            Text(
                text = "Agent",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = AiColors.Purple
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = selectedAgent?.name ?: "",
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text("No agent selected") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AiColors.Purple,
                        unfocusedBorderColor = AiColors.BorderUnfocused,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Button(
                    onClick = { showSelectAgent = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AiColors.Indigo
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Select", fontSize = 12.sp)
                }
            }

            // Prompt text field
            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("Prompt Text") },
                placeholder = { Text("Enter prompt with optional @MODEL@, @PROVIDER@, etc.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                maxLines = 10,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AiColors.Purple,
                    unfocusedBorderColor = AiColors.BorderUnfocused,
                    focusedLabelColor = AiColors.Purple,
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Variable help
            Text(
                text = "Variables: @MODEL@ (model name), @PROVIDER@ (provider name), @AGENT@ (agent name), @SWARM@ (flock name), @NOW@ (current date/time)",
                fontSize = 12.sp,
                color = AiColors.TextTertiary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Save button
        Button(
            onClick = {
                // Validate
                when {
                    name.isBlank() -> {
                        nameError = "Name is required"
                    }
                    !isEditing && name.lowercase() in existingNames.map { it.lowercase() } -> {
                        nameError = "A prompt with this name already exists"
                    }
                    selectedAgentId.isBlank() -> {
                        nameError = "Select an agent"
                    }
                    promptText.isBlank() -> {
                        nameError = "Prompt text is required"
                    }
                    else -> {
                        val newPrompt = AiPrompt(
                            id = prompt?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            agentId = selectedAgentId,
                            promptText = promptText.trim()
                        )
                        onSave(newPrompt)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AiColors.Purple)
        ) {
            Text(if (isEditing) "Save Changes" else "Create Prompt")
        }
    }
}
