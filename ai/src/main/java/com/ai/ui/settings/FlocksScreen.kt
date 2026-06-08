package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.PricingCache
import com.ai.model.*
import com.ai.ui.shared.ParametersSelectScreen
import com.ai.ui.shared.SystemPromptSelectScreen
import com.ai.ui.shared.*

@Composable
fun FlockEditScreen(
    flock: Flock?,
    aiSettings: Settings,
    existingNames: Set<String>,
    onSave: (Flock) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenView: (() -> Unit)? = null,
    /** 🗑 delete this flock (Setup → Workers → Flocks edit). Null hides it. */
    onDelete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isEditing = flock != null

    var resetTick by remember { mutableStateOf(0) }
    var name by remember(resetTick) { mutableStateOf(flock?.name ?: "") }
    val knownAgentIds = remember(aiSettings.agents) { aiSettings.agents.mapTo(LinkedHashSet()) { it.id } }
    // LinkedHashSet so membership keeps insertion order (parity with the
    // Swarm path's ordered member list) — a plain Set yielded arbitrary
    // save order.
    var selectedAgentIds by remember(resetTick, knownAgentIds) {
        mutableStateOf<Set<String>>(LinkedHashSet(flock?.agentIds.orEmpty().filter { it in knownAgentIds }))
    }
    val selectedKnownAgentIds = remember(selectedAgentIds, knownAgentIds) {
        LinkedHashSet(selectedAgentIds.filter { it in knownAgentIds })
    }
    var searchQuery by remember { mutableStateOf("") }
    var selectedParamsIds by remember(resetTick) { mutableStateOf(flock?.paramsIds ?: emptyList()) }
    var selectedSystemPromptId by remember(resetTick) { mutableStateOf(flock?.systemPromptId) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }

    val dup = com.ai.ui.shared.rememberDuplicateMode(
        isEditingExisting = flock != null,
        onDuplicate = { name = "$name-copy" }
    )
    val isAddMode = dup.isAddMode
    val effectiveExistingNames = if (isAddMode && flock != null) {
        existingNames + flock.name.lowercase()
    } else existingNames

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in effectiveExistingNames -> "Name already exists"
        else -> null
    }

    // Show active agents PLUS any already-selected agent whose provider
    // later went inactive — otherwise a hidden member is silently kept on
    // save and the "N selected of M" count can exceed M (Bug 3).
    val availableAgents = remember(aiSettings.agents, selectedKnownAgentIds) {
        aiSettings.agents.filter { aiSettings.isProviderActive(it.provider) || it.id in selectedKnownAgentIds }
    }
    val filteredAgents = remember(searchQuery, availableAgents) {
        if (searchQuery.isBlank()) availableAgents
        else availableAgents.filter { it.name.contains(searchQuery, ignoreCase = true) || it.provider.id.contains(searchQuery, ignoreCase = true) }
    }
    val sortedAgents = remember(filteredAgents, selectedKnownAgentIds) {
        filteredAgents.sortedWith(compareByDescending<Agent> { it.id in selectedKnownAgentIds }.thenBy { it.name.lowercase() })
    }

    if (showParamsDialog) {
        ParametersSelectScreen(aiSettings = aiSettings, selectedIds = selectedParamsIds,
            onConfirm = { selectedParamsIds = it },
            onBack = { showParamsDialog = false }, onNavigateHome = onNavigateHome)
        return
    }
    if (showSystemPromptDialog) {
        SystemPromptSelectScreen(aiSettings = aiSettings, selectedId = selectedSystemPromptId,
            onSelect = { selectedSystemPromptId = it },
            onBack = { showSystemPromptDialog = false }, onNavigateHome = onNavigateHome)
        return
    }

    val flockId = remember { java.util.UUID.randomUUID().toString() }
    val current = if (nameError == null && selectedKnownAgentIds.isNotEmpty())
        Flock(if (isAddMode) flockId else flock!!.id, name.trim(), selectedKnownAgentIds.toList(), selectedParamsIds.distinct(), selectedSystemPromptId) else null
    val back = com.ai.ui.shared.rememberConfirmedBack(current, onBack)
    BackHandler { back() }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "flock_edit",
            title = if (isAddMode) "Add Flock" else "Edit Flock",
            subject = name,
            onBackClick = back,
            onOpenView = if (!isAddMode) onOpenView else null,
            // 👯 duplicate into a new flock (copy-on-edit flow), 🗑 delete it —
            // both hidden once the screen flips into copy/add mode.
            onCopyReport = dup.copyTrigger,
            onDelete = if (isAddMode) null else onDelete,
            onClear = { resetTick++ },
            onParameters = { showParamsDialog = true },
            onSystemPrompt = { showSystemPromptDialog = true }
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onSave(current!!); onBack() },
            enabled = current != null,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text(if (isAddMode) "Create" else "Save", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Flock name") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, colors = AppColors.outlinedFieldColors(),
            isError = name.isNotBlank() && nameError != null
        )

        // System prompt + parameters now live on the bottom-bar 🎭 / 🌡️ icons.
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("Search agents...") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, colors = AppColors.outlinedFieldColors()
        )
        Text("${selectedKnownAgentIds.size} selected of ${availableAgents.size}", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))

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
                    val inactive = !aiSettings.isProviderActive(agent.provider)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (inactive) "${agent.name} (inactive)" else agent.name,
                            fontSize = 14.sp,
                            color = if (inactive) AppColors.TextTertiary else AppColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(com.ai.ui.shared.modelLabel(agent.provider.id, effectiveModel),
                            fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
