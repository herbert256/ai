# API, provider, and runtime suggestions

## P01 - Split `ApiDispatch.kt` by provider format and call kind

Priority: P1

Evidence:

`ApiDispatch.kt` is 1,864 LOC. It includes max-token defaults, timeout wrappers,
host gate logic, analyze dispatch, chat dispatch, model fetching, embeddings,
audit URL reconstruction, request builders, response parsers, and streaming
dispatch. The main dispatch starts at `ApiDispatch.kt:120`; chat implementation
continues at `ApiDispatch.kt:572`; streaming starts at `ApiDispatch.kt:1255`.

Suggestion:

Split into:

- `dispatch/ApiDispatch.kt`: public extension functions and format switch.
- `dispatch/OpenAiDispatch.kt`
- `dispatch/AnthropicDispatch.kt`
- `dispatch/GeminiDispatch.kt`
- `dispatch/StreamingDispatch.kt`
- `dispatch/EmbeddingDispatch.kt`
- `dispatch/DispatchAudit.kt`
- `dispatch/DispatchModels.kt`

Expected benefit:

Provider-specific changes become localized. Golden tests can target one file's
surface at a time.

## P02 - Introduce a provider call descriptor

Priority: P1

Evidence:

Several dispatch functions independently pass service, model, API key, base URL,
params, prompt/messages, image data, and call type. Host gating and audit logic
then reconstruct parts of that context (`ApiDispatch.kt:98`,
`ApiDispatch.kt:716`).

Suggestion:

Create:

```kotlin
data class ProviderCallDescriptor(
    val service: AppService,
    val model: String,
    val baseUrl: String,
    val kind: ApiCallKind,
    val streaming: Boolean,
    val traceCategory: String?
)
```

Use it for timeout, host gate, audit URL, trace labels, and usage recording.

Expected benefit:

Provider calls become easier to log, test, and display in a future execution
plan preview.

## P03 - Add dispatch golden tests for each API family

Priority: P1

Evidence:

The source handles several nuanced provider behaviors:

- OpenAI-compatible chat vs responses API (`ApiDispatch.kt:580`).
- Vision content blocks in responses chat (`ApiDispatch.kt:631` to
  `ApiDispatch.kt:650`).
- Anthropic and Google request/response parsing paths.
- Streaming usage merge modes (`ApiDispatch.kt:1280`).
- Audit URL reconstruction (`ApiDispatch.kt:685`).

Suggestion:

Create JSON fixture tests:

- request body generation for OpenAI chat
- request body generation for OpenAI responses with image
- Anthropic request body with system prompt and reasoning
- Gemini request body with system instruction and tools
- response parsing for text, reasoning fallback, usage, and errors
- streaming SSE chunks for each family

Expected benefit:

The app can safely evolve provider support without relying on live API calls.

## P04 - Make capability resolution a single service

Priority: P1

Evidence:

Capabilities such as reasoning, web search, vision, local runtime support, and
model type overrides are checked in UI badges, selection, dispatch, and replay
flows. Some dispatch checks rely on current settings through `SettingsHolder`
(`AppViewModel.kt:539`).

Suggestion:

Introduce `ModelCapabilityResolver`:

- input: provider, model, current settings/catalogs
- output: `ModelCapabilities`
- reason/source for each capability

Use the same resolver in selection UI, dispatch filtering, badges, execution
plan preview, and tests.

Expected benefit:

The UI and dispatch cannot disagree about whether a model supports vision,
reasoning, web search, local execution, embeddings, or secondary use.

## P05 - Expose provider diagnostics as structured state

Priority: P2

Evidence:

Throttle diagnostics are logged by the caps watchdog in `AppViewModel`
(`AppViewModel.kt:421` to `AppViewModel.kt:448`). ProviderThrottle and caps
state are also read by dashboard screens.

Suggestion:

Create `ProviderRuntimeDiagnostics`:

- provider/host
- concurrent in-flight
- per-minute queue state
- short-bench/cooldown state
- failures by status code
- next available time

Expose a flow for UI and logs.

Expected benefit:

Users can understand why work is waiting. Tests can assert structured state
rather than parsing diagnostic strings.

## P06 - Isolate blocking retry-yield behavior

Priority: P1

Evidence:

`PermitHold.yieldFor` releases permits, calls `Thread.sleep`, and re-acquires
permits using blocking try-acquire loops (`ThrottledBatch.kt:232`,
`ThrottledBatch.kt:241`, `ThrottledBatch.kt:257`). The comment explains this is
because the yielder runs on an OkHttp worker thread.

Suggestion:

Keep the behavior, but wrap it behind an interface:

```kotlin
interface BackoffPermitYielder {
    fun yieldFor(ms: Long)
}
```

Add tests for release/reacquire state transitions using fake semaphores or a
test yielder. Also add metrics around yield count and reacquire wait time.

Expected benefit:

The blocking behavior stays contained and measurable. It becomes easier to
replace later if retry handling moves out of OkHttp interceptors.

## P07 - Treat local runtime as another provider backend behind the same call model

Priority: P2

Evidence:

Local runtime state is read directly in UI places such as `LocalRuntimeBody`,
which scans installed models with `remember` (`AiDashboardScreen.kt:2635` to
`AiDashboardScreen.kt:2637`). Local generation and local embedding are separate
runtime paths in the source.

Suggestion:

Define a `ModelBackend` abstraction:

- `CloudProviderBackend`
- `LocalLlmBackend`
- `LocalEmbedderBackend`

The dispatch layer can route through the backend while the UI sees one model
runtime shape.

Expected benefit:

Local/cloud differences become explicit. Product features such as execution
plans, job center, usage stats, and capability display can include local models
without special UI cases.

## P08 - Add provider metadata provenance

Priority: P2

Functional suggestion:

For every displayed model capability, pricing, blocked/cooldown status, and
endpoint decision, show or expose "why":

- built-in provider metadata
- fetched model list
- manual override
- external catalog cache
- provider self-report
- cooldown/test-excluded/inaccessible state

Technical suggestion:

Return a `ResolvedValue<T>` from capability/pricing resolvers:

```kotlin
data class ResolvedValue<T>(
    val value: T,
    val source: String,
    val confidence: Confidence,
    val timestamp: Long?
)
```

Expected benefit:

Users can debug model availability and cost decisions without reading logs.

## P09 - Normalize provider errors into typed failures

Priority: P1

Evidence:

Many dispatch paths return `AnalysisResponse(error = "...")` with formatted
strings. `FetchModelsException` is a typed exception for model fetching
(`ApiDispatch.kt:183`), but generation errors are less structured.

Suggestion:

Add:

```kotlin
sealed interface ProviderFailure {
    data class Http(...)
    data class Timeout(...)
    data class Parse(...)
    data class EmptyBody(...)
    data class CapabilityRejected(...)
}
```

Keep the user-facing message, but preserve type, status code, retryability, and
provider body separately.

Expected benefit:

Retry, benching, UI badges, audit logs, and tests can reason about failures
without string parsing.

## P10 - Use live-call mocks as first-class test infrastructure

Priority: P1

Suggestion:

For provider-level unit tests, use mock web servers or fake `ApiFactory`
implementations so test cases can assert:

- URL path
- headers
- request body
- response parsing
- retry behavior
- trace and usage side effects

This aligns with the existing need to test API calls without spending credits or
requiring real keys.

Expected benefit:

Provider regressions become cheap to catch locally.
