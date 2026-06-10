# Audit Summary

Date: 2026-06-10

Worktree audited: `/Users/herbert/ai`

Branch observed: `master` (at `e948786f3`)

Status: static audit complete, 10 findings — all 10 open
(per-finding detail in `bugs_reports.md`, including the list of
~15 candidates rejected on hand verification).

Scope: the reports section only (per request) — manage + view screens,
batch engines including the fresh `SecondaryBatchEngine` consolidation,
and the data layer feeding them. Single-user ground rules: no security /
hardening / external-factor findings.

## Highest-Risk Themes

1. **Rank-id instability is half-fixed.** The Podium screen was fixed to
   number ranks by participant position; the Tournament view still
   numbers by the current SUCCESS set, so its ranking labels (and
   head-to-head jumps) drift the moment the success set changes after
   the tournament ran. The Moderation screen has the same underlying
   instability but already surfaces a caution banner.
2. **Delete-time cost rollups read in-memory snapshots.** The
   consolidated remove paths read disk truth, but every `deleteRun`
   (and FanOut's failed/unfinished/benched removes) still sums the
   in-memory snapshot — in-flight calls that settle during the delete
   slip the deleted-items tally.
3. **Two state machines can wedge or misbehave quietly:** the
   regenerate AGENTS phase parks 30 minutes on a SUCCESS-with-blank-body
   row instead of settling it, and the translation reconcile sweep gates
   on a `paused` flag captured once at effect launch.
4. **Fan-out rows can go invisible while still costing:** hydration
   skips pair rows whose answerer no longer matches a report agent, so
   they vanish from the Fan screens but stay on disk, in cost tables,
   and in exports.

## Top Findings

1. **High — Tournament view ranking labels mis-map when participants ≠
   current successful set** (`Tournament.kt:104`; the engine and the
   Podium both number by participant position).
2. **Medium — translation reconcile loop reads a stale `paused`
   capture** (`GenerationPhase.kt:707-728`) — the overlay gate never
   engages (or never releases).
3. **Medium — regenerate AGENTS phase parks 30 min on SUCCESS + blank
   body** (`RegenerateBatchEngine.kt:493-495`).
4. **Medium — deleteRun / remove-pairs cost rollups under-count from
   stale in-memory snapshots** (11 sites listed in `bugs_reports.md`).
5. **Medium — orphaned fan-out pair rows: invisible in UI, still
   counted in costs/exports** (`FanOutEngine.kt:195-197`).
