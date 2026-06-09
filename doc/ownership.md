# Runtime state ownership map

This is the single-writer reference for the app's mutable runtime state —
the answer to "who is allowed to write this, and where does everyone else
read it from". It exists because several central classes coordinate many
domains at once (`AppViewModel`, `ReportViewModel`), and a refactor that
moves state without first knowing its owner is how mirrors and
double-writes creep in. Consult this before moving any `StateFlow`,
`remember`, or job map between layers.

The rule the table encodes: **exactly one writer per row.** Everyone else
either reads the owner's `StateFlow` or calls a method on the owner. A
"mirror" (a second copy kept in sync by hand) is a smell — the only
sanctioned mirror is [`SettingsHolder`](#settingsholder-the-one-mirror).

## Process-scoped runtime state

These flows live above any single Compose tree so they survive Activity
config changes and screen navigation.

| State | Owner (sole writer) | Where it lives | Read by |
|---|---|---|---|
| App UI settings (`UiState`: general + AI settings, refresh ticks) | `AppViewModel` | `_uiState` ([AppViewModel.kt:38](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt)) | every screen via `uiState` |
| Broken-work badge list | `AppViewModel.setBrokenBatches` | `_brokenBatches` (AppViewModel.kt:58) | top-bar badge, Broken-work screen. Re-published whole each 30 s scan tick — the scan loop ([`SecondaryRunManager.startBackgroundBrokenScan`](../ai/src/main/java/com/ai/viewmodel/SecondaryRunManager.kt)) computes, `AppViewModel` publishes |
| Pending "continue this batch" hand-off | `AppViewModel.requestBatchOpen` / `consumeBatchOpen` | `_pendingBatchOpen` (AppViewModel.kt:71) | Manage screen one-shot consumer |
| Batch build-stage progress | `AppViewModel.beginBuild`/`updateBuild`/`finishBuild`/`clearBuild` | `_batchBuildProgress` (AppViewModel.kt:100) | `BuildStageOverlay`. Engines drive it through the four methods — see the `BatchBuildScope` helper (COD-R07) |
| Throttled / running fan-out + fan-meta pair id sets | `AppViewModel.update*Pairs` | `_throttledFanOutPairs`, `_runningFanMetaPairs` (AppViewModel.kt:87,118) | L1 stats panels. The authoritative per-pair `PairStatus` is the `FanOutEngine` flow; these are the throttle-gate-only overlays |
| Refresh-all in-flight state | `AppViewModel` | `_refreshAllState` (AppViewModel.kt:390) | Refresh-all screen (re-attachable) |
| App-wide restart lock | `AppViewModel.engageRestartLock` | `_restartLockActive` (AppViewModel.kt:398) | `AppNavHost` (greys bars, swallows back) |
| Background broken-scan job handle | `AppViewModel` | `backgroundResumeSweepJob` (AppViewModel.kt:49) | the scan's own cancel-prior guard |

### Report generation runtime

Owned by `ReportViewModel` (a plain wrapper over `AppViewModel`, not an
androidx VM). Primary generation already has a formal shape — this is the
"`COD-R03` is satisfied" state:

| State | Field | Notes |
|---|---|---|
| Primary generation job + its report id | `reportGenerationJob`, `activeGenerationReportId` ([ReportViewModel.kt:184](../ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt)) | the job carries no id; `activeGenerationReportId` is what the broken-work scan reads to tell a live PENDING/RUNNING agent from a process-kill-stranded one |
| Single/all-agent regenerate jobs | `regenerateJobs` (keyed by reportId, ReportViewModel.kt:209) | `deleteReport` cancels by key so terminal writes can't recreate a deleted report's dir |
| Is-this-report-live predicate | `isReportGenerating(reportId)` (ReportViewModel.kt:222) | folds `activeGenerationReportId` + `regenerateJobs` + `regenerateBatchEngine` — the one place to ask |
| Variation replay tracks (temperature / reasoning-effort / web-search / prompt-edit) | per-mode `ReplayTrack` (COD-R02) | each track owns its `StateFlow<Map<String,S>>` + job map; see [ReplayTrack.kt](../ai/src/main/java/com/ai/viewmodel/ReplayTrack.kt) |
| Alt-icon fan-out jobs (report / language / per-agent) | `iconFanOutJobs`, `languageIconFanOutJobs`, `agentIconFanOutJobs` (ReportViewModel.kt:249,251,264) | cancel-prior on re-launch; `deleteReport` prefix-cancels |
| Per-agent streamed results | `_agentResults` (ReportViewModel.kt:281) | separate flow from `UiState` so a per-task completion doesn't re-compare every other field |
| Fan-out / tournament / judges runtime | `fanOutEngine`, `tournamentEngine`, `judgeEvalEngine` (ReportViewModel.kt:287+) | each engine is the sole owner of its own run state; UI subscribes to the engine directly |

The in-flight fan-meta batch job is **not** a `ReportViewModel` field — it
lives in the `FanOutEngine` run-job registry under a namespaced `|meta|`
key, because it's a decorator pass over that engine's pairs.

### Secondary, metadata, and cost state

| State | Owner | Notes |
|---|---|---|
| Secondary results (META / RERANK / MODERATION / TRANSLATE / fan-out rows / judged kinds) | [`SecondaryResultStorage`](../ai/src/main/java/com/ai/data/SecondaryResultStorage.kt) | durable JSON; lineage fields (`fanInOf`, `fanOutSourceAgentId`, `metaPromptId`, `targetLanguage`, `tournamentRole`, `tournamentJudgeRunId`) live on the row itself |
| Report metadata (icon / title / language / per-model title-icon) | [`ReportStorage`](../ai/src/main/java/com/ai/data/ReportStorage.kt) via per-kind update methods | generation orchestrated by [`IconGenerationManager`](../ai/src/main/java/com/ai/viewmodel/IconGenerationManager.kt) |
| Global report-data invalidation tick | `ReportDataVersion` (ReportStorage.kt) | one global `StateFlow` bumped by every report mutation; UI refresh ticks observe it |
| Cost ledger per report | `ReportStorage.reconcileApiCallCostLedger` + usage reconciliation | reconciliation today spans `ReportStorage` and `SettingsPreferences` (`COD-D05` target) |

### Persistence-layer settings + usage

| State | Owner | Notes |
|---|---|---|
| General + AI settings, model lists, prefs keys | [`SettingsPreferences`](../ai/src/main/java/com/ai/data/preferences/SettingsPreferences.kt) | the persistence facade |
| Saved prompt history | `PromptHistoryStore` (already extracted) | own lock + cache |
| Usage stats (token/cost counters, debounced flush) | `SettingsPreferences` static caches today | `COD-D10` extracts a `UsageStatsRecorder`; shutdown flush moves off `AppViewModel.onCleared` (`COD-A07`) |

## UI-derived state (Compose-local)

`rememberReportRuntimeState`
([RuntimeState.kt:101](../ai/src/main/java/com/ai/ui/report/manage/RuntimeState.kt))
recomputes a large bundle of report/manage derived state (secondary
counts/rows, translation + fan-out summaries, totals, icon/title/language
fields, loaded-report fields) inside composition via `remember` +
`LaunchedEffect(currentReportId, iconRefreshTick)`. It **derives** from the
storage owners above; it is not itself an authority. Moving it to a
`ReportManageStateStore` flow is `COD-R05` (deferred — it touches the
relied-upon `remember`/overlay-return pattern, so it's higher risk).

## `SettingsHolder`: the one mirror

[`SettingsHolder.current`](../ai/src/main/java/com/ai/data/SettingsHolder.kt)
is a deliberate static mirror of the latest `aiSettings`, written from a
single `uiState.collect` in `AppViewModel` (AppViewModel.kt:544). It exists
so dispatcher helpers that can't thread `Settings` through their call stack
(e.g. `ApiDispatchBuilders.isReasoningCapableForDispatch`) can still answer
capability questions. It is the **only** sanctioned mirror. New code should
prefer an explicit context/resolver (`COD-A03`, `COD-P02`) and treat the
holder as the fallback, not the primary path.

## How to use this map

- Adding runtime state? Pick one owner from this list (or add a new row).
  Don't keep a second copy elsewhere "for convenience".
- Refactoring an owner? Update this file in the same commit.
- Tempted to mirror a flow into another layer? That's the signal to expose
  the owner's `StateFlow` to the reader instead.
