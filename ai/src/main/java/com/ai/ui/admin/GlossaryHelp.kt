package com.ai.ui.admin

internal val glossaryHelp: Map<String, HelpContent> = mapOf(
    "about" to HelpContent(
        title = "Help - About",
        cards = listOf(
            HelpCard("Overview", "Lightweight hub reached from the home screen's About card (or the bottom-bar app icon). Shows the AI logo, the installed version, the build date stamped into the APK at compile time, and a link to the GitHub source. Two cards underneath route to the bundled documentation: the end-user Manual and the developer Technical documentation."),
            HelpCard("Version + build + install times", "`Version <X.Y.Z>` is the `versionName` from the manifest (set in `ai/build.gradle`). `Built <stamp>` is read from the bundled `assets/build-timestamp.txt`, written on every Gradle build by the never-up-to-date `generateBuildStamp` task — always matches the moment the APK was produced (sidesteps the configuration-cache trap that made the old `BuildConfig.BUILD_TIMESTAMP` go stale). `Installed <stamp>` is `PackageInfo.lastUpdateTime` — the moment the APK was last written to the device. Both surface so you can tell the difference between 'old build still installed' and 'fresh install of a fresh build'."),
            HelpCard("GitHub link", "Opens `github.com/herbert256/ai` in the system browser. Public mirror of the source tree."),
            HelpCard("Manual card", "Opens the end-user Manual — a per-screen walkthrough of every feature, bundled at `assets/docs/manual/`."),
            HelpCard("Technical documentation card", "Opens the developer Technical documentation — architecture / data structures / build process / etc., bundled at `assets/docs/technical/`."),
            HelpCard("Copyright + licence", "GPL v2.0 — the LICENSE file at the repo root has the full text. Two footer lines pinned at the bottom of the page so the licence stays in front of every user."),
            HelpCard("Pitfalls", "The build timestamp moves on every assemble even for unchanged code (gradle stamps unconditionally). Use it for 'is the running APK the one I just built?' checks, not for change detection.")
        )
    ),
    "manual" to HelpContent(
        title = "Help - Manual",
        cards = listOf(
            HelpCard("Overview", "End-user walkthrough of every screen and feature, bundled in the APK at `assets/docs/manual/`. Mirrors what a printed quick-start would cover: how to send a report, what each screen does, where the controls live. Sibling hub to the Technical documentation for developer-oriented reference. No network — every page is read from disk."),
            HelpCard("Index", "The first page is a card-style table of contents grouped by use case (Getting started, Reports, Chat, Translation, Settings, etc.). Pages target users, not developers; concrete control names and label quotes match what's on-screen."),
            HelpCard("Navigation", "Tap a card on the index to open that page. The top \"Jump to\" panel anchor-links to each section inside a long page. The system back button walks the browser history first, then exits."),
            HelpCard("Authoritative source", "Hand-written; the code is the ultimate source of truth. When a screen and the manual disagree, the screen wins — open the page's underlying `.md` in the repo to file a fix."),
            HelpCard("Pitfalls", "Pages still marked \"Pass 1 stub\" are placeholders pending a rewrite pass — they link back to the original `doc/<name>.md`. JavaScript is disabled in this WebView; only static HTML + CSS renders.")
        )
    ),
    "technical_documentation" to HelpContent(
        title = "Help - Technical documentation",
        cards = listOf(
            HelpCard("Overview", "Developer-oriented hub over the project's technical documentation, bundled in the APK at `assets/docs/technical/`. Mobile-friendly rewrite of the `doc/` markdown tree: collapsible cards instead of wide tables, nested HTML trees instead of ASCII diagrams, dark theme matching the app. Sibling hub to the end-user Manual. No network — every page is read from disk."),
            HelpCard("Index", "The first page is a card-style table of contents. Sections: For developers (Architecture, Development, API formats, Data structures, Secondary results, Model test, Cooldowns, Help system, AppLog, Report icons, Throttle), Subsystem deep dives (Translation, Share target, Backup & restore), Reference data (Providers, Repositories, Persistent state), Backlog (TODO)."),
            HelpCard("Navigation", "Tap a card on the index to open that doc. Inside a page, the top \"Jump to\" panel anchor-links to each section. Long reference sections (per data class, per provider) are collapsed by default — tap to expand. The system back button walks the browser history first, then exits the screen."),
            HelpCard("Authoritative source", "Documentation is hand-written; the code is the ultimate source of truth. The bundled HTML is a snapshot — when in doubt, the file pointers at the bottom of `index` lead back to the live source files."),
            HelpCard("Pitfalls", "Pages still marked \"Pass 1 stub\" are placeholders pending the second rewrite pass — they link back to the original `doc/<name>.md` in the repo. JavaScript is disabled in this WebView; only static HTML + CSS renders.")
        )
    ),
    "help_home_icons" to HelpContent(
        title = "Help - Icons",
        cards = listOf(
            HelpCard("Overview", "Legend for every icon you'll see in the app's title bars, action rows and lists. The table below pairs each glyph with what it does and where it shows up. Reached from the Help home → \"Help - Icons\" link.")
        )
    ),
    "help_home_info_providers" to HelpContent(
        title = "Help - Info providers",
        cards = listOf(
            HelpCard("Overview", "External services the app fetches metadata from — model lists, pricing tiers, capability flags, free / spot pools. Tap a row in the table below to drill into that one provider's detail topic (where it's read, when it refreshes, fallback chain, …). Reached from the Help home → \"Help - Info providers\" link.")
        )
    ),
    "help_home_ai_providers" to HelpContent(
        title = "Help - Providers (cloud)",
        cards = listOf(
            HelpCard("Overview", "Every cloud LLM / embedder / reranker provider the app can talk to. Tap a row in the table below for that provider's setup notes — endpoint, auth shape, model-list freshness, anything that's worth knowing before pasting an API key. Reached from the Help home → \"Help - AI providers\" link.")
        )
    ),
    "concepts" to HelpContent(
        title = "Help - How it works",
        cards = listOf(
            HelpCard("What this page is", "Behaviours that span multiple screens. The per-screen ❓ help covers buttons / cards / fields on one screen; this page collects the things that quietly happen across the whole app — background sweeps, auto-reconcile, retry policy. Reach it from any per-screen help page's footer (\"How it works\") or from the Help home."),
            HelpCard("Stalled translation auto-reconcile", "Live translation rows (⏳ + 'done / total') auto-heal when their in-memory state has drifted from disk. Two triggers: (a) every report-open, the resume pass scans for any in-memory run flagged in-flight with no live dispatch job — those are demonstrably stuck (a cross-translate / restart-failed coroutine died before flipping the finished flag) and get rebuilt immediately; (b) a 10-second sanity poll on the result page catches rows where the done count already equals total but the row is still flagged in-flight. Either trigger silently rebuilds the in-memory state from the persisted SecondaryResult rows. The hourglass clears, the per-status counts on the run drill-in turn truthful, and any placeholder rows the rebuild surfaces show as Queue so you can decide whether to Restart them."),
            HelpCard("App-wide background resume sweep", "Once on app start and then every 30 seconds the app runs the same per-report stale-runs resume across every report modified in the last 7 days — translation placeholders that never converged, fan-out pairs the previous session abandoned, single-Meta calls left in PENDING. You don't have to open each report individually for its dropped work to come back. Logs land under the BgResumeSweep tag. Reports older than 7 days are skipped (out of the active-work window); a kill+restart catches them on next open."),
            HelpCard("429 / 529 handling", "Translation runs with ≤ 3 models retry rate-limited (429) or overloaded (529) calls inline on the same model up to 3 times (1 s backoff each, configurable in Settings → Network). Runs with > 3 models skip the inline retry: a 429 / 529 finalises immediately as a Failed attempt and the item is requeued for a different model — the cross-model bounce is faster than sleeping on the same one when alternatives are available. Long Retry-After (> 1 h) still benches the model on ModelCooldownStore either way."),
            HelpCard("Throughput", "In a multi-model translation run every worker pulls from the shared queue as fast as its per-host caps allow — no cost-based hesitation. Faster models naturally do more items; a benched (cooled-down) model stops pulling and its share flows to the healthy workers.")
        )
    ),
    "help_about" to HelpContent(
        title = "Help - About the app",
        cards = listOf(
            HelpCard("What this app is", "A multi-provider AI client for Android. The headline feature is the Report flow: one prompt fired at many models in parallel, every response rendered side-by-side, with token/cost stats per row and post-run operations such as Meta, Compare, Rerank, Tournament, Judge the Judges, Moderation, Translation, Fan-out, and Fan-in. Chat, Dual chat, Knowledge bases, local models, Monitor, import/export, and share-target ingest round out the app."),
            HelpCard("Who it's for", "Power users who want to compare what different models do with the same prompt, batch-process prompts at scale, or build their own meta-workflows on top of multi-model output. The app is opinionated towards the Report flow — it's the centre of gravity. If you only want a single-model ChatGPT-style conversation, this app is over-spec'd for that."),
            HelpCard("Multi-model Reports", "Pick a Flock, Swarm, or ad-hoc set of agents, paste a prompt, fire. Each agent's call runs in parallel up to the per-host throttle gates; results stream into the result page as they arrive. The completed Report is one JSON file on disk that survives app restarts, can be exported (HTML / PDF / DOCX / ODT / JSON traces), and is the input to every Meta operation."),
            HelpCard("Post-run operations", "After a Report finishes, you can chain operations over its results: Rerank, Compare/Summarize/Critique style Meta prompts, Compare with meta, Tournament, Judge the Judges, Moderation, Translation, Fan-out, and Fan-in. Outputs persist alongside the Report so you can revisit, export, translate, or regenerate them later."),
            HelpCard("Architecture at a glance", "Single Activity, Jetpack Compose UI, Kotlin coroutines for concurrency, Retrofit + OkHttp + custom interceptors for the network, and SharedPreferences plus JSON files under app-private storage for persistence. Cloud providers route through OpenAI-compatible, Anthropic, or Google dispatch paths. The synthetic Local provider routes to on-device MediaPipe runtimes instead of Retrofit."),
            HelpCard("Privacy stance", "Local-first by default. Your data lives on this device under the app's private storage; uninstalling removes it. Network traffic is limited to API calls you trigger, metadata/pricing refreshes you request, and APK/cloud-file operations you start. Local LLM, Local semantic search, and Local-embedder Knowledge work can run fully on device. No telemetry, no analytics, no phone home."),
            HelpCard("Open source + cost", "Source on GitHub (link from the About screen on Home). Licensed GPLv2. The app itself is free; you pay only for the API calls your configured providers bill you for."),
            HelpCard("Where to start", "Help - Getting started has the step-by-step. Short version: Settings → AI Setup → Providers (paste at least one API key) → Housekeeping → Refresh → Refresh all (verify keys + fetch model lists) → Hub → AI Reports → New report.")
        )
    ),
    "help_getting_started" to HelpContent(
        title = "Help - Getting started",
        cards = listOf(
            HelpCard("Step 1 — Add an API key", "Settings → AI Setup → Providers → pick a provider you have an account with (OpenAI / Anthropic / Google / OpenRouter are good starting points) → paste the key in the API Key card. The key stays on this device; it's only ever sent in the Authorization header to that one provider. Per-screen ❓ on the API Key card explains the key format."),
            HelpCard("Step 2 — Verify + fetch model lists", "Housekeeping → Refresh → Refresh all. This pings each configured provider to confirm the key works, then fetches their model catalog. Successful providers turn green; bad keys / wrong endpoints turn red with the error inline. You only need to refresh when you change providers or want to pick up newly-released models."),
            HelpCard("Step 3 — Create an Agent (optional but recommended)", "Settings → AI Setup → Workers → Agents → Add Agent → name it, pick a provider + model, optionally a parameter set + system prompt. An Agent is the saved combination the rest of the app composes — Reports, Chats, and Flocks can dispatch named Agents rather than raw provider/model pairs."),
            HelpCard("Step 4 — Fire your first Report", "Hub → AI Reports → +Agent / +Flock / +Swarm / +Model picks the model set; type a prompt below; Generate. The result page populates as each model returns. Subject row shows the running cost in cents. Once every row has settled, the action row exposes View / Edit / Regenerate / Export / Translate / Meta / Fan out."),
            HelpCard("Step 5 — Try a post-run operation", "On a finished Report, use Create/Meta/Tournament/Rerank/Moderation/Translate/Fan-out. Rerank produces a sorted table, chat-style Meta produces a synthesis card, Compare with meta scores answers against a selected meta result, and Tournament/Judge the Judges evaluate answer quality through worker models."),
            HelpCard("Step 6 — Translate the Report", "Action row → Translate → pick target languages and the model(s) to use. Multi-model translations parallelise the work and use the Speed / Mixed / Cost mode toggle to bias which model picks up what. See Help - Translations & multi-language for the workflow's specifics."),
            HelpCard("Common first-week pitfalls", "(1) Forgetting Refresh after adding a key → model lists empty. (2) Picking a tiny model for a long prompt → output truncates. (3) Hitting a 429 burst with a slow retry budget → bump Settings → Network → Max calls / minute for that provider. (4) Big translation runs feeling slow → the Cost mode hesitation is biasing toward cheaper models; flip the L1 toggle to Speed."),
            HelpCard("Help anywhere", "Every screen has its own ❓ icon in the title bar — opens the help page for that screen specifically. The Help home (this section's parent) collects cross-cutting topics; the search box at the top searches across everything.")
        )
    ),
    "help_glossary" to HelpContent(
        title = "Help - Concepts & glossary",
        cards = listOf(
            HelpCard("Overview", "The app's domain vocabulary, grouped into three buckets. Tap a category below; each lists the terms it covers with a one-paragraph explainer for each."),
            HelpCard("How the three buckets relate", "Building blocks are the atoms — Provider, Model, Agent. Groupings (Flock, Swarm) bundle Agents for one launch. Operations (Report, Chat, Meta, Fan-out, …) are the things you fire those agents at."),
            HelpCard("Why these names matter", "The UI uses the vocabulary literally. Settings → AI Setup → Workers edits Agents, Flocks, and Swarms; the Report result page lists response rows; the Create/Meta pickers choose operations and internal prompts. If a term feels alien, finding its glossary entry usually clears up whatever screen confused you.")
        )
    ),
    "help_glossary_blocks" to HelpContent(
        title = "Help - Concepts: Building blocks",
        cards = listOf(
            HelpCard("Provider", "A vendor or runtime the app can talk to. 42 cloud providers ship with the app, and custom providers can be added under Settings → AI Setup → Providers. The synthetic Local provider appears when on-device LLMs are installed. Each provider carries its API key/state, base URL or runtime identity, throttle caps, retry policy, endpoints, pricing hooks, and model catalog."),
            HelpCard("Model", "A specific weight / size at one provider — gpt-5-mini, claude-haiku-4-5, gemini-2.5-flash, qwen-2.5-7b-instruct, etc. Reachable by id under one Provider. Pricing per million input/output tokens, capability flags (vision, web search, reasoning), and context-length attach to the Model, not the Provider. The same model id at two different providers (OpenAI gpt-4o-mini vs OpenRouter openai/gpt-4o-mini) is two separate Models."),
            HelpCard("Agent", "A saved combination of provider, model, optional endpoint/API-key override, parameter presets, and optional system prompt. Reports, Chats, and Flocks can dispatch Agents instead of raw provider/model pairs. Settings → AI Setup → Workers → Agents edits the list."),
            HelpCard("Why Agent over raw pair", "Two reasons. (1) Parameter sets matter — a model called with temperature 0.2 + a strict system prompt behaves very differently from the same model with temperature 1.4 + no system prompt. The Agent freezes both. (2) When you change a model's parameters in one Agent, every Report that referenced it picks up the new behaviour on the next run — central knob, not a hundred per-Report tweaks.")
        )
    ),
    "help_glossary_groupings" to HelpContent(
        title = "Help - Concepts: Groupings",
        cards = listOf(
            HelpCard("Flock", "An ordered list of Agents you launch together as a unit. A Flock of 5 agents fired at a single prompt gives 5 parallel responses, one per Agent's configured provider/model/settings. Useful when you've curated a regular comparison group. Settings → AI Setup → Workers → Flocks edits the list."),
            HelpCard("Swarm", "A set of provider/model tuples that gets expanded at run time without creating named Agents. Useful when you want a broad model set from one or more providers without saving each as a worker first. Settings → AI Setup → Workers → Swarms edits the list."),
            HelpCard("Flock vs Swarm — when to use which", "Flock = saved Agents. Use when each Agent's parameter set / system prompt matters. Swarm = saved (provider, model) tuples. Use for breadth-first exploration — \"what does every OpenRouter Llama variant say to this prompt\" — where you don't care about per-model tuning. You can mix: a Flock of 3 hand-tuned Agents plus a Swarm dropped in via +Swarm on the new Report screen."),
            HelpCard("Practical limits", "Both are bounded by your throttle caps. A Swarm of 30 models all on the same host saturates the per-host concurrent gate; the runner will serialise them. For broad comparisons, mix providers so the per-host cap isn't the bottleneck.")
        )
    ),
    "help_glossary_operations" to HelpContent(
        title = "Help - Concepts: Operations",
        cards = listOf(
            HelpCard("Report", "The headline operation: one prompt, N models, every response rendered side-by-side. Each Report stores its prompt, the model list, every per-model response with token / cost stats, plus any meta-prompt outputs run against those responses. Lives on disk as one JSON file under <filesDir>/reports/. Survives restarts; doesn't expire; appears in History, on the result-page navigation, and in the Pin / Hub recent-reports surfaces."),
            HelpCard("Chat", "A two-way conversation with a single model. Lighter than a Report — no fan-out, no per-row comparison, no meta operations. The chat history lives on disk separately under <filesDir>/chats/. Useful for follow-up exchanges (\"now refactor that to use a sealed class\"). Reports stash an entry-point into Chat: the title bar's 💬 icon on a finished Report copies its prompt + a chosen response and launches a fresh chat."),
            HelpCard("Meta prompt", "A stored internal prompt template you apply to a Report's results. Chat-type meta prompts consume all selected results and produce one output; Compare-with-meta prompts score answers against a selected meta result. Edit them under Settings → AI Setup → Prompt management → Internal prompts."),
            HelpCard("Fan-out", "Run a chat meta prompt against every pair (or curated subset of pairs) of source agents. Produces N×M responses where each pair is one cell. Useful for cross-agent comparisons — \"how does Claude critique GPT's answer\" gives 1 cell per (Claude-as-evaluator, GPT-as-source) pair. Lives in the same SecondaryResult table; the Fan-out drill-in (L1/L2/L3) lets you inspect cells individually, restart failed ones, generate per-cell icons."),
            HelpCard("Fan-in", "Combines fan-out responses back into a smaller set of synthesis rows. It is useful after a large fan-out when you want each source model's received critiques summarized into a single readable result."),
            HelpCard("Rerank", "A meta operation that asks a model to assign a rank to each Report row, optionally with rationale. Output is a structured table — each cell carries an anchor link back to the agent card it scored, so tapping a Rerank row jumps to the original response. Useful for picking \"which of these 8 answers is best\"."),
            HelpCard("Tournament", "Pairwise head-to-head judging over report answers. Each unordered pair is judged in both directions to reduce position bias, then the View screen can aggregate the same match data through Copeland, Elo, Davidson, Tideman, or Markov rankings."),
            HelpCard("Judge the Judges", "Evaluates the judge models themselves by giving each judge the same random set of head-to-head pairs. The result highlights agreement, disagreement, and which judge workers are outliers."),
            HelpCard("Moderation", "Per-row content safety scoring against a provider's moderation endpoint (OpenAI / Mistral). Returns per-row category flags + scores. Useful for batch-screening generated text before pasting into a downstream system; also useful for self-audit if you're testing model outputs on contentious prompts."),
            HelpCard("Translation", "Translates a Report's title + prompt + every successful agent response + every chat-type meta result into one or more target languages. Multi-model translation runs use the Speed / Mixed / Cost mode toggle on the L1 — see Help - How it works for the hesitation mechanics. Translations live as TRANSLATE-kind SecondaryResult rows alongside the original; the language picker on the result viewer toggles which language renders."),
            HelpCard("Knowledge", "Knowledge bases index documents or web pages into chunks and embeddings, then let report/chat flows retrieve relevant chunks for retrieval-augmented prompts. Local embedders keep indexing on device; remote embedders use provider APIs."),
            HelpCard("Operation composition", "Operations chain. A Report's results feed Meta operations; a Translation's per-language renders include eligible Meta results too; Fan-out and Fan-in can build on report rows; Tournament and Compare results can be viewed, exported, and regenerated. The Report is the root; everything else hangs off it.")
        )
    ),
    "help_costs" to HelpContent(
        title = "Help - Costs & pricing",
        cards = listOf(
            HelpCard("How pricing is attributed", "Every API call carries token counts or usage units when the provider reports them. The app multiplies those by the (input, output) per-million-token prices of the provider/model pair and stores the USD cost on the call's record. The result-page subject row, per-Report cost table, Monitor → Statistics → Spend & usage, export cost tables, and per-call trace files all read from persisted cost numbers."),
            HelpCard("Pricing tier chain", "When the app needs to look up prices for a (provider, model) pair it walks a chain of sources in order: provider self-report (OpenRouter / Together when caller is them) → manual override → LiteLLM → models.dev → llm-prices → Artificial Analysis → OpenRouter cross-provider fallback → Helicone → DEFAULT (a low-cost placeholder so totals never NaN). The first hit wins. Sources are refreshed at user-controlled intervals; the cache survives restarts."),
            HelpCard("Manual overrides take precedence", "Settings → AI Setup → Costs → Add manual override. A manual override for a provider/model pair is consulted before curated catalog tiers — useful when an info-provider's catalogue is stale or wrong, when you've negotiated custom pricing, or when a brand-new model is not in public catalogs yet."),
            HelpCard("Where costs surface in the UI", "(1) Result-page subject row — running cost for the open Report. (2) Per-Report cost table on the Manage screen — every call grouped by type / model / agent / language. (3) Per-call trace file — opening 🐞 from any cell shows that call's cost inline. (4) Monitor → Statistics → Spend & usage — global aggregates across the device. (5) Export bundles — the same per-Report cost table in HTML / PDF / DOCX / ODT. (6) Translation/Fan-out/Tournament drill-ins — total and per-worker cost while runs progress."),
            HelpCard("Cost-aware translation mode", "Multi-model translation runs apply a cost-aware hesitation — cheaper models pull items first, expensive ones wait proportional to their price ratio. The L1 Speed / Mixed / Cost toggle controls how aggressive that bias is. Saved per-runId across restarts. See Help - How it works for the exact formula."),
            HelpCard("Audit trail", "Every priced API call leaves a persisted usage/cost entry when the call path reports usage. Spend & usage aggregates by provider, model, type, and pricing source so you can answer which provider/model/kind produced the spend."),
            HelpCard("Currency + decimals", "Everything is USD, rendered as cents with two decimals (e.g. \"3.42¢\"). Fractions below 0.01¢ round to 0 in displays but the underlying number on the call record is full-precision so totals don't drift from rounding."),
            HelpCard("Spend visibility before you commit", "The action-row Generate button shows the model count it will fire. For Fan-out and Translate, the confirmation dialogs show the call count before you commit so a 4-model × 200-row translation doesn't surprise you with 800 calls. Manual sanity check: bigger than expected? Hit Cancel.")
        )
    ),
    "help_privacy" to HelpContent(
        title = "Help - Privacy & data",
        cards = listOf(
            HelpCard("Local-first principle", "Everything the app generates — Reports, Chats, traces, logs, settings, agents, knowledge indexes, and local-model metadata — lives on this device under app-private storage. No account and no built-in cloud sync."),
            HelpCard("What leaves the device", "Cloud API requests to configured providers contain the prompts/content needed for that operation. Info providers see metadata/pricing/catalog queries when you refresh. Remote embedding Knowledge bases send extracted chunks to the selected embedding provider. Local LLM, Local semantic search, and Local-embedder Knowledge operations run on device after the model asset is installed."),
            HelpCard("API key handling", "Your API keys are stored under SharedPreferences on this device. They're sent only in the Authorization header (or query param for Google) to the matching provider. They never go anywhere else — not to the developer, not to a backend, not even to other configured providers. Switching apps doesn't expose them; uninstalling deletes them."),
            HelpCard("Telemetry — none", "No crash reporting, no analytics SDK, no \"phone home\". The app does no network beyond what you explicitly trigger. You can verify this by capturing traffic from the device or by leaving it offline and watching nothing happen."),
            HelpCard("Logs and traces", "AppLog files live under <filesDir>/applog/ and per-call API traces under <filesDir>/trace/. They can include prompts, responses, endpoints, models, costs, and error payloads. They never leave the device unless you explicitly share/export them."),
            HelpCard("Data wipe paths", "(1) Per-Report — delete from report lists or Manage. (2) Per-Chat — delete from the chat list. (3) Per-API-key — Settings → AI Setup → Providers → provider → clear the key. (4) Runtime/configuration/application reset — Housekeeping → Reset. (5) Uninstall the app — Android removes app-private storage."),
            HelpCard("Backup file = the only off-device copy", "A Backup zip you create from Housekeeping → Backup & Restore is the only app-created whole-data artefact outside this device. Treat it like API keys and private reports. Large local model bundles are excluded and must be reinstalled/imported separately."),
            HelpCard("Sandbox guarantees", "Android's per-app sandbox means no other app on this device can read <filesDir>/. The app uses MODE_PRIVATE for every SharedPreferences file. Even on a rooted device the app's files require root + a UID match to read."),
            HelpCard("Pitfalls", "Sharing a Backup zip via a non-private channel (public Drive folder, email forwarded) exposes API keys + every Report. If you do need to share data with someone, export the specific Report's HTML / PDF instead — those don't include keys.")
        )
    ),
    "help_backup" to HelpContent(
        title = "Help - Backup & restore",
        cards = listOf(
            HelpCard("What's in a backup", "Tracked SharedPreferences plus the app filesDir/cacheDir contents: reports, chats, secondary results, API traces, AppLog files, knowledge bases/chunks/embeddings, prompt cache, icon cache, settings, workers, prompts, parameters, model states, pricing caches, and exports/staging data. Large local model directories are excluded."),
            HelpCard("Backup flow", "Housekeeping → Backup & Restore → Backup. The app streams a zip through Android's Storage Access Framework into the location you choose, so Drive, Files, Dropbox, OneDrive, and local folders all use the system picker."),
            HelpCard("Restore flow", "Housekeeping → Backup & Restore → Restore. The app copies the chosen zip to a temp file, checks the manifest, validates every entry before touching current data, then writes the staged entries. A successful restore asks for an app restart so in-memory caches reload from disk."),
            HelpCard("Version compatibility", "Backups carry a manifest version. The app currently writes version 1 and refuses backups from a newer manifest version. Missing or unrecognizable manifests are rejected before any current data is cleared."),
            HelpCard("Pitfalls", "(1) Don't restore while reports, translations, fan-out, tests, or local indexing jobs are in flight. (2) Backup files can contain API keys and private content. (3) Local LLM and LiteRT model bundles are intentionally excluded and need to be reinstalled or re-imported."),
            HelpCard("Format changes", "The manifest version is the compatibility gate. It should only change when the backup layout changes in a way older code cannot safely read. doc/backup-restore.md tracks the current format and compatibility notes."),
            HelpCard("Verifying a backup", "Quick smoke test: after a backup, install a fresh copy of the app on a second device, restore the zip there, confirm reports, settings, workers, knowledge bases, traces, and chats look right. Then reinstall any local model assets you need.")
        )
    ),
    "help_translations" to HelpContent(
        title = "Help - Translations & multi-language",
        cards = listOf(
            HelpCard("What gets translated", "Title + prompt + every successful agent response + eligible chat-type secondary text on the Report. Structured tables such as rerank, moderation, tournament, judges, compare grids, fan-out structure, and cost tables are not translated as tables. The original Report stays untouched; translations live as TRANSLATE-kind SecondaryResult rows."),
            HelpCard("Picking a target language", "Action row → Translate → language picker. The curated picker covers 50+ common languages and filters by English or native name. Multiple languages per launch are supported — a single Translate fires target-language × item work."),
            HelpCard("Single- vs multi-model", "Pick one model at the Translate model picker → every item runs on that model in a single queue. Pick several → the work is shared across them via a single shared queue. Multi-model is faster end-to-end (parallelism) but spreads cost across the chosen set. The L1 drill-in shows per-model rows so you can see who's doing what."),
            HelpCard("Throughput", "Every model in a multi-model run pulls from the shared queue as fast as its per-host caps allow — no cost-based bias. Faster models naturally complete more items; a benched (rate-limit cooldown) model stops pulling and its share flows to the healthy workers."),
            HelpCard("Restart failed", "When a multi-model run has errored rows, Restart failed reassigns each one to a different model than the one that failed — round-robin over the remaining models. On a single-model run, the row retries on the same model (no alternatives). Benched rows (rate-limit cooldown) auto-recover when the cooldown lifts and don't need a manual Restart."),
            HelpCard("Self-healing background", "Translation runs that get interrupted by an app restart, a coroutine cancellation, or a mid-flight kill are recovered by two coordinated paths: a 10-second sanity poll on the result page (catches stalled rows where done == total but !isFinished) and a 30-second app-wide background sweep across reports modified in the last 7 days (catches stalled rows in reports you haven't opened recently). Either path auto-reconciles the state and re-dispatches placeholders — see Help - How it works."),
            HelpCard("Cross-translate after a fan-out", "When a fan-out adds new meta rows after a translation has already run, the existing translation run automatically dispatches translations for the new rows too (addCrossTranslationItems). You don't need to re-launch the translation; the new content surfaces in the existing language picker."),
            HelpCard("Cost notes", "Translation is per-row: 1 Report with 5 agents + 3 meta results = 9 source items × N languages × M models worth of API calls. Use the Translate confirm dialog's count preview to avoid surprises. Pick only cheap models at the model picker to keep total spend low."),
            HelpCard("Limitations", "(1) Rerank tables aren't translated (they're structured ranks, not prose). (2) JSON traces aren't translated. (3) Source-content updates after a translation are not auto-retranslated — restart the translation. (4) Translations don't carry their own cost-override store; they use the same pricing as any other call.")
        )
    ),
)
