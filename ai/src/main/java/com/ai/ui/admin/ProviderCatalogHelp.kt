package com.ai.ui.admin

internal val providerCatalogHelp: Map<String, HelpContent> = mapOf(
    "providers" to HelpContent(
        title = "Help - Providers",
        cards = listOf(
            HelpCard("Overview", "List of every registered provider (51 bundled plus any user-added). The state of each row is shown by an emoji."),
            HelpCard("State emojis", "🔑 = ok (key tested + working). ❌ = error (key set but tests fail). 💤 = inactive (manually disabled). ⭕ = not-used (no key set yet)."),
            HelpCard("Sort order", "Working providers (🔑) come first, then errored (❌), then inactive (💤), then never-configured (⭕). Within each bucket, sorted by id case-insensitively. The buckets put what you actually use one tap away."),
            HelpCard("Item rows", "Provider id in white plus the configured default model in dim text (only shown when state == ok). Tap a row to open the Provider edit screen. The 🛠️ icon on the right opens the provider's external admin / signup console in the browser (dimmed when no adminUrl is configured)."),
            HelpCard("Add provider", "The green \"+ Add provider\" button at the bottom opens a single-field name dialog. The name becomes the provider id (spaces stripped). Confirming registers an empty stub via ProviderRegistry.add and jumps straight to the same Provider edit screen used for the bundled providers — fill in the base URL / default model / API format there."),
        )
    ),
    "provider_edit" to HelpContent(
        title = "Help - Provider edit",
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
    "provider_openai" to HelpContent(
        title = "Help - OpenAI",
        cards = listOf(
            HelpCard("Overview", "OpenAI Inc. — the company that turned chat-style LLMs into a mainstream product with ChatGPT (Nov 2022). Headquartered in San Francisco; partly owned by Microsoft. Their API is the de-facto reference shape every \"OpenAI-compatible\" provider mirrors."),
            HelpCard("Setup", "Sign up at platform.openai.com, create an organization, then mint an API key under Settings → API keys. Paid usage from the first call — the free trial credits ended in 2023. New accounts may need a phone-number verification before keys work. Some model families (gpt-4o-search-preview, certain o-series) are gated by tier."),
            HelpCard("Models", "Two tracks coexist: chat-completion models (`gpt-4o`, `gpt-4o-mini`, `gpt-3.5-turbo`, `o1-mini`) and the newer Responses-API models (`gpt-5`, `gpt-5-mini`, `o3*`, `o4*`, `gpt-4.1*`). Moderation (`omni-moderation-latest`, `text-moderation-latest`), TTS (`tts-1`), Whisper, and DALL-E 3 / `gpt-image-1` are available too — they're hardcoded in our catalog because `/v1/models` doesn't enumerate them. The default model in this app is `gpt-4o-mini`. `modelFilter=gpt|o1|o3|o4` trims the noisy /v1/models list."),
            HelpCard("Pricing & quirks", "OpenAI uses TWO endpoints depending on model family: Chat Completions (`v1/chat/completions`) for gpt-4o-class and o1-mini, and Responses API (`v1/responses`) for gpt-5 / o3* / o4* / gpt-4.1*. Auto-routed by `usesResponsesApi()` + `endpointRules`. Multi-text Responses-API output blocks are concatenated by the dispatcher. Pricing fed by LiteLLM + OpenRouter (alias `openai/<model>`); manual override + fixed tiers fill any gaps."),
            HelpCard("Pitfalls", "Tier-gated models 404 with no warning until your account climbs. Some keys are organization-scoped and need an `OpenAI-Organization` header — the app doesn't send one, so use a personal key or set the default org on the key. Reasoning effort (`low`/`medium`/`high`) is honored only by gpt-5 / o-series; non-reasoning models silently drop the field at dispatch."),
        )
    ),
    "provider_anthropic" to HelpContent(
        title = "Help - Anthropic",
        cards = listOf(
            HelpCard("Overview", "Anthropic, founded 2021 by ex-OpenAI researchers (Dario & Daniela Amodei). Based in San Francisco; backed by Amazon, Google. Famous for the Claude family and a constitutional-AI safety approach. Their API has its own shape (`/v1/messages`) — distinct from OpenAI's chat completions."),
            HelpCard("Setup", "Sign up at console.anthropic.com → Settings → API Keys. Pay-as-you-go from the first call; promotional credits sometimes available. Workspace-scoped keys; some accounts need an extra approval for higher rate-limit tiers. Phone verification on new accounts."),
            HelpCard("Models", "Hero family: Claude 4 Opus / Sonnet (May 2024 ids in catalog, e.g. `claude-sonnet-4-20250514`, `claude-opus-4-20250514`), plus Claude 3.7 / 3.5 Sonnet, 3.5 Haiku, and the older 3 Opus / Sonnet / Haiku. Default model in the app is `claude-sonnet-4-20250514`. 8 hardcoded fallback models cover the major ids; live list comes from `v1/models`. `modelFilter=claude` keeps the picker tidy."),
            HelpCard("Pricing & quirks", "`apiFormat = ANTHROPIC` — separate dispatch path. Auth via `x-api-key` + `anthropic-version: 2023-06-01` (NOT Bearer). Path is `v1/messages` (override on `typePaths.chat`). **`max_tokens` is required** — the dispatcher defaults to 4096 if you didn't set one and logs an override when reasoning is on. Streaming uses both `event:` and `data:` SSE framing. Web-search tool (`web_search_20250305`) injected when 🌐 is on for Claude 3.5+."),
            HelpCard("Pitfalls", "Forgetting `max_tokens` is fatal — Anthropic returns 400 immediately. Vision images are base64 `image` content blocks (not OpenAI-shape `image_url`). Some accounts can't access Opus 4 until the rate-limit tier is approved. Long context (200k) costs the same per-token but cache reads price differently — pricing tiers may understate your bill."),
        )
    ),
    "provider_google" to HelpContent(
        title = "Help - Google",
        cards = listOf(
            HelpCard("Overview", "Google's Gemini API (Generative Language API). Successor to Bard / PaLM; runs on Google Cloud infrastructure but has a separate consumer-friendly API key path under aistudio.google.com. Distinct from Vertex AI — same models, different auth + billing."),
            HelpCard("Setup", "Visit aistudio.google.com/app/apikey, sign in with a Google account, and click \"Create API key\". Generous free tier (rate-limited but free for many models including 2.0/2.5 Flash). Paid \"Pay-as-you-go\" upgrades available; some 2.5 Pro / Ultra tiers require a billing-enabled GCP project."),
            HelpCard("Models", "Gemini 2.0 Flash (default in app), 2.5 Pro / Flash / Flash-Lite, 1.5 Pro / Flash, plus older `gemini-pro` / `gemini-pro-vision`. Embedding models (`text-embedding-004`, `gemini-embedding-001`). Live list at `v1beta/models` — `modelListFormat=array` because the response is a bare top-level array. `litellmPrefix=gemini` for the LiteLLM alias."),
            HelpCard("Pricing & quirks", "`apiFormat = GOOGLE` — its own dispatch path. **Auth is `?key=<key>` query parameter** (URL-encoded), NOT a Bearer header. Model id is in the URL path: `v1beta/models/{model}:generateContent` (or `:streamGenerateContent` when streaming). Roles use `user` / `model` (not `user` / `assistant`). System prompt is a separate `systemInstruction` field. Vision images are `inlineData(mimeType, data)` parts. Web-search tool descriptor is `google_search` for 1.5+ / 2.x."),
            HelpCard("Pitfalls", "The query-param auth means a leaked URL leaks the key — `ApiTracer` redacts it at write time, but third-party log captures elsewhere may not. Path-encoded model ids show in trace URLs; the tracing interceptor extracts `trace.model` from there for non-body-encoded providers. Free-tier rate limits are aggressive; expect 429 retries during heavy fan-out."),
        )
    ),
    "provider_xai" to HelpContent(
        title = "Help - xAI (Grok)",
        cards = listOf(
            HelpCard("Overview", "xAI Corp — Elon Musk's AI company, founded 2023. Models train on real-time X (Twitter) data. The API is OpenAI-compatible at the wire level; pricing returns in \"ticks\" rather than dollars."),
            HelpCard("Setup", "Sign up at console.x.ai, top up with a credit card (no free tier on most models — there's been promotional credit programs on and off). API keys appear under Console → API keys. Some models gate behind X Premium / Premium+ subscriptions for the consumer chat; the API itself is paid usage."),
            HelpCard("Models", "Hero models: Grok-3, Grok-3-mini (default), Grok-2, Grok-2-Vision, Grok-Beta. `modelFilter=grok` trims the picker. `defaultModelSource=API` — the app fetches the live list from `v1/models`."),
            HelpCard("Pricing & quirks", "`costTicksDivisor=1e10` — the API returns `usage.cost` denominated in 10⁻¹⁰ USD ticks. The dispatcher divides to get dollars; provider-config edit refuses non-positive divisors so misconfiguration can't accidentally inflate costs. Otherwise OpenAI-compatible — Bearer auth, Chat Completions shape. `litellmPrefix=xai`, `openRouterName=x-ai`."),
            HelpCard("Pitfalls", "Cost ticks confuse third-party pricing tiers — LiteLLM and llm-prices have caught up, but a fresh model id may surface with the wrong magnitude until they update. Some Grok-3 features (e.g. live search) require additional flags this app doesn't yet plumb. Vision support is model-specific (Grok-2-Vision)."),
        )
    ),
    "provider_groq" to HelpContent(
        title = "Help - Groq",
        cards = listOf(
            HelpCard("Overview", "Groq Inc. — runs an in-house LPU (Language Processing Unit) ASIC instead of GPUs, giving notably high token-per-second throughput on open-weight Llama / Mixtral / Qwen models. Headquartered in Mountain View. Don't confuse with Elon Musk's xAI Grok — different companies."),
            HelpCard("Setup", "Sign up at console.groq.com → API Keys. Generous free tier with daily request quotas; paid upgrades for production. No phone verification required on most accounts."),
            HelpCard("Models", "Hosted catalog of Meta's Llama 3.x (`llama-3.3-70b-versatile` default), Llama 4 Scout/Maverick, Mistral / Mixtral, Qwen 3, Whisper, plus image variants. `defaultModelSource=API` so the live list at `v1/models` drives the picker. No hardcoded fallback — Groq's catalog rotates quickly as they add / retire model variants."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing per million tokens is competitive on open-weight models thanks to LPU economics. `litellmPrefix=groq` for the LiteLLM alias."),
            HelpCard("Pitfalls", "Free-tier daily limits hit fast on fan-out runs (the per-day cap counts every retry). Some models retire on short notice — your saved Agent's model id may 404 after a quarterly rotation; Refresh All flags failed providers with one-tap nav-to-edit. Whisper and embedding models are typed but not all flows route to them."),
        )
    ),
    "provider_deepseek" to HelpContent(
        title = "Help - DeepSeek",
        cards = listOf(
            HelpCard("Overview", "DeepSeek (深度求索) — Chinese AI lab spun out of High-Flyer Capital. Authors of the open-weight DeepSeek-V3 (general) and DeepSeek-R1 (reasoning) models that became prominent in 2024–2025. The proprietary API hosts polished versions of those open models."),
            HelpCard("Setup", "Sign up at platform.deepseek.com (you'll need a phone — China-region accounts use SMS, others may use email). Top up the wallet; pricing is famously low per million tokens. API keys under Console → API keys."),
            HelpCard("Models", "`deepseek-chat` (V3, default) and `deepseek-reasoner` (R1). Some accounts see additional preview tiers. `modelsPath=models` (not `v1/models`); `chat` path is `chat/completions` (not `v1/chat/completions`). `modelFilter=deepseek` trims the picker. `defaultModelSource=API`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing-cache aliases: `litellmPrefix=deepseek`, `openRouterName=deepseek`. Reasoning models surface their chain-of-thought as part of the response — the dispatcher does not currently strip it before display."),
            HelpCard("Pitfalls", "Service interruptions sometimes follow regulatory cycles in China — failovers via OpenRouter / Together / SiliconFlow keep the same model accessible elsewhere if needed. Reasoning tokens count as output and can dominate the bill on long chains. Some non-China users report intermittent 403 from carrier IPs; a VPN often fixes it."),
        )
    ),
    "provider_mistral" to HelpContent(
        title = "Help - Mistral",
        cards = listOf(
            HelpCard("Overview", "Mistral AI — Paris-based lab founded 2023 by ex-Meta / DeepMind researchers. Originally famous for releasing strong open-weight models (Mistral-7B, Mixtral-8x7B); the proprietary API hosts a mix of closed models (Large 2, Codestral) plus open Mistral-Small / Pixtral and the Open Codestral."),
            HelpCard("Setup", "console.mistral.ai → API keys. Free tier (\"La Plateforme Free\") with limited per-minute / per-month quotas; paid plans unlock higher limits + priority. EU-based service (data residency in EU)."),
            HelpCard("Models", "`mistral-small-latest` (default), `mistral-large-latest`, `mistral-medium-latest`, `codestral-latest` (code), `pixtral-12b-2409` (vision), `open-mistral-7b`, `open-mixtral-8x7b` / 8x22b. `modelFilter=mistral|open-mistral|codestral|pixtral`. `defaultModelSource=API`."),
            HelpCard("Pricing & quirks", "**`seedFieldName=random_seed`** — Mistral's only structural deviation from the OpenAI shape. The dispatcher writes `random_seed` instead of `seed` based on `service.seedFieldName`. Otherwise vanilla OpenAI-compatible. `openRouterName=mistralai`. Codestral has a separate endpoint (`codestral.mistral.ai`) configurable via Endpoints; default is the unified `api.mistral.ai`."),
            HelpCard("Pitfalls", "If you hand-edit JSON in the API Test screen and use `seed` instead of `random_seed`, Mistral silently ignores it (no error, just non-determinism). Free tier's per-minute limit kicks in fast on fan-out runs. Pixtral pricing is the same as Mistral-12B but image-to-token accounting can surprise."),
        )
    ),
    "provider_perplexity" to HelpContent(
        title = "Help - Perplexity",
        cards = listOf(
            HelpCard("Overview", "Perplexity AI — search-grounded answer engine. Their Sonar API runs LLMs that perform live web searches every turn and return answers with inline citations. Distinct from a vanilla chat API — the response carries a citation list as a first-class field."),
            HelpCard("Setup", "perplexity.ai/settings/api → generate an API key. Paid usage from the first call; per-call cost on Sonar covers both the underlying LLM and the search itself. Some endpoints require Pro subscription tier."),
            HelpCard("Models", "4 hardcoded fallback models: `sonar` (default), `sonar-pro`, `sonar-reasoning-pro`, `sonar-deep-research`. The deep-research variant runs many internal agentic searches per call. `modelFilter=sonar|llama` keeps stale Llama-passthrough ids visible only when intentionally listed."),
            HelpCard("Pricing & quirks", "`supportsCitations=true` — responses include an inline `citations` array that the dispatcher pulls into `AnalysisResponse.citations`. `supportsSearchRecency=true` — the request body accepts a `search_recency_filter` parameter (day / week / month). `chat` path is `chat/completions` (not `v1/chat/completions`). Otherwise OpenAI-compatible. `openRouterName=perplexity`."),
            HelpCard("Pitfalls", "Citations only render when the chosen model supports them — a non-Sonar passthrough returns no citations, even though the response object still has the field. Deep-research calls can run for minutes; the OkHttp read timeout (600s) covers it but some upstream proxies don't. Search-recency filter is a string; typos silently disable filtering."),
        )
    ),
    "provider_together" to HelpContent(
        title = "Help - Together AI",
        cards = listOf(
            HelpCard("Overview", "Together AI — open-weight model serving platform founded 2022. Hosts hundreds of community models plus their own fine-tunes (Llama 3.x Instruct Turbo, Mixtral, Qwen, DeepSeek, Stable Diffusion). Headquartered in Menlo Park."),
            HelpCard("Setup", "api.together.xyz/settings/api-keys → create a key. Free \"Build\" tier with a credit allowance; paid plans for production. Phone verification on new accounts to combat abuse."),
            HelpCard("Models", "`meta-llama/Llama-3.3-70B-Instruct-Turbo` (default), Llama 4 Scout / Maverick, DeepSeek-V3 / R1, Qwen 3, Mixtral 8x22B, Mistral Small/Large, Gemma, Stable Diffusion. `modelFilter=chat|instruct|llama` keeps the catalog focused on chat-capable ids. `defaultModelSource=API`."),
            HelpCard("Pricing & quirks", "**`modelListFormat=array`** — Together's `/models` endpoint returns a bare top-level array (no `{ \"data\": [...] }` wrapper). The parser handles both. Native pricing is read from the `/v1/models` payload itself and persists alongside the OpenRouter snapshot in `together_pricing.json`. `litellmPrefix=together_ai`."),
            HelpCard("Pitfalls", "Catalog rotates quickly; saved Agents may 404 after a model is retired (e.g. when Together flips a `Llama-3.1-` id to `-3.3-`). The Turbo / non-Turbo split can confuse pricing — both run, but Turbo is cheaper and faster. Image-generation models cost-track separately (per-image not per-token)."),
        )
    ),
    "provider_openrouter" to HelpContent(
        title = "Help - OpenRouter",
        cards = listOf(
            HelpCard("Overview", "OpenRouter — aggregator that exposes dozens of upstream AI providers behind one API. Useful for failover (a single key reaches Anthropic, Google, OpenAI, Meta, Mistral, etc.) and for trying obscure models without separate signups. Also doubles as a metadata source — see the `info_provider_openrouter` page for the catalog role."),
            HelpCard("Setup", "openrouter.ai/keys → mint a key. Pay-as-you-go top-up (Stripe / crypto). They take a margin on top of the upstream provider's price; budget accordingly. Each key can be scoped (allow / deny model lists, per-key spend caps)."),
            HelpCard("Models", "Default in the app: `anthropic/claude-3.5-sonnet`. Catalog spans every major provider — model ids are slash-prefixed (`anthropic/claude-3-5-sonnet`, `meta-llama/llama-3.3-70b-instruct`, `google/gemini-2.0-flash`). `defaultModelSource=API` — picker reads the live `/v1/models` list, which is large (700+)."),
            HelpCard("Pricing & quirks", "`extractApiCost=true` — OpenRouter's response includes `usage.cost`, so the dispatcher pulls the exact per-call cost rather than computing from `tokens × unitPrice`. The same hostname (`openrouter.ai`) doubles as an info-provider; the trace category disambiguates AI calls from catalog fetches."),
            HelpCard("Pitfalls", "The slash-prefixed id is mandatory — using a bare id (e.g. `gpt-4o-mini`) returns 404. Their margin means costs can be a few percent above the upstream provider's published rate. Some upstream providers occasionally rate-limit OpenRouter as a whole; a 429 may be unrelated to YOUR usage. Free models (with `:free` suffix) come and go on short notice."),
        )
    ),
    "provider_mergegateway" to HelpContent(
        title = "Help - Merge Gateway",
        cards = listOf(
            HelpCard("Overview", "Merge Gateway (merge.dev) — a control-plane LLM gateway that routes one API across OpenAI, Anthropic, Google, AWS Bedrock and more, with smart routing, fallback, spend controls and observability. Like OpenRouter, a single key reaches every major model behind one endpoint."),
            HelpCard("Setup", "gateway.merge.dev/settings/api-keys → mint a key (the 🛠️ admin icon opens it). One key replaces every per-provider signup; spend and budgets are managed in the Merge dashboard, not here."),
            HelpCard("Endpoints", "Base URL `api-gateway.merge.dev/`. Both chat and the model list use the OpenAI-compatible shim under `v1/openai/`: chat → `v1/openai/chat/completions`, models → `v1/openai/models` (`modelsPath`), which returns the standard OpenAI object shape `{\"data\":[{\"id\":…}]}`. Auth is `Authorization: Bearer`. NB: the *native* `v1/models` endpoint is a different, richer Merge schema (entries keyed by `model`, object-valued `aliases`, nested `vendors`) that the app's OpenAI parser can't read — so this provider points at `v1/openai/models`, not the default `v1/models`."),
            HelpCard("Models", "Default in the app: `anthropic/claude-opus-4-8`. Catalog spans every routed provider — model ids are slash-prefixed. Note Anthropic ids use **dashes** for the version (`anthropic/claude-opus-4-8`, `anthropic/claude-sonnet-4-6`), while Google uses dots (`google/gemini-2.5-pro`, `google/gemini-3.5-flash`) and DeepSeek too (`deepseek/deepseek-v3.2`). `defaultModelSource=API` reads the live `v1/openai/models` list (200+ ids); `mergeHardcodedModels=true` keeps a small bundled Claude/Gemini/DeepSeek fallback visible before the first fetch. (The account's catalog may expose no `openai/*` ids — OpenAI models depend on Merge enabling them.)"),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. No `litellmPrefix` / `openRouterName` set — but the slash-prefixed ids match the OpenRouter cross-provider catalog directly, so pricing usually resolves through that fallback (else DEFAULT). Gateway margin/routing can shift the effective upstream provider per call."),
            HelpCard("Pitfalls", "The slash-prefixed id is mandatory, exactly like OpenRouter — a bare id may 404. Which physical provider serves a request depends on Merge's routing/fallback policy, so latency and the exact upstream can vary call to call. Catalog ids change as Merge adds/retires models; Refresh the model list if the default looks stale."),
        )
    ),
    "provider_vercelaigateway" to HelpContent(
        title = "Help - Vercel AI Gateway",
        cards = listOf(
            HelpCard("Overview", "Vercel AI Gateway — an OpenRouter-style cross-provider gateway. One key reaches ~300 models across OpenAI, Anthropic, Google, xAI, DeepSeek, etc. Vercel advertises **zero markup** (provider list prices) plus a small monthly free credit."),
            HelpCard("Setup", "Create a key in the Vercel dashboard → AI Gateway → API keys (the 🛠️ admin icon opens it). Works with any OpenAI client by just changing the base URL; auth is `Authorization: Bearer`."),
            HelpCard("Endpoints", "Base `ai-gateway.vercel.sh/`, OpenAI-compatible: chat `v1/chat/completions`, model list `v1/models` (standard `{\"data\":[{\"id\":…}]}`)."),
            HelpCard("Models", "Default `openai/gpt-4.1-nano` (cheap OpenAI). Ids are `creator/model` slash-prefixed; note Anthropic ids here use **dots** (`anthropic/claude-opus-4.8`, `anthropic/claude-sonnet-4.6`), unlike the dash form some other gateways use. `defaultModelSource=API` drives the live picker; `mergeHardcodedModels=true` keeps a small Claude/GPT/Gemini/DeepSeek fallback."),
            HelpCard("Pitfalls", "Slash-prefixed id mandatory. No `openRouterName`/`litellmPrefix` — pricing falls through to the OpenRouter cross-provider catalog (the ids match) or DEFAULT. Refresh the list if a default id looks stale."),
        )
    ),
    "provider_glama" to HelpContent(
        title = "Help - Glama",
        cards = listOf(
            HelpCard("Overview", "Glama Gateway — an OpenAI-compatible aggregator (glama.ai) with consolidated billing, load-balancing and fallbacks across ~80 models behind one key."),
            HelpCard("Setup", "Mint a key under glama.ai → Settings → Gateway (the 🛠️ admin icon opens it). Standard `Authorization: Bearer`; drop-in OpenAI SDK base-URL swap."),
            HelpCard("Endpoints", "Base `gateway.glama.ai/`, chat `v1/chat/completions`, models `v1/models` (OpenAI object shape)."),
            HelpCard("Models", "Default `deepseek/deepseek-chat-v3` (cheap DeepSeek). Slash-prefixed ids; Anthropic uses **dashes** (`anthropic/claude-opus-4-6`, `anthropic/claude-sonnet-4-6`) and OpenAI ids carry a date suffix (`openai/gpt-5.4-2026-03-05`). `defaultModelSource=API`; `mergeHardcodedModels=true` Claude/GPT/DeepSeek fallback."),
            HelpCard("Pitfalls", "Catalog is smaller than OpenRouter/Merge and Google models may be absent. Pricing falls through to the OpenRouter cross-provider catalog or DEFAULT."),
        )
    ),
    "provider_requesty" to HelpContent(
        title = "Help - Requesty",
        cards = listOf(
            HelpCard("Overview", "Requesty — an OpenAI-compatible LLM router (requesty.ai) over 500+ models with intelligent routing, fallbacks, caching, spend controls and observability behind one key."),
            HelpCard("Setup", "Create a key in the Requesty app dashboard (the 🛠️ admin icon opens app.requesty.ai). Point any OpenAI client at the base URL; auth is `Authorization: Bearer`."),
            HelpCard("Endpoints", "Base `router.requesty.ai/`, chat `v1/chat/completions`, models `v1/models`."),
            HelpCard("Models", "Default `google/gemini-2.5-flash-lite` (cheap Google). Slash-prefixed ids, Anthropic in **dash** form. Some ids carry routing suffixes like `:priority` / `:flex`. `defaultModelSource=API`; `mergeHardcodedModels=true` Claude/GPT/Gemini/DeepSeek fallback."),
            HelpCard("Pitfalls", "Slash-prefixed id mandatory. The physical upstream depends on Requesty's routing policy. Pricing falls through to the OpenRouter cross-provider catalog or DEFAULT."),
        )
    ),
    "provider_aimlapi" to HelpContent(
        title = "Help - AI-ML-API",
        cards = listOf(
            HelpCard("Overview", "AI-ML-API — the AI/ML API service (aimlapi.com), an OpenAI-compatible aggregator exposing 600+ chat/image/audio models across every major provider behind one key. (Named AI-ML-API, not AI/ML API, because a provider id can't contain a slash.)"),
            HelpCard("Setup", "Mint a key at aimlapi.com → app → keys (the 🛠️ admin icon opens it). Drop-in OpenAI base-URL swap; `Authorization: Bearer`."),
            HelpCard("Endpoints", "Base `api.aimlapi.com/`, chat `v1/chat/completions`, models `v1/models`."),
            HelpCard("Models", "Default `mistralai/mistral-nemo` (cheap Mistral). Slash-prefixed ids — the catalog carries BOTH dashed and dotted Anthropic aliases plus many dated snapshots, so the live `v1/models` list is the source of truth (`defaultModelSource=API`). `mergeHardcodedModels=true` Claude/GPT/Gemini/DeepSeek fallback."),
            HelpCard("Pitfalls", "Large, noisy catalog with duplicate/aliased ids — Refresh and search to find the exact id. Pricing falls through to the OpenRouter cross-provider catalog or DEFAULT."),
        )
    ),
    "provider_atlascloud" to HelpContent(
        title = "Help - Atlas Cloud",
        cards = listOf(
            HelpCard("Overview", "Atlas Cloud (atlascloud.ai) — an OpenAI-compatible aggregator serving ~130 open-weight models (DeepSeek, Qwen, Llama, Gemini, GPT, and Z.AI's GLM incl. `zai-org/glm-5.2`) behind one key."),
            HelpCard("Setup", "atlascloud.ai/console/api-keys → mint a free key (the 🛠️ admin icon opens it). Drop-in OpenAI base-URL swap; `Authorization: Bearer`."),
            HelpCard("Endpoints", "Base `api.atlascloud.ai/`, chat `v1/chat/completions`, models `v1/models`. NB the model list wraps the OpenAI shape as `{\"code\":200,\"msg\":\"succeed\",\"data\":[{\"id\":…}]}` — the parser reads `data[]` so it still loads; each entry carries `pricing`, `context_length`, modalities."),
            HelpCard("Models", "Default `deepseek-ai/DeepSeek-V3-0324` (cheap DeepSeek). Slash-prefixed ids; Z.AI casing is inconsistent (`zai-org/GLM-4.6` vs `zai-org/glm-5.2`) so use the live list (`defaultModelSource=API`). `mergeHardcodedModels=true` DeepSeek/GLM/Qwen/Gemini/GPT fallback."),
            HelpCard("Pitfalls", "Aggregator — the exact upstream that serves a request can vary. Some headline ids from other catalogs aren't present (e.g. `meta-llama/Meta-Llama-3.1-8B-Instruct` 404s); search the fetched list for the right id. No `openRouterName`/`litellmPrefix` — pricing falls through to the OpenRouter cross-provider catalog or DEFAULT."),
        )
    ),
    "provider_parasail" to HelpContent(
        title = "Help - Parasail",
        cards = listOf(
            HelpCard("Overview", "Parasail (parasail.io) — serverless open-weight inference at aggressive prices. ~70 models (Llama, DeepSeek V4, Qwen3, Kimi K2.6/2.7, gpt-oss, and Z.AI's GLM incl. self-hosted `zai-org/GLM-5.2`), free / dedicated tiers."),
            HelpCard("Setup", "saas.parasail.io/keys → mint a key (the 🛠️ admin icon opens it). Drop-in OpenAI base-URL swap; `Authorization: Bearer`."),
            HelpCard("Endpoints", "Base `api.parasail.io/`, chat `v1/chat/completions`, models `v1/models` (standard OpenAI object shape)."),
            HelpCard("Models", "Default `meta-llama/Llama-3.3-70B-Instruct`. Ids come in two flavours: HF-style (`zai-org/GLM-5.2`, `deepseek-ai/DeepSeek-V4-Pro`) and Parasail aliases (`parasail-glm-52`, `parasail-llama-33-70b-fp8`) — both work; the `*-FP8` variants are quantized. `defaultModelSource=API`; `mergeHardcodedModels=true` fallback (Llama / GLM / DeepSeek / Qwen / Kimi / gpt-oss)."),
            HelpCard("Pricing & quirks", "Open-weight serverless host — runs the weights on its own GPUs (a real self-hoster of GLM-5.2, not a router). No `litellmPrefix`/`openRouterName` — pricing falls through to the OpenRouter cross-provider catalog or DEFAULT. Cold-start latency on rarely-used models."),
        )
    ),
    "provider_baseten" to HelpContent(
        title = "Help - Baseten",
        cards = listOf(
            HelpCard("Overview", "Baseten (baseten.co) — production inference platform. Its **Model APIs** give one key access to a small curated catalog (~11) of frontier open-weight models — gpt-oss, GLM (self-hosted `zai-org/GLM-5.2`), Kimi K2.5/2.6/2.7, DeepSeek V4, NVIDIA Nemotron — no model deployment needed."),
            HelpCard("Setup", "app.baseten.co/settings/api_keys → mint a key (the 🛠️ admin icon opens it). Drop-in OpenAI base-URL swap; `Authorization: Bearer`."),
            HelpCard("Endpoints", "Base `inference.baseten.co/`, chat `v1/chat/completions`, models `v1/models` (standard OpenAI object shape, with `pricing`/`context_length` metadata). NB the Model-APIs host is `inference.baseten.co`, not `app`/`api.baseten.co` (which serve the console / your own dedicated deployments)."),
            HelpCard("Models", "Default `openai/gpt-oss-120b`. Slash-prefixed HF-style ids (`zai-org/GLM-5.2`, `moonshotai/Kimi-K2.6`, `deepseek-ai/DeepSeek-V4-Pro`). `defaultModelSource=API` (the curated list is small); `mergeHardcodedModels=true` fallback. The catalog is the Model-APIs library, not Baseten's full deploy-your-own model universe."),
            HelpCard("Pricing & quirks", "Model APIs bill per-million-tokens. No `litellmPrefix`/`openRouterName` — pricing falls through to the OpenRouter cross-provider catalog or DEFAULT. Separately, Baseten lets you deploy dedicated models on its GPUs (per-model `model-{id}.api.baseten.co` URLs) — those aren't reachable through this single Model-APIs provider entry."),
        )
    ),
    "provider_gmicloud" to HelpContent(
        title = "Help - GMI-Cloud",
        cards = listOf(
            HelpCard("Overview", "GMI Cloud (gmicloud.ai) — serverless GPU inference, OpenAI-compatible, ~66 open-weight models (DeepSeek V3/V4, Qwen 3.5/3.7, Kimi K2.x, gpt-oss/gpt-5.x, and Z.AI's GLM incl. self-hosted `zai-org/GLM-5.2-FP8`). Free endpoint → serverless → dedicated H100/H200 on one consistent API."),
            HelpCard("Setup", "console.gmicloud.ai → create an Inference-Engine API key (the 🛠️ admin icon opens the console). The key is a JWT; send it as `Authorization: Bearer`. **Add credit** — an unfunded account lists models fine but generation returns HTTP 402 \"Insufficient balance\"."),
            HelpCard("Endpoints", "Base `api.gmi-serving.com/` (note: the *serving* host, not `gmicloud.ai`), chat `v1/chat/completions`, models `v1/models` (standard OpenAI object shape with `pricing`/`context_length`)."),
            HelpCard("Models", "Default `deepseek-ai/DeepSeek-V3.2`. Slash-prefixed HF-style ids; GLM ships as `-FP8` quantized (`zai-org/GLM-5.2-FP8`, no plain `GLM-5.2`). `defaultModelSource=API`; `mergeHardcodedModels=true` DeepSeek/GLM/Qwen/Kimi/gpt-oss fallback."),
            HelpCard("Pitfalls", "Generation needs a funded account (402 otherwise). No `litellmPrefix`/`openRouterName` — pricing falls through to the OpenRouter cross-provider catalog or DEFAULT. Id is `GMI-Cloud` (dash, not slash — a slash would break the Model Info route)."),
        )
    ),
    "provider_siliconflow" to HelpContent(
        title = "Help - SiliconFlow",
        cards = listOf(
            HelpCard("Overview", "SiliconFlow (硅基流动) — China-based serverless inference for popular open-weight models. Hosts Qwen, DeepSeek, GLM, Llama, Stable Diffusion, BGE embeddings. Often cheaper per-token than the model authors' own hosted services."),
            HelpCard("Setup", "cloud.siliconflow.com/account/ak → mint a key. Phone verification typically required (China-region accounts use SMS). Free credit allowance on new accounts; pay-as-you-go after."),
            HelpCard("Models", "9 hardcoded fallback models: `Qwen/Qwen2.5-7B-Instruct` (default), `Qwen/Qwen2.5-14B-Instruct`, QwQ-32B, DeepSeek-V3, plus image / embedding ids. Live list at `/v1/models` — `defaultModelSource=API` so the picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. No native pricing tier — the layered lookup falls through to LiteLLM / models.dev / Helicone for whichever model id matches. No `litellmPrefix` set, so LiteLLM only matches when the model author's bare id is in their catalog."),
            HelpCard("Pitfalls", "China-region routing means non-CN users sometimes see latency spikes or carrier-IP blocks (a VPN typically fixes it). Catalog can rotate fast; the hardcoded fallback list catches some retired ids but the live `/v1/models` is authoritative. Some endpoints require model-specific request shapes (image gen) that this app doesn't yet route to."),
        )
    ),
    "provider_zai" to HelpContent(
        title = "Help - Z.AI (Zhipu)",
        cards = listOf(
            HelpCard("Overview", "Zhipu AI (智谱清言) — Chinese AI company spun out of Tsinghua University, founded 2019. Authors of the GLM (General Language Model) family, including GLM-4, GLM-4.5, GLM-4.7, plus the CodeGeeX coding family and CharGLM persona models. The Z.AI API is the international rebrand of the BigModel platform."),
            HelpCard("Setup", "open.bigmodel.cn/usercenter/apikeys (the underlying console) → mint a key. Free credits on signup; phone / WeChat verification typical. The Z.AI rebrand provides a friendlier latency profile for non-CN users."),
            HelpCard("Models", "7 hardcoded fallback models: `glm-4.7-flash` (default), `glm-4.7`, `glm-4.5-flash`, `glm-4.5`, `glm-4.5-air`, `codegeex-4`, `charglm-3`. `modelFilter=glm|codegeex|charglm`. Live list at `models` (NOT `v1/models`); `defaultModelSource=API`."),
            HelpCard("Pricing & quirks", "`chat` path is `chat/completions` (not `v1/chat/completions`); `modelsPath=models`. Base URL has the `api/paas/v4/` segment prefixing the standard OpenAI shape. `openRouterName=z-ai`. OpenAI-compatible at the wire level."),
            HelpCard("Pitfalls", "The flagship `glm-4.7` is gated to higher-tier accounts on first signup; smaller flash variants are unrestricted. CodeGeeX has its own API endpoint variant (`api/coding/paas/v4/`) configurable via Endpoints. CharGLM persona models accept a `system_role` extension some other providers don't."),
        )
    ),
    "provider_moonshot" to HelpContent(
        title = "Help - Moonshot (Kimi)",
        cards = listOf(
            HelpCard("Overview", "Moonshot AI (月之暗面) — Chinese AI company founded 2023, behind the Kimi assistant. Famous for very long-context models (up to 200k+ tokens) and strong Chinese-language performance. Two API surfaces: the China-region platform.moonshot.cn and the international platform.moonshot.ai."),
            HelpCard("Setup", "platform.moonshot.ai/console/api-keys → mint a key (international); platform.moonshot.cn for China-region accounts. Pay-as-you-go; new accounts get a free credit allowance."),
            HelpCard("Models", "4 hardcoded fallback models: `kimi-latest` (default), `moonshot-v1-8k`, `moonshot-v1-32k`, `moonshot-v1-128k`. Live list at `/v1/models`. `openRouterName=moonshot`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level — no special quirks. Pricing per-million on long-context variants is reasonable (the headline 128k tier is the same per-token as 32k). Auto-routed via OpenRouter alias when LiteLLM / models.dev don't have an entry."),
            HelpCard("Pitfalls", "The `kimi-latest` alias rotates underneath you; pricing tiers may lag a model swap. China-region carriers sometimes route oddly to platform.moonshot.ai; if you see consistent timeouts try the platform.moonshot.cn base URL via Provider edit → Definition · API."),
        )
    ),
    "provider_cohere" to HelpContent(
        title = "Help - Cohere",
        cards = listOf(
            HelpCard("Overview", "Cohere — Toronto-based foundation model lab, founded 2019 by ex-Google Brain researchers including Aidan Gomez (one of the Transformer paper's authors). Enterprise-focused with strong tool-use tuning; the Command-R / Command-A family is their flagship chat line, plus an industry-leading Rerank API."),
            HelpCard("Setup", "dashboard.cohere.com → API keys. Free \"Trial\" key with limited per-minute / per-month quotas; production keys are paid. No phone verification required on most accounts."),
            HelpCard("Models", "4 hardcoded fallback models: `command-a-03-2025` (default), `command-r-plus-08-2024`, `command-r-08-2024`, `command-r7b-12-2024`. Plus rerank models (`rerank-v3.5`, `rerank-multilingual-v3`) and the Embed family (`embed-english-v3`, `embed-multilingual-v3`). Default model source is the bundled fallback list."),
            HelpCard("Pricing & quirks", "OpenAI-compatible chat at `compatibility/` base URL. **Native rerank endpoint wired** — `callCohereRerank` in `SecondaryResult.kt` routes Cohere-typed Rerank prompts to `/v2/rerank` and converts the response into the same `[{id, rank, score, reason}, ...]` shape the chat-model rerank flow uses. `openRouterName=cohere`."),
            HelpCard("Pitfalls", "Trial keys have a per-minute throttle that's tight on fan-out runs. The chat compatibility layer is newer than Cohere's native API — some niche params (`citation_quality`, structured `documents` arrays) only work via the native endpoints, which this app doesn't route to. Embed and Rerank are billed in \"search units\" — `RerankApiResult.billedSearchUnits` surfaces the count for cost tracking."),
        )
    ),
    "provider_fireworks" to HelpContent(
        title = "Help - Fireworks AI",
        cards = listOf(
            HelpCard("Overview", "Fireworks AI — open-weight model serving founded by ex-Meta PyTorch engineers, 2022. Hosts Llama, Mixtral, DeepSeek, Qwen, plus their own fine-tunes. Strong performance + competitive pricing on open-weight chat models."),
            HelpCard("Setup", "app.fireworks.ai → API keys. Free credits on signup; pay-as-you-go after. Phone verification on new accounts."),
            HelpCard("Models", "4 hardcoded fallback models: `llama-v3p3-70b-instruct` (default), `deepseek-r1-0528`, `qwen3-235b-a22b`, `llama-v3p1-8b-instruct`. Model ids are prefixed `accounts/fireworks/models/<id>` — the full path is the model id sent in the request body. Live list at `/v1/models`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible chat at `inference/` base URL. The model-id naming convention (`accounts/<owner>/models/<id>`) lets users-with-an-account host their own fine-tunes alongside the official catalog. No special dispatch quirks beyond the long ids."),
            HelpCard("Pitfalls", "The `accounts/fireworks/models/` prefix surprises new users — copy the full id from the catalog. Catalog rotates quickly; expect ids to drift between Llama 3.1 → 3.2 → 3.3 → 4. Some hosted models are FP8 / FP4 quantized — pricing is per-token but quality may differ from the upstream."),
        )
    ),
    "provider_cerebras" to HelpContent(
        title = "Help - Cerebras",
        cards = listOf(
            HelpCard("Overview", "Cerebras Systems — wafer-scale AI hardware company (their CS-3 chip is a single 46,225 mm² wafer). Their inference cloud delivers very high tokens-per-second (often >1000 tok/s on Llama 3.1-70B) thanks to keeping entire models in on-wafer SRAM."),
            HelpCard("Setup", "cloud.cerebras.ai → API keys. Generous free tier with daily token limits; paid plans for production. Phone verification typical."),
            HelpCard("Models", "5 hardcoded fallback models: `llama-3.3-70b` (default), `llama-4-scout-17b-16e-instruct`, `llama3.1-8b`, plus Qwen and DeepSeek variants. Default model source is the bundled fallback; `/v1/models` is the live source. Catalog is small by design — Cerebras only hosts a curated set that fits well on their wafer."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing per-token is competitive given the speed; the headline number is tokens-per-second, not just dollars. No special dispatch quirks."),
            HelpCard("Pitfalls", "Free-tier daily quota hits fast on fan-out runs. The very high throughput sometimes overwhelms downstream parsers — if your Reports flow shows truncated streaming, the SSE buffering in `ApiStreaming` should be fine but third-party log capture may not be. Some preview models retire on short notice."),
        )
    ),
    "provider_sambanova" to HelpContent(
        title = "Help - SambaNova",
        cards = listOf(
            HelpCard("Overview", "SambaNova Systems — RDU (Reconfigurable Dataflow Unit) AI hardware company. Like Cerebras, they sell inference speed on open-weight models — Llama, DeepSeek, Qwen — running on their own custom silicon. Headquartered in Palo Alto."),
            HelpCard("Setup", "cloud.sambanova.ai → API keys. Free tier with daily request quotas; paid plans for production."),
            HelpCard("Models", "5 hardcoded fallback models: `Meta-Llama-3.3-70B-Instruct` (default), `DeepSeek-R1`, `DeepSeek-V3-0324`, plus Qwen variants. Note the capitalised ids — SambaNova mirrors the upstream model author's casing exactly. Live list at `/v1/models`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. Pricing competitive; throughput high. No special dispatch quirks beyond the case-sensitive ids."),
            HelpCard("Pitfalls", "Case-sensitive model ids — `meta-llama-3.3-70b-instruct` (lowercase) returns 404; you need the capitalised form. Catalog smaller than Together / Fireworks. Some preview models gate behind enterprise tier."),
        )
    ),
    "provider_minimax" to HelpContent(
        title = "Help - MiniMax",
        cards = listOf(
            HelpCard("Overview", "MiniMax (稀宇科技) — Chinese AI company founded 2021, behind the abab and MiniMax-M model families. Their Hailuo AI consumer product runs on these models. Known for multimodal generation (text, audio, video) plus Chinese-language strength."),
            HelpCard("Setup", "platform.minimax.io → API keys (international); platform.minimaxi.com for the China-region service. Free credits on signup."),
            HelpCard("Models", "4 hardcoded fallback models: `MiniMax-M2.1` (default), `MiniMax-M2`, `MiniMax-M1`, `MiniMax-Text-01`. Live list at `/v1/models` (no `defaultModelSource=API` set, so manual list drives the picker)."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. `openRouterName=minimax` so the OpenRouter fallback can pick up cross-provider pricing. The international vs China platform split affects latency for non-CN users."),
            HelpCard("Pitfalls", "Audio and video generation models exist in the catalog but route via different endpoints this app doesn't yet plumb — only chat models work end-to-end. Capital-M model ids are case-sensitive."),
        )
    ),
    "provider_nvidia" to HelpContent(
        title = "Help - NVIDIA NIM",
        cards = listOf(
            HelpCard("Overview", "NVIDIA Inference Microservices (NIM) — NVIDIA's API platform for hosted open-weight models. Hosts NVIDIA's own Nemotron family plus a 3rd-party catalog (Llama, Mistral, DeepSeek, Qwen). Free tier of 1000 credits / month for personal projects."),
            HelpCard("Setup", "build.nvidia.com → sign in with NVIDIA Developer account → mint API key. The free tier for personal accounts gives a generous credit allowance; enterprise accounts get paid scaling."),
            HelpCard("Models", "Default: `meta/llama-3.3-70b-instruct`. Catalog is large — every NVIDIA-hosted model carries a slash-prefixed id (`nvidia/<model>`, `meta/<model>`, `mistralai/<model>`). `defaultModelSource=API` so picker reads the live list. NB: many listed NIM models 404 with \"not found for account\" until you enable them on build.nvidia.com — the live list isn't a guarantee a given id will run."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. The `integrate.api.nvidia.com/` base URL routes to the NIM platform; the actual model serving runs on NVIDIA's GPU infrastructure. No `openRouterName` / `litellmPrefix` — pricing tiers fall through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "The slash-prefixed id (`nvidia/<model>`) is mandatory. Some preview models in the catalog require an enterprise license. The free credit allowance resets monthly; heavy fan-out can deplete it."),
        )
    ),
    "provider_replicate" to HelpContent(
        title = "Help - Replicate",
        cards = listOf(
            HelpCard("Overview", "Replicate — model marketplace founded 2019 by Ben Firshman (ex-Docker). Hosts thousands of open-weight models (chat, image, audio, video) with per-second billing. Strong for image generation (Flux, SDXL, Imagen-class community models); chat support is bolted on."),
            HelpCard("Setup", "replicate.com/account/api-tokens → mint a token. Free credits on signup; pay-per-second after. Note the GitHub-style flow — you sign in with GitHub on most accounts."),
            HelpCard("Models", "2 hardcoded chat models: `meta/meta-llama-3-8b-instruct` (default) and `meta/meta-llama-3-70b-instruct`. Replicate's `/v1/models` is its entire public catalog (mostly image / audio), so there's no live chat-model list — type any chat model's `owner/name` id into the picker. `apiFormat=REPLICATE`."),
            HelpCard("Pricing & quirks", "Replicate is **not** OpenAI-compatible — it has no `/v1/chat/completions`. The app talks to its native **predictions API** (`apiFormat=REPLICATE`): `POST v1/models/{owner}/{name}/predictions` with the `Prefer: wait` header, which blocks until the model finishes and returns the output (a list of token strings the app joins). Token counts come from the prediction's `metrics`. Per-second billing; slash-prefixed ids."),
            HelpCard("Pitfalls", "Only text chat models work — image / audio models (most of the catalog) and any model that needs the async poll beyond the 60s `Prefer: wait` window won't return inline. Cold-start latency on rarely-used models can be 30+ seconds. A model listed elsewhere may be retired here (e.g. `mistral-7b-instruct-v0.2`); if a test fails with 404, the id is gone — pick a current one."),
        )
    ),
    "provider_huggingface" to HelpContent(
        title = "Help - HuggingFace Inference",
        cards = listOf(
            HelpCard("Overview", "Hugging Face Inference API — serverless inference for thousands of open-weight models hosted on the HF Hub. Different from HuggingFace's role as an info-provider for model-card metadata; same API key works for both. The Pro subscription unlocks higher rate limits."),
            HelpCard("Setup", "huggingface.co/settings/tokens → mint a Read token. Anonymous calls are heavily rate-limited; the Free tier with token unlocks meaningful usage; HF Pro lifts limits further. Some gated models require accepting the model card terms in a browser first."),
            HelpCard("Models", "4 hardcoded fallback models: `meta-llama/Llama-3.1-70B-Instruct` (default), `meta-llama/Llama-3.1-8B-Instruct`, `Mistral-7B-Instruct-v0.3`, plus a Falcon. Slash-prefixed ids match the HF Hub repo path. The Inference API supports far more models than the picker shows; copy a repo id from huggingface.co/models to use it."),
            HelpCard("Pricing & quirks", "OpenAI-compatible chat at `/v1/chat/completions`. The base URL is `api-inference.huggingface.co/`. Cold-start latency on rarely-used models is significant (HF spins down idle endpoints). Note: this is the HF *provider* (chat); the HF *info-provider* (model-card metadata) uses the same key but reads from `huggingface.co/api/models/{id}`."),
            HelpCard("Pitfalls", "Same `huggingFaceApiKey` is used by both this provider AND the model-card info-provider — they're conceptually distinct but share the key. Gated models (Llama 3.1 70B, some Mistral fine-tunes) return 401 / 403 until you accept terms on the HF Hub. HF Pro subscription is separate from API credit cost."),
        )
    ),
    "provider_deepinfra" to HelpContent(
        title = "Help - DeepInfra",
        cards = listOf(
            HelpCard("Overview", "DeepInfra — open-weight model serving founded 2022. Hosts Llama, Mistral, DeepSeek, Qwen, Mixtral, plus embedding and image models. Headquartered in Palo Alto; competitive per-token pricing on the popular open-weight chat models."),
            HelpCard("Setup", "deepinfra.com/dash/api_keys → mint a key. Free credit allowance on signup; pay-as-you-go after."),
            HelpCard("Models", "Default: `meta-llama/Meta-Llama-3.1-70B-Instruct`. `chat` path is `chat/completions` (not `v1/chat/completions`); `modelsPath=models` (not `v1/models`). `defaultModelSource=API` so the picker auto-refreshes from the live list."),
            HelpCard("Pricing & quirks", "Base URL is `api.deepinfra.com/v1/openai/` — the `/v1/openai/` segment is the OpenAI-compatible gateway. Slash-prefixed ids (`<owner>/<model>`). No `litellmPrefix` / `openRouterName` set — pricing falls through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "Path quirks (`chat/completions`, `models` — no `v1/` prefix on those parts) trip hand-edited requests. The `/v1/openai/` URL segment is the OpenAI gateway; native DeepInfra requests use a different path the app doesn't route to."),
        )
    ),
    "provider_hyperbolic" to HelpContent(
        title = "Help - Hyperbolic",
        cards = listOf(
            HelpCard("Overview", "Hyperbolic Labs — open-weight model serving plus image / audio APIs, founded 2023. Hosts DeepSeek, Llama, Qwen, Mistral, plus vision and TTS models. Compute is sourced from a mix of in-house and partner GPU capacity."),
            HelpCard("Setup", "app.hyperbolic.xyz/settings → API keys. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "Default: `deepseek-ai/DeepSeek-V3-0324`. `defaultModelSource=API` so picker auto-refreshes. The serverless LLM list is small (~5: DeepSeek V3-0324 / R1 / R1-0528, Llama-3.3-70B, Qwen3-Coder-480B)."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Slash-prefixed ids. No `litellmPrefix` / `openRouterName` — pricing falls through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "Image / audio models live in the catalog but don't fit the chat dispatch path — only chat models work end-to-end here. Big models cold-start slowly, and the inference endpoint has been seen to return HTTP 500 (\"Internal Error, please report to us\") or hang even on a funded account while /v1/models and billing respond fine — a provider-side issue, retry or contact Hyperbolic."),
        )
    ),
    "provider_novitaai" to HelpContent(
        title = "Help - Novita.ai",
        cards = listOf(
            HelpCard("Overview", "Novita.ai — open-weight serverless inference, founded 2023. Hosts Llama, Mistral, Qwen, DeepSeek and Z.AI's GLM (incl. self-hosted GLM-5.2 as `zai-org/glm-5.2`); competitive per-token pricing on common open-weight models. Headquartered in Singapore."),
            HelpCard("Setup", "novita.ai/settings/key-management → mint a key, then add credit (pay-as-you-go) — generation 403s with NOT_ENOUGH_BALANCE on an unfunded account even though the key lists models fine."),
            HelpCard("Models", "Default: `zai-org/glm-5.2` (Z.AI's open-weight flagship, run on Novita's own GPUs). `chat` path is `chat/completions`; `modelsPath=models`. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "Base URL is `api.novita.ai/v3/openai/` — the `/v3/openai/` segment is the OpenAI-compatible gateway. Slash-prefixed ids. No `litellmPrefix` / `openRouterName` — pricing falls through."),
            HelpCard("Pitfalls", "Path quirks similar to DeepInfra — `chat/completions` / `models` (no `v1/` prefix on those parts). The `/v3/openai/` segment is stable but worth noting if you hand-edit URLs."),
        )
    ),
    "provider_nebiusaistudio" to HelpContent(
        title = "Help - Nebius AI Studio",
        cards = listOf(
            HelpCard("Overview", "Nebius AI Studio — inference platform from Nebius (the AI-cloud arm of the former Yandex international assets). Headquartered in Amsterdam; runs a large GPU fleet across European data centers. Hosts Llama, DeepSeek, Qwen, Mistral, and Z.AI's open-weight GLM (incl. self-hosted GLM-5.2 / 5.1 as `zai-org/GLM-5.2`)."),
            HelpCard("Setup", "studio.nebius.com/settings/api-keys → mint a key. Free credits on signup; pay-as-you-go after. Strong European data residency story for users with that preference."),
            HelpCard("Models", "Default: `zai-org/GLM-5.2` (Z.AI's open-weight flagship, run on Nebius's own GPUs — a reasoning model, so give it room in `max_tokens` or it spends the budget thinking and returns empty). Slash-prefixed ids matching HF Hub paths. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. Base URL is `api.studio.nebius.com/`. Pricing competitive on the popular open-weight chat models. No `litellmPrefix` / `openRouterName` — pricing falls through."),
            HelpCard("Pitfalls", "Newer entrant — model catalog occasionally rotates as they add capacity for new ids. The Yandex history is irrelevant to data flow today (Nebius is a separate Netherlands-incorporated entity), but worth noting if procurement asks."),
        )
    ),
    "provider_chutes" to HelpContent(
        title = "Help - Chutes",
        cards = listOf(
            HelpCard("Overview", "Chutes — open-weight inference platform built on top of the Bittensor decentralized AI network. Compute is sourced from Bittensor miners (subnet 64 / Chutes); pricing reflects the decentralized economics. Founded 2024."),
            HelpCard("Setup", "chutes.ai/app/api → mint a key. Free credit allowance; pay-as-you-go after via TAO (Bittensor's native token) or fiat."),
            HelpCard("Models", "Default: `deepseek-ai/DeepSeek-V3`. Slash-prefixed ids. Catalog: DeepSeek, Llama, Qwen, Mistral, plus Bittensor-native fine-tunes. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. Base URL is `llm.chutes.ai/`. Decentralized compute can give variance in latency / quality across the same model id depending on which miner serves the request — Chutes routes to a chosen one but cold-starts vary."),
            HelpCard("Pitfalls", "Decentralized compute means quality / availability can vary turn-to-turn — for production fan-out, pricier centralized providers (Together / Fireworks) are more deterministic. The TAO billing surface is unusual; most users prefer the fiat top-up."),
        )
    ),
)
