# Parameter audit — 12 September 2026

The same preset is not portable to every provider or model. This audit compares
requested report parameters with captured HTTP bodies and saved outcomes. An
accepted request proves transport compatibility, not that an undocumented field
changed the model's sampling or that a seed makes answers reproducible.

## Scope and method

The app's Level 1–3 swarms contain 18 distinct models from six providers. Temperature
was exercised across those levels. Individual top-p, top-k, seed, penalties, JSON,
output caps, reasoning and stop-sequence reports use the six Level 2 models.
These are representative configured models, not all models in the 91-provider catalog.

Reports were created through New Report, selecting swarms and named parameter
presets. After the first two reproduction reports, metadata was disabled per report
to isolate the primary requests. The audit preserves generated reports on the
emulator; no reports, API keys or personal configuration are bundled in the app.

Raw evidence is under `/private/tmp/ai-parameter-audit-20260912/`: report JSON,
redacted API traces, `summary.json`, logcat and build output. Report IDs and final
counts appear below. Temperature comparisons use the same small JSON prompt;
the token-cap probe deliberately asks for 100 animals, and the stop probe asks
for `START END AFTER` with `END` as the stop sequence.

## Confirmed bugs and fixes

1. A cached supported-parameter list removed temperature/top-p from GPT-5.4-mini,
   and the Responses request class omitted them anyway. The combined-control
   reproduction returned success after losing the requested settings. Requested
   report values now survive into the execution snapshot and dispatch; known
   incompatibilities produce explicit configuration errors. Temperature sweeps
   also stopped using that stale catalog as an authoritative rejection gate.
2. JSON mode was absent from OpenAI Responses and Gemini requests. It now maps to
   `text.format.type=json_object` and `generationConfig.responseMimeType=application/json`.
3. Invalid numeric preset input silently became unset, or was silently clamped
   later. The editor now blocks invalid/non-finite numbers and invalid ranges;
   report dispatch rejects incompatible values rather than changing an experiment.
   The stop-sequence field is now editable. New presets retain the normal
   return-citations default.
4. Known unsupported controls and conflicts were either dropped or sent to fail
   at the provider. Preflight errors now identify the model and conflicting controls
   without an HTTP request or retry. The original reproduction confirmed Anthropic's
   rejection of seed and Mistral's rejection of top-k. Unknown gateway restrictions
   still reach the provider; catalog absence is not proof of incompatibility.
5. Claude and Gemini token-limit endings could appear successful. Both streaming
   and non-streaming report paths now mark them truncated while preserving partial
   content and reported usage. A conflicting explicit Claude output cap is not raised
   to accommodate the thinking budget.
6. The OpenAI-compatible completion validator overwrote a streaming failure with
   a generic missing-answer error. It now preserves the original error and prevents
   a paid automatic fallback. The two observed incomplete Grok streams already
   avoided retry before this fix; their defect was the lost diagnostic detail.
7. The preset picker calculated page capacity without row gaps and enough space
   for details, clipping a final row beneath the bottom bar. Its page calculation
   now reserves those dimensions, and the picker displays all configured controls.

8. Grok 4.3 was missing from the provider's controllable-reasoning allowlist.
   The bundled provider definition now includes it and its alias. Mistral Medium's
   low/medium effort choices are rejected explicitly; its supported high mode has
   a separate example preset. This avoids treating one provider's effort levels
   as universal. Runtime configuration is checked against the updated defaults.
9. The saved-preset list called JSON-only and stop-only presets `0 set`, because
   its count omitted those controls. The list now derives its count from the same
   fields as the detail view, including search recency and disabled citations.

Twelve optional `Audit` presets are seeded once from `ParameterPresets.kt`. They
are not selected automatically. Existing names, edits and deletions are preserved.

## Syntax and model restrictions

| Control | OpenAI Responses | Anthropic Messages | Gemini | Mistral Chat |
|---|---|---|---|---|
| Temperature | `temperature` | `temperature`, 0–1 | `generationConfig.temperature`, 0–2 | `temperature` |
| Top-p | `top_p` | `top_p` | `generationConfig.topP` | `top_p` |
| Top-k | Not exposed | `top_k` on supporting models | `generationConfig.topK` on supporting models | Rejected by tested model |
| Output limit | `max_output_tokens` | `max_tokens` required | `generationConfig.maxOutputTokens` | `max_tokens` |
| Seed | Not exposed | Not supported | `generationConfig.seed` | `random_seed` |
| Stop strings | Not exposed | `stop_sequences` | `generationConfig.stopSequences` | `stop` |
| JSON object mode | `text.format.type` | Requires a schema-based structured-output feature; this app's JSON toggle does not implement it | `generationConfig.responseMimeType` | `response_format.type` |
| Reasoning | `reasoning.effort` | Tested Sonnet: `thinking.budget_tokens`; adaptive models use a different shape | `generationConfig.thinkingConfig` with a model-specific budget or level | `reasoning_effort` |

GPT-5.4 sampling controls require reasoning effort `none`; higher reasoning modes
reject those controls. GPT-5.4-mini lists `none` as its default reasoning mode.
[OpenAI compatibility guide](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-5.4),
[GPT-5.4-mini](https://developers.openai.com/api/docs/models/gpt-5.4-mini).

Anthropic's later models remove adjustable sampling, allowing only specified
compatibility defaults. Temperature zero is not fully deterministic. Its API has
no seed field. [Anthropic Messages reference](https://platform.claude.com/docs/en/api/messages/create).

Gemini's top-k support and defaults depend on model metadata. Its JSON MIME type
and generation settings are nested and use camelCase.
[Gemini API reference](https://ai.google.dev/api/generate-content).

DeepSeek documents temperature, top-p and repetition penalties as having no
effect in thinking mode. Accepting an HTTP request is therefore insufficient
proof of effective support. [DeepSeek thinking mode](https://api-docs.deepseek.com/guides/thinking_mode/).

Mistral recommends changing temperature or top-p individually. Defaults vary by
model and seed is named `random_seed`.
[Mistral Chat reference](https://docs.mistral.ai/api/endpoint/chat).

Grok 4.3 advertises configurable reasoning, including low/medium/high.
[Grok 4.3 reference](https://docs.x.ai/developers/models/grok-4.3).
Mistral Medium's live error response explicitly enumerated only `none` and `high`.

The successful low/high reports demonstrate that these labels are translations,
not equal compute budgets: the app sent 1,024/16,384 thinking tokens to tested
Claude and Gemini models, and literal effort strings to OpenAI, Mistral and xAI.
The editor's `None` choice leaves effort unset; it does not explicitly disable
a model's default reasoning mode.

## Results and validation

19 reports contain 150 primary model results: 97 succeeded and 53 show errors.
Those errors include 37 preflight configuration rejections, six deliberate
truncations, and ten provider/stream failures captured during reproduction.
This is a compatibility matrix with deliberate negative cases, not a model
quality ranking or a 65% app pass rate. All reports have a completion timestamp
and no pending/running primary rows.

| Report | Success / error | Saved report ID |
|---|---:|---|
| Baseline | 6 / 0 | `0bf7ecf9-ae64-40ef-a914-3c3d6fc8ea87` |
| Combined controls, reproduction | 4 / 2 | `47c0ac3f-ee42-4831-9cd5-eb0309703aea` |
| Temperature 0, levels 1–3 | 14 / 4 | `8a0b50c5-9c4e-48ab-b91a-eee08dc45911` |
| Temperature 1, first run | 13 / 5 | `10990fcd-5df6-4292-a98b-2b7c6f615672` |
| Top P 0.8 | 6 / 0 | `af5ebcc7-a012-489f-9e93-aa1aba33dd46` |
| Top K 20 | 3 / 3 | `83f843d4-6a4e-4fca-992f-612b9addd95d` |
| Seed 42 | 3 / 3 | `8302a860-58de-4f77-9acd-073a8f8585c4` |
| JSON object mode | 5 / 1 | `04a1879f-b1cd-4f23-b640-639eaafcda25` |
| Penalties 0.5, reproduction | 2 / 4 | `00edffef-b4a1-4ec9-8d18-e40ce607283f` |
| Max tokens 64, deliberate truncation | 0 / 6 | `05e33aa4-b3ad-41b5-9081-99016e4c43ad` |
| Reasoning low, reproduction | 3 / 3 | `5b599352-e23a-4be1-96b1-c4fc1cfc6200` |
| Temperature 0 + reasoning low | 1 / 5 | `1a79e03f-6c45-42ab-a151-f24e72263362` |
| Stop END, trigger probe | 4 / 2 | `e867f0f2-8409-42b9-b20e-680896fef542` |
| Combined controls, after fix | 2 / 4 | `da609aa3-8257-42e4-8e5c-f6d5c577cbf2` |
| Temperature 1, repeat | 16 / 2 | `8423ad2a-6ef8-4d2d-bd83-5187685fe804` |
| Reasoning low, final | 4 / 2 | `3cf56c17-383a-4dce-a234-c2c65f7dba7f` |
| Reasoning high, final | 5 / 1 | `ea0e95a1-58f9-4124-a053-0ed68538c5d5` |
| Penalties 0.5, final | 2 / 4 | `e4960506-1e6f-4ec1-bb65-1709722db6a5` |
| Stop END, final compatibility check | 4 / 2 | `cbf288a5-f340-4899-9f5c-48f82f167f64` |

The final Level 2 compatibility results are:

| Preset | Anthropic Sonnet 4.6 | DeepSeek Chat | Mistral Medium | GPT-5.4-mini | Grok 4.3 | Gemini 2.5 Flash |
|---|---|---|---|---|---|---|
| Temperature 0 / 1 | Accept | Accept | Accept | Accept | Accept | Accept |
| Top P 0.8 | Accept | Accept | Accept | Accept | Accept | Accept |
| Top K 20 | Accept | Reject | Reject | Reject | Accept* | Accept |
| Seed 42 | Reject | Reject | Accept | Reject | Accept | Accept |
| JSON mode | App feature gap | Accept | Accept | Accept | Accept | Accept |
| Both penalties 0.5 | Reject | Accept | Accept | Reject | Presence rejected | Reject |
| Reasoning low | Accept | Reject | Reject | Accept | Accept | Accept |
| Reasoning high | Accept | Reject | Accept | Accept | Accept | Accept |
| Stop END | Accept | Accept | Accept | Reject | Reject | Accept |

“Accept” means the requested field reached the provider and the report completed.
It does not establish repeatability or equal behavior. *Grok accepted top-k, but
this audit did not establish that the undocumented field affects sampling.
For stop behavior, all four supporting models returned exactly `START` in the
trigger probe, omitting `END AFTER`. All five successful JSON-mode outputs parsed
as JSON. The combined preset after the fix retained all five numeric controls in
the two successful Grok/Gemini request bodies; the other four rows failed explicitly.

Across levels 1–3, the temperature-one repeat completed on 16 models. The two
DeepSeek V4 models report that sampling settings have no effect in their default
thinking mode. Claude Opus 4.7 and GPT-5.5 accepted the fixed default value 1;
that does not imply adjustable temperature support. GPT-5.5 rejected zero live.
An initially too-strict Opus compatibility check was corrected before the repeat.
Two Grok streams in the first temperature-one run ended without a final answer
or usage; no automatic retry occurred. Both succeeded on the repeat. A long wall
clock gap affected the original run, so it is not attributed to temperature or
provider latency alone.

The token-cap report recorded exactly six calls. All six errors retained partial
answers and reported usage, including Gemini's three visible output tokens plus
57 reasoning tokens. No truncated call was automatically retried. Preflight
rejections in the final reasoning/penalty/stop reports have no HTTP trace or paid
call. Costs include metadata calls from the first two reports and any explicit
sweep attempts; they use the app ledger and are not a provider invoice.

Validation follows the repository's default cycle: debug build, replacement
install, cloud APK copy and foreground launch. No unit/instrumented suite or
app-data wipe was performed. Fresh-install examples are verified from their
repository seed; the existing installation is checked across process restart.

The final GPT-5.4-mini temperature sweep added three successful candidates at
0, 1 and 2, preserving the report's existing top P 0.8. All three wire bodies
contained the requested temperature. The original result was retained; sweep
candidates were inspected without applying a replacement. Their three calls
remain in the report ledger. The audit ledger totals **132 calls and
$0.119056655**, including initial metadata and this explicit sweep.

Manual editor checks passed: `NaN` and temperature `2.1` disable Save; `0,2`
is accepted; a duplicate name with trailing whitespace disables Save. Clear
restored the original preset, and no temporary editor value was saved. The
installed list now labels JSON and stop presets `1 set`. The report picker was
exercised across both pages during report creation.

After the final replacement install, all 12 presets matched the pre-restart
values, with no duplicate names. The Grok reasoning allowlist matched the bundled
provider JSON. The emulator remained booted with `com.ai/.MainActivity` in the
foreground. No app crash or ANR was found in the captured logcat. The debug build
succeeded; the built APK and cloud-copy SHA-256 hashes matched:
`ff7f699e4b1bbbbef9fc6a342c578ded13c52df23ab2af57dd8388f3a4621477`.

Limits: this audit did not test every catalog model, every value/boundary, seed
repeatability, or every secondary/chat/non-streaming path independently. Shared
builders and completion guards were reviewed for those paths. Native structured
output schemas and provider-specific search tools remain separate features.
