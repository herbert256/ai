# Bug review — Chat / Dual Chat / Knowledge / Models / Search / History (audit-3, fresh from current code)

Scope: `ui/chat/**`, `ui/knowledge/**`, `ui/models/**`, `ui/search/**`,
`ui/history/**`, and `viewmodel/ChatViewModel.kt`. Findings are grouped by
file and numbered continuously. Every location was read from the live code (2026-06-06).

---

## File: ai/src/main/java/com/ai/viewmodel/ChatViewModel.kt

### Bug 1 — Severity: MEDIUM — Category: cost attribution
**Location:** ChatViewModel.kt:178-185 (`recordChatStatistics`) + ChatScreens.kt:559-574 (`actuallySend`)
**Symptom:** Single-chat token spend is filed under the AI-Usage category `"report"`, not `"chat"`. The AI-Usage / Costs breakdown attributes interactive chat spend to the Reports bucket, while Dual Chat (which records inside its tracer-tag block) is correctly bucketed as `"Dual chat"`. The two chat paths disagree.
**Root cause:** `recordChatStatistics` calls `updateUsageStatsAsync(service, model, in, out, total)` with no `kind` argument, so it defaults to `kind="report"`. `SettingsPreferences.updateUsageStats` (line 637) derives the category as `normalizeUsageKind(ApiTracer.currentCategory ?: kind)`. But in `actuallySend` the `onRecordStatistics(...)` call is placed *outside* the `withTracerTags(reportId=…, category="Chat")` block (it runs after the `collect` completes), so `ApiTracer.currentCategory` has already been restored to null → it falls back to the literal `"report"`.
**Reproduction:** Send a chat turn; open AI Usage / Costs; the call lands in the Reports category, not a Chat category.
**Proposed fix:** Pass `kind = "chat"` explicitly from `recordChatStatistics` (and from `sendDualChatMessage`), or move the `onRecordStatistics` call inside the `withTracerTags("Chat")` block in `actuallySend`.
**Status:** Fixed (2026-06-07) — chat and dual-chat usage stats now pass explicit "Chat" / "Dual chat" kinds instead of falling back to the default report bucket after tracer tags are cleared

### Bug 2 — Severity: LOW — Category: cost tracking
**Location:** ChatViewModel.kt:55-71, 78-111 (`sendChatMessageStream` / `messagesWithRag`)
**Symptom:** RAG retrieval makes an embedding API call per user turn whose token usage and cost are never recorded; the chat cost banner and AI Usage under-report when KBs are attached.
**Root cause:** `messagesWithRag` → `KnowledgeService.retrieve` embeds the query via `repository.embed`, but no `updateUsageStats` call is made for that embedding round-trip. Only the chat model's estimated tokens are recorded (`recordChatStatistics`).
**Proposed fix:** Record embedding usage at the `retrieve` call site (the embedder provider/model are known there), tagged `kind="embedding"` or `"chat/rag"`.
**Status:** Fixed (2026-06-07) — successful chat RAG retrieval attempts now record estimated query-token usage against the KB embedder provider/model with kind `chat/rag`

### Bug 3 — Severity: LOW — Category: prompt formatting
**Location:** ChatViewModel.kt:143-173 (`sendLocalLlmStream`)
**Symptom:** On-device LLM replies can be lower quality / run on, because the conversation is flattened into a bare `User:/Assistant:` transcript with no model-specific chat template and no stop sequence.
**Root cause:** The prompt builder appends `"User: "/"Assistant: "` lines and a trailing `"Assistant: "`. `LocalLlm.generate` then runs unbounded with no `</s>`/turn-delimiter stop, so a chatty model may hallucinate a fake `User:` turn.
**Proposed fix:** Look up a per-model chat template (or at least stop at the first generated `"\nUser:"`), and trim the echoed prefix.
**Status:** Fixed (2026-06-07) — local chat output is now trimmed at generated `User:` turn boundaries and common echoed `Assistant:` prefixes are removed before display

### Bug 4 — Severity: LOW — Category: cost accuracy
**Location:** ChatViewModel.kt:117-133 (`sendDualChatMessage`)
**Symptom:** Dual-chat cost is computed from `AppViewModel.estimateTokens` (a heuristic char/word count) rather than the provider's reported `usage`, so the cost row can be materially off for tokenizer-divergent models.
**Root cause:** `sendChat` returns only the text; the actual `usage` block from the response is discarded, and the screen re-estimates locally.
**Proposed fix:** Thread the API `usage` (prompt/completion tokens) back from `repository.sendChat` and prefer it over the estimate when present.
**Status:** Fixed (2026-06-07) — non-streaming chat dispatch now returns token usage, and dual chat prefers provider-reported usage before falling back to estimates

### Bug 5 — Severity: LOW — Category: RAG edge case
**Location:** ChatViewModel.kt:83 (`messagesWithRag`)
**Symptom:** A user turn that has only an attached image and a blank text body never triggers RAG retrieval, even when a KB is attached.
**Root cause:** `val lastUser = messages.lastOrNull { it.role == "user" }?.content?.takeIf { it.isNotBlank() } ?: return messages` — a blank-but-image turn returns the messages unchanged.
**Proposed fix:** Acceptable for text-only embedders; if vision-OCR retrieval is ever wanted, fall back to a caption or skip silently with a log line (currently silent).
**Status:** Fixed (2026-06-07) — image-only chat turns now log an explicit RAG skip because retrieval still requires a text query

---

## File: ai/src/main/java/com/ai/ui/chat/ChatScreens.kt

### Bug 6 — Severity: HIGH — Category: state loss / data loss
**Location:** ChatScreens.kt:284 (`var messages by remember { mutableStateOf(initialMessages) }`)
**Symptom:** Rotating the device (or any activity recreation) during a chat resets the on-screen conversation back to `initialMessages` (empty for a new chat, or the pre-resume snapshot for a resumed one). Every turn added during this screen's lifetime disappears from the UI, and the next `saveSession` then overwrites the on-disk session with the truncated set — permanent loss of the intervening turns.
**Root cause:** `messages` is a plain `remember`, which does not survive configuration change. The screen never re-seeds `messages` from `persistedSession?.messages` on re-entry (it uses the stale `initialMessages` param), and `saveSession(msgs)` writes whatever is currently in `messages`. The manifest has no `android:configChanges`, so rotation recreates the Activity.
**Reproduction:** Start a chat, send 3-4 turns, rotate the phone → the bubbles vanish; send one more message → disk now holds only `initialMessages + 1`.
**Proposed fix:** Seed from disk on entry (`mutableStateOf(persistedSession?.messages ?: initialMessages)`) keyed on `currentSessionId`, or persist `messages` via a `rememberSaveable` Saver (mirroring `DualMessagesSaver`).
**Status:** Fixed (2026-06-07) — messages re-seed from the on-disk session keyed on currentSessionId, surviving recreation

### Bug 7 — Severity: HIGH — Category: state loss / orphaned data
**Location:** ChatScreens.kt:282 (`val currentSessionId = remember { sessionId ?: java.util.UUID.randomUUID().toString() }`)
**Symptom:** For a freshly started (configure-on-the-fly) chat, rotation mints a brand-new session id. The previously-saved session is orphaned on disk under the old UUID, the screen becomes a blank new chat, and per-bubble 🐞 trace tagging (which keys on `currentSessionId`) loses continuity.
**Root cause:** `remember` (not `rememberSaveable`) regenerates the UUID on activity recreation whenever the incoming `sessionId` param is null (the new-chat case).
**Reproduction:** Start a new chat, send a turn (saved under UUID-A), rotate → a new UUID-B is in play; UUID-A's file lingers in history; further sends write UUID-B.
**Proposed fix:** `rememberSaveable { sessionId ?: UUID.randomUUID().toString() }`.
**Status:** Fixed (2026-06-07) — currentSessionId now rememberSaveable, survives recreation

### Bug 8 — Severity: MEDIUM — Category: stale pricing / wrong cost
**Location:** ChatScreens.kt:405-409 (`val totalCost by remember { derivedStateOf { … pricing.promptPrice … } }`)
**Symptom:** The running-cost banner is computed against the pricing object captured at the *first* composition. When `PricingCache` finishes priming (the comment claims this fixes the cold window), the banner does **not** re-price — it stays frozen at the cold/default rate for the whole session.
**Root cause:** The `remember {}` wrapping `derivedStateOf` has no keys, so the lambda is created once and closes over the first-composition `pricing` value. `pricing.promptPrice` is a plain field read (not snapshot state), so `derivedStateOf` only re-evaluates when `totalInputTokens`/`totalOutputTokens` change — always re-reading the captured (stale) `pricing`. The recomputed `pricing` local from later compositions is never observed.
**Proposed fix:** Key the remember on pricing: `val totalCost by remember(pricing) { derivedStateOf { … } }`.
**Status:** Fixed (2026-06-07) — totalCost's derivedStateOf is now keyed on pricing, so the chat banner reprices when PricingCache refreshes the provider/model price

### Bug 9 — Severity: MEDIUM — Category: locale / comma-decimal
**Location:** ChatScreens.kt:143-153 (`onStartChat`, `temperature.toFloatOrNull()` etc.)
**Symptom:** On a comma-decimal locale (nl-NL), typing `0,7` for Temperature / Top P / penalties is silently dropped — the field falls back to the preset or null. The user can never enter a decimal parameter.
**Root cause:** `String.toFloatOrNull()` parses with a `.` decimal separator (Java `Float.parseFloat`), independent of locale, so `"0,7".toFloatOrNull()` returns null. There is no comma→dot normalization.
**Reproduction:** On the nl-NL device, set Temperature `0,7`, Start Chat → the request uses the preset/null temperature, not 0.7.
**Proposed fix:** Normalize `,`→`.` before parsing, or parse with the device locale's `NumberFormat`.
**Status:** Fixed (2026-06-07) — onStartChat float fields normalize comma→dot before toFloatOrNull

### Bug 10 — Severity: MEDIUM — Category: phantom data
**Location:** ChatScreens.kt:465-486 (`LaunchedEffect(parameters.systemPrompt)`)
**Symptom:** Entering a configure-on-the-fly chat that carries a non-blank system prompt — then leaving without ever sending a message — creates a saved chat session on disk (system-message-only), which shows up in History/Recent as an "Empty chat".
**Root cause:** On first composition the effect inserts the system `ChatMessage` (`changed = true`) and calls `saveSession(messages)`, persisting a session that has no user turn yet.
**Reproduction:** Pick provider/model, set a system prompt preset, open the chat session, press back without sending → an empty session is now in Chat History.
**Proposed fix:** Don't persist until the first user message is sent; keep the system-message merge in memory and only save inside `actuallySend`.
**Status:** Fixed (2026-06-07) - the system-prompt LaunchedEffect only persists once messages contains a user turn; the in-memory merge still applies and actuallySend saves it with the first message

### Bug 11 — Severity: MEDIUM — Category: race / lost update
**Location:** ChatScreens.kt:704-712 (pin toggle → `ChatHistoryManager.setSessionPinned`) with ChatHistoryManager.kt:131-134
**Symptom:** Toggling 📌 while a stream is completing can drop the just-arrived assistant turn from disk.
**Root cause:** `setSessionPinned` does a non-atomic load→copy(pinned)→save against the same session id. If the streaming completion's `saveSession(messages)` lands between the load and the save, the pin's save rewrites the older message list (without the new assistant turn) back over it. The screen also already persists `pinned` in its own `saveSession`, making the separate call both redundant and racy.
**Proposed fix:** Don't call `setSessionPinned` separately; just flip the local `pinned` var and let the next `saveSession(messages)` carry it, or make the pin update a copy-from-current-state operation under the manager lock.
**Status:** Fixed (2026-06-07) — setSessionPinned now performs the read-copy-write under one ChatHistoryManager lock, so it cannot save a stale loaded message list over a concurrent session save

### Bug 12 — Severity: LOW — Category: cost display
**Location:** ChatScreens.kt:315-316, 405-409, 677-679 (`totalInputTokens`/`totalOutputTokens` + title-bar cost)
**Symptom:** Resuming a long, previously-expensive chat shows a running cost of `null`/no-cost in the title bar until the next message is sent, under-reporting what the conversation actually cost.
**Root cause:** The token accumulators are `remember { 0 }` and are never seeded from the persisted session, so on resume the banner reflects only this visit's turns.
**Proposed fix:** Either label the banner "cost this visit", or reconstruct an approximate cumulative figure from the persisted messages on entry.
**Status:** Fixed (2026-06-07) — chat sessions now seed their running token counters from persisted messages, reconstructing approximate input/output totals on resume

### Bug 13 — Severity: LOW — Category: cost tracking
**Location:** ChatScreens.kt:601-606 (`catch (e: Exception)` branch in `actuallySend`)
**Symptom:** When a stream errors after partial output, the partial assistant text is saved with `[Stream interrupted]`, but its output tokens are never added to `totalOutputTokens` / `onRecordStatistics`, so the partial (billed) output is invisible in cost/usage.
**Root cause:** The token accounting (`totalOutputTokens += …; onRecordStatistics(…)`) lives only in the success path before the catch.
**Proposed fix:** Count the partial `sb` content in the error branch too.
**Status:** Fixed (2026-06-07) — interrupted streams now estimate tokens from the partial assistant text and record the same input/output usage as successful turns

### Bug 14 — Severity: LOW — Category: race / double send
**Location:** ChatScreens.kt:620-661, 917-921 (`trySend` / Send button)
**Symptom:** A very fast double-tap on Send can fire two sends (or two moderation calls) for one input.
**Root cause:** The button's `enabled`/onClick guards on `isStreaming`/`isModerating`, but both flags are set inside the launched coroutine (`scope.launch { isStreaming = true … }`), which is dispatched, not run synchronously. Between `trySend` returning and the coroutine running, a second tap still sees the old (false) flags.
**Proposed fix:** Set a synchronous in-flight guard (`if (sending) return; sending = true`) on the main thread before launching.
**Status:** Fixed (2026-06-07) — `trySend` now sets a synchronous saveable `sendInFlight` guard before moderation/streaming launch and clears it only after streaming finishes or flagged input is dismissed

### Bug 15 — Severity: LOW — Category: performance
**Location:** ChatScreens.kt:1101-1130 (`AnimatedTextLines`)
**Symptom:** During streaming, each chunk re-splits the entire accumulated content (`content.split("\n")`) and rebuilds up to 30 `Text` composables each driving an `animateFloatAsState`, on every chunk — measurable churn on long responses.
**Root cause:** The composable is called with the full streaming content and recomposes per chunk; only the >30-line case snaps.
**Proposed fix:** Lower the snap threshold, or memoize the split and only animate newly-added lines.
**Status:** Open

### Bug 16 — Severity: LOW — Category: performance
**Location:** ChatScreens.kt:494-495 (`val displayMessages = messages.filter { it.role != "system" }`)
**Symptom:** The system-message filter runs on every recomposition of the whole session screen (not just when `messages` changes), allocating a new list each time.
**Root cause:** `displayMessages` is a plain val, not `remember(messages)`.
**Proposed fix:** `val displayMessages = remember(messages) { messages.filter { it.role != "system" } }`.
**Status:** Fixed (2026-06-07) — `displayMessages` is now memoized with `remember(messages)`

### Bug 17 — Severity: LOW — Category: list key collision (unconfirmed)
**Location:** ChatScreens.kt:757 (LazyColumn `key`)
**Symptom:** Two display messages sharing role + millisecond `timestamp` + identical content would collide on the composite key and crash Compose ("key already used").
**Root cause:** Key = `role_timestamp_content.hashCode()`; the content hash disambiguates most cases but two identical short messages constructed in the same ms (e.g. duplicate user "ok") still collide. Very narrow — unconfirmed in practice.
**Proposed fix:** Add a stable per-message UUID to `ChatMessage` (as `DualMessage` already has) and key on that.
**Status:** Open

### Bug 18 — Severity: LOW — Category: cost attribution
**Location:** ChatScreens.kt:1143-1187 (`kickOffChatTitleGeneration` → `analyzeWithAgent`)
**Symptom:** Generating the AI chat title makes a real paid call (DeepSeek by default) after the first reply, but that cost is never added to the chat session's cost banner and is filed under the title agent's own bucket — invisible from the chat's perspective.
**Root cause:** The title call is a separate `analyzeWithAgent`; the chat screen doesn't fold its cost into `totalInputTokens`/`totalOutputTokens`.
**Proposed fix:** Surface the title call's usage as part of the session cost, or document that titling has its own cost line.
**Status:** Open

### Bug 19 — Severity: LOW — Category: trace mis-association (unconfirmed)
**Location:** ChatScreens.kt:646-651 (flagged-input trace lookup)
**Symptom:** The 🐞 on a flagged-input dialog can open an unrelated trace.
**Root cause:** The lookup filters `it.reportId == null && it.model == modModelId && it.timestamp >= callStart` and takes the earliest. Any other untagged (reportId==null) trace of the same moderation model produced after `callStart` (e.g. a concurrent moderation elsewhere) could be picked.
**Proposed fix:** Tag the moderation call with a unique reportId/category and filter on it.
**Status:** Open

### Bug 20 — Severity: LOW — Category: state loss
**Location:** ChatScreens.kt:307 (`var error by remember`), 369-370 (`moderationError`, `isModerating`)
**Symptom:** A visible error / moderation banner disappears on rotation.
**Root cause:** These transient flags use plain `remember`; rotation resets them. Mostly benign but the user loses the displayed failure reason.
**Proposed fix:** `rememberSaveable` for `error`/`moderationError`.
**Status:** Fixed (2026-06-07) — visible chat and moderation error strings now use `rememberSaveable` so banners survive recreation

### Bug 21 — Severity: LOW — Category: per-turn flag persistence
**Location:** ChatScreens.kt:788-843 (Web search / 🧠 reasoning chips)
**Symptom:** Toggling the Web-search chip or changing the reasoning-effort level and then leaving the screen *without sending* loses the change — on return the chip reverts.
**Root cause:** `useWebSearch`/`reasoningEffort` are only persisted via `saveSession` inside `actuallySend` (and in the system-prompt effect); a toggle alone never writes.
**Proposed fix:** Persist on chip change too (debounced `saveSession`).
**Status:** Open

### Bug 22 — Severity: LOW — Category: performance
**Location:** ChatScreens.kt:1015-1023 (`ChatMessageBubble` per-bubble `produceState` over `ApiTracer.getTraceFiles()`)
**Symptom:** Every assistant bubble independently calls `ApiTracer.getTraceFiles()` (which can parse the whole trace dir on cold cache) to gate its 🐞. A long conversation does N full trace-dir scans.
**Root cause:** The trace lookup is per-bubble, keyed on `(timestamp, model, sessionId)`, with no shared/batched load.
**Proposed fix:** Load the session's traces once at the screen level and pass a map down.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/chat/DualChatScreen.kt

### Bug 23 — Severity: HIGH — Category: state loss / config in transient UiState
**Location:** DualChatScreen.kt:374-396 (`val config = remember { appViewModel.uiState.value.dualChatConfig }` + `LaunchedEffect(Unit){ … dualChatConfig = null }`)
**Symptom:** Rotating (or any activity recreation / process death) during a dual-chat session immediately kicks the user back out of the session, discarding the entire (never-disk-persisted) conversation — even though `messages` is carefully `rememberSaveable`.
**Root cause:** `config` is read from `uiState` via a plain `remember`, then a `LaunchedEffect(Unit)` *clears* `uiState.dualChatConfig` to null right after capture. On recreation the plain `remember` re-runs and now reads the cleared `null` config, triggering `if (config == null) { onNavigateBack() }`. The `DualMessagesSaver` survives, but with no config the screen exits before it can be used.
**Reproduction:** Start a dual chat, let it run a few rounds, rotate → you're bounced to the previous screen and the conversation is gone.
**Proposed fix:** Persist the config (a `rememberSaveable` Saver for `DualChatConfig`, or don't clear it from UiState until the screen disposes), so it survives recreation alongside the saved messages.
**Status:** Fixed (2026-06-07) — config no longer cleared on entry; survives recreation, cleared only on real exit (onExit)

### Bug 24 — Severity: MEDIUM — Category: state loss / cost desync
**Location:** DualChatScreen.kt:408-411 (`model1InputTokens` … `model2OutputTokens` via plain `remember`)
**Symptom:** If the dual-chat screen survives a recomposition where `messages` is restored from the Saver but the run continues (e.g. "Chat more"), the cost row resets to `0.0000c` while the conversation is intact — the displayed cost no longer matches the messages.
**Root cause:** The four token counters are `remember { 0 }` (not `rememberSaveable`), unlike `messages`/`currentInteraction`/`targetInteractions` which are saveable.
**Proposed fix:** Make the token counters `rememberSaveable`.
**Status:** Fixed (2026-06-07) - the four dual-chat token counters are now rememberSaveable, so they survive a recomposition that restores messages mid-run

### Bug 25 — Severity: MEDIUM — Category: race / concurrent loops
**Location:** DualChatScreen.kt:438-497, 567-578, 587-596 (`startChatLoop` / Stop / "Chat more")
**Symptom:** A fast Stop → "Chat $N more" can leave the new loop running while the UI shows it as stopped (no Stop button), because shared run-state is clobbered by the *old* job's `finally`.
**Root cause:** Stop sets `isRunning=false` synchronously and cancels the job, but the cancelled coroutine's `finally { isRunning=false; isStopped=true; thinkingModel=null }` runs asynchronously later. If "Chat more" starts a new loop (`isRunning=true`) before the old `finally` executes, the old `finally` then overwrites `isRunning`/`isStopped`, hiding the Stop control while the new loop keeps appending messages and counting cost.
**Proposed fix:** Guard the `finally` with a job-identity check (only mutate state if `chatJob` is still this coroutine's job), or `join()` the old job before launching a new one.
**Status:** Fixed (2026-06-07) — startChatLoop now assigns a lazy-started job before execution and the finally block only clears run state when that job is still current

### Bug 26 — Severity: MEDIUM — Category: stale pricing / wrong cost
**Location:** DualChatScreen.kt:424-426 (`model1Cost`/`model2Cost`/`totalCost` via `remember { derivedStateOf { … pricing1.* … } }`)
**Symptom:** Same cold-pricing defect as Bug 8 — the cost rows are frozen at the pricing captured on first composition and never re-price when `PricingCache` primes.
**Root cause:** Unkeyed `remember { derivedStateOf { … } }` closes over the first `pricing1`/`pricing2` values; `derivedStateOf` only re-evaluates on token changes, re-reading the stale pricing objects.
**Proposed fix:** `remember(pricing1) { … }` / `remember(pricing2) { … }`.
**Status:** Fixed (2026-06-07) - cost derivedStateOf now keyed on pricing1/pricing2 (and totalCost on both), so it re-prices when PricingCache primes

### Bug 27 — Severity: LOW — Category: missing persistence
**Location:** DualChatScreen.kt:360-631 (whole session)
**Symptom:** Dual-chat conversations are never written to `ChatHistoryManager`; they cannot be resumed, searched, or reviewed after leaving, and their cost vanishes from the screen on exit.
**Root cause:** The session lives only in screen state (Saver) — no `saveSession` equivalent.
**Proposed fix:** Persist completed dual-chat runs (even as a read-only history entry) if review is desired.
**Status:** Open

### Bug 28 — Severity: LOW — Category: trace mis-association
**Location:** DualChatScreen.kt:655-661 (`DualMessageBubble` trace lookup)
**Symptom:** When Model 1 and Model 2 are the *same* (provider, model), each bubble's 🐞 can resolve to the other turn's trace.
**Root cause:** The lookup filters by `reportId == sessionId && model == msg.modelName` then picks the closest timestamp; with identical model names the only discriminator is timestamp, which aliases when both turns are close.
**Proposed fix:** Tag each turn's trace with the modelIndex (or a per-turn id) and filter on it.
**Status:** Open

### Bug 29 — Severity: LOW — Category: input validation
**Location:** DualChatScreen.kt:264-268, 581-585 (`interactionCount` / `extraChatsText` text fields)
**Symptom:** The Rounds / Extra-chats fields accept arbitrary text (no numeric keyboard, no digit filter); a non-numeric value silently disables the button with no feedback.
**Root cause:** Plain `OutlinedTextField` with `toIntOrNull()` parsing; no `keyboardType = Number` or digit filtering (unlike `ChatManageScreen.daysText`).
**Proposed fix:** Add a numeric keyboard + digit filter.
**Status:** Fixed (2026-06-07) — Rounds and Extra-chats now use a numeric keyboard and digit-only input filtering

### Bug 30 — Severity: LOW — Category: bundle size limit (unconfirmed)
**Location:** DualChatScreen.kt:87-119 (`DualMessagesSaver`)
**Symptom:** A long, content-heavy dual chat can exceed the ~1 MB `TransactionTooLargeException`/Bundle ceiling on saved-instance-state, losing the conversation on recreation.
**Root cause:** The whole conversation is flattened into the saved-state Bundle (acknowledged in the doc comment). Combined with Bug 23 (config lost anyway), the Saver rarely helps.
**Proposed fix:** Back the conversation with a temp file / disk store rather than the Bundle.
**Status:** Open

### Bug 31 — Severity: LOW — Category: persistence timing
**Location:** DualChatScreen.kt:168-189 (`savePrefs` + `DisposableEffect(Unit){ onDispose { savePrefs() } }`)
**Symptom:** Dual-chat setup field edits are only flushed to prefs on dispose (or on Go). A process kill while the setup screen is foreground loses in-progress edits to subject / prompts / rounds.
**Root cause:** No incremental save; only `onDispose` and the Go handler call `savePrefs()`.
**Proposed fix:** Use `rememberSaveable` for the setup fields, or save on change (debounced).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/chat/ChatHub.kt

### Bug 32 — Severity: LOW — Category: performance
**Location:** ChatHub.kt:63-80 (`allSessionsForHub` + `unfinishedSessions`)
**Symptom:** The hub loads and parses **every** chat session JSON into memory on each `historyVersion` change, then iterates all of them to compute pinned / recent / unfinished. With a large history this is a heavy disk + parse pass just to render three short lists.
**Root cause:** `getAllSessionsAsync()` returns full `ChatSession` objects (including image blobs) for the whole history; the hub only needs lightweight headers.
**Proposed fix:** Add a lightweight session-header projection (id, title, preview, pinned, updatedAt, lastRole) to `ChatHistoryManager`.
**Status:** Open

### Bug 33 — Severity: LOW — Category: redundant I/O
**Location:** ChatHub.kt:64-69 (`hasChatHistory` + `allSessionsForHub`)
**Symptom:** Two separate `produceState` blocks both hit disk on the same `historyVersion`: one counts sessions, the other loads them all.
**Root cause:** `hasChatHistory` could be derived from `allSessionsForHub.isNotEmpty()` instead of a separate `getSessionCountAsync()` call.
**Proposed fix:** Derive `hasChatHistory` from the already-loaded list.
**Status:** Open

### Bug 34 — Severity: LOW — Category: UX
**Location:** ChatHub.kt:92-98, 215-233 (`UnfinishedChatPill`)
**Symptom:** When several chats are "awaiting reply", the pill always resumes only `unfinishedSessions.first()` (newest), with no way to pick which one.
**Root cause:** The pill exposes a single `onResume = { onResumeSession(unfinishedSessions.first().id) }`.
**Proposed fix:** Either route to a filtered list, or document that it resumes the most recent.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/chat/ChatManageScreen.kt

### Bug 35 — Severity: LOW — Category: storage leak
**Location:** ChatManageScreen.kt:177-197 (`zipAllChats`)
**Symptom:** Each "Export all" writes a new `ai_chats_backup_<ts>.zip` into `cacheDir/chat_backup` and never deletes prior exports; repeated exports accumulate cache files.
**Root cause:** No cleanup of the output directory before/after writing.
**Proposed fix:** Clear `chat_backup/` (or delete old zips) before writing the new archive.
**Status:** Fixed (2026-06-07) — chat export now clears old files from the dedicated `chat_backup` cache directory before writing the new zip

### Bug 36 — Severity: LOW — Category: state loss
**Location:** ChatManageScreen.kt:48 (`var daysText by remember { mutableStateOf("30") }`)
**Symptom:** Rotation resets the "older than (days)" field back to 30.
**Root cause:** Plain `remember`.
**Proposed fix:** `rememberSaveable`.
**Status:** Fixed (2026-06-07) — the days field now uses `rememberSaveable`, so rotation preserves the typed value

---

## File: ai/src/main/java/com/ai/ui/chat/ChatHistory.kt

### Bug 37 — Severity: LOW — Category: performance
**Location:** ChatHistory.kt:87-91 (`hasTraces` per-row `produceState`)
**Symptom:** Each visible history row independently calls `ApiTracer.getTraceFiles()` to decide whether to show its 🐞, so one page does (rows × full-trace-dir-scan) work.
**Root cause:** Per-row trace probe with no shared load.
**Proposed fix:** Load the set of session ids that have traces once per page and look up locally.
**Status:** Open

### Bug 38 — Severity: LOW — Category: text slicing (documented)
**Location:** ChatHistory.kt:155-171 (`searchInChats` preview windowing)
**Symptom:** When case-folding changes string length (ß→ss, some Greek/Turkic forms), the highlighted preview window is offset from the actual match (no crash — indices are coerced).
**Root cause:** Match offset is computed on the lower-cased copy but the slice is taken from the original; the comment acknowledges this.
**Proposed fix:** Compute the window on the original string via a case-insensitive `indexOf`.
**Status:** Fixed (2026-06-07) — preview offsets now come from `message.content.indexOf(query, ignoreCase = true)` on the original string

### Bug 39 — Severity: LOW — Category: performance
**Location:** ChatHistory.kt:148-185 (`searchInChats`)
**Symptom:** Every search re-lowercases the entire content of every message of every session (`message.content.lowercase(...)` in the inner loop) for each query.
**Root cause:** No precomputed lower-cased index; full re-scan per query.
**Proposed fix:** Acceptable for small histories; for large ones, cache lower-cased haystacks or short-circuit on title/preview first.
**Status:** Open

### Bug 40 — Severity: LOW — Category: state loss
**Location:** ChatHistory.kt:196 (`var searchResults by remember`)
**Symptom:** Search results are dropped on rotation; the screen briefly shows "No matches" until the `LaunchedEffect(historyVersion, hasSearched)` re-runs the search.
**Root cause:** `searchResults` is plain `remember` while `searchQuery`/`hasSearched` are saveable, so the trio is inconsistent across recreation.
**Proposed fix:** Re-run search deterministically on restore, or persist a lightweight result set.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/KnowledgeService.kt (RAG path used by Chat + Knowledge UI)

### Bug 41 — Severity: MEDIUM — Category: index-out-of-bounds / partial embedder response
**Location:** KnowledgeService.kt:138-181 (`indexFile` embedding loop + `pieces.mapIndexed { i -> vectors[i] }`)
**Symptom:** If the embedder returns **fewer vectors than input chunks** (partial batch / deduped rows), indexing throws `IndexOutOfBoundsException` at `vectors[i]` for `i >= vectors.size`, surfacing as a cryptic "Failed: IndexOutOfBoundsException" rather than a clear count-mismatch error. (Caught by the `runCatching` wrapper, so no crash, but the source is left un-indexed with a confusing message.)
**Root cause:** The validation loop (`vectors.forEachIndexed { … if (v.size != embeddingDim) error(…) }`) checks each *returned* vector's dimension but never checks `vectors.size == pieces.size`. The subsequent `pieces.mapIndexed { i, t -> … vectors[i] … }` then over-indexes. Note the cloud/local *search* screens already guard this exact case with `vecs.getOrNull(j) ?: continue` — the indexing path does not.
**Reproduction:** Use an embedder/provider that returns fewer rows than inputs for one batch → indexing fails with an opaque error.
**Proposed fix:** After the embed loop, assert `vectors.size == pieces.size` and `error("Embedder returned ${vectors.size} vectors for ${pieces.size} chunks")`, or zip defensively with `getOrNull`.
**Status:** Fixed (2026-06-07) - assert vectors.size == pieces.size before the mapIndexed, with a clear count-mismatch error instead of IndexOutOfBounds

---

## File: ai/src/main/java/com/ai/ui/knowledge/KnowledgeScreens.kt

### Bug 42 — Severity: LOW — Category: main-thread I/O
**Location:** KnowledgeScreens.kt:242-247 (`onClick` → `KnowledgeStore.createKnowledgeBase`)
**Symptom:** Creating a KB runs the manifest mkdir + JSON write on the main thread in the button's onClick.
**Root cause:** `KnowledgeStore.createKnowledgeBase(...)` is called directly (not in `withContext(Dispatchers.IO)`), unlike every other store mutation in this file.
**Proposed fix:** Wrap the create in `scope.launch(Dispatchers.IO) { … }` then navigate on completion.
**Status:** Fixed (2026-06-07) — KB creation now runs in a coroutine with the manifest write on `Dispatchers.IO`, guarded against duplicate taps

### Bug 43 — Severity: LOW — Category: coroutine lifecycle
**Location:** KnowledgeScreens.kt:311-313, 327-329, 353-354, 463-465 (progress callbacks `scope.launch(Dispatchers.Main) { status = … }`)
**Symptom:** Progress updates are fire-and-forget `scope.launch(Main)` from inside `withContext(IO)`; if the ingest is cancelled, in-flight progress launches can still run and write `status` (the search screens deliberately avoid this by hopping on the same coroutine).
**Root cause:** Each `onProgress` spawns a detached Main coroutine instead of `withContext(Dispatchers.Main)`.
**Proposed fix:** Use `withContext(Dispatchers.Main)` for progress writes (lifecycle-bound), matching `SemanticSearchScreen`.
**Status:** Open

### Bug 44 — Severity: LOW — Category: type detection
**Location:** KnowledgeScreens.kt:521-550 (`pickTypeForUri`)
**Symptom:** Any unknown/binary file (e.g. a `.zip`, image, or proprietary doc) falls through to `KnowledgeSourceType.TEXT` and is "indexed" as garbage text rather than rejected.
**Root cause:** The final `else` returns `TEXT` for everything, including non-text MIME types.
**Proposed fix:** Return null/refuse for clearly-binary MIME types and surface "unsupported source type".
**Status:** Fixed (2026-06-07) — unsupported/unknown and common binary MIME types now return null, and ingest shows an unsupported-source status instead of indexing as text

### Bug 45 — Severity: LOW — Category: state loss
**Location:** KnowledgeScreens.kt:166, 178 (`name`, `selected` via plain `remember`)
**Symptom:** Rotating the New-KB screen clears the typed name and resets the embedder pick.
**Root cause:** Plain `remember`; only `resumeTick`-keyed lists are reactive.
**Proposed fix:** `rememberSaveable` for `name`; persist the selected option key.
**Status:** Fixed (2026-06-07) — the New-KB name and provider/model selection key now use saveable state

### Bug 46 — Severity: LOW — Category: reactivity
**Location:** KnowledgeScreens.kt:297-343 (auto-ingest `LaunchedEffect(kb?.id, pendingUris)` + `refreshTick++` inside the loop)
**Symptom:** During multi-file share-ingest, the in-loop `refreshTick++` reloads `kb` via `produceState`, but the loop keeps using the captured `loaded` snapshot, so the "N sources" header can lag the actual ingest until the loop finishes.
**Root cause:** `loaded` is captured once at loop start; `refreshTick` reload only affects the displayed `kb`, not the working copy.
**Proposed fix:** Acceptable, but consider refreshing the header from the result list rather than re-reading the KB mid-loop.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/models/ModelScreens.kt

### Bug 47 — Severity: LOW — Category: dead code
**Location:** ModelScreens.kt:1330-1331 (`if (provider != null) { }`)
**Symptom:** An empty `if` block that does nothing.
**Root cause:** Leftover from a refactor; the body was moved into the second `if (provider != null)` immediately below.
**Proposed fix:** Delete the empty block.
**Status:** Open

### Bug 48 — Severity: LOW — Category: race / duplicate fetch
**Location:** ModelScreens.kt:137-159 (`ModelInfoCache`)
**Symptom:** Two concurrent Model-Info opens with the same key can both miss the cache and fire duplicate OpenRouter `/models` fetches.
**Root cause:** The `@Volatile`-fielded object has no mutex/in-flight dedup around the network call.
**Proposed fix:** Guard the fetch with a `Mutex`/in-flight `Deferred`.
**Status:** Open

### Bug 49 — Severity: LOW — Category: trace conflation
**Location:** ModelScreens.kt:233-251 (`traceCount` host match)
**Symptom:** For a non-LOCAL provider whose `baseUrl` host can't be parsed (null), the count falls back to model-name-only matching, conflating same-named models across providers — the exact bug the host match was added to prevent.
**Root cause:** `providerHost == null || tf.hostname.equals(providerHost)` — a null host disables the host filter entirely.
**Proposed fix:** When the host can't be derived for a non-LOCAL provider, count nothing (or log) rather than matching by model name alone.
**Status:** Open

### Bug 50 — Severity: LOW — Category: main-thread I/O
**Location:** ModelScreens.kt:489-491, 666-685, 815-817 (`getManualPricing`, `getLiteLLMRawEntry`, `getTierBreakdown`, etc. in `remember`)
**Symptom:** Several disk-backed pricing-cache reads run synchronously inside `remember(provider, modelName)` on the main thread during composition; on a cold cache these can hitch the screen open.
**Root cause:** They are in `remember` blocks, not `produceState`/IO (unlike the OR/HF/usage lookups which were moved off-thread).
**Proposed fix:** Move the raw-entry / breakdown reads into `produceState(...) { withContext(IO) { … } }`.
**Status:** Open

### Bug 51 — Severity: LOW — Category: cosmetic
**Location:** ModelScreens.kt:1408-1416 (`colorizeJson` bareword matching)
**Symptom:** In malformed JSON, a token like `truething` would color `true` then continue — keyword matching has no trailing word-boundary check.
**Root cause:** `regionMatches(i, "true", 0, 4)` without verifying the next char is a delimiter.
**Proposed fix:** Require the following char to be non-alphanumeric. (Cosmetic; JSON-only.)
**Status:** Open

### Bug 52 — Severity: LOW — Category: redundant I/O
**Location:** ModelScreens.kt:252-272 (`usageEntry` builds a fresh `SettingsPreferences(prefs, filesDir)`)
**Symptom:** Reading usage stats constructs a brand-new `SettingsPreferences` instance and calls `loadUsageStats()`, bypassing the app's cached singleton and re-reading the stats file from disk.
**Root cause:** The screen instantiates its own `SettingsPreferences` rather than using the shared one (also in ModelInfoViewScreen).
**Proposed fix:** Reuse the shared `SettingsPreferences`/cache.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/models/ModelInfoViewScreen.kt

### Bug 53 — Severity: MEDIUM — Category: unintended paid call
**Location:** ModelInfoViewScreen.kt:289-293 (`LaunchedEffect(introCacheKey)` auto-fire) + 259-283 (`requestIntroduction`)
**Symptom:** Opening the read-only Model-Info **View** screen for any model that has never been introduced automatically fires a paid self-introduction call — browsing models silently spends money. (The Manage screen, by contrast, shows an "Ask" button and never auto-fires.) Worse, if the call *fails* (no cache written), every subsequent open or rotation re-fires it (`raw == null && canRequestIntro → requestIntroduction()`).
**Root cause:** The View screen auto-requests when there's no cached intro; failures don't write a cache, so the auto-fire repeats.
**Reproduction:** Open View → Model Info for a fresh model with a working API key → a billed call goes out without any user action; if it errors, rotate → another billed call.
**Proposed fix:** Require an explicit tap (as Manage does), or write a negative/cooldown cache marker on failure so it doesn't re-fire on every open.
**Status:** Fixed (2026-06-07) — ModelInfoViewScreen now only loads cached introductions on open; missing intros show an explicit ask action before any paid self-introduction call is made

### Bug 54 — Severity: LOW — Category: duplicate cache / extra fetch
**Location:** ModelInfoViewScreen.kt:1035-1055 (`ModelInfoLookupCache`) vs ModelScreens.kt:137-159 (`ModelInfoCache`)
**Symptom:** The View and Manage Model-Info screens keep two separate per-process OpenRouter caches, so navigating between them can trigger a second full `/models` fetch even though the data is identical.
**Root cause:** Deliberately-private duplicate caches (per the comment) that don't share state.
**Proposed fix:** Share one process-level OR-models cache between the two screens.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/search/LocalSearchScreen.kt

### Bug 55 — Severity: MEDIUM — Category: locale / Unicode matching
**Location:** LocalSearchScreen.kt:136-143 (`Regex("\\b" + Regex.escape(it) + "\\b")`)
**Symptom:** Tokenized local search silently fails to match any token containing non-ASCII letters — accented words (café, schöne) and non-Latin scripts (CJK, Cyrillic, Greek) score 0 and never appear, even when the term is present.
**Root cause:** Java/Kotlin `\b` is defined against ASCII `\w` (`[A-Za-z0-9_]`) by default. For a token like `café`, the trailing `\b` sits between `é` (non-word) and the next char and never matches; for CJK tokens neither boundary matches. The regex is compiled without `UNICODE_CHARACTER_CLASS`.
**Reproduction:** On the nl-NL device, search a report containing "café" for `café` → no results.
**Proposed fix:** Use Unicode-aware boundaries (`Pattern.UNICODE_CHARACTER_CLASS` / `(?U)`), or fall back to substring matching for tokens with non-ASCII letters.
**Status:** Fixed (2026-06-07) - localSearchTokenRegex uses \b only for ASCII tokens; non-ASCII tokens (café/CJK/Cyrillic) fall back to substring matching

---

## File: ai/src/main/java/com/ai/ui/search/SemanticSearchScreen.kt

### Bug 56 — Severity: LOW — Category: ranking logic
**Location:** SemanticSearchScreen.kt:259-262 (`.sortedByDescending{score}.take(10).filter{ it.score > 0.0 }`)
**Symptom:** The result list can contain fewer than 10 hits even when more than 10 reports have positive similarity, because the `> 0.0` filter is applied *after* `take(10)` — any zero/negative scores in the top 10 are dropped without being backfilled from rank 11+.
**Root cause:** Filter ordered after the take.
**Proposed fix:** `.filter { it.score > 0.0 }.take(10)`.
**Status:** Fixed (2026-06-07) — positive-score filtering now happens before the top-10 truncation

### Bug 57 — Severity: LOW — Category: provider coverage
**Location:** SemanticSearchScreen.kt:204-212 (`supportedEmbeddingChoices`)
**Symptom:** Only `ApiFormat.OPENAI_COMPATIBLE` providers expose embedding models for semantic search; embedding-capable Google (Gemini) and Anthropic-format providers are excluded even if a model is marked EMBEDDING.
**Root cause:** `if (service.apiFormat != ApiFormat.OPENAI_COMPATIBLE) return@flatMap emptyList()`.
**Proposed fix:** Document the MVP limit clearly, or route the non-OpenAI formats through their embedding endpoints.
**Status:** Open

### Bug 58 — Severity: LOW — Category: state loss
**Location:** SemanticSearchScreen.kt:74-78 (`query`, `results` via plain `remember`)
**Symptom:** Rotation clears the query and results; the user must re-run the (paid) embedding search.
**Root cause:** Plain `remember`.
**Proposed fix:** `rememberSaveable` the query; re-derive or persist results.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/search/LocalSemanticSearchScreen.kt

### Bug 59 — Severity: LOW — Category: trace selection (unconfirmed)
**Location:** LocalSemanticSearchScreen.kt:72-83 (`latestTrace` keyed only on `running`)
**Symptom:** Before the first search (`searchStartedAt == 0L`), the `produceState` runs with `running=false` and would pick the newest "Local semantic search" trace with `timestamp >= 0` — i.e. the most recent trace from a *previous* run could surface as the title-bar 🐞 even though no search has happened this session.
**Root cause:** `latestTrace` is computed on entry (running starts false) using `searchStartedAt = 0`, so the timestamp guard is a no-op on the first pass.
**Proposed fix:** Gate the lookup on "a search has completed this session" (e.g. `searchStartedAt > 0`).
**Status:** Open

### Bug 60 — Severity: LOW — Category: ranking logic
**Location:** LocalSemanticSearchScreen.kt:246-249 (`.take(10).filter{ it.score > 0.0 }`)
**Symptom:** Same post-`take` filter ordering as Bug 56 — can return fewer than 10 results when zero-score items occupy top ranks.
**Root cause:** Filter after take.
**Proposed fix:** `.filter { it.score > 0.0 }.take(10)`.
**Status:** Fixed (2026-06-07) — local semantic search now filters positive scores before taking the top 10

---

## File: ai/src/main/java/com/ai/ui/search/QuickLocalSearchScreen.kt

### Bug 61 — Severity: LOW — Category: search coverage
**Location:** QuickLocalSearchScreen.kt:131-138 (`runQuickSearch`)
**Symptom:** Quick search matches `Report.prompt` and agent response bodies but **not** the report title; a word that appears only in the (displayed) title returns no hit, which is confusing since the result row shows the title.
**Root cause:** `matchesPrompt`/`matchesAnyResponse` never test `r.title` (the other two search screens include it).
**Proposed fix:** Add `r.title.contains(needle, ignoreCase = true)` to the predicate.
**Status:** Open

### Bug 62 — Severity: LOW — Category: redundant work
**Location:** QuickLocalSearchScreen.kt:127, 132-135 (`needle = word.lowercase(ROOT)` then `contains(needle, ignoreCase = true)`)
**Symptom:** The needle is pre-lowercased *and* matched with `ignoreCase = true`, which double-handles casing and can subtly interact (lowercased needle vs locale-aware case-insensitive compare).
**Root cause:** Redundant case handling.
**Proposed fix:** Drop the `lowercase` (rely on `ignoreCase`) or drop `ignoreCase` (rely on the pre-lowercased needle against a lowercased haystack).
**Status:** Fixed (2026-06-07) — quick search now trims the query and relies on `contains(..., ignoreCase = true)` for casing

---

## File: ai/src/main/java/com/ai/ui/history/HistoryScreen.kt

### Bug 63 — Severity: LOW — Category: stale-list edge case
**Location:** HistoryScreen.kt:146-160 (delete handler) + 67-77 (`filteredReports` debounce)
**Symptom:** While a search filter is active, deleting a row computes `remaining = filteredReports.size - 1` from a `filteredReports` that lags `allReports` by the 250 ms debounce, so the page-clamp math can use a stale count; the deleted row also stays visible until the debounce fires.
**Root cause:** `filteredReports` is produced asynchronously (debounced) from `allReports`; the synchronous delete handler reads the not-yet-recomputed value.
**Proposed fix:** Recompute the remaining count from `allReports` minus the deleted id, or clamp reactively only.
**Status:** Open

### Bug 64 — Severity: LOW — Category: performance
**Location:** HistoryScreen.kt:67-77 (Response filter)
**Symptom:** The "Response" search scans every agent's `responseBody` (potentially MB each) across the whole history on each (debounced) keystroke.
**Root cause:** Full-text scan over all reports; debounced + off-main but still O(total response bytes) per query.
**Proposed fix:** Acceptable with the debounce; consider an index if histories grow large.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/history/PromptHistoryScreen.kt

### Bug 65 — Severity: LOW — Category: layout flicker
**Location:** PromptHistoryScreen.kt:59-68 (`BoxWithConstraints` paging math)
**Symptom:** On the pre-measure frame `maxHeight` is 0, so `pageSize` momentarily computes to 1 and the page count flickers — the sibling `HistoryScreen` guards this (`if (maxHeight.value <= 0f) return`), but `PromptHistoryScreen` does not.
**Root cause:** Missing pre-measure guard.
**Proposed fix:** Add the same `if (maxHeight.value <= 0f) return@BoxWithConstraints` guard.
**Status:** Open

### Bug 66 — Severity: LOW — Category: reactivity
**Location:** PromptHistoryScreen.kt:47-48 (`overrideEntries ?: loaded`)
**Symptom:** After "Clear" sets `overrideEntries = emptyList()`, the screen is permanently pinned to the override; if prompt history is repopulated elsewhere while the screen is open, it won't reflect new entries (the override masks `loaded`).
**Root cause:** The `overrideEntries` override is sticky with no invalidation.
**Proposed fix:** Reset `overrideEntries` to null on resume/refresh, or reload `loaded` after a clear.
**Status:** Open

### Bug 67 — Severity: LOW — Category: state loss
**Location:** PromptHistoryScreen.kt:43-46 (`loaded` plain `remember` + `LaunchedEffect(Unit)`)
**Symptom:** Rotation reloads the prompt-history file (re-parse) because `loaded` is plain `remember`.
**Root cause:** Not `rememberSaveable`; benign but redundant disk work.
**Proposed fix:** Acceptable; could cache via the shared prefs cache.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/history/ExamplePromptPickerScreen.kt

### Bug 68 — Severity: LOW — Category: list key collision (unconfirmed)
**Location:** ExamplePromptPickerScreen.kt:80 (`items(sorted, key = { it.id })`)
**Symptom:** Two example prompts that share an `id` (possible after a duplicate/import) would crash Compose ("key already used").
**Root cause:** Keyed solely on `id` with no uniqueness guarantee across user-editable/imported data.
**Proposed fix:** Fall back to index-disambiguated keys, or dedupe ids on load.
**Status:** Open

### Bug 69 — Severity: LOW — Category: search coverage
**Location:** ExamplePromptPickerScreen.kt:37-44 (`filtered`)
**Symptom:** Search matches title + full text but the row preview only shows the first line of text; a match deep in a multi-line prompt shows a row whose preview doesn't contain the query (minor confusion).
**Root cause:** Preview is `text.lineSequence().firstOrNull()` while search scans the whole text.
**Proposed fix:** Show a match-centered snippet (as ChatSearch does).
**Status:** Open

---

## Cross-cutting / additional

### Bug 70 — Severity: LOW — Category: concurrency (unconfirmed)
**Location:** ChatHistoryManager.kt:56-60 (`notifyHistoryChanged()` called inside `lock.withLock`)
**Symptom:** `saveSession` fires `notifyHistoryChanged()` *inside* the lock, whereas `deleteSession` deliberately fires it *outside* (per its comment). If a collector ever synchronously re-entered the manager from within the StateFlow update, this asymmetry could deadlock.
**Root cause:** Inconsistent placement of the notify relative to the lock between save and delete.
**Root mitigation:** StateFlow resumes collectors on their own dispatchers, so no synchronous re-entry happens today — hence LOW/unconfirmed.
**Proposed fix:** Move `notifyHistoryChanged()` outside the `withLock` in `saveSession` for consistency.
**Status:** Open

### Bug 71 — Severity: LOW — Category: cache coherence (unconfirmed)
**Location:** ChatHistoryManager.kt:88-101 (`getAllSessions` cache) + 30-66 (`saveSession`)
**Symptom:** `cachedSessions` is invalidated on save/delete, but `setSessionPinned` does load→save as two separate locked ops; a reader between them sees the pre-pin list. Combined with Bug 11 this widens the lost-update window.
**Root cause:** No single-transaction update path for in-place mutations.
**Proposed fix:** Provide an atomic `mutateSession(id) { it.copy(...) }` under one lock acquisition.
**Status:** Open

### Bug 72 — Severity: LOW — Category: search/index dim mismatch (unconfirmed)
**Location:** SemanticSearchScreen.kt:259-262 / LocalSemanticSearchScreen.kt:246-249 (`EmbeddingsStore.cosine(queryVec, c.vec)`)
**Symptom:** If a cached report embedding was produced by a model that was later replaced by a different model of the *same name* but a different output dimension, every cached report silently scores 0.0 (cosine returns 0 on dim mismatch) and vanishes from results with no surfaced reason.
**Root cause:** The embeddings cache key is `(docId, providerId, model, contentHash)` — it doesn't capture the embedding dimension, so a same-name/different-dim swap isn't detected; `cosine` then returns 0.0 (graceful, but invisible).
**Proposed fix:** Include the embedding dim in the cache key or invalidate on dim change; surface a "re-index needed" hint.
**Status:** Open

### Bug 73 — Severity: LOW — Category: knowledge attach visibility
**Location:** ChatScreens.kt:690-698 (KB chip gated on `experimentalFeatures`)
**Symptom:** When the master Experimental toggle is off, an already-attached KB still injects RAG context at send time, but the chip is hidden — the user can neither see nor remove the attachment.
**Root cause:** Visibility gated on `experimentalFeatures` while the dispatch path is not (documented as intentional).
**Proposed fix:** Either always show the chip when a KB is already attached, or strip attachments when experimental is off.
**Status:** Open

### Bug 74 — Severity: LOW — Category: state loss
**Location:** ChatScreens.kt:63-73 (ChatParametersScreen free-text fields)
**Symptom:** Rotation wipes every typed parameter on the Chat Parameters setup screen (system prompt, temperature, max tokens, top P, top K, frequency/presence penalty, search recency) — only the preset-id selections and dialog flags are saveable.
**Root cause:** These fields use plain `remember` (only `selectedSystemPromptId`/`selectedParametersIds`/dialog flags are `rememberSaveable`).
**Proposed fix:** `rememberSaveable` the free-text fields.
**Status:** Fixed (2026-06-07) — Chat Parameters setup fields now use `rememberSaveable`, including the citation toggle

### Bug 75 — Severity: LOW — Category: performance
**Location:** DualChatScreen.kt:655-661 (`DualMessageBubble` per-bubble `produceState` over `ApiTracer.getTraceFiles()`)
**Symptom:** Like Bug 22, every dual-chat bubble independently scans the whole trace dir to gate its 🐞.
**Root cause:** Per-bubble trace lookup with no shared/batched load.
**Proposed fix:** Load the session's traces once and pass down a map.
**Status:** Open

### Bug 76 — Severity: LOW — Category: cost accuracy (unconfirmed)
**Location:** ChatScreens.kt:537-539 (`toolOverhead` added to estimated input tokens)
**Symptom:** When web search is on, a fixed LiteLLM tool-use overhead is added to the estimated input token count and billed to the cost banner regardless of whether the provider actually used the tool that turn, slightly over-counting input cost on turns where no search ran.
**Root cause:** `toolOverhead` is added unconditionally whenever `useWebSearch` is true, not conditioned on the response actually invoking the tool.
**Proposed fix:** Add the overhead only when the response indicates a tool call (where the dispatch exposes that), or document it as an upper-bound estimate.
**Status:** Open
