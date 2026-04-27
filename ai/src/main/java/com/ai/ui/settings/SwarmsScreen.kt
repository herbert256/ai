package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    existingNames: Set<String>,
    onSave: (Swarm) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = swarm != null

    var name by remember { mutableStateOf(swarm?.name ?: "") }
    var selectedMembers by remember { mutableStateOf((swarm?.members ?: emptyList()).toSet()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedParamsIds by remember { mutableStateOf(swarm?.paramsIds ?: emptyList()) }
    var selectedSystemPromptId by remember { mutableStateOf(swarm?.systemPromptId) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    // Build all available members from active providers
    val allMembers = remember(aiSettings) {
        aiSettings.getActiveServices().flatMap { provider ->
            val models = aiSettings.getModels(provider)
            if (models.isNotEmpty()) models.map { SwarmMember(provider, it) }
            else listOf(SwarmMember(provider, aiSettings.getModel(provider)))
        }.sortedWith(compareBy({ it.provider.displayName }, { it.model }))
    }
    val filteredMembers = remember(searchQuery, allMembers) {
        if (searchQuery.isBlank()) allMembers
        else allMembers.filter { it.provider.displayName.contains(searchQuery, ignoreCase = true) || it.model.contains(searchQuery, ignoreCase = true) }
    }
    val sortedMembers = remember(filteredMembers, selectedMembers) {
        filteredMembers.sortedWith(compareByDescending<SwarmMember> { it in selectedMembers }.thenBy { it.provider.displayName }.thenBy { it.model })
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
                    onSave(Swarm(id, name.trim(), selectedMembers.toList(), selectedParamsIds, selectedSystemPromptId))
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

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("Search models...") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, colors = AppColors.outlinedFieldColors()
        )
        Text("${selectedMembers.size} selected of ${allMembers.size}", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sortedMembers.forEach { member ->
                val isChecked = member in selectedMembers
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedMembers = if (isChecked) selectedMembers - member else selectedMembers + member
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isChecked, onCheckedChange = {
                        selectedMembers = if (isChecked) selectedMembers - member else selectedMembers + member
                    })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(member.provider.displayName, fontSize = 13.sp, color = AppColors.Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(member.model, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(member.provider, member.model))
                            com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(member.provider, member.model))
                        }
                    }
                }
            }
        }
    }
}
