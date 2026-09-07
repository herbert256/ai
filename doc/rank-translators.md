# Translation review

`SecondaryKind.TRANSRANK` reviews translations already produced in one translation run. It does not re-translate passages. The historical engine/type names still contain “rank”, but the UI presents an **alphabetical review**, not a translator-model leaderboard.

Different translators may have received different passages and different judge coverage. Their averages therefore do not establish which translator is generally best. A controlled benchmark with shared passages and a common independent panel is not implemented; this feature makes no such claim.

## Inputs and judges

Only long-form `AGENT` and `META` translation rows with nonblank translated text and saved `translationSourceText` are eligible. Titles and the prompt are excluded. Legacy rows with no saved original are not scored using a later live answer.

The judge panel consists of distinct translator provider/model pairs represented in the source run. A model never scores its own translation. The `workers/translate-rank` prompt supplies the scoring rubric. Judge identities and execution settings are frozen for replay. Sampling spreads cells across passages before applying the per-translator cap of 25 cells.

Each cell scores the saved original/translation pair from 0 to 100 and records the judge's reason. A passage's received scores are averaged first; then passage averages are averaged for each translator. Extra judge cells cannot give one passage extra weight merely through cell count. The result list is sorted by provider and model, with no ordinal rank. New aggregate JSON includes an interpretation field and omits `rank`.

## Using it

Open the medal action on a translation run, review the proposed judge-call count, and choose **Review translations**. The result screen lists models alphabetically with item counts and mean received scores. Open a model, then a passage, to inspect individual judges and explanations. The Workers view groups scoring work by judge. Retry failed cells to keep completed scores; Delete removes the review run.

Each cell is a billable API call under the report's request and spend limits. Completion, retry and hydration are owned by `TranslatorRankEngine`, using the shared `SecondaryBatchEngine` lifecycle and saved `SecondaryResult` rows. Current translation text and saved original text are used for fresh runs; replay retains the run's captured input versions.

## Relationship to Value

Translation reviews never supply scores for the original-answer Value chart or Custom preference blend. Review a translation's own content, source and cost on the translation screens. A cheap original answer does not make an expensive translation a cheap answer-quality result.

See [translation](translation.md), [secondary results](secondary-results.md), and [functional fixes](functional-audit-remediation-2026-09-07.md).
