package com.ai.ui.admin

internal val reportsHelp: Map<String, HelpContent> = mapOf(
    "translator_rank" to HelpContent(
        title = "Help - Rank the translators",
        cards = listOf(
            HelpCard("What this is", "Reached from the 🏅 on a translation row. It ranks the MODELS that produced this language's translation by how good their translations are — judged by the OTHER models. It reuses the existing translation (no re-translation)."),
            HelpCard("How it scores", "Every long-form translated answer (model responses + fan-out / meta responses; titles and the prompt are skipped) is scored 0–100 by each model in the panel EXCEPT the model that produced it. The panel is the `translate-rank` worker swarm (the report's own models when Worker batches = Report models). Each translator's average score becomes its rank."),
            HelpCard("Reading it", "The leaderboard lists each translator model with how many of its items were scored and its average score (best first). Tap a row to see that model's items and each judge's score + motivation."),
            HelpCard("A fairness note", "The translation pool spreads items across models, so each model is judged on the items IT happened to translate — not the same passage head-to-head. Item difficulty can therefore skew the average. It's most meaningful when a translation was produced by several models."),
            HelpCard("Cost", "Each score is a normal API call, counted in the report's cost table under the 'transrank' group. Each translator model gets at most 25 scoring cells (a random sample of its translation × judge pairs), so the whole batch is at most the translator count × 25; the launch popup shows the exact number of calls and asks to confirm first."),
            HelpCard("🐜 Workers", "The 🐜 icon switches to the per-judge-model breakdown — one row per model that did the scoring, with how many it judged and its cost. Tap 🐜 again to go back to the translator ranking.")
        )
    ),
    "translator_rank_workers" to HelpContent(
        title = "Help - Rank workers",
        cards = listOf(
            HelpCard("What this is", "The per-judge-model breakdown for a 'Rank the translators' run: one row per model that scored translations, showing how many it judged (done / total) and its cost. The green bar fills with each model's progress while the batch runs."),
            HelpCard("Back to the ranking", "Tap the 🐜 icon (or back) to return to the translator leaderboard.")
        )
    ),
    "value_view" to HelpContent(
        title = "Help - Value view",
        cards = listOf(
            HelpCard("What this is", "A cost × quality map of the models in this report. The horizontal axis is each model's cost; the vertical axis is its ranking score (quality). It answers \"which model gives most of the quality for the least cost?\". It appears once the report has a Rerank, a Tournament, or a Judge-the-judges run — that's where the quality scores come from."),
            HelpCard("Ranking switch", "The chips at the top pick which ranking feeds the quality axis: Combined first, then Rerank, then Judges (when a Judge-the-judges run exists), then Compare (each answer's match % against the meta), then Tournament (the total) and each Tournament method (Copeland / Elo / Davidson / Markov / Schulze / Colley / TrueSkill2). Judges ranks the answers by the panel's consensus verdict per match (plurality across judges, Copeland-scored). Tap one and the whole chart, the 💎 best-value pick and the list re-rank instantly — all recomputed locally from stored verdicts / win matrices, no new API calls."),
            HelpCard("Combined", "A single 0–1000 score per model: every available ranking (Rerank, Judges, Translations, Compare, each Tournament method) is normalised to 0–1, then blended by the weights from Settings → Ranking weights and scaled to 0–1000. A ranking weighted 0 is left out. Change the weights and Combined re-blends. It's the first chip."),
            HelpCard("Tournament (total)", "Sits just before the individual methods. Each model's quality is the inverse of its AVERAGE position across all seven Tournament methods — the same ordering as the Tournament screen's Total grid, so the best-on-average model sits highest."),
            HelpCard("How to read it", "Top-left is the sweet spot: cheap and high-scoring. 💎 marks the best-value model (the most quality per cost). Dimmed points are 'dominated': another model is at least as good for the same or less money; the non-dimmed points form the Pareto frontier. Both axes are padded a little so no point sits on an axis line."),
            HelpCard("Full-screen graph", "Tap the chart to open it full-screen — nothing but the graph, no bars. Pinch to zoom in and out and drag to pan around. Press back to return."),
            HelpCard("Export (📤)", "The 📤 icon in the bottom bar exports this whole screen as ONE self-contained HTML page and opens the share sheet. The page has a tab per ranking source (the same set as the chips), each with its cost × quality graph and the ranked model list; click a graph to view it full screen (Esc or click closes). Plain HTML with inline styling — opens in any browser, no internet needed."),
            HelpCard("Where it comes from", "Pure derivation — no new API calls. It reuses the per-agent cost already stored on the report and the scores from the selected ranking. Scores are model-scaled (e.g. 0–1 vs 0–100), so the quality axis auto-scales to this report's range."),
            HelpCard("Fan-out cost", "If this report has a fan-out AND every model shown here also answered that fan-out (the answerer set matches the report's models), each model's cost adds up its main answer PLUS all the fan-out responses it produced — so the comparison reflects total spend per model. A partial fan-out (only some models answered) leaves costs as the main answer only. The caption says when fan-out cost is included."),
            HelpCard("List below the chart", "Each model with its cost and score, sorted best-value first, badged 💎 Best value / Pareto / dominated.")
        )
    ),
    "report_notes" to HelpContent(
        title = "Help - User notes",
        cards = listOf(
            HelpCard("What this is", "Every free-text note you've attached anywhere in this report, grouped by what each note is pinned to (the report itself, a model response, a fan-out run or response, or a meta / rerank / moderation result). Notes are yours only — they're never sent to any model."),
            HelpCard("Adding a note", "Use the ✍️ icon on a report, model response, fan-out or secondary screen to pin a note to that thing. ✍️ here on this screen adds a note to the report as a whole. A thing can hold any number of notes — ✍️ again adds another."),
            HelpCard("Reading & editing", "Each note shows as a card collapsed to its AI title (or its first line until the title arrives); tap it to expand the full text. When expanded, ✏️ edits the note and 🗑 deletes it (no undo)."),
            HelpCard("Search, sort, delete all", "The search field filters notes by text, headline or target label; the Newest/Oldest button flips the order inside each group. The title-bar 🗑 deletes every note in the report after a confirm."),
            HelpCard("Titles", "Saving a note (add or edit) kicks off a quick background call that gives it a short title — the card's headline. Editing the text regenerates the title. The tiny cost shows in the report's cost table under the 'note' group."),
            HelpCard("On the View screens", "The same notes also appear, read-only, at the top of the matching View screen (a model response, a secondary result, a fan-out). You can't add or change notes there — use ✍️ on the Manage side."),
            HelpCard("Deleted targets", "If the model response or secondary a note was pinned to is later removed, its notes are grouped under 'Deleted item' here so you can still read or delete them. (Notes on a removed model are pruned automatically.)")
        )
    ),
    "report_agent_chat" to HelpContent(
        title = "Help - Refine answer",
        cards = listOf(
            HelpCard("What this is", "A chat with the model that produced this answer, anchored to the report. It opens seeded with the original prompt and the current answer, so you can ask for changes — e.g. \"please be more verbose\" or \"add a code example\"."),
            HelpCard("Apply", "Each assistant reply has an Apply button. Tapping it overwrites this answer in the report with that reply. Nothing is changed until you Apply, so you can explore freely first."),
            HelpCard("Conversation is saved", "The back-and-forth is stored on this answer, so re-opening 🗣️ continues where you left off."),
            HelpCard("System prompt & parameters", "🎭 and 🌡️ pick a system prompt / parameter preset for the next reply (seeded from the agent's own settings)."),
            HelpCard("Cost", "Refine-chat turns are billed like a normal chat and counted in AI Usage (statistics), not added to the report's cost table.")
        )
    ),
    "report_user_note_edit" to HelpContent(
        title = "Help - Edit note",
        cards = listOf(
            HelpCard("What this is", "A plain text editor for one user note. Type anything you want to remember about this report or one of its parts."),
            HelpCard("How to use it", "Enter your text and tap Save note (disabled while the field is empty). Back / cancel discards the edit. Your note text is private and is not included in any report/model API call."),
            HelpCard("Auto title", "On save, a short title is generated for the note by a quick background call (the bundled workers/user-note prompt) and becomes the card's headline. It refreshes a moment after you save and regenerates whenever you edit the text.")
        )
    ),
    "reports_hub" to HelpContent(
        title = "Help - Reports",
        cards = listOf(
            HelpCard("Overview", "Dashboard for everything to do with reports. New / Search / All live as icons in the bottom bar; list cards summarise what's already on disk."),
            HelpCard("Bottom icons — 🆕 / 🔍 / 🗂️", "🆕 New opens the creation entry points (blank, previous prompt, example prompt). 🔍 Search opens Quick local, Extended local, Remote semantic, and — when Experimental features is on — Local semantic search. 🗂️ All opens the paginated swipe-through of every saved report."),
            HelpCard("Pinned / Latest", "Two list cards (📌 Pinned mirrors every report flagged on Manage; 🕘 Latest shows the five newest), each up to five rows. There's no separate Running or Problems card — instead a row flags its own state (see Per-row icons). An empty card stays on screen at reduced opacity so the layout doesn't shift."),
            HelpCard("Per-row icons", "A row's leading icon is the report's own — UNLESS it's still running (a spinning ⏳ hourglass) or has broken work (⚠️, also listed on the Broken-work screen), which replace it. Tap a row to open at Manage; 🔧 jumps to Manage explicitly, 👁 opens the View tile grid, 🗑 prompts a delete confirmation.")
        )
    ),
    "new_ai_report_screen" to HelpContent(
        title = "Help - New report",
        cards = listOf(
            HelpCard("What you see", "Three tap-through rows: 🗒 New AI Report opens the blank form; 🔄 Start with a previous prompt opens the prompt history; 💡 Start with an example prompt opens the example-prompt picker (only shown when at least one example prompt is configured)."),
            HelpCard("How to use it", "Pick whichever entry point matches how you want to start. Each one lands on the standard report form where you finish entering title + prompt, then pick the agents/flocks/swarms/models that should answer.")
        )
    ),
    "all_ai_reports_screen" to HelpContent(
        title = "Help - All reports",
        cards = listOf(
            HelpCard("What you see", "Every saved report, newest first. The body doesn't scroll — rows are split into fixed pages that auto-fit the screen height. A small 'Page X of Y' header sits above the rows."),
            HelpCard("How to use it", "Swipe left / right to flip between pages. Each row carries the same tap-to-manage behaviour and 🔧 / 👁 / 🗑 icons as the dashboard cards. The page math re-fits when you rotate the device, so portrait and landscape both fill the visible area."),
            HelpCard("Multi-select", "Long-press a row to enter selection mode: rows gain checkboxes and a header appears with All (select everything), Export (share the selected reports as one zip), Delete (confirm-gated bulk delete) and Done. Back also exits selection.")
        )
    ),
    "ai_examples_screen" to HelpContent(
        title = "Help - Examples",
        cards = listOf(
            HelpCard("What you see", "A list of ready-made example reports bundled with the app. This screen appears from the home menu when you haven't set up any agents yet, so you can open a real, fully-populated report before configuring a provider. (The same list also lives as the 'Example AI Reports' card inside the AI Reports hub once you have agents.)"),
            HelpCard("How to use it", "Tap a row (or its 🔧) to open the example at Manage; tap 👁 to open the View tile grid. The first time you open an example it is imported into your reports — a brief 'Loading example report' popup shows while it copies. Imported examples then appear in your normal report lists."),
            HelpCard("Already exists", "If a report with the same name already exists, you're asked whether to Continue with the existing report, Overwrite it from the example (re-import a fresh copy), or Cancel."),
            HelpCard("Paging", "Rows are split into fixed pages that auto-fit the screen height; swipe left / right to flip between them.")
        )
    ),
    "report_get_info" to HelpContent(
        title = "Help - Report - titles/icons/...",
        cards = listOf(
            HelpCard("What you see", "A list of the report's metadata-generation jobs, each with a status: ⏰ clock (can't run yet), ⏳ hourglass (running), ❌ red cross (failed), ✅ green (done). Report-level jobs are the icon, the detected language, and the AI title; per-model jobs are each model's icon and its model-title."),
            HelpCard("When jobs run", "Icon / language / title start as soon as the report runs. A model's icon and model-title wait (⏰) until that model's own response finishes, then run. A model whose response failed leaves its icon/title rows on the clock — they can't be produced without a response."),
            HelpCard("Which rows appear", "Only enabled jobs are listed: report icon, report language, report title, per-model icons, and per-model titles each follow their own Settings -> Metadata & icons toggle. Report title also requires title mode = AI."),
            HelpCard("Statistics line + top rows", "Under the title bar a statistics line shows the report's API-call count, total API time and running cost in cents (tap → the costs screen). The list leads with two link rows: report (the report's icon + title — tap to return to Manage) and second (the secondary-results aggregate — tap for Report - second results). The bottom bar here is a single row: 1️⃣2️⃣3️⃣ jumps straight to any of the three report screens, 👁 opens the View hub, ❔/❓ on the right for help."),
            HelpCard("Costs", "Each row shows its own cost. The single info row on Manage report mirrors this screen's total, and its status aggregates these jobs (❌ if any failed, else ⏳ if any are still clock/running, else ✅). Tap a row to open its detail (icon / language / title / per-model icon).")
        )
    ),
    "report_second_results" to HelpContent(
        title = "Help - Report - second results",
        cards = listOf(
            HelpCard("What you see", "Every secondary result the report has produced, in one place: Tournament, Judge-the-judges, Compare, and Rank-the-translators batches; individual Meta / Rerank / Moderation / Fan-in rows; Fan-out and Fan-meta; and Translation runs (live + finished). Each row shows the same icon it uses on Manage, a status (⏳ running, ❌ failed, ✅ done), and its own cost."),
            HelpCard("Tap a row", "Opens that result's existing detail or drill-in (the rank / tournament / judges / compare leaderboard, the per-run translation list, a single meta result, the fan-out pairs, …). Back returns here; Back again returns to Manage."),
            HelpCard("Multi-select", "Long-press an individual Meta / Rerank / Moderation / Fan-in row to enter selection mode: those rows gain checkboxes and a header appears with All, Delete (confirm-gated bulk delete — spend moves to the costs-from-deleted-items bank) and Done. Batch rows (Tournament / Judges / Compare / Rank-the-translators / Fan-out / Translations) are managed from their own run screens, so they don't join the selection. Back also exits selection."),
            HelpCard("Statistics line + top rows", "Under the title bar a statistics line shows the report's API-call count, total API time and running cost in cents (tap → the costs screen). The list leads with two link rows: report (the report's icon + title — tap to return to Manage) and info (the metadata-jobs aggregate — tap for Report - titles/icons/...). The bottom bar's 👁 opens the View hub."),
            HelpCard("Costs", "Each row shows its own cost; the single second row on Manage report sums them all and its status aggregates them (❌ if any failed, else ⏳ if any still running, else ✅).")
        )
    ),
    "report_edit_model_title" to HelpContent(
        title = "Help - Edit model title",
        cards = listOf(
            HelpCard("What you see", "The title generated for one model's response (from Report - titles/icons/... → a model-title row). Edit it and tap Update title to save."),
            HelpCard("How it works", "This is an in-place text edit — it does not re-run the model or the title call. The new title shows on the model-title row and as the label of that model's model-icon row.")
        )
    ),
    "report_edit_overview" to HelpContent(
        title = "Help - Edit report",
        cards = listOf(
            HelpCard("What you see", "A single screen gathering everything editable about the report: the report icon (big, centred), the short title, the long title, the parameters preset, the system prompt, and the prompt body. Each has a ✏️ pencil that opens its dedicated editor; Back returns here."),
            HelpCard("Icon + titles", "The pencil beside the icon opens the Icon lookup screen (where Find alternative icons fans out across models). The pencil beside the short title opens Edit short title and the one beside the long title opens Edit long title — each with its own Find-alternative button."),
            HelpCard("Parameters + system prompt", "Each line shows the current preset name, or *NONE when unset. The pencil opens the same picker reached from the bottom-bar 🌡️ / 🎭 icons. A change here needs a Regenerate to affect future calls."),
            HelpCard("Prompt", "The card shows the report's prompt body; its pencil opens the prompt editor. Editing the prompt flags the report as needing a Regenerate to apply."),
            HelpCard("Edit models / icons / titles", "Edit models opens the model-selection screen in edit mode. Edit icons lists every icon in the report; Edit titles lists every dynamic title — each with manual edit + Find alternative. Reached from the ✏️ icon on Manage report.")
        )
    ),
    "report_create_overview" to HelpContent(
        title = "Help - Create analysis",
        cards = listOf(
            HelpCard("What you see", "A full-screen launcher (the 🔗 icon on Manage report) for adding a Meta-style secondary result: Meta and Compare with meta — each a big icon with a one-line explanation."),
            HelpCard("The options", "Meta runs a Compare / Critique / Synthesize prompt over the answers. Compare with meta scores each answer's similarity to a meta result. Tapping Meta opens its prompt picker; tapping Compare with meta opens a meta-item picker (then runs the meta-compare prompt of the same name)."),
            HelpCard("Moved to the bottom bar", "Rerank (🏆), Moderation (🚦), Translate (🌐), Fan out (🔱) and the head-to-head tools (🥊) now each have their own icon on the Manage report bottom bar. Rerank / Moderation jump straight to an existing result if there is one, else open the model picker.")
        )
    ),
    "report_edit_icons" to HelpContent(
        title = "Help - Edit icons",
        cards = listOf(
            HelpCard("What you see", "One row for every editable icon in this report: the report icon, report-language icon, each model's report icon, Meta/Compare icons, Rerank and Moderation icons, Tournament and Judge-the-Judges icons, per-language Translation icons, and fan-out/fan-in response icons. The leading glyph is the current icon (⬜ when none yet)."),
            HelpCard("How it works", "Tap any row to open that icon's Icon lookup screen, where Find alternative icons fans the icon prompt out across the models you pick and lets you choose a replacement. Picks save straight to that icon and the row refreshes."),
            HelpCard("Regenerate all — 🔄", "The title-bar 🔄 re-fires the report icon, language icon and every per-model icon worker call in one confirmed tap (call count shown in the dialog). Meta / ranking / moderation / translation / fan-out icons are regenerated from their own rows."),
            HelpCard("Reached from", "Edit report (✏️ on Manage report) → Edit icons.")
        )
    ),
    "report_edit_titles" to HelpContent(
        title = "Help - Edit titles",
        cards = listOf(
            HelpCard("What you see", "One row for every dynamic title in this report: the report title, the report long title, each model's title, and each fan-out response's title. Every row has a ✏️ pencil (manual edit) and a Find button (the multi-model alternative picker)."),
            HelpCard("Manual edit vs Find", "The pencil opens a text editor to type a title directly. Find opens the model picker, fans the title prompt out across the chosen models, and lets you pick an alternative — for report / per-model titles the pick lands in the editor for you to confirm; for fan-out titles it applies immediately."),
            HelpCard("Refresh all — 🔄", "The title-bar 🔄 re-fires the report short + long titles and every per-model title worker call in one confirmed tap. Fan-out response titles are regenerated from Fan Meta."),
            HelpCard("Reached from", "Edit report (✏️ on Manage report) → Edit titles.")
        )
    ),
    "report_edit_pair_title" to HelpContent(
        title = "Help - Edit title",
        cards = listOf(
            HelpCard("What you see", "The title generated for one fan-out response. Edit it and tap Update title to save, or use Find alternative titles to fan the title prompt out across models."),
            HelpCard("How it works", "An in-place text edit on that response's row — it doesn't re-run anything. The new title shows wherever that fan-out response is listed.")
        )
    ),
    "report_new" to HelpContent(
        title = "Help - New Report",
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
            HelpCard("Overview", "The model-selection page in the report flow. Empty model list at first; +Agent / +Flock / +Swarm / +Model / +Report fill it; Next advances to Report - setup, where the system prompt, parameters and worker routing are chosen and Generate report fires the dispatch."),
            HelpCard("Add buttons", "+Agent picks one saved agent, +Flock adds every member of a flock, +Swarm adds every (provider, model) pair in a swarm, +Model is the multi-select all-providers picker (tap as many as you want, then Back), +Report copies the model list from a previous report. Repeated taps stack — you can mix sources."),
            HelpCard("Next", "Advances to Report - setup — the step that sets the report's system prompt and parameters and decides who generates the report info, the model info, and which pool the worker batches use. The Generate report button there fires the dispatch for every model in the list and flips the screen to Report - manage as soon as the first row starts streaming."),
            HelpCard("Update model list (edit mode)", "When you reach this page via the ✏️ Edit report overview's Edit models row on a finished report, the bottom button switches to Update model list. It stages the new list and pops back without re-running — you re-fire later from Report - manage's title-bar 🔄 Regenerate."),
            HelpCard("Reached from", "Hub → New AI Report → enter title + prompt → Continue. Or History → open an existing report → ✏️ Edit report overview → Edit models — that variant lands here in edit mode (button reads Update model list).")
        )
    ),
    "report_select_workers" to HelpContent(
        title = "Help - Report - setup",
        cards = listOf(
            HelpCard("Overview", "The third step of the report flow (prompt → models → setup). A stack of collapsible cards — all collapsed on open, each showing its current selection — configures the system prompt, the API parameters, the second-result options, and (behind the Workers card) who does the worker jobs for this report; Generate report at the top fires the dispatch. Leave everything on its default to keep the standard behaviour."),
            HelpCard("System prompt", "Override the system prompt for every answer model in this report. Default keeps each agent's own system prompt. (Moved here from the 🎭 icon that used to sit on New report / Report - select models.)"),
            HelpCard("Parameters", "API parameter presets (temperature, max tokens, top-P, …) applied to every call in this report. Default uses each call's own parameters. (Moved here from the 🌡️ icon on New report / Report - select models.)"),
            HelpCard("Workers 👷", "Opens the full-screen Workers page — the four cards that decide who generates each worker job: Report info, Model info, Batches and Meta. (Split off this screen so the setup stays short.) Tap the card to open it; the ❓ on that page documents each card in detail."),
            HelpCard("Second result options", "Two switches (both off by default) that control the intermediate screens shown when you launch a second result from Report - second results. Select scope shows the scope step first (which reports / languages the run covers); off skips it and uses all reports + all present languages — it applies to Meta and Fan-out. Runtime parameters shows a prompt editor before the run, where you tweak the driving prompt for this run only (or tap Update prompt & run to save it). It applies to Meta and Fan-out (their existing edit screens) plus Compare, Fan-in, Tournament, Judge-the-judges, Translate (body + title) and Rank-the-translators. Only Rerank and Moderation are unaffected (no editable prompt). With both off, each kind runs straight on its defaults (e.g. Fan-out runs every successful model against every other)."),
            HelpCard("Editing later", "After generation, the 👷 icon on Report - manage reopens this Report - setup screen without the Generate button (and without the System prompt / Parameters cards). Save persists the change for the report's future batches; the worker cards (and the One-time group once picked) are shown and editable on the Workers page.")
        )
    ),
    "report_workers" to HelpContent(
        title = "Help - Workers",
        cards = listOf(
            HelpCard("Overview", "Who does the worker jobs for this report. Reached by tapping the Workers card on Report - setup (and, after generation, via the 👷 icon on Report - manage, which reopens Report - setup). The Report info card, then the Use report models switch, then three collapsible cards (Model info, Batches, Meta) — all collapsed on open, each carrying a one-line description of what it sets. Leave everything on its default to keep the standard behaviour."),
            HelpCard("Report info", "Who generates the report icon, the short and long titles, and the language detection + language icon. Prompt configuration uses each worker prompt's configured chain; Specify model or agent runs all of them on your own ordered fallback chain of Model / Agent / Flock / Swarm entries (added inline below the choice)."),
            HelpCard("Use report models", "A shortcut switch (default off) directly under Report info. When on, the Model info / Batches / Meta cards are hidden and forced to this report's own answer models — Model info → Own model, Batches → Report models with Round robin, Meta → Report models. Turn it off to reveal and configure those three cards independently."),
            HelpCard("Model info", "Who generates each answer's per-model title and icon. Prompt configuration uses the model-titles / model-icons worker prompts' chains; Own model has each report model write its own title and icon."),
            HelpCard("Batches", "Which pool the batches draw from — Fan Meta, Translation, Tournament, Compare. (Meta + Fan-in have their own Meta card; Rerank, Moderation and Judge-the-judges are NOT listed: they run on the workers defined in their own prompt, whatever this card says.) Prompt configuration keeps each prompt's chain (a prompt set to *SELECT still asks at run time). Report models uses this report's own answer models. User selectable for each batch opens the worker picker on every batch start. One time selectable asks once — at the first batch — and reuses that group for every later batch."),
            HelpCard("Meta", "Worker pool for the Meta + Fan-in batches only, split out of the Batches card so they can run on a different pool. Identical options to Batches (Prompt configuration / Report models + Worker selection / User selectable for each batch / One time selectable)."),
            HelpCard("Worker selection", "Visible under Report models (on both the Batches and Meta cards). When available (the default) hands work to whichever model is free, so fast models absorb more items. Round robin deals items to the models in rotation so every model gets about the same share — a slow model keeps its items (they wait), but a model that errors (429, model gone) passes the item to the next in rotation."),
            HelpCard("Batches that ignore these cards", "Some batches don't use the Batches choice at all. Rerank and Moderation run on the workers configured on their own Internal Prompt (a *SELECT prompt still asks at run time); Report models / SELECT modes do not apply. Judge-the-judges and Rank-the-translators have NO worker choice — each evaluates its own participants: Judge-the-judges re-judges with the actual judges that ran the report's Tournament (so it stays greyed until that Tournament finishes), and Rank-the-translators ranks with the actual translation models of the Translation batch it follows. Moderation also needs a worker whose provider has a native moderation endpoint (e.g. Mistral) — a chat model can't serve /moderations and the row errors."),
            HelpCard("Editing later", "After generation, the 👷 icon on Report - manage reopens Report - setup; tap Workers there to revisit these cards. Save persists the change for the report's future batches; the One-time group (once picked) is shown and editable here too.")
        )
    ),
    "report_run" to HelpContent(
        title = "Help - Report - manage",
        cards = listOf(
            HelpCard("Overview", "The post-Generate page in the report flow. Per-agent rows stream in as each model returns; every operation you can apply to the run sits as an icon on the title bar and bottom bar (see 'Icon-based actions' below). Sibling of the pre-Generate Report - select models — a Generate (or opening a saved report from History) lands you here."),
            HelpCard("Statistics line", "Directly under the title bar's orange title: the report's API-call count, its total API time in seconds, and the running cost in cents (¢). The cost updates live as each call settles; tap the line to open the report's costs screen. The same line appears on Report - titles/icons/... and Report - second results."),
            HelpCard("Per-agent rows", "One card per dispatched model. While the call is in flight the row shows progress; on completion it carries the response, token + cost cell, optional 🐞 trace icon, and the auto-generated per-model emoji once the icon worker finishes."),
            HelpCard("Row labels", "Report rows show generated per-model titles by default. Tap 🔤 in the icon bar to switch those rows to raw provider/model names; tap it again to return to titles."),
            HelpCard("While running", "There's no dedicated Stop button — leaving the screen (Back) lets the run keep going in the background and pops a toast when it's ready; reopening the report shows the in-flight rows still streaming. Deleting the report (🗑) cancels every in-flight call for it first, so that's the way to abort a run outright."),
            HelpCard("Icon-based actions, not an action row", "Once generation is complete, every operation is an icon rather than a labelled button: 🔄 Regenerate, 🐞 Trace, 🗑 Delete, 👁 View (opens the View hub tile grid), ✏️ Edit (opens the Edit report overview), 💬 Chat, 📤 Share/Export, 👯 Copy report and 📌 Pin/Unpin sit on the title bar; 🔗 Add (the Meta / Compare-with-meta launcher), 🔱 Fan out, 🏆 Rerank, 🚦 Moderation, 🌐 Translate and the Tournament icon live on the Report - second results layer's bar."),
            HelpCard("Pick workers at run time", "Tournament, Compare and Translate follow the report's Worker batches choice (👷 Report - setup); Meta and Fan-in follow that screen's separate Meta card. Prompt configuration runs each batch on its Internal Prompt's workers — and a prompt set to *SELECT (Settings → Prompt management) opens the +Agent/+Flock/+Swarm/+Model picker first. User selectable for each batch forces that picker on every batch; One time selectable asks once and reuses the group; Report models never asks. Rerank and Moderation always use their own prompt's workers. Judge-the-judges and Rank-the-translators have no worker picker at all — they reuse the actual judges of the Tournament / translators of the Translation they evaluate."),
            HelpCard("Per-model icons (auto-run)", "When Settings -> Metadata & icons -> Generate per model icons is on, each successful response schedules the workers/model-icons flow. The worker derives an emoji from the generated model title and response context, stores it on that report row, and falls back to the Default icons report-model glyph if no usable emoji is produced. Icon costs appear on the row/report cost breakdown and in AI Usage."),
            HelpCard("View and Edit", "👁 View opens the View hub — the tile-grid overview of everything this report has to look at (Responses, Prompt, Costs, plus a tile per secondary kind the report carries). ✏️ Edit opens the full-screen Edit report overview (icon, titles, parameters/system-prompt line, prompt card, and Edit models/icons/titles at the bottom) — picking Edit models lands on Report - select models in edit mode."),
            HelpCard("Pending-changes banner", "Orange banner appears when the user edited prompt / models / parameters since the last run — Regenerate is required for the new values to take effect. Until then the displayed rows reflect the old configuration."),
            HelpCard("Stuck rows", "On reopen, any row left in PENDING / RUNNING by a force-quit is recovered: a one-shot sweep marks blank-content / null-error / null-duration secondaries as errored, and a 150 ms tick refreshes the inline meta list. If a row still spins, tap Regenerate."),
            HelpCard("Reached from", "Pressing Generate report on Report - setup. Or History → open any saved report — you land here directly, skipping the selection pages."),
            // Bottom-bar icon descriptions moved to the "report_run_icons"
            // page (reached via the ❔ icon, or the link at the top of this
            // page). "Stalled translation auto-reconcile" + "App-wide
            // background resume sweep" live in the "concepts" topic.
        )
    ),
    "report_translations" to HelpContent(
        title = "Help - Translations",
        cards = listOf(
            HelpCard("What you see", "A plain list of the language versions of this report: the Original language first, then one row per existing translation. No progress, counts or cost — just the language. Reached from the 🌐 icon on Manage report once at least one translation exists (with none, 🌐 starts a new translation directly)."),
            HelpCard("How it works", "Tap the Original row to return to the report. Tap a translation row to open that run's detail (its per-call list). The only bottom-bar icon is 🆕 — it starts a new translation (pick a target language, then the model(s)) and drops you on the new run."),
            HelpCard("🏅 Rank the translators", "Each translation row carries a 🏅. Tap it to rank the models that produced that translation: every translated answer is scored 0–100 by the other models, and the translators are ranked by average. Most useful when several models shared the translation.")
        )
    ),
    // Per-screen icon legend reached from the ❔ bottom-bar glyph on
    // "Manage an AI report". Ordered: icons specific to this screen, then
    // general icons with screen-specific behaviour, then standard icons.
    "regenerate_batch" to HelpContent(
        title = "Help - Regenerate report",
        cards = listOf(
            HelpCard("Overview", "The 🔁 icon on the Manage screen opens a confirm dialog; OK enqueues an app-restart-survivable batch that re-runs everything on the report in a fixed order. Replaces the legacy one-shot regenerate that only touched the agent rows."),
            HelpCard("Phase order", "1) Title (short + long — runs before the icon so the icon can derive from the fresh long title). 2) Report icon. 3) Language (detection + language-icon). 4) Model responses (agents). 5) Meta — single-call meta + rerank + moderation (Judge-the-judges and Compare are excluded — they resume from their own screens). 6) Fan-out — every fan-out pair. 7) Fan-in — combined-report rows. 8) Translations. 9) Fan Meta — one worker call per fan-out pair regenerates its title + icon. 10) Tournament — every match row (the ranking itself is recomputed once its matches settle, not a task). The batch moves to the next phase only when every row in the current phase is SUCCESS."),
            HelpCard("Halt + restart on error", "Halts on the first row that ends ❌ in any phase. The Regenerate row on Manage turns ❌. Fix the offending row (delete + rerun via the existing per-row UI), then tap Restart on the Regenerate detail screen. A halted batch also surfaces on the ⚠️ Broken-work screen (the 30 s background scan detects it) — but resuming is always a manual Restart now; the app no longer auto-resumes."),
            HelpCard("Survives app kill", "The job's task list + status lives on disk under <filesDir>/regenerate/<reportId>.json. After an app kill the background scan flags the interrupted batch on the ⚠️ Broken-work screen instead of silently reviving it — open the report and tap Restart on the Regenerate detail screen to pick up from the current phase."),
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
        title = "Help - View report",
        cards = listOf(
            HelpCard("What you see", "The View home for a report — a grid of tiles, one per thing this report has to look at: the original prompt, the per-model responses, the cost breakdown, the in-app HTML preview, plus one tile for each kind of post-run result the report carries (Meta, Compare, Rerank, Tournament, Judge the Judges, Moderation, Fan-out, Fan-in, Translate). The title bar carries the AI logo (taps go to the app home), the report's own title centred in white, and the help icon."),
            HelpCard("How to read it", "Each tile shows an emoji, a label, and — when a kind has more than one item — a small count badge in the top-right. Tiles you can tap are at full colour; tiles for kinds this report doesn't have yet aren't shown at all. Tap a tile to open the matching View screen. Long-press a tile and drag it onto another to swap their positions — your order persists across reports, so once you've arranged the grid the way you like it, it stays that way. When the report has translations, a row of large flag-style icons at the top picks the active language; that language is carried into every tile you open.")
        )
    ),
    "view_tournament" to HelpContent(
        title = "Help - Tournament results",
        cards = listOf(
            HelpCard("The ranking + method switch", "The top table is the 1..N ranking, best first, with a score and one-line reason per answer — the same layout the Rerank view uses. The method buttons re-aggregate the SAME head-to-head results instantly and with no extra API calls. Copeland ranks by opponent wins, Elo replays pairs as rated games, Davidson fits a tie-aware pairwise strength model, Markov uses a stationary graph ranking, Schulze ranks by strongest beat-paths (the Condorcet method, robust to cycles), Colley solves the bias-free sports-rating system (schedule-strength aware), and TrueSkill2 keeps a Bayesian skill belief (μ − 3σ). Whichever method is selected is also the ordering a Top-ranked scope (Meta / Translate) will use."),
            HelpCard("Where it comes from", "Reached from the report's View home (the Tournament tile), or by tapping the 👁 view icon on the Tournament page in Manage. A tournament is selectable as a Top-ranked source anywhere a rerank is, because its ranking conforms to the same format.")
        )
    ),
    "tournament_l1" to HelpContent(
        title = "Help - Tournament",
        cards = listOf(
            HelpCard("What it is", "A tournament ranks the report's answers by pairwise head-to-head judging. Every unordered pair of responses is judged twice — once each way (A-vs-B and B-vs-A) — to cancel first-position bias, so for N answers there are N(N-1) matches. Each match is judged by the worker engine using the configured tournament workers, so judging can spread across many models rather than one. Start a tournament from the report's 🆕 Create launcher → Tournament."),
            HelpCard("Statistics + grouping", "The counters show Total / Done / Error / Run / Wait / Queue / Costs (Wait = parked on a provider rate-limit cap). Matches are worker-judged, so a rate-limited judge is skipped and another picked — no Bench column is used, and terminal failures count as Error. The list groups by the report answer being compared; the green row fill shows progress. Tap a group to drill into its matches, then a match to see the two responses and the verdict. The 🐜 icon opens Tournament workers — the same matches grouped by the judge model that scored them."),
            HelpCard("Viewing the ranking", "The 👁 view icon at the bottom opens the View Tournament screen — the 1..N ranking with the tournament method switch. The 🗑 in the title bar deletes the whole tournament; 'Restart failed' re-judges any errored matches.")
        )
    ),
    "tournament_l2" to HelpContent(
        title = "Help - Tournament group",
        cards = listOf(
            HelpCard("What you see", "Every match in the chosen group. Judge-model groups show Result / Model 1 / Model 2. Report-model groups show Score / Model / Judge, where Score is from the selected report model's perspective. Long values ellipsize. Tap a row for the full match detail.")
        )
    ),
    "tournament_l3" to HelpContent(
        title = "Help - Tournament match",
        cards = listOf(
            HelpCard("What you see", "One head-to-head: the coloured A/B model lines, the verdict (winner + confidence + the judge's one-line reason), which worker model judged it, the orientation (A-vs-B or the swapped B-vs-A pass), and the two full response cards. Swipe horizontally to step through the other matches in this group; the 🔄 in the title bar re-judges this match through the worker engine.")
        )
    ),
    "tournament_workers" to HelpContent(
        title = "Help - Tournament workers",
        cards = listOf(
            HelpCard("What it is", "The per-judge-model breakdown of this tournament — one row per worker model that judged matches, with the count it judged and a green bar normalised to the busiest judge. Reached from the 🐜 icon on the Tournament screen's bottom bar."),
            HelpCard("Drilling in", "Tap a judge model to see the matches it judged, then a match for the verdict + the two responses. The 🔄 redo / 🗑 delete / 👁 view actions mirror the main Tournament screen. Back returns to the report-model list.")
        )
    ),
    "fan_meta_workers" to HelpContent(
        title = "Help - Fan Meta workers",
        cards = listOf(
            HelpCard("What it is", "The per-meta-worker breakdown of this Fan Meta run — one row per worker model that produced a pair's title + icon, with the count it handled and a green bar normalised to the busiest worker. Reached from the 🐜 icon on the Fan Meta screen's bottom bar."),
            HelpCard("Drilling in", "Tap a meta-worker model to see the pairs it titled, then a pair for its icon + title detail. The 🔄 re-run / 🗑 delete / 🐞 trace actions mirror the main Fan Meta screen. Back returns to the report-model list.")
        )
    ),
    "translation_workers" to HelpContent(
        title = "Help - Translation workers",
        cards = listOf(
            HelpCard("What it is", "The per-model breakdown of this translation run — one row per worker model that translated items (plus any idle worker still held by the cost-aware queue), with the call count and a green bar normalised to the busiest model. Reached from the 🐜 icon on the Translation screen's bottom bar."),
            HelpCard("Drilling in", "Tap a model to see the items it translated, then an item for the source + translation. The 🔄 redo / 🗑 delete / 👁 view / 🐞 trace actions mirror the main Translation screen. Back returns to the types list.")
        )
    ),
    "judge_eval_l1" to HelpContent(
        title = "Help - Judge the judges",
        cards = listOf(
            HelpCard("What it is", "This batch evaluates the JUDGES. It takes the same judge models the Tournament uses (the worker models in your 'tournament' swarm) and gives EVERY judge the SAME 25 random head-to-head pairs from this report's answers, so their verdicts can be compared. Start it from the report's 🆕 Create launcher → Judge the judges."),
            HelpCard("Statistics", "The counters show Total cells (judges × matches) / Done / Error / Run / Bench / Wait / Queue / Costs, with the judge and match counts below. Each cell is a fixed judge model (no substitution), so a benched judge — one on a >1h rate-limit cooldown — gets its own Bench column (it'll recover when the cooldown lifts), and Wait = cells parked on a provider rate-limit cap. The 'Judges' / 'Matches' toggle switches the table below between the judge leaderboard (default) and the per-match list. While running, the Judges view shows one progress row per judge."),
            HelpCard("The analysis", "When every cell is judged, the judges are ranked by CONSENSUS AGREEMENT — how often each judge matched the majority verdict across the 25 matches. 'Consensus strength' is the average agreement (high = the judges broadly agree). Each judge row also shows its cost, total API time, and agreement. Tap a judge to see its verdict on each match next to the consensus; the ✏️ opens the judge swarm for editing, the 🔄 redoes the whole batch from scratch, the 🗑 deletes the whole run, 'Restart failed' re-judges errored cells.")
        )
    ),
    "judge_eval_l2" to HelpContent(
        title = "Help - Judge detail",
        cards = listOf(
            HelpCard("What you see", "Every match this judge judged: the two models compared, the judge's verdict (A / B / tie), and whether it agreed (✓) or disagreed (✗) with the consensus of all judges. Tap a row for the full match detail.")
        )
    ),
    "judge_eval_l3" to HelpContent(
        title = "Help - Judge match",
        cards = listOf(
            HelpCard("What you see", "One head-to-head as this judge saw it: its verdict + confidence + one-line reason, the consensus verdict for comparison, and the two full response cards (the chosen side highlighted). The 🔄 in the title bar re-judges this single cell.")
        )
    ),
    "judge_eval_match" to HelpContent(
        title = "Help - Match",
        cards = listOf(
            HelpCard("What you see", "One of the 25 matches and how EVERY judge scored it: each judge's verdict (A / B / tie), whether it agreed (✓) or disagreed (✗) with the consensus, and a 🐞 to open that judging call's API trace. Tap a judge to see the full match detail from that judge's perspective.")
        )
    ),
    "compare_select_meta" to HelpContent(
        title = "Help - Compare with meta",
        cards = listOf(
            HelpCard("What it is", "Compare with meta scores how closely each report answer matches a meta result you already have on the report — a Compare / Summarize / Synthesize prose — as a percentage 0–100. It reads as 'alignment to the synthesized view': which models land closest to the consensus."),
            HelpCard("Pick a meta item", "Tap a meta result to score the answers against. Only meta results that have a meta-compare prompt of the SAME NAME are listed — the rest can't be compared and are hidden. Every successful answer is then scored against the picked item by the WORKER engine (your 'tournament' swarm), so there's no model to pick."),
            HelpCard("No prompt to pick", "Compare runs the meta-compare prompt with the same name as the meta item you tapped (e.g. a 'summarize' meta → the 'summarize' meta-compare prompt), so it starts immediately — there's no comparison-prompt picker. Edit those prompts under AI Setup → Prompt management → Compare prompts (use @RESPONSE@ for the answer, @META_RESPONSE@ for the meta result, and ask for a parseable 'percentage: <0-100>' line).")
        )
    ),
    "compare_l1" to HelpContent(
        title = "Help - Compare with meta",
        cards = listOf(
            HelpCard("What it is", "The results grid for a Compare-with-meta run: each report answer scored 0–100 for how closely it matches the chosen meta result, judged by the worker engine. Start one from the report's 🆕 Create launcher → Compare with meta, then pick one meta result (it runs the same-named meta-compare prompt automatically)."),
            HelpCard("Statistics + list", "The counters show Total / Done / Error / Run / Wait / Queue / Costs (Wait = parked on a provider rate-limit cap). Cells are worker-judged, so a rate-limited judge is skipped and another picked — no Bench column is used, and terminal failures count as Error. Below them each report answer is listed with its score against the meta result — one cell per answer, since a run only ever scores against the one meta item you picked. Tap an answer to open its full detail directly."),
            HelpCard("Actions", "🔄 redoes the whole comparison from scratch, 🗑 deletes the run, 'Restart failed' re-scores any errored cells.")
        )
    ),
    "compare_l2" to HelpContent(
        title = "Help - Compare with meta - model",
        cards = listOf(
            HelpCard("What you see", "One report answer's score against the picked meta item: the big percentage, the answer model vs. the meta item, the worker's one-line reason (when given), and which worker scored it. A 'Report model' / 'Compare model' card below names both sides plus the meta-compare prompt used (with a pencil to jump to editing it), followed by an API-interaction card showing the resolved prompt + the worker's reply."),
            HelpCard("Reload", "The 🔄 in the title bar re-scores just this cell in place, replacing its score, reason and cost.")
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
            HelpCard("Overview", "Detail view for the main report icon — the emoji shown next to the report title. Reached by tapping the report icon from Manage/Edit. Initial generation uses the workers/report-icon flow; alternative searches use alt/main."),
            HelpCard("Subject (green row)", "Shows the stored icon prompt subject, normally `main` for the initial icon and `main_alt` after an alternative pick. Legacy rows whose prompt field is empty fall back to `main`."),
            HelpCard("Title-bar icons", "💬 Continue in chat (preseeds a chat with the prompt + emoji). ℹ️ Model info for the model that ran the call. 📋 Copy the API-interaction body. 📤 Share via the system sheet. 🐞 jumps to the captured API trace (only when tracing was on at call time)."),
            HelpCard("Model / API interaction / Emoji cards", "Standard layout — the same shape for every Icon-lookup scope: provider + model + cumulative cost, plain `[user] … [assistant] …` 2-message transcript, big centred glyph (⏳ pending, ❌ on error)."),
            HelpCard("Find alternative icons", "Runs the self-contained alt/main prompt across user-picked provider/model pairs. Pick a returned emoji to commit it to the report icon."),
            HelpCard("Cost attribution", "Initial call + every alt attempt is bumped on `Report.iconInputCost / iconOutputCost`. On the Report → API cost table the alt calls surface as per-call `icon_main_alt` rows; the initial generation as `icon_main`. By-type collapses every `icon_*` row into one `icons` group."),
            HelpCard("Trace category", "`report/icon` for the initial generation, `alt/main` for Find alternative icons.")
        )
    ),
    "icon_lookup_agent" to HelpContent(
        title = "Help - Icon lookup — per-agent icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for one response row's model icon. Reached by tapping the row emoji on Manage. Initial generation uses the workers/model-icons flow and stores one icon on that report agent."),
            HelpCard("Subject (green row)", "Shows the stored prompt subject, normally `report_title_icon` for generated row icons or `report_alt` after an alternative pick."),
            HelpCard("Title-bar icons", "ℹ️ Model info / 📋 Copy / 📤 Share / 🐞 trace. Continue-in-chat is intentionally NOT wired here — the agent's response already lives on the result screen's row, not here."),
            HelpCard("API interaction card", "Shows the worker prompt/response transcript used for the stored row icon, including report prompt and response context when available."),
            HelpCard("Find alternative icons", "Runs alt/report across picked models. Pick lands on `ReportAgent.icon` for this agent only."),
            HelpCard("Cost attribution", "Bumped on `ReportAgent.iconInputCost / iconOutputCost` and surfaced in the report cost table under the model-icons / alternative-icon call types."),
            HelpCard("Trace category", "`model/icons` for initial generation, `alt/report` for Find alternative icons.")
        )
    ),
    "icon_lookup_meta" to HelpContent(
        title = "Help - Icon lookup — meta-prompt icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for the cached icon on a Meta-prompt row (Compare / Summarize / Critique / Rerank / Moderation / fan-in / fan-out summary). Reached by tapping the emoji on a Meta row. The icon is keyed `(prompt.name, prompt.title)` on the cross-report `InternalPromptIconCache`, so every report that uses the same prompt sees the same icon."),
            HelpCard("Subject (green row)", "The cached `promptName` field on the cache entry — normally `second-meta`. Find-alt picks use `meta_alt`."),
            HelpCard("Title-bar icons", "ℹ️ is NOT wired (the cache entry doesn't track a specific model). 📋 / 📤 work on the transcript. 🐞 looks up the most recent second/meta or alt/meta trace for the cache's stored model — cross-report because the cache itself is cross-report."),
            HelpCard("API interaction card", "One-shot exchange from the resolved second/meta prompt with the prompt name/title substituted, then the returned emoji."),
            HelpCard("Find alternative icons", "Runs alt/meta across picked models. The picked emoji is committed via `InternalPromptIconCache.pickAlternative`."),
            HelpCard("Cost attribution", "Each call bumps the cache entry's cumulative `inputCost / outputCost`. Per-row attribution: when the prompt has a matching SecondaryResult on the current report, the call also bumps that SR's `inputCost / outputCost` so the Report → Manage row total includes the alt spend."),
            HelpCard("Trace category", "`second/meta` for initial generation, `alt/meta` for Find alternative icons.")
        )
    ),
    "icon_lookup_translation" to HelpContent(
        title = "Help - Icon lookup — translation row icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for the cached icon on a per-target-language translation row (one per language the report has been translated into). Reached by tapping the emoji on a Translate row. Keyed `(\"translation_icon\", language)` on the cross-report `InternalPromptIconCache`."),
            HelpCard("Subject (green row)", "`translation` (or `translation_alt` after a Find-alt pick)."),
            HelpCard("Title-bar icons", "ℹ️ NOT wired. 📋 / 📤 work on the transcript. 🐞 looks up the most recent translation/icon or alt/translation trace for the cache's stored model — cross-report."),
            HelpCard("API interaction card", "One-shot exchange from translation/icon with `@LANGUAGE@` substituted, then the returned emoji."),
            HelpCard("Find alternative icons", "Runs alt/translation across picked models. The picked emoji is committed via `InternalPromptIconCache.pickAlternative` with `promptName = translation_alt`."),
            HelpCard("Cost attribution", "Each call bumps the cache entry's cumulative cost; per-row attribution goes into the first TRANSLATE secondary result for the language when one exists."),
            HelpCard("Trace category", "`translation/icon` for initial generation, `alt/translation` for Find alternative icons.")
        )
    ),
    "icon_lookup_language" to HelpContent(
        title = "Help - Icon lookup — detected-language icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for the report's detected-language icon — the emoji rendered next to language-aware report headers. Initial generation uses workers/report-language to detect the language and produce the icon."),
            HelpCard("Subject (green row)", "`language` (or `language_alt` after a Find-alt pick). Legacy rows fall back to `language`."),
            HelpCard("Title-bar icons", "💬 Continue in chat (preseeds a chat about the language). ℹ️ Model info / 📋 / 📤 / 🐞."),
            HelpCard("API interaction card", "Shows the report-language worker transcript, including the detected language and returned emoji when available."),
            HelpCard("Find alternative icons", "Runs alt/language across picked models. Pick commits onto `Report.languageIcon`."),
            HelpCard("Cost attribution", "Bumped on `Report.languageIconInputCost / languageIconOutputCost`. Per-call audit rows are `icon_language` / `icon_language_alt`."),
            HelpCard("Trace category", "`report/language` for initial generation, `alt/language` for Find alternative icons.")
        )
    ),
    "icon_lookup_pair" to HelpContent(
        title = "Help - Icon lookup — fan-out pair icon",
        cards = listOf(
            HelpCard("Overview", "Detail view for one fan-out pair's icon — the emoji on a single source/responder cell in a fan-out run. It is produced by Fan Meta, which generates both title and icon for each pair."),
            HelpCard("Subject (green row)", "Normally `fan-meta` for the Fan Meta generation and `fan_out_alt` after a Find-alt pick."),
            HelpCard("Title-bar icons", "ℹ️ NOT wired. 📋 / 📤 work on the transcript. 🐞 looks up the most recent fan/meta or alt/fan_out trace for the pair's model under this report."),
            HelpCard("API interaction card", "Shows the Fan Meta worker prompt/response for this pair, using report prompt, source response, and pair response context."),
            HelpCard("Find alternative icons", "Runs alt/fan_out across picked models. The picked emoji is committed to the pair via `setFanOutIconAndTier` with `promptUsed = fan_out_alt`."),
            HelpCard("Cost attribution", "Bumped on the pair's `SecondaryResult.iconInputCost / iconOutputCost` (visible in the Cost line here and in the L2/L3 row totals). On Report → Manage → Costs the Fan Meta spend has its own `fan/meta` row (separate from the pair's `meta/<prompt>` response row). Find-alt picks trace under `alt/fan_out`."),
            HelpCard("Trace category", "`fan/meta` for Fan Meta generation, `alt/fan_out` for Find alternative icons."),
            HelpCard("How to reach this screen", "Fan Out → L2 (MAIN mode) — tap the pair's icon on its row (the icon replaces the leading ✅ when present). Fan Out → L3 (MAIN mode) — tap the small icon in the answerer pane's header row (just before the model name).")
        )
    ),
    "find_icons_selection" to HelpContent(
        title = "Help - Find icons",
        cards = listOf(
            HelpCard("Overview", "Model picker that fans the current icon scope's alternative prompt across whatever provider/model pairs you choose. Reached from an Icon detail screen's Find alternative icons button."),
            HelpCard("+Add chips", "Same five chips as the New-Report flow: Agent (saved Agents), Flock (named groups of agents), Swarm (named groups of provider/model pairs), Report (copy the model list from a finished report), Model (free-form (provider, model) picker)."),
            HelpCard("Selected list", "Rows are sorted alphabetically by model id. Each row shows model id + capability badges + provider id + pricing per million tokens. The ✕ on the right drops a single row; the Clear button at the bottom wipes the whole list."),
            HelpCard("Stripped affordances", "Params and Sys prompt are intentionally absent — an icon is a one-shot @PROMPT@ → emoji round-trip; parameter presets don't apply."),
            HelpCard("Find Icons", "Kicks off one call per selected provider/model using the relevant alt category: alt/main, alt/report, alt/meta, alt/language, alt/translation, or alt/fan_out. Per-provider throttles and global API-call caps still apply. The screen then opens the Alternative icons live list."),
            HelpCard("Cost note", "Each call's tokens and cost are attributed to the icon scope that launched it: report icon, report row icon, meta cache, translation cache, detected-language icon, or fan-out pair."),
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
    "alternative_titles" to HelpContent(
        title = "Help - Alternative titles",
        cards = listOf(
            HelpCard("Overview", "Live candidate list for a title fan-out, opened from the 'Find alternative titles' button on an Edit-title screen. One row per (provider, model) you picked on the previous screen — each model proposes a title for the same source (the report prompt, or that model's response)."),
            HelpCard("Row meanings", "⏳ = the call is still running. A title shown = the call returned one; the row is tappable. ❌ = the call failed; the reason shows in red."),
            HelpCard("Tap to pick", "Tapping a candidate fills that title into the Edit-title field and returns you to the editor — nothing is saved until you tap Update there, so you can tweak it first."),
            HelpCard("Restart", "Drops the current candidates and re-opens the model picker so you can fan out across a different set of models."),
            HelpCard("Cost", "Each candidate call posts to AI Usage with a title/alternative-title kind. Because the flow is exploratory, only the selected result is written back to the edited report field.")
        )
    ),
    "alternative_translations" to HelpContent(
        title = "Help - Alternative translations",
        cards = listOf(
            HelpCard("Overview", "Live candidate list for a translation fan-out, opened from the 'Find alternative translation' button on a translation entry (L3). One row per (provider, model) you picked on the previous screen — each model re-translates the same source text into the same target language."),
            HelpCard("Row meanings", "⏳ = the call is still running. A translation shown = the call returned one; the row is tappable. ❌ = the call failed; the reason shows in red."),
            HelpCard("Tap to pick", "Tapping a candidate replaces this entry's translation in place — its text, model and cost overwrite the stored row — and returns you to the translation detail."),
            HelpCard("Restart", "Drops the current candidates and re-opens the model picker so you can fan out across a different set of models."),
            HelpCard("Cost", "Each candidate call posts to AI Usage; only the candidate you pick has its cost written onto the stored translation row.")
        )
    ),
    "alternative_icons" to HelpContent(
        title = "Help - Alternative icons",
        cards = listOf(
            HelpCard("Overview", "Live progress list for an in-flight or completed icon fan-out. One row per (provider, model) pair you picked on the previous screen. State sits in AppViewModel.iconFanOutByReport — survives navigating away and back into the screen for the same report."),
            HelpCard("Row meanings", "⏳ = the icon call is still running (or queued behind the per-provider throttle). The emoji shown big = the call returned a usable response and the row is tappable. ❌ = the call failed or returned an empty body; the error reason renders underneath in red. The row is non-tappable."),
            HelpCard("Tap to pick", "Tapping a Done row commits its emoji as the Report's icon and records the model label on the Report. All three icon overlays (Alternative icons, Find icons picker, Icon detail) close together — you land back on the Report result screen."),
            HelpCard("Cost", "Every call's tokens × pricing tier is attributed to the icon scope that launched the search as the response lands, so the icon row/cache/pair cost reflects the search cost regardless of which candidate you pick."),
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
            HelpCard("Title bar — 🧠", "Opens Reasoning Effort sweep for this model response. It replays the same report call with None / Low / Medium / High on models that expose a controllable reasoning parameter."),
            HelpCard("Title bar — 🧭", "Opens Web search replay for this model response. It reruns the same report call with web search enabled, appends the freshness instruction to the prompt, and lets you apply only the new response body."),
            HelpCard("Translation info", "Shown only when this report has a sourceReportId and the matching agent's responseBody is loaded — opens TranslationCompareScreen with original on top, translation on bottom."),
            HelpCard("Continue in chat", "Disabled when the response is blank or errored. Opens the Continue picker (current history+model / pick agent / configure on the fly)."),
            HelpCard("Pitfalls", "Removing the last successful agent from a report leaves it empty — reopen the parent report and tap the title-bar 🔄 to Regenerate.")
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
            HelpCard("Title bar — 🔄", "Not wired here — re-run a secondary by deleting it and re-firing it from its own launcher on Manage (🔗 Add, or the Rerank / Moderation icon)."),
            HelpCard("Title bar — ℹ️", "Wired when the providerId resolves. Jumps to Model Info for this row's (provider, model) pair."),
            HelpCard("Title bar — 🗑", "Wired. Opens 'Delete this <kindLabel>?' confirm; confirming calls onDelete and pops back."),
            HelpCard("Title bar — 🐞", "Wired when tracing is on and a matching trace exists (reportId + this row's model, max-by-timestamp)."),
            HelpCard("Translation info", "Shown only for META rows that have a translateSourceTargetId resolving to a non-blank source — opens TranslationCompareScreen.")
        )
    ),
    "rerank_detail" to HelpContent(
        title = "Help - Rerank result — detail",
        cards = listOf(
            HelpCard("Overview", "Dedicated detail screen for a rerank result — the 1..N best-first ranking of the report's answers. Errors render as a red Error block; blank content shows '(no content)'."),
            HelpCard("Ranking table", "Parses the structured JSON ([{id, rank, score, reason}, ...]) the rerank flow produces (chat-prompt path or the native rerank API) and renders a sorted Rank / Model / Score / Reason table, resolving each bracketed [N] to its real provider / model. Falls back to raw markdown when the model deviated from the schema."),
            HelpCard("Title bar", "✏️ opens 'Change result', 👁 opens the View Rerank screen, ℹ️ jumps to Model Info for this row's (provider, model), 🐞 opens the captured trace when tracing was on, 🗑 deletes the rerank, and Copy / Share export the raw ranking JSON. ✍️ adds a note."),
            HelpCard("Change result — ✏️", "🔄 Reload re-runs in place with the saved model. 'Switch model / agent' re-runs the ranking against a different saved agent or provider+model — it previews the new ranking so you can Use (replace this row) or Discard it.")
        )
    ),
    "moderation_detail" to HelpContent(
        title = "Help - Moderation result — detail",
        cards = listOf(
            HelpCard("Overview", "Dedicated detail screen for a moderation result — the per-response policy classification of the report's answers. Errors render as a red Error block; blank content shows '(no content)'."),
            HelpCard("Classification table", "Parses the structured JSON ([{id, flagged, categories, scores}, ...]) the moderation flow produces (chat-prompt path or the native moderation API) into a table with 🚩 / ✓ flags, fired categories and the top scores, resolving each bracketed [N] to its real provider / model. Falls back to raw markdown when the model deviated from the schema."),
            HelpCard("Per-response detail", "Tap a row to drill into that response's full classification — every category (fired or not) with its score, plus the exact text that was moderated."),
            HelpCard("Title bar", "✏️ opens 'Change result', 👁 opens the View Moderation screen, ℹ️ jumps to Model Info for this row's (provider, model), 🐞 opens the captured trace when tracing was on, 🗑 deletes the moderation, and Copy / Share export the raw classification JSON. ✍️ adds a note."),
            HelpCard("Change result — ✏️", "🔄 Reload re-runs in place with the saved model. 'Switch model / agent' re-runs the classification against a different saved agent or provider+model — it previews the new result so you can Use (replace this row) or Discard it.")
        )
    ),
    "meta_detail" to HelpContent(
        title = "Help - Meta result — detail",
        cards = listOf(
            HelpCard("Overview", "Dedicated detail screen for a meta result that isn't a fan-out pair: a plain meta (Compare / Critique / Summarize / Synthesize / …) or a fan-in combined report. Renders the full content via ContentWithThinkSections; errors show a red Error block, blank content shows '(no content)'. Fan-out pairs / rerank / moderation rows still use the shared Secondary-detail screen."),
            HelpCard("Title bar — ✏️", "Opens 'Change result' — a list of ways to re-do this meta result: 🔄 Reload, ✏️ Edit prompt, 🗣️ Chat, 🌡️ Temperature sweep, 🧠 Reasoning Effort, 🧭 Web search, and 🤖 Switch model / agent. Each writes the chosen output back to this same row."),
            HelpCard("Switch model / agent", "Re-runs this result against a different saved agent (which brings its own model + parameter presets + system prompt) or a raw provider+model. The new output is previewed first so you can Use (replace this row, re-pointing it at the new model) or Discard it. Works on plain meta, fan-in, rerank and moderation."),
            HelpCard("Reload", "Re-runs in place with the row's saved prompt, model, parameters and language, replacing content, cost and tokens. A plain meta rebuilds from the report's answers (honouring its scope); a fan-in rebuilds from the current fan-out matrix (joining any still-running fan-out first)."),
            HelpCard("Edit prompt", "Edits the resolved meta prompt for one replay (optionally changing parameter presets and system prompt), runs it, and applies the chosen output."),
            HelpCard("Chat", "Opens a refine chat seeded with the report prompt + this result; applying a reply rewrites the content and tags it 'Changed by Chat'."),
            HelpCard("Sweeps & web search", "Temperature / Reasoning Effort run one-to-three variants to pick from; Web search re-runs once with web search enabled. Unsupported options report it on their screen. The applied variant tags a yellow 'Changed by …' badge on the detail."),
            HelpCard("Title bar — 💬", "Continues this analysis in the Chat section. Refining the result in place lives under ✏️ → Chat now."),
            HelpCard("Title bar — ℹ️ / 🐞 / 🗑 / 📋 / 📤 / ✍️ / 👁", "Model Info for this row's model; trace (when tracing is on and a match exists); delete (multi-language rows get the Active-language-only / All-languages popup); copy / share the shown content; add a note; jump to the matching View screen."),
            HelpCard("Languages", "When this meta has translations, the icon row swaps the shown content / trace / copy / share onto the picked language. The ↔ translation-compare opens when a per-language overlay is active.")
        )
    ),
    "model_switch" to HelpContent(
        title = "Help - Switch model / agent",
        cards = listOf(
            HelpCard("What it does", "Re-runs a secondary result (Meta / Fan-in / Rerank / Moderation) against a DIFFERENT model — keeping the same inputs (the report's answers, scope and language), only swapping who produces the result."),
            HelpCard("Agent or model", "Choose an agent to bring its own provider + model + parameter presets + system prompt; or choose a provider & model directly (which keeps this result's existing presets)."),
            HelpCard("Preview then apply", "The new run is shown as a preview with its cost, time and 🐞 trace. Use commits it onto this row (re-pointing the row at the new model and tagging a 'Model switch' badge); Discard throws it away and leaves the original untouched. The replaced run's spend is preserved in the report total either way."),
            HelpCard("Native rerank / moderation", "Picking a rerank- or moderation-capable model runs that result through the dedicated rerank / moderation API; a chat model runs the chat path. An incompatible pick surfaces the provider's error in the preview.")
        )
    ),
    "secondary_fan_out_l1" to HelpContent(
        title = "Help - Fan out — answerers",
        cards = listOf(
            HelpCard("Overview", "Top of the fan-out drilldown. Lists every answerer model on this fan-out run with its current status. Tap an answerer to step into its sources at L2."),
            HelpCard("Status icons", "Per-row: ✅ all pairs done, ❌ at least one errored, ⏳ at least one running, queued = no row on disk yet. Derived from latestByPair across all results."),
            HelpCard("Bench column", "The stats row splits Errors from Bench. A benched pair errored because its model is on a >1h rate-limit cooldown — it'll recover once the cooldown lifts, so it's counted apart from genuine errors. When benched pairs exist, a 'Remove benched' button appears next to 'Remove failed items' to clear just those (the two removes are complementary)."),
            HelpCard("Resume stale", "On open, any fan-out pair with no content / no error / not currently in flight is re-enqueued by the FanOutEngine (bounded retry, then marked failed) — survives app kill mid-batch."),
            HelpCard("Restart failed", "Re-runs only ❌ cells, leaving ✅ alone. Skips the placeholder grid rebuild — quick recovery without re-spending tokens on succeeded cells."),
            HelpCard("HTTP statistics", "After every pair is ✅ or ❌, the 📈 bottom-bar icon opens Fan out statistics. It groups final saved pair outcomes by answerer model and splits HTTP into 200, 429, 529, other 4xx, other 5xx, Other and No HTTP."),
            HelpCard("Combine reports", "When at least one fan-in prompt exists, the screen exposes 'Run combine reports' — fires a meta call against the fan-out matrix's results."),
            HelpCard("Per-answerer delete", "Drops every cell where this answerer participated. Fan-out list refresh tick bumps so the L1 list reflects the gap on pop-back."),
            HelpCard("Pitfalls", "Cell count is N×(N-1) for an N-agent run; cost grows fast. Watch the stats row's Costs figure before pressing Restart on large grids.")
        )
    ),
    "secondary_fan_out_stats" to HelpContent(
        title = "Help - Fan out statistics",
        cards = listOf(
            HelpCard("Overview", "Full-screen HTTP outcome table for the current Fan Out run. It appears from L1 only after every pair is terminal, so the counts match the completed batch."),
            HelpCard("Buckets", "200, 429 and 529 are exact buckets. 4xx excludes 429, 5xx excludes 529, Other covers any remaining status code, and No HTTP covers rows without a saved HTTP status."),
            HelpCard("What is counted", "Counts are the final persisted pair outcomes, not every retry attempt. Use traces or API statistics when you need per-attempt retry detail."),
            HelpCard("Sorting", "Rows are grouped by answerer provider+model and sorted by highest non-200 count first, then by model label.")
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
    "fan_meta_l1" to HelpContent(
        title = "Help - Fan Meta",
        cards = listOf(
            HelpCard("What you see", "Top of the Fan Meta drill-in — a separate screen from Fan out (responses). One worker call per pair (fan/meta, random pick + 429-fallback) returns BOTH a short title and a fitting icon. The stats row tracks the title batch (Total / Done / Error / Run / Wait / Queue / Costs); since the meta worker is a swarm, no Bench column is used and terminal failures count as Error."),
            HelpCard("Grouping", "The list groups by the answerer/report model. The 🐜 icon opens Fan Meta workers — the same pairs grouped by the meta-worker model that produced the title+icon. 'Show all' opens a flat list of every pair's title, grouped by source model."),
            HelpCard("Status & errors", "Pairs classify by their title-batch status (queued → running → done / error). 'Remove errors' clears failed pairs so they read as pending; 'Restart errors' clears and re-fires the batch on them. 'Remove failed' / 'Remove benched' clean up the underlying fan-out responses that can never get a title."),
            HelpCard("Navigation", "The 🗑 drops every title + icon for the run, keeping the fan-out responses. The 'Fan-Out' button cross-links back to the responses screen; system back closes to the report's secondary list. Tap a model row to drill into L2.")
        )
    ),
    "fan_meta_l2" to HelpContent(
        title = "Help - Fan Meta — model",
        cards = listOf(
            HelpCard("Overview", "One model's pairs as a focused icon + title list — no status glyphs or progress fills (that's the Fan out L2). Each row shows the pair's generated icon, its title, and the counterpart model label. Tap a row to open the pair's L3 detail."),
            HelpCard("Role toggle", "Responder = the active model received others' sources. Initiator = the active model's report fed into others. The role chip swaps the row list between the two views."),
            HelpCard("Meta models view", "Reached from the 🐜 Fan Meta workers screen, this variant is scoped to one meta-worker model and lists every pair it titled, with the answerer/report model under each title."),
            HelpCard("Title bar", "ℹ️ opens Model Info for the active pair; 🗑 deletes every fan-out cell where this model participated and pops back.")
        )
    ),
    "fan_meta_l3" to HelpContent(
        title = "Help - Fan Meta — pair",
        cards = listOf(
            HelpCard("Overview", "A single pair's metadata: the generated icon big and centred, the generated title below it in green, and the two model lines (the fan-out answerer model and the meta-worker model that produced the title+icon)."),
            HelpCard("Find alternatives", "'Find alternative icon' and 'Find alternative title' open the big model picker straight onto the per-pair Find-alt flow; a picked result lands here immediately."),
            HelpCard("Navigation", "Swipe right for the previous pair, left for the next — through the same L2-scoped list. The 🔄 reruns this pair; 🗑 drops the pair row (its cost stays counted in the report total). System back pops to L2.")
        )
    ),
    "fan_out_view" to HelpContent(
        title = "Help - Fan-out",
        cards = listOf(
            HelpCard("What you see", "Every fan-out reply in the run, laid out as a chat-style thread. Each answering model has its own header chip with the model name and a count of how many replies it produced; under that header sit the (initiator → answerer) exchanges as two bubbles per row — the initiator's original report response on one side, the answerer's reply on the other. Scroll vertically through the whole conversation."),
            HelpCard("How to read it", "Replies are grouped by answering model so you can see how a single model handled every initiator in one place. Long replies collapse to a preview line with a 'Read more' toggle — tap to expand, tap again to collapse. When you've picked a translated language on the parent View screen, the answerer's bubble shows the translated text with a small 🌍 badge; rows without a translation fall back to the original reply so the thread stays continuous.")
        )
    ),
    "ab_compare_view" to HelpContent(
        title = "Help - A/B compare",
        cards = listOf(
            HelpCard("What you see", "Two answers from this report side by side, each in its own independently scrolling column with a model picker above it. Reached from the \ud83c\udd9a A/B compare tile on Report - view (shown when the report has at least two successful answers)."),
            HelpCard("Switching models", "Tap either column's model name to pick a different answer for that side. The model already shown on the other side is greyed in the list but stays selectable."),
            HelpCard("What it is for", "Reading two long answers against each other without pager ping-pong. For machine judgment use the Tournament, Rerank or Compare-with-meta analyses instead.")
        )
    ),
    "costs_view" to HelpContent(
        title = "Help - Costs",
        cards = listOf(
            HelpCard("What you see", "What this report has cost so far. A big yellow 💰 Total at the top sums every API call this report has fired; below it, a row per raw cost type — the exact `<category>/<prompt>` key each call was stamped with (e.g. `report/prompt`, `meta/compare`, `workers/model-icons`, the `translate/…` family) — showing how much of the total went there. There's no friendly renaming or lumping into hand-picked buckets like 'Meta' or 'Icons' anymore; every distinct internal prompt (and the `report` rows) gets its own row."),
            HelpCard("How to read it", "Each row carries three things: the percentage of the grand total, the absolute amount, and a horizontal bar coloured yellow-to-orange whose length matches that share. A glance tells you whether one kind dominates spending or the cost is spread out. Rows that cost nothing are hidden entirely; very small ones still render a thin sliver so they don't disappear. The call count on the right tells you how many requests landed in that row. A Buckets ⇄ Models pill above the list re-rolls the same calls by model instead of by type; tapping any bar drills into that group's models (or vice versa), and a further tap opens the individual calls.")
        )
    ),
    "reports_view" to HelpContent(
        title = "Help - Model responses",
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
            HelpCard("How to read it", "The answer body renders headings, lists, tables, code blocks, and reasoning sections the same way the rest of the app does. Each meta tile carries its own icon — picking a new icon for one meta affects only that tile, never the others sharing the same name. When you've picked a translated language on the parent View screen, the answer card swaps to that language's translation when one exists; otherwise the original reply stays put."),
            HelpCard("💬 Chat with this result", "The speech-bubble button beside the model name opens a brand-new chat seeded from this meta. The original report prompt and every model response ride along as hidden context (the chat's system prompt); the meta reply you're reading becomes the assistant's first message. Ask follow-ups — 'why did you rate B higher than A?', 'summarise in three bullets' — instead of copy-pasting into a fresh chat. The session uses the model that produced the meta and is fully independent of the report: editing or deleting either one leaves the other untouched. When you're viewing a translated language, that translation seeds the chat.")
        )
    ),
    "secondary_scope" to HelpContent(
        title = "Help - Secondary scope",
        cards = listOf(
            HelpCard("Overview", "Inserted between a Meta-prompt button and the model picker. Picks which rows feed into the next run + (when relevant) which target languages."),
            HelpCard("All model responses", "Default. Uses every successful agent on the report (count shown in the sublabel)."),
            HelpCard("Only top ranked reports", "Available for meta / fan-out prompts when the report has at least one rerank row. Pick the rerank source from a dropdown and an N (1..total)."),
            HelpCard("Manual select models", "Tick exactly which agent rows to include. Defaults to every successful agent ticked, so it's a starting point you can prune."),
            HelpCard("Languages section", "Only shown for chat-type prompts when the report has translation rows. All languages = original + every translated; Select languages = pick a subset alongside the original."),
            HelpCard("Let models respond to their own answers", "Fan Out only. Off by default — a model is never paired with its own answer, so the matrix is N×(N−1). Turn it on to add one self-pair per model (each model also reacts to the answer it gave), making the run a full N×N. The call count on the next 'Fan Out - run' screen updates to match."),
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
            HelpCard("Previous prompts", "Every save records the superseded wording on the report, so the prompt has a revision timeline within this one report instead of earlier versions being lost. The 'Previous prompts' list below the editor shows each prior wording newest-first with the time it was replaced; tap one to load it back into the editor (then Update to re-run it — which itself records the text it replaced). The history lives on the report file, so it survives launches and backup/restore."),
            HelpCard("Saver scoping", "rememberSaveable is keyed on initialPrompt so re-opening the overlay with a fresh seed value doesn't restore a stale draft from the SaveableStateRegistry."),
            HelpCard("Pitfalls", "Editing the prompt alone doesn't re-run agents — the existing responses stay on screen until you Regenerate. Saving the identical text is a no-op and doesn't add a history entry."),
        )
    ),
    "report_picker" to HelpContent(
        title = "Help - Pick a report",
        cards = listOf(
            HelpCard("Overview", "Opened from the 🗂️ on the View hub. Lists your reports in the same five buckets as the AI Reports hub (Running, Problems, Pinned, Latest, Examples). Tap any row to open that report straight in View."),
            HelpCard("Cards", "Each card shows up to five at a glance; scroll inside a card for more. Empty buckets are greyed and sink to the bottom. Rows are title-only — no per-row icons here."),
            HelpCard("From a Manage screen", "The same 🗂️ also appears on several Manage screens (the Manage hub, Fan Out, Meta, Edit prompt, Edit short title). Opened from there, the list is FILTERED to reports relevant to that screen — e.g. from Fan Out only reports that have a fan-out — and picking one returns you to that same screen for the chosen report. The Examples bucket is hidden while a filter is active."),
        )
    ),
    "report_info" to HelpContent(
        title = "Help - Report information",
        cards = listOf(
            HelpCard("Overview", "A read-only summary of one report, opened from the ℹ️ icon on the Manage hub. Unlike the rest of Manage/View it is its own real screen, so Android-back returns to Manage."),
            HelpCard("Identity", "The report's icon, short title (list cards) and long title (top-bar line), plus the detected language + its emoji, and the report id."),
            HelpCard("Timing", "Created is a stable creation time; Last changed bumps on every edit. Status reflects whether the run completed (and how many models errored)."),
            HelpCard("Totals", "API calls, total API time (sums every recorded call duration — agents, secondaries, the icon chain and the metadata calls), total cost (the same figure as Report - costs), distinct models used, and token totals."),
            HelpCard("By model", "Per provider/model breakdown: how many calls each made and what they cost."),
        )
    ),
    "report_edit_short_title" to HelpContent(
        title = "Help - Edit short title",
        cards = listOf(
            HelpCard("Overview", "Rename the report's short title — the ≤25-char line on the AI Reports list cards. Titles are metadata only — no outbound API call references them, so this never sets hasPendingPromptChange and you don't need to regenerate to see the new title applied."),
            HelpCard("One field", "Just the short title. Update title is disabled when it trims to blank — the short title is the primary one (the long title's top-bar line falls back to it when blank)."),
            HelpCard("Find alternative short title", "Fans out to models you pick and lists candidate short titles (≤25 chars). Tapping a candidate drops it into the field; nothing is saved until you tap Update title."),
            HelpCard("🐞 Trace", "Opens the API trace from generating this report's short title ('report/title-short')."),
            HelpCard("Saver scoping", "rememberSaveable is keyed on the initial value so re-opening the overlay with a fresh seed doesn't restore a stale draft."),
        )
    ),
    "report_edit_long_title" to HelpContent(
        title = "Help - Edit long title",
        cards = listOf(
            HelpCard("Overview", "Rename the report's long title — the ≤50-char top-bar orange line on the View and Manage screens. Titles are metadata only — no outbound API call references them, so this never sets hasPendingPromptChange and you don't need to regenerate to see the new title applied."),
            HelpCard("One field", "Just the long title. You may leave it blank — the top-bar line then falls back to the short title (barTitle = long ?: short), so Update title stays enabled even when empty."),
            HelpCard("Find alternative long title", "Fans out to models you pick and lists candidate long titles (≤50 chars). Tapping a candidate drops it into the field; nothing is saved until you tap Update title."),
            HelpCard("🐞 Trace", "Opens the API trace from generating this report's long title ('report/title-long')."),
            HelpCard("Saver scoping", "rememberSaveable is keyed on the initial value so re-opening the overlay with a fresh seed doesn't restore a stale draft."),
        )
    ),
    "report_find_alt_prompt" to HelpContent(
        title = "Help - Edit prompt (Find alternative)",
        cards = listOf(
            HelpCard("Overview", "Every \"Find alternative …\" flow (titles, icons, translations) opens this step first, before you pick models. It shows the exact prompt that will be sent — with all @…@ markers already filled in from this report's data — so you can tweak the wording for just this run."),
            HelpCard("Markers replaced", "The underlying alt template uses placeholders like @PROMPT@, @RESPONSE@, @LANGUAGE@, @QUESTION@. They're substituted with the real values here, so what you read is what the models receive. Editing the filled-in data region is allowed but usually you only tune the instruction."),
            HelpCard("Next — pick models", "Tap Next to carry your edited prompt into the model picker; the fan-out then runs with your text. Back cancels the whole Find-alternative flow."),
            HelpCard("Saved back to the template", "When your edits can be cleanly re-applied (the markers' values are still found verbatim), the edited wording is saved onto the alt template, so the next Find-alternative starts from your version. If you edited inside a data region — or a value was empty — it can't be re-abstracted safely, so it's used for this run only and the template is left untouched."),
        )
    ),
    "report_export_sheet" to HelpContent(
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
    "report_fan_out_confirm" to HelpContent(
        title = "Help - Fan out — confirm run",
        cards = listOf(
            HelpCard("Overview", "Confirmation screen shown after the Fan out scope picker, before the runner kicks off. Lists exactly how many calls a Run will fire and which models are involved."),
            HelpCard("Counts grid", "initiators × responders = total calls. By default self-pairs are skipped, so the total is N×(N−1). When 'Let models respond to their own answers' was switched on in the scope step, self-pairs are included and the total becomes the full N×N."),
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
            HelpCard("Title bar", "The Android system back gesture returns you to the New AI Report selection phase.")
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
        title = "Help - Pick models",
        cards = listOf(
            HelpCard("Overview", "Full-screen model picker reached by +Model on the New AI Report selection phase — now multi-select like the Swarm member picker: tap as many models as you want (each dims as it's added) and Back returns with all of them. The same screen also backs the secondary-result launchers (Meta / Fan-out / Fan-in / Translate / Rerank), which stay single-select."),
            HelpCard("List", "Joins every active provider's catalog."),
            HelpCard("Provider filter", "Dropdown above the list — All Providers or one specific provider (count shown next to each name)."),
            HelpCard("Type filter", "When opened with a modelTypeFilter (RERANK / MODERATION / EMBEDDING / etc.), a checkbox '<Type> models only' is shown ON by default — untick to widen to the full catalog."),
            HelpCard("Search field", "Matches against provider id and model id. The count line above the list reads '<filtered> of <total> models'."),
            HelpCard("Recent section", "When the user has picked from any Report-section model picker before, the last 3 picks surface as a 'Recent' section above the main alphabetical list. Filters and search don't trim it — recents are a quick-access shortcut. Tapping a recent row also re-records it so the bump-to-front keeps ordering stable."),
            HelpCard("Already-added rows", "Rows passed in via alreadyAdded render at 0.4 alpha, are not clickable, and append ' · already added' next to capability badges."),
            HelpCard("Pricing column", "Per-token (×10⁶) prompt / completion, red for real data, grey badge for DEFAULT. Vision / Web / Reasoning badges sit before the price."),
            HelpCard("Tap to confirm", "On the New AI Report +Model picker (and the Find-icons / Translation model pickers) it's multi-select like the Swarm member picker (\"Pick models for swarm\"): tapping a row adds that (provider, model) and keeps the picker open so you can tap several in one visit (each dims as it's added); Back returns with all of them. The secondary-result launchers (Meta / Fan-out / Fan-in / Translate / Rerank) stay single-select — one tap confirms and closes."),
        )
    ),
    "report_swarm_info" to HelpContent(
        title = "Help - Swarm info",
        cards = listOf(
            HelpCard("Overview", "Per-swarm detail screen reached by tapping the ℹ️ icon next to a row on Pick a swarm. Lists every (provider, model) member of the swarm in member order."),
            HelpCard("Per-row content", "Provider id in blue on top, model id in white below. Capability badges (vision / web search / reasoning) only appear when the catalog reports the capability for this model. Per-million-token prompt / completion price pair on the right — red when real pricing data exists, grey badge when the row fell through to DEFAULT_PRICING."),
            HelpCard("Tap a row", "Opens Model Info for that (provider, model) — the same destination the title-bar ℹ️ icon reaches across the rest of the app. Use the Costs and Capabilities cards there to see every source's reading for this model."),
            HelpCard("Title bar", "Static \"Swarm\" with the swarm name as the dynamic subject (folds into the bar when the \"Subject to title bar\" setting is on, otherwise sits below as a green sub-header). The Android system back gesture returns to Pick a swarm with the previous filter intact."),
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
        title = "Help - Translation run — types",
        cards = listOf(
            HelpCard("Overview", "Level 1 of the translation run drill-in: the run grouped by translation type (the trace/cost category each item belongs to — the prompt, fan-out responses, Meta results, and so on). Tap a type to see its items. The 🐜 icon opens Translation workers — the same run grouped by the model that translated each item."),
            HelpCard("Throughput", "Every worker pulls from the shared queue as fast as its per-host caps allow — there's no cost-based hesitation or Speed/Mixed/Cost mode to pick anymore. Faster models naturally complete more items; a benched (rate-limit cooldown) model stops pulling and its share flows to the healthy workers."),
            // "429 / 529 handling" relocated to the "concepts" topic
            // (Help home → How it works) — same OkHttp retry behaviour
            // applies across every screen that fires a translation
            // call, not just this one.
            HelpCard("Stats panel", "Pinned at the top, kept visible even once the run is done: Total, Done, Errors, Run (in-flight), Wait (parked on a provider gate), Queue (items not yet picked up by any model), Costs (run total in cents, 2 decimals). Translation is a worker-pool batch, so no Bench column is used."),
            HelpCard("Per-type row", "Each row shows the type's item count, the type label (the `translate/…` category with its prefix stripped), and the type's cost. A green background fill = that type's done/total while work is in flight; once the run finishes the fill drops so it reads calmly. Sorted by size, then label."),
            HelpCard("Top progress bar", "Run-level (done + error) / total while there's still pending or running work. Hidden on a cancelled run."),
            HelpCard("Title bar", "👁 opens the matching View Translate screen; 🐜 opens Translation workers (per-model breakdown); 🔄 redoes every entry; 🐞 opens the trace list filtered to this run; 🗑 deletes the whole run behind a blocking 'Deleting…' popup.")
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
            HelpCard("Curation", "Not exhaustive. The translation prompt itself can be edited under Settings -> AI Setup -> Prompt management -> Internal prompts if you need a more specific dialect or style instruction."),
            HelpCard("Tips", "Search for native script directly works — typing '中文' jumps to Mandarin without remembering the English name."),
        )
    ),
    "content_model_response" to HelpContent(
        title = "Help - View responses",
        cards = listOf(
            HelpCard("Overview", "Reached from the View hub's Responses tile. Toggles between three sections for this report — Prompt, Costs, and View on one page (concatenates every agent's response onto a single scrollable page) — each with its own help page. There is no per-agent picker on this screen any more; to read one specific agent's response on its own screen with swipe-to-switch, tap that agent's row directly from Manage a report instead, which opens the separate Model response screen."),
            HelpCard("Loading state", "Reports are loaded on Dispatchers.IO via produceState — a Loading sentinel keeps the empty-state text from flashing while the JSON parse runs."),
            HelpCard("Language picker", "When the report has TRANSLATE rows, a row of language tabs (icon-based — the cached translation emoji per language, falling back to the language name) sits below the title bar. Selecting a non-Original tab overlays translated bodies onto the active section."),
            HelpCard("Pitfalls", "Citations, search-result blocks, and related-questions are not rendered here or on the Model response screen — only <think> collapsibles and GFM tables get structured treatment; everything else renders as plain markdown."),
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
            HelpCard("Overview", "Read-only cost view for the report — By type / By model / All calls, covering every API call counted against this report (agents + secondaries + translations). Reached from the result page's View → Costs button."),
            HelpCard("Tap a row", "Opens a popup with the full breakdown for that group or call — calls, in/out tokens, in/out cents, total, plus tier and duration on All-calls rows. Tap Close to dismiss."),
            HelpCard("By type — collapsible tree", "Grouped by the prefix before the `/` in each call's raw `<category>/<prompt>` type (report, meta, workers, alt, after, translate, …). Tap a prefix header to expand its member type rows underneath, each still tapping through to its own breakdown; both groups and members sort by cost, highest first — there's no per-column sort here."),
            HelpCard("By model — sortable columns", "Tap any column header to sort by that column. Tap the active column again to flip direction (▲ ascending / ▼ descending). Model shows only the part after the last `/` (no provider prefix); full provider/model is in the popup. Default sort is Total descending."),
            HelpCard("Totals", "The bold Total row at the end of By type sums every call in the report. If items were deleted with non-zero spend, an orange `deleted +X.XX ¢` line shows directly above it."),
            HelpCard("Translation costs", "Translation calls are billed against the same model that ran them — they group under the `translate` prefix in By type (e.g. `translate/text`, `translate/title`). The language picker is hidden in cost mode since costs aggregate every call."),
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
            HelpCard("Per-row delete", "Each row has a ✕ that opens a confirm dialog. Confirming removes the row locally and routes the disk delete through the report cleanup path."),
            HelpCard("Multi-select", "Long-press a row to enter selection mode: rows gain checkboxes and a header appears with All (select every visible report — the active search filter applies), Export (share the selected reports as one zip), Delete (confirm-gated bulk delete) and Done. Back also exits selection."),
            HelpCard("Title bar — 🗑", "Wired when allReports is non-empty. Confirm dialog shows the count; confirming routes each report through the report cleanup path and clears the local list."),
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
            HelpCard("Prompt editor", "OutlinedTextField (min 8 lines) seeded with `metaPrompt.text`. Edits are local — they don't write back to Settings → AI Setup → Prompt management → Internal prompts. If you want the changes to stick, copy them into the prompt definition by hand after the run."),
            HelpCard("Continue button", "Hoisted to the top of the page so it's reachable without scrolling past a long template. Passes a copy of the meta prompt with the edited text to the model picker; the original stays unchanged."),
            HelpCard("Variables", "Substitution placeholders (`@PROMPT@`, `@RESPONSE@`, `@NAME@`, `@TITLE@`, etc.) remain literal in the editor — they're resolved at call time by the engine. Don't expand them by hand."),
            HelpCard("Reached from", "Settings → AI Setup → Prompt management → Internal prompts → run a meta-category prompt, OR from a report's Manage screen → Meta/Create → pick a prompt → Continue (after the Scope screen).")
        )
    ),
    "report_runtime_prompt" to HelpContent(
        title = "Help - Run with prompt edit",
        cards = listOf(
            HelpCard("Overview", "Full-screen prompt editor shown before a second result runs, when the report's Runtime parameters toggle (Report - setup → Second result options) is on. It appears for the kinds that have no run screen of their own: Compare, Fan-in, Tournament, Judge-the-judges, Translate and Rank-the-translators."),
            HelpCard("This run only", "By default your edits apply to THIS run only — the saved Internal Prompt template (Settings → AI Setup → Prompt management) is left untouched. The next time you run the same kind it starts from the saved text again."),
            HelpCard("Run", "Runs the kind with your edited text, without saving it. For Tournament / Compare / Rank-the-translators a worker picker may follow (per the report's Worker batches mode); Translate continues to its worker picker too."),
            HelpCard("Update prompt & run", "Also writes the edited text back to the saved Internal Prompt (only the text changes — the prompt's workers and other settings are kept), then runs. Use this when you want the change to stick for future runs. Tournament and Judge-the-judges share one prompt, so updating from either changes both."),
            HelpCard("Translate — two fields", "Translate edits two prompts: the Body prompt (workers/translate-text) and the Title prompt (workers/translate-title). Each is run-only unless you tap Update prompt & run, which saves both."),
            HelpCard("Variables", "Substitution placeholders (e.g. `@LANGUAGE@`, `@TEXT@`, `@TITLE@`, `@QUESTION@`, `@RESULTS@`) stay literal in the editor — the engine resolves them at call time. Don't expand them by hand."),
            HelpCard("Reached from", "Turn on Report - setup → Second result options → Runtime parameters, then launch any of the six kinds from Report - second results. With the toggle off, those kinds run via their old confirm dialog / direct launch with no edit screen.")
        )
    ),
)
