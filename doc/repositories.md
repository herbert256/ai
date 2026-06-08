# External Repositories

The app consults **seven external metadata repositories** for model
pricing, capabilities, and model-card information. Six of them are
*pricing/capability catalogs* that feed the layered
`PricingCache.getPricing` lookup; the seventh (HuggingFace) is a
lazy, per-model card-metadata source surfaced only on the Model Info
screen.

The six catalog tiers all round-trip through the backup zip
([backup-restore.md](backup-restore.md)) and ship a **bundled
snapshot** in `assets/info-providers/`, so a freshly installed (or
freshly restored) device has working pricing *before* it ever hits
the network. (HuggingFace is not bundled — it caches lazily into its
own SharedPreferences file.)

## Lookup precedence

For a `(provider, model)` pair, `PricingCache.getPricing`
(`data/PricingCache.kt`) walks these tiers top→bottom; **first hit
wins**:

```
0. Main-thread cold-window short-circuit
   (preload not finished AND on the main thread → DEFAULT, no I/O)
1. Provider self-report (only when the caller IS the source):
   - OpenRouter native  (provider.crossProviderModelList == true)
   - Together AI native (provider.pricingFromModelList   == true)
2. Manual override  (user-set, keyed "<provider.id>:<model>")
3. LiteLLM                (curated bulk)
4. models.dev            (curated bulk)
5. llm-prices.com        (curated bulk)
6. Artificial Analysis   (curated bulk)
7. OpenRouter cross-provider fallback (only for non-OpenRouter callers)
8. Helicone              (last resort — known data-quality issues)
9. DEFAULT_PRICING       ($25/M input, $75/M output)
```

Two things are easy to get wrong here, so spell them out:

- There are **two** provider-self-report tiers *ahead* of the manual
  override — OpenRouter-native (gated on `crossProviderModelList`)
  and Together-native (gated on `pricingFromModelList`). When the
  caller's own provider is the authoritative billing source, its
  own `/v1/models` price beats even a user override.
- The **manual override sits ahead of every curated bulk source**
  (LiteLLM, models.dev, llm-prices, AA, OpenRouter-cross-provider
  fallback, Helicone). It used to sit *behind* LiteLLM, so an
  override added specifically to correct a stale catalog entry was
  silently ignored — the opposite of what the Cost Config UI
  implies. The precedence is implemented top-to-bottom in
  `PricingCache.findPricingMatch`, and the class-level KDoc now
  matches it (override before the curated tiers). One stale
  shorthand survives — `getPricing`'s own KDoc still calls it a
  "five-tier lookup" — but the order it executes is
  `findPricingMatch`'s.

`DEFAULT_PRICING = ModelPricing("default", 25.00e-6, 75.00e-6,
"DEFAULT")` — i.e. **$25 / M input, $75 / M output**, not zero. A
deliberately high fallback so a model the catalogs miss shows an
obviously-wrong cost rather than free.

### Related lookup variants

| Function | Same as `getPricing` except… |
|---|---|
| `getPricingWithoutOverride` | skips step 2 (the manual override). Used by `cleanupRedundantManualOverrides` to decide whether an override would still win the live lookup. |
| `lookupPricing` | context-free, in-memory-only mirror (same precedence incl. override-before-LiteLLM). Never touches disk and never blocks; returns `DEFAULT_PRICING` if the catalogs aren't loaded. Used by `Settings.recomputeCapabilities`. |
| `getTierBreakdown` | computes **every** tier independently (returns a `TierBreakdown` with `litellm/modelsDev/helicone/llmPrices/artificialAnalysis/override/openrouter/together/default`). Drives the layered Costs view and the 🐞 pricing trace. |
| `pricesConflict` | true when ≥ 2 catalog tiers disagree on prompt or completion price beyond a 1% tolerance (override + default excluded). Surfaces "catalog hasn't settled" rows in the AI Models filter. |

### Cold-window caveat

`getPricing` (and `ensureLoaded`) short-circuit to `DEFAULT_PRICING`
when called on the **main thread before the preload completes**
(`!preloadCompleted && isMainThread()`) — the parse of the ~1.2 MB
catalog blobs is too expensive to run synchronously on the UI
thread. UI callers therefore see DEFAULT during the cold window and
pick up real values on the next state-driven recompose once the
off-main preload primes the cache. Don't "fix" this by removing the
guard.

## Cost computation

`computeCost(usage, pricing)` returns `usage.apiCost` verbatim when
the provider reports a total (OpenRouter, Perplexity, xAI — see
[costs.md](costs.md)); otherwise it calls `computeInOutCost`, which
does cache-aware, tier-aware token math: cached-input tokens at the
cache-read rate, Anthropic cache-creation at the cache-write rate,
and the above-200k tier prices when the request crosses 200k input
tokens. When `apiCost` is present, `computeInOutCost` splits it
pro-rata by the simple-rate baseline (or by token ratio when the
baseline is all-zero — Bug 38).

## Capabilities

Capability flags (`supportsVision`, `supportsWebSearch`,
`supportsReasoning`, `supportsFunctionCalling`) are resolved by the
`Settings.isVisionCapable` / `isWebSearchCapable` /
`isReasoningCapable` query methods (`model/SettingsModels.kt`) along
a parallel layered order: the per-provider `/models` response wins
when it self-reports the field (stored on
`ProviderConfig.modelCapabilities`, type `ModelCapabilities`), then a
manual Model-Type-Override flag (override flags can only **add** a
capability, never clear one), then LiteLLM, then a naming heuristic.
See [model-states.md](model-states.md) for the full capability /
type-override chain.

## Storage

Each catalog tier persists as a JSON blob under
`<filesDir>/pricing/<key>.json` (atomic writes); the matching
`*_timestamp` long lives in the `pricing_cache` SharedPreferences.
The user's `manual_pricing` map also lives in `pricing_cache`, **not**
a filesDir blob. `PricingCache.loadBlob`'s look-up order is:

1. the post-Refresh `<filesDir>/pricing/<key>.json` file, then
2. a **bundled snapshot** in `assets/info-providers/<key>.json`.

The bundled blob is read but **not** written through to filesDir, so
its timestamp stays unset (the UI still reads "never refreshed") and
the next Refresh overwrites both file and timestamp. See
[persistent.md](persistent.md) for the full file list.

| Tier | filesDir blob(s) | timestamp key | bundled asset |
|---|---|---|---|
| LiteLLM | `litellm_pricing.json` + `litellm_meta.json` | `litellm_timestamp` | yes |
| OpenRouter | `openrouter_pricing.json` | `openrouter_timestamp` | yes |
| models.dev | `models_dev_pricing.json` + `models_dev_meta.json` | `models_dev_timestamp` | yes |
| Helicone | `helicone_pricing.json` + `helicone_patterns.json` | `helicone_timestamp` | yes |
| llm-prices | `llmprices_pricing.json` | `llmprices_timestamp` | yes |
| Artificial Analysis | `aa_pricing_v2.json` + `aa_meta_v2.json` | `aa_timestamp_v2` | yes |
| Together-native | `together_pricing.json` | `together_timestamp` | no (harvested at runtime) |

In addition, the OpenRouter spec fetch writes one **top-level**
filesDir file — `model_supported_parameters.json` — read back by
`getSupportedParameters`. It no longer writes a sibling
`model_pricing.json`: nothing ever read that file, so the write was
dropped; the cache-clear path still deletes any `model_pricing.json`
left behind on older installs (see
[Cross-provider fan-out](#cross-provider-fan-out)).

---

## 1. LiteLLM

- **Endpoint:** `https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json`
- **Auth:** none (public)
- **Provides:**
  - `input_cost_per_token`, `output_cost_per_token` (prompt / completion price)
  - `supports_vision`, `supports_web_search`, `supports_function_calling`,
    `supports_reasoning`
  - `max_input_tokens`, `max_output_tokens`, `tool_use_system_prompt_tokens`
  - `mode` (chat / embedding / rerank / image / …) — used as the
    authoritative model-**type** when more specific than CHAT
  - `litellm_provider` — composed with `AppService.litellmPrefix` to
    build the lookup key
- **Fetched:** via `ApiFactory.fetchUrlAsString` (so it flows through
  `TracingInterceptor` + the retry interceptors). Triggered from the
  Refresh screen → "LiteLLM" and from Refresh All.
- **Cache:** `<filesDir>/pricing/litellm_pricing.json` (prices) +
  `litellm_meta.json` (capability sidecar); `litellm_timestamp` in
  `pricing_cache`. Seeded from the bundled assets of the same name.

## 2. OpenRouter

- **Endpoint:** `https://openrouter.ai/api/v1/models` (detailed)
- **Auth:** Bearer token (External Services → OpenRouter)
- **Provides:**
  - `pricing.prompt`, `pricing.completion` (converted to per-token Double)
  - `architecture.modality` / `architecture.input_modalities` — auto-flag
    models that accept image input
  - `top_provider.context_length` / `max_completion_tokens`
  - `supported_parameters` — used by the dispatch layer to drop
    parameters a model can't accept before sending the request
- **Fetched:** Refresh screen → "OpenRouter"; Refresh All; and lazily
  on the AI Usage screen when the OpenRouter cache is stale.
- **Cache:** `<filesDir>/pricing/openrouter_pricing.json`;
  `openrouter_timestamp` in `pricing_cache`.
- **Dual role:** OpenRouter is both a catalog tier *and* the
  cross-provider price/parameter fan-out source — see below.

### Cross-provider fan-out

OpenRouter ids are `<vendor>/<model>`. Two distinct mechanisms exploit
that — keep them separate:

**1. Cross-provider price fallback (step 7 of the precedence list).**
`findOpenRouterPricing` resolves a *non-OpenRouter* `(provider, model)`
lookup against the **`openrouter_pricing.json`** catalog by prefixing
the model id with the caller's `AppService.openRouterName` (then a
bucketed normalized scan). This is what gives every local provider a
cross-provider price drawn from OpenRouter's aggregated catalog — and
it reads the **same blob the OpenRouter tier loads**, not a separate
file.

**2. Per-model spec capture.** `fetchAndSaveModelSpecifications` walks
the full detailed catalog, maps each `<vendor>/<model>` id back to a
local provider via `AppService.openRouterName`, and writes two
**top-level** filesDir files:

- `model_supported_parameters.json` — `supported_parameters` per
  `(provider, model)`, read by `getSupportedParameters` so the
  dispatch layer can drop parameters a model can't accept.
- `model_pricing.json` — legacy `ModelPricingEntry(provider, model,
  pricing)` rows. **No longer written** — nothing ever read it (the
  step-7 fallback above goes through `openrouter_pricing.json`), so the
  write was removed; the cache-clear path still deletes the file if an
  older install left one behind.

The OpenRouter provider itself is distinguished by
`AppService.crossProviderModelList` (true only for OpenRouter in
`providers.json`): when *it* is the caller, OpenRouter pricing is its
own step-1 self-report; for every other caller the same catalog is the
step-7 fallback. Together this is what makes OpenRouter's catalog
"free price tags for every provider".

## 3. models.dev

- **Endpoint:** `https://models.dev/api.json`
- **Auth:** none (public)
- **Provides:** per-vendor catalog with input/output prices,
  vision/tool capabilities, context length.
- **Fetched:** via `ApiFactory.fetchUrlAsString` (a previous
  `URL.openStream`-based version silently failed on first install,
  with no timeout and no trace). Refresh screen → "models.dev";
  Refresh All.
- **Cache:** `<filesDir>/pricing/models_dev_pricing.json` +
  `models_dev_meta.json` (capability sidecar); `models_dev_timestamp`
  in `pricing_cache`.

## 4. llm-prices

- **Endpoint:** `https://raw.githubusercontent.com/simonw/llm-prices/main/data/<vendor>.json`
- **Auth:** none (public)
- **Provides:** pricing snapshot maintained by Simon Willison. The
  per-vendor JSON files are fetched in sequence and merged. Current
  vendor list (10): `amazon`, `anthropic`, `deepseek`, `google`,
  `minimax`, `mistral`, `moonshot-ai`, `openai`, `qwen`, `xai`.
- **Cache:** `<filesDir>/pricing/llmprices_pricing.json`;
  `llmprices_timestamp` in `pricing_cache`.

## 5. Artificial Analysis

- **Endpoint:** `https://artificialanalysis.ai/api/v2/data/llms/models`
- **Auth:** API key in the `x-api-key` header (External Services →
  Artificial Analysis; free tier — sign up at
  artificialanalysis.ai/api)
- **Provides:**
  - `pricing.price_1m_input_tokens` / `price_1m_output_tokens`
  - `evaluations.artificial_analysis_intelligence_index` — quality score
  - `median_output_tokens_per_second` — speed score
- **Composite key:** `<model_creator.slug>/<slug>` (lowercased), e.g.
  `anthropic/claude-opus-4-6`. The `_v2` suffix on the keys/blobs
  invalidates the older UUID-keyed entries from the previous parser
  revision.
- **Cache:** `<filesDir>/pricing/aa_pricing_v2.json` +
  `aa_meta_v2.json` (intelligence + speed scores); `aa_timestamp_v2`
  in `pricing_cache`.

## 6. Helicone

- **Endpoint:** `https://www.helicone.ai/api/llm-costs`
- **Auth:** none (public)
- **Provides:** input/output cost per token. Match operators are
  `equals` / `startsWith` / `includes`; `findHeliconePricing` honours
  all three.
- **Cache:** `<filesDir>/pricing/helicone_pricing.json` (exact-match
  rules) + `helicone_patterns.json` (`startsWith` / `includes`
  rules); `helicone_timestamp` in `pricing_cache`.
- **Position:** last catalog tier before DEFAULT — kept only so we
  have *some* answer for a model no better source covers.

## 7. HuggingFace

Unlike the six catalog tiers, HuggingFace is **not** part of Refresh
All, **not** bundled, and **not** a pricing source — it's a lazy,
per-model card-metadata lookup.

- **Endpoint:** `https://huggingface.co/api/models/{modelId}`
  (via `ApiFactory.createHuggingFaceApi().getModelInfo`, traced under
  the `info/huggingface` category — *not* a `pricing/` category).
- **Auth:** Bearer token (External Services → HuggingFace), and it is
  **required**: when the key is blank the lookup is skipped entirely
  and a null (miss) is cached, so Model Info shows nothing for HF until
  a token is set. With a token, gated-model metadata also unlocks.
- **Provides:** model-card metadata — license, downloads, likes,
  `pipeline_tag`, `library_name`, tags, dataset references, base-model
  pointers. Surfaced on the Model Info screen → Sources card.
- **Cache:** the `huggingface_cache` SharedPreferences (single
  `entries_json` JSON-blob key, keyed `${providerId}::${modelId}`)
  with a **7-day TTL** (`HuggingFaceCache`). Negative results are
  cached so a model with no HF mirror doesn't re-hit the API on every
  screen open; concurrent load-modify-save is serialised so two
  simultaneous misses don't tear the blob.
- **Fetched:** lazily, the first time a Model Info screen opens for a
  `(provider, model)` whose entry is stale or missing. The candidate
  id is probed in three forms (the base `vendor/model`, then its
  dash→dot and dot→dash variants); the first 2xx wins, otherwise the
  miss is cached.

---

## Refresh All

The Refresh screen's top-level **Refresh all** button
(`AppViewModel.startRefreshAll`) runs two phases **in parallel** on a
full-screen progress page (`coroutineScope { catJob; wrkJob; join }`),
after first clearing every provider-default agent and emptying the
`default agents` flock:

1. the **catalog phase** (`runCatalogPhase`) fans the **six catalog
   sources out in parallel** (`async(Dispatchers.IO)` + `awaitAll`,
   because they touch disjoint disk paths). OpenRouter and Artificial
   Analysis are skipped when their key is absent. When the catalogs
   settle it recomputes the precomputed vision / web-search /
   reasoning capability sets (`recomputeAllCapabilities`) and saves
   settings;
2. the **per-provider Worker phase** (`runWorkerPhase`, each provider
   in parallel: test key → fetch model list if `ModelSource.API` →
   write the default agent and add it to the `default agents` flock).

When both phases finish, the progress screen surfaces a **"Restart
application"** banner (`onRestart`, shown once `state.isFinished`); the
user taps it to flush usage stats and `restartApp(context)` so the
in-memory singletons reload from disk. The restart is **not**
automatic.

Each catalog step renders its own status and, on failure, keeps the
*previous* cache ("kept previous N from <age>") so a transient
network error doesn't blank out a working tier. Failed providers list
with one-tap nav-to-edit. The overall run catches `Throwable` (not
just `Exception`) and falls back to the throwable's class name when
the message is empty, so an unhandled OOM still surfaces something.

A sibling **Refresh** button runs *only* the Worker phase (key test →
model list → default agent), skipping every external catalog.

## Per-provider `/models` endpoints

Independently of the seven repositories, every active provider's own
`/models` (or equivalent) endpoint is consulted at fetch time to
discover the model list. The response is parsed for the capabilities
the provider self-reports — Mistral's `capabilities` object, Cohere's
`endpoints` array, Gemini's `supportedGenerationMethods`,
OpenRouter's `architecture.input_modalities`, etc. — and stored as
`ProviderConfig.modelCapabilities` (a `Map<String, ModelCapabilities>`).
The provider's own response wins over the layered external sources
for any capability field it populates.

Failures surface inline with a 🐞 trace link rather than silently
returning an empty list, so the user can see what went wrong (404,
auth failure, parser error) without digging through logcat. The raw
JSON of the last `/models` response is preserved in
`ProviderConfig.modelListRawJson` so a future parser revision can
mine extra fields without forcing a re-fetch.

## Help & trace wiring

Each repository has a help page (the seven `info_provider_*` topics
in `ui/admin/InfoProviderHelp.kt`) deep-linked from every entry
point: the ℹ icon beside a Source button on the Model Info screen,
the per-tier card on the Refresh screen, and the Trace detail page
when a captured trace matches a known fetch category. The
trace→repository resolver is `infoProviderForTrace(url, category)`
(`ui/admin/HelpScreen.kt`), backed by the canonical 7-entry
`INFO_PROVIDERS` list; OpenRouter's spec fetch is gated on
`INFO_FETCH_CATEGORIES = {"OpenRouter model specs"}` plus a
`pricing/` category prefix. See [help.md](help.md).
