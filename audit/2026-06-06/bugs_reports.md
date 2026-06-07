# Bug review — Report and Translation areas (audit-3, fresh from current code)

Scope: `ai/src/main/java/com/ai/ui/report/**` and the export helpers in
`ai/src/main/java/com/ai/ui/helpers/**`. Findings are grouped by file and
numbered continuously. Every location was read from the live code (2026-06-06).

## File: ai/src/main/java/com/ai/ui/report/manage/GetInfo.kt

### Bug 1 — Severity: LOW — Category: recomputation / coarse key
**Location:** GetInfo.kt:360-376 (`ReportGetInfoScreen`, `produceState(... settings ...)`)
**Symptom:** The Get-info job list re-reads the whole report from disk and rebuilds every row on any unrelated `Settings` re-emit while the screen is open.
**Root cause:** `produceState` keys on the entire `settings: Settings` object identity, plus `runningInfoJobs`, `iconGenEnabled`, etc. `buildInfoJobs` only reads `settings.internalPrompts` (icon/title/language prompt resolution); keying on the whole Settings makes every settings churn re-trigger the IO read of the report.
**Proposed fix:** Key the `produceState` on `settings.internalPrompts` instead of `settings` (the only slice `buildInfoJobs` consumes).
**Status:** Fixed (2026-06-07) — Get-info job rows now key on `settings.internalPrompts` instead of the full Settings object

### Bug 2 — Severity: LOW — Category: misleading status classifier
**Location:** GetInfo.kt:246-254 (`titleStateFor`), 270-276 (model-title row)
**Symptom:** A per-model title call that concluded with no title and no error message renders as a green ✅ "done" row (doneIcon = the model's icon, label = the model name), implying a title was produced when none was.
**Root cause:** `titleStateFor` returns `InfoJobState.DONE` when `a.modelTitleAttempted()` is true even though `a.modelTitle` is blank and `modelTitleErrorMessage` is null. The "attempted but empty" terminal state is collapsed into DONE with no visual distinction from a real success.
**Proposed fix:** Add a distinct terminal "empty" presentation (e.g. a dimmed ⃠ / "no title" label) for the `modelTitleAttempted() && modelTitle.isNullOrBlank()` case, separate from a genuine DONE.
**Status:** Fixed (2026-06-07) — attempted-but-empty model-title jobs now render as a dim empty terminal state with a no-title label

### Bug 3 — Severity: LOW — Category: shared running-key ambiguity
**Location:** GetInfo.kt:135,152 (`"${report.id}|language" in running`)
**Symptom:** Both the `language` (detection) row and the `language-icon` row test the same `"<id>|language"` membership for their RUNNING state, so once the detection name is set, a still-in-flight detection call keeps the icon row showing "Generating…/Running" even though the icon call may not have started.
**Root cause:** There is one running-key per language flow (`|language`) but two rows; the icon row distinguishes itself only by the `report.languageName.isNullOrBlank()` CLOCK guard, after which it falls through to the shared key.
**Proposed fix:** Emit a distinct running key for the icon stage (e.g. `"<id>|language-icon"`) so the two rows reflect their own call's liveness.
**Status:** Fixed (2026-06-07) — language detection and language-icon generation now use separate running keys

## File: ai/src/main/java/com/ai/ui/report/manage/GenerationPhase.kt

### Bug 4 — Severity: MEDIUM — Category: Compose state / scroll position lost
**Location:** GenerationPhase.kt:760-764 (`newRowTrigger` + `LaunchedEffect(currentReportId, newRowTrigger, paused)`)
**Symptom:** Opening the "Report - Get info" overlay (or any overlay that flips `paused`) and returning to the result list snaps the result LazyColumn back to the top, discarding the user's scroll position on a long report.
**Root cause:** The auto-scroll-to-top effect keys on `paused` in addition to `currentReportId`/`newRowTrigger`. When an overlay sets `paused=true` and then clears it, the effect re-runs `resultListState.scrollToItem(0)`. The comment only describes report-open and new-row appends as the intended triggers; `paused` is an unintended re-anchor trigger.
**Proposed fix:** Drop `paused` from the effect key (it is only used to *suppress* the scroll inside the body via `if (paused) return`); gate the body on `paused` but don't re-key on it.
**Status:** Fixed (2026-06-07) - dropped 'paused' from the scroll-to-top LaunchedEffect key (it now only gates the body), so closing a paused overlay no longer re-anchors the list

### Bug 5 — Severity: LOW — Category: cost double-count window
**Location:** GenerationPhase.kt:561-577 (`liveTranslation` fold), 594-595 (`totalCost`)
**Symptom:** During the brief window after a translation run's rows persist but before a `TranslationRunSummary` for that runId exists, the run's cost is counted both in `liveTranslationCost` and in `secondaryTotals` (computed from the just-persisted rows), briefly inflating the bottom-bar total.
**Root cause:** The live fold excludes runs whose runId is in `translationRunSummaries`, but `secondaryTotals` is computed upstream from disk independently. If the summaries list lags the secondary totals (different recompute cadence), the exclusion set is empty for that runId while secondaryTotals already includes it.
**Proposed fix:** Exclude any runId that already has a persisted TRANSLATE row in `secondaryTotals` from the live fold (track persisted runIds from the same source the totals use), not just runIds that have a summary object.
**Status:** Fixed (2026-06-07) — live translation totals now exclude run ids already present in persisted TRANSLATE rows

### Bug 6 — Severity: LOW — Category: coarse remember key
**Location:** GenerationPhase.kt:655 (`displayRows = remember(isStagedMode, staged, selectedAgents, reportsAgentResults, aiSettings)`)
**Symptom:** The agent display-row list rebuilds on any `Settings` re-emit, even when only an unrelated setting changed.
**Root cause:** Keys on the whole `aiSettings` object; the builder only needs `getAgentById`/`getEffectiveModelForAgent` (agent definitions). A new `Settings` instance on any edit invalidates the memo.
**Proposed fix:** Key on `aiSettings.agents` (the slice the builder reads) rather than the whole Settings.
**Status:** Fixed (2026-06-07) — display rows now remember against `aiSettings.agents` instead of the full settings object

## File: ai/src/main/java/com/ai/ui/report/manage/TranslationRun.kt

### Bug 7 — Severity: LOW — Category: stuck loading state / silent dead-end
**Location:** TranslationRun.kt:210-237 (`persisted` produceState + `if (run == null)` branch)
**Symptom:** A finished run (liveRun null) whose `loadPersisted()` returns null shows "Loading…" forever with no retry and no error.
**Root cause:** `produceState` runs `loadPersisted()` once per key change; `run = liveRun ?: persisted` stays null when both are null, and the only escape is the `return@CompositionLocalProvider` "Loading…" placeholder. `buildPersistedTranslationRunState` returns null when no TRANSLATE rows match the runId (e.g. all rows deleted out-of-band, or a synthetic legacy runId that no longer groups anything), leaving the screen permanently on "Loading…".
**Proposed fix:** Distinguish "still loading" from "loaded null" (e.g. a nullable wrapper / sentinel) and render an explicit "This translation run no longer exists" + back affordance when the load resolved to null.
**Status:** Fixed (2026-06-07) — persisted translation loads now distinguish loading from loaded-null and show a missing-run message with Back

## File: ai/src/main/java/com/ai/ui/report/manage/TranslationL1.kt

### Bug 8 — Severity: MEDIUM — Category: layout churn during live run
**Location:** TranslationL1.kt:257-262 (`TranslationWorkersScreen` modelRows `sortedWith(compareByDescending { it.done } ...)`)
**Symptom:** On the 🐜 Translation-workers screen during an active run, model rows visibly jump/reorder every time an item completes, because rows are ordered by `done` count descending. Tap targets move under the user's finger.
**Root cause:** The sort key `done` (then `total`, then model name) changes continuously while the run progresses. The sibling L1 *types* list explicitly sorts by `total` (stable across status flips) precisely to avoid this; the workers list does not.
**Proposed fix:** Sort modelRows by `total` desc then model name (stable while statuses flip), matching the L1 types list; reserve `done`-based ordering for finished runs only.
**Status:** Fixed (2026-06-07) - TranslationWorkers modelRows now sort by total desc then model name (stable while statuses flip), matching the L1 types list

### Bug 9 — Severity: LOW — Category: inconsistent cost format across the drill-in
**Location:** TranslationL1.kt:151 (`formatCents(run.totalCostDollars, decimals = 2)} ¢`) vs TranslationL2.kt:117 (`formatCents(cost)` no ¢, decimals=4) vs TranslationL3.kt:227 (`formatCents(item.costDollars)} ¢`)
**Symptom:** The same run's cost is rendered with 2 decimals + "¢" on L1, 4 decimals + no unit on the L2 header, and 4 decimals + "¢" on L3 — three different presentations within one feature.
**Root cause:** Each level calls `formatCents` with different `decimals` and a hand-appended (or omitted) "¢" suffix; no shared cost-cell helper.
**Proposed fix:** Introduce one shared cost-cell formatter (fixed decimals + unit) and use it in L1/L2/L3.
**Status:** Fixed (2026-06-07) — L1/L2/L3 translation cost cells now share one cents formatter with a consistent unit suffix

## File: ai/src/main/java/com/ai/ui/report/manage/TranslationL2.kt

### Bug 10 — Severity: LOW — Category: stale label / wrong wording
**Location:** TranslationL2.kt:123-126 (empty-state `Text("No items for this model")`)
**Symptom:** In TYPES mode (grouping by trace/cost type) an empty group still reads "No items for this model".
**Root cause:** The empty-state string is hardcoded for the MODELS dimension; it is not branched on `isModels`.
**Proposed fix:** Use `if (isModels) "No items for this model" else "No items for this type"`.
**Status:** Fixed (2026-06-07) — the L2 empty state now says model or type based on the active grouping

### Bug 11 — Severity: LOW — Category: cosmetic inconsistency
**Location:** TranslationL2.kt:168-172 (per-row cost cell)
**Symptom:** Each L2 row always renders a cost cell via `formatCents(item.costDollars)` even when the cost is 0.0, while the header (line 115) and the L1 rows gate the cost on `cost > 0.0`. A queued/pending item shows "0.0000".
**Root cause:** The row's third column is unconditional, unlike every sibling cost cell which hides zero.
**Proposed fix:** Gate the row cost on `item.costDollars > 0.0` (or render an em-dash) for consistency.
**Status:** Fixed (2026-06-07) — L2 row cost cells now render blank when the item cost is zero

## File: ai/src/main/java/com/ai/ui/report/manage/TranslationL3.kt

### Bug 12 — Severity: LOW — Category: trace aliasing for legacy rows
**Location:** TranslationL3.kt:189-197 (`traceFilename` fallback)
**Symptom:** For a persisted (legacy, reconstructed-from-disk) run where items carry no `traceFile`, every item the same model translated links to the *same* most-recent translate-tagged trace, so the 🐞 trace icon opens the wrong call for all but the latest item.
**Root cause:** The fallback picks `ApiTracer.getTraceFiles().filter { model && category startsWith "translate" }.maxByOrNull { timestamp }` — a single shared result for the whole model, with no per-item disambiguation.
**Proposed fix:** Match the trace by timestamp proximity to the item (closest-timestamp tiebreak, as `SecondaryResultDetailScreen` does), or hide the trace icon for legacy rows lacking `traceFile`.
**Status:** Fixed (2026-06-07) — Translation L3 now shows trace links only for exact stored trace filenames and hides unsafe legacy fallbacks

### Bug 13 — Severity: LOW — Category: stale content (missing version key)
**Location:** TranslationL3.kt:134 (`source` produceState keyed on `reportId, item.id, item.kind, item.target`)
**Symptom:** The source pane (report prompt / agent response / META source) is resolved from disk once and won't refresh if the underlying report or META row changes while the L3 screen stays open.
**Root cause:** The `produceState` keys omit `ReportDataVersion` / `SecondaryDataVersion`, unlike the view-side screens which subscribe to both.
**Proposed fix:** Add `ReportDataVersion.version`/`SecondaryDataVersion.version` to the key list.
**Status:** Fixed (2026-06-07) — Translation L3 source resolution now keys on report and secondary data versions

## File: ai/src/main/java/com/ai/ui/report/manage/Translations.kt

### Bug 14 — Severity: LOW — Category: LazyColumn key collision (legacy)
**Location:** Translations.kt:86 (`items(summaries, key = { "trs-${it.runId}" })`)
**Symptom:** Two summaries with a blank/identical `runId` (legacy translation runs that predate `translationRunId`) collide on the LazyColumn key, dropping one row.
**Root cause:** The key uses only `runId`; legacy rows can share an empty runId.
**Proposed fix:** Use a composite key (e.g. `runId.ifBlank { targetLanguage }` plus an index) so legacy summaries stay distinct.
**Status:** Fixed (2026-06-07) — finished translation summaries now use indexed composite keys with a legacy language fallback

## File: ai/src/main/java/com/ai/ui/report/manage/FindAlternativeTitles.kt

### Bug 15 — Severity: LOW — Category: dead code
**Location:** FindAlternativeTitles.kt:60 (`val tappable = candidate is TitleCandidate.Done`)
**Symptom:** `tappable` is computed but never referenced; the clickable modifier re-derives the same condition inline (line 63).
**Root cause:** Leftover local from an earlier refactor.
**Proposed fix:** Delete the unused `tappable` val.
**Status:** Fixed (2026-06-07) — removed the unused `tappable` local

## File: ai/src/main/java/com/ai/ui/report/manage/FindAlternativeTranslations.kt

### Bug 16 — Severity: LOW — Category: unstable row identity
**Location:** FindAlternativeTranslations.kt:39-44 (`ordered.forEach { c -> TranslationCandidateRow(...) }`)
**Symptom:** Candidates are rendered in a `Column.forEach` with no stable key; two candidates sharing the same `(provider, model)` (e.g. a model selected twice, or the same model re-run) reuse composition slots by position, so a Running→Done transition can flash the wrong row's body/cost.
**Root cause:** Positional composition without keys for a list whose elements aren't guaranteed unique by `(provider,model)`.
**Proposed fix:** Use a stable per-candidate id (candidate's own id, or `(provider,model,index)`) and a `key(...)` block around each row.
**Status:** Fixed (2026-06-07) — alternative translation rows now use keyed composition by provider/model/index

## File: ai/src/main/java/com/ai/ui/helpers/TranslationGrouping.kt

### Bug 17 — Severity: LOW — Category: grouping-key collision (legacy)
**Location:** TranslationGrouping.kt:12-13 (`translationRunGroupingId`)
**Symptom:** Two distinct legacy translation runs to the *same* language (both with null `translationRunId`) collapse into one drill-in / one View Translate screen, merging their rows.
**Root cause:** The legacy fallback `"lang:${targetLanguage}"` is per-language, not per-run, so it cannot separate two runs to the same language.
**Proposed fix:** Accept that legacy rows can't be split, but document it; for new rows the `translationRunId` already disambiguates — ensure all new TRANSLATE rows always carry a non-null runId so this fallback is never hit going forward.
**Status:** Fixed (2026-06-07) — new TRANSLATE rows are documented as nonblank-run-id rows and blank ids now fall back to the legacy language grouping path

## File: ai/src/main/java/com/ai/ui/report/view/Translate.kt

### Bug 18 — Severity: LOW — Category: missing LazyColumn key
**Location:** Translate.kt:209 (`items(rows) { row -> TranslatePair(...) }`)
**Symptom:** The translate-pair list uses index-based identity; when the row set changes (a new translation lands, or a report swap repopulates), Compose reuses item state by position rather than by `SecondaryResult.id`.
**Root cause:** No `key = { it.id }` on the `items` call. The `expanded` map is keyed by `row.id` so the user-visible collapse state survives, but recomposition/animation efficiency and any future per-row remember would mis-associate.
**Proposed fix:** Add `key = { it.id }`.
**Status:** Fixed (2026-06-07) — Translate view rows now use `SecondaryResult.id` as their LazyColumn key

### Bug 19 — Severity: LOW — Category: state leak across report swipe
**Location:** Translate.kt:120 (`val expanded = remember { TranslateExpansionMap() }`)
**Symptom:** The read-more/collapse expansion map is `remember`ed without a key on `currentReportId`, so after a title-bar swipe to a different report the previous report's expansion entries persist in the map.
**Root cause:** `remember { }` survives the in-place report swap (the composable isn't remounted). Entries are keyed by `row.id` so there is no visible mismatch, but the map accumulates stale entries across every swiped-through report.
**Proposed fix:** `remember(currentReportId) { TranslateExpansionMap() }`.
**Status:** Fixed (2026-06-07) — expansion state now keys on `currentReportId` so swiping reports clears stale row entries

## File: ai/src/main/java/com/ai/ui/report/view/Main.kt

### Bug 20 — Severity: MEDIUM — Category: stale icon / missing recomposition key
**Location:** Main.kt:975 (`metaTiles`), 1041 (`fanOutTiles`), 1080 (`fanInTiles`), 911 (`docTiles`)
**Symptom:** A freshly-generated internal-prompt / report icon does not appear on the View tile grid until some unrelated recomposition; the tile keeps showing the static fallback glyph.
**Root cause:** These `remember(...)` blocks each call `InternalPromptIconCache.get(...)` but do **not** include `iconRefreshTick` in their key lists, so a cache write (which bumps `iconRefreshTick`) doesn't invalidate them. `firstFanOutIcon` (line 620) *does* include `iconRefreshTick`, proving the intended pattern; the tile builders diverge. The screen's own KDoc (line 113-116) promises the tiles recompose on `iconRefreshTick`.
**Proposed fix:** Add `iconRefreshTick` to the key lists of `metaTiles`, `fanOutTiles`, `fanInTiles` (and `docTiles` for the report icon).
**Status:** Fixed (2026-06-07) — doc/meta/fan-out/fan-in tile builders now key their remember blocks on iconRefreshTick, so cache writes invalidate the View grid icons

### Bug 21 — Severity: MEDIUM — Category: dead parameter / missing cold-cache trigger
**Location:** Main.kt:120 (`onMissingPromptIcon` param) — never invoked anywhere in the function (only referenced in a comment at 1048)
**Symptom:** When a meta/fan-out/fan-in tile's prompt has no cached emoji, the View grid never kicks off icon generation, so the tile stays on the static fallback indefinitely (the cold-cache path the result list uses is never exercised here).
**Root cause:** `onMissingPromptIcon` is wired by the caller (`PrimaryOverlays.kt:477` / `GenerationPhase.kt:450` → `promptIconCallbacks.onKickoff`) but `ViewAiReportScreen` never calls it; the comment at line 1048 claims the fan-out tile "fire[s] onMissingPromptIcon to kick off generation" but the code does not.
**Proposed fix:** In the tile builders, when `resolvedPrompt != null && cached == null && useInternalPromptsIcons`, fire `onMissingPromptIcon(resolvedPrompt)` (guarded so it fires once per prompt, e.g. via a remembered set), matching the result-list cold-cache path.
**Status:** Fixed (2026-06-07) — ViewAiReportScreen now fires onMissingPromptIcon once per missing meta/fan-out/fan-in prompt cache entry while internal-prompt icons are enabled

### Bug 22 — Severity: MEDIUM — Category: overlay back-stack / wrong route pop
**Location:** Main.kt:674-682 (`seededFromOutside`) and 706-719 (Reports onBack)
**Symptom:** After one external-seed navigation (Model Info View → `aiReportViewAtAgent`), any *later* opening of the Reports sub-overlay (the Reports tile, or the rerank-podium jump) makes Back/`onBack` call `seedBundle.onExitToList?.invoke()`, popping the whole AI_REPORTS route instead of returning to the View tile grid.
**Root cause:** `seededFromOutside` is `remember(seedBundle.initialReportsAgentId) { ...isNotBlank() }`, reading the *raw* bundle value, which is never cleared for the lifetime of the route. `lastSeededAgentId` guards re-seeding the overlay but does not gate `seededFromOutside`; so once the bundle carries a non-blank id, every Reports-overlay exit takes the external-seed pop path.
**Proposed fix:** Gate `seededFromOutside` on whether *this* open was the seeded one (e.g. compare `reportsViewInitialAgentId == seedBundle.initialReportsAgentId` at open time, or clear/consume the bundle's id after the first seed) rather than on the persistent raw bundle value.
**Status:** Fixed (2026-06-07) — Reports overlay now carries a per-open reportsViewSeededFromOutside flag set only by the external seed and cleared for normal tile/rerank opens and on close

### Bug 23 — Severity: MEDIUM — Category: only-first-run picked
**Location:** Main.kt:618-629 (`firstFanOutItem`/`firstFanOutName`/`firstFanOutIcon`), 691-705 (Reports view fan-out wiring)
**Symptom:** On a report with more than one fan-out run, the Reports view's per-response fan-out affordance always shows the *first* run's name + icon and opens that run, regardless of which fan-out the user intends — the other runs are unreachable from the Reports response card.
**Root cause:** `firstFanOutItem = everyItems["fan_out"].orEmpty().firstOrNull()` collapses the whole fan-out set to one, and that single name/icon is threaded into `ReportsViewScreen`.
**Proposed fix:** Either pass the full fan-out list and let the response card present a chooser when >1 run exists, or scope the affordance to the run whose initiator matches the displayed model.
**Status:** Fixed (2026-06-07) — ReportsViewScreen now receives all fan-out runs, opens the single run directly, and shows a chooser when multiple runs are available from a response card

### Bug 24 — Severity: LOW — Category: recomputation (unstable key)
**Location:** Main.kt:1294-1298 (`combinedTiles` then `sortedTiles = remember(combinedTiles, savedOrder)`)
**Symptom:** `sortedTiles` recomputes on every recomposition.
**Root cause:** `combinedTiles` is built with `+` list concatenation outside `remember`, so a new list instance is created each pass; `remember(combinedTiles, savedOrder)` then sees a new key every time and re-sorts.
**Proposed fix:** Wrap `combinedTiles` in `remember(docTiles, metaTiles, fanOutTiles, fanInTiles, computedTiles)`, or key `sortedTiles` on the stable component lists rather than the concatenated instance.
**Status:** Fixed (2026-06-07) — combined tile concatenation is now remembered from the stable component lists before sorting

## File: ai/src/main/java/com/ai/ui/report/view/Meta.kt

### Bug 25 — Severity: MEDIUM — Category: icon precedence disagreement (tile vs detail)
**Location:** Meta.kt:172-176 (`displayedEmoji = cachedIcon ?: rowIcon ?: ...`)
**Symptom:** A META row with a per-row icon override set via Find-alternative-icons (`pickMetaRowIcon`) shows its custom glyph on the View *tile* but a *different* (shared-cache) glyph on the MetaViewScreen detail header.
**Root cause:** The View tile resolves `rowIcon ?: cachedEmoji` (Main.kt:993 — per-row pick wins, by design, per its comment), but MetaViewScreen resolves `cachedIcon ?: rowIcon` (shared cache wins) — inverted precedence. It also uses `getByName(name)` while the tile uses `get(name, title)`.
**Proposed fix:** Make MetaViewScreen use `rowIcon ?: cachedIcon` (and the same `get(name, title)` lookup) so the per-row override wins in both places.
**Status:** Fixed (2026-06-07) - MetaViewScreen header now resolves rowIcon ?: cachedIcon (per-row pick wins), matching the View tile

## File: ai/src/main/java/com/ai/ui/report/view/FanIn.kt

### Bug 26 — Severity: MEDIUM — Category: icon precedence disagreement (tile vs detail)
**Location:** FanIn.kt:123-127 (`headerIcon = cachedIcon ?: rowIcon ?: fanInKnot`)
**Symptom:** Same as Bug 25 for fan-in: a per-row icon override shows on the Fan-in tile but not on the FanInViewScreen header.
**Root cause:** The fan-in tile uses `rowIcon ?: cachedEmoji` (Main.kt:1092) but FanInViewScreen uses `cachedIcon ?: rowIcon` — inverted.
**Proposed fix:** Flip to `rowIcon ?: cachedIcon` in FanInViewScreen.
**Status:** Fixed (2026-06-07) - FanInViewScreen header now resolves rowIcon ?: cachedIcon, matching the Fan-in tile

## File: ai/src/main/java/com/ai/ui/report/view/Fan.kt

### Bug 27 — Severity: LOW — Category: pager wrap edge / no end-stop
**Location:** Fan.kt:225 (`responderPagerState = rememberWrapPager(responders.size.coerceAtLeast(2), 0)`)
**Symptom:** An initiator with a single responder still gets a wrapping pager; "No more responders" never appears and the user can "swipe" the lone responder onto itself indefinitely.
**Root cause:** `coerceAtLeast(2)` forces `rememberWrapPager`'s `wrap = realCount > 1` branch on even for one responder. `wrapTo(1)` always returns 0 so the body is correct, but the wrap behaviour (and the edge overlay) is wrong for the 1-item case.
**Proposed fix:** Pass `responders.size` (not coerced) — `rememberWrapPager` already re-reads the live count via `wrapTo`, so the coerce isn't needed to prevent overflow.
**Status:** Fixed (2026-06-07) — responder pager now receives the actual responder count so single-responder runs do not wrap

### Bug 28 — Severity: LOW — Category: non-lazy composition of all bodies (perf)
**Location:** Fan.kt:548-560 (✋ all-responders `Column(verticalScroll).forEach { FanOutResponderCard(...) }`)
**Symptom:** Switching to ✋ "all" mode on a fan-out with many responders composes every responder card (each running the full markdown/think pipeline via `ContentWithThinkSections` when expanded) at once, with no lazy windowing — jank / memory spike on large runs.
**Root cause:** A plain scrolling `Column` rather than a `LazyColumn`; all children compose eagerly.
**Proposed fix:** Use a `LazyColumn` (keyed by `pair.id`) for the ✋ list so off-screen responder cards aren't composed.
**Status:** Fixed (2026-06-07) — all-responders mode now uses a LazyColumn keyed by responder row id

## File: ai/src/main/java/com/ai/ui/report/view/Costs.kt

### Bug 29 — Severity: LOW — Category: inconsistent cost precision across screens
**Location:** Costs.kt:382 (`formatCentsValue(cents, 4)`) vs AnswerMatrix.kt:451-457 (tiered 2/3/4dp) vs ReportInfoScreen.kt:149 (`formatCents(totalCents, 2)`)
**Symptom:** The same report cost is shown to a different number of decimals on the Costs view (4), the Answer Matrix (2–4 by magnitude), and the Report-info screen (2), so a user comparing them sees mismatched figures.
**Root cause:** Three private `formatCentsValue`/`formatCents` call sites with divergent decimal rules and no shared policy.
**Proposed fix:** Centralise a single cents formatter with one decimal policy and use it across the three screens.
**Status:** Fixed (2026-06-07) — Costs, Answer Matrix, and Report Info now share the same cents-native formatter

## File: ai/src/main/java/com/ai/ui/report/view/Moderation.kt

### Bug 30 — Severity: LOW — Category: locale (comma-decimal display)
**Location:** Moderation.kt:385 (`val text = "$cat ${"%.2f".format(score)}"`)
**Symptom:** On a comma-decimal locale (the user's nl-NL device) moderation category chips render scores with a comma ("violence 0,30") while the rest of the app uses period decimals.
**Root cause:** `"%.2f".format(score)` uses the default locale; the codebase otherwise pins numeric formatting to `Locale.US` (see `UiFormatting.kt`).
**Proposed fix:** Use `String.format(Locale.US, "%.2f", score)`.
**Status:** Fixed (2026-06-07) — moderation category chips now format scores with `Locale.US`

### Bug 31 — Severity: LOW — Category: index-based agent mapping mismatch
**Location:** Moderation.kt:104-114 (`labels`/`responses` mapped by `idx+1`), 232/246/251 (`agentLabels[r.id]`, `agentResponses[r.id]`)
**Symptom:** Moderation pages map each parsed row's `id` (1-based) to the n-th SUCCESS agent's model/response. If the report's successful-agent set changed since the moderation ran (an agent removed/added/regenerated), the chips and response card pair with the wrong model, or fall to "(unknown)".
**Root cause:** The row→agent association is purely positional (re-derived from the *current* agent list each load), not stored against a stable agentId in the moderation result.
**Proposed fix:** Persist the moderated agentId per moderation row, or render a clear "agent set changed" notice when the row count and current success-agent count disagree.
**Status:** Fixed (2026-06-07) — Moderation view now warns when parsed rows no longer match the current successful-agent count

## File: ai/src/main/java/com/ai/ui/report/view/AnswerMatrix.kt

### Bug 32 — Severity: MEDIUM — Category: format→parse round-trip / imprecise total
**Location:** AnswerMatrix.kt:116-120 (`totalCost = matrixRows.mapNotNull { it.cost.removeSuffix(" ¢").toDoubleOrNull() }.sum()`)
**Symptom:** The summary "Cost" total is computed by parsing each row's already-*formatted* cost string back into a Double, so it sums display-rounded values (the `>=10` branch shows only 2 decimals) — the headline total drifts from the true sum, and the code is the exact `String.format(...).toDouble()` anti-pattern flagged for comma-decimal locales.
**Root cause:** `AnswerMatrixRow.cost` is stored as a formatted String (`formatCentsValue(costUsd*100)`); the total re-parses it instead of summing the raw cents. It is *currently* crash-safe only because `formatCentsValue` pins `Locale.US`; if that helper's locale ever changes, `toDoubleOrNull` (locale-independent, expects '.') would silently return null and zero the total on nl-NL.
**Proposed fix:** Carry the raw cents (Double) on `AnswerMatrixRow` and sum that; format only for display.
**Status:** Fixed (2026-06-07) — AnswerMatrixRow carries numeric costCents; the summary total sums that instead of re-parsing the rounded display string

### Bug 33 — Severity: LOW — Category: heuristic misfires on translated text
**Location:** AnswerMatrix.kt:338 (`extractMatrixSignals(displayBody)`), 373-421, 474-497 (English-only regexes)
**Symptom:** When the Answer Matrix is viewed in a non-Original language, the Stance / Confidence / Risk columns become meaningless (every row reads "Neutral" / "Medium" / "None explicit").
**Root cause:** `displayBody` is the *translated* agent response when a language is active (line 332), but `recommendationRegex`/`riskRegex`/`confidence*Regex`/`refusalRegex` are English-only word lists that don't match other languages.
**Proposed fix:** Run the signal extraction on the Original (English) body even when displaying a translation, or hide the heuristic columns when a non-Original language is selected.
**Status:** Fixed (2026-06-07) — Answer Matrix heuristics now extract from the original response body even when translated labels are shown

## File: ai/src/main/java/com/ai/ui/report/view/Prompt.kt

### Bug 34 — Severity: LOW — Category: pager not re-centred after report swap
**Location:** Prompt.kt:131-133 (`pagerState = rememberWrapPager(languages.size, initialIndex)`), no re-centre effect
**Symptom:** After an in-place title-bar swipe to another report, the prompt pager keeps its previous page index; the new report's prompt can open on a language page it doesn't have (the title bar shows that language while the body silently falls back to `report.prompt`).
**Root cause:** Unlike Meta/FanIn (which run a `LaunchedEffect(languages, currentResultId)` re-centre), Prompt has no re-seek after `currentReportId` changes; `rememberWrapPager`'s `initialIndex` only applies at creation.
**Proposed fix:** Add a `LaunchedEffect(currentReportId, languages)` that re-centres to the requested/Original page when the report changes.
**Status:** Fixed (2026-06-07) — Prompt view now re-centres its language pager when the report or requested language changes

## File: ai/src/main/java/com/ai/ui/report/view/Icons.kt

### Bug 35 — Severity: LOW — Category: overlay state not keyed on report
**Location:** Icons.kt:120-124 (`openedReportsAgentId` / `openedPair*` `rememberSaveable` with no report key)
**Symptom:** The child-overlay selection state isn't reset when the screen swaps reports in place; stale agent/pair ids from the previous report survive the swipe.
**Root cause:** The overlay vars use `rememberSaveable { }` without keying on `currentReportId`. Today the outer swipe is only active while no overlay is open, so it's not user-visible, but the state is logically wrong after a swap.
**Proposed fix:** Key the overlay-state `rememberSaveable(currentReportId)` so a report swap clears them.
**Status:** Fixed (2026-06-07) — Icons child-overlay state is now keyed by `currentReportId`

## File: ai/src/main/java/com/ai/ui/report/view/ValueView.kt

### Bug 36 — Severity: HIGH — Category: wrong result (cost shown 100× too large)
**Location:** ValueView.kt:155 (`at ${formatCents(it.costCents)}`) and 262 (`${formatCents(p.costCents)}`)
**Symptom:** Every cost figure on the Value view (the "Best value" annotation and every model row) is displayed 100× larger than the real cost — e.g. a $0.012 (1.2 ¢) call reads "120".
**Root cause:** `ValuePoint.costCents` is already in cents (`costUsd * 100.0`, line 88), but the shared `formatCents(value)` helper expects **dollars** and multiplies by 100 internally (`UiFormatting.kt:28` → `value * 100`). Passing cents into `formatCents` double-applies the ×100. (AnswerMatrix avoids this by using its own `formatCentsValue` that does *not* re-multiply.)
**Proposed fix:** Either pass dollars (`p.costCents / 100.0`) to `formatCents`, or use a cents-native formatter (like AnswerMatrix's `formatCentsValue`) that doesn't re-multiply.
**Status:** Fixed (2026-06-07) — costCents now divided by 100 before formatCents (which re-multiplies)

### Bug 37 — Severity: LOW — Category: mixed quality units in Pareto
**Location:** ValueView.kt:84-99 (`buildValuePoints`, `quality = row.score ?: rank?.let { (n - it + 1) }`)
**Symptom:** When some rerank rows carry a numeric `score` and others only a `rank`, the Pareto-dominance and best-value computation compares score-scaled qualities against rank-scaled ones — incomparable units, so dominance/best-value can be wrong.
**Root cause:** The fallback derives a synthetic quality `(n - rank + 1)` on a different scale from real model scores, then both feed the same `>=`/dominance comparison.
**Proposed fix:** Use one consistent quality basis for all points (all-rank or all-score); if mixing is unavoidable, normalise to a common 0–1 scale before comparing.
**Status:** Fixed (2026-06-07) — Value view now derives all point quality values from one ordered rank scale before Pareto comparison

## File: ai/src/main/java/com/ai/ui/report/view/FanPair.kt

### Bug 38 — Severity: LOW — Category: preview slices markdown mid-token
**Location:** FanPair.kt:238-240 (`shown = body.take(previewChars).trimEnd() + "…"`)
**Symptom:** A collapsed pair bubble's preview can cut through a `<think>…</think>` tag, a ``` ``` ``` code fence, or an `MDTBL<n>` table placeholder mid-token, producing broken markdown in the preview (unclosed think section / half a table placeholder shown as text).
**Root cause:** A hard character cut at 360 with no line-boundary awareness. The sibling Translate.kt `SidePanel` explicitly cuts on a newline boundary to avoid exactly this; FanPair does not.
**Proposed fix:** Reuse the line-boundary cut logic from `Translate.kt:329-338` (break on the last `\n` inside the preview window).
**Status:** Fixed (2026-06-07) — FanPair collapsed previews now prefer a newline boundary before adding the ellipsis

## File: ai/src/main/java/com/ai/ui/report/info/ReportInfoScreen.kt

### Bug 39 — Severity: LOW — Category: totals don't reconcile
**Location:** ReportInfoScreen.kt:103-107 (`totalCents` includes `deletedCents`; `apiCalls`/`inTokens`/`outTokens`/`modelCount` exclude deleted)
**Symptom:** "Total cost" includes spend from deleted items while "API calls", "Tokens", and "Models used" count only current rows, so the displayed total is larger than the sum of the per-call figures the screen also shows — confusing.
**Root cause:** `totalCents = totalInC + totalOutC + deletedCents`, but the other roll-ups read only `costData.rows` (current items).
**Proposed fix:** Either surface the deleted-items contribution as its own labelled line (as the cost tables do) or exclude it from the headline so the numbers reconcile.
**Status:** Fixed (2026-06-07) — Report Info now shows deleted-item cost as its own totals row when nonzero

## File: ai/src/main/java/com/ai/ui/helpers/ReportExport.kt

### Bug 40 — Severity: MEDIUM — Category: locale (comma decimals in shared export)
**Location:** ReportExport.kt:1038-1054, 1066-1072 (cost table), 1208 (rerank score), 1272 (moderation score)
**Symptom:** On a comma-decimal device every cost-cents / score number in the shared HTML export renders with commas ("12,34"), unlike the in-app figures (period). The HTML is a shareable artifact; comma decimals in a "Total cents" column break spreadsheet import and read inconsistently next to the rest of the document.
**Root cause:** All these use `"%.2f"/"%.1f"/"%.4f"/"%.0f".format(...)` which uses the default locale, rather than `String.format(Locale.US, ...)`.
**Proposed fix:** Pin every export numeric format to `Locale.US`.
**Status:** Fixed (2026-06-07) - cost tables + seconds route through Locale.US formatExportCents/formatExportSeconds; the decimal moderation score uses Locale.US too (the %.0f rerank score has no separator and was already safe)

### Bug 41 — Severity: LOW — Category: markdown converted inside code fences
**Location:** ReportExport.kt:1325-1334 (`convertMarkdownToHtmlForExport`)
**Symptom:** `**bold**`, `*em*`, and `#`/`##`/`###` headings *inside* a fenced code block get converted to `<strong>`/`<em>`/`<h*>` in the HTML export, mangling code samples.
**Root cause:** The code-fence regex wraps fences in `<pre><code>…</code></pre>` first (line 1325), but the subsequent inline/heading `replace` passes operate on the whole string, including the text already inside `<pre>`.
**Proposed fix:** Extract fenced code blocks into placeholders (as is done for tables) before the inline passes, and re-insert them after.
**Status:** Fixed (2026-06-07) — fenced code blocks are now extracted to placeholders before inline markdown conversion and restored afterward

### Bug 42 — Severity: LOW — Category: back-translation dropped from Original tab
**Location:** ReportExport.kt:489-500 (`originalSecondary = nonTranslateSecondary.filter { it.targetLanguage == null }`), 534-541
**Symptom:** A META row generated as a back-translation into the report's own detected language (tagged with `targetLanguage == reportLanguageName`) is excluded from the Original tab of the export and never folded back, unlike the in-app View which folds `reportLanguageName` into Original.
**Root cause:** The export's Original filter is strictly `targetLanguage == null`; it has no `reportLanguageName` fold equivalent to the in-app `buildLangTabs(originalAlias=...)`.
**Proposed fix:** Treat `targetLanguage == reportLanguageName` rows as Original in `buildLanguageViews` (fold them into the Original view) to match the in-app behaviour.
**Status:** Fixed (2026-06-07) — HTML export now carries the report language name and folds matching secondary rows into Original

### Bug 43 — Severity: LOW — Category: false-positive anchor linkify
**Location:** ReportExport.kt:1289-1293 (`linkifyAnchorRefs`)
**Symptom:** Any `[N]` in META/rerank prose where `N` happens to fall in `1..maxAnchor` becomes a hyperlink to a result card, even when it's not a citation (e.g. a list marker or a quoted "[2]").
**Root cause:** The regex blindly linkifies every `[digits]` in range; there's no context check that the bracket is actually a result reference.
**Proposed fix:** Tighten the match (e.g. require the model's documented citation form), or accept it as best-effort and document the limitation.
**Status:** Fixed (2026-06-07) — export anchor linkification now requires citation-like context before `[N]`

## File: ai/src/main/java/com/ai/ui/helpers/MarkdownTables.kt

### Bug 44 — Severity: LOW — Category: escaped-pipe handling in table cells
**Location:** MarkdownTables.kt:75-80 (`splitTableRow`)
**Symptom:** A table cell containing an escaped pipe (`\|`) is split into extra columns, then truncated back to the header width (line 45-49), silently losing the cell content after the escaped pipe.
**Root cause:** `split("|")` doesn't honour `\|` escapes that GFM allows inside cells.
**Proposed fix:** Split on unescaped pipes only (e.g. a regex with a negative-lookbehind for `\`) and unescape `\|` → `|` afterward.
**Status:** Fixed (2026-06-07) — table rows now split only on unescaped pipes and unescape `\|` inside cells

### Bug 45 — Severity: LOW — Category: pipe-less GFM tables not detected
**Location:** MarkdownTables.kt:31 (`isHeader = line.trimStart().startsWith("|") && ...`)
**Symptom:** A valid GFM table whose header row omits the leading/trailing pipe isn't recognised, so it renders as raw text (both in-app and in every export).
**Root cause:** Detection requires the header line to start with `|`; GFM permits tables without outer pipes.
**Proposed fix:** Relax the detector to also accept a header line that contains an inner `|` and is followed by a separator row, even without a leading pipe.
**Status:** Fixed (2026-06-07) — table detection now accepts rows with unescaped inner pipes, so GFM tables without outer pipes are parsed

## File: ai/src/main/java/com/ai/ui/helpers/ReportExportScreen.kt

### Bug 46 — Severity: LOW — Category: misleading control / silent scope mismatch
**Location:** ReportExportScreen.kt:171-193 ("Export all (zip)" passes `exportLanguage`), 99-106 (`exportLanguage`)
**Symptom:** With "One language" selected, tapping "Export all (zip)" produces a zip containing only that single language, despite the Language card's copy describing the all-languages layout ("one top-level directory per language").
**Root cause:** "Export all" forwards the same `exportLanguage` the single "Export" button uses; when `ONE_LANGUAGE` is selected `exportLanguage` is `Single(name)`, so `bulkExportAndShare` falls into its flat single-language layout.
**Proposed fix:** Either force `ExportLanguage.All` for the Export-all button, or update the help text to state Export-all honours the One-language selection.
**Status:** Fixed (2026-06-07) — Export all (zip) now always requests `ExportLanguage.All`, while single export still honors the selected scope

## File: ai/src/main/java/com/ai/ui/helpers/PricingFormat.kt

### Bug 47 — Severity: LOW — Category: locale (comma decimals)
**Location:** PricingFormat.kt:12-13 (`"%.2f".format(p.promptPrice * 1_000_000)`)
**Symptom:** The per-million pricing display ("12,34 / 56,78") uses comma decimals on a comma-decimal device, unlike the sibling `formatTokenPricePerMillion` which pins `Locale.US`.
**Root cause:** `"%.2f".format(...)` uses the default locale.
**Proposed fix:** Use `String.format(Locale.US, "%.2f", ...)`.
**Status:** Fixed (2026-06-07) — per-million pricing display now uses `String.format(Locale.US, ...)`

## File: ai/src/main/java/com/ai/ui/helpers/RerankTable.kt

### Bug 48 — Severity: LOW — Category: locale (comma decimals)
**Location:** RerankTable.kt:46 (`"%.3f".format(score)`), 123 (`"%.${it}f".format(s)`)
**Symptom:** Fractional rerank scores (and the Tournament Davidson scores via `scoreDecimals`) render with commas on a comma-decimal device.
**Root cause:** Default-locale `format`.
**Proposed fix:** Use `String.format(Locale.US, ...)` in `formatRerankScore` and the `scoreDecimals` branch.
**Status:** Fixed (2026-06-07) — rerank and fixed-decimal tournament score formatting now use `Locale.US`

## File: ai/src/main/java/com/ai/ui/helpers/ModerationTable.kt

### Bug 49 — Severity: LOW — Category: locale (comma decimals)
**Location:** ModerationTable.kt:142 (`"%.3f".format(v)`), 253 (`"%.4f".format(it)`)
**Symptom:** Moderation top-scores and the per-category detail scores render with commas on a comma-decimal device.
**Root cause:** Default-locale `format`.
**Proposed fix:** Pin `Locale.US`.
**Status:** Fixed (2026-06-07) — Moderation table top scores and detail scores now format with `Locale.US`

## File: ai/src/main/java/com/ai/ui/helpers/ThinkSectionContent.kt

### Bug 50 — Severity: LOW — Category: renderer divergence (no code handling in-app)
**Location:** ThinkSectionContent.kt:177-192 (`convertMarkdownToSimpleHtml`)
**Symptom:** The in-app content renderer doesn't handle inline code (`` `x` ``) or fenced code blocks at all, so report bodies show literal backticks in-app while the HTML export (`convertMarkdownToHtmlForExport`) renders them as `<code>`/`<pre>`. Same content reads differently in-app vs exported.
**Root cause:** `convertMarkdownToSimpleHtml` omits the code-fence / inline-code passes that the export converter has.
**Proposed fix:** Add inline-code / code-fence handling to the in-app converter (or render code spans monospace in `parseHtmlToAnnotatedString`).
**Status:** Fixed (2026-06-07) — in-app report markdown now extracts fenced and inline code before formatting and renders code spans monospace

### Bug 51 — Severity: LOW — Category: double-unescape of user-typed entities
**Location:** ThinkSectionContent.kt:201-206 (`parseHtmlToAnnotatedString` un-escapes `&amp;`/`&lt;`/`&gt;` before parsing)
**Symptom:** Report text that *literally* contains an HTML entity the user typed (e.g. `&lt;`) is escaped once by `convertMarkdownToSimpleHtml` (`&` → `&amp;` → `&amp;lt;`) and then unescaped here back to `&lt;` and finally to `<`, so a typed `&lt;` is shown as `<`.
**Root cause:** The annotated-string pass reverses the escaping the simple-HTML pass applied, but applies a second `&lt;→<` step that also catches literal entities the user authored.
**Proposed fix:** Carry the already-escaped HTML through without re-unescaping, or do a single faithful unescape pass.
**Status:** Fixed (2026-06-07) — report HTML entities are now decoded once while appending text, so literal user-authored entities stay visible

## File: ai/src/main/java/com/ai/ui/helpers/WordOdtExport.kt

### Bug 52 — Severity: MEDIUM — Category: locale (comma decimals in DOCX/ODT)
**Location:** WordOdtExport.kt:244, 269 (seconds), 285-293 (cost cents), 356 (rerank score)
**Symptom:** The cost tables and scores in exported Word/OpenDocument files render with comma decimals on a comma-decimal device, inconsistent with the in-app figures and unsafe for downstream numeric use.
**Root cause:** Default-locale `"%.2f"/"%.1f"/"%.0f".format(...)`.
**Proposed fix:** Pin `Locale.US` on every numeric format in the DOCX/ODT builders.
**Status:** Fixed (2026-06-07) - cost tables + seconds route through Locale.US formatExportCents/formatExportSeconds; the decimal moderation score uses Locale.US too (the %.0f rerank score has no separator and was already safe)

## File: ai/src/main/java/com/ai/ui/helpers/ZippedHtmlExport.kt

### Bug 53 — Severity: MEDIUM — Category: locale (comma decimals in zipped HTML)
**Location:** ZippedHtmlExport.kt:664, 669-678 (cost table), 1058 (rerank score)
**Symptom:** Same comma-decimal issue as ReportExport.kt, in the zipped-HTML cost table and rerank score.
**Root cause:** Default-locale `format`.
**Proposed fix:** Pin `Locale.US`.
**Status:** Fixed (2026-06-07) - cost tables + seconds route through Locale.US formatExportCents/formatExportSeconds; the decimal moderation score uses Locale.US too (the %.0f rerank score has no separator and was already safe)

## File: ai/src/main/java/com/ai/ui/helpers/BulkExport.kt

### Bug 54 — Severity: LOW — Category: optimistic progress count
**Location:** BulkExport.kt:91 (`total = viewsToRender.size * 9 + 1`), 135-160 (`dropIfEmpty` + `bump()`), 170 (trace bump)
**Symptom:** The progress bar reaches 100% counting artifacts that were dropped: a PDF whose render produced no output is deleted (`dropIfEmpty`) yet its `bump()` still fires, and the trace-bundle leg bumps even when `traceZipBytes` is null (no traces). The "done/total" overstates what's actually in the zip.
**Root cause:** `bump()` is called unconditionally per leg regardless of whether the artifact made it into the bundle.
**Proposed fix:** Bump only on a successfully produced artifact, and compute `total` from the artifacts actually emitted (or label the bar as "steps", not "files").
**Status:** Fixed (2026-06-07) — bulk export progress now bumps only emitted PDF/trace artifacts and shrinks the denominator for skipped optional outputs

## File: ai/src/main/java/com/ai/ui/report/manage/view/ContentDisplay.kt

### Bug 55 — Severity: LOW — Category: locale (comma decimals in manage cost table)
**Location:** ContentDisplay.kt:1339, 1453 (`"+%.2f ¢".format(deletedCents)`)
**Symptom:** The "costs from deleted items" line in the in-app Manage cost tables renders with a comma on a comma-decimal device, while the surrounding cells (via `formatCents`, `Locale.US`) use a period.
**Root cause:** Default-locale `"+%.2f ¢".format(...)`.
**Proposed fix:** Use `String.format(Locale.US, "+%.2f ¢", deletedCents)`.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/report/manage/Tournament.kt

### Bug 56 — Severity: LOW — Category: locale (comma decimals)
**Location:** Tournament.kt:794 (`"Confidence: ${"%.0f".format(it * 100)}%"`)
**Symptom:** The per-match confidence percentage uses default-locale formatting (irrelevant for `%.0f` integers, but the pattern is locale-fragile and inconsistent with the codebase convention).
**Root cause:** Default-locale `"%.0f".format(...)`.
**Proposed fix:** Pin `Locale.US` (harmless now, prevents regression if decimals are ever added).
**Status:** Open

## File: ai/src/main/java/com/ai/ui/report/manage/JudgeEval.kt

### Bug 57 — Severity: LOW — Category: locale (comma decimals)
**Location:** JudgeEval.kt:685 (`"conf ${"%.2f".format(it)}"`)
**Symptom:** The judge-cell confidence renders with a comma on a comma-decimal device.
**Root cause:** Default-locale `"%.2f".format(...)`.
**Proposed fix:** Pin `Locale.US`.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/report/manage/Nav.kt

### Bug 58 — Severity: LOW — Category: overlay back-stack (no LIFO arbiter)
**Location:** Nav.kt:368-400 (four positional `if (openX != null) { …; return@CompositionLocalProvider }` blocks: regen → tournament → judge → compare)
**Symptom:** The four batch overlays use positional early returns with no shared back-stack. If two open-state slots are ever non-null simultaneously (e.g. a Regenerate batch is enqueued while a Tournament overlay is already open), the earlier-positioned overlay shadows the later one, and the user's back press peels the wrong layer — the documented recurring overlay anti-pattern.
**Root cause:** Independent open-state vars with positional `return` precedence rather than a single LIFO dispatcher; nothing prevents two from being set at once.
**Proposed fix:** Route the four overlays through one ordered back-stack (push/pop one per back press) or guard so opening one clears the others.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/report/manage/UserNotes.kt

### Bug 59 — Severity: CRITICAL — Category: LazyColumn duplicate-key crash
**Location:** UserNotes.kt:295-303 (`ReportNotesListScreen` group header `item(key = "h:${group.label}")`), 319-334 (`noteTargetLabel`)
**Symptom:** Opening the 📒 "User notes" screen crashes (Compose throws `IllegalArgumentException: Key "h:…" was already used`) whenever the report has notes on two different targets that resolve to the **same** group label.
**Root cause:** Groups are built per distinct `(targetKind, targetId)`, but `noteTargetLabel` returns non-unique labels for distinct targets — e.g. two RERANK secondaries both → "Rerank", two COMPARE on the same model → "Compare · X", or (very common) two deleted targets both → "Deleted item". Each group emits `item(key = "h:<label>")`, so two same-labelled groups produce duplicate LazyColumn keys, which Compose rejects at composition.
**Reproduction:** Add a note to two different rerank rows (or annotate two secondaries then delete both targets), open the report's User notes screen — it crashes.
**Proposed fix:** Make the header key unique by group identity, not label — e.g. `key = "h:${group.targetKind}:${group.targetId}"` (carry the keying pair on `Group`), or append the group index.
**Status:** Fixed (2026-06-07) — header key now uses group target identity (targetKind:targetId), not the non-unique label

### Bug 60 — Severity: LOW — Category: repeated full-report disk read
**Location:** UserNotes.kt:148-160 (`ViewUserNotes` `produceState(... ReportDataVersion ...)`)
**Symptom:** Every View screen mounts `ViewUserNotes`, which re-reads and re-parses the entire report from disk on each `ReportDataVersion` bump just to fetch the notes for one target.
**Root cause:** `ReportStorage.getReport(...)` (full file + Gson parse) is invoked per target per version bump; there's no narrower notes accessor.
**Proposed fix:** Add a lightweight notes-only read (or cache the parsed report) so each view-screen note strip doesn't reparse the whole report on every change.
**Status:** Open

### Bug 61 — Severity: LOW — Category: collapse state lost on config change
**Location:** UserNotes.kt:89 (`var expanded by remember(note.id) { mutableStateOf(false) }`)
**Symptom:** An expanded note card collapses on rotation / process death because its expanded flag isn't saved.
**Root cause:** `remember` (not `rememberSaveable`).
**Proposed fix:** Use `rememberSaveable(note.id)` for the expanded flag.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/report/manage/view/SecondaryDetail.kt

### Bug 62 — Severity: MEDIUM — Category: stale language tabs (missing version key)
**Location:** SecondaryDetail.kt:96-101 (`translatesState = produceState(... result.reportId)`)
**Symptom:** On a META detail screen, the language icon picker doesn't update when a translation is added or deleted while the screen is open — newly-translated languages don't appear and deleted ones linger.
**Root cause:** `translatesState`'s `produceState` keys only on `result.reportId`, omitting `SecondaryDataVersion.version`. The sibling `resultFresh` (line 116) *does* subscribe to `secDataVersion`, so the body content refreshes but the language tab set goes stale.
**Proposed fix:** Add `SecondaryDataVersion.version` to the `translatesState` key list.
**Status:** Fixed (2026-06-07) — translatesState now keys on SecondaryDataVersion.version (single shared subscription)

### Bug 63 — Severity: LOW — Category: stale trace / report (missing version keys)
**Location:** SecondaryDetail.kt:82-88 (`traceFilenameState` keyed on `result.id`), 106-108 (`parentReportState` keyed on `result.reportId`)
**Symptom:** The 🐞 trace link and the title-bar report icon/language don't refresh if a trace is captured/purged or the report metadata changes while the detail screen stays open.
**Root cause:** Neither `produceState` keys on `ApiTracer`/`ReportDataVersion`.
**Proposed fix:** Key `parentReportState` on `ReportDataVersion.version`; refresh the trace lookup on a trace-data signal (or accept it as load-once and document it).
**Status:** Open

## File: ai/src/main/java/com/ai/ui/report/view/Main.kt (additional)

### Bug 64 — Severity: LOW — Category: tile-order id collision (legacy aggregate)
**Location:** Main.kt:998 (`rowId = sourceRow?.id ?: item.label`), 1013-1014 (`id = "meta:${item.label}:$rowId"`)
**Symptom:** Two Meta tiles for legacy aggregate items (no `sourceRow`) that share a `metaPromptName` produce the same IdentifiedTile id `meta:<label>:<label>`, so the persisted tile-order map can't tell them apart and one can be lost from the saved order.
**Root cause:** The id falls back to `item.label` when `sourceRow` is null, which isn't unique across two items of the same label.
**Proposed fix:** Include an index or a secondary discriminator in the fallback id so legacy aggregate tiles stay distinct.
**Status:** Open

## File: ai/src/main/java/com/ai/ui/report/view/FanIn.kt (additional)

### Bug 65 — Severity: LOW — Category: language re-centre tied to result, not language arg
**Location:** FanIn.kt:150-157 (`centeredFor` re-centre `LaunchedEffect(languages, currentResultId)`)
**Symptom:** If the parent re-opens the same fan-in row (same `currentResultId`) with a *different* requested `language`, the pager doesn't re-centre because `centeredFor` already equals `currentResultId` — the requested launch language is ignored on the second open.
**Root cause:** The one-shot guard keys on `currentResultId` only; a new `language` value for the same result doesn't reset `centeredFor`.
**Proposed fix:** Include `language` in the guard (e.g. `centeredFor != (currentResultId to language)`), matching the intent of "open on the requested language".
**Status:** Open
