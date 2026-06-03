# AI Workers — Agents, Flocks, Swarms

Three saved "worker" types let the user bundle model picks (and
their parameter / prompt resolution) for one-tap reuse when
starting a report or chat. They nest:

| Worker | Icon | Is | Holds |
|---|---|---|---|
| **Agent** | 🤖 | one named (provider + model) config | optional Parameters presets + optional system prompt + optional per-agent API key / endpoint |
| **Flock** | 🦆 | a named group of **Agents** | the member agent ids + its own Parameters presets + system prompt |
| **Swarm** | 🐝 | a named group of **(provider, model)** pairs | the member pairs + its own Parameters presets + system prompt |

All three live on `Settings` (`agents`, `flocks`, `swarms`) and
round-trip through Import/Export. The three glyphs are the
factory `MetadataDefaults` constants `AGENT`/`FLOCK`/`SWARM`
([`MetadataDefaults.kt:119`](../ai/src/main/java/com/ai/data/MetadataDefaults.kt),
`:159`, `:160`) and are user-overridable via the metadata-icons
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
  ([`SettingsModels.kt:686`](../ai/src/main/java/com/ai/model/SettingsModels.kt),
  `:685`):

  ```kotlin
  fun getEffectiveApiKeyForAgent(agent) = agent.apiKey.ifBlank { getApiKey(agent.provider) }
  fun getEffectiveModelForAgent(agent)  = agent.model.ifBlank  { getModel(agent.provider) }
  ```

  `getModel(service)` returns `service.defaultModel`
  (`:345`) and `getApiKey(service)` returns the stored
  provider key (`:343`). So an agent left on a provider's
  default-model alias resolves at use time, not at save time.
- **Flock stores agent *ids***, not copies —
  `getAgentsForFlock` maps them back live
  ([`SettingsModels.kt:759`](../ai/src/main/java/com/ai/model/SettingsModels.kt)),
  so editing an agent updates every flock that references it.
  Ids that no longer resolve are silently skipped
  (`mapNotNull`).
- **Swarm members are bare pairs** — no agent id, no key
  override; they carry only the swarm's own `paramsIds`.
- A reserved flock named **`default agents`**
  (`DEFAULT_AGENTS_FLOCK_NAME`,
  [`SettingsModels.kt:123`](../ai/src/main/java/com/ai/model/SettingsModels.kt))
  is auto-managed: the per-provider **Test** button adds the
  provider's tested model as an agent and joins it to this flock
  ([`AppViewModel.kt:1294`](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt)),
  and the **Refresh All → Providers / models / default agents**
  step empties and repopulates it
  ([`AppViewModel.kt:1587`](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt),
  `:1646`).

Full field tables: [datastructures.md](datastructures.md).

## The AI Workers setup hub

`Settings → AI Setup → Workers` opens `WorkersSetupScreen`
([`SetupScreens.kt:189`](../ai/src/main/java/com/ai/ui/settings/SetupScreens.kt)),
a sub-hub with three nav cards plus a model-search entry. The AI
Setup landing's own **Workers** card (subtitle "Agents, Flocks,
and Swarms") and all sub-cards are **gated on `hasApiKey`**
(`Settings.hasAnyApiKey()`,
[`SettingsModels.kt:681`](../ai/src/main/java/com/ai/model/SettingsModels.kt)) —
disabled until at least one provider has a key set
([`SetupScreens.kt:46`](../ai/src/main/java/com/ai/ui/settings/SetupScreens.kt),
`:76`, `:216-220`). The **Agents** card badge counts only agents
whose provider is **active** (`isProviderActive` ⇒ provider
state `"ok"`,
[`SettingsModels.kt:337`](../ai/src/main/java/com/ai/model/SettingsModels.kt));
`agentCount` at `SetupScreens.kt:52`/`:197`. The Flocks and
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
back out to `AI_WORKERS_SETUP` at `:232-233`, which in turn backs
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
([`AgentsScreen.kt:24`](../ai/src/main/java/com/ai/ui/settings/AgentsScreen.kt)),
`FlockEditScreen`
([`FlocksScreen.kt:23`](../ai/src/main/java/com/ai/ui/settings/FlocksScreen.kt)),
`SwarmEditScreen`
([`SwarmsScreen.kt:23`](../ai/src/main/java/com/ai/ui/settings/SwarmsScreen.kt)).
The agent form additionally needs `AgentEditDeps` (model fetch,
Test button, fetch-error map, endpoint persistence, trace-link
callback;
[`agents/edit.kt:13`](../ai/src/main/java/com/ai/ui/cruds/workers/agents/edit.kt)).

**Copy** is the 👯 duplicate affordance inside the legacy edit
form: it appends `-copy` to the name
([`AgentsScreen.kt:95`](../ai/src/main/java/com/ai/ui/settings/AgentsScreen.kt))
and a Save in that state writes a fresh `UUID`
(`AgentsScreen.kt:179`), so the duplicate lands as a new item
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
([`CommonViewWorkerCards.kt:32`](../ai/src/main/java/com/ai/ui/settings/CommonViewWorkerCards.kt)),
which is identical for all three because they share the
`paramsIds` + `systemPromptId` shape.

Help topics: `crud_agents`, `crud_flocks`, `crud_swarms`
([`CrudHelp.kt:175`](../ai/src/main/java/com/ai/ui/admin/CrudHelp.kt))
and `setup_workers`
([`SettingsAdminHelp.kt:491`](../ai/src/main/java/com/ai/ui/admin/SettingsAdminHelp.kt)).

## How workers feed model selection

Workers are never "run" directly — they **expand into
`ReportModel` targets** during the report/chat selection phase.
`SelectionOverlays`
([`report/start/SelectionOverlays.kt`](../ai/src/main/java/com/ai/ui/report/start/SelectionOverlays.kt))
wires the three pickers to expander functions in
[`SettingsModels.kt:1004`](../ai/src/main/java/com/ai/model/SettingsModels.kt):

| Pick | Expander | Yields |
|---|---|---|
| Agent | `expandAgentToModel` (`:1011`) | **one** target (null if provider inactive) |
| Flock | `expandFlockToModels` (`:1004`) | one target **per active member agent** |
| Swarm | `expandSwarmToModels` (`:1017`) | one target **per active member pair** |

Inactive-provider members are silently dropped (every expander
filters on `isProviderActive`). The resulting `ReportModel`
carries a `source`/`origin` tag and the resolved key/params:

- **Agent** → `ReportModel(provider, effectiveModel, "agent",
  "agent", agent.name, agent.id, agentId = agent.id,
  endpointId, effectiveApiKey, agent.paramsIds)`.
- **Flock** → one per member, same as the agent target but with
  `origin = "flock"`, `flockId = flock.id`, and
  `paramsIds = flock.paramsIds + agent.paramsIds` (the flock
  **prepends** its own ids to each agent's).
- **Swarm** → `ReportModel(member.provider, member.model,
  "model", "swarm", swarm.name, swarm.id,
  paramsIds = swarm.paramsIds)` — no `agentId`, no key
  override.

Pickers add into the active target bucket, then the whole list
is run through `deduplicateModels`
([`SettingsModels.kt:1023`](../ai/src/main/java/com/ai/model/SettingsModels.kt)).
Dedup keys by `deduplicationKey = "${provider.id}:$model"`
(`:1001`) into a `LinkedHashMap` (first-appearance order
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
([`NavRoutes.kt:218`](../ai/src/main/java/com/ai/ui/navigation/NavRoutes.kt),
call site
[`ChatRoutes.kt:76`](../ai/src/main/java/com/ai/ui/navigation/ChatRoutes.kt)).

## Parameter + system-prompt resolution

Agents (and flocks/swarms) carry `paramsIds` + `systemPromptId`
references. The full merge precedence — how multiple presets
fold (`mergeParameters`,
[`SettingsModels.kt:972`](../ai/src/main/java/com/ai/model/SettingsModels.kt)),
how the report-generation agent / swarm chains layer
selection / report-level / app-wide overrides, and how an agent
system prompt combines with a Parameters preset's own prompt —
is documented once in the dedicated docs:

- [parameters.md](parameters.md) — Parameters presets + merge precedence.
- [system-prompts.md](system-prompts.md) — system-prompt resolution.

In brief: `resolveAgentParameters(agent) =
mergeParameters(agent.paramsIds) ?: AgentParameters()`
(`SettingsModels.kt:684`); `mergeParameters` folds presets
left→right with "later non-null wins" for scalar/string fields,
OR for `responseFormatJson`/`searchEnabled`/`webSearchTool`, and
AND for `returnCitations`.

## Related docs

- [parameters.md](parameters.md) — Parameters presets + merge precedence.
- [system-prompts.md](system-prompts.md) — system-prompt resolution.
- [datastructures.md](datastructures.md) — full field tables for `Agent` / `Flock` / `Swarm` / `ReportModel`.
- [report-icons.md](report-icons.md) — the worker engine that drives per-report / per-model / fan-meta internal-prompt calls (`WorkerRunner` picks a random worker per call).
- [secondary-results.md](secondary-results.md) — fan-out / fan-in over the expanded set of targets.
