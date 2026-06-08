# Chat Bugs

### Bug 1 - Severity: Medium - Category: Main-thread session load
**Location:** `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt:296-299` (`initialMessagesForSession`)

**Symptom:** Opening a large chat session can block first composition.

**Root cause:** `ChatHistoryManager.loadSession(currentSessionId)` runs
inside `remember` on the Compose thread.

**Reproduction:** Create or import a large image-heavy chat session, then
open it from history. The screen must parse the session JSON before first
paint.

**Proposed fix:** Load the session with `produceState` or view-model state
on `Dispatchers.IO`, with a lightweight loading state.

**Status:** Fixed — 22d9438d (load the session once, off the main thread (chat bugs 1, 2))

### Bug 2 - Severity: Medium - Category: Duplicate main-thread session load
**Location:** `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt:439-457` (`persistedSession`)

**Symptom:** The same chat session can be parsed twice on the main thread
when opening a session.

**Root cause:** The screen first loads messages at lines 296-299, then
loads the full `persistedSession` again at line 443 for pinned state,
knowledge bases, and title.

**Reproduction:** Open an existing large session. Both remembered values
call into `ChatHistoryManager.loadSession`.

**Proposed fix:** Load one session object off-main and derive messages,
pinned state, knowledge-base ids, and title from that single object.

**Status:** Fixed — 22d9438d (load the session once, off the main thread (chat bugs 1, 2))

### Bug 3 - Severity: Medium - Category: Main-thread knowledge-base list
**Location:** `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt:455-457` (`availableKbs`)

**Symptom:** Chat first paint can block when many knowledge bases exist.

**Root cause:** `KnowledgeStore.listKnowledgeBases(context)` runs inside a
`remember` block on the Compose thread.

**Reproduction:** Create many knowledge bases, open chat, and tap/open the
knowledge-base chip path. The file scan is not dispatched to IO.

**Proposed fix:** Load knowledge-base metadata with `produceState` on
`Dispatchers.IO` and show an empty/loading picker until it completes.

**Status:** Fixed — 65a716c7 (load knowledge-base list off the main thread)

### Bug 4 - Severity: Medium - Category: Per-turn option persistence
**Location:** `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt:342-350` (`useWebSearch`, `reasoningEffort`)

**Symptom:** A user can lose unsent web-search/reasoning changes on
rotation before the first user turn is persisted.

**Root cause:** `useWebSearch` and `reasoningEffort` are plain `remember`
states. Persistence is delayed until a session has at least one user
message.

**Reproduction:** Open a new chat, toggle web search or reasoning effort,
rotate the device before sending. The options reset to the initial
parameters.

**Proposed fix:** Use `rememberSaveable(currentSessionId)` for these
per-turn UI states.

**Status:** Fixed — b96e191d (persist web-search / reasoning toggles across rotation)

### Bug 5 - Severity: Low - Category: Moderation picker state
**Location:** `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt:374-378` (`moderationModel`, `showModerationPicker`)

**Symptom:** A selected moderation model or open moderation picker can be
lost on configuration change.

**Root cause:** Moderation state is plain `remember` and has no saver for
provider/model id.

**Reproduction:** Select a moderation model, rotate the device before
sending another chat message.

**Proposed fix:** Store provider id/model id in `rememberSaveable` and
re-resolve the provider on restore.

**Status:** Open

### Bug 6 - Severity: Medium - Category: Reasoning capability refresh
**Location:** `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt:365-373` (`supportsReasoning`)

**Symptom:** A reasoning-capable model can hide the reasoning selector for
the lifetime of the screen if pricing/capability metadata loads after first
composition.

**Root cause:** `supportsReasoning` is remembered only by provider/model.
It reads `PricingCache` capability maps but is not keyed by the same
pricing refresh tick used for pricing at lines 422-423.

**Reproduction:** Start chat during a cold pricing/capability cache window
with a reasoning model that is not covered by the string heuristics.

**Proposed fix:** Key `supportsReasoning` by the pricing/cache refresh tick
or expose a capability state flow from `PricingCache`.

**Status:** Fixed — 382bcb2a (re-evaluate reasoning-capability when pricing cache primes)

### Bug 7 - Severity: Medium - Category: Moderation coroutine error handling
**Location:** `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt:719-747` (`trySend`)

**Symptom:** An unexpected moderation exception can escape the launched
coroutine instead of fail-opening the chat send path.

**Root cause:** The moderation coroutine has a `finally` block but no
`catch` around `callModerationApi` or trace wrapping. The comments promise
API errors surface in `moderationError` and the message is sent anyway, but
that only covers returned errors, not thrown exceptions.

**Reproduction:** Force `callModerationApi` or trace capture to throw
before returning an `apiResult`. The coroutine ends through `finally` and
does not call `actuallySend`.

**Proposed fix:** Add a non-cancellation catch that sets `moderationError`,
sets `handedOffToSend = true`, and calls `actuallySend(input, img)`.

**Status:** Fixed — 647a7e7e (fail-open on a thrown moderation error)

### Bug 8 - Severity: Medium - Category: Chat route main-thread load
**Location:** `ai/src/main/java/com/ai/ui/navigation/ChatRoutes.kt:222-230`

**Symptom:** Navigating from chat history into a session can block route
composition before the chat screen is even mounted.

**Root cause:** The route calls `ChatHistoryManager.loadSession(sessionId)`
inside `remember(sessionId)` on the Compose thread.

**Reproduction:** Open a large saved session from Chat History. The route
parses the full JSON synchronously.

**Proposed fix:** Move session loading to IO-backed state and pass a loading
state or session id into the screen.

**Status:** Fixed — 4fa1ff52 (load session off-main in the continue route)

### Bug 9 - Severity: Medium - Category: Dual chat route state
**Location:** `ai/src/main/java/com/ai/ui/chat/DualChatScreen.kt:405-408`

**Symptom:** A dual-chat session route can bounce back after process
recreation because its config is not route-persisted.

**Root cause:** The running screen reads `dualChatConfig` from current
`UiState` via `remember`. If the process is recreated and `UiState` does not
carry the staged config, the screen navigates back.

**Reproduction:** Start a dual-chat session, background/kill/recreate the
process while on the session route.

**Proposed fix:** Persist the config in the navigation route arguments or a
saved session store, then restore it by id.

**Status:** Open

### Bug 10 - Severity: Medium - Category: Dual chat setup state
**Location:** `ai/src/main/java/com/ai/ui/chat/DualChatScreen.kt:163-180`

**Symptom:** Dual-chat model/parameter/system-prompt selections can reset
on rotation if it happens before the delayed preference save completes.

**Root cause:** Provider/model/parameter/system-prompt states are plain
`remember` values initialized from SharedPreferences. The save runs after a
350 ms delay in `LaunchedEffect`.

**Reproduction:** Change a model or parameter selection in Dual Chat setup,
then rotate immediately.

**Proposed fix:** Use `rememberSaveable` for all setup selections and keep
the delayed preference save as a persistence side effect.

**Status:** Open

