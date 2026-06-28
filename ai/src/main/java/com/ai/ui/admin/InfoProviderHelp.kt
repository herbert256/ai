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
    "info_provider_requesty" to HelpContent(
        title = "Help - Requesty (info provider)",
        cards = listOf(
            HelpCard("Overview", "Requesty is a cross-provider LLM router — one endpoint in front of 500+ models from dozens of upstream providers. As a side effect it publishes a public model catalog with per-token pricing and capability flags, which we read as a pricing + capability tier."),
            HelpCard("What we use it for", "Pricing + capability fallback. Sits just after the OpenRouter cross-provider fallback in the layered lookup (before Helicone), so it backfills models the curated tiers and OpenRouter miss. Its vision / reasoning / web-search flags also feed the capability chain after models.dev. No data is ever sent to Requesty — we only read the public catalog."),
            HelpCard("Endpoint", "`https://router.requesty.ai/v1/models` — anonymous, JSON, no API key. Returns every public model. Refreshed on demand from Refresh → Requesty (and as part of Refresh all)."),
            HelpCard("Freshness", "Requesty curates aggressively to keep their router catalog current, so prices track upstream changes well. A bundled snapshot ships with the app, so Requesty pricing works on a fresh install before the first refresh."),
            HelpCard("Pitfalls", "Ids are `<vendor>/<model>` (OpenRouter-style), matched via the same prefix-bucket logic. Prices are already per-token (no \$/M conversion). Some upstream-specific routing tags (`:free`, `@region`) only resolve through the loose bare-match fallback."),
        )
    ),
    "info_provider_llm_stats" to HelpContent(
        title = "Help - llm-stats (info provider)",
        cards = listOf(
            HelpCard("Overview", "llm-stats.com (by ZeroEval) is an independent catalog + benchmark aggregator. It publishes per-model metadata, per-provider pricing, and category benchmark scores. We use the pricing as a curated tier and keep the benchmark scores + modalities for the Model Info raw view."),
            HelpCard("What we use it for", "Pricing tier right after Artificial Analysis in the layered lookup. Each model lists several providers, each with its own \$/M price — we collapse that to one representative rate (the model maker's own price when present, else the cheapest). Its `modalities` also feed the vision capability flag."),
            HelpCard("Endpoint", "`https://api.llm-stats.com/stats/v1/models` — Bearer key, paginated via `next_cursor`. Refreshed on demand from Refresh → llm-stats (disabled until the key is set)."),
            HelpCard("Freshness", "Independently measured / aggregated. A bundled snapshot ships with the app so the tier works before the first refresh."),
            HelpCard("Pitfalls", "The key needs Stats-API onboarding — until you complete the request at llm-stats.com/developer it returns `403 stats_api_access_denied` and the tier stays empty. `top_scores` mixes benchmark scales upstream, so it's shown raw and never collapsed into a single quality index. Not every model carries pricing (catalog-only entries)."),
        )
    ),
    "info_provider_genai_prices" to HelpContent(
        title = "Help - genai-prices (info provider)",
        cards = listOf(
            HelpCard("Overview", "genai-prices is a Pydantic open-source project that tracks LLM API pricing across 30+ providers, with historic and tiered prices. We read its consolidated price catalog as a pricing tier (we don't use the Python package — just the published JSON)."),
            HelpCard("What we use it for", "Pricing fallback. Sits just after Requesty in the layered lookup (before Helicone), backfilling models the curated tiers miss. Same shape as models.dev — provider → models → \$/M prices, plus a context window we keep for the token-limit chain. No data is ever sent to genai-prices."),
            HelpCard("Endpoint", "`https://raw.githubusercontent.com/pydantic/genai-prices/main/prices/data_slim.json` — a single file, no auth. Refreshed on demand from Refresh → genai-prices (and as part of Refresh all)."),
            HelpCard("Freshness", "Maintained alongside the Pydantic project with historic price tracking, so it's reasonably current. A bundled snapshot ships with the app, so the tier works on a fresh install before the first refresh."),
            HelpCard("Pitfalls", "Keys are `<provider>/<model>` (like models.dev), matched via the prefix-bucket logic. Prices are \$/M tokens (converted to per-token on load). A few models use conditional / tiered pricing — we take the unconstrained (base) rate and skip per-tier variants."),
        )
    ),
    "info_provider_truefoundry" to HelpContent(
        title = "Help - TrueFoundry (info provider)",
        cards = listOf(
            HelpCard("Overview", "TrueFoundry publishes an open, community-maintained model registry on GitHub — one YAML file per model across ~20 providers, with per-token pricing, capability features, modalities, and token limits. We read it as a pricing + capability tier."),
            HelpCard("What we use it for", "Pricing + capability fallback. Sits just before Helicone in the layered lookup. Its vision / tool-calling / reasoning flags also feed the capability chain. Prices are already per-token in the YAML (no \$/M conversion)."),
            HelpCard("Endpoint", "`https://github.com/truefoundry/models` — there's no single data file, so Refresh downloads the whole-repo `.tar.gz` archive and unpacks + parses the per-model YAML on-device. Keyless. This download is larger than the other catalogs."),
            HelpCard("Freshness", "Community-driven via pull requests. A bundled snapshot ships with the app so the tier works before the first refresh; the live refresh re-downloads the current repo."),
            HelpCard("Pitfalls", "Keys are `<provider>/<model>` from the repo's directory layout. The refresh is heavier than the JSON catalogs (archive download + on-device unzip + YAML parse of ~1000 files). Provider-level `default.yaml` files are skipped — only per-model files carry costs."),
        )
    ),
    "info_provider_cloudprice" to HelpContent(
        title = "Help - CloudPrice (info provider)",
        cards = listOf(
            HelpCard("Overview", "CloudPrice (ai.cloudprice.net) is a unified LLM specs / pricing / cost-calculator API covering ~2800 models. We use only its model catalog: capability flags, modalities, and context windows."),
            HelpCard("What we use it for", "Capabilities only — NOT a pricing tier. CloudPrice's bulk model list carries no inline pricing (its prices live behind per-model calculator endpoints), so it never joins the cost lookup. Its vision / tool-calling / reasoning / web-search flags + context windows feed the capability chain, like a richer HuggingFace."),
            HelpCard("Endpoint", "`https://ai.cloudprice.net/api/v1/models` — anonymous, paginated via `next_token`. Refreshed on demand from Refresh → CloudPrice (and as part of Refresh all)."),
            HelpCard("Freshness", "Aggregated catalog, updated regularly upstream. A bundled snapshot ships with the app so the capability flags work on a fresh install before the first refresh."),
            HelpCard("Pitfalls", "Capabilities only — you'll never see CloudPrice in the per-model Costs breakdown, by design. Keys are `<creator>/<name>`, matched via the prefix-bucket logic. The bulk list is paginated, so a refresh walks several pages."),
        )
    ),
)
