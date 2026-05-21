package com.ai.ui.admin

internal val providerSettingsHelp: Map<String, HelpContent> = mapOf(
    "provider_card_state" to HelpContent(
        title = "Help - Provider state",
        cards = listOf(
            HelpCard("What the four states mean", "🔑 ok — a working API key has been tested against the picked Default Model. Pickers, Refresh All, the active-only filter all treat this provider as usable.\n❌ error — the last test failed; the trace icon links to the captured request/response.\n💤 inactive — the user explicitly turned the provider off. Calls and pickers skip it.\n⭕ not-used — no key set yet."),
            HelpCard("Activation flow (Switch ON)", "Flipping the inactive Switch to OFF kicks off two network calls in sequence: 1) a fetch of the live model list from the provider's /models endpoint, 2) a test call against the picked Default Model using the API key from the card below. Both must pass before the state goes 🔑."),
            HelpCard("On success", "State flips to 🔑. A default agent named after the provider is auto-created (and added to the 'default agents' Flock) so the report flow has something to dispatch to without the user opening the Agents screen."),
            HelpCard("On failure", "State goes ❌. The captured trace from the failed call is one tap away via the bug icon next to the test result. Fix the issue (key, model id, network) and either tap Test on the API Key card or flip the Switch off+on to retry."),
            HelpCard("Switch OFF (deactivate)", "Goes straight to 💤 — no network call. Reverses by flipping back on, which re-runs the activation flow.")
        )
    ),
    "provider_card_apikey" to HelpContent(
        title = "Help - API Key card",
        cards = listOf(
            HelpCard("Where it's stored", "Per-provider SharedPreferences slot named `<id>_api_key` (e.g. `OpenAI_api_key`). Masked behind the eye toggle on the input field — never logged in API traces, never shipped through any export bundle marked 'no keys'."),
            HelpCard("Test button", "Fires one round-trip request against the model picked below using this key. Shows 'Connection successful' green on pass, the provider error message red on fail. Tagged with a per-test trace so the bug icon next to a failure links straight to the captured request / response."),
            HelpCard("State coupling", "A successful Test atomically flips the provider's state to 🔑 AND adds the default agent (named after the provider) to the 'default agents' Flock. A failed Test flips state to ❌ — same as the activation Switch."),
            HelpCard("Models row", "Opens the dedicated per-provider Models screen — live API list when the Models card → Default source is API; the manual / hardcoded subset when it's MANUAL. The number to the right is the count currently in the catalog."),
            HelpCard("Default Model row", "Writes the picked model into AppService.defaultModel — the single source of truth for what every API call uses by default. Sorted by output-token cost ascending so the cheap end of the catalog lands on top; pricing is rendered on the picker rows and is provider-aware (live → LiteLLM → models.dev → llm-prices → Artificial Analysis → manual override → OpenRouter cross-provider → Helicone → DEFAULT).")
        )
    ),
    "provider_card_basics" to HelpContent(
        title = "Help - Basics",
        cards = listOf(
            HelpCard("Base URL", "Root of every API call to this provider. Paths declared on the API card (`v1/chat/completions`, `v1/messages`, etc.) append to this. Should end with a slash; the dispatcher normalises both shapes."),
            HelpCard("Admin URL", "Provider's web dashboard — where the user gets / rotates an API key. Optional. Rendered as a tappable link on the per-provider help page so the user can jump out and grab a key."),
            HelpCard("Identity is immutable", "The provider id (= its display label) lives in the title bar above and can't be edited in place. Pre-unification builds carried separate id / displayName / prefsKey fields; the unification refactor collapsed them into one. Renaming a provider is a delete-and-add operation, not an in-place change.")
        )
    ),
    "provider_card_api" to HelpContent(
        title = "Help - API",
        cards = listOf(
            HelpCard("Format", "Selects the dispatch path:\n• OPENAI_COMPATIBLE — the default. Bearer auth, /v1/chat/completions request shape, SSE streaming via `data:` lines.\n• ANTHROPIC — `x-api-key` header + `anthropic-version: 2023-06-01`, /v1/messages, mandatory max_tokens on every request.\n• GOOGLE — `?key=` query param (NOT Bearer), generateContent path, no streaming for vision."),
            HelpCard("Type paths", "Per-provider override of the global per-type defaults from AI Setup → Model Types. Leave blank to inherit the user / hardcoded fallback (the placeholder shows what you'd inherit). Wrong path here yields HTTP 404 on every dispatch."),
            HelpCard("Models path", "The /models endpoint URL relative to Base URL. Defaults to `v1/models`. Some providers expose `models` (no `v1/`); set explicitly when the default 404s."),
            HelpCard("Model list format", "Flips between 'object' (provider returns `{data: [...]}` wrapping the list) and 'array' (provider returns the list directly). Wrong choice yields zero models on fetch with no useful error — the fetcher silently parses the wrong shape and gets nothing."),
            HelpCard("Seed field name", "What the request body calls the determinism seed. Most providers: `seed`. Mistral: `random_seed`. The dispatcher only sets this field when the user picks a Parameters preset with a non-null seed.")
        )
    ),
    "provider_card_models" to HelpContent(
        title = "Help - Models",
        cards = listOf(
            HelpCard("Default source", "Decides where the per-provider Models screen seeds its list from on the first open + on Refresh:\n• API — hits the provider's /models endpoint on every refresh. Live catalog.\n• MANUAL — hands you an empty list to populate by hand from Hardcoded models. Useful for providers whose /models shape isn't supported, or when you want to lock the picker down to a curated subset."),
            HelpCard("Model filter regex", "Java regex (case-insensitive) that trims the live or hardcoded list to entries whose id matches. Examples:\n• `gpt|o1|o3|o4` on OpenAI hides the Whisper / DALL·E / TTS rows.\n• `claude` on Anthropic strips legacy aliases.\nLeave blank to keep everything the API returns. A bad regex compiles silently to a no-op — the field accepts the typo but the persisted value stays at the last good one."),
            HelpCard("Hardcoded models", "Asset-shipped fallback list. Applied verbatim in MANUAL mode. Unioned into the API list at fetch time when the Capability flags card has Merge hardcoded models enabled (OpenAI uses this — its /v1/models silently omits TTS / image / moderation endpoints).")
        )
    ),
    "provider_card_pricing" to HelpContent(
        title = "Help - Pricing & cost",
        cards = listOf(
            HelpCard("OpenRouter name", "Maps this provider into OpenRouter's catalog so cross-provider pricing fan-out works. Example: Anthropic ships `openRouterName: 'anthropic'` so the layered lookup finds `anthropic/claude-3-5-sonnet` pricing on OpenRouter when no native source has it. Required for layered-pricing pickup; harmless when blank for fully self-priced providers."),
            HelpCard("LiteLLM prefix", "Slug used as the lookup key in the LiteLLM catalog. Per-provider override over the lowercased provider id fallback. Example: Together ships `litellmPrefix: 'together_ai'` because LiteLLM uses that slug, not 'together'. Leave blank to use the lowercased id."),
            HelpCard("Cost ticks divisor", "Providers that report cost in fractional ticks (xAI's $/1e10 scale) divide their per-call usage cost by this factor before rolling into the report's total. Leave blank for providers that bill in plain USD per token."),
            HelpCard("Extract API cost", "When ON, the dispatcher reads the cost figure straight off the response body — OpenRouter ships per-call cost in its usage object; most others don't. When OFF, cost is computed locally from token counts × the layered ModelPricing rate (provider self-report → LiteLLM → models.dev → llm-prices → Artificial Analysis → manual override → OpenRouter cross-provider → Helicone → DEFAULT).")
        )
    ),
    "provider_card_throttle" to HelpContent(
        title = "Help - Throttle & retry",
        cards = listOf(
            HelpCard("How the override works", "Every field is a per-provider override of the global default (Settings → Per-provider throttling / 429 / 529 error handling). Leave a field blank to inherit the global value. Useful when one provider has a stricter API tier than the rest, or is more sensitive to bursts."),
            HelpCard("Max calls per minute", "Sliding-window cap enforced by the OkHttp ProviderThrottleInterceptor on this provider's baseUrl host plus any aux hosts. A 61st call inside a 60-second window blocks until the oldest call exits the window. Blank = use the global value."),
            HelpCard("Max concurrent calls", "Semaphore cap on simultaneous in-flight calls to this provider. Stacks on top of the per-minute rate limit. Lower this if the provider 429s under fan-out / report bursts that the global cap allows."),
            HelpCard("Max retries on 429", "How many times a 429 response is re-attempted inline before the call is reported as failed. The Retry-After header wins over the wait-between value below when the provider sends one."),
            HelpCard("Wait between 429 retries (ms)", "Base back-off between the 429 retries above. The retry interceptor applies a small jitter on top so two coroutines hitting the same provider don't re-fire at the exact same millisecond."),
            HelpCard("Max retries on 529 / Wait between 529 retries (ms)", "Same shape as the 429 fields but for 529 'overloaded' responses — Anthropic's signal that the model itself (not the rate limiter) couldn't keep up. Treated separately because 529 usually clears in 1-2 seconds whereas 429 may need much longer.")
        )
    ),
    "provider_card_features" to HelpContent(
        title = "Help - Features",
        cards = listOf(
            HelpCard("Supports citations", "When ON, the response parser pulls the Perplexity-style `citations` array out of the response and surfaces it inline on the per-agent result card. Only Perplexity sets this today. OpenAI's Responses-API web search uses a different shape and is gated separately by the Responses API patterns under Model patterns."),
            HelpCard("Supports search recency", "When ON, the dispatcher attaches Perplexity's `search_recency_filter` request param (`day` / `week` / `month` / `year`) when the user picks one on the report's web-search dropdown. Providers without this flag get the recency dropdown hidden — there's no point offering a knob the API can't honour."),
            HelpCard("Provider-wide gates", "Both fields are simple Boolean switches on the dispatch path, NOT model-name patterns. They apply to every model under this provider, not a subset. Per-model gating is in Model patterns.")
        )
    ),
    "provider_card_native" to HelpContent(
        title = "Help - Native APIs",
        cards = listOf(
            HelpCard("Aux hosts", "Comma-separated list of alternate hostnames the provider's traffic lands on besides its baseUrl host. Example: Cohere — its compat shim lives on `api.cohere.ai` but the native rerank / capability endpoints land on `api.cohere.com`. Without the aux host the trace-list Provider filter would attribute those calls to '(unknown)'."),
            HelpCard("Native rerank URL", "Full URL of a Cohere v2/rerank-shaped POST endpoint. When set, Reports → Rerank routes through it instead of the chat-model fallback. Blank = the user is told to pick a chat model and the prompt-based rerank flow runs. Cohere's `https://api.cohere.com/v2/rerank` is the canonical example."),
            HelpCard("Native moderation URL", "Full URL of a Mistral v1/moderations-shaped POST endpoint. Same pattern as rerank — when set, the Moderate flow routes through it. Mistral's `https://api.mistral.ai/v1/moderations`."),
            HelpCard("Native capability URL", "Full URL of a Cohere-shaped /v1/models capability listing — the response carries `endpoints` / `supports_vision` / `context_length` per model. Set on providers whose OpenAI-compat shim strips that data but a separate native host returns it (Cohere again). The fetcher hits this URL alongside the regular /models call and merges the capability data into the model's row.")
        )
    ),
    "provider_card_capability" to HelpContent(
        title = "Help - Capability flags",
        cards = listOf(
            HelpCard("Pricing from /models", "Provider's /v1/models response carries authoritative pricing (input/output per million tokens) which the fetcher harvests as a self-report tier in PricingCache — wins over LiteLLM, models.dev, etc. Together AI is the canonical example: ships `pricing: {input, output}` per row."),
            HelpCard("Cross-provider model list", "Provider's /models response drives pricing + type fan-out into every OTHER provider via the openRouterName prefix. Only OpenRouter has this today; exactly one provider should. When set, fetching this provider's catalog also enriches sibling providers' model-type labels and gives them a layered-pricing fallback row."),
            HelpCard("Merge hardcoded models", "Fetcher unions the persisted Hardcoded Models list with the API list. Useful when /models silently omits valid endpoints, e.g. OpenAI's TTS / image / moderation models aren't in /v1/models but the user still wants them in the picker. Off by default — most providers' /models is exhaustive."),
            HelpCard("External reasoning signal untrusted", "Ignore the provider's `reasoning: true` field on /models metadata — capability is decided purely by Reasoning model patterns + Reasoning effort accept patterns under Model patterns. xAI uses this because some always-on reasoning variants reject the `reasoning_effort` parameter, so trusting the metadata flag would attach a parameter the API rejects.")
        )
    ),
    "provider_card_patterns" to HelpContent(
        title = "Help - Model patterns",
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
        title = "Help - Built-in endpoints",
        cards = listOf(
            HelpCard("Shape", "List of `{id, name, url, isDefault}` entries. The first `isDefault: true` shows up first when the user picks an endpoint while assigning a model to an Agent or sending a Test request. Empty list = a single synthesised default is used (built from the API card's Base URL + chat type path)."),
            HelpCard("Bundled values", "OpenAI ships Chat Completions + Responses API.\nMistral ships Chat Completions + Codestral.\nDeepSeek ships Chat Completions + Beta (FIM).\nZ.AI ships Chat Completions + Coding.\nEvery other provider ships nothing here and falls back to the synthesised default."),
            HelpCard("Round-trip", "Edits round-trip through providers.json on Export / Import — adding a custom endpoint here will appear in the exported catalog verbatim, and importing a catalog with new endpoints will land them on this card."),
            HelpCard("vs. user endpoints (Settings)", "Settings.endpoints is the per-user override map keyed by AppService. When non-empty for this provider, the user's list takes precedence; when empty, the catalog's builtInEndpoints win. The user's Add Endpoint flow (LiteLLM proxy URL etc.) writes into Settings.endpoints, never here.")
        )
    ),
)
