# Cost tracking, AI Usage, manual overrides

Every billable LLM / rerank call is costed from its token usage ×
the resolved per-model price. The same machinery feeds three
surfaces: the global **AI Usage** statistics screen, the per-report
**Costs** breakdown, and the **Model Info** per-model usage card.

## How a call is costed

`PricingCache.computeInOutCost(usage, pricing)`
(`data/PricingCache.kt:275`) turns a `TokenUsage` + a resolved
`ModelPricing` into `(inDollars, outDollars)`:

- Base: `inputTokens × promptPrice + outputTokens × completionPrice`.
- Cached input is billed at `cachedReadPrice` (falls back to
  `promptPrice`); cache-creation tokens at `cachedWritePrice`.
- **Above-200k tier** — when `inputTokens + cached + cacheCreation
  > 200 000` and `promptPriceAbove200k` is set, the whole call
  switches to the high-context rates (Gemini 2.5/3 Pro, DashScope
  Qwen-Long, …).
- **`apiCost` shortcut** — if the API returns a total, it is split
  pro-rata across input/output by the simple-rate baseline so
  callers still get two halves.
- **Rerank** bills per search-unit, not per token:
  `searchUnits × perQueryPrice` (Cohere `$2 / 1000 searches`).
  The token columns are zero by design.

`ModelPricing` (`data/PricingCache.kt:113`) carries all the rate
fields plus a `source` string (the tier that answered).

## Resolved pricing — layered lookup

`PricingCache.getPricing(context, provider, model)`
(`data/PricingCache.kt:369`) walks tiers top→bottom, first hit wins:

| # | Tier | `source` | Note |
|---|---|---|---|
| 1 | OpenRouter self | `OPENROUTER-SELF` | only when caller's provider *is* OpenRouter |
| 1 | Together self | `TOGETHER-SELF` | only when caller's provider *is* Together |
| 2 | **Manual override** | `OVERRIDE` | user-set per `(provider, model)` |
| 3 | LiteLLM | `LITELLM` | curated bulk |
| 4 | models.dev | `MODELSDEV` | curated bulk |
| 5 | llm-prices | `LLMPRICES` | curated bulk |
| 6 | Artificial Analysis | `AA` | curated bulk |
| 7 | OpenRouter cross-provider | `OPENROUTER` | only for non-OpenRouter callers |
| 8 | Helicone | `HELICONE` | last resort (known data-quality issues) |
| 9 | DEFAULT | — | `$0 / $0` |

The manual override sits **before** every curated tier: a user
adding an override specifically to correct a stale catalog entry
must win. `getPricingWithoutOverride` (`data/PricingCache.kt:408`)
mirrors the exact same precedence minus step 2 — it answers "what
would the layered price be without your override?" for the override
form's *Current:* line and for cleanup. The seven external sources
are documented in [repositories.md](repositories.md).

On the main thread before preload completes, `getPricing` returns
`DEFAULT_PRICING` and recomposition picks up real values once the
preload finishes — don't try to remove that guard.

## Usage statistics store

`SettingsPreferences.updateUsageStats(...)`
(`ui/settings/SettingsPreferences.kt:441`) accumulates one
`UsageStats` row per **`(provider, model, kind)`** — keyed
`"${provider.id}::$model::$kind"`. Rows are held in an in-memory
`ConcurrentHashMap` and flushed to `<filesDir>/usage-stats.json`
debounced (`USAGE_STATS_FLUSH_MS`); see
[persistent.md](persistent.md).

`UsageStats` (`model/SettingsModels.kt:992`) holds `callCount`,
`inputTokens`, `outputTokens`, `searchUnits`, and `kind`. The
`kind` field is one of:

| kind | recorded by |
|---|---|
| `report` (default) | primary report / chat generation |
| `rerank` | Cohere rerank (uses `searchUnits`) |
| `summarize` / `compare` / `moderation` / `translate` | secondary tasks |
| `meta` | fan-in / meta secondary |
| `title` | Find-alternative-title (`viewmodel/IconGenerationManager.kt:1676`) |

Legacy rows written before `kind` existed deserialize via Gson's
Unsafe path with a runtime-null `kind`; `loadUsageStats`
(`ui/settings/SettingsPreferences.kt:387`) backfills them to
`"report"`, and renderers defend again with
`(stat.kind as String?) ?: "report"`.

`clearUsageStats` resets every counter and deletes the file
(`ui/settings/SettingsPreferences.kt:479`).

## Spend & usage screen

Surfaced through the **AI Statistics** dashboard (`AiDashboardScreen`
in `ui/admin/AiDashboardScreen.kt`) as the 💰 **Spend & usage**
link-card. The screen itself is `AiSpendUsageScreen`
(`ui/admin/AiDashboardScreen.kt:872`, help `ai_spend_usage`). On open
it does a one-time OpenRouter pricing refresh (when stale) and then
computes its breakdown via `computeUsageGroups`
(`data/DashboardStats.kt:136`) — heavy enough (per-model `getPricing`)
that it runs off the main thread on open only. Rerank rows fold
`searchUnits × perQueryPrice` into their cost.

- **Total card** — total calls, total tokens, total cost (green).
  `computeUsageGroups` also carries `getPricingStats` (which tiers
  are loaded, with counts) on its result for the dashboard hub.
- **Provider table** — one row per provider (Provider / Calls /
  Tokens / Cost / 🐞), sortable by any column header (default cost,
  descending). The 🐞 opens API Traces scoped to that provider, and
  shows only when that provider has a captured trace.
- Tapping a provider row opens **`AiSpendUsageProviderScreen`**
  (help `ai_usage_provider`) — the per-provider breakdown grouped
  **by call kind**, **by pricing source** (OVERRIDE / OPENROUTER /
  LITELLM / …), and **by model**; each model row links to that
  model's **Model Info** page.
- Delete (🗑) clears all statistics after a confirm dialog.

The 🧮 **Costs tiers** dashboard card opens `AiCostsTierScreen`
(help `ai_costs_tier`) — which pricing tier `getPricing` would pick
for every configured model, counted per `source` via
`computeTierCounts` (`data/DashboardStats.kt`), plus catalog
freshness.

CSV: the layered-cost CSV export/import lives on the **Costs
maintenance** screen below, not here.

## Per-report Costs breakdown

`ui/report/view/Costs.kt` (`CostsViewScreen`, help `costs_view`)
renders a report's spend. The data comes from
`rememberReportCostData(report)`
(`ui/report/manage/view/ContentDisplay.kt:569`), which gathers every
call recorded against the report:

- **Agent rows** (`type = "report"`) — uses each agent's pinned
  `inputCost` / `outputCost` (frozen at the prices in effect when
  the report ran); legacy rows with no pinned split fall back to a
  live `computeInOutCost`.
- **Secondary results** — meta / fan-in / translate / moderation /
  rerank, each carrying its own cost.
- **Icon-gen** (`icon_main`), **language** detect + icon
  (`language`), **model titles** (`model_title`), and the
  alternative-title / alternative-icon fan-outs. Per-call `_alt`
  rows are recorded into `report.iconCalls`; their cost is
  subtracted from the owning aggregate row so totals don't
  double-count (`ContentDisplay.kt:608`).

`CostRow` (`ui/report/manage/view/ContentDisplay.kt:1144`) carries
`type, provider, model, tier, durationMs, in/outTokens,
in/outCents`. Row-level cost is stored in **cents** (Double).

The View screen collapses rows into buckets via `bucketFor(type)`
(`Costs.kt:389`): Reports 📊 / Meta 🧠 / Fan-out 🌀 / Fan-in 🪢 /
Translate 🌍 / Moderation 🚩 / Rerank 🏆 / Icons 🖼 / Model titles 🏷 /
Language 🌐. A hero "💰 Total" card sits above a horizontal-bar list
(bar length = share of total); zero-cost buckets are dropped. A
Buckets ⇄ Models toggle re-rolls the same data by model. Tapping a
bar drills L2 (cross-dimension) → L3 (individual calls, paged).
Find-alternative-title cost is detailed in
[regenerate.md](regenerate.md).

## Manual price overrides

`ModelPricing` overrides are stored **outside** Settings — in
`PricingCache` under the `manual_pricing` map in `pricing_cache.xml`
(`KEY_MANUAL_PRICING`, `data/PricingCache.kt:47`), keyed
`"provider:model"`. Write via `setManualPricing`, read via
`getManualPricing` / `getAllManualPricing`, drop via
`removeManualPricing`.

UI lives in two places:

- **AI Setup → Costs** — per-row CRUD
  (`ui/cruds/costsmanualoverride/`, help `crud_cost_overrides`).
  List → View → Edit / Copy / Add. Copy carries the prices and lets
  the user repoint at another model.
- **Add/Edit Override form** (`AddManualOverrideScreen`,
  `ui/admin/StatisticsScreen.kt:181`, help `cost_override`) —
  provider + model picker, input/output `$/1M tokens` fields. Prices
  are divided by 1 000 000 on save. Shows the *Current:* layered
  price (`getPricingWithoutOverride`) for reference, and supports
  duplicate-mode (👯) to clone an existing override onto a new pair.
  Also reachable pre-filled from **Model Info**
  (`ManualCostOverrideEntryScreen`).

Because the store isn't reactive, the CRUD bumps a refresh tick to
re-read after each write. Overrides round-trip through the backup
zip.

## Costs maintenance screen

`ui/admin/CostsMaintenanceScreen.kt` (Housekeeping → **Costs**, help
`cost_config`) — the two occasional bulk operations:

- **Cleanup** — `cleanupRedundantManualOverrides`
  (`data/PricingCache.kt:204`) drops every override that is dormant
  or redundant: covered by a catalog tier, equal to the built-in
  default, or equal to what `getPricingWithoutOverride` would return
  anyway. Reports the count removed.
- **Layered costs CSV** — `getTierBreakdown`
  (`data/PricingCache.kt:697`) emits one row per active
  `(provider, model)` with every tier's `$/M` price (litellm,
  models.dev, helicone, llm-prices, AA, override, openrouter,
  default). *Export all* / *Export filtered* (filtered drops rows
  already covered by a catalog tier). Fill the two leading
  `new_input_per_million` / `new_output_per_million` columns and
  re-import — only rows with values are written as overrides via
  `setManualPricing`.

## Related docs

- [repositories.md](repositories.md) — the seven external pricing
  sources and their caches.
- [regenerate.md](regenerate.md) — Find-alternative-title call and
  how its cost is recorded.
- [persistent.md](persistent.md) — `usage-stats.json`,
  `pricing_cache.xml`, and the `<filesDir>/pricing/` tier blobs.
- [providers.md](providers.md) — providers, `crossProviderModelList`
  / `pricingFromModelList` self-report flags.
