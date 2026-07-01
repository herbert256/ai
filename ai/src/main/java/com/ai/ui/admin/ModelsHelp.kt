package com.ai.ui.admin

internal val modelsHelp: Map<String, HelpContent> = mapOf(
    "model_statistics" to HelpContent(
        title = "Help - Base models",
        cards = listOf(
            HelpCard("Overview", "AI Setup → Models → Base models. A table that collapses every model across all active providers down to its base name, so you can see at a glance which models are widely available and how many versions of each exist. Each of the three columns drills into a relevant sub-screen."),
            HelpCard("Model column", "The base model name — the model id with its provider/namespace prefix and its version information stripped. So z-ai/glm-5.2, z-ai/glm-5.1 and the bare glm-5.1 all fold to glm."),
            HelpCard("Versions column", "How many distinct versions of that base model exist across the catalog. The namespace prefix is ignored first, so z-ai/glm-5.1 and glm-5.1 count as one version; glm-5.2 + glm-5.1 → 2."),
            HelpCard("Providers column", "How many distinct active providers carry any version of that base model. OpenRouter + AtlasCloud + Z-AI all offering some glm → 3."),
            HelpCard("How the base name is found", "Two passes. Generic: the namespace before the last / is dropped, the name lower-cased, then split on - _ : and space; the first token is kept as the name root and following tokens are appended until the first version/size/variant token — one carrying a digit (5.2, k2.6, 70b, v4, r1, 2024) or a known variant word (instruct, chat, coder, vl, next, mini, thinking, …). So glm-5.2 → glm, kimi-k2.7-code → kimi, qwen3-coder → qwen3, llama-3.3-70b-instruct → llama. A dot is not a separator so 5.2 stays one token."),
            HelpCard("Hard-coded family rules", "Applied after the generic pass. (1) Vendor namespaces are stripped to the model: dot-joined Bedrock ones (mistral.ministral → ministral, nvidia.nemotron → nemotron, openai.gpt-oss → gpt) and dash-joined routing prefixes (parasail-qwen → qwen, parasail-gpt-oss → gpt). (2) Prefix list (wan, command, flux, gemini, claude, gpt, grok, sonar, qwen, glm, voyage, whisper, veo, titan, rerank): anything starting with it collapses to that word — claude-haiku-4-5 → claude, every qwen3 / qwen2.5 / qwen-image → qwen. (3) Segment list (glm, aya, nemotron): an id with that as a -/./_ segment collapses to it — zai-glm → glm, nvidia-nemotron → nemotron — while autoglm-phone is left alone. (4) OpenAI o1 / o3 / o4 → o-series. Edit VENDOR_NAMESPACES / DASH_NAMESPACES / PREFIX_RULES / SEGMENT_RULES in ModelStatisticsScreen.kt."),
            HelpCard("Drill down", "Tap the Model name → its versions (the grouping screen described below for big families); a small family — fewer than 15 versions — skips straight to the flat list of every concrete provider · model (tap a row → Model Info). Tap the Versions count → the distinct versions, each tappable to the providers offering it; when a family has more than 25 versions an extra grouping screen comes first — by version number where that's the distinguishing part (gpt → 5.2, 5.5, 4o) or by the model line when a number is embedded in the name (claude-4-opus / claude-opus-4 → opus; → sonnet, haiku). Deployment-region suffixes (@eastus2, @europe-west1) are ignored. Tap a group to see its versions. Tap the Providers count → the distinct providers, each tappable to the versions it carries. Leaf rows open Model Info. Back unwinds one drill level at a time."),
            HelpCard("Search", "The box at the top filters the rows — a row matches if the base name or any of its underlying model ids contains the text. So searching \"sonnet\" still finds the claude row, and \"embed\" surfaces every family with an embedding model."),
            HelpCard("Sorting", "Tap a column header to sort by it; tap the same header again to flip the arrow (↓ desc / ↑ asc). Opens on Versions ↓ (most versions first). Model sorts A→Z; ties on the count columns break alphabetically by name. The active header is highlighted."),
            HelpCard("Source", "Counts come from each active provider's current model list (the same list the model pickers use). A provider you haven't refreshed yet contributes only its default model — refresh it from its per-provider settings to fill in the catalog."),
        )
    ),
    "models_per_provider" to HelpContent(
        title = "Help - Provider — Models",
        cards = listOf(
            HelpCard("Overview", "Per-provider model list — reached either by tapping a provider row on the Models hub or via the Models card on Provider Edit; both land on this same screen. The all-providers Models hub is a different screen. Auto-saves both the model source and the models list as you go — no separate Save button."),
            HelpCard("Source picker", "API / Manual chips at the top. API mode pulls models from the provider's catalog endpoint; Manual mode lets you curate a fixed list by hand. Switching away from Manual clears any half-typed add/edit state."),
            HelpCard("Fetch Models", "API-mode only, disabled while a fetch is in flight. An inline error row beneath surfaces fetch failures with a 🐞 trace deep-link. Hidden when the provider's API key is blank — a hint pointing back to Providers takes its place."),
            HelpCard("Manual add / update form", "Single \"Model ID\" field plus Add or Update button. Rejects duplicates and ids containing whitespace (both flagged in red). Editing an existing model shows a Cancel button alongside; only Manual mode gets a per-row ✕ delete button — in API mode, refresh is the source of truth."),
            HelpCard("Test all models / Remove failed", "Test all models runs up to 5 concurrent probes (skipping test-excluded models), marking each row ⏳ → ✅/❌; a failed row with a captured trace is tappable. \"Remove N failed\" appears once a run finishes with failures and prunes them from the persisted list."),
            HelpCard("Per-row badges", "👁 vision / 🌐 web search / 🧠 reasoning, any cooldown/blocked/inaccessible advisory badge, and a colour-coded type chip (chat/embedding/rerank/image/tts/stt/moderation/classify) that reflects manual overrides instantly. Tapping a row opens that model's Model Info page."),
        )
    ),
    "model_info" to HelpContent(
        title = "Help - Model Info",
        cards = listOf(
            HelpCard("Overview", "Per-model dossier: name, recent usage, capability summary, layered cost breakdown, raw catalog data from twelve info-provider sources, and an opt-in \"introduce yourself\" call against the model itself."),
            HelpCard("Title bar & Provider card", "The subject line reads \"<provider> · <model>\" — there's no separate combined header card anymore. A trailing 🐞 shows in the title bar and opens the model-filtered trace list when traces exist (the old dedicated \"API Traces\" card is gone). The provider gets its own small \"Provider\" card further down the page (below Capabilities) — tap it to jump to that provider's edit screen."),
            HelpCard("Last usage card", "Last 10 hits across chat sessions, reports, and per-report secondaries (translate / meta / rerank / moderate). Each row links back to its source. A cumulative AI Usage row appears at the bottom when one-shot test calls or model refreshes bumped the counter without persisting a session."),
            HelpCard("Actions", "Start Chat (opens a fresh chat with this provider/model selected), Create Agent (opens AgentEditScreen pre-filled with provider/model/api key + a default name), and — only shown while the provider is active — Test, which fires one live call and prints a ✅/❌ pass/fail line underneath."),
            HelpCard("Model in AI configuration", "Card appears only when this (provider, model) shows up in at least one of five places — Model overrides, Cost overrides, Model cooldowns, Blocked models, Inaccessible models — each rendered as its own clickable row that deep-links straight into the matching CRUD entry."),
            HelpCard("Workers", "Card appears only when this (provider, model) is used by an Agent, or is pulled in by a Flock (through one of its agents) or a Swarm. Each match is a clickable row that jumps to that agent's / flock's / swarm's edit screen."),
            HelpCard("Sources buttons", "Five rows of catalog buttons covering all twelve info-provider sources — HuggingFace + OpenRouter; LiteLLM + models.dev; Helicone + llm-prices + Artificial Analysis; Requesty + llm-stats; genai-prices + TrueFoundry + CloudPrice. A source the user switched off in Info Providers is hidden rather than drawn red. Green when the source has data for this model, red when it doesn't. Tapping opens the pretty-printed JSON. \"Show all\" concatenates every enabled source into one dump."),
            HelpCard("Costs card", "$/M token rows for each tier that has data, in the same precedence order as PricingCache.getPricing. \"Per 1k searches\" sub-rows surface per-query rerank pricing when present. \"Add manual cost override\" jumps into the override CRUD pre-filled."),
            HelpCard("Capabilities card", "Vision, Web search, Thinking, optional PDF input, optional deprecation banner with a replacement model when the provider self-reports one, plus default temperature / stop sequences. Each row shows the resolution source: Pinned, Provider /models, LiteLLM, models.dev, or Auto-detected from name (last-resort)."),
            HelpCard("Usage card", "Titled just \"Usage\" — cumulative call count + input/output tokens + cost for this (provider, model). Shown only once at least one call has been logged; there's no empty-state placeholder, the card is simply absent until then."),
            HelpCard("AI Introduction", "Opt-in: \"Ask the model to introduce itself\" button uses the internal Model info prompt template + this (provider, model) and runs it through repository.analyzePlayerWithAgent. Result is cached in PromptCache so a previously-completed answer reappears immediately on revisit."),
            HelpCard("Tips", "OpenRouter id matching normalises '.' and '-' to handle anthropic/claude-opus-4.6 vs claude-opus-4-6 mismatches. HuggingFace lookups try every dash/dot variant of the candidate path and cache the result (including misses) for a week.")
        )
    ),
    "model_pick_provider" to HelpContent(
        title = "Help - Select Provider",
        cards = listOf(
            HelpCard("Overview", "Full-screen provider picker. Lists every AppService (or only active services when activeOnly is set). Tap to confirm; back exits without choosing."),
            HelpCard("State emoji", "Each row shows the display name plus a small state emoji: 🔑 ok, ❌ error, 💤 inactive, ⭕ untested."),
            HelpCard("Search", "Filters by display name. Result count line shows '<filtered> of <total> providers'."),
            HelpCard("Pitfalls", "Inactive providers are hidden when activeOnly is set (the chat / dual-chat flows). To pick from an inactive provider you have to activate it first in AI Setup."),
        )
    ),
    "model_pick_model" to HelpContent(
        title = "Help - Select Model",
        cards = listOf(
            HelpCard("Overview", "Full-screen model picker for a chosen provider. Pricing columns on the right (In $/M, Out $/M) read from settings overrides first, then PricingCache. Vision / web / reasoning badges sit between the model name and the price columns. Rows sort cheapest-output-price first; models with no resolved pricing (the DEFAULT sentinel) sink to the bottom, alphabetical name breaking ties."),
            HelpCard("Initial refresh", "For API-mode providers with onRefresh wired, the screen kicks a fetch on entry, waits up to 15 s for it to complete, then reveals the list. Stalled fetches unveil whatever was previously cached so you're never stuck staring at a spinner."),
            HelpCard("Default option", "When showDefaultOption is on (per-provider settings reuse), the list starts with a \"Default (use provider setting)\" row that selects the empty-string sentinel."),
            HelpCard("Open Models button", "Visible when onNavigateToProviderModels was wired (typically inside provider edit). Jumps into the rich Models browser with this provider preselected."),
            HelpCard("Fetch error row", "When the provider's last fetch failed, a red error line appears under the search box with a 🐞 link to the captured trace (when API tracing is on)."),
            HelpCard("Title-bar icons", "Help and Home only. Refresh is automatic; manual refresh is on the per-provider settings screen.")
        )
    ),
    "model_pick_agent" to HelpContent(
        title = "Help - Select Agent",
        cards = listOf(
            HelpCard("Overview", "Full-screen agent picker. Reads aiSettings.agents directly — every saved agent appears as a row."),
            HelpCard("Result rows", "Agent name + provider/model line + per-million-token pricing. Pricing badge is red on real source data, grey on DEFAULT_PRICING fallback."),
            HelpCard("Search", "Matches agent name, provider name, or effective model. Result count line shows '<filtered> of <total> agents'."),
            HelpCard("Empty state", "When no agents are configured yet, the body is empty. Add agents under AI Setup → Agents."),
            HelpCard("Title-bar icons", "Help and Home only.")
        )
    ),
    "model_raw" to HelpContent(
        title = "Help - Info provider (source detail)",
        cards = listOf(
            HelpCard("Overview", "Pretty-printed JSON view of one of the twelve info providers for a single model. Reached from the Sources buttons on Model Info."),
            HelpCard("Layout", "Title bar reads \"Info provider\". Below it, in green, the provider's display name (LiteLLM / OpenRouter / …) and beneath that the actual URL the app called. Then a single full-screen card containing the JSON, scrollable in both axes — long lines aren't wrapped or truncated."),
            HelpCard("JSON colouring", "Keys blue, strings green, numbers orange, true/false purple, null grey, punctuation white. Falls back to plain white for non-JSON inputs (e.g. a \"(no data)\" placeholder)."),
            HelpCard("Title bar — ℹ️", "Opens the help page for the info provider this view belongs to (e.g. tapping ℹ️ on the LiteLLM source detail opens the LiteLLM help topic). The same destination is also reachable by tapping the green provider name on the home Help page's Info-providers table."),
            HelpCard("Title bar — ❓", "Opens this page (help for the source-detail screen)."),
            HelpCard("Show all", "When opened from the Show all button on Model Info, the body concatenates every source's raw JSON with === Source === headers between sections. Title bar drops the green name + URL and shows the legacy \"All sources · model\" title; the ℹ️ icon is hidden because there's no single provider to point at."),
            HelpCard("Tips", "The JSON is pre-pretty-printed via createAppGson(prettyPrint = true). Field-name colouring relies on a string being followed by ':' after whitespace, which means escape sequences inside strings won't accidentally re-tokenise."),
        )
    ),
    "models" to HelpContent(
        title = "Help - Models",
        cards = listOf(
            HelpCard("Overview", "Top-level list of every active provider with a model count. Each row drills into that provider's per-provider model list (fetch / test / manual CRUD). Inactive providers are hidden — fix their key first."),
            HelpCard("Bulk refresh button", "\"Call all API retrieve models lists\" at the bottom — same path as Refresh → Models. forceRefresh=true bypasses the cache-validity check; an in-progress dialog shows live status."),
            HelpCard("Refresh results dialog", "After the bulk call finishes, a dialog reports per-provider counts (green) or \"failed\" (red)."),
            HelpCard("Per-provider screen", "API / Manual filter chips at top; API mode adds Fetch Models / Test all models buttons; Manual mode adds an inline Model ID add/edit form."),
            HelpCard("Test all models", "Visible after fetch when there are models. Spawns up to 5 concurrent tests via a Semaphore, gating in flight per row to ⏳ / ✅ / ❌. A failed ❌ links to its trace."),
            HelpCard("Remove failed", "Surfaces only after a Test all run completed with at least one ❌. Tap removes the failed list from the persisted models — the auto-save effect picks it up. Trace icons for those rows clear with them."),
            HelpCard("Per-row badges", "👁 vision / 🌐 web search / 🧠 reasoning + a colored type chip (chat/embedding/rerank/image/tts/stt/moderation/classify). The chip respects manual overrides instantly."),
            HelpCard("Pitfalls", "Manual lists are user-curated — Fetch Models is hidden in Manual mode. If you switch to Manual mid-edit, partial form state is wiped to avoid surprises on return."),
        )
    ),
    "model_types" to HelpContent(
        title = "Help - Model Types",
        cards = listOf(
            HelpCard("Overview", "Sets the default API path per ModelType — all 10 entries in ModelType.ALL (chat / responses / embedding / rerank / image / tts / stt / moderation / classify / ocr). Resolution at dispatch time: per-provider override → these defaults → ModelType.DEFAULT_PATHS hardcoded fallback."),
            HelpCard("Auto-save", "Editing a field pushes the trimmed map back through onSave — no Save button. Blank means \"use the hardcoded default\" for that type."),
            HelpCard("Field placeholder", "Each input shows the hardcoded fallback as a dim placeholder so you know what will run if you leave the field empty."),
            HelpCard("Tips", "Per-provider Type paths under Provider edit → Definition · API win over these — so use this screen for global defaults, the provider screen for exceptions."),
            HelpCard("Pitfalls", "If you blank a default that the hardcoded fallback also doesn't have, dispatch will throw at runtime — easiest case is if you typo a path and the underlying provider doesn't expose that type."),
        )
    ),
    "manual_model_types" to HelpContent(
        title = "Help - Manual model types",
        cards = listOf(
            HelpCard("Overview", "The direct-entry override form opened from Model Info → \"Add manual override\" — pre-filled with the tapped (provider, model) and its current heuristic flags. It edits the same (provider, model, type) override data as the full CRUD list under AI Setup → Manual model types overrides; an existing override for that pair is loaded for editing rather than duplicated."),
            HelpCard("Edit form", "Provider dropdown over all providers; Model dropdown over the provider's known models (placeholder \"No models — fetch this provider first\" when empty); Type filter chips (3 per row over all 10 ModelType.ALL entries); Capabilities checkboxes for vision / web search / reasoning."),
            HelpCard("Capability flags", "Wired into Settings.isVisionCapable / isWebSearchCapable / isReasoningCapable so a tick here surfaces the badge anywhere this model appears, even when the provider's auto-derived sets miss it."),
            HelpCard("Tips", "Pre-fills from the current model's heuristic flags so you usually only need to confirm or tweak the type, not re-enter everything."),
            HelpCard("Pitfalls", "Switching the provider in the editor wipes the model id (model lists are provider-scoped)."),
        )
    ),
)
