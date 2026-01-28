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
 * AI Swarms list screen - shows all swarms with add/edit/delete.
 */
@Composable
fun AiSwarmsScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
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
                    text = "No swarms configured.\nAdd a swarm to group agents together.",
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
                aiSettings.swarms.sortedBy { it.name.lowercase() }.forEach { swarm ->
                    // Filter DUMMY agents when not in developer mode
                    val swarmAgents = aiSettings.getAgentsForSwarm(swarm).filter { agent ->
                        developerMode || agent.provider != AiService.DUMMY
                    }
                    SwarmListItem(
                        swarm = swarm,
                        agents = swarmAgents,
                        onClick = { onEditSwarm(swarm.id) },
                        onDelete = { showDeleteDialog = swarm }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { swarm ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Swarm") },
            text = { Text("Are you sure you want to delete \"${swarm.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newSwarms = aiSettings.swarms.filter { it.id != swarm.id }
                        onSave(aiSettings.copy(swarms = newSwarms))
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
 * List item for a swarm showing name and agent count.
 */
@Composable
private fun SwarmListItem(
    swarm: AiSwarm,
    agents: List<AiAgent>,
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
                    text = swarm.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (agents.isEmpty()) {
                        "No agents"
                    } else {
                        "${agents.size} agent${if (agents.size == 1) "" else "s"}: ${agents.take(3).joinToString(", ") { it.name }}${if (agents.size > 3) "..." else ""}"
                    },
                    fontSize = 14.sp,
                    color = Color(0xFF888888)
                )
            }
            IconButton(onClick = onDelete) {
                Text("X", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
            }
        }
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
    onSave: (AiSwarm) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val isEditing = swarm != null

    var name by remember { mutableStateOf(swarm?.name ?: "") }
    var selectedAgentIds by remember { mutableStateOf(swarm?.agentIds?.toSet() ?: emptySet()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Parameters preset selection
    var selectedParamsId by remember { mutableStateOf(swarm?.paramsId) }
    var selectedParamsName by remember { mutableStateOf(swarm?.paramsId?.let { aiSettings.getParamsById(it)?.name } ?: "") }

    // Get all configured agents (filter DUMMY when not in developer mode)
    val configuredAgents = aiSettings.getConfiguredAgents().filter { agent ->
        developerMode || agent.provider != AiService.DUMMY
    }

    // Filter agents based on search query
    val filteredAgents = if (searchQuery.isBlank()) {
        configuredAgents
    } else {
        configuredAgents.filter { agent ->
            agent.name.contains(searchQuery, ignoreCase = true) ||
            agent.provider.displayName.contains(searchQuery, ignoreCase = true) ||
            agent.model.contains(searchQuery, ignoreCase = true)
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

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Swarm name field
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = { Text("Swarm Name") },
                placeholder = { Text("Enter a name for this swarm") },
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

            // Parameters preset selection
            ParametersSelector(
                aiSettings = aiSettings,
                selectedParamsId = selectedParamsId,
                selectedParamsName = selectedParamsName,
                onParamsSelected = { id, name ->
                    selectedParamsId = id
                    selectedParamsName = name
                }
            )

            // Agent selection section
            Text(
                text = "Select Agents",
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
                // Search box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search agents") },
                    placeholder = { Text("Filter by name, provider, or model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF8B5CF6),
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedLabelColor = Color(0xFF8B5CF6),
                        unfocusedLabelColor = Color.Gray,
                        cursorColor = Color.White
                    )
                )

                // Select all / Select none buttons (operate on filtered agents)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { selectedAgentIds = selectedAgentIds + filteredAgents.map { it.id }.toSet() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Select all")
                    }
                    OutlinedButton(
                        onClick = { selectedAgentIds = selectedAgentIds - filteredAgents.map { it.id }.toSet() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Select none")
                    }
                }

                // Show count of filtered vs total
                if (searchQuery.isNotBlank()) {
                    Text(
                        text = "Showing ${filteredAgents.size} of ${configuredAgents.size} agents",
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Agent checkboxes (filtered)
                filteredAgents.sortedBy { it.name.lowercase() }.forEach { agent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedAgentIds = if (agent.id in selectedAgentIds) {
                                    selectedAgentIds - agent.id
                                } else {
                                    selectedAgentIds + agent.id
                                }
                            }
                            .padding(vertical = 8.dp),
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
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = agent.name,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = "${agent.provider.displayName} - ${agent.model}",
                                fontSize = 12.sp,
                                color = Color(0xFF888888)
                            )
                        }
                    }
                }
            }
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
                    name in existingNames -> {
                        nameError = "A swarm with this name already exists"
                    }
                    selectedAgentIds.isEmpty() -> {
                        nameError = "Select at least one agent"
                    }
                    else -> {
                        val newSwarm = AiSwarm(
                            id = swarm?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            agentIds = selectedAgentIds.toList(),
                            paramsId = selectedParamsId
                        )
                        onSave(newSwarm)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            Text(if (isEditing) "Save Changes" else "Create Swarm")
        }
    }
}
