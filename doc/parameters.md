# AI Parameters — how they resolve

This document explains how **generation parameters** (temperature, max tokens,
top-p / top-k, frequency / presence penalty, seed, stop sequences, JSON response
format, web search, return-citations, search recency, web-search tool, reasoning
effort) are chosen for every kind of API call in the app, and the exact
**precedence** at each call site.

Parameters live in two shapes:

- **`Parameters`** — a *named preset* the user saves (AI Setup → Parameters). It
  carries all the tunable fields plus an optional `systemPrompt`.
- **`AgentParameters`** — the resolved, in-memory bundle actually sent on a call.
  A preset becomes one via `Parameters.toAgentParameters()`.

A "level" (agent, provider, app-wide, …) holds a **list of preset ids**
(`paramsIds` / `parametersIds`). The list is collapsed to a single
`AgentParameters` by `Settings.mergeParameters(ids)`.

> Moderation calls take **no** parameters (the `/moderations` endpoint ignores
> them) and are omitted from the tables below.

---

## Building blocks

### `mergeParameters(ids)` — collapsing a preset list
`model/SettingsModels.kt`. Folds the listed presets left→right:

- For value fields (temperature, maxTokens, topP, topK, penalties, systemPrompt,
  stopSequences, seed, searchRecency, reasoningEffort): **a later preset's
  non-null value wins** over an earlier one (null = "not set, keep previous").
- For booleans the merge is intentional, not just "last wins":
  - `returnCitations` is **AND**-ed — an opt-out anywhere in the chain sticks.
  - `searchEnabled`, `webSearchTool`, `responseFormatJson` are **OR**-ed — an
    opt-in anywhere sticks.
- Empty list, or no resolvable presets → `null` (meaning "this level sets
  nothing", so resolution falls through to the next level).

### `resolveAgentParameters(agent)`
`mergeParameters(agent.paramsIds) ?: AgentParameters()` — an agent's own presets,
or empty.

### The dispatch fold — `AnalysisRepository.analyzeWithAgent(...)`
Every call ends here. It receives `agentResolvedParams` (resolved by the caller,
see below) and an optional `overrideParams`, then:

1. `mergeParameters(agentResolvedParams, overrideParams)` — **override wins**
   per field (same null-coalescing / AND-OR rules as above).
2. `validateParams(...)` — clamps numerics to valid ranges (temperature 0–2,
   top-p 0–1, …); does not change selection.
3. `filterParametersBySupported(...)` — when an override is present, drops any
   field the target model is known not to accept (from the pricing/capability
   catalog). **The system prompt is never filtered** (see `system-prompts.md`).

So the universal last step is: **`overrideParams` over `agentResolvedParams`,
then clamp, then drop-unsupported.**

---

## Where you set each level

| Level | Set it at |
|---|---|
| Per-report **pre-gen override** (🌡️) | New AI Report / Manage report → 🌡️ "Configure API parameters" picker |
| **Selection** pick (per chosen model on the report) | Report – select models / the secondary-op model pickers → 🌡️ |
| **Agent** preset | AI Setup → Workers → Agents → edit (🌡️) |
| **Flock / Swarm** preset | AI Setup → Workers → Flocks / Swarms → edit (🌡️) |
| **Provider** preset | AI Setup → Providers → a provider → edit (🌡️) |
| **App-wide** & **Report-model** defaults | AI Setup → App settings |
| The presets themselves | AI Setup → Parameters (CRUD) |
| Per **internal prompt** | AI Setup → Prompt management → a prompt → 🌡️ (stored by preset *name*) |

---

## Report generation

`viewmodel/ReportViewModel.kt` → `generateGenericReports` → `buildReportTasks`.

A report's models come in three flavours: **agents**, **swarm members**, and
**bare/direct models** (picked straight from the model list). All of them can
additionally be affected by the **per-report pre-gen override**.

### Pre-gen override (applies on top of everything below)
`reportAdvancedParameters` (set by the report's 🌡️ picker, which stores
`reportParametersIds` and resolves them via `mergeParameters`) is merged
field-wise **over** the per-model selection presets. When any pre-gen override is
active (`preGenParamsActive`), the *bare-model* report-model / app-wide fallbacks
below are suppressed (the override already speaks for the report).

### Agent
| # (highest wins) | Source |
|---|---|
| 1 | **Selection pick** for this agent (`selectionParamsById[agent.id]`) |
| 2 | **Agent** preset (`agent.paramsIds`) |
| 3 | **App-wide** default (`appWideParametersIds`) |
| 4 | empty `AgentParameters()` |

(A flock's members are agents, so they resolve by this same agent chain; a flock
only contributes a *system prompt*, not parameters.)

### Swarm member / bare-direct model
| # (highest wins) | Source |
|---|---|
| 1 | **Selection pick** for this model (`selectionParamsById[sid]`) |
| 2 | **Provider** preset (`providerConfig.parametersIds`) — *direct models only* |
| 3 | **Report-model** default (`reportModelParametersIds`) — *direct only, and only when no pre-gen override is active* |
| 4 | **App-wide** default (`appWideParametersIds`) — *only when no pre-gen override is active* |
| 5 | empty `AgentParameters()` |

Then, for every task, the **pre-gen override** is folded on top, and finally the
dispatch fold applies (`overrideParams` carries the per-report 🌐 web-search and
🧠 reasoning-effort toggles too).

---

## Secondary operations & metadata generation

Rerank, Meta, Fan-out, Fan-in, Translate, Compare-with-meta, and
the automatic metadata-gen calls (report icon / title / language,
per-model icon / title, alternative icons / titles) resolve through
one helper: `viewmodel/ReportViewModelHelpers.kt` →
`resolveSecondaryParams(general, aiSettings, paramsIds, systemPromptId, prompt?, agent?)`.

| # (highest wins) | Source |
|---|---|
| 1 | **Runtime 🌡️ pick** on the op's model selector (`paramsIds`) |
| 2 | The **internal prompt's own** parameters preset (matched by *name*) |
| 3 | The **bound agent's** preset (`agent.paramsIds`) — when the prompt is pinned to an agent |
| 4 | **App-wide** default (`appWideParametersIds`) |
| 5 | empty `AgentParameters()` |

(Moderation: no parameters.)

Tournament and Judge-the-judges are worker-grid flows rather than
single selected-model secondary calls. They use `workers/tournament`
and the `tournament` swarm: each expanded worker keeps its own agent
/ provider parameters, and the prompt's own presets still apply when
the worker call is resolved. The runtime shape is documented in
[tournament-judges-compare.md](tournament-judges-compare.md).

---

## Chat & Dual chat

`viewmodel/ChatViewModel.kt`. Chats carry a `ChatParameters` (the session's
source of truth), set when the chat is configured:

- **New chat with an agent** → the agent's resolved parameters seed the session.
- **Configure on the fly** / **Dual chat setup** → the 🌡️ picker on the setup
  screen sets them.
- **Resumed session** → whatever was saved with the session.

At send time the only extra layer is per-turn: the 🌐 web-search and 🧠 reasoning
toggles overlay the session params (`copy(webSearchTool = true)` /
`copy(reasoningEffort = …)`); otherwise the session params are sent as-is.

| # (highest wins) | Source |
|---|---|
| 1 | Per-turn 🌐 / 🧠 overlay |
| 2 | Session `ChatParameters` (agent default / configure-on-the-fly / saved session) |

---

## Quick mental model

1. Start from the **most specific** thing you touched for *this* call (a runtime
   pick, or the per-report override).
2. Fall back through the **entity** that owns the model (agent → flock/swarm →
   provider for direct models).
3. Fall back to the **app-wide** (and, for bare report models, report-model)
   defaults.
4. The **dispatch fold** lets a report-level override win over all of it, then
   clamps values and drops anything the model can't accept.
