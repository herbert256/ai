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
 * TitleBar handler, never guess (e.g. the 🔗 launcher on Manage adds a Meta
 * analysis, it does NOT start a new report; 🌐 / 🏆 / 🚦 on Manage launch
 * Translate / Rerank / Moderation, not their content-screen meanings).
 */
/** Shared by the six icon-lookup screens (main / agent / meta /
 *  translation / language / pair) — same screen, same icons. */
private val ICON_LOOKUP_ROWS = listOf(
    Triple("💬", "Chat", "Continue this icon's source response in a chat."),
    Triple("ℹ️", "Information", "Open the generating model's info."),
    Triple("📋", "Copy", "Copy the API request / response text."),
    Triple("📤", "Share", "Share the API request / response text."),
    Triple("🐞", "Trace", "Open the API trace for this icon generation."),
)

internal val SCREEN_ICON_HELP: Map<String, List<Triple<String, String, String>>> = mapOf(

    // ===== Report flow (top level) =====
    "reports_hub" to listOf(
        Triple("🆕", "New report", "Start a new AI report."),
        Triple("🔍", "Search reports", "Search across all your reports."),
        Triple("🗂️", "All reports", "Browse the full list of reports."),
        Triple("📥", "Import report", "Pick a report .zip and import it as a new report."),
    ),
    "report_run" to listOf(
        Triple("🆕", "New report", "Open the New Report start screen — New report, Start with a previous report, or Start with an example prompt."),
        Triple("🔗", "Meta", "Add a meta analysis to this report: a Meta prompt over the answers, or Compare with meta."),
        Triple("🔱", "Fan Out", "Open this report's Fan Out, or start a new one when none exists yet."),
        Triple("🥊", "Tournament", "Head-to-head tools: run a Tournament over the answers, or Judge the judges."),
        Triple("🌐", "Translate", "Translate the report into one or more other languages."),
        Triple("🏆", "Rerank", "Rank the answers best-first — opens the report's rerank, or picks a model to run one."),
        Triple("🚦", "Moderation", "Safety-check the answers — opens the report's moderation, or picks a model to run one."),
        Triple("💬", "Chat", "Start a chat seeded with this report's prompt."),
        Triple("🗂️", "Switch report", "Pick another report to manage."),
        Triple("ℹ️", "Information", "Open the per-report info screen."),
        Triple("📌", "Pin / unpin", "Keep this report at the top of the lists (orange when pinned)."),
        Triple("👷", "Setup", "Re-open Report - setup to change who handles report info, model info and the worker batches."),
        Triple("🔤", "Row labels", "Toggle the report / meta / rerank / moderation rows between their titles and the model name."),
        Triple("📤", "Export", "Export / share the report (once the run has completed)."),
        Triple("👯", "Duplicate", "Make a copy of this report."),
        Triple("👁", "View", "Open the per-agent results / View hub for this report."),
        Triple("✍️", "Add note", "Attach a free-text note to this report."),
        Triple("📒", "Notes", "Show every user note in this report."),
        Triple("✏️", "Edit", "Change the prompt, title, models, parameters or system prompt."),
        Triple("🔄", "Regenerate", "Re-run every agent and existing operation (once the run has completed)."),
        Triple("🗑", "Delete", "Delete this report (asks to confirm)."),
        Triple("🐞", "Trace", "API traces for this report (each agent row has its own 🐞)."),
    ),
    "report_notes" to listOf(
        Triple("✍️", "Add note", "Add a new note to this report. Edit (✏️) and delete (🗑) live on each note card."),
    ),
    "report_agent_chat" to listOf(
        Triple("🎭", "System prompt", "Pick the system prompt used for the next reply."),
        Triple("🌡️", "Parameters", "Pick the parameter preset(s) used for the next reply."),
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

    // ===== Provider edit (page) =====
    "provider_edit" to listOf(
        Triple("ℹ️", "Information", "Open this provider's reference help page."),
        Triple("🌡️", "Parameters", "Pick the parameter preset(s) this provider uses by default."),
        Triple("🎭", "System prompt", "Pick this provider's default system prompt."),
        Triple("🐞", "Trace", "Open the API trace from this provider's last in-page Test."),
    ),

    // ===== Chat =====
    "chat_parameters" to listOf(
        Triple("🌡️", "Parameters", "Pick the parameter preset(s) for this chat."),
        Triple("🎭", "System prompt", "Pick the system prompt for this chat."),
    ),
    "dual_chat_setup" to listOf(
        Triple("🌡️", "Parameters", "Pick parameters for one of the two models (you choose which)."),
        Triple("🎭", "System prompt", "Pick the system prompt for one of the two models (you choose which)."),
    ),
    "chat_session" to listOf(
        Triple("ℹ️", "Information", "Open the chatting model's info."),
    ),
    "dual_chat_session" to listOf(
        Triple("ℹ️", "Information", "Open one of the two models' info (you choose which)."),
    ),
    "chat_history" to listOf(
        Triple("🧹", "Housekeeping", "Jump to the related Housekeeping screen."),
    ),

    // ===== Report flow (other) =====
    "report_select_models" to listOf(
        Triple("🌡️", "Parameters", "Configure API parameters: pick the preset(s) for this report."),
        Triple("🎭", "System prompt", "Pick the system prompt for this report."),
        Triple("🧽", "Clear", "Clear every selected model (shown once at least one is picked)."),
    ),
    "report_pick_model" to listOf(
        Triple("🌡️", "Parameters", "Pick parameters for this one-off operation (secondary ops only)."),
        Triple("🎭", "System prompt", "Pick the system prompt for this one-off operation (secondary ops only)."),
    ),
    "report_meta" to listOf(
        Triple("👁", "View", "Open this meta result in the View screen."),
    ),
    "report_translations" to listOf(
        Triple("🆕", "New translation", "Start a new translation — pick a target language, then the model(s)."),
    ),
    "translation_run_l2" to listOf(
        Triple("ℹ️", "Information", "Open the translating model's info."),
        Triple("👁", "View", "Open this translation in the View screen."),
    ),
    "secondary_fan_out_onepage" to listOf(
        Triple("ℹ️", "Information", "Open the model's info."),
        Triple("👁", "View", "Open the one-page fan-out view."),
    ),
    "regenerate_batch" to listOf(
        Triple("🗑", "Delete", "Cancel and delete this regenerate job."),
    ),
    "report_edit_short_title" to listOf(
        Triple("🐞", "Trace", "Open the API trace from generating the short title."),
    ),
    "report_edit_long_title" to listOf(
        Triple("🐞", "Trace", "Open the API trace from generating the long title."),
    ),
    "report_edit_model_title" to listOf(
        Triple("🐞", "Trace", "Open the API trace from generating this model's title."),
    ),

    // ===== Setup / refresh / test / cost hubs =====
    "setup_models" to listOf(
        Triple("🧹", "Housekeeping", "Jump to the related Housekeeping screen."),
    ),
    "providers" to listOf(
        Triple("🧹", "Housekeeping", "Jump to the related Housekeeping screen."),
    ),
    "refresh" to listOf(
        Triple("⚙️", "Settings", "Jump to the related AI Setup / Settings screen."),
    ),
    "test" to listOf(
        Triple("⚙️", "Settings", "Jump to the related AI Setup / Settings screen."),
    ),
    "cost_config" to listOf(
        Triple("⚙️", "Settings", "Jump to the related AI Setup / Settings screen."),
    ),
    "test_all_models_l1" to listOf(
        Triple("⚙️", "Settings", "Open the Test-excluded models settings."),
        Triple("🐞", "Trace", "Open the API traces for this test run."),
    ),
    "test_all_models_l2" to listOf(
        Triple("ℹ️", "Information", "Open this provider's info."),
    ),
    "test_all_models_l3" to listOf(
        Triple("ℹ️", "Information", "Open this model's info."),
        Triple("🐞", "Trace", "Open the API trace from testing this model."),
    ),

    // ===== Knowledge / history / reset =====
    "knowledge_detail" to listOf(
        Triple("🗑", "Delete", "Delete this knowledge base (asks to confirm)."),
    ),
    "prompt_history" to listOf(
        Triple("🧽", "Clear", "Clear the entire prompt history (shown when it isn't empty)."),
    ),
    "reset_runtime" to listOf(
        Triple("🧽", "Clear", "Clear runtime data — history, chats, traces, reports, stats. Asks to confirm; keeps config & API keys."),
    ),
    "reset_info_providers" to listOf(
        Triple("🧽", "Clear", "Clear the cached Info-provider pricing. Asks to confirm; refetched on the next Refresh."),
    ),
    "reset_configuration" to listOf(
        Triple("🧽", "Clear", "Clear all configuration (providers, agents, prompts, keys…). Asks to confirm; keeps reports & chats."),
    ),
    "reset_application" to listOf(
        Triple("🧽", "Clear", "Factory-reset the app (only API keys are kept). Asks to confirm."),
    ),

    // ===== Deep report drill-ins (pages) =====
    "report_single_result" to listOf(
        Triple("💬", "Chat", "Continue this response in a chat."),
        Triple("ℹ️", "Information", "Open this model's info."),
        Triple("📋", "Copy", "Copy the response text."),
        Triple("📤", "Share", "Share the response text."),
        Triple("👁", "View", "Open the full per-agent View."),
        Triple("🗣️", "Refine", "Chat with this model to refine its answer; Apply folds a reply back into the report."),
        Triple("🎲", "Temperature sweep", "Run three replay calls at different temperatures and choose one response."),
        Triple("🧠", "Reasoning Effort", "Run replay calls with None, Low, Medium and High reasoning effort and choose one response."),
        Triple("🧭", "Web search replay", "Replay this model response with web search enabled and choose the fresh response."),
        Triple("✍️", "Add note", "Attach a free-text note to this model response."),
        Triple("🌐", "Compare", "Show the original and its translation side by side."),
        Triple("🔄", "Regenerate", "Re-run this single model."),
        Triple("🗑", "Delete", "Remove this response (with multiple languages, just the active one)."),
        Triple("🐞", "Trace", "Open the API trace for this response."),
    ),
    "content_model_response" to listOf(
        Triple("💬", "Chat", "Continue this response in a chat."),
        Triple("ℹ️", "Information", "Open this model's info."),
        Triple("📋", "Copy", "Copy the response text."),
        Triple("📤", "Share", "Share the response text."),
        Triple("👁", "View", "Open the full per-agent View."),
        Triple("🌐", "Compare", "Show the original and its translation side by side."),
        Triple("🔄", "Regenerate", "Re-run this single model."),
        Triple("🗑", "Delete", "Remove this response (with multiple languages, just the active one)."),
        Triple("🐞", "Trace", "Open the API trace for this response."),
    ),
    "secondary_detail" to listOf(
        Triple("ℹ️", "Information", "Open the model's info."),
        Triple("📋", "Copy", "Copy this result's text."),
        Triple("📤", "Share", "Share this result's text."),
        Triple("👁", "View", "Open this result in the View screen."),
        Triple("💬", "Chat", "Continue this analysis in a chat (opens the Chat section; Meta results only)."),
        Triple("🗣️", "Refine", "Chat with the model to refine this analysis; Apply folds a reply back into the report (Meta results only)."),
        Triple("✍️", "Add note", "Attach a free-text note to this meta / rerank / moderation result."),
        Triple("🌐", "Compare", "Show the original and its translation side by side."),
        Triple("🗑", "Delete", "Delete this result (with multiple languages, just the active one)."),
        Triple("🐞", "Trace", "Open the API trace for this result."),
    ),
    "prompt_view" to listOf(
        Triple("📋", "Copy", "Copy the prompt text."),
        Triple("📤", "Share", "Share the prompt text."),
        Triple("👁", "View", "Open the prompt in the View screen."),
        Triple("🌐", "Compare", "Show the prompt and its translation side by side."),
        Triple("🐞", "Trace", "Open the API trace tied to the prompt."),
    ),
    "secondary_list" to listOf(
        Triple("ℹ️", "Information", "Open the selected result's model info (meta-picker mode)."),
        Triple("👁", "View", "Open the secondary results in the View screen."),
        Triple("🗑", "Delete", "Delete the selected result (meta-picker mode)."),
        Triple("🐞", "Trace", "Open the API trace for the selected result (meta-picker mode)."),
    ),
    "secondary_fan_out_l1" to listOf(
        Triple("👁", "View", "Open the fan-out in the View screen."),
        Triple("✍️", "Add note", "Attach a free-text note to this fan-out run."),
        Triple("🔄", "Regenerate", "Re-run the whole fan-out."),
        Triple("🗑", "Delete", "Delete this fan-out run."),
        Triple("🐞", "Trace", "Open the API trace for the fan-out."),
    ),
    "secondary_fan_out_l2" to listOf(
        Triple("ℹ️", "Information", "Open this model's info."),
        Triple("👯", "Switch role", "Swap this model between Initiator and Responder (fan-out mode)."),
        Triple("👁", "View", "Open this model's fan-out in the View screen."),
        Triple("🗑", "Delete", "Delete this model's fan-out rows."),
        Triple("🐞", "Trace", "Open the API trace."),
    ),
    "secondary_fan_out_l3" to listOf(
        Triple("ℹ️", "Information", "Open the answering model's info."),
        Triple("👁", "View", "Open this pair in the View screen."),
        Triple("🗣️", "Refine", "Chat with this model to refine its answer; Apply folds a reply back into the report."),
        Triple("✍️", "Add note", "Attach a free-text note to this fan-out response."),
        Triple("🔄", "Regenerate", "Re-run this initiator → responder pair."),
        Triple("🗑", "Delete", "Delete this pair."),
        Triple("🐞", "Trace", "Open the API trace for this pair."),
    ),
    "translation_run_l1" to listOf(
        Triple("👁", "View", "Open this translation in the View screen."),
        Triple("🐜", "Workers", "Open the per-model worker breakdown."),
        Triple("🔄", "Regenerate", "Re-run the whole translation."),
        Triple("🗑", "Delete", "Delete this translation run."),
        Triple("🐞", "Trace", "Open the API trace for the translation."),
    ),
    "translation_run_l3" to listOf(
        Triple("ℹ️", "Information", "Open the translating model's info."),
        Triple("📋", "Copy", "Copy the translated text."),
        Triple("📤", "Share", "Share the translated text."),
        Triple("👁", "View", "Open this translation call in the View screen."),
        Triple("🗑", "Delete", "Delete this translation call."),
        Triple("🐞", "Trace", "Open the API trace for this call."),
    ),
    "cost_view" to listOf(
        Triple("👁", "View", "Open the costs in the View screen."),
        Triple("🐞", "Trace", "Open the API trace tied to a call."),
    ),
    "icon_lookup_main" to ICON_LOOKUP_ROWS,
    "icon_lookup_agent" to ICON_LOOKUP_ROWS,
    "icon_lookup_meta" to ICON_LOOKUP_ROWS,
    "icon_lookup_translation" to ICON_LOOKUP_ROWS,
    "icon_lookup_language" to ICON_LOOKUP_ROWS,
    "icon_lookup_pair" to ICON_LOOKUP_ROWS,
)

/** Generic name + one-line purpose for each standard bottom-bar glyph,
 *  keyed by the exact emoji used in `buildBottomBarIcons`. Used as the
 *  FALLBACK by the live "<screen> - icons" overlay when a screen has no (or
 *  an incomplete) per-screen [SCREEN_ICON_HELP] legend, so every row still
 *  shows an icon + name + description. Per-screen entries take precedence. */
internal val DEFAULT_BAR_ICON_HELP: Map<String, Pair<String, String>> = mapOf(
    "🆕" to ("Create" to "Add a new item or operation."),
    "🔗" to ("Meta" to "Add a meta analysis (Meta / Compare with meta)."),
    "🔱" to ("Fan Out" to "Open or start a Fan Out."),
    "🥊" to ("Tournament" to "Head-to-head Tournament / Judge the judges."),
    "🏆" to ("Rerank" to "Rank the answers best-first."),
    "🚦" to ("Moderation" to "Safety-check the answers."),
    "💬" to ("Chat" to "Start a chat from here."),
    "🗂️" to ("Switch" to "Pick another report to work on."),
    "🐜" to ("Workers" to "Open the per-worker (model) breakdown for this batch."),
    "🏅" to ("Rank translators" to "Rank the models that produced this translation (start or open the batch)."),
    "🔧" to ("Manage" to "Open the manage screen."),
    "🧹" to ("Housekeeping" to "Jump to the related Housekeeping screen."),
    "⚙️" to ("AI Setup" to "Jump to the related AI Setup screen."),
    "📈" to ("Statistics" to "Open the related statistics screen."),
    "ℹ️" to ("Info" to "Show model / item details."),
    "🌡️" to ("Parameters" to "Pick the generation-parameters preset."),
    "🎲" to ("Temperature sweep" to "Re-run at several temperatures and pick a response."),
    "🎭" to ("System prompt" to "Pick the system prompt."),
    "🧽" to ("Clear" to "Clear the form."),
    "📎" to ("Attach" to "Attach an image."),
    "🚩" to ("Validate" to "Validate the prompt with a moderation model."),
    "👷" to ("Worker configuration" to "Open Report - setup: choose the report's system prompt and parameters, who generates the report info (icon, titles, language), the per-model info (icons & titles), which pool the worker batches (Tournament / Judges / Compare / Translation / Fan Meta) draw from, and — on its own Meta card — the pool for Meta + Fan-in. Includes Report models with When-available or Round-robin selection. Rerank and Moderation always use their own prompt's workers."),
    "📋" to ("Copy" to "Copy to the clipboard."),
    "📌" to ("Pin" to "Pin to the top of the lists."),
    "📤" to ("Export" to "Export or share."),
    "📥" to ("Import" to "Import a report from a file."),
    "👯" to ("Duplicate" to "Make a copy."),
    "👁" to ("View" to "Open the View screen."),
    "🌐" to ("Compare" to "Open the translation compare view."),
    "📝" to ("Memo" to "Open the memo."),
    "✏️" to ("Edit" to "Edit the prompt, title or models."),
    "🔄" to ("Regenerate" to "Re-run / reload."),
    "🗑" to ("Delete" to "Delete this item (asks to confirm)."),
    "🐞" to ("Trace" to "Open the API trace(s)."),
    "1️⃣" to ("Report - manage" to "Go to the Report-manage screen (greyed when you're on another of the three report screens)."),
    "2️⃣" to ("Report - titles/icons/..." to "Go to the titles/icons/... screen (icon / title / language job status)."),
    "3️⃣" to ("Report - second results" to "Go to the second-results screen (Meta / Fan-out / Tournament / Translation / Rerank / Moderation)."),
)

/** Topics whose screen shows MORE than 3 icons → a standalone ❔ icon page
 *  (and the ❔ bottom-bar glyph). Everything else in [SCREEN_ICON_HELP]
 *  embeds its table inline under the main help page. */
internal val ICON_HELP_AS_PAGE: Set<String> = setOf(
    "report_run", "report_new",
    "agent_edit", "flock_edit", "swarm_edit",
    "provider_edit",
    // deep report drill-ins
    "report_single_result", "content_model_response", "secondary_detail",
    "prompt_view", "secondary_list",
    "secondary_fan_out_l1", "secondary_fan_out_l2", "secondary_fan_out_l3",
    "translation_run_l1", "translation_run_l3",
    "icon_lookup_main", "icon_lookup_agent", "icon_lookup_meta",
    "icon_lookup_translation", "icon_lookup_language", "icon_lookup_pair",
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
    "provider_edit" to "Provider",
    "report_single_result" to "Model response",
    "content_model_response" to "Model response",
    "secondary_detail" to "Secondary detail",
    "prompt_view" to "Prompt",
    "secondary_list" to "Secondary results",
    "secondary_fan_out_l1" to "Fan out",
    "secondary_fan_out_l2" to "Fan out — model",
    "secondary_fan_out_l3" to "Fan out — pair",
    "translation_run_l1" to "Translation",
    "translation_run_l3" to "Translation call",
    "icon_lookup_main" to "Icon lookup",
    "icon_lookup_agent" to "Icon lookup",
    "icon_lookup_meta" to "Icon lookup",
    "icon_lookup_translation" to "Icon lookup",
    "icon_lookup_language" to "Icon lookup",
    "icon_lookup_pair" to "Icon lookup",
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
