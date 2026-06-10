# Audit 2026-06-10

Fresh static audit of `master` (at `e948786f3`), using the four prior
`audit/2026-*` rounds as the format and depth reference. Scope per the
request: **the reports section only** — manage + view screens, the batch
engines (including the 2026-06-09 `SecondaryBatchEngine` consolidation),
and the data layer feeding them.

Audit ground rules set by the user:

- Single-user app — no concurrent-multi-user or "data changed out of
  nowhere" scenarios.
- The one user is trusted — no security / hardening / injection /
  path-traversal findings.
- The app itself only — no external-factor findings.

## Scope

- Report manage screens: Main / Run / GetInfo / SecondResults /
  GenerationPhase / RegenerateBatch / RuntimeState / Broken-work.
- Report view screens: view hub, Tournament + Podium, Rerank, Costs,
  Value view (+ the new HTML export), Fan / FanIn / FanPair, Translate,
  Moderation, Compare.
- Batch engines: `SecondaryBatchEngine` + the four siblings, `FanOutEngine`,
  `TranslationRunManager`, `RegenerateBatchEngine`, `SecondaryRunManager`,
  `IconGenerationManager` (fan-meta).
- Data layer: `ReportStorage`, `SecondaryResultStorage`, ranking math
  (`TournamentRanking`), parsers, cost rollups, `ReportBundle`.

## Totals

| File | Findings | Status |
|---|---:|---|
| `bugs_reports.md` | 10 | 10 open |
| **Total** | **10** | **10 open** |

## Severity Counts

| Severity | Count |
|---|---:|
| Critical | 0 |
| High | 1 |
| Medium | 4 |
| Low | 5 |

## Method

- Two waves of parallel exploration agents over disjoint territories
  (manage/lifecycle, view screens, batch engines, data layer; then a
  deeper second pass on view screens, translation + fan-out, and manage
  state machines).
- **Every candidate was then verified by hand against the source before
  inclusion** — ~25 candidates were raised, 10 survived. Rejected
  candidates (with the reason) are listed at the bottom of
  `bugs_reports.md`; the rejects included claims that contradicted
  documented design (cost-keeping on regenerate, the translation
  cancel-item discard), claims the code disproves (`updateContent`
  recalculating costs, a `LaunchedEffect` key that does reset state),
  and one with the project's locale convention backwards.
- Static audit only — no emulator runs, no instrumented tests.

## Suggested Fix Order

1. Tournament view rank-id labeling (High — wrong model names on the
   ranking; the Podium screen already carries the fix pattern).
2. The stale-`paused` reconcile gate and the SUCCESS-blank-body
   regenerate parking (both isolated, low-risk fixes).
3. Unify delete/remove cost rollups on disk-truth reads (one sweep
   across the listed sites, same pattern as `removeItemsMatching`).
4. The Low items opportunistically.
