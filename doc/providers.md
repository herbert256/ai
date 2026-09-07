# Providers

Every provider shipped under `assets/providers/` — one JSON file per
provider, each a bare `ProviderDefinition` object. The full schema is
in [datastructures.md](datastructures.md) under `AppService`; this
table shows only the fields that differ from the default. The dispatch
behaviour each `apiFormat` selects is in [api-formats.md](api-formats.md);
the rate-limit / concurrency overrides are in [throttle.md](throttle.md);
the pricing tiers `litellmPrefix` / `openRouterName` / `pricingFromModelList`
/ `crossProviderModelList` feed are in [costs.md](costs.md) and
[repositories.md](repositories.md).

## How the catalog loads

The catalog is **one JSON file per provider** under `assets/providers/`
— 91 files, each a bare `ProviderDefinition` object (no
`{"providers": [...]}` wrapper, no top-level `version`). It is **not**
hardcoded in Kotlin. `ProviderRegistry` (`data/ProviderRegistry.kt`)
is a mutable `object` that starts **empty** on a fresh install; the
catalog is pulled in on demand by
`ProviderRegistry.importFromAsset(context)` — which calls
`readBundledProviderDefs` to read **every** `*.json` file under
`assets/providers/` (sorted by filename for a deterministic merge; a
single malformed file is skipped rather than fatal; a directory-read
failure returns `-1` = "broken bundle") — wired to a button on the
Providers screen and cached in memory as a
`CopyOnWriteArrayList<AppService>`. It persists back to the
`provider_registry` SharedPreferences file (`KEY_PROVIDERS =
"providers_json"`, `KEY_INITIALIZED = "initialized"`) as a list of
`ProviderDefinition` JSON objects. Custom providers the user adds
round-trip through the same `ProviderDefinition` form.

Registry surface (all in `ProviderRegistry`): `getAll`, `findById`,
`add` (refuses duplicate ids), `update` (bumps per-field
`ProviderFieldTimestamps`), `remove`, `importFromAsset` (append-only;
skips ids already present), `upsertFromJson` (replace-by-id-or-append),
`syncFromAsset` (refreshes only the asset fields the user has **not**
hand-edited — timestamp still null — and never appends new providers),
`resetToDefaults` / `restartFromAsset` (wipe + re-seed), and
`findByHost` (resolves a request hostname to its provider via a
`hostIndex` rebuilt on every `save()` from `baseUrl` + `auxHosts`,
first claimant wins on collision — this is what `ProviderThrottle` uses
to find per-provider overrides). The per-file asset read is
`readBundledProviderDefs` (used by `importFromAsset`, `syncFromAsset`,
`restartFromAsset`); `parseProvidersJson` parses the persisted prefs
array on load and silently drops entries with a blank `id` or `baseUrl`
rather than crashing. `upsertFromJson` is the user-import path and still
expects a top-level `{"providers": [...]}` wrapper.

`apiFormat` is parsed by `ApiFormat.valueOf(apiFormat ?:
"OPENAI_COMPATIBLE")` wrapped in a `try/catch` that falls back to
`OPENAI_COMPATIBLE` on any unrecognised value.

`AppService` is intentionally **not** a Kotlin `data class`: its
`equals` / `hashCode` / `toString` are **id-only** (two services are
equal iff their ids match), and it hand-writes a `copy(...)` funnel
covering all 41 fields so a newly added field can't be silently dropped
on update. The synthetic `AppService.LOCAL` (`id = "Local"`, `baseUrl =
"local://"`) is declared in the companion object, is **not** in the
registry, and routes to the on-device runtime — see
[local-runtime.md](local-runtime.md).

## Identity and defaults

The id-unification refactor collapsed the legacy `displayName` and
`prefsKey` fields into `id`. The UI shows `id` directly; SharedPreferences
key prefixes use `id` directly (e.g. `"OpenAI_api_key"`,
`"OpenAI_model"`); `id` is also the human-readable picker label.

Defaults: `apiFormat = OPENAI_COMPATIBLE`, `modelsPath = "v1/models"`,
`seedFieldName = "seed"`, `modelListFormat = "object"`, every Boolean
flag `false`, every throttle override `null` (inherit the global
default).

`typePaths` is a `Map<String, String>` keyed by the `ModelType` string
constants (`"chat"`, `"responses"`, `"embedding"`, …). It holds the
per-model-type API paths a provider exposes; almost every entry only
declares `"chat"`, a few (DeepInfra) also declare `"embedding"`. There
are **no** separate stored `chatPath` / `responsesPath` / `endpointRules`
fields. `AppService.chatPath` and `AppService.responsesPath` are
**computed getters** over `typePaths`. The generic `pathFor(type)`
helper (and `chatPath`, which uses it) walks the full fallback chain
*per-provider override → user-supplied global default
(`ModelType.userDefaults`, from AI Setup → Model Types) →
`ModelType.DEFAULT_PATHS`* (`chat → v1/chat/completions`,
`responses → v1/responses`, `embedding → v1/embeddings`).
`responsesPath` stops one link short — *per-provider override →
`ModelType.userDefaults`* — returning `null` when neither declares a
Responses path. Chat-vs-
Responses routing for OpenAI is decided at dispatch time by
`usesResponsesApi()` — the provider's `responsesApiPatterns`, falling
back to `ModelType.infer(model) == RESPONSES` (which catches the
`gpt-5` / `o3` / `o4` prefixes) — not by any stored `endpointRules`.

Where a provider declares a `litellmPrefix`, the LiteLLM pricing key is
`<litellmPrefix>/<modelId>`. Where it declares an `openRouterName`, the
OpenRouter pricing key is `<openRouterName>/<modelId>`.

| Provider id | Base URL | Admin URL | Default model | Notable non-default fields |
|---|---|---|---|---|
| **OpenAI** | `https://api.openai.com/` | `https://platform.openai.com/settings/organization/api-keys` | `gpt-4o-mini` | `openRouterName=openai`, `modelFilter=gpt\|o1\|o3\|o4`, `defaultModelSource=API`, `mergeHardcodedModels=true`, `builtInEndpoints` (Chat Completions + Responses API), `responsesApiPatterns`/`reasoningModelPatterns`/`webSearchModelPatterns` for `gpt-5`/`o1`/`o3`/`o4`(/`gpt-4.1`), `maxCallsPerProviderPerMinute=120`, `maxConcurrentCallsPerProvider=10` |
| **Anthropic** | `https://api.anthropic.com/` | `https://console.anthropic.com/settings/keys` | `claude-haiku-4-5-20251001` | `apiFormat=ANTHROPIC`, `typePaths.chat=v1/messages`, `openRouterName=anthropic`, `modelFilter=claude`, 8 hardcoded models, `defaultModelSource=API`, `reasoningModelPatterns`/`webSearchModelPatterns`/`adaptiveThinkingPatterns` (opus-4-7), `maxTokensDefaults` (opus-4=32000, sonnet/haiku-4 & claude-3.5=8192), `maxRetriesOn529=5`, `retryBackoffMs529=5000` |
| **Google** | `https://generativelanguage.googleapis.com/` | `https://aistudio.google.com/app/apikey` | `gemini-2.5-flash` | `apiFormat=GOOGLE`, `typePaths.chat=v1beta/models/{model}:generateContent`, `modelsPath=v1beta/models`, `modelListFormat=array`, `openRouterName=google`, `litellmPrefix=gemini`, `defaultModelSource=API`, `reasoningModelPatterns`/`webSearchModelPatterns` (gemini-2.x), `maxCallsPerProviderPerMinute=60` |
| **xAI** | `https://api.x.ai/` | `https://console.x.ai/` | `grok-3-mini` | `openRouterName=x-ai`, `costTicksDivisor=1e10`, `promptTokensIncludeCachedTokens=false`, `litellmPrefix=xai`, `modelFilter=grok`, `defaultModelSource=API`, `externalReasoningSignalUntrusted=true`, `reasoningModelPatterns`/`reasoningEffortAcceptPatterns` for grok-3/4 |
| **Groq** | `https://api.groq.com/openai/` | `https://console.groq.com/keys` | `openai/gpt-oss-20b` | `litellmPrefix=groq`, `defaultModelSource=API` |
| **DeepSeek** | `https://api.deepseek.com/` | `https://platform.deepseek.com/api_keys` | `deepseek-chat` | `typePaths.chat=chat/completions`, `modelsPath=models`, `openRouterName=deepseek`, `litellmPrefix=deepseek`, `modelFilter=deepseek`, `defaultModelSource=API`, `mergeHardcodedModels=true`, **2 hardcoded models** (`deepseek-chat`, `deepseek-reasoner`) merged with `/models` because the live list is sometimes missing, `builtInEndpoints` (Chat Completions + Beta/FIM). DeepSeek is the pinned agent for the bundled `internal/chat-title` prompt — cheap, fast, reliable |
| **Mistral** | `https://api.mistral.ai/` | `https://console.mistral.ai/api-keys/` | `mistral-small-latest` | `seedFieldName=random_seed`, `openRouterName=mistralai`, `modelFilter=mistral\|open-mistral\|codestral\|pixtral`, `defaultModelSource=API`, `nativeModerationUrl=https://api.mistral.ai/v1/moderations`, `builtInEndpoints` (Chat Completions + Codestral), `maxCallsPerProviderPerMinute=30`, `maxConcurrentCallsPerProvider=3` |
| **Perplexity** | `https://api.perplexity.ai/` | `https://www.perplexity.ai/settings/api` | `sonar` | `typePaths.chat=chat/completions`, `openRouterName=perplexity`, `supportsCitations=true`, `supportsSearchRecency=true`, `modelFilter=sonar\|llama`, 4 hardcoded models |
| **Together** | `https://api.together.xyz/` | `https://api.together.xyz/settings/api-keys` | `deepseek-ai/DeepSeek-V4-Flash-0731` | `modelListFormat=array`, `litellmPrefix=together_ai`, `modelFilter=chat\|instruct\|llama`, `defaultModelSource=API`, `pricingFromModelList=true` |
| **OpenRouter** | `https://openrouter.ai/api/` | `https://openrouter.ai/keys` | `ibm-granite/granite-4.0-h-micro` | `extractApiCost=true`, `crossProviderModelList=true`, `defaultModelSource=API`, `maxCallsPerProviderPerMinute=90`, `maxConcurrentCallsPerProvider=8` |
| **MergeGateway** | `https://api-gateway.merge.dev/` | `https://gateway.merge.dev/settings/api-keys` | `ai21/jamba-1-5-large` | OpenRouter-style control-plane gateway (merge.dev). Both chat and models use the OpenAI-compat shim: `typePaths.chat=v1/openai/chat/completions`, `modelsPath=v1/openai/models` (the native `v1/models` is a different non-OpenAI schema — object-valued `aliases`, ids under `model` not `id` — that the shared parser can't read). Slash-prefixed cross-provider ids (`anthropic/…` use dashes, `google/…`/`deepseek/…` use dots). `defaultModelSource=API`, `mergeHardcodedModels=true`, **6 hardcoded models** (Claude / Gemini / DeepSeek). No `openRouterName`/`litellmPrefix` — the slash ids match the OpenRouter cross-provider pricing catalog directly |
| **VercelAIGateway** | `https://ai-gateway.vercel.sh/` | `https://vercel.com/dashboard/ai-gateway/api-keys` | `mistral/ministral-3b` | Cross-provider gateway (~300 models, zero markup). Standard OpenAI shim: `typePaths.chat=v1/chat/completions`, default `modelsPath=v1/models`. Slash-prefixed `creator/model` ids — Anthropic here uses **dots** (`anthropic/claude-opus-4.8`). `defaultModelSource=API`, `mergeHardcodedModels=true`, 8 hardcoded models. No `openRouterName`/`litellmPrefix` |
| **Glama** | `https://glama.ai/api/gateway/openai/` | `https://glama.ai/settings/gateway` | `google/gemini-2.5-flash-lite` | OpenAI-compatible aggregator using its supported legacy endpoint. `typePaths.chat=v1/chat/completions`, default `modelsPath=v1/models`. Slash-prefixed ids, Anthropic in **dash** form; OpenAI ids support dated snapshots and undated aliases. `defaultModelSource=API`, `mergeHardcodedModels=true`, 6 hardcoded models. Catalog includes Google models |
| **Requesty** | `https://router.requesty.ai/` | `https://app.requesty.ai/` | `openai-responses/gpt-4.1-nano` | OpenAI-compatible router (500+ models). `typePaths.chat=v1/chat/completions`, default `modelsPath=v1/models`. Slash-prefixed ids (dash-form Anthropic); some ids carry routing suffixes (`:priority`/`:flex`). `defaultModelSource=API`, `mergeHardcodedModels=true`, 7 hardcoded models |
| **AI-ML-API** | `https://api.aimlapi.com/` | `https://aimlapi.com/app/keys` | `mistralai/mistral-nemo` | OpenAI-compatible aggregator (600+ chat/image/audio models). `typePaths.chat=v1/chat/completions`, default `modelsPath=v1/models`. Catalog carries both dashed and dotted Anthropic aliases plus dated snapshots — live `v1/models` is the source of truth. `defaultModelSource=API`, `mergeHardcodedModels=true`, 7 hardcoded models |
| **AtlasCloud** | `https://api.atlascloud.ai/` | `https://www.atlascloud.ai/console/api-keys` | `bytedance/doubao-seed-2.0-pro-260215` | OpenAI-compatible aggregator (~130 open-weight models incl. `zai-org/glm-5.2`). `typePaths.chat=v1/chat/completions`, default `modelsPath=v1/models`. The `/v1/models` body wraps the list as `{code,msg,data:[{id}]}` — parser reads `data[]`. `defaultModelSource=API`, `mergeHardcodedModels=true`, 5 hardcoded models, `maxTokensDefaults` (deepseek-v3 → 8192). No `openRouterName`/`litellmPrefix` |
| **Parasail** | `https://api.parasail.io/` | `https://saas.parasail.io/keys` | `meta-llama/Llama-3.2-3B-Instruct` | Serverless open-weight host (~70 models; self-hosts `zai-org/GLM-5.2`). `typePaths.chat=v1/chat/completions`, default `modelsPath=v1/models`. Ids in two flavours: HF-style (`zai-org/GLM-5.2`) and Parasail aliases (`parasail-glm-52`), `*-FP8` = quantized. `defaultModelSource=API`, `mergeHardcodedModels=true`, 7 hardcoded models |
| **Baseten** | `https://inference.baseten.co/` | `https://app.baseten.co/settings/api_keys` | `deepseek-ai/DeepSeek-V4-Flash-0731` | Model-APIs inference host — small curated catalog (~11: gpt-oss, GLM incl. self-hosted `zai-org/GLM-5.2`, Kimi, DeepSeek-V4, Nemotron). Host is `inference.baseten.co` (not `app`/`api.baseten.co`). `typePaths.chat=v1/chat/completions`, default `modelsPath=v1/models`. `defaultModelSource=API`, `mergeHardcodedModels=true`, 6 hardcoded models |
| **GMI-Cloud** | `https://api.gmi-serving.com/` | `https://console.gmicloud.ai/` | `XiaomiMiMo/MiMo-V2.5-Pro` | Serverless GPU inference (~66 models; self-hosts `zai-org/GLM-5.2-FP8`). Host is `api.gmi-serving.com`, JWT bearer key. `typePaths.chat=v1/chat/completions`, default `modelsPath=v1/models`. GLM ships `-FP8`-quantized. `defaultModelSource=API`, `mergeHardcodedModels=true`, 6 hardcoded models. Dash id (slash would break the Model Info route) |
| **SiliconFlow** | `https://api.siliconflow.com/` | `https://cloud.siliconflow.com/account/ak` | `ByteDance-Seed/Seed-OSS-36B-Instruct` | `defaultModelSource=API`, 9 hardcoded models, `nativeRerankUrl=https://api.siliconflow.com/v1/rerank` |
| **Z.AI** | `https://api.z.ai/api/paas/v4/` | `https://open.bigmodel.cn/usercenter/apikeys` | `glm-4.5-air` | `typePaths.chat=chat/completions`, `modelsPath=models`, `openRouterName=z-ai`, `modelFilter=glm\|codegeex\|charglm`, 7 hardcoded models, `defaultModelSource=API`, `builtInEndpoints` (Chat Completions + Coding) |
| **Moonshot** | `https://api.moonshot.ai/` | `https://platform.moonshot.ai/console/api-keys` | `kimi-k2.6` | `openRouterName=moonshot`, 4 hardcoded models, `defaultModelSource=API` |
| **Cohere** | `https://api.cohere.ai/compatibility/` | `https://dashboard.cohere.com/` | `command-r7b-12-2024` | `openRouterName=cohere`, `auxHosts=[api.cohere.com]`, `maxCallsPerProviderPerMinute=19`, native `nativeRerankUrl=https://api.cohere.com/v2/rerank` + `nativeCapabilityUrl=https://api.cohere.com/v1/models` |
| **Fireworks** | `https://api.fireworks.ai/inference/` | `https://app.fireworks.ai/` | `accounts/fireworks/models/glm-5p3-flash` | `defaultModelSource=API`, `maxCallsPerProviderPerMinute=12`, `maxConcurrentCallsPerProvider=2` |
| **Cerebras** | `https://api.cerebras.ai/` | `https://cloud.cerebras.ai/` | `gpt-oss-120b` | `defaultModelSource=API`, `maxCallsPerProviderPerMinute=30`, `maxConcurrentCallsPerProvider=2` |
| **SambaNova** | `https://api.sambanova.ai/` | `https://cloud.sambanova.ai/` | `gemma-4-31B-it` | 5 hardcoded models |
| **MiniMax** | `https://api.minimax.io/` | `https://platform.minimax.io/` | `MiniMax-M2.1` | `openRouterName=minimax`, 4 hardcoded models |
| **NVIDIA** | `https://integrate.api.nvidia.com/` | `https://build.nvidia.com/` | `nvidia/nemotron-3-super-120b-a12b` | `defaultModelSource=API`. The NIM catalog lists many models that 404 ("not found for account") until you enable them on build.nvidia.com — the default is a broadly-available one |
| **Replicate** | `https://api.replicate.com/v1/` | `https://replicate.com/account/api-tokens` | `meta/meta-llama-3-8b-instruct` | `apiFormat=REPLICATE` — async predictions API (`POST v1/models/{owner}/{name}/predictions` with `Prefer: wait`), not OpenAI-compatible. 2 hardcoded models; chat models only |
| **HuggingFace** | `https://router.huggingface.co/` | `https://huggingface.co/settings/tokens` | `deepseek-ai/DeepSeek-V3.1` | 4 hardcoded models |
| **DeepInfra** | `https://api.deepinfra.com/v1/openai/` | `https://deepinfra.com/dash/api_keys` | `stepfun-ai/Step-3.7-Flash` | `typePaths.chat=chat/completions` + `typePaths.embedding=embeddings`, `modelsPath=models`, `defaultModelSource=API` |
| **Novita.ai** | `https://api.novita.ai/v3/openai/` | `https://novita.ai/settings/key-management` | `meta-llama/llama-3.1-8b-instruct` | `typePaths.chat=chat/completions`, `modelsPath=models`, `defaultModelSource=API` |
| **NebiusAIStudio** | `https://api.studio.nebius.com/` | `https://studio.nebius.com/settings/api-keys` | `google/gemma-3-27b-it` | `defaultModelSource=API` |
| **Chutes** | `https://llm.chutes.ai/` | `https://chutes.ai/app/api` | `google/gemma-4-31B-turbo-TEE` | `defaultModelSource=API` |
| **AI21** | `https://api.ai21.com/` | `https://docs.ai21.com/reference/jamba-1-6-api-ref` | `jamba-mini` | `modelsPath=studio/v1/models`, `typePaths.chat=studio/v1/chat/completions`, `defaultModelSource=API` |
| **AIHubMix** | `https://aihubmix.com/` | `https://docs.aihubmix.com/en/api/Aihubmix-Integration` | `gpt-4o-mini` | `defaultModelSource=API` |
| **AbacusAI** | `https://routellm.abacus.ai/` | `https://abacus.ai/help/developer-platform/route-llm/` | `gpt-4o-mini` | `defaultModelSource=API` |
| **Alibaba** | `https://ws-dft0j6dsrm76r6ud.eu-central-1.maas.aliyuncs.com/compatible-mode/` | `https://modelstudio.console.alibabacloud.com/` | `qwen3.7-plus` | `defaultModelSource=API`, `mergeHardcodedModels=true`, 7 hardcoded models |
| **Amazon** | `https://bedrock-mantle.eu-central-1.api.aws/` | `https://eu-central-1.console.aws.amazon.com/bedrock-mantle/?region=eu-central-1` | `openai.gpt-oss-20b` | `defaultModelSource=API`, `mergeHardcodedModels=true`, 7 hardcoded models |
| **AnyAPI** | `https://api.anyapi.ai/` | `https://docs.anyapi.ai/` | `openai/gpt-4-turbo` | `defaultModelSource=API` |
| **Auriko** | `https://api.auriko.ai/` | `https://docs.auriko.ai/quickstart` | `gpt-4o` | `defaultModelSource=API` |
| **Avian** | `https://api.avian.io/` | `https://avian.io/docs/` | `deepseek/deepseek-v3.2` | `defaultModelSource=API` |
| **Clarifai** | `https://api.clarifai.com/` | `https://docs.clarifai.com/compute/inference/open-ai/` | `openai/chat-completion/models/gpt-oss-120b` | `modelsPath=v2/ext/openai/v1/models`, `typePaths.chat=v2/ext/openai/v1/chat/completions`, `defaultModelSource=API` |
| **Cortecs** | `https://api.cortecs.ai/` | `https://docs.cortecs.ai/api-overview/chat-completions` | `gemma-4-31b-it` | `defaultModelSource=API` |
| **CrofAI** | `https://crof.ai/` | `https://github.com/nahcrof-code/crofAI` | `glm-4.7-flash` | `defaultModelSource=API` |
| **Crusoe** | `https://api.inference.crusoecloud.com/` | `https://docs.crusoecloud.com/quickstart/getting-started-with-managed-inference` | `openai/gpt-oss-120b` | `defaultModelSource=API` |
| **DigitalOcean** | `https://inference.do-ai.run/` | `https://docs.digitalocean.com/products/inference/how-to/use-chat-completions-api/` | `openai-gpt-4o-mini` | `defaultModelSource=API` |
| **Doubleword** | `https://api.doubleword.ai/` | `https://docs.doubleword.ai/inference-api/creating-an-api-key` | `openai-gpt-oss-20b` | `defaultModelSource=API` |
| **Evroc** | `https://models.think.evroc.com/` | `https://docs.evroc.com/products/think/concepts.html` | `mistralai/Ministral-8B-Instruct-2410` | `defaultModelSource=API` |
| **FastRouter** | `https://api.fastrouter.ai/` | `https://docs.fastrouter.ai/api-reference/chat-completions` | `openai/gpt-4o-mini` | `modelsPath=api/v1/models`, `typePaths.chat=api/v1/chat/completions`, `defaultModelSource=API` |
| **FriendliAI** | `https://api.friendli.ai/` | `https://friendli.ai/docs/guides/serverless_endpoints/openai-compatibility` | `meta-llama/Llama-3.1-8B-Instruct` | `modelsPath=serverless/v1/models`, `typePaths.chat=serverless/v1/chat/completions`, `defaultModelSource=API` |
| **Frogbot** | `https://app.frogbot.ai/` | `https://docs.frogbot.ai/` | `claude-haiku-4-5` | `modelsPath=api/v1/models`, `typePaths.chat=api/v1/chat/completions`, `defaultModelSource=API` |
| **GitHubModels** | `https://models.github.ai/` | `https://docs.github.com/en/rest/models/inference` | `openai/gpt-4.1-mini` | `modelListFormat=array`, `modelsPath=catalog/models`, `typePaths.chat=inference/chat/completions`, `defaultModelSource=API` |
| **HeliconeGateway** | `https://ai-gateway.helicone.ai/` | `https://docs.helicone.ai/rest/ai-gateway/post-v1-chat-completions` | `gpt-4o-mini` | `defaultModelSource=API` |
| **IONet** | `https://api.intelligence.io.solutions/` | `https://io.net/docs/reference/ai-models/get-started-with-io-intelligence-api` | `meta-llama/Llama-3.3-70B-Instruct` | `modelsPath=api/v1/models`, `typePaths.chat=api/v1/chat/completions`, `defaultModelSource=API` |
| **Inceptron** | `https://api.inceptron.io/` | `https://docs.inceptron.io/introduction` | `MiniMaxAI/MiniMax-M2.5` | `defaultModelSource=API` |
| **Jiekou** | `https://api.jiekou.ai/` | `https://docs.jiekou.ai/docs/models/reference-authentication` | `qwen/qwen-2.5-72b-instruct` | `modelsPath=openai/v1/models`, `typePaths.chat=openai/v1/chat/completions`, `defaultModelSource=API` |
| **KiloCode** | `https://api.kilo.ai/` | `https://kilo.ai/docs/gateway/api-reference` | `google/gemini-3.1-flash-lite` | `modelsPath=api/gateway/models`, `typePaths.chat=api/gateway/chat/completions`, `defaultModelSource=API` |
| **LLMGateway** | `https://api.llmgateway.io/` | `https://docs.llmgateway.io/quick-start` | `gpt-4o` | `defaultModelSource=API` |
| **Lambda** | `https://api.lambda.ai/` | `https://docs.lambda.ai/public-cloud/lambda-inference-api/` | `llama3.1-8b-instruct` | `defaultModelSource=API` |
| **LibertAI** | `https://api.libertai.io/` | `https://docs.libertai.io/apis/text/usage.html` | `hermes-3-8b-tee` | `defaultModelSource=API` |
| **LlamaGate** | `https://api.llamagate.dev/` | `https://docs.litellm.ai/docs/providers/llamagate` | `llama-3.1-8b` | `defaultModelSource=API` |
| **MegaNova** | `https://api.meganova.ai/` | `https://docs.meganova.ai/api-reference` | `openai/gpt-5-mini` | `defaultModelSource=API` |
| **ModelScope** | `https://api-inference.modelscope.cn/` | `https://modelscope.cn/docs/model-service/API-Inference/intro` | `Qwen/Qwen2.5-72B-Instruct` | `defaultModelSource=API` |
| **NanoGPT** | `https://nano-gpt.com/` | `https://docs.nano-gpt.com/introduction` | `google/gemini-3-flash-preview` | `modelsPath=api/v1/models`, `typePaths.chat=api/v1/chat/completions`, `defaultModelSource=API` |
| **NearAI** | `https://cloud-api.near.ai/` | `https://docs.near.ai/cloud/guides/openai-compatibility/` | `Qwen/Qwen3-30B-A3B-Instruct-2507` | `defaultModelSource=API` |
| **NeuralWatt** | `https://api.neuralwatt.com/` | `https://portal.neuralwatt.com/docs/api/overview` | `glm-5.2` | `defaultModelSource=API` |
| **Nscale** | `https://inference.api.nscale.com/` | `https://docs.nscale.com/docs/getting-started/overview` | `meta-llama/Llama-3.1-8B-Instruct` | `defaultModelSource=API` |
| **OVHcloud** | `https://oai.endpoints.kepler.ai.cloud.ovh.net/` | `https://help.ovhcloud.com/csm/en-public-cloud-ai-endpoints-getting-started` | `Mistral-7B-Instruct-v0.3` | `defaultModelSource=API` |
| **Ollama** | `https://ollama.com/` | `https://docs.ollama.com/api/openai-compatibility` | `gpt-oss:20b` | `defaultModelSource=API`. Ollama's hosted cloud endpoint — distinct from the synthetic on-device `AppService.LOCAL` |
| **OpenCodeZen** | `https://opencode.ai/` | `https://opencode.ai/docs/zen/` | `deepseek-v4-flash` | `modelsPath=zen/v1/models`, `typePaths.chat=zen/v1/chat/completions`, `defaultModelSource=API` |
| **OrcaRouter** | `https://api.orcarouter.ai/` | `https://www.orcarouter.ai/` | `deepseek/deepseek-v4-flash` | `defaultModelSource=API` |
| **Poe** | `https://api.poe.com/` | `https://creator.poe.com/docs/external-applications/openai-compatible-api` | `gpt-5.4-nano` | `defaultModelSource=API` |
| **Poolside** | `https://inference.poolside.ai/` | `https://docs.poolside.ai/openai-api/chat/create-chat-completion` | `poolside/laguna-xs.2` | `defaultModelSource=API` |
| **PublicAI** | `https://api.publicai.co/` | `https://platform.publicai.co/docs` | `swiss-ai/apertus-8b-instruct` | `defaultModelSource=API` |
| **QiniuAI** | `https://api.qnaigc.com/` | `https://developer.qiniu.com/aitokenapi` | `qwen-turbo` | `defaultModelSource=API` |
| **RegoloAI** | `https://api.regolo.ai/` | `https://docs.regolo.ai/` | `qwen3.5-9b` | `defaultModelSource=API` |
| **RoutingRun** | `https://api.routing.run/` | `https://docs.routing.run/` | `qwen3.5-9b` | `defaultModelSource=API` |
| **Scaleway** | `https://api.scaleway.ai/` | `https://www.scaleway.com/en/docs/generative-apis/reference-content/openai-compatibility/` | `llama-3.1-8b-instruct` | `defaultModelSource=API` |
| **Tencent** | `https://api.hunyuan.cloud.tencent.com/` | `https://cloud.tencent.com/document/product/1729/111007` | `hunyuan-lite` | `defaultModelSource=API` |
| **TensorMesh** | `https://serverless.tensormesh.ai/` | `https://tensormesh.mintlify.app/ai-sdk/guides/provider-configuration` | `openai/gpt-oss-20b` | `defaultModelSource=API` |
| **ThreeZeroTwoAI** | `https://api.302.ai/` | `https://doc-en.302.ai/api-207705104` | `gpt-4o-mini` | `defaultModelSource=API` |
| **Tinfoil** | `https://inference.tinfoil.sh/` | `https://docs.tinfoil.sh/sdk/direct-api-access` | `gpt-oss-120b` | `defaultModelSource=API` |
| **Venice** | `https://api.venice.ai/` | `https://docs.venice.ai/api-reference/api-spec` | `qwen3-4b` | `modelsPath=api/v1/models`, `typePaths.chat=api/v1/chat/completions`, `defaultModelSource=API` |
| **Vivgrid** | `https://api.vivgrid.com/` | `https://vivgrid.com/docs/quick-start` | `gpt-5.4-mini` | `defaultModelSource=API` |
| **VolcEngine** | `https://ark.cn-beijing.volces.com/` | `https://www.volcengine.com/docs/82379/1298459` | `doubao-seed-1-6-251015` | `modelsPath=api/v3/models`, `typePaths.chat=api/v3/chat/completions`, `defaultModelSource=API` |
| **Vultr** | `https://api.vultrinference.com/` | `https://docs.vultr.com/how-to-use-vultr-serverless-inference-in-python` | `qwen2.5-32b-instruct` | `modelsPath=v1/chat/models`, `defaultModelSource=API` |
| **Wafer** | `https://pass.wafer.ai/` | `https://docs.wafer.ai/serverless/setup` | `deepseek-v4-flash` | `defaultModelSource=API` |
| **WandB** | `https://api.inference.wandb.ai/` | `https://docs.wandb.ai/inference/api-reference/chat-completions` | `meta-llama/Llama-3.1-8B-Instruct` | `defaultModelSource=API` |
| **ZenMux** | `https://zenmux.ai/` | `https://zenmux.ai/docs/api/overview.html` | `deepseek/deepseek-chat` | `modelsPath=api/v1/models`, `typePaths.chat=api/v1/chat/completions`, `defaultModelSource=API` |
| **iFlow** | `https://apis.iflow.cn/` | `https://platform.iflow.cn/en/docs` | `qwen3-32b` | `defaultModelSource=API` |

**91 providers total** — 88 `OPENAI_COMPATIBLE`, 1 `ANTHROPIC`
(Anthropic), 1 `GOOGLE` (Google), 1 `REPLICATE` (Replicate). All 88
OpenAI-compatible providers share the unified dispatch path; only
Anthropic, Google and Replicate carry format-specific code.

## Field reference

A few non-default fields warrant explanation:

- **`apiFormat`** (`ApiFormat`, 3 values): dispatch format.
  `OPENAI_COMPATIBLE` (default), `ANTHROPIC` (Claude `/v1/messages`
  with `x-api-key` + `anthropic-version: 2023-06-01` headers and a
  per-call `max_tokens`), `GOOGLE` (Gemini `:generateContent`
  path-style with the key appended as a `?key=` query param, not an
  `Authorization` header). OpenAI-compatible uses standard
  `Authorization: Bearer`.
- **`typePaths`** (`Map<String, String>`): per-model-type API paths
  keyed by the `ModelType` string constants (`"chat"`, `"responses"`,
  `"embedding"`, …). Most providers only override `"chat"`; DeepInfra
  also overrides `"embedding"`. There is no separate stored `chatPath`
  / `responsesPath` / `endpointRules` — those are computed getters on
  `AppService`.
- **`modelsPath`**: GET path for the model-list endpoint, relative to
  `baseUrl`. Default `v1/models`.
- **`seedFieldName`**: name of the seed field in the request body —
  Mistral calls it `random_seed`, every other provider uses `seed`.
- **`supportsCitations`**: provider returns inline `citations` (e.g.
  Perplexity).
- **`supportsSearchRecency`**: provider accepts a `search_recency`
  parameter.
- **`extractApiCost`**: provider's response includes a per-call cost
  field (OpenRouter); the dispatch layer reads it instead of computing
  `tokenUsage × unitPrice`. See [costs.md](costs.md).
- **`costTicksDivisor`**: provider returns cost in ticks rather than
  dollars (xAI uses `1e10`). The provider-config edit screen refuses
  non-positive values.
- **`promptTokensIncludeCachedTokens`**: whether OpenAI-compatible
  `prompt_tokens` already includes cache reads. Defaults to `true`;
  xAI sets `false` because it reports cache reads in flattened
  `cached_tokens` while leaving `prompt_tokens` as the fresh bucket.
- **`modelListFormat`**: `"object"` (default — wrapped in
  `{ "data": [...] }`) vs `"array"` (Together's bare top-level array;
  Google and GitHubModels also return an array).
- **`modelFilter`**: regex applied to model ids during listing —
  trims internal/test/preview models out of a noisy catalog.
- **`litellmPrefix`** / **`openRouterName`**: composite-key prefixes
  for the corresponding pricing tier (see above).
- **`hardcodedModels`**: fallback list shown when no `/models`
  endpoint is available, `defaultModelSource=MANUAL`, **or** to
  reinstate documented-but-unlisted models. With
  `mergeHardcodedModels=true` the list is unioned with the live API
  list on refresh.
- **`defaultModelSource`**: `"API"` or `"MANUAL"`. Determines whether
  the app fetches a live list or shows the hardcoded fallback.
- **`defaultInactive`**: when `true`, the bootstrap seeds
  `providerStates[id] = "inactive"` the **first** time the provider is
  seen — so it ships visible in pickers but disabled until the user
  explicitly flips it on. An install that has already touched the
  provider's state keeps that state untouched. No bundled provider
  currently sets it — StepFun did, but was dropped in the
  keyless-provider sweep.
- **`nativeRerankUrl` / `nativeModerationUrl` / `nativeCapabilityUrl`**:
  full URLs the rerank / moderation / capability dispatchers POST to
  instead of building a chat fallback. `nativeRerankUrl` is set on
  **SiliconFlow** (`/v1/rerank`) and **Cohere** (`/v2/rerank`);
  `nativeModerationUrl` on **Mistral** (`/v1/moderations`);
  `nativeCapabilityUrl` on **Cohere** (`/v1/models`). Providers without
  these fall through to a chat-prompt fallback.
- **`pricingFromModelList`** (Together): the provider's `/v1/models`
  block carries authoritative pricing — harvested into the `TOGETHER`
  tier on every refresh, and that tier beats every curated catalog and
  the manual override for Together calls.
- **`crossProviderModelList`** (OpenRouter): the provider's
  `/v1/models` drives pricing + type fan-out across other providers.
  The OpenRouter tier serves both as OpenRouter's own self-report
  (highest priority for OpenRouter callers) and as the cross-provider
  fallback for non-OpenRouter callers in `PricingCache.getPricing`.
- **`mergeHardcodedModels`**: union the persisted `hardcodedModels`
  with the API list when the fetcher refreshes — so OpenAI moderation /
  TTS / image models (and DeepSeek's two pinned ids) survive a
  `/v1/models` call that doesn't enumerate them.
- **`externalReasoningSignalUntrusted`** (xAI): ignore the LiteLLM /
  models.dev "is reasoning" signal because xAI's always-on reasoning
  variants reject the `reasoning_effort` parameter even though they
  reason internally. The 🧠 badge still renders; only the parameter is
  suppressed.
- **`responsesApiPatterns` / `reasoningModelPatterns` /
  `reasoningEffortAcceptPatterns` / `webSearchModelPatterns` /
  `adaptiveThinkingPatterns`** (`List<ModelPattern>`): per-id pattern
  matchers that gate, respectively, Responses-API routing, the 🧠
  reasoning badge + thinking dispatch, the `reasoning_effort` request
  param (null → fall back to `reasoningModelPatterns`), the 🌐
  web-search tool descriptor, and Anthropic's adaptive-thinking shape.
  A `ModelPattern` takes any of `exact`, `prefix`, `contains`,
  `suffix` (all `String?`, matched against the lowercased model id);
  when more than one is set they must **all** match; an all-null
  pattern never matches.
- **`maxTokensDefaults`** (`List<MaxTokensRule>`, Anthropic): each rule
  is `{ "pattern": <ModelPattern>, "maxTokens": <Int> }`. The
  per-family default `max_tokens` used when the user hasn't pinned one;
  first matching rule wins, falling back to 4096
  (`defaultMaxTokens`). Anthropic ships opus-4 → 32000, sonnet-4 /
  haiku-4 / claude-3.5 → 8192.
- **`builtInEndpoints`** (`List<Endpoint>`, each `{id, name, url,
  isDefault}`): bundled alternate endpoints the user picks between on
  the provider edit screen — OpenAI (Chat Completions + Responses API),
  DeepSeek (Chat Completions + Beta/FIM), Mistral (Chat Completions +
  Codestral), Z.AI (Chat Completions + Coding).
- **`auxHosts`** (Cohere): alternate API hostnames besides the
  `baseUrl` host. `ProviderRegistry.findByHost` indexes them so the
  throttle, retry interceptor, and tracer keep aux-host calls grouped
  under the same logical provider.
- **Throttle overrides** (`maxCallsPerProviderPerMinute`,
  `maxConcurrentCallsPerProvider`, `maxRetriesOn429`,
  `retryBackoffMs429`, `maxRetriesOn529`, `retryBackoffMs529`):
  per-provider caps that override the global `NetworkSettings`
  defaults (60 calls/min, 5 concurrent, 3 retries, 1000 ms backoff);
  `null` inherits. Bundled overrides — OpenAI 120/10, OpenRouter 90/8,
  Google 60/min, Mistral 30/3, Cohere 19/min, Fireworks 12/2,
  Cerebras 30/2, Anthropic 529-retries 5 × 5000 ms. See
  [throttle.md](throttle.md).

## Activation gating

Setting an API key on a provider isn't enough to mark it active. When
the user flips a provider on, the activation gate runs **both** a
`/models` fetch **and** an API-key test against the default model;
**both** must succeed before the state flips to `"ok"` and the default
agent is created. Either failure leaves the provider in `"error"`
state with no agent created. Clearing the key drops it to `"not-used"`;
the inactive toggle sets `"inactive"`.

The four provider states (`providerStates[id]`, persisted under the
`provider_states` prefs key) are therefore `"ok"`, `"error"`,
`"inactive"`, `"not-used"`. A mis-configured provider stays out of the
"Active" lists until it can prove it actually works. Refresh-all
surfaces failed providers with a one-tap nav-to-edit so the user can
fix bad configurations without hunting. A `defaultInactive` provider
would start at `"inactive"` rather than `"not-used"` on its first
bootstrap — though none ship that way now (StepFun did before the
keyless-provider sweep).
