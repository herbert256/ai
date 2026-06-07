# Bug review — Data layer / view-models / infrastructure (audit-3, fresh from current code)

Scope: `data/**`, `viewmodel/**`, `model/**`. Findings are grouped by file
and numbered continuously. Every location was read from the live code (2026-06-06).

## File: ai/src/main/java/com/ai/data/ApiModels.kt

### Bug 1 — Severity: HIGH — Category: Gson reflection nullability
**Location:** ApiModels.kt:46-82 (`NullSafeFieldAdapterFactory`)
**Symptom:** A persisted / hand-edited / partially-truncated JSON that is missing a non-null Kotlin `String` (or a non-null `FloatArray` / other non-collection) field deserializes that field to `null`, then NPEs later, far from the read site (e.g. in a Compose recomposition or a cost computation), where the catch blocks at the loaders no longer protect.
**Root cause:** Gson constructs via `UnsafeAllocator`, bypassing the primary constructor and its defaults. The safety net here only coerces fields whose declared type is assignable to `List`/`Set`/`Map`/`Collection` (lines 54-61). It deliberately skips `String` (documented, lines 36-44) and structurally also skips primitive arrays such as `KnowledgeChunk.embedding: FloatArray`. So any non-null non-collection field that is genuinely absent stays at the JVM zero value (`null`) inside a type the rest of the app trusts as non-null.
**Reproduction:** Truncate/corrupt a `reports/<id>.json` so `provider` or `model` on a `ReportAgent` is absent (still valid JSON), reopen the report — `loadReport` succeeds (no parse exception), then the per-model viewer NPEs on `agent.provider`/`agent.model`.
**Proposed fix:** Either (a) deserialize through a moshi-kotlin-style / kotlinx.serialization codec that honours Kotlin non-null + defaults, or (b) extend the factory to also re-assert field-specific defaults for the small set of genuinely-non-null `String`/array fields (it already lists them in `normalizeReport`) instead of leaving the contract violated.
**Status:** Fixed (2026-06-07) — non-null agent provider/model defaulted at the load site (normalizeReport), the documented field-specific-default pattern; the global factory/codec swap NOT taken (documented String?-sentinel hazard, needs build verification)

### Bug 2 — Severity: LOW — Category: cost extraction
**Location:** ApiModels.kt:899-914 (`extractApiCost(OpenAiUsage)`)
**Symptom:** For a provider that ships `cost_in_usd_ticks` on a scale other than 1e10 and has no `costTicksDivisor` configured, the cost is computed with the hardcoded `10_000_000_000.0` fallback (lines 910-912) and is silently wrong.
**Root cause:** The `else` fallback assumes every unknown provider uses the xAI tick scale. There is no signal that the divisor was inferred vs configured.
**Proposed fix:** Only apply the 1e10 fallback for providers actually known to use it (gate on `provider == xAI`); otherwise return `null` so the layered pricing path computes cost from tokens.
**Status:** Fixed (2026-06-07) — the hardcoded 1e10 tick fallback is now gated to provider id `xAI`; other providers without `costTicksDivisor` return null and use token-based pricing fallback

### Bug 3 — Severity: LOW — Category: token accounting
**Location:** ApiModels.kt:855-868 (`OpenAiUsage.toTokenUsage`)
**Symptom:** For an OpenAI-compatible provider that reports `prompt_tokens` already *excluding* cached tokens but also flattens `cached_tokens` (the comment at line 194 says "some xAI / others flatten this"), `fresh = total - cached` under-counts the fresh input bucket, mis-splitting billed input vs cached input.
**Root cause:** The subtraction `(total - cached)` assumes `prompt_tokens` always *includes* the cached portion, which is true for OpenAI/DeepSeek but not guaranteed for every provider that flattens `cached_tokens`.
**Proposed fix:** Make the cached-inclusive assumption per-provider (it already differs for Anthropic), or clamp/validate `cached <= total` against the provider's known shape.
**Status:** Open (unconfirmed — depends on each provider's exact wire shape)

### Bug 4 — Severity: LOW — Category: deserialization robustness
**Location:** ApiModels.kt:286 (`ClaudeMessage(role, content: Any)`)
**Symptom:** `content` is declared `Any` (non-null). A persisted/replayed Claude message with a null content would deserialize to `null` in a non-null field (same class of issue as Bug 1) and NPE when re-serialized for a regenerate.
**Root cause:** `Any` (vs `Any?`) participates in the Unsafe-allocator null trap and is not coercible.
**Proposed fix:** Declare `content: Any?` and null-guard at the build sites, or never persist `ClaudeMessage` (it is a wire type — confirm it is never written to disk).
**Status:** Fixed (2026-06-07) — `ClaudeMessage.content` is now nullable to tolerate null replay/deserialization payloads

### Bug 5 — Severity: LOW — Category: trace redaction completeness
**Location:** ApiModels.kt (wire request types e.g. `OpenAiRequest`, `GeminiRequest`)
**Symptom:** Request bodies serialized for traces can embed large base64 image payloads (vision); the trace request body is captured in full (no cap on the request side — see TracingInterceptor Bug 24) and rolls into the backup zip.
**Root cause:** Image content blocks live inside the request body; there is no size cap on the captured request body.
**Proposed fix:** Cap/elide base64 `image_url` / `inline_data` payloads in the trace request body the way response bodies are capped.
**Status:** Fixed (2026-06-07) — `updateChatMessages`, `updateContent`, and the same-shaped model-switch update now use a single locked `updateResult` read-modify-write, so concurrent cost/icon bumps are preserved

## File: ai/src/main/java/com/ai/data/AtomicFileWrite.kt

### Bug 6 — Severity: MEDIUM — Category: crash durability
**Location:** AtomicFileWrite.kt:40-58 (`writeTextAtomic`)
**Symptom:** After the `ATOMIC_MOVE` rename, a power loss before the *directory* metadata is flushed can leave the directory entry still pointing at the old inode (or none), even though the file's own data was fsync'd. The "atomic" promise covers the file content but not the rename's durability.
**Root cause:** The code fsyncs the temp file's descriptor (line 43) but never fsyncs the *parent directory* after `Files.move`. POSIX requires an fsync on the directory to make a rename durable.
**Proposed fix:** After the move, open the parent directory and `fsync` it (best-effort, ignore on platforms that reject it), mirroring the file fsync already done.
**Status:** Fixed (2026-06-07) — parent directory is fsync'd after the move (best-effort) so the rename is durable

### Bug 7 — Severity: LOW — Category: error visibility
**Location:** AtomicFileWrite.kt:59-63
**Symptom:** A failed write logs to `AppLog.e` and returns `false`, but the partial-staging tmp cleanup `tmp.delete()` is itself wrapped in a swallow; on a full disk the orphan `<name>.<uuid>.tmp` files can accumulate in `filesDir` and are themselves backed up.
**Root cause:** No sweep of orphan `*.tmp` staging files; UUID names make them un-deduplicated.
**Proposed fix:** Periodically prune `*.<uuid>.tmp` older than N minutes, or use a single-attempt temp with a deterministic-but-per-thread name cleaned in a finally.
**Status:** Fixed (2026-06-07) — atomic writes now prune stale sibling temp files and warn when temp cleanup fails

## File: ai/src/main/java/com/ai/data/AppService.kt

### Bug 8 — Severity: MEDIUM — Category: data visibility loss
**Location:** AppService.kt:242-250 (`AppServiceAdapter.deserialize`)
**Symptom:** Renaming or removing a custom provider id makes every persisted object that embeds an `AppService` (chat sessions, `Agent`, `Flock`/`Swarm` members, `DualChatConfig`) fail to deserialize and silently disappear: `ChatHistoryManager.getAllSessions` `mapNotNull`-drops them, `loadSession` returns null.
**Root cause:** The adapter `throw`s `JsonParseException("Unknown AppService: $id")` for any id not in `ProviderRegistry` (and not `LOCAL`). There is no fall-back to a tombstone/placeholder provider, so the whole containing object is unparseable.
**Reproduction:** Create a custom provider, start a chat with it, rename the provider id in Provider Setup, reopen the AI Chat hub — the prior session is gone from history.
**Proposed fix:** On an unknown id, deserialize to a synthetic "unknown/disabled" `AppService` carrying the original id (so the row still loads, shows as unavailable) instead of throwing.
**Status:** Fixed (2026-06-07) - unknown provider id deserializes to a synthetic AppService (carrying the id) instead of throwing, so the embedding object still loads

### Bug 9 — Severity: LOW — Category: serialization fragility
**Location:** AppService.kt:251-253 (`AppServiceAdapter.serialize`)
**Symptom:** `serialize(src=null)` emits `JsonPrimitive("")`, and `deserialize` of `""` would then throw (empty id is "unknown"). A null `AppService` field thus round-trips into a hard failure on the next read rather than a null.
**Root cause:** Asymmetric null handling — serialize tolerates null (writes `""`), deserialize rejects it.
**Proposed fix:** Serialize null as JSON null and let deserialize return null for JSON null (Gson skips), instead of the empty-string sentinel.
**Status:** Fixed (2026-06-07) — null AppService values now serialize as JSON null and deserialize null/blank provider ids as null instead of an empty unknown provider id

## File: ai/src/main/java/com/ai/data/TagPropagation.kt

### Bug 10 — Severity: LOW — Category: trace attribution race
**Location:** TagPropagation.kt:100-145 (`TagPropagatingExecutor.execute`)
**Symptom:** A queued OkHttp call promoted later (when a per-host slot frees) is submitted from the *previous* worker thread, so its trace/throttle tags attribute to the previous flow rather than the originating caller.
**Root cause:** Documented in the class comment (lines 106-113): tags are snapshotted at submission time, and promotion submits from a worker thread. Acceptable but real.
**Proposed fix:** Attach per-`Call.tag` at OkHttp Call construction time for race-free attribution (as the comment notes).
**Status:** Fixed (2026-06-07) — shared Retrofit/raw calls now capture trace/throttle context into an OkHttp request tag at Call construction and restore it in the first interceptor, so queued-call promotion cannot inherit the prior worker's tags.

### Bug 11 — Severity: LOW — Category: thread-local lifecycle
**Location:** TagPropagation.kt:136-139, 161-162 (`backoffPermitYielder`, `benchSignal` propagation)
**Symptom:** The captured `backoffPermitYielder` lambda and `benchSignal` AtomicBoolean are propagated onto the worker and restored in `finally`. If the same originating coroutine submits two concurrent OkHttp calls that both run on pooled worker threads, both share the *same* `benchSignal` AtomicBoolean reference; a 429 on either sets it `true`, and the batch loop can't tell which item should be requeued.
**Root cause:** A single per-attempt signal object is shared by reference across sibling calls that originate under the same context element.
**Proposed fix:** Confirm `runThrottledBatch` installs a fresh `benchSignal` per *item* (not per batch); if a coroutine can launch >1 OkHttp call under one signal, scope the signal per dispatched call.
**Status:** Fixed (2026-06-07) — confirmed `runThrottledBatch` allocates a fresh `AtomicBoolean` inside each bench item attempt and installs it only around that item body; no shared batch-level signal exists.

## File: ai/src/main/java/com/ai/data/ApiClient.kt

### Bug 12 — Severity: LOW — Category: resource lifetime
**Location:** ApiClient.kt:281-298 (`fetchUrlAsString`)
**Symptom:** On a non-2xx response the body is read implicitly via `resp.body?.string()` only on the success branch; the else branch logs and returns null but `.use{}` closes the response, so OK — but the raw GET shares the *same* `okHttpClient` and therefore the `RateLimitRetryInterceptor`/`ProviderThrottleInterceptor`. A model-list raw fetch that 429s will Thread.sleep-retry inside this synchronous `.execute()` on whatever coroutine thread called it.
**Root cause:** Reuse of the fully-interceptor-stacked client for a plain blocking GET means the blocking retry/throttle loops run on the caller's thread.
**Proposed fix:** Acceptable if always called on Dispatchers.IO; otherwise route raw fetches through a lighter client without the sleeping retry interceptors.
**Status:** Fixed (2026-06-07) — raw snapshot fetches now use a lighter client that keeps context/throttle/tracing but omits the sleeping 429/529 retry interceptors.

### Bug 13 — Severity: LOW — Category: cache key collision
**Location:** ApiClient.kt:266-275 (`getRetrofit`)
**Symptom:** Retrofit instances are cached by normalized base URL only. Two providers that share a base URL host but need different behaviour would share the same Retrofit/converter — harmless today, but a custom provider pointing at the same host as a built-in shares the cached instance.
**Root cause:** Key is the URL string, not (URL, provider config).
**Proposed fix:** Acceptable (interfaces are stateless); note only. Consider keying by URL + interface type if per-provider converters ever diverge.
**Status:** Fixed (2026-06-07) — Retrofit cache keys now include the API interface namespace as well as the normalized base URL.

## File: ai/src/main/java/com/ai/data/ApiDispatch.kt

### Bug 14 — Severity: MEDIUM — Category: chat streaming param drop
**Location:** ApiStreaming.kt:426-435 / ApiDispatch.kt `chatGemini` (GeminiGenerationConfig construction)
**Symptom:** Gemini *chat streaming* (`streamGemini`) builds `GeminiGenerationConfig` with only temperature/topP/topK/maxTokens/search/thinking — `frequencyPenalty`, `presencePenalty`, `stopSequences`, and `seed` from `ChatParameters` are silently dropped, so a Gemini chat with those set behaves differently than the non-streaming `analyzeGemini` path (ApiDispatch.kt:479-482 passes them).
**Root cause:** The streaming config omits the positional args the non-streaming path includes.
**Proposed fix:** Pass the same full argument set the non-streaming `analyzeGemini` config uses.
**Status:** Fixed (2026-06-07) - streamGemini's GeminiGenerationConfig now passes frequencyPenalty + presencePenalty (stopSequences/seed aren't on ChatParameters)

### Bug 15 — Severity: LOW — Category: host gate resolution
**Location:** ApiDispatch.kt:77-87 (`withHostGate`)
**Symptom:** The coroutine-layer throttle gate resolves the host via `java.net.URI(baseUrl).host`; if the URI has no host (relative/odd baseUrl), it returns blank and the call proceeds *ungated* at the coroutine layer, falling back only to the (thread-blocking) interceptor acquire.
**Root cause:** Blank host → `return dispatch()` with no permit.
**Proposed fix:** Fall back to `okhttp3.HttpUrl.parse(baseUrl)?.host` and, if still blank, log so a mis-configured provider's lack of throttling is visible.
**Status:** Fixed (2026-06-07) — host-gate resolution now falls back to OkHttp URL parsing and logs when no throttle host can be resolved

### Bug 16 — Severity: LOW — Category: timeout coupling
**Location:** ApiDispatch.kt:51-59 (`withApiCallTimeout`)
**Symptom:** The hard call ceiling is derived from `nonStreamingReadTimeoutSec`. For a *streaming open* of a provider that is slow to send headers but within the (longer) streaming read budget, the open can be cancelled by this shorter ceiling and surfaced as an IOException, masking a legitimate slow start.
**Root cause:** One ceiling computed from the non-streaming read timeout is used for both streaming-open and non-streaming calls (line 52).
**Proposed fix:** Use the streaming read timeout for the streaming-open call sites.
**Status:** Fixed (2026-06-07) — `withApiCallTimeout(streamingOpen = true)` now uses the streaming read budget for report/chat stream-open call sites; regular calls keep the non-streaming ceiling.

### Bug 17 — Severity: LOW — Category: Gemini content extraction
**Location:** ApiDispatch.kt:500-501 (`analyzeGemini` content pick)
**Symptom:** Content is taken from `candidates[0].content.parts[0].text` then falls back to the first non-null text across all candidates' parts, but multi-part answers (text split across parts) keep only the first part — a multi-part Gemini answer is truncated to its first part.
**Root cause:** `firstOrNull()`/`firstNotNullOfOrNull` instead of joining all `parts.text`.
**Proposed fix:** Join all `parts.mapNotNull { it.text }` like the streaming extractor `extractGeminiContent` does (ApiStreaming.kt:295-297).
**Status:** Fixed (2026-06-07) — non-streaming Gemini now joins every text part in the first candidate with text, matching the streaming extractor and avoiding truncated multi-part answers

### Bug 18 — Severity: LOW — Category: error body drain
**Location:** ApiDispatch.kt:415-418, 467-470, 510-513 (non-stream error branches)
**Symptom:** Error responses call `response.errorBody()?.string()` (Retrofit) which is fine, but the success branches read `response.body()` once; for a body that fails mid-parse there is no explicit close. Retrofit closes for typed bodies, but the mixed raw/typed code paths are inconsistent.
**Root cause:** Mixed Retrofit-typed and manual body handling across the dispatch functions.
**Proposed fix:** Audit each branch ensures the ResponseBody is consumed/closed; standardise on Retrofit-typed responses where possible.
**Status:** Fixed (2026-06-07) — audited the listed branches: they use Retrofit typed bodies on success and consume `errorBody().string()` on error; no extra raw ResponseBody lifetime remains in those paths.

### Bug 19 — Severity: LOW — Category: default max_tokens
**Location:** ApiDispatch.kt:24-25 (`defaultMaxTokens`)
**Symptom:** When a provider has no `maxTokensDefaults` rule, every call without an explicit `maxTokens` is capped at 4096 output tokens, silently truncating long answers from models with much larger output windows (the user never set a cap; they just get cut off at 4096).
**Root cause:** A fixed 4096 fallback chosen to avoid OpenRouter balance-gating, applied uniformly.
**Proposed fix:** Derive the fallback from the model's known output-token limit (already fetched into capabilities) instead of a flat 4096, or only apply the floor for balance-gating providers.
**Status:** Fixed — default max-token selection now keeps provider-specific rules first, then uses the known models.dev output-token limit when present, before falling back to the conservative 4096 cap.

## File: ai/src/main/java/com/ai/data/ApiStreaming.kt

### Bug 20 — Severity: MEDIUM — Category: silent truncation
**Location:** ApiStreaming.kt:149-151 (`parseSseStream` terminator guard)
**Symptom:** A stream that delivers *some* content then drops the TCP connection without a terminator is treated as a clean, complete answer — the user gets a silently-truncated response with no error and no retry.
**Root cause:** The truncation guard only throws when `sawAnyData && chunkCount == 0`. Once any content chunk is emitted, a mid-stream socket close is indistinguishable from a clean close for the many providers that send no `[DONE]`.
**Proposed fix:** When a `finish_reason`/terminator was expected but absent AND the upstream advertised `Content-Length`/`finish`, surface a "possibly truncated" signal the caller can flag; or treat a non-terminated stream from a known-terminator provider as an error.
**Status:** Fixed (2026-06-07) — parseSseStream now supports requireTerminator and enables it for known-final-event Anthropic, Gemini, and Responses API streaming paths while keeping tolerant OpenAI-compatible Chat Completions EOF behavior

### Bug 21 — Severity: LOW — Category: charset assumption
**Location:** ApiStreaming.kt:65 (`InputStreamReader(body.byteStream(), Charsets.UTF_8)`)
**Symptom:** Forcing UTF-8 is correct for SSE, but a provider that genuinely returns a different charset on a chunked-JSON (non-SSE) stream that lands here would be mis-decoded.
**Root cause:** Charset is hardcoded; the isStreaming branch also catches `Transfer-Encoding: chunked` JSON.
**Proposed fix:** Honour an explicit non-UTF-8 `Content-Type` charset for the chunked-JSON case; keep the UTF-8 default only when the server omits it.
**Status:** Fixed (2026-06-07) — SSE still forces UTF-8, while non-SSE chunked responses now honor an explicit response charset with UTF-8 fallback

### Bug 22 — Severity: LOW — Category: usage merge correctness
**Location:** ApiStreaming.kt:223-233 (`mergeUsage`)
**Symptom:** Field-wise `maxOf` is correct for Anthropic's split and Gemini's cumulative chunks, but for an OpenAI-compatible provider that emits *multiple* partial usage chunks where a later chunk legitimately reports a *smaller* corrected count, `maxOf` keeps the stale larger value, over-counting tokens/cost.
**Root cause:** `maxOf` assumes monotonic non-decreasing usage across events.
**Proposed fix:** For the OpenAI final-chunk case, take the last complete `usage` rather than field-wise max; reserve max-merge for the Anthropic/Gemini split/cumulative shapes.
**Status:** Fixed — streaming collection now selects its usage merge policy per API shape: OpenAI-compatible Chat/Responses streams keep the last complete usage event, while Anthropic and Gemini keep field-wise max merging for split/cumulative usage.

### Bug 23 — Severity: LOW — Category: reasoning fallback ordering
**Location:** ApiStreaming.kt:378-380 (`OpenAiContentExtractor` + `reasoningFallback`)
**Symptom:** When a provider streams reasoning *interleaved* with content but content is empty until the end, `reasoningFallback()` is emitted only after the content stream completes; if the stream is cancelled mid-flight, buffered reasoning is lost even though it was the only "answer" produced.
**Root cause:** Reasoning is buffered and only flushed post-stream; cancellation skips the post-stream emit.
**Proposed fix:** On cancellation/teardown, still flush `reasoningFallback()` if no content was seen.
**Status:** Fixed — OpenAI chat streaming now flushes the buffered reasoning fallback once from the stream teardown path when no content chunk was seen, including active exceptional cleanup before rethrow.

## File: ai/src/main/java/com/ai/data/TracingInterceptor.kt

### Bug 24 — Severity: LOW — Category: trace memory / disk
**Location:** TracingInterceptor.kt:46-50 (request body capture)
**Symptom:** The request body is captured in full (`buffer.readUtf8()`) with no size cap, unlike the response body which is capped at 8 MiB (lines 37, 144-145). A vision request with a multi-MB base64 image produces a multi-MB trace file on disk that also enters the backup zip.
**Root cause:** No cap on the request side.
**Proposed fix:** Apply the same `BODY_CAP_BYTES` truncation to the captured request body.
**Status:** Fixed (2026-06-07) — request-body trace capture now writes through a capped sink and appends the same 8 MiB truncation marker used for responses

### Bug 25 — Severity: LOW — Category: redaction false-positive
**Location:** TracingInterceptor.kt:300-301 (`BODY_KEY_FIELD_REGEX`)
**Symptom:** The body redaction regex matches any JSON field named `key`/`token`/`secret`/etc. anywhere in the request body, so a legitimate user prompt that contains JSON like `{"token": "..."}` (e.g. asking the model about a code snippet) gets its content silently redacted in the trace, making the trace useless for debugging that call.
**Root cause:** The regex is content-agnostic and matches inside the prompt text, not only auth fields.
**Proposed fix:** Restrict redaction to top-level request fields known to carry secrets, not arbitrary nested occurrences inside user content.
**Status:** Fixed — trace body redaction now parses JSON and redacts only known top-level secret fields, leaving nested prompt content and JSON examples untouched.

### Bug 26 — Severity: LOW — Category: streaming trace correctness
**Location:** TracingInterceptor.kt:204-223 (`teedSource` capture window)
**Symptom:** `sink.copyTo(captured, sink.size - n, toCopy)` assumes the `n` newly-read bytes sit at the tail of `sink` at offset `sink.size - n`. If a downstream `ForwardingSource` in the chain consumed from `sink` between reads (it doesn't today), the offset math would copy the wrong window into the trace.
**Root cause:** Offset arithmetic depends on `sink` being append-only across reads.
**Proposed fix:** Capture into a dedicated buffer passed to `super.read` then copy forward, decoupling from `sink`'s state.
**Status:** Fixed — the streaming trace tee now reads upstream bytes into a private chunk buffer, copies from that buffer into the capture buffer, then forwards the bytes to the caller sink, so capture no longer depends on caller sink offset assumptions.

## File: ai/src/main/java/com/ai/data/ApiTracer.kt

### Bug 27 — Severity: LOW — Category: cache/disk desync
**Location:** ApiTracer.kt:165-192 (`saveTrace` cache mutation)
**Symptom:** When `cachedTraceFiles` is non-null and a save lands, the entry is appended/replaced and re-sorted. But the disk write happened *outside* the lock (line 149-150) while the cache mutation is inside the lock; two concurrent saves to *new* filenames both pass the disk write, then serialize on the cache append — fine — but a concurrent `clearTraces()` between the disk write and the cache append re-adds the just-cleared trace's cache entry, leaving a cache entry for a file `clearTraces` already deleted.
**Root cause:** The disk write and cache update are intentionally not atomic; `clearTraces` sets `cachedTraceFiles = emptyList()` but a racing `saveTrace` then does `emptyList() + info`.
**Proposed fix:** Have `saveTrace` re-check that the file still exists before re-adding, or hold the lock across both steps for the append case.
**Status:** Fixed in `ApiTracer.kt` by re-checking the just-written trace file under the cache lock before appending it to `cachedTraceFiles`.

### Bug 28 — Severity: LOW — Category: filename uniqueness
**Location:** ApiTracer.kt:38, 129-138 (`fileSequence` + filename)
**Symptom:** The per-process random-seeded sequence avoids cross-restart collisions, but `incrementAndGet().toString(36)` can wrap past `Long.MAX` only theoretically; more practically, two processes (unlikely on Android but possible with a restarted process reading the same dir) could pick overlapping random ranges and collide on `host_ts_seq.json`, overwriting a prior trace.
**Root cause:** In-memory sequence + random offset is collision-*unlikely*, not collision-*free*.
**Proposed fix:** Append a short UUID segment (the atomic writer already uses one for staging) to fully eliminate collisions.
**Status:** Fixed in `ApiTracer.kt` by appending an 8-character UUID segment to generated trace filenames.

### Bug 29 — Severity: LOW — Category: unbounded growth
**Location:** ApiTracer.kt (trace dir) + BackupManager inclusion
**Symptom:** Trace files accumulate in `filesDir/trace` with no automatic cap (only manual `deleteTracesOlderThan` from a sweep); a long tracing session with 50-pair fan-outs writes thousands of files that all roll into the backup zip.
**Root cause:** No size/count ceiling on the trace directory.
**Proposed fix:** Cap trace count/total bytes with an LRU eviction at save time.
**Status:** Fixed in `ApiTracer.kt` by pruning oldest trace files after saves once the trace dir exceeds 2,000 files or 50 MB, while protecting the just-written trace.

## File: ai/src/main/java/com/ai/data/RateLimitRetry.kt

### Bug 30 — Severity: MEDIUM — Category: false billing-bench
**Location:** RateLimitRetry.kt:366-384 (`creditOrSpendingLimitExhausted`)
**Symptom:** A transient rate-limit 429 whose body happens to contain a phrase like `"billing details"` or `"exceeded your current quota"` (some providers use "quota" wording for *rate* limits) is mis-classified as out-of-credits and the model is benched for 6 hours, taking it out of every picker even though a quick retry would have cleared it.
**Root cause:** Substring phrase matching on the error body (line 378-383) overlaps with rate-limit wording; `"exceeded your current quota"` in particular is OpenAI's `insufficient_quota` message but is also used loosely elsewhere.
**Proposed fix:** Prefer the structured `type`/`code` check only (`insufficient_quota`); drop the loose phrase fallback or require it to co-occur with an explicit billing error type.
**Status:** Fixed (2026-06-07) - dropped the loose 'billing details' / 'exceeded your current quota' needles (overlap rate-limit wording); structured insufficient_quota + unambiguous billing phrases only

### Bug 31 — Severity: LOW — Category: model resolution on bench
**Location:** RateLimitRetry.kt:292-304 (`modelForRequest`)
**Symptom:** For a non-Gemini request, the model id is recovered by re-serializing the request body (`body.writeTo(buf)`). A one-shot/streaming `RequestBody` could throw or already be consumed, so the bench silently skips (`providerId != null && model not blank` fails) and the model is never benched despite a long-retry 429.
**Root cause:** Re-reading a possibly-non-repeatable RequestBody after it was sent.
**Proposed fix:** Carry the model id via a per-call tag (ApiTracer already has `currentModel`) instead of re-parsing the body.
**Status:** Fixed in `RateLimitRetry.kt` by resolving bench model ids from `ApiTracer.currentModel` before falling back to URL/body inspection.

### Bug 32 — Severity: LOW — Category: retry/dispatcher slot occupancy
**Location:** RateLimitRetry.kt:146-180 (429 retry loop)
**Symptom:** When no `backoffPermitYielder` is registered (chat / single calls), `ProviderThrottle.backoffSleep` falls to `Thread.sleep` while still holding the OkHttp dispatcher per-host slot; with `maxRetries` raised by the user and a long Retry-After, the slot is pinned for the full sleep.
**Root cause:** In-place sleep on the OkHttp worker thread for non-batch flows (documented as bounded, but user-tunable retry count + Retry-After can extend it to 5 min via the clamp).
**Proposed fix:** Even for non-batch flows, perform the wait at the coroutine layer (release the throttle/dispatcher slot during the sleep) as the batch path does.
**Status:** Fixed — non-batch 429s no longer enter the interceptor sleep loop when no backoff yielder is installed; they return immediately so the repository-level coroutine retry can wait without pinning an OkHttp worker.

### Bug 33 — Severity: LOW — Category: Cohere bench host fallback
**Location:** RateLimitRetry.kt:97-108
**Symptom:** When `resolvedProviderId` is null but the body matches the Cohere trial-cap text, it benches under literal `"Cohere"`. If the user renamed the Cohere provider id, the bench key won't match the model picker's `providerId:model` key and the picker won't show the cooldown.
**Root cause:** Hardcoded `"Cohere"` provider id fallback.
**Proposed fix:** Resolve the provider by API format / host family rather than a literal id.
**Status:** Fixed in `RateLimitRetry.kt` by resolving Cohere trial-cap benches from registered Cohere-family hosts instead of the literal provider id.

## File: ai/src/main/java/com/ai/data/OverloadedRetry.kt

### Bug 34 — Severity: LOW — Category: trace filename for bench
**Location:** OverloadedRetry.kt:29-33
**Symptom:** Unlike `RateLimitRetryInterceptor` (which clears `ApiTracer.lastTraceFilename` before `chain.proceed`), `OverloadedRetryInterceptor` does not, so a 529 short-bench has no defined trace-filename reset; the bench may reference a stale `lastTraceFilename` from a previous call on the pooled thread.
**Root cause:** Missing the `ApiTracer.lastTraceFilename.set(null)` reset that the 429 sibling performs.
**Proposed fix:** Mirror the 429 interceptor's reset at the top of `intercept`. (529 path doesn't pass a trace file to markShortBench today, but should for consistency.)
**Status:** Fixed in `OverloadedRetry.kt` by clearing `ApiTracer.lastTraceFilename` before the 529 interceptor proceeds the request.

## File: ai/src/main/java/com/ai/data/ProviderThrottling.kt

### Bug 35 — Severity: LOW — Category: rate-window slot leak on concurrency block
**Location:** ProviderThrottling.kt:213-245 (`acquire`)
**Symptom:** A timestamp is added to the per-minute window (line 222) before the concurrency semaphore is acquired (line 241). If the concurrency acquire blocks for a long time, the window slot is "spent" for that minute even though the call hasn't gone out, slightly over-throttling.
**Root cause:** Documented as the safe direction (over-throttle), but it means the effective rate can dip below the configured cap under concurrency pressure.
**Proposed fix:** Acceptable; if exactness is wanted, add the window timestamp only once both gates are passed.
**Status:** Fixed — blocking host acquire now takes the concurrency semaphore first, then records the per-minute window timestamp after that gate has passed; interrupted rate waits release the semaphore before propagating.

### Bug 36 — Severity: LOW — Category: cap change vs in-flight
**Location:** ProviderThrottling.kt:339-342 (`resetForNewLimits`)
**Symptom:** Clearing the `sems`/`windows` maps while calls hold permits on the old (now-unreferenced) semaphores means the host can briefly run at up to old-cap + new-cap concurrency.
**Root cause:** Documented; semaphore swap doesn't drain in-flight holders.
**Proposed fix:** Acceptable for a user-driven setting tweak; note only.
**Status:** Closed — note-only item; `resetForNewLimits` already documents that in-flight calls release old semaphores correctly and that a brief old-cap plus new-cap overlap is acceptable for user-driven setting changes.

### Bug 37 — Severity: LOW — Category: interrupt handling
**Location:** ProviderThrottling.kt:231-234, 109-121 (`Thread.sleep` in rate gate / `backoffSleep`)
**Symptom:** `acquire`'s rate-limit `Thread.sleep` re-throws `InterruptedException` after re-setting the interrupt flag, but the caller (OkHttp interceptor) may translate it into a generic IOException, losing the "cancelled" semantics and potentially triggering the outer retry on a cancellation.
**Root cause:** Interrupt surfaces as a thrown exception inside the interceptor stack.
**Proposed fix:** Ensure the interceptor maps `InterruptedException` to a cancellation, not a retryable failure.
**Status:** Fixed — provider throttle acquire and 429/529 backoff sleeps now translate `InterruptedException` into `CancellationException`, preserving cancellation semantics for outer coroutine retry handling.

## File: ai/src/main/java/com/ai/data/ModelCooldownStore.kt

### Bug 38 — Severity: LOW — Category: unbounded session map
**Location:** ModelCooldownStore.kt:69-93 (`shortBenchMap` / `_shortBenches`)
**Symptom:** `shortBenchMap` is never pruned of expired entries; across a long session with many transient 429/529s it grows unboundedly, and `_shortBenches` snapshots (published to the dashboard) include long-expired entries forever.
**Root cause:** No expiry sweep for the short-bench tier (unlike `cooldownMap`'s `pruneExpired`).
**Proposed fix:** Drop expired keys on `markShortBench`/`isShortBenched`, or run a periodic prune.
**Status:** Fixed in `ModelCooldownStore.kt` by pruning expired short benches on reads/writes and publishing cleaned short-bench snapshots.

### Bug 39 — Severity: LOW — Category: SharedPreferences write on network thread
**Location:** ModelCooldownStore.kt:119-130, 214-221 (`markUnavailable` → `persist`)
**Symptom:** `markUnavailable` is invoked from the OkHttp interceptor worker thread and calls `persist()` which does `gson.toJson(...)` of two maps and a SharedPreferences `.apply()`. The serialization runs synchronously on the network worker thread on every long-429.
**Root cause:** Synchronous serialize-and-write on the interceptor thread.
**Proposed fix:** Acceptable (`apply` is async, maps are small); if cooldown maps grow, move persistence off-thread.
**Status:** Fixed — cooldown persistence now snapshots the maps and performs JSON serialization plus SharedPreferences writes on a dedicated background executor instead of the caller's OkHttp worker.

### Bug 40 — Severity: LOW — Category: import overwrite
**Location:** ModelCooldownStore.kt:181-186 (`importMerge`)
**Symptom:** `importMerge` does `cooldownMap.putAll(incoming)` — imported cooldowns unconditionally overwrite an existing-but-longer local bench for the same key, potentially un-benching a model earlier than the device's own observation.
**Root cause:** putAll lets the incoming value win even when the local value is later.
**Proposed fix:** Merge by `max(existing, incoming)` per key.
**Status:** Fixed in `ModelCooldownStore.kt` by merging imported cooldowns per key and keeping the later expiry.

## File: ai/src/main/java/com/ai/data/PricingCache.kt

### Bug 41 — Severity: LOW — Category: stale documentation in critical path
**Location:** PricingCache.kt:14-21 (class doc) vs 381-389 (`getPricing`)
**Symptom:** The class-level KDoc states "LITELLM sits ahead of OVERRIDE so the curated BerriAI/litellm prices win over stale manual entries" — the exact opposite of the actual precedence (OVERRIDE is now checked first, line 389; confirmed by CLAUDE.md). A maintainer trusting the doc could "fix" the precedence the wrong way and silently break user overrides.
**Root cause:** Doc not updated when OVERRIDE was moved ahead of the curated tiers.
**Proposed fix:** Rewrite the class doc to match the implemented order (provider self-report → OVERRIDE → curated tiers → OpenRouter fallback → Helicone → DEFAULT).
**Status:** Fixed in `PricingCache.kt` by updating the class KDoc and nearby precedence comment to match the implemented manual-override-first ordering.

### Bug 42 — Severity: LOW — Category: cold-window cost skew
**Location:** PricingCache.kt:369-371 (`getPricing` main-thread short-circuit) + ApiUsageRates.costWithin
**Symptom:** During the pre-preload window, `getPricing` returns `DEFAULT_PRICING` on the main thread; the Live Dashboard's `costWithin` (ApiUsageRates.kt:61-81) then computes spend from DEFAULT rates, briefly showing a wrong cost figure until the preload finishes.
**Root cause:** Documented cold-window behaviour bleeds into a numeric dashboard, not just a UI placeholder.
**Proposed fix:** Have `costWithin` skip/withhold the cost figure until `preloadCompleted`, rather than pricing at DEFAULT.
**Status:** Fixed in `ApiUsageRates.kt`, `PricingCache.kt`, and `AiDashboardScreen.kt` by returning null before pricing preload completes and rendering pending spend as an ellipsis.

### Bug 43 — Severity: LOW — Category: precedence parity drift
**Location:** PricingCache.kt:408-429 (`getPricingWithoutOverride`) vs 437-455 (`lookupPricing`)
**Symptom:** Three near-identical lookup ladders (`getPricing`, `getPricingWithoutOverride`, `lookupPricing`) must be kept byte-for-byte in sync; `getPricingWithoutOverride` intentionally omits the OVERRIDE step. A future tier insertion that updates only one or two of the three reintroduces the picker-vs-billed disagreement the comments warn about.
**Root cause:** Duplicated precedence logic across three functions.
**Proposed fix:** Factor the tier ladder into one private function parameterised by "include override?".
**Status:** Fixed — live pricing, without-override pricing, and in-memory pricing now share one tier-ladder helper parameterized by `includeOverride`, so future tier changes cannot drift across call sites.

### Bug 44 — Severity: LOW — Category: `-latest` alias resolution
**Location:** PricingCache.kt:478-512 (`findLatestAliasKey`)
**Symptom:** Resolving `-latest` to the lexically-max dated sibling assumes date tokens sort lexically in chronological order. Mixed formats within one prefix bucket (`20241022` vs `2024-11-20` vs `2411`) can sort wrong (e.g. `2411` > `2024-11-20` lexically), picking an older snapshot's price for a `-latest` alias.
**Root cause:** Lexical max over heterogeneous date formats bucketed only by prefix, not by format.
**Proposed fix:** Normalise candidate date tokens to a comparable canonical form before taking the max.
**Status:** Open (unconfirmed; same-provider buckets usually use one format)

## File: ai/src/main/java/com/ai/data/EmbeddingsStore.kt

### Bug 45 — Severity: LOW — Category: type mismatch silent-zero
**Location:** EmbeddingsStore.kt:100-117 (`cosine(List<Double>)`) and consumers
**Symptom:** `cosine` returns 0.0 on a dim mismatch (with a warn log). A caller comparing a remote (List<Double>) cache vector against a re-embedded vector of a different dimension gets "no similarity" rather than an error — RAG retrieval silently returns nothing when the embedder changed but stale vectors remain on disk.
**Root cause:** Dim mismatch maps to 0.0, indistinguishable from "genuinely orthogonal".
**Proposed fix:** Already mitigated for KnowledgeService (it skips mismatched chunks with a warn). For any other consumer, return a sentinel/throw so the swap is surfaced, not silently zeroed.
**Status:** Fixed in `EmbeddingsStore.kt` by returning `NaN` for `List<Double>` dimension mismatches and updating semantic-search/local-rerank callers to skip or report invalid scores.

### Bug 46 — Severity: LOW — Category: cache read robustness
**Location:** EmbeddingsStore.kt:49-53 (`get`)
**Symptom:** A truncated/corrupt embedding JSON returns `null` (cache miss) silently — fine — but `put` (lines 59-71) does not verify the vector is non-empty, so a provider that returned an empty embedding caches `[]`, and the next `get` returns an empty list that `cosine` then treats as 0.0 for every query against that doc.
**Root cause:** No validation that the stored vector is non-empty.
**Proposed fix:** Refuse to `put` an empty vector; log instead.
**Status:** Fixed in `EmbeddingsStore.kt` by refusing empty vectors on write and treating any existing empty cached vector as a logged cache miss on read.

## File: ai/src/main/java/com/ai/data/local/LocalEmbedder.kt

### Bug 47 — Severity: LOW — Category: download durability
**Location:** LocalEmbedder.kt:104-131 (`download`)
**Symptom:** The model is streamed to a `.part` file via `tmp.outputStream().use{}` with no `fd.sync()` before the `ATOMIC_MOVE`. Unlike `writeTextAtomic`, a power loss after the move but before the page cache flush can surface a zero-length/partial `.tflite` that the runtime then refuses to load (or crashes on).
**Root cause:** Missing fsync of the downloaded temp file before the atomic move.
**Proposed fix:** fsync the FileOutputStream before `Files.move`, mirroring `writeTextAtomic`.
**Status:** Fixed in `LocalEmbedder.kt` by fsyncing the downloaded `.part` file before moving it into place.

### Bug 48 — Severity: LOW — Category: concurrent state clobber
**Location:** LocalEmbedder.kt:33-37, 233-258 (`embedding` @Volatile + `finally { embedding = null }`)
**Symptom:** `currentlyEmbedding` is a single @Volatile var. Two concurrent `embed` calls on *different* models (allowed — serialization is per-embedder, not global) clobber each other's `currentlyEmbedding`, and the first to finish sets `embedding = null` while the other is still running, so the dashboard shows "idle" mid-embed.
**Root cause:** Single global var modelling per-model live state.
**Proposed fix:** Track a set/count of in-flight model names rather than one var.
**Status:** Fixed in `LocalEmbedder.kt` by tracking per-model in-flight counts and deriving the dashboard summary from active model names.

### Bug 49 — Severity: LOW — Category: dangling temp on failure
**Location:** LocalEmbedder.kt:134-139 (`download` catch)
**Symptom:** On failure `tmp.delete()` is called unconditionally without existence check; harmless, but the partial `.part` may already have been partly moved/locked, leaving a stale `<name>.tflite.part` that `availableModels` ignores but that wastes space and is backed-up-excluded only because it's under `local_models`.
**Root cause:** No guaranteed cleanup of `.part` artifacts.
**Proposed fix:** Sweep `*.part` on startup of the Local models screen.
**Status:** Fixed in `LocalEmbedder.kt` by sweeping stale `.part` downloads whenever local LiteRT models are enumerated.

## File: ai/src/main/java/com/ai/data/local/LocalLlm.kt

### Bug 50 — Severity: LOW — Category: concurrent state clobber
**Location:** LocalLlm.kt:34-37, 169-189 (`generating` @Volatile)
**Symptom:** Same class as Bug 48 — two concurrent `generate` calls on different `.task` models clobber `currentlyGenerating` and the first finish nulls it while the other runs.
**Root cause:** Single var for per-model live state.
**Proposed fix:** Track in-flight model names as a set.
**Status:** Fixed in `LocalLlm.kt` by tracking per-model in-flight generation counts and deriving the dashboard summary from active model names.

### Bug 51 — Severity: LOW — Category: locale-sensitive formatting
**Location:** LocalLlm.kt:180 (`"%.1f".format(rate)`)
**Symptom:** The chars/sec rate in the debug log uses the default locale; on the user's nl-NL device it renders `1,5` instead of `1.5`. Cosmetic (log only) but inconsistent with the repo's Locale.US discipline.
**Root cause:** `String.format` default locale.
**Proposed fix:** Use `String.format(Locale.US, "%.1f", rate)`.
**Status:** Fixed (2026-06-07) — local LLM chars/sec debug log now formats with `Locale.US`

## File: ai/src/main/java/com/ai/data/ReportStorage.kt

### Bug 52 — Severity: MEDIUM — Category: lost update on full-report save
**Location:** ReportStorage.kt:98-106 (`createReport` → `saveReport`) and any caller that holds a `Report` then re-saves it
**Symptom:** Mutation helpers do load→mutate→save under the lock (safe), but a caller that obtains a `Report` via `getReport`, mutates a copy, and persists it via a public save path outside the lock-wrapped helpers would clobber concurrent field updates written by the orchestrator/metadata flows in between.
**Root cause:** The atomicity guarantee depends on *every* writer going through the lock-wrapped load→mutate→save helpers; a full-object write based on a stale snapshot loses interleaved updates.
**Proposed fix:** Ensure no caller persists a whole `Report` snapshot; require field-scoped mutators. Audit `createReport`'s `saveReport(report)` is only used at creation (it is) and reject external full-report writes.
**Status:** Fixed (2026-06-07) — full-report writes are now private+lock-asserted; the only public full-object path is `persistNewReport`, which refuses to overwrite an existing report and is used only by new-report/import flows

### Bug 53 — Severity: MEDIUM — Category: additive cost double-count on regen
**Location:** ReportStorage.kt:197-199+ (`updateAgentStatus` additive cost/token writes)
**Symptom:** Cost and token counts are *added* onto the existing values on every status update. If a single dispatch results in two `markAgentSuccess` calls for the same agent (e.g. a retry path that both the streaming collector and a fallback non-streaming path report), the agent's cost is double-counted.
**Root cause:** Additive accumulation with no per-attempt idempotency key; correctness relies on exactly-once success reporting per dispatch.
**Proposed fix:** Key cost additions by an attempt/trace id and dedupe, or have the dispatcher reset-then-add per attempt.
**Status:** Fixed (2026-06-07) — primary SUCCESS cost/token additions are now idempotent for the same trace file, preserving additive regenerate costs across distinct traces while deduping duplicate success bookkeeping for one API attempt

### Bug 54 — Severity: LOW — Category: serialized cross-report writes
**Location:** ReportStorage.kt:44, all mutators (`lock`)
**Symptom:** A single global `ReentrantLock` serializes every report write across *all* reports. During a many-report regenerate batch with frequent per-agent status writes, writers to unrelated reports queue behind each other, adding latency.
**Root cause:** One global lock instead of per-report locks.
**Proposed fix:** Stripe the lock by reportId.
**Status:** Fixed — backup now logs unreadable/skipped files, aggregates `skippedFiles` in `BackupSummary`, and Backup/Restore surfaces a warning when a backup is partial.

### Bug 55 — Severity: LOW — Category: load failure → silent skip
**Location:** ReportStorage.kt:451-458 (`loadAllReports`)
**Symptom:** A report file that throws during parse (e.g. the Bug-1 null-String NPE happens *inside* `normalizeReport`/Gson) is `mapNotNull`-dropped with an error log; the report vanishes from History with no user-visible signal.
**Root cause:** Per-file catch drops the whole report silently.
**Proposed fix:** Surface a "N reports failed to load" banner so corruption isn't invisible.
**Status:** Fixed — `ReportStorage.loadAllReports` now records per-file load failures, and History shows a visible banner when any report file was dropped from the list.

### Bug 56 — Severity: LOW — Category: cost recompute scope
**Location:** ReportStorage.kt:122-150 (`computeReportTotalCost`)
**Symptom:** The total deliberately excludes `costsFromDeletedItems` and includes only specific `iconCalls` types (`TITLE_ALT_TYPES`, `note/title`). A future secondary cost category with no structured home and no matching `iconCalls.type` would be silently omitted from the report total.
**Root cause:** Allow-list of cost categories; new categories must be added here or they're dropped.
**Proposed fix:** Compute the total from the append-only `apiCallCosts` ledger (which is intended to be complete) rather than re-summing per-field categories.
**Status:** Fixed — report total recomputation now uses the complete API-cost ledger when available, with the old structured-field sum retained only as a legacy fallback before ledger reconciliation.

## File: ai/src/main/java/com/ai/data/ReportModels.kt

### Bug 57 — Severity: MEDIUM — Category: Gson null trap on core non-null fields
**Location:** ReportModels.kt:17-114 (`ReportAgent`), 213-487 (`Report`)
**Symptom:** `ReportAgent.provider`/`model`/`agentId`/`agentName` and `Report.title`/`prompt`/`id` are non-null `String`. If any is absent in a stored file (corruption, partial write that slipped past atomic, or a future field rename), Gson leaves it `null`; `normalizeReport` only re-asserts collection defaults (ReportStorage.kt:464-496), so the null core String NPEs at the first non-collection access far from the loader.
**Root cause:** Same systemic Gson-Unsafe issue as Bug 1, applied to the report graph's core identity fields.
**Proposed fix:** Validate core non-null fields in `normalizeReport` and reject (return null) a report missing them, instead of letting a null-bearing object escape.
**Status:** Fixed (2026-06-07) - normalizeReport rejects (returns null) a report with a null id, defaults null title/prompt, and extends the agent coercion to agentId/agentName

## File: ai/src/main/java/com/ai/data/SecondaryResult.kt

### Bug 58 — Severity: MEDIUM — Category: non-atomic read-modify-write lost update
**Location:** SecondaryResult.kt:207-238 (`updateChatMessages`, `updateContent`)
**Symptom:** Both do `get()` (reads + releases lock) then `save()` (re-acquires lock, full overwrite). A concurrent `bumpFanOutIconCost`/`bumpResultInputOutputCost`/`setFanOutIconAndTier` landing between the get and the save is silently overwritten — e.g. applying an alternate fan-out response (Apply) while the icon chain bumps cost loses the cost bump.
**Root cause:** `get()`/`save()` are separate lock acquisitions; `save()` writes the whole row from the stale snapshot, unlike the bump methods which read+write under one lock.
**Reproduction:** During a regenerate batch, trigger an Apply on a fan-out pair while its icon-chain cost bump is in flight; the row's icon cost reverts.
**Proposed fix:** Add a lock-wrapped `update(reportId, resultId) { mutate }` RMW and route `updateContent`/`updateChatMessages` through it (mirror `RegenerateBatchStorage.update`).
**Status:** Fixed (2026-06-07) — `updateChatMessages`, `updateContent`, and the same-shaped model-switch update now use a single locked `updateResult` read-modify-write, so concurrent cost/icon bumps are preserved

### Bug 59 — Severity: LOW — Category: token fidelity loss on accumulation
**Location:** SecondaryResult.kt:303-325 (`mergeCostFromDisk`)
**Symptom:** On a Regenerate re-dispatch, the merged `TokenUsage` is rebuilt as `TokenUsage(inputTokens, outputTokens)` only — `cachedInputTokens`, `cacheCreationTokens`, and `reasoningTokens` are dropped, so the displayed token breakdown for an accumulated secondary loses its cache/reasoning components.
**Root cause:** The merge constructs a 2-field TokenUsage instead of summing all buckets.
**Proposed fix:** Sum all `TokenUsage` buckets when merging prior + new.
**Status:** Fixed (2026-06-07) — secondary regenerate cost merging now sums every TokenUsage bucket, including cached, cache-creation, reasoning, and apiCost values

### Bug 60 — Severity: LOW — Category: list cache mtime granularity
**Location:** SecondaryResult.kt:34-35, 159-180 (`CachedEntry` mtime+length check)
**Symptom:** The list cache validates entries by `(mtime, length)`. A write that produces the *same* byte length within the same filesystem-second and is *not* routed through the invalidation (any future writer that forgets `listCache[...].remove`) would serve a stale parsed row.
**Root cause:** Correctness depends on every writer invalidating the cache entry; the mtime+length check alone can't catch same-second same-length overwrites.
**Proposed fix:** Add a per-file content hash or monotonic version to the cache key, decoupling correctness from every writer remembering to invalidate.
**Status:** Fixed — secondary list-cache entries now include a CRC32 content hash, so same-second same-length rewrites are detected even if a future writer misses explicit invalidation.

### Bug 61 — Severity: LOW — Category: save guarded by report existence (TOCTOU)
**Location:** SecondaryResult.kt:89-127 (`save`)
**Symptom:** `save` checks `ReportStorage.reportExists` twice (before and inside the lock) but `ReportStorage` uses a *different* lock, so a `deleteReport` could complete between the inner check and `writeTextAtomic`, recreating the secondary directory + file under a just-deleted report (orphaned data).
**Root cause:** Cross-object check-then-act across two independent locks.
**Proposed fix:** Have `deleteReport` also remove the secondary dir under a shared ordering, or recheck existence immediately after write and clean up.
**Status:** Fixed — `SecondaryResultStorage.save` now rechecks report existence after a successful write, deletes any late orphan file and empty report directory, and invalidates the cache entry before returning.

## File: ai/src/main/java/com/ai/data/ChatHistoryManager.kt

### Bug 62 — Severity: LOW — Category: cache visibility vs provider removal
**Location:** ChatHistoryManager.kt:88-101 (`getAllSessions`)
**Symptom:** A session whose provider id was removed/renamed throws in `AppServiceAdapter` (Bug 8) and is `mapNotNull`-dropped; the dropped session is then cached in `cachedSessions`, so even after the provider is restored the session stays hidden until the cache is invalidated.
**Root cause:** The failure is cached as "not present" with no re-attempt.
**Proposed fix:** Don't cache a list built while any file failed to parse, or invalidate on provider-registry changes.
**Status:** Fixed in `ChatHistoryManager.kt` by skipping session/header cache population whenever any chat file fails to parse.

## File: ai/src/main/java/com/ai/data/ModelListCache.kt

### Bug 63 — Severity: LOW — Category: id sanitisation collision
**Location:** ModelListCache.kt:34-38 (`safeId`)
**Symptom:** `safeId` maps any non-`[A-Za-z0-9._-]` char to `_`, so two distinct provider ids that differ only in a stripped char (e.g. `My API` vs `My/API`) collide on the same cache file, mixing model lists.
**Root cause:** Lossy sanitisation without a disambiguating hash.
**Proposed fix:** Append a short hash of the original id to the sanitised filename.
**Status:** Fixed in `ModelListCache.kt` by appending an 8-hex SHA-256 suffix to sanitized provider ids, with legacy filename fallback for reads/deletes.

## File: ai/src/main/java/com/ai/data/BackupManager.kt

### Bug 64 — Severity: MEDIUM — Category: restore data loss on structurally-valid empty backup
**Location:** BackupManager.kt:208-261 (`restore`)
**Symptom:** A backup zip that contains a valid `manifest.json` (version 1) but *no* `files/` or `prefs/` entries (the documented historical "0 files" backup bug, addDirectoryRecursive symlink regression) passes the version check, stages 0 entries, applies 0 prefs, then `clearFilesDirForRestore` wipes the device's reports/chats/KBs and writes nothing back.
**Root cause:** Validate-then-write protects against mid-stream corruption but there is no sanity floor on the staged payload before the destructive wipe.
**Reproduction:** Restore a backup produced by a build with the symlink-skip regression (manifest + prefs only, 0 file entries) — filesDir is wiped and nothing restored.
**Proposed fix:** Refuse to proceed past the wipe when the staged set has zero `files/` entries (or fewer than the manifest implies); require a minimum payload sanity check.
**Status:** Fixed (2026-06-07) — restore refuses (before any wipe/prefs apply) when the staged set has zero files/ entries

### Bug 65 — Severity: LOW — Category: silent partial backup
**Location:** BackupManager.kt:576-582 (`addDirectoryRecursive` per-file catch)
**Symptom:** A file that can't be read (locked, transient permission, concurrent atomic rewrite) is silently skipped (`catch (_: Exception) {}`) with no warning and no effect on the `written` count semantics — the user gets a backup that is silently missing a report/chat.
**Root cause:** Blanket swallow with no log/aggregate of skipped files.
**Proposed fix:** Log each skipped file and surface a "N files could not be backed up" count to the caller.
**Status:** Fixed — backup now logs unreadable/skipped files, aggregates `skippedFiles` in `BackupSummary`, and Backup/Restore surfaces a warning when a backup is partial.

### Bug 66 — Severity: LOW — Category: plaintext secrets in backup
**Location:** BackupManager.kt:106-109 (`PREFS_TO_BACKUP` includes MAIN_PREFS) + Agent.apiKey in settings
**Symptom:** The backup zip stores `MAIN_PREFS` (which holds API keys) and `Agent.apiKey` (SettingsModels.kt:111) verbatim in plaintext; anyone with the zip has every key.
**Root cause:** Keys must restore, so they're included unencrypted (by design) — but there's no warning or optional encryption.
**Proposed fix:** Offer an optional passphrase-encrypted backup, or at minimum warn the user the zip contains plaintext keys.
**Status:** Fixed (2026-06-07) — Backup & Restore shows a prominent plaintext-key warning before creating a backup zip.

### Bug 67 — Severity: LOW — Category: manifest version parse strictness
**Location:** BackupManager.kt:422-445 (`readManifestVersion`)
**Symptom:** `(manifest["version"] as? Number)?.toInt()` returns -1 (→ reject) if a valid backup ever wrote `version` as a JSON string; a tolerant producer/consumer mismatch would refuse a real backup.
**Root cause:** Strict Number cast; no string fallback.
**Proposed fix:** Accept a numeric string for `version` too.
**Status:** Fixed in `BackupManager.kt` by accepting numeric string manifest versions in addition to JSON numbers.

## File: ai/src/main/java/com/ai/data/Knowledge.kt

### Bug 68 — Severity: MEDIUM — Category: one bad chunk drops a whole source
**Location:** Knowledge.kt:272-287 (`forEachChunk`)
**Symptom:** Each source's chunk file is parsed into `Array<KnowledgeChunk>` and iterated inside one `runCatching`. A single malformed chunk (e.g. missing/`null` `embedding` FloatArray per the Bug-1 trap, or `arr == null`) throws mid-`arr.forEach(block)`, and the `runCatching` swallows it — silently dropping *every* chunk of that source from retrieval (not just the bad one).
**Root cause:** The catch wraps the whole-file iteration, so any per-chunk failure aborts the rest of the file; `gson.fromJson(..., Array::class)` returning null also NPEs `arr.forEach`.
**Proposed fix:** Null-guard `arr`, and iterate with a per-chunk try so one corrupt chunk doesn't drop the source; log the count skipped.
**Status:** Fixed (2026-06-07) - null-guard the parsed array + per-chunk try so one corrupt chunk skips itself (logged), not the whole source

### Bug 69 — Severity: LOW — Category: embedder swap silent mis-rank
**Location:** Knowledge.kt:229-242 (`saveSource` dim retention)
**Symptom:** When a re-index produces a different embedding dim than the manifest, the manifest dim is *retained* and only a warn is logged; the new source's chunks then silent-zero against the query (different vector space) — retrieval quietly degrades with no user-facing signal.
**Root cause:** Mixed-embedder chunks coexist; only a log warns.
**Proposed fix:** Mark the source (or KB) as "needs re-index" and surface it in the KB UI instead of only logging.
**Status:** Fixed (2026-06-07) — sources saved with an embedding-dimension mismatch now carry a visible needs-reindex warning in the Knowledge UI.

### Bug 70 — Severity: LOW — Category: KB manifest Gson null trap
**Location:** Knowledge.kt:328-331 (`loadKb`)
**Symptom:** `KnowledgeBase.name`/`embedderProviderId`/`embedderModel` are non-null String; a corrupt manifest missing one loads with a null (Bug-1 class) and NPEs at retrieve time (`first.embedderProviderId`). Callers wrap `loadKb` in `runCatching` so a parse *exception* is handled, but a successful-parse-with-null is not.
**Root cause:** Same Gson Unsafe trap; `runCatching` doesn't catch the later NPE.
**Proposed fix:** Validate non-null fields in `loadKb` and treat a null as a load failure.
**Status:** Fixed in `Knowledge.kt` by validating required KB manifest string fields inside `loadKb` and normalizing invalid source rows before returning a `KnowledgeBase`.

## File: ai/src/main/java/com/ai/data/KnowledgeService.kt

### Bug 71 — Severity: LOW — Category: empty-heap NPE on degenerate topK
**Location:** KnowledgeService.kt:241-266 (`heap.peek()!!`)
**Symptom:** With `topK == 0` (`cap == 0`), the `heap.size < cap` branch is never taken, so every candidate hits `else if (sim > heap.peek()!!.score)` with an empty heap → `peek()` returns null → NPE inside `forEachChunk`'s block (caught per Bug 68, so the source is silently dropped).
**Root cause:** No guard for `topK <= 0`.
**Proposed fix:** Coerce `topK`/`cap` to at least 1, or early-return for `topK <= 0`.
**Status:** Fixed (2026-06-07) — retrieval now returns empty immediately for `topK <= 0`, before embedding or heap construction

### Bug 72 — Severity: LOW — Category: locale-sensitive log formatting
**Location:** KnowledgeService.kt:306, 312 (`"%.3f".format(...)`)
**Symptom:** Retrieval score logs use the default locale; on nl-NL scores render with a comma. Cosmetic (log only) and consistent with other `%.Nf` log sites.
**Root cause:** `String.format` default locale.
**Proposed fix:** Use `Locale.US` for the score formatting.
**Status:** Fixed (2026-06-07) — retrieval score log formatting now uses `String.format(Locale.US, "%.3f", ...)`

## File: ai/src/main/java/com/ai/data/AuditLog.kt

### Bug 73 — Severity: LOW — Category: incomplete secret redaction
**Location:** AuditLog.kt:240 (`RAW_KEY_REGEX`) — same in AppLog.kt:353
**Symptom:** Raw-key redaction only matches the prefixes `sk-`/`xai-`/`gsk_`/`key-`. Provider keys without those prefixes (Mistral 32-hex, Cohere, Together, DeepSeek, custom providers) embedded in an audit/log message are *not* redacted. Bearer headers are caught, but a key logged outside an `Authorization` header is not.
**Root cause:** Prefix allow-list rather than entropy/context-based detection.
**Proposed fix:** Add the known key formats for the wired providers, or redact any token longer than N chars adjacent to a `key`-like context.
**Status:** Fixed in `AuditLog.kt` and `AppLog.kt` by adding a context-based secret redaction pass for long values next to key/token/secret/password field names.

### Bug 74 — Severity: LOW — Category: unbounded audit growth
**Location:** AuditLog.kt:185-202 (retention)
**Symptom:** Audit files are *kept* on report delete (a `Report deleted` line is appended) and never auto-pruned; over time `filesDir/audit` grows unboundedly. Unlike `applog`, the audit dir is NOT in `FILES_DIR_BACKUP_EXCLUDES`, so it also bloats every backup zip.
**Root cause:** Deliberate retention with no cap and no backup exclusion.
**Proposed fix:** Cap audit dir size/age, or exclude it from backup like `applog`.
**Status:** Fixed in `AuditLog.kt` by pruning newest-first audit retention to 1,000 files / 20 MB on startup and append.

## File: ai/src/main/java/com/ai/data/AppLog.kt

### Bug 75 — Severity: LOW — Category: cache/disk consistency on rotation
**Location:** AppLog.kt:215, 240, 280-289 (`writerDate!!`, `appendLine` rotation)
**Symptom:** Daily rotation compares `writerDate != today` (string) and reopens the writer. The `getLogFiles` cache (`cachedFiles`) is invalidated by `deleteLog`/`clearLogs` but NOT by a normal `appendLine` that rolls into a *new* day's file — so after midnight the cached file list omits the newly-created day's file until something else invalidates it.
**Root cause:** Append path that creates a new day file doesn't invalidate `cachedFiles`.
**Proposed fix:** Invalidate/append to `cachedFiles` when a new day's writer is opened.
**Status:** Fixed (already present) — `appendLine` invalidates `cachedFiles` after every successful write, including the first write after daily rotation.

## File: ai/src/main/java/com/ai/data/PromptCache.kt + MetaCache.kt

### Bug 76 — Severity: LOW — Category: cache-key prompt drift
**Location:** PromptCache.kt:27-40 (`keyFor`) / MetaCache.kt:115-118 (`keyOf`)
**Symptom:** PromptCache keys on `(prompt, agentId)` and MetaCache on `(category, input)` but neither includes the resolved generation parameters (temperature, reasoning effort, system prompt). A user who changes parameters and re-asks gets the *cached* response computed under the old parameters — stale result, no recompute, within the 48h/7-day TTL.
**Root cause:** Cache key omits parameter/system-prompt state that affects the output.
**Proposed fix:** Fold the resolved params + system prompt into the cache key.
**Status:** Fixed — prompt/meta cache keys now support an explicit generation variant; model-intro keys include the default generation variant, and worker-backed title/language meta cache keys include the internal prompt body plus resolved worker, parameter, and system-prompt state.

## File: ai/src/main/java/com/ai/viewmodel/RegenerateBatchEngine.kt

### Bug 77 — Severity: MEDIUM — Category: cancel/restart not atomic vs orchestrator
**Location:** RegenerateBatchEngine.kt:164-178 (`cancel`), 124-150 (`restart`), 667-670 (`persist`) vs 653-665 (`mutateJob` using `update`)
**Symptom:** `cancel()`/`restart()`/`reconcile()` persist the job via `persist()` → `RegenerateBatchStorage.save()` (blind full-object write), while the orchestrator mutates via `mutateJob()` → `update()` (atomic RMW). A `cancel()` that reads RUNNING, then writes CANCELLED via blind save, can be raced by a still-running orchestrator's `pauseOnError`/`advanceToNextPhase` RMW that reads CANCELLED and writes PAUSED_ON_ERROR/RUNNING — resurrecting a cancelled job (exactly the failure the `mutateJob` Bug-58 comment claims to prevent, but the cancel side doesn't use the atomic path).
**Root cause:** Inconsistent persistence: status transitions on the user/sweep side use blind `save`, the orchestrator uses `update`. The orchestrator coroutine's `mutateJob` is a non-suspend disk operation, so cooperative cancellation can't interrupt it mid-write.
**Proposed fix:** Route `cancel`/`restart`/`reconcile` status writes through `RegenerateBatchStorage.update` (RMW) too, and have the orchestrator's RMW bail when it reads a terminal (CANCELLED/DONE) status instead of overwriting it.
**Status:** Fixed (2026-06-07) — cancel/restart/reconcile status changes now route through the same atomic update path as orchestrator mutations; orchestrator mutations preserve terminal CANCELLED/DONE jobs so a late RMW cannot resurrect a cancelled batch

### Bug 78 — Severity: LOW — Category: double-cancel orchestrator window
**Location:** RegenerateBatchEngine.kt:94-116 (`enqueueAndStart`), 254-271 (`startOrchestrator`)
**Symptom:** `enqueueAndStart` cancels the existing orchestrator (async, not joined), persists a fresh job, then `startOrchestrator` cancels again and launches. The old orchestrator coroutine may still be executing a non-suspend `mutateJob` and write stale task state over the freshly-persisted job between the persist and the new launch.
**Root cause:** Cancellation is requested but not joined before the new job is written.
**Proposed fix:** `cancelAndJoin()` the previous orchestrator before persisting the new job (as `deleteJob` already does).
**Status:** Fixed (2026-06-07) — enqueue now removes and cancelAndJoin()s any previous orchestrator before persisting the fresh job.

### Bug 79 — Severity: LOW — Category: phase-timeout pause picks arbitrary row
**Location:** RegenerateBatchEngine.kt:344-349 (`pauseOnError(... rowIds.first() ...)`)
**Symptom:** On a 30-min phase timeout, the job is paused on `rowIds.first()` — an arbitrary set-iteration-order row, not necessarily the row that actually stalled — so the detail screen highlights the wrong row and the auto-resume watches the wrong row's error state.
**Root cause:** `rowIds.first()` on a `Set` has undefined order and no relation to which row stalled.
**Proposed fix:** Pause on the first row still in RUNNING state (the actual stalled one), not an arbitrary set element.
**Status:** Fixed (2026-06-07) — phase timeout now pauses on the first persisted RUNNING task for that phase, with the old row set only as a fallback.

## File: ai/src/main/java/com/ai/data/ApiUsageRates.kt

### Bug 80 — Severity: LOW — Category: main-thread pricing in hot stat
**Location:** ApiUsageRates.kt:61-81 (`costWithin`)
**Symptom:** `costWithin` calls `PricingCache.getPricing(context, ...)` per (provider,model) group; on the main thread during the cold pricing window it returns DEFAULT and the live dashboard cost reads wrong (see Bug 42), and after warm-up the per-group `getPricing` does a layered lookup on the UI thread on every dashboard tick.
**Root cause:** Pricing resolution on the dashboard read path.
**Proposed fix:** Precompute/caches a price snapshot per (provider,model) and reuse; gate cost display on `preloadCompleted`.
**Status:** Fixed in `ApiUsageRates.kt` by reusing per-provider/model pricing snapshots after pricing preload instead of repeating layered lookups on every dashboard tick.

## File: ai/src/main/java/com/ai/viewmodel/SecondaryRunManager.kt + TranslationRunManager.kt

### Bug 81 — Severity: LOW — Category: non-null assertion fragility
**Location:** SecondaryRunManager.kt:154/1414/1464, TranslationRunManager.kt:177/191/205/223/248 (`!!` on `responseBody`/`modelTitle`/`title`/`content`/`persistedRowId`)
**Symptom:** These `!!` are currently safe because each is preceded by a `filter { !it.X.isNullOrBlank() }`, but the guard and the assertion are textually separated (the filter is on one collection, the `!!` on the mapped element), so a future refactor that changes the filter predicate or reorders the map would turn these into NPEs at run time.
**Root cause:** Invariant enforced by a distant filter rather than a local non-null binding.
**Proposed fix:** Bind the non-null value inside the `mapNotNull`/`filter` (`val body = it.responseBody ?: return@mapNotNull null`) so the non-null is local and the `!!` disappears.
**Status:** Fixed in `SecondaryRunManager.kt`, `SecondaryModelSwitchManager.kt`, and `TranslationRunManager.kt` by replacing distant filter-plus-`!!` invariants with local non-null bindings.

## File: ai/src/main/java/com/ai/model/SettingsModels.kt

### Bug 82 — Severity: MEDIUM — Category: Gson null trap + provider removal in settings
**Location:** SettingsModels.kt:110-133 (`Agent`, `Flock`, `Swarm` members carrying `AppService`/non-null Strings)
**Symptom:** `Agent.provider: AppService` (non-null) + `Agent.apiKey: String` (non-null). A persisted Agent whose provider id was removed throws in `AppServiceAdapter` (Bug 8) and, depending on the load path, either drops the agent silently or fails the whole settings blob; a settings blob missing `apiKey`/`name` deserializes them to null (Bug 1) and NPEs at use.
**Root cause:** Settings sub-objects embed `AppService` and non-null Strings subject to the same Unsafe-allocator + adapter-throw issues as chat sessions.
**Proposed fix:** Load agent/flock/swarm lists with per-item try (drop the bad one, keep the rest) and resolve unknown providers to a disabled placeholder rather than throwing.
**Status:** Fixed (2026-06-07) - provider-removal half resolved by data#8 (synthetic AppService, no throw); loadList now recovers element-by-element so one malformed agent/flock/swarm/prompt no longer drops the whole list

### Bug 83 — Severity: LOW — Category: secret in settings blob
**Location:** SettingsModels.kt:111 (`Agent.apiKey: String`)
**Symptom:** Per-agent API keys are stored in the settings prefs blob (and thus the backup zip, Bug 66) in plaintext, duplicating the per-provider key storage and widening the plaintext-key surface.
**Root cause:** Keys denormalised onto each Agent.
**Proposed fix:** Reference the provider's stored key rather than copying it onto each Agent, reducing plaintext copies.
**Status:** Fixed (2026-06-07) — settings load/save migrates legacy agent keys to provider storage when needed and strips agent.apiKey from the persisted agent list; the Agent editor no longer writes per-agent keys.

## File: ai/src/main/java/com/ai/data/PricingParsers.kt

### Bug 84 — Severity: LOW — Category: numeric parse assumption
**Location:** PricingParsers.kt:80 (`(info[key] as? Number)?.toDouble()`)
**Symptom:** Pricing fields are read as `Number` (Gson parses JSON numbers to Double) — safe for well-formed catalogs. But catalogs that ship prices as *strings* (OpenRouter's `OpenRouterPricing` uses String fields elsewhere) would yield null here, silently zeroing that tier's price for the affected models.
**Root cause:** `as? Number` returns null for a stringified number; no `toDoubleOrNull()` fallback for string-typed values.
**Proposed fix:** Fall back to `(info[key] as? String)?.toDoubleOrNull()` when the value is a numeric string.
**Status:** Fixed in `PricingParsers.kt` by accepting numeric strings for LiteLLM pricing fields.
