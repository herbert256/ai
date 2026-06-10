# Bug candidates — Data layer / view-models / model (audit-fable, INTERRUPTED RUN)

Scope: `data/**`, `viewmodel/**` (minus the four files V3/V4/V5/V6 cover — those
shards did not finish), `model/**`, `data/preferences/**`. Read from the live code
at HEAD `842f476f4` (2026-06-10) by 8 completed shard finders of a planned
31-finder fleet. **The run was stopped early to save usage limits: the
adversarial-verification pass never ran on these findings.** Every entry below is
therefore a CANDIDATE — plausible and cited, but not independently confirmed; the
prior audits' experience says roughly a third of unverified candidates do not
survive verification. Treat each as a lead, not a verdict.

Findings are grouped by file and numbered continuously, worst severity first
within each file.

## File: ai/src/main/java/com/ai/data/ApiDispatch.kt

### Candidate 1 — Severity: HIGH — Category: streaming timeout scope
**Location:** ai/src/main/java/com/ai/data/ApiDispatch.kt:943-960 (with 63-80; contrast ApiStreaming.kt:345,380,434,472)
**Symptom:** A healthy streaming report generation that takes longer than (streamingReadTimeoutSec + 30 + 30)s wall-clock — 300s at the default 240s setting — is cancelled mid-stream even though chunks are arriving. The error is mislabeled "stream open timed out after 300s (no response — possible network/DNS hang)", the partial streamed text is discarded, and analyzeWithAgentStreaming silently re-issues the whole call non-streaming (re-billing the first, fully-streamed attempt; the retry then usually dies at OkHttp's 120s non-streaming read timeout for slow models). Long-output / high-reasoning-effort models effectively cannot complete streamed reports.
**Root cause (claimed):** analyzeAgentStreaming (ApiDispatch.kt:948-959) does withApiCallTimeout(streamingOpen=true){ when(format) -> streamOpenAiReport/streamAnthropicReport/streamGeminiReport }, and those functions (ApiDispatchStreaming.kt) both OPEN the SSE response and fully drain it via collectStreamResponse INSIDE the timeout lambda. This directly contradicts withApiCallTimeout's own contract (ApiDispatch.kt:57-61: "The long SSE body is still outside this wrapper and remains guarded by OkHttp's per-chunk streaming read timeout") and the chat streaming path, which correctly wraps only the api.*Stream(...) open call (ApiStreaming.kt:345, 380, 434, 472). kotlinx withTimeout measures total elapsed time, not inter-chunk stall, so steady progress doesn't reset it.
**Reproduction:** Set an agent to a slow high-effort reasoning model (or temporarily set Settings → network streaming read timeout to 30s), generate a streaming report whose answer takes longer than readSec+60s total; observe the IOException 'stream open timed out … possible network/DNS hang' despite live deltas, followed by a second (non-streaming) billed attempt.
**Proposed fix:** Move the withApiCallTimeout(streamingOpen=true) wrapper inside streamOpenAiReport/streamResponsesApiReport/streamAnthropicReport/streamGeminiReport so it covers only the Retrofit open call (mirroring ApiStreaming.kt's chat paths), leaving the drain guarded by OkHttp's per-chunk streaming read timeout; remove the outer wrapper from analyzeAgentStreaming.
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 2 — Severity: HIGH — Category: dispatch format error
**Location:** ai/src/main/java/com/ai/data/ApiDispatch.kt:639-649 (same pattern at ApiStreaming.kt:326-336)
**Symptom:** In an AI Chat session on a Responses-API model (gpt-5.x / o3 / o4 / gpt-4.1 per OpenAI.json responsesApiPatterns), sending an image works for that turn, but every subsequent send in the session fails with API error 400 — the session is effectively wedged as long as the image-bearing turn remains in history. Affects both non-streaming (chatResponsesApiResponse) and streaming (streamOpenAi Responses branch) chat.
**Root cause (claimed):** When any message in the history carries imageBase64 (`anyImage`), ALL non-system turns — including role="assistant" ones — are mapped to typed content parts of type "input_text" (ApiDispatch.kt:640-648, ApiStreaming.kt:327-335). The OpenAI Responses API rejects "input_text" parts on assistant-role input messages (assistant content must be "output_text"; the server returns 400 "Invalid value: 'input_text'…"). Since chat history persists the image, turn N+1's request contains [user(img), assistant, user] and the assistant turn poisons the payload. The text-only branch is unaffected because plain string content is accepted for any role. An assistant turn with blank content also produces an empty parts array, a second 400 trigger.
**Reproduction:** Chat with gpt-5: attach an image via 📎, send (works), then send any follow-up message — the request 400s on the assistant turn in history.
**Proposed fix:** When building typed content parts, emit "output_text" for role=="assistant" parts (and skip empty-part assistant turns), or use the typed-parts shape only for the image-bearing user messages and plain string content for everything else.
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 3 — Severity: LOW — Category: param drop
**Location:** ai/src/main/java/com/ai/data/ApiDispatch.kt:585-596 (contrast ApiStreaming.kt:365-379)
**Symptom:** Dual chat (ChatViewModel.sendDualChatMessage always uses sendChatResponse) and the LiteLLM "no native streaming" chat fallback ignore the preset's Return-citations opt-out and Search-recency (day/week/month/year) on citation-capable providers (Perplexity): the same session behaves differently streamed vs non-streamed.
**Root cause (claimed):** chatOpenAiResponse builds OpenAiRequest without return_citations/search_recency_filter, while the streaming sibling streamOpenAi (ApiStreaming.kt:373-374) passes both: `return_citations = if (service.supportsCitations) params.returnCitations else null, search_recency_filter = if (service.supportsSearchRecency) params.searchRecency else null`. ChatParameters carries both fields (DataModels.kt:77-78).
**Proposed fix:** Add the same two gated fields to the OpenAiRequest built in chatOpenAiResponse.
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 4 — Severity: LOW — Category: usage normalization drift
**Location:** ai/src/main/java/com/ai/data/ApiDispatch.kt:447 (contrast 665 and ApiStreaming.kt:202)
**Symptom:** On the non-streaming Responses-API analyze path, provider-aware usage normalization is skipped: promptTokensIncludeCachedTokens=false providers would have cached tokens double-subtracted from fresh input, and costTicksDivisor-based apiCost extraction is disabled. No live mis-billing today (only xAI declares those flags and isn't Responses-routed), but any custom OpenAI-compatible provider whose model ids classify as RESPONSES via ModelType.infer inherits the wrong math.
**Root cause (claimed):** Line 447 calls `body?.usage?.toTokenUsage()` with the default provider=null, whereas the sibling chatResponsesApiResponse (line 665) and the streaming extractor extractResponsesApiUsage(service) pass the service — the 2026-06-06 audit fix explicitly routed "the Responses streaming extractor … into the same normalizer" but missed this call site.
**Proposed fix:** Change line 447 to body?.usage?.toTokenUsage(service).
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 5 — Severity: LOW — Category: timeout scope
**Location:** ai/src/main/java/com/ai/data/ApiDispatch.kt:217-227 (with ApiDispatchModels.kt:14-128)
**Symptom:** A model-list refresh against a slow provider can be cancelled and mislabeled "API call timed out … possible network/DNS hang" even though each individual request is progressing: fetchModelsOpenAi performs the raw-JSON snapshot fetch, the typed list call, and (for Cohere) the native capability call sequentially, all inside one 180s (120+30+30) non-streaming ceiling, while each call alone may legitimately use up to ~150s (30s connect + 120s read).
**Root cause (claimed):** withApiCallTimeout's contract is a ceiling for "ONE outbound provider call" (ApiDispatch.kt:42), but fetchModelsWithKinds wraps the whole per-format fetcher, which makes 2-3 calls (ApiDispatchModels.kt:25 raw snapshot, :76/:83 typed list, :107 Cohere native; the Anthropic fetcher's snapshot at :274 is also inside).
**Proposed fix:** Wrap each individual HTTP call in its own withApiCallTimeout inside the fetchers (or budget the ceiling per number of calls).
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/ApiDispatchBuilders.kt

### Candidate 6 — Severity: LOW — Category: reasoning effort mapping
**Location:** ai/src/main/java/com/ai/data/ApiDispatchBuilders.kt:234-253 (with ApiDispatchModels.kt:248-253, ui/chat/ChatScreens.kt:950-960)
**Symptom:** The chat effort pulldown offers exactly the levels Anthropic self-reports per model — including "max" (fetchModelsAnthropic stores it in reasoningEffortLevels; ChatScreens renders it). If "max" is picked for a Claude model that uses the budget_tokens thinking shape (not adaptive), the dispatch silently sends NO thinking block at all — strictly less reasoning than picking "low" — with no warning, and reasoning-effort sweep results for that level are misleading.
**Root cause (claimed):** budgetForEffort (ApiDispatchBuilders.kt:234-239) maps only low/medium/high and returns null for "max"; anthropicThinkingField then returns null for non-adaptive models (line 251), so ClaudeRequest.thinking and output_config are both omitted. Adaptive (Opus 4.7+) models are unaffected because effort rides on output_config.
**Proposed fix:** Map "max" to a large budget (e.g. 32768) in budgetForEffort, or exclude "max" from the selectable set for budget-shape models.
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/ApiModels.kt

### Candidate 7 — Severity: MEDIUM — Category: cost accounting
**Location:** ai/src/main/java/com/ai/data/ApiModels.kt:177-195, 856-870 (consumers: ApiDispatch.kt:447, ApiStreaming.kt:197-204)
**Symptom:** For Responses-API calls (OpenAI gpt-5.x/o3/o4), cachedInputTokens is always 0 even when the response reports cache hits, so the app's cost accounting overstates spend: cached tokens (billed by OpenAI at ~10% of the input rate) are charged at the full promptPrice via computeInOutCost. OpenAI auto-caches prompts ≥1024 tokens, so multi-agent reports re-using the same long prompt hit this constantly.
**Root cause (claimed):** The Responses API reports cached tokens only under usage.input_tokens_details.cached_tokens. OpenAiUsage (ApiModels.kt:179-195) declares prompt_tokens_details (Chat Completions shape), prompt_cache_hit_tokens (DeepSeek) and flattened cached_tokens — but no input_tokens_details field. Both the non-streaming path (analyzeResponsesApi → body.usage.toTokenUsage()) and the streaming path (extractResponsesApiUsage parses the response.completed usage object into OpenAiUsage) therefore compute cached = 0 and fresh = input_tokens in toTokenUsage (ApiModels.kt:857-863).
**Reproduction:** Run two consecutive report agents on gpt-5 with the same >1024-token prompt; the second response's raw usage JSON shows input_tokens_details.cached_tokens > 0 while the app's TokenUsage records cachedInputTokens = 0 and bills the full input rate.
**Proposed fix:** Add `input_tokens_details: OpenAiPromptTokensDetails?` to OpenAiUsage and insert `input_tokens_details?.cached_tokens` into the cached fallback chain in toTokenUsage.
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 8 — Severity: MEDIUM — Category: dispatch format error
**Location:** ai/src/main/java/com/ai/data/ApiModels.kt:288-314 (dispatch sites: ApiDispatch.kt:473-486, ApiStreaming.kt:423-433, ApiDispatchStreaming.kt:126-138)
**Symptom:** Any Claude agent/chat whose resolved Parameters preset sets frequency penalty, presence penalty, seed, or the "Search enabled" checkbox fails every call with API error 400 ("…Extra inputs are not permitted"). withRetry treats 400 as permanent (no retry) and the tool-fallback doesn't trigger (no tool keyword), so the agent hard-fails. The ParametersScreen exposes all four fields generically for every provider, so nothing stops the user from creating this combination.
**Root cause (claimed):** ClaudeRequest (ApiModels.kt:288-314) declares frequency_penalty, presence_penalty, seed and search, and analyzeAnthropic / streamAnthropic / streamAnthropicReport populate them from params whenever set (Gson omits them only when null/unset). The Anthropic Messages API validates request bodies strictly and 400s unknown top-level fields; none of these four exist in the Messages API. Tellingly, the non-streaming chat path chatAnthropicResponse (ApiDispatch.kt:745-752) already omits the penalties — so streaming and non-streaming Anthropic chat behave differently for the same preset, and only Anthropic uses the ANTHROPIC format (no other provider shares this code path).
**Reproduction:** Create a Parameters preset with Frequency penalty = 0.5, attach it to a Claude agent, generate a report → 400 invalid_request_error on every attempt.
**Proposed fix:** Stop mapping frequencyPenalty/presencePenalty/seed/searchEnabled into ClaudeRequest (drop the fields from the data class, or null them in every Anthropic dispatch site), and align the three Anthropic call sites so streaming and non-streaming send identical bodies.
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 9 — Severity: MEDIUM — Category: dispatch format error
**Location:** ai/src/main/java/com/ai/data/ApiModels.kt:389-404 (dispatch sites: ApiDispatch.kt:522-526,782-791, ApiStreaming.kt:456-471, ApiDispatchStreaming.kt:154-159)
**Symptom:** Enabling the generic "Search enabled" Parameters checkbox (the flat search:true flag meant for Perplexity-style OpenAI-compatible providers) on a Gemini agent or chat makes every call fail with 400 INVALID_ARGUMENT ("Unknown name \"search\" at 'generation_config'"). As a deterministic 4xx it skips the retry layer and hard-fails the agent.
**Root cause (claimed):** GeminiGenerationConfig (ApiModels.kt:398) declares `search: Boolean?` and analyzeGemini/chatGeminiResponse/streamGemini/streamGeminiReport set it to true when params.searchEnabled. generativelanguage.googleapis.com uses strict proto-JSON transcoding and rejects unknown generationConfig members. Gemini web grounding is already correctly handled via the separate `tools: [{google_search:{}}]` path (geminiWebSearchTool), so the flag adds nothing on this format.
**Reproduction:** Attach a preset with "Search enabled" checked to a Gemini agent and run it → 400 INVALID_ARGUMENT naming the unknown 'search' field.
**Proposed fix:** Remove the `search` field from GeminiGenerationConfig and stop mapping searchEnabled in the four Gemini dispatch sites (web search stays available through the webSearchTool toggle).
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 10 — Severity: LOW — Category: Gson null-field trap
**Location:** ai/src/main/java/com/ai/data/ApiModels.kt:46-82
**Symptom:** Latent: ModelType.inferAcceptsReasoningEffortParam's null-fallback branch is dead for every JSON-parsed provider — 'provider.reasoningEffortAcceptPatterns?.let { return it.anyMatches(modelId) }' (ModelType.kt:261) always fires with an empty list and returns false instead of falling back to inferReasoning. Today only providers with externalReasoningSignalUntrusted=true consult this (xAI alone, and xAI.json declares patterns), so no visible breakage yet; any future untrusted provider without declared accept patterns would silently never send reasoning_effort.
**Root cause (claimed):** ProviderDefinition has non-defaulted ctor params (id/baseUrl/defaultModel) so Gson allocates it via Unsafe with all fields null; NullSafeFieldAdapterFactory (ApiModels.kt:46-82) then coerces every still-null Collection field — including the intentionally-nullable sentinel reasoningEffortAcceptPatterns (ProviderRegistry.kt:583, 'When null, falls back to reasoningModelPatterns') — to emptyList. toAppService passes it through unguarded (ProviderRegistry.kt:650), and fromAppService also serializes it without the takeIf{isNotEmpty} compression its sibling pattern fields use (ProviderRegistry.kt:689), so the registry permanently stores [] and null can never round-trip.
**Reproduction:** Parse any bundled provider file lacking the field; observe reasoningEffortAcceptPatterns == emptyList() instead of null on the resulting AppService.
**Proposed fix:** Exclude meaningful nullable-collection sentinels from the coercion (e.g. by field allowlist/annotation), or normalize in toAppService: treat empty reasoningEffortAcceptPatterns as null; align fromAppService with takeIf { it.isNotEmpty() }.
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/ApiTracer.kt

### Candidate 11 — Severity: MEDIUM — Category: lock held across disk I/O
**Location:** ai/src/main/java/com/ai/data/ApiTracer.kt:241, 327-354
**Symptom:** With tracing enabled and a dense trace dir (cap is 2000 files / 50 MB), every traced API call's save stalls behind an O(N) pass that opens and stream-parses up to 2000 JSON files while holding ApiTracer.lock; concurrent saves from a 50-pair fan-out serialize on it, and every UI read (getTraceFiles, readTraceFile, hasAnyTraceFile, the Trace screen) contends on the same lock, freezing the trace list during heavy runs.
**Root cause (claimed):** Commit b8103d288 ('Cap API trace retention', 2026-06-07) added the call `pruneTraceDirLocked(dir, protectedFilename = resolvedFilename)` at ApiTracer.kt:241 inside the `lock.withLock` block of saveTrace. pruneTraceDirLocked (lines 327-354) does `dir.listFiles { it.extension == "json" }?.mapNotNull { parseTraceFileInfoStreaming(it) }` — a full JsonReader parse of every trace file per save. This re-introduces, in worse form, exactly the serialized-disk-I/O-under-lock problem that audit/2026-05-24 bugs_data Bug 20 fixed by moving writeTextAtomic outside the lock; the prune was added after that audit and is not covered by the 06-08/06-10 audits.
**Reproduction:** Enable tracing, accumulate ~2000 traces (one big fan-out run), then run another batch: each call's trace save does ~2000 file opens+parses under the lock; the API Traces screen becomes unresponsive while the batch runs.
**Proposed fix:** Prune from the in-memory cachedTraceFiles (augmented with a size field, or stat sizes lazily) instead of re-parsing the dir, and/or run the prune only when the cached count/byte tally crosses the cap (amortized, e.g. every 50 saves), keeping only the cache mutation under the lock.
**Found by:** shard D3-interceptors
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 12 — Severity: LOW — Category: cache incoherence
**Location:** ai/src/main/java/com/ai/data/ApiTracer.kt:217-235
**Symptom:** A long-running streaming call whose partial trace entry was pruned mid-stream (trace dir at the 2000-file/50 MB cap during a heavy run) finishes and writes its final trace, but the trace never appears in the API Traces list (and getTraceCount undercounts) for the rest of the process lifetime; it only shows up after a restart rebuilds the cache from disk.
**Root cause (claimed):** saveTrace's update path (filename != null, line 223-227) replaces a matching cache entry via `current.map { if (it.filename == resolvedFilename) info else it }` — if pruneTraceDirLocked deleted the partial's file and cache entry between the partial save and finishOnce's final save, the disk write at line 196 recreates the file but the map() finds no matching entry and never appends one, so disk and cachedTraceFiles permanently diverge for that file (cache invalidation only happens on a cache-update exception).
**Proposed fix:** In the isUpdate branch, append the info entry when no existing entry matched (e.g. `if (current.none { it.filename == resolvedFilename }) current + info else current.map {...}`).
**Found by:** shard D3-interceptors
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/AppLog.kt

### Candidate 13 — Severity: LOW — Category: settings gate bypass
**Location:** ai/src/main/java/com/ai/data/AppLog.kt:107-124
**Symptom:** With Settings → Logging master toggle OFF but a stored log level of DEBUG/INFO, every cold start writes the whole bootstrap window's log lines into <filesDir>/applog/ (and can pop WARN/ERROR toasts) even though the user disabled all diagnostics — until AppViewModel later mirrors effectiveLogLevel() and forces OFF.
**Root cause (claimed):** AppLog.init (lines 118-123) reads only `prefs.getString("log_level", null)` from eval_prefs and sets threshold directly. The runtime gate is GeneralSettings.effectiveLogLevel() (AppViewModelTypes.kt:478) = `if (loggingMasterEnabled) logLevel else OFF`, with loggingMasterEnabled persisted as "logging_master_enabled" in the same prefs file (SettingsPreferences.kt:1082) — init never consults it.
**Reproduction:** Set log level to DEBUG, turn the logging master switch off, force-stop and relaunch: a fresh applog_<date>.log appears containing the bootstrap lines.
**Proposed fix:** In AppLog.init, also read `logging_master_enabled` (default true) and set threshold to LogLevel.OFF when it is false, mirroring effectiveLogLevel().
**Found by:** shard D3-interceptors
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/BackupManager.kt

### Candidate 14 — Severity: HIGH — Category: registry sync / backup completeness
**Location:** ai/src/main/java/com/ai/data/BackupManager.kt:106-108
**Symptom:** After restoring a backup onto a cleared/fresh install, all hand-edited provider catalog fields (baseUrl, modelFilter, litellmPrefix, defaultModel, throttle overrides, pattern lists, ...) are present right after restore but silently revert to the bundled assets/providers/ values on the next app launch.
**Root cause (claimed):** PREFS_TO_BACKUP (BackupManager.kt:106-108) backs up provider_registry but NOT the provider_field_timestamps prefs file that marks fields as user-touched (ProviderFieldTimestamps.kt:29). The every-start delta-sync (AppViewModel.kt:684 -> ProviderRegistry.syncFromAsset, ProviderRegistry.kt:242-265) pulls the bundled asset value for any tracked field that differs from the asset AND has a null timestamp (line 254: diff.filter { ProviderFieldTimestamps.get(asset.id, it) == null }). After a restore on cleared data the timestamps map is empty, so exactly the fields the user customized (the ones differing from the asset) all have null timestamps and get overwritten on first launch.
**Reproduction:** 1) Edit any bundled provider field in Settings (e.g. OpenAI baseUrl) — update() bumps its timestamp. 2) Create a backup. 3) Clear app data (or install on a new device) and restore the backup. 4) Relaunch: applog shows 'syncFromAsset: OpenAI pulled baseUrl' and the edit is gone.
**Proposed fix:** Add the provider_field_timestamps prefs file to PREFS_TO_BACKUP (and restore it), or derive protection differently (e.g. persist the markers inside provider_registry prefs so they travel with the registry).
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/CompareRunModel.kt

### Candidate 15 — Severity: MEDIUM — Category: score parsing
**Location:** ai/src/main/java/com/ai/data/CompareRunModel.kt:175-177
**Symptom:** A compare-with-meta cell whose judge reply contains no labeled percentage but any incidental digit gets a bogus score: "I cannot meaningfully compare these 2 texts" parses as percent=2 and is committed as the cell's similarity. Because CompareEngine's accept predicate is `parseSimilarityScore(resp.analysis) != null` (CompareEngine.kt:321), the hollow reply is treated as a success — the worker chain does NOT advance to the next worker, so the wrong score sticks and feeds avgForAgent/avgForMeta and the Value view's quality axis.
**Root cause (claimed):** After the labeled `percent...:` line and the strict-JSON form both miss, line 176 runs `parsePercentNumber(cleaned)` over the whole reply and accepts the first number found anywhere (Regex \d+(\.\d+)? at line 184). This is exactly the pattern the 2026-06-08 audit flagged for TranslatorRankModel (bug 9, fixed in 59aa2aba — its replacement code at TranslatorRankModel.kt:161-168 even comments "Scanning the whole body would read a reason like '2 strong points' as the score"), but CompareRunModel kept the whole-body scan.
**Reproduction:** Feed parseSimilarityScore("I cannot compare these 2 answers, the request is ambiguous.") → CompareScore(percent=2). Any compare run where a worker returns prose with an incidental digit produces a silently wrong cell.
**Proposed fix:** Mirror the TransRank fix: only take a number from the first line or an explicitly score/percent-labelled line; return null otherwise so the engine's accept predicate treats it as a logical miss and falls through to the next worker.
**Found by:** shard D6-models-prefs
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/CrashReporter.kt

### Candidate 16 — Severity: MEDIUM — Category: observer leak / duplicate reports
**Location:** ai/src/main/java/com/ai/data/CrashReporter.kt:50-61
**Symptom:** After the Activity has been recreated N times in one process (rotation, locale/dark-mode change, system-initiated recreate — MainActivity has no android:configChanges), a single fatal crash produces N stacked handler invocations: N near-duplicate report_<millis>.txt history files (timestamps a few ms apart), N AppLog.e mirror entries, and the 30-entry crash history can be flooded by duplicates of one crash, evicting older real reports (pruneHistory only runs on the next init).
**Root cause (claimed):** MainActivity.kt:58 calls CrashReporter.init(applicationContext) unconditionally in onCreate. init (CrashReporter.kt:55-60) does `val previous = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler { ... writeReport("FATAL", ...); previous?.uncaughtException(...) }` with no idempotence guard, so each onCreate wraps the previous wrapper — the chain grows monotonically per process and every layer runs writeReport before delegating.
**Reproduction:** Rotate the device (or toggle dark mode) 5 times, then trigger any fatal crash: the Crash reports screen shows ~6 entries for the single crash.
**Proposed fix:** Make init idempotent: keep an installed flag (or check whether the current default handler is already CrashReporter's lambda via a named inner class) and skip re-registration; alternatively install once from an Application subclass instead of Activity.onCreate.
**Found by:** shard D3-interceptors
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/InternalPromptIconCache.kt

### Candidate 17 — Severity: LOW — Category: cost accounting
**Location:** ai/src/main/java/com/ai/data/InternalPromptIconCache.kt:148-213
**Symptom:** The per-(name,title) icon cost shown on the Meta-icon detail / Icon lookup screens undercounts: alternative-icons candidate costs incurred before the initial generation lands disappear, and a shell entry that never received an initial emoji loses its accumulated cost after an app restart.
**Root cause (claimed):** bumpCost (InternalPromptIconCache.kt:185-213) deliberately creates an empty-emoji shell entry to hold candidate costs when the alt-icons flow runs before initial generation, but recordInitial (148-178) unconditionally replaces the entry with 'this call's tokens + cost are the entry's starting totals' — discarding the shell's accumulated inputCost/outputCost instead of adding to them (contradicting the CacheEntry doc, lines 56-61: costs 'accumulate across every API call'). Additionally init() (109-111) drops any persisted entry with a blank emoji, so a not-yet-finalized shell's costs are also lost across process restarts.
**Reproduction:** Open the alternative-icons flow for a prompt whose initial icon generation is still in flight, generate candidates (bumpCost), then let the initial generation complete (recordInitial) → entry totals equal only the initial call.
**Proposed fix:** In recordInitial, add the existing entry's token/cost totals to the new entry's starting totals when one exists; keep blank-emoji entries at load (or merge their costs into the eventual real entry).
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/Knowledge.kt

### Candidate 18 — Severity: MEDIUM — Category: resource leak
**Location:** ai/src/main/java/com/ai/data/Knowledge.kt:258-276
**Symptom:** Disk usage under filesDir/knowledge/<kbId>/files/ grows monotonically: every file ever added to a KB keeps its full byte copy on disk after the source is deleted, and every failed indexing attempt leaves an orphaned copy. Backups inflate accordingly (the files/ mirror includes the orphans).
**Root cause (claimed):** KnowledgeService.persistSourceLocally (KnowledgeService.kt:347-387) copies every picked file into knowledge/<kbId>/files/<timestamp>_<uuid>_<name> and stores the file:// Uri as the source's origin. KnowledgeStore.deleteSource (Knowledge.kt:258-276) deletes only chunks/<sourceId>.json and the manifest entry — the files/ copy is never removed (the only caller, KnowledgeScreens.kt:544, calls deleteSource alone; grep confirms nothing else touches the "files" subdir besides persistSourceLocally). Likewise, if extraction or embedding fails after persistSourceLocally in indexFile (KnowledgeService.kt:49-64), the copy is orphaned with no source row referencing it.
**Reproduction:** Add a large PDF to a KB, then delete the source from the KB screen. Inspect filesDir/knowledge/<kbId>/files/ — the PDF copy is still there. Repeat add/delete to grow it unboundedly.
**Proposed fix:** In deleteSource, load the source row first, and when source.origin is a file:// Uri inside the KB's files/ dir, delete that file along with the chunks file. In indexFile, delete the persisted copy in a catch/cleanup path when runIndex throws before saveSource succeeds. Optionally sweep files/ entries unreferenced by any manifest origin on KB load.
**Found by:** shard D5-rag-local-backup
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/KnowledgeExtractors.kt

### Candidate 19 — Severity: LOW — Category: locale heuristic
**Location:** ai/src/main/java/com/ai/data/KnowledgeExtractors.kt:436-446
**Symptom:** Indexing a headerless Dutch semicolon-delimited CSV whose numbers use comma decimals ("1,5") treats the first data row as a column header: that row is removed from its data position and instead repeated at the top of every 10-row block, polluting every chunk with a bogus 'header' line and skewing retrieval.
**Root cause (claimed):** readUriCsv's heuristic at KnowledgeExtractors.kt:439-442 declares a header when every cell is non-blank and `row.any { it.toDoubleOrNull() == null }`. Kotlin's toDoubleOrNull is locale-independent (US-style only), so comma-decimal numerics like "1,5" always fail to parse and count as non-numeric text — exactly the owner's nl-NL CSV exports, which are semicolon-delimited (correctly sniffed at line 434) with comma decimals. A first row of all comma-decimal numbers therefore always 'looks like' a header.
**Reproduction:** Export a headerless CSV from a Dutch spreadsheet (semicolon delimiter, values like 1,5;2,7;3,1), add it to a KB, inspect the chunks: the first data row appears as a repeated header in every block.
**Proposed fix:** In the numeric test, also try the value with ',' replaced by '.' (and thousands '.' stripped) before concluding non-numeric, e.g. `it.toDoubleOrNull() ?: it.replace('.',' ').replace(',', '.').trim().toDoubleOrNull()`-style normalization, so comma-decimal cells count as numeric.
**Found by:** shard D5-rag-local-backup
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/KnowledgeService.kt

### Candidate 20 — Severity: MEDIUM — Category: cost accounting
**Location:** ai/src/main/java/com/ai/data/KnowledgeService.kt:143-156
**Symptom:** AI Usage / cost tracking silently omits all /v1/embeddings spend from knowledge-base indexing (the bulk of embedding cost — the whole corpus is embedded in 32-text batches) and from report-flow RAG query embedding. Only the chat RAG path records an estimate, so the costs screen under-reports real provider spend whenever a remote embedder is used.
**Root cause (claimed):** KnowledgeService.runIndex calls `repository.embed(...)` (KnowledgeService.kt:152) and retrieve calls it at line 237; AnalysisRepository's report paths call retrieve at AnalysisRepository.kt:254-259 and 402-407. The embed() wrapper (ApiDispatch.kt:387-392) collapses EmbedResult to `.vectors`, discarding `tokenUsage` that embedWithStatus returns precisely 'for the caller's cost accounting' (ApiDispatch.kt:251-253, 313-318). Grep over updateUsageStatsAsync call sites shows no knowledge/indexing caller; the only RAG accounting is ChatViewModel.recordRagEmbeddingUsage (ChatViewModel.kt:121-143), which covers chat retrieval only and by token estimate.
**Reproduction:** Create a KB with an OpenAI embedder, index a multi-MB document (hundreds of chunks), then open AI Usage — no embedding tokens or cost appear for the provider.
**Proposed fix:** Have runIndex and retrieve use embedWithStatus and forward tokenUsage (or the estimateTokens fallback when usage is absent, as the chat path does) into settingsPrefs.updateUsageStatsAsync with kinds like "knowledge/index" and "report/rag"; alternatively record usage inside embedWithStatus itself so all embed callers are covered.
**Found by:** shard D5-rag-local-backup
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/MetadataDefaults.kt

### Candidate 21 — Severity: LOW — Category: UI lies
**Location:** ai/src/main/java/com/ai/data/MetadataDefaults.kt:436-457
**Symptom:** On Settings → Default icons, overriding e.g. web (🌐, 'split from TRANSLATE'), aiFind (🤖, 'split from AGENT'), chart (📊), reasoningSweep (🧠) or memo (📝) has no effect at any call site that resolves through forFactoryGlyph (settings/menu/chat-hub rows, SetupScreens.kt:114, SettingsScreen.kt:992, ChatHub.kt:284, ...); conversely overriding the first-declared field with that glyph (languageIcon, agent, statisticsMonitor, reportModelIcon, reportIcon) changes every unrelated row sharing the factory glyph.
**Root cause (claimed):** factoryGlyphMap (MetadataDefaults.kt:449-457) is keyed by the factory glyph STRING with putIfAbsent in declared-field order, so duplicate factory glyphs (🌐 ×4: languageIcon/translationRow/translationCompare/web; 📝 ×2: reportIcon/memo; 🤖 ×2: agent/aiFind; 📊 ×2: statisticsMonitor/chart; 🧠 ×2: reportModelIcon/reasoningSweep) all resolve to the FIRST field's live value. forFactoryGlyph(factoryGlyph) (436-437) therefore cannot distinguish the split fields the data class deliberately separated — defeating the split.
**Reproduction:** Settings → Default icons: change 'reasoning sweep' 🧠 to another glyph; the reasoning-sweep bottom-bar button (rendered via forFactoryGlyph(REASONING_SWEEP)) still shows 🧠. Change 'report model icon' instead and the sweep button changes too.
**Proposed fix:** Key the lookup by field/constant name instead of glyph string (pass the MetadataDefaults constant identity, or give each duplicated constant a unique factory glyph), and have call sites read the specific MetadataIcons field.
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/ModelCooldownStore.kt

### Candidate 22 — Severity: MEDIUM — Category: race condition / lost write
**Location:** ai/src/main/java/com/ai/data/ModelCooldownStore.kt:132-143, 235-250
**Symptom:** When several in-flight calls 429 at once (the normal shape when a provider's daily quota trips during a fan-out: dozens of concurrent calls hit RateLimitRetryInterceptor's bench path simultaneously), one model's hours-long bench can vanish from the published StateFlow (model pickers stop showing 'rate-limited · back HH:mm') and — worse — from the persisted SharedPreferences, so after a restart the bench is gone and the model is retried into the same exhausted quota.
**Root cause (claimed):** markUnavailable is called concurrently from OkHttp worker threads. persist() (lines 235-246) takes `cooldownMap.toMap()` on the calling thread and then submits to the single-thread executor; publish() (248-250) assigns `_cooldowns.value = cooldownMap.toMap()`. Neither the snapshot+submit nor the snapshot+assign pair is atomic: T1 puts A, snapshots {A}; T2 puts B, snapshots {A,B}, submits/assigns; T1 then submits/assigns its stale {A} — last-write-wins on both the prefs and the StateFlow, losing B until the next mutation republished. The map itself stays correct, so in-session isUnavailable() is right, but the flow and the on-disk copy are not.
**Reproduction:** Run a large Gemini fan-out on a free-tier key until the per-day quota trips; multiple concurrent 429s call markUnavailable for different models within milliseconds. Restart the app: occasionally one of the benched models is missing from the Cooldowns CRUD screen.
**Proposed fix:** Serialize snapshotting with mutation: take the toMap() snapshot inside the persistExecutor task (the single thread then guarantees last-submitted=last-written ordering is consistent with map state at execution time), and guard publish() with a small synchronized block (or use _cooldowns.update { cooldownMap.toMap() } under the same monitor as the map mutation). Same pattern applies to markShortBench/publishShortBenches (81-89, 252-262), though those are transient.
**Found by:** shard D3-interceptors
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/OverloadedRetry.kt

### Candidate 23 — Severity: MEDIUM — Category: retry budget / incomplete fix
**Location:** ai/src/main/java/com/ai/data/OverloadedRetry.kt:58-92
**Symptom:** A 529 (Anthropic overloaded) on a non-batch call (chat send, single regenerate, any flow without a runThrottledBatch yielder) blocks the OkHttp worker in Thread.sleep for up to maxRetriesOn529 x min(Retry-After, 5 min) — Anthropic frequently sends Retry-After on 529, so a single chat call can hang ~15 min with the Stop/cancel only honored between attempts (chain.call().isCanceled() is checked once per loop iteration, never mid-sleep).
**Root cause (claimed):** Commit 78c6ee97f ('Avoid inline 429 sleeps without yielder', fixing audit/2026-06-06 bugs_data #32) added `val hasBackoffYielder = ProviderThrottle.backoffPermitYielder.get() != null` and gated RateLimitRetry.kt:142-144 with `|| !hasBackoffYielder`, but the self-described 'mirror' interceptor OverloadedRetryInterceptor was not updated: OverloadedRetry.kt:58-60 computes `if (ProviderThrottle.suppressInlineRetry.get() == true) 0 to 0L else retryLimitsFor529(...)` with no yielder check, so ProviderThrottle.backoffSleep (ProviderThrottling.kt:123-135) falls through to the in-place Thread.sleep for every flow that has no yielder.
**Reproduction:** With tracing on, send a chat message to Anthropic during an overload window where the 529 carries Retry-After: 300. The chat spinner hangs ~5 min per attempt and pressing stop has no effect until the current sleep ends.
**Proposed fix:** Mirror the 429 gate: when backoffPermitYielder is null (and suppressInlineRetry is false), treat maxRetries as 0 and return the 529 so the repository-level coroutine retry (AnalysisRepository.withRetry) handles the wait without pinning a worker — exactly what RateLimitRetry.kt:141-147 does.
**Found by:** shard D3-interceptors
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/PricingCache.kt

### Candidate 24 — Severity: HIGH — Category: cost accounting
**Location:** ai/src/main/java/com/ai/data/PricingCache.kt:285-297 (with ApiModels.kt:881-893)
**Symptom:** Report/secondary/translation/meta costs for Gemini thinking models are systematically too low. Gemini 2.5 models think by default; thoughtsTokenCount routinely equals or exceeds the visible output, so the persisted/displayed spend can be off by 2x or more while Google bills the full amount.
**Root cause (claimed):** GeminiUsageMetadata.toTokenUsage (ApiModels.kt:882-893) deliberately separates thoughtsTokenCount into TokenUsage.reasoningTokens — its own comment says these are "Billed at the output rate, but distinct from candidatesTokenCount". But the canonical cost path PricingCache.computeInOutCost (PricingCache.kt:294-297) computes outCost = usage.outputTokens * pOut and never references usage.reasoningTokens; computeCost delegates to it. Every call site (ReportViewModel:902, SecondaryRunManager:1576/1696, SecondaryCellCalls:111/253, TranslationRunManager:713/755, IconGenerationManager:192, GenerationPhase:547/910, DashboardStats:285) therefore drops the thinking tokens from spend. The chat screen independently proves the intent: ChatScreens.kt:1311-1312 adds usage.reasoningTokens to output for its own estimate.
**Reproduction:** Run a report agent on gemini-2.5-flash/pro, open the raw usage JSON (thoughtsTokenCount > 0), and compare the app's computed cost against promptTokenCount/candidatesTokenCount/thoughtsTokenCount × the model's published rates — the thoughts portion is missing.
**Proposed fix:** In computeInOutCost, bill reasoning tokens at the output rate: outCost = (usage.outputTokens + usage.reasoningTokens) * pOut, and include reasoningTokens in the apiCost pro-rata token-ratio fallback denominators.
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 25 — Severity: MEDIUM — Category: cache incoherence
**Location:** ai/src/main/java/com/ai/data/PricingCache.kt:1571-1648
**Symptom:** Tapping 🗑 on a tier in Caches → Pricing tiers (or 'clear all' for the pricing card) appears to do nothing: the tier row reappears with the build-time bundled entry count and 'never fetched', and the deleted catalog keeps feeding price lookups. The first lookup after deletion also janks the UI.
**Root cause (claimed):** deleteTier (PricingCache.kt:1571-1603) and clearInfoProviderTiers (1610-1648) delete the filesDir/pricing blob and null the in-memory map, but loadBlob (1259-1263) falls back to assets/info-providers/<key>.json, which exists for all six tiers (litellm_pricing.json 404K, models_dev_pricing.json 748K, etc.). The next ensureLoaded call finds the tier var null and reloads it from the bundled asset — clearInfoProviderTiers' own comment (1645-1647) wrongly claims tiers 'lazily repopulate on the next refresh'. Worse, both functions intentionally leave preloadCompleted=true, so ensureLoaded's main-thread guard (1297: 'if (!preloadCompleted && isMainThread()) return') does not fire and the very next main-thread getPricing/catalogStats call (CachesScreen.kt:189/209 recompose) synchronously parses up to ~1.4 MB of JSON inside synchronized(lock) on the UI thread.
**Reproduction:** Admin → Caches → Pricing tiers → 🗑 on 'models.dev' → the row re-renders with the bundled snapshot's count and 'never fetched' after a visible main-thread stall.
**Proposed fix:** Persist a per-tier 'deleted' marker (or write an empty blob file) so loadBlob skips the bundled-asset fallback for explicitly deleted tiers; alternatively reload deleted tiers only on a background thread and document asset re-seeding in the UI.
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 26 — Severity: MEDIUM — Category: partial-write recovery
**Location:** ai/src/main/java/com/ai/data/PricingCache.kt:1094-1117
**Symptom:** After an llm-prices refresh during flaky connectivity, the tier silently shrinks (e.g. from 10 vendors to 1) and the refresh is reported as Done with a fresh timestamp; pricing for the missing vendors falls through to lower-precedence tiers until the next manual refresh.
**Root cause (claimed):** The vendor loop (PricingCache.kt:1098-1102) does 'ApiFactory.fetchUrlAsString(url) ?: continue' per vendor (fetchUrlAsString returns null on any non-2xx/exception, ApiClient.kt:302-318), then saves whatever accumulated as the complete tier (1105-1110) and bumps llmPricesTimestamp. Only the all-vendors-failed case (combined.isEmpty(), line 1104) keeps the previous data. Every other tier fetch is all-or-nothing; this one alone overwrites a complete cached catalog with a partial one, defeating the 'kept previous N entries' design (previousCacheInfo / RefreshScreen keptPreviousRow).
**Reproduction:** Start the llm-prices refresh and cut network after the first vendor file downloads → tier now contains only that vendor's entries with a fresh timestamp; the other nine vendors' cached prices are gone.
**Proposed fix:** Track per-vendor failures; on any failure either keep the previous map entirely, or merge: keep previous entries for failed vendors and only replace the vendors that fetched successfully (and surface the partial state in the step status).
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 27 — Severity: LOW — Category: race condition / data loss
**Location:** ai/src/main/java/com/ai/data/PricingCache.kt:186-198
**Symptom:** If a manual-override add/remove runs on the main thread before the pricing preload completes, the persisted override map is replaced by a single-entry map (set) or the literal JSON 'null' (remove) — every previously saved manual cost override is permanently lost, and a 'null' blob leaves loadManualPricing returning null forever.
**Root cause (claimed):** setManualPricing (PricingCache.kt:186-192) and removeManualPricing (194-198) call ensureLoaded, which short-circuits on main thread while !preloadCompleted (1296-1297) without loading the persisted map. setManualPricing then does 'manualPricing ?: mutableMapOf().also { manualPricing = it }' (189) and saveManualPricing (255-258) writes gson.toJson of that fresh map over KEY_MANUAL_PRICING, dropping all stored entries. removeManualPricing with manualPricing==null writes gson.toJson(null) == "null", which loadManualPricing (1418-1423) parses to null (fromJson("null") returns null without throwing), so manualPricing never repopulates. Callers are main-thread UI handlers (ui/cruds/costsmanualoverride/add.kt:27, edit.kt:38-41, list.kt:65, StatisticsScreen.kt:174-177). Window is narrow (preload usually completes before these screens are reachable; clearAll also resets preloadCompleted=false but wipes the disk map anyway), hence LOW.
**Reproduction:** Hard to hit organically: requires reaching the override add/delete UI within the cold preload window (or after a failed preload).
**Proposed fix:** Make set/remove load the persisted map regardless of thread (the map is tiny — read it directly instead of via ensureLoaded), and guard saveManualPricing against manualPricing==null.
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 28 — Severity: LOW — Category: cost accounting
**Location:** ai/src/main/java/com/ai/data/PricingCache.kt:285-297
**Symptom:** For models whose LiteLLM entry only defines *_above_128k_tokens tier prices (e.g. Gemini 1.5-class, Qwen-Long), calls with total input between 128k and 200k tokens are costed at the base rate although the provider charges the higher tier — persisted costs undercount for that band.
**Root cause (claimed):** parseLiteLLMJson maps 'input_cost_per_token_above_200k_tokens ?: input_cost_per_token_above_128k_tokens' (and the three sibling fields) into the single promptPriceAbove200k/... slots without recording which threshold applied (PricingParsers.kt:109-116). computeInOutCost then hardcodes 'totalInput > 200_000' (PricingCache.kt:287) as the only tier boundary, so a 128k-threshold price is applied with a 200k threshold.
**Reproduction:** Run a ~150k-token-input call on a model that LiteLLM prices with above_128k fields; compare the computed cost to the provider's tiered price.
**Proposed fix:** Carry the tier threshold in ModelPricing (e.g. tierThresholdTokens = 128_000 or 200_000 depending on which LiteLLM key matched) and compare totalInput against it in computeInOutCost.
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 29 — Severity: LOW — Category: race condition
**Location:** ai/src/main/java/com/ai/data/PricingCache.kt:835-844
**Symptom:** Immediately after a LiteLLM/models.dev refresh, a (provider, model) lookup can keep returning the OLD catalog's pricing/meta indefinitely (until the next refresh, deleteTier, or restart), e.g. a stale price in cost tables or stale vision/reasoning flags.
**Root cause (claimed):** fetchLiteLLMPricingOnline assigns the new maps and clears litellmMetaLookupCache/litellmPricingLookupCache inside synchronized(lock) (PricingCache.kt:835-844), but readers (findLiteLLMMeta 532-546, findLiteLLMPricing 598-612, findModelsDevMeta 905-918) run without the lock: a reader can snapshot the old map reference, lose the race while scanning ~1k entries, and then write its old-map resolution into the memo AFTER the refresh cleared it. The memo (keyed provider|model) then shadows the new catalog for that key. Same pattern in fetchModelsDevOnline (875-883) and ensureLoadedLocked.
**Reproduction:** Timing-dependent: scroll a model list (memo-populating scans) while a LiteLLM refresh completes; a row's price can stay at the pre-refresh value.
**Proposed fix:** Version the memo entries (stamp them with the catalog map reference or a generation counter and validate on read), or have readers re-check the map reference after the scan before publishing into the memo.
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/ProviderRegistry.kt

### Candidate 30 — Severity: MEDIUM — Category: registry merge/sync
**Location:** ai/src/main/java/com/ai/data/ProviderRegistry.kt:119-143
**Symptom:** Import providers via Settings → Import/Export ('providers' file or the All-bundle 'providers' section) shows 'Updated N providers' and the values apply — but after the next app start, every imported field of a bundled provider that differs from assets/providers/ has silently reverted to the bundled value.
**Root cause (claimed):** upsertFromJson (ProviderRegistry.kt:119-143) replaces whole provider entries but never calls ProviderFieldTimestamps.bump, even though its own doc (line 112) says it consumes 'a user-picked JSON blob' (callers: ImportExportScreen.kt:1252 and 1578 — both user-initiated imports). ProviderRegistry.update's comment (lines 205-209) and ProviderFieldTimestamps' header (lines 14-17) misclassify it as an asset-driven path. The startup delta-sync (AppViewModel.kt:684 → syncFromAsset) then sees imported-vs-asset diffs with null timestamps and pulls the asset values back (ProviderRegistry.kt:254-256), undoing the user's import for every provider that exists in the bundle. Custom (non-bundled) providers are unaffected.
**Reproduction:** Export providers, change e.g. modelFilter of a bundled provider in the JSON, import it (toast 'Updated 1 provider'), restart the app → the field is back to the bundled value; applog shows 'syncFromAsset: <id> pulled modelFilter'.
**Proposed fix:** In upsertFromJson, diff each replaced entry against the previous one with diffTrackedFields and call ProviderFieldTimestamps.bump(id, changed) — mirroring update() — since the source is user intent, not the bundle.
**Found by:** shard D4-pricing-registry
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/RateLimitRetry.kt

### Candidate 31 — Severity: LOW — Category: retry hint fidelity
**Location:** ai/src/main/java/com/ai/data/RateLimitRetry.kt:119-132
**Symptom:** For fixed-model batches (Fan Out, Judge-the-judges) hitting Gemini per-minute 429s — which usually carry the wait only in the JSON body's RetryInfo.retryDelay, not in a Retry-After header — the short bench always falls back to typeABenchBaseMs (10 s) instead of the server's actual hint, so the item is requeued too early (extra doomed call + another bench attempt burned against typeABenchMaxAttempts) or benched too long.
**Root cause (claimed):** Line 125 calls `retryAfterHintMs(response, null)` with peekedBody = null, even though the long-bench block above (line 84) already peeked and decoded the same response body; the variable is scoped to the first `run { }` block, so the type-A block can't see it and deliberately skips the body parse that retryAfterHintMs supports.
**Proposed fix:** Hoist the `peekedBody` read above both run-blocks (it is already read unconditionally for every 429) and pass it to retryAfterHintMs in the type-A path, matching the long-bench path's fidelity.
**Found by:** shard D3-interceptors
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/ReportBundle.kt

### Candidate 32 — Severity: LOW — Category: UI counter drift
**Location:** ai/src/main/java/com/ai/data/ReportBundle.kt:242-247
**Symptom:** When a bundle contains a secondary/*.json entry that fails to parse, the Reports-hub import row's "Loading file X of Y" counter finishes with X < Y (it never reaches the published total) before the placeholder disappears.
**Root cause (claimed):** setTotal at ReportBundle.kt:243-245 counts every zip entry under secondary/ (`entries.keys.count { it.startsWith("secondary/") && it.endsWith(".json") }`), but tick() for secondaries runs only inside `parsedSecondaries.forEach` (lines 388-390), and parsedSecondaries was built with mapNotNull that drops malformed entries (lines 286-293). Trace entries by contrast tick before parsing (line 319, 'even a malformed skip') — the secondary pass lacks the same treatment.
**Reproduction:** Create a report bundle, corrupt one secondary/<id>.json into invalid JSON, import it, and watch the hub progress row stop one short of its total.
**Proposed fix:** Mirror the trace pass: count parse failures during pass 1 and advance the counter for each skipped secondary (or compute the total from parsedSecondaries.size instead of the raw entry count).
**Found by:** shard D5-rag-local-backup
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/ReportStorage.kt

### Candidate 33 — Severity: MEDIUM — Category: lost field on duplicate
**Location:** ai/src/main/java/com/ai/data/ReportStorage.kt:2646-2735
**Symptom:** Duplicating a report whose worker routing was configured (Report - select workers / Manage 👷: CUSTOM report-info workers, OWN_MODEL model info, REPORT_MODELS / SELECT_ONCE batches, stored batchWorkers, ROUND_ROBIN selection) produces a copy whose 👷 config is back to all defaults. Regenerates and every worker batch (Fan Meta, Translation, Tournament, Judges, Compare, TransRank, Rerank, Moderation) on the copy route through different workers than the original, silently diverging — exactly the failure mode copyReport's own comments say the copied parameterPresetIds/advancedParameters/reportSystemPromptId exist to prevent.
**Root cause (claimed):** The Report constructed at lines 2667-2709 passes parameterPresetIds/advancedParameters/selectionParamsById/reportSystemPromptId/knowledgeBaseIds/webSearchTool/reasoningEffort from src (each with a comment explaining the replay-fidelity rationale) but never passes `workerConfig`, and no `copy.workerConfig = src.workerConfig` assignment follows (only icon/language visible-state fields are mirrored at 2717-2729). Report.workerConfig therefore takes its default `ReportWorkerConfig()` (ReportModels.kt:330). workerConfig was added by the recent worker-config feature commit and createReport (line 174) does thread it through — only the copy path was missed. (promptHistory and userNotes are likewise silently dropped by the copy with no design comment, unlike pinned/costsFromDeletedItems which have explicit justifications.)
**Reproduction:** Create a report, set Manage → 👷 to Batches=REPORT_MODELS + Round robin, duplicate the report, open the copy's 👷 screen: everything is back to PROMPT/WHEN_AVAILABLE.
**Proposed fix:** Pass `workerConfig = src.workerConfig` in the copied Report (and decide explicitly — with a comment — whether promptHistory/userNotes should be carried; if intentional, document like pinned/costsFromDeletedItems).
**Found by:** shard D1-storage
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 34 — Severity: LOW — Category: token accounting fidelity
**Location:** ai/src/main/java/com/ai/data/ReportStorage.kt:281-286
**Symptom:** The persisted ReportAgent.tokenUsage for every report agent contains only inputTokens/outputTokens — provider-reported cost and cached/cache-creation/reasoning token counts from the response are discarded on disk. Any consumer of the extended fields on agent rows (and the legacy cost-recompute fallback in ContentDisplay.kt:643-657, which calls computeInOutCost on the stored usage when the frozen split is absent) misprices cached input tokens at the full input rate and shows zero cached/reasoning tokens.
**Root cause (claimed):** Lines 282-285 rebuild the merged usage as `TokenUsage(inputTokens = prior + new, outputTokens = prior + new)`, omitting the other four fields of TokenUsage (AnalysisRepository.kt:31-40). The sibling merge in SecondaryResultStorage.mergeTokenUsage (SecondaryResult.kt:564-579) preserves all of them, and the identical truncation in recordTournamentMatch was already classified a bug and fixed in the 2026-06-08 audit (its Bug 3, TransRank) by threading the full TokenUsage through. Impact is bounded because the frozen inputCost/outputCost split is computed from the full usage before persistence (ReportViewModel.kt:901-905) — this is fidelity loss, not a live cost error.
**Reproduction:** Run a report against a provider that reports cached or reasoning tokens (Anthropic prompt caching, Gemini thinking), then read the report JSON: agents[].tokenUsage carries only inputTokens/outputTokens while rawUsageJson shows the cached/reasoning counts.
**Proposed fix:** Merge all TokenUsage fields like SecondaryResultStorage.mergeTokenUsage does (sum cached/cacheCreation/reasoning tokens, sum apiCost when either side has one).
**Found by:** shard D1-storage
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 35 — Severity: LOW — Category: audit-trail drift
**Location:** ai/src/main/java/com/ai/data/ReportStorage.kt:503-519
**Symptom:** After Housekeeping's delete-all flow, every retained audit file under <filesDir>/audit/ still ends with normal activity lines — the Monitor → Audit list shows all wiped reports with no indication they were deleted, breaking the documented retention contract ("the audit file is kept when its report is deleted (a trailing Report deleted line is appended)", AuditLog.kt:46-48).
**Root cause (claimed):** deleteReport appends `AuditLog.append(reportId, "Report deleted")` after the cascade (line 500), but deleteAllReports' per-id cascade loop (lines 511-516) runs SecondaryResultStorage.deleteAllForReport / RegenerateBatchStorage.delete / ApiTracer.deleteTracesForReport without the AuditLog trailer.
**Reproduction:** Create two reports, run Housekeeping → delete all reports, open Monitor → Audit: both files are listed and end with their last activity line instead of a deletion marker; deleting a single report instead does append it.
**Proposed fix:** Add `AuditLog.append(reportId, "Report deleted")` inside the deletedIds.forEach loop in deleteAllReports (matching deleteReport).
**Found by:** shard D1-storage
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/RerankModerationApi.kt

### Candidate 36 — Severity: LOW — Category: locale formatting
**Location:** ai/src/main/java/com/ai/data/RerankModerationApi.kt:250
**Symptom:** On the owner's comma-decimal device, native (Cohere) rerank results show reasons like "Relevance score: 0,8312" while the rest of the pipeline and exports use dot decimals — inconsistent display in the secondary-result detail screen and HTML export. Display-only (id/rank/score are separate numeric JSON fields), no parse-back crash.
**Root cause (claimed):** `"Relevance score: %.4f".format(r.relevance_score)` uses the default locale; on nl-NL %.4f renders with a decimal comma.
**Reproduction:** Run a Cohere native rerank on a device set to nl-NL and open the rerank detail — reasons show comma decimals.
**Proposed fix:** Use String.format(Locale.US, "Relevance score: %.4f", r.relevance_score) (or Locale.ROOT) to match the rest of the JSON pipeline.
**Found by:** shard D2-dispatch
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/SecondaryDataVersion.kt

### Candidate 37 — Severity: LOW — Category: stale UI / dead refresh key
**Location:** ai/src/main/java/com/ai/data/SecondaryDataVersion.kt:41-47
**Symptom:** AiStatReportsScreen keys its produceState on `SecondaryDataVersion.version` (AiDashboardScreen.kt:776, comment: "refreshes when reports / secondaries actually change"), but secondary-only changes (placeholder batches written via saveAll, row deletes, chat-message updates on pairs, etc.) never tick that key — the screen's secondary counters refresh only when a report-file write or the resume tick happens to coincide.
**Root cause (claimed):** SecondaryDataVersion.bump(reportId, kind) updates only the lazily-created per-report/per-kind flows and returns without touching `_version` (lines 41-47); every storage write path passes a non-blank reportId, so the global flow stays at 0 forever. The sibling ReportDataVersion.bump(reportId) (ReportStorage.kt:44-51) explicitly also ticks `_version` with a comment stating that exact requirement ("so screens still on [version] keep refreshing") — SecondaryDataVersion lacks the same line. versionFor(null/blank) also falls back to the same dead global flow.
**Reproduction:** Open Statistics → Reports, then from another flow create a large batch of secondary placeholders (saveAll) without any report-file write: the secondary tallies do not refresh until a report write or screen re-resume.
**Proposed fix:** Mirror ReportDataVersion: add `_version.update { it + 1 }` at the end of bump(reportId, kind), bumpReport, and bumpMany (or migrate AiStatReportsScreen to a per-report or aggregate key).
**Found by:** shard D1-storage
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/SecondaryResult.kt

### Candidate 38 — Severity: MEDIUM — Category: cache incoherence on write failure
**Location:** ai/src/main/java/com/ai/data/SecondaryResult.kt:386-388, 534-539, 616-617, 644-645, 675-676, 702-703, 731-732, 755-756, 785-786, 817-818, 847-848, 875-876, 901-902, 951-952, 988-989, 1016-1017, 1047-1048, 1075-1076, 1115-1116, 1152-1153
**Symptom:** When a row write fails (disk-full or any I/O error), every secondary-result read for the rest of the process lifetime returns the NEW row that never reached disk: icons/titles/costs/content/tournament verdicts show as committed, saveIfStillPresent() returns true (so callers skip their cost-recovery fallback, e.g. ReportViewModel's bumpCostsFromDeletedItems on !persisted), SecondaryDataVersion is bumped, and after an app restart all of those edits silently revert.
**Root cause (claimed):** updateResult (line 386: `target.writeTextAtomic(gson.toJson(next))` with the Boolean discarded, then `rememberCachedResult(reportId, target, next)`), saveIfStillPresent (535-536, then `return true` + version bump), and all 17 field-scoped helpers (bumpResultInputOutputCost, bumpFanOutIconCost, setFanOutIconAndTier, setFanOutIconError, markFanOutFanMetaStarted(+Batch), setRowIcon, clearFanOutIconState(KeepingCost), bumpFanOutTitleCost, setFanOutTitle, setFanOutTitleError, recordFanMetaResult, clearFanOutTitleState(KeepingCost), resetRowToPlaceholder, recordTournamentMatch, recordCompareCell) all discard writeTextAtomic's return and then cache the new object. Because the failed write leaves the destination file untouched, rememberCachedResult stores CachedEntry(file.lastModified(), file.length(), NEW row) — i.e. the OLD file's mtime/length fingerprint paired with the NEW parsed object — so every fingerprint validation in get()/listForReport()/readCachedOrDisk() matches and serves the phantom row. Contrast: save() (lines 186-189) checks the Boolean and aborts, and ReportStorage.saveReport (lines 726-735) logs + skips the version bump on failure; ChatHistoryManager.saveSession had this exact class fixed in a prior audit (its comment at lines 54-59 documents why the Boolean must be forwarded).
**Reproduction:** Fill device storage (or inject a writeTextAtomic failure), then complete a fan-out pair or tap a Fan Meta retry: the row shows the new icon/title/cost, the report screens render it, but `cat` of the row JSON shows the old state; restarting the app reverts the row.
**Proposed fix:** In every mutator, check writeTextAtomic's return: on false, do NOT call rememberCachedResult (or explicitly cache.remove(name) to force a re-read), do not bump SecondaryDataVersion, and propagate failure (saveIfStillPresent should return false so callers run their lost-cost fallback).
**Found by:** shard D1-storage
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/SharedContent.kt

### Candidate 39 — Severity: MEDIUM — Category: wrong-bound parsing
**Location:** ai/src/main/java/com/ai/data/SharedContent.kt:33-42
**Symptom:** Sharing a URL whose path contains parentheses (Wikipedia disambiguation pages like https://en.wikipedia.org/wiki/Android_(operating_system)) yields a truncated URL ending at the last character before ')'. The Knowledge URL ingest then fetches a wrong/404 page, and isUrl-driven UI operates on the broken value.
**Root cause (claimed):** URL_REGEX at SharedContent.kt:41 is `https?://[^\s)\]>}"]+` — ')' is excluded from the URL character class, so matching stops before the closing paren of a legitimate URL; '(' is allowed, leaving an unbalanced, invalid URL such as "…/Android_(operating_system". The result flows directly into the knowledge ingest queue at AppNavHost.kt:217 (`sharedContent.firstUrl` → pendingKnowledgeUris) and into KnowledgeService.indexUrl.
**Reproduction:** In Chrome open https://en.wikipedia.org/wiki/Android_(operating_system), Share → this app → Add to Knowledge as URL. The indexed origin lacks the trailing ')'.
**Proposed fix:** Allow ')' when it has a matching '(' earlier in the candidate: match greedily including ')' then post-trim only unbalanced trailing parens (count '(' vs ')'), the standard autolink heuristic — keep the existing trailing-punctuation trim for . , ; : ! ?.
**Found by:** shard D5-rag-local-backup
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/TournamentRanking.kt

### Candidate 40 — Severity: LOW — Category: locale-format consistency
**Location:** ai/src/main/java/com/ai/data/TournamentRanking.kt:200, 257, 355
**Symptom:** On the comma-decimal device, tournament rank rows show mixed decimal styles depending on the selected aggregation method: Copeland/Schulze/Colley/TrueSkill reasons render "Won 3.5 of 6" / "Colley rating 0.625" (Locale.US), while Davidson shows "Davidson strength 0,123 · tie 0,45", Markov "Stationary share 37,5%", and Colley's degenerate fallback "Win share 50%" with comma fractions — visibly inconsistent within the same screen and exports.
**Root cause (claimed):** copeland (line 129), schulze (line 328), colley (line 362), and trueskill2 (line 436) deliberately use String.format(java.util.Locale.US, …) — line 125's comment says exactly why ("so the reason renders a dot, not a comma, on comma-decimal locales") — but davidson (line 200), markov (line 257), and the colley solve-failure fallback (line 355) use Kotlin's default-locale `"…%.3f".format(…)`. The reasons are display-only JSON strings (Gson escapes them; extractTopRankedIds parses only the numeric id/score fields), so no crash — pure display drift.
**Reproduction:** Device locale nl-NL → run a tournament → toggle the method between Copeland and Davidson on the result screen and compare the reason column decimal separators.
**Proposed fix:** Use the same Locale.US-anchored String.format for the three remaining reason strings.
**Found by:** shard D6-models-prefs
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/local/LocalEmbedder.kt

### Candidate 41 — Severity: MEDIUM — Category: race condition / native lifecycle
**Location:** ai/src/main/java/com/ai/data/local/LocalEmbedder.kt:231-249
**Symptom:** Removing a local embedder model (or running housekeeping 'clear all configuration') while a KB indexing batch or local generate is in flight closes the MediaPipe native task runner under an active call — native crash (SIGSEGV/JNI abort) or hard failure of the in-flight batch.
**Root cause (claimed):** LocalEmbedder.embed serializes calls per instance via `synchronized(embedder)` (LocalEmbedder.kt:266), but release (line 231-233: `instances.remove(modelName)?.close()`), releaseAll (235-238) and clearAll (243-249) call close() without acquiring that same monitor, so close() runs concurrently with an in-progress embedder.embed(input). Identical pattern in LocalLlm: generate uses `synchronized(engine)` (LocalLlm.kt:185) while release/releaseAll/clearAll (153-171) close unsynchronized. Triggerable from the app's own coroutines: LocalRuntimeScreens.kt:149 and :335 (user taps Remove while an index/generate coroutine runs) and AppViewModel.kt:1102-1103 (clearAllConfiguration).
**Reproduction:** Start indexing a large file into a LOCAL-embedder KB (takes minutes), then open Local LiteRT screen and tap Remove on the model mid-index.
**Proposed fix:** Close under the instance monitor: `instances.remove(name)?.let { synchronized(it) { it.close() } }` in release/releaseAll/clearAll for both LocalEmbedder and LocalLlm, so close waits for the in-flight call to finish.
**Found by:** shard D5-rag-local-backup
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/preferences/PromptHistoryStore.kt

### Candidate 42 — Severity: LOW — Category: cache incoherence on write failure
**Location:** ai/src/main/java/com/ai/data/preferences/PromptHistoryStore.kt:53-58
**Symptom:** On a failed prompt-history write (disk-full/I/O error), the session keeps showing the new/reordered history entry (load() serves the cache), but the entry vanishes after an app restart — a silent lost write with the caller none the wiser.
**Root cause (claimed):** Line 56-57: `file.writeTextAtomic(gson.toJson(snapshot)); cache = snapshot` — the Boolean from writeTextAtomic is discarded and the cache is unconditionally replaced with the unpersisted snapshot, so memory and disk diverge until process death. Same class as the SecondaryResultStorage finding; ChatHistoryManager.saveSession was already fixed for this exact pattern (its lines 54-61 forward the Boolean).
**Reproduction:** With storage full, submit a report so add(title, prompt) runs: the prompt-history picker shows the entry; restart the app and it is gone.
**Proposed fix:** Only assign `cache = snapshot` when writeTextAtomic returns true (else log and keep the previous cache, or null it to force a disk re-read), and consider propagating the Boolean from add()/saveList().
**Found by:** shard D1-storage
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/data/preferences/SettingsPreferences.kt

### Candidate 43 — Severity: LOW — Category: cost accounting
**Location:** ai/src/main/java/com/ai/data/preferences/SettingsPreferences.kt:490-513, 673-690, 944-952
**Symptom:** Token/cost rows recorded while the usage-stats file temporarily fails to deserialize (the documented ProviderRegistry-initialising race) vanish: they are never persisted and never appear in AI Usage, and the flush window is consumed without a write.
**Root cause (claimed):** ensureUsageStatsCache deliberately refuses to commit an empty cache when the file had rows but every row failed to parse (`if (cache.isEmpty() && arr.size() > 0) return@synchronized cache`, line 511) so the next read can retry — but it still RETURNS that throwaway map. updateUsageStats (line 673) then `stats.compute(key)`s the new row into the orphan, and scheduleUsageStatsFlush (lines 944-948) advances lastUsageStatsFlush BEFORE bailing on `usageStatsCache?.let { … } ?: return`, so nothing is saved and the next ensureUsageStatsCache call re-reads the file — the increment is gone. The same call's category/report ledger rows do persist, so the per-model table undercounts relative to them.
**Reproduction:** Cold-start with a usage-stats.json referencing a provider id that resolves only after ProviderRegistry init; fire an API call in that window — its tokens never reach usage-stats.json.
**Proposed fix:** Have updateUsageStats detect the uncommitted state (ensureUsageStatsCache could return null / a sentinel when it refuses to commit) and either retry once or buffer the increment until a committed cache exists; also move the lastUsageStatsFlush assignment after the null-snapshot check in scheduleUsageStatsFlush.
**Found by:** shard D6-models-prefs
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 44 — Severity: LOW — Category: race condition
**Location:** ai/src/main/java/com/ai/data/preferences/SettingsPreferences.kt:882-910, 912-938, 737-756
**Symptom:** Opening the Spend & Usage screen while a report is actively generating can permanently undercount that report's row (and a category bucket) in the usage stats: increments recorded around the rebuild moment disappear from the displayed and persisted stats.
**Root cause (claimed):** recordReportApiCallCost (lines 737-756) and recordUsageCategoryStats (699-710) fetch the current ConcurrentHashMap via ensure*Cache() and compute into it (the report path under usageStatsLock, the category path with no lock). rebuildUsageReportStatsFromReports (assignment at line 936) and rebuildUsageCategoryStatsFromUsageStats (line 908) — reachable from loadUsage*Stats and reconcileReportCostLedgers (line 585), none of which hold usageStatsLock around the rebuild — build a NEW map from disk and replace the @Volatile field. A writer that obtained the old instance just before the swap lands its compute in the orphaned map; the rebuilt map was sourced from disk, which does not yet contain the <2s-unflushed rows, so those increments are lost from the caches and from the next save.
**Reproduction:** Start a many-agent report; while rows are completing, open AI Usage → Spend & usage (triggers reconcileReportCostLedgers → rebuildUsageReportStatsFromReports). Compare the report row's callCount with the report's own apiCallCosts ledger afterwards — the stats row can be lower until a later full rebuild.
**Proposed fix:** Run the rebuilds (or at least the cache-swap plus a merge of pending in-memory rows) under usageStatsLock, and flush pendingReportApiCallCosts before rebuilding from disk.
**Found by:** shard D6-models-prefs
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/model/SettingsModels.kt

### Candidate 45 — Severity: MEDIUM — Category: referential cleanup
**Location:** ai/src/main/java/com/ai/model/SettingsModels.kt:877, 970
**Symptom:** Deleting an agent (or a whole provider) is supposed to reset internal prompts bound to that agent back to the "*select" sentinel (the comment at SettingsModels.kt:963-968 says exactly this, to avoid showing a dead binding). It never happens: the prompt keeps the deleted agent's name, the edit screen still shows "Bound to the agent named 'X' (resolved from Settings.agents at run time)" for an agent that no longer exists, and if a new agent is later created with the same name the prompt silently re-binds to it.
**Root cause (claimed):** InternalPrompt.agent stores the agent NAME — InternalPromptsScreen.kt:380 writes `agent = n` where n comes from `aiSettings.agents.map { it.name }` (line 85), and resolvePromptAgent (SettingsModels.kt:716) looks it up with `it.name.equals(prompt.agent, ...)`. But removeAgent compares it to the UUID: `if (it.agent == agentId) it.copy(agent = "*select")` (line 970), and removeProvider builds `removedAgentIds` from `.map { it.id }` and checks `if (p.agent in removedAgentIds)` (lines 865, 877). A name never equals a UUID, so both branches are dead code.
**Reproduction:** Create agent "MyAgent", pin an internal prompt to it (Settings → Internal prompts → agent dropdown), then delete the agent from the Agents CRUD. Re-open the prompt: it still claims to be bound to 'MyAgent'; Settings.internalPrompts still carries agent="MyAgent" instead of "*select".
**Proposed fix:** Collect the removed agents' NAMES (case-insensitively) before filtering and compare `p.agent` against those: e.g. in removeAgent capture `agents.firstOrNull { it.id == agentId }?.name` and reset prompts whose agent equals that name; in removeProvider map the removed agents to their names. Alternatively store agent ids in InternalPrompt.agent and migrate resolvePromptAgent.
**Found by:** shard D6-models-prefs
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 46 — Severity: MEDIUM — Category: resolver correctness
**Location:** ai/src/main/java/com/ai/model/SettingsModels.kt:343-348, 761-764, 1023-1025
**Symptom:** With experimental features on and a .task model installed, the swarm member picker (SwarmsScreen.kt:76 uses ReportSelectModelsScreen, which appends AppService.LOCAL at Selection.kt:238) lets the user add a Local model to a swarm. But adding that swarm to a report silently omits the Local member (only the cloud members land in the selection), and a Swarm-type Worker in a workers-prompt chain also expands without the Local member — no error, no explanation beyond a "1/2 members" count.
**Root cause (claimed):** getProviderState (SettingsModels.kt:343-348) returns "not-used" whenever getApiKey(service) is blank — before consulting the stored state. AppService.LOCAL is synthetic (AppService.kt:234-240): it is not in Settings.providers, has no API key, and can never have one, so isProviderActive(LOCAL) is always false. expandSwarmToModels (line 1023) and expandWorker's swarm branch (lines 761-764) both filter members with `isProviderActive(it.provider)`, so a (Local, model) member is unconditionally discarded — even though a directly-picked Local model works fine as a report model (dispatch routes to LocalLlm), and reportModelWorkers in the new worker-config feature builds Local workers without this filter.
**Reproduction:** Enable Experimental features, import a .task model, create a swarm and add provider "Local" + the model plus one cloud model. New report → +Swarm → pick it: only the cloud model is added. Same swarm referenced as a Worker in a workers prompt expands to the cloud member only.
**Proposed fix:** Special-case the synthetic provider in getProviderState / isProviderActive (e.g. `if (service.id == AppService.LOCAL.id) return if (stored == "inactive") "inactive" else "ok"`), or filter on `it.provider.id == AppService.LOCAL.id || isProviderActive(it.provider)` at the expansion sites. Alternatively block Local from being added as a swarm member in SwarmsScreen with a visible message.
**Found by:** shard D6-models-prefs
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 47 — Severity: LOW — Category: referential cleanup
**Location:** ai/src/main/java/com/ai/model/SettingsModels.kt:895, 956
**Symptom:** Deleting a Parameters preset or System prompt leaves any internal prompt that referenced it (by id) holding a dangling UUID forever. Functionally it degrades to "*NONE" (getParametersByIdOrName returns null and the edit screen shows "No parameters preset"), so no wrong call is made — but the intended reset (`it.copy(parameters = "*NONE")`) never fires for rows the current app writes, leaving permanent dead references in eval_prefs.
**Root cause (claimed):** InternalPromptsScreen stores stable IDs (`selectedParametersRef = ids.firstOrNull()` at line 200, `selectedSystemPromptRef = id` at line 211; the data-class doc at SettingsModels.kt:246-249 says "New saves store stable ids"). removeParameters (line 956) and removeSystemPrompt (line 895) only compare `it.parameters == removedName` / `it.systemPrompt == removedName` — the removed preset's NAME — which a stored UUID never matches. Agents/flocks/swarms/providers are cleaned by id in the same functions; only internalPrompts uses the name-only match.
**Reproduction:** Set a Parameters preset on an internal prompt, then delete that preset from the Parameters CRUD. Inspect ai_meta_prompts in eval_prefs: the prompt still carries the deleted preset's UUID instead of "*NONE".
**Proposed fix:** Match both forms: `if (it.parameters == parametersId || (removedName != null && it.parameters == removedName)) it.copy(parameters = "*NONE")`, and the analogous change in removeSystemPrompt.
**Found by:** shard D6-models-prefs
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/ui/admin/BackupRestoreScreen.kt

### Candidate 48 — Severity: HIGH — Category: restore integrity / stale-cache writeback
**Location:** ai/src/main/java/com/ai/ui/admin/BackupRestoreScreen.kt:95-143
**Symptom:** After a successful restore, the app stays fully interactive behind a non-blocking 'Restart application' banner. Any write between restore-return and the manual restart persists pre-restore in-memory state over the freshly restored disk state: an in-flight chat completion re-saves the old session, any settings mutation re-serializes the entire stale Settings object (API keys, agents, prompts) over the restored eval_prefs, and the chat history screen shows pre-restore sessions from the in-memory cache.
**Root cause (claimed):** BackupManager.kt:53-58 documents the integrity contract: 'HousekeepingScreen handles this by killing the process and relaunching the activity once restore returns'. The only actual caller, BackupRestoreScreen.kt:106-108, instead sets `restartMessage` and renders RestartAppBanner (line 141-143) — the comment at lines 44-49 says explicitly 'we no longer block every other interaction with a modal'. Nothing stops in-flight jobs before restore or invalidates in-memory caches after: ChatHistoryManager keeps `@Volatile private var cachedSessions/cachedHeaders` (ChatHistoryManager.kt:25-26) that survive the filesDir wipe, and AppViewModel.updateSettings (e.g. AppViewModel.kt:940-994) writes the whole stale in-memory Settings back through SharedPreferences, clobbering every restored key in eval_prefs.
**Reproduction:** Open a chat with a slow streaming model, start the reply, switch to Backup & Restore and restore a backup while the stream runs. When the stream completes, ChatViewModel saves the pre-restore session into the restored filesDir. Alternatively: restore, ignore the banner, toggle any setting — the stale full Settings snapshot overwrites the restored prefs.
**Proposed fix:** Either restore the enforced restart (call restartApp(context) immediately on success, as the BackupManager contract assumes), or before applying the restore: cancel running jobs, and after applying: invalidate ChatHistoryManager / ApiTracer / ReportStorage caches and block the settings StateFlow from persisting until relaunch. At minimum make the restart dialog modal and non-dismissable.
**Found by:** shard D5-rag-local-backup
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/ui/report/manage/Savers.kt

### Candidate 49 — Severity: MEDIUM — Category: lossy rememberSaveable saver
**Location:** ai/src/main/java/com/ai/ui/report/manage/Savers.kt:88-105
**Symptom:** Launch a meta run for a prompt that carries a per-prompt Parameters preset / System prompt (or a provider+model pin): if the user hops through Help (or the activity is recreated) between picking the prompt and tapping Run, the run silently executes with the agent/app-wide parameter levels instead of the prompt's own preset, and a pinned provider/model is forgotten. No error — just a differently-configured API call.
**Root cause (claimed):** InternalPromptSaver (Savers.kt:88-105) saves only (id, name, reference, category, agent, text, title) and restores InternalPrompt with everything else at data-class defaults: parameters/systemPrompt reset to "*NONE", provider/model to null, workers to emptyList(), modelSelection to CONFIGURED. The five mid-flow states in State.kt:256-263 (secondaryPickerMetaPrompt, metaRunScreenPrompt, secondaryScopeMetaPrompt, fanOutConfirmMetaPrompt, fanInPickerPrompt) use this saver precisely so the flow survives the Help-hop unmount (the comment above the saver documents that path), and the restored object is passed verbatim to the launch: Main.kt:1145 → onRunSecondary(rid, pickerMetaPrompt, …) → SecondaryRunManager.kt:1672-1674 resolveSecondaryParams(..., metaPrompt) which reads prompt.parameters / prompt.systemPrompt as the per-prompt precedence level (ReportViewModelHelpers.kt:58-67).
**Reproduction:** Give a meta-category internal prompt a Parameters preset with temperature 0. On a report, pick that meta prompt → on the Scope screen open Help (top bar) → back → Continue → Run. The dispatched call resolves params without the preset (default temperature) because the restored prompt has parameters="*NONE".
**Proposed fix:** Either save all execution-relevant fields (provider, model, parameters, systemPrompt, modelSelection, flattened workers — ReportWorkerConfigSaver in the same file already flattens Workers to 5 strings), or save only the id + edited text and re-resolve the prompt from Settings.internalPrompts on restore, overlaying the text.
**Found by:** shard D6-models-prefs
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/viewmodel/AppViewModel.kt

### Candidate 50 — Severity: HIGH — Category: race condition / lost write
**Location:** ai/src/main/java/com/ai/viewmodel/AppViewModel.kt:1287-1291
**Symptom:** After bursts of settings mutations (sharpest: Housekeeping → Reset application reporting 'Reset complete — N API keys restored'), a later app restart loads an older Settings/GeneralSettings snapshot — e.g. blank API keys, reverted toggles — even though the in-memory state (and the success toast) showed the newer values.
**Root cause (claimed):** updateSettings (AppViewModel.kt:1287-1291), updateGeneralSettings (:1255-1256), updateProviderState (:1399-1404), replaceDefaultAgent (:1439-1440), markProviderTestedOk (:1452-1453) and flushAiSettingsToDisk (:1367-1369) all persist via `viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(snapshot) }` with a per-call snapshot. Dispatchers.IO is a parallel pool: two saves launched in order A→B can reach `prefs.edit{}.apply()` (SettingsPreferences.kt:385-435, full-blob write of every key incl. `<id>_api_key`) in order B→A, so the OLDER snapshot wins both the in-memory SharedPreferences map and the queued disk write. resetApplication (AppViewModel.kt:1164-1188) makes this concrete: clearAllConfiguration→updateSettings(Settings()) (blank keys, save#1), resetInternalPromptsFromAssets/resetSystemPromptsFromAssets/resetDefaultMetaItemsFromAssets (saves #2-#4, still blank keys), then updateSettings(result.settings) (save#5, keys restored) — five concurrent IO coroutines each doing a heavy full-Settings gson serialization; if #5's apply() lands before #4's, the persisted state has no API keys and the temp key file was already deleted (:1191). The dangling comment at :1165-1167 ('Persist the reset Settings synchronously…') has no corresponding code.
**Reproduction:** Set API keys for several providers → Housekeeping → Reset application → on completion immediately force-stop and relaunch; repeat — intermittently providers come back with blank keys (probabilistic, depends on IO-worker scheduling).
**Proposed fix:** Serialize all settings persistence: route saves through a single-threaded dispatcher (`Dispatchers.IO.limitedParallelism(1)`) or a Mutex-guarded writer, or replace per-call snapshots with a conflated 'dirty' channel whose worker always persists the CURRENT _uiState.value. In resetApplication, additionally call settingsPrefs.saveSettings synchronously after the final updateSettings before deleting the temp key file.
**Found by:** shard V1-appvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 51 — Severity: MEDIUM — Category: cache incoherence / data wipe
**Location:** ai/src/main/java/com/ai/viewmodel/AppViewModel.kt:1640-1666
**Symptom:** A provider whose /models call returns HTTP 200 with an empty usable list (empty data array, every model active=false on Groq, or a modelFilter regex that no longer matches the renamed catalog) loses its entire stored model list: the picker shows no models and no error, and the wipe is persisted to disk.
**Root cause (claimed):** fetchModelsOpenAi (ApiDispatchModels.kt:75-97) returns FetchedModels with empty ids on a 200 — only HTTP errors throw FetchModelsException. In refreshAllModelLists the success arm (AppViewModel.kt:1650-1653) unconditionally applies `withModels(service, fetched.ids, …)` (SettingsModels.kt:406-438 — replaces models verbatim, no empty guard) and calls `saveModelsForProvider(service, fetched.ids, …)`, persisting the empty list; yet the same result is excluded from `successful` by `it.second > 0` (:1674), so the cache timestamp is NOT updated — the code already treats 0 ids as not-a-success but only after the destructive write. The catch-arm comment (:1658-1663) claims 'The per-provider models on disk are preserved (we never called saveModelsForProvider for this provider)' — true only for the exception path. fetchModelsAwait (:1506-1525) has the same unconditional wipe (only the modelSource flip at :1511 is guarded by `fetched.ids.isNotEmpty()`).
**Reproduction:** Point a provider's modelFilter regex at a pattern matching none of its current model ids (or use a provider whose /models returns an empty data array), trigger Fetch models or restart the app (startup refreshAllModelLists) → the previously cached model list is gone from the picker and from `<id>_manual_models` on disk.
**Proposed fix:** Treat empty fetched.ids as a failure in both call sites: skip withModels/saveModelsForProvider and stamp a FetchModelsError ('provider returned no models — kept previous N') — or throw FetchModelsException from the dispatch layer when the post-filter id list is empty.
**Found by:** shard V1-appvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 52 — Severity: MEDIUM — Category: state machine / guard bypass
**Location:** ai/src/main/java/com/ai/viewmodel/AppViewModel.kt:1691-1700
**Symptom:** Tapping a failed provider row on the Refresh-all progress screen while the run is still in flight, then starting Refresh-all again, runs two refresh chains concurrently: the second run's clean-slate deletes agents mid-way through the first run's worker phase, after which both runs append agents — the 'default agents' flock ends up with duplicate agents for providers whose first-run worker finished after the second clean-slate, plus doubled catalog fetches.
**Root cause (claimed):** startRefreshAll/startRefreshWorkers guard re-entry only via `if (_refreshAllState.value != null && _refreshAllState.value?.isFinished == false) return` (AppViewModel.kt:1700, 1778) — the running Job is never stored, so the StateFlow is the only liveness signal. clearRefreshAllState (:1691) sets it to null without cancelling anything, and RefreshScreen.kt:140-147 calls it from onOpenProvider explicitly while the run may still be live ('Background work in the VM continues regardless'). With the state nulled, a second startRefreshAll passes the guard and its clean-slate (:1738-1752) + runWorkerPhase (:1935-1988, unconditional `agents + newAgent` with no ensureDefaultAgentInFlock-style dedupe) interleave with the still-running first run's worker writes.
**Reproduction:** Configure one provider with a bad key (fails fast) and several slow providers → Refresh all → while workers still run, tap the failed provider (clears the overlay) → back → Refresh all again → inspect Workers: the default agents flock holds duplicate agents for late-finishing providers.
**Proposed fix:** Keep a `refreshAllJob: Job?` on the ViewModel; guard startRefreshAll on `refreshAllJob?.isActive == true` instead of (or in addition to) the StateFlow, and either cancel the job in clearRefreshAllState or only null the state when the job is no longer active.
**Found by:** shard V1-appvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 53 — Severity: MEDIUM — Category: cancellation swallowed / mislabeled error
**Location:** ai/src/main/java/com/ai/viewmodel/AppViewModel.kt:1538-1568
**Symptom:** Backing out of the provider settings screen while the activation flow's model-list fetch is in flight leaves a persistent inline fetch-models error for that provider (message like 'StandaloneCoroutine was cancelled' / 'Job was cancelled') in the model picker, even though nothing actually failed; it sticks until the next fetch for that provider.
**Root cause (claimed):** fetchModelsAwait's `catch (e: Exception)` (AppViewModel.kt:1538) also catches kotlinx CancellationException (a RuntimeException subclass). The activation flow runs it on a rememberCoroutineScope (ServiceSettingsScreens.kt:165/973/986); leaving the screen cancels the scope, repository.fetchModelsWithKinds throws CancellationException, and the catch arm executes its full error path — a trace-dir scan via ApiTracer.getTraceFiles() (:1545-1562) and `_uiState.update { fetchModelsErrors + (service.id to FetchModelsError(msg, …)) }` (:1563-1566) — before withContext re-raises cancellation at its boundary. The non-suspending StateFlow write survives the cancellation. The sibling refreshAllModelLists explicitly rethrows CancellationException (:1655-1656), showing the intended pattern; fetchModelsAwait is missing it.
**Reproduction:** On a slow network, flip a provider from inactive to active (starts the model fetch) and immediately navigate back; reopen the provider's model picker — an inline 'Job was cancelled' fetch error is shown.
**Proposed fix:** Add `catch (e: CancellationException) { _uiState.update { it.copy(loadingModelsFor = it.loadingModelsFor - service) }; throw e }` before the generic catch in fetchModelsAwait so cancellation cleans the spinner without stamping fetchModelsErrors.
**Found by:** shard V1-appvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 54 — Severity: LOW — Category: dead guard / wrong trace attribution
**Location:** ai/src/main/java/com/ai/viewmodel/AppViewModel.kt:2025-2034
**Symptom:** The per-provider prompt-test 🐞 link can open an unrelated, older API trace: when the test call wrote no new trace (tracing off mid-toggle, or a failure before any HTTP exchange), the UI still deep-links the most recent trace file from some earlier call.
**Root cause (claimed):** Lines 2029-2031: `ApiTracer.getTraceFiles().firstOrNull()?.let { if (ApiTracer.getTraceCount() > traceCountBefore) it.filename else null } ?: ApiTracer.getTraceFiles().firstOrNull()?.filename` — when the count guard yields null, the elvis fallback returns exactly the same `firstOrNull()?.filename`, so the expression is provably identical to ignoring the guard. The exception arm (:2033) likewise returns `ApiTracer.getTraceFiles().firstOrNull()?.filename` unconditionally. The sibling testSpecificModel (:2042-2056) shows the correct pattern (match model + timestamp >= startTime).
**Reproduction:** With existing traces on disk, run a per-provider prompt test that fails before tracing (e.g. unresolvable host with tracing producing no file) — the result row's 🐞 opens a previous unrelated trace.
**Proposed fix:** Drop the `?:` fallback so no-new-trace returns null, or reuse testSpecificModel's model+timestamp matching for both arms.
**Found by:** shard V1-appvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 55 — Severity: LOW — Category: bootstrap ordering
**Location:** ai/src/main/java/com/ai/viewmodel/AppViewModel.kt:456-467
**Symptom:** The intended startup prewarm of the trace-file metadata cache never happens: the first Trace-screen open (or first 🐞 lookup) after a cold start still pays the full streaming-parse across the whole trace dir, exactly the cost the prewarm was added to avoid.
**Root cause (claimed):** AppViewModel.init calls `ApiTracer.prewarmCache(viewModelScope)` at :465, but `ApiTracer.init(application)` only runs inside the bootstrap coroutine launched later at :499 (executed at :625). Both are dispatched to Dispatchers.IO in launch order, so the prewarm's getTraceFiles() runs first, hits `val dir = traceDir ?: return emptyList()` (ApiTracer.kt:250) — returning WITHOUT populating cachedTraceFiles — and the prewarm completes having cached nothing. (Correctness is unaffected since the empty result is not cached.)
**Reproduction:** Cold-start with a dense trace dir, watch logs: the prewarm coroutine runs before 'init ApiTracer'; first Trace-screen open performs the full per-file JsonReader parse on demand.
**Proposed fix:** Move the prewarmCache call into bootstrap() immediately after ApiTracer.init, or have prewarmCache lazily init from an Application reference before scanning.
**Found by:** shard V1-appvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 56 — Severity: LOW — Category: race condition / stale snapshot
**Location:** ai/src/main/java/com/ai/viewmodel/AppViewModel.kt:579-581
**Symptom:** A settings change made during the bootstrap window (UI is interactive while the IO bootstrap does singleton inits plus nine asset delta-merges — hundreds of ms, longer on first run) is silently reverted in memory when bootstrap publishes, and the reverted value is then re-persisted by the next save.
**Root cause (claimed):** `_uiState.update { it.copy(generalSettings = bs.first, aiSettings = bs.second) }` (:580) ignores the lambda's current-state parameter for both fields: bs.first was loaded at bootstrap start (:640) and bs.second reflects only the asset merges — any updateGeneralSettings/updateSettings that ran between ViewModel construction and this publish (both are synchronous StateFlow writes available to the already-composed UI) is clobbered. Their disk saves also lose to the subsequent saves of the reverted in-memory state.
**Proposed fix:** Publish via a merging CAS: copy only bootstrap-owned deltas onto the CURRENT state (e.g. detect whether generalSettings/aiSettings still equal the construction-time seeds before replacing), or block settings mutations behind a 'bootstrapped' flag.
**Found by:** shard V1-appvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 57 — Severity: LOW — Category: persistence drift
**Location:** ai/src/main/java/com/ai/viewmodel/AppViewModel.kt:1523-1525
**Symptom:** For providers with mergeHardcodedModels + a hardcodedModels list (DeepSeek today; any provider the user edits to add hardcoded models via the provider settings screen), the hardcoded entries disappear from pickers after an app restart that followed a per-provider Test-button fetch — in-memory they exist (re-merged on fetch), on disk they don't.
**Root cause (claimed):** fetchModelsAwait saves `settingsPrefs.saveModelsForProvider(service, fetched.ids, cfgSelf.modelTypes, …)` (:1525) — types/vision/caps come from the post-update cfgSelf, but the model list passed is the raw `fetched.ids`, NOT `cfgSelf.models` which the 5-arg withModels just merged with service.hardcodedModels (SettingsModels.kt:428-432). The load path (SettingsPreferences.kt:329-332) reads `<id>_manual_models` verbatim with no re-merge (defaults apply only when the key is absent). refreshAllModelLists has the same initial save (:1653) but heals itself by re-saving `cfg.models` in the final pass (:1680-1683); the single-provider path (per-provider Test with fetchAfter=true → AppNavHost.kt:608, markProviderTestedOk :1457-1459) has no such pass, so the truncated list is the last write.
**Reproduction:** Give a provider hardcodedModels + mergeHardcodedModels in its provider settings, set state ok, tap the per-provider Test button (triggers fetchAfter), kill and relaunch the app → the hardcoded models are missing from the picker until the next full settings save or fetch.
**Proposed fix:** Pass `cfgSelf.models` (the merged post-withModels list) to saveModelsForProvider in fetchModelsAwait, matching what the full saveSettings would persist.
**Found by:** shard V1-appvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 58 — Severity: LOW — Category: CAS discipline violation
**Location:** ai/src/main/java/com/ai/viewmodel/AppViewModel.kt:1737-1752
**Symptom:** If another aiSettings writer lands between the clean-slate's read and its write (e.g. a Test-all-models sweep calling applyTestItemIncrement, or a finishing provider test), that change is silently lost when refresh-all starts.
**Root cause (claimed):** Both clean-slate blocks read `val current = _uiState.value.aiSettings` and then call `_uiState.update { it.copy(aiSettings = cleaned) }` (:1739-1750 and :1798-1810) where `cleaned` derives from the earlier `current`, ignoring the update lambda's `it` — the exact closed-over-snapshot pattern the file's own comment at updateProviderState (:1378-1384) documents as the cause of a previous clobber bug. The same pattern exists in loadBundledInternalPrompts/resetInternalPromptsFromAssets and siblings (:933-1038), which read `_uiState.value.aiSettings` then call updateSettings(current.copy(…)).
**Proposed fix:** Compute the clean-slate inside the update lambda from `it.aiSettings` (and likewise build the merged prompt lists inside a CAS update), saving the post-update snapshot afterward.
**Found by:** shard V1-appvm
**Status:** Candidate — NOT verified (run interrupted)

## File: ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt

### Candidate 59 — Severity: HIGH — Category: cross-report state contamination
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:952-964, 2799, 2925
**Symptom:** Continue report A in background, then open report B from History: B's "X / Y complete" counter counts A's completions, so B reads complete while its rows still show the spinning hourglass (isComplete = progress >= total at ui/report/manage/Main.kt:359; KEEP_SCREEN_ON drops, progress bar disappears), or overshoots (e.g. 5/3). If A and B share a direct-model row (deterministic id "swarm:provider:model" — very common when the user reuses the same models), B's row flips to showing A's fresh response status/tokens (GenerationPhase.kt:861, 552-554), and hydrateAgentResultsFromStorage then PREFERS that foreign in-memory entry over B's disk row (line 2714 'rebuilt + _agentResults.value').
**Root cause (claimed):** executeReportTask's non-headless UI writes are unconditional: `_agentResults.update { it + (task.resultId to response) }` (line 959) and the genericReportsProgress increment (960-964) never check that the task's reportId equals uiState.currentReportId. _agentResults is one global map keyed by agentId only, and agent ids for direct models are deterministic across reports. The same applies to regenerateReport's dispatch (isRegeneration = false at line 2265 → bumps), regenerateAgent's publish, and removeAgentFromReport's `_agentResults.update { it - agentId }` (2925). The headless flag added for stress reports ("so concurrent background reports don't clobber the foreground report's progress/results", lines 807-813) protects only that path — continueReportInBackground (2736-2739) keeps the run non-headless. There is also a smaller race in restoreCompletedReport (2633 vs 2659): a task completing between the disk read and the updateUiState has its bump overwritten, leaving progress stuck at total-1 forever.
**Reproduction:** Generate a report with model gpt-4o (direct pick); press back mid-run (continue in background); open an older finished report that also contains gpt-4o; wait for the background tasks to land.
**Proposed fix:** Gate the UI publish and the progress bump on `appViewModel.uiState.value.currentReportId == reportId` (recompute at write time), or key _agentResults and the progress counters by reportId. For the restore race, recompute progress from disk-terminal count inside the same updateUiState pass that publishes task completions.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 60 — Severity: HIGH — Category: regenerate config drift
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:2779-2845
**Symptom:** "Call model API again" on a single model (and the Broken-work restart path, ui/navigation/DeveloperRoutes.kt:171) re-runs the agent with a different system prompt and parameter set than the report was generated with: report-level system prompt, per-model selection params, captured parameter presets and advanced params are all dropped; swarm/direct rows are dispatched with completely empty AgentParameters() (no app-wide/external/provider system-prompt fallback either). After a process kill, Broken-work restarts of interrupted agents silently produce answers under a different config than their siblings.
**Root cause (claimed):** Lines 2779-2795 build the ReportTask from `aiSettings.resolveAgentParameters(savedAgent)` — which is just `mergeParameters(agent.paramsIds) ?: AgentParameters()` (model/SettingsModels.kt:697, no spText fold; the dispatch layer never consults agent.systemPromptId — AnalysisRepository.kt:180 uses params.systemPrompt only) — and `AgentParameters()` for swarm rows. Line 2831 takes `baseOverride = state.reportAdvancedParameters` (live UiState; nulled by dismissGenericReportsDialog at 2727, so it is null after reopening a report, or holds ANOTHER report's pre-gen tweak) instead of `resolveReportOverrideParams(ai, report.parameterPresetIds, report.advancedParameters, …)` used by regenerateReport (2244-2247) and forceRegenerateAllAgents (2375-2378) — whose own comment (2339-2344) calls reading state.* exactly this bug, fixed there but not here. buildTemperatureSweepTask (676-720) proves the correct single-agent rebuild (selectionParamsById + reportSystemPromptId + captured preGen flags) exists in the same file and is used by all four replay flows — just not by regenerateAgent.
**Reproduction:** Create a report with a report-level system prompt and a temperature preset; reopen it later (UiState.reportAdvancedParameters is null); tap a model row → Call model API again; inspect the request trace: no system prompt for swarm rows, no preset temperature.
**Proposed fix:** Rebuild the single-agent task via buildTemperatureSweepTask (or buildReportTasks with the report's captured selectionParamsById / reportSystemPromptId / preGen flags) and resolve overrideParams via resolveReportOverrideParams from report.parameterPresetIds / report.advancedParameters / report.webSearchTool / report.reasoningEffort, keeping the existing capability gating (canWeb/canReason/canVision) on top.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 61 — Severity: MEDIUM — Category: untracked job / broken-work false positive
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:1803-1845, 220-223, 2488-2537
**Symptom:** While a stress-test/background report is actively generating, the 30-second Broken-work sweep flags all its PENDING/RUNNING agents as interrupted (⚠️ badge + "Report models — N unfinished" card); tapping restart fires regenerateAgent concurrently with the still-in-flight original call (double dispatch, double cost, racing terminal writes). Deleting a still-generating background report cancels nothing: every agent coroutine keeps making (billed) API calls against a deleted report.
**Root cause (claimed):** submitBackgroundReport launches a bare `appViewModel.viewModelScope.launch` (1807) and registers the job nowhere: it never sets activeGenerationReportId, never calls trackRegenerateJob. isReportGenerating (220-223) checks only activeGenerationReportId / regenerateJobs / regenerateBatchEngine, so SecondaryRunManager.kt:781-782 passes reportIsLive=false and BrokenWorkPolicy.agentProblems (BrokenWorkPolicy.kt:68-80) flags every PENDING/RUNNING agent immediately — agents have no stale-grace window (that exists only for secondary rows, line 43-49). cancelReportOwnedWorkBeforeDelete (2508) cancels reportGenerationJob only when activeGenerationReportId == reportId, so background-report coroutines survive the delete — the exact orphan-coroutine problem the function's own comment (2490-2498) describes.
**Reproduction:** Housekeeping → Test → Stress test; open the Reports hub within ~30s: in-flight reports appear under Broken work as interrupted.
**Proposed fix:** Track each submitBackgroundReport job in a per-report registry (trackRegenerateJob fits: it already feeds both isReportGenerating and cancelReportOwnedWorkBeforeDelete via regenerateJobs).
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 62 — Severity: MEDIUM — Category: progress double-accounting
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:2926-2942, 944-957
**Symptom:** During generation, open a pending row (rows are always clickable — GenerationPhase.kt:864-868) and tap "Remove model from report": the run later reads complete one task early (a still-running agent's row keeps its hourglass on a report shown as complete; completion behaviors fire early) and the internal counter ends at total+1.
**Root cause (claimed):** Two independent compensations conflict. removeAgentFromReport (2930-2935) decrements genericReportsTotal for an unfinished agent and leaves progress as-is. The removed agent's still-in-flight executeReportTask then hits the !stillPresent branch (944-957) and STILL bumps progress — its comment assumes "its slot was counted into the fixed genericReportsTotal at launch", which is no longer true after the decrement. Net: progress final = T while total = T-1; isComplete (Main.kt:359, progress >= total) fires after T-1 of T completions. Bonus: removeAgentFromReport never checks the agent existed (removeAgent's Boolean return ignored), so a double-tap decrements total twice for one agent.
**Reproduction:** Start a 3-model report; while all pending, open one row, Remove model from report; watch the screen declare complete after the second of the three calls lands.
**Proposed fix:** Pick one compensation: in the !stillPresent branch, skip the bump (the removal path now owns the accounting), and in removeAgentFromReport guard the counter update on ReportStorage.removeAgent() returning true.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 63 — Severity: MEDIUM — Category: UI lies / missing publish
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:820-834
**Symptom:** When a model is benched (Google >1h 429) at dispatch time, its agent row keeps the spinning hourglass for the entire session — including after the run reads complete — instead of the red ❌ the code intends ("keep the agent as a visible red error row", lines 817-818). Only leaving the report entirely and reopening it fixes the icon.
**Root cause (claimed):** The benched branch calls markAgentErrorAsync and bumps progress (828-832) but returns before the `_agentResults.update { it + (task.resultId to response) }` publish at line 959. GenerationPhase.kt:878-885 renders result == null as the infinite hourglass; the only re-hydration trigger is Nav.kt:196-200, which fires solely when agentResults.isEmpty() — never during a live run with other entries populated.
**Reproduction:** Get a Google model benched (long-cooldown 429), include it in a report, generate: its row spins forever while the report completes around it.
**Proposed fix:** In the benched branch (non-headless), also publish `AnalysisResponse(service = …, analysis = null, error = "…benched…")` into _agentResults before returning, mirroring the normal error path.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 64 — Severity: MEDIUM — Category: cost accounting on delete
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:2463, 2481
**Symptom:** After a prompt/parameters-change Regenerate, the report's total-cost view silently loses every dollar previously spent on its meta results and translations: the old rows are deleted and re-run, the new spend is counted, the old spend vanishes.
**Root cause (claimed):** Lines 2463 (`for (m in rows) SecondaryResultStorage.delete(...)`) and 2481 (`for (t in translates) SecondaryResultStorage.delete(...)`) delete rows with no bumpCostsFromDeletedItems and no removeIconCallsForSecondaryIds. Every other delete path preserves spend: removeAgentFromReport in the same file (2916-2922) sums inputCost+outputCost and bumps, plus removeIconCallsForSecondaryIds (2921); SecondaryRunManager (1791, 1839), TranslationRunManager (902, 1015, 1476), and the eleven engine sites fixed in audit 2026-06-10 Bug 4 all bump. ReportStorage.bumpCostsFromDeletedItems' KDoc states the invariant: "so cost stays accounted for after the row disappears" (ReportStorage.kt:2502-2505).
**Reproduction:** Run a report, let auto-metas/translations land, note total cost; edit the prompt and Regenerate; total cost drops by the deleted secondaries' spend.
**Proposed fix:** Before each delete loop, sum the rows' (inputCost ?: 0) + (outputCost ?: 0) (or fullCost), bumpCostsFromDeletedItems with the total, and call removeIconCallsForSecondaryIds for the deleted ids.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 65 — Severity: MEDIUM — Category: params resolution drift
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:448-449 vs 681-682, 2156-2157, 2348-2349
**Symptom:** A report generated with only 🌡️ parameter presets (no advanced values, no web/reasoning toggle) resolves its direct models' base params WITH the report-model/app-wide parameter fallbacks on the fresh run, but WITHOUT them on Regenerate, force-regenerate-all, and all four sweep/replay flows — any field present only in those defaults (e.g. an app-wide maxTokens) silently changes between the original run and its replays, defeating the comments' "Replay the report's CAPTURED generation config" / "both paths replay identically" guarantee.
**Root cause (claimed):** generateGenericReports computes `preGenParamsActive = state.reportAdvancedParameters != null || state.reportWebSearchTool || state.reportReasoningEffort != null` (448-449) — matching doc/parameters.md:126 ("any advanced value, web-search, or reasoning toggle"). buildTemperatureSweepTask (681-682), regenerateReport (2156-2157) and forceRegenerateAllAgents (2348-2349) add `report.parameterPresetIds.isNotEmpty()` to the predicate, so buildReportTasks suppresses the rmPar/appPar fallbacks (652-654) only on the replay side.
**Reproduction:** Set an app-wide default with maxTokens; generate a report picking only a temperature preset; regenerate after a prompt edit; compare request bodies — maxTokens present on the first run, absent on the regenerate.
**Proposed fix:** Extract one shared predicate (decide whether preset-only counts as a pre-gen override — the doc says it doesn't) and use it at all four sites; if presets should count, also update generateGenericReports and the doc.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 66 — Severity: MEDIUM — Category: stale results after cascade
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:2293-2301, 2430-2443, 2467-2485
**Symptom:** After editing the prompt and regenerating (cascade path), every agent answer is replaced, but the report's Rerank and Moderation rows still describe the OLD answers; metas re-run with TopRanked scope select their top-N agents from that stale ranking, so the refreshed meta results are computed over the wrong subset.
**Root cause (claimed):** The call-site comment (2293-2297) says "Re-fire each meta kind with its original picks (RERANK first because chat-type META runs may consume it as Top-Ranked scope)", but cascadeMetasAndTranslations only processes kind == META rows (2430) and TRANSLATE rows (2469); RERANK/MODERATION are never deleted or re-dispatched through their proper entry points (secondary.runRerank / runModeration — the inner comment 2421-2429 only explains why they can't go through runMetaPrompt). The safeScope check (2456-2460) verifies the rerank row still EXISTS, not that it reflects the new answers, so TopRanked metas re-run against it.
**Reproduction:** Report with a Rerank and a TopRanked-scoped meta; edit the prompt, Regenerate; open the rerank — it still ranks the old responses.
**Proposed fix:** In the cascade, delete + re-fire RERANK via secondary.runRerank (joined, before the meta groups so TopRanked metas see the fresh ranking) and MODERATION via secondary.runModeration, mirroring maybeAutoCreateSecondaries' dispatch.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 67 — Severity: MEDIUM — Category: orphaned rows
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:2207 vs 2912-2923
**Symptom:** Removing a model via Edit Models → Regenerate (additive path) leaves that agent's TRANSLATE rows on disk pointing at a non-existent agent; they linger in the translation run data indefinitely, while removing the same model via the row's "Remove model from report" button cleans them up and accounts their cost.
**Root cause (claimed):** regenerateReport's removal loop (`for (id in removedIds) ReportStorage.removeAgent(context, reportId, id)`, line 2207) calls only ReportStorage.removeAgent, which prunes iconCalls/userNotes but not SecondaryResultStorage rows (ReportStorage.kt:2443-2499). The orphan cascade — delete TRANSLATE rows with translateSourceKind == "AGENT" && translateSourceTargetId == agentId, roll cost, removeIconCallsForSecondaryIds — exists only in removeAgentFromReport (2912-2923). On the cascadeAll (prompt-change) path the orphans are coincidentally cleaned because ALL translates are deleted; the model-list-only path is the gap.
**Reproduction:** Report with a translation; Edit Models, drop one model, Regenerate (no prompt change); list the report's TRANSLATE secondaries — the dropped agent's row remains.
**Proposed fix:** Extract removeAgentFromReport's orphan-translate cascade into a helper and call it for each id in regenerateReport's removedIds loop.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 68 — Severity: LOW — Category: job leak on delete
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:2529-2530
**Symptom:** Deleting a report while a web-search or prompt-edit replay is in flight leaves the replay coroutine running its (billed) HTTP call against the deleted report, and its state entry stays in the ReplayTrack map for the rest of the process lifetime (drop only happens on apply/clear).
**Root cause (claimed):** cancelReportOwnedWorkBeforeDelete calls `temperatureSweep.cancelByPrefix(fanOutPrefix)` and `reasoningEffortSweep.cancelByPrefix(fanOutPrefix)` (2529-2530) but omits the two sibling tracks, although all four use the identical "$reportId|$agentId" key (WebSearchReplayState.key / PromptEditReplayState.key, lines 121-123, 151-153) and ReplayTrack.cancelByPrefix exists for exactly this teardown (ReplayTrack.kt:59-62).
**Reproduction:** Start a web-search replay on a model row, back out to the hub, delete the report; the replay call completes in logcat and the state entry remains in webSearchReplayStates.
**Proposed fix:** Add `webSearchReplay.cancelByPrefix(fanOutPrefix)` and `promptEditReplay.cancelByPrefix(fanOutPrefix)` next to the existing two.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)

### Candidate 69 — Severity: LOW — Category: locale-format consistency
**Location:** ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:971
**Symptom:** On the owner's nl-NL device the AppLog line for each finished report task prints the cost with a comma decimal ("cost=0,00012"), inconsistent with every other format call in the project, which pins Locale.US (cf. formatSweepTemperature at line 161 in the same file, and audit 2026-06-10 Bug 10's fix 95976ca5d).
**Root cause (claimed):** `(cost?.let { " cost=${"%.5f".format(it)}" } ?: "")` uses Kotlin's String.format with the default locale. Log-only (no parse round-trip), so a convention/consistency defect rather than a crash.
**Proposed fix:** Use `String.format(Locale.US, "%.5f", it)`.
**Found by:** shard V2-reportvm
**Status:** Candidate — NOT verified (run interrupted)
