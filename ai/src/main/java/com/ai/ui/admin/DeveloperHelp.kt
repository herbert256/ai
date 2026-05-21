package com.ai.ui.admin

internal val developerHelp: Map<String, HelpContent> = mapOf(
    "developer_select_model" to HelpContent(
        title = "Help - API Test — Select Model",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker over the active provider's model list. Tap a row to drop the chosen model into the API Test request."),
            HelpCard("Search field", "Filters by model id (case-insensitive). The ✕ trailing icon clears the field. Counter line shows '<filtered> of <total> models'."),
            HelpCard("Loading state", "If the model list hasn't been fetched yet, a spinner appears in the body. Tap Fetch from the API Test page first when the list reads empty."),
            HelpCard("Pricing column", "Per-row prompt / completion price (×10⁶ tokens). Real pricing renders in green; rows that fell through to DEFAULT_PRICING render dim with 'no pricing'."),
        )
    ),
    "developer_select_endpoint" to HelpContent(
        title = "Help - API Test — Select Endpoint",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker over the active provider's endpoints. The first row is the provider's default base URL; saved custom endpoints follow."),
            HelpCard("Default row", "Drops the provider.baseUrl into the API Test request. Always present even when there are no custom endpoints saved."),
            HelpCard("Custom rows", "One row per Endpoint defined under AI Setup → Providers → Endpoints for this provider. Label + URL on two lines (URL is monospace)."),
        )
    ),
    "refresh_result" to HelpContent(
        title = "Help - Refresh — result",
        cards = listOf(
            HelpCard("Overview", "Result screen shown after a Refresh sub-action finishes (catalog refresh, provider state, model refresh, default-agent generation). Replaces the popup result dialogs the screen used to show."),
            HelpCard("Description block", "Short explanation of what the refresh did and why. Failure states explain what to check (API key, connectivity, etc)."),
            HelpCard("Result rows", "One row per measured value — Status / counts / cache age. Green = loaded, red = failed, grey = neutral metric."),
            HelpCard("Sample model entries", "Catalog refreshes (OpenRouter / LiteLLM) include up to 8 sample model keys from the cache so you can confirm real data landed."),
            HelpCard("OK button", "Returns to the Refresh screen. Multi-row screens (Provider state, Default agents) update live while the underlying refresh runs.")
        )
    ),
    "share_target" to HelpContent(
        title = "Help - Send to AI",
        cards = listOf(
            HelpCard("Overview", "Lightweight chooser shown when another app shares content into this app via ACTION_SEND. Lives between the receiving Activity and the standard nav graph; a card tap clears the share state and routes the payload."),
            HelpCard("Preview card", "Top of the screen: shared subject (when present and non-blank), first 300 characters of shared text (with ellipsis when truncated), \"N attachments\" line for any URIs, and the raw mime type. Lets you double-check the payload before picking a destination."),
            HelpCard("New Report card", "📝. Routes to the New Report flow — text becomes the prompt, the first image attaches for vision. Greyed when there's neither text nor URIs."),
            HelpCard("New Chat card", "💬. Opens a fresh chat session with the shared text staged as the first user turn. Greyed when no text was shared."),
            HelpCard("Cancel", "Back / system back fires onCancel which discards the share without routing. The chooser doesn't add itself to the regular back stack."),
            HelpCard("Title-bar icons", "Help and Home only. Help points to this entry; Home aborts the chooser."),
            HelpCard("Tips", "Cards stay tappable even when the payload is \"weak\" for that route — only-image-shared can still go to Report (vision attach). The receiving callbacks do the heavier validation."),
            HelpCard("Pitfalls", "Multiple shared images: only the first attaches to a chat or report; the rest are dropped.")
        )
    )
,
    "test" to HelpContent(
        title = "Help - Test",
        cards = listOf(
            HelpCard("Overview", "Hub for diagnostic test flows. Each row drills into its own full screen."),
            HelpCard("Test all models", "Probes every configured model of every active provider in one run — a quick way to surface hidden problems (dead models, auth errors, models silently dropped from a provider's catalog)."),
        )
    ),
    "test_all_models_l1" to HelpContent(
        title = "Help - Test all models",
        cards = listOf(
            HelpCard("What you're seeing", "One row per active provider. Each row shows that provider's models-passed count and a green progress fill. Tap a provider to drill into its model list."),
            HelpCard("The stats panel", "Total models · Done (passed) · Errors · Bench · Run (in flight) · Throttled (waiting on a provider rate-limit) · Queue · Costs. Bench counts models whose key is on a >1h-429 cooldown — they failed only because they're rate-limited and will recover on their own, so they're split out from genuine Errors."),
            HelpCard("Test all models button", "Opens the provider picker, where you choose which providers to test before a fresh run starts. The run replaces the previous run's results and can be hundreds of API calls."),
            HelpCard("Cancel test button", "While a run is in flight the bottom button turns red — tap it to stop. Models that hadn't finished are marked as a \"Cancelled\" failure so the run stays a complete, consistent snapshot."),
            HelpCard("Check current test run button", "A run reloaded from disk after the app was killed can still show queued / running models even though nothing is actually working on them. Tap this to check: if the run is genuinely active it just says so, but if it had stalled it restarts the unfinished models (already-passed results are kept)."),
            HelpCard("Rerun Errors again button", "Once a run is finished, an orange button appears with the error count. Tap it to re-probe just the FAILed models — passes are kept untouched. Useful after fixing the parser, raising max_tokens, sleeping off transient 429s, or pruning the catalog."),
            HelpCard("Persistence", "The last run is kept on disk and reloaded every time you open this screen, so you can leave and come back to review results. Starting a fresh run overwrites it; Housekeeping → Reset → Clear runtime data drops it."),
        )
    ),
    "test_all_models_select" to HelpContent(
        title = "Help - Test all models - pick providers",
        cards = listOf(
            HelpCard("What you're seeing", "Every active provider that has an API key, each with its configured-model count. All are checked by default."),
            HelpCard("Select all / none", "Quick toggles for the whole list — handy when you only want to test one or two providers."),
            HelpCard("Start test", "Launches a fresh run scoped to the checked providers and returns to the run screen. Disabled until at least one provider is checked."),
        )
    ),
    "test_all_models_l2" to HelpContent(
        title = "Help - Test all models - provider",
        cards = listOf(
            HelpCard("What you're seeing", "Every configured model of one provider, each with its test status — ✅ passed, ❌ failed, ⏳ running, 🕓 queued. Tap a model for the full result detail."),
            HelpCard("Costs", "Per-model cost is shown when the probe reported token usage; the footer totals the provider's spend for this run."),
        )
    ),
    "test_all_models_l3" to HelpContent(
        title = "Help - Test all models - model",
        cards = listOf(
            HelpCard("What you're seeing", "One model's test result: pass/fail, the error message when it failed, call latency, cost, and the model's actual reply to the \"Reply with exactly: OK\" probe."),
            HelpCard("Trace link", "When API tracing is enabled (Settings → Developer options), the 🐞 icon opens the captured request/response for this probe."),
            HelpCard("Prev / Next", "Step through the same provider-scoped model list the previous screen shows, without going back."),
        )
    ),
    "trace_list" to HelpContent(
        title = "Help - API Traces",
        cards = listOf(
            HelpCard("Overview", "List of every captured API trace. First open of the session populates ApiTracer's cache via streaming parse over the trace dir; subsequent opens are O(1). Title varies: \"Report Traces\" when scoped by reportId, \"Traces — <model>\" when scoped by model, \"API Traces\" otherwise."),
            HelpCard("Filter row", "Up to four slots in one row, each conditionally rendered: Category (when >1 distinct), Hostname (when >2), Provider (when >1), Model (when any pickable). \"(All)\" hides itself on the button label so chips read just \"Category\" instead of \"Category: (All)\"."),
            HelpCard("Errors-only", "Checkbox below the filter row when error count > 0. Filters to status 0 (transport) or >= 400 (HTTP)."),
            HelpCard("Auto-collapse", "When a deep-link arrives with preset filters and the resulting list has exactly one entry, the screen jumps straight into that trace's detail. Flag is rememberSaveable so popping back lands on the (single-entry) list, not the detail again."),
            HelpCard("Pagination", "Computed from BoxWithConstraints maxHeight (52dp per row, 130dp overhead). Prev/Next + position indicator only when total > 1 page."),
            HelpCard("Per-row", "Hostname (left), date/time (center, MM/dd HH:mm:ss), status code (right; green 2xx / orange 4xx / red 5xx / dim others)."),
            HelpCard("Tips", "Picking a model uses a full-screen overlay (TraceModelPickerOverlay) since the option list can be too long for a popup menu."),
            HelpCard("Pitfalls", "PROVIDER_AUX_HOSTS maps Cohere's api.cohere.com onto the Cohere baseUrl host — without that the rerank API traces would land in \"(unknown)\". Extend the map for any provider with a similar second host."),
        )
    ),
    "trace_detail" to HelpContent(
        title = "Help - Trace detail",
        cards = listOf(
            HelpCard("Overview", "One trace's full request/response. First line: \"<HTTP status> - <url>\" with query params stripped (they can carry API keys — see the Get view). Background turns dark red on >=300. Body content is rendered as a colored JSON tree (auto-expands depth 0 and 1) when valid JSON, otherwise plain text."),
            HelpCard("Agent button", "Indigo, only when a saved Agent matches the trace's (provider, model) pair. Drills into the agent's edit screen. Provider drill-in lives on the title-bar ℹ️ instead — directly when there is no model, or via Model Info → Provider when the trace carries a model."),
            HelpCard("Translation result", "Translation traces (category == Translation) get a side-by-side compare view of the user's text and the model's translation."),
            HelpCard("View selector", "All / Get / Req Hdr / Rsp Hdr / Req / Rsp. Get is only present when the request URL had query parameters — same renderer as the header views. Active view highlights blue."),
            HelpCard("Bottom action row", "< (prev trace, fixed 36dp width) — Copy — Edit — Share — > (next). Copy and Share use a redacted variant (URL query params, sensitive headers, sensitive JSON keys → [REDACTED]) so secrets don't leave the device. The on-screen tree always shows raw bytes."),
            HelpCard("Edit", "Persists the trace's request body + url + model into eval_prefs, then opens EditApiRequestScreen with that JSON loaded — fast \"replay this request\" path."),
            HelpCard("Title bar — ℹ️", "When the trace has a model: opens Model Info for (provider, model). When the trace has only a provider (e.g. /v1/models list calls): opens the Provider edit screen in AI Setup."),
            HelpCard("Title bar — 🗑", "Confirm dialog → permanently deletes this trace file from disk and pops back to the trace list. Cannot be undone."),
            HelpCard("Title bar — 🔄", "Same plumbing as the bottom-row Edit button — stages the trace's request into eval_prefs and opens EditApiRequestScreen so you can re-fire (and edit on the way)."),
            HelpCard("Tips", "Translation extraction looks for \"TEXT TO TRANSLATE:\" in the user prompt and walks OpenAI / Anthropic / Gemini response shapes to find the assistant text. Best-effort only — null hides the button."),
            HelpCard("Pitfalls", "Share writes a temp file under cacheDir/shared_traces/<filename> and grants FileProvider read access; long traces survive only as long as the cache survives.")
        )
    ),
    "trace_pick_model" to HelpContent(
        title = "Help - Trace pick model",
        cards = listOf(
            HelpCard("Overview", "Full-screen overlay used by the trace list to pick one (provider, model) pair to filter on. Top card is \"(All models)\" — clears the filter when tapped."),
            HelpCard("Item rows", "Provider name in blue (small) plus model id in white (or blue when selected) below. Trailing ✓ marks the current selection."),
            HelpCard("Sourcing", "Options come from pickableModels in the parent — distinct (provider, model) pairs from traces in the currently scoped Category + Provider + Hostname subset. Picking an unpopulated combination is impossible."),
            HelpCard("Tips", "Sorted (provider, model) by lowercased name; ties broken by model id."),
            HelpCard("Pitfalls", "If you change the upstream Category / Provider / Hostname filters AFTER picking a model, your model selection may no longer match anything — the list will go empty until you clear it."),
        )
    ),
    "developer_test" to HelpContent(
        title = "Help - API Test",
        cards = listOf(
            HelpCard("Overview", "Developer fixture for hand-crafting an API call. Selects provider/endpoint/key/model/prompt/system/temperature/max_tokens, persists to eval_prefs, and opens Edit Request to send a real call."),
            HelpCard("Provider selector", "OutlinedButton cycles through AppService.entries on tap (round-robin). LaunchedEffect resets endpoint URL / key / model fields to the new provider's defaults whenever the provider changes."),
            HelpCard("Endpoint", "Free-text URL plus a \"...\" picker that opens a dialog over the provider's configured Endpoints (default + per-config). Picking sets the URL field."),
            HelpCard("Model picker", "\"...\" button calls AnalysisRepository().fetchModels in a loading dialog, then shows a scrolling list of model ids — tap to pick."),
            HelpCard("API Parameters card", "Collapsible — System Prompt (multi-line), Temperature, Max Tokens. Optional fields."),
            HelpCard("Build Request", "Green button at bottom. Clears any previous raw_json so EditApiRequestScreen builds fresh JSON from the form. Always remembers everything else for next session."),
            HelpCard("Tips", "Reachable from Hub → Developer (long-press / hidden flag depending on build); not part of the regular Settings tree."),
            HelpCard("Pitfalls", "If the endpoint URL doesn't match an actual chat completion path on the chosen provider's host, the call will get a 404 — the form does not validate the URL.")
        )
    ),
    "developer_edit" to HelpContent(
        title = "Help - Edit Request",
        cards = listOf(
            HelpCard("Overview", "Raw JSON editor for an API request. Loads either the captured request body of a previous trace OR a freshly-built body from the API Test form (Gson pretty-printed). Provider/model/url come from eval_prefs."),
            HelpCard("Info card", "Top non-editable card with \"<provider> / <model>\" and the endpoint URL — sanity check before submitting."),
            HelpCard("Editor", "OutlinedTextField with monospace font occupies the rest of the screen. Edits are local until Submit."),
            HelpCard("Submit", "Turns tracing on for this single call regardless of the global setting (and restores it after), runs AnalysisRepository.testApiConnectionWithJson, then jumps to TraceDetail for the new trace if one was captured. If nothing new came in, just toasts \"Request sent. Check traces.\""),
            HelpCard("Tips", "This bypasses the Retrofit serialization layer entirely — perfect for testing edge-case body shapes a provider's docs claim to support."),
            HelpCard("Pitfalls", "API key for the call comes from eval_prefs and is also sent in plain text via the standard provider Authorization scheme; if you mis-typed it on the test screen the failure looks like a 401 in the trace, not a UI error here."),
        )
    ),
    // ===== Per-cloud-provider help pages (42) =====
    // Topic id derived via providerHelpTopicId() — lowercased,
    // alphanumeric only. Cards: Overview / Setup / Models / Pricing
    // & quirks / Pitfalls / Related. Reached from the ℹ️ icon on the
    // ProviderSettingsScreen TitleBar and the Cloud providers
    // directory on the home Help page.,
    "applog_list" to HelpContent(
        title = "Help - Application log — file list",
        cards = listOf(
            HelpCard("Overview", "List of daily-rotating application log files stored under `<filesDir>/applog/`. Each file captures one calendar day filtered by the level threshold set in Settings → Logging. Rows show the date (YYYY-MM-DD) and on-disk size."),
            HelpCard("Title bar — 🗑", "Clears every log file after confirmation. The currently-active in-memory session writes are dropped too — the next log call starts a fresh file."),
            HelpCard("Tap a row", "Opens the per-file detail view with search, tag filter, level filter, time-range filter, copy + share. Files appear newest-first."),
            HelpCard("Empty state", "Shows the current threshold so you can tell when nothing's been written because everything is below it (e.g. threshold = WARN and no warnings have fired today). A red banner appears instead if the log writer hit a disk / IO error — message + dropped-line count surfaced inline."),
            HelpCard("Source", "Files are written by [com.ai.viewmodel.AppLog]; the list is re-read on every screen resume so detail-view deletes propagate."),
            HelpCard("Pitfalls", "Old files are kept until you delete them — set a calendar reminder if storage matters. Toast messages route through AppLog too: WARN / ERROR levels also flash a toast, but the file is the authoritative record."),
            HelpCard("Reached from", "Settings → Logging and tracing → Application log.")
        )
    ),
    "applog_detail" to HelpContent(
        title = "Help - Application log — file detail",
        cards = listOf(
            HelpCard("Overview", "Filtered view of one log file's entries, newest-first. Stack traces are folded into their header line; tap any row to expand it full-screen."),
            HelpCard("Title bar — Copy / Share / 🗑", "Copy and Share open a chooser dialog (last N lines / complete log / filtered-only). 🗑 deletes the entire file with confirmation — back returns to the file list with the deletion already reflected."),
            HelpCard("Row colour = level", "🔴 ERROR / 🟠 WARN / 🟢 INFO / 🔵 DEBUG / grey TRACE. The level chips at the top toggle visibility — every level is on by default."),
            HelpCard("Search box", "Case-insensitive substring match across the header line + every continuation line. The ✕ clears the box. Combined with the level / tag / time filters — every active filter is AND-ed."),
            HelpCard("Tag dropdown + level chips", "Tag picks one tag from the file's distinct set (`(any)` = no filter). Level chips multi-select TRACE/DEBUG/INFO/WARN/ERROR."),
            HelpCard("Time range", "Start / End buttons open clock pickers. Constraints are HH:mm. Each has a Clear button to drop the bound."),
            HelpCard("Counter line", "\"Showing X of Y\" shows the filter result vs. total. A *Clear filters* link appears whenever at least one filter is active."),
            HelpCard("Prev / Next file", "Swipe horizontally on the content area to walk to the previous / next day's file. Filters persist across the swap — useful for following a single tag across midnight."),
            HelpCard("Reached from", "Tap a row on the Application log list. Some screens (Report → View Log) deep-link in with the search pre-seeded to the report id.")
        )
    ),
    "external_intent" to HelpContent(
        title = "Help - External request",
        cards = listOf(
            HelpCard("Overview", "Confirmation gate shown before this app fulfils a cross-app share / `ACTION_SEND` request. Another app is asking AI to generate a report with instructions embedded in the intent — review what will happen before spending API credits."),
            HelpCard("Title bar — Back", "Cancels the request and returns to the calling app. Nothing is sent; no API calls fire."),
            HelpCard("Prompt card", "Shows the optional title + a preview of the AI prompt (first 400 chars, truncated with …) + the system-prompt snippet (first 120 chars) if one was passed."),
            HelpCard("Will-do card", "One-line headline of the action: *Generate a report immediately*, *Open the editor with prompt pre-filled*, or *Open agent / model selection*. Lists the report type and target models / agents when the caller pinned them; otherwise notes that you'll pick on the next screen."),
            HelpCard("Side-effects card (red)", "Only shown when the intent specifies post-generation actions: send email, open in browser, share via the system sheet, or return data to the caller app. Each is bulleted so you can spot a malicious or unexpected effect before confirming."),
            HelpCard("Confirm", "*Generate* on the bottom bar commits — auto-generate flows fire the report; editor / picker flows open with the prompt pre-filled. Side-effects fire only on the generate path."),
            HelpCard("Pitfalls", "A malicious caller could ask the app to email a report to an attacker's address or open a sketchy URL — review the side-effects card carefully. Cancel always returns to the caller with no data leakage."),
            HelpCard("Reached from", "Android share dialog → AI app, or a deep link of the form `com.ai.ACTION_NEW_REPORT`.")
        )
    ),
    "inaccessible_models" to HelpContent(
        title = "Help - Inaccessible models",
        cards = listOf(
            HelpCard("Overview", "Curated list of model definitions that your account genuinely can't reach — no API key, capability mismatch, Together's non-serverless tier, geo-blocked endpoints, etc. Entries are dimmed in pickers and dropped from Test All Models runs instead of marked FAIL."),
            HelpCard("Title bar — Back / 🏠", "Back returns to Settings → AI Models. 🏠 jumps to the home screen."),
            HelpCard("Rows", "One row per `(provider, model)` pair. Shows the provider id + the model name + the reason for inaccessibility (first 80 chars). Sorted alphabetically."),
            HelpCard("Edit / Delete", "Tap a row to open the model picker and replace the entry with a different model. Long-press to delete — instant, no confirm."),
            HelpCard("Auto-population", "The Test All Models engine adds entries automatically when a probe returns a sentinel like \"Unable to access non-serverless\". You can also curate the list by hand."),
            HelpCard("Effect on pickers", "Entries here are dimmed in the New Report / Test All / Find Icons pickers. They're not deleted — the model still exists in your settings; it just won't fire calls in normal flows."),
            HelpCard("Pitfalls", "Removing an entry restores the model to normal pickers at full brightness — useful when an account upgrade unlocked a tier the test engine had flagged."),
            HelpCard("Reached from", "Settings → AI Models → Inaccessible models.")
        )
    ),
)
