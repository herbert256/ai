package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.*
import com.ai.ui.chat.ParametersSelectorDialog
import com.ai.ui.chat.SystemPromptSelectorDialog
import com.ai.ui.shared.*

@Composable
fun SwarmsScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddSwarm: () -> Unit,
    onEditSwarm: (String) -> Unit
) {
    CrudListScreen(
        title = "Swarms",
        items = aiSettings.swarms,
        addLabel = "Add Swarm",
        emptyMessage = "No swarms configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { swarm ->
            val members = swarm.members.filter { aiSettings.isProviderActive(it.provider) }
            "${members.size} members: ${members.joinToString(", ") { "${it.provider.displayName}/${it.model}" }}"
        },
        onAdd = onAddSwarm,
        onEdit = { onEditSwarm(it.id) },
        onDelete = { swarm -> onSave(aiSettings.copy(swarms = aiSettings.swarms.filter { it.id != swarm.id })) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Swarm",
        deleteEntityName = { it.name },
        itemKey = { it.id }
    )
}

@Composable
fun SwarmEditScreen(
    swarm: Swarm?,
    aiSettings: Settings,
    loadingModelsFor: Set<AppService>,
    existingNames: Set<String>,
    onSave: (Swarm) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = swarm != null

    var name by remember { mutableStateOf(swarm?.name ?: "") }
    var selectedMembers by remember { mutableStateOf(swarm?.members ?: emptyList()) }
    var selectedParamsIds by remember { mutableStateOf(swarm?.paramsIds ?: emptyList()) }
    var selectedSystemPromptId by remember { mutableStateOf(swarm?.systemPromptId) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    if (showModelPicker) {
        com.ai.ui.models.ModelSearchScreen(
            aiSettings = aiSettings, loadingModelsFor = loadingModelsFor,
            onBackToAiSetup = { showModelPicker = false }, onBackToHome = onNavigateHome,
            onNavigateToModelInfo = { _, _ -> },
            onPickModel = { provider, model ->
                val candidate = SwarmMember(provider, model)
                if (selectedMembers.none { it.provider.id == provider.id && it.model == model }) {
                    selectedMembers = selectedMembers + candidate
                }
                showModelPicker = false
            }
        )
        return
    }

    if (showParamsDialog) {
        ParametersSelectorDialog(aiSettings = aiSettings, selectedIds = selectedParamsIds,
            onConfirm = { selectedParamsIds = it; showParamsDialog = false }, onDismiss = { showParamsDialog = false })
    }
    if (showSystemPromptDialog) {
        SystemPromptSelectorDialog(aiSettings = aiSettings, selectedId = selectedSystemPromptId,
            onSelect = { selectedSystemPromptId = it; showSystemPromptDialog = false }, onDismiss = { showSystemPromptDialog = false })
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = if (isEditing) "Edit Swarm" else "Add Swarm", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Swarm name") }, modifier = Modifier.weight(1f),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null
            )
            Button(
                onClick = {
                    val id = swarm?.id ?: java.util.UUID.randomUUID().toString()
                    onSave(Swarm(id, name.trim(), selectedMembers, selectedParamsIds, selectedSystemPromptId))
                },
                enabled = nameError == null && selectedMembers.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text(if (isEditing) "Save" else "Create", maxLines = 1, softWrap = false) }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val spName = selectedSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.name }
            OutlinedButton(onClick = { showSystemPromptDialog = true }, modifier = Modifier.weight(1f),
                colors = if (spName != null) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
            ) { Text(spName ?: "System Prompt", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val pNames = selectedParamsIds.mapNotNull { aiSettings.getParametersById(it)?.name }
            OutlinedButton(onClick = { showParamsDialog = true }, modifier = Modifier.weight(1f),
                colors = if (pNames.isNotEmpty()) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
            ) { Text(if (pNames.isNotEmpty()) pNames.joinToString(", ") else "Parameters", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${selectedMembers.size} member${if (selectedMembers.size == 1) "" else "s"}",
                fontSize = 13.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
            Button(
                onClick = { showModelPicker = true },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
            ) { Text("+ Add model", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (selectedMembers.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No models in this swarm yet. Tap “+ Add model” to pick one.",
                    fontSize = 13.sp, color = AppColors.TextTertiary)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(selectedMembers, key = { "${it.provider.id}:${it.model}" }) { member ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(member.provider.displayName, fontSize = 13.sp, color = AppColors.Blue,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(member.model, fontSize = 12.sp, color = Color.White,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(member.provider, member.model))
                                    com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(member.provider, member.model))
                                    com.ai.ui.shared.ReasoningBadge(aiSettings.isReasoningCapable(member.provider, member.model))
                                }
                            }
                            IconButton(onClick = {
                                selectedMembers = selectedMembers.filterNot {
                                    it.provider.id == member.provider.id && it.model == member.model
                                }
                            }) { Text("✕", fontSize = 16.sp, color = AppColors.Red) }
                        }
                    }
                }
            }
        }
    }
}
