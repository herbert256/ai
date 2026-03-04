package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.*
import com.ai.ui.shared.*

@Composable
fun PromptsScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddPrompt: () -> Unit,
    onEditPrompt: (String) -> Unit
) {
    CrudListScreen(
        title = "Internal Prompts",
        items = aiSettings.prompts,
        addLabel = "Add Prompt",
        emptyMessage = "No internal prompts configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { prompt ->
            val agent = aiSettings.getAgentById(prompt.agentId)
            "Agent: ${agent?.name ?: "Not found"}"
        },
        itemExtraLine = { it.promptText.take(50) },
        onAdd = onAddPrompt,
        onEdit = { onEditPrompt(it.id) },
        onDelete = { prompt -> onSave(aiSettings.copy(prompts = aiSettings.prompts.filter { it.id != prompt.id })) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Prompt",
        deleteEntityName = { it.name }
    )
}

@Composable
fun PromptEditScreen(
    prompt: Prompt?,
    aiSettings: Settings,
    existingNames: Set<String>,
    onSave: (Prompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = prompt != null

    var name by remember { mutableStateOf(prompt?.name ?: "") }
    var promptText by remember { mutableStateOf(prompt?.promptText ?: "") }
    var selectedAgentId by remember { mutableStateOf(prompt?.agentId ?: "") }
    var showSelectAgent by remember { mutableStateOf(false) }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    // Full-screen overlay for agent selection
    if (showSelectAgent) {
        SelectAgentScreen(
            aiSettings = aiSettings,
            onSelectAgent = { selectedAgentId = it.id; showSelectAgent = false },
            onBack = { showSelectAgent = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = if (isEditing) "Edit Prompt" else "Add Prompt", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Prompt name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.Red) } } else null
            )

            val agentName = if (selectedAgentId.isNotBlank()) aiSettings.getAgentById(selectedAgentId)?.name ?: "Not found" else "None"
            OutlinedButton(onClick = { showSelectAgent = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Agent: $agentName", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            OutlinedTextField(
                value = promptText, onValueChange = { promptText = it },
                label = { Text("Prompt text") }, modifier = Modifier.fillMaxWidth(),
                minLines = 8, maxLines = 15, colors = AppColors.outlinedFieldColors()
            )

            Text("Variables: @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@", fontSize = 11.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val id = prompt?.id ?: java.util.UUID.randomUUID().toString()
                onSave(Prompt(id, name.trim(), selectedAgentId, promptText))
            },
            enabled = nameError == null && selectedAgentId.isNotBlank() && promptText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isEditing) "Save" else "Create", maxLines = 1, softWrap = false) }
    }
}
