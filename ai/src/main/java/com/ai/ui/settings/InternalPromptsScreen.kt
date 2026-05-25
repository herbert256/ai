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

/** A surfaceVariant Card with a 16.dp Column inside, used to group
 *  the related fields on the Internal Prompt edit screen into
 *  visually distinct sections (Name & Title / Reference / Scope /
 *  Agent / Template). Matches the Card pattern in
 *  ServiceSettingsScreens. */
@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/** Categories whose prompts are pure templates (no agent dispatch).
 *  All five fan-* categories share the same agent-N/A treatment —
 *  they're consumed by the Fan out / Fan in flow which already
 *  resolves the model to run on through its own picker, not via
 *  [Settings.agents]. */
private val FAN_CATEGORIES = setOf(
    "fan_out", "fan_in", "fan-in-model"
)

/** Sentinel meaning the run-time picker should ask the user which
 *  model to fire on (the legacy behaviour). Stored verbatim in
 *  [InternalPrompt.agent]; any other string is interpreted as the
 *  name of an [Agent] in [Settings.agents]. */
private const val AGENT_SELECT = "*select"

/** Sentinel meaning "no agent applies" — used by `fan out` prompts
 *  which fan out across every pair of report-models and never
 *  consult [Settings.agents]. */
private const val AGENT_NA = "*n/a"

/** Display label for each [InternalPrompt.category] surfaced as a
 *  separate CRUD card on Prompt Management. */
fun categoryDisplayName(category: String): String = when (category) {
    "meta" -> "Meta prompts"
    "fan_out" -> "Fan-out prompts"
    "fan_in" -> "Fan-in prompts"
    "fan-in-model" -> "Fan In, model"
    "internal" -> "Other internal prompts"
    "workers" -> "Worker prompts"
    "alt" -> "Alternative prompts"
    else -> category
}

/** Built-in template categories whose entries are fixed (no Add / Copy /
 *  Delete). Single source of truth so the CRUD gating can't drift from the
 *  category definitions above. */
fun isFixedListCategory(category: String): Boolean =
    category == "internal" || category == "workers" || category == "alt"

/** Singular label for a single [InternalPrompt.category] entry — used
 *  for View-page titles and delete-confirm copy. Carried explicitly per
 *  category instead of string-munging the plural (a naive `removeSuffix("s")`
 *  produced awkward singulars like "Fan-in-model" → "Fan-in-model" or
 *  "Icons prompt" → "Icons prompt"). */
fun categorySingularName(category: String): String = when (category) {
    "meta" -> "Meta prompt"
    "fan_out" -> "Fan-out prompt"
    "fan_in" -> "Fan-in prompt"
    "fan-in-model" -> "Fan-in model prompt"
    "internal" -> "Internal prompt"
    "workers" -> "Worker prompt"
    "alt" -> "Alternative prompt"
    else -> category
}
@Composable
fun InternalPromptEditScreen(
    internalPrompt: InternalPrompt?,
    existingNames: Set<String>,
    agentNames: List<String>,
    /** Full settings — needed by the Provider / Model pickers offered
     *  as an alternative to binding the prompt to a named agent. */
    aiSettings: Settings,
    /** Pin the [InternalPrompt.category] to this value and hide the
     *  Category picker. */
    fixedCategory: String,
    onSave: (InternalPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** 🐞 bottom-bar trace-link — non-null when this prompt has ≥1 trace. */
    onTrace: (() -> Unit)? = null
) {
    BackHandler { onBack() }
    val isEditing = internalPrompt != null
    val isFanCategory = fixedCategory in FAN_CATEGORIES
    // Other Internal prompts (intro / model_info / translate / rerank
    // / moderation), icons, info and workers are fixed lists — name is
    // not user-editable. Single source of truth so gating can't drift.
    val isFixedList = isFixedListCategory(fixedCategory)

    var resetTick by remember { mutableStateOf(0) }
    var name by remember(resetTick) { mutableStateOf(internalPrompt?.name ?: "") }
    var title by remember(resetTick) { mutableStateOf(internalPrompt?.title ?: "") }
    // Preserve the existing prompt's category on edit; only enforce
    // fixedCategory for new prompts. Stops a deep-link with the wrong
    // category from silently moving the prompt across buckets.
    val category = internalPrompt?.category ?: fixedCategory
    val isMeta = category.equals("meta", ignoreCase = true)
    val isFanOut = category.equals("fan_out", ignoreCase = true)
    var reference by remember(resetTick) { mutableStateOf(internalPrompt?.reference ?: false) }
    var agent by remember(resetTick) {
        mutableStateOf(
            when {
                // Both fan_out AND fan_in are FAN_CATEGORIES — the
                // agent slot is N/A for both. Without this, an existing
                // fan_in prompt would surface the agent dropdown.
                fixedCategory in FAN_CATEGORIES -> AGENT_NA
                else -> internalPrompt?.agent?.ifBlank { AGENT_SELECT } ?: AGENT_SELECT
            }
        )
    }
    var text by remember(resetTick) { mutableStateOf(internalPrompt?.text ?: "") }
    // Either/or alternative to [agent]: pin a provider id + model. The
    // toggle starts in Provider+Model mode only when both were saved.
    var useProviderModel by remember(resetTick) {
        mutableStateOf(!internalPrompt?.provider.isNullOrBlank() && !internalPrompt?.model.isNullOrBlank())
    }
    var providerId by remember(resetTick) { mutableStateOf(internalPrompt?.provider ?: "") }
    var model by remember(resetTick) { mutableStateOf(internalPrompt?.model ?: "") }
    var providerDialogOpen by remember { mutableStateOf(false) }
    var modelDialogOpen by remember { mutableStateOf(false) }
    // "workers" category: an ordered list of worker rows replaces the
    // single agent / provider+model picker. Execution is not wired yet.
    val isWorkers = category.equals("workers", ignoreCase = true)
    var workers by remember(resetTick) { mutableStateOf(internalPrompt?.workers ?: emptyList()) }
    // Per-prompt Parameters / System-prompt preset NAMES ("*NONE" = unset).
    var selectedParametersName by remember(resetTick) { mutableStateOf(internalPrompt?.parameters ?: "*NONE") }
    var selectedSystemPromptName by remember(resetTick) { mutableStateOf(internalPrompt?.systemPrompt ?: "*NONE") }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSysPromptDialog by remember { mutableStateOf(false) }
    if (showParamsDialog) {
        // The prompt stores a single preset NAME; the multi-select
        // screen hands back ids — take the first → its name.
        com.ai.ui.shared.ParametersSelectScreen(
            aiSettings = aiSettings,
            selectedIds = aiSettings.parameters.firstOrNull { it.name == selectedParametersName }?.id?.let { listOf(it) } ?: emptyList(),
            onConfirm = { ids ->
                selectedParametersName = ids.firstNotNullOfOrNull { id -> aiSettings.parameters.firstOrNull { it.id == id }?.name } ?: "*NONE"
            },
            onBack = { showParamsDialog = false }, onNavigateHome = onNavigateHome
        )
        return
    }
    if (showSysPromptDialog) {
        com.ai.ui.shared.SystemPromptSelectScreen(
            aiSettings = aiSettings,
            selectedId = aiSettings.systemPrompts.firstOrNull { it.name == selectedSystemPromptName }?.id,
            onSelect = { id ->
                selectedSystemPromptName = id?.let { sid -> aiSettings.systemPrompts.firstOrNull { it.id == sid }?.name } ?: "*NONE"
            },
            onBack = { showSysPromptDialog = false }, onNavigateHome = onNavigateHome
        )
        return
    }

    // Duplicate-mode is only meaningful for user-editable categories
    // (meta / fan_*); the fixed-list categories (internal / icons)
    // have predetermined slots that can't be cloned into a new entry.
    val dup = com.ai.ui.shared.rememberDuplicateMode(
        isEditingExisting = internalPrompt != null && !isFixedList,
        onDuplicate = { name = "$name-copy" }
    )
    // A fixed-list prompt (icons / info / internal) is "Edit-only": it
    // has no duplicate path (so dup reports add-mode), but editing it
    // must save back onto the SAME row — otherwise the locked name
    // collides with itself and Create stays disabled, leaving these
    // prompts (the ones that actually consume agent / provider+model)
    // uneditable. So force real-edit semantics for an existing one.
    val isAddMode = if (internalPrompt != null && isFixedList) false else dup.isAddMode
    val effectiveExistingNames = if (isAddMode && internalPrompt != null) {
        existingNames + internalPrompt.name.lowercase()
    } else existingNames

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in effectiveExistingNames -> "Name already exists"
        else -> null
    }

    var agentMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        val singular = categoryDisplayName(fixedCategory).removeSuffix("s")
        TitleBar(
            helpTopic = "internal_prompt_edit",
            title = if (isAddMode) "Add $singular" else "Edit $singular",
            subject = name,
            onBackClick = onBack,
            onCopyReport = null,
            onClear = { resetTick++ },
            onParameters = { showParamsDialog = true },
            onSystemPrompt = { showSysPromptDialog = true },
            onTrace = onTrace
        )
        // Save / Create CTA hoisted to the top — these forms can be
        // long (especially with the prompt-text editor) so a bottom
        // button gets scrolled out of view.
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val id = if (isAddMode) java.util.UUID.randomUUID().toString() else internalPrompt!!.id
                // Provider+Model mode wins when both are set; otherwise
                // the prompt is bound to the agent (and provider/model
                // cleared) so the two are mutually exclusive on disk.
                val pmActive = useProviderModel && providerId.isNotBlank() && model.isNotBlank()
                onSave(
                    InternalPrompt(
                        id = id, name = name.trim(), reference = reference, category = category,
                        agent = if (isWorkers) AGENT_SELECT else if (pmActive) AGENT_SELECT else agent,
                        text = text, title = title.trim(),
                        provider = if (!isWorkers && pmActive) providerId else null,
                        model = if (!isWorkers && pmActive) model else null,
                        parameters = selectedParametersName,
                        systemPrompt = selectedSystemPromptName,
                        workers = if (isWorkers) workers else emptyList()
                    )
                )
            },
            enabled = nameError == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isAddMode) "Create" else "Save", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Name + Title belong together: both are display fields on
            // the list / Fan-out picker. Group them under a single card
            // so the visual hierarchy maps to the role.
            SectionCard {
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
            }

            SectionCard {
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
            }

          if (!isWorkers) {
            SectionCard {
                Text("Run on", fontSize = 12.sp, color = AppColors.TextTertiary)
                // Either/or: bind to a named Agent, or pin a Provider +
                // Model directly. Mutually exclusive — picking one mode
                // hides the other's controls; only the active one is saved.
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !useProviderModel,
                        onClick = { useProviderModel = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("Agent", fontSize = 13.sp) }
                    SegmentedButton(
                        selected = useProviderModel,
                        onClick = { useProviderModel = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("Provider + Model", fontSize = 13.sp) }
                }

                if (!useProviderModel) {
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
                } else {
                    // Provider picker → resets model when the provider changes.
                    OutlinedButton(
                        onClick = { providerDialogOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) {
                        Text(
                            providerId.ifBlank { "Select provider…" },
                            modifier = Modifier.weight(1f), fontSize = 13.sp,
                            color = if (providerId.isBlank()) AppColors.TextTertiary else Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                        Text("▾", color = AppColors.TextTertiary)
                    }
                    OutlinedButton(
                        onClick = { modelDialogOpen = true },
                        enabled = providerId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) {
                        Text(
                            model.ifBlank { "Select model…" },
                            modifier = Modifier.weight(1f), fontSize = 13.sp,
                            color = if (model.isBlank()) AppColors.TextTertiary else Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                        Text("▾", color = AppColors.TextTertiary)
                    }
                    Text(
                        if (providerId.isNotBlank() && model.isNotBlank())
                            "Runs on $providerId / $model (using that provider's API key)."
                        else "Pick a provider and a model to pin this prompt to.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                }
            }
            if (providerDialogOpen) {
                com.ai.ui.report.start.ReportSelectProviderDialog(
                    aiSettings = aiSettings,
                    onSelectProvider = { svc ->
                        if (svc.id != providerId) model = ""   // model belongs to a provider
                        providerId = svc.id
                        providerDialogOpen = false
                    },
                    onDismiss = { providerDialogOpen = false }
                )
            }
            if (modelDialogOpen) {
                val svc = com.ai.data.AppService.findById(providerId)
                if (svc != null) {
                    com.ai.ui.report.start.ReportSelectModelDialog(
                        provider = svc,
                        aiSettings = aiSettings,
                        onSelectModel = { m -> model = m; modelDialogOpen = false },
                        onDismiss = { modelDialogOpen = false }
                    )
                } else modelDialogOpen = false
            }
          } else {
            // Workers category: edit an ordered list of worker rows
            // (each one agent OR provider+model). Intended as a fallback
            // chain; execution is not wired yet.
            SectionCard {
                Text("Workers — ordered fallback chain", fontSize = 12.sp, color = AppColors.TextTertiary)
                if (workers.isEmpty()) {
                    Text("No workers yet — add at least one.", fontSize = 12.sp, color = AppColors.TextDim)
                }
                workers.forEachIndexed { idx, w ->
                    WorkerRowEditor(
                        index = idx,
                        worker = w,
                        agentNames = agentNames,
                        aiSettings = aiSettings,
                        onChange = { nw -> workers = workers.toMutableList().also { it[idx] = nw } },
                        onRemove = { workers = workers.toMutableList().also { it.removeAt(idx) } }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { workers = workers + Worker(agent = AGENT_SELECT) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("+ Add worker", fontSize = 13.sp) }
            }
          }

            // Per-prompt Parameters / System-prompt presets. When set,
            // these override the agent/flock/swarm/provider/app-wide
            // levels for THIS prompt's API call — unless a runtime
            // 🌡️/🎭 pick is made on the model-selection screen.
            SectionCard {
                Text("Parameters & System prompt", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🌡️ ", fontSize = 14.sp)
                    Text(
                        if (selectedParametersName == "*NONE") "No parameters preset" else selectedParametersName,
                        modifier = Modifier.weight(1f), fontSize = 13.sp,
                        color = if (selectedParametersName == "*NONE") AppColors.TextTertiary else Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    if (selectedParametersName != "*NONE") {
                        Text("✕", color = AppColors.Red, fontSize = 16.sp,
                            modifier = Modifier.clickable { selectedParametersName = "*NONE" }.padding(horizontal = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎭 ", fontSize = 14.sp)
                    Text(
                        if (selectedSystemPromptName == "*NONE") "No system prompt" else selectedSystemPromptName,
                        modifier = Modifier.weight(1f), fontSize = 13.sp,
                        color = if (selectedSystemPromptName == "*NONE") AppColors.TextTertiary else Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    if (selectedSystemPromptName != "*NONE") {
                        Text("✕", color = AppColors.Red, fontSize = 16.sp,
                            modifier = Modifier.clickable { selectedSystemPromptName = "*NONE" }.padding(horizontal = 8.dp))
                    }
                }
                Text(
                    "Use 🌡️ / 🎭 in the bottom bar to set these. When set they override agent / provider / app-wide for this prompt — unless picked at run time.",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
            }

            SectionCard {
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
                        "fan_in" -> "Placeholders: @COUNT@ (N reports), @FAN_OUT_COUNT@ (N-1 responses each), @QUESTION@, @TITLE@, @DATE@. Repeat the iterable block `***Report*** @REPORT@@RESPONSES@` (with @RESPONSE@ inside @RESPONSES@) once per report. Runs once on a picked model."
                        "fan-in-model" -> "Placeholders: @INITIATOR@ (active model's report response), @RESPONDERS@ (other models' fan-out responses to the active model), @RESPONDER_PAIRS@ (pairs where the active model is the responder — `***Report***` + `***Response***` per pair), @QUESTION@, @TITLE@, @DATE@, @COUNT@. Scoped to the L2 active model — runs once on a picked model."
                        else -> "Chat placeholders: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@."
                    },
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                Text("${text.length} characters", fontSize = 11.sp, color = AppColors.TextTertiary)
            }
        }

    }
}

/** One editable worker row for the "workers" category. Mode is derived
 *  from the worker, not held as separate state, so add/remove can't
 *  desync: `agent == "*N/A"` ⇒ Provider+Model mode, otherwise Agent
 *  mode. Each user action emits a fresh normalised [Worker] via
 *  [onChange]. Reuses the same controls as the single-prompt editor. */
@Composable
private fun WorkerRowEditor(
    index: Int,
    worker: Worker,
    agentNames: List<String>,
    aiSettings: Settings,
    onChange: (Worker) -> Unit,
    onRemove: () -> Unit
) {
    val pmMode = worker.agent == "*N/A"
    var agentMenuOpen by remember { mutableStateOf(false) }
    var providerDialogOpen by remember { mutableStateOf(false) }
    var modelDialogOpen by remember { mutableStateOf(false) }
    val providerId = worker.provider.takeIf { it != "*N/A" } ?: ""
    val model = worker.model.takeIf { it != "*N/A" } ?: ""

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Worker ${index + 1}", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("✕ Remove", fontSize = 12.sp, color = AppColors.Red) }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !pmMode,
                    onClick = { onChange(Worker(agent = AGENT_SELECT)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Agent", fontSize = 13.sp) }
                SegmentedButton(
                    selected = pmMode,
                    onClick = { onChange(Worker(agent = "*N/A")) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Provider + Model", fontSize = 13.sp) }
            }
            if (!pmMode) {
                Box {
                    OutlinedButton(
                        onClick = { agentMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) {
                        Text(
                            worker.agent, modifier = Modifier.weight(1f), fontSize = 13.sp,
                            color = if (worker.agent == AGENT_SELECT) AppColors.TextTertiary else Color.White,
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
                            text = { Text(AGENT_SELECT, fontSize = 13.sp, color = if (worker.agent == AGENT_SELECT) AppColors.Blue else Color.White) },
                            onClick = { onChange(Worker(agent = AGENT_SELECT)); agentMenuOpen = false }
                        )
                        agentNames.sortedBy { it.lowercase() }.forEach { n ->
                            DropdownMenuItem(
                                text = { Text(n, fontSize = 13.sp, color = if (worker.agent == n) AppColors.Blue else Color.White) },
                                onClick = { onChange(Worker(agent = n)); agentMenuOpen = false }
                            )
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { providerDialogOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) {
                    Text(
                        providerId.ifBlank { "Select provider…" }, modifier = Modifier.weight(1f), fontSize = 13.sp,
                        color = if (providerId.isBlank()) AppColors.TextTertiary else Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("▾", color = AppColors.TextTertiary)
                }
                OutlinedButton(
                    onClick = { modelDialogOpen = true },
                    enabled = providerId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) {
                    Text(
                        model.ifBlank { "Select model…" }, modifier = Modifier.weight(1f), fontSize = 13.sp,
                        color = if (model.isBlank()) AppColors.TextTertiary else Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("▾", color = AppColors.TextTertiary)
                }
            }
        }
    }
    if (providerDialogOpen) {
        com.ai.ui.report.start.ReportSelectProviderDialog(
            aiSettings = aiSettings,
            onSelectProvider = { svc ->
                onChange(Worker(agent = "*N/A", provider = svc.id, model = "*N/A"))  // new provider clears the model
                providerDialogOpen = false
            },
            onDismiss = { providerDialogOpen = false }
        )
    }
    if (modelDialogOpen) {
        val svc = com.ai.data.AppService.findById(providerId)
        if (svc != null) {
            com.ai.ui.report.start.ReportSelectModelDialog(
                provider = svc,
                aiSettings = aiSettings,
                onSelectModel = { m -> onChange(Worker(agent = "*N/A", provider = providerId, model = m)); modelDialogOpen = false },
                onDismiss = { modelDialogOpen = false }
            )
        } else modelDialogOpen = false
    }
}
