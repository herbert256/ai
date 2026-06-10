# Codex source audit

Date: 2026-06-08

Worktree: `/Users/herbert/ai-codex`

Branch: `codex`

Head checked: `5e52f0ccb`

## Scope

This is a fresh static audit of the current source code. It focuses on
maintainability, architecture, runtime ownership, persistence boundaries,
provider/runtime seams, UI workflow structure, and test coverage.

## Current source snapshot

- Production Kotlin files: 393.
- Production Kotlin LOC under `ai/src/main/java/com/ai`: 153,610.
- Test files: 96 Kotlin test files, 69 JVM and 27 instrumented.
- Largest current files remain:
  - `ui/settings/SettingsScreen.kt`: 3,222 LOC.
  - `viewmodel/IconGenerationManager.kt`: 3,096 LOC.
  - `viewmodel/ReportViewModel.kt`: 3,002 LOC.
  - `ui/admin/AiDashboardScreen.kt`: 2,976 LOC.
  - `ui/shared/SharedComponents.kt`: 2,681 LOC.
  - `data/ReportStorage.kt`: 2,620 LOC.
  - `data/ColorUsageData.kt`: 2,499 LOC.
  - `viewmodel/AppViewModel.kt`: 2,050 LOC.
  - `ui/settings/ImportExportScreen.kt`: 2,033 LOC.
  - `viewmodel/FanOutEngine.kt`: 2,020 LOC.

## Files

- `00_summary.md`: executive summary and recommended first pass.
- `01_findings.md`: current findings, grouped by domain.
- `02_progress.md`: re-verification against current `master` (2026-06-09),
  per-finding verdicts, and the work landed in response.

## Verification note

This was a static source check. I did not run build, deploy, JVM tests, or
instrumented tests.
