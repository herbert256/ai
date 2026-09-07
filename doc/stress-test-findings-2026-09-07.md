# Housekeeping stress test — 7 September 2026

Ran the actual **Housekeeping → Test → Stress test** flow on
`emulator-5554`, using debug build from commit `ea744300e`. Started at
19:39:39 Europe/Amsterdam. All primary generation finished at 19:42:00;
the final metadata response arrived at 19:42:49. The dashboard subsequently
returned to zero calls in flight. Existing reports and settings were preserved.

## Outcome

| Check | Result |
|---|---|
| Scope | 19 example prompts × 6 active Level 2 models |
| Primary answers | 114 successful; zero failed, stopped, or pending |
| Primary providers | Anthropic, DeepSeek, Google, Mistral, OpenAI, xAI; 19 successes each |
| Actual HTTP calls | 430, all HTTP 200; no 429/4xx/5xx responses |
| Recorded cost | $0.209356, according to the app's frozen pricing |
| Recorded tokens | 50,848 input; 74,655 output |
| Persistence | 19 readable reports; all answer bodies present, including two external content blobs |
| Accounting | 430 traces matched 430 ledger entries; no duplicate IDs/traces, missing entries, or report/model attribution mismatches |
| Cached report costs | Every report's `totalCost` matched its ledger sum |
| Prompt isolation | Every primary request contained its own report's prompt; no unresolved prompt placeholders |
| Thinking filter | No remaining `<think>` tags in any stored primary answer |
| Metadata | All 114 answer titles/icons and all 19 report titles/icons completed |
| Process health | Same PID 8805 throughout; no app crash, ANR, or out-of-memory event in captured logs |

Automatic secondary analyses were disabled by the existing
`autostart_items_enabled=false` setting. This run exercised primary reports
and metadata, but did **not** exercise Fan-out/Fan-in, Tournament, Translate,
or other secondary engines. No unit or instrumented suite was run.

## Confirmed reporting bugs

### 1. The confirmation understates the workload by excluding metadata

The dialog said **19 prompts × 6 active models = 114 API calls**. The actual
run made **430 calls**, 3.77 times that estimate. The additional work was
enabled before starting the test:

| Trace category | Calls |
|---|---:|
| Primary answers | 114 |
| Answer titles | 114 |
| Answer icons | 115 |
| Report short titles | 19 |
| Report long titles | 19 |
| Report icons | 19 |
| Language names | 19 |
| Language icons | 11 |

One answer-icon response was rejected and a fallback succeeded. Eight
language-icon results used the cache instead of making another HTTP call.

`StressTestEngine.Estimate.apiCallCount` only multiplies prompts and models
(`viewmodel/StressTestEngine.kt:40`), while the confirmation labels that
product as API calls (`ui/admin/StressTestScreen.kt:108`). Background
submission also starts report metadata and per-answer enrichment.

Suggested correction: label the product as **primary calls**, show the
enabled metadata workload separately, and state that cache hits and worker
fallbacks change the final count. This is a workload/expectation bug, not
an accounting loss.

### 2. API completion logs and dashboard timing omit response-body time

For trace
`api.deepseek.com_20260907_194126_569_6qwb_d834f3dc.json`:

- Request start: **19:41:26.569**.
- Completion log: **19:42:49.302**, claiming **305 ms**.
- Trace written: **19:42:49.343**.
- Actual interval between the request and completion log: **82.733 s**.
- The report ledger's worker duration was **120.310 s**, which also includes
  work before HTTP dispatch, such as throttle/worker waiting.

`TracingInterceptor.kt:98` freezes `durationMs` immediately after
`chain.proceed`. It subsequently reads the non-streaming response body at
line 129, then logs the previously captured duration at line 144.
`HttpStatusStatsInterceptor` similarly records time before the body has
been consumed (`data/HttpStatusStats.kt:206`), and those figures feed the
dashboard's **Response times** and slow-call views.

Consequently a slow generation can appear fast in the tools intended to
diagnose slow requests. Suggested correction: distinguish time to response
headers from full response duration, and use body-completion timing for
end-to-end/slow-call reporting. Preserve separate throttle/queue timing.

## Other observed issues

### 3. A tiny metadata job produced excessive reasoning

The same DeepSeek `deepseek-v4-flash` call returned only **⚠️**, but billed
**10,738 output tokens**, including **10,735 reasoning tokens**. Its
recorded cost was **$0.01422476**. The request allowed
`max_tokens=384000` and contained no explicit reasoning restriction.
The response held 41,195 characters of separate reasoning content.

This was a successful provider response, and its usage and cost were
recorded correctly. It exposes inefficient worker/model settings for an
emoji task. A small metadata-specific output budget and a suitable
non-reasoning worker would prevent this kind of tail latency and spend.
Any change should respect explicit user parameter overrides and the
provider's reasoning controls.

### 4. Launch caused a visible UI pause

At **19:39:40.909**, Choreographer logged **52 skipped frames**. Nearby
HWUI records reported frames of **1,337 ms** and **1,084 ms**. The app
recovered, continued processing, and responded to dashboard inspection.

This is an observed responsiveness issue on this emulator. The exact
main-thread cause has not been profiled. Aggregate graphics statistics
also included navigation before the test, so their overall jank percentage
must not be presented as a stress-test-only measurement.

Sampled PSS was about **207.7 MiB** before the run, peaked at **243.1 MiB**,
and returned to **221.1 MiB** afterward. These samples did not show runaway
growth, but one short run cannot establish absence of a memory leak.

### 5. Language icons are inconsistent and sometimes uninformative

All reports were detected as English. Two Cohere language-icon calls
returned **😊**, and an OpenRouter call returned **🗣️**; other English
reports received 🇬🇧 or 🇺🇸. The requests correctly contained
`Language: English`, so this was not prompt substitution or cross-report
state corruption.

`IconGenerationManager.kt:1099` accepts any parseable emoji and caches it
for that language and worker configuration. This is a metadata quality
issue: the UI can show an unrelated smiley as a language icon. A stable
language-to-icon mapping, or a more explicit icon policy, would make the
result consistent without asking a model repeatedly.

Example traces:

- `api.cohere.ai_20260907_193952_537_6qot_26c5d44f.json` — 😊.
- `api.cohere.ai_20260907_194002_395_6qpc_0edc660a.json` — 😊.
- `openrouter.ai_20260907_193951_648_6qoq_9c4fac0b.json` — 🗣️.

## Evidence and limits

Local detailed evidence is retained in
`/private/tmp/ai-stress-20260907/`: continuous `logcat.txt`, sampled
`samples.jsonl`, the 19 IDs in `report-ids.json`, final reports and traces,
`audit.json`, screenshots, and graphics statistics. Raw runtime payloads
are not committed to the repository. The generated reports remain in the
app for review.

The monitoring sampler initially flagged two absent inline response fields;
both were valid external content references and were read and verified.
An apparent single missing ledger entry disappeared once the final worker
finished; the settled audit reconciled all 430 entries. Neither was a bug.

No app code or configuration was changed while investigating this run.
