# Audit 2026-06-08

Fresh static audit of the current `codex` worktree, using
`audit/2026-06-06/` as the format and scope reference.

This audit focuses on current, still-open risk in the report section and
the data/UI flows that feed it. It is intentionally written as an issue
inventory rather than a patch plan: every entry has a concrete location,
symptom, root cause, reproduction path, proposed fix, and a status field.

## Scope

- Report section: report manage/view screens, TransRank, tournament,
  judges, compare, value/cost views, report agent chat.
- Data layer: secondary-result storage, report bundle import, rerank /
  TransRank parsers, throttled batch permits, prompt/log caches.
- Chat section: persisted chat sessions, dual chat, local semantic search
  and chat cost/state persistence.
- Settings section: local runtime model management, prompt translations,
  setup counts, app-log access, import/cache safety.

## Totals

| File | Findings | Status |
|---|---:|---|
| `bugs_reports.md` | 28 | 23 fixed, 5 open |
| `bugs_data.md` | 16 | 8 fixed, 8 open |
| `bugs_chat.md` | 10 | 7 fixed, 3 open |
| `bugs_settings.md` | 10 | 7 fixed, 3 open |
| **Total** | **64** | **45 fixed, 19 open** |

## Severity Counts

| Severity | Count |
|---|---:|
| Critical | 0 |
| High | 13 |
| Medium | 35 |
| Low | 16 |

## Method

- Reviewed the 2026-06-06 audit format and issue taxonomy.
- Ran targeted static scans for risky patterns: unsafe JSON primitive
  extraction, non-saveable Compose state, synchronous storage in
  composition, path construction with user-controlled strings, blocking
  permit reacquisition, and result-storage lifecycle gaps.
- Traced report workflows across UI, view model, and storage layers rather
  than auditing files in isolation.
- Did not execute emulator, build, deploy, or instrumented tests; this is
  a static-code audit only.

## Suggested Fix Order

1. Fix the high-severity TransRank lifecycle/accounting problems in
   `TranslatorRankEngine` and `SecondaryResultStorage`.
2. Fix hidden persisted rows when internal prompts are missing for
   TransRank, Compare, Tournament, and JudgeEval.
3. Fix report value/cost attribution bugs in `ValueView`.
4. Harden storage/path and JSON parsing surfaces.
5. Move remaining synchronous local-model and chat storage reads off the
   Compose main path.

