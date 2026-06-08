# Audit Summary

Date: 2026-06-08

Worktree audited: `/Users/herbert/ai-codex`

Branch observed: `codex`

Status: static audit complete, 64 open findings.

## Highest-Risk Themes

1. TransRank has several lifecycle hazards: random non-reproducible
   sampling, hidden persisted runs when the prompt is missing, retries that
   lose worker configuration, deletion scoped too broadly, and token usage
   persisted without cache/reasoning/api-cost fields.
2. Report value/cost views can misattribute fan-out spend when model names
   differ only by case or when multiple successful report agents share the
   same provider/model.
3. Long-lived report artifacts can disappear from UI after internal prompt
   edits/imports because hydration requires the original prompt to still be
   resolvable.
4. Several storage helpers still construct child file paths from unchecked
   ids or filenames.
5. Some Compose screens still perform file-system scans or JSON loads
   directly in `remember`, which can block first paint on large local model,
   chat, or knowledge-base directories.

## Top Findings

1. **High - TransRank delete removes all rank attempts for a translation
   run.** `TranslatorRankEngine.deleteRun` filters by source translation
   run only, not by the current rank run id.
2. **High - TransRank retry loses the selected worker configuration.**
   Failed-cell restart rebuilds a minimal `Worker(provider, model)` instead
   of reusing the original prompt worker.
3. **High - TransRank persisted token usage drops cache/reasoning/api-cost
   fields.** The full `TokenUsage` is used for cost computation, but only
   input/output token counts are written to disk.
4. **High - Compare/Tournament/JudgeEval/TransRank persisted runs hide
   when the internal prompt is missing.** The UI drops otherwise recoverable
   run rows.
5. **High - `PermitHold.yieldFor` can leak permits if interrupted while
   reacquiring.** The reacquire loops do not undo partial reacquisition.
6. **High - `SecondaryResultStorage.get/exists/delete` do not validate
   `resultId`.** Safer update paths validate ids, but these direct helpers
   still build paths from raw strings.
7. **High - Prompt translations build file paths from raw language,
   category, and name.** A bad value can escape the translation root.
8. **Medium - Value View fan-out cost matching is case-sensitive for
   models.** The comment says case-insensitive matching, but the key keeps
   the model string as-is.
9. **Medium - Value View can double-count fan-out cost for duplicate
   provider/model report agents.**
10. **Medium - Chat session and knowledge-base reads still run in
    composition.**

## Recommended Test Targets

- Unit tests for `TranslatorRankEngine`: deterministic item sampling,
  failed-cell restart preserving worker config, delete scoping by
  `tournamentJudgeRunId`, hydrate fallback when prompt metadata is missing.
- Unit tests for `SecondaryResultStorage.recordTournamentMatch`: full
  `TokenUsage` round-trip, including cached input, cache creation,
  reasoning tokens, and `apiCost`.
- Unit tests for `ValueView.buildValuePoints` and fan-out fold-in:
  duplicate models, model-case differences, zero/unknown cost behavior.
- Unit tests for JSON parsers: malformed rerank ids/ranks, malformed report
  bundle `meta.json`, TransRank score responses without a first-line score.
- Compose/UI tests for chat and settings first paint with large local
  model/chat history directories.

