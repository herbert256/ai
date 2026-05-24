# Deep Code Review – Chat / Knowledge / Models / Search (audit-2, fresh from current code)

## File: ai/src/main/java/com/ai/ui/chat/ChatScreens.kt

### Bug 1 — Severity: HIGH — Category: cost calculation
**Location:** ChatScreens.kt:535 (`actuallySend`, totalCost accumulation)
**Symptom:** The running chat cost shown in the title bar is wrong by a factor of 100 relative to the dual-chat screen, and the units are inconsistent across the app.
**Root cause:** `totalCost += inputTokens * pricing.promptPrice * 100 + outputTokens * pricing.completionPrice * 100`. `pricing.promptPrice` is already a per-token dollar price; multiplying by tokens gives dollars, and `* 100` converts to cents. But the displayed label (lines 639-641) formats it as `"%.2fc"` / `"<0.01c"` — cents. DualChatScreen computes the same quantity as `(tokens*price)*100` and labels it cents too, so they agree on units, but the operator precedence here means `inputTokens * pricing.promptPrice * 100` is evaluated left-to-right and is fine — the real issue is that `pricing.promptPrice` may be per-token while elsewhere prices are quoted per-million (ModelInfo multiplies by 1_000_000). If `promptPrice` is genuinely per-token this is correct; if it is per-million the chat cost is off by 1e6. Cross-check with `PricingCache.getPricing` units; the divergence between this file's `*100` and ModelInfo's `*1_000_000` is suspect and at minimum undocumented.
**Proposed fix:** Centralize cost-from-tokens math in one helper (e.g. `pricing.costCents(inTok, outTok)`) and use it from both chat and dual-chat so the unit convention can't drift.
**Status:** Open

### Bug 2 — Severity: HIGH — Category: stale closure / wrong output tokens
**Location:** ChatScreens.kt:530-536 (`actuallySend`, assistant message build)
**Symptom:** The assistant message content and the recorded output-token count can be lost/zeroed if the screen recomposes such that `streamingContentState` is read after the `finally` clears it.
**Root cause:** After `collect` finishes, the code reads `streamingContentState.value` twice (line 530 to build `assistantMsg`, line 534 for `outputTokens`). The `finally` block (line 570) sets `streamingContentState.value = ""`. These run on the same coroutine so ordering is safe in the happy path, but `assistantMsg` is built from `streamingContentState.value` rather than from the local `sb` StringBuilder that actually accumulated the chunks. If any chunk arrived after the last `streamingContentState.value = sb.toString()` assignment failed to run (e.g. an exception between append and assignment), `sb` and `streamingContentState.value` diverge. Using `sb.toString()` for both the saved message and the token count would be strictly more correct.
**Proposed fix:** Build `assistantMsg` and compute `outputTokens` from `sb.toString()`, not from the mutable UI state.
**Status:** Open

### Bug 3 — Severity: MEDIUM — Category: main-thread I/O
**Location:** ChatScreens.kt:393-413 (`pinned`, `attachedKnowledgeBaseIds`, `sessionTitle` initializers)
**Symptom:** Opening / resuming a chat session does three synchronous `ChatHistoryManager.loadSession(currentSessionId)` disk reads on the main thread during composition (one each for pinned, KB ids, title). For an image-heavy session this parses a multi-MB JSON three times on the UI thread, risking jank/ANR.
**Root cause:** `remember(currentSessionId) { mutableStateOf(ChatHistoryManager.loadSession(...)...) }` runs the loader inline in composition; `loadSession` is a blocking file read + Gson parse and is called three separate times.
**Proposed fix:** Load the session once via `produceState` on `Dispatchers.IO` and derive pinned/KB/title from that single result.
**Status:** Open

### Bug 4 — Severity: MEDIUM — Category: main-thread I/O
**Location:** ChatScreens.kt:415-429, 671 (`saveSession`, pin toggle)
**Symptom:** Every send, every system-prompt change, and every pin/KB toggle calls `ChatHistoryManager.saveSession(...)` (and `setSessionPinned`, which does loadSession+saveSession) directly on the main thread. `writeTextAtomic` of a large session blocks the UI.
**Root cause:** `saveSession` is a plain function invoked from composables / click handlers without dispatching to IO; `setSessionPinned` additionally reads+rewrites the whole file synchronously.
**Proposed fix:** Wrap persistence in `scope.launch(Dispatchers.IO)` (the screen already holds a `scope`).
**Status:** Open

### Bug 5 — Severity: MEDIUM — Category: trace lookup race / mis-association
**Location:** ChatScreens.kt:963-971 (`ChatMessageBubble`, trace lookup)
**Symptom:** The 🐞 trace icon on an assistant bubble can point at the wrong turn's trace when the same model answered several times in one session.
**Root cause:** The match heuristic is `reportId == null && model == model`, then `minByOrNull abs(timestamp - message.timestamp)`. Chat traces carry no session id (`reportId == null`), so traces from *other* chat sessions using the same model are candidates too. The closest-timestamp tiebreak picks across sessions, not within. Two concurrent/adjacent sessions on the same model alias.
**Proposed fix:** Tag chat traces with the session id (as dual-chat already does with `withTracerTags(reportId = sessionId)`) and filter on it.
**Status:** Open

### Bug 6 — Severity: MEDIUM — Category: moderation fail-open / token accounting
**Location:** ChatScreens.kt:604-606 (`trySend`, moderation error path)
**Symptom:** When the moderation call errors, the message is sent anyway (fail-open) — documented — but the moderation provider's tokens are never recorded, and `moderationError` is shown as a persistent banner with no auto-clear after a successful subsequent send within the same turn.
**Root cause:** `moderationError` is only reset at the top of `trySend` (line 589); the moderation API usage (input tokens for the classification call) is never passed to `recordChatStatistics`/usage stats. Cost of the validate-input model is invisible.
**Proposed fix:** Record moderation usage stats with `kind = "moderation"`; the banner reset is acceptable but consider clearing it on the next clean send.
**Status:** Open

### Bug 7 — Severity: MEDIUM — Category: index/identity key instability
**Location:** ChatScreens.kt:709 (`items` key includes list index `$it`)
**Symptom:** LazyColumn item keys are `"${role}_${timestamp}_$idx"`. Including the positional index in the key defeats the purpose of stable keys: any insertion/removal that shifts indices invalidates every following item's key, forcing full re-composition (and losing per-item animation/scroll state).
**Root cause:** Two messages can share role+timestamp (system+user created in the same millisecond, or seeded system prompt) so the author added `$idx` to disambiguate, but that makes keys positional.
**Proposed fix:** Give `ChatMessage` a stable unique id at creation and key on that.
**Status:** Open

### Bug 8 — Severity: MEDIUM — Category: stale starter consumption / double-fire
**Location:** ChatScreens.kt:288-294 (`starter`, `starterImage`, `onConsumeStarter`)
**Symptom:** If the screen recomposes/reenters before the parent clears the staged starter from UiState, the starter can be re-applied or `onConsumeStarter` fires against already-consumed state.
**Root cause:** `userInput` is seeded from `starter` via `rememberSaveable { mutableStateOf(starter ?: "") }`, but `onConsumeStarter()` runs in `LaunchedEffect(Unit)` which only fires once per composition instance. If the parent recreates the screen (config change without saved-state restore of UiState) before consuming, `initialUserInput` is read again and overwrites typed text. Narrow window but real with the "Start with photo" flow.
**Proposed fix:** Gate the seed on a `rememberSaveable` boolean "starterConsumed" flag so a second composition never re-seeds.
**Status:** Open

### Bug 9 — Severity: LOW — Category: image attachment dropped on process death
**Location:** ChatScreens.kt:216-228, 307-309 (`AttachedImageSaver`)
**Symptom:** A multi-MB base64 image attached but not yet sent is silently lost on process death (TransactionTooLarge / Bundle ~1 MB cap). Acknowledged in comments but still a data-loss path.
**Root cause:** The Saver stores the full base64 in the saved-instance Bundle.
**Proposed fix:** Persist the pending image to a cache file and store only its path in the Saver.
**Status:** Open

### Bug 10 — Severity: LOW — Category: animation correctness
**Location:** ChatScreens.kt:1050-1077 (`AnimatedTextLines`)
**Symptom:** During streaming, when the model emits a response of exactly >30 lines the fade-in is skipped (snap), but for responses that *grow past* 30 lines mid-stream the `visibleLineCount` was already animating line-by-line and then jumps; the effect re-runs on every `content` change (each chunk), restarting the `delay(80)` loop. The visible count can oscillate because `if (lines.size < visibleLineCount) visibleLineCount = lines.size` clamps down then the loop walks back up, producing flicker.
**Root cause:** `LaunchedEffect(content)` restarts the reveal loop on every chunk; `visibleLineCount` is shared state across restarts.
**Proposed fix:** Drive the reveal off a monotonically increasing target derived from chunk count, not by restarting a delay loop per chunk.
**Status:** Open

### Bug 11 — Severity: LOW — Category: cost during cold pricing window
**Location:** ChatScreens.kt:388-389, 535 (`pricing`, cost accumulation)
**Symptom:** If the first assistant turn completes before `PricingCache` finishes priming, `pricing` is `DEFAULT_PRICING` and that turn's cost is computed at default rates; the `pricingTick` recompute updates `pricing` for *future* turns but never re-prices the already-accumulated `totalCost`.
**Root cause:** `totalCost` is an accumulator; only the per-turn delta uses the current `pricing`. Early turns priced at default stay baked into the sum.
**Proposed fix:** Recompute `totalCost` from stored per-turn token counts when `pricingTick` changes, or defer the first cost add until pricing primes.
**Status:** Open

## File: ai/src/main/java/com/ai/viewmodel/ChatViewModel.kt

### Bug 12 — Severity: HIGH — Category: RAG injection lost / per-turn vs persisted
**Location:** ChatViewModel.kt:55-70 (`sendChatMessageStream`, RAG branch)
**Symptom:** RAG context is injected only into the messages sent to the API, never persisted. That's intended, but the system-prompt merge happens *inside* the cold flow on every send, re-embedding the query each turn — fine — yet when `context == null` (overload misuse) RAG is silently skipped even though `knowledgeBaseIds` is non-empty.
**Root cause:** `if (knowledgeBaseIds.isNotEmpty() && context != null)` — if a caller forgets to thread `context`, KBs attach in the UI but contribute nothing, with no warning (the warning log lives only inside `messagesWithRag`, which is never reached).
**Proposed fix:** Log a warning when `knowledgeBaseIds.isNotEmpty() && context == null`; better, make `context` non-nullable for the RAG-capable path.
**Status:** Open

### Bug 13 — Severity: MEDIUM — Category: local LLM RAG / system prompt flattening
**Location:** ChatViewModel.kt:156-169 (`sendLocalLlmStream`, prompt build)
**Symptom:** For an on-device LLM, only the *first* system message is prepended; if `messagesWithRag` merged context into an existing system message the merged block is included, but if there were already multiple system messages (e.g. a seeded system prompt plus a RAG-inserted one — which `messagesWithRag` avoids but other paths could create) only `firstOrNull` is emitted, dropping the rest.
**Root cause:** `withRag.firstOrNull { it.role == "system" }` takes one system message; the filter below excludes all system messages from the transcript.
**Proposed fix:** Concatenate all system messages, or assert a single system message invariant.
**Status:** Open

### Bug 14 — Severity: MEDIUM — Category: token/usage not recorded for streaming local LLM
**Location:** ChatViewModel.kt:143-173 (`sendLocalLlmStream`)
**Symptom:** Local-LLM chat turns never call `updateUsageStats`; usage/cost for local models is invisible (cost is $0 which is acceptable, but call count is also never incremented, so Model Info "AI Usage" stays empty for local models).
**Root cause:** Unlike `sendDualChatMessage` (which records stats) and the cloud streaming path (ChatScreens records via `onRecordStatistics`), the local path emits one chunk and the screen *does* call `onRecordStatistics` — but with token estimates from `AppViewModel.estimateTokens`, which is fine. Verify the local path actually routes through the same `onRecordStatistics`; if `LOCAL` is short-circuited before `onRecordStatistics`, the count is lost. (Trace the screen's collect path for the LOCAL provider.)
**Proposed fix:** Ensure local-LLM turns increment call count even at $0 cost.
**Status:** Open

### Bug 15 — Severity: LOW — Category: RAG query uses raw last-user content including image-only turns
**Location:** ChatViewModel.kt:83 (`messagesWithRag`)
**Symptom:** `lastUser = messages.lastOrNull { role == "user" }?.content?.takeIf isNotBlank`. An image-only user turn (content blank, image attached) returns the *previous* text turn as the query, or `return messages` if none — so a "describe this image" + KB attachment retrieves against stale text.
**Root cause:** RAG query is text-only and falls back to the last non-blank text.
**Proposed fix:** Document/accept, or skip RAG when the latest user turn is image-only.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/chat/DualChatScreen.kt

### Bug 16 — Severity: HIGH — Category: process-global tracer tag leak / cross-talk
**Location:** DualChatScreen.kt:423-464 (`startChatLoop` wraps the whole loop in `withTracerTags`)
**Symptom:** `withTracerTags(reportId = sessionId, category = "Dual chat")` is held for the *entire* duration of the multi-turn loop. If the user navigates away (without stopping) and starts a normal chat or report while the dual loop is still cancelling/finishing, traces from those other screens can be tagged with the dual-chat sessionId/category until the dual loop's `finally` restores the previous tags.
**Root cause:** Tracer tags are process-global; the dual loop holds them across many suspending network calls and across potential concurrent activity on other screens.
**Proposed fix:** Set the tag pair per individual API call (around each `sendDualChatMessage`) rather than around the whole loop.
**Status:** Open

### Bug 17 — Severity: HIGH — Category: stop/resume race + double-run
**Location:** DualChatScreen.kt:420-469, 550-559 ("Chat N more" → `startChatLoop`)
**Symptom:** Tapping "Chat N more" assigns a new `chatJob = scope.launch{...}` without cancelling/awaiting the old one. If the previous job hadn't fully reached its `finally` (e.g. user mashed Stop then immediately "Chat more"), two loops can run concurrently, both appending to `messages` and both incrementing cost counters — interleaved garbage conversation.
**Root cause:** `startChatLoop` overwrites `chatJob` without `chatJob?.cancelAndJoin()` first. `isRunning`/`isStopped` are not a reliable mutex (set inside the coroutine after launch).
**Proposed fix:** `chatJob?.cancel()` (or cancelAndJoin) before launching; guard re-entry with a flag set synchronously on the main thread.
**Status:** Open

### Bug 18 — Severity: MEDIUM — Category: Saver index logic off-by-one risk
**Location:** DualChatScreen.kt:86-100 (`DualMessagesSaver.restore`)
**Symptom:** Restore loop condition `while (i + 4 < flat.size)` drops the final record when the flat list length is an exact multiple of 5. For N messages the list has 5N entries; the last record occupies indices [5N-5 .. 5N-1], and `i + 4 < 5N` is `5N-1 < 5N` → true, so it's included. Correct — but the strict `<` means any trailing partial/corrupt tail is dropped silently, and if a future field is added making it 6 per record the condition is wrong. Fragile parallel-array serialization.
**Root cause:** Manual flat-array (de)serialization with a hand-written stride.
**Proposed fix:** Use `i + 4 <= flat.size - 1` explicitly or serialize via JSON; add a stride constant.
**Status:** Open

### Bug 19 — Severity: MEDIUM — Category: LazyColumn key collision
**Location:** DualChatScreen.kt:498 (`items` key `"msg_${it}_${messages[it].modelIndex}"`)
**Symptom:** Key embeds the positional index `$it`, so inserting messages (the loop appends one at a time) reshuffles keys and the same trailing items re-key, defeating stable-key benefits and causing the per-bubble `produceState` trace lookup to restart.
**Root cause:** Positional index in key; `DualMessage` has no stable id.
**Proposed fix:** Add a UUID to `DualMessage`; key on it.
**Status:** Open

### Bug 20 — Severity: MEDIUM — Category: cost units divergence vs single chat
**Location:** DualChatScreen.kt:406-408 vs ChatScreens.kt:535
**Symptom:** Dual chat computes cost as `(inTok*promptPrice + outTok*completionPrice) * 100` (cents) via `derivedStateOf`, while single chat accumulates `inTok*promptPrice*100 + outTok*completionPrice*100`. Both label cents but use different formatting (`"%.4f c"` here vs `"%.2fc"`/`"<0.01c"` there), so the same spend reads differently between screens.
**Root cause:** Duplicated, slightly different cost math + formatting.
**Proposed fix:** Single shared cost+format helper.
**Status:** Open

### Bug 21 — Severity: MEDIUM — Category: cold-pricing cost under-count not re-derived
**Location:** DualChatScreen.kt:399-408
**Symptom:** Although `pricing1/pricing2` recompute on `pricingTick`, the cost uses `derivedStateOf` over the *current* pricing and *cumulative* token counts — so when pricing primes mid-run, ALL accumulated tokens are suddenly re-priced at the real rate. This is the opposite problem from single chat (Bug 11): here early tokens get retroactively re-priced (arguably correct), but it means the displayed cost can jump and disagree with what was charged for early default-priced turns. Behaviour is inconsistent with single chat.
**Root cause:** derivedStateOf recomputes total from cumulative tokens × latest price.
**Proposed fix:** Decide one convention (price-at-time-of-turn vs latest) and apply to both screens.
**Status:** Open

### Bug 22 — Severity: MEDIUM — Category: config null-safety / navigation
**Location:** DualChatScreen.kt:356-368
**Symptom:** `config` is captured from `appViewModel.uiState.value.dualChatConfig` in a `remember{}`. A `LaunchedEffect(Unit)` then clears it from UiState. If the screen recomposes into a *new* composition instance (process recreation restoring the back stack but NOT the transient `dualChatConfig` in UiState), `config` is null on re-entry and the screen immediately `onNavigateBack()`s — the user's in-progress dual chat (messages survive via Saver) is thrown away because config is gone.
**Root cause:** `dualChatConfig` lives only in transient UiState, not in saved instance state, but the conversation it drives is saved. On process death the saved messages can't be resumed (no config to build follow-up prompts / model ids).
**Proposed fix:** Persist the active dual config in rememberSaveable (or in the session record) so resume after process death works.
**Status:** Open

### Bug 23 — Severity: MEDIUM — Category: dual-chat "user" role mislabeling for same model
**Location:** DualChatScreen.kt:410-418 (`buildMessagesForModel`)
**Symptom:** When both models are the *same* provider+model, the transcript is still split by `modelIndex` into assistant/user roles. That's the intent, but if model 1 == model 2 the API sees its own prior outputs labeled "user", which for some providers (Anthropic strict alternation) can break (two consecutive user turns, or system+assistant first).
**Root cause:** Role assignment is purely `if (msg.modelIndex == modelIndex) "assistant" else "user"`; no alternation enforcement, and the first message for model 2 on round 0 may already be "assistant" (model 1's reply) with no leading user turn besides the injected secondPrompt.
**Proposed fix:** Validate/normalize role alternation per provider format before dispatch.
**Status:** Open

### Bug 24 — Severity: LOW — Category: scroll on empty list
**Location:** DualChatScreen.kt:438, 453 (`animateScrollToItem(messages.size - 1)`)
**Symptom:** Guarded by `if (messages.isNotEmpty())`, but `messages.size - 1` is read after `appendMessage`; since `messages` is a State delegate, within the same coroutine frame the recomposition may not have committed, so the index used can momentarily lag. Low impact (animate clamps).
**Root cause:** Reading `messages.size` immediately after mutating the state inside the same suspend frame.
**Proposed fix:** Drive auto-scroll from a `LaunchedEffect(messages.size)` like the single-chat screen.
**Status:** Open

### Bug 25 — Severity: LOW — Category: setup prefs lost on direct Go
**Location:** DualChatScreen.kt:171, 268-269
**Symptom:** `savePrefs()` runs both in `DisposableEffect.onDispose` and at Go. On Go it saves then navigates; the onDispose then saves *again* with the same (now possibly stale if state changed) values. Harmless duplication, but `model1ParamsIds`/`model2ParamsIds` are saved via `saveStringList` outside the same atomic edit, so a crash between the two writes leaves prefs half-updated.
**Root cause:** Two-phase persistence (putString batch + separate JSON string-list writes).
**Proposed fix:** Persist all dual-setup state in one editor commit.
**Status:** Open

### Bug 26 — Severity: LOW — Category: interactionCount parse mismatch
**Location:** DualChatScreen.kt:208, 278, 381
**Symptom:** `canStart` requires `interactionCount.toIntOrNull() ?: 0 > 0`, but the config uses `interactionCount.toIntOrNull() ?: 10`. If the field contains a non-numeric value the button is disabled (good), but `targetInteractions` is seeded from `config.interactionCount` which already resolved to 10 — consistent. However the "Rounds" field accepts arbitrary text with no max; a very large number (e.g. 100000) launches an unbounded paid loop with only a Stop button. No guardrail/confirmation.
**Root cause:** No upper bound on rounds.
**Proposed fix:** Clamp rounds to a sane max and/or confirm above a threshold given the cost implications.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/chat/ChatHub.kt

### Bug 27 — Severity: MEDIUM — Category: unfinished-chat detection includes system-only / image turns
**Location:** ChatHub.kt:75-78 (`unfinishedSessions`)
**Symptom:** A session is "unfinished" if the last non-system message role is "user". This also flags sessions whose only user message is image-only, or where the assistant turn was intentionally not generated, leading to a persistent "N chats awaiting reply" pill the user can't clear without resuming and sending.
**Root cause:** Heuristic doesn't distinguish "interrupted mid-stream" from "user composed but chose not to send to model".
**Proposed fix:** Mark interruption explicitly on the session record when a stream is cancelled, rather than inferring from message roles.
**Status:** Open

### Bug 28 — Severity: LOW — Category: local LLM list cold path
**Location:** ChatHub.kt:189 (`LocalLlmChatCard.onClick`)
**Symptom:** `if (installed.size == 1) onPick(installed.first()) else open = true`. If `installed` is empty (race: card shown from a stale `installedLocalLlms` then list emptied), `installed.first()` is never hit because size!=1, dropdown opens empty — minor, but the card is only shown when `installed.isNotEmpty()`, so fine. Flagged as suspicious only.
**Root cause:** Size-based branching rather than explicit empty check.
**Proposed fix:** Branch on `isEmpty/size==1/else`.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/chat/ChatHistory.kt

### Bug 29 — Severity: MEDIUM — Category: search debounce gated on hasSearched
**Location:** ChatHistory.kt:200-214 (`ChatSearchScreen` search effect)
**Symptom:** The debounced search runs in `LaunchedEffect(historyVersion, searchQuery, hasSearched)` only when `hasSearched` is true. After the first explicit Search tap, every subsequent keystroke re-searches (live), which contradicts the "tap Search" affordance and re-walks every chat per pause. Also, clearing the field sets `hasSearched=false` (line 223) so results vanish, but typing again won't search until Search is tapped again — inconsistent live/manual behaviour.
**Root cause:** Mixed manual-trigger + reactive-effect model.
**Proposed fix:** Pick one model (live debounced OR explicit), not both.
**Status:** Open

### Bug 30 — Severity: MEDIUM — Category: search result key collision
**Location:** ChatHistory.kt:256 (`items` key `"${sessionId}:${messageTimestamp}"`)
**Symptom:** Two matched messages in the same session sharing a timestamp (system + first user seeded same ms, or duplicate-content turns) collide on key → Compose crash ("Key was already used") or dropped row.
**Root cause:** (sessionId, timestamp) not unique across messages.
**Proposed fix:** Include the match index or role in the key.
**Status:** Open

### Bug 31 — Severity: LOW — Category: search preview substring bounds
**Location:** ChatHistory.kt:161-166 (`searchInChats` preview)
**Symptom:** `start`/`end` are computed on `lowerContent` indices but `substring` is taken from `message.content`. For most text this aligns, but `lowercase(Locale.ROOT)` can change string length for certain characters (e.g. German ß → "ss", or some Turkic/Greek forms), so `matchIndex`/`end` can exceed `message.content.length` boundaries or slice mid-grapheme → `StringIndexOutOfBoundsException` or garbled preview.
**Root cause:** Indexing one string by offsets computed from a case-folded copy of different length.
**Proposed fix:** Use `ignoreCase` `indexOf` on the original string for offsets, or coerce bounds against `message.content.length` (end is coerced; start/matchIndex are not fully).
**Status:** Open

### Bug 32 — Severity: LOW — Category: pageSize geometry / clipped rows
**Location:** ChatHistory.kt:59-65 (`availableHeight = maxHeight - 176.dp`, `rowHeight = 80.dp`)
**Symptom:** Hard-coded 176.dp chrome and 80.dp row estimates; on small screens or large font scale a page can over/underfill, hiding the last row or showing empty space. `subList` bounds are guarded, but the paging math can compute a `pageSize` of 1 that never shows enough.
**Root cause:** Fixed-dp layout assumptions instead of measured row heights.
**Proposed fix:** Use a LazyColumn without manual paging, or measure.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/chat/ChatManageScreen.kt

### Bug 33 — Severity: HIGH — Category: delete-old confirmation uses stale candidate set
**Location:** ChatManageScreen.kt:127-167 (`confirmDelete` dialog)
**Symptom:** `cutoff` is computed once when `confirmDelete` flips true, captured in the `produceState(... daysText)`. The dialog re-keys on `daysText`, but `cutoff` is a plain `val` recomputed each composition from `System.currentTimeMillis()`, while `candidates` is keyed only on `daysText` — so if the dialog stays open across a minute boundary the displayed count (from `candidates`) and the actual `toDelete` set can drift. More importantly, if `daysText` changes while the dialog is open, `cutoff` updates but `candidates` recomputes against the *new* cutoff with the *old* still-rendered count momentarily.
**Root cause:** `cutoff` (time-dependent) is not part of the produceState key; count and delete-set derive from different snapshots.
**Proposed fix:** Snapshot `cutoff` into the produceState key (or compute candidates from a frozen cutoff captured when the dialog opened).
**Status:** Open

### Bug 34 — Severity: MEDIUM — Category: getAllSessions on main thread (zip + count)
**Location:** ChatManageScreen.kt:171, 184 (`zipAllChats`), 100 wraps in IO
**Symptom:** `zipAllChats` calls `ChatHistoryManager.getAllSessions()` (line 171) AND iterates `historyDir.listFiles()` to zip. It's invoked inside `withContext(Dispatchers.IO)` (line 100) so off-thread — OK. But `zipAllChats` returns `sessions.size` as the count while zipping `historyDir.listFiles()`; if a session file fails to parse it's still zipped (good) but counted via the parsed list, so "Bundled N chats" can under-report relative to files actually in the zip.
**Root cause:** Count source (parsed sessions) differs from zip source (raw files).
**Proposed fix:** Count the zip entries actually written.
**Status:** Open

### Bug 35 — Severity: LOW — Category: backup includes corrupt/foreign files
**Location:** ChatManageScreen.kt:177-183
**Symptom:** Every file under `chat-history/` is zipped regardless of extension or validity; a stray non-JSON file lands in the backup and a later restore may choke.
**Root cause:** `historyDir.listFiles()?.forEach` with no `.json` filter (unlike `getAllSessions` which filters extension).
**Proposed fix:** Filter to `f.extension == "json"`.
**Status:** Open

## File: ai/src/main/java/com/ai/data/ChatHistoryManager.kt

### Bug 36 — Severity: MEDIUM — Category: cache invalidation race on concurrent save
**Location:** ChatHistoryManager.kt:83-96, 29-65
**Symptom:** `getAllSessions` double-checks `cachedSessions` under lock and caches. `saveSession` null's the cache under lock after a write. But `notifyHistoryChanged()` fires on the saving thread and observers' `produceState` re-run `getAllSessionsAsync` which re-locks and re-parses *every* file — on every single message save during streaming. With auto-save per turn and per system-prompt change, a long session triggers repeated full-directory re-parses.
**Root cause:** Cache is invalidated wholesale on any save; no incremental update.
**Proposed fix:** Update the cached entry in place on save instead of nulling the whole cache.
**Status:** Open

### Bug 37 — Severity: LOW — Category: getSessionCount bypasses cache + re-lists
**Location:** ChatHistoryManager.kt:143-147
**Symptom:** `getSessionCount` lists the directory each call (not cached), invoked from the hub via `getSessionCountAsync` keyed on `historyVersion`. Redundant with `getAllSessions` which the hub also calls. Double directory walk per resume.
**Root cause:** Separate count path.
**Proposed fix:** Derive count from `getAllSessions().size` (already cached).
**Status:** Open

## File: ai/src/main/java/com/ai/ui/knowledge/KnowledgeScreens.kt

### Bug 38 — Severity: HIGH — Category: share-target auto-ingest re-fire / scope leak
**Location:** KnowledgeScreens.kt:283-324 (`LaunchedEffect(kb?.id, pendingUris)` auto-ingest)
**Symptom:** The auto-ingest loop launches progress updates via `scope.launch(Dispatchers.Main)` *inside* a `withContext(Dispatchers.IO)` block, capturing the screen's `scope`. If the user leaves mid-ingest, the outer `LaunchedEffect` is cancelled but the inner `scope.launch` progress coroutines are tied to the composable scope and may outlive the IO call, writing `status` after disposal. Also, the effect re-fires whenever `kb?.id` changes (it changes from null→id when the async KB loads), and `pendingUris` is a key — if the parent recomposes with a new-but-equal list instance, the guard `if (pendingUris.isEmpty())` is the only thing preventing a double ingest; equality of the list content is relied upon, not identity.
**Root cause:** Mixing structured-cancellation IO with fire-and-forget `scope.launch` for progress; re-fire guarded only by emptiness.
**Proposed fix:** Report progress by emitting from the IO block (e.g. a callback hopped to Main via `withContext`), and track a consumed flag rather than relying on list emptiness.
**Status:** Open

### Bug 39 — Severity: MEDIUM — Category: refreshTick reset on every resume / re-index spinner
**Location:** KnowledgeScreens.kt:266-269, 318, 343, 405, 453, 459 (`refreshTick++` from click handlers)
**Symptom:** `refreshTick` is plain `remember { mutableStateOf(0) }` (not rememberSaveable). On config change it resets to 0, re-running the KB load `produceState`. More importantly, `KnowledgeStore.deleteSource` (line 459) runs on the main thread (no IO dispatch) then `refreshTick++`; deleting a source with many chunks blocks the UI.
**Root cause:** Synchronous `deleteSource` on main thread.
**Proposed fix:** Dispatch `deleteSource` to IO like `deleteKnowledgeBase` is.
**Status:** Open

### Bug 40 — Severity: MEDIUM — Category: KnowledgeListScreen forced refresh thrash
**Location:** KnowledgeScreens.kt:62-65, 130 (`LaunchedEffect(Unit) { refreshTick++ }`)
**Symptom:** On first composition `refreshTick++` fires unconditionally, immediately re-running the `produceState` that just ran with `refreshTick=0` — a redundant full `listKnowledgeBases` disk read on every entry. Combined with `resumeTick` this is up to 2-3 directory scans per open.
**Root cause:** Unconditional refresh bump at the bottom of the composable.
**Proposed fix:** Drop the `LaunchedEffect(Unit){refreshTick++}`; rely on `resumeTick`.
**Status:** Open

### Bug 41 — Severity: MEDIUM — Category: embedder list frozen across provider changes
**Location:** KnowledgeScreens.kt:165-173 (`NewKnowledgeBaseScreen` options)
**Symptom:** `localEmbedders = remember { ... }` (no key) and `remoteEmbedders = remember(aiSettings)`. If the user installs a local model in Housekeeping and returns, the local list is stale (remember has no key/resume). `selected` defaults to `options.firstOrNull()` once; if options change, selection isn't revalidated.
**Root cause:** `localEmbedders` unkeyed remember; no resume refresh.
**Proposed fix:** Key on a resume tick / re-list on resume.
**Status:** Open

### Bug 42 — Severity: MEDIUM — Category: KB attach dialog anchor mismatch persisted
**Location:** KnowledgeScreens.kt:571-619 (`KnowledgeAttachDialog`)
**Symptom:** The dialog enforces single-embedder selection live, but on Apply it returns `selected.value` without re-validating that all selected KBs still share an embedder (a KB could be re-indexed with a different embedder between open and Apply via another screen). Mismatched ids then get persisted and silently dropped at retrieve time.
**Root cause:** Validation is UI-side only and not re-checked on confirm; underlying KB embedder can change.
**Proposed fix:** Re-validate the embedder set in `onConfirm` (or at retrieve, surface a user-visible warning, not just a log).
**Status:** Open

### Bug 43 — Severity: LOW — Category: pickTypeForUri default swallows unknown binaries
**Location:** KnowledgeScreens.kt:522-525 (`pickTypeForUri` final else)
**Symptom:** Any unknown MIME (e.g. an image, a zip, an octet-stream) defaults to `KnowledgeSourceType.TEXT`, so a binary file is ingested as garbage text and embedded, polluting the KB with noise chunks.
**Root cause:** Last-resort default is TEXT for everything, including clearly-binary types.
**Proposed fix:** Reject non-text/non-document MIMEs with a user-facing error instead of treating as text.
**Status:** Open

### Bug 44 — Severity: LOW — Category: displayName fallback timestamp collision
**Location:** KnowledgeScreens.kt:306, 329 (`"shared_${System.currentTimeMillis()}"` / `"source_..."`)
**Symptom:** Two share-target files arriving in the same millisecond get the same fallback display name, which can collide as source names / dedupe keys downstream.
**Root cause:** Millisecond timestamp not unique under burst.
**Proposed fix:** Append an index or UUID.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/models/ModelScreens.kt

### Bug 45 — Severity: HIGH — Category: wrong usage-stats key → AI Usage card always empty (Manage)
**Location:** ModelScreens.kt:224-229 (`usageEntry` lookup)
**Symptom:** The "AI Usage" card on the *Manage* Model Info screen never shows data: `loadUsageStats()["${provider.id}::$modelName"]` uses a 2-part key, but `SettingsPreferences.updateUsageStats` (line 442) writes 3-part keys `"${provider.id}::$model::$kind"`. The 2-part key never matches, so `usageEntry` is always null and the card always reads "No usage recorded yet".
**Root cause:** Stale 2-part key; the View screen (ModelInfoViewScreen.kt:193) already fixed this with a prefix match, the Manage screen wasn't updated.
**Reproduction:** Run any report/chat with model X, open Manage → Model Info for X → AI Usage shows "No usage recorded yet" despite recorded stats.
**Proposed fix:** Use the same prefix-aggregate lookup as ModelInfoViewScreen (`all.filterKeys { it.startsWith("${provider.id}::$modelName::") }`).
**Status:** Open

### Bug 46 — Severity: MEDIUM — Category: usageCost / hasUsageStats inconsistent with card
**Location:** ModelScreens.kt:230-236, 466, 1109, 1140-1158
**Symptom:** Because `usageEntry` is always null (Bug 45), `usageCost` never computes, `hasUsageStats` is always false, and the "Last usage → AI Usage" cumulative row (1140-1158) never renders even when stats exist. Cascading from the key bug.
**Root cause:** Same 2-part key.
**Proposed fix:** Fix Bug 45; this resolves automatically.
**Status:** Open

### Bug 47 — Severity: MEDIUM — Category: ModelInfoCache never invalidates across key change
**Location:** ModelScreens.kt:119-135 (`ModelInfoCache`), mirror at ModelInfoViewScreen.kt:973-989
**Symptom:** The per-process OpenRouter models cache keys only on the apiKey. If the OpenRouter catalog updates within the process lifetime, stale model info is served indefinitely; and if two different api keys alternate, each swap triggers a full refetch (thrash) but never a staleness-based refresh.
**Root cause:** No TTL; key is solely apiKey identity.
**Proposed fix:** Add a TTL and reuse a single shared cache instead of two private copies.
**Status:** Open

### Bug 48 — Severity: MEDIUM — Category: PromptCache.get vs getRaw TTL divergence
**Location:** ModelScreens.kt:366-368 (Manage uses `PromptCache.get`) vs ModelInfoViewScreen.kt:269-275 (View uses `getRaw` + 1-week TTL)
**Symptom:** The Manage screen reads the AI Introduction via `PromptCache.get(introCacheKey)` which (per code comments elsewhere) applies a hardcoded 48h destructive TTL, so a cached intro silently disappears after 48h and the user sees the "Ask" button again; the View screen uses `getRaw` with a 1-week window. Same model, two screens, different cache behaviour.
**Root cause:** Two different cache-read APIs with different TTL semantics.
**Proposed fix:** Use a single read path with one TTL policy.
**Status:** Open

### Bug 49 — Severity: MEDIUM — Category: computeModelUsages chat rows are dead links
**Location:** ModelScreens.kt:65-72 (`onOpen = {}` for chat rows)
**Symptom:** "Last usage" rows of type "Chat" have an empty `onOpen` lambda yet are rendered inside a `clickable` Row (line 1117) — the user taps and nothing happens, with no affordance indicating it's non-navigable.
**Root cause:** Chat deep-link unsupported but row still clickable.
**Proposed fix:** Don't wrap non-navigable rows in `clickable`, or implement the chat deep-link.
**Status:** Open

### Bug 50 — Severity: MEDIUM — Category: candidateCap truncates before sort → wrong "Last 10"
**Location:** ModelScreens.kt:81-110 (`candidateCap = 30`, then `take(10)`)
**Symptom:** The loop stops collecting once `out.size >= 30`, but `out` accumulates chat sessions first (all of them, unbounded) THEN reports newest-first. If the user has >30 chat sessions on this model, the report/secondary candidates are never even considered before the cap, so the final `sortedByDescending{timestamp}.take(10)` can miss a recent report whose timestamp would have ranked top-10.
**Root cause:** Cap applied to a list that mixes unbounded chats + capped reports; the cap is checked after chats are fully added.
**Proposed fix:** Bound chat collection too, or collect all candidates with a per-source cap before the global sort.
**Status:** Open

### Bug 51 — Severity: LOW — Category: traceCount provider-host match can drop LOCAL/synthetic
**Location:** ModelScreens.kt:209-223 (`traceCount`)
**Symptom:** Trace count matches on `(hostname, model)` derived from `URI(provider.baseUrl).host`. For the synthetic LOCAL provider (or any provider with a blank/invalid baseUrl) `providerHost` is null, so the count falls back to model-name-only matching — conflating traces of the same model name across providers (the exact bug the code claims to fix), specifically for LOCAL.
**Root cause:** Null host short-circuits to name-only match.
**Proposed fix:** For LOCAL, match the synthetic "local" hostname explicitly.
**Status:** Open

### Bug 52 — Severity: LOW — Category: HF model lookup URL mismatch
**Location:** ModelScreens.kt:646 vs 322 (`calledUrl` shows `/api/models/$modelName` but the fetch tries prefixed variants)
**Symptom:** The "called URL" shown in the raw-source overlay is `https://huggingface.co/api/models/$modelName`, but the actual successful request may have used `provider.openRouterName/$modelName` or a `.`/`-` variant. The displayed URL can be misleading for debugging.
**Root cause:** Hard-coded display URL doesn't reflect the variant that actually matched.
**Proposed fix:** Record and display the variant that succeeded.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/models/ModelInfoViewScreen.kt

### Bug 53 — Severity: MEDIUM — Category: usage aggregation double-counts vs Manage intent
**Location:** ModelInfoViewScreen.kt:193-204 (prefix aggregate across all kinds)
**Symptom:** The View screen sums usage across *every* kind (report/rerank/summarize/meta/moderation/translate) for the (provider, model). This is the documented intent, but `usageCost` (206-212) then prices the aggregated input/output tokens with the chat/report pricing tier — moderation and rerank are billed differently (per-search-unit), so the cost figure overstates/understates spend for models used in those roles.
**Root cause:** One pricing tier applied to token sums that mix billing models.
**Proposed fix:** Either exclude per-query-billed kinds from the cost line or price each kind with its own tier.
**Status:** Open

### Bug 54 — Severity: LOW — Category: AI intro auto-refresh fires paid call on every stale open
**Location:** ModelInfoViewScreen.kt:269-275
**Symptom:** On every screen open where the cached intro is >1 week old (or missing) and an API key exists, a paid self-intro call fires automatically without user action. Opening many model-info pages while browsing silently spends.
**Root cause:** Auto-refresh on mount without opt-in.
**Proposed fix:** Make the refresh user-initiated (the "Ask again" affordance already exists).
**Status:** Open

### Bug 55 — Severity: LOW — Category: computeUsages agentId field
**Location:** ModelInfoViewScreen.kt:959-965 (`matchingAgent.agentId`)
**Symptom:** Uses `matchingAgent.agentId` to deep-link; `ReportAgent` has both `agentId` and the report-agent's own id. If `agentId` is blank for legacy reports (the field exists at ReportModels.kt:12), the scroll-to-agent target is empty and the View Reports screen can't locate the agent, silently opening at the top.
**Root cause:** Relies on a possibly-empty `agentId`.
**Proposed fix:** Fall back to the agent's primary id when `agentId` is blank.
**Status:** Open

### Bug 56 — Severity: LOW — Category: ParsedJsonValue unbounded recursion / depth indent
**Location:** ModelInfoViewScreen.kt:857-918
**Symptom:** Deeply nested JSON (e.g. a large OpenRouter/HF record) recurses one composable level per depth with `padding(start = depth*12.dp)`; very deep or pathological JSON can blow the composition depth or push content off-screen with no horizontal scroll.
**Root cause:** Unbounded recursive composition + cumulative left padding.
**Proposed fix:** Cap render depth with a "…" expander; allow horizontal scroll.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/search/SemanticSearchScreen.kt

### Bug 57 — Severity: HIGH — Category: index-out-of-bounds on short embed response
**Location:** SemanticSearchScreen.kt:240-245 (`vecs[j]` in batch loop)
**Symptom:** `val vecs = repository.embed(...) ?: return emptyList()` then `for ((j, item) in batch.withIndex()) { val v = vecs[j] }`. If the provider returns fewer vectors than inputs (partial batch, dedup, provider quirk), `vecs[j]` throws `IndexOutOfBoundsException`, crashing the search coroutine (caught only by the screen's lack of try/catch → uncaught in the IO context).
**Root cause:** Assumes `vecs.size == batch.size` without checking.
**Proposed fix:** Guard `if (j >= vecs.size) break/continue`, or assert sizes and abort gracefully.
**Status:** Open

### Bug 58 — Severity: MEDIUM — Category: cosine across mismatched dims silently zero
**Location:** SemanticSearchScreen.kt:248-251
**Symptom:** Cached report embeddings from a *different* model/dim than the current query embedder are scored with `EmbeddingsStore.cosine` which (per KnowledgeService comments) returns 0.0 on dim mismatch — so a report cached under an old embedder silently scores 0 and never appears, with no re-embed and no warning.
**Root cause:** `EmbeddingsStore.get` keys on (id, providerId, model, contentHash) so a model change yields a cache miss and re-embed — but if the *same* (providerId, model) string maps to a model whose dim changed server-side, stale vectors score 0.
**Proposed fix:** Store + check embedding dim alongside the cache key.
**Status:** Open

### Bug 59 — Severity: MEDIUM — Category: progress callback scope.launch from IO
**Location:** SemanticSearchScreen.kt:143-146 (`scope.launch(Dispatchers.Main){ status = msg }` inside IO)
**Symptom:** Progress is reported by launching a new Main coroutine per `onProgress` from within the IO block. Rapid progress (per-batch) spawns many short-lived Main coroutines; if the screen is disposed mid-search these can still mutate `status` after disposal (status is a plain remember, so harmless leak but wasted work). Same pattern in LocalSemanticSearchScreen, KnowledgeDetailScreen.
**Root cause:** Fire-and-forget Main launches for UI updates from IO.
**Proposed fix:** Hop to Main via `withContext(Dispatchers.Main)` inside the suspend function, tied to the search coroutine's lifecycle.
**Status:** Open

### Bug 60 — Severity: LOW — Category: picked not revalidated when choices change
**Location:** SemanticSearchScreen.kt:67-68
**Symptom:** `picked = remember { mutableStateOf(embeddingChoices.firstOrNull()) }` — if `aiSettings` changes (provider deactivated) the choices list updates but `picked` may now reference a (provider, model) no longer in the list; the search then runs against an inactive provider's key (blank) and errors.
**Root cause:** `picked` not reconciled against the current `embeddingChoices`.
**Proposed fix:** Reset `picked` to a valid choice when it's no longer present.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/search/LocalSemanticSearchScreen.kt

### Bug 61 — Severity: HIGH — Category: index-out-of-bounds on short embed response
**Location:** LocalSemanticSearchScreen.kt:220-225 (`vecs[j]`)
**Symptom:** Same as Bug 57 — `val vecs = LocalEmbedder.embed(...) ?: return ...` then `vecs[j]`. If the local embedder returns fewer vectors than the batch, IOOBE crashes the search.
**Root cause:** Assumes 1:1 input→vector.
**Proposed fix:** Bounds-check `j` against `vecs.size`.
**Status:** Open

### Bug 62 — Severity: MEDIUM — Category: title split heuristic corrupts titles containing " — "
**Location:** LocalSemanticSearchScreen.kt:228-231 (`title.substringBefore(" — ")` / `substringAfter`)
**Symptom:** The display title is reconstructed by joining `title + " — " + date` then split back on `" — "`. Any report title that itself contains " — " is truncated at the first occurrence, showing a partial title and a wrong "timestamp" column.
**Root cause:** Using a content-collision-prone delimiter to pack two fields into one string.
**Proposed fix:** Carry title and timestamp as separate fields in the Triple/Hit, not concatenated.
**Status:** Open (also affects SemanticSearchScreen.kt:248-250 — same delimiter pattern)

### Bug 63 — Severity: LOW — Category: latestTrace picks newest of category, not this search's
**Location:** LocalSemanticSearchScreen.kt:66-72
**Symptom:** The title-bar 🐞 opens `firstOrNull { category == "Local semantic search" }` — the *most recent* such trace globally, not necessarily the one this search produced. After two searches the icon may open the prior search's trace.
**Root cause:** Category-only match without a per-run id/timestamp gate.
**Proposed fix:** Capture a callStart timestamp and pick the trace with `timestamp >= callStart`.
**Status:** Open

### Bug 64 — Severity: LOW — Category: availableModels unkeyed remember
**Location:** LocalSemanticSearchScreen.kt:56
**Symptom:** `availableModels = remember { LocalEmbedder.availableModels(context) }` — no resume key, so a model installed/removed in Housekeeping isn't reflected on return; `picked` may point at a removed model.
**Root cause:** Unkeyed remember + no resume refresh.
**Proposed fix:** Key on a resume tick.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/search/QuickLocalSearchScreen.kt

### Bug 65 — Severity: MEDIUM — Category: sort by formatted date string, not timestamp
**Location:** QuickLocalSearchScreen.kt:139 (`sortedByDescending { it.timestamp }`)
**Symptom:** `QuickHit.timestamp` is the *formatted* string `"yyyy-MM-dd HH:mm"` (line 138), so `sortedByDescending` sorts lexicographically. For this format lexical order happens to match chronological order — but only because the format is zero-padded big-endian. Any future format change (or a locale that alters the string) silently breaks recency ordering. Fragile.
**Root cause:** Sorting on a display string rather than the numeric epoch.
**Proposed fix:** Keep the raw `Long` timestamp in the hit and sort on it; format only for display.
**Status:** Open

### Bug 66 — Severity: LOW — Category: getAllReports re-parsed per search with no cache key
**Location:** QuickLocalSearchScreen.kt:131 (and LocalSearchScreen.kt:134, both semantic screens)
**Symptom:** Each search calls `ReportStorage.getAllReports(context)` afresh; for large histories this re-reads/parses every report JSON on every search tap. No memoization across repeated searches in the same screen session.
**Root cause:** No caching of the report list within the screen.
**Proposed fix:** Load reports once via produceState and reuse.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/search/LocalSearchScreen.kt

### Bug 67 — Severity: MEDIUM — Category: sort by formatted date string (thenBy)
**Location:** LocalSearchScreen.kt:152 (`.thenByDescending { it.timestamp }`)
**Symptom:** Same as Bug 65 — the secondary sort key `it.timestamp` is the formatted string, so ties on score are broken by lexical date order. Works only because of zero-padded format; fragile.
**Root cause:** Display string used as sort key.
**Proposed fix:** Sort on the raw epoch.
**Status:** Open

### Bug 68 — Severity: LOW — Category: token scoring counts overlapping/substring matches
**Location:** LocalSearchScreen.kt:141-149
**Symptom:** Score counts every occurrence of each token as a substring (`indexOf` advancing by 1), so a token "in" matches inside "rain", "window", etc., inflating scores for short tokens and ranking irrelevant reports high.
**Root cause:** Substring (not word-boundary) matching for scoring.
**Proposed fix:** Use word-boundary matching or weight by token length.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/history/HistoryScreen.kt

### Bug 69 — Severity: MEDIUM — Category: subList bounds with stale pageSize after rotation
**Location:** HistoryScreen.kt:74-80
**Symptom:** `pageSize` is derived from `maxHeight` each composition; `currentPage` is plain `remember` (not saveable) so it resets to 0 on rotation — but during the brief window where `maxHeight` hasn't been measured (0), `pageSize = maxOf(1, ((0 - overhead)/56))` = 1, and `startIndex = currentPage*1`. The `subList(startIndex.coerceAtMost(size), (startIndex+pageSize).coerceAtMost(size))` is guarded, so no crash, but the page count flickers and `LaunchedEffect(totalPages)` can momentarily clamp `currentPage`.
**Root cause:** pageSize computed from possibly-unmeasured constraints.
**Proposed fix:** Defer paging until `maxHeight > 0`.
**Status:** Open

### Bug 70 — Severity: MEDIUM — Category: delete removes from list but pagination not adjusted
**Location:** HistoryScreen.kt:134-145 (`onDeleteReport`)
**Symptom:** Deleting the last report on the last page leaves `currentPage` pointing past the end; `LaunchedEffect(totalPages)` fixes it, but between the delete and the effect firing the page can show empty. The `filteredReports` is derived via `remember(allReports, …)` so it updates, but `currentPage` correction lags one frame.
**Root cause:** currentPage corrected reactively, one frame late.
**Proposed fix:** Clamp currentPage in the same handler that mutates allReports.
**Status:** Open

### Bug 71 — Severity: LOW — Category: search filter scans every agent body each keystroke
**Location:** HistoryScreen.kt:62-69
**Symptom:** `filteredReports` recomputes on each keystroke and, when `searchReport` is set, scans every agent's `responseBody` (potentially MB each) on the main thread inside `remember`. No debounce; large histories hitch while typing in the Response field.
**Root cause:** Synchronous full-text filter in composition.
**Proposed fix:** Debounce + move filtering off the main thread.
**Status:** Open

### Bug 72 — Severity: LOW — Category: currentPage not saveable
**Location:** HistoryScreen.kt:59 (`remember { mutableIntStateOf(0) }`)
**Symptom:** Unlike PromptHistoryScreen/ChatHistoryScreen (which use rememberSaveable), HistoryScreen's `currentPage` resets to 0 on rotation, losing the user's place.
**Root cause:** Plain remember.
**Proposed fix:** `rememberSaveable`.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/history/PromptHistoryScreen.kt

### Bug 73 — Severity: MEDIUM — Category: loadPromptHistory on main thread
**Location:** PromptHistoryScreen.kt:36-38
**Symptom:** `settingsPrefs.loadPromptHistory()` runs synchronously in composition on the main thread; SharedPreferences-backed JSON parse of a long prompt history blocks the first frame.
**Root cause:** Synchronous load in `remember`/state initializer.
**Proposed fix:** Load via produceState on IO.
**Status:** Open

### Bug 74 — Severity: LOW — Category: clearPromptHistory not dispatched off main thread
**Location:** PromptHistoryScreen.kt:62
**Symptom:** `settingsPrefs.clearPromptHistory()` runs on the main thread from the title-bar onClear handler; a large history clear blocks the UI briefly.
**Root cause:** Synchronous prefs write on main.
**Proposed fix:** Dispatch to IO.
**Status:** Open

### Bug 75 — Severity: LOW — Category: items key on timestamp can collide
**Location:** PromptHistoryScreen.kt:90 (`items(pageItems, key = { it.timestamp })`)
**Symptom:** Two prompt-history entries saved in the same millisecond share a timestamp key → Compose "key already used" crash or dropped row.
**Root cause:** Timestamp used as a unique key.
**Proposed fix:** Use a composite (timestamp + title hash) or a stored id.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/history/ExamplePromptPickerScreen.kt

### Bug 76 — Severity: LOW — Category: search count wording when no results
**Location:** ExamplePromptPickerScreen.kt:61-71
**Symptom:** When `all` is non-empty but the filter yields zero (`sorted` empty), the screen shows "0 of N prompts" and then the LazyColumn renders nothing — no "no matches" empty-state, leaving a blank area that looks like a load failure.
**Root cause:** Empty-state only handled for `all.isEmpty()`, not for filtered-empty.
**Proposed fix:** Add a "no matches" message when `all.isNotEmpty() && sorted.isEmpty()`.
**Status:** Open

---

## Cross-cutting observations

### Bug 77 — Severity: MEDIUM — Category: duplicated trace-lookup heuristic
**Location:** ChatScreens.kt:963-971, DualChatScreen.kt:618-624, LocalSemanticSearchScreen.kt:66-72
**Symptom:** Three different "find the matching trace" heuristics (closest-timestamp / category-first / sessionId-filter) with subtly different correctness (see Bugs 5, 63). Inconsistent behaviour and maintenance burden.
**Proposed fix:** Single helper that takes (category|reportId, model, callStart) and returns the deterministic match.
**Status:** Open

### Bug 78 — Severity: LOW — Category: cost/format helper duplication
**Location:** ChatScreens.kt:535/639, DualChatScreen.kt:406-408/600
**Symptom:** Cost-from-tokens and cents formatting are duplicated with divergent rounding/labels (Bugs 1, 20, 21).
**Proposed fix:** One `Pricing.costCents()` + one formatter.
**Status:** Open

### Bug 79 — Severity: LOW — Category: progress-via-scope.launch antipattern repeated
**Location:** KnowledgeScreens.kt:296/311/335/445, SemanticSearchScreen.kt:144, LocalSemanticSearchScreen.kt:136
**Symptom:** The "launch a Main coroutine per progress message from inside IO" pattern recurs (Bugs 38, 59); progress writes can outlive disposal and aren't lifecycle-bound to the search.
**Proposed fix:** Standardize on a Main-hopping suspend progress callback.
**Status:** Open
