# Secondary replay audit — 12 September 2026

This continues the configuration audit in `configuration-audit-2026-09-12.md`.
The earlier 41-report audit is retained as its own completed snapshot. This round
focuses on variations, applying candidates, source edits and model switching.
Evidence is in `/private/tmp/ai-replay-audit-20260912/`.

## Reproductions and corrections

| Finding | Before | Correction |
|---|---|---|
| Meta/Fan In variation lost captured worker settings | Three temperature variants of report 36's combined result sent only temperature; the original SWARM system prompt and 640-token cap disappeared. | Replay starts from the stored execution settings and endpoint, changing only the requested controls. |
| Variations rebuilt current inputs and templates | Meta and Fan Out replay builders consulted current report answers, translations and prompt definitions despite having a saved request. | Replay uses captured request text. Fan Out Reload also uses the saved prompt directly rather than requiring the current source body to be non-empty. Rows without captured settings show an explicit unavailable message instead of silently substituting current inputs. |
| Applying a candidate retained the old request evidence | Selecting temperature 0 changed the content and displayed change marker, but kept temperature 0.33 and the original trace in storage. | A generated preview carries its execution, trace, tokens, cost and duration through Apply. The previous spend stays recorded. Fan Out refreshes the saved response and evidence in its live state. |
| Switching models retained the previous execution configuration | The switch updated model and trace but did not replace `executionConfig`; subsequent Reload could use the previous model's settings. Agent selection also discarded the credential/endpoint identity. | The selected Agent supplies its own settings and live credential reference; a raw-model selection retains captured parameters. Apply saves the new execution. Dedicated rerank/moderation switches use historical source/translation data, preserve the selected credential reference and record their native endpoint without generation controls. |
| Cleared change values displayed an older temperature | After applying an edited prompt over a temperature-0 result, the final screenshot showed `Changed by Edit: 0` although storage correctly cleared the value. | Meta and Fan Out read the latest row as a whole; null fields no longer fall back to the old row. |
| Fan Out Apply stayed on a synthetic running preview | After selecting a completed temperature candidate, the answer was saved but the screen stayed on queued / Running indefinitely. | Temperature, reasoning and web-search Apply now close the overlay and clear the preview job, matching Meta and prompt-edit replay. |

Temperature-only and prompt-edit replays also stop removing unrelated reasoning
or web-search controls based on cached capability flags. Shared request validation
reports unsupported combinations explicitly.

## Verification

The pre-fix temperature reproduction made three calls. After the correction,
three variants (0, 1 and 2) all retained SWARM and cap 640. Applying temperature 0
saved its new trace and temperature, and returned the SWARM marker.

Report 42 (`971b7733-11cb-4816-89b1-23632fd79bf1`) tests historical inputs.
Its primary answers changed from 42 to 43 after editing 17 + 25 to 17 + 26.
Meta prompt replay retained `ORIGINAL_RUBRIC`, the old question and both old
answers. Its run-only SECONDARY system prompt, temperature 0.66 and 1,024-token
cap remained on the wire. The report-wide regeneration also retried Meta against
those captured inputs. Switching Meta to Audit A Claude applied the selected
Agent's 0.11 temperature, 384-token cap, AGENT system prompt, Anthropic endpoint
and credential reference. Reload retained that configuration and answered 42;
the screen explicitly showed historical inputs with 0 of 2 current answers covered.

Adding the high-reasoning preset to that Claude replay produced an actionable
configuration error: temperature conflicts with thinking and the 384-token cap
is below the 16,384 thinking budget. No new request or cost entry was created;
the selected secondary row was unchanged.

The OpenAI Fan Out pair over Claude's answer
(`baed3846-c44f-45e1-9019-07b05ff57335`, report 36) retained its captured prompt in
the editor. A run-only edit requesting answer 99 retained ROW, temperature 0.55
and cap 896. Apply saved its new prompt, trace and answer and immediately showed
the new cost. Three subsequent temperature variants (0, 1, 2) retained the edited
prompt, ROW and cap 896. Their Apply exposed the additional overlay defect above.
After that fix, a fresh three-candidate comparison returned directly to the pair
screen on Apply. Reload then sent the selected temperature 0, cap 896, ROW and
edited answer-99 prompt; disk and UI both showed the new result. The matrix
settled at 30 done, zero errors/running/queued, and reflected the updated cost.

A final Fan In prompt edit requesting answer 77 retained SWARM, temperature 0
and cap 640. Apply changed the combined result's cost on its parent matrix from
0.1030¢ to 0.0135¢ immediately. Reopening it showed the newly selected answer 77.
The final screenshot also caught the stale change-value badge listed above; the
persisted row correctly contained only `responseChangeSource=Edit`. After the
final build/install, the reopened screen showed `Changed by Edit` and answer 77.
The app was foreground and the saved 92/9 call counts were unchanged.
The fixed Fan In row is `9b48c4a5-35df-48ca-b187-e6b4ef836712` in report 36.

### Settled accounting and deployment

- **24 additional live calls**, including the three pre-fix reproductions,
  with **24 unique ledger entries and 24 complete HTTP-200 traces**. The negative
  Claude configuration check added no request or cost entry.
- Additional recorded cost: **$0.01025655** (about **1.03¢**).
- Report 36: **92 lifetime calls**, 6 primary rows and 72 secondary rows;
  **$0.04882309** total. Report 42: **9 lifetime calls**, 2 primary rows and
  1 secondary row; **$0.00305055** total. Both totals exactly match their ledgers;
  all rows are terminal and there are no secondary errors in these two reports.
- Trace retention remained within both budgets: 3,975 protected model-test traces
  / 11,139,892 bytes; 1,358 ordinary traces / 8,551,356 bytes.
- Debug build succeeded. Built, emulator-installed and cloud APK SHA-256 all
  match `669b15c5a754f34f471467d74144f3180d01c8f8c0011f423176659edd452c6d`.
- Live log review found no app crash, ANR or out-of-memory event during this round.

The earlier 41-report snapshot is unchanged. This round adds report 42 and
extends report 36. Detailed runtime evidence, redacted request traces, screenshots
and reconciliations remain in the local evidence directory above.

### Coverage boundaries

Dedicated native model switching, web-search/reasoning Apply, missing credential
and missing-source edge paths were reviewed in source and compiled; they were
not separately exercised through paid live requests in this round. This audit
does not establish parameter support for every provider/model or guarantee model
output quality. Some earlier model checks made contradictory arithmetic claims;
those generated responses remain evidence rather than being rewritten by the app.

No API keys or user-generated reports are included as application defaults.
The default build/deploy cycle is used; no unit or instrumented suite, data wipe,
snapshot or restore is requested or performed.
