# System Prompts — how they resolve

This document explains how the **system prompt** (the standing instruction that
sets a model's role/tone) is chosen for every kind of API call, and the exact
**precedence** at each call site.

System prompts live in two places:

- **`SystemPrompt`** preset — a *named* reusable instruction the user saves
  (AI Setup → System prompts): `{ id, name, prompt }`.
- The resolved text rides to dispatch inside **`AgentParameters.systemPrompt`** —
  i.e. once resolved, a system prompt is just a field on the parameter bundle
  (see `parameters.md` for how that bundle is merged and sent).

Every level (agent / flock / swarm / provider / app-wide / report-model) stores a
single optional `systemPromptId`; a preset list (parameters) can also carry a
`systemPrompt`. Lookups go through `Settings.getSystemPromptById(id)`.

> Moderation calls have **no** system prompt and are omitted below.

---

## Where you set each level

| Level | Set it at |
|---|---|
| Per-report (🎭) | New AI Report / Manage report → 🎭 "Define AI model system prompt" picker (`reportSystemPromptId`) |
| **Agent** | AI Setup → Workers → Agents → edit (🎭) |
| **Flock / Swarm** | AI Setup → Workers → Flocks / Swarms → edit (🎭) |
| **Provider** | AI Setup → Providers → a provider → edit (🎭) |
| **App-wide** & **Report-model** | AI Setup → App settings |
| The presets themselves | AI Setup → System prompts (CRUD) |
| Per **internal prompt** | AI Setup → Prompt management → a prompt → 🎭 (stored by preset *name*) |
| **External intent** | passed in by an `ACTION_NEW_REPORT` intent's `<system>` block |

---

## How the resolved text reaches the model

The resolution chains below produce a **system-prompt string**. It is written
onto the call's `AgentParameters.systemPrompt`. At the dispatch fold
(`AnalysisRepository.analyzeWithAgent`), a **non-blank override** system prompt
wins over the agent-resolved one, and the result is sent as the request's system
message (`buildPrompt` / the per-format dispatch). Unlike numeric params, the
system prompt is **never** dropped by the supported-parameter filter.

Caveat: some models don't accept system messages; the agent / provider / model
edit screens surface a warning when the chosen model is known not to.

---

## Report generation

`viewmodel/ReportViewModel.kt` → `buildReportTasks`, with helpers
`findFlockSystemPromptIdForAgent` and `findSwarmSystemPromptIdForMember` in
`ReportViewModelHelpers.kt`.

### Agent
| # (highest wins) | Source |
|---|---|
| 1 | **Report-level** prompt (`reportSystemPromptId`, the report's 🎭) |
| 2 | **Flock** prompt — first flock this agent belongs to that has one (`findFlockSystemPromptIdForAgent`) |
| 3 | **Agent** prompt (`agent.systemPromptId`) |
| 4 | **External-intent** system prompt (`externalSystemPrompt`) |
| 5 | **App-wide** default (`appWideSystemPromptId`) |
| — | otherwise none |

### Swarm member / bare-direct model
| # (highest wins) | Source |
|---|---|
| 1 | **Report-level** prompt (`reportSystemPromptId`) |
| 2 | **Swarm** prompt — first swarm containing this (provider, model) that has one (`findSwarmSystemPromptIdForMember`) |
| 3 | **Provider** prompt (`providerConfig.systemPromptId`) — *direct models only* |
| 4 | **Report-model** default (`reportModelSystemPromptId`) — *direct only* |
| 5 | **External-intent** system prompt |
| 6 | **App-wide** default (`appWideSystemPromptId`) |
| — | otherwise none |

> Note: a parameter preset applied at any level may itself carry a `systemPrompt`.
> That value travels inside the merged `AgentParameters`; the explicit
> system-prompt chain above is layered on with `copy(systemPrompt = …)` when a
> level resolves one, so an explicitly-resolved prompt takes effect for the call.

---

## Secondary operations & metadata generation

Rerank, Meta, Fan-out, Translate, and the metadata-gen calls (report/per-model
icon, title, language, alternatives) resolve through
`resolveSecondaryParams(general, aiSettings, paramsIds, systemPromptId, prompt?, agent?)`.

| # (highest wins) | Source |
|---|---|
| 1 | **Runtime 🎭 pick** on the op's model selector (`systemPromptId`) |
| 2 | The **internal prompt's own** system prompt (matched by *name*) |
| 3 | The **bound agent's** prompt (`agent.systemPromptId`) — when the prompt is pinned to an agent |
| 4 | **App-wide** default (`appWideSystemPromptId`) |
| — | otherwise none |

(Moderation: no system prompt.)

---

## Chat & Dual chat

`viewmodel/ChatViewModel.kt`. The system prompt is part of the session's
`ChatParameters.systemPrompt`, fixed when the chat is configured:

- **New chat with an agent** → the agent's resolved system prompt seeds it.
- **Configure on the fly** / **Dual chat setup** → the 🎭 picker (or the inline
  System-prompt field on Chat Parameters) sets it.
- **Resumed session** → whatever was saved.

There is no per-turn system-prompt override; the session value is sent each turn.

---

## Quick mental model

1. A **report-level 🎭** (or a **runtime 🎭** on a secondary op) wins for that call.
2. Otherwise the **group** the model runs in speaks: flock (for agents) or swarm
   (for swarm members).
3. Otherwise the **entity** itself: agent, or — for a bare/direct model — the
   provider, then the report-model default.
4. An **external intent** can supply one.
5. Finally the **app-wide** default; if nothing matches, no system message is sent.
