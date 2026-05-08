# Bug Audit — May 2026

Deep code review of every UI screen + the data layer. Four parallel
agents read the actual source and reported findings; this directory
holds the per-area reports and a master summary.

## Files

- `00_summary.md` — totals, severity breakdown, top 20 by severity.
- `bugs_reports.md` — Reports / Translation / History (115 findings)
- `bugs_chat.md` — Chat / Knowledge / Models / Search / Share (164 findings)
- `bugs_settings.md` — Settings / Admin / Trace / Housekeeping (130 findings)
- `bugs_data.md` — Data layer / ViewModels / Networking / Storage (130 findings)

**Total: 539 findings.**

## Severity scale

- **CRITICAL** — crash, data loss, or feature totally broken.
- **HIGH** — visible misbehaviour or wrong result.
- **MEDIUM** — edge case, recoverable, but real.
- **LOW** — cosmetic / suspicious / unconfirmed.
