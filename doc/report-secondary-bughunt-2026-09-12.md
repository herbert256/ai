# Report and secondary-result bug hunt — 12 September 2026

Two Housekeeping stress batches created **38 reports and 228 successful primary answers** on `emulator-5554`. Explicit secondary runs produced **69 saved rows**, all nonempty and without terminal errors. The final report ledgers contain **889 costed calls**, matching **889 successful retained HTTP traces** without duplicate links. Estimated/reported aggregate cost is **$0.3918560255**; this is app accounting, not an invoice.

## Work exercised

Each batch used the 19 existing example prompts and the six configured Level 2 models: Anthropic Sonnet, DeepSeek Chat, Mistral Medium, OpenAI Mini, xAI Grok, and Google Flash. Secondary autostart remained disabled; runs were launched explicitly from the report UI.

| Workflow | Settled result |
|---|---|
| Primary generation | 38 reports; 228/228 answers successful |
| Rerank | One row with six source identifiers and six ranked entries |
| Moderation | One row; six synthetic calendar answers, none flagged |
| Meta | One comparison and one summary |
| Fan-out | Two cross-model pairs; exactly the selected OpenAI/Google sources, no self-pairs |
| Fan-in | One combined answer, confirming Thursday for the calendar example |
| Translation | Six selected answer bodies translated to Dutch; titles and other content excluded |
| Translation review | 18 scoring cells and one aggregate; six passages and four translator models |
| Compare with Meta | Six completed cells against the selected summary; saved source coverage 6/6 |
| Tournament | 30 completed directed matches and one aggregate; saved source coverage 6/6 |

Secondary work was attached to the newly created calendar report `24269a11-fedb-47f7-8653-5c24547eeef5` and self-introduction report `0f3c4602-326a-4847-9c9f-7c2967d98500`. Generated reports remain device data, not bundled defaults.

## Findings and fixes

1. **Report storage contention delayed navigation and cost flushes.** During the first batch, report-opening taps waited minutes while metadata writes continued. A Java stack capture showed 63 operations waiting for the shared storage lock, including navigation reads and the cost-flush worker. The lock is now fair; catalog scans release it between coherent per-report reads, and hydration avoids an unnecessary JSON stringify/parse round trip. In the second batch, a report opened in 6.45 seconds including accessibility capture, and sampled cost totals continued to match the ledger. This is a responsiveness improvement under observed load, not a claim that all UI jank is eliminated.
2. **Fan-in selection cancelled its own launch.** The effect cleared its selection before a suspending settings read, removing itself from composition. Selection appeared to do nothing. Clearing now occurs after the read. The same action subsequently opened the runtime prompt editor and completed a saved fan-in result.
3. **Interrupted billable responses were missing from accounting.** Nine first-batch requests returned successful responses, then timed out before delivery and were retried. Their earlier usage did not reach the caller's accounting path. The dispatch audit now recovers provider usage from its exact completed trace before propagating interruption. Ledger version 5 repairs retained historical evidence. Runtime migration added exactly nine calls and **$0.01261395**, changed no pre-existing call rows, and left no successful trace unaccounted for. The new interruption branch was compiled and reviewed; a second forced transport cancellation was not injected.
4. **Action descriptions were misleading.** Translate shared a glyph with translation comparison and inherited the name “Compare.” Its action now has explicit accessibility/help text. Translation-review launch and icon help no longer promise a translator leaderboard. A disabled Compare-with-Meta action now explains its prerequisites. The fan-in article and obsolete whole-report-only translation documentation were corrected.

## Observations and limits

- One Nebius metadata call timed out; fallback continued. This is the sole non-200 retained trace for these reports. No fatal exception or Android ANR was found in the captured run log.
- A metadata model generated the incorrect title “Tuesday + 4 = Saturday.” The trace contains the correct question and explicit title-only instructions; the six primary answers correctly say Thursday. This is an observed model-output failure, not evidence of corrupted prompt forwarding. Title correctness is not guaranteed by these fixes.
- Initial stack capture and memory profiling added diagnostic overhead. Heavy profiling was stopped before the regression batch; later monitoring sampled persisted status and cost data.
- Judge-the-judges, export/import, and every possible cancellation/retry combination were not exercised. No extended unit/instrumented suite was requested or run.
- Raw snapshots, trace copies, UI captures, and the stack dump are under `/private/tmp/ai-report-bughunt-20260912/`. The repository contains this summary and fixes, not personal report exports or credentials.

## Delivery checks

The debug build succeeded and was installed without uninstalling the app. The same APK was copied to `/Users/herbert/cloud/ai.apk`; foreground launch and APK parity were checked. SHA-256: `78e82503795402b98092e9a19eb438468872bd2b2d068a5e011ed72f24737b7f`. A further restart retained all 69 successful secondary rows and left all 889 ledger entries exactly unchanged. The final UI exposes Translate and Translation review with their corrected accessible names.
