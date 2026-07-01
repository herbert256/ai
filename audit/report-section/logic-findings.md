# Report-section audit — LOGIC errors (not technical)

Date: 2026-07-02. HEAD after the technical-audit fixes (34f25afc5). A
9-agent fleet audited domain-logic correctness — "the code runs fine but
computes the WRONG ANSWER" — as opposed to the technical bugs (races,
crashes, cost-accounting, leaks) fixed in the prior pass. Each agent
hand-traced concrete examples through the code against the canonical
algorithm / doc intent; two of the top findings were re-verified by the
orchestrator directly in the code (marked ✔hand-verified).

**Headline:** the tournament ranking engine (all 7 methods) is
mathematically SOUND — two independent agents with Python replicas +
13k+ stress matrices found no ranking-inverting error. The cost/token
arithmetic is SOUND (full numeric trace matched). The real logic errors
are in **scope selection**, **verdict/score parsing**, **fan-in
combining**, and the **Value-view best-value/blend** — places where the
answer is silently wrong, not where the app breaks.

Counts: 1 HIGH, 7 MEDIUM, 14 LOW.

---

## HIGH

### LG1 [HIGH] ✔hand-verified — TopRanked scope selects the WRONG models after any success-set drift
- **Where:** `viewmodel/SecondaryRunManager.kt:1364-1376` (`runMetaPrompt`), and the same pattern at `SecondaryRunManager.kt:974-984` (resume), `MetaEditManager.kt:153-162` (cascade), `FanOutEngine.kt:1100-1105` (fan-out sources), `FanOutEngine.kt:484-489` (fan-out @COUNT@).
- **Intended:** `TopRanked(count, rerankResultId)` stores only the rerank id + N. `extractTopRankedIds` returns the top-N **positions in the rerank's own numbering** — positions into the success set *at rerank time*, which the rerank row froze as `sourceAgentIds` (added for DISPLAY in the technical audit, G2). To pick the right models each position `p` must map `rerank.sourceAgentIds[p-1]` → agentId → the agent's *current* success position, exactly as the `Manual` branch does (1377-1385).
- **What it does instead:** all five sites use the rerank-relative positions **directly** as indices into the *current* success list (`ids.filter { it in 1..nSuccess }`). None reads `.sourceAgentIds`. The only guard is an out-of-range clamp; an in-range-but-now-different position passes silently.
- **Trace:** report [A,B,C,D,E], rerank ranks D,B,E top-3 and freezes sourceAgentIds=[A,B,C,D,E]. Remove agent A (rank 4). Current success=[B,C,D,E]. Pick "Top 3" → extractTopRankedIds→[4,2,5], clamp→{4,2} (5 dropped), includeIds={2,4}, @COUNT@=2. buildResultsBlock emits [2]=C,[4]=E — the meta is fed **C (the rerank's WORST) and E**, not the real top-3 D,B,E which are all still present. The user asked for the 3 best and got 2 of the wrong ones, billed.
- **Why HIGH:** the wrong subset is fed to a meta/fan-out (wrong output + wasted spend), it's silent, and it's the exact drift the audit's G2 fix acknowledged for *display* but left unfixed for *selection* — so display and selection now diverge. Fix: map positions through the rerank's `sourceAgentIds` snapshot, mirroring `Manual`.

---

## MEDIUM

### LG2 [MEDIUM] ✔hand-verified — verdict parser mistakes a leading article "a"/"b" for the verdict, inverting the winner
- **Where:** `data/TournamentRunModel.kt:194,196` (`normaliseVerdict`, `startsWith("a ")` / `startsWith("b ")`), folded into Tournament (`computeWinMatrix`) and Judge-the-judges.
- **What it does:** `verdict: A win for B` → `substringAfter(":")`="A win for B" → lowercased "a win for b" → `startsWith("a ")` true → returns **"A"**. The judge said B; recorded as an A win — winner inverted. `verdict: A slight edge to B` and any sentence beginning with the indefinite article invert the same way; `verdict: A tie` → "A" turns a tie into a slot-A win (biasing the exact slot the double-orientation run exists to cancel).
- **Accepted downstream:** the accept predicate is `parseMatchVerdict(...)?.verdict != null` (`TournamentEngine.kt:340`, `JudgeEvalEngine.kt:384`), so the mis-normalised "A" is folded into `wins[a][b]`.
- **Why:** the heuristic keys off the first token being the *letter* a/b, but in prose that's usually the article. Bare "A"/"B"/"tie" (what the prompt asks for) parse correctly; verbose sentence verdicts — which the parser deliberately tries to handle — can invert. Fix: require the a/b token to actually be the verdict (exact/quoted/"response a"/"first"), and treat a string mentioning BOTH options as a tie rather than picking the leading article.

### LG3 [MEDIUM] Fan-in combines the full UNSCOPED success set, ignoring the fan-out's scope, and overstates its counts
- **Where:** `viewmodel/SecondaryRunManager.kt:1141` (`successful = all SUCCESS`), :1174, :1196-1201 (`count = perReport.size`, `fanOutCount = size-1`); `runFanInPrompt` takes no scope (:1038-1055, wired `Nav.kt:653`).
- **Intended:** the fan-in prompt says "Here are @COUNT@ reports, with @FAN_OUT_COUNT@ responses each" and should describe the reports the fan-out actually processed (per source agent, populated by its fan-out reactions).
- **What it does:** builds `perReport` from every successful agent regardless of the fan-out's `scopeChoice`/`responderIds`; out-of-scope sources are kept with an empty response list and `resolveFanInPrompt` (`SecondaryResult.kt:1379-1384`) appends a `***Report*** <body>` block even when empty. Trace: fan-out Manual sources {B,C} → fan-in still iterates [A,B,C], injects an empty report-A block, says "3 reports, 2 responses each" (should be 2). With restricted answerers, @FAN_OUT_COUNT@ overstates "N-1 each" while each block shows fewer.
- **Why:** count tokens and shown data disagree; out-of-scope reports appear as empty blocks. Happy path (AllReports, all answerers, no failures) is correct → MEDIUM. Fix: derive the source set + counts from the actual fan-out rows.

### LG4 [MEDIUM] Value view — unknown-cost points act as phantom cost-0 Pareto dominators, stealing/suppressing the 💎
- **Where:** `ui/report/view/ValueView.kt:202-210` (best-value + dominated), fed by :191-195 (`costCents`/`costKnown`).
- **Intended:** doc/value-view.md — a model reporting NO price plots at cost 0 as a placeholder but is "excluded from the best-value contest." It must not DECIDE the frontier.
- **What it does:** `costKnown` gates only *candidate eligibility* (`.filter { it.costKnown }`); the dominance predicate ranges over the ENTIRE `raw` list including unknown-cost points, whose `costCents` is a fake 0. So an unknown-price model dominates every priced model of ≤ quality, dimming genuinely non-dominated priced models and moving/erasing the 💎. Trace: X=unknown q95, Y=$5 q96, Z=$0.01 q50 → X (fake cost 0) dominates Z, badge moves from the true best-value Z to Y; if X is global-max quality the badge can vanish entirely.
- **Fix:** exclude unknown-cost points from the dominator set too (both the candidate filter and the `o`-loop), not just from the candidate set.

### LG5 [MEDIUM] Value view — Combined blend rewards a model ABSENT from a ranking over one that participated and scored last
- **Where:** `ValueView.kt:296-300` (num/den), with :294 (flat→0.5) amplifying.
- **What it does:** the renormalisation is arithmetically correct, but coming LAST normalises to 0.0 (contributes 0 to num, adds w to den → dilutes toward 0) while being ABSENT contributes to neither (stays at the model's other-ranking average). So participating-and-losing scores strictly below skipping the ranking. Trace: with a heavily-weighted low-information ranking, a model absent from it beats one that came last on it, flipping the Combined winner. Routine via partial-participation sources (translations/compare).
- **Why:** Combined 0-1000 scores compare models scored on different ranking subsets; "absent beats last" is a genuine missing-data reward. Spec-level (matches the doc's renormalisation description) but real. Fix is a design call (e.g. impute a floor for non-participation, or only Combine over rankings all compared models share).

### LG6 [MEDIUM] Parameter resolution is wholesale-LEVEL, not per-field — a per-call temperature drops the agent/app-wide maxTokens
- **Where:** `viewmodel/ReportViewModel.kt:630-635` (agent), :658-663 (direct/swarm); within-level merge `model/SettingsModels.kt:1003-1023`.
- **What it does:** `selParams ?: agentPreset ?: appPar ?: AgentParameters()` — the FIRST non-null level wins entirely. A selection preset that sets only `temperature` is non-null, so the chain short-circuits and the agent-level `maxTokens=1000` + app-wide `maxTokens=2000` are both discarded → resolved `maxTokens=null`. Selection ids and agent ids are stored as SEPARATE lists, never concatenated before `mergeParameters`, so no per-field cross-level fallback happens.
- **Ambiguity:** the code matches doc/parameters.md's DETAILED level-precedence tables exactly, but contradicts its one-line summary "Report generation **merges** down this chain" (parameters.md:322), which promises per-field. So this is either a code defect or a doc contradiction — the resolved value is surprising either way. Needs a design decision: is a preset a wholesale choice, or a per-field overlay?

### LG7 [MEDIUM] Auto-created Rerank fires on a 1-answer report (ranks a single item)
- **Where:** `ReportViewModel.kt:772` guard (`successCount < 1`, shared with moderation) → :779 `runRerank`; `runRerank`'s own floor is only `== 0` (`SecondaryRunManager.kt:279-282`).
- **What it does:** a report finishing with exactly 1 SUCCESS agent auto-creates a RERANK and dispatches a worker call to "rank" one answer → `[{id:1,rank:1}]`. Every single-model report (common) wastes one call + leaves a junk Rerank row; a later TopRanked meta pointed at it is degenerate.
- **Fix:** split the rerank threshold to `< 2` from moderation's `< 1` (a rerank needs ≥2 answers to be meaningful; moderation is fine at 1).

### LG8 [MEDIUM] "Regenerate everything" never re-runs COMPARE / JUDGES / TRANSRANK — they go stale over refreshed upstream
- **Where:** `RegenerateBatchEngine.buildTaskList` (:804-966) emits phases up to TOURNAMENT; `RegeneratePhase` enum (`RegenerateBatch.kt:20-67`) has no COMPARE/JUDGES/TRANSRANK; `dispatchPhase` only re-invokes the tournament engine (:686). Their engines' `resumeStaleRunsForReport` re-dispatch only *interrupted* cells, and `maybeAutoCreate*` runs only on fresh generation.
- **What it does:** after a prompt edit + Regenerate, answers/metas/tournament/translations refresh, but any Compare-with-meta / Judge-the-judges / Rank-the-translators result still scores the OLD answers — silently stale next to fresh content. TOURNAMENT got a dedicated phase; its three siblings didn't.
- **Why:** doc/regenerate.md defines Regenerate as re-running "every secondary result." Medium because it could be argued as a deliberate "don't auto-rerun the expensive meta-analyses" tradeoff — but the doc + TOURNAMENT's inclusion argue defect. Fix: add COMPARE/JUDGES/TRANSRANK regenerate phases (or at minimum mark them stale in the UI).

---

## LOW

### LG9 [LOW] Copeland lacks the n==1 special-case → lone model scores 0, not 100 (the other 6 methods return 100)
- `TournamentRanking.kt:116-132` — only `n==0` guarded; `n==1` → score `100·0/1 = 0`. Inconsistent across methods; feeds the Value view Combined fold. Reachable when all but one primary response fail to resolve.

### LG10 [LOW] Near-tie ranking resolves on pre-rounded (0.1) scores in Markov/Colley/Davidson/TrueSkill but full precision in Copeland/Schulze
- `markov:254-256`, `colley:361` round into `RankScored` before `assignRanks` (:500-503); two models within 0.05 collapse and resolve by id-ascending, which can invert their true order — inconsistently with Copeland/Schulze. Debatable (id tiebreak is arguably more stable than sub-0.1 iterative noise).

### LG11 [LOW] TrueSkill conflates a 1-1 orientation split with a genuine tie (two spots)
- `TournamentRanking.kt:417-418` (draw flag `|points(i,j)−points(j,i)|<1e-9` fires for a split) vs :406-411 (draw-rate excludes it); and the legacy-sidecar synthesis `:631-635`/`:39-46` labels every `wins==0.5` pair fully tied. Only shifts the global draw margin, never inverts a ranking; sidecar case is legacy-only (Davidson is protected by the rebuild, TrueSkill isn't).

### LG12 [LOW] Verdict parser doesn't recognize "Response 1"/"Response 2" — a decisive verdict is downgraded to "tie"
- `TournamentRunModel.kt:190-199` — "response 1 is better" matches none of the A/B signals → `else -> "tie"`. Signal lost (not inverted), safe direction.

### LG13 [LOW] extractTopRankedIds drops UNRANKED entries — "top N" can return fewer than N
- `data/RerankModerationApi.kt:289-294` — if any row has a rank, rows without a rank are discarded before `take(count)`. A partial-rank payload (ranks 2 of 5) returns 2 for "Top 3". Deliberate tradeoff (avoids a MAX_VALUE sort no-op) but under-selects.

### LG14 [LOW] Score parsers grab the numerator of "N/M" and leading list markers; no cross-reply scale normalization
- `TranslatorRankModel.kt:159-169`, `CompareRunModel.kt:182-187` — "8/10" → 8 (means 80); "Line 1 - 85" (no colon) → 1. `aggregateTranslatorRanks` averages mixed scales at face value (8 vs 80 → 44), sinking a translator on a scale mismatch. Only bites non-compliant replies (prompt asks for a bare 0-100).

### LG15 [LOW] parseSimilarityScore fallback grabs a year / model digit / index
- `CompareRunModel.kt:176,182-187` — last-resort "first number anywhere": "Matches the 2020 baseline, ~80%" → 2020 → clamp 100; "Closest to GPT-4, ~30%" → 4. Fallback-only.

### LG16 [LOW] A present-but-unparseable verdict line is accepted as "tie" rather than a miss
- `TournamentRunModel.kt:157-165` + `normaliseVerdict else->"tie"`; accept predicate `?.verdict != null` — "verdict: cannot decide" → non-null "tie", halts the round-robin (no better worker tried) and injects a spurious 0.5/0.5. Neutral direction.

### LG17 [LOW] Rerank/Moderation opt out of the REPORT_MODELS pool but inherit its ROUND_ROBIN schedule
- `SecondaryRunManager.kt:110`, :924-935; `workerScheduleFor` (`ReportViewModelHelpers.kt:254-263`) reads `cfg.batches`/`workerSelection` without consulting `alwaysPromptWorkers`. A REPORT_MODELS+ROUND_ROBIN report rotates a rerank's OWN configured chain by the shared batch cursor — round-robin on a non-REPORT_MODELS pool, contrary to workers.md. Only reorders which chain member is tried first.

### LG18 [LOW] Cost-table TOKEN columns omit the cached-input and reasoning token classes
- `ContentDisplay.kt:660`, ledger `SettingsPreferences.kt:734-735`/`ReportStorage.kt:2035-2036` — rows store only `tu.inputTokens` (uncached) and `tu.outputTokens` (excl. thoughts). The CENTS are correct (priced via `computeInOutCost`); the token counts don't partition the billed tokens (a Claude 200+800-cached call shows "in 200 tok").

### LG19 [LOW] Free/$0 model always takes the 💎 (documented as intended)
- `ValueView.kt:198-206` — `eps=1e-6` floor makes a $0 model score `quality×1e6`, unbeatable by any priced point on the frontier. A mediocre free model out-ranks a near-free excellent one. Comment says it's intentional — borderline.

### LG20 [LOW] Language-scoped Rerank/Meta re-runs in the META phase before TRANSLATIONS refreshes the bodies it reads
- Phase order META (`RegenerateBatch.kt:42`) precedes TRANSLATIONS (:55); a `targetLanguage`-tagged secondary reads pre-regenerate translated bodies via `buildLanguageInputs` (`SecondaryRunManager.kt:886-887`). Circular dependency (translations also translate metas) — no clean linear order; niche (most secondaries are original-language).

### LG21 [LOW] A pair picked BOTH as a swarm member and a direct +Model is classified as swarm, dropping the direct pick's fallbacks
- `ReportViewModel.kt:442` (`filter { it !in swarmMemberIds }`), consumed `buildReportTasks:646-663` — the direct id is always dropped when it collides with a swarm member, so it runs as non-direct (skips provider/report-model param + system-prompt layers). Count unaffected; params silently differ; which wins is insertion-order-dependent.

### LG22 [LOW] Translated fan-out substitutes a translated @QUESTION@ but the original-language @TITLE@
- `FanOutEngine.kt:1402-1408`, :512-518 pass `title = report.title` while `question` is translated — inconsistent with the meta/fan-in paths (`SecondaryRunManager.kt:1452`, :1203) which translate the title. Only bites a user-authored fan-out prompt that references @TITLE@ (the two bundled prompts don't).

---

## Verified SOUND (no logic error)

- **All 7 tournament ranking methods** — Copeland, Schulze, Colley, Markov (win-matrix direction loser→winner, beatpaths, Colley matrix+RHS, per-played denominators) and Elo, Davidson/Bradley-Terry MM (exact gradient, tie term in num+den), TrueSkill (factor functions, sign, μ−3σ conservative rank). Two agents, Python replicas, 13k+ stress matrices, no ranking inversion. "Rank 1 = best" consistent across all methods.
- **Cost/token arithmetic** — per-token scale (no 1000× error), cached priced once at the cached rate (parser pre-decrements), reasoning billed once at output rate, cache-creation at write rate, cents×100 exactly once everywhere, cost buckets partition, manual-override scale, apiCost split == total.
- **[N] numbering agreement** — buildResultsBlock ⇄ buildReferenceLegend ⇄ @COUNT@ (== includeIds.size) all consistent; Manual scope agentId-keyed (robust); AllReports = SUCCESS-nonblank.
- **Fan-out pairing** — (answerer, source) not swapped, @RESPONSE@=source body, self-pair excluded unless includeSelfResponses, @COUNT@ = scoped source count (fresh == replay); fan-in combining (default) = source's report + every OTHER answerer's reaction.
- **Precedence** — all system-prompt chains (agent + direct/swarm), resolveBatchSwarm (REPORT_MODELS > runtime > SELECT_ONCE > configured, alwaysPromptWorkers opt-out), WorkerRunner round-robin rotation + 429 fallback, resolveReportOverrideParams, reportPreGenParamsActive, SELECT_ONCE first-write-wins.
- **Judge/parse** — computeWinMatrix orientation folding + tie averaging, consensusForMatch strict plurality, judge A/B slot randomised per match (no position bias), aggregateTranslatorRanks (self-judgement excluded, higher=better), Compare mean %, parseConfidence, JSON verdict/score paths.
- **Report gen** — dedup key parity (picker == buildReportTasks), agent-sourced-wins tiebreak, progress total sourced from built tasks, directModelSids classification (normal path), main-case phase order (RERANK before TopRanked metas, FAN_META after FAN_OUT, TRANSLATIONS after content, TOURNAMENT last).
- **Value view** — Pareto direction/strictness, quality polarity (all sources higher=better), Tournament Total averaging, min-max max==min→0.5 neutral, weighted-average base renormalisation.

---

## Resolution (2026-07-02)

All 22 findings triaged; 20 fixed (one commit each, prefixed with the ID —
`git log --grep '^LG'`), 2 consciously LEFT with rationale. Ranking-engine
and cost-arithmetic findings that could have been math errors were verified
sound during the audit and needed no change.

Fixed: LG1–LG11, LG12 (folded into the LG2 commit), LG13–LG18, LG21, LG22.

Two judgment calls (delegated), both implemented:
- **LG6** — per-field parameter merge (honoring the doc's "merges down this
  chain" summary over its wholesale-level tables).
- **LG8** — added JUDGES / COMPARE / TRANSRANK regenerate phases (matching
  the doc's "every secondary result" and the existing TOURNAMENT phase).

LEFT AS-IS (documented, not regressions):
- **LG19** (free model always takes the 💎) — correct by definition: a $0
  model genuinely IS infinite value-per-dollar, so the best-value metric is
  right. "Fixing" it would make the metric wrong. Behavior kept; the LG4
  fix (exclude unknown-cost phantoms) already handles the real defect nearby.
- **LG20** (language-scoped meta re-runs before TRANSLATIONS in a regenerate)
  — a genuine circular dependency (translations also translate metas, so no
  single linear phase order satisfies both). The common case
  (original-language secondaries) is already correct after the AGENTS phase;
  the niche language-scoped case has no clean linear fix and is left as a
  known limitation.

Note LG5 was fixed only PARTIALLY (exclude no-information rankings from the
Combined blend, which was unambiguously correct); the residual
"absent-from-a-discriminating-ranking isn't strictly worse than last-in-it"
is an inherent, documented property of averaging over available data — the
alternatives (zero-impute / drop partial rankings) break the common
partial-participation case worse, so that property is kept on purpose.
