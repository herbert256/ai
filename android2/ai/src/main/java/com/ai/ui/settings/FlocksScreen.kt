package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.PricingCache
import com.ai.model.*
import com.ai.ui.chat.ParametersSelectorDialog
import com.ai.ui.chat.SystemPromptSelectorDialog
import com.ai.ui.shared.*

@Composable
fun FlocksScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddFlock: () -> Unit,
    onEditFlock: (String) -> Unit
) {
    CrudListScreen(
        title = "Flocks",
        items = aiSettings.flocks,
        addLabel = "Add Flock",
        emptyMessage = "No flocks configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { flock ->
            val agents = aiSettings.getAgentsForFlock(flock).filter { aiSettings.isProviderActive(it.provider) }
            "${agents.size} agents: ${agents.joinToString(", ") { it.name }}"
        },
        onAdd = onAddFlock,
        onEdit = { onEditFlock(it.id) },
        onDelete = { flock -> onSave(aiSettings.copy(flocks = aiSettings.flocks.filter { it.id != flock.id })) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Flock",
        deleteEntityName = { it.name }
    )
}

@Composable
fun FlockEditScreen(
    flock: Flock?,
    aiSettings: Settings,
    existingNames: Set<String>,
    onSave: (Flock) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val isEditing = flock != null

    var name by remember { mutableStateOf(flock?.name ?: "") }
    var selectedAgentIds by remember { mutableStateOf((flock?.agentIds ?: emptyList()).toSet()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedParamsIds by remember { mutableStateOf(flock?.paramsIds ?: emptyList()) }
    var selectedSystemPromptId by remember { mutableStateOf(flock?.systemPromptId) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    val availableAgents = remember(aiSettings.agents) {
        aiSettings.agents.filter { aiSettings.isProviderActive(it.provider) }
    }
    val filteredAgents = remember(searchQuery, availableAgents) {
        if (searchQuery.isBlank()) availableAgents
        else availableAgents.filter { it.name.contains(searchQuery, ignoreCase = true) || it.provider.displayName.contains(searchQuery, ignoreCase = true) }
    }
    val sortedAgents = remember(filteredAgents, selectedAgentIds) {
        filteredAgents.sortedWith(compareByDescending<Agent> { it.id in selectedAgentIds }.thenBy { it.name.lowercase() })
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
        TitleBar(title = if (isEditing) "Edit Flock" else "Add Flock", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Flock name") }, modifier = Modifier.weight(1f),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null
            )
            Button(
                onClick = {
                    val id = flock?.id ?: java.util.UUID.randomUUID().toString()
                    onSave(Flock(id, name.trim(), selectedAgentIds.toList(), selectedParamsIds, selectedSystemPromptId))
                },
                enabled = nameError == null && selectedAgentIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text(if (isEditing) "Save" else "Create") }
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
            placeholder = { Text("Search agents...") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, colors = AppColors.outlinedFieldColors()
        )
        Text("${selectedAgentIds.size} selected of ${availableAgents.size}", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sortedAgents.forEach { agent ->
                val isChecked = agent.id in selectedAgentIds
                val effectiveModel = aiSettings.getEffectiveModelForAgent(agent)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedAgentIds = if (isChecked) selectedAgentIds - agent.id else selectedAgentIds + agent.id
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isChecked, onCheckedChange = {
                        selectedAgentIds = if (isChecked) selectedAgentIds - agent.id else selectedAgentIds + agent.id
                    })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(agent.name, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${agent.provider.displayName} \u00B7 $effectiveModel", fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
