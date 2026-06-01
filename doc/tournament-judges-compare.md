# Tournament, Judge the Judges, Compare with Meta

These three features are worker-judged analysis batches that operate on a
finished report. They are stored as `SecondaryResult` rows but are not ordinary
single-call Meta rows: each run pre-creates a grid of cells, dispatches the
cells through workers or fixed judges, persists each cell independently, and
hydrates the run back from disk when a report is reopened.

## Shared model

All three flows use the same operational pattern:

- A report must have successful primary model responses.
- A run creates placeholder `SecondaryResult` rows before API calls start.
- Each placeholder becomes `RUNNING`, then `DONE` or `ERROR`.
- `runId` tags traces and usage so the UI can deep-link to the batch.
- `activeSecondaryBatches` keeps the result screen polling while work is live.
- On process loss or navigation away, hydration rebuilds the run state from
  `<filesDir>/secondary/<reportId>/`.
- Failed cells can be restarted without deleting successful cells.
- A full redo deletes the run's rows and launches a new grid.

The hot state lives outside `UiState`:

| Flow | Runtime owner | Running ids | Waiting ids |
|---|---|---|---|
| Tournament | `TournamentEngine` | `runningTournamentMatches` | `throttledTournamentMatches` |
| Judge the judges | `JudgeEvalEngine` | `runningJudgeEvalCells` | `throttledJudgeEvalCells` |
| Compare with meta | `CompareEngine` | `runningCompareCells` | `throttledCompareCells` |

All three use `ApiCallCaps.workers` as their sub-cap, plus the normal
per-provider `ProviderThrottle`.

## Tournament

**Tournament** asks worker models to judge every pair of successful report
answers head-to-head.

For `N` report answers, the run creates `N(N-1)` ordered matches:
each unordered pair is judged twice, once as A-vs-B and once swapped as
B-vs-A, to reduce first-position bias. A match starts with
`providerId="*workers"` and `model="*pending"`. When the worker fallback chain
returns a valid verdict, the row is overwritten with the worker provider/model,
tokens, cost, duration, trace filename, and verdict content.

The prompt is the bundled `workers/tournament` internal prompt. It references
the `tournament` swarm, so the worker model is chosen at runtime by
`WorkerRunner`; the match identity does not include the worker.

Stored rows:

| Row | `SecondaryKind` | Key fields |
|---|---|---|
| Match | `TOURNAMENT` | `tournamentRole="MATCH"`, `tournamentJudgeRunId`, `matchResponseAId`, `matchResponseBId`, `matchOrientation` |
| Aggregate | `TOURNAMENT` | `tournamentRole="AGGREGATE"`, `tournamentMatrix`, ranked JSON in `content` |

The aggregate row stores a win matrix plus the selected ranking method. The
view can recompute and persist rankings locally without rerunning matches.
Supported methods are:

- Copeland
- Elo
- Davidson
- Tideman
- Markov

Manage-side drill-in:

- L1 groups by judge model or report model and shows total/done/run/wait/queue
  counts plus cost.
- L2 shows either a judge's matches or a report model's match list.
- L3 shows one match, verdict, confidence/reason, raw judge reply, cost, and a
  trace link when tracing was enabled.

View-side drill-in:

- Tournament appears as its own View tab.
- The leaderboard can switch aggregation method.
- Tapping a ranked model opens head-to-head cards, including A-vs-B/B-vs-A
  orientation switching and trace links.

## Judge the Judges

**Judge the judges** measures agreement between the concrete judge models in
the `workers/tournament` prompt's swarm.

Instead of round-robin worker selection, every judge receives the same random
set of answer pairs. `JUDGE_MATCH_COUNT` is 25, capped by the number of
distinct pairs a small report can form. The grid is:

```
judges × selected matches
```

Each cell is a fixed model call: the row's provider/model is the judge from
the moment the placeholder is created. The same `workers/tournament` prompt
text and verdict parser are reused, so Tournament and Judge-the-judges answer
the same question from different angles.

Stored rows:

| Row | `SecondaryKind` | Key fields |
|---|---|---|
| Cell | `JUDGES` | `tournamentRole="MATCH"`, `tournamentJudgeRunId`, `matchResponseAId`, `matchResponseBId`, `matchOrientation` |
| Aggregate | `JUDGES` | `tournamentRole="AGGREGATE"`, judge agreement JSON in `content` |

The agreement layer computes:

- each judge's agreement with match consensus
- cost and total API time per judge
- overall consensus strength
- per-match consensus and vote counts

Manage-side drill-in:

- L1 toggles between Judges and Matches.
- Judges mode shows per-judge progress while running and a consensus table
  after completion.
- Matches mode shows each selected pair, consensus, and vote counts.
- A judge can be added to the run; the model is also added to the underlying
  tournament swarm.
- Removing a judge deletes its cells and removes it from that swarm.
- Editing the swarm can trigger a rerun prompt if the current run no longer
  matches the active judge set.

## Compare with Meta

**Compare with meta** scores how closely each primary report answer matches
selected Meta results.

The user first selects one or more existing plain Meta rows, then chooses an
internal prompt from `category="meta_compare"` (the bundled prompt is
`meta_compare/equivalent`). The run creates:

```
successful answers × selected meta rows
```

Each cell is worker-judged through the prompt's worker list. The scoring worker
is dynamic like Tournament: placeholders start as `*workers/*pending` and are
overwritten with the winning worker. The parser accepts the labelled
`percentage:` format, JSON with `percentage`/`percent`/`score`, or a first
number fallback, clamped to 0..100.

Stored rows:

| Row | `SecondaryKind` | Key fields |
|---|---|---|
| Cell | `COMPARE` | `compareRunId`, `compareAgentId`, `compareToResultId`, `metaPromptId`, `metaPromptName` |

There is no aggregate row. L1 averages are computed from cells:

- Report models mode: mean score per answer across selected meta rows.
- Meta items mode: mean score per meta row across answers.

L2 opens one group; L3 opens one cell with answer text, meta text, worker reply,
reason, cost, and trace.

## Persistence and deletion

The canonical store for all three features is still
`<filesDir>/secondary/<reportId>/<resultId>.json`. Runtime state is disposable.

Deleting a report deletes these rows with every other secondary result. Deleting
a run removes only rows belonging to that run id. Restarting failed cells resets
only the affected placeholders. Full redo creates a new run id.

## Cost and usage

Usage kinds:

| Flow | Usage kind |
|---|---|
| Tournament | `tournament` |
| Judge the judges | `judges` |
| Compare with meta | `compare` |

Costs are recorded on each cell row from the worker or judge provider/model that
actually billed. The report cost table groups them as tournament, judges, or
compare rows, and deleted-cell spend is rolled into the report's
`costsFromDeletedItems` via `SecondaryResult.fullCost()`.

## Related files

- `data/TournamentRunModel.kt`
- `data/TournamentRanking.kt`
- `data/JudgeEvalRunModel.kt`
- `data/JudgeAgreement.kt`
- `data/CompareRunModel.kt`
- `viewmodel/TournamentEngine.kt`
- `viewmodel/JudgeEvalEngine.kt`
- `viewmodel/CompareEngine.kt`
- `ui/report/manage/Tournament.kt`
- `ui/report/manage/JudgeEval.kt`
- `ui/report/manage/Compare.kt`
- `ui/report/view/Tournament.kt`
- `ui/report/view/TournamentPodium.kt`
