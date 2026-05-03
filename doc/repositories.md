# External Repositories

This app consults seven external sources for model metadata, pricing, and
capabilities. They are layered — when one source is silent, the next is
consulted. All seven round-trip through the backup zip so a restored
device picks up where it left off without needing fresh network calls.

The lookup precedence in `PricingCache.getPricing` for a
`(provider, model)` pair is (top → bottom — first hit wins):

```
1. Provider self-report:
   - OpenRouter native (only when caller's provider is OPENROUTER)
   - Together AI native (only when caller's provider is TOGETHER)
2. LiteLLM (curated bulk)
3. models.dev (curated bulk)
4. llm-prices.com (curated bulk)
5. Artificial Analysis (curated bulk)
6. Manual override (user-set per (provider, model))
7. OpenRouter cross-provider fallback
8. Helicone (last resort — known data-quality issues, kept only
   so we have *some* answer before falling to default)
9. DEFAULT ($0 / $0)
```

Together AI's native pricing is read out of its `/v1/models` payload
and persists alongside the OpenRouter snapshot under the same
file/blob layout (see `together_pricing.json` below).

Capabilities (`supportsVision`, `supportsWebSearch`, `supportsFunctionCalling`)
follow a similar layered order, with the per-provider `/models` response
winning when it surfaces the field directly.

**Storage:** the large tier blobs all live as files under
`<filesDir>/pricing/<key>.json` (one per tier — see
[persistent.md](persistent.md) for the full file list). Only the
small `*_timestamp` longs and the user's `manual_pricing` map stay
in `pricing_cache.xml`. `PricingCache.loadBlob` falls back to the
legacy prefs key once on first read after the upgrade, copies the
JSON to the file, and removes the prefs entry — old installs
migrate transparently.

---

## 1. LiteLLM

- **Endpoint:** `https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json`
- **Auth:** none (public)
- **Provides:**
  - `input_cost_per_token`, `output_cost_per_token` (prompt / completion price)
  - `supports_vision`, `supports_web_search`, `supports_function_calling`
  - `max_input_tokens`, `max_output_tokens`
  - `mode` (chat / embedding / rerank / image / etc.) — used as the
    authoritative model-type when more specific than CHAT
  - `litellm_provider` — used to compose the lookup key together with
    `AppService.litellmPrefix`
- **When fetched:** on Refresh screen → "LiteLLM"; also on Refresh All.
  No bundled fallback — a fresh install has zero LiteLLM coverage
  until the user runs Refresh once. The layered lookup just falls
  through to the next tier (models.dev / Helicone / …) for any model
  whose pricing the user hasn't fetched yet.
- **Cache:** `<filesDir>/pricing/litellm_pricing.json` and
  `litellm_meta.json` (the catalog + capability sidecar);
  `litellm_timestamp` long in `pricing_cache.xml`.

## 2. OpenRouter

- **Endpoint:** `https://openrouter.ai/api/v1/models` (detailed)
- **Auth:** Bearer token (External Services → OpenRouter)
- **Provides:**
  - `pricing.prompt`, `pricing.completion` — converted from per-token to
    per-token Double
  - `architecture.modality` and `architecture.input_modalities` — used to
    auto-flag models that accept image input
  - `top_provider.context_length` / `max_completion_tokens`
  - `supported_parameters` — used by the dispatch layer to filter out
    unsupported parameters before sending the request
- **When fetched:** on Refresh screen → "OpenRouter"; on Refresh All;
  on the AI Usage screen if the OpenRouter cache is stale.
- **Cross-pollination:** OpenRouter's per-model `mode` label is mirrored
  to every other provider's stored model-types map under
  `<openRouterName>/<modelId>`, giving every catalog free type tags.
- **Cache:** `<filesDir>/pricing/openrouter_pricing.json`;
  `openrouter_timestamp` long in `pricing_cache.xml`.

## 3. models.dev

- **Endpoint:** `https://models.dev/api.json`
- **Auth:** none (public)
- **Provides:**
  - Per-vendor model catalog with input/output prices, vision/tool
    capabilities, context length
- **Cache:** `<filesDir>/pricing/models_dev_pricing.json` and
  `models_dev_meta.json` (capabilities sidecar);
  `models_dev_timestamp` long in `pricing_cache.xml`.
- **When fetched:** Refresh screen → "models.dev"; Refresh All.
- **Note:** fetched via `ApiFactory.fetchUrlAsString` so the call flows
  through `TracingInterceptor` and `RateLimitRetryInterceptor` (a previous
  `URL.openStream`-based version silently failed on first install).

## 4. Helicone

- **Endpoint:** `https://www.helicone.ai/api/llm-costs`
- **Auth:** none (public)
- **Provides:** input/output cost per token. Match operators are
  `equals` / `startsWith` / `includes` — `findHeliconePricing` honours
  all three so a model id matched by any operator picks up the price.
- **Cache:** `<filesDir>/pricing/helicone_pricing.json` (exact-match
  rules) and `helicone_patterns.json` (`startsWith` / `includes`
  rules); `helicone_timestamp` long in `pricing_cache.xml`.

## 5. llm-prices

- **Endpoint:** `https://raw.githubusercontent.com/simonw/llm-prices/main/data/<vendor>.json`
- **Auth:** none (public)
- **Provides:** pricing snapshot maintained by Simon Willison; multiple
  per-vendor JSON files fetched concurrently and merged.
- **Cache:** `<filesDir>/pricing/llmprices_pricing.json`;
  `llmprices_timestamp` long in `pricing_cache.xml`.

## 6. Artificial Analysis

- **Endpoint:** `https://artificialanalysis.ai/api/v2/data/llms/models`
- **Auth:** API key in `x-api-key` header (External Services →
  Artificial Analysis; free tier — sign up at artificialanalysis.ai/api)
- **Provides:**
  - `pricing.price_1m_input_tokens` / `price_1m_output_tokens`
  - `evaluations.artificial_analysis_intelligence_index` — quality score
  - `median_output_tokens_per_second` — speed score
- **Composite key:** `<model_creator.slug>/<slug>` (lowercased), e.g.
  `google/gemini-2-5-pro`. Bumped to `_v2` keys to invalidate older
  UUID-keyed entries from the previous parser revision.
- **Cache:** `<filesDir>/pricing/aa_pricing_v2.json` and
  `aa_meta_v2.json` (intelligence + speed scores);
  `aa_timestamp_v2` long in `pricing_cache.xml`.

## 7. HuggingFace

- **Endpoint:** `https://huggingface.co/api/models/{modelId}`
- **Auth:** Bearer token (External Services → HuggingFace) — required to
  avoid aggressive rate-limiting on unauthenticated lookups
- **Provides:** model card metadata — license, downloads, likes,
  pipeline_tag, library_name, tags, dataset references, base-model
  pointers. Surfaced on the Model Info screen.
- **Cache:** `huggingface_cache` SharedPreferences with a 7-day TTL.
  Negative results are cached so a model with no HF mirror doesn't
  re-hit the API every screen open.
- **When fetched:** lazily, the first time a Model Info screen is opened
  for `(provider, model)` whose entry is stale or missing.

---

## Per-provider `/models` endpoints

In addition to the seven external repositories above, every active
provider's own `/models` (or equivalent) endpoint is consulted at fetch
time to discover the model list. The response is parsed for capabilities
the provider self-reports — Mistral's `capabilities` object, Cohere's
`endpoints` array, Gemini's `supportedGenerationMethods`, OpenRouter's
`architecture.input_modalities`, etc. — and stored as
`ProviderConfig.modelCapabilities`. The provider's own response wins
over the layered external sources for any field it populates.

The raw JSON of each provider's last `/models` response is preserved in
`ProviderConfig.modelListRawJson` so a future parser revision can pull
out additional fields without forcing a re-fetch.
