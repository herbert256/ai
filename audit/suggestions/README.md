# Source-only suggestions audit

Date: 2026-06-08

Scope: this audit was produced from the source tree only. I intentionally did
not use existing `doc/` files or existing `audit/` files as inputs. The code
paths reviewed were primarily `ai/src/main/java/com/ai`, with test-source
coverage inspected only to understand current verification depth.

This is not a bug list. It is a source-code-based improvement audit: technical
architecture, maintainability, product behavior, functional workflows, testing,
and staged refactoring opportunities.

## Source snapshot

- Kotlin production files inspected: 388.
- Production LOC counted under `ai/src/main/java/com/ai`: 153,177.
- Largest ownership hotspots:
  - `ui/settings/SettingsScreen.kt`: 3,222 LOC.
  - `viewmodel/IconGenerationManager.kt`: 3,077 LOC.
  - `viewmodel/ReportViewModel.kt`: 3,000 LOC.
  - `ui/admin/AiDashboardScreen.kt`: 2,974 LOC.
  - `ui/shared/SharedComponents.kt`: 2,655 LOC.
  - `data/ReportStorage.kt`: 2,620 LOC.
  - `data/ColorUsageData.kt`: 2,499 LOC.
  - `viewmodel/AppViewModel.kt`: 2,050 LOC.
  - `ui/settings/ImportExportScreen.kt`: 2,033 LOC.
  - `viewmodel/FanOutEngine.kt`: 2,031 LOC.
  - `data/ApiDispatch.kt`: 1,864 LOC.
- Test files inspected: 86 Kotlin files, 59 JVM tests and 27 instrumented tests.

## How to read this audit

The recommendation IDs use these prefixes:

- `A`: architecture and ownership.
- `R`: report, secondary-result, and batch runtime.
- `D`: data, persistence, and settings.
- `P`: providers, API dispatch, and local/cloud runtime.
- `U`: UI, navigation, and functional product improvements.
- `T`: tests and quality gates.

Priority is an implementation-order signal, not a severity label:

- `P0`: do first because it reduces future risk or unlocks many later changes.
- `P1`: high-value, should be planned soon.
- `P2`: useful cleanup or product polish after the main seams exist.

## Files

- `00_executive_summary.md`: ranked themes and top recommendations.
- `01_architecture_and_ownership.md`: module boundaries and state ownership.
- `02_report_and_batch_runtime.md`: report generation, secondaries, batch engines.
- `03_data_persistence_and_settings.md`: JSON stores, settings, costs, versions.
- `04_api_provider_runtime.md`: provider dispatch, throttling, local/cloud execution.
- `05_ui_navigation_and_functional.md`: UI structure and product workflow ideas.
- `06_testing_and_quality.md`: test strategy and missing coverage.
- `07_incremental_roadmap.md`: a staged implementation plan.

## Implementation status (2026-06-08)

A first implementation pass landed the audit's actionable, de-risked core. Each
recommendation that was acted on now carries an inline `> **Status …**` marker
(✅ done · ◑ partial · ☐ deferred) under its heading in the detail files, and
`00_executive_summary.md` has the summary table with commit refs.

Shipped: all the tests the audit asked for (parity, throttle/permit, dispatch
golden + streaming SSE, batch lifecycle, execution-plan), plus `ApiDispatch.kt`
split (P01), `SettingsPreferences` moved below UI (A03) + first sub-store
extracted (D01), `ReportExecutionPlan` + preview (R10/U05), and 4 of 5 batch
engines migrated onto the shared base (R01). +~50 unit tests; suite 405/0.

Everything **without** a Status marker is open **by design** — large
opportunistic file splits (A01/A06/U01/R05, the 2k–3k-LOC files), product
features (job center / presets / lineage — U06–U10, R12), or deliberately
skipped/low-value for a single-user app (D12 migration registry, D04 scoped
ticks, T12 perf, R02's deadlock-nuance unify). The remaining "finish for
consistency" items are `FanOutEngine` (last R01 engine) and the
`GeneralSettingsStore` / `UsageStatsStore` splits (D01/D11).

## Overall conclusion

The app has broad feature depth and a lot of hard-won operational safeguards in
the source: explicit throttle ordering, crash reporting, trace/audit capture,
cost-ledger reconciliation, path guards, and many state-flow based runtime
owners. The main improvement opportunity is not "add architecture"; it is to
finish the architecture already emerging in the source.

The strongest direction is:

1. Extract stable domain services from overloaded coordinators.
2. Turn repeated batch/replay patterns into small reusable runtime primitives.
3. Move persistence concerns below UI packages.
4. Replace broad polling and global version ticks with scoped flows.
5. Add contract tests around provider dispatch, settings parity, JSON stores,
   and batch lifecycle state transitions.

The highest payoff area is the report section. It combines model selection,
primary generation, metadata, translation, fan-out, fan-in, ranking, compare,
icon/title generation, traces, costs, and export paths. That surface is working,
but the code now needs stronger internal boundaries so future features do not
keep adding runtime maps, manual refresh ticks, and callback arguments to the
same few files.
