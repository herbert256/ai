package com.ai.ui.admin

internal val modelsHelp: Map<String, HelpContent> = mapOf(
    "models_per_provider" to HelpContent(
        title = "Help - Provider — Models",
        cards = listOf(
            HelpCard("Overview", "Per-provider model list for one provider. Reached from AI Setup → Providers → <provider> → Models. The all-providers Models hub is a different screen."),
            HelpCard("Source picker", "API / Manual chips at the top. API mode pulls models from the provider's catalog endpoint; Manual mode lets you paste / curate a fixed list (one model id per line)."),
            HelpCard("API mode list", "Shows what the last Fetch returned — the same list every model picker uses. Models known to be stale (LiteLLM has fresher metadata) get a tiny badge."),
            HelpCard("Manual mode editor", "Add lines from the multi-line input + Add button; tap a row to drop it back into the editor for tweaking."),
            HelpCard("Auto-save", "Edits land via the Settings save lambda as you go — no separate Save button. The screen drops local mirror state when switching modes so half-typed values don't stick."),
        )
    ),
    "models_search" to HelpContent(
        title = "Help - Models",
        cards = listOf(
            HelpCard("Overview", "Aggregated browser across every active provider's model list. Used for catalog inspection and as a one-shot picker for Swarm edits and the configure-on-the-fly chat entry."),
            HelpCard("Type + Provider dropdowns", "Type filters by ModelType (chat, embedding, reranker, moderation, etc.). Provider filters to one of the active services with a (count) suffix per row. \"All …\" highlighted in blue when nothing is selected."),
            HelpCard("Min context dropdown", "Tiers: Any, ≥ 8k, 32k, 128k, 200k, 1M. Lookup goes provider /models first, then models.dev. Models with no context length anywhere are dropped from the list (they can't be qualified)."),
            HelpCard("Capability chips", "👁 Vision, 🌐 Web search, 💲 Has pricing (real source, not the 25/75 default), 🎁 Free only (real source + 0 input + 0 output), ⚠ Default 25/75 (no real pricing — useful for finding entries that need a curated source), ⚡ Conflicting pricing (2+ catalog tiers disagree by more than 1%)."),
            HelpCard("Search box", "Plain substring across provider name + model name. Case-insensitive. Combines with every other filter."),
            HelpCard("Result rows", "Model name with badges (vision, web, reasoning), provider name in blue, and the per-million-token pricing in red (real source) or grey (default tier)."),
            HelpCard("Tap behaviour", "Default mode: navigate to Model Info for that pair. Pick mode (when caller passed onPickModel): the click invokes the callback and dismisses — title becomes \"Select Model\" and Model Info is bypassed."),
            HelpCard("Title-bar icons", "Help and Home only. Loading spinner inline shows when at least one provider is mid-fetch (loadingModelsFor non-empty)."),
            HelpCard("Tips", "Filters are rememberSaveable, so flipping into a model and back doesn't reset them. Use ⚠ Default 25/75 + a known-large provider as a quick way to find catalog gaps to file."),
            HelpCard("Pitfalls", "Models live on Settings.models per-provider — a provider that hasn't been refreshed yet shows only its default model. Refresh per-provider from the per-provider settings screen.")
        )
    ),
    "model_info" to HelpContent(
        title = "Help - Model Info",
        cards = listOf(
            HelpCard("Overview", "Per-model dossier: name, recent usage, capability summary, layered cost breakdown, raw catalog data from seven sources, and an opt-in \"introduce yourself\" call against the model itself."),
            HelpCard("Header card", "Model name + provider; trailing 🐞 (when traces exist for this model) opens the trace list filtered to it. Provider name itself is clickable and jumps to the per-provider edit screen."),
            HelpCard("Last usage card", "Last 10 hits across chat sessions, reports, and per-report secondaries (translate / meta / rerank / moderate). Each row links back to its source. A cumulative AI Usage row appears at the bottom when one-shot test calls or model refreshes bumped the counter without persisting a session."),
            HelpCard("Actions", "Start AI Chat (opens a fresh chat with this provider/model selected) and Create AI Agent (opens AgentEditScreen pre-filled with provider/model/api key + a default name)."),
            HelpCard("Sources buttons", "Two rows of catalog buttons — HuggingFace, OpenRouter, LiteLLM, models.dev, then Helicone, llm-prices, Artificial Analysis. Green when the source has data for this model, red when it doesn't. Tapping opens the pretty-printed JSON. \"Show all\" concatenates every source into one dump."),
            HelpCard("Costs card", "$/M token rows for each tier that has data, in the same precedence order as PricingCache.getPricing. \"Per 1k searches\" sub-rows surface per-query rerank pricing when present. \"Add manual cost override\" jumps into the override CRUD pre-filled."),
            HelpCard("Capabilities card", "Vision, Web search, Thinking, optional PDF input, optional deprecation banner with a replacement model when the provider self-reports one, plus default temperature / stop sequences. Each row shows the resolution source: Pinned, Provider /models, LiteLLM, models.dev, or Auto-detected from name (last-resort)."),
            HelpCard("API Traces card", "Total trace count for this model — clickable when non-zero, opens the model-filtered trace list."),
            HelpCard("AI Usage card", "Cumulative call count + input/output tokens + cost. Empty placeholder when the counter is still zero."),
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
            HelpCard("Overview", "Full-screen model picker for a chosen provider. Pricing columns on the right (In $/M, Out $/M) read from settings overrides first, then PricingCache. Vision / web / reasoning badges sit between the model name and the price columns."),
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
            HelpCard("Overview", "Pretty-printed JSON view of one of the seven info providers for a single model. Reached from the Sources buttons on Model Info."),
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
    "model_edit" to HelpContent(
        title = "Help - Per-provider models",
        cards = listOf(
            HelpCard("Overview", "Per-provider model list (the screen you reach by tapping a provider on Models). Same screen also opens via the Models card on Provider Edit. Auto-saves both modelSource and the models list."),
            HelpCard("API / Manual chips", "Switch model-source at the top. Auto-save fires whenever you flip — and switching away from Manual clears any half-typed add/edit state."),
            HelpCard("Fetch Models", "API-mode only. Disabled while a fetch is in flight. Inline error row beneath surfaces fetch errors with a 🐞 trace deep-link."),
            HelpCard("Manual add / update form", "Single \"Model ID\" field plus Add or Update button. Duplicate detection in red. Editing an existing model shows a Cancel button alongside."),
            HelpCard("Per-row test status", "After Test all models: ⏳ rotating, ✅ green, ❌ red. ❌ rows with a captured trace are tappable and jump to the trace."),
            HelpCard("Type chips & badges", "Per row — color-coded type pill (Blue=chat, Purple=embedding, Indigo=rerank, Orange=image, Green=tts/stt, Red=moderation/classify) and capability emoji."),
            HelpCard("Tips", "manual mode shows an ✕ delete button on each row; API mode does not — there refresh is the source of truth."),
            HelpCard("Pitfalls", "If the API key is blank in API mode, the action row replaces itself with a hint pointing back to Providers.")
        )
    ),
    "model_types" to HelpContent(
        title = "Help - Model Types",
        cards = listOf(
            HelpCard("Overview", "Sets the default API path per ModelType (chat / embedding / rerank / image / tts / stt / moderation / classify). Resolution at dispatch time: per-provider override → these defaults → ModelType.DEFAULT_PATHS hardcoded fallback."),
            HelpCard("Auto-save", "Editing a field pushes the trimmed map back through onSave — no Save button. Blank means \"use the hardcoded default\" for that type."),
            HelpCard("Field placeholder", "Each input shows the hardcoded fallback as a dim placeholder so you know what will run if you leave the field empty."),
            HelpCard("Tips", "Per-provider Type paths under Provider edit → Definition · API win over these — so use this screen for global defaults, the provider screen for exceptions."),
            HelpCard("Pitfalls", "If you blank a default that the hardcoded fallback also doesn't have, dispatch will throw at runtime — easiest case is if you typo a path and the underlying provider doesn't expose that type."),
        )
    ),
    "manual_model_types_list" to HelpContent(
        title = "Help - Manual model types",
        cards = listOf(
            HelpCard("Overview", "List of manual (provider, model) → type overrides. The app normally derives a model's type (CHAT / RERANK / MODERATION / EMBEDDING / etc.) from the catalog; an override here pins it to a specific type when the catalog is wrong or missing."),
            HelpCard("Add Override", "Top button opens the editor with a blank override."),
            HelpCard("Row tap", "Opens the editor for the override."),
            HelpCard("Row subtitle", "Provider · model · type."),
            HelpCard("Empty state", "No overrides yet — tap Add Override to create the first one."),
        )
    ),
    "manual_model_types" to HelpContent(
        title = "Help - Manual model types",
        cards = listOf(
            HelpCard("Overview", "CRUD list of (provider, model, type) triples that win over the autodetected type stored on ProviderConfig.modelTypes. Useful when the heuristic and native list APIs both miss — e.g. an embedding model whose name doesn't contain \"embed\"."),
            HelpCard("Item rows", "\"<providerId> / <modelId>\" with \"→ <type>\" plus capability flags (👁 / 🌐 / 🧠) on the second line. Tap to edit, trash to delete with confirmation."),
            HelpCard("Add override", "Top button switches to the editor with the form blank."),
            HelpCard("Edit form", "Provider dropdown over all providers; Model dropdown over the provider's known models (placeholder \"No models — fetch this provider first\" when empty); Type filter chips (3 cols × 3 rows over the 9 ModelType.ALL); Capabilities checkboxes for vision / web search / reasoning."),
            HelpCard("Capability flags", "Wired into Settings.isVisionCapable / isWebSearchCapable / isReasoningCapable so a tick here surfaces the badge anywhere this model appears, even when the provider's auto-derived sets miss it."),
            HelpCard("Tips", "Reachable from Model Info → \"Add manual override\" too — it pre-fills the form with the current model's heuristic flags so you only confirm or change them."),
            HelpCard("Pitfalls", "Switching the provider in the editor wipes the model id (model lists are provider-scoped)."),
        )
    ),
    "model_cooldowns_list" to HelpContent(
        title = "Help - Model cooldowns",
        cards = listOf(
            HelpCard("Overview", "List of (provider, model) pairs that are temporarily benched. A model lands here automatically when a Google call returns a 429 with a retry hint longer than 1 hour (exhausted daily quota). While benched, the model is grayed out and non-selectable in every model picker, and the report / fan-out / translation dispatchers skip it — the in-flight item is removed rather than left as a red error."),
            HelpCard("Add cooldown", "Top button opens the editor blank — lets you manually bench a model (e.g. you know you've hit a limit) by picking provider + model + a duration in hours."),
            HelpCard("Row tap", "Opens the editor to change how long the cooldown lasts."),
            HelpCard("Row subtitle", "\"rate-limited · back <time>\" while active, or \"expired — tap ✕ to clear\" once the cooldown time has passed (expired rows are also pruned automatically the next time the model is checked)."),
            HelpCard("Delete / Clear all", "The trash icon un-benches one model (with confirmation). \"Clear all\" wipes every cooldown at once."),
            HelpCard("Persistence", "Cooldowns survive app restarts, ride along in Export/Import (\"Model cooldowns\" row) and in the full Backup/Restore."),
        )
    ),
    "model_cooldowns" to HelpContent(
        title = "Help - Model cooldowns",
        cards = listOf(
            HelpCard("Overview", "Editor for a single model cooldown. Add mode lets you pick any provider + model; Edit mode locks the provider/model (they're the entry's key) and only lets you change the duration."),
            HelpCard("Provider / Model", "Dropdowns over all providers and the chosen provider's known models. Switching the provider clears the model. Both are read-only when editing an existing cooldown."),
            HelpCard("Available again in (hours)", "How long from now the model stays benched. Saving stores \"now + hours\". A new entry defaults to 24h; editing prefills the hours still remaining."),
            HelpCard("Tips", "Entries normally appear here on their own from the >1h-429 detector — manual add is for when you want to pre-emptively bench a model."),
        )
    ),
)
