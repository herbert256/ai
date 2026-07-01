# AI Parameters — how they resolve

This document explains how **generation parameters** are chosen for every kind of
API call in the app, and the exact **precedence** at each call site.

The tunable fields are (in their `AgentParameters` order, `data/DataModels.kt`):
`temperature`, `maxTokens`, `topP`, `topK`, `frequencyPenalty`,
`presencePenalty`, `systemPrompt`, `stopSequences`, `seed`,
`responseFormatJson`, `searchEnabled`, `returnCitations`, `searchRecency`,
`webSearchTool`, `reasoningEffort` — **15 fields**.

Parameters live in two shapes:

- **`Parameters`** — a *named preset* the user saves (AI Setup → Parameters). It
  carries the same 15 tunable fields (the system prompt included) plus an `id`
  and `name`. Defined in `model/SettingsModels.kt`.
- **`AgentParameters`** — the resolved, in-memory bundle actually sent on a call.
  A preset becomes one via `Parameters.toAgentParameters()`. Defined in
  `data/DataModels.kt`.

A "level" (agent, provider, app-wide, …) holds a **list of preset ids**
(`paramsIds` / `parametersIds`). That list is collapsed to a single
`AgentParameters` by `Settings.mergeParameters(ids)`.

> Moderation calls take **no** parameters (the `/moderations` endpoint ignores
> them) and are omitted from the tables below. `executeSecondaryTask`
> short-circuits straight to the moderation endpoint when `kind ==
> SecondaryKind.MODERATION`.

---

## Building blocks

### `mergeParameters(ids)` — collapsing a preset list
`Settings.mergeParameters` (`model/SettingsModels.kt`). Resolves each id to its
`Parameters`, drops the ones that don't resolve, then folds the rest **left→right**:

- For nullable value fields (temperature, maxTokens, topP, topK, the two
  penalties, systemPrompt, stopSequences, seed, searchRecency, reasoningEffort):
  **a later preset's non-null value wins** over an earlier one (`p.x ?: acc.x` —
  `null` means "not set, keep previous").
- For booleans the merge is intentional, not just "last wins":
  - `returnCitations` is **AND**-ed — it defaults to `true`, so an explicit
    opt-out anywhere in the chain sticks.
  - `searchEnabled`, `webSearchTool`, `responseFormatJson` are **OR**-ed — they
    default to `false`, so an opt-in anywhere sticks.
- Empty list, or no resolvable presets → **`null`** (meaning "this level sets
  nothing", so resolution falls through to the next level).

### `resolveAgentParameters(agent)`
`mergeParameters(agent.paramsIds) ?: AgentParameters()` — an agent's own presets,
or an empty bundle.

### The dispatch fold — `AnalysisRepository.analyzeWithAgent(...)` / `analyzeWithAgentStreaming(...)`
Every report-generation and secondary call ends here. It receives
`agentResolvedParams` (resolved by the caller, see below) and an optional
`overrideParams`, then runs, in order:

1. **`mergeParameters(agentResolvedParams, overrideParams)`** — the dispatch-side
   merge in `AnalysisRepository` (distinct from `Settings.mergeParameters`).
   **`overrideParams` wins per field** (same null-coalescing for scalars, OR for
   the default-false booleans, AND for `returnCitations`). For `systemPrompt` and
   `stopSequences` the override only wins when it is non-blank / non-empty. If
   `overrideParams` is `null` the agent params pass through untouched.
2. **`validateParams(...)`** — clamps numerics to valid ranges. The temperature
   range is **provider-aware**: `0–1.5` for Mistral, `0–1` for Anthropic-format
   providers, `0–2` otherwise; topP `0–1`, topK / maxTokens `≥1`, the two
   penalties `−2..2`. This does not change *selection*, only the values.
3. **`filterParametersBySupported(...)`** — **only when an `overrideParams` was
   present** (and a `context` is available). Drops any field the target model is
   known not to accept, using the catalog's supported-parameter list
   (`PricingCache.getSupportedParameters`). The match keys are the wire names
   (`temperature`, `max_tokens`, `top_p`, `top_k`, `frequency_penalty`,
   `presence_penalty`, `stop`, `seed`, `response_format`). **The system prompt is
   never filtered** (see [system-prompts.md](system-prompts.md)), and the
   web-search / citation / reasoning fields are passed through untouched.

So the universal last step is: **`overrideParams` over `agentResolvedParams`,
then clamp, then (only if an override existed) drop-unsupported.**

> **Reasoning-effort is gated again at the wire.** Even after a `reasoningEffort`
> value is resolved, the dispatch only attaches `reasoning_effort` when
> `isReasoningCapableForDispatch(service, model)` is true — a thin delegate to
> `ModelCapabilityResolver` (Settings reference when published, else a
> LiteLLM / models.dev / heuristic catalog chain, with an xAI-style
> always-on-reasoning gate). A model that doesn't accept the parameter
> silently drops it — so a resolved effort can be a no-op. See
> `data/ApiDispatchBuilders.kt` and `data/ModelCapabilityResolver.kt`.

---

## Where you set each level

| Level | Set it at |
|---|---|
| Per-report **pre-gen override** (🌡️) | New AI Report / Manage report → 🌡️ "Configure API parameters" picker (presets and/or on-the-fly values) |
| **Selection** pick (per chosen model on the report) | Report – select models / the secondary-op model pickers → 🌡️ |
| **Agent** preset | AI Setup → Workers → Agents → edit (🌡️) |
| **Flock / Swarm** preset | AI Setup → Workers → Flocks / Swarms → edit (🌡️) |
| **Provider** preset | AI Setup → Providers → a provider → edit (🌡️) |
| **App-wide** & **Report-model** defaults | AI Setup → App settings (`appWideParametersIds`, `reportModelParametersIds`) |
| The presets themselves | AI Setup → Parameters (CRUD) |
| Per **internal prompt** | AI Setup → Prompt management → a prompt → 🌡️ (stored as `prompt.parameters` — a stable preset id; legacy rows may still hold the preset *name*) |

---

## Report generation

`viewmodel/ReportViewModel.kt` → `generateGenericReports` → `buildReportTasks`.

A report's models come in three flavours: **agents**, **swarm members**, and
**bare/direct models** (picked straight from the model list). `buildReportTasks`
resolves a `resolvedParams` bundle per task; the per-report **pre-gen override**
is resolved once, carried separately as `overrideParams`, and applied later at
the dispatch fold.

### Pre-gen override — where it comes from and how it lands
`resolveReportOverrideParams(...)` builds it once, by merging two sources
(advanced wins per field): the presets picked in the 🌡️ picker
(`reportParametersIds`, merged via `Settings.mergeParameters`) and any on-the-fly
values from that picker (`reportAdvancedParameters`). It then folds in the
per-report 🌐 web-search and 🧠 reasoning-effort toggles
(`reportWebSearchTool` → `webSearchTool = true`, `reportReasoningEffort` →
`reasoningEffort`).

This bundle is **not** merged into the per-model presets during `buildReportTasks`.
It is passed straight to `executeReportTask` as `overrideParams`, so the
**dispatch fold** is what makes it win field-wise over each model's resolved
params. The only thing `buildReportTasks` does with the override is read a single
flag, `preGenParamsActive` (`true` when any advanced value, web-search, or
reasoning toggle is set): when active it **suppresses the bare-model report-model
and app-wide *parameter* fallbacks** below (the override already speaks for the
report).

### Agent
| # (highest wins) | Source |
|---|---|
| 1 | **Selection pick** for this agent (`selectionParamsById[agent.id]`) |
| 2 | **Agent** preset (`agent.paramsIds`) |
| 3 | **App-wide** default (`appWideParametersIds`) |
| 4 | empty `AgentParameters()` |

(A flock's members are agents, so they resolve by this same agent chain; a flock
only contributes a *system prompt*, not parameters — flock and agent param
presets are concatenated at model-expansion time in `expandFlockToModels`, but
the report-task chain above re-resolves from `agent.paramsIds`.)

### Swarm member / bare-direct model
The task id (`sid`) is `swarm:<providerId>:<model>`. "Direct" means the model was
picked straight from the list (not via a swarm).

| # (highest wins) | Source |
|---|---|
| 1 | **Selection pick** for this model (`selectionParamsById[sid]`) |
| 2 | **Provider** preset (`providerConfig.parametersIds`) — *direct models only* |
| 3 | **Report-model** default (`reportModelParametersIds`) — *direct only, and only when no pre-gen override is active* |
| 4 | **App-wide** default (`appWideParametersIds`) — *only when no pre-gen override is active* |
| 5 | empty `AgentParameters()` |

Then, for every task, the **dispatch fold** applies (`overrideParams` carries the
per-report 🌡️ values plus the 🌐 web-search and 🧠 reasoning-effort toggles, and
wins per field over the task's `resolvedParams`).

> The system-prompt chain for the same tasks is similar but distinct (it folds in
> the report-level / flock / swarm / provider / report-model / external /
> app-wide system prompts). It is documented in
> [system-prompts.md](system-prompts.md).

---

## Secondary operations & metadata generation

Rerank, Meta, Fan-out, Fan-in, and the **Find-alternative** metadata calls
(alternative icons / titles, model titles, alternative translations) resolve
through one helper: `viewmodel/ReportViewModelHelpers.kt` →
`resolveSecondaryParams(general, aiSettings, paramsIds, systemPromptId, prompt?, agent?)`.

> The **automatic** metadata-gen calls (report icon / title / language, per-model
> icon / title) and the **worker-grid** flows do **not** use this helper — they
> run through the `WorkerRunner` chain (or a fixed-host judge call) and send empty
> parameters. See "Worker-grid flows send no parameters" below. Only the
> single-result, picker-driven secondaries listed above resolve params here.
>
> **The main Translate batch run is a worker-grid flow, not a
> `resolveSecondaryParams` caller** — despite `TRANSLATE` being one of the eight
> `SecondaryKind` values, `TranslationRunManager.runOneTranslation` dispatches
> each item straight through `WorkerRunner.run` (the same pool shape as
> Tournament / Compare) with no explicit parameter / system-prompt preset — see
> [translation.md](translation.md). Only the **find-alternative-translation**
> candidate preview (a fixed model per candidate) calls `resolveSecondaryParams`.

Unlike the report chains, the parameter-id source here is picked by
**first-non-empty** (`ifEmpty`), *not* a cross-level merge — once a level
supplies any ids the lower levels are ignored, and only that level's ids are then
folded with `Settings.mergeParameters`:

| # (first non-empty wins) | Source |
|---|---|
| 1 | **Runtime 🌡️ pick** on the op's model selector (`paramsIds`) |
| 2 | The **internal prompt's own** parameters preset (`prompt.parameters`, resolved by stable id or — legacy — by name; blank / `*NONE` ignored) |
| 3 | The **bound agent's** preset (`agent.paramsIds`) — when the prompt is pinned to an agent |
| 4 | **App-wide** default (`appWideParametersIds`) |
| 5 | empty `AgentParameters()` |

> Level 3 is **currently inert**: `resolveSecondaryParams` accepts an `agent`
> argument and would slot it between the prompt's own and the app-wide level, but
> **no call site passes one** — every secondary op is driven by a provider+model
> worker or a runtime-picked model, so the `agent` parameter always defaults to
> `null`. It is kept in the signature for the (not-yet-wired) agent-pinned case.

The system prompt resolves independently (also first-non-empty):
`systemPromptId` → the prompt's own `systemPrompt` (by stable id or legacy name) →
`agent.systemPromptId` → `appWideSystemPromptId`; if found it is copied onto the
resolved bundle's `systemPrompt`. Callers include `SecondaryRunManager` (rerank /
meta / fan-in, via `runSecondaryViaSwarm` → `executeSecondaryTask`),
`FanOutEngine`, `MetaEditManager`, `TranslationRunManager` (find-alternative-
translation candidates only), and `IconGenerationManager` (alt icons, model
titles). For the swarm-driven kinds (rerank / meta / fan-in) the **worker chain
only picks the provider/model** (random pick + fallback); fan-out / find-
alternative-translation / alt-metadata run against a fixed model. Either way
the *parameters* come from `resolveSecondaryParams` above.

(Moderation: no parameters.)

### Worker-grid flows send **no parameters** at all

Tournament, Judge-the-judges, Compare-with-meta, and Rank-the-translators
(`SecondaryKind` = `TOURNAMENT` / `JUDGES` / `COMPARE` / `TRANSRANK` — the latter
four of the **eight** kinds) are worker-grid flows rather than single
selected-model secondary calls, and they bypass `resolveSecondaryParams`
entirely:

- **Tournament** and **Compare-with-meta** run each cell through
  `WorkerRunner.run` (the random-pick + fallback worker chain over the prompt's
  swarm).
- **Judge-the-judges** (`JudgeEvalEngine`) and **Rank-the-translators**
  (`TranslatorRankEngine`) fix one named judge per cell and call
  `analyzeWithAgent` directly.

In **all four** the call is `analyzeWithAgent(agent, "", resolved, …)` with
**neither `agentResolvedParams` nor `overrideParams` supplied** — so
`agentResolvedParams` defaults to an empty `AgentParameters()` and nothing is
overridden. The resolved worker contributes **only** provider / model / API key /
base URL; its agent or provider parameter presets, and the prompt's own preset,
are **all ignored**. Net effect: every judge/match/compare cell is sent with the
provider defaults (no temperature, maxTokens, top-p, …). This is what the
rank-translators feature means by "the judge worker uses `parameters=*NONE`". The
runtime shape of these grids is documented in
[tournament-judges-compare.md](tournament-judges-compare.md) and
[secondary-results.md](secondary-results.md).

### `modelSelection` — `*CONFIGURED` vs `*SELECT`

A worker-carrying `InternalPrompt` has a `modelSelection` field
(`model/SettingsModels.kt`, sentinels `MODEL_SELECTION_CONFIGURED = "*CONFIGURED"`
/ `MODEL_SELECTION_SELECT = "*SELECT"`):

- `*CONFIGURED` (default) — run against the prompt's configured `workers`.
- `*SELECT` — at run time, before the work starts, show the
  +Agent/+Flock/+Swarm/+Model picker and run against the workers the user picks
  (passed down as `overrideWorkers`; never written back to the prompt).

This only changes **which** workers run; it does **not** change parameter
resolution — the single-result kinds still resolve params via
`resolveSecondaryParams`, and the worker-grid kinds still send empty params.

### `Report.workerConfig` ("Report - select workers")

The per-report worker config (picked on the "Report - select workers" screen,
see [workers.md](workers.md)) swaps the worker pool per card: Worker batches =
`REPORT_MODELS` puts the report's own answer models (`reportModelWorkers`) on
the batch pool for Tournament, Compare, Fan Meta and Translation — **winning
over a `*SELECT` pick** (`resolveBatchSwarm` precedence); a persisted
`SELECT_ONCE` group sits between a runtime pick and the configured chain.
**Rerank and Moderation are exempt** — both pass `alwaysPromptWorkers = true`
and always run on their own prompt's configured workers regardless of the
`REPORT_MODELS` / `*SELECT` choice. **Judges and TransRank have no worker-pool
selection at all** — each reuses the distinct judges / translator models
recorded on the Tournament / Translation run it evaluates. **Meta and Fan-in**
route on their own mirror set (`metaBatches` / `metaBatchWorkers`, same option
set) so they can draw from a different pool than the batches above. The
Report-info / Model-info cards swap the metadata prompts' chains likewise.
Like `*SELECT`, all of it changes only the worker set; parameters resolve
exactly as above (empty for worker-grid kinds, `resolveSecondaryParams` for
single-result kinds).

---

## Chat & Dual chat

`viewmodel/ChatViewModel.kt`. Chats carry a `ChatParameters` (the session's
source of truth, `data/DataModels.kt`), set when the chat is configured:

- **New chat with an agent** → the agent's resolved parameters seed the session.
- **Configure on the fly** / **Dual chat setup** → the 🌡️ picker on the setup
  screen sets them (passed explicitly as `sessionParams`).
- **Resumed session** → whatever was saved with the `ChatSession`.

At send time (`sendChatMessageStream`) the only extra layer is per-turn: the 🌐
web-search and 🧠 reasoning toggles overlay the session params
(`copy(webSearchTool = true)` when web-search is on and the session didn't already
have it; `copy(reasoningEffort = …)`, where an empty string clears back to "no
hint"). Otherwise the session params are sent as-is.

The two per-turn toggles are screen state seeded from the session params
(`useWebSearch` / `reasoningEffort` in `ui/chat/ChatScreens.kt`), held in
`rememberSaveable(currentSessionId)` so a rotation or process re-creation keeps
the user's current selection instead of snapping back to the session default. The
reasoning value is also re-validated against the model's advertised
`reasoningEffortLevels` and cleared if unsupported.

| # (highest wins) | Source |
|---|---|
| 1 | Per-turn 🌐 / 🧠 overlay |
| 2 | Session `ChatParameters` (agent default / configure-on-the-fly / saved session) |

---

## Quick mental model

1. Start from the **most specific** thing you touched for *this* call (a runtime
   pick, or the per-report override).
2. Fall back through the **entity** that owns the model (agent → flock/swarm →
   provider for direct models). Report generation **merges** down this chain;
   secondary ops take the **first non-empty** level only.
3. Fall back to the **app-wide** (and, for bare report models, report-model)
   defaults.
4. The **dispatch fold** lets the per-report override win over all of it, then
   clamps values to provider-valid ranges, and — when an override was present —
   drops anything the model can't accept. `reasoning_effort` is gated once more at
   the wire by `isReasoningCapableForDispatch`.
5. **Worker-grid flows are the exception**: Tournament / Judges / Compare /
   TransRank (and the main Translate batch run) send **no** resolved
   parameters — provider defaults only. `*SELECT` and `Report.workerConfig`
   change *which* workers run, never the params.
