# Bug Audit — May 2026 (audit-2)

A fresh, independent deep code review of the **current** codebase
(2026-05-24), produced from scratch by four parallel domain reviewers.
The earlier `audit/` directory was used **only as a structural/format
reference** — none of its findings, line numbers, or status notes were
carried over. Every entry here was rediscovered against the live source.

**295 findings** — 1 CRITICAL, 21 HIGH, 101 MEDIUM, 172 LOW. See
`00_summary.md` for the breakdown, the critical-class list, the ranked
HIGH band, and the cross-cutting themes.

## Files

- `00_summary.md` — totals, severity breakdown, critical bugs, ranked
  HIGH list, batchable themes.
- `bugs_reports.md` — Report & Translation: `ui/report/**` + report
  export helpers in `ui/helpers/**`. (70)
- `bugs_chat.md` — Chat / Dual Chat / Knowledge / Models / Search /
  History + `ChatViewModel`. (79)
- `bugs_settings.md` — Settings / AI Setup / Admin / Housekeeping /
  CRUD framework + entity CRUDs / Share-target. (66)
- `bugs_data.md` — Data layer / view-models / infrastructure:
  `data/**`, `viewmodel/**`, `model/**`. (80)

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
2026-05-24 (they drift with edits). Static analysis only — no finding
was reproduced at runtime.

## Relationship to `audit/`

`audit/` is the previous pass (also May 2026). This `audit-2/` is a
clean re-run against newer code that has since been heavily
restructured (e.g. the old `ui/report/ReportScreen.kt` /
`SecondaryResultsScreen.kt` are now split across
`ui/report/manage/**` and `ui/report/view/**`), so findings are not
1:1 comparable. Where the two agree, confidence is higher; divergence
mostly reflects the refactor and intervening fixes.
