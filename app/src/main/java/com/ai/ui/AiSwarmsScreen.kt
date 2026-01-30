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
                    text = "No swarms configured.\nAdd a swarm to group provider/model combinations.",
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
                    // Filter to members with active providers
                    val swarmMembers = swarm.members.filter { member ->
                        aiSettings.isProviderActive(member.provider, developerMode)
                    }
                    SwarmListItem(
                        swarm = swarm,
                        members = swarmMembers,
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
 * List item for a swarm showing name and member count.
 */
@Composable
private fun SwarmListItem(
    swarm: AiSwarm,
    members: List<AiSwarmMember>,
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
                    text = if (members.isEmpty()) {
                        "No members"
                    } else {
                        "${members.size} member${if (members.size == 1) "" else "s"}: ${members.take(3).joinToString(", ") { "${it.provider.displayName}/${it.model.take(20)}" }}${if (members.size > 3) "..." else ""}"
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
    availableModels: Map<AiService, List<String>>,
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

    // Get all available provider/model combinations, sorted by provider name
    val allProviderModels = remember(aiSettings, availableModels, developerMode) {
        val result = mutableListOf<AiSwarmMember>()
        aiSettings.getActiveServices(developerMode).sortedBy { it.displayName.lowercase() }.forEach { provider ->
            // Combine API models and manual models, deduplicated
            val apiModels = availableModels[provider] ?: emptyList()
            val manualModels = aiSettings.getManualModels(provider)
            val combined = (apiModels + manualModels).distinct().ifEmpty { listOf(aiSettings.getModel(provider)) }
            combined.forEach { model ->
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
                supportingText = nameError?.let { { Text(it, color = Color(0xFFFF6B6B)) } },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedLabelColor = Color(0xFF8B5CF6),
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
                                paramsIds = selectedParametersIds
                            )
                            onSave(newSwarm)
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text(if (isEditing) "Save" else "Create", fontSize = 13.sp, maxLines = 1)
            }
            Button(
                onClick = { showParamsDialog = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text(
                    if (selectedParametersIds.isNotEmpty()) "\uD83D\uDCCE Parameters" else "Parameters",
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

        if (allProviderModels.isEmpty()) {
            Text(
                text = "No providers configured with API keys. Configure providers first.",
                color = Color(0xFF888888),
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
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedLabelColor = Color(0xFF8B5CF6),
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Show count of filtered vs total
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "Showing ${filteredProviderModels.size} of ${allProviderModels.size} models",
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
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
                            color = Color(0xFFAAAAAA),
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
                            color = if (pricing.isDefault) Color(0xFF666666) else Color(0xFFFF6B6B),
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
