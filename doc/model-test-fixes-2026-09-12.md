# Test all models: fixes and verification — 12 September 2026

The app defects identified in [the original monitored run](model-test-findings-2026-09-12.md) have been repaired. The original 3,600-model run remains available, with corrected diagnostic outcomes and new attempts replacing only the models retried. This is a reachability check, not an evaluation of answer quality.

## Changes

| Finding | Implemented behavior |
|---|---|
| Valid `OK` response marked failed because `finish_reason=length` and usage was zero | A separate health-probe policy accepts visible output or emitted output/reasoning tokens on HTTP success. Report completion validation remains strict. The false Requesty `openai/o4-mini` block is removed. |
| Concurrent results opened another provider's trace | Each request reserves and passes back its exact trace filename. The identity survives cancellation. Provider-screen probes use the same mechanism. |
| A 60-second probe timeout said 180 seconds | The inner API timeout preserves parent cancellation; the probe reports its actual 60-second ceiling. |
| Invalid API requests | OpenAI search models use Chat Completions; xAI multi-agent models use Responses; OpenAI/GMI reasoning-model Chat requests use `max_completion_tokens`; NVIDIA receives strict JSON media type and embedding `input_type` (`passage` for documents, `query` for retrieval). The NVIDIA topic-control probe includes its system instruction. |
| Unsupported APIs and modalities polluted model health | Native catalog modalities are preserved. Interactions, Batch, document/image-only, code-editing and unwired rerank/moderation probes receive explicit unsupported outcomes. Explicit access, retirement and deployment errors are distinguished from ambiguous failures; a bare 404 is insufficient to infer inaccessibility. |
| Inaccessible models counted as PASS | Separate `INACCESSIBLE` and `UNSUPPORTED` terminal states preserve evidence and count toward completion, never reachability. Migration repairs old results and matching automatic blocks while preserving manually changed reasons. |
| Run traces expired before completion | The latest run has a separate bounded allowance of 10,000 files / 128 MiB. Other traces retain the existing 2,000-file / 50-MiB allowance. Explicit cleanup remains effective; missing evidence is labelled. |
| Finished provider overview hid outcomes | Reachable, inaccessible, unsupported and failed counts remain visible for each provider and in the dashboard. Progress uses completed work. Retrying preserves the original denominator; individual results can be rechecked. |
| Startup blocked the main thread while loading preferences | Preferences load on IO before heavy cache preloading; navigation waits for settings behind a static loading view. Profiling identified a 2.167-second main-thread preference wait in the original implementation. |
| Responses API silently dropped requested output caps | Non-streaming and streaming report/chat requests now forward `max_output_tokens`. Incomplete responses retain failure status and billable usage instead of becoming successful partial reports. Health checks still accept reachability evidence. |
| Responses cost omitted cached inputs and provider identity | The parser reads `input_tokens_details.cached_tokens`, and native Responses analysis passes the provider to usage normalization, retaining its reported cost. |

All behavior and migration rules are in repository source. Personal results, raw traces, credentials and device preferences are not bundled as application defaults.

## Live verification

After the initial repair, **377 selected models were retried to completion**. Four additional targeted HTTP calls checked native Responses limits, xAI cost handling and NVIDIA's guard fixture: **381 verification calls** in total. This was a retry of selected failures and targeted models, not a second full sweep.

The settled saved run contains:

| Outcome | Models |
|---|---:|
| Reachable | 2,903 |
| Inaccessible | 391 |
| Unsupported probe | 150 |
| Failed | 156 |
| Queued / running | 0 / 0 |
| Total | 3,600 |

The catalog partition remains **4,106 = 506 initial skips + 3,600 selected models**. There are **3,975 run traces** on the device, including all 3,594 original HTTP calls recovered from the monitoring archive. All **3,594 linked model results** point to an existing trace with the correct run, provider host and model: **zero wrong links and zero missing linked files**. The other six original items had no wired native rerank endpoint and issued no HTTP request.

The persisted diagnostic lists contain 156 blocked models, 455 inaccessible models (including the original 64 entries) and five test exclusions. No reachable, inaccessible or unsupported result contradicts a remaining block. Requesty's false block is absent.

After a final force-stop and fresh-process launch, the complete run document and all three diagnostic lists matched their pre-restart values exactly. The app returned to the saved run and was confirmed in the foreground.

Specific successful checks include both OpenAI GPT-5 search variants, GMI GPT-5.4/mini/nano, NVIDIA's embedding model (2,048-dimensional vector), supported NVIDIA guard/translation calls, and xAI multi-agent Responses. The final OpenAI `gpt-5.4-mini` probe sent the 64-token Responses cap and returned `OK`. The final xAI call sent the same cap, returned `OK`, and its saved **$0.00298325** cost exactly matched `cost_in_usd_ticks / 10,000,000,000`.

The 37 timeout results after the retry measured 60,002–60,436 ms and correctly report 60 seconds, with exact trace filenames retained. Captured run logs did not show an app crash, ANR, out-of-memory event or test-state save failure during the retry.

## Remaining limits

- The remaining 156 failures are preserved for investigation: 37 timeouts, 20 HTTP 429 responses, 35 HTTP 5xx responses, 49 ambiguous HTTP 400/404 responses, 11 other HTTP errors, and four empty final responses. Ambiguous gateway rejections are not claimed to be proven upstream bugs.
- NVIDIA's topic-control request now reaches inference, but the service still returns HTTP 500 with a TensorRT/CUDA illegal-memory-access error. An Android client cannot repair that server failure.
- xAI multi-agent usage exceeded the requested cap even with the correct field: the last response reported 939 output tokens, including 925 reasoning tokens. Groq Compound had also exceeded its cap and cost five cents or more, so it is excluded from this bounded probe before dispatch. Requested token limits cannot guarantee provider billing.
- The overview sums each model's **latest attempt** estimate, currently approximately **$1.307566**. It is not cumulative spending across retries or a reconciled invoice; the UI and Help now say so. Earlier attempts remain in the retained traces.
- The preference wait has moved off the main thread, but startup and navigation on this constrained emulator still produce some skipped frames. The final post-install startup included a 16-second background bootstrap and a 1.44-second initial frame. The final fresh-process restart reported a 2.664-second activity launch, a 14.3-second background bootstrap and frame stalls up to 892 ms in the captured startup window. This change does not establish that all startup or rendering jank has been eliminated.
- Streaming failure handling and query/document embedding propagation were built and source-reviewed. The live verification here exercises the non-streaming health path; it is not a full report/chat/RAG regression suite.

## Delivery and evidence

The repository's default cycle was used: `:ai:assembleDebug`, install, cloud APK copy, foreground launch and manual verification. No unit/instrumented tests, uninstall or full app-data restore was performed. Only the original diagnostic trace files were recovered from the host archive. The debug and cloud APK SHA-256 values match:

`90f2e63f453cb52cee2a0b2c08e5f522c6bd8358b11c15cfaca306e522397fcb`

Local evidence is under `/private/tmp/ai-model-test-fixes-20260912/`, including `final-verification.json`, `final-xai-check.json`, the retained trace archive, persisted state snapshots, screenshots, startup profiles and process logs. The original audit evidence remains under `/private/tmp/ai-model-test-20260912/`.

Request contracts were checked against the [OpenAI Responses reference](https://developers.openai.com/api/reference/cli/resources/responses/methods/create), [xAI multi-agent documentation](https://docs.x.ai/developers/model-capabilities/text/multi-agent), [xAI Responses reference](https://docs.x.ai/developers/rest-api-reference/inference/responses), [NVIDIA embedding reference](https://docs.nvidia.com/nim/nemo-retriever/embedding/2.0/reference.html), and [NVIDIA topic-control prompt template](https://docs.nvidia.com/nim/llama-3-1-nemoguard-8b-topiccontrol/latest/prompt-template.html). Batch-only and other explicit unsupported outcomes are additionally grounded in the captured provider responses.
