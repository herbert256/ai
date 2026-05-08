# Deep Code Review — Data Layer / View-Models / Infrastructure

## File: ai/src/main/java/com/ai/data/ApiStreaming.kt

### Bug 1 — Severity: HIGH — Category: SSE / streaming
**Location:** lines 53-89 (parseSseStream)
**Symptom:** Multi-line `data:` fields (legitimate per W3C SSE spec) are misinterpreted as separate events; concatenation never happens. Some providers split JSON across two `data:` lines.
**Root cause:** The parser treats every `data:` line as a complete event. SSE spec says successive `data:` lines should be joined with `\n` and dispatched on a blank line. This parser dispatches on each `data:` line immediately and resets only on blank lines.
**Reproduction:** Server sends `data: {"a":1\ndata:  ,"b":2}` followed by blank line. Current code parses the first data line as `{"a":1` (broken JSON), drops it via the silent `catch (_: Exception) { null }`, and never reassembles the full payload.
**Proposed fix:** Buffer `data:` lines into a StringBuilder per event and only call `extractContent` when a blank line terminates the event.
**Status:** Fixed

### Bug 2 — Severity: HIGH — Category: SSE / streaming
**Location:** line 59 (parseSseStream)
**Symptom:** SSE event field state leaks across the comment line: `eventType` is reset on blank lines, but subsequent `data:` arrives with `eventType=null` even when the event-type was set on a previous event that didn't have a blank-line separator.
**Root cause:** Reset of `eventType` happens only on `currentLine.isBlank()`. Per SSE spec, each event ends at a blank line, but the parser also resets eventType THERE. If a server emits `event: foo\n` then `data: ...\n` without a trailing blank line, we still process the data with the right event. But after that data line is processed, eventType remains "foo" and the NEXT event (without an `event:` prefix, defaulting to "message") would erroneously be tagged "foo".
**Proposed fix:** Reset `eventType` after dispatching each event (in the data branch) per spec, not only on the blank-line terminator.
**Status:** Fixed

### Bug 3 — Severity: HIGH — Category: SSE / streaming
**Location:** lines 86-88
**Symptom:** A clean TCP close after a successful response that didn't ship the format-specific terminator throws "ended without terminator" — turning a healthy partial-but-complete response into an error.
**Root cause:** The throw at line 87 fires for every Anthropic stream that finished cleanly on a TCP RST but missed the message_stop event.
**Proposed fix:** Treat clean EOF as end-of-stream; only throw on explicit truncation signals (Content-Length mismatch). Or downgrade to a logged warning.
**Status:** Skip — graceful EOF only; truncation surfaces a real signal

### Bug 4 — Severity: MEDIUM — Category: Charset / encoding
**Location:** line 52 (parseSseStream)
**Symptom:** Multi-byte UTF-8 chunks split across HTTP packet boundaries can corrupt characters when `body.charStream()` decodes lazily.
**Root cause:** `body.charStream().buffered()` defers to OkHttp's source charset; on a streaming response without explicit `charset=utf-8`, the default may not be UTF-8. UTF-8 multi-byte chars split across an OkHttp segment boundary may produce U+FFFD.
**Proposed fix:** Force UTF-8: `body.source().inputStream().reader(Charsets.UTF_8).buffered()`.
**Status:** Fixed

### Bug 5 — Severity: MEDIUM — Category: Coroutine lifetime / streaming
**Location:** lines 185, 208 (streamOpenAi unsuccessful paths)
**Symptom:** When the streaming response is unsuccessful, the error body is never consumed; the OkHttp connection leaks.
**Root cause:** Throws `Exception("API error: ...")` without calling `errorBody()?.string()` or closing the body. Compare streamAnthropic (line 237) which does close it.
**Proposed fix:** Always consume the body (or call `response.errorBody()?.close()` explicitly) before throwing.
**Status:** Fixed

### Bug 6 — Severity: MEDIUM — Category: SSE / streaming
**Location:** line 79
**Symptom:** "[DONE]" comparison is case-sensitive — providers sending `data: [done]` are treated as data, not terminator.
**Proposed fix:** Use `equals("[DONE]", ignoreCase = true)`.
**Status:** Fixed

### Bug 7 — Severity: MEDIUM — Category: SSE / Gemini terminator
**Location:** lines 95-103 (isGeminiFinalChunk)
**Symptom:** When a Gemini stream's connection drops before the final-chunk-with-finishReason arrives, the throw at line 87 turns a partial-but-otherwise-OK response into an error rather than a graceful end.
**Proposed fix:** Don't throw on EOF without terminator — drop that requirement.
**Status:** Skip — same as Bug 3

## File: ai/src/main/java/com/ai/data/ApiTracer.kt

### Bug 8 — Severity: HIGH — Category: I/O / memory
**Location:** lines 240-244 (TracingInterceptor non-streaming body capture)
**Symptom:** `source.request(Long.MAX_VALUE); source.buffer.clone().readUtf8()` reads the entire response body into memory. For a multi-MB JSON response (model lists), this blocks the network thread and doubles memory usage.
**Reproduction:** Enable tracing, fetch a 5 MB OpenRouter model list. App holds 10+ MB in the buffer chain.
**Proposed fix:** Cap captured body size (e.g. at 1 MB) and emit "...truncated" marker.
**Status:** Fixed

### Bug 9 — Severity: HIGH — Category: Concurrency / volatile globals
**Location:** lines 47, 55 (currentReportId, currentCategory) + withTracerTags helper
**Symptom:** `withTracerTags` saves and restores volatile globals — but two parallel coroutines on different threads will see each other's "previous" values and one will restore another's tag, causing tag bleed between threads.
**Root cause:** The save-and-restore inline pattern is not concurrency-safe across overlapping flows on different threads (the doc comment even acknowledges this).
**Reproduction:** Run two report agents in parallel, each in its own withTracerTags. Tags bleed across them.
**Proposed fix:** Use a `kotlinx.coroutines.ThreadLocal` (CoroutineContext element) instead of a volatile global.
**Status:** Open

### Bug 10 — Severity: MEDIUM — Category: File I/O / atomicity
**Location:** lines 73-75 (saveTrace filename)
**Symptom:** `fileSequence.incrementAndGet().toString(36)` is the only collision guard for a per-millisecond timestamp. The sequence is reset on every cold start, so two restarts within the same millisecond can produce colliding filenames; writeTextAtomic then overwrites.
**Proposed fix:** Initialize `fileSequence` from the highest existing trace file's numeric tail, or include a random suffix.
**Status:** Fixed

### Bug 11 — Severity: MEDIUM — Category: Performance
**Location:** line 90 (cachedTraceFiles re-sort)
**Symptom:** Every save re-sorts the entire list (O(n log n)) on each new trace; with 10k traces, every new trace pays a 10k-element resort.
**Proposed fix:** Insert at the right position via binary search or use TreeSet.
**Status:** Fixed

### Bug 12 — Severity: MEDIUM — Category: Tracer / model parsing
**Location:** lines 256-264 (modelFromUrl)
**Symptom:** A path like `/models/anthropic/claude-opus-4` extracts model as `anthropic` only; multi-segment model ids are broken.
**Proposed fix:** Document the assumption (Gemini-only) or extract more carefully.
**Status:** Fixed

### Bug 13 — Severity: HIGH — Category: Coroutine / interceptor
**Location:** lines 289-318 (RateLimitRetryInterceptor)
**Symptom:** `Thread.sleep` blocks an OkHttp dispatcher thread for up to maxRetries × backoffMs (15 s). With OkHttp's default per-host concurrency cap of 5, a single sustained-429 host can block every other call to that host for 15s.
**Proposed fix:** Move retry policy to the application coroutine layer using `delay()`, not the interceptor layer.
**Status:** Open

### Bug 14 — Severity: MEDIUM — Category: Retry policy
**Location:** lines 289-318
**Symptom:** No exponential backoff, no jitter, no respect for the `Retry-After` header. Constant 3s back-off re-triggers the same rate-limit window.
**Proposed fix:** Read `Retry-After`/`X-RateLimit-Reset` and add ±20% jitter.
**Status:** Open

## File: ai/src/main/java/com/ai/data/ApiClient.kt

### Bug 15 — Severity: HIGH — Category: File I/O / leak
**Location:** lines 219-227 (fetchUrlAsString)
**Symptom:** When `resp.isSuccessful` is false, the body is never read; OkHttp's body holds a connection until GC.
**Root cause:** `resp.body?.string()` is called only on the success branch.
**Proposed fix:** Always consume or explicitly `body?.close()`.
**Status:** Fixed

### Bug 16 — Severity: HIGH — Category: TLS / certificate pinning
**Location:** entire ApiFactory
**Symptom:** No certificate pinning for ANY provider — MITM-capable attackers on the network can intercept all API traffic, including API keys.
**Proposed fix:** Add certificate pinning for major providers.
**Status:** Open

## File: ai/src/main/java/com/ai/data/ApiDispatch.kt

### Bug 17 — Severity: HIGH — Category: Anthropic / max_tokens
**Location:** lines 1063-1066 (claudeReasoningBundle)
**Symptom:** When user explicitly sets `params.maxTokens=2000` and effort=high (budget=16384), the bundle silently raises it to 20480, overriding the user's explicit cost cap.
**Root cause:** `if (budget > 0 && baseMax <= budget)` triggers a silent override with no opt-out.
**Reproduction:** User configures strict per-call token limit for cost reasons, then sends with high reasoning effort. Cost cap silently violated.
**Proposed fix:** Surface the conflict (warn / log) and either honor user max (let Anthropic 400) or document the override.
**Status:** Open

### Bug 18 — Severity: HIGH — Category: Responses-API content extraction
**Location:** lines 871-878 (extractResponsesApiContent)
**Symptom:** When multiple text chunks are present (e.g., post-tool-use response), only the first is returned. Compare with analyzeAnthropic (lines 222-226) which joins all text blocks. Inconsistent.
**Reproduction:** Responses-API with web_search returns: pre-search text + tool call + post-search text. User sees only pre-search.
**Proposed fix:** Concatenate all `output_text` chunks across all output items.
**Status:** Open

### Bug 19 — Severity: HIGH — Category: Test connection / URL building
**Location:** lines 776-779 (testApiConnectionWithJson)
**Symptom:** GOOGLE branch: `if (baseUrl.contains("key=")) baseUrl else "$baseUrl${if (baseUrl.contains("?")) "&" else "?"}key=$apiKey"`. The apiKey is appended without URL encoding — a key with `&` or `+` corrupts the URL.
**Proposed fix:** URL-encode the apiKey via `Uri.encode(apiKey)`.
**Status:** Open

### Bug 20 — Severity: HIGH — Category: Vision / empty content
**Location:** lines 942-952 (toOpenAiMessage)
**Symptom:** When `imageBase64 != null` but `content` is blank, the message becomes image-only with no text part — some OpenAI-compatible providers reject such messages (e.g., older qwen-vl).
**Proposed fix:** Add a synthetic blank text part or document the constraint.
**Status:** Open

### Bug 21 — Severity: MEDIUM — Category: Image MIME inference
**Location:** lines 944, 957, 1180 (toOpenAiMessage / toClaudeMessage / toGeminiContent)
**Symptom:** Default MIME `"image/png"` is hardcoded; JPEG attached without MIME (some camera flows) is mistakenly tagged PNG. Server may reject.
**Proposed fix:** Throw rather than guess, or sniff bytes.

### Bug 22 — Severity: MEDIUM — Category: Web-search tool versioning
**Location:** line 1086 (anthropicWebSearchTool)
**Symptom:** Hardcoded `"web_search_20250305"` will be obsolete; dispatch silently keeps using a deprecated tool until manual update.
**Proposed fix:** Make tool version configurable.

### Bug 23 — Severity: HIGH — Category: HTTP error path / response leak
**Location:** lines 762-767 (testApiConnectionWithJson)
**Symptom:** Builds a fresh `OkHttpClient` per call; thread/connection pools are not reused. Many parallel test invocations leak resources.
**Proposed fix:** Use the shared `ApiFactory.okHttpClient` or document the longer test-connection timeout reason.
**Status:** Fixed

### Bug 24 — Severity: HIGH — Category: Provider-prefix bucket
**Location:** lines 690-700 (fetchModelsGemini)
**Symptom:** `m.name?.removePrefix("models/")` — if the API returns model names without `models/` prefix, removePrefix is a no-op. byName keys may collide silently if duplicates exist after stripping.
**Proposed fix:** Check the prefix existed before stripping.
**Status:** Fixed

### Bug 25 — Severity: MEDIUM — Category: Anthropic fetch error path
**Location:** lines 619-633 (fetchModelsAnthropic)
**Symptom:** On parse exception or unsuccessful response, returns `FetchedModels(emptyList(), emptyMap())` silently — caller can't distinguish "Anthropic returned empty" from "we threw".
**Proposed fix:** Propagate error info via FetchedModels.
**Status:** Partial — logs error path; structured propagation deferred

### Bug 26 — Severity: HIGH — Category: AnalysisRepository.embed return type
**Location:** lines 100-121 (embed)
**Symptom:** Returns `List<List<Double>>?` (boxed). Hot RAG path forces boxing on every cosine call. Then the results are converted to FloatArray on the chunk side, producing a needless Float→Double→Float roundtrip.
**Proposed fix:** Return FloatArray directly; align all embedding paths to FloatArray.
**Status:** Open

### Bug 27 — Severity: MEDIUM — Category: Embedding response validation
**Location:** lines 117-119
**Symptom:** Returns null on size mismatch (silent), but a partial response with fewer rows is dropped silently.
**Proposed fix:** Surface partial-response error to caller.
**Status:** Open

## File: ai/src/main/java/com/ai/data/AnalysisRepository.kt

### Bug 28 — Severity: HIGH — Category: Retry / cancellation
**Location:** lines 285-294 (withRetry catch arm)
**Symptom:** Catches all `Exception` (line 285), retrying on bugs like `NullPointerException` in JSON parsing. Real bugs masquerade as transient network errors.
**Proposed fix:** Narrow the catch to IOException / HttpException.
**Status:** Fixed

### Bug 29 — Severity: HIGH — Category: Parameter merging — semantics
**Location:** line 127 (mergeParameters)
**Symptom:** `returnCitations = overrideParams.returnCitations && agentParams.returnCitations`. AND-merging means a non-default override of `false` always wins, but no way to distinguish "unset" from "default true". User opt-in vs. opt-out becomes opaque.
**Proposed fix:** Make `returnCitations` nullable.
**Status:** Open

### Bug 30 — Severity: HIGH — Category: Tool fallback narrow trigger
**Location:** lines 222-234 (Tool fallback)
**Symptom:** Fallback retries WITHOUT `webSearchTool` only when status==400 AND error contains "tool"/"web_search". Other providers may return 422 or different error wording — fallback doesn't fire and user sees a hard error.
**Proposed fix:** Broaden the trigger; parse error code more liberally.
**Status:** Fixed

## File: ai/src/main/java/com/ai/data/ProviderRegistry.kt

### Bug 31 — Severity: HIGH — Category: Provider deserialization / NPE risk
**Location:** lines 144-145 (parseProvidersJson)
**Symptom:** Gson reflection bypasses Kotlin null-safety. A JSON entry missing `displayName` deserializes with `displayName=null`; later AppService method calls NPE.
**Reproduction:** User edits providers.json with malformed entry.
**Proposed fix:** Validate fields after parse and drop entries with missing required values.
**Status:** Fixed

### Bug 32 — Severity: MEDIUM — Category: ProviderRegistry double redundancy
**Location:** lines 24, 152-157 (CopyOnWriteArrayList + synchronized blocks)
**Symptom:** CopyOnWriteArrayList combined with synchronized writes — redundant; the lock alone is sufficient. Confusing for maintainers.

## File: ai/src/main/java/com/ai/data/AppService.kt

### Bug 33 — Severity: MEDIUM — Category: Equality / synthetic LOCAL
**Location:** lines 55-56 (equals/hashCode), 67 (LOCAL)
**Symptom:** Equality is by `id` only. If a custom user provider id happens to be "LOCAL", it shadows the synthetic LOCAL.
**Proposed fix:** Reserve "LOCAL" id in import paths.
**Status:** Open

## File: ai/src/main/java/com/ai/data/ReportStorage.kt

### Bug 34 — Severity: HIGH — Category: File I/O / atomicity / save failure
**Location:** lines 263-273 (saveReport)
**Symptom:** When `writeTextAtomic` returns false (disk full, permission), only a log line; in-memory state diverges from disk. Subsequent `loadReport` returns the old version, silently losing all mutations from the latest updateAgentStatus.
**Proposed fix:** Throw on save failure so callers can react.
**Status:** Fixed

### Bug 35 — Severity: HIGH — Category: Mutable model
**Location:** lines 11-30 (ReportAgent has `var` fields)
**Symptom:** ReportAgent uses `var` fields mutated by updateAgentStatus, encouraging stale-data bugs once a reference escapes the lock.
**Proposed fix:** Make ReportAgent immutable (`val` + `copy()`).
**Status:** Open

### Bug 36 — Severity: HIGH — Category: Cost recomputation race
**Location:** lines 159-160, 354 (totalCost recomputation)
**Symptom:** `totalCost = report.agents.mapNotNull { it.cost }.sum()` — but the prompt-translation cost set via `setReportTotalCost` is overwritten on the next agent update.
**Reproduction:** Translation flow → setReportTotalCost = 5.0 (includes translation cost). User reruns an agent → updateAgentStatus rebuilds totalCost from agents only → 4.5. Translation cost silently lost.
**Proposed fix:** Track non-agent costs in a separate field on Report.
**Status:** Open

### Bug 37 — Severity: MEDIUM — Category: Storage path traversal
**Location:** lines 246-251 (loadReport)
**Symptom:** `File(reportsDir, "$reportId.json")` — if reportId contains `../`, the file path escapes reportsDir. Current callers use UUIDs, but other entry points (URL deep-links) could pass arbitrary reportId.
**Proposed fix:** Add the canonicalPath check used in SecondaryResultStorage.save.

### Bug 38 — Severity: MEDIUM — Category: Cross-singleton lock ordering
**Location:** lines 220-244 (deleteAllReports)
**Symptom:** Holds ReportStorage.lock and calls SecondaryResultStorage.deleteAllForReport which acquires its own lock. Cross-singleton ordering is `ReportStorage → SecondaryResultStorage`. A future reverse-order caller would deadlock.
**Proposed fix:** Document the lock-ordering invariant.

## File: ai/src/main/java/com/ai/data/ChatHistoryManager.kt

### Bug 39 — Severity: HIGH — Category: Cache invalidation race
**Location:** lines 68-75 (deleteSession)
**Symptom:** `File(...).delete()` happens OUTSIDE the lock; cache invalidation is INSIDE. A concurrent `getAllSessions` after delete returned can briefly see the cached list including the deleted session.
**Proposed fix:** Move the delete inside the lock.
**Status:** Fixed

### Bug 40 — Severity: HIGH — Category: Save failure swallowed
**Location:** lines 29-41 (saveSession)
**Symptom:** Returns true unconditionally on the success path even though `writeTextAtomic` returns Boolean — disk-full silently fails as success.
**Proposed fix:** Forward the writeTextAtomic Boolean.
**Status:** Fixed

## File: ai/src/main/java/com/ai/data/SecondaryResult.kt

### Bug 41 — Severity: HIGH — Category: Cache / file mtime resolution
**Location:** lines 165-184 (listForReport with mtime cache)
**Symptom:** mtime cache uses `dir.lastModified()` and `files.size`. Two saves in the same coarse-grained ms (1s on most Android filesystems) with the same file count produce the same key — cache returns stale data. Updating an existing row inside the same ms as the cache build returns the OLD parsed row.
**Reproduction:** Two rapid save() calls on the same row; second read returns the first save's content.
**Proposed fix:** Include a per-save monotonic counter or hash the file list.
**Status:** Fixed

### Bug 42 — Severity: HIGH — Category: Cache invalidation on delete
**Location:** lines 196-202 (delete)
**Symptom:** `File(...).delete()` doesn't invalidate `listCache[reportId]`. Subsequent listForReport returns the cached list including the deleted row.
**Proposed fix:** Invalidate the per-report cache on delete.
**Status:** Fixed

### Bug 43 — Severity: MEDIUM — Category: Cohere rerank score truncation
**Location:** lines 624-637 (rerankResultsToJson)
**Symptom:** `(r.relevance_score * 100).toInt()` — for a relevance_score of 0.9999, gives 99 (truncation), not 100.
**Proposed fix:** Use rounding via `Math.round`.
**Status:** Fixed (this session) — Math.round (was truncating to 99 / 100)

## File: ai/src/main/java/com/ai/data/ModelListCache.kt

### Bug 44 — Severity: MEDIUM — Category: Non-atomic write
**Location:** lines 37-44 (save)
**Symptom:** `fileFor(...).writeText(rawResponse)` is NOT atomic. Process crash mid-write leaves a half-written file; subsequent reads may parse-fail.
**Proposed fix:** Switch to writeTextAtomic.
**Status:** Fixed

### Bug 45 — Severity: HIGH — Category: Path traversal
**Location:** lines 29-30 (fileFor)
**Symptom:** providerId is interpolated directly into the file name. A custom-imported provider id containing `/` writes outside the cache dir.
**Proposed fix:** Sanitize providerId (alphanumeric+dash only) or canonicalPath check.
**Status:** Fixed

## File: ai/src/main/java/com/ai/data/PromptCache.kt

### Bug 46 — Severity: HIGH — Category: Hash collision risk
**Location:** lines 27-31 (keyFor)
**Symptom:** SHA-256(`agentId|prompt`) — `|` may occur in agentId or prompt. agentId="a", prompt="|b" vs agentId="a|", prompt="b" both hash to the same key.
**Proposed fix:** Use a delimiter that cannot appear in either field, or hash separately.
**Status:** Fixed

### Bug 47 — Severity: MEDIUM — Category: Cache invalidation
**Location:** entire file
**Symptom:** No invalidation API on agent settings changes. If user changes the agent's system prompt (which affects responses but not the cache key), stale outputs are returned for 48 h.
**Proposed fix:** Hash the agent's relevant fields (model, system prompt) into the cache key.
**Status:** Open

## File: ai/src/main/java/com/ai/data/EmbeddingsStore.kt

### Bug 48 — Severity: HIGH — Category: Silent dim mismatch
**Location:** lines 68-80 (cosine)
**Symptom:** Returns 0.0 if either vector is empty OR sizes don't match — silent fallback. Caller sees "no relevant hits" when the actual problem is a dim mismatch.
**Proposed fix:** Throw on size mismatch.
**Status:** Partial — logs warning on dim mismatch instead of throwing

### Bug 49 — Severity: MEDIUM — Category: Save error swallowed
**Location:** lines 60-61 (put)
**Symptom:** `writeTextAtomic` returns Boolean — caller ignores it; a failure is logged but invisible to upstream.
**Proposed fix:** Surface failure to caller.
**Status:** Fixed (this session) — log when writeTextAtomic returns false

## File: ai/src/main/java/com/ai/data/AtomicFileWrite.kt

### Bug 50 — Severity: HIGH — Category: Crash safety / fsync
**Location:** lines 19-42 (writeTextAtomic)
**Symptom:** `tmp.writeText(content)` doesn't fsync. After `Files.move(...ATOMIC_MOVE)`, OS buffer may still hold the data. On power loss, the destination may have the OLD content (or empty) — atomic-move-without-fsync isn't crash-safe across power loss on ext4 with delayed allocation.
**Proposed fix:** Open FileChannel on tmp, force(true), then move.
**Status:** Fixed

### Bug 51 — Severity: HIGH — Category: Missing parent dir
**Location:** line 20 (parentFile usage)
**Symptom:** If parent dir doesn't exist, tmp.writeText throws — caught and false returned without recovery attempt.
**Proposed fix:** `parentFile?.mkdirs()` before writing tmp.
**Status:** Fixed

### Bug 52 — Severity: MEDIUM — Category: Tmp file leak
**Location:** lines 21-29
**Symptom:** Failed second-attempt move leaves tmp behind; tmp.delete() in catch may fail (concurrent reader).
**Status:** Open

## File: ai/src/main/java/com/ai/data/BackupManager.kt

### Bug 53 — Severity: CRITICAL — Category: Restore order / atomicity
**Location:** lines 175-211 (restore)
**Symptom:** `clearFilesDirForRestore` deletes filesDir contents BEFORE applyStagedEntries' applyPrefs commit. If the process is killed between the wipe and the prefs commit, prefs are still old, but filesDir is empty — reports gone, settings still pointing to nothing.
**Proposed fix:** Commit prefs FIRST (atomic via commit), then write files. Or use a staged-temp-dir + rename swap at the very end.
**Status:** Fixed

### Bug 54 — Severity: HIGH — Category: Type discriminator forward incompat
**Location:** lines 395-413 (applyPrefs)
**Symptom:** A new type tag added in a future build (e.g., "d" for Double) goes unmatched; that pref entry is silently dropped on restore.
**Proposed fix:** Bump MANIFEST_VERSION on type-set changes; refuse to import unknown tags.
**Status:** Open

### Bug 55 — Severity: HIGH — Category: OOM during restore
**Location:** lines 219-266 (readAllEntriesValidated)
**Symptom:** Stages ALL entries' bytes in memory. A user with many traces and KB chunks (NOT excluded) can produce 200+ MB backup → OOM during restore.
**Proposed fix:** Stream entries to a temp staging directory, then atomic rename swap.
**Status:** Open

### Bug 56 — Severity: HIGH — Category: Provider registry stale state
**Location:** lines 271-316 (applyStagedEntries)
**Symptom:** Restored provider_registry prefs file is written, but ProviderRegistry's in-memory state remains stale until process kill. A non-Housekeeping caller would see divergent state.
**Proposed fix:** Document that callers MUST kill the process; or call ProviderRegistry.resetToDefaults immediately.
**Status:** Open

### Bug 57 — Severity: HIGH — Category: All-bad parse silent wipe
**Location:** lines 395-413 (applyPrefs)
**Symptom:** If the prefs JSON has all-bad entries (still-valid JSON shape but no recognized type tags), commit clears prefs and writes nothing — silent total wipe.
**Proposed fix:** Refuse to apply on any malformed entry; let user see the error.
**Status:** Open

### Bug 58 — Severity: MEDIUM — Category: Cache exclusion overreach
**Location:** lines 117-121 (CACHE_TOPLEVEL_SKIP_PREFIXES)
**Symptom:** `name.startsWith("ai-restore-")` — a user-named cache file with that prefix is skipped from backup.
**Proposed fix:** Use a more specific reserved naming convention.
**Status:** Open

## File: ai/src/main/java/com/ai/data/PricingCache.kt

### Bug 59 — Severity: HIGH — Category: Preload completion not set
**Location:** lines 1309-1314 (ensureLoaded) + 1316 (ensureLoadedLocked)
**Symptom:** `ensureLoadedLocked` is called by both `ensureLoaded` (silent IO call) and `ensureLoadedBlocking`. Only ensureLoadedBlocking flips `preloadCompleted=true`; non-main IO callers load but don't mark complete. So the next main-thread getPricing call still returns DEFAULT_PRICING until `preloadAsync` finishes.
**Proposed fix:** Mark preloadCompleted=true in ensureLoadedLocked itself.
**Status:** Fixed

### Bug 60 — Severity: HIGH — Category: TOGETHER cache key normalization
**Location:** lines 173-175 (findTogetherPricing)
**Symptom:** Uses raw model id, no normalization, no prefix-bucket fallback. A typo in the user's model id misses the cache.
**Proposed fix:** Normalize keys via `normalizeModelId()` and apply prefix-bucket logic.
**Status:** Open

### Bug 61 — Severity: HIGH — Category: Manual override precedence
**Location:** lines 322-351 (getPricing)
**Symptom:** Manual override sits BEHIND LiteLLM/models.dev/etc. A user adding a manual override expecting it to fix LiteLLM's data is confused — the override never wins for known models.
**Proposed fix:** Add a "force override" UI flag, or document precedence prominently.
**Status:** Open

### Bug 62 — Severity: HIGH — Category: Two-field consistency
**Location:** lines 707-716 (fetchLiteLLMPricingOnline)
**Symptom:** `litellmPricing = pricing` then `litellmMeta = meta` — sequential assignments; concurrent reader between them sees new pricing but old meta. Findhmpricing reads litellmPricing without the lock.
**Proposed fix:** Wrap the two-field update in a single struct, atomically reassigned.
**Status:** Open

### Bug 63 — Severity: HIGH — Category: Memo cache unbounded
**Location:** lines 104-105, 110 (litellmMetaLookupCache, modelsDevMetaLookupCache, litellmPricingLookupCache)
**Symptom:** ConcurrentHashMaps grow without bound across the app's lifetime — every (provider, model) miss adds an entry.
**Proposed fix:** LRU-bounded cache.
**Status:** Open

### Bug 64 — Severity: HIGH — Category: Migration data loss
**Location:** lines 1277-1285 (loadBlob)
**Symptom:** Reads from prefs, writes via writeTextAtomic, removes prefs key — but the prefs-key removal at line 1283 happens unconditionally. If writeTextAtomic returns false (disk full), the prefs key is still removed → data loss.
**Proposed fix:** Only remove prefs key on writeTextAtomic success.
**Status:** Fixed (this session) — loadBlob / saveBlob only drop prefs key on success

### Bug 65 — Severity: MEDIUM — Category: LiteLLM fetch bypasses tracing
**Location:** lines 701-723 (fetchLiteLLMPricingOnline)
**Symptom:** `url.openStream()` uses raw java.net.URL, bypassing OkHttp's tracing/timeouts. Other fetches use ApiFactory.fetchUrlAsString — inconsistency.
**Proposed fix:** Use ApiFactory.fetchUrlAsString.
**Status:** Open

### Bug 66 — Severity: MEDIUM — Category: Helicone cross-provider pattern
**Location:** lines 1013-1021 (findHeliconePricing fallback)
**Symptom:** Cross-provider pattern fallback picks ANY pattern matching `target.startsWith(pat)`. A short pattern (`"claude"`) matches every claude-* model, producing wildly wrong rates from an unrelated provider.
**Proposed fix:** Require minimum pattern length (e.g., 4 chars) before allowing cross-provider matching.
**Status:** Fixed (this session) — ≥4-char patterns on cross-provider fallback

## File: ai/src/main/java/com/ai/data/LocalLlm.kt

### Bug 67 — Severity: HIGH — Category: MediaPipe lifecycle / use-after-free
**Location:** lines 102-110 (release / releaseAll)
**Symptom:** When `release(modelName)` is called while a `generate` call is in progress, close() races with native generation — undefined behavior in JNI.
**Reproduction:** User starts a slow on-device LLM call, deletes the model file → release() closes the live engine.
**Proposed fix:** Take the synchronized(engine) lock before close().
**Status:** Open

### Bug 68 — Severity: HIGH — Category: MediaPipe / OOM
**Location:** lines 87-98 (getEngine)
**Symptom:** Each model loaded as a separate LlmInference engine; loading two big models (4 GB Gemma + 2 GB Phi) OOMs the process.
**Proposed fix:** LRU cache with a single live engine, evict-on-load.
**Status:** Open

### Bug 69 — Severity: MEDIUM — Category: Hardcoded MaxTokens
**Location:** line 95 (.setMaxTokens(2048))
**Symptom:** 2048 is too low for many on-device chat scenarios; Gemma supports much larger contexts.
**Proposed fix:** Make configurable via settings.
**Status:** Open

## File: ai/src/main/java/com/ai/data/LocalEmbedder.kt

### Bug 70 — Severity: HIGH — Category: HTTP / TLS / no checksum
**Location:** lines 84-101 (download)
**Symptom:** `java.net.URL.openConnection()` — no certificate pinning. A network MITM could deliver an arbitrary .tflite file which the user then loads. No checksum verification.
**Proposed fix:** Use OkHttp via ApiFactory; consider checksum verification.
**Status:** Open

### Bug 71 — Severity: HIGH — Category: Embedder cache OOM
**Location:** lines 180-192 (getEmbedder)
**Symptom:** Multiple loaded embedders accumulate forever; loading several models drains memory.
**Proposed fix:** LRU cache.
**Status:** Open

### Bug 72 — Severity: MEDIUM — Category: Float boxing roundtrip
**Location:** lines 233-235 (embed)
**Symptom:** Converts FloatArray to List<Double> (boxed). KnowledgeService later converts back to FloatArray. Performance + accuracy loss.
**Proposed fix:** Have LocalEmbedder return FloatArray directly.
**Status:** Open

### Bug 73 — Severity: MEDIUM — Category: Download progress thread
**Location:** lines 96-100 (download progress callback)
**Symptom:** `onProgress(soFar, total)` invoked on whatever thread called download() — UI consumer must marshal back to main; failure to do so updates Compose state from the wrong thread.
**Proposed fix:** Document the callback thread expectation.
**Status:** Open

## File: ai/src/main/java/com/ai/data/Knowledge.kt

### Bug 74 — Severity: HIGH — Category: Data class with FloatArray
**Location:** lines 62-68 (KnowledgeChunk)
**Symptom:** `data class` auto-generated `equals` uses Array reference equality, NOT content. Two chunks with the same content compare unequal if embeddings are different array instances.
**Reproduction:** Any code using `==` to compare chunks gets surprising results.
**Proposed fix:** Override equals/hashCode using `contentEquals` / `contentHashCode`.
**Status:** Fixed

### Bug 75 — Severity: HIGH — Category: clearAll lock
**Location:** lines 145-156 (clearAll)
**Symptom:** No lock — concurrent listKnowledgeBases can iterate while clearAll is mid-deletion, returning partial results / NPEs.
**Proposed fix:** Wrap in lock.withLock.
**Status:** Open

### Bug 76 — Severity: MEDIUM — Category: Streaming chunk parse OOM
**Location:** lines 198-203 (forEachChunk)
**Symptom:** `gson.fromJson(f.readText(), Array<KnowledgeChunk>::class.java)` loads the entire chunks file. For a large source (100+ MB chunk file), OOM.
**Proposed fix:** Use streaming JsonReader for very large files.
**Status:** Open

### Bug 77 — Severity: HIGH — Category: Embedding dim consistency
**Location:** lines 160-175 (saveSource)
**Symptom:** `newDim = if (current.embeddingDim == 0) embeddingDim else current.embeddingDim` — first source's dim wins. Re-indexing the FIRST source with a different model that produces a different dim leaves the OLD dim recorded; subsequent retrieve dims will mismatch with one of the chunk sets.
**Proposed fix:** Lock embeddingDim at KB creation, or recompute when ALL sources share a new dim.
**Status:** Open

## File: ai/src/main/java/com/ai/data/KnowledgeService.kt

### Bug 78 — Severity: HIGH — Category: RAG token budget approximation
**Location:** lines 273-282 (retrieve)
**Symptom:** Token budget is approximated as character count; tokens can be 1 char (Chinese) to 5+ chars. Mis-budgeting truncates context unpredictably.
**Proposed fix:** Use the same estimateTokens helper as elsewhere.
**Status:** Open

### Bug 79 — Severity: HIGH — Category: dim mismatch surfacing
**Location:** lines 247-260
**Symptom:** `dimSurprise` only reports the FIRST mismatch; subsequent different dims are silently ignored.
**Proposed fix:** Collect all mismatches into a set.
**Status:** Open

### Bug 80 — Severity: HIGH — Category: Source persistence non-atomic
**Location:** lines 307-316 (persistSourceLocally)
**Symptom:** `target.outputStream().use { input.copyTo(out) }` writes directly. Process kill mid-copy leaves a partial file. No fsync.
**Proposed fix:** Write to .part then atomic rename.
**Status:** Fixed

## File: ai/src/main/java/com/ai/data/KnowledgeExtractors.kt

### Bug 81 — Severity: HIGH — Category: PDF OCR memory pressure
**Location:** lines 100-136 (ocrPdf)
**Symptom:** Bitmap at 200 DPI for poster-size pages (2400×3200 ARGB_8888 ≈ 30 MB). Low-RAM devices OOM.
**Proposed fix:** Cap size lower for low-memory devices via getMemoryClass().
**Status:** Open

### Bug 82 — Severity: HIGH — Category: ML Kit lifecycle race
**Location:** lines 100-135, 138-172 (recognizer.close in finally)
**Symptom:** Tasks.await is synchronous; close() in finally is fine for single-call. But if a parallel cancellation closes the recognizer mid-await, undefined behavior.
**Proposed fix:** Confirm the calling dispatcher serializes; use cancellation-safe close.
**Status:** Open

### Bug 83 — Severity: MEDIUM — Category: HTTP timeout starvation
**Location:** lines 601-613 (fetchUrlAsText)
**Symptom:** Jsoup 20s timeout starves the IO dispatcher's bounded thread pool; many slow URLs block the indexing pipeline.
**Status:** Open

### Bug 84 — Severity: MEDIUM — Category: CSV charset
**Location:** lines 492-535 (readUriCsv)
**Symptom:** `bufferedReader()` uses platform default charset, may not be UTF-8 on all OSes. No BOM / charset header sniff.
**Proposed fix:** Force UTF-8 explicitly.
**Status:** Fixed (this session) — force UTF-8 in readUriText / readUriCsv

## File: ai/src/main/java/com/ai/model/SettingsModels.kt

### Bug 85 — Severity: HIGH — Category: mergeParameters semantics asymmetry
**Location:** lines 638-658
**Symptom:** Semantics: temperature (last wins) vs. returnCitations (any-false wins). User-confusing — the same parameter list produces different precedence depending on the field.
**Proposed fix:** Either make returnCitations nullable (last-wins) or document the asymmetry.
**Status:** Open

### Bug 86 — Severity: HIGH — Category: removeProvider — orphaned references
**Location:** lines 550-558
**Symptom:** Removes provider, agents, flock agentIds, swarm members, endpoints, providerStates. But per-provider `parametersIds` for the removed provider are NOT cleared. InternalPrompt entries with `agent` referencing the removed provider's agents are also untouched.
**Proposed fix:** Cascading cleanup of all references.
**Status:** Partial — internalPrompt orphans cleared; ProviderConfig already drops with the AppService

### Bug 87 — Severity: HIGH — Category: recomputeCapabilities pricing fallback
**Location:** lines 444-451
**Symptom:** Uses `lookupPricing` (context-free). If PricingCache hasn't been preloaded, returns DEFAULT_PRICING for everything. Settings persist DEFAULT_PRICING into cfg.modelPricing — until next refresh.
**Proposed fix:** Document the ordering requirement; fail loudly if PricingCache.preloadCompleted is false.
**Status:** Open

### Bug 88 — Severity: MEDIUM — Category: Internal prompt orphan references
**Location:** lines 619-622 (removeAgent)
**Symptom:** When an agent is removed, InternalPrompt entries with `agent="DeletedAgent"` are not cleared.
**Status:** Fixed

## File: ai/src/main/java/com/ai/viewmodel/AppViewModel.kt

### Bug 89 — Severity: HIGH — Category: Init race / SettingsHolder
**Location:** lines 221-246 (init block)
**Symptom:** Two viewModelScope.launch coroutines race: one runs bootstrap then refreshAllModelLists; the other collects uiState into SettingsHolder.current. SettingsHolder.current may be set BEFORE bootstrap runs (with default Settings()) → dispatcher helpers reading SettingsHolder.current during the gap see empty Settings → wrong capability decisions on early calls.
**Proposed fix:** Set SettingsHolder.current synchronously after bootstrap, before kicking off background work.
**Status:** Open

### Bug 90 — Severity: HIGH — Category: onCleared synchronous I/O on main
**Location:** lines 248-254 (onCleared)
**Symptom:** `settingsPrefs.flushUsageStats()` runs synchronously on the dispatcher that called onCleared (often main). Disk write on main → strict-mode violation / ANR.
**Proposed fix:** Move to a non-cancellable scope (NonCancellable + GlobalScope).
**Status:** Fixed (this session) — GlobalScope + IO + NonCancellable instead of main

### Bug 91 — Severity: HIGH — Category: fetchModels save race
**Location:** lines 716-722 (cross-pollinate save in fetchModels)
**Symptom:** When OPENROUTER fetch succeeds, it saves OTHER providers' state by reading the latest aiSettings. If those other providers are themselves being fetched concurrently, this saver clobbers in-flight fetches.
**Proposed fix:** Hold a lock or take a per-provider snapshot.
**Status:** Open

### Bug 92 — Severity: HIGH — Category: Trace file resolution race
**Location:** lines 730-734 (fetchModels error path)
**Symptom:** `firstOrNull { it.timestamp >= startedAt && it.category == "Retrieve models list" }` — multiple parallel fetches all share the same category. The earliest matching trace may be from a DIFFERENT provider's fetch.
**Proposed fix:** Resolve by hostname match: `it.hostname == provider.baseUrl.host`.
**Status:** Open

### Bug 93 — Severity: HIGH — Category: testSpecificModel race
**Location:** lines 814-828 (testSpecificModel)
**Symptom:** Filter by `it.model == model && it.timestamp >= startTime` — two parallel calls to the same (provider, model) collide. The trace returned is whichever happens to be first in the list.
**Proposed fix:** Use a per-call unique tag.
**Status:** Open

### Bug 94 — Severity: MEDIUM — Category: resetApplication partial-failure roll-back
**Location:** lines 439-508 (resetApplication)
**Symptom:** Long sequence — none rolled back on partial failure. Half-reset state if any step fails.
**Proposed fix:** Document idempotency; ensure each step can be safely retried.
**Status:** Open

## File: ai/src/main/java/com/ai/viewmodel/ChatViewModel.kt

### Bug 95 — Severity: HIGH — Category: Streaming dispatcher
**Location:** lines 21-64 (sendChatMessageStream)
**Symptom:** When NOT using RAG, the returned flow is NOT wrapped with flowOn(Dispatchers.IO). Charstream reading happens on the collector thread — typically main → UI jank during high-throughput streams.
**Proposed fix:** Always wrap with flowOn(Dispatchers.IO).
**Status:** Fixed

### Bug 96 — Severity: MEDIUM — Category: Local LLM streaming contract
**Location:** lines 125-155 (sendLocalLlmStream)
**Symptom:** Throws on null LLM output rather than emitting an error chunk. Mixed contract (exception vs. chunk) across local vs. HTTP streams.
**Proposed fix:** Document the contract and choose one.
**Status:** Open

## File: ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt

### Bug 97 — Severity: HIGH — Category: executeReportTask cancellation race
**Location:** lines 264-329
**Symptom:** After `analyzeWithAgent` returns successfully, ReportStorage.markAgentSuccessAsync is called via `withContext(Dispatchers.IO)`. If the parent scope is cancelled DURING the markAgentSuccessAsync's withContext, the markAgent never runs. Agent row stays in RUNNING state on disk.
**Proposed fix:** Wrap final markAgent calls in NonCancellable.
**Status:** Fixed (this session) — terminal markAgent calls in NonCancellable

### Bug 98 — Severity: HIGH — Category: stopGenericReports race
**Location:** lines 690-722 (stopGenericReports)
**Symptom:** Cancels reportGenerationJob then markAgentStoppedAsync — but in-flight tasks may still be between API return and markAgentSuccessAsync. ReportStatus oscillates between SUCCESS and STOPPED.
**Proposed fix:** Use cancelAndJoin and only then mark stopped.
**Status:** Open

### Bug 99 — Severity: HIGH — Category: cascadeMetasAndTranslations sequential block
**Location:** lines 527-588
**Symptom:** Sequential `runMetaPrompt(...)?.join()` over groups, then sequential translations. A hung meta blocks all subsequent metas AND translations.
**Proposed fix:** Run independent metas in parallel; isolate hangs with timeouts.

### Bug 100 — Severity: HIGH — Category: regenerateAgent webSearchTool stripping
**Location:** lines 1750-1758
**Symptom:** When report.webSearchTool=true but canWeb=false, `withWeb = baseOverride?.copy(webSearchTool=false) ?: baseOverride`. If baseOverride is null, the result is null — meaning no override is sent and the dispatcher uses the agent's default (which may have webSearchTool=true). The strip doesn't take effect.
**Proposed fix:** Use `(baseOverride ?: AgentParameters()).copy(webSearchTool=false)` for the negative path.

### Bug 101 — Severity: HIGH — Category: Image attachment leak
**Location:** lines 205, 79-89
**Symptom:** generateGenericReports clears reportImageBase64/Mime AFTER the job finishes. If the user navigates away mid-generation, the image stays in UiState (potentially MBs).
**Proposed fix:** Clear in a `try { ... } finally { ... }` outside the job.

### Bug 102 — Severity: MEDIUM — Category: hydrateAgentResultsFromStorage partial hydration
**Location:** lines 661-688
**Symptom:** `if (_agentResults.value.isNotEmpty()) return` — early-returns if ANY entries exist. Partial in-memory state (1 of 5 finished) leaves the other 4 unhydrated.
**Proposed fix:** Merge instead of skip.

### Bug 103 — Severity: HIGH — Category: cross meta cancellation zombie rows
**Location:** lines 1192-1204 (rerunCompleteCross)
**Symptom:** After cancelAndJoin, the cancelled coroutines may still be in mid-network. As they unwind, they may try to call SecondaryResultStorage.save on the now-deleted ids, resurrecting zombie rows alongside fresh placeholders.
**Proposed fix:** Cancellation check before save; saves should respect job state.

### Bug 104 — Severity: HIGH — Category: Translation cost computation mismatch
**Location:** lines 2007-2008 vs 2071-2072
**Symptom:** In runOneTranslation, `costDollars = PricingCache.computeCost(tu, pricing)` (cache-aware + tier-aware). In saveOneTranslationItem, `inCost = tu.inputTokens * pricing.promptPrice` (simple). Two cost figures diverge on long contexts (above-200k tier).
**Proposed fix:** Use the same compute path everywhere.

### Bug 105 — Severity: MEDIUM — Category: cancelTranslation persisted rows survive
**Location:** lines 2107-2113 (cancelTranslation)
**Symptom:** Cancels the job; sets cancelled=true. But per-item runOneTranslation may have already saved a SecondaryResult before being cancelled. Saved rows still appear on the report.
**Proposed fix:** Document the behavior or delete-on-cancel.

### Bug 106 — Severity: HIGH — Category: cascade metas sequential bottleneck
**Location:** line 522 (cascadeMetasAndTranslations call)
**Symptom:** Suspend call inline; subsequent metas/translations all block on it.

## Cross-cutting concerns

### Bug 107 — Severity: HIGH — Category: API key leakage in tracer
**Location:** ApiTracer.kt — TracingInterceptor headersToMap
**Symptom:** Captures all request headers including Authorization. Trace files persisted to disk contain plaintext API keys; backup zip carries them in cleartext too.
**Reproduction:** Open any trace JSON; Authorization header is visible.
**Proposed fix:** Redact bearer tokens / API keys before persisting traces.

### Bug 108 — Severity: HIGH — Category: API key leakage in JSON parse
**Location:** ApiDispatch.kt — testApiConnectionWithJson
**Symptom:** `requestBuilder.addHeader("x-api-key", apiKey)` for ANTHROPIC etc. — captured by tracer same way.

### Bug 109 — Severity: MEDIUM — Category: Inconsistent date format
**Location:** AnalysisRepository.formatCurrentDate, SecondaryResult.resolveSecondaryPrompt
**Symptom:** Two date formats used in prompts; user sees inconsistent dates between report agent prompt and meta prompt.

### Bug 110 — Severity: HIGH — Category: ApiTracer hostname filename
**Location:** ApiTracer.kt line 75
**Symptom:** `${trace.hostname}_${ts}_${seq}.json` — hostname can contain colons (host:port) on some configurations. Filesystem rejects, file write fails.

### Bug 111 — Severity: MEDIUM — Category: Logging only
**Location:** Throughout
**Symptom:** android.util.Log.e/w for failures — no central error reporting, no Crashlytics. User-visible errors require manual logcat inspection.

### Bug 112 — Severity: HIGH — Category: Gson Kotlin null safety bypass
**Location:** Multiple data classes with non-null properties
**Symptom:** Gson uses reflection and bypasses Kotlin's null-safety. Required fields can come back null at runtime; subsequent `.id.lowercase()` calls NPE.
**Proposed fix:** Add validation post-parse, or use kotlinx.serialization.

### Bug 113 — Severity: HIGH — Category: Refresh data race in fetchModels
**Location:** AppViewModel.fetchModels lines 690-722
**Symptom:** Multiple concurrent fetchModels for different providers each call _uiState.update. Each update sees the latest state in the lambda (correct). But the persistence step (settingsPrefs.saveModelsForProvider) reads the latest state AFTER the update — saving for provider A may include provider B's just-merged data. Eventually consistent, but fragile.

### Bug 114 — Severity: HIGH — Category: KnowledgeStore.saveSource concurrent updates lost
**Location:** Knowledge.kt 160-175
**Symptom:** Reads manifest, replaces source entry, saves. The lock.withLock wraps it — actually safe. But the lock is at the singleton level; per-KB concurrency is serialized which is fine, but not informative.

### Bug 115 — Severity: HIGH — Category: ReportViewModel agent results pollution after cancel
**Location:** lines 297-323 (executeReportTask cancellation flow)
**Symptom:** `_agentResults.update { it + (task.resultId to response) }` runs even on errors after the cancellation rethrow path. A cancelled task can pollute _agentResults if the response variable was already constructed with an error AnalysisResponse via the catch arm.

### Bug 116 — Severity: HIGH — Category: BackupManager cache wipe race
**Location:** BackupManager.kt lines 363-367
**Symptom:** `clearCacheDirForRestore` deletes cache files except preserved ones. If a temp file was created in cache dir AFTER the preserve set was constructed but BEFORE the wipe runs, that file is deleted unexpectedly.

### Bug 117 — Severity: MEDIUM — Category: ReportStorage loadAllReports parse race
**Location:** lines 254-261
**Symptom:** Snapshot of listFiles, then reads each. A file deleted between listFiles and readText fails to read; caught and dropped. UI sees the report disappear briefly until a refresh.

### Bug 118 — Severity: HIGH — Category: PromptCache delete race
**Location:** PromptCache.kt lines 33-49 (get)
**Symptom:** `file.delete()` on TTL expiration runs INSIDE lock.withLock — concurrent put for the same key has to wait. Fine in practice.
**Sub-bug:** `runCatching` swallows JsonParseException; the cached entry is deleted. But `try { file.delete() } catch (_: Exception) {}` swallows delete failure. If both succeed, fine.

### Bug 119 — Severity: HIGH — Category: ChatHistoryManager save success
**Location:** ChatHistoryManager.saveSession lines 29-41
**Symptom:** Returns `true` unconditionally (line 36 returns true after writeTextAtomic returned). writeTextAtomic returns Boolean — caller ignores it.

### Bug 120 — Severity: MEDIUM — Category: SecondaryResult timestamp ordering
**Location:** SecondaryResultStorage.listForReport line 178
**Symptom:** sortedBy { it.timestamp } returns consistent order, but when timestamps collide (UUID in same ms), the relative order is unstable across listFiles calls.

### Bug 121 — Severity: HIGH — Category: prefs string set mutation
**Location:** AppViewModel.loadReportAgents, saveReportAgents
**Symptom:** SharedPreferences requires defensive copy of getStringSet result and putStringSet input. The code defensively copies on both sides via `.toHashSet()`. OK.

### Bug 122 — Severity: HIGH — Category: SettingsModels.applyOpenRouterTypes mutation
**Location:** SettingsModels.kt lines 486-506
**Symptom:** Iterates AppService.entries and calls withModels → recomputeCapabilities for each. For a 39-provider setup with many models each, this is O(providers × models × catalog scan) per call. Heavy; but also writes Settings to disk many times via the AppViewModel save chain. Slow.

### Bug 123 — Severity: HIGH — Category: ApiDispatch tool fallback returns AnalysisResponse without status restored
**Location:** AnalysisRepository.analyzeWithAgent lines 222-234
**Symptom:** When fallback retries without webSearchTool, the second response's httpStatusCode replaces the first's (correct). But `error` field reflects the second call only — original 400-with-tool error is lost in logs.

### Bug 124 — Severity: HIGH — Category: AppService.findById id case mismatch
**Location:** AppService.findById line 75
**Symptom:** Compare `id == "LOCAL"` exactly (case-sensitive). ProviderRegistry.findById uses .id == ... (case-sensitive too). A user-typed lowercase "local" doesn't match.

### Bug 125 — Severity: HIGH — Category: BackupManager addDirectoryRecursive followsSymlink
**Location:** lines 423-444
**Symptom:** `dir.listFiles()` follows symbolic links by default. A symbolic link inside filesDir to /sdcard/something would copy unrelated user data into the backup zip.
**Proposed fix:** Check `Files.isSymbolicLink(child.toPath())` and skip symlinks.

### Bug 126 — Severity: HIGH — Category: AnalysisRepository agent lookup
**Location:** AnalysisRepository.analyzeWithAgent line 191
**Symptom:** `if (agent.provider.id == "LOCAL")` — checks the pre-passed agent's provider id. If a non-LOCAL provider id has the same string by accident, this branch fires. Combined with Bug 33, real risk.

### Bug 127 — Severity: HIGH — Category: ReportViewModel cross meta two same-provider permits
**Location:** lines 928-931 (semByProvider)
**Symptom:** `semByProvider[answerer.provider]` — uses the provider OBJECT as key. AppService equality is by id; OK. But if AppService.findById returns a fresh instance for each call (which it might, depending on registry implementation), two different instances of the same provider create different sem entries. Currently AppService.findById returns from a CopyOnWriteArrayList so returns the same instance — safe today.

### Bug 128 — Severity: MEDIUM — Category: Settings.getModelType LiteLLM consultation
**Location:** SettingsModels.kt lines 300-306
**Symptom:** LiteLLM only trusted when it returns something more specific than CHAT — but PricingCache.liteLLMModelType returns null for unknown mode strings. So a future LiteLLM mode addition produces null → falls through to stored type.

### Bug 129 — Severity: HIGH — Category: writeTextAtomic Kotlin writeText charset
**Location:** AtomicFileWrite.kt line 22
**Symptom:** `tmp.writeText(content)` defaults to UTF-8 on JVM, but on Android pre-N the default charset was platform-dependent. Mostly UTF-8 today, but document.

### Bug 130 — Severity: HIGH — Category: KnowledgeService dim-mismatch silent zero score
**Location:** lines 247-260
**Symptom:** When a chunk's embedding dim doesn't match the query, the chunk is skipped — the user gets no hit, no warning except a logcat line.
**Proposed fix:** Surface to UI when retrieval finds zero hits due to dim mismatch.

