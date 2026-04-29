# Providers

Every provider shipped in `assets/setup.json`. The full schema is in
[datastructures.md](datastructures.md) under `AppService`; this table
shows only the fields that differ from the default.

Defaults: `apiFormat = OPENAI_COMPATIBLE`, `modelsPath = "v1/models"`,
`chatPath` derived from `ModelType.DEFAULT_PATHS["chat"] =
"v1/chat/completions"`, `seedFieldName = "seed"`,
`modelListFormat = "object"`.

Where a provider declares a `litellmPrefix`, the LiteLLM lookup key is
`<litellmPrefix>/<modelId>`. Where it declares an `openRouterName`, the
OpenRouter lookup key is `<openRouterName>/<modelId>`.

| Provider | Base URL | Admin URL | Default model | Notable non-default fields |
|---|---|---|---|---|
| **OpenAI** | `https://api.openai.com/` | `https://platform.openai.com/settings/organization/api-keys` | `gpt-4o-mini` | `openRouterName=openai`, `defaultModelSource=API` |
| **Anthropic** | `https://api.anthropic.com/` | `https://console.anthropic.com/settings/keys` | `claude-sonnet-4-6` | `apiFormat=ANTHROPIC`, `typePaths.chat=v1/messages`, `modelsPath=v1/models`, `openRouterName=anthropic`, 9 hardcoded models |
| **Google** | `https://generativelanguage.googleapis.com/` | `https://aistudio.google.com/app/apikey` | `gemini-2.0-flash` | `apiFormat=GOOGLE`, `typePaths.chat=v1beta/models/{model}:generateContent`, `modelsPath=v1beta/models`, `openRouterName=google`, `litellmPrefix=gemini`, `defaultModelSource=API` |
| **xAI** | `https://api.x.ai/` | `https://console.x.ai/` | `grok-3-mini` | `openRouterName=x-ai`, `costTicksDivisor=1e10`, `litellmPrefix=xai`, `defaultModelSource=API` |
| **Groq** | `https://api.groq.com/openai/` | `https://console.groq.com/keys` | `llama-3.3-70b-versatile` | `litellmPrefix=groq`, `defaultModelSource=API` |
| **DeepSeek** | `https://api.deepseek.com/` | `https://platform.deepseek.com/api_keys` | `deepseek-chat` | `typePaths.chat=chat/completions`, `modelsPath=models`, `openRouterName=deepseek`, `litellmPrefix=deepseek`, `defaultModelSource=API` |
| **Mistral** | `https://api.mistral.ai/` | `https://console.mistral.ai/api-keys/` | `mistral-small-latest` | `seedFieldName=random_seed`, `openRouterName=mistralai`, `defaultModelSource=API` |
| **Perplexity** | `https://api.perplexity.ai/` | `https://www.perplexity.ai/settings/api` | `sonar` | `typePaths.chat=chat/completions`, `modelsPath=models`, `openRouterName=perplexity`, `supportsCitations=true`, `supportsSearchRecency=true`, 4 hardcoded models |
| **Together** | `https://api.together.xyz/` | `https://api.together.xyz/settings/api-keys` | `meta-llama/Llama-3.3-70B-Instruct-Turbo` | `modelListFormat=array`, `litellmPrefix=together_ai`, `defaultModelSource=API` |
| **OpenRouter** | `https://openrouter.ai/api/` | `https://openrouter.ai/keys` | `anthropic/claude-3.5-sonnet` | `extractApiCost=true`, `defaultModelSource=API` |
| **SiliconFlow** | `https://api.siliconflow.com/` | `https://cloud.siliconflow.com/account/ak` | `Qwen/Qwen3-32B` | `defaultModelSource=API`, 9 hardcoded models |
| **Z.AI** | `https://api.z.ai/api/paas/v4/` | `https://open.bigmodel.cn/usercenter/apikeys` | `glm-5` | `typePaths.chat=chat/completions`, `modelsPath=models`, `openRouterName=z-ai`, `defaultModelSource=API`, 7 hardcoded models |
| **Moonshot** | `https://api.moonshot.ai/` | `https://platform.moonshot.ai/console/api-keys` | `kimi-k2.5` | `openRouterName=moonshot`, `defaultModelSource=API`, 7 hardcoded models |
| **Cohere** | `https://api.cohere.ai/compatibility/` | `https://dashboard.cohere.com/` | `command-a-03-2025` | `openRouterName=cohere`, 7 hardcoded models |
| **AI21** | `https://api.ai21.com/` | `https://studio.ai21.com/` | `jamba-mini` | `openRouterName=ai21`, 4 hardcoded models |
| **DashScope** | `https://dashscope-intl.aliyuncs.com/compatible-mode/` | `https://dashscope.console.aliyun.com/` | `qwen3.5-plus` | 7 hardcoded models |
| **Fireworks** | `https://api.fireworks.ai/inference/` | `https://app.fireworks.ai/` | `accounts/fireworks/models/deepseek-v3p2` | 6 hardcoded models |
| **Cerebras** | `https://api.cerebras.ai/` | `https://cloud.cerebras.ai/` | `llama-3.3-70b` | 7 hardcoded models |
| **SambaNova** | `https://api.sambanova.ai/` | `https://cloud.sambanova.ai/` | `Meta-Llama-3.3-70B-Instruct` | 8 hardcoded models |
| **Baichuan** | `https://api.baichuan-ai.com/` | `https://platform.baichuan-ai.com/` | `Baichuan4-Turbo` | 3 hardcoded models |
| **StepFun** | `https://api.stepfun.com/` | `https://platform.stepfun.com/` | `step-3.5-flash` | 5 hardcoded models |
| **MiniMax** | `https://api.minimax.io/` | `https://platform.minimax.io/` | `MiniMax-M2.7` | `openRouterName=minimax`, 5 hardcoded models |
| **NVIDIA** | `https://integrate.api.nvidia.com/` | `https://build.nvidia.com/` | `nvidia/llama-3.1-nemotron-70b-instruct` | `defaultModelSource=API` |
| **Replicate** | `https://api.replicate.com/v1/` | `https://replicate.com/account/api-tokens` | `meta/meta-llama-3-70b-instruct` | `typePaths.chat=chat/completions`, 3 hardcoded models |
| **Hugging Face** | `https://api-inference.huggingface.co/` | `https://huggingface.co/settings/tokens` | `meta-llama/Llama-3.1-70B-Instruct` | 4 hardcoded models |
| **Lambda** | `https://api.lambdalabs.com/` | `https://cloud.lambdalabs.com/api-keys` | `hermes-3-llama-3.1-405b-fp8` | `defaultModelSource=API` |
| **Lepton** | `https://api.lepton.ai/` | `https://dashboard.lepton.ai/` | `llama3-1-70b` | 4 hardcoded models |
| **01.AI** | `https://api.01.ai/` | `https://platform.01.ai/` | `yi-lightning` | `defaultModelSource=API`, 5 hardcoded models |
| **Doubao** | `https://ark.cn-beijing.volces.com/api/` | `https://console.volcengine.com/` | `doubao-seed-2.0-pro` | `typePaths.chat=v3/chat/completions`, 7 hardcoded models |
| **Reka** | `https://api.reka.ai/` | `https://platform.reka.ai/` | `reka-flash-3` | 4 hardcoded models |
| **Writer** | `https://api.writer.com/` | `https://app.writer.com/` | `palmyra-x5` | 2 hardcoded models |
| **Cloudflare Workers AI** | `https://api.cloudflare.com/client/v4/accounts/YOUR_ACCOUNT_ID/ai/` | `https://dash.cloudflare.com/` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | `defaultModelSource=API` — replace `YOUR_ACCOUNT_ID` in the base URL |
| **DeepInfra** | `https://api.deepinfra.com/v1/openai/` | `https://deepinfra.com/dash/api_keys` | `meta-llama/Meta-Llama-3.1-70B-Instruct` | `typePaths.chat=chat/completions`, `modelsPath=models`, `defaultModelSource=API` |
| **Hyperbolic** | `https://api.hyperbolic.xyz/` | `https://app.hyperbolic.xyz/settings` | `deepseek-ai/DeepSeek-V3` | `defaultModelSource=API` |
| **Novita.ai** | `https://api.novita.ai/v3/openai/` | `https://novita.ai/settings/key-management` | `meta-llama/llama-3.1-70b-instruct` | `typePaths.chat=chat/completions`, `modelsPath=models`, `defaultModelSource=API` |
| **Featherless.ai** | `https://api.featherless.ai/` | `https://featherless.ai/account/api-keys` | `meta-llama/Meta-Llama-3.1-8B-Instruct` | `defaultModelSource=API` |
| **Liquid AI** | `https://inference-1.liquid.ai/` | `https://platform.liquid.ai/` | `lfm-7b` | `defaultModelSource=API` |
| **Llama API** | `https://api.llama.com/compat/` | `https://llama.developer.meta.com/` | `Llama-4-Maverick-17B-128E-Instruct-FP8` | `defaultModelSource=API` |
| **Krutrim** | `https://cloud.olakrutrim.com/` | `https://cloud.olakrutrim.com/console` | `Meta-Llama-3.1-70B-Instruct` | `defaultModelSource=API` |

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
  dollars (xAI uses 10¹⁰).
- **`modelListFormat`**: `"object"` (default — wrapped in
  `{ "data": [...] }`) vs `"array"` (Together's bare top-level array).
- **`modelFilter`**: regex applied to model ids during listing —
  trims out internal/test/preview models from a noisy catalog.
- **`litellmPrefix`** / **`openRouterName`**: composite-key prefixes
  for the corresponding pricing tier.
- **`hardcodedModels`**: fallback list shown when no `/models` endpoint
  is available or `defaultModelSource=MANUAL`.
- **`defaultModelSource`**: `API` or `MANUAL`. Determines whether the
  app fetches a live list or shows the hardcoded fallback.
- **`endpointRules`**: prefix-based routing (e.g. OpenAI maps `gpt-5`
  to the Responses API, everything else to Chat Completions).
