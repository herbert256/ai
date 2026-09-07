# Value view — answer cost and evaluation criteria

Value is a local comparison of recorded answer attempts. Its X axis is `ReportAgent.currentAttemptCost`; its Y axis is the selected evaluation criterion. It marks every nondominated point on the Pareto frontier. A highlighted **Frontier example** is a navigation aid, not a universal winner or a score-per-dollar recommendation.

## Eligible evidence

| Source | Interpretation |
|---|---|
| Question relevance | The saved rerank rubric or query relevance; not factual correctness |
| Panel preference | The judge panel's plurality verdicts on the answers, summarized with Copeland |
| Reference agreement | Mean agreement with selected reference texts under the saved Compare rubric |
| Tournament / methods | Alternative summaries of the same pairwise judgments |
| Custom preference | A user-weighted blend of informative evidence families |

Translation reviews are excluded from both the selectable sources and the Custom preference calculation. Their scores describe translations, while this chart prices original answers. Fan-out spend is also excluded from the answer cost axis. Lifetime report cost remains available in Costs.

Historical or unknown source revisions do not qualify as current evidence. Unknown attempt cost is excluded. A manually applied replacement answer clears its old attempt attribution; the app does not pretend that its predecessor's price belongs to it. Estimated usage is marked.

## Custom preference

Settings → Ranking weights controls four families: question relevance, panel preference, reference agreement, and tournament. A zero weight excludes a family. Tournament method sliders only determine the blend inside the tournament family; the family's separate slider determines its influence against other families.

Only participants present in every informative included family receive a score. Missing evidence cannot improve an average. Families with no score variation are excluded. Each family is min-max normalized and the weighted mean is displayed on a 0–1000 scale. This is a preference scale, not a calibrated measure of quality. Small raw differences can become large normalized differences.

The **Basis** button on each row explains raw scores, observed ranges, normalized values and effective weights. The screen lists excluded participants. HTML export includes the same calculation details.

## Navigation and persistence

Open the Value tile in the expert View grid. Source changes and weight changes recompute locally; they do not call a model. The graph, frontier and rows share `ValueView.kt`; `ValueViewExport.kt` uses the same source and point builders. Settings stores `rankingWeights`, including the `tournament` family key and method keys. No Value result is persisted as a new secondary.

See [functional fixes](functional-audit-remediation-2026-09-07.md), [costs](costs.md), and [tournament methods](tournament-judges-compare.md).
