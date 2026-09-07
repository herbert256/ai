# Report monitoring findings: remaining fixes and verification

Follow-up to [the Funny question monitoring audit](report-monitor-2026-09-07.md), performed on 7 September 2026 against `emulator-5554`. F01 was already fixed in `4331ce9f8`; this change addresses F02–F06 and preserves that correction.

## Changes

### Final-answer validation and failed-generation accounting (F02)

OpenAI-compatible report calls now distinguish HTTP success from a usable final answer. Streaming and non-streaming parsing retain the finish reason. `length`/`max_tokens`, filtering, unsupported tool requests, and missing final content produce an error. Reasoning text is never substituted for a report answer. A transport interruption preserves available usage and partial final text; an already billed incomplete generation does not silently trigger another unchanged request. Chat retains its compatibility reasoning fallback only after a normal `stop`.

The primary report record persists the finish reason and clears it when a new attempt starts or the answer is edited. Failed responses retain usage, cost and direct trace references. Proven historical reasoning-only successes are corrected only when a matching retained trace establishes that the saved body was reasoning; the old text is archived and all existing costs are preserved.

### Working provider and metadata defaults (F03)

Together's bundled provider default and the Groq/Together members of the bundled `workers` swarm now use `openai/gpt-oss-20b`. Bootstrap migrates the installed pool only if its entire member list matches the old bundled pool. Its UUID and references are preserved; custom or edited pools are left intact. A worker's HTTP 400 `model_not_available` response is treated like an unavailable model for the rest of the session, avoiding repeated dedicated-endpoint failures.

Six authenticated probes ran in parallel across the two providers: one answer, title and emoji prompt per provider. All six returned HTTP 200 and `finish_reason=stop`. Recorded probe durations were 1.812–5.338 seconds for Together and approximately 0.24–0.29 seconds for Groq. Native catalog prices observed during validation were $0.05/$0.20 per million input/output tokens for Together and $0.075/$0.30 for Groq. These prices and account availability are observations at verification time.

A real title-only check uncovered another prompt issue: Together followed/confused the commands inside the report question and produced clarification-style titles. The title builder now labels the question as source text and JSON-quotes it inside the configured title template. A new cache variant prevents reusing titles generated with the old ambiguous prompt. Separate clearer quoted-input probes produced “Animal Joke Prompt” and “Request for Humorous Animal Question.” The exact final app wording compiled and was deployed, but its final live check was blocked as described under limitations.

### Report ledger, tracing and summary consistency (F04–F06)

Short/long report-title usage is recorded while the report tracing context is active, under `report/title-short` and `report/title-long`. Nested API auditing hands the captured filename back to the outer report sink. Usage also carries immutable call-specific attribution, preventing a later worker/fallback from supplying the wrong trace during accounting.

Version 4 upgrades an existing complete version-3 ledger without rebuilding it from the current answers. It retains call IDs, frozen amounts and prior attempts; adds only residual missing saved title amounts; and avoids charging those already globally recorded titles to aggregate usage again. Historical trace links require matching report/provider/model and unambiguous usage/time or response-body evidence. Migration also checks that the report did not change while trace evidence was being read.

The summary listens to both primary and secondary report data versions and labels its count “costed calls.” Manage, metadata, second-results and the report-deletion spending summary now use the current lifetime ledger. This matters after retries: the current visible result fields alone do not include every earlier billed attempt. Ledger updates arrive through the existing batched cost-journal flush; legacy reports retain the structured-total fallback until migration.

## Emulator verification

No unit or instrumented suites were run: this used the repository's default build/deploy cycle and targeted manual emulator checks, not its extended cycle. No app uninstall or app-data restore was performed.

### Output-limit failure

A disposable report used one Together `openai/gpt-oss-20b` slot with `max_tokens=1` and metadata disabled. A targeted failed-item retry produced:

| Check | Result |
| --- | --- |
| Transport | One HTTP 200 stream |
| Finish reason | `length` |
| Persisted status | `ERROR` |
| Error | `Response truncated: output token limit reached (finish_reason=length).` |
| Usage | 100 input / 1 output token, native usage rather than an estimate |
| Cost | $0.0000052, retained in one ledger row |
| Trace | `api.together.xyz_20260907_102050_006_gwlq_ed8e4882.json` |
| Follow-on work | No fallback/retry and no answer title/icon |

A later read still contained exactly one cost row and the same ERROR. This directly exercises the failure that the original audit had incorrectly seen saved as success.

### Successful primary calls, metadata accounting and trace attribution

A second disposable report contained Together and Groq GPT OSS 20B slots with the Funny question example plus an animal-theme instruction. Its endpoint restriction permitted only the two validated providers. Other worker candidates were rejected locally before HTTP and fallback selected an allowed worker; those local validation restrictions are not evidence of provider failure.

| Provider | Saved final answer | Finish |
| --- | --- | --- |
| Together | What kind of fish can never play the bass? | `stop` |
| Groq | What animal always has a full house when it goes to the theater? | `stop` |

Both primary results succeeded and received titles/icons. The live summary updated as cost records arrived. With the two title-only requests included, the report contained eight records: two primary answers, two per-answer titles, two icons, one short report title and one long report title. Every cost row and both primary answers linked to a trace whose report ID and model matched. Total ledger cost was $0.00134985.

The report-title calls established accounting/trace correctness but produced poor titles with the earlier ambiguous prompt. Those outputs are not claimed as successful validation of the final prompt wording.

Relevant trace filenames:

- Together answer: `api.together.xyz_20260907_101613_249_gwky_4a947f1f.json`
- Groq answer: `api.groq.com_20260907_101610_677_gwkx_f8390e32.json`
- Short title: `api.together.xyz_20260907_101734_357_gwlp_98c0bbe6.json`
- Long title: `api.together.xyz_20260907_101734_297_gwlo_0502d08a.json`

Both disposable reports were deleted through the app after their verification evidence was saved locally.

### Existing Funny question report repaired

Report ID: `4757c2cc-36e7-4d2e-9a9c-531bca7162dd`.

At the start of this follow-up, the previously repaired Novita slot had brought the ledger to 111 entries and $0.1083023481. Automatic title reconciliation added exactly $0.012213, yielding 113 entries and $0.1205153481. Multiple restarts preserved that result without duplicate repair entries.

The original Together trace no longer existed on-device. The earlier monitoring evidence retained the exact 62,106-character saved body and a trace summary proving zero final-content characters and `finish_reason=length`. A targeted installed-data repair first verified exact equality with that archived body, retained it in answer history, marked the slot failed, and changed only that slot and its matching default agent to the validated model. Its agent identity and flock membership were preserved. The repo default and unchanged worker-pool migration provide the corresponding fresh-install/update behavior.

The app's **Retry failed** action selected one primary work item. It returned:

> What kind of band doesn’t play music but still keeps a perfect rhythm on your wrist?

Together used `openai/gpt-oss-20b`, completed in 19.652 seconds with `finish_reason=stop`, and received the title **Wristband Rhythm** and icon **🌀**. The primary trace is `api.together.xyz_20260907_102418_924_2zyl_e2f9d881.json`.

Final persisted checks:

- All **36** agent slots have `SUCCESS`.
- The other **35 answer bodies are unchanged** from the start of this follow-up.
- All **111 existing cost IDs, amounts and token counts** are retained.
- The invalid prior Together text remains in answer history.
- The ledger has **116 entries** and totals **$0.1208429681 (12.08¢)**: two repaired title entries and three newly billed Together retry/title/icon entries.
- All three new calls have matching direct trace links. The retry title and icon used the existing Mistral and SiliconFlow workers successfully.

### Final build and deployment

`:ai:assembleDebug` passed on Java 25. The APK was installed with `adb install -r`, copied to `/Users/herbert/cloud/ai.apk`, and launched. The final local APK, cloud copy and installed base APK all have SHA-256 `d997f81412f7359c8fe5ba8eda92c0148fd9aa5033ff5ced0a4e4ce698bb6c59`.

After the last deployment, the settled Manage screen displayed **116 costed calls / 12.08¢**, matching Costs and the persisted ledger. No crash/ANR markers were found in the checked logcat buffer. `com.ai/.MainActivity` was confirmed foreground; the repaired Together answer was left open. `git diff --check` passed.

## Verification limits

The original 07:25 trace files had already been deleted before this follow-up. Available newer traces allowed conservative recovery of some historical links; deleted trace bodies and references cannot be recreated. The original report has six linked cost rows and two linked primary answers after the new retries. The app leaves unresolved old references blank.

Automatic approval review first rejected a full report regeneration because it could also rerun secondary workflows and increase costs. Targeted primary retries and title-only checks were used instead. A later final title-only rerun was also rejected because it would transmit the example question to third-party endpoints and incur two API charges without sufficiently specific authorization. That action was not retried or bypassed. The final quoted title-prompt wording therefore still needs an authorized live check.

Provider output quality remains model-dependent. The original audit's AI-ML-API punchline and Perplexity repeated example were retained with the other unchanged answers; they were not application defects or targets of this repair.
