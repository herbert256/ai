# Novita report token-limit fix — 7 September 2026

The Novita failure identified as F01 in [the report monitoring audit](report-monitor-2026-09-07.md#f01--p1--novitas-default-report-request-exceeds-the-hosts-context-limit) is fixed. The existing Funny question report was retried successfully on `emulator-5554` using its original model and prompt.

## Changes

- Generic model catalogs now retain positive `context_size` and `max_output_tokens` limits. Existing installations recover these fields from their saved raw catalogs during settings loading, preserving other capabilities and avoiding a network refresh.
- Default output and context limits resolve independently. A native context limit constrains every default source, including provider rules and the fixed fallback. Native output limits are also respected. The default reserves 4096 tokens for input; the old 1024-token floor can no longer exceed a small remaining context window.
- Report streaming no longer falls back for deterministic HTTP client errors. HTTP 400/422 permits fallback only for an explicit rejection of streaming fields, excluding token/context errors. Transient failures and empty streams still permit fallback. If both attempts fail, the saved error retains both diagnostics. A fallback exception cannot accidentally trigger another fallback.

The input reserve remains fixed headroom, not exact prompt tokenization. Explicit user max-token overrides retain their existing precedence.

## Emulator verification

Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :ai:assembleDebug` — successful. Installed with `adb install -r`, copied to `/Users/herbert/cloud/ai.apk`, launched and confirmed in the foreground. No unit or instrumented test suites were run, following the repository's default cycle.

The built APK, cloud copy and installed APK had identical SHA-256 hashes: `6eb95ae07503d9e4e2350c73570188ce0d8fd49c862e0418d373f53f43c4a5c6`. Force-stop and relaunch preserved the repaired report and its 111-call cost ledger.

The report's **Retry failed** action selected one work item. The existing saved catalog had `context_size=16384` and `max_output_tokens=16384`, while its persisted capability entry was absent before the update. No model-list refresh was performed before retrying, so the successful request also verifies recovery from that saved catalog.

| Field | Verified value |
| --- | --- |
| Report | `4757c2cc-36e7-4d2e-9a9c-531bca7162dd` — Request for a Funny Question |
| Provider / model | Novita.ai / `meta-llama/llama-3.1-8b-instruct` |
| Request time | 09:45:22, Europe/Amsterdam |
| Trace | `api.novita.ai_20260907_094522_439_be5r_0fcc1a73.json` |
| Request | Streaming, `max_tokens=12288`, usage reporting enabled |
| Result | HTTP 200, one Novita call, no non-streaming fallback |
| Actual usage | 68 input tokens, 13 output tokens; provider-reported usage |
| Duration | 3950 ms |
| Saved answer | What has a head, a tail, but no body? |
| Saved metadata | Head and Tail Riddle; 🐍 |

Comparing saved report objects before and after showed that only Novita's agent entry changed. The other 35 entries were identical. Three calls were added to the cost ledger: Novita's answer and its two successful metadata calls (OpenAI title, Mistral icon).

The permanent-error and failed-fallback branches were reviewed against the original two HTTP 400 diagnostics; this validation did not deliberately send another invalid paid-provider request. Other findings in the monitoring audit, including Together's reasoning-only answer, are separate from this fix.
