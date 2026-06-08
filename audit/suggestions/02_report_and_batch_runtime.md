# Report and batch runtime suggestions

## R01 - Expand `BatchEngine` into a complete lifecycle base

> **Status (2026-06-08): ✅ done** — base lifecycle primitives + tests added, and
> all 5 `BatchEngine` subclasses migrated onto them: Compare / Tournament /
> Judges / TransRank / FanOut (the grid-of-judged-cells engines).
> (`5ef0a8d3`, `ef60fb4e`, `ea9e0ba2`, `270321ff`, `dd775eae3`)
> Scope note: other batch/run flows keep their own `Job` maps and are NOT
> `BatchEngine` subclasses — they don't share the run-state-of-cells shape:
> Translation (`TranslationRunManager`), Fan Meta / Regenerate / icon-title
> fan-outs (`ReportViewModel`, `IconGenerationManager`), the replay/temperature/
> reasoning/web-search sweeps (covered separately by R03), Model-test
> (`ModelTestEngine`), single-secondary runs (`SecondaryRunManager`). Folding
> those in is out of R01's scope (and partly R03 / R12 territory).

Priority: P0

Evidence:

`BatchEngine` currently owns only the run map and item transition/drop helpers
(`BatchEngine.kt:23`). Concrete engines still own repeated lifecycle pieces:

- `FanOutEngine` owns pair jobs, replay jobs, run jobs, and resume scans
  (`FanOutEngine.kt:120` to `FanOutEngine.kt:139`).
- `ReportViewModel` owns many similar job maps for sweeps, replay, regenerate,
  fan meta, icon fan-out, language icon fan-out, and resume state
  (`ReportViewModel.kt:192` to `ReportViewModel.kt:283`).

Suggestion:

Promote common lifecycle machinery into the base or sibling helpers:

- run job registry
- item job registry
- cancel-by-run and cancel-by-item
- hydrate status
- resume scan dedupe
- retry cap integration
- item terminalization
- common `isActivelyRunning` query

Expected benefit:

Every batch engine then has the same cancellation and resume semantics. This is
especially important for report deletion, screen reopen, process death recovery,
and "restart failed" actions.

First slice:

Migrate only one engine, ideally `CompareEngine` or `TournamentEngine` if it is
smaller than `FanOutEngine`. Keep public behavior unchanged and add lifecycle
tests before moving the next engine.

## R02 - Use `runThrottledBatch` for primary report calls and replay flows

Priority: P0

Evidence:

`runThrottledBatch` centralizes the app's concurrent-batch throttle contract
(`ThrottledBatch.kt:14`, `ThrottledBatch.kt:89`). It documents the canonical
order as `subCap -> global -> per-host` (`ThrottledBatch.kt:21`).

`ReportViewModel.runReportPrimaryCalls` still manually acquires throttled
permits (`ReportViewModel.kt:1779` to `ReportViewModel.kt:1817`) and its
comment describes the order as `global -> report -> per-host`
(`ReportViewModel.kt:1780`, `ReportViewModel.kt:1796`).

Suggestion:

Create a report-primary adapter around `runThrottledBatch`:

- item: `ReportTask`
- host: `providerHost(task.runtimeAgent.provider)`
- sub-cap: `ApiCallCaps.report`
- register: optional active task registry
- body: `executeReportTask` plus enrichment

Then gradually route replay/sweep operations through the same helper where
their concurrency shape matches.

Expected benefit:

There is one throttle-order implementation and one test surface. Comments and
behavior cannot drift apart.

## R03 - Extract a reusable "variation replay" engine

> **Status (2026-06-08): ◑ partial** — extracted `ReplayTrack<S>` (the shared
> state-flow + job-map plumbing each replay mode hand-rolled) + tests, and
> migrated `MetaEditManager` onto it (1 of 3 sites). `ReportViewModel` and
> `FanOutEngine` still hold their own temperature / reasoning / web-search /
> prompt-edit maps. A full per-site dispatch runner (the `ReplayTarget` idea
> below) is not built — only the plumbing is shared so far. (`e73b35fd8`)

Priority: P1

Evidence:

`ReportViewModel` tracks temperature, reasoning effort, web search, and prompt
edit replay states separately (`ReportViewModel.kt:192` to
`ReportViewModel.kt:203`). `FanOutEngine` repeats similar state maps and job
maps (`FanOutEngine.kt:108` to `FanOutEngine.kt:128`) and has specialized
methods such as `startFanOutPromptEditReplay` (`FanOutEngine.kt:923`).

Suggestion:

Introduce a generic replay runner:

```kotlin
interface ReplayTarget {
    val reportId: String
    val itemId: String
    suspend fun loadCallInput(): ReplayCallInput
    suspend fun persistAccepted(result: ReplaySuccess)
}
```

Then provide replay modes:

- temperature candidates
- reasoning effort candidates
- web-search retry
- prompt edit

The runner owns job cancellation, state map updates, error capture, trace
filename capture, and result application.

Expected benefit:

Adding a new "try another response" variant becomes a small replay mode rather
than another set of state classes, job maps, and apply methods.

## R04 - Make primary report generation a formal run state

Priority: P1

Evidence:

`ReportViewModel` tracks a single `reportGenerationJob`,
`activeGenerationReportId`, and `reportRunningInBackground`
(`ReportViewModel.kt:184` to `ReportViewModel.kt:191`). Background reports can
run independently through `submitBackgroundReport` (`ReportViewModel.kt:1836`).

Suggestion:

Represent primary generation as `ReportRunState`:

```kotlin
data class ReportRunState(
    val reportId: String,
    val runId: String,
    val mode: ReportRunMode,
    val total: Int,
    val completed: Int,
    val activeAgents: Set<String>,
    val status: Status
)
```

Expose it as a `StateFlow<Map<String, ReportRunState>>`. The foreground run can
still be selected specially by the UI, but background and foreground runs share
one runtime model.

Expected benefit:

Broken-work detection, progress UI, cancellation, and background report
tracking become easier to reason about.

## R05 - Treat report metadata generation as a typed job group

Priority: P1

Evidence:

The report flow triggers report title, report icon, language generation,
language icon, per-model title, per-model icon, and alternative candidates. The
storage side has separate fields and update methods for many of these
(`ReportStorage.kt:790`, `ReportStorage.kt:844`), and runtime ownership lives
mainly in `IconGenerationManager`.

Suggestion:

Introduce a typed `ReportMetadataJob` model:

- `ReportShortTitle`
- `ReportLongTitle`
- `ReportIcon`
- `LanguageName`
- `LanguageIcon`
- `AgentModelTitle(agentId)`
- `AgentModelIcon(agentId)`
- `AlternativeCandidate(target, candidateId)`

Each job has status, provider/model, prompt id, trace file, cost, and accepted
value. Existing storage fields can remain; this model can be a runtime and test
adapter first.

Expected benefit:

The "Get info" section, icon/title retry, alternative selection, costs, and
audit traces can share one representation.

## R06 - Move report-screen derived state out of Compose local variables

Priority: P1

Evidence:

`rememberReportRuntimeState` owns many local mutable states for secondary
counts, runs, summaries, totals, icon metadata, report fields, and loading state
(`RuntimeState.kt:115` to `RuntimeState.kt:152`).

Suggestion:

Create a `ReportRuntimeRepository` or `ReportManageStateStore` that exposes:

- `StateFlow<ReportManageSnapshot>`
- scoped refresh functions
- storage-version subscriptions
- derived summaries for secondaries, metadata, fan-out, and translations

Compose should collect a snapshot, not own the data reload policy.

Expected benefit:

The UI becomes simpler and state survives composition changes in a more
predictable way. It also makes the report manage screen testable without
mounting Compose.

## R07 - Make "broken work" recovery a first-class domain feature

Priority: P1

Evidence:

`AppViewModel` owns broken batch state (`AppViewModel.kt:58`) and pending batch
open requests (`AppViewModel.kt:71`). `BatchResume` caps re-dispatch attempts
for secondary rows (`ThrottledBatch.kt:310`). `ReportViewModel.isReportGenerating`
is used to distinguish live work from interrupted rows (`ReportViewModel.kt:216`).

Suggestion:

Create `BrokenWorkRepository`:

- scans persisted reports and secondary rows
- knows live runtime owners through small interfaces
- emits `StateFlow<List<BrokenWorkItem>>`
- owns "continue", "mark stopped", "mark failed", and "dismiss" commands

Expected benefit:

Recovery behavior becomes visible and testable. It also stops being spread
between app-level state, report view model queries, secondary manager scans, and
UI one-shot requests.

## R08 - Make batch build stages reusable

Priority: P2

Evidence:

`AppViewModel` exposes a generic build-progress map (`AppViewModel.kt:100`).
`FanOutEngine.startRun` uses a `buildKey` while creating placeholders
(`FanOutEngine.kt:1054`, `FanOutEngine.kt:1103`).

Suggestion:

Move build-stage tracking into a `BatchBuildScope`:

```kotlin
buildProgress.run(key, total, "Building fan-out") {
    for (...) {
        createPlaceholder(...)
        step()
    }
}
```

Expected benefit:

Engines do not have to manually call `beginBuild`, `updateBuild`, and
`finishBuild`. Cancellation and failure can reliably clear the progress entry.

## R09 - Normalize result lineage across secondaries

Priority: P1

Evidence:

Fan-out and fan-in rows rely on fields such as source agent ids, meta prompt
ids, `fanInOf`, language fields, and prompt names. `FanOutEngine.hydrate`
contains best-effort grouping logic that attaches fan-in rows to fan-out runs
(`FanOutEngine.kt:168` to `FanOutEngine.kt:180`).

Suggestion:

Add a normalized lineage model to secondary results:

```kotlin
data class SecondaryLineage(
    val parentResultId: String?,
    val sourceReportId: String,
    val sourceAgentIds: List<String>,
    val promptId: String?,
    val runId: String?,
    val language: String?
)
```

Keep old fields for compatibility but populate the normalized lineage for new
rows. Hydration can then group by `runId` or lineage instead of prompt-name
matching.

Expected benefit:

Report view, export, rerun, delete, and future graph/lineage UI become simpler.

## R10 - Add a report execution plan object

> **Status (2026-06-08): ✅ done** — `ReportExecutionPlan` + pure builder + tests (`c5ee76e2`); consumed by the U05 preview.

Priority: P1

Functional suggestion:

Before launching a report or secondary batch, construct an execution plan:

- number of primary calls
- number of metadata calls
- selected providers/models
- expected secondary calls
- provider caps that will apply
- skipped models and why
- rough cost range when pricing is known

This object can be shown to users before expensive runs and logged with the run.

Technical benefit:

The same object can drive tests. Instead of testing UI selection indirectly,
tests can assert that a given settings/report state produces the expected plan.

## R11 - Split "generate now" from "persist result"

Priority: P2

Evidence:

Several runtime paths combine provider calls, cost calculation, trace capture,
and disk writes in one method. `FanOutEngine` delegates actual HTTP and disk
persistence to `ReportViewModel.executeSecondaryTask` per its class comment
(`FanOutEngine.kt:87`).

Suggestion:

Use a two-step command:

1. `SecondaryCallRunner.run(input): SecondaryCallOutcome`
2. `SecondaryResultWriter.apply(outcome): PersistedChange`

Expected benefit:

The provider call can be tested without writing files, and persistence can be
tested with fixture outcomes. It also creates a natural seam for dry-run
execution plans.

## R12 - Promote active jobs into a unified job center

Priority: P2

Functional suggestion:

Expose all active long-running jobs in one product surface:

- reports
- secondary batches
- translations
- metadata/icon/title generation
- refresh-all/model tests
- knowledge extraction/embedding

Each job should show status, progress, provider/model, elapsed time, throttle
state, cost so far, and available actions such as cancel, continue, or open.

Technical benefit:

This requires each engine to publish the same minimal job-state interface, which
also helps testing and recovery.
