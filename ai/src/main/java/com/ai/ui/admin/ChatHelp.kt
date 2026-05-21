package com.ai.ui.admin

internal val chatHelp: Map<String, HelpContent> = mapOf(
    "chat_search" to HelpContent(
        title = "Help - Search chats",
        cards = listOf(
            HelpCard("Overview", "Full-text search over saved chat sessions. Reached from the Chat History list's search icon."),
            HelpCard("Search field", "Filters by anything: session title, message content, model, provider name, system prompt. The match is substring + case-insensitive."),
            HelpCard("Result rows", "One row per matching session with a content excerpt around the first match. Tap to resume the session at the matched message."),
            HelpCard("Empty state", "Empty query shows the most recent N sessions; non-matching query shows 'No chats matching <query>'."),
            HelpCard("Pitfalls", "Search reads + parses every chat session JSON on every keystroke — a debounce keeps it acceptable on slow storage but heavy histories may still feel jittery. Prefer the list's date / pinned filters when you can."),
        )
    ),
    "chat_hub" to HelpContent(
        title = "Help - AI Chat",
        cards = listOf(
            HelpCard("Overview", "Landing screen for everything chat-shaped. Top section starts a new conversation; below it, pinned and recent sessions plus tools to continue, search, or manage existing chats."),
            HelpCard("Unfinished pill", "When at least one chat ended on a user turn with no assistant reply (you navigated away mid-stream), an envelope pill appears at the very top with a Resume link to the most recent such session."),
            HelpCard("Start card", "Four entry points stacked in one card: New Chat with Agent (greyed when no agent has a key + active provider), New Chat – Configure On The Fly (pick provider/model/parameters at start), Dual AI Chat (two models trade turns), and Start with photo (camera capture, image rides into the first user turn)."),
            HelpCard("Continue Existing Chat", "Opens the full chat-history list. Disabled and dimmed when no sessions exist yet. Pinned + Recent below give you a faster jump for the top sessions."),
            HelpCard("Pinned and Recent cards", "Pinned holds every session you marked with the pin chip; Recent shows the next three by updated time. Each row is the first user-message preview; tap to resume."),
            HelpCard("Search Chats", "Free-text search across every saved message — opens the dedicated search screen."),
            HelpCard("Manage", "Bulk-prune by age and zip-export of every chat-history JSON. Disabled when no chats exist."),
            HelpCard("Title-bar icons", "Only the always-on Help (?) and Home icons. No reload, info, delete, or trace from this hub — those live on the per-session screens."),
            HelpCard("Tips", "The hub re-reads chat history on every resume tick, so deleting / pinning a session elsewhere shows up when you come back. The camera capture clears any previous photo error before launching.")
        )
    ),
    "chat_session" to HelpContent(
        title = "Help - Chat",
        cards = listOf(
            HelpCard("Overview", "Live single-model conversation. Messages stream chunk-by-chunk; the input box clears the moment Send fires; the assistant bubble bottom-anchors so short conversations sit just above the input row instead of pinned at the top."),
            HelpCard("Header row", "Provider / model label is clickable and opens Model Info. To its right: a Pin chip. The pin state is written to the session record immediately, so the hub reflects it without waiting for the next message save."),
            HelpCard("Web search chip", "Per-turn 🌐 toggle. When on, an OR with the Parameters preset's searchEnabled drives the request, and the LiteLLM tool-use overhead (~3-4k extra system tokens for Claude with web_search) is folded into the cost estimate."),
            HelpCard("Reasoning chip", "Only shown when LiteLLM, models.dev, or the model id family (o1/o3/o4/gpt-5, anything with thinking/reasoning in the name) marks the model as supporting it. Levels come from the provider's self-reported capabilities when available, otherwise the legacy low/medium/high set."),
            HelpCard("Validate input chip", "Tap once to pick a moderation model; while set, every Send first runs the input through callModerationApi. A clean classification proceeds silently. A flagged result pops a Proceed-anyway / Cancel dialog with the fired categories. API errors fail-open: the message is still sent and the orange Moderation: error line is shown."),
            HelpCard("Attach + send", "📎 opens the SAF picker for an image; the thumbnail + mime type appears above the input row with a Remove button. A red warning fires when the model isn't flagged vision-capable. Send is disabled while streaming, while moderation runs, and when the input is empty without an image."),
            HelpCard("Trace icons", "Title bar's ℹ️ jumps to Model Info. Each finished assistant bubble carries a 🐞 that opens the matching trace (closest timestamp, same model, no reportId). The flagged-input dialog also shows a 🐞 when a moderation trace was captured. All trace icons are suppressed when API tracing is off in Settings."),
            HelpCard("Cost meter", "Live total in cents shown next to the Back button after the first turn — running sum of input tokens × promptPrice + output tokens × completionPrice for this session."),
            HelpCard("Pitfalls", "Cancellation on back-press deliberately doesn't append a [Stream interrupted] line — the partial chunks aren't an error from your perspective. Real exceptions during streaming do append the partial response with the error message. System-prompt changes from Parameters now apply mid-session — the previous flow only seeded on an empty messages list.")
        )
    ),
    "chat_parameters" to HelpContent(
        title = "Help - Chat Parameters",
        cards = listOf(
            HelpCard("Overview", "Pre-flight setup screen for a configure-on-the-fly chat. Pick optional presets, optionally override individual fields, then Start Chat hands the resolved ChatParameters to the session screen."),
            HelpCard("Provider/model line", "Read-only label under the title bar. Clickable — taps open Model Info for the picked (provider, model)."),
            HelpCard("System Prompt button", "Opens a single-select dialog over Settings.systemPrompts. Picking one fills the System prompt text field; typing in the field clears the selection so the manual text wins."),
            HelpCard("Parameters button", "Multi-select over Settings.parameters presets. Selected presets are merged via Settings.mergeParameters into a single ChatParameters; manual fields below override per-field."),
            HelpCard("Per-field overrides", "Temperature, Max tokens, Top P, Top K, Frequency penalty, Presence penalty — each takes a free-form number. Empty falls back to the merged preset value, or null if no preset is set. Invalid input also falls back."),
            HelpCard("Citations + recency", "Return citations defaults on; Search recency takes day / week / month / year. Web search itself moved to a per-turn 🌐 chip on the session screen — the preset's searchEnabled is OR'd with the chip at send time."),
            HelpCard("Start Chat", "Builds the ChatParameters with the resolved system prompt, then navigates to the session. Note: 🌐 doesn't exist here on purpose — flip it from inside the chat."),
            HelpCard("Tips", "Both selector buttons turn purple-tinted when set, so you can see at a glance whether a preset is active. Selecting a system prompt also dumps its text into the editable field, so you can preview/edit it before starting."),
            HelpCard("Pitfalls", "Edits to Settings.parameters elsewhere don't migrate into an already-running session — they're resolved once at Start Chat. Restart the chat to pull new preset values.")
        )
    ),
    "chat_history" to HelpContent(
        title = "Help - Chat History",
        cards = listOf(
            HelpCard("Overview", "Paged list of every saved chat session. Each row shows first user-message preview, provider · model, and last-updated date. Tap a row to resume that session."),
            HelpCard("Pagination", "Page size auto-fits the screen height (rows of ~80dp). Previous / Next buttons sit above and below the list with the current page indicator and total chat count between them."),
            HelpCard("Trace icon per row", "🐞 appears between the row and the > caret when at least one chat-turn trace was tagged with this sessionId. Tap it to open the full trace list filtered to this session."),
            HelpCard("Empty state", "When ChatHistoryManager has no sessions, the screen renders \"No chat history yet\" centered — the Continue Existing Chat card on the hub is also disabled in this state."),
            HelpCard("Resume behaviour", "Tapping a row navigates to AI_CHAT_CONTINUE/{sessionId}, which reopens the same ChatSessionScreen with the stored messages, parameters, attached KBs, pinned flag, and persisted web-search / reasoning toggle."),
            HelpCard("Title-bar icons", "Help and Home only. Bulk delete is on the dedicated Manage screen reachable from the hub, not from here."),
            HelpCard("Tips", "The list re-fetches whenever ChatHistoryManager.historyVersion ticks (after a save / delete / pin elsewhere). Page index is rememberSaveable so it survives a config change."),
            HelpCard("Pitfalls", "The trace probe runs once per row — if you record a new trace for an open session, the icon doesn't appear until you leave and return."),
        )
    ),
    "chat_continue" to HelpContent(
        title = "Help - Chat",
        cards = listOf(
            HelpCard("Overview", "Same screen as Chat session, but seeded with the stored messages, parameters, pinned flag, and persisted web-search / reasoning effort from the saved session record."),
            HelpCard("State you keep", "ChatHistoryManager.loadSession brings back: every message (system prompt repinned in place if Parameters changed), the original ChatParameters, and the pinned flag."),
            HelpCard("Toggles you can flip", "Web search and reasoning effort chips are read from the persisted ChatParameters and are saved back on every turn — the next save uses your current chip state, not the original preset."),
            HelpCard("System prompt update", "If the underlying Settings system-prompt template changed since you last opened the session, the system message is rewritten in-place on the next turn so the new prompt takes effect."),
            HelpCard("Session id", "AI_CHAT_CONTINUE/{sessionId} carries the id; the screen treats it as the current session id so saves overwrite the same record. New messages append to the existing JSON."),
            HelpCard("Title-bar icons", "Same as a fresh chat — ℹ️ jumps to Model Info, Help and Home are always present. The Back button shows the running cost-in-cents for this session next to it."),
            HelpCard("Tips", "Trace icons on existing assistant bubbles probe the on-disk trace store by closest timestamp + same model + no reportId; old assistant turns from before tracing was on still have no icon."),
            HelpCard("Pitfalls", "Switching the underlying model is not supported mid-session — start a new chat instead. Editing a Parameters preset elsewhere doesn't migrate into the resumed session because the persisted ChatParameters record was built at Start Chat time."),
        )
    ),
    "chat_manage" to HelpContent(
        title = "Help - Manage chats",
        cards = listOf(
            HelpCard("Overview", "Two housekeeping actions for chat history: bulk-delete sessions older than N days, and zip-export every chat-history JSON for backup or sharing."),
            HelpCard("Delete old chats card", "Number-only field for the cutoff (max 4 digits). Pinned chats are skipped; the helper line under the field reminds you. Defaults to 30 days; the Delete button is enabled only when the value parses to a positive integer."),
            HelpCard("Confirm dialog", "Loads matching sessions off the UI thread and shows the actual count: \"Delete N chats?\". Title displays \"Loading…\" briefly while the scan runs (long histories can take a moment)."),
            HelpCard("Export all card", "Zips every file in filesDir/chat-history/ into a timestamped archive (ai_chats_backup_YYMMDD_HHMMSS.zip) under cacheDir, then opens the system share sheet via FileProvider. Status line below shows the chat count once bundled."),
            HelpCard("Status line", "After any operation, a single status string at the bottom: \"Zipping chats…\" / \"Bundled N chats.\" / \"Deleted N chats.\" / \"Nothing to export.\""),
            HelpCard("Title-bar icons", "Help and Home only — no per-screen reload, info, delete, or trace icons here."),
            HelpCard("Tips", "The Working… button label tells you when an export is in flight; the Delete button is disabled at the same time, so you can't kick off a parallel scan."),
            HelpCard("Pitfalls", "Pinned chats are excluded from the bulk delete — to remove a pinned chat you have to unpin it from inside the session first. Deletes are immediate and cannot be undone."),
        )
    ),
    "dual_chat_setup" to HelpContent(
        title = "Help - Dual AI Chat",
        cards = listOf(
            HelpCard("Overview", "Configures two models that take turns chatting about a subject. State is persisted to a dedicated SharedPreferences (dual_chat_prefs) so reopening the screen restores your last configuration."),
            HelpCard("Model 1 card (blue)", "Tap the model button to drill into the active-providers picker, then the model picker. System Prompt and Parameters preset buttons sit below — both turn purple-tinted when set."),
            HelpCard("Swap button", "Center row, ⬅ Swap ➡. Swaps Model 1 and Model 2 wholesale (provider, model, parameters preset, system prompt) — useful when you realize you wanted them in the other order."),
            HelpCard("Model 2 card (green)", "Same controls as Model 1 in a different colour so the two sides stay visually distinct."),
            HelpCard("Subject + Rounds", "Subject is the topic both models will talk about. Rounds caps the conversation; the default is 10. Cost grows roughly linearly with rounds × per-turn tokens."),
            HelpCard("Prompt templates", "1st prompt seeds round one and supports %subject%. 2nd prompt fires from Model 2 in round one and supports %answer% (Model 1's reply). From round three onward, the previous response is forwarded directly with no template wrapping."),
            HelpCard("Go button", "Saves the prefs blob, resolves both Parameters preset chains, snapshots both system prompts to text, and starts the session. Disabled until both models, both names, the subject, and a positive Rounds value are set."),
            HelpCard("Title-bar icons", "Help and Home only — provider / model selection happens via full-screen overlays, not via title-bar icons."),
            HelpCard("Tips", "DisposableEffect saves your prefs on screen exit, so back-navigating mid-edit doesn't lose work. Model and provider selection share the same overlay screens used elsewhere — rich pricing columns and badges included.")
        )
    ),
    "dual_chat_session" to HelpContent(
        title = "Help - Dual Chat",
        cards = listOf(
            HelpCard("Overview", "Both models alternate turns automatically until the round budget is hit. Bubbles align left for Model 1 (blue) and right for Model 2 (green). The screen drives a single coroutine job — leaving the screen cancels it via DisposableEffect."),
            HelpCard("Cost row", "Three-column running tally just under the title bar: Model 1, Model 2, Total cost in cents. Recomputed via derivedStateOf from per-side input/output token sums × per-side pricing."),
            HelpCard("Progress line", "Below the cost row: \"Interaction X / N — Subject: …\". X bumps after both models replied for that round."),
            HelpCard("Thinking pill", "While a model is in flight, a small \"Model N is thinking…\" pill renders aligned to that side. Replaced by the actual reply once received."),
            HelpCard("Stop button", "Fires while running — cancels the chat job. The job's withTracerTags finally restores the previous tracer tag pair on its way out, so no manual cleanup is needed."),
            HelpCard("Continue more", "After Stop or after the round budget is reached, an Extra chats field + \"Chat N more\" button appears. The new total is currentInteraction + N; the loop resumes from where it stopped."),
            HelpCard("Title-bar icons", "ℹ️ pops a two-row picker (\"Provider — model\" for each side); tapping a row jumps to that model's Model Info. Help and Home are always-on."),
            HelpCard("Per-bubble 🐞", "Each bubble's ladybug opens the trace tagged with this session id and the bubble's model, with the closest timestamp — same model speaking again gets a different trace. Suppressed entirely when API tracing is off in Settings."),
            HelpCard("Tips", "Provider / model labels in each bubble are click-targets for Model Info too. The session id is prefixed with dualchat_ + start time so traces from this run are easy to find."),
            HelpCard("Pitfalls", "If either provider has no API key configured the call will error and the loop stops. Errors render in red below the message list and the run flips to the stopped state.")
        )
    ),
)
