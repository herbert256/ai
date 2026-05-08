# Settings / Admin / Trace / Housekeeping — Bug review

## File: ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt

### Bug 1 — Severity: HIGH — Category: state/recomposition
**Location:** lines 79-81 (`SettingsScreen` outer composable, `selectedProvider` declaration)
**Symptom:** Whenever `ProviderRegistry` changes size (Add provider, Import providers, Reset), `selectedProvider` state is reset back to `initialProviderId?.let { AppService.findById(it) }`. Any time the user adds a provider while a deep-link `initialProviderId` is non-null, the currently-selected runtime selection is silently replaced — bouncing them out of any open `AI_PROVIDER_EDIT` to `goBack()`.
**Root cause:** `remember(initialProviderId, AppService.entries.size) { mutableStateOf(...) }` keys state on a value (`AppService.entries.size`) that can change at runtime. When the key changes, Compose recreates the `MutableStateOf` from `initialProviderId`, dropping the runtime-set selection.
**Reproduction:** Open Provider Add → save a new provider → AI_PROVIDER_EDIT for the new provider → go back → import a providers.json → registry size changes → user gets bounced.
**Proposed fix:** Don't re-key on registry size. Re-look-up `selectedProvider` lazily by id, or only re-init when `initialProviderId` itself changes.
**Status:** Fixed

### Bug 2 — Severity: HIGH — Category: navigation
**Location:** lines 121-122 (`goBack` AI_PROVIDER_ADD handling)
**Symptom:** Pressing back from `AI_PROVIDER_ADD` lands on `AI_PROVIDERS`, but `AI_PROVIDER_EDIT` reached straight from `AI_PROVIDER_ADD`'s `onSaved` also goes back to `AI_PROVIDERS` — so the user can never reach `AI_PROVIDERS_SETUP` from the new-provider flow without an extra back press.
**Root cause:** Both `AI_PROVIDER_ADD` and `AI_PROVIDER_EDIT` hardcoded to go to `AI_PROVIDERS`; the screen has no way to remember "the user reached EDIT from ADD".
**Proposed fix:** Track an `enteredFromAdd` flag like `modelEditFromProvider`; back-navigate from `AI_PROVIDER_EDIT` to `AI_PROVIDERS_SETUP` when set.

### Bug 3 — Severity: HIGH — Category: navigation / deep link
**Location:** line 115 (deep-link guard in `goBack`)
**Symptom:** When the user deep-links into a non-MAIN sub-screen and manually navigates further down (e.g. opened by Hub into `AI_INTERNAL_PROMPTS_HUB`, taps a category card to go to `AI_INTERNAL_PROMPTS`, opens a row to `AI_INTERNAL_PROMPT_EDIT`), the deep-link guard `currentSubScreen == initialSubScreen` only fires on the initial screen. From `AI_INTERNAL_PROMPT_EDIT`, back walks the natural Settings hierarchy (six steps) to leave a screen reached in two forward steps.
**Root cause:** No tracking of the entry-point chain.
**Proposed fix:** When `initialSubScreen != MAIN`, treat any back from screens reached on/below the deep-link as "exit".

### Bug 4 — Severity: MEDIUM — Category: navigation / state leak
**Location:** lines 124-128 + line 222
**Symptom:** `modelEditFromProvider = false` is only reset when going back from AI_MODEL_EDIT (line 126). The flag is set when navigating forward (line 222). If the user navigates AI_MODEL_EDIT → AI_PROVIDER_EDIT → some other path, the flag stays true and corrupts a later back-navigation from AI_MODEL_EDIT reached via a different path (e.g. via AI_MODELS list).
**Root cause:** Flag never reset except on the back-edge.
**Proposed fix:** Reset on entering AI_MODELS (or any non-AI_MODEL_EDIT path).

### Bug 5 — Severity: HIGH — Category: state preservation / data loss
**Location:** lines 522-541 (`SettingsMainScreen` debounce save)
**Symptom:** On rapid back-navigation away from Settings within 400ms of the last keystroke, the `LaunchedEffect`'s suspend `delay` is cancelled before `onSave` runs — typed change is lost.
**Root cause:** Debounce semantics rely on continued composition. When the parent unmounts the composable on navigation, the LaunchedEffect's coroutine is cancelled mid-`delay`.
**Reproduction:** Type into "User name" → immediately press back → check prefs.
**Proposed fix:** Use `DisposableEffect` to flush pending save on dispose, or fire the save through a longer-lived scope (the AppViewModel's `viewModelScope`).
**Status:** Fixed

### Bug 6 — Severity: MEDIUM — Category: state preservation
**Location:** line 87 (`editingInternalPromptId` declaration)
**Symptom:** `editingInternalPromptId` and `editingAgentId` are seeded from initial-prop on first composition with plain `remember{}` not `remember(initialEditingAgentId)`. If the deep-link prop updates (e.g. via re-launch with new intent extras) while the screen is alive, the screen sticks with the original value.
**Root cause:** Compose-side `remember{}` ignores parameter changes.
**Proposed fix:** Sync prop into state via `LaunchedEffect`.

### Bug 7 — Severity: HIGH — Category: state mismatch / category corruption
**Location:** lines 94-101 (`selectedInternalCategory` initial)
**Symptom:** When deep-linking into `AI_INTERNAL_PROMPT_EDIT` for a prompt that doesn't exist yet (id provided but `aiSettings` not yet loaded), the initial category resolves to `"internal"`. If the prompt later loads with category `"meta"`, the screen still pins to `"internal"` since `remember{}` is unkeyed — opening saves under the wrong category.
**Root cause:** `remember { mutableStateOf(... ?: "internal") }` evaluates exactly once at cold-launch.
**Proposed fix:** Use `derivedStateOf` keyed on `aiSettings`, or a LaunchedEffect that flips the category once the prompt resolves.

### Bug 8 — Severity: MEDIUM — Category: cross-path inconsistency
**Location:** lines 213, 215-217
**Symptom:** Inside `ProviderSettingsScreen` callback wiring, `onTestModelWithPrompt` re-resolves `fresh` via `findById`, but `onProviderStateChange = { onProviderStateChange(provider, it) }` (line 213) still uses the captured stale `provider`. After Definition card edits in same session, state changes are pushed against the stale instance.
**Root cause:** Inconsistent freshness handling.
**Proposed fix:** Always resolve `fresh = findById(provider.id) ?: provider` at call boundary.

---

## File: ai/src/main/java/com/ai/ui/settings/SetupScreens.kt

### Bug 9 — Severity: MEDIUM — Category: stale data / counts
**Location:** lines 49, 58-59 (`SetupScreen` count `remember`s)
**Symptom:** `costCount`, `liteRtCount`, `localLlmCount` are read once via unkeyed `remember { ... }`. After installing a new local model or adding a manual cost override and returning to AI Setup, count badges stay at the old value until full app restart.
**Root cause:** Unkeyed `remember{}` caches first value forever.
**Proposed fix:** Use `resumeRefreshTick()`, or key on a lifecycle event.

### Bug 10 — Severity: LOW — Category: stale data / counts
**Location:** lines 265-266 (`LocalModelsSetupScreen`)
**Symptom:** Same problem as bug 9: unkeyed `remember`, counts never refresh after install/remove in a sibling screen.
**Proposed fix:** Same — use `resumeRefreshTick()`.

### Bug 11 — Severity: HIGH — Category: stale Settings after import
**Location:** lines 337-346 (`ProvidersSetupScreen` import button)
**Symptom:** `refreshTick` is incremented after a successful import, but the count badge `providerCount = remember(refreshTick) { AppService.entries.size }` recomposes only the badge. The new providers exist in `ProviderRegistry` but `Settings.providers` snapshot used elsewhere may not reflect them — opening their per-provider screen returns `defaultProviderConfig(service)` until the next save touches them.
**Root cause:** No cascade-update of Settings after registry mutation.
**Proposed fix:** After import, force a settings reload via `onSaveAi(currentSettings)`.

### Bug 12 — Severity: LOW — Category: ux
**Location:** lines 408-413 (`ProvidersScreen` Add button)
**Symptom:** "+ Add provider" button only appears in the `All` filter chip. Users on the default `Active` chip see "No active providers yet. Switch to All and set an API key." with no mention of adding.
**Proposed fix:** Show Add in both modes, or update empty-state text.

### Bug 13 — Severity: MEDIUM — Category: external state mutation race / churn
**Location:** lines 458-498 (`ExternalServicesScreen` onValueChange)
**Symptom:** Every keystroke fires `onSaveHuggingFaceApiKey(it)`, `onSaveOpenRouterApiKey(it)`, `onSaveArtificialAnalysisApiKey(it)` synchronously. Three keys × possibly long pasted secrets = prefs writes on every character.
**Root cause:** No debouncing.
**Proposed fix:** Add `LaunchedEffect(hfKey, orKey, aaKey) { delay(400); persist }` like SettingsMainScreen.

### Bug 14 — Severity: MEDIUM — Category: dead parameters
**Location:** `ModelsSetupScreen`/`WorkersSetupScreen`/`PromptsSetupScreen`/`InternalPromptsHubScreen`/`LocalModelsSetupScreen`/`ProvidersSetupScreen` all accept `onBackToHome` but never invoke it
**Symptom:** Dead parameter passed by parent and silently ignored. The screens rely on `LocalNavigateHome` (CompositionLocal) instead, but the explicit param is dead code.
**Proposed fix:** Remove unused parameters or wire them through.

---

## File: ai/src/main/java/com/ai/ui/settings/AgentsScreen.kt

### Bug 15 — Severity: HIGH — Category: validation / null defaults
**Location:** line 77 (`selectedProvider` initial)
**Symptom:** `selectedProvider = agent?.provider ?: AppService.entries.firstOrNull { aiSettings.isProviderActive(it) } ?: AppService.entries.first()` — when no provider is active, falls back to the *first* `AppService.entries`, which could be the synthetic `LOCAL` provider. The user can save an agent for LOCAL with empty model — fails at runtime with no UI hint.
**Root cause:** Fallback doesn't filter LOCAL.
**Proposed fix:** Filter out LOCAL from the no-active fallback, or refuse to save without a model.

### Bug 16 — Severity: HIGH — Category: filter scope / data loss
**Location:** line 35 (`AgentsScreen` list filter)
**Symptom:** `aiSettings.agents.filter { aiSettings.isProviderActive(it.provider) }` — agents for inactive providers vanish from the list. They're still in `aiSettings.agents` and may be referenced from flocks/swarms; user has no way to delete/edit them.
**Root cause:** Aggressive filter on display.
**Proposed fix:** Show all agents but visually mark provider-inactive ones (e.g. 💤 emoji).

### Bug 17 — Severity: MEDIUM — Category: numerical / state / UI thread
**Location:** lines 256-261 (Test Agent flow capturing trace file)
**Symptom:** `before = ApiTracer.getTraceFiles().firstOrNull()?.timestamp ?: 0L` is captured on the main thread before the test fires. If `getTraceFiles()` hits the cold path (streaming-parse over the trace dir), it stalls the UI. After the test, picks `firstOrNull { it.timestamp > before }` — but if the test produced multiple traces (retries on 429), only the most recent is captured.
**Root cause:** Trace identification by timestamp is fragile; cold-path call isn't off-thread.
**Proposed fix:** Pass the trace filename through the `onTestAiModel` callback. Move `getTraceFiles()` calls to IO scope.

### Bug 18 — Severity: MEDIUM — Category: invariant violation
**Location:** line 283 (Save constructor)
**Symptom:** `selectedParamsIds` is taken as-is from the dialog. If the dialog returns a list with duplicates, the agent stores duplicates.
**Proposed fix:** `selectedParamsIds.distinct()` at save time.
**Status:** Fixed

### Bug 19 — Severity: HIGH — Category: cross-data inconsistency / duplicate endpoints
**Location:** lines 200-209 (LiteLLM endpoint creation in dropdown)
**Symptom:** When the user picks a LiteLLM-derived endpoint from the dropdown, a fresh UUID Endpoint is created via `onAddEndpoint`. The dedupe via `knownUrls` is per-render against the captured `endpoints`. Two rapid taps of the same LiteLLM option (before parent re-renders) create two endpoints with the same URL but different IDs.
**Root cause:** No synchronous dedup on click.
**Proposed fix:** Inside the click handler, re-fetch `getEndpointsForProvider(provider)` and dedupe by URL before adding.

---

## File: ai/src/main/java/com/ai/ui/settings/FlocksScreen.kt

### Bug 20 — Severity: MEDIUM — Category: data scope / save
**Location:** lines 80-89 (FlockEditScreen filters)
**Symptom:** `availableAgents = agents.filter { isProviderActive(it.provider) }` — when editing an existing flock that contains an agent on an inactive provider, that agent is suppressed entirely from `sortedAgents`. The agent's id is still in `selectedAgentIds` so save persists it correctly, but the user can't *uncheck* it; the count "X selected of Y" is misleading.
**Proposed fix:** Always include selected agents in the displayed list.

### Bug 21 — Severity: LOW — Category: UI inconsistency
**Location:** line 39 (subtitle generator)
**Symptom:** Flock subtitle in list shows "X agents:" using only active agents. If the flock contains 5 agents and 2 providers are inactive, the subtitle reads "3 agents:" — doesn't match `flock.agentIds.size == 5`. User sees different counts in list vs edit.
**Proposed fix:** Show "3 of 5 agents (2 inactive)".

---

## File: ai/src/main/java/com/ai/ui/settings/SwarmsScreen.kt

### Bug 22 — Severity: MEDIUM — Category: data scope mismatch
**Location:** line 39 (subtitle generator)
**Symptom:** Swarm members filtered by `isProviderActive` for display count, but unlike Agents/Flocks the swarm storage allows inactive members. Display count drifts from `swarm.members.size`.
**Proposed fix:** Show both counts.

### Bug 23 — Severity: HIGH — Category: duplicate detection / case sensitivity
**Location:** lines 86-88 (model picker `onPickModel`)
**Symptom:** Duplicate detection uses case-sensitive `provider.id == provider.id && it.model == model`. If picker returns `Gpt-4o` and the swarm already has `gpt-4o`, both are stored. Worse: the LazyColumn `key = "${provider.id}:${model}"` would still be unique, so no Compose warning, but a downstream API call collides.
**Root cause:** No normalization.
**Proposed fix:** `equals(ignoreCase = true)` and persist canonical form.
**Status:** Fixed

### Bug 24 — Severity: LOW — Category: ux
**Location:** lines 178-182 (Remove member button)
**Symptom:** No confirmation. A tap-mistake removes a member with no undo.
**Proposed fix:** Add confirmation dialog or undo snackbar.

---

## File: ai/src/main/java/com/ai/ui/settings/ParametersScreen.kt

### Bug 25 — Severity: HIGH — Category: validation / numerical
**Location:** lines 161-171 (Save constructor)
**Symptom:** `temperature.toFloatOrNull()`, etc. — invalid input ("abc", "1.2.3") silently coerces to `null`. Out-of-range values ("5.0" for top_p ∈ [0,1]) are accepted with no UI feedback; failure surfaces only at dispatch time.
**Proposed fix:** Validate ranges via `isError` and `supportingText`; disable Save when out of range.
**Status:** Open

### Bug 26 — Severity: MEDIUM — Category: missing field / data drop
**Location:** line 167
**Symptom:** Save constructor passes `null` for `stopSequences`. The UI has no field for it. Editing an existing preset that *had* `stopSequences` silently drops them.
**Proposed fix:** Either add UI for stop sequences, or preserve `params.stopSequences` from input on edit.
**Status:** Fixed

### Bug 27 — Severity: LOW — Category: input filtering
**Location:** lines 101-107 (numeric OutlinedTextFields)
**Symptom:** No `KeyboardOptions(keyboardType = KeyboardType.Number)` — user gets alphabetic keyboard for numeric fields.
**Proposed fix:** Add appropriate keyboard type.
**Status:** Fixed

---

## File: ai/src/main/java/com/ai/ui/settings/InternalPromptsScreen.kt

### Bug 28 — Severity: HIGH — Category: invariant violation / category overwrite
**Location:** line 133 (`category = fixedCategory`)
**Symptom:** When `internalPrompt` already has a category that doesn't match `fixedCategory` (e.g. deep-link from elsewhere passed wrong fixedCategory, or a state-mismatch from bug 7), the prompt's category is silently overwritten on save.
**Proposed fix:** When editing existing prompt, preserve `internalPrompt.category`; only enforce `fixedCategory` for new prompts.

### Bug 29 — Severity: MEDIUM — Category: validation
**Location:** line 138 (agent initial)
**Symptom:** Asymmetric handling: `fixedCategory == "cross_out"` forces `agent = AGENT_NA`, but `cross_in` (also a CROSS_CATEGORIES member) is not forced — so an existing cross_in prompt opened for editing shows the agent dropdown.
**Proposed fix:** Unify under `fixedCategory in CROSS_CATEGORIES`.

### Bug 30 — Severity: LOW — Category: UI
**Location:** lines 269-272 (Template body field disabled state)
**Symptom:** When `type` is `rerank`/`moderation`, body field is disabled but the label still says "Template body" with no inline hint about why.
**Proposed fix:** Add "(unused for rerank/moderation)" inline.

---

## File: ai/src/main/java/com/ai/ui/settings/ServiceSettingsScreens.kt

### Bug 31 — Severity: CRITICAL — Category: data loss / save race
**Location:** lines 263-272 (`ProviderModelSettingsScreen` two LaunchedEffects)
**Symptom:** Two-way sync: `LaunchedEffect(config.models) { models = config.models }` (external→local) plus `LaunchedEffect(modelSource, models) { ... onSave(...) }` (local→external). When external `config.models` changes, the first effect updates local `models` — the second effect immediately fires, sees `models` differs from `current.models` (race timing), and saves. Worse: captured `aiSettings` may be a recomposition behind, so the save can OVERWRITE a more recent change made elsewhere.
**Root cause:** Two-way sync without "we're applying external" guard.
**Proposed fix:** One-way data flow. Write through callback; don't maintain a local mirror.
**Status:** Partial — early-return guards at both ends of the sync; structural one-way refactor deferred

### Bug 32 — Severity: HIGH — Category: stale capture / dead code
**Location:** lines 318-345 (Test all models)
**Symptom:** `targets = models.toList()` captures local `models` at click time. If user removes a failed model while testing is still running (failed fast, others still running), `testStatuses` is filtered to drop those entries — but in-flight async lambdas continue and on completion mutate `testStatuses` with `testStatuses + (m to ...)`. Removed model statuses can "rise from the dead".
**Proposed fix:** Use a `Job` for the test batch; cancel before pruning, or guard each lambda with `if (m in models)` before writing.
**Status:** Fixed (this session) — Test all wraps each test in runCatching

### Bug 33 — Severity: HIGH — Category: silent error swallowing
**Location:** lines 318-345 (Test all models semaphore loop)
**Symptom:** No try/catch around `onTestSpecificModel(m, MODEL_TEST_PROMPT)`. An IOException/HttpException propagates and (because of structured concurrency) cancels all sibling launches. The killed siblings keep their `Running` status forever, with no error indicator.
**Proposed fix:** `runCatching { onTestSpecificModel(m, ...) }.fold(...)` per-launch; report failures as `Fail(null)`.
**Status:** Fixed (this session) — Test all skips writes for removed models

### Bug 34 — Severity: HIGH — Category: cross-path inconsistency / agent duplication
**Location:** lines 595-622 (auto-save default-agent sync)
**Symptom:** When `defaultModel` changes, the screen creates a fresh agent if none exists with `name == service.displayName`. If the user has *renamed* the default agent (e.g. "OpenAI" → "GPT 5"), the match fails and a fresh "OpenAI" agent is auto-created — every model edit duplicates it.
**Proposed fix:** Match by stable id-pattern (e.g. `isDefault` flag on Agent), not display name.

### Bug 35 — Severity: HIGH — Category: persisted-state machine
**Location:** lines 663-685 (Provider inactive switch)
**Symptom:** When user un-checks `inactive`, local `isInactive = false` flips before the test runs. If the test fails, `onProviderStateChange("error")` fires — but the local Switch UI stays unchecked (showing "active"), while the persisted state is "error". Visual disconnect: switch off (active), label/state error.
**Proposed fix:** Don't flip `isInactive = false` until test settles, or distinguish "active OK" vs "active failing" visually.

### Bug 36 — Severity: MEDIUM — Category: input validation
**Location:** lines 562-587 (provider-definition auto-save)
**Symptom:** `defCostTicksDivisor.trim().toDoubleOrNull()` accepts 0 or negative values — divide-by-zero downstream produces Infinity in cost calculations.
**Proposed fix:** Reject 0/negative.
**Status:** Fixed

### Bug 37 — Severity: MEDIUM — Category: regex misuse / crash
**Location:** line 582 (defModelFilter persisted)
**Symptom:** Persisted as-is. Compiled into a regex at runtime. Invalid regex (`*`, `[unclosed`) throws `PatternSyntaxException` from the dispatcher.
**Proposed fix:** Try-compile inside auto-save; show `isError` and skip persist.
**Status:** Fixed (this session) — skip persisting modelFilter when regex doesn't compile

### Bug 38 — Severity: MEDIUM — Category: state stickiness / save blocked
**Location:** lines 511-587 (provider-definition state vs auto-save `same` check)
**Symptom:** `defXxx` state is keyed on `service.id`. After ProviderRegistry.update mutates the AppService, the next recomposition sees the new `service` object but the local `defXxx` still has the user's typed value. The `same` equality check (line 544+) compares against the LIVE `service` (just updated), and may compute `same == true` — preventing further saves until something else changes.
**Proposed fix:** Use a stable initial snapshot for the `same` check (i.e., compare to the value last persisted).

### Bug 39 — Severity: HIGH — Category: list ordering / Compose key
**Location:** line 424 (`models.sorted()`)
**Symptom:** Display uses `models.sorted()`, Test all uses `models.toList()` (insertion order). If the inner model rows aren't keyed (the `forEach` doesn't apply Compose `key()`), Compose may reuse Text composables across recompositions when sort order changes — visually attaching the wrong test status icon to a row.
**Proposed fix:** Wrap rows in `key(modelId) { ... }`, or use LazyColumn with `key = { modelId }`.

### Bug 40 — Severity: MEDIUM — Category: ui scrolling / perf
**Location:** lines 421-467 (model list rendered via forEach inside verticalScroll)
**Symptom:** A provider with 1000+ models inflates 1000 Composables eagerly inside `verticalScroll(rememberScrollState())` — slow first compose, high memory.
**Proposed fix:** Use LazyColumn for the inner model list.

---

## File: ai/src/main/java/com/ai/ui/settings/ProviderAddScreen.kt

### Bug 41 — Severity: HIGH — Category: data integrity / prefsKey collision
**Location:** lines 168-189 (Save constructor)
**Symptom:** `prefsKey = prefsKey.trim().ifBlank { normalizedId.lowercase() }` — no uniqueness check on `prefsKey`. A user supplying `prefsKey = "openai"` for a different provider id silently shares storage with the existing OPENAI provider — corrupting the existing provider's API key, models, etc.
**Proposed fix:** Validate `prefsKey` against existing providers' prefsKeys; reject duplicates.
**Status:** Fixed

### Bug 42 — Severity: MEDIUM — Category: missing field
**Location:** line 176 (`typePaths`)
**Symptom:** Only `chatPath` mapped; other types (embedding, rerank, image, tts, stt, moderation, classify) get no UI. A user adding a non-chat-only provider can't set per-type paths.
**Proposed fix:** Add per-type path fields like the edit screen.

### Bug 43 — Severity: LOW — Category: ux
**Location:** line 191
**Symptom:** Toast "Provider added" is generic; doesn't include which provider.
**Proposed fix:** "Provider $displayName added".
**Status:** Fixed (this session) — Toast names the provider added

---

## File: ai/src/main/java/com/ai/ui/settings/ModelTypesScreen.kt

### Bug 44 — Severity: MEDIUM — Category: state preservation / save race
**Location:** lines 39-50
**Symptom:** `paths = rememberSaveable(generalSettings) { ... }` — keyed on the entire `generalSettings`. Any unrelated change (e.g. `userName` debounced update) resets local `paths` from `generalSettings.defaultTypePaths`, losing in-flight edits.
**Proposed fix:** Key on `generalSettings.defaultTypePaths` only.
**Status:** Fixed (this session) — ModelTypesScreen rememberSaveable keyed on defaultTypePaths only

---

## File: ai/src/main/java/com/ai/ui/settings/ManualModelTypesScreen.kt

### Bug 45 — Severity: MEDIUM — Category: data integrity
**Location:** line 148
**Symptom:** `canSave = providerId.isNotBlank() && modelId.trim().isNotBlank()` — no uniqueness check. Two `ModelTypeOverride` entries can have same `(providerId, modelId)` with different types; the second silently overrides the first at runtime.
**Proposed fix:** Detect collision; prompt edit-existing or replace.

### Bug 46 — Severity: LOW — Category: ui escape hatch
**Location:** lines 199-207 (model dropdown empty state)
**Symptom:** When `knownModels.isEmpty()`, dropdown is read-only with placeholder "No models — fetch this provider first". User can't fetch from this screen — no escape hatch.
**Proposed fix:** Allow free-text model id, or show a Fetch button.

---

## File: ai/src/main/java/com/ai/ui/settings/RefreshScreen.kt

### Bug 47 — Severity: CRITICAL — Category: process kill mid-write / data loss
**Location:** lines 382-388 (Refresh all auto-restart)
**Symptom:** `Runtime.getRuntime().exit(0)` is called immediately after `startActivity`. `delay(400)` precedes but no flush of pending writes. `SettingsPreferences.scheduleUsageStatsFlush` debounces to 2 seconds — kill within 0.4s loses unsaved usage stats. PricingCache writes also async.
**Proposed fix:** Explicitly flush all in-memory caches (`flushUsageStats()`, etc.) before exit. Or use `finishAffinity()`.
**Status:** Fixed

### Bug 48 — Severity: HIGH — Category: out-of-order updates / progress
**Location:** lines 285-301 (`runProviders` per-provider update)
**Symptom:** Progress text written from many parallel coroutines: `progressText = "$done / $total — ${service.displayName}"`. While `done` is correct via `AtomicInteger`, the displayed string is non-monotonic — user sees "5/10 — A" then "4/10 — B" if A finishes first but B's write came after.
**Proposed fix:** Only update progressText if `done > previousDone`.

### Bug 49 — Severity: HIGH — Category: race / Settings drift
**Location:** lines 333-352 (`runDefaultAgents` settings mutation)
**Symptom:** `var updatedSettings = aiSettings` captures settings at start. Inside, multiple agents added and a flock created. Concurrent saves elsewhere don't update this captured copy. After loop, `onSave(updatedSettings)` overwrites concurrent changes.
**Proposed fix:** Read fresh settings before save (lambda provider), or merge.

### Bug 50 — Severity: MEDIUM — Category: missing handler for empty case
**Location:** line 314 (`runDefaultAgents` candidates)
**Symptom:** If `getActiveServices()` is empty, candidates is empty, but flock-creation logic still runs and creates a "default agents" flock with empty `agentIds`.
**Proposed fix:** Skip flock creation when `defaultAgentIds.isEmpty()`.

### Bug 51 — Severity: MEDIUM — Category: error swallowing
**Location:** lines 75-78 (`launchTask` exception handling)
**Symptom:** `catch (e: Exception) { taskError = e.message }` — `e.message` is often null, user sees "null". Catches Exception not Throwable.
**Proposed fix:** `e.message ?: e.javaClass.simpleName`; consider catching Throwable for fatal reporting.
**Status:** Fixed

### Bug 52 — Severity: HIGH — Category: progress dialog dismissal / lockout
**Location:** lines 81-87 (progress AlertDialog)
**Symptom:** `onDismissRequest = {}` — user cannot dismiss. With bug 51, a fatal error leaving `progressTitle` non-blank (no finally) locks the user permanently.
**Proposed fix:** Add Cancel button cancelling the underlying job.

### Bug 53 — Severity: MEDIUM — Category: stale state across runs
**Location:** lines 50-63 + 369-378
**Symptom:** Tier results aren't reset at start of `Refresh all`. A partial failure (e.g. OpenRouter completes, LiteLLM fails) shows stale OpenRouter from a previous run mixed with new LiteLLM.
**Proposed fix:** Reset all `*Result` states at start.
**Status:** Fixed

---

## File: ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt

### Bug 54 — Severity: HIGH — Category: data corruption (CSV)
**Location:** lines 116-119, 226-237 (Costs CSV roundtrip)
**Symptom:** No CSV escaping. Model id with comma breaks the row; `"` not handled. Importer does naive `split(",")`.
**Proposed fix:** Proper CSV escaping (quote + escape).

### Bug 55 — Severity: HIGH — Category: locale / decimal separator
**Location:** lines 117-119
**Symptom:** Basic Costs export uses `${pricing.promptPrice * 1_000_000}` — `Double.toString()` is locale-independent (always uses `.`), so this is actually safe for the value formatting. But mixing with `String.format` elsewhere (without `Locale.US`) on the same screen is inconsistent and risk-prone if a future code change adds `String.format`.
**Proposed fix:** Use explicit `String.format(Locale.US, ...)` consistently.

### Bug 56 — Severity: MEDIUM — Category: format consistency
**Location:** lines 116-122 vs lines 134-141 (basic vs layered)
**Symptom:** Basic CSV writes raw `Double.toString` (potentially "1.234567"), layered CSV uses `%.4f`. Roundtripping basic export creates noisier output than expected.
**Proposed fix:** Use consistent formatting.

### Bug 57 — Severity: MEDIUM — Category: charset / encoding
**Location:** lines 56-60 (`writeToUri`/`readFromUri`)
**Symptom:** `content.toByteArray()` uses platform default charset, not explicit UTF-8. On a non-UTF-8 default device, JSON with non-ASCII (Chinese provider names, emoji in agent names) corrupts.
**Proposed fix:** `content.toByteArray(Charsets.UTF_8)`.
**Status:** Fixed

### Bug 58 — Severity: MEDIUM — Category: silent error swallowing
**Location:** lines 56-61 + launcher callbacks
**Symptom:** No try/catch around SAF write. Toast says success even if write threw.
**Proposed fix:** `runCatching { writeToUri(...) }.fold(...)`.

### Bug 59 — Severity: LOW — Category: deprecated / inefficient
**Location:** line 75 (`JsonParser().parse(json)` for keys count)
**Symptom:** Re-parses entire JSON just to count keys. Uses deprecated parser instance method.
**Proposed fix:** `JsonParser.parseString(json).asJsonObject.size()`.
**Status:** Fixed (this session) — JsonParser.parseString — stops using the deprecated parser instance

### Bug 60 — Severity: MEDIUM — Category: import diagnostic
**Location:** lines 232-235 (Costs import)
**Symptom:** When user accidentally re-imports a layered CSV unedited, columns 3+4 are empty → all rows skipped silently. User sees "Imported 0, skipped 100" with no clue why.
**Proposed fix:** Detect layered vs basic CSV via header row and offer specific advice.

### Bug 61 — Severity: HIGH — Category: misleading export
**Location:** lines 96-104 in SettingsExport.kt + the import path
**Symptom:** Config export strips `huggingFaceApiKey`, `openRouterApiKey`. After config import, the External Services keys are NOT restored — were never in the file. UI gives no warning.
**Proposed fix:** Document explicitly: "API keys excluded from this export — use API Keys export to share them".

---

## File: ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt

### Bug 62 — Severity: MEDIUM — Category: stale state after async op
**Location:** lines 91-107 (LiteRT download progress)
**Symptom:** During download `working = true`. On failure mid-download, the partial truncated `.tflite` may remain on disk and `availableModels` lists it as installed (with ✓).
**Proposed fix:** Delete target on failure inside `LocalEmbedder.download`, or filter `availableModels` by file size/integrity.

### Bug 63 — Severity: LOW — Category: thread / coroutine churn
**Location:** lines 95-100 (download progress callback)
**Symptom:** Progress callback fires from `Dispatchers.IO`; each update launches a new Main coroutine. Hundreds per second on a fast download.
**Proposed fix:** Throttle to ≤10/s.

### Bug 64 — Severity: HIGH — Category: file deletion vs in-use
**Location:** lines 141-146 (LiteRT Remove button)
**Symptom:** `release(name)` then `File(...).delete()`. If the embedder is currently being used (KB ingestion running), `release` may not abort and `delete()` returns false silently. File "removed" from `availableModels` but still on disk.
**Proposed fix:** Block UI delete until in-flight ops complete; surface error if delete returns false.

### Bug 65 — Severity: MEDIUM — Category: filename collision
**Location:** lines 305-336 (`importTaskModel`)
**Symptom:** Re-importing a different `gemma.task` overwrites the first without warning.
**Proposed fix:** Append suffix when target exists.
**Status:** Fixed (this session) — importTaskModel disambiguates collisions with -2 / -3 suffixes

### Bug 66 — Severity: LOW — Category: log injection / log path
**Location:** lines 367-378 (extractFirstTaskFromZip)
**Symptom:** `Log.i("LocalRuntime", "Extracted ${entry.name} ...")` logs unsanitized entry name; while writes are safe (target is fixed), the log line could include path traversal data confusing other log analyzers.
**Proposed fix:** Log just `File(entry.name).name`.

---

## File: ai/src/main/java/com/ai/ui/settings/SettingsPreferences.kt

### Bug 67 — Severity: HIGH — Category: thread safety / ANR risk
**Location:** lines 67-79 (`saveGeneralSettings`)
**Symptom:** Synchronous `prefs.edit { ... }` call from main thread. The `prefs.edit { ... }` extension uses `commit()` synchronously by default if no `apply` is specified — checking the import: it imports `androidx.core.content.edit` which uses `apply()` by default if `commit` parameter is false. OK — but the LaunchedEffect runs on the main dispatcher and the I/O of `SharedPreferences.edit().apply()` is itself off-thread; however, the gson serialization (`gson.toJson(settings.defaultTypePaths)`) runs main-thread. For large maps this can stutter.
**Proposed fix:** Wrap save in `withContext(Dispatchers.IO)`.

### Bug 68 — Severity: MEDIUM — Category: Gson type erasure / unchecked cast
**Location:** lines 198-201 (loadJsonStringSet)
**Symptom:** `gson.fromJson(json, List::class.java) as? List<String>` — if JSON has numbers (e.g. someone manually edited prefs to `[1, 2]`), Gson returns `List<Double>`; the cast succeeds (still `List<*>`); iterating returns Doubles; downstream `Set<String>` operations work via toString() but typed checks elsewhere blow up.
**Proposed fix:** Use TypeToken<List<String>> and validate.

### Bug 69 — Severity: HIGH — Category: stale cache flush state
**Location:** lines 391-394 (`clearUsageStats`)
**Symptom:** `usageStatsCache?.clear()` empties the map but `lastUsageStatsFlush` is NOT reset. After clear, the next `updateUsageStats` schedules `scheduleUsageStatsFlush` — but the time-since-last-flush check skips because `lastUsageStatsFlush` was just set ~now from a recent save. Cache holds the new write but disk file remains empty until 2 seconds pass and another write triggers another flush attempt.
**Proposed fix:** Set `lastUsageStatsFlush = 0L` inside `clearUsageStats`.
**Status:** Fixed (this session) — clearUsageStats resets lastUsageStatsFlush so the next write isn't held back

### Bug 70 — Severity: MEDIUM — Category: race / debounce trailing event drop
**Location:** lines 368-378 (`scheduleUsageStatsFlush`)
**Symptom:** Debounce drops the trailing event. After a tight burst of writes, the very latest update may not be flushed within the debounce window. Process kill between burst-end and next flush window loses data.
**Proposed fix:** Schedule a delayed flush coroutine that always fires once.

### Bug 71 — Severity: HIGH — Category: invariant violation / partial migration
**Location:** lines 102-119 (loadSettingsWithMigration internal-prompt migration)
**Symptom:** Defensive casts only on four fields (category, agent, type, title). If any other non-null Kotlin field of `InternalPrompt` is also runtime-null (id, name, text), the `ip.copy(...)` call will NPE — caught by the outer `try` but silently dropping the row (since it's loaded inside `loadList` which catches all).
**Proposed fix:** Defensive-cast all non-null String fields, or `runCatching { ip.copy(...) }`.

### Bug 72 — Severity: MEDIUM — Category: silent data loss
**Location:** line 86
**Symptom:** Agents with unknown `provider.id` are filtered out at load. User who removed a custom provider then re-adds one with the same id finds their old agents are gone.
**Proposed fix:** Keep agents with a "missing provider" flag and surface a stale-agents cleanup button.

### Bug 73 — Severity: MEDIUM — Category: cache initialization wasteful retry
**Location:** lines 301-339 (ensureUsageStatsCache)
**Symptom:** If the JSON is permanently broken (every row throws), every call re-runs the entire JSON parse — bypassing the warm-cache optimization forever.
**Proposed fix:** Cache the failure with a TTL.

---

## File: ai/src/main/java/com/ai/ui/admin/HousekeepingScreen.kt

### Bug 74 — Severity: CRITICAL — Category: data loss / process race after restore
**Location:** lines 105-117 (Restore success → relaunch)
**Symptom:** After restore: `delay(800)` → `startActivity` → `killProcess`. The 800ms is for toast rendering; SharedPreferences/files written by `BackupManager.restore` may not have flushed. SAF OutputStream close doesn't fsync. Killing process before fsync = partial restore.
**Proposed fix:** Explicit fsync in BackupManager.restore (sync `commit()` for prefs, fileChannel.force(true) for files); only then kill.
**Status:** Fixed

### Bug 75 — Severity: HIGH — Category: missing busy guard
**Location:** lines 290-308 (Trim by age button)
**Symptom:** No `enabled = busyLabel == null`. While Backup or Restore is in progress, Trim can fire and tear apart files being archived/restored.
**Proposed fix:** `enabled = ... && busyLabel == null`.
**Status:** Fixed

### Bug 76 — Severity: HIGH — Category: missing busy guards (multiple)
**Location:** lines 312-381 (Clear Usage, Cleanup overrides, Load bundled prompts, Clear All Runtime, Clear All Configuration)
**Symptom:** Same as bug 75 — none have `enabled = busyLabel == null`. Reset Application is the only one that does.
**Proposed fix:** Add busy gates everywhere.
**Status:** Fixed

### Bug 77 — Severity: HIGH — Category: Reset bypass
**Location:** lines 218-230 (Reset confirmation Reset button)
**Symptom:** No `enabled = busyLabel == null` here either. User can type RESET and tap while Backup is in flight, kicking off Reset on top of Backup.
**Proposed fix:** Combine with `busyLabel == null`.
**Status:** Fixed

### Bug 78 — Severity: MEDIUM — Category: dialog state preservation
**Location:** lines 144-188 (multiple AlertDialogs)
**Symptom:** Each `showXConfirm` is `mutableStateOf` not `rememberSaveable`. Rotation closes the dialog.
**Proposed fix:** `rememberSaveable`.

### Bug 79 — Severity: HIGH — Category: confirmation bypass / destructive
**Location:** lines 290-308 (Trim by age — Clear button)
**Symptom:** Pressing Orange "Clear Reports/Chats/Traces" immediately deletes data — no confirmation.
**Proposed fix:** Add a confirmation dialog showing the counts that would be affected.
**Status:** Fixed

---

## File: ai/src/main/java/com/ai/ui/admin/TraceScreen.kt

### Bug 80 — Severity: HIGH — Category: auto-select stuck
**Location:** lines 142-148 (auto-select single trace)
**Symptom:** `autoSelected = rememberSaveable(true)` is one-shot for the screen lifetime. If filters change to a different one-trace narrow later, auto-nav doesn't fire again.
**Proposed fix:** Reset `autoSelected = false` when filters change (LaunchedEffect on filter inputs).

### Bug 81 — Severity: HIGH — Category: navigation / rotation state loss
**Location:** lines 512, 528-531 (TraceDetailScreen)
**Symptom:** `var currentFilename by remember { mutableStateOf(filename) }` — not saveable. On rotation, `currentFilename` resets to the initial `filename` parameter, throwing away prev/next navigation.
**Proposed fix:** `rememberSaveable`.

### Bug 82 — Severity: MEDIUM — Category: secret leak via prefs
**Location:** lines 743-752 (Edit button)
**Symptom:** Saves raw `trace.request.body` to `last_test_raw_json` SharedPreferences. The body may contain secrets in headers/query params. Prefs are clear-text XML — wider exposure than the trace file alone.
**Proposed fix:** Strip Authorization headers (or all headers) before saving; reconstruct at submit time.

### Bug 83 — Severity: MEDIUM — Category: redaction completeness
**Location:** lines 754-757 + 776 (Share button + redactUrl)
**Symptom:** `redactUrl` redacts query params; if a key is embedded in the path (some providers use `/v1/<api-key>/messages`), the path is preserved.
**Proposed fix:** Add per-provider redaction hook for path-position secrets.

### Bug 84 — Severity: HIGH — Category: parser fall-back wrong message
**Location:** lines 919-928 (extractUserPrompt content-parts loop)
**Symptom:** The outer "find last user message" loop falls through to older messages when the last user message is content-parts with no text (image-only). Translation extraction then targets a previous message, returning a wrong original prompt.
**Proposed fix:** Don't fall back across user-message boundaries; if newest user message has no text, return null.

### Bug 85 — Severity: LOW — Category: locale
**Location:** line 477 (date formatter)
**Symptom:** `DateTimeFormatter.ofPattern("MM/dd HH:mm:ss", Locale.US)` — fixed format.
**Proposed fix:** Use `Locale.getDefault()` or system date-time format.

### Bug 86 — Severity: MEDIUM — Category: stale data after sibling-screen change
**Location:** line 521 (LaunchedEffect(Unit))
**Symptom:** Unkeyed LaunchedEffect runs once. If user clears traces from a sibling screen and returns, prev/next list is stale.
**Proposed fix:** Use `resumeRefreshTick()`.

### Bug 87 — Severity: HIGH — Category: out-of-bounds / blank screen
**Location:** lines 731-735, 758-762 (prev/next)
**Symptom:** If the trace file was deleted out-of-band (Trim by age fired), `currentIndex == -1`. Both prev/next disabled. LaunchedEffect re-fires `readTraceFile` → null → blank screen, no recovery.
**Proposed fix:** On null trace, navigate to closest existing or `onBack()`.

---

## File: ai/src/main/java/com/ai/ui/admin/StatisticsScreen.kt

### Bug 88 — Severity: HIGH — Category: pricing race / flicker
**Location:** lines 54-64 (UsageScreen pricing init)
**Symptom:** Re-entering the screen re-runs the LaunchedEffect, reading stats again while previous stats may still be displayed.
**Proposed fix:** Show loading skeleton while loading.

### Bug 89 — Severity: MEDIUM — Category: numerical / precision
**Location:** lines 76-82 (StatWithCost computation)
**Symptom:** `inputTokens: Int * promptPrice: Double`. Token counts in Int can overflow at ~2.1B tokens.
**Proposed fix:** Use Long for token counts.

### Bug 90 — Severity: LOW — Category: stale on resume
**Location:** line 240 (manualPricing remember(refreshTrigger))
**Symptom:** Initial composition reads `getAllManualPricing` once; if user edited an override elsewhere and returned, value is stale.
**Proposed fix:** `resumeRefreshTick()`.

---

## File: ai/src/main/java/com/ai/ui/admin/DeveloperScreens.kt

### Bug 91 — Severity: HIGH — Category: prefs handoff race
**Location:** lines 178-181 (Build Request `.apply()`)
**Symptom:** `apply()` is async. If the next screen reads from prefs synchronously immediately, it sees stale data.
**Proposed fix:** `commit()` for handoff data, or pass values via navigation argument.

### Bug 92 — Severity: HIGH — Category: tracing-state global mutation
**Location:** lines 252-264 (EditApiRequestScreen Submit)
**Symptom:** Mutates the global `ApiTracer.isTracingEnabled = true` then restores in finally. Concurrent operations elsewhere see the global flipped on and write traces they wouldn't have otherwise.
**Proposed fix:** Pass per-call "force tracing" flag; don't mutate global.

---

## File: ai/src/main/java/com/ai/ui/admin/ProviderAdminScreen.kt

### Bug 93 — Severity: MEDIUM — Category: link safety / scheme injection
**Location:** lines 73-79 (open admin URL)
**Symptom:** `url.toUri()` + `Intent(ACTION_VIEW, uri)` — no scheme validation. A malicious provider definition (imported via providers.json) could set `adminUrl = "intent:#Intent;..."` or `javascript:...` triggering arbitrary cross-app interactions.
**Proposed fix:** Reject non-http/https schemes.

---

## File: ai/src/main/java/com/ai/ui/shared/CrudListScreen.kt

### Bug 94 — Severity: MEDIUM — Category: state preservation / sort
**Location:** line 90 (`sorted = remember(items) { ... }`)
**Symptom:** `remember(items)` recomputes only on reference change; if `items` reference is unchanged but inner items mutate (e.g. agent renamed in-place — though the data classes are immutable, downstream callers may pass updated lists with the same content reference), sort doesn't update.
**Proposed fix:** Hash-based key.

### Bug 95 — Severity: LOW — Category: locale-dependent sort
**Location:** line 90
**Symptom:** `lowercase()` is locale-dependent. Turkish locale sorts I/i differently.
**Proposed fix:** `lowercase(Locale.ROOT)`.

---

## File: ai/src/main/java/com/ai/ui/shared/SharedComponents.kt

### Bug 96 — Severity: MEDIUM — Category: BackHandler ordering
**Location:** lines 207-218 (TitleBar BackHandler routing)
**Symptom:** TitleBar's BackHandler is registered when composed; if a screen registers its own BackHandler at the very top of the screen (before TitleBar), TitleBar's BackHandler wins (LIFO), and the screen's is never reached. Most screens register at the top — the comment says TitleBar's is "lowest-priority fallback" but that depends on registration order.
**Proposed fix:** Document the contract; or use a single shared back-stack.

### Bug 97 — Severity: LOW — Category: visual consistency
**Location:** lines 286-294 (TitleBarActionStrip slots)
**Symptom:** Conditional rendering of action slots. Strip width changes per-screen; minor inconsistency.
**Proposed fix:** Reserve fixed width or use uniform spacing.

---

## File: ai/src/main/java/com/ai/ui/settings/SettingsExport.kt (cross-reference)

### Bug 98 — Severity: MEDIUM — Category: import default-model handling
**Location:** lines 191-198 (provider config import)
**Symptom:** `model = p.defaultModel ?: cur.model` — if export has `defaultModel = ""` (rather than null), elvis chooses `""`. Empty model imported clobbers existing default.
**Proposed fix:** `p.defaultModel?.takeIf { it.isNotBlank() } ?: cur.model`.

### Bug 99 — Severity: MEDIUM — Category: id collision in upsertLegacy
**Location:** lines 162-178
**Symptom:** Match by name only — if user has "Intro" prompt in category="meta" and import has legacy intro text, no upsert (name match) — but the new "internal" Intro never lands.
**Proposed fix:** Match by (name, category).

### Bug 100 — Severity: HIGH — Category: silent agent drop count
**Location:** line 138
**Symptom:** Agents with unknown provider id are silently dropped. Toast shows `imported X agents` (success count); skipped count not surfaced. User thinks all imported.
**Proposed fix:** Track and report skipped count.

---

## File: ai/src/main/java/com/ai/ui/settings/ApiKeyTransfer.kt (cross-reference)

### Bug 101 — Severity: MEDIUM — Category: legacy alias incomplete
**Location:** lines 76-83 (legacy aliases)
**Symptom:** Aliases handle `HUGGINGFACE` and `OPENROUTER_KEY` but not `ARTIFICIAL_ANALYSIS`/`ARTIFICIALANALYSIS`. Manually edited old export missing the EXT_ prefix won't import the AA key.
**Proposed fix:** Add the alias, or document the prefix requirement.

### Bug 102 — Severity: MEDIUM — Category: skipped-provider diagnostic
**Location:** lines 83-86
**Symptom:** Unknown provider ids silently bumped into `skipped`. User sees count but doesn't know which provider.
**Proposed fix:** Log/surface the skipped provider ids.

---

## File: ai/src/main/java/com/ai/data/BackupManager.kt (cross-reference)

### Bug 103 — Severity: HIGH — Category: backup integrity / fsync
**Location:** line 167 (zip end-of-output)
**Symptom:** SAF OutputStream close doesn't fsync. Process kill before OS flush = corrupt backup. User sees success toast.
**Proposed fix:** Best-effort; document the limitation.

### Bug 104 — Severity: HIGH — Category: backup completeness / fragile allowlist
**Location:** lines 85-87 (PREFS_TO_BACKUP)
**Symptom:** Hard-coded list of 5 prefs files. Future feature adding new prefs file is silently omitted from backups; restore on fresh device omits that data.
**Proposed fix:** Enumerate `getSharedPrefsDir()` and back up all `*.xml`, with an explicit exclusion list.

---

## Cross-cutting bugs

### Bug 105 — Severity: MEDIUM — Category: agent-name uniqueness / locale
**Location:** AgentsScreen.kt line 92-96; FlocksScreen lines 74-78; SwarmsScreen lines 74-78; ParametersScreen lines 79-83; SystemPromptsScreen lines 57-61
**Symptom:** `existingNames = ... .map { it.name.lowercase() }.toSet()` — implicit locale. Turkish Ï/I behave inconsistently.
**Proposed fix:** `lowercase(Locale.ROOT)` consistently.

### Bug 106 — Severity: HIGH — Category: trim asymmetry / duplicate creation
**Location:** AgentsScreen line 283; FlocksScreen line 116; SwarmsScreen line 121; ParametersScreen line 162; SystemPromptsScreen line 91
**Symptom:** Save passes `name.trim()` but `existingNames` is computed without trim. A name "  Foo  " is treated as different from "Foo" for uniqueness check, then saved as "Foo" — colliding silently.
**Proposed fix:** Apply `.trim().lowercase(Locale.ROOT)` symmetrically in both validation and save.

### Bug 107 — Severity: HIGH — Category: provider state mutation race
**Location:** RefreshScreen.kt line 296 (`onProviderStateChange(service, newState)`)
**Symptom:** Per-provider tests fire `onProviderStateChange` from `Dispatchers.IO`. Each callback mutates `Settings.providerStates`. 30+ concurrent writes can interleave; final state may miss updates.
**Proposed fix:** Collect all results, apply in a single save at the end.

### Bug 108 — Severity: HIGH — Category: secret leak via Copy when trace unloaded
**Location:** TraceScreen lines 736-741
**Symptom:** "Copied (redacted)" — but if `t == null`, `toCopy` falls back to `displayContent` which is raw JSON. Clipboard has unredacted secrets.
**Proposed fix:** Disable Copy when `t == null`, or apply redaction to fallback.

### Bug 109 — Severity: HIGH — Category: secret leak via Share when trace unloaded
**Location:** TraceScreen lines 754-757
**Symptom:** Same as 108 — `t?.let { redactedTraceJson(it) } ?: rawJson` — when null, raw shared.
**Proposed fix:** Disable Share when `t == null`.

### Bug 110 — Severity: MEDIUM — Category: API key visibility
**Location:** ServiceSettingsScreens.kt lines 692-696
**Symptom:** API key OutlinedTextField has no `visualTransformation`. Plaintext key visible to shoulder-surfers.
**Proposed fix:** Add show/hide toggle, default to masked.

### Bug 111 — Severity: HIGH — Category: agent default-flock matching by displayName
**Location:** RefreshScreen.kt lines 343-350 (default-agents flock setup)
**Symptom:** `defaultAgentIds = ... agents.filter { a -> results.any { it.first == a.provider.displayName && it.second } }` — uses `provider.displayName`. Two providers with same display name (one custom-cloned) merge incorrectly.
**Proposed fix:** Use `provider.id`.

### Bug 112 — Severity: HIGH — Category: settings save vs `ProviderModelSettingsScreen` empty-models
**Location:** ServiceSettingsScreens.kt lines 268-272
**Symptom:** Auto-save fires when local `models` differs from `current.models`. Combined with bug 31 (race during external sync), an empty `models` snapshot could persist — wiping the user's saved model list.
**Proposed fix:** Skip persist when `models.isEmpty() && current.models.isNotEmpty()` and source is API.

### Bug 113 — Severity: MEDIUM — Category: model id format
**Location:** ServiceSettingsScreens.kt lines 401-409 (Manual mode add)
**Symptom:** No validation on `t = trimmed`. User can enter "foo bar" with whitespace; saved as-is. Downstream API may not find it.
**Proposed fix:** Validate format (no whitespace, etc.).

### Bug 114 — Severity: HIGH — Category: AppService.LOCAL leakage on Add Provider
**Location:** ProviderAddScreen.kt lines 168-189
**Symptom:** No filter on `id` against the synthetic ID `"LOCAL"`. A user could enter `id = "LOCAL"` and overwrite the synthetic provider; ProviderRegistry.add may or may not catch this. Worse — the synthetic LOCAL has special dispatch routing; a user-defined LOCAL provider would shadow that.
**Proposed fix:** Reject reserved ids (LOCAL).

### Bug 115 — Severity: MEDIUM — Category: import: provider definition update never applied
**Location:** SettingsExport.kt lines 128-136 (provider definitions import)
**Symptom:** Comment: "Updating an existing definition from an import bundle is intentionally NOT done". So if the bundle contains updated baseUrl / typePaths for an existing provider, those changes are silently ignored. The user has no UI hint.
**Proposed fix:** Document explicitly in the UI; offer an "Overwrite definitions" toggle.

### Bug 116 — Severity: MEDIUM — Category: missing search filter / case-sensitive search
**Location:** FlocksScreen.kt lines 83-86 (filteredAgents)
**Symptom:** `it.name.contains(searchQuery, ignoreCase = true)` — fine. But `provider.displayName.contains(searchQuery, ignoreCase = true)` searches a SECOND field; user typing "OpenAI" matches by provider name too, possibly surprising in a long list. Minor UX inconsistency with Agents list which doesn't have a search.
**Proposed fix:** Document or restrict to name-only search.

### Bug 117 — Severity: MEDIUM — Category: missing AppService.LOCAL gating
**Location:** SwarmsScreen.kt line 84-91 (model picker)
**Symptom:** `ModelSearchScreen` may include LOCAL provider models. A swarm member with provider=LOCAL has special dispatch routing — the user can add it without realizing.
**Proposed fix:** Document or filter LOCAL.

### Bug 118 — Severity: MEDIUM — Category: SwarmEditScreen — params/system prompt persistence
**Location:** SwarmsScreen.kt line 121
**Symptom:** Save passes `selectedParamsIds` and `selectedSystemPromptId` directly. Like agents, no de-dupe of `selectedParamsIds`.
**Proposed fix:** `selectedParamsIds.distinct()`.

### Bug 119 — Severity: HIGH — Category: provider edit `onProviderTestedOk` after stale provider
**Location:** SettingsScreen.kt line 214
**Symptom:** `onProviderTestedOk = { defaultModel -> onProviderTestedOk(provider, defaultModel) }` — uses the captured stale `provider` (not `fresh`). After Definition card edits, the per-provider state machine is updated against the stale instance.
**Proposed fix:** Use `AppService.findById(provider.id) ?: provider` consistently.

### Bug 120 — Severity: HIGH — Category: concurrent settings write at refresh + auto-save
**Location:** RefreshScreen.kt + SettingsScreen.kt + ServiceSettingsScreens.kt
**Symptom:** `runDefaultAgents` writes settings via `onSave(updatedSettings)` — at the same time, ServiceSettingsScreens auto-save LaunchedEffects (which capture `aiSettings` from outer recomposition) can fire. The two writes interleave; one overwrites the other.
**Proposed fix:** Centralize all settings writes through a serialized channel in AppViewModel.

### Bug 121 — Severity: HIGH — Category: trace cache file overwrite / FileProvider URI
**Location:** TraceScreen.kt lines 788-796 (shareTrace)
**Symptom:** `sharedTraceCacheFile(cacheDir, filename)` writes to the same path on each share. If the user shares trace A, then before the chooser is dismissed shares trace B with the same `filename` (unlikely but possible), the bytes get overwritten while the receiving app may still hold the URI. Also: `cacheDir/shared_traces/<filename>` is reused on Restore — see BackupManager bug list (the path is excluded from skip prefixes since it doesn't start with "ai-restore-").
**Proposed fix:** Use unique filename per share.

### Bug 122 — Severity: MEDIUM — Category: usage stats cache thread safety
**Location:** SettingsPreferences.kt lines 353-366 (updateUsageStats)
**Symptom:** `stats.compute(key) { _, existing -> ... }` uses ConcurrentHashMap.compute which is atomic. Good. But `existing ?: UsageStats(provider, model, kind = kind)` constructs a fresh UsageStats with `provider` — `AppService` parameter passed in. If `provider` is the synthetic LOCAL or a custom provider that's been removed, the stats are stored with a key like `"LOCAL::model::report"` — fine. No bug here, but the followup `scheduleUsageStatsFlush()` outside the compute block may race.
**Proposed fix:** N/A on closer read; flushed correctly.

### Bug 123 — Severity: MEDIUM — Category: hidden navigation surprise
**Location:** SetupScreens.kt lines 178-179 (Workers card enabled = hasApiKey)
**Symptom:** `enabled = hasApiKey` — but `hasApiKey = aiSettings.hasAnyApiKey()`. A user with the synthetic LOCAL configured (no API key) but no cloud providers — `hasAnyApiKey()` likely returns false, so the Workers card is disabled even though LOCAL Agents are valid (no key needed).
**Proposed fix:** `hasAnyApiKey() || isLocalConfigured()`.

### Bug 124 — Severity: MEDIUM — Category: stale import path reuse
**Location:** ImportExportScreen.kt lines 53, 304-323
**Symptom:** `var importType by remember { mutableStateOf("config") }` — single state machine. Each import button sets `importType = "..."` then launches the same `importFileLauncher`. If the user taps "Config" then quickly taps "Keys" (before picker fires), `importType = "keys"` but the file picker may still be from the first tap — mismatched type when the picker callback fires.
**Proposed fix:** Pass importType through the launcher's input or use separate launchers.

### Bug 125 — Severity: HIGH — Category: ProviderRegistry concurrency
**Location:** ServiceSettingsScreens.kt line 565 (ProviderRegistry.update)
**Symptom:** `ProviderRegistry.update(AppService(...))` is called from a Compose-side LaunchedEffect (main dispatcher). If the registry's mutation is not synchronized with reads from other threads (e.g. the dispatch layer running on IO), readers may see partial state.
**Proposed fix:** Verify ProviderRegistry uses atomic/synchronized writes; document the contract.

### Bug 126 — Severity: MEDIUM — Category: unbounded model list pagination
**Location:** ServiceSettingsScreens.kt line 422
**Symptom:** `${models.size} models available` — if a provider returns thousands of models (some open-source proxies do), the count is shown but the unsorted-then-sorted list is rendered fully (bug 40). No filter / search.
**Proposed fix:** Add a search field for the model list.

### Bug 127 — Severity: HIGH — Category: trace-list Provider/Hostname filter UI confusion
**Location:** TraceScreen.kt lines 93-104 + 220-268
**Symptom:** Provider filter and Hostname filter coexist; they're related but not 1:1 (PROVIDER_AUX_HOSTS for Cohere). User narrowing by Provider="Cohere" *and* Hostname="api.cohere.ai" can produce a confusing intersection — the user picked "Cohere" expecting both hosts but then narrowed to one. The screen doesn't clear conflicting filters automatically.
**Proposed fix:** When Provider changes, reset Hostname to "(All)".

### Bug 128 — Severity: MEDIUM — Category: progressBar wrong total during incremental update
**Location:** RefreshScreen.kt lines 281-284
**Symptom:** `total = testable.size` is fixed at start; `progressText = "0 / $total"`. If `testable.size == 0` (all providers inactive), progress shows "0 / 0" and the supervisorScope returns immediately — no error but the dialog flashes empty.
**Proposed fix:** Skip the dialog open when total == 0.

### Bug 129 — Severity: HIGH — Category: trace-list errorsOnly filter scope
**Location:** TraceScreen.kt line 131 (errorsOnly filter)
**Symptom:** `errorsOnly` filter checks `t.statusCode == 0 || t.statusCode >= 400`. But statusCode == 0 is the "transport-level failure" sentinel. If the trace records were never written (e.g. timeout before HTTP frame), they're not even in `allTraceFiles`. The filter is correct but the count `errorCount` (line 149-151) excludes such ghost-failures. No way to see "fully failed but no trace produced" from this UI.
**Proposed fix:** Document; add a UI hint.

### Bug 130 — Severity: MEDIUM — Category: TraceDetailScreen — translation parts on race
**Location:** TraceScreen.kt lines 543-546
**Symptom:** `translationParts` is computed `remember(t?.category, t?.request?.body, t?.response?.body)`. If the trace re-loads (currentFilename change) and the new trace's category isn't "Translation", `translationParts` becomes null — but the `showTranslationCompare` boolean state is still true from the previous trace, so the user is briefly stuck in a TranslationCompareScreen with null-content.
**Proposed fix:** Reset `showTranslationCompare = false` whenever currentFilename changes.

