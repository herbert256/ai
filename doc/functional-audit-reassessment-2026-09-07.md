# Functional audit reassessment — 7 September 2026

> Follow-up: the partial/open findings were implemented in the [7 September remediation](functional-audit-remediation-2026-09-07.md). The statuses below preserve the pre-fix reassessment.

**Verdict: the two remediation rounds substantially improved Report integrity and execution, but they did not close the original functional audit. Of its 14 findings, 2 have their core failure resolved in source, 10 are partly addressed, and 2 remain open. The most important remaining gaps are translation scores paired with answer costs, inconsistent Agent instructions in Chat, and incomplete evaluation coverage notices.**

This is a reassessment of the [5 September functional audit](/Users/herbert/ai/doc/functional-audit-2026-09-05.md), preserving its numbering and acceptance criteria. The original document remains a historical record. “Partly addressed” means meaningful progress with an original acceptance condition still unmet; it does not mean the changes were ineffective. “Core resolved” closes the identified functional failure, while separately retaining any broader product recommendations. These are source-review conclusions, not claims that all acceptance scenarios passed on a device.

## What was compared

| Baseline or change | Commit | Role in this review |
|---|---|---|
| Original functional audit's source baseline | `6f10aba69` | Original behavior and 14 findings |
| First Report remediation | `e87c6b8a2` | Identity, settings, source snapshots, evaluation semantics, costs, execution and storage |
| Second Report remediation | `adbbc11b2` | Retry approval, saved-input replay, completion, imports, recovery, cost journals and work-limit edge cases |
| Current checkout inspected | `63e471253` | Same application source as the second remediation; latest commit adds the original audit's HTML edition |

I read the current implementations and the two diffs, then checked the installed app without generating new answers, changing configuration or editing report content. The emulator runs version **26.249.607**. Its installed APK SHA-256 matches the workspace APK and the checksum recorded by the second remediation: `5dc9cb784d74d8ac03a3b4a69deb58583cdca894101d3e4ec7f6a94ab3ae1b7e`. This verifies artifact identity; it is not a fresh reproducible-build claim. Read-only live inspection covered the Reports hub and the existing Chess GOAT Debate report's Manage screen. Other behavioral conclusions below are from source.

The [first remediation record](/Users/herbert/ai/doc/report-audit-remediation-2026-09-06.md) and [second bug-hunt record](/Users/herbert/ai/doc/report-audit-followup-2026-09-06.md) describe work against the deeper Report audit. Their 28 findings and 12 follow-up issue groups are different units from the 14 functional findings here. Fixing those technical issues does not automatically satisfy every functional acceptance criterion. Their earlier build/import/retry checks were reviewed as historical evidence and were not rerun for this documentation review.

## Status of all 14 findings

| # | Original concern | Current status | Remaining acceptance gap |
|---|---|---|---|
| 1 | Different meanings of ranking | Partly addressed | Clearer Compare wording and independent judge agreement; no consistent criterion/reference/coverage explanation across every ranking |
| 2 | Universal Best value and mismatched costs | Partly addressed | Ratio winner removed and attempt costs separated, but translator scores still plot against original-answer costs |
| 3 | Combined score mixes overlapping/incomplete evidence | Partly addressed | Common coverage and one tournament family implemented; custom-score meaning, scale sensitivity and contributing dimensions remain insufficiently explained |
| 4 | Translators receive unequal assignments | Partly addressed | Review limitation disclosed and passages weighted equally; shared-passage, common-panel comparison still absent |
| 5 | Matrix heuristics look like assessments | Partly addressed | Confidence renamed and missing cues become Unknown; language gating and other categorical heuristics remain |
| 6 | Different Agents on one model collapse | Core resolved | Distinct Agent IDs survive selection; broader group terminology and duplicate-selection explanation remain product follow-ups |
| 7 | Agent configuration changes meaning by workflow | Partly addressed | Report group provenance fixed; Chat still omits the standalone Agent system prompt |
| 8 | Mutable answers coexist with old evaluations | Partly addressed | Saved inputs and historical notices added; participant additions and changed secondary references are not fully explained |
| 9 | Optional metadata blocks primary answers | Core resolved | Regeneration runs primary answers first; optional phases can still leave the overall batch paused |
| 10 | Launch does not explain the whole commitment | Partly addressed | Work counts, HTTP ceiling and recorded-spend stop added; full job/recipient summary still missing |
| 11 | Navigation requires internal categories and symbols | Partly addressed | Model identity now accompanies generated titles; main navigation structure is largely unchanged |
| 12 | First use and documentation do not match the journey | Partly addressed | Regeneration phase documentation corrected; first-run flow, hub manual and parts of Value documentation remain outdated |
| 13 | Translation cannot start with just the needed answer | Open | Initial translation still builds the whole report content set; retrying a subset later does not provide initial scope selection |
| 14 | No explicit user-selected final deliverable | Open | No conclusion-selection workflow, version-bound decision export or explicit neutral-title rule was added |

## 1. Ranking meanings — partly addressed

**Changed.** Judge agreement now excludes the judge being scored, requires at least two other distinct judge models and uses only a unique plurality. The UI exposes an eligible-match denominator and an unavailable state. Compare rows say “Score against meta”; its launcher already explains similarity to a Meta result. These reduce two specific sources of overclaiming.

**Still missing.** This does not establish factual correctness or external judge quality: different model identities do not guarantee statistically independent judgments. Labels such as “Rerank”, “Combined” and “Tournament” still require interpretation. There is no consistent result summary covering criterion, evaluator, reference and participant coverage, and no new independent-reference workflow was added. Keep this finding open until the meaning is clear at the point of reading a result, including exports.

Evidence: [leave-one-out calculation](/Users/herbert/ai/ai/src/main/java/com/ai/data/JudgeAgreement.kt:58), [judge availability UI](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/JudgeEval.kt:478), [Compare row](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/Compare.kt:179), [Value source labels](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:651).

## 2. Value and cost attribution — partly addressed; high-priority gap

**Changed.** The score-divided-by-cost winner is gone. Every priced, nondominated answer is eligible for the Pareto frontier; the highlighted row is now a “Frontier example”. The chart uses `currentAttemptCost`, excludes unknown attempt cost, marks estimated usage, and does not add Fan-out spend. Applying a refined answer clears attempt cost and usage instead of pretending the replacement cost the same as its predecessor. That intentionally makes some legacy or refined answers unavailable for Value comparison until their cost is known.

**Still wrong.** Translator runs remain selectable sources. `rowsForSource(TransRank)` joins translator scores onto successful original report answers by provider/model. `buildValuePoints` then prices those rows with the original answer's `currentAttemptCost`. A translator with a scored translation and a matching priced report answer can therefore appear on a frontier whose two axes describe different tasks. Removing translations from Combined did not remove this separate Value source. The HTML Value exporter shares these source/point builders, so the mismatch also affects that export.

**Required closure.** Either remove translation rankings from answer Value entirely, or build a translation-specific comparison using the scored translation items and their own costs. A worked counterexample to validate later: an original answer costs $0.01, its translation costs $0.10 and receives 90 points; the translation chart must not plot 90 against $0.01. Threshold/budget exploration remains a useful enhancement, but the task-cost mismatch is the immediate correctness issue.

Evidence: [attempt-cost points and Pareto flags](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:185), [translator source remains available](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:370), [translator-to-answer join](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:415), [shared export calculation](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueViewExport.kt), [replacement cost clearing](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportStorage.kt:397).

## 3. Combined score — partly addressed

**Changed.** Tournament methods are blended within one family rather than each adding a full independent family contribution. Translation scores no longer enter the Combined calculation. Every included answer must have a score in every informative, positively weighted family; missing evidence cannot improve a model's average. The caption explains common coverage and the tournament family.

**Still missing.** The result remains “Combined · weighted 0–1000” and uses min-max scaling. It does not provide the proposed per-answer explanation of dimensions, effective weights and scale sensitivity. The tournament family's effective weight is the maximum enabled method weight, which is less obvious than a separately named family control. Common coverage improves comparability but does not make relevance, synthesis agreement and panel preference the same construct, or make tiny raw differences substantively large.

**Required closure.** Present this as a custom preference score and expose its contributing families, effective weights, included/excluded participants and raw-to-normalized values. The principal missing-data failure is corrected in source; the interpretation acceptance criterion is not fully met.

Evidence: [family aggregation and common-coverage calculation](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:243), [label](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:652), [explanatory caption](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:781).

## 4. Translator ranking — partly addressed

**Changed.** The confirmation now calls this “Translation review” and explicitly warns that passages and judges differ and that it is not a controlled model benchmark. Aggregation first averages each passage's received scores, then averages passages, preventing a passage with more judge cells from dominating solely through cell count. Unscored translators are omitted; legacy translations without a saved original are excluded from scoring.

**Still missing.** Equal weighting does not equalize passage difficulty or judge strictness. The results still rank translators by average received score, and the confirmation action still says “Rank”. There is no newly implemented benchmark in which every candidate translates shared passages under a common independent panel. Treat the current feature as review of produced work. Its limitation is better disclosed, but it has not acquired the evidence needed for general translator selection.

Evidence: [review confirmation](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/TranslatorRank.kt:156), [passage aggregation](/Users/herbert/ai/ai/src/main/java/com/ai/data/TranslatorRankModel.kt:206), [assignment and judge construction](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/TranslatorRankEngine.kt).

## 5. Answer matrix — partly addressed

**Changed.** “Confidence” is now “Wording cues”; no decisive cue produces “Unknown”, and “Refuses” becomes “Refusal wording”. The matrix says its English phrase scan can be misled by quotations, negation and other languages, and does not measure correctness or model confidence. Its cost display uses current-attempt attribution.

**Still missing.** The extractor receives only the answer body and applies the English patterns without a language eligibility check. Unsupported-language extraction is therefore not explicitly unavailable. Other outputs still include “Recommends”, “Cautious”, “Neutral” and “None explicit”; absent English matches can look like substantive absence of risk or stance. Recommendation/risk snippets exist, but wording cues do not provide a matching-evidence explanation.

**Required closure.** Gate unsupported languages or label every affected field unavailable; make the remaining categories explicitly detected wording, with the relevant excerpts. The original “Medium confidence by default” defect is resolved, but the whole matrix acceptance criterion is not.

Evidence: [disclosure](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/AnswerMatrix.kt:282), [column label](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/AnswerMatrix.kt:359), [language-agnostic extractor](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/AnswerMatrix.kt:503).

## 6. Named Agent identity — core resolved in source

**Changed.** A named Agent now deduplicates by `agent:<id>`; a bare model still deduplicates by provider/model. Two saved Agents on the same model retain separate entries through the Report picker and become tasks keyed by their Agent IDs. The original Optimist/Skeptic failure no longer follows from this path.

**Remaining product follow-ups.** The Swarms list still says “Multi-step agent pipelines”, even though it represents a flat group. Selecting the same Agent through multiple sources still yields one Agent entry, with no new explanation of which selection provenance survives. These are distinct usability/inheritance follow-ups; they do not reopen the specific failure of two different Agent IDs collapsing to one. A fresh two-role generation was not run during this review.

Evidence: [identity and deduplication](/Users/herbert/ai/ai/src/main/java/com/ai/model/SettingsModels.kt:1032), [picker](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/start/SelectionOverlays.kt:140), [Agent task construction](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:668), [unchanged Swarm subtitle](/Users/herbert/ai/ai/src/main/java/com/ai/ui/cruds/workers/swarms/list.kt:42).

## 7. Configuration consistency — partly addressed; high-priority gap

**Changed.** Report task construction now resolves a group prompt through the group's recorded selection provenance. It no longer needs the first unrelated group containing that Agent/model to decide the current direct run's instructions. Primary runs also retain resolved execution settings for replay.

**Still wrong.** Chat's saved-Agent route still calls `resolveAgentParameters(agent)`, which merges only parameter presets, then uses `resolvedParams.systemPrompt`. It does not read `agent.systemPromptId`. Report task construction does read that field. A saved Agent with a standalone “Skeptic” system prompt and no preset system prompt consequently starts Chat with an empty seeded system prompt while Reports applies the role. This is the original cross-workflow inconsistency, not a newly introduced regression.

**Required closure.** Use a shared effective-Agent configuration contract for both launchers and expose the resolved instructions and override source before execution. Saving settings for replay solves a different problem from using the right settings on the first run.

Evidence: [Chat route](/Users/herbert/ai/ai/src/main/java/com/ai/ui/navigation/ChatRoutes.kt:88), [preset-only resolver](/Users/herbert/ai/ai/src/main/java/com/ai/model/SettingsModels.kt:725), [selection-scoped group and Agent prompt resolution](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:655).

## 8. Revisions and evaluation coverage — partly addressed; high-priority gap

**Changed.** Source snapshots preserve prompt and answer text; manifests preserve run inputs/settings, and some operations also capture secondary reference bodies. Detail screens show historical/unknown-source notices. Value and current Rerank joins exclude results whose recorded primary sources are stale or unknown. Bundle evidence can preserve those relationships during transfer. The second round corrected mixed-run snapshot association and saved-prompt retry behavior.

**Still incomplete.** The current staleness check compares the prompt and the bodies of answers already present in the snapshot. Adding a new successful Agent does not change any old answer body, so that check returns false. The source notice then says the original revision was recorded, without “Covers 5 of 6 current answers”. This leaves the original participant-addition acceptance case unmet; keeping a valid subset evaluation is reasonable, but its subset must be visible. The same check does not compare captured `secondaryBodies` to current secondary references, so changing a Meta reference is not represented by this freshness check either.

Applying a refinement still replaces the current answer field. Earlier text may survive in an existing source snapshot or refinement conversation, but the apply operation does not guarantee creation of a complete answer-revision history. A readable version history, selective derived-result update and concise coverage-aware export are not yet a complete user workflow. The “replays use saved inputs” notice also overstates the fallback case: `historicalReport` returns the current report when no snapshot can be read.

**Required closure.** Distinguish valid subset evidence, superseded evidence and unavailable provenance; show participant counts and reference revisions; preserve each applied answer revision; refuse or explicitly review a replay when recorded inputs are unavailable. Validate adding a participant separately from changing an existing answer.

Evidence: [snapshot capture, fallback and staleness](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportEvidenceStore.kt:32), [notice branches](/Users/herbert/ai/ai/src/main/java/com/ai/ui/shared/ReportSourceNotice.kt:17), [Compare reference capture](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/CompareEngine.kt:230), [replacement operation](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportStorage.kt:397), [bundle implementation](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportBundle.kt).

## 9. Optional metadata versus answers — core resolved in source

**Changed.** Regeneration starts with AGENTS; report TITLE, ICON and LANGUAGE phases occur after primary and analysis work. The engine lets submitted siblings settle before pausing a failed phase, and completed primary tasks are not the work retried merely because later enrichment fails. An icon/title failure in the later phases can no longer prevent the earlier primary-answer phase from starting.

**Residual behavior.** Optional-phase errors can still leave the overall regeneration job “paused on error”. A more explicit “answers complete; optional enrichment incomplete” summary would improve comprehension, but it is no longer the original prerequisite failure. No deliberate icon-worker failure was injected during this review; closure is based on the current phase order and controller.

Evidence: [primary-first phase enum](/Users/herbert/ai/ai/src/main/java/com/ai/data/RegenerateBatch.kt:18), [phase controller](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/RegenerateBatchEngine.kt:421), [corrected phase documentation](/Users/herbert/ai/doc/regenerate.md:177).

## 10. Launch commitment — partly addressed

**Changed.** The shared review dialog reports an operation's work-item count, allows an HTTP-request ceiling and an optional additional-spend stop, and explains that retries, fallbacks and metadata may add requests. It explicitly states that recorded-cost enforcement can be exceeded by calls already in flight. The second round fixed approval isolation, repeated approvals and malformed saved limits.

**Still missing.** The primary preview is labelled “Primary answers” and counts primary tasks; its review data carries a label and item count, not a complete auxiliary-job plan or eligible-provider list. The dialog provides a spending guard rather than an estimated price for the planned run. The setup screen exposes configurable workers but does not summarize every content recipient or offer the proposed consolidated “Answers only” / “Use only these providers” choice beside the launch summary.

**Required closure.** Show the resolved job families and eligible recipients before launch, including optional worker pools. Keep honest estimate uncertainty and the existing ceiling controls. Do not describe the current preview as a complete execution-plan review.

Evidence: [review data and limit behavior](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportWorkLimits.kt:15), [dialog](/Users/herbert/ai/ai/src/main/java/com/ai/ui/shared/ReportWorkReviewDialog.kt:17), [primary count](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:512), [setup](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/start/SelectWorkers.kt:141).

## 11. Navigation and reading — partly addressed

**Changed.** Current Manage rows keep model identity alongside generated titles. The existing report's visible rows now expose model names, which is a useful improvement over title-only identification.

**Still missing.** The Reports hub still routes Create/Search/list navigation through its bottom bar. Manage still uses categories such as “second” and “report”, while analysis entry points are spread across separate launchers and shortcuts. Read-only live inspection confirms this structure remains on the current installed APK. The proposed obvious reading home and named outcome-based journey were not implemented in these two rounds.

**Required closure.** Add a clear route through reading, comparing, refining and exporting while preserving expert shortcuts. Source inspection can establish the current structure; it cannot establish that an unfamiliar user would successfully navigate it. That acceptance criterion still needs a small usability exercise.

Evidence: [Reports hub](/Users/herbert/ai/ai/src/main/java/com/ai/ui/hub/HubScreens.kt:384), [model identity in Manage](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/GenerationPhase.kt:908), [separate launchers](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/CreateOverview.kt:30); current emulator Reports and Manage screens.

## 12. First use and documentation — partly addressed

**Changed.** The regeneration document now lists the actual 13 phases and the primary-first order. Several subsystem documents received remediation notes. This closes the specific old ten-phase description.

**Still missing.** FirstLaunchScreen is unchanged: Import API keys, AI Setup, Example reports, Housekeeping, Settings, Help and About remain the entry choices. The manual still describes Start/Existing/Search cards that the current Reports hub no longer presents. Value documentation also retains an opening claim about a “best-value” model and historical/fan-out cost descriptions despite later remediation text and the current attempt-only Pareto calculation. Documentation updates have not been reconciled end to end.

**Required closure.** Give first use a direct example/provider path, and update whole affected sections rather than appending corrections to conflicting old descriptions. The first-run acceptance scenario was not replayed by clearing the user's configuration.

Evidence: [first launch](/Users/herbert/ai/ai/src/main/java/com/ai/ui/hub/FirstLaunchScreen.kt:43), [stale hub walkthrough](/Users/herbert/ai/doc/manual.md:98), [corrected phases](/Users/herbert/ai/doc/regenerate.md:177), [conflicting Value introduction](/Users/herbert/ai/doc/value-view.md:3).

## 13. Initial translation scope — open

The main start method still constructs report titles, the prompt, successful answers, model titles, Fan-out titles and eligible Meta bodies. It accepts worker/prompt overrides but no general selected-answer/content-ID scope. Saved original text and subset retry operations improve replay integrity; they do not let a user start by translating only one existing answer. Later per-item alternatives likewise do not satisfy that initial reading need.

**Required closure.** Add “This answer”, “Selected content” and “Entire report” at launch, with the actual selected item count and translator choices. No additional closed status is warranted for this functional finding from the two remediation rounds.

Evidence: [start signature and content construction](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/TranslationRunManager.kt:149), [subset retry APIs](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/TranslationRunManager.kt:988).

## 14. A user-selected final deliverable — open

Notes, refinement and exports remain useful ingredients, and safer export/import is real progress. However, the Report model and inspected reading/refinement/export flows still do not provide the proposed explicit conclusion selection, rationale, dissent and supporting-version relationship as a finished deliverable. A replacement answer marker or another Meta result is not that whole workflow.

The bundled long-title prompt remains a short instruction to create a title from the report prompt; the remediation did not add a rule to describe the question neutrally. The existing report still displays “Magnus Carlsen: The Undisputed Chess GOAT”. That is an observation of retained content, not evidence that the current worker newly generated it or that every generated title is biased.

**Required closure.** Let the user select an answer or synthesis as the conclusion, attach rationale and uncertainty, and export it with references to the relevant versions and optional full evidence. Add explicit neutral-title guidance for future generation. This remains a product-design task beyond the reliability fixes.

Evidence: [Report model](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportModels.kt:311), [refinement workflow](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/AgentChat.kt:61), [current title prompt](/Users/herbert/ai/ai/src/main/assets/internal-prompts/English/workers/report-title-long.txt:1); current emulator Manage heading.

## What should happen next

| Order | Concrete next change | How to demonstrate closure |
|---|---|---|
| 1 | Correct translation Value attribution (#2) | A translation score cannot be paired with original-answer cost, in UI or export |
| 2 | Share effective Agent instructions across Chat and Reports (#7) | A standalone Agent system prompt appears in both resolved initial requests; unused group membership has no effect |
| 3 | Complete source coverage and revision handling (#8) | Adding a sixth answer shows “5 of 6”; changing a Meta reference identifies the previous reference; unavailable saved inputs cannot silently replay live inputs |
| 4 | Finish evaluation interpretation (#1, #3–5) | Criterion/reference/coverage are visible; Combined explains dimensions; unsupported-language matrix cues are unavailable; translator review is distinct from benchmarking |
| 5 | Complete launch disclosure and documentation (#10, #12) | Preview enumerates eligible recipients and auxiliary jobs; manual controls match the screen |
| 6 | Finish the reading-to-deliverable journey (#11, #13–14) | User translates one answer, selects a conclusion and exports it without learning internal result categories |

The immediate priority is the first three concrete correctness gaps, followed by interpretation and the common journey. A broad visual redesign is not a prerequisite for those fixes.

## Evidence limits and acceptance work remaining

This pass made documentation changes only. It did not run a new Android build, unit or instrumented suites, paid generations, full-disk/process-death fault injection, destructive restore/import, or a first-run reset. No application bug fixes are claimed by this reassessment. Existing snapshots cannot retroactively recover inputs that were never recorded.

The most useful next validation is a small controlled fixture set: two distinct Agents sharing one model; one Agent with only a standalone system prompt; a five-answer evaluation followed by a sixth answer; a changed Meta reference; translation costs deliberately different from answer costs; a non-English answer with no English cues; and a failing optional icon worker. Those are proposed acceptance checks, not tests executed in this review. Follow with one unfamiliar-user journey from a single provider to a selected exported conclusion.

The revised assessment is therefore **a stronger and more defensible Report foundation, with material functional work still outstanding**. Keep #6 and #9 closed at their core-failure level, retain the ten partial findings with the remaining conditions above, and leave #13–14 open.
