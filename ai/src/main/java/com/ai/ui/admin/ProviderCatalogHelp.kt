package com.ai.ui.admin

internal val providerCatalogHelp: Map<String, HelpContent> = mapOf(
    "providers" to HelpContent(
        title = "Help - Providers",
        cards = listOf(
            HelpCard("Overview", "List of every registered provider (42 bundled plus any user-added). The state of each row is shown by an emoji."),
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
    "provider_ai21" to HelpContent(
        title = "Help - AI21 Labs",
        cards = listOf(
            HelpCard("Overview", "AI21 Labs — Tel Aviv-based foundation model lab, founded 2017. Famous for the Jurassic-1 / Jurassic-2 generation models and (more recently) the Jamba family — a hybrid State-Space-Model + Transformer architecture that scales gracefully to long context."),
            HelpCard("Setup", "studio.ai21.com → API keys. Free trial credits; pay-as-you-go after."),
            HelpCard("Models", "4 hardcoded fallback models: `jamba-mini` (default), `jamba-large`, `jamba-mini-1.7`, `jamba-large-1.7`. Live list at `/v1/models`. `openRouterName=ai21`."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level — no structural quirks. Pricing tier flow goes LiteLLM → models.dev → OpenRouter fallback. Jamba's hybrid arch shows up as faster latency on long contexts than a pure-Transformer model of similar size would have."),
            HelpCard("Pitfalls", "AI21's older Jurassic API used a different request shape; you may see `/jurassic` references in old docs that won't work via this app's chat path. The `1.6` / `1.7` minor version split sometimes prices differently in tiers — manual override on Costs is the workaround."),
        )
    ),
    "provider_dashscope" to HelpContent(
        title = "Help - DashScope (Alibaba Qwen)",
        cards = listOf(
            HelpCard("Overview", "DashScope — Alibaba Cloud's model-as-a-service platform, hosting the Qwen family (their flagship LLMs) plus image / audio / embedding models. The bundled URL points at the international mirror (`dashscope-intl.aliyuncs.com/compatible-mode/`); the China-region service runs at `dashscope.aliyuncs.com`."),
            HelpCard("Setup", "dashscope.console.aliyun.com → mint an API key. Requires an Alibaba Cloud account; international accounts can sign up directly. Free credit allowance; usage-based billing after."),
            HelpCard("Models", "6 hardcoded fallback models: `qwen-plus` (default), `qwen-max`, `qwen-turbo`, `qwen-long`, `qwen3-235b-a22b`, `qwen-coder-plus`. Plus Qwen-VL (vision), QwQ (reasoning), embedding models. The compatible-mode base routes to OpenAI-shape; the native DashScope shape is different."),
            HelpCard("Pricing & quirks", "`compatible-mode/` segment in the base URL is the OpenAI-shape gateway. No `defaultModelSource=API` set — the hardcoded fallback drives the picker until the user fetches. Pricing is competitive on Qwen-Plus / Turbo; Max is positioned against GPT-4o."),
            HelpCard("Pitfalls", "The international vs China-region split matters — the international URL routes outside China but sometimes throttles harder. Some Qwen variants are gated by region (e.g. Qwen-Math is only on the China URL). Image / audio model ids show in `/v1/models` but route via different endpoints this app doesn't yet plumb."),
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
    "provider_baichuan" to HelpContent(
        title = "Help - Baichuan",
        cards = listOf(
            HelpCard("Overview", "Baichuan Intelligence (百川智能) — Chinese AI lab founded 2023 by Wang Xiaochuan (Sogou founder). Authors of the Baichuan family of LLMs (Baichuan-1 through Baichuan-4-Turbo); strong on Chinese-language tasks plus general bilingual capability."),
            HelpCard("Setup", "platform.baichuan-ai.com → API keys. China-region service; requires Chinese phone verification on most accounts. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "5 hardcoded fallback models: `Baichuan4-Turbo` (default), `Baichuan4`, `Baichuan4-Air`, `Baichuan3-Turbo`, `Baichuan3-Turbo-128k`. Capitalised ids — match the platform's exact casing. No live `defaultModelSource=API` set; bundled fallback drives the picker until refresh."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. No `openRouterName` or `litellmPrefix` — pricing tiers may have spotty coverage; manual override on Costs is the workaround for accurate cost tracking."),
            HelpCard("Pitfalls", "China-region accounts mostly. Non-CN users sometimes see carrier-route latency; a local VPN often improves consistency. The Baichuan-3 family is being phased out in favor of Baichuan-4 — check the platform announcements before committing a saved Agent to a 3-class id."),
        )
    ),
    "provider_stepfun" to HelpContent(
        title = "Help - StepFun",
        cards = listOf(
            HelpCard("Overview", "StepFun (阶跃星辰) — Chinese AI lab founded 2023, behind the Step model family. Strong long-context performance (Step-2 up to 16k / 32k tokens) plus a multimodal Step-3 line. Known for Chinese-language coding and reasoning."),
            HelpCard("Setup", "platform.stepfun.com → API keys. China-region; SMS verification typical. Free credits on signup."),
            HelpCard("Models", "6 hardcoded fallback models: `step-2-16k` (default), `step-3`, `step-2-mini`, `step-1-8k`, plus a couple of vision variants. No `defaultModelSource=API` — fallback drives the picker."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. No external pricing-tier mirror — manual cost overrides recommended for accurate accounting. Step-3 multimodal pricing varies by image-token count."),
            HelpCard("Pitfalls", "China-region. Some Step-3 multimodal calls require a different request shape this app doesn't yet plumb (the chat path handles text-only fine). The Step-1 family is older and being phased out."),
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
            HelpCard("Models", "Default: `nvidia/llama-3.1-nemotron-70b-instruct`. Catalog is large — every NVIDIA-hosted model carries a slash-prefixed id (`nvidia/<model>`, `meta/<model>`, `mistralai/<model>`). `defaultModelSource=API` so picker reads the live list."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. The `integrate.api.nvidia.com/` base URL routes to the NIM platform; the actual model serving runs on NVIDIA's GPU infrastructure. No `openRouterName` / `litellmPrefix` — pricing tiers fall through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "The slash-prefixed id (`nvidia/<model>`) is mandatory. Some preview models in the catalog require an enterprise license. The free credit allowance resets monthly; heavy fan-out can deplete it."),
        )
    ),
    "provider_replicate" to HelpContent(
        title = "Help - Replicate",
        cards = listOf(
            HelpCard("Overview", "Replicate — model marketplace founded 2019 by Ben Firshman (ex-Docker). Hosts thousands of open-weight models (chat, image, audio, video) with per-second billing. Strong for image generation (Flux, SDXL, Imagen-class community models); chat support is bolted on."),
            HelpCard("Setup", "replicate.com/account/api-tokens → mint a token. Free credits on signup; pay-per-second after. Note the GitHub-style flow — you sign in with GitHub on most accounts."),
            HelpCard("Models", "3 hardcoded fallback chat models: `meta/meta-llama-3-70b-instruct` (default), `meta/meta-llama-3-8b-instruct`, `mistralai/mistral-7b-instruct-v0.2`. Replicate's actual catalog spans tens of thousands of models — most are image / audio, not chat. `chat` path is `chat/completions`."),
            HelpCard("Pricing & quirks", "Per-second billing is unique — chat models running on shared GPUs round to the second. The OpenAI-compatible chat endpoint is newer than Replicate's native `predictions/` API; some models exist only on the native shape this app doesn't plumb. Slash-prefixed ids (`<owner>/<model>`)."),
            HelpCard("Pitfalls", "Per-second billing means a slow stream costs more than a fast one for the same output. Cold-start latency on rarely-used models can be 30+ seconds. Image models (most of the catalog) don't fit the chat path — try Together / DeepInfra for those flows."),
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
    "provider_lambda" to HelpContent(
        title = "Help - Lambda Labs",
        cards = listOf(
            HelpCard("Overview", "Lambda Labs — GPU cloud company (founded 2012); also runs an inference API for open-weight models on their H100 / H200 fleet. Headquartered in San Francisco. Their Inference Cloud is a smaller catalog focused on currently-popular Llama, Mistral, and Hermes fine-tunes."),
            HelpCard("Setup", "cloud.lambdalabs.com/api-keys → API keys. Need an existing Lambda Cloud account (mostly self-service signup; some enterprise gating). Pay-as-you-go."),
            HelpCard("Models", "Default: `hermes-3-llama-3.1-405b-fp8`. Catalog is curated — Hermes fine-tunes, Llama 3.x, Mistral. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing per-million is competitive on the FP8-quantized variants. No special dispatch quirks. No `openRouterName` / `litellmPrefix` — pricing falls through to OpenRouter cross-provider fallback or DEFAULT."),
            HelpCard("Pitfalls", "Catalog small and rotates fast. The 405B FP8 default is fast but the FP8 quantization may differ from upstream FP16 quality on edge cases. Lambda's GPU cloud is a separate product — don't confuse Inference Cloud rate limits with GPU instance availability."),
        )
    ),
    "provider_lepton" to HelpContent(
        title = "Help - Lepton AI",
        cards = listOf(
            HelpCard("Overview", "Lepton AI — serverless model-serving platform, founded 2023 by ex-Alibaba / Caffe creators. Acquired by NVIDIA in 2025. Hosts Llama, Mistral, Gemma, Whisper, plus image/audio. Strong on cold-start performance."),
            HelpCard("Setup", "dashboard.lepton.ai → API keys. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "4 hardcoded fallback models: `llama3-1-70b` (default), `llama3-1-8b`, `mistral-7b`, `gemma2-9b`. Note the dashed (not dotted) version naming — Lepton's catalog uses `llama3-1-70b` rather than `llama-3.1-70b`. No `defaultModelSource=API` set."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. The dash-instead-of-dot naming is the only structural quirk. No `openRouterName` / `litellmPrefix` — pricing falls through to fallbacks."),
            HelpCard("Pitfalls", "Naming convention catches new users — copying a `llama-3.1-70b` id from elsewhere returns 404 here; you need `llama3-1-70b`. The NVIDIA acquisition (2025) may eventually merge Lepton into NIM; URL stability not guaranteed long-term."),
        )
    ),
    "provider_01ai" to HelpContent(
        title = "Help - 01.AI (Yi)",
        cards = listOf(
            HelpCard("Overview", "01.AI (零一万物) — Chinese AI lab founded 2023 by Kai-Fu Lee (ex-Google China, Microsoft Research). Authors of the Yi model family — Yi-Lightning, Yi-Large, Yi-Medium, Yi-Spark — bilingual but optimized for Chinese."),
            HelpCard("Setup", "platform.01.ai → API keys. China-region; SMS verification required for most accounts. Free credit allowance; pay-as-you-go after. The international URL `api.01.ai` is the public surface."),
            HelpCard("Models", "4 hardcoded fallback models: `yi-lightning` (default), `yi-large`, `yi-medium`, `yi-spark`. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. No special dispatch quirks. Pricing falls through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "China-region. Some accounts are gated to China-only IP routing. Yi-Lightning is the headline cheap+fast model; Yi-Large positions against GPT-4o-mini / Claude Haiku."),
        )
    ),
    "provider_doubao" to HelpContent(
        title = "Help - Doubao (ByteDance)",
        cards = listOf(
            HelpCard("Overview", "Doubao (豆包) — ByteDance's AI model family, served via Volcano Engine (火山引擎). Hosted on `ark.cn-beijing.volces.com` — the Beijing region of Volcano Engine's API platform. The chat path is at `v3/chat/completions` (not the standard v1)."),
            HelpCard("Setup", "console.volcengine.com (Volcano Engine console) → mint API key. Requires a Volcano Engine account; phone verification typical (Chinese SMS). Free credit allowance; usage-based billing after."),
            HelpCard("Models", "4 hardcoded fallback models: `doubao-pro-32k` (default), `doubao-pro-128k`, `doubao-lite-32k`, `doubao-lite-128k`. Pro vs Lite tiers; 32k vs 128k context split."),
            HelpCard("Pricing & quirks", "**`chat` path is `v3/chat/completions`** (not `v1/chat/completions`) — Volcano Engine's versioning is independent of OpenAI's. Otherwise OpenAI-compatible. China-region service; non-CN users may see carrier-routing variance."),
            HelpCard("Pitfalls", "Path quirk catches users who hand-edit JSON — make sure the path stays `v3/chat/completions`. China-region; some carriers don't route to `volces.com` at all without configuration. Doubao-Pro-128k pricing is the same per-token as 32k but cache-policy differs."),
        )
    ),
    "provider_reka" to HelpContent(
        title = "Help - Reka",
        cards = listOf(
            HelpCard("Overview", "Reka AI — multimodal foundation-model lab founded 2022 by ex-DeepMind / Google Brain / Meta researchers. Headquartered in San Francisco. Reka-Core / Flash / Edge are their model tiers — all natively multimodal (text, image, video, audio)."),
            HelpCard("Setup", "platform.reka.ai → API keys. Pay-as-you-go; commercial pricing similar to mid-tier frontier models."),
            HelpCard("Models", "3 hardcoded fallback models: `reka-flash` (default), `reka-core`, `reka-edge`. Flash is the speed/cost balance; Core is the flagship; Edge is the smallest. No `defaultModelSource=API` set."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level for chat. Multimodal inputs (images, video) work but require image/video URLs or base64 the dispatcher already supports for vision. Pricing falls through to fallbacks."),
            HelpCard("Pitfalls", "Catalog small (3 models). Native video understanding is a Reka strength but few app flows surface video input. Reka-Core gates behind higher-tier accounts on first signup."),
        )
    ),
    "provider_writer" to HelpContent(
        title = "Help - Writer",
        cards = listOf(
            HelpCard("Overview", "Writer Inc. — enterprise generative AI platform founded 2020. Authors of the Palmyra family — domain-tuned LLMs marketed for legal, medical, finance, marketing copy. Headquartered in San Francisco; primarily B2B."),
            HelpCard("Setup", "app.writer.com → API keys. Enterprise-focused — most accounts come from a sales engagement; self-service signup gives a Free trial. Pay-as-you-go for production."),
            HelpCard("Models", "2 hardcoded fallback models: `palmyra-x-004` (default), `palmyra-x-003-instruct`. Catalog small and curated. No `defaultModelSource=API` set; bundled list drives the picker."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing falls through to fallbacks. Writer's strength is the enterprise sales / governance side, not unique API quirks."),
            HelpCard("Pitfalls", "Self-service Free trial caps tightly; production requires the sales conversation. Domain-tuned models may behave differently from general-purpose Llama / GPT — prompt engineering targeted to that variant works best."),
        )
    ),
    "provider_cloudflareworkersai" to HelpContent(
        title = "Help - Cloudflare Workers AI",
        cards = listOf(
            HelpCard("Overview", "Cloudflare Workers AI — Cloudflare's serverless inference for open-weight models, running at the edge across their global network. Hosts a curated catalog of Llama, Mistral, Gemma, Phi, plus image / speech models. Free tier with generous monthly quotas; paid scaling integrated into the Workers / Pages platform."),
            HelpCard("Setup", "dash.cloudflare.com → AI → Workers AI → API tokens. **You must replace `YOUR_ACCOUNT_ID` in the base URL** with your actual Cloudflare account id (visible on the right rail of the Workers dashboard) before keys work. Token + account-id together authorize the call."),
            HelpCard("Models", "Default: `@cf/meta/llama-3.3-70b-instruct-fp8-fast`. Catalog uses Cloudflare-prefixed slash ids — `@cf/<owner>/<model>`. `defaultModelSource=API` so the picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. The `@cf/<owner>/<model>` id convention is mandatory — bare ids return 404. The base URL `api.cloudflare.com/client/v4/accounts/YOUR_ACCOUNT_ID/ai/` is the unique structural quirk."),
            HelpCard("Pitfalls", "Forgetting to replace `YOUR_ACCOUNT_ID` is the most common setup failure — the placeholder ships verbatim in the bundled provider definition; first-run config requires editing it under Provider edit → Definition · Basics → Base URL. The free tier resets monthly. Some models are FP8 / FP16 quality-tradeoffs — the `-fast` suffix is FP8."),
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
            HelpCard("Models", "Default: `deepseek-ai/DeepSeek-V3`. `defaultModelSource=API` so picker auto-refreshes. Catalog includes chat, image, audio, vision-language models."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Slash-prefixed ids. No `litellmPrefix` / `openRouterName` — pricing falls through to OpenRouter cross-provider fallback."),
            HelpCard("Pitfalls", "Image / audio models live in the catalog but don't fit the chat dispatch path — only chat models work end-to-end here. Some preview models gate behind tier upgrades."),
        )
    ),
    "provider_novitaai" to HelpContent(
        title = "Help - Novita.ai",
        cards = listOf(
            HelpCard("Overview", "Novita.ai — open-weight serverless inference, founded 2023. Hosts Llama, Mistral, Qwen, DeepSeek; competitive per-token pricing on common open-weight models. Headquartered in Singapore."),
            HelpCard("Setup", "novita.ai/settings/key-management → mint a key. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "Default: `meta-llama/llama-3.1-70b-instruct`. `chat` path is `chat/completions`; `modelsPath=models`. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "Base URL is `api.novita.ai/v3/openai/` — the `/v3/openai/` segment is the OpenAI-compatible gateway. Slash-prefixed ids. No `litellmPrefix` / `openRouterName` — pricing falls through."),
            HelpCard("Pitfalls", "Path quirks similar to DeepInfra — `chat/completions` / `models` (no `v1/` prefix on those parts). The `/v3/openai/` segment is stable but worth noting if you hand-edit URLs."),
        )
    ),
    "provider_featherlessai" to HelpContent(
        title = "Help - Featherless.ai",
        cards = listOf(
            HelpCard("Overview", "Featherless.ai — serverless host for HuggingFace open-weight models, founded 2024. Subscription-based pricing (flat monthly fee for unlimited usage on chosen tier) rather than per-token, making it distinctive in the open-weight serving space."),
            HelpCard("Setup", "featherless.ai/account/api-keys → mint a key. Subscription tiers (Feather / Wing / Falcon-class) determine which models you can run; pay-monthly upfront."),
            HelpCard("Models", "Default: `meta-llama/Meta-Llama-3.1-8B-Instruct`. Slash-prefixed ids matching HF Hub repo paths. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. Subscription billing means token-based pricing tiers (LiteLLM, etc.) don't really apply — your cost is the flat monthly fee. The Costs / Usage screens still report token counts but the dollar conversion via per-token rates won't reflect actual subscription cost."),
            HelpCard("Pitfalls", "Subscription model breaks token-based cost tracking — manual cost overrides set to $0 / $0 give a more honest view if you're on a flat plan. Some larger models (70B+) require higher tiers; the API returns a tier-error which surfaces as 403/402."),
        )
    ),
    "provider_liquidai" to HelpContent(
        title = "Help - Liquid AI",
        cards = listOf(
            HelpCard("Overview", "Liquid AI — Boston-based foundation-model lab, founded 2023 by MIT researchers. Famous for the LFM (Liquid Foundation Models) series — non-Transformer architectures derived from continuous-time recurrent networks. Strong performance per parameter, especially on long context."),
            HelpCard("Setup", "platform.liquid.ai → API keys. Pay-as-you-go from signup; smaller free trial credit than larger providers."),
            HelpCard("Models", "Default: `lfm-7b`. Catalog: LFM-7B, LFM-40B, plus instruct variants. `defaultModelSource=API` so picker auto-refreshes. Smaller catalog overall."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. The non-Transformer architecture is a Liquid AI selling point but transparent at the API level — calls look like any other chat completion. No special dispatch quirks."),
            HelpCard("Pitfalls", "Smaller catalog and less-tested ecosystem mean fewer model lookups in LiteLLM / OpenRouter — pricing tiers may have spotty coverage. Cold-start latency varies."),
        )
    ),
    "provider_llamaapi" to HelpContent(
        title = "Help - Llama API (Meta)",
        cards = listOf(
            HelpCard("Overview", "Meta's official Llama API — direct hosted inference for the Llama family, run by Meta themselves. Distinct from Llama-on-other-providers (Together, Groq, Fireworks, …) which run open-weight derivatives. Beta product; integration with developer.meta.com."),
            HelpCard("Setup", "llama.developer.meta.com → API keys. Beta / waitlist depending on signup timing; some accounts get instant access."),
            HelpCard("Models", "Default: `Llama-4-Maverick-17B-128E-Instruct-FP8`. Catalog focuses on the Llama 4 family (Maverick, Scout) plus current Llama 3.x. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "Base URL is `api.llama.com/compat/` — the `/compat/` segment is the OpenAI-compatible gateway. Otherwise standard. No `litellmPrefix` / `openRouterName` — pricing falls through to fallbacks."),
            HelpCard("Pitfalls", "Beta product means API stability isn't guaranteed across signups; expect occasional non-backwards-compatible changes. Same model id (Llama 3.3-70B, etc.) on this provider may price / perform differently from the Together / Groq / Fireworks hosted variants."),
        )
    ),
    "provider_krutrim" to HelpContent(
        title = "Help - Krutrim (Ola)",
        cards = listOf(
            HelpCard("Overview", "Ola Krutrim — Indian AI lab and inference platform, part of Ola Cabs founder Bhavish Aggarwal's tech portfolio. Founded 2023 with a focus on Indian-language understanding alongside general-purpose open-weight model serving."),
            HelpCard("Setup", "cloud.olakrutrim.com/console → API keys. Indian-region service; international signups possible. Free credit allowance."),
            HelpCard("Models", "Default: `Meta-Llama-3.1-70B-Instruct`. Catalog: open-weight Llama / Mistral / Qwen plus Krutrim's own multilingual models. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible at the wire level. Pricing falls through to OpenRouter cross-provider fallback. Strong Indian-language support is a Krutrim differentiator."),
            HelpCard("Pitfalls", "India-region routing means non-IN users may see latency variance. Smaller catalog than Together / Fireworks. Native Indian-language models exist but specific endpoints may not all be plumbed via the chat path."),
        )
    ),
    "provider_nebiusaistudio" to HelpContent(
        title = "Help - Nebius AI Studio",
        cards = listOf(
            HelpCard("Overview", "Nebius AI Studio — inference platform from Nebius (the AI-cloud arm of the former Yandex international assets). Headquartered in Amsterdam; runs a large GPU fleet across European data centers. Hosts Llama, DeepSeek, Qwen, Mistral, Mixtral."),
            HelpCard("Setup", "studio.nebius.com/settings/api-keys → mint a key. Free credits on signup; pay-as-you-go after. Strong European data residency story for users with that preference."),
            HelpCard("Models", "Default: `meta-llama/Meta-Llama-3.1-70B-Instruct`. Slash-prefixed ids matching HF Hub paths. `defaultModelSource=API` so picker auto-refreshes."),
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
    "provider_inferencenet" to HelpContent(
        title = "Help - Inference.net",
        cards = listOf(
            HelpCard("Overview", "Inference.net — open-weight serverless inference, founded 2024. Hosts Llama, DeepSeek, Qwen on a low-priced compute pool. Headquartered in San Francisco."),
            HelpCard("Setup", "inference.net/dashboard/api-keys → mint a key. Free credit allowance; pay-as-you-go after."),
            HelpCard("Models", "Default: `meta-llama/llama-3.3-70b-instruct/fp-8`. Note the third path segment (`/fp-8`) — Inference.net's id convention encodes the quantization in the id itself. `defaultModelSource=API` so picker auto-refreshes."),
            HelpCard("Pricing & quirks", "OpenAI-compatible. The `/<owner>/<model>/<quant>` three-part id convention is the structural quirk — copy ids verbatim from their catalog. Pricing falls through to fallbacks."),
            HelpCard("Pitfalls", "The quantization-in-id convention catches new users — `meta-llama/llama-3.3-70b-instruct` (without `/fp-8`) returns 404. Smaller catalog than Together / Fireworks; newer entrant means less LiteLLM coverage so pricing tiers may have gaps."),
        )
    ),
)
