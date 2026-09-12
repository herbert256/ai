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
a free-text `systemPrompt`, and an `InternalPrompt` references a preset by stable **id** (or legacy name)
(`InternalPrompt.systemPrompt`, default `"*NONE"`).

> Worker-driven calls use each worker's frozen resolved parameters, including
> its system prompt. Translation and report/answer title/icon generation add
> task rules and the configured template there; source text stays in separate
> user-message blocks. See the dispatch families under
> [Secondary operations](#secondary-operations--metadata-generation).

---

## Where you set each level

| Level | Set it at |
|---|---|
| Per-report (🎭) | New AI Report / Manage report → 🎭 "Define model system prompt" picker (`reportSystemPromptId`) |
| **Agent** | AI Setup → Workers → Agents → edit (🎭) → `Agent.systemPromptId` |
| **Flock / Swarm** | AI Setup → Workers → Flocks / Swarms → edit (🎭) → `Flock.systemPromptId` / `Swarm.systemPromptId` |
| **Provider** | AI Setup → Providers → a provider → edit (🎭) → `ProviderConfig.systemPromptId` |
| **App-wide** & **Report-model** | AI Setup → App settings (`appWideSystemPromptId`, `reportModelSystemPromptId` on `GeneralSettings`) |
| The presets themselves | AI Setup → System prompts (CRUD) |
| Per **internal prompt** | AI Setup → Prompt management → a prompt → 🎭 (stored by preset **id**, with legacy names supported in `InternalPrompt.systemPrompt`) |
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
2. Preserve the requested configuration for replay and validate supported values
   at dispatch. System prompts remain verbatim; numeric controls are no longer
   silently filtered from report requests.

The merged result is sent as the request's system message by the per-format
dispatch (Anthropic `system`, Gemini `systemInstruction`, OpenAI Chat
`role:"system"` message, OpenAI Responses `instructions`).

Caveat: some models don't accept system messages; the agent / provider / model
edit screens surface a warning when the chosen model is known not to.

---

## Report generation

`viewmodel/ReportViewModel.kt` → `buildReportTasks`, using the selected model's `sourceType` / `sourceId` to identify its group.

The chain is a plain `?:` ladder; the **first non-null wins** and is applied with
`params.copy(systemPrompt = spText)`.

### Agent

```
reportLevelSystemPrompt                              // the report's 🎭
  ?: selectedFlockSystemPrompt                        // selected source only
  ?: agentSystemPrompt
  ?: externalSystemPrompt                            // ACTION_NEW_REPORT "system"
  ?: appSp                                            // app-wide default
```

| # (highest wins) | Source |
|---|---|
| 1 | **Report-level** prompt (`reportSystemPromptId`, the report's 🎭) |
| 2 | **Selected Flock** prompt — only when selected through that flock |
| 3 | **Agent** prompt (`agent.systemPromptId`) |
| 4 | **External-intent** system prompt (`externalSystemPrompt`) |
| 5 | **App-wide** default (`appWideSystemPromptId`) |
| — | otherwise none |

> Membership alone does not apply a group configuration. Selecting the Agent
> directly uses its own defaults. When overlapping group selections deduplicate
> an Agent, the first selected source is retained.

### Swarm member / bare-direct model

```
reportLevelSystemPrompt
  ?: selectedSwarmSystemPrompt                         // selected source only
  ?: (if (isDirect) providerConfig.systemPromptId else null)
  ?: (if (isDirect) reportModelSystemPromptId else null)
  ?: externalSystemPrompt
  ?: appSp
```

| # (highest wins) | Source |
|---|---|
| 1 | **Report-level** prompt (`reportSystemPromptId`) |
| 2 | **Selected Swarm** prompt — only when selected through that swarm |
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
> takes effect for that level. An explicit report system prompt also wins over
> text embedded in a report parameter preset.

---

## Secondary operations & metadata generation

There is no single chain here — secondary / metadata calls split into **three
dispatch families**, all of which preserve the applicable resolved system prompt. The
8 `SecondaryKind` values (`data/SecondaryModels.kt`:
`RERANK, META, MODERATION, TRANSLATE, TOURNAMENT, JUDGES, COMPARE, TRANSRANK`)
plus the metadata-gen calls map onto them like this:

| Call | Dispatcher | System prompt |
|---|---|---|
| **Rerank** (chat-model path) | `executeSecondaryTask` | resolved (Family 1) |
| **Meta / Summarize** | `executeSecondaryTask` | resolved (Family 1) |
| **Fan-out** pairs + replay | `executeSecondaryTask` / direct | resolved (Family 1) |
| **Fan-in** | `executeSecondaryTask` | resolved (Family 1) |
| **Meta edit / replay** | direct `analyzeWithAgent` | resolved (Family 1) |
| **Find-alternatives** probes (alt icons / alt titles / alt translations) | direct `analyzeWithAgent` | resolved (Family 1) |
| **Translate** (main text + titles) | `WorkerRunner.run` | frozen worker prompt plus explicit translation instructions (Family 2) |
| **Tournament** | `WorkerRunner.run` | none (Family 2) |
| **Compare**-with-meta | `WorkerRunner.run` | none (Family 2) |
| Initial **report** icon / title / language name + icon | `WorkerRunner.run` | none (Family 2) |
| Initial **per-model** icons / titles, **fan-meta** | `WorkerRunner.run` | none (Family 2) |
| **Judges** (judge-the-judges) | fixed per-cell direct | none (Family 3) |
| **Transrank** ("Rank the translators") | fixed per-cell direct | none (Family 3) |
| **Moderation** | `callModerationApi` | none — no params at all |

### Family 1 — `resolveSecondaryParams` (system prompt resolved & applied)

Rerank, Meta/Summarize, Fan-out (per-pair + replay), Fan-in, Meta-edit, and the
"Find alternatives" probes resolve their parameters **and** system prompt
through one shared helper:

```kotlin
resolveSecondaryParams(general, aiSettings, paramsIds, systemPromptId,
                       prompt?: InternalPrompt, agent?: Agent)
```

(`viewmodel/ReportViewModelHelpers.kt`). The resolved `AgentParameters` is then
passed **positionally** as `agentResolvedParams` to
`AnalysisRepository.analyzeWithAgent`, so the system-prompt string actually
reaches the call. Call sites: `SecondaryRunManager.executeSecondaryTask`
(rerank / meta / fan-in, and fan-out pairs routed in from `FanOutEngine`),
`FanOutEngine` (fan-out replay), `MetaEditManager` (meta edit/replay),
`IconGenerationManager` (only the *alternatives* fan-outs — alt icons / alt
model & report titles), and `TranslationRunManager` (only the *alternative*
translation probe).

The **system-prompt id** is picked by first-non-null:

```kotlin
val spId = systemPromptId                 // runtime 🎭 pick on the op's selector
  ?: promptSpId                            // InternalPrompt.systemPrompt, matched by id or legacy name
  ?: agent?.systemPromptId                 // bound agent's prompt (if pinned to an agent)
  ?: general.appWideSystemPromptId         // app-wide default
```

| # (highest wins) | Source |
|---|---|
| 1 | **Runtime 🎭 pick** on the op's model selector (`systemPromptId` arg) |
| 2 | The **internal prompt's own** system prompt (`InternalPrompt.systemPrompt`, matched by id or legacy name, blank / `"*NONE"` ignored) |
| 3 | The **bound agent's** prompt (`agent.systemPromptId`) — only when the prompt is pinned to an agent rather than a bare provider+model pair |
| 4 | **App-wide** default (`appWideSystemPromptId`) |
| — | otherwise none |

When a system prompt resolves, `resolveSecondaryParams` returns
`base.copy(systemPrompt = sp)`; otherwise the base merged params are returned
unchanged. (Note this is **first-non-null**, not a merge — there is no
report-level or provider level in the secondary chain.)

### Family 2 — `WorkerRunner.run` (frozen worker configuration)

Main translation, Tournament, Compare-with-meta, and **all initial
metadata generation** (report icon / title / language name + icon, per-model
icons / titles, fan-meta) dispatch through `WorkerRunner.run(prompt,
resolvedText, aiSettings, context, accept)` (`viewmodel/WorkerRunner.kt`). The
runner expands the prompt's `workers` to their members and dispatches with
each worker's `frozenParameters`, including its resolved system prompt when
present. Report translation additionally uses `buildTranslationRequest` to
put the translation rules and configured template in the system message,
while the original text is sent separately as delimited user data. The same
separation applies to alternative translation probes in Family 1. See
[translation.md](translation.md) for saved-run and source-placeholder behavior.

Report/answer titles and icons similarly use `buildMetadataRequest`, including
alternative candidates and fan-meta. The runtime rules forbid answering or
executing source requests and distinguish the original question from an actual
saved answer. `MetadataRequest.workerPrompt` preserves frozen worker settings
and appends the metadata instructions to the resolved system message; direct
alternative calls apply the same addition after `resolveSecondaryParams`.
See [report-icons.md](report-icons.md#source-text-and-metadata-instructions).

### Family 3 — fixed per-cell dispatch

Judge evaluation and Translator ranking freeze each selected worker's resolved
configuration before dispatch. They use the internal prompt, selected group /
Agent, and app-wide settings through the same resolution helper. Native
moderation and rerank endpoints have their own supported schemas.

`Report.workerConfig` selects the worker pool. Selecting a Flock or Swarm carries
its configuration into that pool: Flock presets merge below member Agent presets,
and a group system prompt wins over the Agent prompt. Runtime and internal-prompt
selections take precedence over these worker defaults. Selecting bare report
models as workers selects their provider/model identities; primary report
parameter overrides are not automatically inherited.

Tournament, Compare, Judge evaluation and Translator ranking save frozen worker
manifests for replay. They do send the resolved system prompt when one is set.

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
7. **Secondaries split three ways.** Only the `resolveSecondaryParams` family
   (rerank, meta, fan-out, fan-in, meta-edit, and the *alternatives* probes)
   resolves a system prompt — runtime 🎭 → prompt's own → bound agent →
   app-wide. The `WorkerRunner` family (main translation, tournament, compare,
   initial icons / titles / language) and the fixed-cell family (judges,
   transrank), plus moderation, dispatch with default params and send **no**
   system prompt.


## Agent consistency and launch inspection

`Settings.resolveAgentParameters` overlays the Agent's standalone system prompt on its merged presets. Chat and Report continuation use that role consistently. Report participant selection retains group-specific overrides only for members selected through that group. The Report launch review shows the effective system instruction, parameters and resolved question for each participant and freezes that execution configuration before dispatch; attached knowledge context is added once before the call.

Future generated Report short/long titles receive an explicit neutral-question rule. The report owner selects the conclusion separately; existing saved titles are not rewritten.
