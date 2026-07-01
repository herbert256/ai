# Report-section audit — consolidated findings

HEAD `0741168d0`, 2026-07-01. 54 findings: 16 HIGH / 22 MEDIUM / 16 LOW.
“[2×]/[3×]” = independently found by that many agents. “[spot-checked]” =
mechanism re-verified in code by the orchestrator.

---

## A. Regenerate engine & flows

### A1 [HIGH] Edit-Models “Update model list” is a dead end — staged models are never applied [3×]
- **Where:** `viewmodel/ReportViewModel.kt:1877` (`stageModelListForRegenerate`), `viewmodel/RegenerateBatchEngine.kt:857` (`buildTaskList` reads `report.agents` from disk), banner `ui/report/manage/GenerationPhase.kt:630-651`.
- **What:** Edit → Models → change list → “Update model list” shows “Changes pending: models. Tap Regenerate to apply.” Regenerate then ignores the staged list: added models never run (staged rows sit without results), removed models are re-fired, and the banner never clears (`stagedReportModels`/`hasPendingPromptChange`/`hasPendingParametersChange` reset only in `dismissGenericReportsDialog`, :2539).
- **Why:** The old `regenerateReport` consumed `stagedReportModels` (contract from c82949edc) but was switched off in 706497f24 and deleted in 1683849e7; `enqueueAndStart`/`forceRegenerateAllAgents` never read it. Zero non-display consumers remain (grep-verified).
- **Repro:** Finished report → Edit/Models → add a model → Update model list → Regenerate: new row never runs, banner persists.
- **Confidence:** high.

### A2 [HIGH] Regenerate wipes and corrupts Rank-the-translators runs [3×]
- **Where:** `viewmodel/RegenerateBatchEngine.kt:957-967` (`isMetaPhaseRow` excludes TRANSLATE/TOURNAMENT/JUDGES/COMPARE but **not TRANSRANK**), reset :604-608, dispatch :638-642; correct substitution only in `TranslatorRankEngine.kt:401-404`.
- **What:** 🔁 Regenerate on a report with a finished translator-rank: every TRANSRANK score cell **and** the aggregate ranking row are blanked, then re-fired as generic single-meta calls whose `@ORIGINAL@`/`@TRANSLATION@`/`@LANGUAGE_*@` tokens go out unsubstituted — billed garbage replaces real scores; the aggregate row (provider `*transrank`) can’t re-dispatch and hangs the META phase to the 30-minute safety net → PAUSED_ON_ERROR.
- **Why:** The exclusion list was extended kind-by-kind (db408ee5a fixed the same class for FAN_META); TRANSRANK (added Jun 7) was never added.
- **Repro:** Translations → 🏅 Rank the translators → finish → 🔁 Regenerate → open the rank run.
- **Confidence:** high.

### A3 [HIGH] Regenerate: TopRanked-scoped metas silently widen to AllReports [2×]
- **Where:** `RegenerateBatchEngine.kt:324` + :604-608 (all META-phase rows reset to blank placeholders first), :638-642 (concurrent dispatch, no rerank-first ordering); scope decode `SecondaryRunManager.kt:946-958`.
- **What:** After a regenerate, a meta scoped “Top N (per rerank X)” re-runs over ALL successful responses: the rerank’s content was just nulled, so `extractTopRankedIds(null)` → null → AllReports fallback. Row still claims TopRanked; more tokens billed than the scope implies. (The deleted `regenerateReport` cascade deliberately ordered RERANK first; the replacement lost the sequencing.)
- **Repro:** Rerank + TopRanked(3) meta → 🔁 Regenerate → meta now covers all N.
- **Confidence:** high (deterministic: reset precedes decode).

### A4 [MEDIUM] Regenerate Tournament phase silently loses all prior match spend
- **Where:** `RegenerateBatchEngine.kt:604-608`/`:671-676` vs `data/SecondaryResult.kt:1157-1166` (`recordTournamentMatch` **replaces** cost/usage).
- **What:** The engine resets MATCH rows “keeping cost” on the assumption the storage write is additive — but the tournament dispatcher persists via `recordTournamentMatch`, a plain replace. Prior tournament spend vanishes from row + lifetime totals; nothing bumps `costsFromDeletedItems`.
- **Confidence:** high (comment-vs-code contradiction).

### A5 [MEDIUM] Moderation/Rerank re-dispatch drops `alwaysPromptWorkers` — under “Use report models” a resumed Moderation calls /v1/moderations on chat models
- **Where:** `SecondaryRunManager.kt:898` (`resolveBatchSwarm(report, metaPrompt.workers, null)`) vs fresh paths :306/:387; precedence `ReportViewModelHelpers.kt:172-188`. Introduced by 7e828b5ca missing the third call site.
- **What:** With Worker-batches = REPORT_MODELS, Reload on a Moderation row (or the regenerate META phase) picks a report answer model → guaranteed provider 400/404 → regenerate batch pauses. Rerank similarly loses its dedicated chain.
- **Confidence:** high.

### A6 [MEDIUM] “Regenerate report info” is unreachable — the Get-info 🔄 fork is dead code
- **Where:** `ui/report/manage/Run.kt:606` (`onReload` gated `manageLayer`), fork :735-768; `GetInfo.kt:428-434` passes no `onReload`; only caller chain of `regenerateReportInfo` is the dead fork. Orphaned by 8bb25aeeb.
- **What:** doc/regenerate.md’s documented “regenerate all metadata jobs from Get-info” entry point cannot be triggered; only “Restart errors” remains.
- **Confidence:** high.

### A7 [MEDIUM] Rotation stamps queued cells of every LIVE batch as “Interrupted” (spurious pause; Continue double-bills)
- **Where:** `ui/navigation/AppNavHost.kt:48,66-68` (`remember { ReportViewModel(...) }` + immediate `startBackgroundBrokenScan`); `SecondaryRunManager.kt:629-656,721-741` (`finalizeAbandonedLeftovers`, zero grace); per-instance registries `BatchEngine.kt:127-129`.
- **What:** Rotating mid-batch recreates ReportViewModel + engines with empty job registries while the batch coroutines (on `appViewModel.viewModelScope`) keep running. The fresh zero-grace scan sees no in-flight jobs and stamps still-queued placeholders “Interrupted”: ⚠️ badge on a healthy run, regenerate batches flip to PAUSED_ON_ERROR, and Broken-work Continue re-queues cells the surviving coroutines will also dispatch → double billing. Content self-heals when the old dispatch overwrites the stamp.
- **Confidence:** medium-high (all links code-verified; not device-tested).

### A8 [MEDIUM] `preGenParamsActive` predicate drift — preset-only reports replay with different fallbacks than the fresh run (fable C65)
- **Where:** fresh `ReportViewModel.kt:448-449` vs `buildTemperatureSweepTask` :681-682 and `forceRegenerateAllAgents` :2155-2156 (both add `parameterPresetIds.isNotEmpty()`).
- **What:** A report generated with only a parameter preset gets report-model/app-wide fallbacks (e.g. app-wide maxTokens) on the fresh run but NOT on any regenerate/sweep/replay — “replays identically” comments are violated.
- **Confidence:** high.

### A9 [LOW] Dead `cascadeMetasAndTranslations` still carries the un-bumped cost deletes (fable C64, refuted-as-dead)
- **Where:** `ReportViewModel.kt:2222-2293` — zero callers since 706497f24/1683849e7.
- **What:** Housekeeping: delete it; if ever re-wired, its `SecondaryResultStorage.delete` loops without `bumpCostsFromDeletedItems` come back as a live cost bug.

---

## B. Report generation & lifecycle (ReportViewModel)

### B1 [HIGH] Background-continued report contaminates the open report’s results and progress (fable C59, re-verified)
- **Where:** `ReportViewModel.kt:959` (unconditional `_agentResults` publish), :960-964 + :828-832 (progress bumps), deterministic ids `"swarm:provider:model"` :638/:658, hydrate preference :2523.
- **What:** Continue report A in background, open report B: B’s “X/Y complete” counts A’s completions (early complete / overshoot); a shared direct-model row in B flips to A’s fresh response, and re-hydration prefers the foreign in-memory entry. Also a restore race leaves progress stuck at total−1. Only `removeAgentInternal`’s counter is guarded today (:2762).
- **Repro:** Report with direct gpt-4o → back mid-run → open older finished report that also has gpt-4o.
- **Confidence:** high.

### B2 [HIGH] “Call model API again” / Broken-work restart re-runs the agent under the wrong config (fable C60, re-verified)
- **Where:** `ReportViewModel.kt:2572-2696` (`regenerateAgent`); callers `manage/Nav.kt:732`, `ReportRoutes.kt:769`, `DeveloperRoutes.kt:171`.
- **What:** Swarm/direct rows get bare `AgentParameters()`; saved agents get preset-merge only (no system-prompt fold); `baseOverride = state.reportAdvancedParameters` is live UiState (null after reopening). Report system prompt / selectionParams / captured presets are dropped — Broken-work restarts silently answer under a different config than siblings. The correct rebuild (`buildTemperatureSweepTask`) exists in-file and serves all four replay flows.
- **Confidence:** high.

### B3 [MEDIUM] `submitBackgroundReport` jobs untracked — Broken-work flags live runs; delete doesn’t cancel (fable C61)
- **Where:** `ReportViewModel.kt:1803-1845`; `isReportGenerating` :220-223; `BrokenWorkPolicy.kt:68-80` (agents have no grace window); delete path :2315/:2346.
- **What:** Stress-test/background reports appear under Broken work within ~30 s while generating (restart → double dispatch/billing); deleting one cancels nothing — agents keep billing and NonCancellable terminal writes can recreate the deleted report’s dir.
- **Confidence:** high.

### B4 [MEDIUM] Removing an unfinished agent mid-run double-compensates — completion fires one task early (fable C62)
- **Where:** `ReportViewModel.kt:2765-2770` (total decrement) vs :944-957 (`!stillPresent` still bumps progress); `removeAgent` return ignored :2740.
- **Confidence:** high.

### B5 [MEDIUM] Benched-at-dispatch model row spins forever (fable C63)
- **Where:** `ReportViewModel.kt:820-834` returns before the :959 publish; `GenerationPhase.kt:853-860` renders `result == null` as hourglass; re-hydrate only when `agentResults.isEmpty()` (`Nav.kt:196-199`).
- **Confidence:** high.

### B6 [MEDIUM] Removing an agent doesn’t cancel its in-flight single-agent regenerate — spend vanishes
- **Where:** `ReportViewModel.kt:2723-2731` (cancels sweep/replay/icon families) vs `regenerateJobs` keyed per-report only (:207-212); completion write no-ops via `updateAgentStatus` false (`ReportStorage.kt:236`).
- **What:** Regenerate a row, remove it while in flight: the billed call completes and its cost is recorded nowhere on the report. Same for a running force-regenerate touching the removed agent. (Sixth job family missed by f4682ae61.)
- **Confidence:** medium-high.

### B7 [LOW] Per-task AppLog cost uses default locale — `cost=0,00012` on nl-NL (fable C69)
- **Where:** `ReportViewModel.kt:971`. Log-only; fix with `Locale.US`.

---

## C. Batch engines (Tournament / JudgeEval / Compare / TransRank)

### C1 [HIGH] Removing a model/judge mid-batch cancels the ENTIRE batch [spot-checked]
- **Where:** `SecondaryBatchEngine.kt:484` (`victims.forEach { itemJobOf(it.id)?.cancelAndJoin() }`), `JudgeEvalEngine.kt:504`, mechanism `ThrottledBatch.kt:200-218` (registered deferreds are the `async`s inside `coroutineScope{…}.awaitAll()`); same pattern `FanOutEngine.kt:1971`.
- **What:** Cancelling one victim deferred makes `awaitAll` throw CancellationException inside the `coroutineScope`, cancelling every sibling match; `finalizeLeftoverItems` stamps the rest “Interrupted — run stopped before this match finished”. Standings computed from whatever settled; recovery needs Broken-work Continue. Also fires via `registerItemJob`’s supersede-cancel (`BatchEngine.kt:142`) on a double registration.
- **Repro:** ≥4-model use-report-models tournament running → remove one model from the report.
- **Confidence:** high.

### C2 [HIGH] Re-launching Tournament / Judge-the-judges / Compare wedges an undismissable “Preparing…” modal; relaunch over a finished run orphans its rows
- **Where:** `SecondaryBatchEngine.kt:163` (`launchRun` early-returns a live Job without touching the buildKey), `manage/Main.kt:588-597` (`armBuildStage` calls `onBeginBuild` before the engine), :747-768 (non-dismissable overlay; Cancel = delete run); launchers `Run.kt:1128-1136/1166-1173/356-362`; `hydrateNewestRun` :196-208 hides prior runs.
- **What:** (a) Confirming the launcher while a run is live → modal never releases; only escape destroys the in-flight batch. (b) Confirming after a finished run mints a new runId without deleting the old rows: invisible, undeletable, and an old ❌ pins the Manage “second” aggregate forever. Only TransRank guards this (opens the existing run).
- **Confidence:** high.

### C3 [MEDIUM] Report delete never cancels TranslatorRank — the one engine missing from teardown [2×]
- **Where:** `ReportViewModel.kt:2316-2335` (`cancelReportOwnedWorkBeforeDelete`); `translatorRankEngine.cancelAllForReport` has zero call sites repo-wide.
- **What:** Delete mid-rank: judge cells keep firing billed calls against the deleted report; run entry stays in `_runs` for the session; `activeSecondaryBatches` stays elevated. Bounded by the exists-guard for queued cells.
- **Confidence:** high.

### C4 [MEDIUM] “Use report models” removal misses TransRank cells where the removed model is the JUDGE
- **Where:** `TranslatorRankEngine.kt:68-69` (`itemMatchesModel` checks translator side only) vs Tournament/JudgeEval judge-side matches and the explicit Compare fix 18bee902e.
- **What:** The removed model’s judge cells survive and keep shaping `avgScore` rankings.
- **Confidence:** medium.

### C5 [MEDIUM] Resume scan has no is-run-active guard — double dispatch, then the cascade of C1 kills a batch
- **Where:** `SecondaryBatchEngine.kt:330-383` (`resumeStaleRunsForReport`; checks scan-dedupe + `canRedispatch`, never `isRunActive`); triggers `RegenerateBatchEngine.kt:650,675`, `DeveloperRoutes.kt:64-129`.
- **What:** Between `startRun`’s saveAll and job registration every placeholder is “stale”; an overlapping scan re-dispatches them, the duplicate registration cancels one batch (via C1), matches billed twice.
- **Confidence:** medium (window real; triggers are regenerate orchestrator + dev routes).

### C6 [MEDIUM] JudgeEval “swarm changed → rerun?” compares the wrong judge set — spurious offers, re-billing changes nothing
- **Where:** `manage/JudgeEval.kt:185-194/273-277/329-347`; `JudgeEvalEngine.kt:459-463` (`activeJudgeKeys` resolves the swarm) vs :210-228 (judges derive from Tournament MATCH rows; swarm ignored on rerun too).
- **What:** Opening ✏️ and backing out offers a rerun whenever swarm-resolved ≠ tournament-derived judges (normal under REPORT_MODELS); accepting deletes + re-runs with the SAME judges. Editing the swarm never changes the panel, contradicting the dialog.
- **Confidence:** high.

### C7 [MEDIUM] Compare “Redo” rides the screen scope — Back during the delete kills the relaunch, run permanently gone
- **Where:** `manage/Compare.kt:285-293` (`scope.launch { deleteRun().join(); startRun() }` on `rememberCoroutineScope`), empty-state :256-264; siblings use `rerunBatch` on viewModelScope.
- **What:** Redo synchronously drops the run → “No compare run on this report.” shows mid-delete → Back unmounts the scope → `join()` throws → `startRun` never executes.
- **Confidence:** high (mechanism); window is the delete duration, and the empty screen invites the Back.

### C8 [LOW] `deleteRun` and `deleteJudgeFromRun` bypass the `removeLocks` mutex — deleted-items tally can double-count
- **Where:** `SecondaryBatchEngine.kt:228-247` and `JudgeEvalEngine.kt:493-515` vs the mutex :476-479 (51058a9ab serialized only `removeItemsMatching` against itself). Racy; single-user timing.

### C9 [LOW] Tournament L1 offers ⚖️ Judge-the-judges mid-run — partial judge panel
- **Where:** `manage/Tournament.kt:200-204` (no `tournamentDone` gate) vs `Run.kt:1038-1040` (Manage launcher greyed until done).

### C10 [LOW] TransRank hydrate bypasses the preserve-RUNNING merge — live cells re-publish as PENDING; run-only prompt edits dropped mid-run
- **Where:** `TranslatorRankEngine.kt:471-509` vs base merge `SecondaryBatchEngine.kt:213-221`. Transient display/state only.

### C11 [LOW] Deleting the source translation run strands its TransRank run — no cascade, no cancel; Broken-work Continue dead-ends
- **Where:** `TranslationRunManager.kt:901-918` (`deleteTranslationRun` deletes TRANSLATE rows only); `TranslatorRankEngine.scorableItems` :158-178 returns empty → Continue re-queues rows that can never complete.

---

## D. Fan-out & Fan-Meta

### D1 [HIGH] L3 “Delete this pair” silently erases the pair’s spend — the dialog promises the opposite [spot-checked]
- **Where:** `FanOutEngine.kt:1821-1835` (`cancelPair`: delete + drop, **no** `bumpCostsFromDeletedItems`) — all ten sibling delete/rerun paths bump; dialogs `FanL3.kt:670-676`, `FanMetaL3.kt:273-279` say “The API cost stays counted in the report total.”
- **Confidence:** high.

### D2 [HIGH] Fan-out launch dead-ends, then zombie-fires on Back, when “Select scope” ON + “Runtime parameters” OFF [spot-checked]
- **Where:** `manage/Main.kt:935-940` (scope-screen guard list omits `fanOutDirectRunPrompt`), :1002 (setter), consumer :1108-1150 (below the scope block).
- **What:** Continue looks dead (scope screen re-renders and returns before the consumer composes); pressing Back — the cancel gesture — nulls the scope prompt, the consumer mounts, and the N×M batch fires. The trigger is `rememberSaveable`, so leaving the screen fires it on the next visit instead.
- **Confidence:** high.

### D3 [MEDIUM] “Rerun complete” after any re-hydration expands a responder-subset fan-out to ALL models
- **Where:** `FanOutEngine.kt:240` (`responderIds = null, // not persisted; lost across hydration`) + :1842-1863 (`rerunComplete` trusts it; null → all successful agents). Sources survive via per-row scope; responders don’t.
- **What:** 3×11 becomes 12×11 pairs, billed. Derivable from hydrated pairs’ distinct answerers — just not reconstructed.
- **Confidence:** high.

### D4 [MEDIUM] Remove-model-everywhere skips fan-out runs whose prompt was deleted — stale pair rows survive
- **Where:** `FanOutEngine.kt:190-192` (`hydrate`: unresolvable prompt → `continue`), defeating 9e50a961b’s hydrate-before-sweep; all four SecondaryBatchEngine subclasses hydrate deleted-prompt runs read-only via synthetic prompts (audit bugs 4/16/17), FanOutEngine is the only one that still drops them.
- **Confidence:** medium-high.

### D5 [MEDIUM] Pair rerun double-counts the Fan-Meta title/icon spend
- **Where:** `FanOutEngine.kt:1761-1790` (`rerunPairsBlocking`: `clearedCostDelta += fullCost()` incl. icon/title, but the cleared copy keeps the icon/title fields+costs on the live row) → counted in deleted-items AND on the row (`DashboardStats.kt:355-362`).
- **Confidence:** high.

### D6 [MEDIUM] Fan Meta L1 model rows open L2 in the wrong role — list ≠ the row’s counted set; forever-“collecting information” when initiators ≠ responders
- **Where:** `manage/FanMeta.kt:184-187` (opens `L2(ak, "Initiator")`) vs `FanMetaL1.kt:225-236` (rows are answerer-scoped); Initiator filter `FanMetaL2.kt:74-84` matches nothing for a source that isn’t also a responder; empty state renders as eternal loading :137-143.
- **Confidence:** high.

### D7 [MEDIUM] L3 Prev/Next steps in a different order than L2 displays (fan + fan-meta)
- **Where:** `FanL3.kt:158-172` (timestamp order, comment claims “same ordering as L2”) vs `FanL2.kt:151-156` (label order); same pair `FanMetaL3.kt:117-131` vs `FanMetaL2.kt:99-101`. (Translation L2/L3 use the identical comparator — correct.)

### D8 [MEDIUM] Fan-Meta L3 title-bar reload silently wipes and re-runs the RESPONSE, unconfirmed
- **Where:** `FanMetaL3.kt:202` → `rerunPairsBlocking`. Every sibling reload is meta-scoped and/or confirmed; here one tap destroys the pair’s response (title/icon now describe a gone response), no dialog.

### D9 [MEDIUM] Fan-Meta back navigation replaces the drill-in context (the 3×-shipped back-stack class)
- **Where:** `manage/FanMeta.kt:138-147/204-242` — `FanMetaNav.L3` carries no origin; back from L3 always synthesizes a Report-models L2 the user never visited (Workers/L1All paths dropped).

### D10 [MEDIUM] Cost columns change meaning between drill levels — L2/L3 totals don’t match their L1 parent rows
- **Where:** `FanL1.kt:109` (response-only) vs `FanL2.kt:248,345-350`/`FanL3.kt:582-584` (`PairState.totalCost` incl. icon+title); worse on Fan-Meta side (`FanMetaL1.kt:101-102` title+icon-only vs meta-model screens incl. response cost — can be 10× the row).

### D11 [MEDIUM] Fan-meta run-end finalizer stamps unrelated blank pairs “Interrupted”
- **Where:** `IconGenerationManager.kt:2900-2925` (finally-leftover filter lacks the `rowIds`/run scoping of the pending filter :2847-2856).
- **What:** Restarting pair A errors deliberately-cleared pairs B/C; the “not configured — skipping” early return still stamps; stamped errors count as “started” evidence → resume sweep regenerates icons the user deleted.

### D12 [LOW] Rerun paths resolve `@COUNT@` from the full success count, not the run’s scope
- **Where:** `FanOutEngine.kt:1739-1741` vs :1191 and `resolveFanOutSourceCount` :458-473 (used by the replay path). Prompt-text drift on rerun.

### D13 [LOW] `rerunPairsBlocking` traces carry no runId — Fan-out stats and 🐞 run-filtered lists exclude all rerun traffic
- **Where:** `FanOutEngine.kt:1795` (`withTracerTags(reportId, category)` without runId → `RunHttpStats.record` no-ops).

### D14 [LOW] FanL1 rerun dialog falsely warns combined-report follow-ups will be dropped
- **Where:** `FanL1.kt:393` vs `rerunComplete` :1837-1841 (leaves them alone) + re-attach :1150.

### D15 [LOW] FanDrillIn (legacy fallback): rotation kicks the user from L3 back to L2 and resets the role
- **Where:** `manage/view/FanDrillIn.kt:212-218` — reset effect fires on initial composition; the adjacent prompt-id reset :197-207 has exactly the missing guard.

---

## E. Translations

### E1 [MEDIUM] Cancelling a queued translation item still fires the billed call, discards the result, and the item resurrects
- **Where:** `TranslationRunManager.kt:920-930` (`cancelTranslationItem` → `dropItem` only; docstring “no per-item Job” is stale — items ARE registered at :408-413); exists-guard :414-417 passes (placeholder never deleted); save skipped :552-556; placeholder re-appears via :1319-1325, Broken-work re-bills it.
- **Confidence:** high.

### E2 [MEDIUM] “Language missing” translate mints a duplicate row — old ERROR/DONE row stays, run reads broken forever
- **Where:** `TranslationRunManager.kt:1734-1752` (no dedupe on (kind, target, language)); `runTranslationSubset.rowByKindTarget` :1386-1392 (`toMap()` last-wins); the Reports-tile popup (`view/Main.kt:819-835`) enqueues every successful agent.
- **Confidence:** medium-high.

### E3 [MEDIUM] Applying an alternative translation drops the replaced translation’s cost
- **Where:** `TranslationRunManager.kt:758-802` (`applyAltTranslation` overwrites cost/usage, no `bumpCostsFromDeletedItems`) vs the analogous `SecondaryModelSwitchManager.kt:250-252` which bumps.
- **Confidence:** high.

### E4 [MEDIUM] Deleting a translation item from L3 during a live run is a “dead delete” — row stays listed until the run ends
- **Where:** `TranslationL3.kt:339-345` → `deleteSecondaryResult` (never touches `_runs`); the `onCancelItem` branch that would `dropItem` is unreachable (every live item has `persistedRowId`).
- **Confidence:** high.

### E5 [LOW] Resume/restart/continue translation dispatches never `registerRunJob` — report delete can’t cancel them
- **Where:** `TranslationRunManager.kt` — `startMissingTranslations`/`restartTranslationRowsMatching`/`restartAllTranslations`/`continueBrokenTranslation`/`addCrossTranslationItems`; e5f538c95 fixed exactly this for `translateMissingItems` only. Orphan billed calls, bounded by exists-guards.

### E6 [LOW] Translation-run 🏅 ignores the “Runtime parameters” toggle (list-medal shows the editor, run-screen medal doesn’t)
- **Where:** `manage/Main.kt:1589-1595` vs `Run.kt:262-280`.

---

## F. Icons / titles / alt-picks

### F1 [HIGH] Backing out of the Find-icons model picker leaks target flags — later alt-picks write to the WRONG scope (language icon / report title hijack)
- **Where:** `manage/IconFlowOverlays.kt:234-241` (picker back doesn’t clear `targetLanguageIcon`), :155-169 (navigate-home doesn’t clear `findTitlesFor`/`findTitlesLong`); precedence dispatch `IconOverlays.kt:188-196`, router :259-293.
- **What:** After abandoning a language-icon flow, every later “Find alternative icons” (agent/pair/meta) runs the language flow and the picked emoji lands on `Report.languageIcon`; the navigate-home exit leaves `findTitlesFor="report"`, so a later pick silently overwrites the persisted report title. Flags are `rememberSaveable` — the leak survives process death.
- **Confidence:** high (flag writes/reads exhaustively enumerated).

### F2 [MEDIUM] `onOpenPicker` replaces the Icon-lookup detail instead of layering (the forbidden `showA=false; showB=true`)
- **Where:** `IconFlowOverlays.kt:344-347` — report/language scope only; the five sibling scopes layer correctly. Back from the picker skips the detail; this state-drop is what orphans `targetLanguageIcon` (F1).

### F3 [MEDIUM] Stale-trace siblings of 78827af09 — four pick/apply writers keep pointing 🐞 at the superseded call [3×]
- **Where:** `data/ReportStorage.kt:1413` (`setReportAgentIconChoice` keeps `iconTraceFile`), :1249 (`setReportLanguageChoice` keeps `languageIconTraceFile` + stale `languageIconRawResponse` renders a mixed transcript), :837 (`setReportModelTitleAltChoice` keeps `modelTitleTraceFile`), `viewmodel/MetaEditManager.kt:238-247` (sweep apply keeps the row’s old `traceFile` although the candidate carries its own).
- **Confidence:** high.

### F4 [MEDIUM] Multi-model title fan-out: picked title attributed to the last-finishing model [2×]
- **Where:** `IconGenerationManager.kt:1736-1741` (each candidate stamps `titleModel` on completion) + :1784-1799 (`pickPairTitleAlternative` calls `setFanOutTitle` without `model`); pick site has `picked.provider/model` available.
- **Confidence:** high.

### F5 [MEDIUM] Pair-title flow shows 🌡️/🎭 per-launch pickers but silently discards the selection
- **Where:** `IconOverlays.kt:199-208` (3-arg `onStartPairTitleFanOut` drops `pIds`/`spId`), `Nav.kt:571-573`; `startPairTitleFanOut` fully supports both (`IconGenerationManager.kt:1685-1724`).
- **Confidence:** high.

### F6 [LOW] Agent Icon-lookup 🐞 resolves to nothing — or another agent’s call — after alt-picks
- **Where:** `IconOverlays.kt:60-77` (`rememberAgentIconTrace` matches `tf.model == agent.model`; alt calls ran on the user-picked model; `ReportAgent` stores no iconModel).

### F7 [LOW] Fan-outs pre-populate ⏳ rows before the target disk lookup — a vanished target leaves permanent spinners
- **Where:** `IconGenerationManager.kt:2548-2553/1518-1523/1697-1702/2272-2275` (early `return@launch` skips settling the pre-inserted Running rows).

### F8 [LOW] Meta-row alt-icon spend attributed to the FIRST row matching the prompt, not the row the flow was opened from
- **Where:** `IconGenerationManager.kt:1302-1309`; `Nav.kt:590-597` never passes `metaRowIdForPromptIcon`. Emoji lands right; money lands wrong (dup-prompt reports).

### F9 [LOW] Get-info icon/title spinner keys lack try/finally — an exception strands ⏳ until restart
- **Where:** `IconGenerationManager.kt:397/458` and :613/670 vs the language flow’s try/finally :990-1123.

---

## G. Manage UI (core screens, detail screens, secondary lists)

### G1 [HIGH] ReportsViewerScreen renders a blank screen when no section is set [spot-checked]
- **Where:** `manage/view/ContentDisplay.kt:457-476` — `ReportsViewerScreenLoaded` ends after the one-page block; `showOnePage=false` / `initialSection == null` compose nothing. Regression from 9e3da4965 (deleted the default section body).
- **What:** Back from “View in one page” → black screen (second Back needed); external report instructions with `nextAction="view"` open straight onto the blank screen; `ManageJump.ReportsViewer(null-agent)` same.
- **Confidence:** high.

### G2 [HIGH] Rerank/Moderation detail tables misattribute rows after agent removal or a failed→success regenerate
- **Where:** `RerankDetailScreen.kt:100-111`, `ModerationDetailScreen.kt:102-123`, `SecondaryDetail.kt:270-291` (rebuild the `[N]`→model map from the CURRENT success set) vs `data/SecondaryResult.kt:1402-1416` (ids frozen at run time); `removeAgent` doesn’t invalidate secondaries.
- **What:** Rank/flag rows silently labeled with the wrong model; moderation “exact moderated text” shows the wrong agent’s content.
- **Confidence:** high.

### G3 [MEDIUM] InternalPromptSaver drops `parameters`/`systemPrompt` (+provider/model/workers/modelSelection) — mid-flow Help hop / rotation strips the prompt’s config (fable C49) [2×]
- **Where:** `manage/Savers.kt:88-105` (7 of 13 fields); six mid-flow states `State.kt:291-300`; restored object passed verbatim into `runMetaPrompt`/`FanOutEngine` where `prompt.parameters`/`systemPrompt` are precedence level 2. The fan-out REPLAY path re-fetches by id (correct pattern).
- **Repro:** Meta prompt with a temp-0 preset → pick → rotate on the Scope screen → Continue → Run: trace shows no preset.
- **Confidence:** high.

### G4 [MEDIUM] Abandoning Find-alternative-translation leaks `pickerTarget=TRANSLATION` — later +Add buttons on Edit-models silently deposit into an invisible list
- **Where:** set `manage/Main.kt:1579`; abandon paths :1627/:1635/:1659/:1692 never reset (launch paths do); consumer `start/SelectionOverlays.kt:136-141`; +Add sites Main.kt:1858-1863 unguarded. `rememberSaveable` → survives process death.
- **Confidence:** high.

### G5 [MEDIUM] MetaDetail sweep screens: “Use this response” leaves a permanently-“Running…” screen
- **Where:** `MetaDetailScreen.kt:407/423/440` (apply without clear+close) vs the correct agent-side triple `SingleResult.kt:211-266`; `MetaEditManager` drops the track key and the screens’ optimistic fallbacks synthesize `isRunning=true` forever.
- **Confidence:** high.

### G6 [MEDIUM] Standalone “Report model” route: “Active language only” delete and translation-compare 🗑 silently no-op
- **Where:** `ReportRoutes.kt:758-830` never passes `onDeleteRowById`; default no-op `SingleResult.kt:72`; consumed :404-407/:837-844. Screen closes as if deleted; row remains.
- **Confidence:** high.

### G7 [MEDIUM] Deleting a Meta row races its own refresh — deleted row stays visible and re-openable
- **Where:** `manage/Meta.kt:70-79/106-127/199` (tick bumped before the async delete lands); `RuntimeState.kt:411-414`; `deleteSecondaryResult` is a launched coroutine; no `SecondaryDataVersion` observation, poll only while running.
- **Confidence:** medium (intermittent by nature).

### G8 [MEDIUM] Rerank/Meta detail 🐞 trace still resolves via the pre-switch model (the staleness ec9aef351 said it fixed) [2×]
- **Where:** `RerankDetailScreen.kt:68-75`, `MetaDetailScreen.kt:82-89` — trace produceState filters on the stale `result.model` and is keyed only on `result.id` (not `secDataVersion`/`eff.model`).

### G9 [LOW] SecondResults “· language” suffix is dead — upstream list strips TRANSLATE rows
- **Where:** `manage/SecondResults.kt:327-331` vs `RuntimeState.kt:292-293`.

### G10 [LOW] AltTranslateTargetSaver parcels the full source text — TransactionTooLargeException risk
- **Where:** `manage/Savers.kt:135-156` (saves `sourceText` = whole response body) into saved-instance state; only ids are needed (`persistedRowId` already saved).

---

## H. View mode (read-only pager screens)

### H1 [HIGH] Value view (and its HTML export) maps Tournament scores to the wrong models when the success set drifts from the participant set
- **Where:** `view/ValueView.kt:179-187` (`rowsById[idx+1]` over the CURRENT success filter) vs participant numbering used by `TournamentEngine.kt:380-393`, `TournamentRanking.kt:590-600`, `TournamentPodium.kt:326-344`; feeds :216-225 (Tournament Total), :275-283 (Combined), `ValueViewExport.kt:38-41`.
- **What:** Add a model post-tournament / regenerate a failed agent to SUCCESS → wrong dots, wrong Pareto frontier, wrong 💎 best-value; trailing models drop off. doc/value-view.md promises “a score always lines up with the right agent”. (Rerank chip shares the drift by data-format necessity; Tournament has the correct mapping available.)
- **Confidence:** high.

### H2 [MEDIUM] Wrap pagers created before their data loads anchor at absolute page 0 — backward wrap broken, arbitrary landing after report swipe
- **Where:** `view/Agent.kt:234`, `Fan.kt:202/224`, `FanPair.kt:109`; contract `helpers/WrapPager.kt:20-33`; composed while produceState still holds empty data, re-centre effects all conditional. The language pagers on the same screens received exactly this fix (`Meta.kt:150-164`, `FanIn.kt:148-161`, `Prompt.kt:134-142`, `Agent.kt:202-213`).
- **Confidence:** high.

### H3 [LOW] “Model reports”/“Prompt” leak the previous report’s language tabs onto a translation-less report after title-bar swipe
- **Where:** `view/Agent.kt:188-197`, `Prompt.kt:116-125` (empty `translatedByLang` falls back to the mount-time prop; can’t distinguish loading from none).

### H4 [LOW] View hub’s Icons tile ignores `perModelIconGenEnabled` — parameter dead
- **Where:** `view/Main.kt:962` (tile unconditional; param only a remember key :917).

---

## I. New-report flow, import/export, storage

### I1 [HIGH] NewReportScreen state is plain `remember` — a Help/trace round-trip wipes prompt, attached image, moderation pick and the flagged dialog
- **Where:** `start/NewReportScreen.kt:69-121`; flagged 🐞 link :385-389; share-target fields drained at :87-105 (can’t re-stage). ChatScreens ships the exact savers as the intended pattern (:405-421).
- **What:** After ❓ or the flagged-prompt 🐞: prompt reverts to the previous report’s prefs value, image + KB banner gone, 🚩 pick reset, Proceed/Cancel dialog gone — re-tapping Next sends unvalidated and image-less.
- **Confidence:** high.

### I2 [MEDIUM] Report Copy drops `workerConfig` — 👷 routing silently reverts to defaults on the duplicate (fable C33) [2×]
- **Where:** `data/ReportStorage.kt:2695-2737` (`copyReport` passes every other captured-config field with replay-fidelity comments; `workerConfig` missing → `ReportWorkerConfig()` default); `createReport` threads it (:174); export/import round-trip preserves it (contrast). `promptHistory`/`userNotes` also dropped, undocumented.
- **Confidence:** high.

### I3 [MEDIUM] Import falsely toasts “N agents missing” for every direct-model/swarm row
- **Where:** `data/ReportBundle.kt:118-133` — compares `agentId` against saved-Agent UUIDs; swarm rows carry synthetic `"swarm:provider:model"` ids that never match; regenerate doesn’t need an Agent for them anyway.
- **Confidence:** high.

### I4 [MEDIUM] Import doesn’t re-mint `runId` — original + imported reports share one trace-run key; 🐞 run lists mix both
- **Where:** `ReportBundle.kt:325` (traces re-saved with old runId), :370-408 (report + secondaries keep `runId`/`iconRunId`/`titleRunId`); the import re-mints `translationRunId` for exactly this globally-keyed reason (:299-304).
- **Confidence:** medium-high.

### I5 [MEDIUM] ModelSelection’s 🌡️/🎭 per-launch picks are plain `remember` — a Model-Info hop mid-flow silently resets them
- **Where:** `start/ModelSelection.kt:59-62`; every selected-model row is `modelInfoClickable` (:145); the hoisted model list survives via `ReportModelListSaver` (Savers.kt:158-166 documents exactly this hop) — the picks don’t. Same in `ui/other/Selection.kt:196-197`.
- **Confidence:** high.

### I6 [LOW] +Report picker: inactive-provider rows vanish silently when the agent exists, but are added when the agent was deleted
- **Where:** `start/SelectionOverlays.kt:74-81` (`expandAgentToModel` nulls on inactive provider; deleted-agent fallback does no active check) — one branch is wrong whichever intent holds.

### I7 [LOW] NewReportScreen composes the moderation-picker overlay after the base content — taps in dead zones fall through
- **Where:** `start/NewReportScreen.kt:358-371` (convention places the overlay check before host content; ChatScreens shares the flaw).

### I8 [LOW] Report info flashes “Report not found.” while loading
- **Where:** `info/ReportInfoScreen.kt:72-98` (null = both loading and missing).

### I9 [LOW] Import progress counter ends short when a bundled secondary is malformed (fable C32)
- **Where:** `ReportBundle.kt:243-245` vs :286-293/:388-389 (total counts raw entries; tick only for parsed). Cosmetic; placeholder always cleans up via finally.

### I10 [LOW] `SecondaryDataVersion` global `_version` flow is never ticked — AiStatReportsScreen’s key is dead (fable C37)
- **Where:** `data/SecondaryDataVersion.kt:41-74`; consumer `AiDashboardScreen.kt:776/783`. Stale mid-batch stats only; self-heals at batch boundaries.

### I11 [LOW] Legacy (non-ledger) cost fallback subtracts pair-title alt spend from the wrong row
- **Where:** `manage/view/ContentDisplay.kt:677-682/908-918/968-981` vs bump target `IconGenerationManager.kt:1735-1758` (`titleInputCost`, not `inputCost`). Only reachable on non-ledger-current reports.

---

## Salvaged fable candidates — resolution

| Candidate | Verdict | Note |
|---|---|---|
| 32 ReportBundle counter | CONFIRMED (LOW) | → I9 |
| 33 copyReport workerConfig | CONFIRMED (MEDIUM) | → I2 |
| 34 token merge truncation | FIXED | 0ad4838e9 |
| 35 deleteAllReports audit trailer | REFUTED | sole caller wipes audit dir |
| 37 SecondaryDataVersion global | CONFIRMED (LOW) | → I10 |
| 38 swallowed atomic-write failures | FIXED | 81bfa58a7 |
| 49 InternalPromptSaver | CONFIRMED (MEDIUM) | → G3 |
| 59 cross-report contamination | CONFIRMED (HIGH) | → B1 |
| 60 regenerateAgent config drift | CONFIRMED (HIGH) | → B2 |
| 61 background jobs untracked | CONFIRMED (MEDIUM) | → B3 |
| 62 remove-agent double compensation | CONFIRMED (MEDIUM) | → B4 |
| 63 benched eternal hourglass | CONFIRMED (MEDIUM) | → B5 |
| 64 cascade cost loss | REFUTED (dead code) | → A9 housekeeping |
| 65 preGenParamsActive drift | CONFIRMED (MEDIUM) | → A8 |
| 66 stale rerank for TopRanked | MOVED | → A3 (mutated symptom) |
| 67 orphaned TRANSLATE rows | REFUTED (path deleted) | successor regression → A1 |
| 68 replay cancel on delete | FIXED | 3de6ee831 |
| 69 AppLog locale | CONFIRMED (LOW) | → B7 |
