# Regenerate batch + Get info

Two related surfaces on the Manage hub:

1. **Get info** — a read-only status board for a report's
   *metadata* generation jobs (icon, language, title, per-model
   icon / per-model title). Aggregated into the single **info**
   row on Manage.
2. **Regenerate report** — the orchestration engine that re-runs
   *everything* on a report (metadata + agents + every secondary
   result) in a fixed phase order, surviving app restarts and
   pausing on the first error.

This doc owns the **orchestration**. The icon-generation content
(the worker-engine report / per-model icon prompts) is owned by
[report-icons.md](report-icons.md) — linked, not duplicated.

## Get info

The Manage hub collapses the separate metadata-generation jobs
into one **info** row (built in `GenerationPhase.kt`, ~line 778 —
`RowTypeCell("info")` with an `onGetInfo()` click). The info row is
**always the first row** on Manage — it sits above the Regenerate
batch row. Tapping it opens `ReportGetInfoScreen`
(`GetInfo.kt:337`, launched from `Run.kt:618`), help topic
`report_get_info`. The screen is a layer over the Manage hub —
`publishBottomBar = false`, so Manage keeps publishing its own
bottom bar and the screen total surfaces there.

`buildInfoJobs` (`GetInfo.kt:88`) is the single source of truth
for the rows — used by both the Info screen *and* the Manage info
row, so the two never disagree. It's a pure function of the report
plus the relevant gates. Only **enabled** jobs are emitted, and the
report-level jobs are split into five prompt rows emitted in run
order:

| Job type | Gate | Reads | Cost | Done icon |
|---|---|---|---|---|
| `language` | `reportLanguageOn` (own gate, split from icon) | `Report.languageName` | language in/out | `languageIcon` |
| `language-icon` | same `reportLanguageOn` gate; ⏳ until a language name exists | `Report.languageIcon` / `languageIconErrorMessage` | language-icon in/out | `languageIcon` |
| `report-short` | `titleModeAi` + a `workers/report-title-short`/`report-title-long` worker resolves | `Report.title` / `titleErrorMessage` | title in/out | 🏷️ (`MetadataIcons.label`) |
| `report-long` | same title gate | `Report.titleLong` | titleLong in/out | 🏷️ (`MetadataIcons.label`) |
| `report-icon` | `iconGenEnabled` + `workers/report-icon` has a resolvable worker; derived from the long title | `Report.icon` / `iconErrorMessage` | icon in/out | the icon |
| `model-title` | `perModelTitle` | per-`ReportAgent` `modelTitle` / `modelTitleErrorMessage` | model-title in/out | agent icon, else 🏷️ |
| `model-icon` | `perModelIcon` | per-`ReportAgent` `icon` / `iconErrorMessage` | model-icon in/out | the icon |

Row click routing: `report-icon` → icon detail; `language` /
`language-icon` → language detail; `report-short` / `report-long` →
edit-title; `model-icon` → agent-icon detail; `model-title` →
edit-model-title.

Each report-level row also carries a **`*NeverRan` terminal
guard**: a finished report (`completedAt != null`) that recorded no
attempt at all — no icon/title/language, no error, no cost /
duration / `promptUsed` — is treated as "never ran" rather than
left spinning *Queued…* forever. This covers legacy reports and
copies/fan-out-derived reports whose metadata was inherited rather
than generated.

Per-model jobs sit at `InfoJobState.CLOCK` (⏰) until that agent's
own response reaches `SUCCESS`. The model-icon is derived from the
model-title, so when both are on the icon waits for the title. A
per-model icon/title call that **concludes without a result and
without an error** (markers recorded — cost / tokens / duration /
prompt-name — but `icon` / `modelTitle` left null, e.g. an
empty/unparseable model reply) is treated as terminal `DONE` via
`ReportAgent.modelIconAttempted()` / `modelTitleAttempted()`
(`GetInfo.kt:63`, `:71`), so the Manage **info** row doesn't keep
the animated hourglass spinning forever. (Per-model analogue of the
report-level `iconNeverRan` guard.)

`aggregateInfoState` (`GetInfo.kt:309`) drives the Manage row's
status cell: ❌ if any job FAILED, else ⏳ while any job is still
genuinely `pending`, else ✅ (or the report's own icon). A `CLOCK`
left by an **ERRORed** or **STOPPED** model is *not* pending
(`perModelPending`, `GetInfo.kt:261`) — a finished report with one
failed model settles to ✅ rather than spinning forever.

Rows are clickable to their existing detail screens (icon detail,
language detail, edit-title, agent-icon detail, edit-model-title),
layered over the Info overlay. Get info itself does **not** launch
regeneration — it reports status. Regeneration is driven either by
the normal generate flow or the Regenerate batch below.

## Regenerate batch engine

`RegenerateBatchEngine` (`RegenerateBatchEngine.kt:52`, a `class`
with an `internal constructor(appViewModel, reportViewModel)`) is
the authoritative runtime owner of the per-report "Regenerate
report" job. It replaces the legacy one-shot `regenerateReport`
with a phased, app-restart-survivable orchestrator. State lives on
disk via `RegenerateBatchStorage` (one JSON per report); an
in-memory `StateFlow<Map<reportId, RegenerateJob>>` (`_jobs`)
mirrors it for live UI.

The orchestrator is **one coroutine per report**, scoped to
`AppViewModel.viewModelScope` (survives navigating away from
Manage) and tracked in `orchestratorJobs`
(`ConcurrentHashMap<String, Job>`) so a cancel can `.cancel()` it.

Public API: `hasJob`, `hydrate`, `enqueueAndStart`, `restart`,
`cancel`, `cancelJobNow`, `reconcile`, `deleteJob`.

### Phase order (verified)

`RegeneratePhase` (`RegenerateBatch.kt:20`) is a **10-value** enum
in fixed order; the orchestrator walks forward by `ordinal`.
`enqueueAndStart` starts at `RegeneratePhase.values().firstOrNull()`
(not a hardcoded phase, `RegenerateBatchEngine.kt:103`), so
prepending a phase can't silently skip it.

| # | Phase | Re-runs | Dispatcher (`dispatchPhase`) | Row-status source |
|---|---|---|---|---|
| 1 | `TITLE` | report title workers — short **+** long (synthetic row `__report_title__`); runs *before* icon so the icon can derive from the fresh long title | `iconGen.kickOffReportTitleGeneration(…, thenIcon = false)` | `Report.titlePromptUsed` / `titleErrorMessage` |
| 2 | `ICON` | report 🎯 icon (synthetic `__report_icon__`) | `iconGen.kickOffIconGeneration` | `Report.icon` / `iconErrorMessage` |
| 3 | `LANGUAGE` | language detect + language-icon (synthetic `__report_language__`) | `iconGen.kickOffLanguageGeneration` | `Report.languageName` / `languageIcon` / `languageIconErrorMessage` |
| 4 | `AGENTS` | one task per `ReportAgent` (whose provider still resolves) | `forceRegenerateAllAgents` | `ReportAgent.reportStatus` + `responseBody` |
| 5 | `META` | single-call meta + `RERANK` + `MODERATION` (`fanOutSourceAgentId == null && fanInOf == null`; `JUDGES` / `COMPARE` excluded) | `secondary.resumeStaleMetaPlaceholder` (per row) | `SecondaryResult.content` / `errorMessage` |
| 6 | `FAN_OUT` | fan-out per-pair rows (`fanOutSourceAgentId != null`) | `fanOutEngine.resumeStaleRunsForReport(…, resetAttempts = true)` | `SecondaryResult.content` / `errorMessage` |
| 7 | `FAN_IN` | fan-in combined rows (`fanInOf != null`) | `secondary.resumeStaleMetaPlaceholder` (per row) | `SecondaryResult.content` / `errorMessage` |
| 8 | `TRANSLATIONS` | every `TRANSLATE` row | `translation.startMissingTranslations` (per distinct `translationRunId`) | `SecondaryResult.content` / `errorMessage` |
| 9 | `FAN_META` | per-fan-out-pair title **+** icon — one worker call produces both (pairs that previously had a title/icon or an error) | `iconGen.runFanMetaBatch` (per `metaPromptId`) | `icon` present → Success; `iconErrorMessage`/`titleErrorMessage` with no icon → Error |
| 10 | `TOURNAMENT` | tournament per-match rows (`kind == TOURNAMENT && tournamentRole == "MATCH"`) | `tournamentEngine.resumeStaleRunsForReport(…, resetAttempts = true)` | `SecondaryResult.content` / `errorMessage` |

Notes on specific phases:

- **`TITLE` runs first** (before `ICON`) so report-icon generation
  can read the freshly-regenerated long title. The old separate
  per-icon-tier phases are gone — `FAN_META` is one phase because
  the `workers/fan-meta` prompt returns a `title:` / `icon:` reply,
  so a single worker call covers both halves of a pair.
- The **`TOURNAMENT` AGGREGATE** ranking row is *not* a task — it
  makes no API call. The tournament engine recomputes it once the
  match rows settle. `JUDGES` and `COMPARE` cells are owned by
  their own engines (`JudgeEvalEngine` / `CompareEngine`) and are
  deliberately excluded from the regenerate batch (`isMetaPhaseRow`,
  `RegenerateBatchEngine.kt:884`).
- `FAN_OUT` and `TOURNAMENT` pass `resetAttempts = true` so a
  user-initiated regenerate clears the per-session retry counts the
  30 s background sweep may already have maxed out — otherwise the
  pair/match would be terminalized instantly and never re-fire.

`buildTaskList` (`RegenerateBatchEngine.kt:727`) builds the task
set from the report's *current* contents:

- `TITLE` / `ICON` / `LANGUAGE` only when the matching gate is on
  (`reportTitleAiOn()` / `reportIconOn()` / `reportLanguageOn()`),
  the report has a non-blank `prompt`, **and** the matching worker
  prompt (`report-title-short`/`-long`, `report-icon`,
  `report-language-name`) has a resolvable worker — so the engine
  doesn't park on a synthetic row that could never be dispatched.
- `AGENTS` skips any agent whose provider no longer resolves
  (`AppService.findById(agent.provider) == null`), matching the
  same drop in `forceRegenerateAllAgents` — otherwise its row would
  reset to PENDING but never re-fire and hang the phase to the
  30-minute timeout.
- `FAN_META` only for pairs that previously carried an icon / title
  (or an error), so the engine doesn't spin on rows that can never
  land.

### Phase step machine

Per phase, `orchestrate` (`RegenerateBatchEngine.kt:247`) loops:

1. Empty phase → `advanceToNextPhase`, continue.
2. Flip **every** task in the phase to `RUNNING` (not just
   `WAITING` — on a restart the prior `ERROR` task and its
   already-`SUCCESS` siblings must be re-armed too, or
   `awaitPhaseCompletion`, which only transitions `RUNNING` tasks,
   would leave them stuck), set `startedAt`, clear `endedAt` /
   `errorMessage`; then call `resetRowsForPhase` to reset the
   underlying rows on disk (so Manage shows ⏳). All reset helpers
   are the `*KeepingCost` variants
   (`clearReportTitleKeepingCost`, `clearReportIconKeepingCost`,
   `resetAgentToPendingKeepingCost`,
   `clearFanOutTitle/IconStateKeepingCost`, …) — prior spend stays
   and the dispatcher's additive cost write adds the new call's cost
   on top.
3. `dispatchPhase` fires the phase's dispatcher (table above).
4. `awaitPhaseCompletion` (`RegenerateBatchEngine.kt:311`) polls
   disk **every 1500 ms**, flipping each `RUNNING` task to
   `SUCCESS` / `ERROR` / `CANCELLED` from the row's on-disk
   content / errorMessage. A **30-minute** per-phase timeout is a
   safety net (pauses the phase on timeout).
5. Halt on the **first** ERROR row → `pauseOnError`; otherwise once
   every task is terminal, `advanceToNextPhase`.

`readRowStatuses` (`RegenerateBatchEngine.kt:370`) dispatches the
status read per phase; the synthetic TITLE / ICON / LANGUAGE rows
read `Report` fields directly (no persistent row to match), and the
`FAN_META` read treats an icon present as a usable (partial)
success.

### Job + task states

`RegenerateJobStatus` (`RegenerateBatch.kt:85`): `RUNNING`,
`PAUSED_ON_ERROR`, `DONE`, `CANCELLED`.
`RegenerateTaskState` (`RegenerateBatch.kt:69`): `WAITING`,
`RUNNING`, `SUCCESS`, `ERROR`, `CANCELLED`.

### Pause-on-error + background resume

On the first ERROR in a phase the job persists
`status = PAUSED_ON_ERROR` and stamps `pausedOnRowId` (the first
errored row). No further phases fire until a `restart` succeeds.

Resume paths, all idempotent:

- **`restart`** (`RegenerateBatchEngine.kt:123`) — Restart button
  on the detail screen, or the background sweep. For a paused job
  it only resumes when the paused row is **no longer errored** on
  disk (`isRowStillErrored`); otherwise no-op (re-running would
  just hit the same error). `CANCELLED` jobs always restart at
  `currentPhase`. `DONE` and an already-live `RUNNING` orchestrator
  are no-ops.
- **`reconcile`** (`RegenerateBatchEngine.kt:188`) — called every
  30 s from `ReportViewModel.resumeStaleRunsForReport`. DONE /
  CANCELLED → no-op; RUNNING with a dead orchestrator (app kill) →
  revive; RUNNING with a live orchestrator → no-op;
  PAUSED_ON_ERROR with the row now OK → auto-resume; still errored
  → no-op.
- **`cancel`** (`RegenerateBatchEngine.kt:164`) — stops the
  orchestrator; in-flight HTTP calls finish themselves and persist.
  Only the orchestrator's own `finally` decrements
  `activeSecondaryBatches` (Bug 80 — cancelling decremented it too,
  drifting the badge below the real count).
- **`cancelJobNow`** (`RegenerateBatchEngine.kt:157`) — the
  synchronous variant used by `deleteReport`, which must stop the
  orchestrator *before* removing the report dir (the async `cancel`
  returns before its `launch` body runs).

`mutateJob` does an atomic get → mutate → save under
`RegenerateBatchStorage.update`'s single lock (Bug 58), so a
concurrent cancel can't be clobbered by an orchestrator update
built from a stale `RUNNING` snapshot.

`activeSecondaryBatches` on `UiState` is incremented while an
orchestrator is alive (in `startOrchestrator`) and decremented in
`finally`, so the app-wide "work in flight" indicator counts
regenerate batches.

### UI

`RegenerateBatchScreen` (`RegenerateBatch.kt:72`), help topic
`regenerate_batch`, title **"Regenerate report"** / subject
*"Re-run every model on this report"* — a status banner (phase /
counts / "paused on error"), an action row (Cancel when RUNNING,
Restart when PAUSED / CANCELLED), and per-task cards grouped by
phase (phase chip via the `RegeneratePhase.label` map —
`RegenerateBatch.kt:438` — timestamps, duration, error). It is
mounted as a `RegenerateBatchOverlay` (`RegenerateBatch.kt:347`)
wrapping the screen in `LocalNavigateToCurrentReport`. The
bottom-bar 🗑 pops a confirm dialog, then routes through `deleteJob`
(cancels the orchestrator, drops the JSON + memory entry).

`RegenerateBatchManageRow` (`RegenerateBatch.kt:372`) renders the
top-of-list `regenerate` row on Manage, keyed to the **current**
report only (via `LocalCurrentReportIdForSwipe`) so a leftover job
from another report can't surface on an unrelated one. Its label is
`"<done> / <total> · <currentPhase.label / status>"`.

## Storage

```
<filesDir>/regenerate/<reportId>.json   ← RegenerateJob
```

`RegenerateBatchStorage` (`RegenerateBatchStorage.kt:16`) — one
file per report, `ReentrantLock`-guarded, atomic writes, with
`..`-traversal / suspect-id / canonical-containment guards on the
resolved path. `update` is a compound read-modify-write under one
lock acquisition. `listActiveReports`
(`RegenerateBatchStorage.kt:104`) returns the reportId of every
JSON under the dir and feeds the 30 s background sweep. See
[persistent.md](persistent.md).

## Find alternative title / icon

Both are transient fan-outs launched from a metadata detail screen
(model picker → N picks). "Find alternative icons" is documented in
[report-icons.md](report-icons.md). "Find alternative titles" lives
in `IconGenerationManager.kt` (`startReportTitleFanOut` /
`startModelTitleFanOut` / `startPairTitleFanOut` /
`runTitleCandidate`):

- Resolves the `alt/report_title[_long]` (report) or
  `alt/model_title` (per-model **and** per-fan-out-pair) internal
  prompt; dedupes picks by `provider:model`; pre-populates
  `TitleCandidate.Running` rows in `titleFanOutByReport` /
  `titleFanOutByAgent` / `pairTitleFanOutByPair` (in-memory).
- Each pick pre-acquires the per-provider `ProviderThrottle` permit
  (sets `permitPreAcquired` so the interceptor doesn't double-count)
  and runs `analyzeWithAgent` on a synthetic `Agent`, traced under
  the `alt/report_title[_long]` / `alt/model_title` category.
- **Cost-recorded** even though the picked title only fills the
  editor field: when the call returns any tokens it posts to the
  global Usage ledger via `updateUsageStatsAsync(kind = "title")`
  *and* appends an `IconCallRecord` (with `tier = 0`, blank
  `agentId` so agent/pair icon-clearing never sweeps it, and `type`
  = the trace category) via `ReportStorage.appendIconCall`, so the
  spend shows in the report cost table + totals. See
  [costs.md](costs.md).

## Related docs

- [report-icons.md](report-icons.md) — icon-generation content
  (the worker-engine per-report + per-model icon prompts) the
  TITLE / ICON / FAN_META phases drive.
- [secondary-results.md](secondary-results.md) — the meta /
  fan-out / fan-in / tournament results the META → TOURNAMENT
  phases regenerate.
- [costs.md](costs.md) — find-alternative-title / icon cost
  recording (`IconCallRecord`, Usage ledger).
- [translation.md](translation.md) — the TRANSLATIONS phase's
  multi-language fan-out.
