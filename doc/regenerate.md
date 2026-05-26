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

This doc owns the **orchestration**. The icon-generation chain
content (the per-agent 3-tier prompts) is owned by
[report-icons.md](report-icons.md) — linked, not duplicated.

## Get info

The Manage hub collapses the old separate icon / language / title
rows into one **info** row (`GenerationPhase.kt:1310`). Tapping it
opens `ReportGetInfoScreen` (`GetInfo.kt:211`), help topic
`report_get_info`. The screen is a layer over the Manage hub —
`publishBottomBar = false`, so Manage keeps publishing its own
bottom bar and the screen total surfaces there.

`buildInfoJobs` (`GetInfo.kt:67`) is the single source of truth
for the rows — used by both the Info screen *and* the Manage info
row, so the two never disagree. It's a pure function of the report
plus the relevant gates. Only **enabled** jobs are emitted:

| Job type | Gate | Reads | Done icon |
|---|---|---|---|
| `icon` | `iconGenEnabled` + `icons/main` prompt's agent resolvable | `Report.icon` / `iconErrorMessage` | the icon |
| `language` | `reportLanguageOn` (own gate, split from icon) | `Report.languageName` / `languageIcon` / `languageIconErrorMessage` | `languageIcon` |
| `title` | `titleModeAi` + `info/report_title` prompt's agent resolvable | `Report.titlePromptUsed` / `titleErrorMessage` | 🏷️ |
| `model-title` | `perModelTitle` | per-`ReportAgent` `modelTitle` / `modelTitleErrorMessage` | agent icon, else 🏷️ |
| `model-icon` | `perModelIcon` | per-`ReportAgent` `icon` / `iconErrorMessage` | the icon |

Per-model jobs sit at `InfoJobState.CLOCK` (⏰) until that agent's
own response reaches `SUCCESS`. The model-icon is derived from the
model-title, so when both are on the icon waits for the title.

`aggregateInfoState` (`GetInfo.kt:184`) drives the Manage row's
status cell: ❌ if any job FAILED, else ⏳ while any job is still
genuinely `pending`, else ✅ (or the report's own icon). A `CLOCK`
left by an **ERRORed** model is *not* pending — a finished report
with one failed model settles to ✅ rather than spinning forever.

Rows are clickable to their existing detail screens (icon detail,
language detail, edit-title, agent-icon detail, edit-model-title),
layered over the Info overlay. Get info itself does **not** launch
regeneration — it reports status. Regeneration is driven either by
the normal generate flow or the Regenerate batch below.

## Regenerate batch engine

`RegenerateBatchEngine` (`RegenerateBatchEngine.kt:51`) is the
authoritative runtime owner of the per-report "Regenerate report"
job. It replaces the legacy one-shot `regenerateReport` with a
phased, app-restart-survivable orchestrator. State lives on disk
via `RegenerateBatchStorage` (one JSON per report); an in-memory
`StateFlow<Map<reportId, RegenerateJob>>` mirrors it for live UI.

The orchestrator is **one coroutine per report**, scoped to
`AppViewModel.viewModelScope` (survives navigating away from
Manage) and tracked in `orchestratorJobs` so a cancel can
`.cancel()` it.

### Phase order (verified)

`RegeneratePhase` (`RegenerateBatch.kt:20`) — fixed enum order; the
orchestrator walks forward by `ordinal`. `enqueueAndStart` starts
at `RegeneratePhase.values().first()` (not a hardcoded phase), so
prepending a phase can't silently skip it.

| # | Phase | Re-runs | Dispatcher | Row-status source |
|---|---|---|---|---|
| 1 | `ICON` | report 🎯 icon (synthetic row `__report_icon__`) | `iconGen.kickOffIconGeneration` | `Report.icon` / `iconErrorMessage` |
| 2 | `LANGUAGE` | language detect + language-icon (synthetic `__report_language__`) | `iconGen.kickOffLanguageGeneration` | `Report.languageIcon` / `languageIconErrorMessage` |
| 3 | `AGENTS` | one task per `ReportAgent` | `forceRegenerateAllAgents` | `ReportAgent.reportStatus` + `responseBody` |
| 4 | `META` | single-call meta + RERANK + MODERATION | `secondary.resumeStaleMetaPlaceholder` (per row) | `SecondaryResult.content` / `errorMessage` |
| 5 | `FAN_OUT` | fan-out per-pair rows (`fanOutSourceAgentId != null`) | `secondary.resumeStaleFanOutPairs` (per `metaPromptId`) | `SecondaryResult.content` / `errorMessage` |
| 6 | `FAN_IN` | fan-in combined rows (`fanInOf != null`) | `secondary.resumeStaleMetaPlaceholder` (per row) | `SecondaryResult.content` / `errorMessage` |
| 7 | `TRANSLATIONS` | every TRANSLATE row | `translation.startMissingTranslations` (per `translationRunId`) | `SecondaryResult.content` / `errorMessage` |
| 8 | `FAN_ICONS` | per-fan-out-pair icon chain (pairs that previously had an icon/icon-error) | `iconGen.runFanIconsBatch` (per `metaPromptId`) | `SecondaryResult.icon` / `iconErrorMessage` |
| 9 | `FAN_TITLES` | per-fan-out-pair title (pairs that previously had a title/title-error) | `iconGen.runFanTitlesBatch` (per `metaPromptId`) | `SecondaryResult.title` / `titleErrorMessage` |

`buildTaskList` (`RegenerateBatchEngine.kt:691`) builds the task
set from the report's *current* contents — ICON/LANGUAGE only when
`iconGenEnabled` and the report has a prompt; FAN_ICONS /
FAN_TITLES only for pairs that previously carried an icon / title,
so the engine doesn't spin on rows that can never land.

### Phase step machine

Per phase, `orchestrate` (`RegenerateBatchEngine.kt:236`) loops:

1. Empty phase → `advanceToNextPhase`, continue.
2. Flip every `WAITING` task in the phase to `RUNNING`; call
   `resetRowsForPhase` to reset the underlying row on disk (so
   Manage shows ⏳). All reset helpers are the `*KeepingCost`
   variants — prior spend stays and the dispatcher's additive cost
   write adds the new call's cost on top.
3. `dispatchPhase` fires the phase's dispatcher (table above).
4. `awaitPhaseCompletion` polls disk **every 1500 ms**, flipping
   each `RUNNING` task to `SUCCESS` / `ERROR` from the row's
   on-disk content / errorMessage. A **30-minute** per-phase
   timeout is a safety net.
5. Halt on the **first** ERROR row → `pauseOnError`; otherwise once
   every task is terminal, `advanceToNextPhase`.

`readRowStatuses` (`RegenerateBatchEngine.kt:344`) dispatches the
status read per phase; the synthetic ICON / LANGUAGE rows read
`Report` fields directly (no persistent row to match).

### Job + task states

`RegenerateJobStatus` (`RegenerateBatch.kt:78`): `RUNNING`,
`PAUSED_ON_ERROR`, `DONE`, `CANCELLED`.
`RegenerateTaskState` (`RegenerateBatch.kt:62`): `WAITING`,
`RUNNING`, `SUCCESS`, `ERROR`, `CANCELLED`.

### Pause-on-error + background resume

On the first ERROR in a phase the job persists
`status = PAUSED_ON_ERROR` and stamps `pausedOnRowId` (the first
errored row). No further phases fire until a `restart` succeeds.

Resume paths, all idempotent:

- **`restart`** (`RegenerateBatchEngine.kt:122`) — Restart button
  on the detail screen, or the background sweep. For a paused job
  it only resumes when the paused row is **no longer errored** on
  disk (`isRowStillErrored`); otherwise no-op (re-running would
  just hit the same error). `CANCELLED` jobs always restart at
  `currentPhase`. `DONE` is a no-op.
- **`reconcile`** (`RegenerateBatchEngine.kt:177`) — called every
  30 s from `ReportViewModel.resumeStaleRunsForReport`. DONE /
  CANCELLED → no-op; RUNNING with a dead orchestrator (app kill) →
  revive; RUNNING with a live orchestrator → no-op;
  PAUSED_ON_ERROR with the row now OK → auto-resume; still errored
  → no-op.
- **`cancel`** (`RegenerateBatchEngine.kt:154`) — stops the
  orchestrator; in-flight HTTP calls finish themselves and persist.

`activeSecondaryBatches` on `UiState` is incremented while an
orchestrator is alive (and decremented in `finally`), so the
app-wide "work in flight" indicator counts regenerate batches.

### UI

`RegenerateBatchScreen` (`RegenerateBatch.kt:72`), help topic
`regenerate_batch` — status banner (phase / counts / "waiting on
…"), an action row (Cancel when RUNNING, Restart when PAUSED /
CANCELLED), and per-task cards grouped by phase (phase label,
timestamps, duration, error). The bottom-bar 🗑 routes through
`deleteJob` (cancels the orchestrator, drops the JSON + memory
entry). `RegenerateBatchManageRow` (`RegenerateBatch.kt:356`)
renders the top-of-list `regenerate` row on Manage, keyed to the
current report only.

## Storage

```
<filesDir>/regenerate/<reportId>.json   ← RegenerateJob
```

`RegenerateBatchStorage` (`RegenerateBatchStorage.kt:16`) — one
file per report, `ReentrantLock`-guarded, atomic writes, with
`..`-traversal / suspect-id guards on the resolved path.
`listActiveReports` (`RegenerateBatchStorage.kt:82`) feeds the 30 s
background sweep. See [persistent.md](persistent.md).

## Find alternative title / icon

Both are transient fan-outs launched from a metadata detail screen
(model picker → N picks). "Find alternative icons" is documented in
[report-icons.md](report-icons.md). "Find alternative titles" lives
in `IconGenerationManager.kt:1564` (`startReportTitleFanOut` /
`startModelTitleFanOut` / `runTitleCandidate`):

- Resolves the `info/report_title_alt[_long]` (report) or
  `info/model_title_alt` (per-model) internal prompt; dedupes
  picks by `provider:model`; pre-populates `TitleCandidate.Running`
  rows in `titleFanOutByReport` / `titleFanOutByAgent` (in-memory).
- Each pick pre-acquires the per-provider `ProviderThrottle` permit
  and runs `analyzeWithAgent`, traced under
  `title_report_alt` / `title_model_alt`.
- **Cost-recorded** even though the picked title only fills the
  editor field: posts to the global Usage ledger via
  `updateUsageStatsAsync(kind = "title")` *and* appends an
  `IconCallRecord` (with `tier = 0`, blank `agentId`, `type` =
  the category) via `ReportStorage.appendIconCall`, so the spend
  shows in the report cost table + totals. See [costs.md](costs.md).

## Related docs

- [report-icons.md](report-icons.md) — icon-generation chain
  content (the per-report + 3-tier per-agent icon prompts) the
  ICON / FAN_ICONS phases drive.
- [secondary-results.md](secondary-results.md) — the meta /
  fan-out / fan-in / translate results the META → TRANSLATIONS
  phases regenerate.
- [costs.md](costs.md) — find-alternative-title / icon cost
  recording (`IconCallRecord`, Usage ledger).
- [translation.md](translation.md) — the TRANSLATIONS phase's
  multi-language fan-out.
