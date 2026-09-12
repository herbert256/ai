# Test all Models — 12 September 2026

Ran **Housekeeping → Test → Test all models → all 33 selected providers** on `emulator-5554`. One fresh sweep; no rerun of errors. Start **12:13:35**, completion **12:28:26.270 Europe/Amsterdam**. Run ID: `f9f261d8-f34d-4b1e-a159-a65ba6e32b66`.

The installed APK matched the repository's built debug APK by SHA-256 (`fe4ec11613ae799245c950900114a1a8fc15dec4807981970e8c77e4bc73ef11`), version `26.249.607`; repository HEAD at launch was `80a162f31`. No source or provider configuration was edited to conduct the run. Diagnostic state changes below were produced by the existing test engine.

## Settled result

| Measure | Result |
|---|---:|
| Catalog entries | 4,106 |
| Skipped before run | 60 inaccessible + 2 excluded + 444 non-testable |
| Models tested | 3,600 across 33 providers |
| App PASS / Done | 3,325 |
| Of those, actually labelled inaccessible | 466 |
| Reachable under the probe's rules | 2,859 |
| FAIL | 275 |
| Pending / running | 0 / 0 |
| Reasoning-only reachable results | 325 |
| App-estimated probe cost | $1.288104620 |
| Archived API traces for this run | 3,594 |
| Wrong-provider result trace links | 12 |
| Mislabelled 60-second timeouts | 49 |
| Result trace links whose files were no longer on device at completion | 1,389 of 3,079 |

Reachable includes successful chat, embeddings and rerank probes and the explicitly accepted reasoning-only cases. It is not a quality score or proof of a complete usable answer. The cost is the app's pricing estimate, not reconciled provider billing.

All catalog and outcome counts reconcile: 60 + 2 + 444 + 3,600 = 4,106; 2,859 reachable + 466 newly inaccessible + 275 failed = 3,600. Each persisted monitoring snapshot parsed successfully; item count and catalog count stayed fixed and completed-item counts never regressed.

No app crash, ANR, out-of-memory event, skipped-frame warning, or test-state save failure appeared in the captured run logs. The app retained PID `2763` throughout the sweep and responded to provider/detail navigation. Baseline PSS was 128.7 MiB; sampled running PSS ranged from 129.5 to 195.7 MiB. Graphics aggregates include earlier navigation and are not presented as test-only measurements.

## Persisted diagnostic state

The initial model-state snapshot was taken during the sweep, before the engine's end-of-run settings flush. It still contained the original 64 inaccessible entries and 3 exclusions across all providers (the run's selected-provider subset was 60 and 2).

| State | Before flush | After completion |
|---|---:|---:|
| Blocked models | 0 | 275 |
| Inaccessible models | 64 | 530 |
| Test exclusions | 3 | 5 |

False failures returning exactly `OK`: `Requesty:openai/o4-mini`. Their presence in Blocked was checked against the persisted settings. The two new expensive-probe exclusions are `Groq:groq/compound` and `AI-ML-API:sakana/fugu-ultra-v2`.

The completed run was reloaded in a fresh app process (PID `9547`). The full result document and all three diagnostic state lists survived unchanged; the overview again showed 3,325 Done, 275 Errors and zero queued/running/throttled work.

The 3,594 archived traces reconcile one-to-one by provider and model. The remaining six failures occurred locally because rerank dispatch was not wired; no HTTP call was issued for them. No duplicate probe requests were found.

## Confirmed app issues

### 1. A reachable model is classified as failed and blocked

`Requesty / openai/o4-mini` returned HTTP 200 with visible content exactly `OK`. Requesty also returned `finish_reason=length` and zero usage counters. The generic report validator marks this incomplete; the health probe only overrides that failure when token counters are positive. Consequently the run records FAIL despite receiving its requested answer.

Source: `data/ApiDispatchBuilders.kt:84` and `viewmodel/ModelTestEngine.kt:651`. Give reachability probes a separate success policy: an actual visible answer is evidence of reachability even when usage metadata is missing or contradictory. Keep strict completion checks for reports. Verify the downstream blocked-model state as part of the fix.

### 2. Model results open another provider's API trace

The result resolves a trace by model name and start time, without provider or exact request identity (`viewmodel/ModelTestEngine.kt:572`). Twelve result records linked to the wrong provider. Concurrent providers often use the same model name. Examples include AtlasCloud's `anthropic/claude-opus-4.7` linking to GMI-Cloud, and HuggingFace and NebiusAIStudio `openai/gpt-oss-120b` both linking to Groq's trace.

This is a trace attribution bug; the evidence does not show response text or cost being copied between providers. Use the existing per-call trace filename sink and retain that exact filename across cancellation. Timeout results also lose trace links because their network-failure trace can be written after the model result finishes.

### 3. A 60-second timeout is labelled 180 seconds

The Amazon `google.gemma-3-12b-it` detail screen visibly shows `Latency: 60002 ms` beside `API call timed out after 180s`. All 49 affected records measured 60,000–60,176 ms; multiple providers exhibit the same discrepancy. The enclosing test timeout is 60 seconds, while `withApiCallTimeout` catches any `TimeoutCancellationException` and labels it with its own computed 180-second ceiling (`data/ApiDispatch.kt:98`). Preserve parent cancellation or identify which timeout actually expired. The 60-second limit itself worked in this run; the diagnostic message is wrong.

### 4. Probe requests do not match several models' API contracts

These are direct provider response observations, rather than assumptions based on model names:

| Provider / model examples | Observed rejection | Required correction |
|---|---|---|
| OpenAI `gpt-5-search-api` and dated variant | Responses API unsupported | Apply endpoint exceptions before broad GPT-5 routing |
| Google three Gemini Omni preview models | Only supports Interactions API | Support that API or explicitly classify these models as unsupported by this probe |
| xAI `grok-4.20-multi-agent-0309` | Multi Agent requests rejected on Chat Completions | Correct API routing / explicit capability exclusion |
| GMI-Cloud four GPT-5.4 variants | `max_tokens` rejected; requires `max_completion_tokens` | Model-aware request parameter naming |
| NVIDIA `llama-nemotron-embed-vl-1b-v2` | Required `input_type` absent | Provider-specific embedding inputs |
| Five NVIDIA models including Nemoguard | HTTP 415: `application/json; charset=UTF-8` rejected | Send the media type required by those endpoints |
| Four Cohere `*-v3.0-image` embedders | Text embeddings unsupported | Use a supported input modality or skip with an explicit reason |
| Cohere `parse-v5.0`, gateway video / speech / realtime models | Incompatible API / not a language model | Preserve catalog modality and choose a matching probe |
| Gateway rerank models | Local `Rerank API not wired` failure | Distinguish unsupported app dispatch from provider model health |

Relevant source: `data/AnalysisRepository.kt:627`, `data/ApiDispatchBuilders.kt:27`, `data/ApiDispatch.kt:308`, `viewmodel/ModelTestEngine.kt:499`, and provider definitions under `assets/providers/`. Wrong requests feed into Blocked models (`viewmodel/AppViewModel.kt:1364`), so these failures affect model selection beyond the test screen. A rejected request establishes an integration mismatch; it does not establish that the model would succeed after correction.

### 5. Inaccessible is persisted as PASS

This run recorded 466 newly inaccessible models as PASS. A 404 or matching account/tier-gating message becomes `status=PASS`, loses its error and trace link, and gets the response label `Inaccessible — tier-gated, will be skipped next sweep` (`viewmodel/ModelTestEngine.kt:529`). It increments green Done and can display Passed on the detail page. The top Inaccessible count remains the pre-run snapshot, so newly discovered inaccessible models are not visible there.

Use a distinct inaccessible/skipped outcome, retain the original error and trace, and separate completed work from demonstrated reachability. Generic HTTP 404 alone also cannot establish whether a model ID, endpoint, or account access is wrong.

### 6. The complete run outlives its diagnostic traces

The app keeps at most 2,000 traces / 50 MiB (`data/ApiTracer.kt:38`), while this sweep tests 3,600 models. At completion, 1,389 of 3,079 stored result trace links pointed to files already removed from the device. Early result links and the run-filtered trace view lose evidence before the sweep completes. Live host archiving preserved this run's traces for the audit. Consider retaining a bounded per-run evidence bundle, or clearly showing when diagnostic evidence has expired.

### 7. Provider outcomes disappear from the finished overview

The screen promises “Per-provider pass rate,” but while running its green fill represents completed work (including failures). Once all work finishes, it removes every provider status icon and progress fill and leaves only provider name and cost (`ui/other/ModelTestL1.kt:216`). Show final reachable / inaccessible / failed counts per provider so the completed test can be assessed without opening all 33 providers.

### 8. Large frame stalls during restart verification

The fresh-process persistence check logged `Skipped 534 frames` at 12:29:49.602 and `Skipped 34 frames` at 12:30:04.276, both on app PID `9547`. A matching HWUI entry recorded a 9,038 ms frame. The app recovered and rendered the saved results correctly; no crash or ANR was recorded. These stalls occurred during the post-run restart, separate from the stable sweep. Their main-thread cause has not been profiled, so the evidence does not establish whether test-run hydration, other startup work, or emulator scheduling caused them.

## Other observations and limits

- The app requested `max_tokens=64`, but Groq `groq/compound` returned 967 completion tokens plus 875 input tokens. The app estimated $0.0944 for that one probe. This is an observed provider/budget limitation, not proof that the app sent the wrong cap; the expensive-probe exclusion acts after spending.
- Reasoning-only PASS responses are intentional reachability results. They do not establish that the model produced a usable answer under the probe's 64-token budget.
- Rate limits, service overload, account restrictions, deprecations and upstream failures also occurred. Their presence in this sweep is not by itself an app bug. No retry run was launched, so persistence of transient provider failures was not established.
- These findings concern Test all Models. They do not test report generation, secondary analyses, chat, or Stress test.

## Provider outcomes

“Reachable” excludes the engine's inaccessible PASS records.

| Provider | Tested | Reachable | Newly inaccessible | Failed | Estimated USD |
|---|---:|---:|---:|---:|---:|
| AI-ML-API | 573 | 342 | 222 | 9 | 0.240016 |
| Alibaba | 80 | 77 | 0 | 3 | 0.101349 |
| Amazon | 33 | 12 | 0 | 21 | 0.000249 |
| Anthropic | 11 | 11 | 0 | 0 | 0.002621 |
| AtlasCloud | 116 | 89 | 0 | 27 | 0.025799 |
| Cerebras | 3 | 2 | 1 | 0 | 0.000163 |
| Chutes | 14 | 14 | 0 | 0 | 0.002205 |
| Cohere | 32 | 27 | 0 | 5 | 0.002694 |
| DeepInfra | 149 | 113 | 32 | 4 | 0.010328 |
| DeepSeek | 4 | 4 | 0 | 0 | 0.005858 |
| Fireworks | 26 | 22 | 3 | 1 | 0.009473 |
| Glama | 301 | 276 | 4 | 21 | 0.156458 |
| GMI-Cloud | 84 | 62 | 1 | 21 | 0.029279 |
| Google | 25 | 22 | 0 | 3 | 0.004913 |
| Groq | 10 | 10 | 0 | 0 | 0.108967 |
| HuggingFace | 4 | 4 | 0 | 0 | 0.000100 |
| MergeGateway | 266 | 236 | 18 | 12 | 0.059757 |
| MiniMax | 4 | 4 | 0 | 0 | 0.000642 |
| Mistral | 22 | 22 | 0 | 0 | 0.000407 |
| Moonshot | 4 | 4 | 0 | 0 | 0.001350 |
| NebiusAIStudio | 24 | 24 | 0 | 0 | 0.002955 |
| Novita.ai | 117 | 100 | 0 | 17 | 0.020522 |
| NVIDIA | 82 | 12 | 53 | 17 | 0.006899 |
| OpenAI | 75 | 59 | 14 | 2 | 0.072309 |
| OpenRouter | 429 | 329 | 81 | 19 | 0.146859 |
| Parasail | 90 | 86 | 0 | 4 | 0.059670 |
| Replicate | 2 | 1 | 0 | 1 | 0.000028 |
| Requesty | 662 | 616 | 23 | 23 | 0.121279 |
| SambaNova | 5 | 1 | 4 | 0 | 0.000025 |
| Together | 12 | 2 | 8 | 2 | 0.000003 |
| VercelAIGateway | 324 | 260 | 2 | 62 | 0.089762 |
| xAI | 7 | 6 | 0 | 1 | 0.003318 |
| Z.AI | 10 | 10 | 0 | 0 | 0.001846 |

## Evidence

Raw local evidence is under `/private/tmp/ai-model-test-20260912/`: continuous `logcat.txt`, timestamped persisted snapshots, complete run trace archive, screenshots/UI hierarchies, memory samples, `analysis.json`, and `state-audit.json`. Raw traces and preferences are kept outside the repository; only this findings report is committed. Source paths in this report are relative to `ai/src/main/java/com/ai/` unless stated otherwise.
