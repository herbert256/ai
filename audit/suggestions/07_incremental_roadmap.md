# Incremental roadmap

This roadmap is intentionally staged. The codebase does not need a rewrite; it
needs a sequence of extractions that preserve behavior while making the next
change easier.

> **Progress (2026-06-08).** Phase 1 **landed** (settings parity, throttle/permit
> tests, dispatch golden tests for request shapes AND streaming SSE, mock-server
> infra). Phase 2 **in progress** (`SettingsPreferences` moved to
> `com.ai.data.preferences`; first sub-store `PromptHistoryStore` extracted;
> `GeneralSettingsStore`/`UsageStatsStore` still to split). Phase 3 **landed**
> (`ReportExecutionPlan` + tests + a read-only preview on the select-models
> screen). Phase 4 **landed** (`BatchEngine` lifecycle primitives + tests; all 5
> `BatchEngine` subclasses — Compare / Tournament / Judges / TransRank / FanOut,
> the grid-of-cells engines — migrated, so R01 is complete. Other batch/run flows
> — Translation, Fan Meta, Regenerate, Model-test, replay sweeps — keep their own
> job maps by shape and are out of R01's scope; see R03 / R12). Also done: `ApiDispatch.kt` split by concern (P01, 1864→963 LOC),
> de-risked by the golden+streaming tests; `ReportStorage` cost-ledger /
> corrupted-JSON instrumented tests (T06), run-verified. See the status table in
> `00_executive_summary.md` for the per-recommendation breakdown and commits.

## Phase 1 - Stabilize contracts with tests

Goal: add tests before moving ownership.

Recommended work:

1. Add `GeneralSettings` save/load parity tests.
2. Add provider dispatch golden tests for one family first, preferably
   OpenAI-compatible chat/responses because it has the most branches.
3. Add `runThrottledBatch` tests around permit release/reacquire and
   cancellation.
4. Add a minimal fake provider runtime for report-section tests.

Exit criteria:

- A settings field addition fails a test if it is not persisted.
- A provider request shape change is visible in a fixture diff.
- The throttle helper has direct tests for its deadlock-prevention contract.

## Phase 2 - Move persistence below UI

Goal: fix package ownership without changing behavior.

Recommended work:

1. Move `SettingsPreferences` into a data/preferences package.
2. Keep a compatibility typealias or facade if call-site churn is too high.
3. Extract `UsageStatsStore` from settings persistence.
4. Extract `PromptHistoryStore`.
5. Add source-boundary tests to prevent new durable stores under `ui`.

Exit criteria:

- UI settings screens only render and send save commands.
- Usage stats can be tested without constructing settings UI classes.
- `AppViewModel` depends on data-layer stores, not UI package persistence.

## Phase 3 - Formalize report execution planning

Goal: make run decisions visible before side effects.

Recommended work:

1. Create `ReportExecutionPlan`.
2. Use it for report primary generation first.
3. Add cost/call-count/provider-cap fields.
4. Add tests for model selection, skipped models, and parameter/system prompt
   resolution.
5. Add a compact confirmation/preview UI for expensive runs.

Exit criteria:

- Report launch can be explained without starting network calls.
- Tests can assert the exact planned calls for a given selection.
- Users can see call count and rough cost before large batches.

## Phase 4 - Extend batch runtime primitives

Goal: reduce repeated job/resume/hydration logic.

Recommended work:

1. Extend `BatchEngine` with job registration and run lifecycle helpers.
2. Migrate one smaller engine first.
3. Add lifecycle tests.
4. Migrate fan-out after the helper shape is proven.
5. Remove stale phase/legacy comments as migration completes.

Exit criteria:

- Engines use the same lifecycle primitives.
- Delete/cancel/resume/restart failed behavior is consistent.
- UI can query common active-job state across engines.

## Phase 5 - Split report view model responsibilities

Goal: make report orchestration composable.

Recommended target classes:

- `ReportPrimaryRunCoordinator`
- `ReportReplayCoordinator`
- `ReportMetadataCoordinator`
- `ReportSecondaryCoordinator`
- `ReportExecutionPlanner`

Keep `ReportViewModel` as a facade while moving behavior behind it.

Exit criteria:

- Primary generation can be tested without mounting report UI.
- Replay flows share a common runner.
- Metadata jobs have a typed runtime model.
- `ReportViewModel` mainly coordinates dependencies.

## Phase 6 - Replace direct UI storage scans with state stores

Goal: UI observes state; repositories perform I/O.

Recommended work:

1. Move knowledge-base listing out of report selection composition.
2. Move local runtime installed-model scans out of dashboard composition.
3. Move report manage derived state into `ReportManageStateStore`.
4. Replace broad ticks with scoped flows where possible.

Exit criteria:

- Composables do not directly call file-backed stores for routine state.
- Refresh behavior is driven by repository invalidation.
- Dashboard polling is limited to visual elapsed-time ticks.

## Phase 7 - Normalize lineage and API call records

Goal: make complex report outputs explainable.

Recommended work:

1. Add normalized `SecondaryLineage` for new secondary rows.
2. Add `ApiCallRecord` or `RunRecord` for new provider calls.
3. Keep old fields for compatibility.
4. Build lineage view and reproducible run bundle on top.

Exit criteria:

- Fan-out/fan-in/translation/compare rows can be traced to source rows.
- Export can include a coherent run graph.
- Users can inspect "why this result exists" from the UI.

## Phase 8 - Product workflow improvements

Goal: convert internal runtime knowledge into user-visible control.

Recommended work:

1. Job center for active/failed work.
2. Model/provider health summaries.
3. Execution plan presets.
4. Secondary lineage visualization.
5. Reproducible bundle export.
6. Explain-disabled/throttled/waiting affordances.

Exit criteria:

- Users can understand active work, waiting reasons, and cost exposure.
- Complex report workflows are reusable through presets.
- Reports are easier to audit, share, and reproduce.

## Suggested priority order

1. D02 settings parity tests.
2. T03 throttle helper tests.
3. P03 provider dispatch golden tests.
4. D01 move/split settings persistence.
5. R02 route primary report calls through `runThrottledBatch`.
6. R01 extend `BatchEngine` lifecycle.
7. R10 report execution plan.
8. U05 execution plan preview.
9. R03 replay runner.
10. D03 guarded JSON store abstraction.

This order front-loads test coverage and then removes repeated runtime
patterns. It should reduce the cost of every later report-section feature.
