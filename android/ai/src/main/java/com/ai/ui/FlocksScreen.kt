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
import com.ai.data.AppService
import java.util.UUID

/**
 * AI Flocks list screen - shows all flocks with add/edit/delete.
 */
@Composable
fun FlocksScreen(
    aiSettings: Settings,
    developerMode: Boolean,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddFlock: () -> Unit,
    onEditFlock: (String) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf<Flock?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TitleBar(
            title = "AI Flocks",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Add flock button
        Button(
            onClick = onAddFlock,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) {
            Text("Add Flock")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (aiSettings.flocks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No flocks configured.\nAdd a flock to group agents together.",
                    color = AppColors.TextTertiary,
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
                val sortedFlocks = remember(aiSettings.flocks) { aiSettings.flocks.sortedBy { it.name.lowercase() } }
                sortedFlocks.forEach { flock ->
                    // Filter to agents with active providers
                    val flockAgents = aiSettings.getAgentsForFlock(flock).filter { agent ->
                        aiSettings.isProviderActive(agent.provider)
                    }
                    SettingsListItemCard(
                        title = flock.name,
                        subtitle = if (flockAgents.isEmpty()) {
                            "No agents"
                        } else {
                            "${flockAgents.size} agent${if (flockAgents.size == 1) "" else "s"}: ${flockAgents.take(3).joinToString(", ") { it.name }}${if (flockAgents.size > 3) "..." else ""}"
                        },
                        onClick = { onEditFlock(flock.id) },
                        onDelete = { showDeleteDialog = flock }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { flock ->
        DeleteConfirmationDialog(
            entityType = "Flock",
            entityName = flock.name,
            onConfirm = {
                val newFlocks = aiSettings.flocks.filter { it.id != flock.id }
                onSave(aiSettings.copy(flocks = newFlocks))
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }
}

/**
 * Flock edit screen for creating or editing a flock.
 */
@Composable
fun FlockEditScreen(
    flock: Flock?,
    aiSettings: Settings,
    developerMode: Boolean,
    existingNames: Set<String>,
    onSave: (Flock) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val isEditing = flock != null
    val context = LocalContext.current

    var name by remember { mutableStateOf(flock?.name ?: "") }
    var selectedAgentIds by remember { mutableStateOf(flock?.agentIds?.toSet() ?: emptySet()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Parameters preset selection (multi-select)
    var selectedParametersIds by remember { mutableStateOf(flock?.paramsIds ?: emptyList()) }

    // System prompt selection (single-select)
    var selectedSystemPromptId by remember { mutableStateOf(flock?.systemPromptId) }

    // Get all configured agents with active providers
    val configuredAgents = aiSettings.getConfiguredAgents().filter { agent ->
        aiSettings.isProviderActive(agent.provider)
    }

    // Filter agents based on search query
    val filteredAgents = if (searchQuery.isBlank()) {
        configuredAgents
    } else {
        configuredAgents.filter { agent ->
            agent.name.contains(searchQuery, ignoreCase = true) ||
            agent.provider.displayName.contains(searchQuery, ignoreCase = true) ||
            aiSettings.getEffectiveModelForAgent(agent).contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TitleBar(
            title = if (isEditing) "Edit Flock" else "Add Flock",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Flock name field + Create/Save + Parameters buttons
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
                label = { Text("Flock Name") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it, color = AppColors.Red) } },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.Purple,
                    unfocusedBorderColor = AppColors.BorderUnfocused,
                    focusedLabelColor = AppColors.Purple,
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
                            nameError = "A flock with this name already exists"
                        }
                        selectedAgentIds.isEmpty() -> {
                            nameError = "Select at least one agent"
                        }
                        else -> {
                            val newFlock = Flock(
                                id = flock?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                agentIds = selectedAgentIds.toList(),
                                paramsIds = selectedParametersIds,
                                systemPromptId = selectedSystemPromptId
                            )
                            onSave(newFlock)
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) {
                Text(if (isEditing) "Save" else "Create", fontSize = 13.sp, maxLines = 1)
            }
        }

        // System prompt + Parameters row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SystemPromptSelector(
                    aiSettings = aiSettings,
                    selectedSystemPromptId = selectedSystemPromptId,
                    onSystemPromptSelected = { id -> selectedSystemPromptId = id }
                )
            }
            Button(
                onClick = { showParamsDialog = true },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) {
                Text(
                    if (selectedParametersIds.isNotEmpty()) "⚙ Parameters" else "Parameters",
                    fontSize = 14.sp, maxLines = 1
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

        if (configuredAgents.isEmpty()) {
            Text(
                text = "No agents configured. Create agents first.",
                color = AppColors.TextTertiary,
                fontSize = 14.sp
            )
        } else {
            // Search box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search agents...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.Purple,
                    unfocusedBorderColor = AppColors.BorderUnfocused,
                    focusedLabelColor = AppColors.Purple,
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Show count of filtered vs total
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "Showing ${filteredAgents.size} of ${configuredAgents.size} agents",
                    fontSize = 12.sp,
                    color = AppColors.TextTertiary
                )
            }

            // Sort selected to top, then by name
            val sortedAgents = filteredAgents
                .sortedWith(compareByDescending<Agent> { it.id in selectedAgentIds }
                    .thenBy { it.name.lowercase() })

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                sortedAgents.forEach { agent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedAgentIds = if (agent.id in selectedAgentIds) {
                                    selectedAgentIds - agent.id
                                } else {
                                    selectedAgentIds + agent.id
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = agent.id in selectedAgentIds,
                            onCheckedChange = { checked ->
                                selectedAgentIds = if (checked) {
                                    selectedAgentIds + agent.id
                                } else {
                                    selectedAgentIds - agent.id
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = agent.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = " ${agent.provider.displayName} ",
                            fontSize = 11.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        val effectiveModel = aiSettings.getEffectiveModelForAgent(agent)
                        val pricing = formatPricingPerMillion(context, agent.provider, effectiveModel)
                        Text(
                            text = pricing.text,
                            color = if (pricing.isDefault) AppColors.SurfaceDark else AppColors.Red,
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            modifier = if (pricing.isDefault) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier
                        )
                    }
                }
            }
        }
    }
}
