# API Formats

Three dispatch paths cover all 42 default cloud providers. Identity is
**always** keyed off `service.apiFormat` — never off provider id —
which is why adding an OpenAI-compatible provider is usually a single
JSON entry in `assets/providers.json`.

```kotlin
enum class ApiFormat { OPENAI_COMPATIBLE, ANTHROPIC, GOOGLE }
```

Dispatch lives in `com.ai.data.ApiDispatch`; streaming in
`com.ai.data.ApiStreaming`.

## OPENAI_COMPATIBLE (default — 40 of 42 providers)

The familiar OpenAI Chat Completions wire format. Bearer-token auth.
Same request/response shape as `OpenAiRequest` / `OpenAiResponse` in
`ApiModels.kt`.

- **Auth**: `Authorization: Bearer <key>`
- **Path**: `chat/completions` (default `v1/chat/completions`).
  Per-provider override via `typePaths.chat`.
- **Streaming**: SSE — `data: {...}\n\ndata: {...}\n\n…\ndata: [DONE]`.
  Parsed by `ApiStreaming.streamOpenAiCompatible`. Data lines are
  buffered per W3C spec — multi-line `data:` chunks are concatenated
  before the blank-line dispatch.
- **Tool descriptors**: provider-specific; the dispatch layer attaches
  `web_search_preview` for OpenAI Responses-API models (gated by
  `provider.webSearchModelPatterns`), falls back to no-tool on a 400
  tool-rejection. The fallback trigger covers more variant
  tool-rejection error shapes than earlier versions.
- **Reasoning effort**: when the agent's parameters set
  `reasoningEffort` (`"low"` / `"medium"` / `"high"`), the dispatch
  layer attaches `reasoning_effort` to the request when
  `inferAcceptsReasoningEffortParam(provider, model)` is true.
  Chat Completions silently ignores it on older models; the
  Responses API takes it natively. xAI's always-on reasoning
  variants are filtered out via
  `provider.reasoningEffortAcceptPatterns` so the parameter isn't
  attached even though the 🧠 badge is set.

### OpenAI's dual API split

OpenAI uses two separate endpoints depending on the model family:

- **Chat Completions** (`v1/chat/completions`) — `gpt-4o`, `gpt-4`,
  `gpt-3.5-turbo`, `o1`-mini, etc.
- **Responses API** (`v1/responses`) — `gpt-5`, `gpt-5-mini`, `o3*`,
  `o4*`, `gpt-4.1*`. Different request shape (`OpenAiResponsesRequest`),
  different response shape (`OpenAiResponsesApiResponse`).

Routing is done by `usesResponsesApi()` (in `AnalysisRepository`): it
checks the provider's `responsesApiPatterns` from `providers.json`
first, then falls back to `ModelType.infer(model) == RESPONSES` (the
`gpt-5` / `o3` / `o4` prefixes that used to live in OpenAI's removed
`endpointRules` now live in `ModelType.infer`). There is no stored
`endpointRules` / `responsesPath` field on the serialised provider
definition — `AppService.responsesPath` is a computed getter that
resolves to `v1/responses` from `typePaths` / the type-path defaults.
Other providers don't share this split — only OpenAI. Multi-text
Responses-API output blocks are **concatenated** by the dispatch layer
rather than only the first block being surfaced. The Chat path
forwards image content blocks unchanged.

The Responses API also surfaces `OpenAiResponsesAnnotation`
(`url_citation` annotations the dispatch layer maps onto
`AnalysisResponse.citations`) and `OpenAiResponsesAction`
(`web_search_call` queries) — see `ApiModels.kt` for the wire
shapes.

### Quirks worth knowing

- **Mistral** uses `random_seed` instead of `seed`. Driven by
  `service.seedFieldName`.
- **Perplexity** sets `supportsCitations=true` and
  `supportsSearchRecency=true` — its responses carry inline `citations`
  and accept a `search_recency` parameter the dispatch layer threads
  through.
- **OpenRouter** sets `extractApiCost=true` — its responses include a
  per-call cost (`usage.cost`). The dispatch layer pulls that directly
  rather than computing from `tokens × unitPrice`.
- **xAI** sets `costTicksDivisor=1e10` — its returned costs are in
  ticks, not dollars. Provider-config edit refuses non-positive
  divisors.
- **Together** and **Google** set `modelListFormat=array` because their
  `/models` endpoints return a bare array instead of `{ "data": [...] }`.
- **Cloudflare Workers AI** has `YOUR_ACCOUNT_ID` in its base URL — the
  user replaces it before the provider becomes useful.

## ANTHROPIC (Claude — 1 provider)

Claude's `/v1/messages` API has its own request/response shape.

- **Auth**: `x-api-key: <key>` + `anthropic-version: 2023-06-01`.
- **Path**: `v1/messages` (override on Anthropic via `typePaths.chat`).
- **`max_tokens` is required.** The dispatch layer defaults to 4096 if
  the user didn't set one. The dispatcher logs the override when
  reasoning is on.
- **Vision**: image content as a base64 `image` block in the
  `content` array.
- **Streaming**: SSE with both `event:` and `data:` framing. Parsed by
  `ApiStreaming.streamAnthropic`. Error responses on streaming
  endpoints have their body drained and surfaced rather than left
  half-consumed.
- **Web search tool**: `web_search_20250305` injected when the user
  toggles 🌐 and the model matches `provider.webSearchModelPatterns`
  (Claude 3.5+ / 4.x).
- **Thinking / reasoning**: when the agent's parameters set
  `reasoningEffort` and the model matches
  `provider.reasoningModelPatterns`, the dispatch layer attaches a
  `thinking` block with `budget_tokens` translated from the effort
  level. Anthropic's adaptive-thinking opt-in shape (claude-opus-4.6
  / 4.7) is gated by `provider.adaptiveThinkingPatterns`.
- **Native PDF input**: `ModelCapabilities.supportsPdfInput` (from
  Anthropic `capabilities.pdf_input.supported`) lets a chat session
  attach a PDF as a `document` content block instead of relying on
  client-side OCR.

Models list at `v1/models`. Hardcoded fallback ships in
`providers.json`. The provider's per-family `maxTokensDefaults`
list determines the default `max_tokens` when the user hasn't
pinned one (first match wins, default 4096).

## GOOGLE (Gemini — 1 provider)

Gemini's `:generateContent` path-style API.

- **Auth**: `?key=<key>` query parameter (URL-encoded; not a Bearer
  token).
- **Path**: `v1beta/models/{model}:generateContent` —
  the model id is in the path, not the body. The dispatcher substitutes
  it. For streaming, the path becomes `:streamGenerateContent`.
- **`role` mapping**: Gemini uses `user` / `model`, not `user` /
  `assistant`. Translated by the dispatch layer.
- **System prompt**: separate `systemInstruction` field rather than a
  message with role=system.
- **Vision**: image content as `inlineData(mimeType, data)` parts.
- **Streaming**: chunked-JSON (each line is a complete JSON object).
  Parsed by `ApiStreaming.streamGoogle`.
- **Web search tool**: `google_search` tool descriptor for Gemini 1.5+
  and 2.x (gated by `provider.webSearchModelPatterns`).
- **Thinking / reasoning**: Gemini exposes a `thinkingConfig` field
  with `thinkingBudget` (the dispatcher translates `reasoningEffort`
  → budget) and `includeThoughts`. The provider's
  `reasoningModelPatterns` gates whether the field is sent.

Models list at `v1beta/models` with `modelListFormat=array`.
Path-encoded model ids mean the trace file shows the model in the
URL, which the `TracingInterceptor` extracts into `trace.model` for
non-body-encoded providers.

## Adding an `ApiFormat`

If you ever need a fourth format:

1. Add the enum value to `ApiFormat`.
2. In `ApiDispatch.kt`, add a `when` branch in every dispatch function
   (`analyze`, `analyzeStreaming`, `fetchModels`, `testModel`).
3. In `ApiStreaming.kt`, add an SSE / chunked-JSON parser branch.
4. In `ApiModels.kt`, add the wire-format request / response data
   classes — Gson handles the (de)serialisation as long as the field
   names match.
5. Set `apiFormat` on the new provider's entry in `providers.json`.

The 40-of-42 ratio of `OPENAI_COMPATIBLE` providers means you almost
never need to do this — it's worth pushing back on the third party
to add an OpenAI-compatible endpoint before reaching for a new format.

## Native non-chat endpoints

A handful of providers ship dedicated non-chat endpoints the
dispatch layer routes to instead of building a chat-prompt
fallback:

- **`AppService.nativeRerankUrl`** — Cohere `/v2/rerank`. The Rerank
  flow POSTs `{model, query, documents}` and converts the response
  into the `[{id, rank, score, reason}, ...]` JSON shape the chat
  rerank flow already produces. See
  [secondary-results.md](secondary-results.md).
- **`AppService.nativeModerationUrl`** — Mistral `/v1/moderations`.
  The Moderate flow POSTs `{model, input: [...]}` and lifts
  `tokenUsage` from the response so cost attribution matches
  chat-driven Meta runs.
- **`AppService.nativeCapabilityUrl`** — Cohere-style `/v1/models`
  capability listing. Drives the per-model context-length / vision
  flags during a refresh.

Providers without these URLs fall through to chat-prompt rerank /
moderation with an explanatory error if the picked model isn't
chat-capable.

## A note on OpenAI's hardcoded-model union

The OpenAI `omni-moderation-*` / `text-moderation-*` (moderation),
`tts-1` (TTS), `whisper-1` (STT), and `dall-e-3` / `gpt-image-1`
(image) model ids do **not** show up in `/v1/models` — they're
documented but unlisted. OpenAI's `providers.json` entry sets
`mergeHardcodedModels=true`, which gates the OpenAI-only fallback
union in `Settings.withModels`: the fetcher path unions
`service.hardcodedModels` into the live `/models` list (and
`distinct()`s the overlap) so the Moderation / TTS / Image / STT
pickers can still find them. Every other provider's API list is
canonical (merging hardcoded ids in would resurrect retired model ids
the API correctly omitted, e.g. Anthropic's claude-3.x), so the union
is gated to providers carrying that flag. Note the OpenAI entry no
longer ships a `hardcodedModels` array in the bundle, so the union is
currently a no-op unless the user supplies those ids.

## Streaming hardening

A few cross-format hardening passes:

- **W3C SSE buffering** — multi-line `data:` chunks per spec.
- **Error-body drain** — unsuccessful streaming responses have their
  body drained and surfaced with the HTTP status, instead of leaving
  the response half-consumed.
- **UTF-8 explicit** — `ApiStreaming` reads bodies as UTF-8 to dodge
  platform-locale surprises.
- **Cancellation propagation** — `RateLimitRetryInterceptor` bails
  on cancellation rather than retrying through a cancelled coroutine.
- **Permanent 4xx skip** — `withRetry` skips retries on permanent 4xx
  failures.
- **Tracing tags propagate** — `(reportId, category)` are
  thread-locals that propagate through OkHttp's dispatcher so
  retries and cancellations preserve the originating call's
  identity.
