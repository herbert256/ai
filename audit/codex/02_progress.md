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
| A06 | Still valid — `createReport` 20 args, `updateAgentStatus` 20, `SettingsScreen` 44 | **DO** (storage fns only) | done (CreateReportConfig + AgentStatusPatch; SettingsScreen 44-arg left as DEFER) |
| A07 | Still valid — `GlobalScope+NonCancellable` in `onCleared` | DEFER (rides on D10) | deferred — pairs with the UsageStatsRecorder extraction |
| A08 | Partially done — residual `Phase E/F` comments | **DO** (finish) | done |
| A09 | Still valid — no boundary tests | DEFER | — |
| R01 | Partially done — `acquireThrottledPermits` helper, not `runThrottledBatch` | DEFER | — |
| R02 | Partially done — `ReplayTrack` used only in `MetaEditManager` | **DO** | done (ReportViewModel's 4 flows migrated; FanOutEngine's parallel set is follow-up) |
| R03 | Already done — formal run-state shape exists | DONE | — |
| R04 | Still valid — `IconGenerationManager` 3096 LOC | DEFER | — |
| R05 | Still valid — `rememberReportRuntimeState` holds derived state | DEFER | — |
| R06 | Still valid — broken-work spread across 3 owners | DEFER | — |
| R07 | Still valid — `begin/update/finish/clearBuild` called manually | **DO** | done (runBatchBuild + BatchBuildScope; 2 engines adopted, rest incremental) |
| R08 | Fields already exist on `SecondaryResult` | SKIP | — |
| R09 | Still valid — dispatch+cost+storage combined | DEFER | — |
| D01 | Partially done — `PromptHistoryStore` extracted; ~1124 LOC | DEFER (re-estimated LARGE) | deferred — same SettingsPreferences cost-accounting cluster as D10 |
| D02 | Still valid — 7 stores duplicate lock/atomic-write | DEFER | — |
| D03 | **DONE** — secondary side via master's `SecondaryDataVersion.versionFor`; report side finished this session (`ReportDataVersion.versionFor(reportId)` + scoped bumps at saveReport/deleteReport; all 28 consumers migrated) | ✅ DO | done |
| D04 | Still valid — many specialized mutators | DEFER | — |
| D05 | Still valid — ledger reconciliation coupled across 2 files | DEFER (re-estimated LARGE) | deferred — `reconcileReportCostLedgers` is the seam D10 shares; do together |
| D06 | Still valid — no `ApiCallRecord` | SKIP | — |
| D07 | Still valid — edit targets as screen-level state | DEFER | — |
| D08 | Still valid — ad-hoc safe-id helpers | DEFER | — |
| D09 | Still valid — one round-trip test only | DEFER | — |
| D10 | Still valid — usage stats static under `SettingsPreferences` | DEFER (re-estimated LARGE) | deferred — see re-estimate note below |
| D11 | Still valid — no migration registry | SKIP (conflicts with no-backcompat rule) | — |
| P01 | Still valid — params passed separately | DEFER | — |
| P02 | Still valid — capability checks scattered + `SettingsHolder` fallback | **DO** | done (ModelCapabilityResolver: value + source; dispatch delegates) |
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
| T02 | Still valid — no parser tests | **DO** | done (ExternalAppCommandParserTest, 13 cases) |
| T03 | Partially done — component tests, no nav contracts | DEFER | — |
| T04 | Still valid — no boundary tests | DEFER | — |
| T05 | Still valid — no perf tests | SKIP | — |
| T06 | Partially done — idempotent-dup + removed-cost cases | **DO** (broaden) | done (+6 cases: retry idempotent/additive, in/out-only, error-preserves, ledger accumulate, reconcile no-op) |

## This session — the DO items, one commit each

Build cadence: compile-verify each commit; full `assembleDebug` + deploy to
both targets + launch once at the end.

Landed:

1. `COD-A08` — finish removing stale phase comments. ✅
2. `COD-A04` — write the runtime ownership map (`doc/ownership.md`). ✅
3. `COD-U11` — `IconActionButton`/`StatusIcon` accessibility wrapper. ✅
4. `COD-R07` — `BatchBuildScope.run(...)` helper. ✅
5. `COD-U14` — extract `ExternalAppCommandParser` + command objects. ✅
6. `COD-T02` — JVM tests for the external/share parser. ✅
7. `COD-P02` — `ModelCapabilityResolver` (value + source/reason). ✅
8. `COD-R02` — migrate replay maps onto `ReplayTrack`. ✅
9. `COD-A06` — `CreateReportConfig` / `AgentStatusPatch` command objects. ✅
10. `COD-T06` — broaden cost-accounting mutation tests. ✅

All ten landed as one commit each, plus the tracker commits. Build cadence:
each commit compile-verified (`compileDebugKotlin`, or
`compileDebugAndroidTestKotlin` for the test commits); full `assembleDebug` +
deploy to both targets + launch run once at the end.

### Deferred this session — re-estimated LARGE (cost-accounting cluster)

`COD-D10`, `COD-D05`, `COD-A07`, and `COD-D01` were planned as MEDIUM but,
read against the real code, they form one ~500-line tightly-coupled cluster in
`SettingsPreferences`:

- Three caches — token (`usageStatsCache`), category (`usageCategoryStatsCache`),
  report (`usageReportStatsCache`) — that **share a single `usageStatsLock`**,
  so they can't be split cleanly one at a time.
- `reconcileReportCostLedgers` (D05's subject) mutates all three and is the seam
  D10 also depends on — they must move together.
- Recording chokepoints couple to `ApiTracer`, `ApiUsageRates`, `PricingCache`,
  and `ReportStorage.appendApiCallCost`.
- JVM coverage is thin (one flush test in `SettingsPreferencesUsageStatsTest`).

A pure relocate-and-delegate is behaviorally low-risk (bodies unchanged,
compiler-checked) but high edit-volume on the cost ledger. Deferred to a
dedicated session, ideally after the cost test net is broadened, so the move
has a regression net. Do D10 + D05 + A07 + D01 as one focused unit.
