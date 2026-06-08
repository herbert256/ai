# Functional Audit

Static functional, architecture, and source-structure audit of the
current `codex` worktree.

This audit uses `audit/2026-06-08/` as the format reference, but it is
not a technical bug inventory. Entries are written as observations about
what the app does, where each capability lives, how the architecture is
shaped, and which ownership boundaries matter when changing the system.

## Scope

- Product map: user-visible domains and the main workflows they support.
- Architecture map: navigation, state ownership, engines, provider
  dispatch, concurrency, and overlay patterns.
- Source map: package layout, assets, documentation, tests, and audit
  structure.
- Report section: report lifecycle, secondary results, value/cost views,
  export paths, and report-specific tests.
- Data and persistence: SharedPreferences, JSON stores, provider registry,
  report and secondary storage, caches, backup/restore, traces, logs.
- Operations and quality: build/deploy conventions, testing surfaces,
  observability, provider onboarding, help/docs, extension seams.

## Files

| File | Observations | Focus |
|---|---:|---|
| `00_summary.md` | 10 summary themes | Executive view and key ownership model |
| `functional_map.md` | 14 | User-facing product domains |
| `architecture_view.md` | 12 | Runtime architecture and state boundaries |
| `source_structure.md` | 12 | Source, assets, docs, and test layout |
| `report_section_view.md` | 14 | Report workflow and secondary-result model |
| `data_persistence_view.md` | 12 | Storage, caches, traces, backup, exports |
| `operations_quality_view.md` | 10 | Operating model, tests, observability, extensibility |
| **Total** | **74** | Functional / architecture / source audit |

## Method

- Reviewed `audit/2026-06-08/` to mirror the audit-pack style without
  reusing its bug taxonomy.
- Reviewed current documentation in `doc/`, especially `README.md`,
  `architecture.md`, `development.md`, `manual.md`,
  `secondary-results.md`, `persistent.md`, `value-view.md`, and related
  subsystem docs.
- Verified live source counts and package structure from
  `ai/src/main/java/com/ai`.
- Verified assets under `ai/src/main/assets` and test coverage under
  `ai/src/test` and `ai/src/androidTest`.
- Treated the code as authoritative when a doc paragraph and source
  differed.
- Did not run emulator, build, deploy, unit tests, or instrumented tests.
  This is a static functional audit only.

## Snapshot

Worktree audited: `/Users/herbert/ai-codex`

Branch observed: `codex`

Head observed: `89a07fd1e Fix four audit findings: persist settings, drop dead pricing write/code, escape NUL literals`

Date: 2026-06-08

