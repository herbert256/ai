# Value view — cost × quality frontier

The **Value view** plots every successful model in a report on a
cost × quality plane and marks the **best-value** model and the
**Pareto frontier**. It answers one question: *which model gives the
most quality for the least money?* It is a **pure local derivation** —
no API calls. Both inputs already live on disk: each agent's recorded
cost, and a per-model **ranking** chosen with a chip switch (the
report's Rerank, the Judge-the-judges consensus, any "Rank the
translators" run, the Tournament Total, each individual Tournament
method, or the weighted **Combined** blend). Switching the ranking
re-ranks the chart, the 💎 best-value pick and the list instantly,
recomputing locally from stored verdicts / win matrices.

Everything lives in `ui/report/view/ValueView.kt` (~955 lines).

## What feeds it

| Input | Source on disk | Used for |
|---|---|---|
| Per-agent cost | `report.agents[*].cost` (or `inputCost + outputCost`) | the X (cost) axis |
| Fan-out response cost | META rows with `fanOutSourceAgentId != null` | folded into X when scope matches (below) |
| Rerank | newest `RERANK` `SecondaryResult.content` → `parseRerankRows` | a ranking source |
| Judge-the-judges | `JUDGES` cells → `judgesConsensusWinMatrix` → Copeland | a ranking source |
| Rank the translators | `TRANSRANK` cells → `aggregateTranslatorRanks`, one per language | one source per language |
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
to draw on (`Main.kt:963`):

```
everyItems["rerank"].isNotEmpty() || tournamentRows.isNotEmpty()
    || judgesRows.isNotEmpty() || transRankRows.isNotEmpty()
```

i.e. a Rerank, a Tournament, a Judge-the-judges run, **or** a Rank-the-
translators run. Help topic is `value_view` (`ui/admin/ReportsHelp.kt`).
The title bar shows "Value view" with a subject line that names the
active source (e.g. `Combined · weighted 0–1000`, `ranked by <model>`,
`Judge the judges · consensus`, `Rank the translators · <lang>`,
`Tournament · total of all methods`, `Tournament · Elo`).

If the report has rows but no ranking yields any points, the body shows
*"No ranking to compare. Run a Rerank, Tournament, or Judge-the-judges
on this report first."*

## The ranking-source switch

A horizontally-scrolling row of chips (`SourceChip`) picks which ranking
feeds the quality axis. The sources are assembled in a fixed order
(`ValueView.kt:425-439`):

| # | Chip label | `RankSource` | Quality score | Shown when |
|---|---|---|---|---|
| 1 | **Combined** | `Combined` | weighted 0–1000 blend (below) | any ranking has a non-zero weight |
| 2 | **Rerank** | `Rerank` | the rerank row's `score` (or `n−rank+1`) | a RERANK row exists |
| 3… | *language name* | `TransRank(runId, language)` | the model's average translator score (0–100) | one chip per "Rank the translators" run, sorted by language |
| · | **Judges** | `Judges` | Copeland win-rate over the panel **consensus** matrix | a Judge-the-judges run resolves ≥2 answers |
| · | **Tournament** | `TournamentTotal` | inverse of the model's **average position across all 11 methods** | a Tournament aggregate exists |
| · | **Copeland / Elo / Davidson / Tideman / Markov / Schulze / Minimax / Colley / Glicko2 / Points / Trueskill2** | `Tournament(method)` | that method's `rankFor` score | one chip per `TournamentMethod` value, same Tournament aggregate |

The 11 Tournament methods come straight from
`TournamentMethod.values()` (`data/TournamentRanking.kt:19`:
`COPELAND, ELO, DAVIDSON, TIDEMAN, MARKOV, SCHULZE, MINIMAX, COLLEY,
GLICKO2, POINTS, TRUESKILL2`); each chip's label is the title-cased enum
name. Tournament Total and each method are **recomputed locally** from
the stored `WinMatrix` — no Tournament API calls (`rankFor`,
`tournamentTotalRows`).

**Selection** (`ValueView.kt:440-450`) is a `rememberSaveable`
`selectedKey` keyed by `reportId`. The effective pick is: the user's
chip if still available → else **Rerank** → else the Tournament's
stored default method → else the first source. Note the default lands on
**Rerank** when present, even though **Combined** is the first chip.

## Quality per source

- **Rerank** — uses the parsed rerank rows directly; a row with no
  `score` falls back to `n − rank + 1`.
- **Judges** — folds the latest Judge-the-judges run's cells through
  `judgesConsensusWinMatrix` (each match decided by the panel's
  plurality verdict), then ranks with **Copeland** win-rate
  (`rankFor(COPELAND, …)`) — the robust default for a plurality
  ranking. Requires a matrix of ≥2 answers.
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
  average its rank across all 11 methods (`ranks.average()`), then
  `quality = n − avg + 1` so a lower mean position sits higher. Matches
  the Tournament screen's Total grid ordering.

All sources are reshaped to **rerank-shaped rows** whose `id` is the
1-based SUCCESS position — the same numbering Rerank, Tournament and
`buildValuePoints` all use, so a score always lines up with the right
agent.

## Combined score + Ranking weights

**Combined** is a single 0–1000 score blending every available, non-
zero-weighted ranking (`buildCombinedRows`, `ValueView.kt:218-271`):

1. Gather each contributing ranking as `(weight, id→rawScore)`:
   - `rerank` weight → rerank scores;
   - `judges` weight → Copeland scores over the consensus matrix;
   - `translations` weight → **average across all translator runs** of
     each model's translator score (averaging every language, unlike the
     per-language TransRank chips);
   - each `TournamentMethod.name` weight → that method's scores.
2. **Min-max normalise** each ranking's scores to 0–1 independently (a
   flat ranking where `max == min` maps every model to 0.5).
3. **Weight-average** per model: `Σ(w·norm) / Σ(w)`.
4. Scale ×1000.

A ranking weighted **0** is dropped; Combined is empty (and the chip
absent) when nothing is weighted.

**Weights** are integers 0–10, edited on **Settings → Ranking weights**
(`SettingsSubScreen.SETTINGS_RANKING_WEIGHTS`, `RankingWeightsSubScreen`,
`ui/settings/SettingsScreen.kt:1935`, help `settings_ranking_weights`).
Two cards of 0–10 sliders: **"Rerank · Judges · Translations"** (keys
`rerank`, `judges`, `translations`) and **"Tournament rankings"** (one
slider per `TournamentMethod`, keyed by the uppercase enum name). The
🧽 icon resets to factory defaults by clearing the map.

Weights are stored **sparsely** in `GeneralSettings.rankingWeights`
(`viewmodel/AppViewModelTypes.kt:206`); a missing key resolves through
`GeneralSettings.rankingWeight(key)` to `RANKING_WEIGHT_DEFAULTS`
(`AppViewModelTypes.kt:125`), else 0:

| Key | Default |
|---|---|
| `rerank` | 3 |
| `judges` | 6 |
| `translations` | 6 |
| `ELO` / `DAVIDSON` / `TIDEMAN` | 4 each |
| `COPELAND`, `MARKOV`, `SCHULZE`, `MINIMAX`, `COLLEY`, `GLICKO2`, `POINTS`, `TRUESKILL2` | 0 |

So out of the box Combined blends Rerank, Judges, Translations, Elo,
Davidson and Tideman; Copeland and the remaining Tournament methods are
off until the user raises their slider.

The Value view reads the weights live via
`LocalGeneralSettings.current.rankingWeight(key)` and recomputes
`combinedRows` whenever `generalSettings` changes — change a slider and
Combined re-blends without leaving the screen.

> **Persistence.** `saveGeneralSettings` stores `rankingWeights` sparsely
> as JSON under the `ranking_weights` key in `eval_prefs` (omitted when the
> map is empty), and `loadGeneralSettings` reads it back, falling back to
> `RANKING_WEIGHT_DEFAULTS` for any missing key. The weights survive a
> process restart and ride along in the backup (`eval_prefs` is archived).
> The 🧽 clear resets to factory defaults by emptying the map.

## Cost axis — base cost + fan-out fold-in

Each point's cost (`buildValuePoints`, `ValueView.kt:160-198`) is the
agent's own spend plus an optional fan-out fold-in, expressed in cents
(USD × 100):

- **Base** = `agent.cost ?: (inputCost ?: 0 + outputCost ?: 0)`.
- **`costKnown`** distinguishes a real **$0** (free / local model — still
  eligible for best value) from *no price at all*: `knownBase` is
  `cost ?: inputCost ?: outputCost`, null only when the agent reports no
  price. A point with unknown cost still plots (at cost 0) but is
  excluded from the best-value contest (it would score `quality/ε ≈ ∞`
  and steal the badge).

**Fan-out fold-in** (`ValueView.kt:358-389`) adds each model's fan-out
**response** spend on top of its main answer — but **only when the
fan-out scope matches the report's models**, so the comparison stays
fair. The map is built only when **all** hold:

- there is at least one fan-out pair (`META` row with
  `fanOutSourceAgentId != null`);
- the success-model set is non-empty;
- **no two success agents share a `provider|model` key**
  (`noDuplicateModels`) — duplicates collapse to one key and the per-key
  fan-out total would be double-assigned to both agents (audit bug 11);
- the answerer key set **equals** the success-model key set
  (`answererKeys == successKeys`) — i.e. exactly the report's own models
  answered the fan-out.

When all match, fan-out cost is summed per answerer key
(`(inputCost ?: 0) + (outputCost ?: 0)`, alias-resolved, case-
insensitive — the same `provider|model` key the FanOutEngine hydration
uses) and assigned to each agent. Icon/title Fan-Meta spend is excluded
(those aren't responses). When the fold is active, `includesFanOut` is
true and the caption appends *"Cost includes each model's fan-out
responses (every model here also answered the fan-out)."* See
[secondary-results.md](secondary-results.md) for fan-out itself and
[costs.md](costs.md) for how these costs were recorded.

## Pareto frontier + best value

For each point P (`ValueView.kt:189-197`):

- **dominated** = some other point is at least as good for the same or
  less money: `∃ o ≠ P : o.quality ≥ P.quality ∧ o.costCents ≤
  P.costCents`, strict on at least one. Dominated points are dimmed; the
  non-dimmed points form the **Pareto frontier**.
- **best value** = among `costKnown`, Pareto-undominated points, the one
  maximising `quality / max(costCents, ε)` (ε = 1e-6). Exactly one point
  (or none, if every priced point is dominated) gets the 💎.

A green summary line names the winner: *"💎 Best value: <provider> ·
<model> — score <q> at <$cost>"*.

## The scatter chart (inline)

`ValueScatter` → `ValueScatterCanvas` (`ValueView.kt:608-835`) draws a
240 dp card:

- **X = Cost** (cheap on the left), **Y = the active ranking** (high
  quality at top — the pixel mapping inverts Y).
- Both axes are **padded 10 % of their range on each end** so no point
  ever sits on an axis line or the edge; ticks still show the real
  min / mid / max values (cost via `formatCents`, quality via
  `formatScore`).
- **Dots**: best value = filled 12 px + a 20 px translucent halo
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

Tapping the chart opens `ValueGraphFullScreen` (`ValueView.kt:845-918`)
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
- Back (or the system back press) dismisses the dialog.

## The list below the chart

Below the chart and the best-value line, every point is listed
(`ValueRow`) sorted **best-value first, then non-dominated, then quality
descending** (`ValueView.kt:578-585`). Each card shows `provider ·
model` and a monospace `<$cost> · score <q>` line, plus a badge:

| Badge | Colour | Meaning |
|---|---|---|
| 💎 Best value | `SuccessAccent` | the single best quality-per-cost pick |
| Pareto | `InfoAccent` | on the frontier (not dominated) |
| dominated | `TextDim` | another model is at least as good for less |

`formatScore` (`ValueView.kt:953`) prints whole numbers without a
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
  `buildValuePoints`, `buildCombinedRows`, `tournamentTotalRows`,
  `ValueScatterCanvas`, `ValueGraphFullScreen`, `ValueRow`, the
  `RankSource` sealed class.
- `ui/report/view/Main.kt` — the View hub; the conditional 💎 tile
  (`Main.kt:959-965`) and the overlay mount (`Main.kt:197-209`).
- `viewmodel/AppViewModelTypes.kt` — `GeneralSettings.rankingWeights`,
  `rankingWeight`, `RANKING_WEIGHT_DEFAULTS`.
- `ui/settings/SettingsScreen.kt` — `RankingWeightsSubScreen` /
  `RankingWeightCard` / `RankingWeightSlider` (the two-card 0–10
  sliders).
- `data/TournamentRanking.kt` — `TournamentMethod`, `WinMatrix`,
  `rankFor`, `decodeTournamentMatrix`.
- `data/JudgeAgreement.kt` — `judgesConsensusWinMatrix`.
- `ui/admin/ReportsHelp.kt` — the `value_view` help page.

## Related docs

- [costs.md](costs.md) — how the per-agent and fan-out costs the X axis
  reads were computed and recorded.
- [tournament-judges-compare.md](tournament-judges-compare.md) — the
  Tournament `WinMatrix` / `rankFor` machinery and Judge-the-judges
  consensus the quality sources reuse.
- [rank-translators.md](rank-translators.md) — the TRANSRANK runs that
  become the per-language quality sources.
- [secondary-results.md](secondary-results.md) — Rerank, fan-out and the
  other secondary results that supply the rankings and the folded cost.
