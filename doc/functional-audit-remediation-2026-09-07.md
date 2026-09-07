# Functional audit fixes — 7 September 2026

The 12 findings left partial or open by the reassessment are addressed in this change. The two previously resolved core behaviors—distinct named Agent identity and primary answers before optional metadata—are retained. This closes the reported functional failures; it does not turn model judgments into factual verification or introduce a controlled translator benchmark.

## Changes against the original findings

| Finding | Resolution | Main implementation |
|---|---|---|
| 1 · Evaluation meanings | Shared criterion, evaluator, saved rubric and source-coverage explanations; independent user-authored reference input; matching export explanations | `ReportEvaluation.kt`, `ReportSourceNotice.kt`, `ReportReadingHome.kt`, report exporters |
| 2 · Value task/cost mismatch | Translation reviews removed from answer Value sources, including the legacy-source fallback and HTML export | `ValueView.kt`, `ValueViewExport.kt` |
| 3 · Combined score | Renamed Custom preference; separate tournament family weight; per-answer raw ranges, normalization, effective weights and common-coverage explanation | `ValueView.kt`, Settings |
| 4 · Unfair translator leaderboard | Replaced unsupported ordering with an alphabetical review of produced work; no ordinal rank in new aggregate JSON; equal passage weighting retained | `TranslatorRankModel.kt`, `TranslatorRank.kt` |
| 5 · Matrix overclaims | English report-language eligibility; literal advice/risk wording labels, matching excerpts and explicit unavailable states | `AnswerMatrix.kt` |
| 7 · Agent role changes | Standalone Agent system prompt resolves with its presets in Chat and Report continuation; reasoning setting carried into Chat; primary launch shows and freezes effective instructions | `SettingsModels.kt`, `ChatRoutes.kt`, `ReportLaunchPlan.kt` |
| 8 · Mutable evidence | Previous answer bodies and prompts archived; saved source hashes verified; current participant coverage and changed references disclosed; unavailable replay fails explicitly; saved reference text used for replay | `ReportStorage.kt`, `ReportEvidenceStore.kt`, secondary engines |
| 10 · Hidden launch commitment | Whole primary-launch job and recipient preview, expandable effective settings, answers-only mode, report endpoint restrictions including redirects, request/spend ceilings, scoped translation preview | `ReportLaunchPlan.kt`, `ReportWorkLimits.kt`, `ReportWorkReviewDialog.kt` |
| 11 · Navigation | Named Create/Search/Browse actions and a reading flow with Answers, Analysis and Conclusion tabs | `HubScreens.kt`, `ReportReadingHome.kt` |
| 12 · First use and documentation | Try an example / Connect a provider, optional advanced setup, current manual and in-app Help | `FirstLaunchScreen.kt`, Help content, documentation assets |
| 13 · Initial translation scope | One answer, arbitrary selection, all answers or whole report; item/character preview, shared terminology, one consistent translator; changed source text requires review | `TranslationSelectionScreen.kt`, `TranslationSelection.kt`, `TranslationRunManager.kt` |
| 14 · User deliverable | Explicit answer/synthesis selection with rationale, uncertainty, disagreements and sources; immutable text and evidence snapshot; conclusion HTML and appendix, complete export/bundle support; neutral future report titles | `ReportReadingHome.kt`, `ReportConclusionExport.kt`, Report storage/bundle, title generation |

## Workflow

Open Reports → a report → **Read answers · Compare · Choose conclusion**. Compare two answers or open one for refinement. **Previous versions** shows saved earlier text. In Analysis, add your own independent reference with source attribution or create a synthesis. Choose a result, review the text, explain the decision and save it. The Conclusion tab exports the decision alone or with its evidence appendix.

Translation starts with explicit content selection. **Translate only this answer** selects exactly one body. A chosen single translator remains the sole worker for the selected items. Terminology and prompt edits are frozen in the run manifests; retry retains the original item set. Hashes detect text changed after the scope was reviewed.

A new report's work review lists primary answers, enabled metadata, automatic analyses, knowledge retrieval when attached, and resolved recipient endpoints. Answers only persists the choice to skip optional automatic work. Endpoint restrictions cover every tagged report HTTP request, including redirects and fallbacks. They can be reviewed or extended on later operations. The spend stop remains based on recorded costs, so requests already in flight can exceed it.

## Evidence and compatibility

Answer history is stored as nested `ReportAgent.answerHistory` records; large history bodies/prompts use the existing content store. The optional `Report.conclusion` stores the selected text and source snapshot identity. Old reports default to empty history and no conclusion. Existing generated titles are retained; the neutral-title rule affects future generation.

Portable bundles keep export version 2 because no bundle top-level field changes. Import validates the nested history/decision shape, re-keys secondary references and recomputes/remaps evidence hashes. Duplicate reports copy the selected decision's source file; the decision remains readable even when its original analysis is not duplicated. Complete human-readable exports carry the user decision; conclusion HTML can include the actual saved inputs.

Current analysis exports include criterion, saved rubric and source coverage. Historical source labels come from the recorded answer order; ordinal links to current answers are disabled when that mapping no longer matches. Worker fallbacks retain their own endpoint, role and credential reference while preserving already billed attempt costs. User-authored references cannot be regenerated as provider calls. A changed result cannot silently replace the text the user reviewed when saving a decision.

## Verification

- **Build:** `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :ai:assembleDebug` passed. `git diff --check` passed.
- **Deployment:** installed with `adb install -r`, copied to `/Users/herbert/cloud/ai.apk`, launched, and confirmed `com.ai/.MainActivity` as the resumed activity.
- **Artifact identity:** workspace APK, emulator-installed APK, and cloud copy have identical SHA-256 **`83782e7051d4a03e1e3ca747e9fac73a4f264e92b27b3187b6e45c746d870aa8`**. App version `26.249.607`; APK size 116,965,392 bytes. The unchanged version label alone is not used to identify the build.
- **Device behavior:** the named Reports hub actions render; two named Agents sharing one model remain separate in the reading screen; saved previous answer text and its source prompt are readable; the decision editor shows the selected text before save; rationale, selected identity and both source answers persist; the saved source file's SHA-256 matches the stored snapshot ID.
- **HTML export:** saved a conclusion through Android's document picker, read the resulting HTML, and verified the rationale, evidence identity and both saved answer bodies in its appendix. The picker initially remembered a deleted test directory; choosing the existing Downloads directory completed the save.
- **Translation scope:** opening Translate only this answer produced exactly one checked content item. Source review also verified explicit single-worker precedence, saved terminology/prompt reuse, and source-change rejection. No translation request was sent during verification.
- **Bundle import:** imported a disposable version-2 bundle through the app. A fresh report identity was minted; the conclusion, rationale, previous answer history and hash-verified source snapshot survived.
- **Document rendering:** offline desktop/mobile checks passed for all 12 finding rows, section links, horizontal containment and print layout. Exported conclusion evidence and bundled document heading anchors were checked.
- **Cleanup:** the two disposable verification reports and temporary Downloads artifacts were removed; the app was reopened.
- **Scope:** these were manual UI/storage/artifact checks and source review. Unit and instrumented suites were not run, following the repository's default cycle. Live paid-provider generations, redirect rejection and every provider format were not exercised end-to-end in this turn.

## Deliberate limits

- Translation review remains a review of unequal produced passages. A shared-passage/common-panel benchmark is not implemented, and no capability leaderboard is presented.
- The matrix scans English wording. A non-English or unknown report language is unavailable; quotations, negation and mixed-language answers can still mislead literal matching. Excerpts are exposed for inspection.
- Saved-source history is conservative. A legacy result without a valid original snapshot cannot be certified as current. A replay whose referenced identity metadata has been deleted fails explicitly; saved conclusion text and readable evidence remain available.
- Scores express a saved rubric or user preference. They do not establish truth. Independent reference text is supplied by the user and is not automatically verified.
- Live paid-provider generations are not needed for the non-generating UI and storage checks. Validation details distinguish inspected code from exercised behavior.

The commit also includes the pre-existing audit documents and `.github/workflows/ci.yml` deletion, following the repository rule to commit all current changes.
