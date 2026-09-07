# Fan-out audit fixes — 7 September 2026

All six application findings in [the fan-out monitoring audit](fan-out-monitor-2026-09-07.md) are addressed. The earlier audit's observations and provider failures remain preserved as historical evidence.

## Why fan-out became slower

Commit `e87c6b8a2` (**6 September, “fix: address Report audit integrity and workflow findings”**) changed the shared batch scheduler to sequential windows of 64 concurrent jobs. Every item in a window had to finish before any item in the next window could start. Slow responses and rate-limit benches therefore left unrelated providers idle. The original monitored run repeatedly exposed this: even one running pair could leave hundreds queued.

The scheduler now uses bounded rolling admission. A completed or individually cancelled job immediately admits its successor. It retains host interleaving, per-host limits, the global concurrency limit, cancellation handling, work reviews and the work-size limit. Admission is `max(64, configured global concurrency)`, capped by the report work-item limit. A configured concurrency above 64 is therefore no longer silently reduced by the admission layer. Provider/network delays and configured rate limits can still affect elapsed time.

## Corrections

| Finding | Correction |
| --- | --- |
| **FAN01 — direct traces** | Each secondary attempt installs immutable report/run/model tags and its own trace sink. Saves carry the response trace reference; a trace-only atomic finalizer preserves it for timeouts and network failures without replacing answer/cost/metadata fields. The HTTP interceptor reserves the filename before network I/O. The pair screen now opens the saved response/source reference instead of guessing by model and timestamp, and exposes the response trace beside its result in either navigation role. |
| **FAN02 — idle batch windows** | Replaced `.chunked(64)` barriers with rolling admission; existing semaphore and bench behaviour remains in place. |
| **FAN03 — duplicate successful generations** | Repository retries hand a signalled bench failure back to the outer scheduler without immediately retrying through the bench. A success or permanent semantic/client failure clears any earlier bench signal, preventing a settled result from being discarded and generated again. |
| **FAN04 — Fan Meta autostart** | Normal fan-out and rerun completion can launch metadata for successful rows. Master Autostart, Fan Meta autostart, the Fan Meta feature gate and per-report metadata settings are respected. Failed/partial rows, existing metadata and active jobs are excluded; cancellation/deletion finalizers cannot launch enrichment. The existing app work review remains in effect. |
| **FAN05 — Replicate model identity** | A shared request-model resolver retains `owner/model` for Replicate, supports Gemini model paths and JSON model fields, and prefers explicit model tags. Optional body fallback skips one-shot/duplex and oversized or unknown-length bodies. |
| **FAN06 — HTTP statistics** | Immutable OkHttp request context carries the actual model before dispatcher handoff. Per-run response counters notify Compose as responses arrive, so Statistics appears and updates without reopening the run. Retry responses retain their individual counts. |

The trace cache also upserts first saves using a preallocated filename, preserving subsequent streaming updates under that same filename.

## Emulator verification

Used the actual Android fan-out and Fan Meta engines against a temporary local HTTP server through `adb reverse`, with two disposable providers using OpenAI-compatible and Replicate formats. Both app work reviews restricted requests to the two loopback origins. No external paid generations were used for these checks.

A nine-answer disposable report produced **72 unique non-self pairs**, using the predefined **response — Just the response** prompt. Controlled faults exercised 429 recovery, truncated output, content filtering, empty output, socket failure and timeout.

| Check | Observed result |
| --- | --- |
| Rolling admission | Pair **65 started 32.604 seconds** after the first request. An earlier slow pair was still outstanding and finished at **54.627 seconds**. All 72 unique pairs had reached HTTP by **38.026 seconds**. This directly demonstrates removal of the 64-item barrier; it is not a full-provider throughput benchmark. |
| Final pair outcomes | **67 success / 5 deliberate errors**, no unfinished rows. Errors were truncation, filtering, empty content, network failure and a 90-second timeout. |
| Recovered rate limit | Exactly **one 429 followed by one 200** for the affected pair. No duplicate successful generation. Socket failure exercised its separate two-attempt retry path. |
| Direct trace integrity | **72/72** distinct references resolved on disk; **zero** mismatched report, run or model identities. Timeout and network-error rows retained status-0 traces. Replicate traces retained the full `meta/meta-llama-3-8b-instruct` model. |
| Fan Meta | Completion automatically presented a **67-item** work review. After approval restricted to loopback, **67 HTTP metadata requests** populated **67 titles and icons**. None of the five failed rows was enriched. |
| HTTP Statistics | The control appeared during the run. Completed UI showed **71 HTTP responses / 9 models**, including **one 429**, **62 HTTP 200s** and **eight HTTP 201s**. Connection failures and the cancelled timeout produced no HTTP response and were correctly excluded. |

The first disposable pass used unknown model IDs, a shorter 20-second deadline and a smaller server connection backlog; it finished with 9 successes and 63 errors during startup. It confirmed the metadata gate and statistics wiring, but was unsuitable for timing validation. The second pass used catalogued model identities, a larger server backlog and a 90-second deadline. Its controlled outcomes above are the accepted scheduling/attribution verification. These simultaneous fixture changes do not isolate the first pass's overhead.

On the final build, the pair-screen trace button opened the intentionally truncated request with the matching model, report/category and timestamp, and its `finish_reason=length` response. A scoped logcat check of the final process found no crash or storage-failure markers.

Source inspection also checked the master/per-feature autostart guards, per-report metadata gate, work review path, cancellation propagation and bounded admission. No unit or instrumented test suites were run, following the repository's default cycle.

## Data preservation and verification setup incident

During initial fixture setup, an incorrect host-to-emulator stdin command truncated the provider-registry file. API keys and report files were not involved. The registry was recovered from the current repository's 91 shipped provider definitions. The saved provider field-override markers were empty; prior working-model validation was checked against those definitions. This was a fixture-operation error, not an app defect. Exact original registry bytes were unavailable, so recovery was verified against those definitions and markers rather than claimed as a byte-for-byte restore.

Cleanup removed both disposable reports, 29 synthetic provider-usage rows representing 163 calls, their matching aggregate contributions, and 224 local trace files. The temporary server and ADB reverse mapping were stopped. The master autostart setting returned to off, Fan Meta autostart to on, and the item timeout to 180 seconds. The restored registry contains 91 providers. Non-fixture preference fields were preserved. The entire original report JSON and all 1,260 secondary rows compare equal to the pre-fix audit evidence. Compact final preservation and runtime evidence is retained in [fan-out-fixes-2026-09-07-evidence.json](fan-out-fixes-2026-09-07-evidence.json).

## Scope and remaining historical limits

The original Funny question report's **36 primary results**, **1,260 fan-out rows**, **1,367 cost-ledger entries** and **$0.8186242033 recorded spend** are preserved. Its historical 15 provider/generation errors and previously incurred duplicate charges are not removed or represented as repaired provider outcomes. The full 1,260-pair paid fan-out was not rerun.

New calls receive corrected trace attribution. Missing historical secondary references are not guessed or backfilled, and HTTP Statistics remains session-only by design; restarting the app does not recreate old counters. The deployment applies the fixes to the emulator and the cloud APK for subsequent installations.
