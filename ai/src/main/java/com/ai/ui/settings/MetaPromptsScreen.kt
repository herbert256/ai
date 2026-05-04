package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.*
import com.ai.ui.shared.*

/** Allowed values for [MetaPrompt.type]. Order is the display order
 *  in the FilterChip row on the edit screen. `chat` is the default
 *  for new entries. */
private val META_TYPES = listOf("chat", "rerank", "moderation")

@Composable
fun MetaPromptsListScreen(
    aiSettings: Settings,
    onBackToPromptsSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddMetaPrompt: () -> Unit,
    onEditMetaPrompt: (String) -> Unit
) {
    CrudListScreen(
        title = "Report Meta Prompts",
        items = aiSettings.metaPrompts,
        addLabel = "Add Meta Prompt",
        emptyMessage = "No Meta prompts configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { mp ->
            val refTag = if (mp.type == "chat" && mp.reference) " · ref" else ""
            val preview = mp.text.lineSequence().firstOrNull().orEmpty().take(80)
            "${mp.type}$refTag${if (preview.isBlank()) "" else " — $preview"}"
        },
        onAdd = onAddMetaPrompt,
        onEdit = { onEditMetaPrompt(it.id) },
        onDelete = { mp -> onSave(aiSettings.removeMetaPrompt(mp.id)) },
        onBack = onBackToPromptsSetup,
        onHome = onBackToHome,
        deleteEntityType = "Meta Prompt",
        deleteEntityName = { it.name },
        itemKey = { it.id }
    )
}

@Composable
fun MetaPromptEditScreen(
    metaPrompt: MetaPrompt?,
    existingNames: Set<String>,
    onSave: (MetaPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = metaPrompt != null

    var name by remember { mutableStateOf(metaPrompt?.name ?: "") }
    var type by remember { mutableStateOf(metaPrompt?.type?.takeIf { it in META_TYPES } ?: "chat") }
    var reference by remember { mutableStateOf(metaPrompt?.reference ?: false) }
    var text by remember { mutableStateOf(metaPrompt?.text ?: "") }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    // reference only applies to chat-type templates; flip it off when
    // the user switches type so the persisted value can't disagree
    // with the executor's behaviour.
    LaunchedEffect(type) { if (type != "chat" && reference) reference = false }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = if (isEditing) "Edit Meta Prompt" else "Add Meta Prompt", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.Red) } } else null
            )

            Text("Type", fontSize = 12.sp, color = AppColors.TextTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                META_TYPES.forEach { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = t },
                        label = { Text(t) }
                    )
                }
            }
            Text(
                when (type) {
                    "rerank" -> "Routes to a rerank API model (currently Cohere). Template body is unused — rerank uses the report prompt as the query and the per-agent responses as documents."
                    "moderation" -> "Routes to a moderation API model (currently Mistral). Template body is unused — the moderation endpoint takes the per-agent responses as inputs."
                    else -> "Runs as a chat completion. Template body supports @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@."
                },
                fontSize = 11.sp, color = AppColors.TextTertiary
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = reference,
                    enabled = type == "chat",
                    onCheckedChange = { reference = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Append reference legend", fontSize = 13.sp)
                    Text(
                        if (type == "chat") "Adds a [N] = Provider / Model footer to the response."
                        else "Only available for chat-type prompts.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                }
            }

            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Template (chat only — supports @QUESTION@ @RESULTS@ @COUNT@ @TITLE@ @DATE@)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8, maxLines = 22,
                enabled = type == "chat",
                colors = AppColors.outlinedFieldColors()
            )
            Text("${text.length} characters", fontSize = 11.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val id = metaPrompt?.id ?: java.util.UUID.randomUUID().toString()
                onSave(MetaPrompt(id, name.trim(), type, reference, text))
            },
            enabled = nameError == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isEditing) "Save" else "Create", maxLines = 1, softWrap = false) }
    }
}
