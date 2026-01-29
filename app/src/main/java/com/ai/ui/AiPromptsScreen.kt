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
            title = "AI Prompts",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Add prompt button
        Button(
            onClick = onAddPrompt,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            Text("Add Prompt")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info text about variables
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF2A3A4A)
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
                    color = Color(0xFFFF9800)
                )
                Text(
                    text = "@MODEL@ @PROVIDER@ @AGENT@ @SWARM@ @NOW@",
                    fontSize = 12.sp,
                    color = Color(0xFFAAAAAA)
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
                    color = Color(0xFF888888),
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
                    // Get the agent for this prompt (filter DUMMY when not in developer mode)
                    val agent = aiSettings.getAgentForPrompt(prompt)
                    val agentVisible = agent != null && (developerMode || agent.provider != AiService.DUMMY)

                    PromptListItem(
                        prompt = prompt,
                        agentName = if (agentVisible) agent?.name else null,
                        onClick = { onEditPrompt(prompt.id) },
                        onDelete = { showDeleteDialog = prompt }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { prompt ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Prompt") },
            text = { Text("Are you sure you want to delete \"${prompt.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newPrompts = aiSettings.prompts.filter { it.id != prompt.id }
                        onSave(aiSettings.copy(prompts = newPrompts))
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFFF6B6B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * List item for a prompt showing name and agent.
 */
@Composable
private fun PromptListItem(
    prompt: AiPrompt,
    agentName: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A3A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = prompt.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (agentName != null) "Agent: $agentName" else "Agent not found",
                    fontSize = 14.sp,
                    color = if (agentName != null) Color(0xFF888888) else Color(0xFFFF6B6B)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = prompt.promptText.take(50) + if (prompt.promptText.length > 50) "..." else "",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            }
            IconButton(onClick = onDelete) {
                Text("X", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Prompt edit screen for creating or editing a prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var agentDropdownExpanded by remember { mutableStateOf(false) }

    // Get all configured agents (filter DUMMY when not in developer mode)
    val configuredAgents = aiSettings.getConfiguredAgents().filter { agent ->
        developerMode || agent.provider != AiService.DUMMY
    }.sortedBy { it.name.lowercase() }

    // Find selected agent
    val selectedAgent = configuredAgents.find { it.id == selectedAgentId }

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
                supportingText = nameError?.let { { Text(it, color = Color(0xFFFF6B6B)) } },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedLabelColor = Color(0xFF8B5CF6),
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Agent selection dropdown
            Text(
                text = "Select Agent",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Color(0xFF8B5CF6)
            )

            if (configuredAgents.isEmpty()) {
                Text(
                    text = "No agents configured. Create agents first.",
                    color = Color(0xFF888888),
                    fontSize = 14.sp
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = agentDropdownExpanded,
                    onExpandedChange = { agentDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedAgent?.name ?: "Select an agent",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = agentDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B5CF6),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    ExposedDropdownMenu(
                        expanded = agentDropdownExpanded,
                        onDismissRequest = { agentDropdownExpanded = false }
                    ) {
                        configuredAgents.forEach { agent ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(agent.name, color = Color.White)
                                        Text(
                                            "${agent.provider.displayName} - ${agent.model.ifBlank { agent.provider.defaultModel }}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF888888)
                                        )
                                    }
                                },
                                onClick = {
                                    selectedAgentId = agent.id
                                    agentDropdownExpanded = false
                                }
                            )
                        }
                    }
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
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedLabelColor = Color(0xFF8B5CF6),
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Variable help
            Text(
                text = "Variables: @MODEL@ (model name), @PROVIDER@ (provider name), @AGENT@ (agent name), @SWARM@ (flock name), @NOW@ (current date/time)",
                fontSize = 12.sp,
                color = Color(0xFF888888)
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            Text(if (isEditing) "Save Changes" else "Create Prompt")
        }
    }
}
