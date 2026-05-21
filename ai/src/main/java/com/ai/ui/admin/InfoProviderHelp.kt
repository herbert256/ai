package com.ai.ui.admin

internal val infoProviderHelp: Map<String, HelpContent> = mapOf(
    "info_provider_huggingface" to HelpContent(
        title = "Help - HuggingFace (info provider)",
        cards = listOf(
            HelpCard("Overview", "Hugging Face Inc. runs the largest public registry of machine-learning models, datasets, and demo \"Spaces\". For this app it's a metadata source: we read its model-card API to pull license, context length, capability tags, and the README blurb that surfaces on Model Info."),
            HelpCard("What we use it for", "Per-model lookups against `https://huggingface.co/api/models/{id}`. Surfaces in Model Info → Sources card under HuggingFace as the raw JSON the call returned. Not a pricing source — HF doesn't publish per-token costs."),
            HelpCard("Endpoint", "`https://huggingface.co/api/models/{id}` (model card metadata). Anonymous calls are rate-limited; setting an HF token under External Services raises the limit and unlocks gated model metadata."),
            HelpCard("Freshness", "Lookups are on-demand — each Model Info open re-hits HF for that one model. There's no scheduled refresh because the data is already model-scoped."),
            HelpCard("Pitfalls", "Gated / private models return 401 without a token. Some \"models\" are duplicate aliases (e.g. fine-tuned forks) — the API returns whatever the user typed, not a canonical id. Long descriptions can balloon the response — we render the JSON tree truncated."),
        )
    ),
    "info_provider_openrouter" to HelpContent(
        title = "Help - OpenRouter (info provider)",
        cards = listOf(
            HelpCard("Overview", "OpenRouter is an aggregator that proxies requests to dozens of upstream AI providers behind a single API. The app uses it in two roles: as an AI provider itself (chat / completion) AND as a metadata + pricing catalog spanning every model OpenRouter routes to."),
            HelpCard("What we use it for", "Two endpoints: a global catalog with prompt / completion prices, and a per-model specs lookup with capability fields (context, supports vision, supports tools, etc.). Both feed the layered pricing lookup and Model Info."),
            HelpCard("Endpoint", "Catalog: `https://openrouter.ai/api/v1/models` (auth optional but recommended). Per-model: `https://openrouter.ai/api/v1/models/{id}/endpoints`. API key under External Services raises rate limits."),
            HelpCard("Freshness", "Refreshed on demand from Refresh → OpenRouter (full catalog) or implicitly when Model Info opens (per-model specs). Catalog refresh disables when no API key is set."),
            HelpCard("Pitfalls", "OpenRouter quotes the upstream provider's price plus its own margin; numbers can drift from the provider's own published rates. Model ids are slash-prefixed (`anthropic/claude-3-5-sonnet`) — the catalog uses those, while LiteLLM uses bare ids."),
        )
    ),
    "info_provider_litellm" to HelpContent(
        title = "Help - LiteLLM (info provider)",
        cards = listOf(
            HelpCard("Overview", "LiteLLM is an open-source library by BerriAI that abstracts the SDKs of every major AI provider behind one shape. It also ships a curated JSON catalog of every model the maintainers know about, with input/output token prices, context windows, and capability flags."),
            HelpCard("What we use it for", "We pull the JSON catalog (no SDK / no proxy server — just the data file) and use it as the primary pricing tier in the layered lookup. Also feeds the capability sets (vision, web search, system-message support, …) that drive UI badges."),
            HelpCard("Endpoint", "`https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json` — a single file, no auth, no rate limit beyond GitHub's. Refreshed on demand from Refresh → LiteLLM."),
            HelpCard("Freshness", "Updates land in the LiteLLM repo within days of a provider price change — usually faster than the model's own pricing page goes live in the marketing site. Stale by up to a week is normal."),
            HelpCard("Pitfalls", "Lags `-latest` aliases (model_prices keys are dated ids). New models from less-popular providers can take longer to appear. Keys are bare ids (no provider/ prefix), unlike OpenRouter."),
        )
    ),
    "info_provider_models_dev" to HelpContent(
        title = "Help - models.dev (info provider)",
        cards = listOf(
            HelpCard("Overview", "models.dev is a community-curated catalog of AI models with a single JSON dump endpoint. Slimmer than LiteLLM but covers some entries (and some capability fields) that LiteLLM lags on, so it sits as a per-field fallback in our pricing chain."),
            HelpCard("What we use it for", "Pricing + capability fallback when LiteLLM has no entry for a model. Same shape as LiteLLM: prompt/completion price + context window + supports-X flags."),
            HelpCard("Endpoint", "`https://models.dev/api.json` — anonymous, single file. Refreshed on demand from Refresh → models.dev."),
            HelpCard("Freshness", "Community-driven; updates are less predictable than LiteLLM's. Use both — the layered lookup will pick whichever has a non-null entry first."),
            HelpCard("Pitfalls", "Coverage gaps for niche providers; some entries lag price changes. The JSON shape sometimes drifts — the parser is forgiving but a totally new field would silently be ignored."),
        )
    ),
    "info_provider_helicone" to HelpContent(
        title = "Help - Helicone (info provider)",
        cards = listOf(
            HelpCard("Overview", "Helicone is an AI observability platform — they run a hosted service that logs LLM API calls. As a side product they publish a public LLM-costs JSON aggregating per-model prices across every provider they instrument."),
            HelpCard("What we use it for", "Pricing-only fallback. We never send Helicone any data — we only read the public llm-costs endpoint and slot it into the layered lookup."),
            HelpCard("Endpoint", "`https://www.helicone.ai/api/llm-costs` — anonymous, JSON. Refreshed on demand from Refresh → Helicone."),
            HelpCard("Freshness", "Publishing cadence varies. Used as a low-priority fallback because Helicone's coverage is biased toward providers their customers actually use; coverage of long-tail models is thinner than LiteLLM's."),
            HelpCard("Pitfalls", "Pricing only — no capability fields. Helicone's id format sometimes diverges from the provider's own (case differences). The parser falls back gracefully when an id can't be matched."),
        )
    ),
    "info_provider_llm_prices" to HelpContent(
        title = "Help - llm-prices.com (info provider)",
        cards = listOf(
            HelpCard("Overview", "llm-prices is Simon Willison's hand-curated tracker of frontier-model pricing across roughly 10 vendors (OpenAI, Anthropic, Google, xAI, Meta, Mistral, DeepSeek, …). Smaller scope than LiteLLM but updated quickly when prices change."),
            HelpCard("What we use it for", "Pricing fallback. Per-vendor JSON files in the simonw/llm-prices GitHub repo; one file per vendor, fetched on demand."),
            HelpCard("Endpoint", "`https://raw.githubusercontent.com/simonw/llm-prices/main/data/{vendor}.json` (no auth). Refreshed on demand from Refresh → llm-prices.com."),
            HelpCard("Freshness", "Hand-maintained, often updated within hours of a price announcement. Coverage is intentionally narrow — it's a curated hot-list of frontier models, not a complete catalog."),
            HelpCard("Pitfalls", "Only covers ~10 vendors. Models from anything else won't be found here. The repo's data shape is stable but small schema drifts are possible — we tolerate missing fields."),
        )
    ),
    "info_provider_artificial_analysis" to HelpContent(
        title = "Help - Artificial Analysis (info provider)",
        cards = listOf(
            HelpCard("Overview", "Artificial Analysis is an independent benchmarking company. They publish curated model metadata + pricing alongside their own latency / quality benchmarks. We use only the metadata + pricing slice."),
            HelpCard("What we use it for", "Pricing + capability fallback in the layered lookup. Their dataset is hand-curated and tends to align well with the provider's own pricing page."),
            HelpCard("Endpoint", "`https://artificialanalysis.ai/api/v2/data/llms/models` — requires an API key set under External Services. Refreshed on demand from Refresh → Artificial Analysis (button disabled until the key is set)."),
            HelpCard("Freshness", "Updates are reasonably timely — they instrument the providers themselves, so price-change detection is part of their workflow. Not as fast as llm-prices for the very-new-frontier models."),
            HelpCard("Pitfalls", "API key is mandatory; without it the catalog never loads and AA stays absent from the layered lookup. Some niche models aren't covered."),
        )
    ),
)
