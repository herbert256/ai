# Settings / Admin / Cruds / Share-target — Bug review (audit-3, fresh from current code)

Scope: `ui/settings/**`, `ui/admin/**`, `ui/cruds/**`, `ui/share/**`,
`data/SharedContent.kt`, plus the data-layer helpers these screens call
(`SettingsPreferences`, `PricingCache`, `BackupManager`, `GeneralSettings`).
Pure `*Help.kt` content files were skimmed for logic only. Findings are
grouped by file and numbered continuously; every location was read from the
live tree on 2026-06-06 (branch `master`). The huge read-only aggregate
screens (`AiDashboardScreen`, `TraceScreen`) and the help-content files were
reviewed and found clean enough not to surface confident bugs — they use
`Locale.US` for all numeric formatting, `Dispatchers.IO` for disk reads, and
`rememberSaveable` for filters.

---

## File: ai/src/main/java/com/ai/ui/settings/ParametersScreen.kt

### Bug 1 — Severity: HIGH — Category: locale / comma-decimal
**Location:** ParametersScreen.kt:72-81 (`buildParams`), 114,117-122 (Decimal keyboard fields)
**Symptom:** On the user's nl-NL (comma-decimal) device, typing `0,7` into Temperature / Top P / Frequency penalty / Presence penalty silently drops the value — the saved preset keeps the field null. The numeric fields are even configured with `KeyboardType.Decimal`, which on nl-NL surfaces a comma key that then fails to parse.
**Root cause:** `buildParams` parses every float field with `temperature.toFloatOrNull()` / `topP.toFloatOrNull()` etc. `Float.parseFloat` is locale-independent and only accepts `.`; `"0,7".toFloatOrNull()` returns null. No comma→dot normalization. The preset is auto-saved (edit) / Created (add) with the field cleared.
**Reproduction:** On nl-NL, edit a Parameters preset → Temperature `0,7` → the preset persists with temperature = null; the model call uses no temperature.
**Proposed fix:** Normalize `,`→`.` before `toFloatOrNull`/`toIntOrNull`, or parse via the device-locale `NumberFormat`. Apply to all numeric fields here.
**Status:** Fixed (2026-06-07) — float fields normalize comma→dot before toFloatOrNull (decimalToFloat)

---

## File: ai/src/main/java/com/ai/ui/admin/StatisticsScreen.kt

### Bug 2 — Severity: MEDIUM — Category: locale / comma-decimal
**Location:** StatisticsScreen.kt:211-212,249-252,276-279 (`AddManualOverrideScreen`)
**Symptom:** On a comma-decimal locale the manual cost-override Input/Output price fields can't accept a decimal — typing `0,5` leaves Save disabled and the `onSave` no-ops.
**Root cause:** `inputPrice.toDoubleOrNull()` / `outputPrice.toDoubleOrNull()` parse `.`-only; the Save enablement and the `onSave` body both gate on `toDoubleOrNull() != null`. Prefilled values are produced with `"%.4f".format(Locale.US, …)` (dot), so editing an existing override and retyping a decimal also breaks.
**Reproduction:** nl-NL device → AI Setup → Costs → Add → enter `0,5` for input price → Save stays disabled.
**Proposed fix:** Normalize comma→dot before parsing (and keep the `%.4f` Locale.US prefill, which round-trips fine only with dot input).
**Status:** Fixed (2026-06-07) — manual-override price fields normalize comma→dot before toDoubleOrNull

### Bug 3 — Severity: LOW — Category: cost accuracy
**Location:** StatisticsScreen.kt:44-49 (`buildProviderCostGroups`, legacy fallback)
**Symptom:** For legacy usage rows that predate persisted call-time cost, a row that has *both* token counts and search units is mis-costed: it charges only `searchUnits * perQueryPrice` and reports `outputCost = 0`, ignoring the token spend entirely.
**Root cause:** The fallback branch is `if (searchUnits > 0) searchUnits*perQueryPrice else tokens*price` — an either/or, not additive. New rows (carrying persisted cost) are unaffected; only legacy rows recomputed on the fly.
**Proposed fix:** Sum token cost + search cost like `PricingCache.computeUsageCostSnapshot` does at write time.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ServiceSettingsScreens.kt

### Bug 4 — Severity: MEDIUM — Category: excessive prefs writes / perf
**Location:** ServiceSettingsScreens.kt:846-855 (`ProviderSettingsScreen` apiKey auto-save)
**Symptom:** Every keystroke (and every pasted character) in the provider API-key field triggers a full `onSave(aiSettings.withProvider(...))`, which serializes the entire Settings blob to SharedPreferences. Pasting a 100-char key writes the whole config ~100 times in rapid succession.
**Root cause:** `LaunchedEffect(apiKey, selectedParametersIds, selectedSystemPromptId)` has no debounce. The sibling `ExternalServicesScreen` (SetupScreens.kt:716-734) was explicitly fixed with a 400 ms debounce + dispose-flush for exactly this reason; this screen never got the same fix.
**Proposed fix:** Debounce the key write (e.g. `delay(400)` keyed on `apiKey`, flush on dispose), mirroring `ExternalServicesScreen`.
**Status:** Fixed (2026-06-07) - provider apiKey/params auto-save now debounced 400ms + flushed on dispose (shared saveProviderEdits), mirroring ExternalServicesScreen

### Bug 5 — Severity: MEDIUM — Category: unexpected spend / no confirmation
**Location:** ServiceSettingsScreens.kt:417-457 (`ProviderModelSettingsScreen` "Test all models")
**Symptom:** The per-provider "Test all models" button fires a live API probe against *every* model in the list (5-wide) with no confirmation dialog, no cost guard, and without consulting the test-excluded list. On a provider with hundreds of models this is a large surprise spend; models previously auto-excluded for costing >5¢ are tested again here.
**Root cause:** `val targets = models.toList()` tests the full list directly; unlike the Housekeeping "Test all models" flow there's no exclusion filter or confirm.
**Proposed fix:** Filter out `aiSettings.testExcludedModels` (and/or add a confirm with the call count) before launching the probes.
**Status:** Fixed (2026-06-07) - per-provider 'Test all models' now filters out aiSettings test-excluded models (matching the Housekeeping flow) before probing

### Bug 6 — Severity: LOW — Category: state/UI mismatch
**Location:** ServiceSettingsScreens.kt:938-968 (`ProviderSettingsScreen` activation Switch)
**Symptom:** Flipping the "Provider inactive" switch OFF (activate) sets `isInactive = false` immediately, then runs fetch+test asynchronously. If activation fails the provider state is set to `"error"` but the switch stays in the active position — the toggle and the actual provider state disagree until the screen is recomposed from settings.
**Root cause:** `isInactive` is local UI state set optimistically before the async result; it is never reverted on the failure branch.
**Proposed fix:** Revert `isInactive = true` (or re-derive from `getProviderState`) when the activation fetch/test fails.
**Status:** Fixed (2026-06-07) — activation fetch/test failures now restore the local inactive switch before setting provider state to error

---

## File: ai/src/main/java/com/ai/ui/admin/CostsMaintenanceScreen.kt

### Bug 7 — Severity: MEDIUM — Category: ANR / main-thread work
**Location:** CostsMaintenanceScreen.kt:56-88 (`buildLayeredCsv`) called from 89-104 (export launcher callbacks)
**Symptom:** Exporting the layered-costs CSV calls `PricingCache.getTierBreakdown(context, provider, model)` once per (active provider × model) synchronously on the main thread inside the `CreateDocument` result callback. For a large catalog (thousands of models, each a layered lookup) this blocks the UI and can ANR.
**Root cause:** The whole CSV build runs in the launcher callback with no `withContext(Dispatchers.IO)`.
**Proposed fix:** Build the CSV (and the import loop, Bug 8) off the main thread; show a spinner while it runs.
**Status:** Fixed (2026-06-07) - both layered-CSV exports build + write inside withContext(Dispatchers.IO) via a coroutine scope; Toast on completion

### Bug 8 — Severity: LOW — Category: main-thread work
**Location:** CostsMaintenanceScreen.kt:105-137 (`importLayeredLauncher`)
**Symptom:** CSV import reads the file and runs `PricingCache.getPricing` + `PricingCache.setManualPricing` per row synchronously on the main thread; a large file janks the UI.
**Root cause:** No `withContext(Dispatchers.IO)` around the read/parse/write loop.
**Proposed fix:** Move the parse + per-row writes off the main thread.
**Status:** Fixed (2026-06-07) — layered CSV import now reads, parses, looks up, and writes overrides inside `Dispatchers.IO`

### Bug 9 — Severity: LOW — Category: locale / comma-decimal
**Location:** CostsMaintenanceScreen.kt:122-123 (`rawIn.toDoubleOrNull()` / `rawOut.toDoubleOrNull()`)
**Symptom:** A layered-cost CSV edited in a comma-decimal spreadsheet (values like `0,5`) imports as null and the row is silently skipped. (Export uses `"%.4f".format(Locale.US,…)`, so app-produced files round-trip, but user-edited ones may not.)
**Root cause:** `.toDoubleOrNull()` is dot-only and not normalized.
**Proposed fix:** Normalize comma→dot before parsing the price columns.
**Status:** Fixed (2026-06-07) — layered cost import normalizes comma decimals before parsing per-million prices

---

## File: ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt

### Bug 10 — Severity: MEDIUM — Category: process-death state loss
**Location:** SettingsScreen.kt:161-166 (`editingAgentId`/`editingFlockId`/`editingSwarmId`/`editingSystemPromptId`/`editingInternalPromptId`/`editingExamplePromptId`) vs 143 (`currentSubScreen` is `rememberSaveable`)
**Symptom:** If the process is recreated (rotation with no `configChanges`, or "Don't keep activities" / low memory while a SAF picker is up) during an Agent/Flock/Swarm/Prompt edit, `currentSubScreen` restores to e.g. `AI_AGENT_EDIT` but the matching `editing*Id` resets to its `initial*` (null for in-app navigation). The edit screen then resolves `agent = null` → renders as **Add** mode, and the edit target is lost; an auto-save can even mint a spurious new entity.
**Root cause:** The `editing*Id` selection vars are plain `remember`, while the sub-screen they drive is `rememberSaveable`. The two restore inconsistently.
**Proposed fix:** Make the `editing*Id` vars `rememberSaveable` (Strings/enum survive the Bundle saver), so the edit target survives alongside the sub-screen.
**Status:** Fixed (2026-06-07) - the editing* id vars are now rememberSaveable, so the edit target survives recreation alongside currentSubScreen

---

## File: ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt

### Bug 11 — Severity: MEDIUM — Category: data fidelity / silent loss
**Location:** ImportExportScreen.kt:323-376 (`buildGeneralSettingsTree`) vs SettingsPreferences.kt:91-153 (`loadGeneralSettings` / `GeneralSettings`)
**Symptom:** The "Settings" JSON export (and the `settings` section of the All bundle) still drops several `GeneralSettings` fields, so round-tripping config to a clean install loses them: `usageStatsEnabled` (the master usage-statistics switch), `uiColorOverridesDay` (day-mode color overrides), `uiColorMode` (NIGHT/DAY selection), `pinnedDashboardCards`, and `dashboardCardOrder`.
**Root cause:** `buildGeneralSettingsTree` enumerates fields by hand and these five were never added (the earlier audit added the metadata/network/throttle set but missed these). The full Backup zip carries them via raw prefs; the JSON Settings export does not.
**Proposed fix:** Emit and re-apply these five fields (setters already exist), or generate the tree from the data class to avoid future drift.
**Status:** Fixed (2026-06-07) - export+import now carry usageStatsEnabled, auditLogEnabled, uiColorMode, uiColorOverridesDay, pinnedDashboardCards, dashboardCardOrder

### Bug 12 — Severity: LOW — Category: main-thread work
**Location:** ImportExportScreen.kt:839-841 (`readFromUri`) used in the import launcher callbacks (e.g. 1498-1511 `all`, 1475-1497 `runtimeAll`)
**Symptom:** Every JSON import branch reads the picked file with `readFromUri` synchronously on the main thread inside the SAF result callback. The comment claims "tiny sync JSON reads", but the All / runtime-All / reports bundles can be large and block the UI.
**Root cause:** Only the zip flows use `Dispatchers.IO`; the JSON branches do not.
**Proposed fix:** Read + parse the larger bundle imports off the main thread.
**Status:** Open

### Bug 13 — Severity: LOW — Category: silent overwrite on re-import
**Location:** ImportExportScreen.kt:626-636 (`applyParameters`), 673-683 (`applySystemPrompts`), and the other `apply*` upsert helpers
**Symptom:** Importing a parameters/system-prompts file upserts by `id`: a row whose id matches an existing preset **replaces** it. A user who exported, then locally edited a preset, then re-imports the old file silently loses the edit with no conflict prompt.
**Root cause:** `merged = working.X.filterNot { it.id in incomingIds } + incoming` — incoming always wins, no merge/conflict surface.
**Proposed fix:** Acceptable for device-sync, but consider skip-if-present (additive) or a conflict count in the toast so silent overwrite is visible.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/SettingsPreferences.kt

### Bug 14 — Severity: LOW — Category: deferred ClassCastException
**Location:** SettingsPreferences.kt:288-293 (`loadProviderSettings`, `${key}_model_types`)
**Symptom:** Per-provider model-type maps are parsed via `gson.fromJson(it, Map::class.java) as? Map<String, String>` — an unchecked cast that defers a `ClassCastException` to first use if any value isn't a String.
**Root cause:** This path was never migrated to a concrete `TypeToken<Map<String,String>>`, unlike `defaultTypePaths` (lines 62-71) which the comment there says was fixed for exactly this reason.
**Proposed fix:** Use `TypeTokens.mapStringStringType` here too.
**Status:** Open

### Bug 15 — Severity: LOW — Category: deferred ClassCastException
**Location:** SettingsPreferences.kt:334-340 (`loadJsonStringSet`)
**Symptom:** `gson.fromJson(json, List::class.java) as? List<String>` — numeric/boolean entries deserialize to `Double`/`Boolean` and the unchecked cast defers a CCE to iteration time.
**Root cause:** Untyped `List::class.java` parse + unchecked cast.
**Proposed fix:** Parse with `TypeTokens.listStringType`.
**Status:** Open

### Bug 16 — Severity: LOW — Category: cost attribution
**Location:** SettingsPreferences.kt:636-640 (`updateUsageStats`)
**Symptom:** The `kind` argument passed by callers is overridden whenever `ApiTracer.currentCategory` is set: `category = normalizeUsageKind(ApiTracer.currentCategory ?: normalizedKind)`. A call site that passes an explicit `kind` but runs inside a tracer-category block has its category silently replaced (cross-references the chat-audit cost-attribution bug).
**Root cause:** `ApiTracer.currentCategory` takes precedence over the explicit `kind`.
**Proposed fix:** Prefer the explicit non-default `kind` when provided, falling back to the tracer category only for the default.
**Status:** Open

### Bug 17 — Severity: LOW — Category: redundant disk IO
**Location:** SettingsPreferences.kt:419-424 (`savePromptToHistory`)
**Symptom:** Each prompt save re-reads the entire history file from disk (`loadPromptHistory()`), mutates, and rewrites — O(n) disk read on every saved prompt.
**Root cause:** No in-memory cache for prompt history (unlike usage stats which got a ConcurrentHashMap cache).
**Proposed fix:** Cache the list in memory, or accept it (history is capped at 100).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/framework/CrudFormScaffold.kt

### Bug 18 — Severity: MEDIUM — Category: excessive prefs writes / perf
**Location:** CrudFormScaffold.kt:81-83 (edit-mode `AutoSaveOnChange`)
**Symptom:** Every CRUD edit form (blocked model, inaccessible model, cooldown, manual override, default-meta-item, parameters, …) auto-persists on each keystroke. Because most `onSave` callbacks serialize the whole `Settings` blob (e.g. blocked/inaccessible "reason" fields), typing in a free-text field rewrites the entire config to SharedPreferences per character.
**Root cause:** `AutoSaveOnChange(current, onSave)` fires on every `current` change with no debounce; the upstream `onSave` is a full-Settings write.
**Proposed fix:** Debounce the auto-save (the same fix Bug 4 needs), or make the save incremental.
**Status:** Fixed (2026-06-07) - already resolved: AutoSaveOnChange debounces (350ms delay on a LaunchedEffect(current) that restarts per change), so it no longer writes per keystroke; flush still happens on dispose. No code change.

---

## File: ai/src/main/java/com/ai/ui/cruds/framework/CrudListPage.kt

### Bug 19 — Severity: LOW — Category: state loss on recreation
**Location:** CrudListPage.kt:65 (`var page by remember { mutableStateOf(0) }`)
**Symptom:** The paging position of every CRUD list resets to page 1 on rotation / process recreation. (Deliberately not keyed on `items.size`, but it's a plain `remember`.)
**Root cause:** `remember`, not `rememberSaveable`.
**Proposed fix:** `rememberSaveable` for `page` (it's coerced against `totalPages` already).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/parameters/list.kt

### Bug 20 — Severity: LOW — Category: inconsistent copy semantics
**Location:** parameters/list.kt:58 (`onCopy = { mode = Mode.Edit(m.item.copy(id = UUID..., name = "...-copy")) }`)
**Symptom:** Duplicating a Parameters preset opens it in **Edit** mode (title reads "Edit Parameters") with a fresh id, and — because edit auto-saves on dispose — the copy is committed even if the user immediately backs out without changing anything. Other CRUDs route copy through an Add screen with an explicit Create button.
**Root cause:** Copy reuses `Mode.Edit` instead of an Add flow.
**Proposed fix:** Route the copy through `Mode.Add`/an add screen for parity, or gate the duplicate commit on an explicit action.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/defaultmetaitems/DefaultMetaItemsCrud.kt

### Bug 21 — Severity: LOW — Category: Compose anti-pattern
**Location:** DefaultMetaItemsCrud.kt:243-247 (`Modifier.clickableNoRipple`)
**Symptom:** The inline ✕ "clear target" affordance allocates a new `MutableInteractionSource()` inside a non-`@Composable` Modifier extension, so a fresh instance is created on every recomposition of the form.
**Root cause:** `MutableInteractionSource()` is created unremembered.
**Proposed fix:** Use `Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null)` from a composable, or the no-arg `clickable` overload.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/models/manualoverrides/edit.kt + ai/src/main/java/com/ai/ui/settings/ManualModelTypesScreen.kt

### Bug 22 — Severity: LOW — Category: duplicate state / ambiguous resolution
**Location:** manualoverrides/edit.kt (`ManualOverrideForm`, CrudFormScaffold auto-save) and ManualModelTypesScreen.kt:33-61,112-129 (`ManualModelOverrideEntryScreen` / `ManualModelTypeEditScreen`, manual Save button)
**Symptom:** There are two divergent editors for `ModelTypeOverride` (one auto-saves via CrudFormScaffold, one uses an explicit Save). Neither dedups on `(providerId, modelId)`: editing an override to point at a (provider, model) that already has its own override, or saving a duplicate, yields two overrides for the same key. Resolution (`modelTypeOverrides.firstOrNull { providerId==.. && modelId==.. }`) then silently picks the first.
**Root cause:** Overrides are keyed by UUID `id`; uniqueness on `(provider, model)` is never enforced on save.
**Proposed fix:** On save, prune/replace any existing override for the same `(providerId, modelId)`; ideally collapse the two editors into one.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/RefreshScreen.kt

### Bug 23 — Severity: LOW — Category: stale-snapshot clobber
**Location:** RefreshScreen.kt:332-347 (`runLiteLLM` / `runModelsDev` → `onSave(aiSettings.recomputeAllCapabilities())`)
**Symptom:** After a long catalog fetch, the capability recompute saves `aiSettings.recomputeAllCapabilities()` where `aiSettings` is the snapshot captured at composition. Any unrelated settings change committed during the fetch window is rolled back by this save.
**Root cause:** The lambda closes over the composition-time `aiSettings` rather than re-reading the latest.
**Proposed fix:** Re-read the current settings inside the save, or fold the recompute into the VM with the live snapshot.
**Status:** Open

### Bug 24 — Severity: LOW — Category: non-cancelable modal / state loss
**Location:** RefreshScreen.kt:62-73,92-98,146-317 (per-tier result vars + `showXDialog`, all plain `remember`)
**Symptom:** (a) The in-progress `AlertDialog` has `onDismissRequest = {}` and an empty `confirmButton`, so a hung fetch leaves a non-dismissable spinner. (b) The per-tier result objects and `showXDialog` flags are plain `remember`; rotating while a result page is open loses the result and closes the page.
**Root cause:** No cancel affordance on the progress dialog; result state isn't `rememberSaveable`.
**Proposed fix:** Add a Cancel that cancels the task; consider saveable result state (or accept the loss, since the run state itself lives in the VM).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/TrimByAgeScreen.kt

### Bug 25 — Severity: LOW — Category: main-thread work
**Location:** TrimByAgeScreen.kt:51-53 (`rCount`/`cCount`/`tCount` in the confirm dialog)
**Symptom:** The confirmation dialog computes report/chat/trace counts by enumerating all reports, chat sessions, and trace files synchronously on the UI thread (acknowledged in the comment). For a device with many reports/traces this janks when the dialog opens.
**Root cause:** Counts read disk in composition rather than on `Dispatchers.IO`.
**Proposed fix:** Compute the counts in a `LaunchedEffect`/`produceState` off the main thread.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/UpdateFromCloudScreen.kt

### Bug 26 — Severity: LOW — Category: battery / wakeups
**Location:** UpdateFromCloudScreen.kt:90-95 (`LaunchedEffect(Unit) { while(true){ delay(5_000); sourceTick++ } }`) + 203 (`queryDocumentInfo` keyed on `sourceTick`)
**Symptom:** While the screen is open it polls the source document's metadata via `ContentResolver.query` every 5 s forever. For a cloud DocumentsProvider URI this re-queries the provider indefinitely (battery / wakeups), purely to refresh the displayed mtime.
**Root cause:** Unbounded `while(true)` ticker with no lifecycle backoff.
**Proposed fix:** Poll only on resume / on demand, or stop the ticker after the first read; `queryDocumentInfo` itself also runs on the main thread per tick.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/AuditScreen.kt

### Bug 27 — Severity: LOW — Category: wrong trace correlation
**Location:** AuditScreen.kt:168-174 (`traceFor`)
**Symptom:** Tapping an API-call audit line opens the trace whose timestamp is nearest within a 30 s window. Two API calls in the same report within 30 s of each other can resolve a line to the wrong trace.
**Root cause:** Timestamp-nearest matching with a coarse 30 s window and no per-call id linkage.
**Proposed fix:** Persist a call id / trace filename on the audit line so the link is exact.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/PromptTranslationsScreen.kt

### Bug 28 — Severity: LOW — Category: shadowing the editable baseline
**Location:** PromptTranslationsScreen.kt:69-94,107-114 (`runTranslation` + target picker)
**Symptom:** From a non-English source, the target language picker accepts English (`InternalPromptSeed.BASE_LANGUAGE`) as a target. Only `source == target` is blocked. Translating *into* English would generate a stored "English" set that shadows the editable English baseline.
**Root cause:** No guard that `target != BASE_LANGUAGE`.
**Proposed fix:** Reject English as a translation target.
**Status:** Open

### Bug 29 — Severity: LOW — Category: main-thread work
**Location:** PromptTranslationsScreen.kt:144 (`PromptTranslationStore.count(context, lang)`) inside the `items(...)` lambda
**Symptom:** Each language row reads its stored-prompt count from disk on the main thread during list composition; with several languages this re-reads on every recomposition.
**Root cause:** Per-row synchronous disk read in the LazyColumn item.
**Proposed fix:** Precompute counts once off-thread (a map keyed on the refresh tick).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/FlocksScreen.kt

### Bug 30 — Severity: LOW — Category: orphaned reference / wrong count
**Location:** FlocksScreen.kt:69-71,144 (`availableAgents` / "N selected of M")
**Symptom:** If a flock's `agentIds` contains an id not present in `aiSettings.agents` (e.g. imported from a Workers bundle whose agents weren't imported), that id is counted in `selectedAgentIds.size` but never appears in `availableAgents`, so the count can read "N selected of M" with N > M, and the dangling id is persisted on save (`selectedAgentIds.toList()`).
**Root cause:** `availableAgents` only includes agents present in settings (active or selected); a fully-absent id is invisible but still counted and saved. (Normal in-app agent deletion auto-prunes flocks, so this is an import-path edge.)
**Proposed fix:** Drop ids not resolvable to a real agent before counting/saving, or surface them as a removable "(missing)" row.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/CachesScreen.kt

### Bug 31 — Severity: LOW — Category: misleading size display
**Location:** CachesScreen.kt:478-482 (`formatBytes`)
**Symptom:** Integer division truncates sizes: a 0.9 MB cache shows "0 MB", 1.9 MB shows "1 MB". Sub-KB shows raw bytes, so the rounding is inconsistent across the screen.
**Root cause:** `bytes / (1024*1024)` integer math with no decimal.
**Proposed fix:** Format with one decimal (`%.1f`, Locale.US) like UpdateFromCloudScreen does.
**Status:** Fixed (2026-06-07) — cache sizes now use one-decimal `Locale.US` KB/MB formatting

### Bug 32 — Severity: LOW — Category: stale UI
**Location:** CachesScreen.kt:267-269 (`CachesHubScreen` `produceState(..., registry)`)
**Symptom:** After deleting entries inside `CacheEntriesScreen` and returning to the hub, the per-cache count/size on the hub is stale — it only recomputes when `registry` changes (which it never does).
**Root cause:** Stats `produceState` is keyed only on the stable `registry`; no resume-refresh tick.
**Proposed fix:** Key the stats `produceState` on a `resumeRefreshTick()` so it recomputes on return.
**Status:** Open

### Bug 33 — Severity: LOW — Category: refresh feedback / fire-and-forget
**Location:** CachesScreen.kt:198-207 (`onRefresh` for "Artificial Analysis"/"OpenRouter") + 378-387 (`CacheEntryRow` wrapper)
**Symptom:** For the keyed pricing tiers, `onRefresh` is `{ _ -> onRefreshKeyed("pricing", s.name) }` — a fire-and-forget dispatch that returns instantly. The row wrapper then flips `busy=false` and bumps `refreshTick` immediately, re-listing before the refresh actually completes, so the entry still shows the old age/"never fetched".
**Root cause:** The keyed refresh isn't awaited; the busy/refresh cycle assumes the lambda blocks until done.
**Proposed fix:** Have the keyed path report completion (suspend or callback) before clearing busy / re-listing.
**Status:** Open

### Bug 34 — Severity: LOW — Category: UX
**Location:** CachesScreen.kt:330,378-387,457-459 (single `busy` flag)
**Symptom:** A single screen-level `busy` flag gates `enabled` on every row's 🔄 button, so refreshing one entry disables refresh on all entries until it finishes.
**Root cause:** `busy` is per-screen, not per-entry.
**Proposed fix:** Track busy per entry id (or accept it).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt

### Bug 35 — Severity: LOW — Category: misleading success / inconsistency
**Location:** LocalRuntimeScreens.kt:314-319 (`LocalLlmsScreen` Remove)
**Symptom:** Removing a Local LLM does `File(...).delete()` but ignores the boolean result and always reports `"Removed $name"`. If the `.task` is locked / in use, the file survives but the user is told it was removed (the list re-read at line 317 may still show it). The sibling LiteRT screen (lines 142-153) was explicitly fixed to check `delete()` and report "Could not remove … (file in use?)".
**Root cause:** Return value of `delete()` discarded; success message unconditional.
**Proposed fix:** Branch on `delete()` like the LiteRT screen does.
**Status:** Fixed (2026-06-07) — Local LLM removal now checks `delete()` and reports a failure when the file survives

---

## File: ai/src/main/java/com/ai/ui/settings/SetupScreens.kt

### Bug 36 — Severity: LOW — Category: invalid id / prefs-key collision
**Location:** SetupScreens.kt:645-688 (`AddProviderNameDialog`)
**Symptom:** A new provider id only has spaces stripped (`normalized = name.trim().replace(" ", "")`). Characters like `/`, `:`, `.` are accepted, but the id is used as a SharedPreferences key prefix (`<id>_api_key`, `<id>_manual_models`, …) and in `provider:model` composite keys. A `/` or `:` in the id can collide with or corrupt key parsing.
**Root cause:** Only spaces are sanitized; the reserved `Local` id is rejected but punctuation isn't.
**Proposed fix:** Restrict the id to `[A-Za-z0-9._-]` (or similar) like the model-file sanitizers do.
**Status:** Fixed (2026-06-07) — add-provider IDs are now limited to letters, numbers, dot, dash, and underscore after spaces are stripped

### Bug 37 — Severity: LOW — Category: stale prop / external change
**Location:** SetupScreens.kt:707-709 (`ExternalServicesScreen` `hfKey`/`orKey`/`aaKey` `remember`)
**Symptom:** The three key fields are `remember { mutableStateOf(prop) }` without keying on the incoming prop. If the key changes elsewhere while the screen is open (e.g. an All-bundle import on another screen, or a reset), the field keeps showing the stale value.
**Root cause:** `remember` not keyed on the prop (and no re-sync effect).
**Proposed fix:** Key the remember on the incoming value, or add a sync `LaunchedEffect`.
**Status:** Fixed (2026-06-07) — external-service key fields are now remembered with their incoming prop as the key

### Bug 38 — Severity: LOW — Category: count mismatch / unreachable category
**Location:** SetupScreens.kt:361-365 (`PromptsSetupScreen.internalTotal`) and 399-414 (`InternalPromptsHubScreen`)
**Symptom:** `internalTotal` sums categories `meta + meta_compare + fan_out + fan_in + internal + workers + alt`. The InternalPrompt CRUD docs reference `icons` and `info` categories; if any prompts carry those categories they're neither counted in the badge nor reachable from any hub card.
**Root cause:** The category set on the hub/count diverges from the categories that can exist in `internalPrompts`.
**Proposed fix:** Confirm `icons`/`info` are folded into `internal`; if they can still exist, count and surface them (or migrate them).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/DeveloperScreens.kt

### Bug 39 — Severity: LOW — Category: secret persisted in plaintext
**Location:** DeveloperScreens.kt:50,245-255 (`ApiTestScreen` saves `last_test_api_key` to `eval_prefs`)
**Symptom:** The API Test screen persists the typed raw API key (and the raw request JSON, which may embed a key) into `eval_prefs` for convenience. Because `eval_prefs` is the app's main prefs file, the raw test key rides along in the full Backup zip.
**Root cause:** Convenience persistence of a credential to the primary prefs store.
**Proposed fix:** Don't persist the test key (or store it in a transient/excluded store).
**Status:** Open

### Bug 40 — Severity: LOW — Category: no error feedback
**Location:** DeveloperScreens.kt:215-225 (`ApiTestScreen` model fetch)
**Symptom:** The "..." model picker fetch wraps `AnalysisRepository().fetchModels` in `catch (_: Exception) { emptyList() }`. A failed fetch (bad key / network) shows only "No models loaded yet — fetch first." with no error, indistinguishable from an empty catalog.
**Root cause:** Exception swallowed with no surfaced message.
**Proposed fix:** Surface the failure (toast / inline) instead of an empty list.
**Status:** Open

### Bug 41 — Severity: LOW — Category: wrong trace correlation
**Location:** DeveloperScreens.kt:347-351 (`EditApiRequestScreen` newest-trace pick)
**Symptom:** After submitting the hand-crafted request, the screen identifies "this call's" trace as `getTraceFiles().firstOrNull()` whenever the count increased. A concurrent flow whose trace lands in the same window would be opened instead.
**Root cause:** Newest-trace heuristic with no run/host correlation (the Agent test path, by contrast, filters by host and start time).
**Proposed fix:** Correlate by a captured start timestamp + host like the Agent/Provider test flows.
**Status:** Open

### Bug 42 — Severity: LOW — Category: locale / comma-decimal
**Location:** DeveloperScreens.kt:287 (`EditApiRequestScreen` temperature parse)
**Symptom:** The developer test request builds `temperature` via `prefs.getString(...).toFloatOrNull()`; on a comma-decimal locale a `0,7` temperature entered on the API Test screen is dropped.
**Root cause:** Dot-only `toFloatOrNull`.
**Proposed fix:** Normalize comma→dot (dev-screen, low impact).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/AppLogScreen.kt

### Bug 43 — Severity: LOW — Category: misleading size display
**Location:** AppLogScreen.kt:301-305 (`formatBytes`)
**Symptom:** Same integer-truncation issue as CachesScreen — `b / (1024*1024)` shows e.g. a 1.8 MB log as "1 MB".
**Root cause:** Integer division, no decimal.
**Proposed fix:** Use `%.1f` (Locale.US).
**Status:** Open

### Bug 44 — Severity: LOW — Category: main-thread work
**Location:** AppLogScreen.kt:184-187 (`confirmClearAll`) and 207-209 (`confirmTrim`)
**Symptom:** After clearing / trimming, the screen reloads `AppLog.getLogFiles()` synchronously on the main thread inside the dialog button handler (the initial load uses `Dispatchers.IO`).
**Root cause:** The post-action reload isn't dispatched off-thread.
**Proposed fix:** Reload via the existing `refreshTick` / `produceState` path instead of a synchronous call.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/AgentsScreen.kt

### Bug 45 — Severity: LOW — Category: edit loss while a field is transiently invalid
**Location:** AgentsScreen.kt:187-190 (`AutoSaveOnChange(current = if (nameError == null) buildAgent(...) else null)`); same pattern in FlocksScreen.kt:123-127, SwarmsScreen.kt:145-149, ParametersScreen.kt:93-96, SystemPromptsScreen.kt:72-76, InternalPromptsScreen.kt:262-265
**Symptom:** In edit mode (no Save button), if a required field is temporarily invalid (e.g. the user clears the name to retype it, or a duplicate name), `current` is null so auto-save is suppressed. Edits made to *other* fields (model, API key, params) while the name is blank are not persisted, and are lost if the user leaves the screen before fixing the name.
**Root cause:** `current` collapses to null on any validation failure, gating the whole save rather than just the invalid field.
**Proposed fix:** Persist the last valid snapshot of the other fields, or warn on leave when there are unsaved edits behind a validation error.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/share/ShareChooserScreen.kt

### Bug 46 — Severity: LOW — Category: feature gap
**Location:** ShareChooserScreen.kt:88-94 ("New Chat" card)
**Symptom:** The "New Chat" destination is `enabled = hasText` only. Sharing a file-only payload (e.g. an image with no EXTRA_TEXT) cannot be routed to Chat, even though chat supports image attachments; the user is forced to use New Report.
**Root cause:** Enablement keyed on `hasText`, ignoring `hasUris`.
**Proposed fix:** Allow Chat for image attachments (enable on `hasText || hasUris-with-image`), staging the file as the first turn's attachment.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/share/ExternalIntentConfirmScreen.kt

### Bug 47 — Severity: LOW — Category: duplicate disclosure
**Location:** ExternalIntentConfirmScreen.kt:163-171 (`SideEffectsCard`)
**Symptom:** When the intent sets both an explicit `email` and `nextAction == "email"`, the card shows two lines: "Email the report to {address}" and "Email the report to your default address". The user sees a redundant/contradictory disclosure of one action.
**Root cause:** The `email` field and the `nextAction` email case are rendered independently with no de-dup.
**Proposed fix:** Collapse to a single line when both express an email side effect.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/SharedContent.kt

### Bug 48 — Severity: LOW — Category: URL detection edge cases
**Location:** SharedContent.kt:34-38 (`isUrl`)
**Symptom:** `isUrl` requires the entire trimmed text to be a single whitespace-free token starting with http(s)://. A shared URL with any surrounding text, a trailing label, or a markdown link (`[x](https://…)`) is not recognized, so the "Add to Knowledge as URL" affordance is hidden for many real share payloads (browsers often append the page title to EXTRA_TEXT).
**Root cause:** Whole-string match with a no-internal-whitespace rule.
**Proposed fix:** Extract the first http(s) token from the text rather than requiring the entire payload to be the URL.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/StressTestScreen.kt

### Bug 49 — Severity: LOW — Category: unbounded spend
**Location:** StressTestScreen.kt:98-117 (confirm) → `engine.start(context)`
**Symptom:** Starting the stress test submits one report per Example Prompt, each running swarm "Level 2"'s full model set, with no cap and no displayed call count. The confirm warns "can be a lot of API calls" but doesn't show how many reports/models will fire.
**Root cause:** No pre-flight count or cap surfaced before the fan-out.
**Proposed fix:** Show "{N prompts × M models = K calls}" in the confirm so the spend is visible.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/CostsMaintenanceScreen.kt (continued)

### Bug 50 — Severity: LOW — Category: spreadsheet round-trip
**Location:** CostsMaintenanceScreen.kt:56-87 (`buildLayeredCsv`) + 113-135 (import)
**Symptom:** The layered CSV is comma-separated and the prices use `.` decimals (Locale.US). On a comma-decimal locale, opening the file in a localized spreadsheet (which may switch to `;` separators and `,` decimals) and re-saving produces a file the importer can't parse (fields split wrong, prices null). There's no separator/decimal detection on import.
**Root cause:** Fixed comma-separator + dot-decimal assumption, no locale tolerance on import.
**Proposed fix:** Accept `;`-separated / comma-decimal variants on import, or document that the file must stay US-formatted.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/models/cooldowns/list.kt

### Bug 51 — Severity: LOW — Category: non-reactive store / stale list
**Location:** costsmanualoverride/list.kt:50-57 (`CostManualOverridesCrud`) and the manual-override / cooldown CRUDs that read non-reactive stores
**Symptom:** `PricingCache` manual overrides aren't a reactive flow; the cost-override CRUD re-reads only on `refreshTrigger` / `resumeRefreshTick`. If an override is changed by another surface (e.g. Model Info's "Add manual override") while this list is open in the back-stack, the list shows stale rows until a resume.
**Root cause:** Manual pricing lives in a non-observable store; refresh is tick-driven, not push.
**Proposed fix:** Acceptable given the resume-tick, but a shared reactive flow would remove the staleness window. (Cooldowns, by contrast, do use a `collectAsState` flow — good.)
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/InternalPromptsScreen.kt

### Bug 52 — Severity: LOW — Category: per-prompt preset name vs id fragility
**Location:** InternalPromptsScreen.kt:162-188 (`selectedParametersName` / `selectedSystemPromptName` stored as NAMES)
**Symptom:** An internal prompt stores its Parameters / System-prompt selection by **name** (`*NONE` sentinel), not id. Renaming the referenced Parameters/System-prompt preset silently unlinks it (the name no longer resolves), and two presets with the same name are ambiguous.
**Root cause:** Persisting a human-editable name as the foreign key instead of the stable id.
**Proposed fix:** Store the preset id (resolve to name for display), or re-point on rename.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/BackupRestoreScreen.kt

### Bug 53 — Severity: LOW — Category: incomplete disclosure
**Location:** BackupRestoreScreen.kt:80-81 (restore confirm text)
**Symptom:** The restore confirmation says it "overwrites all current configuration, API keys, reports, chats, and traces", but `BackupManager` excludes (and preserves) `local_llms/` + `local_models/`. A user restoring onto a device with different local models isn't told those are left as-is.
**Root cause:** Confirm copy doesn't mention the preserved local-runtime exclusions.
**Proposed fix:** Note that installed Local LLM/LiteRT models are kept (not restored), matching the backup-exclude design.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ServiceSettingsScreens.kt (continued)

### Bug 54 — Severity: LOW — Category: error feedback parity
**Location:** ServiceSettingsScreens.kt:431-444 ("Test all models" per-model result)
**Symptom:** When a single model test throws (vs returns a failure), `runCatching{...}.getOrElse { false to null }` records it as a plain Fail with a null trace, so the row's ✕ has no 🐞 deep-link and the user can't see why it failed (unlike the single-model test path which captures a trace).
**Root cause:** Thrown exceptions are flattened to `false to null` with no captured trace/message.
**Proposed fix:** Capture the exception's trace/message into `ModelTestStatus.Fail` so the row can link to it.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt (continued)

### Bug 55 — Severity: LOW — Category: back-navigation correctness
**Location:** SettingsScreen.kt:279 (`AI_DEFAULT_META_ITEMS -> currentSubScreen = SETTINGS_AUTOSTART`)
**Symptom:** Back from the Default-meta-items CRUD always lands on `SETTINGS_AUTOSTART`, on the assumption it was reached from Autostart. If it's ever reached from another entry point (the SetupScreens comment notes it "moved"), back goes to the wrong screen.
**Root cause:** Hard-coded parent in `goBack` rather than tracking the actual caller.
**Proposed fix:** Verify it's only reachable from Autostart, or track the entry point.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/BrokenWorkScreen.kt

### Bug 56 — Severity: LOW — Category: stale detail after action
**Location:** BrokenWorkScreen.kt:110-116 (BrokenItemsScreen restart/delete close the overlay)
**Symptom:** Per-item restart/delete in the detail overlay always `viewing = null` (closes the overlay) rather than reloading the remaining items, so a user fixing items one at a time is bounced back to the batch list after each action instead of staying on the (now-shorter) item list.
**Root cause:** The detail handlers close the overlay instead of re-running `loadItems`.
**Proposed fix:** Re-load the item list and stay on the detail screen when items remain.
**Status:** Open
