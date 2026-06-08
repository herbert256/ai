package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
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
 *  All fan-* categories share the same agent-N/A treatment —
 *  they're consumed by the Fan out / Fan in flow which already
 *  resolves the model to run on through its own picker, not via
 *  [Settings.agents]. */
private val FAN_CATEGORIES = setOf(
    "fan_out", "fan_in"
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
    "meta_compare" -> "Compare prompts"
    "fan_out" -> "Fan-out prompts"
    "fan_in" -> "Fan-in prompts"
    "icons" -> "Icon prompts"
    "info" -> "Info prompts"
    "internal" -> "Other internal prompts"
    "workers" -> "Worker prompts"
    "alt" -> "Alternative prompts"
    else -> category
}

/** Built-in template categories whose entries are fixed (no Add / Copy /
 *  Delete). Single source of truth so the CRUD gating can't drift from the
 *  category definitions above. */
fun isFixedListCategory(category: String): Boolean =
    category == "internal" || category == "icons" || category == "info" ||
        category == "workers" || category == "alt"

/** The "workers"-category prompt names that actually drive a covered kind, so
 *  the run-time Model-selection switch (*CONFIGURED / *SELECT) only shows where
 *  it does something. meta_compare (Compare) and alt (Find-alternative) always
 *  qualify — they carry their own per-prompt worker chains. */
private val MODEL_SELECTION_WORKER_NAMES = setOf(
    "meta", "fan-in", "second-rerank", "second-moderation",
    "tournament", "translate-text", "translate-title", "find-translation"
)
fun internalPromptSupportsModelSelection(category: String, name: String): Boolean = when {
    category.equals("meta_compare", ignoreCase = true) -> true
    category.equals("alt", ignoreCase = true) -> true
    category.equals("workers", ignoreCase = true) -> name.lowercase() in MODEL_SELECTION_WORKER_NAMES
    else -> false
}

/** Singular label for a single [InternalPrompt.category] entry — used
 *  for View-page titles and delete-confirm copy. Carried explicitly per
 *  category instead of string-munging the plural (a naive `removeSuffix("s")`
 *  produced awkward singulars like "Icons prompt" → "Icons prompt"). */
fun categorySingularName(category: String): String = when (category) {
    "meta" -> "Meta prompt"
    "meta_compare" -> "Compare prompt"
    "fan_out" -> "Fan-out prompt"
    "fan_in" -> "Fan-in prompt"
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
    onTrace: (() -> Unit)? = null,
    /** 🗑 delete this prompt (non-fixed categories only). Null hides it. */
    onDelete: (() -> Unit)? = null
) {
    BackHandler { onBack() }
    val isEditing = internalPrompt != null
    val isFanCategory = fixedCategory in FAN_CATEGORIES
    // Other Internal prompts (chat-title / model-info / model-intro
    // / second-rerank / second-moderation / test-model),
    // icons, info and workers (which now include translate-text /
    // translate-title) are fixed lists — name is not user-editable.
    // Single source of truth so gating can't drift.
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
        mutableStateOf(internalPrompt?.let { !it.provider.isNullOrBlank() && !it.model.isNullOrBlank() } ?: false)
    }
    var providerId by remember(resetTick) { mutableStateOf(internalPrompt?.provider ?: "") }
    var model by remember(resetTick) { mutableStateOf(internalPrompt?.model ?: "") }
    var providerDialogOpen by remember { mutableStateOf(false) }
    var modelDialogOpen by remember { mutableStateOf(false) }
    // Categories that expose the ordered worker-row editor (Model / Agent /
    // Flock / Swarm) in place of the single agent / provider+model picker, and
    // persist the chain in [InternalPrompt.workers]:
    //  - "workers"      — app-owned worker jobs (report-icon, …).
    //  - "meta_compare" — Compare-with-meta prompts, worker-judged.
    //  - "alt"          — Find-alternative icon/title prompts. A configured
    //                     worker is used directly at run time, skipping the
    //                     model-selection screen (empty ⇒ pick models then).
    val isWorkers = category.equals("workers", ignoreCase = true) ||
        category.equals("meta_compare", ignoreCase = true) ||
        category.equals("alt", ignoreCase = true)
    val isAltWorkers = category.equals("alt", ignoreCase = true)
    var workers by remember(resetTick) { mutableStateOf(internalPrompt?.workers ?: emptyList()) }
    // Run-time worker selection: *CONFIGURED (use [workers]) vs *SELECT (pick
    // workers each run). Only shown for the worker prompts that drive a covered
    // kind (see [internalPromptSupportsModelSelection]).
    var selectedModelSelection by remember(resetTick) {
        mutableStateOf(internalPrompt?.modelSelection ?: com.ai.model.MODEL_SELECTION_CONFIGURED)
    }
    val showModelSelectionSwitch = internalPromptSupportsModelSelection(category, name)
    // Per-prompt Parameters / System-prompt refs ("*NONE" = unset).
    // New edits store stable ids; legacy rows with names still resolve.
    var selectedParametersRef by remember(resetTick) { mutableStateOf(internalPrompt?.parameters ?: "*NONE") }
    var selectedSystemPromptRef by remember(resetTick) { mutableStateOf(internalPrompt?.systemPrompt ?: "*NONE") }
    val selectedParametersLabel = aiSettings.getParametersByIdOrName(selectedParametersRef)?.name
    val selectedSystemPromptLabel = aiSettings.getSystemPromptByIdOrName(selectedSystemPromptRef)?.name
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSysPromptDialog by remember { mutableStateOf(false) }
    if (showParamsDialog) {
        // The prompt stores a single preset ref; the multi-select
        // screen hands back ids — take the first and persist the id.
        com.ai.ui.shared.ParametersSelectScreen(
            aiSettings = aiSettings,
            selectedIds = aiSettings.getParametersByIdOrName(selectedParametersRef)?.id?.let { listOf(it) } ?: emptyList(),
            onConfirm = { ids ->
                selectedParametersRef = ids.firstOrNull() ?: "*NONE"
            },
            onBack = { showParamsDialog = false }, onNavigateHome = onNavigateHome
        )
        return
    }
    if (showSysPromptDialog) {
        com.ai.ui.shared.SystemPromptSelectScreen(
            aiSettings = aiSettings,
            selectedId = aiSettings.getSystemPromptByIdOrName(selectedSystemPromptRef)?.id,
            onSelect = { id ->
                selectedSystemPromptRef = id ?: "*NONE"
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
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        val singular = categoryDisplayName(fixedCategory).removeSuffix("s")
        TitleBar(
            helpTopic = "internal_prompt_edit",
            title = if (isAddMode) "Add $singular" else "Edit $singular",
            subject = name,
            onBackClick = onBack,
            // 👯 duplicate + 🗑 delete — only for non-fixed categories, and
            // hidden once the screen flips into copy/add mode.
            onCopyReport = if (isFixedList) null else dup.copyTrigger,
            onDelete = if (isAddMode || isFixedList) null else onDelete,
            onClear = { resetTick++ },
            onParameters = { showParamsDialog = true },
            onSystemPrompt = { showSysPromptDialog = true },
            onTrace = onTrace
        )
        // Save / Create CTA hoisted to the top — these forms can be
        // long (especially with the prompt-text editor) so a bottom
        // button gets scrolled out of view.
        // Provider+Model mode wins when both are set; otherwise the prompt is
        // bound to the agent (provider/model cleared) — mutually exclusive on disk.
        val pmActive = useProviderModel && providerId.isNotBlank() && model.isNotBlank()
        fun buildPrompt(id: String) = InternalPrompt(
            id = id, name = name.trim(), reference = reference, category = category,
            agent = if (isWorkers) AGENT_SELECT else if (pmActive) AGENT_SELECT else agent,
            text = text, title = title.trim(),
            provider = if (!isWorkers && pmActive) providerId else null,
            model = if (!isWorkers && pmActive) model else null,
            parameters = selectedParametersRef,
            systemPrompt = selectedSystemPromptRef,
            workers = if (isWorkers) workers else emptyList(),
            modelSelection = if (showModelSelectionSwitch) selectedModelSelection else com.ai.model.MODEL_SELECTION_CONFIGURED
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (isAddMode) {
            OutlinedButton(
                onClick = { onSave(buildPrompt(java.util.UUID.randomUUID().toString())); onBack() },
                enabled = nameError == null,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Create", maxLines = 1, softWrap = false) }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            // Edit: no Save button — auto-persist while editing and on leave.
            com.ai.ui.shared.AutoSaveOnChange(
                current = if (nameError == null) buildPrompt(internalPrompt!!.id) else null,
                onSave = onSave
            )
        }

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
                    supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError, color = AppColors.DangerAccent) } } else null
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
                                color = if (agent == AGENT_SELECT) AppColors.TextTertiary else AppColors.TextPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                            Text("▾", color = AppColors.TextTertiary)
                        }
                        DropdownMenu(
                            expanded = agentMenuOpen,
                            onDismissRequest = { agentMenuOpen = false },
                            modifier = Modifier.background(AppColors.SurfaceDark)
                        ) {
                            DropdownMenuItem(
                                text = { Text(AGENT_SELECT, fontSize = 13.sp,
                                    color = if (agent == AGENT_SELECT) AppColors.InfoAccent else AppColors.TextPrimary) },
                                onClick = { agent = AGENT_SELECT; agentMenuOpen = false }
                            )
                            agentNames.sortedBy { it.lowercase() }.forEach { n ->
                                DropdownMenuItem(
                                    text = { Text(n, fontSize = 13.sp,
                                        color = if (agent == n) AppColors.InfoAccent else AppColors.TextPrimary) },
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
                            color = if (providerId.isBlank()) AppColors.TextTertiary else AppColors.TextPrimary,
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
                            color = if (model.isBlank()) AppColors.TextTertiary else AppColors.TextPrimary,
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
            // Worker editor: an ordered list of worker rows (each one
            // Model / Agent / Flock / Swarm). For workers / meta_compare this
            // is a fallback chain; for "alt" it's the set the alternative
            // lookup is generated on (empty ⇒ ask via the model picker).
            SectionCard {
                Text(
                    if (isAltWorkers) "Workers — alternatives are generated on each"
                    else "Workers — ordered fallback chain",
                    fontSize = 12.sp, color = AppColors.TextTertiary
                )
                if (workers.isEmpty()) {
                    Text(
                        if (isAltWorkers) "No workers — the model-selection screen is shown at run time."
                        else "No workers yet — add at least one.",
                        fontSize = 12.sp, color = AppColors.TextDim
                    )
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
            if (showModelSelectionSwitch) {
                SectionCard {
                    Text("Model selection", fontSize = 12.sp, color = AppColors.TextTertiary)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = selectedModelSelection == com.ai.model.MODEL_SELECTION_CONFIGURED,
                            onClick = { selectedModelSelection = com.ai.model.MODEL_SELECTION_CONFIGURED },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("Configured", fontSize = 13.sp) }
                        SegmentedButton(
                            selected = selectedModelSelection == com.ai.model.MODEL_SELECTION_SELECT,
                            onClick = { selectedModelSelection = com.ai.model.MODEL_SELECTION_SELECT },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("Select at run time", fontSize = 13.sp) }
                    }
                    Text(
                        "*CONFIGURED uses the workers above. *SELECT shows the worker picker each time, just before this runs.",
                        fontSize = 11.sp, color = AppColors.TextDim
                    )
                }
            }
          }

            // Per-prompt Parameters / System-prompt presets. When set,
            // these override the agent/flock/swarm/provider/app-wide
            // levels for THIS prompt's API call — unless a runtime
            // 🌡️/🎭 pick is made on the model-selection screen.
            SectionCard {
                Text("Parameters & System prompt", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${com.ai.data.MetadataIconsHolder.current.parameters} ", fontSize = 14.sp)
                    Text(
                        selectedParametersLabel ?: "No parameters preset",
                        modifier = Modifier.weight(1f), fontSize = 13.sp,
                        color = if (selectedParametersLabel == null) AppColors.TextTertiary else AppColors.TextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    if (selectedParametersRef != "*NONE") {
                        Text(com.ai.data.MetadataIconsHolder.current.closeMark, color = AppColors.DangerAccent, fontSize = 16.sp,
                            modifier = Modifier.clickable { selectedParametersRef = "*NONE" }.padding(horizontal = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${com.ai.data.MetadataIconsHolder.current.systemPrompt} ", fontSize = 14.sp)
                    Text(
                        selectedSystemPromptLabel ?: "No system prompt",
                        modifier = Modifier.weight(1f), fontSize = 13.sp,
                        color = if (selectedSystemPromptLabel == null) AppColors.TextTertiary else AppColors.TextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    if (selectedSystemPromptRef != "*NONE") {
                        Text(com.ai.data.MetadataIconsHolder.current.closeMark, color = AppColors.DangerAccent, fontSize = 16.sp,
                            modifier = Modifier.clickable { selectedSystemPromptRef = "*NONE" }.padding(horizontal = 8.dp))
                    }
                }
                Text(
                    "Use ${com.ai.data.MetadataIconsHolder.current.parameters} / ${com.ai.data.MetadataIconsHolder.current.systemPrompt} in the bottom bar to set these. When set they override agent / provider / app-wide for this prompt — unless picked at run time.",
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
internal fun WorkerRowEditor(
    index: Int,
    worker: Worker,
    agentNames: List<String>,
    aiSettings: Settings,
    onChange: (Worker) -> Unit,
    onRemove: () -> Unit
) {
    // Four kinds, told apart by which field is set. A fresh Agent worker
    // uses agent="*select" (AGENT_SELECT), so agent != "*N/A" ⇒ agent kind;
    // flock/swarm use the same "*select" placeholder until one is picked.
    val kind = when {
        worker.flock != "*N/A" && worker.flock.isNotBlank() -> "flock"
        worker.swarm != "*N/A" && worker.swarm.isNotBlank() -> "swarm"
        worker.agent != "*N/A" -> "agent"
        else -> "model"
    }
    var agentMenuOpen by remember { mutableStateOf(false) }
    var flockMenuOpen by remember { mutableStateOf(false) }
    var swarmMenuOpen by remember { mutableStateOf(false) }
    var providerDialogOpen by remember { mutableStateOf(false) }
    var modelDialogOpen by remember { mutableStateOf(false) }
    val providerId = worker.provider.takeIf { it != "*N/A" } ?: ""
    val model = worker.model.takeIf { it != "*N/A" } ?: ""

    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.AppBackground),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Worker ${index + 1}", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("${com.ai.data.MetadataIconsHolder.current.closeMark} Remove", fontSize = 12.sp, color = AppColors.DangerAccent) }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = kind == "model",
                    onClick = { onChange(Worker(agent = "*N/A")) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4)
                ) { Text("Model", fontSize = 12.sp) }
                SegmentedButton(
                    selected = kind == "agent",
                    onClick = { onChange(Worker(agent = AGENT_SELECT)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4)
                ) { Text("Agent", fontSize = 12.sp) }
                SegmentedButton(
                    selected = kind == "flock",
                    onClick = { onChange(Worker(agent = "*N/A", flock = AGENT_SELECT)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4)
                ) { Text("Flock", fontSize = 12.sp) }
                SegmentedButton(
                    selected = kind == "swarm",
                    onClick = { onChange(Worker(agent = "*N/A", swarm = AGENT_SELECT)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4)
                ) { Text("Swarm", fontSize = 12.sp) }
            }
            when (kind) {
                "agent" -> Box {
                    OutlinedButton(
                        onClick = { agentMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) {
                        Text(
                            worker.agent, modifier = Modifier.weight(1f), fontSize = 13.sp,
                            color = if (worker.agent == AGENT_SELECT) AppColors.TextTertiary else AppColors.TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text("▾", color = AppColors.TextTertiary)
                    }
                    DropdownMenu(
                        expanded = agentMenuOpen,
                        onDismissRequest = { agentMenuOpen = false },
                        modifier = Modifier.background(AppColors.SurfaceDark)
                    ) {
                        DropdownMenuItem(
                            text = { Text(AGENT_SELECT, fontSize = 13.sp, color = if (worker.agent == AGENT_SELECT) AppColors.InfoAccent else AppColors.TextPrimary) },
                            onClick = { onChange(Worker(agent = AGENT_SELECT)); agentMenuOpen = false }
                        )
                        agentNames.sortedBy { it.lowercase() }.forEach { n ->
                            DropdownMenuItem(
                                text = { Text(n, fontSize = 13.sp, color = if (worker.agent == n) AppColors.InfoAccent else AppColors.TextPrimary) },
                                onClick = { onChange(Worker(agent = n)); agentMenuOpen = false }
                            )
                        }
                    }
                }
                "flock" -> Box {
                    val chosen = worker.flock != AGENT_SELECT
                    OutlinedButton(
                        onClick = { flockMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) {
                        Text(
                            if (chosen) worker.flock else "Select flock…", modifier = Modifier.weight(1f), fontSize = 13.sp,
                            color = if (chosen) AppColors.TextPrimary else AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text("▾", color = AppColors.TextTertiary)
                    }
                    DropdownMenu(
                        expanded = flockMenuOpen,
                        onDismissRequest = { flockMenuOpen = false },
                        modifier = Modifier.background(AppColors.SurfaceDark)
                    ) {
                        if (aiSettings.flocks.isEmpty()) {
                            DropdownMenuItem(text = { Text("No flocks defined", fontSize = 13.sp, color = AppColors.TextTertiary) }, onClick = { flockMenuOpen = false })
                        }
                        aiSettings.flocks.sortedBy { it.name.lowercase() }.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.name, fontSize = 13.sp, color = if (worker.flock == f.name) AppColors.InfoAccent else AppColors.TextPrimary) },
                                onClick = { onChange(Worker(agent = "*N/A", flock = f.name)); flockMenuOpen = false }
                            )
                        }
                    }
                }
                "swarm" -> Box {
                    val chosen = worker.swarm != AGENT_SELECT
                    OutlinedButton(
                        onClick = { swarmMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) {
                        Text(
                            if (chosen) worker.swarm else "Select swarm…", modifier = Modifier.weight(1f), fontSize = 13.sp,
                            color = if (chosen) AppColors.TextPrimary else AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text("▾", color = AppColors.TextTertiary)
                    }
                    DropdownMenu(
                        expanded = swarmMenuOpen,
                        onDismissRequest = { swarmMenuOpen = false },
                        modifier = Modifier.background(AppColors.SurfaceDark)
                    ) {
                        if (aiSettings.swarms.isEmpty()) {
                            DropdownMenuItem(text = { Text("No swarms defined", fontSize = 13.sp, color = AppColors.TextTertiary) }, onClick = { swarmMenuOpen = false })
                        }
                        aiSettings.swarms.sortedBy { it.name.lowercase() }.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name, fontSize = 13.sp, color = if (worker.swarm == s.name) AppColors.InfoAccent else AppColors.TextPrimary) },
                                onClick = { onChange(Worker(agent = "*N/A", swarm = s.name)); swarmMenuOpen = false }
                            )
                        }
                    }
                }
                else -> {
                    OutlinedButton(
                        onClick = { providerDialogOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) {
                        Text(
                            providerId.ifBlank { "Select provider…" }, modifier = Modifier.weight(1f), fontSize = 13.sp,
                            color = if (providerId.isBlank()) AppColors.TextTertiary else AppColors.TextPrimary,
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
                            color = if (model.isBlank()) AppColors.TextTertiary else AppColors.TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text("▾", color = AppColors.TextTertiary)
                    }
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
