# Funny question report: monitored execution, 7 September 2026

The requested report was created from the **Funny question** example using the **default agents** flock. All 36 expected agent/provider/model combinations ran, with no missing or duplicate agents. Generation and metadata work finished in approximately three minutes. The app saved 35 successes and one error, but only **34 agents produced a final answer**: Together's reasoning-only, truncated response was incorrectly treated as a success.

This record documents the original run and source inspection. No application code, defaults, or report results were changed during this monitoring task.

## Run identity and method

| Field | Value |
| --- | --- |
| Report ID | `4757c2cc-36e7-4d2e-9a9c-531bca7162dd` |
| Saved short title | Funny question request |
| Saved long title | Request for a Funny Question |
| Example | `ai/src/main/assets/prompts/examples/06-funny-question.json` |
| Flock | default agents — 36 agents, 36 providers |
| Run window | Approximately 07:25:23–07:28:22, Europe/Amsterdam |
| Repository revision | `d0bd413a0` — Repair provider defaults and stabilize refresh |
| Device / process | `emulator-5554`, `com.ai`, PID 25432 throughout the observation |
| Final location | Completed report reopened on Report - manage |

Exact example prompt:

> Please ask me a funny question, just the question, no more text. Something like "What kind of band never plays music?", of course not this question exactly.

The normal report settings were retained, including default agent parameters and system prompts. The reasoning control showed “none” with no explicit reasoning override in the Together request. Report titles, icon, language, language icon, and per-answer titles/icons were enabled. No secondary results were started.

Monitoring covered the launch review, live report UI, logcat, persisted report and externalized response bodies, all 121 report-scoped HTTP traces, metadata completion, Costs and Get-info screens, and reopening from the Reports hub. Externalized response bodies were verified against their SHA-256 filenames. The saved agent IDs, providers, and model IDs matched the pre-run flock exactly. Reopening preserved statuses and the cost ledger.

## Confirmed application issues

### F01 · P1 — Novita's default report request exceeds the host's context limit

**Observed:** `meta-llama/llama-3.1-8b-instruct` failed both attempts with HTTP 400. The streaming request set `max_tokens=16384`. Novita reported a 16,384-token context window and 68 input tokens: the request therefore required 16,452 tokens. The non-streaming fallback repeated the same invalid completion budget. Its generic “invalid request error” replaced the first, actionable context-limit diagnosis in the saved error.

The cached native Novita catalog explicitly supplies `context_size: 16384` and `max_output_tokens: 16384`. The app does not ingest those fields into its generic model capabilities. `ApiDispatchModels.kt:158` reads only `max_context_length`, `context_length`, or `context_window`, and line 179 sets `maxOutputTokens` to null. `ApiModels.kt:514` lacks the Novita limit fields. `defaultMaxTokens()` in `ApiDispatch.kt:46` then relies on other catalog limits; it also only applies native context bounds inside its native-output-limit branch.

**Impact:** The newly selected cheap model passes a small smoke test but fails a normal report with default parameters. Retrying without correcting the budget adds another failed call and makes the displayed error less useful.

**Recommended correction:** Parse the native context/output fields, preserve the host's context bound through every default-resolution path, reserve input space, and avoid repeating an unchanged invalid request. Preserve the original useful error when a fallback also fails.

Evidence in the device's `files/trace/` directory:

- `api.novita.ai_20260907_072526_984_4v7k_1953a2c7.json`
- `api.novita.ai_20260907_072528_966_4v82_8b8c2fda.json`

### F02 · P1 — Together's truncated reasoning is saved and displayed as a successful answer

**Observed:** `deepseek-ai/DeepSeek-V4-Flash-0731` ran for 171.295 seconds. The stream contained **zero final-content characters**, 62,106 reasoning characters, and `finish_reason: length`. Its usage reported 16,384 completion tokens, all of them reasoning tokens. The app saved the reasoning as the response body with `SUCCESS`, then generated the title **Joke Brainstorming Process** and an icon. Opening the result showed the brainstorming text, with no final question.

**Cause:** `streamOpenAiReport()` in `ApiDispatchStreaming.kt:80` substitutes `reasoningFallback()` when analysis is empty and clears the error. `OpenAiContentExtractor.reasoningFallback()` in `ApiStreaming.kt:312` does not require a normal completion or final answer. `AnalysisRepository.kt:71` accepts non-null analysis with no error as success. This path turns an exhausted reasoning budget into an apparently complete answer.

**Impact:** The report overstates successful answers, presents internal reasoning as the requested result, and spends additional calls generating metadata for a failed answer. This is more serious than a slow response: the provider supplied no final content.

**Recommended correction:** Preserve finish reasons, detect budget exhaustion, and require final answer content for this response type. Keep usage/cost accounting even when generation fails. Validate reasoning settings and the chosen default using a real report workflow, including metadata, rather than only a short connectivity probe.

Evidence: `api.together.xyz_20260907_072528_035_4v7z_12c769b7.json` and the saved Together response in this report.

### F03 · P2 — The bundled metadata worker pool still uses unavailable models

Seven metadata attempts failed against two worker models:

| Worker | Observed failure | Failed attempts |
| --- | --- | ---: |
| Groq — `llama-3.3-70b-versatile` | HTTP 404: model does not exist or this account lacks access | 1 |
| Together — `Qwen/Qwen3-235B-A22B-Instruct-2507-tput` | HTTP 400: non-serverless model requires a running dedicated endpoint | 6 |

Both IDs remain in `ai/src/main/assets/workers/swarms/workers.json` (Groq around line 13, Together around line 45). Updating provider defaults did not update these separate worker selections. Failures affected report-title, report-icon, and per-answer title/icon work. Worker fallback recovered all requested metadata in this run.

**Impact:** Normal report creation repeatedly tries inaccessible workers, adding errors and latency even though the report eventually receives its metadata. These failures establish unavailability for the imported accounts used in this run; they do not establish global retirement for every account.

**Recommended correction:** Update and validate the bundled worker pool alongside provider defaults, and repair the existing installed worker selections so this emulator also benefits. Do not assume a provider's primary default change updates its worker models.

### F04 · P2 — The report cost ledger omits both report-title calls

Manage and the Costs screen disagree because the ledger is missing the short-title and long-title calls. Manage's total includes their separately stored costs; the ledger and persisted `totalCost` do not.

| Component | USD | Cents |
| --- | ---: | ---: |
| Ledger: 108 records | 0.1081860681 | 10.81860681 |
| Missing short report title | 0.0080235 | 0.80235 |
| Missing long report title | 0.0041895 | 0.41895 |
| Missing total | **0.012213** | **1.2213** |
| Ledger plus both titles | **0.1203990681** | **12.03990681** |

Manage displays **12.04¢**; Costs displays **10.82¢**. Both report titles were produced by Google `gemini-3.5-flash`, and Get-info contains their costs. The discrepancy reconciles exactly to those two calls. The ledger is nevertheless marked `apiCallCostsComplete=true`.

**Cause:** `IconGenerationManager.runTitlePrompt()` records usage at lines 585–588 after leaving the `withTracerTags(reportId=...)` block at lines 557–565. `SettingsPreferences.recordReportApiCallCost()` at line 740 returns immediately when the current report ID is absent. The current-ledger path in `ContentDisplay.kt:625` then excludes the legacy title-cost fields. Manage's structured total in `GenerationPhase.kt:613` includes them.

**Impact:** The same report presents contradictory totals, understates its ledger by about 10.1% of the combined app-recorded cost, and undercounts successful logical calls by two. Manage is not overcharging; the ledger is incomplete.

**Recommended correction:** Record report-title usage while the report context is active, or pass report attribution explicitly. Reconcile already affected ledgers without double-counting titles and only mark ledgers complete when all cost-bearing work is included.

### F05 · P2 — Direct trace references are missing from saved answers and costs

Tracing was enabled and **121 HTTP trace files** carried the correct report ID. Nevertheless, **0 of 36 primary agent records** and **0 of 108 cost records** had a populated `traceFile` reference. The underlying traces exist, but the result/cost records lose their direct connection to them.

**Cause:** `ReportViewModel.kt:982` installs a trace sink and later persists its value at line 1062. `ApiDispatch.auditApiCall()` at lines 759–763 installs a new nested sink without propagating its result to the caller's sink. The interceptor fills the nested sink instead. Separately, the `ReportApiCallCost` construction in `SettingsPreferences.kt:742` never supplies `traceFile`.

**Impact:** Diagnosing a failed or suspicious answer requires manually locating its trace instead of following the saved result's reference. This particularly obstructs investigation of F01 and F02.

**Recommended correction:** Reuse or propagate the trace sink across nested auditing and dispatch, and attach the correctly attributed trace to each cost record. Preserve report and individual-call attribution when fallbacks produce multiple traces.

### F06 · P3 — Manage's API-call counter becomes stale after completion

Manage continued to show **107 api calls** for several minutes after the saved ledger contained 108 records. Opening Costs and returning updated it to 108; reopening the report also showed 108.

`ReportStatsLine` in `SummaryRows.kt:144` reloads on report ID, a caller-supplied refresh key, and secondary-data version. It does not observe primary `ReportDataVersion`. `Run.kt:782` supplies the displayed total cost as the refresh key, so a ledger update that does not change that total can leave the counter stale.

**Recommended correction:** Subscribe to the report's persisted data version or a dedicated ledger version. Clarify counter semantics: it currently counts cost rows, not outbound HTTP attempts. This run has 108 ledger rows, 110 successful logical calls once the missing titles are included, and 121 HTTP traces. Replicate uses two successful HTTP operations for one logical generation; failed and fallback attempts add further traces.

## Model-output quality observations

These are prompt-following failures in otherwise successful provider responses, separate from the application defects above:

- **AI-ML-API:** “What do you call a fake noodle? An impasta” adds the punchline despite the request for just the question.
- **Perplexity:** “What kind of band never plays music?” repeats the exact example explicitly excluded by the prompt.

Together has no final answer at all and is covered by F02. MiniMax includes a `<think>` section before its final question; the app has an intentional collapsible renderer for that format, so the stored wrapper alone is not counted as a rendering defect. SiliconFlow took 114.453 seconds and used 2,661 reasoning tokens out of 2,668 completion tokens, but eventually supplied a final question with a normal stop.

## Completion, recovery, and accounting checks

- All 36 expected slots reached a terminal state: 35 saved successes and one saved error. No pending or running jobs remained.
- All 35 entries marked successful received a title and icon, including the incorrectly successful Together entry. Report titles, icon, language, and language icon completed without saved metadata errors.
- The Reports hub shows a warning beside this partially failed report. Reopening retained results and costs.
- No crash or ANR markers appeared in the monitoring capture, and the app process stayed alive and foregrounded.
- NVIDIA returned one transient HTTP 503 for an overloaded service; retry recovered a valid answer. This was not a persistent provider failure.
- The 121 report-scoped HTTP responses comprise 110 HTTP 200, one HTTP 201, eight HTTP 400, one HTTP 404, and one HTTP 503. The ten errors are Novita's two attempts, seven unavailable metadata-worker attempts, and NVIDIA's recovered attempt.
- Ledger rows comprise 35 primary answers, 35 per-answer titles, 36 icons, one language result, and one language icon. The two report-title rows are missing. The ledger contains 71,058 tokens.
- Manage's **621.814 s duration** is cumulative API work across concurrent calls, not wall-clock runtime. Wall-clock generation and metadata completion took roughly three minutes.
- Manage's metadata subtotal is **9.1666¢**, about 76% of its 12.04¢ total. Primary-answer work accounts for **2.8733¢**. This is an observed cost distribution, not an additional accounting defect.

The previous 64-token provider smoke tests established basic connectivity with the new defaults. This full report demonstrates that token-limit handling, reasoning completion, worker fallback, persisted accounting, and UI refresh also need validation before calling those defaults report-ready.

## Provider outcomes

Durations below are the saved per-agent durations, not wall-clock start offsets. “Answer returned” means final response text was present; it is not a comprehensive factual or humour-quality rating.

| Provider | Model | Saved status | Duration (s) | Observed result |
| --- | --- | --- | ---: | --- |
| AI-ML-API | `mistralai/mistral-nemo` | SUCCESS | 4.091 | Answer includes an unwanted punchline |
| Alibaba | `qwen3.7-plus` | SUCCESS | 19.051 | Answer returned |
| Amazon | `openai.gpt-oss-20b` | SUCCESS | 7.356 | Answer returned |
| Anthropic | `claude-haiku-4-5-20251001` | SUCCESS | 3.984 | Answer returned |
| AtlasCloud | `bytedance/doubao-seed-2.0-pro-260215` | SUCCESS | 21.218 | Answer returned |
| Baseten | `deepseek-ai/DeepSeek-V4-Flash-0731` | SUCCESS | 6.752 | Answer returned |
| Cerebras | `gpt-oss-120b` | SUCCESS | 3.668 | Answer returned |
| Chutes | `google/gemma-4-31B-turbo-TEE` | SUCCESS | 4.078 | Answer returned |
| Cohere | `command-r7b-12-2024` | SUCCESS | 3.601 | Answer returned |
| DeepInfra | `stepfun-ai/Step-3.7-Flash` | SUCCESS | 11.328 | Answer returned |
| DeepSeek | `deepseek-chat` | SUCCESS | 3.277 | Answer returned |
| Fireworks | `accounts/fireworks/models/glm-5p3-flash` | SUCCESS | 3.837 | Answer returned |
| GMI-Cloud | `XiaomiMiMo/MiMo-V2.5-Pro` | SUCCESS | 5.916 | Answer returned |
| Glama | `google/gemini-2.5-flash-lite` | SUCCESS | 3.530 | Answer returned |
| Google | `gemini-2.5-flash` | SUCCESS | 5.945 | Answer returned |
| Groq | `openai/gpt-oss-20b` | SUCCESS | 3.249 | Answer returned |
| HuggingFace | `deepseek-ai/DeepSeek-V3.1` | SUCCESS | 3.428 | Answer returned |
| MergeGateway | `ai21/jamba-1-5-large` | SUCCESS | 3.358 | Answer returned |
| MiniMax | `MiniMax-M2.1` | SUCCESS | 9.562 | Final question with a collapsible think section |
| Mistral | `mistral-small-latest` | SUCCESS | 2.619 | Answer returned |
| Moonshot | `kimi-k2.6` | SUCCESS | 18.400 | Answer returned |
| NVIDIA | `nvidia/nemotron-3-super-120b-a12b` | SUCCESS | 6.167 | Answer returned after recovered HTTP 503 |
| NebiusAIStudio | `google/gemma-3-27b-it` | SUCCESS | 2.567 | Answer returned |
| Novita.ai | `meta-llama/llama-3.1-8b-instruct` | ERROR | 2.845 | No answer; context limit exceeded (F01) |
| OpenAI | `gpt-4o-mini` | SUCCESS | 2.032 | Answer returned |
| OpenRouter | `ibm-granite/granite-4.0-h-micro` | SUCCESS | 1.632 | Answer returned |
| Parasail | `meta-llama/Llama-3.2-3B-Instruct` | SUCCESS | 1.710 | Answer returned |
| Perplexity | `sonar` | SUCCESS | 2.635 | Repeats the explicitly excluded example |
| Replicate | `meta/meta-llama-3-8b-instruct` | SUCCESS | 1.820 | Answer returned |
| Requesty | `openai-responses/gpt-4.1-nano` | SUCCESS | 2.279 | Answer returned |
| SambaNova | `gemma-4-31B-it` | SUCCESS | 1.976 | Answer returned |
| SiliconFlow | `ByteDance-Seed/Seed-OSS-36B-Instruct` | SUCCESS | 114.453 | Answer returned after long reasoning |
| Together | `deepseek-ai/DeepSeek-V4-Flash-0731` | SUCCESS | 171.295 | No final answer; reasoning-only false success (F02) |
| VercelAIGateway | `mistral/ministral-3b` | SUCCESS | 2.171 | Answer returned |
| Z.AI | `glm-4.5-air` | SUCCESS | 8.889 | Answer returned |
| xAI | `grok-3-mini` | SUCCESS | 5.456 | Answer returned |
