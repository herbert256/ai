# API Formats

Three dispatch paths cover all 42 bundled cloud providers. Identity is
**always** keyed off `service.apiFormat` — never off provider id —
which is why adding an OpenAI-compatible provider is usually a single
JSON entry in `assets/providers.json`.

```kotlin
enum class ApiFormat { OPENAI_COMPATIBLE, ANTHROPIC, GOOGLE }
```

The enum has exactly three values (`ApiFormat.kt`). Across the 42
entries in `assets/providers.json`, **40 are `OPENAI_COMPATIBLE`, 1 is
`ANTHROPIC` (the `Anthropic` provider), 1 is `GOOGLE` (the `Google`
provider)**. The inline `// 28 providers…` comment in `ApiFormat.kt` is
stale — the real OpenAI-compatible count is 40.

Dispatch lives in `com.ai.data.ApiDispatch`; chat streaming in
`com.ai.data.ApiStreaming`. Every public entry point switches on a
single `when (service.apiFormat)` with the three branches:

| Entry point | OPENAI_COMPATIBLE | ANTHROPIC | GOOGLE |
|---|---|---|---|
| `analyze` | `analyzeOpenAi` | `analyzeAnthropic` | `analyzeGemini` |
| `sendChat` | `chatOpenAi` | `chatAnthropic` | `chatGemini` |
| `fetchModelsWithKinds` | `fetchModelsOpenAi` | `fetchModelsAnthropic` | `fetchModelsGemini` |
| `analyzeAgentStreaming` (report) | `streamOpenAiReport` | `streamAnthropicReport` | `streamGeminiReport` |
| `sendChatStream` (chat, in `ApiStreaming.kt`) | `streamOpenAi` | `streamAnthropic` | `streamGemini` |

`embedWithStatus` is the exception: it only supports
`OPENAI_COMPATIBLE` and `GOOGLE`; `ANTHROPIC` (and anything else)
returns `errorMessage = "Embed dispatch only supports OpenAI-compatible
and Google providers"`.

Only `ANTHROPIC` and `GOOGLE` carry format-specific code. The 40
`OPENAI_COMPATIBLE` providers all share one Retrofit interface
(`OpenAiCompatibleApi`, dynamic `@Url` endpoints) and one set of
dispatchers — per-provider behaviour is data, driven by `AppService`
fields read from `providers.json`.

## OPENAI_COMPATIBLE (default — 40 of 42 providers)

The familiar OpenAI Chat Completions wire format. Bearer-token auth.
Request/response shapes are `OpenAiRequest` / `OpenAiResponse` in
`ApiModels.kt`.

- **Auth**: `Authorization: Bearer <key>`.
- **Path**: `service.chatPath`, default `v1/chat/completions`. Built
  by `buildChatUrl` from the provider's configured path; per-provider
  override via `typePaths` (keyed by `ModelType`, e.g. the `chat`
  type path). `buildChatUrl` tolerates a bare base, a full endpoint
  already ending in the path, or a full endpoint ending in a different
  known path (stripped then re-appended).
- **`max_tokens` default**: `defaultMaxTokens(service, model)` =
  `service.maxTokensDefaults.resolveMaxTokens(model) ?: 4096`. Although
  4096 is "Anthropic's required default", the dispatch layer applies it
  to OpenAI-compatible chat too — without a cap, OpenRouter and others
  gate the whole output window against the account balance and 402 on
  expensive models that would answer a normal request fine.
- **Streaming**: SSE — `data: {...}\n\ndata: {...}\n\n…\ndata: [DONE]`.
  Parsed by `parseSseStream` (the shared reader) via the
  `streamOpenAi` / `streamOpenAiReport` implementations. Data lines are
  buffered per W3C spec — multiple `data:` lines in one event are
  concatenated with `\n` and dispatched on the blank line. Content
  comes from `choices[0].delta.content`, falling back to
  `delta.reasoning_content` / `delta.reasoning` only when no content
  streamed (reasoning models that put the answer in the reasoning
  field). Streaming usage is read from the trailing `include_usage`
  chunk (the report path sets `stream_options.include_usage = true`).
- **Native streaming gate**: `sendChatStream` first checks
  `PricingCache.liteLLMSupportsNativeStreaming`. If that returns
  `false`, it routes through the non-streaming `sendChat` and emits the
  whole response as a single chunk instead of opening an SSE stream.
- **Web-search tool**: at the dispatch layer the tool is attached only
  when the agent's `webSearchTool` parameter is set. On Chat
  Completions there is *no* native web-search descriptor —
  `openAiChatWebSearchTool()` returns `null`, so the toggle is a no-op
  on that path. The Responses API does emit one
  (`responsesWebSearchTool()` → `[{type: web_search_preview}]`).
  Whether the 🌐 toggle is even offered for a model is a separate
  capability question, driven by `provider.webSearchModelPatterns` (and
  the catalog flags) via `Settings.isWebSearchCapable`.
- **Reasoning effort**: when the agent's parameters set `reasoningEffort`
  (`"low"` / `"medium"` / `"high"`), the dispatch layer attaches
  `reasoning_effort` to the Chat-Completions request only when
  `isReasoningCapableForDispatch(service, model)` is true (it delegates
  to `Settings.acceptsReasoningEffortParam`, else a catalog chain). Chat
  Completions silently ignores it on older models; the Responses API
  takes it natively as a `reasoning: {effort}` field. xAI ships
  always-on reasoning models that *reject* the `reasoning_effort`
  parameter — those are filtered out via the provider's
  `externalReasoningSignalUntrusted` gate plus
  `reasoningEffortAcceptPatterns`, so the parameter isn't attached even
  though the 🧠 badge is set.

### OpenAI's dual API split

OpenAI uses two separate endpoints depending on the model family:

- **Chat Completions** (`v1/chat/completions`) — `gpt-4o`, `gpt-4`,
  `gpt-3.5-turbo`, etc.
- **Responses API** (`v1/responses`) — `gpt-5*`, `o1*`, `o3*`, `o4*`,
  `gpt-4.1*`. Different request shape (`OpenAiResponsesRequest`),
  different response shape (`OpenAiResponsesApiResponse`).

Routing is `usesResponsesApi(service, model)` (in `AnalysisRepository`):

1. **`service.responsesApiPatterns.anyMatches(model)`** — the
   authoritative source, declared in `providers.json` (the OpenAI entry
   carries prefix patterns `gpt-5`, `o1`, `o3`, `o4`, `gpt-4.1`) and
   editable in Service Settings.
2. Else **`ModelType.infer(model) == ModelType.RESPONSES`** — a naming
   heuristic catching `gpt-5` / `o3` / `o4` prefixes on custom
   OpenAI-compatible endpoints with no pattern config. (`infer` does
   **not** catch `o1` / `gpt-4.1` by name — those route to Responses
   only via the provider's `responsesApiPatterns`.)

There is no `endpointRules` field anywhere in the current code; the
`gpt-5` / `o3` / `o4` prefixes that "used to live in OpenAI's
endpointRules" now live in `ModelType.infer`.
`AppService.responsesPath` is a **computed getter** that resolves to
`v1/responses` from `typePaths` / the type-path defaults — it is not a
stored field. Only OpenAI uses this split; other providers don't.

Responses-API specifics (`OpenAiResponsesRequest`, `ApiModels.kt`):

- The system prompt goes to the top-level `instructions` field, not a
  message.
- A single text-only user turn passes `input` as a bare `String`;
  multi-turn or image turns pass an array of typed `input_text` /
  `input_image` parts. The Chat path forwards image content blocks
  too — don't drop that if a vision regression appears.
- Multiple output-text blocks are concatenated by the content
  extractor rather than only the first being surfaced.
- `url_citation` annotations (`OpenAiResponsesAnnotation`) map onto
  `AnalysisResponse.citations`; `web_search_call` queries surface via
  `OpenAiResponsesAction`.
- Responses-stream content arrives on `event: response.output_text.delta`
  (`data.delta`); the stream ends on `event: response.completed`.

### Quirks worth knowing

- **Mistral** uses `random_seed` instead of `seed` (driven by
  `service.seedFieldName = "random_seed"`), and ships a native
  moderation endpoint (see [Native non-chat endpoints](#native-non-chat-endpoints)).
- **Perplexity** sets `supportsCitations=true` and
  `supportsSearchRecency=true` — its responses carry inline `citations`
  and accept a search-recency parameter the dispatch layer threads
  through.
- **OpenRouter** sets `crossProviderModelList=true` and
  `extractApiCost=true` — its responses include a per-call cost
  (`usage.cost`) the cost layer trusts verbatim, and its cross-provider
  model list feeds cross-provider pricing/type fan-out.
- **Together** sets `pricingFromModelList=true` — its `/v1/models`
  carries native pricing the cost layer reads directly.
- **xAI** sets `costTicksDivisor=1e10` — its returned costs are in
  ticks (`usage.cost_in_usd_ticks`), not dollars. Provider-config edit
  refuses non-positive divisors.
- **Together** and **Google** set `modelListFormat=array` because their
  `/models` endpoints return a bare array instead of `{ "data": [...] }`.
- **Cloudflare Workers AI** has `YOUR_ACCOUNT_ID` in its base URL — the
  user replaces it before the provider becomes useful.

## ANTHROPIC (Claude — 1 provider)

Claude's `/v1/messages` API has its own request/response shape.

- **Auth**: `x-api-key: <key>` + `anthropic-version: 2023-06-01`.
- **Path**: `v1/messages`. The dispatch layer rebuilds the URL from the
  bare host + the canonical path so an already-encoded base can't
  produce a doubled `/v1/messages/v1/messages`.
- **`max_tokens` is required.** The `ClaudeRequest.max_tokens` field is
  nullable (`Int? = null`); the required value is supplied at dispatch
  by `defaultMaxTokens` (per-family `maxTokensDefaults`, default 4096).
  `claudeReasoningBundle` then raises `max_tokens` when it would be
  `<=` the thinking `budget_tokens` (`budget + 4096`), logging a
  warning when it silently bumps a user-set cap.
- **Vision**: image content as a base64 `image` block in the `content`
  array.
- **Streaming**: SSE with both `event:` and `data:` framing. Content
  arrives on `event: content_block_delta` (`data.delta.text`); the
  stream ends on `event: message_stop`. Streaming usage reads
  input(+cache) tokens off `message_start` and output off
  `message_delta`. Error responses on streaming endpoints have their
  body drained and surfaced rather than left half-consumed.
- **Web-search tool**: `web_search_20250305` (`anthropicWebSearchTool()`)
  injected when the agent's `webSearchTool` parameter is set. The 🌐
  toggle's availability is gated by `provider.webSearchModelPatterns`
  (Claude 3.5+ / 3.7 / 4.x).
- **Thinking / reasoning**: when `reasoningEffort` is set and the model
  passes `isReasoningCapableForDispatch`, `claudeReasoningBundle`
  attaches a `thinking` block with a `budget_tokens` translated from
  effort (`low=1024`, `medium=4096`, `high=16384`). Two shapes exist:
  the `budget_tokens` form `{type: enabled, budget_tokens: N}` for
  Claude 3.7 / 4.x, and the adaptive form `{type: adaptive}` +
  top-level `output_config.effort` for Claude Opus 4.7+, gated by
  `provider.adaptiveThinkingPatterns`.
- **Native PDF input**: `ModelCapabilities.supportsPdfInput` (from
  Anthropic `capabilities.pdf_input.supported`) lets a chat session
  attach a PDF as a `document` content block instead of relying on
  client-side OCR.

Models list at `v1/models`. A hardcoded fallback list ships in the
provider's `hardcodedModels` (the eight current Claude ids) but is
**not** merged into the live list — `mergeHardcodedModels` is unset for
Anthropic, so merging would resurrect retired `claude-3.x` ids the API
correctly omits. The per-family `maxTokensDefaults` list determines the
default `max_tokens` when the user hasn't pinned one (first match wins,
falling back to 4096).

## GOOGLE (Gemini — 1 provider)

Gemini's `:generateContent` path-style API.

- **Auth**: `?key=<key>` query parameter (URL-encoded; not a Bearer
  token, not a header). The `GeminiApi` Retrofit interface passes it as
  `@Query("key")` on every call.
- **Path**: `v1beta/models/{model}:generateContent` — the model id is
  in the path, not the body, and is URL-encoded by the dispatcher. For
  streaming the path becomes `:streamGenerateContent` (with
  `@Query("alt") = "sse"`).
- **`role` mapping**: Gemini uses `user` / `model`, not `user` /
  `assistant`. Translated by the dispatch layer.
- **System prompt**: separate `systemInstruction` field rather than a
  message with role=system.
- **Vision**: image content as `inlineData(mimeType, data)` parts.
- **Streaming**: SSE chunked JSON. The shared `parseSseStream` treats a
  candidate carrying a non-null `finishReason` as the final chunk
  (`isGeminiFinalChunk`); content is `candidates[0].content.parts[*].text`
  joined; usage reads the cumulative `usageMetadata` per chunk.
- **Web-search tool**: `google_search` descriptor (`geminiWebSearchTool()`)
  injected when the agent's `webSearchTool` parameter is set. The 🌐
  toggle's availability is gated by `provider.webSearchModelPatterns`
  (Gemini 1.5+ / 2.x).
- **Thinking / reasoning**: Gemini exposes a `thinkingConfig` with a
  `thinkingBudget` (the dispatcher translates `reasoningEffort` → budget)
  and `includeThoughts`. The provider's `reasoningModelPatterns` gates
  whether the field is sent.

Models list at `v1beta/models` with `modelListFormat=array`.
Path-encoded model ids mean the trace file shows the model in the URL,
which `TracingInterceptor` extracts into `trace.model` for providers
that don't encode it in the body.

## Per-format auth & endpoint summary

Request-header / auth setup is one `when (service.apiFormat)` block
(`ApiDispatch.kt`, around the request builder):

| Format | URL | Auth |
|---|---|---|
| `OPENAI_COMPATIBLE` | host + `chatPath` (or `responsesPath` when routed to Responses) | `Authorization: Bearer <key>` |
| `ANTHROPIC` | host + `/v1/messages` | `x-api-key: <key>`, `anthropic-version: 2023-06-01` |
| `GOOGLE` | host + `/v1beta/models/<model>:generateContent` | `?key=<key>` query param |

Per-format response usage is parsed the same way (`ReportStorage`):
`ClaudeUsage` (Anthropic), `GeminiUsageMetadata` (Google), `OpenAiUsage`
(OpenAI-compatible).

## Adding an `ApiFormat`

If you ever need a fourth format:

1. Add the enum value to `ApiFormat`.
2. In `ApiDispatch.kt`, add a `when (service.apiFormat)` branch in every
   dispatcher: `analyze`, `sendChat`, `fetchModelsWithKinds`,
   `embedWithStatus`, `analyzeAgentStreaming`, plus the endpoint-URL
   builder (`dispatchUrl`) and the request-header / auth block.
3. In `ApiStreaming.kt`, add a chat-stream branch in `sendChatStream`
   and an SSE / chunked-JSON content+usage extractor for the shared
   `parseSseStream`.
4. In `ApiModels.kt`, add the wire-format request / response data
   classes — Gson handles the (de)serialisation as long as the field
   names match.
5. In `ReportStorage`, add the per-format usage parser branch.
6. Set `apiFormat` on the new provider's entry in `providers.json`.

The 40-of-42 ratio of `OPENAI_COMPATIBLE` providers means you almost
never need to do this — it's worth pushing back on the third party to
add an OpenAI-compatible endpoint before reaching for a new format.

## Native non-chat endpoints

A handful of providers ship dedicated non-chat endpoints the dispatch
layer routes to instead of building a chat-prompt fallback (helpers in
`data/RerankModerationApi.kt`):

- **`AppService.nativeRerankUrl`** — Cohere `/v2/rerank` and SiliconFlow
  `/v1/rerank`. `callRerankApi` POSTs `{model, query, documents}` and
  converts the response into the `[{id, rank, score, reason}, ...]`
  JSON shape the chat rerank flow already produces. A chat model picked
  for Rerank still goes through the normal analyse path — the native
  endpoint is used only when the picked model classifies as
  `ModelType.RERANK`. See [secondary-results.md](secondary-results.md).
- **`AppService.nativeModerationUrl`** — Mistral `/v1/moderations`.
  `callModerationApi` POSTs `{model, input: [...]}` and lifts
  `tokenUsage` from the response so cost attribution matches
  chat-driven Meta runs.
- **`AppService.nativeCapabilityUrl`** — Cohere `/v1/models` capability
  listing. Drives per-model context-length / vision flags during a
  refresh.

Providers without these URLs fall through to chat-prompt rerank /
moderation, with an explanatory error if the picked model isn't
chat-capable.

## A note on OpenAI's hardcoded-model union

OpenAI's `omni-moderation-*` / `text-moderation-*` (moderation),
`tts-1` (TTS), `whisper-1` (STT), and `dall-e-3` / `gpt-image-1`
(image) model ids do **not** show up in `/v1/models` — they're
documented but unlisted. OpenAI's `providers.json` entry sets
`mergeHardcodedModels=true`, which gates the OpenAI-only fallback union
in `Settings.withModels`: the fetcher path unions
`service.hardcodedModels` into the live `/models` list (and
`distinct()`s the overlap) so the Moderation / TTS / Image / STT
pickers can still find them. Every other provider's API list is
canonical — merging hardcoded ids in would resurrect retired model ids
the API correctly omitted (e.g. Anthropic's `claude-3.x`) — so the
union is gated to providers carrying the flag.

Note the OpenAI entry no longer ships a `hardcodedModels` array in the
bundle, so the union is currently a **no-op** unless the user supplies
those ids. (Anthropic, Perplexity, SiliconFlow *do* carry
`hardcodedModels`, but without `mergeHardcodedModels` those lists serve
only as the manual fallback when no live list has been fetched.)

## Streaming hardening

A few cross-format hardening passes in `parseSseStream`:

- **W3C SSE buffering** — multiple `data:` lines per event are
  concatenated with `\n` and dispatched on a blank line; one optional
  leading space after `data:` is stripped.
- **UTF-8 explicit** — bodies are read as UTF-8, ignoring the
  Content-Type charset, because providers often omit it on SSE streams
  and OkHttp's ISO-8859-1 fallback mangles multi-byte characters.
- **Terminator recognition** — `[DONE]`, `event: message_stop`
  (Anthropic), `event: response.completed` (OpenAI Responses), and
  Gemini's `finishReason` final chunk. A clean EOF with no terminator
  is accepted only if at least one content chunk was emitted; otherwise
  it throws `IOException("SSE stream ended without terminator …")` so a
  truncated stream isn't mistaken for a complete short answer.
- **Error-body drain** — unsuccessful streaming responses have their
  body drained and surfaced with the HTTP status, instead of leaving
  the response half-consumed.
- **Cancellation propagation** — the retry interceptors bail on
  cancellation rather than retrying through a cancelled coroutine.
- **Tracing tags propagate** — `(reportId, category, runId, model)` are
  thread-locals (`ApiTracer.currentTags`) that propagate through
  OkHttp's dispatcher (`TagPropagatingExecutor`) so retries and
  cancellations preserve the originating call's identity. See
  [throttle.md](throttle.md) and [applog.md](applog.md).
