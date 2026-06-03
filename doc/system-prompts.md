# System Prompts — how they resolve

This document explains how the **system prompt** (the standing instruction that
sets a model's role/tone) is chosen for every kind of API call, and the exact
**precedence** at each call site.

System prompts live in two places:

- **`SystemPrompt`** preset — a *named* reusable instruction the user saves
  (AI Setup → System prompts): `data class SystemPrompt(id, name, prompt)`
  (`model/SettingsModels.kt`). Looked up by id via
  `Settings.getSystemPromptById(id)`.
- The resolved text rides to dispatch inside **`AgentParameters.systemPrompt`**
  (reports / secondaries) or **`ChatParameters.systemPrompt`** (chat) — i.e.
  once resolved, a system prompt is just a string field on the call's parameter
  bundle (see [parameters.md](parameters.md) for how that bundle is merged and
  sent).

Every level (agent / flock / swarm / provider / app-wide / report-model / report)
stores a single optional `systemPromptId`. A `Parameters` preset can *also* carry
a free-text `systemPrompt`, and an `InternalPrompt` references a preset by **name**
(`InternalPrompt.systemPrompt`, default `"*NONE"`).

> Moderation calls have **no** system prompt and are omitted from the chains
> below.

---

## Where you set each level

| Level | Set it at |
|---|---|
| Per-report (🎭) | New AI Report / Manage report → 🎭 "Define AI model system prompt" picker (`reportSystemPromptId`) |
| **Agent** | AI Setup → Workers → Agents → edit (🎭) → `Agent.systemPromptId` |
| **Flock / Swarm** | AI Setup → Workers → Flocks / Swarms → edit (🎭) → `Flock.systemPromptId` / `Swarm.systemPromptId` |
| **Provider** | AI Setup → Providers → a provider → edit (🎭) → `ProviderConfig.systemPromptId` |
| **App-wide** & **Report-model** | AI Setup → App settings (`appWideSystemPromptId`, `reportModelSystemPromptId` on `GeneralSettings`) |
| The presets themselves | AI Setup → System prompts (CRUD) |
| Per **internal prompt** | AI Setup → Prompt management → a prompt → 🎭 (stored by preset **name** in `InternalPrompt.systemPrompt`) |
| **External intent** | `com.ai.ACTION_NEW_REPORT` intent's `"system"` string extra |

The external-intent value is read in `MainActivity.handleIntent` as
`intent.getStringExtra("system")` and stored on `ExternalIntent.systemPrompt`;
`UiState.externalSystemPrompt` surfaces it for the chains below. (It is a plain
string extra, not an XML block.)

---

## How the resolved text reaches the model

The resolution chains below produce a **system-prompt string** which is written
onto the call's `AgentParameters.systemPrompt` (via `params.copy(systemPrompt = …)`).
At the report dispatch fold — `AnalysisRepository.analyzeWithAgent`
(`data/AnalysisRepository.kt`) — two transforms run, in order:

1. **`mergeParameters(agentResolvedParams, overrideParams)`** — a non-blank
   *override* `systemPrompt` wins over the agent-resolved one
   (`overrideParams.systemPrompt?.isNotBlank() == true`); otherwise the
   agent-resolved value stays.
2. **`filterParametersBySupported`** — drops fields the model can't accept
   (only when an override is present). The **system prompt is never dropped** by
   this filter — it is copied through verbatim, unlike the numeric params.

The merged result is sent as the request's system message by the per-format
dispatch (Anthropic `system`, Gemini `systemInstruction`, OpenAI Chat
`role:"system"` message, OpenAI Responses `instructions`).

Caveat: some models don't accept system messages; the agent / provider / model
edit screens surface a warning when the chosen model is known not to.

---

## Report generation

`viewmodel/ReportViewModel.kt` → `buildReportTasks`, with helpers
`resolveSystemPromptText`, `findFlockSystemPromptIdForAgent` and
`findSwarmSystemPromptIdForMember` in `viewmodel/ReportViewModelHelpers.kt`.

The chain is a plain `?:` ladder; the **first non-null wins** and is applied with
`params.copy(systemPrompt = spText)`.

### Agent

```
reportLevelSystemPrompt                              // the report's 🎭
  ?: resolveSystemPromptText(agent.systemPromptId,   // flock-or-agent (see note)
                             findFlockSystemPromptIdForAgent(...))
  ?: externalSystemPrompt                            // ACTION_NEW_REPORT "system"
  ?: appSp                                            // app-wide default
```

| # (highest wins) | Source |
|---|---|
| 1 | **Report-level** prompt (`reportSystemPromptId`, the report's 🎭) |
| 2 | **Flock** prompt — first flock this agent belongs to that has one (`findFlockSystemPromptIdForAgent`) |
| 3 | **Agent** prompt (`agent.systemPromptId`) |
| 4 | **External-intent** system prompt (`externalSystemPrompt`) |
| 5 | **App-wide** default (`appWideSystemPromptId`) |
| — | otherwise none |

> **Flock-vs-agent nuance.** Levels 2 and 3 are resolved together inside the
> single helper `resolveSystemPromptText(aiSettings, agentSpId, groupSpId)`,
> whose body is `(groupSpId ?: agentSpId)?.let { … }`. So a flock prompt
> *overrides* the per-agent prompt, but they form **one** resolution step that
> sits beneath the report-level prompt and above the external/app-wide levels.
> There is no independent "flock beats agent beats external" cascade — it's
> "report → (flock-else-agent) → external → app-wide".

### Swarm member / bare-direct model

```
reportLevelSystemPrompt
  ?: findSwarmSystemPromptIdForMember(provider, model)   // first matching swarm
  ?: (if (isDirect) providerConfig.systemPromptId else null)
  ?: (if (isDirect) reportModelSystemPromptId else null)
  ?: externalSystemPrompt
  ?: appSp
```

| # (highest wins) | Source |
|---|---|
| 1 | **Report-level** prompt (`reportSystemPromptId`) |
| 2 | **Swarm** prompt — first swarm containing this (provider, model) that has one (`findSwarmSystemPromptIdForMember`) |
| 3 | **Provider** prompt (`providerConfig.systemPromptId`) — *direct models only* |
| 4 | **Report-model** default (`reportModelSystemPromptId`) — *direct only* |
| 5 | **External-intent** system prompt |
| 6 | **App-wide** default (`appWideSystemPromptId`) |
| — | otherwise none |

"Direct" means a bare provider+model the user picked straight from the model
picker (its synthetic id `swarm:<providerId>:<model>` is in `directModelSids`);
a true swarm member skips the provider and report-model fallbacks and only sees
its swarm level.

> A `Parameters` preset applied at any level may itself carry a `systemPrompt`.
> That value travels inside the merged `AgentParameters`; the explicit
> system-prompt chain above is then layered on with `copy(systemPrompt = …)`
> whenever a level resolves a non-null prompt — so an explicitly-resolved prompt
> always takes effect for the call, overwriting any preset-carried one.

---

## Secondary operations & metadata generation

Rerank, Meta, Fan-out, Fan-in, Translate, Compare-with-meta, and the
metadata-gen calls (report / per-model icon, title, language, alternatives) all
resolve their parameters **and** system prompt through one shared helper:

```kotlin
resolveSecondaryParams(general, aiSettings, paramsIds, systemPromptId,
                       prompt?: InternalPrompt, agent?: Agent)
```

(`viewmodel/ReportViewModelHelpers.kt`). Call sites include `FanOutEngine`,
`IconGenerationManager` (icons / titles / alternatives), `MetaEditManager`,
`TranslationRunManager`, and `SecondaryRunManager` (rerank / meta).

The **system-prompt id** is picked by first-non-null:

```kotlin
val spId = systemPromptId                 // runtime 🎭 pick on the op's selector
  ?: promptSpId                            // InternalPrompt.systemPrompt, matched by NAME
  ?: agent?.systemPromptId                 // bound agent's prompt (if pinned to an agent)
  ?: general.appWideSystemPromptId         // app-wide default
```

| # (highest wins) | Source |
|---|---|
| 1 | **Runtime 🎭 pick** on the op's model selector (`systemPromptId` arg) |
| 2 | The **internal prompt's own** system prompt (`InternalPrompt.systemPrompt`, matched by *name*, blank / `"*NONE"` ignored) |
| 3 | The **bound agent's** prompt (`agent.systemPromptId`) — only when the prompt is pinned to an agent rather than a bare provider+model pair |
| 4 | **App-wide** default (`appWideSystemPromptId`) |
| — | otherwise none |

When a system prompt resolves, `resolveSecondaryParams` returns
`base.copy(systemPrompt = sp)`; otherwise the base merged params are returned
unchanged. (Note this is **first-non-null**, not a merge — there is no
report-level or provider level in the secondary chain.)

Moderation takes no params and no system prompt at all
(`SecondaryRunManager.runModeration` calls `callModerationApi` directly).

### Tournament, Judge-the-judges, Compare-with-meta

These are **worker-judged**: they run the bundled `workers/tournament` (or
`meta_compare/equivalent`) prompt through `WorkerRunner`, which picks a worker
from the prompt's swarm. The judging model — and therefore its
agent / provider / app-wide system-prompt resolution — is the resolved worker's,
chosen at call time. See
[tournament-judges-compare.md](tournament-judges-compare.md) and
[secondary-results.md](secondary-results.md).

---

## Chat & Dual chat

`viewmodel/ChatViewModel.kt`. The system prompt is part of the session's
`ChatParameters.systemPrompt` (a non-null `String` defaulting to `""`,
`data/DataModels.kt`), fixed when the chat is configured and resent **every**
turn — there is no per-turn system-prompt override.

| Entry point | How `ChatParameters.systemPrompt` is seeded |
|---|---|
| **New chat with an agent** (`ChatRoutes.kt` → `AI_CHAT_WITH_AGENT`) | `aiSettings.resolveAgentParameters(agent).systemPrompt` |
| **Configure on the fly** (`ChatParametersScreen`) | the inline System-prompt field, or a 🎭 picker that fills it from a preset (`resolvedSp = picked preset prompt ?: typed text`) |
| **Dual chat setup** (`DualChatScreen`) | `mergeParameters(ids).systemPrompt` of the chosen presets |
| **Resumed session** | whatever was saved on the `ChatSession` |

> **Subtle but important:** in the agent path, `resolveAgentParameters(agent)`
> only **`mergeParameters(agent.paramsIds)`** — it does **not** read
> `agent.systemPromptId`. So a chat started from an agent inherits a system
> prompt **only if** one of the agent's parameter presets carries a
> `systemPrompt`; the agent's standalone 🎭 (`systemPromptId`) is a
> report-generation level and does **not** flow into chat. (Reports do honour
> `agent.systemPromptId`; chat does not.) An app-wide default also does not
> apply to chat unless the user wires it in via a preset or the inline field.

---

## Quick mental model

1. A **report-level 🎭** (or a **runtime 🎭** on a secondary op) wins for that
   call.
2. Otherwise the **group** the model runs in speaks: flock (for agents) or swarm
   (for swarm members) — folded together with the agent level in one step for
   agents.
3. Otherwise the **entity** itself: agent (`agent.systemPromptId`), or — for a
   bare/direct model — the provider, then the report-model default.
4. An **external `ACTION_NEW_REPORT` intent** can supply one (reports only).
5. Finally the **app-wide** default; if nothing matches, no system message is
   sent.
6. **Chat is the exception**: it does not walk this ladder — it only takes a
   system prompt from a parameters preset (agent / dual-chat) or what the user
   types/picks at setup.
