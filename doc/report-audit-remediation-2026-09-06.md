# Report audit remediation — 6 September 2026

All 28 audit findings have implementation changes. The original HTML findings
remain a historical record of the pre-fix source; this document describes the
new behavior and the validation actually performed.

## Changes by finding

| Finding | Change | Main implementation |
|---|---|---|
| R01 · Stable score identity | Rerank rows join saved source Agent IDs before projection into the current answer order. Removed or reordered participants cannot relabel a surviving score. | [RerankTable.kt](../ai/src/main/java/com/ai/ui/helpers/RerankTable.kt) |
| R02 · Replay model identity | Full replay builds tasks from the saved provider/model and Agent row ID, including when the live Agent now points elsewhere. | [ReportViewModel.kt](../ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt) |
| R03 · Acknowledged saves | Report and secondary writes fail explicitly. The UI retains unsaved JSON for retry or copying; retries merge against newer disk state and refuse conflicting changes. | [ReportSaveRecovery.kt](../ai/src/main/java/com/ai/data/ReportSaveRecovery.kt) |
| R04 · Independent accounting | Report ledger recording runs before the optional aggregate-statistics toggle, so disabling statistics no longer suppresses Report accounting. | [SettingsPreferences.kt](../ai/src/main/java/com/ai/data/preferences/SettingsPreferences.kt) |
| R05 · Durable cost queue | Every captured call gets a UUID journal record. A trailing flush appends deduplicated records; failed writes retain pending data for the next flush. | [ReportCostJournal.kt](../ai/src/main/java/com/ai/data/ReportCostJournal.kt) |
| R06 · Rejected worker costs | Usage from paid but rejected worker outputs and fixed-judge parse failures is recorded. Primary and secondary usage accounting occurs before saving the answer. | [WorkerRunner.kt](../ai/src/main/java/com/ai/viewmodel/WorkerRunner.kt) |
| R07 · Orphan-Agent completion | Deleted Agents retain their original report row identity on replay. Unresolvable providers become explicit errors instead of leaving unmatched pending tasks. | [ReportViewModel.kt](../ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt) |
| R08 · Saved execution settings | Primary attempts persist resolved prompt, parameters and endpoint. Batch manifests preserve expanded workers, prompt settings and runtime Tournament temperature for replay. Credentials stay live references. | [ReportEvidenceStore.kt](../ai/src/main/java/com/ai/data/ReportEvidenceStore.kt) |
| R09 · Original analysis inputs | Immutable source snapshots preserve answer text and participant identity. Translation reviews freeze original and translated passages. Unknown or changed revisions are labelled historical and excluded from current-answer comparisons. | [ReportEvidenceStore.kt](../ai/src/main/java/com/ai/data/ReportEvidenceStore.kt) |
| R10 · Retry and scheduling semantics | Primary work precedes optional metadata. Retry skips successful siblings, waits for submitted work to settle, and directly retries unfinished rows. Stop scheduling explicitly allows submitted calls to finish and incur cost. | [RegenerateBatchEngine.kt](../ai/src/main/java/com/ai/viewmodel/RegenerateBatchEngine.kt) |
| R11 · Empty scopes | Empty Manual and Top Ranked selections remain empty instead of expanding to all answers. | [SecondaryModels.kt](../ai/src/main/java/com/ai/data/SecondaryModels.kt) |
| R12 · Local control contract | Local requests include resolved system instructions and apply supported temperature, top-p, top-k and seed controls. Unsupported controls and non-default context overrides produce explicit errors. | [AnalysisRepository.kt](../ai/src/main/java/com/ai/data/AnalysisRepository.kt) |
| R13 · Worker control scope | Workers resolve prompt, bound-Agent and app defaults before dispatch; frozen worker configuration is used by batch retries. The UI no longer claims primary controls govern every call. | [ReportViewModelHelpers.kt](../ai/src/main/java/com/ai/viewmodel/ReportViewModelHelpers.kt) |
| R14 · Shared knowledge context | One retrieval result is saved and reused for the report answers. Configuration/retrieval failures stop grounded generation with an explicit error; empty successful retrieval is distinguished. | [ReportKnowledge.kt](../ai/src/main/java/com/ai/viewmodel/ReportKnowledge.kt) |
| R15 · Portable export redaction | Single-report bundles and bulk archives redact credential fields, authorization headers, bearer tokens and sensitive URL parameters, including embedded legacy diagnostic JSON. | [ReportExportRedaction.kt](../ai/src/main/java/com/ai/data/ReportExportRedaction.kt) |
| R16 · Transactional Report bundles | ZIP entries are streamed into bounded staging, validated and remapped. Evidence and secondary files commit before the parent report; a journal rolls back interrupted imports. Every final write is checked. | [ReportBundle.kt](../ai/src/main/java/com/ai/data/ReportBundle.kt) |
| R17 · Duplicate preference | Report duplication carries metadataDisabled, preserving the saved metadata opt-out. | [ReportViewModel.kt](../ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt) |
| R18 · Distinct Agent variants | Selection deduplicates configured Agents by Agent identity and direct models by provider/model. Two Agent roles on one model remain separate report rows. | [SettingsModels.kt](../ai/src/main/java/com/ai/model/SettingsModels.kt) |
| R19 · Explicit group provenance | Group parameters and system prompts follow the selected Flock/Swarm source; unrelated group membership no longer changes a direct pick. | [ReportViewModel.kt](../ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt) |
| R20 · Meaningful value display | The chart highlights all priced Pareto-frontier options and removes the arbitrary quality/cost-ratio winner. Current answer cost is separate from cumulative and fan-out spend. | [ValueView.kt](../ai/src/main/java/com/ai/ui/report/view/ValueView.kt) |
| R21 · Evidence families and coverage | Tournament methods share one evidence-family contribution. Combined requires common coverage across informative families and excludes translation ability from original-answer quality. Ignored translation-weight controls were removed. | [ValueView.kt](../ai/src/main/java/com/ai/ui/report/view/ValueView.kt) |
| R22 · Independent judge agreement | Agreement excludes the judge being evaluated and requires a unique plurality from at least two other distinct judges. The UI reports the eligible sample count and unavailable evidence. | [JudgeAgreement.kt](../ai/src/main/java/com/ai/data/JudgeAgreement.kt) |
| R23 · Translation review limits | Sampling spreads the capped judge cells over passages; aggregation weights passages equally. The interface describes a translation review and explains that differing passages/judges are not a controlled benchmark. | [TranslatorRankModel.kt](../ai/src/main/java/com/ai/data/TranslatorRankModel.kt) |
| R24 · Honest wording cues | English lexical heuristics are labelled Wording cues and Refusal wording, with unknown states and limitations for quotation, negation and other languages. | [AnswerMatrix.kt](../ai/src/main/java/com/ai/ui/report/view/AnswerMatrix.kt) |
| R25 · Attempt cost and estimates | Current-attempt usage and cost are stored separately from lifetime spend. Estimated usage is flagged in Matrix/Value/cost displays; replacing answer text clears obsolete attribution. | [ReportModels.kt](../ai/src/main/java/com/ai/data/ReportModels.kt) |
| R26 · Bounded work and storage | Large answer/request strings use immutable content files, keeping frequent parent writes smaller. Report caches and coroutine windows are bounded; oversized batches and import archives are rejected. Bulk exports materialize the text. | [ReportContentStore.kt](../ai/src/main/java/com/ai/data/ReportContentStore.kt) |
| R27 · Producer and action labels | Manage and Matrix show model identity alongside generated titles. Score-against-meta wording distinguishes the scoring operation from prose comparison. | [ContentDisplay.kt](../ai/src/main/java/com/ai/ui/report/manage/view/ContentDisplay.kt) |
| R28 · Work review and limits | Shared preflight shows item count and request allowance, plus an optional recorded-cost stop. The HTTP interceptor reserves requests durably, including retries and metadata; a batch is limited to 5,000 items. | [ReportWorkLimits.kt](../ai/src/main/java/com/ai/data/ReportWorkLimits.kt) |

## Compatibility and limits

- Existing Reports still load. Legacy answers without attempt-cost provenance
  stay unknown, and historical analyses without a source snapshot remain readable
  but do not influence current-answer rankings. Missing historical costs or
  original inputs cannot be invented retrospectively.
- Single-report bundle version is now **2**, adding evidence. Imports accept
  versions **1 and 2**. Old app versions cannot read version 2 bundles. Full
  backups include the content and evidence directories; exports materialize text.
- Replay preserves saved non-secret settings when available. Credentials still
  resolve from current settings; providers can change a hosted model's behavior.
  Legacy runs without manifests cannot promise exact configuration replay.
- Unsaved payload recovery survives while the app is open. A device that cannot
  write any storage cannot durably retain a newly received answer across process
  death. Copy the payload or restore writable storage before closing the app.
- The spend stop uses recorded usage and prices. In-flight calls and delayed
  journal flushing can overshoot it; unknown prices cannot give a reliable dollar
  estimate. The HTTP request allowance is a separate hard request ceiling.
- The Local engine retains a fixed 2048-token context. Unsupported generation
  controls are rejected explicitly. No new local model was downloaded.
- Content blobs and source revisions remain until report deletion. Queues and
  caches are bounded, but very large report reads still materialize answer text;
  this change does not establish a measured maximum device capacity.

## Validation performed

- PASS: Kotlin compilation and full `:ai:assembleDebug` using Java 25.
- PASS: `adb install -r` on the emulator; existing app data preserved.
- PASS: APK copied to `/Users/herbert/cloud/ai.apk`; built, installed and cloud
  APK SHA-256 hashes match.
- PASS: existing large Report opens with its saved costs; a historical Meta result
  displays the original-source-revision warning after asynchronous loading.
- PASS: a temporary local-only report reached a one-item work preview. Cancelling
  preserved its exact original answer, SUCCESS status and empty API-cost ledger.
- PASS: an orphaned Agent row displayed the provider/model saved in the report.
- PASS: a pin change stored its 7,147-byte answer in an immutable file; the
  referenced SHA-256 and full text matched the original exactly. The Model response
  screen subsequently displayed the hydrated answer and its recorded Local model.
- PASS: HTML parses with unique IDs and valid internal navigation targets.
- The temporary fixture and its supporting files were removed after verification.
- PASS: `com.ai/.MainActivity` foreground confirmation; no matching AndroidRuntime,
  ReportStorage, AtomicFileWrite or Crash errors in the inspected recent log slice.
- No unit or instrumented suites were run: repository instructions reserve those
  for an explicitly requested extended cycle. No paid provider calls were used.
- Not exercised end to end: real-provider generation across all API formats,
  native Local inference, disk-full/process-death fault injection, and large
  archive/device-memory stress. Code review and build success are not substitutes
  for those runtime scenarios.
