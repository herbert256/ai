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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.*
import com.ai.ui.shared.ParametersSelectScreen
import com.ai.ui.shared.SystemPromptSelectScreen
import com.ai.ui.shared.*

@Composable
fun SwarmEditScreen(
    swarm: Swarm?,
    aiSettings: Settings,
    existingNames: Set<String>,
    onSave: (Swarm) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenView: (() -> Unit)? = null,
    /** 🗑 delete this swarm (Setup → Workers → Swarms edit). Null hides it. */
    onDelete: (() -> Unit)? = null
) {
    val isEditing = swarm != null
    // Member rows are always kept sorted by provider id, then the DISPLAYED
    // (short) model name — both case-insensitive — so the list reads in the
    // visual order the user sees, regardless of add order or routing prefixes.
    val memberOrder = compareBy<SwarmMember>(
        { it.provider.id.lowercase() },
        { com.ai.ui.shared.shortModelName(it.model).lowercase() },
        { it.model.lowercase() }
    )

    var resetTick by remember { mutableStateOf(0) }
    var name by remember(resetTick) { mutableStateOf(swarm?.name ?: "") }
    var selectedMembers by remember(resetTick) { mutableStateOf((swarm?.members ?: emptyList()).sortedWith(memberOrder)) }
    var selectedParamsIds by remember(resetTick) { mutableStateOf(swarm?.paramsIds ?: emptyList()) }
    var selectedSystemPromptId by remember(resetTick) { mutableStateOf(swarm?.systemPromptId) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    val dup = com.ai.ui.shared.rememberDuplicateMode(
        isEditingExisting = swarm != null,
        onDuplicate = { name = "$name-copy" }
    )
    val isAddMode = dup.isAddMode
    val effectiveExistingNames = if (isAddMode && swarm != null) {
        existingNames + swarm.name.lowercase()
    } else existingNames

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in effectiveExistingNames -> "Name already exists"
        else -> null
    }

    if (showModelPicker) {
        // Same picker the New Report's "+Model" button uses — full-
        // screen, search + provider filter, with already-selected
        // members dimmed via [alreadyAdded] so the user can't double-
        // add. Single-pick: returns one (provider, model) per
        // open; the user re-opens the picker for each member.
        val already = remember(selectedMembers) {
            selectedMembers.map { it.provider to it.model }.toSet()
        }
        com.ai.ui.other.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            alreadyAdded = already,
            titleText = "Pick model for swarm",
            onConfirm = { (provider, model) ->
                val candidate = SwarmMember(provider, model)
                // Case-insensitive dedupe as a defence-in-depth
                // belt over [alreadyAdded] — covers the rare case
                // where two providers report the same model id in
                // different casing.
                if (selectedMembers.none {
                    it.provider.id == provider.id &&
                        it.model.equals(model, ignoreCase = true)
                }) {
                    selectedMembers = (selectedMembers + candidate).sortedWith(memberOrder)
                }
                showModelPicker = false
            },
            onBack = { showModelPicker = false },
            onNavigateHome = onNavigateHome
        )
        return
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

    val swarmId = remember { java.util.UUID.randomUUID().toString() }
    val current = if (nameError == null && selectedMembers.isNotEmpty())
        Swarm(if (isAddMode) swarmId else swarm!!.id, name.trim(), selectedMembers, selectedParamsIds.distinct(), selectedSystemPromptId) else null
    val back = com.ai.ui.shared.rememberConfirmedBack(current, onBack)
    BackHandler { back() }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "swarm_edit",
            title = if (isAddMode) "Add Swarm" else "Edit Swarm",
            subject = name,
            onBackClick = back,
            onOpenView = if (!isAddMode) onOpenView else null,
            // 👯 duplicate this swarm into a new one (standard copy-on-edit
            // flow), 🗑 delete it — both hidden once the screen flips into
            // copy/add mode, where there is no original to act on.
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
            label = { Text("Swarm name") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, colors = AppColors.outlinedFieldColors(),
            isError = name.isNotBlank() && nameError != null
        )

        // System prompt + parameters now live on the bottom-bar 🎭 / 🌡️ icons.
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${selectedMembers.size} member${if (selectedMembers.size == 1) "" else "s"}",
                fontSize = 13.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { showModelPicker = true },
                colors = AppColors.outlinedButtonColors()
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
                                Text(member.provider.id, fontSize = 13.sp, color = AppColors.InfoAccent,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(com.ai.ui.shared.shortModelName(member.model), fontSize = 12.sp, color = AppColors.TextPrimary,
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
                            }) { Text(com.ai.data.MetadataIconsHolder.current.closeMark, fontSize = 16.sp, color = AppColors.DangerAccent) }
                        }
                    }
                }
            }
        }
    }
}
