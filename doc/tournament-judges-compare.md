# Tournament, Judge the Judges, Compare with Meta

These three features are worker-judged analysis batches that operate on a
finished report. They are stored as `SecondaryResult` rows but are not ordinary
single-call Meta rows: each run pre-creates a grid of cells, dispatches the
cells through workers or fixed judges, persists each cell independently, and
hydrates the run back from disk when a report is reopened. They are three of the
eight `SecondaryKind` values (`RERANK, META, MODERATION, TRANSLATE, TOURNAMENT,
JUDGES, COMPARE, TRANSRANK` — `data/SecondaryModels.kt`). The eighth,
`TRANSRANK` ("Rank the translators"), is a sibling feature that reuses this
file's tournament cell/matrix machinery — see [rank-translators.md](rank-translators.md).

## Shared model

All three flows use the same operational pattern:

- A report must have successful primary model responses.
- A run creates placeholder `SecondaryResult` rows before API calls start, each
  stamped with a sentinel provider/model (see below).
- Each placeholder becomes `RUNNING`, then `DONE` or `ERROR`.
- A run id (`tournamentJudgeRunId` for tournament/judges, `compareRunId` for
  compare) tags traces and usage so the UI can deep-link to the batch.
- `UiState.activeSecondaryBatches` is bumped on entry and decremented in a
  `finally`, keeping the result screen polling while work is live.
- On process loss or navigation away, hydration rebuilds the run state from
  `<filesDir>/secondary/<reportId>/`. There is one run per report — the newest
  run group wins if legacy multi-run rows are present.
- **Persisted runs hydrate read-only when their prompt is missing.** Each
  engine resolves the run's prompt by `metaPromptId`, then falls back to the
  current bundled prompt (`workers/tournament` / `meta_compare`), and finally —
  if both are gone (the prompt was deleted/renamed since the run) — to a
  *synthetic* `InternalPrompt` built from the row's `metaPromptId` /
  `metaPromptName` with **blank text** (its name shows as `(prompt unavailable)`
  when even that is gone). Every mutating path (resume, rerun, restart-failed,
  add-judge) guards on `prompt.text.isBlank()` and no-ops, so a synthetic run
  hydrates for viewing but can't re-fire API calls. (Audit bugs 15 / 16 / 17.)
- Failed cells can be restarted without deleting successful cells.
- A full redo deletes the run's rows and launches a new grid.
- App-kill resume and a per-cell retry bound (`BatchResume.capForRetry`) apply.

Each engine owns its own per-report run-state `StateFlow` (`runs`, on the
`BatchEngine` base); per-item RUNNING status lives in that map (there is **no**
separate "running ids" set on `AppViewModel`). The only hot id sets on
`AppViewModel` (outside `UiState`) are the throttled/"waiting" sets below; the
live in-process Job ids come from each engine's `inFlightRowIds()`
(`matchJobs` / `cellJobs` keys), which the read-only broken-work scan excludes.

| Flow | Runtime owner (in `ReportViewModel`) | Run-state StateFlow | Throttled (waiting) ids |
|---|---|---|---|
| Tournament | `rvm.tournamentEngine` (`TournamentEngine`) | `runs` (per-match `MatchStatus`) | `throttledTournamentMatches` |
| Judge the judges | `rvm.judgeEvalEngine` (`JudgeEvalEngine`) | `runs` (per-cell `JudgeCellStatus`) | `throttledJudgeEvalCells` |
| Compare with meta | `rvm.compareEngine` (`CompareEngine`) | `runs` (per-cell `CompareCellStatus`) | `throttledCompareCells` |

All three use `ApiCallCaps.workers` as their per-flow sub-cap (which shares the
`fanMeta` concurrency limit, default 50), layered over the global
`ApiCallCaps.global` coroutine cap (default 100) and the per-provider
`ProviderThrottle` host gate. The canonical acquisition order is
sub-cap → global → host; the engines reuse the same park-friendly batch helpers
as Fan-Meta and fan-out.

The cross-kind resume orchestrator (`SecondaryRunManager.resumeStaleRunsForReport`,
the app-start + 30 s background sweep) delegates to the fan-out, tournament,
judge-eval, and rank-the-translators engines' own `resumeStaleRunsForReport`.
**Compare is not in that central sweep** — it resumes alongside the others when its Manage screen opens
(`ui/report/manage/Nav.kt`). The single-call Meta/Rerank/Moderation resume and
the legacy "no data yet" fallback explicitly **skip** TOURNAMENT/JUDGES rows by
kind (their engines own them); COMPARE/TRANSRANK cells are skipped implicitly,
because their pending sentinel provider (`*workers`) fails the
`AppService.findById` check that path requires.

## Tournament

**Tournament** asks worker models to judge every pair of successful report
answers head-to-head, then folds the verdicts into a leaderboard.

For `N` report answers, the run creates `N(N-1)` ordered matches plus one
aggregate row. Each unordered pair is judged **twice**, once as A-vs-B
(`matchOrientation = 0`) and once swapped as B-vs-A (`matchOrientation = 1`), so
first-position bias cancels out. A match placeholder starts with the sentinel
`providerId = "*workers"` / `model = "*pending"` (`TOURNAMENT_PENDING_PROVIDER`
/ `TOURNAMENT_PENDING_MODEL`). When the worker fallback chain returns a valid
verdict, `recordTournamentMatch` overwrites the sentinel row with the actual
worker provider/model, tokens, cost, duration, trace filename, and verdict
content.

The prompt is the bundled `workers/tournament` internal prompt (category
`workers`, name `tournament`). It references the `tournament` swarm, so the
worker model is chosen at runtime by `WorkerRunner` (shuffled order, 429
fallback); the match identity does not include the worker until it settles.
Verdicts are parsed by `parseMatchVerdict` — it prefers the labelled
`verdict:` / `confidence:` / `reason:` lines the prompt asks for, with a strict
JSON fallback, normalising the verdict to `A` / `B` / `tie`. A reply with no
verdict is a logical miss, so the chain advances to the next worker.

Stored rows:

| Row | `SecondaryKind` | Sentinel provider/model | Key fields |
|---|---|---|---|
| Match | `TOURNAMENT` | `*workers` / `*pending` (until judged) | `tournamentRole="MATCH"`, `tournamentJudgeRunId`, `matchResponseAId`, `matchResponseBId`, `matchOrientation` |
| Aggregate | `TOURNAMENT` | `*tournament` / `aggregate` (`AGG_PROVIDER`/`AGG_MODEL`) | `tournamentRole="AGGREGATE"`, `tournamentMatrix`, ranked JSON in `content` |

> **Shared with TransRank.** The whole tournament cell/matrix field cluster —
> `tournamentRole` (`MATCH`/`AGGREGATE`), `tournamentJudgeRunId`,
> `tournamentMatrix` — plus the `recordTournamentMatch` storage helper are reused
> by the sibling `TRANSRANK` ("Rank the translators") feature. `recordTournamentMatch`
> takes an optional full `TokenUsage` (TransRank passes the whole usage so
> cached / reasoning tokens and the API-reported cost survive); when null it falls
> back to the legacy input/output-token-only shape Tournament uses. See
> [rank-translators.md](rank-translators.md) for the TransRank specifics; they are
> not duplicated here.

### Aggregation and the win matrix

After the matches settle, `recomputeAndPersistAggregate` folds the verdicts into
a `WinMatrix` and writes it (plus the ranked JSON for the selected method) onto
the single aggregate row.

The `[N]` ids are numbered by each participant's **stable position in
`report.agents`**, filtered to the fixed launch-time participant set — not by
the current SUCCESS set. This matters: `computeWinMatrix` silently drops
unresolved responses, so numbering through the live success set let a transient
status dip (mid-regenerate) shrink the matrix (the historic "9 of 39" bug). The
fixed numbering reproduces the launch-time ordering the View / Top-ranked scope
expect while staying immune to later status changes.

`WinMatrix` (`data/TournamentRanking.kt`) holds:

- `ids: List<Int>` — the 1-based `[N]` ids, the same the rerank JSON uses.
- `wins[i][j]` — `i`'s average fractional credit vs `j` across both
  orientations (A→1.0, B→0.0, tie→0.5; the swapped orientation is inverted).
  Two orientations that agree give 1.0/0.0, disagree-or-tie gives 0.5/0.5; a
  single present orientation takes that verdict; none means no contest (the pair
  is skipped).
- `games[i][j]` — the count of decided ordered judgments (0, 1, or 2) behind
  that average.
- `ties[i][j]` — the explicit draw count, for tie-aware methods.

The matrix and the method that produced the current ranking are serialised into
the `tournamentMatrix` sidecar via `WinMatrix.encode(method)`, so the View can
recompute and persist a different ranking **locally with no API calls**
(`setMethod` / `applyTournamentMethod` / `decodeTournamentMatrix`).

### Ranking methods

`rankFor(method, matrix)` dispatches to one of the **eleven** `TournamentMethod`
values (`COPELAND, ELO, DAVIDSON, TIDEMAN, MARKOV, SCHULZE, MINIMAX, COLLEY,
GLICKO2, POINTS, TRUESKILL2`); every method emits the same rerank-compatible
`[{id, rank, score, reason}]` JSON (`toRerankJson`, which coerces non-finite
scores to 0 and rounds numerically — never `"%.2f".format(x).toDouble()`, which
would crash on a comma-decimal locale).

| Method | What it does |
|---|---|
| **Copeland** | Win-count. `score = 100 · wins / played`, where `wins` is the model's total fractional wins and `played` is the number of opponents it **actually contested** (`games[i][j] > 0`), coerced to ≥ 1. This is a true per-model win-rate; it equals `n-1` only for a complete round-robin. Reason: `"Won %.1f of %d head-to-heads"` (pinned to `Locale.US`). |
| **Elo** | Replays each contested pair once in deterministic id order, K=32 from a 1500 base. Order-sensitive, so a weaker fit for a static round-robin. |
| **Davidson** | Tie-aware paired-comparison MLE using the explicit tie counts, 700 gradient iterations; score = fitted strength rescaled so the strongest = 100. Davidson is the only method that **triggers a rebuild** of the matrix from the match rows when the sidecar lacks explicit ties (`hasTieData == false`); TrueSkill2 also reads tie counts but uses whatever the decoded matrix carries (synthesized from wins/games when absent). |
| **Tideman** | Ranked Pairs — locks pairwise majorities strongest-first, skipping any edge that would close a cycle; scores by rank position in the resulting DAG. |
| **Markov** | Random-walk stationary distribution over the pairwise results (damping 0.92, ~500 power-iterations), rescaled so the strongest = 100. |
| **Schulze** | Beatpaths (Condorcet). Seeds each ordered pair with the winner's pairwise points, then Floyd–Warshall finds the strongest (widest) path between every pair; `i` outranks `j` when its strongest path beats `j`'s back. Ordering method — rank-based score, beatpath win count as the reason. |
| **Minimax** | Simpson–Kramer. Each response's worst pairwise defeat margin; the smallest-worst-defeat ranks first (a Condorcet winner's is 0). Ordering method. |
| **Colley** | Colley's bias-free rating (the sports/BCS method): solves `C·r = b` via Gauss–Jordan with partial pivoting; ratings centre near 0.5 and fold in schedule strength without margin bias, rescaled so the strongest = 100. Falls back to a plain win share if the solve degenerates. |
| **Glicko-2** | One rating period folding all of a response's games against the others (held at start-of-period ratings); volatility solved by Glickman's Illinois iteration. Score = the updated rating from a 1500 base. |
| **Points** | Plain head-to-head points tally (win = 1, draw = 0.5) summed over every contested pair. Unlike Copeland this is **not** a win-rate — playing (and winning) more matches counts for more. Score = the points total. |
| **TrueSkill2** | The 1-v-1 core of Microsoft's TrueSkill: a Bayesian μ/σ skill belief per response updated through every contested pair (draw margin estimated from the observed tie rate). Score = the conservative estimate μ − 3σ. |

> The Copeland denominator was previously a fixed `n-1` shared by all models,
> which scored an uncontested or errored pair like a loss and overstated the
> "head-to-heads" count in the reason. It is now the per-model contested-games
> count (commit `490d8b2e9`).

`assignRanks` sorts by score descending with an id-ascending tiebreak and
assigns ranks 1..N.

Manage-side drill-in:

- L1 groups by judge model or report model and shows total/done/run/wait/queue
  counts plus cost.
- L2 shows either a judge's matches or a report model's match list.
- L3 shows one match, verdict, confidence/reason, raw judge reply, cost, and a
  trace link when tracing was enabled.

View-side drill-in:

- Tournament appears as its own View tab (`ui/report/view/Tournament.kt`, with
  the podium in `TournamentPodium.kt`).
- The in-tab leaderboard's method toggle currently exposes only the first
  **five** methods (Copeland / Elo / Davidson / Tideman / Markov); switching is a
  pure local recompute via `applyTournamentMethod` (no API calls).
- The **podium** (`TournamentPodium.kt`) is the full surface: its
  `MethodSelector` lists **all eleven** methods (`TournamentMethod.entries`), and
  a "total" table shows each model's position across every method side-by-side,
  each column recomputed locally from the stored win matrix.
- Tapping a ranked model opens head-to-head cards, including A-vs-B/B-vs-A
  orientation switching and trace links.

The aggregate row's rerank-compatible `content` is consumed beyond the tournament
screens: it drives the Top-ranked scope (`extractTopRankedIds`) and feeds the
**Value view** (`ui/report/view/ValueView.kt`), which surfaces a "Tournament"
total ranking source plus one source per method and folds them into its weighted
"Combined" (0–1000) ranking alongside Rerank / TransRank — see
[value-view.md](value-view.md).

Trace category `after/tournament`; usage kind `tournament`.

## Judge the Judges

**Judge the judges** measures agreement between the concrete judge models named
by the `workers/tournament` prompt's swarm.

Instead of round-robin worker selection, **every** judge receives the **same**
random set of answer pairs, so per-cell verdicts can be cross-compared.
`JUDGE_MATCH_COUNT` is 25, capped by the number of distinct pairs a small
report can form (`allPairs.shuffled().take(JUDGE_MATCH_COUNT)`). The grid is:

```
judges × selected matches
```

Each selected match is judged **once** by each judge (unlike Tournament's
orientation-doubled pairs). Each cell is a **fixed-model** call: the row's
provider/model is the judge from the moment the placeholder is created (a direct
`analyzeWithAgent`-style call, not the round-robin chain), so the batch runs
fixed-host. The same `workers/tournament` prompt text and `parseMatchVerdict`
parser are reused, so Tournament and Judge-the-judges answer the same question
from different angles.

Stored rows:

| Row | `SecondaryKind` | Sentinel provider/model | Key fields |
|---|---|---|---|
| Cell | `JUDGES` | the judge's own provider/model (fixed at creation) | `tournamentRole="MATCH"` (`JUDGE_ROLE_CELL`), `tournamentJudgeRunId`, `matchResponseAId`, `matchResponseBId`, `matchOrientation` |
| Aggregate | `JUDGES` | `*judges` / `aggregate` (`AGG_PROVIDER`/`AGG_MODEL`) | `tournamentRole="AGGREGATE"` (`JUDGE_ROLE_AGGREGATE`), judge-agreement JSON in `content` |

The agreement layer (`data/JudgeAgreement.kt`) computes, per judge:

- agreement with the per-match consensus
- cost and total API time
- overall consensus strength
- per-match consensus and vote counts

Manage-side drill-in:

- L1 toggles between Judges and Matches.
- Judges mode shows per-judge progress while running and a consensus table
  after completion.
- Matches mode shows each selected pair, consensus, and vote counts.
- A judge can be added to the run; the model is also added to the underlying
  `tournament` swarm.
- Removing a judge deletes its cells and removes it from that swarm.
- Editing the swarm can trigger a rerun prompt if the current run no longer
  matches the active judge set.

Trace category `after/judges` (it reuses the tournament *prompt text* but tags
its own category); usage kind `judges`.

## Compare with Meta

**Compare with meta** scores how closely each primary report answer matches
selected Meta results.

The user selects one existing plain Meta row (a single tap, no multi-select).
There is **no separate prompt-selection step** any more — the former
`CompareSelectPromptScreen` was removed. Instead the launch (`Run.kt`)
auto-resolves the `meta_compare` prompt whose **name matches the picked meta
item's `metaPromptName`** (case-insensitive); the bundled prompt is
`meta_compare/summarize` (it pairs with the bundled `Summarize` meta). If the
resolved prompt's `modelSelection` is `*SELECT` (and report-models-as-workers is
off), a worker picker pops first. The run then creates:

```
successful answers × 1 (the one chosen meta row)
```

(`cellCountFor(agentCount, metaCount) = agentCount * metaCount`; the engine still
takes a `metaResultIds` list, but the UI only ever supplies one.) Each cell is
worker-judged through the prompt's worker list. The scoring worker is **dynamic
like Tournament**: placeholders start at the sentinel `*workers` / `*pending`
(`COMPARE_PENDING_PROVIDER` / `COMPARE_PENDING_MODEL`) and are overwritten with
the winning worker via `recordCompareCell`.

The cell prompt substitutes `@RESPONSE@` (the answer body) and
`@META_RESPONSE@` (the meta content, with its `## References` legend stripped via
`stripMetaReferenceLegend`). The reply is parsed by `parseSimilarityScore`,
which accepts the labelled `percentage:` form, JSON with
`percentage`/`percent`/`score`, or a first-number fallback, clamped to 0..100.

Stored rows:

| Row | `SecondaryKind` | Sentinel provider/model | Key fields |
|---|---|---|---|
| Cell | `COMPARE` | `*workers` / `*pending` (until scored) | `compareRunId`, `compareAgentId`, `compareToResultId`, `metaPromptId`, `metaPromptName` |

There is **no aggregate row** — L1 averages are computed directly from the
cells:

- L1 lists each report answer with its score against the single chosen
  meta result — no grouping toggle (the "Meta items" mode was removed).
  The L2 title is now always "Compare with meta - model".

L2 opens one group; L3 opens one cell with answer text, meta text, worker reply,
reason, cost, and trace.

Trace category `meta/compare`; usage kind `compare`.

## Persistence and deletion

The canonical store for all three features is
`<filesDir>/secondary/<reportId>/<resultId>.json` (one JSON file per
`SecondaryResult`, via `SecondaryResultStorage`). Runtime state is disposable
and always rebuildable from disk.

`SecondaryResultStorage.countForReport` counts TOURNAMENT and JUDGES via their
**AGGREGATE** rows only (the `N(N-1)` / `judges × matches` cells are inspection
detail), and COMPARE cells flat.

Deleting a report deletes these rows with every other secondary result. Deleting
a run removes only rows belonging to that run id. Restarting failed cells resets
the affected placeholders back to their sentinel (`resetTournamentMatch` /
`resetCompareCell`). A full redo creates a new run id.

## Cost and usage

Usage kinds and trace categories:

| Flow | Usage kind | Trace category |
|---|---|---|
| Tournament | `tournament` | `after/tournament` |
| Judge the judges | `judges` | `after/judges` |
| Compare with meta | `compare` | `meta/compare` |

Costs are recorded on each cell row from the worker or judge provider/model that
actually billed. The report cost table groups them as tournament, judges, or
compare rows. Deleted-cell spend is rolled into the report's
`costsFromDeletedItems` via `SecondaryResult.fullCost()`, which sums
`inputCost + outputCost` **plus** any per-row icon and title spend
(`iconInputCost + iconOutputCost + titleInputCost + titleOutputCost`) so the
lifetime total stays whole.

## Related files

- `data/SecondaryModels.kt` — the `SecondaryKind` enum, `SecondaryResult` (the
  flat row used for all kinds), `fullCost()`
- `data/SecondaryResult.kt` — `SecondaryResultStorage` (persistence,
  `countForReport`, the record/reset cell helpers)
- `data/TournamentRunModel.kt` — match/run types, `parseMatchVerdict`, pending
  sentinels
- `data/TournamentRanking.kt` — `WinMatrix`, `computeWinMatrix`, `rankFor` and
  the eleven ranking methods, `toRerankJson`, `applyTournamentMethod`,
  encode/decode of the matrix sidecar
- `data/JudgeEvalRunModel.kt` — judge cell/run types, `JUDGE_MATCH_COUNT`,
  role constants
- `data/JudgeAgreement.kt` — per-judge agreement aggregation
- `data/CompareRunModel.kt` — compare cell/run types, `parseSimilarityScore`,
  `cellCountFor`, pending sentinels
- `viewmodel/TournamentEngine.kt`
- `viewmodel/JudgeEvalEngine.kt`
- `viewmodel/CompareEngine.kt`
- `viewmodel/SecondaryRunManager.kt` — owns the cross-kind resume orchestrator
- `ui/report/manage/Tournament.kt`
- `ui/report/manage/JudgeEval.kt`
- `ui/report/manage/Compare.kt`
- `ui/report/view/Tournament.kt`
- `ui/report/view/TournamentPodium.kt`
- `ui/report/view/ValueView.kt` — consumes the tournament aggregate as ranking sources

See also:

- [rank-translators.md](rank-translators.md) — the sibling `TRANSRANK` feature
  that reuses this file's tournament cell/matrix machinery.
- [value-view.md](value-view.md) — the Value view that folds Tournament / Rerank /
  TransRank rankings into a weighted "Combined" score.
