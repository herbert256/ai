# Report Bugs — Audit 2026-06-10

All findings verified by hand against the source at `e948786f3`.
Status: all **Open** at audit time.

### Bug 1 - Severity: High - Category: Tournament rank-id labeling
**Location:** `ai/src/main/java/com/ai/ui/report/view/Tournament.kt:104` (vs `TournamentPodium.kt:325-344` and `TournamentEngine.recomputeAggregate`)

**Symptom:** The Tournament view's ranking rows (and the head-to-head
jump from a ranking row) can name the wrong models whenever the report's
current SUCCESS set differs from the tournament's participant set.

**Root cause:** The engine numbers the ranking JSON's `[N]` ids by each
participant's stable position in `report.agents` **filtered to the
participant set** (`TournamentEngine.recomputeAggregate`,
"Number every participant by its stable position in the report's agent
order"). `TournamentPodium.kt:325-344` was already fixed to match — its
comment explicitly documents this bug class ("Numbering through the
CURRENT success set shifted the ids … mapping ranks to the wrong
models"). `Tournament.kt:104` still builds the id→label map over **all
currently-successful agents**:

```kotlin
val labels = successful.mapIndexed { i, a -> (i + 1) to shortModelName2(a.model) }.toMap()
```

That map is consumed at `Tournament.kt:209-210` to label the ranking
rows and to pick the head-to-head target
(`h2hModel = loaded.agentLabels[r.id]`).

**Reproduction:** Run a tournament on a report where one model errored
(not a participant). Later regenerate that model so it becomes SUCCESS.
Open the Tournament view: the success list now has one extra entry
before/among the participants, so every `[N]` id at or after its
position maps to the wrong model name, and tapping a ranking row opens
the wrong model's head-to-heads.

**Proposed fix:** Build the map exactly like `TournamentPodium.kt:332`:
collect `participantIds` from the run's MATCH rows, then number
`report.agents.filter { it.agentId in participantIds }` by index.

**Status:** Open

### Bug 2 - Severity: Medium - Category: Stale Compose capture
**Location:** `ai/src/main/java/com/ai/ui/report/manage/GenerationPhase.kt:707-728` (param declared `:411`)

**Symptom:** The 10-second translation reconcile sweep ignores the
Get-info overlay gate: it keeps reconciling while the overlay is up —
and in the inverse case (effect first launched while the overlay is up)
it never reconciles at all, even after the overlay closes.

**Root cause:** `paused` is a plain `Boolean` composable parameter. The
reconcile loop runs in `LaunchedEffect(currentReportId)` — `paused` is
neither a key nor wrapped in `rememberUpdatedState`, so the coroutine
reads the value captured at effect launch forever:

```kotlin
val latestActiveRuns = rememberUpdatedState(activeTranslationRuns)  // ← done right
LaunchedEffect(currentReportId) {
    val reconciled = mutableSetOf<String>()
    while (true) {
        val rid = currentReportId
        if (rid != null && !paused) {   // ← stale capture, never updates
```

The adjacent state (`activeTranslationRuns`) is wrapped in
`rememberUpdatedState` for exactly this reason, and the *other* effect
at `:762-766` documents the gate-not-key intent — but that one re-reads
a `State`, this one captures a raw param.

**Reproduction:** Open a report with a stalled translation run, open the
Get-info overlay, watch the log: the reconcile sweep still fires every
10 s behind the overlay.

**Proposed fix:** `val latestPaused = rememberUpdatedState(paused)` and
gate on `latestPaused.value`, matching the `latestActiveRuns` pattern.

**Status:** Open

### Bug 3 - Severity: Medium - Category: Regenerate phase parking
**Location:** `ai/src/main/java/com/ai/viewmodel/RegenerateBatchEngine.kt:493-495`

**Symptom:** A regenerate batch's AGENTS phase parks for the full
30-minute phase timeout (then pauses the job) if any agent row is
`SUCCESS` with a blank `responseBody`.

**Root cause:**

```kotlin
ReportStatus.SUCCESS ->
    if (!agent.responseBody.isNullOrBlank()) RowStatus.Success
    else RowStatus.Pending
```

A SUCCESS row with a blank body maps to `Pending` — a non-terminal
state — so `awaitPhaseCompletion` polls it until the 30-minute safety
timeout. The codebase treats "SUCCESS with empty body" as a state to
prevent elsewhere (`SecondaryRunManager.kt:1184` maps a blank reply to
`STOPPED` with a comment saying exactly why), so if such a row ever
exists — e.g. a provider 200 with empty content that an older version
persisted, or an imported report — the regenerate engine wedges on it
rather than settling it.

**Reproduction:** Requires a SUCCESS agent row with a blank body on
disk (legacy/imported data, or any future success-path regression);
then run Regenerate report → the AGENTS phase shows ⏳ for 30 minutes
and pauses with "Phase timed out".

**Proposed fix:** Map `SUCCESS` + blank body to a terminal state
(`Success`, or `Error("empty response")` to match the
`SecondaryRunManager` stance) so the phase settles immediately.

**Status:** Open

### Bug 4 - Severity: Medium - Category: Cost accounting on delete
**Location:** `ai/src/main/java/com/ai/viewmodel/TournamentEngine.kt:528`, `JudgeEvalEngine.kt:551` (`deleteJudgeFromRun`), `JudgeEvalEngine.kt:690` (`deleteRun`), `CompareEngine.kt:450`, `FanOutEngine.kt:1459`, `:1479`, `:1504`, `:1531`, `:1795`, `:1830-1831`, `:1901`

**Symptom:** Deleting a whole run (or failed/unfinished/benched pairs)
can roll less than the actual spend into the report's deleted-items
cost tally, so the report total under-counts after delete.

**Root cause:** These paths sum the **in-memory** snapshot:

```kotlin
val costDelta = run.matches.values.sumOf { it.totalCost }   // TournamentEngine.kt:528
```

while the consolidated remove path reads disk truth
(`SecondaryBatchEngine.removeItemsMatching`:
`SecondaryResultStorage.get(...)?.fullCost() ?: it.totalCost`, and
`FanOutEngine.removePairsByIds:1614` does the same). Two gaps:

1. `deleteRun` goes through `deleteRunDeferred`, which **synchronously
   drops the run from `_runs` before joining the item coroutines** — an
   in-flight call that settles during the join writes its cost to disk,
   but the per-item finally's in-memory mirror no-ops (run gone), and
   the disk work then sums the **pre-settle snapshot**.
2. Any in-memory staleness (item state never mirrored) silently
   under-rolls; the disk row is the single source of truth and is being
   read for deletion anyway.

**Reproduction:** Start a tournament, and while matches are in flight
delete the run. Costs of calls that completed between the cancel and
the join are charged by the provider but missing from the deleted-items
tally.

**Proposed fix:** In the delete/remove bodies, sum
`SecondaryResultStorage.get(context, reportId, it.id)?.fullCost()
?: it.totalCost` (the rows are read-then-deleted anyway), matching
`removeItemsMatching` / `removePairsByIds`.

**Status:** Open

### Bug 5 - Severity: Medium - Category: Fan-out orphaned rows
**Location:** `ai/src/main/java/com/ai/viewmodel/FanOutEngine.kt:195-197` (hydrate)

**Symptom:** Fan-out pair rows whose answerer (provider, model) no
longer matches any report agent silently disappear from every Fan
screen — but stay on disk, keep counting in the report's cost tables
and exports, and the resume sweep eventually stamps them "Interrupted"
errors the fan UI never shows.

**Root cause:** Hydration derives each row's answerer agent by matching
(provider, model) against the report's current agents and **skips the
row** when nothing matches:

```kotlin
val answererAgentId = agentsById.values.firstOrNull {
    it.provider.equals(row.providerId, ignoreCase = true) && it.model == row.model
}?.agentId ?: continue
```

Removing a model response from the report (or a model-switch on the
agent) breaks the match for its pairs; the rows become invisible
in-memory while remaining fully present on disk.

**Reproduction:** Run a fan-out, then remove one answerer model from
the report. Reopen Manage → the Fan rows for that model are gone from
L1/L2/L3, but the report cost table still includes their spend, and the
Broken-work sweep later marks them errored — invisible errors.

**Proposed fix:** Hydrate unmatched rows into a "(removed model)"
pair group (read-only, deletable) instead of skipping, or sweep them
into the deleted-items tally when the source agent disappears.

**Status:** Open

### Bug 6 - Severity: Low - Category: Compare precision
**Location:** `ai/src/main/java/com/ai/data/CompareRunModel.kt:86`, `:94` (`avgForAgent` / `avgForMeta`), `ai/src/main/java/com/ai/ui/report/view/ValueView.kt:488`

**Symptom:** Compare-with-meta mean match percentages truncate instead
of round (50.5 → 50), losing up to one point and collapsing close
agents into ties on the Value view's quality axis and the compare
grids.

**Root cause:** Int/Int division:

```kotlin
return scored.sumOf { it.percent!! } / scored.size       // CompareRunModel.kt:86
.mapValues { (_, cs) -> cs.sumOf { it.percent!! } / cs.size }  // ValueView.kt:488
```

All three sites truncate identically, so the screens are at least
consistent — but agents whose true means differ (51.0 vs 51.5) tie, and
the Value view's "best value" pick can flip on the lost fraction.

**Reproduction:** Compare run where one agent scores [51, 52] (mean
51.5 → shown 51) and another [51, 51] (51) — identical displayed and
ranked quality despite a real ordering.

**Proposed fix:** Average in `Double` (`sumOf(...).toDouble() / size`)
and round only at the display edge.

**Status:** Open

### Bug 7 - Severity: Low - Category: Build-popup release
**Location:** `ai/src/main/java/com/ai/viewmodel/SecondaryBatchEngine.kt:247` (`rerunItemsBlocking`)

**Symptom:** The "Preparing N / M…" build popup is not finished on the
`run == null` early-return, unlike the empty-keys and synthetic-prompt
returns directly above and below it.

**Root cause:**

```kotlin
if (itemKeys.isEmpty()) { buildKey?.let { appViewModel.finishBuild(it) }; return }
val run = _runs.value[runKey] ?: return            // ← no finishBuild
if (!canRedispatch(context, run)) { buildKey?.let { appViewModel.finishBuild(it) }; return }
```

Currently mitigated: the only caller passing a `buildKey`
(`continueBrokenBatch`) holds a `finally` that force-finishes the
popup. The asymmetry is a trap for the next direct caller.

**Proposed fix:** `?: run { buildKey?.let { appViewModel.finishBuild(it) }; return }`.

**Status:** Open

### Bug 8 - Severity: Low - Category: Dead status input
**Location:** `ai/src/main/java/com/ai/ui/report/manage/SecondResults.kt:56-62` (`secondAggregate`), only call `RuntimeState.kt:343`

**Symptom:** `secondAggregate(all, liveTranslations)` can force ⏳ for a
live translation run, but its single call site hardcodes
`liveTranslations = false` — the Manage "second" row's spinner leans
entirely on blank placeholder rows existing on disk.

**Root cause:**

```kotlin
secondState = secondAggregate(all, liveTranslations = false)   // RuntimeState.kt:343
```

In the windows where a run is live but its rows are not blank — the
build phase before placeholders persist, or a restart that is about to
clear errored rows — the row reads ✅/❌ while work is in flight.

**Reproduction:** Restart a translation run with many errored rows; in
the moment between the restart tap and the rows being cleared back to
placeholders the second row shows ❌ although the batch is live.

**Proposed fix:** Pass the live-translation signal the screen already
has (`activeTranslationRuns.isNotEmpty()`), or drop the dead parameter.

**Status:** Open

### Bug 9 - Severity: Low - Category: Moderation display
**Location:** `ai/src/main/java/com/ai/ui/report/view/Moderation.kt:368`

**Symptom:** A moderation row that is flagged but has an empty
`firedCategories` list shows no "Fired:" marker at all — the flagged
state is invisible in the detail card.

**Root cause:**

```kotlin
if (row.flagged && row.firedCategories.isNotEmpty()) { … }
```

A model can legitimately return `flagged = true` with no per-category
breakdown; the flag is then silently hidden.

**Proposed fix:** When `row.flagged` and the list is empty, render
"Fired: (no categories reported)".

**Status:** Open

### Bug 10 - Severity: Low - Category: Locale-format consistency
**Location:** `ai/src/main/java/com/ai/ui/report/view/ValueViewExport.kt` (`jsonEsc`, the control-char branch)

**Symptom:** `"\\u%04X".format(c.code)` uses the default locale while
every other format call in the file pins `Locale.US`.

**Root cause:** Hex formatting is locale-stable on Android in practice,
so this is a consistency defect rather than a functional one — but the
project convention (and the nl-NL device) is to pin `Locale.US` on
every `format` call.

**Proposed fix:** `String.format(Locale.US, "\\u%04X", c.code)`.

**Status:** Open

---

## Rejected candidates (and why)

Raised during the hunt, struck on hand verification:

- *Regenerate keeps prior costs on restart* — by design; the
  `*KeepingCost` helpers and `doc/regenerate.md` document additive cost.
- *`applyFanOutPairContent` leaves stale costs in memory* — false:
  `SecondaryResultStorage.updateContent` does not touch cost fields.
- *Translation DONE-in-memory lost if item dropped concurrently* —
  documented design: `cancelTranslationItem`'s KDoc says the in-flight
  result is deliberately discarded.
- *Reconcile `reconciled` set never cleared on report switch* — false:
  the effect is keyed on `currentReportId`, so the set is recreated.
- *Title-cycle sets two overlay flags in one handler* — both writes
  land in one snapshot; composition only ever sees the final state, and
  the Get-info → second-results replacement is the documented cycle.
- *`String.format(Locale.US, …)` should use the device locale* —
  backwards: pinning `Locale.US` is the project convention
  (locale round-trips are the bug class, see memory/feedback).
- *Moderation/agent ordinal drift* — real limitation, but the screen
  already detects it and shows the "Agent set changed…" caution banner.
- *Broken-work 60s false positive* — the predicate already excludes
  active runs (`!activeRun`); retracted by its own finder.
- *`rerunItemsBlocking` missing-disk-row skips cost rollup* — correct
  behavior: a missing row is skipped entirely (nothing cleared, nothing
  to roll).
- *ReportBundle note remap keeps stale ids* — keeping the id preserves
  the "Deleted item" note grouping the UI explicitly supports.
- *Tournament tie-count integer division* — only reachable with
  corrupted data; out of scope per the audit ground rules.
