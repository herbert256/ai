# Funny question fan-out monitoring audit — 7 September 2026


## Final outcome

The approved fan-out finished at **11:56:57 Europe/Amsterdam**: **1,245 successful pairs, 15 errors, zero unfinished**, from all **1,260** intended source/responder combinations. Runtime was approximately **39 minutes 26 seconds**, including the host pause described below. Every provider produced at least one successful answer; **30 of 36 providers completed all 35 pairs successfully**.

The fan-out added **$0.6977812352 (69.7781¢)** in recorded costs. The whole report now has **1,367 costed calls** and **$0.8186242033 (81.8624¢)** in lifetime costs. These are the app's provider-usage/catalog-price records, not independently reconciled provider invoices.

**Original audit status (superseded by remediation below): six application findings were open:** redundant paid generations after recovered rate limits (P1); missing secondary-result trace links, slow fixed-window scheduling, unwired Fan Meta autostart, truncated Replicate trace model IDs, and missing fan-out HTTP statistics (P2). Five were observed during this run and traced to source; Fan Meta autostart is a source-only finding because the master autostart switch was off. No app source was changed during monitoring.

The final emulator screen is the completed **Fan out / response — Just the response**. All failed rows remain available for inspection; no manual retries, regeneration, Fan Meta or Fan-in were launched.

**Remediation update — 7 September 2026:** all six application findings are fixed. See [fan-out fixes and emulator verification](fan-out-fixes-2026-09-07.md) for the speed-regression cause, controlled checks, preservation record and historical limitations.

## Run identity and authorization

The user approved the prepared 1,260-call fan-out and its provider charges. The run started on 7 September 2026 at approximately **11:17:31 Europe/Amsterdam**, with **response — Just the response** selected and its `@RESPONSE@` template unchanged.

The app first persisted all 1,260 placeholders and displayed its work review. Automatic approval review blocked that review's broad fallback/retry scope. After verifying that every pair exactly matched the approved provider/model set, endpoint restriction was enabled for the listed origins and the narrower launch was approved. The review's existing allowance is 5,000 HTTP operations for the 1,260 logical generations, including configured retries and Replicate polling. No extra fan-out scope, models or metadata job was launched manually.

| Setting | Value |
| --- | --- |
| App source revision | `f5d860adf` (documentation revision at launch: `d24b2c9df`) |
| Emulator / app process | `emulator-5554` / `com.ai`, PID 7148 |
| Report | Funny question request — Request for a Funny Question |
| Report ID | `4757c2cc-36e7-4d2e-9a9c-531bca7162dd` |
| Fan-out run ID | `c5335659-6265-44a8-9c20-b1238ff562ad` |
| Predefined prompt | `response` — **Just the response** |
| Template | `@RESPONSE@`, unchanged |
| Initiators | All 36 successful saved answers |
| Responders | All 36 report models listed below |
| Self-pairs | Excluded |
| Logical pairs | 36 × 35 = **1,260**, unique and exactly matched against the baseline |
| Payload | Each source answer replaces `@RESPONSE@`; the responder receives that answer text |
| Existing report baseline | 116 costed calls, $0.1208429681 |
| Parameter/system-prompt overrides | None added |
| Endpoint restriction | Enabled for the approved provider origins |
| Autostart master | Off (`autostart_items_enabled=false`) |
| Fan Meta preference | On, but master off; no separate Fan Meta launch |

Monitoring retains interval samples of persisted pairs, report-ledger changes and report-scoped traces, together with logcat and live UI inspections. Provider outputs are treated as data; their instructions are not followed by the monitoring process.

## Selected destinations

Each responder receives the other 35 models' source answers. Hostnames below come from the existing report's recorded endpoint configuration; the app dispatches requests through its provider-specific API path.

| Provider | Responder model | Recorded endpoint hostname | Source answers |
| --- | --- | --- | ---: |
| AI-ML-API | `mistralai/mistral-nemo` | `api.aimlapi.com` | 35 |
| Alibaba | `qwen3.7-plus` | `ws-dft0j6dsrm76r6ud.eu-central-1.maas.aliyuncs.com` | 35 |
| Amazon | `openai.gpt-oss-20b` | `bedrock-mantle.eu-central-1.api.aws` | 35 |
| Anthropic | `claude-haiku-4-5-20251001` | `api.anthropic.com` | 35 |
| AtlasCloud | `bytedance/doubao-seed-2.0-pro-260215` | `api.atlascloud.ai` | 35 |
| Baseten | `deepseek-ai/DeepSeek-V4-Flash-0731` | `inference.baseten.co` | 35 |
| Cerebras | `gpt-oss-120b` | `api.cerebras.ai` | 35 |
| Chutes | `google/gemma-4-31B-turbo-TEE` | `llm.chutes.ai` | 35 |
| Cohere | `command-r7b-12-2024` | `api.cohere.ai` | 35 |
| DeepInfra | `stepfun-ai/Step-3.7-Flash` | `api.deepinfra.com` | 35 |
| DeepSeek | `deepseek-chat` | `api.deepseek.com` | 35 |
| Fireworks | `accounts/fireworks/models/glm-5p3-flash` | `api.fireworks.ai` | 35 |
| Glama | `google/gemini-2.5-flash-lite` | `glama.ai` | 35 |
| GMI-Cloud | `XiaomiMiMo/MiMo-V2.5-Pro` | `api.gmi-serving.com` | 35 |
| Google | `gemini-2.5-flash` | `generativelanguage.googleapis.com` | 35 |
| Groq | `openai/gpt-oss-20b` | `api.groq.com` | 35 |
| HuggingFace | `deepseek-ai/DeepSeek-V3.1` | `router.huggingface.co` | 35 |
| MergeGateway | `ai21/jamba-1-5-large` | `api-gateway.merge.dev` | 35 |
| MiniMax | `MiniMax-M2.1` | `api.minimax.io` | 35 |
| Mistral | `mistral-small-latest` | `api.mistral.ai` | 35 |
| Moonshot | `kimi-k2.6` | `api.moonshot.ai` | 35 |
| NebiusAIStudio | `google/gemma-3-27b-it` | `api.studio.nebius.com` | 35 |
| Novita.ai | `meta-llama/llama-3.1-8b-instruct` | `api.novita.ai` | 35 |
| NVIDIA | `nvidia/nemotron-3-super-120b-a12b` | `integrate.api.nvidia.com` | 35 |
| OpenAI | `gpt-4o-mini` | `api.openai.com` | 35 |
| OpenRouter | `ibm-granite/granite-4.0-h-micro` | `openrouter.ai` | 35 |
| Parasail | `meta-llama/Llama-3.2-3B-Instruct` | `api.parasail.io` | 35 |
| Perplexity | `sonar` | `api.perplexity.ai` | 35 |
| Replicate | `meta/meta-llama-3-8b-instruct` | `api.replicate.com` | 35 |
| Requesty | `openai-responses/gpt-4.1-nano` | `router.requesty.ai` | 35 |
| SambaNova | `gemma-4-31B-it` | `api.sambanova.ai` | 35 |
| SiliconFlow | `ByteDance-Seed/Seed-OSS-36B-Instruct` | `api.siliconflow.com` | 35 |
| Together | `openai/gpt-oss-20b` | `api.together.xyz` | 35 |
| VercelAIGateway | `mistral/ministral-3b` | `ai-gateway.vercel.sh` | 35 |
| xAI | `grok-3-mini` | `api.x.ai` | 35 |
| Z.AI | `glm-4.5-air` | `api.z.ai` | 35 |

## Findings

### FAN01 · P2 — Saved fan-out results lose direct trace links

All **1,251** new cost-ledger entries carry valid trace references, but **0 of 1,260** fan-out rows has a `traceFile`. The common `SecondaryRunManager.executeSecondaryTask` saves response content, usage, costs and HTTP status without copying the response usage’s trace attribution into `SecondaryResult.traceFile`. The fan-out pair screen exposes the original source model’s trace control, but not the missing direct reference to its own failing call. The Nebius/MiniMax error was inspected through the L1 → L2 → L3 UI: the error is visible, while diagnosing its HTTP response requires finding the trace elsewhere. This extends the primary-result trace issue addressed by the previous audit into a separate secondary-result path.

Required correction: propagate the response’s immutable trace attribution into the saved secondary row, and retain an attempt-scoped trace sink for failures/timeouts that return no token usage. Preserve per-pair attribution under concurrent calls. Source: `SecondaryRunManager.kt:1904–1940`.

### FAN02 · P2 — Fixed 64-item windows leave most concurrency idle

`runThrottledBatch` interleaves providers but then uses `.chunked(64).forEach { window -> coroutineScope { … await each … } }`. No pair in the next window starts until the entire previous window finishes. At the observed first-window tail the UI showed 61 Done, 1 Error, 2 Run and 1,196 Queue, despite a 50-call concurrency limit. The first API calls began around 11:17:31; the next window did not begin until approximately 11:20:17. A slow or benched pair therefore delays unrelated providers. Near the end, the UI again showed **1 Run / 236 Queue**, with 1,009 Done and 14 Error. These repeated idle tails are independent of the separate host pause. Use a bounded rolling queue/worker pool that admits the next item as soon as capacity frees, while preserving host gates, cancellation and bounded memory.

### FAN03 · P1 — A recovered 429 can requeue an already successful pair

The fixed-model scheduler’s bench signal remains true after an inner retry recovers. `analyzeWithAgent` may return a successful response after a 429, but `runThrottledBatch` tests only `sig.get()` and calls `onBenchRetry`, clearing the saved content and requeueing that same pair.

Concrete evidence: the Chutes responder / HuggingFace source pair (`dc6fe740-bb67-456a-8922-10e358793669` / `b914df65-16b4-498f-b461-30890a7bb245`) received a valid HTTP 200 “Sofish-ticated!” response in `llm.chutes.ai_20260907_111753_537_95i4_d9343c15.json`. It was queued again, and `llm.chutes.ai_20260907_111805_509_95i5_47230ec4.json` returned another valid answer to the same source question. Both were billed and recorded. The Chutes/SambaNova pair also requeued after a valid HTTP 200. A successful terminal result must take precedence over an earlier transient bench signal.

The same bug also occurred on Parasail / OpenRouter source (`2fc8800e-ec88-46b1-a108-a15f52097e45` / `9383ecf3-2697-4d33-b121-6d7ca80b2cad`). A 429 in `api.parasail.io_20260907_114709_290_963b_e71bbb54.json` was followed by a valid 200 in `api.parasail.io_20260907_114710_508_963r_58159554.json`. The pair was then queued again and generated another paid 200 in `api.parasail.io_20260907_114719_978_964c_92fde4d8.json`. This confirms the defect across two providers, independent of whether the model’s joke answer is good.

Exactly **three redundant successful generations** were identified: two Chutes and one Parasail. The extra calls added **$0.000184508** in this inexpensive run. The accounting retains these charges correctly; the defect is issuing the extra requests and replacing the first successful answer. The final ledger therefore contains 1,245 successful pairs + 3 usage-bearing failed pairs + 3 redundant successful calls = **1,251 costed calls**. Source: `ThrottledBatch.kt:189–192`, `RateLimitRetry.kt:120–133`, and the `analyzeWithAgent`/`withRetry` path.

### FAN04 · P2 — Autostart Fan Meta setting has no runtime consumer

The preference is exposed by Settings, persisted and imported/exported, and documented as automatically launching Fan Meta after a successful fan-out. A repository-wide search found no runtime read of `autostartFanMeta`. `FanOutEngine.startRun` completes/finalizes without invoking Fan Meta; the fan-out screen's `onLaunchFanMeta` callback is wired to the manual action. This is a source-confirmed wiring gap, not a failure observed in this run. The current emulator's master autostart switch is off, so it would not be expected to autostart Fan Meta here even with that wiring repaired.

References: `ai/src/main/java/com/ai/viewmodel/AppViewModelTypes.kt` (`autostartFanMeta`), `ai/src/main/java/com/ai/viewmodel/FanOutEngine.kt` (`startRun`), `ai/src/main/java/com/ai/ui/report/manage/view/Secondary.kt` (`onLaunchFanMeta`), and `ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt` (Autostart Fan Meta).

### FAN05 · P2 — Replicate traces truncate the model ID to its owner

All **50** captured Replicate attempts to `/v1/models/meta/meta-llama-3-8b-instruct/predictions` are stored with `trace.model="meta"`, while their cost rows correctly identify `meta/meta-llama-3-8b-instruct`. The URL fallback in `TracingInterceptor` takes only the first segment after `/models/`, which handles Gemini's path but truncates Replicate's owner/model form. This breaks model-scoped trace filtering and evidence matching. The direct ledger filename still points to the correct request; this is incorrect trace metadata, not a demonstrated charge attributed to the wrong model.

Example: `api.replicate.com_20260907_111733_190_95gs_f82ecdf9.json`, HTTP 201, report ID and full request URL correct, model field `meta`. The 35 successful Replicate ledger rows each show the full model; the remaining 15 attempts were recovered 429s. Source: `TracingInterceptor.kt:62–74`.


### FAN06 · P2 — Fan-out HTTP statistics are never populated

The Statistics control was absent throughout the run and remained absent after completion and reopening, despite **1,301 captured HTTP responses** and the original process staying alive. This is not the documented loss of in-memory statistics after an app restart.

`FanOutEngine.startRun` sets report/category/run tags, but never a per-responder model tag. Neither `runOnePair`, `executeSecondaryTask`, nor `AnalysisRepository.analyzeWithAgent` supplies that missing tag. `HttpStatusStatsInterceptor` reads only `ApiTracer.currentModel`, while `RunHttpStats.record` silently ignores a missing model. The trace writer separately recovers the model from the request body/URL, which explains why trace files can have models while this recorder has none. `FanL1` hides Statistics when `RunHttpStats.hasRun` is false.

Required correction: attach the actual model to each immutable call context before dispatch, or use a shared provider-aware model resolver in the recorder. Keep the responder's full model identity, preserve retry status counts, and verify the completed run exposes its HTTP statistics. Sources: `FanOutEngine.kt:1113`, `SecondaryRunManager.kt:1866`, `HttpStatusStats.kt:196–210`, `RunHttpStats.kt:49–50`, `FanL1.kt:138`.

## Timing caveat

The host-side collector had a 316-second interval between samples at 11:26:55 and 11:32:11, instead of its normal approximately 25 seconds. A Baseten pair spanning that pause recorded 475.459 seconds of wall time while reporting a 180-second timeout. The collector gap means this is not sufficient evidence of a timeout implementation bug. A temporary `caffeinate -i` assertion was attached to the collector afterward, without changing permanent power settings. It ends when monitoring finishes.

## Provider and generation failures

These 15 final errors are separate from the application findings above. Successful retry responses are counted in their final pair outcome; intermediate failures are not added again to this table.

| Responder | Success | Error | Final failure |
| --- | ---: | ---: | --- |
| NVIDIA | 28 | 7 | HTTP 503: service temporarily overloaded |
| NebiusAIStudio | 32 | 3 | Two 180-second pair timeouts; one empty final answer with `finish_reason=stop` |
| Baseten | 33 | 2 | One 180-second pair timeout; one HTTP 500 Internal Server Error |
| SiliconFlow | 34 | 1 | 180-second pair timeout |
| Novita.ai | 34 | 1 | Output budget exhausted, `finish_reason=length` |
| GMI-Cloud | 34 | 1 | Provider rejected the response, `finish_reason=content_filter` |
| Other 30 providers | 1,050 | 0 | All 35 pairs per provider succeeded |
| **Total** | **1,245** | **15** | |

Representative failure checks:

- **Nebius empty response:** `api.studio.nebius.com_20260907_111736_742_95gv_c642f16e.json` returned HTTP 200 with empty content, `stop`, and 738 input / 1 output token. The app correctly saved ERROR and $0.0000592 usage cost. The L3 error detail was inspected.
- **Novita truncation:** `api.novita.ai_20260907_114101_944_95z1_e400a743.json` returned HTTP 200 / `length`, using 45 input and all 12,288 requested output tokens. The 42,441-character partial answer is retained with ERROR and $0.00036999 cost. The corrected 12,288-token request budget was used; this is not the earlier context-limit 400 bug.
- **GMI-Cloud filtering:** `api.gmi-serving.com_20260907_115247_738_96dg_4a8d60f7.json` rejected the ordinary source question “Why did the scarecrow win an award?” as high risk. Its HTTP 200 carried `content_filter`, 260 prompt tokens (192 cached) and 167 completion tokens. The app correctly recorded ERROR and $0.0002106696 cost. No unchanged automatic retry followed this semantic failure.
- **Timeouts:** four pairs settled as errors and released the run. The unusually long Baseten wall time is qualified by the host-pause evidence above. No unsupported timeout-implementation conclusion is drawn from it.

Chutes, Parasail and Replicate recovered rate-limit responses. Alibaba recovered a connection failure; NVIDIA, Baseten and other affected providers also had recoverable intermediate failures. Bad jokes, explanations instead of punchlines, and long reasoning outputs are model/prompt-quality observations: the selected template forwards the source answer unchanged and adds no brevity instruction. They are not counted as app defects.

## Completed verification

- **Selection and payload:** all 1,260 expected unique `(provider, model, source agent)` combinations exist. No missing, unexpected, duplicate or self-pairs. All **1,260 saved execution prompts exactly equal the selected source answer body**. One report and one fan-out run ID throughout; all captured calls belong to `fan_out/response` and the 36 approved origins.
- **Terminal state:** 1,245 DONE / 15 ERROR, with zero blank successful answers and zero unfinished rows. The UI shows Run, Bench, Wait and Queue all zero. The Broken work screen reports exactly 15 fan-out errors.
- **Preservation:** all 36 original primary agent records and all 116 original cost rows remain unchanged. Outside the added ledger/total, the report timestamp changed as expected when the fan-out started; no metadata enrichment was launched.
- **Accounting:** per-pair accumulated costs equal the added ledger total exactly. The report total equals baseline plus fan-out within floating-point precision. All 1,251 new usage records are marked non-estimated; they total **494,600 input-plus-output tokens**. Rates came from LiteLLM (1,040 rows), models.dev (106), API-reported costs (70) and Together (35).
- **UI totals and persistence:** after completion, navigation to second results and Costs and back to the existing fan-out preserved 1,245/15 counts. Second results shows **1,367 costed calls / 81.86¢**; Costs shows **meta 1,251 calls / 69.78¢**. No rerun was triggered by opening existing results.
- **Trace evidence:** **1,315 finalized trace files** were captured, all in the correct report/run/category. All **1,251 cost-ledger trace links still resolve on the emulator**, and also in the local evidence archive. No crossed report/run cost attribution was found. The only ledger-versus-trace model mismatch is the Replicate owner truncation in FAN05. Missing direct secondary links are FAN01; unavailable HTTP statistics are FAN06.
- **Stability:** the app remained foreground and responsive in PID 7148. The scoped logcat capture contains no `FATAL EXCEPTION`, `ANR in`, or storage-write failure markers. This is an observed-run check, not an exhaustive crash-history assertion.
- **Cleanup:** the task-owned logcat collector was stopped; the periodic result collector and its temporary keep-awake assertion exited. App data and completed results remain on the emulator.

Captured network outcomes differ from final pair outcomes because they include retries and connection failures:

| Trace outcome | Count |
| --- | ---: |
| HTTP 200 | 1,216 |
| HTTP 201 | 35 |
| HTTP 429 | 28 |
| HTTP 503 | 20 |
| HTTP 500 | 2 |
| No HTTP response (connection/timeout/cancellation; status 0) | 14 |
| **Total captured attempts** | **1,315** |

## Evidence and follow-up

The compact final machine-readable verification, provider totals and failed pair IDs are saved in [fan-out-monitor-2026-09-07-evidence.json](fan-out-monitor-2026-09-07-evidence.json). Detailed temporary evidence remains in `/tmp/funny-fanout-2026-09-07-evidence/` (interval samples, persisted pair/report copies, source bodies and captured traces) and `/tmp/funny-fanout-2026-09-07-run-logcat.log`. Temporary files are not a durable archive; the audit and compact evidence file are committed to the repository. Raw request headers/API credentials were not copied into the audit.

Repair priority: **FAN03 first**, then **FAN02**, then trace/statistics attribution (**FAN01, FAN05, FAN06**), then **FAN04** with the master/per-feature autostart settings respected. Verify fixes with bounded cases that reproduce the specific defects before considering another full fan-out. This monitoring task originally recorded the six issues without source changes. All six application findings have since been addressed; see [the remediation and verification record](fan-out-fixes-2026-09-07.md). The original provider failures and run evidence above remain historical observations.
