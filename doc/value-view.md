# Value view — cost × quality frontier

The **Value view** plots every successful model in a report on a
cost × quality plane and marks the **best-value** model and the
**Pareto frontier**. It answers one question: *which model gives the
most quality for the least money?* It is a **pure local derivation** —
no API calls. Both inputs already live on disk: each agent's recorded
cost, and a per-model **ranking** chosen with a chip switch (the
report's Rerank, the Judge-the-judges consensus, any "Rank the
translators" run, a Compare-with-meta run, the Tournament Total, each
individual Tournament method, or the weighted **Combined** blend).
Switching the ranking re-ranks the chart, the 💎 best-value pick and
the list instantly, recomputing locally from stored verdicts / win
matrices.

Everything lives in `ui/report/view/ValueView.kt` (~1090 lines).

## What feeds it

| Input | Source on disk | Used for |
|---|---|---|
| Per-agent cost | `report.agents[*].cost` (or `inputCost + outputCost`) | the X (cost) axis |
| Fan-out response cost | META rows with `fanOutSourceAgentId != null` | folded into X when scope matches (below) |
| Rerank | newest `RERANK` `SecondaryResult.content` → `parseRerankRows` | a ranking source |
| Judge-the-judges | `JUDGES` cells → `judgesConsensusWinMatrix` → Copeland | a ranking source |
| Rank the translators | `TRANSRANK` cells → `aggregateTranslatorRanks`, one per language | one source per language |
| Compare with meta | latest `COMPARE` cell group (by `compareRunId`) → mean `percent` per agent | a ranking source |
| Tournament | newest `TOURNAMENT` AGGREGATE row's `tournamentMatrix` → `decodeTournamentMatrix` | the Total + each method |
| Ranking weights | `GeneralSettings.rankingWeights` (Settings → Ranking weights) | the Combined blend |

All ranking scores are **the ranking's own real score** (Rerank
0–100, Elo ~1500, Copeland win-rate, Combined 0–1000, …) — not a
rank position. The Y axis therefore auto-scales to whatever range the
selected source produces.

## Opening it

The Value view is a tile in the **View hub** (`ViewAiReportScreen`,
`ui/report/view/Main.kt`), reached from a report's result page. It uses
the same full-screen-overlay pattern as the **Costs** tile: tapping the
tile sets `showValueView = true`, and the hub renders
`ValueViewScreen(reportId, onBack)` in place of the tile grid and
`return`s (preserving the grid's `remember` state). Back returns to the
grid.

The **💎 "Value view" tile** (`MetadataIconsHolder.current.gem`,
`SuccessAccent`) is added only when the report has at least one ranking
to draw on (`Main.kt:967`):

```
everyItems["rerank"].orEmpty().isNotEmpty() || tournamentRows.isNotEmpty()
    || judgesRows.isNotEmpty() || transRankRows.isNotEmpty() || compareRows.isNotEmpty()
```

i.e. a Rerank, a Tournament, a Judge-the-judges run, a Rank-the-
translators run, **or** a Compare-with-meta run. Help topic is
`value_view` (`ui/admin/ReportsHelp.kt`). The title bar shows "Value
view" with a subject line that names the active source (e.g.
`Combined · weighted 0–1000`, `ranked by <model>`, `Judge the judges ·
consensus`, `Compare with meta · match %`, `Rank the translators ·
<lang>`, `Tournament · total of all methods`, `Tournament · Elo`).

If the report has rows but no ranking yields any points, the body shows
*"No ranking yet. Run a Rerank, Tournament, Judge-the-judges,
Rank-the-translators, or Compare-with-meta on this report first."*

## The ranking-source switch

A horizontally-scrolling row of chips (`SourceChip`) picks which ranking
feeds the quality axis. The sources are assembled in a fixed order
(`buildRankSources`, `ValueView.kt:328-343`):

| # | Chip label | `RankSource` | Quality score | Shown when |
|---|---|---|---|---|
| 1 | **Combined** | `Combined` | weighted 0–1000 blend (below) | any ranking has a non-zero weight |
| 2 | **Rerank** | `Rerank` | the rerank row's `score` (or `n−rank+1`) | a RERANK row exists |
| 3… | *language name* | `TransRank(runId, language)` | the model's average translator score (0–100) | one chip per "Rank the translators" run, sorted by language |
| · | **Judges** | `Judges` | Copeland win-rate over the panel **consensus** matrix | a Judge-the-judges run resolves ≥2 answers |
| · | **Compare** | `Compare` | each answer's mean match % (0–100) against the chosen meta | a Compare-with-meta run has at least one scored cell |
| · | **Tournament** | `TournamentTotal` | inverse of the model's **average position across all 7 methods** | a Tournament aggregate exists |
| · | **Copeland / Elo / Davidson / Markov / Schulze / Colley / Trueskill2** | `Tournament(method)` | that method's `rankFor` score | one chip per `TournamentMethod` value, same Tournament aggregate |

The 7 Tournament methods come straight from
`TournamentMethod.values()` (`data/TournamentRanking.kt:19`:
`COPELAND, ELO, DAVIDSON, MARKOV, SCHULZE, COLLEY, TRUESKILL2`); each
chip's label is the title-cased enum name. Tournament Total and each
method are **recomputed locally** from the stored `WinMatrix` — no
Tournament API calls (`rankFor`, `tournamentTotalRows`).

**Selection** (`ValueView.kt:534-544`) is a `rememberSaveable`
`selectedKey` keyed by `reportId`. The effective pick is: the user's
chip if still available → else **Rerank** → else the Tournament's
stored default method → else the first source. Note the default lands on
**Rerank** when present, even though **Combined** is the first chip.

## Quality per source

- **Rerank** — joins parsed rows through the saved source Agent IDs; a row with no
  `score` falls back to `n − rank + 1`.
- **Judges** — folds the latest Judge-the-judges run's cells through
  `judgesConsensusWinMatrix` (each match decided by the panel's
  plurality verdict), then ranks with **Copeland** win-rate
  (`rankFor(COPELAND, …)`) — the robust default for a plurality
  ranking. Requires a matrix of ≥2 answers.
- **Compare** — reduces the latest Compare-with-meta run's `COMPARE`
  cells (grouped by `compareRunId`, picking the most recently-updated
  group — there's no aggregate row) to each answer's mean match %
  (0–100) against the chosen meta result, the same average the Compare
  result screen's first column shows.
- **TransRank** — each "Rank the translators" run (`TRANSRANK` cells,
  one group per source translation run / language) is reduced to a
  `providerId|model → avgScore` map via `aggregateTranslatorRanks`. The
  chip maps each model's translator average onto the report's SUCCESS
  answer models by alias-resolved `provider|model` key; a model that
  was not a translator gets no row and drops off the plot. The chip
  label is the language's native name (`targetLanguageNative`). See
  [rank-translators.md](rank-translators.md).
- **Tournament method** — `rankFor(method, matrix).score`.
- **Tournament Total** — `tournamentTotalRows(matrix)`: for each model,
  average its rank across all 7 methods (`ranks.average()`), then
  `quality = n − avg + 1` so a lower mean position sits higher. Matches
  the Tournament screen's Total grid ordering.

After validating source revisions and joining stable identities, display rows use
the current 1-based SUCCESS position. Unknown legacy revisions and results whose
input answers changed are excluded from current-answer charts.

## Combined score + Ranking weights

Combined uses current, recorded answer revisions only. Rerank ordinal IDs are
first joined through the result's saved `sourceAgentIds` and then projected into
the current display order. Missing participants cannot shift another model's score.

Rerank, Judges, Score against meta, and Tournament are separate evidence families.
Each informative source is min-max normalized. The seven Tournament methods
are averaged *within* one family, whose weight is the largest selected method
weight. Adding correlated methods therefore does not multiply Tournament's vote.
Only models covered by every contributing informative family enter Combined.
Translation review scores remain separate: they measure translated passages,
not the quality of the original report answer.

Settings → Ranking weights exposes Rerank (default 3), Judges (6), Score against
meta (4), and the Tournament methods (2 each). Zero disables a contribution.
The old `translations` key is ignored by Combined. Weights remain sparse in
`GeneralSettings.rankingWeights` and reset by clearing the map.

## Cost axis — current answer attempt

The chart uses `ReportAgent.currentAttemptCost`, in cents. Lifetime ledger
spend and fan-out spend are separate accounting measures and are not attached
to the current answer's quality. Missing historical attempt cost stays unknown;
a known zero remains valid. Estimated token usage is marked with ≈. Selecting
an edited or chat replacement clears its old attempt cost and usage attribution.

## Pareto frontier

A priced point is dominated when another priced point is at least as good for
no greater cost, with one strict improvement. Every priced, non-dominated point
is highlighted on the Pareto frontier. There is no unique quality/cost-ratio
winner: arbitrary score origins cannot support that claim. Choose among frontier
points using the relevant score and your budget.

## The scatter chart (inline)

`ValueScatter` → `ValueScatterCanvas` (`ValueView.kt:706-947`) draws a
240 dp card:

- **X = Cost** (cheap on the left), **Y = the active ranking** (high
  quality at top — the pixel mapping inverts Y).
- Both axes are **padded 10 % of their range on each end** so no point
  ever sits on an axis line or the edge; ticks still show the real
  min / mid / max values (cost via `formatCents`, quality via
  `formatScore`).
- **Dots**: Pareto frontier = filled 12 px + a 20 px translucent halo
  (`SuccessAccent`); dominated = 7 px dim (`TextDim`); regular = 9 px
  (`WarningAccent`).
- **Axis names**: "Cost" centred under the X ticks; the ranking name
  rotated −90° down the left edge.

**Labels** are drawn on top of the dots with greedy collision
avoidance. Each label first tries the plain right-of-dot slot; an
isolated dot that would overlap something tries a ring of 10 candidate
positions (right/left/above/below, up- and down-shifted) and draws a
**leader line** back to its dot when it has to move. Near-coincident
dots (within `lineH·1.4`) are unioned into **clusters** (union-find) and
their labels **fanned symmetrically** around the centroid — top dot →
top label — each with its own leader line, so two stacked dots split one
label up and one down instead of one jumping far. Labels also dodge
every dot marker, not just each other. Inline thumbnails clip names to
14 chars; the full-screen graph shows full names in landscape.

## Full-screen graph

Tapping the chart opens `ValueGraphFullScreen` (`ValueView.kt:958-1052`)
— a **chrome-less, edge-to-edge** graph rendered in its **own `Dialog`
window** (`usePlatformDefaultWidth = false`, `decorFitsSystemWindows =
false`) so it covers the app's title bar and bottom icon bar (a plain
early-return overlay would still sit inside them). The dialog window's
Android system bars are hidden for its lifetime and restored on dismiss
via `WindowInsetsControllerCompat` in a `DisposableEffect`.

- **Pinch to zoom** (1×–8×) and **drag to pan**, applied via a
  `graphicsLayer`; zoom/pan reset whenever the ranking changes (keyed on
  the Y-axis title).
- **Tap the left half → previous ranking, right half → next**, wrapping
  with no edge (`cycleSource`, which also keeps the underlying chip
  selection in step).
- In **landscape** the labels show full model names at 1.35× size; the
  full-screen renderer also uses a larger label font (27 px vs 22 px)
  and the cluster-fan / leader-line layout.
- Full-screen labels are also run through `shortModelName2` and drop
  their `-preview` / `-exp` channel tag when that stays unique among the
  plotted points — ~7 catalog pairs (e.g. `gemini-2.5-pro` vs
  `…-pro-preview`) would otherwise collide onto one label, so a preview
  and its GA twin each keep their tag when both are on the chart.
- Back (or the system back press) dismisses the dialog.

## The list below the chart

Below the chart and the best-value line, every point is listed
(`ValueRow`) sorted **best-value first, then non-dominated, then quality
descending** (`ValueView.kt:675-681`). Each card shows `provider ·
model` and a monospace `<$cost> · score <q>` line, plus a badge:

| Badge | Colour | Meaning |
|---|---|---|
| 💎 Pareto frontier | `SuccessAccent` | a priced, non-dominated option |
| Pareto | `InfoAccent` | on the frontier (not dominated) |
| dominated | `TextDim` | another model is at least as good for less |

`formatScore` (`ValueView.kt:1087`) prints whole numbers without a
decimal (e.g. Elo `1500`) and fractional scores to one place
(`Locale.US`, never a comma-decimal round-trip).

## Recomputation & persistence

The view holds **no persistent state of its own**. `produceState` loads
the report and secondary rows off the IO dispatcher, keyed on
`reportId` + `ReportDataVersion` + `SecondaryDataVersion`, so any edit
to the report or its secondary results reloads it. `combinedRows`,
`tournamentTotalRowList`, the source list, the points and the best pick
are all `remember`-derived and recompute when the loaded data, the
selection, or the ranking weights change. Nothing is written back — the
Value view is read-only and makes **no API calls**.

## Related files

- `ui/report/view/ValueView.kt` — the whole screen: `ValueViewScreen`,
  `buildValuePoints`, `buildCombinedRows`, `buildRankSources`,
  `rowsForSource`, `tournamentTotalRows`, `ValueScatterCanvas`,
  `ValueGraphFullScreen`, `ValueRow`, the `RankSource` sealed class.
- `ui/report/view/Main.kt` — the View hub; the conditional 💎 tile
  (`Main.kt:963-969`) and the overlay mount (`Main.kt:197-209`).
- `viewmodel/AppViewModelTypes.kt` — `GeneralSettings.rankingWeights`,
  `rankingWeight`, `RANKING_WEIGHT_DEFAULTS`.
- `ui/settings/SettingsScreen.kt` — `RankingWeightsSubScreen` /
  `RankingWeightCard` / `RankingWeightSlider` (the two-card 0–10
  sliders).
- `data/TournamentRanking.kt` — `TournamentMethod`, `WinMatrix`,
  `rankFor`, `decodeTournamentMatrix`.
- `data/JudgeAgreement.kt` — `judgesConsensusWinMatrix`.
- `data/CompareRunModel.kt` — `CompareCellState`, `toCompareCellState`.
- `ui/admin/ReportsHelp.kt` — the `value_view` help page.

## Related docs

- [costs.md](costs.md) — how the per-agent and fan-out costs the X axis
  reads were computed and recorded.
- [tournament-judges-compare.md](tournament-judges-compare.md) — the
  Tournament `WinMatrix` / `rankFor` machinery, Judge-the-judges
  consensus, and Compare-with-meta scoring the quality sources reuse.
- [rank-translators.md](rank-translators.md) — the TRANSRANK runs that
  become the per-language quality sources.
- [secondary-results.md](secondary-results.md) — Rerank, fan-out and the
  other secondary results that supply the rankings and the folded cost.
