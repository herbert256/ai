# Functional audit — 5 September 2026

> **Reassessed 7 September 2026 after both Report fix rounds:** 2 core failures resolved, 10 findings partly addressed, 2 open. Read the [current reassessment](functional-audit-reassessment-2026-09-07.md) or its [HTML edition](../functional-audit-2026-09-07.html). The original audit below is preserved as historical evidence.

**Verdict: the application makes sense as a personal AI comparison workbench. Its strongest workflow is asking several models the same question, inspecting disagreement, refining an answer, and retaining the work. However, some evaluation features imply stronger conclusions than their inputs justify, and ordinary tasks require understanding too much of the application's internal organization.**

The priority is to make the existing functionality predictable and its results interpretable. Adding more functionality is not the immediate need.

**Scope and evidence.** This is a product and workflow audit, not a technical defect review. I inspected the current source at commit `6f10aba69`, the manual and subsystem documentation, bundled prompts, and the installed emulator application (version `26.179.1275`). Live inspection covered an existing report's Manage screen, the Reports hub, and the New report chooser. I did not run new paid generations, modify application configuration, or exercise destructive operations. The installed version is not asserted to be an exact build of that commit. Behavioral findings below are grounded in source; observed screen details are identified explicitly. No real-user usability study or assessment of model answer accuracy was performed.

The documentation contains outdated behavior descriptions. Where documentation and implementation disagree, the implementation takes precedence. Recommendations are product judgments; examples are illustrative unless marked as observed.

**What already works conceptually**

- A report groups one question and multiple answers. This is a strong organizing object for comparison, inspection, and reuse.
- Reports and Chat address distinct needs: independent answers versus an evolving conversation. Dual Chat is a reasonable advanced experiment with bounded rounds and a Stop control.
- Saved model configurations and reusable groups reduce repetitive setup. The distinction between configured agents and bare models has practical value, even though its presentation and behavior need work.
- Meta analysis, per-answer refinement, and Fan-out/Fan-in provide a useful progression from initial answers to deeper analysis.
- Prompt history, saved conversations, notes, report export, and backup make the app useful beyond the initial API call.
- Costs, call attribution, retry controls, and the preservation of spend from deleted items are valuable controls for a multi-provider tool.
- Existing safeguards deserve credit: draft autosave, additive generation when only models are added, confirmation text for regeneration, some batch call-count previews, inspectable judge reasoning, and experimental feature gating.

Those strengths should survive any simplification. For an experienced owner, fast icon shortcuts and detailed diagnostics are valuable. The same controls need a more explanatory route for occasional use or unfamiliar features.

**Priority overview**

| Priority | Finding | Why it matters |
|---|---|---|
| First | 1–5: interpretation of evaluation results | A user can make an unjustified decision even when every call succeeds. |
| First | 6–8: identity, configuration, and revisions | A user cannot reliably tell what was compared or which answer was evaluated. |
| First | 9: optional metadata can block primary work | A decoration failure can prevent the requested answer generation. |
| Next | 10–12: launch transparency, navigation, onboarding | The app makes users learn mechanisms before accomplishing a task. |
| Next | 13–14: translation scope and useful completion | Work can expand beyond the user's intention without a clear final deliverable. |

**1. “Ranking” currently covers several different questions. — High impact; confirmed**

Dedicated reranking sends the question and answers to a relevance-ranking endpoint. Compare with meta explicitly measures semantic agreement and coverage of a reference generated from the answers. Judge the judges measures agreement with the panel's own consensus. Tournament asks for a preference based on accuracy, completeness, clarity, and usefulness. These are useful measurements, but they do not establish the same thing.

For example, an answer can be highly relevant yet factually wrong. A correct minority answer can disagree with a mistaken synthesis. A judge can agree with other judges without being a better judge. The consensus calculation also includes the judge being scored, making it partly agreement with a consensus it helped create.

**Recommendation:** attach a plain-language measurement to every result: “Question relevance,” “Agreement with this synthesis,” “Panel agreement,” and “Preference under this rubric.” Use “answer quality” only where a defined quality rubric supports that description. Offer independent reference material or a user-approved reference as an optional evaluation input. For judge agreement, compare each judge against the other judges where feasible, and expose insufficient-panel cases.

**Acceptance:** someone opening a ranking can identify the criterion, evaluator, reference, coverage, and meaning of the score without reading Help. A relevance score is never presented as evidence of factual correctness.

Evidence: [dedicated rerank path](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/SecondaryRunManager.kt:1745), [meta comparison instructions](/Users/herbert/ai/ai/src/main/assets/internal-prompts/English/meta_compare/summarize.txt:1), [judge agreement](/Users/herbert/ai/ai/src/main/java/com/ai/data/JudgeAgreement.kt:57), [tournament instructions](/Users/herbert/ai/ai/src/main/assets/internal-prompts/English/workers/tournament.txt:1).

**2. “Best value” is not a defensible universal conclusion from score ÷ cost. — High impact; confirmed**

Value view selects a winner by dividing the selected ranking score by cost. Some supported scores have arbitrary baselines or are derived from rank positions. Their ratios have no stable interpretation as units of quality purchased.

Illustration: A has score 100 at cost 1; B has score 200 at cost 1.5. The ratio picks B. Adding 1,000 to both scores preserves their ordering and difference, yet the ratio now picks A. A harmless change of score origin changes the purchasing recommendation. Elo-like scores make this especially relevant.

There is also a mismatch between current output and historical spend. Regeneration preserves accumulated cost while replacing the answer. Refinement can replace an answer while its trial costs live only in global usage. Translation rankings are plotted against the model's original report-answer cost. Those are different economic questions.

**Recommendation:** preserve the Pareto chart as an exploration tool, but replace the universal winner with explicit choices such as “Lowest cost above my quality threshold” or “Highest judged score within my budget.” Separate latest answer cost, total experiment spend, and translation cost. Show the pricing basis and whether it is estimated. Do not imply that one report establishes general model value.

**Acceptance:** repeating an experiment changes lifetime spend, but does not silently change the comparison's definition of answer cost. A translation comparison uses translation work and its cost.

Evidence: [value point construction and winner selection](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:182), [preserved regeneration costs](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportStorage.kt:2662), [refinement cost contract](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportStorage.kt:353).

**3. Combined score creates apparent precision from overlapping and incomplete evidence. — High impact; confirmed**

Combined normalizes available rankings separately, then averages their weighted values for each model. Seven tournament methods contribute independently despite being calculated from the same judgments. Their default weights total 14, compared with 3 for rerank, 6 for judges, 6 for translations, and 4 for comparison. This is a strong preference for one underlying evidence source, expressed through its alternative calculations.

Translation skill, agreement with a synthesis, and answer preference also enter the same number. Models can receive that number from different subsets of the evidence. The implementation explicitly chooses averaging over available scores; this avoids treating absence as failure, but does not make the resulting comparisons equivalent. Min-max scaling can stretch a very small difference across the whole scale.

**Recommendation:** make Combined an explicitly named custom preference score. Group tournament methods under one evidence-family weight; use method choice to explore sensitivity. Show each model's contributing dimensions and coverage. Keep different task skills separate by default, and compare a common evidence subset when the user requests an overall ordering.

**Acceptance:** two models cannot appear to have directly comparable overall scores without revealing that one was judged on fewer or different criteria.

Evidence: [Combined calculation](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:247), [missing-data policy](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/ValueView.kt:317), [default weights](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/AppViewModelTypes.kt:135).

**4. Rank the translators compares unequal assignments. — High impact; confirmed**

A normal translation run distributes different source items across a worker pool. Translator ranking subsequently compares average received scores for the items each translator happened to produce. Translators need not have translated the same passages. They also judge one another, excluding self-judging, so their evaluator sets differ. A deterministic cap of 25 scoring cells per translator controls cost and repeatability; it does not equalize passage difficulty or judge strictness.

A translator assigned short, straightforward answers can outrank one assigned difficult material for reasons unrelated to capability. With only one translator, there is no independent peer left to score its work.

**Recommendation:** distinguish “Quality review of these translations” from “Compare translator models.” Keep the existing process for review, with item counts and limitations visible. A comparison should translate the same representative passages with every candidate, then use a common independent panel and the same rubric.

**Acceptance:** the app can display “not enough comparable evidence” rather than forcing a translator leaderboard. A comparison identifies the shared passages and common judges.

Evidence: [translation scheduling](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/TranslationRunManager.kt:138), [assignment recovery, peer panel, and sampling](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/TranslatorRankEngine.kt:156), [score averaging](/Users/herbert/ai/ai/src/main/java/com/ai/data/TranslatorRankModel.kt:208).

**5. Answer matrix makes wording heuristics look like assessed properties. — High impact; confirmed**

The matrix labels answers with Stance and Confidence using English regular expressions. “Confidence” counts words such as “certain” and “possibly”; no matching words yields Medium. “Refuses” can be triggered by “I cannot,” and “Risks” can be triggered by ordinary mentions of cost or security. A response can discuss those concepts without refusing or expressing uncertainty. Translated content is processed with the same English patterns.

This is a semantic limitation, even if the extraction runs exactly as written. A terse correct answer is not meaningfully “Medium confidence” merely because it lacks confidence vocabulary. An explanation containing “I cannot recommend…” is not necessarily a refusal to answer the question.

**Recommendation:** label these columns as detected wording, provide the matching excerpt, and use “Not assessed” when evidence is absent. Alternatively remove Confidence from the default matrix. If structured assessment is offered, name its source and rubric and retain uncertainty about what it establishes.

**Acceptance:** the table never implies calibrated confidence or independently checked risk analysis from keyword counts. Unsupported-language extraction is visibly unavailable.

Evidence: [matrix presentation](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/AnswerMatrix.kt:281), [extraction and patterns](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/view/AnswerMatrix.kt:502).

**6. An Agent is presented as a reusable role, but selection treats model identity as the unit. — High impact; confirmed**

An Agent contains a model, instructions, and parameters. It is natural to create “Optimist” and “Skeptic” on the same provider/model and compare them. Report selection deduplicates by provider and model alone, leaving one entry. Distinct agent identities and configurations do not distinguish the two selections.

This is a product contract conflict: the app offers reusable configured roles, but a central comparison workflow cannot preserve two roles on the same model. The same issue affects comparing different presets or endpoints on one model through those saved agents.

**Recommendation:** distinguish model comparison from configuration comparison. Preserve distinct named agents in configuration comparison; collapse only genuinely identical picks. Explain any merge immediately in the selection UI. Flocks and Swarms can remain as advanced shortcuts, but “Saved agents” and “Model groups” would make their purpose clearer. The Swarms list's current “Multi-step agent pipelines” subtitle is inaccurate for a flat group.

**Acceptance:** two agents with different instructions on the same model can appear as two labeled answer slots, or the interface clearly explains that the workflow only compares unique models before selection is lost.

Evidence: [model identity and deduplication](/Users/herbert/ai/ai/src/main/java/com/ai/model/SettingsModels.kt:1026), [selection uses this rule](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/start/SelectionOverlays.kt:142), [Swarm subtitle](/Users/herbert/ai/ai/src/main/java/com/ai/ui/cruds/workers/swarms/list.kt:42).

**7. Saved configuration does not have a consistent meaning across workflows. — High impact; confirmed**

Starting Chat with an Agent takes the system prompt from its parameter presets, but does not read that Agent's standalone system-prompt selection. Report generation does read that selection. A role configured once can therefore behave differently in Chat and Reports.

Report generation also looks for the first containing Flock or Swarm with a system prompt. That lookup is based on membership across saved groups, not necessarily the group through which this particular selection was made. Merely organizing a model into another group can affect the instructions used when running it.

**Recommendation:** define a simple public contract: an Agent carries the same identity and default instructions wherever used; group overrides apply when selecting that group; explicit run overrides win. Add an “Effective instructions and settings” summary before execution, including the source of each override. Keep advanced inheritance, but make its result inspectable before spending money.

**Acceptance:** a user can predict an Agent's role without knowing which screen launched it. Adding it to an unused group cannot silently change a direct run.

Evidence: [Chat seeding](/Users/herbert/ai/ai/src/main/java/com/ai/ui/navigation/ChatRoutes.kt:88), [preset-only resolver](/Users/herbert/ai/ai/src/main/java/com/ai/model/SettingsModels.kt:722), [group lookup](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/ReportViewModelHelpers.kt:112), [report resolution](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt:658).

**8. A report is both an editable document and an experiment record, without enough separation. — High impact; confirmed in the inspected paths**

The prompt editor correctly warns that saving requires regeneration, and stores previous prompts. Refinement records the source of an applied replacement. These are useful foundations. But applying a refined answer replaces its current response body while leaving costs and tokens untouched; that operation does not update or mark dependent secondary evaluations obsolete. Model-list-only regeneration explicitly preserves existing secondary rows while adding new answers.

A user can consequently read a current answer alongside judgments made before that answer or participant set changed. An audit trail helps investigate later, but it is not an immediate explanation of which material a judgment covers.

**Recommendation:** give answer revisions explicit identities and bind evaluations/translations to the source revision. Show “Based on previous answers” or “Covers 5 of 6 current answers” and offer a selective update. Preserve the original answer when applying a refinement. Provide separate “Edit draft,” “Retry failed,” “Run new version,” and “Update derived results” actions.

**Acceptance:** after refining one answer or adding one model, affected evaluations visibly identify their previous source coverage. Export communicates the same relationship.

Evidence: [prompt editing contract](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/EditPrompt.kt:35), [applying a replacement](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportStorage.kt:353), [refinement caller](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/view/SingleResult.kt:592), [additive regeneration message](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/Run.kt:803).

**9. Optional metadata has too much authority over task completion. — High impact; confirmed**

The regeneration sequence runs titles, icons, and language before primary answers. Its phase controller pauses at the first error. With the relevant metadata jobs enabled and configured, an icon or title failure can therefore stop progress before the requested answer regeneration starts.

That ordering makes cosmetic enrichment a prerequisite for substantive work. Users reasonably distinguish “I have my answers” from “one generated decoration failed.”

**Recommendation:** run primary work independently of optional enrichment. Model status should distinguish answer completion, optional metadata issues, and incomplete analysis. Keep metadata retry available without making it a blocking condition.

**Acceptance:** deliberately failing an icon worker does not prevent successful models from producing their answers; the report remains readable with a static fallback.

Evidence: [phase order](/Users/herbert/ai/ai/src/main/java/com/ai/data/RegenerateBatch.kt:18), [pause-on-error behavior](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/RegenerateBatchEngine.kt:432).

**10. The launch flow does not present the whole work commitment. — Medium–high impact; confirmed in the inspected report flow**

Selection shows model prices and a combined per-million-token rate. Some later batches show call counts. Those are helpful, but the final report setup page does not summarize the complete planned work: primary answers, metadata calls, worker provider pools, enabled follow-up work, and approximate total spend. Default metadata generation is enabled; the separate Autostart master is disabled by default, so automatic secondary batches should not be assumed to run on a fresh installation.

Worker pools can include providers beyond those selected to answer. They may process titles, prompt text, or answer content depending on the job. Choosing an answer model alone does not describe every recipient. This matters for both cost expectations and control over where content goes.

**Recommendation:** put a compact execution summary beside Generate: answer count, auxiliary jobs, eligible providers, estimated spend range and assumptions. Offer “Answers only” and “Use only these providers.” Add a spending limit where useful, with a clear statement that in-flight requests may still complete. Reuse the summary for large secondary operations.

**Acceptance:** before launching, the user can explain the planned work and eligible recipients. Price per million tokens is visibly distinguished from estimated cost of this run.

Evidence: [selection price summary](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/start/SelectionPhase.kt:140), [final setup](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/start/SelectWorkers.kt:141), [metadata and Autostart defaults](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/AppViewModelTypes.kt:249), [worker selection](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/WorkerRunner.kt:185).

**11. Navigation makes users learn categories and symbols before choosing an outcome. — Medium–high impact; observed and source-confirmed**

The observed Manage screen gave prominent space to API calls, accumulated duration, cost, and rows labeled “info,” “second,” and “report.” Its visible answer rows emphasized generated titles, with a dense icon bar. The observed Reports hub put New, Search, and the full report list behind bottom icons. The current source confirms that these primary actions have moved out of the body.

Further analysis is distributed between Create analysis, a separate Tournament launcher, direct action icons, second results, and View. “Compare,” “Compare with meta,” and A/B compare describe distinct activities but are close enough to require explanation. The icon legend is helpful, yet needing it for routine actions adds a navigation step.

**Recommendation:** retain the shortcut bars as an expert option, and add visible goal-based actions: “Read answers,” “Compare answers,” “Improve an answer,” “Create a synthesis,” “Translate,” and “Export.” Give the report one obvious reading home with named sections for Answers, Analysis, Translations, and Activity. Keep model identity readily available even when generated titles are enabled.

**Acceptance:** an unfamiliar user can start a report, read an answer, compare two answers, and export without opening the icon legend or learning the term “secondary.”

Evidence: live emulator inspection; [Reports hub](/Users/herbert/ai/ai/src/main/java/com/ai/ui/hub/HubScreens.kt:380), [separate analysis launchers](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/CreateOverview.kt:37). The live screen reflected existing settings; other display configurations may expose model names differently.

**12. First-run setup is a directory of options, and the manual sometimes describes a different product. — Medium impact; confirmed**

First launch presents Import API keys, AI Setup, Example reports, Housekeeping, Settings, Help, and About. It does not lead with one obvious path from adding one provider to completing one small report. “Import API keys” assumes the newcomer already has an exported configuration. Example reports are a valuable alternative, but compete with administrative choices.

The manual's Reports hub describes prominent Start and Search cards; the current implementation places those actions in the bottom bar. The regeneration document describes a ten-phase sequence and missing dedicated later stages; current code has dedicated Judges, Compare, and Transrank stages too. These are concrete documentation mismatches, not application bugs discovered by this audit.

**Recommendation:** give first run two primary paths: “Try an example” and “Connect a provider.” Then suggest a small model selection, one prompt, and reading the result. Keep import and advanced setup accessible. Update help from actual current journeys, especially after navigation changes.

**Acceptance:** starting from no imported configuration, the user reaches the first useful result without understanding Flocks, Swarms, internal prompts, or housekeeping. The manual's named controls match the screen.

Evidence: [First launch](/Users/herbert/ai/ai/src/main/java/com/ai/ui/hub/FirstLaunchScreen.kt:16), [manual](/Users/herbert/ai/doc/manual.md), [regeneration documentation](/Users/herbert/ai/doc/regenerate.md), [current phase enum](/Users/herbert/ai/ai/src/main/java/com/ai/data/RegenerateBatch.kt:18).

**13. Translation is designed around the whole report rather than the immediate reading need. — Medium impact; confirmed**

The main translation run snapshots translatable report content: prompt, successful answers, Meta content, and several title types. It has no general subset selector. A user who wants to understand one answer in another language enters a broader operation. Distribution across translators is operationally useful, but can also make terminology and style vary across one translated report unless managed explicitly.

**Recommendation:** offer “This answer,” “Selected content,” and “Entire report.” Show estimated items before starting. Offer a consistent translator or shared terminology instructions for a finished report. Treat translator benchmarking as a separate optional workflow, as described in finding 4.

**Acceptance:** translating a single selected answer need not translate other answers, the prompt, and decorative titles.

Evidence: [translation scope and item types](/Users/herbert/ai/doc/translation.md), [run construction](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/TranslationRunManager.kt:138).

**14. The app needs a clearer point at which analysis becomes the user's deliverable. — Medium impact; product judgment supported by the inspected flows**

Many operations produce more material: compare, critique, synthesize, fan out, rank, translate, and judge again. Notes, refinement, and export already provide parts of a finishing workflow, but the central organization emphasizes producing and managing outputs. A “final answer selected by me” is a clearer endpoint than simply another Meta result.

An observed report was headed “Magnus Carlsen: The Undisputed Chess GOAT” while visible answer titles favored both Carlsen and Kasparov. This does not establish how that particular title was created, but illustrates why a report title should describe the question rather than pre-empt its conclusion.

**Recommendation:** let users mark a selected answer or synthesis as their conclusion, retain important disagreements and sources, and export that concise result with an optional full audit appendix. Use neutral report titles by default and reserve claims of a winner for explicitly attributed evaluation results.

**Acceptance:** after comparing answers, the user can finish with an identifiable deliverable showing what they selected, why, what remains uncertain, and which source versions support it.

Evidence: live report observation; [analysis launcher](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/CreateOverview.kt:37), [refinement workflow](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/AgentChat.kt:61), [report-title instructions](/Users/herbert/ai/ai/src/main/assets/internal-prompts/English/workers/report-title-long.txt:1).

**A coherent default journey**

Question and attachments → choose models or saved roles → review instructions, recipients, and expected work → generate → read answers and disagreements → optionally evaluate or refine → select a conclusion → export.

Secondary tools fit naturally into this journey when presented as optional steps with a stated purpose. Diagnostics remain one tap away, but are not the default explanation of a completed report. Advanced configuration stays available without becoming a prerequisite.

**Recommended sequence of work**

1. **Make conclusions trustworthy:** clarify ranking meanings; reconsider Best value; expose Combined coverage; relabel matrix heuristics; separate translator review from benchmarking.
2. **Make runs predictable:** preserve agent identity, show effective configuration, expose all worker recipients and planned work, and keep metadata failures from blocking answers.
3. **Make reports durable experiments:** bind judgments to answer revisions, separate current-call cost from lifetime spend, and distinguish retry, new version, and derived-result updates.
4. **Simplify the common journey:** labeled primary actions, two-path onboarding, selected-content translation, and a user-selected final deliverable. Then bring the manual into alignment.

Do not start with a wholesale visual redesign. Navigation simplification alone cannot resolve misleading score semantics or unstable configuration meaning. Conversely, preserving expert shortcuts does not require keeping those ambiguities.

**Follow-up validation**

The following are acceptance scenarios for future product work, not claims of tests already run:

| Scenario | Evidence of a sensible workflow |
|---|---|
| First use with one provider | User gets one useful answer without configuring worker machinery. |
| Optimist and Skeptic on the same model | Both roles survive selection and show their effective instructions. |
| Same Agent in Chat and Reports | Its default role is consistent and any override is visible. |
| One answer refined after a tournament | Old judgments identify the old revision and offer selective refresh. |
| Icon service unavailable | Primary answers still complete; optional failure remains secondary. |
| One answer translated | Only intended content is included in the execution summary. |
| Two translators compared | Both translate the same passages under the same evaluation conditions. |
| Partial rankings | Coverage differences are visible; missing evidence is not disguised as a comparable score. |
| Repeated generation | Current result cost and accumulated experiment spend are distinguishable. |
| Export after a decision | Recipient can identify the chosen conclusion, dissent, and evaluation basis. |

Knowledge ingestion, local models, file-type edge cases, long-lived interrupted work, and restore/import should receive separate hands-on journey validation. They were inspected through documentation and selected integration points here, not exhaustively exercised. Existing source already preserves access to attached knowledge in Chat when experimental creation controls are disabled, so the older documentation's broader claim that all such UI disappears is not treated as a current finding.
