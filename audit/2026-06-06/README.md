# Bug Audit — June 2026 (audit-3)

A fresh, independent deep code review of the **current** codebase
(2026-06-06), produced from scratch by four parallel domain reviewers.
The earlier `audit/2026-05-08` and `audit/2026-05-24` directories were
used **only as a structural/format reference** — none of their findings,
line numbers, or status notes were carried over. Every entry here was
rediscovered against the live source, which has since been further
restructured (e.g. the new `ui/report/manage/view/**`, the Dependencies
screen, the home-bar full-screen/cutout work) and moved to a Kotlin
2.4 / JDK 25 / compileSdk 37 toolchain.

**281 findings** — 1 CRITICAL, 6 HIGH, 44 MEDIUM, 230 LOW. See
`00_summary.md` for the breakdown, the critical-class entry, the ranked
HIGH band, and the cross-cutting themes.

## Files

- `00_summary.md` — totals, severity breakdown, the critical bug, the
  ranked HIGH list, batchable themes.
- `bugs_reports.md` — Report & Translation: `ui/report/**` + report
  export helpers in `ui/helpers/**`. (65)
- `bugs_chat.md` — Chat / Dual Chat / Knowledge / Models / Search /
  History + `ChatViewModel`. (76)
- `bugs_settings.md` — Settings / AI Setup / Admin / Housekeeping /
  CRUD framework + entity CRUDs / Share-target. (56)
- `bugs_data.md` — Data layer / view-models / infrastructure:
  `data/**`, `viewmodel/**`, `model/**`. (84)

## Entry format

Each finding is grouped under a `## File:` heading and numbered
continuously within its domain file:

```
### Bug N — Severity: … — Category: …
**Location:** <file>:<lines> (`Symbol`, brief)
**Symptom:**     what goes wrong, observable
**Root cause:**  the underlying reason in code
**Reproduction:** steps (when applicable)
**Proposed fix:** concrete change
**Status:** Open
```

## Severity scale

- **CRITICAL** — crash, data loss, or feature totally broken.
- **HIGH** — visible misbehaviour or wrong result.
- **MEDIUM** — real edge case, recoverable, but a genuine defect.
- **LOW** — cosmetic / suspicious / unconfirmed; worth a second look.

## Status field

Every entry is **Open**. This is a discovery pass: findings have **not**
been re-verified-as-fixed, triaged, or cross-checked against prior
audits or the active working session. Treat severities as the
reviewers' impact estimates, and line numbers as accurate at
2026-06-06 (they drift with edits). Static analysis only — no finding
was reproduced at runtime.

## Relationship to prior audits

`audit/2026-05-08` (audit-1) and `audit/2026-05-24` (audit-2) are the
earlier passes. This `audit/2026-06-06` (audit-3) is a clean re-run
against newer code, so findings are not 1:1 comparable with either —
the report/secondary screens have been split further into
`ui/report/manage/**` and `ui/report/view/**` (incl. sub-screens like
`manage/UserNotes.kt`, `view/ValueView.kt`, `view/AnswerMatrix.kt`), and
several earlier findings have since been addressed. Where the passes
agree, confidence is higher; divergence mostly reflects the refactor
and intervening fixes.
