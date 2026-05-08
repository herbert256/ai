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

/** Categories whose prompts are pure templates (no agent dispatch). */
private val CROSS_CATEGORIES = setOf("fan_out", "fan_in")

/** Sentinel meaning the run-time picker should ask the user which
 *  model to fire on (the legacy behaviour). Stored verbatim in
 *  [InternalPrompt.agent]; any other string is interpreted as the
 *  name of an [Agent] in [Settings.agents]. */
private const val AGENT_SELECT = "*select"

/** Sentinel meaning "no agent applies" — used by `cross` prompts
 *  which fan out across every pair of report-models and never
 *  consult [Settings.agents]. */
private const val AGENT_NA = "*n/a"

/** Display label for each [InternalPrompt.category] surfaced as a
 *  separate CRUD card on Prompt Management. */
fun categoryDisplayName(category: String): String = when (category) {
    "meta" -> "Meta prompts"
    "fan_out" -> "Fan-out prompts"
    "fan_in" -> "Fan-in prompts"
    "internal" -> "Other internal prompts"
    else -> category
}

@Composable
fun InternalPromptsListScreen(
    aiSettings: Settings,
    /** Pin the list to a single [InternalPrompt.category]. The screen
     *  filters [Settings.internalPrompts] on this value, titles itself
     *  from [categoryDisplayName], and forwards the same value through
     *  to the edit screen so new rows are saved into this bucket. */
    categoryFilter: String,
    onBackToPromptsSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddInternalPrompt: () -> Unit,
    onEditInternalPrompt: (String) -> Unit
) {
    val label = categoryDisplayName(categoryFilter)
    val isFixedList = categoryFilter == "internal"
    CrudListScreen(
        title = label,
        items = aiSettings.internalPrompts.filter { it.category == categoryFilter },
        addLabel = "Add ${label.lowercase().removeSuffix("s")}",
        emptyMessage = "No ${label.lowercase()} configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { ip ->
            val parts = buildList {
                if (ip.reference) add("ref")
                if (ip.agent != AGENT_SELECT && ip.agent.isNotBlank()) add(ip.agent)
            }
            val tail = ip.title.takeIf { it.isNotBlank() }
                ?: ip.text.lineSequence().firstOrNull().orEmpty().take(60)
            val head = parts.joinToString(" · ")
            when {
                head.isBlank() && tail.isBlank() -> ""
                head.isBlank() -> tail
                tail.isBlank() -> head
                else -> "$head — $tail"
            }
        },
        onAdd = onAddInternalPrompt,
        onEdit = { onEditInternalPrompt(it.id) },
        onDelete = { ip -> onSave(aiSettings.removeInternalPrompt(ip.id)) },
        onBack = onBackToPromptsSetup,
        onHome = onBackToHome,
        deleteEntityType = label.removeSuffix("s"),
        deleteEntityName = { it.name },
        itemKey = { it.id },
        fixedList = isFixedList
    )
}

@Composable
fun InternalPromptEditScreen(
    internalPrompt: InternalPrompt?,
    existingNames: Set<String>,
    agentNames: List<String>,
    /** Pin the [InternalPrompt.category] to this value and hide the
     *  Category picker. */
    fixedCategory: String,
    onSave: (InternalPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = internalPrompt != null
    val isCrossCategory = fixedCategory in CROSS_CATEGORIES
    // Other Internal prompts (intro / model_info / translate / rerank
    // / moderation) are a fixed list — name is not user-editable.
    val isFixedList = fixedCategory == "internal"

    var name by remember { mutableStateOf(internalPrompt?.name ?: "") }
    var title by remember { mutableStateOf(internalPrompt?.title ?: "") }
    // Preserve the existing prompt's category on edit; only enforce
    // fixedCategory for new prompts. Stops a deep-link with the wrong
    // category from silently moving the prompt across buckets.
    val category = internalPrompt?.category ?: fixedCategory
    var reference by remember { mutableStateOf(internalPrompt?.reference ?: false) }
    var agent by remember {
        mutableStateOf(
            when {
                // Both fan_out AND fan_in are CROSS_CATEGORIES — the
                // agent slot is N/A for both. Without this, an existing
                // fan_in prompt would surface the agent dropdown.
                fixedCategory in CROSS_CATEGORIES -> AGENT_NA
                else -> internalPrompt?.agent?.ifBlank { AGENT_SELECT } ?: AGENT_SELECT
            }
        )
    }
    var text by remember { mutableStateOf(internalPrompt?.text ?: "") }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    var agentMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        val singular = categoryDisplayName(fixedCategory).removeSuffix("s")
        TitleBar(helpTopic = "internal_prompt_edit", title = if (isEditing) "Edit $singular" else "Add $singular", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                enabled = !isFixedList,
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.Red) } } else null
            )

            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                supportingText = { Text("Short description shown alongside the name on Fan out.",
                    fontSize = 11.sp, color = AppColors.TextTertiary) }
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = reference,
                    onCheckedChange = { reference = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Append reference legend", fontSize = 13.sp)
                    Text(
                        "Adds a [N] = Provider / Model footer to the response.",
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
                label = { Text("Template body") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8, maxLines = 22,
                colors = AppColors.outlinedFieldColors()
            )
            Text(
                when (fixedCategory) {
                    "fan_out" -> "Placeholders: @RESPONSE@ (per-call source response), @QUESTION@, @TITLE@, @DATE@, @COUNT@. Runs across every pair of report-models — N×(N-1) calls."
                    "fan_in" -> "Placeholders: @COUNT@ (N reports), @CROSS_COUNT@ (N-1 responses each), @QUESTION@, @TITLE@, @DATE@. Repeat the iterable block `***Report*** @REPORT@@RESPONSES@` (with @RESPONSE@ inside @RESPONSES@) once per report. Runs once on a picked model."
                    else -> "Chat placeholders: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@."
                },
                fontSize = 11.sp, color = AppColors.TextTertiary
            )
            Text("${text.length} characters", fontSize = 11.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val id = internalPrompt?.id ?: java.util.UUID.randomUUID().toString()
                onSave(InternalPrompt(id, name.trim(), reference, category, agent, text, title.trim()))
            },
            enabled = nameError == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isEditing) "Save" else "Create", maxLines = 1, softWrap = false) }
    }
}
