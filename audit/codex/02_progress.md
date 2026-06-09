# Progress tracker

This file re-verifies the `codex`-branch audit (`01_findings.md`,
head `5e52f0ccb`) against current `master`, and tracks the work done in
response. Re-verification date: 2026-06-09.

## Corrections to the original audit

- **Finding count is 56, not 55.** The UI section has 14 items
  (`COD-U01`..`COD-U14`), not 13. Domain totals: A=9, R=9, D=11, P=7,
  U=14, T=6.
- **Test files now number 92, not 96** (68 JVM, 24 instrumented) on
  current `master`.
- **`COD-R03` is effectively already satisfied.** Primary generation
  already has a formal shape (`reportGenerationJob` +
  `activeGenerationReportId` + `regenerateJobs` + `regenerateBatchEngine`
  + `isReportGenerating()`). The proposed `StateFlow<Map<String,
  ReportRunState>>` is gold-plating; no action.
- **`COD-R08` fields already exist** on `SecondaryResult`
  (`fanInOf`, `fanOutSourceAgentId`, `metaPromptId`, `targetLanguage`,
  `tournamentRole`, `tournamentJudgeRunId`). The audit mislabeled
  `tournamentRole` as "roles". A parallel `SecondaryLineage` object would
  duplicate existing data; no action unless `COD-U08` is ever built.
- **`COD-A08` was only partially done.** The prior commit dropped the
  fan-out Phase C/E/F comments, but residual `Phase E`/`Phase F` markers
  remained in `Fan.kt` and `Secondary.kt` (finished this session).
- Several findings carry in-code `(audit ...)` markers already
  (e.g. `ApiDispatch.kt` "audit P01", `ReportStorageInstrumentedTest`
  "audit T06"), confirming this is a live work-list.

## Verdict legend

- **DO** — implementing this session (clear value, manageable risk).
- **DONE** — already satisfied before this session.
- **DEFER** — reasonable, but only when already working in that code.
- **PRODUCT** — a feature decision, not a maintenance fix.
- **SKIP** — over-engineering / low payoff / conflicts with project rules.

## Status table

| ID | Verified state (master) | Verdict | Session commit |
|---|---|---|---|
| A01 | Still valid — `AppViewModel` owns ~10 domains | DEFER (carve cheap pieces only) | — |
| A02 | Still valid — imperative `bootstrap()` | SKIP | — |
| A03 | Still valid — 4 static `SettingsHolder` reads | DEFER | — |
| A04 | Still valid — multiple owners, no map | **DO** | done (doc/ownership.md) |
| A05 | Still valid — Settings 3227 / Icon 3096 / ReportVM 2993 / Dashboard 2976 / ReportStorage 2620 | DEFER (opportunistic) | — |
| A06 | Still valid — `createReport` 20 args, `updateAgentStatus` 20, `SettingsScreen` 44 | **DO** (storage fns only) | pending |
| A07 | Still valid — `GlobalScope+NonCancellable` in `onCleared` | **DO** | pending |
| A08 | Partially done — residual `Phase E/F` comments | **DO** (finish) | done |
| A09 | Still valid — no boundary tests | DEFER | — |
| R01 | Partially done — `acquireThrottledPermits` helper, not `runThrottledBatch` | DEFER | — |
| R02 | Partially done — `ReplayTrack` used only in `MetaEditManager` | **DO** | pending |
| R03 | Already done — formal run-state shape exists | DONE | — |
| R04 | Still valid — `IconGenerationManager` 3096 LOC | DEFER | — |
| R05 | Still valid — `rememberReportRuntimeState` holds derived state | DEFER | — |
| R06 | Still valid — broken-work spread across 3 owners | DEFER | — |
| R07 | Still valid — `begin/update/finish/clearBuild` called manually | **DO** | done (runBatchBuild + BatchBuildScope; 2 engines adopted, rest incremental) |
| R08 | Fields already exist on `SecondaryResult` | SKIP | — |
| R09 | Still valid — dispatch+cost+storage combined | DEFER | — |
| D01 | Partially done — `PromptHistoryStore` extracted; ~1124 LOC | **DO** (continue) | pending |
| D02 | Still valid — 7 stores duplicate lock/atomic-write | DEFER | — |
| D03 | Still valid — `ReportDataVersion` global | DEFER | — |
| D04 | Still valid — many specialized mutators | DEFER | — |
| D05 | Still valid — ledger reconciliation coupled across 2 files | **DO** | pending |
| D06 | Still valid — no `ApiCallRecord` | SKIP | — |
| D07 | Still valid — edit targets as screen-level state | DEFER | — |
| D08 | Still valid — ad-hoc safe-id helpers | DEFER | — |
| D09 | Still valid — one round-trip test only | DEFER | — |
| D10 | Still valid — usage stats static under `SettingsPreferences` | **DO** | pending |
| D11 | Still valid — no migration registry | SKIP (conflicts with no-backcompat rule) | — |
| P01 | Still valid — params passed separately | DEFER | — |
| P02 | Still valid — capability checks scattered + `SettingsHolder` fallback | **DO** | pending |
| P03 | Partially done — snapshots exist, strings remain | DEFER | — |
| P04 | Still valid — `Thread.sleep` in `PermitHold.yieldFor` | SKIP (audit's own low priority) | — |
| P05 | Still valid — no `ModelBackend` | SKIP | — |
| P06 | Still valid — no `ResolvedValue<T>` | DEFER | — |
| P07 | Still valid — ~15 string-error sites | DEFER | — |
| U01 | Still valid — 45-variant enum + big `when` | DEFER | — |
| U02 | Still valid — no `ReportWorkflowMode` | SKIP | — |
| U03 | Still valid — storage reads in composables | DEFER | — |
| U04 | Still valid — route `when` blocks | SKIP | — |
| U05 | Still valid — no `RuntimeJob` | PRODUCT | — |
| U06 | Still valid — health data scattered | PRODUCT | — |
| U07 | Still valid — no preset type | PRODUCT | — |
| U08 | Still valid — no lineage graph | SKIP | — |
| U09 | Partially done — `ReportBundle` exports subset | PRODUCT | — |
| U10 | Partially done — separate `FindAlternative*` screens | DEFER | — |
| U11 | Partially done — `ModelAdvisory` exists, no central icon wrapper | **DO** | done (IconActionButton/StatusIcon; bar strip labeled) |
| U12 | Still valid — `produceState` ticks | DEFER | — |
| U13 | Partially done — `ModelAdvisory` only | DEFER | — |
| U14 | Still valid — inline tag parsing in `AppNavHost` | **DO** | done (ExternalAppCommandParser + ExternalReportCommand) |
| T01 | Partially done — single-kind export tests | DEFER | — |
| T02 | Still valid — no parser tests | **DO** | pending |
| T03 | Partially done — component tests, no nav contracts | DEFER | — |
| T04 | Still valid — no boundary tests | DEFER | — |
| T05 | Still valid — no perf tests | SKIP | — |
| T06 | Partially done — idempotent-dup + removed-cost cases | **DO** (broaden) | pending |

## This session — the DO items, one commit each

Order is dependency-driven. Build cadence: compile-verify each commit;
full `assembleDebug` + deploy to both targets + launch once at the end.

1. `COD-A08` — finish removing stale phase comments.
2. `COD-A04` — write the runtime ownership map (`doc/ownership.md`).
3. `COD-U11` — `IconActionButton`/`StatusIcon` accessibility wrapper.
4. `COD-R07` — `BatchBuildScope.run(...)` helper.
5. `COD-U14` — extract `ExternalAppCommandParser` + command objects.
6. `COD-T02` — JVM tests for the external/share parser.
7. `COD-D10` — extract `UsageStatsRecorder` from `SettingsPreferences`.
8. `COD-A07` — route shutdown flush through `UsageStatsRecorder`.
9. `COD-D01` — extract `GeneralSettingsStore` from `SettingsPreferences`.
10. `COD-D05` — extract `ReportCostLedgerService`.
11. `COD-T06` — broaden cost-accounting mutation tests.
12. `COD-P02` — `ModelCapabilityResolver` (value + source/reason).
13. `COD-R02` — migrate replay maps onto `ReplayTrack`.
14. `COD-A06` — `CreateReportRequest` / `AgentStatusPatch` command objects.
