# Funny question Fan Meta monitoring — 7 September 2026

**Completed: 1,256 / 1,256 eligible answers now have a title and icon, with no new metadata failures.** All 19 configured worker providers produced results. The four errors shown by the final Fan Meta screen are unchanged failures of the earlier Fan Out. Completion does not mean every generated title meets the requested format: FM03 below documents malformed output accepted as successful.

The batch made **1,258 HTTP attempts**, all with final HTTP 200 responses. Two Together responses had empty content and were rejected; fallback workers recovered both. Recorded additional cost is **$0.105643623 (10.5643623¢)**. The existing report's total is now **$0.9292402619 across 2,638 costed calls**.

Requests started at **14:00:12.758** and the app logged batch end at **14:12:01.085**, Europe/Amsterdam: **11 minutes 48.327 seconds**, following roughly 41 seconds of staging. Five confirmed issues remain open. This task ran and monitored the feature; it did not change application source.

## Run scope

The user explicitly approved the prepared scope after automatic approval review initially rejected it: **1,256 successful Fan Out answers**, the **19-provider workers swarm**, sending the answer text to those providers, fallback attempts and API charges. See [the launch scope](fan-meta-launch-2026-09-07.md) for the exact model/origin table and unchanged prompt.

| Item | Value |
|---|---|
| Source revision | `8b562908e` (application changes through `4897504c1`) |
| Report | Funny question request — Request for a Funny Question |
| Report ID | `4757c2cc-36e7-4d2e-9a9c-531bca7162dd` |
| Existing Fan Out prompt | `response — Just the response` |
| Fan Out prompt ID | `594de4a7-854c-4004-971f-8e70f879fa87` |
| Fan Meta run ID | `aa426a44-24e5-48d5-9286-71a9dec28775` |
| Worker prompt | `workers/fan-meta`; title of at most 30 characters and one fitting emoji |
| Eligible jobs | 1,256; the four pre-existing Fan Out failures are excluded |
| Report worker mode | Prompt-configured pool; not the original answer models |
| Configured concurrency | Global 100; Fan Meta 100, observed in Live Dashboard |
| Request allowance | 5,000 HTTP operations including fallbacks and retries |
| Endpoint restriction | Enabled; all 36 displayed endpoint controls inspected, exactly the 19 approved worker origins selected |
| Baseline | 36 successful primary answers; 1,260 Fan Out rows; 1,380 cost entries; $0.8235966389 lifetime report cost |

No main report or Fan Out answer was regenerated. Existing Fan Out failures were not retried as part of this task. The prompt and model pool were not changed. No source-code fixes were applied during the monitored run.

## Findings

### FM01 · P2 — The work review lists the wrong models and instructions

The actual Fan Meta work review displayed the **36 original report models**, headed “Saved answer”, instead of the **19 current metadata workers**. This confirmed the source finding recorded before launch. Its instruction fallback also comes from the original primary generation configurations, not the Fan Meta template.

`IconGenerationManager.runFanMetaBatch` starts a new run without supplying its effective worker prompt as a `ReportWorkPlan` or registering corresponding evidence. `runThrottledBatch` invokes the generic review; `ReportWorkLimits.savedWorkPlan` finds no prompt for the fresh run ID and falls back to `report.agents`. The displayed recipient list therefore does not describe the operation being approved.

For this run, the saved swarm and provider registry were independently resolved, and the app's endpoint controls were narrowed to those 19 origins. A fresh read-only pass checked all 36 controls, and the persisted work limit was checked after launch. Fix the review to use the frozen, effective Fan Meta prompt and expanded workers, including the own-model routing case when selected. Avoid broadening the prompt's recipient list to unrelated report agents.

Sources: [Fan Meta launch](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/IconGenerationManager.kt:2912), [generic batch review](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/ThrottledBatch.kt:113), [saved-work fallback](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportWorkLimits.kt:44).

### FM02 · P1 — Report-screen reloads block completed workers behind the cost journal

During the run, the Fan Meta screen remained at **108 completed / 1,048 queued**, with another 100 jobs active or waiting. The observer had already captured about 198 finalized HTTP 200 traces. A thread snapshot found **34 threads at `ReportCostJournal.enqueue`** and **28 report initialization lock waits**. The cost flusher was waiting in `ReportStorage.appendApiCallCosts` while holding the journal monitor. A UI runtime-state reload held the report lock while `ReportContentStore.unpack` parsed and re-serialized the report.

Two UI refresh paths repeatedly reload the full report: `RuntimeState` reacts to `iconRefreshTick` and calls `getReport` to read `costsFromDeletedItems`; the secondary-results drill-in hydrates `FanOutEngine` on its throttled refresh ticks. These synchronous file/parsing operations do not become promptly cancellable simply because their `LaunchedEffect` is re-keyed. Meanwhile `ReportCostJournal.flush` keeps its global journal monitor while waiting for and rewriting report storage. This prevents otherwise completed calls from enqueuing their small durable cost records, finishing their pair and releasing capacity.

Moving to Monitor / Live Dashboard left the batch running and allowed observation without repeatedly rebuilding the report screen. This is a runtime workaround, not a source fix or a controlled performance benchmark. A single brief debugger snapshot paused/resumed the VM; its timing disturbance is acknowledged. The blocking stack and source paths, rather than a before/after speed ratio alone, establish the finding.

Separate startup overhead was also visible: the 1,256 per-row “started” timestamps span **40.634 seconds** before requests were released. The UI showed a queue while this staging work completed. Review work before the expensive staging phase, expose preparation progress, and reduce repeated file/directory work without weakening durability.

Recommended correction: take a journal batch under its monitor, release that monitor before appending to report storage, and acknowledge records safely afterwards using existing stable IDs. Cache or narrowly read the report fields needed by UI refreshes; avoid redundant hydration when the engine already updates each completed pair. Preserve crash recovery and cancellation semantics.

Sources: [journal locking](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportCostJournal.kt:29), [report cost append](/Users/herbert/ai/ai/src/main/java/com/ai/data/ReportStorage.kt:1758), [runtime UI reload](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/RuntimeState.kt:271), [secondary hydration](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/view/Secondary.kt:602), [row staging](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/IconGenerationManager.kt:2967).

### FM03 · P2 — Fan Meta accepts and displays malformed titles

The saved results include literal Markdown and label prefixes such as `**Title:** *Sweet Tooth Delight*` and `**title:** *Watch Band Essentials Guide*`. The app marks these as successful metadata. **141 titles retain a Markdown prefix; 119 exceed the prompt's 30-character limit**, with a longest stored title of 60 characters. These groups overlap; they must not be added together. Counts are based on stored text, including retained formatting characters.

The completed “Fan Meta - All” list and a pair detail both visibly showed `**Why He Stood Out**` with its asterisks. The pair identified `ministral-14b-latest` as the metadata model. This confirms the malformed title reaches the UI rather than remaining only in raw storage.

`parseFanMetaTitle` recognizes only lines beginning directly with `title`. Markdown-wrapped labels fall into its fallback, after which `cleanTitle` keeps the first nonblank line and permits up to 325 characters. The acceptance predicate accepts an emoji **or** any nonblank parsed title; it does not validate the requested title shape or length. The model's formatting deviation is a provider-output behaviour; preserving the label and formatting as a supposedly cleaned title is an application parsing defect.

Normalize permitted Markdown/label variants before accepting the result, validate the intended title length, and treat unparseable output as a worker miss. Keep the original response available for diagnosis rather than silently representing malformed text as a valid cleaned artifact.

Sources: [title parsing and cleanup](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/IconGenerationManager.kt:2283), [worker acceptance](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/IconGenerationManager.kt:3073).

### FM04 · P2 — Metadata results discard their exact request trace reference

`runPooledWorkerCall` returns an immutable `traceFile`, but `runFanMetaForPair` never passes it to storage. `recordFanMetaResult` persists the title, icon, model and run IDs without a metadata attempt trace reference. The row's generic `traceFile`, where present, still belongs to its Fan Out answer. `FanMetaL3Screen` provides no direct trace control for the metadata call.

The report cost ledger and run-scoped trace directory retain request records, but they do not supply a direct link from a particular metadata result to its winning or rejected attempts. Identical answer texts and repeated titles make reconstruction by model, time or content ambiguous in a large concurrent batch.

Persist distinct metadata attempt references, including rejected/fallback attempts and failures, and wire the pair-detail trace control to those saved references. Do not overwrite the existing Fan Out response trace.

Sources: [worker call result](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/SecondaryCellCalls.kt:41), [metadata result recording](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/IconGenerationManager.kt:3090), [saved metadata fields](/Users/herbert/ai/ai/src/main/java/com/ai/data/SecondaryResult.kt:1010), [pair screen](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/FanMetaL3.kt:68).

### FM05 · P2 — The Fan Meta subtotal omits paid rejected attempts

Fan Meta's saved per-pair costs sum to **$0.105533523**, displayed as **10.55¢** on the summary and **10.5534¢** in Second results. The report ledger records **$0.105643623** for this run. The **$0.0001101 (0.01101¢)** difference exactly matches the two billed Together responses rejected for empty content.

`WorkerRunner` correctly records rejected attempts as `worker/rejected`. The Fan Meta result stores the winner's usage, and its UI computes the subtotal only from the pair's title/icon cost fields. Rejected attempts therefore appear in lifetime report accounting but vanish from the batch subtotal. This is a display/attribution defect; no paid attempts were lost from the report ledger in this run. The amount is small here but grows when retries or rejected answers are more frequent.

Associate every attempt's cost with its metadata pair and run, and calculate the Fan Meta subtotal from all those attempts. Avoid double-counting the winning attempt or the original Fan Out answer.

Sources: [rejected-attempt accounting](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/WorkerRunner.kt:223), [winning usage](/Users/herbert/ai/ai/src/main/java/com/ai/viewmodel/IconGenerationManager.kt:3088), [Fan Meta cost subtotal](/Users/herbert/ai/ai/src/main/java/com/ai/ui/report/manage/FanMetaL1.kt:101).

## Provider behaviour and verification

All approved worker models are listed in the [launch scope](fan-meta-launch-2026-09-07.md). Provider counts below reflect winning metadata rows and all costed attempts, including rejected attempts. Prices are the app's recorded costs, not an independent reconciliation with provider invoices.

| Provider | Completed metadata rows | Attempts | Recorded cost |
|---|---:|---:|---:|
| Mistral | 71 | 71 | $0.003228200 |
| OpenAI | 77 | 77 | $0.002122650 |
| Groq | 37 | 37 | $0.003836025 |
| Cerebras | 78 | 78 | $0.020002500 |
| DeepSeek | 48 | 48 | $0.015290096 |
| Google | 67 | 67 | $0.001107100 |
| Anthropic | 73 | 73 | $0.015971000 |
| xAI | 75 | 75 | $0.020963700 |
| Cohere | 53 | 53 | $0.000529800 |
| DeepInfra | 73 | 73 | $0.000740600 |
| Together | 78 | 80 | $0.005824750 |
| Parasail | 71 | 71 | $0.000677700 |
| NebiusAIStudio | 50 | 50 | $0.000716700 |
| Novita.ai | 70 | 70 | $0.000498450 |
| OpenRouter | 63 | 63 | $0.000231552 |
| VercelAIGateway | 67 | 67 | $0.001117900 |
| Amazon | 71 | 71 | $0.009849000 |
| Requesty | 71 | 71 | $0.001352400 |
| Glama | 63 | 63 | $0.001583500 |

Together's two empty responses used `openai/gpt-oss-20b`, reported `finish_reason=stop`, and included billed output usage (244 and 239 tokens). Both were final HTTP 200 responses with empty `message.content`. They are provider output failures recovered by the existing fallback path. No new HTTP 429, 4xx or 5xx response was captured for this run.

Final verification:

- All 1,256 eligible rows contain both a title and an icon under the new run ID, with no metadata error fields.
- All 1,258 ledger references resolve to distinct final trace files still present on the emulator. Every trace matches the approved provider/model pool, report, run and category.
- The multiset of winning request payloads exactly matches the 1,256 eligible answer texts substituted into the approved template. This is an aggregate coverage check; duplicate text prevents unique pair attribution without the missing references in FM04.
- All 36 primary answer records, existing cost records, original Fan Out response fields and execution parameters were preserved after the empty-default normalization described below. All four failed Fan Out rows remained unchanged.
- The report ledger reconciles to the added attempts; the metadata-row subtotal reconciles to winning attempts. No pending cost-journal directory remains for this report.
- The endpoint restriction still lists exactly the approved 19 origins. The allowance has **3,742 of 5,000 requests left**, consistent with 1,258 attempts.
- The app logged `FanMeta: ← end` at 14:12:01.085. The observer first saw every row complete at 14:12:05.710. Live Dashboard subsequently showed **Idle, 0/100 calls in flight**.
- Reopening the existing Fan Meta screen showed **Total 1,260; Done 1,256; Error 4; Run 0; Wait 0; Queue 0**. “View errors” identifies only the original Baseten timeout, NVIDIA 503, Nebius empty answer and SiliconFlow certificate-chain failure.

Metadata duration fields ranged from 3.877 seconds to 689.592 seconds, median 31.702 seconds. These measurements include application-side waiting and completion work; they are not isolated provider response latency and should not be used as a provider speed ranking.

## Evidence and limitations

The before-state, interval samples, sanitized request/response traces, UI review snapshots and debugger stack are retained temporarily under `/tmp/funny-fanmeta-20260907/`. The trace copies omit HTTP headers and endpoint query parameters. Provider outputs are treated as data, never as instructions to this audit.

The initial strict JSON comparison detected only newly materialized empty `sourceAgentIds` and `executionConfig.parameters.stopSequences` defaults when older rows were rewritten. These were verified field by field and normalized in subsequent preservation checks. The original answer content, execution parameters and source references remain the preservation targets; byte-for-byte equality is not claimed across that harmless default serialization.

The permanent [structured evidence](fan-meta-monitor-2026-09-07-evidence.json) contains final counts, reconciled costs, per-provider totals, title examples, attempt trace filenames, relevant thread-stack excerpts and verification results. Monitoring collected 60 interval samples plus focused UI/log/thread observations. There were no observed fatal-exception, app-ANR or storage-write-failure markers in the process-scoped log. This does not prove the absence of every possible issue or assess every title's semantic quality.

The temporary collector was stopped after completion and the debugger detached. The emulator was left in `com.ai/.MainActivity` on the completed Fan Meta summary. No source changes, build/deploy, unit tests, instrumented tests, reinstall or destructive reset were performed. Only the monitoring documentation and launch status were updated. All five findings above remain open.
