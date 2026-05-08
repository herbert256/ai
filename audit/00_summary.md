# Bug audit — executive summary

**Total findings: 539**

## Severity breakdown

| Severity | Reports | Chat | Settings | Data | **Total** |
|---|---:|---:|---:|---:|---:|
| CRITICAL | 1 | 1 | 3 | 1 | **6** |
| HIGH | 65 | 75 | 55 | 89 | **284** |
| MEDIUM | 41 | 69 | 57 | 40 | **207** |
| LOW | 8 | 19 | 15 | 0 | **42** |
| **Total** | **115** | **164** | **130** | **130** | **539** |

## Critical-class bugs (data loss / crash / feature broken)

These should jump the queue. All six in one place:

### 1. `BackupManager.restore` destructive ordering
`bugs_data.md` Bug 53 — `clearFilesDirForRestore` wipes filesDir
**before** `applyPrefs` commits. Process kill mid-restore loses both
reports and settings. **Fix:** commit prefs first; write files after.

### 2. Refresh-all auto-restart kills process before flush
`bugs_settings.md` Bug 47 — `Runtime.getRuntime().exit(0)` runs after
a 400 ms delay; `usageStatsCache` debounces flushes to 2 s and
`PricingCache` writes are async. Pending writes lost on every Refresh
All. **Fix:** `flushUsageStats()` + explicit pricing flush before exit.

### 3. Restore relaunch races fsync
`bugs_settings.md` Bug 74 — After restore, `delay(800) → startActivity
→ killProcess`. SAF OutputStreams + SharedPreferences may not have
fsynced. Kill mid-fsync = partial restore. **Fix:** `commit()` prefs
synchronously, `fileChannel.force(true)` files, then kill.

### 4. `ProviderModelSettingsScreen` two-way sync race
`bugs_settings.md` Bug 31 — Two `LaunchedEffect`s mirror local↔external
`models`; race timing during Refresh All can let a stale captured
`aiSettings` overwrite a fresher change. **Silent model-list wipe.**
**Fix:** one-way data flow. Local mirror has to die.

### 5. `recoverStaleSecondariesAsync` corrupts in-flight runs
`bugs_reports.md` Bug 82 — A meta batch kicked off in the recovery
window has its placeholder marked errored mid-flight, corrupting
fresh runs. **Fix:** scope the recovery sweep by start-time so it
skips rows newer than the sweep's launch.

### 6. `DualChatSessionScreen` config null-check pattern fragile
`bugs_chat.md` Bug 19 — Defensive null-check pattern on `dualChatConfig`
mixes `remember { … ?: return@remember null }` with `if (config ==
null) … return`. Smart-cast holds today, but the surrounding code
keeps executing past the null branch. **Fix:** wrap in `config?.let
{ ... }` early-return.

## Top-20 by severity (HIGH-band, ranked by likely user impact)

These are the next-most-impactful issues across all four reports.
Per-file detail in the per-area `.md` files; line numbers + fix
proposals are there.

1. `ApiTracer.kt` — TracingInterceptor captures full Authorization
   header; trace JSON files persist plaintext API keys. Roll into
   backup zip too. (`bugs_data.md`)
2. `ApiStreaming.kt:53-89` — SSE multi-line `data:` field not
   concatenated per W3C spec; provider responses split across lines
   silently fail JSON parse. (`bugs_data.md`)
3. `RateLimitRetryInterceptor` — `Thread.sleep` up to 15 s per call
   blocks OkHttp's per-host concurrency, freezing all traffic to that
   host. (`bugs_data.md`)
4. `ApiTracer.kt:47,55` — `withTracerTags` volatile-global tag bleed
   across parallel coroutines; parallel report agents share
   `currentReportId/currentCategory`, mistagging traces.
   (`bugs_data.md`)
5. `Knowledge.kt:62-68` — `KnowledgeChunk` data-class with `FloatArray`
   uses reference equality for `==`; auto-generated equals/hashCode
   silently broken. (`bugs_data.md`)
6. `ApiDispatch.kt:1063-1066` — Anthropic max_tokens silently
   bumped past user's value when reasoning effort is high; violates
   cost caps. (`bugs_data.md`)
7. `PricingCache.getPricing:322-351` — Manual override sits BEHIND
   LiteLLM. Users adding overrides to beat stale LiteLLM data are
   silently ignored. (`bugs_data.md`)
8. `PricingCache.ensureLoadedLocked:1309` — Doesn't set
   `preloadCompleted = true` from non-main IO callers; main thread
   keeps returning DEFAULT_PRICING until separate preloadAsync fires.
   (`bugs_data.md`)
9. `AtomicFileWrite.kt:19-42` — `writeTextAtomic` doesn't fsync the
   tmp file before atomic move. Power-loss surfaces old or empty
   content on ext4. (`bugs_data.md`)
10. `ChatScreens.kt:423` — `actuallySend` re-bills prior assistant
    tokens as input cost on every turn; client cost grows ~O(N²).
    (`bugs_chat.md`)
11. `ChatScreens.kt:156` — `ChatParametersScreen` never copies preset
    `webSearchTool` / `reasoningEffort` into `ChatParameters`,
    silently dropping preset toggles. (`bugs_chat.md`)
12. `ChatScreens.kt:758` — Outer `BackHandler` stays enabled when
    moderation overlay is up; back-press exits chat instead of
    dismissing the overlay. (`bugs_chat.md`)
13. `SelectionScreens.kt:188` — Pricing source comparison uses
    lowercase `"default"` while PricingCache emits `"DEFAULT"`; all
    prices render in Red regardless of source. (`bugs_chat.md`)
14. `ReportViewModel.kt:1199` — `rerunCompleteCross` filter compares
    cross prompt id against fields that hold after_cross prompt id;
    combine-report follow-ups never cleaned up. (`bugs_reports.md`)
15. `ReportViewModel.kt:1295,1308-1311` — after_cross combine picks
    the OLDEST factcheck row; a successful retry is silently
    shadowed by the original failure. (`bugs_reports.md`)
16. `SecondaryResultsScreen.kt:1119-1161` — `rowStatsByKey` skips
    rows by (provider, model) instead of by agentId; undercounts
    totalSources / cost when an active model has multiple agentIds.
    (`bugs_reports.md`)
17. `SecondaryResultsScreen.kt:153-163` — Translation overlay
    synthesises rows with the original meta id but translated content;
    opening the row surfaces the **untranslated** text instead.
    (`bugs_reports.md`)
18. `Trace screen Copy/Share` — When trace fails to load, both fall
    back to unredacted `displayContent`/`rawJson`, putting plaintext
    secrets in clipboard / shared file. (`bugs_settings.md`)
19. `SettingsScreen.kt selectedProvider` re-keys on
    `AppService.entries.size` — any registry mutation drops the
    runtime selection back to `initialProviderId`, bouncing users
    out of an open provider edit. (`bugs_settings.md`)
20. `BackupManager` API-key leakage — backup zip includes
    `eval_prefs.xml` with provider API keys in plaintext.
    (`bugs_data.md` + `bugs_settings.md`)

## Themes worth fixing in batches

- **fsync / atomicity** — `AtomicFileWrite`, `BackupManager`, prefs
  commits, restore-then-relaunch. Several CRITICAL-class fixes
  cluster here.
- **API key / secret hygiene** — tracer captures auth headers,
  backup zip exports plaintext keys, trace Copy/Share fallback path
  bypasses redaction. One coherent pass would close the lot.
- **Compose state-key issues** — `rememberSaveable` keys missing
  context (cross drill-in, settings sub-screen state, model picker),
  `LaunchedEffect` keys recomputing too often / not enough.
- **Coroutine scope leaks** — screen-scoped exports / deletes
  abandoned on navigation; `viewModelScope` work that should be
  `appScope`-scoped.
- **Pricing precedence** — manual override behind LiteLLM, source
  string casing mismatch, DEFAULT-during-cold-start race.
- **Cross drill-in semantics** — multiple bugs around
  `selectedModelKey`, `runningCrossPairs`, after-cross cleanup,
  retry shadowing.
- **Main-thread file I/O** — HistoryScreen, ReportManageScreen, a
  few others read JSON on the UI thread.
- **Gson reflection nullability** — fields declared non-null in
  Kotlin come back null at runtime via Gson's Unsafe path; only some
  call sites defend against it.
