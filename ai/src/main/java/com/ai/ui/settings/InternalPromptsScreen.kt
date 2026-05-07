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
 *  default for new entries. `N/A` is the only valid type for rows
 *  in `cross_out` / `cross_in` categories — those flows iterate
 *  over the prompt body, not a typed dispatch. */
private val INTERNAL_TYPES = listOf("chat", "rerank", "moderation", "N/A")

/** Allowed categories. `meta` rows show as launchers in the Report
 *  Result screen's Meta button popup; `cross_out` rows show in the
 *  separate Cross button popup and run across every pair of
 *  report-models; `cross_in` rows feed the *Combine reports and
 *  all cross responses* button on Cross Level 1; `internal` rows
 *  are templates consumed by app features (Translate / Model info
 *  / Intro). */
private val INTERNAL_CATEGORIES = listOf("meta", "internal", "cross_out", "cross_in")

/** Categories whose only valid type is `N/A` — used to decide which
 *  Type chips to render and to keep the persisted type field in
 *  sync when the user flips between categories. */
private val CROSS_CATEGORIES = setOf("cross_out", "cross_in")

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
    "cross_out" -> "Cross fan-out prompts"
    "cross_in" -> "Cross fan-in prompts"
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
    onEditInternalPrompt: (String) -> Unit,
    /** Run the on-demand merge of `assets/prompts.json` and return the
     *  number of newly added rows. Caller persists; this screen just
     *  surfaces the result via a status line. */
    onLoadBundledPrompts: () -> Int = { 0 }
) {
    var loadStatus by remember { mutableStateOf<String?>(null) }
    val label = categoryDisplayName(categoryFilter)
    CrudListScreen(
        title = label,
        items = aiSettings.internalPrompts.filter { it.category == categoryFilter },
        addLabel = "Add ${label.lowercase().removeSuffix("s")}",
        emptyMessage = "No ${label.lowercase()} configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { ip ->
            val parts = buildList {
                if (ip.type != "N/A") add(ip.type)
                if (ip.type == "chat" && ip.reference) add("ref")
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
        headerContent = {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val added = onLoadBundledPrompts()
                    loadStatus = when {
                        added == 0 -> "No new prompts in assets/prompts.json"
                        added == 1 -> "Added 1 new prompt"
                        else -> "Added $added new prompts"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) {
                Text("Load new prompts from assets/prompts.json", maxLines = 1, softWrap = false)
            }
            loadStatus?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
            }
        }
    )
}

@Composable
fun InternalPromptEditScreen(
    internalPrompt: InternalPrompt?,
    existingNames: Set<String>,
    agentNames: List<String>,
    /** Pin the [InternalPrompt.category] to this value and hide the
     *  Category picker. cross_out / cross_in additionally hide the
     *  Type picker since their only valid type is `N/A`. */
    fixedCategory: String,
    onSave: (InternalPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = internalPrompt != null
    val isCrossCategory = fixedCategory in CROSS_CATEGORIES
    val initialType = when {
        isCrossCategory -> "N/A"
        internalPrompt?.type?.takeIf { it in INTERNAL_TYPES && it != "N/A" } != null -> internalPrompt.type
        else -> "chat"
    }

    var name by remember { mutableStateOf(internalPrompt?.name ?: "") }
    var title by remember { mutableStateOf(internalPrompt?.title ?: "") }
    var type by remember { mutableStateOf(initialType) }
    val category = fixedCategory
    var reference by remember { mutableStateOf(internalPrompt?.reference ?: false) }
    var agent by remember {
        mutableStateOf(
            when {
                fixedCategory == "cross_out" -> AGENT_NA
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

    // reference only applies to chat-type templates; flip it off when
    // the user switches type so the persisted value can't disagree
    // with the executor's behaviour.
    LaunchedEffect(type) { if (type != "chat" && reference) reference = false }

    var agentMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        val singular = categoryDisplayName(fixedCategory).removeSuffix("s")
        TitleBar(title = if (isEditing) "Edit $singular" else "Add $singular", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.Red) } } else null
            )

            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                supportingText = { Text("Short description shown alongside the name on Cross Level 1.",
                    fontSize = 11.sp, color = AppColors.TextTertiary) }
            )

            if (!isCrossCategory) {
                Text("Type", fontSize = 12.sp, color = AppColors.TextTertiary)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    INTERNAL_TYPES.filter { it != "N/A" }.forEach { t ->
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
            }

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
                label = { Text("Template body") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8, maxLines = 22,
                enabled = type == "chat" || isCrossCategory,
                colors = AppColors.outlinedFieldColors()
            )
            Text(
                when (fixedCategory) {
                    "cross_out" -> "Placeholders: @RESPONSE@ (per-call source response), @QUESTION@, @TITLE@, @DATE@, @COUNT@. Runs across every pair of report-models — N×(N-1) calls."
                    "cross_in" -> "Placeholders: @COUNT@ (N reports), @CROSS_COUNT@ (N-1 responses each), @QUESTION@, @TITLE@, @DATE@. Repeat the iterable block `***Report*** @REPORT@@RESPONSES@` (with @RESPONSE@ inside @RESPONSES@) once per report. Runs once on a picked model."
                    else -> "Chat placeholders: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@. rerank / moderation rows ignore the body."
                },
                fontSize = 11.sp, color = AppColors.TextTertiary
            )
            Text("${text.length} characters", fontSize = 11.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val id = internalPrompt?.id ?: java.util.UUID.randomUUID().toString()
                onSave(InternalPrompt(id, name.trim(), type, reference, category, agent, text, title.trim()))
            },
            enabled = nameError == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isEditing) "Save" else "Create", maxLines = 1, softWrap = false) }
    }
}
