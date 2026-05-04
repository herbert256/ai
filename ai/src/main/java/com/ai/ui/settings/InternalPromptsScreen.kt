package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.*
import com.ai.ui.shared.*

/** Allowed values for [InternalPrompt.type]. Order is the display
 *  order in the FilterChip row on the edit screen. `chat` is the
 *  default for new entries. */
private val INTERNAL_TYPES = listOf("chat", "rerank", "moderation")

/** Allowed categories. `meta` rows show as launchers on the Report
 *  Result screen; `internal` rows are templates consumed by app
 *  features (Translate / Model info / Intro). */
private val INTERNAL_CATEGORIES = listOf("meta", "internal")

/** Sentinel meaning the run-time picker should ask the user which
 *  model to fire on (the legacy behaviour). Stored verbatim in
 *  [InternalPrompt.agent]; any other string is interpreted as the
 *  name of an [Agent] in [Settings.agents]. */
private const val AGENT_SELECT = "*select"

@Composable
fun InternalPromptsListScreen(
    aiSettings: Settings,
    onBackToPromptsSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddInternalPrompt: () -> Unit,
    onEditInternalPrompt: (String) -> Unit
) {
    CrudListScreen(
        title = "Internal Prompts",
        items = aiSettings.internalPrompts,
        addLabel = "Add Internal Prompt",
        emptyMessage = "No Internal prompts configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { ip ->
            val refTag = if (ip.type == "chat" && ip.reference) " · ref" else ""
            val agentTag = if (ip.agent != AGENT_SELECT && ip.agent.isNotBlank()) " · ${ip.agent}" else ""
            val preview = ip.text.lineSequence().firstOrNull().orEmpty().take(60)
            "${ip.category} · ${ip.type}$refTag$agentTag${if (preview.isBlank()) "" else " — $preview"}"
        },
        onAdd = onAddInternalPrompt,
        onEdit = { onEditInternalPrompt(it.id) },
        onDelete = { ip -> onSave(aiSettings.removeInternalPrompt(ip.id)) },
        onBack = onBackToPromptsSetup,
        onHome = onBackToHome,
        deleteEntityType = "Internal Prompt",
        deleteEntityName = { it.name },
        itemKey = { it.id }
    )
}

@Composable
fun InternalPromptEditScreen(
    internalPrompt: InternalPrompt?,
    existingNames: Set<String>,
    agentNames: List<String>,
    onSave: (InternalPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = internalPrompt != null

    var name by remember { mutableStateOf(internalPrompt?.name ?: "") }
    var type by remember { mutableStateOf(internalPrompt?.type?.takeIf { it in INTERNAL_TYPES } ?: "chat") }
    var category by remember { mutableStateOf(internalPrompt?.category?.takeIf { it in INTERNAL_CATEGORIES } ?: "internal") }
    var reference by remember { mutableStateOf(internalPrompt?.reference ?: false) }
    var agent by remember { mutableStateOf(internalPrompt?.agent?.ifBlank { AGENT_SELECT } ?: AGENT_SELECT) }
    var text by remember { mutableStateOf(internalPrompt?.text ?: "") }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    // reference only applies to chat-type templates; flip it off when
    // the user switches type so the persisted value can't disagree
    // with the executor's behaviour.
    LaunchedEffect(type) { if (type != "chat" && reference) reference = false }

    var agentMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = if (isEditing) "Edit Internal Prompt" else "Add Internal Prompt", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.Red) } } else null
            )

            Text("Category", fontSize = 12.sp, color = AppColors.TextTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERNAL_CATEGORIES.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = c },
                        label = { Text(c) }
                    )
                }
            }
            Text(
                when (category) {
                    "meta" -> "Surfaces as a launcher button on the Report Result screen's Meta card."
                    else -> "Internal template used by an app feature (e.g. Translate, Model info)."
                },
                fontSize = 11.sp, color = AppColors.TextTertiary
            )

            Text("Type", fontSize = 12.sp, color = AppColors.TextTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERNAL_TYPES.forEach { t ->
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

            Text("Agent", fontSize = 12.sp, color = AppColors.TextTertiary)
            Box {
                OutlinedButton(
                    onClick = { agentMenuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) {
                    Text(
                        agent,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        color = if (agent == AGENT_SELECT) AppColors.TextTertiary else Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("▾", color = AppColors.TextTertiary)
                }
                DropdownMenu(
                    expanded = agentMenuOpen,
                    onDismissRequest = { agentMenuOpen = false },
                    modifier = Modifier.background(Color(0xFF2D2D2D))
                ) {
                    DropdownMenuItem(
                        text = { Text(AGENT_SELECT, fontSize = 13.sp,
                            color = if (agent == AGENT_SELECT) AppColors.Blue else Color.White) },
                        onClick = { agent = AGENT_SELECT; agentMenuOpen = false }
                    )
                    agentNames.sortedBy { it.lowercase() }.forEach { n ->
                        DropdownMenuItem(
                            text = { Text(n, fontSize = 13.sp,
                                color = if (agent == n) AppColors.Blue else Color.White) },
                            onClick = { agent = n; agentMenuOpen = false }
                        )
                    }
                }
            }
            Text(
                if (agent == AGENT_SELECT) "*select means the user picks the model at run time."
                else "Bound to the agent named '$agent' (resolved from Settings.agents at run time).",
                fontSize = 11.sp, color = AppColors.TextTertiary
            )

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
                val id = internalPrompt?.id ?: java.util.UUID.randomUUID().toString()
                onSave(InternalPrompt(id, name.trim(), type, reference, category, agent, text))
            },
            enabled = nameError == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isEditing) "Save" else "Create", maxLines = 1, softWrap = false) }
    }
}
