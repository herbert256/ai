# Report follow-up bug hunt — 6 September 2026

A second source review found and fixed the following 12 issue groups after the
first remediation commit (`e87c6b8a2`). The original audit remains a historical
record; this follow-up describes the additional changes.

| ID | Failure and correction | Main implementation |
|---|---|---|
| F01 · Mixed-run source snapshots | Bulk secondary saves could copy the first run’s evidence onto rows from other runs. Each row now keeps its own snapshot or manifest; editing a completed legacy row does not invent original inputs. | [SecondaryResult.kt](../ai/src/main/java/com/ai/data/SecondaryResult.kt) |
| F02 · Recovery after read failure | Reading the existing file could fail before save recovery was registered. The unsaved payload now enters recovery; an unknown base cannot be overwritten by a blind retry. | [ReportSaveRecovery.kt](../ai/src/main/java/com/ai/data/ReportSaveRecovery.kt) |
| F03 · Blob integrity | An existing content file was trusted solely by filename. Loading now checks its SHA-256 digest and rejects changed content. | [ReportContentStore.kt](../ai/src/main/java/com/ai/data/ReportContentStore.kt) |
| F04 · New-report overwrite guard | A corrupt existing report looked absent to persistNewReport. The writer now checks file existence and validates the ID before either write path. Report deletion also removes its work-limit file and pending cost directory under their owners’ locks. | [ReportStorage.kt](../ai/src/main/java/com/ai/data/ReportStorage.kt) |
| F05 · Received-answer completion | Stop could cancel the IO hop after a response arrived, and a failed cost-journal write could prevent its answer save. Primary and single-secondary completion now protects accounting and saving; accounting errors cannot skip the answer save. | [ReportCompletion.kt](../ai/src/main/java/com/ai/viewmodel/ReportCompletion.kt) |
| F06 · One preview per secondary retry | A secondary retry cleared its result after one preview and then requested another. Scoped approval now reaches the dispatch, so cancelling the initial preview preserves the saved result. | [SecondaryRunManager.kt](../ai/src/main/java/com/ai/viewmodel/SecondaryRunManager.kt) |
| F07 · Approval isolation | An active regeneration globally suppressed previews for unrelated operations on the same report. Approval now travels in the initiating coroutine and explicitly inherited child-job contexts. | [ReportWorkLimits.kt](../ai/src/main/java/com/ai/data/ReportWorkLimits.kt) |
| F08 · Malformed and repeated limit approvals | Malformed limits could escape an OkHttp interceptor as unchecked errors; repeated Run clicks could reset an already granted allowance. Limit parsing is strict, failures become IOExceptions, completed previews cannot grant again, and reservation retries if the limit changes while its spend baseline is read. | [ReportWorkLimits.kt](../ai/src/main/java/com/ai/data/ReportWorkLimits.kt) |
| F09 · Poisoned cost journals | One malformed pending entry stopped later records, other reports and aggregate-statistics persistence. Valid entries now flush independently, invalid entries remain for repair, and aggregate statistics still save. | [ReportCostJournal.kt](../ai/src/main/java/com/ai/data/ReportCostJournal.kt) |
| F10 · Knowledge retrieval race | Retrieval could save old context, or stamp errors onto new work, after the prompt or attached KB IDs changed. Both success and failure updates compare the original inputs under the report lock. | [ReportKnowledge.kt](../ai/src/main/java/com/ai/viewmodel/ReportKnowledge.kt) |
| F11 · Secondary retry provenance and cleared state | Deleted live prompts blocked saved retries. Retries can use saved manifests/execution prompts and historical source order. Rerank/moderation now refresh the cleared row instead of restoring its old answer and banked costs. | [SecondaryRunManager.kt](../ai/src/main/java/com/ai/viewmodel/SecondaryRunManager.kt) |
| F12 · Import schema and transaction identity | Fractional versions/counts and malformed nested fields passed permissive deserialization. Import now validates core rows and evidence before final writes, accepts exact integer versions/counts, rejects unresolved local body references, and prevents overlapping imports with the same destination ID. Validated reports pass through the storage normalizer before copying, preserving compatibility with older bundles that omit worker configuration. | [ReportBundle.kt](../ai/src/main/java/com/ai/data/ReportBundle.kt) |

## Verification

- `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :ai:assembleDebug` passed.
- Installed with `adb install -r`; copied the same APK to `/Users/herbert/cloud/ai.apk`.
- On-device imports rejected an invalid Agent status, a fractional export version,
  and a null source-answer entry without creating a report or leaving a transaction marker.
- A version 1 fixture initially exposed missing worker defaults; after the fix,
  both version 1 and version 2 fixtures imported successfully.
- The version 2 fixture retained its secondary answer and saved execution prompt.
  Its live InternalPrompt entry was absent. Reload still opened a work preview;
  Cancel preserved the answer. A subsequent Run reached the unavailable Local
  model, with no second preview, and saved the expected error on the same row.
- Both temporary reports were deleted through the UI. Their report/secondary files
  and retry work-limit file were absent afterward; no pending cost directory or import
  journal remained. The original report was still present. The final app was
  confirmed foreground, with the installed APK matching the workspace/cloud checksum
  and no AndroidRuntime fatal exception in the final log window.
- HTML parsing, unique IDs, internal anchors and `git diff --check` passed.

APK SHA-256 (workspace and cloud copy):
`5dc9cb784d74d8ac03a3b4a69deb58583cdca894101d3e4ec7f6a94ab3ae1b7e`.

## Limits

The default repository cycle was used. Unit and instrumented suites were not run;
those belong to the explicitly requested extended cycle. No paid provider calls
were made. Concurrency races, full-disk failures and corrupt accounting records
were checked through the affected source paths, not injected on the user’s data.
The emulator repeatedly reported a system-process ANR during one cold start;
it was rebooted, then relaunched with a temporary 4 GiB RAM allocation, with
app data preserved before continuing verification. These changes are not a claim that all provider behavior or every Report path
has been exhaustively tested.
