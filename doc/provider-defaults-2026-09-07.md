# Provider default repairs — 2026-09-07

The sequential refresh workaround described in this historical record was
subsequently replaced with parallel workers and fixes for catalog lookup,
state merging, and persistence. See
[Parallel refresh verification](parallel-refresh-2026-09-07.md).

The 11 providers that failed after refresh now have replacement defaults that
returned `OK` through the Android app. The emulator was verified before these
models were copied into `ai/src/main/assets/providers/` for new installs.

## Defaults

| Provider | Verified default model |
|---|---|
| Amazon | `openai.gpt-oss-20b` |
| Baseten | `deepseek-ai/DeepSeek-V4-Flash-0731` |
| Fireworks | `accounts/fireworks/models/glm-5p3-flash` |
| Glama | `google/gemini-2.5-flash-lite` |
| Groq | `openai/gpt-oss-20b` |
| Moonshot | `kimi-k2.6` |
| NebiusAIStudio | `google/gemma-3-27b-it` |
| Novita.ai | `meta-llama/llama-3.1-8b-instruct` |
| Parasail | `meta-llama/Llama-3.2-3B-Instruct` |
| Together | `deepseek-ai/DeepSeek-V4-Flash-0731` |
| VercelAIGateway | `mistral/ministral-3b` |

Candidates came from current provider catalogs, serverless availability, and
pricing information. Short generation probes eliminated retired models,
dedicated-deployment-only entries, slow routes, and responses that exhausted
the token limit without producing a final answer. This verifies basic text
generation, not every model capability or comparative answer quality.

Examples of verified provider rates, in USD per million input/output tokens:

- Baseten DeepSeek Flash: **$0.13 / $0.26**. [Baseten pricing](https://www.baseten.co/pricing/)
- Fireworks GLM Flash: **$0.15 / $0.50**. [Fireworks serverless catalog](https://fireworks.ai/models?modelTypes=Serverless&show=serverless)
- Groq GPT OSS 20B: **$0.075 / $0.30**. [Groq production models](https://console.groq.com/docs/models)
- Together DeepSeek Flash: **$0.14 / $0.28**. [Together serverless models](https://docs.together.ai/docs/serverless/models)
- Vercel Ministral 3B: **$0.10 / $0.10**, confirmed by its live model-list pricing. [Vercel model page](https://vercel.com/ai-gateway/models/ministral-3b)
- Novita Llama 3.1 8B: **$0.02 / $0.05**, from the live `/v3/openai/models` response's decimal pricing fields.
- Glama Gemini Flash Lite: **$0.10 / $0.40**, from the live model-list response's `pricePerToken` fields.

Other selections used the app's layered pricing estimates and current model
availability. Cross-provider estimates are not a guarantee of the provider's
own tariff. Moonshot's available Kimi models remain more expensive than small
models on other hosts; K2.6 was the lower-cost general-purpose option in the
available catalog. Rates and availability can change.

## Related fixes

- **Glama endpoint:** use `https://glama.ai/api/gateway/openai/`. The newer
  gateway hostname failed DNS resolution in the earlier refresh. Glama
  explicitly continues to support the legacy endpoint. Both model discovery
  and generation succeeded through it. [Glama release notes](https://glama.ai/release-notes)
- **Glama catalog parsing:** its `capabilities` field is an array of names,
  whereas Mistral uses an object of boolean flags. A field adapter accepts
  both forms, preserves recognized capabilities, and tolerates unsupported
  metadata shapes. Android successfully parsed all 283 Glama catalog entries.
- **Glama latency:** GPT 4.1 Nano routes approached or exceeded the app's
  30-second provider-test timeout. Gemini Flash Lite returned the expected
  response in approximately 1.2 seconds in the emulator.
- **Refresh persistence:** concurrent full-settings saves could overwrite a
  newer snapshot. AI-ML-API passed generation but its new default agent was
  missing after restart. Both refresh variants now save the final merged
  settings after all worker/catalog jobs have joined, before exposing restart.
- **Refresh contention:** starting every provider worker simultaneously made
  metadata processing and settings serialization contend heavily. A repeat
  run timed out several catalogs after their HTTP responses had succeeded.
  The full worker flow now processes providers sequentially, including
  metadata processing and persistence. This avoids repeatedly invalidating
  expensive shared-settings merges and starving large catalogs. Queued
  providers do not start their request timeout until their worker begins.
  Large-catalog pricing recomputation remains expensive: a debugger stack
  sample identified repeated normalized catalog scans in `PricingCache`.
  Serializing the worker flow avoids contention but does not eliminate this
  remaining processing cost.
- **Trace retention:** pruning after every response reparsed the retained
  trace directory under a global lock, delaying completed requests by seconds
  and contributing to catalog timeouts. Pruning now reuses cached trace
  metadata and primes that cache on its first scan. The count/size retention
  limits, protected current trace, and streaming-update handling are preserved.
- **Offline model lists:** merged fallback lists include the new defaults.
  Moonshot's four retired fallback entries were replaced with its four current
  catalog IDs. Both provider reference documents were updated.

## Validation and scope

All 11 replacements produced HTTP 200, final response text `OK` (ignoring
surrounding whitespace), and finish reason `stop` in Android API traces. Each
request used the app's `Reply with exactly: OK` smoke-test prompt with a
64-token output limit. Keys and other existing app data were preserved.

The 25 other configured providers already used the same defaults in the repo
and emulator. The APK contains all 91 provider definitions and each matches
its source JSON. Fresh-install initialization reads those bundled files.
The remaining **55 providers have no imported key**, so their defaults were
not live-verified or changed in this pass.

The final **Providers / models / default agents** refresh completed at
**36 / 36** in the emulator:

- **36 provider checks passed**, including all 11 repaired defaults.
- **31 API-backed model catalogs refreshed**; the five manual/fallback
  providers correctly skipped model-list discovery.
- **36 matching default agents** were saved, each using its provider's
  bundled default. The `default agents` flock contains exactly those 36
  unique agent IDs.
- **Restart preserved the complete saved result:** provider states, default
  models, catalog timestamps, agent IDs, and flock membership were identical
  before and after using the app's Restart application button.
- Startup reported **0 model-list downloads**, confirming that it reused the
  newly refreshed caches. `com.ai/.MainActivity` was confirmed foreground.
- The run recorded **no non-2xx HTTP responses, network failures, catalog
  errors, fatal exceptions, ANRs, or trace-cache write/update errors**.
- Trace retention remained within its limits: **480 files, 51,954,305 bytes**
  (below 2,000 files / 50 MiB).

The full provider refresh took approximately **43 minutes** on this emulator
(04:55–05:38 local time, with brief diagnostic stack sampling). Large-catalog
pricing recomputation remains a performance issue despite the successful
completion and persistence checks.

The installed emulator APK, local debug APK, and `/Users/herbert/cloud/ai.apk`
were byte-identical. SHA-256:

`aa27a286cd5205160b52f1180a5283bce42faf0cef6f32744adc553378a3834c`

The default development cycle was used: debug build, in-place emulator
installation, cloud APK copy, launch/foreground check, and commit. No unit
or instrumented test suites, uninstall, or app-data restore were performed.
