# Audit "report-section" — deep bug-hunt of the Report section

Date: 2026-07-01. Branch `master`, HEAD `0741168d0`.

## What this is

A focused deep audit of the **Report section**: all 101 files under
`ui/report/` (manage, manage/view, view, view/helpers, start, info, other),
the report view-model layer and engines (`ReportViewModel`,
`RegenerateBatchEngine`, `SecondaryRunManager`, `SecondaryBatchEngine` +
Tournament/JudgeEval/Compare/TranslatorRank engines, `FanOutEngine`,
`TranslationRunManager`, `IconGenerationManager`, `MetaEditManager`,
`ThrottledBatch`, `BrokenWorkPolicy`, …), and the report data files
(`ReportStorage`, `ReportModels`, `ReportBundle`, `SecondaryResult`,
`RegenerateBatch*`, …).

Run as 13 parallel agents: 10 shard finders (every file read end-to-end,
cross-file traces, adversarial self-refutation required before reporting),
2 verifiers re-checking the unverified report-related candidates salvaged
from the interrupted 2026-06-10 "fable" audit against current HEAD, and 1
fresh-commits regression reviewer over the 146 commits since that audit.
The orchestrator then spot-checked the four highest-impact single-source
mechanisms directly in code (all four confirmed).

This closes the `ui/report` / V3-engines / V4-runners / V5-icons coverage
gap left by the aborted fable run.

## Ground rules (same as prior audits)

- Single-user app — in-app coroutine races in scope, no multi-user scenarios.
- Trusted user — no security findings. App-only. No backwards-compat findings.
- nl-NL comma-decimal locale bugs count.
- Known intentional patterns excluded (overlay early-return, PricingCache
  main-thread short-circuit, retry-interceptor looper guard, synthetic LOCAL).

## Results

- **findings.md** — 54 consolidated, deduplicated findings:
  **16 HIGH, 22 MEDIUM, 16 LOW**, grouped by component, each with
  location, symptom, root cause, repro and confidence. Findings
  corroborated by 2–3 independent agents are marked; four HIGHs were
  additionally hand-verified by the orchestrator.
- **salvaged-candidates.md** — resolution of the 18 report-related
  candidates from `audit/fable/bugs_data_candidates.md`: 10 confirmed
  (2 HIGH), 4 fixed by post-audit commits, 4 refuted (2 as dead code).

Unlike the fable candidates, these findings went through per-agent
self-refutation and (for the headline items) independent corroboration —
but they have not been reproduced on-device. Treat repro steps as the
verification recipe.
