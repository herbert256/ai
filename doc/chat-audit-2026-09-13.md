# Chat and Dual Chat audit — 13 September 2026

This audit concentrated on conversation correctness, generation controls, interruption recovery, and accounting. It used the installed Android app, saved chat JSON, API request/response traces, and emulator diagnostics. Existing report generation and Statistics had been covered in preceding audits; this pass selected the less-tested chat paths.

## Confirmed defects and corrections

| Area | Failure found | Correction |
| --- | --- | --- |
| Native provider endpoints | An Anthropic agent whose endpoint already ended in `/v1/messages` called `/v1/messages/v1/messages` and failed with HTTP 404. Google full endpoints had the same routing problem. | Native chat calls accept the resolved complete URL and select the correct streaming or non-streaming endpoint. |
| Generation controls | Chat conversions dropped seed, stop sequences, JSON mode, and some reasoning settings; Mistral did not use the common seed alias mapping. | One complete preset-to-chat conversion and shared request builders now serve agent, configured, resumed, Dual Chat, and refinement paths. |
| Unsupported controls | Some values were silently omitted or accepted until a paid request failed. | Shared numeric and endpoint validation rejects known unsupported settings before dispatch. Unknown provider restrictions remain provider-validated. |
| Gemini thinking | Explicit `none` omitted `thinkingConfig`, leaving dynamic thinking enabled. One small-token-cap request returned an empty HTTP 200 stream with no usage. | Gemini 2.5 Flash sends `thinkingBudget: 0`; the UI distinguishes Default from None. Models that cannot disable thinking reject the setting. Empty streams explicitly report unavailable usage. |
| Web search override | Turning web search off could leave the agent preset's enabled value active. | Explicit per-turn false overrides the inherited true value and persists across reopening. |
| System prompts | Chat preset prompts could disappear, and clearing an explicit prompt could restore the preset prompt unexpectedly. | Preserve preset fallback while honoring an explicitly edited or cleared prompt. |
| Chat billing | Conversation text was re-estimated instead of using actual stream usage; chat-title calls were attributed to the conversation model. | Persist call-time usage, provider/model, category, trace, and price. Titles are charged to the title model. Historical estimates remain marked as estimates. |
| Truncation and failed output | Partial/native-limit responses could appear complete or lose billable usage. | Validate terminal events, preserve usage on failure, keep partial answers with a separate interruption reason, and avoid forwarding diagnostics as conversation content. |
| Reopened long partial replies | The stopped warning was below a long answer and initially off-screen. | Keep the latest interruption visible outside the scrolling conversation as well as on its saved message. |
| Saved chat timestamps | `createdAt` changed on subsequent saves. | Preserve creation time and use a separate monotonically increasing update timestamp. |
| Save ordering | Navigation could cancel queued saves; older snapshots could overwrite newer ones. | Writes use a storage-owned scope with update checks. Deleted sessions cannot be resurrected by queued writes. |
| Resume identity | Resumed agent chats lost their agent identity and resolved endpoint. | Save non-secret agent identity and endpoint; resolve the current key from that agent and reject a missing agent clearly. |
| Image history | Responses API image-bearing histories encoded assistant text as `input_text`. | Assistant history uses `output_text`; user text and image blocks retain their input types. |
| Dual Chat turn order | Stopping after model 1 and continuing repeated model 1. Later requests dropped the original subject prompt. | Resume the missing model's turn and retain the original prompt and conversation roles. Recreation pauses instead of automatically starting another paid loop. |
| Dual Chat cancellation billing | A completed Groq response was present in the trace but Stop prevented its usage from reaching the UI. | Recover usage from that exact completed trace; settle accounting before allowing resume. |
| RAG failures | A failed knowledge lookup silently proceeded without the requested knowledge context. | Show the retrieval failure and require retry or detachment; reject invalid generation controls before retrieval. |
| Local routing and cost | Local chat used the wrong synthetic provider identifier and lost parameter overrides; generic prices could imply cloud cost. | Use `AppService.LOCAL.id`, forward parameters, route Dual Chat through local inference, reject unsupported controls, and keep cloud cost zero. |
| Answer sanitation | Some chat paths could expose or forward provider reasoning as the final answer. | Filter streamed and saved assistant content; retain raw traces for diagnosis and billing. |
| Agent picker responsiveness | Price/capability work ran during main-thread row composition. | Precompute picker metadata on the IO dispatcher. See the ANR qualification below. |

## Live coverage

The 19 reproducible control definitions are in [chat-audit-fixtures-2026-09-13.json](chat-audit-fixtures-2026-09-13.json). The [result matrix](chat-audit-results-2026-09-13.json) includes the actual wire controls, HTTP attempt counts, interruption states, and ledger totals. These are test specifications and sanitized summaries, not bundled application defaults. Test conversations remain personal app data.

| Test | Observed result |
| --- | --- |
| OpenAI GPT-5.4-mini, Groq GPT-OSS-20B, Anthropic Haiku 4.5, Google Gemini 2.5 Flash | Multi-turn answers correctly recalled 25 as the number previously added to 17. Native endpoint retests completed cleanly. |
| OpenAI JSON | `text.format.type=json_object`; valid JSON result and actual usage. |
| Groq controls | Temperature, top P, seed, stop list, token cap, and JSON mode reached the request; completion and usage returned. |
| Google controls | Native camelCase parameters, JSON MIME type, seed and stop list reached the request. After the thinking fix, `thinkingBudget: 0` produced zero reported thought tokens. |
| Mistral controls | Seed uses `random_seed`; JSON result and provider usage returned. |
| xAI JSON | JSON result and provider usage returned. |
| OpenAI seed / Anthropic JSON / Replicate seed | Clear preflight rejection, with no HTTP call. |
| Missing knowledge base | Clear retrieval error, with no generation call. |
| Local web / Groq web / deleted agent | Explicit error retained on the saved user turn; no HTTP call. |
| Web search off | Saved override false and no search tool in the outgoing request. |
| Earlier image in conversation | The model recognized the blue image from the earlier user turn; the assistant history block used `output_text`. |
| OpenAI / Anthropic / Google output caps | Incomplete status visible; partial text and provider usage preserved. Google retest returned 61 input / 16 output / 0 thinking tokens. |
| Stop, force-stop, reopen, send again | Stopped status survived reopening; the next request returned `RECOVERED`. No diagnostic text was included in the outgoing conversation. |
| Stop during visible streaming | Preserved 7,728 characters with a separate stopped status and an estimated usage record. The prior exact call remained separate. |
| Dual Chat halfway through a round | After stopping with model 1's answer saved, resume added one DeepSeek/model-2 request and reached 2/2 rounds. No model-1 repeat; original prompts remained in request history. |
| Dual Chat screen recreation | Both an empty paused session and a paused session with three answers survived display-size changes. Neither recreation issued a new API request. |
| Numeric editor | Rejected `abc`, `NaN`, temperature 3, max tokens 0, and top P 1.2. Accepted temperature `0,7` as 0.7; a cleared inline system prompt stayed empty and the resulting chat returned `OK`. |

Provider acceptance proves that these specific requests worked. It does not prove identical randomness, determinism, or semantics across different models.

## Accounting and persistence evidence

The first fixed OpenAI conversation recorded 90 input / 15 output tokens, then a separate DeepSeek title call with 76 input / 4 output tokens, then 140 input / 15 output tokens. Creation time remained unchanged. The corresponding call costs were USD 0.000135, 0.00002296, and 0.0001725.

The Google thinking retest recorded 93 input / 11 output / 0 thinking tokens. The earlier request with the setting omitted recorded 56 input / 9 output / 51 thinking tokens. These are different conversation turns, so the token totals are evidence of the control reaching the provider, not a controlled performance comparison.

The audit captured 46 Chat, Chat-title and Dual-chat HTTP traces across seven cloud providers, including baseline failures and retests. Fourteen non-estimated call records in the 19-case matrix matched their provider response token counts exactly. The seven preflight/retrieval rejection fixtures made zero HTTP calls. Extra manual conversations exercised title attribution, creation timestamps, numeric entry, and recovery.

The final debug build succeeded and was installed without clearing app data. The emulator and cloud delivery use the same APK; local/cloud SHA-256 is `67135d0883c37f99ffc3c6594c17a5aebb2d6196213871d99c895b469a5cf023`. Original Dual Chat setup was restored. Test conversations and traces remain available on the emulator.

Final readback verified all 124 existing report files byte-for-byte unchanged. The stopped chat retained identical messages, call prices, and creation time after reinstall/reopen; its warning was immediately visible. The app was confirmed in the foreground. Global recorded usage increased from 4,782 to 4,820 calls and from USD 1.824683814123 to USD 1.843066609123: an audit increment of USD 0.018382795, including estimated interrupted usage. This is the app's recorded total, not an independently verified provider invoice.

The crash buffer contained no `com.ai` crash from this pass. Two audit-harness UI automation failures and an older Google text-to-speech crash were distinguished from app failures. The final ANR record remained the original 11:33:40 event; no later ANR was observed.

## Validation limits

One real emulator ANR occurred at 11:33:40 while navigating the agent picker. Its main-thread trace was in Android/Compose text drawing, with substantial scheduling and memory pressure on the single-core, 2 GB emulator. No blocking network or disk operation was identified as its root cause. Moving metadata work off the UI thread reduces that work, but does not prove the ANR's cause or guarantee it cannot recur.

A cold restart took about 44 seconds, including 25 seconds loading preferences and a 41-second concurrent pricing preload. It completed without a new ANR. Startup performance on this populated, resource-constrained emulator remains a separate audit target.

Cancellation before the provider supplies usage may leave the final charge unknown. Partial text estimates are labeled accordingly. RAG embedding estimates, successful document indexing/retrieval, web-tool fees, large saved-state limits, and actual on-device model inference need separate coverage. This pass does not certify all 91 providers or the entire application as bug-free.

Refinement shares the corrected parameter conversion, streaming, sanitation, and usage paths and passed compilation. Its Apply action and disk-full failures were not exercised in this pass. No unit or instrumented suite was run; this used the repository's default build/deploy workflow and manual emulator checks.

Google's official [thinking configuration documentation](https://ai.google.dev/gemini-api/docs/generate-content/thinking) confirms that Gemini 2.5 Flash supports a zero thinking budget, while Gemini 2.5 Pro and the Gemini 3 family cannot fully disable thinking.
