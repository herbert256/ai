package com.ai.ui.admin

internal val chatHelp: Map<String, HelpContent> = mapOf(
    "chat_search" to HelpContent(
        title = "Help - Search chats",
        cards = listOf(
            HelpCard("Overview", "Full-text search over saved chat sessions. Reached from the Chat hub's Search Chats card, not from the Chat History list (which has no search icon of its own)."),
            HelpCard("Search field", "Filters on message content only — every message of every session, any role (so a matching system prompt counts too), substring + case-insensitive. Session title, provider, and model aren't matched directly."),
            HelpCard("Result rows", "One row per matching message, not per session — a session with several matching messages appears once per match, each with ~40 chars of context on either side of the hit. Tap resumes the session normally; it doesn't scroll to the matched message."),
            HelpCard("Empty state", "Before you tap Search: \"Enter a search term to find messages\". No hits: No matches found for \"<query>\". Typing anything resets the results and requires a fresh tap on Search — there's no live/as-you-type search."),
            HelpCard("Pitfalls", "Search is explicit-tap only (not per-keystroke or debounced) but still reads + parses every chat session JSON in full on each tap, since it needs message bodies, not just headers — heavy histories may take a moment. A background chat save/delete elsewhere re-runs the last search automatically."),
        )
    ),
    "chat_hub" to HelpContent(
        title = "Help - Chat",
        cards = listOf(
            HelpCard("Overview", "Landing screen for everything chat-shaped. Top section starts a new conversation; below it, pinned and recent sessions plus tools to continue, search, or manage existing chats."),
            HelpCard("Unfinished pill", "When at least one chat ended on a user turn with no assistant reply (you navigated away mid-stream), an envelope pill appears at the very top showing \"N chat(s) awaiting reply\". With exactly one such session it reads \"Resume\" and jumps straight there; with more than one it reads \"Choose\" and opens the Chat History list instead."),
            HelpCard("Start card", "Four entry points stacked in one card: New Chat with Agent (greyed when no agent has a key + active provider), New Chat – Configure On The Fly (pick provider/model/parameters at start), Dual Chat (two models trade turns), and Start with photo (camera capture, image rides into the first user turn)."),
            HelpCard("Continue Existing Chat", "Opens the full chat-history list. Disabled and dimmed when no sessions exist yet. Pinned + Recent below give you a faster jump for the top sessions."),
            HelpCard("Pinned and Recent cards", "Pinned holds every session you marked with the pin chip; Recent shows the next three by updated time. Each row shows the session's title (the AI-generated one once it lands, seeded from the first 10 words of your first message until then) or its preview if no title was saved; tap to resume."),
            HelpCard("Search Chats", "Free-text search across every saved message — opens the dedicated search screen."),
            HelpCard("Manage", "Bulk-prune by age and zip-export of every chat-history JSON. Disabled when no chats exist."),
            HelpCard("Title-bar icons", "Only the always-on Help (?) and Home icons. No reload, info, delete, or trace from this hub — those live on the per-session screens."),
            HelpCard("Tips", "The hub re-reads chat history whenever ChatHistoryManager.historyVersion ticks, so deleting / pinning a session elsewhere shows up when you come back. The camera capture clears any previous photo error before launching.")
        )
    ),
    "chat_session" to HelpContent(
        title = "Help - Chat",
        cards = listOf(
            HelpCard("Overview", "Live single-model conversation. Messages stream chunk-by-chunk; the input box clears the moment Send fires; the assistant bubble bottom-anchors so short conversations sit just above the input row instead of pinned at the top."),
            HelpCard("Header row", "Provider / model label is clickable and opens Model Info. To its right: an optional Knowledge chip (\"📚 Knowledge\" / \"📚 N\"), shown when Experimental features is on and at least one KB exists, or whenever this session already has KBs attached — tap opens a multi-select KB dialog. Then a Pin chip. The pin state is written to the session record immediately, so the hub reflects it without waiting for the next message save."),
            HelpCard("Web search chip", "Per-turn 🌐 toggle that backs ChatParameters.webSearchTool (not the older flat searchEnabled field). At send time it's ORed against whatever webSearchTool the session had when this screen opened, so if that was already true, switching the chip off won't disable web search until your next resume — the OR only ever flips the request to true, never back to false, within the same visit. The chip's state persists back to the session ~350ms after you change it. The LiteLLM tool-use overhead (~3-4k extra system tokens for Claude with web_search) is folded into the cost estimate."),
            HelpCard("Reasoning chip", "Only shown when LiteLLM, models.dev, or the model id family (o1/o3/o4/gpt-5, anything with thinking/reasoning in the name) marks the model as supporting it. Levels come from the provider's self-reported capabilities when available, otherwise the legacy low/medium/high set."),
            HelpCard("Validate input chip", "Tap once to pick a moderation model; while set, every Send first runs the input through callModerationApi. A clean classification proceeds silently. A flagged result pops a Proceed-anyway / Cancel dialog with the fired categories. API errors fail-open: the message is still sent and the orange Moderation: error line is shown."),
            HelpCard("Attach + send", "📎 opens the SAF picker for an image; it's downscaled to a 1568px long edge and re-encoded as JPEG (quality 85) before base64, so the mime shown is always image/jpeg regardless of the source format. The thumbnail + mime type appears above the input row with a Remove button. A red warning fires when the model isn't flagged vision-capable. Send is disabled while streaming, while moderation runs, and when the input is empty without an image."),
            HelpCard("Trace icons", "Title bar's ℹ️ jumps to Model Info. Each finished assistant bubble carries a 🐞 that opens the matching trace — traces tagged with this session's id are preferred (closest timestamp, same model); only when none are scoped to this session does it fall back to older untagged traces. The flagged-input dialog also shows a 🐞 when a moderation trace was captured. All trace icons are suppressed when API tracing is off in Settings."),
            HelpCard("Cost meter", "Live running total in cents appears as the title bar's second line once the first turn completes — there's no separate on-screen Back button it sits next to (Back is the system gesture/button, wired via TitleBar's BackHandler). It's a running sum of input tokens × promptPrice + output tokens × completionPrice for this session."),
            HelpCard("Pitfalls", "Cancellation on back-press deliberately doesn't append a [Stream interrupted] line — the partial chunks aren't an error from your perspective. Real exceptions during streaming do append the partial response with the error message. System-prompt changes from Parameters now apply mid-session — the previous flow only seeded on an empty messages list.")
        )
    ),
    "chat_parameters" to HelpContent(
        title = "Help - Chat Parameters",
        cards = listOf(
            HelpCard("Overview", "Pre-flight setup screen for a configure-on-the-fly chat. Pick optional presets, optionally override individual fields, then Start Chat hands the resolved ChatParameters to the session screen."),
            HelpCard("Provider/model line", "Read-only label under the title bar. Clickable — taps open Model Info for the picked (provider, model)."),
            HelpCard("System Prompt icon", "The title bar's 🎭 icon (not a body button) opens a single-select dialog over Settings.systemPrompts. Picking one fills the System prompt text field; typing in the field clears the selection so the manual text wins."),
            HelpCard("Parameters icon", "The title bar's 🌡️ icon opens a multi-select dialog over Settings.parameters presets. Selected presets are merged via Settings.mergeParameters into a single ChatParameters; manual fields below override per-field."),
            HelpCard("Per-field overrides", "Temperature, Max tokens, Top P, Top K, Frequency penalty, Presence penalty — each takes a free-form number. Empty falls back to the merged preset value, or null if no preset is set. Invalid input also falls back."),
            HelpCard("Citations + recency", "Return citations defaults on; Search recency takes day / week / month / year. Web search itself moved to a per-turn 🌐 chip on the session screen — the preset's webSearchTool flag is OR'd with the chip at send time (see chat_session's Web search chip card for the one-directional gotcha)."),
            HelpCard("Start Chat", "Builds the ChatParameters with the resolved system prompt, then navigates to the session. Note: 🌐 doesn't exist here on purpose — flip it from inside the chat."),
            HelpCard("Tips", "Selecting a system prompt dumps its text into the editable field so you can preview/edit it before starting. The 🎭 / 🌡️ icons themselves don't change appearance when a preset is set — reopen the dialog or check the System prompt field to confirm what's selected."),
            HelpCard("Pitfalls", "Edits to Settings.parameters elsewhere don't migrate into an already-running session — they're resolved once at Start Chat. Restart the chat to pull new preset values.")
        )
    ),
    "chat_history" to HelpContent(
        title = "Help - Chat History",
        cards = listOf(
            HelpCard("Overview", "Paged list of every saved chat session. Each row shows the session's title or preview (whichever is set), provider · model, and last-updated date. Tap a row to resume that session."),
            HelpCard("Pagination", "Page size auto-fits the screen height (rows of ~80dp). Previous / Next buttons sit above and below the list with the current page indicator and total chat count between them."),
            HelpCard("Trace icon per row", "🐞 appears between the row and the > caret when at least one chat-turn trace was tagged with this sessionId. Tap it to open the full trace list filtered to this session."),
            HelpCard("Empty state", "When ChatHistoryManager has no sessions, the screen renders \"No chat history yet\" centered — the Continue Existing Chat card on the hub is also disabled in this state."),
            HelpCard("Resume behaviour", "Tapping a row navigates to AI_CHAT_CONTINUE/{sessionId}, which reopens the same ChatSessionScreen with the stored messages, parameters, attached KBs, pinned flag, and persisted web-search / reasoning toggle."),
            HelpCard("Title-bar icons", "Help and Home only. Bulk delete is on the dedicated Manage screen reachable from the hub, not from here."),
            HelpCard("Tips", "The list re-fetches whenever ChatHistoryManager.historyVersion ticks (after a save / delete / pin elsewhere). Page index is rememberSaveable so it survives a config change."),
            HelpCard("Pitfalls", "The trace icon set is reactive to ApiTracer.traceVersion, so a trace recorded elsewhere for a session on the current page appears without leaving and returning — but only for sessions on the page you're currently viewing; paging away and back re-scans that new page's rows."),
        )
    ),
    "chat_continue" to HelpContent(
        title = "Help - Chat",
        cards = listOf(
            HelpCard("Overview", "Same screen as Chat session, but seeded with the stored messages, parameters, attached knowledge bases, pinned flag, and persisted web-search / reasoning effort from the saved session record."),
            HelpCard("State you keep", "ChatHistoryManager.loadSession brings back: every message, the original ChatParameters, the attached knowledge-base ids, the session's title, and the pinned flag."),
            HelpCard("Toggles you can flip", "Web search and reasoning effort chips are read from the persisted ChatParameters and are saved back on every turn — the next save uses your current chip state, not the original preset."),
            HelpCard("System prompt update", "The system message you see is exactly whatever was saved with this session — ChatParameters.systemPrompt is loaded once from disk when you open the session and doesn't re-sync with Settings.systemPrompts while resumed. Editing that preset elsewhere has no effect on a chat you've already started; the mid-session auto-rewrite (see chat_session's Pitfalls) only fires for a live configure-on-the-fly chat whose ChatParameters object changes while that same screen instance stays open."),
            HelpCard("Session id", "AI_CHAT_CONTINUE/{sessionId} carries the id; the screen treats it as the current session id so saves overwrite the same record. New messages append to the existing JSON."),
            HelpCard("Title-bar icons", "Same as a fresh chat — ℹ️ jumps to Model Info, Help and Home are always present. The running cost-in-cents for this session shows as the title bar's second line, not next to any Back button (there isn't one)."),
            HelpCard("Tips", "Trace icons on existing assistant bubbles prefer traces tagged with this session's id (closest timestamp + same model), falling back to older untagged traces only when none are scoped to this session yet; old assistant turns from before tracing was on still have no icon."),
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
        title = "Help - Dual Chat",
        cards = listOf(
            HelpCard("Overview", "Configures two models that take turns chatting about a subject. State is persisted to a dedicated SharedPreferences (dual_chat_prefs) so reopening the screen restores your last configuration."),
            HelpCard("Model 1 card (blue)", "Tap the model button to drill into the active-providers picker, then the model picker. System Prompt and Parameters presets for both models are set from the two title-bar icons above, not from buttons on this card."),
            HelpCard("Swap button", "Center row, ⬅ Swap ➡. Swaps Model 1 and Model 2 wholesale (provider, model, parameters preset, system prompt) — useful when you realize you wanted them in the other order."),
            HelpCard("Model 2 card (green)", "Same Select-model control as Model 1, tinted green so the two sides stay visually distinct."),
            HelpCard("Subject + Rounds", "Subject is the topic both models will talk about. Rounds caps the conversation; the default is 10, hard-capped at 100 total (guards against an accidental huge paid loop). Cost grows roughly linearly with rounds × per-turn tokens."),
            HelpCard("Prompt templates", "1st prompt seeds round one and supports %subject%. 2nd prompt fires from Model 2 in round one and supports %answer% (Model 1's reply). From round three onward, the previous response is forwarded directly with no template wrapping."),
            HelpCard("Go button", "Saves the prefs blob, resolves both Parameters preset chains, snapshots both system prompts to text, and starts the session. Disabled until both models, both names, the subject, and a positive Rounds value are set."),
            HelpCard("Title-bar icons", "🌡️ Parameters and 🎭 System Prompt icons sit alongside Help and Home; tapping either first asks \"Model 1 or Model 2?\" before opening the matching preset picker for that side. Provider / model selection itself still happens via full-screen overlays, not title-bar icons."),
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
            HelpCard("Per-bubble 🐞", "Each bubble carries the exact trace filename captured at the moment that turn's API call ran (no timestamp probing needed), so same model speaking again still opens the right trace. Suppressed entirely when API tracing is off in Settings."),
            HelpCard("Tips", "Provider / model labels in each bubble are click-targets for Model Info too. The session id is prefixed with dualchat_ + start time so traces from this run are easy to find."),
            HelpCard("Pitfalls", "If either provider has no API key configured the call will error and the loop stops. Errors render in red below the message list and the run flips to the stopped state.")
        )
    ),
)
