# Deep Code Review — Data Layer / View-Models / Infrastructure (audit-2, fresh from current code)

Scope: `ai/src/main/java/com/ai/data/**`, `ai/src/main/java/com/ai/viewmodel/**`, `ai/src/main/java/com/ai/model/**`. All findings derived from reading the live source at the cited lines. Bugs are numbered continuously.

---

## File: ai/src/main/java/com/ai/data/ApiModels.kt

### Bug 1 — Severity: CRITICAL — Category: Gson deserialization / non-null Kotlin fields
**Location:** ApiModels.kt:16-22 (`createAppGson` / `aiGson`)
**Symptom:** Restoring/importing a malformed or partial JSON document (report, chat session, secondary result, KB manifest, trace) can produce an object whose declared non-null Kotlin fields are actually `null`, NPE-ing later code that trusts the type (e.g. `ReportStorage.copyReport` calling `src.title.endsWith("(Copy)")`, `updateAgentStatus` calling `report.agents.find{}`).
**Root cause:** `aiGson` is a plain `GsonBuilder().create()` with no Kotlin-aware adapter (no `kotlin-reflect`/moshi/`@JvmStatic` default handling). Gson constructs instances via `UnsafeAllocator`, bypassing the Kotlin primary constructor and its default values. A JSON object missing a non-null field (`Report.title`, `Report.prompt`, `Report.agents`, `ChatSession.messages`, etc.) leaves that field at the JVM zero value (`null`), violating the Kotlin non-null contract silently.
**Reproduction:** Hand-edit a `reports/<id>.json` to drop `"title"`, reopen the report → `copyReport` / any `report.title.*` call throws NPE; or restore a backup containing such a file.
**Proposed fix:** Register a null-checking type-adapter factory (or use a Kotlin-aware Gson setup) that rejects/fills missing non-null fields; or make the load paths validate required fields and discard objects that fail. At minimum, treat the storage `loadX` catch blocks as the safety net (they already swallow parse exceptions) by post-validating required fields.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ApiStreaming.kt

### Bug 2 — Severity: MEDIUM — Category: SSE parsing / false truncation error
**Location:** ApiStreaming.kt:132-134 (`parseSseStream`, end-of-stream check)
**Symptom:** A streaming response from an OpenAI-compatible provider that emits content but legitimately closes the TCP connection without a `[DONE]` terminator (and is not Anthropic `message_stop` / Responses `response.completed` / Gemini `finishReason`) throws `IOException("SSE stream ended without truncated")`, surfacing a hard error to the user even though the full answer arrived.
**Root cause:** `sawTerminator` is only set by `[DONE]`, the two named events, or the Gemini final-chunk predicate. Many OpenAI clones (and self-hosted vLLM/Ollama-compat endpoints) end the stream by closing the socket after the last delta with no `[DONE]`. The post-loop `if (!sawTerminator && sawAnyData) throw` then treats a complete response as truncated.
**Reproduction:** Stream chat against a provider that omits `data: [DONE]`; observe the chat turn fail at the very end despite content having been emitted.
**Proposed fix:** Treat a clean reader EOF (readLine returns null with no IOException) as a valid terminator for the OPENAI_COMPATIBLE path, or downgrade the missing-terminator case to a warning when at least one content chunk was emitted.
**Status:** Open

### Bug 3 — Severity: LOW — Category: SSE parsing / multi-line event edge case
**Location:** ApiStreaming.kt:99,106-118 (`dispatch` resets `eventType=null`; `event:` line handling)
**Symptom:** If a single SSE event interleaves `data:` and `event:` lines in a non-standard order (e.g. `data:` then `event:` then `data:` with no blank line between), the second `event:` overwrites `eventType` for the whole accumulated buffer, and the extractor may key on the wrong event type.
**Root cause:** `eventType` is a single var spanning the whole event accumulation; the spec allows multiple field lines but assumes the last `event:` wins. The code matches the spec for well-formed streams but has no guard for shard ordering quirks across providers.
**Proposed fix:** Acceptable per spec; if hardening is desired, capture `eventType` at first non-blank field and ignore later overrides within the same event.
**Status:** Open

### Bug 4 — Severity: MEDIUM — Category: error-body handling / connection leak window
**Location:** ApiStreaming.kt:230-240, 261-268, 291-299, 320-327 (stream methods, non-success path)
**Symptom:** On a non-streaming-but-error HTTP response to a *streaming* request, `response.errorBody()?.string()` reads the body, but the success-body (`response.body()`) is never closed when `isSuccessful` is false — already drained via errorBody, so OK — however the thrown `Exception("API error …")` loses the HTTP status code as a structured value (only embedded in the message string), so the retry/cooldown layer downstream can't discriminate 429/402 on the streaming path.
**Root cause:** Streaming dispatch throws plain `Exception` with the status only in the message; non-streaming dispatch (ApiDispatch) returns a structured `AnalysisResponse(httpStatusCode=…)`. The asymmetry means streaming-chat error classification relies on string parsing.
**Proposed fix:** Throw a typed exception carrying `code` (mirror `FetchModelsException`), or surface the code so callers don't regex the message.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ApiDispatch.kt

### Bug 5 — Severity: HIGH — Category: vision content blocks / Gemini multi-part response
**Location:** ApiDispatch.kt:583 (`chatGemini`) vs 423-424 (`analyzeGemini`)
**Symptom:** Non-streaming Gemini *chat* (`chatGemini`) extracts only `candidates[0].content.parts[0].text`. If Gemini returns the answer split across multiple parts (common when grounding/web-search or thinking summaries are interleaved), only the first part is returned and the rest of the reply is dropped. `analyzeGemini` has the multi-part fallback (`flatMap{...}.firstNotNullOfOrNull`), but `chatGemini` does not.
**Root cause:** Copy divergence — the analyze path was hardened for multi-part but the chat path was left on the single-part lookup.
**Proposed fix:** Mirror `analyzeGemini`'s fallback in `chatGemini`: `?: body?.candidates?.flatMap { it.content?.parts ?: emptyList() }?.firstNotNullOfOrNull { it.text }` (ideally join all text parts like the Anthropic/Responses paths).
**Status:** Open

### Bug 6 — Severity: MEDIUM — Category: Gemini streaming / web-search content loss
**Location:** ApiStreaming.kt:187-191 (`extractGeminiContent`) — also relevant to ApiDispatch routing
**Symptom:** Gemini SSE extractor takes only `parts.firstOrNull()?.text`. A streamed chunk carrying multiple parts (text + grounding) emits only the first part's text per chunk, dropping later text parts in the same chunk.
**Root cause:** Same single-part assumption as Bug 5, on the streaming side.
**Proposed fix:** Join all `parts.mapNotNull { it.text }` per chunk.
**Status:** Open

### Bug 7 — Severity: MEDIUM — Category: Anthropic max_tokens silent override on streaming
**Location:** ApiDispatch.kt:1265-1285 (`claudeReasoningBundle`); streaming uses it at ApiStreaming.kt:279-282
**Symptom:** When a user sets an explicit small `max_tokens` and reasoning is on, `claudeReasoningBundle` silently raises `max_tokens` to `budget+4096`. On the streaming path the override log line fires but the user's cap is exceeded with a cost impact, and there is no UI surfacing of the bump on streaming chat.
**Root cause:** Anthropic rejects `max_tokens <= budget_tokens`, so the bump is required for correctness — but it overrides the user's explicit cap. The log is the only signal.
**Proposed fix:** Acceptable for non-thinking; for the thinking case, surface the effective `max_tokens` in the chat/report UI (it is already in the trace). Document the floor in the parameters screen.
**Status:** Open

### Bug 8 — Severity: MEDIUM — Category: embeddings alignment / partial-vector accumulation
**Location:** ApiDispatch.kt:182-191 (`embedWithStatus`) and KnowledgeService.kt:142-179 (`runIndex`)
**Symptom:** `runIndex` accumulates `vectors.addAll(out)` per batch and later indexes `vectors[i]` by chunk position. If any single batch's `embedWithStatus` returns a malformed result it errors out (good), but the GOOGLE path (`embedGemini`) and the local path return vectors that are validated only for `size == texts.size` per batch; a provider that silently drops the *middle* of a batch but returns the right count with an empty vector would be caught by the `any { it.isEmpty() }` check — however the Gemini path returns `it.values ?: emptyList()`, so a single null-valued embedding becomes empty and the whole source errors (refuses to save) rather than skipping the bad chunk. For a long KB this fails the entire index on one bad row.
**Root cause:** All-or-nothing validation per batch; one empty vector aborts the whole source index.
**Proposed fix:** Either retry the offending input or drop the single bad chunk and continue, instead of failing the whole document.
**Status:** Open

### Bug 9 — Severity: LOW — Category: model-list fetch / empty-but-valid list
**Location:** ApiDispatch.kt:594-791 (`fetchModelsOpenAi`) — interaction with AppViewModel.refreshAllModelLists
**Symptom:** A provider whose `/v1/models` returns 200 but an empty list after `modelFilterRegex` filtering yields `FetchedModels(emptyList())` (no throw). `refreshAllModelLists` classifies success as `ids.size > 0`, so this provider's cache timestamp is never updated and it re-fetches on every refresh.
**Root cause:** Success/failure is keyed on `> 0` rather than `>= 0`; a legitimately empty filtered list is indistinguishable from "didn't run".
**Proposed fix:** Track success by absence of exception, not by `ids.size > 0`; record the timestamp for an empty-but-successful fetch.
**Status:** Open

### Bug 10 — Severity: LOW — Category: model-list raw-snapshot double fetch
**Location:** ApiDispatch.kt:601-605, 854-857, 862 (`fetchUrlAsString` second roundtrip)
**Symptom:** Every model-list refresh makes a second HTTP GET solely to capture raw JSON, doubling request count and another way to silently fail/latency. For OpenRouter (cross-provider) the raw URL is hardcoded `v1/models` regardless of `modelsPath`.
**Root cause:** Raw-snapshot capture is a parallel call rather than reusing the typed call's body.
**Proposed fix:** Capture the raw body from the typed call (interceptor/tee) instead of a second request.
**Status:** Open

### Bug 11 — Severity: MEDIUM — Category: testApiConnectionWithJson / streaming flag mutation
**Location:** ApiDispatch.kt:952-957 (`testApiConnectionWithJson`)
**Symptom:** For a GOOGLE-format provider, the code forces `stream=false` by parsing the JSON and adding the property, but Google's `:streamGenerateContent` vs `:generateContent` is selected by URL path, not a body field. A test against a streaming Gemini URL still hits the streaming endpoint, and the response parse (`OpenAiResponse`) won't match Gemini's shape — the test falls through to returning the raw body, which can mislabel a working provider.
**Root cause:** The raw-JSON test path assumes an OpenAI response shape and an in-body stream flag; neither holds for Gemini.
**Proposed fix:** Branch the response parse by `service.apiFormat` (Gemini/Anthropic shapes), and pick the non-streaming Gemini path by URL.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/TagPropagation.kt

### Bug 12 — Severity: HIGH — Category: ThreadLocal leak across pooled OkHttp worker threads
**Location:** TagPropagation.kt:136-152 (`TagPropagatingExecutor.execute` worker block)
**Symptom:** `permitPreAcquired` / `suppressInlineRetry` can leak a stale `true` onto a later, unrelated call running on the same cached-thread-pool worker, causing that later call to skip its own `ProviderThrottle.acquire` (double-concurrency / cap bypass) or skip the 429/529 retry loop.
**Root cause:** The worker only re-applies the flag conditionally: `if (capturedPreAcquired) ProviderThrottle.permitPreAcquired.set(true)` and in `finally` `if (capturedPreAcquired) ... set(previousPreAcquired)`. When `capturedPreAcquired` is **false**, the code neither sets the flag to false nor restores it — so whatever value a *previous* Runnable left on this pooled thread persists. Since `Executors.newCachedThreadPool` reuses threads and a prior fan-out call may have set `true` (and its own finally only restores its own `previous`), a subsequent non-fan-out call inherits `true`.
**Reproduction:** Run a fan-out batch (sets `permitPreAcquired=true`) then a plain report/chat call that reuses the same worker thread; the plain call's `ProviderThrottleInterceptor` reads `true` and skips throttle acquisition.
**Proposed fix:** Always set the flag to the captured value unconditionally (`ProviderThrottle.permitPreAcquired.set(capturedPreAcquired)`), and in finally always restore `previousPreAcquired` — drop the `if (captured…)` guards entirely. Same for `suppressInlineRetry`.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/RateLimitRetry.kt

### Bug 13 — Severity: MEDIUM — Category: 429 retry / repeated peekBody allocations
**Location:** RateLimitRetry.kt:81-92 (`benchUntil` block) → `cohereTrialQuotaExhausted`, `googleDailyQuotaExhausted`, `creditOrSpendingLimitExhausted`, `retryAfterHintMs`
**Symptom:** On every 429, up to four separate `response.peekBody(64 KiB)` calls fully buffer the error body four times (once per predicate). For large error bodies on a hot 429 path this is wasteful and each peek re-reads from the source buffer.
**Root cause:** Each helper independently peeks the body instead of reading it once and passing the string around.
**Proposed fix:** Read `response.peekBody(64KiB).string()` once and pass the decoded body into each predicate.
**Status:** Open

### Bug 14 — Severity: LOW — Category: 429 exponential backoff overflow guard
**Location:** RateLimitRetry.kt:135 (`backoffMs shl attempt.coerceAtMost(16)`)
**Symptom:** `attempt` is coerced to ≤16 for the shift, but with a large user `backoffMs` (e.g. 60_000) `60000 shl 16` overflows past `Int`/long-range intent before the `coerceAtMost(30_000L)`; the `.coerceAtMost(30_000L)` saves it, so functionally OK, but the jitter line `nextLong(expBackoff/2 + 1)` would throw if `expBackoff` were ever 0 (it can't be since `backoffMs` is coerced ≥1, but a future change to allow 0 backoff would crash).
**Root cause:** Shift/jitter math assumes `expBackoff >= 1`.
**Proposed fix:** Compute `expBackoff` with explicit clamping to `[1, 30_000]` before the jitter division.
**Status:** Open

### Bug 15 — Severity: LOW — Category: retry loop holds no throttle permit (intended) but no log of slot occupancy
**Location:** RateLimitRetry.kt:142-150 (`Thread.sleep(sleepMs)` inside retry loop)
**Symptom:** During the 429 retry sleep the OkHttp dispatcher worker thread is blocked sleeping; with a long `Retry-After` (clamped to 5 min) and several concurrent 429s, dispatcher workers can be tied up. The throttle permit is released (inner interceptor's finally) so the per-host cap is free, but the global cached-thread-pool can still balloon with sleeping workers.
**Root cause:** Sleeping in the interceptor occupies a thread for the wait.
**Proposed fix:** Acceptable given the cached thread pool, but consider moving long waits to the suspend layer (`kotlinx.coroutines.delay`) as the comment already notes for caller-driven retries.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/OverloadedRetry.kt

### Bug 16 — Severity: MEDIUM — Category: 529 retry lacks jitter / no lastTraceFilename reset
**Location:** OverloadedRetry.kt:29-46
**Symptom:** (a) The 529 retry uses a flat `backoffMs` per attempt (no exponential/jitter), so a synchronized burst of 529s against Anthropic re-collides each attempt. (b) Unlike `RateLimitRetryInterceptor`, this interceptor does not `ApiTracer.lastTraceFilename.set(null)` at entry; since it sits *outside* the 429 interceptor in the chain (added second, so it wraps further out) a stale `lastTraceFilename` from a prior call on the pooled thread could be read by any 529-driven bench logic (none here today, but fragile).
**Root cause:** The 529 path was written as a "mirror" of the 429 path but omitted the jitter backoff and the thread-local reset.
**Proposed fix:** Apply the same exponential-with-jitter backoff as the 429 path; reset `lastTraceFilename` at entry for symmetry.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/TracingInterceptor.kt

### Bug 17 — Severity: MEDIUM — Category: secret redaction gap in request body
**Location:** TracingInterceptor.kt:46-50 (`rawRequestBody`), 262-301 (header redaction)
**Symptom:** Request/response **headers** are redacted, and Google `?key=` in the URL is redacted, but the **request body** is stored verbatim. Any provider that places a secret in the JSON body (some self-hosted/proxy setups, or future auth-in-body providers) would persist plaintext into the trace JSON, which is then included in backups (`filesDir/trace`).
**Root cause:** Redaction is header/URL-only; the body is trusted to be secret-free.
**Proposed fix:** Run a body redaction pass for known key-bearing fields (`api_key`, `apiKey`, `key`, `token`, `authorization`) before persisting.
**Status:** Open

### Bug 18 — Severity: LOW — Category: streaming detection heuristic false negatives
**Location:** TracingInterceptor.kt:110-111 (`isStreaming`)
**Symptom:** Streaming detection keys on `Content-Type: text/event-stream` OR (`Transfer-Encoding: chunked` AND not `application/json`). A provider that streams SSE but labels it `application/json` with chunked encoding (some proxies do) is classified non-streaming and gets fully buffered up to 8 MiB synchronously inside the interceptor, blocking the worker until the whole stream completes — defeating streaming UX.
**Root cause:** Heuristic depends on the upstream Content-Type being correct.
**Proposed fix:** Also consult the request's `stream:true` flag (already detected by `ReadTimeoutInterceptor`) to decide the tee path.
**Status:** Open

### Bug 19 — Severity: LOW — Category: typo in partial-trace marker
**Location:** TracingInterceptor.kt:177 (`body = "[partitial: stream in progress]"`)
**Symptom:** The partial-trace placeholder text reads `[partitial: …]` (misspelled). Cosmetic; visible in trace files killed mid-stream.
**Proposed fix:** `[partial: stream in progress]`.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ApiTracer.kt

### Bug 20 — Severity: MEDIUM — Category: lock held across disk I/O
**Location:** ApiTracer.kt:104-168 (`saveTrace` under `lock.withLock`), 170-181 (`getTraceFiles`), 260-270 (read)
**Symptom:** `saveTrace` holds the global `ReentrantLock` across `writeTextAtomic` (full disk write + fsync). Every concurrent traced call (chat stream finish, report agent finish, fan-out pair) serializes on this lock during disk I/O. Under a 50-pair fan-out with tracing enabled, trace writes become a serialized bottleneck and can stall the OkHttp workers that call `saveTrace`.
**Root cause:** The lock guards both the in-memory cache mutation and the disk write; only the cache mutation needs it.
**Proposed fix:** Write the file outside the lock (atomic write is already crash-safe), then take the lock only for the cache list mutation.
**Status:** Open

### Bug 21 — Severity: MEDIUM — Category: trace filename sequence collisions / cache resort cost
**Location:** ApiTracer.kt:109-119 (`resolvedFilename`), 160 (`sortedByDescending` on every append)
**Symptom:** (a) Filename is `host_ts_seq.json` where `seq = fileSequence.incrementAndGet().toString(36)`; the sequence is in-memory and resets to 0 on process restart, so after a relaunch a new trace in the same millisecond as an old one (same host) can collide on filename and overwrite a prior trace. (b) Every non-update `saveTrace` re-sorts the entire cached list (`O(n log n)`) under the lock — for a dense trace dir this is repeated on every traced call.
**Root cause:** Sequence is not persisted; cache maintained by full resort rather than insertion.
**Proposed fix:** Seed `fileSequence` from the max existing on disk at init (or use a UUID suffix); insert into the sorted position instead of full resort.
**Status:** Open

### Bug 22 — Severity: LOW — Category: appendCategorySuffix re-serializes whole trace
**Location:** ApiTracer.kt:278-286 (`appendCategorySuffix`)
**Symptom:** Tagging a trace's category after the fact reads the full `ApiTrace` JSON, copies it, and rewrites the whole (possibly multi-MB) file just to append a suffix to the category string.
**Root cause:** No partial update path for metadata.
**Proposed fix:** Acceptable for the rare miss-tag case; if hot, store category in a sidecar.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/AtomicFileWrite.kt

### Bug 23 — Severity: MEDIUM — Category: shared temp filename / concurrent-write races
**Location:** AtomicFileWrite.kt:20-21 (`val tmp = File(parent, "$name.tmp")`)
**Symptom:** The temp file name is a deterministic `<name>.tmp`. Two threads writing the *same* destination file concurrently (without an external lock) both write to the identical `<name>.tmp`, interleaving bytes before the rename — one rename can move a half-other-written tmp into place. Most callers hold a per-object lock, but `ModelListCache`, `EmbeddingsStore`, `PromptCache.put`, `PricingCache.saveBlob`, and `SecondaryResultStorage` bump helpers do not all serialize writes to the same path.
**Root cause:** The tmp name isn't unique per writer.
**Proposed fix:** Use a unique tmp name (`<name>.<uuid>.tmp`) so concurrent writers don't share the staging file.
**Status:** Open

### Bug 24 — Severity: LOW — Category: parent-dir fsync omitted
**Location:** AtomicFileWrite.kt:34-52
**Symptom:** The file FD is fsync'd before the atomic rename, but the *directory* is not fsync'd after the rename. On a hard power loss the rename itself can be lost even though the data was synced (the directory entry isn't durable).
**Root cause:** No directory fsync after rename (Android/Java has no portable API; minor in practice on ext4 with ordered journaling).
**Proposed fix:** Best-effort; document the limitation. Low priority on Android internal storage.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ChatHistoryManager.kt

### Bug 25 — Severity: MEDIUM — Category: path traversal on delete/load not gated
**Location:** ChatHistoryManager.kt:67-81 (`loadSession`), 98-117 (`deleteSession`)
**Symptom:** `saveSession` rejects unsafe ids via `isSafeFlatId` + canonical check, but `loadSession` and `deleteSession` interpolate `sessionId` straight into `File(dir, "$sessionId.json")` with no validation. A deep-link / nav-arg / import-driven `sessionId` of `../reports/foo` could read or delete a file outside `chat-history/`.
**Root cause:** Defence-in-depth applied to write path only.
**Proposed fix:** Apply the same `isSafeFlatId` + canonical-containment guard to `loadSession` and `deleteSession`.
**Status:** Open

### Bug 26 — Severity: LOW — Category: history version StateFlow dedup
**Location:** ChatHistoryManager.kt:152 (`notifyHistoryChanged` → `_historyVersion.value = System.currentTimeMillis()`)
**Symptom:** Two history mutations within the same millisecond set `_historyVersion` to the same value; StateFlow drops equal emissions, so a collector keyed on `historyVersion` may miss a refresh.
**Root cause:** Wall-clock-as-version is not monotonic-unique.
**Proposed fix:** Use a monotonically incrementing counter (`_historyVersion.update { it + 1 }`).
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ReportStorage.kt

### Bug 27 — Severity: HIGH — Category: read-modify-write without per-report serialization across the whole update
**Location:** ReportStorage.kt:57-125 (`updateAgentStatus`) and every `loadReport`→`saveReport` method
**Symptom:** All mutators take the single global `lock`, so updates are serialized — good. But the additive cost/token logic (lines 105-117) is correct only if every write goes through the lock. `appendIconCall`, `bumpReportIconCost`, `updateReportAgentIcon`, etc. all do, so this is consistent. HOWEVER the *global* lock means a long-running report with many agents finishing concurrently serializes all per-agent persistence behind one lock that also wraps `loadReport`+`gson.fromJson` of the *entire* report on every single agent update — O(agents²) JSON parse/serialize cost as the report grows, on a lock that blocks every other report's writes too.
**Root cause:** One process-wide lock + full-document reparse on each per-agent mutation.
**Proposed fix:** Per-report lock striping; or keep the report in memory during an active run and flush periodically rather than reparse+rewrite per agent.
**Status:** Open

### Bug 28 — Severity: MEDIUM — Category: totalCost recompute only on cost!=null path
**Location:** ReportStorage.kt:111-115 (`if (cost != null) { … report.totalCost = … }`)
**Symptom:** `report.totalCost` is recomputed only when `cost != null`. If an agent's icon cost is bumped elsewhere but the primary `cost` is null on that update, the report-level `totalCost` can lag the sum of `iconInputCost+iconOutputCost`. Several icon paths recompute totalCost explicitly, but `updateAgentStatus` with `cost=null` (error path) leaves a stale total when icon costs changed in the same window.
**Root cause:** totalCost recompute is gated on the primary cost being present.
**Proposed fix:** Recompute totalCost on every mutation that can change any cost field, not only when `cost != null`.
**Status:** Open

### Bug 29 — Severity: MEDIUM — Category: getAllReports unbounded full parse
**Location:** ReportStorage.kt:204, 261-268 (`loadAllReports`)
**Symptom:** `getAllReports` parses every report JSON (full graph incl. response bodies + base64 images) under the global lock on every call. History screens that call this repeatedly reparse the entire corpus — heap spike and lock contention proportional to total report data.
**Root cause:** No metadata cache; full parse each time.
**Proposed fix:** Maintain an in-memory metadata cache (like ApiTracer/SecondaryResultStorage), or a streaming metadata parse for list rendering.
**Status:** Open

### Bug 30 — Severity: LOW — Category: copyReport NPE risk on malformed source
**Location:** ReportStorage.kt:1197 (`if (src.title.endsWith("(Copy)"))`)
**Symptom:** If `src.title` is null (see Bug 1 — Gson can leave non-null fields null on a restored/corrupt JSON), `copyReport` NPEs.
**Root cause:** Trusts the non-null type that Gson may violate.
**Proposed fix:** Null-safe access (`src.title?.endsWith(...) == true`) or fix at the Gson layer (Bug 1).
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/SecondaryResultStorage.kt

### Bug 31 — Severity: MEDIUM — Category: cost double-count via mergeCostFromDisk on retry
**Location:** SecondaryResultStorage.kt:192-255 (`saveIfStillPresent` + `mergeCostFromDisk`)
**Symptom:** `saveIfStillPresent` always adds the on-disk row's prior cost to the incoming result. If a single fan-out pair's HTTP call is retried within one run (e.g. an internal retry that produces a second `saveIfStillPresent` for the same logical result without an intervening `resetRowToPlaceholder`), the cost double-counts. The "fresh run prior==0" assumption breaks if any code path writes a non-zero-cost row then re-saves.
**Root cause:** Accumulation is unconditional; idempotency relies on callers never re-saving a costed row without a reset.
**Proposed fix:** Carry a per-attempt marker (e.g. last write's runId) and only accumulate across distinct runIds, or have callers explicitly opt into accumulation.
**Status:** Open

### Bug 32 — Severity: LOW — Category: listForReport cache keyed without traversal guard
**Location:** SecondaryResultStorage.kt:119-124 (`File(it, reportId)` without isSafe check)
**Symptom:** `listForReport`/`get`/`exists`/`bump*` resolve `File(rootDir, reportId)` directly; only `reportDir()` (used by `save`) has the traversal guard. A crafted reportId could read outside the secondary root on the list/get paths.
**Root cause:** Traversal validation applied to the write/create path only.
**Proposed fix:** Route all path resolution through `reportDir`/a shared validated resolver.
**Status:** Open

### Bug 33 — Severity: LOW — Category: countByMetaName bypasses fingerprint cache
**Location:** SecondaryResultStorage.kt:657-673
**Symptom:** `countByMetaName` re-parses every JSON file in the report dir on every call (the doc comment on `countForReport` says it was de-duplicated via the cache, but `countByMetaName` re-implements the raw parse). Fan-out drill-in polls counts frequently.
**Root cause:** Separate code path that doesn't use `listForReport`'s cache.
**Proposed fix:** Build the tally from `listForReport(context, reportId)`.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/SecondaryResult.kt (prompt resolvers)

### Bug 34 — Severity: MEDIUM — Category: fan-in template expansion off-by region
**Location:** SecondaryResult.kt:739-741 (`withTopLevel.substring(0, match.range.first) + expansion + withTopLevel.substring(match.range.last + 1)`)
**Symptom:** The iterable regex has greedy `\s*` on both ends; it can consume legitimate surrounding whitespace/newlines that the user intended to keep between the iterable block and following prose, subtly changing the fan-in prompt formatting. Also, if the template contains the iterable marker more than once, only the first is expanded and the others are stripped to empty by the final `.replace("@REPORT@","")` etc.
**Root cause:** Single-match expansion + greedy whitespace capture.
**Proposed fix:** Anchor the whitespace capture more tightly and document single-occurrence; or expand all occurrences.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/PricingCache.kt

### Bug 35 — Severity: HIGH — Category: lookupPricing precedence contradicts getPricing
**Location:** PricingCache.kt:426-439 (`lookupPricing`) vs 358-387 (`getPricing`)
**Symptom:** `getPricing` puts the user manual `OVERRIDE` ahead of LiteLLM/models.dev/etc. (the documented, UI-implied precedence). `lookupPricing` (used by `Settings.recomputeCapabilities` to fill the per-provider `modelPricing` snapshot) puts `manualPricing` **after** LiteLLM/modelsDev/llmPrices/AA. So for a model that has both a manual override and a LiteLLM entry, the cached capability/pricing snapshot uses the LiteLLM price while live cost computation uses the override — a persistent disagreement between what the model picker/snapshot shows and what a report is actually billed at.
**Root cause:** Two near-duplicate lookup chains with divergent override placement.
**Proposed fix:** Make `lookupPricing` mirror `getPricing`'s precedence exactly (override before curated tiers), or have both delegate to one ordered chain.
**Status:** Open

### Bug 36 — Severity: MEDIUM — Category: ensureLoaded re-reads disk forever for never-refreshed tiers
**Location:** PricingCache.kt:1304-1354 (modelsDev / helicone / llmPrices / aa blocks)
**Symptom:** Unlike `litellmPricing`/`litellmMeta` (which default to `emptyMap()` on miss), `modelsDevPricing`, `modelsDevMeta`, `heliconePricing`, `heliconePatterns`, `llmPricesPricing`, `aaPricing`, `aaMeta` are never set to a non-null sentinel when the blob is absent. Their `== null` guards stay true forever, so **every** `ensureLoaded` call (i.e. every `getPricing`, called per cost-table row / per picker row) re-runs `loadBlob` for each missing tier — a `File.exists()` check plus an `assets.open()` attempt (which throws + catches `IOException`) on each. On a fresh install that never refreshed these tiers, scrolling a model list runs repeated failing asset opens.
**Root cause:** Missing tiers aren't memoized as "loaded-empty".
**Proposed fix:** After each load attempt, assign `emptyMap()`/`emptyList()` when still null (as the litellm block already does), so the guard short-circuits on subsequent calls.
**Status:** Open

### Bug 37 — Severity: LOW — Category: getAllPricing merge order ignores documented precedence
**Location:** PricingCache.kt:628-635 (`getAllPricing`)
**Symptom:** `getAllPricing` merges litellm → openRouter → manual via `putAll`, so the displayed "all pricing" map applies manual last (overrides win) but omits modelsDev/llmPrices/AA/Helicone tiers entirely. Any screen consuming this gets an incomplete, differently-ordered view than `getPricing`.
**Root cause:** Ad-hoc merge that predates the seven-tier chain.
**Proposed fix:** Build the merged view from the same ordered tier list, or document it as litellm+openrouter+manual only.
**Status:** Open

### Bug 38 — Severity: LOW — Category: computeInOutCost apiCost pro-rata split with zero baseline
**Location:** PricingCache.kt:291-301
**Symptom:** When the provider ships `apiCost` but the baseline rates are all zero (free model with DEFAULT/zero pricing), the split returns `(0.0 to total)` — attributing 100% of cost to output even when the spend was input-dominated. Cosmetic for the in/out split persisted on the report.
**Root cause:** Division-by-zero guard collapses to output-only.
**Proposed fix:** Split by token ratio when rates are zero, not all-to-output.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/Knowledge.kt

### Bug 39 — Severity: MEDIUM — Category: manifest race — rename/create outside the lock
**Location:** Knowledge.kt:143-162 (`createKnowledgeBase`, `renameKnowledgeBase`) vs 199-232 (`saveSource` under lock)
**Symptom:** `renameKnowledgeBase` does `loadKb` then `saveManifest` with **no lock**; `saveSource` does `loadKb`→`saveManifest` **under lock**. A concurrent rename + saveSource interleave (rename reads manifest, saveSource reads+writes manifest with new source, rename writes back the old-sources manifest with new name) can drop the just-added source or lose the rename. `createKnowledgeBase` also writes its manifest unlocked.
**Root cause:** Manifest read-modify-write isn't uniformly serialized.
**Proposed fix:** Take `lock` around the load+save in `renameKnowledgeBase` (and `createKnowledgeBase`).
**Status:** Open

### Bug 40 — Severity: LOW — Category: loadKb throws on missing/corrupt manifest
**Location:** Knowledge.kt:317-320 (`loadKb` calls `File(kbDir, MANIFEST).readText()` then `gson.fromJson(...)`)
**Symptom:** `loadKb` can throw (FileNotFound / parse). `listKnowledgeBases`/`loadKnowledgeBase` wrap it in `runCatching`, but `saveSource`/`deleteSource`/`renameKnowledgeBase` call `loadKb(kbDir)` directly inside the mutation with no catch — a corrupt manifest throws out of the public API.
**Root cause:** Inconsistent error handling around `loadKb`.
**Proposed fix:** Wrap `loadKb` callers uniformly, or have `loadKb` return null on failure.
**Status:** Open

### Bug 41 — Severity: LOW — Category: embedding dim-mismatch retained silently
**Location:** Knowledge.kt:218-231 (`newDim` else branch)
**Symptom:** When a re-index produces a different embedding dim than the KB manifest, the new chunks are still saved (with the mismatched dim) while the manifest keeps the old dim. Retrieval then silently zero-scores those chunks (`cosine` dim-mismatch → 0.0). Only a logcat warning surfaces it.
**Root cause:** Mixed-embedder chunks are persisted rather than refused.
**Proposed fix:** Reject the save (surface an error on the source row) when dim ≠ manifest dim, rather than persisting unretrievable chunks.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/KnowledgeService.kt

### Bug 42 — Severity: MEDIUM — Category: retrieval char-budget skip leaves topK unfilled
**Location:** KnowledgeService.kt:283-289
**Symptom:** The budget loop uses `continue` when a candidate would exceed `maxContextChars`, scanning on for smaller chunks. A single oversized top-scoring chunk is skipped while lower-scoring small chunks fill the budget — the most relevant content can be dropped entirely from the injected context.
**Root cause:** Greedy budget fill prefers many small chunks over the single most-relevant large one.
**Proposed fix:** If the top hit alone exceeds the budget, truncate it to fit rather than skipping it; or reserve budget for the top-ranked hit first.
**Status:** Open

### Bug 43 — Severity: LOW — Category: retrieve embedder-mismatch warns but uses first KB's embedder
**Location:** KnowledgeService.kt:217-219, 243-244
**Symptom:** When attached KBs disagree on embedder, the code logs a warning, embeds the query with the *first* KB's embedder, and silently skips every mismatched KB's chunks (`continue`). The user attached multiple KBs but only gets hits from those sharing KB[0]'s embedder, with no UI signal.
**Root cause:** Mismatch handling is log-only.
**Proposed fix:** Surface a user-visible warning, or embed the query per-embedder-group.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/local/LocalEmbedder.kt

### Bug 44 — Severity: LOW — Category: download writes whatever the server returns
**Location:** LocalEmbedder.kt:89-125 (`download`)
**Symptom:** `download` never inspects HTTP response code or content type; it streams `conn.inputStream` to the `.tflite`. `HttpURLConnection.getInputStream()` throws for ≥400, so a 404 is caught — but a 200 returning an HTML interstitial/redirect page (CDN auth wall) would be written as a `.tflite` and later fail opaquely at `TextEmbedder.createFromOptions`.
**Root cause:** No content validation post-download.
**Proposed fix:** Verify `conn.responseCode == 200` and a plausible content-type/length before committing the atomic move.
**Status:** Open

### Bug 45 — Severity: LOW — Category: download progress total may be wrong for chunked responses
**Location:** LocalEmbedder.kt:96 (`conn.contentLengthLong`)
**Symptom:** When the server omits Content-Length (chunked), `total` is -1 and the progress UI can't show a percentage; not a defect per se but the callback contract (`total may be -1`) relies on UI handling it.
**Proposed fix:** Documented behavior; no code change needed beyond UI awareness.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/local/LocalLlm.kt

### Bug 46 — Severity: LOW — Category: serialized generate blocks all callers of one engine
**Location:** LocalLlm.kt:160-177 (`synchronized(engine) { engine.generateResponse(prompt) }`)
**Symptom:** All generate calls for one model serialize on the engine monitor (required — native handle isn't thread-safe). A report fan-out using the Local provider across many agents pointing at the same `.task` runs strictly sequentially, and a long generation blocks the monitor; callers can't be cancelled mid-generate (`generateResponse` is a blocking native call ignoring coroutine cancellation).
**Root cause:** MediaPipe LlmInference has no streaming/cancel API here.
**Proposed fix:** Documented limitation; consider a timeout wrapper so a wedged native call doesn't pin the monitor forever.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ModelCooldownStore.kt

### Bug 47 — Severity: LOW — Category: cooldown caption year-agnostic same-day check
**Location:** ModelCooldownStore.kt:149-157 (`cooldownCaption`)
**Symptom:** `sameDay` compares only `DAY_OF_YEAR` without the year, so a cooldown exactly one year out on the same day-of-year would render as today's `HH:mm` (no date), misleading the user about when the model returns.
**Root cause:** Day-of-year compared without year.
**Proposed fix:** Compare year + day-of-year (or compare truncated-to-day epoch).
**Status:** Open

### Bug 48 — Severity: LOW — Category: isUnavailable mutates + persists on read (hot path)
**Location:** ModelCooldownStore.kt:88-99 (`isUnavailable` lazy-expire)
**Symptom:** Every `isUnavailable` call that finds an expired entry does `remove`+`persist`+`publish` — a SharedPreferences write and StateFlow emission triggered from what callers treat as a cheap read (model pickers call this per row).
**Root cause:** Lazy expiry side-effects on a read path.
**Proposed fix:** Make `isUnavailable` pure (compare timestamp only); prune in `init`/a periodic sweep.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/PromptCache.kt

### Bug 49 — Severity: LOW — Category: TTL expiry deletes inside read
**Location:** PromptCache.kt:65-81 (`get`)
**Symptom:** `get` deletes the file when stale, inside a read call. Concurrent `getRaw` (non-destructive read used by the 1-week View path) could race the delete: `getRaw` opens the file just before `get` deletes it. Both are under `lock`, so serialized — but a stale 48h entry the View screen wanted (with its own 1-week window) gets deleted out from under it by any `get` caller.
**Root cause:** Two readers with different TTL semantics share a store where one mutates.
**Proposed fix:** `get` should not delete; expiry should be a separate sweep so the longer-TTL `getRaw` consumers aren't undercut.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/EmbeddingsStore.kt

### Bug 50 — Severity: LOW — Category: unused doc-cache (dead-ish) + no concurrency lock
**Location:** EmbeddingsStore.kt:55-71 (`put`), 45-53 (`get`)
**Symptom:** `get`/`put` have no lock; concurrent `put` to the same cache key uses the shared `<name>.tmp` (Bug 23) and can interleave. Cache key includes the content hash so collisions are unlikely, but two simultaneous puts of the SAME (docId,provider,model,content) race the tmp file.
**Root cause:** Shared tmp filename + no per-key serialization.
**Proposed fix:** Unique tmp name (see Bug 23).
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/BackupManager.kt

### Bug 51 — Severity: MEDIUM — Category: prefs clear()+restore loses keys not in backup version
**Location:** BackupManager.kt:499-519 (`applyPrefs` → `edit().clear()...commit()`)
**Symptom:** Restore does `clear()` then re-puts only the entries present in the backup JSON. Restoring an *older* backup onto a newer install wipes any new prefs keys the newer app version introduced (they aren't in the old backup), then commits — the new keys are gone and the app starts with defaults for them. Since restore force-kills the process afterward, defaults stick.
**Root cause:** Full clear before selective restore, with no version-aware merge.
**Proposed fix:** Restore-merge rather than clear-then-restore for forward compatibility, or only clear keys the backup is authoritative for.
**Status:** Open

### Bug 52 — Severity: LOW — Category: full-payload staging into heap
**Location:** BackupManager.kt:277-328 (`readAllEntriesValidated`)
**Symptom:** The entire uncompressed backup (up to 1 GB cap) is staged into a `LinkedHashMap<String, ByteArray>` in heap before any file is written. A large RAG corpus backup can OOM well below the 1 GB cap on a low-RAM device.
**Root cause:** Validate-then-write requires holding everything in memory.
**Proposed fix:** Validate by a first scan pass (counting bytes, checking entries) without retaining bytes, then a second pass that streams each entry to disk — or stage to temp files rather than heap.
**Status:** Open

### Bug 53 — Severity: LOW — Category: applyPrefs putString(null) removes key
**Location:** BackupManager.kt:508 (`"s" -> editor.putString(k, m["v"] as? String)`)
**Symptom:** A backed-up string entry whose value was null (or fails the cast) calls `putString(k, null)`, which removes the key rather than storing an empty/null marker. Round-trip of a genuinely-null string key isn't faithful.
**Root cause:** Null string handling.
**Proposed fix:** Skip null-valued string entries explicitly, or store a discriminator.
**Status:** Open

---

## File: ai/src/main/java/com/ai/viewmodel/AppViewModel.kt

### Bug 54 — Severity: MEDIUM — Category: refreshAll read-after-update race on aiSettings
**Location:** AppViewModel.kt:2020-2023, 2049-2052
**Symptom:** Each parallel provider `async` does `_uiState.update { withModels(...) }` then immediately reads `_uiState.value.aiSettings.getProvider(service)` to persist. Between the update and the read, another concurrent provider's update replaces `aiSettings`. The read is per-provider so it usually picks up its own provider's models, but `applyOpenRouterTypes()` cross-pollination + the final pass reading `_uiState.value` after all updates can persist an intermediate cross-provider state.
**Root cause:** update-then-read on a shared MutableStateFlow under concurrency, rather than capturing the post-update snapshot atomically.
**Proposed fix:** Have `_uiState.update` return/compute the value to persist within the same lambda, or collect per-provider results and apply persistence serially after `awaitAll`.
**Status:** Open

### Bug 55 — Severity: LOW — Category: estimateTokens crude length/4 heuristic
**Location:** AppViewModel.kt:2452 (`estimateTokens = text.length / 4`)
**Symptom:** Usage stats for streaming chat and dual chat use `length/4` token estimates that diverge widely from real tokenization (CJK, code, long words), so the per-model usage stats and any cost derived from them are inaccurate.
**Root cause:** No tokenizer; rough heuristic.
**Proposed fix:** Acceptable for stats; document, or prefer provider-reported usage where available.
**Status:** Open

### Bug 56 — Severity: LOW — Category: onCleared GlobalScope leak risk
**Location:** AppViewModel.kt:883-885
**Symptom:** `onCleared` launches a `GlobalScope.launch(IO + NonCancellable)` to flush usage stats. If the process is dying, this is best-effort; but `GlobalScope` work is unmanaged and on rapid recreate/destroy cycles can stack up flushes racing the same prefs file.
**Root cause:** Unscoped survival work.
**Proposed fix:** Use a process-lifetime application scope; ensure flush is idempotent.
**Status:** Open

---

## File: ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt

### Bug 57 — Severity: MEDIUM — Category: positional-arg bug — prompt stored as requestHeaders
**Location:** ReportViewModel.kt:604 (`ReportStorage.markAgentRunningAsync(context, reportId, task.resultId, aiPrompt)`)
**Symptom:** `markAgentRunningAsync(context, reportId, agentId, requestHeaders = null, requestBody = null)` — the 4th positional argument is `requestHeaders`, so `aiPrompt` (the resolved prompt text) is stored in the agent's `requestHeaders` field, leaving `requestBody` empty. The per-model viewer's "request headers" pane then shows the prompt text and the "request body" pane is blank.
**Root cause:** Positional call put `aiPrompt` in the headers slot instead of `requestBody = aiPrompt`.
**Proposed fix:** Call with a named argument: `markAgentRunningAsync(context, reportId, task.resultId, requestBody = aiPrompt)`.
**Status:** Open

---

## File: ai/src/main/java/com/ai/viewmodel/RegenerateBatchEngine.kt

### Bug 58 — Severity: MEDIUM — Category: non-atomic job read-modify-write (lost update)
**Location:** RegenerateBatchEngine.kt:598-606 (`mutateJob`), 154-168 (`cancel`), 290-336 (`awaitPhaseCompletion`)
**Symptom:** `mutateJob` does `RegenerateBatchStorage.get` → mutate → `save` with no cross-call lock. `cancel` (on one coroutine) sets status=CANCELLED while the orchestrator's `awaitPhaseCompletion` (on another coroutine) is mid-`mutateJob` from a stale RUNNING snapshot — the orchestrator's save can overwrite the CANCELLED status back to RUNNING/in-progress task states, resurrecting a cancelled batch.
**Root cause:** Engine-level read-modify-write isn't serialized; per-file storage locks only the individual get/save, not the compound operation.
**Proposed fix:** Serialize all job mutations for a reportId (per-report Mutex), or re-check status inside `mutateJob` and refuse to write over a terminal state.
**Status:** Open

### Bug 59 — Severity: LOW — Category: reconcile runs outside a coroutine, races launches
**Location:** RegenerateBatchEngine.kt:177-203 (`reconcile`), 122-149 (`restart`), 217-234 (`startOrchestrator`)
**Symptom:** `reconcile` (called synchronously from the 30 s sweep) reads/mutates `orchestratorJobs` + `_jobs` and may call `startOrchestrator`, while `restart` does the same from a `viewModelScope.launch`. The `orchestratorJobs[reportId]?.isActive == true` check is check-then-act on a ConcurrentHashMap — two near-simultaneous callers can both pass the guard and each `startOrchestrator` (cancel-prev + relaunch), double-incrementing `activeSecondaryBatches` (the cancelled job's finally decrements, but timing windows can transiently mis-count).
**Root cause:** Non-atomic start guard across two entry points on different threads.
**Proposed fix:** Use `compute`/`putIfAbsent` on `orchestratorJobs` to make start atomic; or run reconcile on viewModelScope.
**Status:** Open

### Bug 60 — Severity: LOW — Category: phase timeout pauses on rowIds.first()
**Location:** RegenerateBatchEngine.kt:298-302
**Symptom:** On a 30-minute phase timeout, the batch pauses attributing the error to `rowIds.first()` (an arbitrary row), even if that row actually succeeded — the Restart-no-op guard then keys on that wrong row's error state.
**Root cause:** Timeout has no real culprit row; first is a placeholder.
**Proposed fix:** Pause without a row attribution (null pausedOnRowId) so Restart isn't gated on an unrelated row's state.
**Status:** Open

---

## File: ai/src/main/java/com/ai/viewmodel/SecondaryRunManager.kt

### Bug 61 — Severity: LOW — Category: per-host fairness mutex held across delay loop
**Location:** ReportViewModelHelpers.kt:179-208 (`acquireOrRequeue`) used by SecondaryRunManager.kt:461-465
**Symptom:** `acquireOrRequeue` holds the per-host `Mutex` for the entire poll/`delay` loop (up to 10 s per iteration). When a host is saturated, every pair for that host serializes behind the mutex and only one probes `tryAcquire` at a time. This is the intended FIFO fairness, but it means a single slow-to-clear host can make all its pairs effectively single-file even when the per-host concurrency cap is >1 (each acquirer releases the mutex immediately after acquiring, so the cap is still used — but the *probe* serialization adds latency proportional to queue depth × poll interval).
**Root cause:** Fairness via long-held mutex over a polling loop.
**Proposed fix:** Acceptable per design intent; if latency matters, shorten the max poll interval or use a condition/await on slot availability instead of polling.
**Status:** Open

### Bug 62 — Severity: LOW — Category: ad-hoc per-host Semaphore(1) fallback
**Location:** SecondaryRunManager.kt:442-443 (`perHostCaps[host] ?: Semaphore(1)`)
**Symptom:** If `perHostCaps` lacks an entry for a host (e.g. host string computed differently than at map-build time, or a blank host from a malformed baseUrl), the fallback `Semaphore(1)` silently forces single concurrency for that host — a stealth throttle the user can't see or change.
**Root cause:** Defensive fallback hides a key-mismatch bug.
**Proposed fix:** Build the host key once and assert membership; log when the fallback fires.
**Status:** Open

---

## File: ai/src/main/java/com/ai/viewmodel/ChatViewModel.kt

### Bug 63 — Severity: LOW — Category: RAG context merged into existing system message unconditionally
**Location:** ChatViewModel.kt:102-110 (`messagesWithRag`)
**Symptom:** The RAG context block is appended to the user's existing system prompt with `existing.content + "\n\n" + ctx`. For a very long system prompt + large retrieved context, this can push the request over the model's context window with no truncation, surfacing as an opaque API error rather than a graceful RAG trim.
**Root cause:** No budget reconciliation between system prompt size and injected context.
**Proposed fix:** Cap combined system+context size, or reduce `maxContextChars` when the system prompt is already large.
**Status:** Open

---

## File: ai/src/main/java/com/ai/viewmodel/ModelTestEngine.kt

### Bug 64 — Severity: LOW — Category: suppressInlineRetry relies on TagPropagatingExecutor leak-free behavior
**Location:** ModelTestEngine.kt:493-494 (`asContextElement(true)` for permitPreAcquired + suppressInlineRetry)
**Symptom:** The "Test all models" sweep sets both flags via `asContextElement(true)`. These propagate onto OkHttp workers via `TagPropagatingExecutor`, which has the leak bug (Bug 12) for the *false* case. A test sweep that sets `suppressInlineRetry=true` on a pooled worker, followed by a non-test call reusing that worker, can leave the worker with `suppressInlineRetry=true` → the non-test call skips its legitimate 429/529 retries.
**Root cause:** Same root cause as Bug 12; this is the concrete blast radius for the test engine.
**Proposed fix:** Fix Bug 12 (always restore the flag, even when captured was false).
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ProviderThrottling.kt

### Bug 65 — Severity: LOW — Category: rate-window slot counted even when concurrency-blocked
**Location:** ProviderThrottling.kt:148-180 (`acquire`)
**Symptom:** `acquire` adds a timestamp to the per-minute window on admission, THEN blocks on the concurrency semaphore. A call that waits a long time on the concurrency gate has already "spent" its rate-window slot; if it ultimately fails/cancels, the slot stays consumed for the full 60 s (over-throttling). The doc comment acknowledges this as the "safe direction", but for a small per-minute cap it can starve legitimate calls.
**Root cause:** Rate accounting precedes concurrency acquisition.
**Proposed fix:** Acceptable per documented intent; could move the window add to after the concurrency permit is held.
**Status:** Open

### Bug 66 — Severity: LOW — Category: resetForNewLimits transient over-cap
**Location:** ProviderThrottling.kt:234-237 (`resetForNewLimits` clears sems/windows)
**Symptom:** Clearing the maps while in-flight calls hold permits on the old (now-orphaned) semaphores means new acquires build fresh semaphores; briefly the host can exceed the new cap by up to the old cap. Documented and accepted, but worth noting it can also momentarily exceed an intentionally-lowered cap right after the user reduces it.
**Root cause:** Semaphore rebuild rather than resize.
**Proposed fix:** Documented; acceptable.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ApiClient.kt

### Bug 67 — Severity: LOW — Category: fetchUrlAsString swallows all errors → silent null
**Location:** ApiClient.kt:262-270 (`fetchUrlAsString`)
**Symptom:** The raw-snapshot helper returns null on any failure (non-2xx, network, parse) with no logging. The model-list snapshot then stores null silently — a user wondering why their raw model JSON isn't captured has no breadcrumb.
**Root cause:** Blanket `catch (_: Exception) { null }` with no log.
**Proposed fix:** Log at WARN on failure (it goes through TracingInterceptor for the request, but the swallow loses the outcome).
**Status:** Open

### Bug 68 — Severity: LOW — Category: retrofit cache keyed only by baseUrl, shared client
**Location:** ApiClient.kt:247-256 (`getRetrofit` cache)
**Symptom:** Retrofit instances are cached by normalized baseUrl and share one OkHttpClient. Per-call timeout overrides rely entirely on the interceptor `withReadTimeout`/`withConnectTimeout` chain mutation; if any future code path bypasses those interceptors it inherits the 10-minute streaming read timeout. Not a current bug but a fragility note.
**Proposed fix:** Documented; ensure all new call paths flow through the interceptor stack.
**Status:** Open

---

## File: ai/src/main/java/com/ai/viewmodel/IconGenerationManager.kt

### Bug 69 — Severity: LOW — Category: large file / many independent fan-out flows share ApiCallCaps
**Location:** IconGenerationManager.kt (fan-icons batches) + ApiTracer.kt:360-393 (`ApiCallCaps`)
**Symptom:** Icon fan-out uses the shared `ApiCallCaps.fanIcons` semaphore (default 15) plus the global cap. When multiple reports run icon batches concurrently, they contend on the single process-wide `fanIcons` cap, so per-report progress can stall behind another report's icon batch with no per-report fairness — purely a throughput characteristic, but surprising to a user running two reports.
**Root cause:** Process-wide kind caps with no per-report sub-allocation.
**Proposed fix:** Documented design; acceptable.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/AnalysisRepository.kt

### Bug 70 — Severity: MEDIUM — Category: response/request headers stored without redaction
**Location:** AnalysisRepository.kt:104-108 (`formatHeaders`)
**Symptom:** `formatHeaders` serializes all headers verbatim into the report agent's `requestHeaders`/`responseHeaders` fields (and these persist in the report JSON, which is included in backups). Unlike `TracingInterceptor.headersToMap`, there is no `isSensitiveHeader` redaction here, so any auth-bearing header captured through this path lands in plaintext on disk and in backups.
**Root cause:** Report-agent header capture doesn't reuse the tracer's redaction.
**Proposed fix:** Redact sensitive headers (Authorization, x-api-key, x-goog-api-key, api-key, etc.) in `formatHeaders` before persisting.
**Status:** Open

### Bug 71 — Severity: LOW — Category: mergeParameters returnCitations AND-logic surprise
**Location:** AnalysisRepository.kt:158 (`returnCitations = overrideParams.returnCitations && agentParams.returnCitations`)
**Symptom:** `returnCitations` defaults to true; the merge uses AND so if either side is false, citations are off. An override that simply left the field at its default `true` won't re-enable citations for an agent — but the inverse is the documented intent. The asymmetry vs the OR'd boolean flags (searchEnabled/webSearchTool) is subtle and easy to get wrong on future edits.
**Root cause:** One default-true boolean merged with AND while siblings use OR.
**Proposed fix:** Documented in the comment; consider a tri-state (null = inherit) to remove the ambiguity.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ModelListCache.kt

### Bug 72 — Severity: LOW — Category: read/save not serialized (atomic-write mitigates)
**Location:** ModelListCache.kt:45-78 (`save`/`read`)
**Symptom:** No lock around save/read; relies solely on `writeTextAtomic`'s rename for consistency. With the shared `<name>.tmp` (Bug 23), two concurrent saves for the *same* provider id race the tmp file. Concurrent same-provider refreshes are rare but possible (manual Test + Refresh-all overlap).
**Root cause:** Shared tmp filename under concurrency.
**Proposed fix:** Unique tmp name (Bug 23) or per-id lock.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/RegenerateBatchStorage.kt

### Bug 73 — Severity: LOW — Category: get/save/delete locked individually, not the compound op
**Location:** RegenerateBatchStorage.kt:47-75
**Symptom:** Each `get`/`save`/`delete` takes the lock, but the engine's `mutateJob` (Bug 58) does get→mutate→save as three separate locked operations — the store's locking gives no compound atomicity, enabling the lost-update in Bug 58.
**Root cause:** Storage offers only per-call atomicity.
**Proposed fix:** Expose an `update(reportId) { job -> newJob }` that holds the lock across read-modify-write.
**Status:** Open

---

## File: ai/src/main/java/com/ai/viewmodel/TranslationRunManager.kt

### Bug 74 — Severity: LOW — Category: same acquireOrRequeue/permit pattern, inherits Bug 12/61 exposure
**Location:** TranslationRunManager.kt (per-target translation fan-out, mirrors SecondaryRunManager)
**Symptom:** The translation dispatcher uses the same `permitPreAcquired.asContextElement(true)` + `acquireOrRequeue` pattern, so it inherits the ThreadLocal-leak exposure (Bug 12) and the fairness-mutex latency (Bug 61). A translation fan-out leaving `permitPreAcquired=true` on a pooled worker can leak into a subsequent unrelated call's throttle decision.
**Root cause:** Shared pattern + Bug 12 root cause.
**Proposed fix:** Fix Bug 12; no per-file change needed.
**Status:** Open

---

## File: ai/src/main/java/com/ai/model/SettingsModels.kt / SettingsHolder.kt

### Bug 75 — Severity: LOW — Category: SettingsHolder.current published via collect (eventual consistency)
**Location:** AppViewModel.kt:873-875 (`uiState.collect { SettingsHolder.current = it.aiSettings }`); read in ApiDispatch.kt:1191 (`isReasoningCapableForDispatch`)
**Symptom:** `SettingsHolder.current` is updated asynchronously by a collector on `uiState`. Dispatch code reading `SettingsHolder.current` (for the reasoning-effort capability gate) can observe a slightly-stale Settings if a dispatch fires in the window between a settings change and the collector running. Effect: a just-toggled "accepts reasoning_effort" capability may not apply to an in-flight call.
**Root cause:** Publish-on-collect rather than synchronous write on settings change.
**Proposed fix:** Set `SettingsHolder.current` synchronously in the same place settings are updated (the `_uiState.update` for aiSettings), not only via the collector.
**Status:** Open

### Bug 76 — Severity: LOW — Category: Gson non-null fields in Settings/Agent (same class as Bug 1)
**Location:** SettingsModels.kt (data classes persisted via Gson)
**Symptom:** Settings/Agent/AgentParameters persisted and restored via `createAppGson` share Bug 1's exposure: a restored/imported settings blob missing a non-null field yields a null in a non-null Kotlin property, NPE-ing capability recompute or dispatch param building.
**Root cause:** Same Gson-unsafe-allocation issue as Bug 1.
**Proposed fix:** Fix at the Gson layer (Bug 1).
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/InternalPromptSeed.kt / ExamplePromptSeed.kt / SystemPromptSeed.kt / InaccessibleSeed.kt / TestExcludedSeed.kt

### Bug 77 — Severity: LOW — Category: seed parse returns emptyList on any failure (silent)
**Location:** InternalPromptSeed.kt:42-65 (`loadFromAssets` → `?: emptyList()` / catch emptyList)
**Symptom:** A malformed bundled seed asset (or a deserialization that leaves non-null Entry fields null per Bug 1) yields an empty list silently, so the app boots with zero internal/example/system prompts and the user sees missing prompts with no error. Affects all five seed loaders that share this pattern.
**Root cause:** Blanket catch → emptyList with no surfaced error.
**Proposed fix:** Log at ERROR (so a packaging mistake is diagnosable); validate required fields after parse.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ApiDispatch.kt (Anthropic / Gemini text-only response coalescing)

### Bug 78 — Severity: LOW — Category: Anthropic/Responses text join drops non-text tool output ordering
**Location:** ApiDispatch.kt:377-381 (`analyzeAnthropic` text join), 1067-1084 (`extractResponsesApiContent`)
**Symptom:** Text blocks are joined with empty separator across the whole response, interleaving pre-tool and post-tool text without any boundary marker. For web-search answers this is usually fine, but a model that emits two distinct answer segments around a tool call gets them concatenated with no whitespace, occasionally fusing two sentences ("…I'll search.Based on results…").
**Root cause:** `joinToString(separator = "")` assumes the model always supplies its own boundary whitespace.
**Proposed fix:** Join with no separator only within a block; insert a single space/newline between distinct text blocks if neither side has boundary whitespace.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/ApiDispatch.kt (buildChatUrl)

### Bug 79 — Severity: LOW — Category: buildChatUrl suffix match can strip wrong tail
**Location:** ApiDispatch.kt:1102-1121 (`buildChatUrl`)
**Symptom:** `buildChatUrl` checks `trimmedUrl.endsWith(cleanedChatPath)` (without a leading slash) at line 1110, so a base URL ending in a longer path that merely *ends with* the chat path substring (e.g. base `…/myv1/chat/completions` vs chatPath `v1/chat/completions`) would be treated as already-terminated and not get the path appended, or the alternate-path strip could remove a partial segment.
**Root cause:** Substring `endsWith` without a path-separator boundary on the non-slash branch.
**Proposed fix:** Require a `/` boundary (`endsWith("/$cleanedChatPath")`) for the match, dropping the bare `endsWith(cleanedChatPath)` branch.
**Status:** Open

---

## File: ai/src/main/java/com/ai/viewmodel/RegenerateBatchEngine.kt (UI counter)

### Bug 80 — Severity: LOW — Category: activeSecondaryBatches counter drift on overlapping start/cancel
**Location:** RegenerateBatchEngine.kt:219-233 (start increments), 227-231 (finally decrements), 164-166 (cancel decrements)
**Symptom:** `startOrchestrator` increments `activeSecondaryBatches`, the launched job's finally decrements; `cancel` ALSO decrements directly. If `cancel` runs and decrements, and the cancelled orchestrator job's finally ALSO decrements, the counter double-decrements (coerced ≥0, so it floors at 0 but can under-count concurrent batches), causing the "batches running" UI badge to show fewer than actual.
**Root cause:** Two code paths decrement for the same logical batch end.
**Proposed fix:** Decrement only in the orchestrator job's finally; cancel should just `.cancel()` and let the finally run.
**Status:** Open

---

## Summary

Total findings: **80**

Severity breakdown:
- CRITICAL: 1 (Bug 1 — Gson non-null deserialization NPE class)
- HIGH: 4 (Bugs 5, 12, 27, 35)
- MEDIUM: 24 (Bugs 2, 4, 7, 8, 11, 13, 16, 17, 20, 21, 25, 28, 29, 31, 34, 36, 39, 42, 51, 54, 57, 58, 70, 73)
- LOW: 51 (the remainder)

Cross-cutting themes worth prioritizing:
1. **Gson non-null deserialization (Bug 1, 30, 76, 77)** — a single root-cause fix at `createAppGson` hardens reports, settings, seeds, KB manifests, and chat sessions against restore/import NPEs.
2. **ThreadLocal flag leak on pooled OkHttp workers (Bug 12, 64, 74)** — one fix in `TagPropagatingExecutor` removes the throttle-bypass and retry-suppression leakage across the test engine, fan-out, and translation flows.
3. **Non-atomic read-modify-write on persisted job/report state (Bug 27, 58, 73, 80)** — per-entity locking / compound update API closes lost-update and counter-drift windows.
4. **Pricing precedence inconsistency (Bug 35) + missing-tier memoization (Bug 36)** — correctness + hot-path performance in the cost layer.
5. **Secret redaction gaps in persisted data (Bug 17 trace body, Bug 70 report headers)** — both land in the backup zip in plaintext.
