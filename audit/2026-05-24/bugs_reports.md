# Bug review — Report and Translation areas (audit-2, fresh from current code)

Scope: `ai/src/main/java/com/ai/ui/report/**` and the export helpers in
`ai/src/main/java/com/ai/ui/helpers/**`. Findings are grouped by file and
numbered continuously. Every location was read from the live code.

## File: ai/src/main/java/com/ai/ui/report/manage/GetInfo.kt

### Bug 1 — Severity: HIGH — Category: Provider+Model pin not honoured / inconsistency with cost view
**Location:** GetInfo.kt:79-114 (`buildInfoJobs`, `iconAgent` / `titleAgent` gates)
**Symptom:** When an icon-gen / report-title internal prompt is pinned to a Provider+Model alternative (the feature added in commit 6e188051) instead of a named Agent, the corresponding row (icon / title) disappears from the "Report - Get info" screen and is dropped from the aggregate Manage info-row status and its summed cost — even though icon/title generation actually ran and the spend shows up in the Costs view.
**Root cause:** The gate resolves the prompt's agent with `settings.agents.firstOrNull { it.name.equals(p.agent, ignoreCase = true) }` (named-agent only). `rememberReportCostData` in ContentDisplay.kt uses `ai.resolvePromptAgent(p)` (SettingsModels.kt:648) which returns a synthetic agent for a Provider+Model pin. The two lookups now disagree.
**Reproduction:** Pin the bundled `icons/main` prompt to a Provider+Model (not a named agent). Run a report with icon-gen on. The Costs screen shows the icon cost; the Get-info screen omits the icon row and the info-row total under-reports.
**Proposed fix:** Replace the two named-agent lookups with `settings.resolvePromptAgent(iconPrompt)` / `resolvePromptAgent(titlePrompt)` so the gate matches the cost path.
**Status:** Fixed — both gates now use `settings.resolvePromptAgent(...)` so a Provider+Model pin is honoured, matching the cost path (GetInfo.kt:80,114).

### Bug 2 — Severity: MEDIUM — Category: stale label / dead state
**Location:** GetInfo.kt:84-91 (`iconRowOn` icon state)
**Symptom:** On an old report where icon-gen never ran (icon null, no error), the icon row reads "Generating…" with a spinning hourglass forever — there is no terminal "not run" state.
**Root cause:** `state` is `RUNNING` whenever `report.icon == null && iconErrorMessage == null`, with no signal distinguishing "in flight" from "never started". The sibling `LanguageRow`/`TitleRow` in GenerationPhase use `promptUsed`/`name` to detect "didn't run", but `buildInfoJobs` has no equivalent for the icon job.
**Reproduction:** Open Get-info for a report generated before icon-gen, or one where the icon agent was unresolvable at run time.
**Proposed fix:** Add a "never ran" signal (e.g. a persisted iconPromptUsed flag, or treat a finished report with no icon attempt as DONE/absent) so the row settles instead of spinning.
**Status:** Fixed — a completed report (completedAt set) with no icon/error/cost/duration/promptUsed is treated as "icon never ran" and the spinning row is omitted (GetInfo.kt:83-91).

## File: ai/src/main/java/com/ai/ui/report/manage/GenerationPhase.kt

### Bug 3 — Severity: MEDIUM — Category: coroutine / unbounded re-fire
**Location:** GenerationPhase.kt:779-791 (`LaunchedEffect(currentReportId)` stalled-translation reconcile loop)
**Symptom:** Every 10 s the screen calls `onReconcileStalledTranslation(rid, run.runId)` for *each* run whose `completed == total` but `!isFinished`, with no dedup. If the reconcile cannot flip the run to finished (e.g. the persisted rows truly never reach the items list), this fires the same reconcile forever at 10 s intervals, doing disk reads / state rebuilds indefinitely while the screen is open.
**Root cause:** No per-runId "already reconciled" guard inside the loop; the only guard (`run.total > 0 && run.completed == run.total`) stays true after a failed reconcile.
**Reproduction:** Open a report whose translation run is genuinely stuck at completed==total but flagged unfinished and where reconcile cannot complete it; watch repeated reconcile calls.
**Proposed fix:** Track a `Set<String>` of runIds already reconciled this session and skip them, or back off exponentially.
**Status:** Fixed — added a per-runId `reconciled` guard set so each stuck run is reconciled once per report-open, not re-fired every 10 s (GenerationPhase.kt:779-797).

### Bug 4 — Severity: MEDIUM — Category: cost double-count window
**Location:** GenerationPhase.kt:599-623 (`liveTranslation` fold into `totalCost`)
**Symptom:** During the ~200ms window after a translation run finishes (rows persisted but the live `TranslationRunState` not yet evicted / not yet `isFinished`), the run's cost is counted twice in the bottom-bar total: once via `liveTranslationCost` and once via `secondaryTotals` (computed from the just-persisted TRANSLATE rows).
**Root cause:** `liveTranslation` sums every run where `!run.isFinished`. `secondaryTotals` is computed from disk. There is no exclusion of runIds that just persisted; the comment at 596-598 acknowledges the window but dismisses it.
**Reproduction:** Run a translation; watch the total briefly inflate at completion before settling.
**Proposed fix:** Exclude persisted runIds from the live fold (the same `activeTranslationRunIds` exclusion already used for the summary rows at 799-804) when summing `liveTranslationCost`.
**Status:** Fixed — the live fold now excludes any runId that already has a persisted summary, closing the double-count window (GenerationPhase.kt:599-616).

### Bug 5 — Severity: LOW — Category: recomposition / unstable key
**Location:** GenerationPhase.kt:530-538 (`everyItems = remember(secondaryRuns, translateRows, aiSettings)`)
**Symptom:** `everyItems` is memoised on `aiSettings` (a large data object). Any settings re-emit that produces a new `Settings` instance (frequent during config churn) rebuilds the whole grouping even when the relevant `internalPrompts` did not change.
**Root cause:** Keying on the entire `aiSettings` object identity rather than the narrow `internalPrompts` slice used by `buildEveryItems`.
**Proposed fix:** Key on `aiSettings.internalPrompts` instead of `aiSettings`.
**Status:** Fixed — `everyItems` now keys on `aiSettings.internalPrompts` (the only slice buildEveryItems reads) instead of the whole Settings object (GenerationPhase.kt:530).

### Bug 6 — Severity: LOW — Category: fan-out summary key collapse
**Location:** GenerationPhase.kt:1056 (`items(fanOutSummaries, key = { "cm-${it.metaPromptName}" })`) vs buildFanOutSummaries:1689
**Symptom:** Two distinct fan-out runs whose prompts both have a blank `metaPromptName` and the same `metaPromptId` fallback collapse into a single LazyColumn key, so one row can be dropped from the list (key collision).
**Root cause:** `buildFanOutSummaries` groups by `metaPromptName ?: metaPromptId` and stores the resolved name (possibly the id) in `FanOutRunSummary.metaPromptName`; the LazyColumn key then uses only that field. Legacy rows with neither name resolve to `""` which is filtered, but rows that share an id-fallback name still risk collision.
**Proposed fix:** Use a composite stable key (e.g. include `timestamp` or a synthesized id) for the summary row.
**Status:** Not a bug — `buildFanOutSummaries` groups by `metaPromptName ?: metaPromptId` and emits one summary per group key, so every `FanOutRunSummary.metaPromptName` in the list is already distinct; the LazyColumn key cannot collide. Adding timestamp would risk key instability during live updates.

### Bug 7 — Severity: LOW — Category: misleading status / classifier
**Location:** GenerationPhase.kt:866-869 (`running` for a secondary run row)
**Symptom:** A secondary run that errored with `durationMs == null` (an error path that didn't stamp duration) but has a non-null `errorMessage` is correctly shown ❌; however a run that has `errorMessage == null`, blank content, and `durationMs == null` shows ⏳ forever if the executor crashed before stamping duration (no error message written).
**Root cause:** "running" is inferred purely from absence of content/duration/error; there's no timeout / liveness check, so a lost worker leaves the row spinning permanently across app restarts.
**Proposed fix:** Treat a row older than N minutes with no progress as failed, or stamp an error on worker abort.
**Status:** Won't fix — a reliable terminal signal (stamp error on worker abort) lives in the data/secondary executor, not this UI classifier; an arbitrary N-minute timeout in the row would misclassify legitimately long calls. Deferred to the data pass.

## File: ai/src/main/java/com/ai/ui/report/view/Main.kt

### Bug 8 — Severity: HIGH — Category: overlay back-stack (the documented recurring class)
**Location:** view/Main.kt:181-356 (the stack of `if (showX) { ...; return }` sub-overlays)
**Symptom:** Each sub-View overlay (Costs / Meta / Rerank / Moderation / Fan-in / Fan-in-model / Translate / Prompt / Reports) is guarded by its own `if (state != null) { ...; return }` early return, and each provides `LocalNavigateToCurrentReport` = its own back-to-grid lambda. There is no single dispatcher ordering; if two of these state vars are ever non-null simultaneously (e.g. a sub-View opens Reports via `reportsViewOpen=true` while another flag wasn't cleared — see Rerank's `onOpenReportForAgent` at 272-277 which sets `rerankViewRowId=null` AND `reportsViewOpen=true` in the same handler), the *first* matching early-return wins and the user's Android-back peels the wrong layer.
**Root cause:** Per-CLAUDE.md memory "Overlay back-stack: layer, don't replace" — multiple state-driven overlays with positional early returns and no LIFO arbiter. The Rerank→Reports transition flips two flags in one tap, exactly the `showA=false; showB=true` pattern the memory warns against.
**Reproduction:** From Rerank view tap a podium card (opens Reports at that agent); press back — verify it returns to the View grid rather than to Rerank.
**Proposed fix:** Route all sub-View opens through a single back-stack list and pop one entry per back press, rather than independent booleans with positional returns.
**Status:** Not a bug — the Rerank→Reports handler clears `rerankViewRowId` (the earlier-positioned overlay) before setting `reportsViewOpen` (later-positioned), so on recomposition only Reports mounts and Android-back from Reports returns to the grid, exactly as the reproduction expects. The positional ordering already yields correct LIFO here; a full back-stack rewrite is the documented-risky change and is out of scope.

### Bug 9 — Severity: MEDIUM — Category: seed consumed only once across report switches
**Location:** view/Main.kt:249-259 (`seedConsumed` + `LaunchedEffect(Unit)`)
**Symptom:** The external Reports-seed (from `aiReportViewAtAgent`) is consumed on first composition keyed `Unit` and `seedConsumed` is `rememberSaveable`. After a title-bar swipe to a different report (which re-runs `restoreCompletedReport` but keeps this composable mounted), a second external navigation to the same screen would not re-seed because `seedConsumed` survived.
**Root cause:** `seedConsumed` is `rememberSaveable` (survives) but the `LaunchedEffect(Unit)` only fires once per composition lifetime; the seed bundle is also read fresh every recomposition (`seededFromOutside` at 571) so the two views of "was this externally seeded" can diverge.
**Proposed fix:** Key the consume effect on `seedBundle.initialReportsAgentId` and reset `seedConsumed` when the bundle changes.
**Status:** Fixed — replaced the one-shot `LaunchedEffect(Unit)`+`seedConsumed` flag with `LaunchedEffect(seedBundle.initialReportsAgentId)` tracking the last-seeded id, so a new external nav to a different agent re-seeds (view/Main.kt:249-263).

### Bug 10 — Severity: MEDIUM — Category: missing-language popup silently no-ops
**Location:** view/Main.kt:635-639, 676-683, 729-733 (`openPromptMissing` / `openReportsMissing` / `openMetaMissing` `effectiveTarget`)
**Symptom:** When the View picker is on Original and `reportLanguageName` is null (language detection never ran), tapping a grayed tile returns silently (`?: return`) with no popup and no feedback — the user taps and nothing happens.
**Root cause:** `effectiveTarget = if (target.isEmpty()) reportLanguageName ?: return else target` — the early `return` produces no UI signal.
**Reproduction:** On a report with no detected language and at least one translation, tap a tile grayed on the Original tab.
**Proposed fix:** Show a toast / dialog explaining there's no back-translation target instead of returning silently.
**Status:** Fixed — added `noBackTranslationTarget()` toast fired on the three `reportLanguageName ?: return` paths (view/Main.kt openPromptMissing/openReportsMissing/openMetaMissing).

### Bug 11 — Severity: LOW — Category: translate run-key mismatch
**Location:** view/Main.kt:1683 (`runKey = seed?.translationRunId ?: seed?.targetLanguage?.let { "lang:$it" }`)
**Symptom:** For a legacy TRANSLATE row with both `translationRunId == null` and `targetLanguage == null`, `runKey` is `null` here, while `buildEveryItems` (GenerationPhase.kt:211) grouped that row under the key `"lang:"`. The two key derivations disagree on the null-language case.
**Root cause:** `buildEveryItems` uses `it.targetLanguage.orEmpty()` (null → "lang:"), but `openComputedItem` uses `targetLanguage?.let { "lang:$it" }` (null → null), then falls back to `item.open(language)` which actually carries the right `"lang:"` runId — so it currently works only by accident.
**Proposed fix:** Unify on `translationRunGroupingId` (TranslationGrouping.kt:12) everywhere.
**Status:** Fixed — `openComputedItem` now derives the translate runKey via `translationRunGroupingId(seed)`, matching buildEveryItems' grouping including the both-null → "lang:" case (view/Main.kt:1698).

### Bug 12 — Severity: LOW — Category: tile-order persistence leaks across reports
**Location:** view/Main.kt:1146-1167 (`onReorder` patches `tile_order` SharedPreferences)
**Symptom:** Tile order is a single global `tile_order` string in `view_screen_prefs`. A user who reorders meta/fan-out tiles (whose ids include the per-report `metaPromptName` / row id) on report A accumulates A's tile ids permanently in the shared list; over many reports this grows unbounded (every distinct meta id ever reordered is retained at the tail).
**Root cause:** The persisted order keeps "any non-current ids (from other reports) in their previous relative positions at the tail" — there is no pruning, so report-specific ids never expire.
**Proposed fix:** Prune ids not present in the current `combinedTiles` periodically, or scope the order key per report for report-specific tile families.
**Status:** Fixed — the carried tail of non-current ids is now bounded with `.takeLast(64)` so report-specific (per-meta/per-fan-out) ids can't accumulate unbounded across many reports (view/Main.kt:1175-1178).

### Bug 13 — Severity: LOW — Category: dead code
**Location:** view/Main.kt:1454-1508 (`ListTileColumn`) and 1510-1542 (`TileFlow`)
**Symptom:** `ListTileColumn` and `TileFlow` are private composables that are never called (the grid uses `ReorderableTileFlow`); `SectionLabel` (1333) is likewise unused. Dead code that can mask intent and bit-rot.
**Root cause:** Leftover from the removed list-mode toggle.
**Proposed fix:** Delete the unused composables.
**Status:** Fixed — deleted the unused `SectionLabel`, `ListTileColumn`, and `TileFlow` private composables (view/Main.kt). `TileCard` and `ReorderableTileFlow` are retained (still used).

## File: ai/src/main/java/com/ai/ui/report/manage/Run.kt

### Bug 14 — Severity: MEDIUM — Category: overlay layered above hub, back ambiguity
**Location:** Run.kt:392-422 (Get-info drawn as a layer over the still-composed hub)
**Symptom:** `ReportGetInfoScreen` is rendered as an opaque layer on top of `GenerationPhase` while the hub stays composed underneath. Get-info's own `BackHandler` (GetInfo.kt:228) calls `onBack` (= `st.showGetInfo.value = false`), but the hub's `GenerationPhase` and its `LaunchedEffect` polling loops keep running underneath the layer — including the 10 s stalled-translation reconcile (Bug 3) and the scroll-to-top effect — wasting work and potentially scrolling the hidden list.
**Root cause:** The layered-overlay pattern keeps the parent fully composed and active rather than pausing it.
**Proposed fix:** Gate the hub's background polling/scroll effects on `!st.showGetInfo.value`.
**Status:** Fixed — added a `paused` param to GenerationPhase (passed `st.showGetInfo.value` from Run.kt) that suppresses the 10 s reconcile sweep and the scroll-to-top effect while the Get-info overlay is layered on top (GenerationPhase.kt:412-418,781,829; Run.kt:362-365).

### Bug 15 — Severity: LOW — Category: swipe vs scroll gesture conflict
**Location:** Run.kt:205-223 (`detectHorizontalDragGestures` on the whole Column)
**Symptom:** The body-level horizontal-drag swipe is attached to the outer Column that also contains the scrollable `LazyColumn` of result rows. A horizontal drag that starts on a row can both scroll-cancel and trigger a report switch; the threshold (80dp) mitigates but a diagonal flick can still switch reports unexpectedly.
**Root cause:** `detectHorizontalDragGestures` does not require the gesture to be predominantly horizontal before consuming.
**Proposed fix:** Require a horizontal-dominant gesture (compare |dx| vs |dy|) before treating it as a report swipe.
**Status:** Not a bug — `detectHorizontalDragGestures` only begins after the horizontal touch slop is crossed (it ignores predominantly-vertical drags by design), and the 80 dp threshold further guards. A diagonal flick that's vertical-dominant won't start the horizontal detector.

## File: ai/src/main/java/com/ai/ui/report/manage/RegenerateBatch.kt

### Bug 16 — Severity: MEDIUM — Category: stale report icon after switch
**Location:** RegenerateBatch.kt:90-96 (`reportIcon` produceState keyed only on `reportId`)
**Symptom:** The overlay's title-bar icon is read once per `reportId`. If icon-gen lands a new icon (or the user picks an alternative) while the overlay is open, the title-bar icon stays stale until the overlay is reopened.
**Root cause:** `produceState(initialValue = null, reportId)` has no refresh key (no `iconRefreshTick`).
**Proposed fix:** Add the icon refresh tick to the produceState keys, mirroring `LanguageRow`/`TitleRow`.
**Status:** Fixed — threaded `iconRefreshTick` (from uiState) through `RegenerateBatchOverlay` → `RegenerateBatchScreen` and added it to the `reportIcon` produceState keys (RegenerateBatch.kt:72-96,333-347; Nav.kt).

### Bug 17 — Severity: LOW — Category: status-banner counter omits CANCELLED/WAITING
**Location:** RegenerateBatch.kt:159-186 (`StatusBanner` counts)
**Symptom:** The banner shows `done / total · running · errored` but never surfaces WAITING or CANCELLED task counts; on a partially-cancelled job the numbers don't add up to total, which reads as data loss to the user.
**Root cause:** Only SUCCESS / ERROR / RUNNING are counted; WAITING + CANCELLED are silently omitted.
**Proposed fix:** Either show all five states or relabel "done" to "settled" and note the remainder.
**Status:** Fixed — the StatusBanner now appends `· N waiting` / `· N cancelled` when non-zero so the counts always reconcile to total (RegenerateBatch.kt:159-191).

## File: ai/src/main/java/com/ai/ui/report/manage/TranslationRun.kt

### Bug 18 — Severity: MEDIUM — Category: produceState misses lambda-key changes
**Location:** TranslationRun.kt:139-143 (`produceState` for `persisted`)
**Symptom:** The persisted run is loaded via `loadPersisted()`, but `loadPersisted` is a lambda parameter that is **not** a produceState key. If the parent recreates `loadPersisted` (e.g. it closes over a changed report id), the producer won't re-run and stale persisted state is shown.
**Root cause:** Keys are `reportId, runId, refreshTick, liveRun == null`; the suspending lambda itself is captured but not keyed.
**Reproduction:** Hard to hit in normal flow because `runId`/`reportId` change too, but a re-target via `onChangeRunId` that keeps `runId` while changing the closure could surface it.
**Proposed fix:** Either make `loadPersisted` stable (`remember`) at the call site, or pass the inputs it needs and reconstruct inside the producer.
**Status:** Not a bug — the producer keys (`reportId, runId, refreshTick, liveRun == null`) already cover everything `loadPersisted` closes over in practice; a closure swap that keeps all three identical would reload identical data. The finding itself notes it's not reachable in normal flow.

### Bug 19 — Severity: LOW — Category: BackHandler conflict with child screens
**Location:** TranslationRun.kt:131-137 (parent BackHandler) and child L2/L3 BackHandlers (TranslationL2.kt:58, TranslationL3.kt:80)
**Symptom:** Both the parent `TranslationRunScreen` and the child `TranslationL2Screen`/`TranslationL3Screen` register a `BackHandler`. Compose dispatches the most-recently-registered enabled handler, so the child's `onBack` (which the parent already wires to step the nav) and the parent's nav-stepping handler can both be live, leading to a double-pop on back depending on composition order.
**Root cause:** Two enabled BackHandlers in the same active subtree both want to handle back.
**Proposed fix:** Keep back handling in one place — either the parent's nav switch or the children, not both.
**Status:** Not a bug — Compose dispatches only the innermost (most-recently-composed) enabled BackHandler. When L2/L3 is composed its handler shadows the parent's, so exactly one fires per press; at L1 the children aren't composed and the parent handles it. No double-pop.

## File: ai/src/main/java/com/ai/ui/report/manage/TranslationL1.kt

### Bug 20 — Severity: MEDIUM — Category: stat double-classification on benched items
**Location:** TranslationL1.kt:108-118 (Done/Errors/Bench/Run/Queue split) vs modelRows:135-143
**Symptom:** The top stats split errored items into "Errors" vs "Bench" by cooldown, but the per-model row's `err` count (`its.count { it.status == ERROR }`) counts benched items as errors too. The headline stat row and the per-model rows disagree on the error count for a model with benched items.
**Root cause:** `benched()` filtering is applied to the top counters but not to `TranslationModelRow.err`.
**Proposed fix:** Apply the same `benched` exclusion to the per-model `err`, or surface a separate benched count per model.
**Status:** Fixed — `TranslationModelRow.err` now excludes benched (cooldown) items, matching the headline Errors/Bench split; the memo also keys on `cooldowns` so it updates as cooldowns lift (TranslationL1.kt:131-148).

### Bug 21 — Severity: LOW — Category: progress denominator excludes benched
**Location:** TranslationL1.kt:284-294 (`pending = queuedCount + runningCount`, `finished = (doneCount + errorCount) / total`)
**Symptom:** The top progress bar uses `(doneCount + errorCount)/total` where `errorCount` excludes benched items. A run whose only unfinished work is benched (cooldown) shows `pending == 0` so the bar hides, yet `doneCount + errorCount < total` — the run looks complete while benched rows are still outstanding.
**Root cause:** Benched items have status ERROR (so not pending) but are excluded from `errorCount`, so they vanish from both numerator and the pending gate.
**Proposed fix:** Include benched in the finished numerator (they're terminal for now) or keep the bar visible while benchCount > 0.
**Status:** Fixed — the progress bar (and per-model bars) now stay visible while `benchCount > 0`, so a run with only benched work outstanding no longer reads as complete (TranslationL1.kt:290,306).

### Bug 22 — Severity: LOW — Category: restart-failed re-fires benched (re-confirms cooldown)
**Location:** TranslationL1.kt:387-411 (`confirmRestartFailed`, `restartN = errorCount + benchCount`)
**Symptom:** "Restart failed" re-fires benched items too; each immediately re-hits the rate-limit cooldown and re-benches, burning a wasted request per benched item with no progress.
**Root cause:** The restart set includes benched items by design (comment at 388-389), but for a model on a >1h cooldown this is pure waste until the cooldown lifts.
**Proposed fix:** Skip items whose model cooldown hasn't lifted, or re-route benched items to a different model like the failed-item round-robin already does.
**Status:** Won't fix — the re-fire/round-robin logic lives in `viewmodel/translation` (`restartFailedTranslations`), outside this pass's scope. The UI dialog only triggers it. Deferred to the viewmodel pass.

## File: ai/src/main/java/com/ai/ui/report/manage/TranslationL2.kt

### Bug 23 — Severity: LOW — Category: sort instability on equal status+label
**Location:** TranslationL2.kt:66-81 (rows `sortedWith(compareBy(status, label.lowercase()))`)
**Symptom:** Items with identical status and identical lowercased label keep arbitrary relative order; the L3 prev/next stepping derived from this same comparator (TranslationL3.kt:93-108) can therefore "jump" if two items tie and the underlying list order shifts between recompositions (e.g. live status flips).
**Root cause:** No stable tiebreaker (e.g. item id) in the comparator.
**Proposed fix:** Add `{ it.id }` as the final comparator key in both L2 and L3.
**Status:** Fixed — added `{ it.id }` as the final tiebreaker in both the L2 rows comparator and the L3 siblings comparator (TranslationL2.kt:66-83, TranslationL3.kt:93-111).

## File: ai/src/main/java/com/ai/ui/report/manage/TranslationL3.kt

### Bug 24 — Severity: MEDIUM — Category: source content disk read keyed too narrowly
**Location:** TranslationL3.kt:122-142 (`source` produceState keyed `reportId, item.id, item.kind, item.target`)
**Symptom:** For a META source, the source content is read from `SecondaryResultStorage.get` once and cached on those keys. If the underlying source META row's content changes (e.g. regenerated) while this L3 is open, the displayed source pane stays stale because none of the keys reflect the source row's mutation.
**Root cause:** No mtime / version key on the source row.
**Proposed fix:** Acceptable for a read-only call detail, but consider keying on a report mtime if live edits are expected.
**Status:** Not a bug — acceptable for a read-only call-detail screen, as the finding itself concludes; a META source rarely mutates while its L3 is open, and the keys re-read on item/target change.

### Bug 25 — Severity: LOW — Category: trace match by nearest-timestamp can mis-pick
**Location:** TranslationL3.kt:158-165 (traceFilename = most recent Translation-tagged trace for the item's model)
**Symptom:** The 🐞 trace is resolved as the *most recent* `category == "Translation"` trace for this report+model. When a model translated several items in one run, every item's L3 links to the same (latest) trace regardless of which call produced this item.
**Root cause:** No correlation between the translation item and a specific trace; only report+model+category is used.
**Proposed fix:** Correlate by timestamp proximity to the item's own call (the fan-out L3 at FanDrillIn.kt:511-517 already uses `minByOrNull { abs(timestamp - res.timestamp) }`).
**Status:** Fixed — now prefers the exact `TranslationItem.traceFile` captured at call time, falling back to the most-recent heuristic only for legacy disk-reconstructed rows (TranslationL3.kt:156-167).

## File: ai/src/main/java/com/ai/ui/report/manage/view/FanDrillIn.kt

### Bug 26 — Severity: MEDIUM — Category: L2/L3 pair-key split fragile for empty src
**Location:** FanDrillIn.kt:457-463 and 843-852 (`gotoPair` / row click split l3PairKey on lastIndexOf('|'))
**Symptom:** `l3PairKey` is `"$pid|$mdl|$srcAgentId"`. The split uses `lastIndexOf('|')`; when `srcAgentId` is empty (orphan/legacy row, `fanOutSourceAgentId` blank) the substring after the last `|` is `""`, and the click guard `if (src.isNotBlank())` then refuses to open the pair — the row is effectively dead (tapping does nothing) even though it renders a result.
**Root cause:** Pair identity collapses when the source agent id is blank; no fallback.
**Reproduction:** A fan-out pair row whose `fanOutSourceAgentId` was cleared; tap it — nothing happens.
**Proposed fix:** Carry the source agent id as a separate field on `L2Row` (it already exists as `sourceAgentId`) and use that for navigation instead of re-splitting the composite key.
**Status:** Fixed — both `gotoPair` and the L2 row click now use `row.sourceAgentId` and derive the answerer key via `l3PairKey.removeSuffix("|$src")`, so a blank-source orphan row navigates instead of being dead, and a model id with a literal `|` stays intact (FanDrillIn.kt:457-463,843-852).

### Bug 27 — Severity: MEDIUM — Category: model-name with literal '|' breaks key
**Location:** FanDrillIn.kt:160-163, 284-285, 331 (keys built as `"$providerId|$model|$src"`, `activeKey.split("|")`)
**Symptom:** `activePid = activeKey.split("|").getOrNull(0)`, `activeMdl = ...getOrNull(1)`. A model name containing a literal `|` shifts the boundary: `activeMdl` gets only the segment up to the first `|`, mis-identifying the model and breaking the L2 filter `successful.filter { it.model == activeMdl }`.
**Root cause:** L1→L2 uses `split("|")` (first-pipe) while L3 carefully uses `lastIndexOf('|')`; the two are inconsistent and the L1 path is wrong for piped model names.
**Reproduction:** A provider whose model id contains `|` (rare but not impossible for custom endpoints).
**Proposed fix:** Use a delimiter that can't appear in model ids, or split with `lastIndexOf` consistently, or carry pid/model as structured fields.
**Status:** Fixed — L1→L2 now derives `activePid`/`activeMdl` via `substringBefore('|')`/`substringAfter('|')` (provider never contains `|`, model keeps any pipes), and the L3 nav paths use `removeSuffix` with the structured `sourceAgentId` (FanDrillIn.kt:284-285 and Bug 26's edits).

### Bug 28 — Severity: LOW — Category: per-pair trace nearest-timestamp can mis-pick
**Location:** FanDrillIn.kt:511-518 (answerer trace = `minByOrNull { abs(timestamp - res.timestamp) }`)
**Symptom:** When the same answerer model ran many pairs in one fan-out burst, the nearest-timestamp heuristic can attach the wrong pair's trace to a given pair (timestamps cluster within ms).
**Root cause:** No direct trace↔pair correlation id; nearest-timestamp is a heuristic.
**Proposed fix:** Tag traces with the pair/secondary id and match exactly.
**Status:** Fixed — now prefers the pair's persisted `SecondaryResult.traceFile` (exact), falling back to the nearest-timestamp heuristic only when absent (FanDrillIn.kt:511-522).

### Bug 29 — Severity: LOW — Category: empty-body classified as done before running finally
**Location:** FanDrillIn.kt:274-280 (`rowState`) and 994-1004 (stats)
**Symptom:** The classifier treats `durationMs != null` with blank content as "done". If a worker stamps `durationMs` on an error path but the error message write lands a beat later, the row flickers done→errored. Conversely a genuinely empty success is fine. Narrow but observable flicker on live batches.
**Root cause:** Terminal "done" is inferred from `durationMs` independent of whether `errorMessage` is in the process of being written (two separate persistence steps).
**Proposed fix:** Stamp `durationMs` and `errorMessage` atomically in one write.
**Status:** Won't fix — the atomic durationMs+errorMessage write lives in the data/SecondaryResultStorage persistence layer, not this UI classifier. Deferred to the data pass.

### Bug 30 — Severity: LOW — Category: O(N^2) Initiator grouping recomputed
**Location:** FanDrillIn.kt:326-355 (Initiator `byPair` groupBy over all `results`)
**Symptom:** The Initiator l2Rows recompute re-scans the full `results` list and re-groups on every relevant recomposition; on a large fan-out (N×(N-1) pairs) this is heavy and runs whenever `results` mutates (frequent during a live batch).
**Root cause:** Grouping is inside the `remember(activeKey, selectedRole, latestByPair, successful, results, activeAgents)` block keyed on `results` which churns continuously during generation.
**Proposed fix:** Derive Initiator rows from the already-built `latestByPair` map instead of re-scanning raw `results`.
**Status:** Won't fix — `latestByPair` doesn't apply the Initiator's `fanInOf == null` filter, so deriving from it would change semantics (include fan-in rows). The grouping is already memoized; keying on `results` is required for correctness during live updates.

## File: ai/src/main/java/com/ai/ui/report/view/Costs.kt

### Bug 31 — Severity: MEDIUM — Category: cross-screen total mismatch (deleted costs)
**Location:** Costs.kt:209 (`totalCents = data.totalInC + data.totalOutC`) vs GenerationPhase.kt:622-623 (bottom-bar total includes `costsFromDeletedItems`)
**Symptom:** The View → Costs hero "Total" omits `costsFromDeletedItems`, while the Manage bottom-bar total and the HTML export cost table include it. The two totals for the same report disagree whenever the user has deleted any billed item.
**Root cause:** `CostsViewScreen` deliberately omits the deleted-items line (documented at 70-72), but the hero total is then no longer the report's true grand total — there's no caveat shown to the user.
**Proposed fix:** Either include deleted cost in the hero total, or label the hero as "Current items total" so it doesn't read as the grand total.
**Status:** Fixed — relabeled the hero from "Total" to "Current items total" so the figure (which omits deleted-items cost by design) no longer reads as the report's grand total (Costs.kt:273-278).

### Bug 32 — Severity: LOW — Category: Models-mode collapses distinct models
**Location:** Costs.kt:235-248 (`modeled` keyed by `shortModelName(row.model)`) and L3 filter:540-545
**Symptom:** Two different full model ids that share a `shortModelName` (e.g. provider-prefixed variants that shorten to the same display) are merged into one Models-mode bucket and one L3 drill, hiding the per-model split and merging costs.
**Root cause:** `shortModelName(model)` is lossy and used as the grouping key, dropping the provider entirely (documented "provider name is dropped").
**Proposed fix:** Key on the full `provider|model` for the roll-up even if the label is shortened.
**Status:** Not a bug — intentional per the documented user spec (Costs.kt:230-234): provider is deliberately dropped and same-short-name models collapse into one row. Keying on full provider|model would violate that spec; the L3 filter mirrors the same key.

### Bug 33 — Severity: LOW — Category: float→double coercion of percent floor
**Location:** Costs.kt:438 (`pct.coerceAtLeast(0.01f.toDouble())`)
**Symptom:** `0.01f.toDouble()` is `0.009999999776...`, not exactly 0.01; the minimum bar fraction is very slightly off. Cosmetic only.
**Root cause:** Float literal converted to Double instead of using `0.01`.
**Proposed fix:** Use `0.01` (a Double literal).
**Status:** Fixed — replaced `0.01f.toDouble()` with the Double literal `0.01` (Costs.kt:443).

## File: ai/src/main/java/com/ai/ui/report/manage/view/ContentDisplay.kt

### Bug 34 — Severity: MEDIUM — Category: alt-cost subtraction can go negative
**Location:** ContentDisplay.kt:1002-1003, 1058-1059, 1168-1169 (`inputCents = (report.iconInputCost * 100) - mainAltInCents`, etc.)
**Symptom:** The aggregate icon / language-icon / secondary rows subtract the Find-alt per-call cost to avoid double counting. If the alt-call costs were recorded but the aggregate `iconInputCost` was *not* bumped by the same amount (timing skew, or a partial write), the subtraction underflows to a negative cent value, which then shows as a negative cost row and drags the total down.
**Root cause:** The invariant "aggregate already includes the alt portion" is assumed but not enforced; no `coerceAtLeast(0.0)`.
**Proposed fix:** Clamp each subtracted row to `coerceAtLeast(0.0)`.
**Status:** Fixed — clamped all three alt-cost subtractions (icon, language-icon, secondary) to `coerceAtLeast(0.0)` (ContentDisplay.kt icon/languageIcon/secondary rows).

### Bug 35 — Severity: MEDIUM — Category: icon/title/language row provider/model resolution can drift from run-time
**Location:** ContentDisplay.kt:983-1090 (iconRow / languageDetectRow / languageIconRow / titleRow resolve provider+model from *current* settings)
**Symptom:** These cost rows resolve provider/model via the *current* `SettingsHolder.current` pinned agent rather than what actually ran. If the user re-pins the icon/title prompt to a different agent after the report ran, the cost row shows the new provider/model and re-prices via `PricingCache.getPricing` for the wrong model, while the persisted token×cost split is from the old model.
**Root cause:** Provider/model are recomputed from live settings; only the cost amount comes from the persisted fields.
**Proposed fix:** Persist the resolved provider/model alongside the cost at run time (the title/language-icon rows already partly do via `titleModel`/`languageIconModel`; the icon `iconRow` and `languageDetectRow` do not).
**Status:** Fixed (partial) — the icon row now prefers the persisted `Report.iconModel` (provider/model the icon actually ran on) before falling back to the live pinned agent, mirroring titleModel/languageIconModel (ContentDisplay.kt:988-993). The `languageDetectRow` has no persisted model field to read; adding one is a data/ReportModels change, deferred to the data pass.

### Bug 36 — Severity: LOW — Category: SettingsHolder.current null window
**Location:** ContentDisplay.kt:984, 1016 (`val ai = SettingsHolder.current`)
**Symptom:** If `SettingsHolder.current` is null (early cold start) the icon/title/language rows fall back to blank provider/model and tier; the cost still shows but with empty attribution.
**Root cause:** No guard distinguishing "settings not loaded" from "agent unresolvable".
**Proposed fix:** Skip building the row (or show a "pending" tier) when settings haven't loaded, so the row doesn't persist a blank attribution into the grouped totals.
**Status:** Not a bug — nothing is persisted; the rows are recomputed every composition and attribution self-corrects once `SettingsHolder.current` loads. The cost amount (from persisted Report fields) is always correct regardless.

## File: ai/src/main/java/com/ai/ui/helpers/ReportExport.kt

### Bug 37 — Severity: MEDIUM — Category: agent vs secondary cost-row filter inconsistency
**Location:** ReportExport.kt:859 (`agentRows = data.agents.filter { it.inputCost != null }`) vs 863 (`secondaryRows = data.secondary.filter { it.inputTokens != null }`)
**Symptom:** Agent cost rows are filtered on `inputCost != null` while secondary rows are filtered on `inputTokens != null`. A secondary with token usage but null cost is included with cost 0; an agent with token usage but null cost is *excluded* entirely. The two row families use different inclusion rules, so the exported Costs table can omit an agent that the in-app Costs table (which falls back to recompute) shows.
**Root cause:** Divergent filter predicates; the in-app `rememberReportCostData` (ContentDisplay.kt:931, falls back to live recompute when cost null) is more lenient for agents than this export path.
**Proposed fix:** Use the same predicate + recompute-fallback the in-app path uses, so the export Costs table matches the screen.
**Status:** Fixed — both agentRows and secondaryRows now use the same lenient predicate: include a row when EITHER token usage OR a persisted cost is present (ReportExport.kt renderCostsView).

### Bug 38 — Severity: MEDIUM — Category: per-language overlay drops native META rows whose source is itself a translation chain
**Location:** ReportExport.kt:516-524 (`perLangMeta` + `overlaidOriginalMeta`)
**Symptom:** A per-language view shows META rows that are either natively tagged `targetLanguage == lang` OR Original rows overlaid via a `META:<id>` TRANSLATE row. A META row that exists *only* as a translation of another translation (chained) or whose TRANSLATE row points at a non-Original META is not surfaced, so some translated meta content silently vanishes from the export's language tab.
**Root cause:** Overlay only resolves a single hop from Original (`byTarget["META:${s.id}"]` where `s.targetLanguage == null`); multi-hop / non-original chains aren't followed.
**Proposed fix:** Resolve the per-language META set by walking all TRANSLATE rows for the language regardless of whether their source is Original.
**Status:** Won't fix — multi-hop translate-chain resolution risks double-surfacing rows already caught by `perLangMeta` (native `targetLanguage == lang` rows) and the single-hop Original overlay; the common cases are handled and the chained case is rare. A safe full-graph walk is too involved for this pass.

### Bug 39 — Severity: LOW — Category: languageKey collision
**Location:** ReportExport.kt:543-544 (`languageKey` strips to `[a-z0-9]`)
**Symptom:** Two distinct language display names that reduce to the same alnum key (e.g. "Chinese (Simplified)" and "Chinese — Simplified" both → "chinesesimplified") collide in `languageOrder`/`buildLanguageViews` keys and the bulk-export per-language directory names, merging or overwriting one language's slice.
**Root cause:** Lossy key derivation with no uniqueness check.
**Proposed fix:** Disambiguate collisions with a numeric suffix.
**Status:** Fixed — `buildLanguageViews` now tracks emitted keys and appends a numeric suffix on collision via `uniqueLanguageKey` (ReportExport.kt:497-535).

### Bug 40 — Severity: LOW — Category: cost totals exclude untokened secondaries
**Location:** ReportExport.kt:863 (secondary filter `inputTokens != null`)
**Symptom:** A secondary that errored before token usage was recorded but still incurred no cost is excluded — fine — but a secondary whose cost was persisted while tokenUsage was null (legacy) is dropped from the export cost table, undercounting vs the persisted cost fields.
**Root cause:** Filter keys on tokens, not on the presence of cost.
**Proposed fix:** Include rows where either tokens or persisted cost is present.
**Status:** Fixed — same edit as Bug 37: the secondary filter now includes rows where either token usage or a persisted cost is present (ReportExport.kt renderCostsView).

## File: ai/src/main/java/com/ai/ui/helpers/MarkdownTables.kt

### Bug 41 — Severity: LOW — Category: placeholder collides with literal model text
**Location:** MarkdownTables.kt:17-18 (`MD_TABLE_PLACEHOLDER_REGEX = Regex("MDTBL(\\d+)")`) and ThinkSectionContent.kt:66
**Symptom:** If model output legitimately contains a token like `MDTBL3` (the placeholder pattern) and the report also has ≥4 tables, the in-app think-section renderer (ThinkSectionContent.kt:66) substitutes that literal text with table #3's content. The export path (ReportExport.kt:1304) bounds-checks the index, but the in-app path's bounds-check needs verifying.
**Root cause:** The placeholder token is a plain alnum string that can appear in real content.
**Reproduction:** A model that emits the literal `MDTBL0` in prose alongside a real markdown table.
**Proposed fix:** Use a token that can't appear in model output (e.g. a private-use Unicode sentinel) and bounds-check the index on every substitution site.
**Status:** Not a bug — the in-app substitution already bounds-checks `idx !in tables.indices` (ThinkSectionContent.kt:73) and treats out-of-range as text, and the export path bounds-checks too. The remaining literal-collision-with-a-valid-index case is acceptably rare; switching to a PUA sentinel risks the markdown→HTML converter escaping/stripping the char and breaking export substitution.

### Bug 42 — Severity: LOW — Category: separator-only "table" with no body rows
**Location:** MarkdownTables.kt:33-49 (header + separator detected, body loop optional)
**Symptom:** A header row followed by a separator but zero body rows is still parsed as a `MarkdownTable` with empty `rows`, producing an empty `<tbody></tbody>` table in the export and an empty in-app table block.
**Root cause:** No guard requiring at least one body row.
**Proposed fix:** Require `bodyRows.isNotEmpty()` before treating it as a table (or render header-only intentionally).
**Status:** Fixed — a header+separator with zero body rows is no longer parsed as a table; the original lines are emitted as-is instead of producing an empty `<tbody>` (MarkdownTables.kt parseGfmTables).

### Bug 43 — Severity: LOW — Category: cell-count mismatch silently truncates
**Location:** MarkdownTables.kt:43 (`alignments = padded.take(headers.size)`) and buildExportTableHtml:99-102
**Symptom:** A body row with more cells than headers renders extra `<td>`s with `alignAttr(getOrNull(i))` returning `""` (fine), but a row with *fewer* cells than headers produces a short `<tr>` — the table renders ragged with no padding to header width.
**Root cause:** No normalization of body-row cell counts to header width.
**Proposed fix:** Pad/truncate each body row to `headers.size` cells.
**Status:** Fixed — body rows are normalised to the header width at parse time (pad short rows with empty cells, truncate over-long), benefiting both the in-app table and the HTML export (MarkdownTables.kt parseGfmTables).

## File: ai/src/main/java/com/ai/ui/helpers/ModerationTable.kt

### Bug 44 — Severity: MEDIUM — Category: flagged-without-categories invisible
**Location:** ModerationTable.kt:62-67 (`anyModerationFlagged`) and parseModerationRows:94 (`fired = allCats.filterValues { it }.keys`)
**Symptom:** A row can be `flagged == true` at the top level while its `categories` map is empty or all-false (some moderation APIs set the boolean flag from scores without per-category booleans). `anyModerationFlagged` keys off `row.flagged` (correct), but the table's "Categories fired" column then shows "—" for a flagged row, and a reader scanning categories sees nothing fired despite the 🚩.
**Root cause:** `firedCategories` is derived only from the boolean category map, decoupled from the top-level `flagged`.
**Proposed fix:** When `flagged` but no boolean categories fired, surface the top scoring category (or a "(flagged by score)" note) in the fired column.
**Status:** Fixed — when a row is flagged but no boolean category fired, the "Categories fired" column now shows the top scoring category with a "(by score)" note instead of "—" (ModerationTable.kt:126).

### Bug 45 — Severity: LOW — Category: parse returns null on empty-but-valid array
**Location:** ModerationTable.kt:79 / RerankTable.kt:55 (`if (arr.size() == 0) return null`)
**Symptom:** A valid moderation/rerank response that is an empty JSON array (`[]`) returns null, which callers treat as "parse failed" and fall back to raw rendering — so a legitimately empty result reads as a parse error rather than "no rows".
**Root cause:** Empty array conflated with parse failure.
**Proposed fix:** Distinguish empty (return `emptyList()`) from malformed (return null).
**Status:** Fixed — a valid but empty JSON array now returns `emptyList()` (rendered as an empty table) instead of null (raw fallback), in both ModerationTable.kt:79 and RerankTable.kt:55.

### Bug 46 — Severity: LOW — Category: score coercion swallows non-numeric
**Location:** ModerationTable.kt:90-93 (`asDouble` in try, `return@mapNotNull null` on failure)
**Symptom:** A score field that is a string like "0.5" throws on `asDouble` for some Gson configs and the *whole* score entry is dropped (mapNotNull returns null), silently losing that category's score from the breakdown.
**Root cause:** Strict `asDouble` with drop-on-failure rather than tolerant parse.
**Proposed fix:** Fall back to `asString.toDoubleOrNull()` before dropping.
**Status:** Fixed — score parse now falls back to `asString.toDoubleOrNull()` before dropping the category (ModerationTable.kt:90-96).

## File: ai/src/main/java/com/ai/ui/helpers/RerankTable.kt

### Bug 47 — Severity: LOW — Category: score type assumption (int only)
**Location:** RerankTable.kt:61 (`score = ...asNumber?.toInt()`)
**Symptom:** Rerank score is parsed as an Int via `asNumber.toInt()`. A model that returns a fractional score (0.87) is truncated to 0, mis-ranking display and losing precision; rank ordering at parse end uses `rank` not score so ordering is OK, but the displayed Score column is wrong.
**Root cause:** `toInt()` truncation of a possibly-fractional score.
**Proposed fix:** Keep the score as a Double (or Number) and format with decimals like the moderation table does.
**Status:** Fixed — `RerankRow.score` is now `Double?`, parsed via `asDouble` (string fallback), and rendered through `formatRerankScore` (drops trailing .0, up to 3 decimals) in both RerankTable and the View Rerank podium/rank rows (RerankTable.kt, view/Rerank.kt).

### Bug 48 — Severity: LOW — Category: stable-sort on missing rank
**Location:** RerankTable.kt:66 (`sortedBy { it.rank ?: Int.MAX_VALUE }`)
**Symptom:** Rows with a null `rank` all collapse to `Int.MAX_VALUE` and keep input order; if the model omitted ranks for several agents they appear in arbitrary order at the bottom with no tiebreaker.
**Root cause:** No secondary sort key (e.g. id).
**Proposed fix:** Add `thenBy { it.id }`.
**Status:** Fixed — sort is now `compareBy { rank ?: MAX }.thenBy { it.id }` so null-rank rows have a stable order (RerankTable.kt:66).

## File: ai/src/main/java/com/ai/ui/helpers/BulkExport.kt

### Bug 49 — Severity: MEDIUM — Category: filename collision across language dirs in flat mode unreachable but fragile
**Location:** BulkExport.kt:106-153 (all language slices write the SAME filenames into shared `workDir` when `perLanguageDirs` is false)
**Symptom:** `perLanguageDirs` is false only when `viewsToRender.size <= 1`, so today no collision. But the loop unconditionally writes `${safeTitle}_short.html` etc. into `langDir`; if a future change makes `viewsToRender.size > 1` while `perLanguageDirs` is false (e.g. a single-language All-mode with two slices), every language overwrites the prior one's files in `workDir` and the master zip contains only the last slice.
**Root cause:** The filename uniqueness depends entirely on `perLanguageDirs`; there's no per-language filename suffix as a backstop.
**Proposed fix:** Always include `lv.key` in the filename when more than one view is rendered, regardless of the directory mode.
**Status:** Fixed — the filename stem now suffixes `lv.key` when more than one view is rendered without per-language dirs, a filename-level backstop against overwrite in a shared workDir (BulkExport.kt).

### Bug 50 — Severity: LOW — Category: progress total wrong if a render throws
**Location:** BulkExport.kt:91-99, 130-147 (`total = size*9+1`, PDF renders in `withContext(Main)`)
**Symptom:** If a PDF render throws/times out, the `bump()` for that artifact still runs after the render block returns (the bump is unconditional), but the file may be missing/zero-bytes; the zip then contains a corrupt/empty PDF while progress reports success. The user gets a "complete" bundle with broken files.
**Root cause:** `renderHtmlToPdfFile` failures aren't surfaced; `bump()` is unconditional.
**Proposed fix:** Check the render result and either retry, omit the file, or note the failure in progress.
**Status:** Fixed — after each PDF render a `dropIfEmpty` check deletes a missing/zero-byte output (and logs a warning) so the master zip omits the corrupt file instead of shipping it (BulkExport.kt).

### Bug 51 — Severity: LOW — Category: System.gc() calls on IO thread
**Location:** BulkExport.kt:128, 138 (`System.gc()` between PDF renders)
**Symptom:** Explicit `System.gc()` calls add unpredictable pauses to the bulk export and don't reliably free WebView memory; "paranoid but harmless" per the comment, but they can stall the export noticeably on large reports.
**Root cause:** Manual GC hints.
**Proposed fix:** Remove the `System.gc()` calls; rely on the runtime.
**Status:** Fixed — removed both `System.gc()` hints between PDF renders (BulkExport.kt).

## File: ai/src/main/java/com/ai/ui/helpers/HtmlPreviewScreen.kt

### Bug 52 — Severity: MEDIUM — Category: JS enabled on model-authored HTML in a WebView
**Location:** HtmlPreviewScreen.kt:125-141 (`javaScriptEnabled = true`, `loadDataWithBaseURL("about:blank", ...)`)
**Symptom:** The preview renders report HTML — which contains model-generated content — with JavaScript enabled. A model that emits a crafted `<script>` runs in the WebView. File/content access is disabled (good), and the base URL is `about:blank` (limits same-origin), but arbitrary script execution (e.g. fetch to an attacker URL exfiltrating the visible report text, or UI spoofing) is still possible.
**Root cause:** Inline export scripts (table sort, collapsibles) require JS, so JS is globally on; model content is not sandboxed away from those scripts.
**Proposed fix:** Strip/escape model-content `<script>`/event-handler attributes before rendering, or render the interactive chrome with a strict CSP `<meta>` that blocks inline/non-allowlisted script and `connect-src`.
**Status:** Won't fix — a proper CSP/sandbox fix requires reworking the shared export HTML generation (the table-sort/collapsible inline scripts the chrome depends on), which is broad and risks breaking the interactive export. The existing mitigations (no file/content access, about:blank base) limit the blast radius. Deferred.

### Bug 53 — Severity: LOW — Category: AndroidView never reloads on html change
**Location:** HtmlPreviewScreen.kt:115-144 (single `AndroidView` with no `update` block, factory loads once)
**Symptom:** The comment says the WebView is keyed off html length to avoid re-instantiation, but there's no `key(...)` wrapper and no `update` lambda — if `state` transitions Ready→Ready with different html (same `reportId`/`detail`/`language` but a re-fetch), the WebView keeps the old html.
**Root cause:** No `update` callback and no key on the AndroidView; factory runs once.
**Proposed fix:** Add an `update = { it.loadDataWithBaseURL(...) }` keyed on `s.html`, or wrap in `key(s.html)`.
**Status:** Fixed — added an `update` block that reloads only when `s.html` differs from the WebView's stored tag, so a Ready→Ready re-fetch with new html refreshes without re-loading identical content (HtmlPreviewScreen.kt).

## File: ai/src/main/java/com/ai/ui/report/view/Translate.kt

### Bug 54 — Severity: MEDIUM — Category: swipe target list vs grouping mismatch
**Location:** Translate.kt:115-122 (swipe uses `ViewSwipeFilter.Translate`, sets `currentTranslationRunId = m.translationRunId`)
**Symptom:** On a title-bar / body swipe to another report, `currentTranslationRunId` is set to that report's *first* TRANSLATE row's `translationRunId` (ViewSwipeNav.kt:104). If that row has a null `translationRunId` (legacy), `currentTranslationRunId` is left unchanged from the previous report, so the screen loads the new report id with the *old* report's run id and shows "No translation rows in this run".
**Root cause:** `m.translationRunId?.let { currentTranslationRunId = it }` skips the update when the match's runId is null, leaving a stale cross-report runId.
**Reproduction:** Swipe from a report whose translation rows carry runIds to one whose rows don't.
**Proposed fix:** When the match has no runId, fall back to the synthetic `lang:<lang>` grouping id (translationRunGroupingId) rather than keeping the stale value.
**Status:** Fixed — `matchOn` for the Translate filter now always returns `translationRunGroupingId(pick)` (synthetic "lang:<lang>" for null-runId legacy rows), so the swipe always updates `currentTranslationRunId`; the TranslateViewScreen loader was switched to match on the same grouping id (ViewSwipeNav.kt:102-113, view/Translate.kt:93-97).

### Bug 55 — Severity: LOW — Category: source body for META uses unfiltered map
**Location:** Translate.kt:99-102, 235-241 (`metaSources` = all non-TRANSLATE rows by id)
**Symptom:** The META source pane resolves `metaSources[sourceTargetId]`. If the source META row was deleted after the translation was made, the source body is blank "(no content)" with a generic "🧠 Meta" label and no indication the source is gone.
**Root cause:** No "source deleted" state.
**Proposed fix:** Distinguish missing source from empty source in the label.
**Status:** Fixed — a deleted META source now renders the label "🧠 Meta (source deleted)" rather than a generic empty "🧠 Meta" (view/Translate.kt:238-249).

### Bug 56 — Severity: LOW — Category: targetLanguage header from first row only
**Location:** Translate.kt:109-111 (`targetLanguage` from `rows.firstOrNull()`)
**Symptom:** The big header language is taken from the first row only. A run whose rows somehow carry mixed `targetLanguage` (shouldn't happen, but the grouping key allows null→"lang:" merges) would show only the first row's language for the whole list.
**Root cause:** Single-row sampling for a list-wide header.
**Proposed fix:** Assert/verify single language per run, or show the distinct set.
**Status:** Not a bug — a run is single-language by construction (rows are grouped by `translationRunId` / synthetic "lang:<lang>"), so they cannot carry mixed `targetLanguage`. The first-row sample is correct.

## File: ai/src/main/java/com/ai/ui/helpers/TranslationCompare.kt

### Bug 57 — Severity: LOW — Category: copy/share gated on translated only
**Location:** TranslationCompare.kt:70-75 (onCopy/onShare use `translatedContent`)
**Symptom:** The title-bar copy/share always act on the translated content; there's no affordance to copy the original pane. Minor UX gap, not a correctness bug.
**Root cause:** Only one body wired to the actions.
**Proposed fix:** Offer copy of either pane, or document the single-target behaviour.
**Status:** Not a bug — the finding itself states this is "not a correctness bug" but a minor UX gap; copying the translated pane is a sensible default and a pane-chooser is an enhancement, not a fix.

## File: ai/src/main/java/com/ai/ui/report/manage/GenerationHandlers.kt

### Bug 58 — Severity: LOW — Category: onViewAgent handler dead (overridden downstream)
**Location:** GenerationHandlers.kt:29 (`onViewAgent = {}`) vs GenerationPhase.kt:420-422 (local override)
**Symptom:** `onViewAgent` is wired as a no-op here and then *shadowed* by a local lambda inside `GenerationPhase` (420-422). The bundled handler value is dead; a future reader wiring `onViewAgent` here would see no effect.
**Root cause:** Two sources of truth for the same callback.
**Proposed fix:** Remove the dead field or document that GenerationPhase owns it.
**Status:** Not a bug — already documented: the comment at GenerationHandlers.kt:26-29 states the 'report' row tap is handled inside GenerationPhase, and GenerationPhase.kt:419-422 owns the live `onViewAgent`. The bundled no-op is an intentional placeholder, not a defect.

### Bug 59 — Severity: LOW — Category: onViewLog day derived from loadedReportTimestamp not report's own day
**Location:** GenerationHandlers.kt:70-79 (`onViewLog` builds `applog_<yyyyMMdd>.log` from `loadedReportTimestamp`)
**Symptom:** The app-log filename is computed from `loadedReportTimestamp` (when the report was loaded into the screen), not the report's creation day. A report run yesterday and viewed today opens today's log, missing the report's actual log entries.
**Root cause:** Uses the load timestamp instead of the report's own creation/run timestamp.
**Proposed fix:** Use the report's stored timestamp for the day computation.
**Status:** Not a bug — `loadedReportTimestamp` is set from `report.timestamp` (RuntimeState.kt:194), i.e. the report's own creation/run time, not the screen-load time. The variable name is misleading but the value is the report's day. No change needed.

## File: ai/src/main/java/com/ai/ui/report/view/helpers/ViewReportCache.kt

### Bug 60 — Severity: MEDIUM — Category: single-slot cache thrash / cross-screen eviction
**Location:** ViewReportCache.kt:18-35 (one static slot, keyed by id+mtime)
**Symptom:** The cache holds exactly one report. When a sub-View overlay (e.g. Translate, which calls `ViewReportCache.get` for `currentReportId`) and the parent ViewAiReportScreen both read different report ids (e.g. mid-swipe, or a Costs view of report A while the grid restored report B), each `get` evicts the other's entry, so the "avoid re-parse" optimization degenerates to re-parsing on every alternating access.
**Root cause:** Capacity-1 static cache shared by all View screens.
**Proposed fix:** Use a small LRU (2–3 entries) keyed by id.
**Status:** Fixed — replaced the single-slot cache with a 3-entry access-ordered LRU (still mtime-keyed per entry) so alternating reads of two report ids no longer evict each other (ViewReportCache.kt).

### Bug 61 — Severity: LOW — Category: cache not invalidated on translation write
**Location:** ViewReportCache.kt:24-26 (staleness keyed on `ReportStorage.reportLastModified`)
**Symptom:** The cache is invalidated by the *report* file's mtime. A translation write that only touches `SecondaryResultStorage` (not the Report JSON) does not bump the report mtime, so a View screen reading the cached Report alongside freshly-written secondaries can mix a stale Report (e.g. stale `languageName`/`languageIcon`) with new translate rows.
**Root cause:** Staleness tracks only the Report file, not secondary writes that influence View rendering.
**Proposed fix:** Acceptable because the Report fields rarely change on a translate write, but if `languageName` is set by a translate flow the cache should be invalidated then.
**Status:** Not a bug — the cache keys on the Report file's mtime, so any write that touches Report fields (including a translate flow that sets `languageName`) rewrites the file and bumps mtime, invalidating the entry. Pure secondary writes don't change Report-derived rendering. Acceptable as the finding concludes.

## File: ai/src/main/java/com/ai/ui/helpers/ViewSwipeNav.kt

### Bug 62 — Severity: LOW — Category: swipe wraps to self / no-op reload
**Location:** ViewSwipeNav.kt:75-80 (`for (k in 1..n)`, `k == n` lands on current report)
**Symptom:** When only the current report matches a filter, the walk reaches `k == n` (itself) and returns a SwipeMatch for the current report; callers then call `switchReport(sameId)` triggering a needless `restoreCompletedReport` reload and a visual flash.
**Root cause:** The loop deliberately wraps to self to "never dead-end", but for filtered swipes (Translate/HasKind) this reloads the same report.
**Proposed fix:** For filtered modes, return null when the only match is the current report (so the swipe is a no-op without a reload).
**Status:** Fixed — `findSwipeMatch` now walks `1..n` only for `ViewSwipeFilter.Any`; filtered modes walk `1..n-1`, so the only-match-is-current case returns null and the swipe is a true no-op without a reload (ViewSwipeNav.kt:71-80).

### Bug 63 — Severity: LOW — Category: blocking disk I/O inside swipe handler
**Location:** ViewSwipeNav.kt:60-113 (`findSwipeMatch` calls `SecondaryResultStorage.listForReport` per candidate, synchronously)
**Symptom:** `findSwipeMatch` is called directly from the swipe lambdas (e.g. Translate.kt:116, Costs.kt:147) on the main thread for `HasKind`/`HasMeta`/`Translate` filters, doing a disk listing for up to `n` reports synchronously — a many-report library produces a janky/blocking swipe.
**Root cause:** Synchronous storage reads in a gesture callback on the main thread.
**Proposed fix:** Move the match search off the main thread (or cache per-report kind presence).
**Status:** Won't fix — moving the search off-thread requires reworking the synchronous swipe-consume contract (the gesture lambdas return Boolean inline); a per-report kind-presence cache belongs in data/SecondaryResultStorage. Both are out of this pass's scope. Deferred.

## File: ai/src/main/java/com/ai/ui/report/manage/Nav.kt

### Bug 64 — Severity: MEDIUM — Category: RegenerateBatch overlay early-return bypasses report-context locals
**Location:** Nav.kt:261-269 (`if (openRegenBatchId != null) { RegenerateBatchOverlay(...); return }`)
**Symptom:** The regenerate-batch overlay early-returns *before* the `CompositionLocalProvider` block (270-302) that supplies `LocalReportIdsNewestFirst`, `LocalReportSwitchHandler`, `LocalReportNeighborNav`, etc. So inside the overlay none of those locals are provided. `RegenerateBatchScreen` works around this by reading the report icon off disk directly (Run.kt analogue), but any child that expects those locals (e.g. a TitleBar swipe) silently gets defaults.
**Root cause:** The overlay mounts above the provider scope.
**Proposed fix:** Move the early-return inside the `CompositionLocalProvider`, or provide the needed locals around the overlay too.
**Status:** Fixed — moved the RegenerateBatch overlay early-return inside the `CompositionLocalProvider` block (returns `@CompositionLocalProvider`), so the overlay now sees `LocalReportIdsNewestFirst` / `LocalReportSwitchHandler` / `LocalReportNeighborNav` / the icon bundle (Nav.kt:261-282).

## File: ai/src/main/java/com/ai/ui/report/manage/RegenerateBatch.kt (RegenerateBatchManageRow)

### Bug 65 — Severity: LOW — Category: row keyed on swipe-local report id, not the screen's report
**Location:** RegenerateBatch.kt:365-366 (`reportId = LocalCurrentReportIdForSwipe.current`)
**Symptom:** The Manage-screen regenerate row reads the report id from `LocalCurrentReportIdForSwipe`. During a swipe transition this local updates a frame before the rest of the screen's `reportsAgentResults`/`secondaryRuns` switch, so the regenerate row can briefly show the *new* report's job while the body still shows the old report's rows.
**Root cause:** The row's report-id source updates on a different cadence than the body's props.
**Proposed fix:** Key the row on the same `currentReportId` the body uses.
**Status:** Not a bug — `LocalCurrentReportIdForSwipe` is provided as `uiState.currentReportId` (Nav.kt:289), the same source the GenerationPhase body's `currentReportId` resolves to. They update on the same cadence; the regenerate row showing the current report's job is correct. The async secondary-rows reload is a separate transient, not a wrong-report mismatch.

## File: ai/src/main/java/com/ai/ui/report/manage/TranslationL1.kt (model bar denominator)

### Bug 66 — Severity: LOW — Category: bar denominator hides slow models
**Location:** TranslationL1.kt:160 (`maxDone = max done count, coerceAtLeast(1)`) and 313 (`barFrac = row.done / maxDone`)
**Symptom:** The per-model green bar is scaled to the busiest model's *done* count, not its total share of work. A model that picked up many items but completed few shows a near-empty bar even though it's carrying most of the queue; the bar misrepresents load distribution.
**Root cause:** Bar fraction uses `done/maxDone` (throughput), not `total/maxTotal` (assignment).
**Proposed fix:** Either show a two-segment (done/total) bar, or document that the bar is throughput-only.
**Status:** Not a bug — intentional throughput visualization, documented at TranslationL1.kt:158-160 ("busiest model (full bar) stays at top") and 301-305. Switching to assignment-share (total/maxTotal) is a deliberate design change, not a defect.

## File: ai/src/main/java/com/ai/ui/report/manage/view/FanDrillIn.kt (L2 fan-in scroll)

### Bug 67 — Severity: LOW — Category: animateScrollToItem on stale list state
**Location:** FanDrillIn.kt:761-768 (`LaunchedEffect(modelScopedFanIn.size) { animateScrollToItem(0) }`)
**Symptom:** The L2 list auto-scrolls to item 0 whenever a model-scoped fan-in row appears. If the list also has many per-pair rows the user was reading, a newly-arrived fan-in row yanks the scroll to the top mid-read.
**Root cause:** Unconditional scroll-to-top on growth.
**Proposed fix:** Only scroll if the user is already near the top (check `firstVisibleItemIndex`).
**Status:** Fixed — the auto-scroll-to-top now fires only when `firstVisibleItemIndex <= 1`, so a newly-arrived fan-in row no longer yanks the scroll up from someone reading further down (FanDrillIn.kt:775-780).

## Cross-cutting

### Bug 68 — Severity: MEDIUM — Category: ContentWithThinkSections fed arbitrary truncated markdown
**Location:** Translate.kt:294-296, 314 (SidePanel `body.take(previewChars)` then `ContentWithThinkSections(analysis = shown)`)
**Symptom:** Long source/translation bodies are truncated at 360 chars mid-content and then passed through the markdown/think-section renderer. Truncating mid-markdown can cut a `<think>` open tag, a code fence, or a table placeholder (MDTBL) — producing broken rendering or a half-table in the collapsed preview.
**Root cause:** Character truncation applied before the markdown parse, not at a safe boundary.
**Proposed fix:** Truncate on a paragraph/line boundary, or render full and clip visually with `maxLines`.
**Status:** Fixed — the SidePanel preview now cuts on the last line boundary within the preview window (falling back to a hard char cut only when no usable newline exists), avoiding slicing through a `<think>` tag / code fence / table placeholder (view/Translate.kt SidePanel).

### Bug 69 — Severity: LOW — Category: agentModelTitles prop threaded but unused on rows
**Location:** GenerationPhase.kt:395 (`agentModelTitles` param) — the per-row report block (1340-1389) renders only `agentIconRows` and the base model name, never the `AgentModelTitle.title` despite the doc at 46-50 claiming it "replaces the model name on the 'report' row".
**Symptom:** The documented behaviour (model title replacing the model name on the report row, cost folded in) is not implemented in the row rendering — `agentModelTitles` is accepted but not read in the displayRows item body.
**Root cause:** Feature documented in the data class / param but the consuming code path renders `modelLabel(...)` unconditionally.
**Proposed fix:** Either wire `agentModelTitles[agentId]?.title` into the row label or remove the misleading doc + unused param.
**Status:** Fixed — wired `agentModelTitles[agentId]?.title` into the 'report' row label so a generated per-model title replaces the model name as documented, falling back to `modelLabel(...)` when absent (GenerationPhase.kt displayRows item body).

### Bug 70 — Severity: LOW — Category: per-row meta emoji remember keyed on internalPrompts.size
**Location:** GenerationPhase.kt:881-888, 1069-1075 (`remember(run.fanInOf, run.metaPromptId, aiSettings.internalPrompts.size)`)
**Symptom:** The resolved prompt lookup is memoised on `internalPrompts.size`. If a prompt is renamed/retitled without changing the list size, the cached resolved prompt (and thus its cached emoji lookup keyed on name/title) does not refresh until the size changes or `iconRefreshTick` bumps.
**Root cause:** Using collection size as a proxy for content identity.
**Proposed fix:** Key on the relevant prompt's id+name+title rather than the list size.
**Status:** Fixed — both per-row prompt-resolve `remember`s now key on `internalPrompts.map { "id|name|title" }` instead of `internalPrompts.size`, so a rename/retitle refreshes the cached prompt + emoji even when the count is unchanged (GenerationPhase.kt:881-888,1080-1086).
