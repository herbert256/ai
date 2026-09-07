# Workers swarm health check — 7 September 2026

The saved `workers` swarm has been expanded from 12 to **19 models from 19 providers**. Eight providers were added, SiliconFlow was removed from the pool, and four existing selections were replaced with cheaper models. The emulator's saved swarm and the bundled seed use the same membership. Provider keys and other swarms are preserved.

## Existing pool and candidate findings

The original pool passed **11 of 12** parallel, authenticated API probes. Each request asked for a short title, a check-mark emoji and the language name, with a 512-token output ceiling. Keys were read in memory from the emulator and used only with their configured provider endpoint; no keys or authorization headers are included in this report or its evidence file.

- **SiliconFlow / Qwen3-14B:** TLS validation failed because the endpoint certificate expired at **2026-09-07 10:12:23 UTC**. The certificate's subject was `siliconflow.com`, issued by Let's Encrypt YE2. Removed from `workers`; the provider definition and saved key remain available. Certificate checks remain enabled.
- **Parasail / Llama 3.2 3B:** returned the requested words but omitted the emoji. Rejected in favour of Gemma 3 4B, which returned all three artifacts.
- **Mistral / Ministral 8B:** answered successfully but added code fencing. Selected Ministral 14B, which followed the simple format in the verification calls.
- **Nebius / Gemma 3 27B:** passed the host probe, then stalled in the first emulator run. Streaming and the non-streaming fallback ultimately failed after 538.571 seconds. A subsequent recorded retry succeeded in 0.411 seconds, so this was intermittent. The final pool uses **Qwen3 30B A3B Instruct 2507**; its separate streaming probe completed in 0.736 seconds with all three artifacts.

These checks establish authenticated access, usable text and emoji output, and actual app compatibility for the tested requests. They do not constitute a quality benchmark for every translation, judging or reranking task. Some providers add whitespace or a short preamble to this prompt.

## Final membership

| Provider | Model | Change |
|---|---|---|
| Mistral | `ministral-14b-latest` | Replaces Medium |
| OpenAI | `gpt-4o-mini` | Retained |
| Groq | `openai/gpt-oss-20b` | Retained |
| Cerebras | `gpt-oss-120b` | Retained |
| DeepSeek | `deepseek-v4-flash` | Retained |
| Google | `gemini-2.5-flash-lite` | Replaces 3.5 Flash |
| Anthropic | `claude-haiku-4-5-20251001` | Retained for provider diversity |
| xAI | `grok-4-1-fast-non-reasoning` | Replaces 4.20 non-reasoning |
| Cohere | `command-r7b-12-2024` | Replaces Command R |
| DeepInfra | `google/gemma-3-12b-it` | Retained |
| Together | `openai/gpt-oss-20b` | Retained |
| Parasail | `google/gemma-3-4b-it` | Added |
| NebiusAIStudio | `Qwen/Qwen3-30B-A3B-Instruct-2507` | Added |
| Novita.ai | `meta-llama/llama-3.1-8b-instruct` | Added |
| OpenRouter | `ibm-granite/granite-4.0-h-micro` | Added |
| VercelAIGateway | `mistral/ministral-3b` | Added |
| Amazon | `openai.gpt-oss-20b` | Added |
| Requesty | `openai-responses/gpt-4.1-nano` | Added |
| Glama | `google/gemini-2.5-flash-lite` | Added |

Published examples supporting the low-cost choices, in USD per million input/output tokens: Ministral 14B **$0.20 / $0.20** ([Mistral pricing](https://docs.mistral.ai/inference/pricing)); Gemini 2.5 Flash-Lite **$0.10 / $0.40** ([Google pricing](https://ai.google.dev/gemini-api/docs/pricing)); DeepInfra Gemma 3 12B **$0.05 / $0.15** ([DeepInfra model page](https://deepinfra.com/google/gemma-3-12b-it)); OpenRouter Granite Micro **$0.017 / $0.112** ([OpenRouter model page](https://openrouter.ai/ibm-granite/granite-4.0-h-micro)); Vercel Ministral 3B **$0.10 / $0.10** ([Vercel model page](https://vercel.com/ai-gateway/models/ministral-3b)). These are standard text rates, not the cost of one worker request. Gateway routing, caching, reasoning tokens and regional pricing can affect the charged amount.

The app displayed a combined catalogue rate of **$4.21 input / $13.25 output per million tokens** for the whole 19-member selection. This is the sum across all selected models, not a single worker's rate. Anthropic Haiku is more expensive than the smallest open models and is retained to keep Anthropic represented.

## App verification and timing

The initial report, `1b6c1e0a-c814-400f-9e44-048e2b85137b`, was run through the actual saved swarm with “Answers only” enabled. Eighteen models initially succeeded; Nebius Gemma timed out. The successful calls took **75.449–97.893 seconds**, median **90.820 seconds**. The app recorded **$0.00069406** for those 18 calls. Subsequent report history is preserved, including the successful Nebius retry.

The final dedicated report, **`6758992b-5542-4c29-b6f5-e5c8e2e47627`** (“Workers verification - 19 providers”), completed **19/19 successfully**, with HTTP 200 and a title, emoji and language in every answer. The app recorded **$0.00130451** (about **0.13 US cents**) for the 19 calls. This is the app's cost ledger, not a reconciled provider invoice.

Final call durations were **2.142–10.080 seconds**, with a median of **4.124 seconds** across all 19 workers. Comparing the same 18 provider/model selections that succeeded in both runs, the median fell from **90.820 seconds to 4.807 seconds**. Nebius's replacement completed in **4.124 seconds**. Calls used the app's normal parallel report flow.

All 19 final trace filenames are distinct, exist on disk, have `partial=false`, and match the report ID, run ID, model and `report/prompt` category. The 19 unique cost ledger entries reference those same traces and sum to the report total. The saved 19-member swarm matches the bundled seed exactly. The actual verification results are retained in the app. Final retention inspection found 1,672 JSON traces totalling 51,944,877 bytes, within the 2,000-file / 52,428,800-byte limits. No runtime or trace-write errors were observed in the scoped app log.

## Performance changes discovered during verification

Short provider responses were delayed by trace bookkeeping. Source inspection found that a cold `ApiTracer.getTraceFiles()` scan parsed up to 50 MiB while holding the same lock needed by response completion. Cold retention pruning could also parse the entire trace directory under that lock. The device held roughly 1,650 traces during the initial investigation.

- Cold trace metadata scans now happen outside the writer lock. A directory version check prevents a concurrent write or deletion from publishing an obsolete scan.
- Retention uses cached timestamps or the timestamp encoded in the trace filename, with filesystem time as a legacy fallback; it no longer parses response JSON under the writer lock. Existing count and byte limits remain in place.
- Atomic-write cleanup rejects unrelated filenames before making filesystem stat calls for every sibling.
- Trace prewarming starts after `ApiTracer.init()` supplies the directory. Previously the background warm-up could run first and silently return without scanning anything.

The measured timings are a small synthetic smoke check on this emulator, not a controlled latency benchmark. Provider latency and concurrent work can vary. The lock and startup-order changes address identified code paths; they do not remove provider timeouts or guarantee that every response completes within a fixed duration.

## Persistence and delivery

The bundled seed is `ai/src/main/assets/workers/swarms/workers.json`. Migration explicitly recognizes both historical 12-member pools, independent of display ordering, and preserves the swarm ID, parameter references and system-prompt reference. Custom membership is retained. The actual device migrated to 19 members while keeping ID `2f12f785-2852-4815-89f2-ec4298573c87`; the final Nebius substitution was applied only to that saved swarm. The other three swarms and unrelated preferences were checked unchanged.

Both the Markdown worker documentation and the bundled technical HTML were updated. The debug build passed, the APK was installed with `adb install -r`, copied to `/Users/herbert/cloud/ai.apk`, and the app was confirmed in the foreground. The build and cloud APK SHA-256 hashes match. No unit or instrumented test suites were run, in accordance with the repository's default cycle. No uninstall, app-data restore or report deletion was performed.

Sanitized per-provider results and final app checks are in [workers-health-2026-09-07-evidence.json](workers-health-2026-09-07-evidence.json).
