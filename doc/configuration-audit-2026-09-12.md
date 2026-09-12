# Report configuration audit — 12 September 2026

This audit exercises reusable Agents, Flocks and Swarms, named system prompts,
parameter presets, report and row overrides, secondary results and replay.
A successful HTTP response is insufficient: saved execution settings and wire
bodies must match the configuration the user selected.

The new optional configurations are defined in `ReportAuditExamples.kt` and
installed additively: 9 Agents, 4 Flocks, 3 Swarms, 8 parameter presets, 6 system
prompts and 4 internal prompts. They contain no keys or generated report data. Existing settings
are preserved. Each level has a unique marker and temperature so incorrect
inheritance is visible in both request traces and responses.

| Level | Marker | Temperature | Output cap |
|---|---|---:|---:|
| Agent | AUDIT_AGENT | 0.11 | 384 |
| Flock | AUDIT_FLOCK | 0.22 | 512 |
| Swarm | AUDIT_SWARM | 0.33 | 640 |
| Report | AUDIT_REPORT | 0.44 | 768 |
| Row | AUDIT_ROW | 0.55 | 896 |
| Secondary prompt | AUDIT_SECONDARY | 0.66 | 1024 |
| Embedded parameter prompt | AUDIT_EMBEDDED | 0.77 | unset |

The six configured providers are Anthropic, DeepSeek, Mistral, OpenAI, xAI and
Google. Three extra OpenAI agents exercise shared-model identity, empty defaults,
embedded system instructions, and a deliberately rejected reasoning/sampling combination. Overlapping groups exercise source selection.
The existing Level 1–3 and workers swarms provide broader model coverage.
Reports 24–26 passed all 18 level models, report 27 passed all 19 worker
providers, and report 28 passed all 18 level models with the report system
prompt. Reports 40–41 repeat the broader workers group after the trace fix.

Evidence is stored in `/private/tmp/ai-config-audit-20260912/`, including a
non-secret configuration baseline, report JSON, redacted HTTP traces, UI captures,
logcat and build logs. Reports remain on the emulator.


## Settled results

- **41 reports**, **206 current primary answers**, all in SUCCESS state.
- **33 provider/model combinations across 19 providers**. This is the tested set,
  not all 4,106 catalog entries or all 91 configured providers.
- **211 primary calls**, including regeneration; **80 successful secondary calls**
  (50 Meta/Fan Out/Fan In and 30 Tournament judgments); **6 metadata calls**.
- **82 saved secondary rows**: 80 successful call results, one Tournament aggregate,
  and one deliberately retained pre-fix fallback error. No unfinished rows remain.
- **297 unique cost entries and 297 unique complete request traces**. Every report's
  total matches its call ledger. Total recorded cost: **$0.120205239** (about 12.02¢).
- Captured primary settings and wire fields were checked for 201 current rows;
  reports 34–35 were checked separately against their edit/replay history. The
  before-fix failures are retained as explicit observations, not counted as
  successful configuration behavior.

No app crash, ANR or out-of-memory error was found in the captured logcat.
The managed thread dump was requested for diagnosis; it was not a reported ANR.
Final trace retention contains 3,975 retained model-test files (11,139,892 bytes)
and 1,334 ordinary files (8,394,835 bytes), within unchanged budgets. The entire
retained model-test set is unchanged from the audit baseline.

## Findings and retests

| Issue | Evidence and correction |
|---|---|
| Secondary Flock/Swarm settings disappeared | Both initial Meta runs on report 05 saved no temperature or system prompt. Their answers echoed a marker from source data, masking the defect. Group references now carry their defaults through expansion and freezing. |
| Embedded preset text beat the named report system prompt | Report 06 requested the report prompt but dispatched `AUDIT_EMBEDDED`. Report 11 dispatches `AUDIT_REPORT` while preserving temperature 0.77. The explicit report prompt is applied last to the report overlay. |
| Plain external requests dropped their system extra | Report 07 returned UNSET with no saved system prompt. Report 12 carries `AUDIT_EXTERNAL`; report 13 has no external prompt. Prefill now preserves the supplied system extra and clears stale external options. |
| Completed report totals/status stayed stale | Report 05 showed zero primary cost and pending metadata despite completed, costed rows on disk. Runtime state now observes the report-scoped storage version. Report 11 updated to its final cost and completed status immediately after the primary call. |
| Preset summaries hid merge order | Selecting report→agent and agent→report produced different temperatures (0.11 versus 0.44) while summaries used catalog order. Summaries now use selected order, including prompt replay. |
| Worker names could collide after saving | Agent/Flock/Swarm forms compared untrimmed input but saved trimmed names. Validation now normalizes both input and existing names before enabling Save. Flock and Swarm fields also explain the blocked save, matching Agents. |
| Runtime secondary selections were frozen too late | `freezeWorkers` previously ignored runtime preset/system selections, then the dispatcher preferred that frozen value. It now resolves the run selections before freezing. Meta also exposes run-only parameter and system-prompt pickers. |
| Temperature field was silently ignored | The shared run screen offered temperature for Compare, Judges, translation review, Fan-in and Translate, whose handlers discarded it. Only Tournament now exposes this field; other operations retain their prompt/worker preset controls. Invalid Tournament values show the permitted numeric range. |
| Same-model fallback reused the failed worker's settings | The third fallback probe (`30d63573-d32e-44b5-bafa-4005be4dcff1`) failed even though the second Agent was valid. A saved worker attempt is now reused only when parameters, endpoint, credential reference and prompt also match. |
| Question edits erased group configuration | Report 34 began at 0.33 / `AUDIT_SWARM`; editing 17+25 to 17+26 and regenerating dispatched no temperature/system prompt. Question edits now stage a prompt refresh while retaining captured settings and endpoint. |
| Saved-report parameter/system edits did not persist | Report 34's edit screen selected the row presets, but the saved report still had an empty preset list and no system-prompt ID; regenerating reused the old configuration. Edits now persist as the next attempt's configuration. Reopening seeds the controls from the report. Previous answer evidence remains unchanged until the new attempt starts. |
| Fan-in lost its parent and combined unrelated inputs | Choosing `Audit combine` after `Audit inspect` produced a saved result absent from the parent run. The input builder also selected rows across all fan-out prompts and dropped explicitly enabled self-responses. New fan-in rows retain the parent prompt in `metaPromptId` and the combine template in `fanInOf`; launch, replay and hydration use those identities, scope input by parent/language, preserve self-responses when present, and refresh the completed combined row. |
| Trace cleanup blocked completed requests | Report 27 took 296.516 seconds. A managed thread dump shows multiple completed streams waiting in `ApiTracer.saveTrace`, while the lock holder scans file sizes in `pruneTraceDirLocked`. Retention now keeps a size/timestamp index updated by writes and deletions, and computes the retained-run prefix once per pass. Count/byte budgets remain unchanged. Report 40 repeats all 19 providers successfully in 112.826 seconds, versus 296.516 before the fix (62% less elapsed time in these runs; not a controlled network benchmark). The warm-process report 41 also passes all 19 providers in 75.409 seconds. No subsequent CapsWatch stall was observed. |
| Added/background groups skipped source-aware resolution | Source review found these callers omitted selected group information. Added rows now freeze their chosen group's configuration before regeneration; background reports use and freeze their explicit Swarm, independent of foreground external-intent state. |

Post-fix Meta results verify Swarm (0.33 / SWARM), bare Flock (0.22 / FLOCK),
Flock member Agent (0.11 / FLOCK), internal-prompt override (0.66 / SECONDARY),
and runtime override over both prompt and worker (0.55 / ROW). All five final
same-model fallback probes succeeded. Probe 4 (`12d5d6e9-c1f8-4b6b-9260-7f934abb7fc6`)
exercised the rejected first worker (application log at 19:31:40), then completed
with the healthy Agent's own configuration. A fresh Meta runtime override also
saved and dispatched temperature 0.55, cap 896 and ROW. The final Fan Out
matrix completes all 30 non-self pairs across six models with those runtime
controls, verified in every saved execution and wire system field. Its UI shows
30 done, zero error/running/queued, and a cost matching the settled rows.
The final Fan In probes preserve separate parent identities: `response` combines
one source with two responses (including its self-response), while `Audit inspect`
combines six sources with 30 responses. Both combined results appear immediately
under the correct parent with the correct combine-template name. The parent
association and combined row were also verified after the final APK restart. The pre-fix
unlinked result is retained as historical evidence; no parent identity was invented
for legacy data. Source-language isolation was verified in code, not with a new
translated matrix.

Tournament completes 30 judgments plus its aggregate. Every wire request uses
run temperature 0.4, cap 640 and SWARM; the saved run manifest retains both the
0.4 override and the worker's original 0.33 configuration. Tournament uses its
run manifest for this evidence, rather than a per-match `executionConfig` field. Entering 2.1 shows the numeric
range and disables Run; this validation probe made no API call.

Report 35 passes question editing (43 with the original Swarm settings),
parameter/system editing (0.55 / ROW), app restart with pending settings, and
clearing those settings (restores 0.33 / SWARM). The four attempts on its original row remain separate
costed calls, with three prior answers retained in history.
Report 35 also adds a three-Agent Flock without rerunning its existing answer.
The new Claude and DeepSeek rows use Agent temperature 0.11 with the selected
Flock system prompt; the bare OpenAI row uses Flock temperature 0.55.
Reports 17–19 preserve three named Agents sharing one model, and a named Agent
alongside a Swarm member in either selection order.
Reports 20–23 pass overlapping Flock/Swarm selection in both orders. Reports
29–32 pass preset order, embedded/named system conflicts on six providers, and
temperature 1. Report 33 settles seven primary/metadata calls, including its
short and long titles, report/model icons, model title and language detection.
Reports 37–39 exercise a row preset over an Agent, one row in a six-model Swarm,
and a report preset overriding that row.

Agent, Flock and Swarm forms reject padded, case-variant duplicate names, show
“Name already exists”, and disable Create. Each draft was discarded. The final
settings comparison after APK restart matches the configuration captured before
these probes.

All pre-existing Agents, Flocks, Swarms, presets, system prompts and internal
prompts match the sanitized baseline. Empty-list serialization for new worker
fields and stop sequences is normalized for that comparison. The 34 new
configuration records are additive and have no credentials. Fresh-install
configuration comes from the same repository seed; this audit did not wipe the
existing emulator data.


## Validation boundaries

The default repository cycle was used: debug build, emulator installation, cloud
APK copy, foreground launch and source review. The final build and cloud APK
share SHA-256 `033fadb24c433286963a3486f0a1c12b47807c7a4c72068975223d2a4559d25e`. No unit or instrumented suite,
app-data restore, wipe or fresh-install test was performed. The background
submission path was checked in source; adding a Flock to a saved report was
exercised live. Legacy reports without captured lower-level parameters cannot
reconstruct an unknown historical group when clearing overrides.

## Model behavior versus app defects

The added OpenAI Agent on report 35 returned `UNSET` once even though the wire
request contained the correct `AUDIT_ROW` system instruction and temperature
0.55. The streamed response also contains UNSET, so this is model instruction
noncompliance, not a storage or dispatch mismatch. Its trace is
`api.openai.com_20260912_183625_308_4pd9_0e03db28.json`. Claude also wrapped a
prompt-requested JSON answer in a code fence; that run did not enable a native
JSON mode. These outputs are retained as evidence rather than rewritten.


## Observed request syntax

Report 14 applies the same report preset (temperature 0.44, output cap 768)
to six models. Every call succeeded, with these actual request fields:

| Tested API / models | Temperature | Output cap | System prompt |
|---|---|---|---|
| OpenAI Responses / gpt-5.4-mini | `temperature` | `max_output_tokens` | `instructions` |
| Anthropic / claude-sonnet-4-6 | `temperature` | `max_tokens` | `system` |
| DeepSeek, Mistral, xAI Chat | `temperature` | `max_tokens` | system entry in `messages` |
| Google / gemini-2.5-flash | `generationConfig.temperature` | `generationConfig.maxOutputTokens` | `systemInstruction` |

These are observations from captured requests, not a claim that every model
supports every control or value. The deliberate OpenAI sampling-plus-reasoning
fixture is rejected locally; the application must preserve that error instead
of silently discarding a requested setting.


## Report inventory

Numbers refer to the original audit titles; report 33 generated the title “17 plus 25”.

| # | Scenario | Current answers | Report ID |
|---:|---|---:|---|
| 1 | agent | 1 | `4f512353-5e37-46cb-a50d-27d6b93d69b0` |
| 2 | flock bare | 1 | `dad4f9b9-c2f5-4a11-bef6-2f9bfa2f2027` |
| 3 | swarm mini | 1 | `fa0ff5a8-7963-481b-bf46-28446a3465b9` |
| 4 | flock six | 6 | `75f9ada1-5a1a-4507-9048-0801a24bf20c` |
| 5 | swarm six | 6 | `9202eee4-3166-42e3-b6dc-1eeb5027d99c` |
| 6 | embedded conflict | 1 | `3057b3e1-228d-40ce-acb8-f652530315ef` |
| 7 | external bare | 1 | `dacb4631-8c32-4710-b6ee-aa856940c1cf` |
| 8 | preset order report agent | 1 | `b2e1d245-ee55-4832-a097-b5a2d7925f9b` |
| 9 | preset order agent report | 1 | `d17846d8-b3ac-4be1-b4a1-f38deb815044` |
| 10 | embedded alone | 1 | `e2b51d34-37d5-4a69-b101-f1347846a102` |
| 11 | fixed embedded conflict | 1 | `e7feebf0-b660-40d7-873f-ac7e022abc7c` |
| 12 | fixed external bare | 1 | `c7dd4dd5-645a-4446-9812-056fb47c17f8` |
| 13 | external cleared | 1 | `5da2ca1b-5cbf-4883-afe1-e8a53a23fd52` |
| 14 | flock report parameters | 6 | `788d6987-5d77-4c81-bf2d-6c250fdd28d6` |
| 15 | swarm report system | 6 | `c6fc949b-ad1a-4f3e-83a8-97317c24daa8` |
| 16 | flock report both | 6 | `e8480cc1-45b7-4580-a831-4d8cf755a3fd` |
| 17 | three named twins | 3 | `1230293f-3b85-4f44-8a28-36a8b4700a59` |
| 18 | agent and swarm | 2 | `97ad7fd5-74d4-4ef7-b43e-6e1cad93e1c5` |
| 19 | swarm and agent | 2 | `c4162fde-1dbc-4729-86ce-2331fcc66ece` |
| 20 | overlapping flocks forward | 7 | `ebf02302-ebf2-47ec-bc56-8b9b2364e2ed` |
| 21 | overlapping flocks reverse | 7 | `c3652bdc-de0c-4cf2-ba2d-b9a548826e66` |
| 22 | overlapping swarms forward | 6 | `db87d629-56e4-4e14-8f40-7dcc8892fb1d` |
| 23 | overlapping swarms reverse | 6 | `f7c73762-4ce8-4e83-83ea-35481b22d03b` |
| 24 | Level 1 | 6 | `7b7f64e1-c282-485d-8d1d-9848640059e5` |
| 25 | Level 2 | 6 | `ae916342-3189-4b01-97b2-b569ddf11069` |
| 26 | Level 3 | 6 | `d0e41c1d-d378-494a-bbaa-27f98045780b` |
| 27 | workers nineteen | 19 | `742c2cd9-fb51-49e9-a6db-c2994a4bc30f` |
| 28 | all three levels system | 18 | `ace600fc-8c57-4bef-8690-aa098623134f` |
| 29 | preset order report agent fixed | 1 | `6a67dc06-5867-4a47-8d23-11c8d41c76ab` |
| 30 | preset order agent report fixed | 1 | `2ba49696-f923-44e5-9e0b-3c7e1ccb9ed2` |
| 31 | six embedded conflict | 6 | `4d343060-7055-4f49-bb03-1d3ef4a19d44` |
| 32 | six high temperature | 6 | `8d1f3aae-5c15-482f-896e-8286c6f7978f` |
| 33 | metadata enabled | 1 | `aa285b9a-ba03-4ca3-ba00-cee4bcb18356` |
| 34 | replay before fix | 1 | `812241e8-d643-4629-acd5-2274df2740f2` |
| 35 | replay after fix | 4 | `0dd53bc0-ce3d-406e-81d0-6d0d2c32750b` |
| 36 | secondary matrix | 6 | `e663ed9e-3986-4865-b9f8-e60fdbf79e09` |
| 37 | row over agent | 1 | `c6ce46dc-4338-4c9e-b98b-ec65dcb89589` |
| 38 | one row in swarm | 6 | `5cdda8a3-5641-4c17-9d7e-2d3181e6790a` |
| 39 | report over row | 6 | `87ba0433-b118-4007-bc6f-fc7c4209d446` |
| 40 | workers retention retest | 19 | `8a45b6d9-efdd-458a-a2cd-ffffa88e9bef` |
| 41 | workers warm retention retest | 19 | `39dfd465-4d0b-49f0-87b2-7eef685d0e14` |

## Tested provider/model combinations

| Provider | Models |
|---|---|
| Amazon | `openai.gpt-oss-20b` |
| Anthropic | `claude-haiku-4-5-20251001`, `claude-opus-4-7`, `claude-sonnet-4-6` |
| Cerebras | `gpt-oss-120b` |
| Cohere | `command-r7b-12-2024` |
| DeepInfra | `google/gemma-3-12b-it` |
| DeepSeek | `deepseek-chat`, `deepseek-v4-flash`, `deepseek-v4-pro` |
| Glama | `google/gemini-2.5-flash-lite` |
| Google | `gemini-2.5-flash`, `gemini-2.5-flash-lite`, `gemini-3.1-pro-preview` |
| Groq | `openai/gpt-oss-20b` |
| Mistral | `ministral-14b-latest`, `mistral-large-latest`, `mistral-medium-latest`, `mistral-small-latest` |
| NebiusAIStudio | `Qwen/Qwen3-30B-A3B-Instruct-2507` |
| Novita.ai | `meta-llama/llama-3.1-8b-instruct` |
| OpenAI | `gpt-4o-mini`, `gpt-5.4-mini`, `gpt-5.4-nano`, `gpt-5.5` |
| OpenRouter | `ibm-granite/granite-4.0-h-micro` |
| Parasail | `google/gemma-3-4b-it` |
| Requesty | `openai-responses/gpt-4.1-nano` |
| Together | `openai/gpt-oss-20b` |
| VercelAIGateway | `mistral/ministral-3b` |
| xAI | `grok-4-1-fast-non-reasoning`, `grok-4.20-0309-reasoning`, `grok-4.3` |
