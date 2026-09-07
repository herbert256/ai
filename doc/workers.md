# AI Workers — Agents, Flocks, Swarms

Three saved "worker" types let the user bundle model picks (and
their parameter / prompt resolution) for one-tap reuse. They have
**two roles**: (1) one-tap model selection when starting a report
or chat (this doc's first half), and (2) the **worker swarm** that
single-call internal kinds — rerank / moderation / meta / fan-in /
translate / icon+title / transrank — dispatch through (second
half). They nest:

| Worker | Icon | Is | Holds |
|---|---|---|---|
| **Agent** | 🤖 | one named (provider + model) config | optional Parameters presets + optional system prompt + optional per-agent API key / endpoint |
| **Flock** | 🦆 | a named group of **Agents** | the member agent ids + its own Parameters presets + system prompt |
| **Swarm** | 🐝 | a named group of **(provider, model)** pairs | the member pairs + its own Parameters presets + system prompt |

The bundled `workers` swarm now uses `openai/gpt-oss-20b` for both Groq and Together. An unchanged older bundled pool is migrated on startup while retaining its ID and references; custom or edited pools are preserved. Together's primary default also uses this model. Provider defaults and swarm selections are separate settings and are updated independently. A worker returning `model_not_available` is excluded for the rest of the session, just like a missing model, so one unavailable dedicated endpoint is not retried for every metadata item.

All three live on `Settings` (`agents`, `flocks`, `swarms`) and
round-trip through Import/Export. The three glyphs are the
factory `MetadataDefaults` constants `AGENT`/`FLOCK`/`SWARM`
([`MetadataDefaults.kt:121`](../ai/src/main/java/com/ai/data/MetadataDefaults.kt),
`:165`, `:161`) and are user-overridable via the metadata-icons
editor.

## Data classes

From [`model/SettingsModels.kt`](../ai/src/main/java/com/ai/model/SettingsModels.kt) (`:109`):

```kotlin
data class Agent(
    val id: String, val name: String, val provider: AppService, val model: String,
    val apiKey: String, val endpointId: String? = null,
    val paramsIds: List<String> = emptyList(), val systemPromptId: String? = null
)
data class Flock(
    val id: String, val name: String, val agentIds: List<String> = emptyList(),
    val paramsIds: List<String> = emptyList(), val systemPromptId: String? = null
)
data class SwarmMember(val provider: AppService, val model: String)
data class Swarm(
    val id: String, val name: String, val members: List<SwarmMember> = emptyList(),
    val paramsIds: List<String> = emptyList(), val systemPromptId: String? = null
)
```

Field notes:

- **Agent `apiKey` / `model` are overrides, not requirements.**
  Both fall back to the provider's stored value through the
  resolver helpers `getEffectiveModelForAgent` and
  `getEffectiveApiKeyForAgent`
  ([`SettingsModels.kt:724`](../ai/src/main/java/com/ai/model/SettingsModels.kt),
  `:723`):

  ```kotlin
  fun getEffectiveApiKeyForAgent(agent) = agent.apiKey.ifBlank { getApiKey(agent.provider) }
  fun getEffectiveModelForAgent(agent)  = agent.model.ifBlank  { getModel(agent.provider) }
  ```

  `getModel(service)` returns `service.defaultModel`
  (`:368`) and `getApiKey(service)` returns the stored
  provider key (`:366`). So an agent left on a provider's
  default-model alias resolves at use time, not at save time.
- **Flock stores agent *ids***, not copies —
  `getAgentsForFlock` maps them back live
  ([`SettingsModels.kt:797`](../ai/src/main/java/com/ai/model/SettingsModels.kt)),
  so editing an agent updates every flock that references it.
  Ids that no longer resolve are silently skipped
  (`mapNotNull`).
- **Swarm members are bare pairs** — no agent id, no key
  override; they carry only the swarm's own `paramsIds`.
- A reserved flock named **`default agents`**
  (`DEFAULT_AGENTS_FLOCK_NAME`,
  [`SettingsModels.kt:123`](../ai/src/main/java/com/ai/model/SettingsModels.kt))
  is auto-managed: the per-provider **Test** button (via
  `markProviderTestedOk` →
  `Settings.ensureDefaultAgentInFlock`,
  [`AppViewModel.kt:1468`](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt) /
  [`SettingsModels.kt:942`](../ai/src/main/java/com/ai/model/SettingsModels.kt))
  adds the provider's tested model as an agent and joins it to this
  flock, and the **Refresh All → Providers / models / default
  agents** step (and the standalone **Refresh workers** step)
  empties it first
  ([`AppViewModel.kt:1790`](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt),
  `:1849`) then repopulates it from each provider's worker phase
  (`runWorkerPhase`, `:2038`).

Full field tables: [datastructures.md](datastructures.md).

## Bundled seeds

Swarms and flocks ship pre-seeded from `assets/`. **As of the
per-item split each bundled item is its own JSON file** — no more
single `workers/swarms.json` / `workers/flocks.json`:

- `assets/workers/swarms/<name>.json` — one file per swarm
  ([`SwarmSeed.kt`](../ai/src/main/java/com/ai/data/SwarmSeed.kt),
  `DIR = "workers/swarms"`). Each is a whole `Swarm`
  (`{ id, name, members:[{provider,model}], paramsIds }`); the
  filename is cosmetic, the in-file `name` is authoritative.
  Member provider strings resolve to `AppService` through the same
  `createAppGson` adapter Import/Export uses.
- `assets/workers/flocks/<name>.json` — one file per flock
  ([`FlockSeed.kt`](../ai/src/main/java/com/ai/data/FlockSeed.kt),
  `DIR = "workers/flocks"`). Members are stored **by agent name**
  (`agentNames`, with a legacy `agentIds` fallback) so the bundle
  is portable; they're re-linked to install-local agent ids
  against the current agent set at load — exactly like the
  Import/Export re-link path.

The bundled set that currently ships:

| File | Kind | Name | Members |
|---|---|---|---|
| `swarms/workers.json` | Swarm | `workers` | Mistral `mistral-medium-latest`, OpenAI `gpt-4o-mini`, Groq `llama-3.3-70b-versatile`, Cerebras `gpt-oss-120b`, DeepSeek `deepseek-v4-flash`, Google `gemini-3.5-flash`, Anthropic `claude-haiku-4-5-20251001`, xAI `grok-4.20-0309-non-reasoning`, Cohere `command-r-08-2024`, DeepInfra `google/gemma-3-12b-it`, Together `Qwen/Qwen3-235B-A22B-Instruct-2507-tput`, SiliconFlow `Qwen/Qwen3-14B` |
| `swarms/level-1.json` | Swarm | `Level 1` | Anthropic `claude-haiku-4-5-20251001`, DeepSeek `deepseek-v4-flash`, Mistral `mistral-small-latest`, OpenAI `gpt-5.4-nano`, Google `gemini-2.5-flash-lite`, xAI `grok-4-1-fast-non-reasoning` |
| `swarms/level-2.json` | Swarm | `Level 2` | Anthropic `claude-sonnet-4-6`, DeepSeek `deepseek-chat`, Mistral `mistral-medium-latest`, OpenAI `gpt-5.4-mini`, xAI `grok-4.3`, Google `gemini-2.5-flash` |
| `swarms/level-3.json` | Swarm | `Level 3` | Anthropic `claude-opus-4-7`, DeepSeek `deepseek-v4-pro`, Google `gemini-3.1-pro-preview`, Mistral `mistral-large-latest`, xAI `grok-4.20-0309-reasoning`, OpenAI `gpt-5.5` |
| `flocks/cheap.json` | Flock | `cheap` | 10 agents by name (Cerebras, Anthropic, DeepSeek, Moonshot, xAI, Google, Z.AI, OpenAI, Groq, Mistral) |

`workers` is the swarm the worker-prompt chains point at (see
"The worker swarm" below) — there is **no separate `tournament`
swarm**; the bundled `tournament` worker prompt also targets
`workers`. The `Level 1/2/3` swarms are curated cheap→capable
tiers offered as one-tap report picks.

**Merge is idempotent and non-destructive.** On launch
`AppViewModel` reads the assets and calls
`SwarmSeed.ensureAllPresent` / `FlockSeed.ensureAllPresent`
([`AppViewModel.kt:810`](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt),
`:833`): any bundled item whose name (case-insensitive) is not
already present is appended with a **fresh UUID**; existing rows —
including user edits to a same-named swarm — are left strictly
alone. A single unparseable / unknown-provider file is skipped,
not fatal. Flocks seed after agents so their `agentNames` resolve.

## The AI Workers setup hub

`Settings → AI Setup → Workers` opens `WorkersSetupScreen`
([`SetupScreens.kt:215`](../ai/src/main/java/com/ai/ui/settings/SetupScreens.kt)),
a sub-hub with three nav cards plus a model-search entry. The AI
Setup landing's own **Workers** card (subtitle "Agents, Flocks,
and Swarms", badge = `agentCount + flocks.size + swarms.size`) and
all sub-cards are **gated on `hasApiKey`** (`Settings.hasAnyApiKey()`,
[`SettingsModels.kt:719`](../ai/src/main/java/com/ai/model/SettingsModels.kt)) —
disabled until at least one provider has a key set
([`SetupScreens.kt:50`](../ai/src/main/java/com/ai/ui/settings/SetupScreens.kt),
`:81`, `:240-246`). The **Agents** card badge counts only agents
whose provider is **active** (`isProviderActive` ⇒ provider
state `"ok"`,
[`SettingsModels.kt:360`](../ai/src/main/java/com/ai/model/SettingsModels.kt));
`agentCount` at `SetupScreens.kt:56`/`:223`. The Flocks and
Swarms badges show the raw `flocks.size` / `swarms.size`.

Nav-card subtitles (verbatim in source): Agents "Named model
configurations", Flocks "Groups of agents", Swarms "Groups of
provider/model pairs".

| Card | Route (`SettingsSubScreen`) | CRUD entry |
|---|---|---|
| Agents | `AI_AGENTS` | `AgentsCrud` |
| Flocks | `AI_FLOCKS` | `FlocksCrud` |
| Swarms | `AI_SWARMS` | `SwarmsCrud` |

Routing + back-stack live in
[`SettingsScreen.kt`](../ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt)
(the `SettingsSubScreen` enum at `:32`; the three list sub-screens
back out to `AI_WORKERS_SETUP` at `:267-268`, which in turn backs
out to `AI_SETUP`).

## CRUD shape

Each worker CRUD is a small `Mode` state machine — **`List` /
`Edit` / `Add`** (no separate read-only View step) — over the
shared `CrudListPage` framework
([`cruds/workers/agents/list.kt:15`](../ai/src/main/java/com/ai/ui/cruds/workers/agents/list.kt)):

- [`cruds/workers/agents/`](../ai/src/main/java/com/ai/ui/cruds/workers/agents/list.kt) — `list.kt` / `add.kt` / `edit.kt`
- [`cruds/workers/flocks/`](../ai/src/main/java/com/ai/ui/cruds/workers/flocks/list.kt)
- [`cruds/workers/swarms/`](../ai/src/main/java/com/ai/ui/cruds/workers/swarms/list.kt)

**Tapping a row jumps straight to Edit** — the `CrudListPage`'s
`onView` handler is wired to `Mode.Edit(it)`, so the read-only
view is skipped and the 👯 **copy** + 🗑 **delete** icons live on
the edit bar. `onAdd` opens `Mode.Add`.

The thin CRUD `Edit`/`Add` wrappers delegate to the rich legacy
forms — `AgentEditScreen`
([`AgentsScreen.kt:23`](../ai/src/main/java/com/ai/ui/settings/AgentsScreen.kt)),
`FlockEditScreen`
([`FlocksScreen.kt:22`](../ai/src/main/java/com/ai/ui/settings/FlocksScreen.kt)),
`SwarmEditScreen`
([`SwarmsScreen.kt:22`](../ai/src/main/java/com/ai/ui/settings/SwarmsScreen.kt)).
The agent form additionally needs `AgentEditDeps` (model fetch,
Test button, fetch-error map, endpoint persistence, trace-link
callback;
[`agents/edit.kt:13`](../ai/src/main/java/com/ai/ui/cruds/workers/agents/edit.kt)).

**Copy** is the 👯 duplicate affordance inside the legacy edit
form: it appends `-copy` to the name
([`AgentsScreen.kt:92`](../ai/src/main/java/com/ai/ui/settings/AgentsScreen.kt))
and a Save in that state writes a fresh `UUID`
(`AgentsScreen.kt:147`), so the duplicate lands as a new item
rather than overwriting the original; the duplicate name is
treated as a collision against the source so the user can't save
`<name>-copy` over it. **Save** upserts by id (replace-if-id-
present, else append;
[`agents/list.kt:37-42`](../ai/src/main/java/com/ai/ui/cruds/workers/agents/list.kt)).
**Delete** is a confirm-dialog that calls `removeAgent` /
filters the list.

The legacy **view** screens (`AgentViewScreen` /
`FlockViewScreen` / `SwarmViewScreen`) — reachable from older
entry points outside the new CRUD list — each render their
prompt + params via the shared `WorkerSharedCards`
([`CommonViewWorkerCards.kt:31`](../ai/src/main/java/com/ai/ui/settings/CommonViewWorkerCards.kt)),
which is identical for all three because they share the
`paramsIds` + `systemPromptId` shape.

Help topics: `crud_agents`, `crud_flocks`, `crud_swarms`
([`CrudHelp.kt:175`](../ai/src/main/java/com/ai/ui/admin/CrudHelp.kt))
and `setup_workers`
([`SettingsAdminHelp.kt:557`](../ai/src/main/java/com/ai/ui/admin/SettingsAdminHelp.kt)).

## How workers feed model selection

Workers are never "run" directly — they **expand into
`ReportModel` targets** during the report/chat selection phase.
`SelectionOverlays`
([`report/start/SelectionOverlays.kt`](../ai/src/main/java/com/ai/ui/report/start/SelectionOverlays.kt))
wires the three pickers to expander functions in
[`SettingsModels.kt:1035`](../ai/src/main/java/com/ai/model/SettingsModels.kt):

| Pick | Expander | Yields |
|---|---|---|
| Agent | `expandAgentToModel` (`:1042`) | **one** target (null if provider inactive) |
| Flock | `expandFlockToModels` (`:1035`) | one target **per active member agent** |
| Swarm | `expandSwarmToModels` (`:1048`) | one target **per active member pair** |

Inactive-provider members are silently dropped (every expander
filters on `isProviderActive`). The resulting `ReportModel`
carries a `type`/`sourceType` tag and the resolved key/params:

- **Agent** → `ReportModel(provider, effectiveModel, "agent",
  "agent", agent.name, agent.id, agentId = agent.id,
  endpointId, effectiveApiKey, agent.paramsIds)`.
- **Flock** → one per member, same as the agent target but with
  `sourceType = "flock"`, `sourceId = flock.id`, and
  `paramsIds = flock.paramsIds + agent.paramsIds` (the flock
  **prepends** its own ids to each agent's).
- **Swarm** → `ReportModel(member.provider, member.model,
  "model", "swarm", swarm.name, swarm.id,
  paramsIds = swarm.paramsIds)` — no `agentId`, no key
  override.

Pickers add into the active target bucket, then the whole list
is run through `deduplicateModels`
([`SettingsModels.kt:1054`](../ai/src/main/java/com/ai/model/SettingsModels.kt)).
Dedup keys by `deduplicationKey = "${provider.id}:$model"`
(`:1032`) into a `LinkedHashMap` (first-appearance order
preserved); on a collision an **agent-sourced** entry (non-null
`agentId` — a direct agent or a flock member) replaces a bare
manual / swarm pick, so the surviving row keeps the api key and
parameter ids.

The flock and swarm pickers
([`ui/other/Selection.kt`](../ai/src/main/java/com/ai/ui/other/Selection.kt))
show a per-worker cost estimate (summed across **active**
members), an active-vs-total member count, and an ℹ️ info screen
listing each member with provider, model, capability badges, and
cost. Chat reuses the agent picker directly: picking an agent
navigates to `aiChatWithAgent(id)`
([`NavRoutes.kt:229`](../ai/src/main/java/com/ai/ui/navigation/NavRoutes.kt),
call site
[`ChatRoutes.kt:79`](../ai/src/main/java/com/ai/ui/navigation/ChatRoutes.kt)).

## The worker swarm — single-call secondary dispatch

The second role. The "workers"-category `InternalPrompt`s that
drive the internal kinds (rerank, moderation, meta, fan-in,
translate-text / translate-title / translate-rank, find-translation,
report/model/translation icons + titles, tournament, user-note)
each carry an ordered **`workers` fallback chain**
([`SettingsModels.kt:258`](../ai/src/main/java/com/ai/model/SettingsModels.kt)).
Every bundled worker prompt ships with a single chain entry — the
`workers` **swarm** — so the cheap 12-member `workers` swarm is the
default model pool for all of these single-call kinds. (There's no
`tournament`-specific pool; `tournament.json` also points at
`workers`.)

A `Worker`
([`SettingsModels.kt:283`](../ai/src/main/java/com/ai/model/SettingsModels.kt))
is one of four kinds, told apart by which field is non-`*N/A`:
**Model** (`provider`+`model`), **Agent** (`agent` name),
**Flock** (`flock` name), **Swarm** (`swarm` name).
`Settings.expandWorker`
([`SettingsModels.kt:781`](../ai/src/main/java/com/ai/model/SettingsModels.kt))
flattens a Flock → one agent-name worker per **active** member
agent and a Swarm → one provider+model worker per **active**
member, so each member becomes its own fallback candidate;
`resolveWorker` (`:769`) turns a single worker into a dispatchable
`Agent` (a pinned provider+model yields a synthetic agent; a named
agent is looked up in `agents`).

Two engines consume the chain, both **shuffling** the expanded
members so a single-call kind picks one at random and a batch
spreads across the pool:

- **`SecondaryRunManager.runSecondaryViaSwarm`**
  ([`SecondaryRunManager.kt:84`](../ai/src/main/java/com/ai/viewmodel/SecondaryRunManager.kt))
  — one single-result secondary row (rerank / moderation / meta /
  fan-in / translate / transrank). Expands the swarm, tries
  members in shuffled order, re-stamps the placeholder's
  provider/model to whoever is being tried (so success attributes
  the row to the model that answered), parks a 429/529 member on a
  short cooldown (`WORKER_429_DEFAULT_MS`) and falls through; the
  row stays ERROR only when the whole chain is spent.
- **`WorkerRunner`**
  ([`WorkerRunner.kt`](../ai/src/main/java/com/ai/viewmodel/WorkerRunner.kt))
  — the no-UI internal-prompt engine (report/model/language icons
  + titles). Random per-call order, per-worker 429 cooldown
  (honouring `Retry-After`), an `accept` predicate that treats a
  200 with no usable artifact as a logical miss, and a
  session-scoped disable for 404/410 ("model gone") members. A
  pass where EVERY candidate was cooling waits for the earliest
  cooldown to lift and re-runs the chain (up to 3 extra passes,
  20 s per-wait cap) before settling on `AllRateLimited` — so a 5 s
  429 blip across the pool doesn't mass-error a batch. Under a
  throttled batch the wait runs permit-free: `runThrottledBatch`'s
  dynamic-host path installs `ProviderThrottle.poolCoolingWaiter`,
  which releases the item's sub-cap + global permits for the sleep
  and re-acquires them before the next pass, so cooling items don't
  throttle the rest of the batch. See
  [report-icons.md](report-icons.md) for the full engine.

**Run-time worker source** — a worker prompt's
`modelSelection` ([`SettingsModels.kt:266`](../ai/src/main/java/com/ai/model/SettingsModels.kt))
is `*CONFIGURED` (default — run against the prompt's saved
`workers`) or `*SELECT` (show the +Agent/+Flock/+Swarm/+Model
picker just before the work starts and run against the user's
pick; never written back).

### Per-report worker config — "Report - setup"

The old single ♻️ models-as-workers flag is replaced by a per-report
`Report.workerConfig: ReportWorkerConfig`
([`ReportModels.kt`](../ai/src/main/java/com/ai/data/ReportModels.kt)),
picked on the **"Report - setup"** screen (composable
`ReportSelectWorkersScreen`,
[`SelectWorkers.kt`](../ai/src/main/java/com/ai/ui/report/start/SelectWorkers.kt))
— the step between "Report - select models" and "Manage a report"
that also hosts the **Generate report** button — and editable later
via the Manage bottom-bar **👷** icon (same screen, Save instead of
Generate; `ReportStorage.setWorkerConfig`). "Report - setup" itself
holds the System prompt, Parameters and Second result options cards
plus a **Workers** nav card; tapping Workers opens a nested
full-screen **Workers** step (`ReportWorkersScreen`, same file) that
holds the four worker-routing cards:

- **Report info** (`reportInfo`: `PROMPT` | `CUSTOM` +
  `reportInfoWorkers`) — who generates the report icon, short/long
  titles, and language name + icon. `CUSTOM` swaps each prompt's
  chain for the per-report worker group
  (`InternalPrompt.withReportInfoWorkers`).
- **Model info** (`modelInfo`: `PROMPT` | `OWN_MODEL`) — who
  generates each answer's per-model title + icon. `OWN_MODEL` runs
  them on the answer's own model
  (`InternalPrompt.withOwnModelWorker` → `singleModelWorker`).
- **Worker batches** ("Batches" card; `batches`: `PROMPT` |
  `REPORT_MODELS` | `SELECT_EACH` | `SELECT_ONCE` + `batchWorkers`,
  `workerSelection`: `WHEN_AVAILABLE` | `ROUND_ROBIN`) — the pool
  for Fan Meta, Translation, Tournament, Compare.
  **Rerank / Moderation** are exempt — they pass `alwaysPromptWorkers
  = true`, so they always run on their own prompt's workers.
  **Judge-the-judges / Rank-the-translators** have no worker selection
  at all: each reuses the participants of the batch it evaluates —
  Judge-the-judges judges with the distinct judges recorded on the
  report's completed Tournament MATCH rows; Rank-the-translators ranks
  with the distinct translation models of the connected Translation run
  (`translatorJudges`). Both are type-A fixed-judge batches and their
  launches no longer open a worker picker.
  **Meta / Fan-in** route on their own mirror set
  (`metaBatches` / `metaBatchWorkers` / `metaWorkerSelection`, same
  option set) — the "Meta" card on Report - setup — so they can draw
  from a different pool than the other batches. Resolution passes
  `meta = true` to `resolveBatchSwarm` / `workerPlanFor` /
  `workerScheduleFor` / `launchWithWorkerPlan` for those two.

A **"Use report models"** switch at the top of the Workers step
(`useReportModels`) is a one-tap shortcut that force-sets `modelInfo`
= `OWN_MODEL`, `batches` = `REPORT_MODELS` + `workerSelection` =
`ROUND_ROBIN`, and `metaBatches` = `REPORT_MODELS` + `metaWorkerSelection`
= `ROUND_ROBIN`, hiding the Model info / Batches / Meta cards underneath
it while it's on. The "Report - setup" screen's own **Second result
options** card (`secondResultSelectScope`, `secondResultRuntimeParams`)
is unrelated to worker routing — it gates whether launching a Meta /
Fan-out secondary from the second-results hub first shows a scope
picker / a runtime prompt editor, or runs straight with the defaults.

Batch-pool precedence lives in one place — `resolveBatchSwarm` /
`InternalPrompt.withBatchWorkers`
([`ReportViewModelHelpers.kt`](../ai/src/main/java/com/ai/viewmodel/ReportViewModelHelpers.kt)):
`REPORT_MODELS` (→ `reportModelWorkers(report)`, the report's own
answer models distinct by `provider:model`) > an explicit runtime
pick > a persisted `SELECT_ONCE` group > the prompt's configured
chain. The UI side of the same decision is `workerPlanFor` +
`launchWithWorkerPlan`
([`RuntimeWorkerPicker.kt`](../ai/src/main/java/com/ai/ui/report/manage/RuntimeWorkerPicker.kt)),
used by all eleven type-B launch sites: `PROMPT` honours the prompt's
`*SELECT` picker; `SELECT_EACH` forces the picker on every batch;
`SELECT_ONCE` shows it once — the first pick is persisted
first-write-wins via `ReportStorage.setBatchWorkersIfEmpty` and
reused by every later batch (engine-internal launches — fan-meta
auto-start, resume, regenerate — never show a picker and fall back
to the configured chain until a group is stored); `REPORT_MODELS`
never asks.

**Worker selection (round robin)** — under `REPORT_MODELS` the
`workerSelection` sub-choice picks the scheduling:
`WHEN_AVAILABLE` keeps the historical shuffled pick (fast models
absorb more work); `ROUND_ROBIN` deals consecutive calls to
consecutive pool members via a process-wide per-report cursor
(`WorkerRotation` + `WorkerSchedule`,
[`WorkerRunner.kt`](../ai/src/main/java/com/ai/viewmodel/WorkerRunner.kt)),
so every worker gets ~the same share. A slow worker keeps its items
(nothing reassigns on slowness); 429 / 404 / logical-miss
fall-through advances an item to the next worker in rotation, so an
erroring worker may end up with less. `WorkerRunner.run` and
`runSecondaryViaSwarm` share the cursor; the engines thread the
schedule through `runPooledItemCall`
([`SecondaryCellCalls.kt`](../ai/src/main/java/com/ai/viewmodel/SecondaryCellCalls.kt)).
Fixed-judge grids (Judges / TransRank cells) and the fan-meta
per-pair single-model pool have no pool pick, so the mode is a
no-op there.

## Parameter + system-prompt resolution

Agents (and flocks/swarms) carry `paramsIds` + `systemPromptId`
references. The full merge precedence — how multiple presets
fold (`mergeParameters`,
[`SettingsModels.kt:1003`](../ai/src/main/java/com/ai/model/SettingsModels.kt)),
how the report-generation agent / swarm chains layer
selection / report-level / app-wide overrides, and how an agent
system prompt combines with a Parameters preset's own prompt —
is documented once in the dedicated docs:

- [parameters.md](parameters.md) — Parameters presets + merge precedence.
- [system-prompts.md](system-prompts.md) — system-prompt resolution.

In brief: `resolveAgentParameters(agent) =
mergeParameters(agent.paramsIds) ?: AgentParameters()`
(`SettingsModels.kt:722`); `mergeParameters` folds presets
left→right with "later non-null wins" for scalar/string fields,
OR for `responseFormatJson`/`searchEnabled`/`webSearchTool`, and
AND for `returnCitations`.

## Related docs

- [parameters.md](parameters.md) — Parameters presets + merge precedence.
- [system-prompts.md](system-prompts.md) — system-prompt resolution.
- [datastructures.md](datastructures.md) — full field tables for `Agent` / `Flock` / `Swarm` / `ReportModel`.
- [report-icons.md](report-icons.md) — the worker engine that drives per-report / per-model / fan-meta internal-prompt calls (`WorkerRunner` picks a random worker per call).
- [secondary-results.md](secondary-results.md) — fan-out / fan-in over the expanded set of targets.
