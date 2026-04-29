# API Formats

Three dispatch paths cover all 38 default providers. Identity is
**always** keyed off `service.apiFormat` — never off provider id —
which is why adding an OpenAI-compatible provider is usually a single
JSON entry in `setup.json`.

```kotlin
enum class ApiFormat { OPENAI_COMPATIBLE, ANTHROPIC, GOOGLE }
```

Dispatch lives in `com.ai.data.ApiDispatch`; streaming in
`com.ai.data.ApiStreaming`.

## OPENAI_COMPATIBLE (default — 36 of 38 providers)

The familiar OpenAI Chat Completions wire format. Bearer-token auth.
Same request/response shape as `OpenAiRequest` / `OpenAiResponse` in
`ApiModels.kt`.

- **Auth**: `Authorization: Bearer <key>`
- **Path**: `chat/completions` (default `v1/chat/completions`).
  Per-provider override via `typePaths.chat`.
- **Streaming**: SSE — `data: {...}\n\ndata: {...}\n\n…\ndata: [DONE]`.
  Parsed by `ApiStreaming.streamOpenAiCompatible`.
- **Tool descriptors**: provider-specific; the dispatch layer attaches
  `web_search_preview` for OpenAI Responses-API models, falls back to
  no-tool on a 400 tool-rejection.

### OpenAI's dual API split

OpenAI uses two separate endpoints depending on the model family:

- **Chat Completions** (`v1/chat/completions`) — `gpt-4o`, `gpt-4`,
  `gpt-3.5-turbo`, `o1`-mini, etc.
- **Responses API** (`v1/responses`) — `gpt-5`, `gpt-5-mini`, `o3*`,
  `o4*`, `gpt-4.1*`. Different request shape (`OpenAiResponsesRequest`),
  different response shape (`OpenAiResponsesApiResponse`).

Routing is done by `usesResponsesApi()` and the `endpointRules`
attached to OpenAI in `setup.json`. Other providers don't share this
split — only OpenAI.

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
  ticks, not dollars.
- **Together** sets `modelListFormat=array` because its `/models`
  endpoint returns a bare array instead of `{ "data": [...] }`.
- **Cloudflare Workers AI** has `YOUR_ACCOUNT_ID` in its base URL — the
  user replaces it before the provider becomes useful.

## ANTHROPIC (Claude — 1 provider)

Claude's `/v1/messages` API has its own request/response shape.

- **Auth**: `x-api-key: <key>` + `anthropic-version: 2023-06-01`.
- **Path**: `v1/messages` (override on Anthropic via `typePaths.chat`).
- **`max_tokens` is required.** The dispatch layer defaults to 4096 if
  the user didn't set one.
- **Vision**: image content as a base64 `image` block in the
  `content` array.
- **Streaming**: SSE with both `event:` and `data:` framing. Parsed by
  `ApiStreaming.streamAnthropic`.
- **Web search tool**: `web_search_20250305` injected when the user
  toggles 🌐 and the model is Claude 3.5+ / 4.x.

Models list at `v1/models`. Hardcoded fallback ships in `setup.json`.

## GOOGLE (Gemini — 1 provider)

Gemini's `:generateContent` path-style API.

- **Auth**: `?key=<key>` query parameter (NOT a Bearer token).
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
  and 2.x.

Models list at `v1beta/models`. Path-encoded model ids mean the trace
file shows the model in the URL, which the `TracingInterceptor`
extracts into `trace.model` for non-body-encoded providers.

## Adding an `ApiFormat`

If you ever need a fourth format:

1. Add the enum value to `ApiFormat`.
2. In `ApiDispatch.kt`, add a `when` branch in every dispatch function
   (`analyze`, `analyzeStreaming`, `fetchModels`, `testModel`).
3. In `ApiStreaming.kt`, add an SSE / chunked-JSON parser branch.
4. In `ApiModels.kt`, add the wire-format request / response data
   classes — Gson handles the (de)serialisation as long as the field
   names match.
5. Set `apiFormat` on the new provider's entry in `setup.json`.

The 28-of-38 ratio of `OPENAI_COMPATIBLE` providers means you almost
never need to do this — it's worth pushing back on the third party
to add an OpenAI-compatible endpoint before reaching for a new format.
