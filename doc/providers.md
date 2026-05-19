# Providers

Every provider shipped in `assets/providers.json`. The full schema is
in [datastructures.md](datastructures.md) under `AppService`; this
table shows only the fields that differ from the default.

The bundled JSON is a flat `{"providers": [...]}` array — no top-level
`version`. Custom providers added by the user persist as
`ProviderDefinition` JSON in the `provider_registry` SharedPreferences
file and are merged with the bundle at runtime.

The id-unification refactor collapsed the legacy `displayName` and
`prefsKey` fields into `id`. UI shows `id` directly; SharedPreferences
key prefixes use `id` directly (e.g. `"OpenAI_api_key"`,
`"OpenAI_model"`).

Defaults: `apiFormat = OPENAI_COMPATIBLE`, `modelsPath = "v1/models"`,
`chatPath` derived from `ModelType.DEFAULT_PATHS["chat"] =
"v1/chat/completions"`, `seedFieldName = "seed"`,
`modelListFormat = "object"`.

Where a provider declares a `litellmPrefix`, the LiteLLM lookup key is
`<litellmPrefix>/<modelId>`. Where it declares an `openRouterName`, the
OpenRouter lookup key is `<openRouterName>/<modelId>`.

| Provider id | Base URL | Admin URL | Default model | Notable non-default fields |
|---|---|---|---|---|
| **OpenAI** | `https://api.openai.com/` | `https://platform.openai.com/settings/organization/api-keys` | `gpt-4o-mini` | `openRouterName=openai`, `modelFilter=gpt|o1|o3|o4`, `defaultModelSource=API`, hardcoded moderation models (`omni-moderation-*`, `text-moderation-*`) since `/v1/models` doesn't surface them |
| **Anthropic** | `https://api.anthropic.com/` | `https://console.anthropic.com/settings/keys` | `claude-haiku-4-5-20251001` | `apiFormat=ANTHROPIC`, `typePaths.chat=v1/messages`, `openRouterName=anthropic`, `modelFilter=claude`, 8 hardcoded models, `defaultModelSource=API` |
| **Google** | `https://generativelanguage.googleapis.com/` | `https://aistudio.google.com/app/apikey` | `gemini-2.0-flash` | `apiFormat=GOOGLE`, `typePaths.chat=v1beta/models/{model}:generateContent`, `modelsPath=v1beta/models`, `modelListFormat=array`, `openRouterName=google`, `litellmPrefix=gemini`, `defaultModelSource=API` |
| **xAI** | `https://api.x.ai/` | `https://console.x.ai/` | `grok-3-mini` | `openRouterName=x-ai`, `costTicksDivisor=1e10`, `litellmPrefix=xai`, `modelFilter=grok`, `defaultModelSource=API` |
| **Groq** | `https://api.groq.com/openai/` | `https://console.groq.com/keys` | `llama-3.3-70b-versatile` | `litellmPrefix=groq`, `defaultModelSource=API` |
| **DeepSeek** | `https://api.deepseek.com/` | `https://platform.deepseek.com/api_keys` | `deepseek-chat` | `typePaths.chat=chat/completions`, `modelsPath=models`, `openRouterName=deepseek`, `litellmPrefix=deepseek`, `modelFilter=deepseek`, `defaultModelSource=API`, **2 hardcoded models** (`deepseek-chat`, `deepseek-reasoner`) merged with `/models` because the API list is sometimes missing. DeepSeek is the bundled `agent` pin for the icon prompts (`internal/icon` + `internal/report_icon_3th`) and the chat_title prompt — cheap, fast, reliably one-glyph emojis |
| **Mistral** | `https://api.mistral.ai/` | `https://console.mistral.ai/api-keys/` | `mistral-small-latest` | `seedFieldName=random_seed`, `openRouterName=mistralai`, `modelFilter=mistral|open-mistral|codestral|pixtral`, `defaultModelSource=API` |
| **Perplexity** | `https://api.perplexity.ai/` | `https://www.perplexity.ai/settings/api` | `sonar` | `typePaths.chat=chat/completions`, `openRouterName=perplexity`, `supportsCitations=true`, `supportsSearchRecency=true`, `modelFilter=sonar|llama`, 4 hardcoded models |
| **Together** | `https://api.together.xyz/` | `https://api.together.xyz/settings/api-keys` | `meta-llama/Llama-3.3-70B-Instruct-Turbo` | `modelListFormat=array`, `litellmPrefix=together_ai`, `modelFilter=chat|instruct|llama`, `defaultModelSource=API` |
| **OpenRouter** | `https://openrouter.ai/api/` | `https://openrouter.ai/keys` | `ibm-granite/granite-4.0-h-micro` | `extractApiCost=true`, `defaultModelSource=API` |
| **SiliconFlow** | `https://api.siliconflow.com/` | `https://cloud.siliconflow.com/account/ak` | `Qwen/Qwen2.5-7B-Instruct` | `defaultModelSource=API`, 9 hardcoded models |
| **Z.AI** | `https://api.z.ai/api/paas/v4/` | `https://open.bigmodel.cn/usercenter/apikeys` | `glm-4.7-flash` | `typePaths.chat=chat/completions`, `modelsPath=models`, `openRouterName=z-ai`, `modelFilter=glm|codegeex|charglm`, 7 hardcoded models, `defaultModelSource=API` |
| **Moonshot** | `https://api.moonshot.ai/` | `https://platform.moonshot.ai/console/api-keys` | `kimi-latest` | `openRouterName=moonshot`, 4 hardcoded models, `defaultModelSource=API` |
| **Cohere** | `https://api.cohere.ai/compatibility/` | `https://dashboard.cohere.com/` | `command-a-03-2025` | `openRouterName=cohere`, 4 hardcoded models. Native `/v2/rerank` endpoint wired for the Rerank flow |
| **AI21** | `https://api.ai21.com/` | `https://studio.ai21.com/` | `jamba-mini` | `openRouterName=ai21`, 4 hardcoded models |
| **DashScope** | `https://dashscope-intl.aliyuncs.com/compatible-mode/` | `https://dashscope.console.aliyun.com/` | `qwen-plus` | 6 hardcoded models |
| **Fireworks** | `https://api.fireworks.ai/inference/` | `https://app.fireworks.ai/` | `accounts/fireworks/models/llama-v3p3-70b-instruct` | 4 hardcoded models |
| **Cerebras** | `https://api.cerebras.ai/` | `https://cloud.cerebras.ai/` | `llama-3.3-70b` | 5 hardcoded models |
| **SambaNova** | `https://api.sambanova.ai/` | `https://cloud.sambanova.ai/` | `Meta-Llama-3.3-70B-Instruct` | 5 hardcoded models |
| **Baichuan** | `https://api.baichuan-ai.com/` | `https://platform.baichuan-ai.com/` | `Baichuan4-Turbo` | 5 hardcoded models |
| **StepFun** | `https://api.stepfun.com/` | `https://platform.stepfun.com/` | `step-2-16k` | 6 hardcoded models |
| **MiniMax** | `https://api.minimax.io/` | `https://platform.minimax.io/` | `MiniMax-M2.1` | `openRouterName=minimax`, 4 hardcoded models |
| **NVIDIA** | `https://integrate.api.nvidia.com/` | `https://build.nvidia.com/` | `nvidia/llama-3.1-nemotron-70b-instruct` | `defaultModelSource=API` |
| **Replicate** | `https://api.replicate.com/v1/` | `https://replicate.com/account/api-tokens` | `meta/meta-llama-3-70b-instruct` | `typePaths.chat=chat/completions`, 3 hardcoded models |
| **HuggingFace** | `https://api-inference.huggingface.co/` | `https://huggingface.co/settings/tokens` | `meta-llama/Llama-3.1-70B-Instruct` | 4 hardcoded models |
| **Lambda** | `https://api.lambdalabs.com/` | `https://cloud.lambdalabs.com/api-keys` | `hermes-3-llama-3.1-405b-fp8` | `defaultModelSource=API` |
| **Lepton** | `https://api.lepton.ai/` | `https://dashboard.lepton.ai/` | `llama3-1-70b` | 4 hardcoded models |
| **01.AI** | `https://api.01.ai/` | `https://platform.01.ai/` | `yi-lightning` | `defaultModelSource=API`, 4 hardcoded models |
| **Doubao** | `https://ark.cn-beijing.volces.com/api/` | `https://console.volcengine.com/` | `doubao-pro-32k` | `typePaths.chat=v3/chat/completions`, 4 hardcoded models |
| **Reka** | `https://api.reka.ai/` | `https://platform.reka.ai/` | `reka-flash` | 3 hardcoded models |
| **Writer** | `https://api.writer.com/` | `https://app.writer.com/` | `palmyra-x-004` | 2 hardcoded models |
| **CloudflareWorkersAI** | `https://api.cloudflare.com/client/v4/accounts/YOUR_ACCOUNT_ID/ai/` | `https://dash.cloudflare.com/` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | `defaultModelSource=API` — replace `YOUR_ACCOUNT_ID` in the base URL |
| **DeepInfra** | `https://api.deepinfra.com/v1/openai/` | `https://deepinfra.com/dash/api_keys` | `meta-llama/Meta-Llama-3.1-70B-Instruct` | `typePaths.chat=chat/completions`, `modelsPath=models`, `defaultModelSource=API` |
| **Hyperbolic** | `https://api.hyperbolic.xyz/` | `https://app.hyperbolic.xyz/settings` | `deepseek-ai/DeepSeek-V3` | `defaultModelSource=API` |
| **Novita.ai** | `https://api.novita.ai/v3/openai/` | `https://novita.ai/settings/key-management` | `meta-llama/llama-3.1-70b-instruct` | `typePaths.chat=chat/completions`, `modelsPath=models`, `defaultModelSource=API` |
| **Featherless.ai** | `https://api.featherless.ai/` | `https://featherless.ai/account/api-keys` | `meta-llama/Meta-Llama-3.1-8B-Instruct` | `defaultModelSource=API` |
| **LiquidAI** | `https://inference-1.liquid.ai/` | `https://platform.liquid.ai/` | `lfm-7b` | `defaultModelSource=API` |
| **LlamaAPI** | `https://api.llama.com/compat/` | `https://llama.developer.meta.com/` | `Llama-4-Maverick-17B-128E-Instruct-FP8` | `defaultModelSource=API` |
| **Krutrim** | `https://cloud.olakrutrim.com/` | `https://cloud.olakrutrim.com/console` | `Meta-Llama-3.1-70B-Instruct` | `defaultModelSource=API` |
| **NebiusAIStudio** | `https://api.studio.nebius.com/` | `https://studio.nebius.com/settings/api-keys` | `meta-llama/Meta-Llama-3.1-70B-Instruct` | `defaultModelSource=API` |
| **Chutes** | `https://llm.chutes.ai/` | `https://chutes.ai/app/api` | `deepseek-ai/DeepSeek-V3` | `defaultModelSource=API` |
| **Inference.net** | `https://api.inference.net/` | `https://inference.net/dashboard/api-keys` | `meta-llama/llama-3.3-70b-instruct/fp-8` | `defaultModelSource=API` |

**42 providers total.**

## Field reference

A few non-default fields warrant explanation:

- **`apiFormat`**: dispatch format. `OPENAI_COMPATIBLE` (default),
  `ANTHROPIC` (Claude `/v1/messages` format with required `max_tokens`),
  `GOOGLE` (Gemini `:generateContent` path-style with `?key=` auth).
- **`typePaths`**: per-model-type API paths overriding the global
  defaults. Most providers only override `chat`; some (Anthropic) also
  override `embedding`/`rerank`/etc.
- **`modelsPath`**: GET path for the model-list endpoint, relative to
  `baseUrl`. Default `v1/models`.
- **`seedFieldName`**: name of the seed field in the request body —
  Mistral calls it `random_seed`, every other provider uses `seed`.
- **`supportsCitations`**: provider returns inline `citations` (e.g.
  Perplexity).
- **`supportsSearchRecency`**: provider accepts a `search_recency`
  parameter.
- **`extractApiCost`**: provider's response includes a per-call cost
  field (e.g. OpenRouter); the dispatch layer extracts it instead of
  computing from `tokenUsage * unitPrice`.
- **`costTicksDivisor`**: provider returns cost in ticks rather than
  dollars (xAI uses 10¹⁰). Provider-config edit refuses non-positive
  values.
- **`modelListFormat`**: `"object"` (default — wrapped in
  `{ "data": [...] }`) vs `"array"` (Together's bare top-level array;
  Google also returns an array).
- **`modelFilter`**: regex applied to model ids during listing —
  trims out internal/test/preview models from a noisy catalog.
- **`litellmPrefix`** / **`openRouterName`**: composite-key prefixes
  for the corresponding pricing tier.
- **`hardcodedModels`**: fallback list shown when no `/models` endpoint
  is available, `defaultModelSource=MANUAL`, **or** to reinstate
  documented-but-unlisted endpoints (OpenAI moderation / TTS /
  transcription / image; merged via the OpenAI-only fallback union
  in `Settings.withModels`).
- **`defaultModelSource`**: `API` or `MANUAL`. Determines whether the
  app fetches a live list or shows the hardcoded fallback.
- **`nativeRerankUrl` / `nativeModerationUrl` / `nativeCapabilityUrl`**:
  full URLs the rerank / moderation / capability dispatchers POST
  to instead of building a chat fallback. Cohere ships
  `nativeRerankUrl=https://api.cohere.com/v2/rerank` and
  `nativeCapabilityUrl=https://api.cohere.com/v1/models`; Mistral
  ships `nativeModerationUrl=https://api.mistral.ai/v1/moderations`.
  Providers without these fall through to a chat-prompt fallback.
- **`pricingFromModelList`** (Together): provider's `/v1/models`
  block carries authoritative pricing — harvested into the
  `TOGETHER` tier on every refresh.
- **`crossProviderModelList`** (OpenRouter): provider's `/v1/models`
  drives pricing + type fan-out across other providers — the
  OpenRouter tier is the final cross-provider fallback in
  `PricingCache.getPricing` for non-OpenRouter callers.
- **`mergeHardcodedModels`**: union persisted `hardcodedModels`
  with the API list when the fetcher refreshes — used so OpenAI
  moderation / TTS / image models survive a `/v1/models` call
  that doesn't enumerate them.
- **`externalReasoningSignalUntrusted`** (xAI): ignore the
  LiteLLM / models.dev "is reasoning" signal because xAI's
  always-on reasoning variants reject the `reasoning_effort`
  parameter even though they reason internally. The 🧠 badge
  still renders; only the parameter is suppressed.
- **`responsesApiPatterns` / `reasoningModelPatterns` /
  `reasoningEffortAcceptPatterns` / `webSearchModelPatterns` /
  `adaptiveThinkingPatterns`** (lists of `ModelPattern`): per-id
  pattern matchers that gate dispatch routing (Responses API),
  feature badges (🧠, 🌐), the `reasoning_effort` parameter, and
  Anthropic's adaptive-thinking shape. Patterns take `prefix`,
  `contains`, or `regex`.
- **`maxTokensDefaults`** (Anthropic): `[{ match: <ModelPattern>,
  value: <Int> }]` — per-family default `max_tokens` when the
  user hasn't pinned one. First match wins; falls back to 4096.
- **`builtInEndpoints`**: bundled alternate endpoints (DeepSeek
  main + reasoner, Mistral chat + Codestral, Z.AI mainland +
  international). User picks one on the provider edit screen.
- **`auxHosts`**: alternate API hostnames besides the `baseUrl`
  host. The rate-limit-retry interceptor and tracer use this to
  keep aux-host calls grouped under the same logical provider.

## Activation gating

Setting an API key on a provider isn't enough to mark it active —
both the `/models` fetch and the API-key test must pass. A
mis-configured provider stays `not-used` until it can prove it
actually works. Refresh-all surfaces failed providers with a one-tap
nav-to-edit so the user can fix bad configurations without hunting.

