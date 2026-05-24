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
round-trip through Import/Export.

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
  Both fall back to the provider's stored value — `model.ifBlank
  { getModel(provider) }` and `apiKey.ifBlank {
  getApiKey(provider) }` ([`SettingsModels.kt:637`](../ai/src/main/java/com/ai/model/SettingsModels.kt),
  `:638`). So an agent pinned to a provider's default-model
  alias resolves at use time.
- **Flock stores agent *ids***, not copies —
  `getAgentsForFlock` maps them back live
  ([`SettingsModels.kt:660`](../ai/src/main/java/com/ai/model/SettingsModels.kt)),
  so editing an agent updates every flock that references it.
- **Swarm members are bare pairs** — no agent id, no key
  override; they carry only the swarm's own `paramsIds`.
- A reserved flock named **`default agents`**
  (`DEFAULT_AGENTS_FLOCK_NAME`,
  [`SettingsModels.kt:123`](../ai/src/main/java/com/ai/model/SettingsModels.kt))
  is auto-populated by the per-provider Test button and Refresh
  All → Default agents step.

Full field tables: [datastructures.md](datastructures.md).

## The AI Workers setup hub

`Settings → AI Setup → Workers` opens `WorkersSetupScreen`
([`SetupScreens.kt:175`](../ai/src/main/java/com/ai/ui/settings/SetupScreens.kt)),
a sub-hub with three nav cards. The AI Setup landing's own
"Workers" card and all three sub-cards are **gated on
`hasApiKey`** (`Settings.hasAnyApiKey()`,
[`SettingsModels.kt:633`](../ai/src/main/java/com/ai/model/SettingsModels.kt)) —
disabled until at least one provider has a key set
([`SetupScreens.kt:69`](../ai/src/main/java/com/ai/ui/settings/SetupScreens.kt),
`:190-195`). The Agents card badge counts only agents whose
provider is **active** (`isProviderActive` ⇒ provider state
`"ok"`).

| Card | Route (`SettingsSubScreen`) | CRUD entry |
|---|---|---|
| Agents | `AI_AGENTS` | `AgentsCrud` |
| Flocks | `AI_FLOCKS` | `FlocksCrud` |
| Swarms | `AI_SWARMS` | `SwarmsCrud` |

Routing + back-stack live in
[`SettingsScreen.kt`](../ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt)
(the `SettingsSubScreen` enum at `:28` and the `when` block
mapping each list back to `AI_WORKERS_SETUP`).

## CRUD shape

Each worker is a self-contained `Mode` state machine
(`List / View / Edit / Add`) over the shared `CrudListPage` /
`CrudViewPage` framework:

- [`cruds/workers/agents/`](../ai/src/main/java/com/ai/ui/cruds/workers/agents/list.kt) — list / add / edit / view
- [`cruds/workers/flocks/`](../ai/src/main/java/com/ai/ui/cruds/workers/flocks/list.kt)
- [`cruds/workers/swarms/`](../ai/src/main/java/com/ai/ui/cruds/workers/swarms/list.kt)

The thin CRUD `*Edit` wrappers delegate to the rich legacy
forms — `AgentEditScreen`
([`AgentsScreen.kt:24`](../ai/src/main/java/com/ai/ui/settings/AgentsScreen.kt)),
`FlockEditScreen`
([`FlocksScreen.kt:23`](../ai/src/main/java/com/ai/ui/settings/FlocksScreen.kt)),
`SwarmEditScreen`
([`SwarmsScreen.kt:23`](../ai/src/main/java/com/ai/ui/settings/SwarmsScreen.kt)).
The agent form additionally needs `AgentEditDeps` (model
fetch, Test button, endpoint persistence, trace links;
[`agents/edit.kt:13`](../ai/src/main/java/com/ai/ui/cruds/workers/agents/edit.kt)).
**Copy** clones with a fresh `UUID` and a `-copy` name suffix
and drops straight into Edit; **Save** upserts by id. Name
uniqueness is enforced case-insensitively against the other
items.

Every View screen ends with `WorkerSharedCards` — identical
System-prompt + Parameters render for all three since they share
the `paramsIds` + `systemPromptId` shape
([`CommonViewWorkerCards.kt:32`](../ai/src/main/java/com/ai/ui/settings/CommonViewWorkerCards.kt)).
Help topics: `crud_agents`, `crud_flocks`, `crud_swarms`,
`setup_workers`.

## How workers feed model selection

Workers are never "run" directly — they **expand into
`ReportModel` targets** during the report/chat selection phase.
`SelectionOverlays`
([`report/start/SelectionOverlays.kt:144-181`](../ai/src/main/java/com/ai/ui/report/start/SelectionOverlays.kt))
wires the three pickers to expander functions in
[`SettingsModels.kt:904`](../ai/src/main/java/com/ai/model/SettingsModels.kt):

| Pick | Expander | Yields |
|---|---|---|
| Agent | `expandAgentToModel` | **one** target (null if provider inactive) |
| Flock | `expandFlockToModels` | one target **per active member agent** |
| Swarm | `expandSwarmToModels` | one target **per active member pair** |

Inactive-provider members are silently dropped. Agent- and
flock-sourced targets carry the resolved api key, endpoint, and
`paramsIds` (flock prepends its own ids to each agent's);
swarm-sourced targets carry only the swarm's `paramsIds` and no
key. Pickers add into the active target bucket and the result
is run through `deduplicateModels`
([`SettingsModels.kt:923`](../ai/src/main/java/com/ai/model/SettingsModels.kt)),
where on a provider+model collision an **agent-sourced** entry
(non-null `agentId`) beats a bare manual / swarm pick — so it
keeps the key and parameter ids.

The flock and swarm pickers
([`ui/other/Selection.kt`](../ai/src/main/java/com/ai/ui/other/Selection.kt))
show a per-worker cost estimate (summed across active members)
and an ℹ️ info screen listing members. Chat reuses the agent
picker directly: picking an agent navigates to
`aiChatWithAgent(id)`
([`navigation/ChatRoutes.kt:76`](../ai/src/main/java/com/ai/ui/navigation/ChatRoutes.kt)).

## Parameter + system-prompt resolution

Agents (and flocks/swarms) carry `paramsIds` + `systemPromptId`
references; the actual merge precedence — how multiple presets
combine and how an agent system prompt layers with a Parameters
preset's own prompt — is **not** re-documented here. See
[parameters.md](parameters.md) and
[system-prompts.md](system-prompts.md).

## Related docs

- [parameters.md](parameters.md) — Parameters presets + merge precedence.
- [system-prompts.md](system-prompts.md) — system-prompt resolution.
- [datastructures.md](datastructures.md) — full field tables for `Agent` / `Flock` / `Swarm` / `ReportModel`.
- [report-icons.md](report-icons.md) — the per-agent icon chain (an agent can be pinned to drive internal-prompt calls).
- [secondary-results.md](secondary-results.md) — fan-out / fan-in over the expanded set of targets.
