package com.ai.ui.admin

/** Help content for every per-screen ❓ topic in the app. Pure data
 *  so the HelpScreen rendering layer stays compact — HelpScreen.kt
 *  loads the right [HelpContent] for the topic id and walks its
 *  [HelpCard] list through [HelpSection].
 *
 *  Both data classes are `internal` so the rendering composables in
 *  the same package can use them directly, but they stay invisible
 *  to other packages — outside callers go through [HelpScreen]. */
internal data class HelpCard(val title: String, val body: String)
internal data class HelpContent(val title: String, val cards: List<HelpCard>)

internal val HELP_TOPICS: Map<String, HelpContent> = mapOf(
    // ===== Per-card help on the provider settings screen =====
    "provider_card_state" to HelpContent(
        title = "Provider state",
        cards = listOf(
            HelpCard("What the four states mean", "🔑 ok — a working API key has been tested against the picked Default Model. Pickers, Refresh All, the active-only filter all treat this provider as usable.\n❌ error — the last test failed; the trace icon links to the captured request/response.\n💤 inactive — the user explicitly turned the provider off. Calls and pickers skip it.\n⭕ not-used — no key set yet."),
            HelpCard("Activation flow (Switch ON)", "Flipping the inactive Switch to OFF kicks off two network calls in sequence: 1) a fetch of the live model list from the provider's /models endpoint, 2) a test call against the picked Default Model using the API key from the card below. Both must pass before the state goes 🔑."),
            HelpCard("On success", "State flips to 🔑. A default agent named after the provider is auto-created (and added to the 'default agents' Flock) so the report flow has something to dispatch to without the user opening the Agents screen."),
            HelpCard("On failure", "State goes ❌. The captured trace from the failed call is one tap away via the bug icon next to the test result. Fix the issue (key, model id, network) and either tap Test on the API Key card or flip the Switch off+on to retry."),
            HelpCard("Switch OFF (deactivate)", "Goes straight to 💤 — no network call. Reverses by flipping back on, which re-runs the activation flow.")
        )
    ),
    "provider_card_apikey" to HelpContent(
        title = "API Key card",
        cards = listOf(
            HelpCard("Where it's stored", "Per-provider SharedPreferences slot named `<id>_api_key` (e.g. `OpenAI_api_key`). Masked behind the eye toggle on the input field — never logged in API traces, never shipped through any export bundle marked 'no keys'."),
            HelpCard("Test button", "Fires one round-trip request against the model picked below using this key. Shows 'Connection successful' green on pass, the provider error message red on fail. Tagged with a per-test trace so the bug icon next to a failure links straight to the captured request / response."),
            HelpCard("State coupling", "A successful Test atomically flips the provider's state to 🔑 AND adds the default agent (named after the provider) to the 'default agents' Flock. A failed Test flips state to ❌ — same as the activation Switch."),
            HelpCard("Models row", "Opens the dedicated per-provider Models screen — live API list when the Models card → Default source is API; the manual / hardcoded subset when it's MANUAL. The number to the right is the count currently in the catalog."),
            HelpCard("Default Model row", "Writes the picked model into AppService.defaultModel — the single source of truth for what every API call uses by default. Sorted by output-token cost ascending so the cheap end of the catalog lands on top; pricing is rendered on the picker rows and is provider-aware (live → LiteLLM → models.dev → llm-prices → Artificial Analysis → manual override → OpenRouter cross-provider → Helicone → DEFAULT).")
        )
    ),
    "provider_card_basics" to HelpContent(
        title = "Basics",
        cards = listOf(
            HelpCard("Base URL", "Root of every API call to this provider. Paths declared on the API card (`v1/chat/completions`, `v1/messages`, etc.) append to this. Should end with a slash; the dispatcher normalises both shapes."),
            HelpCard("Admin URL", "Provider's web dashboard — where the user gets / rotates an API key. Optional. Rendered as a tappable link on the per-provider help page so the user can jump out and grab a key."),
            HelpCard("Identity is immutable", "The provider id (= its display label) lives in the title bar above and can't be edited in place. Pre-unification builds carried separate id / displayName / prefsKey fields; the unification refactor collapsed them into one. Renaming a provider is a delete-and-add operation, not an in-place change.")
        )
    ),
    "provider_card_api" to HelpContent(
        title = "API",
        cards = listOf(
            HelpCard("Format", "Selects the dispatch path:\n• OPENAI_COMPATIBLE — the default. Bearer auth, /v1/chat/completions request shape, SSE streaming via `data:` lines.\n• ANTHROPIC — `x-api-key` header + `anthropic-version: 2023-06-01`, /v1/messages, mandatory max_tokens on every request.\n• GOOGLE — `?key=` query param (NOT Bearer), generateContent path, no streaming for vision."),
            HelpCard("Type paths", "Per-provider override of the global per-type defaults from AI Setup → Model Types. Leave blank to inherit the user / hardcoded fallback (the placeholder shows what you'd inherit). Wrong path here yields HTTP 404 on every dispatch."),
            HelpCard("Models path", "The /models endpoint URL relative to Base URL. Defaults to `v1/models`. Some providers expose `models` (no `v1/`); set explicitly when the default 404s."),
            HelpCard("Model list format", "Flips between 'object' (provider returns `{data: [...]}` wrapping the list) and 'array' (provider returns the list directly). Wrong choice yields zero models on fetch with no useful error — the fetcher silently parses the wrong shape and gets nothing."),
            HelpCard("Seed field name", "What the request body calls the determinism seed. Most providers: `seed`. Mistral: `random_seed`. The dispatcher only sets this field when the user picks a Parameters preset with a non-null seed.")
        )
    ),
    "provider_card_models" to HelpContent(
        title = "Models",
        cards = listOf(
            HelpCard("Default source", "Decides where the per-provider Models screen seeds its list from on the first open + on Refresh:\n• API — hits the provider's /models endpoint on every refresh. Live catalog.\n• MANUAL — hands you an empty list to populate by hand from Hardcoded models. Useful for providers whose /models shape isn't supported, or when you want to lock the picker down to a curated subset."),
            HelpCard("Model filter regex", "Java regex (case-insensitive) that trims the live or hardcoded list to entries whose id matches. Examples:\n• `gpt|o1|o3|o4` on OpenAI hides the Whisper / DALL·E / TTS rows.\n• `claude` on Anthropic strips legacy aliases.\nLeave blank to keep everything the API returns. A bad regex compiles silently to a no-op — the field accepts the typo but the persisted value stays at the last good one."),
            HelpCard("Hardcoded models", "Asset-shipped fallback list. Applied verbatim in MANUAL mode. Unioned into the API list at fetch time when the Capability flags card has Merge hardcoded models enabled (OpenAI uses this — its /v1/models silently omits TTS / image / moderation endpoints).")
        )
    ),
    "provider_card_pricing" to HelpContent(
        title = "Pricing & cost",
        cards = listOf(
            HelpCard("OpenRouter name", "Maps this provider into OpenRouter's catalog so cross-provider pricing fan-out works. Example: Anthropic ships `openRouterName: 'anthropic'` so the layered lookup finds `anthropic/claude-3-5-sonnet` pricing on OpenRouter when no native source has it. Required for layered-pricing pickup; harmless when blank for fully self-priced providers."),
            HelpCard("LiteLLM prefix", "Slug used as the lookup key in the LiteLLM catalog. Per-provider override over the lowercased provider id fallback. Example: Together ships `litellmPrefix: 'together_ai'` because LiteLLM uses that slug, not 'together'. Leave blank to use the lowercased id."),
            HelpCard("Cost ticks divisor", "Providers that report cost in fractional ticks (xAI's $/1e10 scale) divide their per-call usage cost by this factor before rolling into the report's total. Leave blank for providers that bill in plain USD per token."),
            HelpCard("Extract API cost", "When ON, the dispatcher reads the cost figure straight off the response body — OpenRouter ships per-call cost in its usage object; most others don't. When OFF, cost is computed locally from token counts × the layered ModelPricing rate (provider self-report → LiteLLM → models.dev → llm-prices → Artificial Analysis → manual override → OpenRouter cross-provider → Helicone → DEFAULT).")
        )
    ),
    "provider_card_features" to HelpContent(
        title = "Features",
        cards = listOf(
            HelpCard("Supports citations", "When ON, the response parser pulls the Perplexity-style `citations` array out of the response and surfaces it inline on the per-agent result card. Only Perplexity sets this today. OpenAI's Responses-API web search uses a different shape and is gated separately by the Responses API patterns under Model patterns."),
            HelpCard("Supports search recency", "When ON, the dispatcher attaches Perplexity's `search_recency_filter` request param (`day` / `week` / `month` / `year`) when the user picks one on the report's web-search dropdown. Providers without this flag get the recency dropdown hidden — there's no point offering a knob the API can't honour."),
            HelpCard("Provider-wide gates", "Both fields are simple Boolean switches on the dispatch path, NOT model-name patterns. They apply to every model under this provider, not a subset. Per-model gating is in Model patterns.")
        )
    ),
    "provider_card_native" to HelpContent(
        title = "Native APIs",
        cards = listOf(
            HelpCard("Aux hosts", "Comma-separated list of alternate hostnames the provider's traffic lands on besides its baseUrl host. Example: Cohere — its compat shim lives on `api.cohere.ai` but the native rerank / capability endpoints land on `api.cohere.com`. Without the aux host the trace-list Provider filter would attribute those calls to '(unknown)'."),
            HelpCard("Native rerank URL", "Full URL of a Cohere v2/rerank-shaped POST endpoint. When set, Reports → Rerank routes through it instead of the chat-model fallback. Blank = the user is told to pick a chat model and the prompt-based rerank flow runs. Cohere's `https://api.cohere.com/v2/rerank` is the canonical example."),
            HelpCard("Native moderation URL", "Full URL of a Mistral v1/moderations-shaped POST endpoint. Same pattern as rerank — when set, the Moderate flow routes through it. Mistral's `https://api.mistral.ai/v1/moderations`."),
            HelpCard("Native capability URL", "Full URL of a Cohere-shaped /v1/models capability listing — the response carries `endpoints` / `supports_vision` / `context_length` per model. Set on providers whose OpenAI-compat shim strips that data but a separate native host returns it (Cohere again). The fetcher hits this URL alongside the regular /models call and merges the capability data into the model's row.")
        )
    ),
    "provider_card_capability" to HelpContent(
        title = "Capability flags",
        cards = listOf(
            HelpCard("Pricing from /models", "Provider's /v1/models response carries authoritative pricing (input/output per million tokens) which the fetcher harvests as a self-report tier in PricingCache — wins over LiteLLM, models.dev, etc. Together AI is the canonical example: ships `pricing: {input, output}` per row."),
            HelpCard("Cross-provider model list", "Provider's /models response drives pricing + type fan-out into every OTHER provider via the openRouterName prefix. Only OpenRouter has this today; exactly one provider should. When set, fetching this provider's catalog also enriches sibling providers' model-type labels and gives them a layered-pricing fallback row."),
            HelpCard("Merge hardcoded models", "Fetcher unions the persisted Hardcoded Models list with the API list. Useful when /models silently omits valid endpoints, e.g. OpenAI's TTS / image / moderation models aren't in /v1/models but the user still wants them in the picker. Off by default — most providers' /models is exhaustive."),
            HelpCard("External reasoning signal untrusted", "Ignore the provider's `reasoning: true` field on /models metadata — capability is decided purely by Reasoning model patterns + Reasoning effort accept patterns under Model patterns. xAI uses this because some always-on reasoning variants reject the `reasoning_effort` parameter, so trusting the metadata flag would attach a parameter the API rejects.")
        )
    ),
    "provider_card_patterns" to HelpContent(
        title = "Model patterns",
        cards = listOf(
            HelpCard("Pattern syntax", "Each pattern is a JSON object with any combination of `exact` / `prefix` / `contains` / `suffix`, matched against modelId.lowercase(). Every non-null part must match (intersection). Empty list = the feature is OFF for this provider. Example: `{prefix: \"grok-4-\", contains: \"reasoning\"}` matches only the grok-4 reasoning variants."),
            HelpCard("Responses API patterns", "Models routed to OpenAI's /v1/responses instead of /v1/chat/completions. OpenAI bundle ships gpt-5 / o-series / gpt-4.1 here. Other providers leave it empty — Anthropic and Google have their own request shapes selected by the API card's Format dropdown, not by per-model patterns."),
            HelpCard("Reasoning model patterns", "Gates the 🧠 badge AND the thinking dispatch path. Bundled values:\n• Anthropic — claude-3.7+, opus-4 / sonnet-4 / haiku-4\n• OpenAI — gpt-5, o1-o4\n• xAI — grok-3, grok-4, grok-code\n• Moonshot — kimi-k1.5, kimi-k2\n• DeepSeek — r1, reasoner\n• Mistral — magistral\n• Google — gemini-2.5"),
            HelpCard("Reasoning effort accept patterns", "Optionally narrows the previous list to the subset that actually accepts the `reasoning_effort` request param. Leave BLANK to fall back to Reasoning model patterns. Set when always-on variants reject the param. xAI is the only provider with this gap today: bundle ships `[{prefix:\"grok-3\"}, {exact:\"grok-4\"}, {prefix:\"grok-4-0\"}, {suffix:\"-reasoning\"}]` — covers controllable models, excludes grok-4.3 / grok-4.20-multi-agent / grok-code-fast-… (they reason internally but reject the parameter)."),
            HelpCard("Web-search model patterns", "Gates the 🌐 web_search tool descriptor in the request body. Bundled values: Anthropic claude-3.5+/3.7/4.x, Google gemini-1.5/2/pro, OpenAI gpt-5/o3/o4."),
            HelpCard("Adaptive thinking patterns (Anthropic)", "Opts a model into the newer `thinking.type:adaptive` request shape (Claude Opus 4.7+) instead of the legacy `{type:enabled, budget_tokens}` shape. Older 3.7 / 4.x models stay on budget_tokens. Bundle ships `[{contains:\"claude-opus-4-7\"}]`."),
            HelpCard("Max-tokens defaults (Anthropic)", "Anthropic requires max_tokens on every request and the cap differs by family — bundle ships:\n• opus-4 → 32000\n• sonnet-4 / haiku-4 → 8192\n• claude-3-5 / claude-3.5 → 8192\n• fallback (no rule matches) → 4096 (lives in code, NOT here).\nFirst matching rule wins, evaluated top-down.")
        )
    ),
    "provider_card_endpoints" to HelpContent(
        title = "Built-in endpoints",
        cards = listOf(
            HelpCard("Shape", "List of `{id, name, url, isDefault}` entries. The first `isDefault: true` shows up first when the user picks an endpoint while assigning a model to an Agent or sending a Test request. Empty list = a single synthesised default is used (built from the API card's Base URL + chat type path)."),
            HelpCard("Bundled values", "OpenAI ships Chat Completions + Responses API.\nMistral ships Chat Completions + Codestral.\nDeepSeek ships Chat Completions + Beta (FIM).\nZ.AI ships Chat Completions + Coding.\nEvery other provider ships nothing here and falls back to the synthesised default."),
            HelpCard("Round-trip", "Edits round-trip through providers.json on Export / Import — adding a custom endpoint here will appear in the exported catalog verbatim, and importing a catalog with new endpoints will land them on this card."),
            HelpCard("vs. user endpoints (Settings)", "Settings.endpoints is the per-user override map keyed by AppService. When non-empty for this provider, the user's list takes precedence; when empty, the catalog's builtInEndpoints win. The user's Add Endpoint flow (LiteLLM proxy URL etc.) writes into Settings.endpoints, never here.")
        )
    ),

    "info_provider_huggingface" to HelpContent(
        title = "HuggingFace (info provider)",
        cards = listOf(
            HelpCard("Overview", "Hugging Face Inc. runs the largest public registry of machine-learning models, datasets, and demo \"Spaces\". For this app it's a metadata source: we read its model-card API to pull license, context length, capability tags, and the README blurb that surfaces on Model Info."),
            HelpCard("What we use it for", "Per-model lookups against `https://huggingface.co/api/models/{id}`. Surfaces in Model Info → Sources card under HuggingFace as the raw JSON the call returned. Not a pricing source — HF doesn't publish per-token costs."),
            HelpCard("Endpoint", "`https://huggingface.co/api/models/{id}` (model card metadata). Anonymous calls are rate-limited; setting an HF token under External Services raises the limit and unlocks gated model metadata."),
            HelpCard("Freshness", "Lookups are on-demand — each Model Info open re-hits HF for that one model. There's no scheduled refresh because the data is already model-scoped."),
            HelpCard("Pitfalls", "Gated / private models return 401 without a token. Some \"models\" are duplicate aliases (e.g. fine-tuned forks) — the API returns whatever the user typed, not a canonical id. Long descriptions can balloon the response — we render the JSON tree truncated."),
            HelpCard("Related", "External Services screen has the API key field. Set the same key under Settings → External Services to reuse it across model-info, embedding, and download flows.")
        )
    ),
    "info_provider_openrouter" to HelpContent(
        title = "OpenRouter (info provider)",
        cards = listOf(
            HelpCard("Overview", "OpenRouter is an aggregator that proxies requests to dozens of upstream AI providers behind a single API. The app uses it in two roles: as an AI provider itself (chat / completion) AND as a metadata + pricing catalog spanning every model OpenRouter routes to."),
            HelpCard("What we use it for", "Two endpoints: a global catalog with prompt / completion prices, and a per-model specs lookup with capability fields (context, supports vision, supports tools, etc.). Both feed the layered pricing lookup and Model Info."),
            HelpCard("Endpoint", "Catalog: `https://openrouter.ai/api/v1/models` (auth optional but recommended). Per-model: `https://openrouter.ai/api/v1/models/{id}/endpoints`. API key under External Services raises rate limits."),
            HelpCard("Freshness", "Refreshed on demand from Refresh → OpenRouter (full catalog) or implicitly when Model Info opens (per-model specs). Catalog refresh disables when no API key is set."),
            HelpCard("Pitfalls", "OpenRouter quotes the upstream provider's price plus its own margin; numbers can drift from the provider's own published rates. Model ids are slash-prefixed (`anthropic/claude-3-5-sonnet`) — the catalog uses those, while LiteLLM uses bare ids."),
            HelpCard("Related", "OpenRouter is also an AppService — chat completions go to the same host but carry a different trace category. Used as the cross-provider fallback in PricingCache.getPricing.")
        )
    ),
    "info_provider_litellm" to HelpContent(
        title = "LiteLLM (info provider)",
        cards = listOf(
            HelpCard("Overview", "LiteLLM is an open-source library by BerriAI that abstracts the SDKs of every major AI provider behind one shape. It also ships a curated JSON catalog of every model the maintainers know about, with input/output token prices, context windows, and capability flags."),
            HelpCard("What we use it for", "We pull the JSON catalog (no SDK / no proxy server — just the data file) and use it as the primary pricing tier in the layered lookup. Also feeds the capability sets (vision, web search, system-message support, …) that drive UI badges."),
            HelpCard("Endpoint", "`https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json` — a single file, no auth, no rate limit beyond GitHub's. Refreshed on demand from Refresh → LiteLLM."),
            HelpCard("Freshness", "Updates land in the LiteLLM repo within days of a provider price change — usually faster than the model's own pricing page goes live in the marketing site. Stale by up to a week is normal."),
            HelpCard("Pitfalls", "Lags `-latest` aliases (model_prices keys are dated ids). New models from less-popular providers can take longer to appear. Keys are bare ids (no provider/ prefix), unlike OpenRouter."),
            HelpCard("Related", "Sits ahead of OpenRouter in the layered lookup precedence (LiteLLM → models.dev → curated tiers → Override → OpenRouter → Default).")
        )
    ),
    "info_provider_models_dev" to HelpContent(
        title = "models.dev (info provider)",
        cards = listOf(
            HelpCard("Overview", "models.dev is a community-curated catalog of AI models with a single JSON dump endpoint. Slimmer than LiteLLM but covers some entries (and some capability fields) that LiteLLM lags on, so it sits as a per-field fallback in our pricing chain."),
            HelpCard("What we use it for", "Pricing + capability fallback when LiteLLM has no entry for a model. Same shape as LiteLLM: prompt/completion price + context window + supports-X flags."),
            HelpCard("Endpoint", "`https://models.dev/api.json` — anonymous, single file. Refreshed on demand from Refresh → models.dev."),
            HelpCard("Freshness", "Community-driven; updates are less predictable than LiteLLM's. Use both — the layered lookup will pick whichever has a non-null entry first."),
            HelpCard("Pitfalls", "Coverage gaps for niche providers; some entries lag price changes. The JSON shape sometimes drifts — the parser is forgiving but a totally new field would silently be ignored."),
            HelpCard("Related", "Sits between LiteLLM and the curated tiers in the layered lookup. Refresh → models.dev recomputes capability snapshots so vision / web-search / reasoning badges pick up changes.")
        )
    ),
    "info_provider_helicone" to HelpContent(
        title = "Helicone (info provider)",
        cards = listOf(
            HelpCard("Overview", "Helicone is an AI observability platform — they run a hosted service that logs LLM API calls. As a side product they publish a public LLM-costs JSON aggregating per-model prices across every provider they instrument."),
            HelpCard("What we use it for", "Pricing-only fallback. We never send Helicone any data — we only read the public llm-costs endpoint and slot it into the layered lookup."),
            HelpCard("Endpoint", "`https://www.helicone.ai/api/llm-costs` — anonymous, JSON. Refreshed on demand from Refresh → Helicone."),
            HelpCard("Freshness", "Publishing cadence varies. Used as a low-priority fallback because Helicone's coverage is biased toward providers their customers actually use; coverage of long-tail models is thinner than LiteLLM's."),
            HelpCard("Pitfalls", "Pricing only — no capability fields. Helicone's id format sometimes diverges from the provider's own (case differences). The parser falls back gracefully when an id can't be matched."),
            HelpCard("Related", "Sits in the curated-tiers band of the layered lookup, alongside llm-prices and Artificial Analysis.")
        )
    ),
    "info_provider_llm_prices" to HelpContent(
        title = "llm-prices.com (info provider)",
        cards = listOf(
            HelpCard("Overview", "llm-prices is Simon Willison's hand-curated tracker of frontier-model pricing across roughly 10 vendors (OpenAI, Anthropic, Google, xAI, Meta, Mistral, DeepSeek, …). Smaller scope than LiteLLM but updated quickly when prices change."),
            HelpCard("What we use it for", "Pricing fallback. Per-vendor JSON files in the simonw/llm-prices GitHub repo; one file per vendor, fetched on demand."),
            HelpCard("Endpoint", "`https://raw.githubusercontent.com/simonw/llm-prices/main/data/{vendor}.json` (no auth). Refreshed on demand from Refresh → llm-prices.com."),
            HelpCard("Freshness", "Hand-maintained, often updated within hours of a price announcement. Coverage is intentionally narrow — it's a curated hot-list of frontier models, not a complete catalog."),
            HelpCard("Pitfalls", "Only covers ~10 vendors. Models from anything else won't be found here. The repo's data shape is stable but small schema drifts are possible — we tolerate missing fields."),
            HelpCard("Related", "Curated-tier sibling of Helicone and Artificial Analysis. Frequently the freshest source for the big-3 frontier models.")
        )
    ),
    "info_provider_artificial_analysis" to HelpContent(
        title = "Artificial Analysis (info provider)",
        cards = listOf(
            HelpCard("Overview", "Artificial Analysis is an independent benchmarking company. They publish curated model metadata + pricing alongside their own latency / quality benchmarks. We use only the metadata + pricing slice."),
            HelpCard("What we use it for", "Pricing + capability fallback in the layered lookup. Their dataset is hand-curated and tends to align well with the provider's own pricing page."),
            HelpCard("Endpoint", "`https://artificialanalysis.ai/api/v2/data/llms/models` — requires an API key set under External Services. Refreshed on demand from Refresh → Artificial Analysis (button disabled until the key is set)."),
            HelpCard("Freshness", "Updates are reasonably timely — they instrument the providers themselves, so price-change detection is part of their workflow. Not as fast as llm-prices for the very-new-frontier models."),
            HelpCard("Pitfalls", "API key is mandatory; without it the catalog never loads and AA stays absent from the layered lookup. Some niche models aren't covered."),
            HelpCard("Related", "External Services holds the AA API key. Curated-tier sibling of Helicone and llm-prices.")
        )
    ),
    "help_topic_view" to HelpContent(
        title = "Help (this screen)",
        cards = listOf(
            HelpCard("Overview", "You're looking at one help topic. Each topic is a stack of cards — Overview / What we use it for / Endpoint / Freshness / Pitfalls / Related is the typical shape, but topics differ in detail. Card titles are blue; bodies are dim."),
            HelpCard("Title bar — ◀ Back", "Returns to wherever you came from — the home Help page if you tapped a row in the Info-providers table; otherwise the screen whose ℹ️ icon brought you here."),
            HelpCard("Title bar — 🏠 Home", "Returns to the AI Hub. Skips the back stack."),
            HelpCard("Title bar — ❓ Help", "Opens this page (help for the help-topic screen). Hidden on the home Help page and on this meta-topic itself."),
            HelpCard("Reaching this", "Three doors: home Help → Info-providers table tap; any screen's title-bar ℹ️ when it points at one of the 7 info providers (Source detail, Trace detail for a pricing fetch, External Services card, Refresh row); inline links such as the source labels on Model Info → Costs and Capabilities cards."),
            HelpCard("Pitfalls", "Topics don't cross-link inside cards yet — the seven Info-provider topics are reachable only through the entry points listed above. Use the device back arrow / ◀ to navigate.")
        )
    ),
    "reports_hub" to HelpContent(
        title = "AI Reports",
        cards = listOf(
            HelpCard("Overview", "Reports send the same prompt to multiple models in parallel and collect every response. Each result is saved to disk and can be reopened, exported, translated, summarised, or fed forward into a chat."),
            HelpCard("In-flight pill", "When at least one report has unfinished agents (PENDING / RUNNING and no completedAt), an orange ⏳ pill appears at the top — tap it to resume the most recent in-flight run without going through History."),
            HelpCard("Start card", "Four entries: New AI Report (blank), Start with a previous prompt (last 100, deduplicated), Start with an example prompt (from the user-curated library under Prompt management → Example prompts; disabled when empty), Start with photo (camera capture becomes the seed image for a vision-capable run)."),
            HelpCard("View previous reports", "Opens the History list. Disabled when nothing has been saved yet."),
            HelpCard("Pinned and Recent", "Every pinned report is listed under 📌. The three most recent unpinned reports show under 🕘. Tap a row to open the report."),
            HelpCard("Search card", "Four search modes ordered by escalating cost: 🔍 Quick local (substring), 📂 Extended local (tokenised), 🌐 Remote semantic (cloud embeddings), 📱 Local semantic (on-device embedder)."),
            HelpCard("Manage", "Bulk operations — trim by age, export-all backup zip. Disabled until at least one report exists."),
            HelpCard("Shared files banner", "When the Android share-target routed files into a fresh KB, a green banner appears on New Report offering Attach as KB / Skip — handled automatically once dismissed.")
        )
    ),
    "report_new" to HelpContent(
        title = "New AI Report",
        cards = listOf(
            HelpCard("Overview", "Two-stage: type a title + prompt here, then on Next pick which agents / flocks / swarms / models receive the prompt. The title and prompt are saved to LAST_AI_REPORT_TITLE / _PROMPT and to the last-100 prompt history."),
            HelpCard("Title and prompt", "Both are required for Next to enable. Title is single-line; prompt is multi-line with a 10-line minimum height. Clear wipes both fields plus any attached image."),
            HelpCard("Image attachment", "📎 picks an image from device storage and attaches it as base64 — passed through to every agent's prompt at dispatch. Only vision-capable models will actually read it; the rest receive the text alone. Image-attached reports can be MB-sized on disk."),
            HelpCard("Web search chip", "🌐 tags every dispatched call with the web-search tool flag. Providers and models that don't support web search drop the flag silently."),
            HelpCard("Thinking chip", "🧠 None / Low / Medium / High. Applied to every agent at dispatch; non-thinking models drop the field automatically."),
            HelpCard("Validate prompt chip", "🛡 picks a moderation model and runs the prompt through it before any agent fires. If the model flags the prompt, you get a Proceed-anyway / Cancel dialog with a 🐞 link to the moderation trace; tap when on to clear the model."),
            HelpCard("Shared KB banner", "When files were routed in via the share-target, a green banner offers a one-tap KB build from those files (using the local default embedder when installed, otherwise the first remote embedding model). Indexing runs on Dispatchers.IO with progress messages; the new KB id auto-attaches to the report."),
            HelpCard("Next", "Saves title + prompt to last-prompt prefs and prompt history, then routes to the model-selection screen. While moderation is running the button shows a spinner.")
        )
    ),
    "report_result_generation" to HelpContent(
        title = "Report — selection and results",
        cards = listOf(
            HelpCard("Overview", "Single screen with two phases. Selection: empty list with +Agent / +Flock / +Swarm / +Model / +Report buttons + Params + Generate. Results: the same screen flips into per-agent rows + Action row once Generate fires."),
            HelpCard("Add buttons (selection)", "+Agent picks one saved agent, +Flock adds every member of a flock, +Swarm adds every (provider, model) pair in a swarm, +Model is the single-select all-providers picker, +Report copies the model list from a previous report."),
            HelpCard("Knowledge attach (selection)", "When you have at least one saved KB, a 📚 row shows the current attachment count and opens a multi-select. Attached KB ids ride with the report and inject relevant chunks into every dispatched call."),
            HelpCard("Params (selection)", "Opens Advanced Parameters — temperature, max tokens, system prompt, etc. The button reads Params ✓ when an override is active. Clear all wipes the override."),
            HelpCard("Generate / Update model list", "Generate fires the dispatch. When entered via Edit / Models on a finished report, the bottom button switches to Update model list — stages the new list and pops back without re-running; you re-fire from Action row → Regenerate."),
            HelpCard("Action row (results)", "While running: STOP / Background. Once complete: View, Edit, Regenerate, Export, Copy, Pin/Unpin, Translate, Meta (disabled when no Meta prompts exist), Fan out (disabled when no Fan-out prompts exist)."),
            HelpCard("View popup", "Reports / Prompt / Costs plus one row per Meta-prompt name with at least one persisted secondary. Edit popup is Prompt / Title / Models / Parameters."),
            HelpCard("Pending-changes banner", "Orange banner appears when the user edited prompt / models / parameters since the last run — Regenerate is required for the new values to take effect."),
            HelpCard("Title bar — 💬", "Results phase only, and only when the prompt is non-blank. Stashes the prompt as the chat starter and routes to the agent picker — pick an agent, the chat opens with the report's prompt as the first user turn."),
            HelpCard("Per-row 🐞", "Each agent row carries the trace icon when its API call left a recording. Tapping opens that single trace file."),
            HelpCard("Stuck rows", "On reopen, any row left in PENDING / RUNNING by a force-quit is recovered: a one-shot sweep marks blank-content / null-error / null-duration secondaries as errored, and a 150 ms tick refreshes the inline meta list. If a row still spins, tap Regenerate.")
        )
    ),
    "report_single_result" to HelpContent(
        title = "Single agent result",
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
        title = "Continue in chat",
        cards = listOf(
            HelpCard("Overview", "Three-row picker that hands this agent's response off to a fresh chat session as the seed turn. Reached from the 💬 button on the single-agent result."),
            HelpCard("📜 with current history and model", "Reuses the same provider/model and the agent's resolved system prompt + parameters from current settings. The chat starts with the report prompt + this response already in the transcript."),
            HelpCard("🤖 with this response only and select an agent", "Stashes the agent's response as the next chat's input-box starter and routes to the agent picker. The picked agent's system prompt and parameters then drive the session."),
            HelpCard("🛠️ with this response only and configure on the fly", "Stashes the response and walks you through provider → model → parameters before opening the chat — handy when none of your saved agents fit."),
            HelpCard("Tips", "All three rows are always enabled here; the upstream button on the single-result screen is the one that disables on empty / errored responses."),
            HelpCard("Related", "Single agent result → 💬 Continue in chat opens this. Picking a row navigates to a Chat session.")
        )
    ),
    "secondary_list" to HelpContent(
        title = "Secondary results — list",
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
        title = "Secondary result — detail",
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
        title = "Fan out — answerers",
        cards = listOf(
            HelpCard("Overview", "Top of the fan-out drilldown. Lists every answerer model on this fan-out run with its current status. Tap an answerer to step into its sources at L2."),
            HelpCard("Status icons", "Per-row: ✅ all pairs done, ❌ at least one errored, ⏳ at least one running, queued = no row on disk yet. Derived from latestByPair across all results."),
            HelpCard("Resume stale", "On open, any fan-out pair with no content / no error / not in runningFanOutPairs is re-enqueued via onResumeStaleFanOut — survives app kill mid-batch."),
            HelpCard("Restart failed", "Re-runs only ❌ cells, leaving ✅ alone. Skips the placeholder grid rebuild — quick recovery without re-spending tokens on succeeded cells."),
            HelpCard("Combine reports", "When at least one fan-in prompt exists, the screen exposes 'Run combine reports' — fires a meta call against the fan-out matrix's results."),
            HelpCard("Per-answerer delete", "Drops every cell where this answerer participated. Fan-out list refresh tick bumps so the L1 list reflects the gap on pop-back."),
            HelpCard("Pitfalls", "Cell count is N×(N-1) for an N-agent run; cost grows fast. Watch the Action row's cost summary before pressing Restart on large grids.")
        )
    ),
    "secondary_fan_out_l2" to HelpContent(
        title = "Fan out — model",
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
        title = "Fan out — pair",
        cards = listOf(
            HelpCard("Overview", "Single cell view. Source content (the row this answerer was given) is on top; the fan-out response (this answerer's reply to that source) is underneath. Two scrollable panes split half-and-half by default."),
            HelpCard("Title bar — 🐞", "Opens this fan-out call's captured trace when tracing was on at the time of the call."),
            HelpCard("Back", "System back / ‹ pops one level up to L2 (per-model)."),
            HelpCard("Pitfalls", "If the source has been deleted from the report after this fan-out ran, the source pane shows a placeholder; the response stays visible."),
        )
    ),
    "secondary_fan_out_onepage" to HelpContent(
        title = "Fan out — one page",
        cards = listOf(
            HelpCard("Overview", "Concatenates every (source, response) pair under the active answerer onto one page so you can scan the whole drilldown without tapping each cell."),
            HelpCard("Layout", "Per pair: source label + body, then the fan-out response body. Sources render in the order activeAgents (the row stack visible on L2)."),
            HelpCard("Initiator role", "When the parent L2 was on Initiator role, the page lists every (answerer, source) where the active model was the source — same shape, opposite direction."),
            HelpCard("Title bar — ℹ️", "Opens Model Info for the active (provider, model) pair."),
            HelpCard("Pitfalls", "Long fan-out runs render many MB of text; rendering can be slow on dense reports. Use L2 + tap-into-cell when you only need one pair."),
        )
    ),
    "secondary_scope" to HelpContent(
        title = "Secondary scope",
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
        title = "Meta",
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
        title = "Edit prompt",
        cards = listOf(
            HelpCard("Overview", "Modify the report's prompt. Saving stamps hasPendingPromptChange so the result screen surfaces a yellow 'Changes pending: prompt' banner — the existing rows aren't re-rendered until you tap Regenerate."),
            HelpCard("Prompt field", "Multi-line, fills the screen. Update prompt is disabled when the body trims to blank."),
            HelpCard("Saver scoping", "rememberSaveable is keyed on initialPrompt so re-opening the overlay with a fresh seed value doesn't restore a stale draft from the SaveableStateRegistry."),
            HelpCard("Pitfalls", "Editing the prompt alone doesn't re-run agents — the existing responses stay on screen until you Regenerate."),
            HelpCard("Related", "Action row → Edit → Title is a separate overlay; Edit → Models routes to the selection phase in 'Update model list' mode for stripping/adding agents.")
        )
    ),
    "report_edit_title" to HelpContent(
        title = "Edit title",
        cards = listOf(
            HelpCard("Overview", "Rename the report. Title is metadata only — no outbound API call references it, so this never sets hasPendingPromptChange and you don't need to regenerate to see the new title applied."),
            HelpCard("Title field", "Single-line. Update title is disabled when the body trims to blank."),
            HelpCard("Saver scoping", "rememberSaveable is keyed on initialTitle so re-opening the overlay with a fresh seed doesn't restore a stale draft."),
            HelpCard("Related", "Edit prompt is a separate overlay (with the pending-changes banner); this screen is in-place editing.")
        )
    ),
    "report_parameters" to HelpContent(
        title = "Advanced Parameters",
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
        title = "Export report",
        cards = listOf(
            HelpCard("Overview", "Pick a format and (when relevant) a detail level, then either share to another app, view in browser, or build the master Export-all zip."),
            HelpCard("Format chips", "HTML, PDF, MS Word, OpenDocument, JSON, Zipped HTML — wrap to a second row on narrow phones via FlowRow. JSON and Zipped HTML ignore the detail picker; everything else honors it."),
            HelpCard("Detail — Short", "Prompt, per-model results (with citations and related questions), Meta sections (one per Meta prompt) plus Moderations. No index, no costs, no traces."),
            HelpCard("Detail — Complete", "Index, prompt, every Meta section, Reranks / Moderations / Translations, the cost table, and every captured API trace with redacted bodies."),
            HelpCard("Android share", "Builds the file and hands it to the system share sheet. Closes the Export screen so back from the chooser doesn't loop here."),
            HelpCard("View in browser", "Builds the file and opens it as a separate Android intent. Stays on this screen so you can come back and try a different format without rebuilding the picker state."),
            HelpCard("Export all (zip)", "Bundles all 8 documents (Short + Complete × HTML / PDF / DOCX / ODT) plus the JSON traces zip into one master zip and shares it. Pops the screen on success."),
            HelpCard("Progress dialog", "While building, a non-dismissable dialog shows a linear progress bar driven by (done, total) updates from the export. Failures show a Toast with the exception class + message; the dialog clears."),
        )
    ),
    "report_manage" to HelpContent(
        title = "Manage reports",
        cards = listOf(
            HelpCard("Overview", "Hub-level housekeeping for saved reports — two cards: Delete old reports, and Export all (backup)."),
            HelpCard("Delete old reports", "Numeric field (digits, max 4) for 'Older than (days)'. Pinned reports are skipped. Confirm dialog shows the candidate count before any file is touched."),
            HelpCard("Export all (backup)", "Zips every report JSON plus every secondary results file into a single archive (ai_reports_backup_<ts>.zip) and opens the system share sheet. Status text reads 'Bundled N reports' on success."),
            HelpCard("Working state", "While the zip / delete is in flight, both buttons are disabled and the export label switches to 'Working…'."),
            HelpCard("Status line", "Final operation result lives as a small grey line at the bottom — 'Deleted N reports.', 'Bundled N reports.', or 'Nothing to export.'."),
            HelpCard("Pitfalls", "Delete is irreversible — once the cutoff fires, those reports' secondaries and trace files go too. Take an Export all first if you might want them back."),
            HelpCard("Related", "Use Housekeeping → Backup & Restore for the full-app backup; this screen is reports-only.")
        )
    ),
    "report_view_picker" to HelpContent(
        title = "View — picker",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker reached from the Report Result action row's View button. Each row is a separate view of the current report."),
            HelpCard("Reports", "Opens the per-agent reports viewer. Detail line shows N of M agents succeeded so you can spot a partially-failed run before drilling in."),
            HelpCard("Prompt", "Opens the report's full prompt as scrollable text. Detail line previews the first non-blank line (≤80 chars)."),
            HelpCard("Costs", "Tokens + cost breakdown across all agents and secondaries. Detail line shows the secondary spend so far in USD when there is any."),
            HelpCard("Per-Meta-prompt rows", "One row per Meta-prompt name with at least one persisted secondary on this report. Detail = run count; secondary line = the kind label (Rerank / Summarize / Compare / Moderate)."),
        )
    ),
    "report_edit_picker" to HelpContent(
        title = "Edit — picker",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker reached from the Report Result action row's Edit button. Each row routes to a separate edit screen for one slice of the report."),
            HelpCard("Prompt", "Opens the multi-line prompt editor. Detail line previews the first non-blank line (≤80 chars). Saving stamps a 'Changes pending: prompt' banner on the result screen until you Regenerate."),
            HelpCard("Title", "Single-line title editor. No pending-changes flag — title is metadata only."),
            HelpCard("Models", "Routes back to the selection phase with the report's existing model list staged for in-place editing. The detail line says how many models are currently on the report."),
            HelpCard("Parameters", "Opens the per-report parameter override (temperature, max tokens, top P, stop sequences, etc). Detail line is a generic field hint."),
        )
    ),
    "report_fan_out_confirm" to HelpContent(
        title = "Fan out — confirm run",
        cards = listOf(
            HelpCard("Overview", "Confirmation screen shown after the Fan out scope picker, before the runner kicks off. Lists exactly how many calls a Run will fire and which models are involved."),
            HelpCard("Counts grid", "answerers × responses-per-report = total calls. Falls back to a flat 'N calls' line when scope is uneven enough that the grid math doesn't divide cleanly."),
            HelpCard("Scope", "All reports / Top-N ranked / Manual selection. Reflects the choice made on the previous screen — back to change it."),
            HelpCard("Answerer / Source lists", "Two cards listing the model names on each side of the fan out. A model appears in both when it's both an answerer and a source."),
            HelpCard("Fan-out prompt", "Preview of the prompt body (≤12 lines) that will be sent for every pair, with @RESPONSE@ filled in at run time."),
            HelpCard("Run / Cancel", "Run is disabled while the count loads or when there are zero pairs. Cancel pops back to the previous screen without firing.")
        )
    ),
    "developer_select_model" to HelpContent(
        title = "API Test — Select Model",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker over the active provider's model list. Tap a row to drop the chosen model into the API Test request."),
            HelpCard("Search field", "Filters by model id (case-insensitive). The ✕ trailing icon clears the field. Counter line shows '<filtered> of <total> models'."),
            HelpCard("Loading state", "If the model list hasn't been fetched yet, a spinner appears in the body. Tap Fetch from the API Test page first when the list reads empty."),
            HelpCard("Pricing column", "Per-row prompt / completion price (×10⁶ tokens). Real pricing renders in green; rows that fell through to DEFAULT_PRICING render dim with 'no pricing'."),
        )
    ),
    "developer_select_endpoint" to HelpContent(
        title = "API Test — Select Endpoint",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker over the active provider's endpoints. The first row is the provider's default base URL; saved custom endpoints follow."),
            HelpCard("Default row", "Drops the provider.baseUrl into the API Test request. Always present even when there are no custom endpoints saved."),
            HelpCard("Custom rows", "One row per Endpoint defined under AI Setup → Providers → Endpoints for this provider. Label + URL on two lines (URL is monospace)."),
        )
    ),
    "refresh_result" to HelpContent(
        title = "Refresh — result",
        cards = listOf(
            HelpCard("Overview", "Result screen shown after a Refresh sub-action finishes (catalog refresh, provider state, model refresh, default-agent generation). Replaces the popup result dialogs the screen used to show."),
            HelpCard("Description block", "Short explanation of what the refresh did and why. Failure states explain what to check (API key, connectivity, etc)."),
            HelpCard("Result rows", "One row per measured value — Status / counts / cache age. Green = loaded, red = failed, grey = neutral metric."),
            HelpCard("Sample model entries", "Catalog refreshes (OpenRouter / LiteLLM) include up to 8 sample model keys from the cache so you can confirm real data landed."),
            HelpCard("OK button", "Returns to the Refresh screen. Multi-row screens (Provider state, Default agents) update live while the underlying refresh runs.")
        )
    ),
    "report_pick_flock" to HelpContent(
        title = "Pick a flock",
        cards = listOf(
            HelpCard("Overview", "Modal dialog that lists every saved flock with its agent count and a synthetic per-million-tokens cost band. Tap a row to add every member to the report."),
            HelpCard("Search field", "Filters by name (case-insensitive). The ✕ trailing icon clears the field."),
            HelpCard("Member count", "Reflects what expandFlockToModels actually feeds the report — agents whose provider isn't Active are skipped, so the count matches the worker count after Generate."),
            HelpCard("Pricing column", "Sums per-million prompt / completion across all members. Red when at least one member has real pricing data; grey-on-grey badge when every member fell through to DEFAULT."),
            HelpCard("Empty state", "Opens an empty list; define flocks in AI Setup → Workers → Flocks first."),
            HelpCard("Back button", "Bottom-right TextButton dismisses without a selection."),
            HelpCard("Pitfalls", "Flocks reference agents by id; deleting the underlying agent leaves a broken member. Edit the flock first."),
            HelpCard("Related", "+Agent for one agent at a time, +Swarm for direct provider/model groups (no system prompt / parameters).")
        )
    ),
    "report_pick_agent" to HelpContent(
        title = "Pick an agent",
        cards = listOf(
            HelpCard("Overview", "Agent dialog reached by +Agent on the selection phase. Lists every saved agent with name + provider · model + per-million-token pricing. Search filters by name or provider name. Tap a row to add the agent to the report."),
            HelpCard("Pricing badge", "Red when the model has real pricing data; grey-on-grey when the row fell through to DEFAULT_PRICING. Updates as PricingCache loads tier blobs in the background."),
            HelpCard("Empty state", "When there are no agents yet, the body is empty — set up agents first under AI Setup → Agents."),
            HelpCard("Title bar / dismiss", "Dialog dismisses via a Back TextButton at the bottom-right.")
        )
    ),
    "report_pick_previous" to HelpContent(
        title = "Pick previous report",
        cards = listOf(
            HelpCard("Overview", "Single-select picker over saved reports, reached by +Report on the selection phase. Newest first by Report.timestamp. Tap to copy that report's model list into the current selection."),
            HelpCard("Search", "Filters by title or prompt. The count line above the list reads '<filtered> of <total> reports'."),
            HelpCard("Empty state", "When no reports exist yet, the body shows 'No previous reports yet.'"),
            HelpCard("Pitfalls", "Reports list is loaded off the UI thread because getAllReports re-parses every report JSON, including image-attached ones."),
            HelpCard("Title bar", "Standard back arrow — popping back returns you to the New AI Report selection phase.")
        )
    ),
    "report_pick_swarm" to HelpContent(
        title = "Pick a swarm",
        cards = listOf(
            HelpCard("Overview", "Full-screen swarm picker reached by +Swarm on the New AI Report selection phase. Lists every saved swarm with member count + summed per-million pricing. Tap a row to add every (provider, model) pair to the report."),
            HelpCard("Search field", "Filters by swarm name (case-insensitive). The ✕ trailing icon clears the field."),
            HelpCard("ℹ️ icon", "Left of each swarm name — opens a per-swarm detail screen (Swarm info) listing every member with provider, model, capability badges, and per-million pricing. Tap a row there to drill into Model Info. Tap target is separate from the row's main click so you can preview without adding the swarm."),
            HelpCard("Pricing column", "Sums per-million prompt / completion across all members. Red when at least one member has real pricing data; grey-on-grey badge when every member fell through to DEFAULT."),
            HelpCard("Empty state", "Opens an empty list; define swarms in AI Setup → Workers → Swarms first."),
            HelpCard("Pitfalls", "Members survive when their provider is inactive — the swarm definition is purely structural. The report-run dispatch silently skips inactive members; the ℹ info screen still lists them."),
            HelpCard("Related", "+Flock for grouped agents with system-prompt / parameter presets, +Agent for one configured agent at a time, +Model for a single (provider, model) pair.")
        )
    ),
    "report_pick_model" to HelpContent(
        title = "Pick model",
        cards = listOf(
            HelpCard("Overview", "Full-screen single-select model picker reached by +Model on the New AI Report selection phase, and by the secondary-result launchers (Meta / Fan-out / Fan-in / Fan-in-model / Translate / Rerank) when they need a model."),
            HelpCard("List", "Joins every active provider's catalog plus, when a model-type filter is set, on-device LiteRT models exposed under the synthetic Local provider."),
            HelpCard("Provider filter", "Dropdown above the list — All Providers or one specific provider (count shown next to each name). LOCAL appears here only when the type filter has matching local models."),
            HelpCard("Type filter", "When opened with a modelTypeFilter (RERANK / MODERATION / EMBEDDING / etc.), a checkbox '<Type> models only' is shown ON by default — untick to widen to the full catalog."),
            HelpCard("Search field", "Matches against provider id and model id. The count line above the list reads '<filtered> of <total> models'."),
            HelpCard("Recent section", "When the user has picked from any Report-section model picker before, the last 3 picks surface as a 'Recent' section above the main alphabetical list. Filters and search don't trim it — recents are a quick-access shortcut. Tapping a recent row also re-records it so the bump-to-front keeps ordering stable."),
            HelpCard("Already-added rows", "Rows passed in via alreadyAdded render at 0.4 alpha, are not clickable, and append ' · already added' next to capability badges."),
            HelpCard("Pricing column", "Per-token (×10⁶) prompt / completion, red for real data, grey badge for DEFAULT. Vision / Web / Reasoning badges sit before the price."),
            HelpCard("Tap to confirm", "Single-select: tapping a row immediately fires onConfirm with the (provider, model) pair and the caller closes the picker. No multi-select, no batch confirm."),
            HelpCard("Related", "+Swarm for a pre-grouped batch, +Flock for grouped agents, +Agent for one saved agent.")
        )
    ),
    "report_swarm_info" to HelpContent(
        title = "Swarm info",
        cards = listOf(
            HelpCard("Overview", "Per-swarm detail screen reached by tapping the ℹ️ icon next to a row on Pick a swarm. Lists every (provider, model) member of the swarm in member order."),
            HelpCard("Per-row content", "Provider id in blue on top, model id in white below. Capability badges (vision / web search / reasoning) only appear when the catalog reports the capability for this model. Per-million-token prompt / completion price pair on the right — red when real pricing data exists, grey badge when the row fell through to DEFAULT_PRICING."),
            HelpCard("Tap a row", "Opens Model Info for that (provider, model) — the same destination the title-bar ℹ️ icon reaches across the rest of the app. Use the Costs and Capabilities cards there to see every source's reading for this model."),
            HelpCard("Title bar", "Static \"Swarm\" with the swarm name as the dynamic subject (folds into the bar when the \"Subject to title bar\" setting is on, otherwise sits below as a green sub-header). The back arrow returns to Pick a swarm with the previous filter intact."),
            HelpCard("Pitfalls", "Members survive even when their provider is inactive or their API key isn't configured — the swarm definition is purely structural. The report-run dispatch silently skips inactive members; this info screen still lists them so you can spot which row to fix."),
            HelpCard("Related", "Pick a swarm (parent), Model Info (drill target), Edit Swarms (under AI Setup → Workers → Swarms) for changing the member list.")
        )
    ),
    "report_flock_info" to HelpContent(
        title = "Flock info",
        cards = listOf(
            HelpCard("Overview", "Per-flock detail screen reached by tapping the ℹ️ icon next to a row on Pick a flock. Lists every member agent of the flock in member order."),
            HelpCard("Flock overrides header", "Shown at the top only when the flock pins its own params or system-prompt preset(s). These override the matching agent-level presets at report-run time — surfacing them once at the top tells you what'll actually drive the run after the merge."),
            HelpCard("Per-row content", "Provider id (blue), effective model id (white, resolved via getEffectiveModelForAgent so a provider-default model picks up the live default). Vision / Web / Reasoning capability badges + per-million-token price pair on the right. Two extra lines below the model when the agent has them: \"Parameters: name1, name2\" and \"System prompt: name\"."),
            HelpCard("Tap a row", "Opens Model Info for the agent's (provider, effective-model). Same drill-in the title-bar ℹ️ uses elsewhere."),
            HelpCard("Title bar", "Static \"Flock\" with the flock name as the dynamic subject. Back returns to Pick a flock."),
            HelpCard("Pitfalls", "Agents whose provider is inactive still appear here — the agent / flock list is the source of truth, but expandFlockToModels skips inactive members when feeding the report. The agent count shown on Pick a flock already reflects that filtering."),
            HelpCard("Related", "Pick a flock (parent), Model Info (drill target), Edit Flocks / Edit Agents (under AI Setup → Workers) for changing the membership or per-agent presets.")
        )
    ),
    "translation_run" to HelpContent(
        title = "Translation run",
        cards = listOf(
            HelpCard("Overview", "Lists every TRANSLATE call inside one run, grouped by translationRunId (or, for legacy rows, by 'lang:<targetLanguage>'). Sorted by timestamp ascending."),
            HelpCard("Header", "Provider / model line up top — every call in the run shares one model, so it's shown once. Tap the line to open Model Info. Below: '<count> calls' and the summed cost in cents (when > 0)."),
            HelpCard("Per-row content", "Status emoji (✅ done, ⏳ blank-content, ❌ errored), source-type label (report / prompt / fan-out / fan-in / meta-prompt-name), then the actual source rebuilt from the original (so the user's Model name layout setting wins over the frozen agentName)."),
            HelpCard("Per-row tap", "Opens TranslationCallDetailScreen with original on top, translation underneath."),
            HelpCard("Restart failed translations", "Bottom-card button — disabled when erroredCount == 0. Re-runs every ❌ row in this run; ✅ rows are left alone."),
            HelpCard("Start missing translations", "Adds rows for every expected source × language pair that isn't already covered by this run. Useful after a partial cancel."),
            HelpCard("Title bar — 🐞", "Wired when tracing is on and ApiTracer.getTraceFiles() finds at least one entry tagged category=Translation for this report. Opens the trace list filtered to those calls."),
            HelpCard("Title bar — ℹ️", "Wired when the providerId resolves. Jumps to Model Info for the run's translator model."),
            HelpCard("Title bar — 🗑", "Wired. Confirm dialog shows the count and language; confirming deletes every TRANSLATE row in this run and pops back.")
        )
    ),
    "translation_call" to HelpContent(
        title = "Translation call",
        cards = listOf(
            HelpCard("Overview", "Per-call detail. Title reads 'Translate · <language>'. Source pane on top (capped at half the screen), translation pane fills the rest. Both panes scroll independently."),
            HelpCard("Source label", "Provider / model when the source is an AGENT row or META row; 'Prompt' for PROMPT-source translations; the Meta prompt name as fallback for older META rows."),
            HelpCard("Source resolution", "Driven by translateSourceKind + translateSourceTargetId. PROMPT pulls report.prompt; AGENT pulls the matching agent's responseBody; META pulls the SecondaryResult's content via SecondaryResultStorage.get."),
            HelpCard("Cost line", "Below the title bar when totalCost > 0 — formatted as cents with monospace font."),
            HelpCard("Error rendering", "When the row has errorMessage, a red Error block + the message replaces both panes."),
            HelpCard("Title bar — 🐞", "Wired when tracing is on and a trace exists for (this report id, this row's translation model, closest timestamp)."),
            HelpCard("Title bar — ℹ️", "Wired when the translation provider resolves and result.model is non-blank — jumps to Model Info."),
            HelpCard("Title bar — 🔄 / 🗑", "Not wired here — re-fire / delete from the parent Translation run screen."),
            HelpCard("Tips", "The source label and the translation label are both clickable — they each invoke modelInfoClickable, opening Model Info for whichever model the panel represents.")
        )
    ),
    "translation_compare" to HelpContent(
        title = "Translation compare",
        cards = listOf(
            HelpCard("Overview", "Generic side-by-side viewer for any 'original ↔ translation' pair. Reached from the Translation info button on a translated single-agent result, or on a translated Meta secondary."),
            HelpCard("Layout", "Both panes get equal weight (1f each) — original on top in blue, translation on bottom in green, separated by a 2dp divider."),
            HelpCard("Independent scroll", "Each pane has its own verticalScroll so a long original next to a short translation (or vice versa) doesn't lock you into a shared scroll position."),
            HelpCard("Think sections", "Both panes render via ContentWithThinkSections — <think> blocks collapse so the user-readable content stays prominent."),
            HelpCard("Empty content", "A pane with blank content shows '(no content)' in tertiary text."),
            HelpCard("Title", "Caller-supplied — typically reads 'Translation info — <provider> / <model>' or includes the Meta-prompt name."),
            HelpCard("Related", "TranslationCallDetailScreen has the same split layout but caps the original at half-screen — that one is per-call; this one is for whole-document overlays.")
        )
    ),
    "translation_language" to HelpContent(
        title = "Pick target language",
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
        title = "Model response",
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
        title = "View on one page",
        cards = listOf(
            HelpCard("Overview", "Concatenates the prompt and every successful agent's response onto one scrollable page so you can scan the entire report without flipping through the agent picker."),
            HelpCard("Layout", "Title at the top (or folded into the title bar in Subject mode), then the prompt block, then one section per agent with the agent label as a sub-header and the response body underneath."),
            HelpCard("Translations", "When the report has TRANSLATE rows the page honours the active language picker on the parent screen — translated bodies overlay onto the matching agents."),
            HelpCard("Pitfalls", "Long reports render many MB of text; scrolling can be slow on dense reports. Use the per-agent picker on the Model response screen when you only need one section."),
            HelpCard("Related", "Reachable from Model response → 'View on one page' button. Cost summary is its own screen.")
        )
    ),
    "cost_view" to HelpContent(
        title = "Cost summary",
        cards = listOf(
            HelpCard("Overview", "Read-only cost table for the report — every API call counted against this report (agents + secondaries + translations) gets a row. Reached from the result page's View → Costs button."),
            HelpCard("Per-row breakdown", "Each row shows model, kind ('report' / 'rerank' / 'meta' / 'moderate' / 'translate'), input/output tokens, and a USD subtotal computed against the layered pricing lookup at view-time."),
            HelpCard("Group totals", "Tables aggregate by provider and by model so you can see which provider absorbed most of the spend; both groupings render below the per-row list."),
            HelpCard("Translation costs", "Translation calls are billed against the same model that ran them — they appear as 'translate' kind rows. The language picker is hidden in cost mode since costs aggregate every call."),
            HelpCard("Empty state", "When neither the agents nor any secondary carries a tokenUsage record, the body reads '(no usage recorded)'. This usually means the run was cancelled before the first response landed."),
            HelpCard("Pitfalls", "Costs use CURRENT pricing — if the provider changed prices since the run, the displayed cost is the today-rate, not the as-billed rate."),
            HelpCard("Related", "View → Reports for per-agent bodies; View → Prompt for the prompt itself; AI Usage (Settings → Statistics) for cumulative spend across all reports.")
        )
    ),
    "knowledge_new" to HelpContent(
        title = "New knowledge base",
        cards = listOf(
            HelpCard("Overview", "Form to create a new knowledge base. The KB binds an embedder model + a chunk strategy at creation time; both are immutable for the KB's lifetime once chunks land."),
            HelpCard("Name field", "Free-form display name. Used as the KB title in pickers and in the chat-attach dialog. Required — Create is disabled until non-blank."),
            HelpCard("Embedder picker", "Pick one provider/model for embeddings. Local embedder (LiteRT MediaPipe) is also offered when a TextEmbedder model is installed. The chosen embedder's output dimension becomes a hard invariant for every chunk in this KB."),
            HelpCard("Chunk strategy", "How source documents get split before embedding. Defaults pick token / character thresholds tuned to the chosen model's input limit; advanced fields let you override."),
            HelpCard("Create", "Creates the manifest under <filesDir>/knowledge/<id>/manifest.json. No sources yet — drill into the new KB and add documents from there."),
            HelpCard("Pitfalls", "Picking an embedder you don't actively use here means the cosine retrieval at chat-attach time has to load the embedder runtime — which can be slow and memory-heavy. Prefer your default embedder unless you have a reason."),
            HelpCard("Related", "Once created, the KB Detail screen handles ingest (Add file / paste text / share-target import) and indexing.")
        )
    ),
    "chat_search" to HelpContent(
        title = "Search chats",
        cards = listOf(
            HelpCard("Overview", "Full-text search over saved chat sessions. Reached from the Chat History list's search icon."),
            HelpCard("Search field", "Filters by anything: session title, message content, model, provider name, system prompt. The match is substring + case-insensitive."),
            HelpCard("Result rows", "One row per matching session with a content excerpt around the first match. Tap to resume the session at the matched message."),
            HelpCard("Empty state", "Empty query shows the most recent N sessions; non-matching query shows 'No chats matching <query>'."),
            HelpCard("Pitfalls", "Search reads + parses every chat session JSON on every keystroke — a debounce keeps it acceptable on slow storage but heavy histories may still feel jittery. Prefer the list's date / pinned filters when you can."),
            HelpCard("Related", "Chat History (the list) for date-ordered browsing; Manage chats for bulk delete / export.")
        )
    ),
    "models_per_provider" to HelpContent(
        title = "Provider — Models",
        cards = listOf(
            HelpCard("Overview", "Per-provider model list for one provider. Reached from AI Setup → Providers → <provider> → Models. The all-providers Models hub is a different screen."),
            HelpCard("Source picker", "API / Manual chips at the top. API mode pulls models from the provider's catalog endpoint; Manual mode lets you paste / curate a fixed list (one model id per line)."),
            HelpCard("API mode list", "Shows what the last Fetch returned — the same list every model picker uses. Models known to be stale (LiteLLM has fresher metadata) get a tiny badge."),
            HelpCard("Manual mode editor", "Add lines from the multi-line input + Add button; tap a row to drop it back into the editor for tweaking."),
            HelpCard("Auto-save", "Edits land via the Settings save lambda as you go — no separate Save button. The screen drops local mirror state when switching modes so half-typed values don't stick."),
        )
    ),
    "prompt_view" to HelpContent(
        title = "Prompt view",
        cards = listOf(
            HelpCard("Overview", "Read-only viewer for the report's prompt as it was actually saved. Reached from the result page's View → Prompt button."),
            HelpCard("Translated prompt", "When the report has a TRANSLATE row whose translateSourceKind is PROMPT, the language picker at the top lets you flip between the original and the translated body."),
            HelpCard("Empty state", "When report.prompt is blank, the screen shows '(no prompt recorded)' in tertiary grey."),
            HelpCard("Layout", "Single column with verticalScroll — long prompts scroll naturally."),
            HelpCard("Use it for", "Verifying what the model actually saw when results look surprising — variables and any user-tag block from <user>...</user> append are visible."),
            HelpCard("Pitfalls", "The screen renders the saved prompt — if you Edit prompt and don't Regenerate, the new prompt shows here but agents weren't re-run with it. The result page's pending-changes banner reminds you."),
            HelpCard("Related", "View → Reports for the per-agent body picker; View → Costs for the cost table.")
        )
    ),
    "history" to HelpContent(
        title = "History",
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
        title = "Prompt History",
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
    "example_prompt_picker" to HelpContent(
        title = "Pick an example prompt",
        cards = listOf(
            HelpCard("Overview", "Reached from AI Reports → Start with an example prompt. Lists every Example prompt in your library, sorted alphabetically by title. Tap a row to open New Report seeded with the prompt's (title, text)."),
            HelpCard("Search field", "Filters by title OR text (case-insensitive). The trailing ✕ clears."),
            HelpCard("Per-row content", "Title in white; the first line of the text dimmed underneath."),
            HelpCard("Empty state", "Shown when no example prompts exist. Curate them under AI Setup → Prompt management → Example prompts, or load the bundled set via Housekeeping → Prompts → Add new prompts from assets/examples.json."),
            HelpCard("Related", "Prompt history (Start with a previous prompt) is the per-run history of titles you've actually sent; Example prompts are a separate user-curated starter library.")
        )
    )
,
    "chat_hub" to HelpContent(
        title = "AI Chat",
        cards = listOf(
            HelpCard("Overview", "Landing screen for everything chat-shaped. Top section starts a new conversation; below it, pinned and recent sessions plus tools to continue, search, or manage existing chats."),
            HelpCard("Unfinished pill", "When at least one chat ended on a user turn with no assistant reply (you navigated away mid-stream), an envelope pill appears at the very top with a Resume link to the most recent such session."),
            HelpCard("Start card", "Four entry points stacked in one card: New Chat with Agent (greyed when no agent has a key + active provider), New Chat – Configure On The Fly (pick provider/model/parameters at start), Dual AI Chat (two models trade turns), and Start with photo (camera capture, image rides into the first user turn)."),
            HelpCard("Continue Existing Chat", "Opens the full chat-history list. Disabled and dimmed when no sessions exist yet. Pinned + Recent below give you a faster jump for the top sessions."),
            HelpCard("Chat with a local LLM", "Only shown when at least one .task model is installed in filesDir/local_llms/. Tapping with one model installed jumps straight to the session; with two or more, a dropdown appears so you pick which to load."),
            HelpCard("Pinned and Recent cards", "Pinned holds every session you marked with the pin chip; Recent shows the next three by updated time. Each row is the first user-message preview; tap to resume."),
            HelpCard("Search Chats", "Free-text search across every saved message — opens the dedicated search screen."),
            HelpCard("Manage", "Bulk-prune by age and zip-export of every chat-history JSON. Disabled when no chats exist."),
            HelpCard("Title-bar icons", "Only the always-on Help (?) and Home icons. No reload, info, delete, or trace from this hub — those live on the per-session screens."),
            HelpCard("Tips", "The hub re-reads chat history on every resume tick, so deleting / pinning a session elsewhere shows up when you come back. The camera capture clears any previous photo error before launching.")
        )
    ),
    "chat_session" to HelpContent(
        title = "Chat",
        cards = listOf(
            HelpCard("Overview", "Live single-model conversation. Messages stream chunk-by-chunk; the input box clears the moment Send fires; the assistant bubble bottom-anchors so short conversations sit just above the input row instead of pinned at the top."),
            HelpCard("Header row", "Provider / model label is clickable and opens Model Info. To its right: a Knowledge chip (only when at least one KB exists) and a Pin chip. The pin state is written to the session record immediately, so the hub reflects it without waiting for the next message save."),
            HelpCard("Knowledge chip", "Multi-select dialog over saved KBs. Once one KB is checked, every other KB whose embedder differs becomes greyed out — the retriever embeds the query with the first attached KB's embedder and would silently drop the rest. Clearing the selection re-enables every row."),
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
        title = "Chat Parameters",
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
        title = "Chat History",
        cards = listOf(
            HelpCard("Overview", "Paged list of every saved chat session. Each row shows first user-message preview, provider · model, and last-updated date. Tap a row to resume that session."),
            HelpCard("Pagination", "Page size auto-fits the screen height (rows of ~80dp). Previous / Next buttons sit above and below the list with the current page indicator and total chat count between them."),
            HelpCard("Trace icon per row", "🐞 appears between the row and the > caret when at least one chat-turn trace was tagged with this sessionId. Tap it to open the full trace list filtered to this session."),
            HelpCard("Empty state", "When ChatHistoryManager has no sessions, the screen renders \"No chat history yet\" centered — the Continue Existing Chat card on the hub is also disabled in this state."),
            HelpCard("Resume behaviour", "Tapping a row navigates to AI_CHAT_CONTINUE/{sessionId}, which reopens the same ChatSessionScreen with the stored messages, parameters, attached KBs, pinned flag, and persisted web-search / reasoning toggle."),
            HelpCard("Title-bar icons", "Help and Home only. Bulk delete is on the dedicated Manage screen reachable from the hub, not from here."),
            HelpCard("Tips", "The list re-fetches whenever ChatHistoryManager.historyVersion ticks (after a save / delete / pin elsewhere). Page index is rememberSaveable so it survives a config change."),
            HelpCard("Pitfalls", "The trace probe runs once per row — if you record a new trace for an open session, the icon doesn't appear until you leave and return."),
            HelpCard("Related", "Use Search Chats from the hub to find a specific message; use Manage chats for bulk delete by age or full export.")
        )
    ),
    "chat_continue" to HelpContent(
        title = "Chat",
        cards = listOf(
            HelpCard("Overview", "Same screen as Chat session, but seeded with the stored messages, parameters, knowledge attachments, pinned flag, and persisted web-search / reasoning effort from the saved session record."),
            HelpCard("State you keep", "ChatHistoryManager.loadSession brings back: every message (system prompt repinned in place if Parameters changed), the original ChatParameters, the per-session knowledgeBaseIds, and the pinned flag."),
            HelpCard("Toggles you can flip", "Web search and reasoning effort chips are read from the persisted ChatParameters and are saved back on every turn — the next save uses your current chip state, not the original preset."),
            HelpCard("System prompt update", "If the underlying Settings system-prompt template changed since you last opened the session, the system message is rewritten in-place on the next turn so the new prompt takes effect."),
            HelpCard("Session id", "AI_CHAT_CONTINUE/{sessionId} carries the id; the screen treats it as the current session id so saves overwrite the same record. New messages append to the existing JSON."),
            HelpCard("Title-bar icons", "Same as a fresh chat — ℹ️ jumps to Model Info, Help and Home are always present. The Back button shows the running cost-in-cents for this session next to it."),
            HelpCard("Tips", "Trace icons on existing assistant bubbles probe the on-disk trace store by closest timestamp + same model + no reportId; old assistant turns from before tracing was on still have no icon."),
            HelpCard("Pitfalls", "Switching the underlying model is not supported mid-session — start a new chat instead. Editing a Parameters preset elsewhere doesn't migrate into the resumed session because the persisted ChatParameters record was built at Start Chat time."),
            HelpCard("Related", "Use the Pin chip to keep a frequently-resumed session at the top of the hub. Use Continue in chat from a Report to start a fresh chat that inherits a multi-agent run.")
        )
    ),
    "chat_manage" to HelpContent(
        title = "Manage chats",
        cards = listOf(
            HelpCard("Overview", "Two housekeeping actions for chat history: bulk-delete sessions older than N days, and zip-export every chat-history JSON for backup or sharing."),
            HelpCard("Delete old chats card", "Number-only field for the cutoff (max 4 digits). Pinned chats are skipped; the helper line under the field reminds you. Defaults to 30 days; the Delete button is enabled only when the value parses to a positive integer."),
            HelpCard("Confirm dialog", "Loads matching sessions off the UI thread and shows the actual count: \"Delete N chats?\". Title displays \"Loading…\" briefly while the scan runs (long histories can take a moment)."),
            HelpCard("Export all card", "Zips every file in filesDir/chat-history/ into a timestamped archive (ai_chats_backup_YYMMDD_HHMMSS.zip) under cacheDir, then opens the system share sheet via FileProvider. Status line below shows the chat count once bundled."),
            HelpCard("Status line", "After any operation, a single status string at the bottom: \"Zipping chats…\" / \"Bundled N chats.\" / \"Deleted N chats.\" / \"Nothing to export.\""),
            HelpCard("Title-bar icons", "Help and Home only — no per-screen reload, info, delete, or trace icons here."),
            HelpCard("Tips", "The Working… button label tells you when an export is in flight; the Delete button is disabled at the same time, so you can't kick off a parallel scan."),
            HelpCard("Pitfalls", "Pinned chats are excluded from the bulk delete — to remove a pinned chat you have to unpin it from inside the session first. Deletes are immediate and cannot be undone."),
            HelpCard("Related", "Whole-app backup including chats lives under AI Setup → Backup & restore. Use Search Chats from the hub if you only want to find an old message rather than prune.")
        )
    ),
    "dual_chat_setup" to HelpContent(
        title = "Dual AI Chat",
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
        title = "Dual Chat",
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
    "knowledge_list" to HelpContent(
        title = "AI Knowledge",
        cards = listOf(
            HelpCard("Overview", "Lists every saved knowledge base. KBs are RAG corpora that can be attached to a Report or Chat session to inject relevant excerpts before each call. The header line is the one-paragraph reminder of what KBs are for."),
            HelpCard("Shared-content banner", "When you arrive here from the share-target chooser with files / URLs queued, a green sticky banner counts the pending items and explains: pick an existing KB to ingest there, or create a new one. \"Discard share\" abandons the queue."),
            HelpCard("+ New knowledge base", "Green button opens the wizard — name + embedder picker. Embedder choices fold local TextEmbedder .tflite files in filesDir/local_models/ together with every (provider, model) marked EMBEDDING from active providers."),
            HelpCard("KB cards", "Each card shows the KB name, embedder line (\"Local · model\" or \"Provider · model\"), and \"N sources · M chunks\". Tap to drill into the detail screen."),
            HelpCard("Empty state", "When KnowledgeStore lists nothing: \"No knowledge bases yet.\" Tap + New knowledge base to create one."),
            HelpCard("Title-bar icons", "Help and Home only on the list and on the new-KB wizard. Per-KB delete is on the detail screen."),
            HelpCard("Tips", "List re-keys on each ON_RESUME tick — re-indexing or deleting a KB elsewhere shows up when you return. The wizard's empty embedder list points you at Housekeeping → Local LiteRT models or AI Setup → Manual model types overrides."),
            HelpCard("Pitfalls", "Embedder is fixed for the lifetime of the KB — there is no migration path. If you change your mind, create a new KB and re-ingest."),
            HelpCard("Related", "Multi-select KB attach lives on the chat session header (📚) and on the New Report screen. The retriever uses the first attached KB's embedder; mismatched embedders are skipped silently.")
        )
    ),
    "knowledge_detail" to HelpContent(
        title = "Knowledge base",
        cards = listOf(
            HelpCard("Overview", "Per-KB workspace. Add file or URL sources, see the chunk count, re-index a single source, or delete the whole KB. Indexing runs in the screen's coroutine scope so the status line updates live as the import happens."),
            HelpCard("Header", "Title is the KB name. Below: embedder line (provider · model, monospace) and \"N sources · M chunks\". The trash icon in the title bar deletes the entire KB after a confirm dialog."),
            HelpCard("+ File", "Opens the SAF picker scoped to PDF, plain/markdown text, DOCX, ODT, XLSX, ODS, CSV/TSV, and JPG/PNG. File type is detected by extension first, MIME second, defaulting to TEXT."),
            HelpCard("+ Web page", "URL input field below the file row. Trim + non-blank gates the button. KnowledgeService.indexUrl runs in IO, posts progress to status, and disables the buttons while it works."),
            HelpCard("Status line", "Reflects the current step: \"Reading X…\" / \"Fetching X…\" / \"Indexed name (N chunks)\" / \"Failed: …\" — and per-batch progress messages from the embedder for chunked sources."),
            HelpCard("Sources list", "Each row shows source name, type, chunk count, and any error (red). Per-row Re-index re-runs the same extractor; per-row Delete drops the source via KnowledgeStore.deleteSource and refreshes the list."),
            HelpCard("Auto-ingest from share", "When pendingUris arrives from the share-target queue, the screen auto-imports each item once the KB has loaded — http(s) URLs go through indexUrl, content:// URIs go through pickTypeForUri + indexFile. The queue is cleared via onConsumePending so a back-and-forward doesn't re-import."),
            HelpCard("Title-bar icons", "🗑 deletes the whole KB (confirm dialog: \"Removes the manifest, every source, and every chunk. Cannot be undone.\"). Help and Home are always present."),
            HelpCard("Tips", "URL input clears itself on a successful indexUrl. Buttons disable while a working flag is set so you can't queue parallel imports."),
            HelpCard("Pitfalls", "Re-index hits the same upstream — for a flaky URL it may fail again. The error message is preserved on the source row in red until you re-index successfully or delete it.")
        )
    ),
    "models_search" to HelpContent(
        title = "Models",
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
        title = "Model Info",
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
        title = "Select Provider",
        cards = listOf(
            HelpCard("Overview", "Full-screen provider picker. Lists every AppService (or only active services when activeOnly is set). Tap to confirm; back exits without choosing."),
            HelpCard("State emoji", "Each row shows the display name plus a small state emoji: 🔑 ok, ❌ error, 💤 inactive, ⭕ untested."),
            HelpCard("Search", "Filters by display name. Result count line shows '<filtered> of <total> providers'."),
            HelpCard("Pitfalls", "Inactive providers are hidden when activeOnly is set (the chat / dual-chat flows). To pick from an inactive provider you have to activate it first in AI Setup."),
            HelpCard("Related", "Picking a provider here typically routes to the Model picker for that provider next.")
        )
    ),
    "model_pick_model" to HelpContent(
        title = "Select Model",
        cards = listOf(
            HelpCard("Overview", "Full-screen model picker for a chosen provider. Pricing columns on the right (In $/M, Out $/M) read from settings overrides first, then PricingCache. Vision / web / reasoning badges sit between the model name and the price columns."),
            HelpCard("Initial refresh", "For API-mode providers with onRefresh wired, the screen kicks a fetch on entry, waits up to 15 s for it to complete, then reveals the list. Stalled fetches unveil whatever was previously cached so you're never stuck staring at a spinner."),
            HelpCard("Default option", "When showDefaultOption is on (per-provider settings reuse), the list starts with a \"Default (use provider setting)\" row that selects the empty-string sentinel."),
            HelpCard("Open Models button", "Visible when onNavigateToProviderModels was wired (typically inside provider edit). Jumps into the rich Models browser with this provider preselected."),
            HelpCard("Fetch error row", "When the provider's last fetch failed, a red error line appears under the search box with a 🐞 link to the captured trace (when API tracing is on)."),
            HelpCard("Title-bar icons", "Help and Home only. Refresh is automatic; manual refresh is on the per-provider settings screen."),
            HelpCard("Tips", "Local LLM models come from filesDir/local_llms/ via LocalLlm.availableLlms — the synthetic LOCAL provider's model list isn't stored in ProviderConfig.models.")
        )
    ),
    "model_pick_agent" to HelpContent(
        title = "Select Agent",
        cards = listOf(
            HelpCard("Overview", "Full-screen agent picker. Reads aiSettings.agents directly — every saved agent appears as a row."),
            HelpCard("Result rows", "Agent name + provider/model line + per-million-token pricing. Pricing badge is red on real source data, grey on DEFAULT_PRICING fallback."),
            HelpCard("Search", "Matches agent name, provider name, or effective model. Result count line shows '<filtered> of <total> agents'."),
            HelpCard("Empty state", "When no agents are configured yet, the body is empty. Add agents under AI Setup → Agents."),
            HelpCard("Title-bar icons", "Help and Home only.")
        )
    ),
    "model_raw" to HelpContent(
        title = "Info provider (source detail)",
        cards = listOf(
            HelpCard("Overview", "Pretty-printed JSON view of one of the seven info providers for a single model. Reached from the Sources buttons on Model Info."),
            HelpCard("Layout", "Title bar reads \"Info provider\". Below it, in green, the provider's display name (LiteLLM / OpenRouter / …) and beneath that the actual URL the app called. Then a single full-screen card containing the JSON, scrollable in both axes — long lines aren't wrapped or truncated."),
            HelpCard("JSON colouring", "Keys blue, strings green, numbers orange, true/false purple, null grey, punctuation white. Falls back to plain white for non-JSON inputs (e.g. a \"(no data)\" placeholder)."),
            HelpCard("Title bar — ℹ️", "Opens the help page for the info provider this view belongs to (e.g. tapping ℹ️ on the LiteLLM source detail opens the LiteLLM help topic). The same destination is also reachable by tapping the green provider name on the home Help page's Info-providers table."),
            HelpCard("Title bar — ❓", "Opens this page (help for the source-detail screen)."),
            HelpCard("Show all", "When opened from the Show all button on Model Info, the body concatenates every source's raw JSON with === Source === headers between sections. Title bar drops the green name + URL and shows the legacy \"All sources · model\" title; the ℹ️ icon is hidden because there's no single provider to point at."),
            HelpCard("Tips", "The JSON is pre-pretty-printed via createAppGson(prettyPrint = true). Field-name colouring relies on a string being followed by ':' after whitespace, which means escape sequences inside strings won't accidentally re-tokenise."),
            HelpCard("Related", "Capability and pricing rows on Model Info already distill the most useful fields out of these dumps — only dive into the raw view when something looks off or you want to file a catalog issue.")
        )
    ),
    "search_local" to HelpContent(
        title = "Extended local search",
        cards = listOf(
            HelpCard("Overview", "On-device keyword search across saved reports. The query is split on whitespace; each token is matched case-insensitively against title + prompt + every successful agent's response body. Score = total token occurrences."),
            HelpCard("Query field", "Multi-line, up to 3 lines. Whitespace-tokenised — multi-word queries become AND-of-substrings with score summed. No regex."),
            HelpCard("Search button", "Purple. Disabled until the query is non-blank and no run is in flight. Label flips to \"Searching…\" while running."),
            HelpCard("Status line", "Single line under the button: \"Searching…\", \"No matches.\", or \"N results\"."),
            HelpCard("Result rows", "Title (white, bold), date (yyyy-MM-dd HH:mm), and the integer score on the right in blue. Tap to open the report. Top 25 only — sorted by score desc, then timestamp desc."),
            HelpCard("Title-bar icons", "Help and Home only. No trace icon — every byte stays on-device, there's nothing to record."),
            HelpCard("Tips", "Search runs entirely on the device — no API calls, no key required. Useful even when offline."),
            HelpCard("Pitfalls", "Tokens are matched as substrings, so very short tokens (\"ai\", \"the\") will inflate scores via incidental matches. Use longer or more distinctive terms when you need precision."),
            HelpCard("Related", "Use Quick local search for a single-substring filter (no scoring), or Semantic search / Local semantic search for embedding-based similarity instead of keyword matching.")
        )
    ),
    "search_semantic" to HelpContent(
        title = "Semantic search",
        cards = listOf(
            HelpCard("Overview", "Embedding-based similarity search across saved reports. The user picks an embedding-typed model from any active OpenAI-compatible provider; query and reports are embedded, scored by cosine, top 10 returned."),
            HelpCard("Empty state", "When no provider has an embedding-typed model, an inline panel points you at AI Setup → Manual model types overrides or fetching a provider whose list contains text-embedding-3-small."),
            HelpCard("Model picker", "Dropdown lists every (active OpenAI-compatible service, model marked EMBEDDING) pair. Label uses the project's \"Model name layout\" setting via modelLabel."),
            HelpCard("Query field", "Up to 3 lines, multi-line. Submitted whole — not tokenised. The text becomes a single embedding vector compared against report vectors."),
            HelpCard("Search behaviour", "Embeds the query first; then walks every report, building a representative text from title + prompt + first 2k characters of the first non-blank agent response. Cached vectors (keyed on doc id, provider, model, content hash) are reused; new ones are batched in groups of 50."),
            HelpCard("Status line", "Live progress: \"Indexing reports… i / N\" while scanning, then \"Embedding batch X / Y (Z reports)\" while sending. Final state is the result count or \"No matches.\""),
            HelpCard("Result rows", "Title, timestamp, and the cosine score (3 decimals) in blue. Top 10, sorted descending; rows with score ≤ 0 are dropped."),
            HelpCard("Title-bar icons", "Help and Home only."),
            HelpCard("Tips", "Edit a report and a fresh content hash means the next run re-embeds it automatically — caching is correct across edits, not just identity. The 50-per-batch limit fits all observed providers."),
            HelpCard("Pitfalls", "Costs scale with report count on first run for a new model; subsequent runs hit the cache. Switching embedding model invalidates the cache for that model only — vectors from the old model are still on disk and reused if you switch back."),
            HelpCard("Related", "Local semantic search uses an on-device .tflite encoder for the same workflow with no network calls and no per-call cost.")
        )
    ),
    "search_quick" to HelpContent(
        title = "Quick local search",
        cards = listOf(
            HelpCard("Overview", "The cheapest of the search variants — single substring match (case-insensitive) against report prompt and every successful agent response. No tokenisation, no scoring; a report is either a hit or it isn't. Results sorted by recency."),
            HelpCard("Word field", "Single-line input labelled Word. Used as one substring — short whitespace phrases work as a single literal."),
            HelpCard("Search button", "Purple. Disabled until the field is non-blank and no run is in flight. Label flips to \"Searching…\" while running."),
            HelpCard("Status line", "\"Searching…\", \"No matches.\", or \"N results\". No score is shown — every hit is binary."),
            HelpCard("Result rows", "Title and timestamp only. Tap to open the report. Sorted by timestamp desc."),
            HelpCard("Title-bar icons", "Help and Home only."),
            HelpCard("Tips", "Faster than Extended local search for narrow queries — no per-token scoring loop, no top-N truncation. Returns every hit."),
            HelpCard("Pitfalls", "Title is not searched (only prompt + responses). For a title query, use Extended local search instead."),
            HelpCard("Related", "Extended local search adds whitespace tokenisation and an occurrence score; semantic searches do similarity matching beyond literal substring.")
        )
    ),
    "search_local_semantic" to HelpContent(
        title = "Local semantic search",
        cards = listOf(
            HelpCard("Overview", "Semantic search using an on-device LiteRT (MediaPipe Tasks Text Embedder) model. Same workflow as the cloud Semantic search but every embedding stays on the phone."),
            HelpCard("Empty state", "When no .tflite is installed in filesDir/local_models/, the inline panel points you at AI Housekeeping → Local LiteRT models — that screen handles download / import / remove."),
            HelpCard("Model picker", "Dropdown over availableModels — whatever .tflite files live in filesDir/local_models/. Pre-selected to the first one on entry."),
            HelpCard("Query + Search", "Multi-line query, purple Search button, same disabled gating as the other search screens. The button label flips to \"Searching…\" during a run."),
            HelpCard("Status line + 🐞", "Live status: \"Indexing reports… i / N\" then \"Embedding batch X / Y (Z reports)\". After a run completes, a 🐞 next to the status line opens the most recent \"Local semantic search\" trace (synthetic, hostname \"local\")."),
            HelpCard("Caching", "Embeddings persist via EmbeddingsStore keyed on report id + provider \"LOCAL\" + model name + content hash. Re-runs hit the cache; edited reports re-embed automatically."),
            HelpCard("Result rows", "Title, timestamp, cosine score (3 decimals) in blue. Top 10, scores > 0, sorted desc."),
            HelpCard("Title-bar icons", "Help and Home only — the 🐞 lives next to the status line, not in the title bar."),
            HelpCard("Tips", "ApiTracer writes a synthetic trace entry tagged \"Local semantic search\" so the Trace screen lights up the same way it does for HTTP-backed embedders. The MediaPipe TextEmbedder embeds one string per call internally — \"batch of 50\" is just to reduce the trace count."),
            HelpCard("Pitfalls", "Switching to a different .tflite invalidates the cache for that model — first run after a switch re-embeds every report."),
            HelpCard("Related", "Cloud Semantic search does the same job using a remote provider's embedding endpoint. Quick local / Extended local searches do non-semantic substring matching.")
        )
    ),
    "share_target" to HelpContent(
        title = "Send to AI",
        cards = listOf(
            HelpCard("Overview", "Lightweight chooser shown when another app shares content into this app via ACTION_SEND. Lives between the receiving Activity and the standard nav graph; a card tap clears the share state and routes the payload."),
            HelpCard("Preview card", "Top of the screen: shared subject (when present and non-blank), first 300 characters of shared text (with ellipsis when truncated), \"N attachments\" line for any URIs, and the raw mime type. Lets you double-check the payload before picking a destination."),
            HelpCard("New Report card", "📝. Routes to the New Report flow — text becomes the prompt, the first image attaches for vision, non-image files queue for one-tap auto-attach as a knowledge base. Greyed when there's neither text nor URIs."),
            HelpCard("New Chat card", "💬. Opens a fresh chat session with the shared text staged as the first user turn. Greyed when no text was shared."),
            HelpCard("Add to Knowledge card", "📚. Routes to the Knowledge list with the URIs / URL pre-staged in the share-target queue; the queue is consumed by either picking an existing KB or creating a new one. Greyed when there's no URI and the shared text isn't a URL."),
            HelpCard("Cancel", "Back / system back fires onCancel which discards the share without routing. The chooser doesn't add itself to the regular back stack."),
            HelpCard("Title-bar icons", "Help and Home only. Help points to this entry; Home aborts the chooser."),
            HelpCard("Tips", "Cards stay tappable even when the payload is \"weak\" for that route — only-image-shared can still go to Report (vision attach), only-text-shared can still go to Knowledge (paste-in URL or note). The receiving callbacks do the heavier validation."),
            HelpCard("Pitfalls", "Multiple shared images: only the first attaches to a chat or report; the rest are dropped on those routes. Use Add to Knowledge if you want to ingest several files at once.")
        )
    )
,
    "settings_main" to HelpContent(
        title = "Settings",
        cards = listOf(
            HelpCard("Overview", "General app preferences plus the entry point to AI Setup. Edits autosave with a 400 ms debounce, so you don't need a Save button — just type and back out."),
            HelpCard("Identity", "Two text fields — Name and Email address — combined in one card. Name surfaces wherever the app addresses you and defaults the From: header on email-style exports. Email address pre-fills the To: field on report email exports; leave blank to be prompted each time."),
            HelpCard("API tracing", "Master switch. Off → no new trace files are written, the Hub's AI API Traces card and every 🐞 ladybug icon across result screens disappears. On → every API request and response is captured to disk."),
            HelpCard("Model name layout", "Two radio options. Model name only is the dense default. Provider and model name joins the provider's display name and the model id with \" · \" — useful when you run the same model on multiple providers."),
            HelpCard("Subject to title bar", "Compact-header mode. Detail screens normally show a fixed title in the top bar (\"Model Info\" / \"Trace detail\" / …) plus a green page-subject line below it (the model name, KB name, target language, …). Three radio options: Hardcoded screen title (legacy two-row layout); Dynamic subject name (subject in the bar, green line hidden); Both (\"<fixed> / <subject>\" in the bar, green line hidden)."),
            HelpCard("Show < Back", "When off the visible back chevron disappears from every TitleBar and the title left-aligns. System / gesture back keeps working — TitleBar's BackHandler is registered independently."),
            HelpCard("Tips", "Settings has no Save button on purpose — every keystroke restarts the 400 ms debounce timer. If you tap Back fast, the latest values still flush to disk."),
            HelpCard("Related", "Tap the AI Setup row (when present elsewhere) for the rest of the configuration; Housekeeping → Reset application reverts everything here to factory defaults.")
        )
    ),
    "settings_setup" to HelpContent(
        title = "AI Setup",
        cards = listOf(
            HelpCard("Overview", "Top-level hub for AI configuration. Each card opens a sub-hub or list — counts on the right show how many entries you have so you can tell at a glance what is configured."),
            HelpCard("Providers", "Per-provider API keys, state, default model, and the catalog editor. Count = number of registered providers (39 ship by default plus any you have added)."),
            HelpCard("Models", "Sub-hub: per-provider Models, Model Types (default API paths), and Manual model types overrides. Count = total models across active providers only."),
            HelpCard("Workers", "Sub-hub for Agents, Flocks, Swarms. Disabled until at least one provider has an API key. Count = active agents + flocks + swarms."),
            HelpCard("Prompt management", "Sub-hub: System Prompts plus the four Internal Prompts category buckets (Meta / Fan-out / Fan-in / Other). Count = system prompts + internal prompts."),
            HelpCard("Parameters", "Direct CRUD for parameter presets (temperature, max tokens, system prompt, web-search flags, reasoning effort)."),
            HelpCard("Costs", "Opens the manual cost-override list. Count = number of manual override entries currently saved."),
            HelpCard("External Services", "HuggingFace, OpenRouter, Artificial Analysis API keys. Count = number of those keys that are non-blank."),
            HelpCard("Local Models", "Sub-hub for on-device .task LLMs and .tflite LiteRT embedders. Count = installed total across both runtimes."),
        )
    ),
    "agents" to HelpContent(
        title = "Agents",
        cards = listOf(
            HelpCard("Overview", "CRUD list of named agent configurations. Each agent ties a provider, model, optional API key override, optional endpoint, parameters preset(s), and system prompt into one reusable unit."),
            HelpCard("Visible entries", "Only agents whose provider is currently active (state == \"ok\") show up — agents bound to inactive or keyless providers are hidden, not deleted."),
            HelpCard("Item rows", "Show name in white, then \"Provider · effective model\" beneath. Tap a row to edit; tap the row's trash to delete (after a confirmation dialog scoped to entity type \"Agent\")."),
            HelpCard("Add Agent", "Top button opens the Edit screen with empty fields and the first active provider preselected as the default."),
            HelpCard("Tips", "Sort is alphabetical by name (case-insensitive)."),
            HelpCard("Pitfalls", "If your provider list is fresh and nothing is active, this list will appear empty even though you have agent rows saved — set up a provider key first."),
            HelpCard("Related", "Flocks bundle agents into groups; Swarms group provider/model pairs (no agent indirection); Refresh → Default agents seeds one agent per active provider automatically.")
        )
    ),
    "agents_list" to HelpContent(
        title = "Agents",
        cards = listOf(
            HelpCard("Overview", "List of every saved agent. An agent pairs a provider, model, optional API key override, optional endpoint, parameter presets, and a system-prompt preset — one tappable unit you can add to a report, flock, or swarm."),
            HelpCard("Add Agent", "Top button opens the editor with a blank agent. Name must be unique among existing agents (case-insensitive)."),
            HelpCard("Row tap", "Opens the agent's edit screen."),
            HelpCard("Row subtitle", "Provider · model · optional endpoint label. Provider names of inactive providers stay listed here — the report-run dispatch skips them, but you still need to be able to edit / delete the row."),
            HelpCard("Per-row 🗑", "Confirms then removes the agent. Existing references from flocks / swarms / saved reports become broken — fix them up afterwards."),
            HelpCard("Empty state", "No agents yet — tap Add Agent to create the first one."),
            HelpCard("Related", "Flocks group agents for one-tap inclusion; Swarms group raw (provider, model) pairs without per-agent presets.")
        )
    ),
    "agent_edit" to HelpContent(
        title = "Agent edit",
        cards = listOf(
            HelpCard("Overview", "Form for one agent. All fields autosave on Create / Save — there is no per-field autosave here. Cancel = system back."),
            HelpCard("Agent name", "Required, must be unique among other agents (case-insensitive). The Save / Create button stays disabled until the name validates."),
            HelpCard("Provider / Model", "Open full-screen pickers via the two outlined buttons. Switching provider clears the model field; \"(default)\" means \"use the provider's default model\" at run time."),
            HelpCard("API Key", "Optional. Overrides the provider's saved key for this agent only. Leave blank to use the provider key. Editing it clears any earlier test result."),
            HelpCard("Endpoint", "Dropdown surfaces only when the provider has more than the Default endpoint or when LiteLLM lists extra paths for the picked model. Picking a LiteLLM-derived path materialises a real Endpoint entry on the provider via onAddEndpoint."),
            HelpCard("System Prompt / Parameters", "Two side-by-side buttons opening selector dialogs. Selected presets show in purple-tinted state. A red ⚠ banner appears beneath when LiteLLM reports the chosen model does not accept system messages."),
            HelpCard("Test Agent", "Visible only when an API key (override or provider) exists. Runs a real API call; on success shows green \"Success\", on failure red error text. The 🐞 next to the result deep-links to the captured trace."),
            HelpCard("Pitfalls", "If you flip provider after picking a model, the model resets — by design, since model ids rarely match across providers."),
            HelpCard("Related", "Tap Provider on a Trace Detail screen to jump back to the matching agent; the Edit Provider screen auto-creates a default agent when you change its default model.")
        )
    ),
    "flocks" to HelpContent(
        title = "Flocks",
        cards = listOf(
            HelpCard("Overview", "CRUD list of Flocks — named groups of Agents, plus optional shared parameters and system prompt. The Report builder uses a flock to fan one prompt out across several agents in a single tap."),
            HelpCard("Item rows", "Show name in white plus a count and comma-separated list of contained agents (active providers only). Tap a row to edit, trash to delete with confirmation."),
            HelpCard("Add Flock", "Opens the editor blank. The Save / Create button stays disabled until the flock has both a unique name and at least one selected agent."),
            HelpCard("Default agents flock", "Refresh → Default agents creates / maintains a flock named exactly DEFAULT_AGENTS_FLOCK_NAME — do not rename it manually unless you know what you are doing."),
            HelpCard("Tips", "Flocks are sorted alphabetically; the subtitle truncates long member lists, so the Edit screen is the canonical view."),
            HelpCard("Pitfalls", "Deleting a flock does not delete the agents it referenced — agents are owned at a higher level."),
            HelpCard("Related", "Refresh → Default agents auto-builds a default flock; Reports → New report → + Flock pulls from this list.")
        )
    ),
    "flocks_list" to HelpContent(
        title = "Flocks",
        cards = listOf(
            HelpCard("Overview", "List of every saved flock. A flock is a named bundle of agents — pinning a system-prompt preset / parameter preset at flock level overrides the agent-level equivalents at report-run time."),
            HelpCard("Add Flock", "Top button opens the editor with a blank flock. Name must be unique."),
            HelpCard("Row tap", "Opens the flock's edit screen — change name, members, optional flock-level presets."),
            HelpCard("Row subtitle", "Comma-joined agent names. Inactive-provider agents stay in the list so you can fix things up — expandFlockToModels skips them when the report actually runs."),
            HelpCard("Empty state", "No flocks yet — tap Add Flock to create the first one."),
            HelpCard("Related", "Agents (members), Swarms (raw provider/model groups), Parameters / System Prompts (presets you can pin on the flock).")
        )
    ),
    "flock_edit" to HelpContent(
        title = "Flock edit",
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
        title = "Swarms",
        cards = listOf(
            HelpCard("Overview", "CRUD list of Swarms — groups of (provider, model) pairs WITHOUT going through Agents. Use a swarm when you want to fire the same prompt at, say, GPT-5 / Claude / Gemini in parallel without creating individual agent rows."),
            HelpCard("Item rows", "Show name plus \"N members: provider/model, provider/model, …\" filtered to active providers. Tap to edit; trash to delete with confirmation."),
            HelpCard("Add Swarm", "Opens the editor blank. Create / Save stays disabled until the name is valid AND at least one member is selected."),
            HelpCard("Tips", "Members of inactive providers stay in the swarm but won't run — fix the provider state and they re-light without re-editing."),
            HelpCard("Difference from Flocks", "Flocks reference agents (and inherit each agent's API key / endpoint / prompts). Swarms hold raw provider+model tuples and use the provider's saved key / endpoint, plus optional swarm-level system prompt and parameters."),
            HelpCard("Pitfalls", "There is no per-member API key override — for that, build agents and a flock instead."),
            HelpCard("Related", "Reports → New report → + Swarm pulls from this list.")
        )
    ),
    "swarms_list" to HelpContent(
        title = "Swarms",
        cards = listOf(
            HelpCard("Overview", "List of every saved swarm. A swarm is a named bundle of raw (provider, model) pairs — structural, with no per-agent presets. Use a swarm when you want N specific models on a report without configuring agents."),
            HelpCard("Add Swarm", "Top button opens the editor with a blank swarm. Name must be unique."),
            HelpCard("Row tap", "Opens the swarm's edit screen — change name, add / remove (provider, model) members."),
            HelpCard("Row subtitle", "Comma-joined provider/model pairs."),
            HelpCard("Empty state", "No swarms yet — tap Add Swarm to create the first one."),
            HelpCard("Related", "Agents / Flocks for grouped configured workers; the +Swarm button on New AI Report adds every member of a swarm in one tap.")
        )
    ),
    "swarm_edit" to HelpContent(
        title = "Swarm edit",
        cards = listOf(
            HelpCard("Overview", "Edit one swarm. Top row holds the name + Save / Create button. Save activates once the name is valid and at least one member is added."),
            HelpCard("System Prompt / Parameters", "Optional shared bundle applied to every member at run time. Buttons go purple when populated."),
            HelpCard("Member counter", "\"N members\" sits at the left of the action row; the blue \"+ Add model\" button on the right opens the same multi-row picker the New Report's +Model button uses (search + provider filter)."),
            HelpCard("Member cards", "One card per (provider, model) tuple. Provider name in blue, model id in white, plus capability badges (👁 vision / 🌐 web / 🧠 reasoning). The trailing red ✕ removes the member."),
            HelpCard("Adding from the picker", "Already-added members render dimmed and ignore taps so a duplicate can't sneak in. Members are keyed on (provider.id, model) — case-insensitive on the model id."),
            HelpCard("Pitfalls", "The picker only surfaces ACTIVE providers (those with a working API key). Models from inactive providers are hidden from the catalog."),
            HelpCard("Related", "Same picker is reachable from the Hub's AI Models card and from AI Chat → Configure on the fly.")
        )
    ),
    "parameters" to HelpContent(
        title = "Parameters",
        cards = listOf(
            HelpCard("Overview", "CRUD list of parameter presets. A preset bundles temperature / max tokens / top-p / top-k / penalties / seed / system prompt / web-search and reasoning flags into one named row that you can attach to agents, flocks, swarms or one-off report runs."),
            HelpCard("Subtitle", "\"N parameters configured\" — counts how many fields in the preset are non-null, plus a +1 for each web-search flag that's on."),
            HelpCard("Add Parameter Preset", "Top button opens the edit screen blank."),
            HelpCard("Tips", "When multiple presets are attached to a worker (agents accept a list), later non-null values win — a sensible \"merge\" semantics."),
            HelpCard("Pitfalls", "Setting maxTokens here is a soft suggestion for OpenAI but mandatory for Anthropic; leaving it blank in an Anthropic-bound preset falls back to 4096."),
            HelpCard("Related", "Workers → Agents / Flocks / Swarms reference these by id; Provider edit also accepts a list of params ids that apply to every call to that provider.")
        )
    ),
    "parameters_list" to HelpContent(
        title = "Parameters",
        cards = listOf(
            HelpCard("Overview", "List of every saved Parameters preset — a bundle of generation knobs (temperature, max tokens, top_p, top_k, frequency / presence penalties, system prompt override, stop sequences, seed, response-format JSON, web-search flags). Agents / flocks pin presets by id."),
            HelpCard("Add Parameter Preset", "Top button opens the editor with a blank preset."),
            HelpCard("Row tap", "Opens the editor for that preset."),
            HelpCard("Row subtitle", "Compact summary of which knobs the preset overrides — e.g. 'temp / max-tokens / top_p set'."),
            HelpCard("Empty state", "No presets yet — tap Add Parameter Preset to create the first one."),
            HelpCard("Related", "Agents and Flocks reference Parameters presets by id; deleting a preset doesn't break referrers but leaves them with one fewer knob to merge.")
        )
    ),
    "parameters_edit" to HelpContent(
        title = "Parameters edit",
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
        title = "System Prompts",
        cards = listOf(
            HelpCard("Overview", "CRUD list of reusable system-prompt blocks. A row stores a name and a single multi-line text body — assignable to Agents, Flocks, Swarms, Providers, and one-off Report runs."),
            HelpCard("Item rows", "Show the name in white, the first 80 characters of the prompt in dim text below. Tap to edit, trash to delete with confirmation."),
            HelpCard("Add System Prompt", "Top button opens an empty editor."),
            HelpCard("Tips", "Names are unique among system prompts (case-insensitive). The body is required for save."),
            HelpCard("Pitfalls", "If a worker also has a Parameters preset that carries a non-blank systemPrompt, that wins over the bare System Prompt — the preset's value is the late-merge winner."),
            HelpCard("Related", "Internal Prompts (Meta / Fan-out / Fan-in / Other) live in a different bucket and use placeholders like @QUESTION@ / @RESULTS@ — not interchangeable with these.")
        )
    ),
    "system_prompts_list" to HelpContent(
        title = "System Prompts",
        cards = listOf(
            HelpCard("Overview", "List of every saved system-prompt preset. Each preset is a named blob of text that an agent (or flock) can pin — the dispatcher prepends it as the first system message of every call."),
            HelpCard("Add System Prompt", "Top button opens the editor with a blank preset."),
            HelpCard("Row tap", "Opens the editor for that preset — change name and text."),
            HelpCard("Row subtitle", "First non-blank line of the prompt body, truncated."),
            HelpCard("Empty state", "No presets yet — tap Add System Prompt to create the first one."),
            HelpCard("Related", "Agents and Flocks reference system prompts by id; flock-level overrides agent-level at run time.")
        )
    ),
    "system_prompt_edit" to HelpContent(
        title = "System prompt edit",
        cards = listOf(
            HelpCard("Overview", "Form for one system prompt. Two fields: Name (single line, required, unique) and System prompt text (6–15 visible lines)."),
            HelpCard("Save", "Disabled while the name is invalid OR the prompt body is blank — the body is required, unlike Parameters' optional systemPrompt field."),
            HelpCard("Live counter", "\"N characters\" beneath the body, updated on every keystroke."),
            HelpCard("Tips", "Markdown is fine — most chat APIs forward it raw and many models treat it as styled hints."),
            HelpCard("Pitfalls", "There is no token-count check; oversized system prompts will count against context length at run time without a warning here."),
            HelpCard("Related", "Use placeholders like @QUESTION@ here at your peril — substitution only happens for Internal Prompts, not System Prompts.")
        )
    ),
    "internal_prompts_hub" to HelpContent(
        title = "Internal prompts (hub)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under Prompt Management. Three cards: Meta prompts (single CRUD), Fan out/in prompts (forwards to its own sub-hub with three fan-* category CRUDs), and Other internal prompts (fixed list)."),
            HelpCard("Meta prompts", "Summarize, Compare — run on the full report (one final call). category=\"meta\"."),
            HelpCard("Fan out/in prompts", "Forwards to a sub-hub holding the three fan-* category CRUDs (fan_out, fan_in, fan-in-model). The badge count is the sum across all three buckets."),
            HelpCard("Other internal prompts", "Fixed list — intro, model_info, translate, rerank, moderation. Editable but not addable / deletable. category=\"internal\"."),
            HelpCard("Tips", "Names need to be unique within a category — \"Compare\" can exist under both meta and fan_in without collision."),
            HelpCard("Related", "Housekeeping → Internal prompts has a one-shot \"Load new prompts from assets/prompts.json\" merge that adds bundled rows you don't yet have.")
        )
    ),
    "fan_in_out_prompts_hub" to HelpContent(
        title = "Fan out/in prompts (hub)",
        cards = listOf(
            HelpCard("Overview", "Sub-sub-hub two levels deep under AI Setup → Prompt management → Internal prompts → Fan out/in prompts. One card per fan-* category — every CRUD shares the same list / edit infrastructure as the other Internal Prompt buckets."),
            HelpCard("Fan Out", "category=\"fan_out\". Per-pair source-response template — runs across every (answerer × source) pair (N×(N−1) calls). Placeholders include @RESPONSE@."),
            HelpCard("Fan in, total", "category=\"fan_in\". Combined-report template — one run per source agent on a single picked model. Iterable block `***Report*** @REPORT@@RESPONSES@` expands once per source."),
            HelpCard("Fan In, model", "category=\"fan-in-model\". Per-(provider, model) scoped fan-in. Reached from the L2 \"Fan out — model\" page's New Fan In button. Both @RESPONDERS@ (other models' fan-out responses to the active model) and @RESPONDER_PAIRS@ (pairs where active is the responder — `***Report***` + `***Response***` per pair) are populated; the prompt body opts in by reference. Empty state — no fan-out rows touching the active model — produces a placeholder error row instead of running."),
            HelpCard("Tips", "All three fan-* categories share the FAN_CATEGORIES treatment in the editor — no agent dispatch, the agent slot is N/A. Names are unique within each category, not across — the same name can exist in fan_out and fan-in-model without collision."),
            HelpCard("Related", "Settings → AI Setup → Prompt management → Internal prompts → Fan out/in prompts. Each card opens the same list screen pinned to one category — counts show how many entries are in each bucket.")
        )
    ),
    "internal_prompts" to HelpContent(
        title = "Internal prompts (list)",
        cards = listOf(
            HelpCard("Overview", "CRUD list pinned to one category (meta / fan_out / fan_in / internal). The screen title and Add label adapt — Add meta prompt vs. Add fan-out prompt etc. Other internal is a fixed list — no Add / Delete."),
            HelpCard("Item rows", "Show name plus a chip line: ref · agent (omitted when *select), then \"— title or first 60 chars of body\". Tap to edit; trash to delete (hidden for Other internal)."),
            HelpCard("Add", "The button label reflects the active category (e.g. \"Add meta prompt\"). Hidden for Other internal."),
            HelpCard("Tips", "Sorted alphabetically by name. Edits stay scoped to this category — saving in the editor pushes back into the same bucket."),
            HelpCard("Related", "The Report Result screen's Meta and Fan out buttons surface meta and fan_out prompts respectively; Fan out (L1) surfaces fan_in.")
        )
    ),
    "internal_prompts_list" to HelpContent(
        title = "Internal prompts list",
        cards = listOf(
            HelpCard("Overview", "List of every internal prompt within one category — one of meta / fan_out / fan_in / fan-in-model / rerank / internal. The screen title carries the category name."),
            HelpCard("Add", "When the category is editable, the top button opens a blank prompt editor pre-set to this category. The 'Other internal' bucket is fixed-list (no Add, no per-row 🗑) because those entries are bundled defaults."),
            HelpCard("Row tap", "Opens the prompt editor — change name, title, body. The category is locked to the screen's category."),
            HelpCard("Row subtitle", "Prompt title (or first body line when title is blank)."),
            HelpCard("Empty state", "No prompts in this category yet — tap Add (when available) to create one."),
            HelpCard("Related", "The Report flow's Create → Meta / Fan-out / Fan-in / Translate / Rerank buttons all pick from these lists. Load new prompts from assets/prompts.json to refresh the bundled defaults.")
        )
    ),
    "internal_prompt_edit" to HelpContent(
        title = "Internal prompt edit",
        cards = listOf(
            HelpCard("Overview", "Form for one Internal Prompt. Category is fixed by where you arrived from (cannot be changed in this screen)."),
            HelpCard("Name / Title", "Name is unique within the category. Title is a short tag shown alongside the name on Fan out; optional. For Other internal the Name is read-only."),
            HelpCard("Append reference legend", "Switch — adds a [N] = Provider / Model footer to the response."),
            HelpCard("Agent dropdown", "*select = ask the user at run time (legacy default). *n/a = no agent applies (fan_out only). Anything else = the named agent in Settings.agents resolved at run time."),
            HelpCard("Template body", "8–22 visible lines. Helper text lists the placeholders allowed: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@ (meta); @RESPONSE@/@QUESTION@/@TITLE@/@DATE@/@COUNT@ (fan_out); @COUNT@, @FAN_OUT_COUNT@, the iterable @REPORT@@RESPONSES@ block with @RESPONSE@ inside (fan_in)."),
            HelpCard("Pitfalls", "If you deep-link into edit before aiSettings has bootstrapped, the screen shows a \"Loading…\" placeholder and re-keys when the prompt id resolves — saving an empty form there would silently create a duplicate, so it is blocked.")
        )
    ),
    "providers" to HelpContent(
        title = "Providers",
        cards = listOf(
            HelpCard("Overview", "List of every registered provider (42 bundled plus any user-added). The state of each row is shown by an emoji."),
            HelpCard("State emojis", "🔑 = ok (key tested + working). ❌ = error (key set but tests fail). 💤 = inactive (manually disabled). ⭕ = not-used (no key set yet)."),
            HelpCard("Sort order", "Working providers (🔑) come first, then errored (❌), then inactive (💤), then never-configured (⭕). Within each bucket, sorted by id case-insensitively. The buckets put what you actually use one tap away."),
            HelpCard("Item rows", "Provider id in white plus the configured default model in dim text (only shown when state == ok). Tap a row to open the Provider edit screen."),
            HelpCard("Add provider", "The green \"+ Add provider\" button at the bottom opens a single-field name dialog. The name becomes the provider id (spaces stripped; \"Local\" reserved). Confirming registers an empty stub via ProviderRegistry.add and jumps straight to the same Provider edit screen used for the bundled providers — fill in the base URL / default model / API format there."),
            HelpCard("Related", "Provider configuration card on AI Setup → Providers also leads here. ProviderAdminScreen is the deep-link to each provider's web console.")
        )
    ),
    "provider_edit" to HelpContent(
        title = "Provider edit",
        cards = listOf(
            HelpCard("Overview", "Two layers in one screen: per-config runtime state at the top (key, default model, parameters, advanced URLs) and the catalog Definition cards underneath (display name, base URL, API format, type paths, pricing/feature flags). All edits autosave."),
            HelpCard("Provider inactive", "Switch at the very top. Toggling on flips state to inactive immediately. Toggling off triggers a fresh API test — pass = state ok, fail = state error, no key = not-used."),
            HelpCard("API Key card", "Field plus a Test button. On pass: state flips to \"ok\" AND the provider's default agent gets added to the \"default agents\" flock. On fail: 🐞 inline icon links to the trace from this run when tracing is on."),
            HelpCard("Default Model card", "Tap to open the SelectModelScreen overlay. Auto-refresh fetches the model list when the provider's source is API. Returning here updates the agent named after the provider in lock step with the chosen model."),
            HelpCard("Parameters card", "Multi-select dialog. Selected presets show as a comma-joined list inside the card."),
            HelpCard("Models card", "Tap to drill into the per-provider Models screen — same one you reach from AI Setup → Models → this provider. Back from there returns here."),
            HelpCard("Advanced (per-config overrides)", "Custom model list URL + Admin URL override — these stay attached to your runtime ProviderConfig, not the catalog."),
            HelpCard("Definition cards", "Basics / API / Models / Pricing & cost / Features / Storage. Edits push through ProviderRegistry.update — same store loaded from assets/setup.json on first launch. ID and prefs key are read-only (changing them would orphan stored keys, models, statistics)."),
            HelpCard("Pitfalls", "If you change apiFormat, the captured `service` may go stale — Test re-resolves through AppService.findById to use the fresh registry entry. Don't rely on the old reference if you tweak apiFormat then test in the same session.")
        )
    ),
    "models" to HelpContent(
        title = "Models",
        cards = listOf(
            HelpCard("Overview", "Top-level list of every active provider with a model count. Each row drills into that provider's per-provider model list (fetch / test / manual CRUD). Inactive providers are hidden — fix their key first."),
            HelpCard("Bulk refresh button", "\"Call all API retrieve models lists\" at the bottom — same path as Refresh → Models. forceRefresh=true bypasses the cache-validity check; an in-progress dialog shows live status."),
            HelpCard("Refresh results dialog", "After the bulk call finishes, a dialog reports per-provider counts (green) or \"failed\" (red)."),
            HelpCard("Per-provider screen", "API / Manual filter chips at top; API mode adds Fetch Models / Test all models buttons; Manual mode adds an inline Model ID add/edit form."),
            HelpCard("Test all models", "Visible after fetch when there are models. Spawns up to 5 concurrent tests via a Semaphore, gating in flight per row to ⏳ / ✅ / ❌. A failed ❌ links to its trace."),
            HelpCard("Remove failed", "Surfaces only after a Test all run completed with at least one ❌. Tap removes the failed list from the persisted models — the auto-save effect picks it up. Trace icons for those rows clear with them."),
            HelpCard("Per-row badges", "👁 vision / 🌐 web search / 🧠 reasoning + a colored type chip (chat/embedding/rerank/image/tts/stt/moderation/classify). The chip respects manual overrides instantly."),
            HelpCard("Pitfalls", "Manual lists are user-curated — Fetch Models is hidden in Manual mode. If you switch to Manual mid-edit, partial form state is wiped to avoid surprises on return."),
            HelpCard("Related", "Click a model row to open its Model Info screen.")
        )
    ),
    "model_edit" to HelpContent(
        title = "Per-provider models",
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
        title = "Model Types",
        cards = listOf(
            HelpCard("Overview", "Sets the default API path per ModelType (chat / embedding / rerank / image / tts / stt / moderation / classify). Resolution at dispatch time: per-provider override → these defaults → ModelType.DEFAULT_PATHS hardcoded fallback."),
            HelpCard("Auto-save", "Editing a field pushes the trimmed map back through onSave — no Save button. Blank means \"use the hardcoded default\" for that type."),
            HelpCard("Field placeholder", "Each input shows the hardcoded fallback as a dim placeholder so you know what will run if you leave the field empty."),
            HelpCard("Tips", "Per-provider Type paths under Provider edit → Definition · API win over these — so use this screen for global defaults, the provider screen for exceptions."),
            HelpCard("Pitfalls", "If you blank a default that the hardcoded fallback also doesn't have, dispatch will throw at runtime — easiest case is if you typo a path and the underlying provider doesn't expose that type."),
            HelpCard("Related", "Provider edit's Definition · API → Type paths overrides are the per-provider equivalent.")
        )
    ),
    "manual_model_types_list" to HelpContent(
        title = "Manual model types",
        cards = listOf(
            HelpCard("Overview", "List of manual (provider, model) → type overrides. The app normally derives a model's type (CHAT / RERANK / MODERATION / EMBEDDING / etc.) from the catalog; an override here pins it to a specific type when the catalog is wrong or missing."),
            HelpCard("Add Override", "Top button opens the editor with a blank override."),
            HelpCard("Row tap", "Opens the editor for the override."),
            HelpCard("Row subtitle", "Provider · model · type."),
            HelpCard("Empty state", "No overrides yet — tap Add Override to create the first one."),
            HelpCard("Related", "Model Types (configures the default type lookup); Cost Config (separate path for manual pricing overrides).")
        )
    ),
    "manual_model_types" to HelpContent(
        title = "Manual model types",
        cards = listOf(
            HelpCard("Overview", "CRUD list of (provider, model, type) triples that win over the autodetected type stored on ProviderConfig.modelTypes. Useful when the heuristic and native list APIs both miss — e.g. an embedding model whose name doesn't contain \"embed\"."),
            HelpCard("Item rows", "\"<providerId> / <modelId>\" with \"→ <type>\" plus capability flags (👁 / 🌐 / 🧠) on the second line. Tap to edit, trash to delete with confirmation."),
            HelpCard("Add override", "Top button switches to the editor with the form blank."),
            HelpCard("Edit form", "Provider dropdown over all providers; Model dropdown over the provider's known models (placeholder \"No models — fetch this provider first\" when empty); Type filter chips (3 cols × 3 rows over the 9 ModelType.ALL); Capabilities checkboxes for vision / web search / reasoning."),
            HelpCard("Capability flags", "Wired into Settings.isVisionCapable / isWebSearchCapable / isReasoningCapable so a tick here surfaces the badge anywhere this model appears, even when the provider's auto-derived sets miss it."),
            HelpCard("Tips", "Reachable from Model Info → \"Add manual override\" too — it pre-fills the form with the current model's heuristic flags so you only confirm or change them."),
            HelpCard("Pitfalls", "Switching the provider in the editor wipes the model id (model lists are provider-scoped)."),
            HelpCard("Related", "ManualModelOverrideEntryScreen is the same form pre-populated for direct entry from Model Info.")
        )
    ),
    "external_services" to HelpContent(
        title = "External Services",
        cards = listOf(
            HelpCard("Overview", "API keys for three non-LLM auxiliary services consumed by the app. Each field auto-saves on every keystroke (no debounce here)."),
            HelpCard("HuggingFace", "Used by the Model Info lookup to pull model cards / context-length / license fields. Get a free token at huggingface.co/settings/tokens."),
            HelpCard("OpenRouter", "Used for pricing data and model specifications (capability flags, supported parameters). Same key flows into Refresh → OpenRouter."),
            HelpCard("Artificial Analysis", "Pricing snapshot plus quality / speed scores. Free tier — sign up at artificialanalysis.ai/api. Used by Refresh → Artificial Analysis."),
            HelpCard("Tips", "Filling these unlocks the matching Refresh actions on the Refresh screen — without an OpenRouter key the OpenRouter button is disabled, same for Artificial Analysis."),
            HelpCard("Pitfalls", "These are NOT LLM provider keys — adding an OpenAI / Anthropic key here will not register the provider. Use Settings → AI Setup → Providers for that."),
            HelpCard("Related", "Refresh → OpenRouter / Artificial Analysis. Backup includes these keys; Clear all configuration removes them.")
        )
    ),
    "refresh" to HelpContent(
        title = "Refresh",
        cards = listOf(
            HelpCard("Overview", "Bulk refresh hub. Top-level page has three rows: \"Refresh all\" at the top, then NavCards into \"AI Info Providers\" and \"AI Runtime workers\" — each opens a dedicated full screen with its own help."),
            HelpCard("Refresh all (auto-restart)", "Sequence: OpenRouter (if key) → LiteLLM → models.dev → Helicone → llm-prices.com → Artificial Analysis (if key) → Providers → Models → Default agents → kill+relaunch via FLAG_ACTIVITY_NEW_TASK / CLEAR_TASK. Saves you from a manual kill/relaunch. Tapping the button routes to the Refresh-all progress screen."),
            HelpCard("AI Info Providers (sub-page)", "Catalog-source refreshes: OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis. The page's own \"All info providers\" runs the six in parallel without touching per-provider tests."),
            HelpCard("AI Runtime workers (sub-page)", "Per-AppService work: Provider key tests, Model lists, Default agents. The page's own \"All runtime workers\" runs the three sequentially without touching the catalogs."),
            HelpCard("Pitfalls", "OpenRouter and Artificial Analysis buttons inside AI Info Providers disable themselves until you set their keys under External Services."),
            HelpCard("Related", "Reachable both from AI Setup → Refresh and from Housekeeping → Refresh.")
        )
    ),
    "refresh_info_providers" to HelpContent(
        title = "AI Info Providers",
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
        title = "Refresh all",
        cards = listOf(
            HelpCard("Overview", "Refreshes the six pricing/spec catalogs (OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis) AND the per-provider Workers in parallel. The two phases are independent — neither blocks the other."),
            HelpCard("Workers", "For every provider that has an API key and isn't marked Inactive, in parallel: tests the API key, fetches the model list (only when the model source is API — MANUAL providers skip the fetch), writes a default agent named after the provider id, and appends that agent to the `default agents` flock."),
            HelpCard("Clean slate", "At the start of the run the `default agents` flock is emptied and every existing default agent (any agent whose name matches its provider id) is deleted. Custom agents — anything you renamed away from a provider id — survive untouched. The flock and its agents are then rebuilt from the workers that pass."),
            HelpCard("Re-entry", "Tasks continue in the background if you back-gesture out. Open Refresh again while a run is in flight and the live progress screen comes back. Tap the help icon on the progress screen to read this page without stopping anything."),
            HelpCard("Failed providers", "Workers that fail the key test list at the bottom with a tap-target that opens that provider's settings so you can fix the key / endpoint."),
            HelpCard("Auto-restart", "When both phases finish, a non-dismissible popup forces an app restart so freshly-loaded catalogs, capabilities, and rebuilt agents take effect cleanly.")
        )
    ),
    "import_export" to HelpContent(
        title = "Export / Import",
        cards = listOf(
            HelpCard("Overview", "Two card groups (Export, Import). All exports use Android's Storage Access Framework (CreateDocument) — the picker decides where the file lands; the app never sees the location."),
            HelpCard("Export · API Keys", "Just the keys (per-provider + HuggingFace + OpenRouter + Artificial Analysis) as a flat JSON object. Toast reports the populated key count."),
            HelpCard("Export · Costs Overrides", "Manual cost overrides as CSV (provider,model,input_per_million,output_per_million). Only rows the user explicitly added through Add Manual Override or via Manual cost overrides → Import manual changed costs."),
            HelpCard("Export · providers.json / prompts.json", "Drop-in shape for the bundled assets — no API keys included. Useful when shipping a tuned catalog as new defaults."),
            HelpCard("Export · Workers", "Agents + Flocks + Swarms in one file shaped { agents, flocks, swarms }. References to parameter sets, system prompts, and provider ids stay as ids — dangling on a target where those don't exist, but the worker still loads."),
            HelpCard("Export · Example prompts", "Drop-in shape for assets/examples.json — top-level array of {title, text} objects, no ids."),
            HelpCard("Export · Settings", "GeneralSettings (userName, defaultEmail, defaultTypePaths, tracingEnabled, modelNameLayout, showBackButton, subjectToTitleBarMode) as a JSON object. Excludes the three info-provider API keys — those round-trip through API Keys instead."),
            HelpCard("Export · Model lists", "Per-provider model lists keyed by provider id ({ \"OPENAI\": [\"gpt-4o\", …], … }). Sorted by provider id so successive exports diff cleanly."),
            HelpCard("Export · Parameters / System prompts", "Settings.parameters and Settings.systemPrompts as top-level JSON arrays. Imported entries upsert by id (existing rows with a matching id are replaced)."),
            HelpCard("Export · All including / excluding API keys", "Two variants of one bundle. Shape: { apiKeys?, costs, providers, prompts, examples, agents, flocks, swarms, settings, modelLists, parameters, systemPrompts }. Identical except the \"excluding\" variant omits the apiKeys section — safer for sharing with a colleague or pushing to a public repo."),
            HelpCard("Import", "One button per export shape, plus a full-width All that reads either bundle variant (sections are optional, missing ones are skipped). Workers and All upsert agents / flocks / swarms by id; bad rows (e.g. references to a deleted provider) are logged and skipped, the rest go through. Example prompts upsert by case-insensitive title; Parameters and System prompts upsert by id; Model lists replace the per-provider list."),
            HelpCard("Pitfalls", "Feeding a legacy full-config bundle to API Keys import throws ConfigBundleMistakenForKeysException — the toast clarifies the file shape isn't a keys file. Costs CSV importer skips malformed rows silently. Settings import leaves missing fields untouched (partial files are fine)."),
            HelpCard("Related", "Layered-costs CSV (Housekeeping → Manual cost overrides) is the bulk-edit path for overrides across every model. Backup & Restore is the all-in-one zip alternative.")
        )
    ),
    "local_runtime" to HelpContent(
        title = "Local models",
        cards = listOf(
            HelpCard("Overview", "Sub-hub for the two on-device runtimes. Each card shows installed count and drills into a dedicated screen."),
            HelpCard("Local LLMs", "On-device .task chat models (MediaPipe Tasks GenAI). Drives the synthetic Local provider that surfaces in every chat / report flow alongside cloud providers."),
            HelpCard("Local LiteRT models", "On-device .tflite text embedders (MediaPipe Tasks). Drives Local Semantic Search and Knowledge bases whose embedderProviderId == \"LOCAL\"."),
            HelpCard("Tips", "Counts are read once on screen entry — switch back to AI Setup and forward again if you just installed something via the deeper screens."),
            HelpCard("Pitfalls", "Backup explicitly excludes local_llms/ and local_models/ (FILES_DIR_BACKUP_EXCLUDES) — these are big, easily re-downloadable, and personal. Same set is preserved through clearFilesDirForRestore."),
            HelpCard("Related", "Both runtimes are wiped by Housekeeping → Clear all configuration but kept by Clear activity logs. Reset application also wipes them.")
        )
    ),
    "local_litert_models" to HelpContent(
        title = "Local LiteRT models",
        cards = listOf(
            HelpCard("Overview", "On-device .tflite text embedders. Models live in <filesDir>/local_models/ — nothing leaves the device once installed. Only models with MediaPipe Tasks metadata baked into the .tflite can load — the curated list is the verified set."),
            HelpCard("Curated downloads", "One indigo button per spec in LocalEmbedder.downloadable. Shows displayName plus approximate MB. Already-installed entries display \"<name> ✓\" and become non-clickable."),
            HelpCard("Download progress", "Live status text below the buttons updates with byte percentage as the download streams (\"Downloading <name>… NN%\")."),
            HelpCard("Add model from file", "Blue button opens an SAF picker (application/octet-stream + */*) — for users who've stamped MediaPipe Tasks metadata onto their own .tflite via Model Maker. Imported under a sanitized filename in local_models/."),
            HelpCard("Installed list", "Below the buttons. One row per installed model with a red \"Remove\" — releases the embedder and deletes <name>.tflite from disk."),
            HelpCard("Tips", "MediaPipe Tasks metadata is mandatory; arbitrary .tflite files won't load even though the picker accepts them."),
            HelpCard("Pitfalls", "Backup excludes this directory — restoring a backup leaves your installed embedders untouched, but a fresh device starts empty."),
            HelpCard("Related", "Knowledge → embedderProviderId=\"LOCAL\" KBs use these. Local Semantic Search (Search → Local Semantic) does too.")
        )
    ),
    "local_llms" to HelpContent(
        title = "Local LLMs",
        cards = listOf(
            HelpCard("Overview", "On-device LLMs via MediaPipe Tasks GenAI. Most useful models (Gemma, Phi, Llama) are licence-gated — open one of the recommended links in your browser, accept the terms, download the .task file (typically 0.5–2.5 GB), then return and Add LLM from file."),
            HelpCard("Download links", "One indigo button per LocalLlm.recommendedLinks entry. Each opens the model's web page in the system browser — they don't download into the app, they just hand off."),
            HelpCard("Add LLM from file", "Blue button opens an SAF picker. Accepts .task, .zip, .tar.gz, .tgz, .tar — the first .task entry in an archive is extracted automatically (Apache Commons Compress for tar; built-in for zip)."),
            HelpCard("Installed list", "One row per installed model with a red Remove — releases the runtime and deletes <name>.task from <filesDir>/local_llms/."),
            HelpCard("Tips", "AppService.LOCAL is synthetic — not in ProviderRegistry, only reachable via findById(\"LOCAL\"). Once you have at least one .task installed, Local appears as a normal provider in every picker."),
            HelpCard("Pitfalls", "Empty / corrupt extractions are detected (target.length() == 0L) and the partial file is deleted; the toast says \"Could not import model\". Backup excludes local_llms/."),
            HelpCard("Related", "Local LLM dispatch routes to LocalLlm.generate instead of Retrofit — no network, no cost, but token speed is bound by your device.")
        )
    ),
    "setup_providers" to HelpContent(
        title = "Providers (setup)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Three cards: per-provider configuration list, the catalog admin (web consoles), and a one-shot import of bundled providers from assets/providers.json."),
            HelpCard("Provider configuration", "Drills into the Providers list — set keys, default models, parameters, and edit catalog definitions per provider."),
            HelpCard("Provider administration", "Opens ProviderAdminScreen — one row per provider with state emoji and a deep-link to its admin / signup web URL."),
            HelpCard("Import new providers from assets/providers.json", "Tapping calls ProviderRegistry.importFromAsset(context). Adds providers in the bundle that aren't yet registered. Status text underneath reports how many were added (or that no new ones were found)."),
            HelpCard("Refresh tick", "providerCount is keyed on a refreshTick that increments after a successful import — so the count badge above updates immediately."),
            HelpCard("Tips", "If you've forked or extended the bundle, this is the cheap way to add new entries without touching settings.json."),
            HelpCard("Pitfalls", "Existing providers with the same id are NOT overwritten by this card — use Export / Import → providers.json with upsertFromJson for that."),
            HelpCard("Related", "Provider edit's Definition cards modify already-registered entries in place.")
        )
    ),
    "setup_models" to HelpContent(
        title = "AI Models setup",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Three cards: Models (per active provider), Model Types (default API path per type), Manual model types overrides (per-model type assignments)."),
            HelpCard("Models", "Disabled until you have at least one active provider. Drills into the per-provider model lists. Count = total models across active providers."),
            HelpCard("Model Types", "List of the 9 model kinds from ModelType.ALL with their default API paths. Count = number of types."),
            HelpCard("Manual model types overrides", "CRUD for (provider, model, type, capabilities) overrides that win over autodetection. Count = number of overrides currently saved."),
            HelpCard("Tips", "Resolution order at dispatch: per-provider Type paths (Provider edit → Definition · API) → Model Types defaults → ModelType.DEFAULT_PATHS hardcoded fallback."),
            HelpCard("Pitfalls", "If no provider is active the Models card stays grey-blue and unclickable."),
            HelpCard("Related", "Provider edit → Definition · API → Type paths exposes the per-provider override layer.")
        )
    ),
    "setup_workers" to HelpContent(
        title = "AI Workers (setup)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Three cards (Agents / Flocks / Swarms) — all three disabled until at least one provider has an API key set."),
            HelpCard("Agents", "Named (provider, model, key, params, prompt, endpoint) tuples. Count = active agents (whose provider is currently active)."),
            HelpCard("Flocks", "Groups of agents with optional shared parameters/system prompt. Count = number of flocks."),
            HelpCard("Swarms", "Groups of (provider, model) pairs without going through agents. Count = number of swarms."),
            HelpCard("Tips", "When you import a config bundle these counts jump in lockstep with the imported data."),
            HelpCard("Pitfalls", "If your only API key was just removed, all three cards lock — the hub uses aiSettings.hasAnyApiKey() as its enable gate."),
            HelpCard("Related", "Refresh → Default agents auto-creates one agent per active provider plus a \"default agents\" flock that ties them together.")
        )
    ),
    "setup_prompts" to HelpContent(
        title = "Prompt management (setup)",
        cards = listOf(
            HelpCard("Overview", "Three NavCards (System Prompts, Internal Prompts, Example prompts) plus two maintenance cards lifted from the former Housekeeping → Prompts screen."),
            HelpCard("System Prompts", "Direct CRUD list. Count = number of system prompts."),
            HelpCard("Internal Prompts", "Drills into the Internal Prompts hub which splits further into Meta / Fan-out / Fan-in / Other. Count = total across all four categories."),
            HelpCard("Example prompts", "Two-field CRUD (title, text). Pure data the user curates; no placeholder substitution, no agent dispatch, no app feature consumes them automatically."),
            HelpCard("Internal prompts maintenance", "Load merges any (category, name) pair missing from assets/prompts.json — existing rows keep your edits. Reset wipes every Internal prompt and reloads the bundled set fresh; confirmation dialog gates it."),
            HelpCard("Example prompts maintenance", "Adds any prompt in assets/examples.json whose title (case-insensitive) is missing. Existing prompts are NEVER overwritten — there is no Reset for examples."),
            HelpCard("Tips", "System Prompts are referenced by id from Agents/Flocks/Swarms/Providers; Internal Prompts are referenced by name + category by app features. Example prompts are referenced by nothing — they're just a personal library."),
            HelpCard("Pitfalls", "System and Internal prompts are NOT interchangeable — Internal use placeholder substitution that System does not. Internal-prompt Reset is destructive; Backup & Restore is the only undo.")
        )
    ),
    "setup_local_models" to HelpContent(
        title = "Local models (setup)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Two cards: Local LLMs (.task chat / completion bundles driving the Local provider) and Local LiteRT models (.tflite text embedders driving Local Semantic Search and Local-embedder Knowledge)."),
            HelpCard("Local LLMs", "Counts installed .task files in <filesDir>/local_llms/."),
            HelpCard("Local LiteRT models", "Counts installed .tflite files in <filesDir>/local_models/."),
            HelpCard("Tips", "Both card counts are read with remember{} on entry — they don't auto-refresh on changes inside the deeper screens."),
            HelpCard("Pitfalls", "Backup excludes both directories. Clear all configuration removes both, as does Reset application; Clear activity logs leaves them alone."),
            HelpCard("Related", "AppService.LOCAL is synthetic and only registered when a .task is present.")
        )
    ),
    "housekeeping" to HelpContent(
        title = "Housekeeping",
        cards = listOf(
            HelpCard("Overview", "Maintenance hub. Each row is a NavCard that drills into its own full screen with its own help text — tap the row to enter, ℹ️ for the per-screen detail."),
            HelpCard("The six rows", "Backup & Restore · Export & Import · Refresh · Trim by age · Usage statistics · Reset. Order is roughly safe → destructive. Prompt-bundle maintenance and manual-cost-overrides cleanup live under AI Setup → Prompt management / Costs — those screens already host the per-row CRUD they're paired with."),
            HelpCard("Tips", "Backup before any of the destructive screens — Reset, Clear activity logs, and Clear all configuration are not undoable."),
            HelpCard("Related", "Local LLMs / Local LiteRT model maintenance is under AI Setup, not here — the on-device runtimes are configuration, not housekeeping.")
        )
    ),
    "backup_restore" to HelpContent(
        title = "Backup & Restore",
        cards = listOf(
            HelpCard("Overview", "Single-zip whole-app backup and restore. Uses Android's Storage Access Framework so the destination/source picker shows Google Drive, Dropbox, OneDrive, local files — whichever cloud apps you have installed. The app never sees the underlying location."),
            HelpCard("Backup", "Green button. Default filename `ai-backup-<yyyymmdd>.zip`. Includes configuration, API keys, reports, chats, API traces, prompt cache, knowledge bases, embeddings. Excluded: `local_llms/` and `local_models/` (FILES_DIR_BACKUP_EXCLUDES) — they're large and re-downloadable."),
            HelpCard("Restore", "Blue button. Picker is restricted to .zip plus application/octet-stream (some providers report .zip with the latter mime). Confirmation dialog appears first. The restore is validate-then-write: the zip is parsed before any current file is touched."),
            HelpCard("Auto-restart", "On successful restore the app shows a toast, waits ~800ms, then relaunches itself with FLAG_ACTIVITY_NEW_TASK + CLEAR_TASK and kills the current process. The next launch reads the restored data fresh."),
            HelpCard("Pitfalls", "A failed restore leaves the device in a partial state (validate-then-write reduces the window but cannot eliminate it). Local LLM and LiteRT model files have to be re-installed by hand on a new device — they're never in the zip."),
            HelpCard("Related", "Export/Import does selective shape-typed transfer (keys, costs, providers.json, prompts.json) without bundling reports/chats/traces.")
        )
    ),
    "trim_by_age" to HelpContent(
        title = "Trim by age",
        cards = listOf(
            HelpCard("Overview", "Bulk-deletes reports, chat sessions, and API trace files older than a cutoff. Configuration, API keys, knowledge bases, prompt history, usage statistics — all kept."),
            HelpCard("Days-to-keep field", "Digits-only, max four. Defaults to 30. Clear button is disabled until the value is a positive integer."),
            HelpCard("Confirmation", "Tapping the orange button opens a dialog that shows the exact per-kind count (\"Permanently deletes everything older than N days: X reports, Y chat sessions, Z trace files\"). Confirm fires the deletes."),
            HelpCard("Pitfalls", "Cannot be undone. Counts are computed once when the dialog opens — if a chat updates between dialog open and Confirm tap, the actual delete may differ slightly."),
            HelpCard("Related", "Reset → Clear activity logs wipes ALL chats and traces regardless of age, plus app logs and usage statistics. Reports stay; use this screen for reports.")
        )
    ),
    "usage_statistics" to HelpContent(
        title = "Usage statistics",
        cards = listOf(
            HelpCard("Overview", "One purple button that empties the per-(provider, model) call counts, token totals, and accumulated cost. The AI Usage screen empties out; reports, chats, traces, configuration, and pricing tiers stay intact."),
            HelpCard("Confirmation", "None — the action is one tap, confirmed via toast (\"Usage statistics cleared\")."),
            HelpCard("Tips", "Stats are accumulated lazily from API calls — they'll start filling back in the next time you run a report or chat."),
            HelpCard("Related", "AI Usage screen (cost / tokens dashboard) reads exactly the data this button wipes.")
        )
    ),
    "example_prompts_list" to HelpContent(
        title = "Example prompts",
        cards = listOf(
            HelpCard("Overview", "List of every saved example prompt. Examples surface in the Hub's prompt-history flow and in the picker reached by Start with a previous prompt — quick-pick a saved prompt body instead of retyping."),
            HelpCard("Add Example", "Top button opens the editor with a blank example."),
            HelpCard("Row tap", "Opens the editor — change title and body."),
            HelpCard("Row subtitle", "First non-blank line of the body, truncated."),
            HelpCard("Empty state", "No examples yet — tap Add Example to create the first one. Load fresh examples from assets/examples.json via the Internal-prompts loader."),
            HelpCard("Related", "Prompt History (every prompt actually sent); the Hub's Start with a previous prompt entry shows examples + history together.")
        )
    ),
    "example_prompt_edit" to HelpContent(
        title = "Example prompt (edit)",
        cards = listOf(
            HelpCard("Overview", "Two-field CRUD: Title (required, also the de-dup key for Load new prompts) plus Text (free-form template body)."),
            HelpCard("Title", "Required. Used as the case-insensitive de-dup key when Housekeeping → Prompts → Add new prompts from assets/examples.json runs — same title means the bundled row is skipped."),
            HelpCard("Text", "Multi-line body, no enforced placeholder set. Free-form starter the user pastes into the New Report prompt field; the app does not substitute anything automatically."),
            HelpCard("Tips", "Example prompts are pure data, not bound to any app feature. Add as many as you like; reorder via Title since the list sorts alphabetically."),
            HelpCard("Related", "Internal prompt edit (the @QUESTION@ / @RESULTS@ template kind used by Meta / Fan out) is a separate screen with category + agent + reference fields.")
        )
    ),
    "reset" to HelpContent(
        title = "Reset",
        cards = listOf(
            HelpCard("Overview", "Three escalating destructive operations. Each has its own confirmation dialog with the exact list of what's wiped vs. kept."),
            HelpCard("Clear activity logs (red)", "Wipes the narrow activity surface: app logs, chat history, API traces, and usage statistics. Reports, knowledge bases, prompt history, the six Info-provider caches, the per-provider model-list cache, and the semantic-search embedding cache are all preserved. Configuration (providers, agents, flocks, swarms, prompts, parameters, API keys) is also kept."),
            HelpCard("Clear all configuration (dark red)", "Wipes every provider's API key, models, endpoints; agents, flocks, swarms, parameters, system prompts; External Services keys (HuggingFace, OpenRouter, Artificial Analysis); user name and default email; every installed Local LLM (.task) and LiteRT (.tflite). Reports, chats, traces, and usage statistics are kept."),
            HelpCard("Reset application (dark red, type-RESET)", "Factory-style reset. API keys (per-provider + 3 external) are preserved; everything else is wiped, providers and internal prompts are reloaded from assets, then the Refresh-all chain runs (catalogs → provider tests → model lists → default agents). Type-to-confirm gate."),
            HelpCard("Pitfalls", "Reset application's confirmation is CASE-sensitive — must literally type RESET, trimmed. Both Clear-all variants are immediate; only Reset application has a busy-spinner dialog because it also runs the Refresh chain."),
            HelpCard("Related", "Backup & Restore is the only undo path — take a backup first if there's any chance you'll regret a wipe.")
        )
    ),
    "statistics" to HelpContent(
        title = "AI Usage",
        cards = listOf(
            HelpCard("Overview", "Per-provider usage breakdown. Top card is a summary (total calls, total tokens via formatCompactNumber, total cost in green, pricing-source stats). Per-provider rows expand to show per-model details."),
            HelpCard("Pricing fetch", "On entry, if an OpenRouter key is set and the cache is stale, fetchOpenRouterPricing runs in the background; rows display once pricingReady flips true."),
            HelpCard("Provider rows", "Show display name + total calls + total cost + ▾/▸ chevron. Tap toggles expansion. Expanded provider state is rememberSaveable across navigation to Model Info and back."),
            HelpCard("Per-model rows", "model id (white) + optional kind pill (rerank/summarize/compare/moderation/translate; report kind is hidden as the implicit default), call count + tokens or search-units, total cost, pricing-source tag color-coded (OVERRIDE=Orange, OPENROUTER=Blue, LITELLM=Purple, others=dim)."),
            HelpCard("Rerank rows", "Bill per search-unit, not per token. Token columns stay zero by design; per-query cost lands in the input column."),
            HelpCard("Tips", "Tap a model row to drill into Model Info for that (provider, model)."),
            HelpCard("Pitfalls", "Legacy rows written before the kind field exist deserialize without one — SettingsPreferences.loadUsageStats backfills, but the row defends with `(swc.stat.kind as String?) ?: \"report\"` to keep the renderer safe."),
            HelpCard("Related", "Cost Config (Settings → Costs) edits the OVERRIDE tier; Refresh → OpenRouter / LiteLLM rebuild the curated tiers consulted here.")
        )
    ),
    "cost_config" to HelpContent(
        title = "Cost Config",
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
            HelpCard("Related", "Export/Import → Costs Overrides exports just the manual layer as a 4-column CSV.")
        )
    ),
    "cost_override" to HelpContent(
        title = "Cost override (add / edit)",
        cards = listOf(
            HelpCard("Overview", "Form to add or edit one (provider, model, input $/M, output $/M) override. Reachable from Cost Config's Add button or directly from Model Info → \"Add manual cost override\"."),
            HelpCard("Provider button", "Outlined button opens SelectProviderScreen as a full-screen overlay. Default = first provider in AppService.entries."),
            HelpCard("Model row", "Free-text \"Model\" field plus a Select button that opens SelectModelScreen for the chosen provider. Either typing or picking works."),
            HelpCard("Reference price", "When provider+model are populated, a small dim line shows the current price the lookup would return WITHOUT the override (PricingCache.getPricingWithoutOverride) plus the source tier."),
            HelpCard("Save", "Disabled until provider + non-blank model + parseable input + parseable output. Numbers are $/1M tokens; saved divided by 1,000,000."),
            HelpCard("Tips", "When opened from Model Info on an existing override, both price fields pre-populate from the saved values."),
            HelpCard("Pitfalls", "Negative prices and non-numeric input fail the Save gate without an explicit error message — the button just stays disabled."),
            HelpCard("Related", "Layered costs CSV → Import manual changed costs is the bulk equivalent.")
        )
    ),
    "trace_list" to HelpContent(
        title = "API Traces",
        cards = listOf(
            HelpCard("Overview", "List of every captured API trace. First open of the session populates ApiTracer's cache via streaming parse over the trace dir; subsequent opens are O(1). Title varies: \"Report Traces\" when scoped by reportId, \"Traces — <model>\" when scoped by model, \"API Traces\" otherwise."),
            HelpCard("Filter row", "Up to four slots in one row, each conditionally rendered: Category (when >1 distinct), Hostname (when >2), Provider (when >1), Model (when any pickable). \"(All)\" hides itself on the button label so chips read just \"Category\" instead of \"Category: (All)\"."),
            HelpCard("Errors-only", "Checkbox below the filter row when error count > 0. Filters to status 0 (transport) or >= 400 (HTTP)."),
            HelpCard("Auto-collapse", "When a deep-link arrives with preset filters and the resulting list has exactly one entry, the screen jumps straight into that trace's detail. Flag is rememberSaveable so popping back lands on the (single-entry) list, not the detail again."),
            HelpCard("Pagination", "Computed from BoxWithConstraints maxHeight (52dp per row, 130dp overhead). Prev/Next + position indicator only when total > 1 page."),
            HelpCard("Per-row", "Hostname (left), date/time (center, MM/dd HH:mm:ss), status code (right; green 2xx / orange 4xx / red 5xx / dim others)."),
            HelpCard("Tips", "Picking a model uses a full-screen overlay (TraceModelPickerOverlay) since the option list can be too long for a popup menu."),
            HelpCard("Pitfalls", "PROVIDER_AUX_HOSTS maps Cohere's api.cohere.com onto the Cohere baseUrl host — without that the rerank API traces would land in \"(unknown)\". Extend the map for any provider with a similar second host."),
            HelpCard("Related", "Most 🐞 ladybug icons across the app deep-link here with category/model filters preset.")
        )
    ),
    "trace_detail" to HelpContent(
        title = "Trace detail",
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
        title = "Trace pick model",
        cards = listOf(
            HelpCard("Overview", "Full-screen overlay used by the trace list to pick one (provider, model) pair to filter on. Top card is \"(All models)\" — clears the filter when tapped."),
            HelpCard("Item rows", "Provider name in blue (small) plus model id in white (or blue when selected) below. Trailing ✓ marks the current selection."),
            HelpCard("Sourcing", "Options come from pickableModels in the parent — distinct (provider, model) pairs from traces in the currently scoped Category + Provider + Hostname subset. Picking an unpopulated combination is impossible."),
            HelpCard("Tips", "Sorted (provider, model) by lowercased name; ties broken by model id."),
            HelpCard("Pitfalls", "If you change the upstream Category / Provider / Hostname filters AFTER picking a model, your model selection may no longer match anything — the list will go empty until you clear it."),
            HelpCard("Related", "Reachable only from the trace list's Model launcher button.")
        )
    ),
    "developer_test" to HelpContent(
        title = "API Test",
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
        title = "Edit Request",
        cards = listOf(
            HelpCard("Overview", "Raw JSON editor for an API request. Loads either the captured request body of a previous trace OR a freshly-built body from the API Test form (Gson pretty-printed). Provider/model/url come from eval_prefs."),
            HelpCard("Info card", "Top non-editable card with \"<provider> / <model>\" and the endpoint URL — sanity check before submitting."),
            HelpCard("Editor", "OutlinedTextField with monospace font occupies the rest of the screen. Edits are local until Submit."),
            HelpCard("Submit", "Turns tracing on for this single call regardless of the global setting (and restores it after), runs AnalysisRepository.testApiConnectionWithJson, then jumps to TraceDetail for the new trace if one was captured. If nothing new came in, just toasts \"Request sent. Check traces.\""),
            HelpCard("Tips", "This bypasses the Retrofit serialization layer entirely — perfect for testing edge-case body shapes a provider's docs claim to support."),
            HelpCard("Pitfalls", "API key for the call comes from eval_prefs and is also sent in plain text via the standard provider Authorization scheme; if you mis-typed it on the test screen the failure looks like a 401 in the trace, not a UI error here."),
            HelpCard("Related", "Trace Detail → Edit jumps here pre-populated with the trace's request — fast \"replay with tweaks\" loop.")
        )
    ),
    "provider_admin" to HelpContent(
        title = "Provider administration",
        cards = listOf(
            HelpCard("Overview", "One row per provider showing state plus its configured Admin URL. Tap a row to open the URL in the system browser — useful for sign-up, top-up, key rotation, or usage check."),
            HelpCard("Sort order", "ok first, then error, then not-used, then inactive. Within each bucket alphabetical by display name. Counts every registered provider, not just active ones."),
            HelpCard("Per-row card", "Display name in white + state emoji on the right (🔑 ok / ❌ error / 💤 inactive / ⭕ not-used / unknown for anything else). Admin URL line beneath in blue underlined monospace, or \"(no admin URL configured)\" when blank."),
            HelpCard("Tap behavior", "Fires Intent.ACTION_VIEW with the URL. Toast on missing URL or browser-launch exception."),
            HelpCard("Tips", "Reached from AI Setup → Providers → Provider administration."),
            HelpCard("Pitfalls", "Admin URL is part of the catalog — if it's wrong / missing for a custom provider, edit it under Provider edit → Definition · Basics. Edits to that field flow into the catalog directly; there is no longer a separate per-config override layer.")
        )
    ),

    // ===== Per-cloud-provider help pages (42) =====
    // Topic id derived via providerHelpTopicId() — lowercased,
    // alphanumeric only. Cards: Overview / Setup / Models / Pricing
    // & quirks / Pitfalls / Related. Reached from the ℹ️ icon on the
    // ProviderSettingsScreen TitleBar and the Cloud providers
    // directory on the home Help page.

    "provider_openai" to HelpContent(
        title = "OpenAI",
        cards = listOf(
            HelpCard("Overview", "OpenAI Inc. — the company that turned chat-style LLMs into a mainstream product with ChatGPT (Nov 2022). Headquartered in San Francisco; partly owned by Microsoft. Their API is the de-facto reference shape every \"OpenAI-compatible\" provider mirrors."),
            HelpCard("Setup", "Sign up at platform.openai.com, create an organization, then mint an API key under Settings → API keys. Paid usage from the first call — the free trial credits ended in 2023. New accounts may need a phone-number verification before keys work. Some model families (gpt-4o-search-preview, certain o-series) are gated by tier."),
            HelpCard("Models", "Two tracks coexist: chat-completion models (`gpt-4o`, `gpt-4o-mini`, `gpt-3.5-turbo`, `o1-mini`) and the newer Responses-API models (`gpt-5`, `gpt-5-mini`, `o3*`, `o4*`, `gpt-4.1*`). Moderation (`omni-moderation-latest`, `text-moderation-latest`), TTS (`tts-1`), Whisper, and DALL-E 3 / `gpt-image-1` are available too — they're hardcoded in our catalog because `/v1/models` doesn't enumerate them. The default model in this app is `gpt-4o-mini`. `modelFilter=gpt|o1|o3|o4` trims the noisy /v1/models list."),
            HelpCard("Pricing & quirks", "OpenAI uses TWO endpoints depending on model family: Chat Completions (`v1/chat/completions`) for gpt-4o-class and o1-mini, and Responses API (`v1/responses`) for gpt-5 / o3* / o4* / gpt-4.1*. Auto-routed by `usesResponsesApi()` + `endpointRules`. Multi-text Responses-API output blocks are concatenated by the dispatcher. Pricing fed by LiteLLM + OpenRouter (alias `openai/<model>`); manual override + fixed tiers fill any gaps."),
            HelpCard("Pitfalls", "Tier-gated models 404 with no warning until your account climbs. Some keys are organization-scoped and need an `OpenAI-Organization` header — the app doesn't send one, so use a personal key or set the default org on the key. Reasoning effort (`low`/`medium`/`high`) is honored only by gpt-5 / o-series; non-reasoning models silently drop the field at dispatch."),
            HelpCard("Related", "Console: `https://platform.openai.com/settings/organization/api-keys`. OpenRouter alias: `openai`. Status / outage page: status.openai.com — worth checking when every model 503s.")
        )
    ),

    "provider_anthropic" to HelpContent(
        title = "Anthropic",
        cards = listOf(
            HelpCard("Overview", "Anthropic, founded 2021 by ex-OpenAI researchers (Dario & Daniela Amodei). Based in San Francisco; backed by Amazon, Google. Famous for the Claude family and a constitutional-AI safety approach. Their API has its own shape (`/v1/messages`) — distinct from OpenAI's chat completions."),
            HelpCard("Setup", "Sign up at console.anthropic.com → Settings → API Keys. Pay-as-you-go from the first call; promotional credits sometimes available. Workspace-scoped keys; some accounts need an extra approval for higher rate-limit tiers. Phone verification on new accounts."),
            HelpCard("Models", "Hero family: Claude 4 Opus / Sonnet (May 2024 ids in catalog, e.g. `claude-sonnet-4-20250514`, `claude-opus-4-20250514`), plus Claude 3.7 / 3.5 Sonnet, 3.5 Haiku, and the older 3 Opus / Sonnet / Haiku. Default model in the app is `claude-sonnet-4-20250514`. 8 hardcoded fallback models cover the major ids; live list comes from `v1/models`. `modelFilter=claude` keeps the picker tidy."),
            HelpCard("Pricing & quirks", "`apiFormat = ANTHROPIC` — separate dispatch path. Auth via `x-api-key` + `anthropic-version: 2023-06-01` (NOT Bearer). Path is `v1/messages` (override on `typePaths.chat`). **`max_tokens` is required** — the dispatcher defaults to 4096 if you didn't set one and logs an override when reasoning is on. Streaming uses both `event:` and `data:` SSE framing. Web-search tool (`web_search_20250305`) injected when 🌐 is on for Claude 3.5+."),
            HelpCard("Pitfalls", "Forgetting `max_tokens` is fatal — Anthropic returns 400 immediately. Vision images are base64 `image` content blocks (not OpenAI-shape `image_url`). Some accounts can't access Opus 4 until the rate-limit tier is approved. Long context (200k) costs the same per-token but cache reads price differently — pricing tiers may understate your bill."),
            HelpCard("Related", "Console: `https://console.anthropic.com/settings/keys`. OpenRouter alias: `anthropic`. Pricing source order: LiteLLM → OpenRouter cross-provider fallback.")
        )
    ),

    "provider_google" to HelpContent(
        title = "Google",
        cards = listOf(
            HelpCard("Overview", "Google's Gemini API (Generative Language API). Successor to Bard / PaLM; runs on Google Cloud infrastructure but has a separate consumer-friendly API key path under aistudio.google.com. Distinct from Vertex AI — same models, different auth + billing."),
            HelpCard("Setup", "Visit aistudio.google.com/app/apikey, sign in with a Google account, and click \"Create API key\". Generous free tier (rate-limited but free for many models including 2.0/2.5 Flash). Paid \"Pay-as-you-go\" upgrades available; some 2.5 Pro / Ultra tiers require a billing-enabled GCP project."),
            HelpCard("Models", "Gemini 2.0 Flash (default in app), 2.5 Pro / Flash / Flash-Lite, 1.5 Pro / Flash, plus older `gemini-pro` / `gemini-pro-vision`. Embedding models (`text-embedding-004`, `gemini-embedding-001`). Live list at `v1beta/models` — `modelListFormat=array` because the response is a bare top-level array. `litellmPrefix=gemini` for the LiteLLM alias."),
            HelpCard("Pricing & quirks", "`apiFormat = GOOGLE` — its own dispatch path. **Auth is `?key=<key>` query parameter** (URL-encoded), NOT a Bearer header. Model id is in the URL path: `v1beta/models/{model}:generateContent` (or `:streamGenerateContent` when streaming). Roles use `user` / `model` (not `user` / `assistant`). System prompt is a separate `systemInstruction` field. Vision images are `inlineData(mimeType, data)` parts. Web-search tool descriptor is `google_search` for 1.5+ / 2.x."),
            HelpCard("Pitfalls", "The query-param auth means a leaked URL leaks the key — `ApiTracer` redacts it at write time, but third-party log captures elsewhere may not. Path-encoded model ids show in trace URLs; the tracing interceptor extracts `trace.model` from there for non-body-encoded providers. Free-tier rate limits are aggressive; expect 429 retries during heavy fan-out."),
            HelpCard("Related", "Console: `https://aistudio.google.com/app/apikey`. OpenRouter alias: `google`. The streaming-JSON parser (chunked-JSON, not SSE) is in `ApiStreaming.streamGoogle`.")
        )
    ),

    "provider_xai" to HelpContent(
        title = "xAI (Grok)",
        cards = listOf(
            HelpCard("Overview", "xAI Corp — Elon Musk's AI company, founded 2023. Models train on real-time X (Twitter) data. The API is OpenAI-compatible at the wire level; pricing returns in \"ticks\" rather than dollars."),
            HelpCard("Setup", "Sign up at console.x.ai, top up with a credit card (no free tier on most models — there's been promotional credit programs on and off). API keys appear under Console → API keys. Some models gate behind X Premium / Premium+ subscriptions for the consumer chat; the API itself is paid usage."),
            HelpCard("Models", "Hero models: Grok-3, Grok-3-mini (default), Grok-2, Grok-2-Vision, Grok-Beta. `modelFilter=grok` trims the picker. `defaultModelSource=API` — the app fetches the live list from `v1/models`."),
            HelpCard("Pricing & quirks", "`costTicksDivisor=1e10` — the API returns `usage.cost` denominated in 10⁻¹⁰ USD ticks. The dispatcher divides to get dollars; provider-config edit refuses non-positive divisors so misconfiguration can't accidentally inflate costs. Otherwise OpenAI-compatible — Bearer auth, Chat Completions shape. `litellmPrefix=xai`, `openRouterName=x-ai`."),
            HelpCard("Pitfalls", "Cost ticks confuse third-party pricing tiers — LiteLLM and llm-prices have caught up, but a fresh model id may surface with the wrong magnitude until they update. Some Grok-3 features (e.g. live search) require additional flags this app doesn't yet plumb. Vision support is model-specific (Grok-2-Vision)."),
            HelpCard("Related", "Console: `https://console.x.ai/`. OpenRouter alias: `x-ai`. LiteLLM alias: `xai`. xAI API docs: docs.x.ai.")
        )
    ),

    "provider_groq" to HelpContent(
        title = "Groq",
        cards = listOf(
            HelpCard("Overview", "Groq Inc. — runs an in-house LPU (Language Processing Unit) ASIC instead of GPUs, giving notably high token-per-second throughput on open-weight Llama / Mixtral / Qwen models. Headquartered in Mountain View. Don't confuse with Elon Musk's xAI Grok — different companies."),
            HelpCard("Setup", "Sign up at console.groq.com → API Keys. Generous free tier with daily request quotas; paid upgrades for production. No phone verification required on most accounts."),
            HelpCard("Models", "Hosted catalog of Meta's Llama 3.x (`llama-3.3-70b-versatile` default), Llama 4 Scout/Maverick, Mistral / Mixtral, Qwen 3, Whisper, plus image variants. `defaultModelSource=API` so the live list at `v1/models` drives the picker. No hardcoded fallback — Groq's catalog rotates quickly as they add / retire model variants."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing per million tokens is competitive on open-weight models thanks to LPU economics. `litellmPrefix=groq` for the LiteLLM alias."),
            HelpCard("Pitfalls", "Free-tier daily limits hit fast on fan-out runs (the per-day cap counts every retry). Some models retire on short notice — your saved Agent's model id may 404 after a quarterly rotation; Refresh All flags failed providers with one-tap nav-to-edit. Whisper and embedding models are typed but not all flows route to them."),
            HelpCard("Related", "Console: `https://console.groq.com/keys`. LiteLLM alias: `groq`. No OpenRouter mirror — Groq doesn't sell through OpenRouter today.")
        )
    ),

    "provider_deepseek" to HelpContent(
        title = "DeepSeek",
        cards = listOf(
            HelpCard("Overview", "DeepSeek (深度求索) — Chinese AI lab spun out of High-Flyer Capital. Authors of the open-weight DeepSeek-V3 (general) and DeepSeek-R1 (reasoning) models that became prominent in 2024–2025. The proprietary API hosts polished versions of those open models."),
            HelpCard("Setup", "Sign up at platform.deepseek.com (you'll need a phone — China-region accounts use SMS, others may use email). Top up the wallet; pricing is famously low per million tokens. API keys under Console → API keys."),
            HelpCard("Models", "`deepseek-chat` (V3, default) and `deepseek-reasoner` (R1). Some accounts see additional preview tiers. `modelsPath=models` (not `v1/models`); `chat` path is `chat/completions` (not `v1/chat/completions`). `modelFilter=deepseek` trims the picker. `defaultModelSource=API`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing-cache aliases: `litellmPrefix=deepseek`, `openRouterName=deepseek`. Reasoning models surface their chain-of-thought as part of the response — the dispatcher does not currently strip it before display."),
            HelpCard("Pitfalls", "Service interruptions sometimes follow regulatory cycles in China — failovers via OpenRouter / Together / SiliconFlow keep the same model accessible elsewhere if needed. Reasoning tokens count as output and can dominate the bill on long chains. Some non-China users report intermittent 403 from carrier IPs; a VPN often fixes it."),
            HelpCard("Related", "Console: `https://platform.deepseek.com/api_keys`. LiteLLM alias: `deepseek`. OpenRouter alias: `deepseek` (their hosted DeepSeek route).")
        )
    ),

    "provider_mistral" to HelpContent(
        title = "Mistral",
        cards = listOf(
            HelpCard("Overview", "Mistral AI — Paris-based lab founded 2023 by ex-Meta / DeepMind researchers. Originally famous for releasing strong open-weight models (Mistral-7B, Mixtral-8x7B); the proprietary API hosts a mix of closed models (Large 2, Codestral) plus open Mistral-Small / Pixtral and the Open Codestral."),
            HelpCard("Setup", "console.mistral.ai → API keys. Free tier (\"La Plateforme Free\") with limited per-minute / per-month quotas; paid plans unlock higher limits + priority. EU-based service (data residency in EU)."),
            HelpCard("Models", "`mistral-small-latest` (default), `mistral-large-latest`, `mistral-medium-latest`, `codestral-latest` (code), `pixtral-12b-2409` (vision), `open-mistral-7b`, `open-mixtral-8x7b` / 8x22b. `modelFilter=mistral|open-mistral|codestral|pixtral`. `defaultModelSource=API`."),
            HelpCard("Pricing & quirks", "**`seedFieldName=random_seed`** — Mistral's only structural deviation from the OpenAI shape. The dispatcher writes `random_seed` instead of `seed` based on `service.seedFieldName`. Otherwise vanilla OpenAI-compatible. `openRouterName=mistralai`. Codestral has a separate endpoint (`codestral.mistral.ai`) configurable via Endpoints; default is the unified `api.mistral.ai`."),
            HelpCard("Pitfalls", "If you hand-edit JSON in the API Test screen and use `seed` instead of `random_seed`, Mistral silently ignores it (no error, just non-determinism). Free tier's per-minute limit kicks in fast on fan-out runs. Pixtral pricing is the same as Mistral-12B but image-to-token accounting can surprise."),
            HelpCard("Related", "Console: `https://console.mistral.ai/api-keys/`. OpenRouter alias: `mistralai`. Codestral has its own endpoint card on the Endpoints screen.")
        )
    ),

    "provider_perplexity" to HelpContent(
        title = "Perplexity",
        cards = listOf(
            HelpCard("Overview", "Perplexity AI — search-grounded answer engine. Their Sonar API runs LLMs that perform live web searches every turn and return answers with inline citations. Distinct from a vanilla chat API — the response carries a citation list as a first-class field."),
            HelpCard("Setup", "perplexity.ai/settings/api → generate an API key. Paid usage from the first call; per-call cost on Sonar covers both the underlying LLM and the search itself. Some endpoints require Pro subscription tier."),
            HelpCard("Models", "4 hardcoded fallback models: `sonar` (default), `sonar-pro`, `sonar-reasoning-pro`, `sonar-deep-research`. The deep-research variant runs many internal agentic searches per call. `modelFilter=sonar|llama` keeps stale Llama-passthrough ids visible only when intentionally listed."),
            HelpCard("Pricing & quirks", "`supportsCitations=true` — responses include an inline `citations` array that the dispatcher pulls into `AnalysisResponse.citations`. `supportsSearchRecency=true` — the request body accepts a `search_recency_filter` parameter (day / week / month). `chat` path is `chat/completions` (not `v1/chat/completions`). Otherwise OpenAI-compatible. `openRouterName=perplexity`."),
            HelpCard("Pitfalls", "Citations only render when the chosen model supports them — a non-Sonar passthrough returns no citations, even though the response object still has the field. Deep-research calls can run for minutes; the OkHttp read timeout (600s) covers it but some upstream proxies don't. Search-recency filter is a string; typos silently disable filtering."),
            HelpCard("Related", "Console: `https://www.perplexity.ai/settings/api`. OpenRouter alias: `perplexity`. The dispatcher's citations extraction lives in `ApiDispatch.kt`.")
        )
    ),

    "provider_together" to HelpContent(
        title = "Together AI",
        cards = listOf(
            HelpCard("Overview", "Together AI — open-weight model serving platform founded 2022. Hosts hundreds of community models plus their own fine-tunes (Llama 3.x Instruct Turbo, Mixtral, Qwen, DeepSeek, Stable Diffusion). Headquartered in Menlo Park."),
            HelpCard("Setup", "api.together.xyz/settings/api-keys → create a key. Free \"Build\" tier with a credit allowance; paid plans for production. Phone verification on new accounts to combat abuse."),
            HelpCard("Models", "`meta-llama/Llama-3.3-70B-Instruct-Turbo` (default), Llama 4 Scout / Maverick, DeepSeek-V3 / R1, Qwen 3, Mixtral 8x22B, Mistral Small/Large, Gemma, Stable Diffusion. `modelFilter=chat|instruct|llama` keeps the catalog focused on chat-capable ids. `defaultModelSource=API`."),
            HelpCard("Pricing & quirks", "**`modelListFormat=array`** — Together's `/models` endpoint returns a bare top-level array (no `{ \"data\": [...] }` wrapper). The parser handles both. Native pricing is read from the `/v1/models` payload itself and persists alongside the OpenRouter snapshot in `together_pricing.json`. `litellmPrefix=together_ai`."),
            HelpCard("Pitfalls", "Catalog rotates quickly; saved Agents may 404 after a model is retired (e.g. when Together flips a `Llama-3.1-` id to `-3.3-`). The Turbo / non-Turbo split can confuse pricing — both run, but Turbo is cheaper and faster. Image-generation models cost-track separately (per-image not per-token)."),
            HelpCard("Related", "Console: `https://api.together.xyz/settings/api-keys`. LiteLLM alias: `together_ai`. Pricing precedence: Together native first → LiteLLM → OpenRouter fallback.")
        )
    ),

    "provider_openrouter" to HelpContent(
        title = "OpenRouter",
        cards = listOf(
            HelpCard("Overview", "OpenRouter — aggregator that exposes dozens of upstream AI providers behind one API. Useful for failover (a single key reaches Anthropic, Google, OpenAI, Meta, Mistral, etc.) and for trying obscure models without separate signups. Also doubles as a metadata source — see the `info_provider_openrouter` page for the catalog role."),
            HelpCard("Setup", "openrouter.ai/keys → mint a key. Pay-as-you-go top-up (Stripe / crypto). They take a margin on top of the upstream provider's price; budget accordingly. Each key can be scoped (allow / deny model lists, per-key spend caps)."),
            HelpCard("Models", "Default in the app: `anthropic/claude-3.5-sonnet`. Catalog spans every major provider — model ids are slash-prefixed (`anthropic/claude-3-5-sonnet`, `meta-llama/llama-3.3-70b-instruct`, `google/gemini-2.0-flash`). `defaultModelSource=API` — picker reads the live `/v1/models` list, which is large (700+)."),
            HelpCard("Pricing & quirks", "`extractApiCost=true` — OpenRouter's response includes `usage.cost`, so the dispatcher pulls the exact per-call cost rather than computing from `tokens × unitPrice`. The same hostname (`openrouter.ai`) doubles as an info-provider; the trace category disambiguates AI calls from catalog fetches."),
            HelpCard("Pitfalls", "The slash-prefixed id is mandatory — using a bare id (e.g. `gpt-4o-mini`) returns 404. Their margin means costs can be a few percent above the upstream provider's published rate. Some upstream providers occasionally rate-limit OpenRouter as a whole; a 429 may be unrelated to YOUR usage. Free models (with `:free` suffix) come and go on short notice."),
            HelpCard("Related", "Console: `https://openrouter.ai/keys`. Doubles as an info-provider — see Help home → Info providers → OpenRouter for the metadata role.")
        )
    ),

    "provider_siliconflow" to HelpContent(
        title = "SiliconFlow",
        cards = listOf(
            HelpCard("Overview", "SiliconFlow (硅基流动) — China-based serverless inference for popular open-weight models. Hosts Qwen, DeepSeek, GLM, Llama, Stable Diffusion, BGE embeddings. Often cheaper per-token than the model authors' own hosted services."),
            HelpCard("Setup", "cloud.siliconflow.com/account/ak → mint a key. Phone verification typically required (China-region accounts use SMS). Free credit allowance on new accounts; pay-as-you-go after."),
            HelpCard("Models", "9 hardcoded fallback models: `Qwen/Qwen2.5-7B-Instruct` (default), `Qwen/Qwen2.5-14B-Instruct`, QwQ-32B, DeepSeek-V3, plus image / embedding ids. Live list at `/v1/models` — `defaultModelSource=API` so the picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. No native pricing tier — the layered lookup falls through to LiteLLM / models.dev / Helicone for whichever model id matches. No `litellmPrefix` set, so LiteLLM only matches when the model author's bare id is in their catalog."),
            HelpCard("Pitfalls", "China-region routing means non-CN users sometimes see latency spikes or carrier-IP blocks (a VPN typically fixes it). Catalog can rotate fast; the hardcoded fallback list catches some retired ids but the live `/v1/models` is authoritative. Some endpoints require model-specific request shapes (image gen) that this app doesn't yet route to."),
            HelpCard("Related", "Console: `https://cloud.siliconflow.com/account/ak`. No OpenRouter mirror.")
        )
    ),

    "provider_zai" to HelpContent(
        title = "Z.AI (Zhipu)",
        cards = listOf(
            HelpCard("Overview", "Zhipu AI (智谱清言) — Chinese AI company spun out of Tsinghua University, founded 2019. Authors of the GLM (General Language Model) family, including GLM-4, GLM-4.5, GLM-4.7, plus the CodeGeeX coding family and CharGLM persona models. The Z.AI API is the international rebrand of the BigModel platform."),
            HelpCard("Setup", "open.bigmodel.cn/usercenter/apikeys (the underlying console) → mint a key. Free credits on signup; phone / WeChat verification typical. The Z.AI rebrand provides a friendlier latency profile for non-CN users."),
            HelpCard("Models", "7 hardcoded fallback models: `glm-4.7-flash` (default), `glm-4.7`, `glm-4.5-flash`, `glm-4.5`, `glm-4.5-air`, `codegeex-4`, `charglm-3`. `modelFilter=glm|codegeex|charglm`. Live list at `models` (NOT `v1/models`); `defaultModelSource=API`."),
            HelpCard("Pricing & quirks", "`chat` path is `chat/completions` (not `v1/chat/completions`); `modelsPath=models`. Base URL has the `api/paas/v4/` segment prefixing the standard OpenAI shape. `openRouterName=z-ai`. OpenAI-compatible at the wire level."),
            HelpCard("Pitfalls", "The flagship `glm-4.7` is gated to higher-tier accounts on first signup; smaller flash variants are unrestricted. CodeGeeX has its own API endpoint variant (`api/coding/paas/v4/`) configurable via Endpoints. CharGLM persona models accept a `system_role` extension some other providers don't."),
            HelpCard("Related", "Console: `https://open.bigmodel.cn/usercenter/apikeys`. OpenRouter alias: `z-ai`. Endpoints screen has the Coding endpoint variant.")
        )
    ),

    "provider_moonshot" to HelpContent(
        title = "Moonshot (Kimi)",
        cards = listOf(
            HelpCard("Overview", "Moonshot AI (月之暗面) — Chinese AI company founded 2023, behind the Kimi assistant. Famous for very long-context models (up to 200k+ tokens) and strong Chinese-language performance. Two API surfaces: the China-region platform.moonshot.cn and the international platform.moonshot.ai."),
            HelpCard("Setup", "platform.moonshot.ai/console/api-keys → mint a key (international); platform.moonshot.cn for China-region accounts. Pay-as-you-go; new accounts get a free credit allowance."),
            HelpCard("Models", "4 hardcoded fallback models: `kimi-latest` (default), `moonshot-v1-8k`, `moonshot-v1-32k`, `moonshot-v1-128k`. Live list at `/v1/models`. `openRouterName=moonshot`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level — no special quirks. Pricing per-million on long-context variants is reasonable (the headline 128k tier is the same per-token as 32k). Auto-routed via OpenRouter alias when LiteLLM / models.dev don't have an entry."),
            HelpCard("Pitfalls", "The `kimi-latest` alias rotates underneath you; pricing tiers may lag a model swap. China-region carriers sometimes route oddly to platform.moonshot.ai; if you see consistent timeouts try the platform.moonshot.cn base URL via Provider edit → Definition · API."),
            HelpCard("Related", "Console: `https://platform.moonshot.ai/console/api-keys`. OpenRouter alias: `moonshot`.")
        )
    ),

    "provider_cohere" to HelpContent(
        title = "Cohere",
        cards = listOf(
            HelpCard("Overview", "Cohere — Toronto-based foundation model lab, founded 2019 by ex-Google Brain researchers including Aidan Gomez (one of the Transformer paper's authors). Enterprise-focused with strong RAG and tool-use tuning; the Command-R / Command-A family is their flagship chat line, plus an industry-leading Rerank API."),
            HelpCard("Setup", "dashboard.cohere.com → API keys. Free \"Trial\" key with limited per-minute / per-month quotas; production keys are paid. No phone verification required on most accounts."),
            HelpCard("Models", "4 hardcoded fallback models: `command-a-03-2025` (default), `command-r-plus-08-2024`, `command-r-08-2024`, `command-r7b-12-2024`. Plus rerank models (`rerank-v3.5`, `rerank-multilingual-v3`) and the Embed family (`embed-english-v3`, `embed-multilingual-v3`). Default model source is the bundled fallback list."),
            HelpCard("Pricing & quirks", "OpenAI-compatible chat at `compatibility/` base URL. **Native rerank endpoint wired** — `callCohereRerank` in `SecondaryResult.kt` routes Cohere-typed Rerank prompts to `/v2/rerank` and converts the response into the same `[{id, rank, score, reason}, ...]` shape the chat-model rerank flow uses. `openRouterName=cohere`."),
            HelpCard("Pitfalls", "Trial keys have a per-minute throttle that's tight on fan-out runs. The chat compatibility layer is newer than Cohere's native API — some niche params (`citation_quality`, structured `documents` arrays) only work via the native endpoints, which this app doesn't route to. Embed and Rerank are billed in \"search units\" — `RerankApiResult.billedSearchUnits` surfaces the count for cost tracking."),
            HelpCard("Related", "Console: `https://dashboard.cohere.com/`. OpenRouter alias: `cohere`. Rerank endpoint impl: `data/SecondaryResult.kt::callCohereRerank`.")
        )
    ),

    "provider_ai21" to HelpContent(
        title = "AI21 Labs",
        cards = listOf(
            HelpCard("Overview", "AI21 Labs — Tel Aviv-based foundation model lab, founded 2017. Famous for the Jurassic-1 / Jurassic-2 generation models and (more recently) the Jamba family — a hybrid State-Space-Model + Transformer architecture that scales gracefully to long context."),
            HelpCard("Setup", "studio.ai21.com → API keys. Free trial credits; pay-as-you-go after."),
            HelpCard("Models", "4 hardcoded fallback models: `jamba-mini` (default), `jamba-large`, `jamba-mini-1.7`, `jamba-large-1.7`. Live list at `/v1/models`. `openRouterName=ai21`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level — no structural quirks. Pricing tier flow goes LiteLLM → models.dev → OpenRouter fallback. Jamba's hybrid arch shows up as faster latency on long contexts than a pure-Transformer model of similar size would have."),
            HelpCard("Pitfalls", "AI21's older Jurassic API used a different request shape; you may see `/jurassic` references in old docs that won't work via this app's chat path. The `1.6` / `1.7` minor version split sometimes prices differently in tiers — manual override on Costs is the workaround."),
            HelpCard("Related", "Console: `https://studio.ai21.com/`. OpenRouter alias: `ai21`.")
        )
    ),

    "provider_dashscope" to HelpContent(
        title = "DashScope (Alibaba Qwen)",
        cards = listOf(
            HelpCard("Overview", "DashScope — Alibaba Cloud's model-as-a-service platform, hosting the Qwen family (their flagship LLMs) plus image / audio / embedding models. The bundled URL points at the international mirror (`dashscope-intl.aliyuncs.com/compatible-mode/`); the China-region service runs at `dashscope.aliyuncs.com`."),
            HelpCard("Setup", "dashscope.console.aliyun.com → mint an API key. Requires an Alibaba Cloud account; international accounts can sign up directly. Free credit allowance; usage-based billing after."),
            HelpCard("Models", "6 hardcoded fallback models: `qwen-plus` (default), `qwen-max`, `qwen-turbo`, `qwen-long`, `qwen3-235b-a22b`, `qwen-coder-plus`. Plus Qwen-VL (vision), QwQ (reasoning), embedding models. The compatible-mode base routes to OpenAI-shape; the native DashScope shape is different."),
            HelpCard("Pricing & quirks", "`compatible-mode/` segment in the base URL is the OpenAI-shape gateway. No `defaultModelSource=API` set — the hardcoded fallback drives the picker until the user fetches. Pricing is competitive on Qwen-Plus / Turbo; Max is positioned against GPT-4o."),
            HelpCard("Pitfalls", "The international vs China-region split matters — the international URL routes outside China but sometimes throttles harder. Some Qwen variants are gated by region (e.g. Qwen-Math is only on the China URL). Image / audio model ids show in `/v1/models` but route via different endpoints this app doesn't yet plumb."),
            HelpCard("Related", "Console: `https://dashscope.console.aliyun.com/`. The China-region URL `dashscope.aliyuncs.com` can be set via Provider edit → Definition · API → Base URL.")
        )
    ),

    "provider_fireworks" to HelpContent(
        title = "Fireworks AI",
        cards = listOf(
            HelpCard("Overview", "Fireworks AI — open-weight model serving founded by ex-Meta PyTorch engineers, 2022. Hosts Llama, Mixtral, DeepSeek, Qwen, plus their own fine-tunes. Strong performance + competitive pricing on open-weight chat models."),
            HelpCard("Setup", "app.fireworks.ai → API keys. Free credits on signup; pay-as-you-go after. Phone verification on new accounts."),
            HelpCard("Models", "4 hardcoded fallback models: `llama-v3p3-70b-instruct` (default), `deepseek-r1-0528`, `qwen3-235b-a22b`, `llama-v3p1-8b-instruct`. Model ids are prefixed `accounts/fireworks/models/<id>` — the full path is the model id sent in the request body. Live list at `/v1/models`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible chat at `inference/` base URL. The model-id naming convention (`accounts/<owner>/models/<id>`) lets users-with-an-account host their own fine-tunes alongside the official catalog. No special dispatch quirks beyond the long ids."),
            HelpCard("Pitfalls", "The `accounts/fireworks/models/` prefix surprises new users — copy the full id from the catalog. Catalog rotates quickly; expect ids to drift between Llama 3.1 → 3.2 → 3.3 → 4. Some hosted models are FP8 / FP4 quantized — pricing is per-token but quality may differ from the upstream."),
            HelpCard("Related", "Console: `https://app.fireworks.ai/`. No `litellmPrefix` set; pricing falls through to OpenRouter fallback when applicable.")
        )
    ),

    "provider_cerebras" to HelpContent(
        title = "Cerebras",
        cards = listOf(
            HelpCard("Overview", "Cerebras Systems — wafer-scale AI hardware company (their CS-3 chip is a single 46,225 mm² wafer). Their inference cloud delivers very high tokens-per-second (often >1000 tok/s on Llama 3.1-70B) thanks to keeping entire models in on-wafer SRAM."),
            HelpCard("Setup", "cloud.cerebras.ai → API keys. Generous free tier with daily token limits; paid plans for production. Phone verification typical."),
            HelpCard("Models", "5 hardcoded fallback models: `llama-3.3-70b` (default), `llama-4-scout-17b-16e-instruct`, `llama3.1-8b`, plus Qwen and DeepSeek variants. Default model source is the bundled fallback; `/v1/models` is the live source. Catalog is small by design — Cerebras only hosts a curated set that fits well on their wafer."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing per-token is competitive given the speed; the headline number is tokens-per-second, not just dollars. No special dispatch quirks."),
            HelpCard("Pitfalls", "Free-tier daily quota hits fast on fan-out runs. The very high throughput sometimes overwhelms downstream parsers — if your Reports flow shows truncated streaming, the SSE buffering in `ApiStreaming` should be fine but third-party log capture may not be. Some preview models retire on short notice."),
            HelpCard("Related", "Console: `https://cloud.cerebras.ai/`. No OpenRouter mirror.")
        )
    ),

    "provider_sambanova" to HelpContent(
        title = "SambaNova",
        cards = listOf(
            HelpCard("Overview", "SambaNova Systems — RDU (Reconfigurable Dataflow Unit) AI hardware company. Like Cerebras, they sell inference speed on open-weight models — Llama, DeepSeek, Qwen — running on their own custom silicon. Headquartered in Palo Alto."),
            HelpCard("Setup", "cloud.sambanova.ai → API keys. Free tier with daily request quotas; paid plans for production."),
            HelpCard("Models", "5 hardcoded fallback models: `Meta-Llama-3.3-70B-Instruct` (default), `DeepSeek-R1`, `DeepSeek-V3-0324`, plus Qwen variants. Note the capitalised ids — SambaNova mirrors the upstream model author's casing exactly. Live list at `/v1/models`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. Pricing competitive; throughput high. No special dispatch quirks beyond the case-sensitive ids."),
            HelpCard("Pitfalls", "Case-sensitive model ids — `meta-llama-3.3-70b-instruct` (lowercase) returns 404; you need the capitalised form. Catalog smaller than Together / Fireworks. Some preview models gate behind enterprise tier."),
            HelpCard("Related", "Console: `https://cloud.sambanova.ai/`. No OpenRouter mirror.")
        )
    ),

    "provider_baichuan" to HelpContent(
        title = "Baichuan",
        cards = listOf(
            HelpCard("Overview", "Baichuan Intelligence (百川智能) — Chinese AI lab founded 2023 by Wang Xiaochuan (Sogou founder). Authors of the Baichuan family of LLMs (Baichuan-1 through Baichuan-4-Turbo); strong on Chinese-language tasks plus general bilingual capability."),
            HelpCard("Setup", "platform.baichuan-ai.com → API keys. China-region service; requires Chinese phone verification on most accounts. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "5 hardcoded fallback models: `Baichuan4-Turbo` (default), `Baichuan4`, `Baichuan4-Air`, `Baichuan3-Turbo`, `Baichuan3-Turbo-128k`. Capitalised ids — match the platform's exact casing. No live `defaultModelSource=API` set; bundled fallback drives the picker until refresh."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. No `openRouterName` or `litellmPrefix` — pricing tiers may have spotty coverage; manual override on Costs is the workaround for accurate cost tracking."),
            HelpCard("Pitfalls", "China-region accounts mostly. Non-CN users sometimes see carrier-route latency; a local VPN often improves consistency. The Baichuan-3 family is being phased out in favor of Baichuan-4 — check the platform announcements before committing a saved Agent to a 3-class id."),
            HelpCard("Related", "Console: `https://platform.baichuan-ai.com/`. No OpenRouter / LiteLLM mirror.")
        )
    ),

    "provider_stepfun" to HelpContent(
        title = "StepFun",
        cards = listOf(
            HelpCard("Overview", "StepFun (阶跃星辰) — Chinese AI lab founded 2023, behind the Step model family. Strong long-context performance (Step-2 up to 16k / 32k tokens) plus a multimodal Step-3 line. Known for Chinese-language coding and reasoning."),
            HelpCard("Setup", "platform.stepfun.com → API keys. China-region; SMS verification typical. Free credits on signup."),
            HelpCard("Models", "6 hardcoded fallback models: `step-2-16k` (default), `step-3`, `step-2-mini`, `step-1-8k`, plus a couple of vision variants. No `defaultModelSource=API` — fallback drives the picker."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. No external pricing-tier mirror — manual cost overrides recommended for accurate accounting. Step-3 multimodal pricing varies by image-token count."),
            HelpCard("Pitfalls", "China-region. Some Step-3 multimodal calls require a different request shape this app doesn't yet plumb (the chat path handles text-only fine). The Step-1 family is older and being phased out."),
            HelpCard("Related", "Console: `https://platform.stepfun.com/`. No OpenRouter / LiteLLM mirror.")
        )
    ),

    "provider_minimax" to HelpContent(
        title = "MiniMax",
        cards = listOf(
            HelpCard("Overview", "MiniMax (稀宇科技) — Chinese AI company founded 2021, behind the abab and MiniMax-M model families. Their Hailuo AI consumer product runs on these models. Known for multimodal generation (text, audio, video) plus Chinese-language strength."),
            HelpCard("Setup", "platform.minimax.io → API keys (international); platform.minimaxi.com for the China-region service. Free credits on signup."),
            HelpCard("Models", "4 hardcoded fallback models: `MiniMax-M2.1` (default), `MiniMax-M2`, `MiniMax-M1`, `MiniMax-Text-01`. Live list at `/v1/models` (no `defaultModelSource=API` set, so manual list drives the picker)."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. `openRouterName=minimax` so the OpenRouter fallback can pick up cross-provider pricing. The international vs China platform split affects latency for non-CN users."),
            HelpCard("Pitfalls", "Audio and video generation models exist in the catalog but route via different endpoints this app doesn't yet plumb — only chat models work end-to-end. Capital-M model ids are case-sensitive."),
            HelpCard("Related", "Console: `https://platform.minimax.io/`. OpenRouter alias: `minimax`.")
        )
    ),

    "provider_nvidia" to HelpContent(
        title = "NVIDIA NIM",
        cards = listOf(
            HelpCard("Overview", "NVIDIA Inference Microservices (NIM) — NVIDIA's API platform for hosted open-weight models. Hosts NVIDIA's own Nemotron family plus a 3rd-party catalog (Llama, Mistral, DeepSeek, Qwen). Free tier of 1000 credits / month for personal projects."),
            HelpCard("Setup", "build.nvidia.com → sign in with NVIDIA Developer account → mint API key. The free tier for personal accounts gives a generous credit allowance; enterprise accounts get paid scaling."),
            HelpCard("Models", "Default: `nvidia/llama-3.1-nemotron-70b-instruct`. Catalog is large — every NVIDIA-hosted model carries a slash-prefixed id (`nvidia/<model>`, `meta/<model>`, `mistralai/<model>`). `defaultModelSource=API` so picker reads the live list."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. The `integrate.api.nvidia.com/` base URL routes to the NIM platform; the actual model serving runs on NVIDIA's GPU infrastructure. No `openRouterName` / `litellmPrefix` — pricing tiers fall through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "The slash-prefixed id (`nvidia/<model>`) is mandatory. Some preview models in the catalog require an enterprise license. The free credit allowance resets monthly; heavy fan-out can deplete it."),
            HelpCard("Related", "Console: `https://build.nvidia.com/`. NVIDIA Developer account required.")
        )
    ),

    "provider_replicate" to HelpContent(
        title = "Replicate",
        cards = listOf(
            HelpCard("Overview", "Replicate — model marketplace founded 2019 by Ben Firshman (ex-Docker). Hosts thousands of open-weight models (chat, image, audio, video) with per-second billing. Strong for image generation (Flux, SDXL, Imagen-class community models); chat support is bolted on."),
            HelpCard("Setup", "replicate.com/account/api-tokens → mint a token. Free credits on signup; pay-per-second after. Note the GitHub-style flow — you sign in with GitHub on most accounts."),
            HelpCard("Models", "3 hardcoded fallback chat models: `meta/meta-llama-3-70b-instruct` (default), `meta/meta-llama-3-8b-instruct`, `mistralai/mistral-7b-instruct-v0.2`. Replicate's actual catalog spans tens of thousands of models — most are image / audio, not chat. `chat` path is `chat/completions`."),
            HelpCard("Pricing & quirks", "Per-second billing is unique — chat models running on shared GPUs round to the second. The OpenAI-compatible chat endpoint is newer than Replicate's native `predictions/` API; some models exist only on the native shape this app doesn't plumb. Slash-prefixed ids (`<owner>/<model>`)."),
            HelpCard("Pitfalls", "Per-second billing means a slow stream costs more than a fast one for the same output. Cold-start latency on rarely-used models can be 30+ seconds. Image models (most of the catalog) don't fit the chat path — try Together / DeepInfra for those flows."),
            HelpCard("Related", "Console: `https://replicate.com/account/api-tokens`. No OpenRouter / LiteLLM mirror.")
        )
    ),

    "provider_huggingface" to HelpContent(
        title = "HuggingFace Inference",
        cards = listOf(
            HelpCard("Overview", "Hugging Face Inference API — serverless inference for thousands of open-weight models hosted on the HF Hub. Different from HuggingFace's role as an info-provider for model-card metadata; same API key works for both. The Pro subscription unlocks higher rate limits."),
            HelpCard("Setup", "huggingface.co/settings/tokens → mint a Read token. Anonymous calls are heavily rate-limited; the Free tier with token unlocks meaningful usage; HF Pro lifts limits further. Some gated models require accepting the model card terms in a browser first."),
            HelpCard("Models", "4 hardcoded fallback models: `meta-llama/Llama-3.1-70B-Instruct` (default), `meta-llama/Llama-3.1-8B-Instruct`, `Mistral-7B-Instruct-v0.3`, plus a Falcon. Slash-prefixed ids match the HF Hub repo path. The Inference API supports far more models than the picker shows; copy a repo id from huggingface.co/models to use it."),
            HelpCard("Pricing & quirks", "OpenAI-compatible chat at `/v1/chat/completions`. The base URL is `api-inference.huggingface.co/`. Cold-start latency on rarely-used models is significant (HF spins down idle endpoints). Note: this is the HF *provider* (chat); the HF *info-provider* (model-card metadata) uses the same key but reads from `huggingface.co/api/models/{id}`."),
            HelpCard("Pitfalls", "Same `huggingFaceApiKey` is used by both this provider AND the model-card info-provider — they're conceptually distinct but share the key. Gated models (Llama 3.1 70B, some Mistral fine-tunes) return 401 / 403 until you accept terms on the HF Hub. HF Pro subscription is separate from API credit cost."),
            HelpCard("Related", "Console: `https://huggingface.co/settings/tokens`. The same key feeds the `info_provider_huggingface` lookups.")
        )
    ),

    "provider_lambda" to HelpContent(
        title = "Lambda Labs",
        cards = listOf(
            HelpCard("Overview", "Lambda Labs — GPU cloud company (founded 2012); also runs an inference API for open-weight models on their H100 / H200 fleet. Headquartered in San Francisco. Their Inference Cloud is a smaller catalog focused on currently-popular Llama, Mistral, and Hermes fine-tunes."),
            HelpCard("Setup", "cloud.lambdalabs.com/api-keys → API keys. Need an existing Lambda Cloud account (mostly self-service signup; some enterprise gating). Pay-as-you-go."),
            HelpCard("Models", "Default: `hermes-3-llama-3.1-405b-fp8`. Catalog is curated — Hermes fine-tunes, Llama 3.x, Mistral. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing per-million is competitive on the FP8-quantized variants. No special dispatch quirks. No `openRouterName` / `litellmPrefix` — pricing falls through to OpenRouter cross-provider fallback or DEFAULT."),
            HelpCard("Pitfalls", "Catalog small and rotates fast. The 405B FP8 default is fast but the FP8 quantization may differ from upstream FP16 quality on edge cases. Lambda's GPU cloud is a separate product — don't confuse Inference Cloud rate limits with GPU instance availability."),
            HelpCard("Related", "Console: `https://cloud.lambdalabs.com/api-keys`.")
        )
    ),

    "provider_lepton" to HelpContent(
        title = "Lepton AI",
        cards = listOf(
            HelpCard("Overview", "Lepton AI — serverless model-serving platform, founded 2023 by ex-Alibaba / Caffe creators. Acquired by NVIDIA in 2025. Hosts Llama, Mistral, Gemma, Whisper, plus image/audio. Strong on cold-start performance."),
            HelpCard("Setup", "dashboard.lepton.ai → API keys. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "4 hardcoded fallback models: `llama3-1-70b` (default), `llama3-1-8b`, `mistral-7b`, `gemma2-9b`. Note the dashed (not dotted) version naming — Lepton's catalog uses `llama3-1-70b` rather than `llama-3.1-70b`. No `defaultModelSource=API` set."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. The dash-instead-of-dot naming is the only structural quirk. No `openRouterName` / `litellmPrefix` — pricing falls through to fallbacks."),
            HelpCard("Pitfalls", "Naming convention catches new users — copying a `llama-3.1-70b` id from elsewhere returns 404 here; you need `llama3-1-70b`. The NVIDIA acquisition (2025) may eventually merge Lepton into NIM; URL stability not guaranteed long-term."),
            HelpCard("Related", "Console: `https://dashboard.lepton.ai/`.")
        )
    ),

    "provider_01ai" to HelpContent(
        title = "01.AI (Yi)",
        cards = listOf(
            HelpCard("Overview", "01.AI (零一万物) — Chinese AI lab founded 2023 by Kai-Fu Lee (ex-Google China, Microsoft Research). Authors of the Yi model family — Yi-Lightning, Yi-Large, Yi-Medium, Yi-Spark — bilingual but optimized for Chinese."),
            HelpCard("Setup", "platform.01.ai → API keys. China-region; SMS verification required for most accounts. Free credit allowance; pay-as-you-go after. The international URL `api.01.ai` is the public surface."),
            HelpCard("Models", "4 hardcoded fallback models: `yi-lightning` (default), `yi-large`, `yi-medium`, `yi-spark`. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. No special dispatch quirks. Pricing falls through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "China-region. Some accounts are gated to China-only IP routing. Yi-Lightning is the headline cheap+fast model; Yi-Large positions against GPT-4o-mini / Claude Haiku."),
            HelpCard("Related", "Console: `https://platform.01.ai/`.")
        )
    ),

    "provider_doubao" to HelpContent(
        title = "Doubao (ByteDance)",
        cards = listOf(
            HelpCard("Overview", "Doubao (豆包) — ByteDance's AI model family, served via Volcano Engine (火山引擎). Hosted on `ark.cn-beijing.volces.com` — the Beijing region of Volcano Engine's API platform. The chat path is at `v3/chat/completions` (not the standard v1)."),
            HelpCard("Setup", "console.volcengine.com (Volcano Engine console) → mint API key. Requires a Volcano Engine account; phone verification typical (Chinese SMS). Free credit allowance; usage-based billing after."),
            HelpCard("Models", "4 hardcoded fallback models: `doubao-pro-32k` (default), `doubao-pro-128k`, `doubao-lite-32k`, `doubao-lite-128k`. Pro vs Lite tiers; 32k vs 128k context split."),
            HelpCard("Pricing & quirks", "**`chat` path is `v3/chat/completions`** (not `v1/chat/completions`) — Volcano Engine's versioning is independent of OpenAI's. Otherwise OpenAI-compatible. China-region service; non-CN users may see carrier-routing variance."),
            HelpCard("Pitfalls", "Path quirk catches users who hand-edit JSON — make sure the path stays `v3/chat/completions`. China-region; some carriers don't route to `volces.com` at all without configuration. Doubao-Pro-128k pricing is the same per-token as 32k but cache-policy differs."),
            HelpCard("Related", "Console: `https://console.volcengine.com/`. The base URL `ark.cn-beijing.volces.com/api/` segments are stable but the v3 chat path is a key oddity.")
        )
    ),

    "provider_reka" to HelpContent(
        title = "Reka",
        cards = listOf(
            HelpCard("Overview", "Reka AI — multimodal foundation-model lab founded 2022 by ex-DeepMind / Google Brain / Meta researchers. Headquartered in San Francisco. Reka-Core / Flash / Edge are their model tiers — all natively multimodal (text, image, video, audio)."),
            HelpCard("Setup", "platform.reka.ai → API keys. Pay-as-you-go; commercial pricing similar to mid-tier frontier models."),
            HelpCard("Models", "3 hardcoded fallback models: `reka-flash` (default), `reka-core`, `reka-edge`. Flash is the speed/cost balance; Core is the flagship; Edge is the smallest. No `defaultModelSource=API` set."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level for chat. Multimodal inputs (images, video) work but require image/video URLs or base64 the dispatcher already supports for vision. Pricing falls through to fallbacks."),
            HelpCard("Pitfalls", "Catalog small (3 models). Native video understanding is a Reka strength but few app flows surface video input. Reka-Core gates behind higher-tier accounts on first signup."),
            HelpCard("Related", "Console: `https://platform.reka.ai/`.")
        )
    ),

    "provider_writer" to HelpContent(
        title = "Writer",
        cards = listOf(
            HelpCard("Overview", "Writer Inc. — enterprise generative AI platform founded 2020. Authors of the Palmyra family — domain-tuned LLMs marketed for legal, medical, finance, marketing copy. Headquartered in San Francisco; primarily B2B."),
            HelpCard("Setup", "app.writer.com → API keys. Enterprise-focused — most accounts come from a sales engagement; self-service signup gives a Free trial. Pay-as-you-go for production."),
            HelpCard("Models", "2 hardcoded fallback models: `palmyra-x-004` (default), `palmyra-x-003-instruct`. Catalog small and curated. No `defaultModelSource=API` set; bundled list drives the picker."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing falls through to fallbacks. Writer's strength is the enterprise sales / governance side, not unique API quirks."),
            HelpCard("Pitfalls", "Self-service Free trial caps tightly; production requires the sales conversation. Domain-tuned models may behave differently from general-purpose Llama / GPT — prompt engineering targeted to that variant works best."),
            HelpCard("Related", "Console: `https://app.writer.com/`.")
        )
    ),

    "provider_cloudflareworkersai" to HelpContent(
        title = "Cloudflare Workers AI",
        cards = listOf(
            HelpCard("Overview", "Cloudflare Workers AI — Cloudflare's serverless inference for open-weight models, running at the edge across their global network. Hosts a curated catalog of Llama, Mistral, Gemma, Phi, plus image / speech models. Free tier with generous monthly quotas; paid scaling integrated into the Workers / Pages platform."),
            HelpCard("Setup", "dash.cloudflare.com → AI → Workers AI → API tokens. **You must replace `YOUR_ACCOUNT_ID` in the base URL** with your actual Cloudflare account id (visible on the right rail of the Workers dashboard) before keys work. Token + account-id together authorize the call."),
            HelpCard("Models", "Default: `@cf/meta/llama-3.3-70b-instruct-fp8-fast`. Catalog uses Cloudflare-prefixed slash ids — `@cf/<owner>/<model>`. `defaultModelSource=API` so the picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. The `@cf/<owner>/<model>` id convention is mandatory — bare ids return 404. The base URL `api.cloudflare.com/client/v4/accounts/YOUR_ACCOUNT_ID/ai/` is the unique structural quirk."),
            HelpCard("Pitfalls", "Forgetting to replace `YOUR_ACCOUNT_ID` is the most common setup failure — the placeholder ships verbatim in the bundled provider definition; first-run config requires editing it under Provider edit → Definition · Basics → Base URL. The free tier resets monthly. Some models are FP8 / FP16 quality-tradeoffs — the `-fast` suffix is FP8."),
            HelpCard("Related", "Console: `https://dash.cloudflare.com/`. The Workers AI section in the Cloudflare dashboard shows your account id.")
        )
    ),

    "provider_deepinfra" to HelpContent(
        title = "DeepInfra",
        cards = listOf(
            HelpCard("Overview", "DeepInfra — open-weight model serving founded 2022. Hosts Llama, Mistral, DeepSeek, Qwen, Mixtral, plus embedding and image models. Headquartered in Palo Alto; competitive per-token pricing on the popular open-weight chat models."),
            HelpCard("Setup", "deepinfra.com/dash/api_keys → mint a key. Free credit allowance on signup; pay-as-you-go after."),
            HelpCard("Models", "Default: `meta-llama/Meta-Llama-3.1-70B-Instruct`. `chat` path is `chat/completions` (not `v1/chat/completions`); `modelsPath=models` (not `v1/models`). `defaultModelSource=API` so the picker auto-refreshes from the live list."),
            HelpCard("Pricing & quirks", "Base URL is `api.deepinfra.com/v1/openai/` — the `/v1/openai/` segment is the OpenAI-compatible gateway. Slash-prefixed ids (`<owner>/<model>`). No `litellmPrefix` / `openRouterName` set — pricing falls through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "Path quirks (`chat/completions`, `models` — no `v1/` prefix on those parts) trip hand-edited requests. The `/v1/openai/` URL segment is the OpenAI gateway; native DeepInfra requests use a different path the app doesn't route to."),
            HelpCard("Related", "Console: `https://deepinfra.com/dash/api_keys`.")
        )
    ),

    "provider_hyperbolic" to HelpContent(
        title = "Hyperbolic",
        cards = listOf(
            HelpCard("Overview", "Hyperbolic Labs — open-weight model serving plus image / audio APIs, founded 2023. Hosts DeepSeek, Llama, Qwen, Mistral, plus vision and TTS models. Compute is sourced from a mix of in-house and partner GPU capacity."),
            HelpCard("Setup", "app.hyperbolic.xyz/settings → API keys. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "Default: `deepseek-ai/DeepSeek-V3`. `defaultModelSource=API` so picker auto-refreshes. Catalog includes chat, image, audio, vision-language models."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Slash-prefixed ids. No `litellmPrefix` / `openRouterName` — pricing falls through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "Image / audio models live in the catalog but don't fit the chat dispatch path — only chat models work end-to-end here. Some preview models gate behind tier upgrades."),
            HelpCard("Related", "Console: `https://app.hyperbolic.xyz/settings`.")
        )
    ),

    "provider_novitaai" to HelpContent(
        title = "Novita.ai",
        cards = listOf(
            HelpCard("Overview", "Novita.ai — open-weight serverless inference, founded 2023. Hosts Llama, Mistral, Qwen, DeepSeek; competitive per-token pricing on common open-weight models. Headquartered in Singapore."),
            HelpCard("Setup", "novita.ai/settings/key-management → mint a key. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "Default: `meta-llama/llama-3.1-70b-instruct`. `chat` path is `chat/completions`; `modelsPath=models`. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "Base URL is `api.novita.ai/v3/openai/` — the `/v3/openai/` segment is the OpenAI-compatible gateway. Slash-prefixed ids. No `litellmPrefix` / `openRouterName` — pricing falls through."),
            HelpCard("Pitfalls", "Path quirks similar to DeepInfra — `chat/completions` / `models` (no `v1/` prefix on those parts). The `/v3/openai/` segment is stable but worth noting if you hand-edit URLs."),
            HelpCard("Related", "Console: `https://novita.ai/settings/key-management`.")
        )
    ),

    "provider_featherlessai" to HelpContent(
        title = "Featherless.ai",
        cards = listOf(
            HelpCard("Overview", "Featherless.ai — serverless host for HuggingFace open-weight models, founded 2024. Subscription-based pricing (flat monthly fee for unlimited usage on chosen tier) rather than per-token, making it distinctive in the open-weight serving space."),
            HelpCard("Setup", "featherless.ai/account/api-keys → mint a key. Subscription tiers (Feather / Wing / Falcon-class) determine which models you can run; pay-monthly upfront."),
            HelpCard("Models", "Default: `meta-llama/Meta-Llama-3.1-8B-Instruct`. Slash-prefixed ids matching HF Hub repo paths. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. Subscription billing means token-based pricing tiers (LiteLLM, etc.) don't really apply — your cost is the flat monthly fee. The Costs / Usage screens still report token counts but the dollar conversion via per-token rates won't reflect actual subscription cost."),
            HelpCard("Pitfalls", "Subscription model breaks token-based cost tracking — manual cost overrides set to $0 / $0 give a more honest view if you're on a flat plan. Some larger models (70B+) require higher tiers; the API returns a tier-error which surfaces as 403/402."),
            HelpCard("Related", "Console: `https://featherless.ai/account/api-keys`.")
        )
    ),

    "provider_liquidai" to HelpContent(
        title = "Liquid AI",
        cards = listOf(
            HelpCard("Overview", "Liquid AI — Boston-based foundation-model lab, founded 2023 by MIT researchers. Famous for the LFM (Liquid Foundation Models) series — non-Transformer architectures derived from continuous-time recurrent networks. Strong performance per parameter, especially on long context."),
            HelpCard("Setup", "platform.liquid.ai → API keys. Pay-as-you-go from signup; smaller free trial credit than larger providers."),
            HelpCard("Models", "Default: `lfm-7b`. Catalog: LFM-7B, LFM-40B, plus instruct variants. `defaultModelSource=API` so picker auto-refreshes. Smaller catalog overall."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. The non-Transformer architecture is a Liquid AI selling point but transparent at the API level — calls look like any other chat completion. No special dispatch quirks."),
            HelpCard("Pitfalls", "Smaller catalog and less-tested ecosystem mean fewer model lookups in LiteLLM / OpenRouter — pricing tiers may have spotty coverage. Cold-start latency varies."),
            HelpCard("Related", "Console: `https://platform.liquid.ai/`. Liquid AI's research papers explain the LFM architecture in depth.")
        )
    ),

    "provider_llamaapi" to HelpContent(
        title = "Llama API (Meta)",
        cards = listOf(
            HelpCard("Overview", "Meta's official Llama API — direct hosted inference for the Llama family, run by Meta themselves. Distinct from Llama-on-other-providers (Together, Groq, Fireworks, …) which run open-weight derivatives. Beta product; integration with developer.meta.com."),
            HelpCard("Setup", "llama.developer.meta.com → API keys. Beta / waitlist depending on signup timing; some accounts get instant access."),
            HelpCard("Models", "Default: `Llama-4-Maverick-17B-128E-Instruct-FP8`. Catalog focuses on the Llama 4 family (Maverick, Scout) plus current Llama 3.x. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "Base URL is `api.llama.com/compat/` — the `/compat/` segment is the OpenAI-compatible gateway. Otherwise standard. No `litellmPrefix` / `openRouterName` — pricing falls through to fallbacks."),
            HelpCard("Pitfalls", "Beta product means API stability isn't guaranteed across signups; expect occasional non-backwards-compatible changes. Same model id (Llama 3.3-70B, etc.) on this provider may price / perform differently from the Together / Groq / Fireworks hosted variants."),
            HelpCard("Related", "Console: `https://llama.developer.meta.com/`.")
        )
    ),

    "provider_krutrim" to HelpContent(
        title = "Krutrim (Ola)",
        cards = listOf(
            HelpCard("Overview", "Ola Krutrim — Indian AI lab and inference platform, part of Ola Cabs founder Bhavish Aggarwal's tech portfolio. Founded 2023 with a focus on Indian-language understanding alongside general-purpose open-weight model serving."),
            HelpCard("Setup", "cloud.olakrutrim.com/console → API keys. Indian-region service; international signups possible. Free credit allowance."),
            HelpCard("Models", "Default: `Meta-Llama-3.1-70B-Instruct`. Catalog: open-weight Llama / Mistral / Qwen plus Krutrim's own multilingual models. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing falls through to OpenRouter cross-provider fallback. Strong Indian-language support is a Krutrim differentiator."),
            HelpCard("Pitfalls", "India-region routing means non-IN users may see latency variance. Smaller catalog than Together / Fireworks. Native Indian-language models exist but specific endpoints may not all be plumbed via the chat path."),
            HelpCard("Related", "Console: `https://cloud.olakrutrim.com/console`.")
        )
    ),

    "provider_nebiusaistudio" to HelpContent(
        title = "Nebius AI Studio",
        cards = listOf(
            HelpCard("Overview", "Nebius AI Studio — inference platform from Nebius (the AI-cloud arm of the former Yandex international assets). Headquartered in Amsterdam; runs a large GPU fleet across European data centers. Hosts Llama, DeepSeek, Qwen, Mistral, Mixtral."),
            HelpCard("Setup", "studio.nebius.com/settings/api-keys → mint a key. Free credits on signup; pay-as-you-go after. Strong European data residency story for users with that preference."),
            HelpCard("Models", "Default: `meta-llama/Meta-Llama-3.1-70B-Instruct`. Slash-prefixed ids matching HF Hub paths. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. Base URL is `api.studio.nebius.com/`. Pricing competitive on the popular open-weight chat models. No `litellmPrefix` / `openRouterName` — pricing falls through."),
            HelpCard("Pitfalls", "Newer entrant — model catalog occasionally rotates as they add capacity for new ids. The Yandex history is irrelevant to data flow today (Nebius is a separate Netherlands-incorporated entity), but worth noting if procurement asks."),
            HelpCard("Related", "Console: `https://studio.nebius.com/settings/api-keys`.")
        )
    ),

    "provider_chutes" to HelpContent(
        title = "Chutes",
        cards = listOf(
            HelpCard("Overview", "Chutes — open-weight inference platform built on top of the Bittensor decentralized AI network. Compute is sourced from Bittensor miners (subnet 64 / Chutes); pricing reflects the decentralized economics. Founded 2024."),
            HelpCard("Setup", "chutes.ai/app/api → mint a key. Free credit allowance; pay-as-you-go after via TAO (Bittensor's native token) or fiat."),
            HelpCard("Models", "Default: `deepseek-ai/DeepSeek-V3`. Slash-prefixed ids. Catalog: DeepSeek, Llama, Qwen, Mistral, plus Bittensor-native fine-tunes. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. Base URL is `llm.chutes.ai/`. Decentralized compute can give variance in latency / quality across the same model id depending on which miner serves the request — Chutes routes to a chosen one but cold-starts vary."),
            HelpCard("Pitfalls", "Decentralized compute means quality / availability can vary turn-to-turn — for production fan-out, pricier centralized providers (Together / Fireworks) are more deterministic. The TAO billing surface is unusual; most users prefer the fiat top-up."),
            HelpCard("Related", "Console: `https://chutes.ai/app/api`. Bittensor subnet 64 is the underlying network.")
        )
    ),

    "provider_inferencenet" to HelpContent(
        title = "Inference.net",
        cards = listOf(
            HelpCard("Overview", "Inference.net — open-weight serverless inference, founded 2024. Hosts Llama, DeepSeek, Qwen on a low-priced compute pool. Headquartered in San Francisco."),
            HelpCard("Setup", "inference.net/dashboard/api-keys → mint a key. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "Default: `meta-llama/llama-3.3-70b-instruct/fp-8`. Note the third path segment (`/fp-8`) — Inference.net's id convention encodes the quantization in the id itself. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. The `/<owner>/<model>/<quant>` three-part id convention is the structural quirk — copy ids verbatim from their catalog. Pricing falls through to fallbacks."),
            HelpCard("Pitfalls", "The quantization-in-id convention catches new users — `meta-llama/llama-3.3-70b-instruct` (without `/fp-8`) returns 404. Smaller catalog than Together / Fireworks; newer entrant means less LiteLLM coverage so pricing tiers may have gaps."),
            HelpCard("Related", "Console: `https://inference.net/dashboard/api-keys`.")
        )
    )
)

