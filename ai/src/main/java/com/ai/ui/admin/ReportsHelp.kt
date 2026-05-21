package com.ai.ui.admin

internal val reportsHelp: Map<String, HelpContent> = mapOf(
    "reports_hub" to HelpContent(
        title = "Help - AI Reports",
        cards = listOf(
            HelpCard("Overview", "Dashboard for everything to do with reports. Two top buttons jump into the creation and search wrappers; four list cards summarise what's already on disk; one bottom button opens the paginated browser."),
            HelpCard("In-flight pill", "When at least one report has unfinished agents (PENDING / RUNNING and no completedAt), an orange ⏳ pill appears at the top — tap it to resume the most recent in-flight run without going through History."),
            HelpCard("Top buttons", "New AI report opens the three creation entry points (blank, previous prompt, example prompt). Search AI reports opens the three search modes (Quick local, Extended local, Remote semantic)."),
            HelpCard("Problems / Running / Pinned / Latest", "Four list cards, each showing up to five rows. ⚠️ Problems collects reports with an errored agent or a stuck/failed secondary; ⏳ Running collects reports with at least one PENDING / RUNNING agent or an active translation run; 📌 Pinned mirrors every report flagged on Manage; 🕘 Latest shows the five newest. An empty card stays on screen at reduced opacity with an italic '(none)' line so the layout doesn't shift."),
            HelpCard("Per-row icons", "Tap a row to open at Manage. 🔧 jumps to Manage explicitly, 👁 jumps to the View tile grid, 🗑 prompts a delete confirmation that removes the report from disk."),
            HelpCard("All AI reports", "Bottom button opens the paginated swipe-through of every saved report — same per-row icons as the dashboard cards.")
        )
    ),
    "new_ai_report_screen" to HelpContent(
        title = "Help - New AI report",
        cards = listOf(
            HelpCard("What you see", "Three tap-through rows: 🗒 New AI Report opens the blank form; 🔄 Start with a previous prompt opens the prompt history; 💡 Start with an example prompt opens the example-prompt picker (only shown when at least one example prompt is configured)."),
            HelpCard("How to use it", "Pick whichever entry point matches how you want to start. Each one lands on the standard report form where you finish entering title + prompt, then pick the agents/flocks/swarms/models that should answer.")
        )
    ),
    "all_ai_reports_screen" to HelpContent(
        title = "Help - All AI reports",
        cards = listOf(
            HelpCard("What you see", "Every saved report, newest first. The body doesn't scroll — rows are split into fixed pages that auto-fit the screen height. A small 'Page X of Y' header sits above the rows."),
            HelpCard("How to use it", "Swipe left / right to flip between pages. Each row carries the same tap-to-manage behaviour and 🔧 / 👁 / 🗑 icons as the dashboard cards. The page math re-fits when you rotate the device, so portrait and landscape both fill the visible area.")
        )
    ),
    "report_new" to HelpContent(
        title = "Help - New AI Report",
        cards = listOf(
            HelpCard("Overview", "Two-stage: type a title + prompt here, then on Next pick which agents / flocks / swarms / models receive the prompt. The title and prompt are saved to LAST_AI_REPORT_TITLE / _PROMPT and to the last-100 prompt history."),
            HelpCard("Title and prompt", "Both are required for Next to enable. Title is single-line; prompt is multi-line with a 10-line minimum height. Clear wipes both fields plus any attached image."),
            HelpCard("Image attachment", "📎 picks an image from device storage and attaches it as base64 — passed through to every agent's prompt at dispatch. Only vision-capable models will actually read it; the rest receive the text alone. Image-attached reports can be MB-sized on disk."),
            HelpCard("Web search chip", "🌐 tags every dispatched call with the web-search tool flag. Providers and models that don't support web search drop the flag silently."),
            HelpCard("Thinking chip", "🧠 None / Low / Medium / High. Applied to every agent at dispatch; non-thinking models drop the field automatically."),
            HelpCard("Validate prompt chip", "🛡 picks a moderation model and runs the prompt through it before any agent fires. If the model flags the prompt, you get a Proceed-anyway / Cancel dialog with a 🐞 link to the moderation trace; tap when on to clear the model."),
            HelpCard("Next", "Saves title + prompt to last-prompt prefs and prompt history, then routes to the model-selection screen. While moderation is running the button shows a spinner.")
        )
    ),
    "report_select_models" to HelpContent(
        title = "Help - Report — select models",
        cards = listOf(
            HelpCard("Overview", "The pre-Generate page in the report flow. Empty model list at first; +Agent / +Flock / +Swarm / +Model / +Report fill it, Params lets you tweak the per-call parameter set, Generate fires the dispatch and moves the report into the post-Generate manage page (Report - manage)."),
            HelpCard("Add buttons", "+Agent picks one saved agent, +Flock adds every member of a flock, +Swarm adds every (provider, model) pair in a swarm, +Model is the single-select all-providers picker, +Report copies the model list from a previous report. Repeated taps stack — you can mix sources."),
            HelpCard("Params", "Opens Advanced Parameters — temperature, max tokens, system prompt, etc. The button reads Params ✓ when an override is active. Clear all wipes the override and the dispatched call uses each agent's default parameter set."),
            HelpCard("Generate", "Fires the dispatch for every model in the list. The screen flips to Report - manage as soon as the first row starts streaming. While running, that page exposes STOP / Background; once complete it exposes the full Action row."),
            HelpCard("Update model list (edit mode)", "When you reach this page via Edit → Models on a finished report, the bottom button switches to Update model list. It stages the new list and pops back without re-running — you re-fire later from Report - manage → Action row → Regenerate."),
            HelpCard("System prompt", "Optional per-report system prompt picker. The selection is stored on the Report so a Regenerate keeps the same instruction. Independent of any per-agent system prompt — both can apply."),
            HelpCard("Reached from", "Hub → New AI Report → enter title + prompt → Continue. Or History → open an existing report → Action row → Edit → Models — that variant lands here in edit mode (button reads Update model list).")
        )
    ),
    "report_run" to HelpContent(
        title = "Help - Report — manage",
        cards = listOf(
            HelpCard("Overview", "The post-Generate page in the report flow. Per-agent rows stream in as each model returns; the Action row at the bottom exposes the operations you can apply to the finished run. Sibling of the pre-Generate Report - select models — a Generate (or opening a saved report from History) lands you here."),
            HelpCard("Subject row", "Green strip below the title bar carrying the prompt title + the running 💰 cost in cents. Updates live as each call settles. Tapping the title bar's title text drills into the per-agent results viewer."),
            HelpCard("Per-agent rows", "One card per dispatched model. While the call is in flight the row shows progress; on completion it carries the response, token + cost cell, optional 🐞 trace icon, and the auto-generated per-agent emoji once the icon chain finishes."),
            HelpCard("Action row — while running", "STOP cancels every in-flight call (rows mid-stream complete what they've received, then mark CANCELLED). Background drops you back to Hub while the run continues; reopening the report shows the in-flight rows still streaming."),
            HelpCard("Action row — when complete", "View, Edit, Copy, Pin/Unpin, Translate, Meta (disabled when no Meta prompts exist), Fan out (disabled when no Fan-out prompts exist). Regenerate / Delete / Export live as title-bar icons (🔄 / 🗑 / 📤) rather than action-row buttons to avoid duplicating the same tap target."),
            HelpCard("Per-model icons (auto-run, per-task)", "The 3-tier per-agent icon chain (chat continuation → one-shot icons/report template → fixed-agent icons/report_3 fallback) fires automatically when Settings → Generate per model icons is on. Each agent's chain kicks off the moment THAT agent's primary call settles to SUCCESS, so a fast row's emoji can appear before a slow row in the same report has finished generating. Tier 1 = chat (the model emoji-fies its own previous answer). Tier 2 = one-shot with @PROMPT@/@RESPONSE@. Tier 3 = bundled DeepSeek on @RESPONSE@ only. All three fail → 📝. Costs from every tier call accumulate on the row's cost cell, post to global Usage statistics with kind=\"icon\", and appear as their own rows in the export's per-call All tab. Regenerating the report re-fires the chain per regenerated agent."),
            HelpCard("View popup", "Reports / Prompt / Costs plus one row per Meta-prompt name with at least one persisted secondary on this report. Edit popup is Prompt / Title / Models / Parameters — picking Models lands on Report - select models in edit mode."),
            HelpCard("Pending-changes banner", "Orange banner appears when the user edited prompt / models / parameters since the last run — Regenerate is required for the new values to take effect. Until then the displayed rows reflect the old configuration."),
            HelpCard("Title bar — 💬 (chat handoff)", "Stashes the prompt as the chat starter and routes to the agent picker — pick an agent, the chat opens with the report's prompt as the first user turn. Surfaced only when the prompt is non-blank."),
            HelpCard("Title bar — 🐞 (trace) + per-row 🐞", "Title-bar 🐞 opens the API Traces list filtered to this report. Each agent row also carries its own 🐞 when its primary call left a recording; tapping opens that single trace file."),
            HelpCard("Title bar — 🗑 / 🔄 / 📤 / ℹ️", "🗑 deletes the report (confirm dialog). 🔄 opens the regenerate-confirm dialog. 📤 only appears when the run has completed and routes to Export. ℹ️ drills into the per-agent results viewer (same target as tapping the title)."),
            HelpCard("Stuck rows", "On reopen, any row left in PENDING / RUNNING by a force-quit is recovered: a one-shot sweep marks blank-content / null-error / null-duration secondaries as errored, and a 150 ms tick refreshes the inline meta list. If a row still spins, tap Regenerate."),
            HelpCard("Reached from", "Pressing Generate on Report - select models. Or History → open any saved report — you land here directly, skipping the selection page."),
            // "Stalled translation auto-reconcile" + "App-wide
            // background resume sweep" relocated to the "concepts"
            // topic — they're cross-screen behaviours, not specific
            // to this screen. The Help-home "How it works" link
            // surfaces them.
        )
    ),
    "regenerate_batch" to HelpContent(
        title = "Help - Regenerate report",
        cards = listOf(
            HelpCard("Overview", "The 🔁 icon on the Manage screen opens a confirm dialog; OK enqueues an app-restart-survivable batch that re-runs everything on the report in a fixed order. Replaces the legacy one-shot regenerate that only touched the agent rows."),
            HelpCard("Phase order", "1) Report icon. 2) Language (detection + language-icon). 3) Model reports (agents). 4) Meta — single-call meta + rerank + moderation. 5) Fan-out — every fan-out pair. 6) Fan-in — combined-report rows. 7) Translations. 8) Fan-icons — re-runs the icon chain for every fan-out pair that previously had an icon. The batch moves to the next phase only when every row in the current phase is SUCCESS."),
            HelpCard("Halt + restart on error", "Halts on the first row that ends ❌ in any phase. The Regenerate row on Manage turns ❌. Fix the offending row (delete + rerun via the existing per-row UI), then either tap Restart on the Regenerate detail screen OR wait — the same 30 s background sweep that resumes stuck translation / fan-out runs also auto-resumes a paused Regenerate batch once the row's error clears."),
            HelpCard("Survives app kill", "The job's task list + status lives on disk under <filesDir>/regenerate/<reportId>.json. App-restart sweep at ReportViewModel.startBackgroundResumeSweep rehydrates the engine + revives the orchestrator coroutine; rows mid-flight finish themselves and the batch picks up from the current phase."),
            HelpCard("Cancel + re-enqueue", "The detail screen's Cancel button stops scheduling new phases (in-flight HTTP calls finish normally). Tap Restart to resume from where it stopped. A fresh enqueue (tap 🔁 again) replaces the existing job and starts from phase 1."),
            HelpCard("Detail screen", "Tap the 🔁 Regenerate row on Manage. Shows every task grouped by phase with status icon + started / ended / duration timestamps. Per-task error messages render in red beneath the task label.")
        )
    ),
    "moderation_call_detail" to HelpContent(
        title = "Help - Moderation result",
        cards = listOf(
            HelpCard("Overview", "Drill-in for a single moderation API call's per-input result. Reached by tapping a row in the moderation table on the secondary detail screen. Shows the moderated agent's label, the flag verdict, every category the moderation API returned (with its score), and the original text that was classified."),
            HelpCard("Flag verdict", "🚩 Flagged means the moderation model marked at least one category as true. ✓ Clean means no category fired. The category list below the verdict surfaces every category the API returned — fired ones in red with a 🚩 prefix, rest in dim grey — sorted by score (descending)."),
            HelpCard("Scores", "0.0 (definitely not in this category) to 1.0 (definitely in). The boolean \"fired\" decision sits on a provider-internal threshold — a score can be high and still not fire if the model is calibrated conservatively for that category."),
            HelpCard("Moderated text", "The exact text the moderation API saw — the moderated agent's response body. Copy / Share in the title bar exports just this text. If the source response has since been deleted from the report the row reads \"(source response no longer available)\".")
        )
    ),
    "view_ai_report" to HelpContent(
        title = "Help - View AI report",
        cards = listOf(
            HelpCard("What you see", "The View home for a report — a grid of tiles, one per thing this report has to look at: the original prompt, the per-model responses, the cost breakdown, the in-app HTML preview, plus one tile for each kind of post-run result the report carries (Meta, Rerank, Moderation, Fan-out, Fan-in, Fan-in-model, Translate). The title bar carries the AI logo (taps go to the app home), the report's own title centred in white, and the help icon."),
            HelpCard("How to read it", "Each tile shows an emoji, a label, and — when a kind has more than one item — a small count badge in the top-right. Tiles you can tap are at full colour; tiles for kinds this report doesn't have yet aren't shown at all. Tap a tile to open the matching View screen. Long-press a tile and drag it onto another to swap their positions — your order persists across reports, so once you've arranged the grid the way you like it, it stays that way. When the report has translations, a row of large flag-style icons at the top picks the active language; that language is carried into every tile you open.")
        )
    ),
    // Per-scope Icon-lookup help — one topic for each of the six
    // adapters (main / agent / meta / translation / language /
    // fan-out pair). IconLookupContext.helpTopic carries the right
    // id so the title-bar 🐞 always lands on the page that
    // describes *this* flow. Every page shares the same six
    // "what the screen shows" cards (Subject / Title-bar / Model /
    // API interaction / Emoji / Find-alt / Cost / Trace) but the
    // first card and the cost-attribution card are scope-specific.,
    "icon_lookup_main" to HelpContent(
        title = "Help - Icon lookup — main report icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for the main report icon — the emoji shown next to the report title. Reached by tapping the 📝 icon on the report's result screen. Produced by the bundled `icons/main` one-shot prompt with `@PROMPT@` substituted to the report's prompt text."),
            HelpCard("Subject (green row)", "Always `main` (or `main_alt` after a Find-alt pick). Legacy rows whose `iconPromptUsed` is null fall back to `main`."),
            HelpCard("Title-bar icons", "💬 Continue in chat (preseeds a chat with the prompt + emoji). ℹ️ Model info for the model that ran the call. 📋 Copy the API-interaction body. 📤 Share via the system sheet. 🐞 jumps to the captured API trace (only when tracing was on at call time)."),
            HelpCard("Model / API interaction / Emoji cards", "Standard layout — the same shape for every Icon-lookup scope: provider + model + cumulative cost, plain `[user] … [assistant] …` 2-message transcript, big centred glyph (⏳ pending, ❌ on error)."),
            HelpCard("Find alternative icons", "Runs the bundled `icons/main_alt` variant across user-picked (provider, model) pairs. Composed at runtime as `alt.text + \"\\n\\n\" + base.text` — alt nudge first so the model reads the 'pick something distinct' constraint before the template body. Pick a returned emoji to commit it on the report."),
            HelpCard("Cost attribution", "Initial call + every alt attempt is bumped on `Report.iconInputCost / iconOutputCost`. On the Report → API cost table the alt calls surface as per-call `icon_main_alt` rows; the initial generation as `icon_main`. By-type collapses every `icon_*` row into one `icons` group."),
            HelpCard("Trace category", "`icon_main` for the initial generation, `icon_main_alt` for every Find-alt call.")
        )
    ),
    "icon_lookup_agent" to HelpContent(
        title = "Help - Icon lookup — per-agent icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for one agent's per-model icon (one icon per agent on a report). Reached by tapping the agent's emoji cell on the result / manage screen. Produced by the bundled 3-tier chain `report_2` (chat-continuation) → `report` (one-shot) → `report_3` (fixed-agent fallback). The first tier that returns a usable emoji wins."),
            HelpCard("Subject (green row)", "The bundled prompt name that won — `report_2`, `report`, or `report_3`. After a Find-alt pick the subject flips to `report_alt`. Legacy rows fall back to deriving the name from `iconWinningTier`."),
            HelpCard("Title-bar icons", "ℹ️ Model info / 📋 Copy / 📤 Share / 🐞 trace. Continue-in-chat is intentionally NOT wired here — the agent's response already lives on the result screen's row, not here."),
            HelpCard("API interaction card", "Tier-aware — tier 1 (`report_2`) shows the 4-message chat-continuation exchange (`[user] report.prompt → [assistant] agent.response → [user] icon prompt → [assistant] emoji`). Tier 2 and 3 show a 2-message one-shot exchange with the relevant template substituted."),
            HelpCard("Find alternative icons", "Runs the bundled `icons/report_alt` variant across picked models — composed as `alt.text + \"\\n\\n\" + report.text`. Pick lands on `ReportAgent.icon` for this agent only."),
            HelpCard("Cost attribution", "Bumped on `ReportAgent.iconInputCost / iconOutputCost`. Per-call audit rows are `icon_report` / `icon_report_2` / `icon_report_3` / `icon_report_alt`."),
            HelpCard("Trace category", "`icon_report` (tier 2), `icon_report_2` (tier 1), `icon_report_3` (tier 3), `icon_report_alt` (Find-alt).")
        )
    ),
    "icon_lookup_meta" to HelpContent(
        title = "Help - Icon lookup — meta-prompt icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for the cached icon on a Meta-prompt row (Compare / Summarize / Critique / Rerank / Moderation / fan-in / fan-out summary). Reached by tapping the emoji on a Meta row. The icon is keyed `(prompt.name, prompt.title)` on the cross-report `InternalPromptIconCache`, so every report that uses the same prompt sees the same icon."),
            HelpCard("Subject (green row)", "The cached `promptName` field on the cache entry — defaults to `meta`. Find-alt picks flip it to `meta_alt`."),
            HelpCard("Title-bar icons", "ℹ️ is NOT wired (the cache entry doesn't track a specific model). 📋 / 📤 work on the 2-message transcript. 🐞 looks up the most recent `icon_meta` / `icon_meta_alt` trace for the cache's stored model — cross-report (the cache itself is cross-report)."),
            HelpCard("API interaction card", "2-message one-shot exchange: the resolved `icons/meta` prompt with `@NAME@ @TITLE@` substituted, then the returned emoji."),
            HelpCard("Find alternative icons", "Runs `icons/meta_alt` (composed as `alt.text + \"\\n\\n\" + meta.text`) across picked models. The picked emoji is committed via `InternalPromptIconCache.pickAlternative` with `promptName = meta_alt`."),
            HelpCard("Cost attribution", "Each call bumps the cache entry's cumulative `inputCost / outputCost`. Per-row attribution: when the prompt has a matching SecondaryResult on the current report, the call also bumps that SR's `inputCost / outputCost` so the Report → Manage row total includes the alt spend. Per-call audit rows are `icon_meta` / `icon_meta_alt`."),
            HelpCard("Trace category", "`icon_meta` (initial), `icon_meta_alt` (Find-alt).")
        )
    ),
    "icon_lookup_translation" to HelpContent(
        title = "Help - Icon lookup — translation row icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for the cached icon on a per-target-language translation row (one per language the report has been translated into). Reached by tapping the emoji on a Translate row. Keyed `(\"translation_icon\", language)` on the cross-report `InternalPromptIconCache`."),
            HelpCard("Subject (green row)", "`translation` (or `translation_alt` after a Find-alt pick)."),
            HelpCard("Title-bar icons", "ℹ️ NOT wired. 📋 / 📤 work on the 2-message transcript. 🐞 looks up the most recent `icon_translation` / `icon_translation_alt` trace for the cache's stored model — cross-report."),
            HelpCard("API interaction card", "2-message one-shot exchange: bundled `icons/translation` prompt with `@LANGUAGE@` substituted, then the returned emoji."),
            HelpCard("Find alternative icons", "Runs `icons/translation_alt` (composed as `alt.text + \"\\n\\n\" + translation.text`) across picked models. The picked emoji is committed via `InternalPromptIconCache.pickAlternative` with `promptName = translation_alt`."),
            HelpCard("Cost attribution", "Each call bumps the cache entry's cumulative cost; per-row attribution into the first TRANSLATE SR for the language. Per-call audit rows are `icon_translation` / `icon_translation_alt`."),
            HelpCard("Trace category", "`icon_translation` (initial), `icon_translation_alt` (Find-alt). The `_alt` variant is for picking a different country / language emoji.")
        )
    ),
    "icon_lookup_language" to HelpContent(
        title = "Help - Icon lookup — detected-language icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for the report's detected-language icon — the emoji rendered next to a translated report header. Produced by the 2-step `language-detect` → `language` flow: detect first (returns the language NAME), then run the bundled `icons/language` prompt with `@LANGUAGE@` substituted to that name."),
            HelpCard("Subject (green row)", "`language` (or `language_alt` after a Find-alt pick). Legacy rows fall back to `language`."),
            HelpCard("Title-bar icons", "💬 Continue in chat (preseeds a chat about the language). ℹ️ Model info / 📋 / 📤 / 🐞."),
            HelpCard("API interaction card", "2-message one-shot exchange: bundled `icons/language` prompt with `@LANGUAGE@` substituted, then the returned emoji."),
            HelpCard("Find alternative icons", "Runs `icons/language_alt` (composed as `alt.text + \"\\n\\n\" + language.text`) across picked models. Pick commits onto `Report.languageIcon`."),
            HelpCard("Cost attribution", "Bumped on `Report.languageIconInputCost / languageIconOutputCost`. Per-call audit rows are `icon_language` / `icon_language_alt`."),
            HelpCard("Trace category", "`icon_language` (initial), `icon_language_alt` (Find-alt). The `_alt` nudge says 'do not use a country flag emoji' — picks a more abstract glyph.")
        )
    ),
    "icon_lookup_pair" to HelpContent(
        title = "Help - Icon lookup — fan-out pair icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for one fan-out pair's icon — the emoji on a single (source × responder) cell in a fan-out run. Optional — only present once you've tapped *Run Fan Icons* on the L1 of a Fan Out drill-in. Produced by the per-pair 3-tier chain `fan_out_2` (chat-continuation, 6 messages) → `fan_out` (one-shot) → `fan_out_3` (fixed-agent fallback)."),
            HelpCard("Subject (green row)", "The bundled prompt name that won — `fan_out_2`, `fan_out`, or `fan_out_3`. After a Find-alt pick the subject flips to `fan_out_alt`."),
            HelpCard("Title-bar icons", "ℹ️ NOT wired. 📋 / 📤 work on the tier-aware transcript. 🐞 looks up the most recent `icon_fan_out*` trace for the pair's model under this report."),
            HelpCard("API interaction card", "Tier-aware. Tier 1 shows the 6-message chat-continuation transcript (`[user] report.prompt → [assistant] source.response → [user] meta.prompt → [assistant] pair.response → [user] icon prompt → [assistant] emoji`) matching `runFanOutTier1`. Tiers 2/3 show a 2-message one-shot exchange with the relevant template substituted."),
            HelpCard("Find alternative icons", "Runs `icons/fan_out_alt` (composed as `alt.text + \"\\n\\n\" + fan_out.text`) across picked models. The picked emoji is committed to the pair via `setFanOutIconAndTier` with `promptUsed = fan_out_alt`."),
            HelpCard("Cost attribution", "Bumped on the pair's `SecondaryResult.iconInputCost / iconOutputCost` (visible in the Cost line here and in the L2/L3 row totals). On Report → Manage the cost rolls up into the fan-out summary row's 🎨 fan-icons sibling. Per-call audit rows are `icon_fan_out` / `icon_fan_out_2` / `icon_fan_out_3` / `icon_fan_out_alt`, all attributed to the pair's SR."),
            HelpCard("Trace category", "`icon_fan_out_2` (tier 1), `icon_fan_out` (tier 2), `icon_fan_out_3` (tier 3), `icon_fan_out_alt` (Find-alt)."),
            HelpCard("How to reach this screen", "Fan Out → L2 (MAIN mode) — tap the pair's icon on its row (the icon replaces the leading ✅ when present). Fan Out → L3 (MAIN mode) — tap the small icon in the answerer pane's header row (just before the model name).")
        )
    ),
    "find_icons_selection" to HelpContent(
        title = "Help - Find icons",
        cards = listOf(
            HelpCard("Overview", "Model picker that fans the bundled internal/icon prompt across whatever (provider, model) pairs you choose. Reached from the Icon detail screen's 'Find alternative icons' button."),
            HelpCard("+Add chips", "Same five chips as the New-Report flow: Agent (saved Agents), Flock (named groups of agents), Swarm (named groups of provider/model pairs), Report (copy the model list from a finished report), Model (free-form (provider, model) picker)."),
            HelpCard("Selected list", "Rows are sorted alphabetically by model id. Each row shows model id + capability badges + provider id + pricing per million tokens. The ✕ on the right drops a single row; the Clear button at the bottom wipes the whole list."),
            HelpCard("Stripped affordances", "Params and Sys prompt are intentionally absent — an icon is a one-shot @PROMPT@ → emoji round-trip; parameter presets don't apply."),
            HelpCard("Find Icons", "Kicks off one analyzeWithAgent call per (provider, model) pair against the bundled internal/icon prompt with @PROMPT@ replaced by the report's prompt text. Per-provider throttle (ProviderThrottle) caps concurrency. Pops you straight to the Alternative icons live list."),
            HelpCard("Cost note", "Each call's tokens × pricing tier is added to the Report's icon cost as soon as the response lands — regardless of whether you later pick that result."),
            HelpCard("Pitfalls", "Models with no API key set won't run — the call lands as ❌ on the Alternative icons screen. Pricing tiers stuck on DEFAULT show a Red bracket on the picker row.")
        )
    ),
    "translation_models" to HelpContent(
        title = "Help - Pick translation models",
        cards = listOf(
            HelpCard("Overview", "Model picker for a Translate run, reached after choosing a target language. Translation work spreads round-robin across every (provider, model) pair you pick."),
            HelpCard("+Add chips", "Same five chips as the New-Report flow: Agent (saved Agents), Flock (named groups of agents), Swarm (named groups of provider/model pairs), Report (copy the model list from a finished report), Model (free-form (provider, model) picker)."),
            HelpCard("Selected list", "Rows are sorted alphabetically by model id and show capability badges + provider id + pricing per million tokens. The ✕ on the right drops a single row; the Clear button wipes the whole list."),
            HelpCard("Start translation", "Enabled once at least one model is picked. Kicks off the translation run and drops you on the live progress screen; the button label shows the model count when more than one is picked."),
            HelpCard("Pitfalls", "Models with no API key set land as errored rows in the run. Pricing tiers stuck on DEFAULT show a Red bracket on the picker row.")
        )
    ),
    "alternative_icons" to HelpContent(
        title = "Help - Alternative icons",
        cards = listOf(
            HelpCard("Overview", "Live progress list for an in-flight or completed icon fan-out. One row per (provider, model) pair you picked on the previous screen. State sits in AppViewModel.iconFanOutByReport — survives navigating away and back into the screen for the same report."),
            HelpCard("Row meanings", "⏳ = the icon call is still running (or queued behind the per-provider throttle). The emoji shown big = the call returned a usable response and the row is tappable. ❌ = the call failed or returned an empty body; the error reason renders underneath in red. The row is non-tappable."),
            HelpCard("Tap to pick", "Tapping a Done row commits its emoji as the Report's icon and records the model label on the Report. All three icon overlays (Alternative icons, Find icons picker, Icon detail) close together — you land back on the Report result screen."),
            HelpCard("Cost", "Every call's tokens × pricing tier is bumped onto the Report's icon cost as the response lands, so the icon row's cost reflects the total search cost regardless of which (if any) icon you eventually pick."),
            HelpCard("Backing out mid-flight", "Calls keep running. Re-entering the Icon detail screen for the same report shows a 'View alternative icons' button (instead of 'Find alternative icons') — tapping it jumps straight back here with the same live list."),
            HelpCard("Pitfalls", "If the app process dies mid-run, the in-memory candidate map is lost — costs already bumped survive on the Report, but the screen will be empty on next launch.")
        )
    ),
    "icons_view" to HelpContent(
        title = "Help - Icons",
        cards = listOf(
            HelpCard("What you see", "The report's own icon at the top, centred. Below it, the per-model icons. When the report has no fan-out, every agent's icon sits in one flow grid. When fan-outs are present, you get one section per run: a header with the run's name, then one row per initiator showing the initiator's icon, an arrow, and every responder's icon."),
            HelpCard("How to read it", "Tap any responder icon to open just that fan-out pair on its own page (initiator's response on the left, responder's reply on the right). Tap any initiator icon — or, in a no-fan-out report, any model icon — to open the Reports view scrolled to that model's page. Android back returns to the Icons screen each time.")
        )
    ),
    "fan_out_pair_view" to HelpContent(
        title = "Help - Fan-out pair",
        cards = listOf(
            HelpCard("What you see", "One fan-out pair on its own page: the initiator's report response on the left as a neutral bubble, the responder's reply on the right as an indigo bubble. The title bar's green subject is the meta-prompt name that produced the pair."),
            HelpCard("How to read it", "Both bubbles render full markdown — tables, headings, lists, code blocks. Long bodies collapse to a preview with a Read more / Show less toggle. Android back returns to the Icons screen.")
        )
    ),
    "report_single_result" to HelpContent(
        title = "Help - Single agent result",
        cards = listOf(
            HelpCard("Overview", "Detail view for one agent's response, reached by tapping a row on the result screen. Renders <conclusion> and <motivation> blocks separately when present, then the rest of the body, with collapsible <think> sections."),
            HelpCard("Header", "Provider name in the title bar; provider — model line in blue is tappable to open Model Info. Errors render as a red Error block with the underlying message; blank bodies show 'No analysis available'."),
            HelpCard("Title bar — 🔄", "Reload icon opens a confirmation dialog (target = provider / model). Confirming calls onRegenerateAgent for this single (reportId, agentId) and pops back to the result screen."),
            HelpCard("Title bar — ℹ️", "Always wired here. Jumps to Model Info for this agent's (provider, model) pair."),
            HelpCard("Title bar — 🗑", "Always wired. Opens 'Remove from report?' confirm. Confirming drops just this row, recomputes totals, and pops back."),
            HelpCard("Title bar — 🐞", "Wired when tracing is on AND ApiTracer.getTraceFiles() finds a record where reportId == this report and model == this agent's model — opens the most recent matching trace."),
            HelpCard("Translation info", "Shown only when this report has a sourceReportId and the matching agent's responseBody is loaded — opens TranslationCompareScreen with original on top, translation on bottom."),
            HelpCard("Continue in chat", "Disabled when the response is blank or errored. Opens the Continue picker (current history+model / pick agent / configure on the fly)."),
            HelpCard("Pitfalls", "Removing the last successful agent from a report leaves it empty — reopen the parent report and re-run from the Action row.")
        )
    ),
    "report_continue_in_chat" to HelpContent(
        title = "Help - Continue in chat",
        cards = listOf(
            HelpCard("Overview", "Three-row picker that hands this agent's response off to a fresh chat session as the seed turn. Reached from the 💬 button on the single-agent result."),
            HelpCard("📜 with current history and model", "Reuses the same provider/model and the agent's resolved system prompt + parameters from current settings. The chat starts with the report prompt + this response already in the transcript."),
            HelpCard("🤖 with this response only and select an agent", "Stashes the agent's response as the next chat's input-box starter and routes to the agent picker. The picked agent's system prompt and parameters then drive the session."),
            HelpCard("🛠️ with this response only and configure on the fly", "Stashes the response and walks you through provider → model → parameters before opening the chat — handy when none of your saved agents fit."),
            HelpCard("Tips", "All three rows are always enabled here; the upstream button on the single-result screen is the one that disables on empty / errored responses."),
        )
    ),
    "secondary_list" to HelpContent(
        title = "Help - Secondary results — list",
        cards = listOf(
            HelpCard("Overview", "Lists every persisted secondary of one kind (Rerank / Meta / Moderation). Translate has its own per-run screen; Fan-out has its own drilldown. The bar reads the user-given Meta-prompt name (or the legacy kind label for older rows)."),
            HelpCard("Polling", "While at least one batch is in flight, the list re-reads disk every 500 ms so newly-stamped placeholders flip from ⏳ to ✅/❌ without leaving the screen."),
            HelpCard("Language picker", "For chat-type META, when the report has TRANSLATE rows a row of language pills appears: Original plus one per distinct targetLanguage. Selecting a non-Original language overlays translated bodies onto the matching original rows."),
            HelpCard("Meta picker view", "Chat-type META renders a FlowRow of buttons (one per result, labelled by provider · model) plus the selected result inline — mirror of the Reports viewer."),
            HelpCard("Per-row 🗑", "Each row has its own confirm dialog. Title-bar 🗑 is grayed because deletion is per-row here."),
            HelpCard("Per-row tap", "Opens the secondary-result detail screen. Drilling into the same row after popping back is preserved via rememberSaveable openId."),
        )
    ),
    "secondary_detail" to HelpContent(
        title = "Help - Secondary result — detail",
        cards = listOf(
            HelpCard("Overview", "Full content of one Rerank / Meta / Moderation row. Errors render as a red Error block; blank content shows '(no content)'."),
            HelpCard("Rerank rendering", "Tries to parse the structured JSON ([{id, rank, score, reason}, ...]) and render a sorted RerankTable. Falls back to raw markdown via ContentWithThinkSections when the model deviated from the schema."),
            HelpCard("Moderation rendering", "Parses [{id, flagged, categories, scores}, ...] into a ModerationTable with 🚩 / ✓ flags, fired categories, and the top 3 scores. Falls back to raw text on bad JSON."),
            HelpCard("Meta rendering", "Always renders via ContentWithThinkSections so <think> blocks collapse and the rest is plain Markdown-ish text."),
            HelpCard("Title bar — 🔄", "Not wired here — re-run a secondary by deleting and re-firing from the report's Action row."),
            HelpCard("Title bar — ℹ️", "Wired when the providerId resolves. Jumps to Model Info for this row's (provider, model) pair."),
            HelpCard("Title bar — 🗑", "Wired. Opens 'Delete this <kindLabel>?' confirm; confirming calls onDelete and pops back."),
            HelpCard("Title bar — 🐞", "Wired when tracing is on and a matching trace exists (reportId + this row's model, max-by-timestamp)."),
            HelpCard("Translation info", "Shown only for META rows that have a translateSourceTargetId resolving to a non-blank source — opens TranslationCompareScreen.")
        )
    ),
    "secondary_fan_out_l1" to HelpContent(
        title = "Help - Fan out — answerers",
        cards = listOf(
            HelpCard("Overview", "Top of the fan-out drilldown. Lists every answerer model on this fan-out run with its current status. Tap an answerer to step into its sources at L2."),
            HelpCard("Status icons", "Per-row: ✅ all pairs done, ❌ at least one errored, ⏳ at least one running, queued = no row on disk yet. Derived from latestByPair across all results."),
            HelpCard("Bench column", "The stats row splits Errors from Bench. A benched pair errored because its model is on a >1h rate-limit cooldown — it'll recover once the cooldown lifts, so it's counted apart from genuine errors. When benched pairs exist, a 'Remove benched' button appears next to 'Remove failed items' to clear just those (the two removes are complementary)."),
            HelpCard("Resume stale", "On open, any fan-out pair with no content / no error / not in runningFanOutPairs is re-enqueued via onResumeStaleFanOut — survives app kill mid-batch."),
            HelpCard("Restart failed", "Re-runs only ❌ cells, leaving ✅ alone. Skips the placeholder grid rebuild — quick recovery without re-spending tokens on succeeded cells."),
            HelpCard("Combine reports", "When at least one fan-in prompt exists, the screen exposes 'Run combine reports' — fires a meta call against the fan-out matrix's results."),
            HelpCard("Per-answerer delete", "Drops every cell where this answerer participated. Fan-out list refresh tick bumps so the L1 list reflects the gap on pop-back."),
            HelpCard("Pitfalls", "Cell count is N×(N-1) for an N-agent run; cost grows fast. Watch the Action row's cost summary before pressing Restart on large grids.")
        )
    ),
    "secondary_fan_out_l2" to HelpContent(
        title = "Help - Fan out — model",
        cards = listOf(
            HelpCard("Overview", "Per-answerer drilldown. Shows the sources fed into the chosen answerer (or, in Initiator role, every pair where this model was the source). Tap a source row → L3 pair detail."),
            HelpCard("Role toggle", "Responder = the active model received others' sources (default). Initiator = the active model's report fed into others. The role chip swaps the row list between the two views."),
            HelpCard("Title bar — ℹ️", "Opens Model Info for the active (provider, model) pair."),
            HelpCard("Title bar — 🗑", "Deletes every fan-out cell where this answerer participated. Pops back to L1."),
            HelpCard("Title bar — 🐞", "When tracing is on and the answerer's own report run was traced (Initiator role only), opens that trace file."),
            HelpCard("Tap a source", "Opens L3 with the source content on top and the fan-out response underneath."),
            HelpCard("One page view", "The 'View on one page' button concatenates every (source, response) under the active answerer onto a single scrollable page."),
        )
    ),
    "secondary_fan_out_l3" to HelpContent(
        title = "Help - Fan out — pair",
        cards = listOf(
            HelpCard("Overview", "Single cell view. Source content (the row this answerer was given) is on top; the fan-out response (this answerer's reply to that source) is underneath. Two scrollable panes split half-and-half by default."),
            HelpCard("Title bar — 🐞", "Opens this fan-out call's captured trace when tracing was on at the time of the call."),
            HelpCard("Back", "System back / ‹ pops one level up to L2 (per-model)."),
            HelpCard("Pitfalls", "If the source has been deleted from the report after this fan-out ran, the source pane shows a placeholder; the response stays visible."),
        )
    ),
    "secondary_fan_out_onepage" to HelpContent(
        title = "Help - Fan out — one page",
        cards = listOf(
            HelpCard("Overview", "Concatenates every (source, response) pair under the active answerer onto one page so you can scan the whole drilldown without tapping each cell."),
            HelpCard("Layout", "Per pair: source label + body, then the fan-out response body. Sources render in the order activeAgents (the row stack visible on L2)."),
            HelpCard("Initiator role", "When the parent L2 was on Initiator role, the page lists every (answerer, source) where the active model was the source — same shape, opposite direction."),
            HelpCard("Title bar — ℹ️", "Opens Model Info for the active (provider, model) pair."),
            HelpCard("Pitfalls", "Long fan-out runs render many MB of text; rendering can be slow on dense reports. Use L2 + tap-into-cell when you only need one pair."),
        )
    ),
    "fan_out_view" to HelpContent(
        title = "Help - Fan-out",
        cards = listOf(
            HelpCard("What you see", "Every fan-out reply in the run, laid out as a chat-style thread. Each answering model has its own header chip with the model name and a count of how many replies it produced; under that header sit the (initiator → answerer) exchanges as two bubbles per row — the initiator's original report response on one side, the answerer's reply on the other. Scroll vertically through the whole conversation."),
            HelpCard("How to read it", "Replies are grouped by answering model so you can see how a single model handled every initiator in one place. Long replies collapse to a preview line with a 'Read more' toggle — tap to expand, tap again to collapse. When you've picked a translated language on the parent View screen, the answerer's bubble shows the translated text with a small 🌍 badge; rows without a translation fall back to the original reply so the thread stays continuous.")
        )
    ),
    "costs_view" to HelpContent(
        title = "Help - Costs",
        cards = listOf(
            HelpCard("What you see", "What this report has cost so far. A big yellow 💰 Total at the top sums every API call this report has fired; below it, a row per spending bucket — Reports, Meta, Fan-out, Fan-in, Translate, Moderation, Rerank, Icons, Language — showing how much of the total went there."),
            HelpCard("How to read it", "Each bucket row carries three things: the percentage of the grand total, the absolute amount, and a horizontal bar coloured yellow-to-orange whose length matches that share. A glance tells you whether one kind dominates spending or the cost is spread out. Buckets that cost nothing are hidden entirely; very small buckets still render a thin sliver so they don't disappear. The call count on the right tells you how many requests landed in that bucket.")
        )
    ),
    "reports_view" to HelpContent(
        title = "Help - Model reports",
        cards = listOf(
            HelpCard("What you see", "One model's response at a time, shown as a single card with the model's emoji centred at the top and the answer body below it. A small counter sits above the card — 'X / Y' tells you which model you're on and how many there are in total. The green subject line in the title bar shows the current model's short name, so you can always see which one you're reading."),
            HelpCard("How to read it", "Swipe left to move to the next model, swipe right to go back. The model's own emoji (or 🤖 when it doesn't have one yet) marks the card; the answer body renders headings, lists, tables, code blocks, and reasoning sections like the rest of the app. If you've picked a translated language on the parent View screen, the body shows the translated reply for each model when one exists; otherwise the original answer stays put.")
        )
    ),
    "prompt_view_screen" to HelpContent(
        title = "Help - Prompt",
        cards = listOf(
            HelpCard("What you see", "The original prompt that drove this report, presented as a single hero card on a purple-to-indigo gradient — the document feel, no clutter around it. The report's own emoji shows in the header strip; the prompt body sits below with full markdown formatting (headings, lists, tables, code blocks all render properly)."),
            HelpCard("How to read it", "If the prompt was long or technical you can scroll the card to see the whole thing. When a translated language is active on the parent View screen, the body switches to that language's translation of the prompt if one has been made; otherwise the original prompt stays visible.")
        )
    ),
    "translate_view" to HelpContent(
        title = "Help - Translate",
        cards = listOf(
            HelpCard("What you see", "Every item this translation run produced — the prompt, the per-model responses, and any meta replies — laid out one row at a time. Each row is a stacked pair: the original on top in a neutral card, the translation below it on an orange-accented card. A small label above each pair tells you which source it's translating (📝 prompt, 🤖 a specific model's response, or 🧠 a meta result)."),
            HelpCard("How to read it", "The target language shows as the green subject in the title bar so you always know which language you're reading. Long bodies collapse to a preview with a 'Read more' toggle — tap to expand, tap again to collapse. Source and translation use the same markdown rendering so headings, lists, tables, and code blocks line up between the two.")
        )
    ),
    "fan_in_model_view" to HelpContent(
        title = "Help - Fan-in-model",
        cards = listOf(
            HelpCard("What you see", "One per-model synthesis at a time, with a tab strip across the top — one chip per model that contributed a synthesis. The active model is bold and blue-tinted; the others sit dimmer next to it. The card below shows that model's synthesis on a blue-gradient background, with a 'Synthesised from' strip at the bottom naming each source response that fed into it."),
            HelpCard("How to read it", "Swipe left or right between models, or tap a chip in the strip to jump straight there. Each model's synthesis renders with full markdown — headings, tables, lists, code blocks, reasoning sections all behave the way they do elsewhere in the app. The credits strip uses each source agent's own emoji so you can spot at a glance which models fed the synthesis.")
        )
    ),
    "fan_in_view" to HelpContent(
        title = "Help - Fan-in",
        cards = listOf(
            HelpCard("What you see", "The synthesised output from a fan-in — one model's unified answer that drew on every contributing response. The synthesis sits in a single hero card on a green accent gradient with a 🪢 header; the body uses full markdown so headings, lists, tables, code blocks, and reasoning sections all render properly. Below the body, a compact 'Synthesised from' strip names each source response that fed into this run."),
            HelpCard("How to read it", "The hero shows the synthesising model's name under the 🪢 header — that's the model that did the combining. The credits strip uses each contributing model's own emoji so you can recognise the sources at a glance. If a contributor never picked up its own emoji, 🤖 stands in. Scroll the screen vertically for long synthesis bodies.")
        )
    ),
    "moderation_view" to HelpContent(
        title = "Help - Moderation",
        cards = listOf(
            HelpCard("What you see", "A safety check across every model response on this report. The hero up top gives the overall verdict — red 🚩 when at least one response flagged anything, green when everything came back clean — and tells you how many responses were checked. Below, one card per model: the model's name in the header, the flagged categories called out next to a 🚩 when any fired, then a row of category chips covering every category the moderator looked at."),
            HelpCard("How to read it", "Each chip is colour-coded like a traffic light — red means that category fired, amber means the score sits in the elevated range without quite crossing the line, green means clean. The number on the chip is the moderator's score for that category, so you can see how close to (or far from) the threshold each call was without tapping anything. A card with no 🚩 callout passed every check.")
        )
    ),
    "rerank_view" to HelpContent(
        title = "Help - Rerank",
        cards = listOf(
            HelpCard("What you see", "A ranked list of the model responses on this report — the rerank's verdict on which answers it considered strongest. The top three sit in large podium cards with 🥇/🥈/🥉 medals; rank four onwards continues as slimmer numbered rows below. Each row carries the responding model's name, the rerank's score for that answer (out of 100), and the reason the rerank gave for that placement."),
            HelpCard("How to read it", "Cards are sorted top-to-bottom by rank — the best at the top, then second, then third, then everything else in numbered order. The score badge on the right of each card lets you see at a glance how close the ranks are: clustered scores mean it was a tight race, big gaps mean the rerank had a clear opinion. The rerank's prompt name reads as the green subject in the title bar.")
        )
    ),
    "meta_view" to HelpContent(
        title = "Help - Meta",
        cards = listOf(
            HelpCard("What you see", "A two-card 'question and answer' layout. The top hero is the original report prompt — the question this meta was asked to think about — rendered on a purple gradient with the report's own emoji. Below it sits the meta's reply card with the meta's emoji and name in the header, the model that produced the reply underneath, and the answer body in the main panel."),
            HelpCard("How to read it", "The answer body renders headings, lists, tables, code blocks, and reasoning sections the same way the rest of the app does. Each meta tile carries its own icon — picking a new icon for one meta affects only that tile, never the others sharing the same name. When you've picked a translated language on the parent View screen, the answer card swaps to that language's translation when one exists; otherwise the original reply stays put.")
        )
    ),
    "secondary_scope" to HelpContent(
        title = "Help - Secondary scope",
        cards = listOf(
            HelpCard("Overview", "Inserted between a Meta-prompt button and the model picker. Picks which rows feed into the next run + (when relevant) which target languages."),
            HelpCard("All model reports", "Default. Uses every successful agent on the report (count shown in the sublabel)."),
            HelpCard("Only top ranked reports", "Available for meta / fan-out prompts when the report has at least one rerank row. Pick the rerank source from a dropdown and an N (1..total)."),
            HelpCard("Manual select models", "Tick exactly which agent rows to include. Defaults to every successful agent ticked, so it's a starting point you can prune."),
            HelpCard("Languages section", "Only shown for chat-type prompts when the report has translation rows. All languages = original + every translated; Select languages = pick a subset alongside the original."),
            HelpCard("Continue", "Disabled until the chosen scope yields at least one input — Top-Ranked needs a rerank picked + count > 0, Manual needs at least one tick."),
            HelpCard("Pitfalls", "Rerank / moderation runs always operate on the full agent set — those kinds skip this screen entirely."),
        )
    ),
    "report_meta" to HelpContent(
        title = "Help - Meta",
        cards = listOf(
            HelpCard("Overview", "Unified Meta screen. Top: every persisted Meta-prompt result (TRANSLATE excluded — those live in the cost table only), newest first. Bottom: an Add card with one button per saved Meta prompt."),
            HelpCard("Polling", "While isRunning is true, refreshTick bumps every 500 ms — placeholders that runSecondary writes from its IO coroutine surface as ⏳ rows here without bouncing in/out of the screen."),
            HelpCard("Per-row icons", "❌ for errored, animated rotating ⏳ for in-flight (blank content), ✅ for completed."),
            HelpCard("Per-row content", "Kind label in orange, provider · model in white, timestamp underneath. Cost (input + output cents, monospace) appears when totalCost > 0."),
            HelpCard("Per-row 🗑", "Each row has its own confirm. Picks the noun from the row's metaPromptName (or legacy kind label)."),
            HelpCard("Add card", "FlowRow of orange buttons sorted by name, one per metaPrompts entry. Empty case shows a hint pointing at AI Setup → Prompt management → Report Meta Prompts."),
            HelpCard("Tap a row", "Opens SecondaryResultDetailScreen for that result — full content + ℹ️ Model Info + 🐞 trace + 🗑."),
        )
    ),
    "report_edit_prompt" to HelpContent(
        title = "Help - Edit prompt",
        cards = listOf(
            HelpCard("Overview", "Modify the report's prompt. Saving stamps hasPendingPromptChange so the result screen surfaces a yellow 'Changes pending: prompt' banner — the existing rows aren't re-rendered until you tap Regenerate."),
            HelpCard("Prompt field", "Multi-line, fills the screen. Update prompt is disabled when the body trims to blank."),
            HelpCard("Saver scoping", "rememberSaveable is keyed on initialPrompt so re-opening the overlay with a fresh seed value doesn't restore a stale draft from the SaveableStateRegistry."),
            HelpCard("Pitfalls", "Editing the prompt alone doesn't re-run agents — the existing responses stay on screen until you Regenerate."),
        )
    ),
    "report_edit_title" to HelpContent(
        title = "Help - Edit title",
        cards = listOf(
            HelpCard("Overview", "Rename the report. Title is metadata only — no outbound API call references it, so this never sets hasPendingPromptChange and you don't need to regenerate to see the new title applied."),
            HelpCard("Title field", "Single-line. Update title is disabled when the body trims to blank."),
            HelpCard("Saver scoping", "rememberSaveable is keyed on initialTitle so re-opening the overlay with a fresh seed doesn't restore a stale draft."),
        )
    ),
    "report_parameters" to HelpContent(
        title = "Help - Advanced Parameters",
        cards = listOf(
            HelpCard("Overview", "Per-report parameter override that wins over each agent's own settings for this run only. Apply stamps hasPendingParametersChange so the result screen shows the pending banner."),
            HelpCard("Numeric fields", "Temperature (0.0–2.0), max tokens, top P (0.0–1.0), top K, frequency / presence penalty (-2.0–2.0), seed. Empty fields mean 'inherit' — only non-blank values become part of the override."),
            HelpCard("System prompt", "Multi-line (3–5 lines visible). Replaces the agent / flock / swarm system prompt for this run when non-blank."),
            HelpCard("Web search / Citations / Recency", "xAI-style and Perplexity-style toggles. Recency takes 'day', 'week', 'month', 'year' — anything else is dropped silently."),
            HelpCard("Apply", "Builds an AgentParameters from non-blank values. If everything is blank/default, calls onApply(null) — i.e. clears the override."),
            HelpCard("Clear all", "Wipes every field and calls onApply(null). Useful when starting over."),
            HelpCard("Pitfalls", "Provider-specific fields (e.g. Anthropic ignores frequency/presence penalty) are silently dropped server-side — check the trace if behaviour surprises you."),
        )
    ),
    "report_export" to HelpContent(
        title = "Help - Export report",
        cards = listOf(
            HelpCard("Overview", "Pick a format, a detail level, and a target (where the export lands), then tap the green Export button at the top of the page to commit. The purple Export-all-zip button sits right next to it for one-shot bulk export."),
            HelpCard("Export button (top)", "Green CTA in the top button row. Fires whichever Target chip is selected — Android share / View in browser / View in app. Disabled while a previous export is still building."),
            HelpCard("Format chips", "HTML, PDF, MS Word, OpenDocument, JSON, Zipped HTML — wrap to a second row on narrow phones via FlowRow. JSON and Zipped HTML ignore the detail picker; everything else honors it."),
            HelpCard("Detail — Short", "Prompt, per-model results (with citations and related questions), Meta sections (one per Meta prompt) plus Moderations. No index, no costs, no traces."),
            HelpCard("Detail — Complete", "Index, prompt, every Meta section, Reranks / Moderations / Translations, the cost table, and every captured API trace with redacted bodies."),
            HelpCard("Target — Android share", "Builds the file and hands it to the system share sheet. Closes the Export screen so back from the chooser doesn't loop here."),
            HelpCard("Target — View in browser", "Builds the file and opens it as a separate Android intent (system browser for HTML; viewer app for PDF / Word / ODT). Stays on this screen so you can come back and try a different format without rebuilding picker state."),
            HelpCard("Target — View in app", "Renders the HTML inline in the in-app WebView preview — no external app launched. Only available when Format is HTML; the chip disappears for other formats and the selection auto-falls back to View in browser."),
            HelpCard("Language card", "Surfaces only when the report has at least one TRANSLATE secondary. Two chips: All languages / One language. One language reveals an icon-mode picker — source-language icon plus one icon per translation. Reports without translations skip the card and behave like before."),
            HelpCard("Language — JSON gating", "JSON is the trace bundle (request/response files), which has no language. Picking One language hides the JSON chip from the Format card; if JSON was selected, the format auto-falls back to HTML."),
            HelpCard("Export all (zip) — multi-language", "When the report has translations AND scope = All languages, the master zip lays out one top-level directory per language (`original/`, `dutch/`, …) each containing `docs/` (Short + Complete × HTML / PDF / DOCX / ODT) and `html/` (per-language Zipped HTML). The trace bundle lives once at the root under `json/`."),
            HelpCard("Export all (zip) — single language", "When the report has no translations, or scope = One language, the master zip uses a flat layout: `docs/`, `html/`, `json/`. No per-language top-level directory — the language wraps would be redundant noise."),
            HelpCard("Icons in exports — replacements", "Wherever the export would name a language (picker buttons, `Language: …` headings, Zipped HTML breadcrumb / link / h1), the cached language icon takes the slot instead. Original = Report.languageIcon; translations = the cached translation_icon for each language. Cache miss falls back to the English name + native sublabel."),
            HelpCard("Icons in exports — additions", "Report title, agent headings, and meta-prompt section headings carry their dynamic icons as a prefix in front of the existing text. Agent icon = ReportAgent.icon; meta-prompt icon = InternalPromptIconCache.getByName(name); per-secondary icon = SecondaryResult.icon (fan-out pairs). Missing icon = no prefix added, label stands alone."),
            HelpCard("Progress dialog", "While building, a non-dismissable dialog shows a linear progress bar driven by (done, total) updates from the export. Failures show a Toast with the exception class + message; the dialog clears."),
        )
    ),
    "report_manage" to HelpContent(
        title = "Help - Manage reports",
        cards = listOf(
            HelpCard("Overview", "Hub-level housekeeping for saved reports — two cards: Delete old reports, and Export all (backup)."),
            HelpCard("Delete old reports", "Numeric field (digits, max 4) for 'Older than (days)'. Pinned reports are skipped. Confirm dialog shows the candidate count before any file is touched."),
            HelpCard("Export all (backup)", "Zips every report JSON plus every secondary results file into a single archive (ai_reports_backup_<ts>.zip) and opens the system share sheet. Status text reads 'Bundled N reports' on success."),
            HelpCard("Working state", "While the zip / delete is in flight, both buttons are disabled and the export label switches to 'Working…'."),
            HelpCard("Status line", "Final operation result lives as a small grey line at the bottom — 'Deleted N reports.', 'Bundled N reports.', or 'Nothing to export.'."),
            HelpCard("Pitfalls", "Delete is irreversible — once the cutoff fires, those reports' secondaries and trace files go too. Take an Export all first if you might want them back."),
        )
    ),
    "report_view_picker" to HelpContent(
        title = "Help - View — picker",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker reached from the Report Result action row's View button. Each row is a separate view of the current report."),
            HelpCard("Reports", "Opens the per-agent reports viewer. Detail line shows N of M agents succeeded so you can spot a partially-failed run before drilling in."),
            HelpCard("Prompt", "Opens the report's full prompt as scrollable text. Detail line previews the first non-blank line (≤80 chars)."),
            HelpCard("Costs", "Tokens + cost breakdown across all agents and secondaries. Detail line shows the secondary spend so far in USD when there is any."),
            HelpCard("Log", "Opens the App Log Viewer for this report's creation-day log file, pre-filtered to the report's log-id. Every app-log line written while working on a report is tagged ` [#<reportId>]` at the end, so this shows only that report's activity. A run that spilled into the next day is reachable via the viewer's prev/next file buttons — the filter persists across files."),
            HelpCard("Per-Meta-prompt rows", "One row per Meta-prompt name with at least one persisted secondary on this report. Detail = run count; secondary line = the kind label (Rerank / Summarize / Compare / Moderate)."),
        )
    ),
    "report_edit_picker" to HelpContent(
        title = "Help - Edit — picker",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker reached from the Report Result action row's Edit button. Each row routes to a separate edit screen for one slice of the report."),
            HelpCard("Prompt", "Opens the multi-line prompt editor. Detail line previews the first non-blank line (≤80 chars). Saving stamps a 'Changes pending: prompt' banner on the result screen until you Regenerate."),
            HelpCard("Title", "Single-line title editor. No pending-changes flag — title is metadata only."),
            HelpCard("Models", "Routes back to the selection phase with the report's existing model list staged for in-place editing. The detail line says how many models are currently on the report."),
            HelpCard("Parameters", "Opens the per-report parameter override (temperature, max tokens, top P, stop sequences, etc). Detail line is a generic field hint."),
        )
    ),
    "report_fan_out_confirm" to HelpContent(
        title = "Help - Fan out — confirm run",
        cards = listOf(
            HelpCard("Overview", "Confirmation screen shown after the Fan out scope picker, before the runner kicks off. Lists exactly how many calls a Run will fire and which models are involved."),
            HelpCard("Counts grid", "answerers × responses-per-report = total calls. Falls back to a flat 'N calls' line when scope is uneven enough that the grid math doesn't divide cleanly."),
            HelpCard("Scope", "All reports / Top-N ranked / Manual selection. Reflects the choice made on the previous screen — back to change it."),
            HelpCard("Answerer / Source lists", "Two cards listing the model names on each side of the fan out. A model appears in both when it's both an answerer and a source."),
            HelpCard("Fan-out prompt", "Preview of the prompt body (≤12 lines) that will be sent for every pair, with @RESPONSE@ filled in at run time."),
            HelpCard("Run / Cancel", "Run is disabled while the count loads or when there are zero pairs. Cancel pops back to the previous screen without firing.")
        )
    ),
    "report_pick_flock" to HelpContent(
        title = "Help - Pick a flock",
        cards = listOf(
            HelpCard("Overview", "Modal dialog that lists every saved flock with its agent count and a synthetic per-million-tokens cost band. Tap a row to add every member to the report."),
            HelpCard("Search field", "Filters by name (case-insensitive). The ✕ trailing icon clears the field."),
            HelpCard("Member count", "Reflects what expandFlockToModels actually feeds the report — agents whose provider isn't Active are skipped, so the count matches the worker count after Generate."),
            HelpCard("Pricing column", "Sums per-million prompt / completion across all members. Red when at least one member has real pricing data; grey-on-grey badge when every member fell through to DEFAULT."),
            HelpCard("Empty state", "Opens an empty list; define flocks in AI Setup → Workers → Flocks first."),
            HelpCard("Back button", "Bottom-right TextButton dismisses without a selection."),
            HelpCard("Pitfalls", "Flocks reference agents by id; deleting the underlying agent leaves a broken member. Edit the flock first."),
        )
    ),
    "report_pick_agent" to HelpContent(
        title = "Help - Pick an agent",
        cards = listOf(
            HelpCard("Overview", "Agent dialog reached by +Agent on the selection phase. Lists every saved agent with name + provider · model + per-million-token pricing. Search filters by name or provider name. Tap a row to add the agent to the report."),
            HelpCard("Pricing badge", "Red when the model has real pricing data; grey-on-grey when the row fell through to DEFAULT_PRICING. Updates as PricingCache loads tier blobs in the background."),
            HelpCard("Empty state", "When there are no agents yet, the body is empty — set up agents first under AI Setup → Agents."),
            HelpCard("Title bar / dismiss", "Dialog dismisses via a Back TextButton at the bottom-right.")
        )
    ),
    "report_pick_previous" to HelpContent(
        title = "Help - Pick previous report",
        cards = listOf(
            HelpCard("Overview", "Single-select picker over saved reports, reached by +Report on the selection phase. Newest first by Report.timestamp. Tap to copy that report's model list into the current selection."),
            HelpCard("Search", "Filters by title or prompt. The count line above the list reads '<filtered> of <total> reports'."),
            HelpCard("Empty state", "When no reports exist yet, the body shows 'No previous reports yet.'"),
            HelpCard("Pitfalls", "Reports list is loaded off the UI thread because getAllReports re-parses every report JSON, including image-attached ones."),
            HelpCard("Title bar", "Standard back arrow — popping back returns you to the New AI Report selection phase.")
        )
    ),
    "report_pick_swarm" to HelpContent(
        title = "Help - Pick a swarm",
        cards = listOf(
            HelpCard("Overview", "Full-screen swarm picker reached by +Swarm on the New AI Report selection phase. Lists every saved swarm with member count + summed per-million pricing. Tap a row to add every (provider, model) pair to the report."),
            HelpCard("Search field", "Filters by swarm name (case-insensitive). The ✕ trailing icon clears the field."),
            HelpCard("ℹ️ icon", "Left of each swarm name — opens a per-swarm detail screen (Swarm info) listing every member with provider, model, capability badges, and per-million pricing. Tap a row there to drill into Model Info. Tap target is separate from the row's main click so you can preview without adding the swarm."),
            HelpCard("Pricing column", "Sums per-million prompt / completion across all members. Red when at least one member has real pricing data; grey-on-grey badge when every member fell through to DEFAULT."),
            HelpCard("Empty state", "Opens an empty list; define swarms in AI Setup → Workers → Swarms first."),
            HelpCard("Pitfalls", "Members survive when their provider is inactive — the swarm definition is purely structural. The report-run dispatch silently skips inactive members; the ℹ info screen still lists them."),
        )
    ),
    "report_pick_model" to HelpContent(
        title = "Help - Pick model",
        cards = listOf(
            HelpCard("Overview", "Full-screen single-select model picker reached by +Model on the New AI Report selection phase, and by the secondary-result launchers (Meta / Fan-out / Fan-in / Fan-in-model / Translate / Rerank) when they need a model."),
            HelpCard("List", "Joins every active provider's catalog."),
            HelpCard("Provider filter", "Dropdown above the list — All Providers or one specific provider (count shown next to each name)."),
            HelpCard("Type filter", "When opened with a modelTypeFilter (RERANK / MODERATION / EMBEDDING / etc.), a checkbox '<Type> models only' is shown ON by default — untick to widen to the full catalog."),
            HelpCard("Search field", "Matches against provider id and model id. The count line above the list reads '<filtered> of <total> models'."),
            HelpCard("Recent section", "When the user has picked from any Report-section model picker before, the last 3 picks surface as a 'Recent' section above the main alphabetical list. Filters and search don't trim it — recents are a quick-access shortcut. Tapping a recent row also re-records it so the bump-to-front keeps ordering stable."),
            HelpCard("Already-added rows", "Rows passed in via alreadyAdded render at 0.4 alpha, are not clickable, and append ' · already added' next to capability badges."),
            HelpCard("Pricing column", "Per-token (×10⁶) prompt / completion, red for real data, grey badge for DEFAULT. Vision / Web / Reasoning badges sit before the price."),
            HelpCard("Tap to confirm", "Single-select: tapping a row immediately fires onConfirm with the (provider, model) pair and the caller closes the picker. No multi-select, no batch confirm."),
        )
    ),
    "report_swarm_info" to HelpContent(
        title = "Help - Swarm info",
        cards = listOf(
            HelpCard("Overview", "Per-swarm detail screen reached by tapping the ℹ️ icon next to a row on Pick a swarm. Lists every (provider, model) member of the swarm in member order."),
            HelpCard("Per-row content", "Provider id in blue on top, model id in white below. Capability badges (vision / web search / reasoning) only appear when the catalog reports the capability for this model. Per-million-token prompt / completion price pair on the right — red when real pricing data exists, grey badge when the row fell through to DEFAULT_PRICING."),
            HelpCard("Tap a row", "Opens Model Info for that (provider, model) — the same destination the title-bar ℹ️ icon reaches across the rest of the app. Use the Costs and Capabilities cards there to see every source's reading for this model."),
            HelpCard("Title bar", "Static \"Swarm\" with the swarm name as the dynamic subject (folds into the bar when the \"Subject to title bar\" setting is on, otherwise sits below as a green sub-header). The back arrow returns to Pick a swarm with the previous filter intact."),
            HelpCard("Pitfalls", "Members survive even when their provider is inactive or their API key isn't configured — the swarm definition is purely structural. The report-run dispatch silently skips inactive members; this info screen still lists them so you can spot which row to fix."),
        )
    ),
    "report_flock_info" to HelpContent(
        title = "Help - Flock info",
        cards = listOf(
            HelpCard("Overview", "Per-flock detail screen reached by tapping the ℹ️ icon next to a row on Pick a flock. Lists every member agent of the flock in member order."),
            HelpCard("Flock overrides header", "Shown at the top only when the flock pins its own params or system-prompt preset(s). These override the matching agent-level presets at report-run time — surfacing them once at the top tells you what'll actually drive the run after the merge."),
            HelpCard("Per-row content", "Provider id (blue), effective model id (white, resolved via getEffectiveModelForAgent so a provider-default model picks up the live default). Vision / Web / Reasoning capability badges + per-million-token price pair on the right. Two extra lines below the model when the agent has them: \"Parameters: name1, name2\" and \"System prompt: name\"."),
            HelpCard("Tap a row", "Opens Model Info for the agent's (provider, effective-model). Same drill-in the title-bar ℹ️ uses elsewhere."),
            HelpCard("Title bar", "Static \"Flock\" with the flock name as the dynamic subject. Back returns to Pick a flock."),
            HelpCard("Pitfalls", "Agents whose provider is inactive still appear here — the agent / flock list is the source of truth, but expandFlockToModels skips inactive members when feeding the report. The agent count shown on Pick a flock already reflects that filtering."),
        )
    ),
    "translation_run_l1" to HelpContent(
        title = "Help - Translation run — models",
        cards = listOf(
            HelpCard("Overview", "Level 1 of the translation run drill-in: every model that picked up work in this run. The run uses a shared work queue — items aren't pre-assigned, so a model's row appears (and its bar grows) as that model pulls items. Tap a model to see the items it translated."),
            HelpCard("Mode (Speed / Mixed / Cost)", "Three-way toggle above the stats panel. Cost (default) — cheap models drain the queue first, expensive ones hesitate proportional to their price ratio (up to 2 min between pulls). Mixed — softened bias (up to 5 s). Speed — no hesitation, every model pulls as fast as its per-host caps allow; highest throughput, highest spend. Switchable mid-run; the change takes effect on the next queue pull (within ~1 s). Saved per-run on disk so a process kill / app restart preserves your choice."),
            // "429 / 529 handling" relocated to the "concepts" topic
            // (Help home → How it works) — same OkHttp retry behaviour
            // applies across every screen that fires a translation
            // call, not just this one.
            HelpCard("Stats panel", "Pinned at the top, kept visible even once the run is done: Total, Done, Errors, Bench (errored because the model is on a >1h rate-limit cooldown — will recover when it lifts, counted apart from genuine errors), Run (in-flight), Queue (items not yet picked up by any model), Costs (run total in cents, 2 decimals)."),
            HelpCard("Per-model bar", "Each row's background bar is that model's share of the WHOLE run — green for done, red for errored. A per-model progress bar isn't possible: with the work queue you can't know how many items a model will end up taking. A model that did half the run shows a half-filled row."),
            HelpCard("Per-model row", "Status glyph (⏳ running / ❌ errored / ✅ all done / 🕓 mixed), model name, a '<done>/<total> done' summary, and that model's cost. Sorted running first, then errored, then fully-done. Once the whole run is done the glyph + fill drop so it reads calmly — and a leading numeric column appears showing how many translations that model contributed, sorted descending so the busiest model stays at the top."),
            HelpCard("Restart / Remove failed items", "Shown when at least one item errored. Restart re-fires every failed call (the runner's concurrency cap still applies); Remove drops the failed rows without spending tokens. Both are whole-run scope. On a multi-model run, each failed entry is reassigned to a model OTHER than the one it failed on (round-robin over the rest) — a single-model run retries on the same model."),
            HelpCard("Remove benched", "Appears next to Remove failed when the run has benched items (errored because the model is on a rate-limit cooldown). It drops only those — Remove failed leaves them alone — so you can clear the will-recover failures separately from the genuine ones."),
            HelpCard("Top progress bar", "Run-level (done + error) / total while there's still pending or running work. Hidden on a cancelled run."),
            HelpCard("Title bar", "🔄 redoes every entry; 🐞 opens the trace list filtered to category=Translation; 🗑 deletes the whole run behind a blocking 'Deleting…' popup.")
        )
    ),
    "translation_run_l2" to HelpContent(
        title = "Help - Translation run — model",
        cards = listOf(
            HelpCard("Overview", "Level 2: the items one model translated. The header carries the model name; ℹ️ jumps to its Model Info. A summary line shows item / done / error counts plus this model's cost."),
            HelpCard("Per-row content", "Status glyph, a broad kind label (prompt / report / meta), the item's source label, and the per-item cost. Each row's fill is green when done, red when errored."),
            HelpCard("Sorting", "Running and queued items first, then errored, then done — each group alphabetical by label."),
            HelpCard("Per-row tap", "Opens Level 3 — the single translation, original ↔ translated."),
        )
    ),
    "translation_run_l3" to HelpContent(
        title = "Help - Translation",
        cards = listOf(
            HelpCard("Overview", "Level 3: a single translation. Original (source) text on top, capped at half the screen; the translated text fills the rest. Both panes scroll independently."),
            HelpCard("Source resolution", "PROMPT pulls report.prompt; report (AGENT) pulls the matching agent's response; meta (META) pulls the source SecondaryResult's content. Live in-flight items also carry the source inline, so no disk read is needed mid-run."),
            HelpCard("Status rendering", "A DONE item shows the translated text; ERROR shows a red error block; RUNNING shows an animated hourglass; PENDING shows '🕓 Queued'. The original pane stays visible in every state."),
            HelpCard("Prev / Next", "Steps through the same model's items in the Level 2 order, without popping back up."),
            HelpCard("Title bar", "🐞 opens the call's API trace when tracing is on; ℹ️ jumps to the translation model's info; 📋 / share copy or share the translated text; 🗑 deletes this single row (a persisted row off disk, or an in-flight item from the run)."),
        )
    ),
    "translation_compare" to HelpContent(
        title = "Help - Translation compare",
        cards = listOf(
            HelpCard("Overview", "Generic side-by-side viewer for any 'original ↔ translation' pair. Reached from the Translation info button on a translated single-agent result, or on a translated Meta secondary."),
            HelpCard("Layout", "Both panes get equal weight (1f each) — original on top in blue, translation on bottom in green, separated by a 2dp divider."),
            HelpCard("Independent scroll", "Each pane has its own verticalScroll so a long original next to a short translation (or vice versa) doesn't lock you into a shared scroll position."),
            HelpCard("Think sections", "Both panes render via ContentWithThinkSections — <think> blocks collapse so the user-readable content stays prominent."),
            HelpCard("Empty content", "A pane with blank content shows '(no content)' in tertiary text."),
            HelpCard("Title", "Caller-supplied — typically reads 'Translation info — <provider> / <model>' or includes the Meta-prompt name."),
        )
    ),
    "translation_language" to HelpContent(
        title = "Help - Pick target language",
        cards = listOf(
            HelpCard("Overview", "Single-select picker over a curated list of 50+ languages — most-requested by speaker count for the head, alphabetical for the tail."),
            HelpCard("Search", "Filters by English name OR native name (case-insensitive). The ✕ trailing icon clears the field."),
            HelpCard("Per-row content", "English name in white on top, native name in tertiary grey underneath when it differs (e.g. 'Mandarin Chinese' / '中文 (普通话)'). A '>' chevron sits at the right."),
            HelpCard("Tap to confirm", "Single-select — tapping a row fires onConfirm and the caller closes the picker."),
            HelpCard("Pitfalls", "Translate runs against many languages multiply call cost linearly with language count — pick deliberately."),
            HelpCard("Curation", "Not exhaustive. The translation prompt itself can be edited in AI Setup → Prompts → Internal to use a more specific dialect."),
            HelpCard("Tips", "Search for native script directly works — typing '中文' jumps to Mandarin without remembering the English name."),
        )
    ),
    "content_model_response" to HelpContent(
        title = "Help - Model response",
        cards = listOf(
            HelpCard("Overview", "Full-screen viewer for one agent's response on a saved report. The agent picker dropdown sits below the title bar; the active model's body fills the rest of the screen. Other content modes — Prompt, Cost summary, View on one page — have their own help pages."),
            HelpCard("Loading state", "Reports are loaded on Dispatchers.IO via produceState — a Loading sentinel keeps the empty-state text from flashing while the JSON parse runs."),
            HelpCard("Language picker row", "When the report has TRANSLATE rows, a FlowRow of buttons (Original + one per distinct targetLanguage) sits below the title bar. Selecting a non-Original key overlays the translated body onto the active agent's response."),
            HelpCard("Agent picker", "Dropdown over the FlowRow of agents — built from successful (SUCCESS-status) agents sorted alphabetically. The button label rebuilds from agent.provider + agent.model so the Model name layout setting wins."),
            HelpCard("Active model header", "Provider — model in blue under the picker, with a 🐞 next to it when tracing is on and a matching trace exists for (reportId, agent.model, max-by-timestamp)."),
            HelpCard("Body rendering", "ContentWithThinkSections handles <think> collapsibles, citations, related-questions blocks, and search results — so models that emit any of those render structured rather than raw."),
        )
    ),
    "content_one_page" to HelpContent(
        title = "Help - View on one page",
        cards = listOf(
            HelpCard("Overview", "Concatenates the prompt and every successful agent's response onto one scrollable page so you can scan the entire report without flipping through the agent picker."),
            HelpCard("Layout", "Title at the top (or folded into the title bar in Subject mode), then the prompt block, then one section per agent with the agent label as a sub-header and the response body underneath."),
            HelpCard("Translations", "When the report has TRANSLATE rows the page honours the active language picker on the parent screen — translated bodies overlay onto the matching agents."),
            HelpCard("Pitfalls", "Long reports render many MB of text; scrolling can be slow on dense reports. Use the per-agent picker on the Model response screen when you only need one section."),
        )
    ),
    "cost_view" to HelpContent(
        title = "Help - Cost summary",
        cards = listOf(
            HelpCard("Overview", "Read-only cost view for the report — three compact, sortable lists (By type · By model · All calls) covering every API call counted against this report (agents + secondaries + translations). Reached from the result page's View → Costs button."),
            HelpCard("Tap a row", "Opens a popup with the full breakdown for that group or call — calls, in/out tokens, in/out cents, total, plus tier and duration on All-calls rows. Tap Close to dismiss."),
            HelpCard("Sortable columns", "Tap any column header to sort by that column. Tap the active column again to flip direction (▲ ascending / ▼ descending). Each list remembers its own sort independently. Default sort is Total descending."),
            HelpCard("Row display", "One line per row — model column shows only the part after the last `/` (no provider prefix), full provider/model is in the popup. The bold Total row at the end of By type sums every call in the report. If items were deleted with non-zero spend, an orange `deleted +X.XX ¢` line shows directly above the Total."),
            HelpCard("Translation costs", "Translation calls are billed against the same model that ran them — they appear as 'translate' kind rows. The language picker is hidden in cost mode since costs aggregate every call."),
            HelpCard("Empty state", "When neither the agents nor any secondary carries a tokenUsage record, the body reads '(no usage recorded)'. This usually means the run was cancelled before the first response landed."),
            HelpCard("Pitfalls", "Costs use CURRENT pricing — if the provider changed prices since the run, the displayed cost is the today-rate, not the as-billed rate."),
        )
    ),
    "prompt_view" to HelpContent(
        title = "Help - Prompt view",
        cards = listOf(
            HelpCard("Overview", "Read-only viewer for the report's prompt as it was actually saved. Reached from the result page's View → Prompt button."),
            HelpCard("Translated prompt", "When the report has a TRANSLATE row whose translateSourceKind is PROMPT, the language picker at the top lets you flip between the original and the translated body."),
            HelpCard("Empty state", "When report.prompt is blank, the screen shows '(no prompt recorded)' in tertiary grey."),
            HelpCard("Layout", "Single column with verticalScroll — long prompts scroll naturally."),
            HelpCard("Use it for", "Verifying what the model actually saw when results look surprising — variables and any user-tag block from <user>...</user> append are visible."),
            HelpCard("Pitfalls", "The screen renders the saved prompt — if you Edit prompt and don't Regenerate, the new prompt shows here but agents weren't re-run with it. The result page's pending-changes banner reminds you."),
        )
    ),
    "history" to HelpContent(
        title = "Help - History",
        cards = listOf(
            HelpCard("Overview", "All saved reports, newest-first. Re-fetches on every ON_RESUME so coming back from a delete / regenerate shows the updated list."),
            HelpCard("Search card", "Toggle expands to three independent fields: Title, Prompt, Response. Each narrows the list further (logical AND). 'Search (active)' label appears on the toggle when any field is non-blank."),
            HelpCard("Pagination", "Auto-sized to the screen — pageSize derived from maxHeight and a 56dp row height. < Prev / Next > controls when totalPages > 1."),
            HelpCard("Per-row content", "Title (truncated) on the left, MM/dd HH:mm date on the right. Per-row 🐞 (when tracing is on AND ApiTracer has any entries for this reportId) opens the trace list filtered to that report."),
            HelpCard("Per-row delete", "Each row has a ✕ that opens a confirm dialog. Confirming deletes the report on Dispatchers.IO and re-loads the list."),
            HelpCard("Title bar — 🗑", "Wired when allReports is non-empty. Confirm dialog shows the count; confirming calls ReportStorage.deleteAllReports and clears the local list."),
            HelpCard("Title bar — others", "ℹ️ / 🔄 / 🐞 not wired at the list level (those are per-row)."),
            HelpCard("Pitfalls", "Deleting a report cascades — its secondaries (Translate / Meta / Rerank / Moderate) and any trace files for that reportId also go.")
        )
    ),
    "prompt_history" to HelpContent(
        title = "Help - Prompt History",
        cards = listOf(
            HelpCard("Overview", "The last 100 unique (title, prompt) pairs you sent to a report, newest-first. Tap a row to open New Report seeded with that title and prompt."),
            HelpCard("Search field", "Single field that filters by title OR prompt (case-insensitive)."),
            HelpCard("Pagination", "Auto-sized — pageSize derived from screen height and a 56dp row. < Prev / Next > visible when more than one page."),
            HelpCard("Per-row content", "Title in white on the left, MM/dd HH:mm timestamp on the right."),
            HelpCard("Clear History", "Bottom red button — wipes the persisted prompt history and resets the list. Disabled when the list is already empty."),
            HelpCard("Deduplication", "Re-running the exact same (title, prompt) pair just bumps the timestamp; the list never grows past 100 entries."),
            HelpCard("Pitfalls", "Prompt history is independent from Report History — clearing it leaves your saved reports untouched and vice versa."),
        )
    ),
    "report_html_preview" to HelpContent(
        title = "Help - HTML preview",
        cards = listOf(
            HelpCard("What you see", "The same HTML page you'd get from a full HTML export, rendered live inside the app. The title reads 'HTML preview' for the full detail level or 'HTML preview (short)' when you launched the lighter version. The entire body is the document — prompt, model responses, costs, anything else the export builds — laid out as it would appear in a browser."),
            HelpCard("How to read it", "Scroll the document vertically; tap intra-page anchors (for example, a rerank row pointing back to the model that produced an answer) to jump around inside the page. Interactive features the export bakes in — sortable tables, collapsibles — work the same here as they do in a saved HTML file. The preview always shows one language at a time so there's no language picker inside the WebView; pick the language up front on the Export screen and the preview renders that slice.")
        )
    ),
    "report_meta_run" to HelpContent(
        title = "Help - Run a meta prompt",
        cards = listOf(
            HelpCard("Overview", "Full-screen editor for the meta prompt's text body, shown between the Scope screen and the model picker. Lets you tweak the template for this run only — the stored InternalPrompt is left untouched."),
            HelpCard("Title bar — Back", "Cancels and returns to the Scope screen. The state survives the trip so re-entering the Run page shows your unedited starting text again (the editor reseeds from the prompt's stored body)."),
            HelpCard("Prompt editor", "OutlinedTextField (min 8 lines) seeded with `metaPrompt.text`. Edits are local — they don't write back to Settings → Internal prompts. If you want the changes to stick, copy them into the prompt definition by hand after the run."),
            HelpCard("Continue button", "Hoisted to the top of the page so it's reachable without scrolling past a long template. Passes a copy of the meta prompt with the edited text to the model picker; the original stays unchanged."),
            HelpCard("Variables", "Substitution placeholders (`@PROMPT@`, `@RESPONSE@`, `@NAME@`, `@TITLE@`, etc.) remain literal in the editor — they're resolved at call time by the engine. Don't expand them by hand."),
            HelpCard("Reached from", "Settings → Internal prompts → run a meta-category prompt, OR from a report's Run → Meta → pick a prompt → Continue (after the Scope screen).")
        )
    ),
)
