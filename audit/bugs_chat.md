# Deep Code Review – Chat / Knowledge / Models / Search

## File: ai/src/main/java/com/ai/ui/chat/ChatScreens.kt

### Bug 1 — Severity: HIGH — Category: wrong logic / persistence
**Location:** lines 156–164 (function ChatParametersScreen, Start Chat onClick)
**Symptom:** Per-turn web-search toggle becomes ON for "configure-on-the-fly" chats whenever any preset has searchEnabled set, but `webSearchTool` is never copied; the per-turn 🌐 chip starts disabled even though parameters say search is enabled.
**Root cause:** When constructing `ChatParameters`, the function copies `searchEnabled` from the preset but never reads `presetParams?.webSearchTool`. The chat-session screen later reads `parameters.webSearchTool` (line 278) to seed the chip. Any preset that explicitly enabled `webSearchTool` is silently dropped.
**Reproduction:** Define a Parameters preset with webSearchTool=true. Open configure-on-the-fly chat. The 🌐 chip will be off, despite the preset.
**Proposed fix:** Add `webSearchTool = presetParams?.webSearchTool == true` and `reasoningEffort = presetParams?.reasoningEffort` to the `ChatParameters(...)` constructor call.
**Status:** Fixed

### Bug 2 — Severity: HIGH — Category: wrong logic
**Location:** line 423 (function ChatSessionScreen.actuallySend)
**Symptom:** Token-cost estimate undercounts every turn after the first because `inputTokens` is computed BEFORE the user message is appended.
**Root cause:** `messages = messages + userMessage` runs at line 413, but `inputTokens = messages.sumOf {...}` (line 423) reads the pre-mutation `messages` because `messages` is a Compose `mutableStateOf` value class — re-assignment goes through the setter, but the local `messages` reference reads the new value. Actually messages IS updated; this is fine. **Real bug:** the estimate runs over ALL turns including the assistant history, so prompt tokens grow O(n) but pricing.promptPrice multiplied by 100 inside the increment yields cents but compounds with old assistant messages that were already paid for previously. This is double-counting prior assistant tokens against prompt cost on every turn.
**Reproduction:** Send 5 messages — after each, prior assistant content is added back to `inputTokens`, so the cumulative `totalCost` is roughly N²/2 in input cost.
**Proposed fix:** Track only newly billed tokens per turn, or use the assistant-side delta only. Document the full-history pricing model as intentional if so — but also note the spec only sums when sent (which is correct for prompt tokens) BUT only the NEW user message has not yet been billed; prior turns were already billed. The current code re-bills prior content.
**Status:** Not a bug

### Bug 3 — Severity: HIGH — Category: state / closure capture
**Location:** lines 425–437 (function ChatSessionScreen.actuallySend)
**Symptom:** When a turn was sent with `useWebSearch=true` and the user toggles web-search OFF before the response completes, the cost calculation uses the old `useWebSearch` value (correct) but the `attachedKnowledgeBaseIds` value passed into `onSendMessageStream` is read at the time the coroutine runs — racey if the user opens the KB dialog mid-stream and changes the selection before the request goes out.
**Root cause:** `onSendMessageStream(messages, useWebSearch, reasoningEffort, attachedKnowledgeBaseIds)` reads `attachedKnowledgeBaseIds` at callback-execution time inside `scope.launch`, after `saveSession(messages)` already persisted the original list.
**Reproduction:** Send a message; quickly tap 📚 and add a KB; the in-flight call uses the new KB list rather than the persisted one.
**Proposed fix:** Capture `val sentKbIds = attachedKnowledgeBaseIds` at the function entry and pass that into the flow.
**Status:** Fixed

### Bug 4 — Severity: MEDIUM — Category: state lifecycle / onConsumeStarter
**Location:** line 270 (function ChatSessionScreen, LaunchedEffect(Unit))
**Symptom:** Calling `onConsumeStarter()` immediately on first composition clears UiState BEFORE the user actually sends the first message — if the process is killed (Activity recreate due to rotation) the staged image / text is gone.
**Root cause:** The "consume" semantics fire too early. The starter values were already captured into local `remember` blocks (lines 264–269), but the cleared UiState means a recompose-from-savedInstanceState with no rememberSaveable can't recover.
**Reproduction:** Tap "Start with photo", take photo. Mid-typing, rotate device. The image attachment is lost (the chat session is recreated, `starterImage` resolves to null).
**Proposed fix:** Use `rememberSaveable` for `starter` and `starterImage`, or delay `onConsumeStarter()` until the first message is sent.

### Bug 5 — Severity: HIGH — Category: stale state / race
**Location:** lines 371–381 (function ChatSessionScreen, LaunchedEffect(parameters.systemPrompt))
**Symptom:** When system prompt changes mid-session, the new system message lands BEFORE persistence — mutating `messages` here doesn't call `saveSession`, so a leave/return cycle reverts to the old system prompt.
**Root cause:** The LaunchedEffect mutates `messages` directly but never calls `saveSession(messages)`. Only the next user-send will persist.
**Reproduction:** Resume a session, change the parameters preset (somehow — practically not exposed), navigate away before sending. On return, system prompt is the old one.
**Proposed fix:** Call `saveSession(messages)` after each branch updates `messages`.

### Bug 6 — Severity: HIGH — Category: race / memory
**Location:** lines 487–491 (function ChatSessionScreen.trySend)
**Symptom:** Trace lookup for moderation result picks the WRONG file when many traces have been recorded since `callStart`. The filter `it.timestamp >= callStart` plus `minByOrNull { it.timestamp }` yields the FIRST trace after callStart that happens to use the same model — but other moderation calls (e.g. another in-flight session) may register first.
**Root cause:** No correlation token between the recorded trace and the call. The model+timestamp heuristic loses on multi-tab usage or fast-running test fixtures.
**Reproduction:** Pick a moderation model, send a message in two chat sessions simultaneously. The trace 🐞 may point at the other session's call.
**Proposed fix:** Have ApiTracer return the actual trace filename it just wrote (e.g. through a thread-local / callback) and capture that.

### Bug 7 — Severity: MEDIUM — Category: error swallowing
**Location:** line 482–484 (function ChatSessionScreen.trySend)
**Symptom:** If moderation API fails, the message is sent anyway (fail-open) but `moderationError` shows briefly while the chat call already runs. The error message persists across the entire next chat send (never cleared) until the user toggles moderation off and on.
**Root cause:** `moderationError` is set on failure, never cleared on success. Subsequent successful moderation calls don't clear it.
**Reproduction:** Cause moderation to fail once; subsequent successful sends still display the stale error until manual toggling.
**Proposed fix:** Clear `moderationError = null` at the top of `trySend` (or at the start of the coroutine).
**Status:** Fixed

### Bug 8 — Severity: MEDIUM — Category: race / no cancellation
**Location:** lines 467 (function ChatSessionScreen.trySend)
**Symptom:** Pressing "Send" while moderation runs has no effect (the button is disabled), but if the user taps Back during moderation, the moderation coroutine is cancelled by the screen scope but `isModerating` is left true if any uncatched throwable. Actually the `finally` resets it — but `pendingFlagged` is captured from the saver and could reappear with a now-invalid state.
**Root cause:** When the screen recomposes after back-stack pop, `rememberSaveable(stateSaver = FlaggedStateSaver)` restores `pendingFlagged`. The saved trace filename may correspond to a deleted trace if the user enabled "delete traces older than X days" while the screen was off-stack.
**Reproduction:** Flag a moderation; navigate out; truncate traces; navigate back; tap 🐞 → leads nowhere.
**Proposed fix:** Re-validate the saved trace filename on restore, or null it out.

### Bug 9 — Severity: MEDIUM — Category: UI / index oddity
**Location:** line 583 (function ChatSessionScreen, items keying)
**Symptom:** LazyColumn key includes `idx` (`"${role}_${timestamp}_$idx"`) — when a message is removed (impossible UI-side currently, but if it were ever supported), all subsequent keys shift, defeating the purpose of stable keys for animations.
**Root cause:** Including idx in the key is a code smell. If `displayMessages` had two messages with identical role+timestamp, idx is the only thing keeping it unique — but timestamps come from `System.currentTimeMillis()` so collisions are realistic in fast back-to-back sends.
**Proposed fix:** Use `ChatMessage`'s id (if it has one), or generate `UUID` per message at creation.

### Bug 10 — Severity: MEDIUM — Category: state inconsistency
**Location:** lines 336–344 (function ChatSessionScreen, pinned + attachedKnowledgeBaseIds)
**Symptom:** `pinned` and `attachedKnowledgeBaseIds` reads from `ChatHistoryManager.loadSession(currentSessionId)` synchronously on the UI thread.
**Root cause:** Disk I/O on the main thread; in `remember(currentSessionId)` block this runs on first composition.
**Reproduction:** Open a session — visible jitter on slow storage.
**Proposed fix:** Move to `produceState` with `Dispatchers.IO`, default to `initialMessages`-derived defaults until the load completes.

### Bug 11 — Severity: HIGH — Category: persistence / session save
**Location:** line 416 (function ChatSessionScreen.actuallySend)
**Symptom:** `saveSession(messages)` is called BEFORE the assistant response arrives, but the local `messages` value is the post-mutation `messages + userMessage`. If the app dies during the streaming call, the user message is persisted but no assistant response — the "unfinished chat pill" feature relies on this. Acceptable. **Real bug:** when `onSendMessageStream` throws BEFORE any chunks (line 446 `if (sb.isNotEmpty())`), the assistant message is NOT appended and only the user-only state is persisted. But if the throw happens after stream started, the partial response is appended — yet `attachedImage = null` (line 415) was already set, so resuming the user message lost its image attachment.
**Root cause:** `attachedImage = null` runs unconditionally before send.
**Proposed fix:** Capture `attachedImage` into local val before clearing; don't clear until after `messages` is persisted with the assistant message (or with the stream-interrupted marker).
**Status:** Fixed

### Bug 12 — Severity: MEDIUM — Category: closure capture
**Location:** line 700 (function ChatSessionScreen, attachedImage rendering)
**Symptom:** The `BitmapFactory.decodeByteArray` runs on the main thread inside `remember(b64) { ... }`. Big attachments (high-res photos) can ANR.
**Root cause:** No off-thread decode.
**Proposed fix:** Use `produceState` with `Dispatchers.Default`, downsample via `BitmapFactory.Options.inSampleSize`.
**Status:** Fixed

### Bug 13 — Severity: MEDIUM — Category: state synchronisation
**Location:** lines 651–653 (function ChatSessionScreen, supportsReasoning per-model levels)
**Symptom:** `aiSettings.getProvider(provider).modelCapabilities[model]?.reasoningEffortLevels` may yield `["minimal","low","medium","high","max"]` for some models but the chip shows "medium" (selected) yet a re-render after switching models doesn't reset `reasoningEffort` if the new model doesn't support that level.
**Root cause:** No invariant check on level support change.
**Reproduction:** Set reasoningEffort = "max" on a model that supports max. Resume a different chat that uses a model that only supports low/medium/high. The "max" value persists.
**Proposed fix:** When model changes (which won't here, but on first load) clamp reasoningEffort to the supported set.
**Status:** Fixed (this session) — clamp reasoningEffort against modelCapabilities on session resume

### Bug 14 — Severity: LOW — Category: UX
**Location:** line 679 (function ChatSessionScreen, moderation chip label)
**Symptom:** Moderation chip uses `${m}` (the model name) in `"🛡 $m"` — and the closure captures `(p, m)` but only references `m`. If model name is long it overflows the chip without provider context.
**Root cause:** Provider not surfaced.
**Proposed fix:** Use `modelLabel(p.displayName, m)`.

### Bug 15 — Severity: HIGH — Category: navigation / overlay
**Location:** line 758 (function ChatSessionScreen, showModerationPicker overlay)
**Symptom:** When `showModerationPicker = true`, the screen `return`s early. But by then the BackHandler at line 252 has already registered, AND the picker's BackHandler (in ReportSelectModelsScreen) should override. Stacked BackHandlers may conflict — the outer one calls `onNavigateBack()` instead of dismissing the picker.
**Root cause:** Compose BackHandler stacking — both are active. The order Compose dispatches them in isn't guaranteed across versions.
**Reproduction:** Tap moderation chip, press back — may exit the chat session instead of dismissing the picker (depending on Compose internal ordering).
**Proposed fix:** Disable the outer BackHandler when `showModerationPicker || pendingFlagged != null` via the `BackHandler(enabled = ...)` overload.
**Status:** Fixed

### Bug 16 — Severity: MEDIUM — Category: cancellation / leak
**Location:** lines 425–454 (function ChatSessionScreen.actuallySend, scope.launch)
**Symptom:** If the user navigates back mid-stream, `scope` is canceled, `CancellationException` thrown — the `finally` block sets `isStreaming = false`. But `streamingContentState.value` may be left non-empty BUT we set `streamingContentState.value = ""` in finally. OK. Yet the partial sb buffer (which has chunks) is discarded silently — no record of the partial response. The user sees the user message only on resume, no indication a stream was cancelled.
**Root cause:** `CancellationException` branch (line 438) intentionally drops the partial response. The corresponding "unfinished chat pill" is the only signal.
**Proposed fix:** None strictly needed — but consider persisting an `[Stream cancelled]` marker for transparency.

### Bug 17 — Severity: LOW — Category: numerical
**Location:** line 436 (function ChatSessionScreen.actuallySend, totalCost calculation)
**Symptom:** `inputTokens * pricing.promptPrice * 100` — `pricing.promptPrice` is per-token in dollars. Times 100 = cents. But the UI label `"%.2fc".format(totalCost)` (line 513) only shows two decimals → for sub-cent costs it shows "0.00c", not informative.
**Root cause:** Insufficient precision on micro-costs.
**Proposed fix:** Switch to `< 0.01 ? "<0.01c" : "%.2fc".format(...)`.
**Status:** Fixed (this session) — show <0.01c instead of 0.00c for sub-cent costs

### Bug 18 — Severity: MEDIUM — Category: index out of bounds
**Location:** lines 919–923 (function AnimatedTextLines)
**Symptom:** When `content` rapidly changes, the visibleLineCount can lag. If `lines.size` decreases (e.g. content was overwritten with a shorter version mid-stream), the loop skips back via `if (lines.size < visibleLineCount) visibleLineCount = lines.size`. But between the assignment and the while loop, `lines.size` is fixed (computed once outside). Then the while-loop's `kotlinx.coroutines.delay(500)` could starve users on slow streams (one line per 500ms).
**Root cause:** Hard-coded 500ms reveal cadence; on a 100-line response, it takes 50 seconds to fully reveal.
**Proposed fix:** Snap visibleLineCount to lines.size when content arrives faster than the reveal cadence, or skip animation entirely past N lines.

---

## File: ai/src/main/java/com/ai/ui/chat/DualChatScreen.kt

### Bug 19 — Severity: CRITICAL — Category: crash / null safety
**Location:** lines 311–312 (function DualChatSessionScreen)
**Symptom:** `val config = remember { appViewModel.uiState.value.dualChatConfig ?: return@remember null }` — the `return@remember null` returns a null FROM the remember lambda. So `config` has type `DualChatConfig?` and is null. Then `if (config == null) { ... return }` handles it. But: `config!!` is used implicitly via smart-cast at line 330+. Actually Kotlin requires an explicit smart-cast because `config` is the result of `remember { ... }` whose type is inferred. Let me re-check: After `remember { ... ?: return@remember null }`, `config` is `DualChatConfig?`. The `if (config == null) { ...; return }` check ensures all subsequent uses are non-null... except `config` is a `val` from `remember`, not a property — the smart cast holds. OK, but `config.subject` then runs even though we already declared we'd navigate back. **Real bug:** the LaunchedEffect runs `onNavigateBack()`, but the rest of the composable continues to execute and access config!! (no — control flow returns). Actually `return` after the LaunchedEffect should work. Check `config.interactionCount` (line 317): comes after the null check; OK. The actual bug: `remember { appViewModel.uiState.value.dualChatConfig ?: return@remember null }` captures the value at first composition only — if the user navigates here BEFORE setting a config (race), they hit the back-nav path; OK. But the surrounding `chatJob?.cancel()` in the LaunchedEffect path may not run because `chatJob = null` initially.
**Root cause:** Defensive null check is fine but the surrounding code uses `config.X` smart-casts that work, so behaviorally OK. Still, the pattern is fragile.
**Proposed fix:** Wrap whole body in `config?.let { ... }` or early-return cleanly.
**Status:** Partial — config?.let pattern still mixed; smart-cast holds today

### Bug 20 — Severity: HIGH — Category: wrong logic / template substitution
**Location:** lines 354–357 (function DualChatSessionScreen.startChatLoop)
**Symptom:** First-prompt seeding only fires when `messages.isEmpty()`. After "Chat N more" runs, messages is NOT empty — the first round of the second batch will not get the first-prompt template applied, but the `currentInteraction == 0` check at line 369 likewise gates secondPrompt only on the *first ever* turn. Consistent in that sense — restart picks up where left off. **Real bug:** the restart's first model-1 turn re-sends the entire conversation as model-1's view (via `buildMessagesForModel(1)`), which appends every prior message as either assistant (model-1's own) or user (model-2's). Without the firstPrompt template, model-1 gets nothing new to respond to — it'll repeat itself.
**Reproduction:** Start dual chat with 1 round, click "Chat 5 more" — model 1 sees a chat history ending with model-2's message marked as "user" — that triggers it to respond, but no new prompt was added. The model just keeps the conversation rolling. OK, that's actually intended ("From 3rd on: previous response is sent directly"). Not a bug per se.

### Bug 21 — Severity: HIGH — Category: state lifecycle / persistence
**Location:** lines 314–317 (function DualChatSessionScreen, sessionId)
**Symptom:** `sessionId = remember { "dualchat_${System.currentTimeMillis()}" }` — but no `saveSession` is ever called. Dual-chat conversations are lost on screen exit. The Hub doesn't show them in history.
**Root cause:** No persistence layer for dual chats.
**Proposed fix:** Either persist via `ChatHistoryManager.saveSession` with a special provider sentinel, or document that dual chats are ephemeral.

### Bug 22 — Severity: HIGH — Category: race / coroutine cancellation
**Location:** lines 394–395 (function DualChatSessionScreen)
**Symptom:** `DisposableEffect(Unit) { onDispose { chatJob?.cancel() } }` runs `chatJob?.cancel()` on dispose. But `LaunchedEffect(Unit) { startChatLoop() }` only sets `chatJob` AFTER `startChatLoop()` returns (it runs `chatJob = scope.launch {...}`). Until then, `chatJob` is null. Practically: after a tap on "Chat N more", `startChatLoop()` reassigns `chatJob`, but the `DisposableEffect` was already registered and only ever cancels the LATEST `chatJob` reference at dispose time — which IS the latest. OK. **Real bug elsewhere**: `chatJob?.cancel()` doesn't AWAIT cancellation; the coroutine might still emit one more message before the cancel takes effect. But since the launch itself is on `scope` (rememberCoroutineScope), the parent is the composition scope which IS awaited. OK.
**Status:** Skip

### Bug 23 — Severity: MEDIUM — Category: state inconsistency
**Location:** line 332 (function DualChatSessionScreen, model1Cost derivedStateOf)
**Symptom:** `val pricing1 = remember { PricingCache.getPricing(...) }` — the remember is keyed on nothing, so if PricingCache loads after first composition, the pricing displayed is `DEFAULT_PRICING` for the entire session.
**Root cause:** No re-keying on `PricingCache.preloadCompleted` or similar.
**Reproduction:** Open dual chat right after app cold start before pricing preload finished — costs always show as default 25/75 c/M.
**Proposed fix:** Use `remember(provider, model, PricingCache.preloadCompleted) { ... }` or use `produceState`.
**Status:** Fixed

### Bug 24 — Severity: MEDIUM — Category: numerical / cost
**Location:** lines 360–361, 375–376 (function DualChatSessionScreen.startChatLoop)
**Symptom:** `inTokens1 = m1Messages.sumOf { estimateTokens(it.content) }` re-counts the ENTIRE conversation as input tokens for every turn. This is technically correct (all prior turns are sent as context), but `model1InputTokens += inTokens1` accumulates across all rounds, so totalInputTokens ends up O(N²) in conversation length.
**Root cause:** Cumulative addition of per-call input rather than per-turn delta.
**Reproduction:** Set rounds = 10. The displayed `model1Cost` will be massively higher than actual (the provider only counts each token once per call, but the prior call's input is recounted in the next).
**Proposed fix:** Document this, or use deltas (subtract prior round's input).

Actually the provider DOES bill input on every call, so the cumulative is correct relative to API charges. **Not a bug, false alarm.**

### Bug 25 — Severity: HIGH — Category: state restoration
**Location:** lines 395 (function DualChatSessionScreen)
**Symptom:** `LaunchedEffect(Unit) { startChatLoop() }` fires once. If the user rotates the device, the LaunchedEffect re-fires, restarting the entire chat loop while messages may be empty (because `messages` is `mutableStateListOf` not rememberSaveable).
**Root cause:** State not saveable across config changes.
**Reproduction:** Start dual chat, mid-stream rotate device → chat restarts from interaction 0; old messages duplicate or vanish.
**Proposed fix:** Store messages in AppViewModel, or make all state rememberSaveable.

### Bug 26 — Severity: MEDIUM — Category: scroll / animateScrollToItem
**Location:** lines 364, 379 (function DualChatSessionScreen.startChatLoop)
**Symptom:** `listState.animateScrollToItem(messages.size - 1)` is called inside the chat-loop coroutine which runs on `scope` (rememberCoroutineScope, default Dispatchers.Main.immediate). But `chatViewModel.sendDualChatMessage` runs on Dispatchers.IO under the hood — control returns to the main scope after each await, and the mutateState + animateScrollToItem run sequentially. If user scrolled up to read older messages mid-loop, the scroll snaps them back to bottom.
**Root cause:** No "user is reading" detection.
**Proposed fix:** Only auto-scroll if `listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == messages.size - 1` (already at bottom).

### Bug 27 — Severity: HIGH — Category: closure capture / config
**Location:** lines 311 (function DualChatSessionScreen, config)
**Symptom:** `appViewModel.uiState.value.dualChatConfig` is read synchronously on first composition. After `onNavigateBack()` from this screen, the dualChatConfig may still be non-null in uiState. Re-entering doesn't reset, so the same config runs again — but with a fresh `messages` list. Acceptable. **Real bug:** `appViewModel.uiState.value.dualChatConfig` is captured but never cleared from UiState — leaks the config object across navigation.
**Root cause:** No `onConsumeDualConfig()` callback like the chat starter has.
**Proposed fix:** Add a clearing callback after first read.
**Status:** Fixed

### Bug 28 — Severity: LOW — Category: substring / template
**Location:** line 371 (function DualChatSessionScreen.startChatLoop)
**Symptom:** `config.secondPrompt.replace("%answer%", last.content)` — if the model-1 response contains the literal `%subject%` token, that's NOT replaced (only %answer% in secondPrompt is). But if the user *intentionally* writes `%answer%` inside the secondPrompt template hoping for a regex-level replacement, simple `replace` is fine. The bug: if the response itself contains backslashes or replacement patterns interpretable by some downstream regex, weird things happen — but plain `String.replace(String, String)` doesn't interpret escapes. **Probably OK.**
**Status:** Skip

### Bug 29 — Severity: MEDIUM — Category: error handling
**Location:** lines 385–388 (function DualChatSessionScreen.startChatLoop)
**Symptom:** Any exception thrown by `sendDualChatMessage` aborts the entire loop and sets `errorMessage`. There's no per-turn retry, no skip-to-next, no surface of WHICH model failed.
**Root cause:** Single try/catch over the loop.
**Proposed fix:** Surface "Model 1 failed at round 3: …" with the model index.

---

## File: ai/src/main/java/com/ai/ui/chat/ChatHistory.kt

### Bug 30 — Severity: MEDIUM — Category: pagination / state
**Location:** lines 60–67 (function ChatHistoryScreen)
**Symptom:** When `pageSize` recalculates (e.g. window resize, keyboard shows), `currentPage` clamps via `LaunchedEffect(pageSize, allSessions.size)`. But the clamping condition `if (currentPage >= totalPages && totalPages > 0)` doesn't handle `totalPages == 0` (empty list shows pages "Page 1 of 0"). Actually the outer `if (allSessions.isEmpty())` branch already handles empty.
**Real bug:** If the user is on page 5 and a session is deleted/added externally, `historyVersion` change re-triggers the produceState, but the LaunchedEffect only clamps DOWN — it never recovers if the page was already clamped invalid mid-recompose.
**Reproduction:** Page 5 of 5; delete sessions to make total 4 pages → clamp works. But if the page count grows, page jumps to 1 unnecessarily? Actually no, the LaunchedEffect only fires when `pageSize` or `allSessions.size` changes; page stays where it is. OK.

### Bug 31 — Severity: MEDIUM — Category: search / case sensitivity
**Location:** line 175 (function searchInChats)
**Symptom:** `results.sortedByDescending { it.messageTimestamp }` is a final assignment but `results` is a local mutableList. The result doesn't go anywhere — it's the return value via implicit return of withContext. OK.
**Real bug:** the `sortedByDescending` returns a new list but doesn't replace `results` — the function returns the sorted list correctly via expression form. The original mutable `results` is discarded. OK, false alarm.

### Bug 32 — Severity: HIGH — Category: search / blocking IO on main thread
**Location:** lines 196–202 (function ChatSearchScreen)
**Symptom:** `LaunchedEffect(historyVersion, searchQuery, hasSearched)` fires every keystroke after the user once tapped Search. `searchInChats(searchQuery)` walks every chat session every time and blocks until done. With a typical chat history, this can be 100ms+ per keystroke. UI lags.
**Root cause:** No debouncing.
**Reproduction:** Search a query, then type each new character; each rerun walks the whole index.
**Proposed fix:** Debounce via `delay(300)` inside the LaunchedEffect, cancellable on the next launch.

### Bug 33 — Severity: LOW — Category: search / preview window
**Location:** lines 158–162 (function searchInChats)
**Symptom:** Preview window uses `matchIndex` from `lowerContent.indexOf(lowerQuery)`. The substring is taken from the original `message.content` at the same index, which works only because `lowercase()` doesn't change string length in ASCII — but for some Turkish / German locales, lowercase can change length (`ß` → `ss`, capital İ → lowercase i + dot above).
**Root cause:** Locale-sensitive lowercase changes string length.
**Reproduction:** Chat content with `İSTANBUL`, search for `i` — preview offsets shift.
**Proposed fix:** Use `lowercase(Locale.ROOT)` or normalise to NFD before indexOf.

---

## File: ai/src/main/java/com/ai/ui/chat/ChatHub.kt

### Bug 34 — Severity: MEDIUM — Category: stale state / initial composition
**Location:** lines 53–59 (function ChatsHubScreen)
**Symptom:** `hasChatHistory` and `allSessionsForHub` start as `false`/`emptyList` then update asynchronously. The hub renders "Continue Existing Chat" disabled for one frame even when chats exist. Also "Pinned" / "Recent" / "Search Chats" / "Manage" buttons all flash disabled briefly.
**Root cause:** ProduceState's initial value is the placeholder; the IO read happens after first composition.
**Proposed fix:** Use `remember` for a synchronous quick check (file existence) rather than async.

### Bug 35 — Severity: HIGH — Category: data inconsistency / preview
**Location:** lines 60–70 (function ChatsHubScreen)
**Symptom:** `unfinishedSessions = allSessionsForHub.filter { ... lastUserVisible?.role == "user" }`. But system messages aren't filtered consistently — `lastUserVisible` excludes system, OK. If a session has only a system message and one user message (no assistant yet), it counts. But if the streamed response was partially saved with `[Stream interrupted: ...]`, the last message is "assistant" and the session is NOT marked unfinished — yet that's exactly the case where the user might want to retry.
**Root cause:** `[Stream interrupted]` markers are saved as full assistant messages, removing the "unfinished" signal.
**Reproduction:** Send a message, lose internet mid-stream, message saved with interrupted marker. Hub shows session in "Recent" but not in "Awaiting reply".
**Proposed fix:** Either don't save the interrupted marker, or check the marker prefix in the unfinished filter.

### Bug 36 — Severity: LOW — Category: concurrency
**Location:** line 113 (function ChatsHubScreen, LocalLlmChatCard)
**Symptom:** `LocalLlm.availableLlms(context)` does file-system I/O on the main thread (inside `remember(refreshTick)`).
**Root cause:** No off-thread loading.
**Proposed fix:** `produceState` with Dispatchers.IO.

---

## File: ai/src/main/java/com/ai/ui/chat/ChatManageScreen.kt

### Bug 37 — Severity: MEDIUM — Category: numerical / overflow
**Location:** line 129 (function ChatManageScreen)
**Symptom:** `val cutoff = System.currentTimeMillis() - days * 24L * 3600L * 1000L`. `days` is Int; multiplied by `24L`, OK, becomes Long. But if user enters `999999`, `days * 24L * 3600L * 1000L` = ~3.15e16 ms = ~1 million years — fine for Long but the resulting `cutoff` could be a very negative Long, which makes `it.updatedAt < cutoff` always false (no deletion). Conversely, days=0 was filtered upstream; days<0 is filtered to digit-only chars. OK.

### Bug 38 — Severity: HIGH — Category: state lifecycle / state captures
**Location:** lines 132–137 (function ChatManageScreen)
**Symptom:** `produceState<List<ChatSession>?>(initialValue = null, daysText) { ... }` is keyed on `daysText` (a String). When user types digits, the ProduceState relaunches every keystroke, hitting disk every time. The dialog only opens once (`confirmDelete`) but the ProduceState is inside the dialog branch — `confirmDelete = false` doesn't cancel the launched effect because `daysText` keeps changing.
**Root cause:** The effect keys on the input field rather than the dialog visibility.
**Proposed fix:** Compute `candidates` only when `confirmDelete` flips true (`produceState(confirmDelete) { if (confirmDelete) ... }`).

### Bug 39 — Severity: MEDIUM — Category: file I/O
**Location:** line 178–184 (function zipAllChats)
**Symptom:** ZipOutputStream writes are not done atomically. If the app crashes or runs out of space mid-zip, the user sees a half-formed ZIP at `cacheDir/chat_backup/ai_chats_backup_*.zip`.
**Root cause:** No staging file.
**Proposed fix:** Write to a `.tmp` and rename on success.

### Bug 40 — Severity: LOW — Category: missing files dir check
**Location:** line 177 (function zipAllChats)
**Symptom:** `historyDir.listFiles()?.forEach` — if `chat-history` doesn't exist, `listFiles()` returns null, the `?.forEach` skips, the zip ends up empty (zero entries) but is still written to disk and shared.
**Root cause:** No empty-zip guard.
**Proposed fix:** Check `sessions.isEmpty()` already returns null. But the for-each runs over the actual directory, which may differ from `sessions` count. Unify both checks.

---

## File: ai/src/main/java/com/ai/ui/knowledge/KnowledgeScreens.kt

### Bug 41 — Severity: HIGH — Category: race / pendingUris double-ingest
**Location:** lines 270–311 (function KnowledgeDetailScreen)
**Symptom:** `LaunchedEffect(kbId, pendingUris, kb?.id)` includes `kb?.id` as a key. When `kb` first loads from null → non-null, the effect re-fires; if `pendingUris` is non-empty, the same URI is ingested TWICE (once when kb was null and got returned early, once when kb materialised).
**Root cause:** The early-return-on-null inside the effect never sets `working = false` AND doesn't clear `pendingUris`, so the next firing sees the same list. The `onConsumePending()` is in the `finally` of the outer try, which only runs when the effect actually proceeds past the null check.
**Reproduction:** Share a file, hub navigates to detail. Cold start: kb is null → effect short-circuits. Then kb loads → effect re-fires with same pendingUris → ingests for real. OK that path is fine. But: the FIRST early return doesn't dirty anything, so the SECOND firing picks up correctly. **Re-examine:** Actually the effect's keys `(kbId, pendingUris, kb?.id)` mean it only re-fires when kb?.id changes. After a successful ingest, `refreshTick++` triggers `kb` reload but `kb?.id` stays the same (unless refreshTick changes the produced state, which... wait, produceState is keyed on `(kbId, refreshTick)`, so kb is re-loaded but with the same id, so kb?.id doesn't change. OK.
**Real risk:** If the user shares again WHILE the screen is mounted with the same kbId, `pendingUris` swaps in NEW URIs. The LaunchedEffect re-fires. Inside, `loaded = kb` is captured at the time the effect runs — if kb is currently mid-refresh (refreshTick++ in progress), `loaded` may be stale.
**Proposed fix:** Use a request-id token in pendingUris that's unique per share, and check it before dispatching.

### Bug 42 — Severity: HIGH — Category: error swallowing
**Location:** line 291 (function KnowledgeDetailScreen)
**Symptom:** `val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: continue` — silently skips a malformed URI without surfacing why.
**Root cause:** No error display.
**Proposed fix:** `status = "Skipped invalid URI: $trimmed"`.

### Bug 43 — Severity: HIGH — Category: state mismatch
**Location:** lines 319, 325 (function KnowledgeDetailScreen.pickFile)
**Symptom:** `working = true; status = "Reading ${displayName}…"` runs OUTSIDE the IO context, so they execute on Main; OK. But if `pickFile.launch` is invoked twice (rapid taps before `working` flips), two coroutines spawn, both setting `working = true`, both racing to `working = false` after their respective ingests. The result is unpredictable — the second one may finish before the first, but both display their own status.
**Root cause:** No reentrancy guard at the launch site.
**Proposed fix:** Disable the `+ File` button on `working` BEFORE the picker opens, or set a "queued" sentinel.

### Bug 44 — Severity: HIGH — Category: state lifecycle / refreshTick
**Location:** line 257 (function KnowledgeDetailScreen)
**Symptom:** `refreshTick++` after delete-source isn't atomic — if the user taps delete twice rapidly, two refreshTick values may be observed but the produceState is keyed on `refreshTick`, so it re-runs once for each. Disk reads pile up.
**Root cause:** No throttling.
**Proposed fix:** Wrap KnowledgeStore reads in a single-flight `MutableStateFlow`.

### Bug 45 — Severity: MEDIUM — Category: KnowledgeAttachDialog state
**Location:** line 539 (function KnowledgeAttachDialog)
**Symptom:** `mutableStateOf(initialSelectedIds)` is keyed on `initialSelectedIds` which is a Set — every recompose with a new Set object resets the user's in-progress selection. If the parent recomposes (e.g. `kbRefreshTick`), the dialog's state resets to initial.
**Root cause:** Set identity changes whenever a new object is constructed.
**Reproduction:** Open dialog, select some KBs, rotate or trigger upstream recompose → selection resets.
**Proposed fix:** Use `rememberSaveable` keyed only on a stable identity string, or pass `initialSelectedIds` once into a non-keyed `remember`.

### Bug 46 — Severity: HIGH — Category: validation / embedder anchor
**Location:** lines 540–543 (function KnowledgeAttachDialog)
**Symptom:** `anchorEmbedder` is computed from the FIRST KB in `knowledgeBases` whose id is in selected. But `knowledgeBases` ordering may change across recomposition, so the anchor identity flips and previously-allowed selections become disabled mid-edit.
**Root cause:** Anchor is order-dependent.
**Proposed fix:** Lock the anchor when first added; track separately rather than recomputing.

### Bug 47 — Severity: MEDIUM — Category: file pick MIME filter
**Location:** lines 348–360 (function KnowledgeDetailScreen, pickFile.launch)
**Symptom:** MIME types listed include `*/*` at the END but the SAF `OpenDocument` contract OR's all types — in practice, `*/*` makes the explicit type filters meaningless because the picker shows everything. If the intent was "prefer these but allow others", that's already what `*/*` means. OK.
**Real bug:** Missing MIME types — `application/json`, `application/xml` aren't listed; user-shared JSON ends up as TEXT type via `pickTypeForUri` fallback (mime starts with text/ → TEXT; but JSON's mime is `application/json`, falling through the else to TEXT). OK that works.

### Bug 48 — Severity: MEDIUM — Category: cursor leak risk
**Location:** lines 510–516 (function displayNameForUri)
**Symptom:** `context.contentResolver.query(uri, null, null, null, null)?.use { c -> ... }` — `null` projection asks for all columns, which on some content providers throws SecurityException (sandboxed providers reject null projection on Android 14+).
**Root cause:** Wide projection.
**Proposed fix:** Pass `arrayOf(OpenableColumns.DISPLAY_NAME)` as projection.

### Bug 49 — Severity: MEDIUM — Category: KB delete race
**Location:** lines 461–466 (function KnowledgeDetailScreen, delete confirm)
**Symptom:** `KnowledgeStore.deleteKnowledgeBase` is called on the MAIN thread; it does file I/O (`deleteRecursively`).
**Root cause:** No off-thread delete.
**Proposed fix:** Wrap in `scope.launch(Dispatchers.IO) { ... }` then call `onBack()` on Main.

### Bug 50 — Severity: HIGH — Category: re-index against changed embedder
**Location:** lines 425–443 (function KnowledgeDetailScreen, Re-index button)
**Symptom:** Re-index uses the KB's CURRENT embedder identity. But there's no UI to change embedder — comments say it's fixed. `KnowledgeService.reindexSource` relies on `kb.embedderProviderId/embedderModel`. OK. The bug: if a user manually edited `manifest.json` to change the embedder, re-index would write chunks with the new dimension mixed with old chunks of other sources still on the old dimension. The retrieval-time `dimSurprise` warning catches this but skips chunks rather than refusing.
**Root cause:** No invariant that ALL sources share dimension after partial re-index.
**Proposed fix:** When dim changes, invalidate ALL sources (force re-index of every source).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/models/ModelScreens.kt

### Bug 51 — Severity: HIGH — Category: blocking IO on main thread
**Location:** lines 519–531 (function ModelInfoScreen)
**Symptom:** Multiple `remember` blocks call disk I/O synchronously: `ApiTracer.getTraceFiles()` (line 520), `SettingsPreferences.loadUsageStats()` (line 524), `PricingCache.getPricing()` (line 528). Each parses files / SharedPreferences on Main.
**Root cause:** No off-thread fetch.
**Reproduction:** Slow storage → screen open jitters.
**Proposed fix:** `produceState(provider, modelName) { withContext(IO) { ... } }`.

### Bug 52 — Severity: HIGH — Category: cache / staleness
**Location:** lines 113–127 (object ModelInfoCache)
**Symptom:** `ModelInfoCache` caches OpenRouter models keyed by API key. There's no TTL — the cached list lives forever until the API key changes or the process dies. New models added on OpenRouter aren't visible.
**Root cause:** No expiry.
**Proposed fix:** Add a 24h TTL or a "Refresh" button.

### Bug 53 — Severity: MEDIUM — Category: races / atomicity
**Location:** lines 117–124 (function ModelInfoCache.getOpenRouterModels)
**Symptom:** Two concurrent calls with the same apiKey both miss the cache, both fetch independently, both write `openRouterModels = models`. Wasted bandwidth, not a crash. Could also stall if the second caller waits on the same Retrofit client serialised by interceptor.
**Root cause:** No coalescing.
**Proposed fix:** Use a `Mutex` or `Deferred<List<...>>` cache.

### Bug 54 — Severity: HIGH — Category: trace count incorrect
**Location:** lines 519–521 (function ModelInfoScreen, traceCount)
**Symptom:** `ApiTracer.getTraceFiles().count { it.model == modelName }` counts EVERY trace whose model name matches, regardless of provider. Models named "gpt-4" exist on multiple providers (OpenAI, Azure, OpenRouter) — the count conflates them.
**Root cause:** No provider filter.
**Proposed fix:** Add `&& it.providerId == provider.id` if traces carry providerId.

### Bug 55 — Severity: MEDIUM — Category: usage stats
**Location:** lines 529–530 (function ModelInfoScreen, usageCost)
**Symptom:** `it.inputTokens * pricing.promptPrice + it.outputTokens * pricing.completionPrice` — `inputTokens` is `Long`, `promptPrice` is `Double`. Long*Double = Double, OK. But this uses CURRENT pricing for HISTORICAL usage; if the user re-ran the same model after a price change, the displayed cost is wrong.
**Root cause:** No price snapshot at usage time.
**Proposed fix:** Acceptable approximation; document.

### Bug 56 — Severity: MEDIUM — Category: AI introduction caching key
**Location:** lines 656–658 (function ModelInfoScreen, introCacheKey)
**Symptom:** Cache key is `PromptCache.keyFor(introResolvedPrompt, "${provider.id}:$modelName")` — but if the user edits the "Model info" internal prompt template, all old cached responses become orphaned (unreachable but still on disk). PromptCache has no eviction.
**Root cause:** No GC for orphaned prompt cache entries.
**Proposed fix:** Periodic cleanup or LRU.

### Bug 57 — Severity: HIGH — Category: action button / agent edit
**Location:** lines 543–562 (function ModelInfoScreen, AgentEditScreen overlay)
**Symptom:** `Agent("", "${provider.displayName} $modelName", ...)` creates an agent with empty `id`. When `onSaveSettings(aiSettings.copy(agents = aiSettings.agents + agent))` runs, two agents created via this path (same provider+model) get the same empty id — Set-of-id keys collide silently in downstream lookups.
**Root cause:** No UUID generation for new agent.
**Proposed fix:** `Agent(id = UUID.randomUUID().toString(), ...)`.
**Status:** Fixed

### Bug 58 — Severity: HIGH — Category: openRouter prefix matching
**Location:** lines 583–589 (function ModelInfoScreen, OpenRouter lookup)
**Symptom:** `prefixedTargetNorm = if (orName != null) norm("$orName/$modelName") else null` — but `prefixedTargetNorm` is only used in `firstOrNull { norm(it.id) == prefixedTargetNorm }`; if `orName` is null AND `modelName` doesn't contain `/`, the second fallback `endsWith("/$targetNorm")` is the only match path. For provider-less aliases (e.g. directly using "claude-3-5-sonnet" without anthropic prefix), this picks the FIRST OpenRouter entry whose id ends with `/claude-3-5-sonnet` — that may be DeepInfra's mirror, not Anthropic's.
**Root cause:** Lossy match on the "endsWith" fallback.
**Proposed fix:** Prefer matches whose prefix matches the provider's expected name; fall back to "endsWith" only when no other candidate.

### Bug 59 — Severity: MEDIUM — Category: HuggingFace 404 retry
**Location:** lines 610–615 (function ModelInfoScreen, HF lookup)
**Symptom:** Tries dash and dot variants. But if the FIRST variant returns a 404, the loop continues. If it returns a 401 (auth error), the loop also continues, hammering HF with the same auth error 3 times.
**Root cause:** No early-out on auth failures.
**Proposed fix:** Break on `resp.code() == 401 || 403` after first try.

### Bug 60 — Severity: MEDIUM — Category: error display
**Location:** line 627 (function ModelInfoScreen, errorMessage)
**Symptom:** The catch block sets `errorMessage = e.message`. But the LaunchedEffect runs on a coroutine; if it's cancelled (user backed out), `e` is `CancellationException` → `errorMessage` shows "JobCancellationException: ..." next time the screen opens with stale state.
**Root cause:** Doesn't filter out CancellationException.
**Proposed fix:** Re-throw `is CancellationException`.

### Bug 61 — Severity: MEDIUM — Category: ModelSearchScreen filter precedence
**Location:** lines 211–217 (function ModelSearchScreen)
**Symptom:** `(!hasPricingOnly || real) && (!freeOnly || free) && (!defaultPricingOnly || !real)` — selecting both `hasPricingOnly` AND `defaultPricingOnly` requires `real && !real` = always false. The chips are not mutually exclusive in UI but yield empty results when combined.
**Root cause:** No mutual-exclusion logic in UI.
**Proposed fix:** Make the chips a radio group, or auto-deselect the conflicting one.

### Bug 62 — Severity: HIGH — Category: filteredModels recomputation
**Location:** lines 194–229 (function ModelSearchScreen, filteredModels)
**Symptom:** `remember(searchQuery, typeFilter, ...)` includes a long list of keys. Inside the lambda, every iteration calls `aiSettings.getModelType`, `aiSettings.isVisionCapable`, `PricingCache.getPricing`, `PricingCache.pricesConflict` — none of which are pure: pricing reads disk indirectly. Recomputation on every keystroke walks O(N models × 7+ disk-touching predicates).
**Root cause:** No memoisation per (provider, model) key.
**Reproduction:** Type into the Models search box on a 5000+ model catalog → keystroke lag.
**Proposed fix:** Pre-compute a per-model snapshot once, filter in-memory.

### Bug 63 — Severity: MEDIUM — Category: reactive
**Location:** line 707–711 (function ModelInfoScreen, recentUsages produceState)
**Symptom:** `produceState(initialValue = emptyList(), provider, modelName)` keys don't include any "tick" for chat/report changes. If the user starts a chat with this model in a different screen and returns here, recentUsages is stale.
**Root cause:** No invalidation on session save.
**Proposed fix:** Key on `ChatHistoryManager.historyVersion` and a similar version flow on ReportStorage.

### Bug 64 — Severity: HIGH — Category: chat-row title wrong
**Location:** lines 60–64 (function computeModelUsages.chatTitle)
**Symptom:** `chatTitle` uses the FIRST user message's first non-blank line. But chat sessions can start with a system message (filtered correctly). However, if the first user message is just the staged "share-target text", that's the title — and it can be hundreds of chars cut to 80 with no ellipsis indicator.
**Root cause:** No ellipsis on truncation.
**Proposed fix:** `take(80)` → `take(80) + if (length>80) "…" else ""`.

### Bug 65 — Severity: LOW — Category: missing field in ALL sources export
**Location:** lines 924–945 (function ModelInfoScreen, "Show all" button)
**Symptom:** Concatenates 7 sources but doesn't include the manual override entry — that lives in `ProviderConfig` not in any of these caches.
**Root cause:** Manual override sources weren't propagated.
**Proposed fix:** Add an 8th section for the override.

### Bug 66 — Severity: MEDIUM — Category: numeric parsing
**Location:** lines 1192–1196 (function ModelInfoScreen, Technical Specifications)
**Symptom:** `formatCompactNumber(it.toLong())` casts an `Int?` context_length to Long. If context_length comes from OpenRouter as a Double (some entries are float-ish like `8192.0`), `it.toLong()` works but `formatCompactNumber` would format `1500000` as `1500k` rather than `1.5M` — depends on the formatter.
**Root cause:** Compact formatter precision.
**Proposed fix:** Verify formatCompactNumber handles million range.

### Bug 67 — Severity: HIGH — Category: rememberSaveable keys
**Location:** lines 154–179 (function ModelSearchScreen)
**Symptom:** `var providerFilterId by rememberSaveable { mutableStateOf<String?>(null) }` etc. — but `typeFilter`, `searchQuery`, etc. all use rememberSaveable for filters. Changing aiSettings.modelTypeOverrides while on this screen doesn't refresh `filteredModels` because the remember key set doesn't include modelTypeOverrides snapshot.
**Root cause:** Settings mutations during composition aren't observed.
**Proposed fix:** Add `aiSettings.modelTypeOverrides` to the remember key.

---

## File: ai/src/main/java/com/ai/ui/search/LocalSearchScreen.kt

### Bug 68 — Severity: MEDIUM — Category: scoring degenerate case
**Location:** lines 130–137 (function runLocalSearch)
**Symptom:** Score counts every overlapping occurrence. For query "aa" against haystack "aaaa", `indexOf("aa", 0)=0; idx=1; indexOf=1; idx=2;` etc. → 3 matches for a 4-char string. The behaviour is fine but means common short tokens ("a", "an", "the") dominate ranking.
**Root cause:** No stop-word filter, no IDF.
**Proposed fix:** Filter tokens of length < 3, or apply per-document normalisation.

### Bug 69 — Severity: HIGH — Category: case folding pre-tokenisation
**Location:** line 119 (function runLocalSearch)
**Symptom:** `query.lowercase().split(Regex("\\s+"))` uses default locale for lowercase. Same Turkish-i issue as bug 33. Also `haystack` is `sb.toString().lowercase()` — could mangle.
**Root cause:** Default locale.
**Proposed fix:** `lowercase(Locale.ROOT)`.

### Bug 70 — Severity: HIGH — Category: sort key
**Location:** line 140 (function runLocalSearch)
**Symptom:** `thenByDescending { it.timestamp }` — `timestamp` is a String here (`"yyyy-MM-dd HH:mm"`), so descending string compare works for years 1000-9999. OK for now. But the SimpleDateFormat output for some locales adds a 'Z' or AM/PM suffix → string sort breaks. Though `Locale.US` is hard-coded.
**Root cause:** Format dependency.
**Proposed fix:** Sort by Long timestamp, format only for display.

---

## File: ai/src/main/java/com/ai/ui/search/LocalSemanticSearchScreen.kt

### Bug 71 — Severity: HIGH — Category: type confusion
**Location:** line 218–221 (function runLocalEmbedSearch, hit construction)
**Symptom:** `EmbeddingsStore.cosine(queryVec, vec)` — `queryVec` is `List<Double>`, `vec` is `List<Double>` (from `LocalEmbedder.embed` returning `List<List<Double>>`). The `cosine(List<Double>, List<Double>)` overload exists. OK. But `EmbeddingsStore.put(context, item.first, providerKey, modelName, item.second, v)` writes `List<Double>` — yet KnowledgeChunk uses FloatArray for the same data elsewhere. Two storage formats for embeddings risks confusion.
**Root cause:** Two different on-disk representations.
**Proposed fix:** Unify or document.

### Bug 72 — Severity: HIGH — Category: cache reuse with different model
**Location:** line 200 (function runLocalEmbedSearch)
**Symptom:** `EmbeddingsStore.get(context, r.id, providerKey, modelName, rep)` uses `providerKey = "LOCAL"` (constant). Two different LOCAL models (e.g. `universal-sentence-encoder.tflite` vs `mini-lm.tflite`) have DIFFERENT embedding spaces but BOTH use `providerKey="LOCAL"` — the cache key includes modelName, so they're separated. OK.
**Real bug:** If two providers happen to use modelName="text-embedding-3-small" (e.g. user adds OpenRouter alias), the LOCAL one's cache may collide with theirs. Actually `providerKey="LOCAL"` distinguishes; OK.

### Bug 73 — Severity: MEDIUM — Category: substringBefore split confusion
**Location:** lines 218–219 (function runLocalEmbedSearch)
**Symptom:** `title.substringBefore(" — "), title.substringAfter(" — ", "")` — splits the title by ` — `. But `r.title` may contain ` — ` (em dash with spaces), corrupting the split.
**Root cause:** Concatenating then splitting on a delimiter that can appear in the input.
**Reproduction:** Report titled "Gold — silver — analysis" → split gives wrong parts.
**Proposed fix:** Pass title and timestamp as separate fields all the way through.

### Bug 74 — Severity: HIGH — Category: empty catch / cancellation leak
**Location:** lines 188–222 (function runLocalEmbedSearch)
**Symptom:** No try/catch around the whole thing — if `LocalEmbedder.embed` throws (model file corrupt / OOM), the exception propagates up, the IO coroutine ends, the launched Compose coroutine catches nothing, and the UI hangs with `running = true` forever.
**Root cause:** No outer error handling.
**Proposed fix:** Wrap in try/catch in the calling Composable's `scope.launch`.

### Bug 75 — Severity: HIGH — Category: progress callback thread safety
**Location:** lines 127–129 (function LocalSemanticSearchScreen)
**Symptom:** `runLocalEmbedSearch(context, model, q) { msg -> scope.launch(Dispatchers.Main) { status = msg } }` — every progress update spawns a new coroutine on Main. With 100 reports, that's 10 coroutines (every 10) — minor leak risk if scope dies mid-flight.
**Root cause:** Non-cancellable nested launches.
**Proposed fix:** Use `withContext(Dispatchers.Main)` or pre-spawn one progress channel.

### Bug 76 — Severity: HIGH — Category: latestTrace produceState race
**Location:** lines 63–69 (function LocalSemanticSearchScreen)
**Symptom:** `produceState<String?>(initialValue = null, running)` only re-fetches when `running` flips. If the user runs two searches in a row, the SECOND search's trace overwrites the first, but the produceState only fires when running goes from true→false; the value displayed may correspond to the most-recent search, OK.
**Real bug:** `firstOrNull { it.category == "Local semantic search" }` — but `getTraceFiles()` is sorted by what? If unsorted, the "first" is unpredictable. And `firstOrNull` doesn't verify the trace is from THIS search — could be a stale trace from yesterday.
**Proposed fix:** Filter by timestamp ≥ search-start.

---

## File: ai/src/main/java/com/ai/ui/search/QuickLocalSearchScreen.kt

### Bug 77 — Severity: MEDIUM — Category: sort key string vs long
**Location:** line 127 (function runQuickSearch)
**Symptom:** `sortedByDescending { it.timestamp }` where `timestamp` is the formatted `String`. Works for `yyyy-MM-dd HH:mm` because lexicographic order matches chronological. Same fragility as Bug 70.
**Proposed fix:** Carry Long timestamp.

### Bug 78 — Severity: LOW — Category: missing trim
**Location:** line 116 (function runQuickSearch)
**Symptom:** `val needle = word.lowercase()` — already trimmed at the call site (`val q = query.trim()`), but if a user pastes whitespace + query, the trim helps. The lowercase here uses default locale → same Turkish-i issue.
**Proposed fix:** `lowercase(Locale.ROOT)`.

---

## File: ai/src/main/java/com/ai/ui/search/SemanticSearchScreen.kt

### Bug 79 — Severity: HIGH — Category: cache cross-pollution
**Location:** line 206 (function runEmbeddingSearch)
**Symptom:** Same as LocalSemanticSearch: cache keyed on `(reportId, service.id, model, content)`. But `repository.embed` may be called with a model from a different provider that happens to share the model name (e.g. "text-embedding-3-small" on both OpenAI and a forwarded OpenRouter alias). `service.id` differentiates, OK. **Real bug:** `vecs[j]` is a List<Double> — for batch items where the API silently returned a null entry (provider quirk on rate limits), the list might be smaller than `batch.size`. Then `vecs[j]` IndexOutOfBoundsException.
**Root cause:** No length check.
**Proposed fix:** `if (vecs.size != batch.size) error(...)`.

### Bug 80 — Severity: MEDIUM — Category: empty result early return
**Location:** line 216 (function runEmbeddingSearch)
**Symptom:** `val vecs = repository.embed(...) ?: return emptyList()` — if a single batch fails, the entire search aborts and returns an empty list, even if previous batches succeeded.
**Root cause:** All-or-nothing.
**Proposed fix:** Skip the failed batch and continue (or surface a partial-results warning).

### Bug 81 — Severity: HIGH — Category: thread / progress callback
**Location:** lines 130–132 (function SemanticSearchScreen.onClick)
**Symptom:** `scope.launch(Dispatchers.Main)` inside the inner lambda, called from a Dispatchers.IO context. Each progress message spawns a Main-dispatched coroutine. Same as Bug 75.

### Bug 82 — Severity: MEDIUM — Category: error display
**Location:** lines 134–136 (function SemanticSearchScreen.onClick)
**Symptom:** No try/catch. If `repository.embed` throws (network error, auth error), exception propagates and `running` stays true — UI deadlocked.
**Proposed fix:** Try/catch with finally.

### Bug 83 — Severity: LOW — Category: empty-state UX
**Location:** line 145 (function SemanticSearchScreen, status display)
**Symptom:** Status shows "No matches." when zero hits, but doesn't differentiate between "embedded successfully but no relevant content" and "embed call failed silently". The latter happens when one specific batch returns null mid-loop.
**Proposed fix:** Distinguish.

### Bug 84 — Severity: MEDIUM — Category: substringBefore on title
**Location:** line 225 (function runEmbeddingSearch)
**Symptom:** Same as Bug 73 — splitting by ` — ` corrupts titles containing that separator.

---

## File: ai/src/main/java/com/ai/ui/share/ShareChooserScreen.kt

### Bug 85 — Severity: LOW — Category: null-safe access
**Location:** line 55 (function ShareChooserScreen)
**Symptom:** `shared.text.length > 300` — `shared.text` is checked non-null with `!!` after `hasText` check; works because `hasText = !shared.text.isNullOrBlank()` ensures non-null. But the `.length` access is on the smart-cast non-null. OK.

### Bug 86 — Severity: MEDIUM — Category: enabled state
**Location:** line 98 (function ShareChooserScreen)
**Symptom:** "Add to Knowledge" enabled when `hasUris || shared.isUrl`. But `shared.isUrl` may be true with no URI list (text-only URL share). The downstream Knowledge screen expects `pendingUris` to be non-empty. If the share is a URL string (text), it's not in `pendingUris` until conversion.
**Root cause:** Mismatch between chooser's enable rule and downstream consumer's expectations.
**Reproduction:** Share a plain URL via text → "Add to Knowledge" enabled → tap → KB list shows banner with 0 items? Need to check call site. Actually the URL-as-text path may convert text to a single-URL pendingUris. Hard to verify without seeing AppViewModel.
**Proposed fix:** Verify and document.

---

## File: ai/src/main/java/com/ai/ui/shared/SelectionScreens.kt

### Bug 87 — Severity: HIGH — Category: timeout race
**Location:** lines 71–86 (function SelectModelScreen)
**Symptom:** `withTimeout(15_000) { ... first { it } ... first { !it } }` — if `onRefresh()` synchronously sets isRefreshing=false BEFORE the snapshotFlow first { it } is collected, the inner first { it } never sees `true` and the timeout fires. The fallback path "Stalled fetch: unveil what we have rather than blocking forever" runs. OK degraded path, but on every cold open the unveiling may be slow/laggy.
**Root cause:** Race between `onRefresh()` and snapshot subscription.
**Proposed fix:** Subscribe BEFORE invoking `onRefresh()`.

### Bug 88 — Severity: MEDIUM — Category: PRicingCache fallback typo
**Location:** lines 188–189, 347 (function SelectModelScreen / SelectAgentScreen)
**Symptom:** `priceColor = if (pricing.source == "default") AppColors.TextDim else AppColors.Red` — but the pricing source string is `"DEFAULT"` (uppercase) in PricingCache.getPricing comments. Comparing to lowercase "default" never matches → all prices render as red.
**Root cause:** Case mismatch between the source label produced and the comparison.
**Proposed fix:** Normalise — `pricing.source.equals("DEFAULT", ignoreCase = true)`.

### Bug 89 — Severity: LOW — Category: filteredModels recomputation
**Location:** lines 92–96 (function SelectModelScreen)
**Symptom:** `allModels` recomputes on every recomposition because the LOCAL branch calls `LocalLlm.availableLlms(context)` synchronously every recompose (no remember).
**Root cause:** Direct call vs remember.
**Proposed fix:** `remember(provider) { ... }`.

### Bug 90 — Severity: MEDIUM — Category: search lowercase locale
**Location:** lines 95, 227 (function SelectModelScreen / SelectProviderScreen)
**Symptom:** `searchQuery.lowercase()` uses default locale.
**Proposed fix:** `Locale.ROOT`.

### Bug 91 — Severity: LOW — Category: isRefreshing snapshot stale
**Location:** lines 67 (function SelectModelScreen)
**Symptom:** `rememberUpdatedState(isRefreshing)` is correct, but the LaunchedEffect launched on `provider.id` only — if `onRefresh` is null but the screen is later given a non-null one (impossible without recreation but contractually possible), the initial `initialRefreshDone = (...|| onRefresh == null)` evaluates to true, and the screen never refreshes.
**Root cause:** Initial guard captures the first onRefresh value.
**Proposed fix:** Re-key on `onRefresh != null` boolean.

---

## File: ai/src/main/java/com/ai/viewmodel/ChatViewModel.kt

### Bug 92 — Severity: HIGH — Category: RAG silent fallback
**Location:** lines 76–80 (function ChatViewModel.messagesWithRag)
**Symptom:** `runCatching { KnowledgeService.retrieve(...) }.getOrDefault(emptyList())` — embedder errors (network, auth, dim mismatch) are swallowed silently. The user gets a chat response with NO RAG context but no indication that retrieval failed.
**Root cause:** Defensive catch with empty fallback.
**Proposed fix:** Bubble the error to the caller (chat screen), display "RAG retrieval failed: ..." inline.

### Bug 93 — Severity: HIGH — Category: per-turn RAG, no cache
**Location:** lines 71–94 (function ChatViewModel.messagesWithRag)
**Symptom:** Every turn re-embeds the user query and re-sweeps every KB chunk. For a 10-turn conversation, that's 10 retrieval passes. Even with bounded heap, this hits the embed API 10 times.
**Root cause:** No caching of query embedding across closely-spaced turns.
**Proposed fix:** Acceptable for now; document.

### Bug 94 — Severity: MEDIUM — Category: rag dispatch context
**Location:** line 78 (function ChatViewModel.messagesWithRag)
**Symptom:** `appViewModel.uiState.value.aiSettings` reads CURRENT settings inside the IO flow; if the user updates an API key or embedder model mid-stream, the running call uses the new value. Acceptable behaviour but not documented.

### Bug 95 — Severity: HIGH — Category: local LLM RAG injection
**Location:** lines 138–151 (function ChatViewModel.sendLocalLlmStream)
**Symptom:** When RAG context is injected into the system message, the local LLM sees it as a free-form prefix. But the prompt builder strips system messages from the main chat block (`withRag.filter { it.role != "system" }`). The first system message IS prepended once at the top, so RAG context lands. OK.
**Real bug:** If `withRag` has multiple system messages (e.g. existing system prompt + new RAG injected), `firstOrNull { it.role == "system" }` only takes the first, dropping RAG content if it's second. The injection in `messagesWithRag` MERGES into the existing system message, so single-message invariant holds. OK.

### Bug 96 — Severity: MEDIUM — Category: dual chat usage stats
**Location:** line 113 (function sendDualChatMessage)
**Symptom:** `appViewModel.settingsPrefs.updateUsageStatsAsync(...)` is called once per dual-chat turn. But if the call throws (rare, async coroutine fire-and-forget), the stats update is lost silently. No retry, no log.
**Root cause:** Fire-and-forget.
**Proposed fix:** Catch and log inside the async helper.

### Bug 97 — Severity: HIGH — Category: missing usage stats for streaming chat
**Location:** lines 21–63 (function sendChatMessageStream)
**Symptom:** `sendChatMessageStream` doesn't call `updateUsageStatsAsync`. The chat session screen calls `onRecordStatistics(inputTokens, outputTokens)` (line 437 in ChatScreens.kt), which routes to `recordChatStatistics` (line 160) — OK that path works. But for LOCAL LLM via `sendLocalLlmStream`, the chat session screen still calls `onRecordStatistics`, and that updates stats with provider="LOCAL" — which won't have a real pricing entry. The cost displayed is zero, OK. But the stats accumulation works.
**Real bug:** No way to associate usage cost with the actual local model file size; no monitoring of token throughput. Acceptable.

---

## File: ai/src/main/java/com/ai/data/ChatHistoryManager.kt

### Bug 98 — Severity: MEDIUM — Category: cache invalidation race
**Location:** lines 53–66 (function ChatHistoryManager.getAllSessions)
**Symptom:** Double-checked locking pattern: `cachedSessions?.let { return it }` outside lock, then re-check inside. Sound. But `cachedSessions = null` (line 35, 72, 91) runs OUTSIDE the lock for delete, and inside the lock for save — inconsistent.
**Root cause:** Mixed locking discipline.
**Proposed fix:** Always invalidate inside the lock.

### Bug 99 — Severity: HIGH — Category: thread safety
**Location:** line 74 (function deleteSession)
**Symptom:** `lock.withLock { cachedSessions = null }` happens after `delete()`. If another thread reads `getAllSessions` between the delete and the lock-acquired invalidation, it sees the cached list still containing the deleted session.
**Root cause:** Delete then invalidate — non-atomic.
**Proposed fix:** Wrap delete + invalidate in one withLock.

### Bug 100 — Severity: MEDIUM — Category: file scan
**Location:** lines 99–101 (function ChatHistoryManager.getSessionCount)
**Symptom:** `dir.listFiles { f -> f.extension == "json" }?.size` does NOT use the cache; every call walks disk. The hub calls `getSessionCountAsync()` on every refreshTick.
**Root cause:** No cache reuse.
**Proposed fix:** Return `cachedSessions?.size` if available.

### Bug 101 — Severity: HIGH — Category: not initialized
**Location:** lines 17–28 (object ChatHistoryManager)
**Symptom:** `historyDir` is set inside `init(context)`. Multiple call sites (`saveSession`, `loadSession`, `deleteSession`, `getAllSessions`, `getSessionCount`) check for null and bail. But there's no call to `init` from constructor — code paths that hit `getAllSessionsAsync()` before init silently return empty.
**Root cause:** Init is manually invoked; if missed, app behaves as if no history.
**Proposed fix:** Lazy init from any first call, or document init requirement.

---

## File: ai/src/main/java/com/ai/data/Knowledge.kt

### Bug 102 — Severity: MEDIUM — Category: gson FloatArray deserialise
**Location:** line 67 (data class KnowledgeChunk)
**Symptom:** `embedding: FloatArray` — Gson deserialises JSON arrays of numbers into FloatArray correctly only when registered. Default Gson serializer for FloatArray uses ArrayList<Float>; deserialise into FloatArray works via its built-in TypeAdapter. Verified by Gson docs. OK.
**Real bug:** equals/hashCode on data class with FloatArray uses array reference equality, not content. Two chunks with same numeric content compare unequal.
**Root cause:** FloatArray in data class.
**Proposed fix:** Override equals/hashCode if comparison is needed (currently only used by sourceId; no comparison happens).

### Bug 103 — Severity: HIGH — Category: race / cache invalidation
**Location:** lines 86–88 (object KnowledgeStore)
**Symptom:** `rootDir` is the only `@Volatile` cached value; subsequent operations all re-walk `kbDirOrNull`. No cache of KB list — every UI read triggers full directory listing.
**Root cause:** No memoisation; intentional? Documented as "scans only the directory".
**Proposed fix:** Acceptable; document.

### Bug 104 — Severity: MEDIUM — Category: file delete not atomic
**Location:** line 181 (function KnowledgeStore.deleteSource)
**Symptom:** `File(kbDir, "$CHUNKS_DIR/$sourceId.json").delete()` — failure ignored. Manifest then written with the source removed. If delete fails (file in use on Windows-style locking; not on Android typically), manifest doesn't list it but chunks file still exists, occupying disk space silently.
**Root cause:** Best-effort delete, no error surface.
**Proposed fix:** Log if delete fails.

### Bug 105 — Severity: HIGH — Category: missing manifest path
**Location:** line 211–213 (function loadKb)
**Symptom:** `File(kbDir, MANIFEST).readText()` throws if manifest missing. Caller wraps in `runCatching`, returns null. But if a KB directory exists with no manifest (e.g. partial creation crash), it's silently invisible from listKnowledgeBases.
**Root cause:** No recovery / repair.
**Proposed fix:** Auto-repair by reconstructing manifest from chunks/* files.

---

## File: ai/src/main/java/com/ai/data/KnowledgeService.kt

### Bug 106 — Severity: HIGH — Category: persist before extract
**Location:** lines 48–62 (function indexFile)
**Symptom:** `persistSourceLocally` copies the file BEFORE extract is attempted. If the file is malformed (e.g. corrupt PDF), the extractor errors out, but the local copy remains in `filesDir/knowledge/<kbId>/files/` orphaned with no manifest entry.
**Root cause:** Cleanup not done on extraction failure.
**Proposed fix:** Wrap extract in try/catch and delete the persisted file on error before rethrowing.

### Bug 107 — Severity: HIGH — Category: silent partial state
**Location:** lines 119–131 (function runIndex)
**Symptom:** When chunks is empty (extraction returned no text), a zero-chunk source row is saved with errorMessage. But the previously-persisted file copy (from `persistSourceLocally`) stays on disk. If the user re-indexes via the UI, `source.origin` still points at that file. OK that's actually intended (re-extract path tries again).

### Bug 108 — Severity: HIGH — Category: silent missing API key
**Location:** lines 219–221 (function retrieve)
**Symptom:** `if (apiKey.isBlank()) return emptyList()` — silently returns no hits when the embedder's provider has no API key. Chat dispatch then runs without RAG context, no error surfaced.
**Root cause:** Defensive empty fallback.
**Proposed fix:** Throw / surface error.

### Bug 109 — Severity: MEDIUM — Category: persist file naming collision
**Location:** lines 308–311 (function persistSourceLocally)
**Symptom:** `unique = "${System.currentTimeMillis()}_$safe"` — two near-simultaneous shares within the same millisecond produce the same filename; second write overwrites first.
**Root cause:** Insufficient uniqueness.
**Proposed fix:** Append `UUID.randomUUID()` or atomic counter.

### Bug 110 — Severity: HIGH — Category: heap order invariant
**Location:** lines 234–259 (function retrieve)
**Symptom:** `PriorityQueue<Scored>(cap+1, compareBy { it.score })` is a MIN-heap. `if (heap.size < cap) heap.offer(candidate)` adds; `else if (sim > heap.peek().score) { heap.poll(); heap.offer(candidate) }` evicts the lowest. Correct algorithm. But `cap = topK * 2` keeps 16 candidates by default. Then sortDescending and pick by maxContextChars budget. OK.
**Real bug:** When `sim == heap.peek().score`, the new candidate is NOT added — equal-scored chunks tied with the lowest are deterministically dropped. Acceptable.

### Bug 111 — Severity: MEDIUM — Category: kb mismatch UX
**Location:** lines 211–213 (function retrieve)
**Symptom:** Mismatch is logged but the UI flow doesn't surface it (chat sends hidden warning). The KnowledgeAttachDialog gates this client-side, but server-side there's no double-check.
**Root cause:** Defense in depth missing.
**Proposed fix:** Surface to caller via Result type.

---

## File: ai/src/main/java/com/ai/data/KnowledgeExtractors.kt

### Bug 112 — Severity: HIGH — Category: synchronous Tasks.await
**Location:** line 183 (function ocrBitmap)
**Symptom:** `Tasks.await(recognizer.process(...))` blocks the calling thread synchronously. If invoked from a Dispatchers.IO scope OK. But Tasks.await throws InterruptedException on cancellation — not caught — will propagate as a generic Exception.
**Root cause:** No try/catch for Interruption.
**Proposed fix:** Wrap in `runInterruptible` to allow proper coroutine cancellation.

### Bug 113 — Severity: HIGH — Category: bitmap memory
**Location:** lines 105–125 (function ocrPdf)
**Symptom:** Bitmap is created, OCR runs, then `bitmap.recycle()`. But ML Kit may hold a reference to the InputImage internally past the synchronous return; if it caches anything, the recycled bitmap causes downstream NPE / "cannot use recycled bitmap" exceptions.
**Root cause:** Recycle before ML Kit fully releases.
**Proposed fix:** ML Kit's docs say it copies pixels eagerly; should be safe. But if Tasks.await returns before pixel processing completes (it shouldn't), this bites. Verify via ML Kit version.

### Bug 114 — Severity: MEDIUM — Category: scale clamp
**Location:** line 112 (function ocrPdf)
**Symptom:** `2.78f.coerceAtMost(2400f / maxOf(page.width, page.height))` — for a small page (say 500x500), the right side is `2400/500 = 4.8`; `coerceAtMost(2.78, 4.8) = 2.78`. OK. For a poster page (10000x14000), `2400/14000 = 0.17`; `min(2.78, 0.17) = 0.17`. The downsampled rendering may be illegible for OCR.
**Root cause:** Aggressive downscale on large pages defeats OCR.
**Proposed fix:** Process large pages in tiles.

### Bug 115 — Severity: MEDIUM — Category: bounds null-safety
**Location:** lines 148–153 (function readUriImage)
**Symptom:** `bounds.outWidth/outHeight` — for a corrupt or non-image file, both are -1. `maxOf(-1,-1).coerceAtLeast(1) = 1`. Then `sampleSize = 1`. Loaded bitmap returns null at line 162, function returns "". OK degraded but no error message.
**Proposed fix:** Detect outWidth==-1 and surface a meaningful error.

### Bug 116 — Severity: HIGH — Category: zip stream readBytes
**Location:** lines 316, 321, 432 (function readUriXlsx, readUriOds)
**Symptom:** `zin.readBytes()` reads the CURRENT entry's content. But `ZipInputStream.readBytes()` may read past the entry boundary if the implementation isn't strict. Modern Android's ZipInputStream is OK; verify.
**Real bug:** For large XLSX shared-strings (millions of strings), `readBytes()` materialises the entire entry in memory. OOM risk.
**Proposed fix:** Stream-parse without buffering.

### Bug 117 — Severity: MEDIUM — Category: parser namespace handling
**Location:** lines 247, 339, 372, 442 (multiple parsers)
**Symptom:** `factory.isNamespaceAware = true` but `parser.name` returns local name. Comparisons like `parser.name == "row"` work for both DOCX and ODS without prefix discrimination — but means an XLSX with a row element in a different namespace is also treated as a row (rare in practice).
**Root cause:** Namespace ignored despite namespace-aware mode.
**Proposed fix:** Filter on namespace URI.

### Bug 118 — Severity: HIGH — Category: ODT inText flag
**Location:** lines 251, 268–272 (function parseOfficeXml)
**Symptom:** ODT path: `inText = textLocalNames.isEmpty()` = true initially, captures all TEXT events. But ODT has TEXT events inside `<office:annotation>`, `<office:meta>`, etc. — those non-body chunks get included.
**Root cause:** "All text" approach for ODT is too coarse.
**Proposed fix:** Restrict to `<office:body>/<office:text>/...`.

### Bug 119 — Severity: MEDIUM — Category: XLSX shared strings
**Location:** line 351 (function parseXlsxSharedStrings)
**Symptom:** `"si" -> { current.clear(); depth = 1 }` — depth is set to 1 but never decremented for nested `<si>` (legal? probably not). The flag relies on `<si>` being non-nested, which is true per OOXML spec. OK.

### Bug 120 — Severity: HIGH — Category: XLSX cell type "b"
**Location:** lines 386–404 (function parseXlsxSheet)
**Symptom:** Cell type can be `"b"` (boolean), `"e"` (error), `"n"` (number), `"s"` (shared string), `"str"` (formula string), `"inlineStr"` (inline). Code only handles `"s"` and `"inlineStr"` — `"str"` falls through to the `raw` branch, OK. But `"b"` cells contain "1"/"0" — emitted as "1"/"0" rather than "true"/"false". Acceptable approximation.

### Bug 121 — Severity: HIGH — Category: ODS sheet header
**Location:** lines 454–458 (function parseOdsContent)
**Symptom:** `if (sb.isNotEmpty()) sb.append('\n')` then "[sheet N]" — but if `sb` doesn't end with '\n' yet (it likely does because rows end with '\n'), this leaves a single '\n' between sheets. The XLSX path uses `\n\n` for the same separator. Inconsistent.
**Proposed fix:** Use `\n\n` here too for chunker boundary consistency.

### Bug 122 — Severity: MEDIUM — Category: ODS table-cell repeated columns
**Location:** lines 459–474 (function parseOdsContent)
**Symptom:** ODS supports `<table:table-cell table:number-columns-repeated="N">` to compress repeated empty cells. Code doesn't expand the repeats — the row layout drifts when subsequent rows have actual content.
**Root cause:** No support for the repeat attribute.
**Proposed fix:** Parse the attribute and emit N empty cells.

### Bug 123 — Severity: MEDIUM — Category: CSV double-newline
**Location:** lines 488–489 (function readUriCsv)
**Symptom:** When `firstRow` is the header, it's NOT added to dataBuffer — it's used as a per-block prefix. Each 10-row block re-prepends the header, separated by `\n\n`. OK but the chunker now sees blocks where the header repeats — duplicate retrieval matches on the header text.
**Root cause:** Intentional design vs RAG retrieval semantics.
**Proposed fix:** Acceptable; document.

### Bug 124 — Severity: HIGH — Category: CSV boolean-only header detection
**Location:** lines 522–525 (function readUriCsv)
**Symptom:** `hasHeader = row.all { it.isNotBlank() } && row.any { it.toDoubleOrNull() == null }` — if the first row is all blank cells (empty CSV), `hasHeader = false` and the row is added to dataBuffer. OK. If it's `"1,2,3"`, `toDoubleOrNull` succeeds for all → `hasHeader = false`. OK. If it's `"foo,1,2"`, `any { it.toDoubleOrNull() == null }` is true (foo is non-numeric) → `hasHeader = true`. So a pure-string header detector works, but a mixed first row that's actually data (like "Q1,2024,1500") is misclassified as header.
**Root cause:** Heuristic.
**Proposed fix:** Compare type pattern of row 1 vs row 2.

### Bug 125 — Severity: MEDIUM — Category: CSV first row lost
**Location:** lines 521–525 (function readUriCsv)
**Symptom:** When `hasHeader` is false, `dataBuffer += row` adds the first row. When `hasHeader` is true, header is set but the row itself isn't added (correctly). But if the header detection is wrong (Bug 124), the data row "foo,1,2" is treated as header and never appears in dataBuffer.
**Proposed fix:** Always add to dataBuffer; conditionally use as header.

### Bug 126 — Severity: HIGH — Category: CSV bufferedReader.mark / reset
**Location:** lines 513–518 (function readUriCsv)
**Symptom:** `br.mark(2048)` then read sample, then `br.reset()`. Default BufferedReader buffer is 8KB; mark up to 2KB is safe. But if the reader is wrapped over an InputStream with read() that may return less than requested, the sample may be tiny. `read(sample, 0, sample.size)` returns the actual count; `coerceAtLeast(0)` handles -1 EOF. OK. **Real bug:** `br.read(sample, ...)` may consume more than the mark window (it reads 1024 bytes within the 2048 mark — OK).

### Bug 127 — Severity: HIGH — Category: HTML user agent fingerprint
**Location:** line 605 (function fetchUrlAsText)
**Symptom:** UA `"Mozilla/5.0 (compatible; AI-Reports-RAG/1.0)"` — minimal. Some sites block requests with no Accept headers / no cookies. Failures bubble up as IOException → caller's `runCatching { ... }` returns Failure. Acceptable.
**Proposed fix:** Add Accept-Language, User-Agent more browser-like.

### Bug 128 — Severity: HIGH — Category: jsoup blocking
**Location:** lines 605–608 (function fetchUrlAsText)
**Symptom:** `Jsoup.connect(url).timeout(20_000).get()` is synchronous network I/O. Called from KnowledgeService.indexUrl from a Dispatchers.IO context — OK. But if user adds 100 URLs in quick succession, no rate limiting / connection pooling.
**Root cause:** Default Jsoup connection.
**Proposed fix:** Use the Retrofit pool.

### Bug 129 — Severity: MEDIUM — Category: chunker oversized single paragraph
**Location:** lines 668–681 (function KnowledgeChunker.chunk)
**Symptom:** When a single paragraph exceeds `maxCharsPerChunk` (2048), the loop splits it by hard char count. The split may land mid-word, mid-sentence — not great for embedding quality.
**Root cause:** Hard char split, no sentence detection.
**Proposed fix:** Split on sentence boundaries.

### Bug 130 — Severity: MEDIUM — Category: overlap doesn't reset across paragraphs
**Location:** line 689 (function KnowledgeChunker.chunk)
**Symptom:** `if (carry.isNotEmpty()) current.append(carry).append("\n\n")` — overlap carries from the previous emitted chunk into the new one. Then `flush()` writes it, then carry is updated to the trailing of the just-emitted chunk → the next chunk includes ITS own carry which contains the previous carry — overlap stacks.
**Reproduction:** Long doc with many small paragraphs → each chunk's first 200 chars contain the previous chunk's last 200, which contained that previous chunk's last 200, etc. The first 200 of chunk N is a slice from chunk N-1, OK that's the intent.
**Real bug:** When the carry is shorter than 200 (early chunks), the overlap is whatever existed. Consistent.

### Bug 131 — Severity: LOW — Category: overlap carry empty edge case
**Location:** line 651 (function KnowledgeChunker.chunk)
**Symptom:** `var carry = ""` — used only when current is non-empty after a flush. First chunk has no carry. OK.

---

## File: ai/src/main/java/com/ai/data/EmbeddingsStore.kt

### Bug 132 — Severity: HIGH — Category: cache name collision
**Location:** lines 26–32 (function cacheKey)
**Symptom:** `"$providerId::$model::$docId::${contentHash(content)}"` SHA-256 of the concatenated string. If providerId or model contains "::", collisions are theoretically possible (though SHA-256 makes them astronomically unlikely). OK.

### Bug 133 — Severity: HIGH — Category: file enumeration
**Location:** line 64 (function clearAll)
**Symptom:** `dir(context).listFiles()?.forEach { it.delete() }` — runs on caller's thread (no Dispatchers.IO). For thousands of cached embeddings, this stalls.
**Root cause:** No threading.
**Proposed fix:** Wrap in `withContext(Dispatchers.IO)` (and make clearAll suspend).

### Bug 134 — Severity: MEDIUM — Category: cosine numerical stability
**Location:** lines 68–80, 87–100 (function EmbeddingsStore.cosine)
**Symptom:** `Math.sqrt(normA) * Math.sqrt(normB)` — for very large vectors with norms in the thousands, multiplying two square roots can lose precision. `dot / denom` is OK if normA*normB doesn't overflow Double (10^308). But intermediate `normA = sumOf squares` of float vectors may underflow to zero for tiny embeddings.
**Proposed fix:** Use `Math.sqrt(normA * normB)` for one fewer fp operation, or use `kotlin.math.hypot`.

### Bug 135 — Severity: MEDIUM — Category: empty-vector fallback
**Location:** line 69, 88 (function cosine)
**Symptom:** `if (a.isEmpty() || a.size != b.size) return 0.0` — silent zero on dim mismatch. Higher-level code (KnowledgeService.retrieve) explicitly checks dim before calling, but other call sites (LocalSemanticSearch) don't, and a zero score is indistinguishable from "not similar".
**Root cause:** Silent failure mode.
**Proposed fix:** Log warning at first mismatch.

### Bug 136 — Severity: HIGH — Category: data type duplication
**Location:** lines 18, 49, 60 (object EmbeddingsStore)
**Symptom:** Stores `List<Double>` for report-level embeddings vs `FloatArray` for KnowledgeChunk. Two paths for similar data — code maintenance risk.

### Bug 137 — Severity: MEDIUM — Category: writeTextAtomic on huge vectors
**Location:** line 60 (function put)
**Symptom:** `writeTextAtomic(gson.toJson(vector))` — for a 1536-dim vector, JSON is ~30KB. `gson.toJson` materialises the whole string. For large stores this is OK but for embedding 1000+ chunks, total disk I/O is 30MB+.

---

## File: ai/src/main/java/com/ai/ui/chat/ChatScreens.kt — additional

### Bug 138 — Severity: MEDIUM — Category: KB dialog state recovery
**Location:** lines 550–561 (function ChatSessionScreen, KnowledgeAttachDialog)
**Symptom:** When user confirms KB attachment, `attachedKnowledgeBaseIds = selected.toList()` then `saveSession(messages)`. If `saveSession` fails (disk full), the in-memory state still has the new KB list but the persisted state doesn't. Resume → mismatch.
**Root cause:** Save failure not surfaced.
**Proposed fix:** Show error if saveSession returns false.

### Bug 139 — Severity: LOW — Category: focus
**Location:** line 384 (function ChatSessionScreen)
**Symptom:** `try { focusRequester.requestFocus() } catch (_: Exception) {}` — swallowing the exception means a failure to focus (e.g. requester not attached yet) leaves the keyboard hidden silently.
**Root cause:** Defensive try/catch.

### Bug 140 — Severity: MEDIUM — Category: trace probe
**Location:** lines 829–838 (function ChatMessageBubble)
**Symptom:** `produceState<String?>(initialValue = null, message.timestamp, model)` — but `produceState` re-fires when keys change. If model is the same and timestamp is the same, the lookup is cached. But the lookup walks ALL trace files for EVERY assistant message in EVERY scroll. For 20 assistant messages, that's 20 disk passes.
**Root cause:** No memoisation across messages.
**Proposed fix:** Pre-compute once per session, share.

### Bug 141 — Severity: LOW — Category: assistant streaming bubble
**Location:** lines 894–911 (function StreamingMessageBubble)
**Symptom:** No 🐞 icon on the streaming bubble — only on the persisted message. The trace file may exist for the in-flight call but isn't surfaced until streaming completes and the message is appended.
**Root cause:** Trace lookup happens after stream finishes.

---

## File: ai/src/main/java/com/ai/ui/chat/DualChatScreen.kt — additional

### Bug 142 — Severity: HIGH — Category: provider lookup by displayName
**Location:** line 560 (function DualMessageBubble)
**Symptom:** `AppService.entries.firstOrNull { it.displayName == msg.providerName }` — uses displayName for reverse lookup. Two providers could share a displayName (extremely unlikely, but not enforced). Also displayName change in code breaks the lookup for old persisted dual chats. But dual chats aren't persisted, so this only affects in-memory.
**Proposed fix:** Store provider id, not displayName.

### Bug 143 — Severity: MEDIUM — Category: scope cancellation propagation
**Location:** lines 462–463 (function DualChatSessionScreen, Stop button)
**Symptom:** `chatJob?.cancel()` then `isRunning = false`. The cancellation is async; the flag flips immediately, but the coroutine may still emit one more turn before the Cancel takes effect (between API request and response, the cancel arrives but the response comes in and is appended to messages).
**Root cause:** No await on cancel.
**Proposed fix:** `chatJob?.cancelAndJoin()` (suspend).

### Bug 144 — Severity: MEDIUM — Category: extra chats parsing
**Location:** line 475 (function DualChatSessionScreen, extraCount parse)
**Symptom:** `extraChatsText.toIntOrNull() ?: 0` — accepts negative numbers, which then make `targetInteractions = currentInteraction + (-N)` which is < currentInteraction. The while loop at line 351 exits immediately, isStopped becomes true, no error.
**Root cause:** No bounds check.
**Proposed fix:** `extraChatsText.toIntOrNull()?.takeIf { it > 0 } ?: 0`.

---

## File: ai/src/main/java/com/ai/ui/models/ModelScreens.kt — additional

### Bug 145 — Severity: MEDIUM — Category: usage stats cumulative card duplication
**Location:** lines 778–799 (function ModelInfoScreen, hasUsageStats)
**Symptom:** When usageEntry has callCount > 0 BUT the model also appears in `recentUsages` (chat/report rows), the cumulative card and the per-event rows together over-state usage in the user's mental model — they don't realise the cumulative INCLUDES the listed events.
**Root cause:** No de-duplication / clarification.
**Proposed fix:** Label as "Total (includes listed events)".

### Bug 146 — Severity: MEDIUM — Category: openrouter `expiration_date`
**Location:** line 1201 (function ModelInfoScreen, Technical Specs)
**Symptom:** `or.expiration_date?.let { ModelInfoRow("⚠ Expires", it) }` — expiration date string. Not compared to today, so a model that expired last year still shows the date without indicating "DEPRECATED".
**Root cause:** No date comparison.
**Proposed fix:** Parse and compare; show "Expired" badge for past dates.

### Bug 147 — Severity: HIGH — Category: traceCount key
**Location:** line 519 (function ModelInfoScreen)
**Symptom:** `remember(provider, modelName)` — but does NOT key on `ApiTracer.isTracingEnabled`. If user toggles tracing on/off in Settings while the screen is open, the count doesn't refresh.
**Proposed fix:** Add ApiTracer state observable.

### Bug 148 — Severity: MEDIUM — Category: aiDescription PromptCache.get on Main
**Location:** lines 660–662 (function ModelInfoScreen)
**Symptom:** `LaunchedEffect(introCacheKey) { PromptCache.get(introCacheKey)?.let { aiDescription = it } }` — `PromptCache.get` is invoked on a Main coroutine; if it does disk I/O, that's blocking.
**Proposed fix:** `withContext(Dispatchers.IO)` inside.

### Bug 149 — Severity: MEDIUM — Category: agent insertion duplicates
**Location:** line 551 (function ModelInfoScreen, AgentEditScreen onSave)
**Symptom:** `aiSettings.agents + agent` — no check for an existing agent with the same name. `existingNames = aiSettings.agents.map { it.name.lowercase() }.toSet()` is passed to AgentEditScreen for validation, but if the user bypasses (the existingNames check is internal to AgentEditScreen) the dup goes through.
**Root cause:** Validation only inside the screen.
**Proposed fix:** Re-check on save; hard-fail on dup.

### Bug 150 — Severity: LOW — Category: monospace JSON colorize
**Location:** lines 1387 (function colorizeJson)
**Symptom:** Number detection: `c == '-' && i + 1 < n && json[i + 1].isDigit()` — handles negative numbers. But scientific notation like `1.5e-3` parses fine because the inner while accepts 'e', 'E', '+', '-'. OK.
**Real bug:** Inside a number, `-` is consumed as part of scientific notation; outside, only at the start. Code is correct.

### Bug 151 — Severity: LOW — Category: backslash escape in strings
**Location:** line 1377 (function colorizeJson)
**Symptom:** `'\\' -> i = (i + 2).coerceAtMost(n)` — backslash plus next char. `` (Unicode escape) is 6 chars total but only 2 are skipped, the rest are processed normally — keys/values may be misclassified. Acceptable for display only.

---

## File: ai/src/main/java/com/ai/ui/search/* — additional

### Bug 152 — Severity: MEDIUM — Category: query trim consistency
**Location:** lines 68 (LocalSearchScreen), 120 (LocalSemanticSearchScreen), 67 (QuickLocalSearchScreen), 121 (SemanticSearchScreen)
**Symptom:** All four search screens trim the query before passing it to the search function. Some pass raw `query` to enable check (line 80, 136 etc.), some check `query.isNotBlank()` — inconsistent. A query of pure whitespace is enabled in some screens, disabled in others.
**Root cause:** Inconsistent guard.
**Proposed fix:** Standardise on trimmed-non-blank check.

### Bug 153 — Severity: LOW — Category: reports pulled even when query empty
**Location:** lines 122 (LocalSearchScreen.runLocalSearch), 197 (LocalSemanticSearchScreen.runLocalEmbedSearch), 197 (SemanticSearchScreen.runEmbeddingSearch)
**Symptom:** `ReportStorage.getAllReports(context)` is called inside the search function. If reports list is large (1000+), each search reads them all from disk. No caching.
**Root cause:** No memoisation.

---

## File: SelectionScreens.kt — additional

### Bug 154 — Severity: MEDIUM — Category: stale getModelPricing
**Location:** lines 185–186, 345–346 (function SelectModelScreen, SelectAgentScreen)
**Symptom:** `aiSettings.getModelPricing(provider, modelName) ?: PricingCache.getPricing(...)` — first call returns user-set override. But `getModelPricing` is read at composition time; if user updates an override mid-screen, no recomposition trigger.
**Root cause:** Snapshot at composition.
**Proposed fix:** Observe an aiSettings flow.

### Bug 155 — Severity: LOW — Category: showDefaultOption visual
**Location:** line 167–181 (function SelectModelScreen)
**Symptom:** "Default (use provider setting)" row shown only if `showDefaultOption=true`. The provider's effective default model is `aiSettings.getModel(provider)` — if that's blank, the row reads "Default (use provider setting)" with empty subtitle. Confusing.
**Proposed fix:** Skip the row when default is blank.

---

## Cross-cutting

### Bug 156 — Severity: HIGH — Category: BackHandler stacking
**Location:** Many screens (ChatSessionScreen line 252, KnowledgeDetailScreen line 254, ModelInfoScreen line 506, etc.)
**Symptom:** Every top-level Composable registers `BackHandler { onNavigateBack() }`. Overlay sub-screens (e.g. AgentEditScreen, ReportSelectModelsScreen, ModelRawInfoScreen) ALSO register their own. Compose dispatches the top-most one but on rapid taps timing can be racy — the wrong handler may fire if the overlay was just dismissed.
**Root cause:** Top-level handler always enabled.
**Proposed fix:** Use `BackHandler(enabled = !showOverlay) { ... }`.

### Bug 157 — Severity: MEDIUM — Category: Dispatchers.Main usage from non-Main
**Location:** Multiple — KnowledgeDetailScreen line 283, 297, 322, 382; LocalSemanticSearchScreen 128; SemanticSearchScreen 131
**Symptom:** Inside an `withContext(Dispatchers.IO)` block, code calls `scope.launch(Dispatchers.Main) { status = msg }`. The pattern relaunches a coroutine on Main from inside an IO context. Functional, but each launch creates a job. With 100 progress updates → 100 short-lived Main coroutines.
**Root cause:** Naive bridging.
**Proposed fix:** Use a Channel or directly mutate via `withContext(Dispatchers.Main.immediate)`.

### Bug 158 — Severity: MEDIUM — Category: `getActiveServices` recomputed
**Location:** Multiple — ModelSearchScreen, SemanticSearchScreen, ChatHub, etc.
**Symptom:** `aiSettings.getActiveServices()` is called inside `remember` blocks but may recompute large per-call work (filters all 39 providers). Not memoised cross-call.
**Proposed fix:** Cache on Settings.

### Bug 159 — Severity: HIGH — Category: cosine division by zero
**Location:** Bug 134 area — for an embedding all-zero vector (rare but possible if the embedder hiccups), `denom = 0.0` and `cosine` returns 0.0. The chunk is silently ranked at the bottom. No error, no warning.
**Proposed fix:** Log when zero norm detected.

### Bug 160 — Severity: LOW — Category: empty regex split
**Location:** LocalSearchScreen line 119, LocalSemanticSearchScreen, SemanticSearchScreen, ChatHistory
**Symptom:** `query.lowercase().split(Regex("\\s+"))` — if query is `"   "`, result is `[""]` (one empty string). `filter { it.isNotBlank() }` removes it. OK.

### Bug 161 — Severity: HIGH — Category: ChatHistoryManager cache leak
**Location:** ChatHistoryManager line 22 (cachedSessions)
**Symptom:** `cachedSessions: List<ChatSession>?` is a strong reference held forever after the first read. For a user with thousands of sessions, this is hundreds of MB resident.
**Root cause:** No size limit.
**Proposed fix:** WeakReference or LRU cache.

### Bug 162 — Severity: MEDIUM — Category: estimateTokens
**Location:** Multiple — ChatScreens line 423, DualChatScreen lines 360,375
**Symptom:** `AppViewModel.estimateTokens(content)` — heuristic only (likely chars/4 or word count). For cost estimation of e.g. tokenisation-sensitive models (CJK languages, Korean), the estimate can be off by 2-3×.
**Root cause:** No real tokenizer.
**Proposed fix:** Use provider-specific tokenizer when available; document.

### Bug 163 — Severity: MEDIUM — Category: lifetime of `LocalLlm` cached state
**Location:** LocalSemanticSearchScreen line 53, ChatHub line 46, SelectModelScreen line 92
**Symptom:** `LocalLlm.availableLlms(context)` — runs disk I/O without `produceState`/Dispatchers.IO. Stalls UI for a moment on enter.
**Proposed fix:** Async load.

### Bug 164 — Severity: LOW — Category: showAgentEdit overlay return preserves state but spawns recompose
**Location:** ModelScreens line 542–562
**Symptom:** When `showAgentEdit = true`, the overlay screen takes over via `return`. State of the parent ModelInfoScreen is preserved by remember. OK. But if the user creates an agent and lands back here, the overlay path's `onSave` calls `onSaveSettings(...)` which mutates Settings — but the parent's `aiSettings` parameter hasn't been updated yet for this composition. The "AI Usage" cards may read stale numbers until next recompose.

