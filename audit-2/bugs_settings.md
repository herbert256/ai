# Settings / Admin / Trace / Housekeeping — Bug review (audit-2, fresh from current code)

Scope: `ui/settings/**`, `ui/admin/**`, `ui/cruds/**`, `ui/share/**`, plus the
data-layer helpers these screens call (`BackupManager`, `PricingCache`,
`GeneralSettings`). Pure `*Help.kt` content files reviewed only for logic bugs.
Line numbers verified against the live tree on branch
`codex-model-response-icon-trace`.

---

## File: ai/src/main/java/com/ai/ui/settings/AgentsScreen.kt

### Bug 1 — Severity: HIGH — Category: feature broken / no result feedback
**Location:** AgentsScreen.kt:280-318 (`AgentEditScreen`, Test Agent button)
**Symptom:** Tapping "Test Agent" shows "Testing..." then silently returns to "Test Agent" with no pass/fail message. The user never sees whether the model worked.
**Root cause:** The launch block computes `val error = onTestAiModel(selectedProvider, key, effectiveModel)` (line 290) but never assigns the outcome to `testResult` / `testSuccess`. `testResult` is set to `null` at the start (line 282) and is never written again; `testSuccess` stays at its `false` initial. The result text at line 317 (`testResult?.let { … }`) therefore never renders. The `error` local is dead.
**Reproduction:** Open an Agent with a valid key → Test Agent → spinner flips back, no green/red line appears.
**Proposed fix:** After the call: `testSuccess = error == null; testResult = error ?: "OK"` (matching the pattern used in ProviderSettingsScreen's test path).
**Status:** Open

### Bug 2 — Severity: LOW — Category: data ordering
**Location:** FlocksScreen.kt:38,102 (`FlockEditScreen`) vs SwarmsScreen.kt:123
**Symptom:** Flock member order is not preserved; on each save the agent order is whatever `Set` iteration yields.
**Root cause:** `selectedAgentIds` is a `Set<String>`; saved via `selectedAgentIds.toList()`. The Swarm path keeps an ordered `List<SwarmMember>`, and even `.distinct()`s its params — the Flock path does neither (`selectedParamsIds` is saved without `.distinct()`).
**Proposed fix:** Track flock membership as an ordered list (or `LinkedHashSet`), and `.distinct()` paramsIds on save for parity with Swarm.
**Status:** Open

### Bug 3 — Severity: LOW — Category: hidden state / UX
**Location:** FlocksScreen.kt:60-66,124 (`availableAgents` filter)
**Symptom:** When a flock already contains an agent whose provider later goes inactive, that agent vanishes from the picker list but is still counted in "N selected of M" and silently kept on save. The user can neither see nor uncheck it.
**Root cause:** `availableAgents` filters to `isProviderActive` only; `selectedAgentIds` retains the hidden id. The count line uses `selectedAgentIds.size` against `availableAgents.size`, so "selected" can exceed "of".
**Proposed fix:** Always render selected-but-inactive members (greyed) so they're visible and removable; or compute the count over the union.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/costsmanualoverride/edit.kt

### Bug 4 — Severity: HIGH — Category: data loss / orphaned entry
**Location:** costsmanualoverride/edit.kt:20-34 (`CostOverrideEdit`) + StatisticsScreen.kt:274-370 (`AddManualOverrideScreen.onSave`)
**Symptom:** Editing a manual cost override and re-pointing it to a different (provider, model) creates a SECOND override and leaves the original on file. The user ends up with a duplicate where they intended a move.
**Root cause:** `CostOverrideEdit` passes `originalProviderId`/`originalModel` purely to gate the duplicate-mode Save button. `onSave` only calls `PricingCache.setManualPricing(provider, model, …)` for the NEW key; it never calls `removeManualPricing` for the original key. Contrast the blocked/test-excluded/inaccessible CRUDs (blocked/list.kt:34-37, testexcluded/list.kt:30-33, inaccessible/list.kt:30-33) which all "drop the original composite key first" on edit.
**Reproduction:** AI Setup → Costs → tap an override → Edit → repoint the model → Save. The old (provider, model) override remains in `getAllManualPricing`.
**Proposed fix:** In the Edit `onSave`, when `(provider.id, model) != (originalProviderId, originalModel)`, call `PricingCache.removeManualPricing(context, originalProvider, originalModel)` before/after the set. (Cleaner: route cost-override edits through the same "prune original then upsert" pattern the model-state CRUDs use.)
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt

### Bug 5 — Severity: HIGH — Category: data fidelity / silent loss
**Location:** ImportExportScreen.kt:254-312 (`buildGeneralSettingsTree` / `applyGeneralSettings`) vs AppViewModel.kt:40-281 (`GeneralSettings`)
**Symptom:** The "Settings" JSON export/import (and the "settings" section of the All bundle) silently drops most of `GeneralSettings`. Round-tripping config to a new device loses these and falls back to defaults.
**Root cause:** `buildGeneralSettingsTree` emits only ~11 fields (userName, defaultEmail, defaultTypePaths, tracingEnabled, fullScreen, modelNameLayout, iconGenEnabled, reportTitleMode, showKnowledgeCard, autoCreateRerankAndModeration, experimentalFeaturesEnabled, viewTileOrder). NOT carried: `metadataEnabled`, `reportLanguageGenEnabled`, `perModelIconGenEnabled`, `perModelTitleGenEnabled`, `useInternalPromptsIcons`, `autostartFanIconsAndTitles`, the whole `metadataIcons` blob, `appWideSystemPromptId`/`appWideParametersIds`, `reportModelSystemPromptId`/`reportModelParametersIds`, every network/throttle/retry field (`streamingReadTimeoutSec`, `maxCallsPerProviderPerMinute`, `maxConcurrent*`, `maxRetriesOn429/529`, `retryBackoffMs*`, `maxTestApiCalls`), `logLevel`, and `recentReportModels`. `applyGeneralSettings` mirrors the same short list.
**Reproduction:** Toggle off "Generate report icon" + set custom default icons + change retry counts → Export Settings → import on a clean install → all of those revert to defaults.
**Proposed fix:** Emit and re-apply the full `GeneralSettings` surface (the per-field setters already exist). Note the full-zip Backup path does carry these via raw prefs — but the JSON Settings export is advertised as "Settings" and users will expect parity.
**Status:** Open

### Bug 6 — Severity: HIGH — Category: wrong-branch parse on process death
**Location:** ImportExportScreen.kt:642 (`var importType by remember`) + the OpenDocument launcher at 948
**Symptom:** If Android kills the app process while the SAF file picker is in the foreground (common on low-memory devices), then on return the imported file is parsed as the WRONG type — defaulting to "keys". A model-lists or All bundle could be fed into the API-keys importer (mostly a no-op + confusing toast); worse, a costs CSV could be mis-parsed.
**Root cause:** `importType` is plain `remember`, not `rememberSaveable`. Every Import button sets `importType` right before `launch(...)` (e.g. lines 1530, 1671), but that value is lost across process recreation while the external picker activity is up. The result callback then reads the default `"keys"`.
**Reproduction:** Enable "Don't keep activities" in developer options → tap "Import" on the All row → pick a file → on return it's handled as `keys`.
**Proposed fix:** `var importType by rememberSaveable { mutableStateOf("keys") }`.
**Status:** Open

### Bug 7 — Severity: MEDIUM — Category: unhandled exception aborts bulk import
**Location:** ImportExportScreen.kt:1352-1366 (All-bundle "costs" section)
**Symptom:** A single malformed `inputPerMillion`/`outputPerMillion` (e.g. a string, or `"NaN"`) in the All bundle's costs array throws and aborts the ENTIRE bundle import partway through — later sections (providers, prompts, model lists, …) never apply, and any earlier `setManualPricing`/`ProviderRegistry`/cooldown writes are already committed, leaving a half-imported state.
**Root cause:** The costs `.let` block calls `o.get("inputPerMillion")?.asDouble` with no try/catch. `JsonPrimitive.asDouble` on a non-numeric primitive throws `NumberFormatException`. The standalone CSV importer uses `toDoubleOrNull()` (line 1048) and is safe; the bundle path is not.
**Proposed fix:** Guard each numeric read with a runCatching / `asJsonPrimitive.isNumber` check, or wrap the whole costs loop in try/catch like the apiKeys section (1336-1349).
**Status:** Open

### Bug 8 — Severity: MEDIUM — Category: partial-restart / stale singletons
**Location:** ImportExportScreen.kt:1450-1456 ("all" import) + 1468-1470 (RestartAppBanner)
**Symptom:** After an All import the in-memory Settings/ProviderRegistry/PricingCache singletons are out of sync until the user taps Restart — but the banner is dismissible by scrolling/navigating, and `onSave(working)`/`onSaveGeneral` have already mutated the live state asynchronously. Mixed old/new state is reachable if the user doesn't restart (e.g. they navigate back into a CRUD that reads the now-half-updated registry).
**Root cause:** The bundle writes directly to PricingCache (1362) and ModelCooldownStore (1431) AND queues an async settings save, then only shows a non-blocking restart banner. There's no gate preventing further interaction.
**Proposed fix:** Either make the restart modal/blocking for the All import (as the Backup restore does — though that one is also a non-blocking banner, see Bug 12), or apply everything atomically. At minimum document that mid-import navigation is unsupported.
**Status:** Open

### Bug 9 — Severity: LOW — Category: round-trip fidelity
**Location:** ImportExportScreen.kt:587-588 (`buildAllBundle` examples) — `linkedMapOf("title", "text")`
**Symptom:** Example prompts in the All bundle carry only title+text; any other ExamplePrompt field is dropped on export. (Same for the standalone examples export at 741.)
**Root cause:** Hand-built map shape rather than serialising the data class. Matches the assets/examples.json seed shape, so this is intentional for the seed importer — but it means example-prompt config is lossy through the bundle if ExamplePrompt grows fields.
**Proposed fix:** Acceptable as-is if ExamplePrompt stays {title,text}; flag if the model gains fields.
**Status:** Open

### Bug 10 — Severity: LOW — Category: stale documentation
**Location:** ApiKeyTransfer.kt:30-39 vs :47-49 (`buildApiKeysJson`)
**Symptom:** The doc comment says provider keys are exported under `AppService.displayName`; the code actually uses `service.id` (line 49). Import matches by `id` (line 87-89), so the round-trip is correct — only the comment is wrong, which could mislead someone hand-editing a keys.json.
**Root cause:** Comment not updated when the key field changed from displayName to id.
**Proposed fix:** Fix the comment to say `service.id`.
**Status:** Open

---

## File: ai/src/main/java/com/ai/data/BackupManager.kt

### Bug 11 — Severity: MEDIUM — Category: stale config survives restore
**Location:** BackupManager.kt:208-261 (`restore`), :374-385 (`applyPrefsOnly`), :447-458 (`clearFilesDirForRestore`)
**Symptom:** A SharedPreferences file present on the target device but ABSENT from the backup is never cleared on restore. `clearFilesDirForRestore` wipes filesDir, and `applyPrefs` clears each restored prefs file individually — but a device-only prefs file (e.g. a provider-config prefs file for a provider that existed on the target but not in the older backup) survives intact and leaks stale config into the "restored" install.
**Root cause:** Restore is additive over `shared_prefs/`: only prefs files named in the zip are touched (`applyPrefsOnly` iterates staged entries). There's no "delete prefs files not in the backup" pass equivalent to `clearFilesDirForRestore`.
**Reproduction:** Device A backs up. Device B (with an extra provider configured in its own prefs file) restores A's backup → B keeps the extra provider's stale prefs.
**Proposed fix:** Before `applyPrefsOnly`, enumerate `shared_prefs/*.xml`, and for any whose backup counterpart (`prefs/<name>.json`) is absent, `getSharedPreferences(name).edit().clear().commit()` (respecting any deliberate preserve-list). Document the chosen semantics.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/BackupRestoreScreen.kt

### Bug 12 — Severity: MEDIUM — Category: misleading confirm copy
**Location:** BackupRestoreScreen.kt:80 (restore confirm dialog text) vs :96-98,132-134 (RestartAppBanner)
**Symptom:** The restore confirmation dialog states "The app will restart automatically when restore finishes." It does NOT — on success the code sets `restartMessage` and renders a manual "Restart" banner; the user must tap it. State is incoherent until they do.
**Root cause:** Copy left over from an earlier auto-restart implementation; the flow was changed to a non-blocking banner but the dialog text wasn't updated.
**Proposed fix:** Reword to "…you'll be prompted to restart when restore finishes," or actually call `restartApp` on success.
**Status:** Open

### Bug 13 — Severity: LOW — Category: error reporting
**Location:** BackupRestoreScreen.kt:99-105 (restore failure toast)
**Symptom:** On restore failure the user gets a toast but the (validate-then-write) design means filesDir/prefs are untouched — however the toast doesn't say "your data is unchanged," so a user seeing "Restore failed" may assume the app is now broken.
**Root cause:** Failure message omits the safety guarantee that the validate-then-write flow actually provides.
**Proposed fix:** Append "— existing data left unchanged" to the failure toast.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/ResetScreen.kt

### Bug 14 — Severity: MEDIUM — Category: stale in-memory state after reset
**Location:** ResetScreen.kt:331-357 (`ResetApplicationScreen` post-reset banner)
**Symptom:** After a successful "Reset application", three of the four follow-up buttons ("Refresh all", "Refresh providers…", "Import API keys") run while the in-memory singletons are stale (the disk was reset but the running process still holds pre-reset Settings/registry — the comment at 276-279 admits this). Only "Restart application" reconciles. Tapping "Import API keys" navigates to Import/Export operating on stale in-memory `aiSettings`/`generalSettings`, so imported keys merge onto a stale snapshot and a later save can resurrect just-wiped config.
**Root cause:** The reset clears disk + recreates the file state but doesn't reload the in-memory `_uiState`; the banner offers actions that assume fresh in-memory state. The comment even calls three of them "lead to a restart eventually," but only one button restarts.
**Proposed fix:** Force a restart (or a full in-memory reload) before any of the post-reset actions, or have Refresh/Import first re-read settings from disk.
**Status:** Open

### Bug 15 — Severity: LOW — Category: count accuracy
**Location:** ResetScreen.kt:182-219 (`ResetAssetsScreen`) — `if (n >= 0)` success branch
**Symptom:** An asset reset that legitimately loads 0 entries (empty asset list) reports "Loaded 0 …", indistinguishable in tone from a real failure (`n < 0`). Acceptable, but if the asset file is present-but-empty vs missing the user can't tell why nothing happened.
**Root cause:** The reload helpers return `-1` for "couldn't read" and a count otherwise; an empty-but-valid file returns 0.
**Proposed fix:** Cosmetic; consider a distinct message for the 0-entry case.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/UpdateFromCloudScreen.kt

### Bug 16 — Severity: HIGH — Category: ANR / main-thread I/O
**Location:** UpdateFromCloudScreen.kt:131-145 (Update button onClick) → :312-323 (`installFromUri`)
**Symptom:** Tapping "Update" copies the entire APK (tens of MB, often re-fetched from Drive's cloud DocumentsProvider) on the main thread inside the button's `onClick`. On a slow connection this blocks the UI thread and ANRs.
**Root cause:** `installFromUri` does `contentResolver.openInputStream(...).copyTo(output)` synchronously, and it's called directly in the Composable's onClick with no coroutine / `Dispatchers.IO`.
**Reproduction:** Point at a large Drive-synced APK on a throttled network → tap Update → UI freezes / "App isn't responding".
**Proposed fix:** Wrap the read+copy in `scope.launch { withContext(Dispatchers.IO) { … } }`, show a busy indicator, fire the install intent on the main thread afterward.
**Status:** Open

### Bug 17 — Severity: LOW — Category: stale cached APK reuse
**Location:** UpdateFromCloudScreen.kt:313-320 (`installFromUri`) — fixed cache filename `update.apk`
**Symptom:** If `openInputStream` succeeds but `copyTo` is interrupted/truncated (process killed mid-copy), a partial `update.apk` is left in cache; a subsequent run that fails to open the source could conceivably reference the stale file — though here a fresh copy overwrites it first. Edge case: if `copyTo` throws after partially writing, the function returns false but the truncated file remains for the next FileProvider URI if logic ever changes.
**Root cause:** No write-to-temp-then-rename; reuses a fixed filename.
**Proposed fix:** Copy to `update.apk.tmp`, rename on success, delete on failure.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/TrimByAgeScreen.kt

### Bug 18 — Severity: LOW — Category: main-thread I/O on large datasets
**Location:** TrimByAgeScreen.kt:44-46 (count reads) and :61-65 (delete loop)
**Symptom:** Both the confirm-dialog counts and the actual trim run synchronously on the UI thread, reading every report/chat/trace twice (count + filter). On a device with thousands of reports this can jank or briefly ANR. The code comment (40-43) acknowledges this is intentional-for-now.
**Root cause:** `ReportStorage.getAllReports` / `ChatHistoryManager.getAllSessions` / `ApiTracer.getTraceFiles` parse files from disk on the main thread, called from composition and from the confirm onClick.
**Proposed fix:** Move counts and deletion to `Dispatchers.IO` behind a busy state.
**Status:** Open

### Bug 19 — Severity: LOW — Category: TOCTOU on cutoff
**Location:** TrimByAgeScreen.kt:39 (`cutoff` in the showConfirm branch) and :61 (re-used in onClick)
**Symptom:** `cutoff = now - days` is recomputed on every recomposition of the confirm branch; the count uses one evaluation and the delete onClick captures whatever `cutoff` value is current when the button is tapped. They can differ by the dialog's lifetime, so an item at the boundary could be counted-but-not-deleted (or vice versa) — cosmetic mismatch in the toast count.
**Root cause:** `cutoff` is a plain local recomputed each recomposition, not snapshotted at dialog-open time.
**Proposed fix:** Snapshot `cutoff` once when the dialog opens (remember it), use the same value for count and delete.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/CostsMaintenanceScreen.kt

### Bug 20 — Severity: MEDIUM — Category: silent skip on partial row
**Location:** CostsMaintenanceScreen.kt:114-128 (`importLayeredLauncher`)
**Symptom:** The "Import manual changed costs" path requires BOTH new_input and new_output to parse; a row with only one filled (the common case when correcting just the input price) is silently counted as `skipped` with no per-row explanation. The user expects a single-column edit to apply.
**Root cause:** `if (provider != null && model.isNotBlank() && inp != null && outp != null)` — both must be non-null; a half-filled row falls to `skipped++`.
**Proposed fix:** Either require both explicitly in the help text, or fall back to the existing override/tier value for the missing side (read `getPricingWithoutOverride`) so a one-column edit works.
**Status:** Open

### Bug 21 — Severity: LOW — Category: main-thread CSV build
**Location:** CostsMaintenanceScreen.kt:56-88 (`buildLayeredCsv`) invoked from launcher callbacks (93,101)
**Symptom:** Building the layered CSV calls `PricingCache.getTierBreakdown` for every (active provider × model) on the main thread inside the SAF result callback. With many providers/models this is a noticeable hitch.
**Root cause:** No off-thread dispatch around the per-model tier lookups.
**Proposed fix:** Run `buildLayeredCsv` on `Dispatchers.IO`.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/framework/CrudListPage.kt

### Bug 22 — Severity: LOW — Category: paging state reset
**Location:** CrudListPage.kt:59 (`var page by remember(items.size) { mutableStateOf(0) }`)
**Symptom:** Any change to `items.size` resets the visible page to 0. So deleting an item from a later page bounces the user back to page 1; adding/removing while paged loses position.
**Root cause:** `page` is keyed on `items.size`, so the remember is discarded whenever the list grows/shrinks.
**Proposed fix:** Don't key `page` on size; instead `coerceIn` against `totalPages` on read (already done at line 81 via `safePage`), and drop the `remember(items.size)` key.
**Status:** Open

### Bug 23 — Severity: LOW — Category: layout / clipped rows
**Location:** CrudListPage.kt:76-84 (`pageSize` from `rowHeight = 56.dp`)
**Symptom:** `pageSize` is computed from a fixed 56.dp row height, but `CrudRow` height is content-driven (12.dp + 10.dp×2 vertical padding around 14.sp text ≈ variable). On large-font accessibility settings rows can exceed 56.dp, so the last row on a page is partially clipped / overlaps the next, and swipe paging math drifts.
**Root cause:** Hardcoded row-height estimate doesn't track actual measured row height or font scale.
**Proposed fix:** Either fix the row height (give `CrudRow` an explicit `.height(56.dp)`) or compute pages from measured heights.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/models/cooldowns/edit.kt + list.kt

### Bug 24 — Severity: MEDIUM — Category: copy overwrites source
**Location:** cooldowns/list.kt:54-55 (`onCopy = { mode = Mode.Add(m.item) }`), :72-78 (Add onSaved → `markUnavailable`)
**Symptom:** "Copy" of a cooldown prefills the SAME provider/model (cooldowns/edit.kt:46-47 prefill from initial). If the user saves without changing the model, `markUnavailable(providerId, model, untilMs)` overwrites the original cooldown rather than producing a new row — Copy silently mutates the source.
**Root cause:** Cooldowns are keyed by (provider, model) in `ModelCooldownStore`; Add doesn't force the user to re-point, and there's no "key already exists" guard on the Add path (unlike Edit which prunes the old key first).
**Reproduction:** Cooldown CRUD → tap a row → Copy → Save without editing → original's expiry is replaced, no second entry.
**Proposed fix:** On Copy, either clear the model field (force a re-pick) or detect an unchanged key on the Add path and treat it as edit / block the save.
**Status:** Open

### Bug 25 — Severity: LOW — Category: hours rounding on edit
**Location:** cooldowns/edit.kt:48-56 (`hoursText` initial for edit)
**Symptom:** Editing an existing cooldown shows remaining hours rounded UP and coerced to ≥1, then on save recomputes `now + hours*3600000`. So opening+saving an edit without changes can EXTEND the cooldown by up to ~1 hour (rounding) relative to the original `availableAtMs`.
**Root cause:** `((remainMs + 3_600_000 - 1)/3_600_000).coerceAtLeast(1)` ceilings the remaining time, and Save reconstructs the absolute time from the rounded hours measured from a new `now`.
**Proposed fix:** Store/edit the absolute time, or accept fractional hours, or only rewrite `availableAtMs` when the field actually changed.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/models/manualoverrides/edit.kt

### Bug 26 — Severity: LOW — Category: duplicate (provider,model) overrides
**Location:** manualoverrides/edit.kt:77-86 (id-keyed save) + list.kt:31-37 (`upsert` by id)
**Symptom:** Manual model-type overrides are keyed by random `id`, not by (providerId, modelId). Two overrides for the same (provider, model) can coexist (e.g. via Copy then repoint to the same pair, or two Adds). The resolver consuming `modelTypeOverrides` then has an ambiguous/duplicate entry; which wins depends on list order.
**Root cause:** `upsert` matches on `it.id`; there's no de-dup on the (providerId, modelId) composite. The other model-state CRUDs (blocked/test-excluded/inaccessible) key by the composite and can't duplicate.
**Proposed fix:** On save, drop any existing override with the same (providerId, modelId) before appending, mirroring the composite-key CRUDs.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/framework/CrudViewPage.kt

### Bug 27 — Severity: LOW — Category: confusing entity-type label
**Location:** prompts/internal/InternalPromptCrud.kt:92 (`title = label.removeSuffix("s")`) feeding CrudViewPage `title`
**Symptom:** The internal-prompt View page title is derived by stripping a trailing "s" from the category display name. For labels that don't pluralise with a simple "s" (e.g. "Fan-in-model", "Icons" → "Icon", "Meta" unchanged), this produces awkward or wrong singulars, and the delete confirm dialog (`entityType = title`) inherits the odd label.
**Root cause:** Naive `removeSuffix("s")` pluralisation.
**Proposed fix:** Carry an explicit singular label per category instead of string-munging.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt

### Bug 28 — Severity: MEDIUM — Category: deep-link back-stack escape
**Location:** SettingsScreen.kt:191-258 (`goBack`) — the `currentSubScreen == initialSubScreen` shortcut
**Symptom:** When entered via a deep link (e.g. `initialSubScreen = AI_REFRESH` from Housekeeping), the first guard `if (currentSubScreen == initialSubScreen && initialSubScreen != MAIN) { onBack() }` fires. But if the user navigates DEEPER from that entry (e.g. Refresh → opens a provider via `onOpenProvider`, then back to AI_PROVIDER_EDIT → … → back lands them on AI_REFRESH which equals initialSubScreen), pressing back again exits straight to the caller — skipping the intermediate AI_SETUP level a non-deep-linked user would see. The hierarchy a user perceives differs depending on entry point.
**Root cause:** The "equals initialSubScreen ⇒ exit" rule conflates "I'm on the entry screen" with "I arrived here directly," but after intra-Settings navigation the user can land back on `initialSubScreen` having traversed a sub-tree.
**Proposed fix:** Track an explicit "is this the original direct-entry instance" flag that's cleared once the user navigates away from `initialSubScreen`, rather than re-deriving it by equality each time.
**Status:** Open

### Bug 29 — Severity: LOW — Category: provider-edit dead-end
**Location:** SettingsScreen.kt:337-366 (`AI_PROVIDER_EDIT`) — `selectedProvider?.let { … } ?: goBack()`
**Symptom:** If `selectedProviderId` resolves to null (provider removed/renamed in the registry while this screen is mounted, or a cold-launch race before bootstrap), the screen calls `goBack()` from inside composition. From AI_PROVIDER_EDIT, goBack sets `currentSubScreen = AI_PROVIDERS` (line 201) on every frame until resolution — a recomposition loop / flicker if the id is permanently unresolvable (e.g. after Reset deleted it).
**Root cause:** Calling a state-mutating navigation (`goBack`) directly in the composable body on the null branch, with no one-shot guard.
**Proposed fix:** Use a `LaunchedEffect(selectedProviderId)` to navigate away once, or render a "provider no longer exists" placeholder with a manual back.
**Status:** Open

### Bug 30 — Severity: LOW — Category: debounce vs external update
**Location:** SettingsScreen.kt:889-959 and the other 6 carved-out preference sub-screens (`NetworkSettingsSubScreen`, `MaximalApiCallsSubScreen`, `UiTweaksSubScreen`, `OtherSettingsSubScreen`, `MetadataSettingsSubScreen`, `DefaultIconsSubScreen`, `LoggingAndTracingSubScreen`, `AppSettingsScreen`)
**Symptom:** Each sub-screen mirrors `generalSettings` fields into local `remember` state that is NOT keyed on `generalSettings`. If the parent pushes a new `generalSettings` from another source while the sub-screen is mounted (e.g. an import completing, or a concurrent save round-trip), the local state does not refresh and the next debounced save writes the stale local values back — silently reverting the external change.
**Root cause:** `var x by remember { mutableStateOf(generalSettings.x) }` with `build()` capturing the live `generalSettings` only for the untouched fields; the mirrored fields stay at first-composition values.
**Proposed fix:** This is the documented single-editor assumption and is usually fine, but key the mirrors on `generalSettings` (or use `rememberUpdatedState` + reconcile) if these screens can ever be open during an external write.
**Status:** Open

### Bug 31 — Severity: LOW — Category: missing field in debounced editor
**Location:** SettingsScreen.kt:1597-1655 (`AppSettingsScreen`)
**Symptom:** `AppSettingsScreen` edits `appWideSystemPromptId/ParametersIds` and `reportModelSystemPromptId/ParametersIds`. Because the Settings JSON export (Bug 5) doesn't carry these, a user who configures them here loses them on export/import — compounding Bug 5 for a screen whose whole purpose is these four fields.
**Root cause:** Same omission as Bug 5; called out separately because this screen is the only editor for these fields.
**Proposed fix:** Covered by fixing Bug 5.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ServiceSettingsScreens.kt

### Bug 32 — Severity: MEDIUM — Category: concurrent auto-save races
**Location:** ServiceSettingsScreens.kt:709-807 (Definition `LaunchedEffect` → `ProviderRegistry.update`) and :817-826 (per-user-fields `LaunchedEffect` → `onSave(aiSettings.withProvider(...))`)
**Symptom:** Two debounced auto-save effects run against the same captured `aiSettings`/`service`. The per-user-fields effect reads `aiSettings.getProvider(service)` (the prop at composition) and writes the whole provider config back. If a Definition update or any external Settings change recomposes `aiSettings` between the field edit and the save, the per-user effect can write back a stale provider config, clobbering a just-applied change (the comment at 324-331 explicitly warns about this rollback hazard).
**Root cause:** Field-level auto-saves that each `copy()` the full provider entry from a snapshot, rather than a targeted field merge against the latest state.
**Proposed fix:** Merge only the changed fields against the freshest Settings at save time (read live), or serialise the two effects.
**Status:** Open

### Bug 33 — Severity: LOW — Category: silent drop of invalid regex / numeric fields
**Location:** ServiceSettingsScreens.kt:776-779 (`modelFilter`), :767 (`costTicksDivisor`), :798-805 (rate/retry numerics)
**Symptom:** On the provider-definition auto-save, invalid `modelFilter` regex / non-positive `costTicksDivisor` / out-of-range numerics are silently discarded (fall back to old value or null) with no UI feedback. The user sees their typed text persist in the field but the saved value differs — a confusing "looks saved but isn't" state.
**Root cause:** Intentional non-destructive guard (good for auto-save), but there's no inline error/warning telling the user the value didn't take.
**Proposed fix:** Surface a per-field error/hint when the typed value fails validation.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/share/ShareChooserScreen.kt

### Bug 34 — Severity: LOW — Category: misleading enabled state / description
**Location:** ShareChooserScreen.kt:96-105 (Knowledge card)
**Symptom:** The "Add to Knowledge" card description says it accepts "a file or URL pre-staged" and (in the Report card text) implies raw notes, but `enabled = hasUris || shared.isUrl`. Plain shared text that is NOT a URL leaves the card visible-but-disabled with no explanation, even though "raw note" ingestion is implied elsewhere.
**Root cause:** `enabled` excludes non-URL text; description doesn't match.
**Proposed fix:** Either enable for any `hasText` (if Knowledge can ingest a raw note) or reword the description to "file or URL only".
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/share/ExternalIntentConfirmScreen.kt

### Bug 35 — Severity: LOW — Category: action-headline mismatch
**Location:** ExternalIntentConfirmScreen.kt:134-139 (`headline` when block)
**Symptom:** The `when` has identical bodies for `hasSelect` and the `else` branch ("Open agent/model selection for a report"), so the `hasSelect` case is redundant and any future divergence is silently lost; more importantly a request that is neither edit/auto/select still claims it will "Open agent/model selection," which may not be accurate for a malformed intent.
**Root cause:** Duplicated branch + catch-all assuming selection.
**Proposed fix:** Collapse the duplicate and add an explicit "nothing actionable" headline for the degenerate case.
**Status:** Open

### Bug 36 — Severity: LOW — Category: side-effect disclosure gap
**Location:** ExternalIntentConfirmScreen.kt:165-171 (`SideEffectsCard` nextAction `when`)
**Symptom:** Only the known `nextAction` values (view/share/browser/email) render a line; an unrecognised but non-blank `nextAction` falls to `else -> Unit`, so the confirm screen shows NO line for it even though `hasSideEffects` (line 85-86) was true because `nextAction` is non-blank — the card renders with a header and possibly no body line, and the user isn't told what the unknown action will do.
**Root cause:** `hasSideEffects` keys on `nextAction != null/blank` but the disclosure switch silently drops unknown values.
**Proposed fix:** Add a fallback line "• Run action: <nextAction>" for unrecognised values so the user sees something will happen.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/HousekeepingScreen.kt

### Bug 37 — Severity: LOW — Category: stale gating count
**Location:** HousekeepingScreen.kt:62-64 (`hasTrimmable` gate on the Trim card)
**Symptom:** `hasTrimmable` is passed in by the caller as a one-shot boolean; if the user generates a report/chat while the Housekeeping screen is already open, the Trim card stays hidden until the screen is re-entered (and vice versa — it can stay shown after a full reset elsewhere).
**Root cause:** The trimmable state is a static prop, not observed reactively.
**Proposed fix:** Recompute `hasTrimmable` on resume, or observe the underlying counts.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/workers/agents/list.kt

### Bug 38 — Severity: LOW — Category: copy-then-cancel leaves no trace (acceptable) / name collision deferred
**Location:** agents/list.kt:62 (`onCopy` → `Mode.Edit(copy(id=new, name="-copy"))`)
**Symptom:** Copy of an agent opens the rich edit form with a new id and `<name>-copy`. If `<name>-copy` already exists, the form's `existingNames` (built excluding the new id) flags the collision only at Save time — the user can't tell until they try to save. Consistent across all CRUD copies (parameters, system, examples, internal), so noted once.
**Root cause:** Copy doesn't pre-resolve a unique suffix (e.g. `-copy 2`).
**Proposed fix:** Generate a guaranteed-unique copy name up front.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/models/blocked/edit.kt (+ testexcluded/inaccessible forms)

### Bug 39 — Severity: LOW — Category: reason not preserved on copy
**Location:** blocked/list.kt:55 (`onCopy = { mode = Mode.Add(m.item) }`) → blocked/edit.kt:47 (`reason` prefill)
**Symptom:** Copy of a blocked model prefills providerId/model AND the reason, then Add saves with the same (provider, model) key via `upsert(null, saved)` → `upsertBlockedModel`. If the user doesn't repoint, the copy overwrites the original silently (same key). Same shape as the cooldown copy (Bug 24) but lower impact (idempotent overwrite of identical data).
**Root cause:** Add path keyed by (provider, model) with no "already exists" guard; Copy doesn't force a repoint.
**Proposed fix:** On Copy, clear the model selection (force a new pick) so a copy can't collapse onto its source.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/parameters/list.kt

### Bug 40 — Severity: LOW — Category: misleading "N set" count
**Location:** parameters/list.kt:20-24 (`setCount`)
**Symptom:** The list row shows "<name> · N set" where N counts non-null fields. `seed` is counted even when 0 is a meaningful "no seed" sentinel vs an explicit 0; `searchEnabled`/`webSearchTool` count only when true. A preset that sets `temperature = 0.0` counts it (correct), but the count conflates "field present" with "field meaningful," so two presets with different semantics can show the same N. Cosmetic.
**Root cause:** `listOfNotNull(...).size` plus boolean adds; nullable-but-zero fields are indistinguishable.
**Proposed fix:** Cosmetic; acceptable.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/framework/CrudFormScaffold.kt

### Bug 41 — Severity: LOW — Category: no unsaved-changes guard on back
**Location:** CrudFormScaffold.kt:44 (`BackHandler { onBack() }`) — and the same in every rich edit screen
**Symptom:** Pressing back from any CRUD add/edit form discards all typed input with no confirmation. For long forms (Agent edit, provider definition) an accidental back swipe loses the work.
**Root cause:** Back maps straight to `onBack()`; no dirty-state check.
**Proposed fix:** Optionally prompt when the form has unsaved edits (the screens already track a dirty/`resetTick` baseline that could feed this).
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt (model-lists import)

### Bug 42 — Severity: MEDIUM — Category: silent type/capability wipe on bare-array import
**Location:** ImportExportScreen.kt:357-364 (`applyModelLists`, JsonArray branch) → `s.withModels(service, list)`
**Symptom:** Importing a legacy bare `["model-a","model-b"]` model-lists file for a provider replaces the model list via `withModels`, which (depending on `withModels` semantics) may reset or fail to preserve the provider's existing per-model `modelTypes`/vision/websearch/reasoning/pricing sidecars that the object-shaped export would carry. The object branch (366-405) re-infers `modelTypes` from the imported set if absent, but the bare-array branch delegates entirely to `withModels` and skips the sidecar handling.
**Root cause:** Two divergent code paths; the legacy bare-array path doesn't re-derive or preserve capability metadata.
**Proposed fix:** Funnel the bare-array case through the same object handling with empty sidecars (so `modelTypes` is re-inferred consistently), or document that bare-array imports reset capability metadata.
**Status:** Open

### Bug 43 — Severity: LOW — Category: import counts vs actual writes
**Location:** ImportExportScreen.kt:1043-1055 (costs CSV) and 113-128 CostsMaintenance
**Symptom:** The costs CSV importer counts a row as `imported` after calling `setManualPricing`, but if two CSV rows share the same (provider, model) the second overwrites the first while both increment `imported` — the toast over-reports the number of distinct overrides actually present.
**Root cause:** `imported++` counts calls, not distinct keys.
**Proposed fix:** Count distinct keys, or note "rows processed" vs "overrides".
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/StatisticsScreen.kt

### Bug 44 — Severity: MEDIUM — Category: duplicate-mode Save can overwrite original
**Location:** StatisticsScreen.kt:304-342 (`AddManualOverrideScreen`) — `keyMatchesOriginal` gating
**Symptom:** In duplicate (👯) mode the Save button is disabled only while `keyMatchesOriginal` (same provider AND same model as the source). But a user can change ONLY the input/output prices, leave provider+model identical, and the button stays disabled — yet if they then tweak the model by one char and back, or the original was loaded for a different intent, the guard can be bypassed and the original override is overwritten in place (because `onSave` keys by the current provider/model, identical to the original).
**Root cause:** The only protection against overwriting the source in dup mode is the (provider,model)-equality check; price-only edits in dup mode are ambiguous and the screen has no "create new vs overwrite" choice.
**Proposed fix:** In dup mode require the (provider,model) to differ before enabling Save (already attempted) AND ensure the edit (non-dup) path explicitly removes the original key when repointed (ties to Bug 4).
**Status:** Open

### Bug 45 — Severity: LOW — Category: locale-sensitive price format round-trip
**Location:** StatisticsScreen.kt:298-299 (`"%.4f".format(Locale.US, …)`) and :336-337 (`toDoubleOrNull`)
**Symptom:** Prices are formatted Locale.US (decimal dot) but parsed via `String.toDoubleOrNull()` which is locale-independent (also dot). Consistent — but if a user on a comma-decimal locale types a comma into the price field, `toDoubleOrNull` returns null and Save stays disabled with no hint why.
**Root cause:** No locale-aware input parsing / no inline "use a dot" hint.
**Proposed fix:** Accept comma as decimal separator, or show a validation hint.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt (runtime reports import)

### Bug 46 — Severity: MEDIUM — Category: orphaned secondaries on duplicate report id
**Location:** ImportExportScreen.kt:184-206 (`applyRuntimeReports`)
**Symptom:** When a report id already exists on disk it's skipped (190) and its secondaries are NOT imported (the secondaries loop only runs for newly-added reports). Correct for the report — but if the existing report on disk is missing some secondaries that the import carries, those secondaries are silently lost (merge is all-or-nothing per report id).
**Root cause:** Secondaries are gated entirely behind "the parent report was newly added"; no per-secondary id merge for already-present reports.
**Proposed fix:** For skipped (already-present) reports, optionally merge any secondary rows whose ids aren't on disk.
**Status:** Open

### Bug 47 — Severity: LOW — Category: secondary persisted before validation of parent linkage
**Location:** ImportExportScreen.kt:196-205 (secondary loop)
**Symptom:** A secondary row is saved if `sr.id` and `sr.reportId` are non-blank, but there's no check that `sr.reportId == report.id`. A malformed bundle could attach a secondary with a mismatched reportId under the wrong parent, persisting an orphan.
**Root cause:** Missing `sr.reportId == report.id` guard.
**Proposed fix:** Skip secondaries whose `reportId` doesn't match the parent being imported.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/prompts/internal/InternalPromptCrud.kt

### Bug 48 — Severity: LOW — Category: fixed-list scope drift
**Location:** InternalPromptCrud.kt:42 (`fixedList = category == "internal" || "icons" || "info"`)
**Symptom:** The fixed-list set is hardcoded by category string. If a new built-in category is added (or one renamed), Add/Copy/Delete silently become available/unavailable for the wrong bucket. There's no single source of truth tying "is this a built-in template category" to the category enum/registry.
**Root cause:** Magic-string category gating duplicated from wherever categories are defined.
**Proposed fix:** Centralise the "is fixed/built-in" predicate next to the category definition.
**Status:** Open

### Bug 49 — Severity: LOW — Category: mode not reset across category switch edge
**Location:** InternalPromptCrud.kt:44 (`var mode by remember(category) { … List }`)
**Symptom:** `mode` is keyed on `category`, so switching the open category resets to List (good). But the host (SettingsScreen) sets `selectedInternalCategory` then routes to AI_INTERNAL_PROMPTS; if the same composable instance is reused with a changed category while a View/Edit overlay was open, the in-progress edit is discarded silently (no warning). Minor data-loss surprise.
**Root cause:** Category change unconditionally resets mode.
**Proposed fix:** Acceptable; document or guard if an edit is dirty.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/SettingsPreferences.kt

### Bug 50 — Severity: LOW — Category: Gson Map cast fragility
**Location:** SettingsPreferences.kt:47-53 (`loadGeneralSettings` defaultTypePaths)
**Symptom:** `gson.fromJson(it, Map::class.java) as? Map<String, String>` — Gson deserialises a JSON object to `LinkedTreeMap<String, Object>`, so the unchecked cast to `Map<String,String>` succeeds at the cast but individual values are actually whatever JSON types were stored; a non-string value would later throw `ClassCastException` at use, not here.
**Root cause:** Using `Map::class.java` instead of a `TypeToken<Map<String,String>>`.
**Proposed fix:** Use `TypeTokens.mapStringStringType` (already defined at line 39) for this read.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/HelpScreen.kt / help wiring

### Bug 51 — Severity: LOW — Category: help-topic coverage (process)
**Location:** Cross-cutting: every new screen needs a `helpTopic` wired (per repo convention)
**Symptom:** Several screens pass a `helpTopic` string (e.g. CostsMaintenanceScreen uses `"cost_config"` shared with the cost-config screen rather than a dedicated topic; UpdateFromCloud uses `"update_from_cloud"`). Reusing a sibling's topic means the ❓ opens content that may not match the screen.
**Root cause:** `CostsMaintenanceScreen` (Housekeeping → Costs) reuses `helpTopic = "cost_config"` (CostsMaintenanceScreen.kt:132) which is the AI-Setup cost-config topic, not a maintenance-specific one.
**Proposed fix:** Verify each screen's helpTopic resolves to content describing that exact screen; add a dedicated topic where shared topics drift.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/workers/agents/view.kt

### Bug 52 — Severity: LOW — Category: stale effective-model display
**Location:** agents/view.kt:32 (`shortModelName(aiSettings.getEffectiveModelForAgent(agent))`) and list.kt:50-51
**Symptom:** The Agent view/list shows the EFFECTIVE model (resolving the provider default when the agent's own model is blank). If the provider default changes after the agent was created, the displayed model changes for an agent the user thinks is pinned — and the "(inactive)" tag (list.kt:51) depends on live provider state, so a saved agent can flip label without any edit. Mostly correct behaviour, but the View screen presents it as the agent's own "Model" field with no indication it's inherited.
**Root cause:** No visual distinction between an agent's explicit model and an inherited provider default.
**Proposed fix:** Mark inherited values as "(provider default)".
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/ResetScreen.kt (asset reset)

### Bug 53 — Severity: MEDIUM — Category: destructive asset reset drops user-authored entries
**Location:** ResetScreen.kt:387-403 (`AssetReset`) + 203-208 dispatch
**Symptom:** Each "back to assets/*.json" button "drops every entry in the matching list and reloads from the asset" — destroying user-authored prompts/providers in that list with only a single confirm dialog and no export-first nudge. For Internal/Example/System prompts this can wipe substantial hand-written content irreversibly.
**Root cause:** Full replace-from-asset with no merge option and no "export current first" affordance.
**Proposed fix:** Offer a merge-vs-replace choice, or prompt to export the current list before replacing.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt (workers import)

### Bug 54 — Severity: LOW — Category: cross-reference integrity not checked
**Location:** ImportExportScreen.kt:90-114 (`applyWorkers`)
**Symptom:** Imported flocks/swarms reference agent ids (and agents reference provider ids / params / system-prompt ids). `applyWorkers` upserts them blindly; a flock importing agent ids that aren't present on the target (and weren't in the same bundle) yields a flock pointing at non-existent agents — silently broken at run time, with no warning in the toast.
**Root cause:** No referential-integrity validation across the imported worker graph.
**Proposed fix:** After merge, count dangling references and surface them in the toast (as the AI-report import does for missing providers/agents at 974-990).
**Status:** Open

### Bug 55 — Severity: LOW — Category: parameters/system-prompt ids dangling after partial import
**Location:** ImportExportScreen.kt:1153-1184 (parameters / systemPrompts importers) + worker importer
**Symptom:** Importing only "Agents" (not Parameters/System prompts) brings agents whose `paramsIds`/`systemPromptId` reference presets absent on the target. The resolver then silently falls back; the user gets no indication the agent's prompt/params are missing.
**Root cause:** Section-independent imports with no dependency warning.
**Proposed fix:** Warn when an imported agent/flock/swarm references a params/system-prompt id not present after import.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/BackupRestoreScreen.kt (restore picker mime)

### Bug 56 — Severity: LOW — Category: over-broad picker accept
**Location:** BackupRestoreScreen.kt:177 (`launch(arrayOf("application/zip","application/octet-stream"))`)
**Symptom:** Accepting `application/octet-stream` means many non-zip files appear pickable; the user can pick a random binary, which then fails the manifest check (good) but only after a temp-copy of the whole file. Wasted I/O + confusing failure for an obviously-wrong pick.
**Root cause:** Necessary breadth (some providers mis-report zip mime) trades off against picking arbitrary binaries.
**Proposed fix:** Acceptable given the manifest guard; consider a filename `.zip` extension hint in the failure message.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt (AI report export title)

### Bug 57 — Severity: LOW — Category: filename sanitisation edge
**Location:** ImportExportScreen.kt:696-697 (`safeTitle`)
**Symptom:** `report.title.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40).ifBlank { "report" }` — a title that is all non-ASCII (e.g. CJK or emoji) collapses to a single `_` (not blank), so the export filename becomes `ai_report__<ts>.zip` with a bare underscore, losing all title information without triggering the `report` fallback.
**Root cause:** `ifBlank` doesn't catch an all-separator result.
**Proposed fix:** After sanitising, treat an all-`_`/empty result as blank → "report".
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/models/manualoverrides/list.kt (flags display)

### Bug 58 — Severity: LOW — Category: list line ambiguity
**Location:** manualoverrides/list.kt:38-52 (`flags` + `line`)
**Symptom:** The list line shows `provider / modelId → TYPE 👁🌐🧠`. With duplicate (provider,model) overrides possible (Bug 26), two rows can render identically except by hidden id, so the user can't tell them apart in the list to pick which to delete.
**Root cause:** Line text doesn't disambiguate same-key duplicates.
**Proposed fix:** Fix Bug 26 (prevent duplicates) — then this is moot.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt (internal prompt deep-link)

### Bug 59 — Severity: LOW — Category: category-derivation race
**Location:** SettingsScreen.kt:156-177 (`selectedInternalCategory` init + LaunchedEffect)
**Symptom:** When deep-linking into `AI_INTERNAL_PROMPT_EDIT` before settings bootstrap, the initial `remember` defaults the category to `"internal"`; the LaunchedEffect later corrects it once the prompt resolves. But the edit screen is keyed on `ip?.id` (line 649) and `fixedCategory = selectedInternalCategory` — there's a window where the edit screen composes with the wrong (`"internal"`) fixedCategory before the LaunchedEffect runs, which could pin a save to the wrong category if the user is extremely fast. The loading placeholder (642-647) mitigates the null-id case but not the wrong-category-but-non-null case.
**Root cause:** Category correction is a side effect that can lag the first edit-screen composition.
**Proposed fix:** Derive the category synchronously from `ip` inside the edit branch rather than from the laggy `selectedInternalCategory` state.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/HousekeepingScreen.kt / first-run gating

### Bug 60 — Severity: LOW — Category: Reset reachable but Refresh path assumptions
**Location:** HousekeepingScreen.kt:54-83
**Symptom:** On first run (`hasActiveProvider=false`) the screen still shows Refresh, Costs, Test, Update from cloud, Application log. "Costs" and "Test" operate on active providers/models that don't exist yet — tapping them lands on screens that are empty/no-op with no explanation of why.
**Root cause:** Only Backup/Export/Trim are folded for first-run; Costs/Test aren't gated though they have nothing to act on.
**Proposed fix:** Gate Costs/Test behind `hasActiveProvider` too, or show an empty-state hint on those screens.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/AgentsScreen.kt (endpoint persistence)

### Bug 61 — Severity: MEDIUM — Category: orphaned endpoint on unsaved agent
**Location:** AgentsScreen.kt:235-248 (LiteLLM endpoint → `onAddEndpoint`) + SettingsScreen.kt:501-504/521-524
**Symptom:** Picking a LiteLLM-derived endpoint immediately materialises a real `Endpoint` on the provider (via `onAddEndpoint` → `onSaveAi(withEndpoints(...))`) BEFORE the agent is saved. If the user then backs out of the agent edit without saving, the new endpoint persists on the provider with no agent referencing it — an orphaned custom endpoint accumulates on every cancelled edit.
**Root cause:** Endpoint creation is a side effect of selection, committed to Settings independently of the agent save.
**Proposed fix:** Defer `onAddEndpoint` until the agent is actually saved, or clean up unreferenced LiteLLM-materialised endpoints.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/workers/agents/edit.kt (existingNames in copy)

### Bug 62 — Severity: LOW — Category: name-collision check excludes wrong id on copy
**Location:** workers/agents/edit.kt:46-48 (`existingNames` filter `it.id != (agent?.id ?: "")`) used for both Edit and the Copy→Edit flow
**Symptom:** When AgentsCrud copies (list.kt:62) it hands AgentEdit a NEW agent object (new id, "-copy" name). `existingNames` then excludes the NEW id (which isn't in the list anyway), so the original agent's name is INCLUDED in existingNames — good. But `AgentEditScreen` separately re-adds `agent.name.lowercase()` in dup mode (AgentsScreen.kt:97-99) using the COPY's already-suffixed name, not the source name — so the dedup-against-source logic compares against `<name>-copy`, not `<name>`. Net effect: the user could rename the copy back to the source agent's exact name and pass validation, creating two agents with identical names.
**Root cause:** The copy is delivered as a fresh agent with the suffixed name; the dup-mode "add the source name back" path keys off this already-mutated name.
**Proposed fix:** Route copy through the same Add/dup-mode entry the rich edit uses (so the source name is the baseline), or pass the original name explicitly for the collision set.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt (settings import side-effect ordering)

### Bug 63 — Severity: LOW — Category: viewTileOrder written even on dry/failed import
**Location:** ImportExportScreen.kt:295-298 (`applyGeneralSettings` writes `view_screen_prefs/tile_order`)
**Symptom:** `applyGeneralSettings` writes the tile order to `view_screen_prefs` as an immediate side effect during parsing, BEFORE the caller decides whether to keep the result. In the All-bundle path the settings section runs (1400-1403) regardless of whether other sections succeed; the tile order is committed even if the overall import is later considered a no-op or the user doesn't restart.
**Root cause:** A prefs write embedded inside a pure-looking "apply" function.
**Proposed fix:** Return the tile order in the result and let the caller commit it alongside the other writes.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/admin/TrimByAgeScreen.kt (days cap)

### Bug 64 — Severity: LOW — Category: input cap silent
**Location:** TrimByAgeScreen.kt:93 (`.take(4)`)
**Symptom:** Days-to-keep is capped at 4 digits (≤9999) silently. A user typing a 5th digit sees it not appear with no feedback. Also `days.toLong()*24*60*60*1000` for 9999 is fine, but there's no upper-bound sanity (9999 days ≈ 27 years) — harmless but the cap is arbitrary and unexplained.
**Root cause:** Hardcoded `.take(4)` with no hint.
**Proposed fix:** Cosmetic; acceptable.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt (empty onSave guard)

### Bug 65 — Severity: LOW — Category: reference-equality save guard can miss
**Location:** ImportExportScreen.kt:1451 (`if (working !== aiSettings) onSave(working)`)
**Symptom:** The All-import only saves when `working` is a DIFFERENT instance than `aiSettings`. Every `apply*` returns a `.copy()` so this is normally fine — but if a section runs and returns the same instance (e.g. a handler that no-ops by returning its input on empty), and another section DID mutate via a singleton (cooldowns/costs/registry) without touching `working`, the `parts` list is non-empty (restart banner shows) yet `onSave` is skipped — consistent here because those singletons don't live in Settings. Fragile coupling: relies on every Settings-touching handler producing a new instance.
**Root cause:** Identity comparison instead of value comparison for the save gate.
**Proposed fix:** Use value inequality (`working != aiSettings`) or an explicit "settings changed" flag.
**Status:** Open

---

## File: ai/src/main/java/com/ai/ui/cruds/framework/CrudListPage.kt (subList bounds)

### Bug 66 — Severity: LOW — Category: paging math relies on coerce
**Location:** CrudListPage.kt:79-84 (`pageSize`, `safePage`, `subList`)
**Symptom:** `pageSize = maxOf(1, ((maxHeight - 28.dp)/56.dp).toInt())`. On an extremely short container (e.g. split-screen) `maxHeight - 28.dp` can be negative → division yields negative/0 → `maxOf(1, …)` saves it to 1. Then `totalPages` is large and paging works, but a single very short window shows 1 row with a page indicator for potentially hundreds of pages. Edge UX, not a crash (the `maxOf(1,…)` prevents the subList from going out of bounds).
**Root cause:** Fixed-height paging in a flexible container.
**Proposed fix:** Acceptable; the coerce prevents the crash.
**Status:** Open

---

## Summary

Total findings: **66**

By severity:
- CRITICAL: 0
- HIGH: 5 (Bugs 1, 4, 5, 6, 16)
- MEDIUM: 13 (Bugs 7, 8, 11, 14, 20, 24, 28, 32, 42, 44, 46, 53, 61)
- LOW: 48 (Bugs 2, 3, 9, 10, 12, 13, 15, 17, 18, 19, 21, 22, 23, 25, 26, 27, 29, 30, 31, 33, 34, 35, 36, 37, 38, 39, 40, 41, 43, 45, 47, 48, 49, 50, 51, 52, 54, 55, 56, 57, 58, 59, 60, 62, 63, 64, 65, 66)

Highest-impact, most actionable:
- **Bug 1** — Test Agent never shows a result (dead `error` local).
- **Bug 16** — Update-from-cloud copies the APK on the main thread → ANR.
- **Bug 5** — Settings JSON export/import silently drops ~20 GeneralSettings fields (metadata flags, default icons, network/retry caps, app-wide prompt/params).
- **Bug 4 / Bug 44** — Editing a manual cost override to a new model orphans the old entry (duplicate), unlike every other model-state CRUD.
- **Bug 6** — `importType` not `rememberSaveable`: a process kill during the SAF picker mis-routes the imported file (defaults to "keys").
- **Bug 61** — LiteLLM endpoint selection persists to the provider before the agent is saved → orphaned endpoints on cancel.
