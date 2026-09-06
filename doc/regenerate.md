# Regenerate batch + Get info

Two related surfaces on the Manage hub:

1. **Get info** — a status board for a report's *metadata*
   generation jobs (icon, language, title, per-model icon /
   per-model title), with per-item / errors-only / all-info
   regenerate actions. Aggregated into the single **info** row
   on Manage.
2. **Regenerate report** — the orchestration engine that re-runs
   *everything* on a report (metadata + agents + every secondary
   result) in a fixed phase order, surviving app restarts and
   pausing on the first error.

This doc owns the **orchestration**. The icon-generation content
(the worker-engine report / per-model icon prompts) is owned by
[report-icons.md](report-icons.md) — linked, not duplicated.

## Get info

The Manage hub collapses the separate metadata-generation jobs
into one **info** row (`InfoSummaryRow` in `SummaryRows.kt:80` —
renders `RowTypeCell("info")`; called from `GenerationPhase.kt:806`
with `onClick = onGetInfo`). The info row is
**always the first row** on Manage — it sits above the Regenerate
batch row, which sits above the **second** results row (the three
summary rows render in the order `info → regenerate → second`).
Tapping it opens `ReportGetInfoScreen` (`GetInfo.kt:360`, launched
from `Run.kt:856`), help topic `report_get_info`. The screen is a
layer over the Manage hub — `publishBottomBar = false`, so Manage
keeps publishing its own bottom bar and the screen total surfaces
there.

**Title-tap cycle.** Get-info is one of three report screens the
title bar cycles through (`cycleReportScreens`, `Run.kt:164`):
**Manage → Get-info → second-results → Manage**, unconditionally —
the second-results row/step used to be skipped when the report had
no secondary results, but is now always shown (so the
second-results screen stays one tap away even before anything has
run). The screen passes `onCycleNext = cycleReportScreens` with
`forceTitleClick = true`, so a title tap advances rather than
peeling back (Back / the report icon still peel one layer to
Manage). The **second results** row / screen (`SecondResults.kt`,
`ReportSecondResultsScreen`) is the secondary-result analogue of
Get-info — it collapses every secondary-result row (rerank / meta /
moderation / translate / fan-out / tournament / judges / compare /
rank) into one board. See [secondary-results.md](secondary-results.md).

`buildInfoJobs` (`GetInfo.kt:89`) is the single source of truth
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

`InfoJobState` (`GetInfo.kt:36`) has five values — `CLOCK` (⏰
queued), `RUNNING` (animated hourglass), `FAILED` (❌), `EMPTY` (⊘
terminal-no-result, grey) and `DONE` (the generated icon, else ✅).

Per-model jobs sit at `InfoJobState.CLOCK` (⏰) until that agent's
own response reaches `SUCCESS`. The model-icon is derived from the
model-title, so when both are on the icon waits for the title. A
per-model icon/title call that **concludes without a result and
without an error** (markers recorded — cost / tokens / duration /
prompt-name — but `icon` / `modelTitle` left null, e.g. an
empty/unparseable model reply) is terminal — model-title settles to
`EMPTY` ("· no title"), model-icon to `DONE` — via
`ReportAgent.modelIconAttempted()` / `modelTitleAttempted()`
(`GetInfo.kt:64`, `:72`), so the Manage **info** row doesn't keep
the animated hourglass spinning forever. (Per-model analogue of the
report-level `iconNeverRan` guard.)

**Phantom-RUNNING guard on completed reports.** A finished report
(`report.completedAt != null`) whose per-model job left *no* markers
at all — never ran, e.g. an imported / copied report or per-model
icon/title toggled on after generation — would previously fall to
the `else -> RUNNING` branch and spin the hourglass forever.
`titleStateFor` / the icon-state `when` now short-circuit on
`completedAt != null` to terminal (`EMPTY` for title, `DONE` for
icon) *before* that fallback (`GetInfo.kt:268`, `:312`). During
live generation `completedAt` is null, so a freshly-succeeded model
still correctly shows `RUNNING` while its enrichment call is in
flight. The report-level analogue is `reportPending`
(`GetInfo.kt:110`): a report-level `CLOCK` keeps the aggregate
spinning only while `completedAt == null`; once the report is
finished an unstarted job reads as terminal (still shown as ⏰) and
doesn't pin the Manage row to ⏳.

`aggregateInfoState` (`GetInfo.kt:329`) drives the Manage row's
status cell: ❌ if any job FAILED, else ⏳ while any job is still
genuinely `pending`, else ✅ (or the report's own icon). A `CLOCK`
left by an **ERRORed** or **STOPPED** model is *not* pending
(`perModelPending`, `GetInfo.kt:277`) — a finished report with one
failed model settles to ✅ rather than spinning forever.

Rows are clickable to their existing detail screens (icon detail,
language detail, edit-title, agent-icon detail, edit-model-title),
layered over the Info overlay; the per-item 🔄 on those detail
screens re-runs that single item via
`ReportViewModel.regenerateMetaItem` (titles + language-icon are
`MetaCache`-backed, so the relevant cache entry is evicted first).

Get-info also has two screen-level regenerate entry points, both
scoped to **info jobs only** (the model responses + secondary
results are left untouched, and each new call's cost is *added* on
top of the report's existing spend):

- **Bottom-bar 🔄** (Manage's reload; the confirm dialog it opens
  forks on whether the Get-info layer is up — `Run.kt:740`) →
  `regenerateReportInfo`
  (`ReportViewModel.kt:2024`): re-runs language, title→icon, and
  per-model enrichment for every successful agent. Pops a
  "Regenerate report info?" confirm first.
- **"Restart errors"** button — rendered only when at least one job
  is `FAILED` (`GetInfo.kt:501`) → `restartReportInfoErrors`
  (`ReportViewModel.kt:1981`): clears the error state of *only* the
  errored rows and re-fires just the failed side (title-error
  re-runs title→icon together since the icon derives from the
  title; per-model re-runs only the side — icon or model-title —
  that errored).

**⚠️ warning lights immediately on error.** The Manage hub stays
composed underneath the Get-info / second-results layers, so its
`reportHasError` check (`Main.kt:410` — any `FAILED` info job, any
`FAILED` secondary, or any errored agent) fires a `LaunchedEffect`
that calls `LocalRefreshBrokenWork` to force a Broken-work scan
*now*, surfacing the ⚠️ top-bar badge the instant a red ❌ appears
on Manage / Get-info / second-results instead of waiting out the
30-second background sweep. See [the broken-work
section](#pause-on-error--background-resume) below.

## Regenerate batch engine

`RegenerateBatchEngine` (`RegenerateBatchEngine.kt:53`, a `class`
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
`cancel`, `cancelJobNow`, `reconcile`, `detectBroken`,
`isActivelyRunning`, `deleteJob`.

### Phase order

The 13 phases are **AGENTS → META → FAN_OUT → FAN_IN → TRANSLATIONS →
FAN_META → TOURNAMENT → JUDGES → COMPARE → TRANSRANK → TITLE → ICON → LANGUAGE**.
Primary answers run before optional metadata. Title remains before Icon so the
icon can use the fresh title. Each batch kind uses its own engine for retries;
aggregate rows are recomputed without another model call.

### Phase step machine

A shared work review occurs before existing answers are reset. Each phase arms
only unfinished tasks; successful siblings are preserved on Retry unfinished.
The phase waits until all submitted siblings settle, then pauses on substantive
errors. Metadata errors are recorded without preventing other phases from
finishing. The existing 30-minute phase timeout remains a safety net.

Stop scheduling cancels the orchestrator. Submitted calls may finish, persist
results and incur cost. Retry waits for previously scheduled calls to settle,
refreshes completed rows from disk, and requeues only unfinished work. Stale
background scans remain read-only; they do not silently submit paid calls.

### Job + task states

`RegenerateJobStatus` (`RegenerateBatch.kt:85`): `RUNNING`,
`PAUSED_ON_ERROR`, `DONE`, `CANCELLED`.
`RegenerateTaskState` (`RegenerateBatch.kt:69`): `WAITING`,
`RUNNING`, `SUCCESS`, `ERROR`, `CANCELLED`.

### Pause-on-error + background resume

After submitted siblings settle, a failed substantive phase persists
`PAUSED_ON_ERROR` and `pausedOnRowId`. **Retry unfinished** retries the errored
rows directly and keeps completed siblings. It does not require the error to
be manually cleared first. Optional Title/Icon/Language failures do not pause
remaining work.

`reconcile` supports explicit manual recovery; background `detectBroken` only
reports interrupted or paused work. `cancel` stops scheduling and records
non-terminal tasks as cancelled while in-flight workers can still finish.
`cancelJobNow` is the synchronous deletion helper.

`mutateJob` does an atomic get → mutate → save under
`RegenerateBatchStorage.update`'s single lock (Bug 58), so a
concurrent cancel can't be clobbered by an orchestrator update
built from a stale `RUNNING` snapshot.

`activeSecondaryBatches` on `UiState` is incremented while an
orchestrator is alive (in `startOrchestrator`) and decremented in
`finally`, so the app-wide "work in flight" indicator counts
regenerate batches.

### UI

`RegenerateBatchScreen` (`RegenerateBatch.kt:70`), help topic
`regenerate_batch`, title **"Regenerate report"** / subject
*"Re-run every model on this report"* — a status banner (phase /
counts / "paused on error"), an action row (Stop scheduling when RUNNING,
Retry unfinished when PAUSED / CANCELLED), and per-task cards grouped by
phase (phase chip via the `RegeneratePhase.label` map —
`RegenerateBatch.kt:436` — timestamps, duration, error). It is
mounted as a `RegenerateBatchOverlay` (`RegenerateBatch.kt:345`)
wrapping the screen in `LocalNavigateToCurrentReport`. The
bottom-bar 🗑 pops a confirm dialog, then routes through `deleteJob`
(cancels the orchestrator, drops the JSON + memory entry).

`RegenerateBatchManageRow` (`RegenerateBatch.kt:370`) renders the
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
(`RegenerateBatchStorage.kt:106`) returns the reportId of every
JSON under the dir. See [persistent.md](persistent.md).

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
