package com.ai.ui.admin

internal val settingsAdminHelp: Map<String, HelpContent> = mapOf(
    "select_parameters" to HelpContent(
        title = "Help - Configure API parameters",
        cards = listOf(
            HelpCard("Overview", "Opened from the 🌡️ icon. Picks which saved Parameters preset(s) — temperature, max tokens, top-p and the rest — apply to whatever you're configuring (a report model, an agent, a chat, an internal prompt, the app-wide default, …)."),
            HelpCard("Current active", "When at least one preset is selected, the orange line under the title lists the active preset name(s). Nothing there means no preset is set and the next level down (agent / provider / app-wide) decides."),
            HelpCard("Selecting", "Tap a row to toggle it. This is multi-select — pick several and their settings merge. The list pages when it doesn't fit; swipe left / right (or up / down) to move between pages."),
            HelpCard("Icons", "🗑 clears the whole selection (back to none). ✏️ opens the Parameters management screen where you create, edit and delete presets."),
        )
    ),
    "select_system_prompt" to HelpContent(
        title = "Help - Define model system prompt",
        cards = listOf(
            HelpCard("Overview", "Opened from the 🎭 icon. Picks the saved System prompt — the standing instruction that sets the model's role or tone — for whatever you're configuring."),
            HelpCard("Current active", "When a prompt is selected, the orange line under the title shows its name. Nothing there means no system prompt is set at this level."),
            HelpCard("Selecting", "Tap a row to select it (single choice). The list pages when it doesn't fit; swipe to move between pages."),
            HelpCard("Icons", "🗑 clears the selection (back to none). ✏️ opens the System prompts management screen where you create, edit and delete prompts."),
        )
    ),
    "update_from_cloud" to HelpContent(
        title = "Help - Update from cloud",
        cards = listOf(
            HelpCard("Overview", "Installs the app's latest APK from a file you've already synced to this phone — typically a Drive sync folder where a new build lands automatically. Replaces the manual flow (open Drive → wait for download → tap APK → install) with two taps: Update in this screen, then Install in the system dialog."),
            HelpCard("One-time setup", "Tap Pick APK source. The system file picker opens — navigate to your Drive folder (or any storage location), select the APK file. The selection is persisted; you only do this once. Drive's local file provider fetches the latest cloud copy on each read, so re-picking after every update isn't needed."),
            HelpCard("Drive → Computers limitation", "If your APK lives under Drive's Computers section (the area where Drive for Desktop puts synced Mac / Windows folders), the Android file picker can't see it — Drive's SAF integration only exposes My Drive. Workaround: in Drive web, right-click the APK → Add shortcut to Drive → pick any folder under My Drive. The shortcut appears in the picker on Android, and reading it returns the real file's bytes — Drive resolves shortcuts transparently. Your desktop sync setup stays unchanged; the shortcut is a Drive-side pointer, not a copy."),
            HelpCard("Updating", "Tap Update. The app reads the current bytes of the picked file, copies them to its cache, and fires the system PackageInstaller. The system shows its standard Install dialog — confirm there. The Install dialog is mandatory for security and can't be skipped without device admin privileges the app doesn't have."),
            HelpCard("Source file display", "Shows the picked file's name + size + last-modified time so you can confirm you're about to install the right build. When the source is gone (file deleted, permission revoked) the row turns orange and a re-pick is required."),
            HelpCard("Drive check", "A status line shows whether the Google Drive app is installed. Drive isn't required — the picker works with any storage provider (local files, Dropbox, …) — but if you're updating from a Drive-synced folder, Drive being installed is what makes the URI resolve to the up-to-date cloud copy."),
            HelpCard("Permission", "Adds `REQUEST_INSTALL_PACKAGES` to the manifest. On Android 8+ the OS also requires \"Install unknown apps\" enabled for this app the first time — the system Install dialog deep-links there if it isn't.")
        )
    ),
    "example_prompt_picker" to HelpContent(
        title = "Help - Pick an example prompt",
        cards = listOf(
            HelpCard("Overview", "Reached from AI Reports → Start with an example prompt. Lists every Example prompt in your library, sorted alphabetically by title. Tap a row to open New Report seeded with the prompt's (title, text)."),
            HelpCard("Search field", "Filters by title OR text (case-insensitive). The trailing ✕ clears."),
            HelpCard("Per-row content", "Title in white; the first line of the text dimmed underneath."),
            HelpCard("Empty state", "Shown when no example prompts exist. Curate them under AI Setup → Prompt management → Example prompts, or load the bundled set via Housekeeping → Prompts → Add new prompts from assets/examples.json."),
        )
    )
,
    "settings_main" to HelpContent(
        title = "Help - Settings",
        cards = listOf(
            HelpCard("Overview", "Settings is a pure table of contents. Every editable preference lives one tap deeper inside one of the four sub-screens. Edits autosave on each sub-screen with a 400 ms debounce, so you don't need a Save button — just type and back out."),
            HelpCard("Network settings", "Read timeouts, per-provider throttling, and 429 / 529 retry policies. Tap the row to open the dedicated sub-screen."),
            HelpCard("UI tweaks", "Model name layout, full-screen, back-arrow visibility. Tap the row to open the dedicated sub-screen."),
            HelpCard("Logging and tracing", "API tracing master switch and application log level. Tap the row to open the dedicated sub-screen."),
            HelpCard("Metadata & icons", "Grand-master switch for every optional AI-generated extra — report icon / language / title, per-model icons & titles, Fan & meta icons — plus the per-item toggles it gates."),
            HelpCard("Default icons", "Edit the fallback emoji shown when a report or result has no generated icon of its own (report, model, rerank, moderate, language, translation, meta, fan-out / fan-in rows)."),
            HelpCard("Other settings", "Identity (Name + Email) used for outbound prompts and email exports."),
            HelpCard("Tips", "Each sub-screen has no Save button on purpose — every keystroke restarts a 400 ms debounce timer. If you tap Back fast, the latest values still flush to disk via a DisposableEffect."),
        )
    ),
    "settings_other" to HelpContent(
        title = "Help - Other settings",
        cards = listOf(
            HelpCard("Overview", "Holds your Identity (Name + Email) and the 'Auto create Rerank and Moderation' toggle. The optional report-metadata toggles that used to live here now have their own screen — Settings → Metadata & icons. Autosaves with a 400 ms debounce."),
            HelpCard("Identity", "Two text fields — Name and Email address — combined in one card. Name surfaces wherever the app addresses you and defaults the From: header on email-style exports. Email address pre-fills the To: field on report email exports; leave blank to be prompted each time."),
            HelpCard("Auto create Rerank and Moderation", "Default ON. When a report's models all finish, the app automatically creates one Rerank and one Moderation secondary result, each using the first rerank- / moderation-capable model it finds across your active providers (first provider in order, first model of that type). A kind is skipped when you have no capable model for it, or when that report already has one. Turning it off leaves report completion untouched. Either way, creating a Rerank or Moderation by hand still opens the model picker so you choose the model."),
            HelpCard("Tips", "Renaming yourself mid-conversation has no retroactive effect on already-saved chats / reports — the Name field only shapes outbound prompts going forward."),
        )
    ),
    "settings_metadata" to HelpContent(
        title = "Help - Metadata & icons",
        cards = listOf(
            HelpCard("Overview", "One screen for every optional, AI-generated extra a report can carry — its icon, detected language, AI title, per-model icons & titles, Fan Out icons & titles, and the meta / rerank / moderate / translate row icons. A single grand-master toggle at the top turns the whole category on or off; the per-item toggles below it appear only while the master is on. Everything autosaves with a 400 ms debounce."),
            HelpCard("Generate metadata & icons (master)", "The grand-master switch. When OFF: no metadata LLM calls fire, the per-item toggles are hidden, the Fan Out Icons / Titles buttons and the Manage report 'info' row disappear, and a new report must be given a manual title (the AI-title option is suppressed). When ON: each per-item toggle governs its own item exactly as before. Default ON."),
            HelpCard("View screens are never gated", "The master switch only affects generation and the controls that trigger it. Screens that display a finished report always show whatever that report already holds, and fall back to fixed defaults when it holds nothing — 📝 report, 🧠 model, 🏆 rerank, 🚦 moderate, 🌐 translate, 🔗 meta, 🔱 Fan Out, 🎯 Fan In. So switching the master off never blanks out icons on reports that already have them."),
            HelpCard("Report title", "Manual keeps the Title field on the New AI Report screen; AI (default) hides it and fills the title from a background call after report start. With the master off the title is always Manual."),
            HelpCard("Report icon / Report language", "Two independent toggles (previously one). Report icon runs a small call to pick a fitting emoji shown in title bars, the hub list, history, and search hits. Report language detects the language and picks a flag emoji shown on the info screen and the language picker."),
            HelpCard("Per-model icons / titles, internal-prompt icons, Autostart Fan Meta", "Per-model icons derive an emoji from the model title; per-model titles add a ≤4-word title per response. Internal-prompt icons add a leading emoji to secondary-result rows. Autostart Fan Meta kicks the Fan Meta batch off automatically when a clean Fan Out finishes."),
        )
    ),
    "settings_default_icons" to HelpContent(
        title = "Help - Default icons",
        cards = listOf(
            HelpCard("Overview", "Edit the fallback emoji the app shows when a report (or one of its results) has no generated icon of its own. There are 11 entries; tap an entry's emoji box to open the system emoji picker (search, categories, recents) and pick a replacement. Edits autosave with a 400 ms debounce."),
            HelpCard("When defaults show", "View screens always render whatever a report actually holds; these defaults fill in only when that value is missing. So changing a default updates every report that never had its own icon, and never overrides one that does. The defaults are independent of the Metadata & icons master switch — they apply even when metadata generation is off."),
            HelpCard("The 9 entries", "Report (📝) and Report model (🧠) are the title-bar / per-model fallbacks. Rerank (🏆), Moderate (🚦), Translation row (🌐) and Meta (🔗) are the leading glyphs on secondary-result rows. Language icon (🌐) is the report's detected-language flag. Fan Out row (🔱) and Fan In row (🎯) are the leading glyphs of those rows on the Manage report screen."),
            HelpCard("Reset", "Reset all to defaults returns every entry to its factory emoji. The picker always yields exactly one glyph, so an icon can never become blank or invalid."),
        )
    ),
    "settings_app_settings" to HelpContent(
        title = "Help - App settings",
        cards = listOf(
            HelpCard("Overview", "Two app-level defaults for the System prompt and Parameters used when running a report, each with an App-wide and a Report-model slot. These fill in only when nothing more specific is set. Autosaves with a 400 ms debounce."),
            HelpCard("App-wide", "The universal lowest fallback. Applied to every model in a report — including models that come from an agent, flock or swarm — but only when no more-specific value (a pre-generation prompt/params, the agent/flock/swarm, the provider, or the report-model default) is set."),
            HelpCard("Report model", "Applies only to bare models picked directly into a report (not coming from an agent / flock / swarm). It outranks the App-wide default but is below the provider default. It is NOT used at all when you set a system prompt / parameters during the New AI Report screen (pre-generation)."),
            HelpCard("Precedence", "Highest wins. Bare model: pre-generation → provider → report-model → app-wide. Agent / flock / swarm model: pre-generation → the agent/flock/swarm's own → app-wide."),
        )
    ),
    "settings_network" to HelpContent(
        title = "Help - Network settings",
        cards = listOf(
            HelpCard("Overview", "Four cards for how the app talks to remote providers: read timeouts, per-provider throttling, the in-line 429 retry policy, and the in-line 529 (server overloaded) retry policy. Every field autosaves with a 400 ms debounce."),
            HelpCard("Network read timeouts", "How long the OkHttp client waits for an API response before giving up. Streaming applies to SSE chat / report streams — the timeout is the gap between chunks, so the long default (600 s) is normal for slow-reasoning models. Non-streaming applies to analyze, meta, rerank, fetch-models, translate — anything that blocks for the full response body. Provider-test calls always cap at 30 s regardless."),
            HelpCard("Per-provider throttling", "Two caps applied per provider hostname across every flow in the app. Max calls per minute uses a 60-second sliding window — calls beyond the limit sleep until the oldest entry ages out. Max concurrent calls per provider queues additional calls on a per-host semaphore. Defaults: 60 calls/minute, 5 in flight at once."),
            HelpCard("429 error handling", "When a provider answers HTTP 429 (rate-limited), the OkHttp interceptor sleeps for the wait time and re-issues the same request up to the retry cap. Set retries to 0 to disable in-line retries entirely — the outer withRetry layer still gets one more attempt on transient 4xx. Defaults: 3 retries, 1000 ms between each."),
            HelpCard("529 error handling", "When a provider answers HTTP 529 (server overloaded — typically Anthropic), the OkHttp interceptor sleeps for the wait time and re-issues the same request up to the retry cap. Independent of the 429 budget — a 529 burst doesn't eat the 429 retry count. Set retries to 0 to disable in-line retries entirely; the outer withRetry layer still gets one more attempt on transient 5xx. Defaults: 3 retries, 1000 ms between each. Anthropic ships with a stricter override (5 retries, 5000 ms) seeded from providers.json."),
            HelpCard("Per-provider overrides", "Any of these globals can be overridden on a per-provider basis via Settings → AI Setup → Providers → <provider> → Throttle & retry overrides. Leave a field blank there to inherit the default set here."),
            HelpCard("Tips", "If a provider sends frequent 429s, increasing the wait time tends to recover faster than increasing the retry count — bigger backoffs let the rate-limit window actually open. 529s are server-side overload (not your fault) and respond similarly to longer waits. If you're being throttled before reaching 429, drop the max-calls-per-minute instead.")
        )
    ),
    "settings_network_api_calls" to HelpContent(
        title = "Help - Maximal API calls",
        cards = listOf(
            HelpCard("Overview", "Four caps on how many API calls the app keeps in flight at once. Stack as nested suspending semaphores — every coroutine-level call goes through the global cap first, then through its matching per-kind cap, then through the existing per-provider cap (Network settings → Per-provider throttling). When a cap is full, further calls suspend until a permit frees up; no calls are dropped or errored. Defaults: 100 / 50 / 50 / 50."),
            HelpCard("Concurrent API calls at the same time", "Hard global ceiling, applies to every API call the app keeps in flight — reports, translations, fan-out. Set lower if you're hitting provider rate limits across the board or your device is thermal-throttling. Default 100."),
            HelpCard("Concurrent Model reports API calls", "Cap on the primary per-agent calls fired during a new-report run (the boxes you see filling in on the report result page). Replaces the legacy hardcoded ceiling of 4. Bumping this past your global cap has no effect — the global wins. Default 50."),
            HelpCard("Concurrent Translations API calls", "Cap on per-item translation calls (prompt + each agent response + each chat-type Meta result). With a multi-model translation run, the cap is on the total across models, not per model. Default 50."),
            HelpCard("Concurrent Fan Out API calls", "Cap on per-pair fan-out calls. A 6-agent fan out has 30 pairs and the run would top at 30 simultaneously with this default. Bump higher for fast providers that handle parallel load gracefully; drop lower if you regularly see 529s during fan-out. Default 50."),
            HelpCard("Concurrent Fan Meta API calls", "Cap on the Fan Meta batch — the title+icon generation launched via the Fan Meta button on a fan-out detail screen. Separate cap so a parallel fan-out + Fan Meta run doesn't halve each other's budget. Default 50."),
            HelpCard("Per-kind ≠ per-host", "These caps don't replace the per-provider concurrency cap (Network settings → Per-provider throttling) — they sit on top of it. A run with 20 fan-out pairs going to the same provider with a 5-per-host cap will still bottleneck at 5 in flight even with a 50 fan-out cap."),
            HelpCard("Live updates", "Changing a cap takes effect immediately for any new dispatch — calls already in flight keep running on their original permit and release it normally. No restart needed.")
        )
    ),
    "settings_ui" to HelpContent(
        title = "Help - UI tweaks",
        cards = listOf(
            HelpCard("Overview", "Visual / layout preferences that don't affect how the app talks to providers. Pick what's most legible for you — every option autosaves with a 400 ms debounce."),
            HelpCard("Model name layout", "Two radios. Model name only is the dense default — useful when you mostly run different models. Provider and model name joins the provider's display name and the model id with \" · \" — useful when you run the same model id on multiple providers.")
        )
    ),
    "settings_logging" to HelpContent(
        title = "Help - Logging and tracing",
        cards = listOf(
            HelpCard("Overview", "Two diagnostic preferences. Both flow to background subsystems on save — the next traced call and the next log line pick up the change immediately."),
            HelpCard("API tracing", "Master switch for ApiTracer. Off → no new trace files are written, the Hub's AI API Traces card and every 🐞 ladybug icon across the result / detail screens disappear. On → every API request and response (headers + body) gets captured to disk under filesDir/trace/."),
            HelpCard("Application log level", "Severity threshold for the in-app file logger AppLog. Calls at or above this level are appended to a daily-rotating file under filesDir/applog/applog_<yyyyMMdd>.log. Defaults to INFO. Use DEBUG / TRACE when troubleshooting — they flood the file quickly but capture per-call detail. OFF disables the file appender entirely (logcat still works during dev)."),
            HelpCard("Tips", "View / share / clear logs under Housekeeping → Application log. Increase the level when sharing a log with Claude Code for diagnostics; drop back to INFO afterwards.")
        )
    ),
    "settings_setup" to HelpContent(
        title = "Help - Setup",
        cards = listOf(
            HelpCard("Overview", "Top-level hub for AI configuration. Each card opens a sub-hub or list — counts on the right show how many entries you have so you can tell at a glance what is configured."),
            HelpCard("Providers", "Per-provider API keys, state, default model, and the catalog editor. Count = number of registered providers (39 ship by default plus any you have added)."),
            HelpCard("Models", "Sub-hub: per-provider Models, Model Types (default API paths), and Manual model types overrides. Count = total models across active providers only."),
            HelpCard("Workers", "Sub-hub for Agents, Flocks, Swarms. Disabled until at least one provider has an API key. Count = active agents + flocks + swarms."),
            HelpCard("Prompt management", "Sub-hub: System Prompts, Meta prompts, Fan out/in prompts, Other internal prompts, and Example prompts. Count = system prompts + internal prompts."),
            HelpCard("Parameters", "Direct CRUD for parameter presets (temperature, max tokens, system prompt, web-search flags, reasoning effort)."),
            HelpCard("Costs", "Opens the manual cost-override list. Count = number of manual override entries currently saved."),
            HelpCard("External Services", "HuggingFace, OpenRouter, Artificial Analysis API keys. Count = number of those keys that are non-blank."),
        )
    ),
    "agents" to HelpContent(
        title = "Help - Agents",
        cards = listOf(
            HelpCard("Overview", "CRUD list of named agent configurations. Each agent ties a provider, model, optional API key override, optional endpoint, parameters preset(s), and system prompt into one reusable unit."),
            HelpCard("Visible entries", "Only agents whose provider is currently active (state == \"ok\") show up — agents bound to inactive or keyless providers are hidden, not deleted."),
            HelpCard("Item rows", "Show name in white, then \"Provider · effective model\" beneath. Tap a row to edit; tap the row's trash to delete (after a confirmation dialog scoped to entity type \"Agent\")."),
            HelpCard("Add Agent", "Top button opens the Edit screen with empty fields and the first active provider preselected as the default."),
            HelpCard("Tips", "Sort is alphabetical by name (case-insensitive)."),
            HelpCard("Pitfalls", "If your provider list is fresh and nothing is active, this list will appear empty even though you have agent rows saved — set up a provider key first."),
        )
    ),
    "agents_list" to HelpContent(
        title = "Help - Agents",
        cards = listOf(
            HelpCard("Overview", "List of every saved agent. An agent pairs a provider, model, optional API key override, optional endpoint, parameter presets, and a system-prompt preset — one tappable unit you can add to a report, flock, or swarm."),
            HelpCard("Add Agent", "Top button opens the editor with a blank agent. Name must be unique among existing agents (case-insensitive)."),
            HelpCard("Row tap", "Opens the agent's edit screen."),
            HelpCard("Row subtitle", "Provider · model · optional endpoint label. Provider names of inactive providers stay listed here — the report-run dispatch skips them, but you still need to be able to edit / delete the row."),
            HelpCard("Per-row 🗑", "Confirms then removes the agent. Existing references from flocks / swarms / saved reports become broken — fix them up afterwards."),
            HelpCard("Empty state", "No agents yet — tap Add Agent to create the first one."),
        )
    ),
    "agent_edit" to HelpContent(
        title = "Help - Agent edit",
        cards = listOf(
            HelpCard("Overview", "Form for one agent. All fields autosave on Create / Save — there is no per-field autosave here. Cancel = system back."),
            HelpCard("Agent name", "Required, must be unique among other agents (case-insensitive). The Save / Create button stays disabled until the name validates."),
            HelpCard("Provider / Model", "Open full-screen pickers via the two outlined buttons. Switching provider clears the model field; \"(default)\" means \"use the provider's default model\" at run time."),
            HelpCard("API Key", "Optional. Overrides the provider's saved key for this agent only. Leave blank to use the provider key. Editing it clears any earlier test result."),
            HelpCard("Endpoint", "Dropdown surfaces only when the provider has more than the Default endpoint or when LiteLLM lists extra paths for the picked model. Picking a LiteLLM-derived path materialises a real Endpoint entry on the provider via onAddEndpoint."),
            HelpCard("System Prompt / Parameters", "Two side-by-side buttons opening selector dialogs. Selected presets show in purple-tinted state. A red ⚠ banner appears beneath when LiteLLM reports the chosen model does not accept system messages."),
            HelpCard("Test Agent", "Visible only when an API key (override or provider) exists. Runs a real API call; on success shows green \"Success\", on failure red error text. The 🐞 next to the result deep-links to the captured trace."),
            HelpCard("Pitfalls", "If you flip provider after picking a model, the model resets — by design, since model ids rarely match across providers."),
        )
    ),
    "flocks" to HelpContent(
        title = "Help - Flocks",
        cards = listOf(
            HelpCard("Overview", "CRUD list of Flocks — named groups of Agents, plus optional shared parameters and system prompt. The Report builder uses a flock to fan one prompt out across several agents in a single tap."),
            HelpCard("Item rows", "Show name in white plus a count and comma-separated list of contained agents (active providers only). Tap a row to edit, trash to delete with confirmation."),
            HelpCard("Add Flock", "Opens the editor blank. The Save / Create button stays disabled until the flock has both a unique name and at least one selected agent."),
            HelpCard("Default agents flock", "Refresh → Default agents creates / maintains a flock named exactly DEFAULT_AGENTS_FLOCK_NAME — do not rename it manually unless you know what you are doing."),
            HelpCard("Tips", "Flocks are sorted alphabetically; the subtitle truncates long member lists, so the Edit screen is the canonical view."),
            HelpCard("Pitfalls", "Deleting a flock does not delete the agents it referenced — agents are owned at a higher level."),
        )
    ),
    "flocks_list" to HelpContent(
        title = "Help - Flocks",
        cards = listOf(
            HelpCard("Overview", "List of every saved flock. A flock is a named bundle of agents — pinning a system-prompt preset / parameter preset at flock level overrides the agent-level equivalents at report-run time."),
            HelpCard("Add Flock", "Top button opens the editor with a blank flock. Name must be unique."),
            HelpCard("Row tap", "Opens the flock's edit screen — change name, members, optional flock-level presets."),
            HelpCard("Row subtitle", "Comma-joined agent names. Inactive-provider agents stay in the list so you can fix things up — expandFlockToModels skips them when the report actually runs."),
            HelpCard("Empty state", "No flocks yet — tap Add Flock to create the first one."),
        )
    ),
    "flock_edit" to HelpContent(
        title = "Help - Flock edit",
        cards = listOf(
            HelpCard("Overview", "Edit one flock. Top row holds the name field and Create / Save button (disabled until the name is valid AND at least one agent is checked)."),
            HelpCard("System Prompt / Parameters", "Optional shared overrides applied to every agent in the flock at run time. Both buttons turn purple when populated. Selected via the same dialogs used in Agents."),
            HelpCard("Search agents", "Filter input matches agent name OR the agent's provider display name. Selected agents pin to the top of the list (compareByDescending) so a long roster stays manageable."),
            HelpCard("Selection counter", "\"N selected of M\" shows above the list, where M counts only agents whose provider is currently active."),
            HelpCard("Agent rows", "Each row shows name plus the effective model label (provider · model in the chosen layout). Tap the whole row OR the checkbox to toggle membership."),
            HelpCard("Tips", "Empty selection is the only blocker for Save besides name validation — a flock with one agent is legal but pointless."),
            HelpCard("Pitfalls", "An agent bound to an inactive provider is hidden here and effectively disabled — fix the provider's state to use it again.")
        )
    ),
    "swarms" to HelpContent(
        title = "Help - Swarms",
        cards = listOf(
            HelpCard("Overview", "CRUD list of Swarms — groups of (provider, model) pairs WITHOUT going through Agents. Use a swarm when you want to fire the same prompt at, say, GPT-5 / Claude / Gemini in parallel without creating individual agent rows."),
            HelpCard("Item rows", "Show name plus \"N members: provider/model, provider/model, …\" filtered to active providers. Tap to edit; trash to delete with confirmation."),
            HelpCard("Add Swarm", "Opens the editor blank. Create / Save stays disabled until the name is valid AND at least one member is selected."),
            HelpCard("Tips", "Members of inactive providers stay in the swarm but won't run — fix the provider state and they re-light without re-editing."),
            HelpCard("Difference from Flocks", "Flocks reference agents (and inherit each agent's API key / endpoint / prompts). Swarms hold raw provider+model tuples and use the provider's saved key / endpoint, plus optional swarm-level system prompt and parameters."),
            HelpCard("Pitfalls", "There is no per-member API key override — for that, build agents and a flock instead."),
        )
    ),
    "swarms_list" to HelpContent(
        title = "Help - Swarms",
        cards = listOf(
            HelpCard("Overview", "List of every saved swarm. A swarm is a named bundle of raw (provider, model) pairs — structural, with no per-agent presets. Use a swarm when you want N specific models on a report without configuring agents."),
            HelpCard("Add Swarm", "Top button opens the editor with a blank swarm. Name must be unique."),
            HelpCard("Row tap", "Opens the swarm's edit screen — change name, add / remove (provider, model) members."),
            HelpCard("Row subtitle", "Comma-joined provider/model pairs."),
            HelpCard("Empty state", "No swarms yet — tap Add Swarm to create the first one."),
        )
    ),
    "swarm_edit" to HelpContent(
        title = "Help - Swarm edit",
        cards = listOf(
            HelpCard("Overview", "Edit one swarm. Top row holds the name + Save / Create button. Save activates once the name is valid and at least one member is added."),
            HelpCard("System Prompt / Parameters", "Optional shared bundle applied to every member at run time. Buttons go purple when populated."),
            HelpCard("Member counter", "\"N members\" sits at the left of the action row; the blue \"+ Add model\" button on the right opens the same multi-row picker the New Report's +Model button uses (search + provider filter)."),
            HelpCard("Member cards", "One card per (provider, model) tuple. Provider name in blue, model id in white, plus capability badges (👁 vision / 🌐 web / 🧠 reasoning). The trailing red ✕ removes the member."),
            HelpCard("Adding from the picker", "Already-added members render dimmed and ignore taps so a duplicate can't sneak in. Members are keyed on (provider.id, model) — case-insensitive on the model id."),
            HelpCard("Pitfalls", "The picker only surfaces ACTIVE providers (those with a working API key). Models from inactive providers are hidden from the catalog."),
        )
    ),
    "parameters" to HelpContent(
        title = "Help - Parameters",
        cards = listOf(
            HelpCard("Overview", "CRUD list of parameter presets. A preset bundles temperature / max tokens / top-p / top-k / penalties / seed / system prompt / web-search and reasoning flags into one named row that you can attach to agents, flocks, swarms or one-off report runs."),
            HelpCard("Subtitle", "\"N parameters configured\" — counts how many fields in the preset are non-null, plus a +1 for each web-search flag that's on."),
            HelpCard("Add Parameter Preset", "Top button opens the edit screen blank."),
            HelpCard("Tips", "When multiple presets are attached to a worker (agents accept a list), later non-null values win — a sensible \"merge\" semantics."),
            HelpCard("Pitfalls", "Setting maxTokens here is a soft suggestion for OpenAI but mandatory for Anthropic; leaving it blank in an Anthropic-bound preset falls back to 4096."),
        )
    ),
    "parameters_list" to HelpContent(
        title = "Help - Parameters",
        cards = listOf(
            HelpCard("Overview", "List of every saved Parameters preset — a bundle of generation knobs (temperature, max tokens, top_p, top_k, frequency / presence penalties, system prompt override, stop sequences, seed, response-format JSON, web-search flags). Agents / flocks pin presets by id."),
            HelpCard("Add Parameter Preset", "Top button opens the editor with a blank preset."),
            HelpCard("Row tap", "Opens the editor for that preset."),
            HelpCard("Row subtitle", "Compact summary of which knobs the preset overrides — e.g. 'temp / max-tokens / top_p set'."),
            HelpCard("Empty state", "No presets yet — tap Add Parameter Preset to create the first one."),
        )
    ),
    "parameters_edit" to HelpContent(
        title = "Help - Parameters edit",
        cards = listOf(
            HelpCard("Overview", "Form for one preset. Save / Create activates once the name is valid; every other field is optional and blank means \"don't send this parameter\"."),
            HelpCard("Parameters block", "Temperature (0.0–2.0), Max tokens, Top P (0.0–1.0), Top K, Frequency / Presence penalty (-2.0–2.0), Seed. All free-text — ill-typed values become null on save."),
            HelpCard("System Prompt", "Multi-line text (3–6 visible lines). Sent as the system role to providers that accept it; folded into the user message for those that don't (Anthropic with thinking-only models, Mistral cohorts)."),
            HelpCard("Options", "Response format JSON, Enable web search (search:true flag — Perplexity-style), Web search tool (Anthropic / Gemini / OpenAI Responses-style tool block), Return citations."),
            HelpCard("Search Recency", "Filter chips: None / day / week / month / year. Honored only by providers that report supportsSearchRecency=true."),
            HelpCard("Reasoning Effort", "Filter chips: None / low / medium / high. Sent only to reasoning-capable models (gpt-5/o-series, Gemini thinking, Claude with extended thinking) — ignored by everything else."),
            HelpCard("Tips", "Numeric fields tolerate empty input — a blank stays null in the saved preset.")
        )
    ),
    "system_prompts" to HelpContent(
        title = "Help - System Prompts",
        cards = listOf(
            HelpCard("Overview", "CRUD list of reusable system-prompt blocks. A row stores a name and a single multi-line text body — assignable to Agents, Flocks, Swarms, Providers, and one-off Report runs."),
            HelpCard("Item rows", "Show the name in white, the first 80 characters of the prompt in dim text below. Tap to edit, trash to delete with confirmation."),
            HelpCard("Add System Prompt", "Top button opens an empty editor."),
            HelpCard("Tips", "Names are unique among system prompts (case-insensitive). The body is required for save."),
            HelpCard("Pitfalls", "If a worker also has a Parameters preset that carries a non-blank systemPrompt, that wins over the bare System Prompt — the preset's value is the late-merge winner."),
        )
    ),
    "system_prompts_list" to HelpContent(
        title = "Help - System Prompts",
        cards = listOf(
            HelpCard("Overview", "List of every saved system-prompt preset. Each preset is a named blob of text that an agent (or flock) can pin — the dispatcher prepends it as the first system message of every call."),
            HelpCard("Add System Prompt", "Top button opens the editor with a blank preset."),
            HelpCard("Row tap", "Opens the editor for that preset — change name and text."),
            HelpCard("Row subtitle", "First non-blank line of the prompt body, truncated."),
            HelpCard("Empty state", "No presets yet — tap Add System Prompt to create the first one."),
        )
    ),
    "system_prompt_edit" to HelpContent(
        title = "Help - System prompt edit",
        cards = listOf(
            HelpCard("Overview", "Form for one system prompt. Two fields: Name (single line, required, unique) and System prompt text (6–15 visible lines)."),
            HelpCard("Save", "Disabled while the name is invalid OR the prompt body is blank — the body is required, unlike Parameters' optional systemPrompt field."),
            HelpCard("Live counter", "\"N characters\" beneath the body, updated on every keystroke."),
            HelpCard("Tips", "Markdown is fine — most chat APIs forward it raw and many models treat it as styled hints."),
            HelpCard("Pitfalls", "There is no token-count check; oversized system prompts will count against context length at run time without a warning here."),
        )
    ),
    "internal_prompts_hub" to HelpContent(
        title = "Help - Internal prompts (hub)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub one level under AI Setup → Prompt management → Internal prompts. Groups the categories the app's internal flows consume: Meta, Fan out/in, Other internal, Worker, and Alternative. Each card opens the matching CRUD list (or, for Fan out/in, a deeper sub-hub)."),
            HelpCard("Meta prompts", "category=\"meta\". Rerank, Summarize, Compare, Moderation — run on the full report from the View → Actions card."),
            HelpCard("Fan out/in prompts", "Opens the dedicated sub-hub with the fan_out / fan_in categories."),
            HelpCard("Other internal prompts", "category=\"internal\". Templates consumed by app features (Translate, Model info, Intro). Last on the page so the more commonly-edited buckets sit at the top."),
            HelpCard("Worker prompts", "category=\"workers\". Prompts that carry an ordered list of workers (each an Agent or a Provider+Model) tried as a fallback chain. Edit-only; the chain isn't executed yet — wiring comes later."),
            HelpCard("Alternative prompts", "category=\"alt\". The *_alt variants the Find-alternative-icons / titles flows compose with their base prompt (the distinct-emoji / no-country-flag nudges). Edit-only."),
            HelpCard("Counts", "Each card's badge is the live count of prompts in that category (or the sum across the three fan-* categories for Fan out/in)."),
        )
    ),
    "fan_in_out_prompts_hub" to HelpContent(
        title = "Help - Fan out/in prompts (hub)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub one level under AI Setup → Prompt management → Fan out/in prompts. One card per fan-* category — every CRUD shares the same list / edit infrastructure as the other Internal Prompt buckets."),
            HelpCard("Fan Out", "category=\"fan_out\". Per-pair source-response template — runs across every (answerer × source) pair (N×(N−1) calls). Placeholders include @RESPONSE@."),
            HelpCard("Fan in, total", "category=\"fan_in\". Combined-report template — one run per source agent on a single picked model. Iterable block `***Report*** @REPORT@@RESPONSES@` expands once per source."),
            HelpCard("Tips", "Both fan-* categories share the FAN_CATEGORIES treatment in the editor — no agent dispatch, the agent slot is N/A. Names are unique within each category, not across — the same name can exist in fan_out and fan_in without collision."),
        )
    ),
    "internal_prompts" to HelpContent(
        title = "Help - Internal prompts (list)",
        cards = listOf(
            HelpCard("Overview", "CRUD list pinned to one category (meta / fan_out / fan_in / internal). The screen title and Add label adapt — Add meta prompt vs. Add fan-out prompt etc. Other internal is a fixed list — no Add / Delete."),
            HelpCard("Item rows", "Show name plus a chip line: ref · agent (omitted when *select), then \"— title or first 60 chars of body\". Tap to edit; trash to delete (hidden for Other internal)."),
            HelpCard("Add", "The button label reflects the active category (e.g. \"Add meta prompt\"). Hidden for Other internal."),
            HelpCard("Tips", "Sorted alphabetically by name. Edits stay scoped to this category — saving in the editor pushes back into the same bucket."),
        )
    ),
    "internal_prompts_list" to HelpContent(
        title = "Help - Internal prompts list",
        cards = listOf(
            HelpCard("Overview", "List of every internal prompt within one category — one of meta / fan_out / fan_in / rerank / internal. The screen title carries the category name."),
            HelpCard("Add", "When the category is editable, the top button opens a blank prompt editor pre-set to this category. The 'Other internal' bucket is fixed-list (no Add, no per-row 🗑) because those entries are bundled defaults."),
            HelpCard("Row tap", "Opens the prompt editor — change name, title, body. The category is locked to the screen's category."),
            HelpCard("Row subtitle", "Prompt title (or first body line when title is blank)."),
            HelpCard("Empty state", "No prompts in this category yet — tap Add (when available) to create one."),
        )
    ),
    "internal_prompt_edit" to HelpContent(
        title = "Help - Internal prompt edit",
        cards = listOf(
            HelpCard("Overview", "Form for one Internal Prompt. Category is fixed by where you arrived from (cannot be changed in this screen)."),
            HelpCard("Name / Title", "Name is unique within the category. Title is a short tag shown alongside the name on Fan out; optional. For Other internal the Name is read-only."),
            HelpCard("Append reference legend", "Switch — adds a [N] = Provider / Model footer to the response."),
            HelpCard("Agent dropdown", "*select = ask the user at run time (legacy default). *n/a = no agent applies (fan_out only). Anything else = the named agent in Settings.agents resolved at run time."),
            HelpCard("Worker prompts", "For the Workers category the single Agent / Provider+Model picker is replaced by an ordered list of workers — each row is one Agent OR one Provider+Model pick. They're intended as a fallback chain (try worker 1, then 2, …). Add / remove rows with the buttons; the running of the chain is not wired yet."),
            HelpCard("Template body", "8–22 visible lines. Helper text lists the placeholders allowed: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@ (meta); @RESPONSE@/@QUESTION@/@TITLE@/@DATE@/@COUNT@ (fan_out); @COUNT@, @FAN_OUT_COUNT@, the iterable @REPORT@@RESPONSES@ block with @RESPONSE@ inside (fan_in)."),
            HelpCard("Pitfalls", "If you deep-link into edit before aiSettings has bootstrapped, the screen shows a \"Loading…\" placeholder and re-keys when the prompt id resolves — saving an empty form there would silently create a duplicate, so it is blocked.")
        )
    ),
    "blocked_models" to HelpContent(
        title = "Help - Blocked models",
        cards = listOf(
            HelpCard("Overview", "A curated list of provider/model pairs flagged as \"blocked\" — dead models, wrong-endpoint models, ones that always error. Blocked pairs show dimmed (but still selectable) in every model picker."),
            HelpCard("Test all models", "On completion, a \"Test all models\" run syncs itself into this list: every model that errored is added with its error as the reason, and every model that passed is removed. Models the run didn't cover are left untouched."),
            HelpCard("Hand-curated", "Add / edit / delete rows yourself for anything the sweep didn't reach, or to write a clearer reason. Persisted with the rest of your configuration — included in Backup/Restore and Export/Import."),
        )
    ),
    "blocked_model_edit" to HelpContent(
        title = "Help - Blocked model",
        cards = listOf(
            HelpCard("Pick model", "Opens the same model picker the New Report \"+model\" button uses. Pick the provider/model pair to block."),
            HelpCard("Reason", "Free text shown in the list and beside the dimmed entry in pickers. Optional, but useful when you come back later."),
        )
    ),
    "test_excluded_models" to HelpContent(
        title = "Help - Test-excluded models",
        cards = listOf(
            HelpCard("Overview", "Provider/model pairs the \"Test all models\" sweep skips entirely — never enumerated, never probed, no Total/Done/Errors contribution."),
            HelpCard("Auto-add on expensive probes", "Any probe whose computed cost is more than 5¢ is added to this list on run completion. The next sweep won't pay for that model again. Removing an entry here makes the sweep probe it once more."),
            HelpCard("Hand-curated", "Add / edit / delete entries yourself for models you simply don't want tested. Persisted with the rest of your configuration — included in Backup/Restore and Export/Import."),
        )
    ),
    "test_excluded_model_edit" to HelpContent(
        title = "Help - Test-excluded model",
        cards = listOf(
            HelpCard("Pick model", "Opens the same model picker the New Report \"+model\" button uses. Pick the provider/model pair to exclude from the sweep."),
        )
    ),
    "external_services" to HelpContent(
        title = "Help - External Services",
        cards = listOf(
            HelpCard("Overview", "API keys for three non-LLM auxiliary services consumed by the app. Each field auto-saves on every keystroke (no debounce here)."),
            HelpCard("HuggingFace", "Used by the Model Info lookup to pull model cards / context-length / license fields. Get a free token at huggingface.co/settings/tokens."),
            HelpCard("OpenRouter", "Used for pricing data and model specifications (capability flags, supported parameters). Same key flows into Refresh → OpenRouter."),
            HelpCard("Artificial Analysis", "Pricing snapshot plus quality / speed scores. Free tier — sign up at artificialanalysis.ai/api. Used by Refresh → Artificial Analysis."),
            HelpCard("Tips", "Filling these unlocks the matching Refresh actions on the Refresh screen — without an OpenRouter key the OpenRouter button is disabled, same for Artificial Analysis."),
            HelpCard("Pitfalls", "These are NOT LLM provider keys — adding an OpenAI / Anthropic key here will not register the provider. Use Settings → AI Setup → Providers for that."),
        )
    ),
    "refresh" to HelpContent(
        title = "Help - Refresh",
        cards = listOf(
            HelpCard("Overview", "Bulk refresh hub. Top-level page has three rows: \"Refresh all\" at the top, then NavCards into \"AI Info Providers\" and \"AI Runtime workers\" — each opens a dedicated full screen with its own help."),
            HelpCard("Refresh all (auto-restart)", "Sequence: OpenRouter (if key) → LiteLLM → models.dev → Helicone → llm-prices.com → Artificial Analysis (if key) → Providers → Models → Default agents → kill+relaunch via FLAG_ACTIVITY_NEW_TASK / CLEAR_TASK. Saves you from a manual kill/relaunch. Tapping the button routes to the Refresh-all progress screen."),
            HelpCard("AI Info Providers (sub-page)", "Catalog-source refreshes: OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis. The page's own \"All info providers\" runs the six in parallel without touching per-provider tests."),
            HelpCard("AI Runtime workers (sub-page)", "Per-AppService work: Provider key tests, Model lists, Default agents. The page's own \"All runtime workers\" runs the three sequentially without touching the catalogs."),
            HelpCard("Pitfalls", "OpenRouter and Artificial Analysis buttons inside AI Info Providers disable themselves until you set their keys under External Services."),
        )
    ),
    "refresh_info_providers" to HelpContent(
        title = "Help - Info Providers",
        cards = listOf(
            HelpCard("Overview", "Refresh-screen sub-page for the six external metadata catalogs (model pricing, capability flags, supported parameters). They have no per-AppService side effects, so the page's \"All info providers\" runs them in parallel."),
            HelpCard("All info providers", "Runs OpenRouter (if its key is set), LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis (if its key is set) in parallel via the same full-screen progress page Refresh-all uses. Skips Providers / Models / Default agents."),
            HelpCard("OpenRouter", "Pulls the OpenRouter catalog: pricing, capability flags, and supported parameters. Disabled until the OpenRouter External Services key is set."),
            HelpCard("LiteLLM", "Downloads model_prices_and_context_window.json from BerriAI/litellm — primary source for pricing and capability flags."),
            HelpCard("models.dev", "Pulls the models.dev community catalog. LiteLLM fallback for newer models / -latest aliases LiteLLM hasn't picked up yet."),
            HelpCard("Helicone", "Pulls helicone.ai/api/llm-costs. Pricing-only fallback after LiteLLM and models.dev."),
            HelpCard("llm-prices.com", "Pulls Simon Willison's curated per-vendor pricing tables (10 vendors). Useful as a tiebreaker on the major commercial providers."),
            HelpCard("Artificial Analysis", "Pulls pricing + intelligence_index + output speed. Disabled until the Artificial Analysis key is set under External Services."),
            HelpCard("Capability recompute", "LiteLLM and models.dev refreshes call aiSettings.recomputeAllCapabilities() so vision / web-search precomputed sets pick up the new catalog. Helicone is pricing-only — no recompute."),
            HelpCard("Tips", "Each card has its own ℹ️ button that deep-links to that catalog's per-provider help page (the same one you reach from Model Info → Source button).")
        )
    ),
    "refresh_all" to HelpContent(
        title = "Help - Refresh all",
        cards = listOf(
            HelpCard("Overview", "Refreshes the six pricing/spec catalogs (OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis) AND the per-provider Workers in parallel. The two phases are independent — neither blocks the other."),
            HelpCard("Workers", "For every provider that has an API key and isn't marked Inactive, in parallel: tests the API key, fetches the model list (only when the model source is API — MANUAL providers skip the fetch), writes a default agent named after the provider id, and appends that agent to the `default agents` flock."),
            HelpCard("Clean slate", "At the start of the run the `default agents` flock is emptied and every existing default agent (any agent whose name matches its provider id) is deleted. Custom agents — anything you renamed away from a provider id — survive untouched. The flock and its agents are then rebuilt from the workers that pass."),
            HelpCard("Re-entry", "Tasks continue in the background if you back-gesture out. Open Refresh again while a run is in flight and the live progress screen comes back. Tap the help icon on the progress screen to read this page without stopping anything."),
            HelpCard("Failed providers", "Workers that fail the key test list at the bottom with a tap-target that opens that provider's settings so you can fix the key / endpoint."),
            HelpCard("Restart", "When both phases finish, a 'Restart application' button appears at the top of the page — tap to relaunch so freshly-loaded catalogs, capabilities, and rebuilt agents take effect cleanly. The app stays usable while the button is showing, but in-memory state is out of sync with disk until you tap it.")
        )
    ),
    "import_export" to HelpContent(
        title = "Help - Export / Import",
        cards = listOf(
            HelpCard("Overview", "Two card groups (Export, Import). All exports use Android's Storage Access Framework (CreateDocument) — the picker decides where the file lands; the app never sees the location."),
            HelpCard("Export · API Keys", "Just the keys (per-provider + HuggingFace + OpenRouter + Artificial Analysis) as a flat JSON object. Toast reports the populated key count."),
            HelpCard("Export · Costs Overrides", "Manual cost overrides as CSV (provider,model,input_per_million,output_per_million). Only rows the user explicitly added through Add Manual Override or via Manual cost overrides → Import manual changed costs."),
            HelpCard("Export · providers.json / prompts.json", "providers.json is a drop-in for the bundled asset; prompts.json is a flat array of all internal prompts (text inline) that re-imports cleanly. No API keys included. Useful when shipping a tuned catalog as new defaults."),
            HelpCard("Export · Workers", "Agents + Flocks + Swarms in one file shaped { agents, flocks, swarms }. References to parameter sets, system prompts, and provider ids stay as ids — dangling on a target where those don't exist, but the worker still loads."),
            HelpCard("Export · Example prompts", "Drop-in shape for assets/examples.json — top-level array of {title, text} objects, no ids."),
            HelpCard("Export · Settings", "GeneralSettings (userName, defaultEmail, defaultTypePaths, tracingEnabled, modelNameLayout) as a JSON object. Excludes the three info-provider API keys — those round-trip through API Keys instead."),
            HelpCard("Export · Model lists", "Per-provider model lists keyed by provider id ({ \"OPENAI\": [\"gpt-4o\", …], … }). Sorted by provider id so successive exports diff cleanly."),
            HelpCard("Export · Parameters / System prompts", "Settings.parameters and Settings.systemPrompts as top-level JSON arrays. Imported entries upsert by id (existing rows with a matching id are replaced)."),
            HelpCard("Export · All including / excluding API keys", "Two variants of one bundle. Shape: { apiKeys?, costs, providers, prompts, examples, agents, flocks, swarms, settings, modelLists, parameters, systemPrompts }. Identical except the \"excluding\" variant omits the apiKeys section — safer for sharing with a colleague or pushing to a public repo."),
            HelpCard("Import", "One button per export shape, plus a full-width All that reads either bundle variant (sections are optional, missing ones are skipped). Workers and All upsert agents / flocks / swarms by id; bad rows (e.g. references to a deleted provider) are logged and skipped, the rest go through. Example prompts upsert by case-insensitive title; Parameters and System prompts upsert by id; Model lists replace the per-provider list."),
            HelpCard("Pitfalls", "Feeding a legacy full-config bundle to API Keys import throws ConfigBundleMistakenForKeysException — the toast clarifies the file shape isn't a keys file. Costs CSV importer skips malformed rows silently. Settings import leaves missing fields untouched (partial files are fine)."),
        )
    ),
    "setup_models" to HelpContent(
        title = "Help - Models setup",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Three cards: Models (per active provider), Model Types (default API path per type), Manual model types overrides (per-model type assignments)."),
            HelpCard("Models", "Disabled until you have at least one active provider. Drills into the per-provider model lists. Count = total models across active providers."),
            HelpCard("Model Types", "List of the 9 model kinds from ModelType.ALL with their default API paths. Count = number of types."),
            HelpCard("Manual model types overrides", "CRUD for (provider, model, type, capabilities) overrides that win over autodetection. Count = number of overrides currently saved."),
            HelpCard("Tips", "Resolution order at dispatch: per-provider Type paths (Provider edit → Definition · API) → Model Types defaults → ModelType.DEFAULT_PATHS hardcoded fallback."),
            HelpCard("Pitfalls", "If no provider is active the Models card stays grey-blue and unclickable."),
        )
    ),
    "setup_workers" to HelpContent(
        title = "Help - Workers (setup)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Four cards (Models / Agents / Flocks / Swarms) — all disabled until at least one provider has an API key set. Below the cards, a small diagram shows how they connect."),
            HelpCard("Models", "The cross-provider model browser (moved here from the home page). Tap a model to open its Model Info page. Count = total models across active providers."),
            HelpCard("Agents", "Named (provider, model, key, params, prompt, endpoint) tuples. Count = active agents (whose provider is currently active)."),
            HelpCard("Flocks", "Groups of agents with optional shared parameters/system prompt. Count = number of flocks."),
            HelpCard("Swarms", "Groups of (provider, model) pairs without going through agents. Count = number of swarms."),
            HelpCard("Diagram", "Reads top-to-bottom: a provider has many models; an agent is a model with a system prompt and parameters; a flock is a collection of agents; a swarm is a collection of models."),
            HelpCard("Tips", "When you import a config bundle these counts jump in lockstep with the imported data."),
            HelpCard("Pitfalls", "If your only API key was just removed, all cards lock — the hub uses aiSettings.hasAnyApiKey() as its enable gate."),
        )
    ),
    "setup_prompts" to HelpContent(
        title = "Help - Prompt management (setup)",
        cards = listOf(
            HelpCard("Overview", "Five NavCards: System Prompts, Meta prompts, Fan out/in prompts, Other internal prompts, and Example prompts."),
            HelpCard("System Prompts", "Direct CRUD list. Count = number of system prompts."),
            HelpCard("Meta prompts", "Rerank, Summarize, Compare, Moderation — run on the full report (one final call). category=\"meta\"."),
            HelpCard("Fan out/in prompts", "Forwards to a sub-hub holding the two fan-* category CRUDs (fan_out, fan_in). The badge count is the sum across both buckets."),
            HelpCard("Other internal prompts", "Fixed list — chat-title, model-info, model-intro, translate-text, translate-title, second-rerank, second-moderation, test-model. Editable but not addable / deletable. category=\"internal\"."),
            HelpCard("Example prompts", "Two-field CRUD (title, text). Pure data the user curates; no placeholder substitution, no agent dispatch, no app feature consumes them automatically."),
            HelpCard("Tips", "System Prompts are referenced by id from Agents/Flocks/Swarms/Providers; Internal Prompts are referenced by name + category by app features. Example prompts are referenced by nothing — they're just a personal library."),
            HelpCard("Pitfalls", "System and Internal prompts are NOT interchangeable — Internal use placeholder substitution that System does not.")
        )
    ),
    "housekeeping" to HelpContent(
        title = "Help - Housekeeping",
        cards = listOf(
            HelpCard("Overview", "Maintenance hub. Each row is a NavCard that drills into its own full screen with its own help text — tap the row to enter, ℹ️ for the per-screen detail."),
            HelpCard("The six rows", "Backup & Restore · Export & Import · Refresh · Trim by age · Usage statistics · Reset. Order is roughly safe → destructive. Prompt-bundle maintenance and manual-cost-overrides cleanup live under AI Setup → Prompt management / Costs — those screens already host the per-row CRUD they're paired with."),
            HelpCard("Tips", "Backup before any of the destructive screens — Reset, Clear runtime data, and Clear all configuration are not undoable."),
        )
    ),
    "backup_restore" to HelpContent(
        title = "Help - Backup & Restore",
        cards = listOf(
            HelpCard("Overview", "Single-zip whole-app backup and restore. Uses Android's Storage Access Framework so the destination/source picker shows Google Drive, Dropbox, OneDrive, local files — whichever cloud apps you have installed. The app never sees the underlying location."),
            HelpCard("Backup", "Green button. Default filename `ai-backup-<yyyymmdd>.zip`. Includes configuration, API keys, reports, chats, API traces, prompt cache."),
            HelpCard("Restore", "Blue button. Picker is restricted to .zip plus application/octet-stream (some providers report .zip with the latter mime). Confirmation dialog appears first. The restore is validate-then-write: the zip is parsed before any current file is touched."),
            HelpCard("Auto-restart", "On successful restore the app shows a toast, waits ~800ms, then relaunches itself with FLAG_ACTIVITY_NEW_TASK + CLEAR_TASK and kills the current process. The next launch reads the restored data fresh."),
            HelpCard("Pitfalls", "A failed restore leaves the device in a partial state (validate-then-write reduces the window but cannot eliminate it)."),
        )
    ),
    "trim_by_age" to HelpContent(
        title = "Help - Trim by age",
        cards = listOf(
            HelpCard("Overview", "Bulk-deletes reports, chat sessions, and API trace files older than a cutoff. Configuration, API keys, prompt history, usage statistics — all kept."),
            HelpCard("Days-to-keep field", "Digits-only, max four. Defaults to 30. Clear button is disabled until the value is a positive integer."),
            HelpCard("Confirmation", "Tapping the orange button opens a dialog that shows the exact per-kind count (\"Permanently deletes everything older than N days: X reports, Y chat sessions, Z trace files\"). Confirm fires the deletes."),
            HelpCard("Pitfalls", "Cannot be undone. Counts are computed once when the dialog opens — if a chat updates between dialog open and Confirm tap, the actual delete may differ slightly."),
        )
    ),
    "usage_statistics" to HelpContent(
        title = "Help - Usage statistics",
        cards = listOf(
            HelpCard("Overview", "One purple button that empties the per-(provider, model) call counts, token totals, and accumulated cost. The AI Usage screen empties out; reports, chats, traces, configuration, and pricing tiers stay intact."),
            HelpCard("Confirmation", "None — the action is one tap, confirmed via toast (\"Usage statistics cleared\")."),
            HelpCard("Tips", "Stats are accumulated lazily from API calls — they'll start filling back in the next time you run a report or chat."),
        )
    ),
    "example_prompts_list" to HelpContent(
        title = "Help - Example prompts",
        cards = listOf(
            HelpCard("Overview", "List of every saved example prompt. Examples surface in the Hub's prompt-history flow and in the picker reached by Start with a previous prompt — quick-pick a saved prompt body instead of retyping."),
            HelpCard("Add Example", "Top button opens the editor with a blank example."),
            HelpCard("Row tap", "Opens the editor — change title and body."),
            HelpCard("Row subtitle", "First non-blank line of the body, truncated."),
            HelpCard("Empty state", "No examples yet — tap Add Example to create the first one. Load fresh examples from assets/examples.json via the Internal-prompts loader."),
        )
    ),
    "example_prompt_edit" to HelpContent(
        title = "Help - Example prompt (edit)",
        cards = listOf(
            HelpCard("Overview", "Two-field CRUD: Title (required, also the de-dup key for Load new prompts) plus Text (free-form template body)."),
            HelpCard("Title", "Required. Used as the case-insensitive de-dup key when Housekeeping → Prompts → Add new prompts from assets/examples.json runs — same title means the bundled row is skipped."),
            HelpCard("Text", "Multi-line body, no enforced placeholder set. Free-form starter the user pastes into the New Report prompt field; the app does not substitute anything automatically."),
            HelpCard("Tips", "Example prompts are pure data, not bound to any app feature. Add as many as you like; reorder via Title since the list sorts alphabetically."),
        )
    ),
    "reset" to HelpContent(
        title = "Help - Reset",
        cards = listOf(
            HelpCard("Overview", "Hub of five destructive operations, each drilling into its own full screen with its own help topic. Order is roughly safe → destructive: runtime data → Info provider caches → all configuration → asset restores → full app reset."),
            HelpCard("Clear runtime data", "Wipes app logs, chats, API traces, AI reports (incl. their secondary rows), prompt history, and usage stats. Configuration and all caches survive. Tap the row for the full description and the wipe button."),
            HelpCard("Clear Info providers", "Drops the per-provider pricing tier blobs from the six Info providers plus the OpenRouter model-specs cache. Manual overrides and Together's native pricing survive."),
            HelpCard("Clear all configuration", "Wipes every provider's API key, models, endpoints; every agent / flock / swarm; every prompt and parameter preset; External Services keys. Reports, chats, traces, and usage stats are kept."),
            HelpCard("assets/*.json", "Three per-file restore buttons (providers / prompts / examples). Each drops the targeted list and reloads it from the bundled JSON; nothing outside that list is touched."),
            HelpCard("Reset application", "Factory-style — keeps API keys but wipes everything else, reloads providers + internal prompts from assets, then runs the Refresh-all chain. Gated by a type-RESET dialog and force-restarts the app on success."),
            HelpCard("Pitfalls", "Each leaf screen has its own confirmation dialog. Reset application's confirmation is CASE-sensitive (literally \"RESET\", trimmed). The other four are immediate after the dialog."),
        )
    ),
    "reset_runtime" to HelpContent(
        title = "Help - Clear runtime data",
        cards = listOf(
            HelpCard("Overview", "Wipes the activity + personal-history surface that accumulates while the app is in use. The wipe completes immediately after confirmation; a Toast reports the per-bucket counts."),
            HelpCard("What it wipes", "Rolling app logs under <filesDir>/applog/, every chat session, every API trace file, every AI report (the report JSON + its cascaded SecondaryResult rows for rerank / summary / fan-out etc.), the prompt-history file, and the usage-statistics ledger."),
            HelpCard("What it keeps", "The six Info-provider pricing caches and the per-provider model-list cache. Configuration (providers, agents, flocks, swarms, system / internal / example prompts, parameters, API keys, External Services keys) is fully preserved."),
            HelpCard("When to use", "Privacy-driven cleanup — chats, traces, reports and prompt history contain copies of your prompts and the model responses. Also useful when you want to start a clean activity baseline without losing any setup."),
            HelpCard("Pitfalls", "Reports go through SecondaryResultStorage.deleteAllForReport on the way out, so all the fan-out / rerank / summary rows for each report disappear too. The wipe is destructive — Backup & Restore is the only undo path. The Application log viewer goes empty until the app writes new entries."),
        )
    ),
    "reset_info_providers" to HelpContent(
        title = "Help - Clear Info providers",
        cards = listOf(
            HelpCard("Overview", "Wipes the per-provider pricing tier blobs the layered pricing lookup reads from, plus the OpenRouter model-specs cache. Pricing falls back to DEFAULT_PRICING until Refresh repopulates."),
            HelpCard("What it wipes", "Per-tier JSON blobs under <filesDir>/pricing/, the timestamps in pricing_cache.xml, and the OpenRouter model-specs cache. Covers all six Info providers: OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis."),
            HelpCard("What it keeps", "Manual cost overrides (they sit above the Info tiers in the layered lookup), Together's native self-reported pricing, every provider's models / API key / endpoints, and everything else outside the pricing surface."),
            HelpCard("When to use", "When a tier shipped a bad price and you want to force a fresh fetch on the next Refresh, or when troubleshooting the layered lookup."),
            HelpCard("Pitfalls", "Until Refresh re-runs, every model that depends on Info-tier pricing renders as DEFAULT_PRICING — usage / cost numbers will look wrong until you Refresh."),
        )
    ),
    "reset_configuration" to HelpContent(
        title = "Help - Clear all configuration",
        cards = listOf(
            HelpCard("Overview", "Wipes every piece of the app's configuration surface — keys, providers, workers, prompts — in one shot. Reports, chats, traces, and usage stats are preserved."),
            HelpCard("What it wipes", "Every provider's API key, model list, endpoints; every agent, flock, swarm; every parameter preset; every system prompt, internal prompt, example prompt; HuggingFace / OpenRouter / Artificial Analysis keys; user name + default email."),
            HelpCard("What it keeps", "Reports, chats, traces, usage statistics, the six Info-provider pricing caches, the OpenRouter model-specs cache, and the per-provider model-list cache."),
            HelpCard("When to use", "Starting over with a fresh provider/agent setup while keeping your accumulated reports and chats. Less surgical than the asset-restore options; less destructive than Reset application."),
            HelpCard("Pitfalls", "There is no undo apart from Backup & Restore."),
        )
    ),
    "reset_assets" to HelpContent(
        title = "Help - assets/*.json",
        cards = listOf(
            HelpCard("Overview", "Three per-file restore buttons — providers / prompts / examples. Each drops every entry in the matching list and reloads it from the bundled JSON asset. Scoped: a providers restore doesn't touch prompts and vice versa."),
            HelpCard("back to assets/providers.json", "Drops every provider definition currently in the registry (including any hand-edited fields) and reloads assets/providers.json verbatim. Per-provider API keys, model lists, and agents live outside the registry and survive."),
            HelpCard("back to assets/internal-prompts/", "Drops every Internal prompt (including any you customized) and reloads the bundled assets/internal-prompts/ tree (one folder per category, a .json + .txt pair per prompt). Categories (meta / fan_out / fan_in / icons / info / intra / internal) all reset together."),
            HelpCard("back to assets/examples.json", "Drops every Example prompt (including any you authored) and reloads assets/examples.json. Doesn't touch Internal or System prompts."),
            HelpCard("When to use", "Quick rollback when an edit went wrong, or when you've forked the bundle and want to see what the bundled values currently look like compared to your custom set."),
            HelpCard("Pitfalls", "User-authored entries in the targeted list are wiped. Use Backup first if you have hand-built prompts you want back. Bundled-asset failures (missing file, parse error) leave the targeted list empty — the Toast reports failure."),
        )
    ),
    "reset_application" to HelpContent(
        title = "Help - Reset application",
        cards = listOf(
            HelpCard("Overview", "Factory-style reset. API keys (per-provider plus HuggingFace / OpenRouter / Artificial Analysis) are preserved; everything else is wiped; providers + internal prompts reload from assets."),
            HelpCard("Confirmation gate", "A plain Reset / Cancel dialog gates the action — no type-to-confirm. Tap Reset to run."),
            HelpCard("What survives", "API keys (per-provider + 3 external). That's it."),
            HelpCard("What dies", "Agents, flocks, swarms, parameter presets, system prompts, custom-added providers, per-agent API key overrides, custom endpoints, all reports / chats / traces / prompt history / usage statistics, pricing and model-list caches."),
            HelpCard("After it runs", "A four-button banner appears at the top of the page: Refresh all, Refresh providers/models/default agents, Restart application, or Import API keys. Pick one — the in-memory state isn't fresh until you restart (directly or via one of the Refresh paths)."),
        )
    ),
    "ai_live_dashboard" to HelpContent(
        title = "Help - Live Dashboard",
        cards = listOf(
            HelpCard("Overview", "A live ops monitor — what's happening right now, reached from Monitor → Live Dashboard. A Pinned / All switch at the top picks the view (it starts on Pinned each time you open the screen). Pinned shows just the cards you've pinned, each open and control-free. All shows every card with its 📌 pin, ↑ / ↓ reorder and ▾ / ▸ collapse controls — pin the cards you want on the Pinned view from here. Pins + order are saved and ride along in backup/restore. For performance, in All mode a card fetches and ~0.75 s-refreshes only while expanded; on the Pinned view your pinned cards are all open, so keep that set lean. Lifetime totals + costs live on the Statistics hub (under Monitor)."),
            HelpCard("🟢 Live activity", "Hero number is global API calls in flight / the global cap, with an Idle / Active / Saturated badge. The Global bar is always shown; the per-feature bars (Report, Translation, Fan-out, Fan Meta) appear only while that feature has a call in flight — green under 60 %, orange approaching the cap, red at the cap. The caps are owned by Settings → Network settings and count live API calls (a pair parked on a provider's rate-limit holds no cap permit). An orange Throttled row appears when batches are waiting on a provider rate-limit."),
            HelpCard("🏃 Active runs", "What's actually running right now — translation, fan-out, regenerate and fan-meta batches by name, with done/total progress (fan-meta shows in-flight count, which is all it can attribute). A \"Parked on a provider gate\" line counts items waiting on a rate-limit. Hidden when nothing is in flight; a live \"Test all models\" run has its own card below."),
            HelpCard("💸 Spend & tokens", "Rolling cost and token throughput over the last 1 minute and 5 minutes — the 1-minute column is the live per-minute rate. Fed from the same usage chokepoint every paid call funnels through (report / chat / secondary / translation / fan-meta). Cost is priced through the pricing cache (so it tracks computed, not provider-reported, cost). In-memory only."),
            HelpCard("📊 HTTP responses", "Top line shows calls/min and success rate over the last minute. Below, a rolling tally of every network response code over the last 1 minute and last 5 minutes — ✅ 2xx, 🚧 429, ⚠️ 4xx, 🔥 5xx, and ▫️ other (network failures, 1xx/3xx). Counts each attempt, so the per-attempt 429s the retry logic later swallows still show here. In-memory only; resets on app restart. Tap a row's 🐞 to open the API Traces filtered to that status class (needs tracing enabled to show entries)."),
            HelpCard("⚠️ Recent errors", "A live feed of the most recent error responses and network failures — host · model · status code · message · age. Captured at the network layer (independent of API tracing), so it shows even when tracing is off. Hidden when there have been no errors recently."),
            HelpCard("⏱️ Response times", "Aggregate API response time over the same 1 minute / 5 minute windows — average, fastest, median, p95 and slowest — across every response that came back (thrown failures carry no time). The duration is the round-trip to the response (time-to-first-byte for a streaming call). A dash means no samples in that window. In-memory only."),
            HelpCard("🐌 Slowest calls", "The three slowest calls in the last 5 minutes (host / model · duration), slowest first — names the actual call behind the Response-times p95/max. Hidden when there are no samples."),
            HelpCard("🌐 Provider throttle", "Top line (when there's pressure) summarises items parked on a gate, retry attempts in the last minute, and calls currently sleeping in a retry backoff. Below, one row per host with an active gate, busiest first, host trimmed to its last two labels (generativelanguage.googleapis.com → googleapis.com): 'con' = concurrency in-use/limit, 'min' = calls in the trailing 60 s window vs the per-minute cap. Red when no concurrency slot is free. \"Idle — no active hosts\" when nothing is running. Tap a row's 🐞 to open the API Traces filtered to that host."),
            HelpCard("❄️ Model cooldowns", "Models benched by a long 429 retry-hint, with time remaining; hidden when none are cooling down. Manage/clear them from Housekeeping → Model states."),
            HelpCard("🧪 Test all models", "A live \"Test all models\" run's progress — a bar plus passed / failed / running / queued / cost / elapsed. Reflects a run started this session or restored from disk; \"No test run active\" when there's none."),
            HelpCard("🔥 Stress test", "The stress test is fire-and-forget — it submits one report per Example Prompt, then those generate in the background like any report. This card shows the submit status (submitting / submitted / error), how many runs and reports were launched this session, and how long ago. \"No stress test this session\" when none has run."),
            HelpCard("🩺 System health", "Log-writer status (OK / ERROR), dropped log lines, trace-file count + size on disk, API activity, the streaming/non-streaming read timeouts and the per-minute cap currently in effect. Disk sizes refresh on a slow tick."),
            HelpCard("Tips", "Open this while a Stress test (Housekeeping → Test) runs to watch the caps fill, hosts saturate and batches move through throttling in real time."),
            HelpCard("Pitfalls", "The live ticker only runs while the screen is on top — navigate away and it stops."),
        )
    ),
    "ai_monitor" to HelpContent(
        title = "Help - Monitor",
        cards = listOf(
            HelpCard("Overview", "The hub for watching what the app is doing — live and after the fact. Opened from the home 📡 AI Monitor card. Each row opens its own screen. The lifetime-aggregate stat pages live one level down, under the 📊 Statistics card."),
            HelpCard("📡 AI Live Dashboard →", "Real-time runtime state: in-flight calls, per-provider concurrency caps, and throttle / cooldown status."),
            HelpCard("🐞 AI API Traces →", "The per-call request/response records (filterable by category, provider, host, model). Needs API tracing enabled in Settings. Its 📈 icon opens the API trace statistics."),
            HelpCard("📜 Application log →", "The in-app application log, line by line. Its 📈 icon opens the App log statistics."),
            HelpCard("📊 Statistics →", "Own hub: the lifetime-aggregate stat pages — Reports, Providers, Models, Spend & usage, Costs tiers — plus Trace and App-log statistics."),
        )
    ),
    "ai_statistics" to HelpContent(
        title = "Help - Statistics",
        cards = listOf(
            HelpCard("Overview", "The hub for every lifetime-aggregate stat page. Opened from the 📊 Statistics card on the Monitor hub. Each row opens its own screen, so the heavier breakdowns only compute when opened. The 📈 icon on each sub-page jumps back here."),
            HelpCard("📋 Reports →", "Own page: report totals (running / problems / completed, agent calls, error rate, spend) and secondary-result counts by kind."),
            HelpCard("🔌 Providers →", "Own page: per-provider keys, API formats, catalog caches, throttle caps, and last test-run results."),
            HelpCard("🧠 Models →", "Own page: capabilities, types, context-length buckets, model states, deprecation, and models-per-provider."),
            HelpCard("💰 Spend & usage →", "Own page: calls / tokens / cost over expandable per-provider cards, model→Model Info drill-in, and 🧹 clear-stats. Runs getPricing per used model."),
            HelpCard("🧮 Costs tiers →", "Own page: which pricing tier each model resolves to (Config vs Runtime columns) plus the pricing-cache catalog table."),
            HelpCard("🐞 Trace statistics →", "Own page: aggregate stats over the API traces — status split, top hosts / models / categories, activity, and report linkage. Also reachable from the 📈 icon on the API Traces screen."),
            HelpCard("📜 App log statistics →", "Own page: aggregate stats over the application log — health, by-level and by-tag counts, and per-file sizes. Also reachable from the 📈 icon on the Application log screen."),
            HelpCard("📡 🐞 📜 📊 jump row", "Every screen under Monitor — this hub, its stat pages, the Live Dashboard, API Traces and Application log — carries these four icons at the start of the bottom bar, one per Monitor section, so you can hop straight between them without backing out to the hub. On the four main sections the icon for the screen you're already on is dropped."),
        )
    ),
    "ai_trace_stats" to HelpContent(
        title = "Help - API trace statistics",
        cards = listOf(
            HelpCard("Overview", "Aggregate stats over the API traces (the per-call request/response records the 🐞 viewer shows individually). Reached from the 📈 icon on the API Traces screen or the 🐞 card on the Statistics hub. Reads the cached trace list, so it's fast. Empty when tracing is off."),
            HelpCard("🐞 Overview", "Whether tracing is on, total traces, distinct batch runs, and how many are partial (a streaming response still being read)."),
            HelpCard("📡 Status", "HTTP outcome split — 2xx success / 429 rate-limited / other 4xx / 5xx / transport-failed (status 0) / other. Tap any row to open the API Traces list showing only that outcome."),
            HelpCard("Top hosts / models / categories", "The busiest provider hosts, models, and call categories (Report / meta / chat / pricing fetch / …) by trace count. Shows the top 5; tap the card to see every entry, then tap a row to open the traces for just that host / model / category."),
            HelpCard("🗓️ Activity", "Traces from today / the last 7 / 30 days, plus the timestamps of the oldest and newest trace."),
            HelpCard("📋 Reports", "How many traces are tied to a report, across how many distinct reports, and the average traces per report."),
        )
    ),
    "ai_trace_breakdown" to HelpContent(
        title = "Help - Trace breakdown",
        cards = listOf(
            HelpCard("Overview", "The full list behind a 'Top hosts / models / categories' card on API trace statistics — every host, model, or category, ranked by trace count (not just the top 5)."),
            HelpCard("Drill down", "Tap any row to open the API Traces list filtered to just that host / model / category. If the filter matches a single trace, it opens that trace directly."),
            HelpCard("📈", "The chart icon (or the title) returns to the Statistics hub."),
        )
    ),
    "ai_log_stats" to HelpContent(
        title = "Help - App log statistics",
        cards = listOf(
            HelpCard("Overview", "Aggregate stats over the in-app application log (what the App log viewer shows line by line). Reached from the 📈 icon on the Application log screen or the 📜 card on the Statistics hub. File-level numbers are instant; line-level counts parse the most recent log files."),
            HelpCard("🩺 Health", "The current log level (Settings → Logging), whether the file writer is OK, and how many lines were dropped on write failures."),
            HelpCard("📊 By level", "Entry counts by level — Error / Warn / Info / Debug / Trace — plus the total entries parsed."),
            HelpCard("🏷️ Top tags", "The most frequent log tags (ApiTrace / Throttle / FanOut / Report / …)."),
            HelpCard("🗂️ Files", "Number of daily log files, total size on disk, the date range, and a per-file size list (most recent first)."),
            HelpCard("Pitfalls", "To stay fast, line-level counts (levels / tags / total entries) parse only the most recent ~14 daily files; file count and total size cover them all."),
        )
    ),
    "ai_stat_reports" to HelpContent(
        title = "Help - Reports",
        cards = listOf(
            HelpCard("Overview", "Everything the reports on disk add up to, re-read on resume and every ~10 s. Reached from the Statistics hub."),
            HelpCard("📋 Reports", "Total / running / problems / completed, total agent calls, error rate, stopped agents, report spend. Running/problems use the same predicates as the AI Reports hub."),
            HelpCard("🤖 Agent calls", "Status split (success / error / stopped / in-flight) across every per-model call in every report, the error-rate bar, and the average number of models per report."),
            HelpCard("💵 Tokens & spend", "Input / output / total tokens over all agent calls, plus secondary tokens; report spend, secondary spend and the combined total; average cost per report and per call; and total compute time (summed call durations)."),
            HelpCard("🗓️ Activity", "Reports created today / in the last 7 / 30 days, how many are pinned, and the age of the oldest report (by stable creation time, falling back to last-changed for legacy reports)."),
            HelpCard("✨ Features used", "How many reports used Vision (image), Web search, Reasoning, are Translated copies, or are Table-type reports."),
            HelpCard("🏆 Top models / 🔌 Top providers", "The six most-called models and providers across all reports, by number of agent calls."),
            HelpCard("🔗 Secondary results", "Counts of every stored secondary by kind (Rerank / Meta / Moderation / Translate) plus the top meta-prompt names."),
            HelpCard("Pitfalls", "Heavy — one report scan plus a secondary read per report — so it lives on its own page and refreshes on a 10 s tick. Token/duration totals only count what providers reported."),
        )
    ),
    "ai_stat_providers" to HelpContent(
        title = "Help - Providers",
        cards = listOf(
            HelpCard("Overview", "A provider-focused view — aggregate cards on top, then one expandable card per provider. All in-memory or a cheap file-stat, so it opens fast (no pricing lookups or network). Model-level stats are on the separate Models page."),
            HelpCard("Aggregate cards", "Providers (configured / active / with key / inactive); API formats (OpenAI-compatible / Anthropic / Google); Catalog cache (cached / stale > 7 days / never fetched); Workers (agents / flocks / swarms); and the last Test-all-models result when one exists."),
            HelpCard("Per-provider rows", "One card per provider, active first then by model count. Header: a green/grey dot for active, the provider id, an API-format tag, and the model count; a compact line shows the non-zero signals (👁 vision, 🌐 web, 🧠 reasoning, ❄️ cooling, ⛔ blocked) and \"no key\" if unconfigured."),
            HelpCard("Expand a provider", "Tap to reveal: default model, host, API key, the concurrency + per-minute caps (the per-provider override or the inherited global), catalog age, a by-type breakdown, a model-state breakdown, and this provider's passed/failed from the last Test-all-models run."),
        )
    ),
    "ai_stat_models" to HelpContent(
        title = "Help - Models",
        cards = listOf(
            HelpCard("Overview", "Catalog-wide model stats across every configured model. In-memory; opens fast. Provider-level info is on the separate Providers page."),
            HelpCard("🧠 Models", "Total configured models plus how many are Vision / Web-search / Reasoning capable and how many are Embedding models."),
            HelpCard("🛠️ Capabilities", "From each provider's /models metadata: models supporting function calling, native PDF input, exposing reasoning-effort levels, and how many have capability metadata at all."),
            HelpCard("🏷️ By type", "Model count per type (chat / image / tts / embedding / rerank / …)."),
            HelpCard("📏 Context length", "Models bucketed by context window (< 32K / 32–128K / 128K–1M / ≥ 1M / unknown) plus the single largest-context model."),
            HelpCard("🚦 States", "Blocked / inaccessible / test-excluded / cooling counts — matches Housekeeping → Model states."),
            HelpCard("⚰️ Deprecated / 🔌 Models per provider", "How many models carry a deprecation flag, and the average / max models per provider with a top-6 leaderboard."),
        )
    ),
    "ai_spend_usage" to HelpContent(
        title = "Help - Spend & usage",
        cards = listOf(
            HelpCard("Overview", "Calls, tokens and cost across every model you've actually called. Reached from the Statistics hub. Computed only when opened (it runs getPricing per used model), and on entry it refreshes OpenRouter pricing once if a key is set."),
            HelpCard("Summary", "Top card: total calls, total tokens, and total cost (green)."),
            HelpCard("Provider table", "Columns Provider · Calls · Tokens · Cost, one row per provider, sorted by spend. Tap a row to open that provider's usage detail."),
            HelpCard("Provider detail", "The detail page shows totals + avg/call, a By type breakdown (report / meta / rerank / translate / …), a By pricing source breakdown, and a per-model list (tap a model → Model Info)."),
            HelpCard("Clear", "The 🧹 in the title bar clears every usage counter back to zero (confirm dialog). Cannot be undone."),
            HelpCard("Pitfalls", "Rerank rows bill per search-unit, not per token. A model never called won't appear; costs need pricing data loaded."),
        )
    ),
    "ai_usage_provider" to HelpContent(
        title = "Help - Provider usage",
        cards = listOf(
            HelpCard("Overview", "One provider's usage, opened by tapping its row on Spend & usage."),
            HelpCard("💰 Totals", "Calls, tokens, cost, average cost per call, and how many distinct models were used."),
            HelpCard("🏷️ By type", "Spend split by call kind — Report / Meta / Rerank / Moderation / Translate / Title / etc. — with each kind's call count and cost."),
            HelpCard("📐 By pricing source", "How many of this provider's used models priced from each catalog tier (LiteLLM, models.dev, OpenRouter, … or the 25/75 default)."),
            HelpCard("By model", "Every used model with its cost, call count, token count and winning pricing tier. Tap a model to open Model Info."),
        )
    ),
    "ai_costs_tier" to HelpContent(
        title = "Help - Costs tiers",
        cards = listOf(
            HelpCard("Overview", "Which pricing tier PricingCache.getPricing resolves each model to, counted per tier. Reached from the Statistics hub; runs getPricing per model, so it's computed only when opened."),
            HelpCard("Config / Runtime columns", "Two columns side by side. Config = every configured model in each provider's catalog. Runtime = only the DISTINCT (provider, model) pairs that were actually called, read from the API traces (host → provider, plus the recorded model). Runtime mirrors Config but over what really happened."),
            HelpCard("API-reported", "First row: models whose provider ships the per-call cost in the response (OpenRouter, Together, Perplexity, xAI — anything with Extract API cost or a cost-ticks divisor). Their real cost is read off the body, so no pricing tier applies and they're counted here instead of in a tier."),
            HelpCard("Tiers", "The rest, by the source tag the lookup returns: Manual override, LiteLLM, models.dev, llm-prices, Artificial Analysis, OpenRouter, Together, Helicone, then the 25/75 default fallback for models no catalog covers."),
            HelpCard("25/75 default", "A big default count means those models have no real catalog price and would bill at the $25/$75-per-million placeholder. Add a manual override or refresh catalogs to fix."),
            HelpCard("🏷️ Pricing cache", "Below the tiers: a table of the six external pricing info-providers (LiteLLM, models.dev, llm-prices, Artificial Analysis, OpenRouter, Helicone) with each catalog's entry count and when it was last retrieved ('never' if not yet fetched). A 🐞 after the timestamp opens the API Traces for that source's retrieve (shown only when the retrieve was captured). These catalogs are exactly what feeds the tier resolution above. Refresh them from Housekeeping → Costs maintenance."),
            HelpCard("Pitfalls", "Tier resolution reflects the catalogs currently loaded — a model can shift tiers after a refresh. Runtime depends on tracing having been enabled when the calls were made; with no traces it shows zeros."),
        )
    ),
    "cost_config" to HelpContent(
        title = "Help - Cost Config",
        cards = listOf(
            HelpCard("Overview", "Per-row Add Manual Override at the top, the list of currently configured overrides in the middle, and at the bottom two collapsed maintenance cards (Cleanup and Layered costs) lifted from the former Housekeeping → Manual cost overrides screen. The maintenance cards stay collapsed by default — the main task here is curating per-row overrides; cleanup and bulk CSV are occasional."),
            HelpCard("Add Manual Override", "Green button — opens AddManualOverrideScreen as a full-screen overlay. The single-row form."),
            HelpCard("Cleanup (collapsed)", "Drops every manual override that is dormant or redundant: covered by a catalog tier (LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis, OpenRouter), equal to the built-in default, or equal to what the lookup would return without it."),
            HelpCard("Layered costs (collapsed) · Export all", "CSV with one row per (provider, model) for every active provider. Two leading override columns are blank; remaining columns show every catalog tier's $/M-token price in run-time precedence order."),
            HelpCard("Layered costs · Export filtered", "Same shape but drops rows already covered by any catalog tier — surfaces only the (provider, model) pairs the user would actually need to override manually."),
            HelpCard("Layered costs · Import manual changed costs", "Reads the same CSV back. Only rows where the user filled in the two leading override columns are applied via PricingCache.setManualPricing. Blank rows are silently ignored."),
            HelpCard("Per-row card", "Provider name (blue), model id, current input/output prices in $/1M tokens. Two buttons in view mode: Remove (red) / Edit. Edit mode shows two input fields plus Cancel / Save."),
            HelpCard("Pricing precedence", "From PricingCache.getPricing: provider self-report → LiteLLM → models.dev → llm-prices → Artificial Analysis → manual override → OpenRouter cross-provider fallback → Helicone → DEFAULT."),
            HelpCard("Tips", "Stored as $/token internally — the form takes $/1M tokens and divides by 1,000,000 on save (and multiplies by it on edit-load)."),
            HelpCard("Pitfalls", "Manual override comes AFTER the curated tiers — if LiteLLM has a price, your override may not actually win. Cleanup drops those redundant entries."),
        )
    ),
    "cost_override" to HelpContent(
        title = "Help - Cost override (add / edit)",
        cards = listOf(
            HelpCard("Overview", "Form to add or edit one (provider, model, input $/M, output $/M) override. Reachable from Cost Config's Add button or directly from Model Info → \"Add manual cost override\"."),
            HelpCard("Provider button", "Outlined button opens SelectProviderScreen as a full-screen overlay. Default = first provider in AppService.entries."),
            HelpCard("Model row", "Free-text \"Model\" field plus a Select button that opens SelectModelScreen for the chosen provider. Either typing or picking works."),
            HelpCard("Reference price", "When provider+model are populated, a small dim line shows the current price the lookup would return WITHOUT the override (PricingCache.getPricingWithoutOverride) plus the source tier."),
            HelpCard("Save", "Disabled until provider + non-blank model + parseable input + parseable output. Numbers are $/1M tokens; saved divided by 1,000,000."),
            HelpCard("Tips", "When opened from Model Info on an existing override, both price fields pre-populate from the saved values."),
            HelpCard("Pitfalls", "Negative prices and non-numeric input fail the Save gate without an explicit error message — the button just stays disabled."),
        )
    ),
)
