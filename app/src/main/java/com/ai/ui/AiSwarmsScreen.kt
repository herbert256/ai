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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import com.ai.data.AiService
import java.util.UUID

/**
 * AI Swarms list screen - shows all swarms with add/edit/delete.
 */
@Composable
fun AiSwarmsScreen(
    aiSettings: AiSettings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onAddSwarm: () -> Unit,
    onEditSwarm: (String) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf<AiSwarm?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "AI Swarms",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Add swarm button
        Button(
            onClick = onAddSwarm,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AiColors.Purple)
        ) {
            Text("Add Swarm")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (aiSettings.swarms.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No swarms configured.\nAdd a swarm to group provider/model combinations.",
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
                aiSettings.swarms.sortedBy { it.name.lowercase() }.forEach { swarm ->
                    // Filter to members with active providers
                    val swarmMembers = swarm.members.filter { member ->
                        aiSettings.isProviderActive(member.provider)
                    }
                    SettingsListItemCard(
                        title = swarm.name,
                        subtitle = if (swarmMembers.isEmpty()) {
                            "No members"
                        } else {
                            "${swarmMembers.size} member${if (swarmMembers.size == 1) "" else "s"}: ${swarmMembers.take(3).joinToString(", ") { "${it.provider.displayName}/${it.model.take(20)}" }}${if (swarmMembers.size > 3) "..." else ""}"
                        },
                        onClick = { onEditSwarm(swarm.id) },
                        onDelete = { showDeleteDialog = swarm }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { swarm ->
        DeleteConfirmationDialog(
            entityType = "Swarm",
            entityName = swarm.name,
            onConfirm = {
                val newSwarms = aiSettings.swarms.filter { it.id != swarm.id }
                onSave(aiSettings.copy(swarms = newSwarms))
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }
}

/**
 * Swarm edit screen for creating or editing a swarm.
 */
@Composable
fun SwarmEditScreen(
    swarm: AiSwarm?,
    aiSettings: AiSettings,
    developerMode: Boolean,
    existingNames: Set<String>,
    availableModels: Map<AiService, List<String>> = emptyMap(),
    onSave: (AiSwarm) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val isEditing = swarm != null
    val context = LocalContext.current

    var name by remember { mutableStateOf(swarm?.name ?: "") }
    var selectedMembers by remember { mutableStateOf(swarm?.members?.toSet() ?: emptySet()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Parameters preset selection (multi-select)
    var selectedParametersIds by remember { mutableStateOf(swarm?.paramsIds ?: emptyList()) }

    // System prompt selection (single-select)
    var selectedSystemPromptId by remember { mutableStateOf(swarm?.systemPromptId) }

    // Get all available provider/model combinations, sorted by provider name
    val allProviderModels = remember(aiSettings, availableModels, developerMode) {
        val result = mutableListOf<AiSwarmMember>()
        aiSettings.getActiveServices().sortedBy { it.displayName.lowercase() }.forEach { provider ->
            val models = aiSettings.getModels(provider).ifEmpty { listOf(aiSettings.getModel(provider)) }
            models.forEach { model ->
                result.add(AiSwarmMember(provider, model))
            }
        }
        result
    }

    // Filter based on search query
    val filteredProviderModels = if (searchQuery.isBlank()) {
        allProviderModels
    } else {
        allProviderModels.filter { member ->
            member.provider.displayName.contains(searchQuery, ignoreCase = true) ||
            member.model.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = if (isEditing) "Edit Swarm" else "Add Swarm",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Swarm name field + Create/Save + Parameters buttons
        var showParamsDialog by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = { Text("Swarm Name") },
                modifier = Modifier.weight(1f),
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
            Button(
                onClick = {
                    when {
                        name.isBlank() -> {
                            nameError = "Name is required"
                        }
                        name in existingNames -> {
                            nameError = "A swarm with this name already exists"
                        }
                        selectedMembers.isEmpty() -> {
                            nameError = "Select at least one provider/model"
                        }
                        else -> {
                            val newSwarm = AiSwarm(
                                id = swarm?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                members = selectedMembers.toList(),
                                paramsIds = selectedParametersIds,
                                systemPromptId = selectedSystemPromptId
                            )
                            onSave(newSwarm)
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green)
            ) {
                Text(if (isEditing) "Save" else "Create", fontSize = 13.sp, maxLines = 1)
            }
            Button(
                onClick = { showParamsDialog = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green)
            ) {
                Text(
                    if (selectedParametersIds.isNotEmpty()) "⚙ Parameters" else "Parameters",
                    fontSize = 13.sp, maxLines = 1
                )
            }
        }
        if (showParamsDialog) {
            ParametersSelectorDialog(
                aiSettings = aiSettings,
                selectedParametersIds = selectedParametersIds,
                onParamsSelected = { ids ->
                    selectedParametersIds = ids
                    showParamsDialog = false
                },
                onDismiss = { showParamsDialog = false }
            )
        }

        // System prompt selector
        SystemPromptSelector(
            aiSettings = aiSettings,
            selectedSystemPromptId = selectedSystemPromptId,
            onSystemPromptSelected = { id -> selectedSystemPromptId = id }
        )

        if (allProviderModels.isEmpty()) {
            Text(
                text = "No providers configured with API keys. Configure providers first.",
                color = AiColors.TextTertiary,
                fontSize = 14.sp
            )
        } else {
            // Search box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search models...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AiColors.Purple,
                    unfocusedBorderColor = AiColors.BorderUnfocused,
                    focusedLabelColor = AiColors.Purple,
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Show count of filtered vs total
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "Showing ${filteredProviderModels.size} of ${allProviderModels.size} models",
                    fontSize = 12.sp,
                    color = AiColors.TextTertiary
                )
            }

            // Sort selected to top, then by provider/model
            val sortedModels = filteredProviderModels
                .sortedWith(compareByDescending<AiSwarmMember> { it in selectedMembers }
                    .thenBy { it.provider.displayName.lowercase() }
                    .thenBy { it.model.lowercase() })

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                sortedModels.forEach { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedMembers = if (member in selectedMembers) {
                                    selectedMembers - member
                                } else {
                                    selectedMembers + member
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = member in selectedMembers,
                            onCheckedChange = { checked ->
                                selectedMembers = if (checked) {
                                    selectedMembers + member
                                } else {
                                    selectedMembers - member
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "${member.provider.displayName} ",
                            fontSize = 11.sp,
                            color = AiColors.TextSecondary,
                            maxLines = 1
                        )
                        Text(
                            text = member.model,
                            fontSize = 13.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        val pricing = formatPricingPerMillion(context, member.provider, member.model)
                        Text(
                            text = pricing.text,
                            color = if (pricing.isDefault) Color(0xFF2A2A2A) else AiColors.Red,
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            modifier = if (pricing.isDefault) Modifier.background(AiColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier
                        )
                    }
                }
            }
        }
    }
}
