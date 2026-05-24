package com.ai.ui.admin

/**
 * Per-screen bottom-bar icon legends. Each entry is keyed by the screen's
 * `helpTopic` and lists its icons (glyph, short name, screen-specific
 * description) in the order they appear in the bottom bar.
 *
 * Rendering (HelpScreen.kt):
 *  - Topics in [ICON_HELP_AS_PAGE] (a single screen shows >3 icons) get a
 *    standalone "<topic>_icons" page reached by the ❔ bottom-bar glyph.
 *  - Every other topic here (a screen shows 1–3 icons) embeds the same
 *    table inline under its main help page.
 *
 * Where one topic is shared by several screens (CRUD list/view/edit, or
 * manage vs view drill-ins) the row list is the UNION of the icons those
 * screens show. Descriptions are hand-written per screen — trace the
 * TitleBar handler, never guess (e.g. 🆕 on Manage adds an operation, it
 * does NOT start a new report).
 */
internal val SCREEN_ICON_HELP: Map<String, List<Triple<String, String, String>>> = mapOf(

    // ===== Report flow (top level) =====
    "report_run" to listOf(
        Triple("🆕", "Create", "Add an operation to this report: Meta, Rerank, Moderation, Fan out or Translate."),
        Triple("💬", "Chat", "Start a chat seeded with this report's prompt."),
        Triple("🗂️", "Switch report", "Pick another report to manage."),
        Triple("ℹ️", "Information", "The per-report info screen."),
        Triple("🌡️", "Parameters", "Pick the preset(s) used as this report's parameters on the next Regenerate."),
        Triple("🎭", "System prompt", "Pick the system prompt used for this report."),
        Triple("📌", "Pin / unpin", "Keep this report at the top of the lists (orange when pinned)."),
        Triple("📤", "Export", "Export / share the report (once the run has completed)."),
        Triple("👯", "Duplicate", "Make a copy of this report."),
        Triple("👁", "View", "Open the per-agent results / View hub for this report."),
        Triple("✏️", "Edit", "Change the prompt, title, or models."),
        Triple("🔄", "Regenerate", "Re-run every agent (once the run has completed)."),
        Triple("🗑", "Delete", "Delete this report (asks to confirm)."),
        Triple("🐞", "Trace", "API traces for this report (each agent row has its own 🐞)."),
    ),
    "report_new" to listOf(
        Triple("🌡️", "Parameters", "Configure API parameters: pick the preset(s) for this report."),
        Triple("🎭", "System prompt", "Pick the system prompt for this report."),
        Triple("🧽", "Clear", "Clear the title, prompt and any attached image."),
        Triple("📎", "Attach", "Attach an image (vision) to send with the prompt."),
        Triple("🚩", "Validate prompt", "Pick a moderation model to screen the prompt first; tap again to clear it."),
    ),

    // ===== Worker / preset edit screens (own TitleBar, separate from the crud_* lists) =====
    "agent_edit" to listOf(
        Triple("🌡️", "Parameters", "Pick the parameter preset(s) for this agent."),
        Triple("🎭", "System prompt", "Pick this agent's system prompt."),
        Triple("🧽", "Reset", "Discard your edits and restore the saved agent."),
        Triple("👁", "View", "Open this agent's read-only view (editing an existing agent)."),
    ),
    "flock_edit" to listOf(
        Triple("🌡️", "Parameters", "Pick the parameter preset(s) for this flock."),
        Triple("🎭", "System prompt", "Pick this flock's system prompt."),
        Triple("🧽", "Reset", "Discard your edits and restore the saved flock."),
        Triple("👁", "View", "Open this flock's read-only view (editing an existing flock)."),
    ),
    "swarm_edit" to listOf(
        Triple("🌡️", "Parameters", "Pick the parameter preset(s) for this swarm."),
        Triple("🎭", "System prompt", "Pick this swarm's system prompt."),
        Triple("🧽", "Reset", "Discard your edits and restore the saved swarm."),
        Triple("👁", "View", "Open this swarm's read-only view (editing an existing swarm)."),
    ),
    "internal_prompt_edit" to listOf(
        Triple("🌡️", "Parameters", "Pick the parameter preset this prompt uses (overrides the agent / app default)."),
        Triple("🎭", "System prompt", "Pick the system prompt this prompt uses."),
        Triple("🧽", "Reset", "Discard your edits and restore the saved prompt."),
    ),
    "parameters_edit" to listOf(
        Triple("🧽", "Reset", "Reset the form — clears the fields when adding, restores the saved values when editing."),
    ),
    "system_prompt_edit" to listOf(
        Triple("🧽", "Reset", "Reset the form — clears the fields when adding, restores the saved values when editing."),
    ),
    "example_prompt_edit" to listOf(
        Triple("🧽", "Reset", "Reset the form — clears the fields when adding, restores the saved values when editing."),
    ),
    "manual_model_types" to listOf(
        Triple("🧽", "Reset", "Reset the form to its starting values."),
        Triple("👯", "Duplicate", "Duplicate this override as a new entry (editing an existing one)."),
    ),

    // ===== CRUD list / view (shared crud_* topic) =====
    "crud_cost_overrides" to listOf(
        Triple("🆕", "Add", "Add a new manual cost override."),
        Triple("🧹", "Housekeeping", "Jump to the related Housekeeping screen."),
        Triple("✏️", "Edit", "Edit this cost override."),
        Triple("👯", "Duplicate", "Duplicate this cost override as a new one."),
        Triple("🗑", "Delete", "Delete this cost override."),
    ),
    "crud_model_cooldowns" to listOf(
        Triple("🆕", "Add", "Add a new model cooldown."),
        Triple("✏️", "Edit", "Edit this cooldown."),
        Triple("👯", "Duplicate", "Duplicate this cooldown as a new one."),
        Triple("🗑", "Delete", "Delete this cooldown."),
        Triple("🧽", "Reset", "Reset the add / edit form to its starting values."),
    ),
    "crud_model_types" to listOf(
        Triple("🆕", "Add", "Add a new manual model-type override."),
        Triple("✏️", "Edit", "Edit this override."),
        Triple("👯", "Duplicate", "Duplicate this override as a new one."),
        Triple("🗑", "Delete", "Delete this override."),
        Triple("🧽", "Reset", "Reset the add / edit form to its starting values."),
    ),
    "crud_test_excluded" to listOf(
        Triple("🆕", "Add", "Add a model to exclude from Test all models."),
        Triple("🧹", "Housekeeping", "Jump to the related Housekeeping screen."),
        Triple("✏️", "Edit", "Edit this entry."),
        Triple("👯", "Duplicate", "Duplicate this entry as a new one."),
        Triple("🗑", "Delete", "Remove this exclusion."),
        Triple("🧽", "Reset", "Reset the add / edit form to its starting values."),
    ),
    "crud_blocked_models" to listOf(
        Triple("🆕", "Add", "Block another model."),
        Triple("✏️", "Edit", "Edit this blocked model."),
        Triple("👯", "Duplicate", "Duplicate this entry as a new one."),
        Triple("🗑", "Delete", "Unblock this model (remove the entry)."),
        Triple("🧽", "Reset", "Reset the add / edit form to its starting values."),
    ),
    "crud_inaccessible_models" to listOf(
        Triple("🆕", "Add", "Mark another model inaccessible."),
        Triple("🧹", "Housekeeping", "Jump to the related Housekeeping screen."),
        Triple("✏️", "Edit", "Edit this entry."),
        Triple("👯", "Duplicate", "Duplicate this entry as a new one."),
        Triple("🗑", "Delete", "Remove this entry (give the model another chance)."),
        Triple("🧽", "Reset", "Reset the add / edit form to its starting values."),
    ),
    "crud_parameters" to listOf(
        Triple("🆕", "Add", "Add a new parameter preset."),
        Triple("✏️", "Edit", "Edit this preset."),
        Triple("👯", "Duplicate", "Duplicate this preset as a new one."),
        Triple("🗑", "Delete", "Delete this preset."),
    ),
    "crud_system_prompts" to listOf(
        Triple("🆕", "Add", "Add a new system prompt."),
        Triple("✏️", "Edit", "Edit this system prompt."),
        Triple("👯", "Duplicate", "Duplicate this system prompt as a new one."),
        Triple("🗑", "Delete", "Delete this system prompt."),
    ),
    "crud_example_prompts" to listOf(
        Triple("🆕", "Add", "Add a new example prompt."),
        Triple("✏️", "Edit", "Edit this example prompt."),
        Triple("👯", "Duplicate", "Duplicate this example prompt as a new one."),
        Triple("🗑", "Delete", "Delete this example prompt."),
    ),
    "crud_internal_prompts" to listOf(
        Triple("🆕", "Add", "Add a new prompt (only for the user-editable categories)."),
        Triple("✏️", "Edit", "Edit this internal prompt."),
        Triple("👯", "Duplicate", "Duplicate this prompt as a new one (user-editable categories)."),
        Triple("🗑", "Delete", "Delete this prompt (user-editable categories)."),
    ),
    "crud_agents" to listOf(
        Triple("🆕", "Add", "Add a new agent."),
        Triple("✏️", "Edit", "Edit this agent."),
        Triple("👯", "Duplicate", "Duplicate this agent as a new one."),
        Triple("🗑", "Delete", "Delete this agent."),
    ),
    "crud_flocks" to listOf(
        Triple("🆕", "Add", "Add a new flock."),
        Triple("✏️", "Edit", "Edit this flock."),
        Triple("👯", "Duplicate", "Duplicate this flock as a new one."),
        Triple("🗑", "Delete", "Delete this flock."),
    ),
    "crud_swarms" to listOf(
        Triple("🆕", "Add", "Add a new swarm."),
        Triple("✏️", "Edit", "Edit this swarm."),
        Triple("👯", "Duplicate", "Duplicate this swarm as a new one."),
        Triple("🗑", "Delete", "Delete this swarm."),
    ),
)

/** Topics whose screen shows MORE than 3 icons → a standalone ❔ icon page
 *  (and the ❔ bottom-bar glyph). Everything else in [SCREEN_ICON_HELP]
 *  embeds its table inline under the main help page. */
internal val ICON_HELP_AS_PAGE: Set<String> = setOf(
    "report_run", "report_new",
    "agent_edit", "flock_edit", "swarm_edit",
)

/** Base-topic titles for the auto-built icon pages; declared before
 *  [ICON_HELP_TOPIC_CONTENT] (top-level vals initialise in order). Kept
 *  local to avoid a cycle with the full HELP_TOPICS map assembly. */
private val HELP_TOPICS_BASE_TITLES: Map<String, String> = mapOf(
    "report_run" to "Report — manage",
    "report_new" to "New AI report",
    "agent_edit" to "Agent",
    "flock_edit" to "Flock",
    "swarm_edit" to "Swarm",
)

/** Auto-built "<topic>_icons" HelpContent (empty — the table is rendered
 *  by HelpScreen) for every page topic, so HELP_TOPICS.containsKey(...)
 *  succeeds and the ❔ glyph + cross-links light up. */
internal val ICON_HELP_TOPIC_CONTENT: Map<String, HelpContent> =
    ICON_HELP_AS_PAGE.associate { topic ->
        "${topic}_icons" to HelpContent(
            title = (HELP_TOPICS_BASE_TITLES[topic] ?: "Icons") + " — icons",
            cards = emptyList()
        )
    }
